@file:OptIn(ExperimentalMaterial3Api::class)

package kr.aigyeot.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import android.Manifest
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.aigyeot.phone.AppModel
import kr.aigyeot.phone.core.APP_ZONE
import kr.aigyeot.phone.core.CareEvent
import kr.aigyeot.phone.core.SAVE_FAILED
import kr.aigyeot.phone.core.CareFile
import kr.aigyeot.phone.core.CareKind
import kr.aigyeot.phone.core.CareProblem
import kr.aigyeot.phone.core.CareSource
import kr.aigyeot.phone.core.CarLine
import kr.aigyeot.phone.core.Diaper
import kr.aigyeot.phone.core.FeedSide
import kr.aigyeot.phone.core.MINUTE
import kr.aigyeot.phone.core.NOTE_MAX
import kr.aigyeot.phone.core.PhoneState
import kr.aigyeot.phone.core.agoText
import kr.aigyeot.phone.core.avgCountText
import kr.aigyeot.phone.core.avgDurationText
import kr.aigyeot.phone.core.carLines
import kr.aigyeot.phone.core.carePattern
import kr.aigyeot.phone.core.dayEvents
import kr.aigyeot.phone.core.daysText
import kr.aigyeot.phone.core.diaperName
import kr.aigyeot.phone.core.elapsed
import kr.aigyeot.phone.core.firstAtOrAfter
import kr.aigyeot.phone.core.instantOf
import kr.aigyeot.phone.core.intervalText
import kr.aigyeot.phone.core.isOngoing
import kr.aigyeot.phone.core.koClock
import kr.aigyeot.phone.core.koDate
import kr.aigyeot.phone.core.lastFeedingAt
import kr.aigyeot.phone.core.latestAtOrBefore
import kr.aigyeot.phone.core.nearestAtOrBefore
import kr.aigyeot.phone.core.logRowText
import kr.aigyeot.phone.core.memoLine
import kr.aigyeot.phone.core.onDate
import kr.aigyeot.phone.core.ongoing
import kr.aigyeot.phone.core.ongoingText
import kr.aigyeot.phone.core.overMax
import kr.aigyeot.phone.core.overlapText
import kr.aigyeot.phone.core.overlaps
import kr.aigyeot.phone.core.problem
import kr.aigyeot.phone.core.problemText
import kr.aigyeot.phone.core.quickKinds
import kr.aigyeot.phone.core.sideName
import kr.aigyeot.phone.core.text
import kr.aigyeot.phone.core.timerText
import kr.aigyeot.phone.core.ulid
import kr.aigyeot.shared.Role
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// 돌봄 기록 화면(phone-care-plan 5·7·7.1장). 화면 글자는 7장·7.1 표 그대로이고, 표에 없는 글자는 만들지 않는다(U26).
// 주석의 `tab.today` 같은 이름은 7.1 표의 ID다.

/** 7.1 today.quick.* · timer.title.* 의 종류 이름. */
private fun kindName(k: CareKind) = when (k) {
    CareKind.FEED -> "수유"
    CareKind.SLEEP -> "잠"
    CareKind.DIAPER -> "기저귀"
    CareKind.NOTE -> "메모"
}

/** 7.1 sheet.title.* / sheet.edit.* */
private fun sheetTitle(k: CareKind, edit: Boolean) = kindName(k) + if (edit) " 고치기" else " 적기"

private const val GUARDIAN_ONLY = "보호자 기록은 이 휴대폰에만 남아요" // 7장(U23)

/**
 * 화면이 앞에 있는 동안만 시계를 다시 읽는다(진행 중 경과 표시, R15: 저장하지 않는다).
 * 구성은 뒤로 가도 남으므로 STARTED일 때만 돈다(11-05 성능 확인: 뒤에서 30초에 90번 깨어나던 것). 돌아오면 바로 다시 읽는다.
 */
@Composable
private fun rememberNow(periodMs: Long): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(System.currentTimeMillis(), periodMs, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(periodMs)
            }
        }
    }.value
}

private val deviceZone: ZoneId get() = APP_ZONE

private fun CareEvent.zoneOrDevice(): ZoneId = runCatching { ZoneId.of(zone) }.getOrDefault(deviceZone)

/** 시트·타이머의 한 행: 행 이름(7.1 sheet.row.*) + 내용. */
@Composable
private fun RowOf(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Muted(label)
        content()
    }
}

// ---------------------------------------------------------------- 탭(5장)

