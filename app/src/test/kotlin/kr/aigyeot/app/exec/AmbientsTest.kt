package kr.aigyeot.app.exec

import kr.aigyeot.engine.AmbientValue
import kr.aigyeot.engine.ControlKind.AMBIENT_BRIGHTNESS as B
import kr.aigyeot.engine.ControlKind.AMBIENT_COLOR as C
import kr.aigyeot.engine.ControlResult
import kr.aigyeot.engine.DimAmbient
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.RestoreAmbient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// DimAmbient·RestoreAmbient 실행기 차례(달래는 동안 조명 낮춤)
class AmbientsTest {
    private class FakeAmbient(val t: FakeTime) : AmbientPort {
        var area: AmbientArea? = AmbientArea("ROW_1_LEFT+ROW_2_RIGHT", 0, 100, Any())
        var lit = true
        var auto = AutoColor.NONE
        var brightness: Int? = 70
        var color: Int? = 0xFFFFFF
        var applyBrightness = true
        val writes = mutableListOf<String>()
        val failReads = mutableMapOf<String, Int>()   // 종류별로 앞의 n번 읽기를 실패(null)시킨다
        var failBrightnessWrites = 0                  // 앞의 n번 밝기 쓰기를 거절(ack false, 반영 안 함)
        var failColorWrites = 0
        private fun fails(k: String): Boolean { val n = failReads[k] ?: 0; if (n > 0) failReads[k] = n - 1; return n > 0 }
        val silentReads = mutableMapOf<String, Int>()  // 종류별로 앞의 n번 읽기는 콜백이 아예 오지 않는다(응답 없음)
        private fun silent(k: String): Boolean { val n = silentReads[k] ?: 0; if (n > 0) silentReads[k] = n - 1; return n > 0 }
        override fun area(cb: (AmbientArea?) -> Unit) { if (silent("area")) return; cb(if (fails("area")) null else area) }
        override fun lit(a: AmbientArea, cb: (Boolean?) -> Unit) { if (silent("lit")) return; cb(if (fails("lit")) null else lit) }
        override fun autoColor(cb: (AutoColor) -> Unit) { if (silent("auto")) return; cb(auto) }
        var holdBrightnessReads = false                // 밝기 읽기 응답을 붙잡아 두었다가 release로 늦게 낸다
        val held = mutableListOf<() -> Unit>()
        var brightnessApplyMs = 50L                    // 밝기 쓰기가 반영되는 데 걸리는 시간(ack는 20ms)
        override fun readBrightness(a: AmbientArea, cb: (Int?) -> Unit) {
            if (silent("brightness")) return
            if (holdBrightnessReads) { held += { cb(brightness) }; return }
            cb(if (fails("brightness")) null else brightness)
        }
        override fun readColor(a: AmbientArea, cb: (Int?) -> Unit) { if (silent("color")) return; cb(if (fails("color")) null else color) }   // 알파가 붙은 채로 준다(24비트 견줌은 Ambients 몫)
        override fun writeBrightness(a: AmbientArea, v: Int, ack: (Boolean) -> Unit) {
            writes += "b$v"
            if (failBrightnessWrites > 0) { failBrightnessWrites--; t.after(20) { ack(false) }; return }
            t.after(20) { ack(true) }; if (applyBrightness) t.after(brightnessApplyMs) { brightness = v }
        }
        override fun writeColor(a: AmbientArea, argb: Int, ack: (Boolean) -> Unit) {
            writes += "c" + Integer.toHexString(argb)
            if (failColorWrites > 0) { failColorWrites--; t.after(20) { ack(false) }; return }
            t.after(20) { ack(true) }; t.after(50) { color = argb }
        }
    }

    private val t = FakeTime()
    private val r = Rec()
    private val port = FakeAmbient(t)
    private val p = Params()
    private val a = Ambients(port, t, { t.now }, { r.events += it }, logger(t, r), p)
    private val dim = DimAmbient("s1-3", p.ambientDimLevel, p.ambientDimColor)

    private fun results() = r.of<ControlResult>().associateBy { it.control }

    @Test fun `영역은 capability가 정하고 결과는 area null로 늘 둘`() {
        port.area = null
        a.dim(dim); t.advance(1_000)   // 영역 읽기도 재시도한다
        val res = results()
        assertEquals(setOf(B, C), res.keys)
        assertTrue(res.values.all { !it.ok && it.area == null && it.before == null && it.after == null })
        assertTrue(r.rows.isEmpty() && port.writes.isEmpty(), "영역을 못 읽으면 쓰지 않고 줄도 없다")
    }

    @Test fun `스위치가 꺼져 있으면 둘 다 쓰지 않고 줄도 없다`() {
        port.lit = false
        a.dim(dim)
        assertTrue(port.writes.isEmpty() && r.rows.isEmpty())
        assertTrue(results().values.all { !it.ok })
    }

    @Test fun `밝기는 낮추기만 한다 - 이미 목표 이하면 쓰지 않는다`() {
        port.brightness = 20
        a.dim(dim); t.advance(1_000)
        assertTrue(port.writes.none { it.startsWith("b") })
        val b = results().getValue(B)
        assertEquals(listOf(false, 20.0, 20.0), listOf(b.ok, b.before, b.after))
        assertTrue(r.rows.none { it.api == "setAmbientLightBrightness" }, "쓰지 않은 종류는 control_log 줄이 없다")
    }

