package dev.probe.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView

// 찜통차·꽁꽁 언 차·비상 정차 장면에 쓸 제어 API가 에뮬레이터에서 실제로 바뀌는지 기록하는 일회용 실측 화면
// 실행: am start -n dev.probe.pleos/.ScenarioProbe --es mode hot|cold|emergency|babycare|drive|speed|mic|rear|belt|blind|seat|recline|fog|amb (기본 hot)
class ScenarioProbe : Activity() {
    private lateinit var out: TextView
    private lateinit var v: Vehicle
    private val ui = Handler(Looper.getMainLooper())

    private var hvacSeats: List<SeatArea> = emptyList()
    private var tempBounds: Map<SeatArea, AreaBounds<Float>> = emptyMap()
    private var fanBounds: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    private var fanDirs: Map<SeatArea, List<FanDirectionType>> = emptyMap()
    private var defrostWindows: List<WindowArea> = emptyList()
    private var windowMax: Map<WindowArea, Int> = emptyMap()
    private var seatHeat: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    private var seatVent: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    private var wheelHeatMax = 0
    private var mirrors: Map<MirrorArea, AreaBounds<Int>> = emptyMap()
    private var doorPos: Map<DoorArea, AreaBounds<Int>> = emptyMap()
    private var doorLocks: List<DoorArea> = emptyList()
    private var wheels: List<WheelArea> = emptyList()
    private var airbagSeats: List<SeatArea> = emptyList()

    private fun log(msg: String) {
        Log.i("SCENE", msg)
        ui.post { out.append(msg + "\n") }
    }

    private fun err(tag: String): (Exception) -> Unit = { e -> log("ERR $tag ${e.javaClass.simpleName}: ${e.message}") }

