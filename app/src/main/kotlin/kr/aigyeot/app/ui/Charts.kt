package kr.aigyeot.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// 차트(시안 js/charts.js: day·episode·methods·steer·stats를 Compose로, 차 크기 글자 24 이상).
// 데이터는 아래 순수 data class(화면 층 밖에서 만든다). 차트 안 시각만 24시간제(ClockMode.CHART, 시안 v6-9).
// 툴팁은 누르고 있을 때만, 표시 위에 값 글자(시안 data-tip 글자 그대로). 처음 등장(띠가 왼쪽에서 그려짐·막대가 자람)은 동작 줄이기면 없다.

// ── 데이터 ──

/** 데이터 색(tokens `cSleep`·`cFeed`·`cDiaper`·`cCar`). */
enum class DataColor { SLEEP, FEED, DIAPER, CAR }

/**
 * 오늘 하루 한 줄(시안 Charts.day): 머리 '이름 · 부가 · 값' + 0~24시 트랙. 시각은 그날 0시부터 분(0~1440).
 * [blocks]는 시작~끝 분 칸(CAR 줄이면 주행 칸, 연하게), [dots]는 트랙 안 점(기저귀), [diamonds]는 마름모(차에서 울음).
 */
data class DayLane(
    val name: String,
    val color: DataColor,
    val sub: String?,
    val value: String,
    val blocks: List<IntRange> = emptyList(),
    val dots: List<Int> = emptyList(),
    val diamonds: List<Int> = emptyList(),
)

/** 오늘 하루 띠. [nowMin]은 오늘이면 지금 분(지금 선·'지금 HH:MM'), 아니면 null. */
data class DayChartData(val lanes: List<DayLane>, val nowMin: Int?)

/**
 * 에피소드 띠(시안 Charts.episode). 초는 에피소드 시작부터. [marks]는 시각을 아는 표시만 축 위에,
 * [outcomes]는 시각이 기록에 없는 결과('울음 n초'·'쉼터 제안'·'그쳤어요')라 띠 아래 축 밖 글자로 둔다.
 */
data class EpisodeChartData(val totalS: Int, val sounds: List<SoundSeg>, val marks: List<ChartMark>, val outcomes: List<String> = emptyList())

/** 소리 줄 한 칸. [name]이 null이면 듣기 틈. [secs]는 툴팁 초(재생은 기록된 play_s, 듣기 틈은 앞 재생 끝~다음 재생 시작). */
data class SoundSeg(val from: Int, val to: Int, val name: String?, val secs: Int = to - from)

data class ChartMark(val at: Int, val label: String)

/** 조향 막대 하나(1분당 큰 조향 보정). [hot]이면 울음 중(주황). */
data class SteerBar(val label: String, val perMin: Double, val hot: Boolean)

/** 방법별 성적 한 줄: 튼 [n]번만큼 점, 그친 [ok]번만큼 채움. [known]이 거짓이면 '아직 모름'. */
data class MethodDots(val name: String, val ok: Int, val n: Int, val known: Boolean)

// ── 툴팁 글자(순수, 시안 data-tip) ──

internal fun hhmm(min: Int) = TimeText.clock(min / 60 % 24, min % 60, ClockMode.CHART)

internal fun dayBlockTip(l: DayLane, r: IntRange) =
    "${if (l.color == DataColor.CAR) "탑승" else l.name} ${hhmm(r.first)}–${hhmm(r.last)} · ${TimeText.duration((r.last - r.first) * 60_000L)}"

internal fun dayDotTip(l: DayLane, m: Int) = "${l.name} ${hhmm(m)}"

internal fun dayDiamondTip(l: DayLane, m: Int) = "${l.name} 울음 ${hhmm(m)}"

// 재생 사이 칸은 그 사이 판정을 멈췄을 수도 있어(입력 끊김) '듣기 틈'이라 단정하지 않고 시간만
internal fun soundTip(s: SoundSeg) = if (s.name != null) "${s.name} ${s.secs}초" else "${s.secs}초"

internal fun steerValue(v: Double) = "%.1f".format(v)

// ── 공용 그리기 ──

