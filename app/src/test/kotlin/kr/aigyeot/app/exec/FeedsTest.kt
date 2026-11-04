package kr.aigyeot.app.exec

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.RouteState
import kr.aigyeot.engine.TurnSignal
import kr.aigyeot.engine.VehicleSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 3.3 차량 사건(GearChanged·RearDoor·SeatHeat·VehicleSample·RouteState)과 3.4 CallMode로 바꾸는 규칙
class FeedsTest {
    private val t = FakeTime()
    private val events = mutableListOf<Event>()

    @Test fun `기어는 P·R·N·D로, 수동 단은 D로, 모름은 버린다`() {
        assertEquals(Gear.P, gearOf(VehicleGear.GEAR_PARK))
        assertEquals(Gear.R, gearOf(VehicleGear.GEAR_REVERSE))
        assertEquals(Gear.N, gearOf(VehicleGear.GEAR_NEUTRAL))
        assertEquals(Gear.D, gearOf(VehicleGear.GEAR_DRIVE))
        assertEquals(Gear.D, gearOf(VehicleGear.GEAR_3))
        assertNull(gearOf(VehicleGear.GEAR_UNKNOWN))
    }

    @Test fun `방향지시등과 통화 모드`() {
        assertEquals(TurnSignal.LEFT, turnOf(TurnSignalValue.LEFT))
        assertEquals(TurnSignal.RIGHT, turnOf(TurnSignalValue.RIGHT))
        assertEquals(TurnSignal.NONE, turnOf(TurnSignalValue.OFF))
        assertTrue(inCall(2) && inCall(3), "MODE_IN_CALL·MODE_IN_COMMUNICATION")
        assertFalse(inCall(0) || inCall(1), "NORMAL·RINGTONE")
    }

    @Test fun `TBT의 휴게소·졸음쉼터만 쉼터 후보로 km로 바꾸고 수유실은 모르면 없음`() {
        val tbt = listOf(
            TBTInfo(TurnType.TURN_RIGHT, 300.0, "우회전"),
            TBTInfo(TurnType.TURN_ENTER_SERVICEAREA, 7_400.0, "안성휴게소"),
            TBTInfo(TurnType.TURN_RESTAREA, 12_000.0, "망향 졸음쉼터"),
            TBTInfo(TurnType.TURN_ENTER_SERVICEAREA, 9_000.0, " "),
        )
        assertEquals(listOf(
            RestCandidate("tbt:rest:안성휴게소", "안성휴게소", RestKind.REST_AREA, 7.4, false),
            RestCandidate("tbt:shelter:망향졸음쉼터", "망향 졸음쉼터", RestKind.DROWSY_SHELTER, 12.0, false),
        ), restsOf(tbt) { false })
        assertTrue(restsOf(tbt) { it == "안성휴게소" }.first().hasNursingRoom, "수유실 표가 있으면 그 값")
        assertFalse(restsOf(tbt) { true }.last().hasNursingRoom, "졸음쉼터에는 수유실이 없다")
    }

    @Test fun `같은 설명의 졸음쉼터와 휴게소는 id가 다르다`() {
        val tbt = listOf(TBTInfo(TurnType.TURN_RESTAREA, 5_000.0, "오창(하남)"), TBTInfo(TurnType.TURN_ENTER_SERVICEAREA, 8_500.0, "오창(하남)"))
        val got = restsOf(tbt) { true }
        assertEquals(listOf(RestKind.DROWSY_SHELTER, RestKind.REST_AREA), got.map { it.kind }, "조건: 같은 이름의 두 종류")
        assertEquals(2, got.map { it.name }.toSet().size + 1, "이름은 같다")
        assertEquals(2, got.map { it.id }.toSet().size, "id는 종류로 갈린다: ${got.map { it.id }}")
    }

