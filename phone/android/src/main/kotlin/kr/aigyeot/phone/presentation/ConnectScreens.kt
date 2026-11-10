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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import kr.aigyeot.phone.domain.model.PAIR_BUSY
import kr.aigyeot.phone.domain.model.PairRun
import kr.aigyeot.phone.domain.model.TypedAction
import kr.aigyeot.phone.domain.model.codeFromLink
import kr.aigyeot.phone.domain.model.confirmTitle
import kr.aigyeot.phone.domain.model.isInviteCode
import kr.aigyeot.phone.domain.model.isPairLocked
import kr.aigyeot.phone.domain.model.needsConfirm
import kr.aigyeot.phone.domain.model.normalizeCode
import kr.aigyeot.phone.domain.model.typedSubmitAction
import kr.aigyeot.shared.Role

// 연결 흐름(G13·G16·G17·G31). 디자인 v6 시안 phone.js S.intro(차 연결 · 첫 안내)와 같은 결로 나머지 단계도 흰 블록 + 큰 버튼.

/**
 * 첫 실행: 무엇이 오가는지 한 화면(G31, 시안 S.intro). 위 250 무대(3D 차 + 앱 아이콘), 아래 '아이곁'·첫 줄·안내 목록·두 버튼.
 * 글자와 순서는 7.1 intro.*(U43, iOS와 같음): 주고받는 것 · 보내지 않는 것 · 거치는 곳 · AI.
 */
@Composable
fun IntroScreen(onConnect: () -> Unit, onRecordsOnly: () -> Unit) {
    val p = Tokens.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        Box(
            Modifier.fillMaxWidth().height(250.dp)
                .background(Brush.radialGradient(listOf(if (p.dark) Color(0xFF232931) else lerpColor(p.stage, Color.White, 0.6f), p.bg), radius = 700f)),
        ) {
            CarStage(Modifier.fillMaxSize().padding(top = 40.dp), preset = CarPreset.PHONE)
            Box(Modifier.padding(start = 20.dp, top = 16.dp)) { AppMark(52.dp, 16.dp) }
        }
        StaggerColumn(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), gap = 18.dp) {
            Column {
                Text("아이곁", style = Type.display, color = p.textPrimary) // intro.title
                Text("차의 아이곁과 이 휴대폰을 연결해요.", Modifier.padding(top = 6.dp), style = Type.body.copy(fontWeight = FontWeight.Medium), color = p.textSecondary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IntroLine(R.drawable.ic_link, "주고받는 것: 수유 시작 시각, 우리 노래 녹음, 수유·잠·기저귀 기록 요약(시각·횟수·잠 시간), 주행 리포트, 인계 알림 문장")
                IntroLine(R.drawable.ic_x, "보내지 않는 것: 아기 울음 소리, 차 안 마이크 소리, 차의 위치", child = true)
                IntroLine(R.drawable.ic_shield_check, "모두 아이곁 중계 서버를 거쳐 오가요. 인계 알림은 Google·Apple 알림 서버도 거쳐요.")
                IntroLine(R.drawable.ic_sparkles, "차에서 아기 상황을 물으면, 답을 만들려고 수유·잠·기저귀 숫자를 AI 서버로 보내요.")
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("차와 연결하기", onConnect, icon = R.drawable.ic_qr_code, big = true)
                Btn("먼저 기록만 쓸게요", onRecordsOnly, kind = BtnKind.TONAL)
            }
        }
    }
}

/** 안내 한 줄(.ilist: 34 회색 칸 아이콘 + 13.5/20·500 흐린 글). 보내지 않는 것은 아이 주황 아이콘. */
@Composable
private fun IntroLine(@DrawableRes icon: Int, text: String, child: Boolean = false) {
    val p = Tokens.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(p.tonal), contentAlignment = Alignment.Center) {
            AIcon(icon, Modifier.size(18.dp), if (child) p.child else p.textSecondary)
        }
        Text(text, Modifier.weight(1f).padding(top = 6.dp), style = Type.body.copy(fontSize = 13.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium), color = p.textSecondary)
    }
}

