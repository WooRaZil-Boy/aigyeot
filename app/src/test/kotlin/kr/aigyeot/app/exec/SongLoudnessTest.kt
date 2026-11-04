package kr.aigyeot.app.exec

import kr.aigyeot.engine.Params
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 명세서 14장 '−30 LUFS ±1 이내로 맞춤': 이득을 곱한 결과를 다시 재어 확인한다
class SongLoudnessTest {
    private val p = Params()

    @Test fun `목표 −30 LUFS ±1 안이면 맞고 밖이거나 잴 수 없으면 아니다`() {
        assertTrue(SongLoudness.inTarget(-30.0, p))
        assertTrue(SongLoudness.inTarget(-30.9, p))
        assertTrue(SongLoudness.inTarget(-29.1, p))
        assertFalse(SongLoudness.inTarget(-31.1, p))
        assertFalse(SongLoudness.inTarget(-28.9, p))
        assertFalse(SongLoudness.inTarget(null, p))
    }

    @Test fun `문턱 아래 구간이 섞인 곡은 이득을 곱한 뒤 다시 재면 목표 밖이다`() {
        val rate = 48_000
        fun sine(dbfs: Double) = ShortArray(rate * 5) { (Math.pow(10.0, dbfs / 20) * 32767 * sin(2 * PI * 997 * it / rate)).roundToInt().toShort() }
        val pcm = sine(-66.0) + sine(-70.0)
        val before = Loudness(rate, 1).apply { addPcm16(pcm, pcm.size) }
        val lufs = before.integrated()
        assertNotNull(lufs, "조건: 입력은 잴 수 있다(−70 문턱 위 구간만 들어간다)")
        assertTrue(lufs < -60.0, "입력 $lufs LUFS")
        val (gain, _) = SongLoudness.gainFor(lufs, before.peak, p)
        assertNotNull(gain, "조건: 이득은 구해진다(정점 한계 안)")
        SongLoudness.scale(pcm, pcm.size, gain)
        val after = Loudness(rate, 1).apply { addPcm16(pcm, pcm.size) }.integrated()
        assertFalse(SongLoudness.inTarget(after, p), "곱한 뒤 $after LUFS는 −30±1 밖이어야 거절된다")
    }

    @Test fun `고르게 큰 곡은 이득을 곱한 뒤에도 목표 안이다`() {
        val rate = 48_000
        val pcm = ShortArray(rate * 5) { (0.01 * 32767 * sin(2 * PI * 997 * it / rate)).roundToInt().toShort() }
        val m = Loudness(rate, 1).apply { addPcm16(pcm, pcm.size) }
        val (gain, _) = SongLoudness.gainFor(m.integrated(), m.peak, p)
        SongLoudness.scale(pcm, pcm.size, gain!!)
        assertEquals(-30.0, Loudness(rate, 1).apply { addPcm16(pcm, pcm.size) }.integrated()!!, 1.0)
        assertTrue(SongLoudness.inTarget(Loudness(rate, 1).apply { addPcm16(pcm, pcm.size) }.integrated(), p))
    }

    @Test fun `재측정이 목표에서 벗어나면 이득을 한 번 고쳐 ±1 안에 든다`() {
        val rate = 48_000
        fun sine(dbfs: Double) = ShortArray(rate * 5) { (Math.pow(10.0, dbfs / 20) * 32767 * sin(2 * PI * 997 * it / rate)).roundToInt().toShort() }
        val src = sine(-66.0) + sine(-70.0)
        val m = Loudness(rate, 1).apply { addPcm16(src, src.size) }
        val g1 = SongLoudness.gainFor(m.integrated(), m.peak, p).first!!
        fun after(g: Double) = Loudness(rate, 1).apply { addPcm16(src.copyOf().also { SongLoudness.scale(it, it.size, g) }, src.size) }.integrated()
        val first = after(g1)
        assertFalse(SongLoudness.inTarget(first, p), "조건: 첫 이득은 목표 밖이다 $first")
        val (g2, why) = SongLoudness.refineGain(g1, first, m.peak, p)
        assertNotNull(g2, "보정 이득이 나와야 한다: $why")
        assertTrue(SongLoudness.inTarget(after(g2), p), "보정 뒤 ${after(g2)} LUFS")
        assertEquals(g1 to null, SongLoudness.refineGain(g1, -30.2, m.peak, p), "±0.5 안이면 그대로")
        assertEquals(null to "silent", SongLoudness.refineGain(g1, null, m.peak, p))
        assertEquals("peak_limit", SongLoudness.refineGain(g1, -40.0, 0.5, p).second, "고친 이득이 정점 한계를 넘으면")
    }
}
