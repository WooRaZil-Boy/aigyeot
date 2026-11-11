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
import kr.aigyeot.engine.HandoffCause
import kr.aigyeot.engine.HandoffRecord
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.InputLost
import kr.aigyeot.engine.PauseReason
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.MicPath
import kr.aigyeot.engine.MicService
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.PlaybackStarted
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.Qa
import kr.aigyeot.engine.spokenMin
import kr.aigyeot.engine.QaReply
import kr.aigyeot.engine.RearDoor
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.RouteState
import kr.aigyeot.engine.Side
import kr.aigyeot.engine.Stage
import kr.aigyeot.engine.SwitchSound
import kr.aigyeot.engine.Tick
import kr.aigyeot.engine.Via
import kr.aigyeot.engine.cryView
import kr.aigyeot.engine.fresh
import kr.aigyeot.shared.LlmDiaper
import kr.aigyeot.shared.LlmFeeding
import kr.aigyeot.shared.LlmResponse
import kr.aigyeot.shared.LlmSleep
import kr.aigyeot.shared.LlmCry
import kr.aigyeot.shared.LlmState
import kr.aigyeot.shared.LlmToolCall
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    /**
     * C288: 감지로 '지금 울고 있음'인 상태. crying()의 경보(T0+120초)가 운전자 중단(WAIT, 울음을 듣는 단계) 뒤에도 [until]까지 한 걸음마다 이어진다.
     * 울음의 처음은 T0+120초. crying() 그대로는 자동 시도 재생 중이라 '확인할 수 없음'이다.
     */
    private fun cryingNow(until: Long = T0 + 211_000): EngineState {
        var s = step(crying(), DriverStop(T0 + 121_000, Via.VOICE))
        var t = T0 + 121_500                                              // 첫 경보(T0+120초)와 문턱(두 걸음) 안
        while (t <= until) { s = step(s, CrySegment(t, t - 3_000, t, 0.9, true)); t += 975 }
        return s
    }

    private fun ask(text: String = "뭐가 제일 잘 통해?") = runBlocking { link.qa(Qa("L1", text)) }
    private fun reply(text: String, vararg tools: LlmToolCall) = LlmResponse(text, tools.toList(), if (tools.isEmpty()) "end_turn" else "tool_use", "m", "p2", 1)
    private fun answer(vararg topics: String) = LlmToolCall("answer", buildJsonObject { put("topics", kotlinx.serialization.json.JsonArray(topics.map(::JsonPrimitive))) })
    private fun tool(name: String, vararg kv: Pair<String, String>) = LlmToolCall(name, buildJsonObject { kv.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })

    @Test fun llmState_fromEngineState() {
        profile(nickname = "지우")
        engineState = cryingNow(T0 + 150_000)   // C288: 경보가 이어지는 중(자동 시도 재생 중이면 '확인할 수 없음')
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
        assertEquals("우리 노래", st.methodStats.last().label)   // 곡 이름은 보내지 않는다
        assertNull(st.lastNightWakeups)
    }

    // C320 V17: ④ 방법별 성적도 28일 창(홈·리포트와 같은 값)
    @Test fun c320_methodStats_window28Days() {
        val m = Method(MethodKind.WOMB)
        val since = T0 - 28 * kr.aigyeot.engine.DAY_MS
        val c = crying()
        val st = c.copy(session = c.session!!.copy(methods = listOf(m)), history = listOf(FirstTry(since - 1, m, true), FirstTry(since, m, true), FirstTry(T0, m, false)))
        val s = llmState(st, T0, 0).methodStats.single()
        assertEquals(1, s.successes); assertEquals(2, s.attempts)
    }

    // C218: 사실 주제 문장은 답이 도착한 순간 상태로 짠다(LLM에는 질문 때 상태를 보낸다)
    @Test fun c218_careChangeDuringThinking_factAnswerFromLatestState() {
        profile()
        engineState = crying()
        now = T0 + 150_000
        relay.llmRes = { now += 600_000; Res.Ok(200, reply("x", answer("feeding"))) }   // 기다리는 사이 10분이 흐른다(상태가 바뀜)
        ask("오늘 몇 번 먹었어?")
        assertEquals(172, relay.llmCalls.single().state.feeding!!.elapsedMin, "LLM에는 질문 때 상태")
        (events.single() as QaReply).let { assertEquals(listOf("feeding"), it.factTopics, "사실 주제만 싣는다"); assertEquals("", it.text, "문장은 호스트가 큐에서 짠다(C221)") }
    }

    @Test fun c218_staleLlmText_replacedByFallback() {
        val st = stateWith(careNow())
        fun out(r: LlmResponse, q: String = "q") = qaEventsCare(r, "L1", T0, st, q, emptyList())
        out(reply("불편할 수 있어요.", answer("why"))).let { assertTrue(it.llm); assertEquals("", it.fallback); assertEquals(listOf("why"), it.fallbackTopics); assertTrue(it.fallbackCare) }   // C227: 글 대신 주제
        out(reply("불편할 수 있어요.", answer("unknown"))).let { assertTrue(it.llm); assertEquals("그건 알 수 없어요.", it.fallback) }
        out(reply("아기가 불편할 수 있어요."), "q").let { assertTrue(it.llm); assertEquals(offlineReply("q", st), it.fallback); assertEquals(emptyList(), it.fallbackTopics); assertEquals(offlineCareUsed("q"), it.fallbackCare) }
        out(reply("아기가 불편할 수 있어요."), "왜 울어?").let { assertEquals("", it.fallback); assertEquals(listOf("why"), it.fallbackTopics) }
        out(reply("x", answer("feeding"))).let { assertEquals(false, it.llm, "주제 문장은 LLM 글이 아니다"); assertEquals("", it.fallback) }
    }

    @Test fun c227_whyTextAndOfflineWhy_carriedAsTopic() {
        val st = stateWith(careNow())
        qaEventsCare(reply("아기가 배고파서 울어요.", answer("why")), "L1", T0, st, "q", emptyList()).let { assertEquals("", it.second); assertEquals(listOf("why"), it.factTopics) }
        assertEquals(listOf("why"), offlineFactTopics("왜 울어?"))
    }

    @Test fun c122_unconfirmedNotice_noLlmRequest_offlineReply() {
        profile(noticeVersion = 0)
        engineState = crying()
        relay.llmRes = { Res.Ok(200, reply("AI 답")) }
        ask("뭐가 잘 통해?")
        assertEquals(0, relay.llmCalls.size)
        assertEquals("자장가를 틀었을 때 7번 중 6번 그쳤어요. 소리 덕분인지는 알 수 없어요.", (events.single() as QaReply).said())
    }

    @Test fun c122_noProfile_noLlmRequest_offlineReply() {
        engineState = crying()
        relay.llmRes = { Res.Ok(200, reply("AI 답")) }
        ask("뭐가 잘 통해?")
        assertEquals(0, relay.llmCalls.size)
        assertTrue(events.single() is QaReply)
    }

    @Test fun c122_currentNotice_sendsLlmRequest() {
        profile()
        engineState = crying()
        relay.llmRes = { Res.Ok(200, reply("")) }
        ask()
        assertEquals(1, relay.llmCalls.size)
    }

    @Test fun c118_switchSoundWomb_mapsToWombMethod() {
        profile()   // C122: 동의를 기록한 프로필이 있어야 AI에 묻는다
        engineState = crying()
        relay.llmRes = { Res.Ok(200, reply("", tool("switch_sound", "method" to "womb"))) }
        ask("뱃속 소리 틀어줘")
        assertEquals(SwitchSound(T0, Method(MethodKind.WOMB), "L1"), events[0])
    }

    @Test fun c118_methodStats_womb_labelAndMethod() {
        profile()   // C122: 동의를 기록한 프로필이 있어야 AI에 묻는다
        engineState = crying().let { it.copy(session = it.session!!.copy(methods = it.session!!.methods + Method(MethodKind.WOMB)), history = it.history + List(3) { i -> FirstTry(T0 - 1_000_000, Method(MethodKind.WOMB), i < 2) }) }
        relay.llmRes = { Res.Ok(200, reply("")) }
        ask()
        val w = relay.llmCalls.single().state.methodStats.single { it.label == "뱃속 소리" }
        assertEquals(kr.aigyeot.shared.SoundMethod.WOMB, w.method)
        assertEquals(2, w.successes); assertEquals(3, w.attempts)
    }

    // C281: 질문 '자?'·'자니'·'자나'는 잠 주제, 'LLM이 쓴 "지금 자요."'는 사실 단정이라 막힌다
    @Test fun c281_sleepQuestionForms_andStateClaim() {
        for (q in listOf("아기 자?", "애기 자니", "자나?")) assertEquals("sleep", offlineTopic(q), q)
        assertEquals("sleep", questionTopic("자나?"))
        val st = sleepAll()
        assertEquals("그건 알 수 없어요.", say(reply("지금 자요.", answer("unknown")), "q", st))
        assertEquals("그건 알 수 없어요.", say(reply("잘 자네요.", answer("unknown")), "q", st))
    }

    // C311: 조각 나누기가 '?'를 떼면 표의 '자?'에 닿지 못해 시연 질문이 "알 수 없어요"가 됐다
    @Test fun c311_sleepQuestionWithQuestionMark() {
        assertEquals("sleep", questionTopic("아기 자?"))
        val st = sleepAll()
        assertEquals(fallbackReply(listOf("sleep"), st), say(reply("모르겠어요.", answer("unknown")), "아기 자?", st))
    }

    @Test fun c311_mixedQuestions_firstPieceKept() {
        assertEquals("sleep", questionTopic("아기 자? 아직 울어?"))
        assertEquals(listOf("sleep"), offlineFactTopics("아기 자? 아직 울어?"))
    }

    // C311: 표의 모든 낱말은 질문 정규화 + 조각 나누기를 거친 뒤에도 그 행으로 분류된다(낱말 그대로·'아기 ' + 낱말·뒤에 다른 조각)
    @Test fun c311_everyKeywordReachableAfterSplit() {
        for ((topic, ws) in TOPIC_KEYWORDS) for (w in ws) for (q in listOf(w, "아기 $w", "아기 $w? 아직 안 와", "$w!")) {
            val exp = TOPIC_KEYWORDS.first { (_, xs) -> xs.any { it in w } }.first   // 낱말이 다른 행 낱말을 품으면 앞 행이 우선
            assertEquals(exp, questionTopic(q), "$topic/$w/$q")
        }
    }

    @Test fun c216_careUsed_byTopic() {
        val st = stateWith(careNow())
        fun care(call: LlmResponse, q: String = "q") = qaEventsCare(call, "L1", T0, st, q, emptyList()).third
        assertEquals(false, care(reply("x", answer("cabin_temp"))))
        assertEquals(false, care(reply("x", answer("carseat", "cry"))))
        assertEquals(true, care(reply("x", answer("sleep"))))
        assertEquals(true, care(reply("x", answer("cabin_temp", "feeding"))))
        assertEquals(true, care(reply("불편할 수 있어요.", answer("why"))))
        assertEquals(true, care(reply("아기가 불편할 수 있어요.")), "LLM 글을 썼으면 true")
        assertEquals(false, care(reply("x 40분", answer("unknown"))))
        assertEquals(false, care(reply("x 40분"), "실내 온도 알려줘"), "오프라인 온도 문장")
        assertEquals(true, care(reply("x 40분"), "오늘 몇 번 먹었어"))
        assertEquals(false, offlineCareUsed("실내 온도 알려줘")); assertEquals(true, offlineCareUsed("잠 잤어?"))
    }

    @Test fun c216_linkPassesCareUsed() {
        profile(); engineState = crying()
        fun careOf(res: LlmResponse, q: String): Boolean { events.clear(); relay.llmRes = { Res.Ok(200, res) }; ask(q); return (events.last() as QaReply).careUsed }
        assertEquals(false, careOf(reply("x", answer("cabin_temp")), "실내 온도 알려줘"))
        assertEquals(true, careOf(reply("x", answer("feeding")), "몇 번 먹었어"))
        assertEquals(false, careOf(reply("x", tool("switch_sound", "method" to "white_noise")), "소리 바꿔"))
    }

    // C375·C376: 글에 도구 이름·영어가 새면 정해 둔 문장으로, "하지만 주변"의 '만 주'는 숫자가 아니라 글을 그대로 쓴다
    @Test fun c375_c376_leakFallsBack_wordEndingNumeralKept() {
        val st = sleepAll()
        val ok = "죄송하지만 주변 소리까지는 알 수 없어요."
        assertEquals(ok, say(reply(ok), "그냥 얘기해 줘", st))
        for (leak in listOf("answer 도구를 호출해 수유 주제를 고릅니다.", "Rendering the answer for the question.", "stop_soothing을 호출합니다.", "Sleep 주제로 알려 드릴게요.", "먼저 why 쪽으로 답을 고르겠습니다."))
            assertNotEquals(leak, say(reply(leak), "그냥 얘기해 줘", st), leak)
        assertNotEquals("세 번이에요.", say(reply("세 번이에요."), "그냥 얘기해 줘", st))   // 진짜 숫자 말은 여전히 버린다
    }

    // C377: 말하는 분 값은 60분 이상이면 시간·분으로
    @Test fun c377_spokenDuration() {
        assertEquals("0분", spokenMin(0)); assertEquals("59분", spokenMin(59)); assertEquals("1시간", spokenMin(60)); assertEquals("1시간 1분", spokenMin(61))
        assertEquals("17시간 54분", spokenMin(1074)); assertEquals("2시간", spokenMin(120))
        assertEquals("마지막 수유 시작부터 17시간 54분 지났어요.", topicSentence("feeding", sleepAll().copy(feedsToday = null, feeding = LlmFeeding(1074, 180))))
    }

    @Test fun c216_linkPassesCareUsed_offline() {
        profile(noticeVersion = 0); engineState = crying()   // C122: AI에 묻지 않는 오프라인 길
        events.clear(); ask("실내 온도 알려줘"); assertEquals(false, (events.last() as QaReply).careUsed)
        events.clear(); ask("오늘 몇 번 먹었어"); assertEquals(true, (events.last() as QaReply).careUsed)
    }

    private fun QaReply.said(alarms: Int = 0) = if (factTopics.isEmpty()) text else fallbackReply(factTopics, llmState(engineState, now, alarms))   // 호스트가 큐에서 짜는 문장(C221)
    private fun QaOut.said(st: LlmState) = if (factTopics.isEmpty()) second else fallbackReply(factTopics, st)
    private fun say(call: LlmResponse, q: String, st: LlmState) = qaEventsCare(call, "L1", T0, st, q, emptyList()).said(st)
    private val failing = "x 40분"   // 숫자 검사에 걸리는 글 → 대체 답

    // C164: 잠이 모두 있는 상태(마지막 잠 끝 134분 전, 길이 35분, 오늘 430분, 지난밤 깸 2번)
    private fun sleepAll(asOfAgo: Int = 10) = sleepSt(null, 134, 35, asOfAgo).copy(lastNightWakeups = 2)

    private val sleepFull = "기록상 2시간 14분 전에 잠이 끝났고, 마지막 잠은 35분, 오늘은 모두 7시간 10분, 지난밤에는 2번 깼어요."

    // C172: 질문 낱말로 조각을 고르지 않는다. 같은 주제는 질문이 달라도 같은 한 문장
    @Test fun c172_sleepOneSentenceHasAllValues() {
        val st = sleepAll()
        for (q in listOf("마지막 잠은 얼마나 잤어?", "오늘 얼마나 잤어?", "지난밤에 몇 번 깼어?", "잠 언제 끝났어?")) {
            assertEquals(sleepFull, say(reply(failing, answer("sleep")), q, st), q)
            assertEquals(sleepFull, offlineReply(q, st), q)
        }
        assertEquals("기록상 40분 전에 시작한 잠이 아직 끝나지 않았고, 오늘은 모두 7시간 10분, 지난밤에는 2번 깼어요.", topicSentence("sleep", sleepSt(40, null, 35).copy(lastNightWakeups = 2)))
        assertEquals("기록상 오늘 잠은 모두 7시간 10분이에요.", topicSentence("sleep", sleepSt(null, null, null)))
        assertEquals("기록상 지난밤에 2번 깼어요.", topicSentence("sleep", stateWith(null).copy(lastNightWakeups = 2)))
        assertEquals("마지막 수유 시작부터 2시간 50분 지났고, 기록상 오늘 3번 먹었어요.", topicSentence("feeding", sleepAll()))
        assertEquals("기록상 마지막 기저귀는 3시간 55분 전이고, 오늘 2번이에요.", topicSentence("diaper", sleepAll()))
    }

    @Test fun c172_twoTopicsTwoSentences() {
        val st = sleepAll().copy(diaper = LlmDiaper(235, 4))
        // 낱말이 섞여도 주제별 값이 모두 나온다(재현 2)
        val two = "기록상 마지막 기저귀는 3시간 55분 전이고, 오늘 4번이에요. 마지막 수유 시작부터 2시간 50분 지났고, 기록상 오늘 3번 먹었어요."
        assertEquals(two, say(reply(failing, answer("diaper", "feeding")), "오늘 기저귀 몇 번 갈았고 마지막 수유는 언제 했어?", st))
        assertEquals(2, say(reply(failing, answer("feeding", "sleep", "diaper")), "q", st).count { it == '.' })   // 앞 두 주제까지
        assertEquals(sleepFull + " 마지막 수유 시작부터 2시간 50분 지났고, 기록상 오늘 3번 먹었어요.", say(reply(failing, answer("sleep", "feeding")), "q", st))
    }

    @Test fun c172_cryHasCount() {
        val st = sleepAll().copy(cry = LlmCry(2, 3), cabinTemp = 27.5)
        assertEquals("지금 2분째 울고 있고, 이번 주행에서 3번 울었어요. 실내 온도는 27.5도예요.", say(reply(failing, answer("cry", "cabin_temp")), "이번 주행에서 몇 번 울었고 실내 온도는?", st))
        assertEquals("지금은 울음이 들리지 않고, 이번 주행에서 3번 울었어요.", say(reply(failing, answer("cry")), "q", st.copy(cry = LlmCry(null, 3))))
    }

    @Test fun c172_restHasNursingRoom() {
        val st = llmState(crying(), T0, 3).copy(cry = LlmCry(2, 3))
        assertEquals("○○휴게소까지 7.2km이고 수유실이 있어요. 지금 2분째 울고 있고, 이번 주행에서 3번 울었어요.", say(reply(failing, answer("next_rest", "cry")), "다음 쉼터 수유실 있어? 몇 번 울었어?", st))
        assertEquals("○○휴게소까지 7.2km이고 수유실이 있어요.", offlineReply("수유실", st))
    }

    @Test fun c172_offlineSameSentences() {
        val st = sleepAll().copy(diaper = LlmDiaper(235, 4))
        assertEquals("기록상 마지막 기저귀는 3시간 55분 전이고, 오늘 4번이에요.", offlineReply("기저귀 몇 번 갈았어?", st))
        assertEquals(topicSentence("feeding", st), offlineReply("수유한 지 얼마나 됐어?", st))
        assertEquals(topicSentence("sleep", st), offlineReply("마지막 잠 길이는?", st))
        assertTrue(offlineReply("잠 얼마나 잤어", st).count { it == '.' } <= 2)
    }

    @Test fun c172_asOfPrefixOnce() {
        val st = sleepAll(90)
        assertEquals("마지막 수유 시작부터 2시간 50분 지났고, 1시간 30분 전 기록 기준으로 오늘 3번 먹었어요. 기록상 마지막 기저귀는 3시간 55분 전이고, 오늘 2번이에요.", say(reply(failing, answer("feeding", "diaper")), "q", st))
        assertEquals(sleepFull.replace("기록상", "1시간 30분 전 기록 기준으로") , offlineReply("오늘 얼마나 잤어?", st))
        assertEquals("마지막 수유 시작부터 2시간 50분 지났어요.", say(reply(failing, answer("feeding")), "q", st.copy(feedsToday = null)))   // 돌봄 칸이 없는 문장엔 붙지 않는다
        assertEquals(sleepFull, offlineReply("잠", sleepAll(60)))   // 60분 이하는 붙이지 않는다
    }

    // C179 ①: 기록이 오래되면 첫 돌봄 부분의 '기록상'만 시점으로 바뀐다(수유 경과·카시트 앞에는 안 붙는다)
    @Test fun c179_asOfReplacesFirstRecordWord() {
        val st = sleepAll(90)
        val at = sleepFull.replace("기록상", "1시간 30분 전 기록 기준으로")
        assertEquals("카시트에 52분째예요. $at", say(reply(failing, answer("carseat", "sleep")), "q", st))
        assertEquals("마지막 수유 시작부터 2시간 50분 지났고, 1시간 30분 전 기록 기준으로 오늘 3번 먹었어요.", say(reply(failing, answer("feeding")), "q", st))
        assertEquals("$at 마지막 수유 시작부터 2시간 50분 지났어요.", say(reply(failing, answer("sleep", "feeding")), "q", st.copy(feedsToday = null)))   // 둘째 돌봄 문장에는 바꿀 '기록상'이 없고, 경과 앞에는 안 붙는다
        assertEquals("마지막 수유 시작부터 2시간 50분 지났어요. $at", say(reply(failing, answer("feeding", "sleep")), "q", st.copy(feedsToday = null)))   // 수유가 첫 문장이어도 돌봄 값 부분이 없으면 다음 돌봄 부분에서 한 번
    }

    // C179 ②: 돌봄 문장은 늘 '기록상'으로 시작, 값이 없거나 0이면 기록 없음, 일부만 0이면 그 부분만 뺀다
    @Test fun c179_emptySleepRecordSaysNoRecord() {
        val st = stateWith(null).copy(sleep = LlmSleep(null, null, null, 0), lastNightWakeups = 0)
        assertEquals("기록상 오늘 잠 기록이 없어요.", topicSentence("sleep", st))
        assertEquals("기록상 오늘 잠 기록이 없어요.", say(reply(failing, answer("sleep")), "q", st))
        assertEquals("기록상 오늘 잠은 모두 7시간 10분이에요.", topicSentence("sleep", st.copy(sleep = LlmSleep(null, null, null, 430))))
        assertEquals("기록상 지난밤에 2번 깼어요.", topicSentence("sleep", st.copy(lastNightWakeups = 2)))
        assertEquals("기록상 2시간 14분 전에 잠이 끝났고, 마지막 잠은 35분이에요.", topicSentence("sleep", st.copy(sleep = LlmSleep(null, 134, 35, 0))))
        assertEquals("기록상 오늘 수유 기록이 없어요.", topicSentence("feeding", st.copy(feeding = null, feedsToday = 0)))
        assertEquals("마지막 수유 시작부터 2시간 50분 지났어요.", topicSentence("feeding", st.copy(feedsToday = 0)))
        assertEquals("기록상 오늘 기저귀 기록이 없어요.", topicSentence("diaper", st.copy(diaper = LlmDiaper(null, 0))))
        assertEquals("기록상 마지막 기저귀는 3시간 55분 전이에요.", topicSentence("diaper", st.copy(diaper = LlmDiaper(235, 0))))
    }

    @Test fun c179_careSentencesStartWithRecord() {
        val st = sleepAll()
        for (t in listOf("sleep", "diaper")) assertTrue(topicSentence(t, st).startsWith("기록상"), t)
        assertTrue(topicSentence("sleep", sleepSt(null, null, null)).startsWith("기록상"))
        assertTrue(topicSentence("sleep", stateWith(null).copy(lastNightWakeups = 2)).startsWith("기록상"))
    }

    // C179 ③(C165): 기저귀 숫자 칸은 min_ago·today_count 둘 다, 0도 값
    @Test fun c179_diaperCountOnlyPreviewFallsBack() {
        val st = stateWith(null).copy(diaper = LlmDiaper(null, 0))
        assertEquals("기록상 오늘 기저귀 기록이 없어요.", say(reply("오늘 기저귀 기록을 알려 드릴게요.", answer("diaper")), "q", st))
        assertEquals("기록상 오늘 기저귀는 3번이에요.", say(reply("오늘 기저귀 기록을 알려 드릴게요.", answer("diaper")), "q", st.copy(diaper = LlmDiaper(null, 3))))
    }

    // C186 ①: 칸이 null(요약 없음·24시간 넘음·잠·기저귀 끔)이면 확인할 수 없다고 말한다. 유효한 요약의 0만 '기록상 … 없어요'
    @Test fun c186_nullCareSaysCannotConfirm() {
        val none = stateWith(null).copy(feeding = null)
        for (st in listOf(none, stateWith(careNow(asOfAgoMin = 25 * 60)).copy(feeding = null), stateWith(careNow(enabled = false)).copy(feeding = null))) {
            assertEquals("잠 기록을 확인할 수 없어요.", say(reply(failing, answer("sleep")), "q", st))
            assertEquals("기저귀 기록을 확인할 수 없어요.", topicSentence("diaper", st))
        }
        assertEquals("수유 기록을 확인할 수 없어요.", topicSentence("feeding", none))
        assertEquals("수유 기록을 확인할 수 없어요.", topicSentence("feeding", stateWith(careNow(asOfAgoMin = 25 * 60)).copy(feeding = null)))
        assertEquals("마지막 수유 시작부터 2시간 50분 지났어요.", topicSentence("feeding", stateWith(null)))   // 경과가 있으면 그것만
    }

    // 어제 요약 같은 꼴: sleep·diaper 객체는 있지만 안의 값이 모두 null. 시점 안내도 붙지 않는다
    @Test fun c186_allNullInsideSleepSaysCannotConfirm() {
        val st = stateWith(null).copy(sleep = LlmSleep(null, null, null, null), diaper = LlmDiaper(null, null), lastNightWakeups = null, careAsOfMinAgo = 90)
        assertEquals("잠 기록을 확인할 수 없어요.", topicSentence("sleep", st))
        assertEquals("잠 기록을 확인할 수 없어요.", say(reply(failing, answer("sleep")), "q", st))
        assertEquals("기저귀 기록을 확인할 수 없어요.", say(reply(failing, answer("diaper")), "q", st))
        assertEquals("1시간 30분 전 기록 기준으로 오늘 잠 기록이 없어요.", topicSentence("sleep", st.copy(lastNightWakeups = 0)))
    }

    @Test fun c186_zeroFromValidSummarySaysNoRecord() {
        val st = stateWith(careNow().copy(todayFeedCount = 0, todaySleepMin = 0, nightWakeups = 0, todayDiaperCount = 0, lastDiaperAt = null, lastSleepStartAt = null, lastSleepEndAt = null, sleepingSince = null)).copy(feeding = null)
        assertEquals("기록상 오늘 잠 기록이 없어요.", topicSentence("sleep", st))
        assertEquals("기록상 오늘 기저귀 기록이 없어요.", topicSentence("diaper", st))
        assertEquals("기록상 오늘 수유 기록이 없어요.", topicSentence("feeding", st))
    }

    // C186 ②: 유효한 요약의 0으로 기록 없음을 말해도 돌봄 기록을 쓴 것이라 오래된 요약이면 시점이 붙는다
    @Test fun c186_staleZeroRecordGetsAsOf() {
        val st = stateWith(careNow(asOfAgoMin = 90).copy(todayFeedCount = 0)).copy(feeding = null)
        assertEquals("1시간 30분 전 기록 기준으로 오늘 수유 기록이 없어요.", say(reply(failing, answer("feeding")), "q", st))
        assertEquals("1시간 30분 전 기록 기준으로 오늘 수유 기록이 없어요.", topicSentence("feeding", st))
    }

    @Test fun c129_stateClaimText_topicSentence_orOffline() {
        val st = stateWith(careNow())
        fun say(q: String, vararg t: String) = qaEventsCare(reply("아기가 잘 자고 있어요.", *(if (t.isEmpty()) emptyArray() else arrayOf(answer(*t)))), "L1", T0, st, q, emptyList()).said(st)
        assertEquals("카시트에 52분째예요.", say("q", "carseat"))
        assertEquals("카시트에 52분째예요.", say("카시트"))           // 주제도 없으면 질문 키워드로 고른 주제 문장
        assertEquals(fallbackReply(listOf("why"), st), say("q", "why"))   // why만이면 글이 상태 단정 검사에 걸려 why 주제 문장
    }

    // ---- C194: 사실 주제는 차의 주제 문장, 사실 아닌 주제만 LLM 글(숫자 없음·상태 단정 없음) ----
    @Test fun c194_factTopicUsesTopicSentence_notLlmText() {
        val st = stateWith(careNow())
        // 글의 숫자가 상태값과 뒤바뀌어 있어도(수유 3분, 오늘 170번) 차의 주제 문장을 말한다
        val call = reply("마지막 수유는 3분 전이고 오늘 170번 먹었어요.", answer("feeding"))
        assertEquals("마지막 수유 시작부터 2시간 50분 지났고, 기록상 오늘 3번 먹었어요.", say(call, "q", st))
        assertEquals("카시트에 52분째예요.", say(reply("카시트에 마흔 분째예요.", answer("carseat")), "q", st))   // 숫자 없는 글도 쓰지 않는다
    }

    @Test fun c194_noAnswerTool_questionKeywordFactTopic_usesTopicSentence() {
        val st = sleepAll()
        val got = say(reply("기록상 오늘 잠 기록이 없어요."), "오늘 얼마나 잤어?", st)
        assertEquals(topicSentence("sleep", st), got)
        assertTrue("기록이 없어요" !in got)
    }

    @Test fun c194_noAnswerTool_withSwitchTool_keywordDoesNotHideToolSentence() {
        assertEquals("소리를 바꿀게요.", say(reply("", tool("switch_sound", "method" to "white_noise")), "잠 잘 오는 소리로 바꿔 줘", sleepAll()))
    }

    @Test fun c194_whyOnly_llmTextWithoutDigits_spoken() {
        val t = "배가 고프거나 불편할 수 있어요."
        assertEquals(t, say(reply(t, answer("why")), "왜 울어?", sleepAll()))
        assertEquals(t, say(reply(t, answer("unknown")), "q", sleepAll()))
        assertEquals(t, say(reply(t), "왜 울어?", sleepAll()))   // answer 없이 키워드 주제 why여도 글 검사를 통과하면 그 글
    }

    @Test fun c194_whyOnly_llmTextWithDigitsOrKoreanCount_fallsBackToWhySentence() {
        val st = sleepAll()
        val whySentence = fallbackReply(listOf("why"), st)
        assertEquals(whySentence, say(reply("3분째 울고 있어서 불편할 수 있어요.", answer("why")), "q", st))
        assertEquals(whySentence, say(reply("세 번 울어서 불편할 수 있어요.", answer("why")), "q", st))
        assertEquals(whySentence, say(reply("두 시간 동안 불편할 수 있어요.", answer("why")), "q", st))
        assertEquals(whySentence, say(reply("아기가 배고파서 울어요.", answer("why")), "q", st))   // 상태 단정
        assertEquals(whySentence, say(reply("낯선 소리에 십여 분 동안 불편할 수 있어요.", answer("why")), "q", st))   // C249: 한자어 수 + 여 + 단위
        assertEquals("그건 알 수 없어요.", say(reply("세 번 울었어요.", answer("unknown")), "q", st))
    }

    @Test fun c194_mixedFactAndWhy_factSentencesOnly() {
        val st = stateWith(careNow())
        assertEquals("카시트에 52분째예요.", say(reply("불편할 수 있어요.", answer("why", "carseat")), "q", st))
        assertEquals("카시트에 52분째예요. 실내 온도는 27.5도예요.", say(reply("불편할 수 있어요.", answer("carseat", "why", "cabin_temp")), "q", st))
    }

    @Test fun c129_multipleAnswerCalls_merged() {
        val st = stateWith(careNow())
        val call = reply("x 40분", answer("carseat"), answer("cabin_temp"), answer("carseat"))   // 글은 쓰지 않는다(C194)
        assertEquals("카시트에 52분째예요. 실내 온도는 27.5도예요.", qaEventsCare(call, "L1", T0, st, "q", emptyList()).said(st))
    }

    @Test fun c129_passingText_withSwitchSound_spoken_failing_fixed() {
        val st = stateWith(careNow())
        val sw = tool("switch_sound", "method" to "white_noise")
        assertEquals("소리를 바꿀게요.", qaEvents(reply("백색소음으로 바꿀게요.", sw), "L1", T0, st, "q", emptyList()).second)
        assertEquals("소리를 바꿀게요.", qaEvents(reply("백색소음으로 바꿀게요. 괜찮아요.", sw), "L1", T0, st, "q", emptyList()).second)
    }

    @Test fun c127_topics_orderedDistinctKnownMaxThree() {
        val st = stateWith(careNow())
        fun say(vararg t: String) = qaEventsCare(reply("x 40분", answer(*t)), "L1", T0, st, "q", emptyList()).said(st)
        assertEquals("카시트에 52분째예요.", say("carseat", "unknown"))   // 사실 주제가 있으면 unknown은 말하지 않는다(C194)
        assertEquals("카시트에 52분째예요. 실내 온도는 27.5도예요.", say("carseat", "carseat", "엉뚱", "cabin_temp"))   // 겹침·모르는 값은 버린다
        assertEquals("카시트에 52분째예요. 실내 온도는 27.5도예요.", say("carseat", "cabin_temp", "unknown", "cry"))   // 앞 두 주제까지, 주제마다 한 문장(C172)
        assertEquals("그건 알 수 없어요.", say("unknown"))
        assertEquals("카시트에 52분째예요.", qaEventsCare(reply("x 40분", answer("엉뚱")), "L1", T0, st, "카시트", emptyList()).said(st))   // 알려진 주제가 하나도 없으면 오프라인 문장
    }

    // C329: 세션이 없으면 이번 주행 횟수가 없다. 세션이 있으면(마이크 일시정지여도) 지금 문장 그대로
    @Test fun c329_noSession_cryAnswerOnlyUnavailable() {
        val none = llmState(crying().copy(session = null), N, 0)
        assertNull(none.carseatMin)
        assertEquals("지금은 울음을 확인할 수 없어요.", topicSentence("cry", none))
        assertEquals("지금은 울음을 확인할 수 없어요.", offlineReply("아직 울어?", none), "오프라인·대체 답도 같은 함수")
        val two = qaEventsCare(reply("x 40분", answer("cry", "cabin_temp")), "L1", T0, none, "q", emptyList()).said(none)
        assertTrue(two.startsWith("지금은 울음을 확인할 수 없어요."), two)
        val paused = llmState(step(crying(false), InputLost(T0 + 130_000, PauseReason.MIC_ERROR)), N, 0)
        assertEquals("지금은 울음을 확인할 수 없고, 이번 주행에서 0번 울었어요.", topicSentence("cry", paused))
    }

    @Test fun c234_noSessionCarseatUnknown() {
        val none = llmState(crying().copy(session = null, feedingStart = N - 170 * MINUTE), N, 0)
        assertNull(none.carseatMin)   // 세션이 없으면 카시트 분을 재지 않았다
        assertEquals("카시트 시간은 출발한 뒤부터 재요.", topicSentence("carseat", none))
        val st = stateWith(null)
        assertEquals("마지막 수유 시작부터 2시간 50분, 카시트에 52분째예요. 배고프거나 불편할 수 있어요.", topicSentence("why", st))
        assertEquals("마지막 수유 시작부터 2시간 50분이에요. 배고프거나 불편할 수 있어요.", topicSentence("why", st.copy(carseatMin = null)))
        assertEquals("불편할 수 있어요.", topicSentence("why", st.copy(carseatMin = null, feeding = null)))
        assertEquals("기록상 2시간 14분 전에 깼어요. 불편할 수 있어요.", topicSentence("why", sleepSt(null, 134, 35).copy(carseatMin = null, feeding = null)))
    }

    @Test fun c224_restNameWithPeriodNotCut() {
        val st = stateWith(careNow()).copy(nextRest = kr.aigyeot.shared.LlmNextRest("r1", "EXAMPLE(구.이름)휴게소", 7.2, true))
        assertEquals("EXAMPLE(구.이름)휴게소까지 7.2km이고 수유실이 있어요. 실내 온도는 27.5도예요.", fallbackReply(listOf("next_rest", "cabin_temp"), st))
        assertEquals("EXAMPLE(구.이름)휴게소까지 7.2km이고 수유실이 있어요.", fallbackReply(listOf("next_rest"), st))
        // 문장이 둘인 고정 문장 주제는 둘을 함께 말할 때 앞 문장만
        assertEquals("카시트에 52분째예요. 실내 온도는 27.5도예요.", fallbackReply(listOf("carseat", "cabin_temp"), st))
        // 두 문장 주제(sound_stats·why)는 다른 주제와 함께 말할 때 앞 문장만 쓴다(TWO_SENTENCE_TOPICS 절단 분기)
        assertEquals("자장가를 틀었을 때 7번 중 6번 그쳤어요. 카시트에 52분째예요.", fallbackReply(listOf("sound_stats", "carseat"), st))
        assertEquals("마지막 수유 시작부터 2시간 50분, 카시트에 52분째예요. 카시트에 52분째예요.", fallbackReply(listOf("why", "carseat"), st))
    }

    @Test fun c127_newTopics_carseatCabinTempSleepFeeding() {
        val st = sleepSt(null, 134, 35).copy(lastNightWakeups = 2, cabinTemp = null)
        assertEquals("실내 온도를 알 수 없어요.", topicSentence("cabin_temp", st))
        assertEquals("실내 온도는 27.5도예요.", topicSentence("cabin_temp", st.copy(cabinTemp = 27.5)))
        assertEquals("기록상 2시간 14분 전에 잠이 끝났고, 마지막 잠은 35분, 오늘은 모두 7시간 10분, 지난밤에는 2번 깼어요.", topicSentence("sleep", st))
        assertEquals("기록상 40분 전에 시작한 잠이 아직 끝나지 않았고, 오늘은 모두 7시간 10분, 지난밤에는 2번 깼어요.", topicSentence("sleep", sleepSt(40, null, 35).copy(lastNightWakeups = 2)))
        assertEquals("잠 기록을 확인할 수 없어요.", topicSentence("sleep", st.copy(sleep = null, lastNightWakeups = null)))
        assertEquals("마지막 수유 시작부터 2시간 50분 지났고, 기록상 오늘 3번 먹었어요.", topicSentence("feeding", st))
        assertEquals("기록상 오늘 3번 먹었어요.", topicSentence("feeding", st.copy(feeding = null)))
        assertEquals("마지막 수유 시작부터 2시간 50분 지났어요.", topicSentence("feeding", st.copy(feedsToday = null)))
        assertEquals("수유 기록을 확인할 수 없어요.", topicSentence("feeding", st.copy(feeding = null, feedsToday = null)))
        assertEquals("1시간 1분 전 기록 기준으로 오늘 3번 먹었어요.", topicSentence("feeding", sleepSt(40, null, 35, 61).copy(feeding = null)))
    }

    @Test fun c129_textOnly_offlineByKeyword_toolsFixedSentences() {
        profile()   // C122
        engineState = crying()
        relay.llmRes = { Res.Ok(200, reply("아기가 잘 자고 있어요.")) }   // 도구 없이 글만: 글은 버리고 오프라인 문장
        ask("뭐가 잘 통해?")
        assertEquals(listOf<Event>(QaReply(T0, "L1", "", careUsed = false, factTopics = listOf("sound_stats"))), events.toList())
        events.clear()
        relay.llmRes = { Res.Ok(200, reply("2시간 50분 지났어요. 바꿀게요.", tool("switch_sound", "method" to "white_noise"))) }   // 다른 도구만: 글 대신 고정 문장
        ask("뭐가 잘 통해?")
        assertEquals(listOf(SwitchSound(T0, Method(MethodKind.WHITE_NOISE), "L1"), QaReply(T0, "L1", "소리를 바꿀게요.", careUsed = false)), events.toList())
        events.clear()
        relay.llmRes = { Res.Ok(200, reply("x 40분", tool("switch_sound", "method" to "white_noise"), answer("cry"))) }   // 다른 도구와 answer: 도구 사건 + 주제 문장
        ask("틀고 얼마나 울었어?")
        assertEquals(SwitchSound(T0, Method(MethodKind.WHITE_NOISE), "L1"), events[0])
        assertEquals("지금은 울음을 확인할 수 없고, 이번 주행에서 1번 울었어요.", (events[1] as QaReply).said(1), "C288: crying()은 자동 시도 재생 중")
    }

    @Test fun c127_answerWithUnplayableSong_s20Wins() {
        val st = llmState(null, T0, 0)
        val call = reply("x 40분", tool("switch_sound", "method" to "parent_song"), answer("carseat"))
        val (tools, text) = qaEvents(call, "L1", T0, st, "q", emptyList())
        assertTrue(tools.isEmpty()); assertEquals("지금은 소리를 틀 수 없어요.", text)
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

    @Test fun c126_summaryZone_decidesSameDay() {
        val seoul = java.time.ZoneId.of("Asia/Seoul")
        val asOf = instantOf("2025-11-05T23:55:00+09:00")!!; val nowAt = instantOf("2025-11-06T00:05:00+09:00")!!   // 차(서울)는 날짜가 갈리고 LA는 같은 날
        fun st(zone: String?) = llmState(crying().copy(care = careNow(asOf = asOf).copy(zone = zone)), nowAt, 3, seoul)
        assertEquals(3, st("America/Los_Angeles").feedsToday)       // 요약 시간대로 같은 날
        assertNull(st(null).feedsToday)                              // 없으면 차 시간대
        assertNull(st("엉뚱한 값").feedsToday)                       // 잘못된 값도 차 시간대
        val la = java.time.ZoneId.of("America/Los_Angeles")
        val care = careNow(asOf = instantOf("2025-11-05T23:55:00-08:00")!!)   // 반대: 차(LA)는 날짜가 갈리고 서울은 같은 날
        assertNull(llmState(crying().copy(care = care), instantOf("2025-11-06T00:05:00-08:00")!!, 3, la).feedsToday)
        assertEquals(3, llmState(crying().copy(care = care.copy(zone = "Asia/Seoul")), instantOf("2025-11-06T00:05:00-08:00")!!, 3, la).feedsToday)
    }

    @Test fun offline_c247_jamSiganIsSleep() {
        val st = sleepSt(40, null, 35)
        val want = "기록상 40분 전에 시작한 잠이 아직 끝나지 않았고, 오늘은 모두 7시간 10분이에요."
        assertEquals(want, offlineReply("오늘 잠 시간은 얼마나 돼?", st))
        assertEquals(want, offlineReply("잠 시간 알려 줘", st))
    }

    @Test fun offline_jamkkanIsNotSleep() {
        val st = sleepSt(40, null, 35).copy(feeding = kr.aigyeot.shared.LlmFeeding(170, 180))
        val feed = "마지막 수유 시작부터 2시간 50분 지났고, 기록상 오늘 3번 먹었어요."   // sleepSt는 feeds_today가 있다(C127)
        assertEquals(feed, offlineReply("잠깐, 수유 언제 했어?", st))
        assertEquals(feed, offlineReply("잠시만 수유 언제야", st))
        assertEquals("기록상 40분 전에 시작한 잠이 아직 끝나지 않았고, 오늘은 모두 7시간 10분이에요.", offlineReply("잠 언제 잤어?", st))
        assertEquals(offlineReply("얼마나 울었어?", st), offlineReply("잠잠해지기 전까지 얼마나 울었어?", st))   // 잠잠은 잠 질문이 아니라 울음 행
        assertTrue("잠 기록" !in offlineReply("잠잠해지기 전까지 얼마나 울었어?", st))
    }

    @Test fun toolOnly_fixedSentences_parentSongIsCandidate() {
        profile()   // C122: 동의를 기록한 프로필이 있어야 AI에 묻는다
        engineState = crying()
        relay.llmRes = { Res.Ok(200, reply("", tool("switch_sound", "method" to "parent_song"))) }
        ask("우리 노래 틀어줘")
        assertEquals(listOf(SwitchSound(T0, song, "L1"), QaReply(T0, "L1", "소리를 바꿀게요.", careUsed = false)), events.toList())
        events.clear()
        relay.llmRes = { Res.Ok(200, reply("", tool("stop_soothing"))) }
        ask("그만 좀 해줄래")
        assertEquals(listOf(DriverStop(T0, Via.TOOL, "L1"), QaReply(T0, "L1", "소리를 멈출게요.", careUsed = false)), events.toList())
        events.clear()
        relay.llmRes = { Res.Ok(200, reply("아기가 잘 자고 있어요.", tool("add_waypoint", "rest_id" to "r1"))) }   // 상태 단정 글은 QaReply로 나가지 않는다(검토 P3)
        ask("거기 들르자")
        assertEquals(listOf(DriverAddWaypoint(T0, "r1", "L1"), QaReply(T0, "L1", "", careUsed = false)), events.toList())   // 판단 코어가 S09·S19로 답한다
        events.clear()
        relay.llmRes = { Res.Ok(200, reply("쉼터에 들를게요.", tool("add_waypoint", "rest_id" to "r1"))) }   // 도구만이면 통과한 글도 쓰지 않는다(C206)
        ask("거기 들르자")
        assertEquals(listOf(DriverAddWaypoint(T0, "r1", "L1"), QaReply(T0, "L1", "", careUsed = false)), events.toList())
    }

    @Test fun relayFailure_offlineSentence() {
        profile()   // C122
        engineState = crying()
        for (r in listOf(Res.Err(502, "llm_upstream", null), Res.Err(422, "llm_refused", null), Res.Err(429, "rate_limited", 10), Res.Offline)) {
            events.clear()
            relay.llmRes = { r }
            ask("다음 수유실 어디야?")
            assertEquals(listOf<Event>(QaReply(T0, "L1", "", careUsed = false, factTopics = listOf("next_rest"))), events.toList())
        }
        assertEquals(4, relay.llmCalls.size)   // 네 번 모두 중계를 불렀다
    }

    @Test fun c156_offlinePathOnNetworkFailureCapped() {
        profile()
        engineState = crying().copy(care = careNow())
        assertTrue(offlineReply("잠 얼마나 잤어", llmState(engineState!!, T0, 3)).count { it == '.' } == 1)   // 잠 주제는 값을 모두 담은 한 문장(C172)
        for (r in listOf(Res.Err(502, "llm_upstream", null), Res.Offline)) {   // 실패 길
            events.clear()
            relay.llmRes = { r }
            ask("잠 얼마나 잤어")
            assertEquals(1, (events.single() as QaReply).said().count { it == '.' })
        }
        events.clear()
        relay.llmRes = { delay(1_000); Res.Ok(200, reply("x")) }   // 시간 초과 길
        ask("잠 얼마나 잤어")
        assertEquals(1, (events.single() as QaReply).said().count { it == '.' })
    }

    @Test fun lateAnswer_offlineAtDeadline_lateToolsDropped() {
        profile()   // C122
        engineState = crying()
        var called = 0
        relay.llmRes = { called++; delay(1_000); Res.Ok(200, reply("", tool("switch_sound", "method" to "lullaby"))) }
        val t = System.nanoTime()
        ask("언제 먹였지?")
        assertTrue((System.nanoTime() - t) / 1_000_000 < 900)   // 기다림(시험 300ms)에 끝난다
        Thread.sleep(1_200)                                      // 늦은 답이 와도
        assertEquals(1, called)                                  // 중계는 실제로 불렀고(늦게 끝났을 뿐, 취소돼 llmCalls에는 안 남는다)
        assertEquals(listOf<Event>(QaReply(T0, "L1", "", factTopics = listOf("feeding"))), events.toList())
    }

    @Test fun offlineReply_fiveKeywords_useOnlyStateNumbers() {
        val st = llmState(cryingNow(T0 + 150_000), T0 + 150_000, 3)   // C288: 경보가 이어지는 중
        val replies = listOf("왜 울어", "언제 먹였어", "얼마나 울었어", "뭐가 잘 통해", "다음 수유실", "노래 제목이 뭐야").map { offlineReply(it, st) }
        assertEquals(
            listOf(
                "마지막 수유 시작부터 2시간 52분, 카시트에 2분째예요. 배고프거나 불편할 수 있어요.",
                "마지막 수유 시작부터 2시간 52분 지났어요.",
                "지금 1분째 울고 있고, 이번 주행에서 3번 울었어요.",
                "자장가를 틀었을 때 7번 중 6번 그쳤어요. 소리 덕분인지는 알 수 없어요.",
                "○○휴게소까지 7.2km이고 수유실이 있어요.",
                "지금은 답을 가져오지 못했어요.",
            ),
            replies,
        )
        replies.forEach { r ->
            listOf("잠들", "배고파서", "그쳤다").forEach { banned -> assertTrue(banned !in r, r) }
        }
        val quiet = st.copy(cry = st.cry.copy(currentMin = null), feeding = null, nextRest = null, methodStats = emptyList())
        assertEquals("지금은 울음이 들리지 않고, 이번 주행에서 3번 울었어요.", offlineReply("얼마나 울었어", quiet))
        assertEquals("수유 기록을 확인할 수 없어요.", offlineReply("언제 먹였어", quiet))
        assertEquals("아직 기록이 적어서 몰라요.", offlineReply("뭐가 잘 통해", quiet))
        assertEquals("경유지로 넣을 수 있는 가까운 쉼터 정보가 없어요.", offlineReply("휴게소", quiet))
    }

    @Test fun switchSoundEpisode_notCounted_asCrying() {
        val s = crying(alarm = false)
        assertEquals(EpisodeTrigger.SWITCH_SOUND, s.session!!.episode!!.trigger)   // 소리 바꾸기로 연 에피소드가 맞다
        assertNull(llmState(s, T0 + 150_000, 0).cry.currentMin)
        assertEquals(1, llmState(cryingNow(T0 + 150_000), T0 + 150_000, 0).cry.currentMin)   // C288: 경보가 이어지면 울고 있음
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
        profile()   // C122
        engineState = crying()
        var called = 0
        relay.llmRes = { called++; error("예상 못 한 오류") }
        ask("다음 수유실 어디야?")
        assertEquals(1, called)   // 던지는 중계 호출에 실제로 들어갔다
        assertEquals(listOf<Event>(QaReply(T0, "L1", "", careUsed = false, factTopics = listOf("next_rest"))), events.toList())
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
        val c = cryingNow()   // C288: 경보가 이어지는 중(울음 처음 T0+120초 → N에 2분째, 예제 current_min 2)
        val ses = c.session!!.copy(methods = listOf(Method(MethodKind.LULLABY), Method(MethodKind.WHITE_NOISE)), openedAt = N - 52 * MINUTE)   // 카시트 시작 = 세션 시작(V15)
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
        assertEquals("기록상 마지막 기저귀는 3시간 55분 전이고, 오늘 2번이에요.", offlineReply("기저귀 언제 갈았어?", st))
        assertEquals("기저귀 기록을 확인할 수 없어요.", offlineReply("기저귀", st.copy(diaper = null)))
        assertEquals("기저귀 기록을 확인할 수 없어요.", offlineReply("기저귀", st.copy(diaper = LlmDiaper(null, null))))
    }

    @Test fun l61_offline_sleepRow() {
        assertEquals("기록상 40분 전에 시작한 잠이 아직 끝나지 않았고, 오늘은 모두 7시간 10분이에요.", offlineReply("언제 잤어?", sleepSt(40, null, 35)))
        assertEquals("기록상 2시간 14분 전에 잠이 끝났고, 마지막 잠은 35분, 오늘은 모두 7시간 10분이에요.", offlineReply("언제 깼어?", sleepSt(null, 134, 35)))
        assertEquals("기록상 오늘 잠은 모두 7시간 10분이에요.", offlineReply("잠", sleepSt(null, null, null)))   // 오늘 잠 값만 있으면 그 문장만(C127)
        assertEquals("잠 기록을 확인할 수 없어요.", offlineReply("잠", stateWith(null)))
        assertEquals("기록상 2시간 14분 전에 잠이 끝났고, 마지막 잠은 35분, 오늘은 모두 7시간 10분이에요.", offlineReply("얼마나 잤어?", sleepSt(null, 134, 35)))   // C172: 질문 낱말로 고르지 않는다
    }

    @Test fun l61_offline_whyRow_withWoke() {
        assertEquals("마지막 수유 시작부터 2시간 50분, 카시트에 52분째이고, 기록상 2시간 14분 전에 깼어요. 배고프거나 불편할 수 있어요.", offlineReply("왜 울어?", sleepSt(null, 134, 35)))
        assertEquals("카시트에 52분째이고, 기록상 2시간 14분 전에 깼어요. 불편할 수 있어요.", offlineReply("왜 울어?", sleepSt(null, 134, 35).copy(feeding = null)))
        assertEquals("마지막 수유 시작부터 2시간 50분, 카시트에 52분째예요. 배고프거나 불편할 수 있어요.", offlineReply("왜 울어?", sleepSt(40, null, 35)))   // woke 없으면 그대로
    }

    @Test fun l61_offline_oldRecordPrefix_over60only() {
        assertEquals("1시간 1분 전 기록 기준으로 마지막 기저귀는 3시간 55분 전이고, 오늘 2번이에요.", offlineReply("기저귀", sleepSt(40, null, 35, 61)))
        assertEquals("기록상 마지막 기저귀는 3시간 55분 전이고, 오늘 2번이에요.", offlineReply("기저귀", sleepSt(40, null, 35, 60)))
        assertEquals("3시간 20분 전 기록 기준으로 40분 전에 시작한 잠이 아직 끝나지 않았고, 오늘은 모두 7시간 10분이에요.", offlineReply("잠", sleepSt(40, null, 35, 200)))
        assertTrue(offlineReply("왜 울어?", sleepSt(null, 134, 35, 90)).contains("카시트에 52분째이고, 1시간 30분 전 기록 기준으로 2시간 14분 전에 깼어요"))
        assertEquals("마지막 수유 시작부터 2시간 50분 지났고, 5시간 전 기록 기준으로 오늘 3번 먹었어요.", offlineReply("먹였어?", sleepSt(40, null, 35, 300)))   // feeds_today를 쓴 문장(C127)
        assertEquals("마지막 수유 시작부터 2시간 50분 지났어요.", offlineReply("먹였어?", sleepSt(40, null, 35, 300).copy(feedsToday = null)))   // 돌봄 칸을 안 쓴 문장은 붙이지 않는다
    }

    @Test fun l61_offline_keywordOrder() {
        val st = sleepSt(null, 134, 35)
        assertTrue(offlineReply("수유실 기저귀 잠", st).startsWith("○○휴게소까지"))             // 1 수유실
        assertTrue(offlineReply("기저귀 잠", st).startsWith("기록상 마지막 기저귀"))              // 2 기저귀
        assertTrue(offlineReply("잠 언제", st).startsWith("기록상 2시간 14분 전에 잠이 끝났고"))           // 3 잠
        assertTrue(offlineReply("카시트 얼마나 먹였어", st).startsWith("카시트에 52분째"))          // 4 카시트 > 5 수유
        assertTrue(offlineReply("온도 먹였어", st).startsWith("실내 온도"))                       // 카시트 다음 온도
        assertTrue(offlineReply("얼마나 먹였어", st).startsWith("마지막 수유 시작부터"))          // 수유(C127)가 얼마나보다 먼저
        assertTrue(offlineReply("얼마나 울었어", st).startsWith("지금 "))                         // 얼마나
        assertTrue(offlineReply("먹였어 왜", st).startsWith("마지막 수유 시작부터 2시간 50분 지났"))   // 먹였 > 왜
        assertEquals("마지막 수유 시작부터 2시간 50분 지났고, 기록상 오늘 3번 먹었어요.", offlineReply("수유한 지 얼마나 됐어?", st))
        assertEquals("카시트에 52분째예요.", offlineReply("카시트에 얼마나 있었어?", st))
    }

    // C197: 시점 안내는 최종 답 글에서 처음 나오는 '기록상'에 붙는다(수유 횟수 0이라 경과만 말한 수유 문장 뒤 잠 문장)
    @Test fun c197_asOfOnFirstRecordWordInFinalText() {
        val st = sleepSt(null, 134, 35, 90).copy(feedsToday = 0)
        val t = say(reply(failing, answer("feeding", "sleep")), "q", st)
        assertTrue(t.startsWith("마지막 수유 시작부터 2시간 50분 지났어요. 1시간 30분 전 기록 기준으로 2시간 14분 전에 잠이 끝났고"), t)
        assertTrue("기록상" !in t, t)
        assertEquals(offlineReply("잠", st), topicSentence("sleep", st))
    }

    // C199: 오프라인 키워드 순서는 왜 -> 효과 -> 울음(표 7·8·9줄)
    @Test fun c199_stillCryingQuestion_usesCrySentence() {
        val st0 = stateWith(null)
        val st = st0.copy(cry = st0.cry.copy(currentMin = 2))
        val t = say(reply("지금은 울음이 들리지 않아요."), "아기 아직 울어?", st)
        assertTrue(t.startsWith("지금 2분째 울고 있고"), t)
    }

    @Test fun c199_whyCryingStillWhy() {
        assertEquals("why", offlineTopic("왜 울어?"))
        assertEquals("why", offlineTopic("왜 아직도 울어?"))
        assertEquals("sound_stats", offlineTopic("소리 효과 있어? 울음에"))
        assertEquals("cry", offlineTopic("아직 울어?"))
    }

    @Test fun c204_cryInflectionsMatched() {
        listOf("아기 아직 우니?", "그쳤어?", "아직 울어?", "계속 울고 있어?", "울음 그쳐?", "얼마나 울었어?").forEach { assertEquals("cry", offlineTopic(it), it) }
        val st = sleepAll().copy(cry = LlmCry(2, 3))
        assertEquals("지금 2분째 울고 있고, 이번 주행에서 3번 울었어요.", say(reply("지금은 울음이 들리지 않아요."), "아기 아직 우니?", st))
        assertEquals("지금 2분째 울고 있고, 이번 주행에서 3번 울었어요.", say(reply("지금은 울음이 들리지 않아요."), "그쳤어?", st))
    }

    @Test fun c204_winterNotCry() = assertEquals(null, offlineTopic("겨울에 차에서는 겉옷 어떻게 입혀?"))

    @Test fun c206_toolOnlyResponse_usesToolSentence() {
        val st = sleepAll().copy(cry = LlmCry(2, 3))
        assertEquals("소리를 바꿀게요.", say(reply("지금은 울음이 들리지 않아요. 백색소음으로 바꿀게요.", tool("switch_sound", "method" to "white_noise")), "아기 아직 울어? 백색소음으로 바꿔 줘", st))
        assertEquals("", say(reply("괜찮아 보여요", tool("add_waypoint", "rest_id" to "R1")), "q", st))
    }

    // ---- C239: 사실 키워드가 모델의 why·unknown 선택보다 앞선다 ----
    @Test fun c239_factKeywordOverridesUnknown() {
        val st = llmState(crying(), N, 0)
        val o = qaEventsCare(reply("아직 울고 있어요.", answer("unknown")), "L1", T0, st, "아직 울어?", emptyList())
        assertEquals(listOf("cry"), o.factTopics, "unknown 대신 사실 주제")
        assertEquals(false, o.llm)
        assertEquals(listOf("cry"), qaEventsCare(reply("x", answer("why")), "L1", T0, st, "아직 울어?", emptyList()).factTopics)
        assertEquals("x", qaEventsCare(reply("x", answer("why")), "L1", T0, st, "왜 울어?", emptyList()).second, "'왜'가 든 질문은 why 그대로(LLM 글 허용)")
        assertEquals(listOf("cry"), qaEventsCare(reply("", tool("switch_sound", "method" to "white_noise"), answer("unknown")), "L1", T0, st, "울음 그치게 소리 바꿔", emptyList()).factTopics, "도구와 answer가 함께면 사실 키워드를 앞세운다(C243)")
    }

    // ---- C243: 도구와 함께 온 answer(unknown) ----
    @Test fun c243_toolWithAnswerUnknown_factKeywordFirst_toolKept() {
        val st = llmState(crying(), N, 0)
        val o = qaEventsCare(reply("지금은 울음이 들리지 않아요.", tool("switch_sound", "method" to "white_noise"), answer("unknown")), "L1", T0, st, "아직 울어? 백색소음으로 바꿔 줘", emptyList())
        assertEquals("", o.second); assertEquals(listOf("cry"), o.factTopics); assertEquals(false, o.llm)
        assertTrue(o.first.any { it is SwitchSound }, "도구 사건은 그대로")
    }

    @Test fun c243_toolOnly_keepsToolSentence_c206() {
        val st = llmState(crying(), N, 0)
        assertEquals("소리를 바꿀게요.", qaEventsCare(reply("지금은 울음이 들리지 않아요.", tool("switch_sound", "method" to "white_noise")), "L1", T0, st, "아직 울어? 백색소음으로 바꿔 줘", emptyList()).second)
    }

    @Test fun c243_whyQuestionWithToolAndAnswerWhy_staysWhy() {
        val st = llmState(crying(), N, 0)
        val o = qaEventsCare(reply("", tool("switch_sound", "method" to "white_noise"), answer("why")), "L1", T0, st, "왜 울어? 백색소음으로 바꿔 줘", emptyList())
        assertEquals(listOf("why"), o.factTopics)
    }

    // ---- C255: '왜'가 없는 조각의 사실 키워드가 앞선다 ----
    @Test fun c255_factPieceBesideWhyPiece_factWins() {
        val st = llmState(crying(), N, 0)
        val o = qaEventsCare(reply("글", answer("why")), "L1", T0, st, "언제 먹였어? 왜 울어?", emptyList())
        assertEquals(listOf("feeding"), o.factTopics); assertEquals(false, o.llm);
        assertEquals(listOf("why"), qaEventsCare(reply("", answer("why")), "L1", T0, st, "왜 울어?", emptyList()).factTopics)
        assertEquals(listOf("diaper"), qaEventsCare(reply("", answer("why")), "L1", T0, st, "왜 기저귀 갈아야 해?", emptyList()).factTopics, "C263: 표에서 기저귀(2행)가 왜(7행)보다 앞이라 사실")
    }

    @Test fun c256_approxSinoAfterHangul_blocksWhyText() {
        val st = llmState(crying(), N, 0)
        val o = qaEventsCare(reply("약삼십 분 정도 쉬어 가도 좋아요.", answer("why")), "L1", T0, st, "왜 울어?", emptyList())
        assertEquals(false, o.llm); assertEquals(listOf("why"), o.factTopics)
    }

    // ---- C244: 한자어 수사 뒤 '배'는 why 글을 막는다 ----
    @Test fun c244_sinoTimes_blocksWhyText() {
        val st = llmState(crying(), N, 0)
        val o = qaEventsCare(reply("소리가 평소보다 백 배 클 수 있어요.", answer("why")), "L1", T0, st, "왜 울어?", emptyList())
        assertEquals(false, o.llm, "글 검사에 걸려 LLM 글을 쓰지 않는다"); assertEquals(listOf("why"), o.factTopics)
    }

    // ---- C245: 마이크가 꺼졌거나 입력이 끊긴 동안은 울음을 확인할 수 없다 ----
    private fun step(s: EngineState, e: Event) = Engine().step(s, e).state
    private val unavailableCry = "지금은 울음을 확인할 수 없고, 이번 주행에서 0번 울었어요."

    @Test fun c245_micError_cryUnavailable() {
        val st = llmState(step(crying(false), InputLost(T0 + 130_000, PauseReason.MIC_ERROR)), N, 0)
        assertEquals(true, st.cry.unavailable)
        assertEquals(unavailableCry, topicSentence("cry", st))
        assertEquals(unavailableCry, offlineReply("아직 울어?", st))   // 오프라인·3초 대체 답도 같은 문장
    }

    @Test fun c245_sessionPauseSilenced_beatsMinuteAndConfirming() {
        val s = step(crying(), InputLost(T0 + 130_000, PauseReason.SILENCED))
        check(s.session != null && s.car.pauses.isNotEmpty())
        val st = llmState(s, N, 1)
        assertEquals(true, st.cry.unavailable)
        assertEquals("지금은 울음을 확인할 수 없고, 이번 주행에서 1번 울었어요.", topicSentence("cry", st))
    }

    // 인계(HANDOFF) 뒤에도 경보가 이어지면 울고 있다. 호스트 큐 사실 답(composeFacts)도 llmState + fallbackReply 같은 길
    private fun handedOff(cause: HandoffCause): EngineState = cryingNow().let { s ->   // C288: 인계 뒤에도 경보가 이어진다
        val ses = s.session!!
        s.copy(session = ses.copy(stage = Stage.Handoff(ses.attemptNo, ses.quietMs), episode = ses.episode!!.copy(handoff = HandoffRecord(cause, null))))
    }

    /** C303 ②(B3): ④ 지금 울음은 판단 코어 cryView 그대로. 상한 인계도 판단 코어가 그 단계에서 울음을 보고 세므로 경보가 이어지면 울고 있다(C284 예외 없앰). */
    @Test fun c303_b3_capHandoffCryFromEngine() {
        val s = handedOff(HandoffCause.SESSION_CAP)
        val st = llmState(s, N, 1)
        assertEquals(s.cryView(N).unavailable, st.cry.unavailable)
        assertEquals(false, st.cry.unavailable, "상한 인계도 확인할 수 없음이 아니다")
        assertEquals(llmState(handedOff(HandoffCause.TWO_ATTEMPTS), N, 1).cry, st.cry, "인계 원인과 상관없이 같은 감지 기준")
        assertTrue(topicSentence("cry", st).startsWith("지금 ") && "분째 울고 있고" in topicSentence("cry", st), topicSentence("cry", st))
        assertTrue("분째 울고 있고" in fallbackReply(listOf("cry"), st))   // composeFacts가 쓰는 길
    }

    @Test fun c284_cryingHandoff_keepsMinute() {
        val st = llmState(handedOff(HandoffCause.TWO_ATTEMPTS), N, 1)
        assertEquals(false, st.cry.unavailable)
        assertTrue(topicSentence("cry", st).startsWith("지금 "), topicSentence("cry", st))
        assertTrue("분째 울고 있고" in fallbackReply(listOf("cry"), st))
    }

    // C286: 울음 상태값은 에피소드 종류가 아니라 최근 감지(이어진 경보)로 정한다
    /** 소리 바꾸기 에피소드(WAIT) 중 [from]부터 [untilMs] 동안 한 걸음(975ms)마다 경보가 온 상태. */
    private fun switchThenCry(from: Long, untilMs: Long): EngineState {
        var s = crying(alarm = false)
        var t = from
        while (t <= from + untilMs) { s = step(s, CrySegment(t, t - 3_000, t, 0.9, true)); t += 975 }
        return s
    }

    @Test fun c286_switchSoundEpisode_alarmsContinue_saysCrying() {
        val s = switchThenCry(T0 + 130_000, 70_000)
        assertEquals(EpisodeTrigger.SWITCH_SOUND, s.session!!.episode!!.trigger)
        val last = s.session!!.alarmRun!!.last
        val st = llmState(s, last + 500)                                  // 울음 수는 판단 코어 값(C287)
        assertEquals(2, st.cry.currentMin, "이어진 경보의 처음(T0+130초)부터 2분째")
        assertEquals("지금 2분째 울고 있고, 이번 주행에서 1번 울었어요.", topicSentence("cry", st), "C287: 지금 울음과 같은 기준으로 센다")
    }

    @Test fun c286_alarmsStoppedBeyondThreshold_notHeard_newRunRestartsMinutes() {
        val s = switchThenCry(T0 + 130_000, 70_000)
        val last = s.session!!.alarmRun!!.last
        assertEquals(2, llmState(s, last + 2 * Params().yamnetHopMs).cry.currentMin, "문턱(두 걸음) 안은 이어진 경보")
        val quiet = llmState(s, last + 2 * Params().yamnetHopMs + 1)
        assertNull(quiet.cry.currentMin)
        assertEquals("지금은 울음이 들리지 않고, 이번 주행에서 1번 울었어요.", topicSentence("cry", quiet))
        val again = step(s, CrySegment(last + 60_000, last + 57_000, last + 60_000, 0.9, true))   // 1분 뒤 다시 운다
        val st = llmState(again, last + 60_500)
        assertEquals(1, st.cry.currentMin, "새로 이어진 경보의 처음부터")
        assertEquals("지금 1분째 울고 있고, 이번 주행에서 2번 울었어요.", topicSentence("cry", st), "C287: 끊겼다 다시 울면 2번")
    }

    // ---- C288: 지금 울음과 횟수는 감지 기준 하나(에피소드 종류로 단정하지 않는다) ----

    @Test fun c288_autoAttemptPlaying_unavailable() {
        val st = llmState(crying(), T0 + 150_000)                         // 경보 에피소드의 자동 시도 재생 중: 판단 코어가 울음 구간을 보지 않는다
        assertEquals(true, st.cry.unavailable); assertNull(st.cry.currentMin)
        assertEquals("지금은 울음을 확인할 수 없고, 이번 주행에서 1번 울었어요.", topicSentence("cry", st))
    }

    @Test fun c288_alarmEpisodeWait_alarmsStopped_notHeard() {
        val s = cryingNow(T0 + 150_000)
        assertEquals(EpisodeTrigger.ALARM, s.session!!.episode!!.trigger); assertEquals(Phase.WAIT, s.session!!.phase)
        assertEquals("지금은 울음이 들리지 않고, 이번 주행에서 1번 울었어요.", topicSentence("cry", llmState(s, T0 + 160_000)), "경보 에피소드가 열려 있어도 경보가 끊기면")
    }

    @Test fun c288_alarmsContinue_cryingFromRunStart() {
        assertEquals("지금 2분째 울고 있고, 이번 주행에서 1번 울었어요.", topicSentence("cry", llmState(cryingNow(), N)))
    }

    @Test fun c288_gapConfirming_beatsLiveAlarm() {
        var s = step(crying(), Tick(T0 + 176_000))                       // 시도 끝 → GAP(판정 창 T0+176초~)
        assertEquals(Phase.GAP, s.session!!.phase)
        s = step(s, CrySegment(T0 + 178_000, T0 + 175_000, T0 + 178_000, 0.9, true))   // 창 앞에서 시작한 구간의 경보: 판정에는 안 쓰고 GAP 그대로
        assertEquals(Phase.GAP, s.session!!.phase)
        val st = llmState(s, T0 + 178_500)
        assertEquals(true, st.cry.confirming); assertNull(st.cry.currentMin, "확인 중이 울고 있음보다 앞선다")
        assertTrue(topicSentence("cry", st).startsWith("울음이 그쳤는지 확인하고 있고"))
    }

    @Test fun c288_clearRecords_cryingContinues_oneTime_stopped_zero() {
        val cleared = step(cryingNow(T0 + 140_000), kr.aigyeot.engine.ClearRecords(T0 + 140_100))
        assertEquals("지금은 울음이 들리지 않고, 이번 주행에서 0번 울었어요.", topicSentence("cry", llmState(cleared, T0 + 145_000)), "끊겼으면 에피소드가 남아도 들리지 않음")
        val again = step(cleared, CrySegment(T0 + 140_975, T0 + 137_975, T0 + 140_975, 0.9, true))
        assertEquals("지금 1분째 울고 있고, 이번 주행에서 1번 울었어요.", topicSentence("cry", llmState(again, T0 + 141_000)), "이어지면 지운 뒤부터 1번")
    }

    @Test fun c287_clearRecords_countZero() {
        val s = step(switchThenCry(T0 + 130_000, 5_000), kr.aigyeot.engine.ClearRecords(T0 + 140_000))
        assertEquals("지금은 울음이 들리지 않고, 이번 주행에서 0번 울었어요.", topicSentence("cry", llmState(s, T0 + 140_000)))
    }

    @Test fun c245_outPauses_outsideSession() {
        val s = crying(false).let { it.copy(session = null, car = it.car.copy(pauses = setOf(PauseReason.DETECTOR_TIMEOUT))) }
        assertEquals(true, llmState(s, N, 0).cry.unavailable)
    }

    @Test fun c298_noSession_cryUnavailable() {
        val s = crying(false).let { it.copy(session = null, car = it.car.copy(micRunning = true, pauses = emptySet())) }
        assertEquals(true, llmState(s, N, 0).cry.unavailable)
    }

    @Test fun c245_healthyMic_keepsOriginalSentences() {
        val s = cryingNow()   // C288: crying()은 자동 시도 재생 중이라 확인할 수 없음
        assertEquals(false, llmState(s, N, 1).cry.unavailable)
        assertEquals("지금 2분째 울고 있고, 이번 주행에서 3번 울었어요.", topicSentence("cry", LlmCry(2, 3).let { llmState(s, N, 3).copy(cry = it) }))
        assertEquals("지금은 울음이 들리지 않고, 이번 주행에서 0번 울었어요.", topicSentence("cry", llmState(crying(false), N, 0)))
        assertEquals("울음이 그쳤는지 확인하고 있고, 이번 주행에서 1번 울었어요.", topicSentence("cry", llmState(s, N, 1).copy(cry = LlmCry(null, 1, true))))
    }

    // ---- C257: 주제 고르기 한 규칙. (도구) x (answer) x (질문) 모든 조합에서 사실 조각이 있으면 LLM 글을 쓰지 않는다 ----
    @Test fun c257_topicGrid_factPieceNeverUsesLlmText() {
        val st = llmState(crying(), N, 0)
        // (질문, 사실 조각인가, 사실 조각의 주제)
        val questions = listOf(
            Triple("아직 울어?", true, "cry"), Triple("왜 울어?", false, null), Triple("언제 먹였어? 왜 울어?", true, "feeding"),
            Triple("왜 기저귀 갈아야 해?", true, "diaper"), Triple("왜 울어? 얼마나 울었어?", true, "cry"), Triple("고마워", false, null),
        )
        val answers = listOf(emptyList(), listOf("why"), listOf("unknown"), listOf("sleep"), listOf("sleep", "why"))
        for (hasTool in listOf(false, true)) for (a in answers) for ((q, factPiece, fact) in questions) {
            val calls = (if (hasTool) listOf(tool("switch_sound", "method" to "white_noise")) else emptyList()) + (if (a.isEmpty()) emptyList() else listOf(answer(*a.toTypedArray())))
            val o = qaEventsCare(reply("글이에요", *calls.toTypedArray()), "L1", T0, st, q, emptyList())
            val label = "도구=$hasTool answer=$a 질문=$q"
            val toolOnly = hasTool && a.isEmpty()
            val answerFact = a.any { it == "sleep" }
            val expectLlm = !toolOnly && !answerFact && !factPiece
            assertEquals(expectLlm, o.llm, label)
            if (expectLlm) assertEquals("글이에요", o.second, label) else assertTrue(o.second != "글이에요", label)
            if (!toolOnly && factPiece && !answerFact) assertEquals(fact, o.factTopics.first(), label)
            if (!toolOnly && answerFact) assertEquals("sleep", o.factTopics.first { it != "why" }.let { if (factPiece) o.factTopics.first { t -> t == "sleep" } else it }, label)
            if (toolOnly) assertEquals("소리를 바꿀게요.", o.second, label)
        }
    }

    @Test fun c257_offlinePaths_useSamePieceRule() {
        val st = llmState(crying(), N, 0)
        listOf("왜 울어? 얼마나 울었어?" to "cry", "왜 울어? 소리 효과 있어?" to "sound_stats", "언제 먹였어? 왜 울어?" to "feeding", "왜 울어? 잠 잤어?" to "sleep").forEach { (q, t) ->
            assertEquals(t, questionTopic(q), q); assertEquals(listOf(t), offlineFactTopics(q), q)
            assertEquals(fallbackReply(listOf(t), st), offlineReply(q, st), q)
            // 본 길의 LLM 실패 대체(fallbackTopics)도 같은 주제
            val llm = qaEventsCare(reply("글이에요", answer("unknown")), "L1", T0, st, q, emptyList())
            assertEquals(listOf(t), llm.factTopics, q)
        }
        assertEquals("why", questionTopic("왜 울어?")); assertNull(questionTopic("고마워"))
        // 3초 대체(answer·도구 없음)와 본 길(answer 없음)이 같은 주제
        assertEquals(listOf("cry"), qaEventsCare(reply("글이에요"), "L1", T0, st, "왜 울어? 얼마나 울었어?", emptyList()).factTopics)
    }

    // ---- C263: '왜'와 사실 질문이 한 조각에 있어도 표 순서(1~6행 사실 > 왜 > 소리 성적·울음)대로 본다 ----
    @Test fun c263_whyAndFactInOnePiece() {
        val st = llmState(crying(), N, 0)
        listOf(
            "왜 우는지랑 마지막 수유가 언제인지 알려줘" to "feeding", "왜 우는지 그리고 얼마나 울었는지 알려줘" to "cry", "왜 우는지랑 얼마나 울었는지" to "cry", "왜 이렇게 오래 울어?" to "cry", "왜 효과가 없어?" to "sound_stats", "왜 우는지랑 마지막 수유가 언제인지" to "feeding", "왜 이렇게 추워" to "cabin_temp", "왜 이렇게 더워" to "cabin_temp",
            "왜 울어?" to null, "왜 기저귀 갈아야 해?" to "diaper",
        ).forEach { (q, fact) ->
            assertEquals(fact, questionFact(q), q)
            val o = qaEventsCare(reply("방금 먹었어요. 불편할 수 있어요.", answer("why")), "L1", T0, st, q, emptyList())
            if (fact != null) { assertEquals(fact, o.factTopics.first(), q); assertFalse(o.llm, q); assertTrue(o.second != "방금 먹었어요. 불편할 수 있어요.", q) }
        }
        listOf("더워", "더운데", "더웠어", "추운데", "추웠어").forEach { assertEquals("cabin_temp", offlineTopic("차 안이 $it"), it) }
        assertEquals("cry", questionTopic("왜 우는지 그리고 얼마나 울었는지"))
        assertEquals("why", questionTopic("왜 울었어?")); assertEquals("sleep", questionTopic("얼마나 잤어?"))
        assertEquals(listOf("feeding"), offlineFactTopics("왜 우는지랑 마지막 수유가 언제인지 알려줘"))
    }

    // C271: 분류(C270)를 되돌려도 사실 단정 글은 상태 단정 검사 하나로 막힌다. 본 길·LLM 실패 대체 모두 차가 정한 문장
    @Test fun c271_factClaimTextBlocked_evenIfClassifierMisses() {
        val st = llmState(crying(false), N, 0)
        val claims = mapOf("지금은 울음이 들리지 않아요." to "cry", "방금 먹었어요." to "feeding", "아까 잤어요." to "sleep", "기저귀를 갈았어요." to "diaper",
            "온도가 높아요." to "cabin_temp", "휴게소가 있어요." to "next_rest", "효과가 없었어요." to "sound_stats")
        claims.keys.forEach { t ->
            for (a in listOf(listOf("why"), listOf("unknown"), emptyList())) {
                val o = qaEventsCare(reply(t, *(if (a.isEmpty()) emptyArray() else arrayOf(answer(*a.toTypedArray())))), "L1", T0, st, "왜 울어?", emptyList())
                assertFalse(o.llm, "$t $a"); assertTrue(o.second != t, "$t $a")
            }
        }
        // 46회차 재현: 분류가 cry로 잡든 못 잡든 글은 쓰이지 않는다
        val o = qaEventsCare(reply("지금은 울음이 들리지 않아요.", answer("why")), "L1", T0, st, "왜 우는지 그리고 얼마나 울었는지", emptyList())
        assertFalse(o.llm); assertEquals("cry", o.factTopics.first())
        val o2 = qaEventsCare(reply("지금은 울음이 들리지 않아요.", answer("why")), "L1", T0, st, "왜 울어?", emptyList())   // 분류가 why로 놓친 꼴
        assertFalse(o2.llm); assertTrue(o2.second != "지금은 울음이 들리지 않아요.")
    }

    // C265: why 답의 LLM 글에 "반 초"가 있으면 버린다
    @Test fun c265_halfUnitTextIsRejected() {
        val st = llmState(crying(), N, 0)
        listOf("반 초 안에 달라질 수 있어요.", "반 리터예요.", "반 배예요.").forEach { t ->
            assertFalse(qaEventsCare(reply(t, answer("why")), "L1", T0, st, "왜 울어?", emptyList()).llm, t)
        }
    }

    // C266: 오프라인 키워드 표에 더한 활용형. '잘'·'자고'·'먹이'는 넣지 않는다
    @Test fun c266_newKeywords() {
        mapOf("쉴 곳 있어?" to "next_rest", "쉬는 곳 알려줘" to "next_rest", "애기 재워야 해?" to "sleep", "언제 재울까" to "sleep", "지금 자는 거야?" to "sleep",
            "먹여야 해?" to "feeding", "언제 먹일까" to "feeding", "소리 통했어?" to "sound_stats", "울면 어떡해" to "cry", "또 울까?" to "cry",
        ).forEach { (q, t) -> assertEquals(t, offlineTopic(q), q) }
        listOf("잘 모르겠어", "자고 싶어", "먹이를 줘").forEach { assertNull(offlineTopic(it), it) }
    }

    @Test fun c275_offlineTopic_sameForAllSpaceForms() {
        listOf(" ", "\u00A0", "\u2028", "\u2029", "\u3000", "\t", "\n", "\u200B", "\u2060", "\uFEFF").forEach { sp ->
            assertEquals("cry", offlineTopic("아직${sp}울어?"), "울어 ${sp.length}")
            assertEquals("sleep", offlineTopic("잠${sp}시간"), "잠 시간 ${sp.length}")
        }
    }
}
