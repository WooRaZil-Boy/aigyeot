package kr.aigyeot.app.exec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kr.aigyeot.engine.AmbientValue
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.ControlResult
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestoreAmbient
import kr.aigyeot.engine.RestoreAmbientLedger
import kr.aigyeot.engine.fresh

// 준비 = 차가 실제로 답한 때(성공 또는 ERROR_NOT_SUPPORTED_PROPERTY 실패). 그 밖의 실패·예외·응답 없음은 준비 전
class ReadyProbeTest {
    private val t = FakeTime(0)

    @Test fun `연결이 늦으면 응답이 처음 올 때까지 1초마다 다시 묻고 응답이 오면 한 번만 준비로 본다`() {
        var asks = 0
        var readies = 0
        val connectedAt = 6_000L
        val probe = ReadyProbe(t, 1_000, { true }, { ok, _ ->
            asks++
            if (t.now < connectedAt) error("연결 전") // 던지거나 응답 없음: 준비가 아니다
            t.after(30) { ok() }
        }, { readies++ })
        probe.start()
        t.advance(5_900)
        assertFalse(probe.ready)
        assertEquals(0, readies)
        t.advance(1_500)
        assertTrue(probe.ready)
        assertEquals(1, readies)
        val n = asks
        t.advance(10_000)
        assertEquals(n, asks, "준비되면 더 묻지 않는다")
        assertEquals(1, readies)
    }

    @Test fun `응답 없음도 준비가 아니고 stop 뒤에는 멈춘다`() {
        var asks = 0
        var alive = true
        val probe = ReadyProbe(t, 1_000, { alive }, { _, _ -> asks++ }, { })
        probe.start()
        t.advance(3_500)
        assertFalse(probe.ready)
        assertEquals(4, asks)
        alive = false
        t.advance(10_000)
        assertEquals(4, asks)
    }

    @Test fun `그 밖의 실패 콜백은 준비가 아니고 다음 성공에서 준비가 된다`() {
        var asks = 0
        var readies = 0
        val probe = ReadyProbe(t, 1_000, { true }, { ok, fail ->
            asks++
            if (asks < 4) fail(IllegalStateException("service not connected")) else ok()
        }, { readies++ })
        probe.start()
        t.advance(2_500)
        assertFalse(probe.ready)
        assertEquals(0, readies)
        t.advance(1_000)
        assertTrue(probe.ready)
        assertEquals(1, readies)
    }

    @Test fun `NOT_SUPPORTED 실패는 차가 답한 것이라 준비다`() {
        var readies = 0
        val probe = ReadyProbe(t, 1_000, { true }, { _, fail -> fail(Exception("ERROR_NOT_SUPPORTED_PROPERTY")) }, { readies++ })
        probe.start()
        assertTrue(probe.ready)
        assertEquals(1, readies)
    }

    // 장부: 낮춘 채 프로세스가 죽었다 다시 뜨면 차가 준비된 뒤 첫 일로 장부를 되돌리고, 판단 코어 Boot 되돌림과 겹쳐도 한 번만 쓴다
    @Test fun `Exec 판단 코어가 RestoreAmbientLedger를 내지 않으면 시작 때 장부를 건드리지 않는다`() {
        val r = Rec()
        val amb = AmbientsFakeForGate(t)
        amb.brightness = 20
        val store = object : LedgerStore {
            var e: LedgerEntry? = LedgerEntry(AmbientValue(70, 20), null)
            override fun read() = e
            override fun write(e: LedgerEntry?) {
                this.e = e?.takeUnless { it.isEmpty }
            }
        }
        val exec =
            Exec(
                {
                    t.now
                },
                { r.events += it },
                { r.rows += it },
                { EngineState.fresh(1, Profile("2026-02", CarSeat.LEFT, 180, false), emptyList(), emptyList(), null, 0) },
                ExecIo(
                    Any(), t, FakeAudio(),
                    object : TtsPort {
                        override fun speak(text: String, done: (Boolean) -> Unit) {}
                        override fun stop() {}
                    },
                    object : SttPort {
                        override fun begin(onText: (String, Boolean) -> Unit) {}
                        override fun feed(pcm: ByteArray) {}
                        override fun finish() {}
                        override fun cancel() {}
                    },
                    object : MicTap {
                        override fun open(sink: (ByteArray) -> Unit) {}
                        override fun close() {}
                    },
                    FakeControl(t),
                    object : NaviPort {
                        override fun addWaypoint(restId: String, rest: RestCandidate?, done: (Boolean, String) -> Unit) {}
                        override fun cancelWaypoint() {}
                    },
                    ambient = amb, ledger = store
                )
            ) // 차는 처음부터 준비됨
        exec.start()
        t.advance(30_000)
        assertEquals(20, amb.brightness)
        assertEquals(LedgerEntry(AmbientValue(70, 20), null), store.e)
        assertTrue(r.rows.isEmpty() && r.of<ControlResult>().isEmpty())
    }

