package kr.aigyeot.app.exec.android

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kr.aigyeot.app.exec.Changed
import kr.aigyeot.app.exec.ControlPort
import kr.aigyeot.app.exec.Sampler
import kr.aigyeot.app.exec.gearOf
import kr.aigyeot.app.exec.inCall
import kr.aigyeot.app.exec.turnOf
import kr.aigyeot.engine.Area
import kr.aigyeot.engine.CabinTemp
import kr.aigyeot.engine.CallMode
import kr.aigyeot.engine.ControlKind
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.RearDoor
import kr.aigyeot.engine.SeatHeat
import kr.aigyeot.engine.Side

// ---- 차량 신호·제어 ----

private fun seat(a: Area) = SeatArea(SeatValue.valueOf(a.name))
private fun window(a: Area) = WindowArea(WindowValue.valueOf(a.name))
private val REAR_SEATS = listOf(Area.ROW_2_LEFT, Area.ROW_2_RIGHT)

/**
 * 차량 구독(계약 3.3)과 제어(4.2). C71: 리스너를 받는 4인자 Car.createCar로 붙고, CarService가 다시 준비되면 다시 구독한다.
 * Pleos Vehicle은 준비 신호가 없어 프로브처럼 initialize 1.5초 뒤 구독한다(설계값).
 */
internal class PleosCar(private val ctx: Context, private val ex: ExecThread, private val clock: () -> Long, private val post: (Event) -> Unit) : ControlPort {
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var props: CarPropertyManager? = null           // 속도·기어가 함께 쓰는 매니저 참조(구독 중에만). '속도 등록됨'이 아니다
    private var speedOn = false                              // 속도 등록 성공(CAR_SPEED 권한이 있을 때만 true). C321 resubscribeSpeed가 본다
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var changed = Changed()                      // start마다 새로: 다시 시작하면 지금 기어·통화를 다시 낸다
    private val sampler = Sampler(clock, post)
    private var cancelSub: () -> Unit = {}

    private fun <T : Any> emit(key: String, v: T, e: () -> Event) = changed.emit(key, v, e, post)
    private fun emitGear(raw: Int?) { gearOf(raw)?.let { emit("gear", it) { GearChanged(clock(), it) } } }

    /** 장면 끝(V12): 받은 차량 신호의 지금 값을 중복 억제 없이 다시 낸다(Exec.reemitAll, exec 잠금 안). */
    fun reemit() { changed.reemit(post); sampler.reemit() }

    /** C328: 마지막으로 받은 기어(없으면 null). exec 잠금 안에서 부른다. */
    fun lastGear(): kr.aigyeot.engine.Gear? = changed.value("gear") as? kr.aigyeot.engine.Gear

