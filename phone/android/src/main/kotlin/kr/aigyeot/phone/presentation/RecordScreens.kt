@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package kr.aigyeot.phone.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch
import kr.aigyeot.phone.R
import kr.aigyeot.phone.domain.model.CarLine
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.CareSource
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.Diaper
import kr.aigyeot.phone.domain.model.FeedSide
import kr.aigyeot.phone.domain.model.MINUTE
import kr.aigyeot.phone.domain.model.NOTE_MAX
import kr.aigyeot.phone.domain.model.avgCountText
import kr.aigyeot.phone.domain.model.avgDurationText
import kr.aigyeot.phone.domain.model.carLines
import kr.aigyeot.phone.domain.model.carePattern
import kr.aigyeot.phone.domain.model.dayEvents
import kr.aigyeot.phone.domain.model.daysText
import kr.aigyeot.phone.domain.model.diaperName
import kr.aigyeot.phone.domain.model.durationText
import kr.aigyeot.phone.domain.model.firstAtOrAfter
import kr.aigyeot.phone.domain.model.isOngoing
import kr.aigyeot.phone.domain.model.koClock
import kr.aigyeot.phone.domain.model.koDate
import kr.aigyeot.phone.domain.model.latestAtOrBefore
import kr.aigyeot.phone.domain.model.logRowText
import kr.aigyeot.phone.domain.model.memoLine
import kr.aigyeot.phone.domain.model.onDate
import kr.aigyeot.phone.domain.model.overlaps
import kr.aigyeot.phone.domain.model.problem
import kr.aigyeot.phone.domain.model.problemText
import kr.aigyeot.phone.domain.model.quickKinds
import kr.aigyeot.phone.domain.model.sideName
import kr.aigyeot.phone.domain.model.text
import kr.aigyeot.phone.domain.model.ulid

// 기록 탭(C4·C5·C14)과 빠른 기록·고치기 시트(C3). 디자인 v6 시안 phone.js S.log·S.pattern·S.sheet.

/** 7.1 sheet.title.* / sheet.edit.* */
private fun sheetTitle(k: CareKind, edit: Boolean) = kindName(k) + if (edit) " 고치기" else " 적기"

/** 하루 줄을 제목(앞 두 조각: 종류·시각/구간)과 둘째 줄(나머지)로 나눈다(시안 ev(a, b), 결정 1). 글은 7.1 log.row.* 그대로다. */
internal fun splitRow(text: String): Pair<String, String?> {
    val parts = text.split(" · ")
    return if (parts.size <= 2) text to null else parts.take(2).joinToString(" · ") to parts.drop(2).joinToString(" · ")
}

// ---------------------------------------------------------------- 빠른 기록·고치기 시트(C3)

private val agoChips = listOf("지금" to 0L, "5분 전" to 5L, "15분 전" to 15L, "30분 전" to 30L, "1시간 전" to 60L)

/**
 * 새 기록(initial = null)과 고치기(시안 S.sheet: 모달 바텀 시트, 위 손잡이, 제목 18/24·700, 회색 행 묶음 + 값 칩, 세그먼트, 취소·저장 1:2).
 * 수유·잠은 '시작'·'끝'(끝 시각 모름 또는 고른 시각), 기저귀·메모는 '시각' 한 줄. `baseDate`가 있으면(기록 탭의 다른 날) 시각은 그 날의 시각이다.
 * 저장 전에 R3 안내, R4 겹침 경고를 보인다.
 */