    @Test fun `RouteState는 받은 것이 바뀔 때만 내고 목적지가 없으면 남은 시간이 없다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.remain(900)
        assertEquals(RouteState(t.now, false, null, emptyList()), events.single(), "경로 안내가 아니면 남은 시간 없음")
        f.routing(true)
        assertEquals(RouteState(t.now, true, 900, emptyList()), events.last())
        t.advance(1_000); f.remain(900)
        assertEquals(2, events.size, "같은 값이면 다시 내지 않는다")
        f.routing(false)
        assertEquals(RouteState(t.now, false, null, emptyList()), events.last())
        f.routing(true)
        assertNull((events.last() as RouteState).remainingSec, "안내가 끝나면 옛 남은 시간을 버린다")
    }

    @Test fun `안내가 끝나거나 취소되면 옛 쉼터도 비운다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.routing(true)
        f.rests(listOf(RestCandidate("tbt:안성", "안성", RestKind.REST_AREA, 7.0, true)))
        assertEquals(1, (events.last() as RouteState).rests.size, "조건: 쉼터가 들어 있다")
        f.routing(false)
        assertEquals(RouteState(t.now, false, null, emptyList()), events.last())
        f.routing(true)
        assertTrue((events.last() as RouteState).rests.isEmpty(), "새 TBT가 오기 전에는 비어 있다")
    }

    @Test fun `VehicleSample은 m_s를 km_h로 바꾸고 최대 10Hz로 낸다`() {
        val s = Sampler({ t.now }, { events += it })
        s.steer(1.5f)
        assertTrue(events.isEmpty(), "속도를 받기 전에는 0 km/h를 지어내지 않는다")
        s.speedMps(10f)
        assertEquals(VehicleSample(t.now, 36.0, false, 1.5, TurnSignal.NONE), events.single())
        t.advance(50); s.brake(true)
        assertEquals(1, events.size, "100ms 안에는 다시 내지 않는다")
        t.advance(60); s.turn(TurnSignal.LEFT)
        assertEquals(VehicleSample(t.now, 36.0, true, 1.5, TurnSignal.LEFT), events.last())
        repeat(100) { t.advance(10); s.steer(it.toFloat()) }       // 1초에 100번 들어와도
        assertTrue(events.size in 10..12, "1초에 10번 안팎: ${events.size}")
    }

    @Test fun `같은 값이 거듭 오면 한 번만 낸다`() {
        val c = Changed()
        val e = { GearChanged(t.now, Gear.P) }
        c.emit("gear", Gear.P, e) { events += it }
        c.emit("gear", Gear.P, e) { events += it }
        c.emit("gear", Gear.D, { GearChanged(t.now, Gear.D) }) { events += it }
        assertEquals(listOf(Gear.P, Gear.D), events.map { (it as GearChanged).gear })
    }

    @Test fun `노래 이득은 −30 LUFS로 맞추고 정점이 −1 dBFS를 넘으면 peak_limit`() {
        val p = Params()
        val (g, r) = SongLoudness.gainFor(-24.0, 0.5, p)
        assertNull(r); assertEquals(0.5012, g!!, 1e-3)              // 6 dB 낮춤
        assertEquals(null to "peak_limit", SongLoudness.gainFor(-40.0, 0.5, p), "10 dB 올리면 정점 1.58")
        assertEquals(null to "silent", SongLoudness.gainFor(null, 0.1, p))
        assertEquals(null to "silent", SongLoudness.gainFor(-30.0, 0.0, p))
        assertEquals(1.0, SongLoudness.gainFor(-30.0, 0.8, p).first!!, 1e-9)
        assertEquals(null to "peak_limit", SongLoudness.gainFor(-30.0, 0.9, p), "−1 dBFS = 0.891")
    }

    @Test fun `노래 표본에 이득을 곱해도 16비트 범위를 넘지 않는다`() {
        val s = shortArrayOf(1000, -1000, 32767, -32768)
        SongLoudness.scale(s, 4, 0.5)
        assertEquals(listOf<Short>(500, -500, 16384, -16384), s.toList())
        SongLoudness.scale(s, 4, 3.0)
        assertEquals(listOf<Short>(1500, -1500, 32767, -32768), s.toList())
    }
}
