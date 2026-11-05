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
            RestCandidate("tbt:rest:안성휴게소", "안성휴게소", RestKind.REST_AREA, 7.7, false),
            RestCandidate("tbt:shelter:망향졸음쉼터", "망향 졸음쉼터", RestKind.DROWSY_SHELTER, 19.7, false),
        ), restsOf(tbt) { false })
        assertTrue(restsOf(tbt) { it == "안성휴게소" }.first().hasNursingRoom, "수유실 표가 있으면 그 값")
        assertFalse(restsOf(tbt) { true }.last().hasNursingRoom, "졸음쉼터에는 수유실이 없다")
    }


    // 1105d 실측 표 1~16번(navi_tbt_1105d.txt 1절). 거리는 m
    private val busan = listOf(
        TurnType.TURN_LEFT to 80 to "기업지원허브 방면", TurnType.TURN_RIGHT to 380 to "판교IC 방면", TurnType.TURN_AVOID_UNDER_R to 730 to "판교IC 방면",
        TurnType.TURN_ENTER_IC_R to 1700 to "판교IC - 부산 방면", TurnType.TURN_ENTER_SERVICEAREA to 2600 to "하이패스센터", TurnType.TURN_TOLL to 1000 to "서울톨게이트",
        TurnType.TURN_KEEP_R to 7700 to "원주,인천 방면", TurnType.TURN_KEEP_L to 140 to "원주 방면", TurnType.TURN_RESTAREA to 3800 to "용인졸음쉼터",
        TurnType.TURN_DEVIDE_MERGE to 2200 to "영동고속도로", TurnType.TURN_ENTER_SERVICEAREA to 8200 to "용인휴게소", TurnType.TURN_DEVIDE_MERGE to 11000 to "영동고속도로",
        TurnType.TURN_ENTER_SERVICEAREA to 1700 to "덕평휴게소", TurnType.TURN_DEVIDE_MERGE to 3600 to "영동고속도로", TurnType.TURN_RESTAREA to 3900 to "이천졸음쉼터",
        TurnType.TURN_ENTER_SERVICEAREA to 9200 to "여주휴게소",
    ).map { (a, name) -> TBTInfo(a.first, a.second.toDouble(), name) }

    @Test fun `쉼터 거리는 앞 항목 거리를 모두 더한 값이고 하이패스센터는 후보가 아니되 거리는 더한다`() {
        val got = restsOf(busan) { false }.associate { it.name to it.distanceKm }
        assertEquals(listOf("용인졸음쉼터", "용인휴게소", "덕평휴게소", "이천졸음쉼터", "여주휴게소"), got.keys.toList(), "C115: 하이패스센터 제외")
        assertEquals(18.1, got.getValue("용인졸음쉼터"), 0.05, "하이패스센터 2.6km는 누적에 들어 있다")
        assertEquals(28.5, got.getValue("용인휴게소"), 0.05)
        assertEquals(41.2, got.getValue("덕평휴게소"), 0.05)
        assertEquals(57.9, got.getValue("여주휴게소"), 0.05)
    }

    @Test fun `안내 시작 때 끝 알림이 섞여도 ROUTE_DRIVE 상태가 남는다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.driving(16_200)                      // DrivingInfo. isRouting·끝 알림은 PleosNavi가 넘기지 않는다
        f.routing(true)                        // onNavigationStatus(ROUTE_DRIVE)
        assertEquals(RouteState(t.now, true, 16_200, emptyList()), events.last())
    }

    @Test fun `앱을 다시 띄워 상태 알림 없이 TBT나 목적지만 오면 안내 중으로 본다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.driving(900)
        assertEquals(RouteState(t.now, true, 900, emptyList()), events.last(), "상태를 받은 적이 없으면 TBT·목적지로 참")
        f.routing(false)
        assertEquals(RouteState(t.now, false, null, emptyList()), events.last(), "진짜 끝")
        f.driving(900)
        assertFalse((events.last() as RouteState).hasDestination, "상태를 받은 뒤에는 TBT가 다시 참으로 만들지 못한다")
    }

    @Test fun `같은 설명의 졸음쉼터와 휴게소는 id가 다르다`() {
        val tbt = listOf(TBTInfo(TurnType.TURN_RESTAREA, 5_000.0, "오창(하남)휴게소"), TBTInfo(TurnType.TURN_ENTER_SERVICEAREA, 8_500.0, "오창(하남)휴게소"))
        val got = restsOf(tbt) { true }
        assertEquals(listOf(RestKind.DROWSY_SHELTER, RestKind.REST_AREA), got.map { it.kind }, "조건: 같은 이름의 두 종류")
        assertEquals(2, got.map { it.name }.toSet().size + 1, "이름은 같다")
        assertEquals(2, got.map { it.id }.toSet().size, "id는 종류로 갈린다: ${got.map { it.id }}")
    }

    @Test fun `RouteState는 받은 것이 바뀔 때만 내고 목적지가 없으면 남은 시간이 없다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.routing(false); f.driving(900)
        assertEquals(RouteState(t.now, false, null, emptyList()), events.single(), "경로 안내가 아니면 남은 시간 없음")
        f.routing(true)
        assertEquals(RouteState(t.now, true, 900, emptyList()), events.last())
        t.advance(1_000); f.driving(900)
        assertEquals(2, events.size, "같은 값이면 다시 내지 않는다")
        f.routing(false)
        assertEquals(RouteState(t.now, false, null, emptyList()), events.last())
        f.routing(true)
        assertNull((events.last() as RouteState).remainingSec, "안내가 끝나면 옛 남은 시간을 버린다")
    }

    @Test fun `안내가 끝나거나 취소되면 받아 둔 쉼터를 비우고 그 뒤 온 TBT는 안내 중이 되면 낸다`() {
        val rest = RestCandidate("tbt:안성", "안성", RestKind.REST_AREA, 7.0, true)
        val f = RouteFeed({ t.now }, { events += it })
        f.routing(true)
        f.rests(listOf(rest))
        assertEquals(1, (events.last() as RouteState).rests.size, "조건: 쉼터가 들어 있다")
        f.routing(false)
        assertEquals(RouteState(t.now, false, null, emptyList()), events.last(), "안내 중 -> FREE_DRIVE: 쉼터·남은 시간이 빈다")
        f.routing(true)
        assertTrue((events.last() as RouteState).rests.isEmpty(), "다시 안내 중이 돼도 옛 목록은 나오지 않는다")
        f.routing(false)
        f.rests(listOf(rest))                  // 경유지: FREE_DRIVE -> 새 TBT -> ROUTE_DRIVE
        assertTrue((events.last() as RouteState).rests.isEmpty(), "거짓 동안에는 내지 않는다")
        f.routing(true)
        assertEquals(1, (events.last() as RouteState).rests.size, "거짓 뒤에 온 TBT는 저장했다가 안내 중이 되면 낸다")
    }

    @Test fun `상태를 받은 적 없을 때 온 TBT는 driving이나 initial로 참이 되면 나온다`() {
        val rest = RestCandidate("tbt:안성", "안성", RestKind.REST_AREA, 7.0, true)
        val a = RouteFeed({ t.now }, { events += it })
        a.rests(listOf(rest)); a.driving(900)
        assertEquals(1, (events.last() as RouteState).rests.size, "driving")
        val b = RouteFeed({ t.now }, { events += it })
        b.rests(listOf(rest)); b.initial(900)
        assertEquals(1, (events.last() as RouteState).rests.size, "initial")
    }

    @Test fun `상태를 받은 적이 없을 때 TBT만 와도 안내 중이 아니고 쉼터도 내지 않는다(1105e)`() {
        val f = RouteFeed({ t.now }, { events += it })
        // 재시작 뒤 내비가 끝난 경로의 TBT 목록을 돌려준다: PleosNavi는 TBT로 alive()를 부르지 않고 rests만 넘긴다
        f.rests(listOf(RestCandidate("tbt:오산", "오산졸음쉼터", RestKind.DROWSY_SHELTER, 41.4, false)))
        assertEquals(RouteState(t.now, false, null, emptyList()), events.last())
    }

    @Test fun `DrivingInfo 남은 시간이 0이면 안내 중이 아니고 0보다 크면 안내 중이다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.driving(0)                           // 끝날 때 destination은 남고 duration=0
        assertEquals(RouteState(t.now, false, null, emptyList()), events.last(), "0이면 alive 아님, 남은 시간 null")
        f.driving(16_560)
        assertEquals(RouteState(t.now, true, 16_560, emptyList()), events.last())
        f.driving(0)
        assertNull((events.last() as RouteState).remainingSec, "안내 중이어도 0 이하는 null")
        f.driving(null)
        f.driving(-5)
        assertNull((events.last() as RouteState).remainingSec)
    }

    @Test fun `다시 구독하면 앞 회차의 거짓 상태를 잊고 남은 시간 0보다 큰 값으로 복원한다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.routing(false)
        f.initial(900)
        assertFalse((events.last() as RouteState).hasDestination, "상태를 받은 뒤에는 복원이 무시된다")
        f.forget()                             // PleosNavi.start(): 새 구독 회차
        f.initial(900)
        assertEquals(RouteState(t.now, true, 900, emptyList()), events.last())
    }

    @Test fun `getDestinationInfo 복원은 상태를 받은 적이 없고 0보다 클 때만 한다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.initial(0)                           // 끝난 뒤 DestinationInfo는 모두 0
        assertTrue(events.isEmpty() || !(events.last() as RouteState).hasDestination, "0이면 아무것도 하지 않는다")
        f.routing(true); f.driving(600)
        f.initial(900)
        assertEquals(600, (events.last() as RouteState).remainingSec, "상태를 받은 뒤에는 DrivingInfo 값이 남는다")
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
