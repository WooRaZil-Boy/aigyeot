package kr.aigyeot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kr.aigyeot.app.R
import kr.aigyeot.app.store.db.ChildProfileEntity
import kr.aigyeot.app.store.db.consentChecked
import kr.aigyeot.app.ui.car3d.CarView
import kr.aigyeot.app.ui.car3d.Light3d
import kr.aigyeot.app.ui.car3d.Seat3d
import kr.aigyeot.app.ui.car3d.Stage3d
import kr.aigyeot.app.ui.car3d.View3d
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import java.time.YearMonth
import kotlin.math.roundToInt

/** 이 폭보다 좁으면 분할 840 배치(시안 .narrow): 한 줄로 쌓는다. 단독 창은 1706. */
internal val NarrowPage = 1200.dp

/** 카시트 자리(명세서 11장 순서: 뒷좌석 왼쪽·가운데·뒷좌석 오른쪽) → 3D 좌석. */
internal val SEATS = listOf(CarSeat.LEFT to "뒷좌석 왼쪽", CarSeat.CENTER to "뒷좌석 가운데", CarSeat.RIGHT to "뒷좌석 오른쪽")

/** 누른 점 [t]에서 가장 가까운 자리 앵커(반지름 [r] 안). 없으면 null. */
internal fun nearestSeat(pts: Map<Seat3d, Offset>, t: Offset, r: Float): Seat3d? =
    pts.minByOrNull { (it.value - t).getDistance() }?.takeIf { (it.value - t).getDistance() <= r }?.key

internal fun seat3d(seat: CarSeat): Seat3d = when (seat) { CarSeat.LEFT -> Seat3d.RL; CarSeat.CENTER -> Seat3d.RC; CarSeat.RIGHT -> Seat3d.RR }

/** 저장된 자리 이름(ChildProfileEntity.carseat) → 자리. 모르는 값이면 기본 오른쪽(첫 설정 기본값과 같다). */
internal fun carSeatOf(name: String?): CarSeat = CarSeat.entries.firstOrNull { it.name == name } ?: CarSeat.RIGHT

/**
 * 첫 설정·아기 설정(명세서 11장·C387, 시안 setup.html 안 B): 왼쪽은 위에서 본 3D 차 + 3D 좌석 위 자리 단추 + '카시트 자리', 오른쪽은 양식.
 * 보호자 동의(요약 두 문장 + '자세히 보기' 창), 별명, 태어난 해·달과 평소 수유 간격(값 줄 → 고르기 창: Pleos System Popup + Spinner 바퀴), 형제, 음원,
 * 앞에서도 작게 틀기(D12, 뒷좌석 출력이 없을 때만). 휴대폰 연결은 홈 머리의 '휴대폰 연결'에서 한다.
 * 창은 한 번에 하나([Pick]), 굴리는 값은 '고르기'에서만 양식에 들어간다(저장은 '저장하기'). 양식 값은 rememberSaveable(AppRoot의 화면 상태 보관)이라
 * 주행 전환·화면 재생성 뒤에도 남지만, 창과 굴리던 값은 저장하지 않는다(remember, 시안 v8d-10 ⑨): 주행이 되면 AppRoot가 주행 중 화면만 그려 이 화면이 빠지므로,
 * 정차로 돌아오거나 다크 전환 등으로 화면이 다시 만들어지면 창은 닫혀 있고 확정하지 않은 값은 버려진다(정차 전용).
 * 첫 설정이 아니면(뒤로 있음) 아래에 '우리 노래'·'시연·측정'(debug 빌드만)으로 가는 행과 '모든 기록 지우기'(명세서 13장·C40, 확인 창 뒤)를 둔다(시안 9장 ③: 홈 메뉴에서 설정 안으로).
 */
