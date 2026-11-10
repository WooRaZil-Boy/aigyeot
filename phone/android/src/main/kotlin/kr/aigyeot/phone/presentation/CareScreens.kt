@file:OptIn(ExperimentalMaterial3Api::class)

package kr.aigyeot.phone.presentation

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.aigyeot.phone.R
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.FeedSide
import kr.aigyeot.phone.domain.model.MINUTE
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.agoText
import kr.aigyeot.phone.domain.model.carTileText
import kr.aigyeot.phone.domain.model.elapsed
import kr.aigyeot.phone.domain.model.feedingSendText
import kr.aigyeot.phone.domain.model.firstAtOrAfter
import kr.aigyeot.phone.domain.model.instantOf
import kr.aigyeot.phone.domain.model.isOngoing
import kr.aigyeot.phone.domain.model.koClock
import kr.aigyeot.phone.domain.model.lastFeedingAt
import kr.aigyeot.phone.domain.model.nearestAtOrBefore
import kr.aigyeot.phone.domain.model.ongoing
import kr.aigyeot.phone.domain.model.overMax
import kr.aigyeot.phone.domain.model.overlapText
import kr.aigyeot.phone.domain.model.overlaps
import kr.aigyeot.phone.domain.model.problem
import kr.aigyeot.phone.domain.model.problemText
import kr.aigyeot.phone.domain.model.quickKinds
import kr.aigyeot.phone.domain.model.reportRowSub
import kr.aigyeot.phone.domain.model.reportRowTitle
import kr.aigyeot.phone.domain.model.sideName
import kr.aigyeot.phone.domain.model.text
import kr.aigyeot.phone.domain.model.timerText
import kr.aigyeot.shared.ReportSummaryBody
import java.time.Instant
import java.time.ZoneId

// 돌봄 기록 화면(phone-care-plan 5·7·7.1장, 디자인 v6 시안 phone.js S.today·S.timer). 화면 글자는 7장·7.1 표 그대로이고,
// 표에 없는 글자는 시안 IMPLEMENT 6장의 확정 라벨만 쓴다. 주석의 `tab.today` 같은 이름은 7.1 표의 ID다.

/** 7.1 today.quick.* · timer.title.* 의 종류 이름. */
internal fun kindName(k: CareKind) = when (k) {
    CareKind.FEED -> "수유"
    CareKind.SLEEP -> "잠"
    CareKind.DIAPER -> "기저귀"
    CareKind.NOTE -> "메모"
}

/** 종류 아이콘(시안 data.js CARE: 젖병·달·기저귀·수첩, 차는 car-front). */
@DrawableRes
internal fun kindIcon(k: CareKind?) = when (k) {
    CareKind.FEED -> R.drawable.ic_bottle
    CareKind.SLEEP -> R.drawable.ic_moon
    CareKind.DIAPER -> R.drawable.ic_diaper
    CareKind.NOTE -> R.drawable.ic_notebook_pen
    null -> R.drawable.ic_car_front
}

/** 종류 데이터 색(잠 라벤더·수유 초록, 기저귀·메모 회색). 늘 이름과 함께 쓴다. */
internal fun kindColor(k: CareKind?, p: Palette) = when (k) {
    CareKind.FEED -> p.cFeed
    CareKind.SLEEP -> p.cSleep
    CareKind.DIAPER -> p.cDiaper
    CareKind.NOTE -> p.cNote
    null -> p.cCar
}

internal const val GUARDIAN_ONLY = "보호자 기록은 이 휴대폰에만 남아요" // 7장(U23)

/**
 * 화면이 앞에 있는 동안만 시계를 다시 읽는다(진행 중 경과 표시, R15: 저장하지 않는다).
 * 구성은 뒤로 가도 남으므로 STARTED일 때만 돈다(11-05 성능 확인: 뒤에서 30초에 90번 깨어나던 것). 돌아오면 바로 다시 읽는다.
 */
