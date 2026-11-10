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
internal val SEATS = listOf(CarSeat.LEFT to "뒷좌석 왼쪽", CarSeat.CENTER to "가운데", CarSeat.RIGHT to "뒷좌석 오른쪽")

/** 누른 점 [t]에서 가장 가까운 자리 앵커(반지름 [r] 안). 없으면 null. */
internal fun nearestSeat(pts: Map<Seat3d, Offset>, t: Offset, r: Float): Seat3d? =
    pts.minByOrNull { (it.value - t).getDistance() }?.takeIf { (it.value - t).getDistance() <= r }?.key

internal fun seat3d(seat: CarSeat): Seat3d = when (seat) { CarSeat.LEFT -> Seat3d.RL; CarSeat.CENTER -> Seat3d.RC; CarSeat.RIGHT -> Seat3d.RR }

/** 저장된 자리 이름(ChildProfileEntity.carseat) → 자리. 모르는 값이면 기본 오른쪽(첫 설정 기본값과 같다). */
internal fun carSeatOf(name: String?): CarSeat = CarSeat.entries.firstOrNull { it.name == name } ?: CarSeat.RIGHT

/**
 * 첫 설정·아기 설정(명세서 11장, 시안 setup()): 왼쪽은 위에서 본 3D 차 + 3D 좌석 위 자리 단추 + '카시트 자리', 오른쪽은 양식.
 * 보호자 동의, 별명·생년월·카시트 자리, 수유 간격, 형제, 음원, 앞에서도 작게 틀기(D12, 뒷좌석 출력이 없을 때만). 휴대폰 연결은 홈 머리의 '휴대폰 연결'에서 한다.
 * 첫 설정이 아니면(뒤로 있음) 아래에 '부모 노래'·'시연·측정'으로 가는 행을 둔다(시안 9장 ③: 홈 메뉴에서 설정 안으로).
 */
@Composable
fun SetupScreen(
    old: ChildProfileEntity?, now: () -> Long, noRearOutput: Boolean, onBack: (() -> Unit)?,
    onSongs: (() -> Unit)? = null, onDemo: (() -> Unit)? = null, onSave: (ChildProfileEntity) -> Unit,
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
    val p = LocalPalette.current

    val form: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(CarRadius.l)).background(p.surface)
                .toggleable(consent, null, null, role = Role.Checkbox) { consent = it }.padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            CheckBox(consent)
            Column(Modifier.weight(1f)) {
                Text("아기의 보호자(법정대리인)로서 아이곁 사용에 동의해요", style = CarType.bodyL.copy(fontWeight = FontWeight(700), lineHeight = 38.sp), color = p.textPrimary)
                Text(   // C119·C120
                    "질문하면 질문 글과 지금 상태 숫자(울음·수유·잠·기저귀 기록 요약, 카시트 시간, 실내 온도, 소리별 성적, 다음 쉼터 이름·거리)가 아이곁 중계 서버를 거쳐 AI 서버로 가요. 울음 소리와 차 위치 좌표는 보내지 않아요.",
                    style = CarType.label.copy(lineHeight = 32.sp), color = p.textSecondary, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        FieldLabel("아기 별명")
        val fieldShape = RoundedCornerShape(24.dp)
        BasicTextField(
            nickname, { nickname = it.take(20) }, singleLine = true,
            textStyle = TextStyle(fontFamily = AppFont, fontSize = 32.sp, fontWeight = FontWeight(400), color = p.textPrimary),
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).clip(fieldShape).background(p.surface).border(3.dp, p.line, fieldShape)
                .semantics { contentDescription = "아기 별명" },
            decorationBox = { inner -> Box(Modifier.padding(horizontal = 30.dp), Alignment.CenterStart) { inner() } },
        )
        // 양식이 좁으면(628~840 창) 스테퍼를 한 줄에 하나씩: 값 칸이 60dp로 줄어 글자가 잘리지 않게
        BoxWithConstraints { val stack = maxWidth < 760.dp
        val pair: @Composable (@Composable (Modifier) -> Unit, @Composable (Modifier) -> Unit) -> Unit = { a, b ->
            if (stack) Column { a(Modifier.fillMaxWidth()); b(Modifier.fillMaxWidth()) } else Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { a(Modifier.weight(1f)); b(Modifier.weight(1f)) }
        }
        pair({ m -> Column(m) {
                FieldLabel("태어난 해·달")
                val birth = YearMonth.of(year, month)
                val next = nextBirthMonth(birth, now())
                Stepper(fit = true, text = birthMonthText(birth), minusEnabled = true, plusEnabled = next != null,
                    onMinus = { birth.minusMonths(1).let { year = it.year; month = it.monthValue } },
                    onPlus = { next?.let { year = it.year; month = it.monthValue } })
            } }, { m -> Column(m) {
                FieldLabel("평소 수유 간격")
                Stepper(fit = true, text = intervalStepText(interval), minusEnabled = interval != null, plusEnabled = (interval ?: 0) < INTERVAL_MAX_MIN,
                    onMinus = { interval = intervalDown(interval) },
                    onPlus = { interval = intervalUp(interval) })
            } })
        }
        ageNote(YearMonth.of(year, month), now())?.let { Note(it) }
        RowsCard(Modifier.padding(top = 24.dp)) {
            SwitchRow("형제·자매가 함께 타요", null, sibling, R.drawable.ic_users) { sibling = it }
            SwitchRow("휴대폰으로 받은 부모 노래도 써요", "음원: 백색소음·뱃속 소리·자장가는 늘 써요", song, R.drawable.ic_song) { song = it }
            if (noRearOutput) SwitchRow("뒷좌석 스피커가 없어 앞에서도 작게 틀어요", null, front, R.drawable.ic_speaker) { front = it }
        }
        CarButton("저장", Modifier.fillMaxWidth().padding(top = 28.dp), BtnKind.BRAND, enabled = canSave(consent, nickname)) {
            onSave(profileEntity(old, nickname, YearMonth.of(year, month), seat, interval, sibling, song, front, noRearOutput, now()))
        }
        if (onSongs != null || onDemo != null) RowsCard(Modifier.padding(top = 28.dp)) {
            val chev: @Composable () -> Unit = { LineIcon(R.drawable.ic_chevron_right, 36.dp, p.textTertiary) }
            if (onSongs != null) ListRow("부모 노래", icon = R.drawable.ic_song, onClick = onSongs, trailing = chev)
            if (onDemo != null) ListRow("시연·측정", icon = R.drawable.ic_activity, onClick = onDemo, trailing = chev)
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
 * 정차 화면의 3D 정지 그림 한 칸(설정·연결이 같이 쓴다). 상태가 바뀔 때만 한 장 그린다(손가락 회전 없음, animate=false).
 * [onRender]는 그릴 때마다 불린다(앵커 화면 좌표). 화면을 떠나면 [CarView.destroy].
 */
@Composable
internal fun Car3dStill(stage: Stage3d, modifier: Modifier = Modifier, onRender: ((CarView) -> Unit)? = null) {
    val dark = LocalPalette.current.isDark
    AndroidView(
        factory = { CarView(it, interactive = false) },
        modifier = modifier,
        update = { v ->
            v.dark = dark
            v.onRender = onRender?.let { f -> { f(v) } }
            stage.copy(reducedMotion = true).let { s -> if (v.state != s) v.set(s, animate = false) }   // 정지 그림: 효과 움직임 없음
        },
        onRelease = { it.destroy() },
    )
}