@Composable
fun SetupScreen(
    old: ChildProfileEntity?, now: () -> Long, noRearOutput: Boolean, onBack: (() -> Unit)?,
    onSongs: (() -> Unit)? = null, onDemo: (() -> Unit)? = null, onClear: (() -> Unit)? = null, clearEnabled: Boolean = true, onSave: (ChildProfileEntity) -> Unit,
) = Column(Modifier.fillMaxSize()) {
    val ym = birthMonthOf(old?.birthYm, now())
    var consent by rememberSaveable { mutableStateOf(old.consentChecked()) }
    var nickname by rememberSaveable { mutableStateOf(old?.nickname ?: "") }
    var year by rememberSaveable { mutableStateOf(ym.year) }
    var month by rememberSaveable { mutableStateOf(ym.monthValue) }
    var seat by rememberSaveable { mutableStateOf(carSeatOf(old?.carseat)) }
    var interval by rememberSaveable { mutableStateOf(old?.feedingIntervalMin) }
    var sibling by rememberSaveable { mutableStateOf(old?.hasSibling ?: false) }
    var song by rememberSaveable { mutableStateOf(old?.methods?.any { it.kind == MethodKind.SONG } ?: true) }
    var front by rememberSaveable { mutableStateOf(old?.frontFallbackAllowed ?: false) }
    // 창과 창에서 굴리는 값(열 때 양식 값에서 시작, '고르기'에서만 양식으로)은 저장하지 않는다: 주행 전환·재생성 뒤에는 닫힌 채(시안 v8d-10 ⑨)
    var pick by remember { mutableStateOf<Pick?>(null) }
    var dYear by remember { mutableStateOf(year) }
    var dMonth by remember { mutableStateOf(month) }
    var dInterval by remember { mutableStateOf(interval) }
    val p = LocalPalette.current

    val form: @Composable () -> Unit = {
        Consent(consent, onToggle = { consent = it }, onMore = { pick = Pick.CONSENT })
        FieldLabel("아기 별명")
        val fieldShape = RoundedCornerShape(CarRadius.m)
        BasicTextField(
            nickname, { nickname = it.take(20) }, singleLine = true,
            textStyle = CarType.titleS.copy(fontWeight = FontWeight(400), lineHeight = CarType.titleS.fontSize, color = p.textPrimary),   // 시안 .field 400 32px/1
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).clip(fieldShape).background(p.surface).border(3.dp, p.line, fieldShape)
                .semantics { contentDescription = "아기 별명" },
            decorationBox = { inner -> Box(Modifier.padding(horizontal = 30.dp), Alignment.CenterStart) { inner() } },
        )
        // 값 줄 둘(C387): 누르면 그 줄의 고르기 창. 도움말은 카드 바로 아래 한 줄(없으면 두지 않는다)
        val birth = YearMonth.of(year, month)
        RowsCard(Modifier.padding(top = 24.dp)) {
            ValueRow("태어난 해·달", ageText(birth, now()), birthMonthText(birth)) { dYear = year; dMonth = month; pick = Pick.BIRTH }
            Spacer(Modifier.height(6.dp))
            ValueRow("평소 수유 간격", null, intervalText(interval)) { dInterval = interval; pick = Pick.INTERVAL }
        }
        setupHelp(birth, interval, now())?.let { Text(it, style = CarType.label.copy(lineHeight = 32.sp), color = p.textTertiary, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp)) }
        RowsCard(Modifier.padding(top = 24.dp)) {
            // 명세서 11장·C385: 형제 여부는 뒷창 잠금에만 쓰는 값이라 스위치 이름을 하는 일로
            SwitchRow("뒷창 잠그기", "형제·자매가 함께 탈 때 켜요", sibling, R.drawable.ic_users) { sibling = it }
            SwitchRow("우리 노래도 틀기", "백색소음·뱃속 소리·자장가는 늘 틀어요", song, R.drawable.ic_song) { song = it }
            if (noRearOutput) SwitchRow("앞에서도 작게 틀기", "뒷좌석 스피커가 없는 차예요", front, R.drawable.ic_speaker) { front = it }
        }
        CarButton("저장하기", Modifier.fillMaxWidth().padding(top = 28.dp), BtnKind.BRAND, enabled = canSave(consent, nickname)) {
            onSave(profileEntity(old, nickname, YearMonth.of(year, month), seat, interval, sibling, song, front, noRearOutput, now()))
        }
        if (onSongs != null || onDemo != null || onClear != null) RowsCard(Modifier.padding(top = 28.dp)) {
            val chev: @Composable () -> Unit = { LineIcon(R.drawable.ic_chevron_right, 36.dp, p.textTertiary) }
            if (onSongs != null) ListRow("우리 노래", icon = R.drawable.ic_song, onClick = onSongs, trailing = chev)
            if (onDemo != null) ListRow("시연·측정", icon = R.drawable.ic_activity, onClick = onDemo, trailing = chev)
            if (onClear != null) ListRow("모든 기록 지우기", if (clearEnabled) "프로필·연결·노래는 남겨요" else "장면이 끝나면 지울 수 있어요", R.drawable.ic_trash_2,
                enabled = clearEnabled, onClick = { pick = Pick.CLEAR })   // 장면 중에는 호스트가 지우기를 받지 않는다(debug 장면만)
        }
    }

    Header("아기 설정", onBack)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < NarrowPage
        if (narrow) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 44.dp, end = 44.dp, top = 32.dp, bottom = 40.dp)) {
            SeatPick(seat, narrow = true) { seat = it }
            Spacer(Modifier.height(40.dp))
            form()
        } else Row(Modifier.fillMaxSize().padding(start = 44.dp, end = 44.dp, top = 32.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(600.dp)) { SeatPick(seat, narrow = false) { seat = it } }   // 시안 sticky: 양식만 스크롤
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) { form() }
        }
    }
    when (pick) {
        Pick.BIRTH -> Popup("태어난 해·달", ageText(YearMonth.of(dYear, dMonth), now()), "고르기", "취소",
            onConfirm = { year = dYear; month = dMonth; pick = null }, onDismiss = { pick = null }) {
            WheelPanel {
                val t = now()
                val years = birthYears(t, saved = year)   // 양식의 이른 해(저장값)는 목록에 더해 지킨다
                Wheel(years, years.indexOf(dYear), "태어난 해", { "${it}년" }) { i -> clampBirth(years[i], dMonth, t).let { dYear = it.year; dMonth = it.monthValue } }
                val months = birthMonths(dYear, t)
                Wheel(months, months.indexOf(dMonth), "태어난 달", { "${it}월" }) { i -> dMonth = months[i] }
            }
        }
        Pick.INTERVAL -> Popup("평소 수유 간격", setupHelp(YearMonth.of(year, month), dInterval, now()), "고르기", "취소",
            onConfirm = { interval = dInterval; pick = null }, onDismiss = { pick = null }) {
            WheelPanel {
                val list = intervals(interval)   // 목록 밖 저장값(30분 단위가 아님)은 크기 순 자리에 더해 지킨다
                Wheel(list, list.indexOf(dInterval), "평소 수유 간격", ::intervalText) { i -> dInterval = list[i] }
            }
        }
        Pick.CONSENT -> Popup(CONSENT_DETAIL_TITLE, CONSENT_DETAIL, "닫기", null, onConfirm = { pick = null }, onDismiss = { pick = null })
        Pick.CLEAR -> Popup("모든 기록을 지울까요?", CLEAR_DETAIL, "지우기", "닫기", onConfirm = { pick = null; onClear?.invoke() }, onDismiss = { pick = null })   // 장면은 시연 화면에서만 시작해 창이 열린 채 장면이 되지 않는다
        null -> {}
    }
}

