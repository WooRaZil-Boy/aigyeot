package dev.probe.pleos

import ai.pleos.playground.vehicle.Vehicle
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

// 기어 공개 경로(AAOS CarPropertyManager GEAR_SELECTION, CAR_POWERTRAIN)와 Pleos Vehicle.getGear() 값을 나란히 찍는 일회용 실측 화면
// 실행: am start --user 10 -n dev.probe.pleos/.GearProbe
class GearProbe : Activity() {
    private fun log(msg: String) { Log.i("GEAR", msg) }
    private val ui = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var mgr: CarPropertyManager? = null
    private var vehicle: Vehicle? = null

    private val cb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) = log("PUB onChange gear=${v.value} areaId=${v.areaId} status=${v.status}")
        override fun onErrorEvent(propId: Int, areaId: Int) = log("PUB onError prop=$propId area=$areaId")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log("PERM CAR_POWERTRAIN granted=${checkSelfPermission("android.car.permission.CAR_POWERTRAIN")}")
        vehicle = Vehicle(applicationContext).also { try { it.initialize() } catch (e: Exception) { log("PLEOS init ERR $e") } }
        car = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            log("CAR ready=$ready")
            if (!ready) return@createCar
            try {
                val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                mgr = m
                val ok = m.registerCallback(cb, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                log("PUB register=$ok propId=${VehiclePropertyIds.GEAR_SELECTION}")
            } catch (e: Throwable) { log("PUB register ERR ${e.javaClass.simpleName} ${e.message}") }
            ui.post(poll)
        }
    }

    // 1초마다 공개 경로와 Pleos 경로를 한 줄로 찍는다
    private val poll = object : Runnable {
        override fun run() {
            val pub = try { "${mgr?.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)}" } catch (e: Throwable) { "ERR ${e.javaClass.simpleName} ${e.message}" }
            vehicle?.getGear()?.getCurrentGearSelectionState({ log("PAIR public=$pub pleos=$it") }, { e -> log("PAIR public=$pub pleos=ERR ${e.javaClass.simpleName} ${e.message}") })
            ui.postDelayed(this, 1000)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { mgr?.unregisterCallback(cb) } catch (_: Throwable) {}
        try { car?.disconnect() } catch (_: Throwable) {}
        try { vehicle?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
