package kr.aigyeot.app.host

import kotlinx.coroutines.cancel
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.Heard
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.RouteState
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.Tick
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 인계 줄을 다시 고른 뒤의 복원을 호스트를 거쳐 본다.
@RunWith(RobolectricTestRunner::class)
class HandoffHostTest {
    private val k = Kit()

    @After fun close() { k.host.close(); k.linkScope.cancel(); k.db.close() }

    private fun sql(q: String) = k.db.openHelper.writableDatabase.execSQL(q)
    private fun col(id: String, c: String): String? = k.db.openHelper.readableDatabase.query("SELECT $c FROM episode WHERE id = '$id'")
        .use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }
    private fun alerts() = k.link.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
    private fun recry(): Kit = k.ev(CrySegment(0, k.now + 1_000, k.now + 4_000, 0.9, false), k.now + 4_000)
    private fun speaking() = k.state().speech.speaking!!

    /** 쉼터 없는 경로에서 인계(S10) → S02를 끝내고 쉼터가 생겨 꺼낼 때 S08로 바꾼 뒤 그 질문을 낸 상태. */
    private fun askedAfterSwap(): String {
        k.phone("guardian", alerts = true); k.profile(); k.boot(); k.open()
        k.ev(RouteState(0, false, null, emptyList()))
        k.alarm(); k.toGap(); recry(); k.toGap(); recry()
        assertEquals(Phase.HANDOFF, k.ses().phase)
        assertEquals(Sid.S10, k.state().speech.queue.single { it.handoff }.sid)
        k.ev(RouteState(0, false, null, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 8.0, false))), k.now + 1_000)
        k.ev(SpeechDone(0, speaking(), true), k.now + 1_000)            // 앞 발화(S02)가 끝남: 꺼낼 때 S08로 바꾼다
        k.state().speech.qa?.let { k.ev(Heard(0, it.listenId, null)) }   // S02 뒤 듣기 창은 들은 말 없이 닫는다
        assertEquals(Sid.S08, k.state().speech.queue.single { it.handoff }.sid)
        k.ev(Tick(0), k.now + Params().speechGapMs)
        val ask = k.exec.filterIsInstance<Ask>().single()
        assertEquals(Sid.S08, ask.sid); assertEquals("n 휴게소", ask.vars["restName"])
        return ask.askId
    }

    @Test fun swappedLine_restoreRoomToldWins_noReaskNoSecondAlert() {
        val u = askedAfterSwap()
        val before = k.state()   // 질문을 낸 뒤의 스냅숏(장부에 발화 id)
        val a = before.openLedger()!!
        val line = before.speech.queue.first()
        assertTrue(line.handoff, "호스트는 표시로 바꾼 줄을 찾는다")
        val epId = a.episodeId!!
        k.ev(SpeechDone(0, u, true), k.now + 2_000)   // Room에 told=1·알림을 남긴 뒤
        assertEquals(1, alerts().size); assertEquals("1", col(epId, "handoff_told_driver"))
        k.store.saveSnapshot(before)                   // 스냅숏을 저장하기 전에 죽었다
        k.running = null; k.exec.clear(); k.link.clear()
        k.boot()
        assertNull(k.state().openLedger())
        assertTrue(k.state().speech.queue.none { it.handoff || it.sid == Sid.S08 }, "바꾼 줄을 지운다")
        k.ev(Tick(0), k.now + 20_000)
        assertTrue(k.exec.none { it is Ask }, "다시 묻지 않는다"); assertTrue(alerts().isEmpty(), "알림을 다시 보내지 않는다")
        assertEquals("1", col(epId, "handoff_told_driver")); assertEquals("WAYPOINT_OFFER", col(epId, "handoff_kind"))
    }

    // 인계가 넘겨받은 장시간 S10 줄(표시 있음)은 복원 뒤에도 남아 말해지고 알림·told가 맞는다
    @Test fun handoffTakenLongDriveLine_survivesRestore() {
        k.phone("guardian", alerts = true); k.profile(); k.boot(); k.open()
        k.ev(RouteState(0, false, null, emptyList()))
        k.alarm(); k.toGap(); recry(); k.toGap(); recry()
        val s = k.state()
        val line = s.speech.queue.single { it.handoff }
        assertEquals(Sid.S10, line.sid)
        val epId = s.openLedger()!!.episodeId!!
        k.store.saveSnapshot(s.copy(speech = s.speech.copy(speaking = null, queue = listOf(line.copy(longDrive = true)))))
        k.running = null; k.exec.clear(); k.link.clear()
        k.boot()
        assertTrue(k.state().speech.queue.any { it.handoff && it.longDrive }, "복원이 인계 줄을 지우지 않는다")
        assertNotNull(k.state().openLedger())
        k.ev(Tick(0), k.now + 70_000)
        val sp = k.exec.filterIsInstance<Speak>().single { it.sid == Sid.S10 }
        k.ev(SpeechDone(0, sp.utteranceId, true), k.now + 2_000)
        assertEquals(1, alerts().size); assertEquals("1", col(epId, "handoff_told_driver"))
    }
}