/** 아기 설정 창(한 번에 하나): 태어난 해·달, 평소 수유 간격, 동의 '자세히 보기'. */
internal enum class Pick { BIRTH, INTERVAL, CONSENT, CLEAR }

/** '모든 기록 지우기' 확인 창 본문: 지우는 범위(명세서 13장 C40, 중계 보관분 L36), 휴대폰에 이미 온 기록은 남음(contracts-link 오류 표), 되돌릴 수 없음. */
internal const val CLEAR_DETAIL = "주행 기록과 달래기 성적을 모두 지우고, 중계 서버에 남은 기록도 지워요. 이미 휴대폰에 온 리포트·알림은 남으니 휴대폰에서 지워 주세요. 지운 기록은 되돌릴 수 없어요."

/**
 * 동의 블록(C387, 시안 su-consent): 카드 하나. 윗부분은 체크 하나(Role.Checkbox, 체크 48 + 굵은 줄 30/38·700 + 요약 두 문장 24/32 흐림)이고,
 * 그 아래 글자 단추 '자세히 보기 ›'(높이 80, 600 26, 꺾쇠 28)는 체크를 바꾸지 않는 따로인 단추다(읽기 이름 'AI 서버로 가는 정보 자세히 보기').
 */
@Composable
private fun Consent(on: Boolean, onToggle: (Boolean) -> Unit, onMore: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(CarRadius.l)).background(p.surface).padding(horizontal = 32.dp, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth().toggleable(on, null, null, role = Role.Checkbox, onValueChange = onToggle), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(Modifier.offset(y = (-4).dp)) { CheckBox(on) }   // 체크 칸 가운데를 굵은 줄 첫 줄(38) 가운데에
            Column(Modifier.weight(1f)) {
                Text(CONSENT_TITLE, style = CarType.bodyL.copy(fontWeight = FontWeight(700), lineHeight = 38.sp), color = p.textPrimary)
                Text(CONSENT_SUMMARY, style = CarType.label.copy(lineHeight = 32.sp), color = p.textSecondary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Row(
            Modifier.padding(start = 70.dp).heightIn(min = 80.dp)   // 글 열에 맞춤(체크 48 + 사이 22)
                .press(label = "AI 서버로 가는 정보 자세히 보기", shape = RoundedCornerShape(CarRadius.s), pressedBg = p.ink(8), onClick = onMore)
                .semantics { contentDescription = "AI 서버로 가는 정보 자세히 보기" }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("자세히 보기", style = CarType.label.copy(fontSize = 26.sp, fontWeight = FontWeight(600)), color = p.textSecondary)
            LineIcon(R.drawable.ic_chevron_right, 28.dp, p.textSecondary)
        }
    }
}

/** 값 줄(C387, 시안 su-row): 제목(+부제) 왼쪽, 값 600 30/38 한 줄 + 꺾쇠 36 오른쪽. 줄 전체가 단추이고 읽기는 보이는 글을 합친다. */
@Composable
private fun ValueRow(title: String, sub: String?, value: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    ListRow(title, sub, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value, style = CarType.bodyL.copy(fontWeight = FontWeight(600), lineHeight = 38.sp), color = p.textPrimary, maxLines = 1)
            LineIcon(R.drawable.ic_chevron_right, 36.dp, p.textTertiary)
        }
    }
}

