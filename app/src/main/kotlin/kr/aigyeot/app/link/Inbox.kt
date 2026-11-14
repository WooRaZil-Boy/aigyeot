package kr.aigyeot.app.link

import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.aigyeot.app.common.isUlid
import kr.aigyeot.app.store.bestEffort
import kr.aigyeot.app.store.bestEffortSuspend
import kr.aigyeot.engine.CareCleared
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FeedingFrom
import kr.aigyeot.shared.Envelope

private const val TAG = "Link"

/**
 * 받기: 받은 함 가져오기·봉투 하나 처리·커밋 뒤 처리, 기기 목록 대조, 화면 상태 읽기. 받기 잠금 [rx]를 쓴다.
 * ack 전 실패는 던지고, 정리·화면 읽기는 실패해도 로그만 남긴다.
 */
internal class Inbox(
    private val room: LinkRoom,
    private val relay: Relay,
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    private val creds: Creds,
    private val rx: Mutex,
    private val status: MutableStateFlow<LinkStatus>,
    private val wakeOutbox: () -> Unit,
    private val wakeSongs: () -> Unit,
    private val recheck: () -> Unit, // 폰이 생기거나 없어졌다: 소켓 유지 여부를 다시 판단
    // 연결 코드가 쓰였다: 화면이 열려 있으면 새 코드
    private val onNewOwner: () -> Unit
) {
    private val dao = room.dao

    /** 받은 함을 more = false까지 받아 seq 순서로 처리하고, 커밋한 것만 ack한다. 401이면 차 토큰 무효를 처리하고 LOST. */
    suspend fun syncInbox(c: Cred? = creds.cred): Link.Sync {
        c ?: return Link.Sync.FETCH_FAILED
        var after = 0L
        while (true) {
            when (val r = relay.inbox(c.token, after)) {
                is Res.Ok -> {
                    val msgs = r.value.messages.sortedBy { it.seq ?: 0 }
                    val done = mutableListOf<String>()
                    var failed = false
                    for (env in msgs) {
                        when (receive(env, c)) {
                            Link.Rx.ACK -> if (env.id.isNotEmpty()) done += env.id
                            Link.Rx.FAIL -> {
                                failed = true
                                break
                            }
                        }
                    }
                    for (ids in done.chunked(100)) {
                        val a = relay.ack(c.token, ids)
                        if (a is Res.Err && a.status == 401) return Link.Sync.LOST.also { creds.invalidate(c) }
                    }
                    if (failed) return Link.Sync.STORE_FAILED
                    // ack 뒤 정리 실패가 소켓을 끊지 않는다
                    if (msgs.isEmpty() || !r.value.more) return Link.Sync.DONE.also { bestEffortSuspend("받은 뒤 만료 정리") { room.expire(clock()) } }
                    after = msgs.last().seq ?: after
                }
                is Res.Err -> return if (r.status == 401) Link.Sync.LOST.also { creds.invalidate(c) } else Link.Sync.FETCH_FAILED
                Res.Offline -> return Link.Sync.FETCH_FAILED
            }
        }
    }

    /**
     * 봉투 하나(1~3단계와 5단계). ACK면 ack해도 된다(처리를 커밋했거나 무시할 메시지). 저장에 실패하면 FAIL.
     * [c]는 이 봉투를 받은 자격. 그사이 차 토큰 무효로 방이 바뀌었으면 옛 방의 seq를 새 방에 적용하지 않고 FAIL(멈추고 다시 받는다).
     */
    suspend fun receive(env: Envelope, c: Cred? = null): Link.Rx = rx.withLock {
        if (c != null && creds.cred !== c) return Link.Rx.FAIL
        if (env.v != 1 || env.type !in RECEIVED_TYPES || !isUlid(env.id)) {
            Log.w(TAG, "받지 않는 메시지 type=${env.type} v=${env.v} seq=${env.seq}")
            return Link.Rx.ACK
        }
        val carId = (c ?: creds.cred)?.carId ?: env.car
        var out: Applied? = null
        if (!bestEffortSuspend("받기 저장 id=${env.id} type=${env.type}") { out = room.receive(env, clock(), carId) }) return Link.Rx.FAIL // 다 실패하면 ack하지 않는다
        out?.let { after(it, carId) }
        Link.Rx.ACK
    }

    /** 차 화면 수유 입력: 받기와 같은 잠금에서 폰 feeding_started와 같은 수유 함수를 쓴다. 다 실패하면 로그만(사건을 내지 않는다). */
    suspend fun feedingFromCar(at: Long) = rx.withLock {
        var out: Applied? = null
        if (bestEffortSuspend("차 화면 수유 저장") { out = room.tx { Applied().also { room.feedingStarted(at, FeedingFrom.CAR_SCREEN, null, clock(), it) } } }) {
            out?.let { after(it) }
        }
    }

    /** 커밋 뒤: 사건, 파일 정리, 일꾼 깨우기, 화면 상태(5단계). */
    fun after(out: Applied, carId: String? = null) {
        out.deleteFiles.forEach { File(it).delete() }
        out.events.forEach(post)
        // 실제 최신 요약은 Room 행(care_applied) 하나가 든다. link는 사건만 내고, 장면 중이면 호스트가 버리고 장면 끝 복원 ⑤가 이 행으로 맞춘다
        if (out.careCleared) post(CareCleared(clock())) // 요약을 보낸 owner가 빠졌다. 행은 커밋 안에서 지웠다
        out.care?.let { c -> post(CareSummary(clock(), c, out.careSeq, carId)) } // 판정·Room 행은 커밋 안에서 끝났다
        if (out.outbox) wakeOutbox()
        if (out.download) wakeSongs()
        if (out.phones) {
            refreshStatus()
            recheck()
        } else if (out.status) {
            refreshStatus()
        }
        out.justPaired?.let { id -> status.update { it.copy(justPaired = id, error = null) } }
        if (out.newOwner) onNewOwner() // 연결 코드가 쓰였다. 화면이 열려 있으면 새 코드
    }

    // 화면 표시용 읽기라 다 실패해도 던지지 않는다(뒤의 재확인·justPaired를 막지 않는다). 상태는 직전 값, 다음 갱신 때 맞춰진다
    fun refreshStatus() {
        bestEffort("연결 상태 읽기") {
            val phones = dao.phoneLinks().map { LinkedPhone(it.id, it.role, it.lastSeenAt) }
            val registered = creds.cred != null || dao.credential() != null
            status.update { it.copy(phones = phones, registered = registered) }
        }
    }

    /** 기기 목록 대조. 받기와 섞이지 않게 같은 잠금 안에서 한다. */
    suspend fun reconcileDevices(c: Cred): Boolean = when (val r = relay.devices(c.token)) {
        // after도 잠금 안: LinkChanged 순서. 그사이 방이 바뀌었으면 옛 방의 목록을 새 방에 적용하지 않는다
        // 방이 바뀌어 적용 안 했으면 false. 다 실패해도 false(적용 안 함). 호출자는 다음 확인·대조 때 다시 한다
        is Res.Ok -> rx.withLock {
            var out: Applied? = null
            bestEffortSuspend("기기 목록 대조") { out = if (creds.cred === c) Applied().also { room.reconcile(r.value, clock(), it) } else null }
            out?.let {
                after(it)
                true
            } ?: false
        }
        is Res.Err -> {
            if (r.status == 401) creds.invalidate(c)
            false
        }
        Res.Offline -> false
    }
}
