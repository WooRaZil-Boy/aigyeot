package kr.aigyeot.phone.presentation

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kr.aigyeot.phone.R
import kr.aigyeot.phone.domain.model.CodeSource
import kr.aigyeot.phone.domain.model.IntroText
import kr.aigyeot.phone.domain.model.PAIR_BUSY
import kr.aigyeot.phone.domain.model.PairRun
import kr.aigyeot.phone.domain.model.TypedAction
import kr.aigyeot.phone.domain.model.codeFromLink
import kr.aigyeot.phone.domain.model.confirmTitle
import kr.aigyeot.phone.domain.model.introStageHeight
import kr.aigyeot.phone.domain.model.isInviteCode
import kr.aigyeot.phone.domain.model.needsConfirm
import kr.aigyeot.phone.domain.model.normalizeCode
import kr.aigyeot.phone.domain.model.pairErrorLive
import kr.aigyeot.phone.domain.model.pairErrorShown
import kr.aigyeot.phone.domain.model.pairWaitNextMs
import kr.aigyeot.phone.domain.model.pairWaitText
import kr.aigyeot.phone.domain.model.typedSubmitAction
import kotlinx.coroutines.flow.first
import kr.aigyeot.shared.Role

// 연결 흐름(G13·G16·G17·G31). 디자인 v6 시안 phone.js S.intro(차 연결 · 첫 안내)와 같은 결로 나머지 단계도 흰 블록 + 큰 버튼.

/**
 * 첫 안내 등장 안무(v8b-10 ⑤, 사용자 확정 '(나) 도착'). [car]면 빛·차 그림이 0ms에 시작하고, 글은 [textDelayMs]부터 50ms 간격,
 * 단추 띠는 [barDelayMs]. 무대가 숨은 화면은 차 안무 없이 글이 120ms부터.
 */
data class IntroPlan(val car: Boolean, val textDelayMs: Int, val barDelayMs: Int)

/** 단추 띠 앞 글 칸 수: 머리 이름 · 제목 · 정보 줄 넷 · '자세히 보기'. */
private const val INTRO_ITEMS = 7

/** 안무를 정한다. 이미 했거나 동작 줄이기면 null(처음부터 다 보인다). */
fun introPlan(played: Boolean, reduced: Boolean, stage: Boolean): IntroPlan? {
    if (played || reduced) return null
    val text = if (stage) 240 else 120
    return IntroPlan(car = stage, textDelayMs = text, barDelayMs = text + Motion.RISE_STAGGER_MS * INTRO_ITEMS)
}

/**
 * 프로세스에서 첫 안내를 처음 그릴 때 한 번만 안무한다(v8b-10 ⑤): 연결 화면에서 뒤로 오거나 시트를 닫거나 다크 전환·창 크기가 바뀌면 다시 하지 않는다.
 * 화면 상태(rememberSaveable)가 아니라 프로세스 수준 표시다.
 */
object IntroMotion {
    var played = false

    /**
     * 앱 창이 보인다(액티비티가 처음 창 초점을 받음). 시작 화면이 걷히는 동안 안무가 돌면 가려져 보이지 않으므로(에뮬레이터 콜드 스타트 녹화에서
     * 시작 화면이 안무 첫 0.7초를 덮음) 이때부터 시계를 돌린다.
     */
    var windowShown by androidx.compose.runtime.mutableStateOf(false)

    /** 창 신호를 기다리는 한도(신호가 오지 않는 기기에서 화면이 비어 있지 않게). */
    const val WINDOW_WAIT_MS = 1_500L

    /** 이번에 쓸 안무를 받고 '했음'으로 둔다(동작 줄이기로 건너뛴 것도 했음). */
    fun take(reduced: Boolean, stage: Boolean): IntroPlan? = introPlan(played, reduced, stage).also { played = true }
}

/** 등장 시계 끝(ms): 단추 띠가 다 올라온 때. */
private fun IntroPlan.endMs() = barDelayMs + Motion.enter.ms

/** '자세히 보기'가 올라오기 시작하는 때(ms): 머리 이름·제목·정보 줄 넷 다음. */
private fun IntroPlan.moreDelayMs() = textDelayMs + Motion.RISE_STAGGER_MS * 6

