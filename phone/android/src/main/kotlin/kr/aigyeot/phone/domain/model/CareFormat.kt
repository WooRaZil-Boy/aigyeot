package kr.aigyeot.phone.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// 돌봄 기록의 한국어 서식: 시각·구간·경과·길이 글자와 하루 목록 줄. 기기 언어와 관계없이 iOS와 같은 글자다.

// ---- 문구 형식(기기 언어와 관계없이 한국어) ----

private fun half(hour: Int) = if (hour < 12) "오전" else "오후"

/** "오후 12:52". */
fun koClock(ms: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    return "${half(t.hour)} ${hm12(t.hour, t.minute)}"
}

private fun hm12(hour: Int, minute: Int) = "${if (hour % 12 == 0) 12 else hour % 12}:${minute.toString().padStart(2, '0')}"

/** 구간 끝 시각: 시작과 같은 오전·오후면 "1:52", 다르면 "오후 12:20". 날짜는 보지 않는다(iOS와 같음). */
fun koClockAfter(start: Long, end: Long, zone: ZoneId): String {
    val s = Instant.ofEpochMilli(start).atZone(zone)
    val e = Instant.ofEpochMilli(end).atZone(zone)
    return if (half(s.hour) == half(e.hour)) hm12(e.hour, e.minute) else koClock(end, zone)
}

/** 구간 "오후 1:10–1:52". 시작과 끝이 같은 분(epoch 분)이면 시작 시각 하나만. */
fun koSpan(start: Long, end: Long, zone: ZoneId): String =
    if (start / MINUTE == end / MINUTE) koClock(start, zone) else "${koClock(start, zone)}–${koClockAfter(start, end, zone)}"

/** "11월 5일(수)". */
fun koDate(d: LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일(${"월화수목금토일"[d.dayOfWeek.value - 1]})"

/** log.date.*: "오늘" / "어제" / "11월 5일(수)"(고른 날 제목, 날짜 칸·시트 줄 값의 날짜). */
fun dayLabel(d: LocalDate, today: LocalDate) = when (d) {
    today -> "오늘"
    today.minusDays(1) -> "어제"
    else -> koDate(d)
}

/** today.quick.* · timer.title.* 의 종류 이름. */
fun kindName(k: CareKind) = when (k) {
    CareKind.FEED -> "수유"
    CareKind.SLEEP -> "잠"
    CareKind.DIAPER -> "기저귀"
    CareKind.NOTE -> "메모"
}

/** 며칠 전일 수 있는 시각(import.latestFeed): 오늘이면 "오전 11:00", 어제면 "어제 오전 11:00", 그 전이면 "11월 3일 오전 11:00". */
fun koDayClock(ms: Long, now: Long, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (d) {
        today -> ""
        today.minusDays(1) -> "어제 "
        else -> "${d.monthValue}월 ${d.dayOfMonth}일 "
    }
    return day + koClock(ms, zone)
}

/** 1분 미만 "방금", 1~59분 "12분", 60분 이상 "2시간 10분", 분이 0이면 "2시간". */
fun elapsedText(ms: Long): String {
    val m = ms.coerceAtLeast(0) / MINUTE
    return when {
        m < 1 -> "방금"
        m < 60 -> "${m}분"
        m % 60 == 0L -> "${m / 60}시간"
        else -> "${m / 60}시간 ${m % 60}분"
    }
}

/** 길이·평균. 0이면 "0분", 1분 미만이면 "1분 미만", 그 위는 "2시간", "2시간 10분". "방금"은 쓰지 않는다. */
fun durationText(ms: Long): String = when {
    ms <= 0 -> "0분"
    ms < MINUTE -> "1분 미만"
    else -> elapsedText(ms)
}

/** "2시간 10분 전". 1분 미만은 "방금"(뒤에 '전'을 붙이지 않는다). */
fun agoText(at: Long, now: Long) = elapsedText(now - at).let { if (it == "방금") it else "$it 전" }

/** 타이머·잠금 화면: "12:34", 1시간이 넘으면 "1:02:34". */
fun timerText(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

fun sideName(s: FeedSide) = when (s) {
    FeedSide.LEFT -> "왼쪽"
    FeedSide.RIGHT -> "오른쪽"
    FeedSide.BOTH -> "양쪽"
    FeedSide.BOTTLE -> "젖병"
}

// ---- 하루 목록 줄(log.row.*) ----

/** 쪽: 젖병이면 "젖병 {n}ml"(양이 없으면 "젖병"). */
private fun CareEvent.sideText(): String? = feedSide?.let { if (it == FeedSide.BOTTLE && amountMl != null) "젖병 ${amountMl}ml" else sideName(it) }

/** 손 기록 한 줄. 시각은 그 기록의 시간대로 보인다. */
fun logRowText(e: CareEvent, zone: ZoneId): String {
    val t = koClock(e.startAt, zone)
    val span = e.endAt?.let { koSpan(e.startAt, it, zone) }
    return when (e.kind) {
        CareKind.FEED -> when {
            e.isOngoing -> "수유 중 · ${t}부터"
            span == null -> listOfNotNull("수유", t, e.sideText(), "끝 시각 모름").joinToString(" · ") // log.row.feedUnknown
            else -> listOfNotNull("수유", span, e.sideText()).joinToString(" · ")
        }
        CareKind.SLEEP -> when {
            e.isOngoing -> "자는 중 · ${t}부터"
            span == null -> "잠 · $t · 끝 시각 모름"
            else -> "잠 · $span · ${durationText(e.endAt - e.startAt)}"
        }
        CareKind.DIAPER -> listOfNotNull("기저귀", t, e.diaper?.let(::diaperName)).joinToString(" · ")
        CareKind.NOTE -> listOfNotNull("메모", t, e.note).joinToString(" · ")
    }
}

/** log.row.memoLine: 수유·잠·기저귀에 단 메모는 줄 아래 둘째 줄에 글 그대로. 메모 기록은 첫 줄에 이미 있다. */
fun memoLine(e: CareEvent): String? = e.note?.takeIf { e.kind != CareKind.NOTE }

/** diaper.kind. */
fun diaperName(d: Diaper) = when (d) {
    Diaper.WET -> "소변"
    Diaper.DIRTY -> "대변"
    Diaper.BOTH -> "둘 다"
    Diaper.DRY -> "깨끗함"
}
