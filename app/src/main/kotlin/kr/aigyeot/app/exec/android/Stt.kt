package kr.aigyeot.app.exec.android

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import java.util.Locale
import kr.aigyeot.app.exec.SttGate
import kr.aigyeot.app.exec.SttPort

// ---- 듣기 ----

/**
 * STT ON_DEVICE에 PCM을 직접 넣는다. 듣기 하나가 sendAudio… completeAudioSend 한 묶음이다(실측한 순서 그대로). 직렬화는 [SttGate].
 * start()마다 SDK 객체와 게이트를 새로 만들고 release()에서 놓는다.
 */
internal class PleosStt(private val ctx: Context, private val ex: ExecThread) : SttPort {
    private var stt: SpeechToText? = null
    private var ready = false
    private var listening: ResultListener? = null // addListener한 것. release에서 뗀다
    private var gate = newGate()

    private fun newGate() = SttGate(
        sendAudio = { if (ready) runCatching { stt?.sendAudio(it) } },
        completeAudioSend = { if (ready) runCatching { stt?.completeAudioSend() } },
        stopSdk = { if (ready) runCatching { stt?.stop() } }
    )

    fun start() {
        val g = newGate().also { gate = it }
        ready = false
        val s = SpeechToText(ctx, SttMode.ON_DEVICE).also { stt = it }
        val listener = object : ResultListener {
            override fun onUpdated(text: String, completed: Boolean) = ex.guard { g.result(text, completed) }
            override fun onUpdatedEpdData(on: Long, off: Long) {}
            override fun onStartedRecognition() {}
            override fun onEndedRecognition() {}
            override fun onError(e: SttError) = ex.guard {
                Log.w(TAG, "stt $e")
                g.error()
            }
            override fun onReady() {}
            override fun onUpdatedRms(rms: Double) {}
        }
        s.initialize(object : SttInit {
            override fun onSuccess() = ex.guard {
                if (stt === s) {
                    ex.off.run {
                        // SDK 설정은 잠금 밖 레인에서, 칸은 잠금 안에서
                        s.setLocale(Locale.KOREA)
                        s.addListener(listener)
                        ex.guard {
                            if (stt === s) {
                                listening = listener
                                ready = true
                            } else {
                                runCatching { s.removeListener(listener) }
                            }
                        }
                    }
                }
            }
            override fun onFailure(e: SttError) {
                Log.w(TAG, "stt init $e")
            }
        })
    }

    override fun begin(onText: (String, Boolean) -> Unit) = gate.begin(onText)
    override fun feed(pcm: ByteArray) = gate.feed(pcm)
    override fun finish() = gate.finish()
    override fun cancel() = gate.cancel()

    // 공식 순서: 리스너 해제 뒤 release
    fun release() {
        cancel()
        ready = false
        val s = stt
        stt = null
        runCatching { listening?.let { s?.removeListener(it) } }
        listening = null
        runCatching { s?.release() }
    }
}
