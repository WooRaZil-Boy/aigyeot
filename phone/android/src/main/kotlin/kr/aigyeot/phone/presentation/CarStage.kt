package kr.aigyeot.phone.presentation

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kr.aigyeot.phone.R
import kr.aigyeot.phone.presentation.car3d.CarPose
import kr.aigyeot.phone.presentation.car3d.CarScene
import kr.aigyeot.phone.presentation.car3d.CarTextureView

/** 3D 차 카메라 자리(폰이 쓰는 둘): 오늘·차 탭 카드 `tile`, 첫 안내 `phone`. */
enum class CarPreset(internal val pose: CarPose) { TILE(CarPose.TILE), PHONE(CarPose.PHONE) }

/** 3D 준비 표시: 아무것도 안 보임 · 도는 고리 · 정지 표시(동작 줄이기, 읽음) · 실패 정지 표시(읽지 않음). */
enum class StageMark { HIDDEN, RING, STILL, FAILED }

/** 설계값: 이만큼 지나도 첫 그림이 없을 때만 보이고, 한 번 보이면 [STAGE_MARK_MIN_MS]는 둔다. */
const val STAGE_MARK_DELAY_MS = 800L
const val STAGE_MARK_MIN_MS = 400L

/**
 * 3D 준비 표시 규칙(차 앱·iOS와 같은 규칙). 시각은 모두 무대를 붙인 때부터 잰 ms.
 * 실패하면 그때부터 실패 정지 표시. 첫 그림이 [STAGE_MARK_DELAY_MS] 안에 오면 끝까지 아무것도 없다.
 * 아니면 800ms부터 첫 그림과 '800 + 400' 중 늦은 때까지 고리(동작 줄이기면 정지 표시).
 */
fun stageMark(elapsedMs: Long, firstFrameMs: Long?, failedMs: Long?, reduced: Boolean): StageMark {
    if (failedMs != null && elapsedMs >= failedMs) return StageMark.FAILED
    if (elapsedMs < STAGE_MARK_DELAY_MS) return StageMark.HIDDEN
    if (firstFrameMs != null &&
        (firstFrameMs <= STAGE_MARK_DELAY_MS || elapsedMs >= maxOf(firstFrameMs, STAGE_MARK_DELAY_MS + STAGE_MARK_MIN_MS))
    ) {
        return StageMark.HIDDEN
    }
    return if (reduced) StageMark.STILL else StageMark.RING
}

/**
 * 시간만 흘러도 표시가 바뀌는 다음 때(ms). 없으면 null: 표시가 정해졌거나, 다음 바뀜이 첫 그림·실패 사건이라 시계가 아니라 그 사건을 기다린다.
 * 경계는 둘뿐이다: 800ms(나타남), 고리가 보인 뒤 '첫 그림과 800 + 400 중 늦은 때'(걷힘).
 */
fun stageMarkNextMs(elapsedMs: Long, firstFrameMs: Long?, failedMs: Long?): Long? {
    if (failedMs != null && elapsedMs >= failedMs) return null
    if (firstFrameMs != null && firstFrameMs <= STAGE_MARK_DELAY_MS) return null // 고리 없이 끝남
    if (elapsedMs < STAGE_MARK_DELAY_MS) return STAGE_MARK_DELAY_MS
    val hide = firstFrameMs?.let { maxOf(it, STAGE_MARK_DELAY_MS + STAGE_MARK_MIN_MS) } ?: return null // 첫 그림을 기다린다
    return hide.takeIf { elapsedMs < it }
}

/** 장면 준비 상태(준비 중 · 됨 · 실패). */
private sealed interface SceneLoad {
    data object Loading : SceneLoad
    data class Ready(val cs: CarScene) : SceneLoad
    data object Failed : SceneLoad
}

/**
 * 실시간 3D 차(Filament + gltfio). 가로로 끌면 돌고(감쇠), 돌린 뒤에는 '처음 자리로' 단추가 보인다.
 * 오른쪽 아래 작은 회전 표시는 '끌어서 돌려 보기'. 동작 줄이기면 끌기는 되지만 감쇠·되돌리기 이동 없이 바로 바뀐다.
 * 돌리는 양·방향·감쇠는 three.js OrbitControls와 같다(높이만큼 끌면 198도, 오른쪽으로 끌면 방위가 줄어듦, 남은 양의 0.09씩).
 * 대기 중에는 그리지 않는다(0프레임). 화면을 떠나면 보기를 지운다(엔진·모델은 앱 수명에 하나).
 * 첫 그림 전에는 무대 가운데에 준비 표시([stageMark])를 겹친다.
 */
