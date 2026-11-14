package kr.aigyeot.app.store.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kr.aigyeot.app.link.db.CareAppliedEntity
import kr.aigyeot.app.link.db.LinkDao
import kr.aigyeot.app.link.db.OutboxEntity
import kr.aigyeot.app.link.db.PhoneLinkMarkEntity
import kr.aigyeot.app.link.db.ProcessedMessageEntity
import kr.aigyeot.app.link.db.RelayCredentialEntity
import kr.aigyeot.app.link.db.SentIdEntity
import kr.aigyeot.app.link.db.SongDownloadEntity
import kr.aigyeot.app.link.db.SongSelectionEntity

/**
 * store와 link가 함께 쓰는 데이터베이스 하나. 엔티티 목록·버전·DAO 목록은 여기서 정한다.
 * 엔티티 칼럼은 그 엔티티를 가진 모듈이 고친다(store: StoreEntities.kt, link: LinkEntities.kt).
 */
@Database(
    entities = [
        ChildProfileEntity::class, SessionEntity::class, EpisodeEntity::class, PlayLogEntity::class,
        AlarmLogEntity::class, ContextSampleEntity::class, ControlLogEntity::class, SteerWindowEntity::class,
        PhoneLinkEntity::class, SongEntity::class, EngineSnapshotEntity::class,
        RelayCredentialEntity::class, OutboxEntity::class, ProcessedMessageEntity::class,
        SongDownloadEntity::class, SongSelectionEntity::class, PhoneLinkMarkEntity::class, CareAppliedEntity::class, SentIdEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun storeDao(): StoreDao
    abstract fun linkDao(): LinkDao

    companion object
}

// `!= 'PARKED'`는 판단 코어 `HandoffKind.speaks()` 규칙이다(SQL은 함수를 부를 수 없어 상수로 맞춘다).
// 끝난 세션(장면·실주행 모두)의 말하는 인계 행 null은 v2 전 표시 방식대로 말함(1)으로 채운다. 열린 세션은 판단 코어가 정한다
internal const val BACKFILL_ENDED_TOLD =
    "UPDATE episode SET handoff_told_driver = 1 WHERE handoff_told_driver IS NULL AND handoff_kind IS NOT NULL AND handoff_kind != 'PARKED' " +
        "AND session_id IN (SELECT id FROM session WHERE ended_at IS NOT NULL)"

/** 1→2: AI 전송 안내 판 칸과 적용한 돌봄 요약 표(care_applied)·보낸 id 표(sent_id)만 더한다. 옛 행은 0이라 안내를 다시 보여 준다. 기록은 지우지 않는다. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE child_profile ADD COLUMN ai_notice_version INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE episode ADD COLUMN handoff_rest_name TEXT")
        db.execSQL("ALTER TABLE episode ADD COLUMN handoff_told_driver INTEGER")
        // 옛 기록의 null을 "묻지 못했어요"(거짓)나 '미확정'으로 바꾸지 않게 끝난 세션의 말한 인계 행은 참으로 채운다
        db.execSQL(BACKFILL_ENDED_TOLD)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS care_applied (id INTEGER NOT NULL, car_id TEXT, seq INTEGER NOT NULL, " +
                "summary_json TEXT NOT NULL, sender_device TEXT, PRIMARY KEY(id))",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS sent_id (id TEXT NOT NULL, sent_at INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("ALTER TABLE steer_window ADD COLUMN observed INTEGER NOT NULL DEFAULT 1")   // 옛 창은 관측한 것으로 본다
    }
}

/** 2→3(조명 낮춤): control_log.cmd_id만 더한다. 옛 줄은 null이라 앰비언트 리포트 수에 들지 않는다(앰비언트 줄이 없던 때). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE control_log ADD COLUMN cmd_id TEXT") }
}

/** 앱의 데이터베이스(aigyeot.db). 개발 중에는 버전이 바뀌면 지우고 다시 만든다. */
fun AppDatabase.Companion.open(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "aigyeot.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .fallbackToDestructiveMigration(dropAllTables = true).build()
