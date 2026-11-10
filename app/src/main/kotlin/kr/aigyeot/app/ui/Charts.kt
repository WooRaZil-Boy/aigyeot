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
import kotlin.math.ceil
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
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

private val BAND_EASE = Tokens.motion.getValue("camera").easing   // 띠가 왼쪽에서 그려지는 곡선(시안 charts.js: camera 곡선)
private val BAR_EASE = Tokens.motion.getValue("enter").easing    // 막대가 자라는 곡선(시안: enter 곡선)

/** 툴팁을 띄울 수 있는 표시 하나. */
private class Hit(val rect: Rect, val tip: String)

/** 폭에 맞춘 그림 한 장: 높이(px, 잰 글자 크기로 정함), 툴팁 자리, 그리기([draw]의 인자는 0~1 등장 진행). */
private class Geo(val height: Float, val hits: List<Hit>, val draw: DrawScope.(Float) -> Unit)

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
 * 그림 틀: 폭이 정해지면 [build]로 그림을 만들고, 높이는 그림이 정한다(글자를 잰 값으로: 시스템 글자 크기를 키워도 줄·이름·값이 겹치지 않게).
 * 누르고 있는 동안 가까운 표시(좌우 32dp 여유)의 툴팁을 띄운다. [sweep]이면 등장 때 왼쪽에서 그려지고, 아니면 진행 값을 [Geo.draw]가 쓴다(막대가 자람).
 */
