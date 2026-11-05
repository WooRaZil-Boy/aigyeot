package kr.aigyeot.app.exec

import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.AddWaypoint
import kr.aigyeot.engine.Area.ROW_1_LEFT
import kr.aigyeot.engine.Area.ROW_2_LEFT
import kr.aigyeot.engine.Area.ROW_2_RIGHT
import kr.aigyeot.engine.ControlKind.HVAC_TEMP
import kr.aigyeot.engine.ControlKind.READING_LIGHT
import kr.aigyeot.engine.ControlKind.SEAT_HEAT
import kr.aigyeot.engine.ControlKind.WINDOW_LOCK
import kr.aigyeot.engine.ControlResult
import kr.aigyeot.engine.LowerHvac
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.SetReadingLight
import kr.aigyeot.engine.SetSeatHeat
import kr.aigyeot.engine.SetWindowLock
import kr.aigyeot.engine.WaypointResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 4.2 차 제어·내비와 F19(명세서 7장·14장 반영 확인: 0.1초부터 0.15초 간격, 5초, 재시도 1회), 3.5 ControlResult·WaypointResult
class ControlsTest {
    private class FakeNavi : NaviPort {
        val asked = mutableListOf<String>(); var rests = mutableListOf<RestCandidate?>(); var done: ((Boolean, String) -> Unit)? = null
        override fun addWaypoint(restId: String, rest: RestCandidate?, done: (Boolean, String) -> Unit) { asked += restId; rests += rest; this.done = done }
        var cancels = 0; var throwCancel = false
        override fun cancelWaypoint() { cancels++; if (throwCancel) error("cancel 실패") }
    }

    private val t = FakeTime()
    private val t0 = t.now
    private val r = Rec()
    private val car = FakeControl(t)
    private val navi = FakeNavi()
    private val anseong = RestCandidate("tbt:안성", "안성휴게소", kr.aigyeot.engine.RestKind.REST_AREA, 7.0, true)
    private val c = Controls(car, navi, t, { t.now }, { r.events += it }, logger(t, r), Params()) { id -> anseong.takeIf { it.id == id } }

    @Test fun `열선 끄기는 0_1초부터 0_15초 간격으로 다시 읽어 목표가 되면 ok`() {
        car.values[SEAT_HEAT to ROW_2_LEFT] = 2.0
        c.seatHeat(SetSeatHeat("s1-7", listOf(ROW_2_LEFT), 0))
        assertEquals(listOf(Triple(SEAT_HEAT, ROW_2_LEFT, 0.0)), car.writes)
        t.advance(1_000)
        assertEquals(listOf(0L, 100L, 250L, 400L), car.reads.map { it - t0 }, "바꾸기 전 한 번, 그 뒤 0.1초·0.25초·0.4초(300ms에 반영)")
        assertEquals(ControlResult(t0 + 400, "s1-7", SEAT_HEAT, ROW_2_LEFT, true, 2.0, 0.0), r.events.single())
        val row = r.rows.single()
        assertEquals(listOf("setHvacSeatTemperature", "ROW_2_LEFT", "0", "0"), listOf(row.api, row.target, row.goal, row.actual))
        assertEquals(listOf<Long?>(50L, 400L), listOf(row.ackMs, row.appliedMs))
        assertTrue(row.ok); assertEquals(0, row.retries)
        assertEquals(0, t.pending(), "끝나면 타이머를 남기지 않는다")
    }

    @Test fun `5초 안에 안 바뀌면 한 번 다시 보내고 그래도 안 되면 ok=false`() {
        car.values[WINDOW_LOCK to ROW_2_LEFT] = 0.0
        car.applyAfterMs = null
        c.windowLock(SetWindowLock("s1-8", mapOf(ROW_2_LEFT to true)))
        t.advance(4_999)
        assertEquals(1, car.writes.size)
        t.advance(1)
        assertEquals(2, car.writes.size, "5초에 한 번 다시")
        t.advance(4_999)
        assertTrue(r.events.isEmpty())
        t.advance(1)
        assertEquals(ControlResult(t0 + 10_000, "s1-8", WINDOW_LOCK, ROW_2_LEFT, false, 0.0, 0.0), r.events.single())
        assertEquals(1, r.rows.single().retries)
        assertNull(r.rows.single().appliedMs)
        assertEquals(2, car.writes.size, "재시도는 한 번뿐")
    }

