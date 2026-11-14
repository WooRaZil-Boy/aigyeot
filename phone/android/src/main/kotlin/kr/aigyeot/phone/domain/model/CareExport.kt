package kr.aigyeot.phone.domain.model

import java.time.Instant
import java.time.ZoneId

// ---- 내 기록 내보내기 ----

const val CSV_HEADER = "kind,start,end,end_unknown,side,amount_ml,diaper,note"

/** export.fileName: 내보낸 날(기기 시간대). */
fun exportFileName(now: Long, zone: ZoneId): String =
    "aigyeot-care-${java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())}.csv"

/** RFC 4180: 쉼표·따옴표·줄바꿈이 있으면 따옴표로 감싸고 안의 따옴표는 두 번 쓴다. */
private fun csvField(s: String): String = if (s.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

/**
 * 지우지 않은 손 기록을 시작 시각 순으로. UTF-8 BOM, 줄바꿈 CRLF, 첫 줄은 [CSV_HEADER].
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
            e.note?.let(::csvField) ?: ""
        ).joinToString(",")
    }
    return "\uFEFF" + (listOf(CSV_HEADER) + rows).joinToString("\r\n") + "\r\n"
}
