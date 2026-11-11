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

/**
 * 몇 분 전 칩: 누른 때 기준으로 시작을 정한다. null('직접')이면 지금 값에서 날짜·시각을 고른다.
 * 시작을 옮겨도 사용자가 고른 끝은 말없이 바꾸지 않는다: 끝이 시작보다 빠르면 저장 검사(R3)가 알린다(C384, setStart도 같다).
 */
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

// ---- 기록 시트 v8(U80~U84): 질문·줄 값·저장 단추 글. 화면은 이 글을 그리기만 한다(iOS `CareDraft`와 같은 규칙) ----

/** 7.1 sheet.q.*(U81): 새 기록·고치기·지난 기록이 같은 질문이다. 시트의 읽기 이름도 이것이다. */
fun sheetQuestion(k: CareKind) = when (k) {
    CareKind.FEED -> "어떻게 먹였어요?"
    CareKind.SLEEP -> "언제 잤어요?"
    CareKind.DIAPER -> "어떤 기저귀였어요?"
    CareKind.NOTE -> "무엇을 적을까요?"
}

/** 시트에서 값을 고르는 자리(U82). 줄은 [SheetField.START]·[END]·[MEMO], [AMOUNT]는 젖병 양 칸이다. */
enum class SheetField { START, END, MEMO, AMOUNT }

/** 접힌 줄(U82): 수유 = 시작·끝·메모, 기저귀 = 시각·메모, 잠 = 끝·메모(시작은 주인공 칸), 메모 = 시각(글은 주인공 글칸). */
val CareDraft.rows: List<SheetField>
    get() = when (kind) {
        CareKind.FEED -> listOf(SheetField.START, SheetField.END, SheetField.MEMO)
        CareKind.SLEEP -> listOf(SheetField.END, SheetField.MEMO)
        CareKind.DIAPER -> listOf(SheetField.START, SheetField.MEMO)
        CareKind.NOTE -> listOf(SheetField.START)
    }

/** 줄 이름(7.1 sheet.row.*). 빈 메모 줄은 누르면 하는 줄 '메모 더하기'(`sheet.memo.add`). */
fun CareDraft.rowName(f: SheetField) = when (f) {
    SheetField.START -> if (hasDuration) "시작" else "시각"
    SheetField.END -> "끝"
    SheetField.MEMO -> if (note.isBlank()) "메모 더하기" else "메모"
    SheetField.AMOUNT -> "양"
}

/**
 * 시작·시각 줄 값(U82): 몇 분 전을 고른 동안 '{시각} · {칩}', 직접 고른 오늘이면 '{시각}', 다른 날·고치기면 '{날짜} {시각}'(`log.date.*`).
 */
fun CareDraft.startValue(now: Long): String {
    val time = koClock(start, zone)
    chip?.takeIf { showsAgoChips }?.let { return "$time · ${agoChipText(it)}" }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    return if (original != null || this.day != null || day != today) "${dayLabel(day, today)} $time" else time
}

/** 끝 줄 값(U82): 모르면 '모름'(`sheet.end.unknownValue`), 알면 '{시각} · {길이}'(U27). 끝이 시작보다 이르면 '{시각}'만. */
fun CareDraft.endValue(): String {
    val e = end ?: return "모름"
    val time = koClock(e, zone)
    return if (e < start) time else "$time · ${durationText(e - start)}"
}

/** 메모 줄 값: 적었으면 글 한 줄(넘치면 화면이 말줄임), 비었으면 없음(줄 이름이 '메모 더하기'). */
fun CareDraft.memoValue(): String? = note.trim().lineSequence().firstOrNull()?.takeIf { it.isNotEmpty() }

/** 저장 검사(R3)에 걸린 탓이 된 자리(U82 끝): 미래인 시각이 시작이면 시작·시각, 끝이면 끝, 끝·길이 → 끝, 양 → 양 칸, 메모 길이 → 메모. */
fun CareProblem.field(): SheetField = when (this) {
    CareProblem.FUTURE_START -> SheetField.START
    CareProblem.FUTURE_END, CareProblem.END_BEFORE_START, CareProblem.TOO_LONG -> SheetField.END
    CareProblem.AMOUNT -> SheetField.AMOUNT
    CareProblem.NOTE_TOO_LONG -> SheetField.MEMO
}

/** 오류 한 줄이 붙는 자리(U82: 탓이 된 칸 바로 아래, iOS와 같음): 접힌 줄, 젖병 양 칸, 주인공 칸(잠 시작·메모 글칸). */
enum class ErrorSpot { ROW, AMOUNT, HERO }

