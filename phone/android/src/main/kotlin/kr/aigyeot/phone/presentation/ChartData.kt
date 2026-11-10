package kr.aigyeot.phone.presentation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.CareStats
import kr.aigyeot.phone.domain.model.FeedGap
import kr.aigyeot.phone.domain.model.MINUTE
import kr.aigyeot.phone.domain.model.PATTERN_DAYS
import kr.aigyeot.phone.domain.model.SongEntry
import kr.aigyeot.phone.domain.model.carLines
import kr.aigyeot.phone.domain.model.durationOn
import kr.aigyeot.phone.domain.model.durationText
import kr.aigyeot.phone.domain.model.instantOf
import kr.aigyeot.phone.domain.model.isOngoing
import kr.aigyeot.phone.domain.model.playName
import kr.aigyeot.phone.domain.model.triggerText
import kr.aigyeot.shared.EpisodeResult
import kr.aigyeot.shared.EpisodeTrigger
import kr.aigyeot.shared.HandoffKind
import kr.aigyeot.shared.PlayPurpose
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.TimelineEpisode

// 차트에 넣는 값(디자인 v6 시안 js/charts.js의 DATA 자리). 저장하지 않고 그릴 때 기록·리포트에서 계산한다(P4).
// 순수 함수라 JUnit으로 본다(ChartDataTest). 실제 데이터에 붙이는 규칙은 진행 기록 '결정' 1~8.

/** 차트 안 시각만 24시간제 'HH:MM'(사용자 결정 11-09, 시안 v6-9). 나머지 화면은 7.1 '오전/오후 h:mm'. */
fun hhmm(minuteOfDay: Int): String = String.format(Locale.ROOT, "%02d:%02d", (minuteOfDay / 60) % 24, minuteOfDay % 60)

fun hhmm(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).let { hhmm(it.hour * 60 + it.minute) }

/** 하루 안 구간(분, 0~1440). */
data class DaySpan(val from: Int, val to: Int, val tip: String)

data class DayMark(val at: Int, val tip: String)

/** 줄 하나: 머리('이름 · 부가 · 값')와 트랙 위 표시. */
data class DayLane(val kind: LaneKind, val sub: String?, val value: String, val spans: List<DaySpan> = emptyList(), val marks: List<DayMark> = emptyList()) {
    val name: String get() = kind.label
}

/** 줄 종류와 머리 이름(design.day.*). */
enum class LaneKind(val label: String) { SLEEP("잠"), FEED("수유"), DIAPER("기저귀"), CAR("차에서") }

/** 오늘 하루 띠(시안 Charts.day). `nowMin`은 오늘이면 지금 분, 아니면 null. */
data class DayChartData(val lanes: List<DayLane>, val nowMin: Int?) {
    /** 화면 읽기용 한 줄("잠 8시간 5분, 수유 4번, …"). */
    val summary: String get() = "오늘 하루: " + lanes.joinToString(", ") { "${it.name} ${it.value}" }
}

private fun dayBounds(day: LocalDate, zone: ZoneId) =
    day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

private fun minuteIn(ms: Long, from: Long) = ((ms - from) / MINUTE).toInt().coerceIn(0, 1440)

/**
 * 오늘 하루 띠. 잠·수유는 그날에 걸친 구간(진행 중은 지금까지, 끝 모름은 시작 점), 기저귀는 시각 점,
 * '차에서'는 그날 리포트의 탑승 구간(세션 시작~끝, 중간 정차 포함이라 '탑승', 차 앱과 같음)과 울음 경보 에피소드 시각. 잠·기저귀를 끄면 그 줄을 뺀다(R16).
 * 수유·기저귀 횟수는 그날 시작한 기록(R13·패턴과 같음), 잠은 그날에 걸친 기록 수와 끝난 잠의 길이 합(U31).
 */
