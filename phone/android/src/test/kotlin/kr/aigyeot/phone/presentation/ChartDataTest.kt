package kr.aigyeot.phone.presentation

import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.HOUR
import kr.aigyeot.phone.domain.model.MINUTE
import kr.aigyeot.phone.domain.model.onDate
import kr.aigyeot.phone.domain.model.carePattern
import kr.aigyeot.phone.domain.model.reportRowSub
import kr.aigyeot.phone.domain.model.CareStats
import kr.aigyeot.phone.domain.model.FeedGap
import kr.aigyeot.shared.CarAction
import kr.aigyeot.shared.CarActionKind
import kr.aigyeot.shared.EpisodeResult
import kr.aigyeot.shared.EpisodeTrigger
import kr.aigyeot.shared.MethodStat
import kr.aigyeot.shared.HandoffKind
import kr.aigyeot.shared.StopInfo
import kr.aigyeot.shared.StopReason
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.PlayEntry
import kr.aigyeot.shared.PlayPurpose
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.SoundMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** 차트 값(디자인 v6, 진행 기록 '결정' 1~8): 기록·리포트에서 그릴 때 계산한 값이 규칙대로다. */
class ChartDataTest {
    private val zone = APP_ZONE
    private val today = LocalDate.of(2026, 11, 9)
    private fun at(h: Int, m: Int = 0, day: LocalDate = today) = onDate(day, h, m, zone)
    private val now = at(14, 13)
    private var n = 0
    private fun ev(kind: CareKind, start: Long, end: Long? = null, deleted: Boolean = false) =
        CareEvent("e${n++}", kind, start, end, zone = zone.id, createdAt = start, updatedAt = start, deletedAt = if (deleted) start else null)

