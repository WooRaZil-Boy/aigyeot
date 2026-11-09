package kr.aigyeot.app.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.aigyeot.app.store.db.AlarmLogEntity
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.app.store.db.consentChecked
import kr.aigyeot.app.store.db.needsSetup
import kr.aigyeot.app.store.db.ContextSampleEntity
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.app.store.db.EpisodeEntity
import kr.aigyeot.app.store.db.PlayLogEntity
import kr.aigyeot.app.store.db.SessionEntity
import kr.aigyeot.app.store.db.SteerWindowEntity
import kr.aigyeot.engine.*
import kr.aigyeot.shared.SteeringUnavailableReason
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.aigyeot.engine.fresh

private const val T0 = 1_800_000_000_000L

@RunWith(RobolectricTestRunner::class)
class StoreTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.storeDao()
    private var now = T0
    private val store = Store(db, { now })

    @Test fun c185_reportChanges_emitsOnSessionUpdate_andPendingReadable() {
        session()
        assertEquals(true, dao.reportPending("S1"))
        val seen = java.util.concurrent.atomic.AtomicInteger()
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { dao.reportChanges("S1").collect { seen.incrementAndGet() } }
        val end = System.currentTimeMillis() + 5000
        while (seen.get() < 1 && System.currentTimeMillis() < end) Thread.sleep(20)
        session(pending = false)        // 리포트가 만들어지면 다시 내서 화면이 바뀐다
        while (seen.get() < 2 && System.currentTimeMillis() < end) Thread.sleep(20)
        job.cancel()
        assertTrue(seen.get() >= 2, "seen=${seen.get()}")
        assertEquals(false, dao.reportPending("S1"))
    }

    // ---- C262: 부모 세션이 없는 자식 쓰기는 입구에서 버린다 ----
    private fun rec(id: String = "E9", sid: String = "GONE") = EpisodeRecord(id, sid, T0, null, EpisodeTrigger.ALARM, T0, 2_400, null, null, null, null, null, null, null, null, null, null, false, cryMs = 0, recried = false, playedMs = 0, excludeReasons = emptyList())

    @Test fun c262_childWritesWithoutParentSession_dropped() {
        phone()
        store.applyAllTx(listOf(   // 묶음 트랜잭션: 버리지 않으면 외래키 오류가 던져진다(C173이면 프로세스 종료)
            SaveEpisode(rec()),
            SavePlay(PlayRecord("P9", "GONE", "E9", PlayPurpose.AUTO_FIRST, MethodKind.LULLABY, null, T0, T0 + 1, 1, PlayEndReason.DEADLINE)),
            LogAlarm("GONE", T0, AlarmOnly.WAITING), CaptureContext("E9", T0),
            SaveSteerWindow(SteerWindowRecord("GONE", T0, 1, false, false, false, 0)), CloseSession("GONE")))
        store.logControl(ControlLogEntity(sessionId = "GONE", at = T0, api = "a", target = "t", goal = "g", actual = null, ackMs = null, appliedMs = null, ok = true, retries = 0))
        listOf("session", "episode", "play_log", "alarm_log", "context_sample", "steer_window", "control_log").forEach { assertEquals(0, count("SELECT COUNT(*) FROM $it"), it) }
    }

    @Test fun c262_closeSessionOfGoneSession_noReportNoRow() {
        phone()
        store.handle(CloseSession("GONE"))
        assertEquals(0, count("SELECT COUNT(*) FROM outbox")); assertEquals(0, count("SELECT COUNT(*) FROM session"))
    }

    @Test fun c262_newSessionFirstWriteInSameTransaction_saved() {
        store.applyAllTx(listOf(
            SaveSession(SessionRecord("N1", T0, null, OpenTrigger.DOOR_THEN_D, MicPath.HOME_BUTTON, null, 0, 0, false, "[]", null, null, false, null, 10)),
            SaveEpisode(rec("EN", "N1")), LogAlarm("N1", T0, AlarmOnly.WAITING)))
        assertEquals(1, count("SELECT COUNT(*) FROM episode")); assertEquals(1, count("SELECT COUNT(*) FROM alarm_log"))
    }

    @Test fun c262_contextRowsOfGoneEpisode_droppedAtEntry_otherEpisodeKept() {
        session(); store.handle(SaveEpisode(rec("E1", "S1"))); store.handle(SaveEpisode(rec("E2", "S1")))
        val s = EngineState.fresh(1, null, emptyList(), emptyList(), null, 0).let { it.copy(car = it.car.copy(gear = Gear.D)) }
        var t = T0
        while (t <= T0 + 5_000) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), s); t += 100 }
        store.handle(CaptureContext("E1", T0 + 5_000)); store.handle(CaptureContext("E2", T0 + 5_000))
        sql("DELETE FROM episode WHERE id = 'E1'")   // 지운 에피소드
        while (t <= T0 + 8_000) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), s); t += 100 }
        assertEquals(0, count("SELECT COUNT(*) FROM context_sample WHERE episode_id = 'E1'"))
        assertTrue(count("SELECT COUNT(*) FROM context_sample WHERE episode_id = 'E2' AND at > ${T0 + 5_500}") >= 3, "다른 에피소드 줄은 잃지 않는다")
    }

    @Test fun c274_observeParentLookupFailure_isSwallowed() {
        session(); store.handle(SaveEpisode(rec("E1", "S1")))
        val s = EngineState.fresh(1, null, emptyList(), emptyList(), null, 0).let { it.copy(car = it.car.copy(gear = Gear.D)) }
        var t = T0
        while (t <= T0 + 5_000) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), s); t += 100 }
        store.handle(CaptureContext("E1", T0 + 5_000))
        sql("ALTER TABLE episode RENAME TO episode_gone")   // 부모 조회가 SQLiteException
        while (t <= T0 + 8_000) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), s); t += 100 }   // 던지지 않는다
    }

    @After fun close() = db.close()

    private fun sql(q: String) = db.openHelper.writableDatabase.execSQL(q)
    private fun count(q: String) = db.openHelper.readableDatabase.query(q).use { it.moveToFirst(); it.getInt(0) }
    private fun phone() = sql("INSERT INTO phone_link (id, platform, role, linked_at, last_seen_at, handoff_alerts) VALUES ('d1', 'android', 'owner', $T0, NULL, 0)")

    private fun session(id: String = "S1", ended: Long? = T0 + 600_000, route: OutputRoute? = OutputRoute.REAR_ZONE, pending: Boolean = true) =
        store.handle(SaveSession(SessionRecord(id, T0, ended, OpenTrigger.DOOR_THEN_D, MicPath.HOME_BUTTON, route, 10, 56_000, false, "[]", null, null, pending, null, 10)))

    /** 한 에피소드: 경보 → 자장가 56초 → 성공. 맥락·조향·제어 기록까지 채운다. */
    private fun fullSession() {
        session()
        dao.upsertEpisode(EpisodeEntity("E1", "S1", T0 + 60_000, T0 + 200_000, "ALARM", T0 + 60_000, 2_400, "LULLABY", null, "SUCCESS", null,
            null, null, null, null, null, null, false, false, 61_000, false, 56_000, emptyList()))
        dao.upsertPlay(PlayLogEntity("P1", "S1", "E1", "AUTO_FIRST", "LULLABY", null, T0 + 63_000, T0 + 119_000, 56_000, "DEADLINE"))
        dao.insertContext((0..60).map { i -> ContextSampleEntity("E1", T0 + 30_000 + i * 1_000L, 50.0 + i % 3, i == 5, if (i % 10 < 5) 0.0 else 5.0, "NONE", "D", 24.5, null, null, null) })
        dao.upsertSteer(SteerWindowEntity("S1", T0, 2, false, true, false, 4))
        dao.insertControl(ControlLogEntity(sessionId = "S1", at = T0 + 10_000, api = "Seat.setHvacSeatTemperature", target = "ROW_2_LEFT", goal = "0", actual = "0", ackMs = 120, appliedMs = 300, ok = true, retries = 0))
    }

    @Test fun reportFields_matchContractAndSharedExample() {
        fullSession()
        val ours = Json.parseToJsonElement(encodeReport(buildReport(dao, "S1", now)!!)).jsonObject
        val example = Json.parseToJsonElement(File("../shared/src/test/resources/protocol/report_summary.json").readText()).jsonObject["body"]!!.jsonObject
        assertSameShape(example, ours, "body")
        assertEquals("cry_alarm", ours.str("timeline", "trigger")); assertEquals("success", ours.str("timeline", "result"))
        assertEquals("2026-01-15T17:01:00+09:00".length, ours["started_at"].toString().trim('"').length, "시각은 +09:00 ISO 초 단위")
        assertTrue(ours["started_at"].toString().endsWith("+09:00\""))
    }

    @Test fun c118_reportSound_womb_mapsToWombNotParentSong() {
        session()
        dao.upsertEpisode(EpisodeEntity("E1", "S1", T0 + 60_000, T0 + 200_000, "ALARM", T0 + 60_000, 2_400, "WOMB", null, "SUCCESS", null,
            null, null, null, null, null, null, false, false, 61_000, false, 56_000, emptyList()))
        dao.upsertPlay(PlayLogEntity("P1", "S1", "E1", "AUTO_FIRST", "WOMB", null, T0 + 63_000, T0 + 119_000, 56_000, "DEADLINE"))
        val body = encodeReport(buildReport(dao, "S1", now)!!)
        assertTrue("\"sound\":{\"method\":\"womb\",\"label\":\"뱃속 소리\"}" in body, body)
        assertTrue("\"method\":\"womb\",\"song_id\":null" in body, body)   // plays[] 항목
        assertTrue("parent_song" !in body, body)
    }

    @Test fun c118_oldProfileWithoutWomb_stillUsesWomb() {
        val fixed = listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.WOMB), Method(MethodKind.LULLABY))
        dao.upsertProfile(kr.aigyeot.app.store.db.ChildProfileEntity("c", "콩이", "2026-03", "LEFT", null, null,
            listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY), Method(MethodKind.SONG)), false, false, T0, kr.aigyeot.app.store.db.AI_NOTICE_VERSION))
        sql("INSERT OR REPLACE INTO song (id, name, received_at, format, duration_s, lufs, normalized_path, status, reject_reason, added_seq, last_seq) VALUES ('a', 'a', $T0, 'AAC', 60.0, -30.0, NULL, 'ACTIVE', NULL, 1, 1)")
        assertEquals(fixed + Method(MethodKind.SONG, "a"), store.methods(), "옛 프로필(SONG 켬): 뱃속 소리가 둘째")
        dao.upsertProfile(kr.aigyeot.app.store.db.ChildProfileEntity("c", "콩이", "2026-03", "LEFT", null, null,
            listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)), false, false, T0, kr.aigyeot.app.store.db.AI_NOTICE_VERSION))
        assertEquals(fixed, store.methods(), "옛 프로필(SONG 끔): 곡이 있어도 ③ 없음")
    }

    // C122: AI 전송 안내 판
    @Test fun c122_needsSetup_andConsentInitial() {
        val cur = profile(null)
        val old = cur.copy(aiNoticeVersion = 0)
        assertTrue(null.needsSetup() && old.needsSetup() && !cur.needsSetup(), "없음·판 0은 설정 화면, 지금 판은 홈")
        assertTrue(!null.consentChecked() && !old.consentChecked() && cur.consentChecked(), "동의 체크 초기값은 지금 판만 참")
    }

    @Test fun c122_migration1to2_keepsRows_versionZero() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun helper(version: Int, cb: androidx.sqlite.db.SupportSQLiteOpenHelper.Callback) = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
            .create(androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx).name("mig.db").callback(cb).build())
        val v1 = helper(1, object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE child_profile (id TEXT NOT NULL PRIMARY KEY, nickname TEXT NOT NULL, consent_at INTEGER NOT NULL)")
                db.execSQL("INSERT INTO child_profile VALUES ('c', '콩이', 5)")
                db.execSQL("CREATE TABLE session (id TEXT NOT NULL PRIMARY KEY, scenario_id TEXT, ended_at INTEGER)")   // C248·C306: 이전이 session·handoff_kind를 읽는다
                db.execSQL("CREATE TABLE episode (id TEXT NOT NULL PRIMARY KEY, session_id TEXT, handoff_kind TEXT)")
                db.execSQL("CREATE TABLE steer_window (session_id TEXT NOT NULL, start_at INTEGER NOT NULL, PRIMARY KEY(session_id, start_at))")   // C312: 이전이 observed 칸을 더한다
                db.execSQL("INSERT INTO episode (id) VALUES ('E1')")
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, o: Int, n: Int) {}
        })
        v1.writableDatabase; v1.close()
        val v2 = helper(2, object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, o: Int, n: Int) = kr.aigyeot.app.store.db.MIGRATION_1_2.migrate(db)
        })
        v2.writableDatabase.query("SELECT nickname, ai_notice_version FROM child_profile").use {
            assertTrue(it.moveToFirst()); assertEquals("콩이", it.getString(0)); assertEquals(0, it.getInt(1))
        }
        // C130: episode 행이 남고 새 두 칸은 null
        v2.writableDatabase.query("SELECT id, handoff_rest_name, handoff_told_driver FROM episode").use {
            assertTrue(it.moveToFirst()); assertEquals("E1", it.getString(0)); assertTrue(it.isNull(1) && it.isNull(2))
        }
        v2.close()
    }

    @Test fun h11_handoffColumns_storedAndReportedInStop() {
        session()
        val rec = kr.aigyeot.engine.EpisodeRecord("E1", "S1", T0 + 60_000, T0 + 200_000, kr.aigyeot.engine.EpisodeTrigger.ALARM, T0 + 60_000, 2_400,
            null, null, null, null, null, null, null, null, kr.aigyeot.engine.HandoffCause.TWO_ATTEMPTS, kr.aigyeot.engine.HandoffKind.WAYPOINT_OFFER,
            false, 61_000, false, 56_000, emptyList())
        store.handle(kr.aigyeot.engine.SaveEpisode(rec)); store.handle(SettleHandoff("E1", HandoffKind.WAYPOINT_OFFER, true, "n 휴게소"))
        assertEquals("n 휴게소", dao.episode("E1")!!.handoffRestName); assertEquals(true, dao.episode("E1")!!.handoffToldDriver)
        var body = encodeReport(buildReport(dao, "S1", now)!!)
        assertTrue("\"rest_name\":\"n 휴게소\"" in body && "\"told_driver\":true" in body, body)
        store.handle(SettleHandoff("E1", HandoffKind.WAYPOINT_OFFER, false, null))   // 닫힌 뒤 갱신
        body = encodeReport(buildReport(dao, "S1", now)!!)
        assertTrue("\"told_driver\":false" in body && "\"rest_name\":\"n 휴게소\"" !in body, body)
    }

    @Test fun c132_arrivalNoticeCount_excludesToldDriverFalse() {
        session(pending = false)   // C305: null이 있으면 확정 전이라 finalizing으로 센다(null은 참으로 봄)
        fun ep(id: String, told: Boolean?) {
            store.handle(kr.aigyeot.engine.SaveEpisode(kr.aigyeot.engine.EpisodeRecord(id, "S1", T0 + 60_000, T0 + 200_000, kr.aigyeot.engine.EpisodeTrigger.ALARM, T0 + 60_000, 2_400,
                null, null, null, null, null, null, null, null, kr.aigyeot.engine.HandoffCause.TWO_ATTEMPTS, kr.aigyeot.engine.HandoffKind.ARRIVAL_NOTICE,
                false, 61_000, false, 56_000, emptyList())))
            if (told != null) store.handle(SettleHandoff(id, HandoffKind.ARRIVAL_NOTICE, told, null))
        }
        ep("E1", false); ep("E2", true); ep("E3", null)
        val n = buildReport(dao, "S1", now, finalizing = true)!!.carActions.first { it.action == kr.aigyeot.shared.CarActionKind.ARRIVAL_NOTICE }.count
        assertEquals(2, n, "false는 세지 않고 true·null만 센다")
    }

    // C189 ①: 리포트 확정 전(report_pending)에는 toldDriver가 true인 도착 안내만 센다
    @Test fun c189_pendingArrivalNotCounted() {
        session()
        store.handle(kr.aigyeot.engine.SaveEpisode(kr.aigyeot.engine.EpisodeRecord("E1", "S1", T0 + 60_000, T0 + 200_000, kr.aigyeot.engine.EpisodeTrigger.ALARM, T0 + 60_000, 2_400,
            null, null, null, null, null, null, null, null, kr.aigyeot.engine.HandoffCause.TWO_ATTEMPTS, kr.aigyeot.engine.HandoffKind.ARRIVAL_NOTICE,
            false, 61_000, false, 56_000, emptyList())))
        fun n() = buildReport(dao, "S1", now)!!.carActions.firstOrNull { it.action == kr.aigyeot.shared.CarActionKind.ARRIVAL_NOTICE }?.count ?: 0
        assertEquals(0, n(), "toldDriver null이면 세지 않는다(S15 발화 중)")
        assertEquals(1, buildReport(dao, "S1", now, finalizing = true)!!.carActions.first { it.action == kr.aigyeot.shared.CarActionKind.ARRIVAL_NOTICE }.count, "C305: 리포트를 만들 때(finalizing)는 null을 참으로 본다")
    }

    // C305: 인계 확정은 에피소드 행에서만 정한다(report_pending 아님)
    private fun handoffEp(id: String, kind: kr.aigyeot.engine.HandoffKind, told: Boolean?) {
        store.handle(kr.aigyeot.engine.SaveEpisode(kr.aigyeot.engine.EpisodeRecord(id, "S1", T0 + 60_000, T0 + 200_000, kr.aigyeot.engine.EpisodeTrigger.ALARM, T0 + 60_000, 2_400,
            null, null, null, null, null, null, null, null, kr.aigyeot.engine.HandoffCause.TWO_ATTEMPTS, kind,
            false, 61_000, false, 56_000, emptyList())))
        db.linkDao().markGuardianNotified(id)   // 결정 ③: 알림을 보낼 함에 넣은 행(link만 쓴다)
        if (told != null) store.handle(SettleHandoff(id, kind, told, null))
    }
    private fun arrivalCount(finalizing: Boolean = false) = buildReport(dao, "S1", now, finalizing = finalizing)!!.carActions.firstOrNull { it.action == kr.aigyeot.shared.CarActionKind.ARRIVAL_NOTICE }?.count ?: 0

    @Test fun c305_sceneSession_unspokenHandoff_showsSettling() {
        session(pending = false)   // 장면 세션은 report_pending이 늘 거짓
        handoffEp("E1", kr.aigyeot.engine.HandoffKind.ARRIVAL_NOTICE, null)
        assertTrue(readReport(db, "S1") { buildReport(it, "S1", now) }.second, "말하지 못한 인계가 있으면 정리 중")
        assertEquals(0, arrivalCount(), "도착 안내를 세지 않는다")
        assertEquals(0, buildReport(dao, "S1", now)!!.carActions.count { it.action == kr.aigyeot.shared.CarActionKind.GUARDIAN_ALERT && it.count > 0 }, "알림도 세지 않는다")
    }

    @Test fun c305_realSession_reportOutboxFailed_settledRowsShowFinal() {
        session(pending = true)   // 리포트 보낼 함 저장 실패로 report_pending이 남음
        handoffEp("E1", kr.aigyeot.engine.HandoffKind.ARRIVAL_NOTICE, true)
        assertEquals(false, readReport(db, "S1") { buildReport(it, "S1", now) }.second, "인계가 확정이면 정리 중이 아니다")
        assertEquals(1, arrivalCount(), "값이 그대로 그려진다")
    }

    @Test fun c305_parkedHandoff_nullToldIsSettled() {
        session(pending = false)
        handoffEp("E1", kr.aigyeot.engine.HandoffKind.PARKED, null)
        assertEquals(false, readReport(db, "S1") { buildReport(it, "S1", now) }.second)
    }

    // C220: 확정 전(report_pending)에는 보호자 알림 횟수에 세지 않는다
    @Test fun c220_pendingSessionNotCountedInCarActions() {
        session()
        store.handle(kr.aigyeot.engine.SaveEpisode(kr.aigyeot.engine.EpisodeRecord("E1", "S1", T0 + 60_000, T0 + 200_000, kr.aigyeot.engine.EpisodeTrigger.ALARM, T0 + 60_000, 2_400,
            null, null, null, null, null, null, null, null, kr.aigyeot.engine.HandoffCause.TWO_ATTEMPTS, kr.aigyeot.engine.HandoffKind.ARRIVAL_NOTICE,
            false, 61_000, false, 56_000, emptyList())))
        db.linkDao().markGuardianNotified("E1")   // 결정 ③: 보낼 함에 넣은 알림
        fun n(fin: Boolean = false) = buildReport(dao, "S1", now, finalizing = fin)!!.carActions.firstOrNull { it.action == kr.aigyeot.shared.CarActionKind.GUARDIAN_ALERT }?.count ?: 0
        assertEquals(0, n(), "확정 전이면 알림이 나갔는지 모른다")
        assertEquals(1, n(true), "확정되면 센다")
    }

    // C189 ②: 본문과 report_pending을 한 트랜잭션 안에서 함께 읽는다
    @Test fun c189_bodyAndPendingReadTogether() {
        session()
        handoffEp("E1", kr.aigyeot.engine.HandoffKind.ARRIVAL_NOTICE, null)   // C305: 확정 전 인계 행이 있으면 정리 중
        var inTx = false
        val (b, pending) = readReport(db, "S1") { inTx = db.inTransaction(); buildReport(it, "S1", now) }
        assertTrue(inTx, "build가 트랜잭션 안에서 돈다")
        assertTrue(b != null && pending)
        handoffEp("E1", kr.aigyeot.engine.HandoffKind.ARRIVAL_NOTICE, true)
        assertEquals(false, readReport(db, "S1") { buildReport(it, "S1", now) }.second)
    }

    @Test fun c122_saveWritesCurrentVersion_dbDefaultIsCurrent() {
        dao.upsertProfile(profile(null).copy(aiNoticeVersion = 0))
        assertEquals(0, dao.profile()!!.aiNoticeVersion)
        dao.upsertProfile(profile(null))
        assertEquals(kr.aigyeot.app.store.db.AI_NOTICE_VERSION, dao.profile()!!.aiNoticeVersion)
    }

    @Test fun c118_methods_defaultWithoutProfileHasWombSecond() {
        assertEquals(listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.WOMB), Method(MethodKind.LULLABY)), store.methods(), "프로필 없음 + ③ 없음")
    }

    /** 예제에 있는 키가 같은 순서로 모두 있는지(배열은 첫 원소끼리). 값의 null 여부는 보지 않는다. */
    private fun assertSameShape(want: JsonElement, got: JsonElement?, path: String) {
        when (want) {
            is JsonObject -> {
                val g = got as? JsonObject ?: return assertTrue(got.toString() == "null", "$path: 객체가 아님")
                assertEquals(want.keys.toList(), g.keys.toList(), "$path 키 순서")
                want.forEach { (key, v) -> assertSameShape(v, g[key], "$path.$key") }
            }
            is JsonArray -> (got as? JsonArray)?.firstOrNull()?.let { g -> want.firstOrNull()?.let { assertSameShape(it, g, "$path[0]") } }
            else -> {}
        }
    }

    private fun JsonObject.str(arr: String, key: String) = (this[arr] as JsonArray)[0].jsonObject[key].toString().trim('"')

    @Test fun r13_noPlayback_outputRouteNull() {
        session(route = null)
        val body = encodeReport(buildReport(dao, "S1", now)!!)
        assertTrue("\"output_route\":\"no_playback\"" in body)
    }

    // C335: 리포트는 되돌림 결과(control_log) 뒤에 만든다. 실패한 되돌림 줄도 controls 합계에 들어간다
    @Test fun c335_closeSession_reportIncludesFailedRestoreRow() {
        phone(); fullSession()
        store.logControl(ControlLogEntity(sessionId = "S1", at = T0 + 20_000, api = "setWindowLock", target = "ROW_2_LEFT", goal = "0", actual = "1", ackMs = null, appliedMs = null, ok = false, retries = 1))
        store.handle(CloseSession("S1"))
        val body = db.linkDao().outbox().single { it.type == "report_summary" }.bodyJson
        assertTrue("\"failed\":1" in body.replace(" ", ""), "실패한 되돌림이 controls에 남는다: $body")
    }

    @Test fun closeSession_reportToOutboxAndPendingFalse() {
        phone(); fullSession()
        store.handle(CloseSession("S1"))
        val s = dao.session("S1")!!
        assertEquals(false, s.reportPending)
        assertEquals(1, count("SELECT COUNT(*) FROM outbox WHERE type = 'report_summary' AND id = '${s.reportMessageId}'"))
        store.handle(CloseSession("S1"))   // 다시 와도 한 번만
        assertEquals(1, count("SELECT COUNT(*) FROM outbox"))
    }

    /** L82: 지우기 drop_ids는 늦게 닿을 수 있는 것(시도한 적 있는 것·맨 앞)만. 보낼 함 상한은 없다. */
    private fun fillReports(n: Int) {
        for (i in 0 until n) sql("INSERT INTO outbox (id, type, to_device, body_json, created_at, attempts, next_try_at) VALUES ('R${"%04d".format(i)}', 'report_summary', NULL, '{}', $T0, 0, 0)")
    }
    private fun dropIds(): List<String> {
        val body = db.openHelper.readableDatabase.query("SELECT body_json FROM outbox WHERE type = 'records_clear'").use { it.moveToFirst(); it.getString(0) }
        return (Json.parseToJsonElement(body).jsonObject["drop_ids"] as JsonArray).map { it.jsonPrimitive.content }
    }
    private fun clear() = store.clearRecords(EngineState.fresh(1, null, emptyList(), emptyList(), null, 0))

    @Test fun clearRecords_dropIdsOnlyAttempted() {
        sql("INSERT INTO outbox (id, type, to_device, body_json, created_at, attempts, next_try_at) VALUES ('SONG', 'song_selected', NULL, '{}', $T0, 0, 0)")
        fillReports(3)
        sql("UPDATE outbox SET attempts = 2 WHERE id = 'R0001'")   // 시도한 적 있는 것
        clear()
        assertEquals(listOf("R0001"), dropIds(), "보낸 적 없는 R0000·R0002는 늦게 도착할 수 없어 담지 않는다")
        assertEquals(0, count("SELECT COUNT(*) FROM outbox WHERE type = 'report_summary'"))
    }

    @Test fun clearRecords_inFlightFront_inDropIds() {
        fillReports(3)   // 맨 앞 R0000은 첫 전송 중일 수 있다(시도 칸은 실패 때만 오른다)
        clear()
        assertEquals(listOf("R0000"), dropIds())
    }

    private fun sentId(id: String, at: Long) = sql("INSERT INTO sent_id (id, sent_at) VALUES ('$id', $at)")

    // L84: 보내는 중에 7일 만료가 보낼 함에서 지운 리포트도, 10분 안에 보낸 것이면 drop_ids에 든다. 10분 지난 것은 안 든다
    @Test fun l84_expiredInFlightReport_stillInDropIds() {
        fillReports(2)                                        // R0000은 맨 앞(L82), R0001은 안 보냄
        sentId("GONE", now - 60_000)                          // 보낸 뒤 보낼 함에서 지워짐
        sentId("R0000", now - 30_000)                         // 보낼 함에도 있다: 중복 없음
        sentId("OLD", now - kr.aigyeot.app.link.SENT_ID_KEEP_MS - 1)
        clear()
        assertEquals(listOf("R0000", "GONE"), dropIds().sortedByDescending { it == "R0000" })
        assertEquals(2, dropIds().size)
    }

    @Test fun clearRecords_manyUnsent_dropIdsSmall() {
        phone(); fullSession()
        fillReports(1500)
        sql("UPDATE outbox SET attempts = 1 WHERE id = 'R0000'")
        store.handle(CloseSession("S1"))   // 상한 없이 1,501개
        assertEquals(1501, count("SELECT COUNT(*) FROM outbox WHERE type = 'report_summary'"))
        clear()
        assertEquals(listOf("R0000"), dropIds())
    }

    @Test fun closeSession_noPhone_noOutboxButPendingCleared() {
        fullSession()
        store.handle(CloseSession("S1"))
        assertEquals(0, count("SELECT COUNT(*) FROM outbox"))
        assertEquals(false, dao.session("S1")!!.reportPending)
    }

    private fun ep(id: String, sid: String, kind: String?, told: Boolean?) = EpisodeEntity(id, sid, T0 + 60_000, T0 + 200_000, "ALARM", T0 + 60_000, 2_400, "LULLABY", null, "FAIL", null,
        null, null, null, null, if (kind == null) null else "SESSION_CAP", kind, false, false, 61_000, false, 56_000, emptyList(), handoffToldDriver = told)

    private fun scenarioSession(id: String, ended: Long?) {
        val sc = Store(db, { now }, scenarioId = { "A" })
        sc.handle(SaveSession(SessionRecord(id, T0, ended, OpenTrigger.DOOR_THEN_D, null, null, 0, 0, false, "[]", null, null, false, null, 10)))
    }

    // C242 (a): 장면 세션의 확정 안 된 인계는 닫기 뒤 거짓이고 리포트 stop.told_driver도 거짓(열린 세션·이미 닫힌 세션 모두)
    @Test fun c242_closeScenarioSessions_settlesUnknownHandoffToFalse() {
        scenarioSession("SC1", null); scenarioSession("SC2", T0 + 5_000)
        dao.upsertEpisode(ep("E1", "SC1", "WAYPOINT_OFFER", null)); dao.upsertEpisode(ep("E2", "SC2", "ARRIVAL_NOTICE", null))
        store.closeScenarioSessions(T0 + 9_000)
        assertEquals(false, dao.episodes("SC1").single().handoffToldDriver)
        assertEquals(false, dao.episodes("SC2").single().handoffToldDriver)
        assertEquals(false, buildReport(dao, "SC1", now)!!.timeline.single().stop!!.toldDriver)
    }

    // C242 (b): PARKED·이미 참/거짓·인계 없음·실제 세션 행은 그대로
    @Test fun c242_closeScenarioSessions_leavesOtherRows() {
        scenarioSession("SC1", null); session("S1")
        dao.upsertEpisode(ep("P", "SC1", "PARKED", null)); dao.upsertEpisode(ep("T", "SC1", "NO_REST", true))
        dao.upsertEpisode(ep("F", "SC1", "NO_REST", false)); dao.upsertEpisode(ep("N", "SC1", null, null))
        dao.upsertEpisode(ep("R", "S1", "WAYPOINT_OFFER", null))
        store.closeScenarioSessions(T0 + 9_000)
        fun told(id: String) = dao.episodes(if (id == "R") "S1" else "SC1").single { it.id == id }.handoffToldDriver
        assertEquals(listOf(null, true, false, null, null), listOf("P", "T", "F", "N", "R").map(::told))
    }

    // C292: 첫 시도만 실패하게 한다(트리거는 재시도 사이에 dbPause가 지운다, C302)
    private fun failOnce(table: String, act: () -> Unit) {
        sql("CREATE TRIGGER fail_once_u BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT, 'x'); END")
        sql("CREATE TRIGGER fail_once_i BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT, 'x'); END")
        var pauses = 0
        val orig = dbPause
        dbPause = { pauses++; sql("DROP TRIGGER fail_once_u"); sql("DROP TRIGGER fail_once_i") }
        try { act() } finally { dbPause = orig }
        assertEquals(1, pauses, "재시도가 한 번 일어났다")
    }

    @Test fun c292_settleSceneHandoffs_retriesAfterOneFailure() {
        scenarioSession("SC1", null)
        dao.upsertEpisode(ep("A", "SC1", "ARRIVAL_NOTICE", null).copy(guardianNotified = true))
        val a = HandoffLedger("SC1", "A", T0, HandoffCause.TWO_ATTEMPTS, HandoffKind.ARRIVAL_NOTICE, null, alert = true)
        failOnce("episode") { store.settleSceneHandoffs(listOf(a)) }
        val e = dao.episodes("SC1").single()
        assertEquals(true, e.guardianNotified, "결정 ③: 확정은 guardian_notified를 쓰지 않는다(link만)"); assertEquals(false, e.handoffToldDriver)
    }

    /**
     * C303 A9: 장면 끝 인계 확정은 판단 코어 버림 확정과 같은 함수라 장부의 종류를 행에 쓴다. 장면에서 S08(WAYPOINT_OFFER)을 기다리는 동안 경로가 바뀌어
     * 다시 고르기가 S10(NO_REST)으로 바꿨는데(rebind: 장부만 바뀐다) 말하기 전에 장면이 끝났다 → 행도 NO_REST·말하지 못함(차 리포트 "안전한 곳에 세우도록 안내하지 못했어요").
     */
    @Test fun c303_a9_sceneEndSettleWritesKind() {
        scenarioSession("SC1", null)
        dao.upsertEpisode(ep("A", "SC1", "WAYPOINT_OFFER", null).copy(guardianNotified = true))
        val a = HandoffLedger("SC1", "A", T0, HandoffCause.TWO_ATTEMPTS, HandoffKind.NO_REST, null, alert = true)
        store.settleSceneHandoffs(listOf(a))
        val e = dao.episodes("SC1").single()
        assertEquals("NO_REST", e.handoffKind, "다시 고른 장부의 종류")
        assertEquals(false, e.handoffToldDriver); assertEquals(true, e.guardianNotified, "결정 ③: 확정은 guardian_notified를 쓰지 않는다(link만)")
        store.closeScenarioSessions(T0 + 9_000)   // 뒤따르는 쿼리 짐작이 덮지 않는다
        assertEquals("NO_REST", dao.episodes("SC1").single().handoffKind)
    }

    @Test fun c292_closeScenarioSessions_retriesAfterOneFailure() {
        scenarioSession("SC1", null)
        failOnce("session") { store.closeScenarioSessions(T0 + 9_000) }
        assertEquals(T0 + 9_000, dao.session("SC1")!!.endedAt)
    }

    // C259: 끝나지 못한 장면 세션의 열린 에피소드는 닫기와 같은 시각에 닫힌다. 이미 닫힌 에피소드·실제 세션 행은 그대로
    @Test fun c259_closeScenarioSessions_closesOpenEpisodes() {
        scenarioSession("SC1", null); session("S1")
        dao.upsertEpisode(ep("O", "SC1", null, null).copy(endedAt = null)); dao.upsertEpisode(ep("C", "SC1", null, null))
        dao.upsertEpisode(ep("R", "S1", null, null).copy(endedAt = null))
        store.closeScenarioSessions(T0 + 9_000)
        assertEquals(listOf<Long?>(T0 + 9_000, T0 + 200_000), listOf("O", "C").map { id -> dao.episodes("SC1").single { it.id == id }.endedAt })
        assertNull(dao.episodes("S1").single().endedAt)
    }

    @Test fun saveSession_scenarioNeverPending_andKeepsReportId() {
        val sc = Store(db, { now }, scenarioId = { "A" })
        sc.handle(SaveSession(SessionRecord("S2", T0, null, OpenTrigger.DOOR_THEN_D, null, null, 0, 0, false, "[]", null, null, false, null, 10)))
        sc.handle(SaveSession(SessionRecord("S2", T0, T0 + 1, OpenTrigger.DOOR_THEN_D, null, null, 0, 0, false, "[]", null, null, true, null, 10)))
        assertEquals("A", dao.session("S2")!!.scenarioId)
        assertEquals(false, dao.session("S2")!!.reportPending, "장면 세션은 report_pending을 참으로 쓰지 않는다")
        phone(); session(); store.handle(CloseSession("S1"))
        val id = dao.session("S1")!!.reportMessageId
        session()   // 늦게 온 같은 세션 저장이 리포트 id를 지우지 않는다
        assertEquals(id, dao.session("S1")!!.reportMessageId)
        assertEquals(false, dao.session("S1")!!.reportPending)
    }

    @Test fun logAlarm_joinsContinuousAlarms_g3() {
        session(ended = null)
        store.handle(LogAlarm("S1", T0 + 1_000, AlarmOnly.IN_CALL))
        store.handle(LogAlarm("S1", T0 + 2_000, AlarmOnly.IN_CALL))
        store.handle(LogAlarm("S1", T0 + 60_000, AlarmOnly.IN_CALL))
        store.handle(LogAlarm("S1", T0 + 61_000, AlarmOnly.WAITING))
        assertEquals(3, count("SELECT COUNT(*) FROM alarm_log"))
        assertEquals(2_000, count("SELECT last_at - $T0 FROM alarm_log ORDER BY id LIMIT 1"), "이어진 경보는 첫 줄의 last_at만 늘린다")
    }

    @Test fun logAlarm_gapOfThreeSteps_startsNewRow() {
        session(ended = null)
        assertEquals(975L, Params().yamnetHopMs)
        store.handle(LogAlarm("S1", T0, AlarmOnly.IN_CALL))
        store.handle(LogAlarm("S1", T0 + 975, AlarmOnly.IN_CALL))
        assertEquals(1, count("SELECT COUNT(*) FROM alarm_log"), "한 걸음(975ms) 간격은 이어진 경보")
        store.handle(LogAlarm("S1", T0 + 975 + 2_925, AlarmOnly.IN_CALL))
        assertEquals(2, count("SELECT COUNT(*) FROM alarm_log"), "세 걸음(2,925ms) 간격은 끊긴 경보라 새 줄")
    }

    private fun profile(feeding: Long?) = kr.aigyeot.app.store.db.ChildProfileEntity("c", "콩이", "2026-03", "LEFT", 180, feeding, emptyList(), false, false, T0, kr.aigyeot.app.store.db.AI_NOTICE_VERSION)

    @Test fun saveProfile_keepsLaterFeedingStart() {
        dao.upsertProfile(profile(T0 + 10))
        store.saveProfile(profile(T0).copy(nickname = "새 별명"))   // 설정 화면은 열릴 때의(더 이른) 수유 시각을 들고 있다
        assertEquals(T0 + 10, store.feedingStart(), "폰이 커밋한 더 나중 수유 시각을 되돌리지 않는다")
        assertEquals("새 별명", dao.profile()!!.nickname)
    }

    @Test fun saveProfile_noRow_usesGivenFeedingStart() {
        assertNull(dao.profile())
        store.saveProfile(profile(T0))
        assertEquals(T0, store.feedingStart())
    }

    @Test fun report_hugeSession_truncatedUnderEnvelopeLimit_oldestFirst() {
        session()
        val n = 3_000
        for (i in 0 until n) {
            dao.upsertEpisode(EpisodeEntity("E%05d".format(i), "S1", T0 + i * 1_000L, T0 + i * 1_000L + 500, "ALARM", T0 + i * 1_000L, 1_000, null, null, null, null,
                null, null, null, null, null, null, false, false, 0, false, 0, emptyList()))
        }
        assertEquals(n, dao.episodes("S1").size)
        val r = buildReport(dao, "S1", now)!!
        val size = encodeReport(r).toByteArray().size
        assertTrue(r.timeline.size < n, "줄었다")
        assertTrue(size <= 64 * 1024 - 2048, "봉투 여유를 뺀 한도 아래여야 한다: $size")
        assertEquals(true, r.truncated)
        assertEquals("E%05d".format(n - 1), r.timeline.last().episodeId, "새 것은 남는다")
        assertTrue(r.timeline.first().episodeId > "E00000", "오래된 것부터 줄었다")
    }

    @Test fun saveFeedingStart_onlyLater() {
        dao.upsertProfile(kr.aigyeot.app.store.db.ChildProfileEntity("c", "콩이", "2026-03", "LEFT", 180, T0, emptyList(), false, false, T0, kr.aigyeot.app.store.db.AI_NOTICE_VERSION))
        store.handle(SaveFeedingStart(T0 - 1))
        assertEquals(T0, store.feedingStart())
        store.handle(SaveFeedingStart(T0 + 1))
        assertEquals(T0 + 1, store.feedingStart())
    }

    @Test fun methods_thirdCandidate_selectedElseNewest() {
        dao.upsertProfile(kr.aigyeot.app.store.db.ChildProfileEntity("c", "콩이", "2026-03", "LEFT", null, null,
            listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY), Method(MethodKind.SONG)), false, false, T0, kr.aigyeot.app.store.db.AI_NOTICE_VERSION))
        assertEquals(listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.WOMB), Method(MethodKind.LULLABY)), store.methods(), "쓸 곡이 없으면 ③ 없음")
        fun song(id: String, seq: Long, status: String = "ACTIVE") = sql("INSERT OR REPLACE INTO song (id, name, received_at, format, duration_s, lufs, normalized_path, status, reject_reason, added_seq, last_seq) VALUES ('$id', '$id', $T0, 'AAC', 60.0, -30.0, NULL, '$status', NULL, $seq, $seq)")
        song("a", 1); song("b", 2)
        assertEquals(Method(MethodKind.SONG, "b"), store.methods().last(), "고른 적 없으면 added_seq가 가장 큰 곡")
        sql("INSERT INTO song_selection (song_id, seq) VALUES ('a', 5)")
        assertEquals(Method(MethodKind.SONG, "a"), store.methods().last(), "고른 곡")
        song("a", 1, "DELETED")
        assertEquals(Method(MethodKind.SONG, "b"), store.methods().last(), "고른 곡이 지워지면 다시 가장 새 곡")
    }

    @Test fun methods_thirdCandidate_sameRuleAsLink() {
        dao.upsertProfile(kr.aigyeot.app.store.db.ChildProfileEntity("c", "콩이", "2026-03", "LEFT", null, null,
            listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY), Method(MethodKind.SONG)), false, false, T0, kr.aigyeot.app.store.db.AI_NOTICE_VERSION))
        fun song(id: String, seq: Long?, status: String = "ACTIVE") = sql("INSERT OR REPLACE INTO song (id, name, received_at, format, duration_s, lufs, normalized_path, status, reject_reason, added_seq, last_seq) VALUES ('$id', '$id', $T0, 'AAC', 60.0, -30.0, NULL, '$status', NULL, ${seq ?: "NULL"}, ${seq ?: 0})")
        val link = db.linkDao()
        fun third() = store.methods().firstOrNull { it.kind == MethodKind.SONG }?.songId
        fun rule() = kr.aigyeot.app.link.candidateSong(link.profile(), link.songs(), link.selection())
        song("n", null); song("a", -2); song("b", 3, "PENDING")
        assertEquals(rule(), third()); assertEquals("a", third())
        sql("INSERT INTO song_selection (song_id, seq) VALUES ('b', 9)")   // 고른 곡이 아직 쓸 수 없다
        assertEquals(rule(), third()); assertEquals("a", third())
        song("b", 3)
        assertEquals(rule(), third()); assertEquals("b", third())
    }

    @Test fun report_carActions_countOnlySucceededControls() {
        fullSession()   // 열선 성공 한 줄
        dao.insertControl(ControlLogEntity(sessionId = "S1", at = T0 + 11_000, api = "Seat.setHvacSeatTemperature", target = "ROW_2_RIGHT", goal = "0", actual = "3", ackMs = 120, appliedMs = null, ok = false, retries = 1))
        dao.insertControl(ControlLogEntity(sessionId = "S1", at = T0 + 12_000, api = "setWindowLock", target = "ROW_2_LEFT", goal = "1", actual = "0", ackMs = 120, appliedMs = null, ok = false, retries = 1))
        val r = buildReport(dao, "S1", now)!!
        val acts = r.carActions.associate { it.action to it.count }
        assertEquals(1, acts[kr.aigyeot.shared.CarActionKind.SEAT_HEATER_OFF], "car_actions는 차가 대신 한 일: 실패 줄은 세지 않는다")
        assertEquals(0, acts[kr.aigyeot.shared.CarActionKind.REAR_WINDOW_LOCK] ?: 0, "실패한 잠금만 있으면 항목 없음")
        assertEquals(1, r.controls.single { it.target == "ROW_2_RIGHT" }.failed, "실패는 controls에 남는다")
    }

    // 기능 D(C361): ambient_dim = 낮춤에 성공한 DimAmbient 수. 한 명령의 밝기·색 두 줄은 cmd_id로 한 번, 되돌림(restore:)·실패 줄은 세지 않는다
    @Test fun report_ambientDim_countsDistinctSucceededDimCommands() {
        fullSession()
        fun amb(api: String, goal: String, cmd: String?, ok: Boolean = true, at: Long) = dao.insertControl(ControlLogEntity(sessionId = "S1", at = T0 + at, api = api, target = "ROW_1_LEFT+ROW_2_RIGHT", goal = goal, actual = goal, ackMs = 1, appliedMs = 1, ok = ok, retries = 0, cmdId = cmd))
        amb("setAmbientLightBrightness", "dim:20", "S1-5", at = 20_000); amb("setAmbientLightBrightnessColor", "dim:#3050A0", "S1-5", at = 20_001)   // 한 명령
        amb("setAmbientLightBrightness", "restore:70", "S1-9", at = 30_000); amb("setAmbientLightBrightnessColor", "restore:#FFFFFF", "S1-9", at = 30_001)   // 되돌림
        amb("setAmbientLightBrightness", "dim:20", "S1-12", at = 40_000); amb("setAmbientLightBrightnessColor", "dim:#3050A0", "S1-12", ok = false, at = 40_001)   // 두 번째 낮춤: 밝기만 성공
        amb("setAmbientLightBrightness", "dim:20", "S1-15", ok = false, at = 50_000)   // 실패
        val r = buildReport(dao, "S1", now)!!
        assertEquals(2, r.carActions.single { it.action == kr.aigyeot.shared.CarActionKind.AMBIENT_DIM }.count)
        assertEquals(null, r.carActions.single { it.action == kr.aigyeot.shared.CarActionKind.AMBIENT_DIM }.totalS)
        assertTrue(r.controls.any { it.api == "setAmbientLightBrightness" && it.failed == 1 }, "실패·되돌림은 controls 요약에 든다")
    }

    private fun lockLine(target: String, goal: String, at: Long) =
        dao.insertControl(ControlLogEntity(sessionId = "S1", at = T0 + at, api = "setWindowLock", target = target, goal = goal, actual = goal, ackMs = 120, appliedMs = 300, ok = true, retries = 0))
    private fun rearWindowLocks(): Int {
        val r = buildReport(dao, "S1", now)!!
        return r.carActions.firstOrNull { it.action == kr.aigyeot.shared.CarActionKind.REAR_WINDOW_LOCK }?.count ?: 0
    }

    // C337: 리포트 rear_window_lock = 잠금에 성공한 뒷창 수. 같은 창의 두 번째 '목표 참' 줄(되돌림·다시 잠금)은 세지 않는다
    @Test fun c337_rearWindowLock_sameWindowLockThenRestoreLock_countsOnce() {
        fullSession(); lockLine("ROW_2_LEFT", "1", 12_000); lockLine("ROW_2_LEFT", "1", 13_000)
        assertEquals(1, rearWindowLocks())
        assertEquals(2, buildReport(dao, "S1", now)!!.controls.single { it.api == "setWindowLock" }.attempts, "되돌림 줄도 제어 기록 요약에는 든다")
    }

    @Test fun c337_rearWindowLock_twoWindowsEachLockedAndRestored_countsTwo() {
        fullSession()
        lockLine("ROW_2_LEFT", "1", 12_000); lockLine("ROW_2_RIGHT", "1", 12_500); lockLine("ROW_2_LEFT", "1", 13_000); lockLine("ROW_2_RIGHT", "1", 13_500)
        assertEquals(2, rearWindowLocks())
    }

    @Test fun c337_rearWindowLock_relockAfterBoot_countsOnce() {
        fullSession(); lockLine("ROW_2_LEFT", "true", 12_000); lockLine("ROW_2_LEFT", "true", 20_000)
        assertEquals(1, rearWindowLocks())
    }

    @Test fun c337_rearWindowLock_lockThenRestoreUnlock_countsOnce() {
        fullSession(); lockLine("ROW_2_LEFT", "1", 12_000); lockLine("ROW_2_LEFT", "0", 13_000)
        assertEquals(1, rearWindowLocks())
    }

    @Test fun contextRecorder_capturesMinus30ToPlus30_everySecond() {
        session(ended = null)
        dao.upsertEpisode(EpisodeEntity("E1", "S1", T0, null, "ALARM", T0 + 40_000, 1_000, null, null, null, null, null, null, null, null, null, null, false, false, 0, false, 0, emptyList()))
        val s = EngineState.fresh(1, null, emptyList(), emptyList(), null, 0).let { it.copy(car = it.car.copy(gear = Gear.D)) }
        var t = T0
        while (t <= T0 + 40_000) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), s); t += 100 }
        store.handle(CaptureContext("E1", T0 + 40_000))
        while (t <= T0 + 80_000) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), s); t += 100 }
        val rows = dao.context("E1")
        // 줄의 at은 칸의 끝이다: T0+10초 줄은 9~10초 칸, T0+70초 줄은 69~70초 칸(경보 +30초까지 마감된 칸)
        assertEquals(T0 + 10_000, rows.first().at); assertEquals(T0 + 70_000, rows.last().at)
        assertEquals(61, rows.size)
        assertTrue(rows.zipWithNext().all { (a, b) -> b.at - a.at == 1_000L })
    }

    // C319 F-A: 장면 경계(resetContext)는 기다리는 캡처를 버리지 않고 작성 중인 칸까지 마감해 쓴다. 그 뒤 칸은 장면 표본이라 쓰지 않는다
    @Test fun c319_resetContext_settlesOpenSecondOfWaitingCapture() {
        session(ended = null)
        dao.upsertEpisode(EpisodeEntity("E1", "S1", T0, null, "ALARM", T0 + 40_000, 1_000, null, null, null, null, null, null, null, null, null, null, false, false, 0, false, 0, emptyList()))
        val s = EngineState.fresh(1, null, emptyList(), emptyList(), null, 0).let { it.copy(car = it.car.copy(gear = Gear.D)) }
        var t = T0
        while (t <= T0 + 40_000) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), s); t += 100 }
        store.handle(CaptureContext("E1", T0 + 40_000))
        while (t < T0 + 45_500) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), s); t += 100 }   // 45~46초 칸을 쓰는 중
        assertEquals(T0 + 45_000, dao.context("E1").last().at, "전제: 44~45초 칸까지 마감됐다")
        store.resetContext()   // 장면 시작
        assertEquals(T0 + 46_000, dao.context("E1").last().at, "작성 중인 칸(실제 값)을 마감해 쓴다")
        val n = dao.context("E1").size
        while (t <= T0 + 80_000) { store.observe(VehicleSample(t, 99.0, false, 0.0, TurnSignal.NONE), s); t += 100 }   // 장면 표본
        assertEquals(n, dao.context("E1").size, "장면 표본은 실제 에피소드 캡처에 들지 않는다")
    }

    private fun stateWith(playing: Boolean): EngineState {
        val base = EngineState.fresh(1, null, emptyList(), emptyList(), null, 0)
        val play = Play("P", PlayKind.AUTO, PlayPurpose.AUTO_FIRST, Method(MethodKind.LULLABY), if (playing) T0 + 5_200 else null, T0 + 60_000, PlayMode.NORMAL)
        val ses = SessionState(
            id = "S1", openedAt = T0, trigger = OpenTrigger.DOOR_THEN_D, methods = emptyList(), stage = Stage.Attempt(1, play),
            episode = null, playedMs = 0, offers = OfferState(), controls = ControlState(),
            s17Said = false, micNoticeArmed = false, recordsCleared = false,
        )
        return base.copy(car = base.car.copy(gear = Gear.D), session = ses)
    }

    @Test fun preCry_playbackStartedAfterAlarmInSameSecond_isNotPlaying() {
        session(ended = null)
        val alarm = T0 + 5_100
        val ep = EpisodeEntity("E1", "S1", T0, null, "ALARM", alarm, 1_000, null, null, null, null, null, null, null, null, null, null, false, false, 0, false, 0, emptyList())
        dao.upsertEpisode(ep)
        val before = stateWith(false); val after = stateWith(true)
        var t = T0
        while (t <= alarm) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), before); t += 100 }
        store.handle(CaptureContext("E1", alarm))
        while (t <= T0 + 7_000) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), after); t += 100 }   // +200ms에 재생이 시작된다
        val rows = dao.context("E1")
        assertTrue(rows.any { it.at > alarm && it.playback != null }, "경보 뒤 칸에는 재생 중이 담겼다(전제)")
        assertTrue(rows.any { it.at <= alarm }, "경보 이전 줄이 있다(전제)")
        assertEquals(false, preCry(ep, rows)!!.playing)
    }

    @Test fun contextRecorder_clear_dropsOpenBucket() {
        val c = ContextRecorder()
        val s = stateWith(false)
        c.observe(VehicleSample(T0 + 100, 40.0, false, 0.0, TurnSignal.NONE), s)
        c.observe(CrySegment(T0 + 200, T0, T0 + 200, 0.9, true), s)   // 작성 중인 칸(T0~T0+1초)에 울음 확률이 담긴다
        c.clear()
        c.observe(VehicleSample(T0 + 1_000, 40.0, false, 0.0, TurnSignal.NONE), s)
        c.observe(VehicleSample(T0 + 2_000, 40.0, false, 0.0, TurnSignal.NONE), s)
        val rows = c.capture("E", T0 + 1_000)
        assertEquals(1, rows.size, "지우기 전 칸은 마감되지 않고, 지운 뒤 칸 하나만 있다(전제)")
        assertNull(rows[0].cryP, "지우기 전 울음 확률이 남지 않는다")
    }

    @Test fun purgeOld_90days_keepsOpenAndRecent() {
        session("old", ended = T0 + 1)
        session("open", ended = null)
        dao.upsertPlay(PlayLogEntity("P", "old", null, "REQUEST", "LULLABY", null, T0, T0 + 1, 1, "DEADLINE"))
        now = T0 + 91 * kr.aigyeot.engine.DAY_MS
        session("new", ended = now - 1)
        store.purgeOld()
        assertNull(dao.session("old")); assertEquals(0, count("SELECT COUNT(*) FROM play_log"), "자식 행도 함께")
        assertEquals(2, count("SELECT COUNT(*) FROM session"))
    }

    @Test fun reversals_countsThresholdTurns() {
        assertEquals(0, reversals(listOf(0.0, 1.0, 2.0, 1.5, 2.5), 3.0))
        assertEquals(2, reversals(listOf(0.0, 5.0, 1.0, 6.0), 3.0))
    }

    @Test fun steering_afterResponse_onlyLaterSameBandPlayingWindows() {
        val cry = SteerWindowEntity("S1", T0, 2, false, true, false, 6)
        val ok = SteerWindowEntity("S1", T0 + 60_000, 2, false, false, true, 8)
        val otherBand = ok.copy(startAt = T0 + 120_000, speedBand = 4, reversals = 100)
        val before = ok.copy(startAt = T0 - 60_000, reversals = 50)
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, it) }
        val s = steering(listOf(before, cry, ok, otherBand), base, responseAt = ok.startAt)
        assertTrue(s.available && before.startAt < cry.startAt && otherBand.speedBand !in setOf(cry.speedBand))
        assertEquals(8.0, s.afterResponsePerMin, "울음 창 뒤, 같은 속도대 창만 센다")
    }

    @Test fun steering_reasonNoCry_onlyWhenSessionHasNoCryWindow() {                  // C112
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, it) }
        val noCry = SteerWindowEntity("S1", T0, 2, false, false, false, 4)
        val excludedCry = SteerWindowEntity("S1", T0, 2, true, true, false, 6)
        assertEquals(SteeringUnavailableReason.NO_CRY, steering(listOf(noCry), base, null).unavailableReason)
        // C330: 관측한 창이 없으면(창 0개·모두 observed=false) 울음이 없었다고 말할 수 없다
        assertEquals(SteeringUnavailableReason.NO_COMPARABLE_WINDOW, steering(emptyList(), base, null).unavailableReason)
        assertEquals(SteeringUnavailableReason.NO_COMPARABLE_WINDOW, steering(listOf(noCry.copy(observed = false)), base, null).unavailableReason)
        assertEquals(SteeringUnavailableReason.NO_COMPARABLE_WINDOW, steering(listOf(noCry.copy(observed = false), noCry), base, null).unavailableReason, "C338: 관측+미관측 섞임")
        assertEquals(SteeringUnavailableReason.NO_COMPARABLE_WINDOW, steering(listOf(excludedCry), base, null).unavailableReason, "울음 창이 있었지만 모두 제외")
        assertEquals(SteeringUnavailableReason.NO_COMPARABLE_WINDOW, steering(listOf(excludedCry, noCry), base, null).unavailableReason)
        assertEquals(SteeringUnavailableReason.NO_COMPARABLE_WINDOW, steering(listOf(excludedCry.copy(excluded = false, speedBand = 4)), base, null).unavailableReason, "기존: 평소 창 없는 속도대")
    }

    @Test fun steering_cryWindowUnobserved_plusQuietObserved_isNoComparableWindow() {   // C331: 울음 중 재생이 시작된 창(cryPresent·미관측)이 있으면 울음이 없었다고 말할 수 없다. C341: 그 창이 울음 창이라 비교로 간다(NO_CRY 아님)
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, it) }
        val cryUnobserved = SteerWindowEntity("S1", T0, 2, false, true, false, 6).copy(observed = false)
        val quiet = SteerWindowEntity("S1", T0 + 60_000, 2, false, false, false, 4)
        val s = steering(listOf(cryUnobserved, quiet), base, null)
        assertTrue(s.available); assertEquals(null, s.unavailableReason)
        assertEquals(SteeringUnavailableReason.NO_COMPARABLE_WINDOW, steering(listOf(cryUnobserved.copy(excluded = true), quiet), base, null).unavailableReason)
    }

    @Test fun c338_steering_micOnLater_unobservedThenQuietObserved_isNoComparableWindow() {
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, it) }
        val off = (0 until 3).map { SteerWindowEntity("S1", T0 + it * 60_000L, 2, false, false, false, 4, observed = false) }
        val on = (3 until 6).map { SteerWindowEntity("S1", T0 + it * 60_000L, 2, false, false, false, 4) }
        val s = steering(off + on, base, null)
        assertEquals(false, s.available)
        assertEquals(SteeringUnavailableReason.NO_COMPARABLE_WINDOW, s.unavailableReason)
    }

    @Test fun c338_steering_allObservedQuiet_withExcluded_isNoCry() {
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, it) }
        val quiet = SteerWindowEntity("S1", T0, 2, false, false, false, 4)
        val s = steering(listOf(quiet, quiet.copy(startAt = T0 + 60_000, excluded = true), quiet.copy(startAt = T0 + 120_000)), base, null)
        assertEquals(false, s.available)
        assertEquals(SteeringUnavailableReason.NO_CRY, s.unavailableReason)
    }

    @Test fun round1_halfUp_toOneDecimal() {                                          // C112
        assertEquals(80.0, round1(80.00000381469727))
        assertEquals(58.3, round1(58.25))
        assertEquals(58.2, round1(58.24))
        assertEquals(0.1, round1(0.05))
    }

    @Test fun preCry_speedRoundedToOneDecimal() {                                      // C112
        val alarm = T0 + 5_000
        val ep = EpisodeEntity("E1", "S1", T0, null, "ALARM", alarm, 1_000, null, null, null, null, null, null, null, null, null, null, false, false, 0, false, 0, emptyList())
        fun row(at: Long, v: Double) = ContextSampleEntity("E1", at, v, false, 0.0, "NONE", "D", 24.0, null, null, null)
        val c = preCry(ep, listOf(row(alarm - 1_000, 80.00000381469727), row(alarm, 58.25)))!!
        assertEquals(80.0, c.speedKmhMax)
        assertEquals(69.1, c.speedKmhAvg)      // (80.0000038 + 58.25) / 2 = 69.125
    }

    @Test fun steering_comparesSameBandBaseline() {
        val cry = SteerWindowEntity("S1", T0, 2, false, true, false, 6)
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, it) }
        val s = steering(listOf(cry), base, null)
        assertTrue(s.available); assertEquals(3.0, s.baselinePerMin); assertEquals(6.0, s.duringCryPerMin)
        assertEquals(false, steering(listOf(cry), base.map { it.copy(speedBand = 4) }, null).available)
        assertEquals(false, steering(emptyList(), base, null).available)
    }

    // ---- 검토 수정 C6~C8 ----

    @Test fun report_hugeControls_stillUnderLimit() {
        session()
        for (i in 0 until 5_000) dao.insertControl(ControlLogEntity(sessionId = "S1", at = T0 + i, api = "Api$i", target = "T$i", goal = "0", actual = "0", ackMs = 1, appliedMs = 1, ok = true, retries = 0))
        val r = buildReport(dao, "S1", now)!!
        val size = encodeReport(r).toByteArray().size
        assertTrue(dao.controls("S1").size == 5_000, "제어 기록이 많다(전제)")
        assertTrue(size <= 64 * 1024 - 2048, "한도 아래여야 한다: $size")
        assertEquals(true, r.truncated)
    }

    @Test fun baselineWindows_excludesScenarioSessions() {
        session()   // 실제 세션 S1, driver 10
        Store(db, { now }, scenarioId = { "A" }).handle(SaveSession(SessionRecord("S2", T0, T0 + 1, OpenTrigger.DOOR_THEN_D, null, null, 0, 0, false, "[]", null, null, false, null, 10)))
        dao.upsertSteer(SteerWindowEntity("S1", T0, 2, false, false, false, 3))
        dao.upsertSteer(SteerWindowEntity("S2", T0, 2, false, false, false, 30))
        assertEquals(listOf("S1"), dao.baselineWindows(10).map { it.sessionId }, "장면 세션 창은 평소 기준선에 섞이지 않는다")
    }

    // C325 ①: 마이크 꺼진 창(observed=0)은 평소 기준선에 섞이지 않는다
    @Test fun baselineWindows_excludesUnobservedWindows() {
        session()
        dao.upsertSteer(SteerWindowEntity("S1", T0, 2, false, false, false, 3))
        dao.upsertSteer(SteerWindowEntity("S1", T0 + 60_000, 2, false, false, false, 30, observed = false))
        assertEquals(listOf(T0), dao.baselineWindows(10).map { it.startAt })
    }

    @Test fun steering_perBand_noChangeWhenOnlyBandMixDiffers() {
        fun w(at: Int, band: Int, r: Int, cry: Boolean = false, play: Boolean = false) = SteerWindowEntity("S1", T0 + at * 60_000L, band, false, cry, play, r)
        val base = (1..6).map { w(-it, 2, 2) } + (7..8).map { w(-it, 4, 20) }       // 속도대 2가 훨씬 많다
        val s = listOf(w(0, 2, 2, cry = true)) + (1..4).map { w(it, 4, 20, cry = true) } + w(9, 4, 20, play = true)
        val r = steering(s, base, responseAt = T0 + 9 * 60_000L)
        assertEquals(r.baselinePerMin, r.duringCryPerMin, "속도대 비중만 달라도 증가로 보지 않는다")
        assertEquals(r.baselinePerMin, r.afterResponsePerMin)
        assertTrue(r.duringCryZ == null || r.duringCryZ == 0.0, "mad가 0이면 null, 아니면 0")
    }

    // C354: 속도대가 섞인 기준선(중앙값 2·10 → 6)에서 대응 뒤 값은 공식대로 가되 0 아래로 내려가지 않는다
    private fun multiBand(afterReversals: Int): kr.aigyeot.shared.Steering {
        fun w(at: Int, band: Int, r: Int, cry: Boolean = false, play: Boolean = false) = SteerWindowEntity("S1", T0 + at * 60_000L, band, false, cry, play, r)
        val base = (1..5).map { w(-it, 2, 2) } + (6..10).map { w(-it, 4, 10) }
        val s = listOf(w(0, 2, 2, cry = true), w(1, 4, 10, cry = true), w(2, 4, afterReversals))
        return steering(s, base, responseAt = T0 + 2 * 60_000L)
    }

    @Test fun c354_afterResponse_multiBand_neverNegative() {
        val r = multiBand(0)
        assertEquals(6.0, r.baselinePerMin)
        assertEquals(0.0, r.afterResponsePerMin, "6 + (0 - 10) = -4 는 0으로")
    }

    @Test fun c354_afterResponse_multiBand_positiveKeepsFormula() {
        assertEquals(5.0, multiBand(9).afterResponsePerMin, "6 + (9 - 10)")
    }

    // C341: 자동 달래기 주행. 울음 창은 재생이 시작돼 관측이 일부뿐이고, 재생이 끝난 뒤 조용한 창이 대응 뒤다
    private fun autoSoothe(): Pair<List<SteerWindowEntity>, List<SteerWindowEntity>> {
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, 2) }
        val w1 = SteerWindowEntity("S1", T0, 2, false, true, true, 6, observed = false)
        val w2 = SteerWindowEntity("S1", T0 + 60_000, 2, false, false, true, 20, observed = false)
        val w3 = SteerWindowEntity("S1", T0 + 120_000, 2, false, false, false, 4)
        return listOf(w1, w2, w3) to base
    }

    @Test fun c341_autoSoothe_cryWindowPartlyUnobserved_countsAsCry() {
        val (s, base) = autoSoothe()
        val r = steering(s, base, null)
        assertTrue(r.available)
        assertEquals(6.0, r.duringCryPerMin)
    }

    @Test fun c341_autoSoothe_quietAfterPlaybackEnded_isAfterResponse() {
        val (s, base) = autoSoothe()
        assertEquals(4.0, steering(s, base, responseAt = s[1].startAt).afterResponsePerMin)
    }

    @Test fun c341_quietWindowBeforeFirstPlayback_notAfterResponse() {
        val (s, base) = autoSoothe()
        val (w1, w2, w3) = s
        val cry = w1.copy(playing = false)                           // 울음 창에서는 아직 재생 전, 다음 창에서 재생 시작
        val early = w3.copy(startAt = T0 + 30_000, reversals = 50)   // 울음 뒤, 재생 전
        assertEquals(4.0, steering(listOf(cry, early, w2, w3), base, responseAt = w2.startAt).afterResponsePerMin, "대응 시작 전 조용한 창은 빠진다")
        assertEquals(null, steering(listOf(w1.copy(playing = false), w3), base, null).afterResponsePerMin, "대응이 없으면 대응 뒤 없음")
    }

    // C343(C344로 입력이 바뀜): 울음 뒤 시작한 대응이 없으면(responseAt = null) 조용한 창이 있어도 대응 뒤 없음. 이전엔 창의 playing 표시로 이르던 것을 play_log 시각으로 받는다
    @Test fun c343_playbackBeforeCryOnly_notAfterResponse() {
        val (s, base) = autoSoothe()
        val (w1, _, w3) = s
        val earlyPlay = SteerWindowEntity("S1", T0 - 60_000, 2, false, false, true, 3)
        val quiet = (3..5).map { w3.copy(startAt = T0 + it * 60_000L) }
        assertEquals(null, steering(listOf(earlyPlay, w1.copy(playing = false)) + quiet, base, null).afterResponsePerMin)
    }

    @Test fun c344_requestEndedThenCrySameWindow_noResponse_afterNull() {
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, 2) }
        val cry = SteerWindowEntity("S1", T0, 2, false, true, true, 6)           // 운전자가 튼 재생이 끝난 뒤 같은 창에서 울음 (playing·cryPresent 둘 다 참)
        val quiet = SteerWindowEntity("S1", T0 + 60_000, 2, false, false, false, 4)
        assertEquals(null, steering(listOf(cry, quiet), base, null).afterResponsePerMin)
    }

    @Test fun c344_responseStartsAfterCry_quietWindowsAfterResponseCount() {
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, 2) }
        val cry = SteerWindowEntity("S1", T0, 2, false, true, false, 6)
        val early = SteerWindowEntity("S1", T0 + 60_000, 2, false, false, false, 50)
        val late = SteerWindowEntity("S1", T0 + 120_000, 2, false, false, false, 4)
        assertEquals(4.0, steering(listOf(cry, early, late), base, responseAt = T0 + 90_000).afterResponsePerMin, "responseAt 앞에 시작한 창은 빠진다")
        assertEquals(27.0, steering(listOf(cry, early, late), base, responseAt = T0 + 30_000).afterResponsePerMin, "responseAt이 앞이면 둘 다 든다")
    }

    // C347: 첫 울음 창이 기준선 없는 속도대여도 '대응 뒤'의 앞 경계다
    @Test fun c347_firstCryInBandWithoutBaseline_stillAnchorsAfter() {
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, 2) }
        val firstCry = SteerWindowEntity("S1", T0, 3, false, true, false, 9)            // 기준선 없는 속도대
        val quiet = SteerWindowEntity("S1", T0 + 60_000, 2, false, false, false, 4)      // 대응 뒤 조용한 창
        val laterCry = SteerWindowEntity("S1", T0 + 120_000, 2, false, true, false, 6)
        assertEquals(4.0, steering(listOf(firstCry, quiet, laterCry), base, responseAt = T0 + 30_000).afterResponsePerMin)
    }

    // C344: buildReport가 play_log·에피소드로 대응 시작을 계산해 쓴다
    private fun responseReport(playStart: Long?, trigger: String = "ALARM", alarmAt: Long? = T0 + 60_000): Double? {
        session()
        dao.upsertEpisode(EpisodeEntity("E1", "S1", T0 + 60_000, T0 + 200_000, trigger, alarmAt, 2_400, "LULLABY", null, "SUCCESS", null,
            null, null, null, null, null, null, false, false, 61_000, false, 56_000, emptyList()))
        if (playStart != null) dao.upsertPlay(PlayLogEntity("P1", "S1", "E1", "AUTO_FIRST", "LULLABY", null, playStart, playStart + 20_000, 20_000, "ENDED"))
        for (i in 1..5) dao.upsertSteer(SteerWindowEntity("S1", T0 - i * 60_000L, 2, false, false, false, 2))   // 평소 창(driver 10, 울음 앞이라 대응 뒤에는 안 든다)
        dao.upsertSteer(SteerWindowEntity("S1", T0 + 60_000, 2, false, true, true, 6))                          // 울음 창
        dao.upsertSteer(SteerWindowEntity("S1", T0 + 180_000, 2, false, false, false, 4))                       // 뒤의 조용한 창
        return buildReport(dao, "S1", now)!!.steering.afterResponsePerMin
    }

    @Test fun c344_buildReport_noPlayLog_afterNull() = assertEquals(null, responseReport(null))

    @Test fun c345_buildReport_playBeforeAlarm_notResponse() = assertEquals(null, responseReport(T0 + 30_000))

    @Test fun c345_buildReport_playAfterAlarm_afterFromQuietWindow() = assertEquals(4.0, responseReport(T0 + 63_000))

    // C345: 운전자가 LISTENING에서 튼 소리(경보 없는 소리 바꾸기 에피소드)의 재생은 대응이 아니다
    @Test fun c345_buildReport_requestPlayInSwitchSoundEpisode_noAlarm_afterNull() =
        assertEquals(null, responseReport(T0 + 63_000, trigger = "SWITCH_SOUND", alarmAt = null))

    // C346: 경보가 alarm_log에만 있어도(대기 중 울음) 그 뒤에 시작한 재생은 대응이다. 울음 전 재생(30초)은 대응이 아니라 그 앞 조용한 창(T0+40초)은 빠진다
    @Test fun c346_buildReport_alarmOnlyInLog_playAfter_afterFromQuietWindow() {
        session()
        dao.insertAlarm(AlarmLogEntity(sessionId = "S1", firstAt = T0 + 60_000, lastAt = T0 + 60_000, reason = "WAITING"))
        dao.upsertEpisode(EpisodeEntity("E1", "S1", T0 + 10_000, T0 + 200_000, "SWITCH_SOUND", null, 2_400, "LULLABY", null, "SUCCESS", null,
            null, null, null, null, null, null, false, false, 61_000, false, 56_000, emptyList()))
        dao.upsertPlay(PlayLogEntity("P0", "S1", "E1", "REQUEST", "LULLABY", null, T0 + 30_000, T0 + 50_000, 20_000, "ENDED"))
        dao.upsertPlay(PlayLogEntity("P1", "S1", "E1", "REQUEST", "LULLABY", null, T0 + 63_000, T0 + 83_000, 20_000, "ENDED"))
        for (i in 1..5) dao.upsertSteer(SteerWindowEntity("S1", T0 - i * 60_000L, 2, false, false, false, 2))
        dao.upsertSteer(SteerWindowEntity("S1", T0 + 40_000, 2, false, false, true, 50))
        dao.upsertSteer(SteerWindowEntity("S1", T0 + 60_000, 2, false, true, true, 6))
        dao.upsertSteer(SteerWindowEntity("S1", T0 + 180_000, 2, false, false, false, 4))
        assertEquals(4.0, buildReport(dao, "S1", now)!!.steering.afterResponsePerMin)
    }

    @Test fun c312_unobservedWindowsExcludedFromAfterResponse() {
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, 2) }
        val cry = SteerWindowEntity("S1", T0, 2, false, true, false, 2)
        val seen = SteerWindowEntity("S1", T0 + 60_000, 2, false, false, true, 4)
        val unseen = SteerWindowEntity("S1", T0 + 120_000, 2, false, false, true, 20, observed = false)
        assertEquals(4.0, steering(listOf(cry, seen), base, seen.startAt).afterResponsePerMin)
        assertEquals(4.0, steering(listOf(cry, seen, unseen), base, seen.startAt).afterResponsePerMin, "보지 못한 playing 창은 '대응 뒤'에 들지 않는다")
        assertEquals(null, steering(listOf(cry, unseen), base, seen.startAt).afterResponsePerMin)
    }

    @Test fun steering_ignoresCryWindowsInBandsWithoutBaseline() {
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, it) }
        val cry = listOf(SteerWindowEntity("S1", T0, 2, false, true, false, 2)) +
            (1..2).map { SteerWindowEntity("S1", T0 + it * 60_000, 4, false, true, false, 20) }
        assertTrue(cry.any { it.speedBand == 4 } && base.none { it.speedBand == 4 }, "평소 창 없는 속도대의 울음 창이 있다(전제)")
        val s = steering(cry, base, null)
        assertEquals(2.0, s.duringCryPerMin)
        assertEquals(false, steering(cry.filter { it.speedBand == 4 }, base, null).available, "비교할 창이 없다")
    }

    @Test fun contextRecorder_reset_dropsScenarioSamples() {
        val c = ContextRecorder()
        val s = stateWith(false)
        c.observe(VehicleSample(T0 + 100, 99.0, false, 0.0, TurnSignal.NONE), s)
        c.observe(Tick(T0 + 1_100), s)   // 장면 표본이 든 칸이 마감돼 recent에 들어간다
        assertEquals(1, c.capture("X", T0 + 1_000).size, "reset 전에는 장면 칸이 나온다(전제)")
        c.reset()
        c.observe(Tick(T0 + 2_100), s)
        c.observe(Tick(T0 + 3_100), s)
        assertTrue(c.capture("E", T0 + 2_000).isEmpty(), "reset 뒤 이전 표본이 캡처에 나오지 않는다")
    }
}