/** owner·연결 안 됨: [오늘] [기록] [차] [설정], guardian: [오늘] [기록] [설정](tab.*). 손 기록 지우기의 되돌리기 띠도 여기서 보인다. */
@Composable
fun CareTabs(model: AppModel, go: (String) -> Unit) {
    val creds by model.creds.collectAsState()
    val ui by model.ui.collectAsState()
    val guardian = creds?.role == Role.GUARDIAN
    val tabs = if (guardian) listOf("오늘", "기록", "설정") else listOf("오늘", "기록", "차", "설정")
    var tab by rememberSaveable { mutableStateOf("오늘") }
    if (tab !in tabs) tab = "오늘"
    val snack = remember { SnackbarHostState() }
    // U25 b: 설정에서 끊기가 끝나면 오늘로 간다. 띠와 '기록만 쓰기'는 모델이 정한다(끊는 사이 다른 탭으로 가도 빠지지 않게).
    val disconnect by model.disconnectRun.collectAsState()
    LaunchedEffect(disconnect) {
        if (disconnect?.let { !it.busy && it.error == null } == true) {
            tab = "오늘"
            // 오늘·띠를 한 번 그린 뒤 끊기 표시를 지운다: 그 전에 죽으면 다음 실행이 다시 보인다.
            withFrameNanos { }
            withFrameNanos { }
            model.disconnectShown()
        }
    }
    // 7장 '기록 지우기 되돌리기': "지웠어요" + "되돌리기"(5초). 타이머에서 지운 것도 돌아와서 보인다(U38).
    // 효과 하나가 계속 받는다: id를 키로 두면 비우는 순간 효과가 취소돼 띠가 바로 사라진다(11-05 에뮬레이터에서 찾음).
    // 띠가 떠 있는 동안 또 지우면 앞 띠를 닫고 새 기록의 5초를 시작한다(collectLatest).
    LaunchedEffect(Unit) {
        model.ui.mapNotNull { it.undoCareId }.collectLatest { id ->
            model.clearUndoCare()
            val r = withTimeoutOrNull(5_000) { snack.showSnackbar("지웠어요", "되돌리기", duration = SnackbarDuration.Indefinite) }
            if (r == SnackbarResult.ActionPerformed) model.undoDeleteCare(id)
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(windowInsets = WindowInsets(0)) {
                for (t in tabs) NavigationBarItem(
                    selected = t == tab,
                    onClick = { tab = t },
                    icon = { Box(Modifier.size(8.dp).background(if (t == tab) Tokens.colors.accent else Tokens.colors.textSecondary, CircleShape)) },
                    label = { Text(t) },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                "기록" -> RecordsTab(model, go)
                "차" -> CarTab(model, go)
                "설정" -> SettingsTab(model, go)
                else -> TodayTab(model, go)
            }
        }
    }
}

// ---------------------------------------------------------------- 오늘(C1)

/** 알림 권한은 처음 타이머를 시작할 때 한 번만 묻고(13+), 묻기 전에 한 줄을 보인다(notify.permission). 거절하면 앱 안에서만 보인다. */
@Composable
private fun rememberStarter(model: AppModel): (CareKind) -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refreshCareNotice() }
    return { kind ->
        if (Build.VERSION.SDK_INT >= 33 && model.takeNotificationAsk()) {
            Toast.makeText(context, "수유 시간을 잠금 화면에 보여 드릴게요", Toast.LENGTH_LONG).show()
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        model.startCare(kind)
    }
}

/** 상태 카드: 마지막 수유(today.lastFeed.*) · 진행 중(today.running.*) · (owner) 차로 보내기 상태(7장 지금 문구). */
@Composable
private fun StatusCard(care: CareFile, s: PhoneState, owner: Boolean, now: Long, onStart: () -> Unit, go: (String) -> Unit) {
    val last = lastFeedingAt(care, s)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            if (last == null) {
                Text("아직 수유 기록이 없어요", style = Type.headline)
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("수유 시작") }
            } else {
                Muted("마지막 수유")
                Text(agoText(last, now), style = Type.display)
                // 그 시각의 손 기록이 있으면 그 기록의 시간대(R13)와 쪽을 쓴다. 차 값에는 둘 다 없다.
                val mine = care.events.filter { it.kind == CareKind.FEED && it.deletedAt == null && it.startAt == last }
                val side = mine.firstNotNullOfOrNull { it.feedSide }
                Muted(listOfNotNull(koClock(last, mine.firstOrNull()?.zoneOrDevice() ?: deviceZone), side?.let(::sideName)).joinToString(" · "))
            }
            // 카드에 보인 그 수유를 이 폰이 보냈을 때만 전송 상태를 붙인다(계약 7.5). 차 값이 더 늦으면 붙이지 않는다.
            // 전송 기록의 보낸 시작(`startAt`)으로 맞춘다: `at`은 초까지이고 중계가 미래 시각을 고칠 수 있다. 옛 기록은 `at`으로.
            if (owner && last != null) s.feedings.findLast { (it.startAt ?: instantOf(it.at)) == last }?.let { r ->
                val status = when {
                    r.failed != null -> r.failed
                    r.reflected -> "차에 반영됨"
                    r.sentAt != null -> "보냄 ${koClock(r.sentAt, deviceZone)}" // U39
                    else -> "보내는 중"
                }
                Muted(status)
            }
        }
    }
    for (kind in listOf(CareKind.FEED, CareKind.SLEEP)) {
        val e = care.ongoing(kind) ?: continue
        Card(onClick = { go("timer/${e.id}") }, modifier = Modifier.fillMaxWidth()) {
            Text(ongoingText(e, now), Modifier.padding(Space.s4), style = Type.headline)
        }
    }
}