@Composable
fun CareSheet(
    kind: CareKind,
    initial: CareEvent?,
    baseDate: LocalDate?,
    chooseKind: Boolean = false,
    guardian: Boolean,
    vm: CareSheetViewModel = appViewModel(),
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val care by vm.careFile.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 화면을 다시 만들어도(회전·글자 크기·어두운 화면) 적던 내용이 남게 저장 가능한 상태로 둔다.
    val opened = rememberSaveable { System.currentTimeMillis() }
    val zone = initial?.zoneOrDevice() ?: deviceZone
    var k by rememberSaveable { mutableStateOf(initial?.kind ?: kind) }
    var startAt by rememberSaveable { mutableStateOf(initial?.startAt ?: baseDate?.let { onDate(it, 12, 0, zone) } ?: opened) }
    var chip by rememberSaveable { mutableStateOf(if (initial == null && baseDate == null) "지금" else "직접") }
    var endAt by rememberSaveable { mutableStateOf(initial?.endAt) }
    var side by rememberSaveable { mutableStateOf(initial?.feedSide) }
    var ml by rememberSaveable { mutableStateOf(initial?.amountMl) }
    var diaper by rememberSaveable { mutableStateOf(initial?.diaper ?: Diaper.WET) }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var datePicked by rememberSaveable { mutableStateOf(false) } // 날짜를 직접 골랐으면 시각을 골라도 그 날에 둔다
    var error by remember { mutableStateOf<String?>(null) }
    var overlap by remember { mutableStateOf<CareEvent?>(null) }
    val timed = k == CareKind.FEED || k == CareKind.SLEEP

    val newId = rememberSaveable { ulid(System.currentTimeMillis()) } // 시트 하나에 새 기록 하나(빠르게 두 번 눌러도 같은 id라 하나만 생긴다)
    var saving by remember { mutableStateOf(false) }
    fun build(now: Long): CareEvent {
        val base = initial ?: CareEvent(newId, k, startAt, zone = zone.id, source = CareSource.PHONE, createdAt = now, updatedAt = now)
        return base.copy(
            kind = k,
            startAt = startAt,
            endAt = if (timed) endAt else null,
            endUnknown = timed && endAt == null, // 끝을 고르지 않은 수유·잠은 '끝 시각 모름'
            feedSide = if (k == CareKind.FEED) side else null,
            amountMl = if (k == CareKind.FEED && side == FeedSide.BOTTLE) ml else null,
            diaper = if (k == CareKind.DIAPER) diaper else null,
            note = note.trim().ifEmpty { null }, // U37: 수유·잠·기저귀에도 메모를 달 수 있다
        )
    }

    fun save(e: CareEvent) {
        if (saving) return
        saving = true
        scope.launch {
            when (val r = vm.save(e, editing = initial != null)) {
                SheetSave.Saved, SheetSave.Gone -> onClose()
                is SheetSave.Failed -> error = r.error
            }
            saving = false
        }
    }

    fun trySave() {
        val now = System.currentTimeMillis()
        if (k == CareKind.NOTE && note.isBlank()) return
        val e = build(now)
        e.problem(now)?.let { error = problemText(it, e.kind); return }
        if (care.overlaps(e, now)) overlap = e else save(e)
    }

    val p = Tokens.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = p.surface,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 10.dp).size(36.dp, 5.dp).clip(CircleShape).background(p.textTertiary.copy(alpha = 0.4f))) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 20.dp, end = 20.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(sheetTitle(k, edit = initial != null), style = Type.headline.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold), color = p.textPrimary)
            if (chooseKind && initial == null) {
                val kinds = quickKinds(care.sleepDiaperEnabled)
                Segmented(kinds.map(::kindName), kinds.indexOf(k), { i -> k = kinds[i]; error = null })
            }
            if (guardian) FootNote(GUARDIAN_ONLY)
            SheetRows {
                // 시작(수유·잠) 또는 시각(기저귀·메모). 날짜 칩은 날짜를, 시각 칩은 시각을 고른다(iOS 날짜·시각 선택기와 같은 기능: 지난 기록을 다른 날로 옮길 수 있다).
                SheetRow(if (timed) "시작" else "시각", first = true) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ValueChip(dayLabel(Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate(), LocalDate.now(zone)), selected = true) { pickDate = true }
                        ValueChip(koClock(startAt, zone), selected = true) { pick = "start" }
                    }
                }
                if (timed) SheetRow("끝") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ValueChip("끝 시각 모름", selected = endAt == null) { endAt = null; error = null }
                        ValueChip(endAt?.let { koClock(it, zone) } ?: "직접", selected = endAt != null) { pick = "end" }
                    }
                }
                SheetRow("메모") {
                    val ph = "예: 트림 잘 함"
                    BasicTextField(
                        value = note,
                        // U17·U24: 200자(코드 포인트)에서 더 받지 않는다.
                        onValueChange = { if (it.codePointCount(0, it.length) <= NOTE_MAX) note = it },
                        textStyle = Type.body.copy(fontSize = 15.5.sp, color = p.textPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                        cursorBrush = SolidColor(p.accent),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "메모" },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterEnd) {
                                if (note.isEmpty()) Text(ph, style = Type.body.copy(fontSize = 15.5.sp), color = p.textTertiary)
                                inner()
                            }
                        },
                    )
                }
            }
            if (initial == null && baseDate == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, min) in agoChips) Chip(label, chip == label, { chip = label; startAt = System.currentTimeMillis() - min * MINUTE; datePicked = false; error = null })
                Chip("직접", chip == "직접", { pick = "start" })
            }
            when (k) {
                CareKind.FEED -> {
                    val sides = FeedSide.entries
                    // 쪽은 고르지 않아도 된다: 고른 칸을 다시 누르면 비운다.
                    Segmented(sides.map(::sideName), sides.indexOf(side), { i -> side = if (side == sides[i]) null else sides[i] })
                    if (side == FeedSide.BOTTLE) AmountRow(ml) { ml = it }
                }
                CareKind.DIAPER -> {
                    val kinds = Diaper.entries
                    Segmented(kinds.map(::diaperName), kinds.indexOf(diaper), { i -> diaper = kinds[i] })
                }
                else -> Unit
            }
            error?.let { Text(it, style = Type.body.copy(fontSize = 14.sp), color = p.danger) }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("취소", onClose, Modifier.weight(1f), kind = BtnKind.TONAL)
                Btn("저장", { trySave() }, Modifier.weight(2f), enabled = !saving && (k != CareKind.NOTE || note.isNotBlank()))
            }
            if (initial != null) Btn("지우기", { vm.delete(initial.id); onClose() }, kind = BtnKind.DANGER_TEXT)
        }
    }
    pick?.let { which ->
        val ref = Instant.ofEpochMilli(if (which == "end") endAt ?: startAt else startAt).atZone(zone)
        TimeDialog(ref.hour, ref.minute, onDismiss = { pick = null }) { h, m ->
            val now = System.currentTimeMillis()
            when {
                which == "end" -> endAt = firstAtOrAfter(h, m, startAt, zone)
                // 고치는 기록·다른 날 기록·날짜를 고른 기록은 지금 보이는 날 그대로 시각만 바꾼다.
                initial != null || baseDate != null || datePicked -> startAt = onDate(Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate(), h, m, zone)
                else -> startAt = latestAtOrBefore(h, m, now + MINUTE, zone)
            }
            if (which == "start") chip = "직접"
            error = null
            pick = null
        }
    }
    if (pickDate) {
        val t = Instant.ofEpochMilli(startAt).atZone(zone)
        DateDialog(t.toLocalDate(), max = LocalDate.now(zone), onDismiss = { pickDate = false }) { d ->
            val moved = onDate(d, t.hour, t.minute, zone)
            endAt = endAt?.let { it + (moved - startAt) } // 날을 옮기면 끝도 같이 옮겨 길이를 지킨다
            startAt = moved
            chip = "직접"
            datePicked = true
            error = null
            pickDate = false
        }
    }
    overlap?.let { e -> OverlapDialog(e.kind, onSave = { overlap = null; save(e) }, onDismiss = { overlap = null }) }
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

