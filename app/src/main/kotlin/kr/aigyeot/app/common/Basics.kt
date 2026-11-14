package kr.aigyeot.app.common

import kr.aigyeot.engine.DAY_MS
import kr.aigyeot.engine.MINUTE_MS
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// 앱 여러 곳이 함께 쓰는 기본 도우미: ULID, 시각 문자열, 분·하루, 한글 조사. 휴대폰 앱의 Basics와 같은 규칙이다.

internal const val MINUTE = MINUTE_MS
internal const val DAY = DAY_MS

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private val random = SecureRandom()
private val ULID_RE = Regex("[0-9A-HJKMNP-TV-Z]{26}")

/** ULID(대문자 Crockford base32 26자): 앞 48비트 시각 + 뒤 80비트 난수. [next]는 난수(0 until n), 장면은 시드 난수를 넘긴다(같은 시드면 같은 id). */
internal fun ulid(now: Long, next: (Int) -> Int = random::nextInt): String {
    val out = CharArray(26)
    var t = now
    for (i in 9 downTo 0) { out[i] = CROCKFORD[(t % 32).toInt()]; t /= 32 }
    for (i in 10 until 26) out[i] = CROCKFORD[next(32)]
    return String(out)
}

/** 밖에서 온 곡 id 검사. 파일 이름(CarPaths)에 들어가므로 통과한 것만 쓴다('받는 쪽이 전부 검사'). */
internal fun isUlid(s: String) = ULID_RE.matches(s)

/** RFC 3339(시차 포함, 초 단위). */
internal fun rfc3339(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS))

/** RFC 3339 → epoch ms. 형식이 틀리면 null. */
internal fun instantOf(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** 마지막 글자의 받침(0 = 없음, 8 = ㄹ). 한글이 아니면 없음으로 본다. */
internal fun jong(w: String): Int { val c = w.lastOrNull() ?: return 0; return if (c in '가'..'힣') (c - '가') % 28 else 0 }

/** 을/를. 받침이 있으면 '을'. 말(Voice)과 질문 답 문장이 같이 쓴다. */
internal fun eul(w: String) = if (jong(w) == 0) "를" else "을"
