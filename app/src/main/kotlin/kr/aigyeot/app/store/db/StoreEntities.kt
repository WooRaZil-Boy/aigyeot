package kr.aigyeot.app.store.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.Unjudged

// 계약 contracts-car.md 6.1의 store 몫 표. 칼럼 이름·타입·PK·인덱스는 표 그대로다(시각 epoch ms Long, 열거 String, 목록 JSON String).
// 이 파일은 ui-store-host(store)가 고친다. 엔티티 목록·버전은 AppDatabase(조율 담당)에 있다.
// 주의: 부모 행(session·episode)을 @Insert(REPLACE)로 쓰면 지웠다 넣어 CASCADE로 자식 행이 지워진다. @Upsert나 @Update를 쓴다.

/** 13장 ChildProfile(한 행). */
@Entity(tableName = "child_profile")
data class ChildProfileEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "nickname") val nickname: String,
    @ColumnInfo(name = "birth_ym") val birthYm: String,
    @ColumnInfo(name = "carseat") val carseat: String,
    @ColumnInfo(name = "feeding_interval_min") val feedingIntervalMin: Int?,
    @ColumnInfo(name = "last_feeding_start") val lastFeedingStart: Long?,
    @ColumnInfo(name = "methods") val methods: List<Method>,
    @ColumnInfo(name = "has_sibling") val hasSibling: Boolean,
    @ColumnInfo(name = "front_fallback_allowed") val frontFallbackAllowed: Boolean,
    @ColumnInfo(name = "consent_at") val consentAt: Long,
)

/** 13장 Session. */
@Entity(tableName = "session", indices = [Index("started_at")])
data class SessionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "trigger") val trigger: String,
    @ColumnInfo(name = "mic_path") val micPath: String?,
    @ColumnInfo(name = "output_route") val outputRoute: String?,
    @ColumnInfo(name = "drive_min") val driveMin: Int,
    @ColumnInfo(name = "played_ms") val playedMs: Long,
    @ColumnInfo(name = "cap_reached") val capReached: Boolean,
    @ColumnInfo(name = "rest_log") val restLog: String, // 판단 코어 SessionRecord.restLog가 이미 JSON이다
    @ColumnInfo(name = "restore") val restore: String?,
    @ColumnInfo(name = "report_message_id") val reportMessageId: String?,
    @ColumnInfo(name = "report_pending") val reportPending: Boolean,
    @ColumnInfo(name = "scenario_id") val scenarioId: String?,
    @ColumnInfo(name = "driver_user_id") val driverUserId: Int,
)

/** 13장 Episode. */
@Entity(
    tableName = "episode",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["session_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_id"), Index("first_result", "started_at")],
)
data class EpisodeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "trigger") val trigger: String,
    @ColumnInfo(name = "alarm_at") val alarmAt: Long?,
    @ColumnInfo(name = "alarm_delay_ms") val alarmDelayMs: Long?,
    @ColumnInfo(name = "first_method") val firstMethod: String?,
    @ColumnInfo(name = "first_song_id") val firstSongId: String?,
    @ColumnInfo(name = "first_result") val firstResult: String?,
    @ColumnInfo(name = "first_unjudged") val firstUnjudged: String?,
    @ColumnInfo(name = "second_action") val secondAction: String?,
    @ColumnInfo(name = "second_method") val secondMethod: String?,
    @ColumnInfo(name = "second_song_id") val secondSongId: String?,
    @ColumnInfo(name = "second_result") val secondResult: String?,
    @ColumnInfo(name = "handoff_cause") val handoffCause: String?,
    @ColumnInfo(name = "handoff_kind") val handoffKind: String?,
    @ColumnInfo(name = "waypoint_added") val waypointAdded: Boolean,
    @ColumnInfo(name = "guardian_notified") val guardianNotified: Boolean,
    @ColumnInfo(name = "cry_ms") val cryMs: Long,
    @ColumnInfo(name = "recried") val recried: Boolean,
    @ColumnInfo(name = "played_ms") val playedMs: Long,
    @ColumnInfo(name = "exclude_reasons") val excludeReasons: List<Unjudged>,
)

/** 재생마다 한 줄. */
@Entity(
    tableName = "play_log",
    foreignKeys = [
        ForeignKey(SessionEntity::class, ["id"], ["session_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(EpisodeEntity::class, ["id"], ["episode_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("session_id"), Index("episode_id")],
)
data class PlayLogEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "episode_id") val episodeId: String?,
    @ColumnInfo(name = "purpose") val purpose: String,
    @ColumnInfo(name = "method") val method: String,
    @ColumnInfo(name = "song_id") val songId: String?,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    @ColumnInfo(name = "played_ms") val playedMs: Long,
    @ColumnInfo(name = "end_reason") val endReason: String,
)

