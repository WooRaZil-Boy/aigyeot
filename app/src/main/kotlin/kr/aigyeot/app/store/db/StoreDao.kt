package kr.aigyeot.app.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kr.aigyeot.app.link.db.OutboxEntity
import kr.aigyeot.app.link.db.SongSelectionEntity

/** store의 쿼리. 다른 갈래의 표(outbox·song_selection)도 여기서 쿼리한다(handoff README 4장). 부모 행은 @Upsert로 쓴다(REPLACE는 CASCADE로 자식을 지운다). */
@Dao
interface StoreDao {
    // ---- child_profile(한 행) ----
    @Query("SELECT * FROM child_profile LIMIT 1") fun profile(): ChildProfileEntity?
    @Query("SELECT * FROM child_profile LIMIT 1") fun profileFlow(): Flow<ChildProfileEntity?>
    @Upsert fun upsertProfile(p: ChildProfileEntity)
    /** F1·F6: 저장된 값보다 나중일 때만 바꾼다(계약 4.3 SaveFeedingStart). */
    @Query("UPDATE child_profile SET last_feeding_start = :at WHERE last_feeding_start IS NULL OR last_feeding_start < :at")
    fun saveFeedingStart(at: Long)

    // ---- session ----
    @Query("SELECT * FROM session WHERE id = :id") fun session(id: String): SessionEntity?
    @Upsert fun upsertSession(s: SessionEntity)
    @Query("SELECT * FROM session WHERE ended_at IS NOT NULL AND report_pending = 1") fun pendingReports(): List<SessionEntity>
    @Query("UPDATE session SET report_pending = 0, report_message_id = :msgId WHERE id = :id") fun reportDone(id: String, msgId: String?)
    @Query("SELECT * FROM session ORDER BY started_at DESC") fun sessionsFlow(): Flow<List<SessionEntity>>

