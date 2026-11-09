package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 5.1 세션 열기·닫기(3-1·3-2·3-3·3-13).
class S51SessionTest {
    private val L = Area.ROW_2_LEFT
    private val R = Area.ROW_2_RIGHT

    private fun lockResult(sim: Sim, cmd: SetWindowLock, area: Area, before: Double? = 0.0, ok: Boolean = true) =
        sim.ev(ControlResult(sim.t, cmd.cmdId, ControlKind.WINDOW_LOCK, area, ok, before, if (cmd.values[area] == true) 1.0 else 0.0))

    @Test fun r01a_doorCycleThenDrive_opensSession() {
        val sim = session()
        assertEquals(Phase.LISTENING, sim.ses.phase)
        assertEquals(OpenTrigger.DOOR_THEN_D, sim.ses.trigger)
        assertEquals(MicPath.HOME_BUTTON, sim.all<SaveSession>().single().record.micPath)
    }

    @Test fun r01b_reservedThenDrive_opensSession() {
        val sim = idle().ev(BabyAboard(T0, BabyAboardFrom.HOME)).gear(Gear.D)
        assertEquals(OpenTrigger.BUTTON_THEN_D, sim.ses.trigger)
        assertFalse(sim.s.ign.reserved)
    }

    @Test fun r01c_babyAboardInPark_onlyReserves() {
        val sim = idle().ev(BabyAboard(T0, BabyAboardFrom.NOTICE))
        assertNull(sim.s.session)
        assertTrue(sim.s.ign.reserved)
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun r01d_noProfile_staysIdle() {
        val sim = idle(prof = null).doorCycle().gear(Gear.D)
        assertNull(sim.s.session)
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun r01e_micOffAtOpen_saysS17Once() {
        val sim = session(mic = false)
        assertTrue(sim.ses.s17Said)
        assertTrue(sim.ses.micNoticeArmed)
        sim.gear(Gear.P, MIN).gear(Gear.D, MIN).tick(MIN)
        assertEquals(1, sim.sids().count { it == Sid.S17 })
    }

    @Test fun r01f_micOffNextPark_showsNotice() {
        val sim = session(mic = false).gear(Gear.P, MIN)
        assertEquals(listOf(Notice.BABY_ABOARD), sim.step<ShowNotice>().map { it.kind })
        assertTrue(sim.step<Speak>().none { it.sid == Sid.S16 })
    }

    @Test fun r02a_doorClosedParkMicOff_saysS16() {
        val sim = idle(mic = false).doorCycle()
        assertTrue(sim.s.ign.s16Said)
        assertEquals(Sid.S16, sim.step<Speak>().single().sid)
        assertEquals(Notice.BABY_ABOARD, sim.step<ShowNotice>().single().kind)
    }

    @Test fun r02b_s16_oncePerIgnition() {
        val sim = idle(mic = false).doorCycle().done().doorCycle()
        assertTrue(sim.cmds.isEmpty())
        assertEquals(1, sim.sids().count { it == Sid.S16 })
    }

    @Test fun r03a_sessionStart_turnsOffBabySeatHeat() {
        val sim = idle().ev(SeatHeat(T0, L, 2)).ev(SeatHeat(T0, Area.ROW_1_LEFT, 3)).doorCycle().gear(Gear.D)
        assertEquals(listOf(SetSeatHeat(sim.step<SetSeatHeat>().single().cmdId, listOf(L), 0)), sim.step<SetSeatHeat>())
    }

    @Test fun r03b_sibling_locksBothRearWindows() {
        val sim = session(prof = profile(sibling = true))
        val lock = sim.step<SetWindowLock>().single()
        assertEquals(mapOf(L to true, R to true), lock.values)
        assertEquals(mapOf(lock.cmdId to setOf(L, R)), sim.s.ign.windowLockPending.single().locking)
    }

    @Test fun r03c_seatHeatOnDuringSession_turnsOff() {
        val sim = session().ev(SeatHeat(T0, L, 1))
        val off = sim.step<SetSeatHeat>().single()
        assertEquals(listOf(L), off.areas); assertEquals(0, off.level)
        sim.ev(SeatHeat(T0, Area.ROW_1_LEFT, 1))
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun r03d_seatHeatFail_showsSafetyNotice() {
        val sim = session().ev(ControlResult(T0, "x", ControlKind.SEAT_HEAT, L, false, 1.0, 1.0))
        assertEquals(Notice.SAFETY_CONTROL_FAILED, sim.step<ShowNotice>().single().kind)
    }

    @Test fun r03e_lockResult_storesBefore() {
        val sim = session(prof = profile(sibling = true))
        val lock = sim.step<SetWindowLock>().single()
        lockResult(sim, lock, L, 0.0)
        assertEquals(mapOf(L to false), sim.ses.windowLockBefore)
        lockResult(sim, lock, R, 1.0)
        assertEquals(mapOf(L to false, R to true), sim.ses.windowLockBefore)
        assertTrue(sim.s.ign.windowLockPending.isEmpty())
        assertTrue(sim.cmds.isEmpty())
    }

    @Test fun r03f_lockResultAfterSessionEnd_restoresReturnedBefore() {
        val sim = session(prof = profile(sibling = true))
        val lock = sim.step<SetWindowLock>().single()
        sim.gear(Gear.P, MIN).ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session)
        assertTrue(sim.step<SetWindowLock>().isEmpty())   // 아직 받은 before가 없다
        lockResult(sim, lock, L, 0.0)
        val restore = sim.step<SetWindowLock>().single()
        assertEquals(mapOf(L to false), restore.values)
        lockResult(sim, lock, R, ok = false)               // 잠기지 않았으니 복원 없음
        assertTrue(sim.step<SetWindowLock>().isEmpty())
        assertEquals(mapOf(restore.cmdId to setOf(L)), sim.s.ign.windowLockPending.single().restoring)
    }

    @Test fun r03f_restoreCmdId_belongsToLockingSession_inIdle() {   // C323(P5)
        val sim = session(prof = profile(sibling = true))
        val idA = sim.ses.id
        val lock = sim.step<SetWindowLock>().single()
        sim.gear(Gear.P, MIN).ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session)
        lockResult(sim, lock, L, 0.0)
        assertTrue(sim.step<SetWindowLock>().single().cmdId.startsWith("$idA-"))
    }

    @Test fun r03f_restoreCmdId_belongsToLockingSession_whenNextSessionOpen() {   // C323(P5)
        val sim = session(prof = profile(sibling = true))
        val idA = sim.ses.id
        val lock = sim.step<SetWindowLock>().single()
        sim.gear(Gear.P, MIN).doorCycle().gear(Gear.D)
        assertTrue(sim.ses.id != idA)
        lockResult(sim, lock, L, 0.0)
        assertTrue(sim.step<SetWindowLock>().single().cmdId.startsWith("$idA-"))
    }

    @Test fun r03g_restoreResult_releasesQueuedLockOfNextSession() {
        val sim = session(prof = profile(sibling = true))
        val lockA = sim.step<SetWindowLock>().single()
        sim.gear(Gear.P, MIN).doorCycle().gear(Gear.D)            // A 닫힘 → B 열림
        assertTrue(sim.step<SetWindowLock>().isEmpty())
        assertEquals(setOf(L, R), sim.s.ign.windowLockPending.last().queued)
        lockResult(sim, lockA, L, 0.0)                             // 3f: A 복원
        val restoreL = sim.step<SetWindowLock>().single()
        lockResult(sim, restoreL, L)                               // 3g: 풀림 → B 왼쪽 잠금
        assertEquals(mapOf(L to true), sim.step<SetWindowLock>().single().values)
    }

    @Test fun r03h_twoSessionsOverlap_restoresInOrder() {
        val sim = session(prof = profile(sibling = true))
        val lockA = sim.step<SetWindowLock>().single()
        sim.gear(Gear.P, MIN).doorCycle().gear(Gear.D)
        sim.clear()
        lockResult(sim, lockA, L, 0.0); lockResult(sim, lockA, R, 0.0)
        val (restA1, restA2) = sim.all<SetWindowLock>()
        lockResult(sim, restA1, L); lockResult(sim, restA2, R)
        val locksB = sim.all<SetWindowLock>().drop(2)
        assertEquals(listOf(mapOf(L to true), mapOf(R to true)), locksB.map { it.values })
        locksB.forEach { lockResult(sim, it, it.values.keys.single(), 0.0) }   // B가 읽은 before = A가 복원한 값
        sim.gear(Gear.P, MIN).ev(RearDoor(sim.t, Side.RIGHT, true))
        val restB = sim.all<SetWindowLock>().last()
        assertEquals(mapOf(L to false, R to false), restB.values)
        lockResult(sim, restB, L); lockResult(sim, restB, R)
        assertEquals(listOf(false, false, true, true, false), sim.all<SetWindowLock>().map { it.values.values.first() })
        assertTrue(sim.s.ign.windowLockPending.isEmpty())
    }

    @Test fun r03i_twoLockCmdsInFlight_eachResultTracked() {
        val sim = session(prof = profile(sibling = true))
        val lockA = sim.step<SetWindowLock>().single()
        sim.gear(Gear.P, MIN).doorCycle().gear(Gear.D)
        lockResult(sim, lockA, L, 0.0); val restL = sim.step<SetWindowLock>().single()
        lockResult(sim, lockA, R, 0.0); val restR = sim.step<SetWindowLock>().single()
        lockResult(sim, restL, L); val lockBL = sim.step<SetWindowLock>().single()
        lockResult(sim, restR, R); val lockBR = sim.step<SetWindowLock>().single()
        val b = sim.s.ign.windowLockPending.single()
        assertEquals(mapOf(lockBL.cmdId to setOf(L), lockBR.cmdId to setOf(R)), b.locking)
        lockResult(sim, lockBR, R, 1.0)                            // 순서가 바뀌어 와도 각자 찾는다
        assertEquals(mapOf(R to true), sim.ses.windowLockBefore)
        sim.gear(Gear.P, MIN).ev(RearDoor(sim.t, Side.LEFT, true))  // 왼쪽 결과 전에 B 닫힘
        assertEquals(mapOf(R to true), sim.step<SetWindowLock>().single().values)
        lockResult(sim, lockBL, L, 0.0)                             // 3f로 왼쪽 복원
        assertEquals(mapOf(L to false), sim.step<SetWindowLock>().single().values)
    }

    @Test fun r13a_doorOpenAfterPark_reportAndIdle() {
        val sim = session().gear(Gear.P, 10 * MIN)
        val id = sim.ses.id
        sim.ev(RearDoor(sim.t, Side.RIGHT, true))
        assertNull(sim.s.session)
        val ended = sim.step<SaveSession>().last().record
        assertEquals(sim.t, ended.endedAt)
        assertTrue(ended.reportPending)
        assertEquals(listOf(CloseSession(id)), sim.step<CloseSession>())
        assertEquals("feeding_state", sim.step<SendToPhone>().single().type)
    }

    // ---- C335: 리포트(CloseSession)는 뒷창 잠금·되돌림 결과를 기다린 뒤 한 번 ----
    /** 형제 프로필 세션에서 두 영역 잠금 결과를 받고(before 닫힘) P·뒷문 열림으로 닫는다. 되돌림 명령을 돌려준다. */
    private fun closedWithRestore(): Triple<Sim, String, SetWindowLock> {
        val sim = session(prof = profile(sibling = true))
        val id = sim.ses.id
        val lock = sim.step<SetWindowLock>().single()
        lockResult(sim, lock, L, 0.0); lockResult(sim, lock, R, 0.0)
        sim.gear(Gear.P, MIN).clear().ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session)
        return Triple(sim, id, sim.step<SetWindowLock>().single())
    }

