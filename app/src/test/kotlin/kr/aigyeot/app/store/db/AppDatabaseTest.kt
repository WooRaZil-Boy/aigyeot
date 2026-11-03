package kr.aigyeot.app.store.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

// 0단계: store·link가 함께 쓰는 AppDatabase가 Robolectric + Room in-memory로 열리고, 계약 표가 모두 있는지
@RunWith(RobolectricTestRunner::class)
class AppDatabaseTest {
    @Test fun `in-memory로 열면 계약 6장과 link 8장 표가 모두 있다`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val tables = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }.filterNot { it.startsWith("sqlite_") || it.startsWith("android_") || it == "room_master_table" }.toSet()
        db.close()
        assertEquals(
            setOf(
                "child_profile", "session", "episode", "play_log", "alarm_log", "context_sample", "control_log",
                "steer_window", "phone_link", "song", "engine_snapshot", // contracts-car 6.1
                "relay_credential", "outbox", "processed_message", "song_download", "song_selection", "phone_link_mark", // contracts-link 8.2
            ),
            tables,
        )
    }
}