    @Test fun `밝기 70을 20으로 쓰고 다시 읽어 확인하면 ok와 control_log`() {
        a.dim(dim); t.advance(1_000)
        assertTrue("b20" in port.writes)
        val b = results().getValue(B)
        assertEquals(listOf(true, 70.0, 20.0, null), listOf(b.ok, b.before, b.after, b.area))
        val row = r.rows.single { it.api == "setAmbientLightBrightness" }
        assertEquals(listOf("ROW_1_LEFT+ROW_2_RIGHT", "dim:20", "20", "s1-3", true), listOf(row.target, row.goal, row.actual, row.cmdId, row.ok))
    }

    @Test fun `자동 색 capability가 없는 차는 색을 바꾼다 - 24비트로 견주고 알파 FF로 쓴다`() {
        port.auto = AutoColor.NONE
        a.dim(dim); t.advance(1_000)
        assertTrue("c" + Integer.toHexString(p.ambientDimColor) in port.writes)
        val c = results().getValue(C)
        assertEquals(listOf(true, 0xFFFFFF.toDouble(), 0x3050A0.toDouble()), listOf(c.ok, c.before, c.after))
        assertEquals("dim:#3050A0", r.rows.single { it.api == "setAmbientLightBrightnessColor" }.goal)
    }

    @Test fun `자동 색이 켜졌거나 모르면 색은 두고 밝기만`() {
        port.auto = AutoColor.UNKNOWN
        a.dim(dim); t.advance(1_000)
        assertTrue(port.writes.none { it.startsWith("c") } && "b20" in port.writes)
        assertTrue(!results().getValue(C).ok && results().getValue(B).ok)
        assertTrue(r.rows.none { it.api.endsWith("Color") })
    }

    @Test fun `같은 색이면 쓰지 않는다 - 알파가 달라도 24비트가 같으면 같은 색`() {
        port.color = 0xFF3050A0.toInt()
        a.dim(dim); t.advance(1_000)
        assertTrue(port.writes.none { it.startsWith("c") })
        assertTrue(!results().getValue(C).ok)
    }

    @Test fun `쓰기가 반영되지 않으면 한 번 다시 보내고 그래도 안 되면 실패와 control_log`() {
        port.applyBrightness = false
        a.dim(dim); t.advance(12_000)
        assertEquals(2, port.writes.count { it == "b20" })
        assertTrue(!results().getValue(B).ok)
        assertEquals(false, r.rows.single { it.api == "setAmbientLightBrightness" }.ok)
    }

    @Test fun `되돌림은 지금 값이 set과 같을 때만 쓴다`() {
        port.brightness = 20; port.color = 0x3050A0
        a.restore(RestoreAmbient("s1-9", AmbientValue(70, 20), AmbientValue(0xFFFFFF, 0x3050A0))); t.advance(1_000)
        assertEquals(70, port.brightness); assertEquals(0xFFFFFF, port.color?.and(0xFFFFFF))
        assertTrue(results().values.all { it.ok })
        assertEquals(setOf("restore:70", "restore:#FFFFFF"), r.rows.map { it.goal }.toSet())
    }

    @Test fun `운전자가 바꾼 값은 되돌리지 않고 결과만 내며 줄도 없다`() {
        port.brightness = 55; port.color = 0x3050A0
        a.restore(RestoreAmbient("s1-9", AmbientValue(70, 20), AmbientValue(0xFFFFFF, 0x3050A0))); t.advance(1_000)
        assertEquals(55, port.brightness)
        val b = results().getValue(B)
        assertEquals(listOf(false, 55.0, 55.0), listOf(b.ok, b.before, b.after))
        assertEquals(listOf("restore:#FFFFFF"), r.rows.map { it.goal }, "색만 썼다")
    }

    @Test fun `있는 종류의 결과만 낸다`() {
        port.brightness = 20
        a.restore(RestoreAmbient("s1-9", AmbientValue(70, 20), null)); t.advance(1_000)
        assertEquals(setOf(B), results().keys)
    }

    @Test fun `close는 진행 중인 종류를 실패 결과로 닫는다`() {
        a.dim(dim)   // 쓰기 반영 전
        a.closeAll()
        assertEquals(setOf(B, C), results().keys)
        assertTrue(results().values.all { !it.ok })
        t.advance(10_000)
        assertEquals(2, r.of<ControlResult>().size, "늦은 콜백은 결과를 다시 내지 않는다")
    }

    // ---- 읽기 재시도 ----
    @Test fun `되돌림의 첫 밝기·색 읽기만 실패해도 다시 읽어 되돌린다`() {
        port.brightness = 20; port.color = 0x3050A0
        port.failReads["brightness"] = 1; port.failReads["color"] = 1
        a.restore(RestoreAmbient("s1-9", AmbientValue(70, 20), AmbientValue(0xFFFFFF, 0x3050A0))); t.advance(2_000)
        assertEquals(70, port.brightness); assertEquals(0xFFFFFF, port.color?.and(0xFFFFFF))
        assertTrue(results().values.all { it.ok })
    }

    // 스냅숏 안의 읽기 하나가 실패하면 묶음 전체를 처음부터 다시 읽는다(verifyRetries 1번). 실패를 한 묶음에 하나씩만 둔다
    @Test fun `낮춤의 영역·스위치 첫 읽기만 실패해도 다시 읽어 낮춘다`() {
        port.failReads["area"] = 1; port.failReads["lit"] = 1
        a.dim(dim); t.advance(3_000)
        assertEquals(20, port.brightness); assertEquals(0x3050A0, port.color?.and(0xFFFFFF))
        assertTrue(results().values.all { it.ok })
    }

