package kr.aigyeot.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kr.aigyeot.app.host.Graph

/** 정차 화면 목적지. 화면 회전에서 남도록 [key] 문자열로 저장한다. */
sealed interface Route {
    val key: String

    /** 화면 입력 보관 열쇠: 같은 화면이면 같다(리포트 목록과 리포트 하나는 한 화면이라, 넓은 창에서 다른 리포트를 골라도 목록 스크롤이 남는다). */
    val screen: String get() = key
    data object Home : Route {
        override val key = "home"
    }
    data object Setup : Route {
        override val key = "setup"
    }
    data object Connect : Route {
        override val key = "connect"
    }
    data object Songs : Route {
        override val key = "songs"
    }
    data object Reports : Route {
        override val key = "report"
    }
    data class Report(val id: String) : Route {
        override val key get() = "report:$id"
        override val screen get() = Reports.key
    }
    data object Demo : Route {
        override val key = "demo"
    }
    data object DemoScenes : Route {
        override val key = "demo:scenes"
    }

    companion object {
        fun parse(key: String): Route = when {
            key.startsWith("report:") -> Report(key.removePrefix("report:"))
            else -> listOf(Home, Setup, Connect, Songs, Reports, Demo, DemoScenes).firstOrNull { it.key == key }
                ?.takeIf { DemoEntry.available || (it != Demo && it != DemoScenes) } ?: Home // release에서 저장된 시연 경로는 홈으로
        }
        val saver = Saver<Route, String>(save = { it.key }, restore = ::parse)
    }
}

fun appViewModelFactory(g: Graph, ctx: Context) = viewModelFactory {
    val app = ctx.applicationContext
    initializer { AppViewModel(g.store, g.events, app) { noRearOutput(app) } }
}

private fun homeFactory(g: Graph) = viewModelFactory {
    initializer { HomeViewModel(g.host.steps, g.events, g.link, g.clock, g.exec.said, g.exec.heard, g.store) }
}

/** 시스템 뒤로의 위 화면(설정 안의 노래·시연은 설정으로, 나머지는 홈). */
internal fun parentOf(r: Route): Route = when (r) {
    Route.Songs, Route.Demo, Route.DemoScenes -> Route.Setup
    else -> Route.Home
}

/**
 * 화면을 옮길 때 버릴 화면 입력의 열쇠([Route.screen]). 갈 화면과 그 위 화면([parentOf] 사슬)만 남긴다: 저장하지 않고 나간 설정 입력이 다시 열 때 저장된 값처럼 보이지 않게.
 * 설정 → 노래·시연(아래 화면)은 설정 입력을 남겨 돌아오면 이어 쓴다. 주행 중 화면 전환은 route를 바꾸지 않아 여기를 지나지 않는다(입력이 남는다).
 */
internal fun keysToDrop(seen: Set<String>, to: Route): Set<String> =
    seen - generateSequence(to) { r -> parentOf(r).takeIf { it != r } }.map { it.screen }.toSet()

/**
 * 화면 이동과 화면별 ViewModel 연결. 마이크 시작·권한은 액티비티 몫이라 [onBabyAboard]로 받는다.
 * [drive](CarUxRestrictions가 주행 최적화를 요구하거나 값을 아직 모름)면 주행 중 화면 하나만 그린다: 조작·이동 없음, 정차 경로는 그대로 둔다.
 * 최상단에서 Back은 [onExit](moveTaskToBack), 아니면 위 화면으로.
 */
