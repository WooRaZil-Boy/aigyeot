package kr.aigyeot.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.aigyeot.phone.AppModel
import kr.aigyeot.phone.core.PhoneState
import kr.aigyeot.phone.core.instantOf
import kr.aigyeot.phone.core.steeringUnavailableText
import kr.aigyeot.shared.CarActionKind
import kr.aigyeot.shared.EpisodeResult
import kr.aigyeot.shared.EpisodeTrigger
import kr.aigyeot.shared.ExcludedReason
import kr.aigyeot.shared.HandoffKind
import kr.aigyeot.shared.OutputRoute
import kr.aigyeot.shared.PlayPurpose
import kr.aigyeot.shared.PreCryContext
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.SecondAction
import kr.aigyeot.shared.SecondAttempt
import kr.aigyeot.shared.SoundMethod
import kr.aigyeot.shared.SoundRef
import kr.aigyeot.shared.SteeringUnavailableReason
import kr.aigyeot.shared.StopReason

/** 리포트 목록: 새 것이 위. */
@Composable
fun ReportsScreen(model: AppModel, onBack: () -> Unit, open: (String) -> Unit) {
    val s by model.state.collectAsState()
    Page("리포트", onBack) {
        StatusBands(model)
        val list = s.reports.values.sortedByDescending { instantOf(it.body.startedAt) ?: 0 }
        if (list.isEmpty()) Muted("주행이 끝나면 여기에 리포트가 와요")
        for (r in list) ReportCard(r.body, open)
    }
}

/** 리포트 목록 한 줄(guardian 오늘의 '최근 리포트'도 같은 줄을 쓴다). */
@Composable
internal fun ReportCard(b: ReportSummaryBody, open: (String) -> Unit) {
    Card(onClick = { open(b.sessionId) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${dateTime(b.startedAt)} – ${clock(b.endedAt)}", style = MaterialTheme.typography.titleMedium)
            Muted("에피소드 ${b.timeline.size}개" + if (b.simulated) " · 시뮬레이션" else "")
        }
    }
}

private fun method(m: SoundMethod) = when (m) {
    SoundMethod.WHITE_NOISE -> "백색소음"
    SoundMethod.LULLABY -> "자장가"
    SoundMethod.PARENT_SONG -> "부모 노래"
}

private fun purpose(p: PlayPurpose) = when (p) {
    PlayPurpose.AUTO_FIRST -> "자동 1차"
    PlayPurpose.AUTO_SECOND -> "자동 2차"
    PlayPurpose.REQUEST -> "운전자 요청"
}

private fun sound(s: PhoneState, r: SoundRef?, songId: String? = null): String = when {
    r == null -> "재생 없음"
    r.method == SoundMethod.PARENT_SONG -> s.songs.find { it.songId == songId }?.name ?: r.label
    else -> r.label
}

internal fun preCryDrive(p: PreCryContext) =
    "기어 ${p.gear.name}" + (if (p.turnSignalUsed) ", 방향지시등 씀" else "") + (if (p.windowOpen == true) ", 창 열림" else "")

private fun secondText(s: PhoneState, sec: SecondAttempt): String {
    val what = when (sec.action) {
        SecondAction.COMFORT_COOL -> "실내를 시원하게 했어요"
        SecondAction.FEEDING_WINDOW -> "수유 시간대라 안내했어요"
        SecondAction.OTHER_SOUND -> "다른 소리로 바꿨어요" + (sec.sound?.let { "(${sound(s, it)})" } ?: "")
        SecondAction.FOCUS_DENIED -> "소리를 이어서 틀지 못했어요"
    }
    return what + (sec.result?.let { " · ${result(it)}" } ?: "")
}

private fun result(r: EpisodeResult?) = when (r) {
    EpisodeResult.SUCCESS -> "그침"
    EpisodeResult.FAILURE -> "이어서 울음"
    EpisodeResult.UNDETERMINED -> "판정하지 못함"
    null -> "1차 시도 없음"
}

