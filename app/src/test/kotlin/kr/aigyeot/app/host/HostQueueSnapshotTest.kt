package kr.aigyeot.app.host

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kr.aigyeot.app.store.dbPause
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.CabinTemp
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.ClearRecords
import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.Line
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.PendingAsk
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.PlaybackStarted
import kr.aigyeot.engine.RearDoor
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.RouteState
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Side
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.Tick
import kr.aigyeot.engine.TurnSignal
import kr.aigyeot.engine.VehicleSample
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 큐 처리·스냅숏 저장 시점·저장 실패.
@RunWith(RobolectricTestRunner::class)
class HostQueueSnapshotTest : HostTestBase() {
    // ---- 큐·at ----

    @Test fun atStampedOnPost_andMonotonic() {
        k.profile()
        k.boot()
        k.ev(RouteState(0, false, null, emptyList()), at = T0 + 1_000)
        assertEquals(T0 + 1_000, k.state().car.route!!.at) // 모듈이 넣은 at(0) 대신 호스트 시계
        k.ev(RouteState(0, false, null, emptyList()), at = T0 + 500) // 시계가 뒤로 가도
        assertEquals(T0 + 1_000, k.state().car.route!!.at)
    }

    @Test fun playbackStartedProcessedBeforeAlreadyQueuedEvent() {
        k.profile()
        k.boot()
        k.open()
        // 다른 모듈이 재생 시작 결과보다 먼저 큐에 넣은 사건(P). 시작 결과가 먼저 처리돼야 에피소드가 열린 뒤 WAIT로 간다
        k.onStart = { thread { k.host.post(GearChanged(0, Gear.P)) }.join() }
        k.alarm()
        val eps = dao.episodes(k.state().session!!.id)
        assertEquals(1, eps.size, "PlaybackStarted가 먼저 처리돼 에피소드가 열렸다")
        assertEquals("GEAR_P", eps[0].firstUnjudged)
        assertEquals(Phase.WAIT, k.ses().phase)
    }

    @Test fun playbackStartedFromOtherThreadProcessedBeforeAlreadyQueuedEvent() {
        k.profile()
        k.boot()
        k.open()
        k.autoStart = false
        k.onStart = { c ->
            thread { k.host.post(GearChanged(0, Gear.P)) }.join() // 먼저 큐에 들어간 사건
            thread { k.host.post(PlaybackStarted(0, c.playId, "bus100_audio_zone_1", false)) }.join() // 다른 스레드의 재생 시작 결과
        }
        k.alarm()
        val eps = dao.episodes(k.state().session!!.id)
        assertEquals(1, eps.size, "PlaybackStarted가 먼저 처리돼 에피소드가 열렸다")
        assertEquals("GEAR_P", eps[0].firstUnjudged)
        assertEquals(Phase.WAIT, k.ses().phase)
    }

    // ---- 스냅숏 저장 시점 ----

    @Test fun immediateSave_whenSessionOpens() {
        k.profile()
        k.boot()
        k.now += 1_000
        k.open()
        val snap = k.store.loadSnapshot()!!
        assertEquals(k.state().session!!.id, snap.session?.id)
    }

    @Test fun departCheckedSavedImmediately() {
        k.profile()
        k.boot()
        k.open()
        val s0 = k.savedAt()
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        assertTrue(k.ses().departChecked) // 수유 시작이 없어 제안은 없고 확인만 됨
        assertNotEquals(s0, k.savedAt(), "30초를 기다리지 않고 바로 저장")
        assertTrue(k.store.loadSnapshot()!!.session!!.departChecked)
    }

    @Test fun careSummarySavedImmediately_andKeptByClearRecords() {
        k.profile()
        k.boot()
        k.open()
        val s0 = k.savedAt()
        val c = CareView(k.now, true, null, null, null, null, null, null, null, 2)
        k.ev(CareSummary(0, c), k.now + 1_000)
        assertEquals(c, k.state().care)
        assertNotEquals(s0, k.savedAt(), "30초를 기다리지 않고 바로 저장")
        assertEquals(c, k.store.loadSnapshot()!!.care)
        k.ev(CareSummary(0, c.copy(asOf = c.asOf - 1)), k.now + 1_000) // 더 이른 것은 무시: 상태 그대로
        assertEquals(c, k.state().care)
        k.ev(ClearRecords(0), k.now + 1_000)
        assertEquals(c, k.state().care)
        assertEquals(c, k.store.loadSnapshot()!!.care, "지워도 돌봄 요약은 남는다")
    }

