@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package kr.aigyeot.phone.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch
import kr.aigyeot.phone.R
import kr.aigyeot.phone.domain.model.AGO_CHIPS
import kr.aigyeot.phone.domain.model.CarLine
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.Diaper
import kr.aigyeot.phone.domain.model.FeedSide
import kr.aigyeot.phone.domain.model.MINUTE
import kr.aigyeot.phone.domain.model.agoChipText
import kr.aigyeot.phone.domain.model.avgCountText
import kr.aigyeot.phone.domain.model.avgDurationText
import kr.aigyeot.phone.domain.model.carLines
import kr.aigyeot.phone.domain.model.carePattern
import kr.aigyeot.phone.domain.model.dayEvents
import kr.aigyeot.phone.domain.model.daysText
import kr.aigyeot.phone.domain.model.diaperName
import kr.aigyeot.phone.domain.model.durationText
import kr.aigyeot.phone.domain.model.isOngoing
import kr.aigyeot.phone.domain.model.koClock
import kr.aigyeot.phone.domain.model.koDate
import kr.aigyeot.phone.domain.model.logRowText
import kr.aigyeot.phone.domain.model.memoLine
import kr.aigyeot.phone.domain.model.quickKinds
import kr.aigyeot.phone.domain.model.sideName
import kr.aigyeot.phone.domain.model.text
import kr.aigyeot.phone.domain.model.ulid
import kr.aigyeot.phone.domain.model.kindName
import kr.aigyeot.phone.domain.model.dayLabel

// 기록 탭(C4·C5·C14)과 빠른 기록·고치기 시트(C3). 디자인 v6 시안 phone.js S.log·S.pattern·S.sheet.

/** 하루 줄을 제목(앞 두 조각: 종류·시각/구간)과 둘째 줄(나머지)로 나눈다(시안 ev(a, b), 결정 1). 글은 7.1 log.row.* 그대로다. */
internal fun splitRow(text: String): Pair<String, String?> {
    val parts = text.split(" · ")
    return if (parts.size <= 2) text to null else parts.take(2).joinToString(" · ") to parts.drop(2).joinToString(" · ")
}

// ---------------------------------------------------------------- 빈 상태(U85)

/** 빈 상태 판(U85): 아이콘 고리(spring.bouncy 0.92 → 1, 읽지 않음) + 글(7장 그대로) + 할 일 하나. 동작 줄이기면 바로 보인다. */
@Composable
internal fun EmptyState(@androidx.annotation.DrawableRes icon: Int, text: String, action: @Composable () -> Unit) {
    val p = Tokens.colors
    val reduced = LocalReducedMotion.current
    val ring = remember { androidx.compose.animation.core.Animatable(if (reduced) 1f else 0.92f) }
    LaunchedEffect(Unit) { if (!reduced) ring.animateTo(1f, Springs.bouncy.spec()) }
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(64.dp).scale(ring.value).clip(androidx.compose.foundation.shape.CircleShape).background(p.surface),
            contentAlignment = Alignment.Center,
        ) { AIcon(icon, Modifier.size(26.dp), p.textSecondary) }
        Text(text, style = Type.rowTitle, color = p.textPrimary)
        action()
    }
}

