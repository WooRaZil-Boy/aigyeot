package kr.aigyeot.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.aigyeot.app.R

/** 누른 면 배율(Pleos Interaction 공식 95%). */
const val PRESS_SCALE = 0.95f

/**
 * 누름(Pleos Interaction 'Button interaction': 누르면 95%로 줄고 모드별 배경색, 영역 밖으로 나가면 취소): 면 전체가 0.95배(90ms 선형)로 줄고,
 * [pressedBg]가 있으면 누르는 동안 그 색. 영역 밖으로 끌면 취소된다(clickable 기본). 비율은 디자인의 0.97보다 Pleos 공식 95%를 따른다.
 * 모양·배경도 여기서 그려야 면까지 함께 준다(배경을 앞에 두면 글자만 줄어든다).
 */
@Composable
fun Modifier.press(
    enabled: Boolean = true,
    role: Role = Role.Button,
    label: String? = null,
    shape: Shape? = null,
    bg: Color = Color.Transparent,
    pressedBg: Color? = null,
    decor: Modifier = Modifier,
    onClick: () -> Unit
): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val m = Tokens.motion.getValue("press")
    val k by animateFloatAsState(if (pressed) PRESS_SCALE else 1f, tween(m.ms, easing = m.easing), label = "press")
    var out = graphicsLayer {
        scaleX = k
        scaleY = k
    }.then(decor) // [decor](그림자 등)도 면과 함께 줄어든다(Pleos: 버튼 전체가 95%)
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
enum class BtnSize(val h: Dp, val r: Dp, val pad: Dp, val icon: Dp) {
    L(100.dp, CarRadius.l, 40.dp, 38.dp),
    M(80.dp, CarRadius.m, 30.dp, 32.dp),
    S(64.dp, 20.dp, 24.dp, 28.dp)
}

/** 버튼(Pleos Large·Medium·Small). 그림자·빛 없음. 영웅 버튼(BRAND)은 화면에 하나만. */
@Composable
fun CarButton(
    text: String,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.PLAIN,
    size: BtnSize = BtnSize.L,
    icon: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val p = LocalPalette.current
    val (bg, fg) = when (kind) {
        BtnKind.PLAIN -> p.ink(6) to p.textPrimary
        BtnKind.BRAND -> p.accent to p.onAccent
        BtnKind.FILL -> p.fill to p.onFill
        BtnKind.DANGER -> p.surface to p.danger // 위험 버튼은 먹색 6% 면이지만 그 위 danger 글자는 4.07:1이라 흰 면(5.04:1)에 둔다
    }
    // 누름 면(먹색 11%, 브랜드 버튼은 브랜드 86% + 먹색)
    // 브랜드는 라이트·다크 모두 어둡게 눌러 흰 글자 대비 4.5:1 이상을 지킨다(다크의 먹색은 밝아 대비가 떨어진다)
    val pressedBg = when (kind) {
        BtnKind.BRAND -> lerp(p.accent, Color.Black, 0.14f)
        BtnKind.FILL -> lerp(
            p.fill,
            p.onFill,
            0.12f
        )
        BtnKind.DANGER -> p.surface
        else -> p.ink(11)
    }
    val style = when (size) {
        BtnSize.L -> CarType.titleS
        BtnSize.M -> CarType.label.copy(
            fontSize = 28.sp,
            fontWeight = FontWeight(600)
        )
        BtnSize.S -> CarType.label.copy(fontSize = CarType.bodyS.fontSize, fontWeight = FontWeight(600))
    }
    val a = if (enabled) 1f else 0.38f
    Row(
        modifier.heightIn(min = size.h).press(
            enabled,
            label = text,
            shape = RoundedCornerShape(size.r),
            bg = bg.copy(alpha = bg.alpha * a),
            pressedBg = pressedBg,
            onClick = onClick
        )
            .padding(horizontal = size.pad),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) LineIcon(icon, size.icon, fg.copy(alpha = a))
        Text(text, style = style, color = fg.copy(alpha = a), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 판: 흰 면, 모서리 32, 그림자·테두리 없음. [label]은 아이콘 없는 작은 라벨. */
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
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 1200.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/** 머리(높이 112): 뒤로 단추(80) 또는 로고, 제목, 오른쪽 [actions]. */
@Composable
fun Header(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 112.dp).background(p.surface).padding(start = if (onBack != null) 28.dp else 44.dp, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (onBack != null) {
                Box(
                    Modifier.size(80.dp).press(
                        label = "뒤로",
                        shape = RoundedCornerShape(CarRadius.m),
                        pressedBg = p.ink(8),
                        onClick = onBack
                    ),
                    Alignment.Center
                ) {
                    LineIcon(R.drawable.ic_chevron_left, 48.dp, p.textPrimary, "뒤로")
                }
            } else {
                AppMark(60.dp)
            }
            Text(title, style = CarType.titleL, color = p.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
            actions()
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(p.line))
    }
}

/** 앱 아이콘 그림(흰 바탕 + 초승달 + 점)을 둥근 칸에. 흰 면 위에서도 경계가 보이게 얇은 테(검정 10%). */
@Composable
fun AppMark(size: Dp) {
    val shape = RoundedCornerShape(size * 0.3f)
    Icon(
        painterResource(R.drawable.ic_app_mark),
        "아이곁",
        Modifier.size(size).clip(shape).border(1.dp, Color.Black.copy(alpha = 0.1f), shape),
        tint = Color.Unspecified
    )
}

/** 머리 오른쪽 아이콘 버튼(80). */
@Composable
fun IconBtn(icon: Int, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(80.dp).press(
            enabled,
            label = desc,
            shape = RoundedCornerShape(CarRadius.m),
            bg = p.ink(5),
            pressedBg = p.ink(11),
            onClick = onClick
        ),
        Alignment.Center
    ) {
        LineIcon(icon, 40.dp, p.textPrimary, desc)
    }
}

