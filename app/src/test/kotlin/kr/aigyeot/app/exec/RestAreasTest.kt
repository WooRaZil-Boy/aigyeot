package kr.aigyeot.app.exec

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import kr.aigyeot.engine.RestKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 수유실 여부는 공공데이터로 정하는 규칙과 이름 맞추기, 경유지 좌표 고르기(반대쪽 휴게소에 넣지 않기)
class RestAreasTest {
    @Test fun `넣은 공공데이터는 215곳이고 수유실은 212곳이다`() {
        assertEquals(215, RestAreas.all.size)
        assertEquals(212, RestAreas.all.count { it.nursing })
    }

    @Test fun `이름은 공백·휴게소·방향을 빼고 맞추고 방향이 없으면 양방향 둘이다`() {
        assertEquals(listOf("안성(부산)", "안성(서울)"), RestAreas.match("안성휴게소").map { it.name })
        assertEquals(listOf("안성(부산)"), RestAreas.match("안성 휴게소(부산방향)").map { it.name })
        assertEquals(listOf("덕평"), RestAreas.match("덕평휴게소").map { it.name })
        assertTrue(RestAreas.match("안성맞춤휴게소").all { it.name.startsWith("안성맞춤") }, "안성과 섞이지 않는다")
        assertTrue(RestAreas.match("없는휴게소").isEmpty())
        assertTrue(RestAreas.match("휴게소").isEmpty(), "이름이 비면 아무것도 맞추지 않는다")
    }

    @Test fun placeable_twoRowsNoDirection_false() {
        assertFalse(RestAreas.placeable("여주휴게소"), "양방향 두 줄(좌표 차 10m 초과)은 이름만으로 못 넣는다")
        assertFalse(RestAreas.placeable("없는휴게소"))
    }

    // PleosNavi.addWaypoint(NaviHelper SDK 필요, 단위 시험 불가)의 입구 판단이 쓰는 함수. 점이 있으면 위치 없이 보내고 null이고 줄이 있으면 위치를 받는다.
    @Test fun tiedRows_addWaypointWithoutLocation() {
        assertTrue(RestAreas.match("보성녹차휴게소").size > 1)
        assertTrue(RestAreas.pointOf("보성녹차휴게소") != null, "같은 좌표 두 줄은 위치 요청 없이 send")
        assertTrue(RestAreas.pointOf("여주휴게소") == null && RestAreas.match("여주휴게소").size > 1, "다른 점 두 줄은 위치 요청")
        assertTrue(RestAreas.match("없는휴게소").isEmpty(), "맞춘 줄이 없으면 no_coords")
    }

    @Test fun placeable_singleRow_true() {
        assertTrue(RestAreas.placeable("덕평휴게소"))
        assertTrue(RestAreas.placeable("안성휴게소(부산방향)"), "방향이 붙으면 한 줄")
    }

    @Test fun feedsAddable_sameAsPlaceable() {
        val got = restsOf(listOf(
            TBTInfo(TurnType.TURN_ENTER_SERVICEAREA, 1_000.0, "여주휴게소"),
            TBTInfo(TurnType.TURN_ENTER_SERVICEAREA, 1_000.0, "덕평휴게소"),
        )) { true }
        assertEquals(listOf(false, true), got.map { it.addable })
    }

    @Test fun `수유실은 맞춘 줄이 모두 있을 때만 있다`() {
        assertTrue(RestAreas.nursing("강릉휴게소"))
        assertFalse(RestAreas.nursing("옥천만남휴게소"), "데이터에 N")
        assertFalse(RestAreas.nursing("없는휴게소"), "못 맞추면 없음")
        val mixed = RestAreas.parse("휴게소명,도로노선명,도로노선방향,위도,경도,수유실유무\n다라(서울),다라선,상행,37.0,127.0,Y\n다라(부산),다라선,하행,37.0,127.0,N")
        assertFalse(RestAreas.nursing("다라휴게소", mixed), "한쪽이 N이면 없음")
        assertTrue(RestAreas.nursing("다라휴게소(서울방향)", mixed), "방향이 맞으면 그 줄")
    }