fun dayChart(care: CareFile, reports: Collection<ReportSummaryBody>, day: LocalDate, now: Long, zone: ZoneId, showCar: Boolean): DayChartData {
    val (from, to) = dayBounds(day, zone)
    val live = care.events.filter { it.deletedAt == null }
    fun end(e: CareEvent) = e.endAt ?: if (e.isOngoing) now else e.startAt
    // 그날과 실제로 겹치는 구간만(자정에 끝난 어제 잠은 빼고), 길이 없는 점(끝 모름)은 그날 시작한 것만.
    fun inDay(start: Long, stop: Long) = start < to && (stop > from || (stop == start && start >= from))
    fun overlapping(kind: CareKind) = live.filter { it.kind == kind && inDay(it.startAt, end(it)) }.sortedBy { it.startAt }
    fun spans(kind: CareKind, name: String) = overlapping(kind).map { e ->
        val a = minuteIn(maxOf(e.startAt, from), from)
        val b = minuteIn(minOf(end(e), to), from)
        // 끝 시각 모름은 끝을 만들지 않는다(7.1 log.row.feedUnknown과 같은 말).
        if (e.endAt == null && !e.isOngoing) DaySpan(a, a, "$name ${hhmm(a)} · 끝 시각 모름")
        // 길이 글은 그날에 든 기록 시각의 차이(분으로 내린 그림 좌표의 차이가 아니다: 기록 목록·잠 합계와 같은 값).
        // 붙일지도 그 차이로 정한다: 같은 분 안에 끝난 기록도 길이가 있다(1분 미만이면 기록 목록과 같은 "1분 미만").
        else {
            val ms = minOf(end(e), to) - maxOf(e.startAt, from)
            DaySpan(a, b, "$name ${hhmm(a)}–${hhmm(b)}" + if (ms > 0) " · ${durationText(ms)}" else "")
        }
    }
    fun startedToday(kind: CareKind) = live.filter { it.kind == kind && it.startAt in from until to }
    val lanes = mutableListOf<DayLane>()
    if (care.sleepDiaperEnabled) {
        val s = spans(CareKind.SLEEP, "잠")
        val total = durationOn(live, CareKind.SLEEP, day, zone)
        lanes += DayLane(LaneKind.SLEEP, if (s.isEmpty()) null else "${s.size}번", if (s.isEmpty()) "0번" else durationText(total), spans = s)
    }
    lanes += DayLane(LaneKind.FEED, null, "${startedToday(CareKind.FEED).size}번", spans = spans(CareKind.FEED, "수유"))
    if (care.sleepDiaperEnabled) {
        val d = startedToday(CareKind.DIAPER).sortedBy { it.startAt }
        lanes += DayLane(LaneKind.DIAPER, null, "${d.size}번", marks = d.map { e -> minuteIn(e.startAt, from).let { DayMark(it, "기저귀 ${hhmm(it)}") } })
    }
    if (showCar) {
        val lines = carLines(reports)
        // 탑승 길이는 그날에 든 세션 시각의 차이(그림 좌표는 분으로 내림). 합계도 같은 값을 더한다.
        val driveIn = lines.filter { it.isDrive && inDay(it.startAt, it.endAt ?: it.startAt) }.map { l -> maxOf(l.startAt, from) to minOf(l.endAt ?: l.startAt, to) }
        val drives = driveIn.map { (s, e) ->
            val a = minuteIn(s, from)
            val b = minuteIn(e, from)
            DaySpan(a, b, "탑승 ${hhmm(a)}–${hhmm(b)} · ${durationText(e - s)}")
        }
        val cries = lines.filter { !it.isDrive && it.startAt in from until to }.map { l -> minuteIn(l.startAt, from).let { DayMark(it, "차에서 울음 ${hhmm(it)}") } }
        val driveMs = driveIn.sumOf { (s, e) -> e - s }
        lanes += DayLane(LaneKind.CAR, if (driveMs > 0) "탑승 ${durationText(driveMs)}" else null, "울음 ${cries.size}번", spans = drives, marks = cries)
    }
    val nowMin = if (now in from until to) minuteIn(now, from) else null
    return DayChartData(lanes, nowMin)
}

/** 7일 막대 하나. 값이 없으면 null(막대 없음). */
data class WeekBar(val day: LocalDate, val label: String, val value: Long?, val tip: String?)

/** 막대 밑 이름: 요일 한 글자, 오늘은 "오늘". 툴팁 날짜는 "11월 9일". */
fun weekLabel(d: LocalDate, today: LocalDate) = if (d == today) "오늘" else "월화수목금토일"[d.dayOfWeek.value - 1].toString()

