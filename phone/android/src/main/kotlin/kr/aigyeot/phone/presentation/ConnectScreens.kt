package kr.aigyeot.phone.presentation

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
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
import kr.aigyeot.phone.domain.model.PAIR_BUSY
import kr.aigyeot.phone.domain.model.PairRun
import kr.aigyeot.phone.domain.model.TypedAction
import kr.aigyeot.phone.domain.model.codeFromLink
import kr.aigyeot.phone.domain.model.confirmTitle
import kr.aigyeot.phone.domain.model.isInviteCode
import kr.aigyeot.phone.domain.model.needsConfirm
import kr.aigyeot.phone.domain.model.normalizeCode
import kr.aigyeot.phone.domain.model.pairErrorLive
import kr.aigyeot.phone.domain.model.pairErrorShown
import kr.aigyeot.phone.domain.model.pairWaitNextMs
import kr.aigyeot.phone.domain.model.pairWaitText
import kr.aigyeot.phone.domain.model.typedSubmitAction
import kr.aigyeot.shared.Role

// 연결 흐름. 첫 안내(차 연결)와 같은 결로 나머지 단계도 흰 블록 + 큰 버튼.


/** 429 잠금 글([text]: 잠금이 없거나 풀렸으면 null)과 이 화면에서 본 그 잠금의 첫 글([atStart], 한 번만 알리는 데 쓴다, [pairErrorLive]). */
private class PairWait(val text: String?, val atStart: String?)

/**
 * 429 잠금 글. [PairWait.text]가 null이 아니면 버튼이 잠긴다. 끝 시각은 연결 유스케이스가 들고 있어 화면을 옮겨도 풀리지 않는다.
 * 글은 끝 시각에서 지금 잰 남은 시간으로 만들고, 글이 바뀌는 분·초 경계에만 깨어 다시 그린다. 연결·확인 화면이 이것만 쓴다.
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
 * 연결: QR 찍기 또는 코드 넣기. QR로 읽은 코드만 확인 화면으로 간다.
 * 직접 친 코드는 코드 대조 없이 바로 연결하고, 오류는 이 화면 입력칸 아래에 보인다.
 * 다만 이미 연결된 폰이면 확인 화면의 교체 확인을 먼저 거친다.
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
    // 탭에서 열었을 때만 '취소'(닫기). 연결 중에는 잠근다(버튼 잠금)
    BackPage("차와 연결", onBack = null, trailing = { onCancel?.let { TopAction("취소", enabled = !busy, onClick = it) } }) {
        Block {
            Text("차 화면의 QR이나 코드로 연결해요", Modifier.padding(bottom = 16.dp), style = Type.title, color = p.textPrimary)
            Btn(
                "QR 찍기",
                enabled = !busy,
                icon = R.drawable.ic_qr_code,
                big = true,
                onClick = {
                    // 카메라 권한과 스캔 화면은 Google code scanner가 맡는다. 처음에는 모듈을 내려받을 수 있다.
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
 * 확인: QR·링크로 들어온 코드만 거친다. 연결 코드는 "차 화면에 이 코드가 보이나요?",
 * 초대 코드는 "받은 초대 코드가 이것과 같나요?"(직접 친 코드는 ConnectScreen에서 바로 연결).
 * 이미 연결된 폰이면 지금 차를 끊을지 먼저 묻는다.
 */
@Composable
fun ConfirmScreen(code: String, onDone: (Role) -> Unit, onRetry: () -> Unit, vm: ConnectViewModel = appViewModel()) {
    // 연결 작업은 앱 수명에서 끝까지 한다. 화면이 회전·재구성돼도 결과만 다시 읽는다.
    val ui by vm.ui.collectAsStateWithLifecycle()
    val mine = ui.run?.takeIf { it.code == code }
    val busy = mine is PairRun.Running
    // 도는 동안에는 누른 순간의 글자를 둔다(끊기가 끝나 자격이 비어도 "연결하기"로 바뀌지 않게).
    var switching by rememberSaveable { mutableStateOf(false) }
    val replacing = if (busy) switching else ui.connected
    // iOS의 interactiveDismissDisabled(busy)와 같다: 연결 중 뒤로 가기를 막아 완료 처리가 끊기지 않게 한다.
    BackHandler(enabled = busy) {}
    LaunchedEffect(mine) {
        val done = mine ?: return@LaunchedEffect
        // 기존 연결 끊기와 새 연결이 모두 성공했고, 그사이 끊기지(GONE) 않았을 때만 완료 화면으로 간다.
        if (vm.takeDone(done)) onDone((done as PairRun.Done).role!!)
    }
    // 429 뒤에는 Retry-After(없으면 10초)까지 연결 버튼을 잠근다. 직접 입력 길과 같은 값이다.
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
            Btn(
                if (replacing) "끊고 연결하기" else "연결하기",
                { switching = ui.connected; vm.start(code) },
                enabled = !busy && wait.text == null,
                big = true,
            )
            Btn("코드 다시 넣기", onRetry, kind = BtnKind.TONAL, enabled = !busy)
        }
    }
}

/** 연결됐어요(결과 화면). guardian은 받을 푸시가 있어 이때 알림 권한을 묻는다. */
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

/** 연결이 끊겼어요(401·닫기 4001). 연결됐어요와 같은 결과 화면. */
@Composable
fun GoneScreen(onReconnect: () -> Unit, onRecordsOnly: () -> Unit) {
    ResultPage(R.drawable.ic_unlink, "연결이 끊겼어요", "돌봄 기록은 이 휴대폰에 남아요", child = true) {
        Btn("다시 연결하기", onReconnect)
        Btn("기록만 계속 쓸게요", onRecordsOnly, kind = BtnKind.TEXT)
    }
}
