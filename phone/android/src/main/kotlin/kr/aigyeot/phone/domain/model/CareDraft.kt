package kr.aigyeot.phone.domain.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// 돌봄 기록 편집 규칙(C2 타이머·C3 시트). 화면은 동작만 보내고, 입력을 기록으로 만들고 검사하는 일은 여기서 한다(iOS `CareDraft`와 같은 이름).

/** 저장 전 검사: R3 문제가 먼저, 그다음 R4 겹침. 시트와 타이머가 같이 쓴다. */
sealed interface EditCheck {
    data object Ok : EditCheck

    data class Problem(val problem: CareProblem) : EditCheck

    data object Overlap : EditCheck
}

fun CareFile.check(e: CareEvent, now: Long): EditCheck =
    e.problem(now)?.let { EditCheck.Problem(it) } ?: if (overlaps(e, now)) EditCheck.Overlap else EditCheck.Ok

/** 새 기록 시트의 몇 분 전 칩(분). */
val AGO_CHIPS = listOf(0, 5, 15, 30, 60)

/** 몇 분 전 칩 글(7.1 sheet.chip.*): "지금", "5분 전", "1시간 전". */
fun agoChipText(min: Int) = when (min) {
    0 -> "지금"
    60 -> "1시간 전"
    else -> "${min}분 전"
}

/**
 * 기록 시트(빠른 기록·고치기)의 입력(불변). 화면은 이 값을 바꾸기만 하고, 칩·날짜 옮김·끝 시각 맞춤과 저장할 기록 만들기는 여기서 한다.
 * 화면을 다시 만들어도(회전·프로세스 종료) 이어 쓰도록 직렬화한다.
 * - [sheetId]: 시트 하나의 id(안드로이드만: 화면 저장 상태의 입력이 어느 시트 것인지). 새 기록의 id도 이것이다(빠르게 두 번 눌러도 하나만 생긴다).
 * - [original]: 고치는 기록(연 때 모양). 없으면 새 기록.
 * - [day]: 기록 탭에서 고른 다른 날(새 기록, `yyyy-MM-dd`). 있으면 몇 분 전 칩 없이 그날 낮 12시에서 고른다.
 * - [chip]: 몇 분 전 칩(0 = 지금, null = 직접 고름).
 * - [end]: null이면 '끝 시각 모름'.
 * - [datePicked]: 날짜를 직접 골랐다(안드로이드만: 시각 고르기가 날짜와 따로라 그 뒤 시각을 골라도 그 날에 둔다).
 */
@Serializable
data class CareDraft(
    val sheetId: String,
    val kind: CareKind,
    val original: CareEvent?,
    val day: String? = null,
    val chip: Int?,
    val start: Long,
    val end: Long?,
    val side: FeedSide?,
    val amount: Int?,
    val diaper: Diaper,
    val note: String,
    val datePicked: Boolean = false,
) {
    val hasDuration: Boolean get() = kind == CareKind.FEED || kind == CareKind.SLEEP

    /** 몇 분 전 칩은 오늘 새 기록에만 있다(고치기·다른 날 기록에는 없다). */
    val showsAgoChips: Boolean get() = original == null && day == null

    /** 시작(시각) 행: 칩이 시각을 말하는 동안은 숨기고 '직접'이면 보인다. */
    val showsStart: Boolean get() = chip == null

    val endUnknown: Boolean get() = end == null

    /** R13: 고치는 기록은 그 기록의 시간대로 보인다. 새 기록은 기기 시간대. */
    val zone: ZoneId get() = original?.let { runCatching { ZoneId.of(it.zone) }.getOrNull() } ?: APP_ZONE

    /** 쓸 수 있는가(메모는 글이 있어야 저장한다). */
    val savable: Boolean get() = kind != CareKind.NOTE || note.isNotBlank()

    companion object {
        /** 새 기록: 지금(칩 '지금'). 기록 탭에서 다른 날을 골랐으면 그날 낮 12시에서 직접 고른다. */
        fun new(sheetId: String, kind: CareKind, day: LocalDate?, now: Long) = CareDraft(
            sheetId = sheetId,
            kind = kind,
            original = null,
            day = day?.toString(),
            chip = if (day == null) 0 else null,
            start = day?.let { onDate(it, 12, 0, APP_ZONE) } ?: now,
            end = null,
            side = null,
            amount = null,
            diaper = Diaper.WET,
            note = "",
        )

        /** 고치기: 그 기록의 값에서 시작한다. */
        fun editing(sheetId: String, e: CareEvent) = CareDraft(
            sheetId = sheetId,
            kind = e.kind,
            original = e,
            chip = null,
            start = e.startAt,
            end = e.endAt,
            side = e.feedSide,
            amount = e.amountMl,
            diaper = e.diaper ?: Diaper.WET,
            note = e.note.orEmpty(),
        )
    }
}

