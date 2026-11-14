package kr.aigyeot.phone.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kr.aigyeot.phone.R
import kr.aigyeot.phone.domain.model.SONGS_FULL
import kr.aigyeot.phone.domain.model.SongEntry
import kr.aigyeot.phone.domain.model.SongPhase
import kr.aigyeot.phone.domain.model.replaceable
import kr.aigyeot.phone.domain.model.songStatusText
import kotlin.math.abs
import kotlin.math.sin

// 우리 노래(owner): 차 탭 '우리 노래' 블록과 노래 녹음 화면(위 녹음, 아래 목록).

/**
 * 우리 노래 블록: 1~3곡, 차로 보낸 상태. 행을 누르면 듣기·실험에 쓰기·다시 녹음·지우기를 고른다.
 * 오른쪽은 실험에 쓰는 곡이면 브랜드 체크, 아니면 재생 단추. `onRecord`가 있으면 머리에 '녹음하기'(3곡 미만).
 */
@Composable
fun SongsBlock(onRecord: ((replacing: String?) -> Unit)?, vm: SongsViewModel = appViewModel(), recording: Boolean = false, header: Boolean = true) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DisposableEffect(Unit) { onDispose { vm.stopAll() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopPlaying() }
    val songs = ui.songs
    val p = Tokens.colors
    fun play(e: SongEntry) {
        if (!vm.play(e)) android.widget.Toast.makeText(context, "녹음 파일을 읽지 못했어요.", android.widget.Toast.LENGTH_SHORT).show()
    }
    // 녹음 화면은 머리 없이 목록만 둔다(위 막대 제목이 '우리 노래'). 아직 곡이 없으면 위 안내가 대신한다.
    if (!header && songs.isEmpty()) { FootNote("차에서는 다음 주행부터 써요"); return }
    // car.section.songs
    ListBlock(
        if (header) "우리 노래" else null,
        if (header && onRecord != null && !ui.full) "녹음하기" else null,
        onRecord?.let { { it(null) } },
    ) {
        if (songs.isEmpty()) Text("아직 노래가 없어요. 평소 불러 주던 노래를 녹음해 보세요", Modifier.padding(bottom = 14.dp), style = Type.body, color = p.textTertiary)
        // 행 상태(메뉴 열림)는 곡 id에 묶는다: 곡 상태가 바뀌거나 다른 폰이 곡을 지워 자리가 달라져도 열린 메뉴가 다른 곡으로 옮겨 가지 않는다.
        for (e in songs) key(e.songId) {
            var menu by remember { mutableStateOf(false) }
            Box {
                ListRow(
                    e.name ?: "지운 노래",
                    songStatusText(e, ui.online, e.songId in ui.selecting),
                    icon = R.drawable.ic_song,
                    onClick = { menu = true }.takeIf { !recording }, // 녹음하는 동안은 목록을 만지지 않는다
                    trailing = {
                        when {
                            e.candidate -> AIcon(R.drawable.ic_circle_check, Modifier.size(22.dp).semantics { contentDescription = "실험에 쓰는 노래" }, p.accent)
                            e.file != null && !recording -> Box(
                                Modifier.size(40.dp).clip(CircleShape).pressable { play(e) }.semantics { contentDescription = "듣기" },
                                contentAlignment = Alignment.Center,
                            ) { AIcon(R.drawable.ic_play, Modifier.size(20.dp), p.textTertiary) }
                        }
                    },
                )
                DropdownMenu(menu, { menu = false }, shape = Radius.control, containerColor = p.surface) {
                    if (e.file != null) DropdownMenuItem({ Text("듣기") }, { menu = false; play(e) })
                    if (e.phase == SongPhase.ACCEPTED && !e.candidate) DropdownMenuItem({ Text("실험에 쓰기") }, { menu = false; vm.select(e.songId) })
                    // 거절·실패한 곡은 그 자리에서 다시 녹음해 바꾼다('노래 거절', iOS와 같음).
                    if (e.replaceable && onRecord != null) DropdownMenuItem({ Text("다시 녹음하기") }, { menu = false; vm.stopPlaying(); onRecord(e.songId) })
                    DropdownMenuItem({ Text("지우기", color = p.danger) }, { menu = false; vm.remove(e.songId) })
                }
            }
        }
        if (ui.full) Text(SONGS_FULL, Modifier.padding(bottom = 12.dp), style = Type.caption, color = p.textTertiary) // car.songs.full
    }
    FootNote("차에서는 다음 주행부터 써요") // car.songs.note(문구 그대로. 3분 한도는 녹음 화면 "0:00 / 3:00"이 보인다)
}

/**
 * 노래 녹음: 위 소리 결(브랜드 파랑, 녹음 중에만 움직임) + 경과 "0:42 / 3:00" + 녹음 단추(84, 멈춤은 빨간 네모) →
 * 멈춘 뒤 다시 듣기·다시 녹음·이름·차로 보내기 → 아래 우리 노래 목록.
 * `replacing`이 있으면 그 곡을 바꾼다('노래 거절'). 보내기가 목록·보낼 함에 커밋되면 닫는다.
 */
@Composable
fun RecordScreen(replacing: String?, onBack: () -> Unit, vm: SongsViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackPage("우리 노래", onBack) {
        StatusBands()
        Recorder(vm, replacing, ui.online, onBack)
        val s by vm.sheetUi.collectAsStateWithLifecycle()
        SongsBlock(onRecord = null, vm = vm, recording = s.take is Take.Recording, header = false)
    }
}

