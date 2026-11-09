package kr.aigyeot.phone.presentation

import android.os.Build
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.aigyeot.phone.R

// 디자인 v6 부품(시안 css/phone.css의 v5 '한국 앱 결' 값). 화면은 이 부품만 조립한다(Material 꼴을 쓰지 않는다).
// 값은 시안 CSS의 px = dp. 글자는 Theme.kt `Type`.

/** 동작 줄이기: 시스템 '애니메이션 삭제'(animator 길이 배율 0). 그러면 모든 움직임이 바로 바뀐다(README 4.7). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    fun read() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember { mutableStateOf(read()) }
    // 앱을 켠 채 시스템에서 바꿔도 따른다.
    DisposableEffect(Unit) {
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, obs)
        onDispose { context.contentResolver.unregisterContentObserver(obs) }
    }
    return reduced
}

/** 선 아이콘(Lucide·직접 그린 4개, res/drawable/ic_*). 옆 글자가 뜻을 말하므로 꾸밈이다. */
@Composable
fun AIcon(@DrawableRes id: Int, modifier: Modifier = Modifier.size(24.dp), tint: Color = LocalContentColor.current) =
    Icon(painterResource(id), null, modifier, tint)

/** 누름(README 4.7 press: 90ms linear, 0.97배, Pleos·quietly). 물결 없이 크기로만 답한다. */
fun Modifier.pressable(
    enabled: Boolean = true,
    scale: Float = 0.97f,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val s by animateFloatAsState(if (pressed && !reduced) scale else 1f, Motion.press.spec(), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
        .combinedClickable(source, null, enabled = enabled, role = role, onLongClick = onLongClick, onClick = onClick)
}

// ---------------------------------------------------------------- 화면 틀

/**
 * 탭 첫 화면: 큰 제목(display 26/34·700) + 회색 바탕 위 흰 블록들(.pad 12, 간격 12).
 * 내려 굴리면 큰 제목이 위 막대(22/28·600)로 접힌다(시안 .ph-mini). 블록은 처음 보일 때 한 줄씩 올라온다(README 4.7 enter).
 */
@Composable
fun TabPage(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val collapseAt = with(LocalDensity.current) { 56.dp.toPx() }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Spacer(Modifier.height(54.dp)) // 시안 .ptop: 위 10 + 행동 줄 44
            LargeTitle(title)
            StaggerColumn(Modifier.padding(horizontal = 12.dp), content = content)
            Spacer(Modifier.height(28.dp))
        }
        CollapsedTitle(title, scroll.value > collapseAt)
    }
}

/** 탭 안이 아닌 화면: 뒤로(arrow-left 48) + 제목(22/28·600) 한 줄, 아래는 같은 블록. */
@Composable
fun BackPage(title: String, onBack: (() -> Unit)?, padContent: Boolean = true, trailing: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit) {
    // 아래 시스템 막대·자판 자리(탭 밖 화면은 스스로 비운다).
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        TopBar(title, onBack, trailing)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            StaggerColumn(if (padContent) Modifier.padding(horizontal = 12.dp) else Modifier, content = content)
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, trailing: @Composable RowScope.() -> Unit = {}) {
    val p = Tokens.colors
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).pressable(scale = 0.92f, onClick = onBack).semantics { contentDescription = "뒤로" },
                contentAlignment = Alignment.Center,
            ) { AIcon(R.drawable.ic_arrow_left, tint = p.textPrimary) }
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = TextStyle(fontFamily = Pretendard, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold), color = p.textPrimary, maxLines = 2)
        trailing()
    }
}

@Composable
fun LargeTitle(title: String) = Text(
    title,
    Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp).semantics { heading() },
    style = Type.display.copy(lineBreak = LineBreak.Heading),
    color = Tokens.colors.textPrimary,
)

/** 큰 제목이 접힌 위 막대(시안 .p-android .ph-mini: 22/28·600, 바탕색). */
@Composable
private fun CollapsedTitle(title: String, shown: Boolean) {
    val p = Tokens.colors
    val a by animateFloatAsState(if (shown) 1f else 0f, Motion.fast.spec(), label = "mini")
    if (a == 0f) return
    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = a }.background(p.bg).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(title, style = TextStyle(fontFamily = Pretendard, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold), color = p.textPrimary, maxLines = 1)
    }
}

