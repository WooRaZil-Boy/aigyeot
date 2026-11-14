package kr.aigyeot.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.aigyeot.app.R
import kr.aigyeot.app.ui.car3d.Callout3d
import kr.aigyeot.app.ui.car3d.CarView
import kr.aigyeot.app.ui.car3d.Stage3d
import kr.aigyeot.app.ui.car3d.View3d
import kr.aigyeot.shared.CarActionKind
import kotlin.math.roundToInt

// 홈 무대: 실시간 3D 차 + 그 위 '차가 한 일' 칩(무대 위쪽 띠, 3D 앵커까지 선, 그릴 때마다 다시 잼).
// 정차 중: 끌어서 돌려 보기(처음엔 안내, 돌린 뒤엔 '처음 자리로' 단추), 상태가 바뀌면 카메라가 날아간다(1.1초).
// 주행 중: 조작·카메라 이동·소리 결 움직임·칩 없음, 상태가 바뀔 때 200ms 페이드 한 번(Pleos 주행 안전 설계).


/** 칩 글자·아이콘. 리포트 '차가 대신 한 일'과 같은 이름은 그 함수 하나에서 가져온다. 열선만 칩 자리가 좁아 '열선 끔'. */
internal fun calloutText(c: Callout3d) = when (c) {
    Callout3d.LOCK -> actionName(CarActionKind.REAR_WINDOW_LOCK)
    Callout3d.HEAT -> "열선 끔"
    Callout3d.DIM -> actionName(CarActionKind.AMBIENT_DIM)
    Callout3d.HVAC -> actionName(CarActionKind.CABIN_COOL)   // '공조 2° 낮춤'의 폭은 최솟값에 걸리면 달라져(실제 낮춘 폭을 기록하지 않음) 글자에 숫자를 쓰지 않는다
}

internal fun calloutIcon(c: Callout3d) = when (c) {
    Callout3d.LOCK -> R.drawable.ic_lock; Callout3d.HEAT -> R.drawable.ic_flame_kindling
    Callout3d.DIM -> R.drawable.ic_sun_dim; Callout3d.HVAC -> R.drawable.ic_snowflake
}

/**
 * 칩 가로 자리: 앵커 x를 [margin, W-margin]에 묶고 sp 간격으로 벌린 뒤 넘치면 왼쪽으로 민다.
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

/**
 * 첫 3D 장 전에 홈 무대에 둘 정지 그림: 시작 연출 첫 장인 바깥 모습(HERO)을 홈 무대 크기(998×1097px, CarView '첫 화면' 로그)에서 찍은
 * 투명 PNG(debug Car3dSpikeActivity `--es capture --ez noseat true`, 기본 빛, 칩 없음). 지금 상태를 담지 않게 카시트를 빼고 듣는 빛 없이 찍었다:
 * 바깥 모습에서도 옆 유리로 카시트가, 앞 유리로 듣는 빛이 보여 그대로면 다른 자리·빛을 안내하게 된다(car3d_cabin_1111.txt 13절 화소 대조).
 * 3D로 바뀌면 지금 자리의 카시트와 빛이 나타난다.
 */
internal fun posterRes(dark: Boolean): Int = if (dark) R.drawable.car_poster_hero_dark else R.drawable.car_poster_hero_light

/**
 * 홈 무대를 붙일 때 정지 그림을 둘지: 첫 장이 시작 연출의 바깥 모습일 때(연출이 남았고 정차·동작 켜짐). 엔진이 이미 준비돼도 같다:
 * 주행 중에 켠 뒤 첫 정차·아기 설정을 마친 뒤 첫 홈은 바깥 모습 셰이더를 이때 처음 만들어 약 1.3초 빈 무대가 생기고, 덮지 않으면 앞 멈춤의 옅은 X-ray 장도
 * 보인다(car3d_cabin_1111.txt 15절). 주행 중·동작 줄이기는 연출 없이 첫 장이 실내라 바깥 그림이 맞지 않고, 실내 그림은 카시트 자리·빛을 크게 담아
 * 상태와 다를 수 있어 두지 않는다: 그때는 준비 표시의 정지 표시(움직이지 않는 차 아이콘). 카시트 자리·빛과는 무관하다(그림이 그것을 담지 않는다).
 */
