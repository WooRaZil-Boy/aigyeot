package kr.aigyeot.app.ui

import android.os.Debug
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kr.aigyeot.app.R
import kr.aigyeot.app.detect.MicService
import kr.aigyeot.app.host.EventSink
import kr.aigyeot.app.scenario.Scenario
import kr.aigyeot.app.scenario.ScenarioProgress
import kr.aigyeot.app.scenario.SceneInfo
import kr.aigyeot.app.scenario.SceneResults
import kr.aigyeot.engine.ClearRecords
import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.Params
import java.io.File
import java.util.Locale

/** 시연·측정 화면 상태: 장면이 도는지(도는 동안은 고르기·지우기를 끈다), 장면 진행, Virtual Console 확인 결과, 측정표 네 칸, 울음 확률 선. */
data class DemoUiState(
    val running: Boolean = false, val progress: ScenarioProgress? = null, val vcText: String? = null,
    val metrics: List<Metric> = metrics(null, null, null), val prob: List<ProbPoint> = emptyList(),
)

/** 측정표 한 칸(시안 .mtable: 이름 · 값 · 부가). */
data class Metric(val name: String, val value: String, val sub: String)

/** 울음 확률 선의 점: 가로 [x](0~1, 최근 [PROB_WINDOW_MS] 안 자리), 세로 확률 [p](0~1). */
data class ProbPoint(val x: Float, val p: Float)

/** 이 앱 프로세스를 한 번 읽은 값: CPU 누적 틱(utime+stime), 읽은 시각(ms, 단조), PSS(KB). 못 읽은 것은 null. */
data class ProcSample(val ticks: Long?, val atMs: Long, val pssKb: Long?)

/**
 * 시연·측정(F18). 장면 재생기([Scenario])는 읽기·실행·멈춤만 한다. 기록 지우기는 장면 단계가 아니라 사용자가 실제 기록에 하는 일이라
 * 실제 입구([events], V3)로 보낸다(장면 중이면 버튼이 꺼져 있고, 호스트도 장면 중 ClearRecords를 받지 않는다).
 * CPU·메모리는 화면을 보는 동안만 [PROC_EVERY_MS]마다 잰다(구독이 끊기면 멈춘다). 울음 확률은 감지기가 판단 코어로 보내는 구간([segments])을 그대로 받는다.
 */
class DemoViewModel(
    private val scenario: Scenario, private val events: EventSink,
    segments: Flow<CrySegment> = micSegments(), private val sample: () -> ProcSample = ::readProcSample, private val hz: Long = clockTicks(),
) : ViewModel() {
    private val vc = MutableStateFlow<String?>(null)
    private val proc: Flow<Pair<Double?, Long?>> = flow {
        var prev: ProcSample? = null
        while (true) {
            val s = sample()
            emit(prev?.let { cpuPercent(it, s, hz) } to s.pssKb)
            prev = s
            delay(PROC_EVERY_MS)
        }
    }.flowOn(Dispatchers.IO)
    private val prob: Flow<List<ProbPoint>> = segments.scan(emptyList<CrySegment>()) { acc, seg -> recentSegments(acc + seg) }.map(::probPoints)

    val uiState: StateFlow<DemoUiState> = combine(
        combine(scenario.running, scenario.progress, vc, ::Triple), scenario.results, proc, prob,
    ) { (running, progress, vcText), results, (cpu, pss), pts -> DemoUiState(running, progress, vcText, metrics(results, cpu, pss), pts) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DemoUiState(scenario.running.value, scenario.progress.value))

    /** 장면 행(화면에 들어올 때마다 다시 읽는다: 흘려 넣은 장면). */
    fun scenes(): List<SceneInfo> = scenario.list()
    fun run(id: String, repeat: Int?) = scenario.run(id, repeat)
    fun stop() = scenario.stop()
    fun checkVc() { viewModelScope.launch { vc.value = scenario.checkVc() } }
    fun clear() = events.post(ClearRecords(0))
}

