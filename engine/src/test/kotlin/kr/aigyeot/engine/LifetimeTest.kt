package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// D6 수명 표(설계 3.6, 계약 2.0 P6): 표 함수 fate() 전체를 도는 시험 하나 + 대기 행마다 시험 하나(열은 시험 안에서 차례로).
// 열은 실제 입구로 넣는다: 세션 닫힘 = P에서 뒷문 열림(13a), Room에서 끝난 세션 = restoreFromRoom, 장면 시작·끝·복원 ③ = Engine 함수, 나머지 = 사건.
// F-A(C319): 장면 시작은 말하는지와 상관없이 끊고(V9), 장면 끝은 장면 세션 뒷창을 되돌리고(V11), 기록 지우기는 장부를 버리지 않는다(V19).
class LifetimeTest {
    private val eng = Engine()

    /** 한 열을 적용한 결과. 장면 끝은 [cmds] = 실행기 끊기, [settle] = Room에서 확정할 장면 장부(상태는 버리므로 [s]는 보지 않는다). */
    private class Out(val s: EngineState, val cmds: List<Command>, val settle: List<HandoffLedger> = emptyList())

    private fun apply(life: Life, s: EngineState): Out {
        val at = s.now + 1_000
        fun ev(st: EngineState, e: Event) = eng.step(st, e).let { Out(it.state, it.commands) }
        return when (life) {
            Life.SESSION_CLOSE -> ev(s.copy(car = s.car.copy(gear = Gear.P)), RearDoor(at, Side.LEFT, true))
            Life.ROOM_CLOSED -> Out(eng.restoreFromRoom(s, facts(ended = true, history = s.history), at), emptyList())
            Life.SESSION_OPEN -> ev(s, GearChanged(at, Gear.D))   // 세션이 없고 P인 상태에서 부른다
            Life.SCENE_START -> eng.sceneStart(s, s, at).realStep.let { Out(it.state, it.commands) }
            Life.SCENE_END -> eng.sceneEnd(s).let { Out(s, it.commands, it.settle) }
            Life.RESTORE -> Out(eng.afterRestore(s, at, micAlive = true), emptyList())
            Life.BOOT -> ev(s, Boot(at, s.car.gear, 10))
            Life.CLEAR -> ev(s, ClearRecords(at))
            Life.SUSPEND -> ev(s, Suspend(at))
            Life.RESUME -> ev(s, Resume(at))
            Life.CALL -> ev(s, CallMode(at, true))
        }
    }

    private fun Out.alerts() = cmds.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
    private fun Sim.withSes(f: SessionState.() -> SessionState) = apply { s = s.copy(session = ses.f()) }
    private fun Sim.withSpeech(speaking: String?, vararg q: Line) = apply { s = s.copy(speech = s.speech.copy(speaking = speaking, queue = q.toList())) }
    private fun line(sid: Sid, sessionId: String? = null, handoff: Boolean = false, longDrive: Boolean = false, askRestId: String? = null) =
        Line(sid, emptyMap(), askRestId, sessionId = sessionId, handoff = handoff, longDrive = longDrive)

    // ---- 표 전체(순수 함수) ----