    @Test fun vehicleSampleBatched_every30s() {
        k.profile()
        k.boot()
        k.open()
        val s0 = k.savedAt()
        k.ev(VehicleSample(0, 50.0, false, 1.0, TurnSignal.NONE), k.now + 1_000)
        assertNotEquals(k.state(), k.store.loadSnapshot(), "상태는 바뀌었다(조향 누적)")
        assertEquals(s0, k.savedAt(), "30초 안에는 묶는다")
        k.ev(VehicleSample(0, 50.0, false, 2.0, TurnSignal.NONE), k.now + Params().snapshotIntervalMs)
        assertEquals(k.now, k.savedAt())
        assertEquals(k.state(), k.store.loadSnapshot())
    }

    @Test fun skippedChangeSavedByLaterTick() {
        k.profile()
        k.boot()
        k.host.flush()
        k.host.awaitIdle()
        val j0 = k.snapshotJson()
        k.ev(RearDoor(0, Side.LEFT, true), k.now + 1_000)
        assertTrue(k.state().ign.doorOpenedSinceP, "묶음 저장이 건너뛴 변경")
        assertEquals(j0, k.snapshotJson(), "30초 안에는 저장하지 않는다")
        k.ev(Tick(0), k.now + Params().snapshotIntervalMs)
        assertTrue(k.store.loadSnapshot()!!.ign.doorOpenedSinceP, "Tick만 와도 건너뛴 변경을 저장한다")
    }

    @Test fun tickOnlyNowChange_notSaved() {
        k.profile()
        k.boot()
        val s0 = k.savedAt()
        k.ev(Tick(0), k.now + 60_000)
        assertEquals(s0, k.savedAt())
    }

    @Test fun batchInterval_fromParams() {
        k.profile()
        k.boot(Params(snapshotIntervalMs = 5_000))
        k.open()
        val s0 = k.savedAt()
        k.ev(VehicleSample(0, 50.0, false, 1.0, TurnSignal.NONE), k.now + 4_000)
        assertEquals(s0, k.savedAt(), "Params 간격(5초) 안에는 묶는다")
        k.ev(VehicleSample(0, 50.0, false, 2.0, TurnSignal.NONE), k.now + 1_000)
        assertEquals(k.now, k.savedAt(), "Params.snapshotIntervalMs가 지나면 저장(상수 30초가 아니다)")
    }

    // ---- Step 흐름(손실 없음) ----

    @Test fun stepLog_slowCollector_getsEveryStepInOrder() {
        k.profile()
        k.boot()
        val got = CopyOnWriteArrayList<Double?>()
        val scope = collect(Dispatchers.Default) {
            Thread.sleep(2)
            got += it.state.car.cabinC
        } // 느린 수집자
        repeat(200) { k.host.post(CabinTemp(0, it.toDouble())) }
        k.host.awaitIdle()
        val until = System.currentTimeMillis() + 10_000
        while (got.size < 200 && System.currentTimeMillis() < until) Thread.sleep(10)
        scope.cancel()
        assertEquals((0 until 200).map { it.toDouble() }, got.toList(), "중간 Step을 건너뛰지 않고 순서대로")
    }

    // ---- 한 Step의 store 쓰기와 그 Step의 스냅숏을 한 트랜잭션으로 ----
    private fun failSnapshotWrite() = sql("CREATE TRIGGER fail_snap BEFORE UPDATE ON engine_snapshot BEGIN SELECT RAISE(ABORT, '스냅숏 실패'); END")

    /** 2차 GAP까지 보낸 뒤 재울음 직전 상태. 다음 울음이 SaveEpisode(secondResult = FAIL, TWO_ATTEMPTS)를 낸다. */
    private fun toSecondGap() {
        k.profile()
        k.boot()
        k.open()
        k.alarm()
        k.toGap()
        k.ev(CrySegment(0, k.now + 5_000, k.now + 8_000, 0.9, true), k.now + 8_000)
        k.ev(Tick(0), k.ses().play?.endAt ?: error("2차 재생이 없다: ${k.ses().phase}"))
        assertEquals(Phase.GAP, k.ses().phase)
        assertEquals(2, k.ses().attemptNo)
    }
    private fun secondResultRow(): String? = k.db.openHelper.readableDatabase.query("SELECT second_result FROM episode").use {
        if (it.moveToFirst() &&
            !it.isNull(0)
        ) {
            it.getString(0)
        } else {
            null
        }
    }

