package kr.aigyeot.app.host

import kotlinx.coroutines.cancel
import kr.aigyeot.app.store.db.PlayLogEntity
import kr.aigyeot.app.store.db.SessionEntity
import kr.aigyeot.app.store.db.SteerWindowEntity
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.AskButton
import kr.aigyeot.engine.CallMode
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.ClearRecords
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.DriverStop
import kr.aigyeot.engine.EpisodeTrigger
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.HandoffCause
import kr.aigyeot.engine.HandoffKind
import kr.aigyeot.engine.HandoffLedger
import kr.aigyeot.engine.Heard
import kr.aigyeot.engine.InputLost
import kr.aigyeot.engine.InputRestored
import kr.aigyeot.engine.Line
import kr.aigyeot.engine.Listen
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Notice
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.PauseReason
import kr.aigyeot.engine.PendingAsk
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.PlaybackRejected
import kr.aigyeot.engine.QaReply
import kr.aigyeot.engine.QaStep
import kr.aigyeot.engine.QaWindow
import kr.aigyeot.engine.RearDoor
import kr.aigyeot.engine.RejectReason
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.RouteState
import kr.aigyeot.engine.RouteView
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Side
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.Step
import kr.aigyeot.engine.SwitchSound
import kr.aigyeot.engine.Tick
import kr.aigyeot.engine.TryResult
import kr.aigyeot.engine.TurnSignal
import kr.aigyeot.engine.Unjudged
import kr.aigyeot.engine.VehicleSample
import kr.aigyeot.engine.Via
import kr.aigyeot.engine.capReached
import kr.aigyeot.engine.steer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 복원(프로세스 시작·장면 끝)과 Room 대조.
@RunWith(RobolectricTestRunner::class)
class HostRestoreTest : HostTestBase() {
    // ---- 복원 뒤 대조·바로 다시 확인 ----

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

    @Test fun screenOnAndResume_wakeLink() {
        k.profile(); k.boot()
        k.host.resume(); k.host.awaitIdle()
        assertEquals(1, k.wakes, "화면 켜짐은 Suspend가 아니어도 바로 다시 확인")
        k.host.suspend(); k.host.resume(); k.host.awaitIdle()
        assertEquals(2, k.wakes, "Resume 짐작")
    }

    @Test fun clockJump_wakesLink() {
        k.profile(); k.boot()
        assertEquals(0, k.wakes)
        k.slept += 600_000
        k.ev(Tick(0), k.now + 600_000)
        assertEquals(1, k.wakes, "신호 없는 시계 점프")
    }

    @Test fun wakeFailureOnClockJump_doesNotCutResume() {
        k.profile(); k.boot()
        k.host.targets.let { t ->
            k.host.targets = Targets(
                t.exec, t.link, t.notice, wake = { throw IllegalStateException("wake 실패") }, restore = t.restore,
                reemitAll = t.reemitAll, cancelIfSpeaking = t.cancelIfSpeaking, linkNow = t.linkNow,
            )
        }
        k.slept += 600_000
        k.ev(Tick(0), k.now + 600_000)
        assertNull(k.state().ign.suspendedAt, "점프 Suspend·Resume이 끝까지 간다")
        assertTrue(k.host.state().now >= k.now, "Step이 이어졌다")
    }

    // 재개 때 Room 대조(store.profile()·methods())가 던져도 Resume은 반드시 들어간다. 테이블을 지워 읽기가 실패하게 한다
    private fun breakStoreReads() = k.db.openHelper.writableDatabase.execSQL("DROP TABLE child_profile")

    @Test fun reconcileFailureOnResume_doesNotCutResume() {
        k.profile(); k.boot()
        k.host.suspend(); k.host.awaitIdle()
        assertNotNull(k.state().ign.suspendedAt)
        breakStoreReads()
        k.host.resume(); k.host.awaitIdle()
        assertNull(k.state().ign.suspendedAt, "대조가 던져도 Resume이 들어간다")
        k.ev(Tick(0), k.now + 1_000)
        assertTrue(k.host.state().now >= k.now, "다음 사건이 이어진다")
    }

    @Test fun reconcileFailureOnClockJump_doesNotCutResume() {
        k.profile(); k.boot()
        breakStoreReads()
        k.slept += 600_000
        k.ev(Tick(0), k.now + 600_000)
        assertNull(k.state().ign.suspendedAt, "대조가 던져도 점프 Resume이 들어간다")
        assertTrue(k.host.state().now >= k.now, "Step이 이어졌다")
    }