/**
 * 빠른 버튼(today.quick.*, 7장 순서). 수유·잠은 누르면 바로 시작하고, 진행 중이면 같은 버튼이 "수유 중"·"자는 중"이 되어 타이머를 연다.
 * 메모·기저귀는 내용이 있어 시트를 연다. 길게 누르면 그 종류의 시트(지난 시각). '+'(today.quick.past)는 종류를 고르는 시트.
 */
@Composable
private fun QuickButtons(care: CareFile, start: (CareKind) -> Unit, sheet: (CareKind, Boolean) -> Unit, go: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        for (k in quickKinds(care.sleepDiaperEnabled)) {
            val running = care.ongoing(k)
            val label = when {
                running == null -> kindName(k)
                k == CareKind.FEED -> "수유 중"
                else -> "자는 중"
            }
            // U51: 평소 surfaceRaised·종류 색 점·textPrimary, 진행 중 accent·onAccent. 종류 색을 바탕에 깔지 않는다.
            val p = Tokens.colors
            Surface(
                shape = Radius.rLarge,
                color = if (running == null) p.surfaceRaised else p.accent,
                contentColor = if (running == null) p.textPrimary else p.onAccent,
                modifier = Modifier.heightIn(min = 64.dp).width(150.dp).combinedClickable(
                    role = androidx.compose.ui.semantics.Role.Button,
                    onLongClick = { sheet(k, false) },
                    onClick = {
                        when {
                            running != null -> go("timer/${running.id}")
                            k == CareKind.FEED || k == CareKind.SLEEP -> start(k)
                            else -> sheet(k, false)
                        }
                    },
                ),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    KindIcon(k, if (running == null) kindColor(k, p) else p.onAccent)
                    Spacer(Modifier.width(Space.s2))
                    Text(label, style = Type.headline)
                }
            }
        }
        OutlinedButton(
            onClick = { sheet(CareKind.FEED, true) },
            modifier = Modifier.heightIn(min = 64.dp).semantics { contentDescription = "지난 기록 적기" },
        ) { Text("+", style = Type.headline) }
    }
}

/** 다음 수유 시간대(차 값이 있을 때만, 7장). */
@Composable
private fun NextWindow(s: PhoneState) {
    val at = instantOf(s.feeding?.value?.windowStartExpected) ?: return
    Column {
        Text("다음 수유 시간대 · ${koClock(at, deviceZone)}부터", style = Type.body)
        Muted("차에 정해 둔 간격 기준")
    }
}

/**
 * 오늘. owner·연결 안 됨: 상태 카드 → 빠른 버튼 → 다음 수유 시간대 → 차 타일(5장).
 * guardian: 인계 알림 띠 → 상태 카드 → 빠른 버튼(U26) → 다음 수유 시간대 → 차 타일 → 최근 리포트(today.recentReports).
 */
@Composable
fun TodayTab(model: AppModel, go: (String) -> Unit) {
    val s by model.state.collectAsState()
    val care by model.care.collectAsState()
    val ui by model.ui.collectAsState()
    val creds by model.creds.collectAsState()
    val guardian = creds?.role == Role.GUARDIAN
    val now = rememberNow(15_000)
    val start = rememberStarter(model)
    var sheet by rememberSaveable { mutableStateOf<Pair<CareKind, Boolean>?>(null) } // 회전·다시 그리기에도 시트를 둔다
    Page(care.childNickname?.let { "${it}의 오늘" } ?: "오늘") { // today.header(U34)
        if (guardian) GuardianAlerts(model, go) else StatusBands(model, connected = creds != null)
        ui.careNotice?.let { Band(it, "닫기") { model.clearCareNotice() } }
        StatusCard(care, s, owner = creds?.role == Role.OWNER, now = now, onStart = { start(CareKind.FEED) }, go = go)
        QuickButtons(care, start, { k, choose -> sheet = k to choose }, go)
        if (!guardian) NextWindow(s) // U35: guardian은 feeding_state를 받지 못한다
        when {
            guardian -> Unit // U35: 차 타일 없음(기기 목록을 받지 못한다)
            creds == null -> ConnectCarCard(go)
            else -> CarTile(s, ui.online)
        }
        if (guardian) {
            TextButton(onClick = { go("reports") }) { Text("최근 리포트", style = Type.headline) }
            s.reports.values.sortedByDescending { instantOf(it.body.startedAt) ?: 0 }.take(3).forEach { ReportCard(it.body) { id -> go("report/$id") } }
        }
    }
    sheet?.let { (k, choose) -> CareSheet(model, k, initial = null, baseDate = null, chooseKind = choose, guardian = guardian) { sheet = null } }
}

// ---------------------------------------------------------------- 타이머(C2)

