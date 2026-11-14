package kr.aigyeot.app.exec

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.aigyeot.engine.Params
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * exec가 채우고 link가 부른다('노래 받기 작업'의 '−30 LUFS 사본(실행기)').
 * 방법: m4a를 풀어 BS.1770 통합 크기와 표본 정점을 잰다 → 이득 = 10^((−30 − 크기)/20) → 맞춘 정점이 −1 dBFS를 넘으면 peak_limit
 * → 같은 표본율·채널의 AAC-LC m4a로 다시 쓴다(파일 이름 약속 CarPaths.songFile 그대로). 이득이 ±0.5 dB 안이면 다시 쓰지 않고 복사한다.
 * 이득을 곱한 PCM을 다시 재어 −30 LUFS ±[Params.lufsTolerance] 밖이면 사본을 거절한다(문턱 아래였던 구간이 이득으로 들어오는 경우).
 */
object SongLoudness {
    /** 받은 원본을 −30 LUFS 사본으로 만든다. 거절이면 reason에 song_status 사유(peak_limit·silent·bad_format). */
    suspend fun makeCopy(input: File, output: File): LoudnessResult = withContext(Dispatchers.IO) {
        val (meter, fmt) = try { measure(input) } catch (e: Exception) { return@withContext LoudnessResult(false, "bad_format") }
        val p = Params()
        var (gain, reason) = gainFor(meter.integrated(), meter.peak, p)
        if (gain == null) return@withContext LoudnessResult(false, reason)
        try {
            output.parentFile?.mkdirs()
            val copy = abs(20 * log10(gain)) < 0.5
            if (!copy) {
                // 이득을 곱한 PCM을 인코딩 없이 먼저 재어, 목표에서 0.5 dB 넘게 벗어나면 이득을 한 번 고친다(문턱 아래였던 구간이 들어오는 경우)
                val (g2, why) = refineGain(gain, measureScaled(input, gain, fmt), meter.peak, p)
                if (g2 == null) return@withContext LoudnessResult(false, why)
                gain = g2
            }
            val ok = if (copy) { input.copyTo(output, overwrite = true); true }
                     else transcode(input, output, gain, fmt, p)
            if (!ok) { output.delete(); return@withContext LoudnessResult(false, "silent") }
            LoudnessResult(true)
        } catch (e: Exception) {
            output.delete()
            LoudnessResult(false, "bad_format")
        }
    }

    /** 맞출 이득. 잴 수 없으면 silent, 맞춘 정점이 songPeakMaxDbfs를 넘으면 peak_limit. */
    internal fun gainFor(lufs: Double?, peak: Double, p: Params): Pair<Double?, String?> {
        if (lufs == null || peak <= 0.0) return null to "silent"
        val g = 10.0.pow((p.targetLufs - lufs) / 20)
        if (peak * g > 10.0.pow(p.songPeakMaxDbfs / 20)) return null to "peak_limit"
        return g to null
    }

    /**
     * 이득 [g]를 곱한 결과를 잰 값 [after]가 목표에서 0.5 dB 안이면 그대로, 밖이면 g × 10^((목표 − after)/20)로 한 번 고치고 정점 조건을 다시 본다.
     * 잴 수 없으면 silent, 고친 이득이 정점 한계를 넘으면 peak_limit.
     */
    internal fun refineGain(g: Double, after: Double?, peak: Double, p: Params): Pair<Double?, String?> {
        if (after == null) return null to "silent"
        if (abs(after - p.targetLufs) <= 0.5) return g to null
        val g2 = g * 10.0.pow((p.targetLufs - after) / 20)
        if (peak * g2 > 10.0.pow(p.songPeakMaxDbfs / 20)) return null to "peak_limit"
        return g2 to null
    }

    /** 맞춘 결과를 다시 잰 값이 목표 −30 LUFS ±허용 안인가. 잴 수 없으면(null) 아니다. */
    internal fun inTarget(lufs: Double?, p: Params) = lufs != null && abs(lufs - p.targetLufs) <= p.lufsTolerance

