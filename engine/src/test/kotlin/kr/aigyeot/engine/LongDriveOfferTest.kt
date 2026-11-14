package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// 장시간 제안과 인계가 겹칠 때, 경로·쉼터 목록을 기다리는 동안의 판단.
class LongDriveRoutePendingTest {
    private fun JsonObject.str(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun Sim.asked() = all<Ask>().map { it.sid }
    private fun Sim.spoke() = all<Speak>().map { it.sid }
    private fun Sim.release() = done(2_000).tick(P.speechGapMs)
    private fun Sim.routeAt(dt: Long, vararg rests: RestCandidate, pending: Boolean = false, sec: Int = 9000) =
        ev(RouteState(t + dt, true, sec, rests.toList(), 150.0, restsPending = pending))

    // ---- 인계가 줄을 바꿀 때 출처 속성(longDrive)을 옮긴다 ----

    /** 쉼터 없는 길에서 2시간 안내 S10(longDrive)이 S02 뒤에 서 있고, 인계가 그 줄을 넘겨받았다. */
    private fun longDriveTakenByHandoff(): Sim {
        val sim = session()
        sim.ev(Tick(T0 + 119 * MIN + 30_000)).attempt1()
        sim.ev(Tick(T0 + 120 * MIN))
        assertTrue(sim.s.speech.queue.single { it.sid == Sid.S10 }.longDrive, "조건: 장시간(2시간) S10이 큐에 있다")
        sim.toGap().recry().started().toGap().recry()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        val g = sim.s.speech.queue.single { it.handoff }
        assertEquals(Sid.S10, g.sid)
        assertTrue(g.longDrive, "인계 시각에 넘겨받은 줄은 그 줄 그대로(출처 속성 유지)")
        return sim
    }

    @Test fun takenLongDriveS10_swappedToS08_spokenSetsLongDriveOffered_noS11AfterNo() {
        val sim = longDriveTakenByHandoff()
        sim.routeAt(1_000, rest("a", 8.0)).release()
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S08, ask.sid)
        assertTrue(sim.s.speech.queue.first().longDrive, "바꾼 S08이 2시간 안내 출처를 이어받는다")
        assertFalse(sim.ses.longDriveOffered, "말하는 중에는 아직 아니다")
        sim.done(2_000)
        assertTrue(sim.ses.longDriveOffered, "S08을 말했으니 2시간 안내도 한 것이다")
        assertEquals("a 휴게소", sim.alertBodies().single().str("rest_name"))
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO))
        sim.tick(MIN).tick(MIN)
        assertEquals(listOf(Sid.S08), sim.asked(), "같은 쉼터로 S11을 다시 묻지 않는다('아니' 뒤에도)")
    }

    @Test fun swapIntoQueuedSameSentence_slotGetsLongDrive() {
        val sim = longDriveTakenByHandoff()
        // 묶이지 않은 S08(a)이 이미 큐에 있다(수유 시간대 제안 등). 꺼낼 때 S10 → S08을 고르면 그 줄 자리를 쓴다
        val other = Line(Sid.S08, mapOf("restName" to "a 휴게소", "distanceKm" to "8"), "a", queuedAt = sim.t, sessionId = sim.ses.id)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = sim.s.speech.queue + other))
        sim.routeAt(1_000, rest("a", 8.0)).release()
        assertEquals(Sid.S08, sim.step<Ask>().single().sid)
        assertEquals(1, sim.s.speech.queue.count { it.sid == Sid.S08 })
        assertTrue(sim.s.speech.queue.first().longDrive)
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
        sim.s = sim.s.copy(
            speech = if (speaking) sim.s.speech.copy(speaking = "u0", queue = listOf(old) + q) else sim.s.speech.copy(queue = q + old),
            session = sim.ses.withOffers { copy(driveFrom = sim.t - 121 * MIN) }
        ) // 2시간을 넘겼다: 장시간 제안이 같은 쉼터로 S11을 겹쳐 묻는지 본다
        return sim.recry()
    }

    @Test fun secondHandoff_replacedLongDriveGuide_newLineInherits() {
        val sim = secondHandoffOverLongDriveGuide(speaking = false)
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S10 }, "앞 표시 줄은 새 안내가 대신한다")
        val g = sim.s.speech.queue.single { it.handoff }
        assertEquals(Sid.S08, g.sid)
        assertTrue(g.longDrive)
        sim.flush()
        assertTrue(sim.ses.longDriveOffered)
        assertTrue(Sid.S11 !in sim.asked())
    }

    @Test fun secondHandoff_speakingLongDriveGuide_failed_newLineStillCarries() {
        val sim = secondHandoffOverLongDriveGuide(speaking = true)
        assertTrue(sim.s.speech.queue.single { it.handoff }.longDrive, "말하는 앞 줄은 표시만 떼고, 새 줄이 출처를 이어받는다")
        sim.ev(SpeechDone(sim.t + 1_000, "u0", false)) // 앞 줄을 끝까지 말하지 못했다
        assertFalse(sim.ses.longDriveOffered)
        sim.tick(P.speechGapMs).flush()
        assertTrue(sim.ses.longDriveOffered)
        assertTrue(Sid.S11 !in sim.asked(), "그사이 장시간 제안이 같은 쉼터로 S11을 겹쳐 묻지 않는다")
    }

    // ---- 쉼터 목록을 기다림 ≠ 쉼터 없음 ----

    /** 2시간을 넘긴 D 주행 중 새 경로로 안내가 시작돼 쉼터 목록을 다시 받는 중. */
    private fun pendingAfterTwoHours(): Sim = session().routeAt(121 * MIN, pending = true) // 2시간을 넘긴 뒤 첫 사건이 안내 시작

    @Test fun l1_waitsForRests_thenS11() {
        val sim = pendingAfterTwoHours()
        assertTrue(sim.sids().isEmpty(), "목록을 기다리는 동안 '쉼터를 못 찾았어요'를 말하지 않는다")
        sim.routeAt(2_000, rest("a", 12.0))
        assertEquals(listOf(Sid.S11), sim.asked())
    }

    @Test fun l1_listArrivesEmpty_thenS10() {
        val sim = pendingAfterTwoHours().routeAt(2_000)
        assertEquals(listOf(Sid.S10), sim.spoke())
    }

    @Test fun listNeverArrives_judgedAfterRouteWait_sinceKeptAcrossUpdates() {
        val sim = pendingAfterTwoHours()
        val since = sim.s.car.route!!.restsPendingSince!!
        sim.routeAt(30_000, pending = true, sec = 8970) // DrivingInfo 갱신: at은 바뀌어도 기다림 시작은 그대로
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

    @Test fun handoffWhilePending_heldThenRestOffer() {
        val sim = handoffWhilePending().release()
        assertTrue(sim.spoke().none { it == Sid.S10 } && sim.asked().isEmpty(), "목록이 오기 전에는 보류")
        assertTrue(sim.alertBodies().isEmpty())
        sim.routeAt(1_000, rest("a", 8.0))
        assertEquals(listOf(Sid.S08), sim.asked())
        sim.done(2_000)
        assertEquals("a 휴게소", sim.alertBodies().single().str("rest_name"))
        assertEquals(HandoffKind.WAYPOINT_OFFER, sim.ep.handoff!!.kind)
        assertEquals(true, sim.told())
        assertTrue(sim.spoke().none { it == Sid.S10 })
    }

    @Test fun handoffWhilePending_listEmpty_thenS10() {
        val sim = handoffWhilePending().release().routeAt(1_000)
        assertEquals(listOf(Sid.S10), sim.spoke().filter { it == Sid.S10 })
        sim.done(2_000)
        assertEquals("no_rest", sim.alertBodies().single().str("kind"))
        assertEquals(true, sim.told())
    }

    @Test fun handoffWhilePending_s10Banned_notDecidedByEmptyList() {
        var banAt = 0L
        val sim = handoffWhilePending {
            banAt = t - MIN
            s = s.copy(session = ses.withOffers { copy(spokenAt = spokenAt + (Sid.S10 to banAt)) })
        }
        assertTrue(sim.alertBodies().isEmpty(), "빈 목록으로 '막힘'을 정하지 않는다(이름 없는 알림을 바로 내지 않음)")
        assertTrue(sim.s.speech.queue.any { it.handoff })
        sim.release().routeAt(1_000, rest("a", 8.0))
        assertEquals(listOf(Sid.S08), sim.asked())
        assertEquals(banAt, sim.ses.spokenAt[Sid.S10], "자리만 잡은 줄이 앞 S10의 10분 금지를 덮거나 지우지 않는다")
    }

    @Test fun handoffWhilePending_s10Banned_listEmpty_blockedPlain() {
        val sim = handoffWhilePending { s = s.copy(session = ses.withOffers { copy(spokenAt = spokenAt + (Sid.S10 to t - MIN)) }) }
        sim.release().routeAt(1_000)
        assertTrue(sim.spoke().none { it == Sid.S10 }, "10분 안에 S10을 냈으면 막힘(경로가 정해진 뒤 본다)")
        val a = sim.alertBodies().single()
        assertEquals("no_rest", a.str("kind"))
        assertEquals("아기가 계속 울어요.", a.str("text"))
        assertEquals(false, sim.told())
    }

    @Test fun departOffer_notAskedWhilePending() {
        val sim = idle(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN).doorCycle().gear(Gear.D)
        sim.routeAt(0, rest("n", 30.0, nursing = true), pending = true) // 장면 주입처럼 목록과 기다림이 같이 와도
        assertTrue(Sid.S07_DEPART !in sim.sids())
        assertFalse(sim.ses.departChecked, "기다리는 동안 기회를 쓰지 않는다")
        sim.routeAt(1_000, rest("n", 30.0, nursing = true))
        assertEquals(listOf(Sid.S07_DEPART), sim.asked())
        assertTrue(sim.ses.departChecked)
    }

    @Test fun heldS10_notJudgedAgainstUnsettledList() {
        // 묶이지 않은 S10(수유 시간대 제안 등)이 앞 발화 뒤에 서 있다. 기다리는 동안의 목록으로 버리지 않는다(경로가 정해진 뒤에는 꺼낼 때 다시 본다)
        val sim = session().attempt1()
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = sim.s.speech.queue + Line(Sid.S10, emptyMap(), null, queuedAt = sim.t, sessionId = sim.ses.id)))
        sim.routeAt(0, rest("a", 8.0), pending = true).release()
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S10 }, "보류")
        sim.routeAt(1_000)
        assertEquals(listOf(Sid.S10), sim.spoke().filter { it == Sid.S10 })
    }

    @Test fun waypointResultWhileListPending_saysRestName() {
        val sim = session().route(rest("a", 8.0), dest = true, remainingSec = 3600).handoff().release()
        val ask = sim.step<Ask>().single()
        sim.done(2_000).ev(Answer(sim.t + 1_000, ask.askId, YesNo.YES))
        val add = sim.step<AddWaypoint>().single()
        sim.routeAt(1_000, pending = true) // 경유지로 경로가 다시 잡혀 목록을 다시 받는 중
        sim.ev(WaypointResult(sim.t + 1_000, add.cmdId, "a", true)).tick(P.speechGapMs)
        assertEquals("a 휴게소", sim.all<Speak>().single { it.sid == Sid.S09 }.vars["restName"], "쉼터 id를 읽지 않는다")
        assertNull(sim.ses.waypointPending)
    }

    // ---- 인계가 아닌 쉼터 제안 줄도 꺼낼 때 같은 함수로 다시 고른다 ----

    @Test fun feedingS10_heldWhilePending_restAppears_asksS08_noAlert() {
        // 수유 시간대 경보가 목록을 기다리는 동안 와서 제안이 S10으로 섰다
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).routeAt(0, pending = true).alarm().started()
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S10 && !it.handoff && !it.longDrive })
        sim.done().tick(P.speechGapMs).done().tick(P.speechGapMs) // S02·S06
        assertTrue(Sid.S10 !in sim.spoke(), "기다리는 동안 보류")
        sim.routeAt(1_000, rest("a", 12.0))
        assertEquals(listOf(Sid.S08), sim.asked(), "쉼터가 생기면 버리지 않고 그 쉼터를 묻는다")
        assertTrue(Sid.S10 !in sim.spoke() && Sid.S10 !in sim.ses.spokenAt)
        assertTrue(sim.alertBodies().isEmpty() && sim.s.speech.queue.none { it.handoff }, "인계가 아닌 줄은 알림 장부가 없다")
    }

    @Test fun longDriveS10_restAppears_asksS11_spokenSetsOffered() {
        val sim = session()
        sim.ev(Tick(T0 + 119 * MIN + 30_000)).attempt1()
        sim.ev(Tick(T0 + 120 * MIN)) // 쉼터 없는 길: 2시간 S10이 S02 뒤에 선다
        assertTrue(sim.s.speech.queue.single { it.sid == Sid.S10 }.longDrive)
        sim.routeAt(1_000, rest("a", 12.0)).release()
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S11, ask.sid)
        assertEquals("a 휴게소", ask.vars["restName"])
        assertFalse(sim.ses.longDriveOffered, "말하기 전에는 서지 않는다")
        sim.done(2_000)
        assertTrue(sim.ses.longDriveOffered)
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO)).tick(MIN)
        assertEquals(listOf(Sid.S11), sim.asked())
    }

    // ---- 큐에는 한 쉼터를 묻는 제안 줄을 하나만 둔다 ----

    @Test fun queuedS11_handoffS08SameRest_oneLine_noReaskAfterNo() {
        val sim = session().route(rest("a", 8.0))
        sim.ev(Tick(T0 + 119 * MIN + 30_000)).attempt1()
        sim.ev(Tick(T0 + 120 * MIN)) // 2시간 S11(a)이 S02 뒤에 선다
        assertEquals(Sid.S11, sim.s.speech.queue.single { it.askRestId == "a" }.sid)
        sim.toGap().recry().started().toGap().recry() // 인계가 같은 쉼터 a를 고른다
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        val q = sim.s.speech.queue.filter { it.askRestId == "a" }
        assertEquals(1, q.size, "따로 넣지 않고 넘겨받는다")
        assertTrue(q.single().handoff && q.single().longDrive)
        sim.release()
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S08, ask.sid)
        sim.done(2_000)
        assertTrue(sim.ses.longDriveOffered)
        assertEquals("a 휴게소", sim.alertBodies().single().str("rest_name"))
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO)).tick(MIN).tick(MIN)
        assertEquals(listOf(Sid.S08), sim.asked(), "'아니' 뒤 같은 쉼터를 다시 묻지 않는다")
    }

    @Test fun departOfferQueued_longDriveSameRest_takesOverIt() {
        val n = RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)
        // 마이크가 꺼져 있어 S16·S17이 먼저 나가고, 출발 제안 S07_DEPART(n)은 그 뒤에 선다
        val sim = idle(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN, mic = false)
            .route(n, dest = true, remainingSec = 9000, remainingKm = 150.0).doorCycle().gear(Gear.D)
        assertTrue(Sid.S07_DEPART !in sim.sids()) // 마이크가 꺼진 동안은 출발 제안을 내지 않는다
        sim.ev(MicService(sim.t + 100, true, MicPath.HOME_BUTTON)) // 마이크가 켜지면 S16·S17 뒤에 선다
        assertEquals(Sid.S07_DEPART, sim.s.speech.queue.single { it.askRestId == "n" }.sid)
        assertTrue(sim.asked().isEmpty())
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(driveFrom = sim.t - 121 * MIN) }) // 2시간을 넘겼다
        sim.tick(1_000) // 장시간 제안이 같은 쉼터 n을 고른다
        val q = sim.s.speech.queue.filter { it.askRestId == "n" }
        assertEquals(listOf(Sid.S07_DEPART), q.map { it.sid })
        assertTrue(q.single().longDrive, "출발 제안 줄이 장시간 출처를 넘겨받는다")
        while (sim.asked().isEmpty()) sim.done().tick(P.speechGapMs) // S16·S17 뒤
        val ask = sim.all<Ask>().single()
        assertEquals(Sid.S07_DEPART, ask.sid)
        sim.done()
        assertTrue(sim.ses.longDriveOffered)
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO)).tick(MIN)
        assertEquals(listOf(Sid.S07_DEPART), sim.asked())
    }

    @Test fun answerDropsSameRestLineQueuedDuringListen() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 12.0)).alarm().started()
        sim.done().tick(P.speechGapMs).done().tick(P.speechGapMs) // S02·S06 → S08(a)을 묻는다
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S08, ask.sid)
        sim.done() // 묻기를 마치고 답을 듣는 중(줄은 큐에서 빠졌다)
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(driveFrom = sim.t - 121 * MIN) })
        sim.tick() // 그사이 장시간 제안이 같은 쉼터로 S11(a)을 넣는다(말하기 간격으로 아직 대기)
        assertEquals(Sid.S11, sim.s.speech.queue.single { it.askRestId == "a" }.sid)
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO))
        assertTrue(sim.s.speech.queue.none { it.askRestId == "a" }, "답이 나면 같은 쉼터의 남은 줄은 버린다")
        assertTrue(sim.ses.longDriveOffered, "같은 쉼터를 물었으니 장시간 출처는 들은 것으로 본다")
        sim.tick(P.speechGapMs).tick(MIN)
        assertEquals(listOf(Sid.S08), sim.asked())
    }
}