private val BAND_EASE = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val BAR_EASE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** 툴팁을 띄울 수 있는 표시 하나. */
private class Hit(val rect: Rect, val tip: String)

/** 폭에 맞춘 그림 한 장: 툴팁 자리와 그리기([draw]의 인자는 0~1 등장 진행). */
private class Geo(val hits: List<Hit>, val draw: DrawScope.(Float) -> Unit)

private fun motionOff(ctx: Context) = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** 처음 보일 때 한 번 0→1(선형, 곡선은 쓰는 쪽이). 동작 줄이기(애니메이터 배율 0)면 처음부터 1. */
@Composable
private fun rememberReveal(ms: Int): Animatable<Float, AnimationVector1D> {
    val ctx = LocalContext.current
    val a = remember { Animatable(if (motionOff(ctx)) 1f else 0f) }
    LaunchedEffect(a) { a.animateTo(1f, tween(ms, easing = LinearEasing)) }
    return a
}

/**
 * 그림 틀: 높이는 미리 알고, 폭이 정해지면 [build]로 그림을 만든다. 누르고 있는 동안 가까운 표시(좌우 32dp 여유)의 툴팁을 띄운다.
 * [sweep]이면 등장 때 왼쪽에서 그려지고, 아니면 진행 값을 [Geo.draw]가 쓴다(막대가 자람).
 */
@Composable
private fun ChartCanvas(height: Dp, desc: String, key: Any, revealMs: Int, sweep: Boolean, modifier: Modifier, build: (Size, Float, TextMeasurer, Palette) -> Geo) {
    val p = LocalPalette.current
    val tm = rememberTextMeasurer()
    val u = LocalDensity.current.density
    val reveal = rememberReveal(revealMs)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var tip by remember { mutableStateOf<Hit?>(null) }
    val g = remember(key, size, p, u) { if (size.width == 0) null else build(Size(size.width.toFloat(), size.height.toFloat()), u, tm, p) }
    Canvas(
        modifier.fillMaxWidth().height(height).onSizeChanged { size = it }.semantics { contentDescription = desc }
            .pointerInput(g) {
                val slop = 32.dp.toPx()
                detectTapGestures(onPress = { o ->
                    val h = g?.hits?.filter { o.x in it.rect.left - slop..it.rect.right + slop && o.y in it.rect.top - slop..it.rect.bottom + slop }
                        ?.minByOrNull { abs(it.rect.center.x - o.x) + abs(it.rect.center.y - o.y) / 4 }
                    if (h != null) { tip = h; tryAwaitRelease(); tip = null }
                })
            },
    ) {
        val geo = g ?: return@Canvas
        val k = reveal.value
        if (sweep) clipRect(right = size.width * BAND_EASE.transform(k)) { geo.draw(this, 1f) } else geo.draw(this, k)
        tip?.let { drawTip(it, tm, p) }
    }
}

/** 툴팁(시안 .ch-tip: 먹색 판, 흰 글자 24/600, 여백 12·16, 반경 16). 표시 위에, 위가 모자라면 아래에. */
private fun DrawScope.drawTip(h: Hit, tm: TextMeasurer, p: Palette) {
    val l = tm.measure(h.tip, TextStyle(fontFamily = AppFont, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight(600), color = p.surface))
    val ph = 16.dp.toPx(); val pv = 12.dp.toPx(); val m = 4.dp.toPx(); val gap = 12.dp.toPx()
    val w = l.size.width + ph * 2; val ht = l.size.height + pv * 2
    val x = (h.rect.center.x - w / 2).coerceIn(m, max(m, size.width - w - m))
    val y = (h.rect.top - ht - gap).let { if (it < 0) h.rect.bottom + gap else it }
    drawRoundRect(p.textPrimary, Offset(x, y), Size(w, ht), CornerRadius(16.dp.toPx()))
    drawText(l, topLeft = Offset(x + ph, y + pv))
}

private fun style(sizeSp: Float, weight: Int, color: Color) =
    TextStyle(fontFamily = AppFont, fontSize = sizeSp.sp, fontWeight = FontWeight(weight), color = color, fontFeatureSettings = "tnum")