    @Test fun `다시 보낸 뒤 바뀌면 ok이고 retries=1`() {
        car.values[READING_LIGHT to ROW_2_RIGHT] = 1.0
        car.applyAfterMs = null
        c.readingLight(SetReadingLight("s1-9", listOf(ROW_2_RIGHT), false))
        t.advance(5_000)
        car.values[READING_LIGHT to ROW_2_RIGHT] = 0.0
        t.advance(500)
        val e = r.of<ControlResult>().single()
        assertTrue(e.ok); assertEquals(1.0, e.before); assertEquals(0.0, e.after)
        assertEquals(1, r.rows.single().retries)
    }

    @Test fun `거절된 명령은 값이 우연히 같아도 실패이고 5초 뒤 한 번 다시 보내 10초에 닫는다`() {
        car.values[READING_LIGHT to ROW_2_LEFT] = 1.0
        car.ackOk = false; car.applyAfterMs = 10
        c.readingLight(SetReadingLight("s1-9", listOf(ROW_2_LEFT), false))
        t.advance(200)
        assertEquals(0.0, car.values[READING_LIGHT to ROW_2_LEFT], "값은 목표와 같아졌다(우연히)")
        assertTrue(r.events.isEmpty(), "거절만으로 끝내지 않고 5초를 기다린다(계약 4.2)")
        assertEquals(1, car.writes.size, "거절 즉시 다시 보내지 않는다")
        t.advance(4_799)
        assertEquals(1, car.writes.size)
        t.advance(1)
        assertEquals(2, car.writes.size, "5초에 한 번 다시")
        t.advance(4_999)
        assertTrue(r.events.isEmpty())
        t.advance(1)
        val e = r.of<ControlResult>().single()
        assertTrue(!e.ok, "주행 중 독서등 50001 같은 거절")
        assertEquals(t0 + 10_000, e.at)
        assertEquals(2, car.writes.size)
    }

    @Test fun `쓰기와 경유지 호출이 던지면 거절·실패로 한 번만 닫는다`() {
        car.values[READING_LIGHT to ROW_2_LEFT] = 1.0
        car.throwWrite = true
        c.readingLight(SetReadingLight("s1-9", listOf(ROW_2_LEFT), false))
        t.advance(30_000)
        assertTrue(!r.of<ControlResult>().single().ok)
        val nv = object : NaviPort {
            override fun addWaypoint(restId: String, rest: RestCandidate?, done: (Boolean, String) -> Unit) = error("navi 실패")
            override fun cancelWaypoint() {}
        }
        val c2 = Controls(car, nv, t, { t.now }, { r.events += it }, logger(t, r), Params())
        c2.waypoint(AddWaypoint("s1-12", "tbt:안성"))
        assertEquals(WaypointResult(t.now, "s1-12", "tbt:안성", false), r.events.last())
        assertEquals("error", r.rows.last().actual)
        t.advance(30_000)
        assertEquals(1, r.of<WaypointResult>().size)
    }

    @Test fun `뒷창 잠금은 영역마다 before를 싣는다`() {
        car.values[WINDOW_LOCK to ROW_2_LEFT] = 1.0
        car.values[WINDOW_LOCK to ROW_2_RIGHT] = 0.0
        c.windowLock(SetWindowLock("s1-10", mapOf(ROW_2_LEFT to true, ROW_2_RIGHT to true)))
        t.advance(1_000)
        val got = r.of<ControlResult>().associateBy { it.area }
        assertEquals(1.0, got.getValue(ROW_2_LEFT).before)
        assertEquals(0.0, got.getValue(ROW_2_RIGHT).before)
        assertTrue(got.values.all { it.ok && it.after == 1.0 && it.cmdId == "s1-10" })
        assertEquals(2, r.rows.size)
    }

