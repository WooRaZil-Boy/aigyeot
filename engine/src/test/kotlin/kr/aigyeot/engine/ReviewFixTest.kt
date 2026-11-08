package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 11-03 검토 수정(명세서 17장 C79~C81).
class ReviewFixTest {
    @Test fun r21a_lostBeforePlaybackStarted_firstUnjudged() {
        val sim = session().alarm()
        sim.ev(InputLost(sim.t, PauseReason.SILENCED))
        sim.started()
        assertEquals(Unjudged.SILENCED, sim.ep.first!!.unjudged)
        assertEquals(TryResult.UNJUDGED, sim.ep.first!!.result)
        sim.ev(InputRestored(sim.t, PauseReason.SILENCED)).toGap().closeQuiet()
        assertEquals(TryResult.UNJUDGED, sim.s.session?.episode?.first?.result ?: TryResult.UNJUDGED)
        assertTrue(sim.s.history.isEmpty())
    }

    @Test fun r01a_micPathKeptFromSessionStart() {
        val sim = session()
        assertEquals(MicPath.HOME_BUTTON, sim.all<SaveSession>().last().record.micPath)
        sim.ev(MicService(sim.t, false, null))
        assertEquals(null, sim.s.car.micPath)
        sim.clear().gear(Gear.P).ev(RearDoor(sim.t, Side.LEFT, true))   // 13a: 세션 닫힘
        assertEquals(MicPath.HOME_BUTTON, sim.all<SaveSession>().last().record.micPath)
    }

    @Test fun r21h_lostWhileIdle_nextSessionInheritsPause() {
        val sim = idle().ev(InputLost(T0, PauseReason.SILENCED)).doorCycle().gear(Gear.D)
        assertEquals(setOf(PauseReason.SILENCED), sim.s.car.pauses)
        sim.alarm()
        assertTrue(sim.step<StartPlayback>().isEmpty())      // 판정 멈춤 중
        sim.ev(InputRestored(sim.t, PauseReason.SILENCED))
        assertTrue(sim.s.car.pauses.isEmpty())
    }

    @Test fun r21h_pausesCarriedOverWhenSessionCloses() {
        val sim = session().ev(InputLost(T0, PauseReason.SILENCED))
        sim.gear(Gear.P).ev(RearDoor(sim.t, Side.LEFT, true))   // 13a
        assertEquals(null, sim.s.session)
        assertEquals(setOf(PauseReason.SILENCED), sim.s.car.pauses)
        sim.ev(RearDoor(sim.t, Side.LEFT, false)).gear(Gear.D)
        assertEquals(setOf(PauseReason.SILENCED), sim.s.car.pauses)
        assertTrue(sim.s.session != null)
    }

    @Test fun r42a_hotAtStartCoolBeforeStarted_keepsComfort() {
        val sim = session().ev(CabinTemp(T0, 27.6)).attempt1().toGap().recry()
        assertEquals(WN, sim.step<StartPlayback>().single().method)
        sim.ev(CabinTemp(sim.t, 20.0)).started()
        assertEquals(SecondAction.COMFORT, sim.ep.second!!.action)
        assertTrue(Sid.S04 !in sim.sids() && Sid.S03 !in sim.sids())   // S04는 결과가 온 뒤(C102)
        assertTrue(sim.step<LowerHvac>().size == 1)
    }

    @Test fun r42c_coolAtStartHotBeforeStarted_keepsOther() {
        val sim = session().ev(CabinTemp(T0, 20.0)).attempt1().toGap().recry()
        assertEquals(LL, sim.step<StartPlayback>().single().method)
        sim.ev(CabinTemp(sim.t, 30.0)).started()
        assertEquals(SecondAction.OTHER, sim.ep.second!!.action)
        assertTrue(Sid.S03 in sim.sids() && Sid.S04 !in sim.sids())
        assertTrue(sim.all<LowerHvac>().isEmpty())
    }
}

// 명세서 17장 C83: 경유지 요청 중복 방지.
class WaypointDupTest {
    private val n = rest("n", 12.0, nursing = true)