/** CPU·메모리를 다시 재는 간격. */
internal const val PROC_EVERY_MS = 2_000L
/** 울음 확률 선이 보여 주는 최근 길이(시안 그래프 한 폭). */
internal const val PROB_WINDOW_MS = 60_000L

/** 감지기가 내는 구간(읽기 전용 갈래). 구독하는 동안만 걸고, 끊기면 뗀다. */
private fun micSegments(): Flow<CrySegment> = callbackFlow {
    val tap: (kr.aigyeot.engine.CrySegment) -> Unit = { trySend(it) }
    MicService.segmentTaps += tap
    awaitClose { MicService.segmentTaps -= tap }
}

/** 최근 [PROB_WINDOW_MS] 안의 구간만(마지막 구간 끝 기준). */
internal fun recentSegments(segs: List<CrySegment>): List<CrySegment> {
    val last = segs.lastOrNull()?.end ?: return segs
    return segs.filter { it.end >= last - PROB_WINDOW_MS }
}

/** 구간 → 그래프 점. 가로는 마지막 구간 끝을 오른쪽 끝으로 둔 최근 [PROB_WINDOW_MS], 세로는 확률(0~1로 자름). */
internal fun probPoints(segs: List<CrySegment>): List<ProbPoint> {
    val last = segs.lastOrNull()?.end ?: return emptyList()
    val from = last - PROB_WINDOW_MS
    return segs.map { ProbPoint(((it.end - from).toFloat() / PROB_WINDOW_MS).coerceIn(0f, 1f), it.p.toFloat().coerceIn(0f, 1f)) }
}

/** 중앙값(짝수 개면 가운데 둘의 평균). 비면 null. */
internal fun median(xs: List<Long>): Double? {
    if (xs.isEmpty()) return null
    val s = xs.sorted(); val m = s.size / 2
    return if (s.size % 2 == 1) s[m].toDouble() else (s[m - 1] + s[m]) / 2.0
}

/** /proc/self/stat 한 줄 → utime + stime(틱). 이름 칸에 빈칸·괄호가 있어도 되게 마지막 ')' 뒤부터 센다(3번째 칸 state가 첫 칸). */
internal fun cpuTicks(stat: String): Long? {
    val f = stat.substringAfterLast(')').trim().split(' ')
    return f.getOrNull(11)?.toLongOrNull()?.let { u -> f.getOrNull(12)?.toLongOrNull()?.let { u + it } }
}

/** 두 번 읽은 사이 CPU 사용률(%, 코어 하나 기준: dumpsys cpuinfo 앱 줄과 같은 기준, 명세서 15장 C105). */
internal fun cpuPercent(a: ProcSample, b: ProcSample, hz: Long): Double? {
    val ta = a.ticks ?: return null; val tb = b.ticks ?: return null
    val dt = b.atMs - a.atMs
    if (dt <= 0 || hz <= 0 || tb < ta) return null
    return (tb - ta) * 1000.0 / hz / dt * 100
}

/** 측정표 네 칸(시안 demo()). 감지 지연은 마지막으로 실행한 장면의 경보 지연(alarm_latency_ms) 중앙값, 목표·예산은 명세서 15장 고정값. 없는 값은 '—'. */
internal fun metrics(results: SceneResults?, cpuPct: Double?, pssKb: Long?): List<Metric> {
    val lat = results?.rows?.mapNotNull { it.alarmLatencyMs }.orEmpty()
    return listOf(
        Metric("감지 지연 중앙값", median(lat)?.let { "%.1f초".format(Locale.ROOT, it / 1000) } ?: "—", results?.let { "${it.id} ${lat.size}/${it.rows.size}회" } ?: ""),
        Metric("경보 지연 목표", "3초 이하", "명세 15장"),
        Metric("CPU", cpuPct?.let { "%.1f%%".format(Locale.ROOT, it) } ?: "—", "예산 5% 미만"),
        Metric("메모리", pssKb?.let { "${(it + 512) / 1024}MB" } ?: "—", "예산 400MB 이하"),
    )
}

