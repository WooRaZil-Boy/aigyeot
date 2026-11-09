package kr.aigyeot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.aigyeot.app.R

// 디자인 v6(시안 docs/design/1109-unified-v3, 11-09 확정): 회색 바탕 + 흰 판, 브랜드 파랑은 행동(버튼·선택·스위치)에만, 숫자·진행은 먹색.
// 값은 Tokens(tokens.json 한 출처). 모양은 시안 css/car.css를 옮겼다(1px = 1dp, Connect-S).

/** 색 한 벌(라이트·다크). 이름은 tokens.json 그대로. */
@Immutable
class Palette(private val dark: Boolean) {
    private fun c(name: String) = Color(Tokens.color.getValue(name).let { if (dark) it.second else it.first })
    private fun g(name: String) = Tokens.glass.getValue(name).let { if (dark) it.second else it.first }.color
    val isDark = dark
    val bg = c("bg"); val stage = c("stage"); val surface = c("surface"); val surfaceRaised = c("surfaceRaised"); val line = c("line")
    val textPrimary = c("textPrimary"); val textSecondary = c("textSecondary"); val textTertiary = c("textTertiary")
    val accent = c("accent"); val accentInk = c("accentInk"); val onAccent = c("onAccent"); val accentSoft = c("accentSoft"); val onAccentSoft = c("onAccentSoft")
    val glow = c("glow"); val child = c("child"); val childSoft = c("childSoft"); val childGlow = c("childGlow")
    val warning = c("warning"); val danger = c("danger"); val active = c("active"); val fill = c("fill"); val onFill = c("onFill")
    val cFeed = c("cFeed"); val cSleep = c("cSleep"); val cDiaper = c("cDiaper"); val cNote = c("cNote"); val cCar = c("cCar")
    val glassFill = g("fill"); val glassRim = g("rim"); val glassShade = g("shade")
    /** 옅은 먹색 면(시안 color-mix(text-primary n%)): 아이콘 버튼 5%, 버튼 6%, 누름 11%, 꺼진 스위치 22%. */
    fun ink(pct: Int) = textPrimary.copy(alpha = pct / 100f)
}

private val LightPalette = Palette(dark = false)
private val DarkPalette = Palette(dark = true)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Pretendard Variable 1.3.9(OFL, assets/licenses/Pretendard-OFL.txt). 굵기는 가변 축 wght로 고른다. */
private fun pretendard(w: Int) = Font(R.font.pretendard_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val AppFont: FontFamily = FontFamily(listOf(300, 400, 500, 600, 700).map(::pretendard))

/** 차 글자 위계(Tokens.carType). 한국어 줄바꿈은 낱말 단위(시안 word-break: keep-all). */
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

/** 가장 작은 터치 영역(시안 6장 Small S 64). 머리 아이콘 버튼 80, 주 버튼 100. */
val Touch = 64.dp

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
            surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface, surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface,
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

/**
 * 누름(시안 4.7 press·Pleos Interaction): 면 전체가 0.97배(90ms 선형)로 줄고, [pressedBg]가 있으면 누르는 동안 그 색. 영역 밖으로 끌면 취소된다(clickable 기본).
 * 모양·배경도 여기서 그려야 면까지 함께 준다(배경을 앞에 두면 글자만 줄어든다).
 */
@Composable
fun Modifier.press(
    enabled: Boolean = true, role: Role = Role.Button, label: String? = null,
    shape: Shape? = null, bg: Color = Color.Transparent, pressedBg: Color? = null, onClick: () -> Unit,
): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val m = Tokens.motion.getValue("press")
    val k by animateFloatAsState(if (pressed) 0.97f else 1f, tween(m.ms, easing = m.easing), label = "press")
    var out = graphicsLayer { scaleX = k; scaleY = k }
    if (shape != null) out = out.clip(shape)
    val fill = if (pressed && pressedBg != null) pressedBg else bg
    if (fill != Color.Transparent) out = out.background(fill)
    return out.clickable(src, null, enabled, label, role, onClick)
}

/** 선 아이콘(Lucide ISC + 직접 그린 4개, res/drawable/ic_*). 색은 tint로. */
@Composable
fun LineIcon(id: Int, size: Dp, tint: Color = LocalContentColor.current, desc: String? = null) =
    Icon(painterResource(id), desc, Modifier.size(size), tint = tint)