/**
 * 첫 안내의 누를 것이 누름을 받는가: 안무가 정해졌고([decided]) 그것이 나타나기 시작한 뒤([startMs], 안무가 없으면 null = 처음부터).
 * 그림 층 투명도는 입력을 막지 않아, 빈 화면을 누르면 보이지 않는 '먼저 기록만 쓸게요'(받은 연결 코드를 버림)·'자세히 보기'가 눌렸다.
 */
internal fun introLive(decided: Boolean, startMs: Int?, clockMs: Float): Boolean =
    decided && (startMs == null || clockMs >= startMs)

/**
 * 시계 [clock](ms)에서 [startMs]부터 [ms] 동안 아래 [rise]에서 올라오며 나타난다(곡선 [curve], API 31+면 블러 [blur] → 0).
 * 그림 층에서만 바뀌어 다시 그리기만 한다. 시계가 없으면(안무 없음) 그대로 보인다.
 */
private fun Modifier.introRise(clock: () -> Float?, startMs: Int, rise: androidx.compose.ui.unit.Dp, ms: Int = Motion.enter.ms, blur: androidx.compose.ui.unit.Dp = 6.dp, curve: Curve = Motion.enter): Modifier =
    graphicsLayer {
        val t = clock() ?: return@graphicsLayer
        val k = curve.easing.transform(((t - startMs) / ms).coerceIn(0f, 1f))
        alpha = k
        translationY = (1f - k) * rise.toPx()
        renderEffect = if (Build.VERSION.SDK_INT >= 31 && k < 1f && blur > 0.dp) {
            val r = (1f - k) * blur.toPx()
            if (r > 0.5f) androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Decal) else null
        } else null
    }

/**
 * 첫 실행: 무엇이 오가는지 한 화면(G31, U43 안 B, 디자인 v8b-10). 위 무대(정지 차 그림, 남는 높이만큼·120 미만이면 숨김) → 머리 이름 →
 * 큰 제목(두 줄) → 정보 줄 넷 → '자세히 보기'(원문 넷 시트) / 아래 고정 단추 띠('차와 연결하기' + 글자 단추 '먼저 기록만 쓸게요').
 * 글은 [IntroText](7.1 intro.*, iOS와 같은 글자). 등장 안무는 [IntroMotion].
 */
