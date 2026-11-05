package kr.aigyeot.app.exec

import kr.aigyeot.engine.RestKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/** 휴게소 공공데이터 한 줄(RestAreaData.kt). */
internal data class RestArea(val name: String, val route: String, val dir: String, val lat: Double, val lon: Double, val nursing: Boolean)

/** 지금 위치(NaviHelper CurrentLocationInfo). */
internal data class Here(val lat: Double, val lon: Double)

/**
 * TBT 휴게소 이름을 공공데이터와 맞춘다(명세서 17장 C21: 공백·'휴게소'를 빼고 맞춘다. 데이터 이름이 '안성(부산)'처럼
 * 방향을 괄호에 달고 있어 '방향'도 뺀다). 방향까지 같은 줄이 있으면 그 줄, 없으면 괄호 앞 이름이 같은 줄들(양방향 둘).
 */
internal object RestAreas {
    val all: List<RestArea> by lazy { parse(REST_AREA_CSV) }
    /** 졸음쉼터 공공데이터(RestAreaData.kt SHELTER_CSV). 이름은 '쉼터명(종점)' 꼴. 휴게소 자료와 섞지 않는다. */
    val shelters: List<RestArea> by lazy { parse(SHELTER_CSV) }

    /** 쉼터 종류에 맞는 자료. 이름이 같은 휴게소·쉼터(예: 오창)가 서로 맞지 않게 종류로 가른다. */
    fun rowsOf(kind: RestKind) = if (kind == RestKind.DROWSY_SHELTER) shelters else all

    fun parse(csv: String): List<RestArea> = csv.lineSequence().map(String::trim).filter(String::isNotEmpty).drop(1).map { line ->
        val f = line.split(',')
        RestArea(f[0], f[1], f[2], f[3].toDouble(), f[4].toDouble(), f[5] == "Y")
    }.toList()

    fun match(tbtName: String, rows: List<RestArea> = all): List<RestArea> {
        val n = norm(tbtName)
        if (n.isEmpty()) return emptyList()
        val exact = rows.filter { norm(it.name) == n }
        if (exact.isNotEmpty() || '(' in n) return exact              // 방향을 적었으면 방향까지 같은 줄만(없으면 못 맞춘 것, C21)
        return rows.filter { norm(it.name).substringBefore('(') == n }  // 방향이 없을 때만 괄호 앞 이름(양방향 둘)
    }

    /** 맞춘 줄이 있고 모두 수유실이 있을 때만 참. 못 맞추면 없음(G21). */
    fun nursing(tbtName: String, rows: List<RestArea> = all) = match(tbtName, rows).let { it.isNotEmpty() && it.all(RestArea::nursing) }

    /**
     * 경유지로 넣을 줄. 반대쪽 휴게소에 넣는 것이 운전자에게 해로우니 보수적으로 고른다: 거리는 '빼는' 데만 쓴다.
     * ① 한 곳이면 그 줄. ② 지금 위치가 없으면 null. ③ 곧은 거리가 TBT 거리 + [SLACK_KM]을 넘는 줄은 뺀다
     * (곧은 거리는 도로 거리보다 길 수 없다. TBT 거리가 없으면 빼지 않는다). ④ 남은 줄이 정확히 하나면 그 줄, 아니면 null(넣지 않는다).
     * 방향 근거가 없으면 넣지 않는다. TBT 이름에 방향이 붙는지는 첫날 실측(C75).
     */
    fun place(tbtName: String, aheadKm: Double?, here: Here?, rows: List<RestArea> = all): RestArea? {
        val m = match(tbtName, rows)
        if (m.size <= 1) return m.firstOrNull()
        here ?: return null
        val c = m.filter { aheadKm == null || km(here, it) <= aheadKm + SLACK_KM }
        // 남은 줄이 모두 서로 TIE_M 안(사실상 같은 점)이면 어느 줄이든 내비에 가는 점이 같으니 첫 줄
        if (c.size > 1 && c.all { a -> c.all { b -> hypot((a.lon - b.lon) * 111_320 * cos(a.lat * PI / 180), (a.lat - b.lat) * 110_574) <= TIE_M } }) return c.first()
        return c.singleOrNull()
    }

    private fun norm(s: String) = s.filterNot(Char::isWhitespace).replace("휴게소", "").replace("졸음쉼터", "").replace("방향", "")

