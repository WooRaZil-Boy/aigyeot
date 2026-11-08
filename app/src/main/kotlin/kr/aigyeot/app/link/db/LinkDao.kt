package kr.aigyeot.app.link.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kr.aigyeot.app.store.db.ChildProfileEntity
import kr.aigyeot.app.store.db.PhoneLinkEntity
import kr.aigyeot.app.store.db.SongEntity

/**
 * link의 쿼리. 다른 갈래의 표(phone_link·song·child_profile·episode)도 여기서 쿼리한다(handoff README 4장).
 * 모두 막는 호출이다. link는 IO 스레드의 `runInTransaction` 안에서 부른다(contracts-link 8.3 '한 Room 트랜잭션').
 */
@Dao
interface LinkDao {
    // ---- relay_credential(한 행) ----
    @Query("SELECT * FROM relay_credential LIMIT 1") fun credential(): RelayCredentialEntity?
    @Query("DELETE FROM relay_credential") fun clearCredential()
    @Insert fun insertCredential(c: RelayCredentialEntity)

    // ---- outbox: 넣은 순서(rowid)대로 보낸다(8.5). store가 넣은 report_summary·records_clear도 같은 순서에 든다 ----
    @Insert fun insertOutbox(o: OutboxEntity)
    @Query("SELECT * FROM outbox ORDER BY rowid LIMIT 1") fun outboxFront(): OutboxEntity?
    @Query("SELECT * FROM outbox ORDER BY rowid") fun outbox(): List<OutboxEntity>
    @Query("DELETE FROM outbox WHERE id = :id") fun deleteOutbox(id: String)
    @Query("UPDATE outbox SET attempts = attempts + 1, next_try_at = :at WHERE id = :id") fun retryOutbox(id: String, at: Long)
    @Query("DELETE FROM outbox WHERE created_at < :before") fun expireOutbox(before: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertSentId(e: SentIdEntity)
    @Query("DELETE FROM sent_id WHERE sent_at < :before") fun expireSentIds(before: Long)
    @Query("DELETE FROM sent_id WHERE id = :id") fun deleteSentId(id: String)
    /** L86: 넣은 순서(rowid, REPLACE는 새 rowid)로 최근 [keep]개만 남긴다. 시계와 무관. */
    @Query("DELETE FROM sent_id WHERE rowid NOT IN (SELECT rowid FROM sent_id ORDER BY rowid DESC LIMIT :keep)") fun capSentIds(keep: Int)

    // ---- processed_message(7일) ----
    @Query("SELECT EXISTS(SELECT 1 FROM processed_message WHERE id = :id)") fun processed(id: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertProcessed(p: ProcessedMessageEntity)
    @Query("DELETE FROM processed_message WHERE processed_at < :before") fun expireProcessed(before: Long)
    @Query("DELETE FROM processed_message") fun clearProcessed()

    // ---- song_download ----
    @Upsert fun putDownload(d: SongDownloadEntity)
    @Query("SELECT * FROM song_download WHERE next_try_at <= :now ORDER BY added_seq LIMIT 1") fun nextDownload(now: Long): SongDownloadEntity?
    @Query("SELECT MIN(next_try_at) FROM song_download") fun nextDownloadAt(): Long?
    @Query("SELECT * FROM song_download ORDER BY added_seq") fun downloads(): List<SongDownloadEntity>
    @Query("DELETE FROM song_download WHERE song_id = :songId") fun deleteDownload(songId: String)
    @Query("UPDATE song_download SET attempts = attempts + 1, next_try_at = :at WHERE song_id = :songId") fun retryDownload(songId: String, at: Long)
    @Query("DELETE FROM song_download") fun clearDownloads()

    // ---- song_selection(한 행) ----
    @Query("SELECT * FROM song_selection LIMIT 1") fun selection(): SongSelectionEntity?
    @Query("DELETE FROM song_selection") fun clearSelection()
    @Insert fun insertSelection(s: SongSelectionEntity)

    // ---- phone_link_mark ----
    @Query("SELECT * FROM phone_link_mark WHERE device_id = :deviceId") fun mark(deviceId: String): PhoneLinkMarkEntity?
    @Upsert fun putMark(m: PhoneLinkMarkEntity)
    @Query("DELETE FROM phone_link_mark") fun clearMarks()
    @Query("DELETE FROM phone_link_mark WHERE unpaired = 1 AND updated_at < :before") fun expireMarks(before: Long)

    // ---- care_applied(한 행, C144) ----
    @Query("SELECT * FROM care_applied LIMIT 1") fun careApplied(): CareAppliedEntity?
    @Upsert fun putCareApplied(c: CareAppliedEntity)
    @Query("DELETE FROM care_applied") fun clearCareApplied()

    // ---- phone_link(store 표) ----
    @Query("SELECT * FROM phone_link ORDER BY linked_at") fun phoneLinks(): List<PhoneLinkEntity>
    @Query("SELECT * FROM phone_link WHERE id = :id") fun phoneLink(id: String): PhoneLinkEntity?
    @Upsert fun putPhoneLink(p: PhoneLinkEntity)
    @Query("DELETE FROM phone_link WHERE id = :id") fun deletePhoneLink(id: String)
    @Query("DELETE FROM phone_link") fun clearPhoneLinks()
    @Query("UPDATE phone_link SET last_seen_at = :at WHERE id = :id") fun setLastSeen(id: String, at: Long)
    /** C303 A12: 알림 받을 보호자 수(H8). 판단 코어 LinkChanged·보낼 함 넣기·store 처음 상태가 이 쿼리 하나를 쓴다. */
    @Query("SELECT COUNT(*) FROM phone_link WHERE role = 'guardian' AND handoff_alerts = 1") fun otherGuardians(): Int
    /** C303 A13: 보낼 폰 수. 보낼 함 넣기·소켓 유지·리포트 만들기가 이 쿼리 하나를 쓴다. */
    @Query("SELECT COUNT(*) FROM phone_link") fun phoneCount(): Int

    // ---- song(store 표) ----
    @Query("SELECT * FROM song") fun songs(): List<SongEntity>
    @Query("SELECT * FROM song WHERE id = :id") fun song(id: String): SongEntity?
    @Upsert fun putSong(s: SongEntity)
    @Query("DELETE FROM song WHERE status != 'ACTIVE'") fun deleteInactiveSongs()

    // ---- child_profile·episode(store 표) ----
    @Query("SELECT * FROM child_profile LIMIT 1") fun profile(): ChildProfileEntity?
    @Query("UPDATE child_profile SET last_feeding_start = :at") fun setLastFeedingStart(at: Long)
    /** 결정 ③(V7): handoff_alert를 보낼 함에 넣은 트랜잭션에서만 부른다(LinkRoom.enqueue). guardian_notified를 쓰는 유일한 곳. */
    @Query("UPDATE episode SET guardian_notified = 1 WHERE id = :episodeId") fun markGuardianNotified(episodeId: String)
}