@Composable
fun IntroScreen(onConnect: () -> Unit, onRecordsOnly: () -> Unit) {
    val p = Tokens.colors
    val d = LocalDensity.current
    val reduced = LocalReducedMotion.current
    var sheet by rememberSaveable { mutableStateOf(false) }
    // 무대 높이(⑦): 글 칸·단추 띠를 먼저 재고 남는 높이를 무대에 준다(한 번 다시 그림).
    var textH by remember { mutableStateOf(0) }
    var barH by remember { mutableStateOf(0) }
    // 안무: 무대를 그릴지 정해진 뒤에 한 번 받는다. 그 전(잰 첫 장)에는 안무할 화면이면 비워 둔다(글이 보였다 사라지지 않게).
    var decided by remember { mutableStateOf(IntroMotion.played || reduced) }
    var plan by remember { mutableStateOf<IntroPlan?>(null) }
    val clock = remember { androidx.compose.animation.core.Animatable(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().background(p.bg)) {
        val window = maxHeight
        val measured = textH != 0 && barH != 0
        val stage = if (!measured) null else with(d) { introStageHeight(window.value.toInt(), textH.toDp().value.toInt(), barH.toDp().value.toInt()) }
        LaunchedEffect(measured) {
            if (!measured) return@LaunchedEffect
            val got = IntroMotion.take(reduced, stage != null)
            // 안무할 때는 창이 다 들어온 뒤(시작 화면이 걷힌 뒤) 시계를 돌린다. 그때까지 화면은 비어 있다(시작 화면이 바탕으로 걷힘).
            if (got != null) kotlinx.coroutines.withTimeoutOrNull(IntroMotion.WINDOW_WAIT_MS) { androidx.compose.runtime.snapshotFlow { IntroMotion.windowShown }.first { it } }
            android.util.Log.i("IntroScreen", "등장 안무 ${got ?: "없음"}")
            plan = got
            decided = true
            // 화면을 처음 그리는 두 장(레이어·그림 올리기)이 끝난 뒤 시계를 돌린다: 첫 장이 늦으면 안무 앞부분이 건너뛰어진다.
            if (got != null) repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            got?.let { clock.animateTo(it.endMs().toFloat(), androidx.compose.animation.core.tween(it.endMs(), easing = androidx.compose.animation.core.LinearEasing)) }
        }
        val run: () -> Float? = { if (plan != null) clock.value else null }
        // 시계는 매 장 바뀌므로 누름 여부만 따로 계산해 바뀔 때만 다시 그린다(plan·decided도 상태라 늘 새 값을 읽는다).
        val moreLive by remember { androidx.compose.runtime.derivedStateOf { introLive(decided, plan?.moreDelayMs(), clock.value) } }
        val barLive by remember { androidx.compose.runtime.derivedStateOf { introLive(decided, plan?.barDelayMs, clock.value) } }
        val text = plan?.textDelayMs ?: 0
        Column(Modifier.fillMaxSize().graphicsLayer { alpha = if (decided) 1f else 0f }) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (stage != null) IntroStage(stage.dp, animate = plan?.car == true, clock = run)
                else Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(8.dp))
                Column(Modifier.padding(horizontal = 20.dp).onSizeChanged { textH = it.height }) {
                    Row(Modifier.padding(top = 6.dp).introRise(run, text, 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppMark(24.dp, 7.dp)
                        Text(IntroText.TITLE, Modifier.padding(start = 8.dp), style = Type.action, color = p.textSecondary) // intro.title
                    }
                    // 제목은 50ms 뒤 500ms·12 → 0, 한 덩어리로 올라온다. 낱말별 간격·블러는 두 앱 모두 뺐다(11-11).
                    Text(
                        IntroText.LEAD,
                        Modifier.padding(top = 10.dp).introRise(run, text + Motion.RISE_STAGGER_MS, 12.dp, ms = 500, blur = 0.dp).semantics { heading() },
                        style = Type.display.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), color = p.textPrimary,
                    ) // intro.lead
                    // 정보 줄 넷(intro.short.*): 묶음 읽기 이름 '오가는 정보', 줄마다 한 요소.
                    Column(Modifier.padding(top = 28.dp).semantics { contentDescription = IntroText.SHEET_TITLE }, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        val icons = listOf(R.drawable.ic_link, R.drawable.ic_x, R.drawable.ic_shield_check, R.drawable.ic_sparkles)
                        IntroText.SHORT.forEachIndexed { i, (title, sub) ->
                            InfoLine(icons[i], title, sub, child = i == 1, modifier = Modifier.introRise(run, text + Motion.RISE_STAGGER_MS * (2 + i), 16.dp))
                        }
                    }
                    // intro.more: 글 시작선 60(20 + 아이콘 칸 24 + 16)에서 시작하는 글자 단추.
                    Row(
                        Modifier.padding(top = 6.dp, start = 40.dp).introRise(run, plan?.moreDelayMs() ?: 0, 16.dp)
                            .heightIn(min = 48.dp).clip(Radius.medium).pressable(enabled = moreLive, onClick = { sheet = true })
                            .semantics(mergeDescendants = true) { contentDescription = IntroText.MORE_A11Y; role = androidx.compose.ui.semantics.Role.Button }.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(IntroText.MORE, style = Type.quickLabel.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.textSecondary)
                        AIcon(R.drawable.ic_chevron_right, Modifier.padding(start = 2.dp).size(16.dp), p.textSecondary)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            IntroBar(Modifier.onSizeChanged { barH = it.height }.introRise(run, plan?.barDelayMs ?: 0, 12.dp, blur = 0.dp), barLive, onConnect, onRecordsOnly)
        }
    }
    if (sheet) IntroSheet { sheet = false }
}

/**
 * 무대(v8b-10 ④): 바탕은 타원 빛(72%×40% at 50% 58%, `surface` 70% + `stage` → 투명)이라 아래 끝이 `bg`와 이어진다.
 * 차 그림은 무대에서 위 40(상태 막대 아래)을 뺀 칸 가운데에 Fit(좌우 여백 0, 그림 안에 투명 여백이 있다). 꾸밈이라 읽지 않는다.
 * 등장(⑤ '(나) 도착'): 빛은 0ms부터 900ms `light`로 따로 나타나고, 차는 0ms부터 오른쪽 48에서 spring.smooth(1.0·385)로 들어오며 220ms `base`로 나타난다.
 * [animate]가 아니면(안무 없음·이미 함·동작 줄이기) 처음부터 보인다.
 */
@Composable
private fun IntroStage(height: androidx.compose.ui.unit.Dp, animate: Boolean, clock: () -> Float?) {
    val p = Tokens.colors
    val glow = androidx.compose.ui.graphics.lerp(p.stage, p.surface, 0.7f)
    // 차 가로 위치는 스프링이라 시계가 아니라 제 Animatable로 간다(넘침 없는 spring.smooth). 안무가 정해지기 전 장은 화면 전체가 비어 있다.
    val x = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(animate) { if (animate) { x.snapTo(48f); x.animateTo(0f, Springs.smooth.spec()) } }
    Box(
        Modifier.fillMaxWidth().height(height).drawBehind {
            val t = if (animate) clock() else null
            val a = if (t == null) 1f else Motion.light.easing.transform((t / Motion.light.ms).coerceIn(0f, 1f))
            val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.58f)
            val rx = size.width * 0.72f
            val ry = size.height * 0.40f
            scale(rx / ry, 1f, pivot = c) { drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = glow.alpha * a), glow.copy(alpha = 0f)), c, ry), ry, c) }
        },
    ) {
        androidx.compose.foundation.Image(
            painterResource(R.drawable.intro_car), null,
            Modifier.fillMaxSize().padding(top = 40.dp).graphicsLayer {
                val t = if (animate) clock() else null
                alpha = if (t == null) 1f else Motion.base.easing.transform((t / Motion.base.ms).coerceIn(0f, 1f))
                translationX = x.value.dp.toPx()
            },
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
    }
}

