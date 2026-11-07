package kr.aigyeot.phone.core

import kotlinx.serialization.json.JsonObject
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.Role
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// 안드로이드 없이 JVM에서 시험하는 작은 도구들: id, 시각, 연결 코드.

const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE
const val DAY = 24 * HOUR

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private val random = SecureRandom()

/** ULID(대문자 Crockford base32 26자, 계약 0장). */
fun ulid(now: Long = System.currentTimeMillis()): String {
    val out = CharArray(26)
    var t = now
    for (i in 9 downTo 0) {
        out[i] = CROCKFORD[(t % 32).toInt()]
        t /= 32
    }
    for (i in 10 until 26) out[i] = CROCKFORD[random.nextInt(32)]
    return String(out)
}

private val ULID_RE = Regex("[0-9A-HJKMNP-TV-Z]{26}")

/** 메시지 id 형식 검사(계약 0장). 밖에서 온 값(인텐트·푸시)은 이것을 통과해야 쓴다. */
fun isUlid(s: String) = ULID_RE.matches(s)

/** 앱의 시간대. 한국 전용이라 기기 설정과 상관없이 고정한다(사용자 결정 11-07: 서머타임·날짜변경선·다른 시간대는 다루지 않는다). */
val APP_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

/** RFC 3339(시차 포함, 초 단위). `OffsetDateTime.toString()`은 0초를 빼므로 쓰지 않는다. */
fun rfc3339(now: Long, zone: ZoneId = APP_ZONE): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        Instant.ofEpochMilli(now).atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS),
    )

