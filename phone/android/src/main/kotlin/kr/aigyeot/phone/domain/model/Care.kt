package kr.aigyeot.phone.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.aigyeot.shared.ReportSummaryBody

// 돌봄 기록. 폰 안에만 두는 care.json 한 파일이다. 키·값 글자는 iOS와 같다.
// 규칙: 최대 길이를 넘은 진행 중 기록을 빠른 길로 닫으면 끝 모름, 차 기록 줄 글자,
// 겹침 정의, 경과·구간 형식, 잠 재시작, 옮기기의 보낸 표시, 메모 코드 포인트 세기.

/** care.json 전용: camelCase 키, 모든 키를 쓰고 null도 키를 쓴다. 메시지용 [kr.aigyeot.shared.ProtocolJson]과 다르다. */
val CareJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}

const val FEED_MAX_MS = 3 * HOUR
const val SLEEP_MAX_MS = 16 * HOUR
const val FUTURE_SLACK_MS = MINUTE

/** 고를 수 있는 가장 늦은 시각(iOS `CareRules.latestPick`): 지금 + 1분 여유([FUTURE_SLACK_MS]와 같은 값). */
fun latestPick(now: Long) = now + FUTURE_SLACK_MS

const val KEEP_DELETED_MS = 30 * DAY
const val SEND_WINDOW_MS = DAY
const val NOTE_MAX = 200
val AMOUNT_ML = 0..400
const val PATTERN_DAYS = 7
const val MIN_INTERVALS = 5
const val MAX_INTERVAL_MS = 10 * HOUR

enum class CareKind { FEED, SLEEP, DIAPER, NOTE }
enum class FeedSide { LEFT, RIGHT, BOTH, BOTTLE }
enum class Diaper { WET, DIRTY, BOTH, DRY }
enum class CareSource { PHONE, IMPORT }

/** 시작·끝이 있는 종류(수유·잠)의 최대 길이. 나머지는 한 시각만 있다. */
val CareKind.maxMs: Long?
    get() = when (this) {
        CareKind.FEED -> FEED_MAX_MS
        CareKind.SLEEP -> SLEEP_MAX_MS
        else -> null
    }

