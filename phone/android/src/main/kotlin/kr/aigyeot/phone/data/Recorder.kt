package kr.aigyeot.phone.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 우리 노래 녹음(구현 계획 4장): MPEG_4 + AAC, 44.1 kHz 모노 64 kbps, 3분에서 멈춘다(명세서 12장).
 * 파일 크기 상한은 중계의 2,000,000바이트보다 조금 작게 둔다(G30).
 */
class Recorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null

    /** 녹음을 시작한다. 마이크를 다른 앱이 쓰는 등으로 실패하면 녹음기를 해제하고 거짓. */
    fun start(file: File, onLimit: () -> Unit): Boolean {
        stopPlaying()
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            configure(r, file, onLimit)
            r.prepare()
            r.start()
            recorder = r
            true
        } catch (e: Exception) { // IllegalStateException, IOException, RuntimeException
            r.release()
            false
        }
    }

    private fun configure(r: MediaRecorder, file: File, onLimit: () -> Unit) {
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44_100)
        r.setAudioChannels(1)
        r.setAudioEncodingBitRate(64_000)
        r.setMaxDuration(MAX_MS)
        r.setMaxFileSize(1_950_000)
        r.setOutputFile(file.path)
        r.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
            ) {
                stop()
                onLimit()
            }
        }
    }

    /** 멈추고 파일이 쓸 만하면 참. 너무 짧으면 MediaRecorder가 예외를 던진다. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false
        } finally {
            r.release()
        }
    }

    /** 재생을 시작했으면 참. 파일이 없거나 읽지 못하면 거짓(앱을 끝내지 않는다). */
    fun play(file: File, onDone: () -> Unit): Boolean {
        stopPlaying()
        val p = MediaPlayer()
        return try {
            p.setDataSource(file.path)
            p.setOnCompletionListener {
                stopPlaying()
                onDone()
            }
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Exception) {
            p.release()
            false
        }
    }

    fun stopPlaying() {
        player?.release()
        player = null
    }

    companion object {
        /** 화면 최대는 3:00이지만 AAC 프레임 경계로 파일이 조금 길어질 수 있어 1초 여유를 두고 멈춘다(iOS recordLimit=179와 같다). 차의 180초 제한을 넘기지 않기 위함. */
        const val MAX_MS = 179_000

        /** 길이(초). 읽지 못하면 null. */
        fun durationS(file: File): Double? {
            val m = MediaMetadataRetriever() // API 29 전에는 AutoCloseable이 아니다
            return try {
                m.setDataSource(file.path)
                m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?.div(1000.0)
            } catch (e: RuntimeException) {
                null
            } finally {
                m.release()
            }
        }
    }
}