    @Test fun `재시도 뒤에도 못 읽으면 쓰지 않고 실패 결과`() {
        port.brightness = 20
        port.failReads["brightness"] = 5
        a.restore(RestoreAmbient("s1-9", AmbientValue(70, 20), null)); t.advance(2_000)
        assertEquals(20, port.brightness)
        assertTrue(!results().getValue(B).ok && r.rows.isEmpty())
    }

    // ---- 쓰기 재시도는 운전자 변경을 덮지 않는다 ----
    private fun driver(after: Long, f: () -> Unit) { t.after(after) { f() } }

    @Test fun `되돌림 첫 쓰기가 거절된 뒤 운전자가 밝기를 바꾸면 재시도가 덮지 않는다`() {
        port.brightness = 20; port.failBrightnessWrites = 1
        a.restore(RestoreAmbient("s1-9", AmbientValue(70, 20), null)); driver(1_000) { port.brightness = 40 }
        t.advance(12_000)
        assertEquals(40, port.brightness); assertEquals(1, port.writes.count { it.startsWith("b") })
        val b = results().getValue(B)
        assertEquals(listOf(false, 40.0, 40.0), listOf(b.ok, b.before, b.after))
        assertTrue(r.rows.isEmpty(), "쓰지 않은 종류는 줄이 없다")
    }

    @Test fun `되돌림 첫 쓰기가 거절된 뒤 운전자가 색을 바꾸면 재시도가 덮지 않는다`() {
        port.color = 0x3050A0; port.failColorWrites = 1
        a.restore(RestoreAmbient("s1-9", null, AmbientValue(0xFFFFFF, 0x3050A0))); driver(1_000) { port.color = 0x00FF00 }
        t.advance(12_000)
        assertEquals(0x00FF00, port.color); assertEquals(1, port.writes.count { it.startsWith("c") })
        assertTrue(!results().getValue(C).ok && r.rows.isEmpty())
    }

    @Test fun `낮춤 첫 쓰기가 거절된 뒤 운전자가 더 낮추면 재시도가 올리지 않는다`() {
        port.failBrightnessWrites = 1
        a.dim(dim); driver(1_000) { port.brightness = 10 }
        t.advance(12_000)
        assertEquals(10, port.brightness); assertEquals(1, port.writes.count { it == "b20" })
        assertTrue(!results().getValue(B).ok && r.rows.none { it.api == "setAmbientLightBrightness" })
    }

    @Test fun `낮춤 첫 색 쓰기가 거절된 뒤 운전자가 색을 바꾸면 재시도가 덮지 않는다`() {
        port.failColorWrites = 1
        a.dim(dim); driver(1_000) { port.color = 0x00FF00 }
        t.advance(12_000)
        assertEquals(0x00FF00, port.color); assertEquals(1, port.writes.count { it.startsWith("c") })
        assertTrue(!results().getValue(C).ok && r.rows.none { it.api.endsWith("Color") })
    }

    @Test fun `값이 그대로면 재시도는 그대로 쓴다`() {
        port.brightness = 20; port.failBrightnessWrites = 1
        a.restore(RestoreAmbient("s1-9", AmbientValue(70, 20), null)); t.advance(12_000)
        assertEquals(70, port.brightness); assertEquals(2, port.writes.count { it.startsWith("b") })
        assertTrue(results().getValue(B).ok)
    }

    // ---- 응답 없음도 읽기 재시도 ----
    @Test fun `되돌림의 첫 밝기 읽기 콜백이 아예 안 와도 시간 제한 뒤 다시 읽어 되돌린다`() {
        port.brightness = 20; port.silentReads["brightness"] = 1
        a.restore(RestoreAmbient("s1-9", AmbientValue(70, 20), null)); t.advance(4_999)
        assertTrue(results().isEmpty(), "시간 제한 전에는 기다린다")
        t.advance(3_000)
        assertEquals(70, port.brightness); assertTrue(results().getValue(B).ok)
    }

    @Test fun `낮춤의 첫 영역 읽기 콜백이 안 와도 다시 읽어 낮춘다`() {
        port.silentReads["area"] = 1
        a.dim(dim); t.advance(8_000)
        assertEquals(20, port.brightness); assertTrue(results().getValue(B).ok)
    }

    @Test fun `응답이 계속 없으면 재시도 뒤 실패 결과를 낸다`() {
        port.silentReads["brightness"] = 9
        a.restore(RestoreAmbient("s1-9", AmbientValue(70, 20), null)); t.advance(15_000)
        assertTrue(!results().getValue(B).ok && r.rows.isEmpty())
    }

    // ---- 실행기 낮춤 장부: 프로세스가 죽어도 되돌릴 값이 남는다 ----
    private class MemLedger : LedgerStore {
        var e: LedgerEntry? = null
        override fun read() = e
        override fun write(e: LedgerEntry?) { this.e = e?.takeUnless { it.isEmpty } }
    }
    private val ledger = MemLedger()
    /** 실행기를 새로 만든다(프로세스 재시작: 가짜 차와 장부 저장소는 그대로). */
    private fun process() = Ambients(port, t, { t.now }, { r.events += it }, logger(t, r), p, ledger)
    private val BR = AmbientValue(70, 20)
    private val CO = AmbientValue(0xFFFFFF, 0x3050A0)

