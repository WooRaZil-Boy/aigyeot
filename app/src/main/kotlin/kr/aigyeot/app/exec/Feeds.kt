package kr.aigyeot.app.exec

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import android.media.AudioManager
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.RouteState
import kr.aigyeot.engine.TurnSignal
import kr.aigyeot.engine.VehicleSample

// 차량 사건으로 바꾸는 순수 함수. SDK 연결(Android.kt)은 이것만 부른다.

/** AAOS `VehicleGear` 비트값(GEAR_SELECTION 실측, official_audit_1108 1절): 4=P·8=D·2=R·1=N. 그 밖은 내지 않는다. */
internal fun gearOf(v: Int?): Gear? = when (v) {
    4 -> Gear.P
    8 -> Gear.D
    2 -> Gear.R
    1 -> Gear.N
    else -> null
}

internal fun turnOf(v: TurnSignalValue?): TurnSignal = when (v) {
    TurnSignalValue.LEFT -> TurnSignal.LEFT
    TurnSignalValue.RIGHT -> TurnSignal.RIGHT
    else -> TurnSignal.NONE
}

/** 3-15: MODE_IN_CALL(2)·MODE_IN_COMMUNICATION(3). */
internal fun inCall(mode: Int) = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION

/** 쉼터 id. TBT에는 id가 없어 종류와 이름으로 만든다(같은 쉼터가 갱신마다 같은 id, 이름이 같은 휴게소·졸음쉼터는 다른 id). */
internal fun restId(kind: RestKind, name: String) =
    "tbt:" + (if (kind == RestKind.DROWSY_SHELTER) "shelter" else "rest") + ":" + name.filterNot(Char::isWhitespace)

/**
 * TBT의 휴게소(TURN_ENTER_SERVICEAREA)·졸음쉼터(TURN_RESTAREA)를 쉼터 후보로. TBT distance(m)는 앞 지점부터의 구간 거리라
 * 모든 항목(회전·톨게이트 포함)을 순서대로 더해 km로 쓴다. 휴게소는 이름에 '휴게소'가 든 것만 받고 '하이패스센터'는 뺀다(
 * 뺀 항목의 거리는 누적에 든다). 수유실은 [nursing](공공데이터)이 정하고 모르면 없음. 마지막 TURN_WAY(경유지)보다 앞선 쉼터는 넣을 수 없다(경유지 뒤에 붙어 지나쳤다 돌아옴).
 */
internal fun restsOf(tbt: List<TBTInfo>, nursing: (String) -> Boolean): List<RestCandidate> {
    var sumM = 0.0
    val lastWay = tbt.indexOfLast { it.type == TurnType.TURN_WAY }
    return tbt.mapIndexedNotNull { i, t ->
        sumM += t.distance
        val name = t.description.trim()
        val kind = when (t.type) {
            TurnType.TURN_ENTER_SERVICEAREA -> if ("휴게소" in name) RestKind.REST_AREA else return@mapIndexedNotNull null
            TurnType.TURN_RESTAREA -> RestKind.DROWSY_SHELTER
            else -> return@mapIndexedNotNull null
        }
        if (name.isEmpty()) return@mapIndexedNotNull null
        RestCandidate(restId(kind, name), name, kind, sumM / 1000.0, kind == RestKind.REST_AREA && nursing(name),
            addable = i > lastWay && RestAreas.placeable(name, RestAreas.rowsOf(kind)))   // 넣기(place)와 같은 판단
    }
}