fun monthDay(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일"

/** 수유 간격 7일 막대 + '평소'(앞 6일 모든 간격의 중앙값, R14와 같이 10시간 넘는 간격은 빼고 5개 이상일 때만). */
data class IntervalWeek(val bars: List<WeekBar>, val usualMs: Long?) {
    /** 오늘 막대 값(P4: 막대에서 계산). */
    val todayMs: Long? get() = bars.lastOrNull()?.value
}

fun intervalWeek(care: CareFile, now: Long, zone: ZoneId): IntervalWeek {
    val days = CareStats.lastDays(PATTERN_DAYS, now, zone)
    val today = days.last()
    // 간격은 뒤 수유가 시작한 날에 넣는다.
    fun dayOf(g: FeedGap) = Instant.ofEpochMilli(g.end).atZone(zone).toLocalDate()
    val gaps = CareStats.feedGaps(CareStats.feedStarts(care.events, until = now))
    val bars = days.map { d ->
        val v = CareStats.median(gaps.filter { dayOf(it) == d }.map { it.ms })
        WeekBar(d, weekLabel(d, today), v, v?.let { "${monthDay(d)} · ${durationText(it)}" })
    }
    return IntervalWeek(bars, CareStats.usualFeedGapMs(care.events, now, zone))
}

/** 차에서 울음 7일 막대(리포트 울음 경보 에피소드 수) + 평균 감지 지연(초, 없으면 null). */
data class CarCryWeek(val bars: List<WeekBar>, val avgLatencyS: Double?) {
    /** 7일 합(P4: 막대에서 계산). */
    val total: Int get() = bars.sumOf { it.value ?: 0L }.toInt()
}

fun carCryWeek(reports: Collection<ReportSummaryBody>, now: Long, zone: ZoneId): CarCryWeek {
    val days = CareStats.lastDays(PATTERN_DAYS, now, zone)
    val today = days.last()
    val eps = CareStats.cryAlarms(reports).map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() to it.episode }.filter { it.first in days }
    val bars = days.map { d -> eps.count { it.first == d }.toLong().let { n -> WeekBar(d, weekLabel(d, today), n, "${monthDay(d)} · ${n}번") } }
    val lat = eps.mapNotNull { it.second.alarmLatencyS }
    return CarCryWeek(bars, if (lat.isEmpty()) null else lat.average())
}

/** 잠 패턴 히트맵: 7일 × 48칸(30분). 잠 기록(끝난 것, 진행 중은 지금까지)과 겹친 칸이 참. */
/**
 * [perDayMs]: 날마다 끝난 잠이 그날에 걸친 길이(U31). 평균·분모는 여기서 계산한다(P4).
 * U31·패턴 '하루 잠'과 같은 값: 지난 7일(오늘 포함) 중 그 길이가 0보다 큰 날의 평균(같은 화면에서 두 숫자가 어긋나지 않게).
 */
data class SleepGrid(val days: List<LocalDate>, val rows: List<BooleanArray>, val perDayMs: List<Long>) {
    val labels: List<String> get() = days.map { weekLabel(it, days.last()) }
    val sleepDays: Int get() = perDayMs.count { it > 0 }
    val avgMs: Long? get() = CareStats.perRecordedDay(perDayMs)

    /** 화면 읽기용(날마다 끝난 잠이 그날에 걸친 실제 길이, U31). */
    val summary: String get() = "지난 7일 잠 패턴: " + labels.indices.joinToString(", ") { "${labels[it]} ${durationText(perDayMs[it])}" }
}

fun sleepGrid(care: CareFile, now: Long, zone: ZoneId): SleepGrid {
    val days = CareStats.lastDays(PATTERN_DAYS, now, zone)
    val sleeps = care.events.filter { it.kind == CareKind.SLEEP && it.deletedAt == null && (it.endAt != null || it.isOngoing) }
    val rows = days.map { d ->
        val (from, _) = dayBounds(d, zone)
        BooleanArray(48) { c ->
            val a = from + c * 30 * MINUTE
            val b = a + 30 * MINUTE
            sleeps.any { e -> e.startAt < b && (e.endAt ?: now) > a }
        }
    }
    return SleepGrid(days, rows, days.map { durationOn(care.events, CareKind.SLEEP, it, zone) })
}

