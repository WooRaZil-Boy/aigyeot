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

class Kit {
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    var now = T0
    val clock = { now }
    val exec = mutableListOf<Command>()
    val link = mutableListOf<Command>()
    val notices = mutableListOf<Notice>()
    var running: Set<PauseReason>? = null          // 가짜 마이크 서비스: null = 꺼짐
    var pending = emptySet<PauseReason>()
    val assumed = mutableListOf<Set<PauseReason>>()
    var autoStart = true                            // exec가 StartPlayback 안에서 바로 PlaybackStarted를 낸다(진짜 exec처럼)
    var onStart: ((StartPlayback) -> Unit)? = null
    var slept = 0L                                  // 가짜 '기기가 잠든 누적 시간'(시계 점프 감지)
    var onRunningPauses: (() -> Unit)? = null       // runningPauses를 읽는 순간 끼어든다(그 직후 서비스가 시작한 경우)
    var wakes = 0                                   // Link.wake 횟수(contracts-link 8.6 '바로 다시 확인')
    /** 진짜 link(중계는 가짜). 복원 뒤 대조(8.3)는 이 link의 restoreEvents를 같은 DB로 부른다. */
    val linkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val realLink = Link(ApplicationProvider.getApplicationContext(), db, clock, {}, { null }, FakeRelay(), FakeCipher, { null },
        { _, _ -> LoudnessResult(true) }, linkScope)
    lateinit var host: Host
    lateinit var store: Store

    fun profile(sibling: Boolean = false, interval: Int? = null) = db.storeDao().upsertProfile(ChildProfileEntity(
        "child", "콩이", "2026-03", "LEFT", interval, null, listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)),
        sibling, false, T0,
    ))

    fun phone(role: String = "owner", alerts: Boolean = false) = db.openHelper.writableDatabase.execSQL(
        "INSERT INTO phone_link (id, platform, role, linked_at, last_seen_at, handoff_alerts) VALUES ('d-$role', 'android', '$role', $T0, NULL, ${if (alerts) 1 else 0})"
    )

    /** 새 호스트(=새 프로세스). 복원을 끝낼 때까지 기다린다. */
    fun boot(p: Params = Params()): Host {
        if (::host.isInitialized) host.close()
        val s = Store(db, clock, scenarioId = { host.scenarioId })
        store = s
        host = Host(s, clock, p = p, mic = MicPort(
            { val r = running; onRunningPauses?.invoke(); r },
            { if (running != null) assumed += it else pending = pending + it },   // 진짜처럼: 돌고 있으면 감지기에, 아니면 pendingPauses에
        ), userId = { 10 }, slept = { slept })
        host.targets = Targets(
            exec = { c ->
                exec += c
                if (c is StartPlayback) { onStart?.invoke(c); if (autoStart) host.post(PlaybackStarted(0, c.playId, "bus100_audio_zone_1", false)) }
            },
            link = { link += it },
            notice = { notices += it },
            wake = { wakes++ },
            restore = realLink::restoreEvents,
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