@Composable
internal fun rememberNow(periodMs: Long): Long {
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

internal val deviceZone: ZoneId get() = APP_ZONE

internal fun CareEvent.zoneOrDevice(): ZoneId = runCatching { ZoneId.of(zone) }.getOrDefault(deviceZone)

// ---------------------------------------------------------------- 탭(5장)

private data class Tab(val name: String, @DrawableRes val icon: Int)

/**
 * owner·연결 안 됨: [오늘] [기록] [차] [설정], guardian: [오늘] [기록] [설정](tab.*). 손 기록 지우기의 되돌리기 띠도 여기서 보인다.
 * 탭 막대는 흰 막대 + 먹색 선택(시안 v5 안드로이드, 알약 없음). 탭을 바꾸면 내용이 220ms에 걸쳐 바뀐다.
 */
@Composable
fun CareTabs(go: (Route) -> Unit, tab: String, onTab: (String) -> Unit, vm: CareTabsViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val tabs = tabsFor(ui.guardian)
    LaunchedEffect(tab, ui.guardian) { if (tabs.none { it.name == tab }) onTab("오늘") }
    val snack = remember { SnackbarHostState() }
    // U25 b: 설정에서 끊기가 끝나면 오늘로 간다. 띠와 '기록만 쓰기'는 끊기 유스케이스가 정한다(끊는 사이 다른 탭으로 가도 빠지지 않게).
    LaunchedEffect(ui.disconnectDone) {
        if (ui.disconnectDone) {
            onTab("오늘")
            // 오늘·띠를 한 번 그린 뒤 끊기 표시를 지운다: 그 전에 죽으면 다음 실행이 다시 보인다.
            withFrameNanos { }
            withFrameNanos { }
            vm.disconnectShown()
        }
    }
    // 7장 '기록 지우기 되돌리기': "지웠어요" + "되돌리기"(5초). 타이머에서 지운 것도 돌아와서 보인다(U38).
    // 설정의 기록 가져오기도 같은 띠다: "기록 n건을 가져왔어요" + "되돌리기"(5초, U64).
    // 띠의 수명(5초, 다른 띠로 바뀜)은 RecordCare 한 곳이 정하고 화면은 떠 있는 띠를 그리기만 한다: 띠가 걷히거나 바뀌면 이 효과가 취소돼 닫힌다.
    // 가져오기는 띠가 끝난 뒤에야 차로 보내므로(U67 11-09 보충) 화면 시계로 띠를 닫지 않는다.
    val undo by vm.undo.collectAsStateWithLifecycle()
    LaunchedEffect(undo) {
        val u = undo ?: return@LaunchedEffect
        // 눌러 닫힌 띠가 되돌리기 저장 실패로 남았으면 다시 띄운다: 남은 시간 안에 다시 누를 수 있게(U38 11-09 보충). 띠 시간이 끝나면 키가 바뀌어 멈춘다.
        while (snack.showSnackbar(u.text, "되돌리기", duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed) {
            vm.undo(u).join()
            if (vm.undo.value != u) break
        }
    }
    val p = Tokens.colors
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            val reduced = LocalReducedMotion.current
            Crossfade(tab, animationSpec = if (reduced) tween<Float>(0) else Motion.base.spec(), label = "tab") { t ->
                when (t) {
                    "기록" -> RecordsTab(go)
                    "차" -> CarTab(go)
                    "설정" -> SettingsTab(go)
                    else -> TodayTab(go, openTab = onTab)
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(12.dp)) { d ->
                Snackbar(d, shape = RoundedCornerShape(16.dp), containerColor = p.textPrimary, contentColor = p.surface, actionColor = if (p.dark) p.accent else p.glow)
            }
        }
        BottomTabs(tabs, tab, onTab)
    }
}

private fun tabsFor(guardian: Boolean) = buildList {
    add(Tab("오늘", R.drawable.ic_house))
    add(Tab("기록", R.drawable.ic_list))
    if (!guardian) add(Tab("차", R.drawable.ic_car_front))
    add(Tab("설정", R.drawable.ic_settings))
}

/** 탭 밖 화면(리포트·노래 녹음) 아래에 같은 탭 막대를 둔다. 내용은 아래 시스템 막대 자리를 탭 막대에 넘긴다. */
@Composable
fun WithTabs(guardian: Boolean, selected: String, onSelect: (String) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) { content() }
        BottomTabs(tabsFor(guardian), selected, onSelect)
    }
}

