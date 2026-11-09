package kr.aigyeot.app.link

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kr.aigyeot.engine.DriverAddWaypoint
import kr.aigyeot.engine.DriverStop
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.SwitchSound
import kr.aigyeot.engine.Via
import kr.aigyeot.engine.cryView
import kr.aigyeot.engine.methodLabel
import kr.aigyeot.engine.methodStats
import kr.aigyeot.engine.methodScore
import kr.aigyeot.engine.noStateClaim
import kr.aigyeot.engine.firstSentences
import kr.aigyeot.engine.noDigits
import kr.aigyeot.engine.noLeak
import kr.aigyeot.engine.spokenMin
import kr.aigyeot.engine.readable
import kr.aigyeot.engine.pickRest
import kr.aigyeot.shared.LlmCry
import kr.aigyeot.shared.LlmDiaper
import kr.aigyeot.shared.LlmFeeding
import kr.aigyeot.shared.LlmNextRest
import kr.aigyeot.shared.LlmResponse
import kr.aigyeot.shared.LlmSleep
import kr.aigyeot.shared.LlmState
import kr.aigyeot.shared.MethodStat
import kr.aigyeot.shared.ProtocolJson
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

// ④ 상황판(contracts-link 8.8, 명세서 9장). 상태값 만들기, 답 처리, 오프라인 문장. 네트워크는 Link가 부른다.

/**
 * 상태값(1.4 POST /v1/llm 요청 표). [cryRuns]는 이번 세션에서 감지한 이어진 울음 수로, 판단 코어 `SessionState.cryRuns`에서 온다(C277: Room을 읽지 않는다, C287: 지금 울음과 같은 기준).
 * - `cry`(C288 순서): 판단 코어 `cryView`(C303 ②)를 그대로 옮긴다. 확인할 수 없으면 `unavailable`(C245, 판단 코어가 구간을 보지 않음), 아니고 그침 확인(GAP·CONFIRM) 중이면 `confirming`(C231),
 *   아니고 이어진 경보가 끊기지 않았으면 `current_min` = 그 경보의 처음부터 n분째(첫 1분 = 1, C286). 앞 칸이 서면 뒤 칸은 비운다.
 * - 소수(거리·온도)는 소수 첫째 자리로 줄인다. 숫자 검사가 이 값으로 견주므로 모델이 그대로 읽을 수 있게 한다.
 * - 부모 노래 이름은 보내지 않는다(별명이 들어 있을 수 있다, 기준 문서 6.5). `label`은 "부모 노래"다.
 */
