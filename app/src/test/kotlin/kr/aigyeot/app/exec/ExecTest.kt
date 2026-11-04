package kr.aigyeot.app.exec

import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.AddWaypoint
import kr.aigyeot.engine.Answer
import kr.aigyeot.engine.Area
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.CancelSpeech
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.ControlResult
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.EndReason
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Heard
import kr.aigyeot.engine.Listen
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.PlayKind
import kr.aigyeot.engine.PlaybackEnded
import kr.aigyeot.engine.PlaybackRejected
import kr.aigyeot.engine.PlaybackStarted
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.Qa
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import kr.aigyeot.engine.RejectReason
import kr.aigyeot.engine.RouteView
import kr.aigyeot.engine.SetSeatHeat
import kr.aigyeot.engine.SetWindowLock
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.StartPlayback
import kr.aigyeot.engine.WaypointResult
import kr.aigyeot.engine.YesNo
import kr.aigyeot.engine.fresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Exec 공개 모양(handle)의 분배와 state() 읽기(D12 앞 틀기 허락, 마이크 서비스), SDK가 던질 때의 실패 결과
class ExecTest {
    private val t = FakeTime()
    private val r = Rec()
    private val audio = FakeAudio()
    private val car = FakeControl(t)
    private val spoken = mutableListOf<String>()
    private var ttsDone: ((Boolean) -> Unit)? = null
    private var throwOn = false
    private var logThrows = false
    private var starts = 0
    private var stops = 0
    private var ttsStopThrows = false
    private var st: EngineState? = EngineState.fresh(1, Profile("2026-02", CarSeat.LEFT, 180, false, frontFallbackAllowed = false), emptyList(), emptyList(), null, 0)

    private val tts = object : TtsPort {
        override fun speak(text: String, done: (Boolean) -> Unit) { if (throwOn) error("tts 없음"); spoken += text; ttsDone = done }
        override fun stop() { if (ttsStopThrows) error("tts stop 실패") }
    }
    private val stt = object : SttPort {
        override fun begin(onText: (String, Boolean) -> Unit) {}
        override fun feed(pcm: ByteArray) {}
        override fun finish() {}
        override fun cancel() {}
    }
    private val tap = object : MicTap { override fun open(sink: (ByteArray) -> Unit) {}; override fun close() {} }
    private val askedRests = mutableListOf<RestCandidate?>()
    private val navi = object : NaviPort { override fun addWaypoint(restId: String, rest: RestCandidate?, done: (Boolean, String) -> Unit) { askedRests += rest; done(false, "no_coords") }
        override fun cancelWaypoint() {}
    }
    private val exec = Exec({ t.now }, { r.events += it }, { if (logThrows) error("로그 저장 실패"); r.rows += it }, { st }, ExecIo(Any(), t, audio, tts, stt, tap, car, navi, start = { starts++ }, stop = { stops++ }))

    private val start = StartPlayback("s1-1", PlayKind.AUTO, Method(MethodKind.WHITE_NOISE), 1_056_000, 2_000)

    @Test fun `할 일을 받는 곳으로 나눈다`() {
        exec.handle(start)
        assertTrue(r.events.single() is PlaybackStarted)
        exec.handle(Speak("s1-2", Sid.S12, emptyMap()))
        assertEquals("도착했어요. 자고 있어도 카시트에서 꺼내 눕혀 주세요.", spoken.single())
        car.values[kr.aigyeot.engine.ControlKind.SEAT_HEAT to Area.ROW_2_LEFT] = 1.0
        exec.handle(SetSeatHeat("s1-3", listOf(Area.ROW_2_LEFT)))
        t.advance(1_000)
        assertTrue(r.of<ControlResult>().single().ok)
        exec.handle(AddWaypoint("s1-4", "tbt:안성"))
        assertEquals(WaypointResult(t.now, "s1-4", "tbt:안성", false), r.events.last(), "좌표가 없으면 바로 실패")
        val n = r.events.size
        exec.handle(Qa("s1-5", "질문"))
        exec.handle(CancelSpeech)
        assertEquals(n, r.events.size, "Qa는 link 몫이라 무시한다")
    }

