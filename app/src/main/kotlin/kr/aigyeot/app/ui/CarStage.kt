package kr.aigyeot.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kr.aigyeot.app.R
import kr.aigyeot.app.ui.car3d.Callout3d
import kr.aigyeot.app.ui.car3d.CarView
import kr.aigyeot.app.ui.car3d.Stage3d
import kotlin.math.roundToInt

// 홈 무대(시안 js/car-stage.js): 실시간 3D 차 + 그 위 '차가 한 일' 칩(무대 위쪽 띠, 3D 앵커까지 선, 그릴 때마다 다시 잼).
// 정차 중: 끌어서 돌려 보기(처음엔 안내, 돌린 뒤엔 '처음 자리로' 단추), 상태가 바뀌면 카메라가 날아간다(1.1초).
// 주행 중: 조작·카메라 이동·소리 결 움직임·칩 없음, 상태가 바뀔 때 200ms 페이드 한 번(Pleos 주행 안전 설계).


/** 칩 글자·아이콘(시안 DATA.CALLOUTS). */
internal fun calloutText(c: Callout3d) = when (c) {
    Callout3d.LOCK -> "뒷창 잠금"
    Callout3d.HEAT -> "열선 끔"
    Callout3d.DIM -> "조명 낮춤"
    Callout3d.HVAC -> "공조 낮춤"   // 시안 '공조 2° 낮춤'의 폭은 최솟값에 걸리면 달라져(실제 낮춘 폭을 기록하지 않음) 글자에 숫자를 쓰지 않는다
}

internal fun calloutIcon(c: Callout3d) = when (c) {
    Callout3d.LOCK -> R.drawable.ic_lock; Callout3d.HEAT -> R.drawable.ic_flame_kindling; Callout3d.DIM -> R.drawable.ic_sun_dim; Callout3d.HVAC -> R.drawable.ic_snowflake
}

/**
 * 칩 가로 자리(시안 place): 앵커 x를 [margin, W-margin]에 묶고 sp 간격으로 벌린 뒤 넘치면 왼쪽으로 민다.
 * 칩이 많거나 무대가 좁으면(칩 넷·분할 840) 여백을 W의 15%까지, 간격을 남은 폭 ÷ (n-1)까지 줄여 무대 밖으로 나가지 않게 한다.
 */
internal fun chipXs(anchorXs: List<Float?>, w: Float, sp0: Float = 280f, margin0: Float = 200f): List<Float> {
    val n = anchorXs.size
    val margin = minOf(margin0, w * 0.15f)
    val sp = if (n > 1) minOf(sp0, (w - 2 * margin) / (n - 1)) else sp0
    val xs = anchorXs.map { (it ?: w / 2).coerceIn(margin, maxOf(margin, w - margin)) }.toMutableList()
    val order = xs.indices.sortedBy { xs[it] }
    for (j in 1 until order.size) xs[order[j]] = maxOf(xs[order[j]], xs[order[j - 1]] + sp)
    val over = if (order.isEmpty()) 0f else xs[order.last()] - (w - margin)
    if (over > 0) order.forEach { xs[it] -= over }
    return xs.map { it.coerceAtLeast(margin) }
}

