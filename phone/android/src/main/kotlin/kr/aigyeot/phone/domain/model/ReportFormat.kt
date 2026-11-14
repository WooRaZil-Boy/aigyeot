package kr.aigyeot.phone.domain.model

import kr.aigyeot.shared.EpisodeResult
import kr.aigyeot.shared.EpisodeTrigger
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.SoundMethod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ---- 차 기록 줄: 저장하지 않고 받은 리포트에서 그릴 때 만든다 ----

/** 주행이면 `episodeId`가 null이다. 리포트에 없는 값은 null. */
data class CarLine(
    val sessionId: String,
    val episodeId: String?,
    val startAt: Long,
    val endAt: Long?,
    val sound: SoundMethod?,
    val result: EpisodeResult?,
) {
    val isDrive get() = episodeId == null
}

fun carLines(reports: Collection<ReportSummaryBody>): List<CarLine> = reports.flatMap { r ->
    val start = instantOf(r.startedAt) ?: return@flatMap emptyList()
    listOf(CarLine(r.sessionId, null, start, instantOf(r.endedAt), null, null)) +
        CareStats.cryAlarms(listOf(r)).map { (at, e) -> CarLine(r.sessionId, e.episodeId, at, null, e.sound?.method, e.result) }
}.sortedBy { it.startAt }

/** "탑승 · 오후 1:10–1:52"(세션 구간: 중간 정차가 들어 있어 '주행'이라 하지 않는다), "차에서 울음 · {시각} · {소리} · {결과}". 없는 값은 칸째 뺀다. */
fun CarLine.text(zone: ZoneId): String =
    if (isDrive) "탑승 · " + (endAt?.let { koSpan(startAt, it, zone) } ?: koClock(startAt, zone))
    else listOfNotNull("차에서 울음", koClock(startAt, zone), sound?.let(::methodName), result?.let(::resultText)).joinToString(" · ")

// ---- 리포트 글자: 두 앱 같은 글자 ----

/** 리포트·재생의 구간. 끝을 읽지 못하면 시작 시각만, 시작도 못 읽으면 "—". */
fun reportSpan(start: String?, end: String?, zone: ZoneId): String {
    val s = instantOf(start) ?: return "—"
    return instantOf(end)?.let { koSpan(s, it, zone) } ?: koClock(s, zone)
}

/** report.detail.title "11월 6일(금)": 주행을 시작한 날. 읽지 못하면 null. */
fun reportDate(b: ReportSummaryBody, zone: ZoneId): String? =
    instantOf(b.startedAt)?.let { koDate(Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) }

/** report.row.title "11월 6일(금) 오전 2:54–3:44". */
fun reportRowTitle(b: ReportSummaryBody, zone: ZoneId): String =
    listOfNotNull(reportDate(b, zone), reportSpan(b.startedAt, b.endedAt, zone)).joinToString(" ")

/** "울음 {n}번"(0번 "울음 기록 없음"). n은 울음 경보로 시작한 에피소드 수로, 차 기록 줄의 '차에서 울음' 줄 수와 같다. 리포트 상세의 큰 글도 이것이다. */
fun reportCries(b: ReportSummaryBody): String {
    val n = b.timeline.count { it.trigger == EpisodeTrigger.CRY_ALARM } // 시각을 읽지 못한 울음도 센다(iOS reportSub와 같다)
    return if (n == 0) "울음 기록 없음" else "울음 ${n}번"
}

/** report.row.sub: [reportCries], 연습 장면(`simulated`)이면 뒤에 " · 연습 장면". */
fun reportRowSub(b: ReportSummaryBody): String = reportCries(b) + if (b.simulated) " · 연습 장면" else ""

fun methodName(m: SoundMethod) = when (m) {
    SoundMethod.WHITE_NOISE -> "백색소음"
    SoundMethod.WOMB -> "뱃속 소리"
    SoundMethod.LULLABY -> "자장가"
    SoundMethod.PARENT_SONG -> "우리 노래"
    SoundMethod.UNKNOWN -> "기타"
}

/** 차가 보낸 label을 보이되, 모르는 method는 label 대신 "기타". */
fun soundLabel(method: SoundMethod, label: String) = if (method == SoundMethod.UNKNOWN) methodName(method) else label

/** 울음 결과: 리포트 상세·차 기록 줄·울음 띠 표시가 같은 낱말을 쓴다. 1차 시도가 없으면 "1차 시도 없음"(차 기록 줄은 칸째 뺀다). */
fun resultText(r: EpisodeResult?) = when (r) {
    EpisodeResult.SUCCESS -> "그침"
    EpisodeResult.FAILURE -> "계속 울음"
    EpisodeResult.UNDETERMINED -> "판정하지 못함"
    null -> "1차 시도 없음"
}
