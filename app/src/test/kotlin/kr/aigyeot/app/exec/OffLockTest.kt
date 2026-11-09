package kr.aigyeot.app.exec

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 잠금 안에서 바인더를 부르지 않는다: VHAL이 죽어 CarService가 다시 붙을 때 연결 콜백이 막은 ANR(차 에뮬레이터 실측)의 구조 시험. PleosCar 어댑터는 JVM 밖이라 그 안의 규칙인 OffLock을 본다
class OffLockTest {
    private val lock = Any()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "binder-lane").apply { isDaemon = true } }
    private val off = OffLock(lock) { pool.execute(it) }

    @Test fun `막힌 바인더 읽기가 있어도 잠금을 쥔 호출자는 바로 돌아오고 다른 스레드가 잠금을 바로 얻는다`() {
        val blocked = CountDownLatch(1)           // 가짜 get이 바인더에서 막힌 상태
        val entered = CountDownLatch(1)
        val got = AtomicReference<Int?>()
        // 연결 콜백이 하는 일: 잠금 안에서 읽기를 맡기고 돌아온다
        val returned = CountDownLatch(1)
        Thread {
            synchronized(lock) {
                off.read({ entered.countDown(); blocked.await(); 3 }) { got.set(it) }
                returned.countDown()
            }
        }.start()
        assertTrue(returned.await(2, TimeUnit.SECONDS), "호출자가 바인더 읽기를 기다리며 서 있다")
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        // 바인더가 막힌 동안 host·binder 스레드가 잠금을 기다리지 않는다
        val other = CountDownLatch(1)
        Thread { synchronized(lock) { other.countDown() } }.start()
        assertTrue(other.await(2, TimeUnit.SECONDS), "잠금이 바인더 호출 동안 잡혀 있다")
        blocked.countDown()
        pool.shutdown(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(3, got.get())
    }

    @Test fun `결과는 잠금 안에서 돌려받고 바인더가 던지면 null`() {
        val inLock = AtomicReference<Boolean>()
        val v = AtomicReference<Int?>(-1)
        off.read<Int>({ error("VHAL 죽음") }) { v.set(it); inLock.set(Thread.holdsLock(lock)) }
        pool.shutdown(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        assertNull(v.get()); assertEquals(true, inLock.get())
    }

    @Test fun `던지는 일이 레인을 끊지 않는다`() {
        val after = CountDownLatch(1)
        off.run { error("실패") }
        off.run { after.countDown() }
        assertTrue(after.await(2, TimeUnit.SECONDS))
    }
}