@Composable
private fun ChartCanvas(desc: String, key: Any, revealMs: Int, sweep: Boolean, modifier: Modifier, build: (Float, Float, TextMeasurer, Palette) -> Geo) {
    val p = LocalPalette.current
    val tm = rememberTextMeasurer()
    val dens = LocalDensity.current
    val u = dens.density
    val reveal = rememberReveal(revealMs)
    var width by remember { mutableStateOf(0) }
    var tip by remember { mutableStateOf<Hit?>(null) }
    val geos = remember(key, p, dens) { HashMap<Int, Geo>() }   // 폭마다 한 장(재기·그리기가 같은 값을 쓴다)
    fun geo(w: Int) = geos.getOrPut(w) { build(w.toFloat(), u, tm, p) }
    val g = if (width == 0) null else geo(width)
    Canvas(
        modifier.fillMaxWidth()
            .layout { m, c ->
                val w = if (c.hasBoundedWidth) c.maxWidth else c.minWidth
                val h = ceil(geo(w).height).toInt().coerceIn(c.minHeight, c.maxHeight)
                val pl = m.measure(Constraints.fixed(w, h))
                layout(w, h) { pl.place(0, 0) }
            }
            .onSizeChanged { width = it.width }.semantics { contentDescription = desc }
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
    val l = tm.measure(h.tip, CarType.label.copy(lineHeight = 31.sp, fontWeight = FontWeight(600), color = p.surface))
    val ph = 16.dp.toPx(); val pv = 12.dp.toPx(); val m = 4.dp.toPx(); val gap = 12.dp.toPx()
    val w = l.size.width + ph * 2; val ht = l.size.height + pv * 2
    val x = (h.rect.center.x - w / 2).coerceIn(m, max(m, size.width - w - m))
    val y = (h.rect.top - ht - gap).let { if (it < 0) h.rect.bottom + gap else it }
    drawRoundRect(p.textPrimary, Offset(x, y), Size(w, ht), CornerRadius(CarRadius.s.toPx()))
    drawText(l, topLeft = Offset(x + ph, y + pv))
}

private fun style(sizeSp: Float, weight: Int, color: Color) =
    TextStyle(fontFamily = AppFont, fontSize = sizeSp.sp, fontWeight = FontWeight(weight), color = color, fontFeatureSettings = "tnum")

/**
 * 글자 바닥선 [baseline]에 맞춰 그린다. [halo]면 그 테(같은 글을 바탕색으로 잰 것)를 먼저(지금 선이 글자를 긋지 않게).
 * 테는 바탕색을 글 안(span)에 넣어 따로 잰다: drawText의 color 덮어쓰기와 TextStyle 색만 바꾼 측정은 글자색 배치를 그대로 써 테가 글자색으로 그려졌다.
 */
private fun DrawScope.text(l: TextLayoutResult, x: Float, baseline: Float, halo: TextLayoutResult? = null) {
    val at = Offset(x, baseline - l.firstBaseline)
    if (halo != null) drawText(halo, topLeft = at, drawStyle = Stroke(width = 4.dp.toPx()))
    drawText(l, topLeft = at)
}

private fun Palette.data(c: DataColor) = when (c) { DataColor.SLEEP -> cSleep; DataColor.FEED -> cFeed; DataColor.DIAPER -> cDiaper; DataColor.CAR -> cCar }

/** 위만 둥근 막대(시안: 위 4 둥근 끝, 바닥은 각짐). */
private fun barPath(l: Float, r: Float, top: Float, base: Float, rad: Float) = Path().apply {
    val q = min(rad, (base - top) / 2).coerceAtLeast(0f)
    moveTo(l, base); lineTo(l, top + q); quadraticTo(l, top, l + q, top); lineTo(r - q, top); quadraticTo(r, top, r, top + q); lineTo(r, base); close()
}

// ── 오늘 하루(24시간 띠, 차 크기: 글자 24·값 26) ──

private val DAY_FS = CarType.label.fontSize.value   // 차 크기 띠 글자 24(토큰 label)
private val DAY_K = DAY_FS / 13f
private fun r(v: Float) = v.roundToInt().toFloat()
private val DAY_TH = r(18 * DAY_K); private val DAY_HH = r(DAY_FS * 1.35f); private val DAY_PAD = r(7 * DAY_K)
private val DAY_GAP = r(16 * DAY_K); private val DAY_TOP = r(DAY_FS * 0.4f)

/** 줄 머리 배치: 한 줄(이름 왼쪽·값 오른쪽)이 들어가면 한 줄, 아니면 이름 위·값 아래. 높이·바닥선은 잰 글자 크기로(px). */
internal data class Head(val stacked: Boolean, val height: Float, val nameBase: Float, val rightBase: Float)

internal fun dayHead(nameRight: Float, nameH: Float, nameBase: Float, rightW: Float, rightH: Float, rightBase: Float, w: Float, gap: Float, minH: Float): Head {
    if (nameRight + gap + rightW <= w) {
        val b = max(minH * 0.78f, max(nameBase, rightBase))
        return Head(false, max(minH, b + max(nameH - nameBase, rightH - rightBase)), b, b)
    }
    return Head(true, nameH + rightH, nameBase, nameH + rightBase)
}

/**
 * 오늘 하루 띠(시안 Charts.day '오늘 하루 띠 다시 그림'): 줄마다 머리(왼쪽 색 점·이름, 오른쪽 부가·굵은 값) 아래 0~24시 회색 트랙.
 * 지금 뒤는 트랙만 흐리게, 지금 선은 모든 줄을 가로지른다. 아래 눈금 0·6·12·18·24시 중 '지금 HH:MM'과 겹치는 것은 뺀다.
 */
@Composable
fun DayChart(data: DayChartData, modifier: Modifier = Modifier) = ChartCanvas(
    "오늘 하루: " + data.lanes.joinToString(", ") { "${it.name} ${it.value}" }, data, 900, sweep = true, modifier,
) { w, u, tm, p -> dayGeo(data, w, u, tm, p) }

private fun dayGeo(d: DayChartData, w: Float, u: Float, tm: TextMeasurer, p: Palette): Geo {
    val fs = DAY_FS * u; val k = DAY_K * u
    val th = DAY_TH * u; val pad = DAY_PAD * u
    val ink = p.textPrimary
    val dot = max(4f * u, fs * 0.3f)
    val nameX = dot * 2 + 6 * k
    fun x(m: Int) = w * m / 1440f
    val hits = mutableListOf<Hit>()
    val ops = mutableListOf<DrawScope.() -> Unit>()
    val back = mutableListOf<DrawScope.() -> Unit>()   // 시각 눈금 세로선(트랙 아래)
    val rx = 4 * k
    val tracks = mutableListOf<Float>()
    var top = DAY_TOP * u
    d.lanes.forEach { l ->
        val c = p.data(l.color)
        val one = Constraints(maxWidth = w.toInt().coerceAtLeast(1))
        // 색은 글 안(span)에 둔다: TextMeasurer는 색만 다른 글을 같은 배치로 돌려줘(캐시) 테가 글자색으로 그려진다
        fun nameIn(c: Color) = tm.measure(buildAnnotatedString { withStyle(SpanStyle(color = c)) { append(l.name) } }, style(DAY_FS, 600, c), overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = one)
        fun rightIn(sub: Color, value: Color) = tm.measure(buildAnnotatedString {
            l.sub?.let { withStyle(SpanStyle(color = sub, fontWeight = FontWeight(500))) { append("$it  ") } }
            withStyle(SpanStyle(color = value, fontWeight = FontWeight(700), fontSize = CarType.bodyS.fontSize)) { append(l.value) }
        }, style(DAY_FS, 500, sub), overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = one)
        val name = nameIn(ink); val right = rightIn(p.textTertiary, ink)
        val nameHalo = nameIn(p.surface); val rightHalo = rightIn(p.surface, p.surface)   // 같은 글을 바탕색으로(테)
        val hd = dayHead(nameX + name.size.width, name.size.height.toFloat(), name.firstBaseline, right.size.width.toFloat(), right.size.height.toFloat(), right.firstBaseline, w, 16 * u, DAY_HH * u)
        val nb = top + hd.nameBase; val rb = top + hd.rightBase
        val y = top + hd.height + pad
        tracks += y
        ops += {
            drawCircle(c, dot, Offset(dot, nb - name.firstBaseline + name.size.height / 2f))
            text(name, nameX, nb, nameHalo)
            text(right, max(0f, w - right.size.width), rb, rightHalo)
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
        top = y + th + DAY_GAP * u
    }
    val first = tracks.firstOrNull() ?: (DAY_TOP * u + DAY_HH * u + pad)
    val bot = (tracks.lastOrNull() ?: first) + th
    for (hr in listOf(6, 12, 18)) back += { drawLine(p.line, Offset(x(hr * 60), first), Offset(x(hr * 60), bot), 1.dp.toPx()) }
    val now = d.nowMin
    val nowLab = now?.let { tm.measure("지금 ${hhmm(it)}", style(DAY_FS, 700, ink)) }
    val axis = (0..24 step 6).associateWith { tm.measure("${it}시", style(DAY_FS, 500, p.textTertiary)) }
    val labTop = bot + 10 * k
    val labH = max(axis.values.maxOf { it.size.height }, nowLab?.size?.height ?: 0).toFloat()
    val labBase = labTop + max(axis.values.maxOf { it.firstBaseline }, nowLab?.firstBaseline ?: 0f)
    if (now != null && nowLab != null) {
        val nx = x(now)
        ops += {
            tracks.forEach { ty -> drawRect(p.surface.copy(alpha = 0.6f), Offset(nx + 1, ty - 1), Size(max(0f, w - nx - 1), th + 2)) }
            drawLine(ink, Offset(nx, first - 3 * k), Offset(nx, bot + 5 * k), 1.5f * u)
        }
    }
    val nowHalf = (nowLab?.size?.width ?: 0) / 2f + 2 * u
    val nowX = now?.let { min(w - nowHalf, max(nowHalf, x(it))) }
    axis.forEach { (hr, t) ->
        val tx = x(hr * 60); val tw = t.size.width.toFloat()
        val lo = when (hr) { 0 -> tx; 24 -> tx - tw; else -> tx - tw / 2 }
        if (nowX != null && lo + tw > nowX - nowHalf && lo < nowX + nowHalf) return@forEach
        ops += { text(t, lo, labBase) }
    }
    if (nowLab != null && nowX != null) ops += { text(nowLab, nowX - nowLab.size.width / 2f, labBase) }
    return Geo(labTop + labH + 2 * u, hits) { back.forEach { it() }; ops.forEach { it() } }
}

// ── 에피소드 띠(소리 줄·표시 + 축 밖 결과) ──

private val EPI_FS = CarType.label.fontSize.value
private const val EPI_LEAD = 96f
private const val EPI_LH = 64f

/** 소리 줄 하나 + 시각을 아는 표시. 울음 줄은 리포트에 울음 구간이 없어 그리지 않는다(폰과 같음). 결과는 띠 아래 알약으로. */
@Composable
internal fun EpisodeChart(data: EpisodeChartData, modifier: Modifier = Modifier) = Column(modifier.fillMaxWidth()) {
    ChartCanvas(
        "에피소드: " + (data.sounds.mapNotNull { it.name } + data.marks.map { it.label } + data.outcomes).joinToString(", "), data, 900, sweep = true, Modifier,
    ) { w, u, tm, p -> episodeGeo(data, w, u, tm, p) }
    if (data.outcomes.isNotEmpty()) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        data.outcomes.forEach { Pill(it) }
    }
}

private fun episodeGeo(d: EpisodeChartData, w: Float, u: Float, tm: TextMeasurer, p: Palette): Geo {
    val laneSound = tm.measure("소리", style(EPI_FS + 1, 600, p.textSecondary))
    // 줄 이름 칸·줄 높이는 잰 글자로(글자 크기를 키우면 넓어지고 높아진다)
    val lead = max(EPI_LEAD * u, laneSound.size.width + 12 * u); val right = w - 6 * u
    val lh = max(EPI_LH * u, laneSound.size.height + 12 * u); val bh = lh - 8 * u
    val total = max(d.totalS, 1)
    fun x(s: Int) = lead + (right - lead) * s / total
    fun mid(l: TextLayoutResult) = 2 * u + bh / 2 - l.size.height / 2f + l.firstBaseline   // 줄 가운데에 오는 바닥선
    val hits = mutableListOf<Hit>()
    val ops = mutableListOf<DrawScope.() -> Unit>()
    ops += { text(laneSound, 0f, mid(laneSound)) }
    d.sounds.forEach { s ->
        val rect = Rect(x(s.from), 2 * u, x(s.from) + max(4 * u, x(s.to) - x(s.from) - 2 * u), 2 * u + bh)
        val gap = s.name == null
        val inner = s.name?.let { tm.measure(it, style(EPI_FS, 600, p.surface)) }?.takeIf { it.size.width + 16 * u <= rect.width && it.size.height <= bh }   // 칸에 들어갈 때만
        ops += {
            drawRoundRect(if (gap) p.surfaceRaised else p.textPrimary, rect.topLeft, rect.size, CornerRadius(4 * u))
            if (inner != null) text(inner, rect.left + 8 * u, mid(inner))
        }
        hits += Hit(rect, soundTip(s))
    }
    val labels = d.marks.map { tm.measure(it.label, style(EPI_FS, 600, p.textSecondary)) }
    val rowH = labels.maxOfOrNull { it.size.height }?.toFloat() ?: 0f
    d.marks.forEachIndexed { i, m ->
        val mx = min(right, x(m.at))
        val l = labels[i]
        val lw = l.size.width.toFloat()
        val lx = when (i) { 0 -> mx; d.marks.lastIndex -> mx - lw; else -> mx - lw / 2 }.coerceIn(0f, max(0f, w - lw))
        val ly = lh + 10 * u + (i % 2) * (rowH + 6 * u) + l.firstBaseline
        ops += {
            drawLine(p.ink(18), Offset(mx, 0f), Offset(mx, lh - 2 * u), 1.dp.toPx())
            drawCircle(p.textPrimary, 4 * u, Offset(mx, lh + 4 * u))
            drawCircle(p.surface, 4 * u, Offset(mx, lh + 4 * u), style = Stroke(2 * u))
            text(l, lx, ly)
        }
    }
    val rows = if (d.marks.size > 1) 2 else d.marks.size
    return Geo(lh + 10 * u + rows * rowH + (rows - 1).coerceAtLeast(0) * 6 * u + 4 * u, hits) { ops.forEach { it() } }
}

// ── 평소 대비 조향(막대 셋, 울음 중만 주황) ──

private const val STEER_H = 280f

@Composable
internal fun SteerChart(bars: List<SteerBar>, modifier: Modifier = Modifier) = ChartCanvas(
    "1분당 큰 조향 보정: " + bars.joinToString(", ") { "${it.label} ${steerValue(it.perMin)}번" }, bars, 600 + 40 * (bars.size - 1), sweep = false, modifier,
) { w, u, tm, p -> steerGeo(bars, w, u, tm, p) }

private fun steerGeo(bars: List<SteerBar>, w: Float, u: Float, tm: TextMeasurer, p: Palette): Geo {
    val bw = 24 * u
    val slot = w / max(bars.size, 1)
    val fit = Constraints(maxWidth = (slot - 8 * u).toInt().coerceAtLeast(1))   // 이름이 칸보다 길면 말줄임(이웃 칸과 겹치지 않게)
    val labels = bars.map { b ->
        tm.measure(steerValue(b.perMin), style(EPI_FS + 1, if (b.hot) 700 else 600, if (b.hot) p.textPrimary else p.textTertiary)) to
            tm.measure(b.label, style(EPI_FS, if (b.hot) 700 else 500, if (b.hot) p.textPrimary else p.textTertiary), overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = fit)
    }
    // 위·아래 글자 자리는 잰 높이로, 막대 자리는 최소 120dp
    val valueH = labels.maxOfOrNull { it.first.size.height }?.toFloat() ?: 0f
    val labelH = labels.maxOfOrNull { it.second.size.height }?.toFloat() ?: 0f
    val top = 10 * u + valueH + 4 * u
    val h = max(STEER_H * u, top + 120 * u + 10 * u + labelH)
    val base = h - labelH - 10 * u
    val maxV = (bars.maxOfOrNull { it.perMin } ?: 0.0).takeIf { it > 0 }?.times(1.1) ?: 1.0
    val totalMs = 600f + 40f * (bars.size - 1)
    val hits = mutableListOf<Hit>()
    val items = bars.mapIndexed { i, b ->
        val cx = slot * i + slot / 2
        val yy = base - ((base - top) * b.perMin / maxV).toFloat()
        hits += Hit(Rect(cx - slot / 2, top - 10 * u, cx + slot / 2, base), "${b.label} · 1분당 ${steerValue(b.perMin)}번")
        Triple(cx, yy, labels[i])
    }
    return Geo(h, hits) { k ->
        drawLine(p.line, Offset(0f, base), Offset(w, base), 1.dp.toPx())
        items.forEachIndexed { i, (cx, yy, l) ->
            val e = BAR_EASE.transform(((k * totalMs - 40f * i) / 600f).coerceIn(0f, 1f))
            val top1 = base - (base - yy) * e
            drawPath(barPath(cx - bw / 2, cx + bw / 2, top1, base, 4 * u), if (bars[i].hot) p.cCar else p.ink(13))
            text(l.first, cx - l.first.size.width / 2f, top1 - 7 * u)
            text(l.second, cx - l.second.size.width / 2f, base + 6 * u + l.second.firstBaseline)
        }
    }
}

// ── 숫자 줄(시안 kpi k4: 네 칸, 이름 24 흐림 · 값 40/52 굵게 · 부가 24 흐림) ──

@Composable
internal fun StatRow(stats: List<Stat>, modifier: Modifier = Modifier) = androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    val p = LocalPalette.current
    // 칸이 좁으면(분할 840·628) 두 칸씩 두 줄로: 값('10분 30초')이 잘리지 않게
    val cols = if (maxWidth < 760.dp) 2 else 4
    // 칸 폭은 나눠서 계산하지 않고 weight로(픽셀 반올림 합이 줄 폭을 넘어 마지막 칸이 내려가지 않게). 모자란 칸은 빈 자리
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        stats.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { s ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.label, style = CarType.label, color = p.textTertiary)
                        Text(s.value, style = style(CarType.titleL.fontSize.value, 700, p.textPrimary).copy(lineHeight = CarType.titleL.lineHeight), maxLines = 2)
                        s.sub?.let { Text(it, style = CarType.label, color = p.textTertiary) }
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
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
                    withStyle(SpanStyle(color = p.textPrimary, fontWeight = FontWeight(700), fontSize = CarType.titleS.fontSize)) { append("${m.ok}") }
                    append("/${m.n}번")
                } else buildAnnotatedString { append("아직 모름") },
                style = style(CarType.label.fontSize.value, 500, p.textTertiary), maxLines = 1,
            )
        }
    }
}