/** 아래 고정 단추 띠(v8b-10 ②): 위 24 흐림 + 주 단추 56 + 4 + 글자 단추 48, 아래는 시스템 막대. */
@Composable
private fun IntroBar(modifier: Modifier, live: Boolean, onConnect: () -> Unit, onRecordsOnly: () -> Unit) {
    val p = Tokens.colors
    Column(
        modifier.fillMaxWidth()
            .drawBehind { drawRect(Brush.verticalGradient(listOf(p.bg.copy(alpha = 0f), p.bg), startY = -24.dp.toPx(), endY = 0f), topLeft = androidx.compose.ui.geometry.Offset(0f, -24.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 24.dp.toPx())) }
            .background(p.bg).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 띠가 나타나기 전(투명)에는 꺼 둔다. 켜지는 순간 띠는 아직 투명이라 꺼진 모양은 보이지 않는다.
        Btn(IntroText.CONNECT, onConnect, enabled = live) // intro.connect
        Btn(IntroText.RECORDS_ONLY, onRecordsOnly, kind = BtnKind.TEXT, enabled = live) // intro.recordOnly
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/** '자세히 보기' 시트(v8b-10 ③): 제목 '오가는 정보' + 라벨·원문 넷(7.1 intro.* 글자 그대로, 사이 선) + '닫기'. */
@Composable
private fun IntroSheet(onClose: () -> Unit) {
    val p = Tokens.colors
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = p.surface,
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(36.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(p.textTertiary.copy(alpha = 0.4f))) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 22.dp)) {
            Text(IntroText.SHEET_TITLE, Modifier.padding(bottom = 6.dp).semantics { heading() }, style = Type.title, color = p.textPrimary)
            IntroText.SHEET.forEachIndexed { i, (label, text) ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(color = p.line)
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp).semantics(mergeDescendants = true) {}) {
                    Text(label, style = Type.rowTitle.copy(fontSize = 15.sp), color = p.textPrimary)
                    Text(text, Modifier.padding(top = 4.dp), style = Type.body.copy(fontSize = 15.sp, lineHeight = 23.sp), color = p.textSecondary)
                }
            }
            Btn(IntroText.SHEET_CLOSE, onClose, Modifier.padding(top = 10.dp), kind = BtnKind.TONAL)
        }
    }
}