    @Test fun lifetime_fateTable() {
        val sim = session()
        val id = sim.ses.id
        val open = HandoffLedger(id, null, sim.t, HandoffCause.TWO_ATTEMPTS, HandoffKind.NO_REST, null)
        val openSpeaking = open.copy(utteranceId = "u1")
        val closed = open.copy(sessionId = "old")
        val s = sim.s.copy(speech = sim.s.speech.copy(speaking = "u1"))
        // 열 순서 = Life 선언 순서: SESSION_CLOSE ROOM_CLOSED SESSION_OPEN SCENE_START SCENE_END RESTORE BOOT CLEAR SUSPEND RESUME CALL
        // K = KEEP, C = CUT, S = SETTLE, D = DROP, R = RESTORE_LOCK, H = HIDE. 말하는 중인 상태(s)다
        val table = listOf(
            Waiting.Ledger(open) to "SKKKSKKKKKK",           // V19: 기록 지우기는 장부를 버리지 않는다(줄을 따라간다)
            Waiting.Ledger(openSpeaking) to "KKKKSKKKKKK",   // C152: 그 줄을 말하는 중이면 닫힌 세션 장부로 남는다
            Waiting.Ledger(closed) to "KKKKSKKKKKK",
            Waiting.Playing to "CKKCCKKKCKC",                // V9: 장면 시작이 실제 재생을 끊는다
            Waiting.Speaking to "KKKCCDKKCKC",
            Waiting.Queued(line(Sid.S07, id, askRestId = "r")) to "DDKCDKKKCKC",
            Waiting.Queued(line(Sid.QA)) to "KKKCDKKDCKC",
            Waiting.Queued(line(Sid.S10, id, longDrive = true)) to "DDKCDDKKCKC",
            Waiting.Queued(line(Sid.S10, id, handoff = true, longDrive = true)) to "DDKCDKKKCKC",   // C258: 인계가 넘겨받은 장시간 줄은 복원 ③이 남긴다
            Waiting.Queued(line(Sid.S12)) to "KKKCDKKKCKC",
            Waiting.AskAnswer to "DDKCDDKKCKC",
            Waiting.QaTurn to "KKKCDDKDCKC",
            Waiting.Waypoint to "DDKDKKDKKKK",               // V9: 장면 시작 뒤 결과는 장면 상태로 가 받을 곳이 없다
            Waiting.Nursing to "KKKHDKKKKKK",             // C349: 장면 시작은 알림만 내리고 상태는 남기며, 장면 끝은 버린다
            Waiting.Comfort to "DDKDKKDKKKK",
            Waiting.WindowLock to "RKKKRKDKKKK",             // V11: 장면 끝이 장면 세션 잠금을 되돌린다
            Waiting.SteerWindow to "DDKKKDKKKKK",
            Waiting.Reserved to "KKDKKKKKDKK",
        )
        val code = mapOf(Fate.KEEP to 'K', Fate.CUT to 'C', Fate.SETTLE to 'S', Fate.DROP to 'D', Fate.RESTORE_LOCK to 'R', Fate.HIDE to 'H')
        for ((w, row) in table) assertEquals(row, Life.entries.map { code.getValue(fate(w, it, s)) }.joinToString(""), "$w")
        // V9: 장면 시작 열은 말하는 중인지 보지 않는다(말하지 않을 때도 말하기·큐·질문·④를 끊는다)
        val quiet = s.copy(speech = s.speech.copy(speaking = null))
        for ((w, _) in table) assertEquals(fate(w, Life.SCENE_START, s), fate(w, Life.SCENE_START, quiet), "$w")
        // ROOM_CLOSED는 SESSION_CLOSE의 DROP만(할 일은 그때 나갔다)
        for ((w, _) in table) assertEquals(if (fate(w, Life.SESSION_CLOSE, s) == Fate.DROP) Fate.DROP else Fate.KEEP, fate(w, Life.ROOM_CLOSED, s), "$w")
    }

    // ---- 행마다 ----

    @Test fun lifetime_play() {
        val sim = session().attempt1()   // ATTEMPT(1) 재생 중, S02를 말하는 중
        val pl = sim.play()
        val close = apply(Life.SESSION_CLOSE, sim.s)
        assertNull(close.s.session); assertTrue(StopPlayback(pl.id, false) in close.cmds)
        assertEquals(listOf(StopPlayback(pl.id, false), CancelSpeech), apply(Life.SCENE_END, sim.s).cmds)
        assertEquals(pl, apply(Life.RESTORE, sim.s).s.session!!.play, "복원 ③: 호스트가 앞서 PlaybackEnded(ERROR)로 끝낸다(V10)")
        for (l in listOf(Life.BOOT, Life.CLEAR, Life.RESUME)) assertEquals(pl, apply(l, sim.s).s.session!!.play, "$l")
        // V9: 장면 시작은 Suspend와 같이 끊는다(실제 감시를 멈춘다: 끝 사유 SUSPEND). 장면 길이가 실제 재생 시간에 들지 않는다(V10 (가))
        for (l in listOf(Life.SCENE_START, Life.SUSPEND, Life.CALL)) apply(l, sim.s).let { o ->
            assertTrue(StopPlayback(pl.id, false) in o.cmds, "$l"); assertEquals(Phase.WAIT, o.s.session!!.phase, "$l: 자동 시도면 WAIT")
            val r = if (l == Life.CALL) PlayEndReason.CALL else PlayEndReason.SUSPEND
            assertEquals(r, o.cmds.filterIsInstance<SavePlay>().single().record.endReason, "$l")
            assertEquals(Unjudged.valueOf(r.name), o.s.session!!.episode!!.first!!.unjudged, "$l: 1차는 판정하지 않는다")
        }
    }

