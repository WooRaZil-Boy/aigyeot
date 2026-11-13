package kr.aigyeot.app.scenario

import kr.aigyeot.app.detect.Detect
import kr.aigyeot.app.detect.PcmSource
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow

/**
 * 장면 입력원: 마이크 자리에 녹음을 섞는다(계약 7.3 audio). 마이크처럼 창 하나(0.975초)를 그 창이 끝나는 시각에 내고,
 * 녹음은 [play]가 불린 시각의 표본 자리에 들어간다(창 경계로 밀리지 않아 경보 지연을 덜 왜곡한다).
 * 녹음이 없을 때도 0이 아닌 아주 작은 바닥 소리(±8, 약 −72 dBFS)를 낸다: 표본이 3초 내리 0이면 감지가 '무음 처리'로
 * 보고 판정을 멈춘다(C68). 시간은 [nowMs]의 단조 시계로 잰다.
 */
class MixSource(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = Thread::sleep,
) : PcmSource {
    private class Clip(val pcm: ShortArray, val startMs: Long, val samples: Long, val gain: Double)
    private val clips = CopyOnWriteArrayList<Clip>()
    @Volatile private var cancelled = false
    private var origin = 0L
    private var k = 0L
    private var noise = 0x2545F491

    /** [pcm]을 지금부터 [durSec] 동안 넣는다. 녹음이 더 짧으면 처음부터 되풀이한다. */
    fun play(pcm: ShortArray, durSec: Double, gainDb: Double) {
        require(pcm.isNotEmpty()) { "빈 녹음" }
        clips += Clip(pcm, nowMs(), Math.round(durSec * Detect.SAMPLE_RATE), 10.0.pow(gainDb / 20))
    }

    override fun start() { origin = nowMs(); k = 0; cancelled = false }

    override fun read(out: ShortArray): Int {
        val wStart = origin + k * Detect.WINDOW_MS
        val wait = wStart + Detect.WINDOW_MS - nowMs()
        if (wait > 0) sleep(wait)   // 멈추면 러너가 스레드를 깨운다(InterruptedException)
        if (cancelled) return PcmSource.ERROR
        val acc = DoubleArray(Detect.WINDOW_SAMPLES) { nextNoise().toDouble() }
        for (c in clips) {
            val off = (wStart - c.startMs) * Detect.SAMPLE_RATE / 1000   // 창 첫 표본이 녹음의 몇 번째 표본인지
            for (i in acc.indices) {
                val s = off + i
                if (s in 0 until c.samples) acc[i] += c.pcm[(s % c.pcm.size).toInt()] * c.gain
            }
        }
        val wEnd = wStart + Detect.WINDOW_MS
        clips.removeIf { it.startMs + it.samples * 1000 / Detect.SAMPLE_RATE <= wEnd }
        for (i in acc.indices) out[i] = acc[i].toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        k++
        return Detect.WINDOW_SAMPLES
    }

    private fun nextNoise(): Int {
        noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
        return (noise and 15) - 8   // −8..7: 0이 연속 3초 나올 수 없다
    }

    override fun isSilenced() = false
    override fun cancel() { cancelled = true }
    override fun close() { clips.clear() }

    companion object {
        /** 16 kHz 모노 s16le에서 [fromSec]부터 [durSec]만큼(파일 끝까지) 읽는다. */
        fun load(input: InputStream, fromSec: Double, durSec: Double): ShortArray = input.use { ins ->
            val skip = Math.round(fromSec * Detect.SAMPLE_RATE) * 2
            var left = skip
            while (left > 0) { val n = ins.skip(left); if (n <= 0) break; left -= n }
            val want = Math.round(durSec * Detect.SAMPLE_RATE).toInt() * 2
            // 64KB 조각으로 want까지만 읽는다: 읽는 양 ≤ want, 잡는 양 ≈ 실제 읽은 양(짧은 파일에 큰 durSec여도). readNBytes는 minSdk 28에 없다
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (out.size() < want) { val n = ins.read(chunk, 0, minOf(chunk.size, want - out.size())); if (n < 0) break; out.write(chunk, 0, n) }
            val bytes = out.toByteArray()
            ShortArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 0xff) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
        }
    }
}
