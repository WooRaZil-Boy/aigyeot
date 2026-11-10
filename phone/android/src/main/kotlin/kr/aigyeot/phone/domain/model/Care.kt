package kr.aigyeot.phone.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.aigyeot.shared.EpisodeResult
import kr.aigyeot.shared.EpisodeTrigger
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.SoundMethod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// 돌봄 기록(phone-care-plan 6장). 폰 안에만 두는 care.json 한 파일이다. 키·값 글자는 iOS와 같다(13장).
// 계획 0장 U14~U24(11-05 통일 결정)를 따른다: 최대 길이를 넘은 진행 중 기록을 빠른 길로 닫으면 끝 모름(U14), 차 기록 줄 글자(U15·U19),
// 겹침 정의(U16), 경과·구간 형식(U20), 잠 재시작(U21), 옮기기의 보낸 표시(U22), 메모 코드 포인트 세기(U24).

/** care.json 전용: camelCase 키, 모든 키를 쓰고 null도 키를 쓴다(13장). 메시지용 [kr.aigyeot.shared.ProtocolJson]과 다르다. */
val CareJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}

const val FEED_MAX_MS = 3 * HOUR // 설계값(6장 R3)
const val SLEEP_MAX_MS = 16 * HOUR // 설계값(6장 R3)
const val FUTURE_SLACK_MS = MINUTE
const val KEEP_DELETED_MS = 30 * DAY
const val SEND_WINDOW_MS = DAY // R5 (b)
const val NOTE_MAX = 200
val AMOUNT_ML = 0..400
const val PATTERN_DAYS = 7
const val MIN_INTERVALS = 5
const val MAX_INTERVAL_MS = 10 * HOUR // 설계값(6장 R14)

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
    val deletedAt: Long? = null,
)

@Serializable
data class CareFile(
    val version: Int = 1,
    val events: List<CareEvent> = emptyList(),
    val childNickname: String? = null,
    val sleepDiaperEnabled: Boolean = false,
    val migratedFeedings: Boolean = false,
)

/** R1·R15: 수유·잠이고 끝이 없고 끝 모름도 아니며 지우지 않았다. */
val CareEvent.isOngoing get() = kind.maxMs != null && endAt == null && !endUnknown && deletedAt == null

fun CareFile.ongoing(kind: CareKind): CareEvent? = events.firstOrNull { it.kind == kind && it.isOngoing }

/** R15: 경과는 저장하지 않고 지금 시각으로 계산한다. */
fun CareEvent.elapsed(now: Long) = (now - startAt).coerceAtLeast(0)

/** 진행 중 기록을 지금 닫는다. 최대 길이를 넘었으면 끝 모름으로(R2·R3, 11-05 결정). */
private fun CareEvent.closed(now: Long): CareEvent =
    if (now - startAt > (kind.maxMs ?: Long.MAX_VALUE)) copy(endUnknown = true, updatedAt = now)
    else copy(endAt = maxOf(now, startAt), updatedAt = now)

/** `closedPrevious`는 이번 시작으로 닫힌 앞 기록(닫힌 뒤 모양). 없으면 null. */
data class Started(val file: CareFile, val closedPrevious: CareEvent?) {
    /** 새로 시작한 기록(P4: 파일 끝에 더한 그것). */
    val event: CareEvent get() = file.events.last()
}

/** R1·R2·U21: 같은 종류의 진행 중 기록이 있으면 지금 시각으로 닫고 새로 시작한다. 수유 중에 잠을 시작할 수는 있다. */
fun CareFile.start(kind: CareKind, now: Long, zone: String, side: FeedSide? = null, id: String = ulid(now)): Started {
    val prev = ongoing(kind)?.closed(now)
    val e = CareEvent(id, kind, now, zone = zone, feedSide = side, createdAt = now, updatedAt = now)
    return Started(copy(events = events.map { if (it.id == prev?.id) prev else it } + e), prev)
}

/** R2·U21 띠: "앞 수유를 끝냈어요", 끝 모름으로 닫았으면 " · 끝 시각 모름"을 붙인다. */
fun restartNotice(closed: CareEvent): String =
    (if (closed.kind == CareKind.SLEEP) "앞 잠을 끝냈어요" else "앞 수유를 끝냈어요") + if (closed.endUnknown) " · 끝 시각 모름" else ""

