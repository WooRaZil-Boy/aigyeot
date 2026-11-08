package kr.aigyeot.app.link

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kr.aigyeot.engine.Boot
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.CabinTemp
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.DriverAddWaypoint
import kr.aigyeot.engine.DriverStop
import kr.aigyeot.engine.Engine
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.EpisodeTrigger
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FirstTry
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.MicPath
import kr.aigyeot.engine.MicService
import kr.aigyeot.engine.PlaybackStarted
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.Qa
import kr.aigyeot.engine.QaReply
import kr.aigyeot.engine.RearDoor
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.RouteState
import kr.aigyeot.engine.Side
import kr.aigyeot.engine.SwitchSound
import kr.aigyeot.engine.Via
import kr.aigyeot.engine.fresh
import kr.aigyeot.shared.LlmDiaper
import kr.aigyeot.shared.LlmResponse
import kr.aigyeot.shared.LlmSleep
import kr.aigyeot.shared.LlmState
import kr.aigyeot.shared.LlmToolCall
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// contracts-link 8.8 ④ 상황판 호출, 명세서 9장(상태값·숫자 검사·오프라인 문장·3초).
@RunWith(RobolectricTestRunner::class)
class LinkQaTest : LinkKit() {
    override val qaWaitMs = 300L
    private val song = Method(MethodKind.SONG, SA)

