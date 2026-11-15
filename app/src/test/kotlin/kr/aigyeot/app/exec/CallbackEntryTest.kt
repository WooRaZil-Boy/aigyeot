package kr.aigyeot.app.exec

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// SDK·시스템 콜백이 호출 스레드(main일 수 있다)에서 실행기 잠금을 잡지 않는다: 다른 스레드가 잠금 안에서 바인더에 막혀도 main 콜백이 같이 서지 않는다
class CallbackEntryTest {
    private val lock = Any()
    private val lane = Executors.newSingleThreadExecutor { r -> Thread(r, "exec-lane").apply { isDaemon = true } }
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private val errors = Collections.synchronizedList(mutableListOf<String>())
    private val entry = CallbackEntry(lock, { lane.execute(it) }) { errors += it.message.orEmpty() }

    @Test fun `잠금을 다른 스레드가 쥐고 있어도 콜백 스레드는 바로 돌아오고 처리는 실행기 스레드에서 받은 순서대로 된다`() {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        Thread {
            synchronized(lock) {
                holding.countDown()
                release.await()
            }
        }.start() // Exec.handle이 잠금 안에서 바인더에 막힌 상태
        assertTrue(holding.await(30, TimeUnit.SECONDS))
        val returned = CountDownLatch(1)
        Thread {
            // main 콜백
            (1..5).forEach { i -> entry.enter { seen += "$i@${Thread.currentThread().name}" } }
            returned.countDown()
        }.start()
        assertTrue(returned.await(30, TimeUnit.SECONDS), "콜백 스레드가 잠금을 기다리며 서 있다")
        assertTrue(seen.isEmpty(), "잠금이 풀리기 전에는 처리하지 않는다")
        release.countDown()
        lane.shutdown()
        assertTrue(lane.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals((1..5).map { "$it@exec-lane" }, seen.toList())
    }

    @Test fun `처리 중 던져도 다음 콜백을 이어 처리하고 오류를 알린다`() {
        entry.enter { error("첫 번째 실패") }
        entry.enter { seen += "next" }
        lane.shutdown()
        assertTrue(lane.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(listOf("next"), seen.toList())
        assertEquals(listOf("첫 번째 실패"), errors.toList())
    }

    @Test fun `처리는 잠금 안에서 한다`() {
        entry.enter { seen += Thread.holdsLock(lock).toString() }
        lane.shutdown()
        assertTrue(lane.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(listOf("true"), seen.toList())
    }
}