/** 이 프로세스의 CPU 틱(/proc/self/stat)과 PSS(Debug.getPss, KB). */
private fun readProcSample() = ProcSample(
    runCatching { cpuTicks(File("/proc/self/stat").readText()) }.getOrNull(),
    SystemClock.elapsedRealtime(),
    runCatching { Debug.getPss() }.getOrNull()?.takeIf { it > 0 },
)

private fun clockTicks(): Long = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L)

/** 진행 한 줄(지금 앱 문구 그대로): "A 2/5회 · 37초 · 어긋남 t=12.0". */
internal fun progressText(p: ScenarioProgress) = "${p.id} ${p.run}/${p.of}회 · ${p.t.toInt()}초" + (p.failedStep?.let { " · 어긋남 t=$it" } ?: "")

/** 반복 단추 글(null = 장면 파일의 repeat). */
internal fun repeatText(repeat: Int?) = "반복 ${repeat?.toString() ?: "장면 기본"}"

/**
 * 시연·측정(시안 demo()): 왼쪽 장면 목록·반복·실행·멈춤, 오른쪽 측정(장면 진행·Virtual Console 확인)과 모든 기록 지우기(C40) → 확인 창.
 * 울음 확률 선(감지기 구간, 기준선 = Params 경보 문턱)과 측정표 네 칸은 실제로 잰 값만 그린다. 없는 값은 '—', 확률이 아직 없으면 기준선만.
 */
@Composable
fun DemoScreen(
    ui: DemoUiState, scenes: List<SceneInfo>, onRun: (String, Int?) -> Unit, onStop: () -> Unit, onCheckVc: () -> Unit, onClear: () -> Unit, onBack: () -> Unit,
) {
    var ask by rememberSaveable { mutableStateOf(false) }
    var sel by rememberSaveable { mutableStateOf(scenes.firstOrNull()?.id) }   // 화면 재생성(회전)에도 남긴다(명세서 11장)
    var repeat by rememberSaveable { mutableStateOf<Int?>(null) }   // null = 장면 파일의 repeat
    val running = ui.running

    val left: @Composable ColumnScope.(Modifier) -> Unit = { listModifier ->
        Sect("장면")
        LazyColumn(listModifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(scenes, key = { it.id }) { s -> SceneRow(s, s.id == sel, !running) { sel = s.id } }
        }
        Stepper(repeatText(repeat), Modifier.padding(top = 24.dp), minusEnabled = !running, plusEnabled = !running,
            onMinus = { repeat = ((repeat ?: 5) - 1).coerceAtLeast(1) }, onPlus = { repeat = (repeat ?: 5) + 1 })
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CarButton("실행", Modifier.weight(1f), BtnKind.BRAND, icon = R.drawable.ic_play, enabled = sel != null && !running) { sel?.let { onRun(it, repeat) } }
            CarButton("멈춤", Modifier.weight(1f), icon = R.drawable.ic_square, enabled = running, onClick = onStop)
        }
    }
    val right: @Composable ColumnScope.() -> Unit = {
        Sect("측정")
        Panel(label = "울음 확률 · 기준 ${Params().alarmThreshold}") { ProbChart(ui.prob, Params().alarmThreshold.toFloat()) }
        MetricGrid(ui.metrics)
        ui.progress?.let { p ->
            Panel {
                Text(progressText(p), style = CarType.titleS, color = LocalPalette.current.textPrimary)
                p.note?.let { Body(it) }
            }
        }
        CarButton("Virtual Console 확인", Modifier.padding(top = 16.dp), size = BtnSize.M, enabled = !running, onClick = onCheckVc)
        ui.vcText?.let { Body("Virtual Console: $it") }
        Body("프로필·연결·노래는 남기고 주행 기록과 성적을 모두 지워요. 휴대폰 중계에 남은 기록도 지워요")
        CarButton("모든 기록 지우기", Modifier.padding(top = 24.dp), BtnKind.DANGER, icon = R.drawable.ic_trash_2, enabled = !running) { ask = true }
    }

    Column(Modifier.fillMaxSize()) {
        Header("시연·측정", onBack = { if (!running) onBack() })   // 장면이 도는 동안은 떠나지 않는다(지금 앱 그대로)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth < NarrowPage) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 44.dp, end = 44.dp, top = 32.dp, bottom = 40.dp)) {
                left(Modifier.height(560.dp))
                Spacer(Modifier.height(36.dp))
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = right)
            } else Row(Modifier.fillMaxSize().padding(start = 44.dp, end = 44.dp, top = 32.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 40.dp)) { left(Modifier.weight(1f)) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = right)
            }
        }
    }
    if (ask) Popup("모든 기록을 지울까요?", "지운 기록은 되돌릴 수 없어요", "지우기", "그만두기", onConfirm = { ask = false; onClear() }, onDismiss = { ask = false })
}