    @Test fun contextCaptureFailure_keepsEpisodeAndHost() {
        k.profile()
        k.boot()
        k.open()
        k.ev(VehicleSample(0, 50.0, false, 1.0, TurnSignal.NONE), k.now + 1_000) // 지난 초 줄이 있어야 첫 경보의 맥락 쓰기가 일어난다
        k.ev(VehicleSample(0, 50.0, false, 1.0, TurnSignal.NONE), k.now + 1_000)
        sql("CREATE TRIGGER fail_ctx BEFORE INSERT ON context_sample BEGIN SELECT RAISE(ABORT, '맥락 실패'); END")
        val died = catchingHostDeath { k.alarm() }
        assertNull(died, "맥락 기록 실패는 프로세스를 끝내지 않는다")
        val n = k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM episode").use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(1, n, "에피소드 행은 저장된다")
    }

    @Test fun reportBuildFailure_keepsSessionEndAndHost() {
        k.profile()
        k.phone()
        k.boot()
        k.open()
        val id = k.ses().id
        sql("CREATE TRIGGER fail_report BEFORE INSERT ON outbox WHEN NEW.type = 'report_summary' BEGIN SELECT RAISE(ABORT, '리포트 실패'); END")
        val died = catchingHostDeath { k.ev(GearChanged(0, Gear.P), k.now + 60_000).ev(RearDoor(0, Side.LEFT, true)) }
        assertNull(died, "리포트 만들기 실패는 프로세스를 끝내지 않는다")
        assertNotNull(dao.session(id)!!.endedAt, "세션 종료는 저장된다")
        assertEquals(true, dao.session(id)!!.reportPending, "report_pending이 남아 다음 시작에 재개된다")
    }

    @Test fun stepStoreWritesAndSnapshotAtomic() {
        toSecondGap()
        val before = k.snapshotJson()
        failSnapshotWrite()
        val died = catchingHostDeath { k.ev(CrySegment(0, k.now + 5_000, k.now + 8_000, 0.9, true), k.now + 8_000) }
        assertNotNull(died, "묶음 안 실패는 프로세스를 끝낸다")
        assertNull(secondResultRow(), "스냅숏이 실패하면 에피소드 쓰기도 되돌려진다")
        assertEquals(before, k.snapshotJson(), "스냅숏은 옛 것 그대로")
    }

    // 재시도 사이(dbPause)에 실패 원인을 걷어낸다. 재시도가 한 번 일어났는지 센다
    private fun <T> healOnPause(heal: () -> Unit, block: () -> T): T {
        var pauses = 0
        val orig = dbPause
        dbPause = {
            pauses++
            heal()
        }
        try {
            return block()
        } finally {
            dbPause = orig
            assertEquals(1, pauses, "재시도가 한 번 일어났다")
        }
    }

    // 묶음 트랜잭션의 첫 시도만 실패하면 다시 해 커밋하고 프로세스를 끝내지 않는다
    @Test fun tiedTxRetriesOnceThenCommits() {
        toSecondGap()
        failSnapshotWrite()
        val died = healOnPause({ sql("DROP TRIGGER fail_snap") }) {
            catchingHostDeath {
                k.ev(
                    CrySegment(0, k.now + 5_000, k.now + 8_000, 0.9, true),
                    k.now + 8_000
                )
            }
        }
        assertNull(died, "한 번 실패는 다시 해서 커밋한다")
        assertEquals("FAIL", secondResultRow(), "에피소드 쓰기가 들어갔다")
        assertEquals(k.state().session?.episode?.handoff, k.store.loadSnapshot()!!.session?.episode?.handoff, "같은 Step의 스냅숏도 함께 들어갔다")
    }

    // 부모 노래 후보 다시 계산의 읽기가 한 번 실패해도 로그 없이 사라지지 않고 다시 읽어 반영한다
    @Test fun methodsMaybeChanged_readFailsOnceThenApplies() {
        k.profile()
        k.boot()
        sql("UPDATE child_profile SET methods = '[{\"kind\":\"WHITE_NOISE\"},{\"kind\":\"LULLABY\"},{\"kind\":\"SONG\"}]'")
        sql(
            "INSERT INTO song (id, name, received_at, format, duration_s, lufs, normalized_path, status, reject_reason, added_seq, last_seq) " +
                "VALUES ('s1', '자장자장', $T0, 'AAC', 60.0, -30.0, NULL, 'ACTIVE', NULL, 3, 3)"
        )
        sql("ALTER TABLE song RENAME TO song_x") // 첫 읽기가 실패한다
        healOnPause({ sql("ALTER TABLE song_x RENAME TO song") }) {
            k.host.methodsMaybeChanged()
            k.host.awaitIdle()
        }
        assertEquals(Method(MethodKind.SONG, "s1"), k.state().methodsNext.last())
    }

