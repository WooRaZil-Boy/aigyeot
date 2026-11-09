package kr.aigyeot.phone.presentation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.DisconnectRun
import kr.aigyeot.phone.domain.model.PAIR_BUSY
import kr.aigyeot.phone.domain.model.PairRun
import kr.aigyeot.phone.domain.model.SAVE_FAILED
import kr.aigyeot.shared.Role

/** 화면 경로(타입 있는 Navigation). */
@Serializable
sealed interface Route {
    @Serializable data object Intro : Route
    @Serializable data object Connect : Route
    @Serializable data class Confirm(val code: String) : Route

    /** `role`은 [Role] 이름. */
    @Serializable data class Done(val role: String) : Route
    @Serializable data object Gone : Route

    /** 탭(5장): 연결 안 됨·owner·guardian이 같은 경로를 쓰고 탭 모양만 다르다. */
    @Serializable data object Home : Route
    @Serializable data class Timer(val id: String) : Route
    @Serializable data object About : Route
    @Serializable data class License(val index: Int) : Route
    @Serializable data object Songs : Route
    @Serializable data object Reports : Route
    @Serializable data class Report(val id: String) : Route
    @Serializable data object Guardians : Route
    @Serializable data class Alert(val id: String) : Route
}

/** 화면이 ViewModel을 얻는 공장. 화면은 저장소·컨테이너를 보지 않는다. */
val LocalViewModelFactory = staticCompositionLocalOf<ViewModelProvider.Factory> { error("ViewModel 공장이 없다") }

@Composable
inline fun <reified T : ViewModel> appViewModel(): T = viewModel(factory = LocalViewModelFactory.current)

/** 뒤로 가기 기록을 비우고 이동한다. */
private fun NavHostController.reset(route: Route) = navigate(route) { popUpTo(0) { inclusive = true } }

