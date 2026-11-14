package kr.aigyeot.app.exec

import java.util.SplittableRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * ITU-R BS.1770-4 통합 크기(LUFS). K 가중(고역 선반 + 고역 통과, 계수는 libebur128의 표본율 일반식),
 * 400ms 창·100ms 간격, 절대 문턱 −70 LUFS, 상대 문턱 −10 LU. 채널 가중은 모두 1.0(모노·스테레오만 받는다).
 * 정점은 표본 정점이다(실제 정점 4배 과표본은 하지 않는다).
 */
internal class Loudness(rate: Int, private val channels: Int) {
    private val f = Array(channels) { KWeight(rate) }
    private val step = rate / 10 // 100ms 표본(채널당)
    private val sub = ArrayList<Double>() // 100ms마다 채널 합 제곱 평균
    private var acc = 0.0
    private var n = 0
    private var ch = 0
    var peak = 0.0
        private set // 표본 정점(1.0 = 0 dBFS)

    /** 끼운(interleaved) 표본, −1..1. */
    fun add(x: Double) {
        if (abs(x) > peak) peak = abs(x)
        val y = f[ch].next(x)
        acc += y * y
        if (++ch == channels) {
            ch = 0
            if (++n == step) {
                sub += acc / step
                acc = 0.0
                n = 0
            }
        }
    }

    fun addPcm16(pcm: ShortArray, count: Int) {
        for (i in 0 until count) add(pcm[i] / 32768.0)
    }

    /** 통합 크기. 문턱을 넘는 창이 없으면(거의 무음·400ms 미만) null. */
    fun integrated(): Double? {
        val blocks = (0..sub.size - 4).map { i -> (sub[i] + sub[i + 1] + sub[i + 2] + sub[i + 3]) / 4 }
        val abs = blocks.filter { lufs(it) > -70.0 }
        if (abs.isEmpty()) return null
        val rel = lufs(abs.average()) - 10.0
        val gated = abs.filter { lufs(it) > rel }
        return if (gated.isEmpty()) null else lufs(gated.average())
    }

    private fun lufs(z: Double) = -0.691 + 10 * log10(z)

    private class KWeight(rate: Int) {
        private val s1: DoubleArray
        private val s2: DoubleArray
        private val z = DoubleArray(4)

        init {
            // 1단: 고역 선반(+4 dB 안팎, 1.68 kHz)
            var k = tan(PI * 1681.974450955533 / rate)
            val q1 = 0.7071752369554196
            val vh = 10.0.pow(3.999843853973347 / 20)
            val vb = vh.pow(0.4996667741545416)
            var a0 = 1 + k / q1 + k * k
            s1 = doubleArrayOf(
                (vh + vb * k / q1 + k * k) / a0,
                2 * (k * k - vh) / a0,
                (vh - vb * k / q1 + k * k) / a0,
                2 * (k * k - 1) / a0,
                (1 - k / q1 + k * k) / a0
            )
            // 2단: 고역 통과(38 Hz)
            k = tan(PI * 38.13547087602444 / rate)
            val q2 = 0.5003270373238773
            a0 = 1 + k / q2 + k * k
            s2 = doubleArrayOf(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q2 + k * k) / a0)
        }

        val stage1 get() = s1.copyOf()
        val stage2 get() = s2.copyOf()

        // 직접형 II 전치(b0 b1 b2 a1 a2)
        fun next(x: Double): Double {
            val y1 = s1[0] * x + z[0]
            z[0] = s1[1] * x - s1[3] * y1 + z[1]
            z[1] = s1[2] * x - s1[4] * y1
            val y2 = s2[0] * y1 + z[2]
            z[2] = s2[1] * y1 - s2[3] * y2 + z[3]
            z[3] = s2[2] * y1 - s2[4] * y2
            return y2
        }
    }

    companion object {
        internal fun kWeights(rate: Int) = KWeight(rate).let { it.stage1 to it.stage2 }
    }
}

/**
 * 음원 백색소음. 표본마다 새 난수를 만들어 짧은 버퍼를 되풀이하지 않는다.
 * 균등 분포 ±[AMP]는 BS.1770으로 −30 LUFS다(계산: 진폭 1.0일 때 −1.63 LUFS → 0.03815, LoudnessTest가 확인).
 */
internal class WhiteNoise(seed: Long = System.nanoTime()) {
    private val r = SplittableRandom(seed)
    fun fill(buf: ShortArray) {
        for (i in buf.indices) buf[i] = (r.nextDouble(-AMP, AMP) * 32767).toInt().toShort()
    }

    companion object {
        const val RATE = 48_000
        const val AMP = 0.03815
    }
}
