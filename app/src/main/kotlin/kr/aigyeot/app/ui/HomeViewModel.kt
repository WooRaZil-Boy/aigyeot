package kr.aigyeot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kr.aigyeot.app.exec.HeardText
import kr.aigyeot.app.exec.Said
import kr.aigyeot.app.host.EventSink
import kr.aigyeot.app.link.Link
import kr.aigyeot.app.link.LinkConn
import kr.aigyeot.app.link.LinkStatus
import kr.aigyeot.app.link.methodKindOf
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.retryOnDbError
import kr.aigyeot.engine.hears
import kr.aigyeot.app.store.db.ControlAct
import kr.aigyeot.app.store.db.OkControl
import kr.aigyeot.app.ui.car3d.Callout3d
import kr.aigyeot.app.ui.car3d.Light3d
import kr.aigyeot.app.ui.car3d.Seat3d
import kr.aigyeot.app.ui.car3d.Stage3d
import kr.aigyeot.app.ui.car3d.View3d
import kr.aigyeot.engine.AskButton
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Stage
import kr.aigyeot.engine.Step
import kr.aigyeot.engine.TryResult
import kr.aigyeot.engine.ambientDimmed
import kr.aigyeot.engine.canAsk
import kr.aigyeot.engine.methodLabel
import kr.aigyeot.shared.EpisodeResult
import kr.aigyeot.shared.HandoffKind
import kr.aigyeot.shared.TimelineEpisode
import kr.aigyeot.engine.toldShown
import kotlin.math.roundToInt

// 홈 화면 투영(디자인 v6, 계약 차 2.5·명세 17장 C378). 판단 코어 상태·연결·시계·차가 말한 문장에서 [homeUi]가 한 번에 계산한다.
// 화면(HomeScreen·DriveScreen)은 그리기만 한다. 엔진 상태·판단 규칙은 바꾸지 않는다.

/** 눈썹(상태 이름 한 줄): 점 색이 상태를 말한다. [live]면 정차 중에 점이 숨쉰다(실제로 듣는 중일 때만). */
data class Eyebrow(val text: String, val tone: Tone, val live: Boolean = false)
enum class Tone { MUTED, ACCENT, CHILD, METHOD }

/** 달래기 단계 막대 하나의 상태(시안 track: done·now·fail·빈칸). */
enum class StepMark { NONE, NOW, DONE, FAIL }

/** 맥락 카드 하나(그 단계 것). */
sealed interface HomeCard {
    /** 달래기 단계: 막대 넷(1차·듣기 틈·2차·쉼터)과 지금 단계 시간([from]부터 [totalMs]까지, 화면이 1초마다 센다). */
    data class Track(val marks: List<StepMark>, val subs: List<String>, val from: Long?, val totalMs: Long?) : HomeCard
    /** 쉼터: 이름·부가('수유실 · 7km')·경유지로 넣었는가. */
    data class Rest(val name: String, val sub: String?, val added: Boolean) : HomeCard
    /** 정차 중 마지막 에피소드 흐름(시작 시각 + 차례). */
    /** [head]: 제목 머리(episodeHead: 울음 경보 '울음', 운전자 요청 '소리 바꾸기 요청'). */
    data class Episode(val at: Long, val head: String, val flow: List<FlowItem>) : HomeCard
    /** 물어보기: 듣는 중이면 마이크, 들은 질문, 답. */
    data class Ask(val listening: Boolean, val question: String?, val answer: String?) : HomeCard
}
data class FlowItem(val text: String, val tone: Tone)

/** 마지막 수유 카드. [start] null이면 기록 없음. [intervalMin]은 평소 간격(없으면 막대 없음). */
data class FeedCard(
    val start: Long?, val intervalMin: Int?,
    /** 지난 시간 'h:mm'(1분 미만 '방금'), 1분 미만이면 [fresh]('지난 시간' 꼬리 없음). */
    val ago: String = "", val fresh: Boolean = false,
    /** 막대(간격 × [FEED_BAR_SPAN]까지)에 찬 몫과 간격 시작 눈금 자리(판단 코어 windowStartExpected와 같은 값). 간격이 없으면 null. */
    val fill: Float? = null, val windowMark: Float? = null,
)

/** 수유 막대 끝 = 평소 간격의 1.25배(시안 feedCard). */
internal const val FEED_BAR_SPAN = 1.25

/** 홈 시계 눈금(수유 '방금'·'h:mm', 오늘 하루 지금 선을 다시 계산하는 간격). */
private const val TICK_MS = 10_000L

/** 마지막 수유 카드 값(시안 feedCard). 간격 시작 눈금은 판단 코어의 수유 시간대 시작(windowStartExpected, Params.feedingWindowStart)을 그대로 쓴다. */
internal fun feedCard(start: Long?, intervalMin: Int?, now: Long, p: Params = Params()): FeedCard {
    if (start == null) return FeedCard(null, intervalMin)
    val ago = now - start
    val iv = intervalMin?.takeIf { it > 0 }
    val full = iv?.let { it * 60_000.0 * FEED_BAR_SPAN }
    return FeedCard(
        start, intervalMin, agoText(ago), ago < 60_000,
        fill = full?.let { (ago / it).toFloat().coerceIn(0f, 1f) },
        windowMark = full?.let { f -> kr.aigyeot.engine.windowStartExpected(start, iv, p)?.let { ((it - start) / f).toFloat() } },
    )
}

