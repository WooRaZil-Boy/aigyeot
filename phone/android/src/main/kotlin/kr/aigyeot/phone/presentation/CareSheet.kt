@file:OptIn(ExperimentalMaterial3Api::class)

package kr.aigyeot.phone.presentation

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kr.aigyeot.phone.R
import kr.aigyeot.phone.domain.model.AGO_CHIPS
import kr.aigyeot.phone.domain.model.CareDraft
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.Diaper
import kr.aigyeot.phone.domain.model.ErrorSpot
import kr.aigyeot.phone.domain.model.FeedSide
import kr.aigyeot.phone.domain.model.NOTE_MAX
import kr.aigyeot.phone.domain.model.SheetField
import kr.aigyeot.phone.domain.model.agoChipText
import kr.aigyeot.phone.domain.model.dayLabel
import kr.aigyeot.phone.domain.model.diaperName
import kr.aigyeot.phone.domain.model.endValue
import kr.aigyeot.phone.domain.model.errorSpot
import kr.aigyeot.phone.domain.model.field
import kr.aigyeot.phone.domain.model.firstRecord
import kr.aigyeot.phone.domain.model.kindName
import kr.aigyeot.phone.domain.model.koClock
import kr.aigyeot.phone.domain.model.memoValue
import kr.aigyeot.phone.domain.model.quickKinds
import kr.aigyeot.phone.domain.model.rowName
import kr.aigyeot.phone.domain.model.rows
import kr.aigyeot.phone.domain.model.saveLabel
import kr.aigyeot.phone.domain.model.sheetLastSide
import kr.aigyeot.phone.domain.model.sheetQuestion
import kr.aigyeot.phone.domain.model.sideName
import kr.aigyeot.phone.domain.model.startValue
import kr.aigyeot.phone.domain.model.ulid
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// 빠른 기록·고치기·지난 기록 적기 시트.

/**
 * 기록 시트(위 → 아래): 손잡이·(고치기) 휴지통 → (지난 기록) 작은 종류 칸 → (고치기) '고치는 중' → 질문 → (첫 기록) 도움말 →
 * 꼭 고를 것 하나(수유 쪽·젖병 양 / 기저귀 종류 / 잠 시작 / 메모 글칸) → 접힌 줄 → 오류 한 줄 → 보호자 안내·저장 실패 → 저장 단추 하나.
 * 입력·검사·저장 단계는 [CareSheetViewModel]이 갖고, 이 화면은 그리기와 동작 전달만 한다. 펼친 줄은 화면 상태다(VM에 두지 않는다).
 * '취소' 단추는 없다: 끌어 내림·뒤로·가림막으로 닫는다.
 */
