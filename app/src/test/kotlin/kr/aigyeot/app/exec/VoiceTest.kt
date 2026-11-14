package kr.aigyeot.app.exec

import kr.aigyeot.engine.Answer
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Heard
import kr.aigyeot.engine.Listen
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.YesNo
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Speak·Ask·Listen·CancelSpeech, SpeechDone·Answer, Heard. 듣기 창 끝 + 1초 안에 Heard
class VoiceTest {
    private class FakeTts : TtsPort {
        val said = mutableListOf<String>(); var done: ((Boolean) -> Unit)? = null; var stops = 0
        override fun speak(text: String, done: (Boolean) -> Unit) { said += text; this.done = done }
        override fun stop() { stops++ }
    }
    private class FakeStt : SttPort {
        var throwBegin = false
        var onText: ((String, Boolean) -> Unit)? = null; val fed = mutableListOf<ByteArray>(); var finished = 0; var cancels = 0
        override fun begin(onText: (String, Boolean) -> Unit) { if (throwBegin) error("stt 실패"); this.onText = onText }
        override fun feed(pcm: ByteArray) { fed += pcm }
        override fun finish() { finished++ }
        override fun cancel() { cancels++ }
    }
    private class FakeTap : MicTap {
        var sink: ((ByteArray) -> Unit)? = null
        override fun open(sink: (ByteArray) -> Unit) { this.sink = sink }
        override fun close() { sink = null }
    }

    private val t = FakeTime()
    private val r = Rec()
    private val tts = FakeTts(); private val stt = FakeStt(); private val tap = FakeTap()
    private var mic = true
    private val v = Voice(tts, stt, tap, t, { t.now }, { r.events += it }, { mic })

    @Test fun `Speak은 문장을 채워 말하고 끝나면 SpeechDone`() {
        v.speak(Speak("s1-3", Sid.S02, mapOf("method" to "자장가")))
        assertEquals("뒤에서 울어요. 뒷자리에 자장가 틀었어요.", tts.said.single())
        assertTrue(r.events.isEmpty())
        t.advance(2_000); tts.done!!(true)
        assertEquals(listOf<Event>(SpeechDone(t.now, "s1-3", true)), r.events)
    }

    // said: 말하기를 시작하면 화면에 보이고(질문 답이 말하는 동안 카드에), 못 말하고 끝나면 앞 문장으로 되돌린다
    @Test fun `said는 말하기 시작에 보이고 못 말하면 거둔다`() {
        v.speak(Speak("s1-1", Sid.S02, mapOf("method" to "자장가")))
        val first = v.said.value!!
        assertEquals(Sid.S02, first.sid); assertEquals(t.now, first.at)
        tts.done!!(true)
        t.advance(1_000); v.speak(Speak("s1-2", Sid.QA, mapOf("text" to "두 시간 전에 먹였어요.")))
        assertEquals(Sid.QA, v.said.value!!.sid, "끝나기 전, 말하는 동안 보인다")
        tts.done!!(false)
        assertEquals(first, v.said.value, "못 말한 문장은 거두고 앞 문장으로")
        // 통화로 끊으면(cancelIfSpeaking → 완료 콜백을 버림) 그 자리에서 거둔다
        v.speak(Speak("s1-4", Sid.S10, emptyMap()))
        assertEquals(Sid.S10, v.said.value!!.sid)
        assertNull(v.cancelIfSpeaking("s1-4"))
        assertEquals(first, v.said.value, "끊긴 문장은 화면에 남지 않는다")
    }

    @Test fun `TTS가 못 말하면 spoken=false`() {
        v.speak(Speak("s1-3", Sid.S10, emptyMap()))
        tts.done!!(false)
        assertEquals(SpeechDone(t.now, "s1-3", false), r.events.single())
    }