/** 한 메시지는 3줄 이하(Pleos 가독성). */
@Composable
fun Body(text: String, color: Color = Color.Unspecified) = Text(
    text,
    style = CarType.bodyS,
    color = if (color == Color.Unspecified) LocalPalette.current.textSecondary else color,
    maxLines = 3,
    overflow = TextOverflow.Ellipsis
)

// ── 정차 화면 공용 부품 ──

/** 구획 제목(titleS, 아래 18). [small]은 옆 작은 글자. */
@Composable
fun Sect(text: String, small: String? = null, modifier: Modifier = Modifier) = Row(
    modifier.padding(bottom = 18.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)
) {
    Text(text, style = CarType.titleS, color = LocalPalette.current.textPrimary)
    if (small != null) Text(small, style = CarType.label, color = LocalPalette.current.textTertiary)
}

/** 목록 행(높이 100 이상, 모서리 28, 아이콘 36 흐림, 제목 30/38·500, 부가 label 흐림). [selected]면 옅은 먹색 면. */
@Composable
fun ListRow(
    title: String,
    sub: String? = null,
    icon: Int? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    toggled: Boolean? = null,
    trailing: @Composable () -> Unit = {}
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(28.dp)
    val bg = if (selected) p.ink(6) else Color.Transparent
    val base = Modifier.fillMaxWidth().heightIn(min = 100.dp)
    Row(
        (
            if (onClick != null) {
                base.press(
                    enabled,
                    if (toggled != null) Role.Switch else Role.Button,
                    title,
                    shape,
                    bg,
                    p.ink(8),
                    onClick = onClick
                )
            } else {
                base.clip(shape).background(bg)
            }
            )
            // 켜짐·고름 상태를 접근성에 알린다
            .semantics(mergeDescendants = true) {
                if (toggled != null) toggleableState = ToggleableState(toggled)
                if (selected) this.selected = true
            }
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        if (icon != null) LineIcon(icon, 36.dp, p.textTertiary)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = CarType.bodyL.copy(fontWeight = FontWeight(500), lineHeight = 38.sp),
                color = if (enabled) p.textPrimary else p.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (sub != null) {
                Text(
                    sub,
                    style = CarType.label,
                    color = p.textTertiary,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing()
    }
}

/**
 * 스위치(80×48, 손잡이 40, 꺼짐 먹색 22%). 켜짐은 브랜드 파랑 accent('브랜드 색은 행동: 버튼·선택·스위치에만', 두 폰 앱의 스위치와 같음).
 * Pleos informative_active 초록 대신 브랜드 파랑을 쓴다. 대비: 흰 손잡이 / accent 5.03(라이트·다크 같음, 전 초록은 다크 2.19),
 * accent / surface 라이트 5.03·다크 3.38(그래픽 3:1). 다크도 accent(accentInk #7AA5FF는 흰 손잡이와 대비가 낮다). 터치 영역은 행 전체가 맡는다.
 */
@Composable
fun CarSwitch(on: Boolean) {
    val p = LocalPalette.current
    val x by animateFloatAsState(if (on) 32f else 0f, tween(Tokens.motion.getValue("fast").ms, easing = Tokens.motion.getValue("fast").easing), label = "sw")
    Box(Modifier.size(80.dp, 48.dp).clip(RoundedCornerShape(percent = 50)).background(if (on) p.accent else p.ink(22))) {
        Box(Modifier.padding(start = (4 + x).dp, top = 4.dp).size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White))
    }
}

/** 스위치 한 줄(제목·부가 + 스위치). 행 전체를 누르면 바뀐다(터치 100 이상). */
@Composable
fun SwitchRow(title: String, sub: String?, on: Boolean, icon: Int? = null, set: (Boolean) -> Unit) =
    ListRow(title, sub, icon, onClick = { set(!on) }, toggled = on) { CarSwitch(on) }

/** 확인 칸(48, 모서리 12, 꺼짐 textTertiary 테). 켜짐은 스위치와 같은 브랜드 파랑 accent + 흰 체크(onAccent, 대비 5.03). */
@Composable
fun CheckBox(on: Boolean) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(48.dp).clip(shape).background(if (on) p.accent else Color.Transparent).border(
            3.dp,
            if (on) p.accent else p.textTertiary,
            shape
        ),
        Alignment.Center
    ) {
        if (on) LineIcon(R.drawable.ic_check, 32.dp, p.onAccent)
    }
}