/** 버튼: 출발 전(아기 탔어요 영웅) / 그 밖(물어보기 + 지금 먹이기). [askHero]면 물어보기가 영웅(정차 중). */
data class Cta(val board: Boolean, val askHero: Boolean)

/** 머리 알약: 휴대폰(연결 문장 그대로, 8.6)·실내 온도. */
data class Pills(val phone: String?, val phoneLive: Boolean, val tempC: Int?)

/** 주행 중 화면: 큰 기호 하나 + 한 줄(statusText) + '응 / 아니' 안내. */
data class DriveSay(val symbol: Symbol, val ask: Boolean)
enum class Symbol { EAR, BABY, PIN, LULLABY, NOISE, WOMB, SONG }

data class HomeUi(
    val status: String,
    val eyebrow: Eyebrow,
    val sub: String?,
    val said: Said?,
    val card: HomeCard?,
    val feed: FeedCard,
    val showDay: Boolean,
    val cta: Cta,
    val confirmShown: Boolean,
    val askEnabled: Boolean,
    val pausesWarning: Boolean,
    val link: LinkStatus,
    val pills: Pills,
    val stage: Stage3d,
    val drive: DriveSay,
    val ambientDimmed: Boolean,
    /** 출발 전·아기 탔어요의 '오늘 하루'(아는 값만). */
    val day: DayChartData? = null,
)

/**
 * '물어보기' 카드의 질문 창 고르기(VM이 들고 있는 문맥). 누른 뒤 10초 안에 처음 열린 질문 창, 또는 질문을 실제로 들은 창(안내 뒤 자동 듣기 Q1 포함)을 고른다.
 * 자동 듣기 창이 듣기만 하고 끝나면 고르지 않는다(방금 말한 문장·단계 카드를 지우지 않게).
 * 고른 때의 돌봄 문맥(방·요약 있음)이 바뀌면(owner 해제·방 초기화로 요약이 지워짐, 연결 계약) 옛 질문·답 카드를 내린다(P5).
 */
internal class AskPick {
    private var id: String? = null
    private var askAt: Long? = null
    private var pickedAt = Long.MIN_VALUE
    private var room: String? = null
    private var hadCare = false
    fun pressed(now: Long) { askAt = now }
    /**
     * [clearedAt]: 마지막 기록 지우기 시각(Store.clearedAt). 판단 코어가 ④ 창·말 안 한 답을 버리듯(수명 표 CLEAR) 그 전에 고른 질문·답 카드도 내린다.
     */
    fun pick(s: EngineState?, heard: HeardText?, now: Long, clearedAt: Long = 0L): String? {
        if (id != null && (s?.careRoom != room || (hadCare && s?.care == null) || clearedAt > pickedAt)) { id = null; pickedAt = now }   // 내린 뒤 그 전에 들은 글로 다시 고르지 않는다
        if (clearedAt > pickedAt) pickedAt = clearedAt
        val w = s?.speech?.qa?.listenId
        when {
            w != null && w != id && askAt?.let { now - it < 10_000 } == true -> select(w, s, now)
            heard != null && heard.listenId != id && heard.at > pickedAt -> select(heard.listenId, s, now)
        }
        return id
    }
    private fun select(w: String, s: EngineState?, now: Long) { id = w; askAt = null; pickedAt = now; room = s?.careRoom; hadCare = s?.care != null }
}

/** 세션 하나의 Room 읽기 값. [of]는 지금 세션의 값일 때만 돌려준다. */
internal data class Tagged<T>(val sessionId: String?, val value: T) {
    fun of(sid: String?): T? = value.takeIf { sid != null && sid == sessionId }
}

/** 오늘 차에서 한 일(Room): 주행 구간과 그 안의 경보 시각. 시뮬레이션 세션은 뺀다. */
data class CarSpan(val from: Long, val to: Long?, val alarms: List<Long>)

/** 화면 단계 열쇠: 바뀌면 열이 새로 들어오고(정차) 주행 중 화면이 한 번 페이드한다. 차가 말한 문장은 이 열쇠가 그대로인 동안만 보인다. */
fun stepKey(s: EngineState?): String {
    val x = s?.session ?: return if (s?.ign?.reserved == true || s?.car?.micRunning == true) "on" else "off"
    val st = x.stage
    val started = (st as? Stage.Attempt)?.play?.startedAt != null
    // ④ 질문 창은 넣지 않는다: 안내 뒤 자동으로 열리는 듣기 창(Q1)이 방금 말한 문장을 지우지 않게(물어보기 카드는 따로 고른다)
    return "${x.id}:${st.phase}:${st.n}:$started:${x.episode?.waypointAdded}:${x.addedRestIds.size}:${s.car.gear == Gear.P}"
}

/**
 * [lastQa]: 가장 최근에 열린 ④ 질문 창의 listenId(그 창이 받은 질문과 그 뒤 답만 카드에 보인다). [done]: 이 세션에 실제로 해낸 제어 api(control_log 성공 줄).
 */
