package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 명세서 17장 C141. 다른 세션이 넣은 줄이 끝나거나 복원에서 버려져도 지금 세션 상태는 바뀌지 않는다.
class C141SessionLineTest {
    /** 세션 A가 말하는 중(u1, 큐 맨 앞)인 줄을 두고 P에서 뒷문을 열어 닫은 뒤 D로 세션 B를 연다. */
    private fun bOpened(line: Line, keepNull: Boolean = false): Pair<Sim, String> {
        val sim = session()
        val a = sim.ses.id
        val l = if (keepNull) line else line.copy(sessionId = a)
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(l)))
        sim.gear(Gear.P).ev(RearDoor(sim.t, Side.LEFT, true)).ev(RearDoor(sim.t, Side.LEFT, false)).gear(Gear.D)
        assertNotEquals(a, sim.ses.id)
        assertEquals("u1", sim.s.speech.speaking, "C140: 말하는 맨 앞 줄은 남는다")
        return sim to a
    }
    private val s10 = Line(Sid.S10, emptyMap(), null, longDrive = true)

    @Test fun c141_dropSpeaking_otherSessionLine_keepsLongDriveOffered() {
        val (sim, _) = bOpened(s10)
        val st = Engine().dropSpeaking(sim.s, sim.t + 1_000)
        assertFalse(st.session!!.longDriveOffered)
        assertTrue(st.session!!.spokenAt.isEmpty())
    }

    @Test fun c141_lateSpeechDone_otherSessionLine_keepsLongDriveOffered() {
        val (sim, _) = bOpened(s10)
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertFalse(sim.ses.longDriveOffered)
        assertTrue(sim.ses.spokenAt.isEmpty())
    }

    /** 세션 B에서 에피소드를 실제로 연 상태(A의 S06이 u1로 말하는 중). */
    private fun bEpisode(): Sim {
        val (sim, _) = bOpened(Line(Sid.S06, emptyMap(), null))
        sim.alarm().started()
        assertNotNull(sim.ses.episode)
        return sim
    }

    @Test fun c141_dropSpeaking_otherSessionS06_keepsS06Said() {
        val sim = bEpisode()
        val st = Engine().dropSpeaking(sim.s, sim.t + 1_000)
        assertNotNull(st.session!!.episode)
        assertFalse(st.session!!.episode!!.s06Said)
    }

    @Test fun c141_lateSpeechDone_otherSessionS06_keepsS06Said() {
        val sim = bEpisode()
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertNotNull(sim.ses.episode)
        assertFalse(sim.ep.s06Said)
    }

    @Test fun c141_sameSession_speechDoneLongDriveOfferedStands_dropSpeakingNot() {   // C151: 복원이 버리면 말하지 않은 것
        // (C151) dropSpeaking은 같은 세션 줄도 말한 것으로 치지 않는다
        val sim = session()
        sim.s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1", queue = listOf(s10.copy(sessionId = sim.ses.id))))
        assertFalse(Engine().dropSpeaking(sim.s, sim.t + 1_000).session!!.longDriveOffered)
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertTrue(sim.ses.longDriveOffered)
    }

    @Test fun c141_oldSnapshotNullSessionId_lateSpeechDoneAppliesAsBefore() {   // C151: dropSpeaking은 말한 것으로 치지 않는다
        val (sim, _) = bOpened(s10, keepNull = true)
        assertFalse(Engine().dropSpeaking(sim.s, sim.t + 1_000).session!!.longDriveOffered)
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertTrue(sim.ses.longDriveOffered)
    }

    @Test fun c141_say_stampsCurrentSessionId() {
        val sim = session().attempt1()
        val lines = sim.s.speech.queue
        assertTrue(lines.isNotEmpty() && lines.all { it.sessionId == sim.ses.id })
    }
}
