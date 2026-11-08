package kr.aigyeot.app.host

import kr.aigyeot.app.store.db.EpisodeEntity
import kr.aigyeot.engine.*
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// C242: 장면이 인계 문장 확정 전에 끝나면 장면 세션 에피소드의 handoff_told_driver를 거짓으로 쓴다(endScenario 경로).
@RunWith(RobolectricTestRunner::class)
class SceneCloseTest {
    private val k = Kit()
    @After fun close() { k.host.close(); k.db.close(); k.linkScope.cancel() }

    private fun setup(guardians: Int = 0, override: Set<String> = emptySet()) = ScenarioSetup(
        "A", 7, Profile("2026-03", CarSeat.LEFT, null, false), listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)),
        emptyList(), null, guardians, override,
    )

    @Test fun c242_endScenario_settlesUnknownHandoff() {
        k.profile(); k.phone(); k.boot()
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.open()
        val sid = k.ses().id
        val dao = k.db.storeDao()
        dao.upsertEpisode(EpisodeEntity("E1", sid, T0, T0 + 1, "ALARM", T0, 2_400, "LULLABY", null, "FAIL", null,
            null, null, null, null, "SESSION_CAP", "WAYPOINT_OFFER", false, false, 1, false, 1, emptyList()))
        assertNull(dao.episodes(sid).single().handoffToldDriver)
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(false, dao.episodes(sid).single().handoffToldDriver)
    }

    // C259: 장면 중 열린 에피소드(ended_at 없음)·미확정 인계는 장면 끝과 프로세스 시작 복원 모두에서 닫힌다
    private fun openSceneEpisode(): String {
        k.profile(); k.phone(); k.boot()
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.open()
        val sid = k.ses().id
        k.db.storeDao().upsertEpisode(EpisodeEntity("E1", sid, T0, null, "ALARM", T0, 2_400, "LULLABY", null, null, null,
            null, null, null, null, null, "WAYPOINT_OFFER", false, true, 1, false, 1, emptyList()))
        return sid
    }
    private fun assertClosed(sid: String) {
        val e = k.db.storeDao().episodes(sid).single()
        assertNotNull(e.endedAt, "열린 에피소드가 닫힌다"); assertEquals(false, e.handoffToldDriver)
        assertEquals(true, e.guardianNotified, "결정 ③: 장면 정리는 guardian_notified를 짐작해 고치지 않는다(link만 쓴다)")
        assertNotNull(k.db.storeDao().session(sid)!!.endedAt)
    }
    @Test fun c259_endScenario_closesOpenEpisode() { val sid = openSceneEpisode(); k.host.endScenario(); k.host.awaitIdle(); assertClosed(sid) }
    @Test fun c259_restartAfterSceneDeath_closesOpenEpisode() { val sid = openSceneEpisode(); k.boot(); assertClosed(sid) }

    // C254: 장면 인계 발화가 이미 끝났는데 SpeechDone이 큐에서 endScenario 뒤에 있으면 endScenario가 먼저 그 SpeechDone을 처리한다
    private fun sceneHandoffSpeaking(guardians: Int = 0): String {
        k.profile(); k.phone(); k.boot()
        k.host.startScenario(setup(guardians)); k.host.awaitIdle()
        k.open(); k.alarm(); k.toGap()
        k.ev(CrySegment(0, k.now, k.now + 3_000, 0.9, false), k.now + 3_000); k.toGap()
        k.ev(CrySegment(0, k.now, k.now + 3_000, 0.9, false), k.now + 3_000)
        repeat(6) {   // 앞선 안내(S02·S03)를 끝내며 인계 줄(S10)까지 간다
            val q = k.state().speech
            if (q.speaking != null && q.queue.firstOrNull()?.sid == Sid.S10) return@repeat
            if (q.speaking != null) k.ev(SpeechDone(0, q.speaking!!, true), k.now + 1_000)
            k.ev(Tick(0), k.now + 8_000)
        }
        check(k.state().speech.queue.firstOrNull()?.sid == Sid.S10 && k.state().speech.speaking != null && k.ses().phase == Phase.HANDOFF) { "장면 인계 발화 중이어야 한다" }
        return k.ses().id
    }

    @Test fun c254_endScenario_alreadyFinishedHandoffSpeech_toldTrue() {
        val sid = sceneHandoffSpeaking()
        k.cancelIfSpeakingHook = { true to k.now }   // 실행기는 끝났고 SpeechDone은 아직 큐에 못 왔다
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(true, k.db.storeDao().episodes(sid).single().handoffToldDriver)
    }

    @Test fun c254_endScenario_stillSpeaking_toldFalse() {
        val sid = sceneHandoffSpeaking()
        k.host.endScenario(); k.host.awaitIdle()   // 기본 훅: 아직 말하는 중이라 끊는다
        assertEquals(false, k.db.storeDao().episodes(sid).single().handoffToldDriver)
    }

    // C290: 인계 안내를 다시 골라 행의 종류가 장부와 달라도(C258) 장면 끝은 장면 상태의 알림 장부로 확정한다
    private fun sql(q: String) = k.db.openHelper.writableDatabase.execSQL(q)

    @Test fun c290_pendingAlertRechosenAsArrival_notSent_guardianNotifiedFalse() {
        val sid = sceneHandoffSpeaking(guardians = 1)
        val a = k.state().openLedger()!!
        assertEquals(true, a.alert, "전제: 알림 대기(아직 안 보냄)")
        assertEquals(false, k.db.storeDao().episodes(sid).single().guardianNotified, "결정 ③: 넣기 전에는 거짓")
        sql("UPDATE episode SET handoff_kind = 'ARRIVAL_NOTICE' WHERE session_id = '$sid'")   // 도착 안내로 다시 골라 종류만 바뀐 행
        k.host.endScenario(); k.host.awaitIdle()
        val e = k.db.storeDao().episodes(sid).single()
        assertEquals(false, e.guardianNotified, "보내지 않은 알림을 보냈다고 남기지 않는다"); assertEquals(false, e.handoffToldDriver)
        assertEquals(a.kind.name, e.handoffKind, "장면 상태의 장부 종류로 확정(판단 코어와 같은 규칙)")
    }

    @Test fun c290_sentAlertRechosenAsShelter_guardianNotifiedStaysTrue() {
        val sid = sceneHandoffSpeaking()
        assertEquals(false, k.state().openLedger()!!.alert, "전제: 알림은 이미 보냈거나 대상 아님")
        sql("UPDATE episode SET handoff_kind = 'WAYPOINT_OFFER', guardian_notified = 1 WHERE session_id = '$sid'")   // 이미 보낸 알림
        k.host.endScenario(); k.host.awaitIdle()
        val e = k.db.storeDao().episodes(sid).single()
        assertEquals(true, e.guardianNotified, "보낸 알림을 지우지 않는다"); assertEquals(false, e.handoffToldDriver)
    }

    // C289: 장면 끝의 복원이 던지면 프로세스를 끝낸다. 다시 뜨면 장면 시작 때 저장한 실제 스냅숏에서 복원한다
    @Test fun c289_restoreFailureAtSceneEnd_diesThenNewHostRestores() {
        k.profile(); k.phone(); k.boot(); k.open()
        val real = k.ses().id
        k.host.startScenario(setup()); k.host.awaitIdle()
        sql("ALTER TABLE child_profile RENAME TO child_profile_x")
        val old = Thread.getDefaultUncaughtExceptionHandler(); var died: Throwable? = null; val gate = java.util.concurrent.CountDownLatch(1)
        Thread.setDefaultUncaughtExceptionHandler { _, x -> died = x; gate.countDown() }
        try { k.host.endScenario(); gate.await(3, java.util.concurrent.TimeUnit.SECONDS); k.host.awaitIdle() } finally { Thread.setDefaultUncaughtExceptionHandler(old) }
        assertNotNull(died, "장면 끝 복원 실패는 프로세스를 끝낸다")
        sql("ALTER TABLE child_profile_x RENAME TO child_profile")
        k.boot()
        assertEquals(real, k.ses().id, "실제 스냅숏에서 정상 복원")
    }

    // ---- C328: 장면 끝 복원 Boot의 기어는 실행기가 마지막으로 받은 지금 기어(기준선). 보관한 기어로 내면 reemit의 D가 P→D 출발이 된다 ----
    private fun execGear(g: Gear?) {   // 실행기가 마지막으로 받은 기어(장면이 VC로 바꾼 값). reemitAll은 그 값을 GearChanged로 다시 낸다
        val t = k.host.targets
        k.host.targets = Targets(t.exec, t.link, t.notice, wake = {}, restore = t.restore, reemitAll = { g?.let { k.host.postReal(GearChanged(0, it)) } },
            lastGear = { g }, cancelIfSpeaking = t.cancelIfSpeaking, linkNow = t.linkNow)
    }
    private fun sessionRows() = k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM session").use { it.moveToFirst(); it.getInt(0) }
    private fun reservedInP(): Kit { k.profile(); k.boot(); k.ev(GearChanged(0, Gear.P)).ev(BabyAboard(0, BabyAboardFrom.HOME)); check(k.state().ign.reserved); return k }
    /** 장면 중 실제 차가 D로 간다(postReal → applyReal). C342: 그래야 장면 전 정차가 끝난 사실이 선다. */
    private fun sceneEndingInD(override: Set<String> = emptySet()) {
        execGear(Gear.D); k.host.startScenario(setup(override = override)); k.host.awaitIdle()
        k.host.postReal(GearChanged(0, Gear.D)); k.host.awaitIdle(); k.host.endScenario(); k.host.awaitIdle()
    }

    @Test fun c328_sceneEndsInD_reservedInP_noRealSession_reservationKept() {
        reservedInP(); sceneEndingInD()
        assertNull(k.state().session, "장면이 남긴 D는 실제 출발이 아니다")
        assertEquals(0, sessionRows(), "SaveSession 없음")
        assertEquals(true, k.state().ign.reserved, "예약은 남는다")
        assertEquals(Gear.D, k.state().car.gear, "기준선은 지금 기어")
    }

    @Test fun c328_afterScene_realParkThenDrive_opensSession() {
        reservedInP(); sceneEndingInD()
        k.ev(GearChanged(0, Gear.P)).ev(GearChanged(0, Gear.D))
        assertNotNull(k.state().session, "진짜 출발은 그대로 세션을 연다")
    }

    /** 실제 수유 정차 확인(경유지 넣음 → 첫 P)까지 만든다. */
    private fun realNursingStop() {
        k.profile(); k.boot(); k.open()
        k.ev(RouteState(0, true, 9000, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 9.0, true)), 150.0))
        k.ev(DriverAddWaypoint(0, "n"))
        k.ev(WaypointResult(0, k.exec.filterIsInstance<AddWaypoint>().last().cmdId, "n", true))
        k.ev(GearChanged(0, Gear.P))
        assertNotNull(k.state().ign.nursingStop?.firstPAt, "준비: 실제 수유 정차 확인")
    }

    // C336: 장면이 D로 끝나면 실제 상태는 D라 첫 P 뒤 수유 정차는 끝난 것(F9). 남기면 나중 P에서 지난 정차로 확인·거짓 기록
    @Test fun c336_sceneEndsInD_nursingStopDropped_noStaleConfirm() {
        realNursingStop(); val feeding = k.state().feedingStart
        sceneEndingInD()
        assertNull(k.state().ign.nursingStop, "D로 끝나면 정차 확인을 버린다")
        k.ev(GearChanged(0, Gear.P), k.now + 20 * 60_000L)
        k.ev(Tick(0), k.now + 20 * 60_000L)
        assertTrue(Notice.FEEDING_CONFIRM !in k.notices, "지난 정차로 수유 확인을 띄우지 않는다")
        k.ev(FeedingConfirmed(0, true))
        assertEquals(feeding, k.state().feedingStart, "수유 시각이 바뀌지 않는다")
    }

    // C342: 장면 중 D를 거쳤다가 P로 끝나도 장면 전 정차는 끝난 것
    @Test fun c342_sceneDroveAndEndedInP_nursingStopDropped() {
        realNursingStop(); val feeding = k.state().feedingStart
        execGear(Gear.P); k.host.startScenario(setup()); k.host.awaitIdle()
        k.host.postReal(GearChanged(0, Gear.D)); k.host.postReal(GearChanged(0, Gear.P)); k.host.awaitIdle()
        k.host.endScenario(); k.host.awaitIdle()
        assertNull(k.state().ign.nursingStop, "P로 끝나도 장면 전 정차 확인을 버린다")
        k.ev(Tick(0), k.now + 20 * 60_000L)
        assertTrue(Notice.FEEDING_CONFIRM !in k.notices, "지난 정차로 수유 확인을 띄우지 않는다")
        k.ev(FeedingConfirmed(0, true))
        assertEquals(feeding, k.state().feedingStart, "수유 시각이 바뀌지 않는다")
    }

    // C345: 실행기는 D를 받았지만 그 GearChanged가 처리되기 전에 장면이 끝나도(postReal 없음) 정차는 끝난 것
    @Test fun c345_sceneEndsInD_gearEventNotYetProcessed_nursingStopDropped() {
        realNursingStop()
        execGear(Gear.D); k.host.startScenario(setup()); k.host.awaitIdle(); k.host.endScenario(); k.host.awaitIdle()
        assertNull(k.state().ign.nursingStop, "Boot.gear가 D면 stopEnded와 상관없이 정차 확인을 버린다")
    }

    // C347: 띄운 수유 확인이 답 없이 끝나면(출발) HideNotice가 notice 대상의 cancel로 간다
    @Test fun c347_hideNoticeRoutedToNoticeCancel() {
        realNursingStop()
        k.ev(Tick(0), k.now + 16 * 60_000L)
        assertTrue(Notice.FEEDING_CONFIRM in k.notices, "준비: 확인이 떴다")
        k.ev(GearChanged(0, Gear.D))
        assertEquals(listOf(Notice.FEEDING_CONFIRM), k.hiddenNotices)
    }

    // C349: 실제 수유 확인은 장면 중 내려 두고(상태 유지) 장면 끝에 다시 띄우며, 그때 '네'가 실제 수유 시작으로 저장된다
    @Test fun c349_realConfirm_hiddenDuringScene_reshownAfter_answerSaved() {
        realNursingStop()
        k.ev(Tick(0), k.now + 16 * 60_000L)
        assertTrue(k.state().ign.nursingStop!!.confirmShown, "준비: 실제 확인이 떴다")
        assertEquals(1, k.notices.count { it == Notice.FEEDING_CONFIRM })
        val before = k.state().feedingStart
        k.host.startScenario(setup()); k.host.awaitIdle()
        assertEquals(listOf(Notice.FEEDING_CONFIRM), k.hiddenNotices, "장면 시작이 실제 확인 알림을 내린다")
        k.host.endScenario(); k.host.awaitIdle()
        assertEquals(2, k.notices.count { it == Notice.FEEDING_CONFIRM }, "장면 끝에 다시 띄운다")
        assertTrue(k.state().ign.nursingStop!!.confirmShown)
        k.ev(FeedingConfirmed(0, true))
        assertNotEquals(before, k.state().feedingStart, "네 → 실제 수유 시작이 저장된다")
        assertNull(k.state().ign.nursingStop)
    }

    // C342: 장면을 D 전환 전에 멈춰 차가 계속 P면 수유 정차는 이어진다
    @Test fun c342_sceneStoppedBeforeDrive_nursingStopKept() {
        realNursingStop(); val pAt = k.state().ign.nursingStop!!.firstPAt
        execGear(Gear.P); k.host.startScenario(setup()); k.host.awaitIdle(); k.host.endScenario(); k.host.awaitIdle()
        assertEquals(pAt, k.state().ign.nursingStop?.firstPAt, "기어 사건이 없었으면 정차 확인이 남는다")
        k.ev(Tick(0), k.now + 16 * 60_000L)
        assertTrue(Notice.FEEDING_CONFIRM in k.notices, "정차 15분 뒤 수유 확인이 뜬다")
    }

    // C342: override에 GearChanged가 든 장면이어도 실제 차가 P를 벗어난 사실은 선다
    @Test fun c342_overrideGearChanged_stillMarksLeftPark() {
        realNursingStop()
        sceneEndingInD(override = setOf("GearChanged"))
        assertNull(k.state().ign.nursingStop, "override로 버려져도 표시는 선다")
    }

    // 프로세스 재시작 Boot(RestoreUseCase.boot)는 기어를 모른다(null). 정차 확인은 남기고 실행기의 첫 GearChanged가 정한다(결정 ①, C336)
    @Test fun c336_restartInD_dropsNursingStop() {
        realNursingStop()
        k.boot()
        k.ev(GearChanged(0, Gear.D))   // 앞 기어 모름 → D: gear()의 F9가 버린다
        assertNull(k.state().ign.nursingStop)
    }

    @Test fun c336_restartInP_keepsNursingStop() {
        realNursingStop(); val pAt = k.state().ign.nursingStop!!.firstPAt
        k.boot()
        assertEquals(pAt, k.state().ign.nursingStop?.firstPAt, "기어를 모르는 Boot는 남긴다")
        k.ev(GearChanged(0, Gear.P))
        assertEquals(pAt, k.state().ign.nursingStop?.firstPAt)
    }

    @Test fun c328_executorNeverReceivedGear_bootWithKeptGear() {
        reservedInP()
        execGear(null); k.host.startScenario(setup()); k.host.awaitIdle(); k.host.endScenario(); k.host.awaitIdle()
        assertEquals(Gear.P, k.state().car.gear, "받은 적이 없으면 보관한 기어")
        assertNull(k.state().session)
    }
}