/** 내비 신호를 모아 RouteState 하나로. 바뀐 것이 있을 때만 낸다(at 제외). 받은 적이 없으면 내지 않는다(판단 코어가 '없음'으로 본다). */
internal class RouteFeed(
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    private val requestTbt: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private var routing: Boolean? = null
    private var remainSec: Int? = null
    private var remainKm: Double? = null
    private var rests: List<RestCandidate> = emptyList()
    private var restsPending = false  // freshRoute가 다시 요청한 TBT를 아직 받지 못함(빈 rests는 '쉼터 없음'이 아니다)
    private var candidate: Pair<List<RestCandidate>, Double>? = null   // 안내 시작 뒤 남은 거리를 알기 전에 온 첫 쉼터 목록(TBT 거리 합과 함께). 거리가 오면 견준다
    private var last: RouteState? = null
    private var initialOpen = true    // 진행 중 목적지 조회 응답을 받을 수 있는지: routing(false)가 오면 닫고 forget이 다시 연다
    private var drivingSeen = false   // 이번 구독에서 driving()으로 시간·거리를 받았는지

    /**
     * NavigationStatus == ROUTE_DRIVE 여부. isRouting·끝·취소 알림은 넘기지 않는다: 시작과 섞여 와 안내 중 상태를 덮는다.
     * 거짓이 오면 받아 둔 쉼터를 비운다(그 뒤 온 TBT는 저장해 안내 중이 되면 낸다). 쉼터는 안내 중일 때만 낸다.
     */
    fun routing(on: Boolean) {
        val was = routing == true
        routing = on
        if (on && !was) freshRoute()
        if (!on) { initialOpen = false; remainSec = null; remainKm = null; rests = emptyList(); restsPending = false; candidate = null }
        emit()
    }
    /** 새 구독 회차(PleosNavi.start): 앞 회차의 상태를 잊어 재시작 복원(initial)이 막히지 않게 한다. 이미 낸 값은 새 값이 오면 바뀐다. */
    // 경로 값 모두 비운다: 재구독 뒤 옛 경로가 새 경로와 섞이거나 되살아나지 않게
    fun forget() {
        initialOpen = true; routing = null; drivingSeen = false; last = null
        remainSec = null; remainKm = null; rests = emptyList(); restsPending = false; candidate = null
    }
    /** DrivingInfo 남은 시간(초). 0 이하는 끝난 경로(duration=0)라 안내 중으로 보지 않는다. 상태를 받은 적이 없을 때만 안내 중으로 본다. */
    fun driving(sec: Int?, distanceM: Number? = null) {
        val s = sec?.takeIf { it > 0 }
        if (s != null) drivingSeen = true   // 빈 DrivingInfo(null·0)는 initial을 막지 않는다
        if (s != null && routing == null) { routing = true; freshRoute() }
        remainSec = s; remainKm = kmOf(s, distanceM); judgeCandidate(); emit()
    }
    /** 재시작 때 getDestinationInfo의 remainTime(초, 같은 쪽 WaypointInfo 값으로 본 단위). 상태를 받은 적이 없고 0보다 클 때만 안내 중과 남은 시간으로 넣는다. */
    fun initial(sec: Int, distanceM: Number? = null) {
        if (!initialOpen || drivingSeen || sec <= 0) return   // 받은 뒤면 더 최신인 DrivingInfo를 지킨다. 안내 중이 아니면 routing이 거짓일 때 채워도 emit이 내지 않는다
        if (routing == null) { routing = true; freshRoute() }
        remainSec = sec; remainKm = kmOf(sec, distanceM); judgeCandidate(); emit()
    }
    /** 남은 거리(m) → km. 남은 시간이 없거나 0 이하면 null(remainingSec과 함께 낸다). */
    private fun kmOf(sec: Int?, distanceM: Number?) = if (sec == null) null else distanceM?.toDouble()?.div(1000)?.takeIf { it > 0 }
    /** 안내 시작 전환 때 쉼터와 남은 시간·거리·후보를 버리고(옛 경로 값이 섞이지 않게) 지금 경로의 TBT를 다시 요청한다. 시간·거리는 새 DrivingInfo로만 채운다. 응답 전까지 쉼터는 빈 목록이고 restsPending. */
    private fun freshRoute() { rests = emptyList(); remainSec = null; remainKm = null; candidate = null; restsPending = true; requestTbt() }
    /**
     * [totalKm]은 TBT 구간 거리 합(km). 기다리는 중의 첫 목록은 그 합이 남은 거리와 [TBT_KM_TOLERANCE] 안일 때만 받는다
     * (전환 전에 요청한 옛 경로의 늦은 응답을 거른다). 남은 거리를 모르면 후보로 두고 거리가 오면 견준다. null이면 견주지 않는다(시험·장면용).
     */
    fun rests(list: List<RestCandidate>, totalKm: Double? = null) {
        if (restsPending && totalKm != null) { candidate = list to totalKm; judgeCandidate(); emit(); return }
        rests = list; restsPending = false; candidate = null; emit()
    }
    private fun judgeCandidate() {
        val (list, km) = candidate ?: return
        val remain = remainKm ?: return
        candidate = null
        if (kotlin.math.abs(km - remain) <= remain * TBT_KM_TOLERANCE) { rests = list; restsPending = false }
        else log("쉼터 목록 버림(TBT 합 ${"%.1f".format(km)}km, 남은 거리 ${"%.1f".format(remain)}km)")
    }

    /** 지금 상태를 중복 억제 없이 다시 낸다(장면 끝 복원이 판단 코어의 경로를 비웠을 때). 이번 구독에서 낸 적이 없으면 내지 않는다. */
    fun reemit() { last?.let { post(it.copy(at = clock())) } }

    private fun emit() {
        val on = routing == true
        val e = RouteState(clock(), on, if (on) remainSec else null, if (on) rests else emptyList(), if (on) remainKm else null, on && restsPending)
        if (last?.copy(at = e.at) == e) return
        last = e
        post(e)
    }
}

