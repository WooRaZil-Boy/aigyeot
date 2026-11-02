package dev.probe.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView

// 에뮬레이터에서 경고등·앰비언트·제어·상태 API가 실제로 동작하는지 기록하는 일회용 실측 앱
class MainActivity : Activity() {
    private lateinit var out: TextView
    private lateinit var vehicle: Vehicle
    private val ui = Handler(Looper.getMainLooper())
    private val last = HashMap<String, String>()
    private var seatAreas: List<SeatArea> = emptyList()
    private var windowAreas: List<WindowArea> = emptyList()
    private var airbagAreas: List<SeatArea> = emptyList()
    private var tempAreas: List<SeatArea> = emptyList()
    private var recircAreas: List<SeatArea> = emptyList()
    private var ambientArea: SeatArea? = null
    private var lastSteerAt = 0L
    private var wiperAreas: List<WindowArea> = emptyList()
    private var windowPosAreas: List<WindowArea> = emptyList()
    private val windowPos = HashMap<WindowArea, Int>()
    private var raining = false
    private var lowBeamBeforeRain: LightSwitch? = null
    private var doorLockAreas: List<DoorArea> = emptyList()
    private val windowMax = HashMap<WindowArea, Int>()
    private var escaped = false

    // 침수 탈출 대비 시험: 실제 앱은 침수 위험 구간 정차·음성 확인으로 시작하고, 여기서는 앞 와이퍼 고속을 신호로 쓴다
    private fun escapePrep() {
        if (escaped) return
        escaped = true
        log("FLOOD escape prep start")
        windowMax.forEach { (a, max) ->
            vehicle.getWindow().setWindowPosition(a, max, { ar, v -> log("FLOOD window open ${ar.values.joinToString()} -> $v") }, err("openWindow"))
        }
        doorLockAreas.forEach { a ->
            vehicle.getDoor().setDoorLock(a, false, { ar, v -> log("FLOOD door lock ${ar.values.joinToString()} -> $v") }, err("unlockDoor"))
        }
        vehicle.getLight().setCabinLightSwitch(LightSwitch.ON, { log("FLOOD cabin light $it") }, err("cabinOn"))
        ambientArea?.let { a ->
            vehicle.getLight().setAmbientLightSwitch(a, LightSwitch.ON, { _, _ -> }, err("ambOn"))
            vehicle.getLight().setAmbientLightBrightnessColor(a, 0xFF0000, { _, v -> log("FLOOD ambient color ${Integer.toHexString(v ?: 0)}") }, err("ambRed"))
        }
    }

    // 비 모드 시험: 와이퍼가 돌기 시작하면 전조등을 켜고 열린 창문을 닫는다. 와이퍼가 멈추면 전조등을 원래대로 돌린다.
    private fun onWiper(active: Boolean) {
        if (active == raining) return
        raining = active
        val light = vehicle.getLight()
        if (active) {
            light.getLowBeamLightSwitch({ before ->
                lowBeamBeforeRain = before
                light.setLowBeamLightSwitch(LightSwitch.ON, { log("RAIN lowBeam ON (was $before)") }, err("setLowBeam"))
            }, err("getLowBeamSwitch"))
            windowPos.filter { it.value > 0 }.keys.forEach { a ->
                vehicle.getWindow().setWindowPosition(a, 0, { ar, v -> log("RAIN window closed ${ar.values.joinToString()} -> $v") }, err("setWindow"))
            }
        } else {
            val restore = lowBeamBeforeRain ?: LightSwitch.AUTOMATIC
            light.setLowBeamLightSwitch(restore, { log("RAIN over, lowBeam restored $restore") }, err("restoreLowBeam"))
        }
    }

    private fun log(msg: String) {
        Log.i("PROBE", msg)
        ui.post { out.append(msg + "\n") }
    }

    // 값이 바뀔 때만 기록해 로그를 짧게 유지한다
    private fun changed(key: String, value: String) {
        if (last[key] != value) { last[key] = value; log("$key = $value") }
    }

