package kr.aigyeot.app.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kr.aigyeot.app.R

// 3D 준비 표시(세 앱 같은 규칙·값): 첫 그림 전에 3D 칸 가운데에 직접 그린 고리.
// 첫 그림 신호는 CarView.onFirstFrame(TextureView에 첫 장이 들어온 때), 실패는 CarView.onFailed(CarEngine 준비 실패).

/** 아무것도 안 보임 · 도는 고리 · 정지 표시(동작 줄이기·주행 중, 읽음) · 실패 정지 표시(읽지 않음). */
enum class StageMark { HIDDEN, RING, STILL, FAILED }

/** 설계값: 이만큼 지나도 첫 그림이 없을 때만 보이고, 한 번 보이면 [STAGE_MARK_MIN_MS]는 둔다. */
const val STAGE_MARK_DELAY_MS = 800L
const val STAGE_MARK_MIN_MS = 400L

/** 고리를 다시 그리는 횟수(초당, 세 앱 같음). 한 바퀴 1초를 이만큼 끊어 한 장에 360/15 = 24°. */
const val RING_FPS = 15

/**
 * 준비 표시 규칙(안드로이드 폰 `stageMark`와 같은 식). 시각은 모두 3D 칸을 붙인 때부터 잰 ms.
 * 실패하면 그때부터 실패 정지 표시. 첫 그림이 800ms 안에 오면 끝까지 아무것도 없다.
 * 아니면 800ms부터 첫 그림과 '800 + 400' 중 늦은 때까지 고리, [still](동작 줄이기·주행 중)이면 고리 대신 정지 표시.
 */
fun stageMark(elapsedMs: Long, firstFrameMs: Long?, failedMs: Long?, still: Boolean): StageMark {
    if (failedMs != null && elapsedMs >= failedMs) return StageMark.FAILED
    if (elapsedMs < STAGE_MARK_DELAY_MS) return StageMark.HIDDEN
    if (firstFrameMs != null && (firstFrameMs <= STAGE_MARK_DELAY_MS || elapsedMs >= maxOf(firstFrameMs,
            STAGE_MARK_DELAY_MS + STAGE_MARK_MIN_MS))) return StageMark.HIDDEN
    return if (still) StageMark.STILL else StageMark.RING
}

/** 표시가 더는 바뀌지 않는가(시계를 멈춰도 되는가). */
internal fun stageMarkSettled(elapsedMs: Long, firstFrameMs: Long?, failedMs: Long?): Boolean =
    (failedMs != null && elapsedMs >= failedMs) ||
        (firstFrameMs != null && (firstFrameMs <= STAGE_MARK_DELAY_MS || elapsedMs >= maxOf(firstFrameMs, STAGE_MARK_DELAY_MS + STAGE_MARK_MIN_MS)))

/**
 * 새 알림(첫 그림·실패) 없이 표시가 바뀔 다음 시각(칸을 붙인 때부터 ms): 800ms 나타남, 고리가 보였으면 최소 표시 끝. 알림이 와야만 바뀌면 null(기다리지 않는다).
 * 시계는 이 경계에서만 깬다(50ms마다 돌지 않는다).
 */
internal fun stageMarkNextMs(elapsedMs: Long, firstFrameMs: Long?, failedMs: Long?): Long? = when {
    stageMarkSettled(elapsedMs, firstFrameMs, failedMs) -> null
    elapsedMs < STAGE_MARK_DELAY_MS -> STAGE_MARK_DELAY_MS
    firstFrameMs != null -> maxOf(firstFrameMs, STAGE_MARK_DELAY_MS + STAGE_MARK_MIN_MS)
    else -> null
}

/**
 * 3D 칸 하나의 준비 시각(칸을 붙인 때부터 ms, 화면 상태: 칸을 다시 붙이면 새로 잰다). 첫 그림·실패는 CarView 알림이 넣는다.
 * 고리가 보이는 중에 온 첫 그림이면 3D 보기를 숨겨 두었다가([held]) 표시가 걷힐 때 [StageMarkLayer]가 들인다.
 * 숨기기는 첫 그림 알림 안에서 View alpha로 한다: 그 장을 그리는 중이라 차가 한 장도 비치지 않고, 보기는 계속 그려진다(안드로이드 폰과 같은 방법).
 */
@Stable
internal class StageLoad {
    private val born = SystemClock.uptimeMillis()
    var firstFrameMs by mutableStateOf<Long?>(null); private set
    var failedMs by mutableStateOf<Long?>(null); private set
    var held by mutableStateOf<android.view.View?>(null); private set

    fun elapsed() = SystemClock.uptimeMillis() - born

    /** [hide]: 표시가 보일 수 있는 칸인가(정지 그림이 덮는 홈 무대는 숨기지 않는다). */
    fun firstFrame(v: android.view.View, hide: Boolean) {
        val at = elapsed()
        if (hide && at > STAGE_MARK_DELAY_MS) { v.alpha = 0f; held = v }
        firstFrameMs = at
    }

