package kr.aigyeot.app.scenario

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import kr.aigyeot.app.common.CarPaths
import kr.aigyeot.app.exec.android.AndroidAudio
import kr.aigyeot.app.exec.Loudness
import kr.aigyeot.app.exec.WhiteNoise
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import java.nio.ByteOrder

/** 음원 ① 백색소음을 exec 생성기로 [sec]초 만들어 exec [Loudness](BS.1770)로 잰다. */
fun whiteNoiseLufs(sec: Int = 10): Double? {
    val m = Loudness(WhiteNoise.RATE, 1)
    val noise = WhiteNoise(seed = 1)
    val buf = ShortArray(WhiteNoise.RATE)
    repeat(sec) { noise.fill(buf); m.addPcm16(buf, buf.size) }
    return m.integrated()
}

/** 장면에서 쓴 음원의 통합 크기 캐시(계약 7.4 lufs_violations). 잰 값만 남기고, 못 잰 것(null)은 다음에 다시 잰다. */
internal class SourceLoudness(private val measure: (Method) -> Double?) {
    private val cache = HashMap<Method, Double>()

    @Synchronized fun lufs(m: Method): Double? = cache[m] ?: measure(m)?.also { cache[m] = it }
}

/**
 * 음원 하나를 exec [Loudness](BS.1770)로 잰다. 음원은 exec가 트는 것과 같은 곳에서 연다(자장가 assets, 부모 노래 CarPaths.songFile). 못 재면 null.
 */
internal fun androidLufs(ctx: Context, m: Method): Double? = try {
    when (m.kind) {
        MethodKind.WHITE_NOISE -> whiteNoiseLufs()
        MethodKind.WOMB, MethodKind.LULLABY -> ctx.assets.openFd(if (m.kind == MethodKind.WOMB) AndroidAudio.WOMB_ASSET else AndroidAudio.LULLABY_ASSET).use { fd -> decodeLufs { it.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) } }
        MethodKind.SONG -> m.songId?.let { CarPaths.songFile(ctx, it) }?.takeIf { it.isFile }?.let { f -> decodeLufs { it.setDataSource(f.path) } }
    }
} catch (e: Exception) { Log.w("SCENARIO", "음원 크기를 재지 못함 $m", e); null }

// 한계: exec SongLoudness의 private decode와 같은 일을 한 번 더 한다. exec가 `SongLoudness.measure(File)`을 열면 이 함수를 지운다
/** m4a를 풀어 16비트 PCM을 [Loudness]에 넣는다. 30초 안에 끝나지 않으면 예외. */
private fun decodeLufs(source: (MediaExtractor) -> Unit): Double? {
    val ex = MediaExtractor()
    try {
        source(ex)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val until = SystemClock.elapsedRealtime() + 30_000
            var meter: Loudness? = null
            var inDone = false
            while (true) {
                check(SystemClock.elapsedRealtime() < until) { "decoder stalled" }
                if (!inDone) codec.dequeueInputBuffer(10_000).takeIf { it >= 0 }?.let { i ->
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o < 0) continue
                val m = meter ?: codec.outputFormat.let { Loudness(it.getInteger(MediaFormat.KEY_SAMPLE_RATE), it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) }.also { meter = it }
                val b = codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val pcm = ShortArray(b.remaining()).also { b.get(it) }
                codec.releaseOutputBuffer(o, false)
                m.addPcm16(pcm, pcm.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return m.integrated()
            }
        } finally { runCatching { codec.stop() }; codec.release() }
    } finally { ex.release() }
}