/** 탭 막대(시안 .p-android .nav v5): 흰 막대, 위 1dp 선, 아이콘 24 + 이름 11/14·500, 고르면 먹색. */
@Composable
private fun BottomTabs(tabs: List<Tab>, selected: String, onSelect: (String) -> Unit) {
    val p = Tokens.colors
    Row(
        Modifier.fillMaxWidth().background(p.surface).drawBehind { drawLine(p.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        for (t in tabs) {
            val on = t.name == selected
            val c = if (on) p.textPrimary else p.textTertiary
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
                    .selectable(on, interactionSource = null, indication = null, role = SRole.Tab) { onSelect(t.name) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AIcon(t.icon, Modifier.size(24.dp), c)
                Spacer(Modifier.height(4.dp))
                Text(t.name, style = Type.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = c)
            }
        }
    }
}

// ---------------------------------------------------------------- 오늘(C1)

/** 알림 권한은 처음 타이머를 시작할 때 한 번만 묻고(13+), 묻기 전에 한 줄을 보인다(notify.permission). 거절하면 앱 안에서만 보인다. */
@Composable
private fun rememberStarter(vm: TodayViewModel): (CareKind) -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshOngoingNotice() }
    return { kind ->
        if (Build.VERSION.SDK_INT >= 33 && vm.takeNotificationAsk()) {
            Toast.makeText(context, "수유 시간을 잠금 화면에 보여 드릴게요", Toast.LENGTH_LONG).show()
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.start(kind)
    }
}

/**
 * 오늘(시안 S.today). 인계 알림(guardian) → 진행 중 기록 → 마지막 수유 → 빠른 기록 → 오늘 하루 → 차(owner) → 최근 리포트.
 * guardian은 다음 수유 시간대·차 타일이 없다(U35). 빠른 기록은 guardian에게도 있다(U26: 이 휴대폰에만 남는 기록).
 */
@Composable
fun TodayTab(go: (Route) -> Unit, openTab: (String) -> Unit, vm: TodayViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s = ui.phone
    val care = ui.care
    val now = rememberNow(15_000)
    val start = rememberStarter(vm)
    var sheet by rememberSaveable { mutableStateOf<CareKind?>(null) } // 회전·다시 그리기에도 시트를 둔다
    val reports = remember(s.reports) { reportsNewestFirst(s.reports.values.map { it.body }) }
    TabPage(care.childNickname?.let { "${it}의 오늘" } ?: "오늘") { // today.header(U34)
        if (ui.guardian) GuardianAlerts(s, go) else StatusBands(connected = ui.connected)
        ui.careNotice?.let { BandCard(it, "닫기") { vm.clearCareNotice() } }
        for (kind in listOf(CareKind.FEED, CareKind.SLEEP)) care.ongoing(kind)?.let { e -> RunCard(e) { go(Route.Timer(e.id)) } }
        HeroFeed(care, s, owner = ui.owner, guardian = ui.guardian, now = now, onStart = { start(CareKind.FEED) })
        QuickBlock(care, start, { k -> sheet = k }, go)
        val showCar = ui.connected || s.reports.isNotEmpty()
        val day = remember(care, s.reports, now / MINUTE, showCar) {
            dayChart(care, s.reports.values.map { it.body }, Instant.ofEpochMilli(now).atZone(deviceZone).toLocalDate(), now, deviceZone, showCar)
        }
        Block {
            BlockHeader("오늘 하루", "기록") { openTab("기록") }
            DayStrip(day)
        }
        when {
            ui.guardian -> Unit // U35: 차 타일 없음(기기 목록을 받지 못한다)
            !ui.connected -> ConnectCarCard(go)
            else -> CarTileBlock(s, ui.online, onOpen = { openTab("차") })
        }
        if (ui.guardian && reports.isEmpty() && s.alerts.isEmpty()) FootNote("아직 받은 리포트·인계 알림이 없어요")
        if (reports.isNotEmpty()) {
            // today.recentReports: 새 것 셋. '전체'는 owner는 차 탭, guardian은 리포트 목록.
            ListBlock("최근 리포트", "전체", { if (ui.guardian) go(Route.Reports) else openTab("차") }) {
                reports.take(3).forEach { ReportRow(it) { id -> go(Route.Report(id)) } }
            }
        }
    }
    sheet?.let { k -> CareSheet(k, initial = null, baseDate = null, chooseKind = false, guardian = ui.guardian) { sheet = null } }
}

/** 진행 중 기록(.run-c): 살아 있는 점 + "수유 중 · 왼쪽" + "오후 2:01부터" + 흐르는 시간(굵게 28). 누르면 타이머. */
@Composable
private fun RunCard(e: CareEvent, onOpen: () -> Unit) {
    val p = Tokens.colors
    val now = rememberNow(1_000)
    val title = if (e.kind == CareKind.FEED) listOfNotNull("수유 중", e.feedSide?.let(::sideName)).joinToString(" · ") else "자는 중"
    Row(
        Modifier.sharedRun("run-${e.id}").fillMaxWidth().clip(Radius.large).background(p.surface).pressable(scale = 0.98f, onClick = onOpen).padding(horizontal = 20.dp, vertical = 18.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LiveDot(kindColor(e.kind, p))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp), color = p.textPrimary)
            Text("${koClock(e.startAt, e.zoneOrDevice())}부터", style = Type.caption.copy(fontWeight = FontWeight.Normal), color = p.textTertiary)
        }
        RollingText(timerText(e.elapsed(now)), Type.title.copy(fontSize = 28.sp, lineHeight = 32.sp, fontFeatureSettings = "tnum"), p.textPrimary)
    }
}