    // ---- episode·play_log ----
    @Query("SELECT * FROM episode WHERE id = :id") fun episode(id: String): EpisodeEntity?
    @Query("SELECT * FROM episode WHERE session_id = :sessionId ORDER BY started_at") fun episodes(sessionId: String): List<EpisodeEntity>
    @Upsert fun upsertEpisode(e: EpisodeEntity)
    @Upsert fun upsertPlay(p: PlayLogEntity)
    @Query("SELECT * FROM play_log WHERE session_id = :sessionId ORDER BY started_at") fun plays(sessionId: String): List<PlayLogEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM play_log WHERE id = :playId)") fun playLogged(playId: String): Boolean
    @Query("SELECT COALESCE(SUM(played_ms), 0) FROM play_log WHERE session_id = :sessionId") fun sessionPlayedMs(sessionId: String): Long
    @Query("SELECT COALESCE(SUM(played_ms), 0) FROM play_log WHERE episode_id = :episodeId") fun episodePlayedMs(episodeId: String): Long

    /** 판정된 1차 시도(성적·history 입력, 계약 6.1 MethodStat 쿼리와 같은 조건). 장면 세션은 뺀다(G44). */
    @Query(
        """SELECT e.started_at AS at, p.method AS method, p.song_id AS songId, e.first_result AS result
           FROM episode e JOIN session s ON s.id = e.session_id
                          JOIN play_log p ON p.episode_id = e.id AND p.purpose = 'AUTO_FIRST'
           WHERE e.first_result IN ('SUCCESS', 'FAIL') AND e.started_at >= :since AND s.scenario_id IS NULL
           ORDER BY e.started_at"""
    )
    fun firstTries(since: Long): List<FirstTryRow>

    // ---- alarm_log(G3: 이어진 경보는 한 줄) ----
    @Query("SELECT * FROM alarm_log WHERE session_id = :sessionId ORDER BY id DESC LIMIT 1") fun lastAlarm(sessionId: String): AlarmLogEntity?
    @Insert fun insertAlarm(a: AlarmLogEntity)
    @Query("UPDATE alarm_log SET last_at = :at WHERE id = :id") fun extendAlarm(id: Long, at: Long)

    // ---- context_sample·steer_window·control_log ----
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertContext(rows: List<ContextSampleEntity>)
    @Query("SELECT * FROM context_sample WHERE episode_id = :episodeId ORDER BY at") fun context(episodeId: String): List<ContextSampleEntity>
    @Upsert fun upsertSteer(w: SteerWindowEntity)
    @Query("SELECT * FROM steer_window WHERE session_id = :sessionId ORDER BY start_at") fun steerWindows(sessionId: String): List<SteerWindowEntity>
    /** 같은 운전자의 평소 창(울음·재생 없음, 방향지시등 없음, 실제 세션만). 기준선 비교용(G38). */
    @Query(
        """SELECT w.* FROM steer_window w JOIN session s ON s.id = w.session_id
           WHERE s.driver_user_id = :driver AND s.scenario_id IS NULL AND w.excluded = 0 AND w.cry_present = 0 AND w.playing = 0"""
    )
    fun baselineWindows(driver: Int): List<SteerWindowEntity>
    @Insert fun insertControl(c: ControlLogEntity)
    @Query("SELECT * FROM control_log WHERE session_id = :sessionId ORDER BY at") fun controls(sessionId: String): List<ControlLogEntity>

    // ---- engine_snapshot(한 행) ----
    @Query("SELECT * FROM engine_snapshot WHERE id = 1") fun snapshot(): EngineSnapshotEntity?
    @Upsert fun saveSnapshot(s: EngineSnapshotEntity)

    // ---- 다른 갈래 표(link 몫): 읽기와 store가 넣는 outbox 항목 ----
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertOutbox(o: OutboxEntity)
    @Query("SELECT COUNT(*) FROM phone_link") fun phoneCount(): Int
    @Query("SELECT COUNT(*) FROM phone_link WHERE role = 'guardian' AND handoff_alerts = 1") fun otherGuardians(): Int
    @Query("SELECT * FROM song WHERE status = 'ACTIVE' ORDER BY added_seq DESC") fun activeSongs(): List<SongEntity>
    @Query("SELECT * FROM song WHERE status = 'ACTIVE' ORDER BY added_seq DESC") fun activeSongsFlow(): Flow<List<SongEntity>>
    @Query("SELECT * FROM song WHERE id = :id") fun song(id: String): SongEntity?
    @Query("SELECT * FROM song_selection LIMIT 1") fun selection(): SongSelectionEntity?
    @Query("SELECT * FROM song_selection LIMIT 1") fun selectionFlow(): Flow<SongSelectionEntity?>

    // ---- 모든 기록 지우기(5.9, C40)·90일 보관(G40) ----
    @Query("DELETE FROM context_sample") fun clearContext()
    @Query("DELETE FROM play_log") fun clearPlays()
    @Query("DELETE FROM alarm_log") fun clearAlarms()
    @Query("DELETE FROM steer_window") fun clearSteer()
    @Query("DELETE FROM episode") fun clearEpisodes()
    @Query("DELETE FROM session") fun clearSessions()
    @Query("DELETE FROM control_log") fun clearControls()
    @Query("DELETE FROM outbox WHERE type IN ('report_summary', 'handoff_alert')") fun clearReportOutbox()
    /** 자식 표는 FK CASCADE로 함께 지워진다. 열린 세션(ended_at 없음)은 남긴다. */
    @Query("DELETE FROM session WHERE ended_at IS NOT NULL AND ended_at < :cut") fun purgeSessions(cut: Long)
    /** 끝나지 못한 장면 세션을 닫는다. report_pending은 두지 않는다(장면은 리포트를 보내지 않는다, G44). */
    @Query("UPDATE session SET ended_at = :at WHERE scenario_id IS NOT NULL AND ended_at IS NULL") fun closeScenarioSessions(at: Long)
    @Query("DELETE FROM control_log WHERE at < :cut") fun purgeControls(cut: Long)
}

/** [StoreDao.firstTries]의 한 줄. */
data class FirstTryRow(val at: Long, val method: String, val songId: String?, val result: String)