/** 타이머·잠금 화면의 '끝내기'. 최대 길이를 넘었으면 끝 모름으로 닫는다(U14). 진행 중이 아니면 그대로. */
fun CareFile.endNow(id: String, now: Long) = copy(events = events.map { if (it.id == id && it.isOngoing) it.closed(now) else it })

/** 타이머 '끝 시각 모름으로 닫기'(U14). */
fun CareFile.endUnknown(id: String, now: Long) =
    copy(events = events.map { if (it.id == id && it.isOngoing) it.copy(endUnknown = true, updatedAt = now) else it })

/** 진행 중인데 이미 최대 길이를 넘었다: 타이머가 R3 안내와 끝 시각 고르기·끝 모름으로 닫기를 준다(U14). */
fun CareEvent.overMax(now: Long) = isOngoing && now - startAt > (kind.maxMs ?: Long.MAX_VALUE)

enum class CareProblem { FUTURE, END_BEFORE_START, TOO_LONG, AMOUNT, NOTE_TOO_LONG }

/** 저장하지 못한 까닭(7장 + 11-05 결정 문구). 메모는 입력칸이 200자에서 멈춰 보통 보이지 않는다. */
fun problemText(p: CareProblem, kind: CareKind) = when (p) {
    CareProblem.FUTURE -> "앞으로의 시각은 적을 수 없어요"
    CareProblem.END_BEFORE_START -> "끝 시각이 시작보다 빨라요"
    CareProblem.TOO_LONG -> if (kind == CareKind.SLEEP) "잠은 16시간까지 적을 수 있어요" else "수유는 3시간까지 적을 수 있어요"
    CareProblem.AMOUNT -> "0~400ml로 적어 주세요"
    CareProblem.NOTE_TOO_LONG -> "메모는 200자까지 적을 수 있어요"
}

/** R4 경고(7장 + 11-05 결정). */
fun overlapText(kind: CareKind) = if (kind == CareKind.SLEEP) "다른 잠과 시간이 겹쳐요" else "다른 수유와 시간이 겹쳐요"

/** R3과 값 범위. 진행 중 기록의 길이는 보지 않는다(타이머가 3시간 경고를 띄운다). */
fun CareEvent.problem(now: Long): CareProblem? = when {
    startAt > now + FUTURE_SLACK_MS || (endAt != null && endAt > now + FUTURE_SLACK_MS) -> CareProblem.FUTURE
    endAt != null && endAt < startAt -> CareProblem.END_BEFORE_START
    endAt != null && endAt - startAt > (kind.maxMs ?: Long.MAX_VALUE) -> CareProblem.TOO_LONG
    amountMl != null && amountMl !in AMOUNT_ML -> CareProblem.AMOUNT
    note != null && note.codePointCount(0, note.length) > NOTE_MAX -> CareProblem.NOTE_TOO_LONG
    else -> null
}

