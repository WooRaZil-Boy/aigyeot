package kr.aigyeot.app.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kr.aigyeot.app.R
import kr.aigyeot.app.link.LinkConn
import kr.aigyeot.app.link.LinkStatus
import kr.aigyeot.app.ui.car3d.Light3d
import kr.aigyeot.app.ui.car3d.Stage3d
import kr.aigyeot.app.ui.car3d.View3d
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 연결 화면(contracts-link 8.7, 시안 connect()): 열면 코드를 받고(①), 닫으면 지운다(④). 왼쪽 QR·코드·남은 시간, 오른쪽 연결된 휴대폰·끊기·목록 새로 보기와 연결 그림.
 * 연결 상태·"연결됐어요"·오류 문장(8.6)은 맨 위에 그대로 둔다.
 */
@Composable
fun ConnectScreen(
    ui: ConnectUiState, onOpen: () -> Unit, onClose: () -> Unit, onDisconnect: (String) -> Unit, onRefresh: () -> Unit, onBack: () -> Unit,
) = Column(Modifier.fillMaxSize()) {
    DisposableEffect(Unit) { onOpen(); onDispose { onClose() } }
    val st = ui.link
    Header("휴대폰 연결", onBack)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < NarrowPage
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 44.dp, end = 44.dp, top = 32.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinkLines(st)
            val left: @Composable ColumnScope.() -> Unit = { st.pairCode?.let { QrCard(it, st.pairUri, ui.remainingS) } }
            val right: @Composable ColumnScope.() -> Unit = { Phones(st, onDisconnect, onRefresh) }
            if (narrow) Column(verticalArrangement = Arrangement.spacedBy(36.dp)) { left(); right() }
            else Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Column(Modifier.weight(1f), content = left)
                Column(Modifier.weight(1f), content = right)
            }
        }
    }
}

/** QR 옆 코드 칸의 최소 폭: '123 456'이 자동 크기 최소(48sp)로 한 줄에 들어가는 폭(약 220dp). 시안 차 창 1706의 두 열(코드 칸 약 323)은 옆에 둔다. */
private val QR_CODE_MIN = 240.dp

/** QR 카드(시안 .qrcard): 실제 QR(가운데 앱 로고) + 안내 + 코드(3자리 띄움) + 남은 시간. 시안 QR은 장식이라 무늬는 쓰지 않는다(IMPLEMENT 4장). */
@Composable
private fun QrCard(code: String, uri: String?, remainingS: Long?) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val p = LocalPalette.current
    // 카드 실제 폭으로 정한다(창 폭이 아니라): QR 옆 코드 칸이 QR_CODE_MIN보다 좁으면(두 열 1280 등) 코드를 QR 아래에 둔다(6자리가 잘리지 않게)
    // 시안: 넓으면 QR 360, 좁은 창이면 QR 260을 옆에. 그래도 코드 칸이 모자라면 QR 260 아래에 코드
    val qr = if (maxWidth >= 36.dp * 2 + 360.dp + 36.dp + QR_CODE_MIN) 360.dp else 260.dp
    val stacked = maxWidth < 36.dp * 2 + qr + 36.dp + QR_CODE_MIN
    val body: @Composable (@Composable () -> Unit) -> Unit = { c ->
        val m = Modifier.fillMaxWidth().clip(RoundedCornerShape(CarRadius.l)).background(p.surface).padding(36.dp)
        if (stacked) Column(m, verticalArrangement = Arrangement.spacedBy(28.dp)) { c() } else Row(m, horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) { c() }
    }
    body {
        // 다크에서도 흰 판 위 검은 무늬: 뒤집힌 QR은 찍지 못하는 카메라가 있다
        val plate = RoundedCornerShape(CarRadius.l)
        Box(Modifier.width(qr).aspectRatio(1f).clip(plate).background(Color.White).border(2.dp, p.line, plate).padding(20.dp)) {
            uri?.let { u -> remember(u) { pairQr(u) }?.let { QrImage(it) } }
        }
        Column(Modifier.fillMaxWidth()) {   // 가로 줄에서는 QR 뒤 남은 폭 전부
            Text("휴대폰 앱에서 QR을 찍거나 코드를 넣어 주세요", style = CarType.label.copy(fontWeight = FontWeight(600)), color = p.textTertiary)
            BasicText(
                code.chunked(3).joinToString(" "), Modifier.padding(top = 10.dp),
                style = CarType.numeral.copy(lineHeight = 80.sp, fontWeight = FontWeight(300), letterSpacing = 0.08.em, fontFeatureSettings = "tnum", color = p.textPrimary),   // 시안 .code 300 72/80
                maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = CarType.headlineS.fontSize, maxFontSize = CarType.numeral.fontSize),
            )
            remainingS?.let { s ->
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineIcon(R.drawable.ic_timer, 30.dp, p.textSecondary)
                    Text(remainingText(s), style = CarType.label.copy(fontSize = CarType.bodyS.fontSize, fontWeight = FontWeight(600)), color = p.textSecondary)
                }
            }
        }
    }
}

