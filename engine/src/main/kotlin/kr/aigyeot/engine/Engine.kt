package kr.aigyeot.engine

// 계약 2.1. 동작(전이 로직)은 갈래 ②가 채운다.
class Engine(private val p: Params = Params()) {
    /** 순수 함수. 같은 state·event면 늘 같은 Step을 낸다. */
    fun step(state: EngineState, event: Event): Step = TODO("갈래 ②: contracts-car 5장")
}

data class Step(
    val state: EngineState,        // 새 상태
    val commands: List<Command>,   // 할 일. 이 순서대로 실행한다
    val wakeAt: Long?,             // 이 시각까지 Tick이 와야 한다. null이면 기다릴 시간 전이가 없다
)

// 처음 상태. 저장해 둔 스냅숏이 있으면 호스트가 그것을 쓴다(3-14 ⑤).
fun EngineState.Companion.fresh(
    seed: Long, profile: Profile?, methods: List<Method>,
    history: List<FirstTry>, feedingStart: Long?, otherGuardians: Int,
): EngineState = TODO("갈래 ②: contracts-car 5장")
