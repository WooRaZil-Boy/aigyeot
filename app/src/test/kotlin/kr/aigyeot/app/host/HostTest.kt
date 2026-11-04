package kr.aigyeot.app.host

import kr.aigyeot.app.store.db.PlayLogEntity
import kr.aigyeot.engine.*
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 2.4 호스트 시험. 이름이 계약에 있으면 그대로 쓴다(r14r·r14s·r14t·r14u·r14w·r14x).
@RunWith(RobolectricTestRunner::class)
class HostTest {
    private val k = Kit()
    private val dao get() = k.db.storeDao()

    @After fun close() { k.host.close(); k.db.close() }

    private fun sql(q: String) = k.db.openHelper.writableDatabase.execSQL(q)

    // ---- 큐·at ----

    @Test fun atStampedOnPost_andMonotonic() {
        k.profile(); k.boot()
        k.ev(RouteState(0, false, null, emptyList()), at = T0 + 1_000)
        assertEquals(T0 + 1_000, k.state().car.route!!.at)   // 모듈이 넣은 at(0) 대신 호스트 시계
        k.ev(RouteState(0, false, null, emptyList()), at = T0 + 500)   // 시계가 뒤로 가도
        assertEquals(T0 + 1_000, k.state().car.route!!.at)
    }

    @Test fun playbackStartedProcessedBeforeAlreadyQueuedEvent() {
        k.profile(); k.boot(); k.open()
        // 다른 모듈이 재생 시작 결과보다 먼저 큐에 넣은 사건(P). 시작 결과가 먼저 처리돼야 에피소드가 열린 뒤 WAIT로 간다
        k.onStart = { thread { k.host.post(GearChanged(0, Gear.P)) }.join() }
        k.alarm()
        val eps = dao.episodes(k.state().session!!.id)
        assertEquals(1, eps.size, "PlaybackStarted가 먼저 처리돼 에피소드가 열렸다")
        assertEquals("GEAR_P", eps[0].firstUnjudged)
        assertEquals(Phase.WAIT, k.ses().phase)
    }

    @Test fun playbackStartedFromOtherThreadProcessedBeforeAlreadyQueuedEvent() {
        k.profile(); k.boot(); k.open()
        k.autoStart = false
        k.onStart = { c ->
            thread { k.host.post(GearChanged(0, Gear.P)) }.join()   // 먼저 큐에 들어간 사건
            thread { k.host.post(PlaybackStarted(0, c.playId, "bus100_audio_zone_1", false)) }.join()   // 다른 스레드의 재생 시작 결과
        }
        k.alarm()
        val eps = dao.episodes(k.state().session!!.id)
        assertEquals(1, eps.size, "PlaybackStarted가 먼저 처리돼 에피소드가 열렸다")
        assertEquals("GEAR_P", eps[0].firstUnjudged)
        assertEquals(Phase.WAIT, k.ses().phase)
    }

    // ---- C78 스냅숏 저장 시점 ----

    @Test fun c78_immediateSave_whenSessionOpens() {
        k.profile(); k.boot()
        k.now += 1_000
        k.open()
        val snap = k.store.loadSnapshot()!!
        assertEquals(k.state().session!!.id, snap.session?.id)
    }

    @Test fun c78_vehicleSampleBatched_every30s() {
        k.profile(); k.boot(); k.open()
        val s0 = k.savedAt()
        k.ev(VehicleSample(0, 50.0, false, 1.0, TurnSignal.NONE), k.now + 1_000)
        assertNotEquals(k.state(), k.store.loadSnapshot(), "상태는 바뀌었다(조향 누적)")
        assertEquals(s0, k.savedAt(), "30초 안에는 묶는다")
        k.ev(VehicleSample(0, 50.0, false, 2.0, TurnSignal.NONE), k.now + SNAPSHOT_INTERVAL_MS)
        assertEquals(k.now, k.savedAt())
        assertEquals(k.state(), k.store.loadSnapshot())
    }