    private fun report(): ReportSummaryBody {
        val env = ProtocolJson.decodeFromString(Envelope.serializer(), File("../../shared/src/test/resources/protocol/report_summary.json").readText())
        return ProtocolJson.decodeFromJsonElement(ReportSummaryBody.serializer(), env.body)
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toOffsetDateTime().toString()

    @Test
    fun `차트 안 시각은 24시간제 HH MM이다`() {
        assertEquals("00:00", hhmm(0))
        assertEquals("14:13", hhmm(14 * 60 + 13))
        assertEquals("03:10", hhmm(at(3, 10), zone))
    }

    @Test
    fun `오늘 하루 띠는 그날에 걸친 구간을 자르고 횟수는 규칙대로 센다`() {
        val care = CareFile(
            sleepDiaperEnabled = true,
            events = listOf(
                ev(CareKind.SLEEP, at(23, 0, today.minusDays(1)), at(1, 0)), // 어제 밤 → 오늘 0~60분
                ev(CareKind.SLEEP, at(10, 20), at(11, 40)),
                ev(CareKind.FEED, at(3, 15), at(3, 35)),
                ev(CareKind.FEED, at(14, 0)), // 진행 중: 지금까지
                ev(CareKind.FEED, at(9, 0), at(9, 20), deleted = true), // 지운 것은 뺀다
                ev(CareKind.DIAPER, at(12, 58)),
            ),
        )
        val d = dayChart(care, emptyList(), today, now, zone, showCar = false)
        assertEquals(listOf(LaneKind.SLEEP, LaneKind.FEED, LaneKind.DIAPER), d.lanes.map { it.kind })
        val sleep = d.lanes[0]
        assertEquals(listOf(0 to 60, 620 to 700), sleep.spans.map { it.from to it.to })
        assertEquals("2번", sleep.sub)
        assertEquals("2시간 20분", sleep.value) // 끝난 잠이 오늘에 걸친 길이(60 + 80분)
        val feed = d.lanes[1]
        assertEquals("2번", feed.value)
        assertEquals(840 to 853, feed.spans.last().let { it.from to it.to })
        assertEquals("수유 03:15–03:35 · 20분", feed.spans.first().tip)
        assertEquals(listOf(778), d.lanes[2].marks.map { it.at })
        assertEquals(853, d.nowMin)
        assertTrue(d.summary.startsWith("오늘 하루: 잠 2시간 20분"))
        // 자정에 딱 끝난 어제 잠은 오늘 줄에 없다. 오늘 시작한 끝 모름 수유는 점으로 남는다.
        val edge = CareFile(sleepDiaperEnabled = true, events = listOf(ev(CareKind.SLEEP, at(23, 0, today.minusDays(1)), at(0, 0)), CareEvent("u", CareKind.FEED, at(5, 0), endUnknown = true, zone = zone.id, createdAt = 0, updatedAt = 0)))
        val e2 = dayChart(edge, emptyList(), today, now, zone, showCar = false)
        assertTrue(e2.lanes[0].spans.isEmpty())
        assertEquals(listOf(300 to 300), e2.lanes[1].spans.map { it.from to it.to })
        assertEquals("수유 05:00 · 끝 시각 모름", e2.lanes[1].spans.single().tip)
    }

    @Test
    fun `잠·기저귀를 끄면 그 줄을 빼고 차에서 줄은 리포트의 탑승 구간과 울음 경보를 그린다`() {
        val r = report().let { b ->
            b.copy(startedAt = iso(at(13, 10)), endedAt = iso(at(13, 52)), timeline = b.timeline.map { it.copy(startedAt = iso(at(13, 24))) })
        }
        val d = dayChart(CareFile(), listOf(r), today, now, zone, showCar = true)
        assertEquals(listOf(LaneKind.FEED, LaneKind.CAR), d.lanes.map { it.kind })
        val car = d.lanes[1]
        assertEquals("울음 1번", car.value)
        assertEquals("탑승 42분", car.sub)
        assertEquals(listOf(790 to 832), car.spans.map { it.from to it.to })
        assertEquals("탑승 13:10–13:52 · 42분", car.spans.single().tip) // 세션 구간은 중간 정차가 들어 있어 '주행'이라 하지 않는다(차 앱과 같음)
        assertEquals(listOf(804), car.marks.map { it.at })
        // 다른 날이면 지금 선이 없다.
        assertNull(dayChart(CareFile(), emptyList(), today.minusDays(1), now, zone, showCar = false).nowMin)
    }

    @Test
    fun `수유 간격 막대는 날마다 중앙값이고 평소는 앞 6일 간격이 5개 이상일 때만이다`() {
        val y = today.minusDays(1)
        val feeds = listOf(at(0, 0, y), at(3, 0, y), at(6, 0, y), at(9, 0, y)) + listOf(at(2, 0), at(4, 30), at(20, 0))
        val care = CareFile(events = feeds.map { ev(CareKind.FEED, it, it + 20 * MINUTE) })
        val w = intervalWeek(care, now, zone)
        assertEquals(7, w.bars.size)
        assertEquals("오늘", w.bars.last().label)
        assertEquals(180 * MINUTE, w.bars[5].value) // 어제: 3시간 셋
        // 오늘: 어제 9:00 → 오늘 2:00(17시간, 10시간 넘어 뺀다), 2:00 → 4:30. 20:00은 지금 뒤라 뺀다.
        assertEquals(150 * MINUTE, w.todayMs)
        assertNull(w.usualMs) // 앞 6일 간격 3개 < 5
        val more = care.copy(events = care.events + listOf(at(12, 0, y), at(15, 0, y)).map { ev(CareKind.FEED, it) })
        assertEquals(180 * MINUTE, intervalWeek(more, now, zone).usualMs)
        assertEquals("11월 8일 · 3시간", w.bars[5].tip)
    }

    @Test
    fun `차에서 울음 막대는 날마다 울음 경보 에피소드 수와 평균 감지 지연이다`() {
        val r = report().let { b -> b.copy(timeline = b.timeline.map { it.copy(startedAt = iso(at(13, 24))) }) }
        val w = carCryWeek(listOf(r), now, zone)
        assertEquals(1, w.total)
        assertEquals(listOf(0L, 0, 0, 0, 0, 0, 1), w.bars.map { it.value })
        assertEquals(2.4, w.avgLatencyS!!, 1e-9)
        assertEquals("2.4초", secondsText(w.avgLatencyS!!))
    }

    @Test
    fun `잠 패턴은 30분 칸이 잠과 겹치면 참이고 평균은 오늘을 빼고 잔 날로 나눈다`() {
        val y = today.minusDays(1)
        val care = CareFile(sleepDiaperEnabled = true, events = listOf(ev(CareKind.SLEEP, at(0, 0, y), at(3, 10, y)), ev(CareKind.SLEEP, at(0, 0), at(1, 0))))
        val g = sleepGrid(care, now, zone)
        assertEquals((0..6).toList(), g.rows[5].indices.filter { g.rows[5][it] }) // 0:00~3:30 칸 일곱
        assertEquals(listOf(0, 1), g.rows[6].indices.filter { g.rows[6][it] })
        assertEquals(2, g.sleepDays) // 오늘도 끝난 잠이 있으면 센다(패턴 '하루 잠'과 같은 분모)
        assertEquals((190 + 60) / 2 * MINUTE, g.avgMs)
        assertTrue(g.summary.contains("일 3시간 10분")) // 화면 읽기는 칸 수(3시간 30분)가 아니라 실제 길이
    }

    @Test
    fun `에피소드 띠는 재생 구간과 듣기 틈과 표시를 리포트에서 만든다`() {
        val base = report().timeline.first()
        val t0 = at(13, 24)
        val e = base.copy(
            startedAt = iso(t0), recried = true, result = EpisodeResult.SUCCESS,
            plays = listOf(
                PlayEntry(PlayPurpose.AUTO_FIRST, SoundMethod.LULLABY, null, iso(t0 + 2000), iso(t0 + 58_000), 56),
                PlayEntry(PlayPurpose.AUTO_SECOND, SoundMethod.WHITE_NOISE, null, iso(t0 + 66_000), iso(t0 + 122_000), 56),
            ),
        )
        val b = episodeBand(e, emptyList())
        assertEquals(listOf(EpisodeSeg(2, 58, "자장가"), EpisodeSeg(66, 122, "백색소음")), b.sounds)
        assertEquals(listOf(EpisodeSeg(58, 66, "듣기 틈")), b.gaps)
        assertEquals(122, b.totalS)
        // 시간축에는 시각을 아는 것만(시작·자동 2차 시작), 결과는 축 밖.
        assertEquals(listOf(EpisodeMarkAt(0, "감지 2.4초"), EpisodeMarkAt(66, "다시 울음")), b.marks)
        assertEquals(listOf("그쳤어요"), b.outcomes)
        // 다시 울음은 자동 2차 재생의 시작이다(운전자 요청 재생이면 표시하지 않는다).
        val req = e.copy(plays = e.plays.mapIndexed { i, p -> if (i == 1) p.copy(purpose = PlayPurpose.REQUEST) else p })
        assertTrue(episodeBand(req, emptyList()).marks.none { it.label == "다시 울음" })
        // 쉼터 제안은 운전자에게 실제로 물었을 때만, 소리 바꾸기 요청은 '감지'가 아니다.
        val stop = StopInfo(handoff = true, reason = StopReason.ATTEMPT_LIMIT, mode = HandoffKind.REST_OFFER, restName = null, waypointAdded = false, guardianNotified = false)
        assertTrue("쉼터 제안" in episodeBand(e.copy(stop = stop), emptyList()).outcomes)
        assertTrue("쉼터 제안" !in episodeBand(e.copy(stop = stop.copy(toldDriver = false)), emptyList()).outcomes)
        assertEquals("소리 바꾸기 요청", episodeBand(e.copy(trigger = EpisodeTrigger.SWITCH_SOUND), emptyList()).marks.first().label)
    }

    @Test
    fun `리포트 숫자 줄은 감지 평균·울음 합·한 일 종류 수다`() {
        val r = report().let { b -> b.copy(carActions = b.carActions + CarAction(CarActionKind.REAR_PLAYBACK, 1, 10) + CarAction(CarActionKind.WAYPOINT_ADDED, 0, null)) }
        val k = reportKpis(r)
        assertEquals(2.4, k.detectS!!, 1e-9)
        assertEquals(61, k.cryS)
        assertEquals(3, k.actionKinds) // 같은 종류는 한 번, 0번은 세지 않는다
        assertEquals(42L, reportMinutes(r))
    }

    @Test
    fun `마지막 수유 막대는 차 간격이 있으면 그것으로, 없으면 평소로 그린다`() {
        val last = at(12, 52)
        // 차: 마지막 수유 12:52, 다음 시간대 시작 = 12:52 + 3시간 × 0.8 → 간격 3시간, 꽉 참 = 3시간 45분
        val (v, tick) = feedMeter(last, now, last, last + (144 * MINUTE), null)!!
        assertEquals(81f / 225f, v, 1e-4f)
        assertEquals(0.64f, tick!!, 1e-4f)
        val (v2, tick2) = feedMeter(last, now, null, null, 180 * MINUTE)!!
        assertEquals(81f / 225f, v2, 1e-4f)
        assertNull(tick2)
        assertNull(feedMeter(last, now, null, null, null))
    }

    @Test
    fun `하루 줄은 앞 두 조각을 제목으로 나머지를 둘째 줄로 나눈다`() {
        assertEquals("수유 · 오후 12:52–1:10" to "왼쪽", splitRow("수유 · 오후 12:52–1:10 · 왼쪽"))
        assertEquals("수유 중 · 오후 2:01부터" to null, splitRow("수유 중 · 오후 2:01부터"))
        assertEquals("수유 · 오후 12:52" to "왼쪽 · 끝 시각 모름", splitRow("수유 · 오후 12:52 · 왼쪽 · 끝 시각 모름"))
        assertEquals("메모 · 오전 9:42" to "트림 · 잘 함", splitRow("메모 · 오전 9:42 · 트림 · 잘 함"))
    }

    @Test
    fun `지난주보다는 두 주의 하루 평균 수유 차이이고 앞 주 기록이 없거나 같으면 없다`() {
        fun feeds(daysAgo: Int, n: Int) = (0 until n).map { ev(CareKind.FEED, at(1 + it * 3, 0, today.minusDays(daysAgo.toLong()))) }
        val thisWeek = feeds(0, 8) + feeds(1, 7) // 평균 7.5
        assertNull(feedTrend(CareFile(events = thisWeek), now, zone))
        val prev = feeds(7, 7) + feeds(8, 7) // 평균 7.0
        assertEquals("지난주보다 0.5회 많아요", feedTrend(CareFile(events = thisWeek + prev), now, zone))
        assertEquals("지난주보다 0.5회 적어요", feedTrend(CareFile(events = feeds(0, 7) + feeds(1, 7) + feeds(7, 8) + feeds(8, 7)), now, zone))
        assertNull(feedTrend(CareFile(events = feeds(0, 7) + feeds(7, 7)), now, zone))
        // 평균끼리 뺀 뒤 반올림: 이번 주 7일 8번(8/7 ≈ 1.14) vs 앞 주 6일 7번(7/6 ≈ 1.17) → −0.02 → 0.0이라 없다.
        // (반올림한 뒤 빼면 1.1 − 1.2로 '0.1회 적어요'가 잘못 나온다.)
        val a = (1..6).flatMap { feeds(it, 1) } + feeds(0, 2)
        val b = feeds(7, 2) + (8..12).flatMap { feeds(it, 1) }
        assertNull(feedTrend(CareFile(events = a + b), now, zone))
    }

    @Test
    fun `집계 공통 규칙 한 곳을 패턴 숫자와 7일 막대가 같이 쓴다`() {
        // 중앙값·하루 평균(U27·U31)
        assertNull(CareStats.median(emptyList()))
        assertEquals(3L, CareStats.median(listOf(5L, 1L, 3L)))
        assertEquals(2L, CareStats.median(listOf(1L, 3L, 4L, 1L)))
        assertEquals(3.0, CareStats.perRecordedDay(listOf<Int>(0, 3, 3))!!, 0.0)
        assertNull(CareStats.perRecordedDay(listOf<Int>(0, 0)))
        assertEquals(90 * MINUTE, CareStats.perRecordedDay(listOf(0L, 60 * MINUTE, 120 * MINUTE)))
        // R14 간격: 지운 것·지금 뒤로 찍힌 것·10시간 넘는 간격은 빼고, 간격은 뒤 수유에 속한다.
        fun feed(id: String, t: Long, deleted: Boolean = false) =
            CareEvent(id, CareKind.FEED, t, endAt = t + 10 * MINUTE, zone = APP_ZONE.id, createdAt = t, updatedAt = t, deletedAt = if (deleted) t else null)
        val base = now - 20 * HOUR
        val file = CareFile(events = listOf(feed("a", base), feed("b", base + 11 * HOUR), feed("x", base + 12 * HOUR, deleted = true), feed("c", base + 14 * HOUR), feed("f", now + MINUTE)))
        assertEquals(listOf(FeedGap(base + 14 * HOUR, 3 * HOUR)), CareStats.feedGaps(CareStats.feedStarts(file.events, until = now)))
        assertNull(CareStats.usualGap(List(4) { HOUR }))
        assertEquals(HOUR, CareStats.usualGap(List(5) { HOUR }))
        // U47: 울음 경보 에피소드만 센다. 소리 바꾸기 요청이 섞여도 리포트 줄·패턴·7일 막대가 같은 수다.
        val r = report().let { b ->
            b.copy(startedAt = iso(at(9, 0)), endedAt = iso(at(9, 40)), timeline = b.timeline.map { it.copy(startedAt = iso(at(9, 10))) } +
                b.timeline.first().copy(episodeId = "sw", trigger = EpisodeTrigger.SWITCH_SOUND, startedAt = iso(at(9, 20))))
        }
        val cries = CareStats.cryAlarms(listOf(r)).size
        assertEquals(1, cries)
        assertEquals("울음 ${cries}번", reportRowSub(r))
        assertEquals(cries, carePattern(CareFile(), listOf(r), now, zone).carCryEpisodes)
        assertEquals(cries, carCryWeek(listOf(r), now, zone).total)
        // 시각을 읽지 못한 울음: 리포트 줄 수에는 들고(iOS reportSub와 같다), 시각이 필요한 집계에서는 빠진다.
        val broken = r.copy(timeline = r.timeline.map { if (it.trigger == EpisodeTrigger.CRY_ALARM) it.copy(startedAt = "x") else it })
        assertEquals("울음 1번", reportRowSub(broken))
        assertEquals(0, CareStats.cryAlarms(listOf(broken)).size)
    }

    @Test
    fun `방법 성적 순서는 판정할 수 있는 것 먼저 그친 비율 순 같으면 받은 순`() {
        fun m(label: String, ok: Int, n: Int) = MethodStat(SoundMethod.WHITE_NOISE, label, ok, n)
        val order = CareStats.methodOrder(
            listOf(m("두 번", 2, 2), m("반", 2, 4), m("셋 다", 3, 3), m("넘침", 4, 3), m("음수", -1, 3), m("모두", 4, 4)),
        )
        // 3회 경계: 2회는 100%여도 뒤. 넘침(4/3)은 3/3으로 묶여 셋 다·모두와 같은 비율이라 받은 순, 음수는 0으로 묶여 맨 뒤(판정 가능 안에서).
        assertEquals(listOf("셋 다", "넘침", "모두", "반", "음수", "두 번"), order.map { it.value.label })
        assertEquals(listOf(2, 3, 5, 1, 4, 0), order.map { it.index })
        assertTrue(CareStats.methodOrder(emptyList()).isEmpty())
    }
}
