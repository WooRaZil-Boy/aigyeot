package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// C323: 큐에 든 S06은 꺼낼 때 다시 계산한다 / 뒷문은 바뀔 때만 동작한다.
class C323Test {
    /** 수유 시간대에서 울음 → S02 말하는 중, S06이 큐에서 8초 간격을 기다린다. */
    private fun waitingS06(): Sim {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).attempt1().done()
        assertEquals(listOf(Sid.S06), sim.s.speech.queue.map { it.sid }.filter { it == Sid.S06 })
        return sim
    }

    private fun Sim.tickToSpeak() = ev(Tick(s.speech.lastDoneAt!! + P.speechGapMs))
    private fun Sim.spokeS06() = log.filterIsInstance<Speak>().any { it.sid == Sid.S06 }

    @Test fun s06_phoneFeedingStartedWhileQueued_notSpoken() {
        val sim = waitingS06().ev(FeedingStarted(T0 + 1, T0 + 1, FeedingFrom.PHONE, "m1"))
        sim.tickToSpeak()
        assertFalse(sim.spokeS06()); assertTrue(sim.s.speech.queue.none { it.sid == Sid.S06 })
        assertFalse(sim.ep.s06Said)
    }

    @Test fun s06_carScreenFeedingStartedWhileQueued_notSpoken() {
        val sim = waitingS06().ev(FeedingStarted(T0 + 1, T0 + 1, FeedingFrom.CAR_SCREEN, null))
        sim.tickToSpeak()
        assertFalse(sim.spokeS06())
    }

    @Test fun s06_feedingStartMovedOutOfWindow_droppedAtPop() {   // 수유 확인(F6)이 feedingStart를 옮긴 것과 같은 상태 변화
        val sim = waitingS06()
        sim.s = sim.s.copy(feedingStart = sim.t - MIN)
        sim.tickToSpeak()
        assertFalse(sim.spokeS06())
    }

    @Test fun s06_timePassesWhileHeld_popsWithNewElapsed() {
        val sim = waitingS06()
        val old = sim.s.speech.queue.first { it.sid == Sid.S06 }.vars["elapsed"]
        sim.ev(Tick(sim.t + 20 * MIN))
        val spoken = sim.log.filterIsInstance<Speak>().last { it.sid == Sid.S06 }
        assertNotEquals(old, spoken.vars["elapsed"])
        assertEquals(hm(sim.t - (T0 - 150 * MIN)), spoken.vars["elapsed"])
    }

    @Test fun s06_restoredQueuedLine_popsWithNewElapsed_orDroppedOutsideWindow() {
        val sim = waitingS06()
        val old = sim.s.speech.queue.first { it.sid == Sid.S06 }.vars["elapsed"]
        val at = sim.t + 20 * MIN
        val ok = Sim(Engine().afterRestore(sim.s, at, micAlive = true))
        ok.ev(Tick(at + P.speechGapMs))
        val spoken = ok.log.filterIsInstance<Speak>().single { it.sid == Sid.S06 }
        assertNotEquals(old, spoken.vars["elapsed"])
        val out = Sim(Engine().afterRestore(sim.s.copy(feedingStart = at - MIN), at, micAlive = true))
        out.ev(Tick(at + P.speechGapMs))
        assertFalse(out.spokeS06())
    }

    // ---- 뒷문: 바뀔 때만 ----

    @Test fun door_closedValueNeverOpened_noS16() {
        val sim = idle(mic = false).ev(RearDoor(T0, Side.LEFT, false))
        assertFalse(sim.s.ign.s16Said)
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun door_openValueOnAlreadyOpenSide_noCloseSessionNoFlagChange() {
        val sim = idle()
        sim.s = sim.s.copy(car = sim.s.car.copy(rearDoorOpen = setOf(Side.LEFT)))   // 이미 열린 쪽, doorOpenedSinceP는 거짓
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertFalse(sim.s.ign.doorOpenedSinceP)   // 같은 값: 아무것도 하지 않는다
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun door_repeatedOpenWithSession_doesNotCloseSession() {
        val sim = session()                          // D, 세션 열림, 문 닫힘
        sim.s = sim.s.copy(car = sim.s.car.copy(rearDoorOpen = setOf(Side.LEFT), gear = Gear.P))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))      // 이미 열림: 사실 맞추기뿐
        assertTrue(sim.s.session != null)
        assertTrue(sim.cmds.none { it is CloseSession })
    }
}
