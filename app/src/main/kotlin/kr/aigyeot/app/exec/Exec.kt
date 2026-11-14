package kr.aigyeot.app.exec

import android.content.Context
import android.util.Log
import kr.aigyeot.app.exec.android.androidIo
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.engine.AddWaypoint
import kr.aigyeot.engine.AdjustPlayback
import kr.aigyeot.engine.Answer
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.CancelSpeech
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.ControlKind
import kr.aigyeot.engine.ControlResult
import kr.aigyeot.engine.DimAmbient
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.Heard
import kr.aigyeot.engine.Listen
import kr.aigyeot.engine.LowerHvac
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.PlaybackRejected
import kr.aigyeot.engine.RejectReason
import kr.aigyeot.engine.ReleaseAudio
import kr.aigyeot.engine.RestoreAmbient
import kr.aigyeot.engine.RestoreAmbientLedger
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
    val lock: Any,
    val sched: Sched,
    val audio: AudioPort,
    val tts: TtsPort,
    val stt: SttPort,
    val tap: MicTap,
    val control: ControlPort,
    val navi: NaviPort,
    val start: () -> Unit = {},
    val stop: () -> Unit = {},
    val reemit: () -> Unit = {}, // 차량 신호·경로의 지금 값을 중복 억제 없이 다시 낸다(장면 끝)
    val lastGear: () -> Gear? = { null }, // 마지막으로 받은 기어(장면 끝 복원의 기준선)
    val resubscribeSpeed: () -> Unit = {}, // 속도 권한이 늦게 허용됐을 때 차량 구독을 다시 한다
    val ambient: AmbientPort = NoAmbient, // 앰비언트 조명
    val ledger: LedgerStore = NoLedger, // 실행기 낮춤 장부. 앱 저장소의 파일 하나
    val carReady: () -> Boolean = { true }, // 차 연결(Vehicle 초기화)이 끝났는가. 끝나기 전 차 제어 명령은 CarGate가 미룬다
    // 연결이 준비될 때 부를 일을 등록한다(실행기 잠금 안에서 불린다)
    val onCarReady: (() -> Unit) -> Unit = {}
)

/**
 * 차·음성 실행기. 판단 코어의 재생·음성·차 제어·내비 할 일을 실행하고 결과와 차량 신호를 사건으로 돌려준다. 판단은 하지 않는다.
 * [handle]·SDK 콜백·예약이 한 잠금 안에서 차례로 돈다. [handle]은 빨리 돌아온다: StartPlayback의 결과(Started·Rejected)는
 * 돌아오기 전에 post한다(재생 시작 결과 먼저). [stop] 뒤에 [start]하면 다시 구독한다(서비스가 다시 뜨는 경우). [start]·[stop]은 여러 번 불려도 안전하다.
 * 공개 모양은 고치지 않는다.
 */