    @Test fun `앞에서 틀기 허락은 state의 프로필에서 읽는다`() {
        audio.rear = false
        exec.handle(start)
        assertEquals(RejectReason.NO_REAR_OUTPUT, r.of<PlaybackRejected>().single().reason)
        st = st!!.copy(profile = st!!.profile!!.copy(frontFallbackAllowed = true))
        exec.handle(start.copy(playId = "s1-2"))
        assertTrue(r.of<PlaybackStarted>().single().fallback)
    }

    @Test fun `마이크 서비스가 돌지 않으면 듣지 않고 바로 답 없음`() {
        exec.handle(Listen("s1-6", 5_000))
        assertEquals(Heard(t.now, "s1-6", null), r.events.single(), "fresh 상태는 마이크 꺼짐")
        exec.handle(Ask("s1-7", Sid.S08, emptyMap(), 6_000))
        ttsDone!!(true)
        assertEquals(listOf<Event>(SpeechDone(t.now, "s1-7", true), Answer(t.now, "s1-7", YesNo.NONE)), r.events.drop(1))
    }

    @Test fun `TTS가 던지면 못 말한 것으로 끝낸다`() {
        throwOn = true
        exec.handle(Ask("s1-7", Sid.S08, emptyMap(), 6_000))
        assertEquals(listOf<Event>(SpeechDone(t.now, "s1-7", false), Answer(t.now, "s1-7", YesNo.NONE)), r.events)
        t.advance(Voice.SPEECH_TIMEOUT_MS)
        assertEquals(2, r.events.size, "감시 타이머가 두 번 내지 않는다")
    }

    @Test fun `SDK가 던지면 기다리는 결과를 실패로 내고 ControlLog에 남긴다`() {
        audio.throwRear = true
        exec.handle(start)
        assertEquals(listOf<Event>(PlaybackRejected(t.now, "s1-1", RejectReason.ERROR)), r.events)
        assertTrue(r.rows.any { it.api == "playback" && !it.ok }, "부품이 던짐을 막아 ControlLog에 남긴다")
    }

    @Test fun `control_log 콜백이 던져도 재생은 끝 시각에 끝나고 제어·경유지 결과는 하나씩 나온다`() {
        logThrows = true
        exec.handle(start)
        val track = audio.tracks.single()
        assertTrue(r.events.single() is PlaybackStarted, "시작 결과 하나")
        assertTrue(!track.released && t.pending() > 0, "조건: 아직 재생 중이고 타이머가 있다")
        t.advance(56_000)
        assertEquals(EndReason.DEADLINE, r.of<PlaybackEnded>().single().reason)
        assertTrue(track.released && !audio.focusHeld)
        car.values[kr.aigyeot.engine.ControlKind.SEAT_HEAT to Area.ROW_2_LEFT] = 1.0
        exec.handle(SetSeatHeat("s1-3", listOf(Area.ROW_2_LEFT)))
        t.advance(30_000)
        assertEquals(1, r.of<ControlResult>().size)
        exec.handle(AddWaypoint("s1-4", "tbt:안성"))
        assertEquals(1, r.of<WaypointResult>().size)
        assertTrue(r.rows.isEmpty(), "로그는 모두 버려졌다")
    }

    @Test fun `start를 여러 번 불러도 한 번만 시작하고 stop 뒤 start는 다시 시작한다`() {
        exec.start(); exec.start()
        assertEquals(1, starts)
        exec.stop()
        assertEquals(1, stops)
        exec.start()
        assertEquals(2, starts, "stop 뒤에는 다시 구독한다")
        assertEquals(1, stops)
    }

    @Test fun `stop은 두 번 불러도 한 번만 정리하고 시작 안 했으면 아무것도 안 한다`() {
        exec.stop()
        assertEquals(0, stops, "시작 안 했으면 아무것도 안 함")
        exec.start(); exec.stop(); exec.stop()
        assertEquals(1, stops)
    }

