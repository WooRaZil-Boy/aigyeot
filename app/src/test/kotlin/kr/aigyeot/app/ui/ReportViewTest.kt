package kr.aigyeot.app.ui

import kr.aigyeot.app.store.db.SessionEntity
import kr.aigyeot.app.ui.car3d.Callout3d
import kr.aigyeot.shared.CarAction
import kr.aigyeot.shared.CarActionKind
import kr.aigyeot.shared.ControlRecord
import kr.aigyeot.shared.EpisodeResult
import kr.aigyeot.shared.EpisodeTrigger
import kr.aigyeot.shared.Gear
import kr.aigyeot.shared.HandoffKind
import kr.aigyeot.shared.MethodStat
import kr.aigyeot.shared.OutputRoute
import kr.aigyeot.shared.PlayEntry
import kr.aigyeot.shared.PlayPurpose
import kr.aigyeot.shared.PreCryContext
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.SecondAction
import kr.aigyeot.shared.SecondAttempt
import kr.aigyeot.shared.SoundMethod
import kr.aigyeot.shared.SoundRef
import kr.aigyeot.shared.Steering
import kr.aigyeot.shared.SteeringUnavailableReason
import kr.aigyeot.shared.StopInfo
import kr.aigyeot.shared.StopReason
import kr.aigyeot.shared.TimelineEpisode
import java.time.ZoneId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 리포트 화면 상태(목록 행·숫자 줄·차트 데이터·자세히)를 만드는 순수 함수. 값이 없으면 숫자를 만들지 않는다.
class ReportViewTest {
    @BeforeTest fun zone() { TimeText.zone = ZoneId.of("Asia/Seoul") }

    private fun t(hms: String) = "2026-11-09T$hms+09:00"
    private fun ms(hms: String) = epochMs(t(hms))!!
    private fun play(p: PlayPurpose, m: SoundMethod, a: String, b: String, s: Int) = PlayEntry(p, m, null, t(a), t(b), s)
    private fun ep(
        plays: List<PlayEntry> = emptyList(), lat: Double? = 2.4, result: EpisodeResult? = null, recried: Boolean = false,
        second: SecondAttempt? = null, stop: StopInfo? = null, trigger: EpisodeTrigger = EpisodeTrigger.CRY_ALARM, at: String = "13:24:00", cry: Int = 30,
    ) = TimelineEpisode("E", trigger, t(at), lat, plays.firstOrNull()?.let { SoundRef(it.method, "자장가") }, plays.sumOf { it.playS }, plays, result, null, recried, second, stop, cry)
    private fun body(
        timeline: List<TimelineEpisode> = emptyList(), actions: List<CarAction> = emptyList(), steering: Steering = Steering(false, null, null, null, null, SteeringUnavailableReason.NO_CRY),
        stats: List<MethodStat>? = null, route: OutputRoute = OutputRoute.REAR_ZONE, sim: Boolean = false,
    ) = ReportSummaryBody("S", t("13:10:00"), t("13:52:00"), sim, route, null, timeline, emptyList(), steering, actions, emptyList(), stats)

    private val first = play(PlayPurpose.AUTO_FIRST, SoundMethod.LULLABY, "13:24:02", "13:24:58", 56)
    private val second = play(PlayPurpose.AUTO_SECOND, SoundMethod.WHITE_NOISE, "13:25:06", "13:26:02", 56)

    @Test fun reportRows_titleSubAndOpenSession() {
        fun s(id: String, end: Long?, scene: String?) = SessionEntity(id, ms("13:10:00"), end, "T", null, null, 0, 0, false, "[]", null, null, false, scene, 10)
        val rows = reportRows(listOf(s("a", null, null), s("b", ms("13:52:00"), null), s("c", ms("13:52:00"), "sc"))) { if (it == "b") 0 else 2 }
        assertEquals(listOf("11월 9일(월) 오후 1:10", "11월 9일(월) 오후 1:10–1:52", "11월 9일(월) 오후 1:10–1:52"), rows.map { it.title })
        assertEquals(listOf("주행 중", "울음 기록 없음", "울음 2번 · 시뮬레이션"), rows.map { it.sub })
        assertEquals(listOf(false, true, true), rows.map { it.enabled })
        assertEquals("", reportRows(listOf(s("b", ms("13:52:00"), null))).single().sub, "울음 수를 모르면 만들지 않는다")
    }

    @Test fun cryCount_onlyAlarmEpisodes() {
        assertEquals(1, cryCount(body(listOf(ep(), ep(trigger = EpisodeTrigger.SWITCH_SOUND, lat = null)))))
    }

