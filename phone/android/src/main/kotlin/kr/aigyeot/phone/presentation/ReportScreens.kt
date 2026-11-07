package kr.aigyeot.phone.presentation

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.reportDate
import kr.aigyeot.phone.domain.model.reportRowSub
import kr.aigyeot.phone.domain.model.reportRowTitle
import kr.aigyeot.phone.domain.model.reportSpan
import kr.aigyeot.phone.domain.model.steeringUnavailableText
import kr.aigyeot.phone.domain.model.actionText
import kr.aigyeot.phone.domain.model.excludedText
import kr.aigyeot.phone.domain.model.judgeText
import kr.aigyeot.phone.domain.model.methodStatText
import kr.aigyeot.phone.domain.model.outputRouteText
import kr.aigyeot.phone.domain.model.playName
import kr.aigyeot.phone.domain.model.preCryDrive
import kr.aigyeot.phone.domain.model.purposeText
import kr.aigyeot.phone.domain.model.recriedText
import kr.aigyeot.phone.domain.model.secondText
import kr.aigyeot.phone.domain.model.soundText
import kr.aigyeot.phone.domain.model.stopLine
import kr.aigyeot.phone.domain.model.stopNoticeCount
import kr.aigyeot.phone.domain.model.triggerText
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.SteeringUnavailableReason

/** 리포트 목록: 새 것이 위. */
@Composable
fun ReportsScreen(onBack: () -> Unit, open: (String) -> Unit, vm: ReportsViewModel = appViewModel()) {
    val list by vm.reports.collectAsStateWithLifecycle()
    Page("리포트", onBack) {
        StatusBands()
        if (list.isEmpty()) Muted("주행이 끝나면 여기에 리포트가 와요", Type.body)
        for (r in list) ReportCard(r, open)
    }
}

/** 리포트 목록 한 줄(guardian 오늘의 '최근 리포트'도 같은 줄을 쓴다). */
@Composable
internal fun ReportCard(b: ReportSummaryBody, open: (String) -> Unit) {
    Card(onClick = { open(b.sessionId) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(reportRowTitle(b, APP_ZONE), style = MaterialTheme.typography.titleMedium)
            Muted(reportRowSub(b))
        }
    }
}

/** 리포트 상세: 명세서 11장 순서(타임라인 → 울음 직전 맥락 → 조향 → 차가 한 일 → 제어 기록 → 방법별 성적). */
@Composable
fun ReportDetailScreen(onBack: () -> Unit, vm: ReportDetailViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val songs = ui.songs
    val b: ReportSummaryBody = ui.report ?: return Page("리포트", onBack) { Muted("리포트가 없어요") }
    val zone = APP_ZONE
    Page(reportDate(b, zone) ?: "리포트", onBack) {
        Text(reportSpan(b.startedAt, b.endedAt, zone), style = MaterialTheme.typography.titleMedium)
        if (b.simulated) Muted("시뮬레이션")
        // 요약 타일 세 개(구현 계획 8장)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("울음 시간", seconds(b.timeline.sumOf { it.totalCryS }), Modifier.weight(1f))
            Tile("차가 한 일", "${b.carActions.sumOf { it.count }}번", Modifier.weight(1f))
            Tile("정차 안내", "${stopNoticeCount(b.timeline.map { it.stop })}번", Modifier.weight(1f))
        }
        outputRouteText(b.outputRoute)?.let { Muted(it) }
        if (b.truncated == true) Muted("오래된 에피소드는 줄였어요")

        Text("출발 ${clock(b.startedAt)}", style = MaterialTheme.typography.titleMedium)
        if (b.timeline.isEmpty()) Muted("기록된 에피소드가 없어요", Type.body)
        for (e in b.timeline) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${clock(e.startedAt)} ${triggerText(e.trigger)}" + (e.alarmLatencyS?.let { " (${"%.1f".format(it)}초 뒤 알림)" } ?: ""))
                    Text("음원: ${soundText(songs, e.sound, e.plays.firstOrNull()?.songId)} · 재생 ${seconds(e.playS)}")
                    for (p in e.plays) {
                        Muted("· ${purposeText(p.purpose)} ${playName(songs, p)} ${reportSpan(p.startedAt, p.endedAt, zone)} ${seconds(p.playS)}")
                    }
                    Text("판정: ${judgeText(e.result)}" + (e.excludedReason?.let { " · 성적에서 뺌(${excludedText(it)})" } ?: ""))
                    recriedText(e.recried)?.let { Text(it) }
                    e.second?.let { sec -> Text("2차: ${secondText(songs, sec)}") }
                    e.stop?.let { st -> Text(stopLine(st)) }
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
            Muted(steeringUnavailableText(st.unavailableReason == SteeringUnavailableReason.NO_CRY), Type.body)
        } else {
            Muted("평소 분당 ${st.baselinePerMin} · 울음 중 ${st.duringCryPerMin} · 대응 뒤 ${st.afterResponsePerMin}")
        }
        Text("차가 대신 한 일", style = MaterialTheme.typography.titleMedium)
        if (b.carActions.isEmpty()) Muted("없어요", Type.body)
        for (a in b.carActions) Muted("${actionText(a.action)} ${a.count}번" + (a.totalS?.let { " · ${seconds(it)}" } ?: ""))
        if (b.controls.isNotEmpty()) {
            Text("제어 기록", style = MaterialTheme.typography.titleMedium)
            for (c in b.controls) Muted("${c.api} ${c.target}: ${c.succeeded}/${c.attempts} 성공" + if (c.retries > 0) " · 다시 ${c.retries}번" else "")
        }
        b.methodStats?.let { stats ->
            Text("소리별", style = MaterialTheme.typography.titleMedium)
            for (m in stats) Muted(methodStatText(m))
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
