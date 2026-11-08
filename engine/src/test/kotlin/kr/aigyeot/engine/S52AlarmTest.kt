package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 5.2 경보와 1차 시도(3-4·3-5·4.1).
class S52AlarmTest {
    private val feedProfile = profile(interval = 180)
    private val feedStart = T0 - 150 * MIN   // 시간대 = 시작 + 144분 ~ + 360분

    @Test fun r04a_alarm_startsAttempt1() {
        val sim = session().alarm()
        val sp = sim.step<StartPlayback>().single()
        assertEquals(PlayKind.AUTO, sp.kind)
        assertEquals(sim.t + 56_000, sp.endAt)
        assertEquals(2_000, sp.fadeMs)
        assertEquals(Phase.ATTEMPT, sim.ses.phase)
        assertNull(sim.play().startedAt)
        assertEquals(PendingAlarm(sim.t, sim.t - 3_000 - 975), sim.ses.pendingAlarm)
    }

    @Test fun r04b_started_opensEpisodeSaysS02() {
        val sim = session().attempt1()
        val e = sim.ep
        assertEquals(EpisodeTrigger.ALARM, e.trigger)
        assertEquals(sim.t, e.alarmAt)
        assertEquals(3_975, e.alarmDelayMs)
        assertNull(sim.ses.pendingAlarm)
        assertEquals(listOf("SaveEpisode", "CaptureContext", "Speak"), sim.cmds.map { it::class.simpleName })
        assertEquals(Speak(sim.s.speech.speaking!!, Sid.S02, mapOf("method" to "백색소음")), sim.step<Speak>().single())
    }