    @Test fun c335_report_waitsForRestoreResult() {
        val (sim, id, restore) = closedWithRestore()
        assertTrue(sim.step<SaveSession>().last().record.reportPending)
        assertTrue(sim.step<CloseSession>().isEmpty(), "되돌림 결과 전에는 리포트 없음")
        lockResult(sim, restore, L)
        assertTrue(sim.step<CloseSession>().isEmpty())
        lockResult(sim, restore, R)
        assertEquals(listOf(CloseSession(id)), sim.step<CloseSession>())
        assertEquals(1, sim.all<CloseSession>().size)
    }

    @Test fun c335_report_afterRestoreFailure_once() {
        val (sim, id, restore) = closedWithRestore()
        lockResult(sim, restore, L, ok = false)
        assertTrue(sim.step<CloseSession>().isEmpty())
        lockResult(sim, restore, R)
        assertEquals(listOf(CloseSession(id)), sim.step<CloseSession>())   // control_log 줄은 실행기가 결과보다 먼저 쓴다(Controls.finish)
        assertEquals(1, sim.all<CloseSession>().size)
    }

    @Test fun c335_report_waitsThroughLockingThenRestoring_3f3g() {
        val sim = session(prof = profile(sibling = true))
        val id = sim.ses.id
        val lock = sim.step<SetWindowLock>().single()
        sim.gear(Gear.P, MIN).clear().ev(RearDoor(sim.t, Side.LEFT, true))   // 잠금 결과 전에 닫힘
        assertTrue(sim.all<CloseSession>().isEmpty(), "잠금 결과 대기 중")
        lockResult(sim, lock, L, 0.0)                                        // 3f: 되돌림
        val restore = sim.step<SetWindowLock>().single()
        assertTrue(sim.all<CloseSession>().isEmpty())
        lockResult(sim, lock, R, ok = false)                                 // 잠금 실패: 되돌림 결과가 남았다
        assertTrue(sim.all<CloseSession>().isEmpty())
        lockResult(sim, restore, L)                                          // 3g
        assertEquals(listOf(CloseSession(id)), sim.all<CloseSession>())
    }

