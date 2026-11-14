package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.aigyeot.phone.domain.model.Banner
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.Inbound
import kr.aigyeot.phone.domain.model.InboundMessage
import kr.aigyeot.phone.domain.model.StoredAlert
import kr.aigyeot.phone.domain.model.isUlid
import kr.aigyeot.phone.domain.model.rateLimitWaitMs
import kr.aigyeot.phone.domain.model.receive
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.RelayClient
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.phone.domain.repository.SongFileStore
import kr.aigyeot.phone.domain.state.Notices
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 받기: 받은 봉투를 저장한 뒤에만 ack한다. 저장하지 못하면 ack하지 않는다(중계가 다시 준다).
 * 같은 id는 한 번만 띄운다. 차 id 확인은 [receive]가 한다.
 */
class SyncInbox(
    private val state: PhoneStateRepository,
    private val relay: RelayClient,
    private val lose: LoseConnection,
    private val notices: Notices,
    private val songs: SongFileStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    /** 받은 함 동기화 결과. DONE은 모든 쪽을 받아 저장까지 끝난 것뿐이다. */
    enum class Sync { DONE, FAILED, LOST }

    /** 앞에 있을 때 푸시가 온 메시지 id. 본문이 지금 자격·같은 차로 들어오면 그때 배너로 띄운다. */
    private val pendingPush: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 차의 실시간 봉투를 적용한 횟수와 그 시각. 기기 목록을 기다리는 사이 받았는지 KeepConnected가 본다(iOS `carLiveSeen`과 같음). */
    private val carLiveSeen = AtomicInteger(0)

    @Volatile
    private var lastCarLiveAt: Long? = null

    /** 받은 함 429의 Retry-After 기한과 그것을 받은 자격(iOS `inboxHoldUntil`). 자격이 바뀌면 걸리지 않는다. 메모리에만 둔다(iOS와 같다). */
    @Volatile
    private var inboxHold: Pair<Credentials, Long>? = null

    /** 받은 함 429 기다림이 끝나는 시각. 없으면 null. 기다리는 동안은 받은 함도 소켓도 열지 않는다(iOS `beginRefresh`와 같다). */
    fun inboxHoldUntil(c: Credentials, now: Long): Long? = inboxHold?.takeIf { it.first == c }?.second?.takeIf { it > now }

    fun carLiveMark(): Int = carLiveSeen.get()

    /** `mark` 뒤에 차의 실시간 봉투를 받았으면 그 시각. 상태 잠금 안에서 부른다(같은 잠금에서 세므로 틈이 없다). */
    fun carLiveSince(mark: Int): Long? = lastCarLiveAt.takeIf { carLiveSeen.get() != mark }

    /** 받은 함 받기는 한 번에 하나다(iOS `refreshing`). */
    private val gate = Mutex()

    /** 시작한·끝난 받기 수와 마지막 결과. 기다리는 사이 끝난 받기에 합류한다. */
    @Volatile
    private var started = 0L

    @Volatile
    private var finished = 0L
    private var last: Pulled? = null

    private class Pulled(val c: Credentials, val sync: Sync, val number: Long)

    /**
     * 받은 함을 모두 받아 저장한 뒤 ack한다(배너 없이). 받기는 한 번에 하나다.
     * - 기본: 같은 자격의 받기가 돌고 있으면 새로 묻지 않고 그 결과에 합류한다(연결 반복의 대체 경로. iOS `refresh`가 '갱신 중'이면 새로 열지 않는 것과 같다).
     * - `fresh`: 도는 받기는 부른 때보다 앞선 것이라 끝난 뒤 한 번 더 받는다. 소켓이 붙음(iOS `reconnectedDuringRefresh`)과 푸시(그 메시지가 도는 받기보다 늦게 왔을 수 있다)다.
     *   기다리는 사이 부른 뒤에 시작한 받기가 끝났으면 그것에 합류한다(한 번 더는 한 번).
     */
    suspend fun sync(c: Credentials, fresh: Boolean = false): Sync {
        val startedBefore = started
        val finishedBefore = finished
        return gate.withLock {
            last?.takeIf { it.c == c && if (fresh) it.number > startedBefore else finished != finishedBefore }?.sync
                ?: (++started).let { n -> pull(c).also { last = Pulled(c, it, n); finished++ } }
        }
    }

    private suspend fun pull(c: Credentials): Sync {
        if (inboxHoldUntil(c, clock.now()) != null) return Sync.FAILED // 429 기다림 중: 기한 전에는 묻지 않는다
        var after = 0L
        while (true) {
            when (val r = relay.inbox(c.token, after)) {
                is RelayResult.Ok -> {
                    if (!state.isCurrent(c)) return Sync.LOST
                    val got = r.value.messages
                    // 한 쪽을 모두 적용해 한 번만 저장한다. 저장이 확인돼야 ack하고, 실패하면 하나도 ack하지 않는다(중계가 다시 준다).
                    val stored = if (storeAll(c, got)) got else emptyList()
                    if (stored.isNotEmpty()) {
                        val ack = relay.ack(c.token, stored.map { it.id })
                        if (ack is RelayResult.Err && ack.status == 401) return Sync.LOST.also { lose.lost(c) }
                    }
                    if (!state.isCurrent(c)) return Sync.LOST
                    if (stored.size < got.size) return Sync.FAILED
                    if (got.isEmpty() || !r.value.more) return Sync.DONE
                    after = got.last().seq
                }
                is RelayResult.Err -> {
                    if (r.status == 401) return Sync.LOST.also { lose.lost(c) }
                    // 옛 자격의 늦은 429가 지금 자격의 기한을 덮지 않게 지금 자격일 때만 적는다(iOS `performRefresh`와 같음).
                    if (r.status == 429 && state.isCurrent(c)) inboxHold = c to clock.now() + rateLimitWaitMs(r.retryAfterS)
                    return Sync.FAILED
                }
                RelayResult.Offline, RelayResult.Unreached -> return Sync.FAILED
            }
        }
    }

    /**
     * 소켓으로 받은 봉투 하나를 저장한다. 푸시가 먼저 온 id는 받은 함에서 꺼내도 배너로 띄운다. 저장이 확인되면 참.
     * 도는 받기를 기다리지 않는다: 같은 소켓 반복이 pong도 처리하므로 기다리면 끊긴 것으로 볼 수 있다(iOS는 프레임을 모아 둔다, 최종 상태는 같다).
     */
    suspend fun store(c: Credentials, m: Inbound, live: Boolean): Boolean {
        var banner: StoredAlert? = null
        val isLive = live || m.id in pendingPush
        val ok = state.commit(c) { s ->
            s.receive(m, clock.now(), isLive).also { r ->
                banner = r.banner
                // 늦게 온 기기 목록이 이 관찰(차가 지금 켜져 있음)을 덮지 않게 센다. 잠금 안에서 세야 그 목록의 병합(같은 잠금)이 틈 없이 본다.
                if (isLive && m.fromCar && r.state !== s) {
                    lastCarLiveAt = clock.now()
                    carLiveSeen.incrementAndGet()
                }
            }.state
        }
        if (ok) afterStore(banner, m.message is InboundMessage.SongStatus)
        return ok
    }

    /** 받은 함 한 쪽을 한 번의 저장으로 적용한다. 저장이 확인되면 참. */
    private suspend fun storeAll(c: Credentials, ms: List<Inbound>): Boolean {
        if (ms.isEmpty()) return true
        var banner: StoredAlert? = null
        val ok = state.commit(c) { s0 ->
            ms.fold(s0) { s, m -> s.receive(m, clock.now(), m.id in pendingPush).also { r -> r.banner?.let { banner = it } }.state }
        }
        if (ok) afterStore(banner, ms.any { it.message is InboundMessage.SongStatus })
        return ok
    }

    private suspend fun afterStore(banner: StoredAlert?, songStatus: Boolean) {
        banner?.let { notices.showBanner(Banner(it.body.text, it.id)) }
        // 다른 owner 폰에서 지운 곡(song_status removed)의 녹음 파일도 지움 상태를 저장한 뒤 정리한다.
        if (songStatus) songs.clean()
        notices.pruneFeedingDrop(state.state.value.unseenDrops)
    }

    /**
     * 앞에 있을 때 온 푸시. 푸시 문장은 바로 띄우지 않는다: 이전 차의 늦은 푸시일 수 있다.
     * id를 보류해 두고 지금 자격으로 받은 함을 받아, 이 차의 본문이 저장될 때 배너로 띄운다.
     */
    fun onPush(msgId: String) {
        if (!isUlid(msgId)) return
        scope.launch {
            val c = state.creds ?: return@launch
            if (msgId in state.state.value.shown) return@launch // 이미 띄웠다(WebSocket이 먼저 옴)
            pendingPush += msgId
            try {
                sync(c, fresh = true) // 도는 받기가 이 메시지를 지나쳤을 수 있다
            } finally {
                pendingPush -= msgId
            }
        }
    }
}