    @Test fun r04c_focusDeniedAtAttempt1_logsOnly() {
        val sim = session().alarm()
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.FOCUS_DENIED))
        assertEquals(Phase.LISTENING, sim.ses.phase)
        assertNull(sim.ses.episode); assertNull(sim.ses.pendingAlarm); assertNull(sim.ses.play)
        assertEquals(listOf<Command>(LogAlarm(sim.ses.id, sim.t, AlarmOnly.FOCUS_DENIED)), sim.cmds)
    }

    @Test fun r04j_startErrorAtAttempt1_logsOnly() {
        val sim = session().alarm()
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.ERROR))
        assertEquals(Phase.LISTENING, sim.ses.phase)
        assertNull(sim.ses.episode); assertNull(sim.ses.play); assertNull(sim.ses.pendingAlarm)
        assertEquals(listOf<Command>(LogAlarm(sim.ses.id, sim.t, AlarmOnly.FOCUS_DENIED)), sim.cmds)
        assertTrue(sim.all<SavePlay>().isEmpty())
    }

    @Test fun r04k_noRearOutput_handoffWithoutSound() {
        val sim = session().alarm()
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.NO_REAR_OUTPUT))
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        val e = sim.ep
        assertEquals(sim.t, e.alarmAt); assertNull(e.first); assertNull(e.second); assertEquals(0, e.playedMs)
        assertEquals(HandoffCause.NO_SOUND, e.handoff!!.cause)
        assertTrue(sim.step<CaptureContext>().isNotEmpty())
        assertTrue(Sid.S02 !in sim.sids())
        assertTrue(Sid.S10 in sim.sids())   // 경로 정보 없음 → 쉼터 없음(C22)
        assertTrue(sim.step<SendToPhone>().isEmpty())   // C146: 알림은 S10을 말한 뒤
        sim.done()
        assertEquals("handoff_alert", sim.all<SendToPhone>().single().type)
    }

    @Test fun r04d_alarmAfterCap_logsOnly() {
        val sim = session()
        sim.s = sim.s.copy(session = sim.ses.copy(playedMs = P.sessionPlayCapMs))
        sim.alarm()
        assertEquals(listOf<Command>(LogAlarm(sim.ses.id, sim.t, AlarmOnly.SESSION_CAP)), sim.cmds)
        assertEquals(Phase.LISTENING, sim.ses.phase)
    }

    @Test fun r04e_feeding_bestThenS06AndOffer() {
        val sim = session(prof = feedProfile, feedingStart = feedStart).route(rest("n", 8.0, nursing = true)).attempt1()
        assertEquals(TryResult.UNJUDGED, sim.ep.first!!.result)
        assertEquals(Unjudged.FEEDING_WINDOW, sim.ep.first!!.unjudged)
        assertEquals(listOf(Sid.S02, Sid.S06, Sid.S07), sim.sids())
        assertTrue(sim.all<SendToPhone>().isEmpty())
        assertTrue(!sim.ep.s06Said)
        sim.done().tick(8_000)                          // S06을 낸다
        assertEquals(Sid.S06, sim.step<Speak>().single().sid)
        assertTrue(!sim.ep.s06Said)
        sim.done()
        assertTrue(sim.ep.s06Said)                      // SpeechDone(spoken) 뒤에만 참(C15·C66)
        assertEquals(PlayMode.NORMAL, sim.play().mode)   // 재생은 계속
    }

    @Test fun r04f_firstSix_roundRobin() {
        val h = listOf(FirstTry(T0, WN, true), FirstTry(T0, WN, false), FirstTry(T0, LL, false))
        assertEquals(LL, session(history = h).alarm().step<StartPlayback>().single().method)
        assertEquals(WN, session().alarm().step<StartPlayback>().single().method)
    }

    @Test fun r04g_thompson_fixedSeedReproducible() {
        val h = listOf(WN, LL).flatMap { m -> List(3) { FirstTry(T0, m, it == 0) } }
        val a = session(history = h, seed = 7).alarm()
        val b = session(history = h, seed = 7).alarm()
        assertEquals(a.step<StartPlayback>(), b.step<StartPlayback>())
        // 참조 표본: 시드 7은 Beta(2,3) 0.778 > 0.349 → 백색소음, 시드 2는 자장가
        assertEquals(WN, a.step<StartPlayback>().single().method)
        assertEquals(3326683750974675161L, a.s.rng)
        assertEquals(pickFirst(listOf(WN, LL), h, 7), Pick(WN, a.s.rng))
        assertEquals(LL, session(history = h, seed = 2).alarm().step<StartPlayback>().single().method)
    }

    @Test fun r04h_feeding_usesBestNotExperiment() {
        val h = listOf(FirstTry(T0, WN, true), FirstTry(T0, WN, true), FirstTry(T0, LL, false))
        assertEquals(LL, session(history = h).alarm().step<StartPlayback>().single().method)   // 평소: 처음 6번
        val sim = session(prof = feedProfile, history = h, feedingStart = feedStart, seed = 5).alarm()
        assertEquals(WN, sim.step<StartPlayback>().single().method)                           // 수유: 성적
        assertEquals(5L, sim.s.rng)
    }

    @Test fun r05a_parkedAlarm_startsAttempt() {
        val sim = session().gear(Gear.P, MIN).alarm()
        assertEquals(1, sim.step<StartPlayback>().size)
        assertEquals(Phase.ATTEMPT, sim.ses.phase)
        assertEquals(sim.t, sim.ses.pendingAlarm!!.alarmAt)
    }

    @Test fun r05b_parkedAlarm_unjudgedReadingLightOff() {
        val sim = session().gear(Gear.P, MIN).attempt1()
        assertEquals(Unjudged.PARKED_ALARM, sim.ep.first!!.unjudged)
        assertEquals(TryResult.UNJUDGED, sim.ep.first!!.result)
        val light = sim.step<SetReadingLight>().single()
        assertEquals(listOf(Area.ROW_2_LEFT), light.areas); assertEquals(false, light.on)
        assertTrue(Sid.S02 in sim.sids())
    }

    @Test fun r04i_pendingAlarmCarriedToEpisode_orDropped() {
        val sim = session()
        sim.ev(CrySegment(sim.t, sim.t - 3_000, sim.t, 0.9, true, injectedCryAt = sim.t - 5_000))
        val alarmAt = sim.t
        sim.ev(PlaybackStarted(sim.t + 400, sim.play().id, null, false))
        assertEquals(alarmAt, sim.ep.alarmAt)
        assertEquals(5_000, sim.ep.alarmDelayMs)   // 기준점은 PlaybackStarted가 아니다

        val drop = session().alarm()
        drop.ev(DriverStop(drop.t, Via.SCENARIO))
        assertEquals(Phase.WAIT, drop.ses.phase)
        assertNull(drop.ses.pendingAlarm)
        assertNull(drop.ses.episode)
        assertTrue(drop.all<SavePlay>().isEmpty())   // 에피소드 전 재생은 행을 만들지 않는다
    }

    @Test fun r15c_alarmInCall_logsOnly() {
        val sim = session().ev(CallMode(T0, true)).alarm()
        assertEquals(listOf<Command>(LogAlarm(sim.ses.id, sim.t, AlarmOnly.IN_CALL)), sim.cmds)
        assertEquals(Phase.LISTENING, sim.ses.phase)
    }
}
