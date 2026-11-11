package kr.aigyeot.phone.domain.model

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

    @Test fun `첫 안내 글은 7_1 표 그대로이고 원문 넷은 시트에 남는다`() {
        // U43 안 B(iOS test_첫_안내와_연결_확인과_띠_글자는_7_1_표_그대로와 같은 단언).
        assertEquals("아이곁", IntroText.TITLE)
        assertEquals("차의 아이곁과\n이 휴대폰을 연결해요", IntroText.LEAD)
        assertFalse(IntroText.LEAD.endsWith("."))
        assertEquals(1, IntroText.LEAD.count { it == '\n' })
        assertEquals(
            listOf(
                "차와 주고받아요" to "수유 시작 시각, 우리 노래, 수유·잠·기저귀 요약, 주행 리포트, 인계 알림이에요",
                "울음소리와 차 위치는 보내지 않아요" to "차 안 마이크 소리도 보내지 않아요",
                "아이곁 중계 서버를 거쳐요" to "인계 알림은 Google·Apple 알림 서버도 거쳐요",
                "차에서 물으면 AI 서버를 써요" to "답을 만들려고 수유·잠·기저귀 숫자를 보내요",
            ),
            IntroText.SHORT,
        )
        assertEquals(listOf("자세히 보기", "오가는 정보 자세히 보기", "오가는 정보", "닫기"), listOf(IntroText.MORE, IntroText.MORE_A11Y, IntroText.SHEET_TITLE, IntroText.SHEET_CLOSE))
        assertEquals(listOf("주고받는 것", "보내지 않는 것", "거치는 서버", "AI 서버"), IntroText.SHEET.map { it.first })
        // 원문 보존(U43·E5): 라벨 + ": " + 글(주고받는·보내지 않는 것)과 거치는 서버·AI 글이 옛 7.1 원문 그대로다.
        val (sends, notSent, relay, ai) = IntroText.SHEET
        assertEquals("주고받는 것: 수유 시작 시각, 우리 노래 녹음, 수유·잠·기저귀 기록 요약(시각·횟수·잠 시간), 주행 리포트, 인계 알림 문장", "${sends.first}: ${sends.second}")
        assertEquals("보내지 않는 것: 아기 울음 소리, 차 안 마이크 소리, 차의 위치", "${notSent.first}: ${notSent.second}")
        assertEquals("모두 아이곁 중계 서버를 거쳐 오가요. 인계 알림은 Google·Apple 알림 서버도 거쳐요.", relay.second)
        assertEquals("차에서 아기 상황을 물으면, 답을 만들려고 수유·잠·기저귀 숫자를 AI 서버로 보내요.", ai.second)
        assertEquals("차와 연결하기" to "먼저 기록만 쓸게요", IntroText.CONNECT to IntroText.RECORDS_ONLY)
    }

    @Test fun `연결 429 글은 잠금 동안만 보인다`() {
        // contracts-link 7.2 '코드 시도 초과': 글은 잠금 끝 시각에서 잰 것이고, 잠금이 풀려 버튼이 다시 켜지면 걷는다. 다른 실패는 그대로.
        val limited = PairRun.Limited("123456")
        assertEquals("30초 뒤에 다시 해 주세요", pairErrorShown(limited, pairWaitText(30_000)))
        assertEquals(null, pairErrorShown(limited, pairWaitText(0)))
        assertEquals(null, pairErrorShown(limited, wait = null))
        val invalid = PairRun.Failed("123456", "코드가 맞지 않거나 시간이 지났어요. 차 화면의 코드를 다시 확인해 주세요.")
        assertEquals(invalid.error, pairErrorShown(invalid, wait = null))
        assertEquals(null, pairErrorShown(PairRun.Running("123456"), pairWaitText(30_000)))
    }

    @Test fun `연결 429 남은 시간은 잠금의 첫 글만 알리고 줄어드는 동안은 다시 읽지 않는다`() {
        // 화면 읽기(라이브 영역): 카운트다운을 매번 읽지 않는다. 초점을 옮기면 지금 글을 읽는다(글 자체는 늘 지금 값). 다른 실패 글은 늘 알린다.
        val limited = PairRun.Limited("123456")
        val start = pairWaitText(600_000)
        assertTrue(pairErrorLive(limited, start, start))
        assertFalse(pairErrorLive(limited, pairWaitText(540_000), start))
        assertFalse(pairErrorLive(limited, pairWaitText(30_000), start))
        assertTrue(pairErrorLive(PairRun.Failed("123456", "코드가 맞지 않거나 시간이 지났어요. 차 화면의 코드를 다시 확인해 주세요."), null, null))
    }

    @Test fun `연결 429 남은 시간은 60초 넘으면 분 올림 이하면 초 올림`() {
        // contracts-link 7.2(11-12 사용자 결정): 처음 받은 값이 아니라 잠금 끝 시각에서 그때그때 잰 남은 시간.
        assertEquals("10분 뒤에 다시 해 주세요", pairWaitText(600_000))
        assertEquals("10분 뒤에 다시 해 주세요", pairWaitText(540_001))
        assertEquals("9분 뒤에 다시 해 주세요", pairWaitText(540_000))
        assertEquals("2분 뒤에 다시 해 주세요", pairWaitText(60_001))
        assertEquals("60초 뒤에 다시 해 주세요", pairWaitText(60_000))
        assertEquals("1초 뒤에 다시 해 주세요", pairWaitText(1))
        assertEquals(null, pairWaitText(0))
        assertEquals("잠시 뒤에 다시 해 주세요", pairWaitText(null))
        // 다음에 다시 그릴 때는 글이 바뀌는 경계다: 그 시간이 지나면 글이 바뀌고, 그 1ms 전에는 그대로다.
        for (left in listOf(600_000L, 540_001, 540_000, 61_000, 60_001, 60_000, 1_500, 1)) {
            val next = pairWaitNextMs(left)
            assertTrue(next >= 1)
            assertTrue("$left", pairWaitText(left - next) != pairWaitText(left))
            assertEquals("$left", pairWaitText(left), pairWaitText(left - next + 1))
        }
    }

    @Test fun `첫 안내 무대는 남는 높이를 쓰고 120보다 작으면 숨긴다`() {
        // v8b-10 ⑨(두 앱 같은 값, iOS 최대 232).
        assertEquals(232, introStageHeight(874, 446, 150, max = 232))
        assertEquals(222, introStageHeight(915, 446, 134)) // 안드로이드 최대
        assertEquals(148, introStageHeight(760, 446, 134))
        assertEquals(null, introStageHeight(667, 470, 150))
        assertEquals(null, introStageHeight(446 + 134 + 32 + 119, 446, 134)) // 119는 숨김
        assertEquals(120, introStageHeight(446 + 134 + 32 + 120, 446, 134)) // 120은 그림
    }
}