/** 글자 바닥선 [baseline]에 맞춰 그린다. [halo]면 바탕색 테를 먼저(지금 선이 글자를 긋지 않게). */
private fun DrawScope.text(l: TextLayoutResult, x: Float, baseline: Float, halo: Color? = null) {
    val at = Offset(x, baseline - l.firstBaseline)
    if (halo != null) drawText(l, color = halo, topLeft = at, drawStyle = Stroke(width = 4.dp.toPx()))
    drawText(l, topLeft = at)
}

private fun Palette.data(c: DataColor) = when (c) { DataColor.SLEEP -> cSleep; DataColor.FEED -> cFeed; DataColor.DIAPER -> cDiaper; DataColor.CAR -> cCar }

/** 위만 둥근 막대(시안: 위 4 둥근 끝, 바닥은 각짐). */
private fun barPath(l: Float, r: Float, top: Float, base: Float, rad: Float) = Path().apply {
    val q = min(rad, (base - top) / 2).coerceAtLeast(0f)
    moveTo(l, base); lineTo(l, top + q); quadraticTo(l, top, l + q, top); lineTo(r - q, top); quadraticTo(r, top, r, top + q); lineTo(r, base); close()
}

// ── 오늘 하루(24시간 띠, 차 크기: 글자 24·값 26) ──

private const val DAY_FS = 24f
private const val DAY_K = DAY_FS / 13f
private fun r(v: Float) = v.roundToInt().toFloat()
private val DAY_TH = r(18 * DAY_K); private val DAY_HH = r(DAY_FS * 1.35f); private val DAY_PAD = r(7 * DAY_K)
private val DAY_GAP = r(16 * DAY_K); private val DAY_TOP = r(DAY_FS * 0.4f)
private val DAY_STEP = DAY_HH + DAY_PAD + DAY_TH + DAY_GAP

/** 줄 수로 정해지는 높이(dp). */
private fun dayHeight(n: Int) = DAY_TOP + (max(n, 1) - 1) * DAY_STEP + DAY_HH + DAY_PAD + DAY_TH + r(10 * DAY_K) + DAY_FS + 2

/**
 * 오늘 하루 띠(시안 Charts.day '오늘 하루 띠 다시 그림'): 줄마다 머리(왼쪽 색 점·이름, 오른쪽 부가·굵은 값) 아래 0~24시 회색 트랙.
 * 지금 뒤는 트랙만 흐리게, 지금 선은 모든 줄을 가로지른다. 아래 눈금 0·6·12·18·24시 중 '지금 HH:MM'과 겹치는 것은 뺀다.
 */
@Composable
fun DayChart(data: DayChartData, modifier: Modifier = Modifier) = ChartCanvas(
    dayHeight(data.lanes.size).dp, "오늘 하루: " + data.lanes.joinToString(", ") { "${it.name} ${it.value}" }, data, 900, sweep = true, modifier,
) { sz, u, tm, p -> dayGeo(data, sz.width, u, tm, p) }