/**
 * 마지막 수유(.hero-c): 라벨 "마지막 수유"(today.lastFeed.title), 큰 숫자 "{경과} 전"(hero 30/38·700), 작은 글자 "{시각} · {쪽}",
 * 간격 막대(지난 시간 / 간격 × 1.25, 다음 시간대 시작 눈금은 아이 주황), (owner·차 값) "다음 수유 시간대 · {시각}부터"·"차에 정해 둔 간격 기준".
 */
@Composable
private fun HeroFeed(care: CareFile, s: PhoneState, owner: Boolean, guardian: Boolean, now: Long, onStart: () -> Unit) {
    val p = Tokens.colors
    val last = lastFeedingAt(care, s)
    Block(padding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 20.dp)) {
        if (last == null) {
            Label("마지막 수유")
            Text("아직 수유 기록이 없어요", Modifier.padding(top = 6.dp, bottom = 16.dp), style = Type.headline, color = p.textPrimary)
            Btn("수유 시작", onStart)
            return@Block
        }
        Label("마지막 수유")
        Text(agoText(last, now), Modifier.padding(top = 6.dp), style = Type.hero, color = p.textPrimary)
        // 그 시각의 손 기록이 있으면 그 기록의 시간대(R13)와 쪽을 쓴다. 차 값에는 둘 다 없다.
        val mine = care.events.filter { it.kind == CareKind.FEED && it.deletedAt == null && it.startAt == last }
        val side = mine.firstNotNullOfOrNull { it.feedSide }
        Text(
            listOfNotNull(koClock(last, mine.firstOrNull()?.zoneOrDevice() ?: deviceZone), side?.let(::sideName)).joinToString(" · "),
            Modifier.padding(top = 4.dp),
            style = Type.body.copy(fontSize = 15.sp, lineHeight = 22.sp),
            color = p.textTertiary,
        )
        // 카드에 보인 그 수유를 이 폰이 보냈을 때만 전송 상태를 붙인다(계약 7.5). 차 값이 더 늦으면 붙이지 않는다.
        if (owner) s.feedings.findLast { (it.startAt ?: instantOf(it.at)) == last }?.let { r ->
            Text(feedingSendText(r, deviceZone), style = Type.caption, color = p.textTertiary)
        }
        val window = if (guardian) null else instantOf(s.feeding?.value?.windowStartExpected) // U35: guardian은 feeding_state를 받지 못한다
        val meter = remember(care, last, now / MINUTE, window) {
            feedMeter(last, now, instantOf(s.feeding?.value?.lastFeedingStart), window, intervalWeek(care, now, deviceZone).usualMs)
        }
        meter?.let { (v, w) -> FeedMeter(v, w, Modifier.padding(top = 12.dp)) }
        window?.let { at ->
            Column(Modifier.padding(top = 12.dp)) {
                Text("다음 수유 시간대 · ${koClock(at, deviceZone)}부터", style = Type.body.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold), color = p.textPrimary)
                Text("차에 정해 둔 간격 기준", style = Type.caption.copy(fontWeight = FontWeight.Normal), color = p.textTertiary)
            }
        }
    }
}

