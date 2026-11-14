package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 모든 기록 지우기.
class ClearRecordsTest {
    private val full = listOf(WN, LL).flatMap { m -> List(3) { FirstTry(T0, m, it == 0) } }

    @Test fun clearRecords_idle_emptiesHistoryAndStores() {
        val sim = idle(history = full, feedingStart = T0).ev(ClearRecords(T0))
        assertTrue(sim.s.history.isEmpty())
        assertEquals(listOf("DeleteRecords", "DeleteRelayRecords", "SaveSnapshot"), sim.cmds.map { it::class.simpleName })
        assertEquals(sim.s, (sim.cmds.last() as SaveSnapshot).state)
        assertEquals(T0, sim.s.feedingStart)                 // 프로필 쪽 값은 남긴다
    }

    @Test fun clearRecords_openSession_flagsCleared() {
        val sim = session(history = full).attempt1().ev(ClearRecords(T0 + 1_000))
        assertTrue(sim.ses.recordsCleared); assertTrue(sim.s.history.isEmpty())
        assertEquals(Phase.ATTEMPT, sim.ses.phase)           // 상태 기계는 이어 간다
        assertEquals(listOf("DeleteRecords", "DeleteRelayRecords", "SaveSnapshot"), sim.cmds.map { it::class.simpleName })
    }

    @Test fun afterClear_noSaveNoHistoryNoReport() {
        val sim = session().ev(ClearRecords(T0)).clear()
        sim.attempt1().toConfirm().closeQuiet()              // 1차 성공
        sim.alarm(MIN).ev(CallMode(sim.t, true)).alarm(1_000) // 기록만 하는 경보
        sim.ev(VehicleSample(sim.t, 40.0, false, 0.0, TurnSignal.NONE)).ev(VehicleSample(sim.t + 61_000, 40.0, false, 0.0, TurnSignal.NONE))
        sim.ev(CallMode(sim.t, false)).gear(Gear.P, MIN).ev(RearDoor(sim.t, Side.LEFT, true))
        val banned = setOf(SaveSession::class, SaveEpisode::class, SavePlay::class, LogAlarm::class, CaptureContext::class, SaveSteerWindow::class, CloseSession::class)
        assertTrue(sim.log.none { it::class in banned }, sim.log.filter { it::class in banned }.toString())
        assertTrue(sim.s.history.isEmpty())
        assertTrue(sim.log.filterIsInstance<SendToPhone>().none { it.type == "report_summary" })
    }

    @Test fun afterClear_roundRobinRestarts() {
        val sim = session(history = full, seed = 3).ev(ClearRecords(T0)).alarm()
        assertEquals(WN, sim.step<StartPlayback>().single().method)
        assertEquals(3L, sim.s.rng)                           // 톰슨 샘플링을 쓰지 않았다
    }
}
