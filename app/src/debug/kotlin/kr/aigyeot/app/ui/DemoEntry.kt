package kr.aigyeot.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kr.aigyeot.app.host.Graph
import kr.aigyeot.app.scenario.Scenario

/** debug 빌드의 시연·측정 진입점. release에는 같은 이름의 빈 짝이 있다(src/release). */
internal object DemoEntry {
    const val available = true

    // 장면 재생기는 앱 프로세스에 하나: 처음 쓸 때 만들어 붙든다
    @Volatile private var scenario: Scenario? = null
    private fun scenario(g: Graph, ctx: android.content.Context): Scenario =
        scenario ?: synchronized(this) { scenario ?: Scenario(ctx.applicationContext, g.host, g.link).also { scenario = it } }

    @Composable
    fun Screen(g: Graph, onBack: () -> Unit) {
        val ctx = LocalContext.current
        val vm = viewModel<DemoViewModel>(factory = viewModelFactory { initializer { DemoViewModel(scenario(g, ctx)) } })
        DemoScreen(vm.uiState.collectAsStateWithLifecycle().value, vm.scenes.collectAsStateWithLifecycle().value, vm::run, vm::stop, vm::checkVc, onBack)   // 화면이 안 보이면 CPU·메모리 재기를 멈춘다
    }
}
