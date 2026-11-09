package kr.aigyeot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 홈(정차, 명세서 11장). 상태를 그리기만 하고, 바꾸려면 이벤트 람다를 부른다. */
@Composable
fun HomeScreen(
    ui: HomeUiState,
    onBabyAboard: () -> Unit, onFeedingNow: () -> Unit, onAsk: () -> Unit, onFeedingConfirm: (Boolean) -> Unit,
    go: (Route) -> Unit,
) = Page("아이곁", onBack = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui.status, style = MaterialTheme.typography.bodyLarge)
            Body(ui.micText)
            if (ui.ambientDimmed) Body("조명 낮춤")
            if (ui.pausesWarning) Body("입력이 끊겨 판정을 잠시 멈췄어요", MaterialTheme.colorScheme.error)
            Body(ui.feeding)
            LinkLines(ui.link)   // contracts-link 8.6 정차 화면 표시
        }
    }
    if (ui.confirmShown) {
        Body("수유하셨어요?")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { BigButton("네") { onFeedingConfirm(true) } }
            Box(Modifier.weight(1f)) { BigButton("아니요", primary = false) { onFeedingConfirm(false) } }
        }
    }
    BigButton("아기 탔어요", onClick = onBabyAboard)
    BigButton("지금 먹이기 시작했어요", onClick = onFeedingNow)
    // ④ 물어보기: 기어 P이고 마이크가 켜져 있을 때(결정 D1). C303 ⑤: 판단 코어가 AskButton을 받는 조건 그대로
    BigButton("물어보기", enabled = ui.askEnabled, onClick = onAsk)
    Label("방법별 성적")
    ui.stats.forEach { Body(it) }
    Body("그 소리를 틀었을 때 그친 횟수예요. 소리 덕분인지는 알 수 없어요")
    listOf(Route.Connect to "휴대폰 연결", Route.Songs to "부모 노래", Route.Reports to "주행 리포트", Route.Setup to "아기 설정", Route.Demo to "시연·측정")
        .forEach { (r, t) -> BigButton(t, primary = false) { go(r) } }
}
