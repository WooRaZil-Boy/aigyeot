package kr.aigyeot.phone.ui

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.aigyeot.phone.AppModel
import kr.aigyeot.phone.core.replaceable
import kr.aigyeot.phone.core.SongEntry
import kr.aigyeot.phone.core.SongPhase
import kr.aigyeot.phone.core.ulid
import kr.aigyeot.phone.core.selecting
import kr.aigyeot.phone.core.visibleSongs
import kr.aigyeot.phone.data.Recorder
import kr.aigyeot.shared.SongRejectReason
import java.io.File

/** 거절 이유 문구(계약 3.3 표). */
fun rejectText(r: SongRejectReason?) = when (r) {
    SongRejectReason.PEAK_LIMIT -> "조금 더 크게 다시 녹음해 주세요."
    SongRejectReason.SILENT -> "소리가 거의 들리지 않아요. 다시 녹음해 주세요."
    SongRejectReason.BAD_FORMAT -> "파일을 읽지 못했어요. 다시 녹음해 주세요."
    SongRejectReason.TOO_LONG -> "3분이 넘어요. 3분 안으로 다시 녹음해 주세요."
    SongRejectReason.TOO_LARGE -> "파일이 너무 커요. 다시 녹음해 주세요."
    SongRejectReason.LIMIT_REACHED -> "노래는 3곡까지예요. 한 곡을 지운 뒤 보내 주세요."
    SongRejectReason.DOWNLOAD_FAILED -> "차가 파일을 받지 못했어요. 다시 보내 주세요."
    null -> "차가 받지 않았어요."
}

private fun statusText(e: SongEntry, online: Boolean) = e.error ?: when (e.phase) {
    SongPhase.UPLOADING -> if (online) "올리는 중" else "연결되면 보내요" // 7.2 우리 노래·오프라인: 보낼 함에서 기다린다
    SongPhase.WAITING -> "차가 받기를 기다려요"
    SongPhase.ACCEPTED -> if (e.candidate) "다음 주행부터 써요 · 실험 후보" else "다음 주행부터 써요"
    SongPhase.REJECTED -> "거절됨: ${rejectText(e.reason)}"
    SongPhase.REMOVED -> "지운 노래"
}

/** 우리 노래(owner): 1~3곡 녹음·다시 듣기·지우기·고르기, 차로 보낸 상태. */
@Composable
fun SongsScreen(model: AppModel, onBack: () -> Unit) = Page("우리 노래", onBack) {
    StatusBands(model)
    SongsSection(model)
}