fun homeUi(
    s: EngineState?, now: Long, link: LinkStatus, said: Said?, heard: HeardText?, stepFrom: Long, last: TimelineEpisode?,
    done: Set<OkControl> = emptySet(), carDay: List<CarSpan> = emptyList(), zone: java.time.ZoneId = TimeText.zone, lastQa: String? = null, p: Params = Params(),
): HomeUi {
    val x = s?.session
    val parked = s?.car?.gear == Gear.P
    val qa = s?.speech?.qa
    val card = card(s, said, heard, stepFrom, last, lastQa, p)
    val shownSaid = said?.takeIf { it.at >= stepFrom && it.sid != Sid.QA }   // ④ 답은 물어보기 카드가 보인다
    return HomeUi(
        status = statusText(s),
        eyebrow = eyebrow(s, p),
        sub = sub(s, now),
        said = shownSaid,
        card = card,
        feed = feedCard(s?.feedingStart, s?.profile?.feedingIntervalMin, now, p),
        showDay = x == null,
        // 마이크가 멈췄으면 세션·예약 중이어도 '아기 탔어요'로 다시 켤 수 있게(지금 앱 동작, 명세 11장)
        cta = Cta(board = (x == null && s?.ign?.reserved != true) || s?.car?.micRunning != true, askHero = parked || qa != null),
        confirmShown = s?.ign?.nursingStop?.confirmShown == true,
        askEnabled = askEnabled(s),
        pausesWarning = x != null && s.car.pauses.isNotEmpty(),
        link = link,
        pills = pills(link, s?.car?.cabinC),
        stage = stage(s, done),
        drive = drive(s),
        ambientDimmed = s?.ambientDimmed == true,
        day = if (x == null) dayChart(s, carDay, now, zone) else null,
    )
}

internal const val MIC_OFF = "아직 듣지 못하고 있어요"

/** 지금 단계의 재생(자동 시도·요청)이 어떤 상태인가. 상태 문장과 3D 소리 결이 이 한 값을 본다. */
internal enum class Sound { NONE, PENDING, PLAYING, PAUSED }

internal fun soundNow(x: kr.aigyeot.engine.SessionState): Sound {
    val pl = when (val st = x.stage) { is Stage.Attempt -> st.play; is Stage.Wait -> st.play; else -> null } ?: return Sound.NONE
    return when { pl.startedAt == null -> Sound.PENDING; pl.mode == kr.aigyeot.engine.PlayMode.PAUSE -> Sound.PAUSED; else -> Sound.PLAYING }
}

/**
 * 소리가 뒷자리에서 나간다고 확실한가. `session.output`은 이번 세션에 앞좌석 폴백이 한 번이라도 있었는지(누적, C85)라
 * 폴백이 있었으면 지금 재생의 자리를 모른다: 그때는 자리를 말하지 않고 뒷자리 소리 결도 그리지 않는다.
 */
internal fun rearSure(x: kr.aigyeot.engine.SessionState) = x.output != kr.aigyeot.engine.OutputRoute.DEFAULT_FALLBACK

internal fun statusText(s: EngineState?): String {
    val x = s?.session ?: return if (s?.ign?.reserved == true) "출발하면 듣기 시작해요" else "출발 전이에요"
    return when (x.phase) {
        // 판단 코어가 소리를 보지 않는 동안(마이크 꺼짐·입력 끊김 판정 멈춤)은 듣는다고 쓰지 않는다(S17 문장, 계약 차 5장 1e·5.3). hears()는 판단 코어와 같은 조건
        Phase.LISTENING -> if (s.hears()) "아기 소리를 듣고 있어요" else MIC_OFF
        // 소리 사실은 soundNow 한 곳에서(3D 소리 결과 같은 값): 잠시 멈춤이면 튼다고 쓰지 않고, 뒷자리라고 확실할 때만 '뒷자리에서'
        Phase.ATTEMPT -> when (soundNow(x)) {
            Sound.PENDING -> "소리를 준비하고 있어요"   // C382: 재생 시작 전(포커스 거절이면 틀지 않는다)에는 튼다고 쓰지 않는다
            Sound.PAUSED -> "잠시 소리를 멈췄어요"
            else -> if (rearSure(x)) "뒷자리에서 소리를 틀고 있어요" else "소리를 틀고 있어요"
        }
        Phase.GAP, Phase.CONFIRM -> if (s.hears()) "그쳤는지 보고 있어요" else MIC_OFF
        // C356: HANDOFF는 쉼터 안내 없이도 들어간다(P 정차·10분 금지·이미 넣은 경유지·쉼터 없음). 단계가 보장하는 사실만 쓴다
        Phase.HANDOFF -> "자동 달래기를 멈췄어요"
        Phase.WAIT -> "잠시 기다리고 있어요"
    }
}

/** 눈썹 이름(시안 화면 라벨, IMPLEMENT 6장): 듣기 꺼짐·듣기 켜짐·듣는 중·정차 중·울음 감지 · n초·n차 · 방법·듣기 틈 · n초·사람에게 넘김·경유지 추가. */
internal fun eyebrow(s: EngineState?, p: Params = Params()): Eyebrow {
    val x = s?.session ?: return if (s?.car?.micRunning == true) Eyebrow("듣기 켜짐", Tone.ACCENT, live = true) else Eyebrow("듣기 꺼짐", Tone.MUTED)
    val parked = s.car.gear == Gear.P
    return when (val st = x.stage) {
        is Stage.Attempt -> {
            val m = st.play.method
            // 재생 시작 전 경보 지연은 단계 안 PendingAlarm에 있다(PlaybackStarted가 에피소드로 옮긴다, C36)
            if (st.play.startedAt == null) Eyebrow("울음 감지" + ((st.alarm?.let { it.alarmAt - it.delayFromAt } ?: x.episode?.alarmDelayMs)?.let { " · " + seconds1(it) } ?: ""), Tone.CHILD)
            else Eyebrow("${st.n}차 · ${methodLabel(m.kind)}", Tone.METHOD)
        }
        is Stage.Gap, is Stage.Confirm -> Eyebrow("듣기 틈" + (st.window?.let { " · ${((it.end - it.from) / 1000.0).roundToInt()}초" } ?: ""), Tone.ACCENT, live = st.window != null && s.hears())
        is Stage.Handoff -> if (x.episode?.waypointAdded == true) Eyebrow("경유지 추가", Tone.ACCENT) else Eyebrow("사람에게 넘김", Tone.CHILD)
        is Stage.Wait -> st.play?.let { Eyebrow(methodLabel(it.method.kind), Tone.METHOD) } ?: listening(parked, s)
        Stage.Listening -> listening(parked, s)
    }
}