/** 429 잠금 글([text]: 잠금이 없거나 풀렸으면 null)과 이 화면에서 본 그 잠금의 첫 글([atStart], 한 번만 알리는 데 쓴다, [pairErrorLive]). */
private class PairWait(val text: String?, val atStart: String?)

/**
 * 429 잠금 글. [PairWait.text]가 null이 아니면 버튼이 잠긴다. 끝 시각은 연결 유스케이스가 들고 있어 화면을 옮겨도 풀리지 않는다(9장).
 * 글은 끝 시각에서 지금 잰 남은 시간으로 만들고(7.2), 글이 바뀌는 분·초 경계에만 깨어 다시 그린다. 연결·확인 화면이 이것만 쓴다.
 */
@Composable
private fun rememberPairWait(until: Long?): PairWait {
    val start = remember(until) { until?.minus(System.currentTimeMillis()) }
    var left by remember(until) { mutableStateOf(start) }
    LaunchedEffect(until) {
        val u = until ?: return@LaunchedEffect
        while (true) {
            val l = u - System.currentTimeMillis()
            left = l
            if (l <= 0) break
            delay(pairWaitNextMs(l))
        }
    }
    return PairWait(left?.let { pairWaitText(it) }, start?.let { pairWaitText(it) })
}

/** 진행 표시 한 줄("확인하고 있어요"). */
@Composable
private fun Checking() {
    val p = Tokens.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = p.accent, strokeWidth = 2.5.dp)
        Text("  확인하고 있어요", style = Type.body, color = p.textSecondary)
    }
}

/** 오류 한 줄(빨간 글). [live]면 나타나거나 바뀔 때 화면 읽기가 읽는다([pairErrorLive]). */
@Composable
private fun ErrorLine(text: String, live: Boolean) =
    Text(text, Modifier.semantics { if (live) liveRegion = LiveRegionMode.Polite }, style = Type.caption, color = Tokens.colors.danger)

/**
 * 연결: QR 찍기 또는 코드 넣기(7.1). QR로 읽은 코드만 확인 화면으로 간다.
 * 직접 친 코드는 코드 대조 없이 바로 연결하고(G17, L57), 오류는 이 화면 입력칸 아래에 보인다.
 * 다만 이미 연결된 폰이면 확인 화면의 교체 확인(G16)을 먼저 거친다.
 */