    @Test fun `공조는 지금 설정 온도 + deltaC를 목표로 한다`() {
        car.values[HVAC_TEMP to ROW_1_LEFT] = 24.0
        c.lowerHvac(LowerHvac("s1-11", listOf(ROW_1_LEFT), -2.0))
        assertEquals(22.0, car.writes.single().third)
        t.advance(1_000)
        assertEquals(ControlResult(t0 + 400, "s1-11", HVAC_TEMP, ROW_1_LEFT, true, 24.0, 22.0), r.events.single())
        assertEquals("setHvacTemperature", r.rows.single().api)
    }

    @Test fun `공조 보통 22에서 20으로`() {
        car.values[HVAC_TEMP to ROW_1_LEFT] = 22.0
        c.lowerHvac(LowerHvac("s1-11", listOf(ROW_1_LEFT), -2.0))
        t.advance(1_000)
        assertEquals(20.0, car.writes.single().third)
        assertEquals(ControlResult(t0 + 400, "s1-11", HVAC_TEMP, ROW_1_LEFT, true, 22.0, 20.0), r.events.single())
    }

    @Test fun `공조 하한에 걸리면 최솟값까지만 낮춘다 16에서 15로`() {
        car.values[HVAC_TEMP to ROW_1_LEFT] = 16.0
        c.lowerHvac(LowerHvac("s1-11", listOf(ROW_1_LEFT), -2.0))
        t.advance(1_000)
        assertEquals(15.0, car.writes.single().third)
        assertEquals(ControlResult(t0 + 400, "s1-11", HVAC_TEMP, ROW_1_LEFT, true, 16.0, 15.0), r.events.single())
    }

    @Test fun `공조가 이미 최솟값이면 쓰지 않고 ok=false에 before=after`() {
        car.values[HVAC_TEMP to ROW_1_LEFT] = 15.0
        c.lowerHvac(LowerHvac("s1-11", listOf(ROW_1_LEFT), -2.0))
        t.advance(Params().verifyTimeoutMs * 3)
        assertTrue(car.writes.isEmpty())
        assertEquals(ControlResult(t0, "s1-11", HVAC_TEMP, ROW_1_LEFT, false, 15.0, 15.0), r.events.single())
        val row = r.rows.single()
        assertEquals(listOf("setHvacTemperature", "ROW_1_LEFT", "15", "15"), listOf(row.api, row.target, row.goal, row.actual))
        assertTrue(!row.ok)
    }

    @Test fun `최솟값을 못 읽으면 Params hvacMinC를 쓴다`() {
        car.minC = null
        car.values[HVAC_TEMP to ROW_1_LEFT] = 16.0
        c.lowerHvac(LowerHvac("s1-11", listOf(ROW_1_LEFT), -2.0))
        t.advance(1_000)
        assertEquals(Params().hvacMinC, car.writes.single().third)
    }

    @Test fun `설정 온도를 읽지 못하면 보내지 않고 실패`() {
        car.readOk = false
        c.lowerHvac(LowerHvac("s1-11", listOf(ROW_1_LEFT), -2.0))
        assertEquals(ControlResult(t0, "s1-11", HVAC_TEMP, ROW_1_LEFT, false, null, null), r.events.single())
        t.advance(Params().verifyTimeoutMs * 3)
        assertTrue(car.writes.isEmpty())
        assertEquals(1, r.events.size)
        assertEquals("unknown", r.rows.single().goal)
    }

    @Test fun `경유지는 결과 콜백으로만 성공이다`() {
        c.waypoint(AddWaypoint("s1-12", "tbt:안성"))
        assertEquals(listOf("tbt:안성"), navi.asked)
        assertEquals(listOf<RestCandidate?>(anseong), navi.rests, "경로 상태의 쉼터(이름·거리)를 함께 넘긴다")
        assertTrue(r.events.isEmpty(), "호출만으로는 성공이 아니다(C75)")
        t.advance(1_200); navi.done!!(true, "added")
        assertEquals(WaypointResult(t.now, "s1-12", "tbt:안성", true), r.events.single())
        assertEquals(listOf("addWaypoint", "tbt:안성", "added"), r.rows.single().let { listOf(it.api, it.target, it.actual) })
        navi.done!!(false, "late")
        assertEquals(1, r.events.size, "늦은 콜백은 버린다")
    }