private fun excluded(e: ExcludedReason) = when (e) {
    ExcludedReason.SESSION_END -> "세션이 끝나 판정하지 못했어요"
    ExcludedReason.DRIVER_STOP -> "운전자가 멈춤"
    ExcludedReason.CALL -> "통화"
    ExcludedReason.GEAR_TO_PARK, ExcludedReason.STARTED_IN_PARK -> "정차 중"
    ExcludedReason.SWITCH_SOUND -> "소리 바꾸기 요청"
    ExcludedReason.FEEDING_WINDOW -> "수유 시간대"
    ExcludedReason.SESSION_CAP -> "세션 상한"
    else -> e.name.lowercase()
}

private fun stopText(reason: StopReason, mode: HandoffKind, rest: String?) = buildString {
    append(
        when (reason) {
            StopReason.ATTEMPT_LIMIT -> "두 번 시도 뒤"
            StopReason.SESSION_CAP -> "세션 20분 상한"
            StopReason.FEEDING_WINDOW -> "수유 시간대"
            StopReason.NO_SOUND -> "뒷좌석 출력 없음"
        },
    )
    append(" · ")
    append(
        when (mode) {
            HandoffKind.REST_OFFER -> "${rest ?: "쉼터"} 들르기 제안"
            HandoffKind.ARRIVAL_NOTICE -> "도착 안내"
            HandoffKind.NO_REST -> "안전한 곳에 세우기 안내"
            HandoffKind.PARKED -> "정차 중 인계"
        },
    )
}

private fun action(a: CarActionKind) = when (a) {
    CarActionKind.REAR_PLAYBACK -> "뒷좌석 재생"
    CarActionKind.DEFAULT_ZONE_PLAYBACK -> "앞에서도 들리게 재생"
    CarActionKind.SEAT_HEATER_OFF -> "뒷좌석 열선 끔"
    CarActionKind.REAR_WINDOW_LOCK -> "뒷창 잠금"
    CarActionKind.CABIN_COOL -> "실내 온도 낮춤"
    CarActionKind.READING_LIGHT_OFF -> "독서등 끔"
    CarActionKind.WAYPOINT_ADDED -> "경유지 추가"
    CarActionKind.ARRIVAL_NOTICE -> "도착 안내"
    CarActionKind.GUARDIAN_ALERT -> "다른 보호자 알림"
}

