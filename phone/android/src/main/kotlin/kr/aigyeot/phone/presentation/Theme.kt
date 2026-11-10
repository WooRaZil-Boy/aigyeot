package kr.aigyeot.phone.presentation

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kr.aigyeot.phone.R

// 디자인 토큰(디자인 v6 확정, 시안 tokens.json의 폰 값). 같은 이름·같은 값을 iOS가 쓴다.
// 공개 사본 shared/src/test/resources/design/tokens.json과 TokensTest가 값을 대조한다.
// 원칙(한국 앱 결): 회색 바탕 + 흰 블록, 숫자·진행은 먹색, 브랜드 파랑(accent)은 행동(버튼·선택·스위치)에만,
// 아이의 신호는 주황(child). 데이터 색은 잠·수유·차에서 울음 세 칸, 기저귀·메모는 회색.

@Immutable
data class Palette(
    val bg: Color,
    val stage: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val line: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentInk: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val glow: Color,
    val child: Color,
    val childSoft: Color,
    val childGlow: Color,
    val warning: Color,
    val danger: Color,
    val active: Color,
    val fill: Color,
    val onFill: Color,
    val cFeed: Color,
    val cSleep: Color,
    val cDiaper: Color,
    val cNote: Color,
    val cCar: Color,
    val dark: Boolean,
)

/**
 * 회색 바탕(bg) 위 옅은 판(톤 단추, 첫 안내 아이콘 칸, 3D '처음 자리로' 원, 타이머 고리). 라이트의 surfaceRaised는 bg와 같은 #F2F4F6이라
 * 보이지 않아 먹색 8%(시안 .pbtn.ton 7%·세그먼트 판과 같은 결), 다크는 surfaceRaised. 흰 블록 위 판은 surfaceRaised 그대로(iOS DS.tonal과 같음).
 */
val Palette.tonal: Color get() = if (dark) surfaceRaised else textPrimary.copy(alpha = 0.08f)

/** 세그먼트 고른 칸(시안 .segp .sthumb: 라이트 흰 면, 다크 .t-dark .segp .sthumb #3A3F46). */
val Palette.segThumb: Color get() = if (dark) Color(0xFF3A3F46) else surface

/** 첫 안내 무대 가운데 빛(시안 .intro-hero: 라이트 stage에 흰빛 60%, 다크 #232931). */
val Palette.stageGlow: Color get() = if (dark) Color(0xFF232931) else lerp(stage, Color.White, 0.6f)

/** 3D 실내 앰비언트 선 파랑(tokens.json 차 빛 glow). 라이트·다크 같은 빛이다. */
val CarGlow: Color get() = LightPalette.glow

val LightPalette = Palette(
    bg = Color(0xFFF2F4F6),
    stage = Color(0xFFE9ECEF),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF2F4F6),
    line = Color(0x14191F28),
    textPrimary = Color(0xFF191F28),
    textSecondary = Color(0xFF4E5968),
    textTertiary = Color(0xFF5D6673),
    accent = Color(0xFF2867E6),
    accentInk = Color(0xFF2867E6),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEAF1FF),
    onAccentSoft = Color(0xFF1D4FC4),
    glow = Color(0xFF7AA5FF),
    child = Color(0xFFC2410C),
    childSoft = Color(0xFFFFF1E8),
    childGlow = Color(0xFFFF9A5C),
    warning = Color(0xFFB45309),
    danger = Color(0xFFD12F34),
    active = Color(0xFF12A150),
    fill = Color(0xFF2B2E33),
    onFill = Color(0xFFFFFFFF),
    cFeed = Color(0xFF1BAF7A),
    cSleep = Color(0xFF9B87F5),
    cDiaper = Color(0xFF8B95A1),
    cNote = Color(0xFF8B95A1),
    cCar = Color(0xFFEB6834),
    dark = false,
)

