package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.OutboxItem
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.SAVE_RETRY_MS
import kr.aigyeot.phone.domain.model.SendResult
import kr.aigyeot.phone.domain.model.afterSend
import kr.aigyeot.phone.domain.model.backoffMs
import kr.aigyeot.phone.domain.model.frontExpired
import kr.aigyeot.phone.domain.model.markSentOnce
import kr.aigyeot.phone.domain.model.nextToSend
import kr.aigyeot.phone.domain.model.posting
import kr.aigyeot.phone.domain.model.pruned
import kr.aigyeot.phone.domain.model.reconciledSummary
import kr.aigyeot.phone.domain.model.untried
import kr.aigyeot.phone.domain.model.withMessageHold
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.RelayClient
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.phone.domain.state.AppVisibility
import kr.aigyeot.phone.domain.state.Notices

/**
 * 보낼 함 일꾼(7.5). 한 일꾼이 넣은 순서대로 하나씩 보낸다. 신호는 합쳐지므로 도는 중에 온 신호는 한 번 더 돌게 한다.
 * 결과는 보낼 때 잡은 자격·항목으로 처리한다(P5). 메시지 POST의 429 기다림은 그 항목을 지우거나 바꿔도, 다시 켜도 지킨다(1.1).
 */
class SendOutbox(
    private val state: PhoneStateRepository,
    private val care: CareRepository,
    private val relay: RelayClient,
    private val upload: UploadSong,
    private val lose: LoseConnection,
    private val notices: Notices,
    private val visibility: AppVisibility,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var wake: Job? = null

    /** 지금 보내는 보낼 함 항목 id. 만료 정리·기록 지우기·요약 맞추기가 결과를 기다리게 한다(보내는 사이 7일을 넘겨도 성공을 실패로 적지 않게). */
    @Volatile
    var sending: String? = null
        private set

    /** '보내 봄'을 적었지만 보내지 않고 멈췄는데 걷는 저장이 실패한 요약 id(이 일꾼만 읽고 쓴다). */
    private var untryPending: String? = null

    /** 저장하지 못한 메시지 429 기다림의 끝과 그것을 받은 자격. 자격이 바뀌면 걸리지 않는다(저장된 것은 새 상태와 함께 사라졌다). */
    @Volatile
    private var memHold: kotlin.Pair<Credentials, Long>? = null

    init {
        scope.launch { for (x in signal) pumpOnce() }
    }

    fun pump() {
        signal.trySend(Unit)
    }

    /** 뒤로 갔다: 깨움 타이머를 걷는다. 앞으로 올 때(연결 반복) 이어 보낸다(7.5). */
    fun cancelWake() {
        wake?.cancel()
    }

    /** 지금 메시지 429 기다림이 끝나는 시각. 없으면 null. 상태 요청(KeepConnected)도 같은 기다림을 지킨다. */
    fun holdUntil(now: Long): Long? =
        maxOf(memHold?.takeIf { it.first == state.creds }?.second ?: 0, state.state.value.messageHoldUntil ?: 0).takeIf { it > now }

    /** 메시지 POST가 429를 받았다: 저장하지 못해도 지키게 메모리에도 둔다(더 늦은 끝을 남긴다). */
    fun holdInMemory(c: Credentials, until: Long) {
        memHold = c to maxOf(memHold?.takeIf { it.first == c }?.second ?: 0, until)
    }

    /** 7일 지난 것을 정리해 저장하고(7.5), 그때 버린 메시지를 띠로 알린다(U54). 저장하지 못하면 거짓. */
    suspend fun commitPruned(c: Credentials, then: (PhoneState) -> PhoneState = { it }): Boolean {
        if (!state.commit(c) { then(it.pruned(clock.now(), sending)) }) return false
        showDrops()
        return true
    }

    /** 띠를 닫았다(U54): 닫은 띠에 있던 버림(메시지 id)만 지운다. 그사이 새로 버린 것은 같은 종류여도 남긴다. */
    fun dismissNotice() {
        val seen = state.state.value.unseenDrops.keys
        notices.dismiss(seen)
        // 연결과 따로 저장한다(자격을 못 읽은 실행에서도 닫은 것이 다음 실행에 다시 뜨지 않게). 저장하지 못하면 다음 실행에 다시 뜬다.
        if (seen.isNotEmpty()) scope.launch { state.commitLocal { it.copy(unseenDrops = it.unseenDrops - seen) } }
    }

    private fun showDrops() = notices.showDrops(state.state.value.unseenDrops)

    /** 뒤에서는 타이머를 걸지 않는다. 앞으로 올 때(연결 반복) 이어 보낸다(7.5). */
    private fun wakeAt(at: Long) {
        wake?.cancel()
        if (!visibility.visible.value) return
        wake = scope.launch {
            delay(at - clock.now())
            pump()
        }
    }

    /** 저장에 실패했다(W 범주): 잠시 뒤(429 기다림이 있으면 그 뒤) 다시 깨운다(iOS `retrySaveLater`와 같음). */
    private fun retrySaveLater() = wakeAt(maxOf(clock.now() + SAVE_RETRY_MS, holdUntil(clock.now()) ?: 0))

    private suspend fun pumpOnce() {
        var refreshed: String? = null // 보내기 직전 재계산은 항목마다 한 번(시계가 뒤에 있는 동안 바꾸기만 되풀이하지 않게)
        while (true) {
            val c = state.creds ?: return
            // 보내지 않고 멈춘 요약의 '보내 봄'을 걷지 못했다: 걷을 때까지 아무것도 보내지 않는다(그 요약을 보낸 것처럼 보내지 않게).
            untryPending?.let { id ->
                if (!state.commit(c) { it.untried(id) }) return retrySaveLater()
                untryPending = null
            }
            // 맨 앞이 만료면 정리(만료 표시 포함)를 먼저 저장한다. 저장하지 못하면 멈춘다(다음 신호 때 다시).
            if (state.state.value.frontExpired(clock.now()) && !commitPruned(c)) return retrySaveLater()
            // 메시지 429면 Retry-After까지 아무것도 보내지 않는다.
            holdUntil(clock.now())?.let { return wakeAt(it) }
            val item = state.state.value.nextToSend(clock.now())
            if (item?.envelope?.type == "care_summary" && refreshed != item.envelope.id) {
                // 보내기 직전에 다시 계산한다: 오래 기다린 요약의 as_of·'오늘' 값이 낡지 않게(자정을 넘긴 복구, 그사이 지운 기록).
                // 같은 날 같은 내용이면 그대로(같은 id). 바꿨으면 처음부터 다시 고른다. 저장하지 못하면 옛 요약을 보내지 않는다(iOS와 같음).
                // 요약만 다시 계산한다: 수유 보내기는 정해진 네 계기에서만(R6).
                if (!state.commit(c) { it.reconciledSummary(care.care.value, clock.now(), APP_ZONE, sending) }) return retrySaveLater()
                refreshed = state.state.value.outbox.firstOrNull { it.envelope.type == "care_summary" }?.envelope?.id
                if (refreshed != item.envelope.id) continue
            }
            if (item == null) return wakeAt(state.state.value.outbox.firstOrNull()?.nextTryAt ?: return)
            // 요약은 보내기 전에 '보내 봄'을 저장한다(U57): 응답 전에 죽거나 결과 저장이 실패해도 바꿔치우지 않는다.
            var marked = false
            if (item.envelope.type == "care_summary" && !item.sentOnce) {
                var present = false
                if (!state.commit(c) { s -> present = s.outbox.any { it.envelope.id == item.envelope.id }; s.markSentOnce(item.envelope.id) }) return retrySaveLater()
                if (!present) continue // 그사이 지워졌다(기록 지우기 등): 다시 고른다
                marked = true
            }
            // 위 저장을 기다리는 사이 다른 메시지(상태 요청 등)가 429를 받았을 수 있다: 보내기 바로 앞에서 다시 본다(1.1).
            // 방금 적은 '보내 봄'은 걷는다: 보내지 않았으니 기다리는 사이 기록을 지우면 이 요약도 빠져야 한다(R10).
            holdUntil(clock.now())?.let { h ->
                if (marked && !state.commit(c) { it.untried(item.envelope.id) }) untryPending = item.envelope.id
                return wakeAt(h)
            }
            sending = item.envelope.id
            try {
                val r = sendItem(c, item)
                if (!state.isCurrent(c)) return // 이전 연결의 응답은 버린다
                if (r is SendResult.Failed && r.status == 401) return lose.lost(c)
                // 메시지 POST의 429는 저장하지 못해도 Retry-After를 지킨다(상태에도 같은 저장으로 적는다).
                if (r is SendResult.Failed && r.status == 429 && item.posting) {
                    holdInMemory(c, clock.now() + (r.retryAfterS?.times(1000) ?: backoffMs(item.attempts)))
                }
                // 저장하지 못했으면 잠시 뒤 다시 한다. 같은 id로 다시 보내므로 중계에서는 한 번만 처리된다.
                if (!state.commit(c) { it.afterSend(item, r, clock.now(), aheadMs = -(relay.clockSkewMs ?: 0)) }) return retrySaveLater()
            } finally {
                sending = null
            }
            showDrops() // 영구 실패로 버렸으면 afterSend가 같은 저장에 적었다
        }
    }

    private suspend fun sendItem(c: Credentials, item: OutboxItem): SendResult {
        upload(c, item)?.let { return it }
        return when (val r = relay.send(c.token, item.envelope)) {
            is RelayResult.Ok -> SendResult.Sent(r.value.body)
            is RelayResult.Err -> SendResult.Failed(r.status, r.code, r.retryAfterS)
            RelayResult.Offline -> SendResult.Offline
            RelayResult.Unreached -> SendResult.Unreached
        }
    }
}
