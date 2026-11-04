package kr.aigyeot.app

import android.Manifest
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.aigyeot.app.detect.MicService
import kr.aigyeot.app.host.Graph
import kr.aigyeot.app.host.MonitorService
import kr.aigyeot.app.store.buildReport
import kr.aigyeot.app.store.db.ChildProfileEntity
import kr.aigyeot.app.store.toProfile
import kr.aigyeot.app.ui.AppTheme
import kr.aigyeot.app.ui.Body
import kr.aigyeot.app.ui.ConnectScreen
import kr.aigyeot.app.ui.DemoScreen
import kr.aigyeot.app.ui.HomeScreen
import kr.aigyeot.app.ui.Notices
import kr.aigyeot.app.ui.Page
import kr.aigyeot.app.ui.ReportDetailScreen
import kr.aigyeot.app.ui.ReportListScreen
import kr.aigyeot.app.ui.SetupScreen
import kr.aigyeot.app.ui.SongsScreen
import kr.aigyeot.engine.AskButton
import kr.aigyeot.engine.BabyAboard
import kr.aigyeot.engine.BabyAboardFrom
import kr.aigyeot.engine.ClearRecords
import kr.aigyeot.engine.FeedingConfirmed
import kr.aigyeot.engine.FeedingFrom
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.MicPath
import kr.aigyeot.engine.Notice
import kr.aigyeot.engine.ProfileChanged

/**
 * 정차 화면 하나(명세서 11장). DO를 선언하지 않는다(매니페스트). 주행 제한은 CarUxRestrictionsManager 값만 보고, 값을 받기 전에는 막는다(fail-closed).
 * 최상단에서 컨트롤러 Back은 moveTaskToBack(true)(finish 금지).
 */
class MainActivity : ComponentActivity() {
    private val g by lazy { Graph.get(this) }
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private val restricted = mutableStateOf<Boolean?>(null)   // null = 아직 모름 → 막음
    private var afterPermission: (() -> Unit)? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
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
        setContent { AppTheme { if (restricted.value == false) App() else Page("아이곁", null) { Body("차가 서 있을 때 쓸 수 있어요") } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotice(intent)
    }

    override fun onResume() {
        super.onResume()
        // 명세서 6장 시작 경로 '앱 실행': 화면이 있을 때 마이크 서비스를 켠다(프로필이 있을 때만)
        consented {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && micGranted() && MicService.runningPauses() == null) MicService.start(this@MainActivity, MicPath.APP_LAUNCH)
        }
    }

    /** 보호자 동의(첫 설정 저장) 전에는 어느 경로로도 마이크를 켜지 않는다. 프로필은 동의해야 저장된다 */
    private fun consented(then: () -> Unit) = lifecycleScope.launch { if (withContext(Dispatchers.IO) { g.store.dao.profile() != null }) then() }

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
        withMic(if (from == BabyAboardFrom.HOME) MicPath.HOME_BUTTON else MicPath.NOTICE) { g.host.post(BabyAboard(0, from)) }

    private fun handleNotice(i: Intent?) {
        val extra = i?.getStringExtra(Notices.EXTRA)
        i?.removeExtra(Notices.EXTRA)   // 화면을 다시 만들 때 같은 액션을 되풀이하지 않게(코루틴보다 먼저 지운다)
        when (extra) {
            Notices.BABY_ABOARD -> { Notices.cancel(this, Notice.BABY_ABOARD); consented { babyAboard(BabyAboardFrom.NOTICE) } }
            Notices.MIC_RESTART -> { Notices.cancel(this, Notice.MIC_STOPPED); consented { withMic(MicPath.NOTICE) } }
            Notices.FEEDING_YES, Notices.FEEDING_NO -> {
                Notices.cancel(this, Notice.FEEDING_CONFIRM)
                g.host.post(FeedingConfirmed(0, extra == Notices.FEEDING_YES))
            }
        }
    }

    private fun noRearOutput() = getSystemService(AudioManager::class.java)
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type == AudioDeviceInfo.TYPE_BUS && it.address == "bus100_audio_zone_1" }

    private fun saveProfile(p: ChildProfileEntity) = lifecycleScope.launch {
        withContext(Dispatchers.IO) { g.store.saveProfile(p) }   // Room 먼저, 그다음 사건(복원 대조와 같은 순서)
        g.host.post(ProfileChanged(0, p.toProfile()))
    }

    private class Loaded(val p: ChildProfileEntity?)

    @Composable
    private fun App() {
        val s = g.host.steps.collectAsState().value?.state
        val prof = remember { g.store.dao.profileFlow().map { Loaded(it) } }.collectAsState(null).value ?: return
        val now by produceState(g.clock()) { while (true) { value = g.clock(); delay(30_000) } }
        var screen by rememberSaveable { mutableStateOf("home") }
        val home = { screen = "home" }
        BackHandler { if (screen == "home" || prof.p == null) moveTaskToBack(true) else home() }
        if (prof.p == null) return SetupScreen(null, now, noRearOutput(), null) { saveProfile(it) }
        when {
            screen == "home" -> HomeScreen(
                s, now,
                onBabyAboard = { babyAboard(BabyAboardFrom.HOME) },
                onFeedingNow = { g.host.post(FeedingStarted(0, g.clock(), FeedingFrom.CAR_SCREEN, null)) },
                onAsk = { g.host.post(AskButton(0)) },
                onFeedingConfirm = { g.host.post(FeedingConfirmed(0, it)); Notices.cancel(this, Notice.FEEDING_CONFIRM) },
                go = { screen = it },
            )
            screen == "setup" -> SetupScreen(prof.p, now, noRearOutput(), home) { saveProfile(it); home() }
            screen == "connect" -> ConnectScreen(g.link, g.clock, home)
            screen == "songs" -> SongsScreen(
                remember { g.store.dao.activeSongsFlow() }.collectAsState(emptyList()).value,
                remember { g.store.dao.selectionFlow() }.collectAsState(null).value,
                s?.methodsNext.orEmpty(), { g.link.selectSong(it) }, home,
            )
            screen == "report" -> ReportListScreen(remember { g.store.dao.sessionsFlow() }.collectAsState(emptyList()).value, { screen = "report:$it" }, home)
            screen.startsWith("report:") -> ReportDetailScreen(screen.removePrefix("report:"), { buildReport(g.store.dao, it, g.clock()) }) { screen = "report" }
            screen == "demo" -> DemoScreen(g.scenario, onClear = { g.host.post(ClearRecords(0)) }, onBack = home)
        }
    }
}
