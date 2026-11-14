package kr.aigyeot.app.store

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 방침 하나·도우미 하나. 대기는 dbPause로 세기만 한다(시험이 기다리지 않게)
@RunWith(RobolectricTestRunner::class)
class DbFailureTest {
    private var pauses = 0
    private val orig = dbPause

    @BeforeTest fun hook() {
        dbPause = { pauses++ }
    }

    @AfterTest fun unhook() {
        dbPause = orig
    }

    private fun failing(fails: Int): () -> Int {
        var n = 0
        return { if (n++ < fails) error("실패 $n") else n }
    }

    @Test fun retrying_oneFailureThenSucceeds() {
        assertEquals(2, retrying("t", failing(1)))
        assertEquals(1, pauses)
    }

    @Test fun retrying_allFail_throwsLast() {
        val f = failing(DB_TRIES)
        val x = assertFailsWith<IllegalStateException> { retrying("t", f) }
        assertEquals("실패 $DB_TRIES", x.message, "마지막 실패를 던진다")
        assertEquals(DB_TRIES - 1, pauses)
    }

    @Test fun bestEffort_allFail_falseWithoutThrow() {
        var n = 0
        assertFalse(
            bestEffort("t") {
                n++
                error("실패")
            }
        )
        assertEquals(DB_TRIES, n)
        assertTrue(bestEffort("t") { n++ })
    }

    @Test fun suspendHelpers() = runBlocking {
        val f = failing(1)
        assertEquals(2, retryingSuspend("t") { f() })
        var n = 0
        assertFailsWith<IllegalStateException> {
            retryingSuspend("t") {
                n++
                error("실패")
            }
        }
        assertEquals(DB_TRIES, n)
        assertFalse(bestEffortSuspend("t") { error("실패") })
        assertTrue(bestEffortSuspend("t") {})
    }

    @Test fun cancellation_notRetriedNorSwallowed() = runBlocking {
        var n = 0
        assertFailsWith<CancellationException> {
            retrying("t") {
                n++
                throw CancellationException("취소")
            }
        }
        assertFailsWith<CancellationException> {
            bestEffort("t") {
                n++
                throw CancellationException("취소")
            }
        }
        assertFailsWith<CancellationException> {
            retryingSuspend("t") {
                n++
                throw CancellationException("취소")
            }
        }
        assertFailsWith<CancellationException> {
            bestEffortSuspend("t") {
                n++
                throw CancellationException("취소")
            }
        }
        assertEquals(4, n, "취소는 다시 시도하지 않는다")
        assertEquals(0, pauses)
    }

    // 화면 읽기
    @Test fun retryOnDbError_resubscribesAfterFailure() = runBlocking {
        var n = 0
        val f = flow {
            if (n++ == 0) error("읽기 실패")
            emit(7)
        }
        assertEquals(7, f.retryOnDbError("t").first())
        assertEquals(2, n)
        var m = 0
        assertFailsWith<CancellationException> {
            flow<Int> {
                m++
                throw CancellationException("취소")
            }.retryOnDbError("t").first()
        }
        assertEquals(1, m, "취소는 다시 시도하지 않는다")
    }
}