@Composable
fun AppRoot(app: AppViewModel) {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val ui by app.ui.collectAsStateWithLifecycle()
    fun toast(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
    // 첫 실행 안내 화면에서 받은 연결 링크의 코드: 안내를 읽은 뒤 '차와 연결하기'에서 확인 화면을 연다(7.1, U62).
    var introCode by rememberSaveable { mutableStateOf<String?>(null) }
    // 시작 화면은 처음 한 번만 정한다. 연결이 바뀔 때 그래프가 바뀌면 이력이 사라지고 연결됨·확인 화면의 작업이 끊긴다. 연결·해제 뒤 이동은 화면마다 명시한다.
    val start = remember { app.startRoute() }
    val gone = ui.connection is Connection.Gone

    LaunchedEffect(gone) { if (gone) nav.reset(Route.Gone) }
    // 저장된 화면 이력이 복원되면 시작 화면은 쓰이지 않는다(프로세스가 죽은 뒤 최근 앱으로 돌아옴): 완료 화면을 넘기지 않았으면 그리로 간다(7.1).
    LaunchedEffect(Unit) {
        // 이미 넘긴 GONE·완료 화면이 저장된 이력에서 복원됐으면(고른 뒤 이력을 저장하기 전에 죽음) 시작 화면으로 돌린다.
        val restored = nav.currentDestination
        if ((restored?.hasRoute<Route.Gone>() == true && app.ui.value.connection !is Connection.Gone) ||
            (restored?.hasRoute<Route.Done>() == true && app.pendingDoneRole() == null)
        ) {
            nav.reset(app.afterReset())
        }
        val role = app.takeRestoredDone() ?: return@LaunchedEffect
        // 복원된 완료 화면이라도 역할이 다르면(옛 owner 완료 위에 새 guardian 연결) 바꾼다: 알림 권한 단계는 지금 역할로 정한다.
        val top = nav.currentBackStackEntry
        if (top?.destination?.hasRoute<Route.Done>() != true || top.toRoute<Route.Done>().role != role.name) nav.reset(Route.Done(role.name))
    }
    // U52: 끝난 연결 결과는 그것을 받는 화면(연결·확인)만 쓴다. 다른 화면에 있으면 비워 새 링크를 막지 않게 한다.
    val top by nav.currentBackStackEntryAsState()
    LaunchedEffect(ui.pairRun, top) {
        val entry = top ?: return@LaunchedEffect
        val r = ui.pairRun?.takeIf { it !is PairRun.Running } ?: return@LaunchedEffect
        // 그 결과를 실제로 받는 화면이면 맡긴다: 같은 코드의 확인 화면, 또는 연결 화면의 실패(직접 친 코드의 오류는 입력칸 아래에 보인다).
        val receiver = when {
            entry.destination.hasRoute<Route.Connect>() -> r is PairRun.Failed
            entry.destination.hasRoute<Route.Confirm>() -> entry.toRoute<Route.Confirm>().code == r.code
            else -> false
        }
        if (receiver) return@LaunchedEffect
        // 다른 화면에 있는 사이 지금 연결이 성공했다: 완료 화면으로 간다(guardian 알림 권한 단계를 빠뜨리지 않게, 7.1). 실패·지난 결과는 비운다.
        if (app.takePairDone(r)) nav.reset(Route.Done((r as PairRun.Done).role!!.name)) else app.clearPairRun()
    }
    // 연결 정보를 거듭 읽지 못하면 지우고 새로 연결할지 묻는다(11-07 사용자 결정). 돌봄 기록은 남는다.
    (ui.pairRun as? PairRun.Failed)?.takeIf { it.resetOffer }?.let { r ->
        AlertDialog(
            onDismissRequest = app::clearPairRun,
            title = { Text("연결 정보를 읽지 못했어요") },
            text = { Text("이 휴대폰의 연결 정보를 지우고 새로 연결할까요? 돌봄 기록은 그대로 남아요. 전에 연결했던 이 휴대폰은 차 화면에서 지워 주세요.") },
            confirmButton = { TextButton(onClick = { app.resetAndPair(r.code) }) { Text("지우고 연결하기") } },
            dismissButton = { TextButton(onClick = app::clearPairRun) { Text("취소") } },
        )
    }
    // U25 b: 설정에서 끊기가 끝나면, 그사이 다른 화면을 열었어도 탭으로 돌아간다. 이력에 탭이 없으면 새로 간다. 오늘 탭 고르기는 CareTabs가 한다.
    LaunchedEffect(ui.disconnectRun) {
        if (ui.disconnectRun == DisconnectRun.Done && !nav.popBackStack<Route.Home>(inclusive = false)) nav.reset(Route.Home)
    }
    // 코드는 확인 화면 경로에 실렸으니 바로 비운다. 다시 그려도 확인 화면을 또 띄우지 않는다.
    LaunchedEffect(ui.pendingCode) {
        val code = ui.pendingCode ?: return@LaunchedEffect
        when {
            // 직접 친 코드의 요청이 진행 중이면 확인 화면으로 옮기지 않는다(끝난 결과를 받을 화면이 사라진다).
            !app.canTakeLink() -> toast(PAIR_BUSY)
            // 첫 실행이면 무엇이 오가는지 안내 한 화면을 먼저 보인다(7.1, U62).
            nav.currentDestination?.hasRoute<Route.Intro>() == true -> introCode = code
            else -> nav.navigate(Route.Confirm(code))
        }
        app.consumePendingCode()
    }
    LaunchedEffect(ui.openAlert, ui.connection) {
        val id = ui.openAlert ?: return@LaunchedEffect
        if (ui.connection is Connection.Paired) nav.navigate(Route.Alert(id))
        app.consumeOpenAlert()
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        NavHost(nav, startDestination = start) {
            composable<Route.Intro> {
                IntroScreen(
                    onConnect = { introCode?.let { c -> introCode = null; nav.navigate(Route.Confirm(c)) } ?: nav.navigate(Route.Connect) },
                    onRecordsOnly = { introCode = null; app.chooseRecordsOnly(); nav.reset(Route.Home) },
                )
            }
            composable<Route.Connect> {
                // 탭(연결 안 됨)에서 열었으면 '취소'로 돌아간다(U39). 첫 실행 흐름에서는 소개로 돌아가는 뒤로 가기만 있다.
                val fromTabs = nav.previousBackStackEntry?.destination?.hasRoute<Route.Home>() == true
                ConnectScreen(
                    onCode = { code -> nav.navigate(Route.Confirm(code)) },
                    onPaired = { role -> nav.reset(Route.Done(role.name)) },
                    onCancel = if (fromTabs) ({ nav.popBackStack() }) else null,
                )
            }
            composable<Route.Confirm> { e ->
                ConfirmScreen(
                    e.toRoute<Route.Confirm>().code,
                    onDone = { role -> nav.reset(Route.Done(role.name)) },
                    onRetry = {
                        app.clearPairRun()
                        // 밑에 연결 화면이 있으면 그리로 돌아간다(탭에서 열었으면 '취소'가 남는다, U39). 링크로 바로 왔으면 확인 화면을 바꾼다.
                        if (!nav.popBackStack<Route.Connect>(inclusive = false)) nav.navigate(Route.Connect) { popUpTo(e.destination.id) { inclusive = true } }
                    },
                )
            }
            composable<Route.Done> { e ->
                val role = if (e.toRoute<Route.Done>().role == Role.GUARDIAN.name) Role.GUARDIAN else Role.OWNER
                DoneScreen(role) { if (app.pairDoneShown()) nav.reset(Route.Home) else toast(SAVE_FAILED) }
            }
            // U25 a: 차·중계 쪽에서 끊김. 다시 연결하거나 탭(연결 안 됨)에서 기록을 계속 쓴다.
            composable<Route.Gone> {
                // 고른 결과를 저장하지 못했으면 이 화면에 남고 알린다(다음 실행에 같은 선택을 다시 묻지 않게 다시 누르게).
                val recordsOnly = { if (app.acknowledgeGone(recordsOnly = true)) nav.reset(Route.Home) else toast(SAVE_FAILED) }
                BackHandler { recordsOnly() } // 이력이 끊김 화면 하나라 그냥 두면 앱이 끝난다
                GoneScreen(
                    onReconnect = { if (app.acknowledgeGone()) nav.reset(Route.Connect) else toast(SAVE_FAILED) },
                    onRecordsOnly = recordsOnly,
                )
            }
            composable<Route.Home> { CareTabs(go = { nav.navigate(it) }) }
            composable<Route.Timer> { TimerScreen(onBack = { nav.popBackStack() }) }
            composable<Route.About> { AboutScreen({ nav.popBackStack() }) { nav.navigate(Route.License(it)) } }
            composable<Route.License> { e -> LicenseScreen(e.toRoute<Route.License>().index) { nav.popBackStack() } }
            composable<Route.Songs> { SongsScreen { nav.popBackStack() } }
            composable<Route.Reports> { ReportsScreen(onBack = { nav.popBackStack() }, open = { nav.navigate(Route.Report(it)) }) }
            composable<Route.Report> { ReportDetailScreen(onBack = { nav.popBackStack() }) }
            composable<Route.Guardians> { GuardiansScreen(onBack = { nav.popBackStack() }) }
            composable<Route.Alert> { AlertScreen(onBack = { nav.popBackStack() }) }
        }
        // 앱 안 배너(5.6): 앞에 있을 때 온 인계 알림. 시스템 알림은 띄우지 않는다. 8초 뒤 저절로 닫히고(AppViewModel), 글은 3줄에서 자른다(전문은 인계 알림 목록).
        ui.banner?.let { b ->
            Card(Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopCenter).semantics { liveRegion = LiveRegionMode.Polite }) {
                Column(Modifier.padding(16.dp)) {
                    Text("인계 알림", style = MaterialTheme.typography.labelLarge)
                    Text(b.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row {
                        TextButton(onClick = { app.dismissBanner(); nav.navigate(Route.Alert(b.alertId)) }) { Text("보기") }
                        TextButton(onClick = app::dismissBanner) { Text("닫기") }
                    }
                }
            }
        }
    }
}