    @Test fun `한 곳이면 위치 없이도 그 줄이고 양방향이면 위치 없이는 넣지 않는다`() {
        assertEquals("덕평", RestAreas.place("덕평휴게소", 5.0, null)?.name)
        assertNull(RestAreas.place("안성휴게소", 10.0, null))
    }

    @Test fun `방향을 적은 이름은 방향까지 같은 줄만 쓴다`() {
        assertTrue(RestAreas.all.any { it.name == "기흥(부산)" } && RestAreas.all.none { it.name == "기흥(서울)" }, "조건: 데이터에 기흥(서울)은 없다")
        assertTrue(RestAreas.match("기흥휴게소(서울방향)").isEmpty(), "반대 방향 줄로 넘어가면 안 된다")
        assertFalse(RestAreas.nursing("기흥휴게소(서울방향)"))
        assertNull(RestAreas.place("기흥휴게소(서울방향)", 5.0, Here(37.3, 127.1)))
        assertEquals(listOf("기흥(부산)"), RestAreas.match("기흥휴게소").map { it.name }, "방향이 없으면 괄호 앞 이름으로")
        assertEquals(listOf("기흥(부산)"), RestAreas.match("기흥휴게소(부산방향)").map { it.name })
    }

    @Test fun `곧은 거리는 빼는 데만 쓰고 남은 줄이 하나일 때만 고른다`() {
        val north = Here(37.13, 127.12)
        assertNull(RestAreas.place("안성휴게소", 13.5, north), "둘 다 남으면 넣지 않는다")
        val south = Here(36.95, 127.15)
        assertEquals("안성(부산)", RestAreas.place("안성휴게소", 8.0, south)?.name, "서울쪽은 곧은 14km라 TBT 8+1을 넘어 빠진다")
        assertNull(RestAreas.place("안성휴게소", 14.3, south), "둘 다 남으면 넣지 않는다")
        assertNull(RestAreas.place("안성휴게소", null, south), "TBT 거리가 없으면 빼지 않으니 둘이 남는다")
        assertNull(RestAreas.place("안성휴게소", 1.0, north), "남은 줄이 없으면 넣지 않는다")
    }

    @Test fun `한자리의 양쪽이나 굽은 길은 방향 근거가 없어 넣지 않는다`() {
        val rows = RestAreas.parse("""
            휴게소명,도로노선명,도로노선방향,위도,경도,수유실유무
            가나(서울),가나선,상행,37.0,127.0005,Y
            가나(부산),가나선,하행,37.0,126.9995,Y
        """.trimIndent())
        assertNull(RestAreas.place("가나휴게소", 11.5, Here(36.9, 127.0), rows))
        assertNull(RestAreas.place("가나휴게소", 15.0, Here(37.1, 127.0), rows), "굽은 길로 마지막이 북행인 서울 방향까지 15km")
        assertEquals("가나(서울)", RestAreas.place("가나휴게소(서울방향)", 15.0, Here(37.1, 127.0), rows)?.name, "방향이 적혔으면 그 줄")
    }

    @Test fun `경유지 목록에 요청한 줄이 있을 때만 있다고 본다`() {
        val an = RestAreas.all.first { it.name == "안성(부산)" }
        fun at(la: Double, lo: Double) = listOf(la to lo)
        assertFalse(waypointPresent(emptyList(), an))
        assertTrue(waypointPresent(at(37.0138, 127.1448), an), "약 45m")
        assertFalse(waypointPresent(at(37.0767, 127.1325), an), "앞 요청의 다른 휴게소(7km)")
        assertFalse(waypointPresent(at(37.0134 + 0.0035, 127.1448), an), "약 390m는 밖")
        assertTrue(waypointPresent(listOf(37.0767 to 127.1325, 37.0134 to 127.1450), an), "여럿 중 하나")
    }

