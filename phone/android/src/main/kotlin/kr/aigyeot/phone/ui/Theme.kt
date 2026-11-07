package kr.aigyeot.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 디자인 토큰(phone-care-plan 13장 이름·값 그대로, iOS `DesignSystem`과 같은 이름·같은 값). 11-06 사용자 결정: 청록 + Pleos 회색.

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

val LightPalette = Palette(
    bg = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEDEEF2),
    textPrimary = Color(0xFF131417),
    textSecondary = Color(0xFF4F525A),
    accent = Color(0xFF0E6D77),
    onAccent = Color(0xFFFFFFFF),
    feed = Color(0xFFB83C0A),
    sleep = Color(0xFF5146B5),
    diaper = Color(0xFF3F6E0D),
    note = Color(0xFF5F636B),
    car = Color(0xFF0057DE),
    warning = Color(0xFF9A5B00),
    danger = Color(0xFFC8211A),
)

val DarkPalette = Palette(
    bg = Color(0xFF131417),
    surface = Color(0xFF1D1E21),
    surfaceRaised = Color(0xFF2A2B30),
    textPrimary = Color(0xFFF7F8FA),
    textSecondary = Color(0xFFB9BCC3),
    accent = Color(0xFF5CC6D0),
    onAccent = Color(0xFF06282C), // 어둠에서는 어두운 글자(흰 글자는 대비 미달, 13장)
    feed = Color(0xFFFF9A6B),
    sleep = Color(0xFFADA6FF),
    diaper = Color(0xFFA6D66A),
    note = Color(0xFFA4A8AE),
    car = Color(0xFF5AA8FF),
    warning = Color(0xFFFFC24D),
    danger = Color(0xFFFF6D64),
)

/** 글자 단계(13장 크기/줄 높이 sp). `timer`는 경과가 흔들리지 않게 고정폭 숫자(tnum)다. */
object Type {
    val display = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val timer = TextStyle(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
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
    val rMedium = RoundedCornerShape(14.dp) // 카드
    val rLarge = RoundedCornerShape(22.dp) // 큰 버튼
}

private val LocalPalette = staticCompositionLocalOf { LightPalette }

/** 지금 화면의 색 토큰. */
object Tokens {
    val colors: Palette @Composable get() = LocalPalette.current
}

/** 앱 전체 테마. 토큰을 Material 색·글자에 얹어 기존 화면과 Material 부품도 같은 토큰을 따른다. 동적 색은 쓰지 않는다(8장). */
@Composable
fun AigyeotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        background = p.bg,
        onBackground = p.textPrimary,
        surface = p.surface,
        onSurface = p.textPrimary,
        surfaceVariant = p.surfaceRaised,
        onSurfaceVariant = p.textSecondary,
        surfaceTint = p.surface, // 높이에 따른 색 얹기를 쓰지 않는다
        // 카드·시트·탭 막대는 surface, 대화 상자는 surfaceRaised(iOS 카드도 surface).
        surfaceContainerLowest = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surfaceRaised,
        surfaceContainerHighest = p.surface,
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accent,
        secondary = p.accent, // 탭 막대 고른 글자(material3 1.4). 강조색은 하나(8장), iOS tint와 같음
        onSecondary = p.onAccent,
        onPrimaryContainer = p.onAccent,
        // 시각 고르기의 고른 오전·오후 칸 등 Material 기본 분홍(tertiary)이 남지 않게 강조색 하나로(8장).
        tertiary = p.accent,
        onTertiary = p.onAccent,
        tertiaryContainer = p.accent,
        onTertiaryContainer = p.onAccent,
        // 고른 칩·분할 버튼·탭 표시는 회색 바탕(탭의 강조색 점이 보이게, 8장 '강조색은 하나만').
        secondaryContainer = p.surfaceRaised,
        onSecondaryContainer = p.textPrimary,
        outline = p.note,
        outlineVariant = p.surfaceRaised,
        inverseSurface = p.textPrimary,
        inverseOnSurface = p.bg,
        inversePrimary = p.bg, // 스낵바 '되돌리기' 글자. 기본 보라색이 남지 않게(iOS와 같음)
        error = p.danger,
    )
    val base = Typography()
    val type = base.copy(
        displaySmall = Type.display,
        headlineSmall = Type.title,
        titleLarge = Type.title,
        titleMedium = Type.headline,
        bodyLarge = Type.body,
        bodySmall = Type.caption,
    )
    MaterialTheme(colorScheme = scheme, typography = type, shapes = Shapes(small = Radius.rSmall, medium = Radius.rMedium, large = Radius.rLarge)) { CompositionLocalProvider(LocalPalette provides p, content = content) }
}
