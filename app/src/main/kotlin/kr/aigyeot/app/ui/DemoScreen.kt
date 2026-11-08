package kr.aigyeot.app.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import kr.aigyeot.app.host.EventSink
import kr.aigyeot.app.scenario.Scenario
import kr.aigyeot.engine.ClearRecords

/**
 * 시연·측정 화면 상태: 장면이 도는지(도는 동안은 지우지 않는다)와 기록 지우기 사건. 기록 지우기는 장면 단계가 아니라 사용자가 실제 기록에 하는 일이라
 * 실제 입구([events], V3)로 보낸다(장면 중이면 버튼이 꺼져 있고, 호스트도 장면 중 ClearRecords를 받지 않는다).
 */
class DemoViewModel(scenario: Scenario, private val events: EventSink) : ViewModel() {
    val running: StateFlow<Boolean> = scenario.running

    fun clear() = events.post(ClearRecords(0))
}

/** 시연·측정(F18): 장면 화면으로 보내기와 모든 기록 지우기(C40). 장면이 도는 동안은 지우지 않는다(실제 상태가 보관 중이다). */
@Composable
fun DemoScreen(running: Boolean, onScenes: () -> Unit, onClear: () -> Unit, onBack: () -> Unit) {
    var ask by rememberSaveable { mutableStateOf(false) }
    Page("시연·측정", onBack) {
        BigButton("장면 고르기·실행", onClick = onScenes)
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