private fun dayGeo(d: DayChartData, w: Float, u: Float, tm: TextMeasurer, p: Palette): Geo {
    val fs = DAY_FS * u; val k = DAY_K * u
    val th = DAY_TH * u; val hh = DAY_HH * u; val pad = DAY_PAD * u
    fun headY(i: Int) = DAY_TOP * u + i * DAY_STEP * u
    fun trY(i: Int) = headY(i) + hh + pad
    val n = d.lanes.size
    val bot = trY(max(n, 1) - 1) + th
    val hTotal = dayHeight(n) * u
    fun x(m: Int) = w * m / 1440f
    val hits = mutableListOf<Hit>()
    val ops = mutableListOf<DrawScope.() -> Unit>()
    val line = p.line; val ink = p.textPrimary
    for (hr in listOf(6, 12, 18)) ops += { drawLine(line, Offset(x(hr * 60), trY(0)), Offset(x(hr * 60), bot), 1.dp.toPx()) }
    val dot = max(4f * u, fs * 0.3f)
    val rx = 4 * k
    d.lanes.forEachIndexed { i, l ->
        val by = headY(i) + hh * 0.78f
        val c = p.data(l.color)
        val name = tm.measure(l.name, style(DAY_FS, 600, ink))
        val right = tm.measure(buildAnnotatedString {
            l.sub?.let { withStyle(SpanStyle(color = p.textTertiary, fontWeight = FontWeight(500))) { append("$it  ") } }
            withStyle(SpanStyle(color = ink, fontWeight = FontWeight(700), fontSize = 26.sp)) { append(l.value) }
        }, style(DAY_FS, 500, p.textTertiary))
        val y = trY(i)
        ops += {
            drawCircle(c, dot, Offset(dot, by - fs * 0.34f))
            text(name, dot * 2 + 6 * k, by, p.surface)
            text(right, w - right.size.width, by, p.surface)
            drawRoundRect(p.surfaceRaised, Offset(0f, y), Size(w, th), CornerRadius(th / 2))
        }
        val blockColor = if (l.color == DataColor.CAR) lerp(p.surfaceRaised, p.cCar, 0.22f) else c
        l.blocks.forEach { b ->
            val bw = if (l.color == DataColor.CAR) x(b.last) - x(b.first) else max(6 * k, x(b.last) - x(b.first) - 2 * k)
            val rect = Rect(x(b.first), y, x(b.first) + max(bw, 1f), y + th)
            ops += { drawRoundRect(blockColor, rect.topLeft, rect.size, CornerRadius(rx)) }
            hits += Hit(rect, dayBlockTip(l, b))
        }
        l.dots.forEach { m ->
            val cc = Offset(x(m), y + th / 2); val rr = th * 0.3f
            ops += { drawCircle(c, rr, cc) }
            hits += Hit(Rect(cc, rr), dayDotTip(l, m))
        }
        l.diamonds.forEach { m ->
            val cx = x(m); val cy = y + th / 2; val q = th * 0.42f
            val path = Path().apply { moveTo(cx, cy - q); lineTo(cx + q, cy); lineTo(cx, cy + q); lineTo(cx - q, cy); close() }
            ops += { drawPath(path, p.cCar); drawPath(path, p.surface, style = Stroke(2.dp.toPx())) }
            hits += Hit(Rect(Offset(cx, cy), q), dayDiamondTip(l, m))
        }
    }
    val now = d.nowMin
    val ay = hTotal - 2 * u
    val nowLab = now?.let { tm.measure("지금 ${hhmm(it)}", style(DAY_FS, 700, ink)) }
    if (now != null && nowLab != null) {
        val nx = x(now)
        ops += {
            d.lanes.indices.forEach { i -> drawRect(p.surface.copy(alpha = 0.6f), Offset(nx + 1, trY(i) - 1), Size(max(0f, w - nx - 1), th + 2)) }
            drawLine(ink, Offset(nx, trY(0) - 3 * k), Offset(nx, bot + 5 * k), 1.5f * u)
        }
    }
    val nowHalf = (nowLab?.size?.width ?: 0) / 2f + 2 * u
    val nowX = now?.let { min(w - nowHalf, max(nowHalf, x(it))) }
    for (hr in listOf(0, 6, 12, 18, 24)) {
        val t = tm.measure("${hr}시", style(DAY_FS, 500, p.textTertiary))
        val tx = x(hr * 60); val tw = t.size.width.toFloat()
        val lo = when (hr) { 0 -> tx; 24 -> tx - tw; else -> tx - tw / 2 }
        if (nowX != null && lo + tw > nowX - nowHalf && lo < nowX + nowHalf) continue
        ops += { text(t, lo, ay) }
    }
    if (nowLab != null && nowX != null) ops += { text(nowLab, nowX - nowLab.size.width / 2f, ay) }
    return Geo(hits) { ops.forEach { it() } }
}

// ── 에피소드 띠(소리 줄·표시 + 축 밖 결과) ──

private const val EPI_FS = 24f
private const val EPI_LEAD = 96f
private const val EPI_LH = 64f
private const val EPI_H = EPI_LH + 18 + (EPI_FS + 6) * 2

