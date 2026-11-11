package kr.aigyeot.phone.domain.model

// 다시 하기·기다리기 간격은 이 파일 한 곳에 둔다(arch-phone-android 6장). 지점마다 숫자를 쓰지 않는다.

/** 보낼 함 다시 보내기·재접속 간격(1·2·4…60초, 계약 2장). */
fun backoffMs(attempts: Int): Long = minOf(60_000L, 1000L shl minOf(attempts, 6))

/**
 * 연결 429 뒤 버튼 잠금 끝 시각(계약 9장). Retry-After가 없거나 0 이하면 10초. iOS는 없으면 10초, 0이면 1초다
 * (중계가 0을 보내지 않아 실제 차이는 없다, 11-08 두 앱 대조 D13).
 */
fun pairLockUntil(now: Long, retryAfterS: Long?): Long = now + (retryAfterS?.takeIf { it > 0 } ?: 10L) * 1000

/**
 * 연결 429 글(contracts-link 7.2·9장, 11-12 사용자 결정, iOS와 같은 규칙). `remainingMs`는 처음 받은 값이 아니라 잠금 끝 시각에서
 * 그때그때 잰 남은 시간이다: 60초 넘으면 "{n}분 뒤에"(올림), 60초 이하면 "{n}초 뒤에"(올림), 0 이하면 잠금이 풀려 null, 모르면 "잠시 뒤에".
 */
fun pairWaitText(remainingMs: Long?): String? = when {
    remainingMs == null -> "잠시 뒤에 다시 해 주세요"
    remainingMs <= 0 -> null
    remainingMs > MINUTE -> "${(remainingMs + MINUTE - 1) / MINUTE}분 뒤에 다시 해 주세요"
    else -> "${(remainingMs + 999) / 1000}초 뒤에 다시 해 주세요"
}

/** [pairWaitText]가 다음에 바뀌기까지(분·초 경계, 1 이상). 잠금 글은 이때만 다시 그린다(1초 고정 폴링 대신). */
fun pairWaitNextMs(remainingMs: Long): Long = (remainingMs - 1).mod(if (remainingMs > MINUTE) MINUTE else 1000L) + 1

/** 상태 요청 사이 최소 간격(7.4, G6). */
const val STATE_REQUEST_GAP_MS = 10 * MINUTE

/** 저장에 실패한 일꾼(보낼 함·연결 시작)이 다시 해 보기까지(iOS `retrySaveLater`와 같음, W 범주). */
const val SAVE_RETRY_MS = 5_000L

/** 소켓이 4008·429로 닫혔을 때 다시 붙기까지. 429에 Retry-After가 없을 때의 기다림도 이것이다(iOS `rateLimitDefault`). */
const val RATE_LIMITED_WAIT_MS = 60_000L

/**
 * 429의 기다림(1.1): Retry-After, 없으면 60초, 1초보다 짧지 않게(받은 함·메시지·상태 요청·노래 PUT 한 곳, iOS `max(retryAfter ?? rateLimitDefault, 1)`).
 * iOS는 받은 함·상태 요청에는 1초 하한이 없다(중계가 0을 보내지 않아 실제 차이는 없다).
 */
fun rateLimitWaitMs(retryAfterS: Long?): Long = maxOf(retryAfterS?.times(1000) ?: RATE_LIMITED_WAIT_MS, 1000L)

/** 409 `song_quota`: 차가 켜져 파일을 처리한 뒤 다시(1.1, iOS `RetryPolicy.songQuota`). */
const val SONG_QUOTA_WAIT_MS = 60_000L

/** 앞에 있고 소켓이 붙어 있는 동안 기기 목록을 다시 받는 간격(설계값, 실측 아님). */
const val DEVICE_POLL_MS = 60_000L

/** 글자 ping: 붙은 뒤 첫 ping까지, pong을 기다리는 시간, 다음 ping까지 남은 시간(25초 간격, 계약 2장). */
const val PING_FIRST_MS = 25_000L
const val PONG_WAIT_MS = 10_000L
const val PING_REST_MS = 15_000L

/** FCM 토큰을 기다리는 최대 시간. */
const val PUSH_TOKEN_TIMEOUT_MS = 5_000L

/** 앱 안 인계 알림 배너는 뜬 지 8초 뒤 저절로 닫힌다(연결 계약 5.6, 명세 17장 L100, iOS `RetryPolicy.bannerNanos`와 같음). */
const val BANNER_MS = 8_000L

/** 되돌리기 띠('지웠어요'·'기록 n건을 가져왔어요' + '되돌리기')가 떠 있는 시간(7장·U38·U64). 가져오기는 이 띠가 끝난 뒤 차로 보낸다(U67 11-09 보충). */
const val UNDO_MS = 5_000L

/** 화면 읽기가 켜져 있을 때의 띠 시간(U87): 기본값의 10배(WCAG 2.2 2.2.1 '시간 조절'). */
const val UNDO_MS_A11Y = 50_000L

/**
 * 되돌리기 띠 시간(U87, iOS `RetryPolicy.undoBandMs`와 같은 규칙): 화면 읽기면 50초, 아니면 5초. 시스템 권장 시간(안드로이드 '조치를 취할 시간')이 더 길면 그 값.
 * 띠를 띄울 때 한 번 정한다. 플랫폼 쪽은 두 값을 읽어 넘기기만 한다.
 */
fun undoBandMs(screenReader: Boolean, systemRecommendedMs: Long): Long = maxOf(if (screenReader) UNDO_MS_A11Y else UNDO_MS, systemRecommendedMs)
