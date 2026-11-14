package kr.aigyeot.engine

// 판단 코어의 공개 겉면: Engine·Step·SceneStart·SceneEnd·fresh·DEFAULT_METHODS. 전이 본문은 작업 공간 Tx(Tx.kt)의 확장 함수다
// (차례 Router.kt, 리듀서 *Reducer.kt, 복원 ② Restore.kt, 수명 표 Waiting.kt, 순수 규칙 Rules.kt). 이 파일은 상태 칸을 직접 쓰지 않는다
// (시각은 Tx.clock, 차 상태는 Life의 restored·intoScene. OwnershipTest가 본다).
// 호스트는 판단 코어 상태를 손으로 고치지 않고 아래 함수의 결과로만 바꾼다.
class Engine(private val p: Params = Params()) {
    /** 순수 함수. 같은 state·event면 늘 같은 Step을 낸다. */
    fun step(state: EngineState, event: Event): Step = Tx(p, state).run(event)

    /**
     * 복원 ③: 수명 표 RESTORE 열(말하던 줄·질문·질문 창·인계 표시 없는 장시간 줄·조향 창 버림)과
     * 경로 비우기·복원 시각(새 경로를 기다린다). [micAlive]면 GAP·CONFIRM 판정 창을 지금부터 다시 잰다(입력이 돌아올 때와 같은 함수).
     * 할 일이 없는 값 맞추기라 상태만 돌려준다. 호스트는 재생 끝·마이크 끊김을 사실 사건으로 먼저 넣고 이 함수를 부른다.
     * 재생 끝의 시각은 복원 시각이 아니라 스냅숏의 마지막 시각(② 뒤 상태의 now)이다: 재생은 프로세스가 마지막으로 산 때 멈췄다.
     */
    fun afterRestore(state: EngineState, at: Long, micAlive: Boolean): EngineState = Tx(p, state).apply {
        clock(maxOf(at, state.now))
        if (micAlive && s.car.pauses.isEmpty()) reopenWindow()
        lifecycle(Life.RESTORE)
        restored(at)
    }.s

    /**
     * 장면 시작: 보관할 실제 상태에 수명 표 SCENE_START 열을 적용한 Step과 장면 첫 상태. 열은 말하는 중인지와 상관없이 적용한다(
     * 말하기·큐·질문 듣기·질문 답·재생을 Suspend처럼 끊고, 결과가 장면 상태로 갈 경유지·쾌적 대기는 버린다). 실행기 끊기(CancelSpeech)가 늘 있어 Step도 늘 있다.
     * 장면 첫 상태 = [scene](setup으로 만든 fresh)에 실제 차 상태·운전자를 이어받는다. 끊김 사유는 세션 밖 것만 넘긴다.
     */
    fun sceneStart(real: EngineState, scene: EngineState, at: Long): SceneStart {
        val t0 = maxOf(at, real.now)
        val t = Tx(p, real).apply {
            clock(t0)
            lifecycle(Life.SCENE_START)
            flushLedgers()
            syncAmbient()
        }
        return SceneStart(
            Step(t.s, t.out.toList(), t.wakeAt()),
            Tx(p, scene).apply {
                clock(t0)
                intoScene(real)
            }.s
        )
    }

    /**
     * 장면 끝: 버릴 장면 상태에 수명 표 SCENE_END 열을 적용한다. [SceneEnd.commands]는 실행기에 넘길 할 일(장면 재생 멈춤·CancelSpeech·
     * 장면 세션 뒷창 되돌림), [SceneEnd.settle]은 알림 없이 Room에서 확정할 장면 장부. 보관한 실제 상태는 손대지 않는다:
     * 장면 동안 실제로 바뀐 차 값은 실행기가 다시 내고, setup 몫 값은 호스트가 Room으로 맞춘다.
     */
    fun sceneEnd(scene: EngineState): SceneEnd {
        val t = Tx(p, scene).apply { lifecycle(Life.SCENE_END) }
        return SceneEnd(t.out.toList(), scene.handoffs - t.s.handoffs.toSet())
    }

    /** 시험용(복원 ③의 한 행): 말하던 줄을 말하지 않은 것으로 버린다. 앱은 [afterRestore]를 쓴다. */
    internal fun dropSpeaking(state: EngineState, at: Long): EngineState {
        state.speech.speaking ?: return state
        return Tx(p, state).apply {
            clock(maxOf(at, state.now))
            endSpeaking()
        }.s
    }

    /**
     * 복원 ② Room 대조(Room 우선). 호스트는 Room에서 읽은 [room]만 넘긴다. 사건이 아니라 값 맞추기라 할 일·now는 바꾸지 않는다.
     * (a) Room에서 끝난 세션이면 closeSession과 같은 정리로 IDLE. (b) 같은 세션이면 재생 누적·울음 수 하한·끝난 에피소드 닫기·확정 결과·
     * Room에 이미 남은 인계 확정. guardian_notified는 link만 쓰므로 맞출 것이 없다.
     */
    fun restoreFromRoom(state: EngineState, room: RoomFacts, now: Long): EngineState {
        val t = maxOf(now, state.now)
        return Tx(p, state).apply {
            clock(t)
            fromRoom(room)
            clock(state.now)
        }.s
    }

    /** 시험용: 입력이 돌아온 것처럼 판정 창을 지금부터 다시 잰다. GAP·CONFIRM이 아니면 그대로. 앱은 [afterRestore]를 쓴다. */
    internal fun reopenWindow(state: EngineState, at: Long): EngineState = Tx(p, state).apply {
        clock(at)
        reopenWindow()
        clock(state.now)
    }.s
}

data class Step(
    val state: EngineState, // 새 상태
    val commands: List<Command>, // 할 일. 이 순서대로 실행한다
    // 이 시각까지 Tick이 와야 한다. null이면 기다릴 시간 전이가 없다
    val wakeAt: Long?
)

/** [Engine.sceneStart]의 결과. realStep은 실제 상태에서 실행할 Step, scene은 장면 첫 상태. */
data class SceneStart(val realStep: Step, val scene: EngineState)

/** [Engine.sceneEnd]의 결과. commands = 실행기에 넘길 할 일(끊기·뒷창 되돌림), settle = Room에서 확정할 장면 장부. */
data class SceneEnd(val commands: List<Command>, val settle: List<HandoffLedger>)

// 처음 상태. 저장해 둔 스냅숏이 있으면 호스트가 그것을 쓴다.
fun EngineState.Companion.fresh(
    seed: Long,
    profile: Profile?,
    methods: List<Method>,
    history: List<FirstTry>,
    feedingStart: Long?,
    otherGuardians: Int
): EngineState = EngineState(
    now = 0, rng = seed, seq = 0, profile = profile, methodsNext = methods, history = history,
    feedingStart = feedingStart, otherGuardians = otherGuardians,
    car = CarView(null, null, emptySet(), false, false, null, null, null, emptyMap()),
    ign = Ignition(false, false, false, emptyList(), null, 0),
    session = null,
    speech = SpeechQueue(null, null, emptyList()),
    steer = SteerState(null, null, null, null, 0, null)
)

/** 앱 기본 음원(백색소음·뱃속 소리)과 자장가. 세션 기본값·store 후보 목록·홈 화면이 같이 쓴다. */
val DEFAULT_METHODS = listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.WOMB), Method(MethodKind.LULLABY))
