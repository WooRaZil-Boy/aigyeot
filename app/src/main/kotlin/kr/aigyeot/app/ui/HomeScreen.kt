package kr.aigyeot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kr.aigyeot.app.host.Graph
import kr.aigyeot.app.link.LinkStatus
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.FirstTry
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Phase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 홈(정차, 명세서 11장). 판단 코어 상태를 읽기만 하고, 바꾸려면 사건을 보낸다. */
@Composable
fun HomeScreen(
    s: EngineState?, now: Long,
    onBabyAboard: () -> Unit, onFeedingNow: () -> Unit, onAsk: () -> Unit, onFeedingConfirm: (Boolean) -> Unit,
    go: (String) -> Unit,
    link: LinkStatus = graphLinkStatus(),
) = Page("아이곁", onBack = null) {
    Card(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(statusText(s), style = MaterialTheme.typography.bodyLarge)
            Body(if (s?.car?.micRunning == true) "듣기 켜짐" else "듣기 꺼짐")
            if (!s?.session?.pauses.isNullOrEmpty()) Body("입력이 끊겨 판정을 잠시 멈췄어요", MaterialTheme.colorScheme.error)
            Body(feedingText(s, now))
            LinkLines(link)   // contracts-link 8.6 정차 화면 표시
        }
    }
    if (s?.session?.nursingStop?.confirmShown == true) {
        Body("수유하셨어요?")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { BigButton("네") { onFeedingConfirm(true) } }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { BigButton("아니요", primary = false) { onFeedingConfirm(false) } }
        }
    }
    BigButton("아기 탔어요", onClick = onBabyAboard)
    BigButton("지금 먹이기 시작했어요", onClick = onFeedingNow)
    // ④ 물어보기: 기어 P이고 마이크가 켜져 있을 때(결정 D1)
    BigButton("물어보기", enabled = s?.car?.gear == Gear.P && s.car.micRunning, onClick = onAsk)
    Label("방법별 성적")
    stats(s?.history.orEmpty(), s?.methodsNext.orEmpty(), now).forEach { Body(it) }
    Body("그 소리를 틀었을 때 그친 횟수예요. 소리 덕분인지는 알 수 없어요")
    listOf("connect" to "휴대폰 연결", "songs" to "부모 노래", "report" to "주행 리포트", "setup" to "아기 설정", "demo" to "시연·측정")
        .forEach { (k, t) -> BigButton(t, primary = false) { go(k) } }
}

/** 프로세스에 하나인 link의 연결 상태(Graph). 화면 진입점이 넘기지 않으면 여기서 읽는다. */
@Composable
internal fun graphLinkStatus(): LinkStatus {
    val ctx = LocalContext.current
    return remember { Graph.get(ctx).link.status }.collectAsState().value
}

private fun statusText(s: EngineState?): String {
    val x = s?.session ?: return if (s?.ign?.reserved == true) "출발하면 듣기 시작해요" else "출발 전이에요"
    return when (x.phase) {
        Phase.LISTENING -> "아기 소리를 듣고 있어요"
        Phase.ATTEMPT -> "뒷자리에서 소리를 틀고 있어요"
        Phase.GAP, Phase.CONFIRM -> "그쳤는지 보고 있어요"
        Phase.HANDOFF -> "쉬어 가기를 안내했어요"
        Phase.WAIT -> "잠시 기다리고 있어요"
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

private fun feedingText(s: EngineState?, now: Long): String {
    val f = s?.feedingStart ?: return "마지막 수유: 기록 없음"
    val min = ((now - f) / 60_000).coerceAtLeast(0)
    return "마지막 수유 ${Instant.ofEpochMilli(f).atZone(ZoneId.systemDefault()).format(HM)} (${min / 60}시간 ${min % 60}분 전)"
}

/**
 * "자장가 7번 중 6번". 최근 28일 1차 시도만 센다. 3번 미만이면 "아직 모름"(명세서 5장).
 * 부모 노래는 지금 후보 ③과 같은 곡의 시도만 센다(곡이 바뀌면 새로 센다). ③이 없으면 줄을 뺀다.
 */
internal fun stats(h: List<FirstTry>, methods: List<Method>, now: Long): List<String> {
    val recent = h.filter { it.at >= now - 28 * 86_400_000L }
    val third = methods.firstOrNull { it.kind == MethodKind.SONG }
    return listOf(MethodKind.WHITE_NOISE, MethodKind.LULLABY, MethodKind.SONG).mapNotNull { k ->
        if (k == MethodKind.SONG && third == null) return@mapNotNull null
        val r = recent.filter { it.method.kind == k && (k != MethodKind.SONG || it.method.songId == third?.songId) }
        "${label(k)}: " + if (r.size < 3) "아직 모름" else "${r.size}번 중 ${r.count { it.success }}번"
    }
}

fun label(k: MethodKind) = when (k) { MethodKind.WHITE_NOISE -> "백색소음"; MethodKind.LULLABY -> "자장가"; MethodKind.SONG -> "부모 노래" }