    @Test fun h4c_yesWhileWaypointPendingOrAdded_noSecondRequest() {
        val sim = session().route(n).ev(Tick(T0 + 120 * MIN))
        val ask = sim.step<Ask>().single()
        sim.ev(Answer(sim.t, ask.askId, YesNo.YES))
        val add = sim.step<AddWaypoint>().single()
        // 결과 전 인계 제안: 같은 쉼터는 제안하지 않는다
        sim.handoff()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertTrue(sim.all<Ask>().size == 1)
        assertEquals(add.cmdId, sim.ses.waypointPending!!.cmdId)
        // 결과가 오면 첫 요청이 반영된다
        sim.ev(WaypointResult(sim.t, add.cmdId, "n", true))
        assertEquals(true, sim.ses.restLog[0].waypointOk)
        assertEquals(setOf("n"), sim.ses.addedRestIds)
        assertEquals(1, sim.all<AddWaypoint>().size)
    }

    @Test fun h4c_afterAdded_noReoffer() {
        val sim = session().route(n).ev(Tick(T0 + 120 * MIN))
        sim.ev(Answer(sim.t, sim.step<Ask>().single().askId, YesNo.YES))
        sim.ev(WaypointResult(sim.t, sim.step<AddWaypoint>().single().cmdId, "n", true))
        sim.handoff()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertEquals(1, sim.all<Ask>().size)
        assertEquals(1, sim.all<AddWaypoint>().size)
    }
}

// 명세서 17장 C84.
class WaypointRecoveryTest {
    private val r = rest("a", 12.0)

    private fun pending(): Sim {
        val sim = session().route(r).handoff().flush()
        val ask = sim.all<Ask>().last()
        sim.ev(Answer(sim.t, ask.askId, YesNo.YES))
        assertTrue(sim.ses.waypointPending != null)
        return sim
    }

    @Test fun r14g_bootClearsWaypointPending() {
        val sim = pending()
        sim.ev(Boot(sim.t + MIN, Gear.D, 10))
        assertEquals(null, sim.ses.waypointPending)
        assertEquals(null, sim.ses.restLog[0].waypointOk)
        sim.ev(Tick(T0 + 120 * MIN))
        assertEquals(Sid.S11, sim.step<Ask>().single().sid)    // 같은 쉼터를 다시 제안할 수 있다
    }

    @Test fun r14g_resumeKeepsWaypointPending() {
        val sim = pending()
        sim.ev(Resume(sim.t + MIN))
        assertTrue(sim.ses.waypointPending != null)
    }

    @Test fun l1_skippedOffer_retriesAfterWaypointFails() {
        val sim = pending()
        sim.ev(Tick(T0 + 120 * MIN))
        assertTrue(sim.step<Ask>().isEmpty())
        assertEquals(false, sim.ses.longDriveOffered)
        sim.ev(WaypointResult(sim.t, sim.ses.waypointPending!!.cmdId, "a", false))
        sim.ev(Tick(sim.t + 1_000)).flush()
        assertEquals(1, sim.all<Ask>().count { it.sid == Sid.S11 })
        assertTrue(sim.ses.longDriveOffered)
        sim.ev(Tick(sim.t + 1_000)).flush()
        assertEquals(1, sim.all<Ask>().count { it.sid == Sid.S11 })
    }
}

class LostWindowLockTest {
    private val L = Area.ROW_2_LEFT
    private val R = Area.ROW_2_RIGHT
    private fun result(sim: Sim, c: SetWindowLock, a: Area, before: Double = 0.0) =
        sim.ev(ControlResult(sim.t, c.cmdId, ControlKind.WINDOW_LOCK, a, true, before, 1.0))

