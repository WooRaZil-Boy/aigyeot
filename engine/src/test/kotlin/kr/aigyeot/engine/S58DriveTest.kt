package kr.aigyeot.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 5.8 장시간 주행·도착·수유 시계·말하기·④ 묻기·에피소드·우선순위·조향.
class S58DriveTest {
    private val nurse = rest("n", 9.0, nursing = true)
    /** 수유실 경유지를 넣은 세션(장면 흉내 DriverAddWaypoint, listenId 없음). */
    private fun added(): Sim {
        val sim = session().route(nurse).ev(DriverAddWaypoint(T0, "n"))
        return sim.ev(WaypointResult(sim.t + 500, sim.step<AddWaypoint>().single().cmdId, "n", true))
    }
    /** ④ 질문을 던져 답을 기다리는 상태(THINKING). */
    private fun Sim.asking(text: String = "왜 울어?"): Sim {
        if (s.speech.qa == null) ev(AskButton(t))
        return ev(Heard(t, s.speech.qa!!.listenId, text))
    }
    private val Sim.qa get() = s.speech.qa!!

    @Test fun l1_longDrive_offersS11() {
        val sim = session().route(rest("a", 12.0)).ev(Tick(T0 + 119 * MIN + 30_000)).attempt1().done()
        sim.ev(Tick(T0 + 120 * MIN))
        assertEquals(Phase.ATTEMPT, sim.ses.phase)               // 재생 그대로
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S11, ask.sid); assertEquals("2시간 0분", ask.vars["drove"])
        assertTrue(sim.all<SendToPhone>().isEmpty())
        assertFalse(sim.ses.longDriveOffered)                    // 말하는 중: 아직 아니다(C84)
        sim.done()
        assertTrue(sim.ses.longDriveOffered)
        sim.tick(30 * MIN)
        assertEquals(1, sim.all<Ask>().count { it.sid == Sid.S11 })
    }

    @Test fun l2_park15min_resetsDrive() {
        val sim = session().tick(100 * MIN).gear(Gear.P).tick(14 * MIN)
        assertNotNull(sim.ses.driveFrom)
        sim.tick(MIN)
        assertNull(sim.ses.driveFrom); assertFalse(sim.ses.longDriveOffered)
        sim.gear(Gear.D, MIN)
        assertEquals(sim.t, sim.ses.driveFrom)
    }

    @Test fun a1_parkAfter5min_saysS12() {
        val sim = session().gear(Gear.P, 5 * MIN)
        assertTrue(Sid.S12 in sim.sids())
        sim.flush().clear().gear(Gear.D, 20 * MIN).gear(Gear.P, 2 * MIN)   // 마지막 P 뒤 2분만 달림
        assertTrue(Sid.S12 !in sim.sids())
    }

    @Test fun f1_feedingLatestWins() {
        val sim = idle(feedingStart = T0 - 60 * MIN).ev(FeedingStarted(T0, T0 - 10 * MIN, FeedingFrom.CAR_SCREEN, null))
        assertEquals(T0 - 10 * MIN, sim.s.feedingStart)
        assertEquals(SaveFeedingStart(T0 - 10 * MIN), sim.step<SaveFeedingStart>().single())
        assertEquals("feeding_state", sim.step<SendToPhone>().single().type)
        sim.ev(FeedingStarted(T0, T0 - 5 * MIN, FeedingFrom.PHONE, "m1"))
        assertEquals(T0 - 5 * MIN, sim.s.feedingStart)
        assertTrue(sim.cmds.isEmpty())                       // 폰 입력은 link가 저장·전송했다
    }

    @Test fun f2_olderFeedingIgnored() {
        val sim = idle(feedingStart = T0).ev(FeedingStarted(T0, T0 - MIN, FeedingFrom.CAR_SCREEN, null))
        assertEquals(T0, sim.s.feedingStart); assertTrue(sim.cmds.isEmpty())
        sim.ev(FeedingStarted(T0, T0, FeedingFrom.PHONE, null))
        assertEquals(T0, sim.s.feedingStart)
    }

    @Test fun f3_feedingWindowBounds() {
        val s = 0L
        val iv = 100 * MIN
        assertFalse(feedingWindow(s, 100, (iv * 0.8).toLong() - 1))
        assertTrue(feedingWindow(s, 100, (iv * 0.8).toLong()))
        assertTrue(feedingWindow(s, 100, iv * 2 - 1))
        assertFalse(feedingWindow(s, 100, iv * 2))
        assertFalse(feedingWindow(iv * 2 - 10 * MIN, 100, iv * 2 - 1))   // 새 입력 뒤에는 새 시작부터
    }

    @Test fun f4_noInterval_feedingOff() {
        for (now in listOf(0L, 60 * MIN, 600 * MIN)) assertFalse(feedingWindow(0, null, now))
        assertFalse(feedingWindow(null, 120, 100 * MIN))
    }

    // ---- F5·F5a~F5h 출발 수유실 제안(C117): 수유 시작 2시간 전·간격 3시간 → 배고플 시각 now + 60분, 남은 150분·150km ----
    private fun departIdle(feedingStart: Long? = T0 - 120 * MIN, interval: Int? = 180) =
        idle(prof = profile(interval = interval), feedingStart = feedingStart)
    private fun Sim.depRoute(vararg r: RestCandidate, km: Double? = 150.0, sec: Int? = 9000) = route(*r, dest = true, remainingSec = sec, remainingKm = km)
    private fun Sim.assertOpenDriving() { assertNotNull(s.session); assertEquals(Gear.D, s.car.gear) }
    private fun Sim.noDepart() { assertTrue(all<Ask>().isEmpty()); assertTrue(Sid.S07_DEPART !in sids()); assertNull(ses.pendingAsk) }

    @Test fun f5_departure_offersNursingRest() {
        // 경로가 먼저 있고 GearChanged(D)가 세션을 여는 같은 Step에서 제안한다. eta 안성 30분·천안 80분 → h − 20분 = 40분 이하 중 가장 늦은 곳
        val far = RestCandidate("f", "f 휴게소", RestKind.REST_AREA, 80.0, true)
        val sim = departIdle().depRoute(rest("a", 5.0), rest("n", 30.0, nursing = true), far).doorCycle()
        assertNull(sim.s.session)
        sim.gear(Gear.D)
        sim.assertOpenDriving(); assertTrue(sim.ses.departChecked)
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S07_DEPART, ask.sid)
        assertEquals((T0 + 60 * MIN).toString(), ask.vars["hungryAt"]); assertEquals("30", ask.vars["distanceKm"]); assertEquals("n 휴게소", ask.vars["restName"])
        assertTrue(sim.ses.pendingAsk!!.hasNursingRoom); assertEquals("n", sim.ses.pendingAsk!!.restId)
        assertEquals(sim.t, sim.ses.lastOffer[Sid.S07_DEPART])
        assertEquals(Sid.S07_DEPART, sim.ses.restLog.single().sid)
    }

    @Test fun f5_departure_routeArrivesAfterSessionOpens() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN)
        sim.assertOpenDriving(); assertFalse(sim.ses.departChecked)
        sim.depRoute(nurse)
        assertEquals(Sid.S07_DEPART, sim.step<Ask>().single().sid)
    }

    @Test fun f5_departure_notWhileParked_offersAfterDrive() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN).gear(Gear.P)
        assertNotNull(sim.s.session); assertEquals(Gear.P, sim.s.car.gear)
        sim.depRoute(nurse)
        assertFalse(sim.ses.departChecked); sim.noDepart()
        sim.gear(Gear.D, 1_000)
        assertTrue(sim.ses.departChecked); assertEquals(Sid.S07_DEPART, sim.step<Ask>().single().sid)
    }

    @Test fun f5j_reverseGear_waitsForDrive() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN).gear(Gear.P)
        sim.depRoute(nurse).gear(Gear.R, 1_000)
        assertFalse(sim.ses.departChecked); sim.noDepart()                          // 후진에서는 내지 않고 다음에 다시 본다
        sim.gear(Gear.D, 1_000)
        assertTrue(sim.ses.departChecked); assertEquals(Sid.S07_DEPART, sim.step<Ask>().single().sid)
    }

    // S3a: 출발 제안(S07_DEPART)에 아니로 답한 뒤 10분은 같은 수유실 제안(S07)도 내지 않는다
    @Test fun s3a_departAnswerNo_blocksS07In10min() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).depRoute(nurse)
        val ask = sim.step<Ask>().single()
        sim.done().ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO))
        sim.attempt1().toConfirm().closeQuiet().flush().attempt1().flush()   // s3_offerNotRepeatedIn10min과 같은 흐름
        assertTrue(Sid.S07 !in sim.sids(), sim.sids().toString())
    }

    @Test fun f5j_heldDepartOffer_droppedIfNotDrive() {
        for (g in listOf(Gear.R, Gear.N)) {
            val sim = session(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN)
            sim.ev(VehicleSample(sim.t + 100, 60.0, true, 0.0, TurnSignal.NONE))      // 바쁨
            sim.depRoute(nurse)
            assertEquals(listOf(Sid.S07_DEPART), sim.s.speech.queue.map { it.sid }, "$g")
            sim.gear(g, 200)
            sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
            assertTrue(sim.all<Ask>().isEmpty(), "$g"); assertTrue(sim.s.speech.queue.none { it.sid == Sid.S07_DEPART }, "$g")
            assertTrue(sim.ses.departChecked, "$g")
        }
    }

    // S6a: 브레이크로 미뤄진(말하지 않은) 출발 질문이 통화로 버려지면 10분 금지 시각을 남기지 않는다
    @Test fun s6a_callCancelledOffer_doesNotBlockRepeat() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN)
        sim.ev(VehicleSample(sim.t + 100, 60.0, true, 0.0, TurnSignal.NONE))      // 바쁨
        sim.depRoute(nurse)
        assertEquals(listOf(Sid.S07_DEPART), sim.s.speech.queue.map { it.sid })
        assertNotNull(sim.ses.lastOffer[Sid.S07_DEPART])
        sim.ev(CallMode(sim.t + 100, true)).ev(CallMode(sim.t + 100, false))
        assertTrue(sim.s.speech.queue.isEmpty()); assertNull(sim.ses.lastOffer[Sid.S07_DEPART])
        sim.attempt1().toConfirm().closeQuiet().flush().attempt1().flush()
        assertTrue(Sid.S07 in sim.sids(), sim.sids().toString())
    }

    // S6a 반대: 이미 말하던 맨 앞 문장의 금지 시각은 남는다
    @Test fun s6a_callWhileSpeaking_keepsOfferTime() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).depRoute(nurse)
        sim.step<Ask>().single()
        assertNotNull(sim.s.speech.speaking)
        sim.ev(CallMode(sim.t + 100, true))
        assertNotNull(sim.ses.lastOffer[Sid.S07_DEPART])
    }

    @Test fun f5j_droppedDepartOffer_doesNotBlockS07() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN)
        sim.ev(VehicleSample(sim.t + 100, 60.0, true, 0.0, TurnSignal.NONE))      // 바쁨
        sim.depRoute(nurse)
        assertEquals(listOf(Sid.S07_DEPART), sim.s.speech.queue.map { it.sid })
        sim.gear(Gear.R, 200)
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))                   // 꺼낼 때 무효라 버려진다
        assertTrue(sim.s.speech.queue.isEmpty()); assertNull(sim.ses.lastOffer[Sid.S07_DEPART])
        sim.gear(Gear.D, 1_000)
        sim.attempt1().toConfirm().closeQuiet().flush().attempt1().flush()
        assertTrue(Sid.S07 in sim.sids(), sim.sids().toString())                   // 묻지도 않은 출발 제안이 S07을 막지 않는다
    }

    @Test fun f5a_noFeeding_noOfferAndChecked() {
        val sim = departIdle(feedingStart = null).depRoute(nurse).doorCycle().gear(Gear.D)
        sim.assertOpenDriving(); assertNotNull(sim.s.car.route!!.remainingKm); assertNull(sim.s.feedingStart)
        sim.noDepart(); assertTrue(sim.ses.departChecked)
        sim.ev(FeedingStarted(sim.t, T0 - 120 * MIN, FeedingFrom.CAR_SCREEN, null)).depRoute(nurse)   // 그 뒤 수유 입력이 와도 다시 보지 않는다
        assertNotNull(sim.s.feedingStart); sim.noDepart()
    }

    @Test fun f5b_noInterval_noOffer() {
        val sim = departIdle(interval = null).depRoute(nurse).doorCycle().gear(Gear.D)
        sim.assertOpenDriving(); assertNotNull(sim.s.feedingStart); assertNull(sim.s.profile!!.feedingIntervalMin)
        sim.noDepart(); assertTrue(sim.ses.departChecked)
    }

    @Test fun f5c_noRemainingKm_waitsForRoute() {
        val sim = departIdle().doorCycle().gear(Gear.D)
        sim.assertOpenDriving(); assertNull(sim.s.car.route)
        assertFalse(sim.ses.departChecked)
        sim.depRoute(nurse, km = null)                                  // 남은 거리 없음
        assertNull(sim.s.car.route!!.remainingKm); assertFalse(sim.ses.departChecked); sim.noDepart()
        sim.depRoute(nurse, sec = null)                                 // 남은 시간 없음
        assertNull(sim.s.car.route!!.remainingSec); assertFalse(sim.ses.departChecked); sim.noDepart()
        sim.route(nurse, dest = false, remainingSec = 9000, remainingKm = 150.0)   // 안내 중 아님
        assertFalse(sim.s.car.route!!.hasDestination); assertFalse(sim.ses.departChecked); sim.noDepart()
        sim.depRoute(nurse)                                             // 다음 RouteState에서 다시 본다
        assertTrue(sim.ses.departChecked); assertEquals(Sid.S07_DEPART, sim.step<Ask>().single().sid)
    }

    @Test fun f5c_drivingInfoBeforeTbt_offersWhenRestsArrive() {
        val sim = departIdle().doorCycle().gear(Gear.D)
        sim.assertOpenDriving()
        sim.depRoute()                                                  // DrivingInfo만 먼저: 시간·거리 있고 rests 없음
        assertTrue(sim.s.car.route!!.rests.isEmpty()); assertNotNull(sim.s.car.route!!.remainingKm)
        assertFalse(sim.ses.departChecked); sim.noDepart()
        sim.depRoute(nurse)                                             // TBT가 와서 rests가 참
        assertTrue(sim.ses.departChecked); assertEquals(Sid.S07_DEPART, sim.step<Ask>().single().sid)
    }

    @Test fun f5i_otherOfferSameStep_waits() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN)
        sim.route(nurse, dest = true, remainingSec = 9000, remainingKm = null)       // km 미수신: F5c로 기다림
        sim.s = sim.s.copy(session = sim.ses.copy(playedMs = P.sessionPlayCapMs - 1_000))   // 남은 재생 예산 1초
        sim.attempt1()
        assertFalse(sim.ses.departChecked); assertNotNull(sim.s.car.route); assertNull(sim.s.car.route!!.remainingKm)
        val endAt = sim.play().endAt
        sim.ev(RouteState(endAt, true, 9000, listOf(nurse), 150.0))                  // 재생이 끝나는 시각에 km가 채워짐
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertTrue(Sid.S07 in sim.s.speech.queue.map { it.sid })                     // 상한 인계 제안이 먼저 섰다
        assertTrue(Sid.S07_DEPART !in sim.s.speech.queue.map { it.sid }); assertFalse(sim.ses.departChecked)
        sim.ev(RouteState(endAt + 1_000, true, 9000, listOf(nurse), 150.0))          // 인계 S07이 큐에서 기다리는 중(앞 문장 뒤): 미룬다
        assertTrue(Sid.S07 in sim.s.speech.queue.map { it.sid }); assertFalse(sim.ses.departChecked)
        sim.done(); sim.ev(Tick(sim.t + P.speechGapMs))                              // 앞 문장이 끝나고 S07이 질문으로 나간다
        val askId = sim.ses.pendingAsk!!.askId; assertEquals(Sid.S07, sim.ses.pendingAsk!!.sid)
        sim.ev(RouteState(sim.t + 1_000, true, 9000, listOf(nurse), 150.0))          // 말하는 중: 미룬다
        assertEquals(Sid.S07, sim.s.speech.queue.first().sid); assertFalse(sim.ses.departChecked)
        sim.done()                                                                   // 문장은 끝났지만 질문(pendingAsk)이 남아 있다
        assertTrue(sim.s.speech.queue.isEmpty()); assertNotNull(sim.ses.pendingAsk)
        sim.ev(RouteState(sim.t + 1_000, true, 9000, listOf(nurse), 150.0))
        assertFalse(sim.ses.departChecked); assertTrue(sim.s.speech.queue.none { it.sid == Sid.S07_DEPART })
        sim.ev(Answer(sim.t + 1_000, askId, YesNo.NO)); assertNull(sim.ses.pendingAsk)
        assertFalse(sim.ses.departChecked)
        val offeredAt = sim.ses.lastOffer[Sid.S07]!!
        sim.ev(RouteState(sim.t + 1_000, true, 9000, listOf(nurse), 150.0))          // NO 직후: 10분 금지 안이라 다시 묻지 않는다
        assertTrue(sim.t - offeredAt < P.offerRepeatMs); assertNull(sim.ses.pendingAsk)
        assertFalse(sim.ses.departChecked); assertTrue(sim.s.speech.queue.none { it.sid == Sid.S07_DEPART })
        val at = offeredAt + P.offerRepeatMs
        sim.assertOpenDriving(); assertTrue(sim.s.speech.queue.isEmpty()); assertNull(sim.ses.pendingAsk)   // 시간만 흐른다(5분 타이머는 에피소드를 닫을 뿐 F5 조건과 무관)
        sim.ev(RouteState(at - 1, true, 9000, listOf(nurse), 150.0))                 // 10분에서 1ms 모자람
        assertFalse(sim.ses.departChecked)
        sim.ev(RouteState(at, true, 9000, listOf(nurse), 150.0))                     // 10분이 지난 뒤 비로소 본다
        assertTrue(sim.ses.departChecked); assertTrue(sim.s.speech.queue.any { it.sid == Sid.S07_DEPART })
    }

    @Test fun f5d_hungryOutsideDrive_noOffer() {
        val early = departIdle(feedingStart = T0 - 170 * MIN).depRoute(nurse).doorCycle().gear(Gear.D)   // h = now + 10분 < now + 20분
        early.assertOpenDriving(); assertTrue(early.s.feedingStart!! + 180 * MIN < early.t + 20 * MIN)
        early.noDepart(); assertTrue(early.ses.departChecked)
        val late = departIdle(feedingStart = T0 - 10 * MIN).depRoute(nurse).doorCycle().gear(Gear.D)    // h = now + 170분 > now + 150분
        late.assertOpenDriving(); assertTrue(late.s.feedingStart!! + 180 * MIN > late.t + 9000 * 1000L)
        late.noDepart(); assertTrue(late.ses.departChecked)
    }

    @Test fun f5e_noNursingRestInTime_noOffer() {
        val slow = RestCandidate("s", "s 휴게소", RestKind.REST_AREA, 80.0, true)       // eta 80분 > 40분
        val sim = departIdle().depRoute(rest("a", 5.0), slow).doorCycle().gear(Gear.D)    // 수유실 없는 곳 a는 가까워도 안 된다
        sim.assertOpenDriving(); assertTrue(sim.s.car.route!!.rests.any { it.hasNursingRoom })
        sim.noDepart(); assertTrue(sim.ses.departChecked); assertTrue(Sid.S10 !in sim.sids())
    }

    @Test fun f5f_oncePerSession() {
        val sim = departIdle().depRoute(nurse).doorCycle().gear(Gear.D)
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S07_DEPART, ask.sid); assertTrue(sim.ses.departChecked)
        sim.done().ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO))                 // 질문을 NO로 닫는다
        assertNull(sim.ses.pendingAsk); assertTrue(sim.s.speech.queue.isEmpty())
        sim.clear()
        // 이 시각에도 F5 조건(수유·간격·경로·쉼터·시간대)이 모두 맞고, 다른 제안(S07·S08·S10·S11)의 10분 금지도 걸려 있지 않다
        val now = sim.t + 2_000
        assertTrue(OTHER_OFFERS_FOR_TEST.none { sim.ses.lastOffer[it] != null })
        assertNotNull(pickDepartRest(RouteView(now, true, 9000, listOf(nurse), 150.0), sim.s.feedingStart, 180, now, P.departLeadMin))
        sim.assertOpenDriving(); assertTrue(sim.ses.departChecked)
        sim.ev(RouteState(now, true, 9000, listOf(nurse), 150.0))                 // 같은 세션의 다음 RouteState
        assertTrue(sim.all<Ask>().none { it.sid == Sid.S07_DEPART }); assertTrue(sim.s.speech.queue.isEmpty())
        assertEquals(1, sim.ses.restLog.count { it.sid == Sid.S07_DEPART })
    }
    private val OTHER_OFFERS_FOR_TEST = listOf(Sid.S07, Sid.S08, Sid.S10, Sid.S11)

    @Test fun f5g_departAskHeldWhileBusy() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN)
        sim.ev(VehicleSample(sim.t + 100, 60.0, true, 0.0, TurnSignal.NONE))           // 바쁨(브레이크)
        sim.assertOpenDriving(); assertNotNull(sim.s.steer.driveBusyAt)
        sim.depRoute(nurse)
        assertEquals(listOf(Sid.S07_DEPART), sim.s.speech.queue.map { it.sid })        // 큐에 서되
        assertTrue(sim.all<Ask>().isEmpty())                                           // 바쁜 동안은 내지 않는다
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        assertEquals(Sid.S07_DEPART, sim.step<Ask>().single().sid)
    }

    @Test fun f5h_departYes_addsWaypoint() {
        val sim = departIdle().depRoute(nurse).doorCycle().gear(Gear.D)
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S07_DEPART, ask.sid); assertEquals(ask.askId, sim.ses.pendingAsk!!.askId)
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.YES))
        assertEquals("n", sim.step<AddWaypoint>().single().restId)
        assertNotNull(sim.ses.nursingStop)
    }

    @Test fun pickDepartRest_bounds() {
        fun pick(rests: List<RestCandidate>, h: Long, now: Long = 0, sec: Int = 7200, km: Double? = 120.0, iv: Int? = 180) =
            pickDepartRest(RouteView(now, true, sec, rests, km), h - (iv ?: 0) * MIN, iv, now, 20)
        fun at(id: String, d: Double) = RestCandidate(id, id, RestKind.REST_AREA, d, true)   // eta = 7200초 × d ÷ 120 = d × 1분
        assertEquals("a", pick(listOf(at("a", 0.0)), h = 20 * MIN)?.id)          // h = now + lead 정확히, eta = now = h − lead
        assertNull(pick(listOf(at("a", 0.0)), h = 20 * MIN - 1))                  // 1ms 이르다
        assertEquals("a", pick(listOf(at("a", 0.0)), h = 7200_000L)?.id)          // h = now + 남은 시간 정확히
        assertNull(pick(listOf(at("a", 0.0)), h = 7200_000L + 1))
        assertEquals("a", pick(listOf(at("a", 40.0)), h = 60 * MIN)?.id)          // eta = h − lead 정확히
        assertNull(pick(listOf(at("a", 40.1)), h = 60 * MIN))
        assertEquals("b", pick(listOf(at("a", 10.0), at("b", 30.0)), h = 60 * MIN)?.id)   // 가장 늦은 eta
        assertNull(pick(listOf(at("a", 10.0)), h = 60 * MIN, km = 0.0))
        assertNull(pick(listOf(at("a", 10.0).copy(hasNursingRoom = false)), h = 60 * MIN))
    }

    @Test fun f6_confirmFeeding_updatesStart() {
        val sim = added().gear(Gear.P, MIN)
        val pAt = sim.t
        sim.tick(15 * MIN).ev(FeedingConfirmed(sim.t, true))
        assertEquals(pAt, sim.s.feedingStart)
        assertNull(sim.ses.nursingStop)
        assertEquals(SaveFeedingStart(pAt), sim.step<SaveFeedingStart>().single())
        assertEquals("feeding_state", sim.step<SendToPhone>().single().type)
    }

    @Test fun f7_firstParkAfterNursingWaypoint_tracked() {
        val sim = added()
        assertNotNull(sim.ses.nursingStop!!.addedAt)
        sim.gear(Gear.P, MIN)
        assertEquals(sim.t, sim.ses.nursingStop!!.firstPAt)
        sim.ev(GearChanged(sim.t + MIN, Gear.P))
        assertEquals(sim.t - MIN, sim.ses.nursingStop!!.firstPAt)
    }

    @Test fun f8_firstPark15min_showsFeedingConfirm() {
        val sim = added().gear(Gear.P, MIN)
        val pAt = sim.t
        assertEquals(pAt + 15 * MIN, sim.last.wakeAt)
        sim.tick(15 * MIN - 1)
        assertTrue(sim.step<ShowNotice>().isEmpty())
        sim.tick(1)
        assertEquals(Notice.FEEDING_CONFIRM, sim.step<ShowNotice>().single().kind)
        assertTrue(sim.ses.nursingStop!!.confirmShown)
    }

    @Test fun f9_driveBeforeConfirm_clearsNursingStop() {
        val sim = added().gear(Gear.P, MIN).tick(5 * MIN).gear(Gear.D)
        assertNull(sim.ses.nursingStop)
        assertTrue(sim.all<ShowNotice>().none { it.kind == Notice.FEEDING_CONFIRM })
    }

    @Test fun f10_confirmNoOrStale_ignored() {
        val sim = added().gear(Gear.P, MIN).ev(FeedingConfirmed(T0, true))   // 버튼을 띄우기 전
        assertNotNull(sim.ses.nursingStop); assertTrue(sim.cmds.isEmpty())
        sim.tick(15 * MIN).ev(FeedingConfirmed(sim.t, false))
        assertNull(sim.ses.nursingStop); assertNull(sim.s.feedingStart); assertTrue(sim.cmds.isEmpty())
        val none = session().ev(FeedingConfirmed(T0, true))
        assertTrue(none.cmds.isEmpty())
    }

    @Test fun s1_speechGap8s() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 5.0)).attempt1()
        sim.done()
        val doneAt = sim.t
        assertEquals(doneAt + 8_000, sim.last.wakeAt)
        sim.ev(Tick(doneAt + 7_999))
        assertTrue(sim.step<Speak>().isEmpty())
        sim.ev(Tick(doneAt + 8_000))
        assertEquals(Sid.S06, sim.step<Speak>().single().sid)
    }

    // ---- C107: 운전이 바쁜 동안 급하지 않은 문장 미루기 ----

    private fun Sim.busy(dt: Long = 0, brake: Boolean = true, deg: Double = 0.0, sig: TurnSignal = TurnSignal.NONE) =
        ev(VehicleSample(t + dt, 60.0, brake, deg, sig))
    private val Sim.busyAt get() = s.steer.driveBusyAt
    /** S02가 끝나 S06이 8초 뒤를 기다리는 상태(큐 = [S06]). */
    private fun waitingS06(withRest: Boolean = false): Sim {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN)
        if (withRest) sim.route(rest("a", 5.0))       // 쉼터가 있으면 S06 뒤에 S08, 없으면 S10이 함께 큐에 선다
        sim.attempt1().done()
        assertEquals(if (withRest) listOf(Sid.S06, Sid.S08) else listOf(Sid.S06, Sid.S10), sim.s.speech.queue.map { it.sid })
        return sim
    }

    @Test fun s4_busySampleMarksDriveBusy() {
        val sim = session()
        sim.busy(brake = false)                               // 바쁘지 않음
        assertNull(sim.busyAt)
        sim.busy(100, brake = true); assertEquals(sim.t, sim.busyAt)
        sim.busy(100, brake = false, sig = TurnSignal.LEFT); assertEquals(sim.t, sim.busyAt)
        sim.busy(100, brake = false, deg = -30.0); assertEquals(sim.t, sim.busyAt)   // |조향| ≥ 30° 경계
        val at = sim.busyAt
        sim.busy(100, brake = false, deg = 29.9); assertEquals(at, sim.busyAt)        // 바쁘지 않으면 그대로
        assertTrue(sim.s.steer.filtered != null && sim.s.steer.window != null)        // 기존 조향 집계(V1·V2)는 그대로
        // 필드가 없던 옛 스냅숏은 driveBusyAt = null로 읽힌다
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val old = json.encodeToString(sim.s).replace(Regex("\"driveBusyAt\":\\d+,?"), "")
        assertFalse("driveBusyAt" in old)
        assertNull(json.decodeFromString<EngineState>(old).steer.driveBusyAt)
        val oldLine = json.encodeToString(waitingS06().s).replace(Regex(",?\"queuedAt\":\\d+"), "")
        assertFalse("queuedAt" in oldLine)
        assertNull(json.decodeFromString<EngineState>(oldLine).speech.queue.first().queuedAt)
    }

    @Test fun s5_holdableWaitsUntilCalm_urgentGoesFirst() {
        val sim = waitingS06(withRest = true)
        val doneAt = sim.t
        assertNotNull(sim.s.speech.queue.first().queuedAt)              // 큐에 넣을 때 채운다
        sim.busy(7_000)                                                  // 8초 간격이 끝나기 직전 브레이크
        sim.ev(Tick(doneAt + 8_000))
        assertTrue(sim.step<Speak>().none { it.sid == Sid.S06 })         // 바쁨 중 S06은 미뤄지고
        assertEquals(Sid.S08, sim.step<Ask>().single().sid)              // 큐 뒤의 S08(인계 제안)이 먼저 나간다
        assertEquals(listOf(Sid.S08, Sid.S06), sim.s.speech.queue.map { it.sid })   // S06은 자리를 지킨다
        sim.done()
        assertEquals(listOf(Sid.S06), sim.s.speech.queue.map { it.sid })
        sim.busy(500)                                                    // 아직 바쁘다
        val busyAt = sim.busyAt!!
        assertNotNull(sim.last.wakeAt)                                   // 잠잠해지는 때를 깨움 시각으로 낸다
        sim.clear()
        sim.ev(Tick(busyAt + 2_999)); assertTrue(sim.log.none { it is Speak })   // 3초 직전까지는 아직
        sim.ev(Tick(maxOf(busyAt + 3_000, sim.s.speech.lastDoneAt!! + P.speechGapMs)))   // 잠잠해지고 8초 간격도 지남: Tick만으로
        assertEquals(Sid.S06, sim.step<Speak>().single().sid)
    }

    /** 120분 시점에 바쁨 때문에 미뤄진 S11(쉼터 a, 12km)이 큐에 있는 상태. 바쁨은 `at − 0.5초`가 마지막이다. */
    private fun heldS11(): Pair<Sim, Long> {
        val l = session().route(rest("a", 12.0)).ev(Tick(T0 + 119 * MIN + 30_000)).attempt1().done()
        val at = T0 + 120 * MIN
        l.ev(VehicleSample(at - 500, 60.0, true, 0.0, TurnSignal.NONE)).ev(Tick(at))
        assertEquals(Sid.S11, l.s.speech.queue.single().sid)
        assertTrue(l.step<Ask>().isEmpty())
        return l to at
    }

    @Test fun s5_skippedThenInvalidLine_droppedNotSpoken() {
        val sim = waitingS06(withRest = true)
        val doneAt = sim.t
        // 미뤄진 S06 뒤에 이미 끝난 재생의 알림(C15)이 있다
        sim.s = sim.s.copy(speech = sim.s.speech.copy(queue = listOf(sim.s.speech.queue.first(), Line(Sid.S02, emptyMap(), null, playId = "gone", queuedAt = doneAt))))
        sim.busy(7_000).ev(Tick(doneAt + 8_000))
        assertTrue(sim.step<Speak>().isEmpty())                          // 무효인 S02는 나가지 않는다
        assertEquals(listOf(Sid.S06), sim.s.speech.queue.map { it.sid })  // 지워지고 S06은 자리를 지킨다
    }

    @Test fun s5_routeChangedWhileHeld_offerDroppedWhenRestGone() {
        val (l, at) = heldS11()
        l.ev(RouteState(at + 1_000, false, null, listOf(rest("b", 8.0))))   // a가 경로에서 사라졌다
        l.ev(Tick(at + 4_000))                                              // 잠잠해짐
        assertTrue(l.all<Ask>().none { it.vars["restName"] == "a 휴게소" })
        assertTrue(l.s.speech.queue.none { it.askRestId == "a" })
        assertFalse(l.ses.longDriveOffered)
    }

    @Test fun s5_routeChangedWhileHeld_offerRefreshedWhenRestStays() {
        val (l, at) = heldS11()
        l.ev(RouteState(at + 1_000, false, null, listOf(rest("a", 9.0))))   // 같은 쉼터, 거리가 줄었다
        l.ev(Tick(at + 4_000))
        val ask = l.step<Ask>().single()
        assertEquals(Sid.S11, ask.sid); assertEquals("9", ask.vars["distanceKm"]); assertEquals("a 휴게소", ask.vars["restName"])
    }

    @Test fun s5_routeChanged_handoffOfferS08_stillSpoken() {
        val sim = waitingS06(withRest = true)                                // 큐 [S06, S08(a)]
        val doneAt = sim.t
        sim.ev(RouteState(doneAt + 1_000, false, null, listOf(rest("b", 8.0))))
        sim.busy(6_000).ev(Tick(doneAt + 8_000))                              // S06은 미뤄지고 S08이 나간다
        assertEquals("a 휴게소", sim.step<Ask>().single { it.sid == Sid.S08 }.vars["restName"])   // 예전처럼 지우지 않고 그대로 묻는다
    }

    @Test fun s5_droppedOffer_notBlockedFor10min() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(nurse).attempt1()   // 큐 [S06, S07(n)]
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S07 })
        sim.done()
        assertTrue(Sid.S07 in sim.ses.lastOffer)
        sim.ev(RouteState(sim.t, false, null, listOf(rest("b", 8.0, nursing = true))))   // n이 경로에서 사라짐
        sim.ev(Tick(sim.s.speech.lastDoneAt!! + 8_000)).done()                            // S06을 말한다
        sim.ev(Tick(sim.s.speech.lastDoneAt!! + 8_000))                                   // S07(n) 차례: 쉼터가 없어 말하지 않고 버린다
        assertTrue(sim.s.speech.queue.isEmpty() && sim.log.none { it is Ask })
        assertFalse(Sid.S07 in sim.ses.lastOffer)                                         // 말하지 않았으니 10분 금지를 되돌린다
        assertNotNull(offerRest(false, sim.s.car.gear, sim.s.car.route, sim.ses.lastOffer, sim.t, sim.ses.driveFrom))   // 다음 제안 조건이 오면 b를 바로 제안할 수 있다
    }

    @Test fun s6_heldTooLongDropped() {
        val sim = waitingS06()
        val queuedAt = sim.s.speech.queue.first().queuedAt!!
        var t = sim.t
        while (t < queuedAt + P.holdMaxMs) { t += 100; sim.busy(t - sim.t) }   // 계속 바쁘다(10Hz)
        assertEquals(listOf(Sid.S06, Sid.S10), sim.s.speech.queue.map { it.sid })  // 30초까지는 남는다
        sim.busy(1_000)                                                         // 30초를 넘김
        assertTrue(sim.s.speech.queue.isEmpty())
        assertFalse(Sid.S10 in sim.ses.lastOffer)                               // 말하지 않고 버린 제안은 10분 금지를 남기지 않는다
        assertTrue(sim.log.none { it is Speak && it.sid == Sid.S06 } && sim.log.none { it is Ask })
        // 장시간 제안은 버려도 longDriveOffered가 서지 않는다(C84)
        val l = session().route(rest("a", 12.0)).ev(Tick(T0 + 119 * MIN + 30_000)).attempt1().done()
        val at = T0 + 120 * MIN
        l.ev(VehicleSample(at - 500, 60.0, true, 0.0, TurnSignal.NONE)).ev(Tick(at))
        assertEquals(Sid.S11, l.s.speech.queue.single().sid)
        assertTrue(l.step<Ask>().isEmpty())                                      // 바쁜 동안 안 나간다
        for (k in 1..31) l.ev(VehicleSample(at + k * 1_000L, 60.0, true, 0.0, TurnSignal.NONE))
        assertTrue(l.s.speech.queue.none { it.queuedAt == at })                  // 30초를 넘겨 버려졌다(다시 제안은 새 줄)
        assertTrue(l.all<Ask>().isEmpty())
        assertFalse(l.ses.longDriveOffered)
        // 바쁨 없이 순서만 30초 넘게 기다린 문장은 버리지 않는다
        val calm = waitingS06()
        calm.ev(Tick(calm.s.speech.queue.first().queuedAt!! + P.holdMaxMs + 1_000))
        assertEquals(Sid.S06, calm.step<Speak>().single().sid)
    }

    @Test fun s7_noSamplesNoHold() {
        val sim = waitingS06()
        val doneAt = sim.t
        sim.busy(7_000)                                                          // 마지막 바쁨, 그 뒤 표본이 끊김
        sim.ev(Tick(doneAt + 8_000)); assertTrue(sim.step<Speak>().isEmpty())    // 방금 바빴으니 아직 미룬다
        sim.ev(Tick(doneAt + 10_000))                                            // holdStableMs(3초)가 지남, 표본은 계속 없다
        assertEquals(Sid.S06, sim.step<Speak>().single().sid)                    // 오래된 driveBusyAt는 잠잠으로 본다
        val none = waitingS06()                                                  // 표본이 한 번도 없으면 지금과 같다
        none.ev(Tick(none.t + 8_000)); assertEquals(Sid.S06, none.step<Speak>().single().sid)
    }

    @Test fun s2_attemptAndS18_alwaysSpoken() {
        val sim = session().attempt1().toConfirm().closeQuiet().flush().attempt1().flush()
        assertEquals(2, sim.all<Speak>().count { it.sid == Sid.S02 })
        val h = session().handoff().flush()
        h.ev(SwitchSound(h.t, LL)).flush().ev(SwitchSound(h.t + MIN, LL)).flush()
        assertEquals(2, h.all<Speak>().count { it.sid == Sid.S18 })
    }

    @Test fun s3_offerNotRepeatedIn10min() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 5.0))
        sim.attempt1().toConfirm().closeQuiet().flush().attempt1().flush()
        assertEquals(2, sim.all<Speak>().count { it.sid == Sid.S02 })
        assertEquals(1, sim.sids().count { it == Sid.S08 })
    }

    @Test fun q1_afterSpeech_listens5s() {
        val sim = session().attempt1()
        sim.ev(SpeechDone(sim.t + 4_000, sim.s.speech.speaking!!, true))
        val l = sim.step<Listen>().single()
        assertEquals(5_000, l.ms)
        assertEquals(QaWindow(l.listenId, QaStep.HEARING, sim.t + 6_000, false), sim.qa)
        val ask = session().route(rest("a", 5.0)).handoff().flush()   // Ask 뒤에는 열지 않는다
        assertTrue(ask.all<Listen>().size == 1)                       // S02 뒤 한 번만
    }

    @Test fun q2_parkButton_listens() {
        assertTrue(session().ev(AskButton(T0)).cmds.isEmpty())        // D에서는 안 연다
        val p = idle().ev(AskButton(T0))                               // 세션 밖이어도 된다
        assertEquals(5_000, p.step<Listen>().single().ms)
        assertTrue(idle().ev(CallMode(T0, true)).ev(AskButton(T0)).cmds.isEmpty())
        assertTrue(idle(mic = false).ev(AskButton(T0)).cmds.isEmpty())
    }

    @Test fun q3_silenceOrTimeout_resumesQueue() {
        // 대기 문장(S06·S08)이 있는 채로 S02 뒤 듣기 창이 열린다
        fun opened(): Pair<Sim, Long> {
            val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 5.0)).attempt1()
            sim.ev(SpeechDone(sim.t + 3_000, sim.s.speech.speaking!!, true))
            assertEquals(QaStep.HEARING, sim.qa.step)
            return sim to sim.t
        }
        val (heard, d) = opened()
        heard.ev(Heard(d + 2_000, heard.qa.listenId, null))          // 들은 말 없음
        assertNull(heard.s.speech.qa); assertTrue(heard.cmds.isEmpty())
        assertEquals(d + 8_000, heard.last.wakeAt)                     // 8초 규칙대로 이어 간다
        heard.ev(Tick(d + 7_999)); assertTrue(heard.cmds.isEmpty())
        heard.ev(Tick(d + 8_000))
        assertEquals(listOf(Sid.S06), heard.cmds.map { (it as Speak).sid })
        val (timeout, d2) = opened()
        timeout.ev(Tick(d2 + 6_000))                                   // until = 끝 + 5초 + 1초
        assertNull(timeout.s.speech.qa); assertTrue(timeout.cmds.isEmpty())
        timeout.ev(Tick(d2 + 8_000))
        assertEquals(listOf(Sid.S06), timeout.cmds.map { (it as Speak).sid })
        val (think, d3) = opened()                                     // 답 기다림이 8초를 넘으면 그 끝에 재개
        think.ev(Heard(d3 + 5_000, think.qa.listenId, "왜 울어?"))
        think.ev(Tick(d3 + 8_000)); assertTrue(think.step<Speak>().isEmpty())
        think.ev(Tick(d3 + 9_000))
        assertNull(think.s.speech.qa)
        assertEquals(listOf(Sid.S06), think.cmds.map { (it as Speak).sid })
    }

    @Test fun q4_voiceStop_noLlm() {
        val sim = session().attempt1().done(1_000, spoken = true)
        val s2 = session().attempt1()
        s2.ev(SpeechDone(s2.t, s2.s.speech.speaking!!, true)).ev(Heard(s2.t + 2_000, s2.qa.listenId, "그만 해!"))
        assertEquals(Phase.WAIT, s2.ses.phase)
        assertEquals(Unjudged.DRIVER_STOP, s2.ep.first!!.unjudged)
        assertTrue(s2.all<Qa>().isEmpty()); assertNull(s2.s.speech.qa)
        assertEquals(Phase.ATTEMPT, sim.ses.phase)
    }

    @Test fun q4_stopWordWithYo_stops_butQuestionGoesToQ5() {                 // C109
        for (word in listOf("그만요", "그만해요", "내가 할게요", "그만 틀어요", "그만해요.")) {
            val s = session().attempt1()
            s.ev(SpeechDone(s.t, s.s.speech.speaking!!, true)).ev(Heard(s.t + 2_000, s.qa.listenId, word))
            assertEquals(Phase.WAIT, s.ses.phase, word)
            assertEquals(Unjudged.DRIVER_STOP, s.ep.first!!.unjudged, word)
            assertTrue(s.all<Qa>().isEmpty(), word)
        }
        val q = idle().asking("언제 그만 울까요")
        assertEquals(Qa(q.qa.listenId, "언제 그만 울까요"), q.step<Qa>().single(), "멈춤이 아니라 Q5")
    }

    @Test fun q5_question_callsQa() {
        val sim = idle().asking("언제 그만 울까?")
        assertEquals(Qa(sim.qa.listenId, "언제 그만 울까?"), sim.step<Qa>().single())
        assertEquals(QaStep.THINKING, sim.qa.step); assertEquals(sim.t + 4_000, sim.qa.until)
    }

    @Test fun q5a_toolDuringQa_marksRuleSaid() {
        val h = session().handoff().flush().gear(Gear.P, MIN).asking()
        h.ev(SwitchSound(h.t, LL, h.qa.listenId))
        assertTrue(h.qa.ruleSaid)
        assertEquals(Line(Sid.S18, emptyMap(), null, qaAnswer = true), h.s.speech.queue.first().copy(queuedAt = null))
        val i = idle().asking()
        i.ev(SwitchSound(i.t, LL, i.qa.listenId))
        assertEquals(Sid.S20, i.s.speech.queue.single().sid); assertTrue(i.qa.ruleSaid)
        val w = session().attempt1().ev(DriverStop(T0, Via.VOICE)).gear(Gear.P, MIN).flush().asking()
        w.ev(SwitchSound(w.t, LL, w.qa.listenId))
        w.ev(PlaybackRejected(w.t, w.play().id, RejectReason.FOCUS_DENIED))
        assertTrue(w.s.speech.queue.first().let { it.sid == Sid.S18 && it.qaAnswer }); assertTrue(w.qa.ruleSaid)
    }

    @Test fun q5b_qaWaypoint_adds() {
        val sim = session().route(nurse).attempt1()
        sim.ev(SpeechDone(sim.t, sim.s.speech.speaking!!, true)).asking("수유실 경유지 넣어 줘")
        sim.ev(DriverAddWaypoint(sim.t, "n", sim.qa.listenId))
        val add = sim.step<AddWaypoint>().single()
        assertEquals(WaypointPending(add.cmdId, "n", sim.ses.id, sim.ep.id, fromQa = true, restLogIndex = 0), sim.ses.waypointPending)
        assertTrue(sim.qa.ruleSaid)
        assertEquals(NursingStop("n", null, null, false), sim.ses.nursingStop)
        sim.ev(WaypointResult(sim.t, add.cmdId, "n", true))
        assertEquals(Line(Sid.S09, mapOf("restName" to "n 휴게소"), null, qaAnswer = true), sim.s.speech.queue.first().copy(queuedAt = null))
    }

    @Test fun q5c_qaWaypointRejected_saysS19() {
        val sim = session().route(nurse).gear(Gear.P, MIN).flush().asking()
        sim.ev(DriverAddWaypoint(sim.t, "n", sim.qa.listenId))       // P에서는 넣지 않는다
        assertTrue(sim.step<AddWaypoint>().isEmpty())
        assertTrue(sim.qa.ruleSaid)
        assertEquals(Line(Sid.S19, emptyMap(), null, qaAnswer = true), sim.s.speech.queue.single().copy(queuedAt = null))
    }

    @Test fun q6_reply_spokenFirst() {
        // 대기 문장(S06·S08)이 있는데 ④ 답이 온다 → 답이 먼저, 기존 문장은 답이 끝난 뒤 8초
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 5.0)).attempt1()
        sim.ev(SpeechDone(sim.t + 3_000, sim.s.speech.speaking!!, true))
        sim.ev(Heard(sim.t + 1_000, sim.qa.listenId, "왜 울어?"))
        sim.ev(QaReply(sim.t + 1_000, sim.qa.listenId, "추정이에요."))
        assertNull(sim.s.speech.qa)
        assertEquals(listOf<Command>(Speak(sim.s.speech.speaking!!, Sid.QA, mapOf("text" to "추정이에요."))), sim.cmds) // 8초를 기다리지 않는다
        sim.ev(SpeechDone(sim.t + 2_000, sim.s.speech.speaking!!, true))
        assertTrue(sim.step<Listen>().isEmpty())                    // 답 뒤에는 듣지 않는다
        val qaDone = sim.t
        sim.ev(Tick(qaDone + 7_999)); assertTrue(sim.cmds.isEmpty())
        sim.ev(Tick(qaDone + 8_000))
        assertEquals(listOf(Sid.S06), sim.cmds.map { (it as Speak).sid })
        assertEquals(listOf(Sid.S02, Sid.QA, Sid.S06), sim.all<Speak>().map { it.sid })
        val r = session().handoff().flush().gear(Gear.P, MIN).asking()
        r.ev(SwitchSound(r.t, LL, r.qa.listenId)).ev(QaReply(r.t, r.qa.listenId, "LLM 글"))
        assertTrue(r.s.speech.queue.none { it.sid == Sid.QA })        // ruleSaid면 버린다
    }

    @Test fun q7_staleReplyOrTool_dropped() {
        val sim = session().attempt1()
        sim.ev(QaReply(sim.t, "x-1", "늦은 답")).ev(Heard(sim.t, "x-1", "왜"))
        sim.ev(SwitchSound(sim.t, LL, "x-1"))
        assertTrue(sim.cmds.isEmpty()); assertEquals(Phase.ATTEMPT, sim.ses.phase)
        val h = idle().ev(AskButton(T0))                               // HEARING인데 도구가 옴
        h.ev(DriverStop(T0, Via.TOOL, h.qa.listenId))
        assertTrue(h.cmds.isEmpty()); assertEquals(QaStep.HEARING, h.qa.step)
    }

    @Test fun q8_callOrSuspend_dropsQaAndAsk() {
        val sim = session().route(rest("a", 5.0)).handoff().flush()
        val ask = sim.all<Ask>().single()
        sim.ev(Answer(sim.t, ask.askId, YesNo.NONE)).gear(Gear.P, MIN).flush().asking()
        val lid = sim.qa.listenId
        sim.s = sim.s.copy(session = sim.ses.copy(pendingAsk = PendingAsk(ask.askId, Sid.S08, "a", null, false)))
        sim.ev(CallMode(sim.t, true))
        assertNull(sim.s.speech.qa); assertNull(sim.ses.pendingAsk)
        sim.ev(CallMode(sim.t, false)).ev(QaReply(sim.t, lid, "답")).ev(SwitchSound(sim.t, LL, lid)).ev(Answer(sim.t, ask.askId, YesNo.YES))
        assertTrue(sim.cmds.isEmpty())
        val sus = idle().asking().ev(Suspend(T0 + 1_000))
        assertNull(sus.s.speech.qa)
    }

    @Test fun e1_limitsResetOnlyOnListening() {
        val w = session().attempt2().ev(DriverStop(T0 + 2 * MIN, Via.VOICE))
        assertEquals(2, w.ses.attemptNo); assertNotNull(w.ses.episode)      // WAIT에서는 그대로
        val sim = session().attempt1().toConfirm().closeQuiet()
        assertEquals(0, sim.ses.attemptNo); assertNull(sim.ses.episode)
        assertEquals(56_000, sim.ses.playedMs)                              // 세션 누적은 그대로
        w.tick(5 * MIN)
        assertEquals(0, w.ses.attemptNo); assertTrue(w.ses.playedMs > 0)
    }

    @Test fun e2_cryAfterSuccess_newEpisode() {
        val sim = session().attempt1()
        val first = sim.ep.id
        sim.toConfirm().closeQuiet().attempt1()
        assertEquals(1, sim.ses.attemptNo)
        assertTrue(sim.ep.id != first)
    }

    @Test fun p1_priorityOrder() {
        // 2차 GAP에 든 때는 120분 전, 그 GAP을 깨는 울음 구간이 120분 뒤에 온다
        val sim = session().route(rest("a", 12.0)).ev(Tick(T0 + 118 * MIN + 3_000)).attempt2().toGap()
        assertTrue(sim.t < T0 + 120 * MIN); assertFalse(sim.ses.longDriveOffered)
        sim.recry()                                                          // 같은 Step에 인계와 장시간 시점
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertFalse(sim.ses.longDriveOffered)
        assertEquals(listOf(Sid.S08), sim.s.speech.queue.drop(1).filter { it.askRestId != null }.map { it.sid })
        sim.flush().tick(1_000)
        assertTrue(sim.ses.longDriveOffered)                                 // 다음 Step에 장시간 제안
        // 수유 > 장시간: 120분 경계의 경보 → 시작 결과(S02·S06·S08)가 장시간 제안(S11)보다 앞선다
        val f = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 12.0)).alarm(120 * MIN)
        assertTrue(f.step<Ask>().isEmpty()); assertFalse(f.ses.longDriveOffered)
        f.started().tick(1_000)
        assertEquals(listOf(Sid.S02, Sid.S06, Sid.S08, Sid.S11), f.sids())
    }

    @Test fun v1_steerReversal_definedSignal() {
        // 사인파 ±10°, 주기 10초, 10 Hz 표본, 60초. 극값 12개(2.5초, 7.5초, …, 57.5초) → 반전 기대 12.
        var st = SteerState(null, null, null, null, 0, null)
        var closed: SteerWindowRecord? = null
        for (i in 0..600) {
            val at = i * 100L
            val o = steer(st, VehicleSample(at, 50.0, false, 10 * sin(2 * PI * at / 10_000.0), TurnSignal.NONE), false, false, "s")
            st = o.state; closed = closed ?: o.closed
        }
        val rec = closed!!
        val err = rec.reversals - 12
        println("v1 조향 반전: 기대 12, 계산 ${rec.reversals}, 오차 $err")
        assertTrue(abs(err) <= 1)
        assertEquals(2, rec.speedBand)
        // 계단(0° → 20°)은 반전 0, 방향지시등이 켜진 창은 뺀다
        var s2 = SteerState(null, null, null, null, 0, null)
        var c2: SteerWindowRecord? = null
        for (i in 0..600) {
            val o = steer(s2, VehicleSample(i * 100L, 30.0, false, if (i < 300) 0.0 else 20.0, if (i == 10) TurnSignal.LEFT else TurnSignal.NONE), false, false, "s")
            s2 = o.state; c2 = c2 ?: o.closed
        }
        assertEquals(0, c2!!.reversals); assertTrue(c2.excluded)
    }

    @Test fun v2_steerWindowClosed_savesAndContinues() {
        idle().ev(VehicleSample(T0, 40.0, false, 1.0, TurnSignal.NONE)).let { assertNull(it.s.steer.window) }  // 세션 밖은 누적하지 않는다
        val sim = session().attempt1()
        val t0 = sim.t
        sim.ev(VehicleSample(t0, 40.0, false, 1.0, TurnSignal.NONE)).alarm(30_000)
        sim.ev(VehicleSample(t0 + 30_000, 40.0, false, 5.0, TurnSignal.NONE))
        val filtered = sim.s.steer.filtered
        sim.ev(VehicleSample(t0 + 60_000, 40.0, false, 5.0, TurnSignal.NONE))
        val rec = sim.step<SaveSteerWindow>().single().record
        assertEquals(SteerWindowRecord(sim.ses.id, t0, 2, false, true, true, 0), rec)
        assertEquals(t0 + 60_000, sim.s.steer.window!!.startAt)
        assertTrue(sim.s.steer.filtered!! > filtered!!)                      // 필터는 이어진다
    }

    @Test fun s1_staleAttemptNoticeDroppedForNewPlay() {
        val sim = session().tick(6 * MIN).gear(Gear.P).done().gear(Gear.D)   // S12 끝 → 8초 안
        sim.attempt1()                                                     // 백색소음 1차, S02는 간격을 기다림
        assertTrue(sim.step<Speak>().isEmpty())
        sim.ev(SwitchSound(sim.t + 1_000, LL)).started()                   // 자장가로 바꿈
        sim.tick(8_000)
        assertTrue(sim.all<Speak>().none { it.sid == Sid.S02 })
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S02 })
    }

    @Test fun l1_longDriveNursingRest_leadsToFeedingConfirm() {
        val sim = session().route(nurse).ev(Tick(T0 + 120 * MIN))
        val ask = sim.step<Ask>().single()
        assertEquals(Sid.S11, ask.sid)
        assertTrue(sim.ses.pendingAsk!!.hasNursingRoom)            // 문장이 아니라 고른 쉼터의 속성
        sim.ev(Answer(sim.t, ask.askId, YesNo.YES))
        sim.ev(WaypointResult(sim.t, sim.step<AddWaypoint>().single().cmdId, "n", true))
        sim.gear(Gear.P, MIN).tick(15 * MIN)
        assertEquals(Notice.FEEDING_CONFIRM, sim.step<ShowNotice>().single().kind)
    }

    @Test fun q5d_songPlaying_onlyStopWords() {
        /** 1차 음원이 m인 시도 중, S02가 끝나 듣기 창이 열린 상태. */
        fun listening(m: Method): Sim {
            val h = listOf(WN, LL).filter { it != m }.flatMap { o -> List(2) { FirstTry(T0, o, false) } }
            val sim = session(methods = listOf(WN, LL, SONG), history = h).attempt1()
            assertEquals(m, sim.play().method)
            sim.ev(SpeechDone(sim.t + 3_000, sim.s.speech.speaking!!, true))
            assertEquals(QaStep.HEARING, sim.qa.step)
            return sim
        }
        for (m in listOf(LL, SONG)) {                                 // 노래 중 질문은 버린다
            val sim = listening(m)
            sim.ev(Heard(sim.t + 1_000, sim.qa.listenId, "왜 울어?"))
            assertNull(sim.s.speech.qa); assertTrue(sim.cmds.isEmpty())
            assertEquals(Phase.ATTEMPT, sim.ses.phase)
        }
        val stop = listening(LL)                                       // 같은 상황의 '그만'은 멈춘다
        stop.ev(Heard(stop.t + 1_000, stop.qa.listenId, "그만"))
        assertEquals(Phase.WAIT, stop.ses.phase); assertEquals(Unjudged.DRIVER_STOP, stop.ep.first!!.unjudged)
        assertTrue(stop.all<Qa>().isEmpty())
        val wn = listening(WN)                                         // 백색소음 중 질문은 Qa로
        wn.ev(Heard(wn.t + 1_000, wn.qa.listenId, "왜 울어?"))
        assertEquals(Qa(wn.qa.listenId, "왜 울어?"), wn.step<Qa>().single())
        assertEquals(QaStep.THINKING, wn.qa.step)
    }

    @Test fun q5d_songEndedBeforeHeard_stillDropped() {
        for (word in listOf("왜 울어?", "그만")) {
            val h = listOf(WN).flatMap { o -> List(2) { FirstTry(T0, o, false) } }
            val sim = session(methods = listOf(WN, LL), history = h).attempt1()
            assertEquals(LL, sim.play().method)
            sim.ev(SpeechDone(sim.t + 3_000, sim.s.speech.speaking!!, true))
            val id = sim.qa.listenId; assertTrue(sim.qa.songHeard)
            sim.ev(PlaybackEnded(sim.t + 500, sim.play().id, EndReason.DEADLINE))   // 재생이 먼저 끝난다
            sim.ev(Heard(sim.t + 500, id, word))
            assertNull(sim.s.speech.qa); assertTrue(sim.all<Qa>().isEmpty())
        }
    }

    @Test fun q5d_songStartedWhileHearing_dropped() {
        val sim = session(methods = listOf(WN, LL), history = emptyList()).attempt1()
        sim.ev(SpeechDone(sim.t + 3_000, sim.s.speech.speaking!!, true))
        val id = sim.qa.listenId
        assertEquals(WN, sim.play().method)
        assertFalse(sim.qa.songHeard)
        sim.ev(SwitchSound(sim.t, LL)).started()
        assertTrue(sim.qa.songHeard)
        sim.ev(PlaybackEnded(sim.t + 500, sim.play().id, EndReason.DEADLINE))
        assertNotNull(sim.s.speech.qa); assertNull(sim.ses.play)
        sim.ev(Heard(sim.t + 100, id, "왜 울어?"))
        assertNull(sim.s.speech.qa); assertTrue(sim.all<Qa>().isEmpty())
    }

    @Test fun v1_smallSineFromZero_countsReversals() {
        // 0°에서 시작하는 ±2° 사인파(주기 10초, 10 Hz, 60초). 필터 뒤 극값 차이 ≈ 3.9° ≥ 3°라 반이 돌 때마다 반전이다.
        // 극값 12개(2.5초, …, 57.5초) 중 마지막은 3° 되돌아오기 전에 창이 닫혀 기대 11.
        var st = SteerState(null, null, null, null, 0, null)
        var closed: SteerWindowRecord? = null
        for (i in 0..600) {
            val at = i * 100L
            val o = steer(st, VehicleSample(at, 50.0, false, 2 * sin(2 * PI * at / 10_000.0), TurnSignal.NONE), false, false, "s")
            st = o.state; closed = closed ?: o.closed
        }
        val n = closed!!.reversals
        println("작은 사인파 조향 반전: 기대 11, 계산 $n, 오차 ${n - 11}")
        assertTrue(abs(n - 11) <= 1, "반전 $n")
        assertTrue(st.dir != 0)
        // 잡음 수준(±0.2°)만 있으면 방향도 반전도 생기지 않는다
        var q = SteerState(null, null, null, null, 0, null)
        var qc: SteerWindowRecord? = null
        for (i in 0..600) {
            val o = steer(q, VehicleSample(i * 100L, 50.0, false, if (i % 2 == 0) 0.2 else -0.2, TurnSignal.NONE), false, false, "s")
            q = o.state; qc = qc ?: o.closed
        }
        assertEquals(0, qc!!.reversals); assertEquals(0, q.dir)
    }

}