internal fun llmState(s: EngineState?, now: Long, cryRuns: Int = s?.session?.cryRuns ?: 0, zone: ZoneId = ZoneId.systemDefault()): LlmState {
    if (s == null) return LlmState(LlmCry(null, 0, unavailable = true), null, null, null, null, emptyList(), null)
    val ses = s.session
    // C288·C303 ②: 지금 울음은 판단 코어의 울음 상태 함수 하나(상한 인계도 경보가 이어지면 울고 있음. C284의 예외는 C288 감지 기준으로 대체)
    val cry = s.cryView(now)
    val iv = s.profile?.feedingIntervalMin
    val methods = ses?.methods ?: s.methodsNext
    // 폰 돌봄 기록 요약(L61): 24시간보다 오래되면 새 칸을 모두 빼고 last_night_wakeups도 null. 시각 대신 '몇 분 전'만 보낸다
    val c = s.care?.takeIf { it.freshAt(now) }
    fun ago(at: Long) = ((now - at).coerceAtLeast(0) / MINUTE).toInt()
    val sd = c?.takeIf { it.sleepDiaperEnabled }
    // 요약 시각과 지금이 기기 시간대로 다른 날이면 '오늘·지난밤' 값은 어제 것이라 뺀다
    val z = c?.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone   // 요약을 만든 폰의 시간대(C126 ③). 없거나 잘못되면 차 시간대
    val sameDay = c != null && Instant.ofEpochMilli(c.asOf).atZone(z).toLocalDate() == Instant.ofEpochMilli(now).atZone(z).toLocalDate()
    return LlmState(
        cry = LlmCry(cry.runFrom?.let { ((now - it).coerceAtLeast(0) / MINUTE + 1).toInt() }, cryRuns, cry.confirming, cry.unavailable),
        feeding = s.feedingStart?.takeIf { iv != null }?.let { LlmFeeding(((now - it).coerceAtLeast(0) / MINUTE).toInt(), iv!!) },
        carseatMin = ses?.let { ((now - it.carseatFrom).coerceAtLeast(0) / MINUTE).toInt() },
        cabinTemp = s.car.cabinC?.let(::tenth),
        nextRest = pickRest(s.car.route)?.let { LlmNextRest(it.id, it.name.take(40), tenth(it.distanceKm), it.hasNursingRoom) },
        methodStats = methodStats(s.history, methods, now).map { (m, t) -> MethodStat(soundMethod(m.kind), methodLabel(m.kind), t.first, t.second) },   // C320 V17: 28일 창
        lastNightWakeups = c?.nightWakeups?.takeIf { sameDay },
        sleep = sd?.let {
            LlmSleep(
                it.sleepingSince?.let(::ago),
                if (it.sleepingSince != null) null else it.lastSleepEndAt?.let(::ago),   // 진행 중 잠이 있으면 null
                if (it.lastSleepStartAt != null && it.lastSleepEndAt != null) ((it.lastSleepEndAt!! - it.lastSleepStartAt!!).coerceAtLeast(0) / MINUTE).toInt() else null,
                it.todaySleepMin?.takeIf { sameDay },
            )
        },
        diaper = sd?.let { LlmDiaper(it.lastDiaperAt?.let(::ago), it.todayDiaperCount?.takeIf { sameDay }) },
        feedsToday = c?.todayFeedCount?.takeIf { sameDay },
        careAsOfMinAgo = c?.let { ago(it.asOf) },
    )
}

private fun tenth(x: Double) = (x * 10).roundToLong() / 10.0

/** 질문 글자: 아이 별명을 '아기'로 바꾸고 200자로 자른다(G2, 1.4). */
internal fun llmQuestion(text: String, nickname: String?): String =
    (if (nickname.isNullOrBlank()) text else text.replace(nickname, "아기")).trim().take(200)

/**
 * 답을 판단 코어 사건으로(8.8 '판단 코어로 돌려주기'). 도구 사건을 먼저, 마지막에 QaReply 글.
 * 글은 ① 사실 주제가 있으면 주제 문장(LLM 글은 쓰지 않는다) ② 글 검사(숫자 없음·상태 단정 없음)를 통과한 LLM 글
 * ③ why 문장·unknown 문장·도구 고정 문장·오프라인 문장 순(C194).
 * [methods]는 parent_song을 지금 후보 ③ 곡으로 옮기는 데 쓴다.
 */
internal fun qaEvents(
    r: LlmResponse, listenId: String, at: Long, state: LlmState, question: String, methods: List<Method>,
): Pair<List<Event>, String> = qaEventsCare(r, listenId, at, state, question, methods).let { it.first to it.second }

/** [qaEventsCare] 결과. (도구 사건, 글, careUsed)에 C218을 더한다: llm은 글이 LLM 글인가, fallback·fallbackCare는 그 글이 버려질 때 대신 말할 글. */
internal data class QaOut(
    val first: List<Event>, val second: String, val third: Boolean,
    val llm: Boolean = false, val fallback: String = "", val fallbackCare: Boolean = false,
    val factTopics: List<String> = emptyList(),   // C221·C227: 상태값을 쓰는 문장(사실 주제·why)은 글을 비우고 호스트가 큐에서 처리할 때 짠다
    val fallbackTopics: List<String> = emptyList(),   // C227: 버려질 때 말할 대체 답이 상태값을 쓰면 글 대신 주제
)

// C311: 끝 부호를 조각 끝에 남긴다(뒤돌아보기로 부호 뒤에서 나눔). 키워드 표에 '자?'처럼 부호가 든 낱말이 있어 떼면 닿지 못한다
private val QUESTION_PIECE_END = Regex("(?<=[?.!\\uFF1F\\uFF01\\u3002\\uFF0E\\r\\n\\u0085\\u2028\\u2029])")
private val CARE_TOPICS = setOf("feeding", "sleep", "diaper", "why")