    // 복원 중 Room 읽기 예외는 프로세스를 끝낸다(상태 없이 started만 참인 채 멈추지 않는다). 다시 뜨면 스냅숏에서 정상 복원
    private fun sqlExec(q: String) = k.db.openHelper.writableDatabase.execSQL(q)

    @Test fun restoreFailureAtStart_diesThenNewHostRestores() {
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

    // 시작 길 전체가 fail-fast다(스냅숏·초기 상태·대기 리포트 읽기 예외도 프로세스를 끝낸다)
    private fun deathAt(q: String, undo: String): Throwable? {
        sqlExec(q)
        val died = catchingHostDeath { k.boot() }
        sqlExec(undo)
        return died
    }

    @Test fun snapshotReadFailureAtStart_dies() {
        k.profile(); k.boot(); k.open(); k.host.flush(); k.host.awaitIdle()
        assertNotNull(deathAt("ALTER TABLE engine_snapshot RENAME TO engine_snapshot_x", "ALTER TABLE engine_snapshot_x RENAME TO engine_snapshot"), "스냅숏 읽기 실패는 프로세스를 끝낸다")
        k.boot(); assertNotNull(k.ses(), "다시 뜨면 정상")
    }

    @Test fun initialStateReadFailureAtStart_dies() {
        k.profile()   // 스냅숏 없음: 초기 상태를 Room에서 만든다
        assertNotNull(deathAt("ALTER TABLE child_profile RENAME TO child_profile_x", "ALTER TABLE child_profile_x RENAME TO child_profile"), "초기 상태 읽기 실패는 프로세스를 끝낸다")
    }

    @Test fun pendingReportsFailure_doesNotKillHost() {
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

    @Test fun restoreWhilePlaying_treatedAsPlaybackError() {
        k.profile(); k.boot(); k.open(); k.alarm()
        val pl = k.ses().play!!
        assertNotNull(pl.startedAt)
        k.ev(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 2)), k.now + 20_000)   // 20초에 바로 저장(마지막 스냅숏)
        k.running = null
        k.now += 20_000
        k.boot()   // 재생 중 프로세스가 죽었다. 멈춘 시각은 복원 시각(40초)이 아니라 마지막 스냅숏 시각(20초)
        val row = dao.plays(k.ses().id).single { it.id == pl.id }
        assertEquals("ERROR", row.endReason)
        assertEquals(20_000, row.playedMs)
        assertEquals(20_000, k.ses().playedMs)
        assertNull(k.ses().play)
        assertEquals(Phase.WAIT, k.ses().phase)
    }

    @Test fun restoreWithPlayAlreadyLogged_noDoubleCount() {
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

    @Test fun restoreInGapOrConfirm_pausesAndRestartsWindow() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        assertEquals(Phase.GAP, k.ses().phase)
        assertNotNull(k.ses().windowEnd)
        k.running = null
        k.now += 2_000
        k.boot()
        assertTrue(PauseReason.MIC_ERROR in k.state().car.pauses)
        assertNull(k.ses().judgeFrom); assertNull(k.ses().windowEnd)
        assertTrue(PauseReason.MIC_ERROR in k.pending, "새 마이크 서비스가 이어받는다")
        k.ev(InputRestored(0, PauseReason.MIC_ERROR), k.now + 1_000)
        assertEquals(k.now + Params().gapMs, k.ses().windowEnd, "입력이 돌아온 때부터 8초를 다시 잰다")
    }

