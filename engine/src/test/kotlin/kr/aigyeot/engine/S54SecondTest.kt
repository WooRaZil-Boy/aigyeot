package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 5.4 2차 시도(4.2).
class S54SecondTest {
    /** 1차 실패 뒤 ATTEMPT(2) 시작 대기. */
    private fun Sim.wait2() = attempt1().toGap().recry()

    @Test fun r42a_hot_lowersHvacReplaysFirstSaysS04() {
        val sim = session().ev(CabinTemp(T0, 27.6)).wait2()
        assertEquals(WN, sim.step<StartPlayback>().single().method)     // 1차 음원을 다시
        sim.started()
        assertEquals(SecondAction.COMFORT, sim.ep.second!!.action)
        assertTrue(sim.ses.comfortDone)
        val hv = sim.step<LowerHvac>().single()
        assertEquals(listOf(Area.ROW_1_LEFT), hv.areas); assertEquals(-2.0, hv.deltaC)
        assertTrue(Sid.S04 in sim.sids())
        sim.flush()
        assertEquals(mapOf("tempC" to "28"), sim.all<Speak>().first { it.sid == Sid.S04 }.vars)
    }

    @Test fun r42b_feeding_otherBestS03S06Offer() {
        // 1차 때는 시간대 밖, 2차 때 시간대 안
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 144 * MIN + 30_000)
            .route(rest("a", 9.0)).wait2()
        assertEquals(LL, sim.step<StartPlayback>().single().method)
        sim.started()
        assertEquals(SecondAction.FEEDING, sim.ep.second!!.action)
        assertEquals(listOf(Sid.S02, Sid.S03, Sid.S06, Sid.S08), sim.sids())
        assertTrue(sim.all<SendToPhone>().isEmpty())
        assertEquals(Phase.ATTEMPT, sim.ses.phase)
        // S06을 이미 말했으면 건너뛴다
        val b = session(prof = profile(interval = 180), feedingStart = T0 - 144 * MIN + 30_000).route(rest("a", 9.0)).wait2()
        b.s = b.s.copy(session = b.ses.copy(episode = b.ep.copy(s06Said = true)))
        b.started()
        assertEquals(1, b.sids().count { it == Sid.S03 }); assertTrue(Sid.S06 !in b.sids())
    }

    @Test fun r42c_else_otherBestSaysS03() {
        val sim = session().wait2()
        assertEquals(LL, sim.step<StartPlayback>().single().method)
        sim.started()
        assertEquals(SecondAction.OTHER, sim.ep.second!!.action)
        assertEquals(listOf(Sid.S02, Sid.S03), sim.sids())
        sim.flush()
        assertEquals(mapOf("method" to "자장가"), sim.all<Speak>().first { it.sid == Sid.S03 }.vars)
    }

    @Test fun r42d_focusDeniedAtAttempt2_waitNoActions() {
        val sim = session().ev(CabinTemp(T0, 30.0)).wait2()
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.FOCUS_DENIED))
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertEquals(2, sim.ses.attemptNo)
        assertEquals(TryResult.FAIL, sim.ep.first!!.result)
        assertNull(sim.ep.second)
        assertTrue(sim.all<LowerHvac>().isEmpty())
        assertTrue(Sid.S03 !in sim.sids() && Sid.S04 !in sim.sids())
    }

    @Test fun r42f_startErrorAtAttempt2_waitNoActions() {
        val sim = session().wait2()
        val id = sim.play().id
        sim.ev(PlaybackRejected(sim.t, id, RejectReason.ERROR))
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertNull(sim.ses.play)
        assertTrue(sim.all<SavePlay>().none { it.record.id == id })
        assertTrue(sim.step<Speak>().isEmpty() && sim.step<Ask>().isEmpty())
        assertEquals(TryResult.FAIL, sim.ep.first!!.result)
    }

    @Test fun r42e_secondTry_notInExperiment() {
        val sim = session().attempt2().toConfirm().closeQuiet()
        assertEquals(TryResult.SUCCESS, sim.step<SaveEpisode>().single().record.secondResult)
        assertEquals(listOf(WN to false), sim.s.history.map { it.method to it.success })
    }
}
