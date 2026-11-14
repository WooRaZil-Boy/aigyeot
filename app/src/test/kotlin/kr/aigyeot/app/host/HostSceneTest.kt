package kr.aigyeot.app.host

import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.aigyeot.app.common.rfc3339
import kr.aigyeot.app.exec.Changed
import kr.aigyeot.app.exec.LoudnessResult
import kr.aigyeot.app.link.FakeCipher
import kr.aigyeot.app.link.FakeRelay
import kr.aigyeot.app.link.Link
import kr.aigyeot.app.link.db.CareAppliedEntity
import kr.aigyeot.app.store.SnapshotJson
import kr.aigyeot.engine.Area
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.BabyAboard
import kr.aigyeot.engine.BabyAboardFrom
import kr.aigyeot.engine.CabinTemp
import kr.aigyeot.engine.CallMode
import kr.aigyeot.engine.CancelSpeech
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.CareCleared
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.ControlKind
import kr.aigyeot.engine.ControlResult
import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FeedingFrom
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.Line
import kr.aigyeot.engine.LinkChanged
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.MethodsChanged
import kr.aigyeot.engine.Notice
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.ProfileChanged
import kr.aigyeot.engine.RearDoor
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.RouteState
import kr.aigyeot.engine.SeatHeat
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.SetWindowLock
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Side
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.StopPlayback
import kr.aigyeot.engine.Tick
import kr.aigyeot.engine.careReplaces
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 장면 시작·끝과 실제 상태 보관.
@RunWith(RobolectricTestRunner::class)
class HostSceneTest : HostTestBase() {
    // ---- 장면 ----

    // ---- 장면 시작은 실제 상태가 말하는 중이 아니어도 SCENE_START 정리를 조건 없이 적용한다 ----
    @Test fun queuedS06NotSpeaking_cutAtSceneStart_notSaidAfterScene() {
        k.profile()
        k.boot()
        k.open()
        val s = k.state()
        val s06 = Line(Sid.S06, mapOf("elapsed" to "1시간 10분"), null, queuedAt = k.now, sessionId = s.session!!.id)
        k.store.saveSnapshot(s.copy(speech = s.speech.copy(speaking = null, queue = listOf(s06), lastDoneAt = k.now))) // 말 사이 간격으로 기다린다
        k.boot()
        assertEquals(listOf(Sid.S06), k.state().speech.queue.map { it.sid }, "전제: 말하지 않는 S06이 큐에 있다")
        k.exec.clear()
        assertNull(k.host.activeScene.value, "장면 전: 화면의 '모든 기록 지우기'가 켜져 있다")
        k.host.startScenario(setup())
        k.host.awaitIdle()
        assertEquals("A", k.host.activeScene.value?.id, "장면 중: 화면이 지우기를 끈다(호스트도 ClearRecords를 받지 않는다)")
        assertTrue(k.exec.any { it is CancelSpeech }, "말하지 않아도 실행기를 끊는다")
        assertTrue(k.store.loadSnapshot()!!.speech.queue.isEmpty(), "보관한 실제 상태(장면 직전 스냅숏)에 큐 줄이 없다")
        k.host.endScenario()
        k.host.awaitIdle()
        assertNull(k.host.activeScene.value, "장면 끝: 지우기가 다시 켜진다")
        k.ev(Tick(0), k.now + 10 * 60_000) // 장면이 10분 돈 뒤
        assertTrue(k.exec.filterIsInstance<Speak>().none { it.sid == Sid.S06 }, "장면 전 값('1시간 10분')을 장면 뒤에 말하지 않는다")
    }

