package kr.aigyeot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.aigyeot.app.store.db.AI_NOTICE_VERSION
import kr.aigyeot.app.store.db.ChildProfileEntity
import kr.aigyeot.app.store.db.consentChecked
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 첫 설정(명세서 11장): 보호자 동의, 별명·생년월·카시트 자리, 수유 간격, 형제, 음원, 앞에서도 작게 틀기(D12, 뒷좌석 출력이 없을 때만).
 * 휴대폰 연결은 홈의 '휴대폰 연결'에서 한다.
 */
@Composable
fun SetupScreen(old: ChildProfileEntity?, now: () -> Long, noRearOutput: Boolean, onBack: (() -> Unit)?, onSave: (ChildProfileEntity) -> Unit) =
    Page("아기 설정", onBack) {
        val ym = old?.birthYm?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.now()
        var consent by rememberSaveable { mutableStateOf(old.consentChecked()) }
        var nickname by rememberSaveable { mutableStateOf(old?.nickname ?: "") }
        var year by rememberSaveable { mutableStateOf(ym.year) }
        var month by rememberSaveable { mutableStateOf(ym.monthValue) }
        var seat by rememberSaveable { mutableStateOf(old?.carseat ?: CarSeat.RIGHT.name) }
        var interval by rememberSaveable { mutableStateOf(old?.feedingIntervalMin) }
        var sibling by rememberSaveable { mutableStateOf(old?.hasSibling ?: false) }
        var song by rememberSaveable { mutableStateOf(old?.methods?.any { it.kind == MethodKind.SONG } ?: true) }
        var front by rememberSaveable { mutableStateOf(old?.frontFallbackAllowed ?: false) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(consent, { consent = it }, Modifier.size(Touch))
            Body("아기의 보호자(법정대리인)로서 아이곁 사용에 동의해요")
        }
        Body("질문하면 질문 글과 지금 상태 숫자(울음·수유·잠·기저귀 기록 요약, 카시트 시간, 실내 온도, 소리별 성적, 다음 쉼터 이름·거리)가 아이곁 중계 서버를 거쳐 AI 서버로 가요. 울음 소리와 차 위치 좌표는 보내지 않아요.")   // C119·C120
        OutlinedTextField(nickname, { nickname = it.take(20) }, label = { Text("아기 별명") }, singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall)
        Label("태어난 해·달")
        Stepper("${year}년 ${month}월", {
            if (month == 1) { year--; month = 12 } else month--
        }, {
            val next = YearMonth.of(year, month).plusMonths(1)
            if (!next.isAfter(YearMonth.now())) { year = next.year; month = next.monthValue }
        })
        Label("카시트 자리")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(CarSeat.LEFT to "뒷좌석 왼쪽", CarSeat.CENTER to "가운데", CarSeat.RIGHT to "뒷좌석 오른쪽").forEach { (c, t) ->
                Box(Modifier.weight(1f)) { BigButton(t, primary = seat == c.name) { seat = c.name } }
            }
        }
        Label("평소 수유 간격")
        Stepper(interval?.let { "${it / 60}시간 ${it % 60}분" } ?: "비움(수유 시계 꺼짐)", {
            interval = interval?.let { if (it <= 30) null else it - 30 }
        }, { interval = ((interval ?: 0) + 30).coerceAtMost(600) })
        // 앱이 간격을 정하지는 않는다. 0~1개월이면 참고 범위(명세서 10장)를, 6개월 이상이면 비워 둘 수 있다는 것만 알린다
        val age = ChronoUnit.MONTHS.between(YearMonth.of(year, month), YearMonth.now())
        if (age <= 1) Body("참고: 신생아 모유는 보통 2시간, 첫 몇 주는 2~4시간, 분유는 첫 달 말 3~4시간이에요")
        else if (age >= 6) Body("비워 두면 수유 시계가 꺼져요")
        Toggle("형제·자매가 함께 타요", sibling) { sibling = it }
        Label("음원: 백색소음·뱃속 소리·자장가는 늘 써요")
        Toggle("휴대폰으로 받은 부모 노래도 써요", song) { song = it }
        if (noRearOutput) Toggle("뒷좌석 스피커가 없어 앞에서도 작게 틀어요", front) { front = it }
        BigButton("저장", enabled = consent && nickname.isNotBlank()) {
            onSave(ChildProfileEntity(
                id = old?.id ?: "child", nickname = nickname.trim(), birthYm = YearMonth.of(year, month).toString(), carseat = seat,
                feedingIntervalMin = interval, lastFeedingStart = old?.lastFeedingStart,
                methods = listOfNotNull(Method(MethodKind.WHITE_NOISE), Method(MethodKind.WOMB), Method(MethodKind.LULLABY), if (song) Method(MethodKind.SONG) else null),
                hasSibling = sibling, frontFallbackAllowed = noRearOutput && front, consentAt = old?.consentAt ?: now(), aiNoticeVersion = AI_NOTICE_VERSION,
            ))
        }
    }

@Composable
private fun Stepper(text: String, minus: () -> Unit, plus: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f)) { BigButton("−", primary = false, onClick = minus) }
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(2f))
        Box(Modifier.weight(1f)) { BigButton("+", primary = false, onClick = plus) }
    }

@Composable
private fun Toggle(text: String, on: Boolean, set: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f)) { Body(text) }
        Switch(on, set, Modifier.heightIn(min = Touch))
    }