/** 소리 줄 하나 + 시각을 아는 표시. 울음 줄은 리포트에 울음 구간이 없어 그리지 않는다(폰과 같음). 결과는 띠 아래 알약으로. */
@Composable
internal fun EpisodeChart(data: EpisodeChartData, modifier: Modifier = Modifier) = Column(modifier.fillMaxWidth()) {
    ChartCanvas(
        EPI_H.dp, "에피소드: " + (data.sounds.mapNotNull { it.name } + data.marks.map { it.label } + data.outcomes).joinToString(", "), data, 900, sweep = true, Modifier,
    ) { sz, u, tm, p -> episodeGeo(data, sz.width, u, tm, p) }
    if (data.outcomes.isNotEmpty()) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        data.outcomes.forEach { Pill(it) }
    }
}

private fun episodeGeo(d: EpisodeChartData, w: Float, u: Float, tm: TextMeasurer, p: Palette): Geo {
    val fs = EPI_FS * u; val lead = EPI_LEAD * u; val right = w - 6 * u; val lh = EPI_LH * u; val bh = lh - 8 * u
    val total = max(d.totalS, 1)
    fun x(s: Int) = lead + (right - lead) * s / total
    val my = bh / 2 + 2 * u + fs * 0.36f
    val hits = mutableListOf<Hit>()
    val ops = mutableListOf<DrawScope.() -> Unit>()
    val laneSound = tm.measure("소리", style(EPI_FS + 1, 600, p.textSecondary))
    ops += { text(laneSound, 0f, my) }
    d.sounds.forEach { s ->
        val rect = Rect(x(s.from), 2 * u, x(s.from) + max(4 * u, x(s.to) - x(s.from) - 2 * u), 2 * u + bh)
        val gap = s.name == null
        val inner = s.name?.takeIf { rect.width > fs * 4.6f }?.let { tm.measure(it, style(EPI_FS, 600, p.surface)) }
        ops += {
            drawRoundRect(if (gap) p.surfaceRaised else p.textPrimary, rect.topLeft, rect.size, CornerRadius(4 * u))
            if (inner != null) text(inner, rect.left + 8 * u, my)
        }
        hits += Hit(rect, soundTip(s))
    }
    d.marks.forEachIndexed { i, m ->
        val mx = min(right, x(m.at))
        val l = tm.measure(m.label, style(EPI_FS, 600, p.textSecondary))
        val lw = l.size.width.toFloat()
        val lx = when (i) { 0 -> mx; d.marks.lastIndex -> mx - lw; else -> mx - lw / 2 }.coerceIn(0f, max(0f, w - lw))
        val ly = lh + 10 * u + fs + (i % 2) * (fs + 6 * u)
        ops += {
            drawLine(p.ink(18), Offset(mx, 0f), Offset(mx, lh - 2 * u), 1.dp.toPx())
            drawCircle(p.textPrimary, 4 * u, Offset(mx, lh + 4 * u))
            drawCircle(p.surface, 4 * u, Offset(mx, lh + 4 * u), style = Stroke(2 * u))
            text(l, lx, ly)
        }
    }
    return Geo(hits) { ops.forEach { it() } }
}

// ── 평소 대비 조향(막대 셋, 울음 중만 주황) ──

private const val STEER_H = 280f

@Composable
internal fun SteerChart(bars: List<SteerBar>, modifier: Modifier = Modifier) = ChartCanvas(
    STEER_H.dp, "1분당 큰 조향 보정: " + bars.joinToString(", ") { "${it.label} ${steerValue(it.perMin)}번" }, bars, 600 + 40 * (bars.size - 1), sweep = false, modifier,
) { sz, u, tm, p -> steerGeo(bars, sz.width, u, tm, p) }

