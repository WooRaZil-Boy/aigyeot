package kr.aigyeot.app.exec

import kr.aigyeot.engine.AmbientValue
import kr.aigyeot.engine.Area
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.ControlKind
import kr.aigyeot.engine.ControlResult
import kr.aigyeot.engine.DimAmbient
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestoreAmbient
import kr.aigyeot.engine.SetWindowLock
import kr.aigyeot.engine.fresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 차 준비 전 명령과 재시도: 차 연결 전 차 제어 명령은 미뤘다가 준비되면 순서대로, 끝내 안 되면 ok = false
class CarGateTest {
    private val t = FakeTime()

    @Test fun `준비돼 있으면 바로 실행하고 준비 전이면 받은 순서대로 미뤘다가 준비되면 실행한다`() {
        var ready = false
        val order = mutableListOf<String>()
        val g = CarGate(t, 20_000) { ready }
        g.submit({ order += "a" }, { order += "fail-a" }); g.submit({ order += "b" }, { order += "fail-b" })
        assertTrue(order.isEmpty())
        ready = true; g.readyNow()
        assertEquals(listOf("a", "b"), order)
        g.submit({ order += "c" }, {})
        assertEquals(listOf("a", "b", "c"), order, "준비된 뒤에는 바로")
        t.advance(60_000)
        assertEquals(listOf("a", "b", "c"), order, "실행된 것은 시간 초과로 실패하지 않는다")
    }

    @Test fun `끝내 준비되지 않으면 시간 안에 실패를 부르고 늦게 준비돼도 실행하지 않는다`() {
        var ready = false
        val order = mutableListOf<String>()
        val g = CarGate(t, 20_000) { ready }
        g.submit({ order += "a" }, { order += "fail-a" })
        t.advance(19_999); assertTrue(order.isEmpty())
        t.advance(2)
        assertEquals(listOf("fail-a"), order)
        ready = true; g.readyNow()
        assertEquals(listOf("fail-a"), order)
    }

    @Test fun `stop은 미룬 명령을 모두 실패로 닫는다`() {
        val order = mutableListOf<String>()
        val g = CarGate(t, 20_000) { false }
        g.submit({}, { order += "x" }); g.submit({}, { order += "y" })
        g.failAll()
        assertEquals(listOf("x", "y"), order)
    }

    // Exec 입구: 준비 전 RestoreAmbient·SetWindowLock이 준비 뒤에 실행되고, 끝내 안 되면 ControlResult(ok = false)가 온다
    private class Rig {
        val t = FakeTime(); val r = Rec(); var ready = false; var onReady: () -> Unit = {}
        val control = FakeControl(t); val amb = AmbientsFakeForGate(t)
        val exec = Exec({ t.now }, { r.events += it }, { r.rows += it }, { EngineState.fresh(1, Profile("2026-02", CarSeat.LEFT, 180, false), emptyList(), emptyList(), null, 0) },
            ExecIo(Any(), t, FakeAudio(), object : TtsPort { override fun speak(text: String, done: (Boolean) -> Unit) {}; override fun stop() {} },
                object : SttPort { override fun begin(onText: (String, Boolean) -> Unit) {}; override fun feed(pcm: ByteArray) {}; override fun finish() {}; override fun cancel() {} },
                object : MicTap { override fun open(sink: (ByteArray) -> Unit) {}; override fun close() {} }, control,
                object : NaviPort { override fun addWaypoint(restId: String, rest: RestCandidate?, done: (Boolean, String) -> Unit) {}; override fun cancelWaypoint() {} },
                ambient = amb, carReady = { ready }, onCarReady = { onReady = it }))
        fun becomeReady() { ready = true; onReady() }
    }

    @Test fun `Exec 준비 전 RestoreAmbient와 SetWindowLock은 준비 뒤에 실행된다`() {
        val z = Rig()
        z.amb.brightness = 20
        z.control.values[ControlKind.WINDOW_LOCK to Area.ROW_2_LEFT] = 0.0
        z.exec.handle(RestoreAmbient("s1-9", AmbientValue(70, 20), null))
        z.exec.handle(SetWindowLock("s1-10", mapOf(Area.ROW_2_LEFT to true)))
        z.t.advance(3_000)
        assertTrue(z.r.of<ControlResult>().isEmpty() && z.control.writes.isEmpty(), "준비 전에는 아무것도 하지 않는다")
        z.becomeReady(); z.t.advance(2_000)
        assertEquals(70, z.amb.brightness)
        assertEquals(1, z.control.writes.size)
        assertTrue(z.r.of<ControlResult>().all { it.ok }, "${z.r.of<ControlResult>()}")
        assertEquals(2, z.r.of<ControlResult>().size)
    }

    @Test fun `Exec 준비 전 DimAmbient는 미루지 않고 바로 ok false 둘이며 준비 뒤에도 실행되지 않는다`() {
        val z = Rig()
        z.exec.handle(DimAmbient("s1-3", 0.2, 0xFF3050A0.toInt()))
        val res = z.r.of<ControlResult>()
        assertEquals(setOf(ControlKind.AMBIENT_BRIGHTNESS, ControlKind.AMBIENT_COLOR), res.map { it.control }.toSet())
        assertTrue(res.all { !it.ok && it.area == null && it.before == null && it.after == null } && z.r.rows.isEmpty())
        z.becomeReady(); z.t.advance(30_000)
        assertEquals(2, z.r.of<ControlResult>().size, "준비 뒤에 다시 실행되어 결과가 늘지 않는다")
        assertEquals(70, z.amb.brightness, "조명을 건드리지 않았다")
    }

    @Test fun `Exec 끝내 준비되지 않으면 verifyTimeoutMs 곱하기 4 뒤 ok false 결과가 온다`() {
        val z = Rig()
        z.exec.handle(RestoreAmbient("s1-9", AmbientValue(70, 20), null))
        z.exec.handle(SetWindowLock("s1-10", mapOf(Area.ROW_2_LEFT to true)))
        z.t.advance(19_999); assertTrue(z.r.of<ControlResult>().isEmpty())
        z.t.advance(2)
        val res = z.r.of<ControlResult>()
        assertEquals(2, res.size); assertTrue(res.none { it.ok })
        assertEquals(listOf(ControlKind.AMBIENT_BRIGHTNESS, ControlKind.WINDOW_LOCK), res.map { it.control })
    }
}

/** 게이트 시험용 앰비언트 가짜(영역 하나, 밝기만 의미). */
internal class AmbientsFakeForGate(private val t: FakeTime) : AmbientPort {
    var brightness: Int? = 70
    private val area = AmbientArea("A", 0, 100, Any())
    override fun area(cb: (AmbientArea?) -> Unit) = cb(area)
    override fun lit(a: AmbientArea, cb: (Boolean?) -> Unit) = cb(true)
    override fun autoColor(cb: (AutoColor) -> Unit) = cb(AutoColor.NONE)
    override fun readBrightness(a: AmbientArea, cb: (Int?) -> Unit) = cb(brightness)
    override fun readColor(a: AmbientArea, cb: (Int?) -> Unit) = cb(0xFFFFFF)
    override fun writeBrightness(a: AmbientArea, v: Int, ack: (Boolean) -> Unit) { t.after(20) { ack(true) }; t.after(50) { brightness = v } }
    override fun writeColor(a: AmbientArea, argb: Int, ack: (Boolean) -> Unit) = ack(false)
}
