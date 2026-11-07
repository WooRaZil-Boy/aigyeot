package kr.aigyeot.phone.domain.model

// 다시 하기·기다리기 간격은 이 파일 한 곳에 둔다(arch-phone-android 6장). 지점마다 숫자를 쓰지 않는다.

/** 보낼 함 다시 보내기·재접속 간격(1·2·4…60초, 계약 2장). */
fun backoffMs(attempts: Int): Long = minOf(60_000L, 1000L shl minOf(attempts, 6))

/**
 * 연결 429 뒤 버튼 잠금 끝 시각(계약 9장). Retry-After가 없거나 0 이하면 10초. iOS는 없으면 10초, 0이면 1초다
 * (중계가 0을 보내지 않아 실제 차이는 없다, 11-08 두 앱 대조 D13).
 */
fun pairLockUntil(now: Long, retryAfterS: Long?): Long = now + (retryAfterS?.takeIf { it > 0 } ?: 10L) * 1000

/** 상태 요청 사이 최소 간격(7.4, G6). */
const val STATE_REQUEST_GAP_MS = 10 * MINUTE

/** 저장에 실패한 일꾼(보낼 함·연결 시작)이 다시 해 보기까지(iOS `retrySaveLater`와 같음, W 범주). */
const val SAVE_RETRY_MS = 5_000L

/** 소켓이 4008·429로 닫혔을 때 다시 붙기까지. */
const val RATE_LIMITED_WAIT_MS = 60_000L

/** 앞에 있고 소켓이 붙어 있는 동안 기기 목록을 다시 받는 간격(설계값, 실측 아님). */
const val DEVICE_POLL_MS = 60_000L

/** 글자 ping: 붙은 뒤 첫 ping까지, pong을 기다리는 시간, 다음 ping까지 남은 시간(25초 간격, 계약 2장). */
const val PING_FIRST_MS = 25_000L
const val PONG_WAIT_MS = 10_000L
const val PING_REST_MS = 15_000L

/** FCM 토큰을 기다리는 최대 시간. */
const val PUSH_TOKEN_TIMEOUT_MS = 5_000L