/** 세그먼트(회색 판 + 흰 칸, 높이 88, 글자 28·700). */
@Composable
fun <T> Segmented(items: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    val fast = Tokens.motion.getValue("fast")
    // 바깥을 자르지 않는다: 고른 칸 그림자가 판 밖으로 번진다
    Row(modifier.fillMaxWidth().background(p.ink(5), RoundedCornerShape(CarRadius.l)).padding(6.dp)) {
        items.forEach { (k, label) ->
            val on = k == selected
            // 고른 칸 면·그림자는 fast(150ms)로 바뀐다(그림자 0 7 28 .14 + 0 2 2 .06)
            val selState = animateFloatAsState(if (on) 1f else 0f, tween(fast.ms, easing = fast.easing), label = "seg")
            val sel = selState.value
            val selK = remember(selState) { { selState.value } } // 그릴 때 읽는다(전환 중에도 그림자 캐시를 다시 만들지 않게)
            val face = if (p.isDark) Color(0xFF3A3F46) else p.surface
            val shadow = remember(selK) { Modifier.cssShadow(RoundedCornerShape(26.dp), selK, CssShadow(7.dp, 28.dp, 0.14f), CssShadow(2.dp, 2.dp, 0.06f)) }
            Box(
                Modifier.weight(1f).heightIn(min = 88.dp)
                    .press(
                        label = label,
                        role = Role.RadioButton,
                        shape = RoundedCornerShape(26.dp),
                        bg = face.copy(alpha = face.alpha * sel),
                        decor = shadow
                    ) { onSelect(k) }
                    .semantics { this.selected = on },
                Alignment.Center
            ) {
                Text(
                    label,
                    style = CarType.label.copy(fontSize = 28.sp, fontWeight = FontWeight(700)),
                    color = lerp(
                        p.textSecondary,
                        p.textPrimary,
                        sel
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

/** 수 고르기(높이 100, 양끝 100 단추, 가운데 titleM 한 줄). 시연 화면 '반복 장면'이 쓴다. */
@Composable
fun Stepper(text: String, modifier: Modifier = Modifier, minusEnabled: Boolean = true, plusEnabled: Boolean = true, onMinus: () -> Unit, onPlus: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(CarRadius.l)
    Row(
        modifier.fillMaxWidth().heightIn(min = 100.dp).clip(shape).background(p.surface).border(2.dp, p.line, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepKey(R.drawable.ic_minus, "줄이기", minusEnabled, onMinus)
        Text(
            text,
            style = CarType.titleM,
            color = p.textPrimary,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1
        )
        StepKey(R.drawable.ic_plus, "늘리기", plusEnabled, onPlus)
    }
}

/** 스테퍼 양끝 단추(100, 누름 면 먹색 8%, 끄면 흐림 38%). */
@Composable
private fun StepKey(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(100.dp).press(enabled, label = label, shape = RoundedCornerShape(CarRadius.l), pressedBg = p.ink(8), onClick = onClick),
        Alignment.Center
    ) {
        LineIcon(icon, 40.dp, p.textPrimary.copy(alpha = if (enabled) 1f else 0.38f), label)
    }
}

/** 입력칸 위 작은 이름(label 600 흐림, 위 22 아래 10). */
@Composable
fun FieldLabel(text: String) = Text(
    text,
    style = CarType.label.copy(fontWeight = FontWeight(600)),
    color = LocalPalette.current.textTertiary,
    modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)
)

/** 도움말(label 흐림). */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) = Text(
    text,
    style = CarType.label,
    color = LocalPalette.current.textTertiary,
    modifier = modifier.padding(top = 10.dp)
)

/** 알약(높이 56, 반경 28, label 600). [live]면 앞에 파란 점. */
@Composable
fun Pill(text: String, icon: Int? = null, live: Boolean = false) {
    val p = LocalPalette.current
    Row(
        Modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(p.surfaceRaised).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (live) {
            Box(Modifier.size(24.dp), Alignment.Center) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(percent = 50)).background(p.accent.copy(alpha = 0.22f)))
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(p.accent))
            }
        }
        if (icon != null) LineIcon(icon, 28.dp, p.textSecondary)
        Text(text, style = CarType.label.copy(fontWeight = FontWeight(600)), color = p.textSecondary, maxLines = 1)
    }
}