val DarkPalette = Palette(
    bg = Color(0xFF0F1013),
    stage = Color(0xFF141519),
    surface = Color(0xFF1B1C21),
    surfaceRaised = Color(0xFF25262C),
    line = Color(0x14FFFFFF),
    textPrimary = Color(0xFFF2F3F5),
    textSecondary = Color(0xFFB0B8C1),
    textTertiary = Color(0xFF8B95A1),
    accent = Color(0xFF2867E6),
    accentInk = Color(0xFF7AA5FF),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF18233A),
    onAccentSoft = Color(0xFFA9C4FF),
    glow = Color(0xFF7AA5FF),
    child = Color(0xFFFF8F55),
    childSoft = Color(0xFF33211A),
    childGlow = Color(0xFFFF9A5C),
    warning = Color(0xFFF5C451),
    danger = Color(0xFFFF6B6B),
    active = Color(0xFF34C77B),
    fill = Color(0xFFE0E1E6),
    onFill = Color(0xFF131417),
    cFeed = Color(0xFF199E70),
    cSleep = Color(0xFF9580EE),
    cDiaper = Color(0xFF6B7684),
    cNote = Color(0xFF6B7684),
    cCar = Color(0xFFD95926),
    dark = true,
)

/** Pretendard Variable 1.3.9(OFL, res/font). 굵기마다 가변 축 `wght`를 그 값으로 둔다. */
private fun pretendard(w: Int) = Font(R.font.pretendard_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Pretendard = FontFamily(listOf(300, 400, 500, 600, 650, 700).map(::pretendard))

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Pretendard,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
    // 줄 높이 안에서 글자를 가운데에 둔다(웹 line-height와 같은 놓임).
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    // 한국어는 낱말 단위로 줄을 바꾼다(시안 word-break: keep-all, README 4.4). API 33+에서 먹고 그 아래는 기본 줄바꿈.
    lineBreak = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase),
    // 기기 언어가 한국어가 아니어도 한국어 규칙으로 줄을 바꾼다(낱말 단위 줄바꿈은 글자의 로케일을 본다).
    localeList = LocaleList("ko-KR"),
)

/** 글자 단계(tokens.json type.phone: 크기/줄 높이/굵기). 자간은 시안 CSS의 em 값. `timer`는 고정폭 숫자(tnum). */
object Type {
    val hero = style(30, 38, 700, -0.03)
    val display = style(26, 34, 700, -0.03)
    val title = style(20, 28, 700, -0.02)
    val headline = style(17, 24, 600)
    val body = style(16, 24, 400)
    val caption = style(13, 18, 500)
    val timer = style(64, 72, 650, -0.03).copy(fontFeatureSettings = "tnum")

