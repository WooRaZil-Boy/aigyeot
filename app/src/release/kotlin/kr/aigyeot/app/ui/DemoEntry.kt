package kr.aigyeot.app.ui

import androidx.compose.runtime.Composable
import kr.aigyeot.app.host.Graph

/** release 빌드에는 시연·측정이 없다(장면 재생기·화면·장면 파일은 debug 소스셋). 저장된 경로는 [Route.parse]가 홈으로 보낸다. */
internal object DemoEntry {
    @Suppress("ktlint:standard:property-naming")
    const val available = false

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun Screen(g: Graph, onBack: () -> Unit) {}
}