private fun listening(parked: Boolean, s: EngineState) =
    if (parked) Eyebrow("정차 중", Tone.MUTED) else if (s.hears()) Eyebrow("듣는 중", Tone.ACCENT, live = true) else Eyebrow("듣기 꺼짐", Tone.MUTED)

/** 감지 지연 "2.4초"(소수 한 자리). */
internal fun seconds1(ms: Long) = "%.1f초".format(ms / 1000.0)

/** 상태 한 줄 아래 작은 글(듣는 중·주행): '주행 32분째 · 실내 24°'. 아는 값만. */
internal fun sub(s: EngineState?, now: Long): String? {
    val x = s?.session ?: return null
    if (x.phase != Phase.LISTENING || s.car.gear == Gear.P) return null
    val drive = x.leftParkAt?.let { ((now - it) / 60_000).takeIf { m -> m >= 0 } }
    val parts = listOfNotNull(drive?.let { "주행 ${it}분째" }, s.car.cabinC?.let { "실내 ${it.roundToInt()}℃" })
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun pills(link: LinkStatus, cabinC: Double?): Pills {
    val phone = when (link.conn) {
        LinkConn.NONE -> null
        LinkConn.CONNECTED -> if (link.phones.isNotEmpty()) "휴대폰 ${link.phones.size}대" else connText(link.conn, false)
        else -> connText(link.conn, link.phones.isNotEmpty())
    }
    return Pills(phone, link.conn == LinkConn.CONNECTED && link.phones.isNotEmpty(), cabinC?.roundToInt())
}

private fun card(s: EngineState?, said: Said?, heard: HeardText?, stepFrom: Long, last: TimelineEpisode?, lastQa: String?, p: Params): HomeCard? {
    val qa = s?.speech?.qa
    // ④ 카드는 '물어보기'로 연 질문 창 하나의 것만(P5 문맥): 안내 뒤 자동 듣기 창은 카드가 아니다. 열린 창이면 그 창이 받은 질문, 닫힌 뒤면 그 창의 질문과 그 뒤 이 단계에서 말한 답
    if (qa != null && qa.listenId == lastQa) return HomeCard.Ask(listening = qa.step == kr.aigyeot.engine.QaStep.HEARING, question = heard?.takeIf { it.listenId == qa.listenId }?.text, answer = null)
    val q = heard?.takeIf { it.listenId == lastQa }
    val answer = said?.takeIf { q != null && it.sid == Sid.QA && it.at >= q.at && it.at >= stepFrom }?.text
    if (q != null && answer != null) return HomeCard.Ask(listening = false, question = q.text, answer = answer)
    val x = s?.session ?: return null
    val ep = x.episode
    return when (val st = x.stage) {
        is Stage.Attempt, is Stage.Gap, is Stage.Confirm -> track(st, ep, p)
        is Stage.Handoff -> rest(s) ?: track(st, ep, p, offered = x.pendingAsk != null || x.waypointPending != null)
        is Stage.Wait -> null
        Stage.Listening -> if (s.car.gear == Gear.P) last?.let(::episodeCard) else rest(s)
    }
}

/** 달래기 단계(시안 track). 시도 결과(TryRecord)만 보고 칸을 정한다. 2차 소리는 정해진 뒤에만 이름을 쓴다. */
internal fun track(st: Stage, ep: kr.aigyeot.engine.EpisodeState?, p: Params, offered: Boolean = false): HomeCard.Track {
    val firstFail = ep?.first?.result == TryResult.FAIL
    val secondFail = ep?.second?.result == TryResult.FAIL
    // 1차·듣기 틈 두 칸은 1차 결과 하나로 정한다: 2차 시도부터 인계까지 같은 값(1차가 미판정이면 실패로 그리지 않는다)
    val afterFirst = if (firstFail) StepMark.FAIL else StepMark.DONE
    val marks = when (st) {
        is Stage.Attempt -> if (st.n <= 1) listOf(StepMark.NOW, StepMark.NONE, StepMark.NONE, StepMark.NONE) else listOf(afterFirst, afterFirst, StepMark.NOW, StepMark.NONE)
        is Stage.Gap, is Stage.Confirm -> if (st.n <= 1) listOf(StepMark.DONE, StepMark.NOW, StepMark.NONE, StepMark.NONE) else listOf(afterFirst, afterFirst, StepMark.DONE, StepMark.NONE)
        is Stage.Handoff -> listOf(
            if (ep?.first != null) afterFirst else StepMark.NONE,
            if (firstFail) StepMark.FAIL else if (ep?.second != null) StepMark.DONE else StepMark.NONE,   // 2차를 했으면 틈을 지났다
            if (secondFail) StepMark.FAIL else if (ep?.second != null) StepMark.DONE else StepMark.NONE,
            // 쉼터 칸은 쉼터를 실제로 물었거나 넣었을 때만(쉼터 없음·도착 안내·P 정차 인계는 쉼터 제안이 아니다, H2·H7)
            when { ep?.waypointAdded == true -> StepMark.DONE; offered -> StepMark.NOW; else -> StepMark.NONE },
        )
        else -> List(4) { StepMark.NONE }
    }
    val firstName = ep?.first?.method?.kind?.let(::methodLabel) ?: (st as? Stage.Attempt)?.takeIf { it.n <= 1 }?.play?.method?.kind?.let(::methodLabel)
    val secondName = ep?.second?.method?.kind?.let(::methodLabel) ?: (st as? Stage.Attempt)?.takeIf { it.n >= 2 }?.play?.method?.kind?.let(::methodLabel)
    val subs = listOf(firstName.orEmpty(), "${p.gapMs / 1000}초", secondName.orEmpty(), if (offered || ep?.waypointAdded == true) "제안" else "")
    val (from, total) = when (st) {
        is Stage.Attempt -> st.play.startedAt?.let { it to (st.play.endAt - it) } ?: (null to null)
        is Stage.Gap -> st.window?.let { it.from to (it.end - it.from) } ?: (null to null)
        is Stage.Confirm -> st.window?.let { it.from to (it.end - it.from) } ?: (null to null)
        else -> null to null
    }
    return HomeCard.Track(marks, subs, from, total)
}

/** 쉼터 카드: 지금 묻는 쉼터(pendingAsk) 또는 이 세션에 넣은 경유지. 이름을 아는 것만(모르면 카드 없음). */
private fun rest(s: EngineState): HomeCard.Rest? {
    val x = s.session ?: return null
    val rests = s.car.route?.rests.orEmpty()
    val id = x.pendingAsk?.restId ?: x.waypointPending?.restId ?: x.addedRestIds.lastOrNull() ?: return null
    val r = rests.firstOrNull { it.id == id } ?: return null
    val isAdded = id in x.addedRestIds   // 그 쉼터가 넣어졌는지만(같은 에피소드에서 앞 쉼터를 넣었어도 이 쉼터는 아님, H4c·P5)
    val sub = listOfNotNull(if (r.hasNursingRoom) "수유실" else null, "${formatKm(r.distanceKm)}km").joinToString(" · ")
    return HomeCard.Rest(r.name, sub, isAdded)
}

internal fun formatKm(km: Double) = if (km >= 10) km.roundToInt().toString() else "%.1f".format(km).removeSuffix(".0")

/** 정차 중 에피소드 흐름(시안 epi2: 자장가 → 다시 울음 → 백색소음 → 쉼터 제안 → 그쳤어요). 리포트와 같은 timeline 값만 쓴다. */
internal fun episodeCard(e: TimelineEpisode): HomeCard.Episode? {
    val at = runCatching { java.time.OffsetDateTime.parse(e.startedAt).toInstant().toEpochMilli() }.getOrNull() ?: return null
    val flow = buildList {
        e.plays.forEach { pl ->
            // '다시 울음'은 1차가 그치지 않아 자동 2차를 틀 때만(운전자 요청으로 소리를 바꾼 것은 울음이 아니다)
            if (pl.purpose == kr.aigyeot.shared.PlayPurpose.AUTO_SECOND && (e.recried || e.result == EpisodeResult.FAILURE)) add(FlowItem("다시 울음", Tone.CHILD))
            add(FlowItem(methodKindOf(pl.method)?.let(::methodLabel) ?: "기타", Tone.MUTED))
        }
        e.stop?.let { st -> if (st.mode == HandoffKind.REST_OFFER && toldShown(st.toldDriver, settled = true)) add(FlowItem("쉼터 제안", Tone.CHILD)) }
        if (e.result == EpisodeResult.SUCCESS || e.second?.result == EpisodeResult.SUCCESS) add(FlowItem(resultText(EpisodeResult.SUCCESS), Tone.ACCENT))   // 1차든 2차든 그쳤으면
    }
    return if (flow.isEmpty()) null else HomeCard.Episode(at, episodeHead(e.trigger), flow)
}

/** 3D 무대 상태(시안 STEPS의 view·light·ripple·callouts·listen). 주행·동작 줄이기 깃발은 화면이 붙인다. */

internal fun stage(s: EngineState?, done: Set<OkControl> = emptySet()): Stage3d {
    val x = s?.session
    val seat = when (s?.profile?.carseat) { CarSeat.LEFT -> Seat3d.RL; CarSeat.CENTER -> Seat3d.RC; else -> Seat3d.RR }
    // 칩 선·3D 표시는 실제로 해낸 뒷자리 쪽만: 잠금은 결과를 받은 영역(3e windowLockBefore 키), 열선은 성공한 control_log 영역(가운데 카시트는 양쪽을 따로)
    val heat = if (x == null) emptySet() else done.filter { it.act == ControlAct.SEAT_HEAT_OFF }.mapNotNull { sideOf(it.target) }.toSet()
    val lock = x?.windowLockBefore?.keys.orEmpty().mapNotNull { sideOf(it.name) }.toSet()
    val callouts = buildSet {
        if (lock.isNotEmpty()) add(Callout3d.LOCK)          // 3e: 앱이 뒷창을 잠갔다(세션이 닫힐 때 되돌림)
        if (s?.ambientDimmed == true) add(Callout3d.DIM)              // 5.10 낮춤이 확인된 동안
        // 열선·공조는 명령을 낸 것이 아니라 해낸 것만(이 세션 control_log 성공 줄, 리포트 seat_heater_off·cabin_cool과 같은 근거)
        if (x != null && done.any { it.act == ControlAct.CABIN_COOL }) add(Callout3d.HVAC)
        if (x != null && heat.isNotEmpty()) add(Callout3d.HEAT)
    }
    if (x == null) return Stage3d(View3d.CABIN, if (s?.car?.micRunning == true) Light3d.LISTEN else Light3d.BASE, callouts = callouts, seat = seat)
    return sessionStage(x, s.hears(), callouts, seat).copy(sides = mapOf(Callout3d.LOCK to lock, Callout3d.HEAT to heat).filterValues { it.isNotEmpty() })
}

private fun sessionStage(x: kr.aigyeot.engine.SessionState, mic: Boolean, callouts: Set<Callout3d>, seat: Seat3d): Stage3d {
    // 소리 결은 소리가 실제로 나가고(일시 정지 아님, 계약 차 5.5) 뒷자리라고 확실할 때만(소리가 나가는 자리, C365)
    val ripple = soundNow(x) == Sound.PLAYING && rearSure(x)
    return when (val st = x.stage) {
        is Stage.Attempt -> if (st.play.startedAt == null) Stage3d(View3d.REAR, Light3d.ALERT, callouts = callouts, seat = seat)
            else Stage3d(View3d.REAR, Light3d.SOOTHE, ripple = ripple, callouts = callouts, seat = seat)
        // 듣는 빛·듣기 표시는 판단 코어가 소리를 볼 때만(마이크 꺼짐·입력 끊김에 듣는다는 거짓 표시 방지, S17·5.3)
        is Stage.Gap, is Stage.Confirm -> Stage3d(View3d.REAR, Light3d.BASE, listen = mic, callouts = callouts, seat = seat)
        is Stage.Handoff -> Stage3d(View3d.REAR, Light3d.BASE, callouts = callouts, seat = seat)
        is Stage.Wait -> Stage3d(View3d.CABIN, if (st.play != null) Light3d.SOOTHE else if (mic) Light3d.LISTEN else Light3d.BASE, ripple = ripple, callouts = callouts, seat = seat)
        Stage.Listening -> Stage3d(View3d.CABIN, if (mic) Light3d.LISTEN else Light3d.BASE, callouts = callouts, seat = seat)
    }
}

/** control_log 영역 → 뒷자리 쪽(열선 칩 앵커). 뒷줄이 아니면 null. */
private fun sideOf(target: String) = when (target) { "ROW_2_LEFT" -> Seat3d.RL; "ROW_2_RIGHT" -> Seat3d.RR; else -> null }


/** 주행 중 큰 기호(시안 driveSay): 달래는 중이면 방법 아이콘, 울음 감지면 아기(주황), 인계면 위치, 그 밖은 귀. */
internal fun drive(s: EngineState?): DriveSay {
    val x = s?.session
    val st = x?.stage
    val sym = when {
        st is Stage.Attempt && st.play.startedAt == null -> Symbol.BABY
        st is Stage.Attempt -> symbolOf(st.play.method.kind)
        st is Stage.Wait && st.play != null -> symbolOf(st.play!!.method.kind)
        st is Stage.Handoff -> Symbol.PIN
        else -> Symbol.EAR
    }
    return DriveSay(sym, ask = x?.pendingAsk != null)
}

private fun symbolOf(k: MethodKind) = when (k) { MethodKind.LULLABY -> Symbol.LULLABY; MethodKind.WHITE_NOISE -> Symbol.NOISE; MethodKind.WOMB -> Symbol.WOMB; MethodKind.SONG -> Symbol.SONG }

/**
 * 오늘 하루(시안 Charts.day, 차 크기). 차가 아는 값만: 잠·기저귀·수유 횟수는 폰 돌봄 요약(24시간 안·같은 날일 때),
 * 수유 점은 차가 아는 마지막 수유 시작, 차에서 줄은 오늘 주행 구간·경보. 아는 줄이 없으면 null(카드 없음).
 */
internal fun dayChart(s: EngineState?, car: List<CarSpan>, now: Long, zone: java.time.ZoneId): DayChartData? {
    val day0 = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    fun min(t: Long) = ((t - day0) / 60_000).toInt().coerceIn(0, 1440)
    fun today(t: Long?) = t != null && t >= day0 && t < day0 + 86_400_000L   // 오늘 날짜면(시계 눈금 now는 10초마다라 방금 기록이 그보다 늦을 수 있다)
    // 칸 고르기 하나(두 폰 inDay와 같은 규칙): 그날과 실제로 겹치는 구간만. 자정에 딱 끝난 전날 구간(길이 0 칸)은 오늘 칸이 아니다.
    // 그림은 분 칸, 길이는 그날 안으로 자른 기록 시각의 차이
    fun dayBlock(a: Long, e: Long): DayBlock? {
        val end0 = day0 + 86_400_000L
        if (!(a < end0 && (e > day0 || (e == a && a >= day0)))) return null
        val from = maxOf(a, day0); val to = minOf(e, end0)
        return DayBlock(min(from)..min(to), to - from)
    }
    val care = s?.care?.takeIf { it.freshAt(now) && today(it.asOf) }
    val lanes = buildList {
        if (care?.sleepDiaperEnabled == true) {
            val blocks = listOfNotNull(
                care.lastSleepStartAt?.let { a -> care.lastSleepEndAt?.takeIf { e -> e >= a }?.let { e -> dayBlock(a, e) } },
                care.sleepingSince?.let { a -> dayBlock(a, maxOf(a, care.asOf)) },   // 잠든 중은 요약 계산 시각(as_of)까지만: 합계(todaySleepMin)와 같은 기준(연결 계약 돌봄 요약). 끝이 시작보다 앞서지 않게
            )
            // 값은 합계가 먼저(차는 오늘 잠 칸을 다 모른다), 합계가 0분일 때만 칸으로 '1분 미만'·'0번'을 가른다([sleepValue])
            care.todaySleepMin?.let { add(DayLane("잠", DataColor.SLEEP, null, sleepValue(it, blocks.isNotEmpty()), blocks)) }
        }
        val feedDots = listOfNotNull(s?.feedingStart?.takeIf(::today)?.let(::min))
        if (care != null) add(DayLane("수유", DataColor.FEED, null, "${care.todayFeedCount}번", dots = feedDots))
        else if (feedDots.isNotEmpty()) add(DayLane("수유", DataColor.FEED, null, "마지막 " + TimeText.clock(s!!.feedingStart!!, ClockMode.CHART), dots = feedDots))   // 차트 안 시각은 24시간제
        if (care?.sleepDiaperEnabled == true) care.todayDiaperCount?.let { n -> add(DayLane("기저귀", DataColor.DIAPER, null, "${n}번", dots = listOfNotNull(care.lastDiaperAt?.takeIf(::today)?.let(::min)))) }
        val inToday = car.mapNotNull { c -> dayBlock(c.from, c.to ?: now)?.let { c to it } }   // 자정에 딱 끝난 세션은 오늘 칸이 아니다
        if (inToday.isNotEmpty()) {
            val spans = inToday.map { it.first }
            val drives = inToday.map { it.second }
            val driveMs = drives.sumOf { it.ms }   // 합계와 툴팁이 같은 값
            val cries = spans.flatMap { it.alarms }.filter(::today)   // 울음 경보 에피소드마다 한 번(리포트 목록 '울음 n번'과 같은 수, C381)
            // 세션 구간(출발 D부터 도착 뒤 뒷문 열 때까지)이라 중간 정차도 들어 있다: '주행'이 아니라 아기가 타 있던 '탑승' 시간
            add(DayLane("차에서", DataColor.CAR, "탑승 " + TimeText.duration(driveMs), "울음 ${cries.size}번",
                blocks = drives, diamonds = cries.map(::min)))
        }
    }
    return if (lanes.isEmpty()) null else DayChartData(lanes, min(now))
}

/**
 * 오늘 하루 잠 줄 값(U27·두 폰 하루 띠). 차는 오늘 잠 칸을 다 알지 못한다(폰 요약의 마지막 잠·잠든 중만, 막 저장한 잠은 요약 시각 뒤라 빠질 수 있다).
 * 그래서 '잠이 있었나'의 사실은 합계 [todayMin]이고, 칸은 합계가 분으로 0일 때 '1분 미만'(칸 있음)과 '0번'(칸 없음)을 가르는 데만 쓴다.
 */
internal fun sleepValue(todayMin: Int, hasBlock: Boolean) = when {
    todayMin > 0 -> TimeText.duration(todayMin * 60_000L)
    hasBlock -> "1분 미만"
    else -> "0번"
}

/** C303 ⑤(B7): 물어보기 버튼은 판단 코어가 AskButton을 받을 때만 켠다(말하는 중·통화·질문 창 등에서 눌러도 버려지지 않게). */
internal fun askEnabled(s: EngineState?): Boolean = s?.canAsk() == true

/** 수유 간격 글 '평소 3시간'(시안 v4-8 intervalText). 간격 글 꼴(U20)은 아기 설정과 같은 [intervalText] 하나다. */
internal fun usualIntervalText(min: Int) = "평소 " + intervalText(min)

/** 지난 시간 큰 숫자 'h:mm'(시안 feedCard). 1분 미만은 '방금'. */
internal fun agoText(ms: Long): String = (ms / 60_000).coerceAtLeast(0).let { m -> if (m < 1) "방금" else "${m / 60}:%02d".format(m % 60) }

/**
 * 홈 상태. 시계는 10초 눈금마다(마지막 수유 지난 시간), 판단 코어·연결·차가 말한 문장이 바뀔 때마다 다시 계산한다.
 * 정차 중 에피소드 카드는 세션이 있고 기어 P일 때만 Room에서 마지막 에피소드를 읽는다(리포트와 같은 변환).
 * 바꾸려면 사건을 [events](실제 입구, V3)로 보낸다. 수유 입력은 link가 Room에 먼저 쓴다(C322).
 */
class HomeViewModel(
    steps: StateFlow<Step?>, private val events: EventSink, private val link: Link, private val clock: () -> Long,
    said: StateFlow<Said?>, private val heard: StateFlow<HeardText?>, private val store: Store,
) : ViewModel() {
    // 시계 글자(수유 '방금'·'h:mm', 오늘 하루 지금 선)를 다시 계산하는 눈금. 홈은 눈금의 시각만 쓴다: 판단 코어가 Step을 낼 때마다 clock()을 읽으면
    // 수유 막대 몫이 매번 달라 HomeUi가 같지 않게 되고, 주행 중 Step마다 홈 전체를 다시 그린다(장면 B·C·E 앱 CPU 2배). 눈금보다 늦은 수유 기록은 agoText·fill이 0으로 막는다
    private val ticker = flow { while (true) { emit(clock()); delay(TICK_MS) } }
    private var lastKey: String? = null
    private var keyFrom = 0L

    /** 단계 열쇠가 바뀐 시각(차가 말한 문장을 그 단계 동안만 보인다). */
    private fun stepFrom(s: EngineState?): Long = stepKey(s).let { k -> if (k != lastKey) { lastKey = k; keyFrom = clock() - 1_000 }; keyFrom }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    // 세션 id를 붙여 낸다: 세션이 바뀐 뒤 새 읽기가 값을 내기 전(또는 다시 하는 동안) 앞 세션의 카드·칩을 지금 세션에 쓰지 않게(P5)
    private val lastEpisode: Flow<Tagged<TimelineEpisode?>> = steps.map { st -> st?.state?.session?.takeIf { st.state.car.gear == Gear.P }?.id }.distinctUntilChanged()
        .flatMapLatest { sid ->
            if (sid == null) flowOf(Tagged(null, null))
            // 읽기 실패는 화면 범주 U: 1초 뒤 다시 구독하고 그동안 직전 카드를 둔다(계약 차 DB 실패 표, C293)
            else store.reportChanges(sid).map { Tagged(sid, store.lastEpisode(sid)) }.flowOn(Dispatchers.IO).retryOnDbError("home-episode")
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val done: Flow<Tagged<Set<OkControl>>> = steps.map { it?.state?.session?.id }.distinctUntilChanged()
        .flatMapLatest { sid -> if (sid == null) flowOf(Tagged(null, emptySet())) else store.okControlsFlow(sid).map { Tagged(sid, it.toSet()) } }
    private val askPick = AskPick()
    private fun lastQa(s: EngineState?): String? = askPick.pick(s, heard.value, clock(), store.clearedAt.value)

    init {
        // 단계 열쇠는 화면이 안 보일 때도 따라간다(다른 화면에 갔다 오는 동안 지난 단계의 문장이 지금 단계에 남지 않게, P5)
        steps.onEach { stepFrom(it?.state); lastQa(it?.state) }.launchIn(viewModelScope)
    }

    /**
     * 오늘 주행 구간(시뮬레이션 뺌)과 경보 시각. 세션 표가 바뀔 때만 다시 읽는다. 날짜는 주입 시계(눈금과 같은 출처)로 정한다.
     * 이 앞 거름은 화면의 dayChart가 눈금 시각으로 다시 거르므로 넓어도 된다: 눈금 값은 지금보다 최대 [TICK_MS] 늦어, 그만큼 앞 시각의 날짜로 거른다(자정 직후에도 빠뜨리지 않게).
     */
    private val carDay: Flow<List<CarSpan>> = store.sessionsFlow().map { list ->
        val day0 = java.time.Instant.ofEpochMilli(clock() - TICK_MS).atZone(TimeText.zone).toLocalDate().atStartOfDay(TimeText.zone).toInstant().toEpochMilli()
        list.filter { it.scenarioId == null && (it.endedAt ?: Long.MAX_VALUE) >= day0 }.map { CarSpan(it.startedAt, it.endedAt, store.cryAlarms(it.id)) }
    }.flowOn(Dispatchers.IO).retryOnDbError("home-day")   // 읽기 실패: 1초 뒤 다시, 그동안 직전 '오늘 하루'를 둔다('울음 없음'으로 바꾸지 않는다)

    // Room에서 읽는 덧붙임 셋은 처음 값을 먼저 낸다: 첫 읽기가 실패해 다시 하는 동안에도 판단 코어·연결 상태는 화면에 바로 간다(카드·칩·오늘 하루만 늦게)
    private val extras = combine(lastEpisode.onStart { emit(Tagged(null, null)) }, done.onStart { emit(Tagged(null, emptySet())) }, carDay.onStart { emit(emptyList()) }) { a, b, c -> Triple(a, b, c) }

    // 기록 지우기 시각도 같이 본다(지운 뒤 홈에 돌아오면 옛 물어보기 카드가 바로 내려가게)
    val uiState: StateFlow<HomeUi> = combine(steps, link.status, ticker, combine(said, heard, store.clearedAt) { a, b, _ -> a to b }, extras) { s, st, now, sh, ex ->
        val sid = s?.state?.session?.id
        homeUi(s?.state, now, st, sh.first, sh.second, stepFrom(s?.state), ex.first.of(sid), ex.second.of(sid) ?: emptySet(), ex.third, lastQa = lastQa(s?.state))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), homeUi(steps.value?.state, clock(), link.status.value, said.value, heard.value, stepFrom(steps.value?.state), null, lastQa = lastQa(steps.value?.state)))

    fun feedingNow() { link.feedingFromCar(clock()) }
    fun ask() { askPick.pressed(clock()); events.post(AskButton(0)) }
}