/**
 * 블록을 세로로 12dp 간격으로 쌓고, 처음 보일 때 한 줄씩 올라오게 한다(시안 UI.enter: 0.05초 간격, 16dp 아래에서, 블러 6 → 0).
 * 블러는 API 31+만(R10), 그 아래는 페이드만. 움직임은 그림 층(layer)에서만 바뀌어 다시 그리기만 한다.
 */
@Composable
fun StaggerColumn(modifier: Modifier = Modifier, gap: Dp = 12.dp, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val t = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) { if (reduced) t.snapTo(1f) else if (t.value < 1f) t.animateTo(1f, tween(900, easing = androidx.compose.animation.core.LinearEasing)) }
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    val blurPx = with(LocalDensity.current) { 6.dp.toPx() }
    Layout(content, modifier) { measurables, constraints ->
        val c = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(c) }
        val g = gap.roundToPx()
        val h = placeables.sumOf { it.height } + g * (placeables.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, h) {
            var y = 0
            placeables.forEachIndexed { i, pl ->
                pl.placeWithLayer(0, y) {
                    // 900ms 안에서 i번째는 i·50ms 뒤 시작해 360ms(enter 곡선)에 걸쳐 올라온다.
                    val start = (i * 50f).coerceAtMost(500f) / 900f
                    val raw = ((t.value - start) / (360f / 900f)).coerceIn(0f, 1f)
                    val k = Motion.enter.easing.transform(raw)
                    alpha = k
                    translationY = (1f - k) * rise
                    if (Build.VERSION.SDK_INT >= 31 && k < 1f) {
                        val r = (1f - k) * blurPx
                        renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
                    } else {
                        renderEffect = null
                    }
                }
                y += pl.height + g
            }
        }
    }
}

// ---------------------------------------------------------------- 블록

/** 흰 블록(.pcard: surface, 모서리 20, 안쪽 22/20). */
@Composable
fun Block(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.fillMaxWidth().clip(Radius.large).background(Tokens.colors.surface)
    Column((if (onClick != null) base.pressable(scale = 0.98f, onClick = onClick) else base).padding(padding), content = content)
}

/** 블록 머리(.bh): 제목 20/28·700 + 오른쪽 작은 링크(14·500 흐림 + chevron). 아래 14. */
@Composable
fun BlockHeader(title: String, link: String? = null, onLink: (() -> Unit)? = null) {
    val p = Tokens.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = Type.title, color = p.textPrimary)
        if (link != null && onLink != null) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).pressable(onClick = onLink).heightIn(min = 32.dp).padding(start = 6.dp, end = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(link, style = Type.caption.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.textTertiary)
                AIcon(R.drawable.ic_chevron_right, Modifier.size(16.dp), p.textTertiary)
            }
        }
    }
}

/** 블록 부제(.bsub: 14/20, 흐림, 굵은 값은 먹색 700). `**값**`을 굵게 그린다. */
@Composable
fun BlockSub(text: String) {
    val p = Tokens.colors
    val s = buildAnnotatedString {
        text.split("**").forEachIndexed { i, part ->
            if (i % 2 == 1) withStyle(SpanStyle(color = p.textPrimary, fontWeight = FontWeight.Bold)) { append(part) } else append(part)
        }
    }
    Text(s, Modifier.padding(top = 0.dp, bottom = 14.dp).offset(y = (-8).dp), style = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.textTertiary)
}

/** 블록 끝 안내(.bnote: 13/19, 흐림, 위 14). */
@Composable
fun BlockNote(text: String) = Text(text, Modifier.padding(top = 14.dp), style = Type.caption.copy(fontWeight = FontWeight.Normal, lineHeight = 19.sp), color = Tokens.colors.textTertiary)

/** 블록 밖 작은 안내(.foot-note: 12.5/18·500, 흐림). */
@Composable
fun FootNote(text: String) = Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = Type.caption.copy(fontSize = 12.5.sp), color = Tokens.colors.textTertiary)

/** 작은 라벨(.label: 14/20·500, 흐림). */
@Composable
fun Label(text: String, modifier: Modifier = Modifier) = Text(text, modifier, style = Type.caption.copy(fontSize = 14.sp, lineHeight = 20.sp), color = Tokens.colors.textTertiary)