@Composable
fun CareSheet(
    target: CareSheetTarget,
    chooseKind: Boolean = false,
    guardian: Boolean,
    vm: CareSheetViewModel = appViewModel(),
    onClose: () -> Unit,
) {
    // 시트 하나에 id 하나(화면을 다시 만들어도 같은 시트로 이어 쓴다). 새 기록의 id도 이것이다.
    val sheetId = rememberSaveable { ulid(System.currentTimeMillis()) }
    LaunchedEffect(sheetId) { vm.open(sheetId, target) }
    val care by vm.careFile.collectAsStateWithLifecycle()
    val all by vm.sheet.collectAsStateWithLifecycle()
    // 이 시트의 입력·단계만 쓴다(앞 시트에서 남은 것이 비치거나 이 시트를 닫지 않게).
    val mine = all?.takeIf { it.sheetId == sheetId }
    val view = LocalView.current
    val reduced = LocalReducedMotion.current
    fun close() {
        vm.close(sheetId)
        onClose()
    }
    // 닫힘 연출: VM 닫힘 단계의 저장 결과로만 정한다([sheetExit]). 저장된 수유·잠·기저귀는 확인 표시가 잠깐 머문 뒤,
    // 저장된 메모와 동작 줄이기는 햅틱만 내고 바로, 저장하지 않은 닫힘(지우기·지워진 기록)은 바로 닫는다.
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(mine?.phase) {
        when (val ph = mine?.phase) {
            is CareSheetViewModel.Phase.Closed -> {
                when (sheetExit(ph, mine.draft.kind, reduced)) {
                    SheetExit.CHECK -> {
                        done = true
                        if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        delay(320)
                    }
                    SheetExit.HAPTIC -> if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    SheetExit.NONE -> Unit
                }
                close()
            }
            else -> Unit // 실패 반응은 아래 한 묶음에서 낸다
        }
    }
    val d = mine?.draft ?: return
    val phase = mine.phase
    val failed = phase as? CareSheetViewModel.Phase.Failed
    val errField = failed?.problem?.field()
    var expanded by rememberSaveable(sheetId) { mutableStateOf<SheetField?>(null) }
    // 오류 한 줄은 탓이 된 칸 바로 아래(iOS와 같음). 그 칸(과 오류 줄)을 감싼 자리마다 굴림 요청을 둔다.
    val errText = failed?.takeIf { it.problem != null }?.message
    val errSpot = errField?.let { d.errorSpot(it) }
    val rowReq = remember { SheetField.entries.associateWith { BringIntoViewRequester() } }
    val amountReq = remember { BringIntoViewRequester() }
    val heroReq = remember { BringIntoViewRequester() }
    var shakeAt by remember { mutableStateOf<Int?>(null) } // 흔들 실패 번호: 굴린 뒤에 정한다
    // 실패 한 번 = 한 묶음: 탓이 된 줄 펼침 → (펼친 뒤) 그 자리가 본문 안에 보이게 굴림 → 흔들림 → 오류 햅틱.
    // 키는 실패 값(시도 번호 포함)이라 같은 오류가 다시 나도 매번 돈다. 동작 줄이기(시스템 애니메이션 0)면 굴림·펼침은 Compose가 바로 끝낸다.
    LaunchedEffect(failed) {
        val f = failed ?: return@LaunchedEffect
        val field = f.problem?.field()
        if (field != null) {
            val spot = d.errorSpot(field)
            val opening = spot == ErrorSpot.ROW && expanded != field
            if (spot == ErrorSpot.ROW) expanded = field
            withFrameNanos { }
            withFrameNanos { } // 펼침·오류 줄이 자리를 잡은 뒤
            if (opening && !reduced) delay(300) // 펼침(spring.smooth)이 끝난 뒤 굴려 오류 줄까지 보이게
            when (spot) {
                ErrorSpot.ROW -> rowReq.getValue(field)
                ErrorSpot.AMOUNT -> amountReq
                ErrorSpot.HERO -> heroReq
            }.bringIntoView()
            shakeAt = f.attempt
        }
        if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }
    // 이번 실패를 굴린 뒤에만 흔든다(앞 실패의 번호가 새 칸에서 먼저 흔들리지 않게).
    fun shakeKey(f: SheetField): Int? = shakeAt?.takeIf { errField == f && it == failed?.attempt }
    var pick by rememberSaveable { mutableStateOf<String?>(null) } // "start" | "end" 시각 고르기 창
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val p = Tokens.colors
    val editable = phase.editable // VM이 받는 단계와 같은 판단 하나
    ModalBottomSheet(
        onDismissRequest = ::close,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = p.surface,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 10.dp).size(36.dp, 5.dp).clip(CircleShape).background(p.textTertiary.copy(alpha = 0.4f))) },
    ) {
        // 시트(대화 상자)의 읽기 이름은 질문이다.
        Column(Modifier.fillMaxWidth().semantics { paneTitle = sheetQuestion(d.kind) }.imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).animateContentSize(if (reduced) snap() else Springs.smooth.spec()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ① 머리 + ③ 상태 라벨: 고치기면 '고치는 중'과 오른쪽 휴지통 하나(읽기 이름 '지우기', 48dp)를 한 줄에 둔다(고치기에는 ② 종류 칸이 없다).
                // 확인 창 없이 바로 지우고 띠가 되돌리기를 준다.
                if (d.original != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("고치는 중", Modifier.weight(1f), style = Type.footnote, color = p.textTertiary) // sheet.editing
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).pressable(enabled = editable, onClick = vm::delete).semantics { contentDescription = "지우기" },
                        contentAlignment = Alignment.Center,
                    ) { AIcon(R.drawable.ic_trash_2, Modifier.size(22.dp), p.textSecondary) } // 지우기는 5초 되돌리기가 있어 빨강으로 칠하지 않는다(빨강은 되돌릴 수 없는 줄만)
                }
                // 지난 기록 적기: 작은 종류 칸(quickKinds 순서).
                if (chooseKind && d.original == null) {
                    val kinds = quickKinds(care.sleepDiaperEnabled)
                    OptionTiles(kinds.map { Tile(kindName(it), kindIcon(it)) }, kinds.indexOf(d.kind), small = true, group = "종류") { i -> vm.setKind(kinds[i]) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // ④ 질문(머리글, 시트 읽기 이름) ⑤ 첫 기록 도움말
                    Text(sheetQuestion(d.kind), Modifier.semantics { heading() }, style = Type.title, color = p.textPrimary)
                    if (d.original == null && care.firstRecord) Text("필요한 것만 고르고 저장하면 돼요", style = Type.note, color = p.textTertiary) // sheet.hint.first
                }
                // ⑥ 꼭 고를 것 하나
                when (d.kind) {
                    CareKind.FEED -> {
                        val sides = FeedSide.entries
                        val last = care.sheetLastSide(d)
                        OptionTiles(
                            sides.map { Tile(sideName(it), sideIcon(it), note = "지난번".takeIf { _ -> it == last }) },
                            sides.indexOf(d.side),
                            group = "쪽",
                        ) { i -> vm.toggleSide(sides[i]) }
                        if (d.side == FeedSide.BOTTLE) Column(Modifier.bringIntoViewRequester(amountReq), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Shake(shakeKey(SheetField.AMOUNT)) { AmountBlock(d.amount, err = errField == SheetField.AMOUNT, onChange = vm::setAmount) }
                            if (errSpot == ErrorSpot.AMOUNT) errText?.let { ErrorLine(it) }
                        }
                    }
                    CareKind.DIAPER -> {
                        val kinds = Diaper.entries
                        OptionTiles(kinds.map { Tile(diaperName(it), diaperIcon(it)) }, kinds.indexOf(d.diaper), group = "종류") { i -> vm.setDiaper(kinds[i]) }
                    }
                    CareKind.SLEEP -> Column(Modifier.bringIntoViewRequester(heroReq), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Shake(shakeKey(SheetField.START)) {
                            SleepHero(d, err = errField == SheetField.START, onAgo = vm::pick, onTime = { pick = "start" }, onDate = { pickDate = true })
                        }
                        if (errSpot == ErrorSpot.HERO) errText?.let { ErrorLine(it) }
                    }
                    CareKind.NOTE -> Column(Modifier.bringIntoViewRequester(heroReq), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        NoteBox(d.note, vm::setNote, big = true)
                        if (errSpot == ErrorSpot.HERO) errText?.let { ErrorLine(it) }
                    }
                }
                // ⑦ 접힌 줄(한 번에 한 줄만 펼침)
                Column(Modifier.fillMaxWidth().clip(Radius.large).background(p.textPrimary.copy(alpha = 0.04f))) {
                    d.rows.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = p.line)
                        Column(Modifier.bringIntoViewRequester(rowReq.getValue(f))) {
                        Shake(shakeKey(f)) {
                            FoldRow(
                                d, f, expanded == f, err = errField == f,
                                onToggle = { expanded = toggleRow(expanded, f) },
                                onAgo = vm::pick, onTime = { pick = if (f == SheetField.END) "end" else "start" }, onDate = { pickDate = true },
                                onEndUnknown = vm::setEndUnknown, onNote = vm::setNote,
                            )
                        }
                        if (errSpot == ErrorSpot.ROW && errField == f) {
                            errText?.let { Box(Modifier.padding(start = 10.dp, end = 14.dp, bottom = 12.dp)) { ErrorLine(it) } }
                        }
                        }
                    }
                }
            }
            // ⑨ 아래 고정: 보호자 안내·저장 실패 글 + 저장 단추 하나
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed?.problem == null) failed?.message?.let { ErrorLine(it) }
                if (guardian) Row(verticalAlignment = Alignment.CenterVertically) {
                    AIcon(R.drawable.ic_smartphone, Modifier.padding(start = 4.dp, end = 6.dp).size(16.dp), p.textTertiary)
                    Text(GUARDIAN_ONLY, style = Type.footnote, color = p.textTertiary)
                }
                SaveButton(
                    d.saveLabel(shorten = !bigText()),
                    enabled = editable && d.savable,
                    // 확인 표시로 닫힐 때만 그 표시 전까지 고리를 둔다(메모·저장 안 한 닫힘은 바로 닫혀 고리가 남지 않는다).
                    saving = phase == CareSheetViewModel.Phase.Saving ||
                        (phase is CareSheetViewModel.Phase.Closed && sheetExit(phase, d.kind) == SheetExit.CHECK && !done),
                    done = done,
                    onClick = { vm.save() },
                )
            }
        }
    }
    pick?.let { which ->
        val zone = d.zone
        val ref = Instant.ofEpochMilli(if (which == "end") d.end ?: d.start else d.start).atZone(zone)
        TimeDialog(
            ref.hour, ref.minute, onDismiss = { pick = null },
            onUnknown = if (which == "end") ({ vm.setEndUnknown(); pick = null }) else null,
        ) { h, m ->
            if (which == "end") vm.setEnd(h, m) else vm.setStart(h, m)
            pick = null
        }
    }
    if (pickDate) {
        val zone = d.zone
        DateDialog(Instant.ofEpochMilli(d.start).atZone(zone).toLocalDate(), max = LocalDate.now(zone), onDismiss = { pickDate = false }) { day ->
            vm.moveStartDay(day)
            pickDate = false
        }
    }
    (phase as? CareSheetViewModel.Phase.ConfirmOverlap)?.let { o ->
        OverlapDialog(o.event.kind, onSave = { vm.save(allowOverlap = true) }, onDismiss = vm::keepEditing)
    }
}

