package kr.aigyeot.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 명세서 17장 C267~C269.
class C267ToC269Test {
    private fun Sim.alerts() = all<SendToPhone>().filter { it.type == "handoff_alert" }.map { it.body }
    private fun JsonObject.str(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun Sim.asked() = all<Ask>().map { it.sid }
    private fun Sim.spoke() = all<Speak>().map { it.sid }
    private fun Sim.release() = done(2_000).tick(P.speechGapMs)
    private fun Sim.routeAt(dt: Long, vararg rests: RestCandidate, pending: Boolean = false, sec: Int = 9000) =
        ev(RouteState(t + dt, true, sec, rests.toList(), 150.0, restsPending = pending))

    // ---- C267: 인계가 줄을 바꿀 때 출처 속성(longDrive)을 옮긴다 ----

    /** 쉼터 없는 길에서 2시간 안내 S10(longDrive)이 S02 뒤에 서 있고, 인계가 그 줄을 넘겨받았다(H10). */
    private fun longDriveTakenByHandoff(): Sim {
        val sim = session()
        sim.ev(Tick(T0 + 119 * MIN + 30_000)).attempt1()
        sim.ev(Tick(T0 + 120 * MIN))
        assertTrue(sim.s.speech.queue.single { it.sid == Sid.S10 }.longDrive, "조건: L1의 2시간 S10이 큐에 있다")
        sim.toGap().recry().started().toGap().recry()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        val g = sim.s.speech.queue.single { it.handoff }
        assertEquals(Sid.S10, g.sid); assertTrue(g.longDrive, "인계 시각에 넘겨받은 줄은 그 줄 그대로(출처 속성 유지)")
        return sim
    }

    @Test fun c267_takenLongDriveS10_swappedToS08_spokenSetsLongDriveOffered_noS11AfterNo() {
        val sim = longDriveTakenByHandoff()
        sim.routeAt(1_000, rest("a", 8.0)).release()
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S08, ask.sid)
        assertTrue(sim.s.speech.queue.first().longDrive, "바꾼 S08이 2시간 안내 출처를 이어받는다")
        assertFalse(sim.ses.longDriveOffered, "말하는 중에는 아직 아니다(C84)")
        sim.done(2_000)
        assertTrue(sim.ses.longDriveOffered, "S08을 말했으니 2시간 안내도 한 것이다")
        assertEquals("a 휴게소", sim.alerts().single().str("rest_name"))
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO))
        sim.tick(MIN).tick(MIN)
        assertEquals(listOf(Sid.S08), sim.asked(), "같은 쉼터로 S11을 다시 묻지 않는다('아니' 뒤에도)")
    }

    @Test fun c267_swapIntoQueuedSameSentence_slotGetsLongDrive() {
        val sim = longDriveTakenByHandoff()
        // 묶이지 않은 S08(a)이 이미 큐에 있다(수유 시간대 제안 등). 꺼낼 때 S10 → S08을 고르면 그 줄 자리를 쓴다(H10)
        val other = Line(Sid.S08, mapOf("restName" to "a 휴게소", "distanceKm" to "8"), "a", queuedAt = sim.t, sessionId = sim.ses.id)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = sim.s.speech.queue + other))
        sim.routeAt(1_000, rest("a", 8.0)).release()
        assertEquals(Sid.S08, sim.step<Ask>().single().sid)
        assertEquals(1, sim.s.speech.queue.count { it.sid == Sid.S08 }); assertTrue(sim.s.speech.queue.first().longDrive)
        sim.done(2_000)
        assertTrue(sim.ses.longDriveOffered)
        sim.tick(MIN)
        assertEquals(listOf(Sid.S08), sim.asked())
    }

    /** 앞 인계가 넘겨받은 2시간 S10(표시)이 큐에 있는 채 두 번째 인계가 쉼터 있는 길에서 일어난다. */
    private fun secondHandoffOverLongDriveGuide(speaking: Boolean): Sim {
        val sim = session().route(rest("a", 8.0))
        sim.attempt2().toGap()
        val old = Line(Sid.S10, emptyMap(), null, queuedAt = sim.t, sessionId = sim.ses.id, handoff = true, longDrive = true)
        val q = sim.s.speech.queue
        sim.s = sim.s.copy(speech = if (speaking) sim.s.speech.copy(speaking = "u0", queue = listOf(old) + q) else sim.s.speech.copy(queue = q + old),
            session = sim.ses.withOffers { copy(driveFrom = sim.t - 121 * MIN) })   // 2시간을 넘겼다: L1이 같은 쉼터로 S11을 겹쳐 묻는지 본다
        return sim.recry()
    }

    @Test fun c267_secondHandoff_replacedLongDriveGuide_newLineInherits() {
        val sim = secondHandoffOverLongDriveGuide(speaking = false)
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S10 }, "앞 표시 줄은 새 안내가 대신한다")
        val g = sim.s.speech.queue.single { it.handoff }
        assertEquals(Sid.S08, g.sid); assertTrue(g.longDrive)
        sim.flush()
        assertTrue(sim.ses.longDriveOffered)
        assertTrue(Sid.S11 !in sim.asked())
    }

    @Test fun c267_secondHandoff_speakingLongDriveGuide_failed_newLineStillCarries() {
        val sim = secondHandoffOverLongDriveGuide(speaking = true)
        assertTrue(sim.s.speech.queue.single { it.handoff }.longDrive, "말하는 앞 줄은 표시만 떼고, 새 줄이 출처를 이어받는다")
        sim.ev(SpeechDone(sim.t + 1_000, "u0", false))   // 앞 줄을 끝까지 말하지 못했다
        assertFalse(sim.ses.longDriveOffered)
        sim.tick(P.speechGapMs).flush()
        assertTrue(sim.ses.longDriveOffered)
        assertTrue(Sid.S11 !in sim.asked(), "그사이 L1이 같은 쉼터로 S11을 겹쳐 묻지 않는다")
    }

    // ---- C269: 쉼터 목록을 기다림 ≠ 쉼터 없음 ----

    /** 2시간을 넘긴 D 주행 중 새 경로로 안내가 시작돼 쉼터 목록을 다시 받는 중. */
    private fun pendingAfterTwoHours(): Sim = session().routeAt(121 * MIN, pending = true)   // 2시간을 넘긴 뒤 첫 사건이 안내 시작

    @Test fun c269_l1_waitsForRests_thenS11() {
        val sim = pendingAfterTwoHours()
        assertTrue(sim.sids().isEmpty(), "목록을 기다리는 동안 '쉼터를 못 찾았어요'를 말하지 않는다")
        sim.routeAt(2_000, rest("a", 12.0))
        assertEquals(listOf(Sid.S11), sim.asked())
    }

    @Test fun c269_l1_listArrivesEmpty_thenS10() {
        val sim = pendingAfterTwoHours().routeAt(2_000)
        assertEquals(listOf(Sid.S10), sim.spoke())
    }

    @Test fun c269_listNeverArrives_judgedAfterRouteWait_sinceKeptAcrossUpdates() {
        val sim = pendingAfterTwoHours()
        val since = sim.s.car.route!!.restsPendingSince!!
        sim.routeAt(30_000, pending = true, sec = 8970)   // DrivingInfo 갱신: at은 바뀌어도 기다림 시작은 그대로
        assertEquals(since, sim.s.car.route!!.restsPendingSince)
        assertEquals(since + P.routeWaitMs, sim.last.wakeAt)
        sim.ev(Tick(since + P.routeWaitMs - 1))
        assertTrue(sim.sids().isEmpty())
        sim.ev(Tick(since + P.routeWaitMs))
        assertEquals(listOf(Sid.S10), sim.spoke(), "끝내 오지 않으면 routeWaitMs 뒤 지금처럼 판단")
    }

    /** 2차 시도 뒤 GAP. 이때 새 경로가 시작돼 쉼터 목록을 기다린다. 이어 오는 울음이 인계를 낸다. */
    private fun handoffWhilePending(prep: Sim.() -> Unit = {}): Sim {
        val sim = session()
        sim.attempt2().toGap()
        sim.prep()
        sim.routeAt(0, pending = true).recry()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        return sim
    }

    @Test fun c269_handoffWhilePending_heldThenRestOffer() {
        val sim = handoffWhilePending().release()
        assertTrue(sim.spoke().none { it == Sid.S10 } && sim.asked().isEmpty(), "목록이 오기 전에는 보류")
        assertTrue(sim.alerts().isEmpty())
        sim.routeAt(1_000, rest("a", 8.0))
        assertEquals(listOf(Sid.S08), sim.asked())
        sim.done(2_000)
        assertEquals("a 휴게소", sim.alerts().single().str("rest_name"))
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind); assertEquals(true, sim.told())
        assertTrue(sim.spoke().none { it == Sid.S10 })
    }

    @Test fun c269_handoffWhilePending_listEmpty_thenS10() {
        val sim = handoffWhilePending().release().routeAt(1_000)
        assertEquals(listOf(Sid.S10), sim.spoke().filter { it == Sid.S10 })
        sim.done(2_000)
        assertEquals("no_rest", sim.alerts().single().str("kind")); assertEquals(true, sim.told())
    }

    @Test fun c269_handoffWhilePending_s10Banned_notDecidedByEmptyList() {
        var banAt = 0L
        val sim = handoffWhilePending { banAt = t - MIN; s = s.copy(session = ses.withOffers { copy(spokenAt = spokenAt + (Sid.S10 to banAt)) }) }
        assertTrue(sim.alerts().isEmpty(), "빈 목록으로 '막힘'을 정하지 않는다(이름 없는 알림을 바로 내지 않음)")
        assertTrue(sim.s.speech.queue.any { it.handoff })
        sim.release().routeAt(1_000, rest("a", 8.0))
        assertEquals(listOf(Sid.S08), sim.asked())
        assertEquals(banAt, sim.ses.spokenAt[Sid.S10], "자리만 잡은 줄이 앞 S10의 10분 금지를 덮거나 지우지 않는다")
    }

    @Test fun c269_handoffWhilePending_s10Banned_listEmpty_blockedPlain() {
        val sim = handoffWhilePending { s = s.copy(session = ses.withOffers { copy(spokenAt = spokenAt + (Sid.S10 to t - MIN)) }) }
        sim.release().routeAt(1_000)
        assertTrue(sim.spoke().none { it == Sid.S10 }, "10분 안에 S10을 냈으면 막힘(경로가 정해진 뒤 본다)")
        val a = sim.alerts().single()
        assertEquals("no_rest", a.str("kind")); assertEquals("아기가 계속 울어요.", a.str("text"))
        assertEquals(false, sim.told())
    }

    @Test fun c269_departOffer_notAskedWhilePending() {
        val sim = idle(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN).doorCycle().gear(Gear.D)
        sim.routeAt(0, rest("n", 30.0, nursing = true), pending = true)   // 장면 주입처럼 목록과 기다림이 같이 와도
        assertTrue(Sid.S07_DEPART !in sim.sids()); assertFalse(sim.ses.departChecked, "기다리는 동안 기회를 쓰지 않는다")
        sim.routeAt(1_000, rest("n", 30.0, nursing = true))
        assertEquals(listOf(Sid.S07_DEPART), sim.asked()); assertTrue(sim.ses.departChecked)
    }

    @Test fun c269_heldS10_notJudgedAgainstUnsettledList() {
        // 묶이지 않은 S10(수유 시간대 제안 등)이 앞 발화 뒤에 서 있다. 기다리는 동안의 목록으로 버리지 않는다(C246은 경로가 정해진 뒤)
        val sim = session().attempt1()
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = sim.s.speech.queue + Line(Sid.S10, emptyMap(), null, queuedAt = sim.t, sessionId = sim.ses.id)))
        sim.routeAt(0, rest("a", 8.0), pending = true).release()
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S10 }, "보류")
        sim.routeAt(1_000)
        assertEquals(listOf(Sid.S10), sim.spoke().filter { it == Sid.S10 })
    }

    @Test fun c269_waypointResultWhileListPending_saysRestName() {
        val sim = session().route(rest("a", 8.0), dest = true, remainingSec = 3600).handoff().release()
        val ask = sim.step<Ask>().single()
        sim.done(2_000).ev(Answer(sim.t + 1_000, ask.askId, YesNo.YES))
        val add = sim.step<AddWaypoint>().single()
        sim.routeAt(1_000, pending = true)   // 경유지로 경로가 다시 잡혀 목록을 다시 받는 중
        sim.ev(WaypointResult(sim.t + 1_000, add.cmdId, "a", true)).tick(P.speechGapMs)
        assertEquals("a 휴게소", sim.all<Speak>().single { it.sid == Sid.S09 }.vars["restName"], "쉼터 id를 읽지 않는다")
        assertNull(sim.ses.waypointPending)
    }

    // ---- C272: 인계가 아닌 쉼터 제안 줄도 꺼낼 때 같은 함수로 다시 고른다 ----

    @Test fun c272_feedingS10_heldWhilePending_restAppears_asksS08_noAlert() {
        // 수유 시간대 경보가 목록을 기다리는 동안 와서 제안이 S10으로 섰다
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).routeAt(0, pending = true).alarm().started()
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S10 && !it.handoff && !it.longDrive })
        sim.done().tick(P.speechGapMs).done().tick(P.speechGapMs)   // S02·S06
        assertTrue(Sid.S10 !in sim.spoke(), "기다리는 동안 보류")
        sim.routeAt(1_000, rest("a", 12.0))
        assertEquals(listOf(Sid.S08), sim.asked(), "쉼터가 생기면 버리지 않고 그 쉼터를 묻는다")
        assertTrue(Sid.S10 !in sim.spoke() && Sid.S10 !in sim.ses.spokenAt)
        assertTrue(sim.alerts().isEmpty() && sim.s.speech.queue.none { it.handoff }, "인계가 아닌 줄은 알림 장부가 없다")
    }

    @Test fun c272_longDriveS10_restAppears_asksS11_spokenSetsOffered() {
        val sim = session()
        sim.ev(Tick(T0 + 119 * MIN + 30_000)).attempt1()
        sim.ev(Tick(T0 + 120 * MIN))                                   // 쉼터 없는 길: 2시간 S10이 S02 뒤에 선다
        assertTrue(sim.s.speech.queue.single { it.sid == Sid.S10 }.longDrive)
        sim.routeAt(1_000, rest("a", 12.0)).release()
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S11, ask.sid); assertEquals("a 휴게소", ask.vars["restName"])
        assertFalse(sim.ses.longDriveOffered, "말하기 전에는 서지 않는다(C84)")
        sim.done(2_000)
        assertTrue(sim.ses.longDriveOffered)
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO)).tick(MIN)
        assertEquals(listOf(Sid.S11), sim.asked())
    }

    // ---- C273: 큐에는 한 쉼터를 묻는 제안 줄을 하나만 둔다 ----

    @Test fun c273_queuedS11_handoffS08SameRest_oneLine_noReaskAfterNo() {
        val sim = session().route(rest("a", 8.0))
        sim.ev(Tick(T0 + 119 * MIN + 30_000)).attempt1()
        sim.ev(Tick(T0 + 120 * MIN))                                   // 2시간 S11(a)이 S02 뒤에 선다
        assertEquals(Sid.S11, sim.s.speech.queue.single { it.askRestId == "a" }.sid)
        sim.toGap().recry().started().toGap().recry()                  // 인계가 같은 쉼터 a를 고른다
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        val q = sim.s.speech.queue.filter { it.askRestId == "a" }
        assertEquals(1, q.size, "따로 넣지 않고 넘겨받는다"); assertTrue(q.single().handoff && q.single().longDrive)
        sim.release()
        val ask = sim.step<Ask>().single(); assertEquals(Sid.S08, ask.sid)
        sim.done(2_000)
        assertTrue(sim.ses.longDriveOffered); assertEquals("a 휴게소", sim.alerts().single().str("rest_name"))
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO)).tick(MIN).tick(MIN)
        assertEquals(listOf(Sid.S08), sim.asked(), "'아니' 뒤 같은 쉼터를 다시 묻지 않는다")
    }

    @Test fun c273_departOfferQueued_longDriveSameRest_takesOverIt() {
        val n = RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)
        // 마이크가 꺼져 있어 S16·S17이 먼저 나가고, 출발 제안 S07_DEPART(n)은 그 뒤에 선다
        val sim = idle(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN, mic = false)
            .route(n, dest = true, remainingSec = 9000, remainingKm = 150.0).doorCycle().gear(Gear.D)
        assertTrue(Sid.S07_DEPART !in sim.sids())   // C350: 마이크가 꺼진 동안은 출발 제안을 내지 않는다
        sim.ev(MicService(sim.t + 100, true, MicPath.HOME_BUTTON))   // 마이크가 켜지면 S16·S17 뒤에 선다
        assertEquals(Sid.S07_DEPART, sim.s.speech.queue.single { it.askRestId == "n" }.sid); assertTrue(sim.asked().isEmpty())
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(driveFrom = sim.t - 121 * MIN) })   // 2시간을 넘겼다
        sim.tick(1_000)                                                  // L1이 같은 쉼터 n을 고른다
        val q = sim.s.speech.queue.filter { it.askRestId == "n" }
        assertEquals(listOf(Sid.S07_DEPART), q.map { it.sid }); assertTrue(q.single().longDrive, "출발 제안 줄이 장시간 출처를 넘겨받는다")
        while (sim.asked().isEmpty()) sim.done().tick(P.speechGapMs)   // S16·S17 뒤
        val ask = sim.all<Ask>().single(); assertEquals(Sid.S07_DEPART, ask.sid)
        sim.done()
        assertTrue(sim.ses.longDriveOffered)
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO)).tick(MIN)
        assertEquals(listOf(Sid.S07_DEPART), sim.asked())
    }

    @Test fun c273_answerDropsSameRestLineQueuedDuringListen() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 12.0)).alarm().started()
        sim.done().tick(P.speechGapMs).done().tick(P.speechGapMs)   // S02·S06 → S08(a)을 묻는다
        val ask = sim.step<Ask>().single(); assertEquals(Sid.S08, ask.sid)
        sim.done()                                                       // 묻기를 마치고 답을 듣는 중(줄은 큐에서 빠졌다)
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(driveFrom = sim.t - 121 * MIN) })
        sim.tick()                                                     // 그사이 L1이 같은 쉼터로 S11(a)을 넣는다(말하기 간격 S1로 아직 대기)
        assertEquals(Sid.S11, sim.s.speech.queue.single { it.askRestId == "a" }.sid)
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO))
        assertTrue(sim.s.speech.queue.none { it.askRestId == "a" }, "답이 나면 같은 쉼터의 남은 줄은 버린다")
        assertTrue(sim.ses.longDriveOffered, "같은 쉼터를 물었으니 장시간 출처는 들은 것으로 본다")
        sim.tick(P.speechGapMs).tick(MIN)
        assertEquals(listOf(Sid.S08), sim.asked())
    }
}
