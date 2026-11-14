package kr.aigyeot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.aigyeot.app.R

// 디자인: 회색 바탕 + 흰 판, 브랜드 파랑은 행동(버튼·선택·스위치)에만, 숫자·진행은 먹색.
// 값은 Tokens(tokens.json 한 출처). 크기는 1px = 1dp(Connect-S)로 옮겼다.

/** 색 한 벌(라이트·다크). 이름은 tokens.json 그대로. */
@Immutable
class Palette(private val dark: Boolean) {
    private fun c(name: String) = Color(Tokens.color.getValue(name).let { if (dark) it.second else it.first })
    private fun g(name: String) = Tokens.glass.getValue(name).let { if (dark) it.second else it.first }.color
    val isDark = dark
    val bg = c("bg"); val stage = c("stage"); val surface = c("surface"); val surfaceRaised = c("surfaceRaised"); val line = c("line")
    val textPrimary = c("textPrimary"); val textSecondary = c("textSecondary"); val textTertiary = c("textTertiary")
    val accent = c("accent"); val accentInk = c("accentInk"); val onAccent = c("onAccent");
    val accentSoft = c("accentSoft"); val onAccentSoft = c("onAccentSoft")
    val glow = c("glow"); val child = c("child"); val childSoft = c("childSoft"); val childGlow = c("childGlow")
    val warning = c("warning"); val danger = c("danger"); val active = c("active"); val fill = c("fill"); val onFill = c("onFill")
    val cFeed = c("cFeed"); val cSleep = c("cSleep"); val cDiaper = c("cDiaper"); val cNote = c("cNote"); val cCar = c("cCar")
    val glassFill = g("fill"); val glassRim = g("rim"); val glassShade = g("shade")
    /** 옅은 먹색 면(글자색의 n%): 아이콘 버튼 5%, 버튼 6%, 누름 11%, 꺼진 스위치 22%. */
    fun ink(pct: Int) = textPrimary.copy(alpha = pct / 100f)
}

private val LightPalette = Palette(dark = false)
private val DarkPalette = Palette(dark = true)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Pretendard Variable 1.3.9(OFL, assets/licenses/Pretendard-OFL.txt). 굵기는 가변 축 wght로 고른다. */
private fun pretendard(w: Int) = Font(R.font.pretendard_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val AppFont: FontFamily = FontFamily(listOf(300, 400, 500, 600, 700).map(::pretendard))

/** 차 글자 위계(Tokens.carType). 한국어 줄바꿈은 낱말 단위(keep-all). */
@Immutable
object CarType {
    private fun t(name: String) = Tokens.carType.getValue(name).let { (s, l, w) ->
        TextStyle(fontFamily = AppFont, fontSize = s.sp, lineHeight = l.sp, fontWeight = FontWeight(w), lineBreak = LineBreak.Heading)
    }
    val headlineL = t("headlineL"); val headlineS = t("headlineS")
    val titleL = t("titleL"); val titleM = t("titleM"); val titleS = t("titleS")
    val bodyL = t("bodyL"); val bodyS = t("bodyS"); val label = t("label"); val numeral = t("numeral")
}

/** 모서리(Tokens.carRadius). */
object CarRadius {
    val s = Tokens.carRadius.getValue("rS").dp; val m = Tokens.carRadius.getValue("rM").dp
    val l = Tokens.carRadius.getValue("rL").dp; val xl = Tokens.carRadius.getValue("rXL").dp
}

/** Material 부품(입력칸·대화창)이 쓰는 글자도 차 위계로(24 미만 금지). */
private val M3Type = Typography(
    displayLarge = CarType.numeral, displayMedium = CarType.numeral, displaySmall = CarType.headlineL,
    headlineLarge = CarType.headlineL, headlineMedium = CarType.headlineS, headlineSmall = CarType.headlineS,
    titleLarge = CarType.titleL, titleMedium = CarType.titleM, titleSmall = CarType.titleS,
    bodyLarge = CarType.bodyL, bodyMedium = CarType.bodyS, bodySmall = CarType.bodyS,
    labelLarge = CarType.label, labelMedium = CarType.label, labelSmall = CarType.label,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = remember(p) {
        (if (p.isDark) darkColorScheme() else lightColorScheme()).copy(
            background = p.bg, surface = p.surface, surfaceVariant = p.surfaceRaised, onBackground = p.textPrimary, onSurface = p.textPrimary,
            surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
            surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface,
            onSurfaceVariant = p.textSecondary, primary = p.accentInk, onPrimary = if (p.isDark) p.bg else p.onAccent, outline = p.line, error = p.danger,
            // Material 부품이 primary를 글자색으로 쓰므로 글자용 accentInk(다크 #7AA5FF)를 준다(채움용 accent는 다크 바탕에서 대비 미달)
        )
    }
    MaterialTheme(colorScheme = scheme, typography = M3Type) {
        CompositionLocalProvider(LocalPalette provides p, LocalContentColor provides p.textPrimary) {
            Box(Modifier.fillMaxSize().background(p.bg)) { content() }
        }
    }
}