/** 줄 펼침: 시트를 열면 모두 접혀 있고(null), 누른 줄만 펼친다. 펼친 줄을 다시 누르면 접히고, 다른 줄을 누르면 앞 줄은 접힌다. */
internal fun toggleRow(open: SheetField?, tapped: SheetField): SheetField? = if (open == tapped) null else tapped

/** 아이콘 칸 하나. [note]는 글 아래 작은 상태 라벨('지난번', 표시만). */
private class Tile(val label: String, @DrawableRes val icon: Int, val note: String? = null)

/**
 * 아이콘 칸(`OptionTiles`): 회색 판 + 흰 엄지 하나(spring.snappy), 큰 칸 높이 92·아이콘 28, 작은 종류 칸 48·아이콘 20.
 * 라디오 묶음(읽기 이름 [group]), 항목 이름 = 글(+ ', 지난번'), 아이콘은 읽지 않는다. 엄지가 옮겨 가면 가볍게 톡(CLOCK_TICK).
 */
@Composable
private fun OptionTiles(tiles: List<Tile>, selected: Int, small: Boolean = false, group: String, onSelect: (Int) -> Unit) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val view = LocalView.current
    // 큰 글자면 2×2(엄지는 칸과 줄을 함께 옮긴다).
    val big = bigText()
    val anyNote = tiles.any { it.note != null }
    val cols = if (big && tiles.size == 4) 2 else tiles.size
    val sel = selected.coerceAtLeast(0)
    val col by animateFloatAsState((sel % cols).toFloat(), if (reduced) snap() else Springs.snappy.spec(), label = "tileX")
    val line by animateFloatAsState((sel / cols).toFloat(), if (reduced) snap() else Springs.snappy.spec(), label = "tileY")
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxWidth().clip(Radius.large).background(p.textPrimary.copy(alpha = 0.04f)).padding(4.dp)
            .selectableGroup().semantics { contentDescription = group },
    ) {
        val w = maxWidth / cols
        // 칸 높이는 한 묶음 안에서 같아야 엄지가 맞는다. 큰 글자면 글이 커진 만큼 높인다.
        val h = when {
            small -> if (big) 64.dp else 48.dp
            else -> if (big) 128.dp else 92.dp
        }
        if (selected >= 0) Box(
            Modifier.offset { IntOffset((w * col).roundToPx(), (h * line).roundToPx()) }.size(w, h)
                .shadow(if (p.dark) 0.dp else 2.dp, Radius.medium).clip(Radius.medium).background(p.segmentThumb),
        )
        Column { tiles.chunked(cols).forEachIndexed { r, chunk -> Row {
            chunk.forEachIndexed { j, t ->
                val i = r * cols + j
                val on = i == selected
                val fg by animateColorAsState(if (on) p.textPrimary else p.textSecondary, if (reduced) snap() else Motion.fast.spec(), label = "tileFg")
                val desc = listOfNotNull(t.label, t.note).joinToString(", ")
                val content: @Composable () -> Unit = {
                    AIcon(t.icon, Modifier.size(if (small) 20.dp else 28.dp), fg)
                    Text(t.label, style = if (small) Type.quickLabel else Type.chip, color = fg, maxLines = 1)
                }
                Box(
                    Modifier.size(w, h).clip(Radius.medium)
                        .selectable(on, interactionSource = null, indication = null, role = Role.RadioButton) {
                            if (!on) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onSelect(i)
                        }
                        .semantics(mergeDescendants = true) { contentDescription = desc; this.selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    if (small) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
                    else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        content()
                        // sheet.last. 한 칸에만 붙어도 모든 칸에 같은 자리를 두어 아이콘·글 줄이 어긋나지 않게 한다.
                        if (anyNote) Text(t.note ?: "", style = Type.micro, color = p.textTertiary)
                    }
                }
            }
        } } }
    }
}