enum class BtnKind { PLAIN, BRAND, FILL, DANGER }
enum class BtnSize(val h: Dp, val r: Dp, val pad: Dp, val icon: Dp) { L(100.dp, 32.dp, 40.dp, 38.dp), M(80.dp, 24.dp, 30.dp, 32.dp), S(64.dp, 20.dp, 24.dp, 28.dp) }

/** 버튼(Pleos Large·Medium·Small). 그림자·빛 없음. 영웅 버튼(BRAND)은 화면에 하나만. */
@Composable
fun CarButton(
    text: String, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.PLAIN, size: BtnSize = BtnSize.L,
    icon: Int? = null, enabled: Boolean = true, onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val (bg, fg) = when (kind) {
        BtnKind.PLAIN -> p.ink(6) to p.textPrimary
        BtnKind.BRAND -> p.accent to p.onAccent
        BtnKind.FILL -> p.fill to p.onFill
        BtnKind.DANGER -> p.surface to p.danger   // 시안 .btn.danger는 먹색 6% 면이지만 그 위 danger 글자는 4.07:1이라 흰 면(5.04:1)에 둔다
    }
    // 누름 면(시안 .btn:active 먹색 11%, .btn.brand:active 브랜드 86% + 먹색)
    // 브랜드는 라이트·다크 모두 어둡게 눌러 흰 글자 대비 4.5:1 이상을 지킨다(다크의 먹색은 밝아 대비가 떨어진다)
    val pressedBg = when (kind) { BtnKind.BRAND -> lerp(p.accent, Color.Black, 0.14f); BtnKind.FILL -> lerp(p.fill, p.onFill, 0.12f); BtnKind.DANGER -> p.surface; else -> p.ink(11) }
    val style = when (size) { BtnSize.L -> CarType.titleS; BtnSize.M -> CarType.label.copy(fontSize = 28.sp, fontWeight = FontWeight(600)); BtnSize.S -> CarType.label.copy(fontSize = 26.sp, fontWeight = FontWeight(600)) }
    val a = if (enabled) 1f else 0.38f
    Row(
        modifier.heightIn(min = size.h).press(enabled, label = text, shape = RoundedCornerShape(size.r), bg = bg.copy(alpha = bg.alpha * a), pressedBg = pressedBg, onClick = onClick)
            .padding(horizontal = size.pad),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) LineIcon(icon, size.icon, fg.copy(alpha = a))
        Text(text, style = style, color = fg.copy(alpha = a), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 판(시안 .pn·.card): 흰 면, 모서리 32, 그림자·테두리 없음. [label]은 아이콘 없는 작은 라벨. */
@Composable
fun Panel(modifier: Modifier = Modifier, label: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(CarRadius.l)).background(p.surface).padding(horizontal = 32.dp, vertical = 28.dp)) {
        if (label != null) Text(label, style = CarType.label, color = p.textTertiary, modifier = Modifier.padding(bottom = 16.dp))
        content()
    }
}

/** 한 화면 틀(정차 화면 중 홈이 아닌 것): 머리(뒤로 + 제목) + 세로 스크롤 내용. */
@Composable
fun Page(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxSize()) {
    Header(title, onBack)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 44.dp, end = 44.dp, top = 32.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 1200.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/** 머리(시안 .hdr, 높이 112): 뒤로 단추(80) 또는 로고, 제목, 오른쪽 [actions]. */
@Composable
fun Header(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 112.dp).background(p.surface).padding(start = if (onBack != null) 28.dp else 44.dp, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (onBack != null) Box(Modifier.size(80.dp).press(label = "뒤로", shape = RoundedCornerShape(24.dp), pressedBg = p.ink(8), onClick = onBack), Alignment.Center) {
                LineIcon(R.drawable.ic_chevron_left, 48.dp, p.textPrimary, "뒤로")
            } else AppMark(60.dp)
            Text(title, style = CarType.titleL, color = p.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
            actions()
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(p.line))
    }
}

/** 앱 아이콘 그림(흰 바탕 + 초승달 + 점)을 둥근 칸에. 흰 면 위에서도 경계가 보이게 얇은 테(검정 10%, 시안 v6-6). */
@Composable
fun AppMark(size: Dp) {
    val shape = RoundedCornerShape(size * 0.3f)
    Icon(painterResource(R.drawable.ic_app_mark), "아이곁", Modifier.size(size).clip(shape).border(1.dp, Color.Black.copy(alpha = 0.1f), shape), tint = Color.Unspecified)
}