    @Test fun c78_skippedChangeSavedByLaterTick() {
        k.profile(); k.boot()
        k.host.flush(); k.host.awaitIdle()
        val j0 = k.snapshotJson()
        k.ev(RearDoor(0, Side.LEFT, true), k.now + 1_000)
        assertTrue(k.state().ign.doorOpenedSinceP, "묶음 저장이 건너뛴 변경")
        assertEquals(j0, k.snapshotJson(), "30초 안에는 저장하지 않는다")
        k.ev(Tick(0), k.now + SNAPSHOT_INTERVAL_MS)
        assertTrue(k.store.loadSnapshot()!!.ign.doorOpenedSinceP, "Tick만 와도 건너뛴 변경을 저장한다")
    }

    @Test fun c78_tickOnlyNowChange_notSaved() {
        k.profile(); k.boot()
        val s0 = k.savedAt()
        k.ev(Tick(0), k.now + 60_000)
        assertEquals(s0, k.savedAt())
    }

    // ---- 복원 ①~⑤ ----

    @Test fun r14r_restoreWhilePlaying_treatedAsPlaybackError() {
        k.profile(); k.boot(); k.open(); k.alarm()
        val pl = k.ses().play!!
        assertNotNull(pl.startedAt)
        k.running = null
        k.now += 20_000
        k.boot()   // 재생 중 프로세스가 죽었다
        val row = dao.plays(k.ses().id).single { it.id == pl.id }
        assertEquals("ERROR", row.endReason)
        assertEquals(20_000, row.playedMs)
        assertEquals(20_000, k.ses().playedMs)
        assertNull(k.ses().play)
        assertEquals(Phase.WAIT, k.ses().phase)
    }

    @Test fun r14s_restoreWithPlayAlreadyLogged_noDoubleCount() {
        k.profile(); k.boot(); k.open(); k.alarm()
        val s = k.ses()
        val pl = s.play!!
        // SavePlay만 커밋되고 스냅숏 전에 죽었다
        dao.upsertPlay(PlayLogEntity(pl.id, s.id, s.episode!!.id, "AUTO_FIRST", pl.method.kind.name, null, pl.startedAt, pl.startedAt!! + 20_000, 20_000, "ERROR"))
        k.running = null
        k.now += 40_000
        k.boot()
        assertEquals(20_000, k.ses().playedMs, "Room 누적만 센다(두 번 더하지 않음)")
        assertEquals(20_000, dao.plays(s.id).single().playedMs)
        assertNull(k.ses().play)
    }

