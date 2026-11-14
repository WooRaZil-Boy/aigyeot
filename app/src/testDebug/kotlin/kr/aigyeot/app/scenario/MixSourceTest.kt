package kr.aigyeot.app.scenario

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.abs
import kr.aigyeot.app.detect.Detect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixSourceTest {
    private var now = 1_000L
    private val slept = ArrayList<Long>()
    private val mix = MixSource({ now }, {
        slept += it
        now += it
    })
    private val w = ShortArray(Detect.WINDOW_SAMPLES)

    @Test fun `녹음이 없으면 0이 아닌 아주 작은 바닥 소리`() {
        mix.start()
        repeat(5) {
            assertEquals(Detect.WINDOW_SAMPLES, mix.read(w))
            assertTrue(w.all { abs(it.toInt()) <= 8 })
            var run = 0
            var longest = 0
            w.forEach {
                run = if (it.toInt() == 0) run + 1 else 0
                longest = maxOf(longest, run)
            }
            assertTrue("0 연속 $longest", longest < 100)
        }
    }

    @Test fun `창은 그 창이 끝나는 시각에 나온다(마이크 속도)`() {
        mix.start()
        mix.read(w)
        mix.read(w)
        assertEquals(listOf(975L, 975L), slept)
        now += 5_000 // 늦게 읽으면 기다리지 않고 따라잡는다
        mix.read(w)
        assertEquals(2, slept.size)
    }

    @Test fun `녹음은 play가 불린 시각의 표본 자리에 들어가고 durSec 동안 되풀이된다`() {
        mix.start()
        now += 500 // 첫 창 500ms 지점
        mix.play(ShortArray(1_600) { 1_000 }, durSec = 0.3, gainDb = 0.0)
        mix.read(w)
        val start = 500 * 16
        assertTrue(w.take(start).all { abs(it.toInt()) <= 8 })
        assertTrue(w.slice(start until start + 4_800).all { it in 992..1_007 }) // 0.1초 녹음을 0.3초 동안 되풀이
        assertTrue(w.drop(start + 4_800).all { abs(it.toInt()) <= 8 })
    }

    @Test fun `gainDb와 넘침 자르기`() {
        mix.start()
        mix.play(ShortArray(16_000) { 20_000 }, 1.0, -6.0206)
        mix.play(ShortArray(16_000) { 30_000 }, 1.0, 0.0)
        mix.read(w)
        assertEquals(Short.MAX_VALUE, w[100]) // 10,000 + 30,000 → 32,767
        val m = MixSource({ now }, { now += it }).apply { start() }
        m.play(ShortArray(16_000) { 20_000 }, 1.0, -6.0206)
        m.read(w)
        assertTrue(w[100] in 9_992..10_008)
    }

    @Test fun `load는 요청한 길이만 읽는다`() {
        var read = 0L
        val src = object : InputStream() {
            val inner = ByteArrayInputStream(ByteArray(60 * 32_000))
            override fun read() = inner.read().also { if (it >= 0) read++ }
            override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len).also { if (it > 0) read += it }
        }
        val pcm = MixSource.load(src, 0.0, 1.0)
        assertEquals(16_000, pcm.size)
        assertTrue("읽은 바이트 $read", read <= 32_000)
    }

    @Test fun `짧은 파일에 긴 durSec를 요청하면 있는 만큼만 돌려준다`() {
        assertEquals(16_000, MixSource.load(ByteArrayInputStream(ByteArray(32_000)), 0.0, 240.0).size)
    }

    @Test fun `load는 fromSec부터 durSec만큼(파일 끝까지) 읽는다`() {
        val bytes = ByteArray(32_000 * 3) { i -> if (i % 2 == 0) (i / 32_000).toByte() else 0 } // 초마다 값 0,1,2
        val pcm = MixSource.load(ByteArrayInputStream(bytes), 1.0, 5.0)
        assertEquals(32_000, pcm.size)
        assertEquals(1, pcm.first().toInt())
        assertEquals(2, pcm.last().toInt())
    }
}
