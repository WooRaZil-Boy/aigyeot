package kr.aigyeot.app.host

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.aigyeot.app.exec.LoudnessResult
import kr.aigyeot.app.link.FakeCipher
import kr.aigyeot.app.link.FakeRelay
import kr.aigyeot.app.link.Link
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.app.store.db.ChildProfileEntity
import kr.aigyeot.engine.*

// 호스트 시험 도구: 가짜 시계·가짜 모듈·Room in-memory. 프로세스가 죽는 것은 호스트를 버리고 같은 DB로 새 호스트를 만드는 것으로 흉내 낸다.

const val T0 = 1_800_000_000_000L

/** 지금 세션의 인계 장부(V6: 장부 하나 `handoffs`에서 세션 id로 고른다). */
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
    var running: Set<PauseReason>? = null          // 가짜 마이크 서비스: null = 꺼짐
    var pending = emptySet<PauseReason>()
    val assumed = mutableListOf<Set<PauseReason>>()
    var cancelIfSpeakingHook: ((String) -> Pair<Boolean, Long>?)? = null   // C208: 실행기에 '아직 말하는 중이면 끊기'를 묻는 순간 가로채기(없으면 끊고 null). 돌려주는 값: null = 끊음, 아니면 이미 끝난 발화의 (spoken, 완료 시각)
    var execHook: ((Command) -> Unit)? = null     // C188: 실행기에 할 일이 들어가는 순간 가로채기
    var autoStart = true                            // exec가 StartPlayback 안에서 바로 PlaybackStarted를 낸다(진짜 exec처럼)
    var onStart: ((StartPlayback) -> Unit)? = null
    var slept = 0L                                  // 가짜 '기기가 잠든 누적 시간'(시계 점프 감지)
    var onRunningPauses: (() -> Unit)? = null       // runningPauses를 읽는 순간 끼어든다(그 직후 서비스가 시작한 경우)
    var linkNowHook: ((List<SendToPhone>, () -> Unit) -> Unit)? = null   // C160: 인계 알림 넣기와 store 쓰기의 묶음 가로채기(없으면 쓰기 뒤 link 목록에). 결정 ③: also(store 쓰기)가 먼저
    var onSlept: (() -> Unit)? = null              // 호스트 스레드가 사건을 처리하기 시작할 때(checkJump) 끼어든다. 큐 순서 시험에서 호스트를 잠시 붙잡는다
    @Volatile var restoreFailures = 0               // C294: 앞으로 이 횟수만큼 targets.restore가 던진다
    var wakes = 0                                  // Link.wake 횟수(contracts-link 8.6 '바로 다시 확인')
    /** 진짜 link(중계는 가짜). 복원 뒤 대조(8.3)는 이 link의 restoreEvents를 같은 DB로 부른다. */
    val linkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val realLink = Link(ApplicationProvider.getApplicationContext(), db, clock, {}, { null }, FakeRelay(), FakeCipher, { null },
        { _, _ -> LoudnessResult(true) }, linkScope)
    lateinit var host: Host
    lateinit var store: Store

    fun profile(sibling: Boolean = false, interval: Int? = null) = db.storeDao().upsertProfile(ChildProfileEntity(
        "child", "콩이", "2026-03", "LEFT", interval, null, listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)),
        sibling, false, T0, kr.aigyeot.app.store.db.AI_NOTICE_VERSION,
    ))

    fun phone(role: String = "owner", alerts: Boolean = false) = db.openHelper.writableDatabase.execSQL(
        "INSERT INTO phone_link (id, platform, role, linked_at, last_seen_at, handoff_alerts) VALUES ('d-$role', 'android', '$role', $T0, NULL, ${if (alerts) 1 else 0})"
    )

    /** 새 호스트(=새 프로세스). 복원을 끝낼 때까지 기다린다. */
    fun boot(p: Params = Params(), engine: Engine = Engine(p)): Host {
        if (::host.isInitialized) host.close()
        val s = Store(db, clock, scenarioId = { host.scenarioId })
        store = s
        host = Host(s, clock, engine = engine, p = p, mic = MicPort(
            { val r = running; onRunningPauses?.invoke(); r },
            { if (running != null) assumed += it else pending = pending + it },   // 진짜처럼: 돌고 있으면 감지기에, 아니면 pendingPauses에
        ), userId = { 10 }, slept = { onSlept?.invoke(); slept })
        host.targets = Targets(
            exec = { c ->
                exec += c
                execHook?.invoke(c)
                if (c is StartPlayback) { onStart?.invoke(c); if (autoStart) host.post(PlaybackStarted(0, c.playId, "bus100_audio_zone_1", false)) }
            },
            cancelIfSpeaking = { u -> cancelIfSpeakingHook?.invoke(u) ?: run { exec += CancelSpeech; null } },
            link = { link += it },
            notice = { notices += it },
            hideNotice = { hiddenNotices += it },
            wake = { wakes++ },
            restore = { s, at -> if (restoreFailures > 0) { restoreFailures--; error("복원 읽기 실패(시험)") }; realLink.restoreEvents(s, at) },
            linkNow = { cmds, also -> linkNowHook?.invoke(cmds, also) ?: run { also(); link += cmds } },
        )
        host.start()
        host.awaitIdle()
        return host
    }

    fun ev(e: Event, at: Long = now): Kit { now = at; host.post(e); host.awaitIdle(); return this }
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