    @Test fun `바로 옆 반대 방향 휴게소는 요청한 쪽의 성공이 아니다(건천)`() {
        val bu = RestAreas.all.first { it.name == "건천(부산)" }
        val se = RestAreas.all.first { it.name == "건천(서울)" }
        val m = Math.hypot((se.lon - bu.lon) * 111_320 * Math.cos(bu.lat * Math.PI / 180), (se.lat - bu.lat) * 110_574)
        assertTrue(m < 300.0 && m > 50.0, "조건: 두 줄이 300m 안이라 반경만으로는 구분 못 한다: $m")
        assertFalse(waypointPresent(listOf(se.lat to se.lon), bu), "건천(서울) 좌표만 있으면 부산 요청의 성공이 아니다")
        assertTrue(waypointPresent(listOf(bu.lat + 0.00018 to bu.lon), bu), "부산 좌표에서 약 20m 어긋난 점")
        assertTrue(waypointPresent(listOf(se.lat to se.lon), se))
    }

    @Test fun `좌표가 같은 양방향 휴게소는 어느 쪽을 요청해도 데이터 순서에 달리지 않는다(보성녹차)`() {
        val sun = RestAreas.all.first { it.name == "보성녹차(순천)" }
        val yeong = RestAreas.all.first { it.name == "보성녹차(영암)" }
        assertTrue(sun.lat == yeong.lat && sun.lon == yeong.lon, "조건: 두 줄의 거리가 0m다")
        assertTrue(waypointPresent(listOf(yeong.lat to yeong.lon), yeong), "영암 요청에 영암(=순천) 좌표")
        assertTrue(waypointPresent(listOf(sun.lat to sun.lon), sun))
        assertTrue(waypointPresent(listOf(yeong.lat + 0.00018 to yeong.lon), yeong), "20m 어긋나도")
        val near = Here(yeong.lat + 0.1, yeong.lon)               // 곧은 거리 약 11km
        assertTrue(RestAreas.place("보성녹차휴게소", 11.0, near) != null, "좌표가 같아 어느 줄이든 같은 점이라 고른다")
        assertNull(RestAreas.place("안성휴게소", 13.5, Here(37.13, 127.12)), "7km 떨어진 쌍은 여전히 넣지 않는다")
    }

    @Test fun `좌표가 다른 쌍은 점에서 잰 거리가 비슷해도 가까운 줄만 성공이다(건천)`() {
        val bu = RestAreas.all.first { it.name == "건천(부산)" }
        val se = RestAreas.all.first { it.name == "건천(서울)" }
        val p = 35.83134034 to 129.109275067
        fun d(r: RestArea) = Math.hypot((r.lon - p.second) * 111_320 * Math.cos(p.first * Math.PI / 180), (r.lat - p.first) * 110_574)
        assertTrue(d(bu) in 68.0..70.5 && d(se) in 60.0..62.5, "조건: 부산 ${d(bu)}m, 서울 ${d(se)}m(10m 차이 안)")
        assertFalse(waypointPresent(listOf(p), bu), "더 가까운 서울이 있다")
        assertTrue(waypointPresent(listOf(p), se))
    }

    @Test fun `졸음쉼터 자료는 253곳이고 쉼터 이름은 졸음쉼터·방향을 빼고 맞춘다`() {
        assertEquals(253, RestAreas.shelters.size)
        assertEquals(listOf("오창(하남)", "오창(통영)").sorted(), RestAreas.match("오창졸음쉼터", RestAreas.shelters).map { it.name }.sorted(), "방향이 없으면 쌍")
        assertEquals(listOf("오창(하남)"), RestAreas.match("오창 졸음쉼터(하남방향)", RestAreas.shelters).map { it.name }, "방향을 적었으면 그 줄 하나")
        assertTrue(RestAreas.match("오창 졸음쉼터(서울방향)", RestAreas.shelters).isEmpty(), "방향이 다르면 못 맞춘다")
        assertTrue(RestAreas.match("망향 졸음쉼터", RestAreas.shelters).isEmpty(), "데이터에 없는 이름은 못 맞춘다")
    }