    /** 한 구독(subscribe)의 신호 리스너 묶음. 콜백 첫 줄에서 구독 번호가 다르면 무시한다(CarService 재연결로 같은 start 안에서 다시 구독해도 옛 구독의 늦은 응답을 버린다)(앞 회차의 늦은 응답이 새 구독을 건드리지 않게). */
    private inner class Listeners(val sub: Int) {
        fun on(f: () -> Unit) = ex.guard { if (subGen == sub) f() }
        val gearL = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) = on { emitGear(v.value as? Int) }
            override fun onErrorEvent(propId: Int, zone: Int) { Log.w(TAG, "gear error $zone") }
        }
        val doorL = Side.entries.associateWith { side ->
            object : DoorPositionListener {
                override fun onPositionUpdated(p: Int?) = on { door(side, p) }
                override fun onFailed(e: Exception) { Log.w(TAG, "door $side", e) }
            }
        }
        val seatL = REAR_SEATS.associateWith { a ->
            object : HvacSeatTemperatureListener {
                override fun onHvacSeatTemperatureUpdated(l: Int?) = on { l?.let { emit("seat$a", it) { SeatHeat(clock(), a, it) } } }
                override fun onFailed(e: Exception) { Log.w(TAG, "seat $a", e) }
            }
        }
        val cabinL = object : HvacTemperatureListener {
            override fun onHvacTemperatureUpdated(v: Float?) = on { readCabin(sub) }   // 단위가 정해진 읽기로 다시 읽는다
            override fun onFailed(e: Exception) { Log.w(TAG, "cabin", e) }
        }
        val steerL = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) = on { sampler.steer(angle) }
            override fun onFailed(e: Exception) { Log.w(TAG, "steer", e) }
        }
        val brakeL = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) = on { sampler.brake(pressed) }
            override fun onFailed(e: Exception) { Log.w(TAG, "brake", e) }
        }
        val turnL = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(v: TurnSignalValue?) = on { sampler.turn(turnOf(v)) }
            override fun onFailed(e: Exception) { Log.w(TAG, "turn", e) }
        }
        val speedL = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) = on { sampler.speedMps(v.value as? Float) }
            override fun onErrorEvent(propId: Int, zone: Int) { Log.w(TAG, "speed error $zone") }
        }
    }
    private var ls: Listeners? = null
    private var modeL: AudioManager.OnModeChangedListener? = null   // start마다 새로(시작 번호를 잡는다)

    private fun door(side: Side, pos: Int?) { if (pos == null) return; val open = pos > 0; emit("door$side", open) { RearDoor(clock(), side, open) } }   // C321 ③: 위치를 못 읽으면 닫힘으로 단정하지 않는다
    private fun call(mode: Int) { val c = inCall(mode); emit("call", c) { CallMode(clock(), c) } }
    private fun readCabin(sub: Int) = vehicle?.getHVAC()?.getHvacTemperatureCurrent(seat(Area.ROW_1_LEFT), TemperatureUnit.CELSIUS,
        { v -> ex.guard { if (subGen == sub) v?.let { emit("cabin", it.toDouble()) { CabinTemp(clock(), it.toDouble()) } } } }, { e -> Log.w(TAG, "cabin", e) })

    private var readyCar: Car? = null                     // Car가 준비된 동안만(끊기면·stop이면 null). 권한 허용 뒤 다시 구독할 때 쓴다

    private var gen = 0                                 // 시작 번호(start·stop마다 +1): Car 수명 콜백·첫 기어 읽기·구독 예약·통화 모드 리스너가 잡는다
    private var subGen = 0                                // 구독 번호(subscribe·unsubscribe마다 +1): Listeners 묶음·subscribe 안의 get 콜백·readCabin이 잡는다

    fun start() {
        val my = ++gen
        changed = Changed()
        vehicle = Vehicle(ctx).also { runCatching { it.initialize() }.onFailure { e -> Log.w(TAG, "vehicle init", e) } }
        car =Car.createCar(ctx, ex.handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            ex.guard {
                if (gen != my) return@guard                                   // 앞 회차 Car의 늦은 수명 콜백
                cancelSub()
                readyCar = if (ready) c else null
                if (ready) {
                    readGear(c)                                                   // 구독 예약(1.5초)을 기다리지 않고 지금 기어를 한 번 읽는다. 실패하면 조용히 넘어가고 구독 때 다시 읽는다(Changed가 중복을 막는다)
                    cancelSub = ex.after(SUBSCRIBE_DELAY_MS) { if (gen == my) subscribe(c) }
                } else { subGen++; props = null; speedOn = false }   // 끊기면 다시 준비될 때 새로. 다음 subscribe 전까지 옛 구독의 늦은 콜백도 막는다
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            // executor에 이미 넣은 옛 콜백은 제거해도 취소되지 않으므로 시작 번호로 거른다
            val ml = AudioManager.OnModeChangedListener { m -> ex.guard { if (gen == my) call(m) } }
            modeL = ml
            am.addOnModeChangedListener(ctx.mainExecutor, ml)
        }
        call(am.mode)
    }

    /**
     * C321 ①: 속도 권한(CAR_SPEED)이 구독 뒤에 허용되면 같은 입구로 다시 구독한다(exec 잠금 안). 속도가 이미 등록돼 있거나 Car가 준비되지 않았으면 아무것도 안 한다
     * (준비 콜백이 어차피 구독한다). 다시 구독해도 기어·뒷문·열선·온도는 [Changed]가 같은 값을 걸러, reemit과도 어긋나지 않는다.
     */
    fun resubscribeSpeed() { val c = readyCar ?: return; if (!speedOn) subscribe(c) }

    /** 공개 AAOS 경로(CarPropertyManager GEAR_SELECTION, CAR_POWERTRAIN, C324)로 지금 기어를 한 번 읽는다. exec 잠금 안에서만 부른다. */
    private fun readGear(c: Car) {
        runCatching { emitGear((c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)) }
            .onFailure { Log.w(TAG, "gear read", it) }
    }

    private fun subscribe(c: Car) {
        unsubscribe()
        val v = vehicle ?: return
        val sub = ++subGen
        val l = Listeners(sub).also { ls = it }
        runCatching {
            l.doorL.forEach { (side, d) -> v.getDoor().registerDoorPosition(DoorArea(if (side == Side.LEFT) DoorValue.ROW_2_LEFT else DoorValue.ROW_2_RIGHT), d) }
            l.seatL.forEach { (a, x) -> v.getSeat().registerHvacSeatTemperature(seat(a), x) }
            l.seatL.keys.forEach { a -> v.getSeat().getHvacSeatTemperature(seat(a), { h -> l.on { h?.let { emit("seat$a", it) { SeatHeat(clock(), a, it) } } } }, {}) }
            v.getHVAC().registerHvacTemperatureCurrent(seat(Area.ROW_1_LEFT), l.cabinL)
            readCabin(sub)
            v.getSteeringWheel().registerSteeringWheelAngle(l.steerL)
            v.getBrake().registerBrakePedalStatus(l.brakeL)
            v.getTurnSignal().registerTurnSignalState(l.turnL)
        }.onFailure { Log.w(TAG, "subscribe vehicle", it) }
        val m = runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.onFailure { Log.w(TAG, "property manager", it) }.getOrNull() ?: return
        props = m
        runCatching {
            // C326: 권한이 없으면 예외 없이 false를 돌려준다
            if (!m.registerCallback(l.gearL, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)) Log.w(TAG, "subscribe gear(CAR_POWERTRAIN 권한?)")
            emitGear(m.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0))
        }.onFailure { Log.w(TAG, "subscribe gear(CAR_POWERTRAIN 권한?)", it) }
        runCatching {
            speedOn = m.registerCallback(l.speedL, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
            if (!speedOn) Log.w(TAG, "subscribe speed(CAR_SPEED 권한?)")
        }.onFailure { Log.w(TAG, "subscribe speed(CAR_SPEED 권한?)", it) }
    }

    /** 그 회차의 묶음을 해제한다. */
    private fun unsubscribe() {
        subGen++                                          // 옛 구독의 늦은 콜백을 먼저 막는다
        val l = ls ?: return
        ls = null
        val v = vehicle
        if (v != null) {
            l.doorL.forEach { (side, d) -> runCatching { v.getDoor().unregisterDoorPosition(DoorArea(if (side == Side.LEFT) DoorValue.ROW_2_LEFT else DoorValue.ROW_2_RIGHT), d) } }
            l.seatL.forEach { (a, x) -> runCatching { v.getSeat().unregisterHvacSeatTemperature(seat(a), x) } }
            runCatching { v.getHVAC().unregisterHvacTemperatureCurrent(seat(Area.ROW_1_LEFT), l.cabinL) }
            runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(l.steerL) }
            runCatching { v.getBrake().unregisterBrakePedalStatus(l.brakeL) }
            runCatching { v.getTurnSignal().unregisterTurnSignalState(l.turnL) }
        }
        runCatching { props?.unregisterCallback(l.gearL) }
        runCatching { props?.unregisterCallback(l.speedL) }
        props = null; speedOn = false
    }

    fun stop() {
        gen++
        cancelSub()
        unsubscribe()
        modeL?.let { ml -> runCatching { am.removeOnModeChangedListener(ml) } }; modeL = null
        runCatching { car?.disconnect() }
        runCatching { vehicle?.release() }
        car = null; vehicle = null; readyCar = null
    }

    override fun read(kind: ControlKind, area: Area, cb: (Double?) -> Unit) {
        val v = vehicle ?: return cb(null)
        val fail: (Exception) -> Unit = { ex.guard { cb(null) } }
        try {
            when (kind) {
                ControlKind.SEAT_HEAT -> v.getSeat().getHvacSeatTemperature(seat(area), { x -> ex.guard { cb(x?.toDouble()) } }, fail)
                ControlKind.WINDOW_LOCK -> v.getWindow().getCurrentWindowLockState(window(area), { x -> ex.guard { cb(x?.let { if (it) 1.0 else 0.0 }) } }, fail)
                ControlKind.READING_LIGHT -> v.getLight().getReadingLightSwitch(seat(area), { x -> ex.guard { cb(x?.let { if (it == LightSwitch.ON) 1.0 else 0.0 }) } }, fail)
                ControlKind.HVAC_TEMP -> v.getHVAC().getHvacTemperature(seat(area), TemperatureUnit.CELSIUS, { x -> ex.guard { cb(x?.toDouble()) } }, fail)
            }
        } catch (e: Exception) { cb(null) }
    }

    override fun write(kind: ControlKind, area: Area, value: Double, ack: (Boolean) -> Unit) {
        val v = vehicle ?: return ack(false)
        val fail: (Exception) -> Unit = { e -> ex.guard { Log.w(TAG, "write $kind $area ${e.message}"); ack(false) } }
        try {
            when (kind) {
                ControlKind.SEAT_HEAT -> v.getSeat().setHvacSeatTemperature(seat(area), value.toInt(), { _, _ -> ex.guard { ack(true) } }, fail)
                ControlKind.WINDOW_LOCK -> v.getWindow().setWindowLock(window(area), value == 1.0, { _, _ -> ex.guard { ack(true) } }, fail)
                ControlKind.READING_LIGHT -> v.getLight().setReadingLightSwitch(seat(area), if (value == 1.0) LightSwitch.ON else LightSwitch.OFF, { _ -> ex.guard { ack(true) } }, fail)
                ControlKind.HVAC_TEMP -> v.getHVAC().setHvacTemperature(seat(area), value.toFloat(), TemperatureUnit.CELSIUS, { _, _ -> ex.guard { ack(true) } }, fail)
            }
        } catch (e: Exception) { ack(false) }
    }

    /** 공조 설정 범위의 최솟값(Pleos `checkHvacTemperatureCapabilities`, CAR_CLIMATE 권한). 못 읽으면 null(C101). */
    override fun minTemp(area: Area, cb: (Double?) -> Unit) {
        val v = vehicle ?: return cb(null)
        try {
            v.getHVAC().checkHvacTemperatureCapabilities({ _, m, _ -> ex.guard { cb(m[seat(area)]?.minValue?.toDouble()) } }, { ex.guard { cb(null) } })
        } catch (e: Exception) { cb(null) }
    }

    private companion object { const val SUBSCRIBE_DELAY_MS = 1_500L }
}
