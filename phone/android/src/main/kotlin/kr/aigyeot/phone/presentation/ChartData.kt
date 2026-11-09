package kr.aigyeot.phone.presentation

import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.MAX_INTERVAL_MS
import kr.aigyeot.phone.domain.model.MIN_INTERVALS
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// 차트에 넣는 값(디자인 v6 시안 js/charts.js의 DATA 자리). 저장하지 않고 그릴 때 기록·리포트에서 계산한다(P4).
// 순수 함수라 JUnit으로 본다(ChartDataTest). 실제 데이터에 붙이는 규칙은 진행 기록 '결정' 1~8.

/** 차트 안 시각만 24시간제 'HH:MM'(사용자 결정 11-09, 시안 v6-9). 나머지 화면은 7.1 '오전/오후 h:mm'. */
fun hhmm(minuteOfDay: Int): String = String.format(Locale.ROOT, "%02d:%02d", (minuteOfDay / 60) % 24, minuteOfDay % 60)

fun hhmm(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).let { hhmm(it.hour * 60 + it.minute) }

/** 하루 안 구간(분, 0~1440). */
data class DaySpan(val from: Int, val to: Int, val tip: String)

data class DayMark(val at: Int, val tip: String)

/** 줄 하나: 머리('이름 · 부가 · 값')와 트랙 위 표시. */
data class DayLane(val kind: LaneKind, val name: String, val sub: String?, val value: String, val spans: List<DaySpan> = emptyList(), val marks: List<DayMark> = emptyList())

enum class LaneKind { SLEEP, FEED, DIAPER, CAR }

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
        else DaySpan(a, b, "$name ${hhmm(a)}–${hhmm(b)}" + if (b > a) " · ${durationText((b - a) * MINUTE)}" else "")
    }
    fun startedToday(kind: CareKind) = live.filter { it.kind == kind && it.startAt in from until to }
    val lanes = mutableListOf<DayLane>()
    if (care.sleepDiaperEnabled) {
        val s = spans(CareKind.SLEEP, "잠")
        val total = durationOn(live, CareKind.SLEEP, day, zone)
        lanes += DayLane(LaneKind.SLEEP, "잠", if (s.isEmpty()) null else "${s.size}번", if (s.isEmpty()) "0번" else durationText(total), spans = s)
    }
    lanes += DayLane(LaneKind.FEED, "수유", null, "${startedToday(CareKind.FEED).size}번", spans = spans(CareKind.FEED, "수유"))
    if (care.sleepDiaperEnabled) {
        val d = startedToday(CareKind.DIAPER).sortedBy { it.startAt }
        lanes += DayLane(LaneKind.DIAPER, "기저귀", null, "${d.size}번", marks = d.map { e -> minuteIn(e.startAt, from).let { DayMark(it, "기저귀 ${hhmm(it)}") } })
    }
    if (showCar) {
        val lines = carLines(reports)
        val drives = lines.filter { it.isDrive && inDay(it.startAt, it.endAt ?: it.startAt) }.map { l ->
            val a = minuteIn(maxOf(l.startAt, from), from)
            val b = minuteIn(minOf(l.endAt ?: l.startAt, to), from)
            DaySpan(a, b, "탑승 ${hhmm(a)}–${hhmm(b)} · ${durationText((b - a) * MINUTE)}")
        }
        val cries = lines.filter { !it.isDrive && it.startAt in from until to }.map { l -> minuteIn(l.startAt, from).let { DayMark(it, "차에서 울음 ${hhmm(it)}") } }
        val driveMin = drives.sumOf { it.to - it.from }
        lanes += DayLane(LaneKind.CAR, "차에서", if (driveMin > 0) "탑승 ${durationText(driveMin * MINUTE)}" else null, "울음 ${cries.size}번", spans = drives, marks = cries)
    }
    val nowMin = if (now in from until to) minuteIn(now, from) else null
    return DayChartData(lanes, nowMin)
}