/** 시트 행 묶음(.sheet .rows: 먹색 4% 바탕, 모서리 20, 안쪽 2/14, 행 사이 1 선). */
@Composable
private fun SheetRows(content: @Composable () -> Unit) {
    val p = Tokens.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.textPrimary.copy(alpha = 0.04f)).padding(horizontal = 14.dp, vertical = 2.dp)) { content() }
}

/** 시트 행(.sheet .row: 최소 50, 15.5·500). 첫 행이 아니면 위에 1 선. */
@Composable
private fun SheetRow(label: String, first: Boolean = false, value: @Composable () -> Unit) {
    val p = Tokens.colors
    if (!first) androidx.compose.material3.HorizontalDivider(color = p.line)
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 12.dp), style = Type.body.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium), color = p.textPrimary)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

/**
 * 값 칩(.sheet .row em: 고르면 브랜드 글자 + 브랜드 10% 바탕, 모서리 10). 고른 글자는 onAccentSoft: 시안 accentInk(#2867E6)는 행 판 위
 * 브랜드 10% 바탕(#E1E8F5)에서 4.08:1이라 4.5:1에 못 미친다(#1D4FC4는 5.7:1). 고르지 않은 칩은 먹색 8%(행 판 4% 위에서 라이트·다크 모두 보이게).
 */