    @Test fun stats_fourCells_cryTimeIsTotalCrySumLikePhone() {
        val b = body(
            listOf(ep(listOf(first), result = EpisodeResult.SUCCESS)),
            listOf(CarAction(CarActionKind.REAR_WINDOW_LOCK, 2, null), CarAction(CarActionKind.SEAT_HEATER_OFF, 1, null), CarAction(CarActionKind.REAR_PLAYBACK, 1, 56)),
        )
        assertEquals(
            listOf(Stat("울음", "1번", "오후 1:24"), Stat("감지까지", "2.4초"), Stat("울음 시간", "30초"), Stat("차가 한 일", "3가지")),
            reportStats(b),
        )   // total_cry_s 30(폰 '울음 시간'과 같은 값)
        val all = reportStats(body(listOf(ep(listOf(first)), ep(listOf(second)), ep(trigger = EpisodeTrigger.SWITCH_SOUND, lat = null, cry = 12))))
        assertEquals("1분 12초", all.single { it.label == "울음 시간" }.value, "타임라인 전체의 total_cry_s를 더한다(30 + 30 + 12초, 폰 reportKpis와 같음)")
        assertEquals("2번", all.first().value, "울음 n번은 울음 경보 에피소드만")
    }

    @Test fun stats_missingValuesDropCells() {
        val s = reportStats(body(listOf(ep(listOf(first), lat = null, result = EpisodeResult.FAILURE))))
        assertEquals(listOf("울음", "울음 시간", "차가 한 일"), s.map { it.label }, "지연이 없으면 칸을 뺀다")
        assertEquals(listOf(Stat("울음", "0번", null), Stat("울음 시간", "0초"), Stat("차가 한 일", "0가지")), reportStats(body()))
    }

    @Test fun episodeChart_onlyRecordedTimesOnAxis_outcomesOffAxis() {
        val c = episodeChart(ep(listOf(first, second), result = EpisodeResult.FAILURE, recried = true, second = SecondAttempt(SecondAction.OTHER_SOUND, null, EpisodeResult.SUCCESS)))!!
        assertEquals(listOf(SoundSeg(2, 58, "자장가", 56), SoundSeg(58, 66, null, 8), SoundSeg(66, 122, "백색소음", 56)), c.sounds)   // 0초 = 경보 시각
        assertEquals(listOf(ChartMark(0, "감지 2.4초"), ChartMark(66, "다시 울음")), c.marks, "축 위에는 시각을 아는 표시만(감지, 자동 2차 시작)")
        assertEquals(listOf("울음 30초", "그쳤어요"), c.outcomes, "그친 시각은 기록에 없어 축 밖에")
        assertEquals(122, c.totalS)
        assertEquals("자장가 56초", soundTip(c.sounds[0])); assertEquals("8초", soundTip(c.sounds[1]), "듣기 틈은 띠의 이름 칸이 이미 말한다")
    }

    @Test fun episodeChart_noInventedTimes() {
        val cool = episodeChart(ep(listOf(first), result = EpisodeResult.FAILURE, recried = true, second = SecondAttempt(SecondAction.COMFORT_COOL, null, null)))!!
        assertEquals(listOf(ChartMark(0, "감지 2.4초")), cool.marks, "자동 2차 재생이 없으면 다시 울음 시각을 만들지 않는다")
        assertEquals(listOf("울음 30초"), cool.outcomes)
        assertEquals(emptyList(), episodeChart(ep(listOf(first), cry = 0))!!.outcomes, "들은 울음이 0이면 글자도 없다")
        assertNull(episodeChart(ep(lat = 2.4)), "재생이 없으면 띠가 없다(경보 지연을 울음 구간으로 그리지 않는다)")
        val sw = episodeChart(ep(listOf(first), lat = null, trigger = EpisodeTrigger.SWITCH_SOUND))!!
        assertEquals(listOf(ChartMark(0, "소리 바꾸기")), sw.marks, "소리 바꾸기는 울음 감지로 보이지 않는다")
    }

    @Test fun episodeChart_restOfferOnlyWhenTold() {
        fun stop(told: Boolean?) = StopInfo(true, StopReason.ATTEMPT_LIMIT, HandoffKind.REST_OFFER, "n 휴게소", false, false, told)
        assertTrue("쉼터 제안" in episodeChart(ep(listOf(first), stop = stop(true)))!!.outcomes)
        assertTrue("쉼터 제안" !in episodeChart(ep(listOf(first), stop = stop(false)))!!.outcomes, "묻지 못했으면 표시하지 않는다(C130)")
        assertTrue("쉼터 제안" !in episodeChart(ep(listOf(first), stop = stop(null)), settling = true)!!.outcomes, "확정 전(C185)")
        assertTrue(episodeChart(ep(listOf(first), stop = stop(true)))!!.marks.none { it.label == "쉼터 제안" }, "결과는 축 위에 찍지 않는다")
    }

    @Test fun steerBars_missingValuesDropped_unavailableGivesReason() {
        assertNull(steerBars(Steering(false, null, null, null, null, SteeringUnavailableReason.NO_CRY)))
        assertEquals("울음이 없어 비교하지 않았어요", reportView(body(), false).steerText)
        assertEquals("비교할 구간이 없어요", reportView(body(steering = Steering(false, null, null, null, null, SteeringUnavailableReason.NO_COMPARABLE_WINDOW)), false).steerText)
        assertEquals(listOf(SteerBar("평소", 0.8, false), SteerBar("울음 중", 2.1, true)), steerBars(Steering(true, 0.8, 2.1, null, null, null)), "대응 뒤가 없으면 막대를 뺀다")
        assertEquals("0.8", steerValue(0.8))
    }

