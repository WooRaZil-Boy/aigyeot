package kr.aigyeot.app.scenario

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.aigyeot.app.host.HostApi
import kr.aigyeot.app.link.Link

/** 장면 재생기. scenario가 채운다. 공개 모양은 고치지 않는다(handoff README 4장). */
class Scenario(
    private val context: Context,
    private val host: HostApi,
    private val link: Link,
) {
    val progress: StateFlow<ScenarioProgress?> = MutableStateFlow(null)
    fun ids(): List<String> = emptyList()          // assets/scenario + 흘려 넣은 장면
    fun run(id: String, repeat: Int? = null) {}
    fun stop() {}
}

data class ScenarioProgress(val id: String, val run: Int, val of: Int, val t: Double, val failedStep: Double?, val note: String?)

@Composable fun ScenarioScreen(scenario: Scenario, onBack: () -> Unit) {}   // 시연·측정 화면의 장면 부분(ui가 이 화면으로 보낸다)