    @Test fun `장부 낮춘 값이 쓰기 때 장부에 남는다`() {
        process().dim(dim); t.advance(1_000)
        assertEquals(LedgerEntry(BR, CO), ledger.e)
    }

    @Test fun `장부 낮춘 뒤 프로세스가 죽었다 다시 떠도 준비되면 장부로 70과 흰색으로 되돌아오고 장부가 비며 판단 코어로 결과를 보내지 않는다`() {
        process().dim(dim); t.advance(1_000)
        assertEquals(20, port.brightness)
        r.events.clear(); r.rows.clear()
        val restarted = process()          // 앱 재시작: 장면 상태는 없다
        restarted.restoreFromLedger("idle-7"); t.advance(1_000)
        assertEquals(70, port.brightness); assertEquals(0xFFFFFF, port.color?.and(0xFFFFFF))
        assertNull(ledger.e)
        assertTrue(r.of<ControlResult>().isEmpty(), "판단 코어의 명령이 아니라 결과를 보내지 않는다")
        assertEquals(setOf("restore:70", "restore:#FFFFFF"), r.rows.map { it.goal }.toSet())
        assertTrue(r.rows.all { it.cmdId == "idle-7" }, "control_log cmd_id는 명령의 cmdId")
    }

    @Test fun `장부 그사이 운전자가 바꾸면 쓰지 않고 장부만 비운다`() {
        process().dim(dim); t.advance(1_000)
        port.brightness = 55; port.color = 0x00FF00
        r.rows.clear()
        process().restoreFromLedger("idle-7"); t.advance(1_000)
        assertEquals(55, port.brightness); assertEquals(0x00FF00, port.color)
        assertNull(ledger.e); assertTrue(r.rows.isEmpty())
    }

    @Test fun `장부 판단 코어 Boot 되돌림과 겹쳐도 한 번만 쓴다`() {
        process().dim(dim); t.advance(1_000)
        port.writes.clear(); r.events.clear()
        val restarted = process()
        restarted.restoreFromLedger("idle-7")                          // 장부가 첫 일
        restarted.restore(RestoreAmbient("s1-9", BR, CO))              // 같은 시각 판단 코어 Boot 되돌림
        t.advance(2_000)
        assertEquals(1, port.writes.count { it.startsWith("b") }); assertEquals(1, port.writes.count { it.startsWith("c") })
        assertEquals(70, port.brightness); assertNull(ledger.e)
        assertEquals(2, r.of<ControlResult>().size, "판단 코어 몫의 결과는 둘 다(건너뛰어도) 온다")
    }

    // ---- RestoreAmbientLedger가 먼저 진행 중인 낮춤 작업을 버린다 ----
    @Test fun `첫 밝기 읽기 무응답으로 재시도를 기다리는 낮춤은 장부 되돌림이 버려 30초 뒤에도 낮추지 않는다`() {
        port.silentReads["brightness"] = 1; port.auto = AutoColor.UNKNOWN   // 색은 건드리지 않는 차(밝기만 본다)
        val x = process(); x.dim(dim); t.advance(1_000)
        assertTrue(port.writes.isEmpty() && ledger.e == null)
        x.restoreFromLedger("idle-7")        // 장면 끝 Boot: 판단 코어는 Dimming을 버렸다
        t.advance(30_000)
        assertEquals(70, port.brightness); assertTrue(port.writes.none { it.startsWith("b") }, "쓰기 0회: ${port.writes}")
        assertNull(ledger.e)
    }

    @Test fun `낮춤 쓰기가 반영된 직후 장부 되돌림은 낮춤을 닫고 70으로 되돌리며 다시 낮추지 않는다`() {
        val x = process(); x.dim(dim); t.advance(60)      // 쓰기 반영(50ms), 확인 폴링 전
        assertEquals(20, port.brightness); assertEquals(LedgerEntry(BR, CO), ledger.e)
        x.restoreFromLedger("idle-7")
        t.advance(30_000)
        assertEquals(70, port.brightness)
        assertEquals(listOf("b20", "b70"), port.writes.filter { it.startsWith("b") }, "재전송 없음")
        assertTrue(!results().getValue(B).ok, "낮춤 작업은 ok=false로 닫혔다(정상 확인으로 끝나지 않았다)")
    }

    // ---- 종류별 차례 ----
    private val BRR = RestoreAmbient("s1-9", BR, null)

    // 차례는 명령을 받는 즉시 잡는다: 앞 명령이 영역·켜짐을 읽는 동안 뒤 명령이 앞지르지 못한다
    @Test fun `앞 되돌림의 영역 읽기가 무응답인 동안 온 낮춤은 되돌림 뒤에 돌아 조명과 장부가 맞는다`() {
        port.brightness = 55; port.auto = AutoColor.UNKNOWN; port.silentReads["area"] = 1
        val x = process()
        x.restore(RestoreAmbient("s1-9", AmbientValue(70, 20), null))   // 영역 읽기 무응답 → 5초 뒤 재시도
        x.dim(dim)                                                       // 그사이 온 낮춤: 되돌림 뒤에 돈다
        t.advance(15_000)
        assertEquals(20, port.brightness)
        assertEquals(listOf("b20"), port.writes.filter { it.startsWith("b") }, "되돌림이 now(20)==set을 보고 덮지 않는다")
        val res = r.of<ControlResult>().filter { it.control == B }.associateBy { it.cmdId }
        assertTrue(!res.getValue("s1-9").ok); assertTrue(res.getValue("s1-3").ok)
        assertEquals(AmbientValue(55, 20), ledger.e?.brightness)
    }