    @Test fun `Exec RestoreAmbientLedger 명령이 오면 준비 뒤 장부를 되돌리고 결과 사건 없이 control_log에 명령 cmdId로 남긴다`() {
        val r = Rec()
        val amb = AmbientsFakeForGate(t)
        amb.brightness = 20
        val store = object : LedgerStore {
            var e: LedgerEntry? = LedgerEntry(AmbientValue(70, 20), null)
            override fun read() = e
            override fun write(e: LedgerEntry?) {
                this.e = e?.takeUnless { it.isEmpty }
            }
        }
        var ready = false
        var readyCb: () -> Unit = {}
        val exec =
            Exec(
                {
                    t.now
                },
                { r.events += it },
                { r.rows += it },
                { EngineState.fresh(1, Profile("2026-02", CarSeat.LEFT, 180, false), emptyList(), emptyList(), null, 0) },
                ExecIo(
                    Any(), t, FakeAudio(),
                    object : TtsPort {
                        override fun speak(text: String, done: (Boolean) -> Unit) {}
                        override fun stop() {}
                    },
                    object : SttPort {
                        override fun begin(onText: (String, Boolean) -> Unit) {}
                        override fun feed(pcm: ByteArray) {}
                        override fun finish() {}
                        override fun cancel() {}
                    },
                    object : MicTap {
                        override fun open(sink: (ByteArray) -> Unit) {}
                        override fun close() {}
                    },
                    FakeControl(t),
                    object : NaviPort {
                        override fun addWaypoint(restId: String, rest: RestCandidate?, done: (Boolean, String) -> Unit) {}
                        override fun cancelWaypoint() {}
                    },
                    ambient = amb, ledger = store, carReady = { ready }, onCarReady = { readyCb = it }
                )
            )
        exec.handle(RestoreAmbientLedger("idle-5"))
        t.advance(3_000)
        assertEquals(20, amb.brightness)
        ready = true
        readyCb()
        t.advance(2_000)
        assertEquals(70, amb.brightness)
        assertEquals(null, store.e)
        assertTrue(r.of<ControlResult>().isEmpty(), "판단 코어로 결과를 보내지 않는다")
        assertEquals(listOf("idle-5"), r.rows.map { it.cmdId })
    }

