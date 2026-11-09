package kr.aigyeot.phone.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.aigyeot.phone.domain.model.durationText
import kotlin.math.roundToInt

// 차트(시안 js/charts.js·css/charts.css를 Compose Canvas로). 규칙(dataviz): 막대 24 이하·위 4 둥근 끝·바닥 각짐, 격자 1 실선,
// 글자는 글자 토큰(데이터 색 금지), 하나를 강조하고 나머지는 회색, 툴팁은 보조(값은 라벨에도 있다).
// 상호작용: 누르거나 끌면 가장 가까운 표시의 값 말풍선. 처음 보일 때 한 번: 띠·히트맵은 왼쪽에서 그려지고(0.9초),
// 막대는 바닥에서 자란다(0.04초 간격, 0.6초). 동작 줄이기면 바로 그린다.

/** 처음 보일 때 0→1(차트가 화면에 들어올 때 한 번, 시안 charts.js grow). 시각·데이터가 바뀌어도 다시 하지 않는다. 동작 줄이기면 1. */
@Composable
private fun rememberReveal(ms: Int, curve: Curve): Animatable<Float, *> {
    val reduced = LocalReducedMotion.current
    val a = remember { Animatable(if (reduced) 1f else 0f) }
    // 도중에 동작 줄이기가 켜지면 바로 끝 상태로.
    LaunchedEffect(reduced) { if (reduced) a.snapTo(1f) else if (a.value < 1f) a.animateTo(1f, androidx.compose.animation.core.tween(ms, easing = curve.easing)) }
    return a
}

/** 표시 하나의 누를 자리와 말풍선 글. */
private class Hit(val rect: Rect, val tip: String)

/** 말풍선(.ch-tip: 먹색 바탕 · 흰 글자 12.5/600, 모서리 10) + 누르기·끌기. 고른 표시는 [selected]로 강조한다. */
@Composable
private fun TipHost(hits: List<Hit>, modifier: Modifier, height: Dp, content: @Composable (selected: Int?) -> Unit) {
    var sel by remember(hits.size) { mutableStateOf<Int?>(null) }
    val p = Tokens.colors
    fun pick(o: Offset): Int? = hits.indices.minByOrNull { i ->
        val r = hits[i].rect
        val dx = (o.x - o.x.coerceIn(r.left, r.right)).let { it * it }
        val dy = (o.y - o.y.coerceIn(r.top, r.bottom)).let { it * it }
        dx + dy
    }?.takeIf { i -> hits[i].rect.inflate(24f).contains(o) }
    Box(
        modifier.fillMaxWidth().height(height)
            .pointerInput(hits) { detectTapGestures { o -> sel = pick(o).takeIf { it != sel } } }
            // 가로로 끌 때만 값을 따라간다(세로 끌기는 화면 굴리기에 넘긴다).
            .pointerInput(hits) { detectHorizontalDragGestures(onDragEnd = { sel = null }, onDragCancel = { sel = null }) { c, _ -> sel = pick(c.position) } },
    ) {
        content(sel)
        val i = sel ?: return@Box
        val r = hits.getOrNull(i)?.rect ?: return@Box
        var w by remember { mutableStateOf(0) }
        var h by remember { mutableStateOf(0) }
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val maxW = with(density) { maxWidth.toPx() }
            val x = (r.center.x - w / 2f).coerceIn(4f, (maxW - w - 4f).coerceAtLeast(4f))
            val y = (r.top - h - with(density) { 10.dp.toPx() }).coerceAtLeast(0f)
            Text(
                hits[i].tip,
                Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .onSizeChanged { w = it.width; h = it.height }
                    .clip(RoundedCornerShape(10.dp)).background(p.textPrimary).padding(horizontal = 10.dp, vertical = 7.dp),
                style = TextStyle(fontFamily = Pretendard, fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
                color = p.surface,
                maxLines = 1,
            )
        }
    }
}