/** 우리 노래 내용(지금 화면). 차 탭 '우리 노래' 섹션(U39)과 노래 화면이 함께 쓴다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnScope.SongsSection(model: AppModel) {
    val s by model.state.collectAsState()
    val context = LocalContext.current
    val recorder = remember { Recorder(context) }
    DisposableEffect(Unit) { onDispose { recorder.stop(); recorder.stopPlaying() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { recorder.stopPlaying() }
    var sheet by rememberSaveable { mutableStateOf(false) } // 화면을 다시 만들어도 시트를 둔다(녹음은 놓았으니 다시 녹음)
    var replacing by rememberSaveable { mutableStateOf<String?>(null) } // '다시 녹음'으로 바꿀 곡(9장)
    val ui by model.ui.collectAsState()
    val songs = s.visibleSongs
    run {
        if (songs.isEmpty()) Muted("아직 노래가 없어요. 평소 불러 주던 노래를 녹음해 보세요", Type.body)
        for (e in songs) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(e.name ?: "지운 노래", style = MaterialTheme.typography.titleMedium)
                    // '실험 후보'는 최신 song_status의 candidate로만 보인다. 고른 뒤 아직 못 보냈으면 그것만 덧붙인다.
                    Muted(statusText(e, ui.online) + (if (s.selecting(e.songId) && !e.candidate) " · 고르는 중" else ""))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        e.file?.let { f ->
                            TextButton(onClick = {
                                if (!recorder.play(File(model.songsDir, f)) {}) android.widget.Toast.makeText(context, "녹음 파일을 읽지 못했어요.", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text("듣기") }
                        }
                        if (e.phase == SongPhase.ACCEPTED && !e.candidate) {
                            TextButton(onClick = { model.selectSong(e.songId) }) { Text("실험에 쓰기") }
                        }
                        // 거절·실패한 곡은 그 자리에서 다시 녹음해 바꾼다(9장 '노래 거절', iOS와 같음).
                        if (e.replaceable) {
                            TextButton(onClick = { recorder.stopPlaying(); replacing = e.songId; sheet = true }) { Text("다시 녹음") }
                        }
                        TextButton(onClick = { model.removeSong(e.songId) }) { Text("지우기") }
                    }
                }
            }
        }
        Button(enabled = songs.size < 3, onClick = { replacing = null; sheet = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(if (songs.size < 3) "녹음하기" else "노래는 3곡까지예요")
        }
        Muted("녹음은 3분까지예요. 차가 받으면 다음 주행부터 써요.")
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { recorder.stop(); sheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            RecordSheet(model, recorder, replacing, ui.online) { sheet = false }
        }
    }
}

/** 녹음 시트: 큰 녹음·멈춤 버튼, 경과/최대 시간, 멈춘 뒤 다시 듣기·다시 녹음·보내기(구현 계획 8장). */
@Composable
private fun RecordSheet(model: AppModel, recorder: Recorder, replacing: String?, online: Boolean, onClose: () -> Unit) {
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

    // 시트마다 고유한 임시 파일. 앞 시트의 보내기(복사·원본 지우기)가 늦어도 새 녹음과 섞이지 않는다.
    // 화면을 다시 만들어도(회전·글자 크기·어두운 화면) 끝낸 녹음은 이어받는다: 파일 이름·완료·길이를 저장 가능한 상태로 둔다.
    var fileName by rememberSaveable { mutableStateOf("recording-${ulid()}.m4a") }
    val file = remember(fileName) { File(context.cacheDir, fileName) }
    var recording by remember { mutableStateOf(false) } // 다시 만들면 녹음기는 멈췄다: 하던 녹음은 다시 한다
    var done by rememberSaveable { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by rememberSaveable { mutableLongStateOf(0L) }
    // 프로세스가 죽은 뒤 복원됐으면 앞 프로세스의 임시 녹음은 시작 때 지운다: 그 녹음은 없는 것으로 보고, 새 이름으로 다시 녹음하게 한다
    // (같은 이름을 다시 쓰면 그 뒤의 새 녹음도 '지울 목록'으로 보인다).
    LaunchedEffect(Unit) {
        if (model.isLeftoverRecording(file) || (done && !file.exists())) {
            if (model.isLeftoverRecording(file)) fileName = "recording-${ulid()}.m4a"
            done = false
            elapsed = 0
        }
    }
    var name by rememberSaveable { mutableStateOf("") }
    var startError by rememberSaveable { mutableStateOf<String?>(null) }
    var sendError by rememberSaveable { mutableStateOf<String?>(null) }
    // 화면을 다시 만들며 복원됐으면 앞 화면이 닫히며 버린 이 녹음의 보내기·원본을 다시 맡는다(처음 연 시트면 아무 일도 없다).
    LaunchedEffect(file) { model.adoptRecording(file) }
    // 보내기는 모델이 하고 결과도 모델에 남는다: 화면을 다시 만들어도 같은 녹음의 보내기를 이어 본다(보내는 동안 다시 녹음·다시 보내기를 막는다).
    val sends by model.songSends.collectAsState()
    val mySend = sends[file.path]
    val sending = mySend?.busy == true
    LaunchedEffect(mySend) {
        val r = mySend?.takeIf { !it.busy } ?: return@LaunchedEffect
        model.consumeSongSend(file)
        // 목록·보낼 함에 커밋된 뒤에만 시트를 닫는다. 실패하면 녹음은 그대로 두고 다시 보낼 수 있다.
        if (r.error == null) onClose() else sendError = r.error
    }
    // 닫으면 임시 파일은 모델이 지운다(보내는 중이면 끝난 뒤에. 실패한 보내기의 원본도 남기지 않는다).
    // 시트를 닫았거나 화면을 끝냈을 때만 지운다. 회전·시스템이 화면만 없앤 때는 남겨 복원된 시트가 이어받는다
    // (그대로 프로세스가 죽으면 다음 시작이 앞 프로세스의 임시 녹음으로 지운다).
    val activity = context.findActivity()
    DisposableEffect(file) {
        onDispose {
            val visible = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
            if (activity?.isChangingConfigurations != true && (visible || activity?.isFinishing == true)) model.releaseRecording(file)
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(200)
        }
    }
    fun stop() {
        recording = false
        done = recorder.stop()
    }
    // 녹음은 앱 화면에서만 한다(명세서 12장). 배경으로 가면 멈추고 그때까지 녹음한 것을 남긴다.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (recording) stop()
        recorder.stopPlaying()
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
            enabled = !sending, // 보내는 동안 그 원본을 바꾸는 녹음은 막는다
            onClick = {
                if (recording) {
                    stop()
                } else {
                    done = false
                    elapsed = 0
                    sendError = null // 앞 녹음의 보내기 오류를 새 녹음에 남기지 않는다
                    startedAt = System.currentTimeMillis()
                    recording = recorder.start(file) { recording = false; done = true }
                    startError = if (recording) null else "녹음을 시작하지 못했어요. 다른 앱이 마이크를 쓰고 있으면 닫고 다시 눌러 주세요."
                }
            },
            modifier = Modifier.size(120.dp),
        ) { Text(if (recording) "멈춤" else "녹음") }
        startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (done && !recording) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (!recorder.play(file) {}) sendError = "녹음 파일을 읽지 못했어요. 다시 녹음해 주세요." }) { Text("다시 듣기") }
                OutlinedButton(enabled = !sending, onClick = { done = false; elapsed = 0; sendError = null }) { Text("다시 녹음") }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                label = { Text("노래 이름(30자까지)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = name.isNotBlank() && !sending,
                onClick = {
                    val d = Recorder.durationS(file)
                    if (d == null) {
                        sendError = "녹음 파일을 읽지 못했어요. 다시 녹음해 주세요."
                        return@Button
                    }
                    sendError = null
                    model.sendRecording(file, name.trim(), d, replacing)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(if (sending) "보내는 중" else "차로 보내기") }
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
