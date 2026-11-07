package kr.aigyeot.phone

import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.FeedSide
import kr.aigyeot.phone.domain.model.MINUTE
import kr.aigyeot.phone.domain.usecase.BuildCareSummary
import kr.aigyeot.phone.domain.model.carePattern
import kr.aigyeot.phone.domain.model.lastFeedStart
import kr.aigyeot.phone.domain.model.overlaps
import kr.aigyeot.phone.domain.model.problem
import kr.aigyeot.phone.domain.model.rfc3339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DebugSeedTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun `시연 시드는 U50 숫자를 낸다`() {
        val t = ZonedDateTime.of(2026, 11, 6, 15, 0, 30, 0, seoul).toInstant().toEpochMilli()
        val m = t / MINUTE * MINUTE // 분 내림
        val f = demoSeed(t, seoul, CareFile(childNickname = "콩이"))
        assertU50(f, t)
        assertEquals(FeedSide.BOTH, f.events.single { it.startAt == m - 120 * MINUTE }.feedSide)
        assertEquals("콩이", f.childNickname)
        // 지난 6일: 날마다 수유 7번(간격 중앙값 3시간), 잠 13시간 30분, 기저귀 6번.
        val p = carePattern(f, emptyList(), t, seoul)
        assertEquals(listOf(7, 7, 7, 7, 7, 7, 2), p.feedsPerDay)
        assertEquals(List(6) { 810 * MINUTE }, p.sleepPerDayMs!!.take(6))
        assertEquals(listOf(6, 6, 6, 6, 6, 6, 2), p.diapersPerDay)
        assertEquals(180 * MINUTE, p.feedIntervalMs)
    }

    @Test
    fun `시연 시드는 언제 넣어도 규칙에 맞는다`() {
        // 0시부터 30분마다: 미래 기록·같은 종류 겹침·진행 중 기록이 없다(이른 시각에는 오늘 장면이 어제로 넘어간다).
        for (half in 0 until 48) {
            val t = ZonedDateTime.of(2026, 11, 6, half / 2, half % 2 * 30, 0, 0, seoul).toInstant().toEpochMilli()
            val f = demoSeed(t, seoul)
            for (e in f.events) {
                assertNull("$half ${e.kind}", e.problem(t))
                assertFalse("$half ${e.kind} ${e.startAt}", f.overlaps(e, t))
                assertFalse(e.endAt == null && e.kind != CareKind.DIAPER && e.kind != CareKind.NOTE)
            }
            // 종류가 달라도 시각이 겹치지 않는다(이른 시드에서 어제 잠이 오늘 장면 수유와 겹치지 않게). 경계에 닿는 것은 괜찮다.
            for (x in f.events) for (y in f.events) {
                if (x === y) continue
                assertFalse("$half ${x.kind}·${y.kind} ${x.startAt}", x.startAt < (y.endAt ?: y.startAt) && y.startAt < (x.endAt ?: x.startAt))
            }
            assertEquals(t - 120 * MINUTE, f.lastFeedStart())
            assertEquals(t - 60 * MINUTE, f.events.filter { it.kind == CareKind.DIAPER }.maxOf { it.startAt })
            // U50 오늘 숫자는 08:30부터 모두 맞는다. 그 전에는 6시간 전 수유가 0시 앞이고, 마지막 잠 끝(T−150)이 오늘 6시 전이라 밤중 깸에 들어간다.
            if (half >= 17) assertU50(f, t)
        }
    }

    @Test
    fun `06시 40분 시드는 어제 마지막 수유를 남긴다`() {
        // 지우는 경계(T−400)가 0시다. 어제 23:40~24:00 수유는 경계에 닿을 뿐이라 남는다(iOS와 같음, 어제 수유 7번).
        val t = ZonedDateTime.of(2026, 11, 6, 6, 40, 0, 0, seoul).toInstant().toEpochMilli()
        val f = demoSeed(t, seoul)
        assertEquals(7, carePattern(f, emptyList(), t, seoul).feedsPerDay[5])
    }

    @Test
    fun `서머타임이 바뀐 날이 지난 6일에 있어도 시드 기록이 겹치지 않는다`() {
        // 뉴욕 봄(3/8 2시→3시)·가을(11/1 2시→1시), 산티아고 0시 건너뜀(9/6 24시→1시).
        for ((zone, date) in listOf("America/New_York" to "2026-03-10", "America/New_York" to "2026-11-03", "America/Santiago" to "2026-09-08")) {
            val z = ZoneId.of(zone)
            val t = java.time.LocalDate.parse(date).atTime(15, 0).atZone(z).toInstant().toEpochMilli()
            val f = demoSeed(t, z)
            for (e in f.events) {
                assertNull("$zone ${e.kind}", e.problem(t))
                assertFalse("$zone ${e.kind} ${e.startAt}", f.overlaps(e, t))
            }
            assertU50(f, t, z)
            // U50: 지난 6일은 날마다 잠 합 13~14시간(바뀐 날도). 보정을 빼면 바뀐 날이 12시간 30분·14시간 30분이 된다.
            for (ms in carePattern(f, emptyList(), t, z).sleepPerDayMs!!.take(6)) assertTrue("$zone ${ms / MINUTE}", ms in 780 * MINUTE..840 * MINUTE)
        }
    }

    private fun assertU50(f: CareFile, t: Long, zone: ZoneId = seoul) {
        val m = t / MINUTE * MINUTE
        val s = BuildCareSummary(f, t, zone)
        val at = rfc3339(t, zone)
        assertEquals(at, rfc3339(m - 210 * MINUTE, zone), s.lastSleepStartAt)
        assertEquals(at, rfc3339(m - 150 * MINUTE, zone), s.lastSleepEndAt)
        assertNull(at, s.sleepingSince)
        assertEquals(at, 120, s.todaySleepMin)
        assertEquals(at, 1, s.nightWakeups)
        assertEquals(at, rfc3339(m - 60 * MINUTE, zone), s.lastDiaperAt)
        assertEquals(at, 2, s.todayDiaperCount)
        assertEquals(at, 2, s.todayFeedCount)
        assertEquals(at, m - 120 * MINUTE, f.lastFeedStart())
    }
}
