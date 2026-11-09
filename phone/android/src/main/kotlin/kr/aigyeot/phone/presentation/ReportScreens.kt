package kr.aigyeot.phone.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.actionText
import kr.aigyeot.phone.domain.model.excludedText
import kr.aigyeot.phone.domain.model.instantOf
import kr.aigyeot.phone.domain.model.judgeText
import kr.aigyeot.phone.domain.model.outputRouteText
import kr.aigyeot.phone.domain.model.playName
import kr.aigyeot.phone.domain.model.preCryDrive
import kr.aigyeot.phone.domain.model.purposeText
import kr.aigyeot.phone.domain.model.recriedText
import kr.aigyeot.phone.domain.model.reportRowSub
import kr.aigyeot.phone.domain.model.reportRowTitle
import kr.aigyeot.phone.domain.model.reportSpan
import kr.aigyeot.phone.domain.model.secondText
import kr.aigyeot.phone.domain.model.soundLabel
import kr.aigyeot.phone.domain.model.soundText
import kr.aigyeot.phone.domain.model.steeringUnavailableText
import kr.aigyeot.phone.domain.model.stopLine
import kr.aigyeot.phone.domain.model.stopNoticeCount
import kr.aigyeot.phone.domain.model.triggerText
import kr.aigyeot.shared.EpisodeResult
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.SteeringUnavailableReason
import java.util.Locale

/** 리포트 목록: 새 것이 위. */
@Composable
fun ReportsScreen(onBack: () -> Unit, open: (String) -> Unit, vm: ReportsViewModel = appViewModel()) {
    val list by vm.reports.collectAsStateWithLifecycle()
    BackPage("리포트", onBack) {
        StatusBands()
        if (list.isEmpty()) FootNote("주행이 끝나면 여기에 리포트가 와요")
        else ListBlock { for (r in list) ReportRow(r, open) }
    }
}

/**
 * 주행 리포트(시안 S.report): 머리('{날짜} {구간} · {n}분' + 큰 '울음 n번' + 감지까지·그칠 때까지·차가 한 일) → 에피소드(띠 + 줄 글) →
 * 방법별 성적(점 행렬) → 평소 대비 조향 보정(막대 셋) → 울음 직전 주행(이름·값) → 차가 대신 한 일 → 제어 기록.
 * 블록 안 글자(판정·2차·정차 문장, 소리 이름)는 지금 문구 함수 그대로다(U47·U53·U55).
 */
