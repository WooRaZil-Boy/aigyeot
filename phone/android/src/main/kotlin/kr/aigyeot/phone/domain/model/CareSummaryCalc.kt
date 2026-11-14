package kr.aigyeot.phone.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kr.aigyeot.shared.CareSummaryBody

// ---- 돌봄 요약: 숫자와 시각만. 메모 글·기저귀 종류·젖병 양·수유 쪽·별명은 넣지 않는다 ----

/**
 * 돌봄 요약 계산: 숫자와 시각만. 메모 글·기저귀 종류·젖병 양·수유 쪽·별명은 넣지 않는다. 저장소가 없는 순수 계산이다.
 * '오늘 0시'·'어제 22시'·'오늘 6시'는 `asOf`를 `zone` 벽시계로 본 날 기준이다. 시각은 그 순간의 `zone` 오프셋, 초까지.
 */
fun careSummary(care: CareFile, asOf: Long, zone: ZoneId): CareSummaryBody {
    val live = care.events.filter { it.deletedAt == null && it.startAt <= asOf }
    val today = Instant.ofEpochMilli(asOf).atZone(zone).toLocalDate()
    fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    val midnight = at(today, 0)
    val feeds = live.count { it.kind == CareKind.FEED && it.startAt >= midnight } // 끝 모름 수유도 센다
    if (!care.sleepDiaperEnabled) return CareSummaryBody(rfc3339(asOf, zone), zone.id, false, null, null, null, null, null, null, null, feeds)
    fun t(ms: Long?) = ms?.let { rfc3339(it, zone) }
    val sleeps = live.filter { it.kind == CareKind.SLEEP && !it.endUnknown } // 끝 모름 잠은 모든 잠 칸에서 뺀다
    val ended = sleeps.filter { it.endAt != null && it.endAt <= asOf }
    val last = ended.maxWithOrNull(compareBy({ it.endAt }, { it.startAt }))
    val sleepMs = sleeps.sumOf { (minOf(it.endAt ?: asOf, asOf) - maxOf(it.startAt, midnight)).coerceAtLeast(0) }
    val diapers = live.filter { it.kind == CareKind.DIAPER }
    return CareSummaryBody(
        asOf = rfc3339(asOf, zone),
        zone = zone.id,
        sleepDiaperEnabled = true,
        lastSleepStartAt = t(last?.startAt),
        lastSleepEndAt = t(last?.endAt),
        sleepingSince = t(sleeps.filter { it.endAt == null }.maxOfOrNull { it.startAt }),
        todaySleepMin = (sleepMs / MINUTE).toInt(), // 합을 낸 뒤 내림
        nightWakeups = ended.count { it.endAt!! >= at(today.minusDays(1), 22) && it.endAt < at(today, 6) },
        lastDiaperAt = t(diapers.maxOfOrNull { it.startAt }),
        todayDiaperCount = diapers.count { it.startAt >= midnight },
        todayFeedCount = feeds
    )
}

/**
 * 기록에서 온 값이 하나도 없는 요약(오늘 수유 0회, 잠·기저귀 칸이 비었거나 0). 메모만·옛 기록만 있는 폰의 요약도 그렇다.
 * 이 연결에서 요약을 넣은 적이 없으면 이런 요약은 보내지 않는다(같은 차의 다른 owner 폰 요약을 덮지 않게).
 */
val CareSummaryBody.empty: Boolean
    get() = todayFeedCount == 0 &&
        lastSleepStartAt == null &&
        lastSleepEndAt == null &&
        sleepingSince == null &&
        (todaySleepMin ?: 0) == 0 &&
        (nightWakeups ?: 0) == 0 &&
        lastDiaperAt == null &&
        (todayDiaperCount ?: 0) == 0

/** 수유를 차로 보내는 계기. 앱은 이 넷에서만 [kr.aigyeot.phone.domain.usecase.RecordCare.syncToCar]를 부른다. */
enum class FeedTrigger { APP_START, FOREGROUND, CARE_SAVED, PAIRED }
