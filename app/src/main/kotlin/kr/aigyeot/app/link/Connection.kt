package kr.aigyeot.app.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kr.aigyeot.app.store.bestEffort
import kr.aigyeot.app.store.bestEffortSuspend
import kr.aigyeot.shared.ProtocolJson
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "Link"
private const val OPENED = "\u0000opened"
private fun mono() = System.nanoTime() / 1_000_000

/**
 * 연결 반복·WebSocket·마지막 폰 확인·네트워크 콜백. 연결 반복은 60초 뒤 다시 돈다.
 * 소켓 세대 [sockGen]·소켓 [live]는 여기서만 쓴다. 받기는 [Inbox], 자격은 [Creds]에 맡긴다.
 */
internal class Connection(
    private val context: Context,
    private val room: LinkRoom,
    private val relay: Relay,
    private val clock: () -> Long,
    private val status: MutableStateFlow<LinkStatus>,
    private val rx: Mutex,
    private val creds: Creds,
    private val inbox: Inbox,
    private val scope: CoroutineScope,
    private val stateReady: suspend () -> Unit,   // 호스트 복원 뒤 첫 상태가 공개될 때까지 기다린다
    private val pairingOpen: () -> Boolean,
    private val wakeOutbox: () -> Unit,
    private val wakeSongs: () -> Unit,
) {
    private val dao = room.dao
    private val kick = Channel<Unit>(Channel.CONFLATED)         // 연결 반복을 바로 깨운다('바로 다시 확인하는 계기')
    private val pingNow = Channel<Unit>(Channel.CONFLATED)      // 연결 중 바로 다시 확인: 곧바로 ping
    private val recheckSignal = Channel<Unit>(Channel.CONFLATED) // 마지막 폰 확인 요청. 확인 중 들어온 요청은 하나로 합쳐져 한 번 더 확인한다
    @Volatile private var live: Pair<RelaySocket, Channel<Frame>>? = null
    private class Frame(val text: String)
    private val sockGen = AtomicInteger()   // 연결마다 올리는 소켓 세대: dropSocket 뒤 닫은 소켓의 늦은 프레임·ack은 무시한다
    private var netWatch: ConnectivityManager.NetworkCallback? = null

    // ---------------------------------------------------------------- 시작·멈춤

    /** 연결 반복. 예외로 끝나도 60초 뒤 다시 돈다. 정상 반환(4002)이면 다시 돌지 않는다. */
    fun startLoop(): Job {
        recheckSignal.tryReceive()   // 멈춘 동안 쌓인 옛 요청은 버린다. 연결 반복이 처음부터 다시 판단한다
        return scope.launch {
            while (true) {   // 연결 반복이 예외로 끝나도 60초 뒤 다시 돈다. 정상 반환(4002)이면 다시 돌지 않는다
                // 정리 실패가 연결을 막지 않는다. 호스트 복원 전에는 받기를 시작하지 않는다
                try { bestEffortSuspend("만료 정리") { room.expire(clock()) }; inbox.refreshStatus(); stateReady(); connectLoop(); return@launch }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "연결 반복 오류", e); delay(backoffMs(6)) }
            }
        }
    }

    /** 마지막 폰 확인 소비 코루틴. */
    fun startRecheck(): Job = scope.launch {
        try { stateReady(); recheckUntilDone(needCred = true) }   // 시작 때 자격이 있고 폰이 없으면 확인 한 번: 재시도 중 멈췄거나 프로세스가 죽었어도 새 폰의 paired를 받는다. 첫 자격 읽기도 재시도 안
        catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "시작 때 마지막 폰 확인 오류", e) }
        for (x in recheckSignal) try { recheckUntilDone() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "마지막 폰 확인 오류", e) }
    }

    fun watchNetwork() {
        netWatch = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = wake()
        }.also { cb -> runCatching { context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) } }
    }

    /** 네트워크 콜백을 풀고 연결 없음으로(멈춤 조건). 소켓은 [dropSocket]으로 먼저 닫는다. */
    fun stopWatching() {
        netWatch?.let { cb -> runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netWatch = null
        conn(LinkConn.NONE)
    }

    /** 바로 다시 확인하는 계기: 화면 켜짐·시계 점프·Resume 감지·네트워크 복구. */
    fun wake() {
        kick.trySend(Unit)
        pingNow.trySend(Unit)
        wakeOutbox()
        wakeSongs()
    }

    fun kick() { kick.trySend(Unit) }

    // ---------------------------------------------------------------- 연결 상태

    private fun conn(c: LinkConn) = status.update { it.copy(conn = c) }

    private fun wantSocket() = pairingOpen() || dao.phoneCount() > 0

    fun dropSocket() {
        sockGen.incrementAndGet()
        live?.let { (ws, frames) -> ws.cancel(); frames.close() }
        live = null
    }

    /** PhoneLink가 없고 연결 화면도 닫혔으면 소켓을 닫는다(연결 없음). 확인은 start()의 소비 코루틴 하나가 차례로 한다. */
    fun recheck() {
        // 폰 유무 읽기가 다 실패하면 확인 신호를 넣는다(소비 코루틴이 다시 읽고 기다림 간격으로 재시도한다)
        var want = false
        bestEffort("폰 유무 읽기") { want = wantSocket() }
        if (want) kick.trySend(Unit) else recheckSignal.trySend(Unit)
    }

    private suspend fun safeCheck(f: suspend () -> Boolean): Boolean =
        try { f() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "마지막 폰 확인 예외", e); false }

    /** 확인은 성공할 때까지 끝까지 간다: 조회 밖의 예외(Room 등)로 빠져나가도 기다림 간격으로 다시 한다. 폰이 생기면 멈춘다. */
    private suspend fun recheckUntilDone(needCred: Boolean = false) {
        var n = 0
        while (true) {
            try { if (needCred && creds.storedCred() == null) return; if (!wantSocket()) recheckOnce(); return }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.e(TAG, "마지막 폰 확인 오류", e); delay(backoffMs(n++)) }
        }
    }

    private suspend fun recheckOnce() {
        var fails = 0
        while (true) {
            // 소켓에 이어 온 새 폰의 paired가 버려지지 않게 받은 함을 끝까지 받고 기기 목록을 대조한 뒤에도 폰이 없을 때만 닫는다
            val c = creds.cred
            val gen = sockGen.get()
            // 확인이 하나라도 실패하면 닫지 않는다. 닫는 판단은 프레임 처리와 같은 받기 잠금 안에서
            // 소켓이 없어도(끊겼어도) 확인한다: 끊김 사이 붙은 새 폰이 반영돼야 한다. 예외도 실패와 같이 다시 한다
            val ok = c == null || safeCheck { inbox.syncInbox(c) == Link.Sync.DONE && inbox.reconcileDevices(c) }
            if (ok) {
                // 확인 시작 때의 자격·소켓 세대가 그대로일 때만 닫는다: 바뀌었으면 옛 방·옛 소켓의 결과라 지금 연결로 다시 판단한다
                val closed: Boolean? = rx.withLock {
                    if (wantSocket()) null   // 소켓이 이미 끊겼어도(live == null) 마지막 받은 함 확인은 한다. 닫을 소켓이 없을 뿐
                    else if (creds.cred !== c || sockGen.get() != gen) false
                    else { dropSocket(); true }
                }
                if (closed == false) continue
                // 닫으며 버려진 프레임(ack 안 된 paired 등)은 받은 함에 남아 있다: 한 번 더 받아 처리한다
                // 받기가 실패하면 성공할 때까지 같은 간격으로 다시 받는다. 폰이 생기면(wantSocket) 멈추고 다시 붙는다
                // 401(LOST)로 자격이 지워졌거나 지금 자격이 그 요청의 것과 다르면 폐기된 토큰으로 계속 요청하지 않는다
                if (closed == true && c != null) while (true) {
                    val r = try { inbox.syncInbox(c) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Log.w(TAG, "마지막 폰 확인 받기 예외", e); Link.Sync.FETCH_FAILED }
                    if (r == Link.Sync.DONE || r == Link.Sync.LOST || wantSocket() || creds.cred != c) break
                    delay(backoffMs(fails++))
                }
                break
            }
            if (creds.cred !== c) continue    // 방이 바뀌었다: 옛 자격의 실패라 기다리지 않고 지금 자격으로 다시
            delay(backoffMs(fails++))   // 확인 실패: 기다림 간격으로 다시 확인한다
            if (wantSocket()) break
        }
        kick.trySend(Unit)
    }

    private suspend fun connectLoop() {
        var fails = 0
        var fallbackOk: Boolean? = null   // HTTPS 대체 확인: 실패(또는 처음) 뒤 성공할 때마다 기기 목록 대조
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
            val c = creds.credential()
            val code = if (c == null) -1 else runSocket(c) { fails = 0; fallbackOk = null }
            when (code) {
                4001, 401 -> creds.invalidate(c)   // 아래로 내려가 기다린다: 곧바로 재등록하면 연쇄한다
                4002 -> { conn(LinkConn.NONE); return }   // 같은 기기의 새 연결. 서비스가 다시 시작될 때까지
            }
            fails++
            conn(LinkConn.BACKOFF)
            if (!wantSocket()) continue   // 끊긴 뒤에는 맨 위에서 NONE으로
            // 연속 3번 실패 뒤 60초마다 HTTPS
            if (code != 4001 && code != 401 && c != null && fails >= 3 && lastFallback.let { it == null || clock() - it >= 60_000 }) {
                lastFallback = clock()
                val r = inbox.syncInbox(c)
                if (r == Link.Sync.DONE && fallbackOk != true) inbox.reconcileDevices(c)
                wakeOutbox()   // '보낼 함 확인': 성공·실패와 상관없이
                fallbackOk = r == Link.Sync.DONE
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
            override fun onOpen() { frames.trySend(Frame(OPENED)) }
            // pong은 프레임 루프가 첫 동기화에 묶여 있어도 바로 적는다(안 그러면 멀쩡한 소켓이 끊긴다)
            override fun onText(text: String) { if (text == "pong") lastPong.set(mono()) else if (sockGen.get() == myGen) frames.trySend(Frame(text)) }
            override fun onClosed(code: Int) { closeCode.compareAndSet(-1, code); frames.close() }
        })
        live = ws to frames
        var pinger: Job? = null
        try {
            for (frame in frames) when (val text = if (sockGen.get() == myGen) frame.text else break) {   // 닫은 소켓의 대기 프레임은 버린다: ack하지 않았으니 다시 연결 뒤 받은 함에서 다시 온다
                OPENED -> {
                    onOpen()
                    creds.resetRegisterTries()
                    conn(LinkConn.CONNECTED)
                    pinger = launch {   // 25초마다 글자 ping, 10초 안에 pong이 없으면 끊고 다시 붙는다
                        withTimeoutOrNull(25_000) { pingNow.receive() }   // 깨우면(wake) 바로 ping
                        while (isActive) {
                            val sentAt = mono()
                            ws.send("ping")
                            delay(10_000)
                            if (lastPong.get() < sentAt) { ws.cancel(); frames.close(); break }
                            withTimeoutOrNull(15_000) { pingNow.receive() }
                        }
                    }
                    // 열린 소켓의 프레임은 채널에 쌓아 두고 받은 함부터 모두 처리한다('붙을 때')
                    when (inbox.syncInbox(c)) {
                        Link.Sync.LOST -> return@coroutineScope 401
                        Link.Sync.STORE_FAILED -> break   // 저장 실패: 뒤 실시간 프레임을 처리하면 seq 순서가 깨진다. 닫고 다시 받는다
                        else -> Unit
                    }
                    inbox.reconcileDevices(c)                     // '연결됨이 되어 밀린 메시지를 모두 처리한 직후'
                    wakeOutbox()
                    wakeSongs()
                }
                else -> {
                    val env = runCatching { parseEnvelope(ProtocolJson.parseToJsonElement(text)) }.getOrNull() ?: continue
                    val rr = inbox.receive(env, c)
                    if (rr == Link.Rx.FAIL) break                                         // 저장 실패: ack 없이 닫는다. 다시 붙어 받은 함에서 다시 받는다(4단계)
                    if (rr == Link.Rx.ACK && env.id.isNotEmpty() && sockGen.get() == myGen) ws.send(ackFrame(listOf(env.id), clock()))
                }
            }
        } finally {
            pinger?.cancel()
            ws.cancel()
            if (live?.first === ws) live = null
        }
        closeCode.get()
    }
}