@Composable
fun TimerScreen(model: AppModel, id: String, onBack: () -> Unit) {
    val care by model.care.collectAsState()
    val creds by model.creds.collectAsState()
    val now = rememberNow(1_000)
    val scope = rememberCoroutineScope()
    val e = care.events.find { it.id == id }
    var pick by remember { mutableStateOf<String?>(null) } // "start" | "end"
    var error by remember { mutableStateOf<String?>(null) }
    var overlap by remember { mutableStateOf<CareEvent?>(null) }
    fun save(next: CareEvent) = scope.launch { if (model.editOngoing(next.id) { it.copy(startAt = next.startAt, endAt = next.endAt) }) error = null }
    // 끝났거나 지운 기록이면 화면을 닫는다(알림 '끝내기'로 닫힌 경우 등).
    LaunchedEffect(e?.isOngoing) { if (e == null || !e.isOngoing) onBack() }
    if (e == null || !e.isOngoing) return
    Page(kindName(e.kind), onBack) { // timer.title.*
        StatusBands(model, connected = creds != null)
        if (creds?.role == Role.GUARDIAN) Muted(GUARDIAN_ONLY)
        Text(timerText(e.elapsed(now)), style = Type.timer)
        if (e.kind == CareKind.FEED) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) { // timer.side
                for (side in FeedSide.entries) FilterChip(
                    selected = e.feedSide == side,
                    onClick = { scope.launch { model.editOngoing(e.id) { it.copy(feedSide = side, amountMl = if (side == FeedSide.BOTTLE) it.amountMl else null) } } },
                    label = { Text(sideName(side)) },
                )
            }
            if (e.feedSide == FeedSide.BOTTLE) AmountRow(e.amountMl) { ml -> scope.launch { model.editOngoing(e.id) { it.copy(amountMl = ml) } } }
        }
        if (e.overMax(now)) {
            // U14: R3 안내와 함께 끝 시각 고르기·끝 시각 모름으로 닫기.
            Text(if (e.kind == CareKind.FEED) "수유가 3시간째예요. 끝내는 걸 잊으셨나요?" else "잠이 16시간째예요. 끝내는 걸 잊으셨나요?") // timer.overMax.*(U36)
            Button(onClick = { pick = "end" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("끝 시각 고르기") }
            OutlinedButton(onClick = { model.endCareUnknown(e.id) }, modifier = Modifier.fillMaxWidth()) { Text("끝 시각 모름으로 닫기") }
        } else {
            Button(onClick = { model.endCare(e.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text("끝내기", style = Type.headline) }
        }
        OutlinedButton(onClick = { pick = "start" }, modifier = Modifier.fillMaxWidth()) { Text("시작 시각 수정") }
        TextButton(onClick = { model.deleteCare(e.id) }) { Text("이 기록 지우기", color = Tokens.colors.danger) }
        error?.let { Text(it, color = Tokens.colors.danger) }
    }
    pick?.let { which ->
        val zone = e.zoneOrDevice()
        val ref = Instant.ofEpochMilli(if (which == "start") e.startAt else now).atZone(zone)
        TimeDialog(ref.hour, ref.minute, onDismiss = { pick = null }) { h, m ->
            val next = if (which == "start") e.copy(startAt = nearestAtOrBefore(h, m, e.startAt, System.currentTimeMillis() + MINUTE, zone))
            else e.copy(endAt = firstAtOrAfter(h, m, e.startAt, zone))
            val t = System.currentTimeMillis()
            val p = next.problem(t)
            pick = null
            when {
                p != null -> error = problemText(p, e.kind)
                care.overlaps(next, t) -> overlap = next // R4: 시트와 같은 경고
                else -> save(next)
            }
        }
    }
    overlap?.let { o -> OverlapDialog(o.kind, onSave = { overlap = null; save(o) }, onDismiss = { overlap = null }) }
}

/** R4 겹침 경고(7장 겹침): "그래도 저장" / "고치기". */
@Composable
private fun OverlapDialog(kind: CareKind, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(overlapText(kind)) },
        confirmButton = { TextButton(onClick = onSave) { Text("그래도 저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("고치기") } },
    )
}

/** 젖병 양(timer.amount · sheet.row.amount): "양" + "{n}ml", ±10ml, 0~400. 빈 값에서 +는 10ml(U39). */
@Composable
private fun AmountRow(ml: Int?, onChange: (Int?) -> Unit) {
    RowOf("양") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            OutlinedButton(enabled = ml != null, onClick = { onChange(ml?.let { (it - 10).coerceAtLeast(0) }) }) { Text("−") }
            Text(ml?.let { "${it}ml" } ?: "", style = Type.headline.copy(fontFeatureSettings = "tnum"))
            OutlinedButton(onClick = { onChange(((ml ?: 0) + 10).coerceAtMost(400)) }) { Text("+") }
        }
    }
}

/** 시·분 고르기(플랫폼다움: Material 시각 선택기). 확인 버튼은 시스템 글자를 쓴다. */
@Composable
private fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(state.hour, state.minute) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        text = { TimePicker(state) },
    )
}

// ---------------------------------------------------------------- 빠른 기록·고치기 시트(C3)

private val agoChips = listOf("지금" to 0L, "5분 전" to 5L, "15분 전" to 15L, "30분 전" to 30L, "1시간 전" to 60L)

/**
 * 새 기록(initial = null)과 고치기. 수유·잠은 '시작'·'끝'(끝 시각 모름 또는 고른 시각), 기저귀·메모는 '시각' 한 줄.
 * `baseDate`가 있으면(기록 탭의 다른 날) 시각은 그 날의 시각이다. 저장 전에 R3 안내, R4 겹침 경고를 보인다.
 */