/** 머리 오른쪽 아이콘 버튼(80, 시안 .ibtn). */
@Composable
fun IconBtn(icon: Int, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(80.dp).press(enabled, label = desc, shape = RoundedCornerShape(24.dp), bg = p.ink(5), pressedBg = p.ink(11), onClick = onClick), Alignment.Center) {
        LineIcon(icon, 40.dp, p.textPrimary, desc)
    }
}

/** 지난 판의 큰 버튼 자리(아직 새 화면으로 옮기지 않은 화면용). [primary]면 영웅(브랜드). */
@Composable
fun BigButton(text: String, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) =
    CarButton(text, Modifier.fillMaxWidth(), if (primary) BtnKind.BRAND else BtnKind.PLAIN, enabled = enabled, onClick = onClick)

/** 한 메시지는 3줄 이하(Pleos 가독성). */
@Composable
fun Body(text: String, color: Color = Color.Unspecified) =
    Text(text, style = CarType.bodyS, color = if (color == Color.Unspecified) LocalPalette.current.textSecondary else color, maxLines = 3, overflow = TextOverflow.Ellipsis)

@Composable
fun Label(text: String) = Text(text, style = CarType.label, color = LocalPalette.current.textTertiary)

// ── 정차 화면 공용 부품(시안 car.css '다른 화면 공통') ──

/** 구획 제목(시안 .sect: titleS, 아래 18). [small]은 옆 작은 글자. */
@Composable
fun Sect(text: String, small: String? = null, modifier: Modifier = Modifier) = Row(modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(text, style = CarType.titleS, color = LocalPalette.current.textPrimary)
    if (small != null) Text(small, style = CarType.label, color = LocalPalette.current.textTertiary)
}

/** 목록 행(시안 .lrow: 높이 100 이상, 모서리 28, 아이콘 36 흐림, 제목 30/38·500, 부가 label 흐림). [selected]면 옅은 먹색 면. */
@Composable
fun ListRow(
    title: String, sub: String? = null, icon: Int? = null, selected: Boolean = false, enabled: Boolean = true,
    onClick: (() -> Unit)? = null, toggled: Boolean? = null, trailing: @Composable () -> Unit = {},
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(28.dp)
    val bg = if (selected) p.ink(6) else Color.Transparent
    val base = Modifier.fillMaxWidth().heightIn(min = 100.dp)
    Row(
        (if (onClick != null) base.press(enabled, if (toggled != null) Role.Switch else Role.Button, title, shape, bg, p.ink(8), onClick) else base.clip(shape).background(bg))
            .semantics(mergeDescendants = true) { if (toggled != null) toggleableState = ToggleableState(toggled); if (selected) this.selected = true }   // 켜짐·고름 상태를 접근성에 알린다
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (icon != null) LineIcon(icon, 36.dp, p.textTertiary)
        Column(Modifier.weight(1f)) {
            Text(title, style = CarType.bodyL.copy(fontWeight = FontWeight(500), lineHeight = 38.sp), color = if (enabled) p.textPrimary else p.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = CarType.label, color = p.textTertiary, modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** 스위치(시안 .sw: 80×48, 켜짐 active 초록, 손잡이 40). 터치 영역은 행 전체가 맡는다. */
@Composable
fun CarSwitch(on: Boolean) {
    val p = LocalPalette.current
    val x by animateFloatAsState(if (on) 32f else 0f, tween(Tokens.motion.getValue("fast").ms, easing = Tokens.motion.getValue("fast").easing), label = "sw")
    Box(Modifier.size(80.dp, 48.dp).clip(RoundedCornerShape(24.dp)).background(if (on) p.active else p.ink(22))) {
        Box(Modifier.padding(start = (4 + x).dp, top = 4.dp).size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White))
    }
}

/** 스위치 한 줄(제목·부가 + 스위치). 행 전체를 누르면 바뀐다(터치 100 이상). */
@Composable
fun SwitchRow(title: String, sub: String?, on: Boolean, icon: Int? = null, set: (Boolean) -> Unit) =
    ListRow(title, sub, icon, onClick = { set(!on) }, toggled = on) { CarSwitch(on) }

/** 확인 칸(시안 .chk: 48, 모서리 12, 켜짐 active). */
@Composable
fun CheckBox(on: Boolean) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.size(48.dp).clip(shape).background(if (on) p.active else Color.Transparent).border(3.dp, if (on) p.active else p.textTertiary, shape), Alignment.Center) {
        if (on) LineIcon(R.drawable.ic_check, 32.dp, Color.White)
    }
}

/** 세그먼트(시안 .segc: 회색 판 + 흰 칸, 높이 88, 글자 28·700). */
@Composable
fun <T> Segmented(items: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(p.ink(5)).padding(6.dp)) {
        items.forEach { (k, label) ->
            val on = k == selected
            Box(
                Modifier.weight(1f).heightIn(min = 88.dp)
                    .press(label = label, role = Role.RadioButton, shape = RoundedCornerShape(26.dp), bg = if (on) (if (p.isDark) Color(0xFF3A3F46) else p.surface) else Color.Transparent) { onSelect(k) }
                    .semantics { this.selected = on },
                Alignment.Center,
            ) { Text(label, style = CarType.label.copy(fontSize = 28.sp, fontWeight = FontWeight(700)), color = if (on) p.textPrimary else p.textSecondary, maxLines = 1) }
        }
    }
}