    // 다 실패해도 호스트는 이어 간다(부모 노래 후보는 옛 값, 다음 계기가 다시 한다)
    @Test fun methodsMaybeChanged_allFail_hostContinues() {
        k.profile()
        k.boot()
        val before = k.state().methodsNext
        sql("UPDATE child_profile SET methods = '[{\"kind\":\"WHITE_NOISE\"},{\"kind\":\"LULLABY\"},{\"kind\":\"SONG\"}]'") // 곡 표를 읽게 한다
        sql("ALTER TABLE song RENAME TO song_x")
        val died = catchingHostDeath {
            k.host.methodsMaybeChanged()
            k.host.awaitIdle()
        }
        sql("ALTER TABLE song_x RENAME TO song")
        assertNull(died)
        assertEquals(before, k.state().methodsNext)
        k.ev(Tick(0), k.now + 1_000)
        assertTrue(k.host.state().now >= k.now, "다음 사건이 이어진다")
    }

    @Test fun stepStoreWritesAndSnapshot_bothCommitted() {
        toSecondGap()
        k.ev(CrySegment(0, k.now + 5_000, k.now + 8_000, 0.9, true), k.now + 8_000)
        assertEquals("FAIL", secondResultRow())
        val snap = k.store.loadSnapshot()!!
        assertEquals(k.state().session?.episode?.handoff, snap.session?.episode?.handoff, "같은 Step의 스냅숏이 함께 들어갔다")
        assertNotNull(snap.session?.episode?.handoff)
        assertNotNull(snap.openLedger(), "인계 진입 Step의 스냅숏에 대기 알림이 Room 행과 함께 있다")
    }

    // ---- speaking의 바뀜(시작·끝)도 바로 저장 ----
    @Test fun speechDoneSavedImmediately_restoreKeepsRepeatBlock() {
        k.profile()
        k.boot()
        k.open()
        k.alarm()
        val u = k.exec.filterIsInstance<Speak>().single { it.sid == Sid.S02 }.utteranceId // 사건 없이 speaking만 바뀌는 평범한 안내
        assertEquals(u, k.store.loadSnapshot()!!.speech.speaking, "전제: 말하기 시작은 저장됐다")
        k.ev(SpeechDone(0, u, true), k.now + 5_000)
        assertNull(k.state().speech.speaking)
        val saved = k.store.loadSnapshot()!!.speech
        assertNull(saved.speaking, "말하기 끝도 바로 저장(죽으면 복원이 끝난 말을 말하던 줄로 보고 lastOffer를 지운다)")
        assertEquals(k.state().speech.lastDoneAt, saved.lastDoneAt)
    }

    // ---- 90일 정리는 묶음 밖에서. 정리 실패가 세션 종료를 되돌리지 않고, 묶음 안 쓰기 실패는 삼키지 않는다 ----
    @Test fun purgeFailureDoesNotRollbackStep() {
        k.profile()
        k.phone()
        k.boot()
        k.open()
        val id = k.ses().id
        sql("CREATE TRIGGER fail_purge BEFORE DELETE ON control_log BEGIN SELECT RAISE(ABORT, '정리 실패'); END")
        sql("INSERT INTO control_log(session_id, at, api, target, goal, ok, retries) VALUES ('$id', 1, 'a', 't', 'g', 1, 0)")
        k.ev(GearChanged(0, Gear.P), k.now + 60_000).ev(RearDoor(0, Side.LEFT, true))
        assertNull(k.state().session)
        assertNotNull(dao.session(id)!!.endedAt, "정리가 실패해도 세션 종료는 커밋된다")
        assertEquals(1, reportBodies().size, "리포트도 커밋된다")
    }

    @Test fun writeFailureInsideStepDies() {
        k.profile()
        k.phone()
        k.boot()
        k.open()
        val id = k.ses().id
        sql("CREATE TRIGGER fail_close BEFORE UPDATE ON session BEGIN SELECT RAISE(ABORT, '쓰기 실패'); END")
        val died = catchingHostDeath { k.ev(GearChanged(0, Gear.P), k.now + 60_000).ev(RearDoor(0, Side.LEFT, true)) }
        assertNotNull(died, "묶음 안 쓰기 실패는 프로세스를 끝낸다")
        assertNull(dao.session(id)!!.endedAt)
    }

