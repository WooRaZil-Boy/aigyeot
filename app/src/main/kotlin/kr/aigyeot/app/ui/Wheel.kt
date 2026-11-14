package kr.aigyeot.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 바퀴 줄 높이(dp, Pleos Spinner 그림 Picker4·Picker7: 줄 간격 88). */
private const val WHEEL_ROW = 88

/**
 * 바퀴 판(Pleos Spinner 결, 그림에서 잰 값): 높이 384, 모서리 32, 바탕 bg, 좌우 안쪽 40, 열 사이 24(열 폭은 같게).
 * 선택 띠는 판 위에서 148, 높이 88, 좌우 16 안쪽, 모서리 24, textPrimary 5%(서 있고 글이 굴러간다). 위아래 60은 판 바탕색으로 흐린다.
 */
@Composable
fun WheelPanel(content: @Composable RowScope.() -> Unit) {
    val p = LocalPalette.current
    val fade = 60.dp
    Box(
        Modifier.fillMaxWidth().height((WHEEL_ROW * 4 + 32).dp).clip(RoundedCornerShape(32.dp)).background(p.bg)
            .drawWithContent {
                drawContent()
                val f = fade.toPx()
                drawRect(Brush.verticalGradient(listOf(p.bg, p.bg.copy(alpha = 0f)), 0f, f), size = androidx.compose.ui.geometry.Size(size.width, f))
                drawRect(Brush.verticalGradient(listOf(p.bg.copy(alpha = 0f), p.bg), size.height - f, size.height), topLeft = Offset(0f,
                        size.height - f), size = androidx.compose.ui.geometry.Size(size.width, f))
            },
    ) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp,
                top = WHEEL_BAND_TOP.dp).fillMaxWidth().height(WHEEL_ROW.dp).clip(RoundedCornerShape(24.dp)).background(p.ink(5)))
        Row(Modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), content = content)
    }
}

/** 판 위에서 선택 띠까지(dp): (384 − 88) / 2. */
private const val WHEEL_BAND_TOP = (WHEEL_ROW * 4 + 32 - WHEEL_ROW) / 2

/**
 * 바퀴 한 열(Pleos Spinner Contents): 열 높이 전체가 누름·끌기 영역(Pleos 'Touch / Drag controls'). 끌면 손가락을 따르고, 놓으면 플랫폼 감속으로
 * 미끄러지다 띠 가운데에 가장 가까운 줄에 멈춘다(돌지 않음, 처음·끝은 빈칸). 띠 밖 줄을 누르면 그 줄로 굴러간다(base 220ms, 동작 줄이기면 바로).
 * 띠 안 줄은 700 48 textPrimary, 밖은 400 48 textPrimary 32%(굵기·색은 띠 가운데를 지나는 순간 바뀜).
 * [selected]가 밖에서 바뀌면(예: 해를 올해로 바꿔 달이 줄어듦) 그 줄로 굴러간다. 띠 가운데 줄이 바뀔 때마다 [onSelect](미끄러지는 중에도, 그래서 '고르기'는 가장 가까운 줄).
 * 읽기(TalkBack): 열 하나가 노드 하나, 이름 [label], 값은 띠 줄 글, Slider와 같은 조절(한 칸씩).
 */
@Composable
fun <T> RowScope.Wheel(values: List<T>, selected: Int, label: String, text: (T) -> String, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { WHEEL_ROW.dp.toPx() }
    val reduced = reducedMotion()
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, (values.size - 1).coerceAtLeast(0)))
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 띠 가운데(줄 앞 = 띠 위)에 가장 가까운 줄. 항목 offset은 내용 시작(위 여백 148 뒤)부터라 띠에 선 줄은 offset 0이다
    val centered by remember(state) {
        androidx.compose.runtime.derivedStateOf {
            state.layoutInfo.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset.toFloat()) }?.index ?: state.firstVisibleItemIndex
        }
    }
    val pick by androidx.compose.runtime.rememberUpdatedState(onSelect)
    LaunchedEffect(state) { androidx.compose.runtime.snapshotFlow { centered }.collect { pick(it) } }
    val base = Tokens.motion.getValue("base")
    suspend fun go(i: Int) {
        val target = i.coerceIn(0, values.size - 1)
        if (reduced) { state.scrollToItem(target); return }
        val d = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.offset?.toFloat() ?: ((target - centered) * rowPx)
        state.animateScrollBy(d, tween(base.ms, easing = base.easing))
    }
    // 굴러가기는 이 열의 범위에서 돌린다: 굴러가는 동안 띠 줄이 바뀌어 [selected]가 따라 바뀌어도 효과가 다시 시작되며 굴러가기를 끊지 않게
    LaunchedEffect(selected, values.size) { if (selected in values.indices && selected != centered && !state.isScrollInProgress) scope.launch { go(selected) } }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics {
            contentDescription = label
            if (centered in values.indices) stateDescription = text(values[centered])
            progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(centered.toFloat(),
                    0f..(values.size - 1).coerceAtLeast(1).toFloat(), (values.size - 2).coerceAtLeast(0))
            setProgress { v -> scope.launch { go(kotlin.math.round(v).toInt()) }; true }
        },
        state = state,
        contentPadding = PaddingValues(vertical = WHEEL_BAND_TOP.dp),
        flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state,
                androidx.compose.foundation.gestures.snapping.SnapPosition.Start),
    ) {
        items(values.size) { i ->
            val on = i == centered
            Box(
                Modifier.fillMaxWidth().height(WHEEL_ROW.dp).clickable(remember { MutableInteractionSource() }, null) { scope.launch { go(i) } },
                Alignment.Center,
            ) {
                Text(text(values[i]), style = CarType.headlineS.copy(fontWeight = FontWeight(if (on) 700 else 400), lineHeight = 52.sp),
                    color = if (on) p.textPrimary else p.textPrimary.copy(alpha = 0.32f), maxLines = 1)
            }
        }
    }
}