/** C221: 오프라인 문장이 사실 주제면 그 주제(호스트가 큐에서 짠다). 아니면 빈 목록. */
internal fun offlineFactTopics(question: String): List<String> = listOfNotNull(questionTopic(question)?.takeIf { it in FACT_TOPICS || it == "why" })

/** C216: 오프라인 문장이 돌봄 값을 쓰는가(QaReply.careUsed). */
internal fun offlineCareUsed(question: String): Boolean = questionTopic(question) in CARE_TOPICS

/** [qaEvents]에 그 글이 돌봄 값을 썼는지(C216)를 더한 것: (도구 사건, 글, careUsed). */
internal fun qaEventsCare(
    r: LlmResponse, listenId: String, at: Long, state: LlmState, question: String, methods: List<Method>,
): QaOut {
    val tools = r.toolCalls.mapNotNull { t ->
        when (t.name) {
            "switch_sound" -> when (val k = methodKindOf((t.input["method"] as? JsonPrimitive)?.contentOrNull)) {   // C303 A19: 전송 글자 → 종류는 Wire 한 표
                MethodKind.SONG -> methods.firstOrNull { it.kind == MethodKind.SONG && it.songId != null }   // 후보 ③이 없으면 곡 id 없는 음원을 만들지 않는다
                null -> null
                else -> Method(k)
            }?.let { SwitchSound(at, it, listenId) }
            "add_waypoint" -> (t.input["rest_id"] as? JsonPrimitive)?.contentOrNull?.let { DriverAddWaypoint(at, it, listenId) }
            "stop_soothing" -> DriverStop(at, Via.TOOL, listenId)
            else -> null
        }
    }
    val noSong = r.toolCalls.any { t -> t.name == "switch_sound" && (t.input["method"] as? JsonPrimitive)?.contentOrNull == "parent_song" } &&
        methods.none { it.kind == MethodKind.SONG && it.songId != null }
    val answered = r.toolCalls.filter { it.name == "answer" }.flatMap { t ->
        ((t.input["topics"] as? JsonArray) ?: emptyList()).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }.distinct().filter { it in TOPICS }.take(3)   // 모든 answer 호출을 응답 순서대로 합치고, 겹치거나 모르는 주제는 버리고 셋까지(C129)
    // answer가 없고 도구도 없으면 질문 키워드로 주제를 고른다(C194). 도구가 있으면 키워드가 도구 안내를 가리지 않게 건너뛴다
    // C239: 모델이 사실 질문을 why·unknown으로 골랐으면 키워드의 사실 주제를 앞세운다('왜'가 든 질문은 why 그대로, 도구가 함께여도 answer가 있으면 적용 C243, answer 없이 도구만이면 C206대로)
    // C255·C257: 주제 고르기는 질문의 조각 규칙([questionFact]) 하나다. 사실 조각이 있으면 사실 문장이고 LLM 글은 쓰지 않는다. 오프라인 길도 같은 [questionTopic]
    val kw = if (tools.isEmpty() || answered.isNotEmpty()) questionFact(question) else null
    val topics = if (kw != null && answered.none { it in FACT_TOPICS }) listOf(kw) + answered.filter { it != "why" && it != "unknown" }.take(2)
        else answered.ifEmpty { if (tools.isEmpty()) listOfNotNull(questionTopic(question)) else emptyList() }
    val facts = topics.filter { it in FACT_TOPICS }
    val llmText = r.text.trim().takeIf { !(answered.isEmpty() && tools.isNotEmpty()) && it.isNotEmpty() && noDigits(it) && noStateClaim(it) && noLeak(it) }   // answer 없이 도구만 오면 LLM 글을 쓰지 않는다(C206)
    val text = when {
        noSong -> "지금은 소리를 틀 수 없어요."   // 명세서 8장 S20. 틀 곡이 없으니 답 주제와 상관없이
        facts.isNotEmpty() -> ""   // 사실 주제는 차가 정한 문장만 말한다(C194). 글은 호스트가 큐에서 처리할 때 짠다(C221)
        llmText != null -> llmText
        "why" in topics -> ""   // C227: 호스트가 짠다(composeTopics)
        "unknown" in topics -> "그건 제가 알 수 없어요."
        else -> when (tools.firstOrNull()) {
            is SwitchSound -> "소리를 바꿀게요."
            is DriverStop -> "소리를 멈출게요."
            is DriverAddWaypoint -> ""
            else -> offlineReply(question, state)
        }
    }
    val care = when {
        noSong -> false
        facts.isNotEmpty() -> facts.take(2).any { it in CARE_TOPICS }   // fallbackReply가 말하는 앞 두 주제
        llmText != null || "why" in topics -> true
        else -> false
    }
    val composeTopics = if (noSong) emptyList() else facts.ifEmpty { if (llmText == null && "why" in topics) listOf("why") else emptyList() }
    val useLlm = !noSong && facts.isEmpty() && llmText != null
    // C218: LLM 글이 버려질 때 같은 질문에 대신 말할 글(주제 문장은 facts에서 이미 LLM 글이 아니다)
    var fbTopics = emptyList<String>()
    val (fb, fbCare) = when {
        "why" in topics -> { fbTopics = listOf("why"); "" to true }   // C227: 대체 답도 호스트가 짠다
        "unknown" in topics -> "그건 제가 알 수 없어요." to false
        else -> when (tools.firstOrNull()) {
            is SwitchSound -> "소리를 바꿀게요." to false
            is DriverStop -> "소리를 멈출게요." to false
            is DriverAddWaypoint -> "" to false
            else -> {
                val t = questionTopic(question)
                if (t != null) fbTopics = listOf(t)
                (if (t != null) "" else offlineReply(question, state)) to offlineCareUsed(question)
            }
        }
    }
    return QaOut(tools, text, care, useLlm, if (useLlm) fb else "", useLlm && fbCare, composeTopics, if (useLlm) fbTopics else emptyList())
}