/**
 * VehicleSample. 신호 넷 중 무엇이 오든 최신 값으로 한 표본을 내되 100ms보다 촘촘히 내지 않는다(최대 10Hz).
 * 폴링 타이머는 없다. 조향·속도를 한 번도 받지 못했으면 내지 않는다(0 km/h로 지어내지 않는다). 속도 원값은 m/s다.
 */
internal class Sampler(private val clock: () -> Long, private val post: (Event) -> Unit, private val gapMs: Long = 100) {
    private var speedKmh: Double? = null
    private var steer: Double? = null
    private var brake = false
    private var turn = TurnSignal.NONE
    private var lastAt: Long? = null

    fun speedMps(v: Float?) { if (v != null) speedKmh = v * 3.6; emit() }
    fun steer(deg: Float?) { if (deg != null) steer = deg.toDouble(); emit() }
    fun brake(on: Boolean?) { if (on != null) brake = on; emit() }
    fun turn(t: TurnSignal) { turn = t; emit() }

    private fun emit() {
        val s = steer ?: return
        val v = speedKmh ?: return
        val now = clock()
        if (lastAt?.let { now - it < gapMs } == true) return
        lastAt = now
        post(VehicleSample(now, v, brake, s, turn))
    }

    /** 지금 표본을 간격 제한 없이 다시 낸다(장면 끝). 조향·속도를 받은 적이 없으면 내지 않는다. */
    fun reemit() { lastAt = null; emit() }
}

/** 같은 값이 거듭 오면(리스너 초기값·재구독) 한 번만 낸다. */
internal class Changed {
    private val last = HashMap<String, Pair<Any, () -> Event>>()
    fun <T : Any> emit(key: String, value: T, event: () -> Event, post: (Event) -> Unit) {
        if (last[key]?.first == value) return
        last[key] = value to event
        post(event())
    }

    /** 마지막으로 받은 값(장면 끝 복원이 지금 기어를 기준선으로 쓴다). 받은 적이 없으면 null. */
    fun value(key: String): Any? = last[key]?.first

    /**
     * 낸 값마다 지금 시각으로 중복 억제 없이 다시 낸다(장면 끝). 낸 적이 없는 신호는 내지 않는다.
     * 순서는 기어 → 통화 → 뒷문 → 열선 → 실내 온도(키 앞부분)로 고정한다(뒷문 판단이 지금 기어를 본다). 표본·경로는 호출한 쪽이 그 뒤에 낸다.
     */
    fun reemit(post: (Event) -> Unit) = last.entries
        .sortedBy { (k, _) -> REEMIT_ORDER.indexOfFirst { k.startsWith(it) }.let { if (it < 0) REEMIT_ORDER.size else it } }
        .forEach { post(it.value.second()) }

    private companion object { val REEMIT_ORDER = listOf("gear", "call", "door", "seat", "cabin") }
}

/** 첫 쉼터 목록의 TBT 거리 합과 남은 거리가 맞는 허용 비율(실측 368.6km ≈ 378km, 2.5%). 설계값, 에뮬레이터 미판정. */
internal const val TBT_KM_TOLERANCE = 0.15