class Exec internal constructor(
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    logControl: (ControlLogEntity) -> Unit, // control_log 한 줄. 호스트가 store와 controlLog 흐름으로 넘긴다
    state: () -> EngineState?, // 최신 상태 읽기(예: 앞에서 틀기 허락)
    private val io: ExecIo
) {
    constructor(
        context: Context,
        clock: () -> Long,
        post: (Event) -> Unit,
        logControl: (ControlLogEntity) -> Unit,
        state: () -> EngineState?
    ) : this(clock, post, logControl, state, androidIo(context.applicationContext, clock, post))

    private val p = Params()
    private val log = ControlLogger(clock, { state()?.session?.id }, logControl)
    private val player = Player(io.audio, io.sched, clock, post, log, { state()?.profile?.frontFallbackAllowed == true }, p)
    private val voice = Voice(io.tts, io.stt, io.tap, io.sched, clock, post, { state()?.car?.micRunning == true })

    /** 화면 읽기 투영: 차가 방금 말한 문장·들은 질문. */
    val said get() = voice.said
    val heard get() = voice.heard
    private val controls = Controls(io.control, io.navi, io.sched, clock, post, log, p) { id -> state()?.car?.route?.rests?.firstOrNull { it.id == id } }

    private val ambients = Ambients(io.ambient, io.sched, clock, post, log, p, io.ledger) { msg -> runCatching { Log.i("Ambient", msg) } }

    private val gate = CarGate(io.sched, p.verifyTimeoutMs * 4, io.carReady).also { g ->
        io.onCarReady { g.readyNow() }
    }

    private var started = false

    /**
     * 차량 신호 구독·통화 모드·내비·TTS 예열·STT 준비(감시 서비스 시작 때). 여러 번 불려도 처음 한 번만 하고,
     * [stop] 뒤에 다시 부르면 다시 구독한다(서비스가 다시 뜨는 경우). 지금 기어·통화 상태를 바로 낸다.
     */
    fun start() = synchronized(io.lock) {
        if (started) return@synchronized
        started = true
        try {
            io.start()
        } catch (e: Exception) {
            log.row(null, "start", "Exec", "done", e.toString(), false)
        }
    }

    /** 말하기·듣기를 끊고 재생을 사건 없이 놓고 구독을 푼다. 시작 안 했거나 두 번 불러도 안전하며, 한 단계가 던져도 나머지 정리가 이어진다. */
    fun stop() = synchronized(io.lock) {
        if (!started) return@synchronized
        started = false
        runCatching { voice.cancel() }
        runCatching { player.release() }
        runCatching { controls.closeAll("stop") }
        runCatching { ambients.closeAll() }
        runCatching { gate.failAll() }
        runCatching { io.stop() }
    }

    /** 발화를 끊는 사건 앞에서 말하는 중인지 묻기와 끊기를 done 콜백과 같은 잠금 안에서 한다. null = 끊었다, 아니면 이미 끝난 발화의 spoken. */
    fun cancelIfSpeaking(id: String): Pair<Boolean, Long>? = synchronized(io.lock) { runCatching { voice.cancelIfSpeaking(id) }.getOrDefault(null) }

    /**
     * 장면 끝 복원 뒤 호스트가 부른다. 지금 차 값(기어·뒷문·통화·실내 온도·뒷좌석 열선·속도 표본·경로)을 중복 억제 없이 다시 내,
     * 장면 동안 장면 상태로 갔거나 override로 버린 실제 신호를 실제 상태가 받게 한다. 받은 적이 없는 신호는 내지 않는다.
     */
    fun reemitAll(): Unit = synchronized(io.lock) { runCatching { io.reemit() } }

    /** 실행기가 마지막으로 받은 기어. 받은 적이 없거나 던지면 null. */
    fun lastGear(): Gear? = synchronized(io.lock) { runCatching { io.lastGear() }.getOrNull() }

    /** 화면의 권한 결과에서 CAR_SPEED가 허용되면 부른다. 실행기 스레드에서 Car가 준비돼 있고 속도가 아직 안 붙었을 때만 다시 구독한다. */
    fun resubscribeSpeed(): Unit = synchronized(io.lock) { runCatching { io.resubscribeSpeed() } }

    /** 받는 곳이 exec인 할 일. 그 밖(Qa·ShowNotice·저장)은 무시한다. */
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
                is SetSeatHeat -> gated(cmd) { controls.seatHeat(cmd) }
                is SetWindowLock -> gated(cmd) { controls.windowLock(cmd) }
                is SetReadingLight -> gated(cmd) { controls.readingLight(cmd) }
                is LowerHvac -> gated(cmd) { controls.lowerHvac(cmd) }
                is AddWaypoint -> controls.waypoint(cmd)
                is DimAmbient -> gated(cmd) { ambients.dim(cmd) }
                is RestoreAmbient -> gated(cmd) { ambients.restore(cmd) }
                is RestoreAmbientLedger -> gated(cmd) { ambients.restoreFromLedger(cmd.cmdId) } // 장부는 판단 코어가 이 명령을 낼 때만 되돌린다
                else -> {}
            }
        } catch (e: Exception) {
            // SDK가 던져도 판단 코어가 결과를 영원히 기다리지 않게 실패 결과를 낸다
            log.row(null, "handle", cmd::class.simpleName.orEmpty(), "done", e.toString(), false)
            failed(cmd).forEach(post)
        }
    }

    /** 차 제어 명령의 입구 하나: 연결 전이면 미뤘다가 준비되면 순서대로, 시간 안에 안 되면 실패 결과. */
    private fun gated(cmd: Command, run: () -> Unit) = gate.submit({
        try {
            run()
        } catch (e: Exception) {
            log.row(null, "handle", cmd::class.simpleName.orEmpty(), "done", e.toString(), false)
            failed(cmd).forEach(post)
        }
    }, { failed(cmd).forEach(post) }, defer = cmd !is DimAmbient)

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
            is DimAmbient -> listOf(ControlKind.AMBIENT_BRIGHTNESS, ControlKind.AMBIENT_COLOR).map { ControlResult(at, cmd.cmdId, it, null, false, null, null) }
            is RestoreAmbient -> listOfNotNull(
                ControlKind.AMBIENT_BRIGHTNESS.takeIf { cmd.brightness != null },
                ControlKind.AMBIENT_COLOR.takeIf { cmd.color != null }
            ).map { ControlResult(at, cmd.cmdId, it, null, false, null, null) }
            else -> emptyList()
        }
    }
}
