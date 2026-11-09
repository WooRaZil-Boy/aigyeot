package kr.aigyeot.app.exec.android

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kr.aigyeot.app.exec.Here
import kr.aigyeot.app.exec.NaviPort
import kr.aigyeot.app.exec.RestArea
import kr.aigyeot.app.exec.RestAreas
import kr.aigyeot.app.exec.RouteFeed
import kr.aigyeot.app.exec.WaypointPrecheck
import kr.aigyeot.app.exec.restsOf
import kr.aigyeot.app.exec.waypointListed
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.RestCandidate

// ---- 내비 ----

/**
 * RouteState(계약 3.3)와 addWaypoint. 남은 시간은 DrivingInfo.destination.duration(초)으로 본다(C75, 1105d 확정). 안내 중 여부는 NavigationStatus(ROUTE_DRIVE)로 정한다(C114, NaviHelper에 상태 조회가 없어 재시작·다시 구독 때 상태 알림을 받은 적이 없으면 남은 시간이 0보다 큰 목적지 정보(DrivingInfo.destination.duration, getDestinationInfo의 remainTime)로만 안내 중으로 본다. 끝난 경로의 TBT가 돌아오므로 TBT로는 판단하지 않는다, C116. 쉼터는 안내 중일 때만 낸다). TBT 쉼터 거리는 앞 항목을 모두 더한 값이다(C113).
 * TBT에는 좌표가 없어 경유지 좌표·수유실은 휴게소 공공데이터(RestAreas)에서 찾는다. 양방향 휴게소처럼 이름이 둘이면
 * 지금 위치를 한 번 받아 곧은 거리로 먼 쪽 줄을 빼고 하나가 남을 때만 고른다(RestAreas.place). 고를 수 없으면 넣지 않는다.
 * 보내기 전에 목록을 한 번 읽어(WaypointPrecheck, 2초) 이미 있으면 보내지 않고 성공(C110). 결과: ADD 알림 뒤 경유지 목록을 다시 읽어 보낸 좌표 300m 안의 경유지가 있을 때만 성공(F19 다시 읽기, waypointPresent).
 * 앞 요청의 늦은 알림·응답은 Controls가 요청을 닫을 때 [cancelWaypoint]로 비워 다음 요청의 결과가 되지 않는다(C65·C95).
 */
internal class PleosNavi(private val ctx: Context, private val ex: ExecThread, clock: () -> Long, post: (Event) -> Unit) : NaviPort {
    private var helper: NaviHelper? = null
    private val route = RouteFeed(clock, post, { helper?.let { h -> ex.off.run { h.getTBTInfo() } } }, { Log.w(TAG, it) })   // C264: 안내 시작 때 지금 경로 TBT를 다시 요청(리스너 안에서만 불려 회차가 맞다)
    private var pending: ((Boolean, String) -> Unit)? = null          // addWaypoint 결과 콜백을 기다림
    private var targetRows: List<RestArea> = RestAreas.all               // 대조에 쓸 자료(쉼터 종류에 맞춘다)
    private var target: RestArea? = null                              // 보낸 경유지 줄(대조 대상)
    private var awaitingHere: Pair<RestCandidate, (Boolean, String) -> Unit>? = null   // 위치를 받아야 고를 수 있음
    private val precheck = WaypointPrecheck(ex, kr.aigyeot.engine.Params().waypointPrecheckMs)   // 보내기 전 목록 확인 중(C110). 끝나면 pending이 보낸 뒤 기다림
    private var listener: NaviHelperEventListener? = null
    private var gen = 0                                               // 시작 회차(start·stop마다 +1). 앞 회차의 늦은 콜백·응답을 버린다

