@file:OptIn(ExperimentalSerializationApi::class)

package kr.aigyeot.engine

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

// SendToPhone의 `type` 필드와 부딪치지 않게 구분자 이름을 `cmd`로 둔다.
@Serializable
@JsonClassDiscriminator("cmd")
sealed interface Command

// 재생·음성
@Serializable data class StartPlayback(val playId: String, val kind: PlayKind, val method: Method, val endAt: Long, val fadeMs: Long) : Command

@Serializable data class StopPlayback(val playId: String, val fade: Boolean) : Command

@Serializable data class AdjustPlayback(val playId: String, val mode: PlayMode) : Command

@Serializable data object ReleaseAudio : Command

@Serializable data class Speak(val utteranceId: String, val sid: Sid, val vars: Map<String, String>) : Command

@Serializable data class Ask(val askId: String, val sid: Sid, val vars: Map<String, String>, val listenMs: Long) : Command

@Serializable data class Listen(val listenId: String, val ms: Long) : Command

@Serializable data class Qa(val listenId: String, val text: String) : Command

@Serializable data object CancelSpeech : Command

// 차 제어·내비
@Serializable data class SetSeatHeat(val cmdId: String, val areas: List<Area>, val level: Int = 0) : Command

@Serializable data class SetWindowLock(val cmdId: String, val values: Map<Area, Boolean>) : Command

@Serializable data class SetReadingLight(val cmdId: String, val areas: List<Area>, val on: Boolean = false) : Command

@Serializable data class LowerHvac(val cmdId: String, val areas: List<Area>, val deltaC: Double) : Command

/** 앰비언트: 밝기·색 낮춤. 결과는 ControlResult(AMBIENT_BRIGHTNESS·AMBIENT_COLOR) 둘(area = null). 영역은 실행기가 정한다 */
@Serializable data class DimAmbient(val cmdId: String, val level: Double, val color: Int) : Command

/** 앰비언트: 되돌림. 있는 종류만. 실행기는 지금 값이 그 종류의 set과 같을 때만 before를 쓴다 */
@Serializable data class RestoreAmbient(val cmdId: String, val brightness: AmbientValue?, val color: AmbientValue?) : Command

/**
 * 앰비언트: 실행기 낮춤 장부에 남은 낮춤을 되돌린다. `Boot`에서 `ign.ambient`가 Dimmed가 아닐 때만 낸다.
 * 결과 사건은 없다(ControlResult를 내지 않는다)
 */
@Serializable data class RestoreAmbientLedger(val cmdId: String) : Command

@Serializable data class AddWaypoint(val cmdId: String, val restId: String) : Command

// 화면·휴대폰·기록
@Serializable data class ShowNotice(val kind: Notice) : Command

@Serializable data class HideNotice(val kind: Notice) : Command

@Serializable data class SendToPhone(val type: String, val body: JsonObject) : Command

@Serializable data class SaveSession(val record: SessionRecord) : Command

@Serializable data class SaveEpisode(val record: EpisodeRecord) : Command

/**
 * 인계 확정. 그 에피소드 행의 handoff_kind·handoff_told_driver·handoff_rest_name만 쓴다.
 * 판단 코어·장면 끝이 같은 규칙([settleHandoff])으로 낸다.
 */
@Serializable data class SettleHandoff(val episodeId: String, val kind: HandoffKind, val toldDriver: Boolean?, val restName: String?) : Command

@Serializable data class SavePlay(val record: PlayRecord) : Command

@Serializable data class LogAlarm(val sessionId: String, val at: Long, val reason: AlarmOnly) : Command

@Serializable data class CaptureContext(val episodeId: String, val alarmAt: Long) : Command

@Serializable data class SaveSteerWindow(val record: SteerWindowRecord) : Command

@Serializable data class CloseSession(val sessionId: String) : Command

@Serializable data class SaveSnapshot(val state: EngineState) : Command

@Serializable data class SaveFeedingStart(val at: Long) : Command

@Serializable data object DeleteRecords : Command

@Serializable data object DeleteRelayRecords : Command
