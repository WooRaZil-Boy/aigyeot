package kr.aigyeot.app.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloat as animateFloatT
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.aigyeot.app.R
import kr.aigyeot.app.ui.car3d.Light3d

// 홈(디자인 v6 '차 13단계', 시안 js/car.js column·driveSay, css/car.css 홈·주행 중 절). 상태(HomeUi)를 그리기만 하고, 바꾸려면 람다를 부른다.
// 정차: 머리 + [3D 무대 | 상태 열(눈썹 → 상태 한 줄 → 말한 문장 → 맥락 카드 → 마지막 수유 → 버튼)].
// 주행 중(DO, CarUxRestrictions가 알려 준 값만): 머리(버튼 없음) + [멈춘 3D | 큰 기호 + 한 줄]. 조작·스크롤·움직임 없음, 상태가 바뀔 때 200ms 페이드 한 번.

/** 시스템 '애니메이션 삭제'(동작 줄이기)면 참. */
@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    fun read() = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var v by remember { mutableStateOf(read()) }
    // 화면으로 돌아올 때마다 다시 읽는다(앱 밖에서 '애니메이션 삭제'를 켜고 돌아와도 바로 따르게)
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { v = read(); onPauseOrDispose { } }
    return v
}

@Composable
fun HomeScreen(
    ui: HomeUi, drive: Boolean,
    onBabyAboard: () -> Unit, onFeedingNow: () -> Unit, onAsk: () -> Unit, onFeedingConfirm: (Boolean) -> Unit,
    go: (Route) -> Unit,
) {
    val p = LocalPalette.current
    val reduced = reducedMotion()
    val stage = ui.stage.copy(drive = drive, reducedMotion = reduced)
    // 분위기 빛(시안 --atmo): 듣는 중 곁빛 55%, 달래는 중 방법 색(브랜드 파랑), 경보 아이 빛. 정차 중 0.9초에 번지고 주행 중·동작 줄이기는 바로
    val atmoTarget = when (ui.stage.light) {
        Light3d.LISTEN -> p.glow.copy(alpha = 0.55f); Light3d.SOOTHE -> p.accentInk; Light3d.ALERT -> p.childGlow; Light3d.BASE -> Color.Transparent
    }
    val light = Tokens.motion.getValue("light")
    val atmo by animateColorAsState(atmoTarget, if (drive || reduced) tween(0) else tween(light.ms, easing = light.easing), label = "atmo")
    BoxWithConstraints(Modifier.fillMaxSize()) { val narrowWindow = maxWidth < 1000.dp
    Column(Modifier.fillMaxSize()) {
        Header("아이곁", onBack = null) {
            if (!drive) {
                // 분할 840에서는 알약을 숨긴다(시안 .w840 .hdr .pills): 제목이 잘리지 않게
                if (!narrowWindow) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(end = 8.dp)) {
                    ui.pills.phone?.let { Pill(it, live = ui.pills.phoneLive) }
                    ui.pills.tempC?.let { Pill("$it°", icon = R.drawable.ic_thermometer) }
                }
                IconBtn(R.drawable.ic_chart_column, "주행 리포트") { go(Route.Reports) }
                IconBtn(R.drawable.ic_smartphone, "휴대폰 연결") { go(Route.Connect) }
                IconBtn(R.drawable.ic_settings, "아기 설정") { go(Route.Setup) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(atmoBrush(atmo))) {
            val narrow = maxWidth < 1000.dp
            if (drive) DriveBody(ui, stage, narrow) else ParkBody(ui, stage, narrow, reduced, onBabyAboard, onFeedingNow, onAsk, onFeedingConfirm)
        }
    }
    }
}

/** 시안 .home 바탕: radial-gradient(60% 75% at 24% 52%, atmo 16%, transparent 72%). */
private fun atmoBrush(atmo: Color): Brush = object : androidx.compose.ui.graphics.ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size) = androidx.compose.ui.graphics.RadialGradientShader(
        center = Offset(size.width * 0.24f, size.height * 0.52f), radius = maxOf(size.width * 0.6f, size.height * 0.75f),
        colors = listOf(atmo.copy(alpha = atmo.alpha * 0.16f), Color.Transparent), colorStops = listOf(0f, 0.72f),
    )
}