@Composable
private fun Recorder(vm: SongsViewModel, replacing: String?, online: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.RECORD_AUDIO) }
    // 설정에서 권한을 켜고 돌아왔을 때
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) denied = false
    }
    // 화면마다 고유한 임시 파일(ViewModel이 정한다). 화면만 다시 만들면(회전) 같은 녹음·보내기를 이어받는다.
    LaunchedEffect(Unit) { vm.openSheet() }
    val s by vm.sheetUi.collectAsStateWithLifecycle()
    // 목록·보낼 함에 커밋된 뒤에만 닫는다. 실패하면 녹음은 그대로 두고 다시 보낼 수 있다.
    LaunchedEffect(s.send) { if (vm.takeSendResult()) onClose() }
    // 떠나면 임시 파일은 보내기가 끝난 뒤 지운다. 화면을 떠났거나 끝냈을 때만: 회전·시스템이 화면만 없앤 때는 남겨 복원된 화면이 이어받는다.
    val activity = context.findActivity()
    DisposableEffect(Unit) {
        onDispose {
            val visible = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
            if (activity?.isChangingConfigurations != true && (visible || activity?.isFinishing == true)) vm.closeSheet()
        }
    }
    // 녹음은 앱 화면에서만 한다. 배경으로 가면 멈추고 그때까지 녹음한 것을 남긴다.
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
    val p = Tokens.colors
    // 녹음 영역: 흰 블록 없이 바탕 위에(안쪽 10/20, 칸 사이 18).
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
        if (!granted) {
            if (denied) {
                Text("설정에서 마이크를 켜 주세요", Modifier.padding(bottom = 12.dp), style = Type.headline, color = p.textPrimary)
                Btn("설정 열기", {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }, kind = BtnKind.TONAL)
            }
            return@Column
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Wave(take is Take.Recording)
            val sec = (elapsed / 1000).toInt()
            Text("%d:%02d / 3:00".format(sec / 60, sec % 60), style = Type.recordTimer, color = p.textPrimary)
            RecButton(take is Take.Recording, enabled = !s.sending, onClick = vm::toggleRecording) // 보내는 동안 그 원본을 바꾸는 녹음은 막는다
            s.startError?.let { Text(it, style = Type.caption, color = p.danger) }
            if (take !is Take.Recorded) Text("평소 불러 주던 노래를 녹음해 보세요", style = Type.footnote, color = p.textTertiary) // 녹음 영역 아래 각주
        }
        if (take is Take.Recorded) {
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("다시 듣기", vm::playTake, Modifier.weight(1f), kind = BtnKind.TONAL, icon = R.drawable.ic_play)
                    Btn("다시 녹음하기", vm::discardTake, Modifier.weight(1f), kind = BtnKind.TONAL, enabled = !s.sending)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("노래 이름(30자까지)") },
                    singleLine = true,
                    shape = Radius.field,
                    modifier = Modifier.fillMaxWidth(),
                )
                s.sendError?.let { Text(it, style = Type.caption, color = p.danger) }
                Btn(if (s.sending) "보내는 중" else "차로 보내기", { vm.send(name.trim(), replacing) }, enabled = name.isNotBlank() && !s.sending, big = true)
                if (!online) Text("연결되면 보내요", style = Type.caption, color = p.textTertiary) // 우리 노래·오프라인(iOS 시트와 같음)
            }
        }
    }
}

/** 소리 결(.wave: 막대 44, 폭 4, 브랜드 파랑). 녹음 중에만 1.2초 결로 움직이고, 아니면(동작 줄이기 포함) 멈춘 모양. */
@Composable
private fun Wave(live: Boolean) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(true) }
    // 녹음 중이고 화면에 보일 때만 움직인다(굴려 가리면 멈춤).
    val t = if (live && !reduced && shown) {
        rememberInfiniteTransition(label = "wave")
            .animateFloat(0f, 1f, infiniteRepeatable(Motion.wave.spec<Float>(), RepeatMode.Restart), label = "wave").value
    } else {
        0f
    }
    Canvas(Modifier.fillMaxWidth().height(120.dp).onScreen { shown = it }.semantics { contentDescription = if (live) "녹음 중" else "녹음 대기" }) {
        val n = 44
        val bw = 4.dp.toPx()
        val gap = 3.dp.toPx()
        val total = n * bw + (n - 1) * gap
        val x0 = (size.width - total) / 2
        for (i in 0 until n) {
            val base = 0.2f + abs(sin(i * 0.7f)) * 0.7f
            val k = if (live) (0.35f + 0.65f * abs(sin((t * 2f * Math.PI.toFloat()) + i * 0.55f))) else 0.25f
            val h = size.height * base * k
            drawRoundRect(
                p.accentInk.copy(alpha = if (live) 1f else 0.45f),
                Offset(x0 + i * (bw + gap), (size.height - h) / 2),
                Size(bw, h),
                CornerRadius(bw / 2),
            )
        }
    }
}

/** 녹음 단추(.recbtn: 84 원, 빨강 14% 바탕. 대기는 빨간 원 60, 녹음 중은 빨간 네모 30·모서리 8, 스프링 bouncy). */
@Composable
private fun RecButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val spec = if (reduced) tween<androidx.compose.ui.unit.Dp>(0) else Springs.bouncy.spec()
    val side by animateDpAsState(if (recording) 30.dp else 60.dp, spec, label = "rec")
    val corner by animateDpAsState(if (recording) 8.dp else 30.dp, spec, label = "recR")
    Box(
        Modifier.size(84.dp).clip(CircleShape).background(lerpColor(p.surface, p.danger, 0.14f))
            .pressable(enabled = enabled, onClick = onClick).semantics { contentDescription = if (recording) "녹음 멈추기" else "녹음하기" },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(side).clip(RoundedCornerShape(corner)).background(p.danger)) }
}
