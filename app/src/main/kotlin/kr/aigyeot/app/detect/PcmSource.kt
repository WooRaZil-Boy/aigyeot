package kr.aigyeot.app.detect

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

// 16kHz 모노 s16 PCM 입력원. 한 번에 YAMNet 창 하나(15600표본)를 채워 돌려준다.
interface PcmSource {
    /** 열기. 실패하면 던진다(→ MIC_ERROR). */
    fun start()
    /** [out]을 창 하나로 채운다. 채운 표본 수, 끝이면 [END], 오류면 [ERROR]. */
    fun read(out: ShortArray): Int
    /** 시스템이 이 입력을 무음 처리했는가(isClientSilenced). 파일은 늘 false. */
    fun isSilenced(): Boolean
    /** 다른 스레드에서 불러 막혀 있는 [read]를 깨운다(read는 [ERROR]로 돌아온다). 자원은 [close]가 읽기가 끝난 뒤에 놓는다. */
    fun cancel() {}
    fun close()
    companion object { const val END = -1; const val ERROR = -2 }
}

// 창 점수기. 창(15600표본) → 521 점수. MediaPipe는 JVM에서 돌지 않으니 시험에서는 가짜로 바꾼다.
fun interface WindowScorer { fun score(window: ShortArray): FloatArray }

class MediaPipeScorer(context: Context, modelAsset: String = "yamnet.tflite") : WindowScorer, Closeable {
    private val classifier = AudioClassifier.createFromOptions(context, AudioClassifier.AudioClassifierOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAsset).build())
        .setRunningMode(RunningMode.AUDIO_CLIPS).build()) // setMaxResults 호출 금지: -1은 기기에서 IllegalArgumentException. 생략하면 521개 전부 온다(프로브와 같다)
    private val format = AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(Detect.SAMPLE_RATE.toFloat()).build()

    override fun score(window: ShortArray): FloatArray {
        val data = AudioData.create(format, window.size)
        data.load(FloatArray(window.size) { window[it] / 32768f })
        val out = FloatArray(521)
        classifier.classify(data).classificationResults().firstOrNull()?.classifications()?.firstOrNull()?.categories()
            ?.forEach { out[it.index()] = it.score() }
        return out
    }

    override fun close() = classifier.close()
}

// 녹음 파일(.pcm, 16kHz mono s16le). 마지막 창이 모자라면 0으로 채운다(MediaPipe가 파일 전체를 넣을 때와 같은 창 수).
// [realtime]이면 창마다 0.975초를 기다려 마이크처럼 흘린다(장면 재생). 아니면 최대한 빨리(측정).
class FilePcmSource(private val file: File, private val realtime: Boolean = false) : PcmSource {
    private var input: BufferedInputStream? = null
    private val bytes = ByteArray(Detect.WINDOW_SAMPLES * 2)

    override fun start() { input = BufferedInputStream(file.inputStream()) }

    override fun read(out: ShortArray): Int {
        val ins = input ?: return PcmSource.ERROR
        var n = 0
        while (n < bytes.size) { val r = ins.read(bytes, n, bytes.size - n); if (r < 0) break; n += r }
        if (n == 0) return PcmSource.END
        Arrays.fill(bytes, n, bytes.size, 0)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, Detect.WINDOW_SAMPLES)
        if (realtime) Thread.sleep(Detect.WINDOW_MS)
        return Detect.WINDOW_SAMPLES
    }

    override fun isSilenced() = false
    override fun close() { input?.close(); input = null }
}

// 차 마이크(AudioRecord). 무음 처리는 registerAudioRecordingCallback으로 본다.
class MicPcmSource(context: Context) : PcmSource {
    private var rec: AudioRecord? = null
    @Volatile private var silenced = false
    @Volatile private var cancelled = false
    private val am = context.getSystemService(AudioManager::class.java)
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = update(configs)
    }

    private fun update(configs: List<AudioRecordingConfiguration>) {
        val id = rec?.audioSessionId ?: return
        if (Build.VERSION.SDK_INT >= 29) silenced = configs.firstOrNull { it.clientAudioSessionId == id }?.isClientSilenced == true
    }

    override fun start() {
        val size = maxOf(AudioRecord.getMinBufferSize(Detect.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            Detect.WINDOW_SAMPLES * 4)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, Detect.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); error("AudioRecord 초기화 실패") } // rec에 넣기 전이라 close가 못 놓는다
        rec = r
        am.registerAudioRecordingCallback(callback, null)
        r.startRecording()
        update(am.activeRecordingConfigurations)
    }

    override fun read(out: ShortArray): Int {
        val r = rec ?: return PcmSource.ERROR
        var n = 0
        while (n < Detect.WINDOW_SAMPLES) {
            if (cancelled) return PcmSource.ERROR
            val got = r.read(out, n, Detect.WINDOW_SAMPLES - n)
            if (got < 0) return PcmSource.ERROR
            n += got // 0이면 다시 읽는다. 취소됐으면 위에서 끝낸다
        }
        return n
    }

    override fun isSilenced() = silenced

    // AudioRecord.stop()이 막힌 read를 깨운다. release는 읽기가 끝난 뒤 close에서
    override fun cancel() { cancelled = true; runCatching { rec?.stop() } }

    override fun close() {
        runCatching { am.unregisterAudioRecordingCallback(callback) }
        rec?.let { runCatching { it.stop() }; it.release() }
        rec = null
    }
}