@Composable
fun CareSheet(
    model: AppModel,
    kind: CareKind,
    initial: CareEvent?,
    baseDate: LocalDate?,
    chooseKind: Boolean = false,
    guardian: Boolean,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val care by model.care.collectAsState()
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
            // 저장 실패는 시트 안에서도 보인다. 띠의 다른 줄(버림 안내)은 시트에 옮기지 않는다.
            when {
                model.saveCareEvent(e, editing = initial != null) -> onClose()
                // 고치던 기록이 그사이 지워졌다: 저장하지 않고 닫는다(되살리지 않는다, R10).
                initial != null && model.care.value.events.none { it.id == initial.id && it.deletedAt == null } -> onClose()
                else -> error = SAVE_FAILED.takeIf { model.ui.value.notice?.lines()?.contains(it) == true }
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

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.s5, vertical = Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Text(sheetTitle(k, edit = initial != null), style = Type.title)
            if (chooseKind && initial == null) {
                val kinds = quickKinds(care.sleepDiaperEnabled)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    kinds.forEachIndexed { i, x ->
                        SegmentedButton(selected = x == k, onClick = { k = x; error = null }, shape = SegmentedButtonDefaults.itemShape(i, kinds.size)) { Text(kindName(x)) }
                    }
                }
            }
            if (guardian) Muted(GUARDIAN_ONLY)
            // 시작(수유·잠) 또는 시각(기저귀·메모)
            RowOf(if (timed) "시작" else "시각") {
                if (initial == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        if (baseDate == null) for ((label, min) in agoChips) FilterChip(
                            selected = chip == label,
                            onClick = { chip = label; startAt = System.currentTimeMillis() - min * MINUTE; error = null },
                            label = { Text(label) },
                        )
                        FilterChip(selected = chip == "직접", onClick = { pick = "start" }, label = { Text("직접") })
                    }
                    Muted(listOfNotNull(baseDate?.let(::koDate), koClock(startAt, zone)).joinToString(" "))
                } else {
                    OutlinedButton(onClick = { pick = "start" }) { Text(koClock(startAt, zone)) }
                }
            }
            if (timed) RowOf("끝") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    FilterChip(selected = endAt == null, onClick = { endAt = null; error = null }, label = { Text("끝 시각 모름") })
                    FilterChip(selected = endAt != null, onClick = { pick = "end" }, label = { Text(endAt?.let { koClock(it, zone) } ?: "직접") })
                }
            }
            when (k) {
                CareKind.FEED -> {
                    RowOf("쪽") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                            for (x in FeedSide.entries) FilterChip(selected = side == x, onClick = { side = if (side == x) null else x }, label = { Text(sideName(x)) })
                        }
                    }
                    if (side == FeedSide.BOTTLE) AmountRow(ml) { ml = it }
                }
                CareKind.DIAPER -> RowOf("종류") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        for (x in Diaper.entries) FilterChip(selected = diaper == x, onClick = { diaper = x }, label = { Text(diaperName(x)) })
                    }
                }
                else -> Unit
            }
            RowOf("메모") {
                OutlinedTextField(
                    value = note,
                    // U17·U24: 200자(코드 포인트)에서 더 받지 않는다.
                    onValueChange = { if (it.codePointCount(0, it.length) <= NOTE_MAX) note = it },
                    placeholder = { Text("예: 트림 잘 함") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            error?.let { Text(it, color = Tokens.colors.danger) }
            Button(onClick = { trySave() }, enabled = !saving && (k != CareKind.NOTE || note.isNotBlank()), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("저장") }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                TextButton(onClick = onClose) { Text("취소") }
                if (initial != null) TextButton(onClick = { model.deleteCare(initial.id); onClose() }) { Text("지우기", color = Tokens.colors.danger) }
            }
            Spacer(Modifier.height(Space.s4))
        }
    }
    pick?.let { which ->
        val ref = Instant.ofEpochMilli(if (which == "end") endAt ?: startAt else startAt).atZone(zone)
        TimeDialog(ref.hour, ref.minute, onDismiss = { pick = null }) { h, m ->
            val now = System.currentTimeMillis()
            when {
                which == "end" -> endAt = firstAtOrAfter(h, m, startAt, zone)
                initial != null -> startAt = onDate(Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate(), h, m, zone)
                baseDate != null -> startAt = onDate(baseDate, h, m, zone)
                else -> startAt = latestAtOrBefore(h, m, now + MINUTE, zone)
            }
            if (which == "start") chip = "직접"
            error = null
            pick = null
        }
    }
    overlap?.let { e -> OverlapDialog(e.kind, onSave = { overlap = null; save(e) }, onDismiss = { overlap = null }) }
}

// ---------------------------------------------------------------- 기록(C4·C5·C14)

