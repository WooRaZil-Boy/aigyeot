package kr.aigyeot.app.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kr.aigyeot.app.scenario.Scenario
import kr.aigyeot.app.scenario.ScenarioScreen

/** 시연·측정(F18): 장면 화면으로 보내기와 모든 기록 지우기(C40). 장면이 도는 동안은 지우지 않는다(실제 상태가 보관 중이다). */
@Composable
fun DemoScreen(scenario: Scenario, onClear: () -> Unit, onBack: () -> Unit) {
    var scenes by rememberSaveable { mutableStateOf(false) }
    if (scenes) return ScenarioScreen(scenario) { scenes = false }
    val running = scenario.progress.collectAsState().value != null
    var ask by rememberSaveable { mutableStateOf(false) }
    Page("시연·측정", onBack) {
        BigButton("장면 고르기·실행") { scenes = true }
        Body("프로필·연결·노래는 남기고 주행 기록과 성적을 모두 지워요. 휴대폰 중계에 남은 기록도 지워요")
        BigButton("모든 기록 지우기", enabled = !running, primary = false) { ask = true }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("모든 기록을 지울까요?") },
        text = { Body("지운 기록은 되돌릴 수 없어요") },
        confirmButton = { TextButton({ ask = false; onClear() }, Modifier.heightIn(min = Touch)) { Text("지우기") } },
        dismissButton = { TextButton({ ask = false }, Modifier.heightIn(min = Touch)) { Text("그만두기") } },
    )
}
