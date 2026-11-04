package kr.aigyeot.app.host

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kr.aigyeot.app.detect.MicService as MicFgs
import kr.aigyeot.app.exec.Exec
import kr.aigyeot.app.link.Link
import kr.aigyeot.app.scenario.Scenario
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.app.store.db.open
import kr.aigyeot.app.ui.Notices
import kr.aigyeot.engine.*

/** 할 일을 받는 곳(계약 4장 '받는 곳'). */
enum class To { EXEC, STORE, LINK, UI }

/** 할 일 → 받는 곳. 새 할 일이 생기면 컴파일이 여기를 짚는다(sealed). */
fun route(c: Command): To = when (c) {
    is StartPlayback, is StopPlayback, is AdjustPlayback, ReleaseAudio, is Speak, is Ask, is Listen, CancelSpeech,
    is SetSeatHeat, is SetWindowLock, is SetReadingLight, is LowerHvac, is AddWaypoint -> To.EXEC
    is SendToPhone, is Qa -> To.LINK
    is ShowNotice -> To.UI
    is SaveSession, is SaveEpisode, is SavePlay, is LogAlarm, is CaptureContext, is SaveSteerWindow, is CloseSession,
    is SaveSnapshot, is SaveFeedingStart, DeleteRecords, DeleteRelayRecords -> To.STORE   // DeleteRelayRecords는 store가 기록 지우기와 한 트랜잭션(C90)
}

/** 호스트가 할 일을 넘기는 함수들. store는 호스트가 직접 부른다. */
class Targets(val exec: (Command) -> Unit, val link: (Command) -> Unit, val notice: (Notice) -> Unit)

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
    is MethodsChanged -> copy(at = t); is LinkChanged -> copy(at = t)
}

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
    val link = Link(ctx, db, clock, host::post, latest)
    val scenario = Scenario(ctx, host, link)

    init {
        host.targets = Targets(exec::handle, link::handle) { Notices.show(ctx, it) }
        MicFgs.clock = clock
        MicFgs.emit = host::post
        // link가 곡 표를 고치면 store가 후보 ③을 다시 계산한다(명세서 5장, 계약 6.1 '③ 계산')
        db.invalidationTracker.createFlow("song", "song_selection", "child_profile")
            .onEach { host.methodsMaybeChanged() }.launchIn(CoroutineScope(SupervisorJob() + Dispatchers.IO))
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