    private fun n(a: Any?): String = when (a) {
        is SeatArea -> a.values.joinToString("+")
        is WindowArea -> a.values.joinToString("+")
        is DoorArea -> a.values.joinToString("+")
        is MirrorArea -> a.values.joinToString("+")
        is WheelArea -> a.values.joinToString("+")
        else -> "$a"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 12f }
        setContentView(ScrollView(this).apply { addView(out) })
        val mode = intent.getStringExtra("mode") ?: "hot"
        log("mode=$mode")
        // 마이크 실측은 차량 SDK 없이 서비스만 켜고 화면을 닫아, 앱이 뒤에 있는 상태를 만든다
        if (mode == "mic") {
            val tag = intent.getStringExtra("tag") ?: "fg-activity"
            Log.i("MICTEST", "$tag activity onCreate importance=${importance()}")
            startForegroundService(android.content.Intent(this, MicService::class.java).putExtra("tag", tag)); finish(); return
        }
        v = Vehicle(applicationContext)
        try { v.initialize() } catch (e: Exception) { log("INIT ERR $e"); return }
        ui.postDelayed({ discover() }, 1500)
        ui.postDelayed({ run(mode) }, 4000)
    }

    private fun discover() {
        val h = v.getHVAC()
        h.checkHvacPowerCapabilities({ c, l -> hvacSeats = l; log("CAP power $c ${l.map(::n)}") }, err("capPower"))
        h.checkHvacTemperatureCapabilities({ _, m -> tempBounds = m; log("CAP temp ${m.map { "${n(it.key)}=${it.value.minValue}..${it.value.maxValue}" }}") }, err("capTemp"))
        h.checkHvacFanSpeedCapabilities({ _, m -> fanBounds = m; log("CAP fan ${m.map { "${n(it.key)}=${it.value.minValue}..${it.value.maxValue}" }}") }, err("capFan"))
        h.checkHvacFanDirectionCapabilities({ _, m -> fanDirs = m.mapValues { it.value.toList() }; log("CAP fanDir ${m.map { "${n(it.key)}=${it.value}" }}") }, err("capFanDir"))
        h.checkHvacDefrosterCapabilities({ _, l -> defrostWindows = l; log("CAP defroster ${l.map(::n)}") }, err("capDefrost"))
        v.getWindow().checkWindowPositionCapabilities({ _, m -> windowMax = m.mapValues { it.value.maxValue ?: 10 } }, err("capWin"))
        v.getSeat().checkSeatTemperatureCapabilities({ c, m -> seatHeat = m; log("CAP seatHeat $c ${m.map { "${n(it.key)}=${it.value.minValue}..${it.value.maxValue}" }}") }, err("capSeatHeat"))
        v.getSeat().checkSeatVentilationCapabilities({ c, m -> seatVent = m; log("CAP seatVent $c ${m.map { "${n(it.key)}=${it.value.minValue}..${it.value.maxValue}" }}") }, err("capSeatVent"))
        v.getSteeringWheel().checkHvacSteeringWheelHeatCapabilities({ c, b -> wheelHeatMax = b.maxValue ?: 0; log("CAP wheelHeat $c ${b.minValue}..${b.maxValue}") }, err("capWheelHeat"))
        v.getSideMirror().checkHvacSideMirrorHeatCapabilities({ c, m -> mirrors = m; log("CAP mirrorHeat $c ${m.map { "${n(it.key)}=${it.value.minValue}..${it.value.maxValue}" }}") }, err("capMirror"))
        v.getDoor().checkDoorPositionCapabilities({ c, m -> doorPos = m; log("CAP doorPos $c ${m.map { "${n(it.key)}=${it.value.minValue}..${it.value.maxValue}" }}") }, err("capDoorPos"))
        v.getDoor().checkDoorLockCapabilities({ _, l -> doorLocks = l }, err("capDoorLock"))
        v.getTire().checkTirePressureCapabilities({ c, l -> wheels = l; log("CAP tire $c ${l.map(::n)}") }, err("capTire"))
        v.getSafety().checkAirbagDeployedCapabilities({ c, l -> airbagSeats = l; log("CAP airbagDeployed $c ${l.map(::n)}") }, err("capAirbag"))
    }

    private fun run(mode: String) {
        when (mode) {
            "hot" -> hot()
            "cold" -> cold()
            "emergency" -> emergency()
            "seat" -> { seatMove(); return }
            "fog" -> { fogLights(); return }
            "amb" -> { ambientZones(); return }
            "mic" -> { startForegroundService(android.content.Intent(this, MicService::class.java)); return }
            "belt" -> { beltLocks(); return }
            "speed" -> { speed(); return }
            "recline" -> { recline(); return }
            "rear" -> { rearZone(); return }
            "blind" -> { blind(); return }
            "drive" -> { drive(); return }
            "babycare" -> { babycare(); return }
        }
        ui.postDelayed({ readBack() }, 2500)
    }

    // '아이곁' 효과 계측: 조향각·브레이크·방향지시등·속도를 앱이 계속 받을 수 있는지. Virtual Console(/vehicle/controls)로 값을 바꾸며 이벤트를 기록한다
    private fun drive() {
        val sw = v.getSteeringWheel()
        sw.checkSteeringWheelAngleCapabilities({ c, b -> log("CAP steer $c ${b.minValue}..${b.maxValue}") }, err("capSteer"))
        sw.getCurrentSteeringWheelAngle({ log("GET steer $it") }, err("gSteer"))
        sw.registerSteeringWheelAngle(object : ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) { log("EVT steer ${android.os.SystemClock.elapsedRealtime()} $angle") }
            override fun onFailed(e: Exception) { log("ERR steerListener ${e.message}") } })
        v.getBrake().registerBrakePedalStatus(object : ai.pleos.playground.vehicle.listener.BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) { log("EVT brake ${android.os.SystemClock.elapsedRealtime()} $pressed") }
            override fun onFailed(e: Exception) { log("ERR brakeListener ${e.message}") } })
        speed()
    }

    // 명령 → 다시 읽기로 반영을 확인하고 걸린 시간을 잰다(ack = 성공 콜백까지, seen = 다시 읽은 값이 목표와 같아질 때까지, 5초 넘으면 FAIL)
    // ok는 성공 콜백을 받았고(ack ≥ 0) 다시 읽은 값도 목표일 때만이다. 거절된 명령은 값이 우연히 목표와 같아도 FAIL(noAck)로 남긴다(11-02 수정)
    // 0번째 목표가 현재 값과 같으면 목표 순서를 한 칸 밀어, 첫 명령이 항상 상태를 바꾸게 한다(n번 모두 번갈아 바뀜, 11-02 수정)
    private fun measure(name: String, n: Int, set: (Int, () -> Unit) -> Unit, read: ((Any?) -> Unit) -> Unit, want0: (Int) -> Any?, next: () -> Unit) {
        read { cur ->
            val sh = if (cur != null && cur == want0(0)) 1 else 0
            val want = { i: Int -> want0(i + sh) }
            val setS: (Int, () -> Unit) -> Unit = { i, a -> set(i + sh, a) }
            measure2(name, n, setS, read, want, next)
        }
    }

    private fun measure2(name: String, n: Int, set: (Int, () -> Unit) -> Unit, read: ((Any?) -> Unit) -> Unit, want: (Int) -> Any?, next: () -> Unit) {
        fun run(i: Int) {
            if (i >= n) { next(); return }
            val t = android.os.SystemClock.elapsedRealtime(); var ack = -1L
            set(i) { ack = android.os.SystemClock.elapsedRealtime() - t }
            fun poll() {
                read { v ->
                    val dt = android.os.SystemClock.elapsedRealtime() - t
                    when {
                        v == want(i) && ack >= 0 -> { log("RES $name $i ack=$ack seen=$dt ok"); ui.postDelayed({ run(i + 1) }, 400) }
                        dt > 5000 -> { log("RES $name $i ack=$ack seen=-1 FAIL last=$v${if (ack < 0) " noAck" else ""}"); ui.postDelayed({ run(i + 1) }, 400) }
                        else -> ui.postDelayed({ poll() }, 150)
                    }
                }
            }
            ui.postDelayed({ poll() }, 100)
        }
        run(0)
    }

    // '아이곁' 뒷자리 아기 케어 후보 API: 뒷좌석 열선·독서등·뒷창 잠금·하이패스 음량·앞 공조 온도·앰비언트 색은 반영률·시간을 재고, 주행 시간·뒷문·뒷좌석 착석은 수신을 기록한다
    private fun babycare() {
        // 뒷좌석 전용 영역만 고른다: 묶음 영역(예: ROW_1_LEFT+…+ROW_2_RIGHT)은 구성원이 모두 ROW_2일 때만(11-02 수정, 앞창까지 바뀌던 문제)
        val rear = { a: Any -> n(a).split("+").all { it.startsWith("ROW_2") } }
        val l = v.getLight(); val w = v.getWindow(); val h = v.getHVAC(); val s = v.getSeat(); val toll = v.getToll()
        // 장면 조건(D·60km/h, P·0)이 실제로 걸렸는지 로그로 남긴다
        v.getGear().getCurrentGearSelectionState({ g ->
            val spd = try {
                val pm = android.car.Car.createCar(this).getCarManager(android.car.Car.PROPERTY_SERVICE) as android.car.hardware.property.CarPropertyManager
                pm.getProperty<Float>(android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)?.value
            } catch (e: Exception) { "ERR $e" }
            log("STATE gear=$g speed=$spd") }, err("gear"))
        val tests = mutableListOf<(() -> Unit) -> Unit>()
        seatHeat.keys.firstOrNull(rear)?.let { a -> tests += { nx -> measure("seatHeat_${n(a)}", 6, { i, ack -> s.setHvacSeatTemperature(a, if (i % 2 == 0) 2 else 0, { _, _ -> ack() }, err("seatHeat")) },
            { cb -> s.getHvacSeatTemperature(a, { cb(it) }, { cb(null) }) }, { if (it % 2 == 0) 2 else 0 }, nx) } }
        tests += { nx -> l.checkReadingLightSwitchCapabilities({ m -> val a = m.keys.firstOrNull(rear); log("CAP readingLight ${m.keys.map(::n)}")
            if (a == null) nx() else measure("readingLight_${n(a)}", 6, { i, ack -> l.setReadingLightSwitch(a, if (i % 2 == 0) LightSwitch.OFF else LightSwitch.ON, { ack() }, err("readingLight")) },
                { cb -> l.getReadingLightSwitch(a, { cb(it) }, { cb(null) }) }, { if (it % 2 == 0) LightSwitch.OFF else LightSwitch.ON }, nx) }, { e -> err("capReading")(e); nx() }) }
        tests += { nx -> w.checkWindowLockCapabilities({ list -> val a = list.firstOrNull(rear); log("CAP windowLock ${list.map(::n)}")
            if (a == null) nx() else measure("windowLock_${n(a)}", 6, { i, ack -> w.setWindowLock(a, i % 2 == 0, { _, _ -> ack() }, err("windowLock")) },
                { cb -> w.getCurrentWindowLockState(a, { cb(it) }, { cb(null) }) }, { it % 2 == 0 }, nx) }, { e -> err("capWinLock")(e); nx() }) }
        tests += { nx -> toll.checkEtcsVoiceGuidanceVolumeCapabilities({ c, b -> log("CAP etcsVolume $c ${b.minValue}..${b.maxValue}")
            measure("etcsVolume", 6, { i, ack -> toll.setEtcsVoiceGuidanceVolume(if (i % 2 == 0) (b.minValue ?: 0) else (b.maxValue ?: 3), { ack() }, err("etcsVol")) },
                { cb -> toll.getCurrentEtcsVoiceGuidanceVolume({ cb(it) }, { cb(null) }) }, { if (it % 2 == 0) (b.minValue ?: 0) else (b.maxValue ?: 3) }, nx) }, { e -> err("capEtcs")(e); nx() }) }
        tempBounds.keys.firstOrNull()?.let { a -> tests += { nx -> measure("hvacTemp_${n(a)}", 6, { i, ack -> h.setHvacTemperature(a, if (i % 2 == 0) 21f else 23f, TemperatureUnit.CELSIUS, { _, _ -> ack() }, err("temp")) },
            { cb -> h.getHvacTemperature(a, TemperatureUnit.CELSIUS, { cb(it) }, { cb(null) }) }, { if (it % 2 == 0) 21f else 23f }, nx) } }
        tests += { nx -> l.checkAmbientLightBrightnessColorCapabilities({ _, list -> val a = list.firstOrNull(); val colors = listOf(0xFFB060, 0x4060FF)
            if (a == null) nx() else measure("ambientColor_${n(a)}", 6, { i, ack -> l.setAmbientLightBrightnessColor(a, colors[i % 2], { _, _ -> ack() }, err("ambColor")) },
                { cb -> l.getAmbientLightBrightnessColor(a, { cb(it) }, { cb(null) }) }, { colors[it % 2] }, nx) }, { e -> err("capAmb")(e); nx() }) }
        // 수신만 기록: 주행 시간(CAR_DRIVE), 뒷문(Virtual Console에서 연다), 뒷좌석 착석
        v.getOdometer().getTripCurrentDrivingTime({ log("GET tripTime $it") }, err("tripTime"))
        v.getOdometer().registerTripCurrentDrivingTime(object : ai.pleos.playground.vehicle.listener.DrivingTimeListener {
            override fun onDrivingTimeUpdated(t: Int?) { log("EVT tripTime ${System.currentTimeMillis()} $t") }
            override fun onFailed(e: Exception) { log("ERR tripTimeListener ${e.message}") } })
        doorPos.keys.filter(rear).forEach { a -> v.getDoor().registerDoorPosition(a, object : ai.pleos.playground.vehicle.listener.DoorPositionListener {
            override fun onPositionUpdated(p: Int?) { log("EVT door ${n(a)} ${System.currentTimeMillis()} $p") }
            override fun onFailed(e: Exception) { log("ERR doorListener ${e.message}") } }) }
        // 착석은 HVAC 전원 영역(앞뒤 한 덩어리)이 아니라 Seat API의 착석 capability 영역으로 조회한다(11-02 수정)
        s.checkSeatOccupancyCapabilities({ c, list -> log("CAP occupancy $c ${list.map(::n)}")
            list.filter(rear).forEach { a -> s.getCurrentSeatOccupancy(a, { log("GET occupancy ${n(a)} $it") }, err("occupancy")) } }, err("capOcc"))
        fun chain(i: Int) { if (i < tests.size) tests[i] { chain(i + 1) } else log("RES done") }
        chain(0)
    }

    // 카시트 햇빛 가리기: 뒷창 가림막이 실제로 움직이는지. 방향 표기가 모호해 뒤로 8초 → 멈춤 → 앞으로 8초 순서로 둘 다 시험하고 위치를 계속 듣는다
    private fun blind() {
        val w = v.getWindow()
        w.checkMoveWindowBlindCapabilities({ c, l ->
            log("CAP moveBlind $c ${l.map(::n)}")
            l.forEach { a ->
                w.registerWindowBlindPosition(a, object : ai.pleos.playground.vehicle.listener.WindowPositionListener {
                    override fun onWindowPositionUpdated(p: Int?) { log("EVT blind ${n(a)} $p") }
                    override fun onFailed(e: Exception) { log("ERR blindListener ${e.message}") }
                })
            }
            val read = { tag: String -> l.forEach { a -> w.getCurrentWindowBlindPosition(a, { log("POS $tag ${n(a)} $it") }, err("gBlind")) } }
            val move = { m: MoveControl -> l.forEach { a -> w.moveWindowBlind(a, m, { ar, x -> log("MOVE blind ${n(ar)} $x") }, err("moveBlind")) } }
            read("start"); move(MoveControl.BACKWARD)
            ui.postDelayed({ read("back4s") }, 4000)
            ui.postDelayed({ move(MoveControl.STOP); read("back8s") }, 8000)
            ui.postDelayed({ move(MoveControl.FORWARD) }, 9000)
            ui.postDelayed({ read("fwd4s") }, 13000)
            ui.postDelayed({ move(MoveControl.STOP); read("fwd8s") }, 17000)
        }, err("capMoveBlind"))
    }

    // 아이 탑승 안전에 쓸 신호: 좌석별 탑승·안전띠를 계속 듣고, 뒷창문 잠금과 문 잠금을 걸어 본 뒤 다시 읽는다
    private fun beltLocks() {
        val s = v.getSeat()
        s.checkSeatOccupancyCapabilities({ c, l -> log("CAP occupancy $c ${l.map(::n)}"); l.forEach { a ->
            s.registerSeatOccupancy(a, object : ai.pleos.playground.vehicle.listener.SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(o: Boolean?) { log("EVT occupied ${n(a)} $o") }
                override fun onFailed(e: Exception) { log("ERR occListener ${e.message}") }
            })
        } }, err("capOcc"))
        s.checkSeatBeltBuckledCapabilities({ c, l -> log("CAP belt $c ${l.map(::n)}"); l.forEach { a ->
            s.registerSeatBeltBuckledState(a, object : ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(b: Boolean?) { log("EVT belt ${n(a)} $b") }
                override fun onFailed(e: Exception) { log("ERR beltListener ${e.message}") }
            })
        } }, err("capBelt"))
        val w = v.getWindow()
        // 뒷창만: 앞+뒤 묶음 영역은 제외(babycare와 같은 rear 조건, 구성원이 모두 ROW_2)
        val rear = { a: Any -> n(a).split("+").all { it.startsWith("ROW_2") } }
        w.checkWindowLockCapabilities({ c, l -> log("CAP windowLock $c ${l.map(::n)}"); l.filter(rear).forEach { a ->
            w.setWindowLock(a, true, { r, ok -> log("SET windowLock ${n(r)} $ok") }, err("windowLock"))
            ui.postDelayed({ w.getCurrentWindowLockState(a, { st -> log("GET windowLock ${n(a)} $st") }, err("getWindowLock")) }, 2000)
        } }, err("capWindowLock"))
        doorLocks.forEach { a ->
            v.getDoor().setDoorLock(a, true, { r, ok -> log("SET doorLock ${n(r)} $ok") }, err("doorLock"))
            ui.postDelayed({ v.getDoor().getDoorLockState(a, { st -> log("GET doorLock ${n(a)} $st") }, err("getDoorLock")) }, 2000)
        }
    }

    // 뒷자리만 따로 돌볼 수 있는지: 독서등·열선·창문 상한을 뒷좌석에만 걸고, 공조 구역 목록을 본다
    private fun rearZone() {
        val l = v.getLight()
        l.checkReadingLightSwitchCapabilities({ c, m -> log("CAP readingLight $c ${m.keys.map(::n)}"); m.keys.filter { a -> a.values.any { it.name.startsWith("ROW_2") } }.forEach { a ->
            l.setReadingLightSwitch(a, LightSwitch.ON, { s -> log("SET readingLight ${n(a)} $s") }, err("readingLight"))
        } }, err("capReadingLight"))
        seatHeat.keys.filter { a -> a.values.any { it.name.startsWith("ROW_2") } }.forEach { a ->
            v.getSeat().setHvacSeatTemperature(a, 2, { r, ok -> log("SET seatHeat ${n(r)} $ok") }, err("seatHeat"))
        }
        windowMax.keys.filter { a -> a.values.all { it.name.startsWith("ROW_2") } }.forEach { a ->
            v.getWindow().setWindowPosition(a, 3, { r, ok -> log("SET window ${n(r)} $ok") }, err("window"))
            v.getWindow().setWindowLock(a, true, { r, ok -> log("SET windowLock ${n(r)} $ok") }, err("windowLock"))
        }
    }

    // 젖힌 동승석을 앱이 알아챌 수 있는지: 좌석별 등받이 위치를 계속 듣는다 (가상 콘솔로 각도를 바꿔 본다)
    private fun recline() {
        val s = v.getSeat()
        s.checkSeatBackrestAnglePositionCapabilities({ c, m -> log("CAP backrest $c ${m.map { "${n(it.key)}=${it.value.minValue}..${it.value.maxValue}" }}"); m.keys.forEach { a ->
            s.registerSeatBackrestAnglePosition(a, object : ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener {
                override fun onSeatBackrestAnglePositionUpdated(p: Int?) { log("EVT backrest ${n(a)} $p") }
                override fun onFailed(e: Exception) { log("ERR backrestListener ${e.message}") }
            })
        } }, err("capBackrest"))
    }

    // Pleos SDK에서 빠진 차량 속도를 Android 표준 Car API로 읽을 수 있는지 (권한 CAR_SPEED)
    private fun speed() {
        val car = android.car.Car.createCar(this)
        val pm = car.getCarManager(android.car.Car.PROPERTY_SERVICE) as android.car.hardware.property.CarPropertyManager
        val id = android.car.VehiclePropertyIds.PERF_VEHICLE_SPEED
        try { log("GET speed ${pm.getProperty<Float>(id, 0)?.value}") } catch (e: Exception) { log("ERR getSpeed $e") }
        pm.registerCallback(object : android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(e: android.car.hardware.CarPropertyValue<*>) { log("EVT speed ${e.value}") }
            override fun onErrorEvent(p: Int, z: Int) { log("ERR speedEvt $p $z") }
        }, id, android.car.hardware.property.CarPropertyManager.SENSOR_RATE_NORMAL)
    }

    // 앰비언트를 구역별로 따로 칠할 수 있는지: 구역 목록을 보고 구역마다 다른 색을 넣은 뒤 다시 읽는다
    private fun ambientZones() {
        val l = v.getLight()
        val colors = listOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF)
        l.checkAmbientLightBrightnessColorCapabilities({ c, areas ->
            log("CAP ambientColor $c ${areas.map(::n)}")
            areas.forEachIndexed { i, a ->
                l.setAmbientLightSwitch(a, LightSwitch.ON, { _, _ -> }, err("ambOn"))
                l.setAmbientLightBrightnessColor(a, colors[i % colors.size], { ar, x -> log("SET ambientColor ${n(ar)} ${Integer.toHexString(x ?: 0)}") }, err("ambColor"))
            }
            ui.postDelayed({ areas.forEach { a -> l.getAmbientLightBrightnessColor(a, { log("GET ambientColor ${n(a)} ${Integer.toHexString(it ?: 0)}") }, err("gAmbColor")) } }, 2000)
        }, err("capAmbColor"))
    }

    // 안개 구간: 앞·뒤 안개등과 하향등을 켜고 상향등을 끈 뒤 상태를 다시 읽는다
    private fun fogLights() {
        val l = v.getLight()
        fun read(tag: String) {
            l.getCurrentFrontFogLightState({ log("GET $tag frontFog $it") }, err("gFrontFog"))
            l.getCurrentRearFogLightState({ log("GET $tag rearFog $it") }, err("gRearFog"))
            l.getCurrentLowBeamLightState({ log("GET $tag lowBeam $it") }, err("gLowBeam"))
            l.getCurrentHighBeamLightState({ log("GET $tag highBeam $it") }, err("gHighBeam"))
        }
        read("before")
        l.setFrontFogLightSwitch(LightSwitch.ON, { log("SET frontFog $it") }, err("frontFog"))
        l.setRearFogLightSwitch(LightSwitch.ON, { log("SET rearFog $it") }, err("rearFog"))
        l.setLowBeamLightSwitch(LightSwitch.ON, { log("SET lowBeam $it") }, err("lowBeam"))
        l.setHighBeamLightSwitch(LightSwitch.OFF, { log("SET highBeam $it") }, err("highBeam"))
        ui.postDelayed({ read("after") }, 2500)
    }

    // 시트 앞뒤·등받이와 창문 가림막이 스스로 움직이는지: 3초 움직였다 멈추고, 전·중·후 위치를 읽는다
    private fun seatMove() {
        val s = v.getSeat()
        val w = v.getWindow()
        fun readSeats(tag: String, areas: List<SeatArea>) = areas.forEach { a ->
            s.getCurrentSeatForeAftPosition(a, { log("POS $tag foreAft ${n(a)} $it") }, err("gForeAft"))
            s.getCurrentSeatBackrestAnglePosition(a, { log("POS $tag backrest ${n(a)} $it") }, err("gBackrest"))
        }
        s.checkSeatForeAftPositionCapabilities({ c, m -> log("CAP foreAftPos $c ${m.map { "${n(it.key)}=${it.value.minValue}..${it.value.maxValue}" }}") }, err("capForeAftPos"))
        s.checkMoveSeatForeAftCapabilities({ c, l ->
            log("CAP moveForeAft $c ${l.map(::n)}")
            readSeats("before", l)
            l.forEach { a -> s.moveSeatForeAft(a, MoveControl.FORWARD, { ar, x -> log("MOVE foreAft ${n(ar)} $x") }, err("moveForeAft")) }
            ui.postDelayed({ readSeats("during", l) }, 1500)
            ui.postDelayed({ l.forEach { a -> s.moveSeatForeAft(a, MoveControl.STOP, { ar, x -> log("MOVE foreAft ${n(ar)} $x") }, err("stopForeAft")) } }, 3000)
            ui.postDelayed({ readSeats("after", l) }, 4500)
        }, err("capMoveForeAft"))
        s.checkMoveSeatBackrestAngleCapabilities({ c, l ->
            log("CAP moveBackrest $c ${l.map(::n)}")
            // 방향 표기가 모호해 앞으로 3초, 멈춤, 뒤로 3초 순서로 둘 다 시험한다
            l.forEach { a -> s.moveSeatBackrestAngle(a, MoveControl.FORWARD, { ar, x -> log("MOVE backrest ${n(ar)} $x") }, err("moveBackrest")) }
            ui.postDelayed({ l.forEach { a -> s.moveSeatBackrestAngle(a, MoveControl.STOP, { ar, x -> log("MOVE backrest ${n(ar)} $x") }, err("stopBackrest")) } }, 3000)
            ui.postDelayed({ l.forEach { a -> s.moveSeatBackrestAngle(a, MoveControl.BACKWARD, { ar, x -> log("MOVE backrest ${n(ar)} $x") }, err("moveBackrest2")) } }, 6000)
            ui.postDelayed({ l.forEach { a -> s.moveSeatBackrestAngle(a, MoveControl.STOP, { ar, x -> log("MOVE backrest ${n(ar)} $x") }, err("stopBackrest2")) } }, 9000)
        }, err("capMoveBackrest"))
        w.checkWindowBlindPositionCapabilities({ c, m -> log("CAP blindPos $c ${m.map { "${n(it.key)}=${it.value.minValue}..${it.value.maxValue}" }}") }, err("capBlindPos"))
        w.checkMoveWindowBlindCapabilities({ c, l ->
            log("CAP moveBlind $c ${l.map(::n)}")
            l.forEach { a ->
                w.getCurrentWindowBlindPosition(a, { log("POS before blind ${n(a)} $it") }, err("gBlind"))
                w.moveWindowBlind(a, MoveControl.FORWARD, { ar, x -> log("MOVE blind ${n(ar)} $x") }, err("moveBlind"))
            }
            ui.postDelayed({ l.forEach { a -> w.moveWindowBlind(a, MoveControl.STOP, { _, _ -> }, err("stopBlind")); w.getCurrentWindowBlindPosition(a, { log("POS after blind ${n(a)} $it") }, err("gBlind")) } }, 3000)
        }, err("capMoveBlind"))
        w.checkSmartGlassLevelCapabilities({ c, m -> log("CAP smartGlass $c ${m.map { "${n(it.key)}=${it.value.minValue}..${it.value.maxValue}" }}") }, err("capSmartGlass"))
    }

    // 찜통차: 창문 전부 열기 + 외기 + 에어컨 최대 냉방 + 최저 온도 + 최대 풍량 + 얼굴 쪽 바람 + 탑승 좌석 통풍
    private fun hot() {
        val h = v.getHVAC()
        hvacSeats.forEach { a ->
            h.setHvacPower(a, true, { ar, x -> log("SET power ${n(ar)} $x") }, err("power"))
            h.setHvacAc(a, true, { ar, x -> log("SET ac ${n(ar)} $x") }, err("ac"))
            h.setHvacMaxAc(a, true, { ar, x -> log("SET maxAc ${n(ar)} $x") }, err("maxAc"))
            h.setHvacRecirculation(a, false, { ar, x -> log("SET recirc ${n(ar)} $x") }, err("recirc"))
        }
        tempBounds.forEach { (a, b) -> h.setHvacTemperature(a, b.minValue ?: 16f, TemperatureUnit.CELSIUS, { ar, x -> log("SET temp ${n(ar)} $x") }, err("temp")) }
        fanBounds.forEach { (a, b) -> h.setHvacFanSpeed(a, b.maxValue ?: 8, { ar, x -> log("SET fan ${n(ar)} $x") }, err("fan")) }
        fanDirs.forEach { (a, l) -> if (FanDirectionType.FACE in l) h.setHvacFanDirection(a, FanDirectionType.FACE, { ar, x -> log("SET fanDir ${n(ar)} $x") }, err("fanDir")) }
        windowMax.forEach { (a, m) -> v.getWindow().setWindowPosition(a, m, { ar, x -> log("SET window ${n(ar)} $x") }, err("window")) }
        seatVent.forEach { (a, b) -> v.getSeat().setHvacSeatVentilation(a, b.maxValue ?: 3, { ar, x -> log("SET seatVent ${n(ar)} $x") }, err("seatVent")) }
        // 2분 대신 6초 뒤 창문 닫고 내기로
        ui.postDelayed({
            windowMax.keys.forEach { a -> v.getWindow().setWindowPosition(a, 0, { ar, x -> log("SET window close ${n(ar)} $x") }, err("windowClose")) }
            hvacSeats.forEach { a -> h.setHvacRecirculation(a, true, { ar, x -> log("SET recirc on ${n(ar)} $x") }, err("recircOn")) }
            ui.postDelayed({ readBack() }, 2500)
        }, 6000)
    }

    // 꽁꽁 언 차: 앞·뒤 유리 성에 제거 + 최고 온도 + 최대 풍량 + 유리 쪽 바람 + 시트·핸들·미러 열선
    private fun cold() {
        val h = v.getHVAC()
        hvacSeats.forEach { a ->
            h.setHvacPower(a, true, { ar, x -> log("SET power ${n(ar)} $x") }, err("power"))
            h.setHvacMaxDefrost(a, true, { ar, x -> log("SET maxDefrost ${n(ar)} $x") }, err("maxDefrost"))
        }
        defrostWindows.forEach { a -> h.setHvacDefroster(a, true, { ar, x -> log("SET defroster ${n(ar)} $x") }, err("defroster")) }
        tempBounds.forEach { (a, b) -> h.setHvacTemperature(a, b.maxValue ?: 32f, TemperatureUnit.CELSIUS, { ar, x -> log("SET temp ${n(ar)} $x") }, err("temp")) }
        fanBounds.forEach { (a, b) -> h.setHvacFanSpeed(a, b.maxValue ?: 8, { ar, x -> log("SET fan ${n(ar)} $x") }, err("fan")) }
        fanDirs.forEach { (a, l) -> if (FanDirectionType.DEFROST in l) h.setHvacFanDirection(a, FanDirectionType.DEFROST, { ar, x -> log("SET fanDir ${n(ar)} $x") }, err("fanDir")) }
        seatHeat.forEach { (a, b) -> v.getSeat().setHvacSeatTemperature(a, b.maxValue ?: 3, { ar, x -> log("SET seatHeat ${n(ar)} $x") }, err("seatHeat")) }
        v.getSteeringWheel().setHvacSteeringWheelHeat(if (wheelHeatMax > 0) wheelHeatMax else 1, { x -> log("SET wheelHeat $x") }, err("wheelHeat"))
        mirrors.forEach { (a, b) -> v.getSideMirror().setHvacSideMirrorHeat(a, b.maxValue ?: 1, { ar, x -> log("SET mirrorHeat ${n(ar)} $x") }, err("mirrorHeat")) }
    }

    // 비상 정차: 트렁크 열기(뒤차에 알림) + 문 잠금 해제 + 실내등, 타이어 공기압과 에어백 전개 구독
    private fun emergency() {
        doorPos.filterKeys { it.values.any { d -> d.name == "REAR" } }.forEach { (a, b) ->
            v.getDoor().setDoorPosition(a, b.maxValue ?: 1, { ar, x -> log("SET trunk ${n(ar)} $x") }, err("trunk"))
        }
        doorLocks.filter { a -> a.values.none { it.name == "HOOD" } }.forEach { a ->
            v.getDoor().setDoorLock(a, false, { ar, x -> log("SET unlock ${n(ar)} $x") }, err("unlock"))
        }
        v.getLight().setCabinLightSwitch(LightSwitch.ON, { x -> log("SET cabin $x") }, err("cabin"))
        // capability 조회가 지원되지 않아도 바퀴를 직접 지정해 읽어 본다
        if (wheels.isEmpty()) wheels = listOf(WheelValue.LEFT_FRONT, WheelValue.RIGHT_FRONT, WheelValue.LEFT_REAR, WheelValue.RIGHT_REAR).map { WheelArea(it) }
        wheels.forEach { w ->
            v.getTire().registerTirePressure(w, object : TirePressureListener {
                override fun onTirePressureUpdated(pressure: Float?) { log("EVT tire ${n(w)} $pressure") }
                override fun onFailed(e: Exception) { log("ERR tireListener ${e.message}") }
            })
        }
        airbagSeats.forEach { s ->
            v.getSafety().registerSeatAirbagDeployed(s, object : SeatAirbagDeployedListener {
                override fun onSeatAirbagDeployUpdated(deployed: Boolean?) { log("EVT airbag ${n(s)} $deployed") }
                override fun onFailed(e: Exception) { log("ERR airbagListener ${e.message}") }
            })
        }
    }

    private fun readBack() {
        val h = v.getHVAC()
        hvacSeats.forEach { a ->
            h.getHvacPowerState(a, { log("GET power ${n(a)} $it") }, err("gPower"))
            h.getHvacAcState(a, { log("GET ac ${n(a)} $it") }, err("gAc"))
            h.getHvacMaxAcState(a, { log("GET maxAc ${n(a)} $it") }, err("gMaxAc"))
            h.getHvacMaxDefrostState(a, { log("GET maxDefrost ${n(a)} $it") }, err("gMaxDefrost"))
            h.getHvacRecirculationState(a, { log("GET recirc ${n(a)} $it") }, err("gRecirc"))
            h.getHvacFanDirection(a, { log("GET fanDir ${n(a)} $it") }, err("gFanDir"))
            h.getHvacTemperatureCurrent(a, { log("GET cabinTemp ${n(a)} $it") }, err("gCabin"))
        }
        fanBounds.keys.forEach { a -> h.getHvacFanSpeed(a, { log("GET fan ${n(a)} $it") }, err("gFan")) }
        defrostWindows.forEach { a -> h.getHvacDefrosterState(a, { log("GET defroster ${n(a)} $it") }, err("gDefrost")) }
        windowMax.keys.forEach { a -> v.getWindow().getCurrentWindowPosition(a, { log("GET window ${n(a)} $it") }, err("gWin")) }
        seatHeat.keys.forEach { a -> v.getSeat().getHvacSeatTemperature(a, { log("GET seatHeat ${n(a)} $it") }, err("gSeatHeat")) }
        seatVent.keys.forEach { a -> v.getSeat().getHvacSeatVentilationState(a, { log("GET seatVent ${n(a)} $it") }, err("gSeatVent")) }
        v.getSteeringWheel().getHvacSteeringWheelHeatState({ log("GET wheelHeat $it") }, err("gWheel"))
        mirrors.keys.forEach { a -> v.getSideMirror().getHvacSideMirrorHeatState(a, { log("GET mirrorHeat ${n(a)} $it") }, err("gMirror")) }
        doorPos.keys.forEach { a -> v.getDoor().getCurrentDoorPosition(a, { log("GET doorPos ${n(a)} $it") }, err("gDoorPos")) }
        wheels.forEach { w -> v.getTire().getTirePressure(w, { log("GET tire ${n(w)} $it") }, err("gTire")) }
    }
}
