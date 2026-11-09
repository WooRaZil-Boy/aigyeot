package kr.aigyeot.engine

import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 명세서 17장 C150~C153(11-07 영역별 검토 24회차).
class C150ToC153Test {
    private val nurse = rest("n", 9.0, nursing = true)
    private fun Sim.alerts() = all<SendToPhone>().filter { it.type == "handoff_alert" }
    private fun Sim.busyNow() = ev(VehicleSample(t, 60.0, true, 0.0, TurnSignal.NONE))

    private fun restored(): Sim {
        val sim = session()
        sim.s = sim.s.copy(bootAt = T0 + 120 * MIN + 30_000, car = sim.s.car.copy(route = null))
        return sim
    }

    // ---- C150: 복원 뒤 경로 기다림 동안 쉼터 질문을 버리지 않는다 ----
    private fun restoredWithS07(vararg extra: Line): Sim {
        val sim = session().route(rest("n", 14.0, nursing = true))
        val q = Line(Sid.S07, mapOf("restName" to "n 휴게소", "distanceKm" to "14"), "n", queuedAt = sim.t, sessionId = sim.ses.id)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = listOf(q) + extra), bootAt = sim.t, car = sim.s.car.copy(route = null))
        return sim
    }

    @Test fun c150_restoreGearBeforeRoute_keepsQueuedAsk() {
        val sim = restoredWithS07()
        sim.ev(Tick(sim.t + 1_000))
        assertEquals(listOf(Sid.S07), sim.s.speech.queue.map { it.sid }, "경로 기다림 동안 큐에 둔다")
        assertTrue(sim.all<Ask>().isEmpty())
        sim.ev(RouteState(sim.t + 2_000, true, 3000, listOf(rest("n", 14.0, nursing = true)), 40.0))
        assertEquals(Sid.S07, sim.all<Ask>().single().sid, "쉼터 경로가 오면 그 질문을 낸다")
    }

    @Test fun c150_waitExpiresWithoutRoute_dropsAsBefore() {
        val sim = restoredWithS07()
        sim.ev(Tick(sim.t + P.routeWaitMs + 1_000))
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S07 })
        assertTrue(sim.all<Ask>().isEmpty())
    }

    @Test fun c150_waitingKeepsAsk_butOtherLinesGoOut() {
        val sim = restoredWithS07(Line(Sid.S12, emptyMap(), null))
        sim.ev(Tick(sim.t + 1_000))
        assertEquals(Sid.S12, sim.all<Speak>().single().sid)
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S07 })
    }

    // ---- C151: 복원은 말하던 줄을 '말하지 않음'으로 버린다 ----
    @Test fun c151_restoreSpeakingLongDrive_notMarkedOffered_reoffersAfterWait() {
        val sim = session()
        val s10 = Line(Sid.S10, emptyMap(), null, longDrive = true, sessionId = sim.ses.id, queuedAt = sim.t)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(s10)))
        sim.s = Engine().dropSpeaking(sim.s, sim.t + 1_000)
        assertFalse(sim.ses.longDriveOffered); assertNull(sim.s.speech.speaking); assertTrue(sim.s.speech.queue.isEmpty())
        sim.ev(RouteState(T0 + 121 * MIN, true, 3600, emptyList(), 50.0))
        assertTrue(Sid.S10 in sim.sids(), "휴식 안내가 다시 나온다: ${sim.sids()}")
    }

    @Test fun c151_restoreSpeakingS06_notMarkedSaid() {
        val sim = session().alarm().started()
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(Line(Sid.S06, emptyMap(), null, sessionId = sim.ses.id))))
        val st = Engine().dropSpeaking(sim.s, sim.t + 1_000)
        assertFalse(st.session!!.episode!!.s06Said)
        assertNull(st.speech.speaking)
    }

    /** 말하던 인계 줄 [head]와 다음 줄. Room에는 그 에피소드의 확정([told])이 이미 있다(C136). C303 A3~A7: 복원 ②는 판단 코어 restoreFromRoom이 한다. */
    private fun roomSettledHead(head: Line, told: Boolean): Pair<Sim, EngineState> {
        val sim = session()
        val h = head.copy(sessionId = sim.ses.id, handoff = true)
        val a = HandoffLedger(sim.ses.id, "e1", sim.t, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null)
        sim.s = sim.s.copy(handoffs = listOf(a),
            speech = sim.s.speech.copy(speaking = "u1", queue = listOf(h, Line(Sid.S12, emptyMap(), null))))
        return sim to Engine().restoreFromRoom(sim.s, facts(rows = listOf(roomRow("e1", sim.ses.id)), told = mapOf("e1" to told)), sim.t)
    }

    @Test fun c151_roomFalseHeadLine_markDropped_nextLineKept() {
        val (sim, st) = roomSettledHead(Line(Sid.S07, emptyMap(), "n", queuedAt = T0), told = false)
        assertTrue(st.session!!.spokenAt.isEmpty(),"말하지 않은 줄로 버린다(10분 금지 없음)")
        assertNull(st.speech.speaking); assertNull(st.openLedger())
        assertEquals(listOf(Sid.S12), Engine().dropSpeaking(st, sim.t + 1_000).speech.queue.map { it.sid }, "다음 줄을 버리지 않는다")
    }

    @Test fun c151_roomTrueHeadLine_markSpoken_nextLineKept() {
        val (sim, st) = roomSettledHead(Line(Sid.S10, emptyMap(), null, longDrive = true), told = true)
        assertTrue(st.session!!.longDriveOffered, "말한 줄로 처리한다")
        assertEquals(sim.t, st.speech.lastDoneAt, "말하기 간격을 이어 간다(C161)")
        assertEquals(listOf(Sid.S12), Engine().dropSpeaking(st, sim.t + 1_000).speech.queue.map { it.sid })
    }

    // ---- C152: 세션이 닫혀도 말하는 인계 줄은 끝까지 따라간다 ----
    /** 쉼터 질문 S07을 말하는 중(u1)에 인계가 묶이고, 그 상태로 P·뒷문 열림으로 세션이 닫힌다. */
    private fun closedWhileSpeaking(prof: Profile = profile()): Sim {
        val sim = session(prof = prof).route(rest("n", 14.0, nursing = true))
        sim.attempt2().dimSkipped().toGap().busyNow()   // 앰비언트 결과를 닫아 둔다(AM12는 AmbientTest)
        val old = Line(Sid.S07, mapOf("restName" to "n 휴게소", "distanceKm" to "14"), "n", queuedAt = sim.t)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(old) + sim.s.speech.queue))
        sim.recry()
        sim.s = sim.s.copy(car = sim.s.car.copy(gear = Gear.P))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session)
        assertEquals("u1", sim.s.closedLedgers().single().utteranceId)
        assertTrue(sim.alerts().isEmpty(), "말하는 줄이 끝날 때까지 알림을 미룬다")
        return sim
    }

    @Test fun c152_closedWhileSpeaking_spokenUpdatesClosedRowTrue() {
        val sim = closedWhileSpeaking()
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertEquals("n 휴게소", sim.alerts().single().body["rest_name"]!!.toString().trim('"'))
        val r = sim.all<SettleHandoff>().last()
        assertEquals(true, r.toldDriver); assertEquals("n 휴게소", r.restName)
        assertTrue(sim.s.closedLedgers().isEmpty())
    }

    @Test fun c152_closedWhileSpeaking_ttsFailPlainFalse() {
        val sim = closedWhileSpeaking()
        sim.ev(SpeechDone(sim.t + 1_000, "u1", false))
        assertEquals(JsonNull, sim.alerts().single().body["rest_name"])
        val r = sim.all<SettleHandoff>().last()
        assertEquals(false, r.toldDriver); assertNull(r.restName)
        assertTrue(sim.s.closedLedgers().isEmpty())
    }

    @Test fun c152_restoreFlushesClosedHandoffPlain() {
        val sim = closedWhileSpeaking()
        sim.s = Engine().dropSpeaking(sim.s, sim.t + 1_000)
        assertTrue(sim.alerts().isEmpty())
        sim.ev(Tick(sim.t + 2_000))
        assertEquals(JsonNull, sim.alerts().single().body["rest_name"])
        assertEquals(false, sim.all<SettleHandoff>().last().toldDriver)
        assertTrue(sim.s.closedLedgers().isEmpty())
    }

    @Test fun c152_newSessionHandoffNotMixed() {
        val sim = closedWhileSpeaking()
        val closedEp = sim.s.closedLedgers().single().episodeId
        sim.gear(Gear.P).doorCycle().gear(Gear.D)   // 새 세션 B
        assertNull(sim.ledger)
        assertEquals("u1", sim.s.closedLedgers().single().utteranceId, "B가 열려도 닫힌 묶음은 그대로")
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertNull(sim.ledger); assertTrue(sim.s.closedLedgers().isEmpty())
        assertEquals(closedEp, sim.all<SettleHandoff>().last().episodeId)
        assertEquals(true, sim.all<SettleHandoff>().last().toldDriver)
    }

    // C319 V19(옛 c152_clearRecordsDropsClosedHandoff): 기록 지우기는 닫힌 세션 장부도 버리지 않는다. 알림은 기록이 아니라 지금 우는 아기에 대한 통지다.
    // 말한 뒤 알림이 나가고, 그 세션 행 확정(SettleHandoff)·미룬 리포트(CloseSession)는 store 입구가 부모 행이 없어 버린다(C262)
    @Test fun c319_clearRecordsKeepsClosedHandoff_alertAfterSpoken() {
        val sim = closedWhileSpeaking()
        val saves = sim.all<SettleHandoff>().size
        sim.ev(ClearRecords(sim.t + 500))
        assertEquals("u1", sim.s.closedLedgers().single().utteranceId)
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertTrue(sim.s.closedLedgers().isEmpty())
        assertEquals(1, sim.alerts().size); assertEquals(saves + 1, sim.all<SettleHandoff>().size, "닫힌 세션의 recordsCleared는 판단 코어가 모른다(store C262가 버린다)")
    }

    @Test fun c152_reportWaitsForClosedHandoff_ttsFail() {
        val sim = closedWhileSpeaking()
        assertTrue(sim.all<CloseSession>().isEmpty(), "닫힌 인계 줄이 남은 동안 리포트 명령을 내지 않는다")
        sim.ev(SpeechDone(sim.t + 1_000, "u1", false))
        val i = sim.log.indexOfLast { it is SettleHandoff }; val j = sim.log.indexOfFirst { it is CloseSession }
        assertTrue(i in 0 until j, "SettleHandoff(false) 뒤에 CloseSession")
        assertEquals(false, (sim.log[i] as SettleHandoff).toldDriver)
    }

    @Test fun c152_reportAfterRestoreFlush() {
        val sim = closedWhileSpeaking()
        sim.s = Engine().dropSpeaking(sim.s, sim.t + 1_000)
        assertTrue(sim.all<CloseSession>().isEmpty())
        sim.ev(Tick(sim.t + 2_000))
        assertEquals(1, sim.all<CloseSession>().size)
        assertTrue(sim.log.indexOfLast { it is SettleHandoff } < sim.log.indexOfFirst { it is CloseSession })
    }

    private fun lockRes(sim: Sim, cmd: SetWindowLock, area: Area, ok: Boolean = true) =
        sim.ev(ControlResult(sim.t, cmd.cmdId, ControlKind.WINDOW_LOCK, area, ok, 0.0, 0.0))

    // C335: 인계 장부와 잠금 대기가 둘 다 있으면 마지막 것이 끝날 때 한 번
    @Test fun c335_report_ledgerEndsLast() {
        val sim = closedWhileSpeaking(profile(sibling = true))
        val id = sim.s.closedLedgers().single().sessionId
        val lock = sim.all<SetWindowLock>().first()
        lockRes(sim, lock, Area.ROW_2_LEFT); val rest = sim.all<SetWindowLock>().last()
        lockRes(sim, lock, Area.ROW_2_RIGHT, ok = false)
        lockRes(sim, rest, Area.ROW_2_LEFT)
        assertTrue(sim.all<CloseSession>().isEmpty(), "말하는 줄이 남았다")
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertEquals(listOf(CloseSession(id)), sim.all<CloseSession>())
    }

    @Test fun c335_report_lockEndsLast() {
        val sim = closedWhileSpeaking(profile(sibling = true))
        val id = sim.s.closedLedgers().single().sessionId
        val lock = sim.all<SetWindowLock>().first()
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertTrue(sim.all<CloseSession>().isEmpty(), "잠금 결과 대기가 남았다")
        lockRes(sim, lock, Area.ROW_2_LEFT); val rest = sim.all<SetWindowLock>().last()
        lockRes(sim, lock, Area.ROW_2_RIGHT, ok = false)
        assertTrue(sim.all<CloseSession>().isEmpty())
        lockRes(sim, rest, Area.ROW_2_LEFT)
        assertEquals(listOf(CloseSession(id)), sim.all<CloseSession>())
    }

    @Test fun c145_destinationBeforeRests_keepsWaiting() {
        val sim = restored()
        sim.ev(RouteState(T0 + 121 * MIN, true, 3600, emptyList(), 50.0))   // 목적지 신호만 먼저
        assertTrue(sim.sids().isEmpty(), "쉼터 목록 전에는 S10을 말하지 않는다")
        assertTrue(sim.s.session!!.longDriveOffered.not())
        sim.ev(RouteState(T0 + 121 * MIN + 3_000, true, 3600, listOf(rest("n", 10.0, nursing = true)), 50.0))
        assertTrue(Sid.S11 in sim.sids(), "쉼터 목록이 오면 S11: ${sim.sids()}")
    }

    @Test fun c145_noRestsRoute_S10After60s() {
        val sim = restored()
        sim.ev(RouteState(T0 + 121 * MIN, true, 3600, emptyList(), 50.0))
        sim.ev(Tick(T0 + 120 * MIN + 90_000))
        assertTrue(Sid.S10 in sim.sids())
    }

    // ---- C153: 통화 중 출발 제안 기회를 쓰지 않는다 ----
    private fun departSession() = session(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN)
    private fun Sim.depRoute() = route(nurse, dest = true, remainingSec = 9000, remainingKm = 150.0)

    @Test fun c153_inCallDepart_notChecked_offersAfterCallEnds() {
        val sim = departSession()
        sim.ev(CallMode(sim.t, true)).depRoute()
        assertFalse(sim.ses.departChecked); assertTrue(sim.all<Ask>().isEmpty())
        sim.ev(CallMode(sim.t + 1_000, false))
        assertTrue(sim.ses.departChecked)
        assertEquals(Sid.S07_DEPART, sim.step<Ask>().single().sid)
    }

    @Test fun c153_suspended_notChecked() {
        val sim = departSession()
        sim.ev(Suspend(sim.t)).depRoute()
        assertFalse(sim.ses.departChecked); assertTrue(sim.all<Ask>().isEmpty())
    }

    // ---- C162: 두 번째 세션이 닫혀도 앞 세션의 말하는 인계 줄은 끝까지 따라간다 ----
    @Test fun c162_secondSessionCloseKeepsEarlierSpeakingHandoff() {
        val sim = closedWhileSpeaking()
        sim.gear(Gear.P).doorCycle().gear(Gear.D)   // 새 세션 B
        sim.gear(Gear.P).ev(RearDoor(sim.t, Side.LEFT, true))   // B가 같은 발화가 끝나기 전에 닫힘
        assertNull(sim.s.session)
        assertEquals("u1", sim.s.closedLedgers().single().utteranceId, "A의 묶음을 false로 확정하지 않는다")
        assertTrue(sim.alerts().isEmpty())
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertEquals(true, sim.all<SettleHandoff>().last().toldDriver)
        assertEquals("n 휴게소", sim.alerts().single().body["rest_name"]!!.toString().trim('"'))
        assertTrue(sim.s.closedLedgers().isEmpty())
    }

    @Test fun c162_closedHandoffLineGone_flushedAtSecondClose() {
        val sim = closedWhileSpeaking()
        sim.s = Engine().dropSpeaking(sim.s, sim.t + 500).copy(session = null)   // 말하던 줄이 사라졌다(복원 등)
        sim.gear(Gear.P).doorCycle().gear(Gear.D)
        sim.ev(Tick(sim.t + 1_000))
        assertTrue(sim.s.closedLedgers().isEmpty(), "줄이 사라졌으면 정리한다")
        assertEquals(false, sim.all<SettleHandoff>().last().toldDriver)
    }

    // ---- C163: Suspend가 풀린 뒤 출발 제안을 다시 본다 ----
    @Test fun c163_resumeReevaluatesDepartOffer() {
        val sim = departSession()
        sim.ev(Suspend(sim.t)).depRoute()
        assertFalse(sim.ses.departChecked)
        sim.ev(Resume(sim.t + 1_000)).ev(Tick(sim.t + 2_000))   // 같은 경로 값은 다시 오지 않는다
        assertTrue(sim.ses.departChecked)
        assertEquals(Sid.S07_DEPART, sim.all<Ask>().single().sid)
    }

    // ---- C233: 기어를 모를 때 출발 제안 줄은 버리지 않고 보류한다 ----
    @Test fun c233_unknownGearKeepsDepartOffer() {
        val sim = departSession()
        sim.ev(CallMode(sim.t, true)).depRoute()
        val l = Line(Sid.S07_DEPART, emptyMap(), nurse.id, queuedAt = sim.t, sessionId = sim.ses.id)
        sim.s = sim.s.copy(car = sim.s.car.copy(gear = null), speech = sim.s.speech.copy(queue = listOf(l)))   // 재시작 직후: 기어 미상, 줄 보류
        sim.ev(CallMode(sim.t + 1_000, false))
        assertTrue(sim.all<Ask>().isEmpty(), "기어를 모르면 말하지 않는다")
        assertEquals(listOf(Sid.S07_DEPART), sim.s.speech.queue.map { it.sid }, "버리지 않고 보류한다")
        sim.gear(Gear.D, 2_000).ev(Tick(sim.t + 3_000))
        assertEquals(Sid.S07_DEPART, sim.all<Ask>().single().sid)
    }
}