/** 목록 블록(.list: 안쪽 22/20/8). 머리가 있으면 그 아래에 64dp 행들. */
@Composable
fun ListBlock(title: String? = null, link: String? = null, onLink: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    Block(padding = PaddingValues(start = 20.dp, end = 20.dp, top = if (title != null) 22.dp else 6.dp, bottom = if (title != null) 8.dp else 6.dp)) {
        if (title != null) BlockHeader(title, link, onLink)
        content()
    }

/** 행 아이콘 원(40, surfaceRaised). `add`면 브랜드 옅은 바탕. */
@Composable
fun RowIcon(@DrawableRes id: Int, add: Boolean = false, size: Dp = 40.dp, iconSize: Dp = 19.dp, tint: Color? = null) {
    val p = Tokens.colors
    Box(Modifier.size(size).clip(CircleShape).background(if (add) p.accentSoft else p.surfaceRaised), contentAlignment = Alignment.Center) {
        AIcon(id, Modifier.size(iconSize), tint ?: if (add) p.onAccentSoft else p.textSecondary)
    }
}

/** 목록 행(.lrow-p: 최소 64, 아이콘 40 · 제목 16/22·600 · 부제 14/20 흐림 · 오른쪽 chevron). */
@Composable
fun ListRow(
    title: String,
    sub: String? = null,
    @DrawableRes icon: Int? = null,
    add: Boolean = false,
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = if (onClick != null) ({ AIcon(R.drawable.ic_chevron_right, Modifier.size(18.dp), Tokens.colors.textTertiary) }) else null,
) {
    val p = Tokens.colors
    val base = Modifier.fillMaxWidth().heightIn(min = 64.dp)
    Row(
        (if (onClick != null) base.clip(RoundedCornerShape(12.dp)).pressable(scale = 0.98f, onClick = onClick) else base).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            leading != null -> leading()
            icon != null -> RowIcon(icon, add)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp), color = titleColor ?: if (add) p.accentInk else p.textPrimary)
            sub?.let { Text(it, style = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.textTertiary) }
        }
        trailing?.invoke()
    }
}

/** 설정 묶음(.grp: surface, 모서리 28, 안쪽 2/16). */
@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(Radius.xl).background(Tokens.colors.surface).padding(horizontal = 16.dp, vertical = 2.dp), content = content)

/** 설정 행(.grp .lrow-p: 최소 56, 아이콘 32칸·17 흐림, 바탕 없음). */
@Composable
fun SettingRow(
    title: String,
    @DrawableRes icon: Int,
    sub: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    /** 있으면 행 전체가 스위치 하나다(화면 읽기가 제목과 상태를 함께 읽는다). */
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = when {
        checked != null -> ({ ToggleSwitch(checked, onChange = null, enabled = enabled) })
        onClick != null -> ({ AIcon(R.drawable.ic_chevron_right, Modifier.size(18.dp), Tokens.colors.textTertiary) })
        else -> null
    },
) {
    val p = Tokens.colors
    val base = Modifier.fillMaxWidth().heightIn(min = 56.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f }
    val act = when {
        checked != null && onCheckedChange != null ->
            base.toggleable(checked, interactionSource = null, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        onClick != null -> base.pressable(enabled = enabled, scale = 0.98f, onClick = onClick)
        else -> base
    }
    Row(
        act.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { AIcon(icon, Modifier.size(19.dp), if (danger) p.danger else p.textTertiary) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp), color = if (danger) p.danger else p.textPrimary)
            sub?.let { Text(it, style = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.textTertiary) }
        }
        trailing?.invoke()
    }
}

/** 스위치(.psw: 52×32, 켜짐은 브랜드 파랑, 엄지 26 흰색, 스프링 bouncy). */
@Composable
fun ToggleSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, if (reduced) tween(0) else Springs.bouncy.spec(), label = "sw")
    val track = if (checked) p.accent else p.textPrimary.copy(alpha = 0.18f)
    val base = modifier.size(52.dp, 32.dp).clip(CircleShape).background(track).graphicsLayer { alpha = if (enabled) 1f else 0.45f }
    Box(if (onChange != null) base.toggleable(checked, interactionSource = null, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onChange) else base) {
        Box(
            Modifier.padding(3.dp).offset(x = x).size(26.dp)
                .drawBehind { drawCircle(Color.Black.copy(alpha = 0.2f), radius = size.minDimension / 2, center = center + Offset(0f, 2.dp.toPx())) }
                .clip(CircleShape).background(Color.White),
        )
    }
}

