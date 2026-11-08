package kr.aigyeot.engine

import kotlinx.serialization.Serializable

// 계약 6.1 Room 테이블의 칼럼을 camelCase로 옮긴 기록(store가 저장한다). 시각은 epoch ms.

/** `session` 행. */
@Serializable
data class SessionRecord(
    val id: String,
    val startedAt: Long, val endedAt: Long?,
    val trigger: OpenTrigger,
    val micPath: MicPath?,
    val outputRoute: OutputRoute?,
    val driveMin: Int,
    val playedMs: Long,
    val capReached: Boolean,
    val restLog: String,              // 휴식 기록 JSON(내용 형식은 계약에 없음)
    val restore: String?,             // 바꾼 차 상태 JSON
    val reportMessageId: String?,
    val reportPending: Boolean,
    val scenarioId: String?,
    val driverUserId: Int,
)

/** `episode` 행. */
@Serializable
data class EpisodeRecord(
    val id: String,
    val sessionId: String,
    val startedAt: Long, val endedAt: Long?,
    val trigger: EpisodeTrigger,
    val alarmAt: Long?, val alarmDelayMs: Long?,
    val firstMethod: MethodKind?, val firstSongId: String?,
    val firstResult: TryResult?, val firstUnjudged: Unjudged?,
    val secondAction: SecondAction?, val secondMethod: MethodKind?, val secondSongId: String?,
    val secondResult: TryResult?,
    val handoffCause: HandoffCause?,
    val handoffKind: HandoffKind?,
    val waypointAdded: Boolean,
    val guardianNotified: Boolean,
    val handoffRestName: String? = null,
    val handoffToldDriver: Boolean? = null,
    val cryMs: Long,
    val recried: Boolean,
    val playedMs: Long,
    val excludeReasons: List<Unjudged>,   // 1차 뒤에 생긴 이유(`laterReasons`). 칼럼은 JSON
)

/** `play_log` 행. */
@Serializable
data class PlayRecord(
    val id: String,                       // playId
    val sessionId: String,
    val episodeId: String?,
    val purpose: PlayPurpose,
    val method: MethodKind, val songId: String?,
    val startedAt: Long?, val endedAt: Long,
    val playedMs: Long,
    val endReason: PlayEndReason,
)

/** `steer_window` 행. */
@Serializable
data class SteerWindowRecord(
    val sessionId: String,
    val startAt: Long,
    val speedBand: Int,
    val excluded: Boolean,
    val cryPresent: Boolean,
    val playing: Boolean,
    val reversals: Int,
)
