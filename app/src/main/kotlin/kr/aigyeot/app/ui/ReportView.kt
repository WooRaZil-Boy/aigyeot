package kr.aigyeot.app.ui

import java.time.OffsetDateTime
import kotlin.math.roundToInt
import kr.aigyeot.app.link.methodKindOf
import kr.aigyeot.app.store.db.SessionEntity
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

// 리포트 목록·상세의 화면 상태와 그 계산(순수 함수). 화면(ReportScreen)은 여기서 만든 값을 그리기만 한다.

/** 리포트 목록 한 줄: [title] "{날짜} {구간}", [sub] "울음 {n}번"·"울음 기록 없음"·"주행 중"(+ " · 연습 장면"). 열린 세션은 열 수 없다. */
data class ReportRow(val id: String, val title: String, val sub: String, val enabled: Boolean)

/** [cries]: 닫힌 세션의 울음 경보 에피소드 수(모르면 null, 부가 글자에서 뺀다). */
internal fun reportRows(sessions: List<SessionEntity>, cries: (String) -> Int? = { null }) = sessions.map { s ->
    val end = s.endedAt
    val title = TimeText.date(s.startedAt) + " " + (end?.let { TimeText.range(s.startedAt, it) } ?: TimeText.clock(s.startedAt))
    val sub = listOfNotNull(if (end == null) "주행 중" else cries(s.id)?.let(::cryText), if (s.scenarioId != null) "연습 장면" else null)
    ReportRow(s.id, title, sub.joinToString(" · "), end != null)
}

/** 0번이면 "울음 기록 없음". */
internal fun cryText(n: Int) = if (n == 0) "울음 기록 없음" else "울음 ${n}번"

/** 운전자가 소리 바꾸기를 청해 열린 에피소드의 이름(두 폰 triggerText와 같은 낱말). */
private const val SWITCH_SOUND_NAME = "소리 바꾸기 요청"

/** 에피소드 시작 이름(두 폰 triggerText와 같음): 울음 경보 '울음 감지', 운전자 요청 '소리 바꾸기 요청'. 리포트 '자세히' 머리 줄·띠 시작 표시가 쓴다. */
internal fun triggerText(t: EpisodeTrigger) = if (t == EpisodeTrigger.CRY_ALARM) "울음 감지" else SWITCH_SOUND_NAME

/**
 * 에피소드 카드 제목 머리(리포트 카드 '{머리} · {시각}부터'·정차 홈 카드 '{머리} · {시각}'·띠 읽기 이름): 울음 경보 '울음', 운전자 요청 '소리 바꾸기 요청'.
 * 울음 없이 소리만 바꾼 에피소드가 '울음'으로 보이지 않게 trigger로 정한다(울음 수 '울음 n번'은 울음 경보만 센다).
 */
internal fun episodeHead(t: EpisodeTrigger) = if (t == EpisodeTrigger.CRY_ALARM) "울음" else SWITCH_SOUND_NAME

// ── 상세 화면 상태(숫자 줄 → 에피소드 → 자세히 → 차가 한 일 | 조향 → 방법별 성적) ──

/** 숫자 칸 하나: 이름·값·부가. */
data class Stat(val label: String, val value: String, val sub: String? = null)

/** '자세히' 한 줄. [head]면 묶음 머리(에피소드 시각·'울음 직전 주행'·'제어 기록'). */
data class DetailLine(val text: String, val head: Boolean = false)

/** 차가 대신 한 일 한 줄: 이름(아이콘은 화면이 [kind]로 고른다)과 횟수·재생 초. */
data class ActionItem(val kind: CarActionKind, val name: String, val extra: String)

/** 울음 카드: 제목 '울음 · {시각}부터'와 띠. */
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
    val methods: List<MethodDots>
)

