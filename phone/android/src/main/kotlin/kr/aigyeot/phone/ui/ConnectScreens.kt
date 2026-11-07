package kr.aigyeot.phone.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
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
import kr.aigyeot.phone.core.CodeSource
import kr.aigyeot.phone.core.TypedAction
import kr.aigyeot.phone.core.PAIR_BUSY
import kr.aigyeot.phone.core.codeFromLink
import kr.aigyeot.phone.core.isPairLocked
import kr.aigyeot.phone.core.typedSubmitAction
import kr.aigyeot.phone.core.confirmTitle
import kr.aigyeot.phone.core.needsConfirm
import kr.aigyeot.phone.core.isInviteCode
import kr.aigyeot.phone.core.normalizeCode
import kr.aigyeot.shared.Role

/** 첫 실행: 무엇이 오가는지 한 화면(G31). 글자와 순서는 7.1 intro.*(U43, iOS와 같음). */
@Composable
fun IntroScreen(onConnect: () -> Unit, onRecordsOnly: () -> Unit) = Page("아이곁") {
    Text("차의 아이곁과 이 휴대폰을 연결해요.", style = MaterialTheme.typography.titleMedium)
    Text("주고받는 것: 수유 시작 시각, 우리 노래 녹음, 수유·잠·기저귀 기록 요약(시각·횟수·잠 시간), 주행 리포트, 인계 알림 문장")
    Text("보내지 않는 것: 아기 울음 소리, 차 안 마이크 소리, 차의 위치")
    Muted("모두 아이곁 중계 서버를 거쳐 오가요. 인계 알림은 Google·Apple 알림 서버도 거쳐요.")
    Muted("차에서 아기 상황을 물으면, 답을 만들려고 수유·잠·기저귀 숫자를 AI 서버로 보내요.")
    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("차와 연결하기") }
    OutlinedButton(onClick = onRecordsOnly, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("먼저 기록만 쓸게요") }
}

/** 429 잠금이 남았는가. 시각은 모델이 들고 있고, 끝나는 때에 다시 그린다(화면을 옮겨도 풀리지 않는다, 9장). */
@Composable
private fun rememberPairLocked(model: AppModel): Boolean {
    val until by model.pairLockedUntil.collectAsState()
    var locked by remember(until) { mutableStateOf(isPairLocked(until, System.currentTimeMillis())) }
    LaunchedEffect(until) {
        val u = until ?: return@LaunchedEffect
        delay((u - System.currentTimeMillis()).coerceAtLeast(0))
        locked = false
    }
    return locked
}

/**
 * 연결: QR 찍기 또는 코드 넣기(7.1). QR로 읽은 코드만 확인 화면으로 간다.
 * 직접 친 코드는 코드 대조 없이 바로 연결하고(G17, L57), 오류는 이 화면 입력칸 아래에 보인다.
 * 다만 이미 연결된 폰이면 확인 화면의 교체 확인(G16)을 먼저 거친다.
 */
