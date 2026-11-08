package kr.aigyeot.app.host

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.app.store.db.EpisodeEntity
import kr.aigyeot.engine.EpisodeRecord
import kr.aigyeot.engine.EpisodeTrigger
import kr.aigyeot.engine.HandoffCause
import kr.aigyeot.engine.withoutSessionOffers
import kr.aigyeot.engine.HandoffKind
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.SecondAction
import kr.aigyeot.engine.Boot
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.careReplaces
import kr.aigyeot.engine.ClearRecords
import kr.aigyeot.engine.CloseSession
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.DeleteRecords
import kr.aigyeot.engine.DeleteRelayRecords
import kr.aigyeot.engine.EndReason
import kr.aigyeot.engine.Engine
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.InputLost
import kr.aigyeot.engine.LogAlarm
import kr.aigyeot.engine.MethodsChanged
import kr.aigyeot.engine.MicService
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.PauseReason
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.PlaybackEnded
import kr.aigyeot.engine.PlaybackRejected
import kr.aigyeot.engine.PlaybackStarted
import kr.aigyeot.engine.ProfileChanged
import kr.aigyeot.engine.Resume
import kr.aigyeot.engine.SaveEpisode
import kr.aigyeot.engine.SaveFeedingStart
import kr.aigyeot.engine.SavePlay
import kr.aigyeot.engine.SaveSession
import kr.aigyeot.engine.SaveSnapshot
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.SaveSteerWindow
import kr.aigyeot.engine.SetWindowLock
import kr.aigyeot.engine.ShowNotice
import kr.aigyeot.engine.StartPlayback
import kr.aigyeot.engine.Step
import kr.aigyeot.engine.StopPlayback
import kr.aigyeot.engine.CancelSpeech
import kr.aigyeot.engine.Suspend
import kr.aigyeot.engine.Tick
import kr.aigyeot.engine.TryResult
import kr.aigyeot.engine.Unjudged
import kr.aigyeot.engine.fresh
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 마이크 서비스와 맞추는 곳(2.4 ③, C82). 기본은 MicService companion, 시험은 가짜. */
class MicPort(
    val runningPauses: () -> Set<PauseReason>? = kr.aigyeot.app.detect.MicService::runningPauses,
    val assumePausedNow: (Set<PauseReason>) -> Unit = kr.aigyeot.app.detect.MicService::assumePausedNow,
)

/**
 * 호스트(계약 2.4). 사건 큐 하나를 스레드 하나("host")에서 돌려 판단 코어를 부르고, 할 일을 계약 4장 '받는 곳'에 넘긴다.
 * store 쓰기도 이 스레드에서 받은 순서대로 한다(쓰기 줄 하나). [targets]는 시작 전에 건다(Wiring.kt).
 */