    fun failed() { if (failedMs == null) failedMs = elapsed() }

    fun entered() { held = null }
}

/** 표시·3D의 나타남과 사라짐: 동작 줄이기 바로, 주행 중 200ms 한 번(선형, 주행 중 규칙), 아니면 [token]. */
internal fun <T> stageFade(reduced: Boolean, drive: Boolean, token: String): FiniteAnimationSpec<T> = when {
    reduced -> snap()
    drive -> tween(Tokens.DRIVE_FADE_MS, easing = LinearEasing)
    else -> Tokens.motion.getValue(token).let { tween(it.ms, easing = it.easing) }
}

/**
 * 3D 칸 가운데 준비 표시(차 값: 고리 지름 64·굵기 5, 바탕 `textPrimary` 10%, 호 `accentInk` ¼바퀴·둥근 끝, 1초에 한 바퀴를 초당 [RING_FPS]장으로 끊어 그림.
 * 정지 표시는 차 선 아이콘 64 `textTertiary` 50%). 나타남·사라짐 220ms(`base`), 고리 뒤에 숨겨 둔 3D는 360ms(`enter`)로 들어온다.
 * [enabled]가 거짓이면(홈 무대 정지 그림이 덮을 때) 아무것도 그리지 않고 시계도 돌리지 않는다.
 */
@Composable
internal fun BoxScope.StageMarkLayer(load: StageLoad, reduced: Boolean, drive: Boolean, enabled: Boolean = true) {
    if (!enabled) return
    val p = LocalPalette.current
    var elapsed by remember { mutableLongStateOf(load.elapsed()) }
    // 시계는 다음 경계(800ms 나타남·최소 표시 끝)에서만 깬다. 첫 그림·실패 알림이 오면 열쇠가 바뀌어 다시 잰다.
    // 깨면 먼저 프레임 시계를 기다린다(withFrameMillis, 정차 홈 듣는 점과 같은 방법): 앱이 가려지면 프레임 시계가 멈춰 그 자리에서 쉬고, 다시 보이면 이어 간다
    LaunchedEffect(load.firstFrameMs, load.failedMs) {
        while (true) {
            withFrameMillis { }
            elapsed = load.elapsed()
            val next = stageMarkNextMs(elapsed, load.firstFrameMs, load.failedMs) ?: break
            delay(next - elapsed)
        }
    }
    val mark = stageMark(elapsed, load.firstFrameMs, load.failedMs, still = reduced || drive)
    // 고리가 걷히면 숨겨 둔 3D를 들인다
    LaunchedEffect(load.held, mark == StageMark.HIDDEN) {
        val v = load.held ?: return@LaunchedEffect
        if (mark != StageMark.HIDDEN) return@LaunchedEffect
        androidx.compose.animation.core.animate(v.alpha, 1f, animationSpec = stageFade(reduced, drive, "enter")) { a, _ -> v.alpha = a }
        load.entered()
    }
    val fade = stageFade<Float>(reduced, drive, "base")
    Box(Modifier.align(Alignment.Center)) {
        AnimatedVisibility(mark == StageMark.RING, enter = fadeIn(fade), exit = fadeOut(fade)) {
            // 다시 그리는 횟수는 초당 RING_FPS번(정차 홈 듣는 점과 같은 방법): 매 장 돌리면 창 전체를 초당 60장 다시 그려 같은 CPU의 3D 준비가 늦어진다.
            // 화면을 떠나면 프레임 시계와 함께 멈춘다
            val step by produceState(0) {
                val t0 = withFrameMillis { it }
                while (true) {
                    value = (((withFrameMillis { it } - t0) * RING_FPS / 1000) % RING_FPS).toInt()
                    delay(1000L / RING_FPS)
                }
            }
            Canvas(Modifier.size(64.dp).semantics { contentDescription = STAGE_LOADING; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
                val w = 5.dp.toPx()
                drawCircle(p.textPrimary.copy(alpha = 0.1f), radius = (size.minDimension - w) / 2, style = Stroke(w))
                rotate(step * 360f / RING_FPS) {
                    drawArc(p.accentInk, -90f, 90f, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                            style = Stroke(w, cap = StrokeCap.Round))
                }
            }
        }
        AnimatedVisibility(mark == StageMark.STILL || mark == StageMark.FAILED, enter = fadeIn(fade), exit = fadeOut(fade)) {
            val read = mark == StageMark.STILL   // 실패 표시는 읽지 않는다
            Box(
                Modifier.size(64.dp).semantics {
                    if (read) { contentDescription = STAGE_LOADING; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
                },
            ) {
                LineIcon(R.drawable.ic_car_front, 64.dp, p.textTertiary.copy(alpha = 0.5f))
            }
        }
    }
}

/** design.stage.loading(세 앱 같음): 읽기 이름만, 보이는 글은 없다. */
internal const val STAGE_LOADING = "3D 차 준비 중"
