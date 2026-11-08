package kr.aigyeot.app.link

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kr.aigyeot.app.exec.LoudnessResult
import kr.aigyeot.app.exec.SongLoudness
import kr.aigyeot.app.store.SnapshotJson
import kr.aigyeot.app.store.retryingSuspend
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FeedingFrom
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.CareCleared
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.LinkChanged
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.Qa
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.FeedingStartedBody
import kr.aigyeot.shared.ProtocolJson
import java.io.File

/**
 * 차 쪽 중계 연결(contracts-link 8장)의 겉면. 감시 서비스가 start·stop하고, 호스트가 할 일(SendToPhone·Qa)을 handle로 넘긴다.
 * 일은 책임별 클래스가 한다: [Creds]·[Connection]·[Inbox]·[Outbox]·[SongWorker]·[Pairing]·[QaService].
 * 공유 상태는 [Creds]의 자격과 [LinkStatus] 흐름 둘뿐이고(받기 잠금 [rx]·코루틴 범위는 함께 쓰는 하나), 서로 부르는 것은 생성자 람다로 넘긴다.
 * 공개 모양(생성자·메서드 이름과 인자)은 0단계 그대로다(handoff README 4장). 더한 메서드: [wake], [restoreEvents].
 */
class Link internal constructor(
    private val context: Context,
    private val db: AppDatabase,
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    private val state: () -> EngineState?,         // 최신 상태 읽기(상황판 호출에 넣는 상태 JSON, contracts-link 8.8)
    private val relay: Relay,
    private val cipher: TokenCipher,
    private val probe: (File) -> SongProbe?,
    private val makeCopy: suspend (File, File) -> LoudnessResult,
    private val scope: CoroutineScope,
    private val qaWaitMs: Long = Params().qaReplyWaitMs,
    private val stateReady: suspend () -> Unit = {},   // 호스트 복원 뒤 첫 상태가 공개될 때까지 기다린다: 복원 전에 낸 사건이 Boot·복원 대조보다 앞서지 않게(요약 판정은 Room 행이 기준이라 상태와 무관하다, L69). 취소되면 함께 취소
    private val postInjected: (Event) -> Unit = post,   // 장면 phone 단계가 주입한 사건(7.3): override로 막지 않는 길. [post]는 실제로 받은 사건
) {
    constructor(
        context: Context, db: AppDatabase, clock: () -> Long, post: (Event) -> Unit, state: () -> EngineState?, postInjected: (Event) -> Unit = post, stateReady: suspend () -> Unit = {},
    ) : this(
        context, db, clock, post, state, OkRelay(RelayAddress.of(context)), KeystoreCipher(), ::probeSong,
        { i, o -> SongLoudness.makeCopy(i, o) }, CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "link 코루틴 오류", e) }),   // 잡히지 않은 예외로 앱이 죽지 않게
        stateReady = stateReady, postInjected = postInjected,
    )

    private val room = LinkRoom(db)
    private val dao = room.dao
    private val _status = MutableStateFlow(LinkStatus())
    val status: StateFlow<LinkStatus> = _status   // 차 화면 연결·폰 목록용

    private val rx = Mutex()                                    // 받기는 한 번에 하나, seq 순서(8.3)
    private val creds: Creds = Creds(
        context, room, relay, cipher, clock, _status, rx,
        onRegistered = { outbox.signal(); songs.signal() },
        dropSocket = { connection.dropSocket() },
        resetPairing = { pairing.onInvalidated() },
        after = { inbox.after(it) },
    )
    private val inbox: Inbox = Inbox(
        room, relay, clock, post, creds, rx, _status,
        wakeOutbox = { outbox.signal() }, wakeSongs = { songs.signal() }, recheck = { connection.recheck() }, onNewOwner = { pairing.onNewOwner() },
    )
    private val connection: Connection = Connection(
        context, room, relay, clock, _status, rx, creds, inbox, scope, stateReady,
        pairingOpen = { pairing.isOpen }, wakeOutbox = { outbox.signal() }, wakeSongs = { songs.signal() },
    )
    private val outbox: Outbox = Outbox(db, room, relay, clock, creds, rx, scope, after = { inbox.after(it) })
    private val songs: SongWorker = SongWorker(context, room, relay, clock, creds, rx, probe, makeCopy, scope, after = { inbox.after(it) })
    private val pairing: Pairing = Pairing(room, relay, clock, _status, rx, creds, inbox, scope, kick = { connection.kick() }, recheck = { connection.recheck() })
    private val qaService = QaService(dao, relay, clock, post, state, creds, scope, qaWaitMs)

    private val cmds = Channel<Command>(Channel.UNLIMITED)      // 할 일은 받은 순서대로 한 일꾼이(feeding_state 순서)
    @Volatile private var jobs: List<Job> = emptyList()

    init {
        scope.launch { for (c in cmds) try { exec(c) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "할 일 실패 ${c::class.simpleName}", e) } }
    }

    // ---------------------------------------------------------------- 시작·멈춤

    fun start() {                                  // 차 등록·WebSocket·보낼 함(감시 서비스 시작 때)
        if (jobs.isNotEmpty()) return
        jobs = listOf(connection.startLoop(), outbox.startConsumer(), songs.startConsumer(), connection.startRecheck())
        outbox.watch()   // store가 넣는 report_summary·records_clear도 깨운다(같은 outbox 표)
        connection.watchNetwork()
        outbox.signal()
        songs.signal()
        if (pairing.isOpen) pairing.open()   // 연결 화면이 열린 채 다시 시작: stop이 멈춘 코드 갱신도 다시(8.7 ②)
    }

    fun stop() {                                   // 연결 없음(8.6 멈춤 조건). 보낼 함은 남긴다
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        pairing.stop(); outbox.cancelWake(); songs.cancelWake()
        connection.dropSocket()
        outbox.unwatch()
        connection.stopWatching()
    }

    /** 바로 다시 확인하는 계기(8.6, C67): 화면 켜짐·시계 점프·Resume 감지 때 호스트가 부른다. 네트워크 복구는 link가 직접 본다. */
    fun wake() = connection.wake()

    // ---------------------------------------------------------------- 할 일

    fun handle(cmd: Command) {                     // SendToPhone, Qa(상황판 호출). DeleteRelayRecords는 store가 보낼 함에 넣는다
        cmds.trySend(cmd)
    }

    internal fun enqueueNow(cmds: List<SendToPhone>, also: () -> Unit = {}): Boolean = outbox.enqueueNow(cmds, also)   // 보낼 함 바로 넣기(C160)

    internal suspend fun exec(c: Command) {
        when (c) {
            is SendToPhone -> retryingSuspend("보낼 함 넣기 ${c.type}") { enqueueNow(listOf(c)) }   // C302 W: 다 실패하면 할 일 소비가 로그를 남긴다
            is Qa -> scope.launch { qa(c) }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 시험이 부르는 위임(받기·자격·보내기·노래·상황판)

    internal enum class Sync { DONE, FETCH_FAILED, STORE_FAILED, LOST }

    /** 받기 결과: ACK(커밋했거나 무시할 메시지), FAIL(저장 실패). */
    internal enum class Rx { ACK, FAIL }

    internal suspend fun syncInbox(c: Cred? = creds.cred): Sync = inbox.syncInbox(c)

    internal suspend fun receive(env: Envelope, c: Cred? = null): Rx = inbox.receive(env, c)

    internal suspend fun reconcileDevices(c: Cred): Boolean = inbox.reconcileDevices(c)

    internal suspend fun credential(): Cred? = creds.credential()

    internal suspend fun invalidate(c: Cred? = null) = creds.invalidate(c)

    /** 시험용(L86): 맨 앞을 고른 직후, 보낸 id를 적기 전에 부른다. 운영에서는 null. */
    internal var afterFrontSelect: (() -> Unit)?
        get() = outbox.afterFrontSelect
        set(v) { outbox.afterFrontSelect = v }

    internal suspend fun pumpOnce() = outbox.pumpOnce()

    internal suspend fun songsOnce() = songs.songsOnce()

    internal suspend fun qa(cmd: Qa) = qaService.qa(cmd)

    /** 정차 화면에서 고른 곡(G20, 8.5): 연결이 없어도 보내고, 돌아온 seq로 song_selection에 적용한다. */
    fun selectSong(songId: String) = outbox.selectSong(songId)

    /** 차 화면 '지금 먹이기 시작했어요'(V3, C322): Room에 먼저 쓰고 feeding_state를 넣은 뒤 FeedingStarted(CAR_SCREEN)를 실제 사건 길로 낸다. */
    fun feedingFromCar(at: Long) = scope.launch { inbox.feedingFromCar(at) }

    // ---------------------------------------------------------------- 연결 화면(8.7)

    fun openPairing() = pairing.open()             // 연결 화면 열림(contracts-link 8.7 ①~③)

    fun closePairing() = pairing.close()           // 8.7 ④

    fun disconnect(deviceId: String) = pairing.disconnect(deviceId)   // 8.7 ⑤

    fun refreshPhones() = pairing.refreshPhones()  // 8.7 ⑥

    // ---------------------------------------------------------------- 복원·장면

    /**
     * 복원 뒤 대조(8.3)의 수유·보호자 몫. 호스트가 스냅숏 복원 → Boot·Resume 뒤 첫 판단 전에 부르고, 돌려준 사건을 넣는다.
     * Room 값이 스냅숏과 다를 때만 만든다. 후보 ③(MethodsChanged)은 store 몫이다.
     */
    suspend fun restoreEvents(snapshot: EngineState, at: Long): List<Event> = withContext(Dispatchers.IO) {
        // 프로세스 시작·장면 끝·Resume이 실제 상태를 Room에 맞춘다(C126 ④). 장면 중에는 호스트가 대조하지 않는다(C322 V2)
        val care = mutableListOf<Event>()
        room.tx {
            var row = dao.careApplied()
            var rowCare = row?.let { r -> runCatching { SnapshotJson.decodeFromString(CareView.serializer(), r.summaryJson) }.getOrNull() }
            if (row != null && rowCare == null) { dao.clearCareApplied(); row = null }   // 깨진 행
            // Room 행이 정답이다(C169). 판정 기준이 행이라, 연결 해제로 행이 지워진 뒤 더 이른 요약이 행이 된 경우도 스냅숏이 아닌 행을 따른다
            val use = row != null && rowCare != null && !(snapshot.care == rowCare && snapshot.careSeq == row.seq && snapshot.careRoom == row.carId)
            if (use) {   // 처리 완료 뒤 스냅숏 전에 죽어도 요약이 빠지지 않는다. 판단 코어는 care == null일 때만 받으므로 스냅숏 값이 있으면 먼저 비운다
                if (snapshot.care != null) care.add(CareCleared(at))
                care.add(CareSummary(at, rowCare!!, row!!.seq, row.carId))
            } else if (row == null && snapshot.careSeq != null) care.add(CareCleared(at))   // 중계 seq가 있는 요약은 행이 있던 것이다(장면 주입은 seq 없음). 행을 지운 뒤 스냅숏 전에 죽었다(C158)
        }
        val fed = dao.profile()?.lastFeedingStart
        val guardians = dao.otherGuardians()
        buildList {
            addAll(care)
            if (fed != null && fed != snapshot.feedingStart) add(FeedingStarted(at, fed, FeedingFrom.PHONE, null))
            if (guardians != snapshot.otherGuardians) add(LinkChanged(at, guardians))
        }
    }

    /**
     * 장면 'phone' 단계(계약 7.3): 받는 type은 feeding_started뿐이다. Room·보낼 함은 쓰지 않고 FeedingStarted(PHONE) 사건 하나만 낸다.
     * 장면 봉투는 seq가 비어 오고 장면은 실제 상태를 바꾸지 않아야 하므로 8.3 받기 길(seq·processed_message)을 타지 않는다.
     */
    suspend fun receiveForScenario(envelopeJson: String) {
        val env = runCatching { ProtocolJson.decodeFromString(Envelope.serializer(), envelopeJson) }.getOrNull()
            ?: return Unit.also { Log.w(TAG, "장면 봉투를 읽지 못함") }
        val at: Long? = if (env.type != "feeding_started") null else runCatching { instantOf(ProtocolJson.decodeFromJsonElement(FeedingStartedBody.serializer(), env.body).at) }.getOrNull()
        if (at == null) {
            Log.w(TAG, "장면 phone 봉투를 쓰지 않음 type=${env.type}")
            return
        }
        postInjected(FeedingStarted(clock(), at, FeedingFrom.PHONE, env.id))
    }

    private companion object {
        const val TAG = "Link"
    }
}

/** 8.6 연결 상태 네 개. 정차 화면 표시: 붙는 중·연결됨·다시 붙는 중. */
enum class LinkConn { NONE, CONNECTING, CONNECTED, BACKOFF }

data class LinkStatus(
    val registered: Boolean = false, val online: Boolean = false,
    val pairCode: String? = null, val pairUri: String? = null, val pairExpiresAt: Long? = null,
    val phones: List<LinkedPhone> = emptyList(), val error: String? = null,
    val conn: LinkConn = LinkConn.NONE,             // 8.6 정차 화면 표시
    val justPaired: String? = null,                 // 실시간 paired로 새로 붙은 기기 id. 바뀌면 화면이 "연결됐어요"(8.4)
)

data class LinkedPhone(val deviceId: String, val role: String, val lastSeenAt: Long?)

/** 받은 노래 파일의 길이·형식(8.4 검사). */
data class SongProbe(val durationS: Double, val aac: Boolean)

/** AAC(.m4a, `audio/mp4a-latm`) 소리 트랙의 길이. 읽지 못하면 null. */
internal fun probeSong(f: File): SongProbe? = runCatching {
    val ex = MediaExtractor()
    try {
        ex.setDataSource(f.path)
        (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?.let { SongProbe(it.getLong(MediaFormat.KEY_DURATION) / 1e6, it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm") }
    } finally {
        ex.release()
    }
}.getOrNull()