    @Test fun `cancelIfSpeaking은 말하는 중인 그 발화만 끊고 끝났으면 그 spoken을 돌려준다`() {
        v.speak(Speak("s1-3", Sid.S10, emptyMap()))
        assertNull(v.cancelIfSpeaking("다른")); assertEquals(0, tts.stops)
        assertNull(v.cancelIfSpeaking("s1-3")); assertEquals(1, tts.stops)
        tts.done!!(true)
        assertTrue(r.events.isEmpty(), "끊은 발화의 늦은 끝 신호는 버린다")
        v.speak(Speak("s1-4", Sid.S10, emptyMap())); tts.done!!(true); val at4 = t.now
        t.now += 3_000   // 묻는 시각이 늦어도 실제 완료 시각을 돌려준다
        assertEquals(true to at4, v.cancelIfSpeaking("s1-4"), "이미 끝났다: spoken=true")
        v.speak(Speak("s1-5", Sid.S10, emptyMap())); tts.done!!(false); val at5 = t.now
        assertEquals(false to at5, v.cancelIfSpeaking("s1-5"), "이미 끝났다: spoken=false")
        assertEquals(listOf<Event>(SpeechDone(at4, "s1-4", true), SpeechDone(at5, "s1-5", false)), r.events)
    }

    @Test fun `끝 신호가 오지 않으면 20초 뒤 spoken=false로 끝내 큐가 막히지 않는다`() {
        v.speak(Speak("s1-3", Sid.S10, emptyMap()))
        t.advance(Voice.SPEECH_TIMEOUT_MS)
        assertEquals(SpeechDone(t.now, "s1-3", false), r.events.single())
        assertEquals(1, tts.stops)
        tts.done!!(true)
        assertEquals(1, r.events.size, "늦게 온 끝 신호는 버린다")
    }

    @Test fun `Ask는 말한 뒤 6초 듣고 응이면 YES`() {
        v.ask(Ask("s1-4", Sid.S07, mapOf("distanceKm" to "7", "restName" to "안성휴게소"), 6_000))
        assertEquals("7킬로미터 앞 안성휴게소에 수유실이 있어요. 들를까요?", tts.said.single())
        tts.done!!(true)
        assertEquals(SpeechDone(t.now, "s1-4", true), r.events.single())
        tap.sink!!(byteArrayOf(1, 2))
        assertEquals(1, stt.fed.size, "마이크 서비스 PCM을 STT에 넣는다")
        t.advance(2_000); stt.onText!!("응", false)
        t.advance(4_000)
        assertNull(tap.sink, "6초가 지나면 PCM을 그만 받는다"); assertEquals(1, stt.finished)
        stt.onText!!("응 들러", true)
        t.advance(Voice.STT_FINAL_GRACE_MS)                // 유예가 끝날 때 낸다
        assertEquals(Answer(t.now, "s1-4", YesNo.YES), r.events.last())
    }

    @Test fun `Ask에 최종 결과가 늦으면 듣기 창 끝 800ms 뒤 마지막 글로 답한다`() {
        v.ask(Ask("s1-4", Sid.S08, mapOf("distanceKm" to "7", "restName" to "망향휴게소"), 6_000))
        tts.done!!(true)
        stt.onText!!("아니요", false)
        t.advance(6_000 + Voice.STT_FINAL_GRACE_MS)
        assertEquals(Answer(t.now, "s1-4", YesNo.NO), r.events.last())
    }

    @Test fun `Ask에 아무 말도 없으면 NONE`() {
        v.ask(Ask("s1-4", Sid.S08, emptyMap(), 6_000))
        tts.done!!(true)
        t.advance(10_000)
        assertEquals(Answer(1_000_000L + 6_000 + Voice.STT_FINAL_GRACE_MS, "s1-4", YesNo.NONE), r.events.last())
        assertEquals(2, r.events.size, "답은 한 번만")
    }

    @Test fun `마이크 서비스가 없으면 Ask는 말한 뒤 바로 NONE`() {
        mic = false
        v.ask(Ask("s1-4", Sid.S08, emptyMap(), 6_000))
        tts.done!!(true)
        assertEquals(listOf<Event>(SpeechDone(t.now, "s1-4", true), Answer(t.now, "s1-4", YesNo.NONE)), r.events)
        assertNull(tap.sink)
    }