internal fun posterFor(introPending: Boolean, s: Stage3d): Boolean = introPending && s.motionOk

/** 정지 그림이 지금 보이는가: 골랐고, 아직 3D로 바꾸지 않았고, 3D 준비가 실패하지 않았을 때. 실패면 걷고 실패 정지 표시를 둔다. */
internal fun posterShown(chosen: Boolean, unveiled: Boolean, failedMs: Long?): Boolean = chosen && !unveiled && failedMs == null

/**
 * 정지 그림 → 3D 교차 페이드 길이(3D가 들어오는 길이 = `enter` 360ms, 주행 중 200ms 한 번, 동작 줄이기 바로). [exact]면(찍은 크기 = 무대 크기) 같은 구도라
 * 0(한 장에 바로 바꿈, 카시트·지금 빛만 나타난다): 투명한 데(그림자·유리)가 있는 같은 구도를 겹쳐 흐리면 그 사이 두 겹으로 짙어졌다 옅어져 오히려 보인다(12절 실측).
 */
internal fun posterFadeMs(s: Stage3d, exact: Boolean): Int = when {
    exact || s.reducedMotion -> 0
    s.drive -> Tokens.DRIVE_FADE_MS
    else -> Tokens.motion.getValue("enter").ms
}

@Composable
fun CarStage(stage: Stage3d, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    var view by remember { mutableStateOf<CarView?>(null) }
    val load = remember { StageLoad() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val stageNow by androidx.compose.runtime.rememberUpdatedState(stage)
    // 정지 그림은 무대를 붙일 때 한 번 고른다. 3D는 정지 그림과 같은 구도의 장이 화면에 들어오면(시작 연출 앞 멈춤이 끝난 회전 첫 장, 연출을 못 하게 됐으면 첫 장)
    // 그 장에서는 숨기고 다음 장에서 정지 그림을 빼며 보인다: 한 장이라도 겹치면 그림자·유리가 두 겹으로 짙어진다. 크기가 달라 어긋나면 교차 페이드로 덮는다
    val poster = remember { posterFor(CarView.introPending(), stage) }
    val posterImg = if (poster) androidx.compose.ui.graphics.ImageBitmap.imageResource(posterRes(p.isDark)) else null
    var unveiled by remember { mutableStateOf(false) }
    fun posterOn() = posterShown(poster, unveiled, load.failedMs)
    var posterK by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var crossfade by remember { mutableStateOf(false) }
    val swap = remember { arrayOfNulls<CarView>(1) }   // 정지 그림을 빼는 장에서 보일 3D
    if (!posterOn()) androidx.compose.runtime.SideEffect { swap[0]?.let { it.alpha = 1f; swap[0] = null } }
    LaunchedEffect(crossfade) {
        val v = view ?: return@LaunchedEffect
        if (!crossfade) return@LaunchedEffect
        val ms = posterFadeMs(stageNow, exact = false)
        androidx.compose.animation.core.animate(0f, 1f, animationSpec = tween(ms,
                easing = if (stageNow.drive) androidx.compose.animation.core.LinearEasing else Tokens.motion.getValue("enter").easing)
        ) { k, _ -> v.alpha = k; posterK = 1f - k }
        unveiled = true
    }
    var shown by remember { mutableStateOf<List<Callout3d>>(emptyList()) }      // 카메라가 닿은 뒤에만 칩(도착 뒤에 띄운다)
    var anchors by remember { mutableStateOf<List<FloatArray?>>(emptyList()) }
    var moved by remember { mutableStateOf(false) }
    val fade = remember { Animatable(1f) }
    val last = remember { arrayOfNulls<Stage3d>(1) }

    // 주행 중 상태가 바뀌면 200ms 페이드 한 번. 앞 상태는 시작할 때 바꿔 둔다: 페이드가 도중에 끊기고(A→B→A) 돌아와도 다시 끝까지 간다
    LaunchedEffect(stage) {
        val prev = last[0]; last[0] = stage
        if (stage.drive && prev != null && prev != stage) { fade.snapTo(0f); fade.animateTo(1f, tween(Tokens.DRIVE_FADE_MS,
                easing = androidx.compose.animation.core.LinearEasing)) } else fade.snapTo(1f)
    }
    // 칩 등장: 칩마다 90ms 늦게, 아래 10에서 올라오며 나타남, 선은 같이 페이드. 동작 줄이기면 바로
    val enter = Tokens.motion.getValue("enter")
    val reveal = remember(shown) { shown.map { Animatable(if (stage.reducedMotion) 1f else 0f) } }
    LaunchedEffect(reveal) {
        reveal.forEachIndexed { i, a -> launch { delay(i * 90L); a.animateTo(1f, tween(enter.ms, easing = enter.easing)) } }
    }

    BoxWithConstraints(modifier.onSizeChanged { size = it }) {
        // 바닥 빛(가장자리는 투명이라 화면 분위기 빛과 이어진다) + 접지 그림자 띠
        Box(Modifier.fillMaxSize().background(floorBrush(p)))
        if (posterOn() && posterImg != null) PosterImage(posterImg) { posterK }
        AndroidView(
            factory = { ctx -> CarView(ctx, interactive, intro = true).also { v ->   // 홈 무대: 프로세스에서 처음 한 번 바깥 모습 → 실내 X-ray 연출
                view = v
                v.onMoved = { moved = it }
                // 정지 그림을 걷는다: 같은 크기면 다음 장에 바로, 아니면 교차 페이드
                fun unveil() {
                    val exact = posterImg != null && size == IntSize(posterImg.width, posterImg.height)
                    if (posterFadeMs(stageNow, exact) == 0) { swap[0] = v; unveiled = true } else crossfade = true
                }
                v.onFirstFrame = {
                    // 이 장은 정지 그림만(알림이 그 장을 그리는 중에 와서 바로 먹는다). 시작 연출이면 앞 멈춤 동안(옅은 X-ray 셰이더 준비) 계속 덮고
                    // 회전 첫 장(바깥 모습 그대로)이 들어올 때 걷는다(onHoldEnd)
                    if (posterOn()) { v.alpha = 0f; if (!v.holding) unveil() }
                    load.firstFrame(v, hide = !poster)
                }
                v.onHoldEnd = { if (posterOn()) unveil() }
                v.onFailed = load::failed   // 실패면 정지 그림이 걷히고(posterShown) 실패 정지 표시
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
                v.onRender = {
                    if (shown.isNotEmpty()) shown.map { c -> c.anchorsIn(stage).mapNotNull(v::project).minByOrNull { it[2] } }
                        .let { if (!sameAnchors(anchors, it)) anchors = it }
                }
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
                Chip(c, Modifier.offset { IntOffset(xs[i].roundToInt(),
                        y.roundToInt()) }.graphicsLayer { alpha = k(); translationY = (1 - k()) * 10.dp.toPx() })
            }
        }
        if (interactive && !stage.drive) {
            // 처음엔 안내, 돌린 뒤엔 '처음 자리로'(80, 유리)
            if (!moved) Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(percent = 50)).background(p.surface.copy(alpha = 0.7f)).padding(horizontal = 20.dp),
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
        // 준비 표시는 정지 그림이 보이지 않을 때만(정지 그림이 덮는 시작 연출 정차 홈에서는 나오지 않는다)
        StageMarkLayer(load, reduced = stage.reducedMotion, drive = stage.drive, enabled = !posterOn())
    }
}

/** 정지 그림을 무대 가운데에 3D와 같은 배율로(CarMath.posterScale, 찍은 크기와 같으면 화소 그대로). [alpha]는 교차 페이드 때만 바뀐다. */
@Composable
private fun PosterImage(img: androidx.compose.ui.graphics.ImageBitmap, alpha: () -> Float) {
    Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }) {
        val s = kr.aigyeot.app.ui.car3d.CarMath.posterScale(View3d.HERO, img.width, img.height, size.width, size.height)
        val w = (img.width * s).roundToInt(); val h = (img.height * s).roundToInt()
        drawImage(img, dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()), dstSize = IntSize(w, h))
    }
}

/** 칩(높이 56, 반경 28, 유리, 아이콘 28 브랜드 글자색 + 글자 700 24). 가운데가 x에 오게. */
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

/** 무대 바닥(라이트·다크, radial 62%×58% 가운데). 바깥은 투명. */
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