    @Test fun `stop은 재생을 사건 없이 놓고 말하기를 끊으며 그 뒤 start하면 새 재생이 정상이다`() {
        exec.start()
        exec.handle(start)
        exec.handle(Speak("s1-2", Sid.S12, emptyMap()))
        val n = r.events.size
        val track = audio.tracks.single()
        assertTrue(!track.released && audio.focusHeld && ttsDone != null, "조건: 재생 중이고 말하는 중")
        exec.stop()
        assertTrue(track.released && !audio.focusHeld)
        assertEquals(n, r.events.size, "사건 없이 놓인다")
        ttsDone!!(true)
        assertEquals(n, r.events.size, "끊은 문장의 SpeechDone을 내지 않는다")
        t.advance(60_000)
        assertEquals(n, r.events.size)
        exec.start()
        exec.handle(start.copy(playId = "s1-9"))
        assertEquals("s1-9", r.of<PlaybackStarted>().last().playId)
        assertEquals(2, audio.tracks.size)
    }

    @Test fun `stop 뒤 옛 제어 작업의 재시도가 차량에 다시 쓰지 않는다`() {
        val key = kr.aigyeot.engine.ControlKind.WINDOW_LOCK to Area.ROW_2_LEFT
        exec.start()
        car.values[key] = 1.0; car.applyAfterMs = null
        exec.handle(SetWindowLock("s1-3", mapOf(Area.ROW_2_LEFT to false)))
        t.advance(2_000)
        assertEquals(listOf(0.0), car.writes.map { it.third })
        assertTrue(r.of<ControlResult>().isEmpty(), "조건: 옛 작업이 진행 중이다")
        exec.stop(); exec.start()
        assertEquals(ControlResult(t.now, "s1-3", kr.aigyeot.engine.ControlKind.WINDOW_LOCK, Area.ROW_2_LEFT, false, 1.0, 1.0), r.of<ControlResult>().single(), "stop 때 옛 작업을 ok=false로 닫는다")
        car.applyAfterMs = 300
        exec.handle(SetWindowLock("s1-4", mapOf(Area.ROW_2_LEFT to true)))
        t.advance(30_000)
        assertEquals(listOf(0.0, 1.0), car.writes.map { it.third }, "옛 재시도(0.0)가 새 값을 덮지 않는다")
        assertTrue(r.of<ControlResult>().last().ok && r.of<ControlResult>().size == 2)
    }

    @Test fun `이름이 같은 졸음쉼터와 휴게소 중 제안한 휴게소의 후보를 내비에 넘긴다`() {
        val shelter = restsOf(listOf(TBTInfo(TurnType.TURN_RESTAREA, 5_000.0, "오창(하남)"))) { false }.single()
        val rest = restsOf(listOf(TBTInfo(TurnType.TURN_ENTER_SERVICEAREA, 8_500.0, "오창(하남)"))) { true }.single()
        assertTrue(shelter.name == rest.name && shelter.kind != rest.kind, "조건: 같은 이름, 다른 종류")
        st = st!!.copy(car = st!!.car.copy(route = RouteView(t.now, true, 900, listOf(shelter, rest))))
        exec.handle(AddWaypoint("s1-4", rest.id))
        assertEquals(listOf<RestCandidate?>(rest), askedRests, "졸음쉼터(5km)가 아니라 휴게소(8.5km)")
        assertEquals(8.5, askedRests.single()!!.distanceKm)
    }

    @Test fun `stop 단계 하나가 던져도 나머지 정리가 이어진다`() {
        exec.start()
        exec.handle(start)
        exec.handle(Speak("s1-2", Sid.S12, emptyMap()))
        ttsStopThrows = true
        exec.stop()
        assertTrue(audio.tracks.single().released && !audio.focusHeld, "말하기 끊기가 던져도 재생은 놓인다")
        assertEquals(1, stops)
    }

    @Test fun `읽기가 던져도 결과는 영역마다 하나뿐이고 남은 타이머가 또 내지 않는다`() {
        car.throwRead = true
        exec.handle(SetWindowLock("s1-3", mapOf(Area.ROW_2_LEFT to true)))
        t.advance(30_000)
        val got = r.of<ControlResult>()
        assertEquals(1, got.size, "30초 뒤에도 늘지 않는다: $got")
        assertTrue(!got.single().ok)
        assertEquals(1, r.rows.count { it.api == "setWindowLock" })
        assertEquals(0, t.pending())
    }
}