    @Test fun restoreInConfirm_pausesAndRestartsWindow() {
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

    @Test fun restoreAfterEndedAtBeforeReport_rebuildsReportOnly() {
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

    @Test fun restoreKeepsRoomFirstResult() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        val ep = k.ses().episode!!
        assertNull(ep.first!!.result)
        sql("UPDATE episode SET first_result = 'FAIL' WHERE id = '${ep.id}'")   // SaveEpisode가 스냅숏보다 먼저 커밋됐다
        k.running = null
        k.boot()
        assertEquals(TryResult.FAIL, k.ses().episode!!.first!!.result, "GAP 복원의 판정 멈춤이 미판정으로 바꾸지 않는다")
    }

    @Test fun restoreEndedEpisode_toListening() {
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

    @Test fun restoreRoomEndedEpisode_pendingHandoff_droppedLineSavesToldDriverFalse() {
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

    @Test fun restoreRoomToldDriverWins_noReask() {
        k.phone("guardian", alerts = true)   // 알림을 켠 보호자: 대기 인계가 남아 있으면 복원 뒤 알림이 나간다
        val (id, _) = snapshotWithHandoffLine()
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

    @Test fun restoreToldTrue_longDriveLine_marksOffered() {
        val (id, _) = snapshotWithHandoffLine(mk = { Line(Sid.S10, emptyMap(), null, longDrive = true, queuedAt = it) })
        sql("UPDATE episode SET handoff_told_driver = 1 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertTrue(k.ses().longDriveOffered, "말한 장시간 제안은 줄만 빼지 않고 longDriveOffered를 세운다")
        assertNotNull(k.ses().spokenAt[Sid.S10])
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S10 })
    }

    @Test fun restoreToldFalse_dropsLine_forgetsOffer() {
        val (id, _) = snapshotWithHandoffLine()   // 큐에 든 줄은 금지 시각을 남기지 않는다
        sql("UPDATE episode SET handoff_told_driver = 0 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.ses().spokenAt[Sid.S07], "듣지 못한 제안은 금지를 남기지 않는다")
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 })
    }

    @Test fun restoreToldTrue_otherSessionLine_keepsState() {
        val (id, _) = snapshotWithHandoffLine(mk = { Line(Sid.S10, emptyMap(), null, longDrive = true, queuedAt = it, sessionId = "other") })
        sql("UPDATE episode SET handoff_told_driver = 1 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertEquals(false, k.ses().longDriveOffered, "다른 세션 줄은 지금 세션 상태를 바꾸지 않는다")
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S10 })
    }

    @Test fun restoreRoomToldDriverNull_keepsPending() {
        snapshotWithHandoffLine()
        k.running = null
        k.boot()
        assertNotNull(k.state().openLedger(), "Room 값이 null이면 지금처럼 대기")
    }

    @Test fun restoreRoomEndedEpisode_toldDriverWins_closedRowKept() {
        val (id, _) = snapshotWithHandoffLine()
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소', first_result = 'FAIL', ended_at = ${k.now} WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.state().openLedger()); assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 })
        assertEquals(1, toldDriverRow(id), "닫힌 행의 true를 false로 덮지 않는다")
    }

    @Test fun restoreSpeakingFrontLine_handledOnce() {
        val other = Line(Sid.S08, emptyMap(), null, queuedAt = 1)
        val (id, _) = snapshotWithHandoffLine(speaking = "u1", extra = listOf(other))
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소' WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.state().openLedger())
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 }, "말하던 줄은 한 번만 버린다")
        assertEquals(1, toldDriverRow(id))
    }

    @Test fun roomFalseHeadLine_markDropped_nextLineKept() {
        val other = Line(Sid.S08, emptyMap(), null, queuedAt = 1)
        val (id, _) = snapshotWithHandoffLine(speaking = "u1", extra = listOf(other))
        sql("UPDATE episode SET handoff_told_driver = 0 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.state().speech.speaking); assertNull(k.state().openLedger())
        assertEquals(listOf(Sid.S08), k.state().speech.queue.map { it.sid }, "맨 앞 줄만 빼고 다음 줄은 남긴다")
        assertNull(k.ses().spokenAt[Sid.S07], "듣지 못한 제안은 금지를 남기지 않는다")
        assertEquals(0, toldDriverRow(id), "Room의 false를 말한 것으로 덮지 않는다")
    }

    @Test fun roomTrueHeadLine_markSpoken_nextLineKept() {
        val other = Line(Sid.S08, emptyMap(), null, queuedAt = 1)
        val (id, _) = snapshotWithHandoffLine(speaking = "u1", extra = listOf(other), mk = { Line(Sid.S10, emptyMap(), null, longDrive = true, queuedAt = it) })
        sql("UPDATE episode SET handoff_told_driver = 1 WHERE id = '$id'")
        k.running = null
        k.boot()
        assertNull(k.state().speech.speaking)
        assertEquals(listOf(Sid.S08), k.state().speech.queue.map { it.sid }, "다음 줄을 버리지 않는다")
        assertTrue(k.ses().longDriveOffered, "Room 참이면 말한 것과 같은 상태 변화")
    }

    @Test fun restoreRoomToldTrue_closedHandoffDropped_noDuplicateAlert() {
        // 세션이 닫힌 뒤 인계 발화가 성공해 Room에 true를 쓰고 스냅숏 전에 죽었다(스냅숏은 session == null, closedHandoff 있음)
        val (id, _) = snapshotWithHandoffLine()
        val s = k.state(); val sid = s.session!!.id
        val closed = HandoffLedger(sid, id, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, utteranceId = "u1", restName = "n 휴게소")
        k.store.saveSnapshot(s.copy(session = null, handoffs = listOf(closed),
            speech = s.speech.copy(speaking = "u1", queue = listOf(Line(Sid.S07, emptyMap(), "n", queuedAt = k.now, sessionId = sid, handoff = true)))))
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소' WHERE id = '$id'")
        k.running = null; k.exec.clear(); k.link.clear()
        k.boot()
        assertTrue(k.state().handoffs.isEmpty(), "Room 값이 있으니 닫힌 묶음을 지운다")
        k.ev(Tick(0), k.now + 1_000)
        assertTrue(k.link.none { it is SendToPhone && it.type == "handoff_alert" }, "이름 없는 알림을 또 보내지 않는다")
        assertEquals(1, toldDriverRow(id), "true를 false로 덮지 않는다")
    }

    @Test fun restoreClosedHandoff_reportBuiltAfterToldDriverFalseRecorded() {   // 흐름 시험: 복원의 flush가 told_driver=false를 적은 뒤에 리포트가 만들어진다
        k.phone()
        val (id, _) = snapshotWithHandoffLine()
        val s = k.state(); val sid = s.session!!.id
        val closed = HandoffLedger(sid, id, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, utteranceId = "u1")
        k.store.saveSnapshot(s.copy(session = null, handoffs = listOf(closed),
            speech = s.speech.copy(speaking = "u1", queue = listOf(Line(Sid.S07, emptyMap(), "n", queuedAt = k.now, sessionId = sid, handoff = true)))))
        sql("UPDATE session SET ended_at = ${k.now}, report_pending = 1 WHERE id = '$sid'")   // Room 값(handoff_told_driver)은 없으니 flush가 정리한다
        k.running = null; k.exec.clear(); k.link.clear()
        k.boot()
        val body = k.db.openHelper.readableDatabase.query("SELECT body_json FROM outbox WHERE type = 'report_summary'").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(1, body.size, body.toString()); assertTrue("\"told_driver\":false" in body[0], "엔진 flush가 told_driver를 적은 뒤에 리포트를 만든다: " + body[0])
    }

    @Test fun closedHandoffChange_savedImmediately() {
        k.profile(); k.boot(); k.open()
        val old = k.state()
        val closed = HandoffLedger("closed-session", null, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, utteranceId = "u1")
        val withClosed = old.copy(handoffs = listOf(closed))
        assertTrue(k.host.needsSnapshot(old, Step(withClosed, emptyList(), null)), "닫힌 묶음을 두는 Step")
        assertTrue(k.host.needsSnapshot(withClosed, Step(old, emptyList(), null)), "정리해 지우는 Step")
    }

    @Test fun restoreRoomEndedSession_dropsPendingHandoffLine_keepsOthers() {
        val other = Line(Sid.S12, emptyMap(), null, queuedAt = 1)   // 세션과 무관한 문장
        val offer = Line(Sid.S08, emptyMap(), null, queuedAt = 1)   // 일반 제안도 closeSession처럼 뺀다
        val (id, _) = snapshotWithHandoffLine(extra = listOf(offer, other))
        sql("UPDATE session SET ended_at = ${k.now} WHERE id = '${k.ses().id}'")
        k.running = null; k.exec.clear(); k.link.clear()
        k.boot()
        assertNull(k.state().session)
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S07 }, "묶인 줄을 지운다")
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S08 }, "일반 제안 줄도 closeSession처럼 뺀다")
        assertTrue(k.state().speech.queue.any { it.sid == Sid.S12 }, "세션과 무관한 줄은 남는다")
        k.ev(GearChanged(0, Gear.P), k.now + 100)   // 닫힌 묶음은 첫 Step의 flush가 알림으로 낸다
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
        assertTrue(PauseReason.SILENCED in k.pending, "풀리지 않은 사유 전부를 마이크 서비스에 넘긴다")
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

    @Test fun restoreDropsSpeakingLine() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val line = Line(Sid.S07, mapOf("restName" to "n 휴게소"), "n")
        k.store.saveSnapshot(s.copy(
            speech = s.speech.copy(speaking = "u1", queue = listOf(line)),
            session = s.session!!.let { it.copy(offers = it.offers.copy(spokenAt = mapOf(Sid.S07 to k.now))) },
        ))
        k.boot()
        assertTrue(k.state().speech.queue.isEmpty(), "말하던 줄은 다시 말하지 않는다")
        assertEquals(k.now, k.ses().spokenAt[Sid.S07], "10분 금지 시각은 남는다")
        // 말하던 줄이 없었으면(speaking == null) 큐는 그대로
        val s2 = k.state()
        k.store.saveSnapshot(s2.copy(speech = s2.speech.copy(speaking = null, queue = listOf(line))))
        k.boot()
        assertTrue(k.state().speech.queue.isNotEmpty() || k.exec.any { it is Ask }, "speaking이 없으면 줄을 버리지 않는다")
    }

    @Test fun restoreDroppedLongDrive_reofferedAfterRoute() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val line = Line(Sid.S11, mapOf("restName" to "n 휴게소"), "n", longDrive = true)
        k.store.saveSnapshot(s.copy(
            speech = s.speech.copy(speaking = "u1", queue = listOf(line)),
            session = s.session!!.let { it.copy(offers = it.offers.copy(pendingAsk = PendingAsk("a1", Sid.S11, "n", null, true), driveFrom = k.now - 7_300_000)) },
        ))
        k.boot()
        assertEquals(false, k.ses().longDriveOffered, "버린 장시간 제안은 말하지 않은 것으로 친다")
        assertNull(k.ses().spokenAt[Sid.S11])
        assertTrue(k.exec.none { it is Listen }, "듣기는 열지 않는다")
        val before = k.exec.count { it is Ask && it.sid == Sid.S11 }
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        k.ev(Tick(0), k.now + Params().speechGapMs + 1_000)   // 복원이 말하던 줄을 버린 시각부터 말 사이 간격
        assertEquals(before + 1, k.exec.count { it is Ask && it.sid == Sid.S11 }, "새 경로로 휴식 안내를 한 번 다시 낸다")
    }

    private val rest10 = RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 10.0, true)), 150.0)

    /** 복원 큐에 말하지 않은 장시간 제안이 있다. 상태는 큐에 S10(longDrive)과 호출자가 고른 줄을 둔 채 복원한다. */
    private fun restoreWithQueued(vararg lines: Line) {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        k.store.saveSnapshot(s.copy(speech = s.speech.copy(queue = lines.toList()),
            session = s.session!!.let { it.copy(offers = it.offers.copy(driveFrom = k.now - 7_300_000)) }, car = s.car.copy(gear = Gear.P)))
        k.boot()
    }
    private val s10 = Line(Sid.S10, emptyMap(), null, longDrive = true)

    @Test fun restoreQueuedLongDrive_notSpokenBeforeRoute_thenJudgedByNewRoute() {
        restoreWithQueued(s10)
        assertTrue(k.state().speech.queue.none { it.longDrive }, "말하지 않은 장시간 줄은 복원 ③에서 뺀다")
        assertEquals(false, k.ses().longDriveOffered)
        k.ev(GearChanged(0, Gear.D), k.now + 5_000)
        assertTrue(k.exec.none { it is Speak && it.sid == Sid.S10 }, "경로 응답 전에 옛 쉼터 없음 문장을 말하지 않는다")
        k.ev(rest10, k.now + 1_000)
        assertTrue(k.exec.none { it is Speak && it.sid == Sid.S10 }, "10km 앞 휴게소가 있으면 쉼터 없음이 아니다")
        assertTrue(k.exec.any { (it is Ask && it.sid == Sid.S11) || (it is Speak && it.sid == Sid.S11) }, "새 경로로 S11을 판단한다")
    }

    @Test fun restoreQueuedLongDrive_noRouteFor60s_judgedWithoutRoute() {
        restoreWithQueued(s10)
        k.ev(GearChanged(0, Gear.D), k.now + 5_000)
        k.ev(Tick(0), k.now + Params().routeWaitMs + 1_000)
        assertTrue(k.exec.any { it is Speak && it.sid == Sid.S10 }, "60초 뒤 경로 없이 판단해 말한다")
    }

    @Test fun restoreQueued_nonLongDriveLineKept() {
        val keep = Line(Sid.S06, emptyMap(), null)
        restoreWithQueued(keep, s10)
        assertEquals(listOf(keep.sid), k.state().speech.queue.map { it.sid }, "장시간이 아닌 줄은 그대로")
    }

    @Test fun restoreSpeakingLongDrive_notMarkedOffered_andQueuedLongDriveDropped() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val speaking = Line(Sid.S10, emptyMap(), null, longDrive = true)
        val queued = Line(Sid.S11, mapOf("restName" to "n 휴게소"), "n", longDrive = true)
        k.store.saveSnapshot(s.copy(speech = s.speech.copy(speaking = "u1", queue = listOf(speaking, queued)),
            session = s.session!!.let { it.copy(offers = it.offers.copy(driveFrom = k.now - 7_300_000)) }))
        k.boot()
        assertEquals(false, k.ses().longDriveOffered, "말하던 줄은 말하지 않은 것으로 버린다(휴식 안내가 다시 난다)")
        assertTrue(k.state().speech.queue.isEmpty())
    }

    @Test fun restoreClearsRoute() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        val route = RouteView(k.now, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0)
        k.store.saveSnapshot(s.copy(car = s.car.copy(route = route, gear = Gear.P)))
        k.boot()
        assertNull(k.state().car.route, "옛 경로로 판단하지 않는다")
        k.ev(GearChanged(0, Gear.D), k.now + 1_000)
        assertEquals(false, k.ses().departChecked, "내비 응답 전에는 출발 제안을 판단하지 않는다")
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        assertTrue(k.ses().departChecked)
    }

    @Test fun pendingHandoffAlertChange_savedImmediately() {
        k.profile(); k.boot(); k.open()
        val old = k.state()
        val alert = HandoffLedger(old.session!!.id, null, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null)
        val withAlert = old.copy(handoffs = listOf(alert))
        assertTrue(k.host.needsSnapshot(old, Step(withAlert, emptyList(), null)), "두는 Step")
        assertTrue(k.host.needsSnapshot(withAlert, Step(old, emptyList(), null)), "보내 지우는 Step")
    }

    @Test fun speakOrAskSavedImmediately() {
        k.profile(); k.boot(); k.open()
        val s = k.state()
        for (c in listOf<Command>(Speak("u1", Sid.S06, emptyMap()), Ask("u2", Sid.S07, emptyMap(), 5_000)))
            assertTrue(k.host.needsSnapshot(s, Step(s, listOf(c), null)), "$c")
    }

    @Test fun restoreMicNotRunning_inputLostAndNotice() {
        k.profile(); k.boot(); k.open()
        assertTrue(k.state().car.micRunning)
        k.running = null
        k.boot()
        assertEquals(false, k.state().car.micRunning)
        assertTrue(PauseReason.MIC_ERROR in k.state().car.pauses)
        assertTrue(Notice.MIC_STOPPED !in k.notices, "복원 기어가 D(주행 중)면 띄우지 않는다")
        k.ev(GearChanged(0, Gear.P))
        assertTrue(Notice.MIC_STOPPED in k.notices, "다음 P에서 1i가 띄운다")
    }

    @Test fun restoreMicAlreadyRunning_reconcilesBothWays() {
        k.profile(); k.boot(); k.open()
        k.ev(InputLost(0, PauseReason.SILENCED))
        k.running = setOf(PauseReason.DETECTOR_TIMEOUT)   // 서비스가 살아 있다(엔진이 다시 붙음)
        k.pending = emptySet()
        k.boot()
        assertEquals(listOf(setOf(PauseReason.SILENCED)), k.assumed, "엔진에만 있는 사유 → assumePaused")
        assertTrue(PauseReason.DETECTOR_TIMEOUT in k.state().car.pauses, "감지기에만 있는 사유 → InputLost")
        assertTrue(k.pending.isEmpty(), "돌고 있으면 pendingPauses를 쓰지 않는다")
    }

    @Test fun serviceStartsRightAfterRunningPausesNull_reasonNotLost() {
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
        sql("INSERT INTO song (id, name, received_at, format, duration_s, lufs, normalized_path, status, reject_reason, added_seq, last_seq) " +
            "VALUES ('s1', '자장자장', $T0, 'AAC', 60.0, -30.0, NULL, 'ACTIVE', NULL, 3, 3)")
        k.host.methodsMaybeChanged(); k.host.awaitIdle()
        assertEquals(Method(MethodKind.SONG, "s1"), k.state().methodsNext.last())
    }

    // ---- 복원: 조향 창·마이크·사건 순서·Room 기록 ----

    @Test fun restoreDropsOpenSteerWindow_doesNotOverwriteCommittedRow() {
        k.profile(); k.boot(); k.open()
        k.ev(VehicleSample(0, 50.0, false, 1.0, TurnSignal.NONE), k.now + 1_000)
        k.host.flush(); k.host.awaitIdle()
        val w = k.store.loadSnapshot()!!.steer.window
        assertNotNull(w, "스냅숏에 진행 중 조향 창이 있다(전제)")
        val sid = k.ses().id
        // 그 창은 이미 닫혀 커밋됐는데 스냅숏만 뒤처졌다
        dao.upsertSteer(SteerWindowEntity(sid, w.startAt, 2, false, false, false, 9))
        k.boot()
        assertNull(k.state().steer.window, "복원 뒤 진행 중 창은 버려진다")
        k.ev(VehicleSample(0, 50.0, false, 2.0, TurnSignal.NONE), w.startAt + 61_000)
        assertEquals(9, dao.steerWindows(sid).single { it.startAt == w.startAt }.reversals, "완성 행을 옛 부분 집계로 덮지 않는다")
    }

    @Test fun scenarioEndWithLiveMic_gapWindowRestartsWithoutMicStopped() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        assertEquals(Phase.GAP, k.ses().phase)
        assertEquals(emptySet(), k.running, "마이크 서비스가 살아 있다(전제)")
        k.state().speech.speaking?.let { k.host.postReal(SpeechDone(0, it, true)); k.host.awaitIdle() }   // 말하는 중에는 장면이 시작하지 않는다
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.now += 5_000
        k.host.endScenario(); k.host.awaitIdle()
        // 장면 시작이 실제 판정을 Suspend처럼 멈춘다(WAIT, 1차 미정 SUSPEND).
        // 장면 동안 실제 울음을 받지 않았으니 장면 뒤 울음으로 그 1차를 판정하지 않는다. 마이크가 산 채 돌아오는 것은 그대로다
        assertEquals(Phase.WAIT, k.ses().phase)
        assertEquals(Unjudged.SUSPEND, k.ses().episode!!.first!!.unjudged)
        assertTrue(k.state().car.micRunning)
        assertTrue(Notice.MIC_STOPPED !in k.notices)
        assertTrue(k.state().car.pauses.isEmpty())
    }

    // 판정 창 다시 재기는 마이크가 살아 있는 채 복원하는 길(프로세스 시작)에 남는다(위 시험이 장면 끝에서 보던 몫)
    @Test fun restoreWithLiveMic_gapWindowRestarts() {
        k.profile(); k.boot(); k.open(); k.alarm(); k.toGap()
        assertEquals(emptySet(), k.running, "마이크 서비스가 살아 있다(전제)")
        k.now += 5_000
        k.boot()
        assertEquals(Phase.GAP, k.ses().phase)
        assertTrue(Notice.MIC_STOPPED !in k.notices)
        assertEquals(k.now + Params().gapMs, k.ses().windowEnd, "판정 창을 지금부터 다시 잰다")
    }

    @Test fun urgentFromOtherThread_checksClockJump() {
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

    // ---- 닫힌 세션 복원이 말하던 인계 묶음을 잃지 않는다 ----
    @Test fun closedSessionRestore_pendingHandoffFlushedPlain_reportAfter() {
        val (id, _) = closedSessionWithHandoff()
        k.boot()
        assertNull(k.state().session); assertTrue(k.state().handoffs.isEmpty(), "첫 Step의 flush가 정리했다")
        val a = k.link.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
        assertEquals(1, a.size, "알림이 유실되지 않는다"); assertEquals("아기가 계속 울어요.", a.single().body["text"]!!.toString().trim('"'))
        assertEquals(0, toldDriverRow(id))
        val r = reportBodies(); assertEquals(1, r.size, "리포트는 한 번만: $r"); assertTrue("\"told_driver\":false" in r[0], r[0])
    }

    @Test fun closedSessionRestore_roomToldWins() {
        val (id, _) = closedSessionWithHandoff(toldInRoom = 1)
        k.boot()
        assertTrue(k.state().handoffs.isEmpty())
        assertTrue(k.link.none { it is SendToPhone && it.type == "handoff_alert" }, "Room 값이 있으면 알림을 또 보내지 않는다")
        assertEquals(1, toldDriverRow(id), "true를 false로 덮지 않는다")
        assertEquals(1, reportBodies().size)
    }

    // ---- Room 값으로 말하던 맨 앞 줄을 뺄 때도 말하기 간격을 이어 간다 ----
    @Test fun roomRemovesSpeakingHead_keepsSpeechGap() {
        val (id, _) = snapshotWithHandoffLine(speaking = "u1", extra = listOf(Line(Sid.S12, emptyMap(), null, queuedAt = 1)))
        sql("UPDATE episode SET handoff_told_driver = 1, handoff_rest_name = 'n 휴게소' WHERE id = '$id'")
        k.running = null; k.exec.clear()
        k.boot()
        assertEquals(k.now, k.state().speech.lastDoneAt)
        k.ev(Tick(0), k.now + 3_000)
        assertTrue(k.exec.none { it is Speak }, "8초 안에 다음 안내가 나가지 않는다")
        k.ev(Tick(0), k.now + 6_000)
        assertTrue(k.exec.any { it is Speak && it.sid == Sid.S12 }, "간격 뒤에는 나간다")
    }

    private fun Kit.hush() { state().speech.speaking?.let { ev(SpeechDone(0, it, false)) } }
    /** GAP → CONFIRM → 성공(에피소드 닫힘). */
    private fun Kit.quietClose() = repeat(2) { val we = ses().windowEnd!!; ev(CrySegment(0, we - 1_000, we + 2_000, 0.1, false), we + 2_000) }
    private fun Kit.alarmEpisode() { alarm(); hush(); toGap(); quietClose(); check(ses().episode == null) }

    @Test fun cryFactCountFromEngine_noRoomRead() {
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

    @Test fun restore_alarmCountMatchesRoom() {
        k.profile(); k.boot(); k.open()
        repeat(2) { k.alarmEpisode() }
        val s = k.store.loadSnapshot()!!
        k.store.saveSnapshot(s.copy(session = s.session!!.copy(cryRuns = 0)))   // 칸이 없던 옛 스냅숏(기본값 0)
        k.boot()
        assertEquals(2, k.ses().cryRuns, "옛 스냅숏은 Room 경보 에피소드 수(하한)로 맞춘다")
    }

    @Test fun clearRecords_zero_thenRestoreKeepsLaterCount() {
        k.profile(); k.boot(); k.open()
        k.alarmEpisode()
        k.ev(ClearRecords(0), k.now + 1_000)
        assertEquals(0, k.ses().cryRuns, "기록을 지우면 0")
        k.alarmEpisode()
        assertEquals(1, k.ses().cryRuns)
        k.boot()
        assertEquals(1, k.ses().cryRuns, "복원은 스냅숏 값(지운 세션은 Room에 행이 없다)")
    }

    /** 장면에서도 소리를 직접 튼 에피소드 중 경보가 이어지면 질문 사실 답은 울고 있다고 한다(호스트 큐 composeFacts 길). */
    @Test fun scene_switchSoundThenAlarms_cryFactSaysCrying() {
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
        assertEquals("지금 1분째 울고 있고, 이번 주행에서 1번 울었어요.", line, "지금 울음과 같은 기준으로 센다")
    }

    @Test fun sceneResumeReconcile_doesNotOverwriteSceneProfile() {
        k.profile(); k.boot()
        k.host.startScenario(setup()); k.host.awaitIdle()
        val sceneProfile = k.state().profile
        k.profile(interval = 120)   // 실제 Room 프로필이 장면 값과 다르다
        k.host.suspend(); k.host.resume(); k.host.awaitIdle()
        assertEquals(sceneProfile, k.state().profile, "장면 상태에 실제 프로필을 넣지 않는다")
    }

    @Test fun twoClosedHandoffsAtRestore_bothFlushedPlain() {
        // 스냅숏에 A의 닫힌 묶음이 있고, 열려 있던 B 세션이 Room에서 닫힌 채 복원된다
        val (idB, sidB) = closedSessionWithHandoff()
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

    // ---- Resume 전에 link 대조(Suspend 중 Room에만 든 수유)를 먼저 반영한다 ----
    @Test fun resumeReconcilesBeforeDepartCheck() {
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
}