/** 울음 확률 선(시안 .prob: 높이 200, 기준선 아이 주황 점선, 선 브랜드 파랑 4). 정차 화면이라 새 구간마다 다시 그린다. */
@Composable
private fun ProbChart(pts: List<ProbPoint>, threshold: Float) {
    val p = LocalPalette.current
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val y = { v: Float -> size.height * (1 - v) }
        drawLine(p.child, Offset(0f, y(threshold)), Offset(size.width, y(threshold)), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())))
        if (pts.size >= 2) drawPath(
            Path().apply { pts.forEachIndexed { i, q -> if (i == 0) moveTo(q.x * size.width, y(q.p)) else lineTo(q.x * size.width, y(q.p)) } },
            p.accent, style = Stroke(4.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round),
        ) else pts.firstOrNull()?.let { drawCircle(p.accent, 3.dp.toPx(), Offset(it.x * size.width, y(it.p))) }
    }
}

/** 측정표(시안 .mtable: 두 줄 두 칸, 흰 칸 반경 28, 이름 label 600 흐림, 값 300 44/52 고정폭 숫자, 부가 500 24 흐림). */
@Composable
private fun MetricGrid(ms: List<Metric>) {
    val p = LocalPalette.current
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ms.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { m ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(28.dp)).background(p.surface).padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(m.name, style = CarType.label.copy(fontWeight = FontWeight(600)), color = p.textTertiary, maxLines = 1)
                        Text(m.value, style = TextStyle(fontFamily = AppFont, fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight(300), fontFeatureSettings = "tnum"), color = p.textPrimary, maxLines = 1)
                        if (m.sub.isNotEmpty()) Text(m.sub, style = CarType.label.copy(fontWeight = FontWeight(500)), color = p.textTertiary, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 장면 행(시안 .lrow + .badge): 표 · 제목 · 녹음 파일, 고르면 옅은 면 + 확인 표시. */
@Composable
private fun SceneRow(s: SceneInfo, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 100.dp).semantics { selected = on }
            .press(enabled, role = Role.RadioButton, label = s.title ?: s.id, shape = RoundedCornerShape(28.dp), bg = if (on) p.ink(6) else Color.Transparent, pressedBg = p.ink(8), onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Badge(s.id)
        Column(Modifier.weight(1f)) {
            Text(s.title ?: s.id, style = CarType.bodyL.copy(fontWeight = FontWeight(500), lineHeight = 38.sp), color = if (enabled) p.textPrimary else p.textTertiary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.audio.isNotEmpty()) Text(s.audio.joinToString(" · "), style = CarType.label, color = p.textTertiary, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
        }
        if (on) LineIcon(R.drawable.ic_check, 40.dp, p.textPrimary)
    }
}

/** 장면 표(시안 .badge: 높이 64, 600 28, 옅은 면). id가 길면(F14-01) 옆으로 늘어난다. */
@Composable
private fun Badge(id: String) {
    val p = LocalPalette.current
    Box(Modifier.heightIn(min = 64.dp).widthIn(min = 64.dp).clip(RoundedCornerShape(32.dp)).background(p.surfaceRaised).padding(horizontal = 16.dp), Alignment.Center) {
        Text(id, style = CarType.label.copy(fontSize = 28.sp, fontWeight = FontWeight(600)), color = p.textSecondary, maxLines = 1)
    }
}