/** 몇 분 전 칩: 누른 때 기준으로 시작을 정한다. null('직접')이면 지금 값에서 날짜·시각을 고른다. */
fun CareDraft.pick(chip: Int?, now: Long) =
    if (chip == null) copy(chip = null) else copy(chip = chip, start = now - chip * MINUTE, datePicked = false)

/** 시작 시각 칩. 고치기·다른 날·날짜를 고른 시트는 지금 보이는 날 그대로, 오늘 새 기록이면 지금 이전에서 가장 늦은 그 시각. */
fun CareDraft.setStart(hour: Int, minute: Int, now: Long): CareDraft {
    val at = if (original != null || day != null || datePicked) onDate(Instant.ofEpochMilli(start).atZone(zone).toLocalDate(), hour, minute, zone)
    else latestAtOrBefore(hour, minute, latestPick(now), zone)
    return copy(start = at, chip = null)
}

/** 시작 날짜 칩: 날을 옮기면 끝도 같은 만큼 옮겨 길이를 지킨다. */
fun CareDraft.moveStartDay(to: LocalDate): CareDraft {
    val t = Instant.ofEpochMilli(start).atZone(zone)
    val moved = onDate(to, t.hour, t.minute, zone)
    return copy(start = moved, end = end?.let { it + (moved - start) }, chip = null, datePicked = true)
}

/** 끝 시각 칩: 고른 시·분을 시작 뒤 처음 오는 그 시각으로 둔다(자정을 넘긴 잠). */
fun CareDraft.setEnd(hour: Int, minute: Int) = copy(end = firstAtOrAfter(hour, minute, start, zone))

/** '끝 시각 모름'. */
fun CareDraft.setEndUnknown() = copy(end = null)

/** 쪽은 고르지 않아도 된다: 고른 칸을 다시 누르면 비운다. */
fun CareDraft.toggleSide(s: FeedSide) = copy(side = if (side == s) null else s)

/** U17·U24: 메모는 200자(코드 포인트)에서 더 받지 않는다. */
fun CareDraft.setNote(text: String) = if (text.codePointCount(0, text.length) <= NOTE_MAX) copy(note = text) else this

/**
 * 저장할 기록. [base]는 고치는 기록의 지금 모양(기본은 연 때 모양 [CareDraft.original]), 없으면 [CareDraft.sheetId]를 id로 하는 새 기록.
 * 종류에 맞지 않는 칸은 [normalized]가 비운다.
 */
fun CareDraft.event(now: Long, base: CareEvent? = original): CareEvent {
    val b = base ?: CareEvent(sheetId, kind, start, zone = APP_ZONE.id, source = CareSource.PHONE, createdAt = now, updatedAt = now)
    return b.copy(
        kind = kind,
        startAt = start,
        endAt = if (hasDuration) end else null,
        endUnknown = hasDuration && end == null,
        feedSide = side,
        amountMl = amount,
        diaper = diaper,
        note = note,
    ).normalized()
}

/** 종류에 맞지 않는 칸을 비운다(iOS `CareRules.normalized`): 쪽·양은 수유만(양은 젖병만), 기저귀 종류는 기저귀만, 메모는 앞뒤 빈칸을 걷는다(U37). */
fun CareEvent.normalized(): CareEvent = copy(
    feedSide = if (kind == CareKind.FEED) feedSide else null,
    amountMl = if (kind == CareKind.FEED && feedSide == FeedSide.BOTTLE) amountMl else null,
    diaper = if (kind == CareKind.DIAPER) diaper else null,
    note = note?.trim()?.ifEmpty { null },
)

// ---- 타이머(진행 중 기록)의 고치기 ----

/** 시작 시각 수정: 지금 시작에 가장 가까운 그 시각 중 [limit] 이전(하루 넘게 진행된 기록의 날짜가 바뀌지 않게). */
fun CareEvent.withStartPicked(hour: Int, minute: Int, limit: Long) =
    copy(startAt = nearestAtOrBefore(hour, minute, startAt, limit, runCatching { ZoneId.of(zone) }.getOrDefault(APP_ZONE)))

/** 끝 시각 고르기(최대 길이를 넘긴 기록, U14): 시작 뒤 처음 오는 그 시각. */
fun CareEvent.withEndPicked(hour: Int, minute: Int) =
    copy(endAt = firstAtOrAfter(hour, minute, startAt, runCatching { ZoneId.of(zone) }.getOrDefault(APP_ZONE)))

/** 쪽을 바꾸면 젖병이 아닐 때 양을 비운다. */
fun CareEvent.withSide(s: FeedSide) = copy(feedSide = s, amountMl = if (s == FeedSide.BOTTLE) amountMl else null)