/**
 * 빠른 기록(.qblock: 흰 블록 안 회색 원 56 넷, 7장 순서). 수유·잠은 누르면 바로 시작하고, 진행 중이면 원이 먹색으로 뒤집혀
 * "수유 중"·"자는 중"이 되어 타이머를 연다. 메모·기저귀는 시트를 연다. 길게 누르면 그 종류의 시트(지난 시각).
 */
@Composable
private fun QuickBlock(care: CareFile, start: (CareKind) -> Unit, sheet: (CareKind) -> Unit, go: (Route) -> Unit) {
    val p = Tokens.colors
    Block(padding = PaddingValues(horizontal = 8.dp, vertical = 18.dp)) {
        Row(Modifier.fillMaxWidth()) {
            for (k in quickKinds(care.sleepDiaperEnabled)) {
                val running = care.ongoing(k)
                val label = when {
                    running == null -> kindName(k)
                    k == CareKind.FEED -> "수유 중"
                    else -> "자는 중"
                }
                Column(
                    Modifier.weight(1f).pressable(
                        scale = 0.94f,
                        onLongClick = { sheet(k) },
                        onClick = {
                            when {
                                running != null -> go(Route.Timer(running.id))
                                k == CareKind.FEED || k == CareKind.SLEEP -> start(k)
                                else -> sheet(k)
                            }
                        },
                    ).padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val bg = if (running != null) p.textPrimary else p.surfaceRaised
                    Box(Modifier.size(56.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                        AIcon(kindIcon(k), Modifier.size(24.dp), if (running != null) p.surface else p.textPrimary)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(label, style = Type.caption.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = if (running != null) p.textPrimary else p.textSecondary)
                }
            }
        }
    }
}

/** 오늘 탭·차 탭의 차 타일(연결 안 됨, 7장): "차와 연결하면 수유 기록이 차에도 가요" + "차와 연결하기". */
@Composable
internal fun ConnectCarCard(go: (Route) -> Unit) = Block {
    Text("차와 연결하면 수유 기록이 차에도 가요", Modifier.padding(bottom = 16.dp), style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = Tokens.colors.textPrimary)
    Btn("차와 연결하기", { go(Route.Connect) }, icon = R.drawable.ic_qr_code)
}

/**
 * 차 타일(시안 .car-tile): 위 3D 차(실시간, 끌어서 돌리기) + 아래 '● 연결됨' · 부제(7.2 차 타일 글을 ' · '에서 나눔).
 * `onOpen`이 있으면 아래 줄을 누르면 차 탭으로 간다(오늘 탭). `right`는 오른쪽 작은 라벨(차 탭의 '휴대폰 n대').
 */
@Composable
internal fun CarTileBlock(s: PhoneState, online: Boolean, onOpen: (() -> Unit)? = null, stageHeight: androidx.compose.ui.unit.Dp = 150.dp, right: String? = null) {
    val p = Tokens.colors
    val text = carTileText(s.devices, online, rememberNow(60_000))
    val head = text.substringBefore(" · ")
    val sub = text.substringAfter(" · ", "").ifEmpty { null }
    Column(Modifier.fillMaxWidth().clip(Radius.large).background(p.surface)) {
        CarStage(Modifier.fillMaxWidth().height(stageHeight), preset = CarPreset.TILE)
        Row(
            (if (onOpen != null) Modifier.pressable(scale = 0.98f, onClick = onOpen) else Modifier).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (head == "연결됨") Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(p.active))
                    Text(head, style = Type.body.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp), color = p.textPrimary)
                }
                sub?.let { Text(it, style = Type.caption, color = p.textSecondary) }
            }
            if (right != null) Row(verticalAlignment = Alignment.CenterVertically) {
                AIcon(R.drawable.ic_smartphone, Modifier.size(16.dp), p.textTertiary)
                Spacer(Modifier.width(4.dp))
                Text(right, style = Type.caption.copy(fontSize = 14.sp, lineHeight = 20.sp), color = p.textTertiary)
            } else if (onOpen != null) AIcon(R.drawable.ic_chevron_right, Modifier.size(20.dp), p.textTertiary)
        }
    }
}

