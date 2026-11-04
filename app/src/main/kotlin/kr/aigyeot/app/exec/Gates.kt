package kr.aigyeot.app.exec

// Pleos SDK의 TTS·STT 콜백에는 회차 id가 없다. 앞 회차의 늦은 콜백이 새 회차의 결과가 되지 않게 SDK 앞에서 직렬화한다.
// 실제 SDK는 Android.kt의 PleosTts·PleosStt가 이 클래스에 함수로 이어 준다(SDK 없이 시험하려고 떼어 둠).

/**
 * TTS 직렬화. SDK에 요청이 걸려 있는 동안(끝 신호 onDone·onError·onStop 중 하나가 아직 안 옴) 다음 문장은 미뤄 둔다.
 * [stop]은 콜백을 떼고 SDK를 멈추되 '걸려 있음'은 끝 신호가 오거나 [SETTLE_MS]가 지날 때까지 유지한다. 예열도 [WARM_MS] 안에 풀린다.
 * 끝 신호가 다음 문장의 것으로 잘못 읽히는 틈은 시간 초과로 풀린 뒤 앞 문장의 신호가 아주 늦게 올 때뿐이다(알려진 한계).
 */
internal class TtsGate(
    private val sdkSpeak: (String) -> Unit,
    private val sdkStop: () -> Unit,
    private val requestFocus: () -> Boolean,
    private val abandonFocus: () -> Unit,
    private val sched: Sched,
) : TtsPort {
    private var up = false                                   // 초기화가 끝났다
    private var failed = false
    private var inflight = false                             // SDK에 끝 신호를 기다리는 요청이 있다
    private var cur: ((Boolean) -> Unit)? = null             // 그 요청이 지금 문장의 것일 때만
    private var queued: Pair<String, (Boolean) -> Unit>? = null
    private var cancelWait: () -> Unit = {}

    /** 초기화 성공. 빈 문장으로 예열한다(C72). */
    fun warm() {
        up = true
        inflight = true
        try { sdkSpeak("") } catch (e: Exception) { inflight = false }
        if (inflight) arm(WARM_MS) else drain()
    }

    fun initFailed() { failed = true; queued?.second?.invoke(false); queued = null }

    /** SDK 끝 신호(onDone이면 true, onError·onStop이면 false). 걸려 있는 요청이 없으면 버린다. */
    fun settled(ok: Boolean) {
        if (!inflight) return
        inflight = false
        release()
        val c = cur
        cur = null
        if (c != null) { abandonFocus(); c(ok) }
        drain()
    }

    override fun speak(text: String, done: (Boolean) -> Unit) {
        if (failed) return done(false)
        if (!up || inflight) { queued = text to done; return }
        begin(text, done)
    }

    override fun stop() {
        queued = null
        if (cur == null) return                              // 걸린 요청이 없거나 예열·앞 요청뿐이면 그대로 둔다
        cur = null
        abandonFocus()
        runCatching { sdkStop() }
        arm(SETTLE_MS)                                       // 끝 신호가 올 때까지 다음 문장을 미룬다
    }

    private fun begin(text: String, done: (Boolean) -> Unit) {
        if (!requestFocus()) return done(false)
        cur = done
        inflight = true
        try { sdkSpeak(text) } catch (e: Exception) { inflight = false; cur = null; abandonFocus(); done(false) }
    }

    private fun drain() {
        if (!up || inflight) return
        val q = queued ?: return
        queued = null
        begin(q.first, q.second)
    }

    private fun arm(ms: Long) { cancelWait(); cancelWait = sched.after(ms) { timeout() } }
    private fun release() { cancelWait(); cancelWait = {} }

    /** 앞 요청·예열의 끝 신호가 끝내 오지 않았다. 풀고 미룬 문장을 시작한다. */
    private fun timeout() {
        cancelWait = {}
        inflight = false
        drain()
    }

    companion object {
        /** stop 뒤 끝 신호를 기다리는 시간. 설계값, 근거 없음. */
        const val SETTLE_MS = 1_000L
        /** 예열 끝 신호가 없을 때. 설계값, 근거 없음. */
        const val WARM_MS = 3_000L
    }
}

/**
 * STT 직렬화. 듣기 하나가 sendAudio… completeAudioSend 한 묶음이다. 묶음의 PCM을 하나도 넣기 전에 온 결과·오류는 앞 묶음의 것이라 버린다.
 */
internal class SttGate(
    private val sendAudio: (ByteArray) -> Unit,
    private val completeAudioSend: () -> Unit,
    private val stopSdk: () -> Unit,
) : SttPort {
    private var onText: ((String, Boolean) -> Unit)? = null
    private var fed = false

    override fun begin(onText: (String, Boolean) -> Unit) { this.onText = onText; fed = false }
    override fun feed(pcm: ByteArray) { fed = true; sendAudio(pcm) }
    override fun finish() = completeAudioSend()
    override fun cancel() { onText = null; fed = false; stopSdk() }

    /** SDK 결과(ResultListener.onUpdated). */
    fun result(text: String, completed: Boolean) { if (fed) onText?.invoke(text, completed) }
    /** SDK 오류. 최종 결과 없음(빈 글)으로 알린다. */
    fun error() = result("", true)
}
