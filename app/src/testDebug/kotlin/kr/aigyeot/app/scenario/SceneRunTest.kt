package kr.aigyeot.app.scenario

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.aigyeot.engine.AdjustPlayback
import kr.aigyeot.engine.Answer
import kr.aigyeot.engine.BabyAboard
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.CabinTemp
import kr.aigyeot.engine.PendingAsk
import kr.aigyeot.engine.Play
import kr.aigyeot.engine.PlayPurpose
import kr.aigyeot.engine.StartPlayback
import kr.aigyeot.engine.PlayKind
import kr.aigyeot.engine.Step
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.engine.PlayMode
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.Stage
import kr.aigyeot.engine.YesNo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith
import java.time.Instant
import java.time.OffsetDateTime

class SceneRunTest {
    private fun scene(steps: String, override: String = "\"RearDoor\",\"CabinTemp\"", sibling: Boolean = false) = parseScene("""
        {"format":1,"id":"T","title":"시험","repeat":1,
         "setup":{"profile":{"birthYm":"2026-02","carseat":"RIGHT","feedingIntervalMin":180,"hasSibling":$sibling},"feedingStartOffsetSec":-600,
                  "methods":["WHITE_NOISE","LULLABY"],"history":[{"method":"LULLABY","result":"SUCCESS","daysAgo":2}],
                  "seed":10,"override":[$override],"otherGuardians":2},
         "steps":[{"t":0,"vc":"PUT /vehicle/gear?gear=Park"},{"t":2,"inject":{"event":"RearDoor","side":"RIGHT","open":true}},
                  {"t":5,"inject":{"event":"RearDoor","side":"RIGHT","open":false}},$steps]}""")

