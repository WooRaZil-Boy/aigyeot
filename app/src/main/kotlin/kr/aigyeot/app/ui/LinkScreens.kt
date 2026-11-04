package kr.aigyeot.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kr.aigyeot.app.link.Link
import kr.aigyeot.app.link.LinkConn
import kr.aigyeot.app.link.LinkStatus
import kr.aigyeot.app.link.db.SongSelectionEntity
import kr.aigyeot.app.store.db.SongEntity
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 연결 화면(contracts-link 8.7): 열면 코드를 받고(①), 닫으면 지운다(④). 코드·QR·남은 시간·폰 목록·끊기. */
@Composable
fun ConnectScreen(link: Link, clock: () -> Long, onBack: () -> Unit) = Page("휴대폰 연결", onBack) {
    DisposableEffect(link) { link.openPairing(); onDispose { link.closePairing() } }
    val st by link.status.collectAsState()
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(st.pairExpiresAt) { while (st.pairExpiresAt != null) { now = clock(); delay(1_000) } }
    LinkLines(st)
    val code = st.pairCode
    if (code != null) {
        Body("휴대폰 앱에서 QR을 찍거나 코드를 넣어 주세요")
        Text(code.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineSmall)
        st.pairUri?.let { uri -> remember(uri) { qr(uri) }?.let { Image(it, "연결 QR", Modifier.size(320.dp)) } }
        st.pairExpiresAt?.let { Label("남은 시간 ${((it - now) / 1000).coerceAtLeast(0) / 60}분 ${((it - now) / 1000).coerceAtLeast(0) % 60}초") }
    }
    Label("연결된 휴대폰")
    if (st.phones.isEmpty()) Body("아직 없어요")
    st.phones.forEach { ph ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(2f)) {
                Body((if (ph.role == "owner") "함께 타는 보호자" else "다른 보호자") + (ph.lastSeenAt?.let { " · 마지막 연락 ${hm(it)}" } ?: ""))
            }
            Box(Modifier.weight(1f)) { BigButton("끊기", primary = false) { link.disconnect(ph.deviceId) } }
        }
    }
    BigButton("목록 새로 보기", primary = false) { link.refreshPhones() }
}

/** 정차 화면의 연결 상태 한 줄(contracts-link 8.6). NONE은 표시하지 않는다. CONNECTED는 연결된 폰이 있을 때만 "연결됨"(C106). */
internal fun connText(c: LinkConn, hasPhone: Boolean): String? = when (c) {
    LinkConn.NONE -> null
    LinkConn.CONNECTING -> "휴대폰 연결: 붙는 중"
    LinkConn.CONNECTED -> if (hasPhone) "휴대폰 연결됨" else "아직 연결된 휴대폰이 없어요"
    LinkConn.BACKOFF -> "휴대폰 연결: 다시 붙는 중"
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
    linkLines(st, paired).forEach { Body(it.text, if (it.error) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) }
}

/** 노래 고르기(G20, 명세서 5장): 받은 부모 노래 중 실험에 쓸 곡을 고른다. 후보 ③ 다시 계산은 store가 한다. */
@Composable
fun SongsScreen(songs: List<SongEntity>, selection: SongSelectionEntity?, methods: List<Method>, onSelect: (String) -> Unit, onBack: () -> Unit) =
    Page("부모 노래", onBack) {
        val third = methods.firstOrNull { it.kind == MethodKind.SONG }?.songId
        if (songs.isEmpty()) Body("휴대폰에서 노래를 보내면 여기 보여요")
        songs.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(2f)) {
                    Body((s.name ?: "이름 없는 노래") + (s.durationS?.let { " · ${it.toInt()}초" } ?: "") + if (s.id == third) " · 실험에 쓰는 곡" else "")
                }
                Box(Modifier.weight(1f)) { BigButton("이 곡 쓰기", enabled = s.id != selection?.songId) { onSelect(s.id) } }
            }
        }
        Label("고른 곡은 다음 주행부터 써요")
    }

private val HM = DateTimeFormatter.ofPattern("MM.dd HH:mm")
internal fun hm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HM)

private fun qr(text: String): ImageBitmap? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()
