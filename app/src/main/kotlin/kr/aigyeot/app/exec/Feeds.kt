package kr.aigyeot.app.exec

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.RouteState
import kr.aigyeot.engine.TurnSignal
import kr.aigyeot.engine.VehicleSample

// 계약 3.3 차량 사건으로 바꾸는 순수 함수. SDK 연결(Android.kt)은 이것만 부른다.

internal fun gearOf(g: VehicleGear?): Gear? = when (g) {
    VehicleGear.GEAR_PARK -> Gear.P
    VehicleGear.GEAR_REVERSE -> Gear.R
    VehicleGear.GEAR_NEUTRAL -> Gear.N
    null, VehicleGear.GEAR_UNKNOWN -> null
    else -> Gear.D                                   // DRIVE와 수동 단(1~9)
}

internal fun turnOf(v: TurnSignalValue?): TurnSignal = when (v) {
    TurnSignalValue.LEFT -> TurnSignal.LEFT
    TurnSignalValue.RIGHT -> TurnSignal.RIGHT
    else -> TurnSignal.NONE
}

/** 3-15: MODE_IN_CALL(2)·MODE_IN_COMMUNICATION(3). */
internal fun inCall(mode: Int) = mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION

/** 쉼터 id. TBT에는 id가 없어 종류와 이름으로 만든다(같은 쉼터가 갱신마다 같은 id, 이름이 같은 휴게소·졸음쉼터는 다른 id). */
internal fun restId(kind: RestKind, name: String) = "tbt:" + (if (kind == RestKind.DROWSY_SHELTER) "shelter" else "rest") + ":" + name.filterNot(Char::isWhitespace)

/**
 * TBT의 휴게소(TURN_ENTER_SERVICEAREA)·졸음쉼터(TURN_RESTAREA)를 쉼터 후보로. TBT distance(m)는 앞 지점부터의 구간 거리라
 * 모든 항목(회전·톨게이트 포함)을 순서대로 더해 km로 쓴다(C113). 휴게소는 이름에 '휴게소'가 든 것만 받고 '하이패스센터'는 뺀다(C115,
 * 뺀 항목의 거리는 누적에 든다). 수유실은 [nursing](공공데이터, G21)이 정하고 모르면 없음.
 */
internal fun restsOf(tbt: List<TBTInfo>, nursing: (String) -> Boolean): List<RestCandidate> {
    var sumM = 0.0
    return tbt.mapNotNull { t ->
        sumM += t.distance
        val name = t.description.trim()
        val kind = when (t.type) {
            TurnType.TURN_ENTER_SERVICEAREA -> if ("휴게소" in name) RestKind.REST_AREA else return@mapNotNull null
            TurnType.TURN_RESTAREA -> RestKind.DROWSY_SHELTER
            else -> return@mapNotNull null
        }
        if (name.isEmpty()) return@mapNotNull null
        RestCandidate(restId(kind, name), name, kind, sumM / 1000.0, kind == RestKind.REST_AREA && nursing(name))
    }
}

/** 내비 신호를 모아 RouteState 하나로. 바뀐 것이 있을 때만 낸다(at 제외). 받은 적이 없으면 내지 않는다(C22: 판단 코어가 '없음'으로 본다). */
internal class RouteFeed(private val clock: () -> Long, private val post: (Event) -> Unit) {
    private var routing: Boolean? = null
    private var remainSec: Int? = null
    private var remainKm: Double? = null
    private var rests: List<RestCandidate> = emptyList()
    private var last: RouteState? = null

    /** NavigationStatus == ROUTE_DRIVE 여부(C114). isRouting·끝·취소 알림은 넘기지 않는다: 시작과 섞여 와 안내 중 상태를 덮는다. 거짓이 오면 받아 둔 쉼터를 비운다(그 뒤 온 TBT는 저장해 안내 중이 되면 낸다). 쉼터는 안내 중일 때만 낸다. */
    fun routing(on: Boolean) { routing = on; if (!on) { remainSec = null; remainKm = null; rests = emptyList() }; emit() }
    /** 새 구독 회차(PleosNavi.start): 앞 회차의 상태를 잊어 재시작 복원(initial)이 막히지 않게 한다. 이미 낸 값은 새 값이 오면 바뀐다. */
    fun forget() { routing = null; last = null; remainSec = null; remainKm = null; rests = emptyList() }   // 경로 값 모두 비운다: 재구독 뒤 옛 경로가 새 경로와 섞이거나 되살아나지 않게(C130 ②, C135 ③)
    /** DrivingInfo 남은 시간(초). 0 이하는 끝난 경로(duration=0)라 안내 중으로 보지 않는다. 상태를 받은 적이 없을 때만 안내 중으로 본다. */
    fun driving(sec: Int?, distanceM: Number? = null) {
        val s = sec?.takeIf { it > 0 }
        if (s != null && routing == null) routing = true
        remainSec = s; remainKm = kmOf(s, distanceM); emit()
    }
    /** 재시작 때 getDestinationInfo의 remainTime(초, 같은 쪽 WaypointInfo 값으로 본 단위). 상태를 받은 적이 없고 0보다 클 때만 안내 중과 남은 시간으로 넣는다. */
    fun initial(sec: Int, distanceM: Number? = null) { if (routing == null && sec > 0) { routing = true; remainSec = sec; remainKm = kmOf(sec, distanceM); emit() } }
    /** 남은 거리(m) → km. 남은 시간이 없거나 0 이하면 null(계약 3.3: remainingSec과 함께 낸다). */
    private fun kmOf(sec: Int?, distanceM: Number?) = if (sec == null) null else distanceM?.toDouble()?.div(1000)?.takeIf { it > 0 }
    fun rests(list: List<RestCandidate>) { rests = list; emit() }

    /** 지금 상태를 중복 억제 없이 다시 낸다(복원이 판단 코어의 경로를 비웠을 때, C130). 이번 구독에서 낸 적이 없으면 내지 않는다. */
    fun reemit() { last?.let { post(it.copy(at = clock())) } }

    private fun emit() {
        val on = routing == true
        val e = RouteState(clock(), on, if (on) remainSec else null, if (on) rests else emptyList(), if (on) remainKm else null)
        if (last?.copy(at = e.at) == e) return
        last = e
        post(e)
    }
}

/**
 * VehicleSample(F9·F17). 신호 넷 중 무엇이 오든 최신 값으로 한 표본을 내되 100ms보다 촘촘히 내지 않는다(최대 10Hz).
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
}

/** 같은 값이 거듭 오면(리스너 초기값·재구독) 한 번만 낸다. */
internal class Changed {
    private val last = HashMap<String, Any>()
    fun <T : Any> emit(key: String, value: T, event: () -> Event, post: (Event) -> Unit) {
        if (last[key] == value) return
        last[key] = value
        post(event())
    }
}