    @Test fun r14g_bootDropsLostWindowLocks_nextSessionLocks() {
        val sim = session(prof = profile(sibling = true))
        sim.step<SetWindowLock>().single()                       // 결과는 오지 않는다
        sim.ev(Boot(sim.t + MIN, Gear.D, 10))                    // 옛 명령은 버리고 A의 두 영역을 다시 보낸다
        sim.step<SetWindowLock>().forEach { c -> c.values.keys.forEach { result(sim, c, it) } }
        sim.clear()
        sim.gear(Gear.P, MIN)                                     // A 닫힘(13a)
        sim.all<SetWindowLock>().forEach { c -> c.values.keys.forEach { result(sim, c, it, 1.0) } }
        sim.clear().doorCycle().gear(Gear.D)                      // B 열림
        val b = sim.all<SetWindowLock>().flatMap { it.values.keys }
        assertEquals(setOf(L, R), b.toSet())
        assertEquals(2, b.size)
    }

    @Test fun r14g_bootRelocksOpenSessionArea() {
        val sim = session(prof = profile(sibling = true))
        val old = sim.step<SetWindowLock>().single()
        sim.ev(Boot(sim.t + MIN, Gear.D, 10))
        assertNotNull(sim.ses)
        val locks = sim.step<SetWindowLock>()
        assertEquals(setOf(L, R), locks.flatMap { it.values.keys }.toSet())
        assertTrue(locks.all { it.cmdId != old.cmdId && it.values.values.all { v -> v } })
        result(sim, locks.first(), locks.first().values.keys.first(), 0.0)
        assertEquals(mapOf(locks.first().values.keys.first() to false), sim.ses.windowLockBefore)
    }

    @Test fun r14g_resumeKeepsWindowLockPending() {
        val sim = session(prof = profile(sibling = true))
        val before = sim.s.ign.windowLockPending
        assertTrue(before.isNotEmpty())
        sim.ev(Resume(sim.t + MIN))
        assertEquals(before, sim.s.ign.windowLockPending)
    }
}

class LongDriveSpokenTest {
    private val r = rest("a", 12.0)
    private fun count(sim: Sim) = sim.all<Ask>().count { it.sid == Sid.S11 } +
        sim.s.speech.queue.count { it.sid == Sid.S11 && it.sid != Sid.S02 }

    /** S02를 말하는 중에 120분이 되어 S11이 큐에서 기다리는 상태. */
    private fun queued(): Sim {
        val sim = session().route(r).ev(Tick(T0 + 119 * MIN + 30_000)).attempt1()
        sim.ev(Tick(T0 + 120 * MIN))
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S11 })
        assertFalse(sim.ses.longDriveOffered)
        return sim
    }

    @Test fun l1_queuedOffer_notDuplicatedByTicks() {
        val sim = queued()
        repeat(5) { sim.ev(Tick(sim.t + 1_000)) }
        assertEquals(1, sim.s.speech.queue.count { it.sid == Sid.S11 })
    }

    @Test fun l1_droppedByCall_retriesOnceAfterCall() {
        val sim = queued()
        sim.ev(CallMode(sim.t, true))
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S11 })
        assertFalse(sim.ses.longDriveOffered)
        sim.ev(CallMode(sim.t, false)).ev(Tick(sim.t + 1_000)).flush()
        assertEquals(1, sim.all<Ask>().count { it.sid == Sid.S11 })
        assertTrue(sim.ses.longDriveOffered)
        sim.ev(Tick(sim.t + 1_000)).flush()
        assertEquals(1, sim.all<Ask>().count { it.sid == Sid.S11 })
    }

    @Test fun l1_spoken_notOfferedAgain() {
        val sim = queued().flush()
        assertTrue(sim.ses.longDriveOffered)
        sim.ev(Tick(sim.t + MIN)).flush()
        assertEquals(1, sim.all<Ask>().count { it.sid == Sid.S11 })
    }
}

// 명세서 17장 C89. 꺼낼 때도 쉼터를 묻는 줄은 canAddWaypoint를 본다.
class QueuedOfferDropTest {
    private val n = rest("n", 12.0, nursing = true)