    @Test fun listeningForAnswer_cutAtSceneStart() {
        k.profile()
        k.boot()
        k.open()
        val s = k.state()
        k.store.saveSnapshot(s.copy(session = s.session!!.let { it.copy(offers = it.offers.copy(driveFrom = k.now - 7_300_000)) })) // 2시간 넘게 달렸다
        k.boot()
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 30.0, true)), 150.0), k.now + 1_000)
        k.ev(Tick(0), k.now + Params().speechGapMs + 1_000)
        val ask = k.exec.filterIsInstance<Ask>().single()
        k.ev(SpeechDone(0, ask.askId, true), k.now + 3_000)
        assertNotNull(k.ses().pendingAsk, "전제: 질문이 끝나 답을 듣는 중")
        assertNull(k.state().speech.speaking)
        k.exec.clear()
        k.host.startScenario(setup())
        k.host.awaitIdle()
        assertTrue(k.exec.any { it is CancelSpeech }, "실행기의 답 듣기를 끊는다(운전자의 '응'이 장면 상태로 가 버려지지 않게)")
        assertNull(k.store.loadSnapshot()!!.session!!.pendingAsk, "보관한 실제 상태에 답 기다림이 없다")
    }

    @Test fun realAutoPlay_stoppedAtSceneStart_suspendReason() {
        k.profile()
        k.boot()
        k.open()
        k.alarm()
        val pl = k.ses().play!!
        k.exec.clear()
        k.host.startScenario(setup())
        k.host.awaitIdle()
        assertTrue(StopPlayback(pl.id, false) in k.exec, "실제 재생을 장면 재생이 덮을 때까지 두지 않는다")
        val real = k.store.loadSnapshot()!!.session!!
        assertEquals(Phase.WAIT, real.phase)
        assertNull(real.play)
        val row = dao.plays(real.id).single { it.id == pl.id }
        assertEquals("SUSPEND", row.endReason)
        k.now += 20 * 60_000 // 장면이 20분 돈다
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(row.playedMs, dao.plays(real.id).single { it.id == pl.id }.playedMs, "장면 길이가 실제 재생 시간에 들지 않는다")
        assertEquals(Phase.WAIT, k.ses().phase)
    }

    // ---- 장면 끝은 장면 세션이 잠근 뒷창을 되돌린다 ----
    @Test fun sceneEnd_restoresSceneSessionWindowLock() {
        k.profile()
        k.boot()
        k.host.startScenario(setup(sibling = true))
        k.host.awaitIdle()
        k.open() // 장면 세션(형제 있음 → 뒷창 잠금)
        val lock = k.exec.filterIsInstance<SetWindowLock>().single()
        lock.values.keys.forEach { a -> k.ev(ControlResult(0, lock.cmdId, ControlKind.WINDOW_LOCK, a, true, 0.0, 1.0)) } // 원래 열림
        k.exec.clear()
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(lock.values.keys.associateWith { false }, k.exec.filterIsInstance<SetWindowLock>().single().values, "장면 세션이 닫히기 전에 끝나도 뒷창을 원래 값으로 둔다")
    }

    @Test fun scenario_saveDuringScenario_keepsRealSnapshot() {
        k.profile()
        k.boot()
        k.host.startScenario(setup())
        k.host.awaitIdle()
        val real = k.snapshotJson()
        k.open() // 장면 안에서 세션을 연다(SaveSession → 저장 지시)
        assertEquals(real, k.snapshotJson(), "장면 중에는 실제 engine_snapshot을 덮지 않는다")
        assertEquals("A", dao.session(k.ses().id)!!.scenarioId)
        k.running = null
        k.boot() // 장면 중 프로세스가 죽었다
        assertNull(k.state().session, "실제 상태(세션 없음)로 복원")
    }

    @Test fun scenario_endRestoresReal_recordsNotMixed() {
        k.profile()
        k.phone()
        k.boot()
        val realHistory = k.state().history
        k.host.startScenario(setup())
        k.host.awaitIdle()
        k.open()
        k.alarm()
        k.toGap()
        k.ev(CrySegment(0, k.now, k.now + 3_000, 0.9, false), k.now + 3_000) // GAP 재울음 → 1차 FAIL
        val sid = k.ses().id
        assertEquals("FAIL", dao.episodes(sid).single().firstResult)
        k.ev(GearChanged(0, Gear.P), k.now + 1_000).ev(RearDoor(0, Side.LEFT, true))
        assertEquals(false, dao.session(sid)!!.reportPending)
        assertTrue(k.store.history(k.now).isEmpty(), "장면 세션은 성적에서 뺀다")
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(realHistory, k.state().history)
        val c = k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox WHERE type = 'report_summary'")
        c.moveToFirst()
        assertEquals(0, c.getInt(0), "장면 리포트는 보내지 않는다")
        c.close()
    }

    @Test fun scenario_endBeforeSessionClose_closesScenarioSession() {
        k.profile()
        k.phone()
        k.boot()
        k.open()
        val realId = k.ses().id
        k.host.startScenario(setup())
        k.host.awaitIdle()
        k.now += 1_000 // 세션 id는 시각에서 만든다
        k.ev(GearChanged(0, Gear.P)).open() // 장면은 실제 기어 D를 이어받아 P부터
        val sid = k.ses().id
        assertNotEquals(realId, sid)
        assertNotNull(dao.session(sid)!!.scenarioId)
        assertNull(dao.session(sid)!!.endedAt) // 전제: 장면 세션이 열려 있다
        k.host.endScenario()
        k.host.awaitIdle()
        assertNotNull(dao.session(sid)!!.endedAt, "끝나지 못한 장면 세션을 닫는다")
        assertEquals(false, dao.session(sid)!!.reportPending)
        assertNull(dao.session(realId)!!.endedAt, "실제 세션은 열린 채")
    }

    @Test fun start_closesOrphanScenarioSession() {
        k.profile()
        k.phone()
        k.boot()
        k.open()
        val realId = k.ses().id
        val real = dao.session(realId)!!
        dao.upsertSession(real.copy(id = "orphan", scenarioId = "A")) // 장면 중 프로세스가 죽어 남은 행
        assertNull(dao.session("orphan")!!.endedAt)
        k.running = null
        k.boot()
        assertNotNull(dao.session("orphan")!!.endedAt)
        assertEquals(false, dao.session("orphan")!!.reportPending)
        assertNull(dao.session(realId)!!.endedAt, "실제 세션은 그대로")
    }

    @Test fun scenario_overrideDropsRealEvents() {
        k.profile()
        k.boot()
        k.host.startScenario(setup(override = setOf("CabinTemp")))
        k.host.awaitIdle()
        k.host.postReal(CabinTemp(0, 19.1))
        k.host.awaitIdle()
        assertNull(k.state().car.cabinC, "진짜 신호는 버린다")
        k.ev(CabinTemp(0, 30.0))
        assertEquals(30.0, k.state().car.cabinC, "장면 주입은 받는다")
        k.host.endScenario()
        k.host.awaitIdle()
        k.host.postReal(CabinTemp(0, 19.1))
        k.host.awaitIdle()
        assertEquals(19.1, k.state().car.cabinC, "끝나면 다시 받는다")
    }

    @Test fun scenarioCare_overridesRealSummary_andEndRestoresReal() {
        k.profile()
        k.boot()
        val real = CareView(k.now, true, null, null, null, null, null, null, null, 5)
        linkCare(real, 1)
        val fake = real.copy(asOf = real.asOf - 60_000, todayFeedCount = 2)
        k.host.startScenario(setup(override = setOf("CareSummary")).copy(care = fake))
        k.host.awaitIdle()
        assertEquals(fake, k.state().care, "장면 setup.care가 첫 Step 뒤 들어간다")
        val newer = real.copy(asOf = real.asOf + 1_000)
        linkCare(newer, 2)
        assertEquals(fake, k.state().care, "실제 link 요약은 막힌다")
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(newer, k.state().care, "끝나면 실제로 받은 가장 늦은 값")
    }

    /**
     * link 받기 흉내(조립 줄): Room 행과 [careReplaces](LinkRoom care_summary와 같은 함수)로 견줘 이기면 행을 바꾸고 실제 사건을 낸다.
     * 장면 끝 실제 요약은 이 행만 따른다(호스트가 따로 들지 않는다).
     */
    private fun linkCare(c: CareView, seq: Long, room: String? = null) {
        val row = k.db.linkDao().careApplied()
        val old = row?.let { SnapshotJson.decodeFromString(CareView.serializer(), it.summaryJson) }
        if (row != null && !careReplaces(old, row.seq, c, seq, row.carId, room)) return
        careRow(c, seq, room)
        k.host.postReal(CareSummary(0, c, seq, room))
        k.host.awaitIdle()
    }

    private fun careEndsAs(override: Set<String>, during: CareView?): CareView? {
        k.db.linkDao().clearCareApplied() // 같은 kit으로 되풀이해 부른다. 앞 호출의 Room 행·장면 끝에 바로 저장한 요약이 다음 boot로 이어지지 않게
        k.profile()
        k.boot()
        k.store.saveSnapshot(k.state().copy(care = null, careSeq = null, careRoom = null))
        k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        linkCare(c1, 1)
        k.host.startScenario(setup(override = override))
        k.host.awaitIdle()
        during?.let { linkCare(it, 2) }
        k.host.endScenario()
        k.host.awaitIdle()
        return k.state().care
    }

    @Test fun realSummaryDuringScene_survivesSceneEnd() {
        val c2 = CareView(T0 + 5_000, true, null, null, null, null, null, null, null, 9)
        assertEquals(c2, careEndsAs(emptySet(), c2), "override 없는 장면: 장면 상태에 들어갔다가 끝나도 남는다")
        assertEquals(c2, careEndsAs(setOf("CareSummary"), c2), "override 장면: 장면에는 안 들어가도 끝나면 실제 최신 값")
        assertEquals(1, careEndsAs(setOf("CareSummary"), null)!!.todayFeedCount, "장면 전 값만 있으면 그대로")
        assertEquals(1, careEndsAs(emptySet(), c2.copy(asOf = T0 - 5_000))!!.todayFeedCount, "장면 전 값보다 이른 요약은 이기지 못한다")
    }

    @Test fun sameSecondSeq_savedImmediately_evenWhenOnlySeqChanges() {
        k.profile()
        k.boot()
        k.open()
        val c = CareView(k.now, true, null, null, null, null, null, null, null, 2)
        k.ev(CareSummary(0, c, seq = 5), k.now + 1_000)
        val s0 = k.savedAt()
        k.ev(CareSummary(0, c, seq = 6), k.now + 1_000) // 같은 초·같은 내용, seq만 큼: careSeq가 key에 들어 있어야 바로 저장
        assertEquals(6L, k.state().careSeq)
        assertNotEquals(s0, k.savedAt())
        assertEquals(6L, k.store.loadSnapshot()!!.careSeq)
    }

    @Test fun realSummaryDuringScene_sameSecondLaterSeq_restoredAtEnd_andSavedNow() {
        k.profile()
        k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c1, seq = 5))
        careRow(c1, 5, null) // link가 받았다면 Room 행도 있다(행 없이 seq 있는 요약은 지운 것으로 본다)
        k.host.startScenario(setup(override = setOf("CareSummary")).copy(care = c1.copy(asOf = k.now + 60_000, todayFeedCount = 9)))
        k.host.awaitIdle()
        val c2 = c1.copy(todayFeedCount = 2) // 장면 값보다 이르고 장면 전 실제 값과 같은 초지만 seq가 큰 실제 요약
        k.host.postReal(CareSummary(0, c2, seq = 6))
        k.host.awaitIdle()
        careRow(c2, 6, null) // link가 받았다면 Room 행도 갱신된다(복원은 행을 정답으로 본다)
        assertEquals(9, k.state().care!!.todayFeedCount, "장면 중에는 장면 값")
        assertEquals(1, k.store.loadSnapshot()!!.care!!.todayFeedCount, "장면 중 실제 스냅숏은 장면 직전 값")
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(c2, k.state().care)
        assertEquals(6L, k.state().careSeq)
        assertEquals(c2, k.store.loadSnapshot()!!.care, "장면 끝에 실제 값으로 되돌린 상태는 바로 저장")
        assertEquals(6L, k.store.loadSnapshot()!!.careSeq)
    }

    // 장면이 CareSummary를 막지 않아도 장면 중 실제 요약은 장면 상태에 넣지 않는다(실제 값만 바꾸고 장면 끝에 되돌린다)
    @Test fun realSummaryDuringNonBlockingScene_sceneCareKept_restoredAtEnd() {
        k.profile()
        k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c1, seq = 5))
        careRow(c1, 5, null)
        k.host.startScenario(setup().copy(care = c1.copy(asOf = k.now - 60_000, todayFeedCount = 9)))
        k.host.awaitIdle() // 장면 값이 더 이르다: 장면 상태로 들어가면 실제 요약이 이긴다
        val c2 = c1.copy(todayFeedCount = 2)
        k.host.postReal(CareSummary(0, c2, seq = 6))
        k.host.awaitIdle()
        careRow(c2, 6, null)
        assertEquals(9, k.state().care!!.todayFeedCount, "막지 않는 장면이어도 장면 중에는 장면 값")
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(c2, k.state().care)
        assertEquals(6L, k.state().careSeq)
    }

    @Test fun careClearedDuringNonBlockingScene_noSummaryAtEnd() {
        k.profile()
        k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c1, seq = 5))
        careRow(c1, 5, null)
        k.host.startScenario(setup().copy(care = c1.copy(asOf = k.now - 60_000, todayFeedCount = 9)))
        k.host.awaitIdle()
        k.host.postReal(CareSummary(0, c1.copy(todayFeedCount = 2), seq = 6))
        k.db.linkDao().clearCareApplied()
        k.host.postReal(CareCleared(0))
        k.host.awaitIdle() // link는 지우기와 함께 Room 행도 지운다
        assertEquals(9, k.state().care!!.todayFeedCount, "장면 값은 그대로")
        k.host.endScenario()
        k.host.awaitIdle()
        assertNull(k.state().care)
    }

    // 장면 중 실제 수유 기록(child_profile 변경)이 methodsMaybeChanged를 불러도 장면 음원 목록은 setup 값 그대로
    @Test fun realFeedingDuringScene_doesNotChangeSceneMethods() {
        k.profile()
        k.boot()
        k.host.startScenario(setup())
        k.host.awaitIdle()
        val scene = k.state().methodsNext
        sql("UPDATE child_profile SET methods = '[{\"kind\":\"WHITE_NOISE\"},{\"kind\":\"LULLABY\"},{\"kind\":\"SONG\"}]'")
        sql(
            "INSERT INTO song (id, name, received_at, format, duration_s, lufs, normalized_path, status, reject_reason, added_seq, last_seq) " +
                "VALUES ('s1', '자장자장', $T0, 'AAC', 60.0, -30.0, NULL, 'ACTIVE', NULL, 3, 3)"
        )
        k.host.methodsMaybeChanged()
        k.host.awaitIdle()
        assertEquals(scene, k.state().methodsNext, "장면 상태는 장면 setup 값")
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(Method(MethodKind.SONG, "s1"), k.state().methodsNext.last(), "실제 값은 장면 끝에 남는다")
    }

    // 장면 중 실제 link·프로필 변경은 장면 상태를 바꾸지 않고 장면 끝에 실제 상태에 남는다
    @Test fun realLinkAndProfileDuringScene_goToReal() {
        k.profile()
        k.boot()
        k.host.startScenario(setup())
        k.host.awaitIdle()
        val sceneProfile = k.state().profile
        k.phone("guardian", alerts = true)
        k.profile(interval = 120)
        k.host.postReal(LinkChanged(0, 1))
        k.host.postReal(ProfileChanged(0, k.store.profile()))
        k.host.awaitIdle()
        assertEquals(0, k.state().otherGuardians)
        assertEquals(sceneProfile, k.state().profile)
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(1, k.state().otherGuardians)
        assertEquals(k.store.profile(), k.state().profile)
    }

    // 장면 phone 단계 주입(post)은 장면 사건이라 장면 상태에 들어간다
    @Test fun scenePhoneStepStillApplies() {
        k.profile()
        k.boot()
        k.host.startScenario(setup())
        k.host.awaitIdle()
        k.ev(FeedingStarted(0, k.now, FeedingFrom.PHONE, "m1"))
        assertEquals(k.now, k.state().feedingStart)
    }

    // 복원 대조의 link 읽기가 한 번 실패해도 빈 목록으로 넘어가지 않고 다시 읽는다
    @Test fun reconcileRetriesLinkReadOnce_sceneEnd() {
        k.profile()
        k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c1, seq = 5))
        careRow(c1, 5, null)
        k.host.startScenario(setup().copy(care = c1.copy(asOf = k.now - 60_000, todayFeedCount = 9)))
        k.host.awaitIdle()
        k.db.linkDao().clearCareApplied() // 장면 중 연결 해제로 행만 지워졌다(CareCleared 사건은 장면 끝 복원만 낸다)
        k.restoreFailures = 1
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(0, k.restoreFailures, "첫 읽기가 실제로 던졌다")
        assertNull(k.state().care, "다시 읽어 CareCleared가 반영된다")
    }

    @Test fun reconcileRetriesLinkReadOnce_processStart() {
        k.profile()
        k.boot()
        val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c1, seq = 5))
        careRow(c1, 5, null)
        k.host.flush()
        k.host.awaitIdle()
        k.db.linkDao().clearCareApplied() // 연결 해제로 행이 지워졌다
        k.restoreFailures = 1
        k.boot()
        assertEquals(0, k.restoreFailures)
        assertNull(k.state().care, "다시 읽어 CareCleared가 반영된다")
    }

    // ---- 장면 끝에서 실제 요약 복원 ----
    @Test fun realSummary_afterQueuedStartScenario_isKeptAndRestoredAtEnd() {
        for (override in listOf(emptySet(), setOf("CareSummary"))) {
            k.db.linkDao().clearCareApplied()
            k.profile()
            k.boot()
            k.store.saveSnapshot(k.state().copy(care = null, careSeq = null, careRoom = null))
            k.boot() // 앞서 쓴 요약을 비운다
            val c1 = CareView(k.now, true, null, null, null, null, null, null, null, 1)
            linkCare(c1, 1)
            val latch = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val saved = k.host.targets
            k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {
                entered.countDown()
                latch.await()
            })
            k.host.resume() // 호스트 스레드를 붙잡는다(큐가 대기하는 동안)
            entered.await()
            k.host.startScenario(setup(override = override)) // 큐에 장면 시작
            val newer = c1.copy(asOf = c1.asOf + 1_000, todayFeedCount = 7)
            careRow(newer, 2, null)
            k.host.postReal(CareSummary(0, newer, 2)) // 실행 전에 새 실제 요약이 온다(link가 Room 행을 먼저 쓴다)
            latch.countDown()
            k.host.awaitIdle()
            k.host.targets = saved
            k.host.endScenario()
            k.host.awaitIdle()
            assertEquals(newer, k.state().care, "override=$override: 장면 끝에 장면 전 값이 아니라 새 실제 요약")
            assertEquals(newer, k.store.loadSnapshot()!!.care)
        }
    }

    @Test fun endScenario_callsRouteAgain_afterRestoreClearedRoute() {
        k.profile()
        k.boot()
        val route = RouteState(0, true, 3_000, emptyList(), 80.0)
        k.ev(route)
        assertEquals(true, k.state().car.route != null)
        val saved = k.host.targets
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {
        }, restore = saved.restore, reemitAll = { k.host.postReal(route) }) // exec가 최신 RouteState를 다시 낸다(reemitAll)
        k.host.startScenario(setup())
        k.host.awaitIdle()
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(true, k.state().car.route != null, "복원 ③이 비운 경로가 돌아온다")
    }

    @Test fun sceneResume_doesNotRefillRealCare_bootAndSceneEndDo() { // 장면 중 Resume·시계 점프는 대조하지 않는다. 장면 끝 복원이 한 번 맞춘다
        k.profile()
        k.boot()
        val calls = AtomicInteger()
        val saved = k.host.targets
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = { st, at ->
            calls.incrementAndGet()
            saved.restore(st, at)
        })
        k.host.suspend()
        k.host.resume()
        k.host.awaitIdle()
        assertEquals(1, calls.get(), "장면 없는 Resume")
        k.host.startScenario(setup())
        k.host.awaitIdle()
        calls.set(0)
        k.host.suspend()
        k.host.resume()
        k.host.awaitIdle()
        k.slept += 600_000
        k.ev(Tick(0), k.now + 600_000) // 신호 없는 시계 점프도
        assertEquals(0, calls.get(), "장면 중 Resume·시계 점프는 대조하지 않는다")
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(1, calls.get(), "장면 끝 복원은 대조한다")
    }

    private fun careRow(c: CareView, seq: Long, room: String?) =
        k.db.linkDao().putCareApplied(CareAppliedEntity(1, room, seq, SnapshotJson.encodeToString(CareView.serializer(), c), "d_PHONE"))

    @Test fun sceneHeldSummary_seqDecides() { // 장면 중 이른 as_of·큰 seq 요약이 장면 끝에 적용된다(판정은 link의 Room 행 하나, 호스트는 들지 않는다)
        k.profile()
        k.boot()
        fun c(at: Long, feeds: Int) = CareView(at, true, null, null, null, null, null, null, null, feeds)
        linkCare(c(k.now + 240_000, 1), 5, "c_A")
        k.host.startScenario(setup())
        k.host.awaitIdle()
        linkCare(c(k.now + 60_000, 2), 6, "c_A") // 장면 중 실제로 받은 요약(Room 행)
        linkCare(c(k.now + 300_000, 3), 4, "c_A") // 작은 seq는 늦은 as_of도 버린다
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(2, k.state().care?.todayFeedCount)
        assertEquals(6L, k.state().careSeq)
    }

    // ---- 장면 중 setup 몫 실제 사건은 장면 상태에도, 호스트가 따로 든 실제 값에도 들지 않는다. 장면 끝 실제 상태는 Room(복원 ⑤)만 맞춘다 ----
    @Test fun setupOwnedDuringScene_notHeld_onlyRoomAtEnd() {
        k.profile()
        k.boot()
        val before = k.state()
        val saved = k.host.targets
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = { _, _ -> emptyList() }) // link 몫 Room 값(수유·보호자·요약)이 그대로라고 답한다
        k.host.startScenario(setup())
        k.host.awaitIdle()
        val scene = k.state()
        k.host.postReal(FeedingStarted(0, k.now - 1_000, FeedingFrom.PHONE, "m1"))
        k.host.postReal(LinkChanged(0, 2))
        k.host.postReal(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 4), 9, "c_A"))
        k.host.postReal(MethodsChanged(0, listOf(Method(MethodKind.WOMB)))) // store Room 값(부모 노래 후보)과 다르다
        k.host.postReal(ProfileChanged(0, Profile("2025-01", CarSeat.RIGHT, 90, true))) // store Room 값(프로필)과 다르다
        k.host.awaitIdle()
        val mid = k.state()
        assertEquals(
            listOf(scene.feedingStart, scene.otherGuardians, scene.care, scene.methodsNext, scene.profile),
            listOf(mid.feedingStart, mid.otherGuardians, mid.care, mid.methodsNext, mid.profile),
            "장면 상태는 setup 값 그대로"
        )
        k.host.endScenario()
        k.host.awaitIdle()
        val s = k.state()
        assertEquals(
            listOf(before.feedingStart, before.otherGuardians, before.care, before.methodsNext, before.profile),
            listOf(s.feedingStart, s.otherGuardians, s.care, s.methodsNext, s.profile),
            "Room이 바뀌지 않았으면 실제 상태도 그대로(장면 중 사건을 접어 두지 않는다)"
        )
    }

    // ---- 화면·알림 입력은 실제 입구(Host.uiSink = postReal) 하나로 들어간다 ----
    @Test fun uiSink_isRealEntrance_overrideAndSetupOwnedDuringScene() {
        k.profile()
        k.boot()
        val ui = k.host.uiSink()
        val sceneSetup = setup(override = setOf("BabyAboard", "AskButton", "FeedingConfirmed"))
        k.host.startScenario(sceneSetup)
        k.host.awaitIdle()
        ui.post(BabyAboard(0, BabyAboardFrom.HOME))
        k.host.awaitIdle()
        assertEquals(false, k.state().ign.reserved, "장면이 막은 화면 입력은 장면 상태에 들지 않는다")
        ui.post(ProfileChanged(0, Profile("2025-01", CarSeat.RIGHT, 90, true)))
        k.host.awaitIdle()
        assertEquals(sceneSetup.profile, k.state().profile, "setup 몫 화면 입력(프로필 저장)은 장면 상태에 들지 않는다")
        k.ev(BabyAboard(0, BabyAboardFrom.HOME))
        assertEquals(true, k.state().ign.reserved, "장면 주입(post)은 받는다")
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(false, k.state().ign.reserved, "장면 주입은 실제 상태로 새지 않는다")
        ui.post(BabyAboard(0, BabyAboardFrom.HOME))
        k.host.awaitIdle()
        assertEquals(true, k.state().ign.reserved, "장면이 끝나면 실제 상태가 받는다")
    }

    // 차 화면 수유는 Room에 먼저 쓰고 feeding_state를 넣은 뒤 실제 입구로 사건을 낸다. 장면 중이면 장면 상태에 들지 않고 장면 끝 복원이 맞춘다
    private fun carFeedingDuringScene(): Long {
        k.profile()
        k.phone()
        k.boot()
        val (real, injected) = k.host.linkSinks()
        val link = Link(
            ApplicationProvider.getApplicationContext(), k.db, k.clock, real, { null }, FakeRelay(),
            FakeCipher, { null }, { _, _ -> LoudnessResult(true) }, k.linkScope, postInjected = injected
        )
        k.host.startScenario(setup())
        k.host.awaitIdle()
        val at = k.now - 1_000
        kotlinx.coroutines.runBlocking { link.feedingFromCar(at).join() }
        k.host.awaitIdle()
        assertEquals(at, dao.profile()!!.lastFeedingStart, "Room에 먼저 쓴다")
        assertEquals(1, k.db.linkDao().outbox().count { it.type == "feeding_state" }, "feeding_state를 보낼 함에 넣는다")
        assertNull(k.state().feedingStart, "장면 상태에는 들지 않는다")
        return at
    }

    @Test fun carFeedingDuringScene_roomFirst_appliedAtSceneEnd() {
        val at = carFeedingDuringScene()
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(at, k.state().feedingStart, "장면 끝 복원 ⑤가 Room 값으로 맞춘다")
    }

    @Test fun carFeedingDuringScene_survivesDeathMidScene() {
        val at = carFeedingDuringScene()
        k.running = null
        k.boot() // 장면 중 프로세스가 죽었다
        assertEquals(at, k.state().feedingStart, "다시 켜면 실제 스냅숏 + Room으로 수유 시각이 남는다")
    }

    // ---- 장면 끝에 실행기가 지금 차 값을 다시 내고, 판단 코어는 장면 차 값을 칸별로 되돌리지 않는다 ----
    @Test fun sceneEnd_reemitAll_realCarValuesReplaceStale() {
        k.profile()
        k.boot()
        k.open()
        k.ev(CabinTemp(0, 20.0)).ev(SeatHeat(0, Area.ROW_2_LEFT, 0))
        val saved = k.host.targets
        val now = mutableListOf<Event>(CabinTemp(0, 27.0), SeatHeat(0, Area.ROW_2_LEFT, 2), GearChanged(0, Gear.D)) // 실제 차의 지금 값(장면 동안 바뀜)
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = saved.restore, reemitAll = { now.forEach(k.host::postReal) })
        k.host.startScenario(setup(override = setOf("CabinTemp")))
        k.host.awaitIdle()
        k.host.postReal(CabinTemp(0, 27.0))
        k.host.awaitIdle() // override라 버려진 실제 온도
        k.ev(CabinTemp(0, 35.0)) // 장면 주입
        k.host.postReal(SeatHeat(0, Area.ROW_2_LEFT, 2))
        k.host.awaitIdle() // override가 아니라 장면 상태로 간 실제 열선
        assertEquals(35.0, k.state().car.cabinC)
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(27.0, k.state().car.cabinC, "장면 주입 값이 아니라 실제 차의 지금 온도(옛 칸별 되돌리기는 장면 전 20.0)")
        assertEquals(2, k.state().car.seatHeat[Area.ROW_2_LEFT], "장면 동안 바뀐 실제 열선도 다시 낸 값으로")
        assertEquals(Gear.D, k.state().car.gear)
    }

    // ---- 장면 끝 reemitAll은 바뀐 값만 사건으로 만든다(닫힘 값만 다시 와도 S16·알림 없음), 순서는 기어 먼저 ----
    @Test fun sceneEnd_reemitAll_closedDoorOnly_noS16NoNotice() {
        k.profile()
        k.boot()
        k.ev(GearChanged(0, Gear.P)).ev(RearDoor(0, Side.LEFT, false)) // 실제: P·IDLE·문 닫힘·마이크 꺼짐
        val saved = k.host.targets
        k.host.targets = Targets(
            saved.exec,
            saved.link,
            saved.notice,
            wake = {},
            restore = saved.restore,
            reemitAll = {
                k.host.postReal(GearChanged(0, Gear.P))
                k.host.postReal(RearDoor(0, Side.LEFT, false))
            }
        )
        k.notices.clear()
        repeat(2) {
            k.host.startScenario(setup())
            k.host.awaitIdle()
            k.host.endScenario()
            k.host.awaitIdle()
        }
        assertEquals(false, k.state().ign.s16Said)
        assertTrue(k.notices.none { it == Notice.BABY_ABOARD })
        assertTrue(k.state().speech.queue.isEmpty() && k.state().speech.speaking == null)
    }

    @Test fun sceneEnd_reemitAll_gearBeforeDoor_closesRealSession13a() {
        k.profile()
        k.boot()
        k.open() // 실제 세션 D 열림
        val changed = Changed() // 실제 연결이 받은 값: 뒷문을 먼저 받은 것처럼 넣어도 다시 낼 때는 기어가 먼저
        changed.emit("doorLEFT", true, { RearDoor(0, Side.LEFT, true) }) {}
        changed.emit("gear", Gear.P, { GearChanged(0, Gear.P) }) {}
        val saved = k.host.targets
        k.host.targets = Targets(saved.exec, saved.link, saved.notice, wake = {}, restore = saved.restore, reemitAll = { changed.reemit(k.host::postReal) })
        k.host.startScenario(setup())
        k.host.awaitIdle()
        k.host.endScenario()
        k.host.awaitIdle()
        assertNull(k.state().session, "장면 중 실제 차가 P가 되고 문이 열림: 장면 끝에 세션이 닫힌다")
    }

    @Test fun snapshot_keepsCareRoom() {
        k.profile()
        k.boot()
        val c = CareView(k.now, true, null, null, null, null, null, null, null, 1)
        k.ev(CareSummary(0, c, seq = 5, room = "c_A"))
        careRow(c, 5, "c_A")
        k.boot()
        assertEquals("c_A", k.state().careRoom)
        assertEquals(5L, k.state().careSeq)
    }

    @Test fun scenarioPhoneStep_feedingStarted_notBlockedByOverride_viaWiringSinks() {
        k.profile()
        k.boot()
        val (real, injected) = k.host.linkSinks()
        val link = Link(
            ApplicationProvider.getApplicationContext(), k.db, k.clock, real, { null }, FakeRelay(),
            FakeCipher, { null }, { _, _ -> LoudnessResult(true) }, k.linkScope, postInjected = injected
        )
        k.host.startScenario(setup(override = setOf("FeedingStarted")))
        k.host.awaitIdle()
        val at = T0 - 600_000
        val json = """{"v":1,"id":"01JC8Y2K6N3T5Q9W0ZB4H7X2D1","type":"feeding_started","ts":"2026-11-05T08:12:21+09:00","body":{"at":"${kr.aigyeot.app.common.rfc3339(
            at
        )}"}}"""
        kotlinx.coroutines.runBlocking { link.receiveForScenario(json) }
        k.host.awaitIdle()
        assertEquals(at, k.state().feedingStart, "장면 phone 단계 주입은 override로 막히지 않는다")
        real(FeedingStarted(0, at + 1_000, FeedingFrom.PHONE, null))
        k.host.awaitIdle()
        assertEquals(at, k.state().feedingStart, "실제로 받은 사건은 override로 막힌다")
    }

    @Test fun scenario_endDoesNotKeepInjectedOverrideCar() {
        k.profile()
        k.boot()
        k.host.startScenario(setup(override = setOf("CallMode")))
        k.host.awaitIdle()
        k.ev(CallMode(0, true))
        assertTrue(k.state().car.inCall, "장면 주입")
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(false, k.state().car.inCall, "실제 값으로 되돌린다")
    }

    @Test fun scenario_endClearsRealSpeaking() {
        k.profile()
        k.boot()
        k.host.startScenario(setup())
        k.host.awaitIdle() // 실제 상태가 말하는 중에는 장면이 시작하지 않으므로 장면 안에서 말하게 한다
        k.ev(GearChanged(0, Gear.P)).ev(RearDoor(0, Side.LEFT, true)).ev(RearDoor(0, Side.LEFT, false)) // 마이크 꺼짐 → S16
        assertNotNull(k.state().speech.speaking, "장면 상태가 말하는 중")
        k.host.endScenario()
        k.host.awaitIdle()
        assertNull(k.state().speech.speaking, "콜백이 사라진 말하기는 복원처럼 비운다")
    }

    @Test fun endScenario_restoresOverriddenGearAndDoor() {
        k.profile()
        k.boot()
        k.open()
        k.host.startScenario(setup(override = setOf("GearChanged", "RearDoor")))
        k.host.awaitIdle()
        k.ev(GearChanged(0, Gear.P)).ev(RearDoor(0, Side.LEFT, true)) // 장면 주입
        assertEquals(Gear.P, k.state().car.gear)
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(Gear.D, k.state().car.gear, "실제 기어로 되돌린다")
        assertEquals(emptySet(), k.state().car.rearDoorOpen, "실제 뒷문(닫힘)으로 되돌린다")
    }

    // ---- 실제 사건의 override 판정은 큐에서 꺼낼 때 ----
    @Test fun realEventQueuedAfterSceneStart_judgedAtQueue() {
        k.profile()
        k.boot()
        val gate = CountDownLatch(1)
        val inside = CountDownLatch(1)
        k.onSlept = {
            k.onSlept = null
            inside.countDown()
            gate.await()
        } // 호스트를 붙잡고 큐를 쌓는다
        k.host.post(Tick(0))
        inside.await()
        k.host.startScenario(setup(override = setOf("FeedingStarted")))
        k.host.postReal(FeedingStarted(0, k.now - 1_000, FeedingFrom.PHONE, "m1")) // 장면 시작이 큐에 먼저 든 뒤
        gate.countDown()
        k.host.awaitIdle()
        assertNull(k.state().feedingStart, "장면이 막은 실제 수유 사건이 장면 값을 덮지 않는다")
    }

    // ---- 장면 시작이 실제 발화를 먼저 끊고(통화와 같은 cancelSpeech) 바로 시작한다 ----
    @Test fun sceneStartCancelsRealSpeech_thenStartsNow() {
        val (id, u) = speakingWithHandoff(handoffPhase = true)
        assertNotNull(k.state().session, "전제: 실제 세션")
        assertEquals(u, k.state().speech.speaking)
        k.exec.clear()
        k.link.clear()
        k.host.startScenario(setup())
        k.host.awaitIdle()
        assertTrue(k.exec.any { it is CancelSpeech }, "실제 발화를 끊는다")
        assertNull(k.state().session, "미루지 않고 바로 시작했다")
        assertNull(k.state().speech.speaking)
        val a = k.link.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
        assertEquals(1, a.size, "묶인 인계는 이름 없는 알림")
        assertEquals("아기가 계속 울어요.", a.single().body["text"]!!.toString().trim('"'))
        assertEquals(0, toldDriverRow(id), "끊긴 안내는 false")
        // 장면 입력(뒷문 열림)이 실제 상태에 들어가지 않는다: 장면이 끝나면 실제 세션은 그대로다
        k.host.postReal(SpeechDone(0, u, true))
        k.host.endScenario()
        k.host.awaitIdle()
        assertNotNull(k.state().session, "실제 세션이 장면 입력으로 닫히지 않았다")
        assertNull(k.state().speech.speaking)
        assertEquals(0, toldDriverRow(id))
        assertEquals(1, k.link.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" }, "알림이 늘지 않는다")
    }

    @Test fun sceneStartImmediateWhenNotSpeaking() {
        k.profile()
        k.boot()
        k.open()
        assertNull(k.state().speech.speaking)
        assertNotNull(k.state().session, "전제: 실제 세션")
        k.exec.clear()
        k.host.startScenario(setup())
        k.host.awaitIdle()
        // 말하지 않아도 실행기를 끊는다(질문 답·질문 듣기가 같은 실행기다. Suspend·통화와 같다). 기다리지 않고 바로 시작하는 것은 그대로
        assertNull(k.state().session, "말하지 않으면 바로 시작한다")
        assertTrue(k.exec.any { it is CancelSpeech })
    }

    // ---- 장면 시작도 맨 앞에서 시계 점프를 확인한다 ----
    @Test fun sceneStartChecksJumpFirst() {
        val (_, u) = speakingWithHandoff(handoffPhase = true)
        val q0 = k.ses().quietMs
        val wake = k.now + 600_000
        k.cancelIfSpeakingHook = { true to wake } // 깨어난 뒤에야 TTS 완료가 보고됐다
        k.slept += 600_000
        k.now = wake
        k.host.startScenario(setup())
        k.host.awaitIdle()
        k.host.endScenario()
        k.host.awaitIdle()
        assertTrue((k.state().session?.quietMs ?: 0) - q0 < 600_000, "잠든 10분이 quietMs에 더해지지 않는다")
        assertNotEquals(Phase.LISTENING, k.state().session?.phase, "잠든 시간으로 에피소드가 닫히지 않는다")
    }
}