    @Test fun `머리 작업이 영역을 끝내 못 읽어 닫히면 뒤 작업이 돈다`() {
        port.brightness = 70; port.auto = AutoColor.UNKNOWN; port.silentReads["area"] = 2
        val x = process()
        x.restore(RestoreAmbient("s1-9", BR, null))   // 영역 읽기 두 번 무응답 → 약 10초 뒤 닫힘
        x.dim(dim)
        t.advance(15_000)
        assertTrue(!r.of<ControlResult>().single { it.cmdId == "s1-9" }.ok)
        assertEquals(20, port.brightness); assertTrue(r.of<ControlResult>().single { it.cmdId == "s1-3" && it.control == B }.ok)
    }

    @Test fun `낮춤이 켜짐 읽기를 기다리는 동안 온 되돌림은 낮춤 뒤에 돈다`() {
        port.brightness = 70; port.auto = AutoColor.UNKNOWN; port.silentReads["lit"] = 1
        val x = process()
        x.dim(dim); t.advance(1_000)
        x.restore(RestoreAmbient("s1-9", BR, null))
        t.advance(15_000)
        assertEquals(listOf("b20", "b70"), port.writes.filter { it.startsWith("b") })
        assertEquals(70, port.brightness)
    }

    // ---- 종단: 쓰기 ack 성공 + 확인 읽기 무응답 → ok=false → 판단 코어는 back을 모른다 → 엔진이 낸 RestoreAmbientLedger를 restoreFromLedger로 실행 ----
    @Test fun `쓰기는 성공했는데 확인 읽기만 무응답이면 장부 되돌림이 밝기 70으로 되돌리고 장부를 비운다`() {
        port.auto = AutoColor.UNKNOWN
        val x = process(); x.dim(dim)                   // 차례 시작 스냅숏과 첫 쓰기는 동기로 끝난다(쓰기 ack는 20ms 뒤)
        port.silentReads["brightness"] = 500            // 이후 읽기는 모두 무응답: 확인 폴링·다시 보내기 앞 스냅숏 모두 못 읽음
        t.advance(20_000)
        assertEquals(20, port.brightness)
        assertTrue(!r.of<ControlResult>().single { it.control == B }.ok, "판단 코어는 back에서 뺀다")
        assertEquals(LedgerEntry(BR, null), ledger.e, "쓰기 직전 남긴 장부는 그대로")
        port.silentReads.clear()
        x.restoreFromLedger("idle-7"); t.advance(2_000)   // Dimmed(back 일부) 달래기 끝에서 엔진이 낸 명령
        assertEquals(70, port.brightness); assertNull(ledger.e)
    }

    @Test fun `스위치가 꺼져 건너뛴 종류는 장부가 비어 있어 장부 되돌림이 아무것도 쓰지 않는다`() {
        port.lit = false
        val x = process(); x.dim(dim); t.advance(1_000)
        port.writes.clear()
        x.restoreFromLedger("idle-7"); t.advance(2_000)
        assertTrue(port.writes.isEmpty()); assertNull(ledger.e)
    }

    // ---- 장부는 결과가 정해졌을 때만 지운다(되돌림 쓰기 확인, 또는 지금 값 ≠ set). 못 읽음·쓰기 실패·확인 못 함은 남긴다 ----
    @Test fun `되돌림이 지금 값을 끝내 못 읽으면 장부가 남고 읽기가 회복된 뒤 다시 부르면 70으로 되돌린다`() {
        port.brightness = 20; ledger.e = LedgerEntry(BR, null); port.silentReads["brightness"] = 9
        process().restoreFromLedger("idle-7"); t.advance(15_000)
        assertEquals(LedgerEntry(BR, null), ledger.e); assertEquals(20, port.brightness)
        port.silentReads.clear()
        process().restoreFromLedger("idle-8"); t.advance(2_000)
        assertEquals(70, port.brightness); assertNull(ledger.e)
    }

    @Test fun `되돌림 쓰기가 첫 시도와 재전송 모두 거절되면 장부가 남고 control_log 실패 한 줄 다음 장부 되돌림이 70으로 되돌린다`() {
        port.brightness = 20; ledger.e = LedgerEntry(BR, null); port.failBrightnessWrites = 2
        process().restoreFromLedger("idle-7"); t.advance(15_000)
        assertEquals(LedgerEntry(BR, null), ledger.e); assertEquals(20, port.brightness)
        assertEquals(1, r.rows.size); assertTrue(!r.rows.single().ok)
        process().restoreFromLedger("idle-8"); t.advance(2_000)
        assertEquals(70, port.brightness); assertNull(ledger.e)
    }

    @Test fun `첫 쓰기가 거절된 뒤 다시 보내기 앞 스냅숏을 못 읽으면 장부가 남는다`() {
        port.brightness = 20; ledger.e = LedgerEntry(BR, null); port.failBrightnessWrites = 1
        process().restoreFromLedger("idle-7"); t.advance(1_000)
        port.silentReads["brightness"] = 500   // 확인 폴링 읽기도 무응답이라 다시 보내기 앞 스냅숏까지 계속 못 읽는다
        t.advance(20_000)
        assertEquals(LedgerEntry(BR, null), ledger.e)
    }