/**
 * 에피소드 띠(시안 Charts.episode). 초는 감지(에피소드 시작)부터. 시간축 표시([marks])는 시각을 아는 것만(시작, 자동 2차 시작),
 * 결과(쉼터 제안·그쳤어요)는 리포트에 그 시각이 없어 축 밖 [outcomes]로 둔다(마지막 재생 끝에 찍으면 거짓 시각이 된다).
 */
data class EpisodeBand(val sounds: List<EpisodeSeg>, val marks: List<EpisodeMarkAt>, val startAt: Long?, val outcomes: List<String> = emptyList()) {
    /**
     * 재생 사이 칸(P4: 소리 구간에서 계산, 이름 없음). [sounds]는 시작 순서다. 그 사이 통화 등으로 판정을 멈췄을 수도 있고 리포트에는 그 사실을 알 칸이 없어
     * '듣기 틈'이라 단정하지 않는다(차 리포트 soundTip과 같은 규칙, 시안 charts.js의 '듣기 틈' 라벨과 다르게 한 곳).
     */
    val gaps: List<EpisodeSeg> get() = sounds.zipWithNext { x, y -> if (y.from > x.to) EpisodeSeg(x.to, y.from, null) else null }.filterNotNull()
    /** 축 길이(초): 마지막 재생 끝, 적어도 1(P4: 소리 구간에서 계산). */
    val totalS: Int get() = maxOf(sounds.maxOfOrNull { it.to } ?: 0, 1)
}

/**
 * 에피소드 띠 한 칸(초). [from]·[to]는 그림 좌표(시작·끝 시각, 최소 1초 폭), [name]은 재생 방법 이름(재생 사이 칸은 null),
 * [playS]는 차가 보낸 재생 길이(iOS `Seg.playS`와 같다).
 */
data class EpisodeSeg(val from: Int, val to: Int, val name: String?, val playS: Int? = null) {
    /** 툴팁·설명의 길이: 기록에 있는 재생 길이 그대로, 없으면(재생 사이 칸) 칸 길이. */
    val seconds: Int get() = playS ?: (to - from)

    /** 툴팁·설명 글: 재생은 "{이름} {n}초", 재생 사이 칸은 "{n}초"(차 soundTip과 같다). */
    val tip: String get() = name?.let { "$it ${seconds}초" } ?: "${seconds}초"
}

data class EpisodeMarkAt(val at: Int, val label: String)

fun episodeBand(e: TimelineEpisode, songs: List<SongEntry>): EpisodeBand {
    val t0 = instantOf(e.startedAt)
    fun sec(s: String) = t0?.let { base -> instantOf(s)?.let { ((it - base) / 1000).toInt().coerceAtLeast(0) } }
    val timed = e.plays.mapNotNull { p ->
        val a = sec(p.startedAt) ?: return@mapNotNull null
        val b = sec(p.endedAt) ?: (a + p.playS)
        p to EpisodeSeg(a, maxOf(b, a + 1), playName(songs, p), p.playS)
    }.sortedBy { it.second.from }
    val plays = timed.map { it.second }
    val marks = buildList {
        // 시작 표시: 울음 경보면 '감지 {지연}초', 소리 바꾸기 요청이면 그 이름(triggerText, 울음을 감지했다고 보이지 않게).
        add(
            EpisodeMarkAt(
                0,
                if (e.trigger != EpisodeTrigger.CRY_ALARM) triggerText(e.trigger)
                else e.alarmLatencyS?.let { "감지 ${String.format(Locale.ROOT, "%.1f", it)}초" } ?: "감지",
            ),
        )
        // 다시 울음: 그 때문에 시작한 자동 2차 재생의 시작(운전자 요청 재생은 다시 울음이 아니다). 없으면 표시하지 않는다.
        if (e.recried) timed.firstOrNull { it.first.purpose == PlayPurpose.AUTO_SECOND }?.let { add(EpisodeMarkAt(it.second.from, "다시 울음")) }
    }
    val outcomes = buildList {
        // 운전자에게 실제로 묻지 못한 쉼터는 제안했다고 보이지 않는다(U53, stopText·stopNoticeCount와 같은 규칙).
        if (e.stop?.let { it.mode == HandoffKind.REST_OFFER && it.toldDriver != false } == true) add("쉼터 제안")
        if (e.result == EpisodeResult.SUCCESS || e.second?.result == EpisodeResult.SUCCESS) add("그쳤어요")
    }
    return EpisodeBand(plays, marks, t0, outcomes)
}