/** 패턴 수유 간격 5개 미만(U85 ③): 막대 자리 위 가운데 '기록이 더 쌓이면 보여 드려요' + 점 다섯(쌓인 만큼 채움). 읽기 이름은 감싼 판이 한 번 말한다. */
@Composable
private fun IntervalProgress(count: Int) {
    val p = Tokens.colors
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("기록이 더 쌓이면 보여 드려요", style = Type.sheetValue, color = p.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { i ->
                Box(
                    Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .then(if (i < count) Modifier.background(p.textPrimary) else Modifier.border(1.5.dp, p.textPrimary.copy(alpha = 0.26f), androidx.compose.foundation.shape.CircleShape)),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 기록(C4·C5·C14)

/** 기록 줄 판과 밀어 지우기 바탕 모서리(시안 .tl .ev 18). */
private val CardShape = RoundedCornerShape(18.dp)

/**
 * 고른 날의 줄: 지우지 않은 손 기록 + 그날 시작한 차 기록, 새 것이 위(시안). 화면은 3시간 칸마다 시각 머리("오후 12시")를 단다.
 * 비면 화면 가운데 빈 상태 판 하나(U85 ①)이고 목록 끝 '지난 기록 적기' 줄은 숨긴다.
 */
internal fun dayRows(care: CareFile, allCars: List<CarLine>, day: LocalDate, zone: java.time.ZoneId): List<Pair<Long, Any>> {
    val hand = care.dayEvents(day, zone)
    val cars = allCars.filter { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() == day }
    return (hand.map { it.startAt to it } + cars.map { it.startAt to it }).sortedByDescending { it.first }
}

/** 기록(시안 S.log·S.pattern): 큰 제목 → 하루·패턴 세그먼트 → (하루) 날짜 띠 + 타임라인 + '지난 기록 적기' / (패턴) 차트 블록들. */
@Composable
fun RecordsTab(go: (Route) -> Unit, vm: RecordsViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val fresh by vm.fresh.collectAsStateWithLifecycle()
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
        // 하루·패턴을 바꾸면 그 아래 새 블록만 한 줄씩 올라온다(세그먼트는 그대로, iOS와 같음).
        StaggerColumn(replay = pattern, animateFirst = false) {
            if (pattern) PatternView(ui, now)
            else DayView(ui, today, LocalDate.ofEpochDay(dayEpoch), { dayEpoch = it.toEpochDay() }, go, vm::delete, { editingId = it }, { adding = true }, fresh, vm::clearFresh)
        }
    }
    val editing = editingId?.let { id -> ui.care.events.firstOrNull { it.id == id && it.deletedAt == null } }
    // 고치던 기록이 지워지면(기록 지우기 등) 시트를 닫는다.
    LaunchedEffect(ui.care, editingId) { if (editingId != null && editing == null) editingId = null }
    editing?.let { e -> CareSheet(CareSheetTarget.Edit(e.id), guardian = ui.guardian) { editingId = null } }
    val day = LocalDate.ofEpochDay(dayEpoch)
    if (adding) CareSheet(CareSheetTarget.New(CareKind.FEED, day.takeIf { it != today }), chooseKind = true, guardian = ui.guardian) { adding = false }
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
    fresh: String?,
    clearFresh: (String) -> Unit,
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
                    Text(if (d == today) "오늘" else "월화수목금토일"[d.dayOfWeek.value - 1].toString(), style = Type.micro, color = if (on) p.bg.copy(alpha = 0.75f) else p.textTertiary)
                    Text("${d.dayOfMonth}", style = Type.dateNumber, color = if (on) p.bg else p.textPrimary)
                }
            }
        }
        // 고른 날 제목(log.date.*). 띠는 요일·날짜 숫자뿐이라 기록이 여러 달이면 달을 알 수 없다(iOS와 같음).
        Text(dayLabel(day, today), Modifier.padding(start = 4.dp, top = 2.dp).semantics { heading() }, style = Type.headline, color = p.textPrimary)
        val rows = dayRows(care, allCars, day, deviceZone)
        // U85 ①: 빈 날은 화면 가운데 달력 고리 + '이 날 기록이 없어요' + '＋ 지난 기록 적기' 하나(목록 끝 줄은 숨긴다: 같은 일 두 번 금지).
        if (rows.isEmpty()) {
            EmptyState(R.drawable.ic_calendar, "이 날 기록이 없어요") {
                Box(
                    Modifier.heightIn(min = 48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.accentSoft).pressable(onClick = add)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AIcon(R.drawable.ic_plus, Modifier.size(18.dp), p.onAccentSoft)
                        Text("지난 기록 적기", style = Type.quickLabel, color = p.onAccentSoft) // today.quick.past
                    }
                }
            }
            return@Column
        }
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
                        HandRow(row, fresh = fresh == row.id, onFreshDone = { clearFresh(row.id) }, onOpen = { if (row.isOngoing) go(Route.Timer(row.id)) else edit(row.id) }, onDelete = { fail -> delete(row.id, fail) })
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
        Text(label, Modifier.padding(start = 4.dp, end = 8.dp), style = Type.hourMark, color = p.textTertiary)
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
private fun TimelineCard(kind: CareKind?, title: String, sub: String?, memo: String?, tall: Boolean = false, glow: Float = 0f, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = Tokens.colors
    val dot = kindColor(kind, p)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.padding(start = 25.dp).size(16.dp).clip(CircleShape).background(p.bg), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            }
        }
        Row(
            Modifier.weight(1f).heightIn(min = if (tall) 92.dp else 56.dp).clip(CardShape).background(lerpColor(p.surface, p.accentSoft, glow))
                .pressable(scale = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AIcon(kindIcon(kind), Modifier.size(18.dp), p.textTertiary)
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.eventTitle, color = p.textPrimary)
                sub?.let { Text(it, style = Type.footnote, color = p.textSecondary) }
                memo?.let { Text(it, style = Type.footnote, color = p.textTertiary) } // log.row.memoLine(U37)
            }
        }
    }
}

