package kr.aigyeot.engine

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 설계 3.9 결정 ②(D3, V5): 10분 금지는 '말한 지 10분 안 또는 큐에 같은 문장'을 offerBanned로 계산한다.
class D3OfferBanTest {
    private fun Sim.alerts() = all<SendToPhone>().filter { it.type == "handoff_alert" }.map { it.body }

    /** V5 재현: 수유 시간대 S07이 큐에 든 채 기어 P로 버려지고, 10분 안에 인계가 오면 그 인계는 쉼터 안내(S07)를 낸다. */
    @Test fun d3_droppedOfferLeavesNoBan() {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("n", 14.0, nursing = true)).alarm().started()
        val queuedAt = sim.s.speech.queue.single { it.sid == Sid.S07 }.queuedAt!!
        sim.done().gear(Gear.P, 1_000)                                     // S02 뒤 갓길에 P(앞좌석, 뒷문 안 엶)
        assertTrue(sim.s.speech.queue.none { it.sid == Sid.S07 }, "전제: 말하지 않은 S07을 P로 버렸다")
        assertTrue(Sid.S07 !in sim.all<Ask>().map { it.sid })
        sim.gear(Gear.D, 1_000).tick(P.resumeQuietMs)                       // 다시 D, 5분 조용해 에피소드 닫힘
        assertNull(sim.ses.episode)
        sim.handoff()
        assertTrue(sim.t - queuedAt < P.offerRepeatMs, "전제: 버린 S07을 넣은 지 10분 안의 인계")
        sim.flush()
        assertEquals(listOf("n 휴게소"), sim.all<Ask>().filter { it.sid == Sid.S07 }.map { it.vars["restName"] }, "듣지 못한 제안이 막지 않는다")
        assertEquals("n 휴게소", sim.alerts().single()["rest_name"]!!.jsonPrimitive.content)
        assertEquals(true, sim.told())
    }

    /** 같은 제안 문장이 이 세션 큐(말하는 맨 앞 줄 포함)에 있으면 막힘. 다시 쓰는 줄(except)·다른 세션 줄은 막지 않는다. S07과 S07_DEPART는 묶는다. */
    @Test fun d3_queuedOfferBlocksSame() {
        val a = Line(Sid.S07, emptyMap(), "a", queuedAt = T0, sessionId = "s")
        assertTrue(offerBanned(emptyMap(), listOf(a), "s", Sid.S07, T0))
        assertFalse(offerBanned(emptyMap(), listOf(a), "s", Sid.S07, T0, except = listOf(a)), "다시 쓰는 줄은 막지 않는다")
        assertFalse(offerBanned(emptyMap(), listOf(a), "other", Sid.S07, T0), "다른 세션 줄은 막지 않는다")
        assertFalse(offerBanned(emptyMap(), listOf(a), "s", Sid.S08, T0))
        assertTrue(offerBanned(emptyMap(), listOf(a.copy(sid = Sid.S07_DEPART)), "s", Sid.S07, T0), "S3a: 출발 제안이 큐에 있으면 S07도 막힘")
        // 판단 코어: 말하는 S07(a, 아직 말한 시각 없음)이 있으면 수유 시간대 제안이 S07(b)을 넣지 않는다
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 150 * MIN).route(rest("b", 14.0, nursing = true))
        sim.s = sim.s.copy(speech = SpeechQueue("uA", sim.t, listOf(a.copy(sessionId = sim.ses.id))))
        assertTrue(sim.ses.spokenAt.isEmpty())
        sim.alarm().started()
        assertTrue(sim.s.speech.queue.none { it.askRestId == "b" }, "큐에 같은 문장이 있어 S07(b)을 넣지 않는다: ${sim.s.speech.queue}")
    }
}