@Composable
fun ConnectScreen(onCode: (String) -> Unit, onPaired: (Role) -> Unit, onCancel: (() -> Unit)? = null, vm: ConnectViewModel = appViewModel()) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // 이 화면에서 직접 시작한 연결만 본다. 확인 화면에서 돌아온 옛 결과는 보이지 않는다.
    var typed by rememberSaveable { mutableStateOf<String?>(null) }
    val mine = ui.run?.takeIf { it.code == typed }
    // 요청 중 여부는 이 화면의 입력만이 아니라 앱 전체의 연결 작업으로 본다.
    val busy = ui.run is PairRun.Running
    val wait = rememberPairWait(ui.lockedUntil)
    val locked = wait.text != null
    BackHandler(enabled = busy) {}
    LaunchedEffect(mine) {
        val done = mine ?: return@LaunchedEffect
        if (vm.takeDone(done)) onPaired((done as PairRun.Done).role!!)
    }
    fun submit(raw: String) {
        val code = normalizeCode(raw)
        when (typedSubmitAction(code, ui.connected, busy, ui.lockedUntil, System.currentTimeMillis())) {
            TypedAction.BAD_FORMAT -> error = "6자리 숫자 또는 초대 코드를 넣어 주세요"
            TypedAction.IGNORE -> {}
            TypedAction.CONFIRM_REPLACE -> onCode(code!!)
            TypedAction.PAIR -> { typed = code; vm.start(code!!) }
        }
    }
    val mineError = pairErrorShown(mine, wait.text) // 429 글은 잠금이 풀리면 걷힌다
    val fieldError = error ?: mineError // 칸 아래 한 줄: 가장 최근 오류(QR 실패·형식)가 앞선 429 글보다 먼저
    val live = error != null || pairErrorLive(mine, wait.text, wait.atStart) // 줄어드는 남은 시간은 처음 한 번만 알린다
    val p = Tokens.colors
    // U39: 탭에서 열었을 때만 '취소'(닫기). 연결 중에는 잠근다(7.2 버튼 잠금)
    BackPage("차와 연결", onBack = null, trailing = { onCancel?.let { TopAction("취소", enabled = !busy, onClick = it) } }) {
        Block {
            Text("차 화면의 QR이나 코드로 연결해요", Modifier.padding(bottom = 16.dp), style = Type.title, color = p.textPrimary)
            Btn(
                "QR 찍기",
                enabled = !busy,
                icon = R.drawable.ic_qr_code,
                big = true,
                onClick = {
                    // 카메라 권한과 스캔 화면은 Google code scanner가 맡는다(7.3). 처음에는 모듈을 내려받을 수 있다.
                    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { b ->
                            val code = b.rawValue?.let { codeFromLink(it) ?: normalizeCode(it) }
                            // 스캔 중에 직접 친 코드의 요청이 시작됐을 수 있다. 끝난 결과를 받을 화면이 사라지지 않게 간다.
                            if (code == null) error = "올바른 연결 링크가 아니에요"
                            else if (!vm.canTakeLink()) android.widget.Toast.makeText(context, PAIR_BUSY, android.widget.Toast.LENGTH_SHORT).show()
                            else if (needsConfirm(CodeSource.QR)) onCode(code)
                        }
                        .addOnFailureListener { error = "QR을 찍지 못했어요. 코드를 넣어 주세요." }
                },
            )
        }
        Block {
            Text("코드로 연결", Modifier.padding(bottom = 12.dp), style = Type.title, color = p.textPrimary)
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(12)
                    error = null
                    vm.clear()
                    if (Regex("\\d{6}").matches(text)) submit(text)
                },
                label = { Text("6자리 숫자 또는 초대 코드") },
                isError = fieldError != null,
                supportingText = { fieldError?.let { Text(it, Modifier.semantics { if (live) liveRegion = LiveRegionMode.Polite }) } },
                enabled = !busy,
                singleLine = true,
                textStyle = Type.title.copy(fontFeatureSettings = "tnum", letterSpacing = 0.1.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                shape = Radius.field,
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = p.textTertiary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) Checking()
            // 잠긴 동안 칸 아래 글이 남은 시간이 아니면(QR 실패 글이 덮었거나, 고쳐서 걷혔거나, QR로 시작한 연결) 남은 시간을 한 줄 더 보인다.
            // '확인'이 왜 꺼졌는지 늘 보이게(같은 말 두 번 금지, iOS와 같음).
            wait.text?.takeIf { it != fieldError }?.let { Text(it, style = Type.caption, color = p.textTertiary) }
            Spacer(Modifier.height(8.dp))
            Btn("확인", { submit(text) }, enabled = text.isNotBlank() && !busy && !locked)
        }
        if (ui.connected) FootNote("이미 연결된 차가 있어요. 새로 연결하면 지금 연결을 끊어요.")
    }
}

/**
 * 확인(G17): QR·링크로 들어온 코드만 거친다. 연결 코드는 "차 화면에 이 코드가 보이나요?",
 * 초대 코드는 "받은 초대 코드가 이것과 같나요?"(직접 친 코드는 ConnectScreen에서 바로 연결).
 * 이미 연결된 폰이면 지금 차를 끊을지 먼저 묻는다(G16).
 */
