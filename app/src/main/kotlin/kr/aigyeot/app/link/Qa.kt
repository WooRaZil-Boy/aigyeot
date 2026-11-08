package kr.aigyeot.app.link

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kr.aigyeot.engine.DriverAddWaypoint
import kr.aigyeot.engine.DriverStop
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.EpisodeTrigger
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.SwitchSound
import kr.aigyeot.engine.Via
import kr.aigyeot.engine.noStateClaim
import kr.aigyeot.engine.numbersGrounded
import kr.aigyeot.engine.pickRest
import kr.aigyeot.shared.LlmCry
import kr.aigyeot.shared.LlmFeeding
import kr.aigyeot.shared.LlmNextRest
import kr.aigyeot.shared.LlmResponse
import kr.aigyeot.shared.LlmState
import kr.aigyeot.shared.MethodStat
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.SoundMethod
import kotlin.math.roundToLong

// ④ 상황판(contracts-link 8.8, 명세서 9장). 상태값 만들기, 답 처리, 오프라인 문장. 네트워크는 Link가 부른다.

/**
 * 상태값(1.4 POST /v1/llm 요청 표). [alarmEpisodes]는 이번 세션의 경보 에피소드 수(Room + 아직 저장 전인 지금 에피소드).
 * - `cry.current_min`: 에피소드가 열려 있고 조용함 확인(GAP·CONFIRM) 중이 아니면 에피소드 시작부터 n분째(첫 1분 = 1). 아니면 null.
 * - 소수(거리·온도)는 소수 첫째 자리로 줄인다. 숫자 검사가 이 값으로 견주므로 모델이 그대로 읽을 수 있게 한다.
 * - 부모 노래 이름은 보내지 않는다(별명이 들어 있을 수 있다, 기준 문서 6.5). `label`은 "부모 노래"다.
 */
internal fun llmState(s: EngineState?, now: Long, alarmEpisodes: Int): LlmState {
    if (s == null) return LlmState(LlmCry(null, 0), null, 0, null, null, emptyList(), null)
    val ses = s.session
    val ep = ses?.episode
    val crying = ep != null && ep.trigger == EpisodeTrigger.ALARM && ses.phase != Phase.GAP && ses.phase != Phase.CONFIRM
    val iv = s.profile?.feedingIntervalMin
    val methods = ses?.methods ?: s.methodsNext
    return LlmState(
        cry = LlmCry(if (crying) ((now - ep.startedAt).coerceAtLeast(0) / MINUTE + 1).toInt() else null, alarmEpisodes),
        feeding = s.feedingStart?.takeIf { iv != null }?.let { LlmFeeding(((now - it).coerceAtLeast(0) / MINUTE).toInt(), iv!!) },
        carseatMin = ses?.let { ((now - it.carseatFrom).coerceAtLeast(0) / MINUTE).toInt() } ?: 0,
        cabinTemp = s.car.cabinC?.let(::tenth),
        nextRest = pickRest(s.car.route)?.let { LlmNextRest(it.id, it.name.take(40), tenth(it.distanceKm), it.hasNursingRoom) },
        methodStats = methods.map { m ->
            val tries = s.history.filter { it.method == m }
            MethodStat(sound(m.kind), label(m.kind), tries.count { it.success }, tries.size)
        },
        lastNightWakeups = null,
    )
}

private fun tenth(x: Double) = (x * 10).roundToLong() / 10.0
private fun sound(k: MethodKind) = when (k) {
    MethodKind.WHITE_NOISE -> SoundMethod.WHITE_NOISE
    MethodKind.WOMB -> SoundMethod.WOMB
    MethodKind.LULLABY -> SoundMethod.LULLABY
    MethodKind.SONG -> SoundMethod.PARENT_SONG
}
private fun label(k: MethodKind) = when (k) {
    MethodKind.WHITE_NOISE -> "백색소음"
    MethodKind.WOMB -> "뱃속 소리"
    MethodKind.LULLABY -> "자장가"
    MethodKind.SONG -> "부모 노래"
}

/** 질문 글자: 아이 별명을 '아기'로 바꾸고 200자로 자른다(G2, 1.4). */
internal fun llmQuestion(text: String, nickname: String?): String =
    (if (nickname.isNullOrBlank()) text else text.replace(nickname, "아기")).trim().take(200)

/**
 * 답을 판단 코어 사건으로(8.8 '판단 코어로 돌려주기'). 도구 사건을 먼저, 마지막에 QaReply 글.
 * 글은 숫자 검사를 통과한 LLM 글, 아니면 오프라인 문장. 글이 비면 도구에 맞는 고정 문장(add_waypoint는 글을 그대로 둔다).
 * [methods]는 parent_song을 지금 후보 ③ 곡으로 옮기는 데 쓴다.
 */