@Composable
fun AppRoot(g: Graph, app: AppViewModel, drive: Boolean, onBabyAboard: () -> Unit, onExit: () -> Unit) {
    var route by rememberSaveable(stateSaver = Route.saver) { mutableStateOf<Route>(Route.Home) }
    // 주행 중 화면으로 바뀌는 동안 정차 화면이 컴포지션에서 빠져도 그 화면의 입력(설정 양식·장면 고름·반복)을 남긴다(풀리면 그대로 돌아온다)
    val saved = rememberSaveableStateHolder()
    val seen = remember { mutableSetOf<String>() }
    // 화면 이동의 입구 하나: 갈 화면 사슬 밖의 입력을 버리고 옮긴다
    val move = { to: Route ->
        keysToDrop(seen, to).forEach {
            saved.removeState(it)
            seen -= it
        }
        route = to
    }
    if (drive) {
        BackHandler { onExit() }
        val vm = viewModel<HomeViewModel>(factory = homeFactory(g))
        return HomeScreen(
            vm.uiState.collectAsStateWithLifecycle().value,
            drive = true,
            onBabyAboard = {},
            onFeedingNow = {},
            onAsk = {},
            onFeedingConfirm = {},
            go = {}
        )
    }
    val profile = app.profile.collectAsStateWithLifecycle().value as? ProfileState.Loaded ?: return
    val home = { move(Route.Home) }
    BackHandler { if (route == Route.Home || profile.needsSetup) onExit() else move(parentOf(route)) }
    if (profile.needsSetup) {
        return saved.SaveableStateProvider("first-setup") {
            SetupScreen(
                profile.profile,
                g.clock,
                profile.noRearOutput,
                null
            ) { app.saveProfile(it) }
        }
    }
    seen += route.screen
    saved.SaveableStateProvider(route.screen) { ParkRoute(g, app, route, profile, home, onBabyAboard, move) }
}

@Composable
private fun ParkRoute(
    g: Graph,
    app: AppViewModel,
    route: Route,
    profile: ProfileState.Loaded,
    home: () -> Unit,
    onBabyAboard: () -> Unit,
    go: (Route) -> Unit
) {
    when (val r = route) {
        Route.Home -> {
            val vm = viewModel<HomeViewModel>(factory = homeFactory(g))
            HomeScreen(
                vm.uiState.collectAsStateWithLifecycle().value,
                drive = false,
                onBabyAboard = onBabyAboard,
                onFeedingNow = vm::feedingNow,
                onAsk = vm::ask,
                onFeedingConfirm = app::feedingConfirm,
                go = { go(it) }
            )
        }
        Route.Setup -> {
            val inScene = g.host.activeScene.collectAsStateWithLifecycle().value != null // release에는 장면이 없어 늘 false
            SetupScreen(
                profile.profile,
                g.clock,
                profile.noRearOutput,
                home,
                onSongs = { go(Route.Songs) },
                onDemo = if (DemoEntry.available) ({ go(Route.Demo) }) else null,
                onClear = app::clearRecords,
                clearEnabled = !inScene
            ) {
                app.saveProfile(it)
                home()
            }
        }
        Route.Connect -> {
            val vm = viewModel<ConnectViewModel>(factory = viewModelFactory { initializer { ConnectViewModel(g.link, g.clock) } })
            ConnectScreen(vm.uiState.collectAsStateWithLifecycle().value, vm::open, vm::close, vm::disconnect, vm::refreshPhones, home)
        }
        Route.Songs -> {
            val vm = viewModel<SongsViewModel>(factory = viewModelFactory { initializer { SongsViewModel(g.store, g.host, g.link) } })
            SongsScreen(vm.rows.collectAsStateWithLifecycle().value, vm::select) { go(Route.Setup) } // 설정 안에서 들어온다
        }
        Route.Reports, is Route.Report -> { // 넓은 창은 목록 + 상세 한 화면, 840 이하는 목록 → 상세
            val vm = viewModel<ReportListViewModel>(factory = viewModelFactory { initializer { ReportListViewModel(g.store) } })
            ReportsScreen(vm.rows.collectAsStateWithLifecycle().value, (r as? Route.Report)?.id, detail = { id ->
                viewModel<ReportDetailViewModel>(
                    key = Route.Report(id).key,
                    factory = viewModelFactory { initializer { ReportDetailViewModel(g.store, g.clock, id) } }
                )
                    .uiState.collectAsStateWithLifecycle().value
            }, onOpen = { go(Route.Report(it)) }, onBack = { go(if (it) Route.Reports else Route.Home) })
        }
        Route.Demo, Route.DemoScenes -> DemoEntry.Screen(g) { go(Route.Setup) } // 장면 고르기는 시연·측정 화면 안으로 합쳤다. DemoScenes는 저장된 경로를 위해 남긴다. release에는 없다
    }
}
