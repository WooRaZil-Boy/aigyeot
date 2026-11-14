package kr.aigyeot.engine

import kotlinx.serialization.Serializable

// 모든 사건은 at(epoch ms)을 싣는다.
@Serializable
sealed interface Event { val at: Long }

/**
 * 비동기 결과 사건. [ref] = 시작할 때 낸 id(askId·cmdId·playId·utteranceId·listenId). JSON 칸은 그대로다(ref는 계산 값).
 * 처리는 waitingFor(Rules.kt)로 그 id의 대기 항목을 찾아 거기 잡아 둔 문맥만 쓴다(결과가 온 순간의 상태로 대상을 다시 정하지 않는다).
 */
sealed interface Reply : Event { val ref: String }
/** 재생 결과(Playback*·FocusChanged). 가리키는 대기는 낼 때 잡은 Play 하나다. */
sealed interface PlayReply : Reply

// 시간·전원
@Serializable data class Tick(override val at: Long) : Event
@Serializable data class Boot(override val at: Long, val gear: Gear?, val userId: Int, val stopEnded: Boolean = false) : Event
@Serializable data class Suspend(override val at: Long) : Event
@Serializable data class Resume(override val at: Long) : Event

// 감지
@Serializable data class CrySegment(
    override val at: Long, val start: Long, val end: Long, val p: Double, val alarm: Boolean,
    val injectedCryAt: Long? = null,
) : Event
@Serializable data class InputLost(override val at: Long, val reason: PauseReason) : Event
@Serializable data class InputRestored(override val at: Long, val reason: PauseReason) : Event
@Serializable data class MicService(override val at: Long, val running: Boolean, val path: MicPath?) : Event

// 차량
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
    val restsPending: Boolean = false,   // 쉼터 목록을 다시 요청해 기다리는 중(빈 rests는 '쉼터 없음'이 아니다). 장면 주입에 키가 없으면 거짓
) : Event

// 오디오·음성
@Serializable data class CallMode(override val at: Long, val inCall: Boolean) : Event
@Serializable data class FocusChanged(override val at: Long, val playId: String, val change: FocusChange) : Event, PlayReply { override val ref get() = playId }
@Serializable data class PlaybackStarted(
    override val at: Long, val playId: String, val routedDevice: String?, val fallback: Boolean,
) : Event, PlayReply { override val ref get() = playId }
@Serializable data class PlaybackRejected(override val at: Long, val playId: String, val reason: RejectReason) :
    Event, PlayReply { override val ref get() = playId }
@Serializable data class PlaybackEnded(
    override val at: Long, val playId: String, val reason: EndReason, val recorded: Boolean = false,
) : Event, PlayReply { override val ref get() = playId }
@Serializable data class SpeechDone(override val at: Long, val utteranceId: String, val spoken: Boolean) : Event, Reply { override val ref get() = utteranceId }
@Serializable data class Answer(override val at: Long, val askId: String, val answer: YesNo) : Event, Reply { override val ref get() = askId }

// 제어 결과
@Serializable data class ControlResult(
    override val at: Long, val cmdId: String, val control: ControlKind, val area: Area?, val ok: Boolean,
    val before: Double?, val after: Double?,
) : Event, Reply { override val ref get() = cmdId }
@Serializable data class WaypointResult(
    override val at: Long, val cmdId: String, val restId: String, val ok: Boolean,
) : Event, Reply { override val ref get() = cmdId }

// 운전자·화면·휴대폰
@Serializable data class DriverStop(override val at: Long, val via: Via, val listenId: String? = null) : Event
@Serializable data class SwitchSound(override val at: Long, val method: Method, val listenId: String? = null) : Event
@Serializable data class DriverAddWaypoint(override val at: Long, val restId: String, val listenId: String? = null) : Event
@Serializable data class AskButton(override val at: Long) : Event
@Serializable data class Heard(override val at: Long, val listenId: String, val text: String?) : Event, Reply { override val ref get() = listenId }
// fallbackTopics는 대체 답을 호스트가 같은 방식으로 짜게 하는 주제. 비어 있지 않으면 text 대신 호스트가 큐에서 처리할 때 상태로 주제 문장을 짠다.
// careUsed: 답이 돌봄 값을 썼는가. 질문 중 돌봄 요약이 바뀌면 true인 답만 버린다.
@Serializable data class QaReply(
    override val at: Long, val listenId: String, val text: String, val careUsed: Boolean = true, val llm: Boolean = false,
    val fallback: String = "", val fallbackCare: Boolean = false, val factTopics: List<String> = emptyList(),
    val fallbackTopics: List<String> = emptyList(),
) : Event, Reply { override val ref get() = listenId }
@Serializable data class BabyAboard(override val at: Long, val from: BabyAboardFrom) : Event
@Serializable data class FeedingStarted(
    override val at: Long, val startedAt: Long, val from: FeedingFrom, val msgId: String?,
) : Event
@Serializable data class FeedingConfirmed(override val at: Long, val yes: Boolean) : Event
@Serializable data class ProfileChanged(override val at: Long, val profile: Profile?) : Event
@Serializable data class ClearRecords(override val at: Long) : Event
@Serializable data class MethodsChanged(override val at: Long, val methods: List<Method>) : Event
@Serializable data class LinkChanged(override val at: Long, val otherGuardians: Int) : Event
// room: 중계 방(차 id). 같은 asOf의 seq는 같은 방일 때만 견준다(장면 주입은 null). seq: 그 메시지의 중계 seq(장면 주입은 null)
@Serializable data class CareSummary(override val at: Long, val summary: CareView, val seq: Long? = null, val room: String? = null) : Event
@Serializable data class CareCleared(override val at: Long) : Event   // 요약을 보낸 owner가 연결에서 빠졌다
