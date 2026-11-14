package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 돌봄 요약 교체 규칙. 판단 코어는 요약을 저장만 한다.
class CareSummaryTest {
    private fun care(asOf: Long, feeds: Int = 1) = CareView(asOf, true, null, null, null, null, null, null, null, feeds)

    @Test fun newerCareSummaryReplaces() {
        val sim = idle().ev(CareSummary(T0, care(T0 + 1_000, 1)))
        assertEquals(care(T0 + 1_000, 1), sim.s.care)                       // care == null이면 받는다
        sim.ev(CareSummary(T0, care(T0 + 2_000, 2)))
        assertEquals(care(T0 + 2_000, 2), sim.s.care)                       // asOf가 더 늦으면 바꾼다
        assertTrue(sim.cmds.isEmpty())                                      // 할 일·문장·제안은 없다
    }

    @Test fun olderOrSameCareSummaryIgnored() {
        val sim = idle().ev(CareSummary(T0, care(T0 + 2_000, 2)))
        sim.ev(CareSummary(T0, care(T0 + 1_000, 9)))
        sim.ev(CareSummary(T0, care(T0 + 2_000, 7)))                        // 같은 asOf도 무시
        assertEquals(care(T0 + 2_000, 2), sim.s.care)
    }

    @Test fun sameSecondLaterSeqReplaces() {
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

    @Test fun differentSenderHigherSeqReplaces_regardlessOfAsOf() {
        val sim = idle().ev(CareSummary(T0 + 240_000, care(T0 + 240_000, 1), seq = 430, room = "c_A"))
        sim.ev(CareSummary(T0 + 60_000, care(T0 + 60_000, 2), seq = 431, room = "c_A"))
        assertEquals(care(T0 + 60_000, 2), sim.s.care)                                          // 큰 seq는 이른 as_of도 바꾼다(보낸 기기는 보지 않는다)
        sim.ev(CareSummary(T0 + 300_000, care(T0 + 300_000, 3), seq = 429, room = "c_A"))
        assertEquals(care(T0 + 60_000, 2), sim.s.care)                                          // 작은 seq는 늦은 as_of도 버린다
        val inj = idle().ev(CareSummary(T0 + 240_000, care(T0 + 240_000, 1)))                  // seq 없는 장면 주입끼리는 as_of
        inj.ev(CareSummary(T0 + 60_000, care(T0 + 60_000, 2), room = "c_A")); assertEquals(care(T0 + 240_000, 1), inj.s.care)
        inj.ev(CareSummary(T0 + 300_000, care(T0 + 300_000, 3))); assertEquals(care(T0 + 300_000, 3), inj.s.care)
    }

    @Test fun differentRoom_sameSecond_laterReceivedWins_sameRoomComparesSeq() {
        val sim = idle().ev(CareSummary(T0, care(T0, 1), seq = 9000, room = "c_A"))
        sim.ev(CareSummary(T0, care(T0, 2), seq = 2, room = "c_B"))               // 새 방은 seq를 새로 센다
        assertEquals(care(T0, 2), sim.s.care); assertEquals("c_B", sim.s.careRoom); assertEquals(2L, sim.s.careSeq)
        sim.ev(CareSummary(T0, care(T0, 3), seq = 1, room = "c_B"))               // 같은 방은 그대로 seq로 견준다
        assertEquals(care(T0, 2), sim.s.care)
        sim.ev(CareSummary(T0, care(T0, 4), seq = 3, room = "c_B"))
        assertEquals(care(T0, 4), sim.s.care)
    }

    @Test fun doesNotChangeDecisions() {
        val a = idle(feedingStart = T0 - 170 * MIN, prof = profile(interval = 180))
        val b = idle(feedingStart = T0 - 170 * MIN, prof = profile(interval = 180)).ev(CareSummary(T0, care(T0)))
        assertEquals(a.s, b.s.copy(care = null, careSeq = null, careRoom = null))
    }

    @Test fun clearRecords_keepsCareSummary() {
        val sim = idle().ev(CareSummary(T0, care(T0), seq = 7)).ev(ClearRecords(T0))
        assertEquals(care(T0), sim.s.care); assertEquals(7L, sim.s.careSeq)   // 폰 돌봄 요약은 지우지 않는다
        assertEquals(listOf("DeleteRecords", "DeleteRelayRecords", "SaveSnapshot"), sim.cmds.map { it::class.simpleName })
    }

    @Test fun careClearedEvent() {
        val sim = idle().ev(CareSummary(T0, care(T0), seq = 7, room = "c_A"))
        sim.ev(CareCleared(T0))
        assertNull(sim.s.care); assertNull(sim.s.careSeq); assertNull(sim.s.careRoom)
        sim.ev(CareSummary(T0, care(T0 - 1_000)))                                  // 비운 뒤에는 이른 요약도 받는다
        assertEquals(care(T0 - 1_000), sim.s.care)
    }
}