internal fun stateJson(s: LlmState): JsonElement = ProtocolJson.encodeToJsonElement(LlmState.serializer(), s)

private val FACT_TOPICS = setOf("feeding", "sleep", "diaper", "cry", "carseat", "cabin_temp", "next_rest", "sound_stats")
private val TOPICS = FACT_TOPICS + setOf("why", "unknown")

/** 값 조각을 ", "로 잇고 마지막 조각만 끝맺는다. 조각은 (이어질 때 꼴, 끝날 때 꼴). 비면 null. */
private fun chain(parts: List<Pair<String, String>>): String? =
    if (parts.isEmpty()) null else parts.dropLast(1).joinToString("") { it.first + ", " } + parts.last().second

/**
 * 주제 하나의 한 문장과, 돌봄 기록 칸을 썼는지(C172). 질문 낱말로 조각을 고르지 않고 그 주제의 값을 모두 한 문장에 담는다.
 * 값이 없는 부분은 뺀다.
 */
private fun sentence(topic: String, s: LlmState, p: Params): Pair<String, Boolean> = when (topic) {
    "feeding" -> {
        val parts = listOfNotNull(
            s.feeding?.let { "마지막 수유 시작부터 ${spokenMin(it.elapsedMin)} 지났고" to "마지막 수유 시작부터 ${spokenMin(it.elapsedMin)} 지났어요" },
            s.feedsToday?.takeIf { it > 0 }?.let { "기록상 오늘 ${it}번 먹었고" to "기록상 오늘 ${it}번 먹었어요" },   // 0번은 뺀다(C179)
        )
        // 횟수가 null이고 경과도 없으면 확인할 수 없다. 유효한 요약의 0이면 기록 없음이고 돌봄 기록을 쓴 것으로 친다(C186)
        (chain(parts)?.plus(".") ?: if (s.feedsToday == null) "수유 기록을 확인할 수 없어요." else "기록상 오늘 수유 기록이 없어요.") to (s.feedsToday != null)
    }
    "sleep" -> {
        val z = s.sleep
        val out = mutableListOf<Pair<String, String>>()
        when {
            z?.sleepingSinceMin != null -> out += "기록상 ${spokenMin(z.sleepingSinceMin!!)} 전에 시작한 잠이 아직 끝나지 않았고" to "기록상 ${spokenMin(z.sleepingSinceMin!!)} 전에 시작한 잠이 아직 끝나지 않았어요"
            z?.wokeMinAgo != null -> out += "기록상 ${spokenMin(z.wokeMinAgo!!)} 전에 잠이 끝났고" to "기록상 ${spokenMin(z.wokeMinAgo!!)} 전에 잠이 끝났어요"
        }
        if (z != null && z.sleepingSinceMin == null && z.wokeMinAgo != null) z.lastSleepMin?.let { out += "마지막 잠은 ${spokenMin(it)}" to "마지막 잠은 ${spokenMin(it)}이에요" }
        z?.todaySleepMin?.takeIf { it > 0 }?.let { out += if (out.isEmpty()) "오늘 잠은 모두 ${spokenMin(it)}" to "오늘 잠은 모두 ${spokenMin(it)}이에요" else "오늘은 모두 ${spokenMin(it)}" to "오늘은 모두 ${spokenMin(it)}이에요" }
        s.lastNightWakeups?.takeIf { it > 0 }?.let { out += if (out.isEmpty()) "지난밤에 ${it}번 깼고" to "지난밤에 ${it}번 깼어요" else "지난밤에는 ${it}번 깼고" to "지난밤에는 ${it}번 깼어요" }
        val sleepKnown = z?.todaySleepMin != null || s.lastNightWakeups != null   // 값이 모두 null이면(날짜 바뀜·끔) 0이 아니라 모르는 것(C186)
        // 돌봄 주제 문장은 늘 '기록상'으로 시작한다. 값이 모두 0이면 기록 없음(C179), 칸 자체가 null이면 확인할 수 없음(C186)
        (chain(out)?.plus(".")?.let { if (it.startsWith("기록상")) it else "기록상 $it" } ?: if (!sleepKnown) "잠 기록을 확인할 수 없어요." else "기록상 오늘 잠 기록이 없어요.") to (out.isNotEmpty() || sleepKnown)
    }
    "diaper" -> {
        val d = s.diaper
        val parts = listOfNotNull(
            d?.minAgo?.let { "기록상 마지막 기저귀는 ${spokenMin(it)} 전이고" to "기록상 마지막 기저귀는 ${spokenMin(it)} 전이에요" },
            d?.todayCount?.takeIf { it > 0 }?.let { if (d.minAgo == null) "기록상 오늘 기저귀는 ${it}번이고" to "기록상 오늘 기저귀는 ${it}번이에요" else "오늘 ${it}번이고" to "오늘 ${it}번이에요" },
        )
        (chain(parts)?.plus(".") ?: if (d?.todayCount == null) "기저귀 기록을 확인할 수 없어요." else "기록상 오늘 기저귀 기록이 없어요.") to (parts.isNotEmpty() || d?.todayCount != null)
    }
    else -> plainTopic(topic, s, p)
}

