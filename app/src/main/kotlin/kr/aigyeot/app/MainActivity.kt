package kr.aigyeot.app

import android.Manifest
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.aigyeot.app.detect.MicService
import kr.aigyeot.app.host.Graph
import kr.aigyeot.app.host.MonitorService
import kr.aigyeot.app.ui.AppRoot
import kr.aigyeot.app.ui.AppTheme
import kr.aigyeot.app.ui.AppViewModel
import kr.aigyeot.app.ui.Notices
import kr.aigyeot.app.ui.appViewModelFactory
import kr.aigyeot.engine.BabyAboardFrom
import kr.aigyeot.engine.MicPath
import kr.aigyeot.engine.Notice

/**
 * 화면 하나(명세서 11장). DO를 선언하고(매니페스트, C365·C378) 주행 제한은 CarUxRestrictionsManager 값만 본다: 주행 최적화가 필요하거나
 * 값을 아직 모르면 주행 중 화면만 그린다(fail-closed, 조작 없음).
 * 화면은 [AppRoot]가 그리고, 여기는 플랫폼 일(차량 UX 제한·권한·마이크 서비스·알림 인텐트)만 한다.
 * 최상단에서 컨트롤러 Back은 moveTaskToBack(true)(finish 금지).
 */
class MainActivity : ComponentActivity() {
    private val g by lazy { Graph.get(this) }
    private val app: AppViewModel by viewModels { appViewModelFactory(g, this) }
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private val restricted = mutableStateOf<Boolean?>(null)   // null = 아직 모름 → 막음
    private var afterPermission: (() -> Unit)? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(CAR_SPEED) == PackageManager.PERMISSION_GRANTED) g.exec.resubscribeSpeed()   // C321 ①: 구독 때 못 붙은 속도를 다시
        if (micGranted()) afterPermission?.invoke()
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()   // 매니페스트(공용)에 테마를 못 정해 기본 제목 표시줄을 코드로 숨긴다. 제목은 화면 안에 있다
        MonitorService.start(this)
        watchDriving()
        permissions.launch(buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(CAR_SPEED)   // 차량 속도 런타임 권한(매니페스트에 선언됨)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
        handleNotice(intent)
        setContent {
            AppTheme {
                AppRoot(g, app, drive = restricted.value != false, onBabyAboard = { babyAboard(BabyAboardFrom.HOME) }, onExit = { moveTaskToBack(true) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotice(intent)
    }

    override fun onResume() {
        super.onResume()
        // 명세서 6장 시작 경로 '앱 실행': 화면이 있을 때 마이크 서비스를 켠다(프로필이 있을 때만)
        consented {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && micGranted() && MicService.runningPauses() == null) MicService.start(this@MainActivity, MicPath.APP_LAUNCH)
        }
    }

    private fun consented(then: () -> Unit) = lifecycleScope.launch { if (app.hasConsent()) then() }

    override fun onDestroy() {
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
        super.onDestroy()
    }

    private fun watchDriving() {
        car = runCatching {
            Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (!ready) { restricted.value = null; return@createCar }
                val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                ux = m
                restricted.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization
                m.registerListener { restricted.value = it.isRequiresDistractionOptimization }
            }
        }.getOrNull()
    }

    private companion object { const val CAR_SPEED = "android.car.permission.CAR_SPEED" }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 마이크 서비스는 화면이 있을 때만 켠다(명세서 6장). 권한이 없으면 묻고 나서 한다. */
    private fun withMic(path: MicPath, then: () -> Unit = {}) {
        val go = { MicService.start(this, path); then() }
        if (micGranted()) go() else { afterPermission = go; permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
    }

    private fun babyAboard(from: BabyAboardFrom) =
        withMic(if (from == BabyAboardFrom.HOME) MicPath.HOME_BUTTON else MicPath.NOTICE) { app.babyAboard(from) }

    private fun handleNotice(i: Intent?) {
        // C352: 비공개 alias로 들어온 intent(알림)만 믿는다. 직접 MainActivity를 연 다른 앱의 extra는 버린다
        val extra = if (i?.component?.className == Notices.ENTRY) i.getStringExtra(Notices.EXTRA) else null
        i?.removeExtra(Notices.EXTRA)   // 화면을 다시 만들 때 같은 액션을 되풀이하지 않게(코루틴보다 먼저 지운다)
        when (extra) {
            Notices.BABY_ABOARD -> { Notices.cancel(this, Notice.BABY_ABOARD); consented { babyAboard(BabyAboardFrom.NOTICE) } }
            Notices.MIC_RESTART -> { Notices.cancel(this, Notice.MIC_STOPPED); consented { withMic(MicPath.NOTICE) } }
            Notices.FEEDING_YES, Notices.FEEDING_NO -> app.feedingConfirm(extra == Notices.FEEDING_YES)   // 알림 취소 포함
        }
    }
}