    @Test fun `Ask를 말하지 못하면 듣지 않고 NONE`() {
        v.ask(Ask("s1-4", Sid.S08, emptyMap(), 6_000))
        tts.done!!(false)
        assertEquals(YesNo.NONE, r.of<Answer>().single().answer)
        assertNull(tap.sink)
    }

    @Test fun `Listen은 5초 들은 글을 Heard로 듣기 창 끝 + 1초 안에 넘긴다`() {
        v.listen(Listen("s1-5", 5_000))
        stt.onText!!("다음 쉼터까지 얼마나 남았어", false)
        t.advance(5_000 + Voice.STT_FINAL_GRACE_MS)
        val h = r.of<Heard>().single()
        assertEquals("다음 쉼터까지 얼마나 남았어", h.text)
        assertTrue(h.at < 1_000_000L + 5_000 + 1_000, "판단 코어 듣기 창(+1초) 안")
    }

    @Test fun `Listen에서 공백을 뺀 2자 미만은 들은 말 없음`() {
        v.listen(Listen("s1-5", 5_000))
        stt.onText!!(" 어 ", true)
        t.advance(6_000)
        assertEquals(Heard(1_000_000L + 5_000 + Voice.STT_FINAL_GRACE_MS, "s1-5", null), r.events.single())
    }

    @Test fun `마이크 서비스가 없으면 Listen은 바로 Heard(null)`() {
        mic = false
        v.listen(Listen("s1-5", 5_000))
        assertEquals(Heard(t.now, "s1-5", null), r.events.single())
    }

    @Test fun `CancelSpeech는 말하기와 듣기를 끊고 사건을 내지 않는다`() {
        v.ask(Ask("s1-4", Sid.S08, emptyMap(), 6_000))
        tts.done!!(true)
        r.events.clear()
        v.cancel()
        assertNull(tap.sink); assertEquals(1, stt.cancels)
        t.advance(10_000); stt.onText?.invoke("응", true)
        assertTrue(r.events.isEmpty(), "끊은 듣기의 답을 내지 않는다")
        v.speak(Speak("s1-6", Sid.S12, emptyMap()))
        v.cancel()
        assertEquals(1, tts.stops)
        tts.done!!(true)
        assertTrue(r.events.isEmpty(), "끊은 문장의 SpeechDone을 내지 않는다")
    }

    @Test fun `응 아니 말 고르기`() {
        val yes = listOf("응", "응.", "네 들러 주세요", "예", "그래", "좋아요", "들러", "넣어 줘", "오케이", "응 들러", "그래 넣어줘", "좋지", "콜")
        val no = listOf("아니", "아니요", "아뇨", "싫어", "됐어", "괜찮아요", "안 들러", "안 돼")
        val none = listOf(null, "", "음", "어", "어?", "어디야", "뭐라고?", "언제 도착해")
        yes.forEach { assertEquals(YesNo.YES, Voice.yesNo(it), it) }
        no.forEach { assertEquals(YesNo.NO, Voice.yesNo(it), it) }
        none.forEach { assertEquals(YesNo.NONE, Voice.yesNo(it), "$it") }
    }

    @Test fun `거절 문장은 접두사가 응이어도 YES가 아니다`() {
        listOf("네비에 넣지 마", "그래도 안 갈래", "예정대로 그냥 가", "응 근데 안 들러", "네 말고 다음에").forEach {
            assertTrue(Voice.yesNo(it) != YesNo.YES, "거절이 YES가 됐다: $it")
        }
    }

