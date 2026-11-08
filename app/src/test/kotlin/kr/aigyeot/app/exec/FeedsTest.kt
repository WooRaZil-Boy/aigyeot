package kr.aigyeot.app.exec

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
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

    @Test fun `기어 값은 4=P·8=D·2=R·1=N이고 그 밖은 버린다`() {
        assertEquals(Gear.P, gearOf(4))
        assertEquals(Gear.D, gearOf(8))
        assertEquals(Gear.R, gearOf(2))
        assertEquals(Gear.N, gearOf(1))
        assertNull(gearOf(0))
        assertNull(gearOf(16))
        assertNull(gearOf(null))
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
            RestCandidate("tbt:rest:안성휴게소", "안성휴게소", RestKind.REST_AREA, 7.7, false, addable = false),   // C304: 안성은 양방향 두 줄
            RestCandidate("tbt:shelter:망향졸음쉼터", "망향 졸음쉼터", RestKind.DROWSY_SHELTER, 19.7, false, addable = false),   // 자료에 없으면 넣을 수 없다
        ), restsOf(tbt) { false })
        assertTrue(restsOf(tbt) { it == "안성휴게소" }.first().hasNursingRoom, "수유실 표가 있으면 그 값")
        assertFalse(restsOf(tbt) { true }.last().hasNursingRoom, "졸음쉼터에는 수유실이 없다")
    }

    // C353: 경유지(TURN_WAY) 앞 쉼터는 넣을 수 없다. 이름은 둘 다 한 줄로 정해져(placeable) 순서만 갈린다
    private fun rest(name: String) = TBTInfo(TurnType.TURN_ENTER_SERVICEAREA, 1_000.0, name)
    private fun way() = TBTInfo(TurnType.TURN_WAY, 2_000.0, "경유지")

    @Test fun c353_restBeforeWay_notAddable_afterWay_addable() {
        val got = restsOf(listOf(rest("덕평휴게소"), way(), rest("안성휴게소(부산방향)"))) { false }
        assertEquals(listOf(false, true), got.map { it.addable })
    }

    @Test fun c353_twoWays_onlyAfterLastAddable() {
        val got = restsOf(listOf(rest("덕평휴게소"), way(), rest("안성휴게소(부산방향)"), way(), rest("안성휴게소(서울방향)"))) { false }
        assertEquals(listOf(false, false, true), got.map { it.addable })
    }

    @Test fun c353_noWay_unchanged() {
        assertEquals(listOf(true, true), restsOf(listOf(rest("덕평휴게소"), rest("안성휴게소(부산방향)"))) { false }.map { it.addable })
    }

    @Test fun c353_wayDistanceStillAccumulates() {
        val withWay = restsOf(listOf(way(), rest("덕평휴게소"))) { false }.single().distanceKm
        assertEquals(3.0, withWay, 1e-9, "TURN_WAY 2km + 1km")
        assertEquals(1.0, restsOf(listOf(rest("덕평휴게소"))) { false }.single().distanceKm, 1e-9)
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

    @Test fun `reemit은 같은 값이어도 마지막 경로를 다시 내고 낸 적이 없으면 내지 않는다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.reemit(); assertTrue(events.isEmpty())
        f.driving(900); f.driving(900)
        assertEquals(1, events.size, "같은 값은 억제된다")
        f.reemit()
        assertEquals(2, events.size); assertEquals(events[0], events[1])
    }

    @Test fun `재구독 뒤 새 값이 오기 전의 reemit은 옛 경로를 내지 않고 같은 구독 안에서는 마지막 값을 낸다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.driving(900)
        f.forget(); events.clear()
        f.reemit(); assertTrue(events.isEmpty(), "새 내비 응답 전에는 옛 경로를 내지 않는다")
        f.driving(600); events.clear()
        f.reemit()
        assertEquals(600, (events.single() as RouteState).remainingSec)
    }

    private val restA = listOf(RestCandidate("tbt:rest:A휴게소", "A휴게소", RestKind.REST_AREA, 30.0, false))

    // ---- C264: 안내 중이 아니던 상태에서 안내 중으로 바뀔 때 옛 TBT를 버리고 다시 요청 ----
    private val restB = listOf(RestCandidate("tbt:rest:B휴게소", "B휴게소", RestKind.REST_AREA, 40.0, true))

    @Test fun c264_endedRouteTbtDropped_requestedOnce_newListFills() {
        var asked = 0
        val f = RouteFeed({ t.now }, { events += it }, { asked++ })
        f.routing(true); f.driving(600, 100000); f.rests(restA); f.routing(false)   // A가 끝남
        f.rests(restA)                                                           // 내비가 끝난 경로 TBT를 다시 줌(C116)
        asked = 0; events.clear()
        f.routing(true); f.driving(9000, 150000)
        assertEquals(1, asked, "안내 중으로 바뀔 때 한 번만 다시 요청")
        assertTrue((events.last() as RouteState).rests.isEmpty(), "A 쉼터가 B 시간·거리와 합쳐지지 않는다")
        f.rests(restB)
        assertEquals(restB, (events.last() as RouteState).rests)
        f.routing(true); f.driving(8000, 140000); assertEquals(1, asked, "이미 안내 중이면 다시 요청하지 않는다")
        f.rests(restA); assertEquals(restA, (events.last() as RouteState).rests, "안내 중 TBT 갱신은 그대로 바꾼다")
    }

    @Test fun c264_driving_orInitial_fromNull_isTheSameTransition() {
        var asked = 0
        val a = RouteFeed({ t.now }, { events += it }, { asked++ })
        a.rests(restA); a.driving(600); assertEquals(1, asked); assertTrue((events.last() as RouteState).rests.isEmpty())
        val b = RouteFeed({ t.now }, { events += it }, { asked++ })
        b.rests(restA); b.initial(600); assertEquals(2, asked); assertTrue((events.last() as RouteState).rests.isEmpty())
    }

    @Test fun c264_restartOrder_tbtThenStatus_refilledByRequest() {
        var asked = 0
        val f = RouteFeed({ t.now }, { events += it }, { asked++ })
        f.routing(true); f.rests(restA)
        f.forget(); events.clear(); asked = 0
        f.rests(restA)               // 재시작 응답: 끝난 경로 TBT가 먼저
        f.routing(true)              // 상태 응답
        assertEquals(1, asked); f.driving(9000, 150000)
        assertTrue((events.last() as RouteState).rests.isEmpty())
        f.rests(restB); assertEquals(restB, (events.last() as RouteState).rests)
        f.reemit(); assertEquals(restB, (events.last() as RouteState).rests, "reemit은 지금 목록")
    }

    // ---- C278: 경로 전환 때 옛 경로 값이 새 경로에 섞이지 않음 ----
    @Test fun c278_lateDrivingOfEndedRoute_clearedAtNewRouteStart() {
        val f = RouteFeed({ t.now }, { events += it })
        f.routing(true); f.driving(100, 5000); f.routing(false)
        f.driving(600, 10_000)                       // A의 늦은 DrivingInfo
        f.routing(true)                              // B 시작
        (events.last() as RouteState).let { assertNull(it.remainingSec); assertNull(it.remainingKm); assertTrue(it.restsPending) }
        f.driving(9000, 150_000)
        assertEquals(9000, (events.last() as RouteState).remainingSec)
    }

    @Test fun c278_lateListOfOldRoute_discarded_newListAccepted() {
        val log = mutableListOf<String>()
        val f = RouteFeed({ t.now }, { events += it }, {}, { log += it })
        f.routing(true); f.driving(600, 100_000); f.routing(false)
        f.routing(true)                              // B 시작(전환 전에 요청한 조회가 진행 중)
        f.driving(9000, 150_000)
        f.rests(restA, 100.0)                        // 옛 A 목록(거리 합 100km)
        (events.last() as RouteState).let { assertTrue(it.restsPending); assertTrue(it.rests.isEmpty()) }
        assertEquals(1, log.size)
        f.rests(restB, 148.0)                        // B 목록(±15% 안)
        (events.last() as RouteState).let { assertFalse(it.restsPending); assertEquals(restB, it.rests) }
        f.rests(restA, 100.0)                        // 첫 목록 뒤 갱신은 합과 상관없이 받는다
        assertEquals(restA, (events.last() as RouteState).rests)
    }

    @Test fun c278_tbtBeforeDriving_heldAsCandidate_comparedWhenDistanceArrives() {
        val f = RouteFeed({ t.now }, { events += it })
        f.routing(true)
        f.rests(restB, 150.0)                        // 거리보다 TBT가 먼저
        assertTrue((events.last() as RouteState).restsPending, "남은 거리를 모르면 후보로 둔다")
        f.driving(9000, 150_000)
        (events.last() as RouteState).let { assertFalse(it.restsPending); assertEquals(restB, it.rests) }
        val g = RouteFeed({ t.now }, { events += it })
        g.routing(true); g.rests(restA, 60.0); g.driving(9000, 150_000)
        (events.last() as RouteState).let { assertTrue(it.restsPending, "맞지 않는 후보는 버리고 기다림을 잇는다"); assertTrue(it.rests.isEmpty()) }
        g.rests(restB, 150.0)
        assertEquals(restB, (events.last() as RouteState).rests)
    }

    @Test fun c278_restartInitial_sameFilter_andForgetDropsCandidate() {
        val f = RouteFeed({ t.now }, { events += it })
        f.forget(); f.routing(true); f.initial(9000, 150_000)
        f.rests(restA, 60.0); assertTrue((events.last() as RouteState).restsPending)
        f.rests(restB, 150.0); assertEquals(restB, (events.last() as RouteState).rests)
        val g = RouteFeed({ t.now }, { events += it })
        g.routing(true); g.rests(restA, 60.0); g.forget(); g.routing(true); g.driving(600, 60_000)
        assertTrue((events.last() as RouteState).restsPending, "forget 전 후보가 새 회차에 남지 않는다")
    }

    // ---- C269: 다시 요청한 TBT를 받을 때까지 restsPending(빈 목록 ≠ 쉼터 없음) ----
    @Test fun c269_restsPending_fromFreshRouteUntilTbt_clearedByEndOrForget() {
        val f = RouteFeed({ t.now }, { events += it })
        f.routing(true)
        assertTrue((events.last() as RouteState).restsPending, "안내 시작 직후는 목록을 기다린다")
        f.driving(9000, 150000)
        assertTrue((events.last() as RouteState).restsPending, "시간·거리 갱신에도 기다림은 그대로")
        f.rests(emptyList())
        (events.last() as RouteState).let { assertFalse(it.restsPending); assertTrue(it.rests.isEmpty(), "응답이 빈 목록이면 정말 쉼터 없음") }
        f.rests(restA); f.routing(true)
        assertFalse((events.last() as RouteState).restsPending, "이미 안내 중이면 다시 기다리지 않는다")
        f.routing(false)
        assertFalse((events.last() as RouteState).restsPending, "안내가 끝나면 기다리지 않는다")
        f.routing(true); f.routing(false); f.routing(true)
        assertTrue((events.last() as RouteState).restsPending, "끝난 뒤 다시 시작하면 또 기다린다")
        f.forget(); events.clear(); f.rests(restA)
        assertTrue(events.none { (it as RouteState).restsPending }, "새 구독 회차는 기다림을 잊는다")
        f.driving(600)
        assertTrue((events.last() as RouteState).restsPending, "복원 경로(driving·initial)도 같은 전이")
    }

    @Test fun `재구독 뒤 DrivingInfo가 TBT보다 먼저 와도 옛 경로의 쉼터가 섞이지 않는다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.driving(600, 100000); f.rests(restA)
        f.forget(); events.clear()
        f.driving(9000, 150000)
        val e = events.last() as RouteState
        assertEquals(9000, e.remainingSec); assertTrue(e.rests.isEmpty(), "옛 쉼터 A가 B의 시간·거리와 합쳐지면 안 된다")
    }

    @Test fun `재구독 뒤 routing(true)만 와도 옛 시간·거리·쉼터가 나가지 않는다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.driving(600, 100000); f.rests(restA)
        f.forget(); events.clear()
        f.routing(true)
        val e = events.last() as RouteState
        assertNull(e.remainingSec); assertNull(e.remainingKm); assertTrue(e.rests.isEmpty())
    }

    @Test fun `재구독 뒤 initial은 값을 낸다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.driving(600, 100000); f.rests(restA)
        f.forget(); events.clear()
        f.initial(1200, 50000)
        val e = events.single() as RouteState
        assertEquals(1200, e.remainingSec); assertTrue(e.rests.isEmpty())
    }

    @Test fun `안내 시작 때 끝 알림이 섞여도 ROUTE_DRIVE 상태가 남는다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.driving(16_200)                      // DrivingInfo. isRouting·끝 알림은 PleosNavi가 넘기지 않는다
        f.routing(true)                        // onNavigationStatus(ROUTE_DRIVE)
        assertEquals(RouteState(t.now, true, 16_200, emptyList(), restsPending = true), events.last())   // C269: 다시 요청한 TBT를 기다린다
    }

    @Test fun `앱을 다시 띄워 상태 알림 없이 TBT나 목적지만 오면 안내 중으로 본다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.driving(900)
        assertEquals(RouteState(t.now, true, 900, emptyList(), restsPending = true), events.last(), "상태를 받은 적이 없으면 TBT·목적지로 참")
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
        assertEquals(RouteState(t.now, true, null, emptyList(), restsPending = true), events.last(), "C278: 거짓 동안 온 옛 시간은 안내 시작 때 버린다")
        f.driving(900)
        assertEquals(RouteState(t.now, true, 900, emptyList(), restsPending = true), events.last())
        t.advance(1_000); f.driving(900)
        assertEquals(3, events.size, "같은 값이면 다시 내지 않는다")
        f.routing(false)
        assertEquals(RouteState(t.now, false, null, emptyList()), events.last())
        f.routing(true)
        assertNull((events.last() as RouteState).remainingSec, "안내가 끝나면 옛 남은 시간을 버린다")
    }

    @Test fun `안내가 끝나거나 취소되면 받아 둔 쉼터를 비우고 그 뒤 온 TBT는 안내 중이 되면 버리고 다시 요청한다(C264)`() {
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
        assertTrue((events.last() as RouteState).rests.isEmpty(), "C264: 거짓 뒤에 온 TBT는 끝난 경로의 것일 수 있어 버린다(다시 요청한 응답이 채운다)")
    }

    @Test fun `상태를 받은 적 없을 때 온 TBT는 driving이나 initial로 참이 되면 버린다(C264)`() {
        val rest = RestCandidate("tbt:안성", "안성", RestKind.REST_AREA, 7.0, true)
        val a = RouteFeed({ t.now }, { events += it })
        a.rests(listOf(rest)); a.driving(900)
        assertTrue((events.last() as RouteState).rests.isEmpty(), "driving")
        val b = RouteFeed({ t.now }, { events += it })
        b.rests(listOf(rest)); b.initial(900)
        assertTrue((events.last() as RouteState).rests.isEmpty(), "initial")
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
        assertEquals(RouteState(t.now, true, 16_560, emptyList(), restsPending = true), events.last())
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
        assertEquals(RouteState(t.now, true, 900, emptyList(), restsPending = true), events.last())
    }

    @Test fun c176_routingBeforeInitial_fillsRemaining() {
        val f = RouteFeed({ t.now }, { events += it })
        val rest = RestCandidate("tbt:안성", "안성", RestKind.REST_AREA, 7.0, true)
        f.routing(true)                        // 재구독 뒤 상태가 initial보다 먼저 온다
        f.rests(listOf(rest))
        f.initial(9000, 150_000)
        (events.last() as RouteState).let {
            assertEquals(9000, it.remainingSec); assertEquals(150.0, it.remainingKm); assertEquals(1, it.rests.size)
        }
    }

    @Test fun c203_emptyDrivingDoesNotBlockInitial() {
        val f = RouteFeed({ t.now }, { events += it })
        val rest = RestCandidate("tbt:안성", "안성", RestKind.REST_AREA, 7.0, true)
        f.forget(); f.driving(null, null); f.routing(true)   // 재구독 직후 빈 DrivingInfo
        f.initial(3600, 60_000)
        f.rests(listOf(rest))
        (events.last() as RouteState).let { assertEquals(3600, it.remainingSec); assertEquals(60.0, it.remainingKm) }
    }

    @Test fun c184_lateInitialAfterRouteEnd_ignored() {
        val f = RouteFeed({ t.now }, { events += it })
        f.forget()                             // 재구독: 목적지 조회 A를 보낸다
        f.routing(false)                       // 조회 응답 전에 경로가 끝났다
        f.initial(9000, 150_000)               // 늦은 A 응답
        val rest = RestCandidate("tbt:안성", "안성", RestKind.REST_AREA, 7.0, true)
        f.rests(listOf(rest)); f.routing(true) // 새 경로 B
        assertNull((events.last() as RouteState).remainingSec, "A의 150분이 B에 섞이지 않는다")
        f.driving(600, 10_000)
        assertEquals(600, (events.last() as RouteState).remainingSec)
        f.forget(); f.initial(900)             // 다음 재구독의 조회는 다시 받는다
        assertEquals(900, (events.last() as RouteState).remainingSec)
    }

    @Test fun c176_initialAfterDriving_ignored() {
        val f = RouteFeed({ t.now }, { events += it })
        f.routing(true); f.driving(600, 10_000)
        f.initial(9000, 150_000)
        assertEquals(600, (events.last() as RouteState).remainingSec)
        f.forget(); f.routing(true)            // forget은 받은 표시도 비운다
        f.initial(9000, 150_000)
        assertEquals(9000, (events.last() as RouteState).remainingSec)
    }

    @Test fun `getDestinationInfo 복원은 상태를 받은 적이 없고 0보다 클 때만 한다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.initial(0)                           // 끝난 뒤 DestinationInfo는 모두 0
        assertTrue(events.isEmpty() || !(events.last() as RouteState).hasDestination, "0이면 아무것도 하지 않는다")
        f.routing(true); f.driving(600)
        f.initial(900)
        assertEquals(600, (events.last() as RouteState).remainingSec, "상태를 받은 뒤에는 DrivingInfo 값이 남는다")
    }

    @Test fun `남은 거리 m는 km로 바꿔 남은 시간과 함께 낸다`() {
        val f = RouteFeed({ t.now }, { events += it })
        f.driving(16_200, 378_000)             // 1105d 실측: duration 초, distance m
        assertEquals(378.0, (events.last() as RouteState).remainingKm)
        f.driving(0, 0)                        // 끝난 경로: 둘 다 null
        (events.last() as RouteState).let { assertNull(it.remainingSec); assertNull(it.remainingKm) }
        f.driving(900, 0)                      // 거리 0 이하는 null
        assertNull((events.last() as RouteState).remainingKm)
        f.driving(900, null)
        assertNull((events.last() as RouteState).remainingKm)
        f.routing(false)
        assertNull((events.last() as RouteState).remainingKm)
        val g = RouteFeed({ t.now }, { events += it })
        g.initial(900, 24_000)                 // C116 복원 경로도 같은 값
        assertEquals(24.0, (events.last() as RouteState).remainingKm)
        g.routing(false); g.initial(900, 24_000)
        assertNull((events.last() as RouteState).remainingKm)
        val h = RouteFeed({ t.now }, { events += it })
        h.driving(900, 24_000); assertEquals(24.0, (events.last() as RouteState).remainingKm)
        h.routing(false); h.routing(true)
        (events.last() as RouteState).let { assertTrue(it.hasDestination); assertNull(it.remainingKm, "새 거리 입력 없이 옛 km가 다시 나오면 안 된다") }
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

    // C322 V12: 장면 끝에 실행기가 받은 차 값을 중복 억제 없이 지금 시각으로 다시 낸다
    @Test fun `reemit은 신호마다 마지막 값을 지금 시각으로 다시 내고 받은 적 없는 신호는 내지 않는다`() {
        val c = Changed()
        c.reemit { events += it }; assertTrue(events.isEmpty(), "받은 적이 없으면 내지 않는다")
        c.emit("gear", Gear.P, { GearChanged(t.now, Gear.P) }) { events += it }
        c.emit("gear", Gear.D, { GearChanged(t.now, Gear.D) }) { events += it }
        c.emit("cabin", 21.0, { kr.aigyeot.engine.CabinTemp(t.now, 21.0) }) { events += it }
        events.clear(); t.advance(5_000)
        c.reemit { events += it }
        assertEquals(setOf<Event>(GearChanged(t.now, Gear.D), kr.aigyeot.engine.CabinTemp(t.now, 21.0)), events.toSet(), "같은 값이어도 신호마다 마지막 값 하나")
        c.reemit { events += it }; assertEquals(4, events.size, "다시 불러도 억제하지 않는다")
        c.emit("gear", Gear.D, { GearChanged(t.now, Gear.D) }) { events += it }; assertEquals(4, events.size, "다시 낸 뒤에도 보통 사건은 중복 억제")
    }

    @Test fun `reemit 순서는 키 낸 순서와 상관없이 기어 통화 뒷문 열선 온도`() {
        val c = Changed()
        c.emit("cabin", 21.0, { kr.aigyeot.engine.CabinTemp(t.now, 21.0) }) { }
        c.emit("seatROW_2_LEFT", 1, { kr.aigyeot.engine.SeatHeat(t.now, kr.aigyeot.engine.Area.ROW_2_LEFT, 1) }) { }
        c.emit("doorLEFT", true, { kr.aigyeot.engine.RearDoor(t.now, kr.aigyeot.engine.Side.LEFT, true) }) { }
        c.emit("call", true, { kr.aigyeot.engine.CallMode(t.now, true) }) { }
        c.emit("gear", Gear.P, { GearChanged(t.now, Gear.P) }) { }
        c.reemit { events += it }
        assertEquals(listOf("GearChanged", "CallMode", "RearDoor", "SeatHeat", "CabinTemp"), events.map { it::class.simpleName })
    }

    @Test fun `Sampler reemit은 간격 제한 없이 지금 표본을 다시 내고 받은 적이 없으면 내지 않는다`() {
        val s = Sampler({ t.now }, { events += it })
        s.reemit(); assertTrue(events.isEmpty(), "조향·속도를 받기 전에는 내지 않는다")
        s.steer(1.5f); s.speedMps(10f)
        t.advance(10); s.reemit()
        assertEquals(listOf<Event>(VehicleSample(t.now - 10, 36.0, false, 1.5, TurnSignal.NONE), VehicleSample(t.now, 36.0, false, 1.5, TurnSignal.NONE)), events, "100ms 안이어도 다시 낸다")
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
