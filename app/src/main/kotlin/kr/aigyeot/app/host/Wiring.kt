package kr.aigyeot.app.host

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kr.aigyeot.app.detect.MicService as MicFgs
import kr.aigyeot.app.exec.Exec
import kr.aigyeot.app.link.Link
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.retryOnDbError
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.app.store.db.open
import kr.aigyeot.app.ui.Notices
import kr.aigyeot.engine.*

/** 할 일을 받는 곳(계약 4장 '받는 곳'). */
enum class To { EXEC, STORE, LINK, UI }

/** 할 일 → 받는 곳. 새 할 일이 생기면 컴파일이 여기를 짚는다(sealed). */
fun route(c: Command): To = when (c) {
    is StartPlayback, is StopPlayback, is AdjustPlayback, ReleaseAudio, is Speak, is Ask, is Listen, CancelSpeech,
    is SetSeatHeat, is SetWindowLock, is SetReadingLight, is LowerHvac, is AddWaypoint, is DimAmbient, is RestoreAmbient, is RestoreAmbientLedger -> To.EXEC
    is SendToPhone, is Qa -> To.LINK
    is ShowNotice, is HideNotice -> To.UI
    is SaveSession, is SaveEpisode, is SettleHandoff, is SavePlay, is LogAlarm, is CaptureContext, is SaveSteerWindow, is CloseSession,
    is SaveSnapshot, is SaveFeedingStart, DeleteRecords, DeleteRelayRecords -> To.STORE   // DeleteRelayRecords는 store가 기록 지우기와 한 트랜잭션(C90)
}

/** 호스트가 할 일을 넘기고 link를 부르는 함수들. store는 호스트가 직접 부른다. */
class Targets(
    val exec: (Command) -> Unit, val link: (Command) -> Unit, val notice: (Notice) -> Unit,
    val wake: () -> Unit = {},                                                      // contracts-link 8.6 '바로 다시 확인'(Link.wake)
    val restore: suspend (EngineState, Long) -> List<Event> = { _, _ -> emptyList() },   // 8.3 '복원 뒤 대조'의 수유·보호자·요약(Link.restoreEvents)
    val reemitAll: () -> Unit = {},                                                 // 장면 끝 복원 뒤 지금 차 값·경로를 중복 억제 없이 다시 낸다(Exec.reemitAll, V12·C130)
    val lastGear: () -> Gear? = { null },                                           // C328: 실행기가 마지막으로 받은 기어. 장면 끝 복원 Boot의 기준선(Exec.lastGear)
    val cancelIfSpeaking: (String) -> Pair<Boolean, Long>? = { exec(CancelSpeech); null },    // C208: 이 발화가 아직 말하는 중이면 끊고 null, 이미 끝났으면 그 spoken(Exec.cancelIfSpeaking)
    val linkNow: (List<SendToPhone>, () -> Unit) -> Unit = { cmds, also -> also(); cmds.forEach(link) },   // C160·결정 ③: store 쓰기(also) 먼저, 보낼 함 넣기 나중을 한 트랜잭션으로(Link.enqueueNow)
    val hideNotice: (Notice) -> Unit = {},                                          // C347: HideNotice(Notices.cancel)
)

/** 사건의 at만 바꾼 사본(2.4 'at 찍기'). */
fun Event.withAt(t: Long): Event = if (at == t) this else when (this) {
    is Tick -> copy(at = t); is Boot -> copy(at = t); is Suspend -> copy(at = t); is Resume -> copy(at = t)
    is CrySegment -> copy(at = t); is InputLost -> copy(at = t); is InputRestored -> copy(at = t); is MicService -> copy(at = t)
    is GearChanged -> copy(at = t); is RearDoor -> copy(at = t); is SeatHeat -> copy(at = t); is CabinTemp -> copy(at = t)
    is VehicleSample -> copy(at = t); is RouteState -> copy(at = t); is CallMode -> copy(at = t); is FocusChanged -> copy(at = t)
    is PlaybackStarted -> copy(at = t); is PlaybackRejected -> copy(at = t); is PlaybackEnded -> copy(at = t)
    is SpeechDone -> copy(at = t); is Answer -> copy(at = t); is ControlResult -> copy(at = t); is WaypointResult -> copy(at = t)
    is DriverStop -> copy(at = t); is SwitchSound -> copy(at = t); is DriverAddWaypoint -> copy(at = t); is AskButton -> copy(at = t)
    is Heard -> copy(at = t); is QaReply -> copy(at = t); is BabyAboard -> copy(at = t); is FeedingStarted -> copy(at = t)
    is FeedingConfirmed -> copy(at = t); is ProfileChanged -> copy(at = t); is ClearRecords -> copy(at = t)
    is MethodsChanged -> copy(at = t); is LinkChanged -> copy(at = t); is CareSummary -> copy(at = t); is CareCleared -> copy(at = t)
}