/** 리포트 목록 한 줄(report.row.*): route 아이콘 원 + "{날짜} {구간}" + "울음 {n}번". */
@Composable
internal fun ReportRow(b: ReportSummaryBody, open: (String) -> Unit) =
    ListRow(reportRowTitle(b, APP_ZONE), reportRowSub(b), icon = R.drawable.ic_route, onClick = { open(b.sessionId) })

// ---------------------------------------------------------------- 차 탭

/**
 * 차(시안 S.car): 차 타일(3D 190, '● 연결됨' · 마지막 연락, 오른쪽 휴대폰 n대) → (owner) 우리 노래 → 리포트(5장).
 * 블록 머리는 지금 화면 제목 "우리 노래"·"리포트"(U39). 연결 안 됨이면 차 타일(연결 안내)만.
 */
@Composable
fun CarTab(go: (Route) -> Unit, vm: CarViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    TabPage("차") {
        if (ui.connection !is kr.aigyeot.phone.domain.model.Connection.Paired) {
            ConnectCarCard(go)
            return@TabPage
        }
        StatusBands()
        val phones = ui.phone.devices.count { it.role != kr.aigyeot.shared.Role.CAR }
        CarTileBlock(ui.phone, ui.online, stageHeight = 190.dp, right = phones.takeIf { it > 0 }?.let { "${it}대" })
        if (ui.owner) SongsBlock(onRecord = { replacing -> go(Route.Record(replacing)) })
        val list = remember(ui.phone.reports) { reportsNewestFirst(ui.phone.reports.values.map { it.body }) }
        ListBlock("리포트") { // car.section.reports
            if (list.isEmpty()) Text("주행이 끝나면 여기에 리포트가 와요", Modifier.padding(bottom = 14.dp), style = Type.body, color = Tokens.colors.textTertiary)
            for (r in list) ReportRow(r) { id -> go(Route.Report(id)) }
        }
    }
}

// ---------------------------------------------------------------- 타이머(C2)

/**
 * 타이머(시안 S.timer): 300 고리(1시간을 한 바퀴로 지난 분만큼 호, 초침 점이 60초에 한 바퀴) 안에 흐르는 시간(timer 64/72·650)과
 * "수유 중 · 왼쪽", 쪽 세그먼트, 끝내기(큰 파랑), 시작 시각 수정(회색), 이 기록 지우기(빨간 글자).
 */
