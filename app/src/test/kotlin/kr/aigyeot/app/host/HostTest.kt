package kr.aigyeot.app.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kr.aigyeot.app.scenario.FakeWorld
import kr.aigyeot.app.scenario.SceneGate
import kr.aigyeot.app.scenario.SceneRun
import kr.aigyeot.app.scenario.parseScene
import kr.aigyeot.app.store.db.PlayLogEntity
import kr.aigyeot.engine.*
import kr.aigyeot.app.link.Link
import org.junit.After
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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

    @After fun close() { k.host.close(); k.linkScope.cancel(); k.db.close() }

    /** stepLog를 지금 이 스레드에서 구독한다(UNDISPATCHED: 돌아올 때 이미 붙어 있다). */
    private fun collect(on: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined, f: (Step) -> Unit) =
        CoroutineScope(on).also { it.launch(start = CoroutineStart.UNDISPATCHED) { k.host.stepLog.collect(f) } }

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

    @Test fun c78_departCheckedSavedImmediately() {
        k.profile(); k.boot(); k.open()
        val s0 = k.savedAt()
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        assertTrue(k.ses().departChecked)                    // 수유 시작이 없어 제안은 없고 확인만 됨(F5a)
        assertNotEquals(s0, k.savedAt(), "30초를 기다리지 않고 바로 저장")
        assertTrue(k.store.loadSnapshot()!!.session!!.departChecked)
    }

    @Test fun c119_careSummarySavedImmediately_andClearedByClearRecords() {
        k.profile(); k.boot(); k.open()
        val s0 = k.savedAt()
        val c = CareView(k.now, true, null, null, null, null, null, null, null, 2)
        k.ev(CareSummary(0, c), k.now + 1_000)
        assertEquals(c, k.state().care)
        assertNotEquals(s0, k.savedAt(), "30초를 기다리지 않고 바로 저장")
        assertEquals(c, k.store.loadSnapshot()!!.care)
        k.ev(CareSummary(0, c.copy(asOf = c.asOf - 1)), k.now + 1_000)    // 더 이른 것은 무시: 상태 그대로
        assertEquals(c, k.state().care)
        k.ev(ClearRecords(0), k.now + 1_000)
        assertNull(k.state().care); assertNull(k.store.loadSnapshot()!!.care)
    }

    @Test fun c78_vehicleSampleBatched_every30s() {
        k.profile(); k.boot(); k.open()
        val s0 = k.savedAt()
        k.ev(VehicleSample(0, 50.0, false, 1.0, TurnSignal.NONE), k.now + 1_000)
        assertNotEquals(k.state(), k.store.loadSnapshot(), "상태는 바뀌었다(조향 누적)")
        assertEquals(s0, k.savedAt(), "30초 안에는 묶는다")
        k.ev(VehicleSample(0, 50.0, false, 2.0, TurnSignal.NONE), k.now + Params().snapshotIntervalMs)
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
        k.ev(Tick(0), k.now + Params().snapshotIntervalMs)
        assertTrue(k.store.loadSnapshot()!!.ign.doorOpenedSinceP, "Tick만 와도 건너뛴 변경을 저장한다")
    }

    @Test fun c78_tickOnlyNowChange_notSaved() {
        k.profile(); k.boot()
        val s0 = k.savedAt()
        k.ev(Tick(0), k.now + 60_000)
        assertEquals(s0, k.savedAt())
    }

    @Test fun c78_batchInterval_fromParams() {
        k.profile(); k.boot(Params(snapshotIntervalMs = 5_000)); k.open()
        val s0 = k.savedAt()
        k.ev(VehicleSample(0, 50.0, false, 1.0, TurnSignal.NONE), k.now + 4_000)
        assertEquals(s0, k.savedAt(), "Params 간격(5초) 안에는 묶는다")
        k.ev(VehicleSample(0, 50.0, false, 2.0, TurnSignal.NONE), k.now + 1_000)
        assertEquals(k.now, k.savedAt(), "Params.snapshotIntervalMs가 지나면 저장(상수 30초가 아니다)")
    }

    // ---- Step 흐름(계약 2.4, 손실 없음) ----

    @Test fun stepLog_slowCollector_getsEveryStepInOrder() {
        k.profile(); k.boot()
        val got = CopyOnWriteArrayList<Double?>()
        val scope = collect(Dispatchers.Default) { Thread.sleep(2); got += it.state.car.cabinC }   // 느린 수집자
        repeat(200) { k.host.post(CabinTemp(0, it.toDouble())) }
        k.host.awaitIdle()
        val until = System.currentTimeMillis() + 10_000
        while (got.size < 200 && System.currentTimeMillis() < until) Thread.sleep(10)
        scope.cancel()
        assertEquals((0 until 200).map { it.toDouble() }, got.toList(), "중간 Step을 건너뛰지 않고 순서대로")
    }

    @Test fun stepLog_overflow_countedAndFailsSceneRun() {
        k.profile(); k.boot()
        val scene = parseScene("""{"format":1,"id":"T","title":"t","repeat":1,"setup":{"profile":{"birthYm":"2026-02","carseat":"RIGHT","feedingIntervalMin":null,"hasSibling":false},"methods":["WHITE_NOISE"],"history":[],"seed":1,"override":[],"otherGuardians":0},
            "steps":[{"t":1,"tap":"BABY_ABOARD"}]}""")
        val run = SceneRun(scene, 0, k.host, FakeWorld(), k.now)   // 반복 시작: 지금 유실 수를 기억한다
        val gate = CountDownLatch(1)
        val scope = collect(Dispatchers.Default) { gate.await() }   // 멈춘 수집자: 버퍼(4096)를 넘기면 버려진다
        repeat(4_200) { k.host.post(CabinTemp(0, it.toDouble())) }
        k.host.awaitIdle()
        val lost = k.host.stepLogDrops.value
        gate.countDown(); scope.cancel()
        assertTrue(lost > 0, "버퍼가 넘친 수를 센다")
        run.checkStepLog()
        val row = run.row()
        assertEquals(false, row.completed, "Step이 버려진 반복은 통과로 남지 않는다")
        assertTrue(row.failures.any { "stepLog 유실 $lost" in it }, row.failures.toString())
    }

    // ---- 복원 뒤 대조(contracts-link 8.3)·바로 다시 확인(8.6, C67) ----

    @Test fun restoreEvents_fromLink_afterBoot_beforeEarlyEvents() {
        k.profile(); k.boot()
        k.host.flush(); k.host.awaitIdle()                                   // 스냅숏이 있다(fresh면 Room에서 읽어 대조할 것이 없다)
        sql("UPDATE child_profile SET last_feeding_start = ${T0 + 5_000}")   // ack 뒤 사건 전에 죽었다: Room만 새 값
        k.host.close()
        val h = Host(k.store, k.clock, mic = MicPort({ null }, {}), userId = { 10 })
        val asked = mutableListOf<Long?>()
        h.targets = Targets({}, {}, {}, restore = { s, at, refill -> asked += s.feedingStart; k.realLink.restoreEvents(s, at, refill) })
        k.host = h
        val seen = CopyOnWriteArrayList<Step>()
        val scope = collect { seen += it }
        h.post(CallMode(0, true))   // 복원 전에 온 사건(모듈이 시작하며 낸 지금 상태)
        h.start(); h.awaitIdle()
        scope.cancel()
        assertEquals(1, asked.size, "link.restoreEvents를 한 번 부른다")
        assertNotEquals(T0 + 5_000, asked.single(), "스냅숏(Boot 뒤) 값으로 견준다")
        val fed = seen.indexOfFirst { it.state.feedingStart == T0 + 5_000 }
        val call = seen.indexOfFirst { it.state.car.inCall }
        assertTrue(fed > 0, "Boot 뒤에 FeedingStarted")
        assertTrue(fed < call, "첫 판단(복원 전에 온 사건)보다 먼저")
    }

    @Test fun c67_screenOnAndResume_wakeLink() {
        k.profile(); k.boot()
        k.host.resume(); k.host.awaitIdle()
        assertEquals(1, k.wakes, "화면 켜짐은 Suspend가 아니어도 바로 다시 확인")
        k.host.suspend(); k.host.resume(); k.host.awaitIdle()
        assertEquals(2, k.wakes, "Resume 짐작")
    }

    @Test fun c67_clockJump_wakesLink() {
        k.profile(); k.boot()
        assertEquals(0, k.wakes)
        k.slept += 600_000
        k.ev(Tick(0), k.now + 600_000)
        assertEquals(1, k.wakes, "신호 없는 시계 점프")
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

    @Test fun c133_restoreRoomEndedEpisode_pendingHandoff_droppedLineSavesToldDriverFalse() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        val s = k.state(); val ep = s.session!!.episode!!
        // 인계 줄이 큐에 있던 스냅숏, 에피소드 끝(handoff 칸 포함)만 Room에 저장된 채 죽었다
        k.store.saveSnapshot(s.copy(session = s.session!!.copy(pendingHandoffAlert = PendingHandoffAlert(
            HandoffKind.WAYPOINT_OFFER, Sid.S07, "n", k.now, null, s.session!!.id, ep.id, k.now, alert = false))))
        sql("UPDATE episode SET first_result = 'FAIL', handoff_cause = 'TWO_ATTEMPTS', handoff_kind = 'WAYPOINT_OFFER', ended_at = ${k.now} WHERE id = '${ep.id}'")
        k.running = null
        k.boot()   // 큐에 그 줄이 없다 → 버려짐
        assertNull(k.state().session!!.pendingHandoffAlert)
        val c = k.db.openHelper.readableDatabase.query("SELECT handoff_told_driver FROM episode WHERE id = '${ep.id}'")
        c.moveToFirst(); assertTrue(!c.isNull(0), "Room에 null이 남지 않는다"); assertEquals(0, c.getInt(0)); c.close()
        sql("UPDATE session SET ended_at = ${k.now} WHERE id = '${k.ses().id}'")
        val r = kr.aigyeot.app.store.buildReport(k.db.storeDao(), k.ses().id, k.now)!!
        assertEquals(false, r.timeline.single().stop!!.toldDriver)
    }

    /** C136: 에피소드 handoff가 TWO_ATTEMPTS/WAYPOINT_OFFER, S07이 큐에 묶인 스냅숏을 저장하고 죽은 상태를 만든다. */
    private fun c136Snapshot(speaking: String? = null, extra: List<Line> = emptyList(), mk: (Long) -> Line = { Line(Sid.S07, mapOf("restName" to "n 휴게소"), "n", queuedAt = it) }, lastOffer: (Long) -> Map<Sid, Long> = { emptyMap() }): Pair<String, Line> {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        val s = k.state(); val ep = s.session!!.episode!!
        val line = mk(k.now)
        k.store.saveSnapshot(s.copy(
            speech = s.speech.copy(speaking = speaking, queue = listOf(line) + extra),
            session = s.session!!.copy(lastOffer = lastOffer(k.now),
                episode = ep.copy(handoff = HandoffRecord(HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, false, false)),
                pendingHandoffAlert = PendingHandoffAlert(HandoffKind.WAYPOINT_OFFER, line.sid, line.askRestId, k.now, null, s.session!!.id, ep.id, k.now, alert = false))))
        sql("UPDATE episode SET handoff_cause = 'TWO_ATTEMPTS', handoff_kind = 'WAYPOINT_OFFER' WHERE id = '${ep.id}'")
        return ep.id to line
    }
    private fun toldDriverRow(id: String): Int? = k.db.openHelper.readableDatabase.query("SELECT handoff_told_driver FROM episode WHERE id = '$id'").use { it.moveToFirst(); if (it.isNull(0)) null else it.getInt(0) }

    @Test fun c136_restoreRoomToldDriverWins_noReask() {
        val (id, _) = c136Snapshot()
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소' WHERE id = '$id'")   // 질문을 내며 저장했다
        k.running = null; k.exec.clear()
        k.boot()
        val x = k.ses()
        assertNull(x.pendingHandoffAlert); assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 }, "묶인 줄을 지운다")
        assertEquals(true, x.episode!!.handoff!!.toldDriver); assertEquals("n 휴게소", x.episode!!.handoff!!.restName)
        assertTrue(k.exec.none { it is Ask || (it is SendToPhone) }, "다시 묻거나 알리지 않는다")
        // (b) 내비 응답 전 D로 경로가 비어도 false로 덮지 않는다
        k.ev(GearChanged(0, Gear.D), k.now + 1_000)
        assertEquals(1, toldDriverRow(id)); assertEquals("n 휴게소", k.ses().episode?.handoff?.restName ?: "n 휴게소")
    }

    @Test fun c136_restoreToldTrue_longDriveLine_marksOffered() {
        val (id, _) = c136Snapshot(mk = { Line(Sid.S10, emptyMap(), null, longDrive = true, queuedAt = it) })
        sql("UPDATE episode SET handoff_told_driver = 1 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertTrue(k.ses().longDriveOffered, "말한 장시간 제안은 줄만 빼지 않고 longDriveOffered를 세운다(C84)")
        assertNotNull(k.ses().lastOffer[Sid.S10])
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S10 })
    }

    @Test fun c136_restoreToldFalse_dropsLine_forgetsOffer() {
        val (id, _) = c136Snapshot(lastOffer = { mapOf(Sid.S07 to it) })   // 넣을 때 적은 10분 금지(값 = queuedAt = 이 시각)
        sql("UPDATE episode SET handoff_told_driver = 0 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.ses().lastOffer[Sid.S07], "듣지 못한 제안의 금지를 지운다(forgetOffer)")
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 })
    }

    @Test fun c136_restoreToldTrue_otherSessionLine_keepsState() {
        val (id, _) = c136Snapshot(mk = { Line(Sid.S10, emptyMap(), null, longDrive = true, queuedAt = it, sessionId = "other") })
        sql("UPDATE episode SET handoff_told_driver = 1 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertEquals(false, k.ses().longDriveOffered, "C141: 다른 세션 줄은 지금 세션 상태를 바꾸지 않는다")
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S10 })
    }

    @Test fun c136_restoreRoomToldDriverNull_keepsPending() {
        c136Snapshot()
        k.running = null
        k.boot()
        assertNotNull(k.ses().pendingHandoffAlert, "Room 값이 null이면 지금처럼 대기")
    }

    @Test fun c136_restoreRoomEndedEpisode_toldDriverWins_closedRowKept() {
        val (id, _) = c136Snapshot()
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소', first_result = 'FAIL', ended_at = ${k.now} WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.ses().pendingHandoffAlert); assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 })
        assertEquals(1, toldDriverRow(id), "닫힌 행의 true를 false로 덮지 않는다")
    }

    @Test fun c136_restoreSpeakingFrontLine_handledOnceWithC123() {
        val other = Line(Sid.S08, emptyMap(), null, queuedAt = 1)
        val (id, _) = c136Snapshot(speaking = "u1", extra = listOf(other))
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소' WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.ses().pendingHandoffAlert)
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 }, "말하던 줄은 한 번만 버린다")
        assertEquals(1, toldDriverRow(id))
    }

    @Test fun c137_restoreRoomEndedSession_dropsPendingHandoffLine_keepsOthers() {
        val other = Line(Sid.S12, emptyMap(), null, queuedAt = 1)   // 세션과 무관한 문장
        val offer = Line(Sid.S08, emptyMap(), null, queuedAt = 1)   // C140: 일반 제안도 closeSession처럼 뺀다
        val (id, _) = c136Snapshot(extra = listOf(offer, other))
        sql("UPDATE session SET ended_at = ${k.now} WHERE id = '${k.ses().id}'")
        k.running = null; k.exec.clear()
        k.boot()
        assertNull(k.state().session)
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 }, "묶인 줄을 지운다")
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S08 }, "C140: 일반 제안 줄도 closeSession처럼 뺀다")
        assertTrue(k.state().speech.queue.any { it.sid == Sid.S12 }, "세션과 무관한 줄은 남는다")
        assertTrue(k.exec.none { it is SendToPhone }, "알림을 내지 않는다")
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

    @Test fun r14x_restoreDropsSpeakingLine() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val line = Line(Sid.S07, mapOf("restName" to "n 휴게소"), "n")
        k.store.saveSnapshot(s.copy(
            speech = s.speech.copy(speaking = "u1", queue = listOf(line)),
            session = s.session!!.copy(lastOffer = mapOf(Sid.S07 to k.now)),
        ))
        k.boot()
        assertTrue(k.state().speech.queue.isEmpty(), "말하던 줄은 다시 말하지 않는다(C123)")
        assertEquals(k.now, k.ses().lastOffer[Sid.S07], "10분 금지 시각은 남는다")
        // 말하던 줄이 없었으면(speaking == null) 큐는 그대로
        val s2 = k.state()
        k.store.saveSnapshot(s2.copy(speech = s2.speech.copy(speaking = null, queue = listOf(line))))
        k.boot()
        assertTrue(k.state().speech.queue.isNotEmpty() || k.exec.any { it is Ask }, "speaking이 없으면 줄을 버리지 않는다")
    }

    @Test fun c128_clearRecordsQueued_oldGenSummaryDoesNotRevive() {
        k.profile(); k.boot()
        val c = CareView(k.now, true, null, null, null, null, null, null, null, 2)
        val gen = k.state().careGen
        k.host.post(ClearRecords(0))                           // 큐에 지우기가 먼저 대기하고
        k.host.postReal(CareSummary(0, c, seq = 430, gen = gen))   // link가 지우기 전 세대로 만든 요약이 뒤에 선다
        k.host.awaitIdle()
        assertNull(k.state().care)
        assertEquals(gen + 1, k.state().careGen)
    }

    @Test fun c132_sceneEnd_summaryHeldBeforeClear_notRestored() {   // 장면 중 큐에 묶여 있던 gen 0 요약이 지우기 뒤 되살아나지 않는다
        k.profile(); k.boot()
        val gen0 = k.state().careGen
        k.ev(ClearRecords(0))
        k.host.startScenario(setup(override = setOf("CareSummary"))); k.host.awaitIdle()
        k.host.postReal(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 2), seq = 430, gen = gen0)); k.host.awaitIdle()
        k.host.endScenario(); k.host.awaitIdle()
        assertNull(k.state().care); assertNull(k.store.loadSnapshot()!!.care)
        assertEquals(gen0 + 1, k.state().careGen)
    }

    @Test fun c132_scene_carriesRealCareGen_andCurrentGenSummaryApplies() {   // 장면 진입이 세대를 0으로 되돌리지 않는다
        k.profile(); k.boot()
        k.ev(ClearRecords(0))
        val gen = k.state().careGen
        k.host.startScenario(setup()); k.host.awaitIdle()
        assertEquals(gen, k.state().careGen)
        val c = CareView(k.now, true, null, null, null, null, null, null, null, 3)
        k.host.postReal(CareSummary(0, c, seq = 5, gen = gen)); k.host.awaitIdle()
        assertEquals(c, k.state().care, "지금 세대의 실제 요약은 장면 안에서도 적용된다")
    }

    @Test fun c132_clearRecordsStep_publishedAfterLinkHandledDeleteRelay() {
        k.profile(); k.boot()
        val gen = k.state().careGen
        var genWhenLinked: Int? = null
        val t = k.host.targets
        k.host.targets = Targets(t.exec, { c -> if (c is DeleteRelayRecords) genWhenLinked = k.host.steps.value!!.state.careGen; t.link(c) }, t.notice, t.wake, t.restore, t.routeAgain)
        k.ev(ClearRecords(0))
        assertEquals(gen, genWhenLinked, "link가 삭제 표시를 할 때 새 세대는 아직 공개되지 않았다")
        assertEquals(gen + 1, k.host.steps.value!!.state.careGen)
    }

    @Test fun r14x_restoreDroppedLongDrive_notAskedAgain() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val line = Line(Sid.S11, mapOf("restName" to "n 휴게소"), "n", longDrive = true)
        k.store.saveSnapshot(s.copy(
            speech = s.speech.copy(speaking = "u1", queue = listOf(line)),
            session = s.session!!.copy(pendingAsk = PendingAsk("a1", Sid.S11, "n", null, true), driveFrom = k.now - 7_300_000),
        ))
        k.boot()
        assertTrue(k.ses().longDriveOffered, "버린 장시간 제안은 말한 것으로 친다(C128)")
        assertEquals(k.now, k.ses().lastOffer[Sid.S11])
        assertTrue(k.exec.none { it is Listen }, "듣기는 열지 않는다")
        val before = k.exec.count { it is Ask && it.sid == Sid.S11 }
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        assertEquals(before, k.exec.count { it is Ask && it.sid == Sid.S11 }, "같은 질문을 다시 만들지 않는다")
    }

    private val rest10 = RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 10.0, true)), 150.0)

    /** C138: 복원 큐에 말하지 않은 장시간 제안이 있다. 상태는 큐에 S10(longDrive)과 호출자가 고른 줄을 둔 채 복원한다. */
    private fun restoreWithQueued(vararg lines: Line) {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        k.store.saveSnapshot(s.copy(speech = s.speech.copy(queue = lines.toList()), session = s.session!!.copy(driveFrom = k.now - 7_300_000), car = s.car.copy(gear = Gear.P)))
        k.boot()
    }
    private val s10 = Line(Sid.S10, emptyMap(), null, longDrive = true)

    @Test fun c138_restoreQueuedLongDrive_notSpokenBeforeRoute_thenJudgedByNewRoute() {
        restoreWithQueued(s10)
        assertTrue(k.state().speech.queue.none { it.longDrive }, "말하지 않은 장시간 줄은 복원 ③에서 뺀다")
        assertEquals(false, k.ses().longDriveOffered)
        k.ev(GearChanged(0, Gear.D), k.now + 5_000)
        assertTrue(k.exec.none { it is Speak && it.sid == Sid.S10 }, "경로 응답 전에 옛 쉼터 없음 문장을 말하지 않는다")
        k.ev(rest10, k.now + 1_000)
        assertTrue(k.exec.none { it is Speak && it.sid == Sid.S10 }, "10km 앞 휴게소가 있으면 쉼터 없음이 아니다")
        assertTrue(k.exec.any { (it is Ask && it.sid == Sid.S11) || (it is Speak && it.sid == Sid.S11) }, "새 경로로 S11을 판단한다")
    }

    @Test fun c138_restoreQueuedLongDrive_noRouteFor60s_judgedWithoutRoute() {
        restoreWithQueued(s10)
        k.ev(GearChanged(0, Gear.D), k.now + 5_000)
        k.ev(Tick(0), k.now + Params().routeWaitMs + 1_000)
        assertTrue(k.exec.any { it is Speak && it.sid == Sid.S10 }, "60초 뒤 경로 없이 판단해 말한다")
    }

    @Test fun c138_restoreQueued_nonLongDriveLineKept() {
        val keep = Line(Sid.S06, emptyMap(), null)
        restoreWithQueued(keep, s10)
        assertEquals(listOf(keep.sid), k.state().speech.queue.map { it.sid }, "장시간이 아닌 줄은 그대로")
    }

    @Test fun c138_restoreSpeakingLongDrive_c123StillCountsAsSaid_andQueuedLongDriveDropped() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val speaking = Line(Sid.S10, emptyMap(), null, longDrive = true)
        val queued = Line(Sid.S11, mapOf("restName" to "n 휴게소"), "n", longDrive = true)
        k.store.saveSnapshot(s.copy(speech = s.speech.copy(speaking = "u1", queue = listOf(speaking, queued)), session = s.session!!.copy(driveFrom = k.now - 7_300_000)))
        k.boot()
        assertTrue(k.ses().longDriveOffered, "말하던 줄은 C123이 말한 것으로 친다")
        assertTrue(k.state().speech.queue.isEmpty())
    }

    @Test fun r14y_restoreClearsRoute() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val route = RouteView(k.now, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0)
        k.store.saveSnapshot(s.copy(car = s.car.copy(route = route, gear = Gear.P)))
        k.boot()
        assertNull(k.state().car.route, "옛 경로로 판단하지 않는다(C125)")
        k.ev(GearChanged(0, Gear.D), k.now + 1_000)
        assertEquals(false, k.ses().departChecked, "내비 응답 전에는 출발 제안을 판단하지 않는다")
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        assertTrue(k.ses().departChecked)
    }

    @Test fun c121_pendingHandoffAlertChange_savedImmediately() {
        k.profile(); k.boot(); k.open()
        val old = k.state()
        val alert = PendingHandoffAlert(HandoffKind.WAYPOINT_OFFER, Sid.S07, "n", k.now, null, old.session!!.id, null, k.now)
        val withAlert = old.copy(session = old.session!!.copy(pendingHandoffAlert = alert))
        assertTrue(k.host.needsSnapshot(old, Step(withAlert, emptyList(), null)), "두는 Step")
        assertTrue(k.host.needsSnapshot(withAlert, Step(old, emptyList(), null)), "보내 지우는 Step")
    }

    @Test fun c123_speakOrAskSavedImmediately() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        for (c in listOf<Command>(Speak("u1", Sid.S06, emptyMap()), Ask("u2", Sid.S07, emptyMap(), 5_000)))
            assertTrue(k.host.needsSnapshot(s, Step(s, listOf(c), null)), "$c")
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

    @Test fun c119_scenarioCare_overridesRealSummary_andEndRestoresReal() {
        k.profile(); k.boot()
        val real = CareView(k.now, true, null, null, null, null, null, null, null, 5)
        k.ev(CareSummary(0, real))
        val fake = real.copy(asOf = real.asOf - 60_000, todayFeedCount = 2)
        k.host.startScenario(setup(override = setOf("CareSummary")).copy(care = fake)); k.host.awaitIdle()
        assertEquals(fake, k.state().care, "장면 setup.care가 첫 Step 뒤 들어간다")
        val newer = real.copy(asOf = real.asOf + 1_000)
        k.host.postReal(CareSummary(0, newer)); k.host.awaitIdle()
        assertEquals(fake, k.state().care, "실제 link 요약은 막힌다")
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(newer, k.state().care, "끝나면 실제로 받은 가장 늦은 값")
    }

    private fun careEndsAs(override: Set<String>, during: CareView?): CareView? {
        k.profile(); k.boot()
        k.ev(ClearRecords(0))   // 같은 kit으로 되풀이해 부른다. 앞 호출이 장면 끝에 바로 저장한 요약(C126 ④)이 다음 boot로 이어지지 않게
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c1, gen = k.state().careGen))
        k.host.startScenario(setup(override = override)); k.host.awaitIdle()
        during?.let { k.host.postReal(CareSummary(0, it, gen = k.state().careGen)); k.host.awaitIdle() }
        k.host.endScenario(); k.host.awaitIdle()
        return k.state().care
    }

    @Test fun c119_realSummaryDuringScene_survivesSceneEnd() {
        val c2 = CareView(T0 + 5_000, true, null, null, null, null, null, null, null, 9)
        assertEquals(c2, careEndsAs(emptySet(), c2), "override 없는 장면: 장면 상태에 들어갔다가 끝나도 남는다")
        assertEquals(c2, careEndsAs(setOf("CareSummary"), c2), "override 장면: 장면에는 안 들어가도 끝나면 실제 최신 값")
        assertEquals(1, careEndsAs(setOf("CareSummary"), null)!!.todayFeedCount, "장면 전 값만 있으면 그대로")
        assertEquals(1, careEndsAs(emptySet(), c2.copy(asOf = T0 - 5_000))!!.todayFeedCount, "장면 전 값보다 이른 요약은 이기지 못한다")
    }

    @Test fun c126_sameSecondSeq_savedImmediately_evenWhenOnlySeqChanges() {
        k.profile(); k.boot(); k.open()
        val c = CareView(k.now, true, null, null, null, null, null, null, null, 2)
        k.ev(CareSummary(0, c, seq = 5), k.now + 1_000)
        val s0 = k.savedAt()
        k.ev(CareSummary(0, c, seq = 6), k.now + 1_000)    // 같은 초·같은 내용, seq만 큼: careSeq가 key에 들어 있어야 바로 저장
        assertEquals(6L, k.state().careSeq)
        assertNotEquals(s0, k.savedAt())
        assertEquals(6L, k.store.loadSnapshot()!!.careSeq)
    }

    @Test fun c126_realSummaryDuringScene_sameSecondLaterSeq_restoredAtEnd_andSavedNow() {
        k.profile(); k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c1, seq = 5))
        k.host.startScenario(setup(override = setOf("CareSummary")).copy(care = c1.copy(asOf = k.now + 60_000, todayFeedCount = 9))); k.host.awaitIdle()
        val c2 = c1.copy(todayFeedCount = 2)                       // 장면 값보다 이르고 장면 전 실제 값과 같은 초지만 seq가 큰 실제 요약
        k.host.postReal(CareSummary(0, c2, seq = 6)); k.host.awaitIdle()
        assertEquals(9, k.state().care!!.todayFeedCount, "장면 중에는 장면 값")
        assertEquals(1, k.store.loadSnapshot()!!.care!!.todayFeedCount, "장면 중 실제 스냅숏은 장면 직전 값")
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(c2, k.state().care); assertEquals(6L, k.state().careSeq)
        assertEquals(c2, k.store.loadSnapshot()!!.care, "장면 끝에 실제 값으로 되돌린 상태는 바로 저장")
        assertEquals(6L, k.store.loadSnapshot()!!.careSeq)
    }

    // ---- C130
    @Test fun c130_realSummary_afterQueuedStartScenario_isKeptAndRestoredAtEnd() {
        for (override in listOf(emptySet(), setOf("CareSummary"))) {
            k.profile(); k.boot(); k.ev(ClearRecords(0))
            val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
            val gen = k.state().careGen
            k.ev(CareSummary(0, c1, gen = gen))
            val latch = java.util.concurrent.CountDownLatch(1)
            val entered = java.util.concurrent.CountDownLatch(1)
            val saved = k.host.targets
            k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = { entered.countDown(); latch.await() })
            k.host.resume()                                                  // 호스트 스레드를 붙잡는다(큐가 대기하는 동안)
            entered.await()
            k.host.startScenario(setup(override = override))                 // 큐에 장면 시작
            val newer = c1.copy(asOf = c1.asOf + 1_000, todayFeedCount = 7)
            k.host.postReal(CareSummary(0, newer, gen = gen))               // 실행 전에 새 실제 요약이 온다
            latch.countDown(); k.host.awaitIdle()
            k.host.targets = saved
            k.host.endScenario(); k.host.awaitIdle()
            assertEquals(newer, k.state().care, "override=$override: 장면 끝에 장면 전 값이 아니라 새 실제 요약")
            assertEquals(newer, k.store.loadSnapshot()!!.care)
        }
    }

    @Test fun c130_endScenario_callsRouteAgain_afterRestoreClearedRoute() {
        k.profile(); k.boot()
        val route = RouteState(0, true, 3_000, emptyList(), 80.0)
        k.ev(route)
        assertEquals(true, k.state().car.route != null)
        val saved = k.host.targets
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = saved.restore, routeAgain = { k.host.post(route) })   // exec가 최신 RouteState를 다시 낸다
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(true, k.state().car.route != null, "복원 ③이 비운 경로가 돌아온다")
    }

    @Test fun c133_sceneResume_doesNotRefillRealCare_bootAndSceneEndDo() {   // 장면 중 Resume 대조는 실제 요약 기준을 다시 채우지 않는다
        k.profile(); k.boot()
        val flags = mutableListOf<Boolean>()
        val saved = k.host.targets
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = { st, at, refill -> flags += refill; saved.restore(st, at, refill) })
        k.host.suspend(); k.host.resume(); k.host.awaitIdle()
        assertEquals(listOf(true), flags, "장면 없는 Resume")
        k.host.startScenario(setup()); k.host.awaitIdle()
        flags.clear()
        k.host.suspend(); k.host.resume(); k.host.awaitIdle()
        assertEquals(listOf(false), flags, "장면 중 Resume")
        flags.clear()
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(listOf(true), flags, "장면 끝 복원은 채운다")
    }

    @Test fun c130_snapshot_keepsCareRoom() {
        k.profile(); k.boot()
        val c = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c, seq = 5, gen = k.state().careGen, room = "c_A"))
        k.boot()
        assertEquals("c_A", k.state().careRoom); assertEquals(5L, k.state().careSeq)
    }

    @Test fun scenarioPhoneStep_feedingStarted_notBlockedByOverride_viaWiringSinks() {
        k.profile(); k.boot()
        val (real, injected) = k.host.linkSinks()
        val link = Link(androidx.test.core.app.ApplicationProvider.getApplicationContext(), k.db, k.clock, real, { null }, kr.aigyeot.app.link.FakeRelay(),
            kr.aigyeot.app.link.FakeCipher, { null }, { _, _ -> kr.aigyeot.app.exec.LoudnessResult(true) }, k.linkScope, postInjected = injected)
        k.host.startScenario(setup(override = setOf("FeedingStarted"))); k.host.awaitIdle()
        val at = T0 - 600_000
        val json = """{"v":1,"id":"01JC8Y2K6N3T5Q9W0ZB4H7X2D1","type":"feeding_started","ts":"2026-11-05T08:12:21+09:00","body":{"at":"${kr.aigyeot.app.link.rfc3339(at)}"}}"""
        kotlinx.coroutines.runBlocking { link.receiveForScenario(json) }; k.host.awaitIdle()
        assertEquals(at, k.state().feedingStart, "장면 phone 단계 주입은 override로 막히지 않는다(7.3)")
        real(FeedingStarted(0, at + 1_000, FeedingFrom.PHONE, null)); k.host.awaitIdle()
        assertEquals(at, k.state().feedingStart, "실제로 받은 사건은 override로 막힌다")
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

    @Test fun c92_startScenario_inheritsRealCar_startStepInStepLog() {
        k.profile(); k.boot(); k.open()   // 실제: 마이크 켜짐, 뒷문 닫힘, 기어 D, 세션 열림
        val real = k.state()
        val gate = SceneGate(7)
        val got = CopyOnWriteArrayList<Step>()
        val scope = collect { if (gate.accept(it)) got += it }
        k.host.post(CabinTemp(0, 21.0))   // startScenario보다 먼저 큐에 든 실제 사건
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.ev(Tick(0), k.now + 1_000)
        scope.cancel()
        val first = got.first()
        assertTrue(first.commands.isEmpty() && first.state.session == null, "장면 첫 Step부터 받는다")
        assertTrue(got.none { it.state.session?.id == real.session!!.id }, "그 앞 실제 Step은 버린다")
        assertEquals(Gear.D, first.state.car.gear, "실제 기어를 이어받는다")
        assertTrue(first.state.car.micRunning, "실제 마이크 상태")
        assertEquals(emptySet(), first.state.car.rearDoorOpen, "실제 뒷문(닫힘)")
        assertEquals(real.ign.driverUserId, first.state.ign.driverUserId)
        assertEquals(2, got.size, "시작 Step과 그 뒤 Tick")
    }

    @Test fun c92_endScenario_restoresOverriddenGearAndDoor() {
        k.profile(); k.boot(); k.open()
        k.host.startScenario(setup(override = setOf("GearChanged", "RearDoor"))); k.host.awaitIdle()
        k.ev(GearChanged(0, Gear.P)).ev(RearDoor(0, Side.LEFT, true))   // 장면 주입
        assertEquals(Gear.P, k.state().car.gear)
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(Gear.D, k.state().car.gear, "실제 기어로 되돌린다")
        assertEquals(emptySet(), k.state().car.rearDoorOpen, "실제 뒷문(닫힘)으로 되돌린다")
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
