package dev.probe.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

// 앰비언트 라이트 실측: 영역·밝기 범위·읽기·주행 모드 자동 색, 밝기 set 6번과 되읽기, 원래 값 복구
// 실행: am start --user 10 -n dev.probe.pleos/.AmbientProbe --es phase stop|drive
class AmbientProbe : Activity() {
    private fun log(msg: String) { Log.i("AMB", msg) }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var v: Vehicle
    private var phase = "stop"
    private var areas: List<SeatArea> = emptyList()
    private var bounds: Map<SeatArea, Any> = emptyMap()
    private var min = 0
    private var max = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phase = intent.getStringExtra("phase") ?: "stop"
        v = Vehicle(applicationContext)
        try { v.initialize() } catch (e: Exception) { log("INIT ERR $e"); return }
        log("INIT ok phase=$phase")
        ui.postDelayed({ caps() }, 1500)
    }

    private val err: (String) -> (Exception) -> Unit = { t -> { e -> log("ERR $t ${e.javaClass.simpleName} ${e.message}") } }

    private fun caps() {
        val l = v.getLight()
        l.checkAmbientLightBrightnessColorCapabilities({ cfg, list -> log("CAP color cfg=$cfg areas=${list.map { it.values.joinToString() }}") }, err("capColor"))
        l.checkAmbientLightSwitchCapabilities({ cfg, m -> log("CAP switch cfg=$cfg ${m.map { it.key.values.joinToString() + "=" + it.value }}") }, err("capSwitch"))
        l.checkAmbientColorAutoByDriveModeEnabledCapabilities({ cfg -> log("CAP autoDriveMode cfg=$cfg") }, err("capAuto"))
        l.getAmbientColorAutoByDriveModeEnabled({ log("READ autoByDriveMode=$it") }, err("getAuto"))
        l.checkAmbientLightBrightnessCapabilities({ cfg, m ->
            log("CAP brightness cfg=$cfg")
            m.forEach { (a, b) -> log("CAP brightness area=${a.values.joinToString()} bounds=$b") }
            areas = m.keys.toList()
            bounds = m
            val b = m.values.firstOrNull()
            if (b != null) {
                // AreaBounds 필드 이름을 몰라도 getter 이름으로 읽는다
                min = (b.javaClass.methods.firstOrNull { it.name == "getMinValue" }?.invoke(b) as? Int) ?: 0
                max = (b.javaClass.methods.firstOrNull { it.name == "getMaxValue" }?.invoke(b) as? Int) ?: 100
            }
            log("RANGE min=$min max=$max")
            ui.postDelayed({ reads() }, 500)
        }, err("capBrightness"))
    }

    private var origBrightness: Int? = null
    private fun reads() {
        val a = areas.firstOrNull() ?: run { log("NO AREA"); return }
        val l = v.getLight()
        l.getAmbientLightSwitch(a, { log("READ switch=$it") }, err("getSwitch"))
        l.getAmbientLightBrightnessColor(a, { log("READ color=${Integer.toHexString(it ?: -1)}") }, err("getColor"))
        l.getAmbientLightBrightness(a, {
            origBrightness = it; log("READ brightness=$it")
            // 원래 밝기를 읽은 때만 쓴다(못 읽으면 끝에서 복구할 수 없다)
            if (it != null) ui.postDelayed({ sets(a, 1, 0) }, 1000) else log("SKIP sets: 원래 밝기를 못 읽음")
        }, { err("getBrightness")(it); log("SKIP sets: 원래 밝기를 못 읽음") })
    }

    // 6번: min..max를 7등분한 값을 차례로 쓰고, set 콜백 뒤 get으로 반영을 확인한다
    private fun sets(a: SeatArea, k: Int, ok: Int) {
        if (k > 6) {
            log("RESULT phase=$phase brightness applied $ok/6")
            val o = origBrightness
            if (o != null) v.getLight().setAmbientLightBrightness(a, o, { _, x -> log("RESTORE brightness=$x") }, err("restore"))
            return
        }
        val target = min + (max - min) * k / 7
        v.getLight().setAmbientLightBrightness(a, target, { _, cbv ->
            ui.postDelayed({
                v.getLight().getAmbientLightBrightness(a, { r ->
                    val hit = r == target
                    log("SET#$k target=$target cb=$cbv readBack=$r ${if (hit) "OK" else "MISS"}")
                    sets(a, k + 1, if (hit) ok + 1 else ok)
                }, { e -> log("ERR readBack ${e.message}"); sets(a, k + 1, ok) })
            }, 500)
        }, { e -> log("SET#$k target=$target ERR ${e.javaClass.simpleName} ${e.message}"); sets(a, k + 1, ok) })
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { v.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