/** Link가 쓰는 사건 길: 첫째는 실제로 받은 사건(장면 override가 막는다), 둘째는 장면 phone 단계 주입(7.3, 막지 않는다). */
internal fun Host.linkSinks(): Pair<(Event) -> Unit, (Event) -> Unit> = ::postReal to ::post

/** 화면·알림이 사건을 넣는 곳(설계 4.4). 화면은 호스트를 모르고 이것만 받는다. */
fun interface EventSink { fun post(e: Event) }

/** V3(P7, C322): 화면 버튼·알림은 실제 세계 입력이라 실제 입구(SceneController.postReal) 하나로 들어간다. 무엇을 넘길지는 이 한 줄에서 정한다. */
internal fun Host.uiSink(): EventSink = EventSink(::postReal)

/**
 * 프로세스에 하나인 모듈 묶음. 화면과 감시 서비스가 같은 것을 쓴다.
 * 만들 때 호스트를 바로 시작해 복원(①~⑤)이 큐의 첫 일이 된다(그 뒤 화면·마이크 사건이 들어온다).
 */
class Graph private constructor(ctx: Context) {
    val clock: () -> Long = System::currentTimeMillis
    val db: AppDatabase = AppDatabase.open(ctx)
    private lateinit var hostRef: Host
    val store = Store(db, clock, scenarioId = { hostRef.scenarioId })
    val host = Host(store, clock).also { hostRef = it }
    private val latest = { host.steps.value?.state }
    val exec = Exec(ctx, clock, host::postReal, host::logControl, latest)
    private val sinks = host.linkSinks()
    val link = Link(ctx, db, clock, sinks.first, latest, sinks.second, stateReady = { host.steps.first { it != null } })
    /** 화면·알림 사건의 입구(V3). */
    val events: EventSink = host.uiSink()

    init {
        host.targets = Targets(exec::handle, link::handle, { Notices.show(ctx, it) }, link::wake, link::restoreEvents, exec::reemitAll, lastGear = exec::lastGear, cancelIfSpeaking = exec::cancelIfSpeaking, linkNow = link::enqueueNow, hideNotice = { Notices.cancel(ctx, it) })
        MicFgs.clock = clock
        // 마이크 사건은 장면 입력원이다: 장면은 실제 마이크를 끄고 장면 PCM을 마이크 서비스에 넣으며(debug 빌드 Scenario.runAll·runOnce), 장면 끝 전에 다시 끈다.
        // 그래서 장면 중 마이크 사건은 늘 장면 소리에서 나오고 override로 거르지 않는 주입 길(post)을 쓴다(C322)
        MicFgs.emit = host::post
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // link가 곡 표를 고치면 store가 후보 ③을 다시 계산한다(명세서 5장, 계약 6.1 '③ 계산')
        db.invalidationTracker.createFlow("song", "song_selection", "child_profile")
            .retryOnDbError("host").onEach { host.methodsMaybeChanged() }.launchIn(scope)   // C293
        // 세션·단계가 바뀔 때만 logcat 한 줄(tag host). 에뮬레이터 실측이 세션 열림을 본다
        host.stepLog.map { it.state.session?.let { x -> "${x.id} ${x.phase}" } ?: "IDLE" }.distinctUntilChanged()
            .onEach { Log.i("host", "stepLog 세션 $it") }.launchIn(scope)
        startModules()
        host.start()   // exec가 먼저 서야 복원이 내는 할 일(재잠금 등)이 받을 곳이 있다
    }

    private var modulesOn = false
    @Synchronized fun startModules() { if (!modulesOn) { modulesOn = true; exec.start(); link.start() } }
    @Synchronized fun stopModules() { if (modulesOn) { modulesOn = false; exec.stop(); link.stop() } }

    companion object {
        @Volatile private var g: Graph? = null
        fun get(ctx: Context): Graph = g ?: synchronized(this) { g ?: Graph(ctx.applicationContext).also { g = it } }
    }
}