    @Test fun c335_report_immediateWhenNothingLocked() {
        val sim = session(prof = profile(sibling = true))
        val lock = sim.step<SetWindowLock>().single()
        lockResult(sim, lock, L, ok = false); lockResult(sim, lock, R, ok = false)
        val id = sim.ses.id
        sim.gear(Gear.P, MIN).clear().ev(RearDoor(sim.t, Side.LEFT, true))
        assertEquals(listOf(CloseSession(id)), sim.step<CloseSession>())
    }

    @Test fun c335_report_afterBootDropsRestoreWait_c86() {
        val (sim, id, _) = closedWithRestore()
        assertTrue(sim.all<CloseSession>().isEmpty())
        sim.ev(Boot(sim.t + MIN, Gear.P, 10))
        assertEquals(listOf(CloseSession(id)), sim.step<CloseSession>())
        assertTrue(sim.s.ign.windowLockPending.isEmpty())
    }

    @Test fun c335_report_ofOldSessionAfterNextSessionOpens() {
        val sim = session(prof = profile(sibling = true))
        val idA = sim.ses.id
        val lockA = sim.step<SetWindowLock>().single()
        sim.gear(Gear.P, MIN).doorCycle().gear(Gear.D)
        assertTrue(sim.ses.id != idA)
        assertTrue(sim.all<CloseSession>().isEmpty())
        lockResult(sim, lockA, L, 0.0)
        val restL = sim.step<SetWindowLock>().single()
        lockResult(sim, lockA, R, ok = false)
        assertTrue(sim.all<CloseSession>().isEmpty())
        lockResult(sim, restL, L)
        assertEquals(listOf(CloseSession(idA)), sim.all<CloseSession>(), "옛 세션 id로, 새 세션이 열려 있어도")
    }