@Composable
fun CarStage(stage: Stage3d, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    var view by remember { mutableStateOf<CarView?>(null) }
    var shown by remember { mutableStateOf<List<Callout3d>>(emptyList()) }      // 카메라가 닿은 뒤에만 칩(시안 set: 도착 뒤 callouts)
    var anchors by remember { mutableStateOf<List<FloatArray?>>(emptyList()) }
    var moved by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val fade = remember { Animatable(1f) }
    val last = remember { arrayOfNulls<Stage3d>(1) }

    // 주행 중 상태가 바뀌면 200ms 페이드 한 번(시안 .cs.fade). 앞 상태는 시작할 때 바꿔 둔다: 페이드가 도중에 끊기고(A→B→A) 돌아와도 다시 끝까지 간다
    LaunchedEffect(stage) {
        val prev = last[0]; last[0] = stage
        if (stage.drive && prev != null && prev != stage) { fade.snapTo(0f); fade.animateTo(1f, tween(Tokens.DRIVE_FADE_MS, easing = androidx.compose.animation.core.LinearEasing)) } else fade.snapTo(1f)
    }
    // 칩 등장(시안 .co coIn: 칩마다 90ms 늦게, 아래 10에서 올라오며 나타남, 선은 같이 페이드). 동작 줄이기면 바로
    val enter = Tokens.motion.getValue("enter")
    val reveal = remember(shown) { shown.map { Animatable(if (stage.reducedMotion) 1f else 0f) } }
    LaunchedEffect(reveal) {
        reveal.forEachIndexed { i, a -> launch { delay(i * 90L); a.animateTo(1f, tween(enter.ms, easing = enter.easing)) } }
    }

    BoxWithConstraints(modifier.onSizeChanged { size = it }) {
        // 바닥 빛(시안 .cs-floor, 가장자리는 투명이라 화면 분위기 빛과 이어진다) + 접지 그림자 띠
        Box(Modifier.fillMaxSize().background(floorBrush(p)))
        AndroidView(
            factory = { ctx -> CarView(ctx, interactive).also { v ->
                view = v
                v.onMoved = { moved = it }
            } },
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
            update = { v ->
                v.dark = p.isDark
                if (v.state != stage) {
                    shown = emptyList()
                    val callouts = stage.callouts.sortedBy { it.ordinal }
                    v.set(stage, animate = true) { shown = if (stage.drive) emptyList() else callouts }
                }
                // 칩 자리는 값이 바뀔 때만 상태에 넣는다(FloatArray는 참조로 비교돼, 매 장 새 목록을 넣으면 칩이 떠 있는 동안 60fps로 다시 그린다)
                v.onRender = { if (shown.isNotEmpty()) shown.map { c -> c.anchorsIn(stage).mapNotNull(v::project).minByOrNull { it[2] } }.let { if (!sameAnchors(anchors, it)) anchors = it } }
            },
            onRelease = { it.destroy(); view = null },
        )
        // 칩과 선
        if (shown.isNotEmpty() && anchors.size == shown.size && size.width > 0) {
            val w = size.width.toFloat(); val y = size.height * 0.075f
            val xs = chipXs(anchors.map { it?.get(0) }, w, with(density) { 280.dp.toPx() }, with(density) { 200.dp.toPx() })
            val line = p.accent.copy(alpha = 0.7f)
            Canvas(Modifier.fillMaxSize()) {
                val d34 = 34.dp.toPx(); val d64 = 64.dp.toPx()
                shown.indices.forEach { i ->
                    val a = anchors[i] ?: return@forEach
                    val k = reveal.getOrNull(i)?.value ?: 1f
                    val path = Path().apply { moveTo(xs[i], y + d34); lineTo(xs[i], y + d64); lineTo(a[0], a[1]) }
                    drawPath(path, line, alpha = k, style = Stroke(2.dp.toPx()))
                    drawCircle(p.surface, 7.dp.toPx(), Offset(a[0], a[1]), alpha = k)
                    drawCircle(p.accent, 7.dp.toPx(), Offset(a[0], a[1]), alpha = k, style = Stroke(4.dp.toPx()))
                }
            }
            shown.forEachIndexed { i, c ->
                val k = { reveal.getOrNull(i)?.value ?: 1f }
                Chip(c, Modifier.offset { IntOffset(xs[i].roundToInt(), y.roundToInt()) }.graphicsLayer { alpha = k(); translationY = (1 - k()) * 10.dp.toPx() })
            }
        }
        if (interactive && !stage.drive) {
            // 처음엔 안내, 돌린 뒤엔 '처음 자리로'(80, 유리)
            if (!moved) Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(percent = 50)).background(p.surface.copy(alpha = 0.7f)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LineIcon(R.drawable.ic_rotate_3d, 28.dp, p.accentInk)
                Text("끌어서 돌려 보기", style = CarType.label.copy(fontWeight = FontWeight(600)), color = p.textSecondary)
            } else Box(
                Modifier.align(Alignment.BottomStart).padding(36.dp).size(80.dp)
                    .press(label = "처음 자리로", shape = RoundedCornerShape(CarRadius.m), bg = p.glassFill, pressedBg = p.glassFill) { view?.reset() }
                    .border(1.dp, p.line, RoundedCornerShape(CarRadius.m)),
                Alignment.Center,
            ) { LineIcon(R.drawable.ic_locate_fixed, 36.dp, p.textPrimary, "처음 자리로") }
        }
    }
}

/** 칩(시안 .co-chip: 높이 56, 반경 28, 유리, 아이콘 28 브랜드 글자색 + 글자 700 24). 가운데가 x에 오게. */
@Composable
private fun Chip(c: Callout3d, modifier: Modifier) {
    val p = LocalPalette.current
    var w by remember { mutableStateOf(0) }
    Row(
        modifier.graphicsLayer { translationX = -w / 2f; translationY = -28.dp.toPx() }.onSizeChanged { w = it.width }
            .heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(p.glassFill).border(1.dp, p.line, RoundedCornerShape(28.dp))
            .padding(start = 16.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LineIcon(calloutIcon(c), 28.dp, p.accentInk)
        Text(calloutText(c), style = CarType.label.copy(fontWeight = FontWeight(700)), color = p.textPrimary, maxLines = 1)
    }
}

/** 무대 바닥(시안 .cs-floor 라이트·다크, radial 62%×58% 가운데). 바깥은 투명. */
private fun floorBrush(p: Palette): Brush = object : androidx.compose.ui.graphics.ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size) = androidx.compose.ui.graphics.RadialGradientShader(
        center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2), radius = maxOf(size.width * 0.62f, size.height * 0.58f),
        colors = if (p.isDark) listOf(Color(0xFF252B33), Color(0xFF171B20), p.bg.copy(alpha = 0f))
            else listOf(lerpColor(Color.White, p.stage, 0.15f), lerpColor(p.stage, p.bg, 0.4f), p.bg.copy(alpha = 0f)),
        colorStops = listOf(0f, if (p.isDark) 0.52f else 0.55f, 0.78f),
    )
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** 칩 자리 둘이 값으로 같은가(투영 좌표 FloatArray는 내용으로 비교). */
internal fun sameAnchors(a: List<FloatArray?>, b: List<FloatArray?>) =
    a.size == b.size && a.indices.all { i -> val x = a[i]; val y = b[i]; if (x == null || y == null) x == null && y == null else x.contentEquals(y) }
