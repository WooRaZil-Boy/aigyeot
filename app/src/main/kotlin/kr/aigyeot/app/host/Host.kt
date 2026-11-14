package kr.aigyeot.app.host

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kr.aigyeot.app.detect.MicService
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.engine.Engine
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.PauseReason
import kr.aigyeot.engine.Step

/** 마이크 서비스와 맞추는 곳. 기본은 MicService companion, 시험은 가짜. */
class MicPort(
    val runningPauses: () -> Set<PauseReason>? = MicService::runningPauses,
    val assumePausedNow: (Set<PauseReason>) -> Unit = MicService::assumePausedNow
)

/**
 * 호스트 겉면. 사건 큐 하나를 스레드 하나("host")에서 돌려 판단 코어를 부르고, 할 일을 받는 곳에 넘긴다.
 * store 쓰기도 이 스레드에서 받은 순서대로 한다(쓰기 줄 하나). [targets]는 시작 전에 건다(Wiring.kt).
 * 안은 넷으로 나눴다: [EngineRunner](큐·실행·전원 짐작), [CommandRouter](할 일 배달·스냅숏 저장),
 * [SceneController](장면·실제 사건 입구), [RestoreUseCase](복원 ①~⑤). 겉면은 생성자·공개 함수를 그대로 두고 넘기기만 한다.
 */
class Host(
    private val store: Store,
    private val clock: () -> Long,
    engine: Engine = Engine(),
    p: Params = Params(),
    mic: MicPort = MicPort(),
    userId: () -> Int = { Process.myUid() / 100_000 },
    /** 기기가 잠든 누적 시간(elapsedRealtime − uptimeMillis). 화면·전원 신호 없는 잠듦을 시계 점프로 알아챈다. */
    slept: () -> Long = { SystemClock.elapsedRealtime() - SystemClock.uptimeMillis() }
) : HostApi {
    @Volatile var targets = Targets({}, {}, {})

    // 넷은 서로 부른다. 순환은 호출 때 읽는 람다로 끊는다(만드는 동안에는 부르지 않는다)
    private val router: CommandRouter = CommandRouter(store, clock, p, { targets }, inScene = { scene.scenario != null }, fatal = { runner.die(it) })
    private val runner: EngineRunner = EngineRunner(
        engine,
        store,
        clock,
        slept,
        { targets },
        router,
        inScene = { scene.scenario != null },
        reconcileLink = { restore.reconcileLink(it) }
    )
    private val restore: RestoreUseCase = RestoreUseCase(runner, engine, store, clock, mic, userId, { targets }, inScene = { scene.scenario != null })
    private val scene: SceneController = SceneController(runner, router, restore, engine, store, clock, { targets })

    override val steps: StateFlow<Step?> = runner.steps
    override val stepLog: SharedFlow<Step> = runner.stepLog
    override val stepLogDrops: StateFlow<Long> = runner.drops

    @Suppress("ktlint:standard:backing-property-naming")
    private val _control = MutableSharedFlow<ControlLogEntity>(extraBufferCapacity = 256)
    override val controlLog: SharedFlow<ControlLogEntity> = _control

    /** 지금 도는 장면 id. store가 SaveSession 때 붙인다. */
    val scenarioId: String? get() = scene.scenario?.id

    /** 지금 도는 장면(없으면 null). 장면 중에는 ClearRecords를 받지 않으므로(EngineRunner) 화면은 이것으로 '모든 기록 지우기'를 끈다. */
    val activeScene: StateFlow<ScenarioSetup?> = scene.scenarioFlow

    override fun clock(): Long = clock.invoke()

    /** 사건을 큐에 넣는다. at은 넣을 때 찍고 단조 증가로 올린다. 할 일을 넘기는 중 exec가 낸 재생 시작 결과는 다음 사건보다 먼저 처리한다. */
    override fun post(event: Event) = runner.post(event)

    /** exec·link·화면·알림이 낸 실제 세계의 변화(입구 하나). 장면 중 처리는 SceneController.applyReal 한 곳이 정한다. */
    fun postReal(event: Event) = scene.postReal(event)

    /** exec의 control_log 한 줄: store에 쓰고 장면 측정용 흐름에도 낸다. */
    fun logControl(row: ControlLogEntity) {
        _control.tryEmit(row)
        runner.execute { store.logControl(row) } // 부모 세션이 없으면 store가 버린다
    }

    /** 복원 ①~⑤. 한 번만. 큐의 첫 일이 되도록 호스트를 만든 곳에서 바로 부른다. */
    fun start() = runner.start(restore::boot)

    /** Suspend 짐작. 감시 서비스가 부른다. 이미 Suspend면 넣지 않는다. */
    fun suspend() = runner.suspend()

    /** Resume 짐작. 화면 켜짐·전원 정책 켜짐마다 link를 바로 다시 확인시킨다. */
    fun resume() = runner.resume()

    /** 묶음 저장(30초) 중인 상태를 지금 쓴다(감시 서비스가 끝날 때). */
    fun flush() = runner.flush()

    override fun startScenario(setup: ScenarioSetup) = scene.start(setup)

    override fun endScenario() = scene.end()

    /** store가 song·song_selection·프로필 변화를 보고 부른다(부모 노래 후보 다시 계산). */
    fun methodsMaybeChanged() = scene.methodsMaybeChanged()

    /** 시험용: 큐에 쌓인 일을 모두 끝낼 때까지 기다린다. */
    internal fun awaitIdle() = runner.awaitIdle()

    /** 시험용: 지금 상태. 호스트 스레드에서 읽는다. */
    internal fun state(): EngineState = runner.awaitState()

    /** 시험용: 바로 저장 판단(CommandRouter.needsSnapshot). */
    internal fun needsSnapshot(old: EngineState, step: Step): Boolean = router.needsSnapshot(old, step)

    fun close() = runner.close()
}