/** 큰 글자(글꼴 1.3배 이상): 단추 글을 줄이지 않고, 아이콘 칸은 2×2, 줄 값은 이름 아래로. */
@Composable
private fun bigText() = LocalDensity.current.fontScale >= 1.3f

/** 젖병 양(`AmountBlock`): 작은 '양' + 큰 숫자 + 'ml' + 둥근 −/＋(48dp). 읽기는 '줄이기'·'늘리기' + 값 '양, {n}ml'. */
@Composable
private fun AmountBlock(ml: Int?, err: Boolean, onChange: (Int?) -> Unit) {
    val p = Tokens.colors
    Row(
        Modifier.fillMaxWidth().clip(Radius.large).background(p.textPrimary.copy(alpha = 0.04f))
            .border(if (err) 1.5.dp else 0.dp, if (err) p.danger else p.surface.copy(alpha = 0f), Radius.large).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundStep(R.drawable.ic_minus, "줄이기", enabled = ml != null) {
            onChange(ml?.let { (it - 10).coerceAtLeast(kr.aigyeot.phone.domain.model.AMOUNT_ML.first) })
        }
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "양, " + (ml?.let { "${it}ml" } ?: "없음") },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("양", style = Type.micro, color = p.textTertiary)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ml?.toString() ?: "—", style = Type.runningTimer, color = if (err) p.danger else p.textPrimary)
                Text("ml", Modifier.padding(start = 2.dp, bottom = 3.dp), style = Type.meta, color = p.textSecondary)
            }
        }
        RoundStep(R.drawable.ic_plus, "늘리기") { onChange(((ml ?: 0) + 10).coerceAtMost(kr.aigyeot.phone.domain.model.AMOUNT_ML.last)) }
    }
}

