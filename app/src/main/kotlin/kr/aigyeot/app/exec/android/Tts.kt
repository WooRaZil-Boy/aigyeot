package kr.aigyeot.app.exec.android

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import kr.aigyeot.app.exec.TtsGate
import kr.aigyeot.app.exec.TtsPort
import java.util.Locale
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

// ---- 말하기 ----

/**
 * C72: USAGE_NOTIFICATION + CONTENT_TYPE_SPEECH, 포커스 GAIN_TRANSIENT_MAY_DUCK, Locale.KOREA, 시작 때 빈 문장으로 예열. 직렬화는 [TtsGate].
 * start()마다 SDK 객체와 게이트를 새로 만들고 release()에서 놓는다(stop 뒤 다시 start 가능).
 */
internal class PleosTts(private val ctx: Context, private val ex: ExecThread) : TtsPort {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    private var events: EventListener? = null                 // addEventListener한 것. release에서 뗀다
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener({ c -> if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) ex.guard { gate.focusLost() } }, ex.handler).build()   // C321 ②: DUCK·GAIN은 무시
    private var gate: TtsGate = newGate()

    private fun newGate() = TtsGate(
        sdkSpeak = { (tts ?: error("tts 없음")).speak(it, true) }, sdkStop = { tts?.stop() },
        requestFocus = { am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED },
        abandonFocus = { am.abandonAudioFocusRequest(focus) }, sched = ex)

    fun start() {
        val g = newGate().also { gate = it }
        val t = TextToSpeech(ctx, TtsMode.ON_DEVICE).also { tts = it }
        val ev = object : EventListener {
            override fun onStart() {}
            override fun onReady() {}
            override fun onDone() = ex.guard { g.settled(true) }
            override fun onError(e: TtsError) = ex.guard { Log.w(TAG, "tts $e"); g.settled(false) }
            override fun onStop() = ex.guard { g.settled(false) }
            override fun onUpdatedRms(rms: Double) {}
        }
        t.initialize(object : TtsInit {
            override fun onSuccess() = ex.guard {
                if (tts !== t) return@guard                          // 그사이 release됐다
                ex.off.run {                                          // SDK 설정은 잠금 밖 레인에서, 칸·워밍업은 잠금 안에서
                    t.setLocale(Locale.KOREA)
                    t.setAudioAttributes(attrs)
                    t.addEventListener(ev)
                    ex.guard { if (tts === t) { events = ev; g.warm() } else runCatching { t.removeEventListener(ev) } }
                }
            }
            override fun onFailure(e: TtsError) = ex.guard { Log.w(TAG, "tts init $e"); g.initFailed() }
        })
    }

    override fun speak(text: String, done: (Boolean) -> Unit) = gate.speak(text, done)
    override fun stop() = gate.stop()
    fun release() { gate.stop(); val t = tts; tts = null; runCatching { events?.let { t?.removeEventListener(it) } }; events = null; runCatching { t?.release() } }   // 공식 순서: 리스너 해제 뒤 release
}
