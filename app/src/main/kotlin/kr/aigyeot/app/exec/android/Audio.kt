package kr.aigyeot.app.exec.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import kr.aigyeot.app.common.CarPaths
import kr.aigyeot.app.exec.AudioPort
import kr.aigyeot.app.exec.REAR_ZONE
import kr.aigyeot.app.exec.Track
import kr.aigyeot.engine.FocusChange
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind

// ---- 재생 ----

internal class AndroidAudio(private val ctx: Context, private val ex: ExecThread) : AudioPort {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var focus: AudioFocusRequest? = null

    private fun rear(): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.address == REAR_ZONE }
    override fun hasRearOutput() = rear() != null

    override fun requestFocus(onChange: (FocusChange) -> Unit): Boolean {
        abandonFocus()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)   // 달래기는 일시 재생이라 영구 GAIN으로 앞좌석 앱을 멈춰 세우지 않는다
            .setAudioAttributes(media)
            .setOnAudioFocusChangeListener({ c -> ex.guard { focusOf(c)?.let(onChange) } }, ex.handler).build()
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = req
        return true
    }

    override fun abandonFocus() { focus?.let { am.abandonAudioFocusRequest(it) }; focus = null }

    override fun open(method: Method, rear: Boolean, onRouted: (String?) -> Unit, onError: () -> Unit): Track? = try {
        val dev = if (rear) rear() else null
        when (method.kind) {
            MethodKind.WHITE_NOISE -> NoiseTrack(media, dev, ex, onRouted, onError)
            MethodKind.WOMB, MethodKind.LULLABY -> ctx.assets.openFd(if (method.kind == MethodKind.WOMB) WOMB_ASSET else LULLABY_ASSET).use { fd ->
                FileTrack(media, dev, ex, onRouted, onError) { it.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            }
            MethodKind.SONG -> {
                val f = CarPaths.songFile(ctx, method.songId ?: error("songId 없음"))
                check(f.isFile) { "노래 파일 없음 ${f.name}" }
                FileTrack(media, dev, ex, onRouted, onError) { it.setDataSource(f.path) }
            }
        }
    } catch (e: Exception) { Log.w(TAG, "open $method", e); null }

    private fun focusOf(c: Int) = when (c) {
        AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOSS
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LOSS_TRANSIENT
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.LOSS_TRANSIENT_CAN_DUCK
        AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAIN
        else -> null
    }

    companion object {
        /**
         * 음원 녹음 자장가(직접 렌더링): 브람스 「자장가」 Op.49 No.4(퍼블릭 도메인), MIDI Rabanus Flavus(Wikimedia Commons, CC0,
         * 'Guten Abend, gut Nacht (Wiegenlied).mid')의 네 성부를 GM 10번 오르골로 바꿔 FluidSynth 2.6.2와 FluidR3_GM(Frank Wen, MIT,
         * Debian fluid-soundfont-gm 3.1-6)으로 44.1kHz 렌더링 → 모노 → −5.0 dB → AAC-LC 64kbps. ffmpeg ebur128로 −30.0 LUFS, 정점 −12.5 dBFS, 65.8초.
         * 없으면 PlaybackRejected(ERROR).
         */
        const val LULLABY_ASSET = "sound/lullaby.m4a"

        /**
         * 음원 뱃속 소리: 외부 음원 없이 직접 합성(`tools/sound/womb.py`). 60초 이음새 없는 반복, AAC 64kbps 모노 44.1kHz,
         * −30.0 LUFS, 정점 −13.2 dBFS. 자장가와 같은 FileTrack으로 반복 재생한다. 없으면 PlaybackRejected(ERROR).
         */
        const val WOMB_ASSET = "sound/womb.m4a"
    }
}