@Composable
fun RecordsTab(model: AppModel, go: (String) -> Unit) {
    var pattern by rememberSaveable { mutableStateOf(false) }
    val creds by model.creds.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        // 고정 영역이라 큰 글자로 띠가 쌓여 넘치면 묶음째 굴린다(8장 동적 글자 크기, iOS StatusBands와 같은 200).
        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Spacer(Modifier.height(Space.s2))
            StatusBands(model, connected = creds != null)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { // log.segment
            SegmentedButton(selected = !pattern, onClick = { pattern = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("하루") }
            SegmentedButton(selected = pattern, onClick = { pattern = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("패턴") }
        }
        if (pattern) PatternView(model) else DayView(model, go)
    }
}

/** log.date.*: "오늘" / "어제" / "11월 5일(수)". */
private fun dayLabel(d: LocalDate, today: LocalDate) = when (d) {
    today -> "오늘"
    today.minusDays(1) -> "어제"
    else -> koDate(d)
}

/** 날짜 띠 + 하루 타임라인(손 기록 + 차 기록 줄). 손 기록은 누르면 고치기(진행 중이면 타이머), 밀면 지우기. */
@Composable
private fun ColumnScope.DayView(model: AppModel, go: (String) -> Unit) {
    val care by model.care.collectAsState()
    val s by model.state.collectAsState()
    val creds by model.creds.collectAsState()
    val now = rememberNow(30_000)
    val today = Instant.ofEpochMilli(now).atZone(deviceZone).toLocalDate()
    var dayEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val day = LocalDate.ofEpochDay(dayEpoch)
    // 고치는 기록은 id로 둔다(화면을 다시 만들어도 남고, 지금 기록에서 다시 찾는다).
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> care.events.firstOrNull { it.id == id && it.deletedAt == null } }
    var adding by rememberSaveable { mutableStateOf(false) }
    val allCars = carLines(s.reports.values.map { it.body })
    // 지난 30일. 기록이 있는 날이 그 밖이면(더 오래됨) 그날까지 늘린다: 돌봄 기록은 기간으로 지우지 않으니(U44) 열 수 있어야 한다(iOS `DayStrip`과 같음).
    val recordDays = care.events.filter { it.deletedAt == null }.map { Instant.ofEpochMilli(it.startAt).atZone(it.zoneOrDevice()).toLocalDate() } +
        allCars.map { Instant.ofEpochMilli(it.startAt).atZone(deviceZone).toLocalDate() }
    val first = minOf(today.minusDays(29), recordDays.minOrNull() ?: today)
    val last = maxOf(today, recordDays.maxOrNull() ?: today)
    // 날을 목록으로 만들지 않고 보이는 칸만 계산한다: 차 리포트의 날짜가 크게 어긋나도(먼 미래 등) 멈추지 않는다.
    val span = ChronoUnit.DAYS.between(first, last).coerceAtMost(Int.MAX_VALUE - 1L).toInt() + 1
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = ChronoUnit.DAYS.between(first, today).coerceAtMost(span - 1L).toInt())
    LazyRow(state = strip, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        items(span, key = { first.toEpochDay() + it }) { i ->
            val d = first.plusDays(i.toLong())
            FilterChip(selected = d == day, onClick = { dayEpoch = d.toEpochDay() }, label = { Text(dayLabel(d, today)) })
        }
    }
    val hand = care.dayEvents(day, deviceZone)
    val cars = allCars.filter { Instant.ofEpochMilli(it.startAt).atZone(deviceZone).toLocalDate() == day }
    val rows: List<Pair<Long, Any>> = (hand.map { it.startAt to it } + cars.map { it.startAt to it }).sortedBy { it.first }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        if (rows.isEmpty()) Muted("이 날 기록이 없어요", Type.body)
        for ((_, row) in rows) when (row) {
            is CareEvent -> key(row.id) {
                HandRow(row, onOpen = { if (row.isOngoing) go("timer/${row.id}") else editingId = row.id }, onDelete = { fail -> model.deleteCare(row.id, fail) })
            }
            is CarLine -> key(row.sessionId + (row.episodeId ?: "")) {
                Card(onClick = { go("report/${row.sessionId}") }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(Space.s4), verticalAlignment = Alignment.CenterVertically) {
                        KindIcon(null, Tokens.colors.car)
                        Spacer(Modifier.width(Space.s3))
                        Text(row.text(deviceZone))
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { adding = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "지난 기록 적기" },
        ) { Text("+", style = Type.headline) }
        Spacer(Modifier.height(Space.s4))
    }
    val guardian = creds?.role == Role.GUARDIAN
    // 고치던 기록이 지워지면(기록 지우기 등) 시트를 닫는다.
    LaunchedEffect(care, editingId) { if (editingId != null && editing == null) editingId = null }
    editing?.let { e -> CareSheet(model, e.kind, initial = e, baseDate = null, guardian = guardian) { editingId = null } }
    if (adding) CareSheet(model, CareKind.FEED, initial = null, baseDate = day.takeIf { it != today }, chooseKind = true, guardian = guardian) { adding = false }
}

private fun kindColor(k: CareKind, p: Palette) = when (k) {
    CareKind.FEED -> p.feed
    CareKind.SLEEP -> p.sleep
    CareKind.DIAPER -> p.diaper
    CareKind.NOTE -> p.note
}

/**
 * 종류 아이콘(13장 '종류 색은 늘 아이콘과 함께', U51). iOS SF Symbol과 같은 모양을 그린다: 수유 물방울, 잠 달, 기저귀 점선 원,
 * 메모 쪽지, `kind`가 null이면 차. 아이콘 묶음 의존성 없이 그린다. 옆 글자가 종류를 말하므로 화면 읽기에는 꾸밈이다.
 */