@Serializable
data class CareEvent(
    val id: String,
    val kind: CareKind,
    val startAt: Long,
    val endAt: Long? = null,
    val endUnknown: Boolean = false,
    val zone: String,
    val feedSide: FeedSide? = null,
    val amountMl: Int? = null,
    val diaper: Diaper? = null,
    val note: String? = null,
    val source: CareSource = CareSource.PHONE,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Serializable
data class CareFile(
    val version: Int = 1,
    val events: List<CareEvent> = emptyList(),
    val childNickname: String? = null,
    val sleepDiaperEnabled: Boolean = false,
    val migratedFeedings: Boolean = false
)

/** 수유·잠이고 끝이 없고 끝 모름도 아니며 지우지 않았다. */
val CareEvent.isOngoing get() = kind.maxMs != null && endAt == null && !endUnknown && deletedAt == null

fun CareFile.ongoing(kind: CareKind): CareEvent? = events.firstOrNull { it.kind == kind && it.isOngoing }

/** 경과는 저장하지 않고 지금 시각으로 계산한다. */
fun CareEvent.elapsed(now: Long) = (now - startAt).coerceAtLeast(0)

/** 진행 중 기록을 지금 닫는다. 최대 길이를 넘었으면 끝 모름으로 닫는다. */
private fun CareEvent.closed(now: Long): CareEvent = if (now - startAt > (kind.maxMs ?: Long.MAX_VALUE)) {
    copy(endUnknown = true, updatedAt = now)
} else {
    copy(endAt = maxOf(now, startAt), updatedAt = now)
}

/** `closedPrevious`는 이번 시작으로 닫힌 앞 기록(닫힌 뒤 모양). 없으면 null. */
data class Started(val file: CareFile, val closedPrevious: CareEvent?) {
    /** 새로 시작한 기록(파일 끝에 더한 그것). */
    val event: CareEvent get() = file.events.last()
}

/** 같은 종류의 진행 중 기록이 있으면 지금 시각으로 닫고 새로 시작한다. 수유 중에 잠을 시작할 수는 있다. */
fun CareFile.start(kind: CareKind, now: Long, zone: String, side: FeedSide? = null, id: String = ulid(now)): Started {
    val prev = ongoing(kind)?.closed(now)
    val e = CareEvent(id, kind, now, zone = zone, feedSide = side, createdAt = now, updatedAt = now)
    return Started(copy(events = events.map { if (it.id == prev?.id) prev else it } + e), prev)
}

/** 띠: "앞 수유를 끝냈어요", 끝 모름으로 닫았으면 " · 끝 시각 모름"을 붙인다. */
fun restartNotice(closed: CareEvent): String = (if (closed.kind == CareKind.SLEEP) "앞 잠을 끝냈어요" else "앞 수유를 끝냈어요") + if (closed.endUnknown) " · 끝 시각 모름" else ""

/** 타이머·잠금 화면의 '끝내기'. 최대 길이를 넘었으면 끝 모름으로 닫는다. 진행 중이 아니면 그대로. */
fun CareFile.endNow(id: String, now: Long) = copy(events = events.map { if (it.id == id && it.isOngoing) it.closed(now) else it })

/** 타이머 '끝 시각 모름으로 닫기'. */
fun CareFile.endUnknown(id: String, now: Long) =
    copy(events = events.map { if (it.id == id && it.isOngoing) it.copy(endUnknown = true, updatedAt = now) else it })

/** 진행 중인데 이미 최대 길이를 넘었다: 타이머가 길이 초과 안내와 끝 시각 고르기·끝 모름으로 닫기를 준다. */
fun CareEvent.overMax(now: Long) = isOngoing && now - startAt > (kind.maxMs ?: Long.MAX_VALUE)

/**
 * 저장 검사에 걸린 까닭. 값마다 탓이 된 자리가 하나다(iOS `CareProblem`과 같은 값): 미래 시각은 어느 시각이 미래인지로 나눈다
 * (둘 다 미래면 시작). 글은 [problemText], 자리는 [field].
 */
enum class CareProblem { FUTURE_START, FUTURE_END, END_BEFORE_START, TOO_LONG, AMOUNT, NOTE_TOO_LONG }

/** 저장하지 못한 까닭. 메모는 입력칸이 200자에서 멈춰 보통 보이지 않는다. */
fun problemText(p: CareProblem, kind: CareKind) = when (p) {
    CareProblem.FUTURE_START, CareProblem.FUTURE_END -> "앞으로의 시각은 적을 수 없어요"
    CareProblem.END_BEFORE_START -> "끝 시각이 시작보다 빨라요"
    CareProblem.TOO_LONG -> if (kind == CareKind.SLEEP) "잠은 16시간까지 적을 수 있어요" else "수유는 3시간까지 적을 수 있어요"
    CareProblem.AMOUNT -> "0~400ml로 적어 주세요"
    CareProblem.NOTE_TOO_LONG -> "메모는 200자까지 적을 수 있어요"
}

/** 겹침 경고. */
fun overlapText(kind: CareKind) = if (kind == CareKind.SLEEP) "다른 잠과 시간이 겹쳐요" else "다른 수유와 시간이 겹쳐요"

/** 미래 시각 금지와 값 범위. 진행 중 기록의 길이는 보지 않는다(타이머가 3시간 경고를 띄운다). */
fun CareEvent.problem(now: Long): CareProblem? = when {
    startAt > now + FUTURE_SLACK_MS -> CareProblem.FUTURE_START
    endAt != null && endAt > now + FUTURE_SLACK_MS -> CareProblem.FUTURE_END
    endAt != null && endAt < startAt -> CareProblem.END_BEFORE_START
    endAt != null && endAt - startAt > (kind.maxMs ?: Long.MAX_VALUE) -> CareProblem.TOO_LONG
    amountMl != null && amountMl !in AMOUNT_ML -> CareProblem.AMOUNT
    note != null && note.codePointCount(0, note.length) > NOTE_MAX -> CareProblem.NOTE_TOO_LONG
    else -> null
}

/** 새 기록을 넣거나 같은 id를 고친다. 규칙에 맞지 않거나 같은 종류의 진행 중이 둘이 되면 저장하지 않고 null. 겹침은 막지 않는다([overlaps]). */
fun CareFile.upsert(e: CareEvent, now: Long, editing: Boolean = false): CareFile? {
    if (e.problem(now) != null) return null
    if (e.isOngoing && events.any { it.id != e.id && it.kind == e.kind && it.isOngoing }) return null
    val old = events.find { it.id == e.id }
    // 고치기는 아직 있는 기록에만 얹는다: 시트를 연 사이 그 기록을 지웠으면(기록 지우기·밀어 지우기) 옛 사본으로 되살리지 않는다.
    if (editing && (old == null || old.deletedAt != null)) return null
    val next = e.copy(createdAt = old?.createdAt ?: e.createdAt, updatedAt = if (old == null) e.updatedAt else now)
    return copy(events = if (old == null) events + next else events.map { if (it.id == e.id) next else it })
}

/** 진행 중인 그 기록의 일부만 고친다(타이머의 쪽·양·시각). 그사이 끝났거나 지웠으면 null: 화면이 든 옛 사본이 끝 시각을 덮지 않게. */
fun CareFile.editOngoing(id: String, now: Long, f: (CareEvent) -> CareEvent): CareFile? {
    val cur = events.find { it.id == id }?.takeIf { it.isOngoing } ?: return null
    return upsert(f(cur), now)
}

/**
 * 수유·잠에만 본다. 진행 중은 [시작, 지금), 끝 모름은 시작 한 점. 구간은 a.start < b.end && b.start < a.end,
 * 점 p와 구간 [s, e)는 s ≤ p < e, 두 점은 같은 시각이면 겹친다. 길이 0인 끝난 기록도 구간 공식 그대로 본다(시작이 같으면 안 겹치고, 다른 구간 안이면 겹침. iOS와 같음).
 */
fun CareFile.overlaps(e: CareEvent, now: Long): Boolean {
    if (e.kind.maxMs == null) return false
    fun end(x: CareEvent): Long? = if (x.isOngoing) maxOf(now, x.startAt) else x.endAt // null = 끝 모름(한 점)
    val t = end(e)
    return events.any { o ->
        if (o.id == e.id || o.kind != e.kind || o.deletedAt != null) return@any false
        val ot = end(o)
        when {
            t == null && ot == null -> e.startAt == o.startAt
            t == null -> o.startAt <= e.startAt && e.startAt < ot!!
            ot == null -> e.startAt <= o.startAt && o.startAt < t
            else -> e.startAt < ot && o.startAt < t
        }
    }
}

/** 표시만 한다. 고친 시각(updatedAt)은 그대로 둔다(표준 예제와 같다). */
fun CareFile.delete(id: String, now: Long) = copy(events = events.map { if (it.id == id) it.copy(deletedAt = now) else it })

/** 되돌리기. 지운 사이 같은 종류를 새로 시작했으면 되살린 기록은 끝 모름으로 둔다(진행 중은 하나만). */
fun CareFile.undoDelete(id: String): CareFile {
    val e = events.find { it.id == id } ?: return this
    val restored = e.copy(deletedAt = null)
    val clash = restored.isOngoing && events.any { it.id != id && it.kind == e.kind && it.isOngoing }
    return copy(events = events.map { if (it.id == id) (if (clash) restored.copy(endUnknown = true) else restored) else it })
}

/** 띠의 '되돌리기'(5초)가 되돌릴 것: 방금 지운 손 기록 하나 또는 방금 가져온 기록들. */
sealed interface CareUndo {
    data class Delete(val id: String) : CareUndo

    data class Import(val ids: List<String>) : CareUndo
}

/** 띠 글: "지웠어요" / import.done "기록 {n}건을 가져왔어요". */
val CareUndo.text: String
    get() = when (this) {
        is CareUndo.Delete -> "지웠어요"
        is CareUndo.Import -> "기록 ${ids.size}건을 가져왔어요"
    }

/**
 * 차가 볼 기록: 가져오기 띠가 떠 있는 동안 그 줄은 아직 되돌릴 수 있으니 뺀다. 수유·요약을 계산하는 곳은 모두 이것을 지난다
 * (보내기 계기 [kr.aigyeot.phone.domain.usecase.ReconcileCareSummary]와 보내기 직전 재계산 [kr.aigyeot.phone.domain.usecase.SendOutbox]).
 * 그래서 띠 안에 되돌린 수유는 어느 길로도 차에 가지 않고, 띠가 끝나거나 다른 띠로 바뀌면 다음 계기부터 보인다.
 */
fun CareFile.forCar(undo: CareUndo?): CareFile = if (undo is CareUndo.Import) withoutIds(undo.ids) else this

/** 지운 지 30일이 지난 기록을 실제로 뺀다. 앱을 열 때 부른다. */
fun CareFile.purged(now: Long) = copy(events = events.filter { it.deletedAt == null || now - it.deletedAt < KEEP_DELETED_MS })

/** 지금 수유 기록(전송 상태 표시용)을 FEED로 한 번만 옮긴다. 원래 기록은 그대로 둔다. */
fun CareFile.migrated(feedings: List<FeedingRecord>, zone: String): CareFile {
    if (migratedFeedings) return this
    val have = events.map { it.id }.toMutableSet()
    val moved = feedings.mapNotNull { f ->
        val t = instantOf(f.at) ?: return@mapNotNull null
        if (!have.add(f.msgId)) return@mapNotNull null
        CareEvent(f.msgId, CareKind.FEED, t, endUnknown = true, zone = zone, createdAt = t, updatedAt = t)
    }
    return copy(events = events + moved, migratedFeedings = true)
}

/** 지우지 않은 수유 중 가장 늦은 시작과 차의 마지막 수유 시작 중 늦은 것. */
fun lastFeedingAt(care: CareFile, state: PhoneState): Long? = listOfNotNull(care.lastFeedStart(), instantOf(state.feeding?.value?.lastFeedingStart)).maxOrNull()

fun CareFile.lastFeedStart(): Long? = events.lastFeedStart()

fun List<CareEvent>.lastFeedStart(): Long? = filter { it.kind == CareKind.FEED && it.deletedAt == null }.maxOfOrNull { it.startAt }

/** 기록한 때의 시간대. 읽을 수 없으면 기기 시간대. */
fun CareEvent.zoneId(fallback: ZoneId): ZoneId = runCatching { ZoneId.of(zone) }.getOrDefault(fallback)

/** 기록이 놓이는 날은 그 기록 시간대에서 시작한 날이다. */
fun CareEvent.day(fallback: ZoneId): LocalDate = Instant.ofEpochMilli(startAt).atZone(zoneId(fallback)).toLocalDate()

/** 하루 목록(지운 것 제외, 시각 순). */
fun CareFile.dayEvents(date: LocalDate, zone: ZoneId) = events.filter { it.deletedAt == null && it.day(zone) == date }.sortedBy { it.startAt }

/** 그 날(기기 시간대) 경계로 잘라 센 길이의 합. 끝난 기록만 센다(끝 모름·진행 중은 길이를 세지 않는다). */
fun durationOn(events: List<CareEvent>, kind: CareKind, date: LocalDate, zone: ZoneId): Long {
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return events.filter { it.kind == kind && it.deletedAt == null && it.endAt != null }.sumOf { e ->
        (minOf(e.endAt!!, to) - maxOf(e.startAt, from)).coerceAtLeast(0)
    }
}

/** 빠른 버튼(순서: 수유, 메모, 켜면 잠·기저귀). */
fun quickKinds(sleepDiaper: Boolean) = listOf(CareKind.FEED, CareKind.NOTE) + if (sleepDiaper) listOf(CareKind.SLEEP, CareKind.DIAPER) else emptyList()

/** 패턴. 날짜는 오래된 것부터 오늘까지 7개. 잠·기저귀를 끄면 그 칸은 null. */
data class CarePattern(
    val days: List<LocalDate>,
    val feedsPerDay: List<Int>,
    /** 두 수유가 모두 7일 안에 있는 간격(10시간 넘는 것은 뺌). */
    val feedGapsMs: List<Long>,
    val sleepPerDayMs: List<Long>?,
    val diapersPerDay: List<Int>?,
    val carCryEpisodes: Int
) {
    /** 보통 간격(중앙값). 간격이 5개 미만이면 null(간격에서 계산). */
    val feedIntervalMs: Long? get() = CareStats.usualGap(feedGapsMs)

    /** 쌓인 간격 수(패턴 빈 상태의 점, 5개가 되면 보통 간격이 보인다). */
    val feedIntervalCount: Int get() = feedGapsMs.size

    /** 점 다섯의 읽기 이름(`pattern.intervalProgress`). 보통 간격이 보이면 null(점을 그리지 않는다). */
    val intervalProgress: String? get() = if (feedIntervalMs == null) "수유 간격 5개 중 ${feedIntervalCount}개 쌓임" else null

    /** 평균의 분모(날별 값에서 계산). 수유·기저귀는 그 종류 기록이 그날 시작한 날, 잠은 끝난 잠이 그날에 걸친 시간이 0보다 큰 날. */
    val feedDays: Int get() = feedsPerDay.count { it > 0 }
    val sleepDays: Int get() = sleepPerDayMs?.count { it > 0 } ?: 0
    val diaperDays: Int get() = diapersPerDay?.count { it > 0 } ?: 0
}

/** pattern.value: 횟수 "평균 {n}번"(소수 첫째 자리), 기록한 날이 없으면 "기록 없음". */
fun avgCountText(perDay: List<Int>): String = CareStats.perRecordedDay(perDay)?.let { "평균 ${String.format(java.util.Locale.ROOT, "%.1f", it)}번" } ?: "기록 없음"

/** 잠 "평균 {기간}"(길이 형식). */
fun avgDurationText(perDayMs: List<Long>): String = CareStats.perRecordedDay(perDayMs)?.let { "평균 ${durationText(it)}" } ?: "기록 없음"

/** pattern.days: 값 아래 "{n}일 기준". 기록한 날이 없으면(값이 "기록 없음") 보이지 않는다. */
fun daysText(days: Int): String? = if (days == 0) null else "${days}일 기준"

/** 지난 7일(오늘 포함, 기기 시간대). 간격은 두 수유가 모두 7일 안에 있는 것의 중앙값. */
fun carePattern(care: CareFile, reports: Collection<ReportSummaryBody>, now: Long, zone: ZoneId): CarePattern {
    val days = CareStats.lastDays(PATTERN_DAYS, now, zone)
    val from = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
    fun dayOf(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    // 잠은 끝난 잠이 그날(자정에서 나눔)에 걸친 시간.
    val sleepMs = days.map { durationOn(care.events, CareKind.SLEEP, it, zone) }
    return CarePattern(
        days = days,
        feedsPerDay = CareStats.countPerDay(care.events, CareKind.FEED, days, zone),
        feedGapsMs = CareStats.feedGaps(CareStats.feedStarts(care.events, from = from, until = now)).map { it.ms },
        sleepPerDayMs = if (care.sleepDiaperEnabled) sleepMs else null,
        diapersPerDay = if (care.sleepDiaperEnabled) CareStats.countPerDay(care.events, CareKind.DIAPER, days, zone) else null,
        // 차 시계가 앞서 내일로 찍힌 것은 뺀다.
        carCryEpisodes = CareStats.cryAlarms(reports).count { dayOf(it.at) in days }
    )
}

// ---- 시각 고르기(시·분만 고르는 위젯을 순간으로) ----

/** 그 날(그 시간대)의 h:m. */
fun onDate(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

/** `limit` 이전에서 가장 늦은 h:m(오늘 그 시각이 아직 안 왔으면 어제). 빠른 기록 '직접'·타이머 시작 시각 수정. */
fun latestAtOrBefore(hour: Int, minute: Int, limit: Long, zone: ZoneId): Long {
    val day = Instant.ofEpochMilli(limit).atZone(zone).toLocalDate()
    val t = onDate(day, hour, minute, zone)
    return if (t <= limit) t else onDate(day.minusDays(1), hour, minute, zone)
}

/**
 * 진행 중 기록의 시작 시각 수정(타이머): `ref`(지금 시작)에 가장 가까운 h:m 중 `limit` 이전. 그대로 확인하면 그대로다.
 * 오늘 기준으로 찾으면 하루 넘게 진행된 기록의 날짜가 바뀌어 차로 새 시각이 간다.
 */
fun nearestAtOrBefore(hour: Int, minute: Int, ref: Long, limit: Long, zone: ZoneId): Long {
    val r = Instant.ofEpochMilli(ref).atZone(zone)
    if (r.hour == hour && r.minute == minute && ref <= limit) return ref
    return (-1L..1L).map { onDate(r.toLocalDate().plusDays(it), hour, minute, zone) }.filter { it <= limit }
        .minByOrNull { kotlin.math.abs(it - ref) } ?: latestAtOrBefore(hour, minute, limit, zone)
}

/**
 * `from` 이후에서 가장 이른 h:m. 타이머 '끝 시각 고르기'(시작보다 늦은 쪽).
 * 선택기는 분까지라 `from`과 같은 분을 고르면 다음 날로 넘기지 않고 `from`을 돌려준다(초가 있는 시작과 같은 분에 끝난 기록).
 */
fun firstAtOrAfter(hour: Int, minute: Int, from: Long, zone: ZoneId): Long {
    val day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
    val t = onDate(day, hour, minute, zone)
    return maxOf(from, if (t >= from / MINUTE * MINUTE) t else onDate(day.plusDays(1), hour, minute, zone))
}