    @Test fun `다시 보내기 앞에서 운전자가 55로 바꿨으면 정해진 결과라 장부를 지운다`() {
        port.brightness = 20; ledger.e = LedgerEntry(BR, null); port.failBrightnessWrites = 1
        process().restoreFromLedger("idle-7"); t.advance(1_000)
        port.brightness = 55
        t.advance(15_000)
        assertEquals(55, port.brightness); assertNull(ledger.e)
    }

    @Test fun `판단 코어 RestoreAmbient도 값을 못 읽으면 결과 ok false는 가고 장부는 남는다`() {
        port.brightness = 20; ledger.e = LedgerEntry(BR, null); port.silentReads["brightness"] = 9
        process().restore(RestoreAmbient("s1-9", BR, null)); t.advance(15_000)
        assertTrue(!r.of<ControlResult>().single { it.cmdId == "s1-9" }.ok)
        assertEquals(LedgerEntry(BR, null), ledger.e)
    }

    @Test fun `색 종류도 지금 값을 못 읽으면 장부가 남고 회복 뒤 되돌린다`() {
        port.color = 0x3050A0; ledger.e = LedgerEntry(null, CO); port.silentReads["color"] = 9
        process().restoreFromLedger("idle-7"); t.advance(15_000)
        assertEquals(LedgerEntry(null, CO), ledger.e)
        port.silentReads.clear()
        process().restoreFromLedger("idle-8"); t.advance(2_000)
        assertEquals(0xFFFFFF, port.color?.and(0xFFFFFF)); assertNull(ledger.e)
    }

    // ---- 한 번의 스냅숏으로 판단하고 쓴다(읽기 하나라도 못 읽으면 묶음 전체를 다시 읽는다) ----
    @Test fun `켜짐 읽기를 기다리는 사이 밝기가 10으로 내려가면 묶음 재시도가 10을 읽고 쓰지 않는다`() {
        port.silentReads["lit"] = 1; port.auto = AutoColor.UNKNOWN
        val x = process(); x.dim(dim); t.advance(1_000)
        port.brightness = 10                      // 운전자가 밝기를 내림
        t.advance(15_000)
        assertEquals(10, port.brightness); assertTrue(port.writes.none { it.startsWith("b") }, "${port.writes}")
        assertTrue(!r.of<ControlResult>().single { it.control == B }.ok); assertTrue(r.rows.isEmpty())
    }

    @Test fun `다시 보내기 스냅숏의 켜짐 읽기를 기다리는 사이 밝기가 10으로 바뀌면 다시 보내지 않는다`() {
        port.failBrightnessWrites = 1; port.auto = AutoColor.UNKNOWN
        val x = process(); x.dim(dim); t.advance(1_000)
        port.silentReads["lit"] = 1; port.brightness = 10
        t.advance(15_000)
        assertEquals(1, port.writes.count { it.startsWith("b") }); assertEquals(10, port.brightness)
        assertTrue(!r.of<ControlResult>().single { it.control == B }.ok)
    }

    @Test fun `자동 색 읽기를 기다리는 사이 조명이 꺼지면 묶음 재시도가 꺼짐을 읽고 색을 쓰지 않는다`() {
        port.silentReads["auto"] = 1
        val x = process(); x.dim(dim); t.advance(1_000)
        port.lit = false
        t.advance(15_000)
        assertEquals(0xFFFFFF, port.color?.and(0xFFFFFF)); assertTrue(port.writes.none { it.startsWith("c") }, "${port.writes}")
        assertTrue(!r.of<ControlResult>().single { it.control == C }.ok)
    }

    @Test fun `차례 시작 스냅숏을 기다리는 중 장부 되돌림으로 버려지면 control_log 줄 없이 ok false`() {
        port.silentReads["lit"] = 2
        val x = process(); x.dim(dim); t.advance(1_000)
        x.restoreFromLedger("idle-7")
        t.advance(15_000)
        assertTrue(r.rows.isEmpty(), "시도가 아니라 control_log 줄 0개: ${r.rows}")
        assertTrue(r.of<ControlResult>().all { !it.ok } && r.of<ControlResult>().size == 2)
    }

    // ---- 쓰기 바로 앞(처음·다시 보내기)마다 판단 조건을 다시 본다 ----
    @Test fun `첫 쓰기가 거절된 뒤 스위치가 꺼지면 다시 보내기 앞에서 쓰지 않고 control_log에 실패 한 줄`() {
        port.failBrightnessWrites = 1; port.auto = AutoColor.UNKNOWN
        val x = process(); x.dim(dim); t.advance(1_000)
        port.lit = false
        t.advance(12_000)
        assertEquals(1, port.writes.count { it.startsWith("b") }); assertEquals(70, port.brightness)
        assertTrue(!r.of<ControlResult>().single { it.control == B }.ok)
        val rows = r.rows.filter { it.api == "setAmbientLightBrightness" }
        assertEquals(1, rows.size); assertTrue(!rows.single().ok)
    }