@Composable
private fun KindIcon(kind: CareKind?, color: Color, modifier: Modifier = Modifier.size(20.dp)) = Canvas(modifier) {
    val w = size.width
    val h = size.height
    when (kind) {
        CareKind.FEED -> {
            drawCircle(color, radius = w * 0.3f, center = Offset(w / 2, h * 0.62f))
            drawPath(Path().apply { moveTo(w / 2, h * 0.06f); lineTo(w * 0.24f, h * 0.5f); lineTo(w * 0.76f, h * 0.5f); close() }, color)
        }
        CareKind.SLEEP -> {
            val moon = Path().apply { addOval(Rect(w * 0.1f, h * 0.1f, w * 0.9f, h * 0.9f)) }
            val cut = Path().apply { addOval(Rect(w * 0.32f, h * 0.0f, w * 1.08f, h * 0.76f)) }
            drawPath(Path().apply { op(moon, cut, PathOperation.Difference) }, color)
        }
        CareKind.DIAPER -> repeat(12) { i ->
            val a = Math.toRadians(i * 30.0)
            drawCircle(color, radius = w * 0.065f, center = Offset(w / 2 + w * 0.38f * cos(a).toFloat(), h / 2 + h * 0.38f * sin(a).toFloat()))
        }
        CareKind.NOTE -> {
            val stroke = w * 0.09f
            drawRoundRect(color, Offset(w * 0.16f, h * 0.1f), Size(w * 0.68f, h * 0.8f), CornerRadius(w * 0.12f), style = Stroke(stroke))
            for (y in listOf(0.34f, 0.5f, 0.66f)) drawLine(color, Offset(w * 0.32f, h * y), Offset(w * 0.68f, h * y), stroke)
        }
        null -> {
            drawPath(Path().apply { moveTo(w * 0.26f, h * 0.44f); lineTo(w * 0.36f, h * 0.2f); lineTo(w * 0.64f, h * 0.2f); lineTo(w * 0.74f, h * 0.44f); close() }, color)
            drawRoundRect(color, Offset(w * 0.08f, h * 0.42f), Size(w * 0.84f, h * 0.3f), CornerRadius(w * 0.08f))
            drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.3f, h * 0.74f))
            drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.7f, h * 0.74f))
        }
    }
}

/** 손 기록 한 줄(log.row.*): 종류 아이콘 + 7.1 줄 글자(색만으로 구분하지 않게 종류 이름이 글자에 있다, 8장). */
@Composable
private fun HandRow(e: CareEvent, onOpen: () -> Unit, onDelete: (onFail: () -> Unit) -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    val rowScope = rememberCoroutineScope()
    // 지우기를 저장하지 못했으면 밀린 줄을 되돌린다(기록은 그대로 있다).
    val restore: () -> Unit = { rowScope.launch { swipe.reset() } }
    LaunchedEffect(swipe.currentValue) { if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete(restore) }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        // 밀 때만 그린다(가만히 있을 때 카드 모서리 밖으로 빨간 테두리가 비치지 않게).
        backgroundContent = { if (swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart) Box(Modifier.fillMaxSize().background(Tokens.colors.danger, Radius.rMedium)) },
    ) {
        // 밀기를 못 쓰는 화면 읽기에도 같은 지우기(되돌리기 포함)를 준다(iOS swipeActions와 같음).
        Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().semantics { customActions = listOf(CustomAccessibilityAction("지우기") { onDelete(restore); true }) }) {
            Row(Modifier.padding(Space.s4), verticalAlignment = Alignment.CenterVertically) {
                KindIcon(e.kind, kindColor(e.kind, Tokens.colors))
                Spacer(Modifier.width(Space.s3))
                Column {
                    Text(logRowText(e, e.zoneOrDevice()))
                    memoLine(e)?.let { Muted(it) } // log.row.memoLine(U37)
                }
            }
        }
    }
}

/** 패턴(C5, R14·R16·U27): "지난 7일", 칸 이름과 값은 7.1 pattern.*. */
@Composable
private fun ColumnScope.PatternView(model: AppModel) {
    val care by model.care.collectAsState()
    val s by model.state.collectAsState()
    val now = rememberNow(60_000)
    val p = carePattern(care, s.reports.values.map { it.body }, now, deviceZone)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Text("지난 7일", style = Type.headline)
        PatternRow("하루 수유", avgCountText(p.feedsPerDay, p.feedDays), daysText(p.feedDays))
        PatternRow("수유 간격", intervalText(p.feedIntervalMs), null)
        p.sleepPerDayMs?.let { PatternRow("하루 잠", avgDurationText(it, p.sleepDays), daysText(p.sleepDays)) }
        p.diapersPerDay?.let { PatternRow("하루 기저귀", avgCountText(it, p.diaperDays), daysText(p.diaperDays)) }
        PatternRow("차에서 울음", "${p.carCryEpisodes}번", null)
        Muted("직접 기록한 것만 셉니다. 빠진 기록이 있으면 실제와 다를 수 있어요")
        Spacer(Modifier.height(Space.s4))
    }
}

