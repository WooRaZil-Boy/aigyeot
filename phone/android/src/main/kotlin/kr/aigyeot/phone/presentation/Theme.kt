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
val Palette.segmentThumb: Color get() = if (dark) Color(0xFF3A3F46) else surface

/** 첫 안내 무대 가운데 빛(시안 .intro-hero: 라이트 stage에 흰빛 60%, 다크 #232931). */
val Palette.stageGlow: Color get() = if (dark) Color(0xFF232931) else lerp(stage, Color.White, 0.6f)

/** 3D 차 색(iOS `CarPaint`와 같은 이름). 재질 표는 car3d `SPECS` 한 곳에 있다. */
object CarPaint {
    /** 실내 앰비언트 선 빛(tokens.json 차 빛 glow). 라이트·다크 같은 빛이다. */
    val ambientGlow: Color get() = LightPalette.glow
}

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

private fun style(size: Double, line: Int, weight: Int, tracking: Double = 0.0) = TextStyle(
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
    val hero = style(30.0, 38, 700, -0.03)
    val display = style(26.0, 34, 700, -0.03)
    val title = style(20.0, 28, 700, -0.02)
    val headline = style(17.0, 24, 600)
    val body = style(16.0, 24, 400)
    val caption = style(13.0, 18, 500)
    val timer = style(64.0, 72, 650, -0.03).copy(fontFeatureSettings = "tnum")

    // 글자 역할(iOS `DesignSystem.TextRole`과 같은 이름·값). 단계(위 7개)와 같은 조합은 단계를 쓰고, 시안 phone.css가 단계 사이 크기를 쓰는 자리만 역할이다.
    // 크기·굵기 조합 하나에 이름 하나(시험이 겹침을 막는다). 화면은 단계·역할로만 고르고 크기·굵기를 직접 쓰지 않는다(고정폭 숫자·자간·색은 덧붙여도 된다).
    /** 타임라인 시간 머리(.tl .hr span 11·600). */
    val hourMark = style(11.0, 14, 600)
    /** 숫자 칸 부제, 날짜 띠 요일(.datestrip span 12·500). */
    val micro = style(12.0, 16, 500)
    /** 줄 부제·덧붙임(.tl .ev span, .foot-note 12.5·500). */
    val footnote = style(12.5, 17, 500)
    /** 배지·말풍선(.badge, .ch-tip 12.5·600). */
    val tag = style(12.5, 16, 600)
    /** 흐린 안내 긴 글(.bnote 13·400). */
    val note = style(13.0, 18, 400)
    /** 첫 안내 목록(.ilist 13.5/20·500). */
    val introItem = style(13.5, 20, 500)
    /** 목록 줄 부제(.lrow span 14/20·400). 화면 안 오류는 iOS와 같이 caption. */
    val rowSub = style(14.0, 20, 400)
    /** 링크·흐린 머리·알림 글·안내 띠 글(.bh a, .anoti p 14/20·500). */
    val meta = style(14.0, 20, 500)
    /** 빠른 기록 이름(.qb 14·600). */
    val quickLabel = style(14.0, 18, 600)
    /** 띠 단추·부제 굵은 조각(14/20·700). */
    val metaStrong = style(14.0, 20, 700)
    /** 타임라인 줄 제목(.tl .ev b 14.5/20·500). */
    val eventTitle = style(14.5, 20, 500)
    /** 배너 앱 이름(.ncard b 14.5/20·700). */
    val bannerTitle = style(14.5, 20, 700)
    /** 이름·값 줄의 이름, 빈 목록 안내(15/22·400). */
    val valueKey = style(15.0, 22, 400)
    /** 타이머 상태·되돌리기 띠 글(15/22·500). */
    val bodyMedium = style(15.0, 22, 500)
    /** 칩·세그먼트·값(.chip, .segp span 15·600). */
    val chip = style(15.0, 22, 600)
    /** 알림 제목(.anoti b 15/20·700), 되돌리기 띠 단추. */
    val action = style(15.0, 20, 700)
    /** 시트 행 이름·입력(.sheet .row 15.5·500). */
    val sheetRow = style(15.5, 21, 500)
    /** 시트 값 칩(.sheet .row em 15.5·600). */
    val sheetValue = style(15.5, 21, 600)
    /** 첫 안내 첫 줄(.intro p.lead 16/24·500). */
    val lead = style(16.0, 24, 500)
    /** 목록 줄 제목·진행 중 판 제목(.lrow b, .run-c b 16/22·600). */
    val rowTitle = style(16.0, 22, 600)
    /** 날짜 띠 날(.datestrip b 17·500, 고정폭 숫자). */
    val dateNumber = style(17.0, 22, 500).copy(fontFeatureSettings = "tnum")
    /** 버튼(.pbtn 17·600 → 700), 방법별 성적 숫자(.dm b). */
    val strong = style(17.0, 22, 700)
    /** 큰 버튼, 시트 제목(18/24·700). */
    val strongLarge = style(18.0, 24, 700)
    /** 진행 중 판 시계(28/32·700, 고정폭 숫자). */
    val runningTimer = style(28.0, 32, 700, -0.02).copy(fontFeatureSettings = "tnum")
    /** 녹음 시간(.rec .rt 44·600, 고정폭 숫자). */
    val recordTimer = style(44.0, 48, 600, -0.02).copy(fontFeatureSettings = "tnum")

    // 안드로이드 화면에만 있는 자리(시안 .p-android 값).
    /** 아래 내비게이션 이름(.p-android .nav button 11/14·500, 고른 칸도 색만 바뀐다). */
    val navLabel = style(11.0, 14, 500)
    /** 알림 행동 단추(.anoti .acts span 13.5·600). */
    val bannerAction = style(13.5, 18, 600)
    /** 위 막대 제목·접힌 큰 제목(.p-android .ph-mini 22/28·600). */
    val barTitle = style(22.0, 28, 600)
    /** 초대 코드(고정폭 숫자·글자 사이를 띄움, iOS는 시스템 고정폭). */
    val inviteCode = style(34.0, 42, 700).copy(fontFeatureSettings = "tnum", letterSpacing = 0.06.sp)
    /** 연결 코드 확인(고정폭 숫자·글자 사이를 띄움). */
    val pairCode = style(36.0, 44, 700).copy(fontFeatureSettings = "tnum", letterSpacing = 0.08.sp)
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
    /** 화면 교체 때 블록이 한 줄씩 올라옴(rise): 시작 지연과 간격(core.js enter: startDelay 0.06·stagger 0.05, 열째 줄부터 같이). */
    const val RISE_DELAY_MS = 60
    const val RISE_STAGGER_MS = 50
    const val RISE_MAX_MS = 500
    /** 차트가 왼쪽부터 드러남(charts.js grow 0.9초 cb(.65,0,.35,1)). */
    val chartReveal = camera.copy(ms = 900)
    /** 막대 하나가 자람(0.6초 enter 곡선)과 막대 사이 간격(0.04초, 8개마다 다시). */
    val chartBar = enter.copy(ms = 600)
    const val CHART_BAR_STAGGER_MS = 40
    /** 방법별 성적 점이 튐(charts.js: spring 강성 600·감쇠 30 → 감쇠비 30 / (2·√600))과 점 사이 간격(0.02초). */
    val dotPop = SpringToken(30f / (2f * kotlin.math.sqrt(600f)), 600f)
    const val DOT_STAGGER_MS = 20L
    /** 차트 말풍선(.ch-tip 120ms linear). */
    val tip = press.copy(ms = 120)
    /** 새 수유가 들어올 때 판 둘레 빛(.flash 1.2초 base 곡선). */
    val flash = base.copy(ms = 1200)
    /** 살아 있는 점(live 1.8초, 앞 60% 동안 퍼짐). */
    val live = base.copy(ms = 1800)
    /** 녹음 소리 결 한 번(.wave i 1.2초). */
    val wave = base.copy(ms = 1200)
    /** 타이머 초침 한 바퀴(60초 linear). */
    val secondHand = press.copy(ms = 60_000)
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
            bodyMedium = Type.valueKey,
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
