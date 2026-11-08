package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// C348(G37): 경보로 연 에피소드는 경보를 이룬 구간(alarmSegments개)을 들은 울음으로 넣고 시작한다. 소리 바꾸기는 0.
class C348CrySeedTest {
    private val seed get() = P.alarmSegments * P.yamnetHopMs

    @Test fun c348_alarmEpisode_startsWithAlarmSegments() {
        val sim = session().attempt1()
        assertEquals(EpisodeTrigger.ALARM, sim.ep.trigger); assertEquals(Phase.ATTEMPT, sim.ses.phase)
        assertEquals(seed, sim.ep.cryMs)
    }

    @Test fun c348_quickFirstSuccess_recordsNonZeroCry() {
        val sim = session().attempt1().toConfirm().closeQuiet()
        val rec = sim.step<SaveEpisode>().single().record
        assertEquals(TryResult.SUCCESS, rec.firstResult)
        assertTrue(rec.cryMs != 0L && rec.cryMs >= seed, "cryMs=${rec.cryMs}")
    }

    @Test fun c348_noRearOutput_episodeAlsoSeeded() {
        val sim = session().alarm()
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.NO_REAR_OUTPUT))
        assertEquals(EpisodeTrigger.ALARM, sim.ep.trigger)
        assertEquals(seed, sim.ep.cryMs)
    }

    // G37·C348: 확인 창 끝을 넘기는 큰 구간은 성공으로 닫게 한 구간이라 옛 에피소드에 더하지 않는다(다음 경보 시작값과 두 번 세지 않게)
    @Test fun c348_confirmCrossingSegment_notDoubleCounted() {
        val sim = session().attempt1().toConfirm()
        assertEquals(Phase.CONFIRM, sim.ses.phase)
        val end = sim.ses.stage.window!!.end
        val before = sim.ep.cryMs
        assertEquals(seed, before)
        sim.ev(CrySegment(end + 500, end - 1_000, end + 2_000, 0.9, false))
        val rec = sim.step<SaveEpisode>().single().record
        assertEquals(TryResult.SUCCESS, rec.firstResult)
        assertEquals(Phase.LISTENING, sim.ses.phase)
        assertEquals(before, rec.cryMs, "닫게 한 경계 구간은 옛 기록에 넣지 않는다")
        sim.alarm(dt = P.yamnetHopMs).started()
        assertEquals(Phase.ATTEMPT, sim.ses.phase)
        assertEquals(seed, sim.ep.cryMs)
        assertEquals(seed * 2, rec.cryMs + sim.ep.cryMs, "경계 구간은 한 번만 센다")
    }

    // G37: 재울음 구간은 recry()가 1차를 저장한 뒤에도 열려 있는 에피소드(2차)에 더해져 최종 기록에 들어간다
    @Test fun c348_recrySegment_inFinalRecord() {
        val sim = session().attempt1().toGap().recry()
        assertEquals(Phase.ATTEMPT, sim.ses.phase); assertEquals(2, sim.ses.attemptNo)
        sim.started().toConfirm()
        assertEquals(Phase.CONFIRM, sim.ses.phase)
        sim.closeQuiet()
        assertEquals(Phase.LISTENING, sim.ses.phase)
        val rec = sim.all<SaveEpisode>().last().record
        assertEquals(seed + P.yamnetHopMs, rec.cryMs)
    }

    @Test fun c348_switchSound_startsAtZero() {
        val sim = session().ev(SwitchSound(T0, LL))
        assertEquals(EpisodeTrigger.SWITCH_SOUND, sim.ep.trigger)
        assertEquals(0, sim.ep.cryMs)
    }

    @Test fun c348_seenCryAfterOpen_addsOnTop() {
        val sim = session().attempt1()
        sim.ev(CrySegment(sim.t + 3_000, sim.t, sim.t + 3_000, 0.9, true))   // ATTEMPT 중은 보지 않는다
        assertEquals(seed, sim.ep.cryMs, "ATTEMPT 중 구간은 더하지 않는다")
        sim.toGap()
        val from = sim.ses.judgeFrom!!
        sim.ev(CrySegment(from + 3_000, from, from + 3_000, 0.9, false))
        assertEquals(seed + P.yamnetHopMs, sim.ep.cryMs)
    }
}