/** 기록이 60분보다 오래됐으면 문장 속 첫 '기록상'을 "{n}분 전 기록 기준으로"로 바꾼다(C179). */
private fun asOf(t: String, s: LlmState) = s.careAsOfMinAgo?.takeIf { it > 60 }?.let { t.replaceFirst("기록상", "${spokenMin(it)} 전 기록 기준으로") } ?: t

/** 주제 문장 전체(8.8 '주제 문장' 표). 기록이 60분보다 오래됐으면 문장 속 첫 '기록상'을 시점으로 바꾼다(L61, C179, C197). */
internal fun topicSentence(topic: String, s: LlmState, p: Params = Params()): String = asOf(sentence(topic, s, p).first, s)

/**
 * 대체 답 짜기(C172, C164를 바꿈). 주제마다 값을 모두 담은 한 문장이고 앞 두 주제까지 말한다(두 문장 이내).
 * 주제가 하나면 그 주제 문장(why·sound_stats처럼 문장이 둘인 주제는 두 문장까지). 최종 글에서 처음 나오는 '기록상'을 시점으로 한 번 바꾼다(C197).
 */
internal fun fallbackReply(topics: List<String>, s: LlmState, p: Params = Params()): String {
    val ts = topics.take(2)
    // C224: 주제 문장을 단위로 센다. 쉼터 이름 같은 값이 든 글을 문장 부호로 다시 자르지 않는다(문장이 둘인 고정 문장 주제만 앞 한 문장).
    return asOf(ts.joinToString(" ") { t -> sentence(t, s, p).first.let { if (ts.size > 1 && t in TWO_SENTENCE_TOPICS) firstSentences(it, 1) else it } }, s)
}

