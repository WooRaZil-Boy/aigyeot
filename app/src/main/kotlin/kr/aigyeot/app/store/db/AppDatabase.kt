package kr.aigyeot.app.store.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kr.aigyeot.app.link.db.LinkDao
import kr.aigyeot.app.link.db.OutboxEntity
import kr.aigyeot.app.link.db.PhoneLinkMarkEntity
import kr.aigyeot.app.link.db.ProcessedMessageEntity
import kr.aigyeot.app.link.db.RelayCredentialEntity
import kr.aigyeot.app.link.db.SongDownloadEntity
import kr.aigyeot.app.link.db.SongSelectionEntity

/**
 * store와 link가 함께 쓰는 데이터베이스 하나(handoff 0-scaffold 1절). 엔티티 목록·버전·DAO 목록은 조율 담당만 고친다.
 * 엔티티 칼럼은 그 엔티티를 가진 갈래가 고친다(store: StoreEntities.kt, link: LinkEntities.kt).
 */
@Database(
    entities = [
        ChildProfileEntity::class, SessionEntity::class, EpisodeEntity::class, PlayLogEntity::class,
        AlarmLogEntity::class, ContextSampleEntity::class, ControlLogEntity::class, SteerWindowEntity::class,
        PhoneLinkEntity::class, SongEntity::class, EngineSnapshotEntity::class,
        RelayCredentialEntity::class, OutboxEntity::class, ProcessedMessageEntity::class,
        SongDownloadEntity::class, SongSelectionEntity::class, PhoneLinkMarkEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun storeDao(): StoreDao
    abstract fun linkDao(): LinkDao

    companion object
}

/** 앱의 데이터베이스(aigyeot.db). 개발 중에는 버전이 바뀌면 지우고 다시 만든다. */
fun AppDatabase.Companion.open(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "aigyeot.db").fallbackToDestructiveMigration(dropAllTables = true).build()