/** 수 고르기(시안 .stepper: 높이 100, 양끝 100 단추, 가운데 titleM). */
@Composable
fun Stepper(text: String, modifier: Modifier = Modifier, minusEnabled: Boolean = true, plusEnabled: Boolean = true, onMinus: () -> Unit, onPlus: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(32.dp)
    Row(modifier.fillMaxWidth().heightIn(min = 100.dp).clip(shape).background(p.surface).border(2.dp, p.line, shape), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(100.dp).press(minusEnabled, label = "줄이기", shape = RoundedCornerShape(32.dp), pressedBg = p.ink(8), onClick = onMinus), Alignment.Center) { LineIcon(R.drawable.ic_minus, 40.dp, p.textPrimary.copy(alpha = if (minusEnabled) 1f else 0.38f), "줄이기") }
        Text(text, style = CarType.titleM, color = p.textPrimary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
        Box(Modifier.size(100.dp).press(plusEnabled, label = "늘리기", shape = RoundedCornerShape(32.dp), pressedBg = p.ink(8), onClick = onPlus), Alignment.Center) { LineIcon(R.drawable.ic_plus, 40.dp, p.textPrimary.copy(alpha = if (plusEnabled) 1f else 0.38f), "늘리기") }
    }
}

/** 입력칸 위 작은 이름(시안 .flabel: label 600 흐림, 위 22 아래 10). */
@Composable
fun FieldLabel(text: String) = Text(text, style = CarType.label.copy(fontWeight = FontWeight(600)), color = LocalPalette.current.textTertiary, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))

/** 도움말(시안 .help·.note: label 흐림). */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) = Text(text, style = CarType.label, color = LocalPalette.current.textTertiary, modifier = modifier.padding(top = 10.dp))

/** 알약(시안 .pill: 높이 56, 반경 28, label 600). [live]면 앞에 파란 점. */
@Composable
fun Pill(text: String, icon: Int? = null, live: Boolean = false) {
    val p = LocalPalette.current
    Row(Modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(p.surfaceRaised).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (live) Box(Modifier.size(24.dp), Alignment.Center) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(p.accent.copy(alpha = 0.22f)))
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(p.accent))
        }
        if (icon != null) LineIcon(icon, 28.dp, p.textSecondary)
        Text(text, style = CarType.label.copy(fontWeight = FontWeight(600)), color = p.textSecondary, maxLines = 1)
    }
}

/** 확인 창(시안 .popup: 폭 808, 반경 48, 여백 48, 제목 40/52·700, 본문 bodyL, 단추 둘). 바깥을 누르면 [onDismiss]. */
@Composable
fun Popup(title: String, body: String, confirm: String, dismiss: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 808.dp).fillMaxWidth().clip(RoundedCornerShape(48.dp)).background(p.surface).padding(48.dp)) {
            Text(title, style = TextStyle(fontFamily = AppFont, fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight(700)), color = p.textPrimary)
            Text(body, style = CarType.bodyL, color = p.textSecondary, modifier = Modifier.padding(top = 16.dp, bottom = 36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CarButton(confirm, Modifier.weight(1f), BtnKind.FILL, onClick = onConfirm)
                CarButton(dismiss, Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
}