    /** start마다 새로 만들어 그 회차 번호를 잡는다. 회차가 다르면 아무것도 하지 않는다. */
    private fun newListener(my: Int) = object : NaviHelperEventListener {
        private fun on(f: () -> Unit) = ex.guard { if (gen == my) f() }
        override fun onNavigationStatus(status: NavigationStatus) = on { route.routing(status == NavigationStatus.ROUTE_DRIVE) }
        // isRouting은 안내 중에도 거짓이고 onRouteEnded는 안내 시작과 섞여 온다(C114): 안내 여부는 NavigationStatus만 따른다
        override fun onDrivingInfo(info: DrivingInfo) = on { route.driving(info.destination?.duration, info.destination?.distance) }
        // 끝난 경로의 TBT를 재시작 때 돌려주므로(1105e) TBT로는 안내 여부를 정하지 않는다
        override fun onDestinationInfo(info: DestinationInfo) = on { route.initial(info.remainTime, info.remainDistance) }
        override fun onTBTInfo(list: List<TBTInfo>) = on { route.rests(restsOf(list) { RestAreas.nursing(it) }, list.sumOf { it.distance } / 1000.0) }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = on {
            awaitingHere?.let { (rest, done) -> awaitingHere = null; send(rest, Here(info.latitude, info.longitude), done) }
        }
        override fun onWaypointChanged(info: WaypointChangedInfo) = on {
            // 어느 요청의 알림인지 알 수 없으니 바로 성공으로 보지 않고 경유지 목록을 다시 읽어 대조한다
            if (info.cmdType == WaypointCmdType.ADD && pending != null && target != null) helper?.let { h -> ex.off.run { h.getWaypointInfo() } }   // 바인더라 잠금 밖 레인에서
        }
        override fun onWayPointInfo(list: List<WaypointInfo>) = on {
            val t = target ?: return@on
            val listed = waypointListed(list.map { Triple(it.name, it.latitude, it.longitude) }, t, targetRows)
            if (precheck.onList(listed)) return@on                    // 보내기 전 응답
            if (listed) pending?.let { pending = null; target = null; it(true, "added") }   // 보낸 뒤 응답
        }
        // 어느 요청의 오류인지 알 수 없어 결과로 쓰지 않는다(실패는 Controls의 20초 시간 초과가 정한다)
        override fun onError(code: NaviErrorCode) = on { Log.w(TAG, "navi error ${code.name}") }
    }

    fun start() {
        val my = ++gen
        route.forget()                                                // 새 구독 회차: 앞 회차의 상태가 복원을 막지 않게
        val l = newListener(my).also { listener = it }
        helper = NaviHelper(ctx).also { h ->
            h.initialize({ ex.guard { if (gen == my) ex.off.run { h.addListener(l); h.getRouteStateInfo(); h.getTBTInfo(); h.getDestinationInfo() } } }, { e -> Log.w(TAG, "navi init", e) })
        }
    }

    fun stop() {
        gen++
        cancelWaypoint()
        val l = listener; listener = null
        helper?.let { h -> runCatching { l?.let(h::removeListener); h.release() } }
        helper = null
    }

    override fun addWaypoint(restId: String, rest: RestCandidate?, done: (Boolean, String) -> Unit) {
        cancelWaypoint()                                                 // 앞 요청은 Controls가 이미 닫았다
        rest ?: return done(false, "unknown_rest")
        val h = helper ?: return done(false, "no_navi")
        val rows = RestAreas.rowsOf(rest.kind)                           // 졸음쉼터는 졸음쉼터 자료에서
        // C310: 이름 → 한 점 판단은 pointOf 하나(같은 점 두 줄은 위치 없이 바로 보낸다). 점이 안 정해지면 위치로 좁힌다.
        when {
            RestAreas.pointOf(rest.name, rows) != null -> send(rest, null, done)
            RestAreas.match(rest.name, rows).isEmpty() -> done(false, "no_coords")
            else -> { awaitingHere = rest to done; runCatching { h.getCurrentLocationInfo() }.onFailure { awaitingHere = null; done(false, "no_location") } }
        }
    }

    /** 장면 끝(V12·C130): 마지막 경로를 중복 억제 없이 다시 낸다(Exec.reemitAll). */
    fun reemit() = route.reemit()

    override fun cancelWaypoint() { precheck.cancel(); pending = null; awaitingHere = null; target = null }

    private fun send(rest: RestCandidate, here: Here?, done: (Boolean, String) -> Unit) {
        val h = helper ?: return done(false, "no_navi")
        val rows = RestAreas.rowsOf(rest.kind)
        val a = RestAreas.place(rest.name, rest.distanceKm, here, rows) ?: return done(false, "ambiguous")
        target = a
        targetRows = rows
        val my = gen
        // 이미 같은 경유지가 경로에 있으면 내비는 다시 넣지 않고 알림도 주지 않는다: 먼저 목록을 읽어 있으면 보내지 않고 성공(C110)
        precheck.start({ ex.off.run { h.getWaypointInfo() } }) { listed ->
            if (gen != my || target !== a) return@start
            if (listed) { target = null; done(true, "added") } else push(h, rest, a, done)
        }
    }

    private fun push(h: NaviHelper, rest: RestCandidate, a: RestArea, done: (Boolean, String) -> Unit) {
        pending = done
        try {
            h.addWaypoint(RequestWaypointInfo(latitude = a.lat, longitude = a.lon, poiName = rest.name, waypointIndex = WaypointIndex.FIRST,
                poiId = "", address = "${a.route} ${a.name}", poiSubId = "0"))
        } catch (e: Exception) { pending = null; target = null; done(false, "error ${e.message}") }
    }
}
