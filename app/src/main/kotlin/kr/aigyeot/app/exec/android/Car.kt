package kr.aigyeot.app.exec.android

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
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
import kr.aigyeot.app.exec.AmbientArea
import kr.aigyeot.app.exec.AmbientPort
import kr.aigyeot.app.exec.AutoColor
import kr.aigyeot.app.exec.Changed
import kr.aigyeot.app.exec.ReadyProbe
import kr.aigyeot.app.exec.notSupported
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
internal class PleosCar(private val ctx: Context, private val ex: ExecThread, private val clock: () -> Long, private val post: (Event) -> Unit) : ControlPort, AmbientPort {
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
        /** C367: 이 구독에서 리스너 값이 이미 온 키(gear·seatX). 초기 조회(getIntProperty·getHvacSeatTemperature)는 binder 스레드에서 돌아 콜백보다 늦게 처리될 수 있다.
         *  리스너 값이 하나라도 왔으면 그것이 더 새 값이니 초기 조회는 버린다(Changed는 다른 값을 걸러 주지 않는다). 잠금 안에서만 만진다. */
        val live = HashSet<String>()
        val gearL = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) = on { live += "gear"; emitGear(v.value as? Int) }
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
                override fun onHvacSeatTemperatureUpdated(l: Int?) = on { l?.let { live += "seat$a"; emit("seat$a", it) { SeatHeat(clock(), a, it) } } }
                override fun onFailed(e: Exception) { Log.w(TAG, "seat $a", e) }
            }
        }
        val cabinL = object : HvacTemperatureListener {
            override fun onHvacTemperatureUpdated(v: Float?) = on { vehicle?.let { veh -> ex.off.run { readCabin(veh, sub) } } }   // 단위가 정해진 읽기로 다시 읽는다(바인더라 잠금 밖 레인에서)
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
    // 바인더 호출(SDK get*/register*·Car 연결)은 잠금 밖 레인(ex.off)에서 한다. 잠금 안(ex.guard)은 상태 칸만 만진다(OffLock 설명).
    private fun readCabin(v: Vehicle, sub: Int) = v.getHVAC().getHvacTemperatureCurrent(seat(Area.ROW_1_LEFT), TemperatureUnit.CELSIUS,
        { x -> ex.guard { if (subGen == sub) x?.let { emit("cabin", it.toDouble()) { CabinTemp(clock(), it.toDouble()) } } } }, { e -> Log.w(TAG, "cabin", e) })

    private var readyCar: Car? = null                     // Car가 준비된 동안만(끊기면·stop이면 null). 권한 허용 뒤 다시 구독할 때 쓴다

    /** 차 연결(Vehicle 초기화)이 끝났는가(잠금 안에서 읽는다). Exec의 CarGate가 차 제어 명령을 이것으로 미룬다. */
    fun isReady(): Boolean = vehicle != null && probe?.ready == true
    private var probe: ReadyProbe? = null
    /** 연결이 준비되는 순간(잠금 안) 부를 일. Exec가 등록한다. */
    var onReady: () -> Unit = {}

    private var gen = 0                                 // 시작 번호(start·stop마다 +1): Car 수명 콜백·첫 기어 읽기·구독 예약·통화 모드 리스너가 잡는다
    private var subGen = 0                                // 구독 번호(subscribe·unsubscribe마다 +1): Listeners 묶음·subscribe 안의 get 콜백·readCabin이 잡는다

    /** 잠금 안에서 부른다. Vehicle·Car 연결과 첫 읽기는 레인에서 하고(바인더), 끝나면 잠금 안에서 칸에 넣는다. */
    fun start() {
        val my = ++gen
        changed = Changed()
        ex.off.run {
            // C370: 초기화 단계는 서로 막지 않는다. 한 단계가 던져도 나머지(차 제어 준비·Car 연결·통화 감시)는 계속한다. 던진 단계의 칸은 null로 둔다
            val v: Vehicle? = runCatching { Vehicle(ctx) }.onFailure { e -> Log.w(TAG, "vehicle create", e) }.getOrNull()
            // initialize가 던져도 v는 그대로 쓴다(전과 같다): 준비 여부는 아래 ReadyProbe가 capability 응답으로 판단한다
            v?.let { runCatching { it.initialize() }.onFailure { e -> Log.w(TAG, "vehicle init", e) } }
            // Car 수명 콜백은 main 스레드에서도 올 수 있다: 거기서는 아무 바인더도 부르지 않고 레인에 넘기기만 한다
            val c: Car? = runCatching { Car.createCar(ctx, ex.handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { cc, ready -> ex.off.run { onCarState(my, cc, ready) } } }
                .onFailure { e -> Log.w(TAG, "car connect", e) }.getOrNull()
            var ml: AudioManager.OnModeChangedListener? = null
            if (Build.VERSION.SDK_INT >= 31) {
                // executor에 이미 넣은 옛 콜백은 제거해도 취소되지 않으므로 시작 번호로 거른다
                val l = AudioManager.OnModeChangedListener { m -> ex.guard { if (gen == my) call(m) } }
                ml = runCatching { am.addOnModeChangedListener(ctx.mainExecutor, l); l }.onFailure { e -> Log.w(TAG, "call listener", e) }.getOrNull()   // 등록 실패면 null
            }
            val mode: Int? = runCatching { am.mode }.onFailure { e -> Log.w(TAG, "audio mode", e) }.getOrNull()   // 못 읽으면 null: CallMode를 내지 않는다
            val stale = synchronized(ex.lock) {
                if (gen != my) true
                else {
                    vehicle = v; car = c; modeL = ml; mode?.let { call(it) }
                    // initialize()는 서비스 연결을 비동기로 시작하고 완료 콜백이 없다(공식 문서). 가벼운 capability 읽기 하나가 처음 실제로 응답한 때를 준비로 본다(4.2 ①)
                    if (v != null) probe = ReadyProbe(ex, READY_PROBE_MS, { gen == my },
                        { ok, fail -> ex.off.run { v.getLight().checkAmbientLightBrightnessCapabilities({ _, _ -> ex.guard { ok() } }, { e -> ex.guard { fail(e) } }) } },
                        { onReady() }).also { it.start() }
                    false
                }
            }
            if (stale) { runCatching { ml?.let { am.removeOnModeChangedListener(it) } }; runCatching { c?.disconnect() }; runCatching { v?.release() } }   // 그사이 stop·다시 start
        }
    }

    /** 레인에서. Car 준비·끊김: 칸 정리는 잠금 안, 첫 기어 읽기(바인더)는 잠금 밖. */
    private fun onCarState(my: Int, c: Car, ready: Boolean) {
        val go = synchronized(ex.lock) {
            if (gen != my) return@synchronized false                       // 앞 회차 Car의 늦은 수명 콜백
            cancelSub()
            readyCar = if (ready) c else null
            if (!ready) { subGen++; props = null; speedOn = false }          // 끊기면 다시 준비될 때 새로. 다음 subscribe 전까지 옛 구독의 늦은 콜백도 막는다
            ready
        }
        if (!go) return
        // 구독 예약(1.5초)을 기다리지 않고 지금 기어를 한 번 읽는다. 실패하면 조용히 넘어가고 구독 때 다시 읽는다(Changed가 중복을 막는다)
        ex.off.read({ gearOnce(c) }) { g ->
            if (gen == my) {
                g?.let { emitGear(it) }
                cancelSub = ex.after(SUBSCRIBE_DELAY_MS) { if (gen == my) ex.off.run { subscribe(c, my) } }
            }
        }
    }

    /** 공개 AAOS 경로(CarPropertyManager GEAR_SELECTION, CAR_POWERTRAIN, C324)로 지금 기어를 한 번 읽는다. 바인더 호출이라 잠금 밖에서만 부른다. */
    private fun gearOnce(c: Car): Int? =
        runCatching { (c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }
            .onFailure { Log.w(TAG, "gear read", it) }.getOrNull()

    /**
     * C321 ①: 속도 권한(CAR_SPEED)이 구독 뒤에 허용되면 같은 입구로 다시 구독한다(잠금 안에서 부른다). 속도가 이미 등록돼 있거나 Car가 준비되지 않았으면 아무것도 안 한다
     * (준비 콜백이 어차피 구독한다). 다시 구독해도 기어·뒷문·열선·온도는 [Changed]가 같은 값을 걸러, reemit과도 어긋나지 않는다.
     */
    fun resubscribeSpeed() { val c = readyCar ?: return; if (!speedOn) { val my = gen; ex.off.run { subscribe(c, my) } } }

    /** 레인에서(잠금 밖). 옛 구독 해제·새 등록은 바인더라 잠금 밖, 칸 교체·결과 반영은 잠금 안. */
    private fun subscribe(c: Car, my: Int) {
        class Plan(val v: Vehicle, val sub: Int, val l: Listeners, val oldL: Listeners?, val oldProps: CarPropertyManager?)
        val plan = synchronized(ex.lock) {
            if (gen != my) return
            val v = vehicle ?: return
            val oldL = ls; val oldProps = props
            subGen++; ls = null; props = null; speedOn = false           // 옛 구독의 늦은 콜백을 먼저 막는다
            val sub = ++subGen
            Plan(v, sub, Listeners(sub).also { ls = it }, oldL, oldProps)
        }
        val v = plan.v; val l = plan.l; val sub = plan.sub
        unregister(v, plan.oldL, plan.oldProps)
        runCatching {
            l.doorL.forEach { (side, d) -> v.getDoor().registerDoorPosition(DoorArea(if (side == Side.LEFT) DoorValue.ROW_2_LEFT else DoorValue.ROW_2_RIGHT), d) }
            l.seatL.forEach { (a, x) -> v.getSeat().registerHvacSeatTemperature(seat(a), x) }
            l.seatL.keys.forEach { a -> v.getSeat().getHvacSeatTemperature(seat(a), { h -> l.on { h?.let { if ("seat$a" !in l.live) emit("seat$a", it) { SeatHeat(clock(), a, it) } } } }, {}) }   // C367: 리스너 값이 먼저 왔으면 늦은 초기 조회는 버린다
            v.getHVAC().registerHvacTemperatureCurrent(seat(Area.ROW_1_LEFT), l.cabinL)
            readCabin(v, sub)
            v.getSteeringWheel().registerSteeringWheelAngle(l.steerL)
            v.getBrake().registerBrakePedalStatus(l.brakeL)
            v.getTurnSignal().registerTurnSignalState(l.turnL)
        }.onFailure { Log.w(TAG, "subscribe vehicle", it) }
        val m = runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.onFailure { Log.w(TAG, "property manager", it) }.getOrNull()
        var gearNow: Int? = null
        var speed = false
        if (m != null) {
            runCatching {
                // C326: 권한이 없으면 예외 없이 false를 돌려준다
                if (!m.registerCallback(l.gearL, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)) Log.w(TAG, "subscribe gear(CAR_POWERTRAIN 권한?)")
                gearNow = m.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)
            }.onFailure { Log.w(TAG, "subscribe gear(CAR_POWERTRAIN 권한?)", it) }
            runCatching {
                speed = m.registerCallback(l.speedL, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
                if (!speed) Log.w(TAG, "subscribe speed(CAR_SPEED 권한?)")
            }.onFailure { Log.w(TAG, "subscribe speed(CAR_SPEED 권한?)", it) }
        }
        val stale = synchronized(ex.lock) {
            if (gen != my || subGen != sub) true
            else { if (m != null) props = m; speedOn = speed; if ("gear" !in l.live) gearNow?.let { emitGear(it) }; false }   // C367: 그사이 기어 콜백이 먼저 왔으면 늦은 초기 조회(P)가 D를 덮지 않게
        }
        if (stale) unregister(v, l, m)                                    // 그사이 stop·끊김·다시 구독
    }

    /** 그 회차의 묶음을 해제한다(바인더라 잠금 밖에서만 부른다). */
    private fun unregister(v: Vehicle?, l: Listeners?, pm: CarPropertyManager?) {
        if (l == null) return
        if (v != null) {
            l.doorL.forEach { (side, d) -> runCatching { v.getDoor().unregisterDoorPosition(DoorArea(if (side == Side.LEFT) DoorValue.ROW_2_LEFT else DoorValue.ROW_2_RIGHT), d) } }
            l.seatL.forEach { (a, x) -> runCatching { v.getSeat().unregisterHvacSeatTemperature(seat(a), x) } }
            runCatching { v.getHVAC().unregisterHvacTemperatureCurrent(seat(Area.ROW_1_LEFT), l.cabinL) }
            runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(l.steerL) }
            runCatching { v.getBrake().unregisterBrakePedalStatus(l.brakeL) }
            runCatching { v.getTurnSignal().unregisterTurnSignalState(l.turnL) }
        }
        runCatching { pm?.unregisterCallback(l.gearL) }
        runCatching { pm?.unregisterCallback(l.speedL) }
    }

    /** 잠금 안에서 칸만 비우고, 해제·연결 끊기(바인더)는 레인에서 한다. 레인은 차례대로라 바로 이어지는 start의 연결보다 먼저 끝난다. */
    fun stop() {
        gen++
        cancelSub()
        subGen++                                          // 옛 구독의 늦은 콜백을 먼저 막는다
        val l = ls; val pm = props; val v = vehicle; val c = car; val ml = modeL
        ls = null; props = null; speedOn = false; modeL = null; car = null; vehicle = null; readyCar = null; probe = null
        ex.off.run {
            unregister(v, l, pm)
            ml?.let { m -> runCatching { am.removeOnModeChangedListener(m) } }
            runCatching { c?.disconnect() }
            runCatching { v?.release() }
        }
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
                ControlKind.AMBIENT_BRIGHTNESS, ControlKind.AMBIENT_COLOR -> cb(null)   // 앰비언트는 AmbientPort(영역이 capability)
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
                ControlKind.AMBIENT_BRIGHTNESS, ControlKind.AMBIENT_COLOR -> ack(false)   // 앰비언트는 AmbientPort
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

    // ---- 앰비언트 조명(기능 D, 4.2). Vehicle 2.3.2 Light, 권한 CAR_LIGHTS. 영역은 capability가 돌려준 SeatArea 하나 ----

    private fun lightFail(cb: () -> Unit): (Exception) -> Unit = { e -> ex.guard { Log.w(TAG, "ambient ${e.message}"); cb() } }

    /** 값이 모두 ROW_2_*인 첫 영역, 없으면 첫 영역(한 영역만 쓴다). 못 읽으면 null. */
    override fun area(cb: (AmbientArea?) -> Unit) {
        val v = vehicle ?: return cb(null)
        try {
            v.getLight().checkAmbientLightBrightnessCapabilities({ _, m ->
                ex.guard {
                    val list = m.entries.toList()
                    val pick = list.firstOrNull { e -> e.key.values.isNotEmpty() && e.key.values.all { it.name.startsWith("ROW_2") } } ?: list.firstOrNull()
                    cb(pick?.let { AmbientArea(it.key.values.joinToString("+") { x -> x.name }, it.value.minValue, it.value.maxValue, it.key) })
                }
            }, lightFail { cb(null) })
        } catch (e: Exception) { cb(null) }
    }

    /** 스위치 ON, 또는 AUTOMATIC이고 State가 ON일 때만 true(C359 결정 ②). 그 밖·읽기 실패는 false. 스위치는 읽기만 한다. */
    override fun lit(a: AmbientArea, cb: (Boolean?) -> Unit) {
        val v = vehicle ?: return cb(null)
        val h = a.handle as SeatArea
        try {
            v.getLight().getAmbientLightSwitch(h, { sw ->
                ex.guard {
                    when (sw) {
                        LightSwitch.ON -> cb(true)
                        LightSwitch.AUTOMATIC -> try {
                            v.getLight().getAmbientLightState(h, { st -> ex.guard { cb(st == LightState.ON) } }, lightFail { cb(null) })
                        } catch (e: Exception) { cb(null) }
                        null -> cb(null)
                        else -> cb(false)
                    }
                }
            }, lightFail { cb(null) })
        } catch (e: Exception) { cb(null) }
    }

    /** capability가 ERROR_NOT_SUPPORTED_PROPERTY로 실패하면 NONE(11-08 실측: 에뮬레이터). 있으면 값이 false일 때만 OFF, 나머지(true·null·그 밖 실패)는 UNKNOWN. */
    override fun autoColor(cb: (AutoColor) -> Unit) {
        val v = vehicle ?: return cb(AutoColor.UNKNOWN)
        val l = v.getLight()
        try {
            l.checkAmbientColorAutoByDriveModeEnabledCapabilities({ _ ->
                try {
                    l.getAmbientColorAutoByDriveModeEnabled({ on -> ex.guard { cb(if (on == false) AutoColor.OFF else AutoColor.UNKNOWN) } }, lightFail { cb(AutoColor.UNKNOWN) })
                } catch (e: Exception) { ex.guard { cb(AutoColor.UNKNOWN) } }
            }, { e -> ex.guard { cb(if (notSupported(e)) AutoColor.NONE else AutoColor.UNKNOWN) } })
        } catch (e: Exception) { cb(AutoColor.UNKNOWN) }
    }

    override fun readBrightness(a: AmbientArea, cb: (Int?) -> Unit) {
        val v = vehicle ?: return cb(null)
        try { v.getLight().getAmbientLightBrightness(a.handle as SeatArea, { x -> ex.guard { cb(x) } }, lightFail { cb(null) }) } catch (e: Exception) { cb(null) }
    }

    /** 24비트 RGB(알파 뺌). */
    override fun readColor(a: AmbientArea, cb: (Int?) -> Unit) {
        val v = vehicle ?: return cb(null)
        try { v.getLight().getAmbientLightBrightnessColor(a.handle as SeatArea, { x -> ex.guard { cb(x?.and(0xFFFFFF)) } }, lightFail { cb(null) }) } catch (e: Exception) { cb(null) }
    }

    override fun writeBrightness(a: AmbientArea, v: Int, ack: (Boolean) -> Unit) {
        val veh = vehicle ?: return ack(false)
        try { veh.getLight().setAmbientLightBrightness(a.handle as SeatArea, v, { _, _ -> ex.guard { ack(true) } }, lightFail { ack(false) }) } catch (e: Exception) { ack(false) }
    }

    /** [argb]는 32비트 ARGB(Ambients가 알파 0xFF를 붙여 넘긴다). */
    override fun writeColor(a: AmbientArea, argb: Int, ack: (Boolean) -> Unit) {
        val veh = vehicle ?: return ack(false)
        try { veh.getLight().setAmbientLightBrightnessColor(a.handle as SeatArea, argb, { _, _ -> ex.guard { ack(true) } }, lightFail { ack(false) }) } catch (e: Exception) { ack(false) }
    }

    private companion object { const val SUBSCRIBE_DELAY_MS = 1_500L; const val READY_PROBE_MS = 1_000L }
}