/** 429 잠금이 남았는가. 시각은 연결 유스케이스가 들고 있고, 끝나는 때에 다시 그린다(화면을 옮겨도 풀리지 않는다, 9장). */
@Composable
private fun rememberPairLocked(until: Long?): Boolean {
    var locked by remember(until) { mutableStateOf(isPairLocked(until, System.currentTimeMillis())) }
    LaunchedEffect(until) {
        val u = until ?: return@LaunchedEffect
        delay((u - System.currentTimeMillis()).coerceAtLeast(0))
        locked = false
    }
    return locked
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

/** 오류 한 줄(빨간 글, 바뀌면 화면 읽기가 읽는다). */
@Composable
private fun ErrorLine(text: String) = Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Type.body.copy(fontSize = 14.sp), color = Tokens.colors.danger)

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
    val locked = rememberPairLocked(ui.lockedUntil)
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
    val mineError = (mine as? PairRun.Failed)?.error
    val p = Tokens.colors
    // U39: 탭에서 열었을 때만 '취소'(닫기). 연결 중에는 잠근다(7.2 버튼 잠금)
    BackPage("차와 연결", onBack = null, trailing = { onCancel?.let { TopAction("취소", enabled = !busy, onClick = it) } }) {
        Block {
            Text("차 화면의 QR을 비춰 주세요", Modifier.padding(bottom = 16.dp), style = Type.title, color = p.textPrimary)
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
                        .addOnFailureListener { error = "QR을 찍지 못했어요. 코드로 입력해 주세요." }
                },
            )
        }
        Block {
            Text("코드로 입력", Modifier.padding(bottom = 12.dp), style = Type.title, color = p.textPrimary)
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(12)
                    error = null
                    vm.clear()
                    if (Regex("\\d{6}").matches(text)) submit(text)
                },
                label = { Text("6자리 숫자 또는 초대 코드") },
                isError = (error ?: mineError) != null,
                supportingText = { (error ?: mineError)?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
                enabled = !busy,
                singleLine = true,
                textStyle = Type.title.copy(fontFeatureSettings = "tnum", letterSpacing = 0.1.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = p.textTertiary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) Checking()
            if (locked) Text("잠시 뒤에 다시 해 주세요.", style = Type.caption, color = p.textTertiary)
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
    val locked = rememberPairLocked(ui.lockedUntil)
    val shown = if (isInviteCode(code)) "${code.take(5)}-${code.drop(5)}" else code
    val p = Tokens.colors
    BackPage("확인", onBack = null) {
        Block {
            Text(confirmTitle(code), style = Type.title, color = p.textPrimary)
            Text(shown, Modifier.padding(vertical = 14.dp), style = Type.hero.copy(fontSize = 36.sp, lineHeight = 44.sp, fontFeatureSettings = "tnum", letterSpacing = 0.08.sp), color = p.textPrimary)
            if (isInviteCode(code)) Text("다른 보호자 초대 코드예요. 리포트와 인계 알림을 받아요.", style = Type.caption, color = p.textTertiary)
            if (replacing) Text("지금 연결된 차를 끊고 새 차에 연결할까요?", Modifier.padding(top = 8.dp), style = Type.body, color = p.textSecondary)
        }
        if (busy) Checking()
        (mine as? PairRun.Failed)?.error?.let { ErrorLine(it) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (replacing) "끊고 연결하기" else "연결하기", { switching = ui.connected; vm.start(code) }, enabled = !busy && !locked, big = true) // U49 pair.confirmSwitch / pair.confirm
            Btn("코드 다시 넣기", onRetry, kind = BtnKind.TONAL, enabled = !busy)
        }
    }
}

/** 연결됐어요. guardian은 받을 푸시가 있어 이때 알림 권한을 묻는다(G13). */
@Composable
fun DoneScreen(role: Role, onNext: () -> Unit) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onNext() }
    val p = Tokens.colors
    BackPage("연결됐어요", onBack = null) {
        Block {
            Box(Modifier.padding(bottom = 14.dp).size(48.dp).clip(RoundedCornerShape(24.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                AIcon(R.drawable.ic_check, Modifier.size(24.dp), p.onAccentSoft)
            }
            Text(if (role == Role.GUARDIAN) "리포트와 인계 알림을 이 휴대폰으로 받아요." else "수유 시작과 우리 노래를 차로 보낼 수 있어요.", style = Type.headline, color = p.textPrimary)
        }
        Btn(if (role == Role.GUARDIAN) "알림 켜고 시작하기" else "시작하기", {
            if (role == Role.GUARDIAN && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else onNext()
        }, big = true)
    }
}

/** 401·닫기 4001(9장). */
@Composable
fun GoneScreen(onReconnect: () -> Unit, onRecordsOnly: () -> Unit) {
    val p = Tokens.colors
    BackPage("연결이 끊겼어요", onBack = null) {
        Block {
            Box(Modifier.padding(bottom = 14.dp).size(48.dp).clip(RoundedCornerShape(24.dp)).background(p.childSoft), contentAlignment = Alignment.Center) {
                AIcon(R.drawable.ic_unlink, Modifier.size(24.dp), p.child)
            }
            Text("연결이 끊겼어요. 다시 연결해 주세요.", style = Type.headline, color = p.textPrimary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("다시 연결하기", onReconnect, big = true)
            Btn("기록만 계속 쓸게요", onRecordsOnly, kind = BtnKind.TONAL)
        }
    }
}
