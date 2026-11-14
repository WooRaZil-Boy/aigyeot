package kr.aigyeot.app.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.bestEffort
import kr.aigyeot.engine.CareCleared
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.Engine
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.LinkChanged
import kr.aigyeot.engine.MethodsChanged
import kr.aigyeot.engine.PlaybackRejected
import kr.aigyeot.engine.PlaybackStarted
import kr.aigyeot.engine.ProfileChanged
import kr.aigyeot.engine.Step
import kr.aigyeot.engine.fresh

/**
 * 장면 제어와 실제 세계 변경의 입구. 장면 동안 실제 상태를 [real]에 보관하고 손대지 않는다.
 * 실제 사건은 [applyReal] 한 곳이 받을지 정한다: 장면 중 setup 몫·override 이름은 버리고(원본은 Room·실제 차), 장면 끝에
 * 복원 ⑤가 Room 값으로, 실행기가 지금 차 값으로 실제 상태를 맞춘다. 호스트 스레드에서 돈다.
 */
internal class SceneController(
    private val runner: EngineRunner,
    private val router: CommandRouter,
    private val restore: RestoreUseCase,
    private val engine: Engine,
    private val store: Store,
    private val clock: () -> Long,
    private val targets: () -> Targets,
) {
    private val _scenario = MutableStateFlow<ScenarioSetup?>(null)
    /** 지금 도는 장면. 다른 스레드(store의 장면 id, 재생 시작 결과의 override)도 읽는다. 화면은 [scenarioFlow]로 본다. */
    val scenario: ScenarioSetup? get() = _scenario.value
    val scenarioFlow: StateFlow<ScenarioSetup?> = _scenario
    /** 장면 동안 보관한 실제 상태. */
    var real: EngineState? = null
        private set
    /** 장면 중 실제 기어가 P를 벗어났나(장면 끝 Boot의 stopEnded). 첫 장면 시작에서 지우고 끝에서 되돌린다. */
    private var leftParkInScene = false

    /** exec·link·화면·알림이 낸 실제 세계의 변화(입구 하나). 장면 중 처리는 [applyReal] 한 곳이 정한다. */
    fun postReal(event: Event) {
        if (event is PlaybackStarted || event is PlaybackRejected) {   // 재생 시작 결과는 urgent 길(순서가 먼저)이라 넣는 순간에 본다
            if (scenario?.override?.contains(event::class.simpleName) == true) return
            runner.post(event); return
        }
        // 넣을 때 찍고, 장면 판정은 큐에서 꺼낼 때(먼저 큐에 든 startScenario·끝 뒤에 차례로)
        val e = runner.stamp(event)
        runner.execute { applyReal(e) }
    }

    /**
     * 장면 setup이 정하는 칸(프로필·음원 목록·수유 시각·다른 보호자 수·돌봄 요약)을 바꾸는 사건. 모두 Room에 먼저 쓴 뒤 오는 사건이라
     * (store 프로필·부모 노래 후보, link 수유·보호자·요약 행) 장면 중이면 버리고 장면 끝 복원 ⑤가 Room 값으로 실제 상태를 맞춘다.
     */
    private fun setupOwned(e: Event) =
        e is ProfileChanged || e is MethodsChanged || e is LinkChanged || e is FeedingStarted || e is CareSummary || e is CareCleared

    /** 실제 변경의 호스트 스레드 입구 하나. 장면 중이면 setup 몫 사건과 override 이름은 버리고, 나머지(차 신호·운전자 입력 등)는 지금 상태(장면)가 받는다. */
    private fun applyReal(e: Event) {
        val sc = scenario
        if (sc != null && e is GearChanged && e.gear != Gear.P) leftParkInScene = true   // override·setup 몫이어도 실제 차가 P를 벗어난 사실은 남긴다
        if (sc != null && (setupOwned(e) || e::class.simpleName in sc.override)) return
        runner.process(e)
    }

    /**
     * 장면 시작(SCENE_START). 판단 코어 대기는 [Engine.sceneStart]가 실제 상태에 조건 없이 적용한다. 앱 쪽 대기:
     * 실행기 Voice 말하기·듣기는 CancelSpeech, Player 재생은 StopPlayback(둘 다 realStep의 할 일), Controls의 경유지·제어 결과는 끊지 않는다
     * (실제 대기는 판단 코어가 버렸고 늦은 결과는 cmdId가 달라 장면 상태가 버린다), 맥락 캡처는 작성 중인 칸까지 쓰고 끝낸다(resetContext).
     */
    fun start(setup: ScenarioSetup): Unit = runner.execute {
        if (!runner.started) return@execute
        runner.checkJump()   // 잠든 시간이 settleSpeech의 quietMs로 새지 않게 시계 점프 확인 경로를 먼저 탄다
        runner.settleSpeech()   // 장면 중 장면 교체도 같다(실행기 발화를 끊고 장면 상태에서 먼저 정리)
        // 실제 상태 정리(SCENE_START)와 장면 첫 상태 조립(setup + 실제 차 상태·운전자)은 판단 코어가 한다. 장면 교체면 보관한 실제 상태에서 조립만
        val first = scenario == null
        val f = EngineState.fresh(setup.seed, setup.profile, setup.methods, setup.history, setup.feedingStart, setup.otherGuardians)
        val start = engine.sceneStart(if (first) runner.state else real!!, f, clock())
        if (first) {
            leftParkInScene = false
            // 실제 상태에서 실행: 실행기 끊기(CancelSpeech·StopPlayback → Voice 말하기·듣기, Player 재생), 재생·에피소드 기록, 이름 없는 알림·SettleHandoff
            val step = start.realStep
            val old = runner.state
            runner.replace(step.state); runner.publish(step); router.dispatch(old, step); runner.scheduleTick(step.wakeAt)
            real = runner.state; router.snapshot(runner.state); store.resetContext()   // 장면 표본이 실제 맥락에 섞이지 않게. 실제 스냅숏은 장면 직전 값으로 둔다(장면 중 죽어도 실제로 복원)
        }
        _scenario.value = setup
        runner.replace(start.scene)
        runner.publish(Step(runner.state, emptyList(), null))   // 장면 첫 Step(rng = 그 반복 시드, 할 일 없음). scenario의 SceneGate가 여기서부터 받는다
        setup.care?.let { runner.run(runner.stamp(CareSummary(clock(), it))) }   // 첫 Step 뒤 요약 흉내
    }

    /**
     * 장면 끝(SCENE_END 열): 장면 상태 대기는 [Engine.sceneEnd](실행기 끊기·장면 세션 뒷창 되돌림·Room 확정 장부), 맥락 캡처는 작성 중인 칸까지,
     * 보관한 실제 상태는 손대지 않고 복원 ①~⑤(RESTORE·BOOT 열)를 탄다. 장면 동안 실제 상태로 가지 않은 것은 사실로 맞춘다:
     * setup 몫(수유·보호자·요약·부모 노래 후보·프로필)은 ⑤가 Room 값으로, 차 값은 실행기가 지금 값을 중복 억제 없이 다시 낸다.
     */
    fun end() = runner.execute {
        val r = real ?: return@execute
        runner.settleSpeech()   // 이미 끝난 장면 인계 발화의 SpeechDone을 장면 상태에서 먼저 처리한다(장면 시작과 같다, 상한 없음). 아직 말하는 중이면 아래가 끊는다
        val end = engine.sceneEnd(runner.state)   // 장면 상태 정리(SCENE_END)는 판단 코어가 한다
        router.sendLoose(end.commands)   // 장면 재생·발화를 남기지 않고(StopPlayback·CancelSpeech) 장면 세션이 잠근 뒷창을 되돌린다(SetWindowLock)
        store.settleSceneHandoffs(end.settle)   // 쿼리 정리(restore 안) 전에 장면 상태의 알림 장부로 행을 정확히 확정(판단 코어와 같은 규칙)
        _scenario.value = null
        real = null
        store.resetContext()
        // 실제 상태의 말하기·질문·재생은 장면 시작이 끊었다. 남은 잠금 결과 대기 등 장면 상태로 간 콜백은 프로세스 복원과 같은 길로 비운다(Tick도 다시 예약)
        // Boot의 기어는 실행기가 마지막으로 받은 지금 기어다(전이 없는 기준선). 보관한 기어로 내면 뒤이은 reemit의 같은 기어가 P→D 출발로 읽힌다
        restore.restore(r, clock(), targets().lastGear() ?: r.car.gear, stopEnded = leftParkInScene)
        leftParkInScene = false
        targets().reemitAll()   // 그 뒤 차 신호·경로가 지금 값으로 실제 상태에 든다(복원 ③이 비운 경로 포함). 사건은 이 일 뒤 큐에서 실제 상태가 받는다
    }

    /** store가 song·song_selection·프로필 변화를 보고 부른다(부모 노래 후보 다시 계산). 장면 중이면 [applyReal]이 버리고 장면 끝 복원 ⑤가 Room으로 맞춘다. */
    fun methodsMaybeChanged() = runner.execute {
        if (!runner.started) return@execute
        val cur = runner.state.methodsNext
        var m = cur
        if (!bestEffort("부모 노래 후보 다시 계산") { m = store.methods() }) return@execute   // 다음 곡·선택·프로필 변경이나 Resume 대조가 다시 한다
        if (m != cur) applyReal(runner.stamp(MethodsChanged(0, m)))
    }
}
