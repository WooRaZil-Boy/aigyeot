package kr.aigyeot.phone.presentation

import androidx.compose.foundation.layout.heightIn
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kr.aigyeot.phone.domain.model.SongPhase
import kr.aigyeot.phone.domain.model.replaceable
import kr.aigyeot.phone.domain.model.songStatusText

/** 우리 노래(owner): 1~3곡 녹음·다시 듣기·지우기·고르기, 차로 보낸 상태. */
@Composable
fun SongsScreen(onBack: () -> Unit) = Page("우리 노래", onBack) {
    StatusBands()
    SongsSection()
}

/** 우리 노래 내용(지금 화면). 차 탭 '우리 노래' 섹션(U39)과 노래 화면이 함께 쓴다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnScope.SongsSection(vm: SongsViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DisposableEffect(Unit) { onDispose { vm.stopAll() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopPlaying() }
    var sheet by rememberSaveable { mutableStateOf(false) } // 화면을 다시 만들어도 시트를 둔다(녹음은 놓았으니 다시 녹음)
    var replacing by rememberSaveable { mutableStateOf<String?>(null) } // '다시 녹음'으로 바꿀 곡(9장)
    val songs = ui.songs
    if (songs.isEmpty()) Muted("아직 노래가 없어요. 평소 불러 주던 노래를 녹음해 보세요", Type.body)
    for (e in songs) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.name ?: "지운 노래", style = MaterialTheme.typography.titleMedium)
                Muted(songStatusText(e, ui.online, e.songId in ui.selecting))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (e.file != null) {
                        TextButton(onClick = {
                            if (!vm.play(e)) android.widget.Toast.makeText(context, "녹음 파일을 읽지 못했어요.", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("듣기") }
                    }
                    if (e.phase == SongPhase.ACCEPTED && !e.candidate) {
                        TextButton(onClick = { vm.select(e.songId) }) { Text("실험에 쓰기") }
                    }
                    // 거절·실패한 곡은 그 자리에서 다시 녹음해 바꾼다(9장 '노래 거절', iOS와 같음).
                    if (e.replaceable) {
                        TextButton(onClick = { vm.stopPlaying(); replacing = e.songId; sheet = true }) { Text("다시 녹음") }
                    }
                    TextButton(onClick = { vm.remove(e.songId) }) { Text("지우기") }
                }
            }
        }
    }
    Button(enabled = songs.size < 3, onClick = { replacing = null; sheet = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(if (songs.size < 3) "녹음하기" else "노래는 3곡까지예요")
    }
    Muted("녹음은 3분까지예요. 차가 받으면 다음 주행부터 써요.")
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { vm.closeSheet(); sheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            RecordSheet(vm, replacing, ui.online) { sheet = false }
        }
    }
}

/** 녹음 시트: 큰 녹음·멈춤 버튼, 경과/최대 시간, 멈춘 뒤 다시 듣기·다시 녹음·보내기(구현 계획 8장). */
@Composable
private fun RecordSheet(vm: SongsViewModel, replacing: String?, online: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.RECORD_AUDIO) }
    // 설정에서 권한을 켜고 돌아왔을 때(7.3)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) denied = false
    }
    // 시트마다 고유한 임시 파일(ViewModel이 정한다). 화면만 다시 만들면(회전) 같은 녹음·보내기를 이어받는다.
    LaunchedEffect(Unit) { vm.openSheet() }
    val s by vm.sheetUi.collectAsStateWithLifecycle()
    // 목록·보낼 함에 커밋된 뒤에만 시트를 닫는다. 실패하면 녹음은 그대로 두고 다시 보낼 수 있다.
    LaunchedEffect(s.send) { if (vm.takeSendResult()) onClose() }
    // 닫으면 임시 파일은 보내기가 끝난 뒤 지운다. 시트를 닫았거나 화면을 끝냈을 때만: 회전·시스템이 화면만 없앤 때는 남겨 복원된 시트가 이어받는다.
    val activity = context.findActivity()
    DisposableEffect(Unit) {
        onDispose {
            val visible = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
            if (activity?.isChangingConfigurations != true && (visible || activity?.isFinishing == true)) vm.closeSheet()
        }
    }
    // 녹음은 앱 화면에서만 한다(명세서 12장). 배경으로 가면 멈추고 그때까지 녹음한 것을 남긴다.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }
    var name by rememberSaveable { mutableStateOf("") }
    // 경과는 화면이 그릴 때만 시계를 읽는다(녹음 중 0.2초마다).
    val take = s.take
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(take) {
        while (take is Take.Recording) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val elapsed = when (take) {
        is Take.Recording -> (now - take.startedAt).coerceAtLeast(0)
        is Take.Recorded -> take.ms
        is Take.Idle -> take.ms
    }

    // 큰 글자·키보드가 떠도 이름 칸과 보내기 버튼까지 굴려 닿게 한다(8장 동적 글자 크기).
    Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("노래 녹음", style = MaterialTheme.typography.titleLarge)
        if (!granted) {
            if (denied) {
                Text("설정에서 마이크를 켜 주세요")
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("설정 열기") }
            }
            return@Column
        }
        val sec = (elapsed / 1000).toInt()
        Text("%d:%02d / 3:00".format(sec / 60, sec % 60), style = MaterialTheme.typography.displaySmall)
        Button(
            enabled = !s.sending, // 보내는 동안 그 원본을 바꾸는 녹음은 막는다
            onClick = vm::toggleRecording,
            modifier = Modifier.size(120.dp),
        ) { Text(if (take is Take.Recording) "멈춤" else "녹음") }
        s.startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (take is Take.Recorded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::playTake) { Text("다시 듣기") }
                OutlinedButton(enabled = !s.sending, onClick = vm::discardTake) { Text("다시 녹음") }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                label = { Text("노래 이름(30자까지)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            s.sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = name.isNotBlank() && !s.sending,
                onClick = { vm.send(name.trim(), replacing) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(if (s.sending) "보내는 중" else "차로 보내기") }
            if (!online) Muted("연결되면 보내요") // 7.2 우리 노래·오프라인(iOS 시트와 같음)
        }
    }
}

/** 화면의 액티비티(감싼 Context를 벗겨 찾는다). */
internal tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
