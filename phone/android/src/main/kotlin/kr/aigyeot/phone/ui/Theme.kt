package kr.aigyeot.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

// 디자인 토큰(phone-care-plan 13장 이름 그대로, iOS `DesignSystem`과 같은 이름).
// shortcut: 값은 8장 디자인 방향이 정해지기 전이라 지금 모양(Material 3 기본)에서 가져온다. 13장에 값이 적히면 여기 값만 바꾼다.

@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val feed: Color,
    val sleep: Color,
    val diaper: Color,
    val note: Color,
    val car: Color,
    val warning: Color,
    val danger: Color,
)

private fun from(c: ColorScheme) = Palette(
    bg = c.background,
    surface = c.surface,
    surfaceRaised = c.surfaceContainerHigh,
    textPrimary = c.onSurface,
    textSecondary = c.onSurfaceVariant,
    accent = c.primary,
    onAccent = c.onPrimary,
    feed = c.primary,
    sleep = c.tertiary,
    diaper = c.secondary,
    note = c.outline,
    car = c.inversePrimary,
    warning = c.tertiary,
    danger = c.error,
)

val LightPalette = from(lightColorScheme())
val DarkPalette = from(darkColorScheme())

/** 글자 단계. `timer`는 경과가 흔들리지 않게 고정폭 숫자(tnum)다. */
object Type {
    private val base = Typography()
    val display: TextStyle = base.displaySmall
    val title: TextStyle = base.headlineSmall
    val headline: TextStyle = base.titleLarge
    val body: TextStyle = base.bodyLarge
    val caption: TextStyle = base.bodyMedium
    val timer: TextStyle = base.displayLarge.copy(fontFeatureSettings = "tnum")
}

/** 간격(4의 배수). */
object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 24.dp
    val s6 = 32.dp
}

/** 모서리. */
object Radius {
    val rSmall = RoundedCornerShape(8.dp)
    val rMedium = RoundedCornerShape(12.dp)
    val rLarge = RoundedCornerShape(28.dp)
}

private val LocalPalette = staticCompositionLocalOf { LightPalette }

/** 지금 화면의 색 토큰. */
object Tokens {
    val colors: Palette @Composable get() = LocalPalette.current
}

/** 앱 전체 테마. 토큰을 Material 색에 얹어 기존 화면도 같은 토큰을 따른다. 동적 색은 쓰지 않는다(8장). */
@Composable
fun AigyeotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        background = p.bg,
        surface = p.surface,
        surfaceContainerHigh = p.surfaceRaised,
        onSurface = p.textPrimary,
        onSurfaceVariant = p.textSecondary,
        primary = p.accent,
        onPrimary = p.onAccent,
        error = p.danger,
    )
    MaterialTheme(colorScheme = scheme) { CompositionLocalProvider(LocalPalette provides p, content = content) }
}
