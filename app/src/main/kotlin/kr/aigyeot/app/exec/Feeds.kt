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
 * TBT의 휴게소(TURN_ENTER_SERVICEAREA)·졸음쉼터(TURN_RESTAREA)를 쉼터 후보로. 거리는 m로 보고 km로 바꾼다
 * (TBT 거리·유형의 뜻은 첫날 실제 값으로 정한다, C75 미판정). 수유실은 [nursing](공공데이터, G21)이 정하고 모르면 없음.
 */
internal fun restsOf(tbt: List<TBTInfo>, nursing: (String) -> Boolean): List<RestCandidate> = tbt.mapNotNull { t ->
    val kind = when (t.type) {
        TurnType.TURN_ENTER_SERVICEAREA -> RestKind.REST_AREA
        TurnType.TURN_RESTAREA -> RestKind.DROWSY_SHELTER
        else -> return@mapNotNull null
    }
    val name = t.description.trim().ifEmpty { return@mapNotNull null }
    RestCandidate(restId(kind, name), name, kind, t.distance / 1000.0, kind == RestKind.REST_AREA && nursing(name))
}

/** 내비 신호를 모아 RouteState 하나로. 바뀐 것이 있을 때만 낸다(at 제외). 받은 적이 없으면 내지 않는다(C22: 판단 코어가 '없음'으로 본다). */
internal class RouteFeed(private val clock: () -> Long, private val post: (Event) -> Unit) {
    private var routing: Boolean? = null
    private var remainSec: Int? = null
    private var rests: List<RestCandidate> = emptyList()
    private var last: RouteState? = null

    fun routing(on: Boolean) { routing = on; if (!on) { remainSec = null; rests = emptyList() }; emit() }   // 끝난 안내의 쉼터를 남기지 않는다(새 TBT가 오면 다시 찬다)
    fun remain(sec: Int?) { remainSec = sec; emit() }
    fun rests(list: List<RestCandidate>) { rests = list; emit() }

    private fun emit() {
        val on = routing == true
        val e = RouteState(clock(), on, if (on) remainSec else null, rests)
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
