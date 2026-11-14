package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 2차 시도.
class SecondAttemptTest {
    /** 1차 실패 뒤 ATTEMPT(2) 시작 대기. */
    private fun Sim.wait2() = attempt1().toGap().recry()

    @Test fun hot_lowersHvacReplaysFirstNoS04Yet() {
        val sim = session().ev(CabinTemp(T0, 27.6)).wait2()
        assertEquals(WN, sim.step<StartPlayback>().single().method) // 1차 음원을 다시
        sim.started()
        assertEquals(SecondAction.COMFORT, sim.ep.second!!.action)
        assertTrue(sim.ses.comfortDone)
        val hv = sim.step<LowerHvac>().single()
        assertEquals(listOf(Area.ROW_1_LEFT), hv.areas)
        assertEquals(-2.0, hv.deltaC)
        assertTrue(Sid.S04 !in sim.sids()) // 결과가 오기 전에는 말하지 않는다
        sim.flush()
        assertTrue(Sid.S04 !in sim.sids())
        assertEquals(ComfortPending(hv.cmdId, 28.0, sim.play().id), sim.ses.comfortPending)
    }

    private fun hvac(sim: Sim, ok: Boolean, before: Double?, after: Double?): Sim {
        val id = sim.ses.comfortPending!!.cmdId
        return sim.ev(ControlResult(sim.t, id, ControlKind.HVAC_TEMP, Area.ROW_1_LEFT, ok, before, after))
    }

    @Test fun hvacLowered_saysS04ActualDelta() {
        val sim = session().ev(CabinTemp(T0, 27.6)).wait2().started()
        hvac(sim, true, 22.0, 20.5)
        assertNull(sim.ses.comfortPending)
        assertEquals(sim.play().id, sim.s.speech.queue.single { it.sid == Sid.S04 }.playId) // 그 재생
        sim.flush()
        val s04 = sim.all<Speak>().single { it.sid == Sid.S04 }
        assertEquals(mapOf("tempC" to "28", "loweredC" to "1.5"), s04.vars) // before − after
    }

    @Test fun hvacFailedOrPlayEnded_noS04() {
        val fail = session().ev(CabinTemp(T0, 27.6)).wait2().started()
        hvac(fail, false, 16.0, 16.0)
        assertNull(fail.ses.comfortPending)
        fail.flush()
        assertTrue(Sid.S04 !in fail.sids())

        val ended = session().ev(CabinTemp(T0, 27.6)).wait2().started()
        ended.ev(Tick(ended.play().endAt)) // 그 재생이 끝남
        hvac(ended, true, 22.0, 20.0)
        assertNull(ended.ses.comfortPending)
        ended.flush()
        assertTrue(Sid.S04 !in ended.sids())
    }

    @Test fun hvacNotLowered_noS04() {
        for ((b, a) in listOf(22.0 to 22.0, 20.0 to 22.0, null to 20.0, 22.0 to null)) {
            val sim = session().ev(CabinTemp(T0, 27.6)).wait2().started()
            hvac(sim, true, b, a)
            assertNull(sim.ses.comfortPending)
            sim.flush()
            assertTrue(Sid.S04 !in sim.sids(), "$b→$a")
        }
    }

    @Test fun bootClearsComfortPending() {
        val sim = session().ev(CabinTemp(T0, 27.6)).wait2().started()
        assertTrue(sim.ses.comfortPending != null)
        sim.ev(Resume(sim.t + 1_000))
        assertTrue(sim.ses.comfortPending != null) // Resume은 지우지 않는다
        sim.ev(Boot(sim.t + 1_000, Gear.D, 10))
        assertNull(sim.ses.comfortPending)
        assertTrue(sim.ses.comfortDone) // comfortDone은 그대로
    }

    @Test fun feeding_otherBestS03S06Offer() {
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
        assertEquals(1, b.sids().count { it == Sid.S03 })
        assertTrue(Sid.S06 !in b.sids())
    }

    @Test fun else_otherBestSaysS03() {
        val sim = session().wait2()
        assertEquals(LL, sim.step<StartPlayback>().single().method)
        sim.started()
        assertEquals(SecondAction.OTHER, sim.ep.second!!.action)
        assertEquals(listOf(Sid.S02, Sid.S03), sim.sids())
        sim.flush()
        assertEquals(mapOf("method" to "자장가"), sim.all<Speak>().first { it.sid == Sid.S03 }.vars)
    }

    @Test fun focusDeniedAtAttempt2_waitNoActions() {
        val sim = session().ev(CabinTemp(T0, 30.0)).wait2()
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.FOCUS_DENIED))
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertEquals(2, sim.ses.attemptNo)
        assertEquals(TryResult.FAIL, sim.ep.first!!.result)
        assertNull(sim.ep.second)
        assertTrue(sim.all<LowerHvac>().isEmpty())
        assertTrue(Sid.S03 !in sim.sids() && Sid.S04 !in sim.sids())
    }

    @Test fun startErrorAtAttempt2_waitNoActions() {
        val sim = session().wait2()
        val id = sim.play().id
        sim.ev(PlaybackRejected(sim.t, id, RejectReason.ERROR))
        assertEquals(Phase.WAIT, sim.ses.phase)
        assertNull(sim.ses.play)
        assertTrue(sim.all<SavePlay>().none { it.record.id == id })
        assertTrue(sim.step<Speak>().isEmpty() && sim.step<Ask>().isEmpty())
        assertEquals(TryResult.FAIL, sim.ep.first!!.result)
    }

    @Test fun secondTry_notInExperiment() {
        val sim = session().attempt2().toConfirm().closeQuiet()
        assertEquals(TryResult.SUCCESS, sim.step<SaveEpisode>().single().record.secondResult)
        assertEquals(listOf(WN to false), sim.s.history.map { it.method to it.success })
    }
}