    // 부품 글자(시안 phone.css 값 그대로). 두 곳 이상이 같은 역할로 쓰는 것만 여기 두고, 한 부품만 쓰는 크기는 그 부품 안에 둔다.
    /** 목록·설정 행 제목, 진행 중 판·차 타일 머리(.lrow-p b 600 16/22). */
    val rowTitle = body.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    /** 판 안 굵은 한 줄(16/24·600). */
    val strong = body.copy(fontWeight = FontWeight.SemiBold)
    /** 행 둘째 줄·블록 부제·리포트 작은 글(14/20·400). */
    val sub = body.copy(fontSize = 14.sp, lineHeight = 20.sp)
    /** 흐린 라벨·블록 링크·짧은 안내(.label 14/20·500). */
    val label = caption.copy(fontSize = 14.sp, lineHeight = 20.sp)
    /** 화면 안 오류 한 줄(14·400). */
    val error = body.copy(fontSize = 14.sp)
    /** 값 옆 보조 글·키(15/22·400). */
    val meta = body.copy(fontSize = 15.sp, lineHeight = 22.sp)
    /** 세그먼트·칩·방법 이름(15/20·600). */
    val option = body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    /** 시트 행 이름(.sheet .row 15.5·500). */
    val rowLabel = body.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
    /** 블록 밖 작은 안내(.foot-note 12.5/18·500). */
    val footNote = caption.copy(fontSize = 12.5.sp)
    /** 기록 줄 둘째 줄·메모(12.5/17). */
    val logSub = caption.copy(fontSize = 12.5.sp, lineHeight = 17.sp)
    /** 숫자 칸 아래 글(.kpi small 12/16). */
    val kpiSub = caption.copy(fontSize = 12.sp, lineHeight = 16.sp)
    /** 흐린 한 줄(13/18·400). */
    val hint = caption.copy(fontWeight = FontWeight.Normal)
    /** 인계 알림 글(17/25·600). */
    val alert = headline.copy(lineHeight = 25.sp)
    /** 위 막대 제목·접힌 큰 제목(.p-android .ph-mini 22/28·600). */
    val barTitle = TextStyle(fontFamily = Pretendard, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    /** 차트 말풍선(.ch-tip 12.5/16·600). */
    val tip = TextStyle(fontFamily = Pretendard, fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
}

/** 모서리(tokens.json radius.phone). 흰 블록은 rL(20). */
object Radius {
    val rS: Dp = 8.dp
    val rM: Dp = 12.dp
    val rL: Dp = 20.dp
    val rXL: Dp = 28.dp
    val small = RoundedCornerShape(rS)
    val medium = RoundedCornerShape(rM)
    val large = RoundedCornerShape(rL)
    val xl = RoundedCornerShape(rXL)
    /** 알림창(확인·겹침·가져오기). */
    val dialog = RoundedCornerShape(24.dp)
    /** 단추·띠·스낵바·메뉴(.pbtn 16). */
    val control = RoundedCornerShape(16.dp)
    /** 입력 칸·세그먼트 판·탭 칸(14). */
    val field = RoundedCornerShape(14.dp)
    /** 판 안 작은 칸: 세그먼트 고른 칸·값 칩·말풍선·띠 행동(10). 그림자처럼 그려 쓰는 곳은 [rInner]. */
    val rInner: Dp = 10.dp
    val inner = RoundedCornerShape(rInner)
}

/** 시간 곡선 하나(tokens.json motion: ms + cubic-bezier). linear는 (0,0,1,1). */
data class Curve(val ms: Int, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val easing: Easing get() = if (x1 == 0f && y1 == 0f && x2 == 1f && y2 == 1f) LinearEasing else CubicBezierEasing(x1, y1, x2, y2)
    fun <T> spec(delayMs: Int = 0): TweenSpec<T> = tween(ms, delayMs, easing)
}

/** 모션 표(시안 README 4.7). 시스템 애니메이션이 꺼져 있으면 바로 바뀐다. */
object Motion {
    val press = Curve(90, 0f, 0f, 1f, 1f)
    val fast = Curve(150, 0.4f, 0f, 0.2f, 1f)
    val base = Curve(220, 0.4f, 0f, 0.2f, 1f)
    val enter = Curve(360, 0.2f, 0.8f, 0.2f, 1f)
    val exit = Curve(180, 0.4f, 0f, 1f, 1f)
    val camera = Curve(1100, 0.65f, 0f, 0.35f, 1f)
    val light = Curve(900, 0.4f, 0f, 0.2f, 1f)
    val ripple = Curve(2400, 0.2f, 0.6f, 0.35f, 1f)

    // 부품 모션(시안 css·js 값 그대로, 위 곡선을 시간만 바꿔 쓴다).
    /** 화면 교체 때 블록이 한 줄씩 올라오는 시작 지연과 간격(core.js enter: startDelay 0.06·stagger 0.05, 열째 줄부터 같이). */
    const val STAGGER_DELAY_MS = 60
    const val STAGGER_STEP_MS = 50
    const val STAGGER_MAX_MS = 500
    /** 차트가 왼쪽부터 드러남(charts.js grow 0.9초 cb(.65,0,.35,1)). */
    val reveal = camera.copy(ms = 900)
    /** 막대 하나가 자람(0.6초 enter 곡선)과 막대 사이 간격(0.04초, 8개마다 다시). */
    val barGrow = enter.copy(ms = 600)
    const val BAR_STEP_MS = 40
    /** 방법별 성적 점이 튀는 간격(0.02초). */
    const val DOT_STEP_MS = 20L
    /** 차트 말풍선(.ch-tip 120ms linear). */
    val tip = press.copy(ms = 120)
    /** 새 수유가 들어올 때 판 둘레 빛(.flash 1.2초 base 곡선). */
    val flash = base.copy(ms = 1200)
    /** 살아 있는 점(live 1.8초, 앞 60% 동안 퍼짐). */
    val live = base.copy(ms = 1800)
    /** 녹음 소리 결 한 번(.wave i 1.2초). */
    val wave = base.copy(ms = 1200)
    /** 타이머 초침 한 바퀴(60초 linear). */
    val sweep = press.copy(ms = 60_000)
    /** 인계 배너가 나감(.banner.out 260ms exit 곡선). */
    val bannerOut = exit.copy(ms = 260)
    /** 숫자 굴림의 새 글자 나타남·옛 글자 사라짐. */
    val rollIn = base.copy(ms = 120)
    val rollOut = base.copy(ms = 90)
}

/** 스프링 하나(tokens.json spring: Compose 감쇠비·강성 그대로). */
data class SpringToken(val damping: Float, val stiffness: Float) {
    fun <T> spec(): FiniteAnimationSpec<T> = spring(damping, stiffness)
}

object Springs {
    val snappy = SpringToken(0.86f, 600f)
    val smooth = SpringToken(1.0f, 385f)
    val bouncy = SpringToken(0.78f, 420f)

    /** 숫자 굴림(README 4.7 roll, Dorr): .85·632. */
    val roll = SpringToken(0.85f, 632f)
}

private val LocalPalette = staticCompositionLocalOf { LightPalette }

/** 지금 화면의 색 토큰. */
object Tokens {
    val colors: Palette @Composable get() = LocalPalette.current
}

/** 앱 전체 테마. 토큰을 Material 색·글자에 얹어 남은 Material 부품(대화 상자·시각 고르기·시트)도 같은 토큰을 따른다. 동적 색은 쓰지 않는다. */
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
        onSurfaceVariant = p.textTertiary,
        surfaceTint = p.surface, // 높이에 따른 색 얹기를 쓰지 않는다
        surfaceContainerLowest = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.surfaceRaised,
        surfaceDim = p.bg,
        surfaceBright = p.surface,
        // 남은 Material 글자 버튼(대화 상자 '취소' 등)은 primary를 글자색으로 쓴다: 글자 짝 대비를 지키게 accentInk(다크 #7AA5FF).
        // 그 위에 얹는 글자(시각 고르기의 고른 칸)는 다크에서 어두운 바탕색으로 대비를 맞춘다.
        primary = p.accentInk,
        onPrimary = if (p.dark) p.bg else p.onAccent,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.onAccentSoft,
        secondary = p.accentInk,
        onSecondary = if (p.dark) p.bg else p.onAccent,
        secondaryContainer = p.surfaceRaised,
        onSecondaryContainer = p.textPrimary,
        tertiary = p.accentInk,
        onTertiary = if (p.dark) p.bg else p.onAccent,
        tertiaryContainer = p.accentSoft,
        onTertiaryContainer = p.onAccentSoft,
        outline = p.textTertiary,
        outlineVariant = p.line,
        inverseSurface = p.textPrimary,
        inverseOnSurface = p.surface,
        inversePrimary = p.surface, // 스낵바 '되돌리기' 글자(먹색 띠 위 흰 글자)
        scrim = Color(0x520A0C0E), // 시안 .scrim rgba(10,12,14,.32)
        error = p.danger,
    )
    val type = Typography().run {
        copy(
            displayLarge = displayLarge.copy(fontFamily = Pretendard),
            displayMedium = displayMedium.copy(fontFamily = Pretendard),
            displaySmall = Type.display,
            headlineLarge = headlineLarge.copy(fontFamily = Pretendard),
            headlineMedium = headlineMedium.copy(fontFamily = Pretendard),
            headlineSmall = Type.title,
            titleLarge = Type.title,
            titleMedium = Type.headline,
            titleSmall = titleSmall.copy(fontFamily = Pretendard),
            bodyLarge = Type.body,
            bodyMedium = Type.meta,
            bodySmall = Type.caption,
            labelLarge = labelLarge.copy(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold),
            labelMedium = labelMedium.copy(fontFamily = Pretendard),
            labelSmall = labelSmall.copy(fontFamily = Pretendard),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = type, shapes = Shapes(small = Radius.small, medium = Radius.medium, large = Radius.large, extraLarge = Radius.xl)) {
        CompositionLocalProvider(LocalPalette provides p, content = content)
    }
}