    @Test fun `최종 결과가 비면 앞의 부분 인식으로 답하지 않는다`() {
        v.ask(Ask("s1-4", Sid.S08, emptyMap(), 6_000))
        tts.done!!(true)
        stt.onText!!("응", false)
        t.advance(6_000)
        assertEquals(1, stt.finished, "조건: 듣기 창이 닫혔다")
        stt.onText!!("", true)                            // 실제 연결부는 오류도 ("", true)로 넘긴다
        t.advance(Voice.STT_FINAL_GRACE_MS)
        assertEquals(Answer(t.now, "s1-4", YesNo.NONE), r.events.last())
        assertEquals(0, stt.cancels, "정상 최종 결과로 끝나면 cancel하지 않는다")
        v.listen(Listen("s1-5", 5_000))
        stt.onText!!("다음 쉼터까지 얼마나 남았어", false)
        t.advance(5_000)
        stt.onText!!("", true)
        t.advance(Voice.STT_FINAL_GRACE_MS)
        assertEquals(Heard(t.now, "s1-5", null), r.events.last())
    }

    @Test fun `한 창의 비지 않은 최종 결과는 온 순서대로 띄어 이어 하나로 낸다`() {
        v.ask(Ask("s1-4", Sid.S08, emptyMap(), 6_000))
        tts.done!!(true)
        stt.onText!!("아니 괜찮아", true)
        stt.onText!!("아", true)
        t.advance(6_000 + Voice.STT_FINAL_GRACE_MS)
        assertEquals(Answer(t.now, "s1-4", YesNo.NO), r.events.last(), "엔진 쪽 판정: '아니 괜찮아 아'는 NO")
        assertEquals(YesNo.NO, Voice.yesNo("아니 괜찮아 아"))
        v.listen(Listen("s1-5", 5_000))
        stt.onText!!("다음 쉼터", true); stt.onText!!("얼마나 남았어", true)
        t.advance(5_000 + Voice.STT_FINAL_GRACE_MS)
        assertEquals(Heard(t.now, "s1-5", "다음 쉼터 얼마나 남았어"), r.events.last())
    }

    @Test fun `창이 닫힌 뒤 유예 중에 최종이 여럿 오면 유예가 끝날 때 이어 한 번에 낸다`() {
        v.ask(Ask("s1-4", Sid.S08, emptyMap(), 6_000))
        tts.done!!(true)
        t.advance(6_000 + 100); stt.onText!!("응", true)
        assertEquals(1, r.events.size, "첫 최종에서 바로 끝내지 않는다(SpeechDone만)")
        t.advance(100); stt.onText!!("아니 괜찮아", true)
        t.advance(Voice.STT_FINAL_GRACE_MS)
        val a = r.events.last() as Answer
        assertEquals(YesNo.NO, a.answer, "'응 아니 괜찮아'는 지금 yesNo 규칙의 결과")
        assertEquals(Voice.yesNo("응 아니 괜찮아"), a.answer)
        assertEquals(2, r.events.size, "답은 한 번만")
    }

    @Test fun `창이 닫힌 뒤 최종 하나만 와도 유예가 끝날 때 나간다`() {
        v.listen(Listen("s1-5", 5_000))
        t.advance(5_000 + 100); stt.onText!!("다음 쉼터", true)
        assertTrue(r.events.isEmpty())
        t.advance(Voice.STT_FINAL_GRACE_MS - 100)
        assertEquals(Heard(t.now, "s1-5", "다음 쉼터"), r.events.last())
        assertEquals(0, stt.cancels, "최종이 있었으니 cancel하지 않는다")
    }

    @Test fun `빈 최종 결과는 앞의 최종 결과를 지우지 않는다`() {
        v.listen(Listen("s1-5", 5_000))
        stt.onText!!("아니 괜찮아", true)
        stt.onText!!("", true)
        stt.onText!!("아", false)                          // 최종이 있으면 뒤의 부분 결과도 쓰지 않는다
        t.advance(5_000 + Voice.STT_FINAL_GRACE_MS)
        assertEquals(Heard(t.now, "s1-5", "아니 괜찮아"), r.events.last())
    }

    @Test fun `최종이 하나도 없으면 마지막 부분 결과를 쓴다`() {
        v.listen(Listen("s1-5", 5_000))
        stt.onText!!("다음", false); stt.onText!!("다음 쉼터", false)
        t.advance(5_000 + Voice.STT_FINAL_GRACE_MS)
        assertEquals(Heard(t.now, "s1-5", "다음 쉼터"), r.events.last())
    }