@Composable
fun ReportDetailScreen(onBack: () -> Unit, vm: ReportDetailViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val songs = ui.songs
    val p = Tokens.colors
    val b: ReportSummaryBody = ui.report ?: return BackPage("주행 리포트", onBack) { FootNote("리포트가 없어요") }
    val zone = APP_ZONE
    val k = remember(b) { reportKpis(b) }
    BackPage("주행 리포트", onBack) {
        Block(padding = androidx.compose.foundation.layout.PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 20.dp)) {
            Label(listOfNotNull(reportRowTitle(b, zone), reportMinutes(b)?.let { "${it}분" }).joinToString(" · "))
            Text(reportRowSub(b).removeSuffix(" · 시뮬레이션"), Modifier.padding(top = 6.dp), style = Type.hero, color = p.textPrimary)
            if (b.simulated) Row(Modifier.padding(top = 6.dp)) { Badge("시뮬레이션") }
            KpiRow(
                listOf(
                    Kpi("감지까지", k.detectS?.let(::secondsText) ?: "—"),
                    // 시안 '그칠 때까지'는 그친 시각이 리포트에 없어 쓰지 않고, 기존 타일 '울음 시간'(totalCryS 합)을 쓴다(거짓 시간 방지).
                    Kpi("울음 시간", seconds(k.cryS)),
                    Kpi("차가 한 일", "${k.actionKinds}가지"),
                    Kpi("정차 안내", "${stopNoticeCount(b.timeline.map { it.stop })}번"),
                ),
                Modifier.padding(top = 16.dp),
            )
            outputRouteText(b.outputRoute)?.let { Text(it, Modifier.padding(top = 12.dp), style = Type.caption, color = p.textTertiary) }
            if (b.truncated == true) Text("오래된 에피소드는 줄였어요", style = Type.caption, color = p.textTertiary)
        }
        Block {
            BlockHeader("에피소드")
            if (b.timeline.isEmpty()) Text("기록된 에피소드가 없어요", style = Type.body, color = p.textTertiary)
            b.timeline.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 16.dp), color = p.line)
                val start = instantOf(e.startedAt)?.let { clock(it) } ?: "—"
                BlockSub("$start ${triggerText(e.trigger)}부터" + if (e.result == EpisodeResult.SUCCESS) " 그칠 때까지" else "")
                if (e.plays.isNotEmpty()) EpisodeStrip(remember(e, songs) { episodeBand(e, songs) }, Modifier.padding(bottom = 12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val small = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp)
                    Text("음원: ${soundText(songs, e.sound, e.plays.firstOrNull()?.songId)} · 재생 ${seconds(e.playS)}" + (e.alarmLatencyS?.let { " · ${String.format(Locale.ROOT, "%.1f", it)}초 뒤 알림" } ?: ""), style = small, color = p.textSecondary)
                    for (pl in e.plays) Text("${purposeText(pl.purpose)} ${playName(songs, pl)} ${reportSpan(pl.startedAt, pl.endedAt, zone)} ${seconds(pl.playS)}", style = Type.caption, color = p.textTertiary)
                    Text("판정: ${judgeText(e.result)}" + (e.excludedReason?.let { " · 성적에서 뺌(${excludedText(it)})" } ?: ""), style = small, color = p.textSecondary)
                    recriedText(e.recried)?.let { Text(it, style = small, color = p.textSecondary) }
                    e.second?.let { sec -> Text("2차: ${secondText(songs, sec)}", style = small, color = p.textSecondary) }
                    e.stop?.let { st -> Text(stopLine(st), style = small, color = p.textSecondary) }
                    Text("총 울음 ${seconds(e.totalCryS)}", style = Type.caption, color = p.textTertiary)
                }
            }
        }
        b.methodStats?.let { stats ->
            Block {
                BlockHeader("방법별 성적")
                MethodDots(stats.map { MethodRow(soundLabel(it.method, it.label), it.successes, it.attempts) })
                BlockNote("그 소리를 틀었을 때 그친 횟수예요. 소리 덕분인지는 알 수 없어요")
            }
        }
        Block {
            BlockHeader("평소 대비 조향 보정")
            val st = b.steering
            if (!st.available) {
                Text(steeringUnavailableText(st.unavailableReason == SteeringUnavailableReason.NO_CRY), style = Type.body, color = p.textTertiary)
            } else {
                BlockSub("1분당 큰 조향 보정")
                SteerBars(st.baselinePerMin, st.duringCryPerMin, st.afterResponsePerMin)
            }
        }
        if (b.preCryContext.isNotEmpty()) Block {
            BlockHeader("울음 직전 주행")
            b.preCryContext.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = p.line)
                if (b.preCryContext.size > 1) b.timeline.find { it.episodeId == c.episodeId }?.let { e -> Text(clock(e.startedAt), Modifier.padding(bottom = 6.dp), style = Type.caption, color = p.textTertiary) }
                KeyValues(
                    listOfNotNull(
                        "속도" to "평균 ${"%.0f".format(c.speedKmhAvg)} · 최고 ${"%.0f".format(c.speedKmhMax)} km/h",
                        "브레이크" to "${c.brakePresses}번",
                        "큰 조향 반전" to "${c.steeringReversals}번",
                        c.cabinTempC?.let { "실내" to "${it}°C" },
                    ),
                )
                Text(preCryDrive(c) + if (c.playing) " · 재생 중" else "", Modifier.padding(top = 8.dp), style = Type.caption, color = p.textTertiary)
            }
        }
        Block {
            BlockHeader("차가 대신 한 일")
            if (b.carActions.isEmpty()) Text("없어요", style = Type.body, color = p.textTertiary)
            else KeyValues(b.carActions.map { a -> actionText(a.action) to ("${a.count}번" + (a.totalS?.let { " · ${seconds(it)}" } ?: "")) })
        }
        if (b.controls.isNotEmpty()) Block {
            BlockHeader("제어 기록")
            KeyValues(b.controls.map { c -> "${c.api} ${c.target}" to ("${c.succeeded}/${c.attempts} 성공" + if (c.retries > 0) " · 다시 ${c.retries}번" else "") })
        }
    }
}

/** 이름·값 줄(.kv: 이름 흐림 15/22, 값 600 오른쪽). */
@Composable
private fun KeyValues(rows: List<Pair<String, String>>) {
    val p = Tokens.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((k, v) in rows) Row(Modifier.fillMaxWidth()) {
            Text(k, Modifier.padding(end = 14.dp), style = Type.body.copy(fontSize = 15.sp, lineHeight = 22.sp), color = p.textTertiary)
            Text(v, Modifier.weight(1f), style = Type.body.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = p.textPrimary, textAlign = TextAlign.End)
        }
    }
}
