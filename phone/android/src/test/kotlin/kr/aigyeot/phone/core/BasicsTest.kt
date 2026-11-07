package kr.aigyeot.phone.core

import kr.aigyeot.shared.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun `연결하는 동안 온 새 링크는 화면 코드를 바꾸지 않는다`() {
        // U52: 연결(끊기 포함)이 도는 동안, 그리고 성공했지만 화면이 아직 완료로 옮기지 않은 동안은 확인 화면으로 가지 않고 pair.busy만 보인다.
        assertEquals(false, canOpenConfirm(pairBusy = true, doneUnshown = false))
        assertEquals(false, canOpenConfirm(pairBusy = false, doneUnshown = true))
        // 실패했거나 완료를 보인 뒤(결과를 비움)에는 평소처럼 받는다.
        assertEquals(true, canOpenConfirm(pairBusy = false, doneUnshown = false))
        assertEquals("연결하는 중이에요. 끝난 뒤 링크를 다시 열어 주세요.", PAIR_BUSY)
    }

    // 연결 7.1 "어디서든 401·4001 → GONE": 사용자가 요청한 끊기 중에 온 것은 끊기가 끝날 때 본다.
    @Test fun `끊기가 실패해도 그사이 온 4001을 잊지 않는다`() {
        val w = LeaveWatch<String>()
        w.start("A")
        assertEquals(false, w.lostFor("A"))
        assertEquals(true, w.absorb("A")) // 끊는 중에는 바로 GONE으로 가지 않고 적어 둔다
        assertEquals(true, w.lostFor("A")) // 중계가 토큰을 지운 것을 적어 둘 수 있다
        assertEquals(false, w.lostFor("B"))
        assertEquals(true, w.end(failed = true)) // 끊기가 오프라인·5xx로 끝나면 GONE으로 간다
        assertEquals(false, w.absorb("A")) // 끊기가 끝난 뒤의 401·4001은 평소처럼 바로 GONE이다
    }

    @Test fun `끊기가 실패하고 그사이 401이 없었으면 연결을 둔다`() {
        val w = LeaveWatch<String>()
        w.start("A")
        assertEquals(false, w.absorb("B")) // 끊는 자격이 아닌 401은 적지 않는다
        assertEquals(false, w.end(failed = true))
        // 앞 끊기에서 적은 것이 다음 끊기로 넘어가지 않는다.
        w.start("A")
        w.absorb("A")
        w.end(failed = false)
        w.start("A")
        assertEquals(false, w.end(failed = true))
    }

    @Test fun `끊기가 성공하면 GONE 안내 없이 끊긴다`() {
        val w = LeaveWatch<String>()
        w.start("A")
        assertEquals(true, w.absorb("A")) // 중계는 204보다 먼저 이 폰의 소켓을 4001로 닫는다
        assertEquals(false, w.end(failed = false))
    }

    @Test
    fun `연결 결과는 그사이 끊기지 않았을 때만 완료 화면으로 간다`() {
        // 7.1·U25: 새 연결 직후 401·4001로 GONE이 됐으면 완료 화면이 GONE 화면을 덮지 않는다.
        assertTrue(pairDoneValid(Role.OWNER, gone = false, currentRole = Role.OWNER))
        assertFalse(pairDoneValid(Role.OWNER, gone = true, currentRole = Role.OWNER))
        assertFalse(pairDoneValid(Role.OWNER, gone = false, currentRole = null)) // 정리로 자격이 비었다
        assertFalse(pairDoneValid(null, gone = false, currentRole = Role.OWNER)) // 실패한 결과
    }
}