    /** 표본에 이득을 곱해 16비트로 되돌린다(gainFor가 정점을 막으므로 잘림은 반올림 끝자리뿐). */
    internal fun scale(pcm: ShortArray, n: Int, gain: Double) {
        for (i in 0 until n) pcm[i] = (pcm[i] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
    }

    private class Fmt(val rate: Int, val channels: Int)

    /** 파일 하나의 통합 크기(BS.1770, LUFS). 잴 수 없으면 null. 장면 측정(SourceLoudness)도 이것을 쓴다. */
    fun lufs(source: (MediaExtractor) -> Unit): Double? {
        var meter: Loudness? = null
        decode(source, onFormat = { meter = Loudness(it.rate, it.channels) }) { s, n -> meter!!.addPcm16(s, n) }
        return meter?.integrated()
    }

    private fun decode(input: File, onFormat: (Fmt) -> Unit = {}, onPcm: (ShortArray, Int) -> Unit = { _, _ -> }): Fmt =
        decode({ it.setDataSource(input.path) }, onFormat, onPcm)

    /**
     * 풀어서 16비트 PCM을 [onPcm]에 넘긴다(디코더는 이것 하나: 부모 노래 사본과 장면 음원 측정이 같이 쓴다). [source]가 MediaExtractor에 자료를 건다(파일·asset).
     * 출력 형식이 정해지면 [onFormat]. PCM 16비트가 아니면 예외. 연속 [MAX_IDLE]번 진전이 없으면 예외.
     */
    private fun decode(source: (MediaExtractor) -> Unit, onFormat: (Fmt) -> Unit = {}, onPcm: (ShortArray, Int) -> Unit = { _, _ -> }): Fmt {
        val ex = MediaExtractor()
        try {
            source(ex)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            ex.selectTrack(track)
            val inFmt = ex.getTrackFormat(track)
            var fmt = Fmt(inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val codec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            try {
                var told = false
                codec.configure(inFmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inDone = false
                var idle = 0
                while (true) {
                    var moved = false
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            moved = true
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        moved = true
                        val f = codec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            require(f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                        fmt = Fmt(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    } else if (o >= 0) {
                        moved = true
                        if (!told) { told = true; onFormat(fmt) }
                        val b = codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
                        val s = ShortArray(info.size / 2).also { b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
                        codec.releaseOutputBuffer(o, false)
                        if (s.isNotEmpty()) onPcm(s, s.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    idle = if (moved) 0 else idle + 1
                    check(idle <= MAX_IDLE) { "decoder stalled" }
                }
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
            return fmt
        } finally {
            ex.release()
        }
    }

    private fun measure(input: File): Pair<Loudness, Fmt> {
        var meter: Loudness? = null
        val fmt = decode(input, onFormat = { meter = Loudness(it.rate, it.channels) }) { s, n -> meter!!.addPcm16(s, n) }
        return requireNotNull(meter) to fmt
    }

    /** 이득을 곱해 AAC로 다시 쓰고, 곱한 PCM을 다시 재어 목표 안이면 true. 목표 밖이거나 실패면 `.tmp`를 지운다(실패는 예외). */
    /** 풀어서 이득을 곱한 PCM의 통합 크기(인코딩 없음). 잴 수 없으면 null. */
    private fun measureScaled(input: File, gain: Double, fmt: Fmt): Double? {
        var meter: Loudness? = null
        decode(input, onFormat = { meter = Loudness(it.rate, it.channels) }) { s, n -> scale(s, n, gain); meter!!.addPcm16(s, n) }
        return meter?.integrated()
    }

    private fun transcode(input: File, output: File, gain: Double, fmt: Fmt, p: Params): Boolean {
        val tmp = File(output.path + ".tmp")
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        var ok = false
        val enc = MediaCodec.createEncoderByType(mime)
        try {
            val after = Loudness(fmt.rate, fmt.channels)          // 이득을 곱한 결과를 다시 잰다
            val mux = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                var track = -1
                var frames = 0L                                       // 채널당 표본 수(시각 계산)
                var eos = false                                       // 인코더 끝 신호를 받았다(drain·feed가 공유)
                val info = MediaCodec.BufferInfo()
                fun drain(untilEnd: Boolean) {
                    if (eos) return
                    var tries = 0
                    while (tries++ < 500) {                          // 끝까지 기다려도 최대 5초
                        val o = enc.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
                        when {
                            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(enc.outputFormat); mux.start() }
                            o >= 0 -> {
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0)
                                    mux.writeSampleData(track, enc.getOutputBuffer(o)!!, info)
                                enc.releaseOutputBuffer(o, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; return }
                            }
                            !untilEnd -> return                       // INFO_TRY_AGAIN_LATER
                        }
                    }
                    check(!untilEnd) { "encoder eos timeout" }        // 끝 신호 없이 시간이 지나면 성공이 아니다
                }
                fun feed(s: ShortArray, n: Int, end: Boolean) {
                    var off = 0
                    var idle = 0
                    while (off < n || end) {
                        val i = enc.dequeueInputBuffer(10_000)
                        if (i < 0) { drain(false); check(++idle <= MAX_IDLE) { "encoder input stalled" }; continue }
                        idle = 0
                        val buf = enc.getInputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN)
                        val k = minOf(n - off, buf.remaining() / 2)
                        buf.asShortBuffer().put(s, off, k)
                        val pts = frames * 1_000_000L / fmt.rate
                        frames += k / fmt.channels
                        off += k
                        val last = end && off >= n
                        enc.queueInputBuffer(i, 0, k * 2, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        drain(false)
                        if (last) return
                    }
                }
                val f = MediaFormat.createAudioFormat(mime, fmt.rate, fmt.channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 64_000)                 // 모노 64 kbps 안팎
                }
                enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                decode(input) { s, n -> scale(s, n, gain); after.addPcm16(s, n); feed(s, n, false) }
                feed(ShortArray(0), 0, true)
                drain(true)
                mux.stop()
            } finally {
                runCatching { mux.release() }
            }
            if (!inTarget(after.integrated(), p)) return false
            check(tmp.renameTo(output)) { "rename" }
            ok = true
            return true
        } finally {
            runCatching { enc.stop() }; enc.release()
            if (!ok) tmp.delete()
        }
    }

    private const val MAX_IDLE = 500                              // 연속 이만큼 진전이 없으면 멈춘 것으로 본다(약 5~10초)
}

data class LoudnessResult(val ok: Boolean, val reason: String? = null)