@Composable
fun TimerScreen(onBack: () -> Unit, vm: TimerViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val care = ui.care
    val now = rememberNow(1_000)
    val scope = rememberCoroutineScope()
    val e = ui.event
    var pick by remember { mutableStateOf<String?>(null) } // "start" | "end"
    var error by remember { mutableStateOf<String?>(null) }
    var overlap by remember { mutableStateOf<CareEvent?>(null) }
    fun save(next: CareEvent) = scope.launch { if (vm.edit { it.copy(startAt = next.startAt, endAt = next.endAt) }) error = null }
    // 끝났거나 지운 기록이면 화면을 닫는다(알림 '끝내기'로 닫힌 경우 등).
    LaunchedEffect(e?.isOngoing) { if (e == null || !e.isOngoing) onBack() }
    if (e == null || !e.isOngoing) return
    val p = Tokens.colors
    BackPage(kindName(e.kind), onBack) { // timer.title.*
        StatusBands(connected = ui.connected)
        if (ui.guardian) FootNote(GUARDIAN_ONLY)
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            TimerRing(e, now)
            if (e.kind == CareKind.FEED) {
                val sides = FeedSide.entries
                Segmented(sides.map(::sideName), sides.indexOf(e.feedSide), { i -> // timer.side
                    val side = sides[i]
                    scope.launch { vm.edit { it.copy(feedSide = side, amountMl = if (side == FeedSide.BOTTLE) it.amountMl else null) } }
                })
                if (e.feedSide == FeedSide.BOTTLE) AmountRow(e.amountMl) { ml -> scope.launch { vm.edit { it.copy(amountMl = ml) } } }
            }
            if (e.overMax(now)) {
                // U14: R3 안내와 함께 끝 시각 고르기·끝 시각 모름으로 닫기.
                Text(
                    if (e.kind == CareKind.FEED) "수유가 3시간째예요. 끝내는 걸 잊으셨나요?" else "잠이 16시간째예요. 끝내는 걸 잊으셨나요?", // timer.overMax.*(U36)
                    style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = p.textPrimary,
                )
                Btn("끝 시각 고르기", { pick = "end" }, big = true)
                Btn("끝 시각 모름으로 닫기", vm::endUnknown, kind = BtnKind.TONAL)
            } else {
                Btn("끝내기", { vm.end() }, icon = R.drawable.ic_square, big = true)
            }
            Column(Modifier.fillMaxWidth()) {
                Btn("시작 시각 수정", { pick = "start" }, kind = BtnKind.TONAL, icon = R.drawable.ic_clock)
                Spacer(Modifier.height(8.dp))
                Btn("이 기록 지우기", vm::delete, kind = BtnKind.DANGER_TEXT)
            }
            error?.let { Text(it, style = Type.body.copy(fontSize = 14.sp), color = p.danger) }
        }
    }
    pick?.let { which ->
        val zone = e.zoneOrDevice()
        val ref = Instant.ofEpochMilli(if (which == "start") e.startAt else now).atZone(zone)
        TimeDialog(ref.hour, ref.minute, onDismiss = { pick = null }) { h, m ->
            val next = if (which == "start") e.copy(startAt = nearestAtOrBefore(h, m, e.startAt, System.currentTimeMillis() + MINUTE, zone))
            else e.copy(endAt = firstAtOrAfter(h, m, e.startAt, zone))
            val t = System.currentTimeMillis()
            val prob = next.problem(t)
            pick = null
            when {
                prob != null -> error = problemText(prob, e.kind)
                care.overlaps(next, t) -> overlap = next // R4: 시트와 같은 경고
                else -> save(next)
            }
        }
    }
    overlap?.let { o -> OverlapDialog(o.kind, onSave = { overlap = null; save(o) }, onDismiss = { overlap = null }) }
}

