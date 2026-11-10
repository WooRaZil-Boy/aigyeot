package kr.aigyeot.phone.presentation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kr.aigyeot.phone.R
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
    /** 노래 녹음(시안 S.song). `replacing`은 다시 녹음해 바꿀 곡(9장 '노래 거절'). */
    @Serializable data class Record(val replacing: String? = null) : Route
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
            title = { Text("연결 정보를 읽지 못했어요", style = Type.headline) },
            text = { Text("이 휴대폰의 연결 정보를 지우고 새로 연결할까요? 돌봄 기록은 그대로 남아요. 전에 연결했던 이 휴대폰은 차 화면에서 지워 주세요.") },
            confirmButton = { TextButton(onClick = { app.resetAndPair(r.code) }) { Text("지우고 연결하기") } },
            dismissButton = { TextButton(onClick = app::clearPairRun) { Text("취소") } },
            shape = Radius.dialog,
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

    // 탭 고르기는 여기서 든다: 리포트·노래 녹음처럼 탭 밖 화면도 시안대로 아래 탭 막대를 보이고, 누르면 그 탭으로 돌아간다.
    var homeTab by rememberSaveable { mutableStateOf("오늘") }
    val guardian = (ui.connection as? Connection.Paired)?.creds?.role == Role.GUARDIAN
    fun toTab(t: String) {
        homeTab = t
        if (!nav.popBackStack<Route.Home>(inclusive = false)) nav.reset(Route.Home)
    }
    val reduced = LocalReducedMotion.current
    // 아래 시스템 막대·자판 자리는 화면마다 맡는다(탭 막대는 흰 막대가 그 자리까지 깔린다, 시안 v5).
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
      // 진행 중 판 → 타이머 고리 공유 요소(README 4.7 spring.smooth). 화면 교체는 base 220 페이드, 동작 줄이기면 바로.
      SharedTransitionLayout {
       CompositionLocalProvider(LocalSharedScope provides this) {
        NavHost(
            nav,
            startDestination = start,
            enterTransition = { if (reduced) EnterTransition.None else fadeIn(Motion.base.spec()) },
            exitTransition = { if (reduced) ExitTransition.None else fadeOut(Motion.exit.spec()) },
            popEnterTransition = { if (reduced) EnterTransition.None else fadeIn(Motion.base.spec()) },
            popExitTransition = { if (reduced) ExitTransition.None else fadeOut(Motion.exit.spec()) },
        ) {
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
            composable<Route.Home> { NavScope(this) { CareTabs(go = { nav.navigate(it) }, tab = homeTab, onTab = { homeTab = it }) } }
            composable<Route.Timer> { NavScope(this) { TimerScreen(onBack = { nav.popBackStack() }) } }
            composable<Route.About> { AboutScreen({ nav.popBackStack() }) { nav.navigate(Route.License(it)) } }
            composable<Route.License> { e -> LicenseScreen(e.toRoute<Route.License>().index) { nav.popBackStack() } }
            // 시안 S.song·S.report: 탭 밖 화면이지만 아래 탭 막대가 있다(노래·리포트는 '차', guardian 리포트는 '오늘').
            composable<Route.Record> { e -> WithTabs(guardian, "차", ::toTab) { RecordScreen(e.toRoute<Route.Record>().replacing, onBack = { nav.popBackStack() }) } }
            composable<Route.Reports> { WithTabs(guardian, if (guardian) "오늘" else "차", ::toTab) { ReportsScreen(onBack = { nav.popBackStack() }, open = { nav.navigate(Route.Report(it)) }) } }
            composable<Route.Report> { WithTabs(guardian, if (guardian) "오늘" else "차", ::toTab) { ReportDetailScreen(onBack = { nav.popBackStack() }) } }
            composable<Route.Guardians> { GuardiansScreen(onBack = { nav.popBackStack() }) }
            composable<Route.Alert> { AlertScreen(onBack = { nav.popBackStack() }) }
        }
       }
      }
        // 앱 안 배너(5.6, 시안 .banner .anoti): 앞에 있을 때 온 인계 알림. 시스템 알림은 띄우지 않는다. 8초 뒤 저절로 닫히고(AppViewModel),
        // 글은 3줄에서 자른다(전문은 인계 알림 목록). 위에서 떨어지듯 들어온다(spring bouncy).
        AnimatedVisibility(
            visible = ui.banner != null,
            modifier = Modifier.align(Alignment.TopCenter),
            // 시안 .banner: 위 140%에서 0.96배·투명으로 spring.bouncy, 나갈 때 위로 260ms motion.exit 곡선(iOS와 같음).
            enter = if (reduced) EnterTransition.None else slideInVertically(Springs.bouncy.spec()) { -(it * 1.4f).toInt() } +
                scaleIn(Springs.bouncy.spec(), initialScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f)) + fadeIn(Springs.bouncy.spec()),
            exit = if (reduced) ExitTransition.None else slideOutVertically(Motion.bannerOut.spec()) { -(it * 1.4f).toInt() } +
                fadeOut(Motion.bannerOut.spec()),
        ) {
            var last by remember { mutableStateOf(ui.banner) }
            ui.banner?.let { last = it }
            last?.let { b -> HandoffBanner(b.text, onOpen = { app.dismissBanner(); nav.navigate(Route.Alert(b.alertId)) }, onClose = app::dismissBanner) }
        }
    }
}

/**
 * 인계 알림 배너(시안 .anoti): 흰 카드(모서리 26), 머리 '● 아이곁 · 지금'(브랜드 원 안 흰 표지), 제목 '인계 알림', 글 3줄, '보기'·'닫기'.
 */
/** 앱 안 배너 모서리(시안 .anoti 26). */
private val BannerShape = RoundedCornerShape(26.dp)

@Composable
private fun HandoffBanner(text: String, onOpen: () -> Unit, onClose: () -> Unit) {
    val p = Tokens.colors
    Column(
        Modifier.fillMaxWidth().padding(8.dp)
            .shadow(16.dp, BannerShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(BannerShape).background(p.surface).padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
                AIcon(R.drawable.ic_stat_aigyeot, Modifier.size(16.dp), Color.White)
            }
            Text("  아이곁 · 지금", style = Type.footNote, color = p.textSecondary)
        }
        Text("인계 알림", Modifier.padding(top = 8.dp), style = Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold), color = p.textPrimary)
        Text(text, style = Type.label, color = p.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BannerAction("보기", onOpen)
            BannerAction("닫기", onClose)
        }
    }
}

@Composable
private fun BannerAction(text: String, onClick: () -> Unit) {
    val p = Tokens.colors
    Box(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(18.dp)).background(p.textPrimary.copy(alpha = 0.07f)).pressable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), color = p.textPrimary) }
}