/** QR 그림: 모듈 하나 = 비트맵 1px(최근접 확대로 또렷하게) + 비워 둔 가운데 칸에 앱 로고(흰 둥근 칸). */
@Composable
private fun QrImage(qr: PairQr) = Box(Modifier.fillMaxSize(), Alignment.Center) {
    val bmp = remember(qr) {
        val m = qr.matrix
        val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bmp, "연결 QR", Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
    val f = qr.logo.toFloat() / qr.matrix.width
    Icon(painterResource(R.drawable.ic_app_mark), null, Modifier.fillMaxSize(f).clip(RoundedCornerShape(27)).background(Color.White), tint = Color.Unspecified)
}

/** 연결 QR과 가운데 로고 칸 한 변(모듈 수). [matrix]의 로고 칸은 이미 비웠다. */
internal class PairQr(val matrix: BitMatrix, val logo: Int)

/** QR 가장자리 빈칸(모듈). 흰 판 안 여백 20이 더해져 조용한 영역이 4칸 안팎이 된다. */
internal const val QR_MARGIN = 2

/** 로고가 가리는 면적 상한(기호 면적 대비). 오류 정정 H는 약 30%를 되살리므로 그 1/5로 둔다. */
internal const val QR_LOGO_AREA = 0.06

/** 가운데 로고 칸 한 변(모듈). 면적이 [QR_LOGO_AREA] 이하이고, (n − 한 변)이 짝수라 모듈 칸에 딱 맞게 가운데 놓인다. */
internal fun qrLogoModules(n: Int): Int {
    val l = floor(n * sqrt(QR_LOGO_AREA)).toInt()
    return if ((n - l) % 2 == 0) l else l - 1
}

/** 연결 링크(aigyeot://pair?code=…)의 QR(ZXing, 오류 정정 H). 가운데 로고 칸의 모듈을 비운다. 만들지 못하면 null. */
internal fun pairQr(text: String): PairQr? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to QR_MARGIN))
    val l = qrLogoModules(m.width - 2 * QR_MARGIN)
    val o = (m.width - l) / 2
    for (y in o until o + l) for (x in o until o + l) m.unset(x, y)
    PairQr(m, l)
}.getOrNull()

/** 연결된 휴대폰(시안 '연결된 휴대폰 N대' + 행 + 끊기) + 목록 새로 보기 + 연결 그림. */
@Composable
private fun Phones(st: LinkStatus, onDisconnect: (String) -> Unit, onRefresh: () -> Unit) {
    Sect("연결된 휴대폰", st.phones.size.takeIf { it > 0 }?.let { "${it}대" })
    if (st.phones.isEmpty()) Body("아직 없어요")
    st.phones.forEach { ph ->
        val (title, sub) = phoneLines(ph)
        ListRow(title, sub, R.drawable.ic_smartphone) { CarButton("끊기", size = BtnSize.S) { onDisconnect(ph.deviceId) } }
    }
    CarButton("목록 새로 보기", Modifier.padding(top = 12.dp), size = BtnSize.M, icon = R.drawable.ic_rotate_ccw, onClick = onRefresh)
    LinkArt()
}

