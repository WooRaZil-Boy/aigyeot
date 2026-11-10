package kr.aigyeot.phone.domain.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// 돌봄 기록 편집 규칙(C2 타이머·C3 시트). 화면은 동작만 보내고, 입력을 기록으로 만들고 검사하는 일은 여기서 한다.

/** 저장 전 검사: R3 문제가 먼저, 그다음 R4 겹침. 시트와 타이머가 같이 쓴다. */
sealed interface EditCheck {
    data object Ok : EditCheck

    data class Problem(val problem: CareProblem) : EditCheck

    data object Overlap : EditCheck
}

fun CareFile.check(e: CareEvent, now: Long): EditCheck =
    e.problem(now)?.let { EditCheck.Problem(it) } ?: if (overlaps(e, now)) EditCheck.Overlap else EditCheck.Ok

/** 새 기록 시트의 몇 분 전 칩(분). */
val AGO_MINUTES = listOf(0L, 5L, 15L, 30L, 60L)

/**
 * 기록 적기·고치기 시트의 입력(불변). 화면을 다시 만들어도(회전·프로세스 종료) 이어 쓰도록 직렬화한다.
 * [agoMin]: 새 기록에서 고른 몇 분 전 칩. null이면 '직접'(날짜·시각 칩으로 고른다).
 * [pinnedDay]: 고치기이거나 기록 탭의 다른 날에서 연 시트라 시각을 골라도 그 날에 둔다. [datePicked]도 같다(날짜를 직접 골랐다).
 */
@Serializable
data class CareEditDraft(
    /** 시트 하나의 id. 새 기록의 id도 이것이다(빠르게 두 번 눌러도 같은 id라 하나만 생긴다). */
    val sheetId: String,
    /** 고치는 기록 id. 새 기록이면 null. */
    val editingId: String?,
    val kind: CareKind,
    val startAt: Long,
    val endAt: Long?,
    val side: FeedSide?,
    val ml: Int?,
    val diaper: Diaper,
    val note: String,
    val zone: String,
    val agoMin: Long?,
    val pinnedDay: Boolean,
    val datePicked: Boolean = false,
) {
    val timed: Boolean get() = kind == CareKind.FEED || kind == CareKind.SLEEP

    /** 몇 분 전 칩은 새 기록·오늘에서 연 시트에만 있다. */
    val showAgo: Boolean get() = editingId == null && !pinnedDay

    /** 시작(시각) 행: 칩이 시각을 말하는 동안은 숨기고 '직접'이면 보인다(iOS와 같음). */
    val showStart: Boolean get() = agoMin == null

    val zoneId: ZoneId get() = runCatching { ZoneId.of(zone) }.getOrDefault(APP_ZONE)

    /** 쓸 수 있는 글이 있는가(메모는 글이 있어야 저장한다). */
    val savable: Boolean get() = kind != CareKind.NOTE || note.isNotBlank()

    companion object {
        /** 새 기록. 다른 날([baseDate])에서 열면 그날 낮 12시, 아니면 지금(칩 '지금'). */
        fun new(sheetId: String, kind: CareKind, baseDate: LocalDate?, now: Long, zone: ZoneId) = CareEditDraft(
            sheetId = sheetId,
            editingId = null,
            kind = kind,
            startAt = baseDate?.let { onDate(it, 12, 0, zone) } ?: now,
            endAt = null,
            side = null,
            ml = null,
            diaper = Diaper.WET,
            note = "",
            zone = zone.id,
            agoMin = if (baseDate == null) 0L else null,
            pinnedDay = baseDate != null,
        )

        /** 고치기: 그 기록의 값과 시간대 그대로. */
        fun edit(sheetId: String, e: CareEvent) = CareEditDraft(
            sheetId = sheetId,
            editingId = e.id,
            kind = e.kind,
            startAt = e.startAt,
            endAt = e.endAt,
            side = e.feedSide,
            ml = e.amountMl,
            diaper = e.diaper ?: Diaper.WET,
            note = e.note.orEmpty(),
            zone = e.zone,
            agoMin = null,
            pinnedDay = true,
        )
    }
}

/** 몇 분 전 칩: 누른 때 기준으로 시작을 정한다. */
fun CareEditDraft.withAgo(min: Long, now: Long) = copy(agoMin = min, startAt = now - min * MINUTE, datePicked = false)

/** '직접': 지금 값에서 날짜·시각을 고른다. */
fun CareEditDraft.custom() = copy(agoMin = null)

/** 날짜를 고른다. 끝도 같은 만큼 옮겨 길이를 지킨다. */
fun CareEditDraft.withDate(d: LocalDate): CareEditDraft {
    val t = Instant.ofEpochMilli(startAt).atZone(zoneId)
    val moved = onDate(d, t.hour, t.minute, zoneId)
    return copy(startAt = moved, endAt = endAt?.let { it + (moved - startAt) }, agoMin = null, datePicked = true)
}

/** 시작 시각을 고른다. 고치기·다른 날·날짜를 고른 시트는 지금 보이는 날 그대로, 새 기록·오늘이면 지금 이전에서 가장 늦은 그 시각. */
fun CareEditDraft.withStartTime(hour: Int, minute: Int, now: Long): CareEditDraft {
    val at = if (editingId != null || pinnedDay || datePicked) onDate(Instant.ofEpochMilli(startAt).atZone(zoneId).toLocalDate(), hour, minute, zoneId)
    else latestAtOrBefore(hour, minute, now + MINUTE, zoneId)
    return copy(startAt = at, agoMin = null)
}

/** 끝 시각을 고른다: 시작 뒤 처음 오는 그 시각(자정을 넘긴 잠). */
fun CareEditDraft.withEndTime(hour: Int, minute: Int) = copy(endAt = firstAtOrAfter(hour, minute, startAt, zoneId))

fun CareEditDraft.withEndUnknown() = copy(endAt = null)

/** 쪽은 고르지 않아도 된다: 고른 칸을 다시 누르면 비운다. */
fun CareEditDraft.toggledSide(s: FeedSide) = copy(side = if (side == s) null else s)

/** U17·U24: 메모는 200자(코드 포인트)에서 더 받지 않는다. */
fun CareEditDraft.withNote(text: String) = if (text.codePointCount(0, text.length) <= NOTE_MAX) copy(note = text) else this

/**
 * 입력을 저장할 기록으로 만든다. 종류에 맞지 않는 칸은 비우고, 끝을 고르지 않은 수유·잠은 '끝 시각 모름'이다.
 * [base]는 고치는 기록의 지금 모양(없으면 [CareEditDraft.sheetId]를 id로 하는 새 기록).
 */
fun CareEditDraft.toEvent(base: CareEvent?, now: Long): CareEvent {
    val b = base ?: CareEvent(sheetId, kind, startAt, zone = zone, source = CareSource.PHONE, createdAt = now, updatedAt = now)
    return b.copy(
        kind = kind,
        startAt = startAt,
        endAt = if (timed) endAt else null,
        endUnknown = timed && endAt == null,
        feedSide = if (kind == CareKind.FEED) side else null,
        amountMl = if (kind == CareKind.FEED && side == FeedSide.BOTTLE) ml else null,
        diaper = if (kind == CareKind.DIAPER) diaper else null,
        note = note.trim().ifEmpty { null }, // U37: 수유·잠·기저귀에도 메모를 달 수 있다
    )
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

/** 몇 분 전 칩 글(7.1 sheet.chip.*): "지금", "5분 전", "1시간 전". */
fun agoChipText(min: Long) = when (min) {
    0L -> "지금"
    60L -> "1시간 전"
    else -> "${min}분 전"
}
