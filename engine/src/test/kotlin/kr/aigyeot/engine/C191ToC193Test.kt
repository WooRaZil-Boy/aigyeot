package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 명세서 17장 C191·C193.
class C191ToC193Test {
    private val h = S57HandoffTest()
    private fun Sim.alertsOf() = all<SendToPhone>().filter { it.type == "handoff_alert" }
    private fun speakingOffer(): Sim = h.speakingOffer().first

    // ---- C191 코어 함수: 말하는 중인 인계 줄을 끊으면 이름 없는 알림·false, 반복 금지 지움 ----
    @Test fun c191_cancelForScene_plainAlertAndToldFalse() {   // D6: cancelForScene → sceneStart의 실제 상태 Step(수명 표 SCENE_START)
        val sim = speakingOffer()
        val step = Engine().sceneStart(sim.s, sim.s, sim.t + 100).realStep
        assertTrue(CancelSpeech in step.commands)
        assertEquals(1, step.commands.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" })
        assertEquals("아기가 계속 울어요.", step.commands.filterIsInstance<SendToPhone>().single().body["text"].toString().trim('"'))
        assertEquals(null, step.state.speech.speaking); assertTrue(step.state.speech.queue.isEmpty())
        assertEquals(false, step.commands.filterIsInstance<SettleHandoff>().last().toldDriver)
        assertEquals(null, step.state.openLedger())
    }

    // ---- C193: 알림을 낼 때 보호자가 이미 없으면 보내지 않는다(guardian_notified는 link가 넣을 때만 참, 결정 ③) ----
    @Test fun c193_guardianGoneBeforeSend_notNotified() {
        val sim = speakingOffer()
        sim.ev(LinkChanged(sim.t + 500, 0))   // 인계 발화 중 마지막 보호자 해제
        sim.ev(SpeechDone(sim.t + 1_000, "u1", true))
        assertTrue(sim.alertsOf().isEmpty(), "받는 사람이 없으면 알림을 내지 않는다")
        assertEquals(true, sim.told())
    }

    @Test fun c193_guardianGoneBeforeSend_flushPlain_notNotified() {
        val sim = speakingOffer()
        sim.ev(LinkChanged(sim.t + 500, 0))
        sim.ev(SpeechDone(sim.t + 1_000, "u1", false))   // 말하지 못함: 이름 없는 알림 길
        assertTrue(sim.alertsOf().isEmpty())
        assertEquals(false, sim.told())
    }
}
