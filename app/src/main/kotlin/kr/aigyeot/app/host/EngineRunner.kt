package kr.aigyeot.app.host

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kr.aigyeot.app.link.fallbackReply
import kr.aigyeot.app.link.llmState
import kr.aigyeot.app.store.Store
import kr.aigyeot.engine.CallMode
import kr.aigyeot.engine.ClearRecords
import kr.aigyeot.engine.Engine
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.PlaybackRejected
import kr.aigyeot.engine.PlaybackStarted
import kr.aigyeot.engine.QaReply
import kr.aigyeot.engine.Resume
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.Step
import kr.aigyeot.engine.Suspend
import kr.aigyeot.engine.Tick
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val TAG = "host"

/**
 * 사건 큐·실행. 사건 큐 하나를 스레드 하나("host")에서 돌려 판단 코어를 부르고, 할 일은 [router]에 넘긴다.
 * 앱 쪽에서 EngineState를 쓰는 곳은 [run]과 [replace]뿐이다(replace는 판단 코어 순수 함수·Room 대조 결과만 받는다).
 * Suspend·Resume 짐작과 시계 점프도 이 큐의 일이다. 장면 여부는 [inScene], 복원 ⑤는 [reconcileLink]로 묻는다(순환이라 람다).
 */
internal class EngineRunner(
    private val engine: Engine,
    private val store: Store,
    private val clock: () -> Long,
    private val slept: () -> Long,
    private val targets: () -> Targets,
    private val router: CommandRouter,
    private val inScene: () -> Boolean,
    private val reconcileLink: (resume: Boolean) -> Unit,
) {
    private var hostThread: Thread? = null
    // dead 뒤에는 큐에 있던 flush·snapshot·장면 시작·끝·복원 대조 등 run()을 거치지 않는 길도 한 곳에서 막는다(execute로 들어오는 모든 일)
    private val worker = object : ScheduledThreadPoolExecutor(1, { r -> Thread(r, "host").also { hostThread = it } }) {
        override fun execute(command: Runnable) = super.execute { if (!dead) command.run() }
        // 작업의 예외는 Future에 묻혀 로그 없이 사라진다. 실패 처리를 빠져나간 예외를 여기 한 곳에서 남긴다
        override fun afterExecute(r: Runnable, t: Throwable?) {
            super.afterExecute(r, t)
            val f = r as? Future<*>
            val x = t ?: if (f != null && f.isDone && !f.isCancelled) try { f.get(); null } catch (e: ExecutionException) { e.cause } else null
            if (x != null) Log.e(TAG, "호스트 작업 예외", x)
        }
    }
    private val urgent = ConcurrentLinkedDeque<Event>()   // 재생 시작 결과(먼저 처리). 어느 스레드에서든 넣는다
    private var tick: ScheduledFuture<*>? = null
    private var lastAt = Long.MIN_VALUE
    var started = false
        private set
    private var lastSlept = 0L
    private val early = ArrayList<Event>()             // 복원 전에 온 사건(모듈이 시작하며 낸 지금 상태 등). 복원 ①~⑤ 뒤에 처리한다
    /** 지금 상태. 호스트 스레드에서만 읽고 쓴다. */
    lateinit var state: EngineState
        private set

    private val _steps = MutableStateFlow<Step?>(null)
    val steps: StateFlow<Step?> = _steps
    // 버퍼 4096 Step(10Hz 표본이면 약 7분 밀림). 넘치면 그 Step은 버리고 수를 센다(호스트 스레드는 수집자를 기다리지 않는다)
    private val _stepLog = MutableSharedFlow<Step>(extraBufferCapacity = 4096)
    val stepLog: SharedFlow<Step> = _stepLog
    private val _drops = MutableStateFlow(0L)
    val drops: StateFlow<Long> = _drops

    /** 호스트 스레드에서 할 일(장면·복원·전원·store 쓰기). */
    fun execute(block: Runnable) = worker.execute(block)

    /** 판단 코어 순수 함수(restoreFromRoom·afterRestore·sceneStart)의 결과로만 상태를 갈아 끼운다. 호스트 스레드에서. */
    fun replace(s: EngineState) { state = s }

    /** 사건을 큐에 넣는다. at은 넣을 때 찍고 단조 증가로 올린다. 할 일을 넘기는 중 exec가 낸 재생 시작 결과는 다음 사건보다 먼저 처리한다. */
    fun post(event: Event) {
        val e = stamp(event)
        if (e is PlaybackStarted || e is PlaybackRejected) {
            urgent.addLast(e)
            if (Thread.currentThread() !== hostThread) worker.execute { drainUrgent() }
        } else worker.execute { process(e) }
    }

    /** 복원 ①~⑤. 한 번만. 큐의 첫 일이 되도록 호스트를 만든 곳에서 바로 부른다. [boot]는 RestoreUseCase.boot. */
    fun start(boot: (Long) -> Unit) = worker.execute {
        if (started) return@execute
        started = true
        // 시작 길 전체가 fail-fast다. 스냅숏·초기 상태·대기 리포트 읽기가 한 번 던져도 started만 참인 채 첫 Step 없이 멈추지 않는다
        try {
            val now = clock()
            lastSlept = slept()
            boot(now)
            early.forEach { process(it) }; early.clear()   // exec.start()가 낸 지금 기어·통화 상태 등을 버리지 않는다
            drainUrgent()
        } catch (x: Exception) { Log.e(TAG, "시작 실패: 프로세스를 끝낸다", x); die(x); throw x }
    }

    /** Suspend 짐작. 감시 서비스가 부른다. 이미 Suspend면 넣지 않는다. */
    fun suspend() = worker.execute { if (started && state.ign.suspendedAt == null) process(stamp(Suspend(0))) }

    /** Resume 짐작: Resume 뒤 대조('Suspend 뒤 재개 포함'). 화면 켜짐·전원 정책 켜짐마다 link를 바로 다시 확인시킨다. */
    fun resume() = worker.execute {
        Log.i(TAG, "link.wake: 화면·전원 켜짐")
        targets().wake()
        if (!started) return@execute
        checkJump()   // 점프로 이미 Resume을 넣었으면 아래에서 다시 넣지 않는다
        if (state.ign.suspendedAt != null) { reconcileLink(true); process(stamp(Resume(0))) }   // Suspend 중 Room에만 든 수유·경로를 먼저 반영한 뒤 출발 제안이 판단한다
    }

    /** 묶음 저장(30초) 중인 상태를 지금 쓴다(감시 서비스가 끝날 때). */
    fun flush() = worker.execute { if (started && !inScene()) router.snapshot(state) }

    /**
     * 발화를 끊는 사건 앞에서 실행기에 원자적으로 묻는다. 이미 끝난 발화면 그 SpeechDone을 먼저 처리해 인계 확정이 true로 간다
     * (뒤에 큐에서 오는 같은 SpeechDone은 말하는 줄이 달라 엔진이 무시한다). 아직 말하는 중이면 실행기가 끊었고 원래 사건이 이어서 처리한다.
     */
    /** 점프 확인(첫 정리부터 점프 Suspend 처리까지) 동안의 잠들기 전 시각. 그 밖엔 null(상한 없음). */
    private var jumpCap: Long? = null

    fun settleSpeech() {
        val u = state.speech.speaking ?: return
        val (spoken, doneAt) = targets().cancelIfSpeaking(u) ?: return
        val cap = jumpCap   // 점프 처리 중엔 정리가 몇 번 불려도(다음 발화가 바로 끝난 경우, run 안의 Suspend 정리 포함) 모두 잠들기 전 시각이 상한이다
        // 실제 완료 시각. Suspend만 사건 시각보다 늦지 않게(점프 Suspend는 잠들기 전 시각이라 잠든 시간을 세지 않는다).
        // 통화·장면 시작은 실제 시각 그대로(큐에서 밀려 늦게 처리돼도 완료 시각을 앞당기지 않는다)
        run(SpeechDone(if (cap == null) doneAt else minOf(doneAt, cap), u, spoken))
    }

    /** 시험용: 큐에 쌓인 일을 모두 끝낼 때까지 기다린다. */
    fun awaitIdle() { worker.submit {}.get() }

    /** 시험용: 지금 상태. 호스트 스레드에서 읽는다. */
    fun awaitState(): EngineState = worker.submit<EngineState> { state }.get()

    fun close() { worker.shutdownNow() }

    // ---- 사건 처리 ----

    fun stamp(e: Event): Event = synchronized(this) {
        lastAt = maxOf(clock(), lastAt)
        e.withAt(lastAt)
    }

    fun process(e: Event) {
        if (!started) { early += e; return }
        checkJump()
        drainUrgent(); run(composeFacts(e)); drainUrgent()
    }

    /** 사실 주제 답은 큐에서 처리하는 지금의 상태로 문장을 짠다(앞선 요약·지우기가 반영된다). careStale이어도 사실 답은 최신이라 말한다. */
    private fun composeFacts(e: Event): Event {
        if (e !is QaReply || (e.factTopics.isEmpty() && e.fallbackTopics.isEmpty())) return e
        run(Tick(e.at))   // 그 시각까지 시간 진행을 먼저 적용한 상태로 짠다(같은 사건에서 GAP로 바뀐 뒤 울음 진행값을 말하지 않게)
        val st = llmState(state, clock())   // 경보 수는 판단 코어 상태 값(Room을 읽지 않아 읽기 실패가 대체값으로 말해지지 않는다)
        val topics = e.factTopics
        return e.copy(
            text = if (topics.isEmpty()) e.text else fallbackReply(topics, st), factTopics = emptyList(),
            fallback = if (e.fallbackTopics.isEmpty()) e.fallback else fallbackReply(e.fallbackTopics, st), fallbackTopics = emptyList(),
        )
    }

    private fun drainUrgent() {
        if (!started) return
        checkJump()   // 다른 스레드의 재생 시작 결과는 process를 안 거친다. process에서 두 번 불려도 lastSlept가 갱신돼 무해하다
        while (true) run(urgent.pollFirst() ?: return)
    }

    /** 화면·전원 신호 없이 잠들었다 깼으면(잠든 시간이 늘었음) 잠들기 전 시각에 Suspend, 깬 시각에 Resume을 먼저 넣는다. */
    fun checkJump() {
        val now = slept()
        val jumped = now - lastSlept > JUMP_MS
        lastSlept = now
        if (!jumped) return
        Log.i(TAG, "시계 점프, link.wake")
        runCatching { targets().wake() }.onFailure { Log.e(TAG, "link.wake 실패", it) }   // 시계 점프도 바로 다시 확인. 부수 일
        if (state.ign.suspendedAt == null) {
            jumpCap = state.now
            try { settleSpeech(); run(Suspend(state.now)) } finally { jumpCap = null }
        }   // 이른 시각 상한은 점프 Suspend만. stamp하지 않는다: 잠든 시간을 quietMs에 세지 않게
        reconcileLink(true)   // Resume(재판단)보다 먼저
        run(stamp(Resume(0)))
    }

    fun run(raw: Event) {
        if (dead) return
        if (inScene() && raw is ClearRecords) { Log.w(TAG, "장면 중에는 기록을 지우지 않는다"); return }
        if ((raw is CallMode && raw.inCall) || raw is Suspend) settleSpeech()
        val old = state
        val e = if (raw.at < old.now) raw.withAt(old.now) else raw   // at이 state.now보다 앞서면 올린다
        val step = try { engine.step(old, e) } catch (x: Exception) { Log.e(TAG, "step 실패: $e", x); return }
        applyStep(old, e, step)
    }

    private fun applyStep(old: EngineState, e: Event, step: Step) {
        state = step.state
        store.observe(e, step.state)
        runCatching { publish(step) }.onFailure { Log.e(TAG, "Step 발행 실패", it) }   // 부수 일
        router.dispatch(old, step)
        scheduleTick(step.wakeAt)
    }

    /** 모든 Step을 순서대로 낸다(steps는 마지막 값, stepLog는 손실 없는 흐름). */
    fun publish(step: Step) {
        _steps.value = step
        if (!_stepLog.tryEmit(step)) { _drops.value++; Log.w(TAG, "stepLog 버퍼가 넘쳐 Step 하나를 버림(누적 ${_drops.value})") }
    }

    @Volatile private var dead = false

    /** 호스트 스레드는 ScheduledExecutor가 예외를 삼키므로, 새 스레드에서 던져 기본 핸들러가 프로세스를 끝내게 한다. 그 전까지 더는 처리하지 않는다. */
    fun die(x: Throwable) { dead = true; Thread({ throw x }, "host-fatal").start() }

    fun scheduleTick(wakeAt: Long?) {
        tick?.cancel(false)
        val snapAt = router.batchDueAt()
        val wake = listOfNotNull(wakeAt, snapAt).minOrNull() ?: return
        // 이미 지난 wakeAt이 같은 Step에서 풀리지 않았으면 1초 뒤에 다시 본다(빈 Tick으로 바쁘게 돌지 않게)
        val d = (wake - clock()).let { if (it <= 0) 1_000 else it }
        tick = worker.schedule({ worker.execute { process(stamp(Tick(0))) } }, d, TimeUnit.MILLISECONDS)
    }

    private companion object {
        // shortcut: 시계 점프 문턱 5초는 설계값(근거 없음). 첫날 suspend --simulate 실측으로 맞춘다
        const val JUMP_MS = 5_000L
    }
}