/**
 * 창(Pleos System Popup Medium 결: 폭 808, 반경 48, 여백 48, 제목 40/52·700 왼쪽, 설명 bodyL 흐림). 바깥을 누르거나 뒤로면 [onDismiss].
 * [content]는 Pleos Body(서비스 맥락의 콘텐츠, 예: 바퀴 판) 자리로 설명 아래 36(설명이 없으면 제목 아래 32)에 둔다.
 * 단추는 주 동작 [confirm](짙은 칠, 왼쪽) + [dismiss](기본) 가로 둘, [dismiss]가 없으면 [confirm] 하나를 폭 가득(Pleos Single).
 * 창 이름(읽기)은 제목이다. 들어오기는 0.96배·투명에서 enter 360ms, 동작 줄이기면 바로.
 */
@Composable
fun Popup(
    title: String,
    body: String?,
    confirm: String,
    dismiss: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val p = LocalPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        // 바탕 가림(rgba(19,20,23,.4))은 창 흐림 0.4로, 창은 0.96배·투명에서 enter(360ms cb(.2,.8,.2,1))로 들어온다
        (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(0.4f)
        val e = Tokens.motion.getValue("enter")
        val still = reducedMotion()
        val k = remember { Animatable(if (still) 1f else 0f) }
        LaunchedEffect(Unit) { k.animateTo(1f, tween(e.ms, easing = e.easing)) }
        Column(
            Modifier.graphicsLayer {
                alpha = k.value
                val s = 0.96f + 0.04f * k.value
                scaleX = s
                scaleY = s
            }
                .widthIn(max = 808.dp).fillMaxWidth().clip(RoundedCornerShape(48.dp)).background(p.surface).padding(48.dp)
                .semantics { paneTitle = title }
        ) {
            Text(title, style = CarType.titleL.copy(fontWeight = FontWeight(700)), color = p.textPrimary) // 제목 700 40/52
            if (body != null) Text(body, style = CarType.bodyL, color = p.textSecondary, modifier = Modifier.padding(top = 16.dp))
            if (content != null) {
                Spacer(Modifier.height(if (body != null) 36.dp else 32.dp))
                content()
            }
            Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CarButton(confirm, Modifier.weight(1f), BtnKind.FILL, onClick = onConfirm)
                if (dismiss != null) CarButton(dismiss, Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
}

/** CSS box-shadow 한 겹(dp): 아래로 [dy], 흐림 [blur](CSS 흐림 = 2σ), 검정 [alpha]. */
data class CssShadow(val dy: Dp, val blur: Dp, val alpha: Float)

/** 여러 겹 그림자를 겹마다 setShadowLayer로 그린다. [k]는 0~1 세기(그릴 때 읽는다). 윤곽·물감은 크기가 바뀔 때만 만든다(그릴 때 할당 없음). */
fun Modifier.cssShadow(shape: Shape, k: () -> Float, vararg layers: CssShadow): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = androidx.compose.ui.graphics.Path().apply {
        when (outline) {
            is androidx.compose.ui.graphics.Outline.Rectangle -> addRect(outline.rect)
            is androidx.compose.ui.graphics.Outline.Rounded -> addRoundRect(outline.roundRect)
            is androidx.compose.ui.graphics.Outline.Generic -> addPath(outline.path)
        }
    }.asAndroidPath()
    val paints = layers.map {
        androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.TRANSPARENT
        }
    }
    val radii = layers.map { ((it.blur.toPx() / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.1f) }
    val dys = layers.map { it.dy.toPx() }
    onDrawBehind {
        val kk = k()
        if (kk <= 0f) return@onDrawBehind
        drawIntoCanvas { c ->
            layers.forEachIndexed { i, l ->
                paints[i].setShadowLayer(radii[i], 0f, dys[i], android.graphics.Color.argb((l.alpha * kk * 255).toInt(), 0, 0, 0))
                c.nativeCanvas.drawPath(path, paints[i])
            }
        }
    }
}
