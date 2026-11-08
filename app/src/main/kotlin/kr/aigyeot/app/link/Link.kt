package kr.aigyeot.app.link

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kr.aigyeot.app.common.CarPaths
import kr.aigyeot.app.exec.LoudnessResult
import kr.aigyeot.app.exec.SongLoudness
import kr.aigyeot.app.link.db.RelayCredentialEntity
import kr.aigyeot.app.link.db.SongDownloadEntity
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.EpisodeTrigger
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FeedingFrom
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.DeleteRelayRecords
import kr.aigyeot.engine.careReplaces
import kr.aigyeot.app.link.db.PhoneLinkMarkEntity
import kr.aigyeot.engine.LinkChanged
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.Qa
import kr.aigyeot.engine.QaReply
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.FeedingStartedBody
import kr.aigyeot.shared.LlmRequest
import kr.aigyeot.app.store.db.needsSetup
import kr.aigyeot.shared.LlmResponse
import kr.aigyeot.shared.LlmState
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.SendMessageResponse
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 차 쪽 중계 연결(contracts-link 8장). 감시 서비스가 start·stop하고, 호스트가 할 일(SendToPhone·Qa)을 handle로 넘긴다.
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
    private val stateReady: suspend () -> Unit = {},   // 호스트 복원 뒤 첫 상태가 공개될 때까지 기다린다(C132). 취소되면 함께 취소
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

    internal class Cred(val carId: String, val deviceId: String, val token: String)

    @Volatile private var cred: Cred? = null
    private val credLock = Mutex()
    private val rx = Mutex()                                    // 받기는 한 번에 하나, seq 순서(8.3)
    private val kick = Channel<Unit>(Channel.CONFLATED)         // 연결 반복을 바로 깨운다(8.6 '바로 다시 확인하는 계기')
    private val pingNow = Channel<Unit>(Channel.CONFLATED)      // 연결 중 바로 다시 확인: 곧바로 ping(8.6)
    private val pumpSignal = Channel<Unit>(Channel.CONFLATED)
    private val songSignal = Channel<Unit>(Channel.CONFLATED)
    private val cmds = Channel<Command>(Channel.UNLIMITED)      // 할 일은 받은 순서대로 한 일꾼이(feeding_state 순서)
    @Volatile private var pairingOpen = false
    private val pairKick = Channel<Unit>(Channel.CONFLATED)     // 무효 뒤·만료 대기 중 연결 코드 반복을 깨운다(8.7 ②)
    private val pairLock = Mutex()                              // 연결 코드 받기·지우기를 차례로: DELETE는 차의 코드를 모두 지워 늦으면 새 코드까지 지운다
    @Volatile private var nextRegisterAt = 0L                   // 차 등록 간격(8.6 '차 토큰 무효'): 이 시각 전에는 등록하지 않는다
    @Volatile private var registerTries = 0
    @Volatile private var live: Pair<RelaySocket, Channel<Frame>>? = null
    private class Frame(val text: String, val gen: Int, val epoch: Int)   // gen: 프레임을 받은 시점의 careGen(C130)
    @Volatile private var jobs: List<Job> = emptyList()
    @Volatile private var pairJob: Job? = null
    @Volatile private var pumpWake: Job? = null
    @Volatile private var songWake: Job? = null
    private var outboxWatch: InvalidationTracker.Observer? = null
    private var netWatch: ConnectivityManager.NetworkCallback? = null

    init {
        scope.launch { for (c in cmds) try { exec(c) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "할 일 실패 ${c::class.simpleName}", e) } }
    }

    // ---------------------------------------------------------------- 시작·멈춤

    fun start() {                                  // 차 등록·WebSocket·보낼 함(감시 서비스 시작 때)
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch {
                while (true) {   // 연결 반복이 예외로 끝나도 60초 뒤 다시 돈다. 정상 반환(4002)이면 다시 돌지 않는다
                    try { room.expire(clock()); refreshStatus(); stateReady(); connectLoop(); return@launch }   // 호스트 복원 전에는 세대를 몰라 받기를 시작하지 않는다(C132)
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Log.e(TAG, "연결 반복 오류", e); delay(backoffMs(6)) }
                }
            },
            scope.launch { for (x in pumpSignal) try { pumpOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "보낼 함 오류", e) } },
            scope.launch { for (x in songSignal) try { songsOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "노래 받기 오류", e) } },
        )
        // store가 넣는 report_summary·records_clear도 깨운다(같은 outbox 표)
        outboxWatch = object : InvalidationTracker.Observer("outbox") {
            override fun onInvalidated(tables: Set<String>) { pumpSignal.trySend(Unit) }
        }.also { db.invalidationTracker.addObserver(it) }
        netWatch = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = wake()
        }.also { cb -> runCatching { context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) } }
        pumpSignal.trySend(Unit)
        songSignal.trySend(Unit)
        if (pairingOpen) openPairing()   // 연결 화면이 열린 채 다시 시작: stop이 멈춘 코드 갱신도 다시(8.7 ②)
    }

    fun stop() {                                   // 연결 없음(8.6 멈춤 조건). 보낼 함은 남긴다
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        pairJob?.cancel(); pumpWake?.cancel(); songWake?.cancel()
        dropSocket()
        outboxWatch?.let { db.invalidationTracker.removeObserver(it) }
        outboxWatch = null
        netWatch?.let { cb -> runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netWatch = null
        conn(LinkConn.NONE)
    }

    /** 바로 다시 확인하는 계기(8.6, C67): 화면 켜짐·시계 점프·Resume 감지 때 호스트가 부른다. 네트워크 복구는 link가 직접 본다. */
    fun wake() {
        kick.trySend(Unit)
        pingNow.trySend(Unit)
        pumpSignal.trySend(Unit)
        songSignal.trySend(Unit)
    }

    // ---------------------------------------------------------------- 할 일

    fun handle(cmd: Command) {                     // SendToPhone, Qa(상황판 호출). DeleteRelayRecords는 store가 보낼 함에 넣고, 여기서는 받은 seq만 남긴다(C126 ②)
        if (cmd is DeleteRelayRecords) return markCareClear()
        cmds.trySend(cmd)
    }

    @Volatile private var maxSeenSeq = 0L                 // 받아 온 묶음까지 포함해 본 가장 큰 중계 seq
    @Volatile private var realCare: CareView? = null                 // 실제로 받은 최신 요약(장면 값과 따로, C126 ④). restoreEvents가 복원 값으로 채운다
    @Volatile private var realCareSeq: Long? = null
    @Volatile private var realCareRoom: String? = null
    @Volatile private var realCareGen: Int? = null                    // 실제 요약을 채운 세대: 다른 세대의 기준은 기준 없음으로 본다(C135)
    @Volatile private var clearEpoch = 0                              // 지우기마다 올린다: 공개 세대가 바뀌기 전에 끝난 옛 수신이 실제 요약을 되살리지 못하게(C133)
    private val careLock = Any()                                          // 세대 확인·실제 요약 갱신과 지우기 표시를 한 덩어리로(C130)
    private fun curGen() = state()?.careGen ?: 0
    private fun seen(seq: Long) { if (seq > maxSeenSeq) maxSeenSeq = seq }

    /** '모든 기록 지우기'(C126 ②): 그때까지 받은 마지막 seq를 남겨, 지운 뒤 처리되는 그 이하 care_summary를 버린다. 프로세스가 죽어도 남는다. */
    @Volatile private var restored = false                           // restoreEvents가 실제 요약을 채운 뒤에는 실제 요약끼리만 견준다(없으면 null과, C128 ③)
    private fun markCareClear() { synchronized(careLock) { clearEpoch++; restored = true; realCare = null; realCareSeq = null; realCareRoom = null; realCareGen = null }; markCareSeq() }   // 판단 코어도 지웠다: 폰이 다음에 보내는 요약은 옛 asOf라도 받는다
    private fun markCareSeq() = room.tx {
        val old = dao.mark(CARE_CLEAR)?.lastSeq ?: 0
        dao.putMark(PhoneLinkMarkEntity(CARE_CLEAR, maxOf(old, maxSeenSeq), false, clock()))
    }

    internal suspend fun exec(c: Command) {
        when (c) {
            is SendToPhone -> {
                val now = clock()
                // feeding_state는 코어 body를 쓰지 않고 넣는 트랜잭션에서 Room 최신 값으로 만든다(3.3 '경합 방지', 차 계약 4.3)
                room.tx { room.enqueue(c.type, if (c.type == "feeding_state") room.feedingState() else withRfc3339Times(c.body), now, null) }
                pumpSignal.trySend(Unit)
            }
            is Qa -> scope.launch { qa(c) }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 연결 상태(8.6)

    private fun conn(c: LinkConn) = _status.update { it.copy(conn = c, online = c == LinkConn.CONNECTED) }

    private fun wantSocket() = pairingOpen || dao.phoneLinks().isNotEmpty()

    private val sockGen = AtomicInteger()   // 연결마다 올리는 소켓 세대: dropSocket 뒤 닫은 소켓의 늦은 프레임·ack은 무시한다(C139)

    private fun dropSocket() {
        sockGen.incrementAndGet()
        live?.let { (ws, frames) -> ws.cancel(); frames.close() }
        live = null
    }

    /** PhoneLink가 없고 연결 화면도 닫혔으면 소켓을 닫는다(8.6 연결 없음). */
    private fun recheck() {
        if (!wantSocket()) dropSocket()
        kick.trySend(Unit)
    }

    private suspend fun connectLoop() {
        var fails = 0
        var fallbackOk: Boolean? = null   // HTTPS 대체 확인: 실패(또는 처음) 뒤 성공할 때마다 기기 목록 대조(8.6, L42)
        var lastFallback: Long? = null
        while (true) {
            if (!wantSocket()) {
                fails = 0
                conn(LinkConn.NONE)
                kick.receive()
                continue
            }
            kick.tryReceive()   // 시도하는 동안 온 kick은 남겨 기다림을 바로 끝낸다
            conn(LinkConn.CONNECTING)
            val c = credential()
            val code = if (c == null) -1 else runSocket(c) { fails = 0; fallbackOk = null }
            when (code) {
                4001, 401 -> invalidate(c)   // 아래로 내려가 기다린다: 곧바로 재등록하면 연쇄한다(8.6)
                4002 -> { conn(LinkConn.NONE); return }   // 같은 기기의 새 연결. 서비스가 다시 시작될 때까지
            }
            fails++
            conn(LinkConn.BACKOFF)
            if (!wantSocket()) continue   // 끊긴 뒤에는 맨 위에서 NONE으로
            if (code != 4001 && code != 401 && c != null && fails >= 3 && lastFallback.let { it == null || clock() - it >= 60_000 }) {   // 연속 3번 실패 뒤 60초마다 HTTPS(2장)
                lastFallback = clock()
                val r = syncInbox(c)
                if (r == Sync.DONE && fallbackOk != true) reconcileDevices(c)
                pumpSignal.trySend(Unit)   // 8.6 '보낼 함 확인': 성공·실패와 상관없이
                fallbackOk = r == Sync.DONE
            }
            withTimeoutOrNull(if (code == 4008 || code == 429) 60_000L else backoffMs(fails - 1)) { kick.receive() }
        }
    }

    /** WebSocket 하나를 닫힐 때까지 돈다. 닫기 코드(업그레이드 실패는 HTTP 코드, 네트워크는 -1). */
    private suspend fun runSocket(c: Cred, onOpen: () -> Unit): Int = coroutineScope {
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val closeCode = AtomicInteger(-1)
        val lastPong = AtomicLong(mono())
        val myGen = sockGen.incrementAndGet()
        val ws = relay.socket(c.token, object : SocketEvents {
            override fun onOpen() { frames.trySend(Frame(OPENED, 0, 0)) }
            // pong은 프레임 루프가 첫 동기화에 묶여 있어도 바로 적는다(안 그러면 멀쩡한 소켓이 끊긴다)
            override fun onText(text: String) { if (text == "pong") lastPong.set(mono()) else if (sockGen.get() == myGen) frames.trySend(Frame(text, curGen(), clearEpoch)) }
            override fun onClosed(code: Int) { closeCode.compareAndSet(-1, code); frames.close() }
        })
        live = ws to frames
        var pinger: Job? = null
        try {
            for (frame in frames) when (val text = if (sockGen.get() == myGen) frame.text else break) {   // 닫은 소켓의 대기 프레임은 버린다: ack하지 않았으니 다시 연결 뒤 받은 함에서 다시 온다(C139)
                OPENED -> {
                    onOpen()
                    registerTries = 0
                    conn(LinkConn.CONNECTED)
                    pinger = launch {   // 25초마다 글자 ping, 10초 안에 pong이 없으면 끊고 다시 붙는다(2장)
                        withTimeoutOrNull(25_000) { pingNow.receive() }   // 깨우면(wake) 바로 ping
                        while (isActive) {
                            val sentAt = mono()
                            ws.send("ping")
                            delay(10_000)
                            if (lastPong.get() < sentAt) { ws.cancel(); frames.close(); break }
                            withTimeoutOrNull(15_000) { pingNow.receive() }
                        }
                    }
                    // 열린 소켓의 프레임은 채널에 쌓아 두고 받은 함부터 모두 처리한다(8.3 '붙을 때')
                    when (syncInbox(c)) {
                        Sync.LOST -> return@coroutineScope 401
                        Sync.STORE_FAILED -> break   // 저장 실패: 뒤 실시간 프레임을 처리하면 seq 순서가 깨진다. 닫고 다시 받는다(8.3)
                        else -> Unit
                    }
                    reconcileDevices(c)                     // 8.4 '연결됨이 되어 밀린 메시지를 모두 처리한 직후'
                    pumpSignal.trySend(Unit)
                    songSignal.trySend(Unit)
                }
                else -> {
                    val env = runCatching { parseEnvelope(ProtocolJson.parseToJsonElement(text)) }.getOrNull() ?: continue
                    if (!receive(env, c, frame.gen, frame.epoch)) break             // 저장 실패: ack 없이 닫는다. 다시 붙어 받은 함에서 다시 받는다(8.3 4단계)
                    if (env.id.isNotEmpty() && sockGen.get() == myGen) ws.send(ackFrame(listOf(env.id), clock()))
                }
            }
        } finally {
            pinger?.cancel()
            ws.cancel()
            if (live?.first === ws) live = null
        }
        closeCode.get()
    }

    // ---------------------------------------------------------------- 받기(8.3)

    internal enum class Sync { DONE, FETCH_FAILED, STORE_FAILED, LOST }

    /** 받은 함을 more = false까지 받아 seq 순서로 처리하고, 커밋한 것만 ack한다. 401이면 차 토큰 무효를 처리하고 LOST. */
    internal suspend fun syncInbox(c: Cred? = cred): Sync {
        c ?: return Sync.FETCH_FAILED
        var after = 0L
        while (true) {
            val gen = curGen(); val epoch = clearEpoch   // 요청을 시작할 때의 세대: 그사이 지우기가 있으면 이 응답의 요약은 버린다(C130)
            when (val r = relay.inbox(c.token, after)) {
                is Res.Ok -> {
                    val msgs = r.value.messages.sortedBy { it.seq ?: 0 }
                    // 방이 바뀌었으면 옛 방의 seq는 반영하지 않는다(C128 ①). 확인과 반영은 invalidate와 같은 잠금 안(C130)
                    msgs.lastOrNull()?.seq?.let { sq -> rx.withLock { if (cred === c) seen(sq) } }
                    val done = mutableListOf<String>()
                    var failed = false
                    for (env in msgs) if (receive(env, c, gen, epoch)) { if (env.id.isNotEmpty()) done += env.id } else { failed = true; break }
                    for (ids in done.chunked(100)) {
                        val a = relay.ack(c.token, ids)
                        if (a is Res.Err && a.status == 401) return Sync.LOST.also { invalidate(c) }
                    }
                    if (failed) return Sync.STORE_FAILED
                    if (msgs.isEmpty() || !r.value.more) return Sync.DONE.also { room.expire(clock()) }
                    after = msgs.last().seq ?: after
                }
                is Res.Err -> return if (r.status == 401) Sync.LOST.also { invalidate(c) } else Sync.FETCH_FAILED
                Res.Offline -> return Sync.FETCH_FAILED
            }
        }
    }

    /**
     * 봉투 하나(8.3 1~3단계와 5단계). 참이면 ack해도 된다(처리를 커밋했거나 무시할 메시지). 저장에 실패하면 거짓.
     * [c]는 이 봉투를 받은 자격. 그사이 차 토큰 무효로 방이 바뀌었으면 옛 방의 seq를 새 방에 적용하지 않고 거짓(멈추고 다시 받는다).
     */
    internal suspend fun receive(env: Envelope, c: Cred? = null, gen: Int = curGen(), epoch: Int = clearEpoch): Boolean = rx.withLock {
        if (c != null && cred !== c) return false
        if (env.v != 1 || env.type !in RECEIVED_TYPES || !isUlid(env.id)) {
            Log.w(TAG, "받지 않는 메시지 type=${env.type} v=${env.v} seq=${env.seq}")
            return true
        }
        env.seq?.let(::seen)
        val out = try {
            room.receive(env, clock())
        } catch (e: Exception) {
            Log.e(TAG, "받기 저장 실패 id=${env.id} type=${env.type}", e)
            return false
        }
        if (out != null) after(out, gen, (c ?: cred)?.carId ?: env.car, epoch)
        true
    }

    /** 커밋 뒤: 사건, 파일 정리, 일꾼 깨우기, 화면 상태(8.3 5단계). */
    private fun after(out: Applied, gen: Int = curGen(), carId: String? = null, epoch: Int = clearEpoch) {
        out.deleteFiles.forEach { File(it).delete() }
        out.events.forEach(post)
        out.care?.let { c -> synchronized(careLock) {   // L60: 실제 최신 요약보다 늦을 때만(C126). 장면 값과는 견주지 않는다
            if (curGen() != gen || clearEpoch != epoch) return@synchronized   // 받기 시작 뒤 지우기가 있었다: 사건도 실제 값도 건드리지 않고 ack(C130)
            val (old, oldSeq, oldRoom) = if (restored) (if (realCareGen == gen) Triple(realCare, realCareSeq, realCareRoom) else Triple(null, null, null)) else state().let { Triple(it?.care, it?.careSeq, it?.careRoom) }   // 복원 전이면 마지막 상태
            if (careReplaces(old, oldSeq, c, out.careSeq, oldRoom, carId)) {
                restored = true; realCare = c; realCareSeq = out.careSeq; realCareRoom = carId; realCareGen = gen
                post(CareSummary(clock(), c, out.careSeq, gen, carId))
            }
        } }
        if (out.outbox) pumpSignal.trySend(Unit)
        if (out.download) songSignal.trySend(Unit)
        if (out.phones) { refreshStatus(); recheck() }
        out.justPaired?.let { id -> _status.update { it.copy(justPaired = id, error = null) } }
        if (out.newOwner && pairingOpen) openPairing()   // G41: 연결 코드가 쓰였다. 화면이 열려 있으면 새 코드
    }

    private fun refreshStatus() {
        val phones = dao.phoneLinks().map { LinkedPhone(it.id, it.role, it.lastSeenAt) }
        val registered = cred != null || dao.credential() != null
        _status.update { it.copy(phones = phones, registered = registered) }
    }

    /** 기기 목록 대조(8.4 G37). 받기와 섞이지 않게 같은 잠금 안에서 한다. */
    internal suspend fun reconcileDevices(c: Cred) {
        when (val r = relay.devices(c.token)) {
            // after도 잠금 안: LinkChanged 순서. 그사이 방이 바뀌었으면 옛 방의 목록을 새 방에 적용하지 않는다
            is Res.Ok -> { val out = Applied(); rx.withLock { if (cred === c) { room.reconcile(r.value, clock(), out); after(out) } } }
            is Res.Err -> if (r.status == 401) invalidate(c)
            Res.Offline -> Unit
        }
    }

    // ---------------------------------------------------------------- 자격(8.2, 8.6)

    internal suspend fun credential(): Cred? {
        cred?.let { return it }
        val row = dao.credential()
        if (row != null) {
            runCatching { cipher.open(row.tokenEncrypted) }.getOrNull()?.let { return Cred(row.carId, row.deviceId, it).also { c -> cred = c } }
            Log.w(TAG, "차 토큰을 풀지 못함: 차 토큰 무효로 처리")   // 키가 없어졌다(다른 기기로 복원 등). 옛 방은 쓸 수 없다
            invalidate()
        }
        return credLock.withLock {
            cred ?: if (clock() < nextRegisterAt) null else when (val r = relay.registerCar().also { nextRegisterAt = clock() + backoffMs(registerTries++) }) {
                is Res.Ok -> {
                    val v = r.value
                    val sealed = runCatching { cipher.seal(v.carToken) }.getOrElse { Log.e(TAG, "차 토큰 감싸기 실패", it); return@withLock null }
                    room.tx { dao.clearCredential(); dao.insertCredential(RelayCredentialEntity(v.carId, v.deviceId, sealed)) }
                    Cred(v.carId, v.deviceId, v.carToken).also {
                        cred = it; _status.update { s -> s.copy(registered = true) }
                        pumpSignal.trySend(Unit); songSignal.trySend(Unit)   // 등록 전에 쌓인 보낼 함·받을 노래를 바로
                    }
                }
                is Res.Err -> null.also { Log.w(TAG, "차 등록 실패 status=${r.status} code=${r.code}") }
                Res.Offline -> null
            }
        }
    }

    /**
     * 차 토큰 무효(401, 닫기 4001): 자격·PhoneLink를 지우고 옛 방 순번을 버린다. 다음 계기에 새로 등록한다(8.6, G7).
     * [c]는 401을 받은 요청의 자격. 그사이 다시 등록했으면 옛 토큰의 늦은 401로 새 방을 지우지 않는다.
     */
    internal suspend fun invalidate(c: Cred? = null) {
        if (!credLock.withLock { (c == null || cred == null || cred === c).also { if (it) cred = null } }) return
        dropSocket()   // 옛 토큰 소켓
        if (pairingOpen) { _status.update { it.copy(pairCode = null, pairUri = null, pairExpiresAt = null) }; pairKick.trySend(Unit) }
        val out = Applied()
        rx.withLock {
            maxSeenSeq = 0   // 새 방은 seq를 다시 센다(C128 ①)
            room.resetForNewRoom(clock(), out).forEach { CarPaths.songDownload(context, it).delete(); CarPaths.songFile(context, it).delete() }
            after(out)
        }
        _status.update { it.copy(registered = false, error = RELINK) }
    }

    // ---------------------------------------------------------------- 보내기(8.5)

    /** 보낼 함을 넣은 순서대로 하나씩 보낸다. 맨 앞이 기다리는 중이면 뒤도 기다린다. */
    internal suspend fun pumpOnce() {
        while (true) {
            val now = clock()
            dao.expireOutbox(now - KEEP_MS)
            val item = dao.outboxFront() ?: return
            if (item.nextTryAt > now) return wakePumpAt(item.nextTryAt)
            val c = credential() ?: return wakePumpAt(now + 60_000)
            val r: Res<Any> = if (item.type == "records_clear") relay.deleteRecords(c.token) else {
                val body = runCatching { Json.parseToJsonElement(item.bodyJson).jsonObject }.getOrNull()
                if (body == null) { Log.w(TAG, "보낼 함 본문이 깨짐 type=${item.type} id=${item.id}"); dao.deleteOutbox(item.id); continue }
                relay.send(c.token, envelopeOf(item.id, item.type, body, item.createdAt, item.toDevice))
            }
            when (r) {
                is Res.Ok -> {
                    val out = Applied()
                    val sameRoom = rx.withLock {
                        if (cred !== c) return@withLock false   // 그사이 차 토큰 무효: 옛 방에 간 것이라 지우지 않고 새 방에 다시 보낸다(옛 seq도 쓰지 않는다)
                        room.tx {
                            dao.deleteOutbox(item.id)
                            if (item.type == "records_clear") { synchronized(careLock) { clearEpoch++ }; markCareSeq(); dropSocket(); kick.trySend(Unit) }   // 완료 전에 시작한 요청·프레임의 옛 요약은 epoch가 달라 버린다(C134 ①). 삭제가 끝난 시점까지 본 seq도 남긴다(C126 ②)
                            // 정차 화면 선택은 돌려받은 seq로 적용한다(G20)
                            (r.value as? SendMessageResponse)?.takeIf { item.type == "song_selected" }?.let { res ->
                                Json.parseToJsonElement(item.bodyJson).jsonObject["song_id"]?.jsonPrimitive?.content?.let { room.select(it, res.seq, now, out) }
                            }
                        }
                        after(out)
                        true
                    }
                    if (!sameRoom) continue
                }
                is Res.Err -> when {
                    r.status == 401 -> { dao.retryOutbox(item.id, now + 60_000); return invalidate(c) }   // 등록 성공 신호로 깨어나도 맨 앞이 기다린다(8.6)
                    r.status == 429 -> dao.retryOutbox(item.id, now + (r.retryAfterS?.times(1000)?.coerceAtLeast(1000) ?: backoffMs(item.attempts)))
                    r.status >= 500 -> dao.retryOutbox(item.id, now + backoffMs(item.attempts))
                    else -> {   // 400·403·409·413·422 등: 다시 보내도 같다
                        Log.w(TAG, "보낼 함 버림 type=${item.type} id=${item.id} status=${r.status} code=${r.code}")
                        dao.deleteOutbox(item.id)
                    }
                }
                Res.Offline -> dao.retryOutbox(item.id, now + backoffMs(item.attempts))
            }
        }
    }

    private fun wakePumpAt(at: Long) {
        pumpWake?.cancel()
        pumpWake = scope.launch { delay((at - clock()).coerceAtLeast(0)); pumpSignal.trySend(Unit) }
    }

    // ---------------------------------------------------------------- 노래 받기 작업(8.4)

    internal suspend fun songsOnce() {
        while (true) {
            val now = clock()
            val d = dao.nextDownload(now) ?: return dao.nextDownloadAt()?.let(::wakeSongsAt) ?: Unit
            if (!room.stillWanted(d)) { dao.deleteDownload(d.songId); continue }   // 그사이 지움이 왔다
            val c = credential() ?: return wakeSongsAt(now + 60_000)
            val raw = CarPaths.songDownload(context, d.songId).apply { parentFile?.mkdirs() }
            when (val r = relay.song(c.token, d.songId, raw, MAX_SONG_BYTES)) {
                is Res.Ok -> finishSong(d, raw, r.value, null)
                is Res.Err -> when {
                    r.status == 401 -> { raw.delete(); return invalidate(c) }
                    r.status == 429 -> dao.retryDownload(d.songId, now + (r.retryAfterS?.times(1000)?.coerceAtLeast(1000) ?: backoffMs(d.attempts)))
                    r.status >= 500 -> dao.retryDownload(d.songId, now + backoffMs(d.attempts))
                    else -> finishSong(d, raw, null, "download_failed")   // 404: 중계에 파일이 없다(이미 지움·7일 지남)
                }
                Res.Offline -> dao.retryDownload(d.songId, now + backoffMs(d.attempts))
            }
            raw.delete()
        }
    }

    /** 크기·길이·형식 검사 → −30 LUFS 사본(실행기) → 마지막 트랜잭션(8.4). */
    private suspend fun finishSong(d: SongDownloadEntity, raw: File, size: Long?, failed: String?) {
        val p = if (failed == null) probe(raw) else null
        val checked = failed ?: when {
            size!! > MAX_SONG_BYTES -> "too_large"
            p == null || !p.aac -> "bad_format"
            p.durationS > Params().songMaxSec -> "too_long"
            else -> null
        }
        val copy = CarPaths.songFile(context, d.songId)
        val made = if (checked == null) {
            try { makeCopy(raw, copy) } catch (e: CancellationException) { throw e }   // 취소는 거절이 아니다. 다음에 다시 받는다
            catch (e: Exception) { LoudnessResult(false, "bad_format") }
        } else null
        // 실행기의 거절 이유는 그대로 싣는다. 3.3 열거에 없는 글자면 bad_format으로 둔다(폰이 읽지 못하는 값을 보내지 않게)
        val reason = checked ?: made?.takeIf { !it.ok }?.let { m -> m.reason?.takeIf { LinkRoom.rejectReason(it) != null } ?: "bad_format" }
        val out = Applied()
        rx.withLock {
            if (!room.finishDownload(d, reason, copy.path, p?.durationS, clock(), out)) copy.delete()
            after(out)
        }
    }

    private fun wakeSongsAt(at: Long) {
        songWake?.cancel()
        songWake = scope.launch { delay((at - clock()).coerceAtLeast(0)); songSignal.trySend(Unit) }
    }

    // ---------------------------------------------------------------- 연결 화면(8.7)

    fun openPairing() {                            // 연결 화면 열림(contracts-link 8.7 ①~③)
        pairingOpen = true
        pairJob?.cancel()
        pairJob = scope.launch { pairLoop() }
        kick.trySend(Unit)
    }

    /** 코드를 받아 띄우고, 만료되면 화면이 열린 동안 새로 받는다(8.7 ②). 처음 한 번 기기 목록을 대조한다(8.4). */
    private suspend fun pairLoop() {
        var fails = 0
        var first = true
        while (pairingOpen) {
            try {
            // 요청 앞에서 비운다: 그 전의 신호(자기 401 등)는 이번 요청이 이미 반영한다. 요청 뒤의 신호(대조의 401 등)는 남겨 바로 새 코드를 받는다
            pairKick.tryReceive()
            val c = credential()
            when (val r = if (c == null) Res.Offline else pairLock.withLock { relay.pairCode(c.token) }) {
                is Res.Ok -> {
                    if (cred !== c) continue   // 그사이 차 토큰 무효: 옛 방의 코드는 띄우지 않고 새 방에서 다시 받는다
                    fails = 0; registerTries = 0
                    val exp = instantOf(r.value.expiresAt) ?: (clock() + Params().pairCodeTtlMs)
                    _status.update { it.copy(pairCode = r.value.code, pairUri = r.value.link, pairExpiresAt = exp, error = null) }
                    if (first && c != null) { first = false; reconcileDevices(c) }
                    val wait = exp - clock()
                    withTimeoutOrNull(if (wait > 0) wait else 30_000) { pairKick.receive() }   // 만료가 과거여도 1초마다 새 코드를 받지 않는다
                }
                is Res.Err -> if (r.status == 401) { invalidate(c); delay(backoffMs(fails++)) } else delay(r.retryAfterS?.times(1000)?.coerceAtLeast(1000) ?: backoffMs(fails++))
                Res.Offline -> delay(backoffMs(fails++))
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "연결 코드 오류", e)
                delay(backoffMs(fails++))
            }
        }
    }

    fun closePairing() {                           // 8.7 ④
        pairingOpen = false
        pairJob?.cancel()
        pairJob = null
        _status.update { it.copy(pairCode = null, pairUri = null, pairExpiresAt = null) }
        scope.launch {
            // 살아 있는 시간을 줄인다(6.2). 그사이 화면이 다시 열렸으면 새 코드를 지우지 않는다. 다시 연 화면의 코드 받기는 이 DELETE 뒤에 한다
            pairLock.withLock { if (!pairingOpen) cred?.let { relay.deletePairCode(it.token) } }
            recheck()
        }
    }

    fun disconnect(deviceId: String) {            // 8.7 ⑤
        scope.launch {
            val c = credential() ?: return@launch _status.update { it.copy(error = DISCONNECT_FAILED) }
            when (val r = relay.deleteDevice(c.token, deviceId)) {
                is Res.Ok -> unpairHere(deviceId)
                is Res.Err -> when (r.status) {
                    404 -> unpairHere(deviceId)   // 이미 없다: 같은 결과
                    401 -> invalidate(c)
                    else -> _status.update { it.copy(error = DISCONNECT_FAILED) }
                }
                Res.Offline -> _status.update { it.copy(error = DISCONNECT_FAILED) }
            }
        }
    }

    private suspend fun unpairHere(deviceId: String) {
        val out = Applied()
        rx.withLock { room.tx { room.unpair(deviceId, 0, clock(), out) }; after(out) }
        _status.update { it.copy(error = null) }
    }

    fun refreshPhones() {                          // 8.7 ⑥
        scope.launch { credential()?.let { reconcileDevices(it) } }
    }

    /** 정차 화면에서 고른 곡(G20, 8.5): 연결이 없어도 보내고, 돌아온 seq로 song_selection에 적용한다. */
    fun selectSong(songId: String) {
        if (!isUlid(songId)) return
        scope.launch {
            room.tx { room.enqueue("song_selected", buildJsonObject { put("song_id", songId) }, clock(), null) }
            pumpSignal.trySend(Unit)
        }
    }

    // ---------------------------------------------------------------- 상황판(8.8)

    internal suspend fun qa(cmd: Qa) {
        var question = cmd.text
        var st = llmState(null, clock(), 0)
        var tools: List<Event> = emptyList()
        var text: String? = null
        try {
            val s = state()
            val now = clock()
            val sid = s?.session?.id
            val saved = if (sid != null) dao.alarmEpisodeIds(sid) else emptyList()
            val unsaved = s?.session?.episode?.takeIf { it.trigger == EpisodeTrigger.ALARM && it.id !in saved }
            st = llmState(s, now, saved.size + if (unsaved != null) 1 else 0)
            val prof = dao.profile()
            question = llmQuestion(cmd.text, prof?.nickname)
            val methods = s?.session?.methods ?: s?.methodsNext.orEmpty()
            // 안내를 확인하지 않은 프로필은 AI에 묻지 않는다(C122)
            val call = if (prof.needsSetup()) null else scope.async { ask(question, st) }
            // 3초 안에 답이 없으면 오프라인 문장. 늦은 답과 도구는 버린다(G5)
            val r = call?.let { withTimeoutOrNull(qaWaitMs) { it.await() } }
            if (r == null) call?.cancel()
            val (t, x) = r?.let { qaEvents(it, cmd.listenId, clock(), st, question, methods) } ?: (emptyList<Event>() to offlineReply(question, st))
            tools = t; text = x
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "상황판 처리 오류", e)
        } finally {   // 어떤 예외가 나도 QaReply는 하나 낸다(8.8)
            tools.forEach(post)
            post(QaReply(clock(), cmd.listenId, text ?: offlineReply(question, st)))
        }
    }

    private suspend fun ask(question: String, st: LlmState): LlmResponse? {
        val c = credential() ?: return null
        return when (val r = relay.llm(c.token, LlmRequest(ulid(clock()), question, st))) {
            is Res.Ok -> r.value
            is Res.Err -> null.also {
                Log.w(TAG, "상황판 실패 status=${r.status} code=${r.code}")
                if (r.status == 401) invalidate(c)
            }
            Res.Offline -> null
        }
    }

    // ---------------------------------------------------------------- 복원·장면

    /**
     * 복원 뒤 대조(8.3)의 수유·보호자 몫. 호스트가 스냅숏 복원 → Boot·Resume 뒤 첫 판단 전에 부르고, 돌려준 사건을 넣는다.
     * Room 값이 스냅숏과 다를 때만 만든다. 후보 ③(MethodsChanged)은 store 몫이다.
     */
    suspend fun restoreEvents(snapshot: EngineState, at: Long, refillCare: Boolean = true): List<Event> = withContext(Dispatchers.IO) {
        // 프로세스 시작·장면 끝만 복원한 실제 값으로 채운다(C126 ④). 장면 중 Resume 대조는 장면 상태라 실제 기준을 그대로 둔다(C133)
        if (refillCare) synchronized(careLock) { restored = true; realCare = snapshot.care; realCareSeq = snapshot.careSeq; realCareRoom = snapshot.careRoom; realCareGen = snapshot.careGen }
        val fed = dao.profile()?.lastFeedingStart
        val guardians = dao.otherGuardians()
        buildList {
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
        const val OPENED = "\u0000opened"
        const val RELINK = "휴대폰을 다시 연결해 주세요."            // 9장 차 토큰 무효
        const val DISCONNECT_FAILED = "연결을 끊지 못했어요. 잠시 뒤 다시 해 주세요."
        val MAX_SONG_BYTES = Params().songMaxBytes
        fun mono() = System.nanoTime() / 1_000_000
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