@Composable
private fun RoundStep(@DrawableRes icon: Int, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Tokens.colors
    Box(
        Modifier.size(48.dp).shadow(if (p.dark) 0.dp else 1.dp, CircleShape).clip(CircleShape).background(p.segmentThumb)
            .pressable(enabled = enabled, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { AIcon(icon, Modifier.size(22.dp), if (enabled) p.textPrimary else p.textTertiary) }
}

/** 잠의 주인공 칸: '시작' + 큰 시각(누르면 시각 고르기) + (직접·칩 없는 시트) 날짜 알약 + 몇 분 전 줄(늘 보임). */
@Composable
private fun SleepHero(d: CareDraft, err: Boolean, onAgo: (Int?) -> Unit, onTime: () -> Unit, onDate: () -> Unit) {
    val p = Tokens.colors
    val t = Instant.ofEpochMilli(d.start).atZone(d.zone)
    val clock = koClock(d.start, d.zone)
    Column(
        Modifier.fillMaxWidth().clip(Radius.large).background(p.textPrimary.copy(alpha = 0.04f))
            .border(if (err) 1.5.dp else 0.dp, if (err) p.danger else p.surface.copy(alpha = 0f), Radius.large),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
            Text("시작", style = Type.micro, color = p.textTertiary)
            Row(
                Modifier.heightIn(min = 48.dp).clip(Radius.inner).pressable(onClick = onTime).semantics { contentDescription = "시각 고르기, $clock" },
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(clock.substringBefore(' '), Modifier.padding(end = 4.dp, bottom = 4.dp), style = Type.meta, color = if (err) p.danger else p.textSecondary)
                Text(clock.substringAfter(' '), style = Type.runningTimer, color = if (err) p.danger else p.textPrimary)
            }
            if (!d.showsAgoChips || d.chip == null) Row(Modifier.padding(top = 4.dp)) {
                Pill(
                    dayLabel(t.toLocalDate(), LocalDate.now(d.zone)),
                    selected = false,
                    desc = "날짜, ${dayLabel(t.toLocalDate(), LocalDate.now(d.zone))}",
                    onClick = onDate,
                )
            }
        }
        if (d.showsAgoChips) {
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = p.line)
            AgoStrip(d.chip, onAgo, Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
        }
    }
}

/** 메모 글칸(200자). 큰 칸(메모 종류의 주인공)이나 펼친 메모 줄 안. 입력 중이거나 150자부터 '{n}/200'. */
@Composable
private fun NoteBox(note: String, onNote: (String) -> Unit, big: Boolean, focus: Boolean = false) {
    val p = Tokens.colors
    var focused by remember { mutableStateOf(false) }
    val req = remember { FocusRequester() }
    LaunchedEffect(focus) { if (focus) runCatching { req.requestFocus() } }
    val n = note.codePointCount(0, note.length)
    // 글자 수는 본문 아래 제 줄에 둔다(iOS와 같음): 여러 줄 메모가 칸을 넘어도 마지막 줄과 겹치지 않는다. 자리는 늘 두어 보일 때 칸이 튀지 않는다.
    Column(
        Modifier.fillMaxWidth().clip(Radius.large).background(if (big) p.textPrimary.copy(alpha = 0.04f) else p.surface)
            .border(if (focused && big) 1.5.dp else 0.dp, if (focused && big) p.accent else p.surface.copy(alpha = 0f), Radius.large)
            .heightIn(min = if (big) 132.dp else 72.dp).padding(14.dp),
    ) {
        BasicTextField(
            value = note,
            onValueChange = onNote, // 200자에서 더 받지 않는다(도메인 규칙)
            textStyle = Type.sheetRow.copy(color = p.textPrimary),
            cursorBrush = SolidColor(p.accent),
            // 본문은 칸 최소 높이에서 글자 수 줄을 뺀 만큼은 차지하고, 길면 늘어난다(무한 높이 안이라 weight를 쓰지 않는다).
            modifier = Modifier.fillMaxWidth().heightIn(min = if (big) 80.dp else 20.dp).focusRequester(req)
                .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "메모" },
            decorationBox = { inner ->
                Box {
                    if (note.isEmpty()) Text("예: 트림 잘 함", style = Type.sheetRow, color = p.textTertiary)
                    inner()
                }
            },
        )
        Text(
            "$n/$NOTE_MAX", Modifier.align(Alignment.End).padding(top = 8.dp).graphicsLayer { alpha = if (focused || n >= 150) 1f else 0f },
            style = Type.micro, color = p.textTertiary,
        )
    }
}