    @Test fun methodDots_underMinShownUnknown_knownFirst() {
        val d = methodDots(listOf(MethodStat(SoundMethod.LULLABY, "자장가", 2, 2), MethodStat(SoundMethod.WOMB, "뱃속 소리", 1, 4), MethodStat(SoundMethod.WHITE_NOISE, "백색소음", 6, 7)))
        assertEquals(listOf("백색소음", "뱃속 소리", "자장가"), d.map { it.name })
        assertEquals(listOf(true, true, false), d.map { it.known }, "3번 미만은 아직 모름(statMinShown)")
        assertEquals(emptyList(), methodDots(null))
    }

    @Test fun reportView_titleCalloutsAndDetailsKeepFacts() {
        val stop = StopInfo(true, StopReason.ATTEMPT_LIMIT, HandoffKind.NO_REST, null, false, true, false)
        val b = body(
            listOf(ep(listOf(first), result = EpisodeResult.FAILURE, recried = true, stop = stop)),
            listOf(CarAction(CarActionKind.REAR_WINDOW_LOCK, 2, null), CarAction(CarActionKind.AMBIENT_DIM, 1, null), CarAction(CarActionKind.REAR_PLAYBACK, 1, 56), CarAction(CarActionKind.SEAT_HEATER_OFF, 0, null)),
            route = OutputRoute.DEFAULT_ZONE_FALLBACK, sim = true,
        ).copy(
            truncated = true,
            preCryContext = listOf(PreCryContext("E", 30, 62.0, 70.5, 1, 3, false, Gear.D, null, null, false)),
            controls = listOf(ControlRecord("Seat.setHvacSeatTemperature", "ROW_2_LEFT", 1, 1, 0, 0)),
        )
        val v = reportView(b, settling = false)
        assertEquals("11월 9일(월) 오후 1:10–1:52 · 42분", v.title)
        assertEquals(setOf(Callout3d.LOCK, Callout3d.DIM), v.callouts, "3D 칩은 실제로 한 것만")
        assertEquals(listOf("뒷창 잠금", "조명 낮춤", "뒷자리에만 재생"), v.actions.map { it.name }, "0번은 한 일이 아니다")
        assertEquals("1번 · 56초", v.actions.last().extra)
        assertEquals("에피소드 · 오후 1:24부터", v.episodes.single().title)
        val lines = v.details.map { it.text }
        for (want in listOf(
            "시뮬레이션 입력으로 만든 기록이에요", "뒷좌석 출력이 없어 앞에서도 들렸어요", "오래된 에피소드는 줄였어요", "오후 1:24 울음 감지 (지연 2.4초)",
            "· 자동 1차 자장가 오후 1:24–1:24 56초", "결과: 계속 울었어요", "다시 울었어요",
            "정차: 두 번 시도 뒤 · 안전한 곳에 세우도록 안내하지 못했어요 · 다른 보호자에게 알림", "울음 30초",
            "평균 62.0km/h · 최고 70.5km/h · 브레이크 1번", "Seat.setHvacSeatTemperature ROW_2_LEFT: 성공 1 · 실패 0 · 재시도 0",
        )) assertTrue(want in lines, "자세히에 '$want'가 있어야 한다: $lines")
    }

    @Test fun dayTips_chartClockOnly() {
        val car = DayLane("차에서", DataColor.CAR, "주행 42분", "울음 1번", blocks = listOf(790..832), diamonds = listOf(804))
        assertEquals("탑승 13:10–13:52 · 42분", dayBlockTip(car, 790..832))
        assertEquals("차에서 울음 13:24", dayDiamondTip(car, 804))
        assertEquals("잠 00:00–03:10 · 3시간 10분", dayBlockTip(DayLane("잠", DataColor.SLEEP, "4번", "8시간 5분"), 0..190))
        assertEquals("기저귀 03:38", dayDotTip(DayLane("기저귀", DataColor.DIAPER, null, "4번"), 218))
    }

    // 시스템 글자 200%: 이름 끝 + 간격 + 값 폭이 줄 폭을 넘으면 이름 위·값 아래로 나누고, 높이는 잰 글자 높이의 합(겹치지 않음)
    @Test fun dayHead_stacksWhenNameAndValueDoNotFit() {
        val one = dayHead(nameRight = 150f, nameH = 29f, nameBase = 23f, rightW = 300f, rightH = 33f, rightBase = 26f, w = 935f, gap = 16f, minH = 32f)
        assertEquals(false, one.stacked); assertEquals(one.nameBase, one.rightBase)
        assertTrue(one.height >= 33f, "한 줄이면 높은 글자 높이 이상")
        val big = dayHead(nameRight = 260f, nameH = 58f, nameBase = 46f, rightW = 700f, rightH = 66f, rightBase = 52f, w = 935f, gap = 32f, minH = 32f)
        assertEquals(true, big.stacked)
        assertEquals(58f + 66f, big.height); assertEquals(46f, big.nameBase); assertEquals(58f + 52f, big.rightBase, "값은 이름 아래 줄")
    }
}
