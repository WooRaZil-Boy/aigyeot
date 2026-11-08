package kr.aigyeot.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 명세서 17장 C258(11-07): 인계 안내는 한 함수(handoffGuidance)가 인계 시각과 꺼낼 때 고르고, 인계 줄은 Line.handoff 표시로 알아본다.
// 알림의 종류·쉼터 이름·분과 에피소드 handoff.kind는 실제로 낸(또는 버린) 줄에서 정한다.
class C258Test {
    private fun Sim.reroute(sec: Int?, dest: Boolean = true, vararg rests: RestCandidate) = ev(RouteState(t + 1_000, dest, sec, rests.toList(), null))
    private fun Sim.alerts() = all<SendToPhone>().filter { it.type == "handoff_alert" }.map { it.body }
    private fun JsonObject.str(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private val handoffSids = setOf(Sid.S07, Sid.S08, Sid.S10, Sid.S15)
    /** 실제로 낸 인계 문장(Speak·Ask)과 그 vars. */
    private fun Sim.said() = log.mapNotNull { c -> (c as? Speak)?.let { it.sid to it.vars } ?: (c as? Ask)?.let { it.sid to it.vars } }.filter { it.first in handoffSids }
    private fun Sim.marks() = s.speech.queue.count { it.handoff }
    /** 앞 발화(S02) 뒤에서 기다리던 인계 줄을 꺼내 낸다(S03은 그 재생이 끝나 버려진다). */
    private fun Sim.release() = done(2_000).tick(P.speechGapMs)
    private fun Sim.busyNow(dt: Long = 0) = ev(VehicleSample(t + dt, 60.0, true, 0.0, TurnSignal.NONE))

    private fun s15() = session().route(rest("a", 5.0), dest = true, remainingSec = 360).handoff()
    private fun s08() = session().route(rest("a", 8.0), dest = true, remainingSec = 3600).handoff()
    private fun s10() = session().handoff()

    /** 표 한 칸의 끝: 낸 줄을 말한 뒤(spoken) 알림·기록을 본다. */
    private fun Sim.finish(): Sim { if (s.speech.speaking != null) done(2_000); return this }

    // ---- 처음 S15(H2 알림은 인계 때 이미 보냄) ----
    @Test fun c258_s15_arrivalStill_minutesRefreshed() {
        val sim = s15()
        assertEquals(1, sim.marks()); assertEquals(Sid.S15, sim.s.speech.queue.single { it.handoff }.sid)
        sim.reroute(240, true, rest("a", 5.0)).release()
        assertEquals(listOf(Sid.S15 to mapOf("minutes" to "4")), sim.said())
        assertEquals(HandoffKind.ARRIVAL_NOTICE, sim.ep.handoff!!.kind)
        sim.finish()
        val a = sim.alerts().single()
        assertEquals("arrival_notice", a.str("kind")); assertEquals("6", a.str("minutes_to_arrival"), "인계 때 보낸 알림 한 건뿐")
        assertEquals(true, sim.told()); assertNull(sim.settled()?.restName)
    }

    @Test fun c258_s15_restNow_asksRest_noSecondAlert() {
        val sim = s15().reroute(3600, true, rest("a", 5.0)).release()
        assertEquals(listOf(Sid.S08), sim.said().map { it.first })
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind)
        sim.finish()
        assertEquals(listOf("arrival_notice"), sim.alerts().map { it.str("kind") })
        assertEquals(true, sim.told()); assertEquals("a 휴게소", sim.settled()?.restName)
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.all<SaveEpisode>().last().record.handoffKind)
    }

    @Test fun c258_s15_restNowBlocked_noLine_toldFalse() {
        val sim = s15()
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(spokenAt = spokenAt + (Sid.S08 to sim.t)) })   // S08을 10분 안에 냈다
        sim.reroute(3600, true, rest("a", 5.0)).release().finish()
        assertTrue(sim.said().isEmpty(), "도착 안내도 쉼터 없음 안내도 하지 않는다")
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind)
        assertEquals(listOf("arrival_notice"), sim.alerts().map { it.str("kind") })
        assertEquals(false, sim.told()); assertNull(sim.ledger)
        assertEquals(0, sim.marks())
    }

    @Test fun c258_s15_noRestNow_s10() {
        val sim = s15().reroute(3600, true).release()
        assertEquals(listOf(Sid.S10), sim.said().map { it.first })
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind)
        sim.finish()
        assertEquals(1, sim.alerts().size); assertEquals(true, sim.told())
    }

    // ---- 처음 S08(알림은 낼 때) ----
    @Test fun c258_s08_sameRest_distanceRefreshed() {
        val sim = s08().reroute(3600, true, rest("a", 6.0)).release()
        assertEquals(listOf(Sid.S08 to mapOf("distanceKm" to "6", "restName" to "a 휴게소")), sim.said())
        sim.finish()
        val a = sim.alerts().single()
        assertEquals("rest_offer", a.str("kind")); assertEquals("a 휴게소", a.str("rest_name"))
        assertEquals(true, sim.told()); assertEquals("a 휴게소", sim.settled()?.restName)
    }

    @Test fun c258_s08_otherRest_asksNewRest() {
        val sim = s08().reroute(3600, true, rest("b", 9.0)).release()
        assertEquals(listOf(Sid.S08), sim.said().map { it.first }); assertEquals("b", sim.ses.pendingAsk!!.restId)
        sim.finish()
        assertEquals("b 휴게소", sim.alerts().single().str("rest_name"))
        assertEquals(true, sim.told()); assertEquals("b 휴게소", sim.settled()?.restName)
    }

    @Test fun c258_s08_nearDestination_s15_alertMinutes() {
        val sim = s08().reroute(360, true, rest("a", 8.0)).release()
        assertEquals(listOf(Sid.S15 to mapOf("minutes" to "6")), sim.said())
        assertEquals(HandoffKind.ARRIVAL_NOTICE, sim.ep.handoff!!.kind)
        sim.finish()
        val a = sim.alerts().single()
        assertEquals("arrival_notice", a.str("kind")); assertEquals("6", a.str("minutes_to_arrival")); assertNull(a.str("rest_name"))
        assertEquals(true, sim.told()); assertNull(sim.settled()?.restName)
    }

    @Test fun c258_s08_restGone_s10_namedNoRestAlert() {
        val sim = s08().reroute(3600, true).release()
        assertEquals(listOf(Sid.S10), sim.said().map { it.first })
        sim.finish()
        val a = sim.alerts().single()
        assertEquals("no_rest", a.str("kind")); assertEquals("아기가 계속 울어요. 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요.", a.str("text"))
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind); assertEquals(true, sim.told())
    }

    @Test fun c258_s08_blockedByWaypointInProgress_plainAlert() {
        val sim = s08()
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(waypointPending = WaypointPending("c", "z", sim.ses.id, null)) })   // H4c: 다른 경유지 요청이 진행 중
        sim.release().finish()
        assertTrue(sim.said().isEmpty())
        val a = sim.alerts().single()
        assertEquals("rest_offer", a.str("kind")); assertNull(a.str("rest_name")); assertEquals("아기가 계속 울어요.", a.str("text"))
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind); assertEquals(false, sim.told())
        assertTrue(Sid.S08 !in sim.ses.spokenAt, "듣지 못한 질문은 10분 금지를 남기지 않는다")
    }

    // ---- 처음 S10(알림은 낼 때) ----
    @Test fun c258_s10_restAppeared_asksRest() {
        val sim = s10().reroute(null, false, rest("n", 8.0)).release()
        assertEquals(listOf(Sid.S08), sim.said().map { it.first })
        assertTrue(Sid.S10 !in sim.ses.spokenAt)
        sim.finish()
        assertEquals("rest_offer", sim.alerts().single().str("kind")); assertEquals("n 휴게소", sim.alerts().single().str("rest_name"))
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind); assertEquals(true, sim.told())
    }

    @Test fun c258_s10_restAppearedBlocked_plainRestOffer() {
        val sim = s10()
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(spokenAt = spokenAt + (Sid.S08 to sim.t)) })
        sim.reroute(null, false, rest("n", 8.0)).release().finish()
        assertTrue(sim.said().isEmpty(), "쉼터가 있는데 '못 찾았다'고 하지 않는다")
        val a = sim.alerts().single()
        assertEquals("rest_offer", a.str("kind")); assertNull(a.str("rest_name")); assertEquals("아기가 계속 울어요.", a.str("text"))
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind); assertEquals(false, sim.told())
    }

    @Test fun c258_s10_nearDestination_s15() {
        val sim = s10().reroute(300, true).release()
        assertEquals(listOf(Sid.S15 to mapOf("minutes" to "5")), sim.said())
        sim.finish()
        val a = sim.alerts().single()
        assertEquals("arrival_notice", a.str("kind")); assertEquals("5", a.str("minutes_to_arrival"))
        assertEquals(HandoffKind.ARRIVAL_NOTICE, sim.ep.handoff!!.kind); assertEquals(true, sim.told())
    }

    @Test fun c258_s10_unchanged_spoken() {
        val sim = s10().release()
        assertEquals(listOf(Sid.S10), sim.said().map { it.first })
        sim.finish()
        assertEquals("no_rest", sim.alerts().single().str("kind"))
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind); assertEquals(true, sim.told())
    }

    // ---- 바꾼 뒤 8초 간격 대기 중 경로가 또 바뀜 ----
    @Test fun c258_swappedThenRouteChangesAgain_nextDrainSwapsAgain() {
        val sim = s10().reroute(null, false, rest("n", 8.0)).done(2_000)   // 간격 대기 중 S08로 바꿨다
        val l = sim.s.speech.queue.single { it.handoff }
        assertEquals(Sid.S08, l.sid)
        val a = sim.ledger!!
        assertEquals(HandoffKind.WAYPOINT_OFFER, a.kind)
        sim.reroute(300, true).tick(P.speechGapMs)
        assertEquals(listOf(Sid.S15 to mapOf("minutes" to "5")), sim.said())
        assertEquals(1, sim.marks())
        sim.finish()
        assertEquals("arrival_notice", sim.alerts().single().str("kind")); assertEquals("5", sim.alerts().single().str("minutes_to_arrival"))
        assertEquals(HandoffKind.ARRIVAL_NOTICE, sim.ep.handoff!!.kind)
    }

    // ---- H10: 다른 안내로 바꿀 때 같은 문장이 큐에 있으면 그 줄을 돌려받는다(겹쳐 묻지 않는다) ----
    @Test fun c258_swapToQueuedSameSentence_noDuplicate() {
        val sim = s10()
        val other = Line(Sid.S08, mapOf("restName" to "n 휴게소", "distanceKm" to "8"), "n", queuedAt = sim.t, sessionId = sim.ses.id)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = sim.s.speech.queue + other))
        sim.reroute(null, false, rest("n", 8.0)).release()
        sim.flush()
        assertEquals(listOf(Sid.S08), sim.said().map { it.first }, "S08을 두 번 묻지 않는다")
        assertEquals("n 휴게소", sim.alerts().single().str("rest_name")); assertEquals(true, sim.told())
    }

    @Test fun c258_swapToQueuedSameSentenceOtherRest_takesItsSlotWithNewRest() {
        // 큐에 묶이지 않은 S08(a)가 있고 지금 고른 쉼터는 b: 그 자리에 b를 넣어 한 번만 묻는다(다시 봐도 같은 결과라 한 drain에 끝난다)
        val sim = s10()
        val e = Line(Sid.S08, mapOf("restName" to "a 휴게소", "distanceKm" to "20"), "a", queuedAt = sim.t, sessionId = sim.ses.id)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = sim.s.speech.queue + e))
        sim.reroute(null, false, rest("a", 20.0), rest("b", 8.0)).done(2_000)
        assertEquals(listOf("b"), sim.s.speech.queue.filter { it.sid == Sid.S08 }.map { it.askRestId }, "S08은 한 줄, 쉼터는 b")
        assertTrue(sim.s.speech.queue.single { it.sid == Sid.S08 }.handoff)
        sim.tick(P.speechGapMs).flush()
        assertEquals(listOf(Sid.S08 to mapOf("restName" to "b 휴게소", "distanceKm" to "8")), sim.said())
        assertEquals("b 휴게소", sim.alerts().single().str("rest_name")); assertEquals(true, sim.told())
    }

    // ---- 운전 바쁨 보류: 보류 중 경로가 바뀌면 먼저 다시 고르고 그 문장으로 미룰지 정한다(C285) ----
    @Test fun c258_heldWhileBusy_routeChanged_repickedS08NotHeld() {
        val sim = session()
        sim.attempt2().toGap().recry().busyNow().done()   // recry가 시각을 3초 밀므로 바쁨은 인계 뒤에 넣는다
        assertEquals(Sid.S10, sim.s.speech.queue.single { it.handoff }.sid)
        sim.reroute(null, false, rest("n", 8.0))
        assertEquals(Sid.S08, sim.s.speech.queue.single { it.handoff }.sid, "C285: 꺼낼 차례 전에도 다시 고른다")
        sim.busyNow(P.speechGapMs)
        assertEquals(listOf(Sid.S08), sim.said().map { it.first }, "다시 고른 S08은 미루는 문장이 아니라 바빠도 낸다")
        sim.done(2_000)
        assertEquals("n 휴게소", sim.alerts().single().str("rest_name")); assertEquals(true, sim.told())
    }

    // ---- S6: 바쁨으로 너무 오래 미뤄진 인계 줄은 버린다(바꾼 줄도 그 종류로) ----
    @Test fun c258_swappedLineExpiredS6_plainAlertOfNewKind() {
        val sim = s10().reroute(null, false, rest("n", 8.0, nursing = true)).done(2_000)   // S07(holdable)로 바뀜
        assertEquals(Sid.S07, sim.s.speech.queue.single { it.handoff }.sid)
        repeat(35) { sim.busyNow(1_000) }
        assertTrue(sim.said().isEmpty()); assertEquals(0, sim.marks())
        val a = sim.alerts().single()
        assertEquals("rest_offer", a.str("kind")); assertNull(a.str("rest_name"))
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind); assertEquals(false, sim.told())
        assertTrue(Sid.S07 !in sim.ses.spokenAt)
    }

    // ---- 앞 버림(dropWhile): 인계 줄은 경로 때문에 앞에서 버리지 않고, 기어 P면 버린다 ----
    private fun heldAtFront(): Sim {
        val sim = session()
        sim.attempt2().toGap().recry().busyNow().done()   // S02 끝 → S03은 재생이 끝나 버려지고 S10이 맨 앞에서 바쁨으로 보류
        assertEquals(Sid.S10, sim.s.speech.queue.first().sid); assertTrue(sim.s.speech.queue.first().handoff)
        return sim
    }

    @Test fun c258_frontHeldGuide_notDroppedByRoute() {
        val sim = heldAtFront()
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = sim.s.speech.queue + Line(Sid.S12, emptyMap(), null)))
        sim.reroute(null, false, rest("n", 8.0)).busyNow(P.speechGapMs)
        assertEquals(Sid.S12, sim.step<Speak>().single().sid, "급한 문장이 먼저")
        assertTrue(sim.s.speech.queue.any { it.handoff && it.sid == Sid.S08 }, "쉼터가 생겨도 앞에서 버리지 않고 다시 고른다(C285)")
        sim.done().ev(Tick(sim.t + P.speechGapMs))
        assertEquals(Sid.S08, sim.said().single().first)
    }

    @Test fun c258_frontGuide_gearP_droppedPlain() {
        val sim = heldAtFront().gear(Gear.P, 1_000)
        assertTrue(sim.said().isEmpty()); assertEquals(0, sim.marks())
        val a = sim.alerts().single()
        assertEquals("no_rest", a.str("kind")); assertEquals("아기가 계속 울어요.", a.str("text"))
        assertEquals(false, sim.told())
    }

    // ---- 경로 기다림(C150): 인계 줄은 다시 고르지 않고 건너뛰었다가 경로가 오면 고른다 ----
    @Test fun c258_routeWait_heldThenRepicked() {
        val sim = s08()
        sim.s = sim.s.copy(bootAt = sim.t, car = sim.s.car.copy(route = null))
        sim.release()
        assertTrue(sim.said().isEmpty()); assertEquals(Sid.S08, sim.s.speech.queue.single { it.handoff }.sid)
        sim.ev(RouteState(sim.t + 1_000, true, 3600, listOf(rest("b", 9.0)), null)).tick(P.speechGapMs)
        assertEquals(listOf(Sid.S08 to mapOf("distanceKm" to "9", "restName" to "b 휴게소")), sim.said())
        sim.finish()
        assertEquals("b 휴게소", sim.alerts().single().str("rest_name"))
    }

    @Test fun c258_routeWaitExpires_noRoute_s10() {
        val sim = s08()
        sim.s = sim.s.copy(bootAt = sim.t, car = sim.s.car.copy(route = null))
        sim.release().ev(Tick(sim.s.bootAt!! + P.routeWaitMs + 1_000))
        assertEquals(listOf(Sid.S10), sim.said().map { it.first })
        sim.finish()
        assertEquals("no_rest", sim.alerts().single().str("kind")); assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind)
    }

    // ---- 버림 길: 세션 닫힘·통화·TTS 실패·장면 시작은 그때(바꾼 뒤)의 종류로 이름 없는 알림 ----
    private fun swappedToS08(): Sim = s10().reroute(null, false, rest("n", 8.0)).done(2_000).also {
        assertEquals(Sid.S08, it.s.speech.queue.single { l -> l.handoff }.sid); assertTrue(it.alerts().isEmpty())
    }
    private fun Sim.plainRestOffer() {
        val a = alerts().single()
        assertEquals("rest_offer", a.str("kind")); assertNull(a.str("rest_name")); assertEquals("아기가 계속 울어요.", a.str("text"))
    }

    @Test fun c258_swapped_sessionClosed_plainOfNewKind() {
        val sim = swappedToS08()
        sim.s = sim.s.copy(car = sim.s.car.copy(gear = Gear.P))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session); assertEquals(0, sim.marks())
        sim.plainRestOffer()
        val r = sim.all<SettleHandoff>().last()
        assertEquals(false, r.toldDriver); assertEquals(HandoffKind.WAYPOINT_OFFER, r.kind)
    }

    @Test fun c258_swapped_call_plainOfNewKind() {
        val sim = swappedToS08()
        sim.ev(CallMode(sim.t, true))
        sim.plainRestOffer()
        assertEquals(false, sim.told()); assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind)
        assertTrue(Sid.S08 !in sim.ses.spokenAt)
    }

    @Test fun c258_swapped_suspend_plainOfNewKind() {
        val sim = swappedToS08()
        sim.ev(Suspend(sim.t + 500))
        sim.plainRestOffer(); assertEquals(false, sim.told())
    }

    @Test fun c258_swapped_ttsFailed_plainToldFalse() {
        val sim = swappedToS08().tick(P.speechGapMs)
        assertEquals(Sid.S08, sim.said().single().first)
        sim.done(spoken = false)
        sim.plainRestOffer()
        assertEquals(false, sim.told()); assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind)
    }

    @Test fun c258_swapped_cancelForScene_plainOfNewKind() {
        // D6: cancelForScene → sceneStart의 실제 상태 Step(수명 표 SCENE_START). 바뀐 S08을 말하는 중에 시작한다(C319 V9 뒤에는 말하지 않아도 끊는다)
        val sim = swappedToS08().tick(P.speechGapMs)
        assertEquals(Sid.S08, sim.said().single().first)
        val step = Engine().sceneStart(sim.s, sim.s, sim.t + 100).realStep
        val a = step.commands.filterIsInstance<SendToPhone>().single { it.type == "handoff_alert" }.body
        assertEquals("rest_offer", a.str("kind")); assertNull(a.str("rest_name"))
        val r = step.commands.filterIsInstance<SettleHandoff>().last()
        assertEquals(false, r.toldDriver); assertEquals(HandoffKind.WAYPOINT_OFFER, r.kind)
        assertNull(step.state.openLedger())
    }

    // ---- 복원(C151 dropSpeaking): 바꾼 줄을 말하던 중 죽으면 다음 Step이 이름 없는 알림·false ----
    @Test fun c258_swappedSpeaking_restoreDropSpeaking_plain() {
        val sim = swappedToS08().tick(P.speechGapMs)
        assertEquals(Sid.S08, sim.said().single().first)
        val after = Sim(Engine().dropSpeaking(sim.s, sim.t + 1_000)).tick()
        after.plainRestOffer()
        assertEquals(false, after.told()); assertNull(after.ledger)
    }

    // ---- 기록 지우기: 기록은 버리지만 운전자에게는 지금 경로의 안내를 내고, 보호자 알림은 그 안내를 따라 나간다(C319 V19, 옛 ..._noAlertNoSave) ----
    @Test fun c258_clearRecords_guideStillRepicked_alertSentNoSave() {
        val sim = s10()
        sim.ev(ClearRecords(sim.t))
        assertNotNull(sim.ledger)
        fun saves() = sim.log.count { it is SaveEpisode || it is SettleHandoff }
        val before = saves()
        sim.reroute(null, false, rest("n", 8.0)).release().finish()
        assertEquals(listOf(Sid.S08), sim.said().map { it.first }, "쉼터가 생겼는데 '못 찾았다'고 하지 않는다")
        assertEquals(listOf("rest_offer" to "n 휴게소"), sim.alerts().map { it.str("kind") to it.str("rest_name") }, "알림은 기록이 아니다: 말한 안내대로 나간다")
        assertEquals(before, saves())
    }

    // ---- C319 결정 7(A9): 인계 종류를 다시 고를 때 장부와 열린 에피소드에 같은 함수로 쓴다(말할 때까지 미루지 않는다) ----
    @Test fun c319_repickedKind_writtenToEpisodeAtRepick() {
        val sim = s10().reroute(null, false, rest("n", 8.0))
        assertEquals(HandoffKind.NO_REST, sim.ep.handoff!!.kind, "전제: 처음 고른 종류")
        sim.done(2_000)   // 앞 발화(S02)가 끝나고 듣기 창이 닫혀 큐를 다시 고른다. S1 간격이라 아직 말하지 않는다
        assertNull(sim.s.speech.speaking); assertTrue(sim.s.speech.queue.any { it.sid == Sid.S08 && it.handoff }, "전제: 다시 고른 S08이 말하지 않은 채 큐에 있다")
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ledger!!.kind)
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind, "다시 고를 때 에피소드에도 쓴다")
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.all<SaveEpisode>().last().record.handoffKind, "행에도 쓴다")
    }

    // ---- 두 번째 인계: 앞 인계를 강제 정리하고, 앞 인계의 표시 줄은 새 안내가 대신한다 ----
    private fun secondHandoff(speaking: Boolean): Sim {
        val sim = session().route(rest("a", 8.0))
        sim.attempt2().toGap()
        val old = Line(Sid.S10, emptyMap(), null, queuedAt = sim.t, sessionId = sim.ses.id, handoff = true)
        val q = sim.s.speech.queue
        sim.s = sim.s.copy(
            speech = if (speaking) sim.s.speech.copy(speaking = "u0", queue = listOf(old) + q) else sim.s.speech.copy(queue = q + old),
            handoffs = listOf(HandoffLedger(sim.ses.id, "prev-ep", sim.t, HandoffCause.TWO_ATTEMPTS, HandoffKind.NO_REST, null)))
        return sim.recry()
    }

    @Test fun c258_secondHandoff_oldGuideDropped_prevFlushedPlain() {
        val sim = secondHandoff(speaking = false)
        val prev = sim.alerts().single()
        assertEquals("prev-ep", prev.str("episode_id")); assertEquals("아기가 계속 울어요.", prev.str("text"))
        assertEquals(false, sim.told("prev-ep"))
        assertEquals(listOf(Sid.S08), sim.s.speech.queue.filter { it.handoff }.map { it.sid })
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S10 }, "앞 인계의 S10은 새 안내가 대신한다")
        assertTrue(Sid.S10 !in sim.ses.spokenAt)
        sim.flush()
        assertEquals(listOf(Sid.S08), sim.said().map { it.first })
        assertEquals("a 휴게소", sim.alerts().last().str("rest_name")); assertEquals(true, sim.told())
    }

    @Test fun c258_secondHandoff_oldGuideSpeaking_unmarkedKept() {
        val sim = secondHandoff(speaking = true)
        assertEquals("u0", sim.s.speech.speaking)
        assertFalse(sim.s.speech.queue.first().handoff, "말하는 줄은 표시만 뗀다")
        assertEquals(Sid.S10, sim.s.speech.queue.first().sid)
        assertEquals(listOf(Sid.S08), sim.s.speech.queue.filter { it.handoff }.map { it.sid })
        sim.ev(SpeechDone(sim.t + 1_000, "u0", true))
        assertEquals(1, sim.alerts().size, "앞 인계의 이름 없는 알림만")
        assertEquals(null, sim.told())
    }

    // ---- 인계 시각: 표시는 한 줄에만, 같은 줄이 이미 있으면 그 줄(H10), 말하는 줄이면 발화 id(C131) ----
    @Test fun c258_handoffMarksExactlyOneLine() {
        for (sim in listOf(s15(), s08(), s10())) assertEquals(1, sim.marks())
        val p = session().route(rest("a", 5.0)).gear(Gear.P, MIN).handoff()
        assertEquals(0, p.marks()); assertNull(p.ledger)
        val (spk, _) = S57HandoffTest().speakingOffer()
        assertTrue(spk.s.speech.queue.first().handoff); assertEquals("u1", spk.ledger!!.utteranceId)
        assertEquals(spk.ses.id, spk.s.speech.queue.first().sessionId, "옛 줄(세션 id 없음)에는 지금 세션을 적는다")
    }

    @Test fun c258_closedSessionSpeaking_newSessionHandoff_doesNotTouchClosedLine() {
        // 세션 A의 인계 줄을 말하는 중 A가 닫히고, 그 발화가 끝나기 전에 세션 B에서 인계가 난다: B는 A의 줄을 자기 인계 줄로 보지 않는다(C147·C152)
        val (sim, _) = S57HandoffTest().speakingOffer()
        val epA = sim.ep.id
        sim.s = sim.s.copy(car = sim.s.car.copy(gear = Gear.P))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        sim.gear(Gear.P).doorCycle().gear(Gear.D).route(rest("n", 14.0, nursing = true))   // 세션 B
        sim.attempt2().toGap().recry()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertTrue(sim.s.speech.queue.first().handoff, "A의 말하는 줄 표시를 떼지 않는다")
        assertEquals("u1", sim.s.closedLedgers().single().utteranceId); assertTrue(sim.alerts().isEmpty())
        assertEquals(listOf(sim.ses.id), sim.s.speech.queue.drop(1).filter { it.handoff }.map { it.sessionId })
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        val a = sim.alerts().single()
        assertEquals(epA, a.str("episode_id")); assertEquals("n 휴게소", a.str("rest_name"))
        assertEquals(true, sim.told(epA))
        assertEquals(null, sim.told(), "B는 아직 묻지 않았다")
    }

    @Test fun c258_speakingBoundLine_sessionClosed_markedHeadKeepsClosedHandoff() {
        val (sim, _) = S57HandoffTest().speakingOffer()
        sim.s = sim.s.copy(car = sim.s.car.copy(gear = Gear.P))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertEquals(listOf(true), sim.s.speech.queue.filter { it.handoff }.map { it.sid == Sid.S07 }, "닫힌 세션의 표시 줄은 말하는 맨 앞 줄 하나")
        assertEquals("u1", sim.s.closedLedgers().single().utteranceId)
        sim.ev(SpeechDone(sim.t + 1_000, "u1", false))
        assertEquals(null, sim.alerts().single().str("rest_name")); assertTrue(sim.s.closedLedgers().isEmpty())
    }
}