internal fun qaEvents(
    r: LlmResponse, listenId: String, at: Long, state: LlmState, question: String, methods: List<Method>,
): Pair<List<Event>, String> {
    val tools = r.toolCalls.mapNotNull { t ->
        when (t.name) {
            "switch_sound" -> when ((t.input["method"] as? JsonPrimitive)?.contentOrNull) {
                "white_noise" -> Method(MethodKind.WHITE_NOISE)
                "womb" -> Method(MethodKind.WOMB)
                "lullaby" -> Method(MethodKind.LULLABY)
                "parent_song" -> methods.firstOrNull { it.kind == MethodKind.SONG && it.songId != null }   // 후보 ③이 없으면 곡 id 없는 음원을 만들지 않는다
                else -> null
            }?.let { SwitchSound(at, it, listenId) }
            "add_waypoint" -> (t.input["rest_id"] as? JsonPrimitive)?.contentOrNull?.let { DriverAddWaypoint(at, it, listenId) }
            "stop_soothing" -> DriverStop(at, Via.TOOL, listenId)
            else -> null
        }
    }
    val noSong = r.toolCalls.any { t -> t.name == "switch_sound" && (t.input["method"] as? JsonPrimitive)?.contentOrNull == "parent_song" } &&
        methods.none { it.kind == MethodKind.SONG && it.songId != null }
    val text = when {
        noSong -> "지금은 소리를 틀 수 없어요."   // 명세서 8장 S20. 틀 곡이 없으니 LLM 글과 상관없이
        r.text.isNotBlank() -> if (numbersGrounded(r.text, stateJson(state)) && noStateClaim(r.text)) r.text else offlineReply(question, state)   // 숫자 검사·상태 단정 검사(C108): 하나라도 걸리면 오프라인 문장
        else -> when (tools.firstOrNull()) {
            is SwitchSound -> "소리를 바꿀게요."
            is DriverStop -> "소리를 멈출게요."
            is DriverAddWaypoint -> r.text
            else -> offlineReply(question, state)
        }
    }
    return tools to text
}

internal fun stateJson(s: LlmState): JsonElement = ProtocolJson.encodeToJsonElement(LlmState.serializer(), s)

/**
 * 오프라인 문장(명세서 9장 키워드 다섯 가지, 17장 L54). LLM이 실패·늦음·숫자 검사 탈락일 때 쓴다.
 * 숫자는 모두 상태값에서 온다. 원인은 추정으로만 말하고 '잠들었어요'·'그쳤어요'를 먼저 말하지 않는다(기준 문서 6.1·6.4).
 */
internal fun offlineReply(question: String, s: LlmState, p: Params = Params()): String {
    val q = question.replace(" ", "")
    fun has(vararg w: String) = w.any { it in q }
    return when {
        has("수유실", "휴게소", "쉼터") -> s.nextRest?.let {
            "${it.name}까지 ${num(it.distanceKm)}km예요. 수유실이 ${if (it.nursingRoom) "있어요" else "없어요"}."
        } ?: "가까운 쉼터 정보가 없어요."
        has("얼마나", "몇분", "오래") ->
            (s.cry.currentMin?.let { "지금 ${it}분째 울고 있어요. " } ?: "지금은 울음이 들리지 않아요. ") +
                "이번 주행에서 ${s.cry.countThisDrive}번 울었어요."
        has("먹였", "먹은", "수유", "배고") -> s.feeding?.let { "마지막 수유 시작부터 ${it.elapsedMin}분 지났어요." } ?: "수유 기록이 없어요."
        has("통해", "잘통", "효과", "뭐가좋") -> s.methodStats.filter { it.attempts >= p.statMinShown }
            .maxByOrNull { (1.0 + it.successes) / (2.0 + it.attempts) }
            ?.let { "${it.label}${josa(it.label)} 틀었을 때 ${it.attempts}번 중 ${it.successes}번 그쳤어요. 소리 덕분인지는 알 수 없어요." }
            ?: "아직 기록이 적어서 몰라요."
        has("왜") -> s.feeding?.let { "마지막 수유 시작부터 ${it.elapsedMin}분, 카시트에 ${s.carseatMin}분째예요. 배고프거나 불편할 수 있어요." }
            ?: "카시트에 ${s.carseatMin}분째예요. 불편할 수 있어요."
        else -> "지금은 답을 가져오지 못했어요."
    }
}

private fun num(x: Double) = if (x % 1.0 == 0.0) x.toLong().toString() else x.toString()

/** 을/를: 마지막 글자에 받침이 있으면 '을'. */
private fun josa(word: String) = word.lastOrNull()?.let { if (it in '가'..'힣' && (it - '가') % 28 != 0) "을" else "를" } ?: "를"