    @Test fun `최종 결과 없이 끝나면 마지막 부분 인식으로 답하고 묶음을 cancel로 닫는다`() {
        v.ask(Ask("s1-4", Sid.S08, emptyMap(), 6_000))
        tts.done!!(true)
        stt.onText!!("응", false)
        t.advance(6_000 + Voice.STT_FINAL_GRACE_MS)
        assertEquals(Answer(t.now, "s1-4", YesNo.YES), r.events.last())
        assertEquals(1, stt.cancels)
    }

    @Test fun `STT나 마이크가 시작에서 던지면 그 듣기를 빈 결과로 끝낸다`() {
        stt.throwBegin = true
        v.ask(Ask("s1-4", Sid.S08, emptyMap(), 6_000))
        tts.done!!(true)
        assertEquals(Answer(t.now, "s1-4", YesNo.NONE), r.events.last())
        v.listen(Listen("s1-5", 5_000))
        assertEquals(Heard(t.now, "s1-5", null), r.events.last())
        t.advance(30_000)
        assertEquals(3, r.events.size, "SpeechDone, Answer, Heard 뒤 더 없다: ${r.events}")
        assertEquals(0, t.pending())
    }

    @Test fun `문장 표의 조사는 받침에 맞춘다`() {
        val tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        try {
            fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 6, h, m, 0, 0, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli().toString()
            fun dep(ms: String) = Lines.text(Sid.S07_DEPART, mapOf("hungryAt" to ms, "distanceKm" to "30", "restName" to "안성휴게소"))
            assertEquals("오후 3시 10분쯤 배고플 시간이라, 30킬로미터 앞 수유실 있는 안성휴게소를 경유지로 넣을까요?", dep(at(15, 10)))
            assertEquals("오후 3시쯤 배고플 시간이라, 30킬로미터 앞 수유실 있는 안성휴게소를 경유지로 넣을까요?", dep(at(15, 0)))
            assertEquals("오전 12시 5분", Lines.clock(at(0, 5)))
            assertEquals("오후 12시", Lines.clock(at(12, 0))); assertEquals("곧", Lines.clock("곧"))
        } finally { TimeZone.setDefault(tz) }
        assertEquals("아직 울어요. 백색소음으로 바꿨어요.", Lines.text(Sid.S03, mapOf("method" to "백색소음")))
        assertEquals("아직 울어요. 자장가로 바꿨어요.", Lines.text(Sid.S03, mapOf("method" to "자장가")))
        assertEquals("아직 울어요. 우리 노래로 바꿨어요.", Lines.text(Sid.S03, mapOf("method" to "우리 노래")))
        assertEquals("실내가 28도예요. 2도 낮췄어요.", Lines.text(Sid.S04, mapOf("tempC" to "28", "loweredC" to "2")))
        assertEquals("실내가 28도예요. 1.5도 낮췄어요.", Lines.text(Sid.S04, mapOf("tempC" to "28", "loweredC" to "1.5")))
        assertEquals("안성휴게소를 경유지로 넣었어요.", Lines.text(Sid.S09, mapOf("restName" to "안성휴게소")))
        assertEquals("망향 졸음쉼터를 경유지로 넣었어요.", Lines.text(Sid.S09, mapOf("restName" to "망향 졸음쉼터")))
        assertEquals("서울만남의광장을 경유지로 넣었어요.", Lines.text(Sid.S09, mapOf("restName" to "서울만남의광장")))
        assertEquals("1시간 50분째 달리고 있어요. 12킬로미터 앞 안성휴게소에서 쉬어 갈까요?",
            Lines.text(Sid.S11, mapOf("drove" to "1시간 50분", "distanceKm" to "12", "restName" to "안성휴게소")))
        assertEquals("질문 답", Lines.text(Sid.QA, mapOf("text" to "질문 답")))
    }
}
