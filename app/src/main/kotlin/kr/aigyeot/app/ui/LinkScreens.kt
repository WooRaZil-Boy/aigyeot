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
    Body(if (st.online) "휴대폰 연결됨" else if (st.registered) "휴대폰 연결: 다시 붙는 중" else "휴대폰 연결: 붙는 중")
    st.error?.let { Body(it, MaterialTheme.colorScheme.error) }
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
