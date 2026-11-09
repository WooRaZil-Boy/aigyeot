package kr.aigyeot.phone.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 3D 차 카메라 자리(시안 js/car-stage.js PRESET 중 폰이 쓰는 둘): 오늘·차 탭 카드 `tile`, 첫 안내 `phone`. */
enum class CarPreset { TILE, PHONE }

/**
 * 3D 차 무대 자리. 실시간 3D(Filament)는 다음 단위에서 이 함수 안에 넣는다. 지금은 바닥 빛(시안 .car-tile 방사 그라데이션)만 그린다.
 */
@Composable
fun CarStage(modifier: Modifier, preset: CarPreset) {
    val p = Tokens.colors
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height * 0.85f)
        drawOval(
            Brush.radialGradient(listOf(Color.Black.copy(alpha = if (p.dark) 0.35f else 0.10f), Color.Transparent), c, size.width * 0.35f),
            Offset(c.x - size.width * 0.35f, c.y - size.height * 0.08f),
            Size(size.width * 0.7f, size.height * 0.16f),
        )
        if (preset == CarPreset.PHONE) Unit
    }
}
