package kr.aigyeot.app.host

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FirstTry
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.Step

/** 호스트. ui-store-host가 구현하고 화면·scenario가 쓴다. 공개 모양은 고치지 않는다(handoff README 4장). */
interface HostApi {
    val steps: StateFlow<Step?>                    // 판단 코어 Step 흐름(읽기 전용, 계약 2.4). 마지막 값만: 느린 수집자는 중간 Step을 건너뛴다
    val stepLog: SharedFlow<Step>                  // 모든 Step을 순서대로(replay 0). 할 일을 하나도 놓치면 안 되는 수집자(장면 측정)용
    val stepLogDrops: StateFlow<Long>              // stepLog 버퍼가 넘쳐 아무에게도 가지 못한 Step 수(누적). 장면은 반복 시작·끝을 견준다
    fun post(event: Event)                         // 사건 큐에 넣기(at은 호스트가 찍는다)
    fun clock(): Long                              // 호스트 시계(epoch ms)
    val controlLog: SharedFlow<ControlLogEntity>   // exec가 남긴 control_log 줄을 그대로 흘린다(장면 측정 7.4용). store 저장과 별개
    fun startScenario(setup: ScenarioSetup)        // 장면 한 번 시작: 지금 실제 상태를 보관하고 setup으로 처음 상태를 만든다(계약 7.2, 반복 k번째는 seed + k). G44 표시·override 적용
    fun endScenario()                              // 장면 끝: 보관한 실제 상태로 되돌리고 override를 푼다
}

data class ScenarioSetup(
    val id: String, val seed: Long, val profile: Profile?, val methods: List<Method>,
    val history: List<FirstTry>, val feedingStart: Long?, val otherGuardians: Int,
    val override: Set<String>,                     // 진짜 신호를 막을 사건 이름(계약 7.2 setup.override)
    val care: CareView? = null,                    // 폰 돌봄 기록 요약 흉내(C119). 장면 첫 Step 뒤 CareSummary로 넣는다
)
