package kr.aigyeot.app.ui

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 시각 글자 한 곳(디자인 v6 결정 11-09): 차트 안(축·'지금'·툴팁)만 24시간제 `HH:MM`, 나머지는 문구표(phone-care-plan 7.1) '오전/오후 h:mm'.
 * 같은 값을 화면마다 따로 만들지 않는다. 시간대는 기기 시간대(한국 한정, 범위 결정 11-07).
 */
enum class ClockMode { CHART, TEXT }

object TimeText {
    var zone: ZoneId = ZoneId.systemDefault()

    private fun at(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    /** 시각 하나. CHART "14:05", TEXT "오후 2:05"(정오 "오후 12:05", 자정 "오전 12:05"). */
    fun clock(ms: Long, mode: ClockMode = ClockMode.TEXT): String = clock(at(ms).hour, at(ms).minute, mode)

    fun clock(hour: Int, minute: Int, mode: ClockMode): String = when (mode) {
        ClockMode.CHART -> "%02d:%02d".format(hour, minute)
        ClockMode.TEXT -> (if (hour < 12) "오전 " else "오후 ") + "${(hour + 11) % 12 + 1}:%02d".format(minute)
    }

    /** 시간 구간 "오후 1:10–1:52"(같은 오전/오후면 뒤쪽 접두를 뺀다). */
    fun range(from: Long, to: Long): String {
        val a = clock(from); val b = clock(to)
        return if (a.take(2) == b.take(2)) "$a–${b.drop(3)}" else "$a–$b"
    }

    private val DOW = arrayOf("월", "화", "수", "목", "금", "토", "일")

    /** 날짜 "11월 9일(월)". */
    fun date(ms: Long): String = at(ms).let { "${it.monthValue}월 ${it.dayOfMonth}일(${DOW[it.dayOfWeek.value - 1]})" }

    /** 길이 "3분 12초"·"42분"·"1시간 21분". [seconds]면 1분 미만은 초까지. */
    fun duration(ms: Long, seconds: Boolean = false): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return when {
            h > 0 -> if (m > 0) "${h}시간 ${m}분" else "${h}시간"
            m > 0 -> if (seconds && r > 0) "${m}분 ${r}초" else "${m}분"
            else -> "${r}초"
        }
    }
}
