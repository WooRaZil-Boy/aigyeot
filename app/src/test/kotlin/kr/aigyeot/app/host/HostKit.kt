package kr.aigyeot.app.host

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kr.aigyeot.app.exec.LoudnessResult
import kr.aigyeot.app.link.FakeCipher
import kr.aigyeot.app.link.FakeRelay
import kr.aigyeot.app.link.Link
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.db.AI_NOTICE_VERSION
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.app.store.db.ChildProfileEntity
import kr.aigyeot.engine.CancelSpeech
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.Engine
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.HandoffCause
import kr.aigyeot.engine.HandoffKind
import kr.aigyeot.engine.HandoffLedger
import kr.aigyeot.engine.HandoffRecord
import kr.aigyeot.engine.Line
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.MicPath
import kr.aigyeot.engine.MicService
import kr.aigyeot.engine.Notice
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.PauseReason
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.PlaybackStarted
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.RearDoor
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Side
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.Stage
import kr.aigyeot.engine.StartPlayback
import kr.aigyeot.engine.Step
import kr.aigyeot.engine.Tick
import org.junit.After

// 호스트 시험 도구: 가짜 시계·가짜 모듈·Room in-memory. 프로세스가 죽는 것은 호스트를 버리고 같은 DB로 새 호스트를 만드는 것으로 흉내 낸다.

const val T0 = 1_800_000_000_000L

/** 지금 세션의 인계 장부(장부 하나 `handoffs`에서 세션 id로 고른다). */
fun EngineState.openLedger(): HandoffLedger? = session?.let { x -> handoffs.firstOrNull { it.sessionId == x.id } }

class Kit {
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    var now = T0
    val clock = { now }
    val exec = mutableListOf<Command>()
    val link = mutableListOf<Command>()
    val notices = mutableListOf<Notice>()
    val hiddenNotices = mutableListOf<Notice>()
    var running: Set<PauseReason>? = null // 가짜 마이크 서비스: null = 꺼짐
    var pending = emptySet<PauseReason>()
    val assumed = mutableListOf<Set<PauseReason>>()

    // 실행기에 '아직 말하는 중이면 끊기'를 묻는 순간 가로채기(없으면 끊고 null). 돌려주는 값: null = 끊음, 아니면 이미 끝난 발화의 (spoken, 완료 시각)
    var cancelIfSpeakingHook: ((String) -> Pair<Boolean, Long>?)? = null
    var execHook: ((Command) -> Unit)? = null // 실행기에 할 일이 들어가는 순간 가로채기
    var autoStart = true // exec가 StartPlayback 안에서 바로 PlaybackStarted를 낸다(진짜 exec처럼)
    var onStart: ((StartPlayback) -> Unit)? = null
    var slept = 0L // 가짜 '기기가 잠든 누적 시간'(시계 점프 감지)
    var onRunningPauses: (() -> Unit)? = null // runningPauses를 읽는 순간 끼어든다(그 직후 서비스가 시작한 경우)
    var linkNowHook: ((List<SendToPhone>, () -> Unit) -> Unit)? = null // 인계 알림 넣기와 store 쓰기의 묶음 가로채기(없으면 쓰기 뒤 link 목록에). also(store 쓰기)가 먼저

    // 호스트 스레드가 사건을 처리하기 시작할 때(checkJump) 끼어든다. 큐 순서 시험에서 호스트를 잠시 붙잡는다
    var onSlept: (() -> Unit)? = null

    @Volatile var restoreFailures = 0 // 앞으로 이 횟수만큼 targets.restore가 던진다
    var wakes = 0 // Link.wake 횟수('바로 다시 확인')

    /** 진짜 link(중계는 가짜). 복원 뒤 대조는 이 link의 restoreEvents를 같은 DB로 부른다. */
    val linkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val realLink = Link(
        ApplicationProvider.getApplicationContext(), db, clock, {}, { null }, FakeRelay(), FakeCipher, { null },
        { _, _ -> LoudnessResult(true) }, linkScope
    )
    lateinit var host: Host
    lateinit var store: Store

