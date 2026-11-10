package kr.aigyeot.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kr.aigyeot.app.R
import kr.aigyeot.app.ui.car3d.CarView
import kr.aigyeot.app.ui.car3d.Light3d
import kr.aigyeot.app.ui.car3d.Stage3d
import kr.aigyeot.app.ui.car3d.View3d
import kr.aigyeot.shared.CarActionKind

// 주행 리포트(시안 car.js reports(): 왼쪽 목록 560 + 오른쪽 상세, 창 폭 840 이하면 목록과 상세를 한 줄로 따로).
// 상세 순서: 날짜·구간 → 숫자 줄 → 에피소드 띠 → 차가 대신 한 일 | 평소 대비 조향 → 방법별 성적 → 자세히(시안에 자리가 없는 사실).
// 화면은 ReportView를 그리기만 한다(계산은 ReportViewModels.kt).

/**
 * 리포트 화면 하나. [openId]: 고른 리포트(넓은 창에서 없으면 열 수 있는 첫 줄). 좁은 창(840 이하)에서는 [openId]가 있으면 상세만, 없으면 목록만.
 * [detail]은 리포트 id의 상세 상태(ViewModel은 부르는 쪽이 만든다). [onBack]의 인자는 목록으로 돌아갈지(좁은 창 상세에서만 참).
 */
@Composable
fun ReportsScreen(
    rows: List<ReportRow>, openId: String?, detail: @Composable (String) -> ReportDetailUiState,
    onOpen: (String) -> Unit, onBack: (toList: Boolean) -> Unit,
) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val narrow = maxWidth < NarrowPage   // 목록 560 + 상세 두 칸이 들어가는 폭(설정·시연과 같은 기준)이 아니면 목록과 상세를 따로
    val detailOnly = narrow && openId != null
    // 목록 스크롤은 화면 맨 위에서 하나: 840 이하에서 상세를 보고 돌아와도, 넓은 창에서 다른 리포트를 골라도 그대로(목록이 컴포지션에서 빠져도 남는다)
    val listState = rememberLazyListState()
    BackHandler(enabled = detailOnly) { onBack(true) }
    Column(Modifier.fillMaxSize()) {
        Header("주행 리포트", { onBack(detailOnly) })
        when {
            detailOnly -> key(openId) { Detail(detail(openId!!), narrow = true, Modifier.fillMaxSize()) }
            narrow -> ReportList(rows, null, onOpen, listState, Modifier.fillMaxSize())
            else -> Row(Modifier.fillMaxSize()) {
                val sel = openId ?: rows.firstOrNull { it.enabled }?.id
                ReportList(rows, sel, onOpen, listState, Modifier.width(560.dp + 44.dp + 18.dp).fillMaxHeight(), PaddingValues(start = 44.dp, end = 18.dp, top = 32.dp, bottom = 40.dp))
                if (sel != null) key(sel) { Detail(detail(sel), narrow = false, Modifier.weight(1f).fillMaxHeight(), PaddingValues(start = 18.dp, end = 44.dp, top = 32.dp, bottom = 40.dp)) }
            }
        }
    }
}

/** 차가 한 일 | 조향 두 칸이 들어가는 상세 폭: 칸마다 안쪽 여백 64 + 차 그림 200 + 간격 20 + 글자 200, 칸 사이 28(시안 창 1706의 상세 약 1022는 두 칸). */
private val TwoCards = (64 + 200 + 20 + 200).dp * 2 + 28.dp

private val PagePad = PaddingValues(start = 44.dp, end = 44.dp, top = 32.dp, bottom = 40.dp)

/** 목록(시안 왼쪽): '주행' 제목 + 행(아이콘 route, '{날짜} {구간}', 부가 '울음 n번'). 열린 세션은 '주행 중'이라 열 수 없다. */
@Composable
private fun ReportList(rows: List<ReportRow>, selected: String?, onOpen: (String) -> Unit, state: LazyListState, modifier: Modifier, pad: PaddingValues = PagePad) {
    val p = LocalPalette.current
    LazyColumn(modifier, state = state, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Sect("주행") }
        if (rows.isEmpty()) item { Body("아직 기록이 없어요") }
        items(rows, key = { it.id }) { r ->
            ListRow(r.title, r.sub.ifEmpty { null }, R.drawable.ic_route, selected = r.id == selected, enabled = r.enabled, onClick = { onOpen(r.id) }) {
                if (r.enabled) LineIcon(R.drawable.ic_chevron_right, 36.dp, p.textTertiary)
            }
        }
    }
}