/**
 * 접힌 줄(`FoldRow`): 이름 · 값 · ⌄(54dp 이상). 누르면 펼치고 다른 줄은 접힌다. 읽기는 '{이름}, {값}' 단추 + 펼침 상태.
 * 빈 메모 줄은 '메모 더하기 ＋'(브랜드 글자). 저장 검사에 걸린 줄은 빨간 테·값이 빨갛다.
 */
@Composable
private fun FoldRow(
    d: CareDraft,
    f: SheetField,
    open: Boolean,
    err: Boolean,
    onToggle: () -> Unit,
    onAgo: (Int?) -> Unit,
    onTime: () -> Unit,
    onDate: () -> Unit,
    onEndUnknown: () -> Unit,
    onNote: (String) -> Unit,
) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val name = d.rowName(f)
    val memoAdd = f == SheetField.MEMO && d.memoValue() == null
    val value = when (f) {
        SheetField.START -> d.startValue(System.currentTimeMillis())
        SheetField.END -> d.endValue()
        SheetField.MEMO -> d.memoValue()
        SheetField.AMOUNT -> null
    }
    val turn by animateFloatAsState(if (open) 180f else 0f, if (reduced) snap() else Motion.fast.spec(), label = "chev")
    Column(
        Modifier.fillMaxWidth().clip(Radius.large)
            .border(if (err) 1.5.dp else 0.dp, if (err) p.danger else p.surface.copy(alpha = 0f), Radius.large),
    ) {
        val big = bigText()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Radius.large).pressable(scale = 1f, onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(name, value).joinToString(", ")
                    role = Role.Button
                    if (!memoAdd) stateDescription = if (open) "펼침" else "접힘"
                }
                .padding(horizontal = 14.dp, vertical = if (big) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val valueText: @Composable (Modifier) -> Unit = { m ->
                if (value != null) {
                    SwapText(
                        value,
                        Type.sheetValue.copy(fontFeatureSettings = "tnum"),
                        if (err) p.danger else p.textPrimary,
                        m,
                        maxLines = if (big) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 큰 글자면 값이 이름 아래로 내려간다.
            if (big) Column(Modifier.weight(1f)) {
                Text(name, style = Type.sheetRow, color = if (memoAdd) p.accentInk else p.textSecondary)
                valueText(Modifier)
            } else {
                Text(name, Modifier.padding(end = 12.dp), style = Type.sheetRow, color = if (memoAdd) p.accentInk else p.textSecondary)
                // 값은 오른쪽 끝(⌄ 바로 앞)에 붙인다.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { valueText(Modifier) }
            }
            if (memoAdd) AIcon(R.drawable.ic_plus, Modifier.size(20.dp), p.accentInk)
            else AIcon(R.drawable.ic_chevron_down, Modifier.padding(start = 6.dp).size(18.dp).rotate(turn), p.textTertiary)
        }
        AnimatedVisibility(
            open,
            enter = if (reduced) fadeIn(snap()) else expandVertically(Springs.smooth.spec()) + fadeIn(Motion.enter.spec()),
            exit = if (reduced) fadeOut(snap()) else shrinkVertically(Springs.smooth.spec()) + fadeOut(Motion.exit.spec()),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (f) {
                    SheetField.START -> {
                        if (d.showsAgoChips) AgoStrip(d.chip, onAgo)
                        // '직접'이거나 칩이 없는 시트(고치기·다른 날)면 알약 [날짜][시각]. 몇 분 전을 고른 동안은 숨긴다.
                        if (d.showsStart) Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val day = dayLabel(Instant.ofEpochMilli(d.start).atZone(d.zone).toLocalDate(), LocalDate.now(d.zone))
                            Pill(day, selected = false, desc = "날짜, $day", onClick = onDate)
                            val clock = koClock(d.start, d.zone)
                            Pill(clock, selected = true, desc = "시각 고르기, $clock", onClick = onTime)
                        }
                    }
                    SheetField.END -> Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("끝 시각 모름", selected = d.endUnknown, onClick = onEndUnknown) // sheet.end.unknown
                        Pill(d.end?.let { koClock(it, d.zone) } ?: "직접", selected = !d.endUnknown, desc = "끝 시각 고르기", onClick = onTime)
                    }
                    SheetField.MEMO -> Box(Modifier.padding(horizontal = 8.dp)) { NoteBox(d.note, onNote, big = false, focus = true) }
                    SheetField.AMOUNT -> Unit
                }
            }
        }
    }
}