/** RFC 3339 → epoch ms. 형식이 틀리면 null. 비교는 문자열이 아니라 순간으로 한다(계약 '공통 표기'). */
fun instantOf(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** 보낼 봉투. `seq`·`car`·`from`은 중계가 채운다(3.1). */
fun envelope(type: String, body: JsonObject, now: Long, id: String = ulid(now), to: String? = null) =
    Envelope(v = 1, id = id, type = type, to = to, ts = rfc3339(now), body = body)

/** 연결 코드 정규화(1.4): 공백·하이픈 제거, 대문자. 6자리 숫자 또는 Crockford 규칙을 적용한 초대 10자. 아니면 null. */
fun normalizeCode(raw: String): String? {
    val s = raw.replace(Regex("[\\s-]"), "").uppercase()
    if (Regex("\\d{6}").matches(s)) return s
    val c = s.replace('O', '0').replace('I', '1').replace('L', '1')
    return if (Regex("[0-9A-HJKMNP-TV-Z]{10}").matches(c)) c else null
}

/** `aigyeot://pair?code=…` 링크의 코드. 형식 검사를 통과한 것만 돌려준다(7.6, 다른 앱도 같은 scheme을 등록할 수 있다). */
fun codeFromLink(link: String): String? {
    val m = Regex("^aigyeot://pair\\?(?:.*&)?code=([^&#]+)").find(link.trim()) ?: return null
    // `%ZZ` 같은 잘못된 인코딩은 IllegalArgumentException을 던진다. 잘못된 링크로 본다.
    val raw = runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull() ?: return null
    return normalizeCode(raw)
}

/** 6자리면 연결 코드(owner), 아니면 초대 코드(guardian). */
fun isInviteCode(code: String) = code.length == 10

/** 코드가 들어온 길. QR·링크만 확인 화면을 거치고, 직접 친 코드는 바로 연결한다(G17, 명세서 17장 L57). */
enum class CodeSource { LINK, QR, TYPED }

fun needsConfirm(source: CodeSource) = source != CodeSource.TYPED

/** 직접 친 코드를 냈을 때 화면이 할 일. */
enum class TypedAction { BAD_FORMAT, IGNORE, CONFIRM_REPLACE, PAIR }

/**
 * 직접 친 코드 제출(자동 제출 포함). 요청 중이거나 429 잠금 중이면 요청하지 않는다(9장).
 * 이미 연결된 폰이면 요청 전에 교체 확인 화면(G16)으로 보낸다. 코드 대조 생략(G17)과 교체 동의는 별개다.
 */
fun typedSubmitAction(code: String?, connected: Boolean, busy: Boolean, lockUntil: Long?, now: Long): TypedAction = when {
    code == null -> TypedAction.BAD_FORMAT
    busy || isPairLocked(lockUntil, now) -> TypedAction.IGNORE
    connected -> TypedAction.CONFIRM_REPLACE
    else -> TypedAction.PAIR
}

/** 429 잠금이 아직 남았는가. 시각은 화면이 아니라 모델이 들고 있다. */
fun isPairLocked(lockUntil: Long?, now: Long) = lockUntil != null && lockUntil > now

/**
 * U52: 연결(직접 친 코드·'연결하기'·'끊고 연결하기'의 끊기까지)이 도는 동안 온 QR·링크로는 확인 화면에 가지 않는다.
 * 성공했지만 화면이 아직 완료로 옮기지 않은 동안(`doneUnshown`)도 같다. 늦게 도는 완료 이동이 새 확인 화면을 지운다(iOS `confirming`과 같음).
 * 화면의 코드와 도는 연결의 코드가 어긋나지 않게 하고, [PAIR_BUSY]를 짧게 보인다.
 */
fun canOpenConfirm(pairBusy: Boolean, doneUnshown: Boolean) = !pairBusy && !doneUnshown

/** 끝난 연결 결과(`role`)로 완료 화면에 가도 되는가. 그사이 401·4001로 끊겼거나(GONE) 자격이 바뀌었으면 아니다(7.1, U25). */
fun pairDoneValid(role: Role?, gone: Boolean, currentRole: Role?) = role != null && !gone && role == currentRole

/** 저장 실패 띠. 버림 안내와 함께 떠도 서로 덮지 않는다([withNoticeLine]). */
const val SAVE_FAILED = "저장하지 못했어요. 저장 공간을 확인해 주세요."

/** 7.1 pair.busy. */
const val PAIR_BUSY = "연결하는 중이에요. 끝난 뒤 링크를 다시 열어 주세요."

/** 확인 화면 제목. 초대 코드에 "차 화면에…"라고 묻지 않는다(G17). */
fun confirmTitle(code: String) = if (isInviteCode(code)) "받은 초대 코드가 이것과 같나요?" else "차 화면에 이 코드가 보이나요?"

/** 보낼 함 다시 보내기·재접속 간격(1·2·4…60초, 2장). */
fun backoffMs(attempts: Int): Long = minOf(60_000L, 1000L shl minOf(attempts, 6))

/** 연결 429 뒤 버튼 잠금 끝 시각. Retry-After가 없거나 0 이하면 10초(iOS와 같음, 계약 9장). */
fun pairLockUntil(now: Long, retryAfterS: Long?): Long = now + (retryAfterS?.takeIf { it > 0 } ?: 10L) * 1000

/** 조향 비교 불가 사유 문구(7.2). */
fun steeringUnavailableText(noCry: Boolean) = if (noCry) "울음이 없어 비교하지 않았어요" else "비교할 구간이 없어요"

/**
 * 사용자가 요청한 끊기(연결 7.1 "어디서든 401·4001 → GONE"). 그사이 온 401·4001은 끊기의 결과일 수 있어 바로 GONE으로 가지 않고 적어 둔다.
 * 끊기가 성공하면 평소 끊기(GONE 안내 없음), 실패로 끝나면 적어 둔 것이 있을 때만 GONE으로 간다(iOS `lostWhileLeaving`).
 */
class LeaveWatch<C : Any> {
    private var leaving: C? = null
    private var lost = false

    fun start(c: C) {
        leaving = c
        lost = false
    }

    /** 끊는 중인 `c`에 401·4001이 왔다(중계가 그 토큰을 지웠다). */
    fun lostFor(c: C): Boolean = leaving == c && lost

    /** `c`의 401·4001. 끊는 중인 자격이면 적어 두고 true(부르는 쪽은 아직 GONE으로 가지 않는다). */
    fun absorb(c: C): Boolean = (leaving == c).also { if (it) lost = true }

    /** 끊기가 끝났다. 실패했는데 그사이 그 자격의 401·4001이 왔으면 true(부르는 쪽이 GONE으로 간다). */
    fun end(failed: Boolean): Boolean = (failed && lost).also {
        leaving = null
        lost = false
    }
}
