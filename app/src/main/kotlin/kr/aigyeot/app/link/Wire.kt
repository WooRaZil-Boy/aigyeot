package kr.aigyeot.app.link

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kr.aigyeot.shared.Envelope
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// 봉투·id·시각(contracts-link 0장 '공통 표기', 3.1). 폰 core/Basics.kt와 같은 규칙이다.

internal const val MINUTE = 60_000L
internal const val DAY = 24 * 60 * MINUTE
internal const val KEEP_MS = 7 * DAY   // 보낼 함·처리한 id·끊김 표시 보관(8.2, 8.5)

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private val random = SecureRandom()
private val ULID_RE = Regex("[0-9A-HJKMNP-TV-Z]{26}")

/** ULID(대문자 Crockford base32 26자). */
internal fun ulid(now: Long): String {
    val out = CharArray(26)
    var t = now
    for (i in 9 downTo 0) { out[i] = CROCKFORD[(t % 32).toInt()]; t /= 32 }
    for (i in 10 until 26) out[i] = CROCKFORD[random.nextInt(32)]
    return String(out)
}

/** 밖에서 온 곡 id 검사. 파일 이름(CarPaths)에 들어가므로 통과한 것만 쓴다(6.4 '받는 쪽이 전부 검사'). */
internal fun isUlid(s: String) = ULID_RE.matches(s)

/** RFC 3339(시차 포함, 초 단위). */
internal fun rfc3339(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS))

/** RFC 3339 → epoch ms. 형식이 틀리면 null. */
internal fun instantOf(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** 보낼 함 재시도·재접속 간격 1·2·4…60초(2장, 8.6). */
internal fun backoffMs(attempts: Int): Long = minOf(60_000L, 1000L shl attempts.coerceIn(0, 6))

/** 보낼 봉투. ts는 넣은 시각이라 같은 id로 다시 보내도 내용이 같다(1.4 '같은 id 다시 보내기'). */
internal fun envelopeOf(id: String, type: String, body: JsonObject, createdAt: Long, to: String? = null) =
    Envelope(v = 1, id = id, type = type, to = to, ts = rfc3339(createdAt), body = body)

// 판단 코어가 SendToPhone body에 epoch ms로 담는 시각 칸(차 계약 4.3 C48). link가 봉투를 씌울 때 RFC 3339로 바꾼다.
private val TIME_KEYS = setOf("at", "occurred_at", "started_at", "ended_at", "last_feeding_start", "window_start_expected")

internal fun withRfc3339Times(body: JsonObject): JsonObject = JsonObject(
    body.mapValues { (k, v) -> v.timeOrSelf(k) },
)

private fun JsonElement.timeOrSelf(key: String): JsonElement {
    if (key !in TIME_KEYS) return this
    val ms = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return this
    return JsonPrimitive(rfc3339(ms))
}