    @Test fun waypointQueuedOffer_droppedAfterAdded() {
        // S02를 말하는 중에 2시간 제안(S11)이 큐에 서고, 이어 인계의 쉼터 제안(S07)이 같은 쉼터로 큐에 선다
        val sim = session().route(n).ev(Tick(T0 + 119 * MIN + 30_000)).attempt1()
        sim.ev(Tick(T0 + 120 * MIN)).toGap().recry().started().toGap().recry()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        val q = sim.s.speech.queue
        assertEquals(1, q.count { it.askRestId == "n" }, "C273: 인계가 같은 쉼터를 묻는 S11 줄을 넘겨받는다(두 줄을 두지 않는다)")
        assertTrue(q.single { it.askRestId == "n" }.let { it.handoff && it.longDrive })
        // 그 제안에 응해 경유지가 들어간다
        sim.done()
        sim.ev(Tick(sim.t + P.speechGapMs))
        val ask = sim.step<Ask>().single()
        sim.ev(Answer(sim.t, ask.askId, YesNo.YES))
        sim.ev(WaypointResult(sim.t, sim.step<AddWaypoint>().single().cmdId, "n", true))
        assertEquals(setOf("n"), sim.ses.addedRestIds)
        assertEquals(0, sim.s.speech.queue.drop(if (sim.s.speech.speaking != null) 1 else 0).count { it.askRestId == "n" })
        sim.flush()
        assertEquals(1, sim.all<Ask>().count { it.sid == Sid.S07 || it.sid == Sid.S11 })
    }
}

class OfferDupSidTest {
    @Test fun s3_sameSidQueued_notDoubled() {
        // 쉼터 없음: 2시간 S10을 큐에 둔 채 다른 S10 제안이 와도 한 번만 나온다
        val sim = session().ev(Tick(T0 + 119 * MIN + 30_000)).attempt1()
        sim.ev(Tick(T0 + 120 * MIN))
        assertEquals(1, sim.s.speech.queue.count { it.sid == Sid.S10 })
        sim.toGap().recry().started().toGap().recry()           // 인계: 쉼터 없음 S10(H7)
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertEquals(1, sim.s.speech.queue.count { it.sid == Sid.S10 })
        sim.flush()
        assertEquals(1, sim.all<Speak>().count { it.sid == Sid.S10 })
    }
}

class OutputRouteTest {
    private fun route(sim: Sim): OutputRoute? {
        sim.gear(Gear.P).ev(RearDoor(sim.t, Side.LEFT, true))
        return sim.all<SaveSession>().last().record.outputRoute
    }

    @Test fun r13_noPlayback_outputRouteNull() = assertEquals(null, route(session()))

    @Test fun r13_rearPlayback_rearZone() = assertEquals(OutputRoute.REAR_ZONE, route(session().attempt1()))

    @Test fun r13_frontFallback_defaultFallback() =
        assertEquals(OutputRoute.DEFAULT_FALLBACK, route(session().alarm().started(fallback = true)))

    @Test fun r14y_longDriveWaitsForRouteAfterRestore() {
        fun restored(): Sim {
            val sim = session()
            sim.s = sim.s.copy(bootAt = T0 + 120 * MIN + 30_000, car = sim.s.car.copy(route = null))
            return sim
        }
        // 60초 안에 경로가 오면 그 경로로 판단한다: 도착 임박이라 제안하지 않는다
        val a = restored()
        a.ev(Tick(T0 + 121 * MIN))
        assertTrue(a.sids().isEmpty(), "경로를 기다린다")
        a.ev(RouteState(T0 + 121 * MIN + 5_000, true, 300, listOf(rest("a", 10.0)), 5.0))
        assertTrue(a.sids().isEmpty(), "도착 임박 경로로 판단")
        // 안 오면 60초 뒤 경로 없이 본다
        val b = restored()
        b.ev(Tick(T0 + 121 * MIN))
        assertTrue(b.sids().isEmpty())
        b.ev(Tick(T0 + 120 * MIN + 90_000))
        assertTrue(Sid.S10 in b.sids(), "60초가 지나면 쉼터 없음 S10")
    }
}
