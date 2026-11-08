package kr.aigyeot.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.aigyeot.app.store.db.SessionEntity
import kr.aigyeot.shared.CarActionKind
import kr.aigyeot.shared.EpisodeResult
import kr.aigyeot.shared.ExcludedReason
import kr.aigyeot.shared.HandoffKind
import kr.aigyeot.shared.OutputRoute
import kr.aigyeot.shared.PlayPurpose
import kr.aigyeot.shared.SecondAction
import kr.aigyeot.shared.SecondAttempt
import kr.aigyeot.shared.SoundMethod
import kr.aigyeot.shared.StopInfo
import kr.aigyeot.shared.StopReason
import kr.aigyeot.shared.ReportSummaryBody

/** 리포트 목록(읽기만). 열린 세션은 '주행 중'으로 보인다. */
@Composable
fun ReportListScreen(sessions: List<SessionEntity>, onOpen: (String) -> Unit, onBack: () -> Unit) = Page("주행 리포트", onBack) {
    if (sessions.isEmpty()) Body("아직 기록이 없어요")
    sessions.forEach { s ->
        BigButton(
            hm(s.startedAt) + (s.endedAt?.let { " ~ ${hm(it)}" } ?: " · 주행 중") + if (s.scenarioId != null) " · 시뮬레이션" else "",
            enabled = s.endedAt != null, primary = false,
        ) { onOpen(s.id) }
    }
}

/** 리포트 상세. 휴대폰에 보내는 report_summary와 같은 body를 같은 순서로 그린다(명세서 11장 다섯 항목 + 방법별 성적). */
@Composable
fun ReportDetailScreen(id: String, build: (String) -> ReportSummaryBody?, onBack: () -> Unit) = Page("리포트", onBack) {
    var r by remember(id) { mutableStateOf<ReportSummaryBody?>(null) }
    LaunchedEffect(id) { r = withContext(Dispatchers.IO) { build(id) } }
    val b = r ?: return@Page Body("불러오는 중이에요")
    if (b.simulated) Body("시뮬레이션 입력으로 만든 기록이에요")
    Body("${b.startedAt.substring(11, 16)} ~ ${b.endedAt.substring(11, 16)}")
    when (b.outputRoute) {
        OutputRoute.REAR_ZONE -> Body("소리는 뒷좌석으로 나갔어요")
        OutputRoute.DEFAULT_ZONE_FALLBACK -> Body("뒷좌석 출력이 없어 앞에서도 들렸어요")
        OutputRoute.NO_PLAYBACK -> Unit   // 재생이 없었으면 출력 경로를 보이지 않는다
    }
    if (b.truncated == true) Body("오래된 에피소드는 줄였어요")
    Label("1. 에피소드 타임라인")
    if (b.timeline.isEmpty()) Body("기록된 에피소드가 없어요")   // 통화 중 경보처럼 에피소드 없이 기록만 한 경보도 있다
    b.timeline.forEach { e ->
        Section {
            Body("${e.startedAt.substring(11, 19)} " + if (e.trigger.name == "CRY_ALARM") "울음 감지${e.alarmLatencyS?.let { " (지연 ${"%.1f".format(it)}초)" } ?: ""}" else "소리 바꾸기")
            Body("${e.sound?.label ?: "재생 없음"} · ${e.playS}초" + (e.plays.size.takeIf { it > 1 }?.let { " · 재생 ${it}번" } ?: ""))
            e.plays.forEach { Body("· ${purpose(it.purpose)} ${method(it.method)} ${it.startedAt.substring(11, 19)}~${it.endedAt.substring(11, 19)} ${it.playS}초") }
            Body("결과: " + result(e.result) + (e.excludedReason?.let { " · 성적에서 뺌(${excluded(it)})" } ?: ""))
            if (e.recried) Body("다시 울었어요")
            e.second?.let { Body("2차: " + secondText(it)) }
            e.stop?.let { Body("정차: " + stopText(it)) }
            Body("울음 ${e.totalCryS}초")
        }
    }
    Label("2. 울음 직전 주행")
    if (b.preCryContext.isEmpty()) Body("기록된 맥락이 없어요")
    b.preCryContext.forEach { c ->
        Section {
            Body("평균 ${c.speedKmhAvg}km/h · 최고 ${c.speedKmhMax}km/h · 브레이크 ${c.brakePresses}번")
            Body("큰 조향 ${c.steeringReversals}번 · 방향지시등 ${if (c.turnSignalUsed) "켬" else "안 켬"} · 기어 ${c.gear.name}" + (c.cabinTempC?.let { " · 실내 ${"%.1f".format(it)}℃" } ?: "") + if (c.playing) " · 재생 중" else "")
        }
    }
    Label("3. 평소 대비 조향")
    with(b.steering) {
        Body(if (!available) (if (unavailableReason?.name == "NO_CRY") "울음이 없어 비교하지 않았어요" else "비교할 구간이 없어요")
             else "평소 분당 ${"%.1f".format(baselinePerMin)}번 · 울음 중 ${"%.1f".format(duringCryPerMin)}번" + (afterResponsePerMin?.let { " · 대응 뒤 ${"%.1f".format(it)}번" } ?: ""))
    }
    Label("4. 차가 대신 한 일")
    if (b.carActions.isEmpty()) Body("없어요")
    b.carActions.forEach { Body("${actionName(it.action)} ${it.count}번" + (it.totalS?.let { s -> " · ${s}초" } ?: "")) }
    Label("5. 제어 기록")
    if (b.controls.isEmpty()) Body("없어요")
    b.controls.forEach { Body("${it.api} ${it.target}: 성공 ${it.succeeded} · 실패 ${it.failed} · 재시도 ${it.retries}") }
    Label("방법별 성적")
    b.methodStats.orEmpty().forEach { Body("${it.label}: " + if (it.attempts < 3) "아직 모름" else "${it.attempts}번 중 ${it.successes}번 그침") }
    Body("그 소리를 틀었을 때 그친 횟수예요. 소리 덕분인지는 알 수 없어요")
}

