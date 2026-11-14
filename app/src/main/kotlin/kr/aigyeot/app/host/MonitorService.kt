package kr.aigyeot.app.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.Car
import android.car.hardware.power.CarPowerManager
import android.car.hardware.power.CarPowerPolicyFilter
import android.car.hardware.power.PowerComponent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.util.Log
import kr.aigyeot.app.R
import kr.aigyeot.engine.Tick

/**
 * 감시 서비스(specialUse). 부팅·앱 시작 때 뜬다. 실행기·연결을 붙들고, Suspend·Resume을 짐작해 호스트에 넣는다.
 * 마이크는 쓰지 않는다.
 */
class MonitorService : Service() {
    private lateinit var g: Graph
    private var screen: BroadcastReceiver? = null
    private var net: ConnectivityManager.NetworkCallback? = null
    private var car: Car? = null
    private var power: Pair<CarPowerManager, CarPowerManager.CarPowerPolicyListener>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "탑승·주행", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setContentTitle("탑승과 주행을 살피고 있어요") // 탑승 전·주행 중 모두 맞는 글
            .setSmallIcon(R.drawable.ic_stat_aigyeot).setOngoing(true).build()
        try {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (
            e: Exception
        ) {
            Log.e(TAG, "startForeground 실패", e)
            stopSelf()
            return
        }
        g = Graph.get(this) // 호스트 복원(①~⑤)이 여기서 큐에 들어간다
        g.startModules()
        // 전원 정책 리스너(되면 우선) → 화면 꺼짐·켜짐 → 시계 점프 → 네트워크. 같은 짐작을 여러 신호가 내도 한 번만 넣는다
        watchPowerPolicy()
        screen = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = signal(off = i.action == Intent.ACTION_SCREEN_OFF, i.action ?: "")
        }.also {
            registerReceiver(
                it,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
            )
        }
        net = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "신호 network")
                g.host.post(Tick(0)) // 호스트가 깨어 시계 점프를 본다
            }
        }.also { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(it) }
    }

    /** 오디오 부품이 꺼지는 정책 = Suspend 준비로 본다(첫날 suspend --simulate로 확인할 짐작, 미판정). 권한·서비스가 없으면 로그만. */
    private fun watchPowerPolicy() {
        car = runCatching {
            Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (!ready) return@createCar
                runCatching {
                    val pm = c.getCarManager(Car.POWER_SERVICE) as CarPowerManager
                    val l = CarPowerManager.CarPowerPolicyListener { policy ->
                        signal(off = !policy.isComponentEnabled(PowerComponent.AUDIO), "policy:${policy.policyId}")
                    }
                    pm.addPowerPolicyListener(mainExecutor, CarPowerPolicyFilter.Builder().setComponents(PowerComponent.AUDIO).build(), l)
                    power = pm to l
                }.onFailure { Log.w(TAG, "전원 정책 리스너 못 붙음", it) }
            }
        }.onFailure { Log.w(TAG, "Car 연결 실패", it) }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        screen?.let { unregisterReceiver(it) }
        net?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        power?.let { (pm, l) -> runCatching { pm.removePowerPolicyListener(l) } }
        runCatching { car?.disconnect() }
        if (::g.isInitialized) {
            g.stopModules()
            g.host.flush()
        }
        super.onDestroy()
    }

    private fun signal(off: Boolean, source: String) {
        Log.i(TAG, "신호 $source off=$off")
        if (off) g.host.suspend() else g.host.resume() // 중복은 호스트가 막는다
    }

    companion object {
        private const val TAG = "monitor"
        private const val CHANNEL = "monitor"
        private const val ID = 1

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
    }
}

/** 부팅 때 감시 서비스를 띄운다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            MonitorService.start(context)
        } catch (e: Exception) {
            Log.e("monitor", "부팅 시작 실패", e)
        }
    }
}