/** G4: 기록만 한 경보. 표는 FK만 적어 CASCADE는 다른 기록 표와 맞춘 것이다. */
@Entity(
    tableName = "alarm_log",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["session_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_id")],
)
data class AlarmLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "first_at") val firstAt: Long,
    @ColumnInfo(name = "last_at") val lastAt: Long,
    @ColumnInfo(name = "reason") val reason: String,
)

/** 13장 ContextSample(F17). */
@Entity(
    tableName = "context_sample",
    primaryKeys = ["episode_id", "at"],
    foreignKeys = [ForeignKey(EpisodeEntity::class, ["id"], ["episode_id"], onDelete = ForeignKey.CASCADE)],
)
data class ContextSampleEntity(
    @ColumnInfo(name = "episode_id") val episodeId: String,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "speed_kmh") val speedKmh: Double,
    @ColumnInfo(name = "brake") val brake: Boolean,
    @ColumnInfo(name = "steering_deg") val steeringDeg: Double,
    @ColumnInfo(name = "turn_signal") val turnSignal: String,
    @ColumnInfo(name = "gear") val gear: String,
    @ColumnInfo(name = "cabin_c") val cabinC: Double?,
    @ColumnInfo(name = "window_pos") val windowPos: Int?,
    @ColumnInfo(name = "playback") val playback: String?,
    @ColumnInfo(name = "cry_p") val cryP: Double?,
)

/** 13장 ControlLog(F19). exec가 만들고 호스트가 store와 HostApi.controlLog로 넘긴다. */
@Entity(tableName = "control_log", indices = [Index("at"), Index("session_id")])
data class ControlLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "api") val api: String,
    @ColumnInfo(name = "target") val target: String,
    @ColumnInfo(name = "goal") val goal: String,
    @ColumnInfo(name = "actual") val actual: String?,
    @ColumnInfo(name = "ack_ms") val ackMs: Long?,
    @ColumnInfo(name = "applied_ms") val appliedMs: Long?,
    @ColumnInfo(name = "ok") val ok: Boolean,
    @ColumnInfo(name = "retries") val retries: Int,
)

/** G38 조향 창(F9). CASCADE는 alarm_log와 같은 이유. */
@Entity(
    tableName = "steer_window",
    primaryKeys = ["session_id", "start_at"],
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["session_id"], onDelete = ForeignKey.CASCADE)],
)
data class SteerWindowEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "speed_band") val speedBand: Int,
    @ColumnInfo(name = "excluded") val excluded: Boolean,
    @ColumnInfo(name = "cry_present") val cryPresent: Boolean,
    @ColumnInfo(name = "playing") val playing: Boolean,
    @ColumnInfo(name = "reversals") val reversals: Int,
)

/** 13장 PhoneLink. */
@Entity(tableName = "phone_link")
data class PhoneLinkEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "linked_at") val linkedAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?,
    @ColumnInfo(name = "handoff_alerts") val handoffAlerts: Boolean,
)

/** 13장 Song. */
@Entity(tableName = "song", indices = [Index("status")])
data class SongEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "received_at") val receivedAt: Long?,
    @ColumnInfo(name = "format") val format: String,
    @ColumnInfo(name = "duration_s") val durationS: Double?,
    @ColumnInfo(name = "lufs") val lufs: Double?,
    @ColumnInfo(name = "normalized_path") val normalizedPath: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "reject_reason") val rejectReason: String?,
    @ColumnInfo(name = "added_seq") val addedSeq: Long?,
    @ColumnInfo(name = "last_seq") val lastSeq: Long,
)

/** 3-14 ⑤ 상태 저장(한 행, id = 1). */
@Entity(tableName = "engine_snapshot")
data class EngineSnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 1,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    @ColumnInfo(name = "state") val state: String, // EngineState JSON
)

/** 목록 칼럼 ↔ JSON String(6.1 '공통'). */
class Converters {
    @TypeConverter fun methodsToJson(v: List<Method>): String = Json.encodeToString(v)
    @TypeConverter fun methodsFromJson(v: String): List<Method> = Json.decodeFromString(v)
    @TypeConverter fun unjudgedToJson(v: List<Unjudged>): String = Json.encodeToString(v)
    @TypeConverter fun unjudgedFromJson(v: String): List<Unjudged> = Json.decodeFromString(v)
}
