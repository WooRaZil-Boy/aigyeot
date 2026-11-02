package dev.probe.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Activity
import android.os.Bundle
import android.util.Log

// 고속도로 장면에 쓸 내비 신호(IC 진입, 제한속도, 정체·사고, 경유지 추가)가 에뮬레이터에서 오는지 기록하는 일회용 실측 화면
// 실행: am start -n dev.probe.pleos/.NaviProbe --es cmd route|wp|state|options|opt [--es opt COMFORTABLE] --ed lat 37.1 --ed lon 127.0 --es name 목적지
class NaviProbe : Activity(), NaviHelperEventListener {
    private fun log(msg: String) { Log.i("NAVI", msg) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: android.content.Intent) {
        val h = helper ?: NaviHelper(applicationContext).also {
            it.initialize({ log("INIT ok") }, { e -> log("INIT ERR $e") })
            it.addListener(this)
            helper = it
        }
        val lat = i.getDoubleExtra("lat", 0.0)
        val lon = i.getDoubleExtra("lon", 0.0)
        val name = i.getStringExtra("name") ?: "test"
        when (i.getStringExtra("cmd")) {
            "route" -> h.requestRoute(RouteInfo(latitude = lat, longitude = lon, poiId = "", poiName = name, poiSubId = "0", address = name,
                routeOption = ai.pleos.playground.navi.constants.RouteOption.valueOf(i.getStringExtra("opt") ?: "RECOMMENDED")))
            "wp" -> h.addWaypoint(RequestWaypointInfo(latitude = lat, longitude = lon, waypointIndex = WaypointIndex.FIRST, poiId = "", poiName = name, poiSubId = "0", address = name))
            "state" -> { h.getRouteStateInfo(); h.getTBTInfo(); h.getCurrentLocationInfo() }
            "options" -> h.requestNavigationScreen(ai.pleos.playground.navi.data.NavigationScreenRequest.RouteOptionList) // 내비의 경로 옵션 목록 화면
            "opt" -> h.changeRouteOption(ai.pleos.playground.navi.constants.RouteOption.valueOf(i.getStringExtra("opt") ?: "COMFORTABLE")) // 아기 잠: '편한 길'로 재탐색
        }
        log("CMD ${i.getStringExtra("cmd")} $lat,$lon $name")
    }

    override fun onRouteStarted(info: RouteStartInfo) = log("EVT routeStarted $info")
    override fun onRoutingCompleted() = log("EVT routingCompleted")
    override fun onReRouteEnded() = log("EVT reRouteEnded")
    override fun onRouteCancelled() = log("EVT routeCancelled")
    override fun onWaypointChanged(info: WaypointChangedInfo) = log("EVT waypointChanged $info")
    override fun onError(code: NaviErrorCode) = log("EVT error $code")
    override fun onNavigationStatus(status: NavigationStatus) = log("EVT status $status")
    override fun onCurrentLocationInfo(info: CurrentLocationInfo) = log("EVT location ${info.latitude},${info.longitude} heading=${info.heading} ${info.address}")
    override fun onRouteStateInfo(info: RouteStateInfo) = log("EVT routeState routing=${info.isRouting} option=${info.routeOption} jams=${info.trafficStatus?.congestionItemList?.map { "${it.roadName}:${it.trafficCongestionType}@${it.offset}" }} events=${info.trafficStatus?.trafficEventList?.map { "${it.eventType}:${it.description}" }}")
    override fun onCurrentRoadSpeedLimit(limit: Int) = log("EVT speedLimit $limit")
    override fun onCameraAlert(list: List<CameraAlert>) = log("EVT camera ${list.map { "${it.type}:${it.speedLimit}@${it.remainDistance} over=${it.isOverSpeed}" }}")
    override fun onDrivingInfo(info: DrivingInfo) = log("EVT driving ${info.destination?.name} ${info.destination?.distance}m ${info.destination?.duration}s")
    override fun onTBTInfo(list: List<TBTInfo>) = log("EVT tbt ${list.map { "${it.type}:${it.description}@${it.distance.toInt()}" }}")
    override fun onCautionPointInfo(list: List<CautionPoint>) = log("EVT caution $list")

    companion object { private var helper: NaviHelper? = null }
}