@Composable
private fun ValueChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = Tokens.colors
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) p.accent.copy(alpha = 0.1f) else p.textPrimary.copy(alpha = 0.08f))
            .selectable(selected, interactionSource = null, indication = null, role = Role.Button, onClick = onClick).heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.body.copy(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = if (selected) p.onAccentSoft else p.textSecondary) }
}

// ---------------------------------------------------------------- 기록(C4·C5·C14)

/** 기록(시안 S.log·S.pattern): 큰 제목 → 하루·패턴 세그먼트 → (하루) 날짜 띠 + 타임라인 + '지난 기록 적기' / (패턴) 차트 블록들. */
@Composable
fun RecordsTab(go: (Route) -> Unit, vm: RecordsViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var pattern by rememberSaveable { mutableStateOf(false) }
    // 고치는 기록·새 기록 시트(하루 보기)
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val now = rememberNow(30_000)
    val today = Instant.ofEpochMilli(now).atZone(deviceZone).toLocalDate()
    var dayEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    TabPage("기록") {
        StatusBands(connected = ui.connection is Connection.Paired)
        Segmented(listOf("하루", "패턴"), if (pattern) 1 else 0, { pattern = it == 1 }) // log.segment
        if (pattern) PatternView(ui, now)
        else DayView(ui, today, LocalDate.ofEpochDay(dayEpoch), { dayEpoch = it.toEpochDay() }, go, vm::delete, { editingId = it }, { adding = true })
    }
    val editing = editingId?.let { id -> ui.care.events.firstOrNull { it.id == id && it.deletedAt == null } }
    // 고치던 기록이 지워지면(기록 지우기 등) 시트를 닫는다.
    LaunchedEffect(ui.care, editingId) { if (editingId != null && editing == null) editingId = null }
    editing?.let { e -> CareSheet(e.kind, initial = e, baseDate = null, guardian = ui.guardian) { editingId = null } }
    val day = LocalDate.ofEpochDay(dayEpoch)
    if (adding) CareSheet(CareKind.FEED, initial = null, baseDate = day.takeIf { it != today }, chooseKind = true, guardian = ui.guardian) { adding = false }
}

/** log.date.*: "오늘" / "어제" / "11월 5일(수)"(고른 날 제목, 날짜 칸의 화면 읽기 이름). */
private fun dayLabel(d: LocalDate, today: LocalDate) = when (d) {
    today -> "오늘"
    today.minusDays(1) -> "어제"
    else -> koDate(d)
}

/**
 * 날짜 띠(.datestrip: 46×62 알약, 요일 12·500 + 날 17, 고르면 먹색 반전) + 하루 타임라인(.tl: 줄기 + 종류 점, 3시간마다 시각 머리,
 * 새 것이 위) + '지난 기록 적기' 행. 손 기록은 누르면 고치기(진행 중이면 타이머), 밀면 지우기. 차 기록 줄은 누르면 리포트.
 */
