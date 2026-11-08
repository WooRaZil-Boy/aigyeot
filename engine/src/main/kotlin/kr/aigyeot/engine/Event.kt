package kr.aigyeot.engine

import kotlinx.serialization.Serializable

// 계약 3장. 모든 사건은 at(epoch ms)을 싣는다.
@Serializable
sealed interface Event { val at: Long }

// 3.1 시간·전원
@Serializable data class Tick(override val at: Long) : Event
@Serializable data class Boot(override val at: Long, val gear: Gear?, val userId: Int) : Event
@Serializable data class Suspend(override val at: Long) : Event
@Serializable data class Resume(override val at: Long) : Event

// 3.2 감지
@Serializable data class CrySegment(
    override val at: Long, val start: Long, val end: Long, val p: Double, val alarm: Boolean,
    val injectedCryAt: Long? = null,
) : Event
@Serializable data class InputLost(override val at: Long, val reason: PauseReason) : Event
@Serializable data class InputRestored(override val at: Long, val reason: PauseReason) : Event
@Serializable data class MicService(override val at: Long, val running: Boolean, val path: MicPath?) : Event

// 3.3 차량
@Serializable data class GearChanged(override val at: Long, val gear: Gear) : Event
@Serializable data class RearDoor(override val at: Long, val side: Side, val open: Boolean) : Event
@Serializable data class SeatHeat(override val at: Long, val area: Area, val level: Int) : Event
@Serializable data class CabinTemp(override val at: Long, val celsius: Double) : Event
@Serializable data class VehicleSample(
    override val at: Long, val speedKmh: Double, val brake: Boolean, val steeringDeg: Double,
    val turnSignal: TurnSignal,
) : Event
@Serializable data class RouteState(
    override val at: Long, val hasDestination: Boolean, val remainingSec: Int?, val rests: List<RestCandidate>,
    val remainingKm: Double? = null,
) : Event

// 3.4 오디오·음성
@Serializable data class CallMode(override val at: Long, val inCall: Boolean) : Event
@Serializable data class FocusChanged(override val at: Long, val playId: String, val change: FocusChange) : Event
@Serializable data class PlaybackStarted(
    override val at: Long, val playId: String, val routedDevice: String?, val fallback: Boolean,
) : Event
@Serializable data class PlaybackRejected(override val at: Long, val playId: String, val reason: RejectReason) : Event
@Serializable data class PlaybackEnded(
    override val at: Long, val playId: String, val reason: EndReason, val recorded: Boolean = false,
) : Event
@Serializable data class SpeechDone(override val at: Long, val utteranceId: String, val spoken: Boolean) : Event
@Serializable data class Answer(override val at: Long, val askId: String, val answer: YesNo) : Event

// 3.5 제어 결과
@Serializable data class ControlResult(
    override val at: Long, val cmdId: String, val control: ControlKind, val area: Area, val ok: Boolean,
    val before: Double?, val after: Double?,
) : Event
@Serializable data class WaypointResult(
    override val at: Long, val cmdId: String, val restId: String, val ok: Boolean,
) : Event

// 3.6 운전자·화면·휴대폰
@Serializable data class DriverStop(override val at: Long, val via: Via, val listenId: String? = null) : Event
@Serializable data class SwitchSound(override val at: Long, val method: Method, val listenId: String? = null) : Event
@Serializable data class DriverAddWaypoint(override val at: Long, val restId: String, val listenId: String? = null) : Event
@Serializable data class AskButton(override val at: Long) : Event
@Serializable data class Heard(override val at: Long, val listenId: String, val text: String?) : Event
@Serializable data class QaReply(override val at: Long, val listenId: String, val text: String) : Event
@Serializable data class BabyAboard(override val at: Long, val from: BabyAboardFrom) : Event
@Serializable data class FeedingStarted(
    override val at: Long, val startedAt: Long, val from: FeedingFrom, val msgId: String?,
) : Event
@Serializable data class FeedingConfirmed(override val at: Long, val yes: Boolean) : Event
@Serializable data class ProfileChanged(override val at: Long, val profile: Profile?) : Event
@Serializable data class ClearRecords(override val at: Long) : Event
@Serializable data class MethodsChanged(override val at: Long, val methods: List<Method>) : Event
@Serializable data class LinkChanged(override val at: Long, val otherGuardians: Int) : Event
@Serializable data class CareSummary(override val at: Long, val summary: CareView, val seq: Long? = null, val gen: Int = 0, val room: String? = null) : Event   // C119. room: 중계 방(차 id). 같은 asOf의 seq는 같은 방일 때만 견준다(C130, 장면 주입은 null). seq: 그 메시지의 중계 seq(장면 주입은 null, C126 ①)