/** 탓이 된 자리 [f]가 이 시트의 접힌 줄이면 그 줄 아래, 양이면 양 칸 아래, 그 밖(잠 시작·메모 종류의 글)은 주인공 칸 아래. */
fun CareDraft.errorSpot(f: SheetField): ErrorSpot = when {
    f in rows -> ErrorSpot.ROW
    f == SheetField.AMOUNT -> ErrorSpot.AMOUNT
    else -> ErrorSpot.HERO
}

/**
 * 쪽 칸의 '지난번'(U80, `sheet.last`, iOS `lastSide`와 같은 규칙): 새 기록(빠른 기록·지난 기록 적기)에만 두고 고치기에서는 숨긴다(null).
 * 새 기록이면 지우지 않은 가장 늦은 수유의 쪽이다. 표시만 하고 미리 고르지 않는다.
 */
fun CareFile.sheetLastSide(d: CareDraft): FeedSide? =
    if (d.original != null) null else events.filter { it.kind == CareKind.FEED && it.deletedAt == null }.maxByOrNull { it.startAt }?.feedSide

/** 첫 기록 도움말 조건(U84): 지우지 않은 손 기록이 0건(차 기록 줄은 `care.json`에 없어 세지 않는다). */
val CareFile.firstRecord: Boolean get() = events.none { it.deletedAt == null }

/** U83 저장 단추 글을 줄이는 길이(공백 포함 코드 포인트). */
const val SAVE_LABEL_MAX = 22

/**
 * 저장 단추 글(U83 `sheet.save.label`): '{시각} {고른 것} {종류} 저장하기'. 시각은 시작(잠이고 끝을 알면 구간), 고른 것은 수유 쪽(젖병이면 양이 있을 때
 * '젖병 {n}ml'이고 '수유'를 뺀다, 양이 없으면 '젖병 수유')·기저귀 종류. 메모는 '메모 저장하기'. [shorten]이면 22자를 넘을 때 ① 잠 구간 → 시작만
 * ② 고른 것을 뺀다 ③ 시각을 뺀다 순서로 처음 22자 안에 드는 것을 쓴다(큰 글자는 줄이지 않고 두 줄까지).
 */
fun CareDraft.saveLabel(shorten: Boolean = true): String {
    if (kind == CareKind.NOTE) return "메모 저장하기"
    val clock = koClock(start, zone)
    val span = end?.takeIf { kind == CareKind.SLEEP && it > start }?.let { koSpan(start, it, zone) }
    val bottleMl = amount?.takeIf { kind == CareKind.FEED && side == FeedSide.BOTTLE }
    val chosen = when (kind) {
        CareKind.FEED -> side?.let { if (it == FeedSide.BOTTLE) bottleMl?.let { ml -> "젖병 ${ml}ml" } ?: "젖병" else sideName(it) }
        CareKind.DIAPER -> diaperName(diaper)
        else -> null
    }
    // 젖병 양이 있으면 '수유'를 뺀다(그 글이 무엇인지 말한다).
    val noun = if (bottleMl != null) null else kindName(kind)
    fun label(time: String?, pick: String?, name: String?) = listOfNotNull(time, pick, name ?: kindName(kind).takeIf { pick == null }, "저장하기").joinToString(" ")
    val full = label(span ?: clock, chosen, noun)
    if (!shorten) return full
    return sequenceOf(full, label(clock, chosen, noun), label(clock, null, kindName(kind)), label(null, null, kindName(kind)))
        .first { it.codePointCount(0, it.length) <= SAVE_LABEL_MAX || it == label(null, null, kindName(kind)) }
}

// ---- 타이머(진행 중 기록)의 고치기 ----

/** 시작 시각 수정: 지금 시작에 가장 가까운 그 시각 중 [limit] 이전(하루 넘게 진행된 기록의 날짜가 바뀌지 않게). */
fun CareEvent.withStartPicked(hour: Int, minute: Int, limit: Long) =
    copy(startAt = nearestAtOrBefore(hour, minute, startAt, limit, runCatching { ZoneId.of(zone) }.getOrDefault(APP_ZONE)))

/** 끝 시각 고르기(최대 길이를 넘긴 기록, U14): 시작 뒤 처음 오는 그 시각. */
fun CareEvent.withEndPicked(hour: Int, minute: Int) =
    copy(endAt = firstAtOrAfter(hour, minute, startAt, runCatching { ZoneId.of(zone) }.getOrDefault(APP_ZONE)))

/** 쪽을 바꾸면 젖병이 아닐 때 양을 비운다. */
fun CareEvent.withSide(s: FeedSide) = copy(feedSide = s, amountMl = if (s == FeedSide.BOTTLE) amountMl else null)