private fun method(m: SoundMethod) = when (m) { SoundMethod.WHITE_NOISE -> "백색소음"; SoundMethod.WOMB -> "뱃속 소리"; SoundMethod.LULLABY -> "자장가"; SoundMethod.PARENT_SONG -> "부모 노래"; SoundMethod.UNKNOWN -> "기타" }

private fun purpose(p: PlayPurpose) = when (p) { PlayPurpose.AUTO_FIRST -> "자동 1차"; PlayPurpose.AUTO_SECOND -> "자동 2차"; PlayPurpose.REQUEST -> "운전자 요청" }

private fun result(r: EpisodeResult?) = when (r) {
    EpisodeResult.SUCCESS -> "그쳤어요"; EpisodeResult.FAILURE -> "계속 울었어요"; EpisodeResult.UNDETERMINED -> "판정하지 못했어요"; null -> "시도 없음"
}

/** 폰 리포트의 excluded()와 같은 뜻(열거 이름을 그대로 쓰지 않는다). */
private fun excluded(e: ExcludedReason) = when (e) {
    ExcludedReason.SESSION_END -> "세션이 끝나 판정하지 못했어요"
    ExcludedReason.DRIVER_STOP -> "운전자가 멈춤"
    ExcludedReason.CALL -> "통화"
    ExcludedReason.GEAR_TO_PARK, ExcludedReason.STARTED_IN_PARK -> "정차 중"
    ExcludedReason.SWITCH_SOUND -> "소리 바꾸기 요청"
    ExcludedReason.FEEDING_WINDOW -> "수유 시간대"
    ExcludedReason.SESSION_CAP -> "세션 상한"
    ExcludedReason.MIC_ERROR -> "마이크 오류"
    ExcludedReason.DETECTOR_TIMEOUT -> "감지 시간 초과"
    ExcludedReason.INPUT_SILENCED -> "입력이 무음 처리됨"
    ExcludedReason.FOCUS_LOSS -> "소리를 이어서 틀지 못함"
    ExcludedReason.SUSPEND -> "일시 중단"
}

private fun secondText(sec: SecondAttempt): String {
    val what = when (sec.action) {
        SecondAction.COMFORT_COOL -> "실내를 시원하게 했어요"
        SecondAction.FEEDING_WINDOW -> "수유 시간대라 안내했어요"
        SecondAction.OTHER_SOUND -> "다른 소리로 바꿨어요" + (sec.sound?.let { "(${it.label})" } ?: "")
        SecondAction.FOCUS_DENIED -> "소리를 이어서 틀지 못했어요"
    }
    return what + (sec.result?.let { " · ${result(it)}" } ?: "")
}

private fun stopText(st: StopInfo) = buildString {
    append(when (st.reason) {
        StopReason.ATTEMPT_LIMIT -> "두 번 시도 뒤"
        StopReason.SESSION_CAP -> "세션 20분 상한"
        StopReason.FEEDING_WINDOW -> "수유 시간대"
        StopReason.NO_SOUND -> "뒷좌석 출력 없음"
    })
    append(" · ")
    append(when (st.mode) {
        HandoffKind.REST_OFFER -> "${st.restName ?: "쉼터"} 들르기 제안"
        HandoffKind.ARRIVAL_NOTICE -> "도착 안내"
        HandoffKind.NO_REST -> "안전한 곳에 세우기 안내"
        HandoffKind.PARKED -> "정차 중 인계"
    })
    if (st.waypointAdded) append(" · 경유지 추가")
    if (st.guardianNotified) append(" · 다른 보호자에게 알림")
}

@Composable
private fun Section(content: @Composable () -> Unit) =
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { content() } }

private fun actionName(a: CarActionKind) = when (a) {
    CarActionKind.REAR_PLAYBACK -> "뒷자리 재생"
    CarActionKind.DEFAULT_ZONE_PLAYBACK -> "앞에서도 들리게 재생"
    CarActionKind.SEAT_HEATER_OFF -> "열선 끄기"
    CarActionKind.REAR_WINDOW_LOCK -> "뒷창 잠금"
    CarActionKind.CABIN_COOL -> "실내 온도 낮추기"
    CarActionKind.READING_LIGHT_OFF -> "독서등 끄기"
    CarActionKind.WAYPOINT_ADDED -> "경유지 추가"
    CarActionKind.ARRIVAL_NOTICE -> "도착 안내"
    CarActionKind.GUARDIAN_ALERT -> "보호자 알림"
}