@Composable
fun ConfirmScreen(code: String, onDone: (Role) -> Unit, onRetry: () -> Unit, vm: ConnectViewModel = appViewModel()) {
    // 연결 작업은 앱 수명에서 끝까지 한다. 화면이 회전·재구성돼도 결과만 다시 읽는다.
    val ui by vm.ui.collectAsStateWithLifecycle()
    val mine = ui.run?.takeIf { it.code == code }
    val busy = mine is PairRun.Running
    // U49: 도는 동안에는 누른 순간의 글자를 둔다(끊기가 끝나 자격이 비어도 "연결하기"로 바뀌지 않게).
    var switching by rememberSaveable { mutableStateOf(false) }
    val replacing = if (busy) switching else ui.connected
    // iOS의 interactiveDismissDisabled(busy)와 같다: 연결 중 뒤로 가기를 막아 완료 처리가 끊기지 않게 한다.
    BackHandler(enabled = busy) {}
    LaunchedEffect(mine) {
        val done = mine ?: return@LaunchedEffect
        // 기존 연결 끊기와 새 연결이 모두 성공했고, 그사이 끊기지(GONE) 않았을 때만 완료 화면으로 간다.
        if (vm.takeDone(done)) onDone((done as PairRun.Done).role!!)
    }
    // 429 뒤에는 Retry-After(없으면 10초)까지 연결 버튼을 잠근다(9장). 직접 입력 길과 같은 값이다.
    val wait = rememberPairWait(ui.lockedUntil)
    val shown =if (isInviteCode(code)) "${code.take(5)}-${code.drop(5)}" else code
    val p = Tokens.colors
    BackPage("확인", onBack = null) {
        Block {
            Text(confirmTitle(code), style = Type.title, color = p.textPrimary)
            Text(shown, Modifier.padding(vertical = 14.dp), style = Type.pairCode, color = p.textPrimary)
            if (isInviteCode(code)) Text("다른 보호자가 보낸 초대 코드예요. 리포트와 인계 알림을 받아요.", style = Type.caption, color = p.textTertiary)
            if (replacing) Text("지금 연결된 차를 끊고 새 차에 연결할까요?", Modifier.padding(top = 8.dp), style = Type.body, color = p.textSecondary)
        }
        if (busy) Checking()
        val error = pairErrorShown(mine, wait.text)
        error?.let { ErrorLine(it, live = pairErrorLive(mine, wait.text, wait.atStart)) } // 429 글은 잠금 동안만, 알림은 처음 한 번
        // 다른 코드(직접 입력)로 받은 429로 잠긴 동안에도 남은 시간을 보인다: '연결하기'가 왜 꺼졌는지(연결 화면과 같은 규칙, 같은 글 두 번 없이).
        wait.text?.takeIf { it != error }?.let { Text(it, style = Type.caption, color = p.textTertiary) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (replacing) "끊고 연결하기" else "연결하기", { switching = ui.connected; vm.start(code) }, enabled = !busy && wait.text == null, big = true) // U49 pair.confirmSwitch / pair.confirm
            Btn("코드 다시 넣기", onRetry, kind = BtnKind.TONAL, enabled = !busy)
        }
    }
}

/** 연결됐어요(v8b-10 ⑧ 결과 화면). guardian은 받을 푸시가 있어 이때 알림 권한을 묻는다(G13). */
@Composable
fun DoneScreen(role: Role, onNext: () -> Unit) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onNext() }
    val guardian = role == Role.GUARDIAN
    ResultPage(R.drawable.ic_check, "연결됐어요", if (guardian) "리포트와 인계 알림을\n이 휴대폰으로 받아요" else "수유 시작과 우리 노래를 차로 보낼 수 있어요") {
        Btn(if (guardian) "알림 켜고 시작하기" else "시작하기", {
            if (guardian && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else onNext()
        })
    }
}

/** 연결이 끊겼어요(401·닫기 4001, 9장·U25 a). 연결됐어요와 같은 결과 화면(v8b-10 ⑧, copy-audit P10). */
@Composable
fun GoneScreen(onReconnect: () -> Unit, onRecordsOnly: () -> Unit) {
    ResultPage(R.drawable.ic_unlink, "연결이 끊겼어요", "돌봄 기록은 이 휴대폰에 남아요", child = true) {
        Btn("다시 연결하기", onReconnect)
        Btn("기록만 계속 쓸게요", onRecordsOnly, kind = BtnKind.TEXT)
    }
}