    @Test fun lifetime_speakingLine() {
        val sim = session().attempt1()
        val u = sim.s.speech.speaking!!
        assertEquals(u, apply(Life.SESSION_CLOSE, sim.s).s.speech.speaking, "13a: 끝까지 말한다")
        for (l in listOf(Life.SCENE_START, Life.SUSPEND, Life.CALL)) apply(l, sim.s).let { o ->
            assertNull(o.s.speech.speaking, "$l"); assertTrue(CancelSpeech in o.cmds, "$l")
        }
        assertTrue(CancelSpeech in apply(Life.SCENE_END, sim.s).cmds)
        val r = apply(Life.RESTORE, sim.s).s
        assertNull(r.speech.speaking); assertEquals(sim.t + 1_000, r.speech.lastDoneAt, "C151: 말하지 않은 것으로 버리고 간격은 지금부터")
        assertFalse(r.speech.queue.any { it.sid == Sid.S02 })
        for (l in listOf(Life.BOOT, Life.CLEAR, Life.RESUME)) assertEquals(u, apply(l, sim.s).s.speech.speaking, "$l")
        // V9: 말하는 줄이 없어도 장면 시작은 실행기를 끊는다(질문 답 듣기·④ 듣기가 같은 실행기다. Suspend·통화와 같다)
        val quiet = session()
        assertNull(quiet.s.speech.speaking, "전제")
        for (l in listOf(Life.SCENE_START, Life.SUSPEND, Life.CALL)) assertTrue(CancelSpeech in apply(l, quiet.s).cmds, "$l")
    }

    @Test fun lifetime_queueLine() {
        val sim = session()
        val id = sim.ses.id
        val front = line(Sid.S12); val offer = line(Sid.S07, id, askRestId = "r"); val qa = Line(Sid.QA, mapOf("text" to "답"), null, qaAnswer = true)
        val ld = line(Sid.S10, id, longDrive = true); val plain = line(Sid.S16)
        sim.withSpeech("u1", front, offer, qa, ld, plain)
        fun q(l: Life) = apply(l, sim.s).s.speech.queue
        assertEquals(listOf(front, qa, plain), q(Life.SESSION_CLOSE), "C140: 제안 문장만 뺀다(말하는 줄은 둔다)")
        assertEquals(listOf(front, qa, plain), q(Life.ROOM_CLOSED))
        assertEquals(listOf(offer, qa, plain), q(Life.RESTORE), "C138: 말하던 줄·인계 표시 없는 장시간 줄을 뺀다(⚠ 옛 ④ 답은 남음)")
        assertEquals(listOf(front, offer, ld, plain), q(Life.CLEAR), "C148: 말하지 않은 ④ 답을 뺀다")
        for (l in listOf(Life.SCENE_START, Life.SUSPEND, Life.CALL)) assertTrue(q(l).isEmpty(), "$l")
        for (l in listOf(Life.BOOT, Life.RESUME)) assertEquals(listOf(front, offer, qa, ld, plain), q(l), "$l")
        // V9: 말하는 중이 아니어도 장면 시작은 큐를 비운다(S1 간격으로 기다리던 S06의 옛 값을 장면 뒤에 말하지 않게)
        val s06 = Line(Sid.S06, mapOf("elapsed" to "1시간 10분"), null, sessionId = id)
        sim.withSpeech(null, s06, offer, plain)
        apply(Life.SCENE_START, sim.s).let { o -> assertTrue(o.s.speech.queue.isEmpty()); assertTrue(CancelSpeech in o.cmds) }
    }