/** 손 기록 한 줄(log.row.*): 글은 7.1 줄 글자를 나눈 것(종류 이름이 글자에 있어 색만으로 구분하지 않는다, 8장). 밀면 지우기. */
@Composable
private fun HandRow(e: CareEvent, fresh: Boolean, onFreshDone: () -> Unit, onOpen: () -> Unit, onDelete: (onFail: () -> Unit) -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    val rowScope = rememberCoroutineScope()
    // 지우기를 저장하지 못했으면 밀린 줄을 되돌린다(기록은 그대로 있다).
    val restore: () -> Unit = { rowScope.launch { swipe.reset() } }
    LaunchedEffect(swipe.currentValue) { if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete(restore) }
    val p = Tokens.colors
    val (title, sub) = splitRow(logRowText(e, e.zoneOrDevice()))
    // 시트로 막 저장한 줄(U88, iOS TimelineCard fresh와 같음): 옅은 accentSoft 바탕이 1.4초 뒤 light 900ms로 걷힌다(동작 줄이기면 1.6초 뒤 바로).
    val reduced = LocalReducedMotion.current
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(fresh) {
        // 기다리거나 걷히는 중에 다른 기록을 저장해 이 줄의 표시가 넘어가면(거짓) 강조를 바로 처음으로 둔다(남지 않게).
        if (!fresh) { glow.snapTo(0f); return@LaunchedEffect }
        glow.snapTo(1f)
        kotlinx.coroutines.delay(if (reduced) 1_600 else 1_400)
        if (reduced) glow.snapTo(0f) else glow.animateTo(0f, Motion.light.spec())
        onFreshDone()
    }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        // 밀 때만 그린다(가만히 있을 때 카드 모서리 밖으로 빨간 테두리가 비치지 않게).
        backgroundContent = {
            if (swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart) Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.width(52.dp))
                Box(Modifier.weight(1f).fillMaxSize().clip(CardShape).background(p.danger).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                    AIcon(R.drawable.ic_trash_2, Modifier.size(20.dp), androidx.compose.ui.graphics.Color.White)
                }
            }
        },
    ) {
        // 밀기를 못 쓰는 화면 읽기에도 같은 지우기(되돌리기 포함)를 준다(iOS swipeActions와 같음).
        TimelineCard(
            e.kind, title, sub, memoLine(e),
            tall = e.kind == CareKind.SLEEP && (e.endAt ?: e.startAt) - e.startAt >= 60 * MINUTE,
            glow = glow.value,
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
                add(Kpi("하루 수유", avgCountText(p.feedsPerDay), daysText(p.feedDays), trend))
                // 보통 간격이 아직 없으면(5개 미만, R14) 7장 문구를 값 아래에 그대로 보인다.
                add(Kpi("수유 간격", p.feedIntervalMs?.let(::durationText) ?: "—", if (p.feedIntervalMs != null) "보통" else "기록이 더 쌓이면 보여 드려요"))
                add(Kpi("차에서 울음", "${p.carCryEpisodes}번", "7일 동안"))
                p.sleepPerDayMs?.let { add(Kpi("하루 잠", avgDurationText(it), daysText(p.sleepDays))) }
                p.diapersPerDay?.let { add(Kpi("하루 기저귀", avgCountText(it), daysText(p.diaperDays))) }
            },
        )
    }
    Block {
        BlockHeader("수유 간격")
        val progress = p.intervalProgress
        if (progress != null) {
            // U85 ③: 간격이 5개 미만(R14)이면 가짜 막대 없이 바닥 눈금(요일)만 + 가운데 문구와 쌓인 간격 수 점 다섯. 한 요소로 읽는다.
            Box(Modifier.clearAndSetSemantics { contentDescription = progress }) {
                WeekBars(iw.bars.map { it.copy(value = null, tip = null) }, c.cFeed, ::durationText, progress)
                IntervalProgress(p.feedIntervalCount)
            }
        } else {
            val sub = listOfNotNull(iw.todayMs?.let { "오늘 **${durationText(it)}**" }, iw.usualMs?.let { "평소 ${durationText(it)}" }).joinToString(" · ")
            if (sub.isNotEmpty()) BlockSub(sub)
            WeekBars(iw.bars, c.cFeed, ::durationText, "지난 7일 보통 수유 간격", target = iw.usualMs, targetLabel = iw.usualMs?.let { "평소 ${durationText(it)}" })
        }
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
    FootNote("직접 적은 기록만 세어요. 빠진 기록이 있으면 실제와 다를 수 있어요.")
}