/** 고정 문장이 둘인 주제: 두 주제를 함께 말할 때는 앞 문장만 쓴다. */
private val TWO_SENTENCE_TOPICS = setOf("why", "sound_stats")

// "잠깐"은 잠 질문이 아니다. 낱말은 공백을 지우기 전에 지운다(C247: "잠 시간"이 "잠시간"이 되지 않게). 띄어쓰기를 없애 "몇 번"을 "몇번"으로 본다
private fun normQuestion(q: String) = readable(q).replace("잠깐", "").replace("잠잠", "").replace("잠시", "").replace(" ", "")

private fun plainTopic(topic: String, s: LlmState, p: Params): Pair<String, Boolean> = when (topic) {
    "cry" -> if (s.carseatMin == null) "지금은 울음을 확인할 수 없어요." to false  // C329: 세션이 없으면 이번 주행 횟수가 없다(why의 C234와 같은 기준)
    else ((if (s.cry.unavailable) "지금은 울음을 확인할 수 없고, " else s.cry.currentMin?.let { "지금 ${spokenMin(it)}째 울고 있고, " } ?: if (s.cry.confirming) "울음이 그쳤는지 확인하고 있고, " else "지금은 울음이 들리지 않고, ") +
        "이번 주행에서 ${s.cry.countThisDrive}번 울었어요.") to false
    "carseat" -> (s.carseatMin?.let { "카시트에 ${spokenMin(it)}째예요." } ?: "카시트 시간은 출발한 뒤부터 재요.") to false
    "cabin_temp" -> (s.cabinTemp?.let { "실내 온도는 ${num(it)}도예요." } ?: "실내 온도를 알 수 없어요.") to false
    "next_rest" -> (s.nextRest?.let {
        "${it.name}까지 ${num(it.distanceKm)}km이고 수유실이 ${if (it.nursingRoom) "있어요" else "없어요"}."
    } ?: "경유지로 넣을 수 있는 가까운 쉼터 정보가 없어요.") to false
    "sound_stats" -> (s.methodStats.filter { it.attempts >= p.statMinShown }
        .maxByOrNull { methodScore(it.successes, it.attempts) }
        ?.let { "${it.label}${eul(it.label)} 틀었을 때 ${it.attempts}번 중 ${it.successes}번 그쳤어요. 소리 덕분인지는 알 수 없어요." }
        ?: "아직 기록이 적어서 몰라요.") to false
    "why" -> {
        val woke = s.sleep?.wokeMinAgo
        val seat = when {   // C234: 세션이 없으면 카시트 부분을 뺀다
            s.carseatMin != null && woke != null -> "카시트에 ${spokenMin(s.carseatMin!!)}째이고, 기록상 ${spokenMin(woke)} 전에 깼어요."
            s.carseatMin != null -> "카시트에 ${spokenMin(s.carseatMin!!)}째예요."
            woke != null -> "기록상 ${spokenMin(woke)} 전에 깼어요."
            else -> null
        }
        (s.feeding?.let { "마지막 수유 시작부터 ${spokenMin(it.elapsedMin)}${if (seat != null) ", $seat" else "이에요."} 배고프거나 불편할 수 있어요." }
            ?: "${seat?.plus(" ") ?: ""}불편할 수 있어요.") to (woke != null)
    }
    "unknown" -> "그건 제가 알 수 없어요." to false
    else -> "지금은 답을 가져오지 못했어요." to false
}