@Composable
fun ConnectScreen(model: AppModel, onCode: (String) -> Unit, onPaired: (Role) -> Unit, onCancel: (() -> Unit)? = null) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // 이 화면에서 직접 시작한 연결만 본다. 확인 화면에서 돌아온 옛 결과는 보이지 않는다.
    var typed by rememberSaveable { mutableStateOf<String?>(null) }
    val run by model.pairRun.collectAsState()
    val mine = run?.takeIf { it.code == typed }
    // 요청 중 여부는 이 화면의 입력만이 아니라 모델 전체의 연결 작업으로 본다.
    val busy = run?.busy == true
    val locked = rememberPairLocked(model)
    BackHandler(enabled = busy) {}
    LaunchedEffect(mine) {
        val done = mine ?: return@LaunchedEffect
        if (model.takePairDone(done)) onPaired(done.role!!)
    }
    fun submit(raw: String) {
        val code = normalizeCode(raw)
        when (typedSubmitAction(code, model.creds.value != null, busy, model.pairLockedUntil.value, System.currentTimeMillis())) {
            TypedAction.BAD_FORMAT -> error = "6자리 숫자 또는 초대 코드를 넣어 주세요"
            TypedAction.IGNORE -> {}
            TypedAction.CONFIRM_REPLACE -> onCode(code!!)
            TypedAction.PAIR -> { typed = code; model.startPair(code!!) }
        }
    }
    Page("차와 연결") {
        onCancel?.let { TextButton(enabled = !busy, onClick = it) { Text("취소") } } // U39: 탭에서 열었을 때 닫기. 연결 중에는 잠근다(7.2 버튼 잠금)
        Text("차 화면의 QR을 비춰 주세요", style = MaterialTheme.typography.titleMedium)
        Button(
            enabled = !busy,
            onClick = {
                // 카메라 권한과 스캔 화면은 Google code scanner가 맡는다(7.3). 처음에는 모듈을 내려받을 수 있다.
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { b ->
                        val code = b.rawValue?.let { codeFromLink(it) ?: normalizeCode(it) }
                        // 스캔 중에 직접 친 코드의 요청이 시작됐을 수 있다. 끝난 결과를 받을 화면이 사라지지 않게 간다.
                        if (code == null) error = "올바른 연결 링크가 아니에요"
                        else if (!model.canTakeLink()) android.widget.Toast.makeText(context, PAIR_BUSY, android.widget.Toast.LENGTH_SHORT).show()
                        else if (needsConfirm(CodeSource.QR)) onCode(code)
                    }
                    .addOnFailureListener { error = "QR을 찍지 못했어요. 코드로 입력해 주세요." }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("QR 찍기") }
        Text("코드로 입력", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.take(12)
                error = null
                model.clearPairRun()
                if (Regex("\\d{6}").matches(text)) submit(text)
            },
            label = { Text("6자리 숫자 또는 초대 코드") },
            isError = (error ?: mine?.error) != null,
            supportingText = { (error ?: mine?.error)?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
            enabled = !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        if (busy) Row { CircularProgressIndicator(); Text("  확인하고 있어요") }
        if (locked) Muted("잠시 뒤에 다시 해 주세요.")
        Button(onClick = { submit(text) }, enabled = text.isNotBlank() && !busy && !locked, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("확인") }
        if (model.creds.value != null) Muted("이미 연결된 차가 있어요. 새로 연결하면 지금 연결을 끊어요.")
    }
}

/**
 * 확인(G17): QR·링크로 들어온 코드만 거친다. 연결 코드는 "차 화면에 이 코드가 보이나요?",
 * 초대 코드는 "받은 초대 코드가 이것과 같나요?"(직접 친 코드는 ConnectScreen에서 바로 연결).
 * 이미 연결된 폰이면 지금 차를 끊을지 먼저 묻는다(G16).
 */
@Composable
fun ConfirmScreen(model: AppModel, code: String, onDone: (Role) -> Unit, onRetry: () -> Unit) {
    // 연결 작업은 모델이 끝까지 한다. 화면이 회전·재구성돼도 결과(pairRun)만 다시 읽는다.
    val run by model.pairRun.collectAsState()
    val creds by model.creds.collectAsState()
    val mine = run?.takeIf { it.code == code }
    val busy = mine?.busy == true
    // U49: 도는 동안에는 누른 순간의 글자를 둔다(끊기가 끝나 자격이 비어도 "연결하기"로 바뀌지 않게).
    var switching by rememberSaveable { mutableStateOf(false) }
    val replacing = if (busy) switching else creds != null
    // iOS의 interactiveDismissDisabled(busy)와 같다: 연결 중 뒤로 가기를 막아 완료 처리가 끊기지 않게 한다.
    BackHandler(enabled = busy) {}
    LaunchedEffect(mine) {
        val done = mine ?: return@LaunchedEffect
        // 기존 연결 끊기와 새 연결이 모두 성공했고, 그사이 끊기지(GONE) 않았을 때만 완료 화면으로 간다.
        if (model.takePairDone(done)) onDone(done.role!!)
    }
    // 429 뒤에는 Retry-After(없으면 10초)까지 연결 버튼을 잠근다(9장). 직접 입력 길과 같은 모델 값이다.
    val locked = rememberPairLocked(model)
    val shown = if (isInviteCode(code)) "${code.take(5)}-${code.drop(5)}" else code
    Page("확인") {
        Text(confirmTitle(code), style = MaterialTheme.typography.titleMedium)
        Text(shown, style = MaterialTheme.typography.displaySmall)
        if (isInviteCode(code)) Muted("다른 보호자 초대 코드예요. 리포트와 인계 알림을 받아요.")
        if (replacing) Text("지금 연결된 차를 끊고 새 차에 연결할까요?")
        if (busy) {
            Row { CircularProgressIndicator(); Text("  확인하고 있어요") }
        }
        mine?.error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !busy && !locked,
            onClick = { switching = creds != null; model.startPair(code) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(if (replacing) "끊고 연결하기" else "연결하기") } // U49 pair.confirmSwitch / pair.confirm
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(if (role == Role.GUARDIAN) "알림 켜고 시작하기" else "시작하기") }
    }
}

/** 401·닫기 4001(9장). */
@Composable
fun GoneScreen(onReconnect: () -> Unit, onRecordsOnly: () -> Unit) = Page("연결이 끊겼어요") {
    Text("연결이 끊겼어요. 다시 연결해 주세요.")
    Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("다시 연결하기") }
    OutlinedButton(onClick = onRecordsOnly, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("기록만 계속 쓸게요") }
}