/** 연결 그림(시안 .linkart): 3D 차 정지 그림(TILE) + 폰 둘 + 점선. 점선은 정차 화면이라 흐르고, 동작 줄이기면 멈춘다. */
@Composable
private fun LinkArt() {
    val p = LocalPalette.current
    val phase: State<Float>? = if (reducedMotion()) null
        else rememberInfiniteTransition("link").animateFloat(0f, -DASH * 2, infiniteRepeatable(tween(Tokens.loopMs.getValue("dash"), easing = LinearEasing)), label = "dash")
    val glow = lerp(p.stage, Color.White, 0.45f)
    Box(
        Modifier.padding(top = 20.dp).fillMaxWidth().height(300.dp).clip(RoundedCornerShape(CarRadius.l))
            .drawBehind { drawRect(Brush.radialGradient(listOf(glow, p.stage), Offset(size.width / 2, size.height * 0.7f), size.width * 0.6f)) },
    ) {
        Car3dStill(Stage3d(view = View3d.CABIN, light = Light3d.LISTEN), Modifier.fillMaxWidth(0.84f).fillMaxHeight(0.94f).align(Alignment.TopCenter))
        Canvas(Modifier.fillMaxSize().padding(start = 70.dp, end = 70.dp, top = 40.dp, bottom = 70.dp)) {
            val sx = size.width / 100f; val sy = size.height / 60f
            fun curve(x0: Float, c1: Float, c2: Float) = Path().apply { moveTo(x0 * sx, 48 * sy); cubicTo(c1 * sx, 10 * sy, c2 * sx, 10 * sy, 50 * sx, 30 * sy) }
            val stroke = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.dp.toPx(), DASH.dp.toPx()), (phase?.value ?: 0f).dp.toPx()))
            drawPath(curve(8f, 30f, 45f), p.accent, style = stroke)
            drawPath(curve(92f, 70f, 55f), p.accent, style = stroke)
        }
        PhoneChip(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 30.dp))
        PhoneChip(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 30.dp))
    }
}

private const val DASH = 10f   // 점선 한 칸(dp)

@Composable
private fun PhoneChip(modifier: Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.size(72.dp).shadow(12.dp, shape).clip(shape).background(p.surface), Alignment.Center) { LineIcon(R.drawable.ic_smartphone, 38.dp, p.accentInk) }
}

/** 정차 화면의 연결 상태 한 줄(contracts-link 8.6, 한 칸의 상태라 모두 명사형, C385). NONE은 표시하지 않는다. CONNECTED는 연결된 폰이 있을 때만 "연결됨"(C106). */
internal fun connText(c: LinkConn, hasPhone: Boolean): String? = when (c) {
    LinkConn.NONE -> null
    LinkConn.CONNECTING -> "휴대폰과 연결하는 중"
    LinkConn.CONNECTED -> if (hasPhone) "휴대폰 연결됨" else "연결된 휴대폰 없음"
    LinkConn.BACKOFF -> "휴대폰과 다시 연결하는 중"
}

/**
 * "연결됐어요"를 마지막으로 보여 준 기기 id(8.4 '새 행이면 화면에 연결됐어요', 8.7 ③). 프로세스 수명이라 다른 화면에 있거나 주행 제한으로
 * 가려진 동안 붙은 기기도 다음에 정차 화면을 그릴 때 한 번 보인다.
 */
internal class PairedNotice {
    private var shown: String? = null
    /** 정차 화면이 [now]를 실제로 그렸을 때 부른다. 아직 보여 주지 않은 기기면 true를 주고 보여 준 것으로 적는다. */
    @Synchronized fun take(now: String?): Boolean = (now != null && now != shown).also { if (it) shown = now }
}

internal val pairedNotice = PairedNotice()

internal class LinkLine(val text: String, val error: Boolean = false)

/** 연결 상태·"연결됐어요"([paired])·오류 문장(link가 넣은 그대로, 8.6 '차 토큰 무효' 등) 순서. */
internal fun linkLines(st: LinkStatus, paired: Boolean): List<LinkLine> = listOfNotNull(
    connText(st.conn, st.phones.isNotEmpty())?.let(::LinkLine),
    if (paired) LinkLine("연결됐어요") else null,
    st.error?.let { LinkLine(it, error = true) },
)

/** 홈·연결 화면이 같이 쓰는 연결 표시. "연결됐어요"는 보여 준 방문 동안(화면을 떠날 때까지) 남는다. */
@Composable
internal fun LinkLines(st: LinkStatus) {
    var paired by remember { mutableStateOf(false) }
    LaunchedEffect(st.justPaired) { if (pairedNotice.take(st.justPaired)) paired = true }   // 그린 뒤에만 본 것으로 적는다
    linkLines(st, paired).forEach { Body(it.text, if (it.error) LocalPalette.current.danger else Color.Unspecified) }
}

/** 노래 고르기 화면(G20, 시안 목록 행 결). 고른 노래는 다음 주행부터 쓴다. */
@Composable
fun SongsScreen(rows: List<SongRow>, onSelect: (String) -> Unit, onBack: () -> Unit) =
    Page("우리 노래", onBack) {
        if (rows.isEmpty()) Body("휴대폰에서 노래를 보내면 여기 보여요")
        rows.forEach { s ->
            ListRow(s.label, icon = R.drawable.ic_song, selected = s.selected) {
                CarButton("실험에 쓰기", size = BtnSize.M, enabled = !s.selected) { onSelect(s.id) }
            }
        }
        Note("고른 노래는 다음 주행부터 써요")
    }
