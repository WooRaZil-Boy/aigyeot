package kr.aigyeot.phone.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

private fun statusText(e: SongEntry) = e.error ?: when (e.phase) {
    SongPhase.UPLOADING -> "올리는 중"
    SongPhase.WAITING -> "차가 받기를 기다려요"
    SongPhase.ACCEPTED -> if (e.candidate) "다음 주행부터 써요 · 실험 후보" else "다음 주행부터 써요"
    SongPhase.REJECTED -> "거절됨: ${rejectText(e.reason)}"
    SongPhase.REMOVED -> "지운 노래"
}

/** 우리 노래(owner): 1~3곡 녹음·다시 듣기·지우기·고르기, 차로 보낸 상태. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsScreen(model: AppModel, onBack: () -> Unit) {
    val s by model.state.collectAsState()
    val context = LocalContext.current
    val recorder = remember { Recorder(context) }
    DisposableEffect(Unit) { onDispose { recorder.stop(); recorder.stopPlaying() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { recorder.stopPlaying() }
    var sheet by remember { mutableStateOf(false) }
    val songs = s.visibleSongs
    Page("우리 노래", onBack) {
        StatusBands(model)
        if (songs.isEmpty()) Muted("아직 노래가 없어요. 평소 불러 주던 노래를 녹음해 보세요")
        for (e in songs) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(e.name ?: "지운 노래", style = MaterialTheme.typography.titleMedium)
                    // '실험 후보'는 최신 song_status의 candidate로만 보인다. 고른 뒤 아직 못 보냈으면 그것만 덧붙인다.
                    Muted(statusText(e) + (if (s.selecting(e.songId) && !e.candidate) " · 고르는 중" else ""))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        e.file?.let { f -> TextButton(onClick = { recorder.play(File(model.songsDir, f)) {} }) { Text("듣기") } }
                        if (e.phase == SongPhase.ACCEPTED && !e.candidate) {
                            TextButton(onClick = { model.selectSong(e.songId) }) { Text("실험에 쓰기") }
                        }
                        TextButton(onClick = { model.removeSong(e.songId) }) { Text("지우기") }
                    }
                }
            }
        }
        Button(enabled = songs.size < 3, onClick = { sheet = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (songs.size < 3) "녹음하기" else "노래는 3곡까지예요")
        }
        Muted("녹음은 3분까지예요. 차가 받으면 다음 주행부터 써요.")
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { recorder.stop(); sheet = false }) {
            RecordSheet(model, recorder) { sheet = false }
        }
    }
}

/** 녹음 시트: 큰 녹음·멈춤 버튼, 경과/최대 시간, 멈춘 뒤 다시 듣기·다시 녹음·보내기(구현 계획 8장). */
@Composable
private fun RecordSheet(model: AppModel, recorder: Recorder, onClose: () -> Unit) {
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
    val file = remember { File(context.cacheDir, "recording-${ulid()}.m4a") }
    var recording by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var name by remember { mutableStateOf("") }
    var startError by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // 보내지 않고 닫으면 임시 파일을 지운다. 보내는 중이면 모델이 커밋 뒤에 지운다(실패하면 캐시에 남는다).
    DisposableEffect(file) { onDispose { if (!sending) file.delete() } }
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

    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                OutlinedButton(onClick = { recorder.play(file) {} }) { Text("다시 듣기") }
                OutlinedButton(enabled = !sending, onClick = { done = false; elapsed = 0 }) { Text("다시 녹음") }
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
                    sending = true
                    sendError = null
                    scope.launch {
                        // 목록·보낼 함에 커밋된 뒤에만 시트를 닫는다. 실패하면 녹음은 그대로 두고 다시 보낼 수 있다.
                        val err = model.addSong(file, name.trim(), d)
                        sending = false
                        if (err == null) onClose() else sendError = err
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (sending) "보내는 중" else "차로 보내기") }
        }
    }
}