@Composable
fun CarStage(modifier: Modifier, preset: CarPreset) {
    if (LocalInspectionMode.current) {
        Box(modifier)
        return
    }
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val context = LocalContext.current
    // 무대를 붙인 때부터 잰다. 첫 그림·실패 시각은 그 뒤 ms(화면 상태, 무대를 다시 붙이면 새로 잰다).
    val born = remember { SystemClock.uptimeMillis() }
    var firstFrameMs by remember { mutableStateOf<Long?>(null) }
    var failedMs by remember { mutableStateOf<Long?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    // 장면은 앱에 하나, 처음 한 번 만든다(무거운 계산은 작업 스레드). 실패하면 다음에 3D 칸을 열 때 다시 시도한다(CarScene.load).
    val load by produceState<SceneLoad>(SceneLoad.Loading) {
        value = CarScene.load(context).await()?.let { SceneLoad.Ready(it) } ?: SceneLoad.Failed.also { failedMs = SystemClock.uptimeMillis() - born }
    }
    // 다음 경계(800ms·걷힘)까지만 한 번씩 기다린다. 첫 그림·실패는 사건이라 키가 바뀌어 다시 잰다. 시각은 프레임 시계로 읽어,
    // 앱이 가려지면(프레임 없음) 함께 멈추고 다시 보일 때 잇는다(50ms마다 깨던 것을 없앰).
    LaunchedEffect(firstFrameMs, failedMs) {
        while (true) {
            val now = withFrameMillis { SystemClock.uptimeMillis() - born }
            elapsed = now
            val next = stageMarkNextMs(now, firstFrameMs, failedMs) ?: break
            delay(next - now)
        }
    }
    val mark = stageMark(elapsed, firstFrameMs, failedMs, reduced)
    var view by remember { mutableStateOf<CarTextureView?>(null) }
    var moved by remember { mutableStateOf(false) }
    // 고리가 보인 뒤 온 첫 그림(800ms 넘어): 3D를 숨겼다가 고리가 걷힐 때 360ms(enter)로 들인다. 숨기기는 첫 그림 알림 안에서 TextureView alpha로 한다:
    // 그 장을 그리는 중이라 차가 한 장도 비치지 않고, 보기는 계속 그려져 표면이 산다(Compose 층 alpha 0이면 보기를 그리지 않아 첫 그림이 오지 않을 수 있다).
    LaunchedEffect(firstFrameMs != null, mark == StageMark.HIDDEN) {
        val v = view ?: return@LaunchedEffect
        val ff = firstFrameMs ?: return@LaunchedEffect
        if (ff <= STAGE_MARK_DELAY_MS || mark != StageMark.HIDDEN) return@LaunchedEffect
        if (reduced) v.alpha = 1f else androidx.compose.animation.core.animate(v.alpha, 1f, animationSpec = Motion.enter.spec()) { a, _ -> v.alpha = a }
    }
    val loading = mark == StageMark.RING || mark == StageMark.STILL
    // 읽기 이름은 '우리 차 3D 그림'(끌어 돌리기는 화면 읽기로 쓸 수 없어 읽지 않는다. 처음 모습은 '처음 자리로' 단추). 준비 표시가 보이는 동안은 그 표시가 '3D 차 준비 중'을 읽는다.
    Box(modifier.semantics { if (!loading) contentDescription = "우리 차 3D 그림" }) {
        (load as? SceneLoad.Ready)?.cs?.let { cs ->
            AndroidView(
                factory = { ctx ->
                    CarTextureView(ctx, preset.pose, cs).also { v ->
                        v.onMoved = { moved = it }
                        v.onFirstFrame = {
                            val at = SystemClock.uptimeMillis() - born
                            android.util.Log.i("CarStage", "첫 그림 ${at}ms")
                            if (at > STAGE_MARK_DELAY_MS) v.alpha = 0f // 고리가 보이는 중
                            firstFrameMs = at
                        }
                        view = v
                    }
                },
                modifier = Modifier.fillMaxSize().pointerInput(reduced) {
                    detectHorizontalDragGestures(
                        onDragEnd = { view?.release() },
                        onDragCancel = { view?.release() }
                    ) { change, dx ->
                        change.consume()
                        view?.drag(dx)
                    }
                },
                update = {
                    it.dark = p.dark
                    it.reduced = reduced
                },
                onRelease = {
                    it.destroy()
                    view = null
                }
            )
        }
        StageMarkView(mark, reduced, Modifier.align(Alignment.Center))
        if (firstFrameMs == null) return@Box // 회전 표시·'처음 자리로'는 차가 보인 뒤에만
        AnimatedVisibility(moved, Modifier.align(Alignment.BottomEnd).padding(10.dp), enter = fadeIn(Motion.base.spec()), exit = fadeOut(Motion.base.spec())) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(p.tonal)
                    .pressable { view?.reset(animate = !reduced) }.semantics { contentDescription = "처음 자리로" },
                contentAlignment = Alignment.Center
            ) { AIcon(R.drawable.ic_locate_fixed, Modifier.size(20.dp), p.textSecondary) }
        }
        if (!moved) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(12.dp).size(26.dp).clip(CircleShape).graphicsLayer { alpha = 0.7f },
                contentAlignment = Alignment.Center
            ) { AIcon(R.drawable.ic_rotate_3d, Modifier.size(16.dp), p.textTertiary) }
        }
    }
}