/** 리포트 숫자 줄(결정 6). [cryS]는 울음 시간 합(그친 시각은 리포트에 없어 '그칠 때까지'로 쓰지 않는다). */
data class ReportKpis(val detectS: Double?, val cryS: Int, val actionKinds: Int)

fun reportKpis(b: ReportSummaryBody): ReportKpis {
    val lat = b.timeline.mapNotNull { it.alarmLatencyS }
    return ReportKpis(if (lat.isEmpty()) null else lat.average(), b.timeline.sumOf { it.totalCryS }, b.carActions.filter { it.count > 0 }.map { it.action }.distinct().size)
}

/** 주행 길이(분). */
fun reportMinutes(b: ReportSummaryBody): Long? {
    val s = instantOf(b.startedAt) ?: return null
    val e = instantOf(b.endedAt) ?: return null
    return ((e - s) / MINUTE).takeIf { it >= 0 }
}

/** 소수 한 자리 초("2.4초"). */
fun secondsText(s: Double) = String.format(Locale.ROOT, "%.1f초", s)

/** "1분당 큰 조향 보정" 값("0.8"). */
fun perMinText(v: Double) = String.format(Locale.ROOT, "%.1f", v)

/** 엔진 `Params.feedingWindowStart`(다음 수유 시간대 시작 = 마지막 수유 + 간격 × 0.8, IMPLEMENT 4장 `Calc.kt windowStartExpected`). */
const val FEEDING_WINDOW_START = 0.8

/**
 * 마지막 수유 막대(시안 .pmeter): 채움 = 지난 시간 / (간격 × 1.25), 눈금 = 다음 수유 시간대 시작의 자리.
 * 간격은 차 값이 있으면 차에 정해 둔 간격((시간대 시작 − 차의 마지막 수유) ÷ 0.8), 없으면 지난 7일 '평소'(앞 6일 중앙값).
 * 둘 다 없으면 null(막대를 그리지 않는다). 눈금은 차 값이 있고 그 시각이 마지막 수유 뒤일 때만.
 */
fun feedMeter(last: Long, now: Long, carLast: Long?, windowStart: Long?, usualMs: Long?): Pair<Float, Float?>? {
    val interval = if (carLast != null && windowStart != null && windowStart > carLast) ((windowStart - carLast) / FEEDING_WINDOW_START).toLong() else usualMs
    if (interval == null || interval <= 0) return null
    val full = interval * 1.25
    val tick = windowStart?.takeIf { it > last }?.let { ((it - last) / full).toFloat().coerceIn(0f, 1f) }
    return ((now - last) / full).toFloat().coerceIn(0f, 1f) to tick
}

/**
 * '지난주보다 {n}회 많아요/적어요'(문구표 design.chart, 시안 S.pattern): 지난 7일 하루 평균 수유 − 그 앞 7일 하루 평균.
 * 평균은 패턴과 같은 규칙(그날 시작한 수유 수를 수유한 날 수로 나눔, 소수 첫째 자리). 앞 7일에 수유한 날이 없거나 차이가 0.0이면 null.
 */
fun feedTrend(care: CareFile, now: Long, zone: ZoneId): String? {
    val days = CareStats.lastDays(PATTERN_DAYS, now, zone)
    val now7 = CareStats.perRecordedDay(CareStats.countPerDay(care.events, CareKind.FEED, days, zone)) ?: return null
    val prev7 = CareStats.perRecordedDay(CareStats.countPerDay(care.events, CareKind.FEED, days.map { it.minusDays(PATTERN_DAYS.toLong()) }, zone)) ?: return null
    val d = now7 - prev7 // 평균끼리 뺀 뒤 마지막에만 반올림한다
    val n = String.format(Locale.ROOT, "%.1f", kotlin.math.abs(d))
    return when {
        n == "0.0" -> null
        d > 0 -> "지난주보다 ${n}회 많아요"
        else -> "지난주보다 ${n}회 적어요"
    }
}
