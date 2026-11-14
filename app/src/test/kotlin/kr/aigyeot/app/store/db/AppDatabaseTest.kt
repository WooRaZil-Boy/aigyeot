package kr.aigyeot.app.store.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// store·link가 함께 쓰는 AppDatabase가 Robolectric + Room in-memory로 열리고, 표가 모두 있는지
@RunWith(RobolectricTestRunner::class)
class AppDatabaseTest {
    @Test fun `in-memory로 열면 store·link 표가 모두 있다`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val tables = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }.filterNot { it.startsWith("sqlite_") || it.startsWith("android_") || it == "room_master_table" }.toSet()
        db.close()
        assertEquals(
            setOf(
                "child_profile", "session", "episode", "play_log", "alarm_log", "context_sample", "control_log",
                "steer_window", "phone_link", "song", "engine_snapshot",
                "relay_credential", "outbox", "processed_message", "song_download", "song_selection", "phone_link_mark", "care_applied", "sent_id"
            ),
            tables
        )
    }

    // 1→2 이전 직후 옛 장면 기록의 말했는지(null)를 참으로 채워 거짓으로 바뀌지 않게 한다. 실제 세션·PARKED는 null 그대로
    @Test fun `BACKFILL_ENDED_TOLD는 장면 옛 행만 참으로 채우고 이후 장면 닫기에도 참이다`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.storeDao()
        fun session(id: String, scenario: String?, ended: Long?) =
            SessionEntity(id, 1_000, ended, "DOOR_THEN_D", null, null, 0, 0, false, "[]", null, null, false, scenario, 10)
        fun ep(id: String, sid: String, kind: String?) = EpisodeEntity(
            id, sid, 1_000, 2_000, "ALARM", 1_000, 100, null, null, "FAIL", null,
            null, null, null, null, null, kind, false, false, 0, false, 0, emptyList()
        )
        dao.upsertSession(session("SC", "A", 3_000))
        dao.upsertSession(session("RL", null, null)) // RL은 열린 실제 세션
        dao.upsertEpisode(ep("scene", "SC", "SESSION_CAP"))
        dao.upsertEpisode(ep("scenePark", "SC", "PARKED"))
        dao.upsertEpisode(ep("real", "RL", "SESSION_CAP"))
        dao.upsertEpisode(ep("sceneNone", "SC", null))
        val raw = db.openHelper.writableDatabase
        raw.execSQL("UPDATE episode SET handoff_told_driver = NULL") // v1에서 온 행: 칸이 새로 생겨 모두 null
        raw.execSQL(BACKFILL_ENDED_TOLD) // 이전이 쓰는 바로 그 문장
        fun told(id: String) = raw.query("SELECT handoff_told_driver FROM episode WHERE id = '$id'").use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getInt(0)
        }
        assertEquals(1, told("scene"))
        assertEquals(null, told("real"))
        assertEquals(null, told("scenePark"))
        assertEquals(null, told("sceneNone"))
        dao.closeScenarioSessions(5_000)
        dao.settleScenarioHandoffs()
        assertEquals(1, told("scene"))
        db.close()
    }

    // 이전 함수 자체: v1 모양(칸 없음) DB에서 MIGRATION_1_2를 돌려 장면 행만 참이 된다
    @Test fun migrationBackfillsEndedRealSessionHandoffs() {
        val h = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext()).name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE child_profile (id INTEGER PRIMARY KEY)")
                        db.execSQL("CREATE TABLE session (id TEXT PRIMARY KEY, scenario_id TEXT, ended_at INTEGER)")
                        db.execSQL("CREATE TABLE episode (id TEXT PRIMARY KEY, session_id TEXT, handoff_kind TEXT)")
                        // 이전이 observed 칸을 더한다
                        db.execSQL("CREATE TABLE steer_window (session_id TEXT NOT NULL, start_at INTEGER NOT NULL, PRIMARY KEY(session_id, start_at))")
                        // SC: 끝난 장면, RL: 끝난 실주행, OP: 열린 실주행
                        db.execSQL("INSERT INTO session VALUES ('SC','A',3000),('RL',NULL,3000),('OP',NULL,NULL)")
                        db.execSQL(
                            "INSERT INTO episode VALUES ('scene','SC','SESSION_CAP'),('park','SC','PARKED'),('real','RL','ARRIVAL_NOTICE'),('none','SC',NULL),('open','OP','ARRIVAL_NOTICE')"
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, o: Int, n: Int) = Unit
                }).build()
        )
        val raw = h.writableDatabase
        MIGRATION_1_2.migrate(raw)
        fun told(id: String) = raw.query("SELECT handoff_told_driver FROM episode WHERE id = '$id'").use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getInt(0)
        }
        // 끝난 실주행·장면은 1, PARKED·종류 없음·열린 세션은 null 그대로
        assertEquals(1, told("scene"))
        assertEquals(null, told("park"))
        assertEquals(1, told("real"))
        assertEquals(null, told("none"))
        assertEquals(null, told("open"))
        h.close()
    }
}