    @Test fun lifetime_pendingAsk() {
        val sim = session().withSes { withOffers { copy(pendingAsk = PendingAsk("a1", Sid.S07, "r1", null, false)) } }
        assertNull(apply(Life.SESSION_CLOSE, sim.s).s.session)
        assertNull(apply(Life.RESTORE, sim.s).s.session!!.pendingAsk)
        // V9: 질문이 끝나 답을 듣는 중(말하는 줄 없음)이어도 장면 시작이 실행기 듣기를 끊는다(운전자의 '응'이 장면 상태로 가 버려지지 않게)
        for (l in listOf(Life.SCENE_START, Life.SUSPEND, Life.CALL)) apply(l, sim.s).let { o -> assertNull(o.s.session!!.pendingAsk, "$l"); assertTrue(CancelSpeech in o.cmds, "$l") }
        for (l in listOf(Life.BOOT, Life.CLEAR, Life.RESUME)) assertNotNull(apply(l, sim.s).s.session!!.pendingAsk, "$l")
    }

    @Test fun lifetime_qaWindow() {
        val sim = session()
        sim.s = sim.s.copy(speech = sim.s.speech.copy(qa = QaWindow("l1", QaStep.THINKING, sim.t + 60 * MIN, false)))
        assertNotNull(apply(Life.SESSION_CLOSE, sim.s).s.speech.qa, "④는 세션 없이도 된다")
        for (l in listOf(Life.RESTORE, Life.CLEAR)) assertNull(apply(l, sim.s).s.speech.qa, "$l")
        // V9: 말하는 줄이 없어도 장면 시작이 ④ 창을 닫고 실행기를 끊는다(늦은 답이 장면 상태로 가 버려지지 않게)
        for (l in listOf(Life.SCENE_START, Life.SUSPEND, Life.CALL)) apply(l, sim.s).let { o -> assertNull(o.s.speech.qa, "$l"); assertTrue(CancelSpeech in o.cmds, "$l") }
        for (l in listOf(Life.BOOT, Life.RESUME)) assertNotNull(apply(l, sim.s).s.speech.qa, "$l")
    }

    @Test fun lifetime_ledgerOpen() {
        val sim = session()
        val id = sim.ses.id
        val a = HandoffLedger(id, null, sim.t, HandoffCause.TWO_ATTEMPTS, HandoffKind.NO_REST, null)
        sim.withSpeech("u1", line(Sid.S12), line(Sid.S10, id, handoff = true))
        sim.s = sim.s.copy(handoffs = listOf(a))
        apply(Life.SESSION_CLOSE, sim.s).let { o ->
            assertTrue(o.s.handoffs.isEmpty()); assertEquals(1, o.alerts().size, "H10: 이름 없는 알림"); assertTrue(CloseSession(id) in o.cmds)
        }
        apply(Life.SCENE_START, sim.s).let { o -> assertTrue(o.s.handoffs.isEmpty()); assertEquals(1, o.alerts().size, "C191: 줄이 끊겨 확정") }
        apply(Life.SCENE_END, sim.s).let { o -> assertEquals(listOf(a), o.settle, "C290: Room만"); assertTrue(o.alerts().isEmpty()) }
        assertEquals(listOf(a), apply(Life.RESTORE, sim.s).s.handoffs, "줄이 남아 따라간다")
        // V19: 기록 지우기는 장부를 버리지 않는다(알림은 기록이 아니라 지금 우는 아기에 대한 통지다). 장부는 묶인 줄을 따라가 그 줄이 끝날 때 알림이 나가고,
        // Room 확정은 recordsCleared가 막는다(지운 기록을 되살리지 않는다)
        apply(Life.CLEAR, sim.s).let { o ->
            assertEquals(listOf(a), o.s.handoffs); assertTrue(o.alerts().isEmpty(), "묶인 줄이 아직 큐에 있다")
            val after = Sim(o.s).flush()
            assertEquals(1, after.all<SendToPhone>().count { it.type == "handoff_alert" }, "V19: 줄이 끝나면 알림이 나간다")
            assertTrue(after.s.handoffs.isEmpty()); assertTrue(after.all<SettleHandoff>().isEmpty(), "지운 세션 행에 되쓰지 않는다")
        }
        for (l in listOf(Life.SUSPEND, Life.CALL)) apply(l, sim.s).let { o -> assertTrue(o.s.handoffs.isEmpty(), "$l"); assertEquals(1, o.alerts().size, "$l: 줄이 끊겨 Step 끝 확정") }
        // V9: 말하는 줄이 없어도(인계 줄이 S1 간격으로 기다림) 장면 시작이 그 줄을 끊어 Step 끝에 이름 없는 알림으로 확정한다(장면 뒤 옛 안내를 말하지 않게)
        sim.withSpeech(null, line(Sid.S10, id, handoff = true))
        apply(Life.SCENE_START, sim.s).let { o -> assertTrue(o.s.handoffs.isEmpty()); assertTrue(o.s.speech.queue.isEmpty()); assertEquals(1, o.alerts().size, "V9") }
        for (l in listOf(Life.BOOT, Life.RESUME)) assertEquals(listOf(a), apply(l, sim.s).s.handoffs, "$l")
        // C152: 그 줄을 말하는 중이면 닫힌 세션 장부로 남고 리포트는 장부를 확정할 때
        val speaking = a.copy(utteranceId = "u1")
        sim.withSpeech("u1", line(Sid.S10, id, handoff = true))
        sim.s = sim.s.copy(handoffs = listOf(speaking))
        apply(Life.SESSION_CLOSE, sim.s).let { o ->
            assertEquals(listOf(speaking), o.s.handoffs); assertTrue(o.alerts().isEmpty()); assertFalse(CloseSession(id) in o.cmds)
        }
    }

