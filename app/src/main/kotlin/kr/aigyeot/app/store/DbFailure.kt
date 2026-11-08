package kr.aigyeot.app.store

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

// C302: Room 실패 처리 방침 하나(계약 6.0). 지점마다 try-catch·재시도 반복문·대기 시간을 따로 쓰지 않고 이 도우미만 쓴다.
// 범주(S·T·A·W·R·U)는 부르는 곳이 정한다: S·T는 retrying 뒤 던져 die, A·U 쓰기는 bestEffort, W는 한 바퀴 실패 뒤 WORKER_RETRY_MS로 다시 깨움,
// R은 다 실패하면 ack하지 않음, U 읽기는 retryOnDbError. 취소(CancellationException)는 어디서도 잡지 않고 다시 하지 않는다.

/** 같은 자리에서 시도하는 횟수(첫 시도 포함). */
const val DB_TRIES = 3
/** 시도 사이 대기. */
const val DB_RETRY_PAUSE_MS = 100L
/** W: 일꾼 한 바퀴가 다 실패하면 새 신호가 없어도 이만큼 뒤에 다시 깨운다(L94, 설계값 workerRetryMs). */
const val WORKER_RETRY_MS = 10_000L
/** U: 화면 읽기 흐름이 실패하면 이만큼 뒤에 다시 구독한다(C293). */
const val SCREEN_RESUBSCRIBE_MS = 1_000L

private const val TAG = "db"
private val sleep: (Long) -> Unit = { Thread.sleep(it) }

/** 시험용 연결 고리 하나: 재시도 사이 대기. 시험이 바꾸면 그 사이에 실패 원인을 걷어낸다. */
internal var dbPause: (Long) -> Unit = sleep

/** 블로킹(호스트·store 스레드). 최대 [DB_TRIES]번, 마지막 실패는 던진다. */
fun <T> retrying(what: String, block: () -> T): T {
    repeat(DB_TRIES - 1) { n ->
        try { return block() } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "C302 $what 실패, 다시 ${n + 1}", e)
            dbPause(DB_RETRY_PAUSE_MS)
        }
    }
    return block()
}

/** 코루틴. [retrying]과 같은 횟수를 스레드를 막지 않고 `delay`로 기다린다. 마지막 실패는 던진다. */
suspend fun <T> retryingSuspend(what: String, block: suspend () -> T): T {
    repeat(DB_TRIES - 1) { n ->
        try { return block() } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "C302 $what 실패, 다시 ${n + 1}", e)
            dbPause.let { if (it === sleep) delay(DB_RETRY_PAUSE_MS) else it(DB_RETRY_PAUSE_MS) }   // 시험이 바꾼 대기는 그대로 부른다
        }
    }
    return block()
}

/** A·U 쓰기: [retrying] 뒤 다 실패하면 로그만 남기고 false. 던지지 않는다. */
fun bestEffort(what: String, block: () -> Unit): Boolean = try { retrying(what, block); true } catch (e: Exception) {
    if (e is CancellationException) throw e
    Log.e(TAG, "C302 $what 실패: 로그만", e); false
}

/** [bestEffort]의 코루틴판. */
suspend fun bestEffortSuspend(what: String, block: suspend () -> Unit): Boolean = try { retryingSuspend(what, block); true } catch (e: Exception) {
    if (e is CancellationException) throw e
    Log.e(TAG, "C302 $what 실패: 로그만", e); false
}

/** C293·C302 U: Room 읽기 흐름이 실패해도 처리기 없는 코루틴(화면·Graph)이 프로세스를 죽이지 않게 [SCREEN_RESUBSCRIBE_MS] 뒤 다시 구독한다. 취소는 다시 시도하지 않는다. */
fun <T> Flow<T>.retryOnDbError(tag: String): Flow<T> = retryWhen { e, _ ->
    if (e is CancellationException) false else { Log.w(tag, "Room 읽기 실패, ${SCREEN_RESUBSCRIBE_MS}ms 뒤 다시", e); delay(SCREEN_RESUBSCRIBE_MS); true }
}
