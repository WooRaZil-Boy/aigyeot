@file:OptIn(ExperimentalSerializationApi::class)

package kr.aigyeot.engine

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

// 계약 4장. SendToPhone의 `type` 필드와 부딪치지 않게 구분자 이름을 `cmd`로 둔다.
@Serializable
@JsonClassDiscriminator("cmd")
sealed interface Command

// 4.1 재생·음성
@Serializable data class StartPlayback(
    val playId: String, val kind: PlayKind, val method: Method, val endAt: Long, val fadeMs: Long,
) : Command
@Serializable data class StopPlayback(val playId: String, val fade: Boolean) : Command
@Serializable data class AdjustPlayback(val playId: String, val mode: PlayMode) : Command
@Serializable data object ReleaseAudio : Command
@Serializable data class Speak(val utteranceId: String, val sid: Sid, val vars: Map<String, String>) : Command
@Serializable data class Ask(
    val askId: String, val sid: Sid, val vars: Map<String, String>, val listenMs: Long,
) : Command
@Serializable data class Listen(val listenId: String, val ms: Long) : Command
@Serializable data class Qa(val listenId: String, val text: String) : Command
@Serializable data object CancelSpeech : Command

// 4.2 차 제어·내비
@Serializable data class SetSeatHeat(val cmdId: String, val areas: List<Area>, val level: Int = 0) : Command
@Serializable data class SetWindowLock(val cmdId: String, val values: Map<Area, Boolean>) : Command
@Serializable data class SetReadingLight(val cmdId: String, val areas: List<Area>, val on: Boolean = false) : Command
@Serializable data class LowerHvac(val cmdId: String, val areas: List<Area>, val deltaC: Double) : Command
@Serializable data class AddWaypoint(val cmdId: String, val restId: String) : Command

// 4.3 화면·휴대폰·기록
@Serializable data class ShowNotice(val kind: Notice) : Command
@Serializable data class SendToPhone(val type: String, val body: JsonObject) : Command
@Serializable data class SaveSession(val record: SessionRecord) : Command
@Serializable data class SaveEpisode(val record: EpisodeRecord) : Command
@Serializable data class SavePlay(val record: PlayRecord) : Command
@Serializable data class LogAlarm(val sessionId: String, val at: Long, val reason: AlarmOnly) : Command
@Serializable data class CaptureContext(val episodeId: String, val alarmAt: Long) : Command
@Serializable data class SaveSteerWindow(val record: SteerWindowRecord) : Command
@Serializable data class CloseSession(val sessionId: String) : Command
@Serializable data class SaveSnapshot(val state: EngineState) : Command
@Serializable data class SaveFeedingStart(val at: Long) : Command
@Serializable data object DeleteRecords : Command
@Serializable data object DeleteRelayRecords : Command