/** 몇 분 전 한 줄(`AgoStrip`, 읽기 묶음 '시작 시각 빠르게 고르기'): 글자 + 먹색 엄지, 넘치면 가로 넘김, 48dp. */
@Composable
private fun AgoStrip(chip: Int?, onAgo: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val view = LocalView.current
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().semantics { contentDescription = "시작 시각 빠르게 고르기" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        (AGO_CHIPS.map { it as Int? } + listOf(null)).forEach { c ->
            val on = chip == c
            val bg by animateColorAsState(
                if (on) p.textPrimary else p.textPrimary.copy(alpha = 0f),
                if (reduced) snap() else Springs.snappy.spec(),
                label = "agoBg",
            )
            val fg by animateColorAsState(if (on) p.surface else p.textSecondary, if (reduced) snap() else Springs.snappy.spec(), label = "agoFg")
            Box(
                Modifier.heightIn(min = 48.dp).clip(CircleShape)
                    .selectable(on, interactionSource = null, indication = null, role = Role.RadioButton) {
                        if (!on) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onAgo(c)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    c?.let(::agoChipText) ?: "직접",
                    Modifier.clip(CircleShape).background(bg).padding(horizontal = 12.dp, vertical = 7.dp),
                    style = Type.quickLabel,
                    color = fg,
                )
            }
        }
    }
}