/** ISO 8601 시각 → epoch ms(못 읽으면 null). */
internal fun epochMs(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

internal fun reportView(b: ReportSummaryBody, settling: Boolean): ReportView {
    val start = epochMs(b.startedAt)
    val end = epochMs(b.endedAt)
    val title = if (start != null && end != null) "${TimeText.date(start)} ${TimeText.range(start, end)} · ${TimeText.duration(end - start)}" else b.startedAt
    val steer = steerBars(b.steering)
    return ReportView(
        title = title,
        stats = reportStats(b),
        episodes = b.timeline.mapNotNull { e ->
            val chart = episodeChart(e, settling) ?: return@mapNotNull null
            EpisodeCard("${episodeHead(e.trigger)} · ${epochMs(e.startedAt)?.let { TimeText.clock(it) } ?: ""}부터", chart)
        },
        details = detailLines(b, settling),
        // 0번은 한 일이 아니다(숫자 줄·3D 칩과 같은 기준)
        actions = b.carActions.filter { it.count > 0 }
            .map { ActionItem(it.action, actionName(it.action), "${it.count}번" + (it.totalS?.let { s -> " · ${s}초" } ?: "")) },
        callouts = b.carActions.filter { it.count > 0 }.mapNotNull { CALLOUT[it.action] }.toSet(),
        steer = steer,
        steerText = if (steer == null) steeringUnavailable(b.steering) else null,
        methods = methodDots(b.methodStats)
    )
}

private val CALLOUT = mapOf(
    CarActionKind.REAR_WINDOW_LOCK to Callout3d.LOCK,
    CarActionKind.SEAT_HEATER_OFF to Callout3d.HEAT,
    CarActionKind.AMBIENT_DIM to Callout3d.DIM,
    CarActionKind.CABIN_COOL to Callout3d.HVAC
)

/**
 * 숫자 줄. 값이 없는 칸은 뺀다(숫자를 만들어 내지 않는다).
 * - 울음 n번: 울음 경보 에피소드 수, 부가는 첫 울음 시각.
 * - 감지까지: 경보 지연(alarm_latency_s)의 평균(폰과 같은 값).
 * - 울음 시간: 타임라인 전체의 total_cry_s 합(폰 리포트 reportKpis와 같은 값·같은 이름, 들은 울음만). '그칠 때까지'는
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
        Stat("차가 한 일", "${b.carActions.filter { it.count > 0 }.map { it.action }.distinct().size}가지")
    )
}

/**
 * 에피소드 띠 하나. 0초 = 에피소드 시작(경보 시각, 소리 바꾸기면 요청 시각).
 * 리포트에 있는 사실만 그린다(폰 episodeBand와 같은 규칙):
 * - 소리 줄: 재생마다 방법 이름 칸(툴팁 초는 기록된 play_s), 재생 사이 빈틈은 이름 없이 시간만(그 사이 판정을 멈췄을 수도 있어 '듣기 틈'이라 단정하지 않는다, [soundTip]).
 * - 울음 줄은 없다: report_summary에 울음 구간이 없고 경보 지연은 들은 울음 시간이 아니다. 들은 울음 합은 [EpisodeChartData.outcomes]의 '울음 n초'.
 * - 시간축 표시는 시각을 아는 것만: 시작(감지 지연 초 또는 '소리 바꾸기'), 다시 울음(그 때문에 시작한 자동 2차 재생의 시작).
 * - 결과(쉼터 제안·그쳤어요)는 판정 시각이 리포트에 없어 축 밖 [EpisodeChartData.outcomes]로 둔다(재생 끝에 찍으면 거짓 시각).
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
        add(ChartMark(0, if (e.trigger != EpisodeTrigger.CRY_ALARM) triggerText(e.trigger) else e.alarmLatencyS?.let { "감지 %.1f초".format(it) } ?: "감지"))
        if (e.recried) plays.firstOrNull { it.first.purpose == PlayPurpose.AUTO_SECOND }?.let { add(ChartMark(sec(it.second), "다시 울음")) }
    }
    val outcomes = listOfNotNull(
        e.totalCryS.takeIf { it > 0 }?.let { "울음 ${it}초" },
        // 묻지 못했거나 확정 전이면 제안했다고 보이지 않는다
        "쉼터 제안".takeIf { e.stop?.let { it.mode == HandoffKind.REST_OFFER && toldShown(it.toldDriver, settled = !settling) } == true },
        resultText(EpisodeResult.SUCCESS).takeIf { e.result == EpisodeResult.SUCCESS || e.second?.result == EpisodeResult.SUCCESS }
    )
    return EpisodeChartData(maxOf(sounds.maxOf { it.to }, 1), sounds, marks, outcomes, episodeHead(e.trigger))
}

/** 조향 막대 셋(평소·울음 중·달랜 뒤). 값이 없는 막대는 뺀다. 비교할 수 없으면 null. */
internal fun steerBars(s: Steering): List<SteerBar>? = if (!s.available) {
    null
} else {
    listOfNotNull(
        s.baselinePerMin?.let { SteerBar("평소", it, false) },
        s.duringCryPerMin?.let { SteerBar("울음 중", it, true) },
        s.afterResponsePerMin?.let { SteerBar("달랜 뒤", it, false) }
    ).ifEmpty { null }
}

private fun steeringUnavailable(s: Steering) =
    if (!s.available && s.unavailableReason == SteeringUnavailableReason.NO_CRY) "울음이 없어 비교하지 않았어요" else "비교할 구간이 없어요"

/** 3번 미만이면 "아직 모름"(Params.statMinShown). 아는 것 먼저, 그친 비율 높은 순. */
internal fun methodDots(stats: List<MethodStat>?, p: Params = Params()) = stats.orEmpty()
    .map { MethodDots(it.label, it.successes, it.attempts, it.attempts >= p.statMinShown) }
    .sortedWith(compareByDescending<MethodDots> { it.known }.thenByDescending { if (it.n == 0) 0.0 else it.ok.toDouble() / it.n })

/**
 * '자세히': 디자인에 자리가 없는 사실(출력 경로·줄임·연습 장면·울음별 재생·결과·정차·울음 직전 주행·제어 기록). 줄은 '이름 · 값'(콜론 없음),
 * 결과·뺀 이유·소리 나간 곳은 두 폰과 같은 낱말(`report.route`).
 */
internal fun detailLines(b: ReportSummaryBody, settling: Boolean) = buildList {
    if (b.simulated) add(DetailLine("연습 장면으로 만든 기록이에요"))
    when (b.outputRoute) {
        // 폴백이 한 번이라도 있었다는 값이라 원인·전부를 말하지 않는다(폰 report.route와 같은 글)
        OutputRoute.REAR_ZONE -> add(DetailLine("소리 나간 곳 · 뒷좌석"))
        OutputRoute.DEFAULT_ZONE_FALLBACK -> add(DetailLine("소리 나간 곳 · 앞에서도 들림"))
        OutputRoute.NO_PLAYBACK -> Unit // 재생이 없었으면 출력 경로를 보이지 않는다
    }
    if (b.truncated == true) add(DetailLine("기록이 많아 일부를 줄였어요"))
    if (b.timeline.isEmpty()) add(DetailLine("기록된 울음이 없어요")) // 통화 중 경보처럼 에피소드 없이 기록만 한 경보도 있다(울음이 없었다고 단정하지 않는다)
    b.timeline.forEach { e ->
        val at = epochMs(e.startedAt)?.let { TimeText.clock(it) } ?: ""
        add(
            DetailLine(
                "$at " + triggerText(e.trigger) +
                    (if (e.trigger == EpisodeTrigger.CRY_ALARM) e.alarmLatencyS?.let { " · 감지까지 ${"%.1f".format(it)}초" } else null).orEmpty(),
                head = true
            )
        )
        add(DetailLine("${e.sound?.label ?: "재생 없음"} · ${e.playS}초" + (e.plays.size.takeIf { it > 1 }?.let { " · 재생 ${it}번" } ?: "")))
        e.plays.forEach { pl ->
            val span = epochMs(pl.startedAt)?.let { a -> epochMs(pl.endedAt)?.let { z -> TimeText.range(a, z) } } ?: ""
            add(DetailLine("· ${purpose(pl.purpose)} ${method(pl.method)} $span ${pl.playS}초"))
        }
        add(DetailLine("결과 · " + resultText(e.result)))
        e.excludedReason?.let { add(DetailLine("성적에서 뺌 · " + excludedText(it))) }
        if (e.recried) add(DetailLine("다시 울음"))
        e.second?.let { add(DetailLine("2차 · " + secondText(it))) }
        e.stop?.let { add(DetailLine("정차 · " + stopText(it, settling))) }
        add(DetailLine("울음 시간 ${e.totalCryS}초"))
    }
    if (b.preCryContext.isNotEmpty()) add(DetailLine("울음 직전 주행", head = true))
    b.preCryContext.forEach { c ->
        add(DetailLine("평균 ${c.speedKmhAvg}km/h · 최고 ${c.speedKmhMax}km/h · 브레이크 ${c.brakePresses}번"))
        add(
            DetailLine(
                "큰 조향 ${c.steeringReversals}번" + (if (c.turnSignalUsed) " · 방향지시등 씀" else "") + " · 기어 ${c.gear.name}" +
                    (c.cabinTempC?.let { " · 실내 ${"%.1f".format(it)}℃" } ?: "") + if (c.playing) " · 재생 중" else ""
            )
        )
    }
    add(DetailLine("제어 기록", head = true))
    if (b.controls.isEmpty()) add(DetailLine("없어요"))
    b.controls.forEach { add(DetailLine("${it.api} ${it.target} · 성공 ${it.succeeded} · 실패 ${it.failed} · 다시 ${it.retries}")) }
}

/** 이름은 판단 코어 methodLabel 하나. 받은 모르는 방식(UNKNOWN)만 "기타". */
private fun method(m: SoundMethod) = methodKindOf(m)?.let(::methodLabel) ?: "기타"

private fun purpose(p: PlayPurpose) = when (p) {
    PlayPurpose.AUTO_FIRST -> "자동 1차"
    PlayPurpose.AUTO_SECOND -> "자동 2차"
    PlayPurpose.REQUEST -> "운전자 요청"
}

/** 울음 결과 낱말(차·두 폰 같은 글자): 리포트 '자세히'·울음 띠·정차 홈 흐름이 이 하나를 쓴다. 1차 시도가 없으면 null. */
internal fun resultText(r: EpisodeResult?) = when (r) {
    EpisodeResult.SUCCESS -> "그침"
    EpisodeResult.FAILURE -> "계속 울음"
    EpisodeResult.UNDETERMINED -> "판정하지 못함"
    null -> "1차 시도 없음"
}

/** 성적에서 뺀 이유(차·두 폰 같은 글자. 열거 이름을 그대로 쓰지 않는다). */
internal fun excludedText(e: ExcludedReason) = when (e) {
    ExcludedReason.SESSION_END -> "탑승이 끝남"
    ExcludedReason.DRIVER_STOP -> "운전자가 멈춤"
    ExcludedReason.CALL -> "통화 중"
    ExcludedReason.GEAR_TO_PARK, ExcludedReason.STARTED_IN_PARK -> "정차 중"
    ExcludedReason.SWITCH_SOUND -> "소리 바꾸기 요청"
    ExcludedReason.FEEDING_WINDOW -> "수유 시간대"
    ExcludedReason.SESSION_CAP -> SESSION_CAP_TEXT
    ExcludedReason.MIC_ERROR -> "마이크 오류"
    ExcludedReason.DETECTOR_TIMEOUT -> "감지 시간 초과"
    ExcludedReason.INPUT_SILENCED -> "마이크 입력이 막힘"
    ExcludedReason.FOCUS_LOSS -> "소리를 이어서 틀지 못함"
    ExcludedReason.SUSPEND -> "차가 절전에 들어감"
}

/** 달래기 상한(뺀 이유·정차 이유가 같은 낱말. 인계 알림 '달래기 시간이 끝났어요'와 같은 말). */
private const val SESSION_CAP_TEXT = "달래기 20분 끝남"

private fun secondText(sec: SecondAttempt): String {
    val what = when (sec.action) {
        // 분기는 고른 사실이다. 실제로 낮췄는지는 car_actions cabin_cool이 보인다
        SecondAction.COMFORT_COOL -> "더운 실내로 판단했어요"
        SecondAction.FEEDING_WINDOW -> "수유 시간대로 판단했어요"
        SecondAction.OTHER_SOUND -> "다른 소리로 바꿨어요" + (sec.sound?.let { " · ${it.label}" } ?: "")
        SecondAction.FOCUS_DENIED -> "소리를 이어서 틀지 못했어요"
    }
    return what + (sec.result?.let { " · ${resultText(it)}" } ?: "")
}

/** [settling]: 인계 행에 아직 확정 안 된(말하는 종류인데 told_driver null) 것이 있으면(`handoffSettled` 거짓) 한 줄로만 보인다. */
internal fun stopText(st: StopInfo, settling: Boolean = false) = buildString {
    append(
        when (st.reason) {
            StopReason.ATTEMPT_LIMIT -> "두 번 시도 뒤"
            StopReason.SESSION_CAP -> SESSION_CAP_TEXT
            StopReason.FEEDING_WINDOW -> "수유 시간대"
            StopReason.NO_SOUND -> "뒷좌석 출력 없음"
        }
    )
    append(" · ")
    if (settling) {
        append("정리하는 중이에요")
        return@buildString
    }
    val told = toldShown(st.toldDriver, settled = true) // 인계 문장을 못 말했으면 "했다"고 쓰지 않는다(폰 앱과 같은 글). 확정 전은 위에서 끝났다
    append(
        when (st.mode) {
            HandoffKind.REST_OFFER -> if (told) "${st.restName ?: "쉼터"}에 들르자고 제안했어요" else "쉼터는 묻지 못했어요"
            HandoffKind.ARRIVAL_NOTICE -> if (told) "도착 안내를 했어요" else "도착 안내는 하지 못했어요"
            HandoffKind.NO_REST -> if (told) "경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요" else "안전한 곳에 세우도록 안내하지 못했어요"
            HandoffKind.PARKED -> if (st.reason == StopReason.NO_SOUND) "차가 선 채로 소리를 틀지 못했어요" else "차가 선 채로 달래기를 멈췄어요"
        }
    )
    if (st.waypointAdded) append(" · 경유지 추가")
    if (st.guardianNotified) append(" · 다른 보호자에게 알림")
}

/**
 * 차가 한 일 이름: 세 앱 같은 글자(report.actions). 사실대로: 열선은 아기 자리 2열만 끄고,
 * 실내 온도 낮춤은 성공한 공조 줄만 세고, 알림은 다른 보호자에게 간다(F21).
 */
internal fun actionName(a: CarActionKind) = when (a) {
    CarActionKind.REAR_PLAYBACK -> "뒷좌석 재생"
    CarActionKind.DEFAULT_ZONE_PLAYBACK -> "앞에서도 들리게 재생"
    CarActionKind.SEAT_HEATER_OFF -> "뒷좌석 열선 끔"
    CarActionKind.REAR_WINDOW_LOCK -> "뒷창 잠금"
    CarActionKind.CABIN_COOL -> "실내 온도 낮춤"
    CarActionKind.READING_LIGHT_OFF -> "독서등 끔"
    CarActionKind.AMBIENT_DIM -> "조명 낮춤"
    CarActionKind.WAYPOINT_ADDED -> "경유지 추가"
    CarActionKind.ARRIVAL_NOTICE -> "도착 안내"
    CarActionKind.GUARDIAN_ALERT -> "다른 보호자 알림"
}
