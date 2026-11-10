package kr.aigyeot.phone.presentation

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kr.aigyeot.phone.R
import kr.aigyeot.phone.domain.model.DisconnectRun
import kr.aigyeot.phone.domain.model.SAVE_FAILED
import kr.aigyeot.phone.domain.model.importPreview

// 설정 탭(디자인 v6 시안 phone.js S.settings: 흰 묶음 다섯, 행 56 + 흐린 아이콘). 항목 글자는 7.1 settings.*.

/**
 * 설정: [보호자 · 아이 별명] [잠·기저귀도 기록하기] [내 기록 내보내기 · 다른 앱 기록 가져오기] [연결 끊기 · 이 휴대폰의 기록 지우기] [정보·라이선스].
 * 보호자·연결 끊기는 연결됐을 때만. 별명은 누르면 입력 창.
 */
@Composable
fun SettingsTab(go: (Route) -> Unit, vm: SettingsViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val care = ui.care
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = ui.disconnect == DisconnectRun.Running
    val error = (ui.disconnect as? DisconnectRun.Failed)?.error
    // 설정을 떠나면(회전은 빼고) 지난 끊기 실패 글을 걷는다: 인터넷이 돌아온 뒤 다시 와도 옛 '인터넷이 필요해요'가 남지 않게.
    val activity = context.findActivity()
    DisposableEffect(Unit) { onDispose { if (activity?.isChangingConfigurations != true) vm.dismissDisconnectError() } }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var nicknameOpen by rememberSaveable { mutableStateOf(false) }
    val importUi by vm.import.collectAsStateWithLifecycle()
    // 기록 가져오기(U64): 시스템 파일 고르기 → 주소만 ViewModel로(읽기는 ViewModel이 쥔 작업, 회전해도 이어진다).
    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.pick(it.toString()) } }
    TabPage("설정") {
        StatusBands(connected = ui.connected)
        Group {
            if (ui.connected) SettingRow("보호자", R.drawable.ic_users, sub = ui.phones.takeIf { it > 0 }?.let { "${it}명" }, onClick = { go(Route.Guardians) })
            SettingRow("아이 별명", R.drawable.ic_baby, sub = care.childNickname ?: "예: 콩이", onClick = { nicknameOpen = true })
        }
        Group {
            SettingRow("잠·기저귀도 기록하기", R.drawable.ic_moon, sub = "다른 육아 앱에 이미 적고 있다면 꺼 두셔도 돼요", checked = care.sleepDiaperEnabled, onCheckedChange = vm::setSleepDiaper)
        }
        Group {
            SettingRow("내 기록 내보내기", R.drawable.ic_download, onClick = {
                scope.launch {
                    // 사본을 쓰지 못했으면(저장 공간 등) 누른 것에 답한다(노래 '듣기' 실패와 같게 토스트)
                    val file = vm.export() ?: return@launch Toast.makeText(context, SAVE_FAILED, Toast.LENGTH_SHORT).show()
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, null))
                }
            })
            // settings.import(U64) / settings.import.sub
            SettingRow("다른 앱 기록 가져오기", R.drawable.ic_file_up, sub = "아이곁·Huckleberry·Nara에서 내보낸 CSV", onClick = { pickCsv.launch(CSV_TYPES) })
        }
        Group {
            if (ui.connected) SettingRow("연결 끊기", R.drawable.ic_unlink, sub = if (busy) "끊고 있어요" else null, enabled = !busy, onClick = { confirm = "disconnect" })
            SettingRow("이 휴대폰의 기록 지우기", R.drawable.ic_trash_2, danger = true, onClick = { confirm = "clear" }, trailing = null)
        }
        if (ui.connected) FootNote("기록을 지워도 연결은 그대로예요. 차에 남은 기록은 차 화면에서 지워요.")
        error?.let { BandCard(it) }
        Group { SettingRow("정보·라이선스", R.drawable.ic_info, onClick = { go(Route.About) }) }
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "clear") "이 휴대폰의 리포트·알림·노래·돌봄 기록을 지울까요?" else "연결을 끊을까요?", style = Type.headline) },
            text = { Text(if (what == "clear") "차와 다른 보호자에게 있는 기록은 그대로예요" else "다시 연결하려면 차 화면의 새 코드가 필요해요. 돌봄 기록은 이 휴대폰에 남아요", style = Type.body) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (what == "clear") vm.clearRecords() else vm.startDisconnect()
                }) { Text(if (what == "clear") "지우기" else "끊기", color = Tokens.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } },
            shape = Radius.dialog,
        )
    }
    if (nicknameOpen) NicknameDialog(care.childNickname.orEmpty(), onSave = { vm.setNickname(it); nicknameOpen = false }) { nicknameOpen = false }
    ImportDialog(importUi, vm)
}