/**
 * 오프라인 문장(명세서 9장 키워드, 17장 L54·C127). LLM이 실패·늦음·도구 없음일 때 쓴다. 키워드로 주제를 골라 주제 문장을 말한다.
 * 원인은 추정으로만 말하고 '잠들었어요'·'그쳤어요'를 먼저 말하지 않는다(기준 문서 6.1·6.4).
 */
internal fun offlineReply(question: String, s: LlmState, p: Params = Params()): String =
    questionTopic(question)?.let { fallbackReply(listOf(it), s, p) } ?: "지금은 답을 가져오지 못했어요."   // C172: 네트워크 실패·시간 초과 길도 여기를 거치므로 같은 짜기

/**
 * 질문의 사실 주제(C255·C257·C263). 질문을 문장 끝 부호로 나눈 조각마다 오프라인 표([offlineTopic])의 순서 그대로 보고 사실 주제가 나오는 첫 조각의 주제(조각 안에서는 표 순서의 첫 하나).
 * 표에서 '왜'(9행)보다 앞인 사실 주제(1~8행)는 같은 조각에 '왜'가 있어도 사실이고("왜 우는지랑 마지막 수유가 언제인지" → feeding), 뒤인 울음 동사(10행)는 '왜'가 없는 조각에서만 사실이다
 * ("언제 먹였어? 왜 울어?"는 feeding, "왜 울어?"·"왜 우는지 그리고 얼마나 울었는지"는 없음 → LLM 글 허용). 없으면 null.
 */
internal fun questionFact(question: String): String? =
    java.text.Normalizer.normalize(question, java.text.Normalizer.Form.NFKC).split(QUESTION_PIECE_END).firstNotNullOfOrNull { p -> offlineTopic(p)?.takeIf { it in FACT_TOPICS } }

/** 질문의 주제: 사실 조각이 있으면 그 주제, 아니면 질문 전체의 키워드 주제(why 또는 null). 본 길·오프라인 길·3초 대체 길이 모두 이 함수 하나를 쓴다. */
internal fun questionTopic(question: String): String? = questionFact(question) ?: offlineTopic(question)

/** 질문 키워드로 고른 주제(명세서 9장 표). 없으면 null. 조각 규칙은 [questionTopic]. */
internal fun offlineTopic(question: String): String? {
    val q = normQuestion(question)
    return TOPIC_KEYWORDS.firstOrNull { (_, ws) -> ws.any { it in q } }?.first
}

/** 키워드 표(명세서 9장). 순서가 우선순위. C311: 시험이 낱말마다 조각 나누기를 거친 뒤에도 닿는지 표 전체를 돈다. */
internal val TOPIC_KEYWORDS: List<Pair<String, List<String>>> = listOf(
    "next_rest" to listOf("수유실", "휴게소", "쉼터", "쉴곳", "쉬는곳"),
    "diaper" to listOf("기저귀"),
    "sleep" to listOf("잤", "잠", "깼", "재워", "재울", "자는", "자?", "자니", "자나"),
    "carseat" to listOf("카시트"),
    "cabin_temp" to listOf("온도", "덥", "춥", "추워", "더워", "더운", "더웠", "추운", "추웠"),
    "feeding" to listOf("먹였", "먹여", "먹일", "먹은", "먹었", "먹을", "수유", "배고", "분유", "젖"),
    "sound_stats" to listOf("통해", "잘통", "통했", "효과", "뭐가좋"),
    "cry" to listOf("얼마나", "몇분", "몇번", "오래"),   // 울음 양: '왜'보다 앞(C270)
    "why" to listOf("왜"),
    "cry" to listOf("울어", "울음", "울고", "울었", "우니", "우는", "울지", "울기", "울면", "울까", "그쳤", "그쳐", "그치"),   // 활용형만. '울' 한 글자는 '겨울'을 잡는다(C204)
)

private fun num(x: Double) = if (x % 1.0 == 0.0) x.toLong().toString() else x.toString()