    fun profile(sibling: Boolean = false, interval: Int? = null) = db.storeDao().upsertProfile(
        ChildProfileEntity(
            "child", "콩이", "2026-03", "LEFT", interval, null, listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)),
            sibling, false, T0, AI_NOTICE_VERSION
        )
    )

    fun phone(role: String = "owner", alerts: Boolean = false) = db.openHelper.writableDatabase.execSQL(
        "INSERT INTO phone_link (id, platform, role, linked_at, last_seen_at, handoff_alerts) VALUES ('d-$role', 'android', '$role', $T0, NULL, ${if (alerts) 1 else 0})"
    )

    /** 새 호스트(=새 프로세스). 복원을 끝낼 때까지 기다린다. */
    fun boot(p: Params = Params(), engine: Engine = Engine(p)): Host {
        if (::host.isInitialized) host.close()
        val s = Store(db, clock, scenarioId = { host.scenarioId })
        store = s
        host = Host(
            s,
            clock,
            engine = engine,
            p = p,
            mic = MicPort(
                {
                    val r = running
                    onRunningPauses?.invoke()
                    r
                },
                { if (running != null) assumed += it else pending = pending + it } // 진짜처럼: 돌고 있으면 감지기에, 아니면 pendingPauses에
            ),
            userId = { 10 },
            slept = {
                onSlept?.invoke()
                slept
            }
        )
        host.targets = Targets(
            exec = { c ->
                exec += c
                execHook?.invoke(c)
                if (c is StartPlayback) {
                    onStart?.invoke(c)
                    if (autoStart) host.post(PlaybackStarted(0, c.playId, "bus100_audio_zone_1", false))
                }
            },
            cancelIfSpeaking = { u ->
                cancelIfSpeakingHook?.invoke(u) ?: run {
                    exec += CancelSpeech
                    null
                }
            },
            link = { link += it },
            notice = { notices += it },
            hideNotice = { hiddenNotices += it },
            wake = { wakes++ },
            restore = { s, at ->
                if (restoreFailures > 0) {
                    restoreFailures--
                    error("복원 읽기 실패(시험)")
                }
                realLink.restoreEvents(s, at)
            },
            linkNow = { cmds, also ->
                linkNowHook?.invoke(cmds, also) ?: run {
                    also()
                    link += cmds
                }
            }
        )
        host.start()
        host.awaitIdle()
        return host
    }

    fun ev(e: Event, at: Long = now): Kit {
        now = at
        host.post(e)
        host.awaitIdle()
        return this
    }
    fun state() = host.state()
    fun ses() = state().session!!

    /** 마이크 켜짐 + 뒷문 열림·닫힘 + D = 세션 LISTENING. */
    fun open(): Kit {
        ev(MicService(0, true, MicPath.HOME_BUTTON))
        running = emptySet()
        ev(RearDoor(0, Side.LEFT, true)).ev(RearDoor(0, Side.LEFT, false)).ev(GearChanged(0, Gear.D))
        check(state().session?.phase == Phase.LISTENING)
        return this
    }

    /** 경보 → ATTEMPT(1) 재생 중. */
    fun alarm(dt: Long = 1_000): Kit = ev(CrySegment(0, now + dt - 3_000, now + dt, 0.9, true), now + dt)

    /** 재생 끝(endAt의 Tick) → GAP. */
    fun toGap(): Kit = ev(Tick(0), ses().play!!.endAt)

    fun snapshotJson() = db.storeDao().snapshot()?.state
    fun savedAt() = db.storeDao().snapshot()?.savedAt
}

/** 호스트 시험의 공통 틀: 도구 모음 [k]와 정리, 여러 시험이 쓰는 질의. */
abstract class HostTestBase {
    protected val k = Kit()
    protected val dao get() = k.db.storeDao()

    @After fun close() {
        k.host.close()
        k.linkScope.cancel()
        k.db.close()
    }

    /** stepLog를 지금 이 스레드에서 구독한다(UNDISPATCHED: 돌아올 때 이미 붙어 있다). */
    protected fun collect(on: CoroutineDispatcher = Dispatchers.Unconfined, f: (Step) -> Unit) =
        CoroutineScope(on).also { it.launch(start = CoroutineStart.UNDISPATCHED) { k.host.stepLog.collect(f) } }

    protected fun sql(q: String) = k.db.openHelper.writableDatabase.execSQL(q)

    /** 순서(store 쓰기 → 보낼 함 넣기)가 모두 끝난 뒤 커밋 전에 실패한다: 바깥 트랜잭션이 둘 다 되돌린다. */
    protected fun failTied() {
        k.linkNowHook = { c, also ->
            k.db.runInTransaction(
                Runnable {
                    k.realLink.enqueueNow(c, also)
                    error("커밋 전 실패")
                }
            )
        }
    }

