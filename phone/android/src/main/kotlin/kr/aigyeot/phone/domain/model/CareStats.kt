package kr.aigyeot.phone.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kr.aigyeot.shared.EpisodeTrigger
import kr.aigyeot.shared.MethodStat
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.TimelineEpisode

/** 수유 간격 하나: [end]는 뒤 수유의 시작(간격이 속한 날을 정한다), [ms]는 앞 수유 시작부터. */
data class FeedGap(val end: Long, val ms: Long)

/** 리포트의 울음 경보 에피소드와 그 시작 시각. */
data class CryAlarm(val at: Long, val episode: TimelineEpisode)

/**
 * 돌봄 기록·리포트 집계의 공통 규칙 한 곳(iOS `CareStats`와 같은 이름). 패턴 숫자 칸(`carePattern`), 7일 막대·히트맵·지난주 대비(ChartData),
 * 마지막 수유 막대, 차 기록 줄·리포트 줄이 같은 함수를 쓴다. 기간이 다른 곳(7일 숫자 칸, 앞 6일 '평소')은 기간을 인자로 넘긴다.
 */
object CareStats {
    /** 오늘을 포함한 지난 [count]일(오래된 날부터, 기기 시간대). */
    fun lastDays(count: Int, now: Long, zone: ZoneId): List<LocalDate> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (count - 1 downTo 0).map { today.minusDays(it.toLong()) }
    }

    /** 수유 시작 시각(지운 기록 빼고, [from] ≤ 시작 ≤ [until], 오름차순). */
    fun feedStarts(events: List<CareEvent>, from: Long = Long.MIN_VALUE, until: Long = Long.MAX_VALUE): List<Long> =
        events.filter { it.kind == CareKind.FEED && it.deletedAt == null && it.startAt in from..until }.map { it.startAt }.sorted()

    /** 이웃 수유 사이 간격(뒤 수유의 시작, 길이). 10시간 넘는 간격은 밤잠·빠진 기록으로 보고 뺀다. */
    fun feedGaps(starts: List<Long>): List<FeedGap> = starts.zipWithNext { a, b -> FeedGap(b, b - a) }.filter { it.ms <= MAX_INTERVAL_MS }

    /** 중앙값(짝수 개면 가운데 둘의 평균을 내림). 비었으면 null. */
    fun median(xs: List<Long>): Long? = xs.sorted().let { s ->
        when {
            s.isEmpty() -> null
            s.size % 2 == 1 -> s[s.size / 2]
            else -> (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }

    /** '평소' 간격: 간격이 [MIN_INTERVALS]개 이상일 때만 중앙값. */
    fun usualGap(gaps: List<Long>): Long? = if (gaps.size < MIN_INTERVALS) null else median(gaps)

    /** 오늘 앞 [days]일 동안 끝난(뒤 수유가 시작한) 수유 간격의 '평소': 오늘 간격 막대·마지막 수유 막대와 견주는 값. */
    fun usualFeedGapMs(events: List<CareEvent>, now: Long, zone: ZoneId, days: Int = PATTERN_DAYS - 1): Long? {
        val prior = lastDays(days + 1, now, zone).dropLast(1).toSet()
        return usualGap(feedGaps(feedStarts(events, until = now)).filter { Instant.ofEpochMilli(it.end).atZone(zone).toLocalDate() in prior }.map { it.ms })
    }

    /** 날마다 그 종류 기록이 그날 시작한 수(지운 것 뺌). */
    fun countPerDay(events: List<CareEvent>, kind: CareKind, days: List<LocalDate>, zone: ZoneId): List<Int> {
        val starts = events.filter { it.kind == kind && it.deletedAt == null }.map { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }
        return days.map { d -> starts.count { it == d } }
    }

    /** 기록한 날만 나눠 센 하루 평균: 0보다 큰 날의 합 ÷ 그 날 수. 그런 날이 없으면 null. */
    fun perRecordedDay(values: List<Int>): Double? = values.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()

    /** 같은 규칙의 길이 평균(잠, 밀리초 내림). 그런 날이 없으면 null. */
    @JvmName("perRecordedDayMs")
    fun perRecordedDay(values: List<Long>): Long? = values.filter { it > 0 }.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }

    /** 리포트의 울음 경보 에피소드와 그 시작 시각(소리 바꾸기 요청 에피소드는 빼고, 시각을 읽지 못하면 뺀다). */
    fun cryAlarms(reports: Collection<ReportSummaryBody>): List<CryAlarm> =
        reports.flatMap { it.timeline }.filter { it.trigger == EpisodeTrigger.CRY_ALARM }.mapNotNull { e -> instantOf(e.startedAt)?.let { CryAlarm(it, e) } }

    /** 방법별 성적에서 판정할 수 있는 최소 횟수(이보다 적으면 '아직 모름'). */
    @Suppress("ktlint:standard:property-naming")
    const val methodKnownAttempts = 3

    /**
     * 방법별 성적 순서: 판정할 수 있는 것 먼저, 그 안은 그친 비율이 높은 순, 같으면 받은 순. 받은 차례를 함께 준다.
     * 리포트 값은 차가 보낸 그대로라 음수·뒤집힌 값이 올 수 있어 0 ≤ 그침 ≤ 횟수로 묶어 센다.
     */
    fun methodOrder(stats: List<MethodStat>): List<IndexedValue<MethodStat>> {
        fun rate(s: MethodStat): Double {
            val n = maxOf(0, s.attempts)
            val ok = s.successes.coerceIn(0, n)
            return if (n > 0) ok.toDouble() / n else 0.0
        }
        return stats.withIndex().sortedWith(
            compareByDescending<IndexedValue<MethodStat>> { it.value.attempts >= methodKnownAttempts }.thenByDescending { rate(it.value) }.thenBy { it.index }
        )
    }
}
