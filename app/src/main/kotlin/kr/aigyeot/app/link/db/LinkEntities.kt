package kr.aigyeot.app.link.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// 계약 contracts-link.md 8.2의 link 몫 표. 칼럼은 표 그대로다. 표에 PK가 없어 고른 것: 한 행 표(relay_credential·song_selection)는
// 그 표의 칼럼 하나를 PK로 두고 DAO가 한 행만 남긴다(바꿀 때 지우고 넣는다). 이 파일은 link가 고친다.

/** 차 토큰(한 행). token_encrypted는 Keystore 키로 감싼 값의 Base64. 백업에서 뺀다. */
@Entity(tableName = "relay_credential")
data class RelayCredentialEntity(
    @PrimaryKey @ColumnInfo(name = "car_id") val carId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "token_encrypted") val tokenEncrypted: String,
)

/** 보낼 함. type은 메시지 5종과 records_clear(body_json 빈 문자열, 8.5). */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String, // ULID
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "to_device") val toDevice: String?, // state_request의 답에만
    @ColumnInfo(name = "body_json") val bodyJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "next_try_at") val nextTryAt: Long,
)

/** 처리한 메시지 id(7일). */
@Entity(tableName = "processed_message")
data class ProcessedMessageEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "seq") val seq: Long,
    @ColumnInfo(name = "processed_at") val processedAt: Long,
)

/** 받을 노래 파일. */
@Entity(tableName = "song_download")
data class SongDownloadEntity(
    @PrimaryKey @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "added_seq") val addedSeq: Long,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "next_try_at") val nextTryAt: Long,
)

/** 마지막 곡 선택(한 행, 곡을 지워도 남김). */
@Entity(tableName = "song_selection")
data class SongSelectionEntity(
    @PrimaryKey @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "seq") val seq: Long,
)

/** 기기별 마지막 적용 seq와 끊김 표시(8.4). */
@Entity(tableName = "phone_link_mark")
data class PhoneLinkMarkEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "last_seq") val lastSeq: Long,
    @ColumnInfo(name = "unpaired") val unpaired: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
