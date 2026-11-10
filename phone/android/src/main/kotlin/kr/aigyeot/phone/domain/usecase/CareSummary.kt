package kr.aigyeot.phone.domain.usecase

import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.forCar
import kr.aigyeot.phone.domain.model.reconciled
import kr.aigyeot.phone.domain.model.reconciledSummary
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.state.Notices

// 돌봄 요약(기능 C, U45): R18 계산과 R19 보내기. 두 앱이 글자까지 같다.

/**
 * R5·R19: 차로 보낼 수유와 바뀐 돌봄 요약을 보낼 함에 넣고, 보낸 표시를 같은 `state.json` 저장에서 올린다. 저장에 실패하면 아무것도 바뀌지 않는다.
 * 규칙은 [reconciled]·[reconciledSummary](순수 함수)이고, 이 클래스는 그 결과를 저장하고 보낼 함을 깨운다. 연결이 없거나 owner가 아니면 그대로다.
 * 부르는 곳은 정해진 네 계기([RecordCare.syncToCar])와 기록 지우기를 마친 뒤뿐이다(R6).
 */
class ReconcileCareSummary(
    private val state: PhoneStateRepository,
    private val care: CareRepository,
    private val outbox: SendOutbox,
    private val notices: Notices,
    private val clock: Clock,
) {
    /** 수유와 요약(R5·R19). `c`는 계기가 시작할 때 잡은 자격이다(P5). */
    suspend fun feedingAndSummary(c: Credentials) {
        if (state.commit(c) { it.reconciled(care.care.value.forCar(notices.undo.value), clock.now(), APP_ZONE, outbox.sending) } && state.state.value.outbox.isNotEmpty()) outbox.pump()
    }

    /** 요약만(기록 지우기를 마친 뒤). 지우기를 아직 못 마쳤으면 아무것도 넣지 않는다. */
    suspend fun summaryOnly() {
        val c = state.creds ?: return
        if (state.commit(c) { it.reconciledSummary(care.care.value.forCar(notices.undo.value), clock.now(), APP_ZONE, outbox.sending) } && state.state.value.outbox.isNotEmpty()) outbox.pump()
    }
}