    @Test fun `첫 읽기 재시도 뒤 첫 쓰기 앞에서 꺼짐이면 쓰기 0회 장부 없음 control_log 없음`() {
        port.silentReads["brightness"] = 1; port.auto = AutoColor.UNKNOWN
        val x = process(); x.dim(dim); t.advance(1_000)
        port.lit = false
        t.advance(15_000)
        assertTrue(port.writes.none { it.startsWith("b") }, "${port.writes}"); assertNull(ledger.e)
        assertTrue(r.rows.isEmpty()); assertTrue(!r.of<ControlResult>().single { it.control == B }.ok)
    }

    @Test fun `색 첫 쓰기가 거절된 뒤 자동 색이 켜지면 다시 보내기 앞에서 색을 쓰지 않는다`() {
        port.failColorWrites = 1
        val x = process(); x.dim(dim); t.advance(1_000)
        port.auto = AutoColor.UNKNOWN
        t.advance(12_000)
        assertEquals(1, port.writes.count { it.startsWith("c") }); assertEquals(0xFFFFFF, port.color?.and(0xFFFFFF))
        assertTrue(!r.of<ControlResult>().single { it.control == C }.ok)
    }

    @Test fun `되돌림은 스위치가 꺼져 있어도 before를 쓴다`() {
        port.brightness = 20; port.lit = false
        process().restore(RestoreAmbient("s1-9", BR, null)); t.advance(2_000)
        assertEquals(70, port.brightness)
    }

    // ---- 켜짐·자동 색도 차례가 온 뒤에 읽는다 ----
    @Test fun `앞 되돌림이 재전송을 기다리는 동안 스위치가 꺼지면 차례가 와도 낮춤 밝기를 쓰지 않는다`() {
        port.brightness = 20; port.failBrightnessWrites = 1; port.auto = AutoColor.UNKNOWN
        val x = process()
        x.restore(RestoreAmbient("s1-9", AmbientValue(70, 20), null)); t.advance(1_000)
        x.dim(dim); t.advance(1_000)
        port.lit = false
        t.advance(15_000)
        assertEquals(70, port.brightness)
        assertEquals(listOf("b70", "b70"), port.writes.filter { it.startsWith("b") }, "낮춤 쓰기 없음")
        val res = r.of<ControlResult>().filter { it.control == B }.associateBy { it.cmdId }
        assertTrue(res.getValue("s1-9").ok); assertTrue(!res.getValue("s1-3").ok)
        assertTrue(r.rows.none { it.goal.startsWith("dim:") }, "그 낮춤의 control_log 줄이 없다")
    }

    @Test fun `앞 색 작업이 차례를 잡고 있는 동안 자동 색이 켜지면 차례가 와도 색을 쓰지 않는다`() {
        port.color = 0x3050A0; port.failColorWrites = 1
        val x = process()
        x.restore(RestoreAmbient("s1-9", null, CO)); t.advance(1_000)
        x.dim(dim); t.advance(1_000)
        port.auto = AutoColor.UNKNOWN
        t.advance(15_000)
        assertEquals(0xFFFFFF, port.color?.and(0xFFFFFF))
        assertEquals(2, port.writes.count { it.startsWith("c") }, "되돌림 두 번뿐: ${port.writes}")
        assertTrue(!r.of<ControlResult>().single { it.cmdId == "s1-3" && it.control == C }.ok)
    }

    @Test fun `되돌림 읽기 대기 중 closeAll 뒤 늦은 읽기 응답이 와도 이후 되돌림은 70을 쓴다`() {
        port.brightness = 20; port.holdBrightnessReads = true
        val x = process()
        x.restore(RestoreAmbient("s1-9", BR, null))
        x.closeAll()                                         // Exec.stop
        x.restore(RestoreAmbient("s1-10", BR, null))
        port.held.toList().forEach { it() }                  // 늦은 읽기 응답: 먼저 버려진 c1의 것, 그다음 c2의 것
        port.holdBrightnessReads = false
        t.advance(2_000)
        assertEquals(70, port.brightness); assertEquals(1, port.writes.count { it.startsWith("b") })
        assertTrue(r.of<ControlResult>().single { it.cmdId == "s1-10" }.ok)
    }

    @Test fun `장부 되돌림 첫 쓰기 거절 뒤 재전송을 기다리는 동안 온 낮춤은 되돌린 70을 읽고 20을 쓴다`() {
        port.brightness = 20; ledger.e = LedgerEntry(BR, null); port.failBrightnessWrites = 1
        val x = process()
        x.restoreFromLedger("idle-7"); t.advance(1_000)
        x.dim(dim)
        t.advance(15_000)
        assertEquals(listOf("b70", "b70", "b20"), port.writes.filter { it.startsWith("b") })
        assertEquals(20, port.brightness)
        assertEquals(AmbientValue(70, 20), ledger.e?.brightness, "낮춘 값 20이 아니라 되돌린 70이 before")
        assertTrue(r.of<ControlResult>().single { it.control == B }.ok)
    }

    @Test fun `되돌림 쓰기가 반영되기 전에 온 낮춤은 되돌림이 끝난 뒤 읽는다`() {
        port.brightness = 20; port.brightnessApplyMs = 2_000
        val x = process()
        x.restore(RestoreAmbient("s1-9", BR, null)); t.advance(100)   // 쓰기는 나갔고 반영(2초) 전
        x.dim(dim)
        t.advance(10_000)
        assertEquals(listOf("b70", "b20"), port.writes.filter { it.startsWith("b") })
        assertEquals(20, port.brightness)
        assertTrue(r.of<ControlResult>().filter { it.control == B }.all { it.ok })
    }