    /** 호스트 스레드가 끝나며 던지는 것(운영에서는 기본 핸들러가 프로세스를 끝낸다). */
    protected fun <T> catchingHostDeath(block: () -> T): Throwable? {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        var died: Throwable? = null
        val gate = CountDownLatch(1)
        Thread.setDefaultUncaughtExceptionHandler { _, x ->
            died = x
            gate.countDown()
        } // 운영에서는 이 핸들러가 프로세스를 끝낸다
        try {
            block()
            gate.await(3, TimeUnit.SECONDS)
            k.host.awaitIdle()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old)
        }
        return died
    }

    /** 보호자(알림 켬)가 있고 S08(NO_REST) 인계 줄을 말하는 중(u)까지 간 상태. 다음 SpeechDone(true)이 알림과 SettleHandoff를 한 Step에 낸다. */
    protected fun speakingWithHandoff(handoffPhase: Boolean = false, extra: List<Line> = emptyList()): Pair<String, String> {
        k.phone("guardian", alerts = true)
        val (id, _) = snapshotWithHandoffLine(extra = extra, mk = { Line(Sid.S08, emptyMap(), null, queuedAt = it) })
        val s = k.store.loadSnapshot()!!
        val x = s.session!!
        k.store.saveSnapshot(
            s.copy(
                handoffs = s.handoffs.map { it.copy(kind = HandoffKind.NO_REST, alert = true) },
                car = if (handoffPhase) s.car.copy(pauses = emptySet()) else s.car,
                session = if (handoffPhase) x.copy(stage = Stage.Handoff(x.attemptNo)) else x
            )
        )
        k.running = if (handoffPhase) emptySet() else null
        k.exec.clear()
        k.link.clear() // handoffPhase: 마이크가 살아 있어 quietMs가 센다
        k.boot()
        k.ev(Tick(0), k.now + 10_000)
        val u = k.exec.filterIsInstance<Speak>().single { it.sid == Sid.S08 }.utteranceId
        return id to u
    }

    protected fun outboxTypes() = k.db.openHelper.readableDatabase.query("SELECT type FROM outbox WHERE type = 'handoff_alert'").use { it.count }

    /** 에피소드 handoff가 TWO_ATTEMPTS/WAYPOINT_OFFER, S07이 큐에 묶인 스냅숏을 저장하고 죽은 상태를 만든다. */
    protected fun snapshotWithHandoffLine(
        speaking: String? = null,
        extra: List<Line> = emptyList(),
        mk: (Long) -> Line = { Line(Sid.S07, mapOf("restName" to "n 휴게소"), "n", queuedAt = it) }
    ): Pair<String, Line> {
        k.profile()
        k.boot()
        k.open()
        k.alarm()
        k.toGap()
        val s = k.state()
        val ep = s.session!!.episode!!
        val line = mk(k.now).let { if (it.sessionId == null) it.copy(sessionId = s.session!!.id, handoff = true) else it } // 장부가 묶인 줄(표시). 다른 세션 줄은 묶이지 않는다
        k.store.saveSnapshot(
            s.copy(
                speech = s.speech.copy(speaking = speaking, queue = listOf(line) + extra),
                session = s.session!!.copy(
                    episode = ep.copy(handoff = HandoffRecord(HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER))
                ),
                handoffs = listOf(HandoffLedger(s.session!!.id, ep.id, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, alert = false))
            )
        )
        sql("UPDATE episode SET handoff_cause = 'TWO_ATTEMPTS', handoff_kind = 'WAYPOINT_OFFER' WHERE id = '${ep.id}'")
        return ep.id to line
    }

    protected fun toldDriverRow(id: String): Int? = k.db.openHelper.readableDatabase.query("SELECT handoff_told_driver FROM episode WHERE id = '$id'")
        .use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getInt(0)
        }

    protected fun restNameRow(id: String): String? = k.db.openHelper.readableDatabase.query("SELECT handoff_rest_name FROM episode WHERE id = '$id'")
        .use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getString(0)
        }

    protected fun closedSessionWithHandoff(alert: Boolean = true, toldInRoom: Int? = null): Pair<String, String> {
        k.phone("guardian", alerts = true)
        val (id, _) = snapshotWithHandoffLine()
        val s = k.store.loadSnapshot()!!
        val sid = s.session!!.id
        k.store.saveSnapshot(s.copy(handoffs = s.handoffs.map { it.copy(alert = alert, utteranceId = "u1") }, speech = s.speech.copy(speaking = "u1")))
        sql("UPDATE session SET ended_at = ${k.now}, report_pending = 1 WHERE id = '$sid'")
        toldInRoom?.let { sql("UPDATE episode SET handoff_told_driver = $it WHERE id = '$id'") }
        k.running = null
        k.exec.clear()
        k.link.clear()
        return id to sid
    }

    protected fun reportBodies() = k.db.openHelper.readableDatabase.query("SELECT body_json FROM outbox WHERE type = 'report_summary'")
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    protected fun setup(override: Set<String> = emptySet(), sibling: Boolean = false) = ScenarioSetup(
        "A",
        7,
        Profile("2026-03", CarSeat.LEFT, null, sibling),
        listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)),
        emptyList(),
        null,
        0,
        override
    )
}