/** 세그먼트(.segp: 회색 판 + 흰 칸, 높이 40, 엄지는 스프링 snappy). 두 플랫폼 같다. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val pos by animateFloatAsState(selected.coerceAtLeast(0).toFloat(), if (reduced) tween(0) else Springs.snappy.spec(), label = "seg")
    val thumb = if (p.dark) Color(0xFF3A3F46) else p.surface // 시안 .t-dark .segp .sthumb
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.textPrimary.copy(alpha = 0.06f)).padding(4.dp)) {
        Layout(
            content = {
                Box(
                    Modifier
                        .drawBehind { drawRoundRect(Color.Black.copy(alpha = 0.06f), topLeft = Offset(0f, 2.dp.toPx()), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())) }
                        .clip(RoundedCornerShape(10.dp)).background(thumb),
                )
                options.forEachIndexed { i, label ->
                    Box(
                        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                            .selectable(i == selected, interactionSource = null, indication = null, role = Role.Tab, onClick = { onSelect(i) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), color = if (i == selected) p.textPrimary else p.textSecondary, maxLines = 2)
                    }
                }
            },
        ) { ms, c ->
            val n = options.size
            val w = c.maxWidth / n
            val cells = ms.drop(1).map { it.measure(c.copy(minWidth = w, maxWidth = w, minHeight = 0)) }
            val h = cells.maxOf { it.height }
            val th = ms[0].measure(c.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h))
            layout(c.maxWidth, h) {
                // 고른 칸이 없으면(쪽을 아직 고르지 않음) 엄지를 그리지 않는다.
                if (selected >= 0) th.placeWithLayer(0, 0) { translationX = pos * w }
                cells.forEachIndexed { i, cell -> cell.place(i * w, 0) }
            }
        }
    }
}

// ---------------------------------------------------------------- 버튼

enum class BtnKind { PRIMARY, TONAL, DANGER_TEXT, TEXT }

/** 버튼(.pbtn: 높이 56·모서리 16·700 17. big 58·18). 누르면 0.98배. */
@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.PRIMARY,
    @DrawableRes icon: Int? = null,
    big: Boolean = false,
    enabled: Boolean = true,
) {
    val p = Tokens.colors
    val (bg, fg) = when (kind) {
        BtnKind.PRIMARY -> p.accent to p.onAccent
        BtnKind.TONAL -> p.surfaceRaised to p.textPrimary
        BtnKind.DANGER_TEXT -> Color.Transparent to p.danger
        BtnKind.TEXT -> Color.Transparent to p.accentInk
    }
    val h = when {
        kind == BtnKind.DANGER_TEXT || kind == BtnKind.TEXT -> 48.dp
        big -> 58.dp
        else -> 56.dp
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = h).clip(RoundedCornerShape(16.dp)).background(bg)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pressable(enabled = enabled, scale = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { AIcon(it, Modifier.size(20.dp), fg); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.body.copy(fontSize = if (big) 18.sp else 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold), color = fg)
    }
}

/** 칩(작은 선택지: 고르면 먹색 채움, 아니면 회색 판. k-style 칩 규칙). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    // 고름은 fast 150ms 색 바뀜, 누름은 0.97배(README 4.7). 동작 줄이기면 바로.
    val bg by animateColorAsState(if (selected) p.textPrimary else p.surfaceRaised, if (reduced) tween(0) else Motion.fast.spec(), label = "chip")
    val fg by animateColorAsState(if (selected) p.surface else p.textSecondary, if (reduced) tween(0) else Motion.fast.spec(), label = "chipText")
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, Motion.press.spec(), label = "chipPress")
    Box(
        modifier.graphicsLayer { scaleX = s; scaleY = s }.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .selectable(selected, interactionSource = source, indication = null, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), color = fg) }
}

/** 배지(.badge: 높이 24, 모서리 6, 12.5·600). 아이 신호면 childSoft/child. 뜻 하나에 색 하나. */
@Composable
fun Badge(text: String, @DrawableRes icon: Int? = null, child: Boolean = false) {
    val p = Tokens.colors
    val (bg, fg) = if (child) p.childSoft to p.child else p.surfaceRaised to p.textSecondary
    Row(Modifier.heightIn(min = 24.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { AIcon(it, Modifier.size(14.dp), fg); Spacer(Modifier.width(4.dp)) }
        Text(text, style = Type.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = fg)
    }
}

/** 숫자 줄(.kpi: 세 칸, 이름 13 흐림 · 값 20/28·700 · 아래 12 흐림). 넷 이상이면 다음 줄로. */
data class Kpi(val label: String, val value: String, val sub: String? = null, val note: String? = null)

@Composable
fun KpiRow(items: List<Kpi>, modifier: Modifier = Modifier) {
    val p = Tokens.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (row in items.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in row) Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(k.label, style = Type.caption, color = p.textTertiary)
                Text(k.value, style = Type.title.copy(fontFeatureSettings = "tnum"), color = p.textPrimary)
                k.sub?.let { Text(it, style = Type.caption.copy(fontSize = 12.sp, lineHeight = 16.sp), color = p.textTertiary) }
                k.note?.let { Text(it, style = Type.caption.copy(fontSize = 12.sp, lineHeight = 16.sp), color = p.textTertiary) }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** 상태 띠(오프라인·안내·시계 차이, 7.2): 흰 블록 안 한 줄 + 오른쪽 행동 글자(브랜드). */
@Composable
fun BandCard(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val p = Tokens.colors
    // 새로 뜨거나 바뀐 안내는 화면 읽기가 알아서 읽는다(포커스가 다른 곳에 있어도).
    Row(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = 6.dp), style = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.textSecondary)
        if (action != null) {
            Box(Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onAction).heightIn(min = 40.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(action, style = Type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = p.accentInk)
            }
        }
    }
}