    @Test fun `같은 종류 되돌림 둘이 겹치면 하나만 쓰고 뒤 것은 건너뛴다`() {
        port.brightness = 20
        val x = process()
        x.restore(RestoreAmbient("s1-9", BR, null)); x.restore(RestoreAmbient("s1-10", BR, null))
        t.advance(3_000)
        assertEquals(1, port.writes.count { it.startsWith("b") }); assertEquals(70, port.brightness)
        val res = r.of<ControlResult>().associateBy { it.cmdId }
        assertTrue(res.getValue("s1-9").ok); assertTrue(!res.getValue("s1-10").ok)
        assertEquals(listOf(70.0, 70.0), listOf(res.getValue("s1-10").before, res.getValue("s1-10").after))
    }

    @Test fun `차례에서 기다리던 작업이 4배 상한에 걸리면 결과가 나가고 다음 작업이 돈다`() {
        port.brightness = 20; port.silentReads["brightness"] = 4
        val x = process()
        listOf("s1-9", "s1-10", "s1-11").forEach { x.restore(RestoreAmbient(it, BR, null)) }
        t.advance(21_000)
        assertEquals(setOf("s1-9", "s1-10", "s1-11"), r.of<ControlResult>().map { it.cmdId }.toSet())
        assertTrue(r.of<ControlResult>().none { it.ok })
        x.restore(RestoreAmbient("s1-12", BR, null)); t.advance(2_000)
        assertEquals(70, port.brightness)
    }

    @Test fun `밝기와 색 차례는 서로 기다리지 않는다`() {
        port.brightness = 20; port.color = 0x3050A0; port.silentReads["brightness"] = 1
        val x = process()
        x.restore(RestoreAmbient("s1-9", BR, CO)); t.advance(1_000)
        assertEquals(0xFFFFFF, port.color?.and(0xFFFFFF), "색은 밝기 읽기를 기다리지 않고 되돌렸다")
        assertEquals(20, port.brightness, "밝기는 아직 응답을 기다린다")
    }

    @Test fun `closeAll 뒤 새 명령은 바로 돈다`() {
        port.brightness = 20; port.silentReads["brightness"] = 1
        val x = process()
        x.restore(RestoreAmbient("s1-9", BR, null))
        x.closeAll()
        x.restore(RestoreAmbient("s1-10", BR, null)); t.advance(1_000)
        assertEquals(70, port.brightness)
    }

    @Test fun `장부 되돌림 바로 뒤의 새 낮춤은 되돌린 원래 값 70을 before로 잡는다`() {
        process().dim(dim); t.advance(1_000)
        process().restoreFromLedger("idle-7"); t.advance(1_000)
        assertEquals(70, port.brightness)
        process().dim(dim); t.advance(1_000)
        assertEquals(LedgerEntry(BR, CO), ledger.e, "낮춰진 20이 아니라 되돌린 70이 before")
    }

    @Test fun `진행 중인 RestoreAmbient 되돌림은 버려지지 않고 끝까지 가며 한 번만 쓴다`() {
        port.brightness = 20; ledger.e = LedgerEntry(BR, null)
        val x = process()
        x.restore(RestoreAmbient("s1-9", BR, null))        // 장면 끝이 먼저 들어와 쓰는 중
        x.restoreFromLedger("idle-7")
        t.advance(2_000)
        assertEquals(70, port.brightness); assertEquals(1, port.writes.count { it.startsWith("b") })
        assertTrue(r.of<ControlResult>().single { it.cmdId == "s1-9" }.ok)
        assertNull(ledger.e)
    }

    @Test fun `색 종류도 무응답 낮춤을 버린다`() {
        port.auto = AutoColor.OFF; port.silentReads["color"] = 1
        val x = process(); x.dim(dim); t.advance(1_000)
        x.restoreFromLedger("idle-7")
        t.advance(30_000)
        assertEquals(0xFFFFFF, port.color?.and(0xFFFFFF)); assertTrue(port.writes.none { it.startsWith("c") }, "색 쓰기 0회: ${port.writes}")
    }

    @Test fun `장부 되돌림 뒤 장부가 비어 다음 시작에는 아무것도 하지 않는다`() {
        process().dim(dim); t.advance(1_000)
        process().restoreFromLedger("idle-7"); t.advance(1_000)
        port.writes.clear(); port.brightness = 33
        process().restoreFromLedger("idle-8"); t.advance(1_000)
        assertTrue(port.writes.isEmpty()); assertEquals(33, port.brightness)
    }

    @Test fun `장부 판단 코어가 정상으로 되돌리면 장부가 비고 stop으로 닫힌 되돌림은 장부를 남긴다`() {
        val x = process(); x.dim(dim); t.advance(1_000)
        x.restore(RestoreAmbient("s1-9", BR, CO)); t.advance(1_000)
        assertNull(ledger.e)
        val y = process(); y.dim(dim); t.advance(1_000)
        assertEquals(LedgerEntry(BR, CO), ledger.e)
        y.restore(RestoreAmbient("s1-10", BR, CO)); y.closeAll()       // 되돌리다 서비스가 멈춤: 끝까지 가지 못했다
        assertEquals(LedgerEntry(BR, CO), ledger.e)
    }
}
