package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 5.8 CS1·CS2, 5.9 C40e (명세서 17장 C119). 판단 코어는 요약을 저장만 한다.
class CareSummaryTest {
    private fun care(asOf: Long, feeds: Int = 1) = CareView(asOf, true, null, null, null, null, null, null, null, feeds)

    @Test fun cs1_newerCareSummaryReplaces() {
        val sim = idle().ev(CareSummary(T0, care(T0 + 1_000, 1)))
        assertEquals(care(T0 + 1_000, 1), sim.s.care)                       // care == null이면 받는다
        sim.ev(CareSummary(T0, care(T0 + 2_000, 2)))
        assertEquals(care(T0 + 2_000, 2), sim.s.care)                       // asOf가 더 늦으면 바꾼다
        assertTrue(sim.cmds.isEmpty())                                      // 할 일·문장·제안은 없다
    }

    @Test fun cs2_olderOrSameCareSummaryIgnored() {
        val sim = idle().ev(CareSummary(T0, care(T0 + 2_000, 2)))
        sim.ev(CareSummary(T0, care(T0 + 1_000, 9)))
        sim.ev(CareSummary(T0, care(T0 + 2_000, 7)))                        // 같은 asOf도 무시
        assertEquals(care(T0 + 2_000, 2), sim.s.care)
    }

    @Test fun cs1_doesNotChangeDecisions() {
        val a = idle(feedingStart = T0 - 170 * MIN, prof = profile(interval = 180))
        val b = idle(feedingStart = T0 - 170 * MIN, prof = profile(interval = 180)).ev(CareSummary(T0, care(T0)))
        assertEquals(a.s, b.s.copy(care = null))
    }

    @Test fun c40e_clearRecords_dropsCareSummary() {
        val sim = idle().ev(CareSummary(T0, care(T0))).ev(ClearRecords(T0))
        assertNull(sim.s.care)
        assertEquals(listOf("DeleteRecords", "DeleteRelayRecords", "SaveSnapshot"), sim.cmds.map { it::class.simpleName })
        sim.ev(CareSummary(T0, care(T0 - 5_000)))                           // 폰이 다음에 보내는 요약은 다시 받는다(옛 asOf라도)
        assertEquals(care(T0 - 5_000), sim.s.care)
    }
}