@Composable
private fun PatternRow(title: String, value: String, days: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Muted(title)
            Text(value, style = Type.headline.copy(fontFeatureSettings = "tnum"))
            days?.let { Muted(it) }
        }
    }
}

// ---------------------------------------------------------------- 차·설정 탭

/** 오늘 탭·차 탭의 차 타일(연결 안 됨, 7장): "차와 연결하면 수유 기록이 차에도 가요" + "차와 연결하기". */
@Composable
private fun ConnectCarCard(go: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text("차와 연결하면 수유 기록이 차에도 가요")
            Button(onClick = { go("connect") }) { Text("차와 연결하기") }
        }
    }
}

/**
 * 차: 연결 상태 → 우리 노래 → 리포트(5장). 섹션 머리는 지금 화면 제목 "우리 노래"·"리포트"(U39)이고, 내용은 지금 화면 그대로다.
 * 연결 안 됨이면 차 타일만.
 */
@Composable
fun CarTab(model: AppModel, go: (String) -> Unit) {
    val s by model.state.collectAsState()
    val ui by model.ui.collectAsState()
    val creds by model.creds.collectAsState()
    Page("차") {
        if (creds == null) {
            ConnectCarCard(go)
            return@Page
        }
        StatusBands(model)
        CarTile(s, ui.online)
        if (creds?.role == Role.OWNER) {
            Text("우리 노래", style = Type.headline) // car.section.songs
            SongsSection(model)
        }
        Text("리포트", style = Type.headline) // car.section.reports
        val list = s.reports.values.sortedByDescending { instantOf(it.body.startedAt) ?: 0 }
        if (list.isEmpty()) Muted("주행이 끝나면 여기에 리포트가 와요", Type.body)
        for (r in list) ReportCard(r.body) { id -> go("report/$id") }
    }
}

/** 설정 항목(settings.*): 보호자 · 아이 별명 · 잠·기저귀도 기록하기 · 내 기록 내보내기 · 이 휴대폰의 기록 지우기 · 연결 끊기 · 정보·라이선스. */
@Composable
fun SettingsTab(model: AppModel, go: (String) -> Unit) {
    val care by model.care.collectAsState()
    val creds by model.creds.collectAsState()
    val run by model.disconnectRun.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = run?.busy == true
    val error = run?.takeIf { !it.busy }?.error
    // 설정을 떠나면(회전은 빼고) 지난 끊기 실패 글을 걷는다: 인터넷이 돌아온 뒤 다시 와도 옛 '인터넷이 필요해요'가 남지 않게.
    val activity = context.findActivity()
    DisposableEffect(Unit) { onDispose { if (activity?.isChangingConfigurations != true) model.dismissDisconnectError() } }
    var confirm by remember { mutableStateOf<String?>(null) }
    var nickname by remember(care.childNickname) { mutableStateOf(care.childNickname.orEmpty()) }
    fun saveNickname() {
        if (nickname.trim() != care.childNickname.orEmpty()) model.setChildNickname(nickname)
    }
    Page("설정") {
        StatusBands(model, connected = creds != null)
        if (creds != null) OutlinedButton(onClick = { go("guardians") }, modifier = Modifier.fillMaxWidth()) { Text("보호자") }
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text("아이 별명") },
            placeholder = { Text("예: 콩이") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { saveNickname() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) saveNickname() },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("잠·기저귀도 기록하기")
                Muted("다른 육아 앱에 이미 적고 있다면 꺼 두셔도 돼요")
            }
            Switch(checked = care.sleepDiaperEnabled, onCheckedChange = model::setSleepDiaper)
        }
        HorizontalDivider()
        OutlinedButton(
            onClick = {
                scope.launch {
                    // 사본을 쓰지 못했으면(저장 공간 등) 누른 것에 답한다(노래 '듣기' 실패와 같게 토스트)
                    val file = model.exportCare() ?: return@launch Toast.makeText(context, SAVE_FAILED, Toast.LENGTH_SHORT).show()
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, null))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("내 기록 내보내기") }
        OutlinedButton(onClick = { confirm = "clear" }, modifier = Modifier.fillMaxWidth()) { Text("이 휴대폰의 기록 지우기") }
        if (creds != null) {
            Muted("기록을 지워도 연결은 그대로예요. 차에 남은 기록은 차 화면에서 지워요.")
            if (busy) Muted("끊고 있어요")
            Button(enabled = !busy, onClick = { confirm = "disconnect" }, modifier = Modifier.fillMaxWidth()) { Text("연결 끊기") }
        }
        error?.let { Band(it) }
        OutlinedButton(onClick = { go("about") }, modifier = Modifier.fillMaxWidth()) { Text("정보·라이선스") }
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "clear") "이 휴대폰의 리포트·알림·노래·돌봄 기록을 지울까요?" else "연결을 끊을까요?") },
            text = { Text(if (what == "clear") "차와 다른 보호자에게 있는 기록은 그대로예요" else "다시 연결하려면 차 화면의 새 코드가 필요해요. 돌봄 기록은 이 휴대폰에 남아요") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (what == "clear") model.clearRecords() else model.startDisconnect()
                }) { Text(if (what == "clear") "지우기" else "끊기") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } },
        )
    }
}