private fun DrawScope.text(m: TextMeasurer, s: String, style: TextStyle, x: Float, baselineY: Float, align: TextAlign = TextAlign.Start, halo: Color? = null) {
    val r = m.measure(s, style)
    val left = when (align) {
        TextAlign.End -> x - r.size.width
        TextAlign.Center -> x - r.size.width / 2f
        else -> x
    }
    val top = baselineY - r.firstBaseline
    if (halo != null) drawRect(halo, Offset(left - 3f, top), Size(r.size.width + 6f, r.size.height.toFloat()))
    drawText(r, topLeft = Offset(left, top))
}

private fun chartStyle(size: Float, weight: Int = 500, color: Color) =
    TextStyle(fontFamily = Pretendard, fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontWeight = FontWeight(weight), color = color, fontFeatureSettings = "tnum")

// ---------------------------------------------------------------- 오늘 하루(24시간 띠)

/**
 * 오늘 하루(시안 Charts.day 6차): 줄마다 머리('● 이름 … 부가 값') + 0~24시 회색 트랙(높이 18) 위 표시.
 * 잠·수유는 트랙 높이 블록(최소 폭 6, 모서리 4), 기저귀는 점, 차에서 울음은 바탕색 테 마름모, 탑승 구간은 연한 주황 칸.
 * 지금 선은 모든 줄을 가로지르고 지금 뒤 트랙은 흐리다. 아래 눈금 0·6·12·18·24시, '지금 HH:MM'과 겹치는 눈금은 뺀다.
 */