@Composable
private fun ParkBody(
    ui: HomeUi, stage: kr.aigyeot.app.ui.car3d.Stage3d, narrow: Boolean, reduced: Boolean,
    onBabyAboard: () -> Unit, onFeedingNow: () -> Unit, onAsk: () -> Unit, onFeedingConfirm: (Boolean) -> Unit,
) {
    // 좁은 창은 머리 알약을 숨기므로 연결 문장을 열 아래에 쓴다(연결 계약 8.6 '차 정차 화면 표시')
    val column: @Composable (Modifier) -> Unit = { m -> StatusColumn(ui, reduced, m, if (narrow) ui.pills.phone else null, onBabyAboard, onFeedingNow, onAsk, onFeedingConfirm) }
    if (narrow) Column(Modifier.fillMaxSize()) {
        // 시안 .w840 .stage 높이 450. 창이 낮으면 무대를 줄여 상태 열·버튼 자리를 남긴다(무대:열 = 0.7:1)
        CarStage(stage, Modifier.fillMaxWidth().weight(0.7f, fill = false).heightIn(max = 450.dp))
        column(Modifier.padding(start = 28.dp, end = 28.dp, bottom = 28.dp).weight(1f))
    } else Row(Modifier.fillMaxSize().padding(end = 48.dp)) {
        // 무대는 위·아래·왼쪽 끝까지(시안 .stage margin: -36 0 -40 -28), 열은 시안 여백 그대로
        CarStage(stage, Modifier.weight(1f).fillMaxHeight())
        column(Modifier.padding(start = 36.dp, top = 36.dp, bottom = 40.dp).width(600.dp).fillMaxHeight())
    }
}

/** 상태 열. 단계 열쇠가 바뀌면 옛 열이 블러 6으로 0.18초에 빠지고 새 열이 한 줄씩 0.05초 간격으로 올라온다(시안 4.7 ①). */
@Composable
private fun StatusColumn(
    ui: HomeUi, reduced: Boolean, modifier: Modifier, connLine: String?,
    onBabyAboard: () -> Unit, onFeedingNow: () -> Unit, onAsk: () -> Unit, onFeedingConfirm: (Boolean) -> Unit,
) {
    val enter = Tokens.motion.getValue("enter"); val exit = Tokens.motion.getValue("exit")
    Column(modifier) {
        AnimatedContent(
            targetState = ui, contentKey = { columnKey(it) }, modifier = Modifier.weight(1f),
            transitionSpec = { if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0)) else fadeIn(tween(1, delayMillis = exit.ms)) togetherWith fadeOut(tween(exit.ms, easing = exit.easing)) },
            label = "column",
        ) { u ->
            // 나가는 열은 블러 6으로 흐려지며 빠진다(시안 4.7 ① exit 180ms). 들어오는 열은 줄마다 Enter가 맡는다
            val leave by transition.animateFloat({ tween(exit.ms, easing = exit.easing) }, label = "leave") { if (it == androidx.compose.animation.EnterExitState.PostExit) 1f else 0f }
            // 정차 화면이라 넘치면 스크롤(분할 840·카드가 많은 단계에서 마지막 수유가 잘리지 않게). 버튼은 아래 고정
            Column(Modifier.fillMaxSize().blur((leave * 6).dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                var i = 0
                Enter(i++, reduced) { EyebrowRow(u.eyebrow) }
                Column {
                    Headline(u.status, i++, reduced)
                    u.sub?.let { Enter(i++, reduced) { Text(it, style = CarType.bodyS, color = LocalPalette.current.textTertiary) } }
                }
                u.said?.let { s -> Enter(i++, reduced) { SaidLine(s.text, ask = u.drive.ask) } }
                if (u.pausesWarning) Enter(i++, reduced) { Text("입력이 끊겨 판정을 잠시 멈췄어요", style = CarType.label.copy(fontWeight = FontWeight(600)), color = LocalPalette.current.danger) }
                if (u.confirmShown) Enter(i++, reduced) { ConfirmCard(onFeedingConfirm) }
                u.card?.let { c -> Enter(i++, reduced) { ContextCard(c, reduced) } }
                Enter(i++, reduced) { FeedPanel(u.feed) }
                u.day?.let { d -> Enter(i++, reduced) { Panel(label = "오늘 하루") { DayChart(d) } } }
            }
        }
        LinkNotes(ui.link, connLine)
        Spacer(Modifier.height(14.dp))
        CtaButtons(ui, onBabyAboard, onFeedingNow, onAsk)
    }
}