/** 리포트 상세: 명세서 11장 순서(타임라인 → 울음 직전 맥락 → 조향 → 차가 한 일 → 제어 기록 → 방법별 성적). */
@Composable
fun ReportDetailScreen(model: AppModel, sessionId: String, onBack: () -> Unit) {
    val s by model.state.collectAsState()
    val b: ReportSummaryBody = s.reports[sessionId]?.body ?: return Page("리포트", onBack) { Muted("리포트가 없어요") }
    Page("주행 리포트", onBack) {
        if (b.simulated) Muted("시뮬레이션")
        // 요약 타일 세 개(구현 계획 8장)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("울음 시간", seconds(b.timeline.sumOf { it.totalCryS }), Modifier.weight(1f))
            Tile("차가 한 일", "${b.carActions.sumOf { it.count }}번", Modifier.weight(1f))
            // 정차 단계(쉼터 제안·도착 안내 등)가 있었던 에피소드 수. 실제 정차 횟수가 아니다
            Tile("정차 안내", "${b.timeline.count { it.stop != null }}번", Modifier.weight(1f))
        }
        when (b.outputRoute) {
            OutputRoute.REAR_ZONE -> Muted("소리는 뒷좌석으로 나갔어요")
            OutputRoute.DEFAULT_ZONE_FALLBACK -> Muted("뒷좌석 출력이 없어 앞에서도 들렸어요")
            OutputRoute.NO_PLAYBACK -> Unit // 재생이 없었으면 출력 경로를 보이지 않는다
        }
        if (b.truncated == true) Muted("오래된 에피소드는 줄였어요")

        Text("출발 ${clock(b.startedAt)}", style = MaterialTheme.typography.titleMedium)
        if (b.timeline.isEmpty()) Muted("기록된 에피소드가 없어요")
        for (e in b.timeline) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val how = if (e.trigger == EpisodeTrigger.CRY_ALARM) "울음 감지" else "소리 바꾸기 요청"
                    Text("${clock(e.startedAt)} $how" + (e.alarmLatencyS?.let { " (${"%.1f".format(it)}초 뒤 알림)" } ?: ""))
                    Text("음원: ${sound(s, e.sound, e.plays.firstOrNull()?.songId)} · 재생 ${seconds(e.playS)}")
                    for (p in e.plays) {
                        val name = if (p.method == SoundMethod.PARENT_SONG) s.songs.find { it.songId == p.songId }?.name?.let { "부모 노래($it)" } ?: "부모 노래" else method(p.method)
                        Muted("· ${purpose(p.purpose)} $name ${clock(p.startedAt)}~${clock(p.endedAt)} ${seconds(p.playS)}")
                    }
                    Text("판정: ${result(e.result)}" + (e.excludedReason?.let { " · 성적에서 뺌(${excluded(it)})" } ?: ""))
                    if (e.recried) Text("다시 울음")
                    e.second?.let { sec -> Text("2차: ${secondText(s, sec)}") }
                    e.stop?.let { st ->
                        Text("정차: ${stopText(st.reason, st.mode, st.restName)}" + (if (st.waypointAdded) " · 경유지 추가" else "") + if (st.guardianNotified) " · 다른 보호자에게 알림" else "")
                    }
                    Muted("총 울음 ${seconds(e.totalCryS)}")
                }
            }
        }
        Text("도착 ${clock(b.endedAt)}", style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()

        if (b.preCryContext.isNotEmpty()) {
            Text("울음 직전 30초", style = MaterialTheme.typography.titleMedium)
            for (p in b.preCryContext) {
                Muted(
                    "평균 ${"%.0f".format(p.speedKmhAvg)}km/h(최고 ${"%.0f".format(p.speedKmhMax)}) · 브레이크 ${p.brakePresses}번 · 큰 조향 ${p.steeringReversals}번" +
                        (p.cabinTempC?.let { " · 실내 ${it}℃" } ?: "") + if (p.playing) " · 재생 중" else "",
                )
                Muted(preCryDrive(p))
            }
        }
        Text("평소 대비 조향", style = MaterialTheme.typography.titleMedium)
        val st = b.steering
        if (!st.available) {
            Muted(steeringUnavailableText(st.unavailableReason == SteeringUnavailableReason.NO_CRY))
        } else {
            Muted("평소 분당 ${st.baselinePerMin} · 울음 중 ${st.duringCryPerMin} · 대응 뒤 ${st.afterResponsePerMin}")
        }
        Text("차가 대신 한 일", style = MaterialTheme.typography.titleMedium)
        if (b.carActions.isEmpty()) Muted("없어요")
        for (a in b.carActions) Muted("${action(a.action)} ${a.count}번" + (a.totalS?.let { " · ${seconds(it)}" } ?: ""))
        if (b.controls.isNotEmpty()) {
            Text("제어 기록", style = MaterialTheme.typography.titleMedium)
            for (c in b.controls) Muted("${c.api} ${c.target}: ${c.succeeded}/${c.attempts} 성공" + if (c.retries > 0) " · 다시 ${c.retries}번" else "")
        }
        b.methodStats?.let { stats ->
            Text("소리별", style = MaterialTheme.typography.titleMedium)
            for (m in stats) Muted("${m.label}: " + if (m.attempts < 3) "아직 모름" else "${m.attempts}번 중 ${m.successes}번 그침")
            Muted("그 소리를 틀었을 때 그친 횟수예요. 소리 덕분인지는 알 수 없어요")
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) = Card(modifier) {
    Column(Modifier.padding(12.dp)) {
        Muted(label)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