    @Test fun `경유지 콜백이 20초 안에 없으면 실패하고 다시 보내지 않는다`() {
        c.waypoint(AddWaypoint("s1-12", "tbt:안성"))
        assertEquals(20_000L, Params().waypointTimeoutMs)
        t.advance(19_999)
        assertTrue(r.events.isEmpty(), "10초(옛 한도)가 지나도 기다린다")
        t.advance(1)
        assertEquals(WaypointResult(t.now, "s1-12", "tbt:안성", false), r.events.single())
        assertEquals("timeout", r.rows.single().actual)
        assertEquals(1, navi.asked.size)
    }

    @Test fun `시간 초과로 닫으면 navi에 요청 취소를 알리고 늦은 콜백은 결과를 또 내지 않는다`() {
        c.waypoint(AddWaypoint("s1-12", "tbt:안성"))
        assertEquals(0, navi.cancels, "조건: 아직 열려 있다")
        t.advance(19_999)
        assertTrue(r.events.isEmpty())
        t.advance(1)
        assertEquals(WaypointResult(t.now, "s1-12", "tbt:안성", false), r.events.single())
        assertEquals(1, navi.cancels)
        navi.done!!(true, "added")                       // 11초에 늦게 도착한 위치·알림
        assertEquals(1, r.events.size)
    }

    @Test fun `성공해도 닫을 때 취소를 부르고 취소가 던져도 결과는 나간다`() {
        navi.throwCancel = true
        c.waypoint(AddWaypoint("s1-12", "tbt:안성"))
        navi.done!!(true, "added")
        assertEquals(WaypointResult(t.now, "s1-12", "tbt:안성", true), r.events.single())
        assertEquals(1, navi.cancels)
    }

    @Test fun `closeAll은 진행 중인 제어 작업과 경유지를 한 번씩 닫고 재시도를 막는다`() {
        car.values[WINDOW_LOCK to ROW_2_LEFT] = 1.0
        car.applyAfterMs = null
        c.windowLock(SetWindowLock("s1-8", mapOf(ROW_2_LEFT to false)))
        c.waypoint(AddWaypoint("s1-12", "tbt:안성"))
        t.advance(2_000)
        assertTrue(r.events.isEmpty() && car.writes.size == 1 && navi.cancels == 0, "조건: 둘 다 진행 중")
        c.closeAll("stop")
        assertEquals(listOf(ControlResult(t.now, "s1-8", WINDOW_LOCK, ROW_2_LEFT, false, 1.0, 1.0), WaypointResult(t.now, "s1-12", "tbt:안성", false)), r.events)
        assertEquals(1, navi.cancels)
        assertEquals(0, t.pending(), "타이머 취소")
        t.advance(30_000)
        assertEquals(2, r.events.size)
        assertEquals(1, car.writes.size, "5초 재시도가 없다")
        c.closeAll("stop")
        assertEquals(2, r.events.size, "두 번 불러도 안전")
    }

    @Test fun `새 경유지 요청이 오면 앞 요청은 실패로 닫는다`() {
        c.waypoint(AddWaypoint("s1-12", "tbt:안성"))
        assertEquals(0, navi.cancels, "조건")
        c.waypoint(AddWaypoint("s1-13", "tbt:망향"))
        assertEquals(1, navi.cancels, "앞 요청을 닫으며 취소")
        assertEquals(WaypointResult(t.now, "s1-12", "tbt:안성", false), r.events.single())
        navi.done!!(true, "added")
        assertEquals(WaypointResult(t.now, "s1-13", "tbt:망향", true), r.events.last())
    }
}