/** 열을 새로 들일지 정하는 열쇠(눈썹·상태·카드 종류·말한 문장이 바뀔 때만). 시계·숫자만 바뀌면 같은 열을 고쳐 그린다. */
private fun columnKey(u: HomeUi) = listOf(u.eyebrow.text, u.status, u.card?.let { it::class.simpleName + ((it as? HomeCard.Rest)?.added ?: "") + ((it as? HomeCard.Ask)?.answer ?: "") }, u.said?.at, u.confirmShown, u.pausesWarning, u.day != null).joinToString("|")

/** 상태 한 줄은 낱말마다 블러가 걷히며 나타난다(시안 4.7 ①, .headline .w). 낱말 단위로 줄바꿈(keep-all)이 되게 FlowRow로 둔다. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Headline(text: String, index: Int, reduced: Boolean) {
    val style = CarType.headlineS.copy(letterSpacing = (-0.025).em)
    FlowRow(Modifier.offset(y = (-8).dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        text.split(' ').forEachIndexed { w, word -> Enter(index + w, reduced) { Text(word, style = style, color = LocalPalette.current.textPrimary, maxLines = 1) } }
    }
}

/** 한 줄 들어오기: 블러 6 → 0, 아래 12dp에서, 0.36초, 줄마다 0.05초 늦게(시안 enter). 동작 줄이기면 바로. */
@Composable
private fun Enter(index: Int, reduced: Boolean, content: @Composable () -> Unit) {
    val k = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) { delay(50L * index); val e = Tokens.motion.getValue("enter"); k.animateTo(1f, tween(e.ms, easing = e.easing)) } }
    Box(Modifier.graphicsLayer { alpha = k.value; translationY = (1 - k.value) * 12.dp.toPx() }.blur(((1 - k.value) * 6).dp)) { content() }
}

@Composable
private fun EyebrowRow(e: Eyebrow) {
    val p = LocalPalette.current
    val color = when (e.tone) { Tone.CHILD -> p.child; else -> p.textSecondary }
    val dot = when (e.tone) { Tone.MUTED -> p.textTertiary; Tone.ACCENT -> p.accent; Tone.CHILD -> p.child; Tone.METHOD -> p.accentInk }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(14.dp), Alignment.Center) {
            if (e.live && !reducedMotion()) {
                // 듣는 중 점 숨쉬기(1.8초, 시안 @keyframes live): 실제로 듣는 신호일 때만
                val t = rememberInfiniteTransition(label = "live")
                val r by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "r")
                Canvas(Modifier.size(14.dp)) {
                    val ring = (r / 0.6f).coerceAtMost(1f)
                    if (r < 0.6f) drawCircle(dot.copy(alpha = 0.4f * (1 - ring)), size.minDimension / 2 + 12.dp.toPx() * ring)
                    drawCircle(dot, size.minDimension / 2)
                }
            } else Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
        }
        Text(e.text, style = CarType.label.copy(fontWeight = FontWeight(600), letterSpacing = 0.01.em), color = color, maxLines = 1)
    }
}

/** 차가 방금 말한 문장 한 줄(시안 .said). 쉼터 질문이면 '응 / 아니' 안내(누르는 단추가 아니다, 음성으로 답한다). */
@Composable
private fun SaidLine(text: String, ask: Boolean) {
    val p = LocalPalette.current
    Column(Modifier.offset(y = (-10).dp)) {
        Row(verticalAlignment = Alignment.Top) {
            LineIcon(R.drawable.ic_volume_2, 28.dp, p.textTertiary)
            Spacer(Modifier.width(10.dp))
            Text("“$text”", style = CarType.bodyL.copy(lineHeight = 42.sp, letterSpacing = (-0.01).em), color = p.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (ask) Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            YnHint("응", true); YnHint("아니", false)
        }
    }
}

@Composable
private fun YnHint(text: String, yes: Boolean) {
    val p = LocalPalette.current
    Row(
        Modifier.heightIn(min = 72.dp).clip(RoundedCornerShape(36.dp)).background(if (yes) p.accent else p.surfaceRaised).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (yes) LineIcon(R.drawable.ic_mic, 30.dp, p.onAccent)
        Text(text, style = CarType.titleS, color = if (yes) p.onAccent else p.textPrimary)
    }
}

@Composable
private fun ConfirmCard(onConfirm: (Boolean) -> Unit) = Panel(label = "수유 확인") {
    Text("수유하셨어요?", style = CarType.titleS, color = LocalPalette.current.textPrimary)
    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CarButton("네", Modifier.weight(1f), BtnKind.FILL, BtnSize.M) { onConfirm(true) }
        CarButton("아니요", Modifier.weight(1f), size = BtnSize.M) { onConfirm(false) }
    }
}