@Composable
private fun DayView(
    ui: RecordsUiState,
    today: LocalDate,
    day: LocalDate,
    pickDay: (LocalDate) -> Unit,
    go: (Route) -> Unit,
    delete: (String, () -> Unit) -> Unit,
    edit: (String) -> Unit,
    add: () -> Unit,
) {
    val care = ui.care
    val s = ui.phone
    val allCars = remember(s.reports) { carLines(s.reports.values.map { it.body }) }
    // 지난 30일. 기록이 있는 날이 그 밖이면(더 오래됨) 그날까지 늘린다: 돌봄 기록은 기간으로 지우지 않으니(U44) 열 수 있어야 한다(iOS `DayStrip`과 같음).
    val recordDays = care.events.filter { it.deletedAt == null }.map { Instant.ofEpochMilli(it.startAt).atZone(it.zoneOrDevice()).toLocalDate() } +
        allCars.map { Instant.ofEpochMilli(it.startAt).atZone(deviceZone).toLocalDate() }
    val first = minOf(today.minusDays(29), recordDays.minOrNull() ?: today)
    val last = maxOf(today, recordDays.maxOrNull() ?: today)
    // 날을 목록으로 만들지 않고 보이는 칸만 계산한다: 차 리포트의 날짜가 크게 어긋나도(먼 미래 등) 멈추지 않는다.
    val span = ChronoUnit.DAYS.between(first, last).coerceAtMost(Int.MAX_VALUE - 1L).toInt() + 1
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = (ChronoUnit.DAYS.between(first, today) - 4).coerceIn(0, span - 1L).toInt())
    val p = Tokens.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(state = strip, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
            items(span, key = { first.toEpochDay() + it }) { i ->
                val d = first.plusDays(i.toLong())
                val on = d == day
                Column(
                    Modifier.width(46.dp).heightIn(min = 62.dp).clip(RoundedCornerShape(23.dp)).background(if (on) p.textPrimary else androidx.compose.ui.graphics.Color.Transparent)
                        .selectable(on, interactionSource = null, indication = null, role = Role.Tab) { pickDay(d) }.semantics { contentDescription = dayLabel(d, today) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                ) {
                    Text(if (d == today) "오늘" else "월화수목금토일"[d.dayOfWeek.value - 1].toString(), style = Type.caption.copy(fontSize = 12.sp, lineHeight = 14.sp), color = if (on) p.bg.copy(alpha = 0.75f) else p.textTertiary)
                    Text("${d.dayOfMonth}", style = Type.headline.copy(fontWeight = FontWeight.Medium, lineHeight = 20.sp, fontFeatureSettings = "tnum"), color = if (on) p.bg else p.textPrimary)
                }
            }
        }
        // 고른 날 제목(log.date.*). 띠는 요일·날짜 숫자뿐이라 기록이 여러 달이면 달을 알 수 없다(iOS와 같음).
        Text(dayLabel(day, today), Modifier.padding(start = 4.dp, top = 2.dp).semantics { heading() }, style = Type.headline, color = p.textPrimary)
        val hand = care.dayEvents(day, deviceZone)
        val cars = allCars.filter { Instant.ofEpochMilli(it.startAt).atZone(deviceZone).toLocalDate() == day }
        // 새 것이 위(시안). 3시간 칸마다 시각 머리("오후 12시").
        val rows: List<Pair<Long, Any>> = (hand.map { it.startAt to it } + cars.map { it.startAt to it }).sortedByDescending { it.first }
        if (rows.isEmpty()) FootNote("이 날 기록이 없어요")
        Timeline {
            var lastBucket = -1
            for ((at, row) in rows) {
                val t = Instant.ofEpochMilli(at).atZone(if (row is CareEvent) row.zoneOrDevice() else deviceZone)
                val bucket = t.hour / 3 * 3
                if (bucket != lastBucket) {
                    HourHead(bucket)
                    lastBucket = bucket
                }
                when (row) {
                    is CareEvent -> key(row.id) {
                        HandRow(row, onOpen = { if (row.isOngoing) go(Route.Timer(row.id)) else edit(row.id) }, onDelete = { fail -> delete(row.id, fail) })
                    }
                    is CarLine -> key(row.sessionId + (row.episodeId ?: "")) {
                        val (title, sub) = splitRow(row.text(deviceZone))
                        TimelineCard(null, title, sub, null) { go(Route.Report(row.sessionId)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        ListBlock { ListRow("지난 기록 적기", icon = R.drawable.ic_plus, add = true, onClick = add) } // today.quick.past
    }
}

/** 시각 머리("오전 9시", 3시간 칸의 시작). 줄기 왼쪽 흐린 11·600 + 점선. */
@Composable
private fun HourHead(hour: Int) {
    val p = Tokens.colors
    val label = (if (hour < 12) "오전 " else "오후 ") + "${if (hour % 12 == 0) 12 else hour % 12}시"
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(start = 4.dp, end = 8.dp), style = Type.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold), color = p.textTertiary)
        Box(
            Modifier.weight(1f).height(1.dp).drawBehind {
                drawLine(p.line, Offset(0f, 0f), Offset(size.width, 0f), size.height, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            },
        )
    }
}

/** 타임라인 틀: 왼쪽 52 안에 세로 줄기(x 33). 카드는 오른쪽. */
@Composable
private fun Timeline(content: @Composable () -> Unit) {
    val p = Tokens.colors
    Column(
        Modifier.fillMaxWidth().drawBehind { drawLine(p.line, Offset(33.dp.toPx(), 0f), Offset(33.dp.toPx(), size.height), 1.dp.toPx()) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** 타임라인 카드(.tl .ev): 줄기 위 종류 점 8(바탕색 테) + 흰 카드(모서리 18, 아이콘 18 흐림 · 제목 14.5/20·500 · 둘째 줄 12.5/17). */
@Composable
private fun TimelineCard(kind: CareKind?, title: String, sub: String?, memo: String?, tall: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = Tokens.colors
    val dot = kindColor(kind, p)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.padding(start = 25.dp).size(16.dp).clip(CircleShape).background(p.bg), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            }
        }
        Row(
            Modifier.weight(1f).heightIn(min = if (tall) 92.dp else 56.dp).clip(RoundedCornerShape(18.dp)).background(p.surface)
                .pressable(scale = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AIcon(kindIcon(kind), Modifier.size(18.dp), p.textTertiary)
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.body.copy(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium), color = p.textPrimary)
                sub?.let { Text(it, style = Type.caption.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = p.textSecondary) }
                memo?.let { Text(it, style = Type.caption.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = p.textTertiary) } // log.row.memoLine(U37)
            }
        }
    }
}

/** 손 기록 한 줄(log.row.*): 글은 7.1 줄 글자를 나눈 것(종류 이름이 글자에 있어 색만으로 구분하지 않는다, 8장). 밀면 지우기. */
@Composable
private fun HandRow(e: CareEvent, onOpen: () -> Unit, onDelete: (onFail: () -> Unit) -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    val rowScope = rememberCoroutineScope()
    // 지우기를 저장하지 못했으면 밀린 줄을 되돌린다(기록은 그대로 있다).
    val restore: () -> Unit = { rowScope.launch { swipe.reset() } }
    LaunchedEffect(swipe.currentValue) { if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete(restore) }
    val p = Tokens.colors
    val (title, sub) = splitRow(logRowText(e, e.zoneOrDevice()))
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        // 밀 때만 그린다(가만히 있을 때 카드 모서리 밖으로 빨간 테두리가 비치지 않게).
        backgroundContent = {
            if (swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart) Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.width(52.dp))
                Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(18.dp)).background(p.danger).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                    AIcon(R.drawable.ic_trash_2, Modifier.size(20.dp), androidx.compose.ui.graphics.Color.White)
                }
            }
        },
    ) {
        // 밀기를 못 쓰는 화면 읽기에도 같은 지우기(되돌리기 포함)를 준다(iOS swipeActions와 같음).
        TimelineCard(
            e.kind, title, sub, memoLine(e),
            tall = e.kind == CareKind.SLEEP && (e.endAt ?: e.startAt) - e.startAt >= 60 * MINUTE,
            modifier = Modifier.background(p.bg).semantics { customActions = listOf(CustomAccessibilityAction("지우기") { onDelete(restore); true }) },
            onClick = onOpen,
        )
    }
}