private fun steerGeo(bars: List<SteerBar>, w: Float, u: Float, tm: TextMeasurer, p: Palette): Geo {
    val fs = EPI_FS * u; val h = STEER_H * u; val bw = 24 * u
    val top = 14 * u + fs * 1.6f; val base = h - fs - 12 * u
    val maxV = (bars.maxOfOrNull { it.perMin } ?: 0.0).takeIf { it > 0 }?.times(1.1) ?: 1.0
    val slot = w / max(bars.size, 1)
    val totalMs = 600f + 40f * (bars.size - 1)
    val hits = mutableListOf<Hit>()
    val items = bars.mapIndexed { i, b ->
        val cx = slot * i + slot / 2
        val yy = base - ((base - top) * b.perMin / maxV).toFloat()
        hits += Hit(Rect(cx - slot / 2, top - 10 * u, cx + slot / 2, base), "${b.label} · 1분당 ${steerValue(b.perMin)}번")
        val v = tm.measure(steerValue(b.perMin), style(EPI_FS + 1, if (b.hot) 700 else 600, if (b.hot) p.textPrimary else p.textTertiary))
        val t = tm.measure(b.label, style(EPI_FS, if (b.hot) 700 else 500, if (b.hot) p.textPrimary else p.textTertiary))
        Triple(cx, yy, v to t)
    }
    return Geo(hits) { k ->
        drawLine(p.line, Offset(0f, base), Offset(w, base), 1.dp.toPx())
        items.forEachIndexed { i, (cx, yy, labels) ->
            val e = BAR_EASE.transform(((k * totalMs - 40f * i) / 600f).coerceIn(0f, 1f))
            val top1 = base - (base - yy) * e
            drawPath(barPath(cx - bw / 2, cx + bw / 2, top1, base, 4 * u), if (bars[i].hot) p.cCar else p.ink(13))
            text(labels.first, cx - labels.first.size.width / 2f, top1 - 7 * u)
            text(labels.second, cx - labels.second.size.width / 2f, h - 5 * u)
        }
    }
}

// ── 숫자 줄(시안 kpi k4: 네 칸, 이름 24 흐림 · 값 40/52 굵게 · 부가 24 흐림) ──

@Composable
internal fun StatRow(stats: List<Stat>, modifier: Modifier = Modifier) = androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    val p = LocalPalette.current
    // 칸이 좁으면(분할 840·628) 두 칸씩 두 줄로: 값('10분 30초')이 잘리지 않게
    val cols = if (maxWidth < 760.dp) 2 else 4
    val cell = (maxWidth - 20.dp * (cols - 1)) / cols
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        stats.forEach { s ->
            Column(Modifier.width(cell), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.label, style = CarType.label, color = p.textTertiary)
                Text(s.value, style = style(40f, 700, p.textPrimary).copy(lineHeight = 52.sp), maxLines = 2)
                s.sub?.let { Text(it, style = CarType.label, color = p.textTertiary) }
            }
        }
    }
}

// ── 방법별 성적(점 행렬, 먹색 한 색) ──

@Composable
internal fun MethodDotsChart(rows: List<MethodDots>, modifier: Modifier = Modifier) = Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
    val p = LocalPalette.current
    // 처음 보일 때 점이 하나씩 찍힌다(시안 charts.js dots: 점마다 0.02초 늦게). 동작 줄이기면 바로(rememberReveal)
    val total = rows.sumOf { it.n }.coerceAtLeast(1)
    val reveal = rememberReveal(300 + 20 * total)
    var before = 0
    rows.forEach { m ->
        val start = before; before += m.n
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(m.name, Modifier.width(170.dp), style = CarType.label.copy(fontSize = 28.sp, fontWeight = FontWeight(600)), color = if (m.known) p.textPrimary else p.textTertiary, maxLines = 2)
            FlowRow(
                Modifier.weight(1f).semantics { contentDescription = "${m.n}번 중 ${m.ok}번" },
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(m.n) { i ->
                    val at = (start + i).toFloat() / total   // 끝까지 가면 마지막 점까지 다 찍히게 (total + 4)/total로 늘린다
                    val dot = Modifier.size(22.dp).graphicsLayer { val k = ((reveal.value * (total + 4f) / total - at) * total / 4f).coerceIn(0f, 1f); alpha = k; scaleX = 0.6f + 0.4f * k; scaleY = 0.6f + 0.4f * k }.clip(CircleShape)
                    Box(if (i < m.ok) dot.background(p.textPrimary) else dot.border(1.5.dp, p.ink(if (m.known) 26 else 14), CircleShape))
                }
            }
            Text(
                if (m.known) buildAnnotatedString {
                    withStyle(SpanStyle(color = p.textPrimary, fontWeight = FontWeight(700), fontSize = 32.sp)) { append("${m.ok}") }
                    append("/${m.n}번")
                } else buildAnnotatedString { append("아직 모름") },
                style = style(24f, 500, p.textTertiary), maxLines = 1,
            )
        }
    }
}