@Composable
private fun ContextCard(c: HomeCard, reduced: Boolean) = when (c) {
    is HomeCard.Track -> TrackCard(c, reduced)
    is HomeCard.Rest -> RestCard(c)
    is HomeCard.Episode -> EpisodeFlow(c)
    is HomeCard.Ask -> AskCard(c, reduced)
}

/** 지금 단계 시간이 1초마다 흐른다(시안 4.7 ④). 주행 중 화면에는 이 카드가 없다. */
@Composable
private fun elapsedSince(from: Long?, total: Long?): Long? {
    val v by produceState<Long?>(null, from, total) {
        if (from == null || total == null) { value = null; return@produceState }
        while (true) { value = (System.currentTimeMillis() - from).coerceIn(0, total); if (value == total) break; delay(1000) }
    }
    return v
}

private fun mmss(ms: Long) = (ms / 1000).let { "${it / 60}:%02d".format(it % 60) }

@Composable
private fun TrackCard(c: HomeCard.Track, reduced: Boolean) {
    val p = LocalPalette.current
    val el = elapsedSince(c.from, c.totalMs)
    Panel {
        Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.Bottom) {
            Text("달래기 단계", style = CarType.label, color = p.textTertiary, modifier = Modifier.weight(1f))
            if (el != null && c.totalMs != null) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(mmss(el), style = CarType.numeral.copy(letterSpacing = (-0.03).em, fontFeatureSettings = "tnum"), color = p.textPrimary)
                Text("/ ${mmss(c.totalMs)}", style = CarType.bodyS.copy(fontFeatureSettings = "tnum"), color = p.textTertiary, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        val names = listOf("1차", "듣기 틈", "2차", "쉼터")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            names.forEachIndexed { i, n ->
                val m = c.marks.getOrElse(i) { StepMark.NONE }
                val frac = when (m) { StepMark.DONE, StepMark.FAIL -> 1f; StepMark.NOW -> if (el != null && c.totalMs != null && c.totalMs > 0) el.toFloat() / c.totalMs else 0f; StepMark.NONE -> 0f }
                val fill = when (m) { StepMark.FAIL -> lerp(p.surfaceRaised, p.child, 0.55f); StepMark.NOW -> if (i == 3) p.child else p.accent; else -> p.accent }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(p.surfaceRaised)) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(fill))
                    }
                    Column {
                        Text(n, style = CarType.label.copy(fontWeight = FontWeight(600)), color = if (m == StepMark.NOW) p.textPrimary else p.textTertiary)
                        val sub = if (m == StepMark.FAIL) "다시 울음" else c.subs.getOrElse(i) { "" }
                        if (sub.isNotEmpty()) Text(sub, style = CarType.label, color = when (m) { StepMark.FAIL -> p.child; StepMark.NOW -> p.textSecondary; else -> p.textTertiary }, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun RestCard(c: HomeCard.Rest) {
    val p = LocalPalette.current
    Panel(label = "쉼터") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(c.name, style = CarType.titleM, color = p.textPrimary, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            c.sub?.let { Text(it, style = CarType.label, color = p.textSecondary, maxLines = 1) }
        }
        if (c.added) Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineIcon(R.drawable.ic_check, 28.dp, p.accentInk)
            Text("경유지로 넣었어요", style = CarType.label.copy(fontWeight = FontWeight(600)), color = p.accentInk)
        }
    }
}

@Composable
private fun EpisodeFlow(c: HomeCard.Episode) {
    val p = LocalPalette.current
    Panel(label = "에피소드 · ${TimeText.clock(c.at)}") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            c.flow.forEachIndexed { i, f ->
                if (i > 0) Text("→", style = CarType.label, color = p.textTertiary)
                Text(f.text, style = CarType.label.copy(fontWeight = FontWeight(if (f.tone == Tone.ACCENT) 600 else 500)),
                    color = when (f.tone) { Tone.CHILD -> p.child; Tone.ACCENT -> p.accentInk; else -> p.textSecondary })
            }
        }
    }
}

