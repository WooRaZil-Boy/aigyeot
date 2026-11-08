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

    @Test fun cs1_sameSecondLaterSeqReplaces() {
        val sim = idle().ev(CareSummary(T0, care(T0, 1), seq = 10))
        sim.ev(CareSummary(T0, care(T0, 2), seq = 11))
        assertEquals(care(T0, 2), sim.s.care); assertEquals(11L, sim.s.careSeq)    // 같은 초, 큰 seq
        sim.ev(CareSummary(T0, care(T0, 3), seq = 11)); sim.ev(CareSummary(T0, care(T0, 4), seq = 5))
        assertEquals(care(T0, 2), sim.s.care)                                       // 같거나 작은 seq는 무시
        sim.ev(CareSummary(T0, care(T0, 5)))                                        // seq 없음(장면 주입)은 같은 초를 못 바꾼다
        assertEquals(care(T0, 2), sim.s.care)
        val inj = idle().ev(CareSummary(T0, care(T0, 1)))                           // careSeq == null이면 같은 초를 못 바꾼다
        inj.ev(CareSummary(T0, care(T0, 2), seq = 3)); assertEquals(care(T0, 1), inj.s.care)
    }

    @Test fun cs1_differentRoom_sameSecond_laterReceivedWins_sameRoomComparesSeq() {
        val sim = idle().ev(CareSummary(T0, care(T0, 1), seq = 9000, room = "c_A"))
        sim.ev(CareSummary(T0, care(T0, 2), seq = 2, room = "c_B"))               // 새 방은 seq를 새로 센다
        assertEquals(care(T0, 2), sim.s.care); assertEquals("c_B", sim.s.careRoom); assertEquals(2L, sim.s.careSeq)
        sim.ev(CareSummary(T0, care(T0, 3), seq = 1, room = "c_B"))               // 같은 방은 그대로 seq로 견준다
        assertEquals(care(T0, 2), sim.s.care)
        sim.ev(CareSummary(T0, care(T0, 4), seq = 3, room = "c_B"))
        assertEquals(care(T0, 4), sim.s.care)
    }

    @Test fun cs1_doesNotChangeDecisions() {
        val a = idle(feedingStart = T0 - 170 * MIN, prof = profile(interval = 180))
        val b = idle(feedingStart = T0 - 170 * MIN, prof = profile(interval = 180)).ev(CareSummary(T0, care(T0)))
        assertEquals(a.s, b.s.copy(care = null, careSeq = null, careRoom = null))
    }

    @Test fun c40e_clearRecords_dropsCareSummary() {
        val sim = idle().ev(CareSummary(T0, care(T0))).ev(ClearRecords(T0))
        assertNull(sim.s.care)
        assertEquals(listOf("DeleteRecords", "DeleteRelayRecords", "SaveSnapshot"), sim.cmds.map { it::class.simpleName })
        sim.ev(CareSummary(T0, care(T0 - 5_000), gen = sim.s.careGen))      // 폰이 다음에 보내는 요약은 다시 받는다(옛 asOf라도)
        assertEquals(care(T0 - 5_000), sim.s.care)
    }

    @Test fun cs1_staleGenDropped_afterClearRecords() {
        val sim = idle().ev(ClearRecords(T0))
        assertEquals(1, sim.s.careGen)
        sim.ev(CareSummary(T0, care(T0), seq = 430, gen = 0))               // 지우기 전에 받아 큐에서 기다리던 옛 요약
        assertNull(sim.s.care)
        sim.ev(CareSummary(T0, care(T0), seq = 431, gen = 1))
        assertEquals(care(T0), sim.s.care)
    }
}