class Host(
    private val store: Store,
    private val clock: () -> Long,
    private val engine: Engine = Engine(),
    private val p: Params = Params(),
    private val mic: MicPort = MicPort(),
    private val userId: () -> Int = { android.os.Process.myUid() / 100_000 },
    /** 기기가 잠든 누적 시간(elapsedRealtime − uptimeMillis). 화면·전원 신호 없는 잠듦을 시계 점프로 알아챈다(C67). */
    private val slept: () -> Long = { SystemClock.elapsedRealtime() - SystemClock.uptimeMillis() },
) : HostApi {
    @Volatile var targets = Targets({}, {}, {})

    private var hostThread: Thread? = null
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "host").also { hostThread = it } }
    private val urgent = ConcurrentLinkedDeque<Event>()   // 재생 시작 결과(2.4 '먼저'). 어느 스레드에서든 넣는다
    private var tick: ScheduledFuture<*>? = null
    private var lastAt = Long.MIN_VALUE
    private var lastSnapshotAt = 0L                    // 0이면 첫 변화에서 저장(MIN_VALUE는 빼기에서 넘친다)
    private var started = false
    private var lastSlept = 0L
    private var dirty = false                          // 묶음 저장을 건너뛴 변경이 있다(C78)
    private val early = ArrayList<Event>()             // 복원 전에 온 사건(모듈이 시작하며 낸 지금 상태 등). 복원 ①~⑤ 뒤에 처리한다
    private lateinit var state: EngineState
    private var real: EngineState? = null             // 장면 동안 보관한 실제 상태
    @Volatile private var scenario: ScenarioSetup? = null

    private val _steps = MutableStateFlow<Step?>(null)
    override val steps: StateFlow<Step?> = _steps
    // 버퍼 4096 Step(10Hz 표본이면 약 7분 밀림). 넘치면 그 Step은 버리고 수를 센다(호스트 스레드는 수집자를 기다리지 않는다)
    private val _stepLog = MutableSharedFlow<Step>(extraBufferCapacity = 4096)
    override val stepLog: SharedFlow<Step> = _stepLog
    private val _drops = MutableStateFlow(0L)
    override val stepLogDrops: StateFlow<Long> = _drops
    private val _control = MutableSharedFlow<ControlLogEntity>(extraBufferCapacity = 256)
    override val controlLog: SharedFlow<ControlLogEntity> = _control

    /** 지금 도는 장면 id(G44). store가 SaveSession 때 붙인다. */
    val scenarioId: String? get() = scenario?.id

    override fun clock(): Long = clock.invoke()

    /** 사건을 큐에 넣는다. at은 넣을 때 찍고 단조 증가로 올린다. 할 일을 넘기는 중 exec가 낸 재생 시작 결과는 다음 사건보다 먼저 처리한다. */
    override fun post(event: Event) {
        val e = stamp(event)
        if (e is PlaybackStarted || e is PlaybackRejected) {
            urgent.addLast(e)
            if (Thread.currentThread() !== hostThread) worker.execute { drainUrgent() }
        } else worker.execute { process(e) }
    }

    /** exec가 낸 진짜 신호. 장면이 `override`로 막은 이름이면 버린다(계약 7.2). */
    fun postReal(event: Event) {
        if (event is CareSummary) {   // 보관·적용 판단은 큐에서 꺼낼 때 호스트 스레드에서: 장면 시작·끝과 같은 줄에서 차례로(C130)
            val e = stamp(event) as CareSummary
            worker.execute {
                if (scenario != null) noteRealCare(e)   // 장면이 막았든 아니든 실제로 받은 요약은 장면 끝에 되돌려 준다
                if (scenario?.override?.contains("CareSummary") == true) return@execute
                process(e)
            }
            return
        }
        if (scenario?.override?.contains(event::class.simpleName) == true) return
        post(event)
    }

    @Volatile private var realCareDuringScene: CareSummary? = null   // 장면 중 실제로 받은 가장 늦은 요약(Room에 없어 호스트가 들고 있는다)
    @Synchronized private fun noteRealCare(c: CareSummary) { if (c.gen == real?.careGen && realCareDuringScene.let { it == null || careReplaces(it.summary, it.seq, c.summary, c.seq, it.room, c.room) }) realCareDuringScene = c }

    /** exec의 control_log 한 줄: store에 쓰고 장면 측정용 흐름에도 낸다. */
    fun logControl(row: ControlLogEntity) {
        _control.tryEmit(row)
        worker.execute {
            // 5.9·C40: 기록을 지운 뒤 늦게 온 행이 지운 세션의 control_log를 다시 만들지 않게
            val sid = row.sessionId
            if (sid == null || store.dao.session(sid) != null) store.logControl(row)
        }
    }

    /** 복원 ①~⑤(2.4, contracts-link 8.3). 한 번만. 큐의 첫 일이 되도록 호스트를 만든 곳에서 바로 부른다. */
    fun start() = worker.execute {
        if (started) return@execute
        started = true
        val now = clock()
        lastSlept = slept()
        restore(store.loadSnapshot() ?: store.initialState(seed = now), now, null)   // ④ 기어는 exec 첫 GearChanged가 채운다
        store.dao.pendingReports().forEach { store.handle(CloseSession(it.id)) }                             // 리포트 재개(14u)
        store.purgeOld()
        early.forEach { process(it) }; early.clear()   // exec.start()가 낸 지금 기어·통화 상태 등을 버리지 않는다
        drainUrgent()
    }

    /** 복원 ①~⑤. 프로세스 시작과 장면 끝(장면 동안 콜백이 사라진 일을 비운다)이 같은 길을 탄다. */
    private fun restore(s: EngineState, now: Long, gear: Gear?) {
        store.closeScenarioSessions(now)   // 실제 상태로 돌아올 때(장면 끝·프로세스 시작) 끝나지 못한 장면 세션을 닫는다. 보관 정리는 닫힌 세션만 지운다(명세서 17장 C98)
        // C78: 스냅숏의 진행 중 조향 창은 버린다. 이미 닫혀 SaveSteerWindow로 커밋됐을 수 있어, 두면 같은 (session_id, start_at) 행을 옛 부분 집계로 덮는다.
        // window가 null이면 steer()가 다음 표본에서 새 창을 연다(필터 상태는 그대로 둔다)
        state = reconcileRoom(s, now).let { it.copy(steer = it.steer.copy(window = null)) }   // ①②
        afterRestore(now)                                              // ③
        run(stamp(Boot(now, gear = gear, userId = userId())))          // ④
        reconcileLink()                                                // ⑤
    }

    /** Suspend 짐작(C67). 감시 서비스가 부른다. 이미 Suspend면 넣지 않는다. */
    fun suspend() = worker.execute { if (started && state.ign.suspendedAt == null) process(stamp(Suspend(0))) }

    /** Resume 짐작(C67): Resume 뒤 8.3 대조('Suspend 뒤 재개 포함'). 화면 켜짐·전원 정책 켜짐마다 link를 바로 다시 확인시킨다(contracts-link 8.6). */
    fun resume() = worker.execute {
        Log.i(TAG, "C67 link.wake: 화면·전원 켜짐")
        targets.wake()
        if (!started) return@execute
        checkJump()   // 점프로 이미 Resume을 넣었으면 아래에서 다시 넣지 않는다
        if (state.ign.suspendedAt != null) { process(stamp(Resume(0))); reconcileLink() }
    }

    /** 묶음 저장(30초) 중인 상태를 지금 쓴다(감시 서비스가 끝날 때). */
    fun flush() = worker.execute { if (started && scenario == null) snapshot(state) }

    override fun startScenario(setup: ScenarioSetup) = worker.execute {
        if (!started) return@execute
        if (scenario == null) { real = state; realCareDuringScene = null; snapshot(state); store.resetContext() }   // 장면 표본이 실제 맥락에 섞이지 않게(C92). 실제 스냅숏은 장면 직전 값으로 둔다(장면 중 죽어도 실제로 복원)
        scenario = setup
        val r = real!!
        // 차 상태(기어·문·마이크)와 운전자는 실제 값을 이어받는다. 나머지는 장면 setup으로 처음부터
        val f = EngineState.fresh(setup.seed, setup.profile, setup.methods, setup.history, setup.feedingStart, setup.otherGuardians)
        state = f.copy(now = maxOf(clock(), r.now), car = r.car, careGen = r.careGen, ign = f.ign.copy(driverUserId = r.ign.driverUserId))
        publish(Step(state, emptyList(), null))   // 장면 첫 Step(rng = 그 반복 시드, 할 일 없음). scenario의 SceneGate가 여기서부터 받는다
        setup.care?.let { run(stamp(CareSummary(clock(), it))) }   // C119: 첫 Step 뒤 요약 흉내
    }

    override fun endScenario() = worker.execute {
        val r = real ?: return@execute
        state.session?.play?.let { targets.exec(StopPlayback(it.id, false)) }   // 장면 재생을 남기지 않는다
        targets.exec(CancelSpeech)
        // 장면 car에서 시작하되 장면이 override로 막아 주입한 칸은 실제 값으로 되돌린다
        val o = scenario?.override.orEmpty()
        var c = state.car.copy(outPauses = r.car.outPauses)
        if ("GearChanged" in o) c = c.copy(gear = r.car.gear, gearSince = r.car.gearSince)
        if ("RearDoor" in o) c = c.copy(rearDoorOpen = r.car.rearDoorOpen)
        if ("CallMode" in o) c = c.copy(inCall = r.car.inCall)
        if ("CabinTemp" in o) c = c.copy(cabinC = r.car.cabinC)
        if ("RouteState" in o) c = c.copy(route = r.car.route)
        if ("SeatHeat" in o) c = c.copy(seatHeat = r.car.seatHeat)
        if ("MicService" in o) c = c.copy(micRunning = r.car.micRunning, micPath = r.car.micPath)
        scenario = null
        real = null
        store.resetContext()
        // 실제 상태의 말하기·질문·재생·잠금 콜백은 장면 상태로 가서 사라졌다. 프로세스 복원과 같은 길로 비운다(H7: Tick도 다시 예약)
        // 장면 전 값과 장면 중 받은 값 중 CS1이 이기는 것(C126 ④)
        val d = realCareDuringScene?.takeIf { it.gen == r.careGen }   // 지우기 전 세대의 요약은 되살리지 않는다(C132)
        val back = if (d != null && careReplaces(r.care, r.careSeq, d.summary, d.seq, r.careRoom, d.room)) r.copy(care = d.summary, careSeq = d.seq, careRoom = d.room) else r
        realCareDuringScene = null
        restore(back.copy(car = c), clock(), c.gear)
        targets.routeAgain()   // 복원 ③이 비운 경로를 내비의 최신 값으로 다시 채운다: 같은 값이라 억제되지 않게(C130)
        if (back.care != r.care || back.careSeq != r.careSeq || back.careRoom != r.careRoom) snapshot(state)   // 실제 값으로 되돌린 요약은 바로 저장(Boot Step의 key는 이미 같은 값이라 못 본다)
    }

    /** 시험용: 큐에 쌓인 일을 모두 끝낼 때까지 기다린다. */
    internal fun awaitIdle() { worker.submit {}.get() }

    /** 시험용: 지금 상태. 호스트 스레드에서 읽는다. */
    internal fun state(): EngineState = worker.submit<EngineState> { state }.get()

    fun close() { worker.shutdownNow() }

    // ---- 사건 처리 ----

    private fun stamp(e: Event): Event = synchronized(this) {
        lastAt = maxOf(clock(), lastAt)
        e.withAt(lastAt)
    }

    private fun process(e: Event) {
        if (!started) { early += e; return }
        checkJump()
        drainUrgent(); run(e); drainUrgent()
    }

    private fun drainUrgent() {
        if (!started) return
        checkJump()   // 다른 스레드의 재생 시작 결과는 process를 안 거친다. process에서 두 번 불려도 lastSlept가 갱신돼 무해하다(C93)
        while (true) run(urgent.pollFirst() ?: return)
    }

    /** 화면·전원 신호 없이 잠들었다 깼으면(잠든 시간이 늘었음) 잠들기 전 시각에 Suspend, 깬 시각에 Resume을 먼저 넣는다(C10·C67). */
    private fun checkJump() {
        val now = slept()
        val jumped = now - lastSlept > JUMP_MS
        lastSlept = now
        if (!jumped) return
        Log.i(TAG, "C67 시계 점프, link.wake")
        targets.wake()   // contracts-link 8.6: 시계 점프도 바로 다시 확인
        if (state.ign.suspendedAt == null) run(Suspend(state.now))   // stamp하지 않는다: 잠든 시간을 quietMs에 세지 않게
        run(stamp(Resume(0)))
        reconcileLink()
    }

    private fun run(raw: Event) {
        if (scenario != null && raw is ClearRecords) { Log.w(TAG, "장면 중에는 기록을 지우지 않는다"); return }
        val old = state
        val sceneCare = if (scenario != null && raw is CareSummary && raw.seq == null) raw.copy(gen = old.careGen) else raw   // 장면 주입은 지금 세대(C128)
        val e = if (sceneCare.at < old.now) sceneCare.withAt(old.now) else sceneCare   // 2.4: at이 state.now보다 앞서면 올린다
        val step = try { engine.step(old, e) } catch (x: Exception) { Log.e(TAG, "step 실패: $e", x); return }
        state = step.state
        store.observe(e, step.state)
        val relayClear = DeleteRelayRecords in step.commands
        if (!relayClear) publish(step)
        dispatch(old, step)
        if (relayClear) publish(step)   // 새 careGen은 link가 삭제 표시를 한 뒤에 보인다(C132)
        scheduleTick(step.wakeAt)
    }

    /** 할 일을 순서대로 '받는 곳'에 넘긴다(Wiring.route). store 몫은 이 스레드에서 바로 쓴다. */
    private fun dispatch(old: EngineState, step: Step) {
        val cmds = step.commands
        val clearing = DeleteRecords in cmds
        var saved = false
        for (c in cmds) {
            try {
                when (route(c)) {
                    To.EXEC -> targets.exec(c)
                    To.LINK -> targets.link(c)
                    To.UI -> targets.notice((c as ShowNotice).kind)
                    To.STORE -> when {
                        // 5.9·C90: 같은 Step의 DeleteRecords·SaveSnapshot·DeleteRelayRecords를 한 트랜잭션으로
                        c is DeleteRecords -> { store.clearRecords(cmds.filterIsInstance<SaveSnapshot>().lastOrNull()?.state ?: step.state); lastSnapshotAt = clock(); dirty = false; saved = true }
                        c is DeleteRelayRecords -> targets.link(c)   // 중계 삭제는 store가 보낼 함에 넣었다. link는 그때까지 받은 seq를 남긴다(C126 ②)
                        c is SaveSnapshot -> if (!clearing) { if (scenario == null) snapshot(c.state); saved = true }
                        c is SaveFeedingStart && scenario != null -> {}   // 장면은 실제 마지막 수유를 바꾸지 않는다
                        else -> store.handle(c)
                    }
                }
            } catch (x: Exception) { Log.e(TAG, "할 일 실패: $c", x) }
        }
        // C78: 그 Step의 저장 뒤 같은 쓰기 줄에 스냅숏. 장면 중에는 실제 스냅숏을 덮지 않는다
        if (!saved && scenario == null && needsSnapshot(old, step)) snapshot(step.state)
    }

    /** 모든 Step을 순서대로 낸다(steps는 마지막 값, stepLog는 손실 없는 흐름, 계약 2.4). */
    private fun publish(step: Step) {
        _steps.value = step
        if (!_stepLog.tryEmit(step)) { _drops.value++; Log.w(TAG, "stepLog 버퍼가 넘쳐 Step 하나를 버림(누적 ${_drops.value})") }
    }

    private fun snapshot(s: EngineState) { store.saveSnapshot(s); lastSnapshotAt = clock(); dirty = false }

    /** C78 (a) 바로 저장 / (b) 30초 묶음 / Tick으로 now만 바뀐 Step은 저장하지 않음. */
    internal fun needsSnapshot(old: EngineState, step: Step): Boolean {
        val n = step.state
        if (step.commands.any { it::class in IMMEDIATE }) return true
        if (key(old) != key(n)) return true
        if (old.copy(now = n.now) != n) dirty = true   // 건너뛴 변경은 그 뒤 Tick만 와도 30초 안에 저장한다
        return dirty && clock() - lastSnapshotAt >= p.snapshotIntervalMs
    }

    private fun scheduleTick(wakeAt: Long?) {
        tick?.cancel(false)
        val snapAt = if (dirty && scenario == null) lastSnapshotAt + p.snapshotIntervalMs else null
        val wake = listOfNotNull(wakeAt, snapAt).minOrNull() ?: return
        // 이미 지난 wakeAt이 같은 Step에서 풀리지 않았으면 1초 뒤에 다시 본다(빈 Tick으로 바쁘게 돌지 않게, 2.4 '1초마다 넣어도 된다')
        val d = (wake - clock()).let { if (it <= 0) 1_000 else it }
        tick = worker.schedule({ process(stamp(Tick(0))) }, d, TimeUnit.MILLISECONDS)
    }

    // ---- 복원 ----

    /**
     * ② Room 대조(Room 우선). (a) Room에서 끝난 세션이면 IDLE로(남은 pauses는 outPauses로, C82). (b) 같은 세션이면 재생 누적을 큰 값으로·상한.
     * 확정 결과 우선(r14w), 끝난 에피소드는 LISTENING으로(r14x). 사건이 아니라 값 맞추기다.
     */
    internal fun reconcileRoom(snap: EngineState, now: Long): EngineState {
        val x = snap.session ?: return snap
        val dao = store.dao
        val row = dao.session(x.id)
        if (row?.endedAt != null) {
            // C137·C140: closeSession과 같은 규칙(제안 문장을 큐에서 뺀다, 말하던 줄은 C123이 버린다). 알림·SaveEpisode 없음(closeSession이 이미 Room에 기록했다)
            val sp = snap.speech.withoutSessionOffers()
            return snap.copy(session = null, speech = sp, car = snap.car.copy(outPauses = snap.car.outPauses + x.pauses), steer = snap.steer.copy(window = null),
                history = store.history(now))   // 세션 끝과 함께 커밋된 마지막 1차 결과가 성적에서 빠지지 않게 Room에서 다시 센다(5장, C56)
        }
        val played = maxOf(x.playedMs, dao.sessionPlayedMs(x.id))
        var ses = x.copy(playedMs = played, capReached = x.capReached || played >= p.sessionPlayCapMs)
        var history = snap.history
        val ep = ses.episode
        if (ep != null) {
            val room = dao.episode(ep.id)
            if (room?.endedAt != null) {
                // C133 ②: 대기 인계 줄이 이 에피소드의 것이면 Room의 닫힌 행을 closedRecord로 채운다(버려질 때 그 행에 toldDriver=false를 남긴다)
                val pa = ses.pendingHandoffAlert?.takeIf { it.episodeId == ep.id }?.copy(closedRecord = room.toRecord()) ?: ses.pendingHandoffAlert
                ses = ses.copy(phase = Phase.LISTENING, attemptNo = 0, play = null, judgeFrom = null, windowEnd = null, episode = null, quietMs = 0, pendingAlarm = null, pendingHandoffAlert = pa)
                history = store.history(now)   // Room에서 다시 세어 같은 에피소드를 두 번 넣지 않는다
            } else {
                val first = ep.first?.let { f ->
                    val r = room?.firstResult?.let(TryResult::valueOf)
                    if (f.result == null && r != null) f.copy(result = r, unjudged = room.firstUnjudged?.let(Unjudged::valueOf)) else f
                }
                ses = ses.copy(episode = ep.copy(playedMs = maxOf(ep.playedMs, dao.episodePlayedMs(ep.id)), first = first))
            }
        }
        // C136: 질문을 내며 Room에 handoff_told_driver를 저장한 뒤 스냅숏 전에 죽었다. Room 값이 앞서니 대기 줄과 큐 줄을 지우고 값만 옮긴다(알림·SaveEpisode 없음)
        var speech = snap.speech
        ses.pendingHandoffAlert?.let { a ->
            val told = a.episodeId?.let { dao.episode(it) }?.takeIf { it.handoffToldDriver != null } ?: return@let
            ses = ses.copy(pendingHandoffAlert = null, episode = ses.episode?.takeIf { it.id == a.episodeId }?.let { e ->
                e.copy(handoff = e.handoff?.copy(toldDriver = told.handoffToldDriver, restName = told.handoffRestName))
            } ?: ses.episode)
            // 말하던 맨 앞 줄이면 ③의 dropSpeaking이 버린다(C123). 여기서 빼면 다음 줄이 버려진다
            val keep = speech.speaking != null
            val (goneIx, keptIx) = speech.queue.indices.partition { i -> speech.queue[i].let { l -> l.sid == a.sid && l.askRestId == a.askRestId && l.queuedAt == a.queuedAt } && !(keep && i == 0) }
            val gone = goneIx.map { speech.queue[it] }
            speech = speech.copy(queue = keptIx.map { speech.queue[it] })
            // 빼는 줄에 엔진이 그때 했을 처리를 적용한다: 말했다면 speechDone과 같은 변화(C84·C66·C141), 아니면 forgetOffer
            gone.forEach { l ->
                val tmp = snap.copy(session = ses, speech = speech)
                val r = if (told.handoffToldDriver == true) engine.markSpoken(tmp, l, now) else engine.markDropped(tmp, l)
                ses = r.session!!
            }
        }
        return snap.copy(session = ses, history = history, speech = speech)
    }

    /** ③ 복원 처리: 재생 이중 누적 방지(C58), GAP·CONFIRM 판정 멈춤(C59), 질문·듣기·말하기 비우기, 마이크 맞추기(C69·C82). */
    private fun afterRestore(now: Long) {
        state.session?.play?.let { run(stamp(PlaybackEnded(now, it.id, EndReason.ERROR, recorded = store.dao.playLogged(it.id)))) }   // r14r·r14s
        val running = mic.runningPauses()
        val x = state.session
        if (x?.phase == Phase.GAP || x?.phase == Phase.CONFIRM) {
            if (running == null) run(stamp(InputLost(now, PauseReason.MIC_ERROR)))   // r14t: 프로세스가 새로 떴다
            else if (x.pauses.isEmpty())   // 장면 끝: 마이크가 살아 있다. 장면 동안 실제 상태가 관측하지 못한 창이라 입력이 돌아온 것처럼 지금부터 다시 잰다(C92)
                state = state.copy(session = x.copy(judgeFrom = now, windowEnd = now + if (x.phase == Phase.GAP) p.gapMs else p.confirmMs))
        }
        // C123: 말하던 줄(큐 맨 앞)은 다시 말하지 않는다. C125: 옛 경로로 판단하지 않고 내비 응답을 기다린다
        state = engine.dropSpeaking(state, now)   // C128 ④: 버리기 전에 말한 것으로 친다(longDriveOffered·s06Said)
        state = state.copy(session = state.session?.copy(pendingAsk = null), car = state.car.copy(route = null), bootAt = now,
            speech = state.speech.copy(qa = null, speaking = null, queue = state.speech.queue.filterNot { it.longDrive }))   // C138: 말하지 않은 장시간 제안은 새 경로로 L1이 다시 판단한다(인계 줄은 longDrive가 아니다, H7)
        if (state.car.micRunning && running == null) { run(stamp(MicService(now, false, null))); run(stamp(InputLost(now, PauseReason.MIC_ERROR))) }
        val unresolved = state.session?.pauses.orEmpty() + state.car.outPauses
        // 엔진에만 있는 사유는 늘 assumePausedNow로: 서비스가 그사이 시작해도 잠금 안에서 감지기·pendingPauses로 나뉜다(C82)
        (unresolved - (running ?: emptySet())).takeIf { it.isNotEmpty() }?.let(mic.assumePausedNow)
        running?.let { r -> (r - unresolved).forEach { run(stamp(InputLost(now, it))) } }
    }

    /**
     * ⑤ contracts-link 8.3 '복원 뒤 대조': Room 값과 다르면 사건으로 맞춘다. 수유·보호자는 link 몫(Link.restoreEvents), 후보 ③은 store 몫.
     * 프로필도 같은 틈이 있어 함께 본다. restoreEvents는 IO에서 돌지만 첫 판단 전에 넣어야 해 호스트 스레드가 끝날 때까지 기다린다(큐 한 스레드 그대로).
     */
    private fun reconcileLink() {
        if (!started) return
        val now = clock()
        val fromLink = try { runBlocking { targets.restore(state, now, scenario == null) } } catch (x: Exception) { Log.e(TAG, "복원 뒤 대조(link) 실패", x); emptyList() }
        fromLink.forEach { process(stamp(it)) }
        store.methods().let { if (it != state.methodsNext) process(stamp(MethodsChanged(now, it))) }
        store.profile().let { if (it != state.profile) process(stamp(ProfileChanged(now, it))) }
    }

    /** store가 song·song_selection·프로필 변화를 보고 부른다(후보 ③ 다시 계산, 명세서 5장). */
    fun methodsMaybeChanged() = worker.execute {
        if (!started) return@execute
        store.methods().let { if (it != state.methodsNext) process(stamp(MethodsChanged(0, it))) }
    }

    companion object {
        private const val TAG = "host"
        // shortcut: 시계 점프 문턱 5초는 설계값(근거 없음). 첫날 suspend --simulate 실측으로 맞춘다
        private const val JUMP_MS = 5_000L
        private val IMMEDIATE = setOf(
            SaveSession::class, SaveEpisode::class, SavePlay::class, SaveSteerWindow::class, SaveFeedingStart::class,
            LogAlarm::class, CloseSession::class, DeleteRecords::class, SetWindowLock::class, StartPlayback::class, StopPlayback::class, Speak::class, Ask::class,   // C123: 말하기 시작
        )

        /** C78 (a)의 '바뀌면 바로 저장' 값들. */
        private fun key(s: EngineState) = s.session.let { x ->
            listOf(
                x?.id, x?.phase, x?.attemptNo, x?.play, x?.episode, x?.pauses, x?.longDriveOffered, x?.capReached,
                x?.pendingAsk, x?.waypointPending, x?.nursingStop, s.car.outPauses, s.ign.windowLockPending, s.ign.suspendedAt,
                s.feedingStart, s.profile, s.methodsNext, x?.departChecked, s.care, s.careSeq, s.careRoom, x?.pendingHandoffAlert,
            )
        }
    }
}

/** Room 행 → 판단 코어 기록(복원 대조에서 닫힌 에피소드를 closedRecord로 되살릴 때). */
private fun EpisodeEntity.toRecord() = EpisodeRecord(
    id, sessionId, startedAt, endedAt, EpisodeTrigger.valueOf(trigger), alarmAt, alarmDelayMs,
    firstMethod?.let(MethodKind::valueOf), firstSongId, firstResult?.let(TryResult::valueOf), firstUnjudged?.let(Unjudged::valueOf),
    secondAction?.let(SecondAction::valueOf), secondMethod?.let(MethodKind::valueOf), secondSongId, secondResult?.let(TryResult::valueOf),
    handoffCause?.let(HandoffCause::valueOf), handoffKind?.let(HandoffKind::valueOf), waypointAdded, guardianNotified,
    handoffRestName, handoffToldDriver, cryMs, recried, playedMs, excludeReasons,
)