@Composable
private fun AskCard(c: HomeCard.Ask, reduced: Boolean) {
    val p = LocalPalette.current
    Panel(label = "물어보기") {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (c.listening) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.size(88.dp).clip(CircleShape).background(p.accent), Alignment.Center) { LineIcon(R.drawable.ic_mic, 40.dp, p.onAccent) }
                LevelBars(reduced)
            }
            c.question?.let { q ->
                Box(Modifier.fillMaxWidth(), Alignment.CenterEnd) {
                    Text(q, style = CarType.bodyL.copy(fontWeight = FontWeight(500)), color = p.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(RoundedCornerShape(30.dp, 30.dp, 8.dp, 30.dp)).background(p.surfaceRaised).padding(horizontal = 26.dp, vertical = 18.dp))
                }
            }
            c.answer?.let { Text(it, style = CarType.titleS.copy(fontWeight = FontWeight(500), lineHeight = 44.sp), color = p.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** 듣는 중 마이크 막대(실제로 듣는 동안만 움직인다, 정차 화면). */
@Composable
private fun LevelBars(reduced: Boolean) {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "lv")
    val ph by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "ph")
    Canvas(Modifier.width(216.dp).height(56.dp)) {
        val bw = 6.dp.toPx(); val gap = 6.dp.toPx()
        for (i in 0 until 18) {
            val base = 0.18f + kotlin.math.abs(kotlin.math.sin(i * 1.3f)) * 0.7f
            val k = if (reduced) base else (0.18f + 0.72f * (0.5f + 0.5f * kotlin.math.sin(((ph + (i * 97 % 700) / 1100f) * 2 * Math.PI).toFloat())))
            val h = size.height * k
            drawRoundRect(p.textTertiary, Offset(i * (bw + gap), (size.height - h) / 2), androidx.compose.ui.geometry.Size(bw, h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
    }
}

/** 마지막 수유(시안 feedCard): 지난 시간을 크게, 평소 간격의 1.25배까지 막대, 간격 시작(0.8) 눈금은 아이 신호 색. */
@Composable
private fun FeedPanel(f: FeedCard) {
    val p = LocalPalette.current
    val now by produceState(System.currentTimeMillis(), f.start) { while (true) { value = System.currentTimeMillis(); delay(30_000) } }
    Panel(label = "마지막 수유") {
        val start = f.start
        if (start == null) { Text("기록 없음", style = CarType.bodyL, color = p.textSecondary); return@Panel }
        val ago = now - start
        val fresh = ago < 60_000
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(agoText(ago), style = CarType.numeral.copy(letterSpacing = (-0.03).em, fontFeatureSettings = "tnum"), color = p.textPrimary)
            if (!fresh) Text("지난 시간", style = CarType.bodyS, color = p.textTertiary, modifier = Modifier.padding(bottom = 10.dp))
        }
        f.intervalMin?.takeIf { it > 0 }?.let { iv ->
            val full = iv * 1.25f * 60_000
            val v = (ago / full).coerceIn(0f, 1f); val w = 0.8f / 1.25f
            Box(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 16.dp).height(24.dp)) {
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(p.surfaceRaised)) {
                    Box(Modifier.fillMaxWidth(v).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(p.textSecondary))
                }
                BoxWithConstraints(Modifier.fillMaxSize()) { Box(Modifier.offset(x = maxWidth * w).width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(p.child)) }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(TimeText.clock(start), style = CarType.label.copy(fontFeatureSettings = "tnum"), color = p.textSecondary, modifier = Modifier.weight(1f))
            f.intervalMin?.takeIf { it > 0 }?.let { Text(intervalText(it), style = CarType.label, color = p.textTertiary) }
        }
    }
}

/** 연결 오류·'연결됐어요'(계약 8.6). 연결 상태 한 줄은 머리 알약이 보인다. */
@Composable
private fun LinkNotes(st: kr.aigyeot.app.link.LinkStatus, connLine: String?) {
    var paired by remember { mutableStateOf(false) }
    LaunchedEffect(st.justPaired) { if (pairedNotice.take(st.justPaired)) paired = true }
    val p = LocalPalette.current
    connLine?.let { Text(it, style = CarType.label, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
    if (paired) Text("연결됐어요", style = CarType.label.copy(fontWeight = FontWeight(600)), color = p.accentInk, modifier = Modifier.padding(top = 10.dp))
    st.error?.let { Text(it, style = CarType.label, color = p.danger, maxLines = 3, modifier = Modifier.padding(top = 10.dp)) }
}

/** 버튼(시안 cta): 출발 전이면 '아기 탔어요'(영웅), 그 밖은 '물어보기'(정차 중이면 영웅) + '지금 먹이기 시작했어요'. */
@Composable
private fun CtaButtons(ui: HomeUi, onBabyAboard: () -> Unit, onFeedingNow: () -> Unit, onAsk: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (ui.cta.board) CarButton("아기 탔어요", Modifier.fillMaxWidth(), BtnKind.BRAND, icon = R.drawable.ic_baby, onClick = onBabyAboard)
    // ④ 물어보기: 기어 P이고 마이크가 켜져 있을 때(결정 D1). C303 ⑤: 판단 코어가 AskButton을 받는 조건 그대로
    else CarButton("물어보기", Modifier.fillMaxWidth(), if (ui.cta.askHero) BtnKind.BRAND else BtnKind.PLAIN, icon = R.drawable.ic_mic, enabled = ui.askEnabled, onClick = onAsk)
    CarButton("지금 먹이기 시작했어요", Modifier.fillMaxWidth(), icon = R.drawable.ic_bottle, onClick = onFeedingNow)
}

// ── 주행 중 ──

@Composable
private fun DriveBody(ui: HomeUi, stage: kr.aigyeot.app.ui.car3d.Stage3d, narrow: Boolean) {
    if (narrow) Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, bottom = 48.dp)) {
        CarStage(stage, Modifier.fillMaxWidth().weight(1f), interactive = false)
        DriveSay(ui, 160.dp, 84.dp, RoundedCornerShape(52.dp), 28.dp)   // 시안 .w840 .drive .sym 160·글자 84·반경 52, .say 간격 28
    } else Row(Modifier.fillMaxSize().padding(end = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        CarStage(stage, Modifier.weight(1f).fillMaxHeight(), interactive = false)
        Box(Modifier.width(640.dp)) { DriveSay(ui, 184.dp, 92.dp, CircleShape, 40.dp) }
    }
}

/** 큰 기호 하나 + 한 줄(statusText, 2줄까지) + 쉼터 질문이면 '응 / 아니'. 상태가 바뀌면 200ms 페이드 한 번. */
@Composable
private fun DriveSay(ui: HomeUi, sym: Dp, icon: Dp, shape: androidx.compose.ui.graphics.Shape, gap: Dp) {
    val p = LocalPalette.current
    val a = remember { Animatable(1f) }
    val key = ui.status + ui.drive + ui.pausesWarning
    LaunchedEffect(key) { a.snapTo(0f); a.animateTo(1f, tween(Tokens.DRIVE_FADE_MS)) }
    val child = ui.drive.symbol == Symbol.BABY
    val mc = if (child) p.child else p.accentInk
    Column(Modifier.graphicsLayer { alpha = a.value }, verticalArrangement = Arrangement.spacedBy(gap)) {
        Box(Modifier.size(sym).clip(shape).background(p.surface).border(3.dp, mc.copy(alpha = 0.45f), shape), Alignment.Center) {
            LineIcon(symbolIcon(ui.drive.symbol), icon, mc)
        }
        // 주행 중 글은 합해서 3줄 이하(IMPLEMENT 주행 중 규칙): 경고·'응 / 아니'가 둘 다 있으면 상태는 한 줄
        Text(ui.status, style = CarType.headlineL.copy(letterSpacing = (-0.02).em), color = p.textPrimary, maxLines = if (ui.pausesWarning && ui.drive.ask) 1 else 2, overflow = TextOverflow.Ellipsis)
        // 입력이 끊겨 판정을 멈춘 동안은 주행 중에도 알린다(듣고 있다는 거짓 안내가 되지 않게, 계약 차 5.3). 한 줄이라 3줄 이하
        if (ui.pausesWarning) Text("입력이 끊겨 판정을 잠시 멈췄어요", style = CarType.bodyL.copy(fontWeight = FontWeight(600)), color = p.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (ui.drive.ask) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LineIcon(R.drawable.ic_mic, 36.dp, p.accentInk)
            Text("응 / 아니", style = CarType.bodyL.copy(fontWeight = FontWeight(600)), color = p.textSecondary)
        }
    }
}

private fun symbolIcon(s: Symbol) = when (s) {
    Symbol.EAR -> R.drawable.ic_ear_thin; Symbol.BABY -> R.drawable.ic_baby_thin; Symbol.PIN -> R.drawable.ic_map_pin_thin
    Symbol.LULLABY -> R.drawable.ic_moon_star_thin; Symbol.NOISE -> R.drawable.ic_waves_thin; Symbol.WOMB -> R.drawable.ic_heart_pulse_thin; Symbol.SONG -> R.drawable.ic_song_thin
}