    // ---- speaking을 바꾼 Step은 Speak·Ask를 실행기에 넘기기 전에 스냅숏을 저장한다 ----
    @Test fun snapshotSavedBeforeAskDispatched() {
        k.profile()
        k.boot()
        k.open()
        val s = k.state()
        val line = Line(Sid.S11, mapOf("restName" to "n 휴게소"), "n", longDrive = true)
        k.store.saveSnapshot(
            s.copy( // 스냅숏에는 말하지 않은 상태. 복원이 말하던 줄을 버린 뒤 새 경로로 S11 Ask를 낸다(HostRestoreTest.restoreDroppedLongDrive_reofferedAfterRoute와 같은 길)
                speech = s.speech.copy(speaking = "u1", queue = listOf(line)),
                session = s.session!!.let {
                    it.copy(offers = it.offers.copy(pendingAsk = PendingAsk("a1", Sid.S11, "n", null, true), driveFrom = k.now - 7_300_000))
                }
            )
        )
        k.boot()
        assertNull(k.store.loadSnapshot()!!.speech.speaking)
        val at = mutableListOf<Pair<String, String?>>() // 실행기에 Ask가 들어가는 순간 저장된 스냅숏의 speaking
        k.execHook = { c -> if (c is Ask) at += c.askId to k.store.loadSnapshot()?.speech?.speaking }
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        k.ev(Tick(0), k.now + Params().speechGapMs + 1_000)
        assertTrue(at.isNotEmpty(), "전제: Ask가 나갔다")
        at.forEach { (id, saved) -> assertEquals(id, saved, "Ask를 넘기기 전에 speaking이 저장돼 있다") }
    }

    // ---- 말하기 전 스냅숏 저장이 실패하면 그 Step을 끊고 프로세스를 끝낸다(Ask를 넘기지 않는다) ----
    @Test fun preSpeakSnapshotFailure_dies_noExecCall() {
        k.profile()
        k.boot()
        k.open()
        val s = k.state()
        val line = Line(Sid.S11, mapOf("restName" to "n 휴게소"), "n", longDrive = true)
        k.store.saveSnapshot(
            s.copy(
                speech = s.speech.copy(speaking = "u1", queue = listOf(line)),
                session = s.session!!.let {
                    it.copy(offers = it.offers.copy(pendingAsk = PendingAsk("a1", Sid.S11, "n", null, true), driveFrom = k.now - 7_300_000))
                }
            )
        )
        k.boot()
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        k.exec.clear()
        sql("CREATE TRIGGER snap_fail BEFORE INSERT ON engine_snapshot BEGIN SELECT RAISE(ABORT, '저장 실패'); END")
        val died = catchingHostDeath {
            k.now += Params().speechGapMs + 1_000
            k.host.post(Tick(0))
        }
        assertNotNull(died, "예외가 프로세스 밖으로 나간다")
        assertTrue(k.exec.none { it is Ask || it is Speak }, "저장하지 못한 말하기는 실행기에 넘기지 않는다")
    }

    // ---- 단독 스냅숏 저장 실패도 die ----
    @Test fun standaloneSnapshotFailureDies() {
        k.profile()
        k.boot()
        k.open()
        sql("CREATE TRIGGER snap_fail BEFORE INSERT ON engine_snapshot BEGIN SELECT RAISE(ABORT, '저장 실패'); END")
        val died = catchingHostDeath {
            k.now += 1_000
            k.host.post(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 2)))
        } // 묶음 없는 바로 저장
        assertNotNull(died, "예외가 프로세스 밖으로 나간다(호스트 스레드는 삼킨다)")
    }

    // ---- 말하기 큐의 줄 목록이 바뀌면 바로 저장한다 ----
    @Test fun droppedDepartOfferSavedImmediately() {
        k.profile()
        k.boot()
        k.open()
        val s = k.state()
        val line = Line(Sid.S07_DEPART, emptyMap(), "n", queuedAt = k.now, sessionId = s.session!!.id)
        k.store.saveSnapshot(
            s.copy(
                car = s.car.copy(gear = Gear.R),
                speech = s.speech.copy(queue = listOf(line)),
                session = s.session!!.let { it.copy(offers = it.offers.copy(spokenAt = mapOf(Sid.S07_DEPART to k.now))) }
            )
        )
        k.boot()
        k.ev(GearChanged(0, Gear.R), k.now + 1_000)
        assertTrue(k.state().speech.queue.isEmpty(), "R 기어에서 버렸다")
        assertTrue(k.store.loadSnapshot()!!.speech.queue.isEmpty(), "저장 전에 죽어도 옛 제안이 되살아나지 않는다")
    }
}