    @Test fun lifetime_ledgerClosed() {
        val sim = session()
        // 닫힌 세션 장부는 그 줄을 말하는 중이다(C152, 그 SpeechDone까지 따라간다)
        val a = HandoffLedger("old", null, sim.t, HandoffCause.TWO_ATTEMPTS, HandoffKind.NO_REST, null, utteranceId = "u0")
        sim.withSpeech("u0", line(Sid.S10, "old", handoff = true), line(Sid.S12))
        sim.s = sim.s.copy(handoffs = listOf(a))
        apply(Life.SESSION_CLOSE, sim.s).let { o -> assertEquals(listOf(a), o.s.handoffs); assertFalse(CloseSession("old") in o.cmds) }
        apply(Life.CLEAR, sim.s).let { o ->   // V19: 장부는 남아 그 SpeechDone까지 따라간다
            assertEquals(listOf(a), o.s.handoffs); assertTrue(o.alerts().isEmpty()); assertFalse(CloseSession("old") in o.cmds)
            val done = eng.step(o.s, SpeechDone(sim.t + 2_000, "u0", true))
            assertTrue(done.state.handoffs.isEmpty())
            assertEquals(1, done.commands.count { it is SendToPhone && it.type == "handoff_alert" }, "V19: 말한 뒤 알림이 나간다")
            assertTrue(done.commands.none { it is SettleHandoff }, "지운 세션 행에 되쓰지 않는다(recordsCleared)")
        }
        apply(Life.SUSPEND, sim.s).let { o -> assertTrue(o.s.handoffs.isEmpty()); assertEquals(1, o.alerts().size); assertTrue(CloseSession("old") in o.cmds, "C152: 미룬 리포트") }
        assertEquals(listOf(a), apply(Life.SCENE_END, sim.s).settle)
        for (l in listOf(Life.RESTORE, Life.BOOT, Life.RESUME)) assertEquals(listOf(a), apply(l, sim.s).s.handoffs, "$l")
    }

    @Test fun lifetime_waypoint() {
        val sim = session()
        sim.withSes { withOffers { copy(waypointPending = WaypointPending("c1", "r1", id, null)) } }
        assertNull(apply(Life.SESSION_CLOSE, sim.s).s.session)
        assertNull(apply(Life.BOOT, sim.s).s.session!!.waypointPending, "C84: 옛 AddWaypoint 콜백은 오지 않는다")
        assertNull(apply(Life.SCENE_START, sim.s).s.session!!.waypointPending, "V9: 결과는 장면 상태로 가 받을 곳이 없다(보관하는 실제 상태에 남기지 않는다)")
        for (l in listOf(Life.RESTORE, Life.CLEAR, Life.SUSPEND, Life.RESUME, Life.CALL))
            assertNotNull(apply(l, sim.s).s.session!!.waypointPending, "$l")
    }