/** 아이 별명(settings.nickname): 입력 칸 하나(예: 콩이). 바뀐 때만 저장한다. */
@Composable
private fun NicknameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    fun save() = if (text.trim() != current) onSave(text) else onDismiss()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("아이 별명", style = Type.headline) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("예: 콩이") }, // settings.nickname.placeholder
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                shape = Radius.field,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { save() }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        shape = Radius.dialog,
    )
}

/** 고르는 화면의 종류(U64). 파일 앱마다 CSV를 다른 이름으로 알려 넷 다 연다. */
private val CSV_TYPES = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")

/** 기록 가져오기(U64~U68) 대화창. 단계는 [ImportUi]가 정하고 글은 7.1 import.* 그대로다(미리 보기 글은 [importPreview]가 고른다). */
@Composable
private fun ImportDialog(ui: ImportUi, vm: SettingsViewModel) {
    val shape = Radius.dialog
    when (ui) {
        ImportUi.Closed -> Unit
        // 읽는 중(클라우드 파일은 오래 걸릴 수 있다): 새 글자 없이 진행 표시와 '취소'만(iOS와 같음). 취소하면 읽던 결과는 버린다.
        ImportUi.Reading -> AlertDialog(
            onDismissRequest = vm::closeImport,
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Tokens.colors.accent) } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::closeImport) { Text("취소") } }, // import.cancel
            shape = shape,
        )
        is ImportUi.PickChild -> AlertDialog(
            onDismissRequest = vm::closeImport,
            title = { Text("어느 아이의 기록인가요?", style = Type.headline) }, // import.pickChild
            text = {
                // 큰 글자·낮은 창에서도 아래 아이까지 고를 수 있게 넘긴다(대화창 본문 높이는 제한된다).
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (name in ui.profiles) ListRow(name, onClick = { vm.pickChild(name) })
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::closeImport) { Text("취소") } }, // import.cancel
            shape = shape,
        )
        is ImportUi.Preview, is ImportUi.Saving -> {
            val saving = ui is ImportUi.Saving
            val draft = if (ui is ImportUi.Preview) ui.draft else (ui as ImportUi.Saving).draft
            val p = importPreview(draft, rememberNow(60_000), deviceZone)
            AlertDialog(
                onDismissRequest = vm::closeImport, // 저장 중에는 닫지 않는다(ViewModel)
                title = { Text(p.title, style = Type.headline) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (line in p.lines) Text(line, style = Type.body, color = Tokens.colors.textPrimary)
                        p.skipTitle?.let { Text(it, style = Type.headline, color = Tokens.colors.textPrimary) }
                        for (line in p.skips) Text(line, style = Type.body, color = Tokens.colors.textTertiary)
                        p.note?.let { Text(it, style = Type.caption, color = Tokens.colors.textTertiary) }
                    }
                },
                // 첫 버튼은 '가져오기' 또는(가져올 것이 없으면) '닫기'. 없으면 ViewModel이 닫는다.
                confirmButton = { TextButton(enabled = !saving, onClick = vm::confirmImport) { Text(p.buttons.first()) } },
                dismissButton = p.buttons.getOrNull(1)?.let { cancel -> { TextButton(enabled = !saving, onClick = vm::closeImport) { Text(cancel) } } },
                shape = shape,
            )
        }
        is ImportUi.Ended -> AlertDialog(
            onDismissRequest = vm::closeImport,
            text = { Text(ui.message, style = Type.body) },
            confirmButton = { TextButton(onClick = vm::closeImport) { Text("닫기") } }, // banner.close
            shape = shape,
        )
    }
}