@Composable
fun DayStrip(data: DayChartData, modifier: Modifier = Modifier) {
    val p = Tokens.colors
    val m = rememberTextMeasurer()
    val d = LocalDensity.current
    val fs = 13f
    val lane = chartStyle(fs, 600, p.textPrimary)
    val sub = chartStyle(fs, 500, p.textTertiary)
    val value = chartStyle(fs + 2, 700, p.textPrimary)
    val tick = chartStyle(fs, 500, p.textTertiary)
    val nowStyle = chartStyle(fs, 700, p.textPrimary)
    // 줄 높이는 글자 크기(큰 글자 설정 포함)를 재서 정한다.
    val headH = remember(m, value) { m.measure("가", value).size.height.toFloat() }
    val tickH = remember(m, tick) { m.measure("0시", tick).size.height.toFloat() }
    val px = with(d) { 1.dp.toPx() }
    val th = 18 * px
    val pad = 7 * px
    val gap = 16 * px
    val n = data.lanes.size
    val reveal = rememberReveal(900, Motion.camera)
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = data.summary }) {
        val w = with(d) { maxWidth.toPx() }
        fun x(min: Int) = w * min / 1440f
        // 머리가 한 줄에 들지 않으면(큰 글자, R13) 이름 아래 줄에 부가·값을 둔다.
        val two = remember(data, w, headH) {
            data.lanes.map { l ->
                val need = 14 * px + m.measure(l.name, lane).size.width + 12 * px + (l.sub?.let { m.measure(it, sub).size.width + 8 * px } ?: 0f) + m.measure(l.value, value).size.width
                need > w
            }
        }
        val tops = two.runningFold(0f) { acc, t -> acc + (if (t) 2 * headH else headH) + pad + th + gap }
        fun headHeight(i: Int) = if (two[i]) 2 * headH else headH
        fun trackY(i: Int) = tops[i] + headHeight(i) + pad
        val trackBottom = trackY(n - 1) + th
        val totalH = trackBottom + 10 * px + tickH
        val hits = remember(data, w, two) {
            data.lanes.flatMapIndexed { i, l ->
                l.spans.map { s -> Hit(Rect(x(s.from), trackY(i), x(s.from) + maxOf(6 * px, x(s.to) - x(s.from)), trackY(i) + th), s.tip) } +
                    l.marks.map { mk -> Hit(Rect(x(mk.at) - 6 * px, trackY(i), x(mk.at) + 6 * px, trackY(i) + th), mk.tip) }
            }
        }
        TipHost(hits, Modifier, with(d) { totalH.toDp() }) { selected ->
            Canvas(Modifier.fillMaxWidth().height(with(d) { totalH.toDp() })) {
                clipRect(right = size.width * reveal.value) {
                    // 6·12·18시 세로선(줄 사이 틈에서만 보인다)
                    for (h in listOf(6, 12, 18)) drawLine(p.line, Offset(x(h * 60), trackY(0)), Offset(x(h * 60), trackBottom), px)
                    data.lanes.forEachIndexed { i, l ->
                        val color = laneColor(l.kind, p)
                        val ty = trackY(i)
                        drawRoundRect(p.surfaceRaised, Offset(0f, ty), Size(size.width, th), CornerRadius(th / 2))
                        if (l.kind == LaneKind.CAR) l.spans.forEach { s ->
                            drawRoundRect(lerpColor(p.surfaceRaised, p.cCar, 0.22f), Offset(x(s.from), ty), Size(maxOf(2 * px, x(s.to) - x(s.from)), th), CornerRadius(4 * px))
                        } else l.spans.forEach { s ->
                            drawRoundRect(color, Offset(x(s.from), ty), Size(maxOf(6 * px, x(s.to) - x(s.from) - 2 * px), th), CornerRadius(4 * px))
                        }
                        l.marks.forEach { mk ->
                            val c = Offset(x(mk.at), ty + th / 2)
                            if (l.kind == LaneKind.CAR) {
                                val q = th * 0.42f
                                val dia = Path().apply { moveTo(c.x, c.y - q); lineTo(c.x + q, c.y); lineTo(c.x, c.y + q); lineTo(c.x - q, c.y); close() }
                                drawPath(dia, p.cCar)
                                drawPath(dia, p.surface, style = Stroke(2 * px))
                            } else drawCircle(color, th * 0.3f, c)
                        }
                    }
                    // 고른 표시 강조(살짝 밝게)
                    selected?.let { hits.getOrNull(it) }?.let { h -> drawRoundRect(Color.White.copy(alpha = 0.18f), h.rect.topLeft, h.rect.size, CornerRadius(4 * px)) }
                    data.nowMin?.let { now ->
                        val nx = x(now)
                        for (i in 0 until n) drawRect(p.surface.copy(alpha = 0.6f), Offset(nx + px, trackY(i) - px), Size(size.width - nx, th + 2 * px))
                        drawLine(p.textPrimary, Offset(nx, trackY(0) - 3 * px), Offset(nx, trackBottom + 5 * px), 1.5f * px)
                    }
                    // 줄 머리는 지금 선 위에 바탕색 테(halo)를 두르고 그린다: 선이 글자를 긋지 않게(시안 .halo).
                    data.lanes.forEachIndexed { i, l ->
                        val top = tops[i]
                        val baseline = top + headH * 0.8f
                        drawCircle(laneColor(l.kind, p), 4 * px, Offset(4 * px, top + headH / 2))
                        text(m, l.name, lane, 14 * px, baseline, halo = p.surface)
                        val vb = if (two[i]) baseline + headH else baseline
                        val v = m.measure(l.value, value)
                        text(m, l.value, value, size.width, vb, TextAlign.End, halo = p.surface)
                        l.sub?.let { text(m, it, sub, size.width - v.size.width - 8 * px, vb, TextAlign.End, halo = p.surface) }
                    }
                }
                // 아래 눈금
                val ay = totalH - 2 * px
                val nowLab = data.nowMin?.let { "지금 ${hhmm(it)}" }
                val nowW = nowLab?.let { m.measure(it, nowStyle).size.width / 2f + 2 * px } ?: 0f
                val nowX = data.nowMin?.let { x(it).coerceIn(nowW, size.width - nowW) }
                var right = Float.NEGATIVE_INFINITY
                for ((h, align) in listOf(0 to TextAlign.Start, 6 to TextAlign.Center, 12 to TextAlign.Center, 18 to TextAlign.Center, 24 to TextAlign.End)) {
                    val label = "${h}시"
                    val tw = m.measure(label, tick).size.width.toFloat()
                    val tx = x(h * 60)
                    val lo = when (align) { TextAlign.Start -> tx; TextAlign.End -> tx - tw; else -> tx - tw / 2 }
                    if (nowX != null && lo + tw > nowX - nowW && lo < nowX + nowW) continue
                    if (lo < right + 4 * px) continue // 큰 글자에서 앞 눈금과 겹치면 뺀다(R13)
                    text(m, label, tick, tx, ay - tickH * 0.2f, align)
                    right = lo + tw
                }
                if (nowLab != null && nowX != null) text(m, nowLab, nowStyle, nowX, ay - tickH * 0.2f, TextAlign.Center)
            }
        }
    }
}

