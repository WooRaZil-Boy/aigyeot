package dev.probe.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

// 부팅 직후 사용자가 앱을 열지 않아도 감시가 시작되는지 확인하는 일회용 실측
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("PROBE", "BOOT received ${intent.action}")
        try {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        } catch (e: Exception) {
            Log.i("PROBE", "BOOT startForegroundService failed ${e.javaClass.simpleName} ${e.message}")
        }
        // 경로 A: 부팅 리시버에서 바로 마이크 서비스 시작
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("MICTEST", "boot-receiver importance=${importance()}")
            try {
                context.startForegroundService(Intent(context, MicService::class.java).putExtra("tag", "boot-receiver"))
            } catch (e: Exception) {
                Log.i("MICTEST", "boot-receiver startForegroundService ERR ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }
}

class MonitorService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var wiperArea: WindowArea? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("probe", "probe", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "probe").setContentTitle("Probe monitor").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            Log.i("PROBE", "SERVICE foreground ok")
        } catch (e: Exception) {
            Log.i("PROBE", "SERVICE startForeground failed ${e.javaClass.simpleName} ${e.message}")
        }
        // 문 이벤트를 받은 상황을 시간으로 흉내 낸다: 앱 화면 없이 감시 서비스만 돌 때 마이크를 여는 세 가지 방법
        h.postDelayed({ tryStartMicService() }, 40_000)
        h.postDelayed({ thread { recordAndReport(this, "bg-specialuse-record", 8) } }, 60_000)
        h.postDelayed({ tryUpgradeToMicrophone() }, 80_000)
        h.postDelayed({ tryLaunchActivity() }, 100_000)
        vehicle = Vehicle(applicationContext).also {
            try { it.initialize(); Log.i("PROBE", "SERVICE vehicle init ok") } catch (e: Exception) { Log.i("PROBE", "SERVICE vehicle init failed $e") }
        }
        vehicle?.getWiper()?.checkWiperStateCapabilities({ _, map -> wiperArea = map.keys.firstOrNull() }, { e -> Log.i("PROBE", "SERVICE capWiper ${e.message}") })
        h.post(object : Runnable {
            override fun run() {
                val a = wiperArea
                if (a != null) vehicle?.getWiper()?.getCurrentWiperState(a, { Log.i("PROBE", "SERVICE wiper $it") }, { e -> Log.i("PROBE", "SERVICE wiper err ${e.message}") })
                h.postDelayed(this, 5000)
            }
        })
    }

    // 경로 B: 감시 서비스(백그라운드)에서 마이크 서비스를 새로 시작
    private fun tryStartMicService() {
        Log.i("MICTEST", "bg-start-fgs importance=${importance()}")
        try {
            startForegroundService(Intent(this, MicService::class.java).putExtra("tag", "bg-start-fgs"))
        } catch (e: Exception) {
            Log.i("MICTEST", "bg-start-fgs startForegroundService ERR ${e.javaClass.simpleName} ${e.message}")
        }
    }

    // 경로 E: 감시 서비스가 화면 없는 실행 화면을 스스로 띄우고, 그 화면에서 마이크 서비스를 시작한다
    private fun tryLaunchActivity() {
        Log.i("MICTEST", "bg-activity-launch importance=${importance()}")
        try {
            startActivity(Intent(this, ScenarioProbe::class.java).putExtra("mode", "mic").putExtra("tag", "bg-activity-launch").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i("MICTEST", "bg-activity-launch startActivity returned")
        } catch (e: Exception) {
            Log.i("MICTEST", "bg-activity-launch startActivity ERR ${e.javaClass.simpleName} ${e.message}")
        }
    }

    // 경로 D: 이미 돌고 있는 감시 서비스의 종류에 마이크를 더한다
    private fun tryUpgradeToMicrophone() {
        Log.i("MICTEST", "bg-upgrade-type importance=${importance()}")
        try {
            startForeground(1, micNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            Log.i("MICTEST", "bg-upgrade-type startForeground(SPECIAL_USE|MICROPHONE) ok")
            thread { recordAndReport(this, "bg-upgrade-type", 8) }
        } catch (e: Exception) {
            Log.i("MICTEST", "bg-upgrade-type startForeground ERR ${e.javaClass.simpleName} ${e.message}")
        }
    }
}

fun importance(): Int = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
