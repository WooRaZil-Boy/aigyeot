package kr.aigyeot.app.exec

import android.content.Context
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event

/** 차·음성 실행기. exec가 채운다. 공개 모양은 고치지 않는다(handoff README 4장). */
class Exec(
    private val context: Context,
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    private val logControl: (ControlLogEntity) -> Unit,   // control_log 한 줄(계약 4.2 F19). 호스트가 store와 controlLog 흐름으로 넘긴다
    private val state: () -> EngineState?,                 // 최신 상태 읽기(예: 앞에서 틀기 허락 D12)
) {
    fun start() {}                 // 차량 신호 구독·통화 모드·오디오 준비(감시 서비스 시작 때)
    fun stop() {}
    fun handle(cmd: Command) {}    // 계약 4장 '받는 곳'이 exec인 할 일
}