/** 타이머 고리(.tring): 회색 고리 3 + 종류 색 호(지난 분 / 60) + 초침 점(60초 한 바퀴, 동작 줄이기면 멈춤). */
@Composable
private fun TimerRing(e: CareEvent, now: Long) {
    val p = Tokens.colors
    val color = kindColor(e.kind, p)
    val elapsed = e.elapsed(now)
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(true) }
    // 초침은 보일 때만 돈다(굴려 가리면 멈춤). 동작 줄이기면 지난 초 자리에 멈춘 점.
    val sweep = if (reduced || !shown) (elapsed / 1000 % 60) / 60f else rememberInfiniteTransition(label = "sweep").animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "sweep").value
    val sub = if (e.kind == CareKind.FEED) listOfNotNull("수유 중", e.feedSide?.let(::sideName)).joinToString(" · ") else "자는 중"
    BoxWithConstraints(Modifier.fillMaxWidth().onScreen { shown = it }, contentAlignment = Alignment.Center) {
        val d = minOf(300.dp, maxWidth)
        Box(Modifier.sharedRun("run-${e.id}").size(d), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                val r = size.minDimension / 2 - 10.dp.toPx()
                val tl = Offset(center.x - r, center.y - r)
                drawCircle(p.tonal, r, style = Stroke(stroke)) // 고리 바탕: bg 위라 tonal(라이트 surfaceRaised는 bg와 같은 색)
                drawArc(color, -90f, 360f * ((elapsed / 1000 % 3600) / 3600f), false, tl, Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                rotate(360f * sweep) { drawCircle(color, 6.dp.toPx(), Offset(center.x, center.y - r)) }
            }
            // 큰 글자(200%)에서도 고리 안에 들게 숫자 크기를 고리 폭의 78%로 줄인다(R13). 평소 크기에서는 그대로 64.
            val text = timerText(elapsed)
            val density = LocalDensity.current
            val k = with(density) { minOf(1f, d.toPx() * 0.78f / (Type.timer.fontSize.toPx() * 0.62f * text.length)) }
            val style = Type.timer.copy(fontSize = Type.timer.fontSize * k, lineHeight = Type.timer.lineHeight * k)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RollingText(text, style, p.textPrimary)
                Spacer(Modifier.height(12.dp))
                Text(sub, style = Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium), color = p.textTertiary)
            }
        }
    }
}

/** R4 겹침 경고(7장 겹침): "그래도 저장" / "고치기". */
@Composable
internal fun OverlapDialog(kind: CareKind, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(overlapText(kind), style = Type.body) },
        confirmButton = { TextButton(onClick = onSave) { Text("그래도 저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("고치기") } },
        shape = RoundedCornerShape(24.dp),
    )
}

/** 젖병 양(timer.amount · sheet.row.amount): "양" + "{n}ml", ±10ml, 0~400. 빈 값에서 +는 10ml(U39). */
@Composable
internal fun AmountRow(ml: Int?, onChange: (Int?) -> Unit) {
    val p = Tokens.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.textPrimary.copy(alpha = 0.04f)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("양", Modifier.weight(1f), style = Type.body.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium), color = p.textPrimary)
        StepButton(R.drawable.ic_minus, "빼기", enabled = ml != null) { onChange(ml?.let { (it - 10).coerceAtLeast(0) }) }
        Text(ml?.let { "${it}ml" } ?: "", Modifier.widthIn(min = 72.dp), style = Type.headline.copy(fontFeatureSettings = "tnum"), color = p.textPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton(R.drawable.ic_plus, "더하기") { onChange(((ml ?: 0) + 10).coerceAtMost(400)) }
    }
}

@Composable
private fun StepButton(@DrawableRes icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Tokens.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(p.surface).pressable(enabled = enabled, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { AIcon(icon, Modifier.size(20.dp), if (enabled) p.textPrimary else p.textTertiary) }
}

/** 시·분 고르기(플랫폼다움: Material 시각 선택기, 토큰 색). 확인 버튼은 시스템 글자를 쓴다. */
@Composable
internal fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(state.hour, state.minute) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        text = { TimePicker(state) },
        shape = RoundedCornerShape(28.dp),
    )
}