/** 7일 막대 하나. 값이 없으면 null(막대 없음). */
data class WeekBar(val day: LocalDate, val label: String, val value: Long?, val tip: String?)

/** 지난 7일 날짜(오늘 포함, 오래된 것부터). */
fun lastWeek(now: Long, zone: ZoneId): List<LocalDate> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (PATTERN_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
}

/** 막대 밑 이름: 요일 한 글자, 오늘은 "오늘". 툴팁 날짜는 "11월 9일". */
fun weekLabel(d: LocalDate, today: LocalDate) = if (d == today) "오늘" else "월화수목금토일"[d.dayOfWeek.value - 1].toString()

fun monthDay(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일"

private fun median(xs: List<Long>): Long? = xs.sorted().let { s ->
    when {
        s.isEmpty() -> null
        s.size % 2 == 1 -> s[s.size / 2]
        else -> (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/** 수유 간격 7일 막대 + '평소'(앞 6일 모든 간격의 중앙값, R14와 같이 10시간 넘는 간격은 빼고 5개 이상일 때만). */
data class IntervalWeek(val bars: List<WeekBar>, val usualMs: Long?, val todayMs: Long?)

fun intervalWeek(care: CareFile, now: Long, zone: ZoneId): IntervalWeek {
    val days = lastWeek(now, zone)
    val today = days.last()
    val starts = care.events.filter { it.kind == CareKind.FEED && it.deletedAt == null && it.startAt <= now }.map { it.startAt }.sorted()
    // 간격은 뒤 수유가 시작한 날에 넣는다.
    val pairs = starts.zipWithNext { a, b -> Instant.ofEpochMilli(b).atZone(zone).toLocalDate() to b - a }.filter { it.second <= MAX_INTERVAL_MS }
    val bars = days.map { d ->
        val v = median(pairs.filter { it.first == d }.map { it.second })
        WeekBar(d, weekLabel(d, today), v, v?.let { "${monthDay(d)} · ${durationText(it)}" })
    }
    val before = pairs.filter { it.first in days.dropLast(1) }.map { it.second }
    return IntervalWeek(bars, if (before.size >= MIN_INTERVALS) median(before) else null, bars.last().value)
}

/** 차에서 울음 7일 막대(리포트 울음 경보 에피소드 수) + 평균 감지 지연(초, 없으면 null). */
data class CarCryWeek(val bars: List<WeekBar>, val total: Int, val avgLatencyS: Double?)

fun carCryWeek(reports: Collection<ReportSummaryBody>, now: Long, zone: ZoneId): CarCryWeek {
    val days = lastWeek(now, zone)
    val today = days.last()
    val eps = reports.flatMap { r -> r.timeline.filter { it.trigger == EpisodeTrigger.CRY_ALARM } }
        .mapNotNull { e -> instantOf(e.startedAt)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() to e } }
        .filter { it.first in days }
    val bars = days.map { d -> eps.count { it.first == d }.toLong().let { n -> WeekBar(d, weekLabel(d, today), n, "${monthDay(d)} · ${n}번") } }
    val lat = eps.mapNotNull { it.second.alarmLatencyS }
    return CarCryWeek(bars, eps.size, if (lat.isEmpty()) null else lat.average())
}

/** 잠 패턴 히트맵: 7일 × 48칸(30분). 잠 기록(끝난 것, 진행 중은 지금까지)과 겹친 칸이 참. */
data class SleepGrid(val days: List<LocalDate>, val labels: List<String>, val rows: List<BooleanArray>, val avgMs: Long?, val sleepDays: Int, val perDayMs: List<Long> = emptyList()) {
    /** 화면 읽기용(날마다 끝난 잠이 그날에 걸친 실제 길이, U31). */
    val summary: String get() = "지난 7일 잠 패턴: " + labels.indices.joinToString(", ") { "${labels[it]} ${durationText(perDayMs.getOrElse(it) { 0L })}" }
}

fun sleepGrid(care: CareFile, now: Long, zone: ZoneId): SleepGrid {
    val days = lastWeek(now, zone)
    val today = days.last()
    val sleeps = care.events.filter { it.kind == CareKind.SLEEP && it.deletedAt == null && (it.endAt != null || it.isOngoing) }
    val rows = days.map { d ->
        val (from, _) = dayBounds(d, zone)
        BooleanArray(48) { c ->
            val a = from + c * 30 * MINUTE
            val b = a + 30 * MINUTE
            sleeps.any { e -> e.startAt < b && (e.endAt ?: now) > a }
        }
    }
    // U31·패턴 '하루 잠'과 같은 값: 지난 7일(오늘 포함) 중 끝난 잠이 그날에 걸친 시간이 0보다 큰 날의 평균(같은 화면에서 두 숫자가 어긋나지 않게).
    val all = days.map { durationOn(care.events, CareKind.SLEEP, it, zone) }
    val per = all.filter { it > 0 }
    return SleepGrid(days, days.map { weekLabel(it, today) }, rows, if (per.isEmpty()) null else per.sum() / per.size, per.size, all)
}

/**
 * 에피소드 띠(시안 Charts.episode). 초는 감지(에피소드 시작)부터. 시간축 표시([marks])는 시각을 아는 것만(시작, 자동 2차 시작),
 * 결과(쉼터 제안·그쳤어요)는 리포트에 그 시각이 없어 축 밖 [outcomes]로 둔다(마지막 재생 끝에 찍으면 거짓 시각이 된다).
 */
data class EpisodeBand(val totalS: Int, val sounds: List<EpisodeSeg>, val gaps: List<EpisodeSeg>, val marks: List<EpisodeMarkAt>, val startAt: Long?, val outcomes: List<String> = emptyList())

data class EpisodeSeg(val from: Int, val to: Int, val name: String)

data class EpisodeMarkAt(val at: Int, val label: String)

fun episodeBand(e: TimelineEpisode, songs: List<SongEntry>): EpisodeBand {
    val t0 = instantOf(e.startedAt)
    fun sec(s: String) = t0?.let { base -> instantOf(s)?.let { ((it - base) / 1000).toInt().coerceAtLeast(0) } }
    val timed = e.plays.mapNotNull { p ->
        val a = sec(p.startedAt) ?: return@mapNotNull null
        val b = sec(p.endedAt) ?: (a + p.playS)
        p to EpisodeSeg(a, maxOf(b, a + 1), playName(songs, p))
    }.sortedBy { it.second.from }
    val plays = timed.map { it.second }
    val gaps = plays.zipWithNext { x, y -> if (y.from > x.to) EpisodeSeg(x.to, y.from, "듣기 틈") else null }.filterNotNull()
    val end = maxOf(plays.maxOfOrNull { it.to } ?: 0, 1)
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
    return EpisodeBand(end, plays, gaps, marks, t0, outcomes)
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
    val days = lastWeek(now, zone)
    val live = care.events.filter { it.kind == CareKind.FEED && it.deletedAt == null }
    fun avg(ds: List<LocalDate>): Double? {
        val per = ds.map { d -> live.count { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() == d } }.filter { it > 0 }
        return if (per.isEmpty()) null else per.sum().toDouble() / per.size
    }
    val now7 = avg(days) ?: return null
    val prev7 = avg(days.map { it.minusDays(PATTERN_DAYS.toLong()) }) ?: return null
    val d = now7 - prev7 // 평균끼리 뺀 뒤 마지막에만 반올림한다
    val n = String.format(Locale.ROOT, "%.1f", kotlin.math.abs(d))
    return when {
        n == "0.0" -> null
        d > 0 -> "지난주보다 ${n}회 많아요"
        else -> "지난주보다 ${n}회 적어요"
    }
}