/**
 * 준비 표시(세 앱 같은 값): 고리 지름 28·굵기 2.5, 바탕 `textPrimary` 10%, 호 `accentInk` ¼바퀴·둥근 끝, 1초에 한 바퀴 일정하게.
 * 정지 표시는 차 선 아이콘 28 `textTertiary` 50%. 나타남·사라짐 220ms(`base`), 동작 줄이기면 바로. 실패 표시는 읽지 않는다.
 */
@Composable
private fun StageMarkView(mark: StageMark, reduced: Boolean, modifier: Modifier) {
    val p = Tokens.colors
    val fade = if (reduced) snap<Float>() else Motion.base.spec()
    Box(modifier) {
        AnimatedVisibility(mark == StageMark.RING, enter = fadeIn(fade), exit = fadeOut(fade)) {
            // 한 바퀴 1초 일정한 속도. 다시 그리는 횟수는 초당 RING_FPS번(차 앱 듣는 중 점과 같은 방법): 매 장 돌리면 창 전체를 초당 60장 다시 그려,
            // 에뮬레이터(SwiftShader)에서 같은 CPU로 그리는 3D 첫 그림이 1.4초 → 4.8~11.6초로 늦어졌다(콜드 스타트 실측). 화면을 떠나면 프레임 시계와 함께 멈춘다.
            val turn by produceState(0f) {
                val t0 = androidx.compose.runtime.withFrameMillis { it }
                while (true) {
                    val now = androidx.compose.runtime.withFrameMillis { it }
                    value = ((now - t0) % 1_000L) * 0.36f
                    delay(1000L / RING_FPS)
                }
            }
            Canvas(
                Modifier.size(28.dp).semantics {
                    contentDescription = STAGE_LOADING
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                }
            ) {
                val w = 2.5.dp.toPx()
                val inset = w / 2
                val arcSize = Size(size.width - w, size.height - w)
                drawCircle(p.textPrimary.copy(alpha = 0.1f), radius = (size.minDimension - w) / 2, style = Stroke(w))
                rotate(turn) {
                    drawArc(p.accentInk, -90f, 90f, false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(w, cap = StrokeCap.Round))
                }
            }
        }
        AnimatedVisibility(mark == StageMark.STILL || mark == StageMark.FAILED, enter = fadeIn(fade), exit = fadeOut(fade)) {
            val read = mark == StageMark.STILL
            Box(
                Modifier.size(28.dp).semantics {
                    if (read) {
                        contentDescription = STAGE_LOADING
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    }
                }
            ) {
                AIcon(R.drawable.ic_car_front, Modifier.size(28.dp), p.textTertiary.copy(alpha = 0.5f))
            }
        }
    }
}

/** 준비 고리를 다시 그리는 횟수(초당). */
private const val RING_FPS = 15

/** design.stage.loading. */
private const val STAGE_LOADING = "3D 차 준비 중"
