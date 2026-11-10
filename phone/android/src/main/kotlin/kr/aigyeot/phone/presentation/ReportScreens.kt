package kr.aigyeot.phone.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.aigyeot.phone.domain.model.CareStats
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.actionText
import kr.aigyeot.phone.domain.model.cabinTempText
import kr.aigyeot.phone.domain.model.controlStatsText
import kr.aigyeot.phone.domain.model.controlTitle
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
                    val small = Type.rowSub
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
                MethodDots(CareStats.methodOrder(stats).map { (_, s) -> MethodRow(soundLabel(s.method, s.label), s.successes, s.attempts) })
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
                        c.cabinTempC?.let { "실내" to cabinTempText(it) },
                    ),
                )
                Text(preCryDrive(c), Modifier.padding(top = 8.dp), style = Type.caption, color = p.textTertiary)
            }
        }
        Block {
            BlockHeader("차가 대신 한 일")
            if (b.carActions.isEmpty()) Text("없어요", style = Type.body, color = p.textTertiary)
            else KeyValues(b.carActions.map { a -> actionText(a.action) to ("${a.count}번" + (a.totalS?.let { " · ${seconds(it)}" } ?: "")) })
        }
        // 제어 기록(iOS와 같다): 기록이 없어도 블록을 보인다. 줄마다 'API · 대상'(고정폭 작은 글)과 '시도 · 성공 · 실패 · 다시'(흐린 글) 두 줄.
        Block {
            BlockHeader("제어 기록")
            if (b.controls.isEmpty()) Text("없어요", style = Type.body, color = p.textTertiary)
            for (c in b.controls) Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(controlTitle(c), style = Type.footnote.copy(fontFamily = FontFamily.Monospace), color = p.textPrimary) // 고정폭 식별자(iOS .footnote.monospaced())
                Text(controlStatsText(c), style = Type.note, color = p.textTertiary)
            }
        }
    }
}

/** 이름·값 줄(.kv: 이름 흐림 15/22, 값 600 오른쪽). */
@Composable
private fun KeyValues(rows: List<Pair<String, String>>) {
    val p = Tokens.colors
    // 값 우선, 이름 하한: 값(weight 없음)이 먼저 자기 폭으로 재지되 줄의 65%를 넘지 않고, 이름은 남은 폭(최소 35%)을 쓴다.
    // 짧은 이름 + 긴 값은 값이 한 줄, 긴 이름 + 짧은 값은 이름이 줄을 바꾸고, 둘 다 길어도 어느 쪽도 0폭이 되지 않는다.
    BoxWithConstraints {
        val cap = maxWidth * 0.65f
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((k, v) in rows) Row(Modifier.fillMaxWidth()) {
                Text(k, Modifier.weight(1f).padding(end = 14.dp), style = Type.valueKey, color = p.textTertiary)
                Text(v, Modifier.widthIn(max = cap), style = Type.chip.copy(fontFeatureSettings = "tnum"), color = p.textPrimary, textAlign = TextAlign.End)
            }
        }
    }
}
