package kr.aigyeot.app.exec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Pleos SDK 콜백에는 회차 id가 없어 앞 회차의 늦은 끝 신호가 새 회차의 결과가 되면 안 된다(TtsGate·SttGate)
class GatesTest {
    private val t = FakeTime()
    private val said = mutableListOf<String>()
    private var stops = 0
    private var focusHeld = 0
    private var focusOk = true
    private val g = TtsGate({ said += it }, { stops++ }, { if (focusOk) { focusHeld++; true } else false }, { focusHeld-- }, t)
    private var doneA: Boolean? = null
    private var doneB: Boolean? = null
    private fun ready() { g.warm(); g.settled(true) }

    @Test fun `A를 끊고 B를 말하면 A의 늦은 끝 신호 뒤에 B가 시작되고 B의 끝 신호로 B가 끝난다`() {
        ready()
        g.speak("A") { doneA = it }
        assertEquals(listOf("", "A"), said)
        g.stop()
        assertEquals(1, stops)
        g.speak("B") { doneB = it }
        assertEquals(listOf("", "A"), said, "A의 요청이 아직 걸려 있어 B를 미룬다")
        g.settled(true)                                  // A의 늦은 onDone
        assertEquals(listOf("", "A", "B"), said, "A의 끝 신호 뒤에 B 시작")
        assertNull(doneB, "A의 끝 신호가 B를 끝내면 안 된다")
        assertNull(doneA, "끊은 A에는 알리지 않는다")
        g.settled(true)
        assertEquals(true, doneB)
        assertEquals(0, focusHeld)
    }

    @Test fun `끝 신호가 아예 없으면 1초 뒤 B가 시작된다`() {
        ready()
        g.speak("A") { }
        g.stop()
        g.speak("B") { doneB = it }
        t.advance(TtsGate.SETTLE_MS - 1)
        assertEquals(listOf("", "A"), said)
        t.advance(1)
        assertEquals(listOf("", "A", "B"), said)
        g.settled(true)
        assertEquals(true, doneB)
    }

    @Test fun `예열의 늦은 끝 신호도 첫 문장을 끝내지 못한다`() {
        g.warm()
        g.speak("A") { doneA = it }
        assertEquals(listOf(""), said, "예열 중에는 미룬다")
        g.settled(true)                                  // 예열 onDone
        assertEquals(listOf("", "A"), said)
        assertNull(doneA)
        g.settled(true)
        assertEquals(true, doneA)
    }

    @Test fun `예열 끝 신호가 없으면 3초 뒤 풀린다`() {
        g.warm()
        g.speak("A") { }
        t.advance(TtsGate.WARM_MS - 1)
        assertEquals(listOf(""), said)
        t.advance(1)
        assertEquals(listOf("", "A"), said)
    }

    @Test fun `초기화 전 문장은 미루고 실패하면 못 말한 것으로 끝낸다`() {
        g.speak("A") { doneA = it }
        assertTrue(said.isEmpty())
        g.initFailed()
        assertEquals(false, doneA)
        g.speak("B") { doneB = it }
        assertEquals(false, doneB)
    }

    @Test fun `포커스를 못 얻으면 못 말한 것이고 오류 신호도 끝낸다`() {
        ready()
        focusOk = false
        g.speak("A") { doneA = it }
        assertEquals(false, doneA)
        focusOk = true
        g.speak("B") { doneB = it }
        g.settled(false)
        assertEquals(false, doneB)
        assertEquals(0, focusHeld)
    }

    @Test fun `포커스를 잃으면 걸린 문장을 멈추고 실패로 끝내며 포커스를 반납한다`() {
        ready()
        g.speak("A") { doneA = it }
        g.focusLost()
        assertEquals(false, doneA)
        assertEquals(1, stops, "SDK 멈춤")
        assertEquals(0, focusHeld, "포커스 반납")
    }

    @Test fun `예열 중 포커스 상실은 무시한다`() {
        g.warm()
        g.focusLost()
        assertEquals(0, stops)
        g.speak("A") { doneA = it }
        g.settled(true)                                  // 예열 onDone
        assertEquals(listOf("", "A"), said)
        assertNull(doneA)
    }

    @Test fun `상실 뒤 큐 문장은 끝 신호 뒤에 이어서 시작하고 늦은 onStop이 그 문장을 끝내지 않는다`() {
        ready()
        g.speak("A") { doneA = it }
        g.focusLost()
        g.speak("B") { doneB = it }
        assertEquals(listOf("", "A"), said, "A의 끝 신호를 기다리며 미룬다")
        g.settled(false)                                 // A의 늦은 onStop
        assertEquals(listOf("", "A", "B"), said)
        assertNull(doneB, "onStop이 B를 끝내면 안 된다")
        g.settled(true)
        assertEquals(true, doneB)
        assertEquals(0, focusHeld)
    }

    @Test fun `상실 전에 큐에 있던 문장은 지우지 않고 상실 뒤 이어서 말한다`() {
        ready()
        g.speak("A") { doneA = it }
        g.speak("B") { doneB = it }                      // A가 걸려 있어 큐
        g.focusLost()
        assertEquals(false, doneA)
        t.advance(TtsGate.SETTLE_MS)
        assertEquals(listOf("", "A", "B"), said)
    }

    @Test fun `STT는 PCM을 넣기 전에 온 결과를 버리고 넣은 뒤 결과는 전달한다`() {
        val got = mutableListOf<Pair<String, Boolean>>()
        val sent = mutableListOf<Int>(); var completes = 0; var stops = 0
        val s = SttGate({ sent += it.size }, { completes++ }, { stops++ })
        s.begin { x, f -> got += x to f }
        s.result("앞 묶음의 글", true)
        s.error()
        assertTrue(got.isEmpty(), "begin 뒤 feed 전 결과는 앞 묶음 것: $got")
        s.feed(byteArrayOf(1, 2))
        s.result("응", false)
        s.error()
        assertEquals(listOf("응" to false, "" to true), got)
        s.finish()
        assertEquals(1, completes); assertEquals(listOf(2), sent)
        s.begin { x, f -> got += "새" + x to f }          // 새 묶음
        s.result("늦은 결과", true)
        assertEquals(2, got.size, "새 묶음도 feed 전에는 버린다")
        s.cancel()
        s.feed(byteArrayOf(1)); s.result("취소 뒤", true)
        assertEquals(2, got.size); assertEquals(1, stops)
    }
}