// 말하지 못한 질문, 쉼터 제안 반복 금지와 재시도, 울음 수 세기.
class OfferRetryAndBanTest {
    private fun Sim.asked() = all<Ask>().map { it.sid }
    private fun Sim.spoke() = all<Speak>().map { it.sid }

    /** 목적지 없는 경로. pending이면 쉼터 목록을 다시 받는 중. */
    private fun Sim.road(dt: Long, vararg rests: RestCandidate, pending: Boolean = false) =
        ev(RouteState(t + dt, false, null, rests.toList(), restsPending = pending))

    // ---- '물었다·들었다'는 그 줄의 SpeechDone(spoken = true)에만 근거한다 ----

    @Test fun s11TtsFailed_answerNone_notHeard_reaskedThenNoSetsOffered() {
        val sim = session().route(rest("a", 8.0))
        sim.ev(Tick(T0 + 120 * MIN))
        val ask1 = sim.step<Ask>().single()
        assertEquals(Sid.S11, ask1.sid)
        sim.done(spoken = false) // TTS 실패: 같은 Step에서 장시간 제안이 S11을 다시 넣는다(말하기 간격으로 대기)
        assertNull(sim.ses.pendingAsk, "발화되지 않은 질문은 답을 기다리지 않는다")
        assertTrue(sim.s.speech.queue.single().let { it.sid == Sid.S11 && it.longDrive })
        sim.ev(Answer(sim.t, ask1.askId, YesNo.NONE)) // 실행기가 발화 실패 뒤 보내는 '답 없음'
        assertFalse(sim.ses.longDriveOffered, "운전자는 듣지 못했다")
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S11 }, "다시 넣은 질문을 답 뒤 정리로 지우지 않는다")
        sim.tick(P.speechGapMs)
        val ask2 = sim.step<Ask>().single()
        assertEquals(Sid.S11, ask2.sid)
        sim.done(2_000)
        assertTrue(sim.ses.longDriveOffered, "실제로 말했다")
        sim.ev(Answer(sim.t + 1_000, ask2.askId, YesNo.NO)).tick(MIN).tick(MIN)
        assertTrue(sim.ses.longDriveOffered)
        assertEquals(listOf(Sid.S11, Sid.S11), sim.asked())
    }

    @Test fun unspokenQuestion_lateYesIgnored_noWaypoint() {
        val sim = session().route(rest("a", 8.0)).handoff()
        repeat(6) {
            if (sim.asked().isEmpty()) {
                if (sim.s.speech.speaking != null) sim.done()
                sim.tick(P.speechGapMs)
            }
        } // S02·S03 뒤 인계 질문
        val ask = sim.all<Ask>().single()
        assertEquals(Sid.S08, ask.sid)
        sim.done(spoken = false)
        sim.ev(Answer(sim.t, ask.askId, YesNo.YES)) // 장면 답·늦은 답
        assertTrue(sim.all<AddWaypoint>().isEmpty(), "듣지 못한 질문의 답으로 경유지를 넣지 않는다")
        assertNull(sim.ses.restLog.last().answer, "답을 기록하지 않는다")
        assertEquals(false, sim.told())
    }

    @Test fun answerCleanup_droppedLongDriveLine_noBanForUnspokenSentence() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 12.0)).alarm().started()
        sim.done().tick(P.speechGapMs).done().tick(P.speechGapMs) // S02·S06 → S08(a)을 묻는다
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S08, ask.sid)
        sim.done()
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(driveFrom = sim.t - 121 * MIN) })
        sim.tick() // 장시간 제안이 같은 쉼터로 S11(a)을 넣는다
        assertEquals(Sid.S11, sim.s.speech.queue.single { it.askRestId == "a" }.sid)
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO))
        assertTrue(sim.ses.longDriveOffered, "같은 쉼터를 물었으니 장시간 출처는 들은 것으로 본다")
        assertTrue(Sid.S11 !in sim.ses.spokenAt, "버린 S11은 말하지 않았으니 그 문장의 10분 금지는 남기지 않는다")
    }

    // ---- 경로가 정해지지 않은 동안의 쉼터 제안은 금지를 보지 않고 자리만 잡고, 꺼낼 때 본다 ----

    /** 수유 시간대에 쉼터 없는 길에서 S10을 말했고(10분 금지) 첫 울음은 그쳤다. 금지 시각을 돌려준다. */
    private fun feedingS10Spoken(): Pair<Sim, Long> {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).road(0)
        sim.alarm().started().flush() // S02·S06·S10
        assertEquals(listOf(Sid.S10), sim.spoke().filter { it == Sid.S10 })
        val banAt = sim.ses.spokenAt[Sid.S10]!!
        sim.toConfirm().closeQuiet()
        assertNull(sim.ses.episode)
        return sim to banAt
    }

    /** 새 경로가 쉼터 목록을 다시 받는 동안 다시 운다. 수유 시간대 제안이 선 뒤 S02·S06을 끝낸다. */
    private fun Sim.cryWhilePending(): Sim {
        road(0, pending = true).alarm().started()
        done().tick(P.speechGapMs).done().tick(P.speechGapMs)
        return this
    }

    @Test fun feedingOfferWhilePending_bannedS10_heldThenAsksS07() {
        val (sim, banAt) = feedingS10Spoken()
        sim.tick(MIN).cryWhilePending()
        val held = sim.s.speech.queue.single { it.sid == Sid.S10 }
        assertTrue(!held.handoff && !held.longDrive, "앞 S10의 금지에 걸려 사라지지 않고 자리만 잡는다")
        assertEquals(banAt, sim.ses.spokenAt[Sid.S10], "자리만 잡은 줄이 앞 금지를 덮지 않는다")
        assertEquals(1, sim.spoke().count { it == Sid.S10 }, "기다리는 동안 보류")
        sim.road(1_000, rest("n", 12.0, nursing = true))
        assertEquals(listOf(Sid.S07), sim.asked(), "목록이 오면 S07을 묻는다")
        assertEquals(banAt, sim.ses.spokenAt[Sid.S10])
    }

    @Test fun heldS10_listEmpty_bannedAtTakeOut_dropped() {
        val (sim, banAt) = feedingS10Spoken()
        sim.tick(MIN).cryWhilePending()
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S10 }, "전제: 보류 줄")
        sim.road(1_000).tick(P.speechGapMs) // 목록이 왔고 쉼터가 없다
        assertEquals(1, sim.spoke().count { it == Sid.S10 }, "꺼낼 때 실제로 고른 문장(S10)의 10분 금지를 본다")
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S10 }, "금지에 걸린 보류 줄이 남지 않는다")
        assertEquals(banAt, sim.ses.spokenAt[Sid.S10])
    }

    @Test fun heldS10_banExpiredByTakeOut_spoken_banFromSpeech() {
        val (sim, banAt) = feedingS10Spoken()
        sim.ev(Tick(banAt + P.offerRepeatMs - 30_000)).cryWhilePending() // 금지가 끝나기 30초 전에 자리만 잡는다
        assertEquals(banAt, sim.ses.spokenAt[Sid.S10])
        sim.road(30_000).tick(P.speechGapMs) // 목록(쉼터 없음)이 올 때는 금지가 끝났다
        assertEquals(2, sim.spoke().count { it == Sid.S10 })
        sim.done(2_000)
        assertEquals(sim.t, sim.ses.spokenAt[Sid.S10], "넣을 때 금지를 남기지 않은 줄은 말한 때부터 막는다")
    }

    // ---- 이번 세션 울음 수는 판단 코어가 센다(이어진 울음마다 하나) ----

    @Test fun cryRuns_alarmEpisodePath_clearedToZero() {
        val sim = session()
        repeat(2) { sim.attempt1().toConfirm().closeQuiet() }
        assertEquals(2, sim.ses.cryRuns, "경보 에피소드 길도 같은 기준(경보로 연 울음마다 하나)")
        sim.alarm().ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.NO_REAR_OUTPUT)) // 소리 없이 연 경보 에피소드
        assertEquals(EpisodeTrigger.ALARM, sim.ep.trigger)
        assertEquals(3, sim.ses.cryRuns)
        sim.ev(ClearRecords(sim.t))
        assertEquals(0, sim.ses.cryRuns, "기록을 지우면 0")
        assertNull(sim.ses.alarmRun)
        sim.alarm(975) // 지우기 전부터 울던 울음이 이어져도
        assertEquals(1, sim.ses.cryRuns, "지운 뒤부터 새로 센다(지금 울음과 수가 어긋나지 않게)")
        sim.ev(Tick(sim.t + P.resumeQuietMs)) // 인계가 조용히 끝난다
        sim.attempt1()
        assertEquals(2, sim.ses.cryRuns)
    }

    // ---- 이어진 경보를 단계와 상관없이 센다 ----

    @Test fun alarmRun_trackedInSwitchSoundEpisode_restartsAfterGap() {
        val sim = session().ev(SwitchSound(T0, LL)).started()
        assertEquals(Phase.WAIT, sim.ses.phase)
        sim.alarm(1_000).alarm(975).alarm(975) // 한 걸음마다 경보
        assertEquals(AlarmRun(T0 + 1_000, T0 + 2_950), sim.ses.alarmRun)
        assertEquals(1, sim.ses.cryRuns, "소리를 직접 튼 중의 울음도 센다")
        sim.alarm(2 * P.yamnetHopMs + 1) // 문턱보다 오래 끊겼다
        assertEquals(AlarmRun(sim.t, sim.t), sim.ses.alarmRun, "새로 이어진 경보")
        assertEquals(2, sim.ses.cryRuns, "끊겼다 다시 울면 하나 더")
        sim.ev(CrySegment(sim.t + 975, sim.t - 2_025, sim.t + 975, 0.9, false)) // 경보 아닌 구간은 세지 않는다
        assertEquals(sim.t - 975, sim.ses.alarmRun!!.last)
        assertEquals(2, sim.ses.cryRuns)
    }

    @Test fun autoAttemptPlaying_alarmsNotCounted_gapRecryCountedNew_waitCounted() {
        val sim = session().attempt1() // LISTENING 경보로 연 울음 하나 → 자동 시도 재생
        val run = sim.ses.alarmRun
        assertEquals(1, sim.ses.cryRuns)
        assertEquals(Phase.ATTEMPT, sim.ses.phase)
        sim.alarm(5_000).alarm(975) // 재생 중 경보(노래 오인일 수 있다)
        assertEquals(run, sim.ses.alarmRun, "자동 시도 재생 중에는 이어짐을 갱신하지 않는다")
        assertEquals(1, sim.ses.cryRuns)
        sim.toGap()
        val from = sim.ses.judgeFrom!!
        sim.ev(CrySegment(from + 3_000, from, from + 3_000, 0.9, true)) // 재생이 끝난 뒤 다시 운다
        assertEquals(2, sim.ses.cryRuns, "재생 뒤 다시 울면 새로 센다")
        assertEquals(Phase.ATTEMPT, sim.ses.phase)
        sim.ev(DriverStop(sim.t + 1_000, Via.VOICE)) // 운전자 중단 → WAIT
        assertEquals(Phase.WAIT, sim.ses.phase)
        sim.alarm(5_000)
        assertEquals(3, sim.ses.cryRuns, "WAIT 중 경보는 센다")
    }

    @Test fun segmentStartedDuringPlayback_notCounted() {
        val sim = session().attempt1().toGap()
        val from = sim.ses.judgeFrom!!
        assertEquals(1, sim.ses.cryRuns)
        sim.ev(CrySegment(from + 975, from - 2_025, from + 975, 0.9, true, null)) // 재생 끝 전에 시작한 구간
        assertEquals(1, sim.ses.cryRuns, "판정이 보지 않는 구간은 세지 않는다")
        sim.ev(CrySegment(from + 3_000, from, from + 3_000, 0.9, true)) // 재생 뒤 시작: 판정(다시 울음)과 함께 센다
        assertEquals(2, sim.ses.cryRuns)
    }

    @Test fun switchSoundEpisode_noAlarm_zeroRuns() {
        val sim = session().ev(SwitchSound(T0, LL))
        assertEquals(EpisodeTrigger.SWITCH_SOUND, sim.ep.trigger)
        assertEquals(0, sim.ses.cryRuns)
    }

    // ---- 같은 문장이라는 이유로 다른 쉼터 줄을 다시 쓰지 않는다 ----

    private fun JsonObject.str(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private val nb = rest("b", 20.0, nursing = true)

    /**
     * 2차 시도 GAP. 큐에는 쉼터 a를 묻는 S07 줄(speaking이면 말하는 중, 발화 id "uA"). 경로에는 [route]만 있다.
     * 큐(말하는 맨 앞 줄 포함)에 있는 a 줄이 같은 문장(S07)을 막는다(넣을 때 남기던 금지 시각은 없다).
     * otherHead면 다른 문장(S12)이 앞에서 말하는 중이라 인계 Step에 꺼내기(다시 고르기)가 돌지 않는다.
     */
    private fun gapWithA(speaking: Boolean, route: RestCandidate = nb, otherHead: Boolean = false): Sim {
        val sim = session().route(route).attempt2().toGap()
        val a = Line(Sid.S07, mapOf("restName" to "a 휴게소", "distanceKm" to "12"), "a", queuedAt = sim.t, sessionId = sim.ses.id)
        val head = listOfNotNull(Line(Sid.S12, emptyMap(), null, queuedAt = sim.t, sessionId = sim.ses.id).takeIf { otherHead })
        sim.s = sim.s.copy(
            speech = SpeechQueue(
                if (speaking) {
                    "uA"
                } else if (otherHead) {
                    "u0"
                } else {
                    null
                },
                sim.t,
                head + a
            ),
            session = sim.ses.withOffers {
                copy(pendingAsk = if (speaking) PendingAsk("uA", Sid.S07, "a", sim.ep.id, true) else null)
            }
        )
        return sim
    }

    @Test fun speakingOtherRest_banned_notBoundToSpeaking_plainAlert() {
        val sim = gapWithA(speaking = true).recry()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertNull(sim.ledger, "말하는 a 질문에 묶지 않는다")
        val a = sim.alertBodies().single()
        assertNull(a.str("rest_name"))
        assertEquals("아기가 계속 울어요.", a.str("text"), "S07이 10분 금지라 이름 없는 알림")
        assertEquals(false, sim.told())
        sim.done() // a 질문이 끝나도
        assertEquals(1, sim.alertBodies().size)
        assertTrue(sim.alertBodies().none { "a 휴게소" in it.toString() })
    }

    @Test fun speakingOtherRest_unheard_bNotAsked_plain() { // 말하는 a(S07)가 큐에 있어 b(S07) 줄을 넣지 않는다. a가 들리지 않아도 그대로
        val sim = gapWithA(speaking = true).recry()
        assertNull(sim.ledger, "새 b 줄이 없다")
        sim.done(spoken = false).tick(P.speechGapMs) // a는 들리지 않았다
        assertTrue(sim.step<Ask>().isEmpty())
        val a = sim.alertBodies().single()
        assertNull(a.str("rest_name"))
        assertEquals(false, sim.told())
    }

    @Test fun speakingOtherRest_notBanned_aHeard_bBannedAtTakeOut_plain() {
        val sim = gapWithA(speaking = true).recry()
        sim.done(2_000).tick(P.speechGapMs) // a S07을 들었으니 10분 안의 S07(b)은 10분 금지에 걸린다
        assertTrue(sim.step<Ask>().isEmpty())
        val a = sim.alertBodies().single()
        assertNull(a.str("rest_name"))
        assertEquals(false, sim.told())
    }

    @Test fun queuedOtherRest_handoff_rewrittenToB() {
        val sim = gapWithA(speaking = false, otherHead = true).recry()
        val l = sim.s.speech.queue.single { it.sid == Sid.S07 }
        assertTrue(l.handoff)
        assertEquals("b", l.askRestId)
        assertEquals("b 휴게소", l.vars["restName"], "인계 때도 지금 쉼터 값으로 바꿔 묶는다(꺼내기 전)")
        sim.done(2_000).tick(P.speechGapMs)
        assertEquals("b 휴게소", sim.step<Ask>().single().vars["restName"])
        sim.done()
        assertEquals("b 휴게소", sim.alertBodies().single().str("rest_name"))
    }

    @Test fun speakingSameRest_handoff_boundToUtterance() { // 말하는 줄은 그대로 묶는다
        val sim = gapWithA(speaking = true, route = rest("a", 12.0, nursing = true)).recry()
        assertEquals("uA", sim.ledger!!.utteranceId)
        sim.done()
        assertEquals("a 휴게소", sim.alertBodies().single().str("rest_name"))
        assertEquals(true, sim.told())
    }

    // ---- 미룸·만료·앞 버림은 다시 고른 문장으로 판단한다 ----

    private fun Sim.busy(dt: Long) = ev(VehicleSample(t + dt, 60.0, true, 0.0, TurnSignal.NONE))

    /** 쉼터 목록을 기다리는 동안 인계가 임시 S10을 큐에 두었다. 앞 안내를 끝내고 [waitMs] 더 기다린다(목록은 아직). */
    private fun heldHandoffS10(waitMs: Long = 0): Sim {
        val sim = session().attempt2().toGap().road(0, pending = true).recry()
        assertEquals(Sid.S10, sim.s.speech.queue.single { it.handoff }.sid)
        sim.done().tick(P.speechGapMs).tick(waitMs)
        assertTrue(sim.asked().isEmpty() && Sid.S10 !in sim.spoke(), "전제: 목록을 기다리며 보류")
        return sim
    }

    @Test fun heldS10_listBringsS08_busyOver30s_askedWithName() {
        val sim = heldHandoffS10()
        sim.busy(0).road(1_000, rest("n", 8.0)) // 바쁜 중에 목록이 온다: 지금 안내는 S08(미루지 않음)
        repeat(35) { if (sim.asked().isEmpty()) sim.busy(1_000) } // 바쁨이 30초(holdMaxMs) 넘게 이어져도
        val ask = sim.all<Ask>().single()
        assertEquals(Sid.S08, ask.sid)
        assertEquals("n 휴게소", ask.vars["restName"])
        assertTrue(sim.alertBodies().isEmpty(), "이름 없는 알림을 먼저 보내지 않는다")
        sim.done(2_000)
        assertEquals("n 휴게소", sim.alertBodies().single().str("rest_name"))
        assertEquals(true, sim.told())
    }

    @Test fun heldS10_listBringsS07_newLineNotExpiredByHoldWait() {
        val sim = heldHandoffS10(waitMs = 16_000) // 임시 줄은 목록을 25초 가까이 기다렸다
        sim.busy(0).road(1_000, rest("n", 8.0, nursing = true)) // S07은 미루는 문장이다
        val l = sim.s.speech.queue.single { it.handoff }
        assertEquals(Sid.S07, l.sid)
        assertEquals(sim.t, l.queuedAt, "새 줄의 만료 기준은 지금(임시 줄이 기다린 시간을 잇지 않는다)")
        repeat(10) { sim.busy(1_000) } // 임시 줄 시각이면 30초를 넘었을 바쁨
        assertTrue(sim.asked().isEmpty(), "바쁜 동안은 미룬다")
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        assertEquals(Sid.S07, sim.step<Ask>().single().sid, "만료되지 않고 잠잠해지면 묻는다")
    }

    @Test fun heldS10_listEmpty_stillS10_heldWhileBusy_thenSaid() {
        val sim = heldHandoffS10()
        sim.busy(0).road(1_000) // 목록이 왔고 쉼터가 없다: 다시 골라도 S10
        sim.busy(1_000)
        assertTrue(Sid.S10 !in sim.spoke(), "지금처럼 바쁨에 미룬다")
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        assertEquals(Sid.S10, sim.step<Speak>().single().sid)
    }

    @Test fun heldS10_waited40s_stillS10_busyRightAway_notExpired_saidAfterCalm() {
        val sim = heldHandoffS10(waitMs = 21_000)
        repeat(10) { sim.busy(1_000) } // 임시 S10이 목록을 40초 기다렸고 그 끝 10초는 바빴다(보류 중이라 만료 아님)
        assertTrue(sim.s.speech.queue.any { it.handoff && it.sid == Sid.S10 })
        sim.road(1_000) // 목록이 왔고 쉼터가 없다: 그대로 S10, 곧바로 바쁨
        val l = sim.s.speech.queue.single { it.handoff }
        assertEquals(sim.t, l.queuedAt, "만료 기준은 경로가 정해진 지금")
        assertNull(sim.ses.spokenAt[Sid.S10], "말하기 전에는 금지 시각이 없다(옮길 짝이 없다)")
        repeat(5) { sim.busy(1_000) }
        assertTrue(Sid.S10 !in sim.spoke(), "바쁜 동안은 미룬다")
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        assertEquals(Sid.S10, sim.step<Speak>().single().sid, "기다린 시간 때문에 만료되지 않고 잠잠해지면 말한다")
    }

    // 복원 기다림 중 보류한 S10이, 새 경로 기다림으로 이어진 기다림의 시작이 밀려도 버려지지 않는다
    @Test fun bootWaitExpiresDuringRestsPending_heldS10Kept() {
        val sim = session()
        sim.s = sim.s.copy(bootAt = T0)
        sim.alarm(1_000)
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.NO_REAR_OUTPUT)) // S10 보류
        sim.ev(RouteState(T0 + 35_000, true, 3600, emptyList(), 150.0, true)) // 새 경로, 쉼터 목록 기다림
        sim.ev(Tick(T0 + 60_000)) // 복원 기다림 만료
        sim.ev(VehicleSample(T0 + 64_000, 60.0, true, 0.0, TurnSignal.NONE)) // 제동
        sim.ev(RouteState(T0 + 65_000, true, 3600, emptyList(), 149.0, false)) // 빈 목록 도착
        sim.ev(Tick(T0 + 70_000))
        assertTrue(Sid.S10 in sim.spoke() || sim.s.speech.queue.any { it.sid == Sid.S10 }, "보류한 S10을 버리지 않는다")
    }

    @Test fun lineQueuedBeforeWait_keepsOriginalQueuedAt() {
        val sim = session().road(0).attempt2().toGap().recry() // 정해진 경로(쉼터 없음)에서 인계 S10
        val at = sim.s.speech.queue.single { it.handoff }.queuedAt
        sim.road(1_000, pending = true).road(5_000) // 그 뒤 목록을 다시 받았다
        assertEquals(at, sim.s.speech.queue.single { it.handoff }.queuedAt, "기다림 전에 넣은 줄은 원래 시각")
    }

    @Test fun offer_queuedOtherRestSameSentence_rewrittenToB() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(nb)
        val head = Line(Sid.S12, emptyMap(), null, queuedAt = sim.t, sessionId = sim.ses.id) // 말하는 중인 다른 문장(꺼내기가 먼저 다시 고르지 않게)
        val a = Line(Sid.S07, mapOf("restName" to "a 휴게소", "distanceKm" to "12"), "a", queuedAt = sim.t, sessionId = sim.ses.id)
        sim.s = sim.s.copy(speech = SpeechQueue("u0", sim.t, listOf(head, a)))
        sim.alarm().started() // 수유 시간대 제안이 S07(b)을 고른다
        val l = sim.s.speech.queue.single { it.sid == Sid.S07 }
        assertEquals("b", l.askRestId, "인계가 아닌 제안도 같은 규칙")
        assertFalse(l.handoff)
    }

    /** 2시간이 되는 순간 경로가 수유실 휴게소로 바뀌어 S11이 큐에 들어가면, 다시 고르기가 겹친 줄을 S07로 바꾸되 10분 금지를 본다. */
    private fun c308Sim(banS07: Boolean): Sim {
        val p = Params()
        val sim = session().route(rest("a", 8.0)).handoff()
        val guide = sim.s.speech.queue.single { it.handoff } // S08
        val now = sim.t
        sim.s = sim.s.copy(
            speech = sim.s.speech.copy(speaking = null, queue = listOf(guide), lastDoneAt = now - p.speechGapMs),
            session = sim.ses.withOffers {
                copy(driveFrom = now + 1000 - p.longDriveMs, spokenAt = if (banS07) spokenAt + (Sid.S07 to now - 2 * MIN) else spokenAt)
            }
        )
        sim.ev(RouteState(now + 1000, true, 3600, listOf(rest("b", 9.0, true)), 40.0))
        return sim
    }

    @Test fun repickOverlapRespectsBan() {
        assertTrue(c308Sim(banS07 = true).step<Ask>().none { it.sid == Sid.S07 }, "S07을 말한 지 10분이 안 됐으면 겹친 줄을 S07로 바꿔 묻지 않는다")
    }

    @Test fun repickOverlapWithoutBan_becomesS07() {
        assertEquals(1, c308Sim(banS07 = false).step<Ask>().count { it.sid == Sid.S07 })
    }
}
