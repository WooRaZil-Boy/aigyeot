package kr.aigyeot.phone.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BasicsTest {
    @Test fun 연결429_잠금은_RetryAfter를_따르고_없으면_10초() {
        assertEquals(1_000_000L + 30_000, pairLockUntil(1_000_000L, 30))
        assertEquals(1_000_000L + 10_000, pairLockUntil(1_000_000L, null))
        assertEquals(1_000_000L + 10_000, pairLockUntil(1_000_000L, 0))
    }

    @Test fun 조향_비교불가_사유_문구() {
        assertEquals("울음이 없어 비교하지 않았어요", steeringUnavailableText(true))
        assertEquals("비교할 구간이 없어요", steeringUnavailableText(false))
    }

    @Test fun 직접_친_코드는_확인_없이_연결_요청() {
        assertEquals(false, needsConfirm(CodeSource.TYPED))
    }

    @Test fun QR과_링크로_온_코드는_확인_화면을_거친다() {
        assertEquals(true, needsConfirm(CodeSource.QR))
        assertEquals(true, needsConfirm(CodeSource.LINK))
    }

    @Test fun 링크로_온_연결_코드는_확인_화면_제목이_차_화면() {
        assertEquals("차 화면에 이 코드가 보이나요?", confirmTitle(codeFromLink("aigyeot://pair?code=042917")!!))
    }

    @Test fun 링크로_온_초대_코드는_확인_화면_제목이_받은_초대_코드() {
        assertEquals("받은 초대 코드가 이것과 같나요?", confirmTitle(codeFromLink("aigyeot://pair?code=7k3qm-2xw9d")!!))
    }

    private val T = 1_000_000L

    @Test fun 직접_친_코드_연결_안_됐으면_바로_요청() {
        assertEquals(TypedAction.PAIR, typedSubmitAction("042917", connected = false, busy = false, lockUntil = null, now = T))
    }

    @Test fun 직접_친_코드_연결됐으면_교체_확인_길로_보낸다() {
        assertEquals(TypedAction.CONFIRM_REPLACE, typedSubmitAction("042917", connected = true, busy = false, lockUntil = null, now = T))
    }

    @Test fun 직접_친_코드_잠금_중이면_요청하지_않는다() {
        assertEquals(TypedAction.IGNORE, typedSubmitAction("042917", connected = false, busy = false, lockUntil = T + 1, now = T))
        assertEquals(TypedAction.IGNORE, typedSubmitAction("042917", connected = true, busy = false, lockUntil = T + 1, now = T))
    }

    @Test fun 잠금_시각이_지나면_다시_요청() {
        assertEquals(TypedAction.PAIR, typedSubmitAction("042917", connected = false, busy = false, lockUntil = T, now = T))
    }

    @Test fun 직접_친_코드_요청_중이면_다시_요청하지_않는다() {
        assertEquals(TypedAction.IGNORE, typedSubmitAction("042917", connected = false, busy = true, lockUntil = null, now = T))
    }

    @Test fun 직접_친_코드_형식이_틀리면_요청하지_않는다() {
        assertEquals(TypedAction.BAD_FORMAT, typedSubmitAction(null, connected = false, busy = false, lockUntil = null, now = T))
    }

    @Test fun 요청_중에는_QR_링크로_확인_화면에_가지_않는다() {
        assertEquals(false, canOpenConfirm(pairBusy = true))
        assertEquals(true, canOpenConfirm(pairBusy = false))
    }
}