    @Test fun r13b_longParkWithoutDoor_keepsSession() {
        val sim = session().gear(Gear.P, 10 * MIN).done(1_000).clear()
        repeat(6) { sim.tick(10 * MIN) }
        assertNotNull(sim.s.session)
        assertTrue(sim.all<CloseSession>().isEmpty())
        assertNull(sim.ses.driveFrom)   // 4.5 15분 초기화는 그대로
    }

    @Test fun r13c_reportRestoresOnlyWindowLock() {
        val sim = idle(prof = profile(sibling = true)).ev(SeatHeat(T0, L, 2)).doorCycle().gear(Gear.D)
        val lock = sim.step<SetWindowLock>().single()
        lockResult(sim, lock, L, 0.0); lockResult(sim, lock, R, 0.0)
        sim.ev(CabinTemp(sim.t, 28.0)).attempt2()                 // 2차에서 공조 낮춤
        assertTrue(sim.all<LowerHvac>().isNotEmpty())
        sim.gear(Gear.P, MIN).clear().ev(RearDoor(sim.t, Side.LEFT, true))
        assertEquals(mapOf(L to false, R to false), sim.step<SetWindowLock>().single().values)
        assertTrue(sim.step<SetSeatHeat>().isEmpty() && sim.step<LowerHvac>().isEmpty() && sim.step<SetReadingLight>().isEmpty())
    }

    @Test fun r13d_closeSession_afterEndedAtSaved() {
        val sim = session().gear(Gear.P, MIN).attempt1()          // P에서 시작한 1차(5a) 재생 중
        val from = sim.log.size
        sim.ev(RearDoor(sim.t + 10_000, Side.LEFT, true))
        assertTrue(sim.all<CloseSession>().isEmpty(), "AM12: 낮춤 결과를 기다리는 동안은 리포트를 미룬다")
        sim.dimSkipped()
        val c = sim.log.drop(from)
        val close = c.indexOfFirst { it is CloseSession }
        val play = c.indexOfFirst { it is SavePlay }
        val epEnd = c.indexOfLast { it is SaveEpisode }
        val sesEnd = c.indexOfLast { it is SaveSession }
        assertTrue(play in 0 until close && epEnd in 0 until close && sesEnd in 0 until close)
        assertEquals(PlayEndReason.SESSION_END, (c[play] as SavePlay).record.endReason)
        assertNotNull((c[epEnd] as SaveEpisode).record.endedAt)
        assertEquals(Unjudged.PARKED_ALARM, (c[epEnd] as SaveEpisode).record.firstUnjudged)
        assertNotNull((c[sesEnd] as SaveSession).record.endedAt)
    }
}