    private val err: (String) -> (Exception) -> Unit = { tag -> { e -> log("ERR $tag: ${e.javaClass.simpleName} ${e.message}") } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 13f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(out) })
        vehicle = Vehicle(applicationContext)
        try { vehicle.initialize() } catch (e: Exception) { log("INIT ERR $e"); return }
        log("init ok")
        ui.postDelayed({ discover() }, 1500)
    }

    private fun discover() {
        val light = vehicle.getLight()
        light.checkAmbientLightSwitchCapabilities({ cfg, map ->
            log("CAP ambientSwitch $cfg ${map.keys}")
            ambientArea = map.keys.firstOrNull()
        }, err("capAmbientSwitch"))
        light.checkAmbientLightBrightnessColorCapabilities({ cfg, list -> log("CAP ambientColor $cfg $list") }, err("capAmbientColor"))
        light.checkCabinLightSwitchCapabilities({ cfg, list -> log("CAP cabinLight $cfg $list") }, err("capCabin"))
        vehicle.getHVAC().checkHvacTemperatureCurrentCapabilities({ _, list -> tempAreas = list }, err("capTemp"))
        vehicle.getHVAC().checkHvacRecirculationCapabilities({ cfg, list -> log("CAP recirc $cfg $list"); recircAreas = list }, err("capRecirc"))
        vehicle.getSeat().checkSeatOccupancyCapabilities({ _, list -> seatAreas = list }, err("capSeat"))
        vehicle.getWindow().checkWindowLockCapabilities({ cfg, list -> log("CAP windowLock $cfg $list"); windowAreas = list }, err("capWinLock"))
        vehicle.getSafety().checkAirbagCapabilities({ cfg, list -> log("CAP airbag $cfg $list"); airbagAreas = list }, err("capAirbag"))
        vehicle.getWiper().checkWiperStateCapabilities({ cfg, map -> log("CAP wiper $cfg ${map.mapKeys { it.key.values.joinToString() }}"); wiperAreas = map.keys.toList() }, err("capWiper"))
        vehicle.getWindow().checkWindowPositionCapabilities({ cfg, map -> log("CAP windowPos $cfg ${map.values.firstOrNull()}"); windowPosAreas = map.keys.toList(); map.forEach { (a, b) -> windowMax[a] = b.maxValue ?: 10 } }, err("capWinPos"))
        vehicle.getDoor().checkDoorLockCapabilities({ cfg, list -> log("CAP doorLock $cfg ${list.map { it.values.joinToString() }}"); doorLockAreas = list }, err("capDoorLock"))
        vehicle.getLight().checkLowBeamLightSwitchCapabilities({ cfg, list -> log("CAP lowBeamSwitch $cfg $list") }, err("capLowBeam"))
        vehicle.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) {
                val now = SystemClock.elapsedRealtime()
                Log.i("PROBE", "STEER $angle dt=${if (lastSteerAt == 0L) 0 else now - lastSteerAt}ms")
                lastSteerAt = now
            }
            override fun onFailed(e: Exception) { log("ERR steerListener ${e.message}") }
        })
        ui.postDelayed({ writeTests() }, 2000)
        ui.post(poll)
    }

    // 쓰기 시험: 앰비언트(CAR_LIGHTS)와 제어 권한 API(CONTROL_*)가 에뮬레이터에서 실제로 바뀌는지
    private fun writeTests() {
        val light = vehicle.getLight()
        val area = ambientArea
        if (area != null) {
            light.setAmbientLightSwitch(area, LightSwitch.ON, { a, v -> log("SET ambientSwitch ok $a $v") }, err("setAmbientSwitch"))
            light.setAmbientLightBrightness(area, 40, { a, v -> log("SET ambientBrightness ok $a $v") }, err("setAmbientBrightness"))
            light.setAmbientLightBrightnessColor(area, 0xFF0000, { a, v -> log("SET ambientColor ok $a $v") }, err("setAmbientColor"))
        }
        light.setCabinLightSwitch(LightSwitch.ON, { v -> log("SET cabinLight ok $v") }, err("setCabinLight"))
        recircAreas.firstOrNull()?.let { a ->
            vehicle.getHVAC().setHvacRecirculation(a, false, { ar, v -> log("SET recirc ok $ar $v") }, err("setRecirc"))
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            vehicle.getGear().getCurrentGearSelectionState({ changed("gear", "$it") }, err("gear"))
            vehicle.getSafety().getTelltalesInfo({ t ->
                val active = t.telltales.filter { "${it.state}" != "OFF" }.joinToString { "${it.type}:${it.state}:c${it.color}" }
                changed("telltales", active.ifEmpty { "(none)" })
            }, err("telltales"))
            ambientArea?.let { a ->
                vehicle.getLight().getAmbientLightSwitch(a, { changed("ambientSwitch", "$it") }, err("getAmbientSwitch"))
                vehicle.getLight().getAmbientLightBrightness(a, { changed("ambientBrightness", "$it") }, err("getAmbientBrightness"))
                vehicle.getLight().getAmbientLightBrightnessColor(a, { changed("ambientColor", Integer.toHexString(it ?: -1)) }, err("getAmbientColor"))
            }
            vehicle.getLight().getCabinLightSwitch({ changed("cabinLight", "$it") }, err("getCabin"))
            tempAreas.forEach { a -> vehicle.getHVAC().getHvacTemperatureCurrent(a, { changed("cabinTemp $a", "$it") }, err("temp")) }
            recircAreas.forEach { a -> vehicle.getHVAC().getHvacRecirculationState(a, { changed("recirc", "$it") }, err("recirc")) }
            seatAreas.forEach { a -> vehicle.getSeat().getCurrentSeatOccupancy(a, { changed("occupied $a", "$it") }, err("seat")) }
            windowAreas.forEach { a -> vehicle.getWindow().getCurrentWindowLockState(a, { changed("windowLock $a", "$it") }, err("winLock")) }
            airbagAreas.forEach { a -> vehicle.getSafety().hasSeatAirbagEnabled(a, { changed("airbagEnabled $a", "$it") }, err("airbag")) }
            windowPosAreas.forEach { a -> vehicle.getWindow().getCurrentWindowPosition(a, { p -> windowPos[a] = p ?: 0; changed("windowPos ${a.values.joinToString()}", "$p") }, err("winPos")) }
            vehicle.getLight().getLowBeamLightSwitch({ changed("lowBeamSwitch", "$it") }, err("lowBeamSwitch"))
            doorLockAreas.forEach { a -> vehicle.getDoor().getDoorLockState(a, { changed("doorLocked ${a.values.joinToString()}", "$it") }, err("doorLock")) }
            wiperAreas.firstOrNull()?.let { a ->
                vehicle.getWiper().getCurrentWiperSwitch(a, { s ->
                    changed("wiperSwitch ${a.values.joinToString()}", "$s")
                    if ("$s".startsWith("CONTINUOUS")) ui.post { escapePrep() }
                }, err("wiperSwitch"))
            }
            vehicle.getLight().getCurrentLowBeamLightState({ changed("lowBeamState", "$it") }, err("lowBeamState"))
            var anyOn = false
            var pending = wiperAreas.size
            wiperAreas.forEach { a ->
                vehicle.getWiper().getCurrentWiperState(a, { s ->
                    changed("wiper ${a.values.joinToString()}", "$s")
                    if ("$s" == "ON") anyOn = true
                    if (--pending == 0) ui.post { onWiper(anyOn) }
                }, err("wiper"))
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { vehicle.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