    @Test fun `Exec 시작 뒤 차가 준비되면 장부가 첫 일로 되돌리고 뒤이은 Boot 되돌림은 건너뛴다`() {
        val r = Rec()
        val amb = AmbientsFakeForGate(t)
        amb.brightness = 20
        val store = object : LedgerStore {
            var e: LedgerEntry? = LedgerEntry(AmbientValue(70, 20), null)
            override fun read() = e
            override fun write(e: LedgerEntry?) {
                this.e = e?.takeUnless { it.isEmpty }
            }
        }
        var ready = false
        var readyCb: () -> Unit = {}
        val exec =
            Exec(
                {
                    t.now
                },
                { r.events += it },
                { r.rows += it },
                { EngineState.fresh(1, Profile("2026-02", CarSeat.LEFT, 180, false), emptyList(), emptyList(), null, 0) },
                ExecIo(
                    Any(), t, FakeAudio(),
                    object : TtsPort {
                        override fun speak(text: String, done: (Boolean) -> Unit) {}
                        override fun stop() {}
                    },
                    object : SttPort {
                        override fun begin(onText: (String, Boolean) -> Unit) {}
                        override fun feed(pcm: ByteArray) {}
                        override fun finish() {}
                        override fun cancel() {}
                    },
                    object : MicTap {
                        override fun open(sink: (ByteArray) -> Unit) {}
                        override fun close() {}
                    },
                    FakeControl(t),
                    object : NaviPort {
                        override fun addWaypoint(restId: String, rest: RestCandidate?, done: (Boolean, String) -> Unit) {}
                        override fun cancelWaypoint() {}
                    },
                    ambient = amb, ledger = store, carReady = { ready }, onCarReady = { readyCb = it }
                )
            )
        exec.handle(RestoreAmbientLedger("idle-5")) // Boot가 낸 장부 되돌림이 먼저
        exec.handle(RestoreAmbient("s1-9", AmbientValue(70, 20), null)) // 판단 코어 Boot 되돌림도 준비 전에 줄을 선다
        t.advance(3_000)
        assertEquals(20, amb.brightness)
        ready = true
        readyCb()
        t.advance(2_000)
        assertEquals(70, amb.brightness)
        assertEquals(null, store.e)
        assertEquals(1, r.rows.size, "한 번만 썼다: ${r.rows.map { it.cmdId }}")
        assertEquals("idle-5", r.rows.single().cmdId)
        assertEquals(1, r.of<ControlResult>().size, "판단 코어 몫은 건너뛴 결과(ok=false) 하나")
    }

    // 조명이 낮은 채 앱 재시작 → Boot의 RestoreAmbient가 연결 6초 지연보다 먼저 와도 준비된 뒤에 실행되어 조명이 되돌아온다
    @Test fun `Exec 연결이 6초 늦어도 미룬 RestoreAmbient가 준비된 뒤 실행되어 조명이 되돌아온다`() {
        val r = Rec()
        val amb = AmbientsFakeForGate(t)
        amb.brightness = 20
        var readyCb: () -> Unit = {}
        var probeRef: ReadyProbe? = null
        val exec =
            Exec(
                {
                    t.now
                },
                { r.events += it },
                { r.rows += it },
                { EngineState.fresh(1, Profile("2026-02", CarSeat.LEFT, 180, false), emptyList(), emptyList(), null, 0) },
                ExecIo(
                    Any(), t, FakeAudio(),
                    object : TtsPort {
                        override fun speak(text: String, done: (Boolean) -> Unit) {}
                        override fun stop() {}
                    },
                    object : SttPort {
                        override fun begin(onText: (String, Boolean) -> Unit) {}
                        override fun feed(pcm: ByteArray) {}
                        override fun finish() {}
                        override fun cancel() {}
                    },
                    object : MicTap {
                        override fun open(sink: (ByteArray) -> Unit) {}
                        override fun close() {}
                    },
                    FakeControl(t),
                    object : NaviPort {
                        override fun addWaypoint(restId: String, rest: RestCandidate?, done: (Boolean, String) -> Unit) {}
                        override fun cancelWaypoint() {}
                    },
                    ambient = amb, carReady = { probeRef?.ready == true }, onCarReady = { readyCb = it }
                )
            )
        val probe = ReadyProbe(t, 1_000, { true }, { ok, _ ->
            if (t.now < 6_000) error("연결 전")
            t.after(30) { ok() }
        }, { readyCb() })
        probeRef = probe
        probe.start()
        exec.handle(RestoreAmbient("s1-9", AmbientValue(70, 20), null))
        t.advance(5_900)
        assertTrue(r.of<ControlResult>().isEmpty(), "준비 전에는 영역 조회도 하지 않아 실패 결과가 없다")
        assertEquals(20, amb.brightness)
        t.advance(3_000)
        assertEquals(70, amb.brightness)
        assertTrue(r.of<ControlResult>().single().ok)
    }
}
