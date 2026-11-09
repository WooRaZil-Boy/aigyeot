package kr.aigyeot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kr.aigyeot.app.link.methodKindOf
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.db.SessionEntity
import kr.aigyeot.app.store.retryOnDbError
import kr.aigyeot.app.ui.car3d.Callout3d
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.methodLabel
import kr.aigyeot.engine.toldShown
import kr.aigyeot.shared.CarActionKind
import kr.aigyeot.shared.EpisodeResult
import kr.aigyeot.shared.EpisodeTrigger
import kr.aigyeot.shared.ExcludedReason
import kr.aigyeot.shared.HandoffKind
import kr.aigyeot.shared.MethodStat
import kr.aigyeot.shared.OutputRoute
import kr.aigyeot.shared.PlayPurpose
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.SecondAction
import kr.aigyeot.shared.SecondAttempt
import kr.aigyeot.shared.SoundMethod
import kr.aigyeot.shared.Steering
import kr.aigyeot.shared.SteeringUnavailableReason
import kr.aigyeot.shared.StopInfo
import kr.aigyeot.shared.StopReason
import kr.aigyeot.shared.TimelineEpisode
import java.time.OffsetDateTime
import kotlin.math.roundToInt

// 리포트 목록·상세의 화면 상태와 그 계산(순수 함수). 화면(ReportScreen)은 여기서 만든 값을 그리기만 한다.

/** 리포트 목록 한 줄(U47): [title] "{날짜} {구간}", [sub] "울음 {n}번"·"울음 기록 없음"·"주행 중"(+ " · 시뮬레이션"). 열린 세션은 열 수 없다. */
data class ReportRow(val id: String, val title: String, val sub: String, val enabled: Boolean)

/** [cries]: 닫힌 세션의 울음 경보 에피소드 수(모르면 null, 부가 글자에서 뺀다). */
internal fun reportRows(sessions: List<SessionEntity>, cries: (String) -> Int? = { null }) = sessions.map { s ->
    val end = s.endedAt
    val title = TimeText.date(s.startedAt) + " " + (end?.let { TimeText.range(s.startedAt, it) } ?: TimeText.clock(s.startedAt))
    val sub = listOfNotNull(if (end == null) "주행 중" else cries(s.id)?.let(::cryText), if (s.scenarioId != null) "시뮬레이션" else null)
    ReportRow(s.id, title, sub.joinToString(" · "), end != null)
}

/** U47: 0번이면 "울음 기록 없음". */
internal fun cryText(n: Int) = if (n == 0) "울음 기록 없음" else "울음 ${n}번"

/** U47: 울음 경보로 시작한 에피소드 수(소리 바꾸기로 연 에피소드는 세지 않는다). 폰 기록 탭 '차에서 울음'과 같은 수. */
internal fun cryCount(b: ReportSummaryBody) = b.timeline.count { it.trigger == EpisodeTrigger.CRY_ALARM }

class ReportListViewModel(store: Store, clock: () -> Long) : ViewModel() {
    // 닫힌 세션의 울음 수는 바뀌지 않으니 한 번만 센다(다시 구독할 때 앞 흐름과 겹칠 수 있어 동시 맵)
    // 한계: 세는 데 리포트 전체를 만든다(store에 세션별 울음 수 쿼리가 없음). 세션이 많아 느리면 store에 GROUP BY 쿼리를 더한다
    private val cries = java.util.concurrent.ConcurrentHashMap<String, Int>()