/** 위에서 본 3D 차 + 자리 단추(3D 좌석 앵커에, 그릴 때마다 다시 잼) + '카시트 자리' 세그먼트. 분할 840이면 자리 단추를 숨긴다(시안 .narrow). */
@Composable
private fun SeatPick(seat: CarSeat, narrow: Boolean, onSeat: (CarSeat) -> Unit) {
    val p = LocalPalette.current
    var pts by remember { mutableStateOf<Map<Seat3d, Offset>>(emptyMap()) }   // 같은 값이면 다시 그리지 않는다(구조 비교)
    val glow = lerp(p.stage, Color.White, 0.55f)
    Box(
        Modifier.fillMaxWidth().aspectRatio(if (narrow) 1.3f else 1200f / 1400f).clip(RoundedCornerShape(48.dp))
            .drawBehind { drawRect(Brush.radialGradient(listOf(glow, p.stage), center, size.width * 0.7f)) },
    ) {
        Car3dStill(
            Stage3d(view = View3d.TOP, light = Light3d.LISTEN, seat = seat3d(seat)), Modifier.fillMaxSize(),
            onRender = if (narrow) null else { v -> pts = Seat3d.entries.mapNotNull { s -> v.project(s.anchor)?.let { s to Offset(it[0], it[1]) } }.toMap() },
        )
        if (!narrow) {
            SEATS.forEach { (c, label) ->
                pts[seat3d(c)]?.let { o -> SeatHit(c == seat, label, Modifier.offset { IntOffset(o.x.roundToInt() - 48.dp.roundToPx(), o.y.roundToInt() - 48.dp.roundToPx()) }) { onSeat(c) } }
            }
            // 누름은 가장 가까운 자리로: 위에서 본 차의 자리 간격(약 52)이 원 96보다 좁아, 원마다 누름을 받으면 나중 원이 앞 원을 가린다
            Box(Modifier.matchParentSize().pointerInput(pts) {
                detectTapGestures { t -> nearestSeat(pts, t, 48.dp.toPx())?.let { s -> SEATS.firstOrNull { seat3d(it.first) == s }?.let { onSeat(it.first) } } }
            })
        }
    }
    FieldLabel("카시트 자리")
    Segmented(SEATS, seat, onSelect = onSeat)
}

