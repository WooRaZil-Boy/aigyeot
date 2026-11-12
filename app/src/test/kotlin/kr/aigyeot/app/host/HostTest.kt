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
import kr.aigyeot.app.store.SnapshotJson
import kr.aigyeot.app.store.dbPause
import kr.aigyeot.app.store.toRecord
import kr.aigyeot.app.store.db.PlayLogEntity
import kr.aigyeot.app.store.db.SessionEntity
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

    @Test fun c119_careSummarySavedImmediately_andKeptByClearRecords() {
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
        assertEquals(c, k.state().care); assertEquals(c, k.store.loadSnapshot()!!.care, "지워도 돌봄 요약은 남는다(C157)")
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
        h.targets = Targets({}, {}, {}, restore = { s, at -> asked += s.feedingStart; k.realLink.restoreEvents(s, at) })
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

    @Test fun c274_wakeFailureOnClockJump_doesNotCutResume() {
        k.profile(); k.boot()
        k.host.targets.let { t -> k.host.targets = Targets(t.exec, t.link, t.notice, wake = { throw IllegalStateException("wake 실패") }, restore = t.restore, reemitAll = t.reemitAll, cancelIfSpeaking = t.cancelIfSpeaking, linkNow = t.linkNow) }
        k.slept += 600_000
        k.ev(Tick(0), k.now + 600_000)
        assertNull(k.state().ign.suspendedAt, "점프 Suspend·Resume이 끝까지 간다")
        assertTrue(k.host.state().now >= k.now, "Step이 이어졌다")
    }

    // C282: 재개 때 Room 대조(store.profile()·methods())가 던져도 Resume은 반드시 들어간다. 테이블을 지워 읽기가 실패하게 한다
    private fun breakStoreReads() = k.db.openHelper.writableDatabase.execSQL("DROP TABLE child_profile")

    @Test fun c282_reconcileFailureOnResume_doesNotCutResume() {
        k.profile(); k.boot()
        k.host.suspend(); k.host.awaitIdle()
        assertNotNull(k.state().ign.suspendedAt)
        breakStoreReads()
        k.host.resume(); k.host.awaitIdle()
        assertNull(k.state().ign.suspendedAt, "대조가 던져도 Resume이 들어간다")
        k.ev(Tick(0), k.now + 1_000)
        assertTrue(k.host.state().now >= k.now, "다음 사건이 이어진다")
    }

    @Test fun c282_reconcileFailureOnClockJump_doesNotCutResume() {
        k.profile(); k.boot()
        breakStoreReads()
        k.slept += 600_000
        k.ev(Tick(0), k.now + 600_000)
        assertNull(k.state().ign.suspendedAt, "대조가 던져도 점프 Resume이 들어간다")
        assertTrue(k.host.state().now >= k.now, "Step이 이어졌다")
    }

    // C289: 복원 중 Room 읽기 예외는 프로세스를 끝낸다(상태 없이 started만 참인 채 멈추지 않는다). 다시 뜨면 스냅숏에서 정상 복원
    private fun sqlExec(q: String) = k.db.openHelper.writableDatabase.execSQL(q)

    @Test fun c289_restoreFailureAtStart_diesThenNewHostRestores() {
        k.profile(); k.boot(); k.open()
        val id = k.ses().id
        k.host.flush(); k.host.awaitIdle()
        sqlExec("ALTER TABLE child_profile RENAME TO child_profile_x")   // 복원 ⑤의 Room 읽기가 실패한다
        val died = catchingHostDeath { k.boot() }
        assertNotNull(died, "복원 실패는 프로세스를 끝낸다")
        sqlExec("ALTER TABLE child_profile_x RENAME TO child_profile")
        k.boot()   // 죽었다 다시 뜸
        assertEquals(id, k.ses().id, "스냅숏에서 정상 복원")
    }

    // C291: 시작 길 전체가 fail-fast다(스냅숏·초기 상태·대기 리포트 읽기 예외도 프로세스를 끝낸다)
    private fun deathAt(q: String, undo: String): Throwable? {
        sqlExec(q)
        val died = catchingHostDeath { k.boot() }
        sqlExec(undo)
        return died
    }

    @Test fun c291_snapshotReadFailureAtStart_dies() {
        k.profile(); k.boot(); k.open(); k.host.flush(); k.host.awaitIdle()
        assertNotNull(deathAt("ALTER TABLE engine_snapshot RENAME TO engine_snapshot_x", "ALTER TABLE engine_snapshot_x RENAME TO engine_snapshot"), "스냅숏 읽기 실패는 프로세스를 끝낸다")
        k.boot(); assertNotNull(k.ses(), "다시 뜨면 정상")
    }

    @Test fun c291_initialStateReadFailureAtStart_dies() {
        k.profile()   // 스냅숏 없음: 초기 상태를 Room에서 만든다
        assertNotNull(deathAt("ALTER TABLE child_profile RENAME TO child_profile_x", "ALTER TABLE child_profile_x RENAME TO child_profile"), "초기 상태 읽기 실패는 프로세스를 끝낸다")
    }

    @Test fun c296_pendingReportsFailure_doesNotKillHost() {
        k.profile(); k.boot(); k.open(); k.host.flush(); k.host.awaitIdle()
        // 복원이 읽지 않는 다른 세션 행의 started_at을 BLOB으로 깨 pendingReports()만 변환 예외를 낸다
        dao.upsertSession(SessionEntity("bad", 1, 2, "t", null, null, 0, 0, false, "[]", null, null, true, null, 10))
        sqlExec("UPDATE session SET started_at = x'00' WHERE id = 'bad'")
        val died = catchingHostDeath { k.boot() }
        sqlExec("DELETE FROM session WHERE id = 'bad'")
        assertNull(died, "리포트 재개 조회 실패는 프로세스를 끝내지 않는다")
        assertNotNull(k.ses(), "복원은 끝났다")
    }

    // ---- 복원 ①~⑤ ----

    @Test fun r14r_restoreWhilePlaying_treatedAsPlaybackError() {
        k.profile(); k.boot(); k.open(); k.alarm()
        val pl = k.ses().play!!
        assertNotNull(pl.startedAt)
        k.ev(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 2)), k.now + 20_000)   // 20초에 바로 저장(마지막 스냅숏)
        k.running = null
        k.now += 20_000
        k.boot()   // 재생 중 프로세스가 죽었다. V10(C318): 멈춘 시각은 복원 시각(40초)이 아니라 마지막 스냅숏 시각(20초)
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
        assertTrue(PauseReason.MIC_ERROR in k.state().car.pauses)
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
        assertTrue(PauseReason.MIC_ERROR in k.state().car.pauses)
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
        k.store.saveSnapshot(s.copy(handoffs = listOf(HandoffLedger(s.session!!.id, ep.id, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, alert = false))))
        sql("UPDATE episode SET first_result = 'FAIL', handoff_cause = 'TWO_ATTEMPTS', handoff_kind = 'WAYPOINT_OFFER', ended_at = ${k.now} WHERE id = '${ep.id}'")
        k.running = null
        k.boot()   // 큐에 그 줄이 없다 → 버려짐
        assertNull(k.state().openLedger())
        val c = k.db.openHelper.readableDatabase.query("SELECT handoff_told_driver FROM episode WHERE id = '${ep.id}'")
        c.moveToFirst(); assertTrue(!c.isNull(0), "Room에 null이 남지 않는다"); assertEquals(0, c.getInt(0)); c.close()
        sql("UPDATE session SET ended_at = ${k.now} WHERE id = '${k.ses().id}'")
        val r = kr.aigyeot.app.store.buildReport(k.db.storeDao(), k.ses().id, k.now)!!
        assertEquals(false, r.timeline.single().stop!!.toldDriver)
    }

    /** C136: 에피소드 handoff가 TWO_ATTEMPTS/WAYPOINT_OFFER, S07이 큐에 묶인 스냅숏을 저장하고 죽은 상태를 만든다. */
    private fun c136Snapshot(speaking: String? = null, extra: List<Line> = emptyList(), mk: (Long) -> Line = { Line(Sid.S07, mapOf("restName" to "n 휴게소"), "n", queuedAt = it) }): Pair<String, Line> {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        val s = k.state(); val ep = s.session!!.episode!!
        val line = mk(k.now).let { if (it.sessionId == null) it.copy(sessionId = s.session!!.id, handoff = true) else it }   // 장부가 묶인 줄(표시). 다른 세션 줄은 묶이지 않는다
        k.store.saveSnapshot(s.copy(
            speech = s.speech.copy(speaking = speaking, queue = listOf(line) + extra),
            session = s.session!!.copy(
                episode = ep.copy(handoff = HandoffRecord(HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER))),
            handoffs = listOf(HandoffLedger(s.session!!.id, ep.id, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, alert = false))))
        sql("UPDATE episode SET handoff_cause = 'TWO_ATTEMPTS', handoff_kind = 'WAYPOINT_OFFER' WHERE id = '${ep.id}'")
        return ep.id to line
    }
    private fun toldDriverRow(id: String): Int? = k.db.openHelper.readableDatabase.query("SELECT handoff_told_driver FROM episode WHERE id = '$id'").use { it.moveToFirst(); if (it.isNull(0)) null else it.getInt(0) }
    private fun restNameRow(id: String): String? = k.db.openHelper.readableDatabase.query("SELECT handoff_rest_name FROM episode WHERE id = '$id'").use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }

    @Test fun c136_restoreRoomToldDriverWins_noReask() {
        k.phone("guardian", alerts = true)   // 알림을 켠 보호자: 대기 인계가 남아 있으면 복원 뒤 알림이 나간다
        val (id, _) = c136Snapshot()
        k.store.loadSnapshot()!!.let { s -> k.store.saveSnapshot(s.copy(handoffs = s.handoffs.map { it.copy(alert = true) })) }
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소' WHERE id = '$id'")   // 질문을 내며 저장했다
        k.running = null; k.exec.clear(); k.link.clear()
        k.boot()
        assertNull(k.state().openLedger()); assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 }, "묶인 줄을 지운다")
        assertEquals(1, toldDriverRow(id)); assertEquals("n 휴게소", restNameRow(id))   // 확정 값은 Room 한 곳(판단 코어에 사본 없음)
        assertTrue(k.exec.none { it is Ask }, "다시 묻지 않는다"); assertTrue(k.link.none { it is SendToPhone && it.type == "handoff_alert" }, "복원 직후 알림을 다시 보내지 않는다")
        // (b) 내비 응답 전 D로 경로가 비어도 false로 덮지 않는다
        k.ev(GearChanged(0, Gear.D), k.now + 1_000)
        assertTrue(k.link.none { it is SendToPhone && it.type == "handoff_alert" }, "그 뒤에도 알림이 나가지 않는다")
        assertEquals(1, toldDriverRow(id)); assertEquals("n 휴게소", restNameRow(id))
    }

    @Test fun c136_restoreToldTrue_longDriveLine_marksOffered() {
        val (id, _) = c136Snapshot(mk = { Line(Sid.S10, emptyMap(), null, longDrive = true, queuedAt = it) })
        sql("UPDATE episode SET handoff_told_driver = 1 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertTrue(k.ses().longDriveOffered, "말한 장시간 제안은 줄만 빼지 않고 longDriveOffered를 세운다(C84)")
        assertNotNull(k.ses().spokenAt[Sid.S10])
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S10 })
    }

    @Test fun c136_restoreToldFalse_dropsLine_forgetsOffer() {
        val (id, _) = c136Snapshot()   // 결정 ②: 큐에 든 줄은 금지 시각을 남기지 않는다
        sql("UPDATE episode SET handoff_told_driver = 0 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.ses().spokenAt[Sid.S07], "듣지 못한 제안은 금지를 남기지 않는다")
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
        assertNotNull(k.state().openLedger(), "Room 값이 null이면 지금처럼 대기")
    }

    @Test fun c136_restoreRoomEndedEpisode_toldDriverWins_closedRowKept() {
        val (id, _) = c136Snapshot()
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소', first_result = 'FAIL', ended_at = ${k.now} WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.state().openLedger()); assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 })
        assertEquals(1, toldDriverRow(id), "닫힌 행의 true를 false로 덮지 않는다")
    }

    @Test fun c136_restoreSpeakingFrontLine_handledOnceWithC123() {
        val other = Line(Sid.S08, emptyMap(), null, queuedAt = 1)
        val (id, _) = c136Snapshot(speaking = "u1", extra = listOf(other))
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소' WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.state().openLedger())
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 }, "말하던 줄은 한 번만 버린다")
        assertEquals(1, toldDriverRow(id))
    }

    @Test fun c151_roomFalseHeadLine_markDropped_nextLineKept() {
        val other = Line(Sid.S08, emptyMap(), null, queuedAt = 1)
        val (id, _) = c136Snapshot(speaking = "u1", extra = listOf(other))
        sql("UPDATE episode SET handoff_told_driver = 0 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.state().speech.speaking); assertNull(k.state().openLedger())
        assertEquals(listOf(Sid.S08), k.state().speech.queue.map { it.sid }, "맨 앞 줄만 빼고 다음 줄은 남긴다")
        assertNull(k.ses().spokenAt[Sid.S07], "듣지 못한 제안은 금지를 남기지 않는다")
        assertEquals(0, toldDriverRow(id), "Room의 false를 말한 것으로 덮지 않는다")
    }

    @Test fun c151_roomTrueHeadLine_markSpoken_nextLineKept() {
        val other = Line(Sid.S08, emptyMap(), null, queuedAt = 1)
        val (id, _) = c136Snapshot(speaking = "u1", extra = listOf(other), mk = { Line(Sid.S10, emptyMap(), null, longDrive = true, queuedAt = it) })
        sql("UPDATE episode SET handoff_told_driver = 1 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.state().speech.speaking)
        assertEquals(listOf(Sid.S08), k.state().speech.queue.map { it.sid }, "다음 줄을 버리지 않는다")
        assertTrue(k.ses().longDriveOffered, "Room 참이면 말한 것과 같은 상태 변화")
    }

    @Test fun c152_restoreRoomToldTrue_closedHandoffDropped_noDuplicateAlert() {
        // 세션이 닫힌 뒤 인계 발화가 성공해 Room에 true를 쓰고 스냅숏 전에 죽었다(스냅숏은 session == null, closedHandoff 있음)
        val (id, _) = c136Snapshot()
        val s = k.state(); val sid = s.session!!.id
        val closed = HandoffLedger(sid, id, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, utteranceId = "u1", restName = "n 휴게소")
        k.store.saveSnapshot(s.copy(session = null, handoffs = listOf(closed), speech = s.speech.copy(speaking = "u1", queue = listOf(Line(Sid.S07, emptyMap(), "n", queuedAt = k.now, sessionId = sid, handoff = true)))))
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소' WHERE id = '$id'")
        k.running = null; k.exec.clear(); k.link.clear()
        k.boot()
        assertTrue(k.state().handoffs.isEmpty(), "Room 값이 있으니 닫힌 묶음을 지운다")
        k.ev(Tick(0), k.now + 1_000)
        assertTrue(k.link.none { it is SendToPhone && it.type == "handoff_alert" }, "이름 없는 알림을 또 보내지 않는다")
        assertEquals(1, toldDriverRow(id), "true를 false로 덮지 않는다")
    }

    @Test fun c152_restoreClosedHandoff_reportBuiltAfterToldDriverFalseRecorded() {   // 흐름 시험: 복원의 flush가 told_driver=false를 적은 뒤에 리포트가 만들어진다
        k.phone()
        val (id, _) = c136Snapshot()
        val s = k.state(); val sid = s.session!!.id
        val closed = HandoffLedger(sid, id, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, utteranceId = "u1")
        k.store.saveSnapshot(s.copy(session = null, handoffs = listOf(closed), speech = s.speech.copy(speaking = "u1", queue = listOf(Line(Sid.S07, emptyMap(), "n", queuedAt = k.now, sessionId = sid, handoff = true)))))
        sql("UPDATE session SET ended_at = ${k.now}, report_pending = 1 WHERE id = '$sid'")   // Room 값(handoff_told_driver)은 없으니 flush가 정리한다
        k.running = null; k.exec.clear(); k.link.clear()
        k.boot()
        val body = k.db.openHelper.readableDatabase.query("SELECT body_json FROM outbox WHERE type = 'report_summary'").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(1, body.size, body.toString()); assertTrue("\"told_driver\":false" in body[0], "엔진 flush가 told_driver를 적은 뒤에 리포트를 만든다: " + body[0])
    }

    @Test fun c152_closedHandoffChange_savedImmediately() {
        k.profile(); k.boot(); k.open()
        val old = k.state()
        val closed = HandoffLedger("closed-session", null, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, utteranceId = "u1")
        val withClosed = old.copy(handoffs = listOf(closed))
        assertTrue(k.host.needsSnapshot(old, Step(withClosed, emptyList(), null)), "닫힌 묶음을 두는 Step")
        assertTrue(k.host.needsSnapshot(withClosed, Step(old, emptyList(), null)), "정리해 지우는 Step")
    }

    @Test fun c137_restoreRoomEndedSession_dropsPendingHandoffLine_keepsOthers() {
        val other = Line(Sid.S12, emptyMap(), null, queuedAt = 1)   // 세션과 무관한 문장
        val offer = Line(Sid.S08, emptyMap(), null, queuedAt = 1)   // C140: 일반 제안도 closeSession처럼 뺀다
        val (id, _) = c136Snapshot(extra = listOf(offer, other))
        sql("UPDATE session SET ended_at = ${k.now} WHERE id = '${k.ses().id}'")
        k.running = null; k.exec.clear(); k.link.clear()
        k.boot()
        assertNull(k.state().session)
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 }, "묶인 줄을 지운다")
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S08 }, "C140: 일반 제안 줄도 closeSession처럼 뺀다")
        assertTrue(k.state().speech.queue.any { it.sid == Sid.S12 }, "세션과 무관한 줄은 남는다")
        k.ev(GearChanged(0, Gear.P), k.now + 100)   // 닫힌 묶음은 첫 Step의 flush가 알림으로 낸다(C159)
        assertTrue(k.exec.none { it is SendToPhone } && k.link.none { it is SendToPhone && it.type == "handoff_alert" }, "알림을 내지 않는다(알림은 link로 나간다)")
    }

    @Test fun restoreRoomEndedSession_toIdle_movesPausesToOutPauses() {
        k.profile(); k.boot(); k.open()
        k.ev(InputLost(0, PauseReason.SILENCED))
        sql("UPDATE session SET ended_at = ${k.now} WHERE id = '${k.ses().id}'")
        k.running = null
        k.boot()
        assertNull(k.state().session)
        assertTrue(PauseReason.SILENCED in k.state().car.pauses)
        assertTrue(PauseReason.SILENCED in k.pending, "풀리지 않은 사유 전부를 마이크 서비스에 넘긴다(C82)")
    }

    @Test fun restoreRoomPlayedMsWins_capReached() {
        k.profile(); k.boot(); k.open()
        val id = k.ses().id
        dao.upsertPlay(PlayLogEntity("p1", id, null, "REQUEST", "LULLABY", null, T0, T0 + 1_200_000, 1_200_000, "DEADLINE"))
        k.running = null
        k.boot()
        assertEquals(1_200_000, k.ses().playedMs)
        assertTrue(k.ses().capReached(Params()))
    }

    @Test fun restoreClearsAskListenSpeech() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        k.store.saveSnapshot(s.copy(
            speech = s.speech.copy(speaking = "u1", qa = QaWindow("l1", QaStep.HEARING, k.now + 5_000, false)),
            session = s.session!!.let { it.copy(offers = it.offers.copy(pendingAsk = PendingAsk("a1", Sid.S08, "r1", null, false))) },
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
            session = s.session!!.let { it.copy(offers = it.offers.copy(spokenAt = mapOf(Sid.S07 to k.now))) },
        ))
        k.boot()
        assertTrue(k.state().speech.queue.isEmpty(), "말하던 줄은 다시 말하지 않는다(C123)")
        assertEquals(k.now, k.ses().spokenAt[Sid.S07], "10분 금지 시각은 남는다")
        // 말하던 줄이 없었으면(speaking == null) 큐는 그대로
        val s2 = k.state()
        k.store.saveSnapshot(s2.copy(speech = s2.speech.copy(speaking = null, queue = listOf(line))))
        k.boot()
        assertTrue(k.state().speech.queue.isNotEmpty() || k.exec.any { it is Ask }, "speaking이 없으면 줄을 버리지 않는다")
    }

    @Test fun r14x_c151_restoreDroppedLongDrive_reofferedAfterRoute() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val line = Line(Sid.S11, mapOf("restName" to "n 휴게소"), "n", longDrive = true)
        k.store.saveSnapshot(s.copy(
            speech = s.speech.copy(speaking = "u1", queue = listOf(line)),
            session = s.session!!.let { it.copy(offers = it.offers.copy(pendingAsk = PendingAsk("a1", Sid.S11, "n", null, true), driveFrom = k.now - 7_300_000)) },
        ))
        k.boot()
        assertEquals(false, k.ses().longDriveOffered, "C151: 버린 장시간 제안은 말하지 않은 것으로 친다")
        assertNull(k.ses().spokenAt[Sid.S11])
        assertTrue(k.exec.none { it is Listen }, "듣기는 열지 않는다")
        val before = k.exec.count { it is Ask && it.sid == Sid.S11 }
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        k.ev(Tick(0), k.now + Params().speechGapMs + 1_000)   // 복원이 말하던 줄을 버린 시각부터 말 사이 간격(S1)
        assertEquals(before + 1, k.exec.count { it is Ask && it.sid == Sid.S11 }, "새 경로로 휴식 안내를 한 번 다시 낸다")
    }

    private val rest10 = RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 10.0, true)), 150.0)

    /** C138: 복원 큐에 말하지 않은 장시간 제안이 있다. 상태는 큐에 S10(longDrive)과 호출자가 고른 줄을 둔 채 복원한다. */
    private fun restoreWithQueued(vararg lines: Line) {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        k.store.saveSnapshot(s.copy(speech = s.speech.copy(queue = lines.toList()), session = s.session!!.let { it.copy(offers = it.offers.copy(driveFrom = k.now - 7_300_000)) }, car = s.car.copy(gear = Gear.P)))
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

    @Test fun c138_restoreSpeakingLongDrive_c151NotMarkedOffered_andQueuedLongDriveDropped() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val speaking = Line(Sid.S10, emptyMap(), null, longDrive = true)
        val queued = Line(Sid.S11, mapOf("restName" to "n 휴게소"), "n", longDrive = true)
        k.store.saveSnapshot(s.copy(speech = s.speech.copy(speaking = "u1", queue = listOf(speaking, queued)), session = s.session!!.let { it.copy(offers = it.offers.copy(driveFrom = k.now - 7_300_000)) }))
        k.boot()
        assertEquals(false, k.ses().longDriveOffered, "C151: 말하던 줄은 말하지 않은 것으로 버린다(휴식 안내가 다시 난다)")
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
        val alert = HandoffLedger(old.session!!.id, null, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null)
        val withAlert = old.copy(handoffs = listOf(alert))
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
        assertTrue(PauseReason.MIC_ERROR in k.state().car.pauses)
        assertTrue(Notice.MIC_STOPPED !in k.notices, "C393: 복원 기어가 D(주행 중)면 띄우지 않는다")
        k.ev(GearChanged(0, Gear.P))
        assertTrue(Notice.MIC_STOPPED in k.notices, "다음 P에서 1i가 띄운다")
    }

    @Test fun restoreMicAlreadyRunning_c82_reconcilesBothWays() {
        k.profile(); k.boot(); k.open()
        k.ev(InputLost(0, PauseReason.SILENCED))
        k.running = setOf(PauseReason.DETECTOR_TIMEOUT)   // 서비스가 살아 있다(엔진이 다시 붙음)
        k.pending = emptySet()
        k.boot()
        assertEquals(listOf(setOf(PauseReason.SILENCED)), k.assumed, "엔진에만 있는 사유 → assumePaused")
        assertTrue(PauseReason.DETECTOR_TIMEOUT in k.state().car.pauses, "감지기에만 있는 사유 → InputLost")
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

    private fun setup(override: Set<String> = emptySet(), sibling: Boolean = false) = ScenarioSetup(
        "A", 7, Profile("2026-03", CarSeat.LEFT, null, sibling), listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)),
        emptyList(), null, 0, override,
    )

    // ---- C319 F-A V9: 장면 시작은 실제 상태가 말하는 중이 아니어도 수명 표 SCENE_START 열을 조건 없이 적용한다 ----
    @Test fun c319_v9_queuedS06NotSpeaking_cutAtSceneStart_notSaidAfterScene() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val s06 = Line(Sid.S06, mapOf("elapsed" to "1시간 10분"), null, queuedAt = k.now, sessionId = s.session!!.id)
        k.store.saveSnapshot(s.copy(speech = s.speech.copy(speaking = null, queue = listOf(s06), lastDoneAt = k.now)))   // S1 간격으로 기다린다
        k.boot()
        assertEquals(listOf(Sid.S06), k.state().speech.queue.map { it.sid }, "전제: 말하지 않는 S06이 큐에 있다")
        k.exec.clear()
        k.host.startScenario(setup()); k.host.awaitIdle()
        assertTrue(k.exec.any { it is CancelSpeech }, "말하지 않아도 실행기를 끊는다")
        assertTrue(k.store.loadSnapshot()!!.speech.queue.isEmpty(), "보관한 실제 상태(장면 직전 스냅숏)에 큐 줄이 없다")
        k.host.endScenario(); k.host.awaitIdle()
        k.ev(Tick(0), k.now + 10 * 60_000)   // 장면이 10분 돈 뒤
        assertTrue(k.exec.filterIsInstance<Speak>().none { it.sid == Sid.S06 }, "장면 전 값('1시간 10분')을 장면 뒤에 말하지 않는다")
    }

    @Test fun c319_v9_listeningForAnswer_cutAtSceneStart() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        k.store.saveSnapshot(s.copy(session = s.session!!.let { it.copy(offers = it.offers.copy(driveFrom = k.now - 7_300_000)) }))   // 2시간 넘게 달렸다(L1)
        k.boot()
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        k.ev(Tick(0), k.now + Params().speechGapMs + 1_000)
        val ask = k.exec.filterIsInstance<Ask>().single()
        k.ev(SpeechDone(0, ask.askId, true), k.now + 3_000)
        assertNotNull(k.ses().pendingAsk, "전제: 질문이 끝나 답을 듣는 중"); assertNull(k.state().speech.speaking)
        k.exec.clear()
        k.host.startScenario(setup()); k.host.awaitIdle()
        assertTrue(k.exec.any { it is CancelSpeech }, "실행기의 답 듣기를 끊는다(운전자의 '응'이 장면 상태로 가 버려지지 않게)")
        assertNull(k.store.loadSnapshot()!!.session!!.pendingAsk, "보관한 실제 상태에 답 기다림이 없다")
    }

    @Test fun c319_v9_realAutoPlay_stoppedAtSceneStart_suspendReason() {
        k.profile(); k.boot(); k.open(); k.alarm()
        val pl = k.ses().play!!
        k.exec.clear()
        k.host.startScenario(setup()); k.host.awaitIdle()
        assertTrue(StopPlayback(pl.id, false) in k.exec, "실제 재생을 장면 재생이 덮을 때까지 두지 않는다")
        val real = k.store.loadSnapshot()!!.session!!
        assertEquals(Phase.WAIT, real.phase); assertNull(real.play)
        val row = dao.plays(real.id).single { it.id == pl.id }
        assertEquals("SUSPEND", row.endReason)
        k.now += 20 * 60_000   // 장면이 20분 돈다
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(row.playedMs, dao.plays(real.id).single { it.id == pl.id }.playedMs, "장면 길이가 실제 재생 시간에 들지 않는다(V10 (가))")
        assertEquals(Phase.WAIT, k.ses().phase)
    }

    // ---- C319 F-A V11: 장면 끝은 장면 세션이 잠근 뒷창을 13a처럼 되돌린다 ----
    @Test fun c319_v11_sceneEnd_restoresSceneSessionWindowLock() {
        k.profile(); k.boot()
        k.host.startScenario(setup(sibling = true)); k.host.awaitIdle()
        k.open()   // 장면 세션(형제 있음 → 뒷창 잠금 3b)
        val lock = k.exec.filterIsInstance<SetWindowLock>().single()
        lock.values.keys.forEach { a -> k.ev(ControlResult(0, lock.cmdId, ControlKind.WINDOW_LOCK, a, true, 0.0, 1.0)) }   // 3e: 원래 열림
        k.exec.clear()
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(lock.values.keys.associateWith { false }, k.exec.filterIsInstance<SetWindowLock>().single().values, "장면 세션이 닫히기 전에 끝나도 뒷창을 원래 값으로 둔다")
    }

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
        linkCare(real, 1)
        val fake = real.copy(asOf = real.asOf - 60_000, todayFeedCount = 2)
        k.host.startScenario(setup(override = setOf("CareSummary")).copy(care = fake)); k.host.awaitIdle()
        assertEquals(fake, k.state().care, "장면 setup.care가 첫 Step 뒤 들어간다")
        val newer = real.copy(asOf = real.asOf + 1_000)
        linkCare(newer, 2)
        assertEquals(fake, k.state().care, "실제 link 요약은 막힌다")
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(newer, k.state().care, "끝나면 실제로 받은 가장 늦은 값")
    }

    /**
     * link 받기 흉내(L69·C144, 조립 줄): Room 행과 [careReplaces](LinkRoom care_summary와 같은 함수)로 견줘 이기면 행을 바꾸고 실제 사건을 낸다.
     * C322 V2부터 장면 끝 실제 요약은 이 행만 따른다(호스트가 따로 들지 않는다).
     */
    private fun linkCare(c: CareView, seq: Long, room: String? = null) {
        val row = k.db.linkDao().careApplied()
        val old = row?.let { kr.aigyeot.app.store.SnapshotJson.decodeFromString(CareView.serializer(), it.summaryJson) }
        if (row != null && !careReplaces(old, row.seq, c, seq, row.carId, room)) return
        careRow(c, seq, room); k.host.postReal(CareSummary(0, c, seq, room)); k.host.awaitIdle()
    }

    private fun careEndsAs(override: Set<String>, during: CareView?): CareView? {
        k.db.linkDao().clearCareApplied()   // 같은 kit으로 되풀이해 부른다. 앞 호출의 Room 행·장면 끝에 바로 저장한 요약(C126 ④)이 다음 boot로 이어지지 않게
        k.profile(); k.boot()
        k.store.saveSnapshot(k.state().copy(care = null, careSeq = null, careRoom = null)); k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        linkCare(c1, 1)
        k.host.startScenario(setup(override = override)); k.host.awaitIdle()
        during?.let { linkCare(it, 2) }
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
        k.ev(CareSummary(0, c1, seq = 5)); careRow(c1, 5, null)   // link가 받았다면 Room 행도 있다(L69·C158: 행 없이 seq 있는 요약은 지운 것으로 본다)
        k.host.startScenario(setup(override = setOf("CareSummary")).copy(care = c1.copy(asOf = k.now + 60_000, todayFeedCount = 9))); k.host.awaitIdle()
        val c2 = c1.copy(todayFeedCount = 2)                       // 장면 값보다 이르고 장면 전 실제 값과 같은 초지만 seq가 큰 실제 요약
        k.host.postReal(CareSummary(0, c2, seq = 6)); k.host.awaitIdle(); careRow(c2, 6, null)   // link가 받았다면 Room 행도 갱신된다(C169: 복원은 행을 정답으로 본다)
        assertEquals(9, k.state().care!!.todayFeedCount, "장면 중에는 장면 값")
        assertEquals(1, k.store.loadSnapshot()!!.care!!.todayFeedCount, "장면 중 실제 스냅숏은 장면 직전 값")
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(c2, k.state().care); assertEquals(6L, k.state().careSeq)
        assertEquals(c2, k.store.loadSnapshot()!!.care, "장면 끝에 실제 값으로 되돌린 상태는 바로 저장")
        assertEquals(6L, k.store.loadSnapshot()!!.careSeq)
    }

    // C283: 장면이 CareSummary를 막지 않아도 장면 중 실제 요약은 장면 상태에 넣지 않는다(실제 값만 바꾸고 장면 끝에 되돌린다)
    @Test fun c283_realSummaryDuringNonBlockingScene_sceneCareKept_restoredAtEnd() {
        k.profile(); k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c1, seq = 5)); careRow(c1, 5, null)
        k.host.startScenario(setup().copy(care = c1.copy(asOf = k.now - 60_000, todayFeedCount = 9))); k.host.awaitIdle()   // 장면 값이 더 이르다: 장면 상태로 들어가면 실제 요약이 이긴다
        val c2 = c1.copy(todayFeedCount = 2)
        k.host.postReal(CareSummary(0, c2, seq = 6)); k.host.awaitIdle(); careRow(c2, 6, null)
        assertEquals(9, k.state().care!!.todayFeedCount, "막지 않는 장면이어도 장면 중에는 장면 값")
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(c2, k.state().care); assertEquals(6L, k.state().careSeq)
    }

    @Test fun c283_careClearedDuringNonBlockingScene_noSummaryAtEnd() {
        k.profile(); k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c1, seq = 5)); careRow(c1, 5, null)
        k.host.startScenario(setup().copy(care = c1.copy(asOf = k.now - 60_000, todayFeedCount = 9))); k.host.awaitIdle()
        k.host.postReal(CareSummary(0, c1.copy(todayFeedCount = 2), seq = 6)); k.db.linkDao().clearCareApplied(); k.host.postReal(CareCleared(0)); k.host.awaitIdle()   // link는 지우기와 함께 Room 행도 지운다
        assertEquals(9, k.state().care!!.todayFeedCount, "장면 값은 그대로")
        k.host.endScenario(); k.host.awaitIdle()
        assertNull(k.state().care)
    }

    // C307: 장면 중 실제 수유 기록(child_profile 변경)이 methodsMaybeChanged를 불러도 장면 음원 목록은 setup 값 그대로
    @Test fun c307_realFeedingDuringScene_doesNotChangeSceneMethods() {
        k.profile(); k.boot()
        k.host.startScenario(setup()); k.host.awaitIdle()
        val scene = k.state().methodsNext
        sql("UPDATE child_profile SET methods = '[{\"kind\":\"WHITE_NOISE\"},{\"kind\":\"LULLABY\"},{\"kind\":\"SONG\"}]'")
        sql("INSERT INTO song (id, name, received_at, format, duration_s, lufs, normalized_path, status, reject_reason, added_seq, last_seq) VALUES ('s1', '자장자장', $T0, 'AAC', 60.0, -30.0, NULL, 'ACTIVE', NULL, 3, 3)")
        k.host.methodsMaybeChanged(); k.host.awaitIdle()
        assertEquals(scene, k.state().methodsNext, "장면 상태는 장면 setup 값")
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(Method(MethodKind.SONG, "s1"), k.state().methodsNext.last(), "실제 값은 장면 끝에 남는다")
    }

    // C307: 장면 중 실제 link·프로필 변경은 장면 상태를 바꾸지 않고 장면 끝에 실제 상태에 남는다
    @Test fun c307_realLinkAndProfileDuringScene_goToReal() {
        k.profile(); k.boot()
        k.host.startScenario(setup()); k.host.awaitIdle()
        val sceneProfile = k.state().profile
        k.phone("guardian", alerts = true); k.profile(interval = 120)
        k.host.postReal(LinkChanged(0, 1)); k.host.postReal(ProfileChanged(0, k.store.profile())); k.host.awaitIdle()
        assertEquals(0, k.state().otherGuardians); assertEquals(sceneProfile, k.state().profile)
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(1, k.state().otherGuardians); assertEquals(k.store.profile(), k.state().profile)
    }

    // C307: 장면 phone 단계 주입(post)은 장면 사건이라 장면 상태에 들어간다
    @Test fun c307_scenePhoneStepStillApplies() {
        k.profile(); k.boot()
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.ev(FeedingStarted(0, k.now, FeedingFrom.PHONE, "m1"))
        assertEquals(k.now, k.state().feedingStart)
    }

    // C294: 복원 대조의 link 읽기가 한 번 실패해도 빈 목록으로 넘어가지 않고 다시 읽는다
    @Test fun c294_reconcileRetriesLinkReadOnce_sceneEnd() {
        k.profile(); k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c1, seq = 5)); careRow(c1, 5, null)
        k.host.startScenario(setup().copy(care = c1.copy(asOf = k.now - 60_000, todayFeedCount = 9))); k.host.awaitIdle()
        k.db.linkDao().clearCareApplied()   // 장면 중 연결 해제로 행만 지워졌다(CareCleared 사건은 장면 끝 복원만 낸다)
        k.restoreFailures = 1
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(0, k.restoreFailures, "첫 읽기가 실제로 던졌다")
        assertNull(k.state().care, "다시 읽어 CareCleared가 반영된다")
    }

    @Test fun c294_reconcileRetriesLinkReadOnce_processStart() {
        k.profile(); k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c1, seq = 5)); careRow(c1, 5, null)
        k.host.flush(); k.host.awaitIdle()
        k.db.linkDao().clearCareApplied()   // 연결 해제로 행이 지워졌다
        k.restoreFailures = 1
        k.boot()
        assertEquals(0, k.restoreFailures)
        assertNull(k.state().care, "다시 읽어 CareCleared가 반영된다")
    }

    // ---- C130
    @Test fun c130_realSummary_afterQueuedStartScenario_isKeptAndRestoredAtEnd() {
        for (override in listOf(emptySet(), setOf("CareSummary"))) {
            k.db.linkDao().clearCareApplied()
            k.profile(); k.boot(); k.store.saveSnapshot(k.state().copy(care = null, careSeq = null, careRoom = null)); k.boot()   // 앞 회차 요약을 비운다
            val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
            linkCare(c1, 1)
            val latch = java.util.concurrent.CountDownLatch(1)
            val entered = java.util.concurrent.CountDownLatch(1)
            val saved = k.host.targets
            k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = { entered.countDown(); latch.await() })
            k.host.resume()                                                  // 호스트 스레드를 붙잡는다(큐가 대기하는 동안)
            entered.await()
            k.host.startScenario(setup(override = override))                 // 큐에 장면 시작
            val newer = c1.copy(asOf = c1.asOf + 1_000, todayFeedCount = 7)
            careRow(newer, 2, null); k.host.postReal(CareSummary(0, newer, 2))   // 실행 전에 새 실제 요약이 온다(link가 Room 행을 먼저 쓴다)
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
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = saved.restore, reemitAll = { k.host.postReal(route) })   // exec가 최신 RouteState를 다시 낸다(V12 reemitAll)
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(true, k.state().car.route != null, "복원 ③이 비운 경로가 돌아온다")
    }

    @Test fun c133_sceneResume_doesNotRefillRealCare_bootAndSceneEndDo() {   // C322 V2: 장면 중 Resume·시계 점프는 대조하지 않는다. 장면 끝 복원이 한 번 맞춘다
        k.profile(); k.boot()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val saved = k.host.targets
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = { st, at -> calls.incrementAndGet(); saved.restore(st, at) })
        k.host.suspend(); k.host.resume(); k.host.awaitIdle()
        assertEquals(1, calls.get(), "장면 없는 Resume")
        k.host.startScenario(setup()); k.host.awaitIdle()
        calls.set(0)
        k.host.suspend(); k.host.resume(); k.host.awaitIdle()
        k.slept += 600_000; k.ev(Tick(0), k.now + 600_000)   // 신호 없는 시계 점프도(C236 대조 자리)
        assertEquals(0, calls.get(), "장면 중 Resume·시계 점프는 대조하지 않는다(바뀐 기대값: 옛 C175·C133은 실제 상태에 접으려고 refill=false로 불렀다)")
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(1, calls.get(), "장면 끝 복원은 대조한다")
    }

    private fun careRow(c: CareView, seq: Long, room: String?) =
        k.db.linkDao().putCareApplied(kr.aigyeot.app.link.db.CareAppliedEntity(1, room, seq, kr.aigyeot.app.store.SnapshotJson.encodeToString(CareView.serializer(), c), "d_PHONE"))

    @Test fun l77_sceneHeldSummary_seqDecides() {   // 장면 중 이른 as_of·큰 seq 요약이 장면 끝에 적용된다(C322 V2: 판정은 link의 Room 행 하나, 호스트는 들지 않는다)
        k.profile(); k.boot()
        fun c(at: Long, feeds: Int) = CareView(at, true, null, null, null, null, null, null, null, feeds)
        linkCare(c(k.now + 240_000, 1), 5, "c_A")
        k.host.startScenario(setup()); k.host.awaitIdle()
        linkCare(c(k.now + 60_000, 2), 6, "c_A")    // 장면 중 실제로 받은 요약(Room 행)
        linkCare(c(k.now + 300_000, 3), 4, "c_A")   // 작은 seq는 늦은 as_of도 버린다
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(2, k.state().care?.todayFeedCount); assertEquals(6L, k.state().careSeq)
    }

    // ---- C322 V2: 장면 중 setup 몫 실제 사건은 장면 상태에도, 호스트가 따로 든 실제 값에도 들지 않는다. 장면 끝 실제 상태는 Room(복원 ⑤)만 맞춘다 ----
    @Test fun c322_v2_setupOwnedDuringScene_notHeld_onlyRoomAtEnd() {
        k.profile(); k.boot()
        val before = k.state()
        val saved = k.host.targets
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = { _, _ -> emptyList() })   // link 몫 Room 값(수유·보호자·요약)이 그대로라고 답한다
        k.host.startScenario(setup()); k.host.awaitIdle()
        val scene = k.state()
        k.host.postReal(FeedingStarted(0, k.now - 1_000, FeedingFrom.PHONE, "m1"))
        k.host.postReal(LinkChanged(0, 2))
        k.host.postReal(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 4), 9, "c_A"))
        k.host.postReal(MethodsChanged(0, listOf(Method(MethodKind.WOMB))))            // store Room 값(후보 ③)과 다르다
        k.host.postReal(ProfileChanged(0, Profile("2025-01", CarSeat.RIGHT, 90, true)))   // store Room 값(프로필)과 다르다
        k.host.awaitIdle()
        val mid = k.state()
        assertEquals(listOf(scene.feedingStart, scene.otherGuardians, scene.care, scene.methodsNext, scene.profile),
            listOf(mid.feedingStart, mid.otherGuardians, mid.care, mid.methodsNext, mid.profile), "장면 상태는 setup 값 그대로")
        k.host.endScenario(); k.host.awaitIdle()
        val s = k.state()
        assertEquals(listOf(before.feedingStart, before.otherGuardians, before.care, before.methodsNext, before.profile),
            listOf(s.feedingStart, s.otherGuardians, s.care, s.methodsNext, s.profile), "Room이 바뀌지 않았으면 실제 상태도 그대로(장면 중 사건을 접어 두지 않는다)")
    }

    // ---- C322 V3: 화면·알림 입력은 실제 입구(Host.uiSink = postReal) 하나로 들어간다 ----
    @Test fun c322_v3_uiSink_isRealEntrance_overrideAndSetupOwnedDuringScene() {
        k.profile(); k.boot()
        val ui = k.host.uiSink()
        val sceneSetup = setup(override = setOf("BabyAboard", "AskButton", "FeedingConfirmed"))
        k.host.startScenario(sceneSetup); k.host.awaitIdle()
        ui.post(BabyAboard(0, BabyAboardFrom.HOME)); k.host.awaitIdle()
        assertEquals(false, k.state().ign.reserved, "장면이 막은 화면 입력은 장면 상태에 들지 않는다")
        ui.post(ProfileChanged(0, Profile("2025-01", CarSeat.RIGHT, 90, true))); k.host.awaitIdle()
        assertEquals(sceneSetup.profile, k.state().profile, "setup 몫 화면 입력(프로필 저장)은 장면 상태에 들지 않는다")
        k.ev(BabyAboard(0, BabyAboardFrom.HOME))
        assertEquals(true, k.state().ign.reserved, "장면 주입(post)은 받는다")
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(false, k.state().ign.reserved, "장면 주입은 실제 상태로 새지 않는다")
        ui.post(BabyAboard(0, BabyAboardFrom.HOME)); k.host.awaitIdle()
        assertEquals(true, k.state().ign.reserved, "장면이 끝나면 실제 상태가 받는다")
    }

    // C322 V3: 차 화면 수유는 Room에 먼저 쓰고 feeding_state를 넣은 뒤 실제 입구로 사건을 낸다. 장면 중이면 장면 상태에 들지 않고 장면 끝 복원이 맞춘다
    private fun carFeedingDuringScene(): Long {
        k.profile(); k.phone(); k.boot()
        val (real, injected) = k.host.linkSinks()
        val link = Link(androidx.test.core.app.ApplicationProvider.getApplicationContext(), k.db, k.clock, real, { null }, kr.aigyeot.app.link.FakeRelay(),
            kr.aigyeot.app.link.FakeCipher, { null }, { _, _ -> kr.aigyeot.app.exec.LoudnessResult(true) }, k.linkScope, postInjected = injected)
        k.host.startScenario(setup()); k.host.awaitIdle()
        val at = k.now - 1_000
        kotlinx.coroutines.runBlocking { link.feedingFromCar(at).join() }; k.host.awaitIdle()
        assertEquals(at, dao.profile()!!.lastFeedingStart, "Room에 먼저 쓴다")
        assertEquals(1, k.db.linkDao().outbox().count { it.type == "feeding_state" }, "feeding_state를 보낼 함에 넣는다")
        assertNull(k.state().feedingStart, "장면 상태에는 들지 않는다")
        return at
    }

    @Test fun c322_v3_carFeedingDuringScene_roomFirst_appliedAtSceneEnd() {
        val at = carFeedingDuringScene()
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(at, k.state().feedingStart, "장면 끝 복원 ⑤가 Room 값으로 맞춘다")
    }

    @Test fun c322_v3_carFeedingDuringScene_survivesDeathMidScene() {
        val at = carFeedingDuringScene()
        k.running = null
        k.boot()   // 장면 중 프로세스가 죽었다
        assertEquals(at, k.state().feedingStart, "다시 켜면 실제 스냅숏 + Room으로 수유 시각이 남는다")
    }

    // ---- C322 V12: 장면 끝에 실행기가 지금 차 값을 다시 내고, 판단 코어는 장면 차 값을 칸별로 되돌리지 않는다 ----
    @Test fun c322_v12_sceneEnd_reemitAll_realCarValuesReplaceStale() {
        k.profile(); k.boot(); k.open()
        k.ev(CabinTemp(0, 20.0)).ev(SeatHeat(0, Area.ROW_2_LEFT, 0))
        val saved = k.host.targets
        val now = mutableListOf<Event>(CabinTemp(0, 27.0), SeatHeat(0, Area.ROW_2_LEFT, 2), GearChanged(0, Gear.D))   // 실제 차의 지금 값(장면 동안 바뀜)
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = saved.restore, reemitAll = { now.forEach(k.host::postReal) })
        k.host.startScenario(setup(override = setOf("CabinTemp"))); k.host.awaitIdle()
        k.host.postReal(CabinTemp(0, 27.0)); k.host.awaitIdle()   // override라 버려진 실제 온도
        k.ev(CabinTemp(0, 35.0))                                   // 장면 주입
        k.host.postReal(SeatHeat(0, Area.ROW_2_LEFT, 2)); k.host.awaitIdle()   // override가 아니라 장면 상태로 간 실제 열선
        assertEquals(35.0, k.state().car.cabinC)
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(27.0, k.state().car.cabinC, "장면 주입 값이 아니라 실제 차의 지금 온도(옛 칸별 되돌리기는 장면 전 20.0)")
        assertEquals(2, k.state().car.seatHeat[Area.ROW_2_LEFT], "장면 동안 바뀐 실제 열선도 다시 낸 값으로")
        assertEquals(Gear.D, k.state().car.gear)
    }

    // ---- C323: 장면 끝 reemitAll은 바뀐 값만 사건으로 만든다(닫힘 값만 다시 와도 S16·알림 없음), 순서는 기어 먼저 ----
    @Test fun c323_sceneEnd_reemitAll_closedDoorOnly_noS16NoNotice() {
        k.profile(); k.boot()
        k.ev(GearChanged(0, Gear.P)).ev(RearDoor(0, Side.LEFT, false))   // 실제: P·IDLE·문 닫힘·마이크 꺼짐
        val saved = k.host.targets
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = saved.restore,
            reemitAll = { k.host.postReal(GearChanged(0, Gear.P)); k.host.postReal(RearDoor(0, Side.LEFT, false)) })
        k.notices.clear()
        repeat(2) {
            k.host.startScenario(setup()); k.host.awaitIdle()
            k.host.endScenario(); k.host.awaitIdle()
        }
        assertEquals(false, k.state().ign.s16Said)
        assertTrue(k.notices.none { it == Notice.BABY_ABOARD })
        assertTrue(k.state().speech.queue.isEmpty() && k.state().speech.speaking == null)
    }

    @Test fun c323_sceneEnd_reemitAll_gearBeforeDoor_closesRealSession13a() {
        k.profile(); k.boot(); k.open()   // 실제 세션 D 열림
        val changed = kr.aigyeot.app.exec.Changed()   // 실제 연결이 받은 값: 뒷문을 먼저 받은 것처럼 넣어도 다시 낼 때는 기어가 먼저
        changed.emit("doorLEFT", true, { RearDoor(0, Side.LEFT, true) }) {}
        changed.emit("gear", Gear.P, { GearChanged(0, Gear.P) }) {}
        val saved = k.host.targets
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = saved.restore, reemitAll = { changed.reemit(k.host::postReal) })
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.host.endScenario(); k.host.awaitIdle()
        assertNull(k.state().session, "장면 중 실제 차가 P가 되고 문이 열림: 장면 끝에 세션이 닫힌다")
    }

    @Test fun c130_snapshot_keepsCareRoom() {
        k.profile(); k.boot()
        val c = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c, seq = 5, room = "c_A")); careRow(c, 5, "c_A")
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
        k.host.startScenario(setup()); k.host.awaitIdle()   // C187: 실제 상태가 말하는 중에는 장면이 시작하지 않으므로 장면 안에서 말하게 한다
        k.ev(GearChanged(0, Gear.P)).ev(RearDoor(0, Side.LEFT, true)).ev(RearDoor(0, Side.LEFT, false))   // 마이크 꺼짐 → S16
        assertNotNull(k.state().speech.speaking, "장면 상태가 말하는 중")
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


    @Test fun clearRecords_recordsDroppedOutboxIds_c40_storeOneTransaction_keepsProfileLinkSongs() {
        k.profile(); k.phone(); k.boot(); k.open(); k.alarm()
        sql("INSERT INTO outbox (id, type, to_device, body_json, created_at, attempts, next_try_at) VALUES ('o1', 'handoff_alert', NULL, '{}', 0, 1, 0), ('o2', 'feeding_state', NULL, '{}', 0, 0, 0), ('o3', 'handoff_alert', NULL, '{}', 0, 0, 0)")
        k.ev(ClearRecords(0))
        val q = { t: String -> k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $t").use { it.moveToFirst(); it.getInt(0) } }
        listOf("session", "episode", "play_log", "alarm_log", "context_sample", "control_log", "steer_window").forEach { assertEquals(0, q(it), it) }
        val types = k.db.openHelper.readableDatabase.query("SELECT type FROM outbox ORDER BY type").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf("feeding_state", "records_clear"), types)
        // 지우기 때 뺀 report_summary·handoff_alert id가 records_clear 본문에 담긴다(L79)
        val body = k.db.openHelper.readableDatabase.query("SELECT body_json FROM outbox WHERE type = 'records_clear'").use { it.moveToFirst(); it.getString(0) }
        assertEquals("{\"drop_ids\":[\"o1\"]}", body)   // 보낸 적 없는 o3은 담지 않는다(L82)
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
        k.state().speech.speaking?.let { k.host.postReal(SpeechDone(0, it, true)); k.host.awaitIdle() }   // C187: 말하는 중에는 장면이 시작하지 않는다
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.now += 5_000
        k.host.endScenario(); k.host.awaitIdle()
        // C319 V9(바뀐 기대값, 옛: GAP 유지 + 판정 창을 장면 끝부터 다시 잼): 장면 시작이 실제 판정을 Suspend처럼 멈춘다(WAIT, 1차 미정 SUSPEND).
        // 장면 동안 실제 울음을 받지 않았으니 장면 뒤 울음으로 그 1차를 판정하지 않는다. 마이크가 산 채 돌아오는 것은 그대로다
        assertEquals(Phase.WAIT, k.ses().phase)
        assertEquals(Unjudged.SUSPEND, k.ses().episode!!.first!!.unjudged)
        assertTrue(k.state().car.micRunning)
        assertTrue(Notice.MIC_STOPPED !in k.notices)
        assertTrue(k.state().car.pauses.isEmpty())
    }

    // C92 판정 창 다시 재기는 마이크가 살아 있는 채 복원하는 길(프로세스 시작)에 남는다(위 시험이 장면 끝에서 보던 몫)
    @Test fun c92_restoreWithLiveMic_gapWindowRestarts() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        assertEquals(emptySet(), k.running, "마이크 서비스가 살아 있다(전제)")
        k.now += 5_000
        k.boot()
        assertEquals(Phase.GAP, k.ses().phase)
        assertTrue(Notice.MIC_STOPPED !in k.notices)
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

    // ---- C159: 닫힌 세션 복원이 말하던 인계 묶음을 잃지 않는다 ----
    private fun c159Closed(alert: Boolean = true, toldInRoom: Int? = null): Pair<String, String> {
        k.phone("guardian", alerts = true)
        val (id, _) = c136Snapshot()
        val s = k.store.loadSnapshot()!!; val sid = s.session!!.id
        k.store.saveSnapshot(s.copy(handoffs = s.handoffs.map { it.copy(alert = alert, utteranceId = "u1") }, speech = s.speech.copy(speaking = "u1")))
        sql("UPDATE session SET ended_at = ${k.now}, report_pending = 1 WHERE id = '$sid'")
        toldInRoom?.let { sql("UPDATE episode SET handoff_told_driver = $it WHERE id = '$id'") }
        k.running = null; k.exec.clear(); k.link.clear()
        return id to sid
    }
    private fun reportBodies() = k.db.openHelper.readableDatabase.query("SELECT body_json FROM outbox WHERE type = 'report_summary'").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test fun c159_closedSessionRestore_pendingHandoffFlushedPlain_reportAfter() {
        val (id, _) = c159Closed()
        k.boot()
        assertNull(k.state().session); assertTrue(k.state().handoffs.isEmpty(), "첫 Step의 flush가 정리했다")
        val a = k.link.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
        assertEquals(1, a.size, "알림이 유실되지 않는다"); assertEquals("아기가 계속 울어요.", a.single().body["text"]!!.toString().trim('"'))
        assertEquals(0, toldDriverRow(id))
        val r = reportBodies(); assertEquals(1, r.size, "리포트는 한 번만: $r"); assertTrue("\"told_driver\":false" in r[0], r[0])
    }

    @Test fun c159_closedSessionRestore_roomToldWins() {
        val (id, _) = c159Closed(toldInRoom = 1)
        k.boot()
        assertTrue(k.state().handoffs.isEmpty())
        assertTrue(k.link.none { it is SendToPhone && it.type == "handoff_alert" }, "Room 값이 있으면 알림을 또 보내지 않는다")
        assertEquals(1, toldDriverRow(id), "true를 false로 덮지 않는다")
        assertEquals(1, reportBodies().size)
    }

    // ---- C161: Room 값으로 말하던 맨 앞 줄을 뺄 때도 말하기 간격을 이어 간다 ----
    @Test fun c161_roomRemovesSpeakingHead_keepsSpeechGap() {
        val (id, _) = c136Snapshot(speaking = "u1", extra = listOf(Line(Sid.S12, emptyMap(), null, queuedAt = 1)))
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소' WHERE id = '$id'")
        k.running = null; k.exec.clear()
        k.boot()
        assertEquals(k.now, k.state().speech.lastDoneAt)
        k.ev(Tick(0), k.now + 3_000)
        assertTrue(k.exec.none { it is Speak }, "8초 안에 다음 안내가 나가지 않는다")
        k.ev(Tick(0), k.now + 6_000)
        assertTrue(k.exec.any { it is Speak && it.sid == Sid.S12 }, "간격 뒤에는 나간다")
    }

    // ---- C160: 인계 알림 넣기와 told 기록을 한 트랜잭션으로 ----
    /** 보호자(알림 켬)가 있고 S08(NO_REST) 인계 줄을 말하는 중(u)까지 간 상태. 다음 SpeechDone(true)이 알림과 SettleHandoff를 한 Step에 낸다. */
    private fun c160Speaking(handoffPhase: Boolean = false, extra: List<Line> = emptyList()): Pair<String, String> {
        k.phone("guardian", alerts = true)
        val (id, _) = c136Snapshot(extra = extra, mk = { Line(Sid.S08, emptyMap(), null, queuedAt = it) })
        val s = k.store.loadSnapshot()!!
        val x = s.session!!
        k.store.saveSnapshot(s.copy(handoffs = s.handoffs.map { it.copy(kind = HandoffKind.NO_REST, alert = true) },
            car = if (handoffPhase) s.car.copy(pauses = emptySet()) else s.car,
            session = if (handoffPhase) x.copy(stage = Stage.Handoff(x.attemptNo)) else x))
        k.running = if (handoffPhase) emptySet() else null; k.exec.clear(); k.link.clear()   // handoffPhase: 마이크가 살아 있어 quietMs가 센다
        k.boot()
        k.ev(Tick(0), k.now + 10_000)
        val u = k.exec.filterIsInstance<Speak>().single { it.sid == Sid.S08 }.utteranceId
        return id to u
    }
    private fun outboxTypes() = k.db.openHelper.readableDatabase.query("SELECT type FROM outbox WHERE type = 'handoff_alert'").use { it.count }

    @Test fun c160_handoffAlertAndToldSavedInOneTransaction() {
        val (id, u) = c160Speaking()
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.ev(SpeechDone(0, u, true))
        assertEquals(1, outboxTypes(), "알림이 보낼 함에"); assertEquals(1, toldDriverRow(id), "told가 Room에")
    }

    @Test fun c160_failureInsideTransaction_neitherSaved() {
        val (id, u) = c160Speaking()
        failTied()
        k.ev(SpeechDone(0, u, true))
        assertEquals(0, outboxTypes(), "둘 다 되돌려진다"); assertNull(toldDriverRow(id))
    }

    // ---- C173: 묶음 트랜잭션이 실패하면 예외를 다시 던져 프로세스를 끝낸다(나머지 할 일·스냅숏 없음) ----
    private fun pendingAlertInSnapshot() = k.store.loadSnapshot()!!.openLedger()
    /** 결정 ③ 순서(store 쓰기 → 보낼 함 넣기)가 모두 끝난 뒤 커밋 전에 실패한다: 바깥 트랜잭션이 둘 다 되돌린다. */
    private fun failTied() { k.linkNowHook = { c, also -> k.db.runInTransaction(Runnable { k.realLink.enqueueNow(c, also); error("커밋 전 실패") }) } }
    /** 호스트 스레드가 끝나며 던지는 것(운영에서는 기본 핸들러가 프로세스를 끝낸다). */
    private fun <T> catchingHostDeath(block: () -> T): Throwable? {
        val old = Thread.getDefaultUncaughtExceptionHandler(); var died: Throwable? = null; val gate = java.util.concurrent.CountDownLatch(1)
        Thread.setDefaultUncaughtExceptionHandler { _, x -> died = x; gate.countDown() }   // 운영에서는 이 핸들러가 프로세스를 끝낸다
        try { block(); gate.await(3, java.util.concurrent.TimeUnit.SECONDS); k.host.awaitIdle() } finally { Thread.setDefaultUncaughtExceptionHandler(old) }
        return died
    }

    @Test fun c173_tiedTxFailure_throws_noFurtherCommandsOrSnapshot() {
        val (id, u) = c160Speaking()
        assertNotNull(pendingAlertInSnapshot(), "전제: 스냅숏에 대기 인계가 있다")
        failTied(); k.exec.clear()
        val died = catchingHostDeath { k.host.post(SpeechDone(0, u, true)) }
        assertNotNull(died, "예외가 프로세스 밖으로 나간다(호스트 스레드는 삼킨다)"); assertEquals("커밋 전 실패", generateSequence(died) { it.cause }.last().message)
        assertNotNull(pendingAlertInSnapshot(), "스냅숏은 옛 것 그대로")
        assertEquals(0, outboxTypes()); assertNull(toldDriverRow(id))
        assertTrue(k.exec.none { it is Listen }, "그 Step의 나머지 할 일은 나가지 않는다")
    }

    @Test fun c173_restartAfterFailure_flushesPlain() {
        val (id, u) = c160Speaking()
        failTied()
        catchingHostDeath { k.host.post(SpeechDone(0, u, true)) }
        k.exec.clear(); k.link.clear(); k.running = null; k.linkNowHook = null
        k.boot()   // 죽었다 다시 뜸: 옛 스냅숏으로 복원
        val a = k.link.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
        assertEquals(1, a.size, "알림을 잃지 않는다"); assertEquals("아기가 계속 울어요.", a.single().body["text"]!!.toString().trim('"'))
        assertEquals(0, toldDriverRow(id))
    }

    // ---- C183: dead 뒤에는 큐에 있던 flush·snapshot·장면 시작이 상태를 저장하거나 바꾸지 않는다 ----
    @Test fun c183_afterDie_flushAndSnapshotBlocked() {
        val (id, u) = c160Speaking()
        failTied()
        catchingHostDeath { k.host.post(SpeechDone(0, u, true)); k.host.flush(); k.host.startScenario(setup()) }
        assertNotNull(pendingAlertInSnapshot(), "실패 뒤 flush가 대기 인계를 지운 상태를 저장하지 않는다(스냅숏은 실패 전 그대로)")
        assertNull(k.host.scenarioId, "장면 시작도 막힌다")
        k.exec.clear(); k.link.clear(); k.running = null; k.linkNowHook = null
        k.boot()
        val a = k.link.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
        assertEquals(1, a.size, "알림을 잃지 않는다"); assertEquals("아기가 계속 울어요.", a.single().body["text"]!!.toString().trim('"'))
        assertEquals(0, toldDriverRow(id))
    }

    // ---- C167: 실제 사건의 override 판정은 큐에서 꺼낼 때 ----
    @Test fun c167_realEventQueuedAfterSceneStart_judgedAtQueue() {
        k.profile(); k.boot()
        val gate = java.util.concurrent.CountDownLatch(1); val inside = java.util.concurrent.CountDownLatch(1)
        k.onSlept = { k.onSlept = null; inside.countDown(); gate.await() }   // 호스트를 붙잡고 큐를 쌓는다
        k.host.post(Tick(0)); inside.await()
        k.host.startScenario(setup(override = setOf("FeedingStarted")))
        k.host.postReal(FeedingStarted(0, k.now - 1_000, FeedingFrom.PHONE, "m1"))   // 장면 시작이 큐에 먼저 든 뒤
        gate.countDown(); k.host.awaitIdle()
        assertNull(k.state().feedingStart, "장면이 막은 실제 수유 사건이 장면 값을 덮지 않는다")
    }

    // ---- C191: 장면 시작이 실제 발화를 먼저 끊고(통화와 같은 cancelSpeech) 바로 시작한다(C187의 미룸은 걷어냈다) ----
    @Test fun c191_sceneStartCancelsRealSpeech_thenStartsNow() {
        val (id, u) = c160Speaking(handoffPhase = true)
        assertNotNull(k.state().session, "전제: 실제 세션"); assertEquals(u, k.state().speech.speaking)
        k.exec.clear(); k.link.clear()
        k.host.startScenario(setup()); k.host.awaitIdle()
        assertTrue(k.exec.any { it is CancelSpeech }, "실제 발화를 끊는다")
        assertNull(k.state().session, "미루지 않고 바로 시작했다"); assertNull(k.state().speech.speaking)
        val a = k.link.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
        assertEquals(1, a.size, "묶인 인계는 이름 없는 알림"); assertEquals("아기가 계속 울어요.", a.single().body["text"]!!.toString().trim('"'))
        assertEquals(0, toldDriverRow(id), "끊긴 안내는 false")
        // 장면 입력(뒷문 열림)이 실제 상태에 들어가지 않는다: 장면이 끝나면 실제 세션은 그대로다
        k.host.postReal(SpeechDone(0, u, true)); k.host.endScenario(); k.host.awaitIdle()
        assertNotNull(k.state().session, "실제 세션이 장면 입력으로 닫히지 않았다"); assertNull(k.state().speech.speaking)
        assertEquals(0, toldDriverRow(id)); assertEquals(1, k.link.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" }, "알림이 늘지 않는다")
    }

    // ---- C208: 발화를 끊는 사건(장면 시작·CallMode(true)·Suspend)은 실행기에 원자적으로 묻는다. 이미 끝났으면 그 SpeechDone을 먼저 처리한다(C202의 큐 다시 넣기는 걷어냈다) ----
    @Test fun c208_sceneStart_speechAlreadyDone_toldTrue_sceneInputsStayInScene() {
        val (id, u) = c160Speaking(handoffPhase = true)
        val feedBefore = k.db.storeDao().profile()?.lastFeedingStart
        k.exec.clear(); k.link.clear()
        k.cancelIfSpeakingHook = { true to k.now }   // 실행기에서는 이미 끝났다(spoken=true). SpeechDone은 큐에 이미 들어 있다
        val gate = java.util.concurrent.CountDownLatch(1); val inside = java.util.concurrent.CountDownLatch(1)
        k.onSlept = { k.onSlept = null; inside.countDown(); gate.await() }   // 호스트를 붙잡고 큐를 쌓는다: 장면 시작 → 장면 입력 → SpeechDone
        k.host.post(Tick(0)); inside.await()
        k.host.startScenario(setup())
        k.host.post(FeedingStarted(0, k.now - 1_000, FeedingFrom.PHONE, "m-scene"))
        k.host.postReal(SpeechDone(0, u, true))
        gate.countDown(); k.host.awaitIdle()
        assertEquals(1, toldDriverRow(id), "끝난 발화는 말했다(true)로 확정된다")
        assertEquals("A", k.host.scenarioId, "장면은 미루지 않고 시작했다")
        assertEquals(feedBefore, k.db.storeDao().profile()?.lastFeedingStart, "장면 입력이 실제 Room 수유 시각을 바꾸지 않는다")
        k.host.endScenario(); k.host.awaitIdle()
        assertNotEquals(k.now - 1_000, k.state().feedingStart, "장면 입력이 실제 상태로 새지 않는다")
        assertEquals(1, k.link.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" }, "알림은 한 번")
    }

    @Test fun c208_callMode_speechAlreadyDone_toldTrue() {
        val (id, u) = c160Speaking(handoffPhase = true)
        k.cancelIfSpeakingHook = { true to k.now }
        k.ev(CallMode(0, true)); k.host.postReal(SpeechDone(0, u, true)); k.host.awaitIdle()   // 뒤의 같은 SpeechDone은 엔진이 무시한다
        assertEquals(1, toldDriverRow(id), "끝난 발화는 true로 확정"); assertNull(k.state().speech.speaking)
        assertEquals(1, k.link.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" })
    }

    @Test fun c208_suspend_speechAlreadyDone_toldTrue() {
        val (id, u) = c160Speaking(handoffPhase = true)
        k.cancelIfSpeakingHook = { true to k.now }
        k.host.suspend(); k.host.postReal(SpeechDone(0, u, true)); k.host.awaitIdle()
        assertNotNull(k.state().ign.suspendedAt, "전제: Suspend가 처리됐다")
        assertEquals(1, toldDriverRow(id), "끝난 발화는 true로 확정"); assertNull(k.state().speech.speaking)
        assertEquals(1, k.link.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" })
    }

    @Test fun c208_stillSpeaking_cancelledAsBefore() {
        val (id, u) = c160Speaking(handoffPhase = true)
        k.exec.clear(); k.link.clear()
        k.cancelIfSpeakingHook = { x -> assertEquals(u, x); k.exec += CancelSpeech; null }
        k.host.startScenario(setup()); k.host.awaitIdle()
        assertTrue(k.exec.any { it is CancelSpeech }); assertNull(k.state().session)
        assertEquals(0, toldDriverRow(id), "끊긴 안내는 false(C191)")
        val a = k.link.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
        assertEquals(1, a.size)
    }

    // ---- C207: 한 Step의 store 쓰기와 그 Step의 스냅숏을 한 트랜잭션으로 ----
    private fun failSnapshotWrite() = sql("CREATE TRIGGER fail_snap BEFORE UPDATE ON engine_snapshot BEGIN SELECT RAISE(ABORT, '스냅숏 실패'); END")
    /** 2차 GAP까지 보낸 뒤 재울음 직전 상태. 다음 울음이 SaveEpisode(secondResult = FAIL, TWO_ATTEMPTS)를 낸다. */
    private fun toSecondGap() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        k.ev(CrySegment(0, k.now + 5_000, k.now + 8_000, 0.9, true), k.now + 8_000)
        k.ev(Tick(0), k.ses().play?.endAt ?: error("2차 재생이 없다: ${k.ses().phase}"))
        assertEquals(Phase.GAP, k.ses().phase); assertEquals(2, k.ses().attemptNo)
    }
    private fun secondResultRow(): String? = k.db.openHelper.readableDatabase.query("SELECT second_result FROM episode").use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    @Test fun c297_contextCaptureFailure_keepsEpisodeAndHost() {
        k.profile(); k.boot(); k.open()
        k.ev(VehicleSample(0, 50.0, false, 1.0, TurnSignal.NONE), k.now + 1_000)   // 지난 초 줄이 있어야 첫 경보의 맥락 쓰기가 일어난다
        k.ev(VehicleSample(0, 50.0, false, 1.0, TurnSignal.NONE), k.now + 1_000)
        sql("CREATE TRIGGER fail_ctx BEFORE INSERT ON context_sample BEGIN SELECT RAISE(ABORT, '맥락 실패'); END")
        val died = catchingHostDeath { k.alarm() }
        assertNull(died, "맥락 기록 실패는 프로세스를 끝내지 않는다")
        val n = k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM episode").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, n, "에피소드 행은 저장된다")
    }

    @Test fun c299_reportBuildFailure_keepsSessionEndAndHost() {
        k.profile(); k.phone(); k.boot(); k.open()
        val id = k.ses().id
        sql("CREATE TRIGGER fail_report BEFORE INSERT ON outbox WHEN NEW.type = 'report_summary' BEGIN SELECT RAISE(ABORT, '리포트 실패'); END")
        val died = catchingHostDeath { k.ev(GearChanged(0, Gear.P), k.now + 60_000).ev(RearDoor(0, Side.LEFT, true)) }
        assertNull(died, "리포트 만들기 실패는 프로세스를 끝내지 않는다")
        assertNotNull(dao.session(id)!!.endedAt, "세션 종료는 저장된다")
        assertEquals(true, dao.session(id)!!.reportPending, "report_pending이 남아 다음 시작에 재개된다")
    }

    @Test fun c207_stepStoreWritesAndSnapshotAtomic() {
        toSecondGap()
        val before = k.snapshotJson()
        failSnapshotWrite()
        val died = catchingHostDeath { k.ev(CrySegment(0, k.now + 5_000, k.now + 8_000, 0.9, true), k.now + 8_000) }
        assertNotNull(died, "묶음 안 실패는 C173으로 프로세스를 끝낸다")
        assertNull(secondResultRow(), "스냅숏이 실패하면 에피소드 쓰기도 되돌려진다")
        assertEquals(before, k.snapshotJson(), "스냅숏은 옛 것 그대로")
    }

    // C302: 재시도 사이(dbPause)에 실패 원인을 걷어낸다. 재시도가 한 번 일어났는지 센다
    private fun <T> healOnPause(heal: () -> Unit, block: () -> T): T {
        var pauses = 0
        val orig = dbPause
        dbPause = { pauses++; heal() }
        try { return block() } finally { dbPause = orig; assertEquals(1, pauses, "재시도가 한 번 일어났다") }
    }

    // C302 T: 묶음 트랜잭션의 첫 시도만 실패하면 다시 해 커밋하고 프로세스를 끝내지 않는다
    @Test fun c302_tiedTxRetriesOnceThenCommits() {
        toSecondGap()
        failSnapshotWrite()
        val died = healOnPause({ sql("DROP TRIGGER fail_snap") }) { catchingHostDeath { k.ev(CrySegment(0, k.now + 5_000, k.now + 8_000, 0.9, true), k.now + 8_000) } }
        assertNull(died, "한 번 실패는 다시 해서 커밋한다")
        assertEquals("FAIL", secondResultRow(), "에피소드 쓰기가 들어갔다")
        assertEquals(k.state().session?.episode?.handoff, k.store.loadSnapshot()!!.session?.episode?.handoff, "같은 Step의 스냅숏도 함께 들어갔다")
    }

    // C302 A: 후보 ③ 다시 계산의 읽기가 한 번 실패해도 로그 없이 사라지지 않고 다시 읽어 반영한다
    @Test fun c302_methodsMaybeChanged_readFailsOnceThenApplies() {
        k.profile(); k.boot()
        sql("UPDATE child_profile SET methods = '[{\"kind\":\"WHITE_NOISE\"},{\"kind\":\"LULLABY\"},{\"kind\":\"SONG\"}]'")
        sql("INSERT INTO song (id, name, received_at, format, duration_s, lufs, normalized_path, status, reject_reason, added_seq, last_seq) VALUES ('s1', '자장자장', $T0, 'AAC', 60.0, -30.0, NULL, 'ACTIVE', NULL, 3, 3)")
        sql("ALTER TABLE song RENAME TO song_x")   // 첫 읽기가 실패한다
        healOnPause({ sql("ALTER TABLE song_x RENAME TO song") }) { k.host.methodsMaybeChanged(); k.host.awaitIdle() }
        assertEquals(Method(MethodKind.SONG, "s1"), k.state().methodsNext.last())
    }

    // C302 A: 다 실패해도 호스트는 이어 간다(후보 ③은 옛 값, 다음 계기가 다시 한다)
    @Test fun c302_methodsMaybeChanged_allFail_hostContinues() {
        k.profile(); k.boot()
        val before = k.state().methodsNext
        sql("UPDATE child_profile SET methods = '[{\"kind\":\"WHITE_NOISE\"},{\"kind\":\"LULLABY\"},{\"kind\":\"SONG\"}]'")   // 곡 표를 읽게 한다
        sql("ALTER TABLE song RENAME TO song_x")
        val died = catchingHostDeath { k.host.methodsMaybeChanged(); k.host.awaitIdle() }
        sql("ALTER TABLE song_x RENAME TO song")
        assertNull(died); assertEquals(before, k.state().methodsNext)
        k.ev(Tick(0), k.now + 1_000)
        assertTrue(k.host.state().now >= k.now, "다음 사건이 이어진다")
    }

    @Test fun c207_stepStoreWritesAndSnapshot_bothCommitted() {
        toSecondGap()
        k.ev(CrySegment(0, k.now + 5_000, k.now + 8_000, 0.9, true), k.now + 8_000)
        assertEquals("FAIL", secondResultRow())
        val snap = k.store.loadSnapshot()!!
        assertEquals(k.state().session?.episode?.handoff, snap.session?.episode?.handoff, "같은 Step의 스냅숏이 함께 들어갔다")
        assertNotNull(snap.session?.episode?.handoff); assertNotNull(snap.openLedger(), "인계 진입 Step의 스냅숏에 대기 알림이 Room 행과 함께 있다")
    }

    // ---- C210: speaking의 바뀜(시작·끝)도 바로 저장 ----
    @Test fun c210_speechDoneSavedImmediately_restoreKeepsRepeatBlock() {
        k.profile(); k.boot(); k.open(); k.alarm()
        val u = k.exec.filterIsInstance<Speak>().single { it.sid == Sid.S02 }.utteranceId   // 사건 없이 speaking만 바뀌는 평범한 안내
        assertEquals(u, k.store.loadSnapshot()!!.speech.speaking, "전제: 말하기 시작은 저장됐다")
        k.ev(SpeechDone(0, u, true), k.now + 5_000)
        assertNull(k.state().speech.speaking)
        val saved = k.store.loadSnapshot()!!.speech
        assertNull(saved.speaking, "말하기 끝도 바로 저장(죽으면 복원이 끝난 말을 말하던 줄로 보고 lastOffer를 지운다)")
        assertEquals(k.state().speech.lastDoneAt, saved.lastDoneAt)
    }

    // ---- C211: 90일 정리는 묶음 밖에서. 정리 실패가 세션 종료를 되돌리지 않고, 묶음 안 쓰기 실패는 삼키지 않는다 ----
    @Test fun c211_purgeFailureDoesNotRollbackStep() {
        k.profile(); k.phone(); k.boot(); k.open()
        val id = k.ses().id
        sql("CREATE TRIGGER fail_purge BEFORE DELETE ON control_log BEGIN SELECT RAISE(ABORT, '정리 실패'); END")
        sql("INSERT INTO control_log(session_id, at, api, target, goal, ok, retries) VALUES ('$id', 1, 'a', 't', 'g', 1, 0)")
        k.ev(GearChanged(0, Gear.P), k.now + 60_000).ev(RearDoor(0, Side.LEFT, true))
        assertNull(k.state().session)
        assertNotNull(dao.session(id)!!.endedAt, "정리가 실패해도 세션 종료는 커밋된다")
        assertEquals(1, reportBodies().size, "리포트도 커밋된다")
    }

    @Test fun c211_writeFailureInsideStepDies() {
        k.profile(); k.phone(); k.boot(); k.open()
        val id = k.ses().id
        sql("CREATE TRIGGER fail_close BEFORE UPDATE ON session BEGIN SELECT RAISE(ABORT, '쓰기 실패'); END")
        val died = catchingHostDeath { k.ev(GearChanged(0, Gear.P), k.now + 60_000).ev(RearDoor(0, Side.LEFT, true)) }
        assertNotNull(died, "묶음 안 쓰기 실패는 프로세스를 끝낸다")
        assertNull(dao.session(id)!!.endedAt)
    }

    // ---- C212: 끊기 직전에 이미 끝난 발화는 실제 완료 시각으로 SpeechDone ----
    @Test fun c212_settledSpeechUsesActualDoneTime() {
        val (_, u) = c160Speaking(handoffPhase = true)
        val doneAt = k.now + 500   // 끊는 사건(+1000)보다 이르다. 늦으면 C228로 사건 시각에 맞춘다
        k.cancelIfSpeakingHook = { x -> assertEquals(u, x); true to doneAt }
        k.ev(CallMode(0, true), k.now + 1_000)
        assertEquals(doneAt, k.state().speech.lastDoneAt, "호스트가 밀려도 실제 완료 시각")
    }

    // C228: SpeechDone 시각 = min(실제 완료, 끊는 사건 시각). 점프 Suspend는 잠들기 전 시각이라 잠든 시간을 세지 않는다
    @Test fun c228_jumpSuspend_settleDoesNotCountSleep() {
        c160Speaking(handoffPhase = true)
        val q0 = k.ses().quietMs
        val wake = k.now + 600_000
        k.cancelIfSpeakingHook = { true to wake }   // 깨어난 뒤에야 TTS 완료가 보고됐다
        k.slept += 600_000
        k.ev(Tick(0), wake)
        assertTrue((k.state().session?.quietMs ?: 0) - q0 < 600_000, "잠든 10분이 quietMs에 더해지지 않는다")
        assertNotEquals(Phase.LISTENING, k.state().session?.phase)
    }

    // ---- C241(11-07 42회차): 통화·장면 시작은 밀려 늦게 처리돼도 실제 완료 시각 그대로(S1 8초 간격). Suspend만 C228로 사건 시각에 맞춘다 ----
    private fun queuedBehindHost(post: () -> Unit, doneAt: Long, processAt: Long) {
        val gate = java.util.concurrent.CountDownLatch(1); val inside = java.util.concurrent.CountDownLatch(1)
        k.onSlept = { k.onSlept = null; inside.countDown(); gate.await() }
        k.host.post(Tick(0)); inside.await()
        post()   // 사건은 지금 시각으로 stamp된다
        k.now = processAt
        k.cancelIfSpeakingHook = { true to doneAt }
        gate.countDown(); k.host.awaitIdle()
    }

    @Test fun c241_callMode_queuedEventEarlierThanDone_keepsDoneAt() {
        c160Speaking(handoffPhase = true)
        val t0 = k.now
        queuedBehindHost({ k.host.post(CallMode(0, true)) }, doneAt = t0 + 4_000, processAt = t0 + 5_000)
        assertEquals(t0 + 4_000, k.state().speech.lastDoneAt, "통화 사건 시각(t0)이 아니라 실제 완료 시각")
    }

    @Test fun c241_sceneStart_keepsDoneAt() {
        c160Speaking(handoffPhase = true)
        val doneAt = k.now + 500
        k.cancelIfSpeakingHook = { true to doneAt }
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(doneAt, k.state().speech.lastDoneAt)
    }

    @Test fun c241_normalSuspend_keepsDoneAt() {
        c160Speaking(handoffPhase = true)
        val doneAt = k.now + 5_000   // 사건 시각(지금)보다 늦은 실제 완료: 이른 시각 상한이면 앞당겨진다
        k.cancelIfSpeakingHook = { true to doneAt }
        k.host.suspend(); k.host.awaitIdle()
        assertEquals(doneAt, k.state().speech.lastDoneAt)
    }

    // C252(11-07 44회차): 점프 확인의 첫 정리 뒤 다음 발화가 바로 끝나도(포커스 거절) 그 정리도 잠들기 전 시각이 상한이다
    @Test fun c252_jumpSettle_nextSpeechFailsAtOnce_sleepNotCounted() {
        val (_, u) = c160Speaking(handoffPhase = true, extra = listOf(Line(Sid.S19, emptyMap(), null, qaAnswer = true)))
        val q0 = k.ses().quietMs
        val wake = k.now + 600_000
        k.cancelIfSpeakingHook = { x -> if (x == u) true to wake else false to wake }   // 첫 발화는 끝났고 다음 발화는 바로 실패
        k.slept += 600_000
        k.ev(Tick(0), wake)
        assertTrue((k.state().session?.quietMs ?: 0) - q0 < 600_000, "잠든 10분이 quietMs에 더해지지 않는다")
        assertEquals(Phase.HANDOFF, k.state().session?.phase, "인계를 끝내지 않는다")
    }

    // ---- C259: 실행기 결과를 버리는 모든 곳(통화 시작·Suspend·시계 점프·장면 시작·장면 교체)이 먼저 정리한다. 끝났으면 told=참(1), 말하는 중이면 끊어 거짓(0) ----
    private fun settleCase(finished: Boolean, trigger: (wake: Long) -> Unit) {
        val (id, _) = c160Speaking(handoffPhase = true)
        val wake = k.now + 600_000
        k.cancelIfSpeakingHook = if (finished) { _ -> true to wake } else null
        trigger(wake)
        assertEquals(if (finished) 1 else 0, toldDriverRow(id))
    }
    private fun call(@Suppress("UNUSED_PARAMETER") w: Long) { k.ev(CallMode(0, true), k.now + 1_000) }
    private fun suspendNow(@Suppress("UNUSED_PARAMETER") w: Long) { k.host.suspend(); k.host.awaitIdle() }
    private fun jump(w: Long) { k.slept += 600_000; k.ev(Tick(0), w) }
    private fun sceneStart(@Suppress("UNUSED_PARAMETER") w: Long) { k.host.startScenario(setup()); k.host.awaitIdle() }
    @Test fun c259_call_finished() = settleCase(true, ::call)
    @Test fun c259_call_stillSpeaking() = settleCase(false, ::call)
    @Test fun c259_suspend_finished() = settleCase(true, ::suspendNow)
    @Test fun c259_suspend_stillSpeaking() = settleCase(false, ::suspendNow)
    @Test fun c259_jump_finished() = settleCase(true, ::jump)
    @Test fun c259_jump_stillSpeaking() = settleCase(false, ::jump)
    @Test fun c259_sceneStart_finished() = settleCase(true, ::sceneStart)
    @Test fun c259_sceneStart_stillSpeaking() = settleCase(false, ::sceneStart)
    // endScenario의 두 경우는 SceneCloseTest c254_endScenario_*

    // 장면 중 장면 교체: 장면 상태의 발화도 실행기에서 먼저 끊고 정리한다
    @Test fun c259_sceneReplace_settlesSceneSpeech() {
        k.profile(); k.boot()
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.open(); k.alarm()
        val u = k.state().speech.speaking!!
        var asked: String? = null
        k.cancelIfSpeakingHook = { x -> asked = x; null }
        k.host.startScenario(setup()); k.host.awaitIdle()
        assertEquals(u, asked, "교체 전에 실행기에 묻는다")
    }

    // C259: 점프 중 첫 정리가 다음 발화를 시작하고 그 발화가 바로 끝나는 경우 run 안의 Suspend 정리도 상한을 쓴다(C252). HANDOFF 유지
    @Test fun c259_jumpCapAppliesToNestedSettle() = c252_jumpSettle_nextSpeechFailsAtOnce_sleepNotCounted()

    @Test fun c241_jumpSuspend_stillClampedToEventTime() = c228_jumpSuspend_settleDoesNotCountSleep()

    // ---- C209: CareCleared도 넣을 때 stamp ----
    @Test fun c209_careClearedStampedAtPost() {
        k.profile(); k.boot()
        val gate = java.util.concurrent.CountDownLatch(1); val inside = java.util.concurrent.CountDownLatch(1)
        k.onSlept = { k.onSlept = null; inside.countDown(); gate.await() }
        k.host.post(Tick(0)); inside.await()
        val postedAt = k.now + 1_000
        k.now = postedAt; k.host.postReal(CareCleared(0))
        k.now = postedAt + 60_000   // 큐에서 꺼낼 때는 시계가 더 갔다
        gate.countDown(); k.host.awaitIdle()
        assertEquals(postedAt, k.state().now, "넣을 때 찍은 시각으로 처리된다")
    }

    // ---- C201(결정 ③): 판단 코어는 guardian_notified를 갖지 않는다. 복원 뒤 SaveEpisode가 Room의 거짓을 참으로 덮지 않는다 ----
    @Test fun c201_restoreAppliesRoomGuardianNotifiedFalse() {
        val (id, u) = c160Speaking()
        sql("UPDATE episode SET guardian_notified = 0 WHERE id = '$id'"); k.phone("owner")
        k.boot()
        k.ev(SpeechDone(0, u, true)).ev(GearChanged(0, Gear.P), k.now + 60_000).ev(RearDoor(0, Side.LEFT, true))
        assertNull(k.state().session); assertEquals(0, guardianNotifiedRow(id), "SaveEpisode가 true로 덮지 않는다")
        assertTrue(reportBodies().all { "\"guardian_notified\":false" in it }, reportBodies().toString())
    }

    @Test fun c191_sceneStartImmediateWhenNotSpeaking() {
        k.profile(); k.boot(); k.open()
        assertNull(k.state().speech.speaking); assertNotNull(k.state().session, "전제: 실제 세션")
        k.exec.clear()
        k.host.startScenario(setup()); k.host.awaitIdle()
        // C319 V9: 말하지 않아도 실행기를 끊는다(질문 답·④ 듣기가 같은 실행기다. Suspend·통화와 같다). 기다리지 않고 바로 시작하는 것은 그대로
        assertNull(k.state().session, "말하지 않으면 바로 시작한다"); assertTrue(k.exec.any { it is CancelSpeech })
    }

    // ---- C188: speaking을 바꾼 Step은 Speak·Ask를 실행기에 넘기기 전에 스냅숏을 저장한다 ----
    @Test fun c188_snapshotSavedBeforeAskDispatched() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val line = Line(Sid.S11, mapOf("restName" to "n 휴게소"), "n", longDrive = true)
        k.store.saveSnapshot(s.copy(   // 스냅숏에는 말하지 않은 상태. 복원이 말하던 줄을 버린 뒤 새 경로로 S11 Ask를 낸다(r14x_c151과 같은 길)
            speech = s.speech.copy(speaking = "u1", queue = listOf(line)),
            session = s.session!!.let { it.copy(offers = it.offers.copy(pendingAsk = PendingAsk("a1", Sid.S11, "n", null, true), driveFrom = k.now - 7_300_000)) }))
        k.boot()
        assertNull(k.store.loadSnapshot()!!.speech.speaking)
        val at = mutableListOf<Pair<String, String?>>()   // 실행기에 Ask가 들어가는 순간 저장된 스냅숏의 speaking
        k.execHook = { c -> if (c is Ask) at += c.askId to k.store.loadSnapshot()?.speech?.speaking }
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        k.ev(Tick(0), k.now + Params().speechGapMs + 1_000)
        assertTrue(at.isNotEmpty(), "전제: Ask가 나갔다")
        at.forEach { (id, saved) -> assertEquals(id, saved, "Ask를 넘기기 전에 speaking이 저장돼 있다") }
    }

    // ---- C192: 말하기 전 스냅숏 저장이 실패하면 그 Step을 끊고 프로세스를 끝낸다(Ask를 넘기지 않는다) ----
    @Test fun c192_preSpeakSnapshotFailure_dies_noExecCall() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val line = Line(Sid.S11, mapOf("restName" to "n 휴게소"), "n", longDrive = true)
        k.store.saveSnapshot(s.copy(speech = s.speech.copy(speaking = "u1", queue = listOf(line)),
            session = s.session!!.let { it.copy(offers = it.offers.copy(pendingAsk = PendingAsk("a1", Sid.S11, "n", null, true), driveFrom = k.now - 7_300_000)) }))
        k.boot()
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        k.exec.clear()
        sql("CREATE TRIGGER snap_fail BEFORE INSERT ON engine_snapshot BEGIN SELECT RAISE(ABORT, '저장 실패'); END")
        val died = catchingHostDeath { k.now += Params().speechGapMs + 1_000; k.host.post(Tick(0)) }
        assertNotNull(died, "예외가 프로세스 밖으로 나간다"); assertTrue(k.exec.none { it is Ask || it is Speak }, "저장하지 못한 말하기는 실행기에 넘기지 않는다")
    }

    // ---- C193(결정 ③): link가 받는 사람이 없어 알림을 보낼 함에 넣지 않으면 guardian_notified는 거짓 그대로 ----
    private fun guardianNotifiedRow(id: String): Int = k.db.openHelper.readableDatabase.query("SELECT guardian_notified FROM episode WHERE id = '$id'").use { it.moveToFirst(); it.getInt(0) }

    @Test fun c193_linkSkipsEnqueue_guardianNotifiedStaysFalse() {
        val (id, u) = c160Speaking()
        sql("DELETE FROM phone_link")   // 엔진이 LinkChanged를 받기 전에 마지막 보호자가 해제됐다
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.ev(SpeechDone(0, u, true))
        assertEquals(0, outboxTypes(), "보낼 함에 넣지 않았다"); assertEquals(1, toldDriverRow(id))
        assertEquals(0, guardianNotifiedRow(id), "리포트에 '다른 보호자에게 알림'이 남지 않는다")
    }

    @Test fun c193_linkSkipsEnqueue_closedEpisodeRow() {
        val (id, _) = c159Closed()
        sql("DELETE FROM phone_link")
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.boot()   // 닫힌 세션의 인계 묶음을 첫 Step이 정리한다
        assertEquals(0, outboxTypes()); assertEquals(0, toldDriverRow(id))
        assertEquals(0, guardianNotifiedRow(id), "닫힌 행도 거짓")
    }

    // ---- D2(결정 ③·V7): guardian_notified는 link가 보낼 함에 넣는 트랜잭션에서만 쓴다. 판단 코어는 이 값을 갖지 않는다 ----
    /** P에서 경보 → 뒷좌석 출력이 없어 재생 거절(4k): 같은 Step에서 에피소드 행이 새로 생기고 H1 알림이 바로 나간다. 그 에피소드 id. */
    private fun parkedNoSoundHandoff(): String {
        k.alarm()
        k.ev(PlaybackRejected(0, k.ses().play!!.id, RejectReason.NO_REAR_OUTPUT))
        assertEquals(Phase.HANDOFF, k.ses().phase)
        return k.ses().episode!!.id
    }

    @Test fun d2_guardianNotifiedOnlyByLink() {
        k.phone("guardian", alerts = true); k.profile(); k.boot(); k.open()
        k.autoStart = false
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.ev(GearChanged(0, Gear.P), k.now + 1_000)
        val queued = parkedNoSoundHandoff()
        assertEquals(1, outboxTypes()); assertEquals(1, guardianNotifiedRow(queued), "넣은 트랜잭션에서 참(store 쓰기로 행이 먼저 생긴다)")
        val step = k.host.steps.value!!
        assertTrue(step.commands.any { it is SendToPhone && it.type == "handoff_alert" }, "전제: 이 Step이 알림을 냈다")
        val json = SnapshotJson.encodeToString(EngineState.serializer(), step.state) + step.commands.joinToString { it.toString() }
        assertTrue("guardianNotified" !in json && "guardian_notified" !in json, "판단 코어의 상태·할 일에 그 값이 없다: $json")
        val types = listOf(EngineState::class.java, SessionState::class.java, EpisodeState::class.java, HandoffRecord::class.java, HandoffLedger::class.java, EpisodeRecord::class.java)
        assertTrue(types.flatMap { it.declaredFields.toList() }.none { "guardian" in it.name.lowercase() && it.name != "otherGuardians" }, "판단 코어 타입에 칸이 없다")
        // 받는 사람이 없어 넣지 못하면 거짓 그대로(판단 코어는 캐시 otherGuardians > 0이라 알림을 냈다)
        sql("DELETE FROM phone_link")
        k.ev(Tick(0), k.now + Params().resumeQuietMs + 1_000)   // 11a: 조용함으로 에피소드를 닫는다
        assertNull(k.ses().episode)
        val skipped = parkedNoSoundHandoff()
        assertTrue(k.host.steps.value!!.commands.any { it is SendToPhone && it.type == "handoff_alert" }, "전제: 판단 코어는 알림을 냈다")
        assertEquals(1, outboxTypes(), "보낼 함에 넣지 않았다"); assertEquals(0, guardianNotifiedRow(skipped), "넣지 못하면 거짓")
        assertEquals(1, guardianNotifiedRow(queued), "앞 행은 그대로")
    }

    // ---- D2(V6·V20): 장부 하나. 열린 세션 장부와 닫힌 세션 장부가 같은 확정 규칙(SettleHandoff)으로 행을 쓴다 ----
    @Test fun d2_singleLedgerSettle() {
        fun kindRow(id: String) = k.db.openHelper.readableDatabase.query("SELECT handoff_kind FROM episode WHERE id = '$id'").use { it.moveToFirst(); it.getString(0) }
        fun row(id: String) = Triple(kindRow(id), toldDriverRow(id), restNameRow(id))
        // 닫힌 세션: 묶인 줄을 말하던 세션이 Room에서 끝난 채 복원 → 첫 Step의 flush가 닫힌 세션 장부를 확정(C159)
        val (closedId, _) = c159Closed(alert = false)
        k.running = emptySet(); k.boot()   // 마이크는 살아 있다(다음 세션이 입력 끊김을 이어받지 않게)
        assertNull(k.state().session); assertTrue(k.state().handoffs.isEmpty())
        // 열린 세션: 새 세션 에피소드의 장부가 묶인 줄 없이 남았다 → 첫 Step의 flush가 열린 세션 장부를 확정(H10)
        k.open(); k.alarm(); k.toGap()
        val s = k.state(); val x = s.session!!; val ep = x.episode!!
        k.store.saveSnapshot(s.copy(session = x.copy(episode = ep.copy(handoff = HandoffRecord(HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER))),
            handoffs = listOf(HandoffLedger(x.id, ep.id, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, alert = false))))
        k.running = null; k.boot()
        assertNotNull(k.state().session); assertTrue(k.state().handoffs.isEmpty())
        assertEquals(Triple("WAYPOINT_OFFER", 0, null), row(ep.id))
        assertEquals(row(ep.id), row(closedId), "열린·닫힌 세션 장부가 같은 규칙으로 행을 쓴다")
        // 확정 뒤 그 에피소드의 SaveEpisode가 확정 칸을 덮지 않는다(store가 지금 행 값을 지킨다)
        k.ev(DriverStop(0, Via.TOOL), k.now + 1_000)
        assertEquals(Phase.WAIT, k.ses().phase)
        assertEquals(Triple("WAYPOINT_OFFER", 0, null), row(ep.id))
    }

    // ---- C195(결정 ③): 보낼 함에 못 넣으면 에피소드 행이 거짓이라 같은 Step의 리포트가 맞다 ----
    @Test fun c195_notQueued_reportSaysNotNotified() {
        val (id, _) = c159Closed()
        sql("DELETE FROM phone_link"); k.phone("owner")   // 받는 보호자는 없고 리포트를 받을 owner만 남는다
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.boot()
        assertEquals(0, outboxTypes(), "알림은 보낼 함에 넣지 않았다")
        assertEquals(0, guardianNotifiedRow(id))
        val r = reportBodies(); assertEquals(1, r.size, "owner에게 리포트: $r")
        assertTrue("\"guardian_notified\":false" in r[0], r[0])
    }

    // ---- C277(결정 ③): 닫힌 행 읽기(옛 C193 사건)가 없어졌다. 넣지 않은 알림의 행은 다시 켜져도 거짓이다 ----
    @Test fun c277_notQueuedClosedRow_restoreKeepsNotNotified() {
        val (id, _) = c159Closed()
        sql("DELETE FROM phone_link"); k.phone("owner")
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.boot()
        k.boot()   // 다시 켜짐: 복원 대조
        assertEquals(0, outboxTypes()); assertEquals(0, guardianNotifiedRow(id), "Room의 거짓 값이 남는다")
        val r = reportBodies(); assertEquals(1, r.size, "$r"); assertTrue("\"guardian_notified\":false" in r[0], r[0])
    }

    /** 말하던 줄은 말하지 않은 것으로 끝낸다(Q1 듣기를 열지 않게). */
    private fun Kit.hush() { state().speech.speaking?.let { ev(SpeechDone(0, it, false)) } }
    /** GAP → CONFIRM → 성공(에피소드 닫힘). */
    private fun Kit.quietClose() = repeat(2) { val we = ses().windowEnd!!; ev(CrySegment(0, we - 1_000, we + 2_000, 0.1, false), we + 2_000) }
    private fun Kit.alarmEpisode() { alarm(); hush(); toGap(); quietClose(); check(ses().episode == null) }

    @Test fun c277_cryFactCountFromEngine_noRoomRead() {
        k.profile(); k.boot(); k.open()
        repeat(3) { k.alarmEpisode() }
        assertEquals(3, k.ses().cryRuns)
        k.ev(GearChanged(0, Gear.P), k.now + 1_000); k.hush()
        k.ev(AskButton(0), k.now + 1_000)
        val id = k.state().speech.qa!!.listenId
        k.ev(Heard(0, id, "얼마나 울었어"), k.now + 1_000)
        sql("ALTER TABLE episode RENAME TO episode_gone")   // Room 경보 수 읽기가 실패한다
        try { k.ev(QaReply(0, id, "", careUsed = false, factTopics = listOf("cry")), k.now + 1_000) } finally { sql("ALTER TABLE episode_gone RENAME TO episode") }
        val line = k.state().speech.queue.firstOrNull { it.sid == Sid.QA }?.vars?.get("text") ?: k.exec.filterIsInstance<Speak>().lastOrNull { it.sid == Sid.QA }?.vars?.get("text")
        assertTrue(line != null && line.endsWith("이번 주행에서 3번 울었어요."), "$line")
    }

    @Test fun c277_restore_alarmCountMatchesRoom() {
        k.profile(); k.boot(); k.open()
        repeat(2) { k.alarmEpisode() }
        val s = k.store.loadSnapshot()!!
        k.store.saveSnapshot(s.copy(session = s.session!!.copy(cryRuns = 0)))   // 칸이 없던 옛 스냅숏(기본값 0)
        k.boot()
        assertEquals(2, k.ses().cryRuns, "옛 스냅숏은 Room 경보 에피소드 수(하한)로 맞춘다")
    }

    @Test fun c277_clearRecords_zero_thenRestoreKeepsLaterCount() {
        k.profile(); k.boot(); k.open()
        k.alarmEpisode()
        k.ev(ClearRecords(0), k.now + 1_000)
        assertEquals(0, k.ses().cryRuns, "기록을 지우면 0")
        k.alarmEpisode()
        assertEquals(1, k.ses().cryRuns)
        k.boot()
        assertEquals(1, k.ses().cryRuns, "복원은 스냅숏 값(지운 세션은 Room에 행이 없다)")
    }

    /** C286: 장면에서도 소리를 직접 튼 에피소드 중 경보가 이어지면 ④ 사실 답은 울고 있다고 한다(호스트 큐 composeFacts 길). */
    @Test fun c286_scene_switchSoundThenAlarms_cryFactSaysCrying() {
        k.profile(); k.boot()
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.open()
        k.ev(SwitchSound(0, Method(MethodKind.WHITE_NOISE)), k.now + 1_000)
        k.ev(GearChanged(0, Gear.P), k.now + 1_000)                       // 정차(재생은 멈추고 에피소드는 그대로)
        assertEquals(EpisodeTrigger.SWITCH_SOUND, k.ses().episode!!.trigger)
        k.ev(AskButton(0), k.now + 100)
        val id = k.state().speech.qa!!.listenId
        k.ev(Heard(0, id, "아직 울어?"), k.now + 100)
        repeat(3) { k.alarm(975) }                                        // 아기가 운다(경보만 기록)
        k.ev(QaReply(0, id, "", careUsed = false, factTopics = listOf("cry")), k.now + 500)
        val line = k.state().speech.queue.firstOrNull { it.sid == Sid.QA }?.vars?.get("text") ?: k.exec.filterIsInstance<Speak>().lastOrNull { it.sid == Sid.QA }?.vars?.get("text")
        assertEquals("지금 1분째 울고 있고, 이번 주행에서 1번 울었어요.", line, "C287: 지금 울음과 같은 기준으로 센다")
    }

    @Test fun c175_sceneResumeReconcile_doesNotOverwriteSceneProfile() {
        k.profile(); k.boot()
        k.host.startScenario(setup()); k.host.awaitIdle()
        val sceneProfile = k.state().profile
        k.profile(interval = 120)   // 실제 Room 프로필이 장면 값과 다르다
        k.host.suspend(); k.host.resume(); k.host.awaitIdle()
        assertEquals(sceneProfile, k.state().profile, "장면 상태에 실제 프로필을 넣지 않는다")
    }

    @Test fun c159_twoClosedHandoffsAtRestore_bothFlushedPlain() {
        // 스냅숏에 A의 닫힌 묶음이 있고, 열려 있던 B 세션이 Room에서 닫힌 채 복원된다
        val (idB, sidB) = c159Closed()
        val s = k.store.loadSnapshot()!!
        val sidA = "sessA"
        dao.upsertSession(dao.session(sidB)!!.copy(id = sidA))   // A: 이미 닫힌 세션(리포트 대기)
        val a = HandoffLedger(sidA, null, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, alert = true, utteranceId = "uA")
        k.store.saveSnapshot(s.copy(handoffs = listOf(a) + s.handoffs, speech = s.speech.copy(speaking = "u1")))
        k.exec.clear(); k.link.clear(); k.running = null
        k.boot()
        assertTrue(k.state().handoffs.isEmpty())
        assertEquals(2, k.link.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" && it.body["text"]!!.toString().trim('"') == "아기가 계속 울어요." }, "A·B 모두 이름 없는 알림")
        assertEquals(0, toldDriverRow(idB))
        assertEquals(2, reportBodies().size, "리포트 각 한 번")
    }

    // ---- C221: 사실 답은 큐에서 처리하는 순간의 상태로 짠다 ----
    private fun c221Thinking(): String {
        k.profile(); k.boot(); k.open()
        k.ev(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 1), seq = 1), k.now + 1_000)
        k.ev(GearChanged(0, Gear.P), k.now + 1_000)
        k.ev(AskButton(0), k.now + 1_000)
        val id = k.state().speech.qa!!.listenId
        k.ev(Heard(0, id, "오늘 몇 번 먹었어"), k.now + 1_000)
        assertEquals(QaStep.THINKING, k.state().speech.qa!!.step, "전제: 답을 기다린다")
        return id
    }
    private fun qaLine() = k.state().speech.queue.firstOrNull { it.sid == Sid.QA }?.vars?.get("text")
        ?: k.exec.filterIsInstance<Speak>().firstOrNull { it.sid == Sid.QA }?.vars?.get("text")

    @Test fun c221_factAnswerComposedAtHostQueue() {
        val id = c221Thinking()
        k.ev(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 2), seq = 2), k.now + 1_000)   // 새 요약(2회)이 큐에서 먼저 처리된다
        assertEquals(true, k.state().speech.qa?.careStale, "전제: 질문 중 요약이 바뀌었다")
        k.ev(QaReply(0, id, "", careUsed = true, factTopics = listOf("feeding")), k.now + 1_000)
        assertEquals("기록상 오늘 2번 먹었어요.", qaLine(), "처리하는 순간의 요약(2회)으로 말한다")
    }

    @Test fun c221_factAnswerAfterCareCleared_saysUnknown() {
        val id = c221Thinking()
        k.host.postReal(CareCleared(0)); k.host.awaitIdle()
        k.ev(QaReply(0, id, "", careUsed = true, factTopics = listOf("feeding")), k.now + 1_000)
        assertEquals("수유 기록을 확인할 수 없어요.", qaLine(), "지운 뒤 상태로 말한다")
    }

    // C227: 대체 답도 큐에서 처리하는 순간의 상태로 짠다
    @Test fun c227_whyFallbackComposedAtHost_afterCareCleared() {
        val id = c221Thinking()
        k.ev(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 3), seq = 3), k.now + 1_000)
        k.host.postReal(CareCleared(0)); k.host.awaitIdle()
        k.ev(QaReply(0, id, "AI 글", careUsed = true, llm = true, fallback = "기록상 134분 전에 깼어요.", fallbackCare = true, fallbackTopics = listOf("why")), k.now + 1_000)
        val line = qaLine()
        assertTrue(line != null && "카시트에" in line && "134분" !in line, "$line")
    }

    @Test fun c227_offlineWhyComposedAtHost() {
        val id = c221Thinking()
        k.host.postReal(CareCleared(0)); k.host.awaitIdle()
        k.ev(QaReply(0, id, "", careUsed = true, factTopics = listOf("why")), k.now + 1_000)
        val line = qaLine()
        assertTrue(line != null && "카시트에" in line && "기록상" !in line, "$line")
    }

    // C229: QaReply 시각까지 시간 진행을 먼저 적용한 상태로 짠다
    @Test fun c229_factComposedAfterTimeAdvance() {
        k.profile(); k.boot(Params(attemptMs = 2_500)); k.open()   // 재생이 질문 대기(4초) 안에 끝나게
        k.ev(GearChanged(0, Gear.P), k.now + 100)
        k.ev(AskButton(0), k.now + 100)
        val id = k.state().speech.qa!!.listenId
        k.ev(Heard(0, id, "얼마나 울었어"), k.now + 100)
        k.ev(GearChanged(0, Gear.D), k.now + 100).alarm(500)   // 질문 중 주행해 ATTEMPT 재생
        assertEquals(Phase.ATTEMPT, k.ses().phase, "전제: 재생 중")
        assertEquals(QaStep.THINKING, k.state().speech.qa?.step, "전제: 답을 기다린다")
        k.ev(QaReply(0, id, "", careUsed = false, factTopics = listOf("cry")), k.ses().play!!.endAt)
        assertEquals(Phase.GAP, k.ses().phase, "같은 사건에서 GAP")
        val line = qaLine()
        assertTrue(line != null && "울음이 그쳤는지 확인하고 있고" in line && "지금은 울음이 들리지 않고" !in line, "C231: GAP에서는 확인 중이라 말한다 $line")
    }

    // ---- C231: 그침 확인 중(GAP)의 울음 답은 '들리지 않고'가 아니다 ----
    @Test fun c231_gapSaysConfirming_notSilent() {
        k.profile(); k.boot(Params(attemptMs = 2_500)); k.open()
        k.ev(GearChanged(0, Gear.P), k.now + 100)
        k.ev(AskButton(0), k.now + 100)
        val id = k.state().speech.qa!!.listenId
        k.ev(Heard(0, id, "얼마나 울었어"), k.now + 100)
        k.ev(GearChanged(0, Gear.D), k.now + 100).alarm(500)
        k.ev(QaReply(0, id, "", careUsed = false, factTopics = listOf("cry")), k.ses().play!!.endAt)
        assertEquals(Phase.GAP, k.ses().phase)
        val line = qaLine()
        assertTrue(line != null && line.startsWith("울음이 그쳤는지 확인하고 있고, 이번 주행에서 ") && line.endsWith("번 울었어요."), "$line")
    }

    // ---- C222: 단독 스냅숏 저장 실패도 die ----
    @Test fun c222_standaloneSnapshotFailureDies() {
        k.profile(); k.boot(); k.open()
        sql("CREATE TRIGGER snap_fail BEFORE INSERT ON engine_snapshot BEGIN SELECT RAISE(ABORT, '저장 실패'); END")
        val died = catchingHostDeath { k.now += 1_000; k.host.post(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 2))) }   // 묶음 없는 바로 저장
        assertNotNull(died, "예외가 프로세스 밖으로 나간다(호스트 스레드는 삼킨다)")
    }

    // ---- C226: 기록 지우기 트랜잭션 실패는 die, 부분 상태 없음 ----
    @Test fun c226_clearRecordsTxFailureDies_noPartialState() {
        k.profile(); k.phone(); k.boot(); k.open(); k.alarm()
        val before = k.snapshotJson()
        sql("CREATE TRIGGER clr_fail BEFORE INSERT ON outbox WHEN NEW.type = 'records_clear' BEGIN SELECT RAISE(ABORT, '지우기 실패'); END")
        val died = catchingHostDeath { k.host.post(ClearRecords(0)) }
        assertNotNull(died, "삼키지 않고 프로세스를 끝낸다")
        val n = { t: String -> k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $t").use { it.moveToFirst(); it.getInt(0) } }
        assertTrue(n("session") > 0 && n("episode") > 0, "되돌려져 기록이 그대로")
        assertEquals(0, n("outbox"), "records_clear를 넣지 못했다")
        assertEquals(before, k.snapshotJson(), "스냅숏도 옛 것 그대로")
    }

    // ---- C236: Resume 전에 link 대조(Suspend 중 Room에만 든 수유)를 먼저 반영한다 ----
    @Test fun c236_resumeReconcilesBeforeDepartCheck() {
        k.profile(interval = 180); k.boot(); k.open()
        k.host.suspend(); k.host.awaitIdle()
        assertNotNull(k.state().ign.suspendedAt)
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        k.db.storeDao().upsertProfile(k.db.storeDao().profile()!!.copy(lastFeedingStart = k.now - 120 * 60_000L))   // 폰 수유가 Room에만
        k.exec.clear(); k.now += 2_000
        k.host.resume(); k.host.awaitIdle()
        assertNull(k.state().ign.suspendedAt)
        assertTrue(k.ses().departChecked)
        assertEquals(listOf(Sid.S07_DEPART), k.state().speech.queue.map { it.sid }, "최신 수유로 출발 제안을 판단한다")
    }

    // ---- C237: 장면 시작도 맨 앞에서 시계 점프를 확인한다 ----
    @Test fun c237_sceneStartChecksJumpFirst() {
        val (_, u) = c160Speaking(handoffPhase = true)
        val q0 = k.ses().quietMs
        val wake = k.now + 600_000
        k.cancelIfSpeakingHook = { true to wake }   // 깨어난 뒤에야 TTS 완료가 보고됐다
        k.slept += 600_000; k.now = wake
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.host.endScenario(); k.host.awaitIdle()
        assertTrue((k.state().session?.quietMs ?: 0) - q0 < 600_000, "잠든 10분이 quietMs에 더해지지 않는다")
        assertNotEquals(Phase.LISTENING, k.state().session?.phase, "잠든 시간으로 에피소드가 닫히지 않는다")
    }

    // ---- C238: 말하기 큐의 줄 목록이 바뀌면 바로 저장한다 ----
    @Test fun c238_droppedDepartOfferSavedImmediately() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val line = Line(Sid.S07_DEPART, emptyMap(), "n", queuedAt = k.now, sessionId = s.session!!.id)
        k.store.saveSnapshot(s.copy(car = s.car.copy(gear = Gear.R), speech = s.speech.copy(queue = listOf(line)), session = s.session!!.let { it.copy(offers = it.offers.copy(spokenAt = mapOf(Sid.S07_DEPART to k.now))) }))
        k.boot()
        k.ev(GearChanged(0, Gear.R), k.now + 1_000)
        assertTrue(k.state().speech.queue.isEmpty(), "R 기어에서 버렸다")
        assertTrue(k.store.loadSnapshot()!!.speech.queue.isEmpty(), "저장 전에 죽어도 옛 제안이 되살아나지 않는다")
    }
}