    val rows: StateFlow<List<ReportRow>> = store.sessionsFlow().map { ss ->
        reportRows(ss) { id -> cries[id] ?: store.readReport(id, clock()).first?.let(::cryCount)?.also { cries[id] = it } }
    }.flowOn(Dispatchers.IO).retryOnDbError("report_list")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

sealed interface ReportDetailUiState {
    data object Loading : ReportDetailUiState
    data class Loaded(val view: ReportView) : ReportDetailUiState
}

/** 리포트 상세. 세션·에피소드가 바뀔 때마다 다시 읽는다: 인계 TTS 도중 열어도 결과가 들어오면 고쳐 그린다(C185). */
class ReportDetailViewModel(store: Store, clock: () -> Long, id: String) : ViewModel() {
    // C293: 다시 읽기까지 흐름 안에 넣어, 한 번 실패해도 직전 화면을 두고 1초 뒤 다시 읽는다
    val uiState: StateFlow<ReportDetailUiState> = store.reportChanges(id).map { store.readReport(id, clock()) }.flowOn(Dispatchers.IO)
        .retryOnDbError("report").map { (b, settling) -> if (b == null) ReportDetailUiState.Loading else ReportDetailUiState.Loaded(reportView(b, settling)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportDetailUiState.Loading)
}

// ── 상세 화면 상태(시안 reports() 오른쪽: 숫자 줄 → 에피소드 → 자세히 → 차가 한 일 | 조향 → 방법별 성적) ──

/** 숫자 칸 하나(시안 Charts.stats): 이름·값·부가. */
data class Stat(val label: String, val value: String, val sub: String? = null)

/** '자세히' 한 줄. [head]면 묶음 머리(에피소드 시각·'울음 직전 주행'·'제어 기록'). */
data class DetailLine(val text: String, val head: Boolean = false)

/** 차가 대신 한 일 한 줄: 이름(아이콘은 화면이 [kind]로 고른다)과 횟수·재생 초. */
data class ActionItem(val kind: CarActionKind, val name: String, val extra: String)

/** 에피소드 카드: 제목 '에피소드 · {시각}부터'와 띠. */
data class EpisodeCard(val title: String, val chart: EpisodeChartData)

data class ReportView(
    /** 구획 제목 "11월 9일(월) 오후 1:10–1:52 · 42분". */
    val title: String,
    val stats: List<Stat>,
    val episodes: List<EpisodeCard>,
    val details: List<DetailLine>,
    val actions: List<ActionItem>,
    /** 3D 'did' 칩: car_actions에 실제로 있는 것만. */
    val callouts: Set<Callout3d>,
    /** 조향 막대(비교할 수 없으면 null이고 [steerText]가 이유). */
    val steer: List<SteerBar>?,
    val steerText: String?,
    val methods: List<MethodDots>,
)

/** ISO 8601 시각 → epoch ms(못 읽으면 null). */
internal fun epochMs(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

internal fun reportView(b: ReportSummaryBody, settling: Boolean): ReportView {
    val start = epochMs(b.startedAt); val end = epochMs(b.endedAt)
    val title = if (start != null && end != null) "${TimeText.date(start)} ${TimeText.range(start, end)} · ${TimeText.duration(end - start)}" else b.startedAt
    val steer = steerBars(b.steering)
    return ReportView(
        title = title,
        stats = reportStats(b),
        episodes = b.timeline.mapNotNull { e ->
            val chart = episodeChart(e, settling) ?: return@mapNotNull null
            EpisodeCard("에피소드 · ${epochMs(e.startedAt)?.let { TimeText.clock(it) } ?: ""}부터", chart)
        },
        details = detailLines(b, settling),
        actions = b.carActions.filter { it.count > 0 }.map { ActionItem(it.action, actionName(it.action), "${it.count}번" + (it.totalS?.let { s -> " · ${s}초" } ?: "")) },   // 0번은 한 일이 아니다(숫자 줄·3D 칩과 같은 기준)
        callouts = b.carActions.filter { it.count > 0 }.mapNotNull { CALLOUT[it.action] }.toSet(),
        steer = steer,
        steerText = if (steer == null) steeringUnavailable(b.steering) else null,
        methods = methodDots(b.methodStats),
    )
}

private val CALLOUT = mapOf(
    CarActionKind.REAR_WINDOW_LOCK to Callout3d.LOCK, CarActionKind.SEAT_HEATER_OFF to Callout3d.HEAT,
    CarActionKind.AMBIENT_DIM to Callout3d.DIM, CarActionKind.CABIN_COOL to Callout3d.HVAC,
)

/**
 * 숫자 줄(시안 k4). 값이 없는 칸은 뺀다(숫자를 만들어 내지 않는다).
 * - 울음 n번: 울음 경보 에피소드 수, 부가는 첫 울음 시각.
 * - 감지까지: 경보 지연(alarm_latency_s)의 평균(폰과 같은 값).
 * - 울음 시간: 타임라인 전체의 total_cry_s 합(폰 리포트 reportKpis와 같은 값·같은 이름, 들은 울음만, 계약 G37). 시안 '그칠 때까지'는
 *   그친 시각이 리포트에 없어 쓰지 않는다(재생 끝으로 어림하면 거짓 시간이 된다).
 * - 차가 한 일 n가지: car_actions의 종류 수.
 */
internal fun reportStats(b: ReportSummaryBody): List<Stat> {
    val cries = b.timeline.filter { it.trigger == EpisodeTrigger.CRY_ALARM }
    val first = cries.mapNotNull { epochMs(it.startedAt) }.minOrNull()
    val lat = b.timeline.mapNotNull { it.alarmLatencyS }
    return listOfNotNull(
        Stat("울음", "${cries.size}번", first?.let { TimeText.clock(it) }),
        lat.takeIf { it.isNotEmpty() }?.let { Stat("감지까지", "%.1f초".format(it.average())) },
        Stat("울음 시간", TimeText.duration(b.timeline.sumOf { it.totalCryS } * 1000L, seconds = true)),
        Stat("차가 한 일", "${b.carActions.filter { it.count > 0 }.map { it.action }.distinct().size}가지"),
    )
}

/**
 * 에피소드 띠 하나(시안 Charts.episode). 0초 = 에피소드 시작(경보 시각, 소리 바꾸기면 요청 시각).
 * 리포트에 있는 사실만 그린다(폰 episodeBand와 같은 규칙):
 * - 소리 줄: 재생마다 방법 이름 칸(툴팁 초는 기록된 play_s), 재생 사이 빈틈은 '듣기 틈'.
 * - 울음 줄은 없다: report_summary에 울음 구간이 없고 경보 지연은 들은 울음 시간이 아니다(계약 G36·G37). 들은 울음 합은 [EpisodeChartData.outcomes]의 '울음 n초'.
 * - 시간축 표시는 시각을 아는 것만: 시작(감지 지연 초 또는 '소리 바꾸기'), 다시 울음(그 때문에 시작한 자동 2차 재생의 시작).
 * - 결과(쉼터 제안·그쳤어요)는 판정 시각이 리포트에 없어 축 밖 [EpisodeChartData.outcomes]로 둔다(재생 끝에 찍으면 거짓 시각, 명세 5장 성공 조건).
 * 재생이 없으면 null(그릴 띠가 없다. 사실은 '자세히'에 있다).
 */
internal fun episodeChart(e: TimelineEpisode, settling: Boolean = false): EpisodeChartData? {
    val t0 = epochMs(e.startedAt) ?: return null
    fun ms(iso: String) = epochMs(iso)?.minus(t0)
    fun sec(d: Long) = (d / 1000.0).roundToInt().coerceAtLeast(0)
    val plays = e.plays.mapNotNull { p ->
        val a = ms(p.startedAt) ?: return@mapNotNull null
        Triple(p, a, maxOf(ms(p.endedAt) ?: (a + p.playS * 1000L), a))
    }.sortedBy { it.second }
    if (plays.isEmpty()) return null
    val sounds = buildList {
        plays.forEachIndexed { i, (p, a, b) ->
            val prev = plays.getOrNull(i - 1)?.third
            if (prev != null && a > prev) add(SoundSeg(sec(prev), sec(a), null, sec(a - prev)))
            add(SoundSeg(sec(a), maxOf(sec(b), sec(a) + 1), method(p.method), p.playS))
        }
    }
    val marks = buildList {
        add(ChartMark(0, if (e.trigger != EpisodeTrigger.CRY_ALARM) "소리 바꾸기" else e.alarmLatencyS?.let { "감지 %.1f초".format(it) } ?: "울음 감지"))
        if (e.recried) plays.firstOrNull { it.first.purpose == PlayPurpose.AUTO_SECOND }?.let { add(ChartMark(sec(it.second), "다시 울음")) }
    }
    val outcomes = listOfNotNull(
        e.totalCryS.takeIf { it > 0 }?.let { "울음 ${it}초" },
        "쉼터 제안".takeIf { e.stop?.let { it.mode == HandoffKind.REST_OFFER && toldShown(it.toldDriver, settled = !settling) } == true },   // 묻지 못했거나 확정 전이면 제안했다고 보이지 않는다(C130·C185)
        "그쳤어요".takeIf { e.result == EpisodeResult.SUCCESS || e.second?.result == EpisodeResult.SUCCESS },
    )
    return EpisodeChartData(maxOf(sounds.maxOf { it.to }, 1), sounds, marks, outcomes)
}

/** 조향 막대 셋(평소·울음 중·대응 뒤). 값이 없는 막대는 뺀다. 비교할 수 없으면 null. */
internal fun steerBars(s: Steering): List<SteerBar>? = if (!s.available) null else listOfNotNull(
    s.baselinePerMin?.let { SteerBar("평소", it, false) },
    s.duringCryPerMin?.let { SteerBar("울음 중", it, true) },
    s.afterResponsePerMin?.let { SteerBar("대응 뒤", it, false) },
).ifEmpty { null }

private fun steeringUnavailable(s: Steering) =
    if (!s.available && s.unavailableReason == SteeringUnavailableReason.NO_CRY) "울음이 없어 비교하지 않았어요" else "비교할 구간이 없어요"

/** C303 A18: 3번 미만이면 "아직 모름"(Params.statMinShown). 아는 것 먼저, 그친 비율 높은 순(시안 Charts.methods). */
internal fun methodDots(stats: List<MethodStat>?, p: Params = Params()) = stats.orEmpty()
    .map { MethodDots(it.label, it.successes, it.attempts, it.attempts >= p.statMinShown) }
    .sortedWith(compareByDescending<MethodDots> { it.known }.thenByDescending { if (it.n == 0) 0.0 else it.ok.toDouble() / it.n })

/** '자세히': 시안에 자리가 없는 사실을 지금 문장 그대로(출력 경로·줄임·시뮬레이션·에피소드별 재생·결과·정차·울음 직전 주행·제어 기록). */
internal fun detailLines(b: ReportSummaryBody, settling: Boolean) = buildList {
    if (b.simulated) add(DetailLine("시뮬레이션 입력으로 만든 기록이에요"))
    when (b.outputRoute) {
        OutputRoute.REAR_ZONE -> add(DetailLine("소리는 뒷좌석으로 나갔어요"))
        OutputRoute.DEFAULT_ZONE_FALLBACK -> add(DetailLine("뒷좌석 출력이 없어 앞에서도 들렸어요"))
        OutputRoute.NO_PLAYBACK -> Unit   // 재생이 없었으면 출력 경로를 보이지 않는다
    }
    if (b.truncated == true) add(DetailLine("오래된 에피소드는 줄였어요"))
    if (b.timeline.isEmpty()) add(DetailLine("기록된 에피소드가 없어요"))   // 통화 중 경보처럼 에피소드 없이 기록만 한 경보도 있다
    b.timeline.forEach { e ->
        val at = epochMs(e.startedAt)?.let { TimeText.clock(it) } ?: ""
        add(DetailLine("$at " + if (e.trigger == EpisodeTrigger.CRY_ALARM) "울음 감지${e.alarmLatencyS?.let { " (지연 ${"%.1f".format(it)}초)" } ?: ""}" else "소리 바꾸기", head = true))
        add(DetailLine("${e.sound?.label ?: "재생 없음"} · ${e.playS}초" + (e.plays.size.takeIf { it > 1 }?.let { " · 재생 ${it}번" } ?: "")))
        e.plays.forEach { pl ->
            val span = epochMs(pl.startedAt)?.let { a -> epochMs(pl.endedAt)?.let { z -> TimeText.range(a, z) } } ?: ""
            add(DetailLine("· ${purpose(pl.purpose)} ${method(pl.method)} $span ${pl.playS}초"))
        }
        add(DetailLine("결과: " + result(e.result) + (e.excludedReason?.let { " · 성적에서 뺌(${excluded(it)})" } ?: "")))
        if (e.recried) add(DetailLine("다시 울었어요"))
        e.second?.let { add(DetailLine("2차: " + secondText(it))) }
        e.stop?.let { add(DetailLine("정차: " + stopText(it, settling))) }
        add(DetailLine("울음 ${e.totalCryS}초"))
    }
    if (b.preCryContext.isNotEmpty()) add(DetailLine("울음 직전 주행", head = true))
    b.preCryContext.forEach { c ->
        add(DetailLine("평균 ${c.speedKmhAvg}km/h · 최고 ${c.speedKmhMax}km/h · 브레이크 ${c.brakePresses}번"))
        add(DetailLine("큰 조향 ${c.steeringReversals}번 · 방향지시등 ${if (c.turnSignalUsed) "켬" else "안 켬"} · 기어 ${c.gear.name}" + (c.cabinTempC?.let { " · 실내 ${"%.1f".format(it)}℃" } ?: "") + if (c.playing) " · 재생 중" else ""))
    }
    add(DetailLine("제어 기록", head = true))
    if (b.controls.isEmpty()) add(DetailLine("없어요"))
    b.controls.forEach { add(DetailLine("${it.api} ${it.target}: 성공 ${it.succeeded} · 실패 ${it.failed} · 재시도 ${it.retries}")) }
}

/** C303 A19: 이름은 판단 코어 methodLabel 하나. 받은 모르는 방식(UNKNOWN)만 "기타". */
private fun method(m: SoundMethod) = methodKindOf(m)?.let(::methodLabel) ?: "기타"

private fun purpose(p: PlayPurpose) = when (p) { PlayPurpose.AUTO_FIRST -> "자동 1차"; PlayPurpose.AUTO_SECOND -> "자동 2차"; PlayPurpose.REQUEST -> "운전자 요청" }

private fun result(r: EpisodeResult?) = when (r) {
    EpisodeResult.SUCCESS -> "그쳤어요"; EpisodeResult.FAILURE -> "계속 울었어요"; EpisodeResult.UNDETERMINED -> "판정하지 못했어요"; null -> "시도 없음"
}

/** 폰 리포트의 excluded()와 같은 뜻(열거 이름을 그대로 쓰지 않는다). */
private fun excluded(e: ExcludedReason) = when (e) {
    ExcludedReason.SESSION_END -> "세션이 끝나 판정하지 못했어요"
    ExcludedReason.DRIVER_STOP -> "운전자가 멈춤"
    ExcludedReason.CALL -> "통화"
    ExcludedReason.GEAR_TO_PARK, ExcludedReason.STARTED_IN_PARK -> "정차 중"
    ExcludedReason.SWITCH_SOUND -> "소리 바꾸기 요청"
    ExcludedReason.FEEDING_WINDOW -> "수유 시간대"
    ExcludedReason.SESSION_CAP -> "세션 상한"
    ExcludedReason.MIC_ERROR -> "마이크 오류"
    ExcludedReason.DETECTOR_TIMEOUT -> "감지 시간 초과"
    ExcludedReason.INPUT_SILENCED -> "입력이 무음 처리됨"
    ExcludedReason.FOCUS_LOSS -> "소리를 이어서 틀지 못함"
    ExcludedReason.SUSPEND -> "일시 중단"
}

private fun secondText(sec: SecondAttempt): String {
    val what = when (sec.action) {
        // C355: 분기는 고른 사실이다. 실제로 낮췄는지는 car_actions cabin_cool이 보인다
        SecondAction.COMFORT_COOL -> "더운 실내로 판단했어요"
        SecondAction.FEEDING_WINDOW -> "수유 시간대로 판단했어요"
        SecondAction.OTHER_SOUND -> "다른 소리로 바꿨어요" + (sec.sound?.let { "(${it.label})" } ?: "")
        SecondAction.FOCUS_DENIED -> "소리를 이어서 틀지 못했어요"
    }
    return what + (sec.result?.let { " · ${result(it)}" } ?: "")
}

/** [settling]: 인계 행에 아직 확정 안 된(말하는 종류인데 told_driver null) 것이 있으면(`handoffSettled` 거짓, C305) 한 줄로만 보인다(C185). */
internal fun stopText(st: StopInfo, settling: Boolean = false) = buildString {
    append(when (st.reason) {
        StopReason.ATTEMPT_LIMIT -> "두 번 시도 뒤"
        StopReason.SESSION_CAP -> "세션 20분 상한"
        StopReason.FEEDING_WINDOW -> "수유 시간대"
        StopReason.NO_SOUND -> "뒷좌석 출력 없음"
    })
    append(" · ")
    if (settling) { append("정리하는 중이에요"); return@buildString }
    val told = toldShown(st.toldDriver, settled = true)   // C130 ⑦·C303 A10: 인계 문장을 못 말했으면 "했다"고 쓰지 않는다(폰 U53과 같은 글). 확정 전은 위에서 끝났다
    append(when (st.mode) {
        HandoffKind.REST_OFFER -> if (told) "${st.restName ?: "쉼터"} 들르기 제안" else "쉼터는 묻지 못했어요"
        HandoffKind.ARRIVAL_NOTICE -> if (told) "도착 안내" else "도착 안내는 하지 못했어요"
        HandoffKind.NO_REST -> if (told) "경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요" else "안전한 곳에 세우도록 안내하지 못했어요"
        HandoffKind.PARKED -> if (st.reason == StopReason.NO_SOUND) "차가 선 채로 소리를 틀지 못했어요" else "차가 선 채로 달래기를 멈췄어요"   // U59
    })
    if (st.waypointAdded) append(" · 경유지 추가")
    if (st.guardianNotified) append(" · 다른 보호자에게 알림")
}

/** 차가 한 일 이름. 시안 화면에 보이는 것('열선 끔'·'뒷자리에만 재생'·'뒷창 잠금'·'조명 낮춤')은 시안 글자, 나머지는 지금 앱 글자. */
internal fun actionName(a: CarActionKind) = when (a) {
    CarActionKind.REAR_PLAYBACK -> "뒷자리에만 재생"
    CarActionKind.DEFAULT_ZONE_PLAYBACK -> "앞에서도 들리게 재생"
    CarActionKind.SEAT_HEATER_OFF -> "열선 끔"
    CarActionKind.REAR_WINDOW_LOCK -> "뒷창 잠금"
    CarActionKind.CABIN_COOL -> "실내 온도 낮추기"
    CarActionKind.READING_LIGHT_OFF -> "독서등 끄기"
    CarActionKind.AMBIENT_DIM -> "조명 낮춤"
    CarActionKind.WAYPOINT_ADDED -> "경유지 추가"
    CarActionKind.ARRIVAL_NOTICE -> "도착 안내"
    CarActionKind.GUARDIAN_ALERT -> "보호자 알림"
}
