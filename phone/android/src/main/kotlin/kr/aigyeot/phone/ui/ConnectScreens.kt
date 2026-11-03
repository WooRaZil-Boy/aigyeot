package kr.aigyeot.phone.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kr.aigyeot.phone.AppModel
import kotlinx.coroutines.delay
import kr.aigyeot.phone.core.codeFromLink
import kr.aigyeot.phone.core.isInviteCode
import kr.aigyeot.phone.core.normalizeCode
import kr.aigyeot.shared.Role

/** 첫 실행: 무엇이 오가는지 한 화면(G31). */
@Composable
fun IntroScreen(onNext: () -> Unit) = Page("아이곁") {
    Text("차의 아이곁과 이 휴대폰을 연결해요.", style = MaterialTheme.typography.titleMedium)
    Text("주고받는 것: 수유 시작 시각, 우리 노래 녹음, 주행 리포트, 인계 알림 문장.")
    Text("보내지 않는 것: 아기 울음 소리, 차 안 마이크 소리, 차의 위치.")
    Muted("인계 알림은 Google·Apple 알림 서버를 거쳐 알림 문장만 갑니다.")
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("연결하기") }
}

/** 연결: QR 찍기 또는 코드 넣기(7.1). 6자리를 다 넣으면 바로 확인 화면으로 간다. */
@Composable
fun ConnectScreen(model: AppModel, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit(raw: String) {
        val code = normalizeCode(raw)
        if (code == null) error = "6자리 숫자 또는 초대 코드를 넣어 주세요" else onCode(code)
    }
    Page("차와 연결") {
        Text("차 화면의 QR을 비춰 주세요", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = {
                // 카메라 권한과 스캔 화면은 Google code scanner가 맡는다(7.3). 처음에는 모듈을 내려받을 수 있다.
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { b ->
                        val code = b.rawValue?.let { codeFromLink(it) ?: normalizeCode(it) }
                        if (code == null) error = "올바른 연결 링크가 아니에요" else onCode(code)
                    }
                    .addOnFailureListener { error = "QR을 찍지 못했어요. 코드로 입력해 주세요." }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("QR 찍기") }
        Text("코드로 입력", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.take(12)
                error = null
                if (Regex("\\d{6}").matches(text)) submit(text)
            },
            label = { Text("6자리 숫자 또는 초대 코드") },
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { submit(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("확인") }
        if (model.creds.value != null) Muted("이미 연결된 차가 있어요. 새로 연결하면 지금 연결을 끊어요.")
    }
}

/**
 * 확인(G17): QR·링크·코드 셋 다 "차 화면에 이 코드가 보이나요?"를 거친다.
 * 이미 연결된 폰이면 지금 차를 끊을지 먼저 묻는다(G16).
 */
@Composable
fun ConfirmScreen(model: AppModel, code: String, onDone: (Role) -> Unit, onRetry: () -> Unit) {
    // 연결 작업은 모델이 끝까지 한다. 화면이 회전·재구성돼도 결과(pairRun)만 다시 읽는다.
    val run by model.pairRun.collectAsState()
    val creds by model.creds.collectAsState()
    val mine = run?.takeIf { it.code == code }
    val busy = mine?.busy == true
    val replacing = creds != null && !busy
    // iOS의 interactiveDismissDisabled(busy)와 같다: 연결 중 뒤로 가기를 막아 완료 처리가 끊기지 않게 한다.
    BackHandler(enabled = busy) {}
    LaunchedEffect(mine) {
        val done = mine ?: return@LaunchedEffect
        // 기존 연결 끊기와 새 연결이 모두 성공했을 때만 완료 화면으로 간다.
        if (!done.busy && done.error == null && done.role != null) {
            model.clearPairRun()
            onDone(done.role)
        }
    }
    // 429 뒤에는 Retry-After(없으면 10초)까지 연결 버튼을 잠근다(9장).
    var locked by remember(mine?.lockUntil) { mutableStateOf(mine?.lockUntil?.let { it > System.currentTimeMillis() } == true) }
    LaunchedEffect(mine?.lockUntil) {
        val until = mine?.lockUntil ?: return@LaunchedEffect
        delay((until - System.currentTimeMillis()).coerceAtLeast(0))
        locked = false
    }
    val shown = if (isInviteCode(code)) "${code.take(5)}-${code.drop(5)}" else code
    Page("확인") {
        Text("차 화면에 이 코드가 보이나요?", style = MaterialTheme.typography.titleMedium)
        Text(shown, style = MaterialTheme.typography.displaySmall)
        if (isInviteCode(code)) Muted("다른 보호자 초대 코드예요. 리포트와 인계 알림을 받아요.")
        if (replacing) Text("지금 연결된 차를 끊고 새 차에 연결할까요?")
        if (busy) {
            Row { CircularProgressIndicator(); Text("  확인하고 있어요") }
        }
        mine?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !busy && !locked,
            onClick = { model.startPair(code) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (replacing) "끊고 연결할게요" else "네, 연결할게요") }
        OutlinedButton(enabled = !busy, onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("코드 다시 넣기") }
    }
}

/** 연결됐어요. guardian은 받을 푸시가 있어 이때 알림 권한을 묻는다(G13). */
@Composable
fun DoneScreen(role: Role, onNext: () -> Unit) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onNext() }
    Page("연결됐어요") {
        Text(if (role == Role.GUARDIAN) "리포트와 인계 알림을 이 휴대폰으로 받아요." else "수유 시작과 우리 노래를 차로 보낼 수 있어요.")
        Button(
            onClick = {
                if (role == Role.GUARDIAN && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else onNext()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (role == Role.GUARDIAN) "알림 켜고 시작하기" else "시작하기") }
    }
}

/** 401·닫기 4001(9장). */
@Composable
fun GoneScreen(onReconnect: () -> Unit) = Page("연결이 끊겼어요") {
    Text("연결이 끊겼어요. 다시 연결해 주세요.")
    Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("다시 연결하기") }
}
