package kr.aigyeot.app.exec

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 음원 크기 −30 LUFS ±1, 볼륨 상한(사전 측정). BS.1770-4 측정기와 백색소음 크기
class LoudnessTest {
    @Test fun `48kHz K 가중 계수가 BS_1770 표와 같다`() {
        val (s1, s2) = Loudness.kWeights(48_000)
        val want1 = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585)
        val want2 = doubleArrayOf(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621)
        want1.indices.forEach { assertEquals(want1[it], s1[it], 1e-9) }
        want2.indices.forEach { assertEquals(want2[it], s2[it], 1e-9) }
    }

    @Test fun `997Hz 사인은 진폭 dBFS에서 3_01을 뺀 LUFS다`() {
        for (rate in listOf(44_100, 48_000)) {
            val l = Loudness(rate, 1)
            val a = 0.1                                           // −20 dBFS 정점
            repeat(rate * 5) { l.add(a * sin(2 * PI * 997 * it / rate)) }
            assertEquals(20 * log10(a) - 3.01, l.integrated()!!, 0.1, "rate=$rate")
        }
    }

    @Test fun `스테레오는 두 채널 에너지를 더한다`() {
        val l = Loudness(48_000, 2)
        repeat(48_000 * 3) { val x = 0.1 * sin(2 * PI * 997 * it / 48_000); l.add(x); l.add(x) }
        assertEquals(20 * log10(0.1) - 3.01 + 3.01, l.integrated()!!, 0.1)
    }

    @Test fun `거의 무음이거나 400ms보다 짧으면 잴 수 없다`() {
        val quiet = Loudness(48_000, 1).apply { repeat(48_000 * 3) { add(1e-5 * sin(it * 0.1)) } }
        assertNull(quiet.integrated(), "−70 LUFS 아래")
        val short = Loudness(48_000, 1).apply { repeat(48_000 / 4) { add(0.1 * sin(it * 0.1)) } }
        assertNull(short.integrated(), "창 하나도 못 채움")
    }

    @Test fun `상대 문턱은 조용한 구간을 뺀다`() {
        val l = Loudness(48_000, 1)
        repeat(48_000 * 5) { l.add(0.1 * sin(2 * PI * 997 * it / 48_000)) }
        repeat(48_000 * 5) { l.add(0.001 * sin(2 * PI * 997 * it / 48_000)) }    // −40 dB 더 작음: 상대 문턱(−10 LU) 아래
        assertEquals(20 * log10(0.1) - 3.01, l.integrated()!!, 0.2)
    }

    @Test fun `앱이 만드는 백색소음은 −30 LUFS ±0_5다`() {
        val n = WhiteNoise(seed = 7)
        val l = Loudness(WhiteNoise.RATE, 1)
        val buf = ShortArray(4_800)
        repeat(100) { n.fill(buf); l.addPcm16(buf, buf.size) }              // 10초
        val got = l.integrated()!!
        assertTrue(abs(got - -30.0) <= 0.5, "백색소음 $got LUFS")
    }

    @Test fun `백색소음은 짧은 버퍼를 되풀이하지 않는다`() {
        val n = WhiteNoise(seed = 1)
        val a = ShortArray(4_800).also(n::fill)
        val b = ShortArray(4_800).also(n::fill)
        assertTrue(!a.contentEquals(b), "연속한 두 버퍼가 같으면 되풀이다")
        // 한 버퍼 안에서도 앞 절반과 뒤 절반이 같지 않다(주기 없음)
        assertTrue(!a.copyOfRange(0, 2_400).contentEquals(a.copyOfRange(2_400, 4_800)))
    }
}