/** 새 기록을 넣거나 같은 id를 고친다. 규칙에 맞지 않거나 같은 종류의 진행 중이 둘이 되면 저장하지 않고 null(R3·R1). 겹침은 막지 않는다(R4, [overlaps]). */
fun CareFile.upsert(e: CareEvent, now: Long, editing: Boolean = false): CareFile? {
    if (e.problem(now) != null) return null
    if (e.isOngoing && events.any { it.id != e.id && it.kind == e.kind && it.isOngoing }) return null
    val old = events.find { it.id == e.id }
    // 고치기는 아직 있는 기록에만 얹는다: 시트를 연 사이 그 기록을 지웠으면(기록 지우기·밀어 지우기) 옛 사본으로 되살리지 않는다(R10).
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
 * R4·U16: 수유·잠에만 본다. 진행 중은 [시작, 지금), 끝 모름은 시작 한 점. 구간은 a.start < b.end && b.start < a.end,
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

/** R8: 표시만 한다. 고친 시각(updatedAt)은 그대로 둔다(표준 예제와 같다). */
fun CareFile.delete(id: String, now: Long) = copy(events = events.map { if (it.id == id) it.copy(deletedAt = now) else it })

/** 되돌리기. 지운 사이 같은 종류를 새로 시작했으면 되살린 기록은 끝 모름으로 둔다(R1: 진행 중은 하나만). */
fun CareFile.undoDelete(id: String): CareFile {
    val e = events.find { it.id == id } ?: return this
    val restored = e.copy(deletedAt = null)
    val clash = restored.isOngoing && events.any { it.id != id && it.kind == e.kind && it.isOngoing }
    return copy(events = events.map { if (it.id == id) (if (clash) restored.copy(endUnknown = true) else restored) else it })
}

/** 띠의 '되돌리기'(5초)가 되돌릴 것: 방금 지운 손 기록 하나(7장·U38) 또는 방금 가져온 기록들(U64). */
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
 * 차가 볼 기록(U67 11-09 보충): 가져오기 띠가 떠 있는 동안 그 줄은 아직 되돌릴 수 있으니 뺀다. 수유·요약(R5·R19)을 계산하는 곳은 모두 이것을 지난다
 * (보내기 계기 [kr.aigyeot.phone.domain.usecase.ReconcileCareSummary]와 보내기 직전 재계산 [kr.aigyeot.phone.domain.usecase.SendOutbox]).
 * 그래서 띠 안에 되돌린 수유는 어느 길로도 차에 가지 않고, 띠가 끝나거나 다른 띠로 바뀌면 다음 계기부터 보인다.
 */
fun CareFile.forCar(undo: CareUndo?): CareFile = if (undo is CareUndo.Import) withoutIds(undo.ids) else this

/** R8: 지운 지 30일이 지난 기록을 실제로 뺀다. 앱을 열 때 부른다. */
fun CareFile.purged(now: Long) = copy(events = events.filter { it.deletedAt == null || now - it.deletedAt < KEEP_DELETED_MS })

/** R11: 지금 수유 기록(전송 상태 표시용)을 FEED로 한 번만 옮긴다. 원래 기록은 그대로 둔다. */
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

/** R12: 지우지 않은 수유 중 가장 늦은 시작과 차의 마지막 수유 시작 중 늦은 것. */
fun lastFeedingAt(care: CareFile, state: PhoneState): Long? =
    listOfNotNull(care.lastFeedStart(), instantOf(state.feeding?.value?.lastFeedingStart)).maxOrNull()

fun CareFile.lastFeedStart(): Long? = events.lastFeedStart()

fun List<CareEvent>.lastFeedStart(): Long? = filter { it.kind == CareKind.FEED && it.deletedAt == null }.maxOfOrNull { it.startAt }

/** 기록한 때의 시간대. 읽을 수 없으면 기기 시간대. */
fun CareEvent.zoneId(fallback: ZoneId): ZoneId = runCatching { ZoneId.of(zone) }.getOrDefault(fallback)

/** R13: 기록이 놓이는 날은 그 기록 시간대에서 시작한 날이다. */
fun CareEvent.day(fallback: ZoneId): LocalDate = Instant.ofEpochMilli(startAt).atZone(zoneId(fallback)).toLocalDate()

/** 하루 목록(지운 것 제외, 시각 순). */
fun CareFile.dayEvents(date: LocalDate, zone: ZoneId) = events.filter { it.deletedAt == null && it.day(zone) == date }.sortedBy { it.startAt }

/** R13·R14: 그 날(기기 시간대) 경계로 잘라 센 길이의 합. 끝난 기록만 센다(끝 모름·진행 중은 길이를 세지 않는다). */
fun durationOn(events: List<CareEvent>, kind: CareKind, date: LocalDate, zone: ZoneId): Long {
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return events.filter { it.kind == kind && it.deletedAt == null && it.endAt != null }.sumOf { e ->
        (minOf(e.endAt!!, to) - maxOf(e.startAt, from)).coerceAtLeast(0)
    }
}

/** R16: 빠른 버튼(7장 순서: 수유, 메모, 켜면 잠·기저귀). */
fun quickKinds(sleepDiaper: Boolean) =
    listOf(CareKind.FEED, CareKind.NOTE) + if (sleepDiaper) listOf(CareKind.SLEEP, CareKind.DIAPER) else emptyList()

/** C5 패턴. 날짜는 오래된 것부터 오늘까지 7개. 잠·기저귀를 끄면 그 칸은 null(R16). */
data class CarePattern(
    val days: List<LocalDate>,
    val feedsPerDay: List<Int>,
    /** 5개 미만이면 null(R14). */
    val feedIntervalMs: Long?,
    val sleepPerDayMs: List<Long>?,
    val diapersPerDay: List<Int>?,
    val carCryEpisodes: Int,
) {
    /** U27·U31: 평균의 분모(P4: 날별 값에서 계산). 수유·기저귀는 그 종류 기록이 그날 시작한 날, 잠은 끝난 잠이 그날에 걸친 시간이 0보다 큰 날. */
    val feedDays: Int get() = feedsPerDay.count { it > 0 }
    val sleepDays: Int get() = sleepPerDayMs?.count { it > 0 } ?: 0
    val diaperDays: Int get() = diapersPerDay?.count { it > 0 } ?: 0
}

/** 7.1 pattern.value: 횟수 "평균 {n}회"(소수 첫째 자리), 기록한 날이 없으면 "기록 없음"(U27). */
fun avgCountText(perDay: List<Int>, days: Int): String =
    if (days == 0) "기록 없음" else "평균 ${String.format(java.util.Locale.ROOT, "%.1f", perDay.sum().toDouble() / days)}회"

/** 잠 "평균 {기간}"(U27 길이 형식). */
fun avgDurationText(perDayMs: List<Long>, days: Int): String = if (days == 0) "기록 없음" else "평균 ${durationText(perDayMs.sum() / days)}"

/** 7.1 pattern.days: 값 아래 "{n}일 기준". 기록한 날이 없으면(값이 "기록 없음") 보이지 않는다. */
fun daysText(days: Int): String? = if (days == 0) null else "${days}일 기준"

/** R14: 지난 7일(오늘 포함, 기기 시간대). 간격 = 이어진 수유 시작 사이의 중앙값(짝수면 가운데 둘의 평균, 정수 나눗셈), 10시간 넘는 간격은 뺀다. */
fun carePattern(care: CareFile, reports: Collection<ReportSummaryBody>, now: Long, zone: ZoneId): CarePattern {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = (PATTERN_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val from = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
    fun dayOf(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    val live = care.events.filter { it.deletedAt == null && it.startAt >= from }
    fun countPerDay(kind: CareKind) = days.map { d -> live.count { it.kind == kind && dayOf(it.startAt) == d } }
    val intervals = live.filter { it.kind == CareKind.FEED && it.startAt <= now }.map { it.startAt }.sorted() // R3의 1분 여유로 내일에 걸친 수유는 뺀다
        .zipWithNext { a, b -> b - a }.filter { it <= MAX_INTERVAL_MS }.sorted()
    val median = when {
        intervals.size < MIN_INTERVALS -> null
        intervals.size % 2 == 1 -> intervals[intervals.size / 2]
        else -> (intervals[intervals.size / 2 - 1] + intervals[intervals.size / 2]) / 2
    }
    val feeds = countPerDay(CareKind.FEED)
    val diapers = countPerDay(CareKind.DIAPER)
    // U31: 잠은 끝난 잠이 그날(자정에서 나눔)에 걸친 시간이 0보다 큰 날을 센다.
    val sleepMs = days.map { durationOn(care.events, CareKind.SLEEP, it, zone) }
    return CarePattern(
        days = days,
        feedsPerDay = feeds,
        feedIntervalMs = median,
        sleepPerDayMs = if (care.sleepDiaperEnabled) sleepMs else null,
        diapersPerDay = if (care.sleepDiaperEnabled) diapers else null,
        carCryEpisodes = carLines(reports).count { !it.isDrive && dayOf(it.startAt) in days }, // 차 시계가 앞서 내일로 찍힌 것은 뺀다
    )
}

// ---- 차 기록 줄(C14, R17): 저장하지 않고 받은 리포트에서 그릴 때 만든다 ----

/** 주행이면 `episodeId`가 null이다. 리포트에 없는 값은 null. */
data class CarLine(
    val sessionId: String,
    val episodeId: String?,
    val startAt: Long,
    val endAt: Long?,
    val sound: SoundMethod?,
    val result: EpisodeResult?,
) {
    val isDrive get() = episodeId == null
}

fun carLines(reports: Collection<ReportSummaryBody>): List<CarLine> = reports.flatMap { r ->
    val start = instantOf(r.startedAt) ?: return@flatMap emptyList()
    listOf(CarLine(r.sessionId, null, start, instantOf(r.endedAt), null, null)) +
        // 울음 경보로 시작한 에피소드만. 소리 바꾸기 요청(switch_sound)은 울음이 없었을 수 있다(11-05 결정).
        r.timeline.filter { it.trigger == EpisodeTrigger.CRY_ALARM }
            .mapNotNull { e -> instantOf(e.startedAt)?.let { CarLine(r.sessionId, e.episodeId, it, null, e.sound?.method, e.result) } }
}.sortedBy { it.startAt }

/** "탑승 · 오후 1:10–1:52"(세션 구간: 중간 정차가 들어 있어 '주행'이라 하지 않는다), "차에서 울음 · {시각} · {소리} · {결과}"(7장·U37). 없는 값은 칸째 뺀다(U15). */
fun CarLine.text(zone: ZoneId): String =
    if (isDrive) "탑승 · " + (endAt?.let { koSpan(startAt, it, zone) } ?: koClock(startAt, zone))
    else listOfNotNull("차에서 울음", koClock(startAt, zone), sound?.let(::methodName), result?.let(::resultText)).joinToString(" · ")

// ---- 리포트 글자(U47): 시각·구간은 U20, 두 앱 같은 글자 ----

/** 리포트·재생의 구간(U20). 끝을 읽지 못하면 시작 시각만, 시작도 못 읽으면 "—". */
fun reportSpan(start: String?, end: String?, zone: ZoneId): String {
    val s = instantOf(start) ?: return "—"
    return instantOf(end)?.let { koSpan(s, it, zone) } ?: koClock(s, zone)
}

/** report.detail.title "11월 6일(금)": 주행을 시작한 날. 읽지 못하면 null. */
fun reportDate(b: ReportSummaryBody, zone: ZoneId): String? =
    instantOf(b.startedAt)?.let { koDate(Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) }

/** report.row.title "11월 6일(금) 오전 2:54–3:44". */
fun reportRowTitle(b: ReportSummaryBody, zone: ZoneId): String =
    listOfNotNull(reportDate(b, zone), reportSpan(b.startedAt, b.endedAt, zone)).joinToString(" ")

/** report.row.sub "울음 {n}번". n은 울음 경보로 시작한 에피소드 수로, 차 기록 줄(R17)의 '차에서 울음' 줄 수와 같다. */
fun reportRowSub(b: ReportSummaryBody): String {
    val n = b.timeline.count { it.trigger == EpisodeTrigger.CRY_ALARM }
    return (if (n == 0) "울음 기록 없음" else "울음 ${n}번") + if (b.simulated) " · 시뮬레이션" else ""
}

fun methodName(m: SoundMethod) = when (m) {
    SoundMethod.WHITE_NOISE -> "백색소음"
    SoundMethod.WOMB -> "뱃속 소리"
    SoundMethod.LULLABY -> "자장가"
    SoundMethod.PARENT_SONG -> "부모 노래"
    SoundMethod.UNKNOWN -> "기타"
}

/** 차가 보낸 label을 보이되, 모르는 method(U46)는 label 대신 "기타". */
fun soundLabel(method: SoundMethod, label: String) = if (method == SoundMethod.UNKNOWN) methodName(method) else label

fun resultText(r: EpisodeResult) = when (r) {
    EpisodeResult.SUCCESS -> "그쳤어요"
    EpisodeResult.FAILURE -> "계속 울었어요"
    EpisodeResult.UNDETERMINED -> "알 수 없음"
}

// ---- 문구 형식(7장, 기기 언어와 관계없이 한국어) ----

private fun half(hour: Int) = if (hour < 12) "오전" else "오후"

/** "오후 12:52". */
fun koClock(ms: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    return "${half(t.hour)} ${hm12(t.hour, t.minute)}"
}

private fun hm12(hour: Int, minute: Int) = "${if (hour % 12 == 0) 12 else hour % 12}:${minute.toString().padStart(2, '0')}"

/** 구간 끝 시각(U20): 시작과 같은 오전·오후면 "1:52", 다르면 "오후 12:20". 날짜는 보지 않는다(U20 원문, iOS와 같음). */
fun koClockAfter(start: Long, end: Long, zone: ZoneId): String {
    val s = Instant.ofEpochMilli(start).atZone(zone)
    val e = Instant.ofEpochMilli(end).atZone(zone)
    return if (half(s.hour) == half(e.hour)) hm12(e.hour, e.minute) else koClock(end, zone)
}

/** 구간(U20) "오후 1:10–1:52". 시작과 끝이 같은 분(epoch 분)이면 시작 시각 하나만(U41). */
fun koSpan(start: Long, end: Long, zone: ZoneId): String =
    if (start / MINUTE == end / MINUTE) koClock(start, zone) else "${koClock(start, zone)}–${koClockAfter(start, end, zone)}"

/** "11월 5일(수)". */
fun koDate(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일(${"월화수목금토일"[d.dayOfWeek.value - 1]})"

/** 며칠 전일 수 있는 시각(import.latestFeed, U68 11-09 보충): 오늘이면 "오전 11:00", 어제면 "어제 오전 11:00", 그 전이면 "11월 3일 오전 11:00". */
fun koDayClock(ms: Long, now: Long, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (d) {
        today -> ""
        today.minusDays(1) -> "어제 "
        else -> "${d.monthValue}월 ${d.dayOfMonth}일 "
    }
    return day + koClock(ms, zone)
}

/** U20: 1분 미만 "방금", 1~59분 "12분", 60분 이상 "2시간 10분", 분이 0이면 "2시간". */
fun elapsedText(ms: Long): String {
    val m = ms.coerceAtLeast(0) / MINUTE
    return when {
        m < 1 -> "방금"
        m < 60 -> "${m}분"
        m % 60 == 0L -> "${m / 60}시간"
        else -> "${m / 60}시간 ${m % 60}분"
    }
}

/** U27: 길이·평균. 0이면 "0분", 1분 미만이면 "1분 미만", 그 위는 U20("2시간", "2시간 10분"). "방금"은 쓰지 않는다. */
fun durationText(ms: Long): String = when {
    ms <= 0 -> "0분"
    ms < MINUTE -> "1분 미만"
    else -> elapsedText(ms)
}

/** "2시간 10분 전". 1분 미만은 "방금"(뒤에 '전'을 붙이지 않는다). */
fun agoText(at: Long, now: Long) = elapsedText(now - at).let { if (it == "방금") it else "$it 전" }

/** 타이머·잠금 화면: "12:34", 1시간이 넘으면 "1:02:34". */
fun timerText(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

fun sideName(s: FeedSide) = when (s) {
    FeedSide.LEFT -> "왼쪽"
    FeedSide.RIGHT -> "오른쪽"
    FeedSide.BOTH -> "양쪽"
    FeedSide.BOTTLE -> "젖병"
}

// ---- 돌봄 요약(U45, R18): 숫자와 시각만. 메모 글·기저귀 종류·젖병 양·수유 쪽·별명은 넣지 않는다 ----

// shortcut: shared의 CareSummaryBody가 main에 들어오기 전이라 같은 칸·같은 이름으로 둔다. rebase 뒤 이것을 지우고 kr.aigyeot.shared.CareSummaryBody를 쓴다.
@Serializable
data class CareSummaryBody(
    val asOf: String,
    val zone: String,
    val sleepDiaperEnabled: Boolean,
    val lastSleepStartAt: String?,
    val lastSleepEndAt: String?,
    val sleepingSince: String?,
    val todaySleepMin: Int?,
    val nightWakeups: Int?,
    val lastDiaperAt: String?,
    val todayDiaperCount: Int?,
    val todayFeedCount: Int,
)

/**
 * U61: 기록에서 온 값이 하나도 없는 요약(오늘 수유 0회, 잠·기저귀 칸이 비었거나 0). 메모만·옛 기록만 있는 폰의 요약도 그렇다.
 * 이 연결에서 요약을 넣은 적이 없으면 이런 요약은 보내지 않는다(같은 차의 다른 owner 폰 요약을 덮지 않게).
 */
val CareSummaryBody.empty: Boolean
    get() = todayFeedCount == 0 && lastSleepStartAt == null && lastSleepEndAt == null && sleepingSince == null && (todaySleepMin ?: 0) == 0 &&
        (nightWakeups ?: 0) == 0 && lastDiaperAt == null && (todayDiaperCount ?: 0) == 0

/** 수유를 차로 보내는 계기(R6). 앱은 이 넷에서만 [kr.aigyeot.phone.domain.usecase.RecordCare.syncToCar]를 부른다. */
enum class FeedTrigger { APP_START, FOREGROUND, CARE_SAVED, PAIRED }

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

// ---- 하루 목록 줄(7.1 log.row.*) ----

/** 쪽: 젖병이면 "젖병 {n}ml"(양이 없으면 "젖병"). */
private fun CareEvent.sideText(): String? = feedSide?.let { if (it == FeedSide.BOTTLE && amountMl != null) "젖병 ${amountMl}ml" else sideName(it) }

/** 손 기록 한 줄. 시각은 그 기록의 시간대로 보인다(R13). */
fun logRowText(e: CareEvent, zone: ZoneId): String {
    val t = koClock(e.startAt, zone)
    val span = e.endAt?.let { koSpan(e.startAt, it, zone) }
    return when (e.kind) {
        CareKind.FEED -> when {
            e.isOngoing -> "수유 중 · ${t}부터"
            span == null -> listOfNotNull("수유", t, e.sideText(), "끝 시각 모름").joinToString(" · ") // log.row.feedUnknown(U37)
            else -> listOfNotNull("수유", span, e.sideText()).joinToString(" · ")
        }
        CareKind.SLEEP -> when {
            e.isOngoing -> "자는 중 · ${t}부터"
            span == null -> "잠 · $t · 끝 시각 모름"
            else -> "잠 · $span · ${durationText(e.endAt - e.startAt)}"
        }
        CareKind.DIAPER -> listOfNotNull("기저귀", t, e.diaper?.let(::diaperName)).joinToString(" · ")
        CareKind.NOTE -> listOfNotNull("메모", t, e.note).joinToString(" · ")
    }
}

/** log.row.memoLine(U37): 수유·잠·기저귀에 단 메모는 줄 아래 둘째 줄에 글 그대로. 메모 기록은 첫 줄에 이미 있다. */
fun memoLine(e: CareEvent): String? = e.note?.takeIf { e.kind != CareKind.NOTE }

/** 7.1 diaper.kind. */
fun diaperName(d: Diaper) = when (d) {
    Diaper.WET -> "소변"
    Diaper.DIRTY -> "대변"
    Diaper.BOTH -> "둘 다"
    Diaper.DRY -> "깨끗함"
}

// ---- 내 기록 내보내기(C7, U29) ----

const val CSV_HEADER = "kind,start,end,end_unknown,side,amount_ml,diaper,note"

/** 7.1 export.fileName: 내보낸 날(기기 시간대). */
fun exportFileName(now: Long, zone: ZoneId): String =
    "aigyeot-care-${java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())}.csv"

/** RFC 4180: 쉼표·따옴표·줄바꿈이 있으면 따옴표로 감싸고 안의 따옴표는 두 번 쓴다. */
private fun csvField(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

/**
 * U29: 지우지 않은 손 기록을 시작 시각 순으로. UTF-8 BOM, 줄바꿈 CRLF, 첫 줄은 [CSV_HEADER].
 * 시각은 ISO 8601에 그 기록 시간대의 오프셋(초 단위, 지금 메시지 시각과 같은 [rfc3339]). 차 기록 줄은 넣지 않는다.
 */
fun CareFile.toCsv(fallback: ZoneId): String {
    val rows = events.filter { it.deletedAt == null }.sortedBy { it.startAt }.map { e ->
        val z = e.zoneId(fallback)
        listOf(
            e.kind.name,
            rfc3339(e.startAt, z),
            e.endAt?.let { rfc3339(it, z) } ?: "",
            e.endUnknown.toString(),
            e.feedSide?.name ?: "",
            e.amountMl?.toString() ?: "",
            e.diaper?.name ?: "",
            e.note?.let(::csvField) ?: "",
        ).joinToString(",")
    }
    return "﻿" + (listOf(CSV_HEADER) + rows).joinToString("\r\n") + "\r\n"
}
