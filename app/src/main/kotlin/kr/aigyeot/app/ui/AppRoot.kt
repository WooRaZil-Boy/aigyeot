package kr.aigyeot.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kr.aigyeot.app.host.Graph
import kr.aigyeot.app.scenario.ScenarioScreen

/** 정차 화면 목적지. 화면 회전에서 남도록 [key] 문자열로 저장한다. */
sealed interface Route {
    val key: String
    data object Home : Route { override val key = "home" }
    data object Setup : Route { override val key = "setup" }
    data object Connect : Route { override val key = "connect" }
    data object Songs : Route { override val key = "songs" }
    data object Reports : Route { override val key = "report" }
    data class Report(val id: String) : Route { override val key get() = "report:$id" }
    data object Demo : Route { override val key = "demo" }
    data object DemoScenes : Route { override val key = "demo:scenes" }

    companion object {
        fun parse(key: String): Route = when {
            key.startsWith("report:") -> Report(key.removePrefix("report:"))
            else -> listOf(Home, Setup, Connect, Songs, Reports, Demo, DemoScenes).firstOrNull { it.key == key } ?: Home
        }
        val saver = Saver<Route, String>(save = { it.key }, restore = ::parse)
    }
}

fun appViewModelFactory(g: Graph, ctx: Context) = viewModelFactory {
    val app = ctx.applicationContext
    initializer { AppViewModel(g.store, g.events, app) { noRearOutput(app) } }
}

/**
 * 화면 이동과 화면별 ViewModel 연결(주행 제한이 풀린 정차 상태에서만 그린다). 마이크 시작·권한은 액티비티 몫이라 [onBabyAboard]로 받는다.
 * 최상단에서 Back은 [onExit](moveTaskToBack), 아니면 홈으로.
 */
@Composable
fun AppRoot(g: Graph, app: AppViewModel, onBabyAboard: () -> Unit, onExit: () -> Unit) {
    val profile = app.profile.collectAsState().value as? ProfileState.Loaded ?: return
    var route by rememberSaveable(stateSaver = Route.saver) { mutableStateOf<Route>(Route.Home) }
    val home = { route = Route.Home }
    BackHandler { if (route == Route.Home || profile.needsSetup) onExit() else home() }
    if (profile.needsSetup) return SetupScreen(profile.profile, g.clock, profile.noRearOutput, null) { app.saveProfile(it) }
    when (val r = route) {
        Route.Home -> {
            val vm = viewModel<HomeViewModel>(factory = viewModelFactory { initializer { HomeViewModel(g.host.steps, g.events, g.link, g.clock) } })
            HomeScreen(
                vm.uiState.collectAsState().value,
                onBabyAboard = onBabyAboard, onFeedingNow = vm::feedingNow, onAsk = vm::ask, onFeedingConfirm = app::feedingConfirm,
                go = { route = it },
            )
        }
        Route.Setup -> SetupScreen(profile.profile, g.clock, profile.noRearOutput, home) { app.saveProfile(it); home() }
        Route.Connect -> {
            val vm = viewModel<ConnectViewModel>(factory = viewModelFactory { initializer { ConnectViewModel(g.link, g.clock) } })
            ConnectScreen(vm.uiState.collectAsState().value, vm::open, vm::close, vm::disconnect, vm::refreshPhones, home)
        }
        Route.Songs -> {
            val vm = viewModel<SongsViewModel>(factory = viewModelFactory { initializer { SongsViewModel(g.store, g.host, g.link) } })
            SongsScreen(vm.rows.collectAsState().value, vm::select, home)
        }
        Route.Reports -> {
            val vm = viewModel<ReportListViewModel>(factory = viewModelFactory { initializer { ReportListViewModel(g.store) } })
            ReportListScreen(vm.rows.collectAsState().value, { route = Route.Report(it) }, home)
        }
        is Route.Report -> {
            val vm = viewModel<ReportDetailViewModel>(key = r.key, factory = viewModelFactory { initializer { ReportDetailViewModel(g.store, g.clock, r.id) } })
            ReportDetailScreen(vm.uiState.collectAsState().value) { route = Route.Reports }
        }
        Route.Demo -> {
            val vm = viewModel<DemoViewModel>(factory = viewModelFactory { initializer { DemoViewModel(g.scenario, g.events) } })
            DemoScreen(vm.running.collectAsState().value, { route = Route.DemoScenes }, vm::clear, home)
        }
        Route.DemoScenes -> ScenarioScreen(g.scenario) { route = Route.Demo }
    }
}
