package kr.aigyeot.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 명세서 17장 C276·C277·C279.
class C276ToC279Test {
    private fun Sim.asked() = all<Ask>().map { it.sid }
    private fun Sim.spoke() = all<Speak>().map { it.sid }
    /** 목적지 없는 경로. pending이면 쉼터 목록을 다시 받는 중(C269). */
    private fun Sim.road(dt: Long, vararg rests: RestCandidate, pending: Boolean = false) =
        ev(RouteState(t + dt, false, null, rests.toList(), restsPending = pending))

    // ---- C276: '물었다·들었다'는 그 줄의 SpeechDone(spoken = true)에만 근거한다 ----

    @Test fun c276_s11TtsFailed_answerNone_notHeard_reaskedThenNoSetsOffered() {
        val sim = session().route(rest("a", 8.0))
        sim.ev(Tick(T0 + 120 * MIN))
        val ask1 = sim.step<Ask>().single(); assertEquals(Sid.S11, ask1.sid)
        sim.done(spoken = false)                                         // TTS 실패: 같은 Step에서 L1이 S11을 다시 넣는다(S1 간격으로 대기)
        assertNull(sim.ses.pendingAsk, "발화되지 않은 질문은 답을 기다리지 않는다")
        assertTrue(sim.s.speech.queue.single().let { it.sid == Sid.S11 && it.longDrive })
        sim.ev(Answer(sim.t, ask1.askId, YesNo.NONE))                    // 실행기가 발화 실패 뒤 보내는 '답 없음'
        assertFalse(sim.ses.longDriveOffered, "운전자는 듣지 못했다")
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S11 }, "다시 넣은 질문을 답 뒤 정리(C273)로 지우지 않는다")
        sim.tick(P.speechGapMs)
        val ask2 = sim.step<Ask>().single(); assertEquals(Sid.S11, ask2.sid)
        sim.done(2_000)
        assertTrue(sim.ses.longDriveOffered, "실제로 말했다")
        sim.ev(Answer(sim.t + 1_000, ask2.askId, YesNo.NO)).tick(MIN).tick(MIN)
        assertTrue(sim.ses.longDriveOffered)
        assertEquals(listOf(Sid.S11, Sid.S11), sim.asked())
    }

    @Test fun c276_unspokenQuestion_lateYesIgnored_noWaypoint() {
        val sim = session().route(rest("a", 8.0)).handoff()
        repeat(6) { if (sim.asked().isEmpty()) { if (sim.s.speech.speaking != null) sim.done(); sim.tick(P.speechGapMs) } }   // S02·S03 뒤 인계 질문
        val ask = sim.all<Ask>().single(); assertEquals(Sid.S08, ask.sid)
        sim.done(spoken = false)
        sim.ev(Answer(sim.t, ask.askId, YesNo.YES))                      // 장면 답·늦은 답
        assertTrue(sim.all<AddWaypoint>().isEmpty(), "듣지 못한 질문의 답으로 경유지를 넣지 않는다")
        assertNull(sim.ses.restLog.last().answer, "답을 기록하지 않는다")
        assertEquals(false, sim.told())
    }

    @Test fun c276_answerCleanup_droppedLongDriveLine_noBanForUnspokenSentence() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("a", 12.0)).alarm().started()
        sim.done().tick(P.speechGapMs).done().tick(P.speechGapMs)        // S02·S06 → S08(a)을 묻는다
        val ask = sim.step<Ask>().single(); assertEquals(Sid.S08, ask.sid)
        sim.done()
        sim.s = sim.s.copy(session = sim.ses.withOffers { copy(driveFrom = sim.t - 121 * MIN) })
        sim.tick()                                                       // L1이 같은 쉼터로 S11(a)을 넣는다
        assertEquals(Sid.S11, sim.s.speech.queue.single { it.askRestId == "a" }.sid)
        sim.ev(Answer(sim.t + 1_000, ask.askId, YesNo.NO))
        assertTrue(sim.ses.longDriveOffered, "같은 쉼터를 물었으니 장시간 출처는 들은 것으로 본다(C273)")
        assertTrue(Sid.S11 !in sim.ses.spokenAt, "버린 S11은 말하지 않았으니 그 문장의 10분 금지는 남기지 않는다")
    }

    // ---- C279: 경로가 정해지지 않은 동안의 쉼터 제안은 금지를 보지 않고 자리만 잡고, 꺼낼 때 본다 ----

    /** 수유 시간대에 쉼터 없는 길에서 S10을 말했고(10분 금지) 첫 울음은 그쳤다. 금지 시각을 돌려준다. */
    private fun feedingS10Spoken(): Pair<Sim, Long> {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).road(0)
        sim.alarm().started().flush()                                    // S02·S06·S10
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

    @Test fun c279_feedingOfferWhilePending_bannedS10_heldThenAsksS07() {
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

    @Test fun c279_heldS10_listEmpty_bannedAtTakeOut_dropped() {
        val (sim, banAt) = feedingS10Spoken()
        sim.tick(MIN).cryWhilePending()
        assertTrue(sim.s.speech.queue.any { it.sid == Sid.S10 }, "전제: 보류 줄")
        sim.road(1_000).tick(P.speechGapMs)                              // 목록이 왔고 쉼터가 없다
        assertEquals(1, sim.spoke().count { it == Sid.S10 }, "꺼낼 때 실제로 고른 문장(S10)의 10분 금지를 본다")
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S10 }, "금지에 걸린 보류 줄이 남지 않는다")
        assertEquals(banAt, sim.ses.spokenAt[Sid.S10])
    }

    @Test fun c279_heldS10_banExpiredByTakeOut_spoken_banFromSpeech() {
        val (sim, banAt) = feedingS10Spoken()
        sim.ev(Tick(banAt + P.offerRepeatMs - 30_000)).cryWhilePending()   // 금지가 끝나기 30초 전에 자리만 잡는다
        assertEquals(banAt, sim.ses.spokenAt[Sid.S10])
        sim.road(30_000).tick(P.speechGapMs)                             // 목록(쉼터 없음)이 올 때는 금지가 끝났다
        assertEquals(2, sim.spoke().count { it == Sid.S10 })
        sim.done(2_000)
        assertEquals(sim.t, sim.ses.spokenAt[Sid.S10], "넣을 때 금지를 남기지 않은 줄은 말한 때부터 막는다")
    }

    // ---- C277·C287: 이번 세션 울음 수는 판단 코어가 센다(이어진 울음마다 하나) ----

    @Test fun c287_cryRuns_alarmEpisodePath_clearedToZero() {
        val sim = session()
        repeat(2) { sim.attempt1().toConfirm().closeQuiet() }
        assertEquals(2, sim.ses.cryRuns, "경보 에피소드 길도 같은 기준(경보로 연 울음마다 하나)")
        sim.alarm().ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.NO_REAR_OUTPUT))   // 4k: 소리 없이 연 경보 에피소드
        assertEquals(EpisodeTrigger.ALARM, sim.ep.trigger); assertEquals(3, sim.ses.cryRuns)
        sim.ev(ClearRecords(sim.t))
        assertEquals(0, sim.ses.cryRuns, "기록을 지우면 0"); assertNull(sim.ses.alarmRun)
        sim.alarm(975)                                                    // 지우기 전부터 울던 울음이 이어져도
        assertEquals(1, sim.ses.cryRuns, "지운 뒤부터 새로 센다(지금 울음과 수가 어긋나지 않게)")
        sim.ev(Tick(sim.t + P.resumeQuietMs))                            // 인계가 조용히 끝난다
        sim.attempt1()
        assertEquals(2, sim.ses.cryRuns)
    }

    // ---- C286: 이어진 경보(G3)를 단계와 상관없이 센다 ----

    @Test fun c286_alarmRun_trackedInSwitchSoundEpisode_restartsAfterGap() {
        val sim = session().ev(SwitchSound(T0, LL)).started()
        assertEquals(Phase.WAIT, sim.ses.phase)
        sim.alarm(1_000).alarm(975).alarm(975)                             // 한 걸음마다 경보
        assertEquals(AlarmRun(T0 + 1_000, T0 + 2_950), sim.ses.alarmRun)
        assertEquals(1, sim.ses.cryRuns, "C287: 소리를 직접 튼 중의 울음도 센다")
        sim.alarm(2 * P.yamnetHopMs + 1)                                   // 문턱보다 오래 끊겼다
        assertEquals(AlarmRun(sim.t, sim.t), sim.ses.alarmRun, "새로 이어진 경보")
        assertEquals(2, sim.ses.cryRuns, "끊겼다 다시 울면 하나 더")
        sim.ev(CrySegment(sim.t + 975, sim.t - 2_025, sim.t + 975, 0.9, false))   // 경보 아닌 구간은 세지 않는다
        assertEquals(sim.t - 975, sim.ses.alarmRun!!.last); assertEquals(2, sim.ses.cryRuns)
    }

    @Test fun c288_autoAttemptPlaying_alarmsNotCounted_gapRecryCountedNew_waitCounted() {
        val sim = session().attempt1()                                     // LISTENING 경보로 연 울음 하나 → 자동 시도 재생
        val run = sim.ses.alarmRun; assertEquals(1, sim.ses.cryRuns); assertEquals(Phase.ATTEMPT, sim.ses.phase)
        sim.alarm(5_000).alarm(975)                                        // 재생 중 경보(노래 오인일 수 있다)
        assertEquals(run, sim.ses.alarmRun, "자동 시도 재생 중에는 이어짐을 갱신하지 않는다"); assertEquals(1, sim.ses.cryRuns)
        sim.toGap()
        val from = sim.ses.judgeFrom!!
        sim.ev(CrySegment(from + 3_000, from, from + 3_000, 0.9, true))    // 재생이 끝난 뒤 다시 운다
        assertEquals(2, sim.ses.cryRuns, "재생 뒤 다시 울면 새로 센다"); assertEquals(Phase.ATTEMPT, sim.ses.phase)
        sim.ev(DriverStop(sim.t + 1_000, Via.VOICE))                       // 운전자 중단 → WAIT
        assertEquals(Phase.WAIT, sim.ses.phase)
        sim.alarm(5_000)
        assertEquals(3, sim.ses.cryRuns, "WAIT 중 경보는 센다")
    }

    @Test fun c301_segmentStartedDuringPlayback_notCounted() {
        val sim = session().attempt1().toGap()
        val from = sim.ses.judgeFrom!!
        assertEquals(1, sim.ses.cryRuns)
        sim.ev(CrySegment(from + 975, from - 2_025, from + 975, 0.9, true, null))   // 재생 끝 전에 시작한 구간
        assertEquals(1, sim.ses.cryRuns, "판정이 보지 않는 구간은 세지 않는다")
        sim.ev(CrySegment(from + 3_000, from, from + 3_000, 0.9, true))             // 재생 뒤 시작: 판정(다시 울음)과 함께 센다
        assertEquals(2, sim.ses.cryRuns)
    }

    @Test fun c287_switchSoundEpisode_noAlarm_zeroRuns() {
        val sim = session().ev(SwitchSound(T0, LL))
        assertEquals(EpisodeTrigger.SWITCH_SOUND, sim.ep.trigger)
        assertEquals(0, sim.ses.cryRuns)
    }

    // ---- C280: 같은 문장이라는 이유로 다른 쉼터 줄을 다시 쓰지 않는다 ----

    private fun Sim.alerts() = all<SendToPhone>().filter { it.type == "handoff_alert" }.map { it.body }
    private fun JsonObject.str(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private val nb = rest("b", 20.0, nursing = true)

    /**
     * 2차 시도 GAP. 큐에는 쉼터 a를 묻는 S07 줄(speaking이면 말하는 중, 발화 id "uA"). 경로에는 [route]만 있다.
     * 결정 ②: 큐(말하는 맨 앞 줄 포함)에 있는 a 줄이 같은 문장(S07)을 막는다(넣을 때 남기던 금지 시각은 없다).
     * otherHead면 다른 문장(S12)이 앞에서 말하는 중이라 인계 Step에 꺼내기(다시 고르기)가 돌지 않는다.
     */
    private fun gapWithA(speaking: Boolean, route: RestCandidate = nb, otherHead: Boolean = false): Sim {
        val sim = session().route(route).attempt2().toGap()
        val a = Line(Sid.S07, mapOf("restName" to "a 휴게소", "distanceKm" to "12"), "a", queuedAt = sim.t, sessionId = sim.ses.id)
        val head = listOfNotNull(Line(Sid.S12, emptyMap(), null, queuedAt = sim.t, sessionId = sim.ses.id).takeIf { otherHead })
        sim.s = sim.s.copy(speech = SpeechQueue(if (speaking) "uA" else if (otherHead) "u0" else null, sim.t, head + a), session = sim.ses.withOffers {
            copy(pendingAsk = if (speaking) PendingAsk("uA", Sid.S07, "a", sim.ep.id, true) else null) })
        return sim
    }

    @Test fun c280_speakingOtherRest_banned_notBoundToSpeaking_plainAlert() {
        val sim = gapWithA(speaking = true).recry()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertNull(sim.ledger, "말하는 a 질문에 묶지 않는다")
        val a = sim.alerts().single()
        assertNull(a.str("rest_name")); assertEquals("아기가 계속 울어요.", a.str("text"), "S07이 10분 금지라 H3대로 이름 없는 알림")
        assertEquals(false, sim.told())
        sim.done()                                                       // a 질문이 끝나도
        assertEquals(1, sim.alerts().size); assertTrue(sim.alerts().none { "a 휴게소" in it.toString() })
    }

    @Test fun c280_speakingOtherRest_unheard_bNotAsked_plain() {   // 결정 ②: 말하는 a(S07)가 큐에 있어 b(S07) 줄을 넣지 않는다. a가 들리지 않아도 그대로
        val sim = gapWithA(speaking = true).recry()
        assertNull(sim.ledger, "새 b 줄이 없다")
        sim.done(spoken = false).tick(P.speechGapMs)                     // a는 들리지 않았다
        assertTrue(sim.step<Ask>().isEmpty())
        val a = sim.alerts().single(); assertNull(a.str("rest_name")); assertEquals(false, sim.told())
    }

    @Test fun c280_speakingOtherRest_notBanned_aHeard_bBannedAtTakeOut_plain() {
        val sim = gapWithA(speaking = true).recry()
        sim.done(2_000).tick(P.speechGapMs)                              // a S07을 들었으니 10분 안의 S07(b)은 S3에 걸린다
        assertTrue(sim.step<Ask>().isEmpty())
        val a = sim.alerts().single(); assertNull(a.str("rest_name")); assertEquals(false, sim.told())
    }

    @Test fun c280_queuedOtherRest_handoff_rewrittenToB() {
        val sim = gapWithA(speaking = false, otherHead = true).recry()
        val l = sim.s.speech.queue.single { it.sid == Sid.S07 }
        assertTrue(l.handoff); assertEquals("b", l.askRestId); assertEquals("b 휴게소", l.vars["restName"], "인계 때도 지금 쉼터 값으로 바꿔 묶는다(꺼내기 전)")
        sim.done(2_000).tick(P.speechGapMs)
        assertEquals("b 휴게소", sim.step<Ask>().single().vars["restName"])
        sim.done()
        assertEquals("b 휴게소", sim.alerts().single().str("rest_name"))
    }

    @Test fun c280_speakingSameRest_handoff_boundToUtterance() {   // C131 그대로
        val sim = gapWithA(speaking = true, route = rest("a", 12.0, nursing = true)).recry()
        assertEquals("uA", sim.ledger!!.utteranceId)
        sim.done()
        assertEquals("a 휴게소", sim.alerts().single().str("rest_name")); assertEquals(true, sim.told())
    }

    // ---- C285: 미룸(S5)·만료(S6)·앞 버림은 다시 고른 문장으로 판단한다 ----

    private fun Sim.busy(dt: Long) = ev(VehicleSample(t + dt, 60.0, true, 0.0, TurnSignal.NONE))

    /** 쉼터 목록을 기다리는 동안 인계가 임시 S10을 큐에 두었다. 앞 안내를 끝내고 [waitMs] 더 기다린다(목록은 아직). */
    private fun heldHandoffS10(waitMs: Long = 0): Sim {
        val sim = session().attempt2().toGap().road(0, pending = true).recry()
        assertEquals(Sid.S10, sim.s.speech.queue.single { it.handoff }.sid)
        sim.done().tick(P.speechGapMs).tick(waitMs)
        assertTrue(sim.asked().isEmpty() && Sid.S10 !in sim.spoke(), "전제: 목록을 기다리며 보류")
        return sim
    }

    @Test fun c285_heldS10_listBringsS08_busyOver30s_askedWithName() {
        val sim = heldHandoffS10()
        sim.busy(0).road(1_000, rest("n", 8.0))                          // 바쁜 중에 목록이 온다: 지금 안내는 S08(미루지 않음)
        repeat(35) { if (sim.asked().isEmpty()) sim.busy(1_000) }        // 바쁨이 30초(holdMaxMs) 넘게 이어져도
        val ask = sim.all<Ask>().single(); assertEquals(Sid.S08, ask.sid); assertEquals("n 휴게소", ask.vars["restName"])
        assertTrue(sim.alerts().isEmpty(), "이름 없는 알림을 먼저 보내지 않는다")
        sim.done(2_000)
        assertEquals("n 휴게소", sim.alerts().single().str("rest_name")); assertEquals(true, sim.told())
    }

    @Test fun c285_heldS10_listBringsS07_newLineNotExpiredByHoldWait() {
        val sim = heldHandoffS10(waitMs = 16_000)                        // 임시 줄은 목록을 25초 가까이 기다렸다
        sim.busy(0).road(1_000, rest("n", 8.0, nursing = true))          // S07은 미루는 문장이다
        val l = sim.s.speech.queue.single { it.handoff }
        assertEquals(Sid.S07, l.sid); assertEquals(sim.t, l.queuedAt, "새 줄의 만료 기준은 지금(임시 줄이 기다린 시간을 잇지 않는다)")
        repeat(10) { sim.busy(1_000) }                                   // 임시 줄 시각이면 30초를 넘었을 바쁨
        assertTrue(sim.asked().isEmpty(), "바쁜 동안은 미룬다")
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        assertEquals(Sid.S07, sim.step<Ask>().single().sid, "만료되지 않고 잠잠해지면 묻는다")
    }

    @Test fun c285_heldS10_listEmpty_stillS10_heldWhileBusy_thenSaid() {
        val sim = heldHandoffS10()
        sim.busy(0).road(1_000)                                          // 목록이 왔고 쉼터가 없다: 다시 골라도 S10
        sim.busy(1_000)
        assertTrue(Sid.S10 !in sim.spoke(), "지금처럼 바쁨에 미룬다")
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        assertEquals(Sid.S10, sim.step<Speak>().single().sid)
    }

    @Test fun c285_heldS10_waited40s_stillS10_busyRightAway_notExpired_saidAfterCalm() {
        val sim = heldHandoffS10(waitMs = 21_000)
        repeat(10) { sim.busy(1_000) }                                   // 임시 S10이 목록을 40초 기다렸고 그 끝 10초는 바빴다(보류 중이라 만료 아님)
        assertTrue(sim.s.speech.queue.any { it.handoff && it.sid == Sid.S10 })
        sim.road(1_000)                                                  // 목록이 왔고 쉼터가 없다: 그대로 S10, 곧바로 바쁨
        val l = sim.s.speech.queue.single { it.handoff }
        assertEquals(sim.t, l.queuedAt, "만료 기준은 경로가 정해진 지금")
        assertNull(sim.ses.spokenAt[Sid.S10], "결정 ②: 말하기 전에는 금지 시각이 없다(옮길 짝이 없다)")
        repeat(5) { sim.busy(1_000) }
        assertTrue(Sid.S10 !in sim.spoke(), "바쁜 동안은 미룬다")
        sim.ev(Tick(sim.s.steer.driveBusyAt!! + P.holdStableMs))
        assertEquals(Sid.S10, sim.step<Speak>().single().sid, "기다린 시간 때문에 만료되지 않고 잠잠해지면 말한다")
    }

    // C295: 복원 기다림 중 보류한 S10이, 새 경로 기다림으로 이어진 기다림의 시작이 밀려도 버려지지 않는다
    @Test fun c295_bootWaitExpiresDuringRestsPending_heldS10Kept() {
        val sim = session(); sim.s = sim.s.copy(bootAt = T0)
        sim.alarm(1_000)
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.NO_REAR_OUTPUT))   // S10 보류
        sim.ev(RouteState(T0 + 35_000, true, 3600, emptyList(), 150.0, true))         // 새 경로, 쉼터 목록 기다림
        sim.ev(Tick(T0 + 60_000))                                                      // 복원 기다림 만료
        sim.ev(VehicleSample(T0 + 64_000, 60.0, true, 0.0, TurnSignal.NONE))          // 제동
        sim.ev(RouteState(T0 + 65_000, true, 3600, emptyList(), 149.0, false))        // 빈 목록 도착
        sim.ev(Tick(T0 + 70_000))
        assertTrue(Sid.S10 in sim.spoke() || sim.s.speech.queue.any { it.sid == Sid.S10 }, "보류한 S10을 버리지 않는다")
    }

    @Test fun c285_lineQueuedBeforeWait_keepsOriginalQueuedAt() {
        val sim = session().road(0).attempt2().toGap().recry()          // 정해진 경로(쉼터 없음)에서 인계 S10
        val at = sim.s.speech.queue.single { it.handoff }.queuedAt
        sim.road(1_000, pending = true).road(5_000)                      // 그 뒤 목록을 다시 받았다
        assertEquals(at, sim.s.speech.queue.single { it.handoff }.queuedAt, "기다림 전에 넣은 줄은 원래 시각")
    }

    @Test fun c280_offer_queuedOtherRestSameSentence_rewrittenToB() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(nb)
        val head = Line(Sid.S12, emptyMap(), null, queuedAt = sim.t, sessionId = sim.ses.id)   // 말하는 중인 다른 문장(꺼내기가 먼저 다시 고르지 않게)
        val a = Line(Sid.S07, mapOf("restName" to "a 휴게소", "distanceKm" to "12"), "a", queuedAt = sim.t, sessionId = sim.ses.id)
        sim.s = sim.s.copy(speech = SpeechQueue("u0", sim.t, listOf(head, a)))
        sim.alarm().started()                                            // 수유 시간대 제안이 S07(b)을 고른다
        val l = sim.s.speech.queue.single { it.sid == Sid.S07 }
        assertEquals("b", l.askRestId, "인계가 아닌 제안도 같은 규칙"); assertFalse(l.handoff)
    }

    /** C308: 2시간이 되는 순간 경로가 수유실 휴게소로 바뀌어 S11이 큐에 들어가면, 다시 고르기가 겹친 줄을 S07로 바꾸되 10분 금지를 본다. */
    private fun c308Sim(banS07: Boolean): Sim {
        val p = Params(); val sim = session().route(rest("a", 8.0)).handoff()
        val guide = sim.s.speech.queue.single { it.handoff }   // S08
        val now = sim.t
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = null, queue = listOf(guide), lastDoneAt = now - p.speechGapMs),
            session = sim.ses.withOffers { copy(driveFrom = now + 1000 - p.longDriveMs, spokenAt = if (banS07) spokenAt + (Sid.S07 to now - 2 * MIN) else spokenAt) })
        sim.ev(RouteState(now + 1000, true, 3600, listOf(rest("b", 9.0, true)), 40.0))
        return sim
    }

    @Test fun c308_repickOverlapRespectsBan() {
        assertTrue(c308Sim(banS07 = true).step<Ask>().none { it.sid == Sid.S07 }, "S07을 말한 지 10분이 안 됐으면 겹친 줄을 S07로 바꿔 묻지 않는다")
    }

    @Test fun c308_repickOverlapWithoutBan_becomesS07() {
        assertEquals(1, c308Sim(banS07 = false).step<Ask>().count { it.sid == Sid.S07 })
    }
}