/** 알약: 날짜·시각·'끝 시각 모름'. 고르면 먹색, 아니면 회색 바탕. 누름 영역은 48dp. */
@Composable
private fun Pill(text: String, selected: Boolean, desc: String = text, onClick: () -> Unit) {
    val p = Tokens.colors
    Box(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).pressable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            Modifier.clip(CircleShape).background(if (selected) p.textPrimary else p.textPrimary.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            style = Type.quickLabel.copy(fontFeatureSettings = "tnum"), color = if (selected) p.surface else p.textSecondary,
        )
    }
}

/** 오류 한 줄: 빨간 느낌표 + 글. 나타날 때 읽는다. */
@Composable
private fun ErrorLine(text: String) {
    val p = Tokens.colors
    Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        AIcon(R.drawable.ic_circle_alert, Modifier.padding(start = 4.dp, end = 6.dp).size(16.dp), p.danger)
        Text(text, style = Type.footnote, color = p.danger)
    }
}

/** 탓이 된 칸을 가볍게 흔든다(360ms, 0 → −6 → 6 → −4 → 4 → −2 → 0). 동작 줄이기면 흔들지 않는다. */
@Composable
private fun Shake(key: Int?, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val x = remember { Animatable(0f) }
    // [key]는 이 칸을 탓한 실패의 시도 번호(아니면 null): 같은 오류가 다시 나도 번호가 달라 다시 흔든다.
    LaunchedEffect(key) {
        if (key != null && !reduced) x.animateTo(0f, keyframes {
            durationMillis = 360
            -6f at 50; 6f at 110; -4f at 170; 4f at 230; -2f at 290; 0f at 360
        })
    }
    Box(Modifier.offset { IntOffset(x.value.dp.roundToPx(), 0) }) { content() }
}

/**
 * 저장 단추 하나(`SaveButton`): 56 높이·모서리 16·17/700, 글은 [label](저장될 것). 메모가 비면 꺼진다. 저장 중이면 고리, 저장되면 확인 표시(spring.bouncy 0.5 → 1).
 * 읽기 이름은 보이는 글 그대로(저장 중이면 꺼진 단추).
 */
@Composable
private fun SaveButton(label: String, enabled: Boolean, saving: Boolean, done: Boolean, onClick: () -> Unit) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val on = enabled || saving || done
    val check by animateFloatAsState(if (done) 1f else 0.5f, if (reduced) snap() else Springs.bouncy.spec(), label = "check")
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Radius.control).background(if (on) p.accent else p.textPrimary.copy(alpha = 0.08f))
            .pressable(enabled = enabled && !saving && !done, onClick = onClick).semantics { contentDescription = label }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            done -> AIcon(R.drawable.ic_check, Modifier.size(26.dp).scale(check), p.onAccent)
            saving -> CircularProgressIndicator(Modifier.size(24.dp), color = p.onAccent, strokeWidth = 2.5.dp)
            else -> SwapText(
                label,
                Type.strong,
                if (on) p.onAccent else p.textTertiary,
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** 쪽 아이콘(채운 원이 그 쪽). */
@DrawableRes
private fun sideIcon(s: FeedSide) = when (s) {
    FeedSide.LEFT -> R.drawable.ic_side_left
    FeedSide.RIGHT -> R.drawable.ic_side_right
    FeedSide.BOTH -> R.drawable.ic_side_both
    FeedSide.BOTTLE -> R.drawable.ic_bottle
}

/** 기저귀 종류 아이콘(droplet·poo·pee-poo·clean). */
@DrawableRes
private fun diaperIcon(d: Diaper) = when (d) {
    Diaper.WET -> R.drawable.ic_droplet
    Diaper.DIRTY -> R.drawable.ic_poo
    Diaper.BOTH -> R.drawable.ic_pee_poo
    Diaper.DRY -> R.drawable.ic_clean
}

/** 날짜 고르기(플랫폼다움: Material 날짜 선택기). 오늘 뒤는 고를 수 없다(iOS `in: ...지금`과 같음). */
@Composable
private fun DateDialog(date: LocalDate, max: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // DatePicker는 UTC 자정 밀리초로 날을 주고받는다.
    val maxMs = max.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= maxMs
            override fun isSelectableYear(year: Int) = year <= max.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) { DatePicker(state, showModeToggle = false) }
}