    /** 판단 코어를 실제로 돌려 만든 상태: 주행 중 경보로 1차 시도(ATTEMPT) 재생 중, 경보 시각 = T0 + 2분. [alarm]이 거짓이면 경보 없이 소리 바꾸기로 연 에피소드. */
    private fun crying(alarm: Boolean = true): EngineState {
        val eng = Engine()
        var s = EngineState.fresh(
            7, Profile("2026-03", CarSeat.LEFT, 180, false), listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY), song),
            List(7) { FirstTry(T0 - 1_000_000, Method(MethodKind.LULLABY), it < 6) }, T0 - 170 * 60_000L, 0,
        )
        fun ev(e: Event) { s = eng.step(s, e).state }
        ev(Boot(T0, Gear.P, 10)); ev(MicService(T0, true, MicPath.HOME_BUTTON))
        ev(RearDoor(T0, Side.LEFT, true)); ev(RearDoor(T0, Side.LEFT, false)); ev(GearChanged(T0, Gear.D))
        ev(RouteState(T0, false, null, listOf(RestCandidate("r1", "○○휴게소", RestKind.REST_AREA, 7.23, true))))
        ev(CabinTemp(T0, 27.46))
        if (alarm) ev(CrySegment(T0 + 120_000, T0 + 117_000, T0 + 120_000, 0.9, true))
        else ev(SwitchSound(T0 + 120_000, Method(MethodKind.LULLABY)))
        ev(PlaybackStarted(T0 + 121_000, s.session!!.play!!.id, "bus100_audio_zone_1", false))   // 에피소드는 재생이 시작될 때 열린다
        check(s.session!!.episode != null)
        return s
    }

    private fun ask(text: String = "뭐가 제일 잘 통해?") = runBlocking { link.qa(Qa("L1", text)) }
    private fun reply(text: String, vararg tools: LlmToolCall) = LlmResponse(text, tools.toList(), if (tools.isEmpty()) "end_turn" else "tool_use", "m", "p2", 1)
    private fun tool(name: String, vararg kv: Pair<String, String>) = LlmToolCall(name, buildJsonObject { kv.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })

    @Test fun llmState_fromEngineState() {
        profile(nickname = "지우")
        engineState = crying()
        now = T0 + 150_000
        relay.llmRes = { Res.Ok(200, reply("")) }
        ask("지우가 왜 울어?")
        val req = relay.llmCalls.single()
        assertEquals("아기가 왜 울어?", req.question)   // 별명은 '아기'로(G2)
        val st = req.state
        assertEquals(1, st.cry.currentMin)              // 경보 30초 뒤 = 1분째
        assertEquals(1, st.cry.countThisDrive)          // 저장 전 에피소드도 센다
        assertEquals(172, st.feeding!!.elapsedMin); assertEquals(180, st.feeding!!.intervalMin)
        assertEquals(2, st.carseatMin)
        assertEquals(27.5, st.cabinTemp)
        assertEquals(7.2, st.nextRest!!.distanceKm); assertEquals("r1", st.nextRest!!.id)
        val lullaby = st.methodStats.single { it.label == "자장가" }
        assertEquals(6, lullaby.successes); assertEquals(7, lullaby.attempts)
        assertEquals("부모 노래", st.methodStats.last().label)   // 곡 이름은 보내지 않는다
        assertNull(st.lastNightWakeups)
    }

    @Test fun c118_switchSoundWomb_mapsToWombMethod() {
        engineState = crying()
        relay.llmRes = { Res.Ok(200, reply("", tool("switch_sound", "method" to "womb"))) }
        ask("뱃속 소리 틀어줘")
        assertEquals(SwitchSound(T0, Method(MethodKind.WOMB), "L1"), events[0])
    }

    @Test fun c118_methodStats_womb_labelAndMethod() {
        engineState = crying().let { it.copy(session = it.session!!.copy(methods = it.session!!.methods + Method(MethodKind.WOMB)), history = it.history + List(3) { i -> FirstTry(T0 - 1_000_000, Method(MethodKind.WOMB), i < 2) }) }
        relay.llmRes = { Res.Ok(200, reply("")) }
        ask()
        val w = relay.llmCalls.single().state.methodStats.single { it.label == "뱃속 소리" }
        assertEquals(kr.aigyeot.shared.SoundMethod.WOMB, w.method)
        assertEquals(2, w.successes); assertEquals(3, w.attempts)
    }

    @Test fun groundedText_passesThrough() {
        engineState = crying()
        relay.llmRes = { Res.Ok(200, reply("자장가를 틀었을 때 7번 중 6번 그쳤어요. 소리 덕분인지는 알 수 없어요.")) }
        ask()
        assertEquals(listOf<Event>(QaReply(T0, "L1", "자장가를 틀었을 때 7번 중 6번 그쳤어요. 소리 덕분인지는 알 수 없어요.")), events.toList())
    }

    @Test fun numberNotInState_offlineSentence_toolsStillPosted() {
        engineState = crying()
        relay.llmRes = { Res.Ok(200, reply("2시간 50분 지났어요. 바꿀게요.", tool("switch_sound", "method" to "white_noise"))) }
        ask("뭐가 잘 통해?")
        assertEquals(SwitchSound(T0, Method(MethodKind.WHITE_NOISE), "L1"), events[0])
        assertEquals("자장가를 틀었을 때 7번 중 6번 그쳤어요. 소리 덕분인지는 알 수 없어요.", (events[1] as QaReply).text)
    }

    @Test fun stateClaim_offlineSentence_toolsStillPosted() {
        engineState = crying()
        // 숫자는 없지만 관찰하지 않은 상태를 단정한다(C108): 숫자 검사 탈락과 같은 오프라인 문장 경로
        relay.llmRes = { Res.Ok(200, reply("아기가 잘 자고 있어요. 바꿀게요.", tool("switch_sound", "method" to "white_noise"))) }
        ask("뭐가 잘 통해?")
        assertEquals(SwitchSound(T0, Method(MethodKind.WHITE_NOISE), "L1"), events[0])
        assertEquals("자장가를 틀었을 때 7번 중 6번 그쳤어요. 소리 덕분인지는 알 수 없어요.", (events[1] as QaReply).text)
        events.clear()
        relay.llmRes = { Res.Ok(200, reply("울음이 들리지 않아요.")) }   // 관찰한 사실만 말하면 그대로 나간다
        ask("지금 울어?")
        assertEquals(listOf<Event>(QaReply(T0, "L1", "울음이 들리지 않아요.")), events.toList())
    }

    @Test fun c108_recordClaim_offlineSentenceEvenWithSleepState() {
        now = N                                                  // stateWith와 같은 시각이라 답의 숫자가 상태값과 맞는다
        engineState = crying().copy(care = careNow(sleeping = false))
        val st = stateWith(careNow(sleeping = false))
        val llm = "기록상 ${st.sleep!!.wokeMinAgo}분 전에 깼어요."
        assertTrue(kr.aigyeot.engine.numbersGrounded(llm, stateJson(st)))   // 숫자 검사는 통과하고 noStateClaim에서 걸린다
        relay.llmRes = { Res.Ok(200, reply(llm)) }
        ask("언제 깼어?")
        val text = (events.single() as QaReply).text
        assertEquals(offlineReply("언제 깼어?", st), text); assertTrue(text != llm, text)
    }

    @Test fun l61_summaryFromYesterday_dropsDayCounts() {
        val seoul = java.time.ZoneId.of("Asia/Seoul")
        val c = crying()
        fun st(asOf: String, nowAt: String) = llmState(c.copy(care = careNow(asOf = instantOf(asOf)!!)), instantOf(nowAt)!!, 3, seoul)
        val same = st("2025-11-05T23:50:00+09:00", "2025-11-05T23:59:00+09:00")
        assertEquals(3, same.feedsToday); assertEquals(430, same.sleep!!.todaySleepMin); assertEquals(2, same.diaper!!.todayCount); assertEquals(2, same.lastNightWakeups)
        val next = st("2025-11-05T23:55:00+09:00", "2025-11-06T00:05:00+09:00")
        assertNull(next.feedsToday); assertNull(next.sleep!!.todaySleepMin); assertNull(next.diaper!!.todayCount); assertNull(next.lastNightWakeups)
        assertEquals(10, next.careAsOfMinAgo); assertNotNull(next.diaper!!.minAgo); assertNotNull(next.sleep!!.sleepingSinceMin)   // 나머지 칸은 그대로
    }

    @Test fun offline_jamkkanIsNotSleep() {
        val st = sleepSt(40, null, 35).copy(feeding = kr.aigyeot.shared.LlmFeeding(170, 180))
        assertEquals("마지막 수유 시작부터 170분 지났어요.", offlineReply("잠깐, 수유 언제 했어?", st))
        assertEquals("마지막 수유 시작부터 170분 지났어요.", offlineReply("잠시만 수유 언제야", st))
        assertEquals("기록상 40분 전에 시작한 잠이 아직 끝나지 않았어요.", offlineReply("잠 언제 잤어?", st))
        assertEquals(offlineReply("얼마나 울었어?", st), offlineReply("잠잠해지기 전까지 얼마나 울었어?", st))   // 잠잠은 잠 질문이 아니라 울음 행
        assertTrue("잠 기록" !in offlineReply("잠잠해지기 전까지 얼마나 울었어?", st))
    }

    @Test fun toolOnly_fixedSentences_parentSongIsCandidate() {
        engineState = crying()
        relay.llmRes = { Res.Ok(200, reply("", tool("switch_sound", "method" to "parent_song"))) }
        ask("우리 노래 틀어줘")
        assertEquals(listOf(SwitchSound(T0, song, "L1"), QaReply(T0, "L1", "소리를 바꿀게요.")), events.toList())
        events.clear()
        relay.llmRes = { Res.Ok(200, reply("", tool("stop_soothing"))) }
        ask("그만 좀 해줄래")
        assertEquals(listOf(DriverStop(T0, Via.TOOL, "L1"), QaReply(T0, "L1", "소리를 멈출게요.")), events.toList())
        events.clear()
        relay.llmRes = { Res.Ok(200, reply("", tool("add_waypoint", "rest_id" to "r1"))) }
        ask("거기 들르자")
        assertEquals(listOf(DriverAddWaypoint(T0, "r1", "L1"), QaReply(T0, "L1", "")), events.toList())   // 판단 코어가 S09·S19로 답한다
    }

    @Test fun relayFailure_offlineSentence() {
        engineState = crying()
        for (r in listOf(Res.Err(502, "llm_upstream", null), Res.Err(422, "llm_refused", null), Res.Err(429, "rate_limited", 10), Res.Offline)) {
            events.clear()
            relay.llmRes = { r }
            ask("다음 수유실 어디야?")
            assertEquals(listOf<Event>(QaReply(T0, "L1", "○○휴게소까지 7.2km예요. 수유실이 있어요.")), events.toList())
        }
    }

    @Test fun lateAnswer_offlineAtDeadline_lateToolsDropped() {
        engineState = crying()
        relay.llmRes = { delay(1_000); Res.Ok(200, reply("", tool("switch_sound", "method" to "lullaby"))) }
        val t = System.nanoTime()
        ask("언제 먹였지?")
        assertTrue((System.nanoTime() - t) / 1_000_000 < 900)   // 기다림(시험 300ms)에 끝난다
        Thread.sleep(1_200)                                      // 늦은 답이 와도
        assertEquals(listOf<Event>(QaReply(T0, "L1", "마지막 수유 시작부터 170분 지났어요.")), events.toList())
    }

    @Test fun offlineReply_fiveKeywords_useOnlyStateNumbers() {
        val st = llmState(crying(), T0 + 150_000, 3)
        val replies = listOf("왜 울어", "언제 먹였어", "얼마나 울었어", "뭐가 잘 통해", "다음 수유실", "노래 제목이 뭐야").map { offlineReply(it, st) }
        assertEquals(
            listOf(
                "마지막 수유 시작부터 172분, 카시트에 2분째예요. 배고프거나 불편할 수 있어요.",
                "마지막 수유 시작부터 172분 지났어요.",
                "지금 1분째 울고 있어요. 이번 주행에서 3번 울었어요.",
                "자장가를 틀었을 때 7번 중 6번 그쳤어요. 소리 덕분인지는 알 수 없어요.",
                "○○휴게소까지 7.2km예요. 수유실이 있어요.",
                "지금은 답을 가져오지 못했어요.",
            ),
            replies,
        )
        replies.forEach { r ->
            assertTrue(kr.aigyeot.engine.numbersGrounded(r, stateJson(st)), r)
            listOf("잠들", "배고파서", "그쳤다").forEach { banned -> assertTrue(banned !in r, r) }
        }
        val quiet = st.copy(cry = st.cry.copy(currentMin = null), feeding = null, nextRest = null, methodStats = emptyList())
        assertEquals("지금은 울음이 들리지 않아요. 이번 주행에서 3번 울었어요.", offlineReply("얼마나 울었어", quiet))
        assertEquals("수유 기록이 없어요.", offlineReply("언제 먹였어", quiet))
        assertEquals("아직 기록이 적어서 몰라요.", offlineReply("뭐가 잘 통해", quiet))
        assertEquals("가까운 쉼터 정보가 없어요.", offlineReply("휴게소", quiet))
    }

    @Test fun switchSoundEpisode_notCounted_asCrying() {
        val s = crying(alarm = false)
        assertEquals(EpisodeTrigger.SWITCH_SOUND, s.session!!.episode!!.trigger)   // 소리 바꾸기로 연 에피소드가 맞다
        assertNull(llmState(s, T0 + 150_000, 0).cry.currentMin)
        assertEquals(1, llmState(crying(), T0 + 150_000, 0).cry.currentMin)       // 경보 에피소드는 그대로
    }

    @Test fun parentSong_withoutCandidate_noToolSentenceS20() {
        val st = llmState(null, T0, 0)
        val call = reply("아무 말 7번.", tool("switch_sound", "method" to "parent_song"))
        for (m in listOf(emptyList(), listOf(Method(MethodKind.SONG)), listOf(Method(MethodKind.WHITE_NOISE)))) {
            val (tools, text) = qaEvents(call, "L1", T0, st, "노래 틀어줘", m)
            assertTrue(tools.isEmpty()); assertEquals("지금은 소리를 틀 수 없어요.", text)
        }
        val other = reply("", tool("switch_sound", "method" to "parent_song"), tool("stop_soothing"))
        assertEquals(listOf<Event>(DriverStop(T0, Via.TOOL, "L1")), qaEvents(other, "L1", T0, st, "q", emptyList()).first)   // 다른 도구는 그대로
    }

    @Test fun llmCallThrows_stillOneOfflineReply() {
        engineState = crying()
        relay.llmRes = { error("예상 못 한 오류") }
        ask("다음 수유실 어디야?")
        assertEquals(listOf<Event>(QaReply(T0, "L1", "○○휴게소까지 7.2km예요. 수유실이 있어요.")), events.toList())
    }

    // ---- 폰 돌봄 기록 요약(C119, L61) ----
    private val N = T0 + 211_000                         // 경보 에피소드 시작(T0+121초)부터 90초 = 2분째
    private fun careNow(asOfAgoMin: Long = 10, enabled: Boolean = true, sleeping: Boolean = true, asOf: Long = N - asOfAgoMin * MINUTE) = CareView(
        asOf, enabled,
        if (enabled) asOf - 160 * MINUTE else null, if (enabled) asOf - 160 * MINUTE + 35 * MINUTE + 30_000 else null,   // 35분 30초 잠
        if (enabled && sleeping) asOf - 30 * MINUTE else null,
        if (enabled) 430 else null, if (enabled) 2 else null, if (enabled) N - 235 * MINUTE else null, if (enabled) 2 else null, 3,
    )
    private fun stateWith(care: CareView?): LlmState {
        val c = crying()
        val ses = c.session!!.copy(methods = listOf(Method(MethodKind.LULLABY), Method(MethodKind.WHITE_NOISE)), carseatFrom = N - 52 * MINUTE)
        val hist = List(7) { FirstTry(T0 - 1_000_000, Method(MethodKind.LULLABY), it < 6) } + List(6) { FirstTry(T0 - 1_000_000, Method(MethodKind.WHITE_NOISE), it < 2) }
        return llmState(c.copy(session = ses, history = hist, feedingStart = N - 170 * MINUTE, care = care), N, 3)
    }
    private fun fixtureState() = kotlinx.serialization.json.Json.parseToJsonElement(java.io.File("../shared/src/test/resources/protocol/api_llm_request_care.json").readText())
        .let { it as kotlinx.serialization.json.JsonObject }.getValue("state")

    @Test fun l61_careState_equalsFixtureState() {
        assertEquals(fixtureState(), stateJson(stateWith(careNow())))
    }

    @Test fun l61_summaryOlderThan24h_omittedAndWakeupsNull() {
        val atEdge = stateWith(careNow(asOf = N - 24 * 60 * MINUTE))              // 정확히 24시간은 쓴다
        assertEquals(1440, atEdge.careAsOfMinAgo); assertNotNull(atEdge.sleep)   // 어제 것이라 오늘·지난밤 값은 빠진다(l61_summaryFromYesterday)
        val over = stateWith(careNow(asOf = N - 24 * 60 * MINUTE - 1))
        assertNull(over.sleep); assertNull(over.diaper); assertNull(over.feedsToday); assertNull(over.careAsOfMinAgo); assertNull(over.lastNightWakeups)
        val none = stateWith(null)
        assertNull(none.sleep); assertNull(none.feedsToday); assertNull(none.careAsOfMinAgo)
        val json = stateJson(over).toString()
        listOf("\"sleep\"", "\"diaper\"", "feeds_today", "care_as_of_min_ago").forEach { assertTrue(it !in json, it) }   // 키 자체가 빠진다
    }

    @Test fun l61_sleepDiaperOff_dropsSleepDiaperKeepsFeeds() {
        val st = stateWith(careNow(enabled = false))
        assertNull(st.sleep); assertNull(st.diaper); assertNull(st.lastNightWakeups)
        assertEquals(3, st.feedsToday); assertEquals(10, st.careAsOfMinAgo)
    }

    @Test fun l61_sleepInProgress_wokeMinAgoNull_endedSleep_hasWoke() {
        val on = stateWith(careNow()).sleep!!
        assertEquals(40, on.sleepingSinceMin); assertNull(on.wokeMinAgo); assertEquals(35, on.lastSleepMin)
        val ended = stateWith(careNow(sleeping = false)).sleep!!
        assertNull(ended.sleepingSinceMin); assertEquals(134, ended.wokeMinAgo); assertEquals(35, ended.lastSleepMin)
    }

    private fun sleepSt(sinceMin: Int?, woke: Int?, last: Int?, asOfAgo: Int = 10) =
        stateWith(null).copy(sleep = LlmSleep(sinceMin, woke, last, 430), diaper = LlmDiaper(235, 2), feedsToday = 3, careAsOfMinAgo = asOfAgo)

    @Test fun l61_offline_diaperRow() {
        val st = sleepSt(40, null, 35)
        assertEquals("기록상 마지막 기저귀는 235분 전이에요. 오늘 2번이에요.", offlineReply("기저귀 언제 갈았어?", st))
        assertEquals("기저귀 기록이 없어요.", offlineReply("기저귀", st.copy(diaper = null)))
        assertEquals("기저귀 기록이 없어요.", offlineReply("기저귀", st.copy(diaper = LlmDiaper(null, null))))
    }

    @Test fun l61_offline_sleepRow() {
        assertEquals("기록상 40분 전에 시작한 잠이 아직 끝나지 않았어요.", offlineReply("언제 잤어?", sleepSt(40, null, 35)))
        assertEquals("기록상 134분 전에 깼어요. 마지막 잠은 35분이었어요.", offlineReply("언제 깼어?", sleepSt(null, 134, 35)))
        assertEquals("잠 기록이 없어요.", offlineReply("잠", sleepSt(null, null, null)))
        assertEquals("잠 기록이 없어요.", offlineReply("잠", stateWith(null)))
        assertEquals("기록상 134분 전에 깼어요. 마지막 잠은 35분이었어요.", offlineReply("얼마나 잤어?", sleepSt(null, 134, 35)))   // 4행(얼마나)보다 3행이 먼저
    }

    @Test fun l61_offline_whyRow_withWoke() {
        assertEquals("마지막 수유 시작부터 170분, 카시트에 52분째이고, 기록상 134분 전에 깼어요. 배고프거나 불편할 수 있어요.", offlineReply("왜 울어?", sleepSt(null, 134, 35)))
        assertEquals("카시트에 52분째이고, 기록상 134분 전에 깼어요. 불편할 수 있어요.", offlineReply("왜 울어?", sleepSt(null, 134, 35).copy(feeding = null)))
        assertEquals("마지막 수유 시작부터 170분, 카시트에 52분째예요. 배고프거나 불편할 수 있어요.", offlineReply("왜 울어?", sleepSt(40, null, 35)))   // woke 없으면 그대로
    }

    @Test fun l61_offline_oldRecordPrefix_over60only() {
        assertEquals("61분 전 기록 기준으로, 기록상 마지막 기저귀는 235분 전이에요. 오늘 2번이에요.", offlineReply("기저귀", sleepSt(40, null, 35, 61)))
        assertEquals("기록상 마지막 기저귀는 235분 전이에요. 오늘 2번이에요.", offlineReply("기저귀", sleepSt(40, null, 35, 60)))
        assertEquals("200분 전 기록 기준으로, 기록상 40분 전에 시작한 잠이 아직 끝나지 않았어요.", offlineReply("잠", sleepSt(40, null, 35, 200)))
        assertTrue(offlineReply("왜 울어?", sleepSt(null, 134, 35, 90)).startsWith("90분 전 기록 기준으로, 마지막 수유"))
        assertEquals("마지막 수유 시작부터 170분 지났어요.", offlineReply("먹였어?", sleepSt(40, null, 35, 300)))   // 돌봄 칸을 안 쓴 문장은 붙이지 않는다
    }

    @Test fun l61_offline_keywordOrder() {
        val st = sleepSt(null, 134, 35)
        assertTrue(offlineReply("수유실 기저귀 잠", st).startsWith("○○휴게소까지"))             // 1 수유실
        assertTrue(offlineReply("기저귀 잠", st).startsWith("기록상 마지막 기저귀"))              // 2 기저귀
        assertTrue(offlineReply("잠 얼마나", st).startsWith("기록상 134분 전에 깼어요"))         // 3 잠
        assertTrue(offlineReply("얼마나 먹였어", st).startsWith("지금 "))                         // 4 얼마나
        assertTrue(offlineReply("먹였어 왜", st).startsWith("마지막 수유 시작부터 170분 지났"))   // 5 먹였 > 7 왜
    }

    @Test fun l61_offline_numbersAreGrounded() {
        val st = stateWith(careNow())
        for (q in listOf("기저귀", "잠", "왜 울어")) assertTrue(kr.aigyeot.engine.numbersGrounded(offlineReply(q, st), stateJson(st)), q)
    }
}