    /** (동, 북) m. 짧은 거리라 평면으로 본다. */
    private fun enu(h: Here, r: RestArea) = enu(h, Here(r.lat, r.lon))
    private fun enu(h: Here, r: Here): Pair<Double, Double> =
        (r.lon - h.lon) * 111_320 * cos(h.lat * PI / 180) to (r.lat - h.lat) * 110_574
    private fun km(h: Here, r: RestArea) = enu(h, r).let { (x, y) -> hypot(x, y) / 1000 }

    /** 곧은 거리 여유(좌표·TBT 기준점 차이). 설계값, 근거 없음. */
    private const val SLACK_KM = 1.0
}

/** 사실상 같은 점으로 보는 거리(m). 설계값, 근거 없음. */
private const val TIE_M = 10.0

/**
 * 경유지 목록 판정(C110). 점은 (이름, 위도, 경도). 좌표가 0·0이면(실제 내비는 이름만 맞게 준다) 이름을 [rows]의 줄로 바꿔
 * 한 줄로 정해지고 [target]과 같을 때만 성공이다(방향 없는 이름은 두 줄이라 아님). 좌표가 있으면 [waypointPresent]의 300m 규칙.
 */
internal fun waypointListed(points: List<Triple<String, Double, Double>>, target: RestArea, rows: List<RestArea> = RestAreas.all, radiusM: Double = 300.0): Boolean =
    points.any { (name, la, lo) ->
        if (la == 0.0 && lo == 0.0) RestAreas.match(name, rows).singleOrNull() == target
        else waypointPresent(listOf(la to lo), target, rows, radiusM)
    }

/**
 * 경유지 목록([points], 위도·경도)에 요청한 줄 [target]이 들어 있는가. 점이 [target]에서 [radiusM](300m, 설계값·근거 없음) 안이면서
 * 그 점에서 가장 가까운 데이터 줄(같은 괄호 앞 이름의 줄들, 데이터 전체 [rows])이 [target]일 때만 있다고 본다.
 * 바로 옆 반대 방향 휴게소(건천 부산·서울 약 130m)를 요청한 쪽의 성공으로 보지 않으려고.
 */
internal fun waypointPresent(points: List<Pair<Double, Double>>, target: RestArea, rows: List<RestArea> = RestAreas.all, radiusM: Double = 300.0): Boolean {
    fun m(la: Double, lo: Double, r: RestArea) = hypot((r.lon - lo) * 111_320 * cos(la * PI / 180), (r.lat - la) * 110_574)
    val base = target.name.substringBefore('(')
    val same = rows.filter { it.name.substringBefore('(') == base }.ifEmpty { listOf(target) }
    // 동률은 데이터 좌표끼리 TIE_M 안인 줄(보성녹차 순천·영암)에만 허용한다: 그 점에서 가장 가까운 줄이 target이거나,
    // 가장 가까운 줄과 target의 데이터 좌표가 TIE_M 안일 때만(좌표가 다른 쌍은 점에서 잰 거리 차이로 구분하지 않는다)
    fun apart(a: RestArea, b: RestArea) = hypot((a.lon - b.lon) * 111_320 * cos(a.lat * PI / 180), (a.lat - b.lat) * 110_574)
    return points.any { (la, lo) ->
        m(la, lo, target) <= radiusM && same.minBy { m(la, lo, it) }.let { it.name == target.name || apart(it, target) <= TIE_M }
    }
}

/**
 * 경유지를 보내기 전 사전 확인(C110): 목록을 한 번 묻고 응답 또는 [waitMs]가 지나면 [start]의 result를 한 번 부른다(이미 있음 = true).
 * 끝난 뒤나 [cancel] 뒤에 오는 응답은 [onList]가 받지 않는다(false). 그 응답은 보낸 뒤 확인 쪽이 본다.
 */
internal class WaypointPrecheck(private val sched: Sched, private val waitMs: Long) {
    private var result: ((Boolean) -> Unit)? = null
    private var cancelTimer: () -> Unit = {}

    fun start(ask: () -> Unit, result: (Boolean) -> Unit) {
        cancel()
        this.result = result
        cancelTimer = sched.after(waitMs) { finish(false) }
        try { ask() } catch (e: Exception) { finish(false) }
    }

    /** 사전 확인 중이면 결과를 내고 true. 아니면 false(소비하지 않음). */
    fun onList(listed: Boolean): Boolean { if (result == null) return false; finish(listed); return true }

    fun cancel() { cancelTimer(); cancelTimer = {}; result = null }

    private fun finish(listed: Boolean) {
        val r = result ?: return
        cancelTimer(); cancelTimer = {}; result = null
        r(listed)
    }
}