/**
 * 패턴(C5, R14·R16·U27, 시안 S.pattern): '지난 7일' 숫자 줄 → 수유 간격 7일 막대(평소 선) → 잠 패턴 히트맵(잠·기저귀 켤 때) →
 * 차에서 울음 7일 막대 → 안내. 칸 이름과 값은 7.1 pattern.*.
 */
@Composable
private fun PatternView(ui: RecordsUiState, now: Long) {
    val reports = ui.phone.reports.values.map { it.body }
    val minute = now / MINUTE
    val p = remember(ui.care, ui.phone.reports, minute) { carePattern(ui.care, reports, now, deviceZone) }
    val iw = remember(ui.care, minute) { intervalWeek(ui.care, now, deviceZone) }
    val cw = remember(ui.phone.reports, minute) { carCryWeek(reports, now, deviceZone) }
    val trend = remember(ui.care, minute) { feedTrend(ui.care, now, deviceZone) }
    val c = Tokens.colors
    Block {
        BlockHeader("지난 7일") // pattern.header
        KpiRow(
            buildList {
                // pattern.days는 늘 보이고, 앞 주와 견줄 수 있으면 그 아래 '지난주보다 …'(design.chart).
                add(Kpi("하루 수유", avgCountText(p.feedsPerDay, p.feedDays), daysText(p.feedDays), trend))
                // 간격 중앙값이 아직 없으면(5개 미만, R14) 7장 문구를 값 아래에 그대로 보인다.
                add(Kpi("수유 간격", p.feedIntervalMs?.let(::durationText) ?: "—", if (p.feedIntervalMs != null) "중앙값" else "기록이 더 쌓이면 보여 드려요"))
                add(Kpi("차에서 울음", "${p.carCryEpisodes}번", "7일 동안"))
                p.sleepPerDayMs?.let { add(Kpi("하루 잠", avgDurationText(it, p.sleepDays), daysText(p.sleepDays))) }
                p.diapersPerDay?.let { add(Kpi("하루 기저귀", avgCountText(it, p.diaperDays), daysText(p.diaperDays))) }
            },
        )
    }
    Block {
        BlockHeader("수유 간격")
        val sub = listOfNotNull(iw.todayMs?.let { "오늘 **${durationText(it)}**" }, iw.usualMs?.let { "평소 ${durationText(it)}" }).joinToString(" · ")
        if (sub.isNotEmpty()) BlockSub(sub)
        WeekBars(iw.bars, c.cFeed, ::durationText, "지난 7일 수유 간격 중앙값", target = iw.usualMs, targetLabel = iw.usualMs?.let { "평소 ${durationText(it)}" })
    }
    if (ui.care.sleepDiaperEnabled) Block {
        val g = remember(ui.care, minute) { sleepGrid(ui.care, now, deviceZone) }
        BlockHeader("잠 패턴")
        g.avgMs?.let { BlockSub("하루 평균 **${durationText(it)}** · ${g.sleepDays}일 기준") }
        SleepHeat(g)
    }
    Block {
        BlockHeader("차에서 울음")
        BlockSub(listOfNotNull("7일 동안 **${cw.total}번**", cw.avgLatencyS?.let { "평균 감지 ${secondsText(it)}" }).joinToString(" · "))
        WeekBars(cw.bars, c.cCar, { "${it}번" }, "지난 7일 차에서 울음 횟수", height = 110.dp)
    }
    FootNote("직접 기록한 것만 셉니다. 빠진 기록이 있으면 실제와 다를 수 있어요")
}
