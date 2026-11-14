package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 큐에 든 S06은 꺼낼 때 다시 계산한다 / 뒷문은 바뀔 때만 동작한다.
class QueuedFeedingLineTest {
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

    @Test fun s06_feedingStartMovedOutOfWindow_droppedAtPop() {   // 수유 확인이 feedingStart를 옮긴 것과 같은 상태 변화
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

// 수유 확인 알림 = 그때 쓰는 상태의 확인 투영. 장면 시작은 실제 알림만 내리고(상태 유지), 장면 끝은 장면 확인을 버리고, Boot는 남은 확인을 다시 띄운다.
class FeedingConfirmNoticeTest {
    private val hide = HideNotice(Notice.FEEDING_CONFIRM)

    private fun parked(): Sim {
        val sim = session().route(nurse).ev(DriverAddWaypoint(T0, "n"))
        sim.ev(WaypointResult(sim.t + 500, sim.step<AddWaypoint>().single().cmdId, "n", true))
        return sim.gear(Gear.P, MIN)
    }
    private fun confirmed(): Sim = parked().tick(15 * MIN).also { assertTrue(it.s.ign.nursingStop!!.confirmShown) }

    @Test fun sceneStart_hidesRealConfirmButKeepsState() {
        val sim = confirmed()
        val step = Engine().sceneStart(sim.s, sim.s, sim.t + 100).realStep
        assertTrue(hide in step.commands)
        assertTrue(step.state.ign.nursingStop!!.confirmShown)
    }

    @Test fun sceneStart_noConfirm_noHide() {
        val sim = parked()
        assertNotNull(sim.s.ign.nursingStop!!.firstPAt); assertTrue(!sim.s.ign.nursingStop!!.confirmShown)
        val step = Engine().sceneStart(sim.s, sim.s, sim.t + 100).realStep
        assertTrue(step.commands.none { it is HideNotice })
        assertEquals(sim.s.ign.nursingStop, step.state.ign.nursingStop)
    }

    @Test fun sceneEnd_dropsSceneConfirmAndHides() {
        val sim = confirmed()
        assertTrue(hide in Engine().sceneEnd(sim.s).commands)
    }

    @Test fun boot_reshowsConfirm() {
        val sim = confirmed()
        sim.ev(Boot(sim.t + 1, Gear.P, 10, stopEnded = false))
        assertTrue(sim.s.ign.nursingStop!!.confirmShown)
        assertEquals(Notice.FEEDING_CONFIRM, sim.step<ShowNotice>().single().kind)
    }

    @Test fun boot_stopEnded_hidesNotReshows() {
        val sim = confirmed()
        sim.ev(Boot(sim.t + 1, Gear.P, 10, stopEnded = true))
        assertNull(sim.s.ign.nursingStop)
        assertTrue(hide in sim.cmds)
        assertTrue(sim.step<ShowNotice>().isEmpty())
    }
}
