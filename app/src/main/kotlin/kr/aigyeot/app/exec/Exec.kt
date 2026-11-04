package kr.aigyeot.app.exec

import android.content.Context
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.engine.AddWaypoint
import kr.aigyeot.engine.AdjustPlayback
import kr.aigyeot.engine.Answer
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.CancelSpeech
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.ControlKind
import kr.aigyeot.engine.ControlResult
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Heard
import kr.aigyeot.engine.Listen
import kr.aigyeot.engine.LowerHvac
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.PlaybackRejected
import kr.aigyeot.engine.RejectReason
import kr.aigyeot.engine.ReleaseAudio
import kr.aigyeot.engine.SetReadingLight
import kr.aigyeot.engine.SetSeatHeat
import kr.aigyeot.engine.SetWindowLock
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.StartPlayback
import kr.aigyeot.engine.StopPlayback
import kr.aigyeot.engine.WaypointResult
import kr.aigyeot.engine.YesNo

/** 실제 연결(또는 시험의 가짜) 묶음. 콜백·예약은 모두 [lock] 안에서 돈다. */
internal class ExecIo(
    val lock: Any, val sched: Sched, val audio: AudioPort, val tts: TtsPort, val stt: SttPort, val tap: MicTap,
    val control: ControlPort, val navi: NaviPort, val start: () -> Unit = {}, val stop: () -> Unit = {},
)

/**
 * 차·음성 실행기. 판단 코어의 재생·음성·차 제어·내비 할 일을 실행하고 결과와 차량 신호를 사건으로 돌려준다. 판단은 하지 않는다.
 * [handle]·SDK 콜백·예약이 한 잠금 안에서 차례로 돈다. [handle]은 빨리 돌아온다: StartPlayback의 결과(Started·Rejected)는
 * 돌아오기 전에 post한다(계약 2.4 '재생 시작 결과 먼저'). [stop] 뒤에 [start]하면 다시 구독한다(서비스가 다시 뜨는 경우). [start]·[stop]은 여러 번 불려도 안전하다.
 * 공개 모양은 고치지 않는다(handoff README 4장).
 */
class Exec internal constructor(
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    logControl: (ControlLogEntity) -> Unit,   // control_log 한 줄(계약 4.2 F19). 호스트가 store와 controlLog 흐름으로 넘긴다
    private val state: () -> EngineState?,     // 최신 상태 읽기(예: 앞에서 틀기 허락 D12)
    private val io: ExecIo,
) {
    constructor(
        context: Context,
        clock: () -> Long,
        post: (Event) -> Unit,
        logControl: (ControlLogEntity) -> Unit,
        state: () -> EngineState?,
    ) : this(clock, post, logControl, state, androidIo(context.applicationContext, clock, post))

    private val p = Params()
    private val log = ControlLogger(clock, { state()?.session?.id }, logControl)
    private val player = Player(io.audio, io.sched, clock, post, log, { state()?.profile?.frontFallbackAllowed == true }, p)
    private val voice = Voice(io.tts, io.stt, io.tap, io.sched, clock, post, { state()?.car?.micRunning == true })
    private val controls = Controls(io.control, io.navi, io.sched, clock, post, log, p) { id -> state()?.car?.route?.rests?.firstOrNull { it.id == id } }

    private var started = false

    /**
     * 차량 신호 구독·통화 모드·내비·TTS 예열·STT 준비(감시 서비스 시작 때). 여러 번 불려도 처음 한 번만 하고,
     * [stop] 뒤에 다시 부르면 다시 구독한다(서비스가 다시 뜨는 경우, ui-store-host 보고). 지금 기어·통화 상태를 바로 낸다.
     */
    fun start() = synchronized(io.lock) {
        if (started) return@synchronized
        started = true
        try { io.start() } catch (e: Exception) { log.row("start", "Exec", "done", e.toString(), false) }
    }

    /** 말하기·듣기를 끊고 재생을 사건 없이 놓고 구독을 푼다. 시작 안 했거나 두 번 불러도 안전하며, 한 단계가 던져도 나머지 정리가 이어진다. */
    fun stop() = synchronized(io.lock) {
        if (!started) return@synchronized
        started = false
        runCatching { voice.cancel() }
        runCatching { player.release() }
        runCatching { controls.closeAll("stop") }
        runCatching { io.stop() }
    }

    /** 계약 4장 '받는 곳'이 exec인 할 일. 그 밖(Qa·ShowNotice·저장)은 무시한다. */
    fun handle(cmd: Command): Unit = synchronized(io.lock) {
        try {
            when (cmd) {
                is StartPlayback -> player.start(cmd)
                is StopPlayback -> player.stop(cmd)
                is AdjustPlayback -> player.adjust(cmd)
                ReleaseAudio -> player.release()
                is Speak -> voice.speak(cmd)
                is Ask -> voice.ask(cmd)
                is Listen -> voice.listen(cmd)
                CancelSpeech -> voice.cancel()
                is SetSeatHeat -> controls.seatHeat(cmd)
                is SetWindowLock -> controls.windowLock(cmd)
                is SetReadingLight -> controls.readingLight(cmd)
                is LowerHvac -> controls.lowerHvac(cmd)
                is AddWaypoint -> controls.waypoint(cmd)
                else -> {}
            }
        } catch (e: Exception) {
            // SDK가 던져도 판단 코어가 결과를 영원히 기다리지 않게 실패 결과를 낸다
            log.row("handle", cmd::class.simpleName.orEmpty(), "done", e.toString(), false)
            failed(cmd).forEach(post)
        }
    }

    private fun failed(cmd: Command): List<Event> {
        val at = clock()
        return when (cmd) {
            is StartPlayback -> listOf(PlaybackRejected(at, cmd.playId, RejectReason.ERROR))
            is Speak -> listOf(SpeechDone(at, cmd.utteranceId, false))
            is Ask -> listOf(SpeechDone(at, cmd.askId, false), Answer(at, cmd.askId, YesNo.NONE))
            is Listen -> listOf(Heard(at, cmd.listenId, null))
            is SetSeatHeat -> cmd.areas.map { ControlResult(at, cmd.cmdId, ControlKind.SEAT_HEAT, it, false, null, null) }
            is SetWindowLock -> cmd.values.keys.map { ControlResult(at, cmd.cmdId, ControlKind.WINDOW_LOCK, it, false, null, null) }
            is SetReadingLight -> cmd.areas.map { ControlResult(at, cmd.cmdId, ControlKind.READING_LIGHT, it, false, null, null) }
            is LowerHvac -> cmd.areas.map { ControlResult(at, cmd.cmdId, ControlKind.HVAC_TEMP, it, false, null, null) }
            is AddWaypoint -> listOf(WaypointResult(at, cmd.cmdId, cmd.restId, false))
            else -> emptyList()
        }
    }
}