    @Test fun `같은 이름의 휴게소와 졸음쉼터는 종류로 갈린다`() {
        val h = RestAreas.rowsOf(RestKind.REST_AREA).first { it.name == "오창(하남)" }
        val s = RestAreas.rowsOf(RestKind.DROWSY_SHELTER).first { it.name == "오창(하남)" }
        assertTrue(h.lat != s.lat, "조건: 이름은 같고 좌표가 다른 두 곳이다 ${h.lat} / ${s.lat}")
        assertTrue(RestAreas.rowsOf(RestKind.REST_AREA) === RestAreas.all && RestAreas.rowsOf(RestKind.DROWSY_SHELTER) === RestAreas.shelters)
        assertEquals(s.lat, RestAreas.place("오창 졸음쉼터(하남방향)", 5.0, null, RestAreas.rowsOf(RestKind.DROWSY_SHELTER))?.lat)
        assertEquals(h.lat, RestAreas.place("오창휴게소(하남방향)", 5.0, null, RestAreas.rowsOf(RestKind.REST_AREA))?.lat)
        assertFalse(RestAreas.nursing("오창졸음쉼터", RestAreas.shelters), "쉼터에는 수유실이 없다")
    }

    @Test fun `졸음쉼터가 한 곳이면 위치 없이도 그 줄이고 양방향 쌍은 위치 없이는 넣지 않는다`() {
        val rows = RestAreas.shelters
        assertEquals(1, RestAreas.match("가남졸음쉼터", rows).size, "조건: 가남은 한 곳")
        assertEquals("가남(창원)", RestAreas.place("가남졸음쉼터", 3.0, null, rows)?.name)
        assertNull(RestAreas.place("오창졸음쉼터", 3.0, null, rows), "오창은 쌍")
        val s = rows.first { it.name == "오창(하남)" }
        assertTrue(waypointPresent(listOf(s.lat to s.lon), s, rows), "쉼터 자료로 대조")
    }

    @Test fun tiedRows_waypointListedByName() {
        val sun = RestAreas.all.first { it.name == "보성녹차(순천)" }
        val yeong = RestAreas.all.first { it.name == "보성녹차(영암)" }
        for (t in listOf(sun, yeong)) assertTrue(waypointListed(listOf(Triple("보성녹차휴게소", 0.0, 0.0)), t), "같은 점 두 줄은 이름만으로 한 점")
        val yeoju = RestAreas.all.first { it.name.startsWith("여주(") }
        assertFalse(waypointListed(listOf(Triple("여주휴게소", 0.0, 0.0)), yeoju), "다른 점 두 줄은 이름만으로는 실패")
    }

    // 실제 내비는 목록 좌표를 0.0, 0.0으로 주고 이름만 맞게 준다
    @Test fun `좌표가 0·0인 경유지는 이름을 자료 줄로 바꿔 보낸 줄과 같을 때만 성공이다`() {
        val an = RestAreas.all.first { it.name == "안성(부산)" }
        fun list(name: String, la: Double = 0.0, lo: Double = 0.0) = listOf(Triple(name, la, lo))
        assertTrue(waypointListed(list("안성휴게소(부산방향)"), an), "0·0 + 맞는 이름")
        assertFalse(waypointListed(list("안성휴게소(서울방향)"), an), "0·0 + 반대 방향 이름")
        assertFalse(waypointListed(list("없는휴게소"), an), "0·0 + 모르는 이름")
        assertFalse(waypointListed(list("안성휴게소"), an), "0·0 + 방향 없는 이름은 두 줄이라 보낸 줄이라 단정하지 않는다")
        assertTrue(waypointListed(list("엉뚱", 37.0138, 127.1448), an), "좌표가 있으면 기존 300m 규칙(이름 무시)")
        assertFalse(waypointListed(list("안성휴게소(부산방향)", 37.0767, 127.1325), an), "좌표가 있으면 이름이 맞아도 7km 밖은 실패")
        assertFalse(waypointListed(emptyList(), an))
        assertTrue(waypointListed(list("없는휴게소") + list("안성휴게소(부산방향)"), an), "여럿 중 하나")
    }
}