    @Test fun lifetime_nursingStop() {
        val ns = NursingStop("r1", null, null, false)
        val sim = session()
        sim.s = sim.s.copy(ign = sim.s.ign.copy(nursingStop = ns))
        for (l in Life.entries - Life.SESSION_OPEN - Life.SCENE_END) assertEquals(ns, apply(l, sim.s).s.ign.nursingStop, "$l: 결정 ① 시동 단위")
        val idle = idle().doorCycle()
        idle.s = idle.s.copy(ign = idle.s.ign.copy(nursingStop = ns))
        apply(Life.SESSION_OPEN, idle.s).let { o -> assertNotNull(o.s.session); assertEquals(ns, o.s.ign.nursingStop) }
    }

    @Test fun lifetime_comfort() {
        val sim = session().withSes { copy(controls = controls.copy(comfortPending = ComfortPending("c1", 25.0, "p1"))) }
        assertNull(apply(Life.SESSION_CLOSE, sim.s).s.session)
        assertNull(apply(Life.BOOT, sim.s).s.session!!.comfortPending, "C102: 옛 ControlResult는 오지 않는다")
        assertNull(apply(Life.SCENE_START, sim.s).s.session!!.comfortPending, "V9: 결과는 장면 상태로 가고, 그 재생도 장면 시작이 끊었다")
        for (l in listOf(Life.RESTORE, Life.CLEAR, Life.SUSPEND, Life.RESUME, Life.CALL))
            assertNotNull(apply(l, sim.s).s.session!!.comfortPending, "$l")
    }

    @Test fun lifetime_windowLock() {
        val sim = session(prof = profile(sibling = true))   // 3b: 뒷창 둘을 잠근다
        val c = sim.all<SetWindowLock>().single().cmdId
        sim.ev(ControlResult(sim.t, c, ControlKind.WINDOW_LOCK, Area.ROW_2_LEFT, true, 0.0, 1.0))   // 3e: 왼쪽은 원래 열림
        val pending = sim.s.ign.windowLockPending
        assertEquals(mapOf(Area.ROW_2_LEFT to false), apply(Life.SESSION_CLOSE, sim.s).cmds.filterIsInstance<SetWindowLock>().single().values, "3f: 되돌림")
        assertEquals(mapOf(Area.ROW_2_RIGHT to true), apply(Life.BOOT, sim.s).cmds.filterIsInstance<SetWindowLock>().single().values, "C86: 결과 대기 버림·다시 잠금")
        // V11: 장면 끝도 13a와 같은 되돌림(받은 영역만, 결과 대기 영역은 장면 상태와 함께 버려진다)
        assertEquals(mapOf(Area.ROW_2_LEFT to false), apply(Life.SCENE_END, sim.s).cmds.filterIsInstance<SetWindowLock>().single().values, "V11: 장면 세션이 잠근 뒷창을 되돌린다")
        for (l in listOf(Life.SCENE_START, Life.RESTORE, Life.CLEAR, Life.SUSPEND, Life.RESUME, Life.CALL))
            assertEquals(pending, apply(l, sim.s).s.ign.windowLockPending, "$l")
    }

    @Test fun lifetime_steerWindow() {
        val sim = session()
        val w = SteerWindowAcc(sim.t, 0.0, 0, 0, false, false, false)
        sim.s = sim.s.copy(steer = sim.s.steer.copy(window = w))
        for (l in listOf(Life.SESSION_CLOSE, Life.ROOM_CLOSED, Life.RESTORE)) assertNull(apply(l, sim.s).s.steer.window, "$l")
        for (l in listOf(Life.SCENE_START, Life.BOOT, Life.CLEAR, Life.SUSPEND, Life.RESUME, Life.CALL)) assertEquals(w, apply(l, sim.s).s.steer.window, "$l")
    }

    @Test fun lifetime_reserved() {
        val sim = idle().ev(BabyAboard(T0, BabyAboardFrom.HOME))   // 1c: P에서 예약
        assertTrue(sim.s.ign.reserved)
        apply(Life.SESSION_OPEN, sim.s).let { o -> assertNotNull(o.s.session); assertFalse(o.s.ign.reserved, "1a: 열며 쓴다") }
        assertFalse(apply(Life.SUSPEND, sim.s).s.ign.reserved, "C14")
        for (l in listOf(Life.BOOT, Life.CLEAR, Life.RESUME, Life.CALL)) assertTrue(apply(l, sim.s).s.ign.reserved, "$l")
    }
}
