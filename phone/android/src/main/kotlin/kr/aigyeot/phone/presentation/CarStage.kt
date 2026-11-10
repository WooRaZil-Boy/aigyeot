package kr.aigyeot.phone.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kr.aigyeot.phone.R
import kr.aigyeot.phone.presentation.car3d.CarPose
import kr.aigyeot.phone.presentation.car3d.CarScene
import kr.aigyeot.phone.presentation.car3d.CarTextureView

/** 3D 차 카메라 자리(시안 js/car3d.js PRESET 중 폰이 쓰는 둘): 오늘·차 탭 카드 `tile`, 첫 안내 `phone`. */
enum class CarPreset(internal val pose: CarPose) { TILE(CarPose.TILE), PHONE(CarPose.PHONE) }

/**
 * 실시간 3D 차(Filament + gltfio, 디자인 v6 시안 phone.js `data-car3d`). 가로로 끌면 돌고(감쇠), 돌린 뒤에는 '처음 자리로' 단추가 보인다.
 * 오른쪽 아래 작은 회전 표시는 '끌어서 돌려 보기'(시안 .car-tile .ct-img.live3d::after). 동작 줄이기면 끌기는 되지만 감쇠·되돌리기 이동 없이 바로 바뀐다.
 * 돌리는 양·방향·감쇠는 시안 OrbitControls 그대로(높이만큼 끌면 198도, 오른쪽으로 끌면 방위가 줄어듦, 남은 양의 0.09씩).
 * 대기 중에는 그리지 않는다(0프레임). 화면을 떠나면 보기를 지운다(엔진·모델은 앱 수명에 하나).
 */
@Composable
fun CarStage(modifier: Modifier, preset: CarPreset) {
    if (LocalInspectionMode.current) { Box(modifier); return }
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val context = LocalContext.current
    // 장면은 앱에 하나, 처음 한 번 만든다(무거운 계산은 작업 스레드). 다 될 때까지는 빈 무대.
    val scene by produceState<CarScene?>(null) { value = CarScene.load(context).await() }
    var view by remember { mutableStateOf<CarTextureView?>(null) }
    var moved by remember { mutableStateOf(false) }
    Box(modifier.semantics { contentDescription = "끌어서 돌려 보기" }) {
        val cs = scene ?: return@Box
        AndroidView(
            factory = { ctx -> CarTextureView(ctx, preset.pose, cs).also { v -> v.onMoved = { moved = it }; view = v } },
            modifier = Modifier.fillMaxSize().pointerInput(reduced) {
                detectHorizontalDragGestures(
                    onDragEnd = { view?.release() },
                    onDragCancel = { view?.release() },
                ) { change, dx -> change.consume(); view?.drag(dx) }
            },
            update = { it.dark = p.dark; it.reduced = reduced },
            onRelease = { it.destroy(); view = null },
        )
        AnimatedVisibility(moved, Modifier.align(Alignment.BottomEnd).padding(10.dp), enter = fadeIn(Motion.base.spec()), exit = fadeOut(Motion.base.spec())) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(p.tonal)
                    .pressable { view?.reset(animate = !reduced) }.semantics { contentDescription = "처음 자리로" },
                contentAlignment = Alignment.Center,
            ) { AIcon(R.drawable.ic_locate_fixed, Modifier.size(20.dp), p.textSecondary) }
        }
        if (!moved) Box(
            Modifier.align(Alignment.BottomEnd).padding(12.dp).size(26.dp).clip(CircleShape).graphicsLayer { alpha = 0.7f },
            contentAlignment = Alignment.Center,
        ) { AIcon(R.drawable.ic_rotate_3d, Modifier.size(16.dp), p.textTertiary) }
    }
}