/** 상세(시안 오른쪽 .detail, 칸 사이 22). 좁은 창이면 두 칸(차가 한 일 | 조향)도 세로로 쌓는다. */
@Composable
private fun Detail(ui: ReportDetailUiState, narrow: Boolean, modifier: Modifier, pad: PaddingValues = PagePad) {
    val p = LocalPalette.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        val v = (ui as? ReportDetailUiState.Loaded)?.view ?: return@Column Body("불러오는 중이에요")
        Text(v.title, style = CarType.titleS, color = p.textPrimary)
        Panel { StatRow(v.stats) }
        v.episodes.forEach { Panel(label = it.title) { EpisodeChart(it.chart) } }
        // 두 칸은 상세의 실제 폭이 두 칸을 받을 때만(창 1200처럼 상세가 좁으면 차 그림 200이 글자 칸을 다 먹는다)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (narrow || maxWidth < TwoCards) Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                DidCard(v)
                SteerCard(v)
            } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                DidCard(v, Modifier.weight(1f).fillMaxHeight())
                SteerCard(v, Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (v.methods.isNotEmpty()) Panel(label = "방법별 성적") {
            MethodDotsChart(v.methods)
            Note("그 소리를 틀었을 때 그친 횟수예요. 소리 덕분인지는 알 수 없어요.", Modifier.padding(top = 6.dp))
        }
        // 시안 순서(car.js reports: 숫자 줄 → 에피소드 띠 → 차가 한 일 | 조향 → 방법별 성적)를 지키고, 시안에 자리가 없는 사실은 맨 아래에
        Panel(label = "자세히") { v.details.forEach { DetailText(it) } }
    }
}

@Composable
private fun DetailText(d: DetailLine) {
    val p = LocalPalette.current
    if (d.head) Text(d.text, Modifier.padding(top = 10.dp), style = CarType.bodyS.copy(fontWeight = FontWeight(600)), color = p.textPrimary)
    else Body(d.text)
}

/** 차가 대신 한 일(시안 .did): 위에서 본 3D 정지 그림(칩은 실제로 한 것만) + 한 일 목록. */
@Composable
private fun DidCard(v: ReportView, modifier: Modifier = Modifier) = Panel(modifier, label = "차가 대신 한 일") {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        CarStill(v, Modifier.size(200.dp, 233.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (v.actions.isEmpty()) Body("없어요")
            v.actions.forEach { a ->
                // 횟수·초는 이름 아래 둘째 줄(넓은 창 두 칸이면 목록 폭이 약 210이라 한 줄에 두면 잘린다)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LineIcon(actionIcon(a.kind), 30.dp, p.textTertiary)
                    Column {
                        Text(a.name, style = CarType.label.copy(fontSize = CarType.bodyS.fontSize, lineHeight = 32.sp), color = p.textPrimary)
                        Text(a.extra, style = CarType.label, color = p.textTertiary)
                    }
                }
            }
        }
    }
}

/** 3D 'did' 자리(정차 화면에서만 보이고 조작 없음, 상태가 바뀔 때 한 장만 그린다). 화면을 떠나면 놓는다. */
@Composable
private fun CarStill(v: ReportView, modifier: Modifier) {
    val dark = LocalPalette.current.isDark
    AndroidView(
        factory = { CarView(it, interactive = false) }, modifier = modifier, onRelease = { it.destroy() },
        // 지난 주행의 정지 그림: 움직임 없음(reducedMotion으로 바람 결·효과도 끈다)
        update = { it.dark = dark; Stage3d(view = View3d.DID, light = Light3d.BASE, callouts = v.callouts, reducedMotion = true).let { s -> if (it.state != s) it.set(s, animate = false) } },
    )
}

/** 평소 대비 조향 보정(시안 Charts.steer + note). 비교할 수 없으면 그 이유 한 줄. */
@Composable
private fun SteerCard(v: ReportView, modifier: Modifier = Modifier) = Panel(modifier, label = "평소 대비 조향 보정") {
    if (v.steer == null) Body(v.steerText ?: "")
    else {
        SteerChart(v.steer)
        Note("1분당 큰 조향 보정 횟수예요.", Modifier.padding(top = 6.dp))
    }
}

private fun actionIcon(a: CarActionKind) = when (a) {
    CarActionKind.REAR_PLAYBACK -> R.drawable.ic_volume_2
    CarActionKind.DEFAULT_ZONE_PLAYBACK -> R.drawable.ic_speaker
    CarActionKind.SEAT_HEATER_OFF -> R.drawable.ic_flame_kindling
    CarActionKind.REAR_WINDOW_LOCK -> R.drawable.ic_lock
    CarActionKind.CABIN_COOL -> R.drawable.ic_snowflake
    CarActionKind.READING_LIGHT_OFF -> R.drawable.ic_lightbulb_off
    CarActionKind.AMBIENT_DIM -> R.drawable.ic_sun_dim
    CarActionKind.WAYPOINT_ADDED -> R.drawable.ic_map_pin
    CarActionKind.ARRIVAL_NOTICE -> R.drawable.ic_navigation
    CarActionKind.GUARDIAN_ALERT -> R.drawable.ic_bell
}