    @Test fun r14t_restoreInGapOrConfirm_pausesAndRestartsWindow() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        assertEquals(Phase.GAP, k.ses().phase)
        assertNotNull(k.ses().windowEnd)
        k.running = null
        k.now += 2_000
        k.boot()
        assertTrue(PauseReason.MIC_ERROR in k.ses().pauses)
        assertNull(k.ses().judgeFrom); assertNull(k.ses().windowEnd)
        assertTrue(PauseReason.MIC_ERROR in k.pending, "새 마이크 서비스가 이어받는다(C82)")
        k.ev(InputRestored(0, PauseReason.MIC_ERROR), k.now + 1_000)
        assertEquals(k.now + Params().gapMs, k.ses().windowEnd, "입력이 돌아온 때부터 8초를 다시 잰다")
    }

    @Test fun r14t_restoreInConfirm_pausesAndRestartsWindow() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        val we = k.ses().windowEnd!!
        k.ev(CrySegment(0, we - 1_000, we + 2_000, 0.1, false), we + 2_000)   // GAP 창을 조용히 닫는다
        assertEquals(Phase.CONFIRM, k.ses().phase)
        k.running = null
        k.now += 2_000
        k.boot()
        assertTrue(PauseReason.MIC_ERROR in k.ses().pauses)
        assertNull(k.ses().judgeFrom); assertNull(k.ses().windowEnd)
        k.ev(InputRestored(0, PauseReason.MIC_ERROR), k.now + 1_000)
        assertEquals(k.now + Params().confirmMs, k.ses().windowEnd, "입력이 돌아온 때부터 확인 창을 다시 잰다")
    }

    @Test fun r14u_restoreAfterEndedAtBeforeReport_rebuildsReportOnly() {
        k.profile(); k.phone(); k.boot(); k.open()
        val before = k.state()
        val id = before.session!!.id
        k.ev(GearChanged(0, Gear.P), k.now + 60_000).ev(RearDoor(0, Side.LEFT, true))
        assertNull(k.state().session)
        // 세션 끝은 커밋됐지만 리포트를 넣기 전에 죽었다. 스냅숏도 세션 끝보다 늦다
        sql("UPDATE session SET report_pending = 1, report_message_id = NULL WHERE id = '$id'")
        sql("DELETE FROM outbox")
        k.store.saveSnapshot(before)
        val ended = dao.session(id)!!.endedAt
        k.exec.clear(); k.link.clear()
        k.boot()
        assertNull(k.state().session, "Room 우선: 끝난 세션은 IDLE")
        val s = dao.session(id)!!
        assertEquals(false, s.reportPending)
        assertEquals(ended, s.endedAt, "세션 저장은 다시 내지 않는다")
        val c = k.db.openHelper.readableDatabase.query("SELECT id FROM outbox WHERE type = 'report_summary'")
        assertEquals(1, c.count); c.moveToFirst(); assertEquals(s.reportMessageId, c.getString(0)); c.close()
        assertTrue(k.link.none { it is SendToPhone }, "SendToPhone은 다시 내지 않는다")
    }

    @Test fun r14w_restoreKeepsRoomFirstResult() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        val ep = k.ses().episode!!
        assertNull(ep.first!!.result)
        sql("UPDATE episode SET first_result = 'FAIL' WHERE id = '${ep.id}'")   // SaveEpisode가 스냅숏보다 먼저 커밋됐다
        k.running = null
        k.boot()
        assertEquals(TryResult.FAIL, k.ses().episode!!.first!!.result, "GAP 복원의 판정 멈춤이 미판정으로 바꾸지 않는다")
    }

    @Test fun r14x_restoreEndedEpisode_toListening() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        val ep = k.ses().episode!!
        sql("UPDATE episode SET first_result = 'SUCCESS', ended_at = ${k.now} WHERE id = '${ep.id}'")
        k.running = null
        k.boot()
        val s = k.ses()
        assertEquals(Phase.LISTENING, s.phase)
        assertNull(s.episode); assertEquals(0, s.attemptNo); assertNull(s.play)
        assertEquals(1, k.state().history.size, "성적은 Room에서 한 번만")
        assertTrue(k.state().history.single().success)
    }

    @Test fun restoreRoomEndedSession_toIdle_movesPausesToOutPauses() {
        k.profile(); k.boot(); k.open()
        k.ev(InputLost(0, PauseReason.SILENCED))
        sql("UPDATE session SET ended_at = ${k.now} WHERE id = '${k.ses().id}'")
        k.running = null
        k.boot()
        assertNull(k.state().session)
        assertTrue(PauseReason.SILENCED in k.state().car.outPauses)
        assertTrue(PauseReason.SILENCED in k.pending, "풀리지 않은 사유 전부를 마이크 서비스에 넘긴다(C82)")
    }

    @Test fun restoreRoomPlayedMsWins_capReached() {
        k.profile(); k.boot(); k.open()
        val id = k.ses().id
        dao.upsertPlay(PlayLogEntity("p1", id, null, "REQUEST", "LULLABY", null, T0, T0 + 1_200_000, 1_200_000, "DEADLINE"))
        k.running = null
        k.boot()
        assertEquals(1_200_000, k.ses().playedMs)
        assertTrue(k.ses().capReached)
    }

    @Test fun restoreClearsAskListenSpeech() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        k.store.saveSnapshot(s.copy(
            speech = s.speech.copy(speaking = "u1", qa = QaWindow("l1", QaStep.HEARING, k.now + 5_000, false)),
            session = s.session!!.copy(pendingAsk = PendingAsk("a1", Sid.S08, "r1", null, false)),
        ))
        k.boot()
        assertNull(k.state().speech.speaking); assertNull(k.state().speech.qa); assertNull(k.ses().pendingAsk)
    }

    @Test fun restoreMicNotRunning_c69_inputLostAndNotice() {
        k.profile(); k.boot(); k.open()
        assertTrue(k.state().car.micRunning)
        k.running = null
        k.boot()
        assertEquals(false, k.state().car.micRunning)
        assertTrue(PauseReason.MIC_ERROR in k.ses().pauses)
        assertTrue(Notice.MIC_STOPPED in k.notices)
    }

    @Test fun restoreMicAlreadyRunning_c82_reconcilesBothWays() {
        k.profile(); k.boot(); k.open()
        k.ev(InputLost(0, PauseReason.SILENCED))
        k.running = setOf(PauseReason.DETECTOR_TIMEOUT)   // 서비스가 살아 있다(엔진이 다시 붙음)
        k.pending = emptySet()
        k.boot()
        assertEquals(listOf(setOf(PauseReason.SILENCED)), k.assumed, "엔진에만 있는 사유 → assumePaused")
        assertTrue(PauseReason.DETECTOR_TIMEOUT in k.ses().pauses, "감지기에만 있는 사유 → InputLost")
        assertTrue(k.pending.isEmpty(), "돌고 있으면 pendingPauses를 쓰지 않는다")
    }

    @Test fun c82_serviceStartsRightAfterRunningPausesNull_reasonNotLost() {
        k.profile(); k.boot(); k.open()
        k.ev(InputLost(0, PauseReason.SILENCED))
        k.running = null
        k.pending = emptySet(); k.assumed.clear()
        k.onRunningPauses = { k.running = emptySet() }   // null을 돌려준 직후 마이크 서비스가 시작했다
        k.boot()
        assertTrue(k.assumed.any { PauseReason.SILENCED in it }, "돌고 있는 감지기에 넘긴다")
        assertTrue(k.pending.isEmpty(), "pendingPauses로 가서 잃지 않는다")
    }

    @Test fun restoreReconcileLink_feedingGuardiansAfterBoot() {
        k.profile(); k.boot()
        sql("UPDATE child_profile SET last_feeding_start = ${T0 + 5_000}")
        k.phone("guardian", alerts = true)
        k.boot()
        assertEquals(T0 + 5_000, k.state().feedingStart)
        assertEquals(1, k.state().otherGuardians)
    }

    @Test fun methodsChanged_whenActiveSongAppears() {
        k.profile(); k.boot()
        sql("UPDATE child_profile SET methods = '[{\"kind\":\"WHITE_NOISE\"},{\"kind\":\"LULLABY\"},{\"kind\":\"SONG\"}]'")
        sql("INSERT INTO song (id, name, received_at, format, duration_s, lufs, normalized_path, status, reject_reason, added_seq, last_seq) VALUES ('s1', '자장자장', $T0, 'AAC', 60.0, -30.0, NULL, 'ACTIVE', NULL, 3, 3)")
        k.host.methodsMaybeChanged(); k.host.awaitIdle()
        assertEquals(Method(MethodKind.SONG, "s1"), k.state().methodsNext.last())
    }

    // ---- 장면(F18) ----

    private fun setup(override: Set<String> = emptySet()) = ScenarioSetup(
        "A", 7, Profile("2026-03", CarSeat.LEFT, null, false), listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)),
        emptyList(), null, 0, override,
    )

    @Test fun scenario_saveDuringScenario_keepsRealSnapshot() {
        k.profile(); k.boot()
        k.host.startScenario(setup()); k.host.awaitIdle()
        val real = k.snapshotJson()
        k.open()   // 장면 안에서 세션을 연다(SaveSession → 저장 지시)
        assertEquals(real, k.snapshotJson(), "장면 중에는 실제 engine_snapshot을 덮지 않는다")
        assertEquals("A", dao.session(k.ses().id)!!.scenarioId)
        k.running = null
        k.boot()   // 장면 중 프로세스가 죽었다
        assertNull(k.state().session, "실제 상태(세션 없음)로 복원")
    }

    @Test fun scenario_endRestoresReal_recordsNotMixed() {
        k.profile(); k.phone(); k.boot()
        val realHistory = k.state().history
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.open(); k.alarm(); k.toGap()
        k.ev(CrySegment(0, k.now, k.now + 3_000, 0.9, false), k.now + 3_000)   // GAP 재울음 → 1차 FAIL
        val sid = k.ses().id
        assertEquals("FAIL", dao.episodes(sid).single().firstResult)
        k.ev(GearChanged(0, Gear.P), k.now + 1_000).ev(RearDoor(0, Side.LEFT, true))
        assertEquals(false, dao.session(sid)!!.reportPending)
        assertTrue(k.store.history(k.now).isEmpty(), "장면 세션은 성적에서 뺀다(G44)")
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(realHistory, k.state().history)
        val c = k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox WHERE type = 'report_summary'")
        c.moveToFirst(); assertEquals(0, c.getInt(0), "장면 리포트는 보내지 않는다"); c.close()
    }

    @Test fun scenario_endBeforeSessionClose_closesScenarioSession() {
        k.profile(); k.phone(); k.boot(); k.open()
        val realId = k.ses().id
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.now += 1_000   // 세션 id는 시각에서 만든다
        k.ev(GearChanged(0, Gear.P)).open()   // 장면은 실제 기어 D를 이어받아 P부터
        val sid = k.ses().id
        assertNotEquals(realId, sid)
        assertNotNull(dao.session(sid)!!.scenarioId); assertNull(dao.session(sid)!!.endedAt)   // 전제: 장면 세션이 열려 있다
        k.host.endScenario(); k.host.awaitIdle()
        assertNotNull(dao.session(sid)!!.endedAt, "끝나지 못한 장면 세션을 닫는다")
        assertEquals(false, dao.session(sid)!!.reportPending)
        assertNull(dao.session(realId)!!.endedAt, "실제 세션은 열린 채")
    }

    @Test fun start_closesOrphanScenarioSession() {
        k.profile(); k.phone(); k.boot(); k.open()
        val realId = k.ses().id
        val real = dao.session(realId)!!
        dao.upsertSession(real.copy(id = "orphan", scenarioId = "A"))   // 장면 중 프로세스가 죽어 남은 행
        assertNull(dao.session("orphan")!!.endedAt)
        k.running = null
        k.boot()
        assertNotNull(dao.session("orphan")!!.endedAt)
        assertEquals(false, dao.session("orphan")!!.reportPending)
        assertNull(dao.session(realId)!!.endedAt, "실제 세션은 그대로")
    }

    @Test fun scenario_overrideDropsRealEvents() {
        k.profile(); k.boot()
        k.host.startScenario(setup(override = setOf("CabinTemp"))); k.host.awaitIdle()
        k.host.postReal(CabinTemp(0, 19.1)); k.host.awaitIdle()
        assertNull(k.state().car.cabinC, "진짜 신호는 버린다")
        k.ev(CabinTemp(0, 30.0))
        assertEquals(30.0, k.state().car.cabinC, "장면 주입은 받는다")
        k.host.endScenario(); k.host.awaitIdle()
        k.host.postReal(CabinTemp(0, 19.1)); k.host.awaitIdle()
        assertEquals(19.1, k.state().car.cabinC, "끝나면 다시 받는다")
    }

    @Test fun scenario_endDoesNotKeepInjectedOverrideCar() {
        k.profile(); k.boot()
        k.host.startScenario(setup(override = setOf("CallMode"))); k.host.awaitIdle()
        k.ev(CallMode(0, true))
        assertTrue(k.state().car.inCall, "장면 주입")
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(false, k.state().car.inCall, "실제 값으로 되돌린다")
    }

    @Test fun scenario_endClearsRealSpeaking() {
        k.profile(); k.boot()
        k.ev(GearChanged(0, Gear.P)).ev(RearDoor(0, Side.LEFT, true)).ev(RearDoor(0, Side.LEFT, false))   // 마이크 꺼짐 → S16
        assertNotNull(k.state().speech.speaking, "실제 상태가 말하는 중")
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.host.endScenario(); k.host.awaitIdle()
        assertNull(k.state().speech.speaking, "콜백이 사라진 말하기는 복원처럼 비운다")
    }

    // ---- Suspend 짐작(C10·C67) ----

    @Test fun c67_clockJumpWithoutSignal_doesNotCountSleepAsQuiet() {
        k.profile(); k.boot(); k.open(); k.alarm()
        k.ev(DriverStop(0, Via.VOICE))
        assertEquals(Phase.WAIT, k.ses().phase)
        val q = k.ses().quietMs
        k.slept += 600_000
        k.ev(Tick(0), k.now + 600_000)
        assertEquals(Phase.WAIT, k.ses().phase, "잠든 10분이 에피소드를 닫지 않는다")
        assertNotNull(k.ses().episode)
        assertEquals(q, k.ses().quietMs)
        assertNull(k.state().ign.suspendedAt)
    }

    @Test fun c67_jumpAfterSuspendSignal_resumes() {
        k.profile(); k.boot(); k.open()
        k.host.suspend(); k.host.awaitIdle()
        assertNotNull(k.state().ign.suspendedAt)
        k.host.suspend(); k.host.awaitIdle()   // 중복은 호스트가 막는다
        k.slept += 600_000
        k.ev(Tick(0), k.now + 600_000)
        assertNull(k.state().ign.suspendedAt)
    }

    // ---- 기록 지우기(5.9, C90) ----

    @Test fun c40_lateControlLogAfterClear_notRecreated() {
        k.profile(); k.boot(); k.open()
        val sid = k.ses().id
        k.ev(ClearRecords(0))
        val row = { s: String? -> kr.aigyeot.app.store.db.ControlLogEntity(sessionId = s, at = k.now, api = "a", target = "t", goal = "g", actual = null, ackMs = null, appliedMs = null, ok = true, retries = 0) }
        k.host.logControl(row(sid)); k.host.awaitIdle()
        val q = { k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM control_log").use { it.moveToFirst(); it.getInt(0) } }
        assertEquals(0, q())
        k.host.logControl(row(null)); k.host.awaitIdle()
        assertEquals(1, q())
    }


    @Test fun c40_clearRecords_storeOneTransaction_keepsProfileLinkSongs() {
        k.profile(); k.phone(); k.boot(); k.open(); k.alarm()
        sql("INSERT INTO outbox (id, type, to_device, body_json, created_at, attempts, next_try_at) VALUES ('o1', 'handoff_alert', NULL, '{}', 0, 0, 0), ('o2', 'feeding_state', NULL, '{}', 0, 0, 0)")
        k.ev(ClearRecords(0))
        val q = { t: String -> k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $t").use { it.moveToFirst(); it.getInt(0) } }
        listOf("session", "episode", "play_log", "alarm_log", "context_sample", "control_log", "steer_window").forEach { assertEquals(0, q(it), it) }
        val types = k.db.openHelper.readableDatabase.query("SELECT type FROM outbox ORDER BY type").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf("feeding_state", "records_clear"), types)
        assertEquals(1, q("child_profile")); assertEquals(1, q("phone_link"))
        assertTrue(k.store.loadSnapshot()!!.history.isEmpty())
        assertTrue(k.ses().recordsCleared)
    }

    // ---- 검토 수정 C1~C4 ----

    @Test fun c78_restoreDropsOpenSteerWindow_doesNotOverwriteCommittedRow() {
        k.profile(); k.boot(); k.open()
        k.ev(VehicleSample(0, 50.0, false, 1.0, TurnSignal.NONE), k.now + 1_000)
        k.host.flush(); k.host.awaitIdle()
        val w = k.store.loadSnapshot()!!.steer.window
        assertNotNull(w, "스냅숏에 진행 중 조향 창이 있다(전제)")
        val sid = k.ses().id
        // 그 창은 이미 닫혀 커밋됐는데 스냅숏만 뒤처졌다
        dao.upsertSteer(kr.aigyeot.app.store.db.SteerWindowEntity(sid, w.startAt, 2, false, false, false, 9))
        k.boot()
        assertNull(k.state().steer.window, "복원 뒤 진행 중 창은 버려진다")
        k.ev(VehicleSample(0, 50.0, false, 2.0, TurnSignal.NONE), w.startAt + 61_000)
        assertEquals(9, dao.steerWindows(sid).single { it.startAt == w.startAt }.reversals, "완성 행을 옛 부분 집계로 덮지 않는다")
    }

    @Test fun c92_scenarioEndWithLiveMic_gapWindowRestartsWithoutMicStopped() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        assertEquals(Phase.GAP, k.ses().phase)
        assertEquals(emptySet(), k.running, "마이크 서비스가 살아 있다(전제)")
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.now += 5_000
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(Phase.GAP, k.ses().phase)
        assertTrue(k.state().car.micRunning)
        assertTrue(Notice.MIC_STOPPED !in k.notices)
        assertTrue(k.ses().pauses.isEmpty())
        assertEquals(k.now + Params().gapMs, k.ses().windowEnd, "판정 창을 지금부터 다시 잰다")
    }

    @Test fun c93_urgentFromOtherThread_checksClockJump() {
        k.profile(); k.boot(); k.open(); k.alarm()
        k.ev(DriverStop(0, Via.VOICE))
        assertEquals(Phase.WAIT, k.ses().phase)
        val q = k.ses().quietMs
        k.slept += 600_000
        k.now += 600_000
        thread { k.host.post(PlaybackRejected(0, "unknown", RejectReason.values().first())) }.join()
        k.host.awaitIdle()
        assertEquals(Phase.WAIT, k.ses().phase, "잠든 10분이 에피소드를 닫지 않는다")
        assertNotNull(k.ses().episode)
        assertEquals(q, k.ses().quietMs)
    }

    @Test fun restoreRoomEndedSession_historyRecounted() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        val ep = k.ses().episode!!
        assertTrue(k.state().history.isEmpty(), "스냅숏에는 성적이 아직 없다(전제)")
        sql("UPDATE episode SET first_result = 'FAIL', ended_at = ${k.now} WHERE id = '${ep.id}'")
        sql("UPDATE session SET ended_at = ${k.now} WHERE id = '${k.ses().id}'")
        k.boot()
        assertNull(k.state().session)
        assertEquals(1, k.state().history.size, "세션 끝과 함께 커밋된 1차 FAIL이 성적에 든다")
        assertEquals(false, k.state().history.single().success)
    }

    @Test fun eventsBeforeStart_processedAfterRestore() {
        k.profile(); k.boot()
        assertEquals(false, k.state().car.inCall, "스냅숏은 통화 아님(전제)")
        k.host.close()
        val h = Host(k.store, k.clock, mic = MicPort({ null }, {}), userId = { 10 })
        h.targets = Targets({}, {}, {})
        h.post(CallMode(0, true))   // exec.start()가 복원(start)보다 먼저 낸 지금 통화 상태
        h.start(); h.awaitIdle()
        assertTrue(h.state().car.inCall, "복원 전에 온 사건을 버리지 않고 복원 뒤 처리한다")
        h.close()
    }
}