/** 살아 있는 점(진행 중 기록 .run-c .ri: 1.8초 숨). 동작 줄이기면, 그리고 굴려서 가려지면 멈춘 점. */
@Composable
fun LiveDot(color: Color, size: Dp = 10.dp) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(true) }
    val ring = if (reduced || !shown) 0f else rememberInfiniteTransition(label = "live").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = Motion.base.easing), RepeatMode.Restart), label = "live").value
    Box(
        Modifier.size(size).onScreen { shown = it }.drawBehind {
            if (ring > 0f) drawCircle(color.copy(alpha = 0.5f * (1f - ring)), radius = size.toPx() / 2 + ring * 8.dp.toPx())
            drawCircle(color)
        },
    )
}

/**
 * 숫자 굴림(README 4.7 roll: 자리마다, spring .85·632). 바뀐 자리만 아래에서 올라온다. 길이가 바뀌면 통째로 바꾼다.
 * 동작 줄이기면 바로 바뀐다.
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    if (reduced) {
        Text(text, modifier, style = style, color = color)
        return
    }
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            androidx.compose.runtime.key(i, text.length) {
                AnimatedContent(
                    targetState = ch,
                    modifier = Modifier.clipToBounds(),
                    transitionSpec = {
                        (slideInVertically(Springs.roll.spec()) { it / 2 } + fadeIn(tween(120))) togetherWith (slideOutVertically(Springs.roll.spec()) { -it / 2 } + fadeOut(tween(90)))
                    },
                    label = "roll",
                ) { c -> Text(c.toString(), style = style, color = color) }
            }
        }
    }
}

/** 둘레 테가 있는 앱 아이콘 칸(흰 바탕 아이콘이라 흰 면 위에서 경계가 보이게 검정 10% 테, 시안 v6-6). */
@Composable
fun AppMark(size: Dp, corner: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(Color.White).border(1.dp, Color.Black.copy(alpha = 0.1f), RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(size * 1.5f))
    }
}

/** 화면 전체를 감싸는 동작 줄이기 값. */
@Composable
fun ProvideMotion(content: @Composable () -> Unit) = CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion(), content = content)

/** 위 막대 오른쪽 글자 행동(브랜드 글자 600, 누를 자리 48). */
@Composable
fun TopAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Tokens.colors
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pressable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = p.accentInk) }
}

/** 화면에 보이는지 알린다(굴려서 가려지면 거짓). 계속 도는 움직임을 보일 때만 돌리는 데 쓴다. */
fun Modifier.onScreen(onChange: (Boolean) -> Unit): Modifier = onGloballyPositioned { onChange(!it.boundsInWindow().isEmpty) }

/** 공유 요소 범위(AppRoot의 SharedTransitionLayout)와 지금 경로의 등장 범위(NavHost composable). */
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun NavScope(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) = CompositionLocalProvider(LocalNavScope provides scope, content = content)

/**
 * 진행 중 판 → 타이머 고리 공유 요소(README 4.7 spring.smooth, 시안 view-transition-name run-{id}). 같은 [key]를 두 화면에 둔다.
 * 동작 줄이기면 그냥 바뀐다.
 */
@Composable
fun Modifier.sharedRun(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    if (LocalReducedMotion.current) return this
    val base: Modifier = this
    return with(shared) { base.sharedBounds(rememberSharedContentState(key), nav, boundsTransform = { _, _ -> Springs.smooth.spec() }) }
}