/** 자리 단추(시안 .seat-hit: 96 원, 꺼짐 점선 테 브랜드 60%, 켜짐 실선 4 + 바깥 고리 10). */
@Composable
private fun SeatHit(on: Boolean, label: String, modifier: Modifier, onClick: () -> Unit) {
    val a = LocalPalette.current.accent
    Box(
        // 손가락 누름은 SeatPick이 가장 가까운 자리로 고른다(겹침). 여기는 접근성 동작만(읽어 주기·두 번 눌러 고르기)
        modifier.size(96.dp).semantics { contentDescription = label; selected = on; role = Role.RadioButton; onClick(label) { onClick(); true } }
            .drawBehind {
                val r = size.minDimension / 2
                if (on) drawCircle(a.copy(alpha = 0.14f), r + 10.dp.toPx())
                drawCircle(a.copy(alpha = if (on) 0.16f else 0.08f), r)
                val w = (if (on) 4 else 3).dp.toPx()
                drawCircle(if (on) a else a.copy(alpha = 0.6f), r - w / 2, style = Stroke(w, pathEffect = if (on) null else PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))))
            },
    )
}

/** 행 묶음 판(시안 .card.rows: 흰 면, 안 여백 8). */
@Composable
private fun RowsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(CarRadius.l)).background(LocalPalette.current.surface).padding(8.dp)) { content() }

/**
 * 정차 화면의 3D 정지 그림 한 칸(설정·연결·리포트가 같이 쓴다). 상태가 바뀔 때만 한 장 그린다(손가락 회전 없음, animate=false).
 * 첫 그림 전에는 칸 가운데에 준비 표시([StageMarkLayer], 800ms 넘게 걸릴 때만). [onRender]는 그릴 때마다 불린다(앵커 화면 좌표). 화면을 떠나면 [CarView.destroy].
 */
@Composable
internal fun Car3dStill(stage: Stage3d, modifier: Modifier = Modifier, onRender: ((CarView) -> Unit)? = null) {
    val dark = LocalPalette.current.isDark
    val load = remember { StageLoad() }
    Box(modifier) {
        AndroidView(
            factory = { CarView(it, interactive = false).also { v -> v.onFirstFrame = { load.firstFrame(v, hide = true) }; v.onFailed = load::failed } },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                v.dark = dark
                v.onRender = onRender?.let { f -> { f(v) } }
                stage.copy(reducedMotion = true).let { s -> if (v.state != s) v.set(s, animate = false) }   // 정지 그림: 효과 움직임 없음
            },
            onRelease = { it.destroy() },
        )
        StageMarkLayer(load, reduced = reducedMotion(), drive = false)   // 정차 화면에서만 열린다
    }
}