private fun laneColor(k: LaneKind, p: Palette) = when (k) {
    LaneKind.SLEEP -> p.cSleep
    LaneKind.FEED -> p.cFeed
    LaneKind.DIAPER -> p.cDiaper
    LaneKind.CAR -> p.cCar
}

/** 두 색 사이(CSS color-mix in srgb). */
fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)

// ---------------------------------------------------------------- 7일 막대

/**
 * 7일 막대(시안 Charts.week): 오늘만 데이터 색(`hot`), 나머지는 먹색 13%. 막대 22, 위 4 둥근 끝, 바닥선 1.
 * `target`이 있으면 기준선과 왼쪽 위 라벨(평소 3시간). 오늘 값만 막대 위에 라벨.
 */
@Composable
fun WeekBars(
    bars: List<WeekBar>,
    hot: Color,
    fmt: (Long) -> String,
    label: String,
    modifier: Modifier = Modifier,
    target: Long? = null,
    targetLabel: String? = null,
    height: Dp = 150.dp,
) {
    val p = Tokens.colors
    val m = rememberTextMeasurer()
    val d = LocalDensity.current
    val fs = 12f
    val tick = chartStyle(fs, 500, p.textTertiary)
    val tickOn = chartStyle(fs, 700, p.textPrimary)
    val valStyle = chartStyle(fs + 1, 700, p.textPrimary)
    val tlab = chartStyle(fs, 600, p.textTertiary)
    val tickH = remember(m, tick) { m.measure("오늘", tick).size.height.toFloat() }
    val px = with(d) { 1.dp.toPx() }
    val grow = rememberReveal(600 + 40 * 7, Motion.enter)
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = label + ": " + bars.joinToString(", ") { b -> "${b.label} ${b.value?.let(fmt) ?: "기록 없음"}" } }) {
        val w = with(d) { maxWidth.toPx() }
        val h = with(d) { height.toPx() }
        val top = 20 * px + tickH
        val base = h - tickH - 12 * px
        val max = (listOfNotNull(target) + bars.mapNotNull { it.value }).maxOrNull()?.times(1.12f)?.coerceAtLeast(1f) ?: 1f
        fun y(v: Long) = base - (base - top) * v / max
        val slot = (w - 8 * px) / bars.size
        val bw = minOf(22 * px, slot * 0.6f)
        val hits = remember(bars, w) {
            bars.mapIndexedNotNull { i, b -> b.tip?.let { Hit(Rect(4 * px + slot * i, top - 10 * px, 4 * px + slot * (i + 1), base), it) } }
        }
        TipHost(hits, Modifier, height) { selected ->
            Canvas(Modifier.fillMaxWidth().height(height)) {
                drawLine(p.line, Offset(0f, base), Offset(size.width, base), px)
                if (target != null) {
                    val ty = y(target)
                    drawLine(p.textTertiary, Offset(0f, ty), Offset(size.width, ty), px)
                    targetLabel?.let { text(m, it, tlab, 0f, ty - 6 * px) }
                }
                bars.forEachIndexed { i, b ->
                    val cx = 4 * px + slot * i + slot / 2
                    val isHot = i == bars.lastIndex
                    text(m, b.label, if (isHot) tickOn else tick, cx, h - 5 * px, TextAlign.Center)
                    val v = b.value ?: return@forEachIndexed
                    // 오늘 값은 0이어도 기준선 위에 라벨을 남긴다(시안 charts.js week: 값 라벨은 막대와 따로).
                    if (isHot && v == 0L && grow.value >= 1f) text(m, fmt(v), valStyle, minOf(cx + bw / 2, size.width), base - 7 * px, TextAlign.End)
                    val local = ((grow.value * (600f + 40f * bars.size) - 40f * i) / 600f).coerceIn(0f, 1f)
                    val k = if (grow.value >= 1f) 1f else Motion.enter.easing.transform(local)
                    val yy = y(v)
                    val full = base - yy
                    val barH = full * k
                    if (barH <= 0f) return@forEachIndexed
                    val r = minOf(4 * px, barH / 2)
                    val color = when {
                        isHot -> hot
                        else -> p.textPrimary.copy(alpha = 0.13f)
                    }
                    val left = cx - bw / 2
                    val path = Path().apply {
                        moveTo(left, base); lineTo(left, base - barH + r)
                        quadraticTo(left, base - barH, left + r, base - barH); lineTo(left + bw - r, base - barH)
                        quadraticTo(left + bw, base - barH, left + bw, base - barH + r); lineTo(left + bw, base); close()
                    }
                    drawPath(path, if (selected == hits.indexOfFirst { it.rect.left <= cx && it.rect.right >= cx }) lerpColor(color, Color.White, 0.15f) else color)
                    if (isHot && k >= 1f) text(m, fmt(v), valStyle, minOf(cx + bw / 2, size.width), yy - 7 * px, TextAlign.End)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 잠 패턴 히트맵

/** 잠 패턴(시안 Charts.heat): 7일 × 48칸(30분), 잔 칸만 잠 색. 왼쪽 요일, 아래 0·6·12·18·24시. 툴팁 '11월 9일 00:00 잠'. */
@Composable
fun SleepHeat(g: SleepGrid, modifier: Modifier = Modifier) {
    val p = Tokens.colors
    val m = rememberTextMeasurer()
    val d = LocalDensity.current
    val fs = 12f
    val tick = chartStyle(fs, 500, p.textTertiary)
    val tickOn = chartStyle(fs, 700, p.textPrimary)
    val px = with(d) { 1.dp.toPx() }
    val tickH = remember(m, tick) { m.measure("0시", tick).size.height.toFloat() }
    // 칸 높이 14(시안), 큰 글자에서는 요일 글자 높이만큼 늘려 줄끼리 겹치지 않게 한다(R13).
    val cellH = maxOf(14 * px, remember(m, tickOn) { m.measure("오늘", tickOn).size.height.toFloat() })
    val rowsH = g.rows.size * (cellH + 2 * px)
    val totalH = rowsH + 6 * px + tickH
    val reveal = rememberReveal(900, Motion.camera)
    val leadW = remember(m, tickOn, g.labels) { g.labels.maxOf { m.measure(it, tickOn).size.width }.toFloat() }
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = g.summary }) {
        val w = with(d) { maxWidth.toPx() }
        val lead = maxOf(30 * px, leadW + 6 * px)
        val cw = (w - lead) / 48f
        val hits = remember(g, w) {
            g.rows.flatMapIndexed { r, row ->
                row.indices.filter { row[it] }.map { c -> Hit(Rect(lead + c * cw, r * (cellH + 2 * px), lead + (c + 1) * cw, r * (cellH + 2 * px) + cellH), "${monthDay(g.days[r])} ${hhmm(c * 30)} 잠") }
            }
        }
        TipHost(hits, Modifier, with(d) { totalH.toDp() }) { selected ->
            Canvas(Modifier.fillMaxWidth().height(with(d) { totalH.toDp() })) {
                g.rows.forEachIndexed { r, row ->
                    val yy = r * (cellH + 2 * px)
                    val st = if (r == g.rows.lastIndex) tickOn else tick
                    val lh = m.measure(g.labels[r], st)
                    text(m, g.labels[r], st, 0f, yy + (cellH - lh.size.height) / 2 + lh.firstBaseline)
                }
                clipRect(right = lead + (size.width - lead) * reveal.value) {
                    g.rows.forEachIndexed { r, row ->
                        val yy = r * (cellH + 2 * px)
                        row.forEachIndexed { c, on ->
                            drawRoundRect(if (on) p.cSleep else p.surfaceRaised, Offset(lead + c * cw, yy), Size(cw - 1.2f * px, cellH), CornerRadius(1.5f * px))
                        }
                    }
                    selected?.let { hits.getOrNull(it) }?.let { h -> drawRoundRect(Color.White.copy(alpha = 0.25f), h.rect.topLeft, Size(cw - 1.2f * px, cellH), CornerRadius(1.5f * px)) }
                }
                // 큰 글자에서 앞 눈금과 겹치는 눈금은 뺀다(R13, 200%에서 '18시24시'가 붙었다).
                var right = Float.NEGATIVE_INFINITY
                for (h in listOf(0, 6, 12, 18, 24)) {
                    val align = when (h) { 0 -> TextAlign.Start; 24 -> TextAlign.End; else -> TextAlign.Center }
                    val tw = m.measure("${h}시", tick).size.width.toFloat()
                    val tx = lead + h * 2 * cw
                    val lo = when (align) { TextAlign.Start -> tx; TextAlign.End -> tx - tw; else -> tx - tw / 2 }
                    if (lo < right + 4 * px) continue
                    text(m, "${h}시", tick, tx, totalH - tickH * 0.25f, align)
                    right = lo + tw
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 에피소드 띠

/**
 * 에피소드 띠(시안 Charts.episode). 소리 줄(먹색 막대 + 안에 방법 이름, 듣기 틈은 회색), 아래 표시(감지·다시 울음·쉼터 제안·그쳤어요).
 * 리포트에 울음 구간이 없어 울음 줄은 그리지 않는다(결정 7).
 */
@Composable
fun EpisodeStrip(band: EpisodeBand, modifier: Modifier = Modifier) {
    val p = Tokens.colors
    val m = rememberTextMeasurer()
    val d = LocalDensity.current
    val fs = 12f
    val laneStyle = chartStyle(fs + 1, 600, p.textSecondary)
    val inStyle = chartStyle(fs, 600, p.surface)
    val markStyle = chartStyle(fs, 600, p.textSecondary)
    val px = with(d) { 1.dp.toPx() }
    // 막대(줄 높이 − 8)는 안 글자 높이 + 위아래 4 이상(큰 글자, R13).
    val inH = remember(m, inStyle) { m.measure("가", inStyle).size.height.toFloat() }
    val lh = maxOf(30 * px, inH + 16 * px)
    val markH = remember(m, markStyle) { m.measure("가", markStyle).size.height.toFloat() }
    val totalH = lh + 14 * px + (markH + 6 * px) * 2
    val reveal = rememberReveal(900, Motion.camera)
    val laneW = remember(m, laneStyle) { m.measure("소리", laneStyle).size.width + 12 * px }
    Column(modifier) {
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = "에피소드: " + (band.sounds.map { "${it.name} ${it.to - it.from}초" } + band.marks.map { it.label } + band.outcomes).joinToString(", ") }) {
        val w = with(d) { maxWidth.toPx() }
        val left = laneW
        val right = w - 6 * px
        fun x(s: Int) = left + (right - left) * s / band.totalS.coerceAtLeast(1)
        val hits = remember(band, w) {
            (band.sounds + band.gaps).map { s -> Hit(Rect(x(s.from), 2 * px, x(s.to), lh - 6 * px), "${s.name} ${s.to - s.from}초") }
        }
        TipHost(hits, Modifier, with(d) { totalH.toDp() }) { _ ->
            Canvas(Modifier.fillMaxWidth().height(with(d) { totalH.toDp() })) {
                val laneL = m.measure("소리", laneStyle)
                text(m, "소리", laneStyle, 0f, 2 * px + (lh - 8 * px - laneL.size.height) / 2 + laneL.firstBaseline)
                clipRect(right = left + (size.width - left) * reveal.value) {
                    band.gaps.forEach { s -> drawRoundRect(p.surfaceRaised, Offset(x(s.from), 2 * px), Size(maxOf(2 * px, x(s.to) - x(s.from) - 2 * px), lh - 8 * px), CornerRadius(4 * px)) }
                    band.sounds.forEach { s ->
                        val bw = maxOf(2 * px, x(s.to) - x(s.from) - 2 * px)
                        drawRoundRect(p.textPrimary, Offset(x(s.from), 2 * px), Size(bw, lh - 8 * px), CornerRadius(4 * px))
                        val t = m.measure(s.name, inStyle)
                        if (bw > t.size.width + 16 * px) text(m, s.name, inStyle, x(s.from) + 8 * px, 2 * px + (lh - 8 * px - t.size.height) / 2 + t.firstBaseline)
                    }
                    band.marks.forEachIndexed { i, mk ->
                        val mx = minOf(right, x(mk.at))
                        drawLine(p.textPrimary.copy(alpha = 0.18f), Offset(mx, 0f), Offset(mx, lh), px)
                        drawCircle(p.surface, 6 * px, Offset(mx, lh + 4 * px))
                        drawCircle(p.textPrimary, 4 * px, Offset(mx, lh + 4 * px))
                        val align = when (i) { 0 -> TextAlign.Start; band.marks.lastIndex -> TextAlign.End; else -> TextAlign.Center }
                        text(m, mk.label, markStyle, mx, lh + 10 * px + markH * 0.8f + (i % 2) * (markH + 6 * px), align)
                    }
                }
            }
        }
    }
    // 결과(쉼터 제안·그쳤어요): 그 시각이 리포트에 없어 축 밖에 둔다.
    if (band.outcomes.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { band.outcomes.forEach { Badge(it) } }
    }
}

// ---------------------------------------------------------------- 방법별 성적

/** 방법별 성적(시안 Charts.methods): 이름 · 점 행렬(튼 횟수만큼, 그친 만큼 채움, 먹색 한 색) · '6/7번', 3번 미만은 '아직 모름'. */
data class MethodRow(val name: String, val ok: Int, val n: Int) {
    val known get() = n >= 3
}

@Composable
fun MethodDots(rows: List<MethodRow>, modifier: Modifier = Modifier) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val sorted = rows.sortedWith(compareByDescending<MethodRow> { it.known }.thenByDescending { if (it.n == 0) 0.0 else it.ok.toDouble() / it.n })
    var tip by remember { mutableStateOf<Int?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        sorted.forEachIndexed { r, row ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${row.name}: " + if (row.known) "${row.n}번 중 ${row.ok}번 그침" else "아직 모름" }
                    .pointerInput(r) { detectTapGestures { tip = if (tip == r) null else r } },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 이름 칸은 80 이상(시안 74), 큰 글자에서는 이름 폭만큼 늘어 낱말 가운데서 끊기지 않게(R13).
                // 144에서 멈춰 긴 부모 노래 이름(30자까지)은 줄을 바꾸고 점·횟수 자리를 남긴다.
                Text(row.name, Modifier.widthIn(min = 80.dp, max = 144.dp), style = Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), color = if (row.known) p.textPrimary else p.textTertiary)
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(row.n) { i ->
                        val on = i < row.ok
                        val pop = remember { Animatable(if (reduced || !on) 1f else 0f) }
                        LaunchedEffect(reduced) {
                            if (reduced) pop.snapTo(1f) else if (on && pop.value < 1f) { kotlinx.coroutines.delay(20L * (r * 8 + i)); pop.animateTo(1f, Springs.snappy.spec()) }
                        }
                        Canvas(Modifier.size(12.dp)) {
                            val rr = size.minDimension / 2
                            if (on) scale(pop.value) { drawCircle(p.textPrimary, rr) }
                            else drawCircle(p.textPrimary.copy(alpha = if (row.known) 0.26f else 0.14f), rr - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
                if (row.known) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${row.ok}", style = Type.headline.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = p.textPrimary)
                        Text("/${row.n}번", style = Type.body.copy(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium), color = p.textTertiary)
                    }
                } else Text("아직 모름", style = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium), color = p.textTertiary)
            }
            if (tip == r) Text(
                "${row.name}: ${row.n}번 틀어서 ${row.ok}번 그침",
                Modifier.clip(RoundedCornerShape(10.dp)).background(p.textPrimary).padding(horizontal = 10.dp, vertical = 7.dp),
                style = TextStyle(fontFamily = Pretendard, fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
                color = p.surface,
            )
        }
    }
}

// ---------------------------------------------------------------- 조향 비교

/** 평소 대비 조향 보정(시안 Charts.steer): 평소·울음 중·대응 뒤 셋, '울음 중'만 주황. 값 라벨은 막대 위. */
@Composable
fun SteerBars(baseline: Double?, during: Double?, after: Double?, modifier: Modifier = Modifier) {
    val p = Tokens.colors
    val m = rememberTextMeasurer()
    val d = LocalDensity.current
    val items = listOf(Triple("평소", baseline, false), Triple("울음 중", during, true), Triple("대응 뒤", after, false))
    val fs = 12f
    val tick = chartStyle(fs, 500, p.textTertiary)
    val tickOn = chartStyle(fs, 700, p.textPrimary)
    val valOn = chartStyle(fs + 1, 700, p.textPrimary)
    val valDim = chartStyle(fs + 1, 600, p.textTertiary)
    val px = with(d) { 1.dp.toPx() }
    val height = 140.dp
    val grow = rememberReveal(720, Motion.enter)
    BoxWithConstraints(
        modifier.fillMaxWidth().semantics {
            contentDescription = "1분당 큰 조향 보정: " + items.joinToString(", ") { (k, v, _) -> "$k ${v?.let(::perMinText) ?: "없음"}번" }
        },
    ) {
    val w = with(d) { maxWidth.toPx() }
    val hits = remember(items, w) {
        val slot = w / items.size
        items.mapIndexedNotNull { i, (k, v, _) -> v?.let { Hit(Rect(slot * i, 0f, slot * (i + 1), with(d) { height.toPx() }), "$k · 1분당 ${perMinText(it)}번") } }
    }
    TipHost(hits, Modifier, height) { _ ->
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val tickH = m.measure("평소", tick).size.height
        val top = 14 * px + fs * px * 1.6f
        val base = size.height - tickH - 12 * px
        val max = (items.mapNotNull { it.second }.maxOrNull() ?: 1.0).times(1.1).coerceAtLeast(0.1)
        val slot = size.width / items.size
        val bw = 24 * px
        drawLine(p.line, Offset(0f, base), Offset(size.width, base), px)
        items.forEachIndexed { i, (k, v, hotBar) ->
            val cx = slot * i + slot / 2
            text(m, k, if (hotBar) tickOn else tick, cx, size.height - 5 * px, TextAlign.Center)
            v ?: return@forEachIndexed
            val local = ((grow.value * 720f - 40f * i) / 600f).coerceIn(0f, 1f)
            val kk = if (grow.value >= 1f) 1f else Motion.enter.easing.transform(local)
            val full = ((base - top) * v / max).toFloat()
            val bh = full * kk
            if (bh > 0f) {
                val r = minOf(4 * px, bh / 2)
                val left = cx - bw / 2
                val path = Path().apply {
                    moveTo(left, base); lineTo(left, base - bh + r); quadraticTo(left, base - bh, left + r, base - bh)
                    lineTo(left + bw - r, base - bh); quadraticTo(left + bw, base - bh, left + bw, base - bh + r); lineTo(left + bw, base); close()
                }
                drawPath(path, if (hotBar) p.cCar else p.textPrimary.copy(alpha = 0.13f))
            }
            if (kk >= 1f) text(m, perMinText(v), if (hotBar) valOn else valDim, cx, base - full - 7 * px, TextAlign.Center)
        }
    }
    }
    }
}

/** 마지막 수유 막대(.pmeter): 높이 6 회색 트랙, 먹색 채움(지난 시간 / 간격 × 1.25), 다음 시간대 시작 눈금(아이 주황). */
@Composable
fun FeedMeter(fraction: Float, windowAt: Float?, modifier: Modifier = Modifier) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val v by androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f), if (reduced) androidx.compose.animation.core.tween(0) else Springs.smooth.spec(), label = "meter")
    Canvas(modifier.fillMaxWidth().height(18.dp)) {
        val y = size.height / 2
        val h = 6.dp.toPx()
        drawRoundRect(p.surfaceRaised, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        if (v > 0f) drawRoundRect(p.textPrimary, Offset(0f, y - h / 2), Size(size.width * v, h), CornerRadius(h / 2))
        windowAt?.let { w -> drawRoundRect(p.child, Offset(size.width * w - 1.5.dp.toPx(), y - h / 2 - 6.dp.toPx()), Size(3.dp.toPx(), h + 12.dp.toPx()), CornerRadius(1.5.dp.toPx())) }
    }
}