    @Test fun `setup은 반복 k번째 시드와 시작 기준 시각으로 바뀐다`() {
        val w = FakeWorld()
        val r = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 3, w, w, 1_000_000)
        val s = r.setup()
        assertEquals(13L, s.seed)
        assertEquals(1_000_000L - 600_000, s.feedingStart)
        assertEquals(1_000_000L - 2 * 86_400_000L, s.history.single().at)
        assertEquals(2, s.otherGuardians)
        assertEquals(setOf("RearDoor", "CabinTemp"), s.override)
    }

    @Test fun `단계는 t 순서로 그 시각에 실행된다(파일 순서와 무관)`() {
        val w = FakeWorld()
        w.play(scene("""{"t":8,"inject":{"event":"CabinTemp","celsius":30}},{"t":6,"inject":{"event":"CabinTemp","celsius":20}}"""))
        val temps = w.events.filterIsInstance<CabinTemp>()
        assertEquals(listOf(20.0, 30.0), temps.map { it.celsius })
        assertEquals(listOf(FakeWorld.T0 + 6_000, FakeWorld.T0 + 8_000), temps.map { it.at })
        assertTrue(w.ended)
        assertEquals(4, w.simulated)   // 주입 넷(뒷문 둘·온도 둘)만 시뮬레이션 입력으로 알린다. vc는 진짜 차량 신호 길이다
    }

    @Test fun `expect는 창 안에서 맞으면 통과, 창이 짧으면 그 단계 t로 실패`() {
        val ok = FakeWorld().play(scene("""{"t":6,"tap":"BABY_ABOARD"},{"t":10,"vc":"PUT /vehicle/gear?gear=Drive"},{"t":10,"expect":{"state":"LISTENING","by":1}}"""))
        assertTrue(ok.failures.toString(), ok.completed)
        // 기어 사건은 vc 0.1초 뒤에 온다: 0.05초 창에는 아직 IDLE
        val late = FakeWorld().play(scene("""{"t":6,"tap":"BABY_ABOARD"},{"t":10,"vc":"PUT /vehicle/gear?gear=Drive"},{"t":10,"expect":{"state":"LISTENING","by":0.05}}"""))
        assertFalse(late.completed)
        assertEquals(10.0, late.failedStep!!, 0.0)
    }

    @Test fun `expect는 창 시작 때 이미 맞는 상태도 통과다`() {
        // 9초에 이미 LISTENING(6초 D)이고 그 뒤 Step이 없어도 창 시작 직전 상태로 판정
        val r = FakeWorld().play(scene("""{"t":6,"tap":"BABY_ABOARD"},{"t":7,"vc":"PUT /vehicle/gear?gear=Drive"},{"t":9.5,"expect":{"state":"LISTENING","by":0.05}}"""))
        assertTrue(r.failures.toString(), r.completed)
    }

    @Test fun `expect의 command·sid는 창이 열리기 전 할 일을 세지 않는다`() {
        // S16은 5초(뒷문 닫힘)에 나온다. 6초부터 보는 창에는 없다
        val r = FakeWorld().play(scene("""{"t":5,"expect":{"command":"Speak","sid":"S16","by":1}},{"t":6,"expect":{"command":"Speak","sid":"S16","by":3}}"""))
        assertEquals(listOf(6.0), r.failures.map { it.substringAfter("t=").substringBefore(":").toDouble() })
    }

    @Test fun `expectNone은 나오면 실패, 끝까지 안 나오면 통과`() {
        val hit = FakeWorld().play(scene("""{"t":6,"tap":"BABY_ABOARD"},{"t":10,"vc":"PUT /vehicle/gear?gear=Drive"},{"t":9,"expectNone":{"command":"SetWindowLock","for":5}}""", sibling = true))
        assertEquals(9.0, hit.failedStep!!, 0.0)
        val clean = FakeWorld().play(scene("""{"t":6,"tap":"BABY_ABOARD"},{"t":10,"vc":"PUT /vehicle/gear?gear=Drive"},{"t":9,"expectNone":{"command":"SetWindowLock","for":5}}"""))
        assertTrue(clean.failures.toString(), clean.completed)
        // type으로 거르면 다른 종류의 SendToPhone은 통과
        val typed = FakeWorld().play(scene("""{"t":6,"tap":"BABY_ABOARD"},{"t":10,"vc":"PUT /vehicle/gear?gear=Drive"},{"t":20,"vc":"PUT /vehicle/gear?gear=Park"},
            {"t":25,"inject":{"event":"RearDoor","side":"RIGHT","open":true}},{"t":24,"expectNone":{"command":"SendToPhone","type":"handoff_alert","for":3}},
            {"t":24,"expect":{"command":"SendToPhone","type":"feeding_state","by":3}}"""))
        assertTrue(typed.failures.toString(), typed.completed)
    }

    @Test fun `say YES는 Ask가 말을 마치고 듣기를 열 때 Answer를 넣는다`() {
        val w = FakeWorld()
        // 닫힌 뒷문 + P + 마이크 꺼짐은 Speak(S16)이지 Ask가 아니다 → 기다리다 실패
        val r = w.play(scene("""{"t":5,"say":{"intent":"YES","onAsk":"S07","by":5}}"""))
        assertEquals(5.0, r.failedStep!!, 0.0)
        assertTrue(w.events.none { it is Answer })
    }

    @Test fun `say YES는 듣기가 열린 뒤에만 답하고 쉼터 응은 운전자 조작이 아니다`() {
        val c = parseScene(java.io.File("src/debug/assets/scenario/C.json").readText())
        val w = FakeWorld(mapOf("cry/stop_50s.pcm" to 20.0))
        val r = w.play(c)
        assertTrue(r.failures.toString(), r.completed)
        val ans = w.events.filterIsInstance<Answer>().first { it.answer == YesNo.YES }
        val askDone = w.events.filterIsInstance<kr.aigyeot.engine.SpeechDone>().first { it.utteranceId == ans.askId }
        assertTrue("말을 마친 뒤 답", ans.at >= askDone.at)
        assertEquals(0, r.driverOps)
    }

    @Test fun `주행 중 누르기·말은 운전자 조작으로 센다`() {
        val r = FakeWorld().play(scene("""{"t":6,"tap":"BABY_ABOARD"},{"t":10,"vc":"PUT /vehicle/gear?gear=Drive"},
            {"t":12,"say":{"intent":"STOP"}},{"t":13,"tap":"FEEDING_NOW"}"""))
        assertEquals(2, r.driverOps)   // 정차 중 BABY_ABOARD는 세지 않는다
    }

    @Test fun `BABY_ABOARD는 마이크를 켜고 BabyAboard를 넣는다`() {
        val w = FakeWorld()
        w.play(scene("""{"t":6,"tap":"BABY_ABOARD"},{"t":6,"expectNone":{"command":"CancelSpeech","for":1}}"""))   // 마이크는 0.3초 뒤에 켜진다
        assertTrue(w.events.any { it is BabyAboard })
        assertTrue(w.events.any { it is kr.aigyeot.engine.MicService && it.running })
    }

    @Test fun `phone은 atOffsetSec를 그 단계 기준 시각으로 바꾼 봉투를 보낸다`() {
        val sent = ArrayList<String>()
        val w = object : ScenePorts by FakeWorld() { override fun phone(envelopeJson: String, done: (Boolean, String) -> Unit) { sent += envelopeJson; done(true, "") } }
        val host = FakeWorld()
        val r = SceneRun(scene("""{"t":3,"phone":{"type":"feeding_started","body":{"atOffsetSec":-9,"x":1}}}"""), 0, host, w, host.now)
        host.now += 3_000
        r.advance(host.now)
        val env = Json.parseToJsonElement(sent.single()).jsonObject
        assertEquals("feeding_started", env["type"]!!.jsonPrimitive.content)
        assertEquals(26, env["id"]!!.jsonPrimitive.content.length)
        val body = env["body"]!!.jsonObject
        assertEquals(host.now - 9_000, OffsetDateTime.parse(body["at"]!!.jsonPrimitive.content).toInstant().toEpochMilli())
        assertNull(body["atOffsetSec"])
        assertEquals(JsonPrimitive(1), body["x"])
    }

    @Test fun `cmdMatches - body는 적은 필드만, 시각은 2초까지`() {
        val body = JsonObject(mapOf("kind" to JsonPrimitive("waypoint_offer"), "at" to JsonPrimitive("2026-11-05T09:00:01+09:00"), "n" to JsonPrimitive(3)))
        val c = SendToPhone("handoff_alert", body)
        fun want(vararg kv: Pair<String, JsonPrimitive>) = JsonObject(kv.toMap())
        assertTrue(cmdMatches(c, "SendToPhone", null, null, "handoff_alert", want("kind" to JsonPrimitive("waypoint_offer")), 0))
        assertTrue(cmdMatches(c, "SendToPhone", null, null, null, want("at" to JsonPrimitive("2026-11-05T00:00:03Z")), 0))
        assertFalse(cmdMatches(c, "SendToPhone", null, null, null, want("at" to JsonPrimitive("2026-11-05T00:00:04Z")), 0))
        assertFalse(cmdMatches(c, "SendToPhone", null, null, "feeding_state", null, 0))
        assertFalse(cmdMatches(c, "SendToPhone", null, null, null, want("missing" to JsonPrimitive(1)), 0))
        assertFalse(cmdMatches(Speak("u", Sid.S02, emptyMap()), "Speak", Sid.S03, null, null, null, 0))
    }

    @Test fun `gain_violations - 1 넘음, 재생 중 올림은 위반, 감쇄 풀림 복귀·페이드·폴백은 허용`() {
        fun g(at: Long, v: Double, id: String = "p") = GainPoint(at, id, v)
        fun adj(at: Long, m: PlayMode) = at to AdjustPlayback("p", m)
        assertEquals(1, gainViolations(listOf(g(0, 1.2)), emptyList()))
        assertEquals(0, gainViolations(listOf(g(0, 1.0)), emptyList()))
        assertEquals(1, gainViolations(listOf(g(0, 0.5), g(10, 0.8)), emptyList()))                                   // 이유 없이 올림
        assertEquals(0, gainViolations(listOf(g(0, 1.0), g(5, 0.5), g(10, 0.0)), emptyList()))                        // 마지막 페이드
        assertEquals(0, gainViolations(listOf(g(0, 0.3)), emptyList()))                                               // 폴백 0.3
        assertEquals(0, gainViolations(listOf(g(0, 0.8), g(11, 0.2), g(21, 0.8)), listOf(adj(10, PlayMode.DUCK), adj(20, PlayMode.NORMAL))))   // 감쇄 직전 이득까지 복귀
        assertEquals(1, gainViolations(listOf(g(0, 0.8), g(11, 0.2), g(21, 0.9)), listOf(adj(10, PlayMode.DUCK), adj(20, PlayMode.NORMAL))))   // 감쇄 직전보다 높게
        assertEquals(1, gainViolations(listOf(g(0, 0.8), g(11, 0.2), g(15, 0.8)), listOf(adj(10, PlayMode.DUCK), adj(20, PlayMode.NORMAL))))   // 풀리기 전에 올림
        assertEquals(0, gainViolations(listOf(g(0, 0.5), g(10, 0.8, "q")), emptyList()))                              // 다른 재생은 따로 본다
    }

    @Test fun `CSV는 계약 7_4 칼럼 그대로이고 요약 줄을 붙인다`() {
        val a = RunRow(1, 1, true, null, 2_400, 300, "bus100_audio_zone_1", false, 1, 56_000, 56_000, 0, null, 0, 0, null, 1.0, emptyList())
        val b = a.copy(run = 2, seed = 2, completed = false, failedStep = 366.0, alarmLatencyMs = 3_100, maxAttempts = 2, controlOkRate = 0.5)
        val lines = csv(listOf(a, b)).trim().lines()
        assertEquals("run,seed,completed,failed_step,alarm_latency_ms,alarm_to_play_ms,routed_device,fallback,max_attempts,max_episode_play_ms," +
            "session_play_ms,gain_violations,lufs_violations,system_volume_changes,driver_ops,handoff_ms,control_ok_rate", lines[0])
        assertEquals("1,1,true,,2400,300,bus100_audio_zone_1,false,1,56000,56000,0,,0,0,,1.000", lines[1])
        assertEquals("summary,,1/2,366.0,3100,300,,false,2,56000,56000,0,,0,0,,0.500", lines[3])
    }

    // ---- 1회차 수정 시험 ----

    /** 세션이 열려 있는 Step 하나(주행 시작 직후 LISTENING). */
    private fun openStep(): Step {
        val w = FakeWorld()
        w.play(scene("""{"t":6,"tap":"BABY_ABOARD"},{"t":10,"vc":"PUT /vehicle/gear?gear=Drive"},{"t":10,"expect":{"state":"LISTENING","by":1}}"""))
        return w.steps.value!!.also { assertTrue(it.state.session != null) }
    }
    private fun Step.withSession(f: (kr.aigyeot.engine.SessionState) -> kr.aigyeot.engine.SessionState) = copy(state = state.copy(session = f(state.session!!)))
    private fun control(api: String, actual: String?, ok: Boolean = true, target: String = "t") = ControlLogEntity(0, null, 0, api, target, "g", actual, 50, 100, ok, 0)

    @Test fun `F1 장면 시작 전에 열린 세션의 Step은 측정에 섞이지 않는다`() {
        val base = openStep()
        val w = FakeWorld()
        val t0 = 1_000_000L
        val r = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 0, w, w, t0)
        r.onStep(base.withSession { it.copy(stage = Stage.Gap(2, null), openedAt = t0 - 1) })   // 실제 세션이 2차 시도 뒤
        assertEquals(0, r.row().maxAttempts)
        r.onStep(base.withSession { it.copy(stage = Stage.Gap(2, null), openedAt = t0 + 1) })   // 장면 안에서 열린 세션은 센다
        assertEquals(2, r.row().maxAttempts)
    }

    @Test fun `F2 vc 입력이 실패하면 그 단계 t로 실패하고 completed는 false`() {
        val r = FakeWorld(vcFails = true).play(scene("""{"t":6,"tap":"BABY_ABOARD"}"""))
        assertFalse(r.completed)
        assertEquals(0.0, r.failedStep!!, 0.0)   // 첫 vc는 t=0
        assertTrue(r.failures.toString(), r.failures.first().contains("vc"))
    }

    @Test fun `F2 vc가 끝나지 않으면 done이 아니고 finish가 실패로 닫는다`() {
        val w = FakeWorld()
        val never = object : ScenePorts by w { override fun vc(method: String, path: String, done: (Boolean, String) -> Unit) {} }
        val r = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 0, w, never, w.now)
        r.advance(w.now + 6_000)
        assertFalse(r.done(Long.MAX_VALUE))   // 단계는 다 돌았고 vc 응답만 기다린다
        r.finish("멈춤")
        assertTrue(r.done(Long.MAX_VALUE))
        assertEquals(0.0, r.firstFail()!!, 0.0)
    }

    @Test fun `L1 phone 수신이 끝나지 않으면 done이 아니고 finish가 실패로 닫는다, 실패면 그 단계 t`() {
        val w = FakeWorld()
        var cb: ((Boolean, String) -> Unit)? = null
        val slow = object : ScenePorts by w { override fun phone(envelopeJson: String, done: (Boolean, String) -> Unit) { cb = done } }
        val steps = """{"t":8,"phone":{"type":"feeding_started","body":{}}}"""
        val r = SceneRun(scene(steps), 0, w, slow, w.now)
        r.advance(w.now + 8_000)
        assertFalse(r.done(Long.MAX_VALUE))   // 단계는 다 돌았고 수신만 기다린다
        r.finish("멈춤")
        assertTrue(r.done(Long.MAX_VALUE))
        assertTrue(r.row().failures.toString(), r.row().failures.any { it.contains("phone 요청이 끝나지 않음") })
        val r2 = SceneRun(scene(steps), 0, w, slow, w.now)
        r2.advance(w.now + 8_000)
        cb!!(false, "모의 실패")
        assertTrue(r2.done(Long.MAX_VALUE))
        assertEquals(8.0, r2.firstFail()!!, 0.0)
    }

    @Test fun `M1 차 제어 결과 줄을 기다리고, 17초 안에 안 오면 실패로 센다`() {
        val base = openStep()
        val areas = listOf(kr.aigyeot.engine.Area.ROW_1_LEFT, kr.aigyeot.engine.Area.ROW_1_RIGHT)
        fun run(): SceneRun {
            val w = FakeWorld()
            val r = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 0, w, w, w.now)
            r.advance(w.now + 6_000)
            w.now += 6_000
            r.onStep(base.copy(commands = listOf(kr.aigyeot.engine.SetSeatHeat("c", areas, 1))))
            r.onControl(control("SEAT_HEAT", "1", target = "ROW_1_LEFT"))
            return r
        }
        val t = FakeWorld().now + 6_000
        val a = run()
        assertEquals(17_000L, CONTROL_WAIT_MS)
        assertFalse(a.done(t + 16_999))
        assertTrue(a.done(t + 17_000))
        assertEquals(0.5, a.row().controlOkRate!!, 0.0)
        assertFalse(a.row().completed)
        assertEquals(6.0, a.row().failedStep!!, 0.0)   // 마지막 차 제어 할 일의 장면 시각
        assertTrue(a.row().failures.toString(), a.row().failures.any { it.contains("1개를 받지 못함") })
        val b = run()
        b.onControl(control("SEAT_HEAT", "1", target = "ROW_1_RIGHT"))   // 결과가 다 오면 바로 끝
        assertTrue(b.done(t))
        assertTrue(b.row().completed)
    }

    @Test fun `F3 같은 분에 다시 돌리면 이름 뒤에 번호를 붙인다`() {
        val dir = java.nio.file.Files.createTempDirectory("res").toFile()
        val a = freeResultFile(dir, "A", "20261105-0900")
        assertEquals("A-20261105-0900.csv", a.name)
        a.writeText("x")
        val b = freeResultFile(dir, "A", "20261105-0900")
        assertEquals("A-20261105-0900-2.csv", b.name)
        java.io.File(dir, "A-20261105-0900-2.txt").writeText("x")   // csv는 없고 txt만 있어도 건너뛴다
        assertEquals("A-20261105-0900-3.csv", freeResultFile(dir, "A", "20261105-0900").name)
    }

    @Test fun `F4 gain - 복귀가 끝난 뒤의 상승은 위반`() {
        fun g(at: Long, v: Double) = GainPoint(at, "p", v)
        fun adj(at: Long, m: PlayMode) = at to AdjustPlayback("p", m)
        val a = listOf(adj(10, PlayMode.DUCK), adj(20, PlayMode.NORMAL))
        assertEquals(0, gainViolations(listOf(g(0, 1.0), g(11, 0.2), g(21, 1.0)), a))                         // 복귀 한 번은 허용
        assertEquals(1, gainViolations(listOf(g(0, 1.0), g(11, 0.2), g(21, 1.0), g(30, 0.5), g(40, 1.0)), a))   // 그 뒤 다시 올림
    }

    @Test fun `F4 gain - DUCK 뒤 PAUSE 뒤 NORMAL은 처음 감쇄 직전 이득까지 복귀`() {
        fun g(at: Long, v: Double) = GainPoint(at, "p", v)
        fun adj(at: Long, m: PlayMode) = at to AdjustPlayback("p", m)
        val a = listOf(adj(10, PlayMode.DUCK), adj(15, PlayMode.PAUSE), adj(20, PlayMode.NORMAL))
        assertEquals(0, gainViolations(listOf(g(0, 1.0), g(11, 0.2), g(16, 0.0), g(21, 1.0)), a))
        assertEquals(1, gainViolations(listOf(g(0, 0.8), g(11, 0.2), g(16, 0.0), g(21, 0.9)), a))
    }

    @Test fun `F4 gain - 페이드 구간에서 풀리면 페이드 값까지만 허용`() {
        fun g(at: Long, v: Double) = GainPoint(at, "p", v)
        fun adj(at: Long, m: PlayMode) = at to AdjustPlayback("p", m)
        val start = StartPlayback("p", PlayKind.AUTO, Method(MethodKind.WHITE_NOISE, null), 10_000, 2_000)
        val a = listOf(adj(8_000, PlayMode.DUCK), adj(9_000, PlayMode.NORMAL))
        val starts = mapOf("p" to start)
        // 9100에 풀림: 상한 = 1.0 × (10000 − (9100 − 250)) / 2000 = 0.575
        assertEquals(1, gainViolations(listOf(g(0, 1.0), g(8_001, 0.2), g(9_100, 1.0)), a, starts))   // 감쇄 직전(ref)까지 되돌림 → 위반
        assertEquals(0, gainViolations(listOf(g(0, 1.0), g(8_001, 0.2), g(9_100, 0.5)), a, starts))   // 페이드 값 이하 → 허용
        assertEquals(0, gainViolations(listOf(g(0, 1.0), g(8_001, 0.2), g(9_100, 1.0)), a))           // 시작 정보가 없으면 ref까지
    }

    @Test fun `K1 복귀가 시작된 뒤 내려가면 허용이 닫히고 단계 복귀는 허용`() {
        fun g(at: Long, v: Double) = GainPoint(at, "p", v)
        val a = listOf(10L to AdjustPlayback("p", PlayMode.DUCK), 20L to AdjustPlayback("p", PlayMode.NORMAL))
        assertEquals(1, gainViolations(listOf(g(0, 1.0), g(11, 0.2), g(21, 0.8), g(22, 0.3), g(23, 0.8)), a))
        assertEquals(0, gainViolations(listOf(g(0, 1.0), g(11, 0.2), g(21, 0.5), g(22, 0.8), g(23, 1.0)), a))
    }

    @Test fun `F5 routed_device는 getRoutedDevice의 actual이다`() {
        val w = FakeWorld()
        val r = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 0, w, w, w.now)
        r.onControl(control("setPreferredDevice", "rear"))
        assertNull(r.row().routedDevice)   // 요청한 장치는 실제 출력이 아니다
        r.onControl(control(ROUTE_API, "front"))
        assertEquals("front", r.row().routedDevice)
    }

    @Test fun `F6 볼륨 호출도 system_volume_changes로 세고 control_ok_rate에서는 뺀다`() {
        val w = FakeWorld()
        val r = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 0, w, w, w.now)
        r.onControl(control("setStreamVolume", "5"))
        r.onControl(control("adjustStreamVolume", "6", ok = false))
        r.onControl(control(ROUTE_API, "front", ok = false))
        r.onControl(control("SEAT_HEAT", "1", target = "ROW_2_RIGHT"))
        r.onVolumeChange()
        val row = r.row()
        assertEquals(3, row.systemVolumeChanges)   // 호출 둘 + 콜백 하나
        assertEquals(1.0, row.controlOkRate!!, 0.0)   // 차 제어(SEAT_HEAT)만
    }

    @Test fun `J2 control_ok_rate는 영역 target인 차 제어 줄만 센다`() {
        val w = FakeWorld()
        val r = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 0, w, w, w.now)
        r.onControl(control("requestAudioFocus", "1"))   // 오디오 줄은 분모에 없다
        assertNull(r.row().controlOkRate)                  // 차 제어가 없으면 빈 칸
        r.onControl(control("stopPlayback", "1"))
        r.onControl(control("SEAT_HEAT", "1", ok = false, target = "ROW_1_LEFT"))
        assertEquals(0.0, r.row().controlOkRate!!, 0.0)
    }

    @Test fun `L1 lufs_violations는 쓴 음원 중 −30 ±1 LUFS 밖을 센다`() {
        val wn = Method(MethodKind.WHITE_NOISE, null); val lu = Method(MethodKind.LULLABY, null); val song = Method(MethodKind.SONG, "s1")
        assertEquals(0, lufsViolations(emptySet()) { null })                                         // 튼 음원이 없다
        assertEquals(1, lufsViolations(setOf(wn, lu)) { if (it == lu) -32.0 else -30.4 })
        assertEquals(0, lufsViolations(setOf(wn, song)) { if (it == song) -31.0 else -29.0 })       // 경계 ±1은 안
        assertEquals(1, lufsViolations(setOf(song)) { -28.9 })
        assertNull(lufsViolations(setOf(wn, song)) { if (it == song) null else -30.0 })             // 못 잰 음원이 있으면 미측정
    }

    @Test fun `L2 row의 lufs_violations는 그 장면 StartPlayback 음원을 포트로 잰다`() {
        val base = openStep()
        val asked = ArrayList<Method>()
        val ports = object : ScenePorts by FakeWorld() {
            override fun lufs(method: Method): Double? { asked += method; return if (method.kind == MethodKind.LULLABY) -33.0 else -30.0 }
        }
        val w = FakeWorld()
        val r = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 0, w, ports, w.now)
        r.onStep(base.copy(commands = listOf(StartPlayback("p1", PlayKind.AUTO, Method(MethodKind.WHITE_NOISE, null), 10_000, 2_000))))
        r.onStep(base.copy(commands = listOf(StartPlayback("p2", PlayKind.AUTO, Method(MethodKind.LULLABY, null), 20_000, 2_000))))
        r.onStep(base.copy(commands = listOf(StartPlayback("p3", PlayKind.AUTO, Method(MethodKind.LULLABY, null), 30_000, 2_000))))
        assertEquals(1, r.row().lufsViolations)
        assertEquals(setOf(Method(MethodKind.WHITE_NOISE, null), Method(MethodKind.LULLABY, null)), asked.toSet())
    }

    @Test fun `L3 control_ok_rate는 exec가 남기는 줄 중 차 제어 넷만 센다(C96)`() {
        val w = FakeWorld()
        val r = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 0, w, w, w.now)
        // exec가 실제로 남기는 재생·경유지·오류 줄(report-exec 3장): target이 영역 이름이 아니다
        listOf("getDevices" to "bus100_audio_zone_1", "requestAudioFocus" to "p1", ROUTE_API to "p1", GAIN_API to "p1", "stopPlayback" to "p1",
            "playback" to "p1", "addWaypoint" to "tbt:rest:안성(부산)", "handle" to "StartPlayback", "start" to "exec")
            .forEach { (api, t) -> r.onControl(control(api, "x", ok = false, target = t)) }
        assertNull(r.row().controlOkRate)
        r.onControl(control("setHvacSeatTemperature", "0", target = "ROW_2_LEFT"))
        r.onControl(control("setWindowLock", "0", ok = false, target = "ROW_2_RIGHT"))
        r.onControl(control("setReadingLightSwitch", "0", target = "ROW_2_LEFT"))
        r.onControl(control("setHvacTemperature", "24", target = "ROW_1_LEFT"))
        assertEquals(0.75, r.row().controlOkRate!!, 0.0)
    }

    @Test fun `L4 CSV 요약의 lufs_violations는 반복 하나라도 미측정이면 빈 칸`() {
        val a = RunRow(1, 1, true, null, 2_400, 300, "z", false, 1, 56_000, 56_000, 0, 0, 0, 0, null, 1.0, emptyList())
        fun summaryLufs(vararg v: Int?) = csv(v.mapIndexed { i, x -> a.copy(run = i + 1, lufsViolations = x) }).trim().lines().last().split(",")[12]
        assertEquals("2", summaryLufs(0, 2))
        assertEquals("", summaryLufs(0, null))   // 0으로 쓰지 않는다
        assertEquals("", summaryLufs(null, 3))
    }

    @Test fun `S1 stepLog 유실이 반복 중에 늘면 그 반복은 completed=false, 사유 stepLog 유실`() {
        val w = FakeWorld()
        val ok = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 0, w, w, w.now)
        ok.checkStepLog()
        assertTrue(ok.row().completed)                          // 유실 없음
        val r = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 0, w, w, w.now)
        w.stepLogDrops.value += 3                                // 반복 중 버퍼가 넘쳤다
        r.checkStepLog()
        val row = r.row()
        assertFalse(row.completed)
        assertTrue(row.failures.toString(), row.failures.any { it.contains("stepLog 유실 3") })
    }

    @Test fun `G1 SceneGate는 장면 첫 Step(시드 rng, 할 일 없음)부터 넘긴다`() {
        val base = openStep()
        val gate = SceneGate(13)
        assertFalse(gate.accept(base))                                                           // 실제 세션 Step
        assertFalse(gate.accept(base.copy(state = base.state.copy(rng = 13, session = null), commands = listOf(Speak("u", Sid.S16, emptyMap())))))   // 할 일이 있으면 시작 Step이 아니다
        val start = base.copy(state = base.state.copy(rng = 13, session = null), commands = emptyList())
        assertTrue(gate.accept(start))
        assertTrue(gate.accept(base))                                                            // 그 뒤는 모두
    }

    @Test fun `F8 SendToPhone body의 occurred_at 숫자(epoch ms)는 기대 RFC 3339와 2초까지 맞다`() {
        val at = Instant.parse("2026-11-05T00:00:00Z").toEpochMilli()
        fun c(ms: Long) = SendToPhone("handoff_alert", JsonObject(mapOf("occurred_at" to JsonPrimitive(ms))))
        val want = JsonObject(mapOf("occurred_at" to JsonPrimitive("2026-11-05T00:00:00Z")))
        assertTrue(cmdMatches(c(at + 1_000), "SendToPhone", null, null, null, want, 0))
        assertFalse(cmdMatches(c(at + 3_000), "SendToPhone", null, null, null, want, 0))
    }

    @Test fun `F10 마지막 단계가 녹음이면 녹음 길이만큼 끝나지 않는다`() {
        val w = FakeWorld()
        val r = SceneRun(scene("""{"t":0,"audio":{"file":"x.pcm","durSec":40}}"""), 0, w, w, w.now)
        r.advance(w.now + 5_000)   // 녹음은 첫 advance(5초)에서 시작한다
        assertTrue(r.done(Long.MAX_VALUE))   // 단계는 다 돌았고 녹음 끝만 남았다
        assertFalse(r.done(w.now + 44_999))   // 늦게 시작한 녹음도 시작 + 40초까지
        assertTrue(r.done(w.now + 45_000))
        assertEquals(w.now + 40_000, r.endAt)
    }

    @Test fun `F15 앱 화면이 없어 마이크를 못 켜면 그 단계가 실패이고 BabyAboard는 그대로 넣는다`() {
        val w = FakeWorld(micAvailable = false)
        val r = w.play(scene("""{"t":6,"tap":"BABY_ABOARD"}"""))
        assertFalse(r.completed)
        assertEquals(6.0, r.failedStep!!, 0.0)
        assertTrue(w.events.any { it is BabyAboard })
    }

    @Test fun `F17 say YES는 이미 닫힌 질문에 답하지 않고 창이 끝나면 실패한다`() {
        val base = openStep()
        val ask = PendingAsk("a1", Sid.S07, "r1", null, false)
        val w = FakeWorld()
        val t0 = w.now
        val r = SceneRun(scene("""{"t":6,"say":{"intent":"YES","onAsk":"S07","by":5}}"""), 0, w, w, t0)
        w.now = t0 + 6_000; r.advance(w.now)
        w.now += 100; r.onStep(base.withSession { it.copy(offers = it.offers.copy(pendingAsk = ask)) })     // 듣기 중
        w.now += 100; r.onStep(base.withSession { it.copy(offers = it.offers.copy(pendingAsk = null)) })    // 이미 닫힘
        w.now += 100; r.advance(w.now)
        assertTrue(w.events.none { it is Answer })
        w.now = t0 + 11_500; r.advance(w.now)
        assertEquals(6.0, r.firstFail()!!, 0.0)
        // 지금 열려 있는 질문에는 답한다
        val w2 = FakeWorld()
        val r2 = SceneRun(scene("""{"t":6,"say":{"intent":"YES","onAsk":"S07","by":5}}"""), 0, w2, w2, t0)
        w2.now = t0 + 6_000; r2.advance(w2.now)
        w2.now += 100; r2.onStep(base.withSession { it.copy(offers = it.offers.copy(pendingAsk = ask)) })
        w2.now += 100; r2.advance(w2.now)
        assertEquals("a1", w2.events.filterIsInstance<Answer>().single().askId)
    }

    // ---- 2회차 수정 시험 ----

    @Test fun `G1 녹음만 진행 중일 때 멈추면 completed는 false`() {
        val w = FakeWorld()
        val r = SceneRun(scene("""{"t":0,"audio":{"file":"x.pcm","durSec":40}}"""), 0, w, w, w.now)
        r.advance(w.now + 5_000)
        assertTrue(r.done(Long.MAX_VALUE))   // 남은 단계·판정은 없다
        r.finish("멈춤")
        val row = r.row()
        assertFalse(row.completed)
        assertTrue(row.failures.toString(), row.failures.single().contains("멈춤"))
    }

    @Test fun `G2 진행 중 재생분은 마지막으로 본 시각까지 센다`() {
        val base = openStep()
        val w = FakeWorld()
        val t0 = w.now
        val r = SceneRun(scene("""{"t":6,"tap":"BABY_ABOARD"}"""), 0, w, w, t0)
        val play = Play("p", PlayPurpose.AUTO_FIRST, Method(MethodKind.WHITE_NOISE, null), t0 + 100, t0 + 60_000, PlayMode.NORMAL)
        w.now = t0 + 100
        r.onStep(base.withSession { it.copy(stage = Stage.Attempt(1, play), playedMs = 0, openedAt = t0 + 1) })
        assertEquals(0L, r.row().sessionPlayMs)   // 시계가 안 갔다
        r.advance(t0 + 5_100)   // Tick 없이 시계만 5초 감
        assertTrue("session_play_ms ${r.row().sessionPlayMs}", r.row().sessionPlayMs >= 5_000)
    }

    @Test fun `G3 처음 이득 점과 DUCK이 같은 ms여도 감쇄 직전 이득이 기준`() {
        fun g(at: Long, v: Double) = GainPoint(at, "p", v)
        fun adj(at: Long, m: PlayMode) = at to AdjustPlayback("p", m)
        assertEquals(1, gainViolations(listOf(g(0, 0.3), g(1, 0.06), g(21, 0.8)), listOf(adj(0, PlayMode.DUCK), adj(20, PlayMode.NORMAL))))
    }

    @Test fun `G4 같은 advance의 뒤 단계는 앞 단계 사건이 반영된 상태를 본다`() {
        val r = FakeWorld().play(scene("""{"t":1,"inject":{"event":"GearChanged","gear":"D"}},{"t":1,"tap":"FEEDING_NOW"}""", "\"RearDoor\",\"GearChanged\""))
        assertEquals(1, r.driverOps)
    }

    @Test fun `G6 by가 지난 뒤 처음 열린 질문에는 답하지 않고 실패한다`() {
        val base = openStep()
        val w = FakeWorld()
        val t0 = w.now
        val r = SceneRun(scene("""{"t":0,"say":{"intent":"YES","onAsk":"S07","by":1}}"""), 0, w, w, t0)
        r.advance(t0)
        w.now = t0 + 2_000
        r.onStep(base.withSession { it.copy(offers = it.offers.copy(pendingAsk = PendingAsk("a1", Sid.S07, "r1", null, false))) })
        r.advance(w.now)
        assertTrue(w.events.none { it is Answer })
        assertEquals(0.0, r.firstFail()!!, 0.0)
    }

    @Test fun `G7 페이드로 줄어든 이득에서 풀릴 때 페이드 비율을 다시 곱하지 않는다`() {
        fun g(at: Long, v: Double) = GainPoint(at, "p", v)
        val starts = mapOf("p" to StartPlayback("p", PlayKind.AUTO, Method(MethodKind.WHITE_NOISE, null), 10_000, 2_000))
        val a = listOf(9_001L to AdjustPlayback("p", PlayMode.DUCK), 9_150L to AdjustPlayback("p", PlayMode.NORMAL))
        assertEquals(0, gainViolations(listOf(g(0, 1.0), g(9_000, 0.5), g(9_100, 0.1), g(9_200, 0.4)), a, starts))
        assertEquals(1, gainViolations(listOf(g(0, 1.0), g(9_000, 0.5), g(9_100, 0.1), g(9_200, 0.6)), a, starts))   // 감쇄 직전 0.5 초과
    }

    @Test fun `H1 처음 이득 점과 DUCK이 같은 ms면 base도 그 점이다`() {
        fun g(at: Long, v: Double) = GainPoint(at, "p", v)
        val starts = mapOf("p" to StartPlayback("p", PlayKind.AUTO, Method(MethodKind.WHITE_NOISE, null), 10_000, 2_000))
        val a = listOf(0L to AdjustPlayback("p", PlayMode.DUCK), 9_150L to AdjustPlayback("p", PlayMode.NORMAL))
        // 상한 0.3 × (10000 − 8950) / 2000 = 0.1575
        assertEquals(1, gainViolations(listOf(g(0, 0.3), g(1, 0.06), g(9_200, 0.2)), a, starts))
        assertEquals(0, gainViolations(listOf(g(0, 0.3), g(1, 0.06), g(9_200, 0.15)), a, starts))
    }

    @Test fun `H3 녹음 시작이 늦으면 audioEnd도 그만큼 늦다`() {
        val w = FakeWorld(audioDelayMs = 2_000)
        val r = SceneRun(scene("""{"t":0,"audio":{"file":"x.pcm","durSec":40}}"""), 0, w, w, w.now)
        r.advance(w.now + 5_000)
        assertFalse(r.done(w.now + 46_999))   // 시작 5초 + 지연 2초 + 40초
        assertTrue(r.done(w.now + 47_000))
    }

    // ---- C119 setup.care·inject CareSummary ----
    private val careFields = """"asOfSec":-60,"sleepDiaperEnabled":true,"lastSleepStartSec":-9000,"lastSleepEndSec":-6000,"sleepingSinceSec":null,"todaySleepMin":120,"nightWakeups":1,"lastDiaperSec":-3600,"todayDiaperCount":2,"todayFeedCount":2"""

    @Test fun `setup care는 시작 기준 시각으로 바뀐다`() {
        val s = parseScene("""{"format":1,"id":"T","title":"","repeat":1,"setup":{"seed":1,"override":["CareSummary"],"care":{$careFields}},"steps":[]}""")
        val w = FakeWorld()
        val care = SceneRun(s, 0, w, w, 1_000_000).setup().care!!
        assertEquals(1_000_000L - 60_000, care.asOf)
        assertEquals(1_000_000L - 9_000_000, care.lastSleepStartAt); assertNull(care.sleepingSince); assertEquals(2, care.todayDiaperCount)
    }

    @Test fun `setup care는 override CareSummary 없이는 거절, 모르는 칸도 거절`() {
        assertFailsWith<SceneError> { parseScene("""{"format":1,"id":"T","title":"","repeat":1,"setup":{"seed":1,"override":[],"care":{$careFields}},"steps":[]}""") }
        assertFailsWith<SceneError> { parseScene("""{"format":1,"id":"T","title":"","repeat":1,"setup":{"seed":1,"override":["CareSummary"],"care":{$careFields,"x":1}},"steps":[]}""") }
        assertFailsWith<SceneError> { parseScene("""{"format":1,"id":"T","title":"","repeat":1,"setup":{"seed":1,"override":["CareSummary"],"care":{"asOfSec":-1}},"steps":[]}""") }
    }

    @Test fun `inject CareSummary의 시각 칸은 그 단계 t 기준 초`() {
        val w = FakeWorld()
        w.play(scene("""{"t":7,"inject":{"event":"CareSummary",$careFields}}""", override = "\"RearDoor\",\"CabinTemp\",\"CareSummary\""))
        val c = w.events.filterIsInstance<kr.aigyeot.engine.CareSummary>().single().summary
        assertEquals(FakeWorld.T0 + 7_000 - 60_000, c.asOf)
        assertEquals(FakeWorld.T0 + 7_000 - 3_600_000, c.lastDiaperAt)
        assertEquals(c, w.steps.value!!.state.care)
    }
}
