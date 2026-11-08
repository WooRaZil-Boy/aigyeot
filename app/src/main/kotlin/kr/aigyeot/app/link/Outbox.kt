package kr.aigyeot.app.link

import android.util.Log
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kr.aigyeot.app.link.db.SentIdEntity
import kr.aigyeot.app.store.WORKER_RETRY_MS
import kr.aigyeot.app.store.bestEffortSuspend
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.shared.SendMessageResponse

private const val TAG = "Link"

/**
 * 보내기(8.5): 보낼 함에 넣기, 넣은 순서대로 하나씩 보내기, 정차 화면 곡 선택. 범주 W(한 바퀴 실패 뒤 다시 깨움)·U(곡 선택).
 * 신호 [signal]이 일꾼 한 코루틴을 깨운다.
 */
internal class Outbox(
    private val db: AppDatabase,
    private val room: LinkRoom,
    private val relay: Relay,
    private val clock: () -> Long,
    private val creds: Creds,
    private val rx: Mutex,
    private val scope: CoroutineScope,
    private val after: (Applied) -> Unit,
) {
    private val dao = room.dao
    private val pumpSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var pumpWake: Job? = null
    private var outboxWatch: InvalidationTracker.Observer? = null

    /** 시험용(L86): 맨 앞을 고른 직후, 보낸 id를 적기 전에 부른다. 운영에서는 null. */
    @Volatile var afterFrontSelect: (() -> Unit)? = null

    fun signal() { pumpSignal.trySend(Unit) }

    // C302 W(L94): 한 바퀴를 다시 하고, 다 실패하면 새 신호가 없어도 WORKER_RETRY_MS 뒤 다시 깨운다
    fun startConsumer(): Job = scope.launch { for (x in pumpSignal) if (!bestEffortSuspend("보낼 함") { pumpOnce() }) wakePumpAt(clock() + WORKER_RETRY_MS) }

    /** store가 넣는 report_summary·records_clear도 깨운다(같은 outbox 표). */
    fun watch() {
        outboxWatch = object : InvalidationTracker.Observer("outbox") {
            override fun onInvalidated(tables: Set<String>) { signal() }
        }.also { db.invalidationTracker.addObserver(it) }
    }

    fun unwatch() {
        outboxWatch?.let { db.invalidationTracker.removeObserver(it) }
        outboxWatch = null
    }

    fun cancelWake() { pumpWake?.cancel() }

    /**
     * 보낼 함에 바로 넣는다(한 Room 트랜잭션). [also]는 같은 트랜잭션에서 하는 호스트 쓰기: 둘이 함께 커밋되거나 함께 사라진다(C160).
     * 결정 ③: [also](store 쓰기)가 먼저다. 같은 Step에서 생긴 에피소드 행에 handoff_alert를 넣으며 guardian_notified를 붙인다(LinkRoom.enqueue).
     */
    fun enqueueNow(cmds: List<SendToPhone>, also: () -> Unit = {}): Boolean {   // 돌려주는 값: 하나라도 보낼 함에 넣었는가(받는 사람이 없으면 거짓)
        val now = clock()
        val out = Applied()
        // feeding_state는 코어 body를 쓰지 않고 넣는 트랜잭션에서 Room 최신 값으로 만든다(3.3 '경합 방지', 차 계약 4.3)
        room.tx { also(); cmds.forEach { c -> room.enqueue(c.type, if (c.type == "feeding_state") room.feedingState() else withRfc3339Times(c.body), now, out) } }
        signal()
        return out.outbox
    }

    /** 정차 화면에서 고른 곡(G20, 8.5): 연결이 없어도 보내고, 돌아온 seq로 song_selection에 적용한다. */
    fun selectSong(songId: String) {
        if (!isUlid(songId)) return
        scope.launch {
            // C302 U: 다 실패하면 로그만(보내지 않는다)
            if (bestEffortSuspend("곡 선택") { room.tx { room.enqueue("song_selected", buildJsonObject { put("song_id", songId) }, clock(), null) } }) signal()
        }
    }

    /** 보낼 함을 넣은 순서대로 하나씩 보낸다. 맨 앞이 기다리는 중이면 뒤도 기다린다. */
    /** records_clear 항목 본문 `{"drop_ids":[…]}`의 id들. 옛 빈 본문은 빈 목록. 보낼 함의 리포트·알림은 1,000개 이하라 한 요청이다(L81). */
    private fun clearIds(bodyJson: String): List<String> =
        runCatching { Json.parseToJsonElement(bodyJson).jsonObject["drop_ids"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())

    suspend fun pumpOnce() {
        while (true) {
            val now = clock()
            // L86: 맨 앞 고르기와 보낸 id 적기를 한 트랜잭션으로(만료·지우기와 직렬화). 이미 사라진 항목은 고르지 못해 보내지 않는다
            val item = room.tx {
                dao.expireOutbox(now - KEEP_MS)
                dao.outboxFront()?.also {
                    afterFrontSelect?.invoke()
                    if (it.nextTryAt <= now && (it.type == "report_summary" || it.type == "handoff_alert")) {
                        dao.insertSentId(SentIdEntity(it.id, now)); dao.expireSentIds(now - SENT_ID_KEEP_MS); dao.capSentIds(SENT_ID_MAX)
                    }
                }
            } ?: return
            if (item.nextTryAt > now) return wakePumpAt(item.nextTryAt)
            val c = creds.credential() ?: return wakePumpAt(now + 60_000)
            val r: Res<Any> = if (item.type == "records_clear") relay.deleteRecords(c.token, item.id, clearIds(item.bodyJson)) else {   // 뺀 id 목록은 항목 본문에 있어 재시도도 같다(L79)
                val body = runCatching { Json.parseToJsonElement(item.bodyJson).jsonObject }.getOrNull()
                if (body == null) { Log.w(TAG, "보낼 함 본문이 깨짐 type=${item.type} id=${item.id}"); dao.deleteOutbox(item.id); dao.deleteSentId(item.id); continue }
                relay.send(c.token, envelopeOf(item.id, item.type, body, item.createdAt, item.toDevice))
            }
            when (r) {
                is Res.Ok -> {
                    val out = Applied()
                    val sameRoom = rx.withLock {
                        if (creds.cred !== c) return@withLock false   // 그사이 차 토큰 무효: 옛 방에 간 것이라 지우지 않고 새 방에 다시 보낸다(옛 seq도 쓰지 않는다)
                        room.tx {
                            dao.deleteOutbox(item.id); dao.deleteSentId(item.id)   // L86: 확정 응답이라 결과를 안다
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
                    r.status == 401 -> { dao.retryOutbox(item.id, now + 60_000); return creds.invalidate(c) }   // 등록 성공 신호로 깨어나도 맨 앞이 기다린다(8.6)
                    r.status == 429 -> dao.retryOutbox(item.id, now + (r.retryAfterS?.times(1000)?.coerceAtLeast(1000) ?: backoffMs(item.attempts)))
                    r.status >= 500 -> dao.retryOutbox(item.id, now + backoffMs(item.attempts))
                    else -> {   // 400·403·409·413·422 등: 다시 보내도 같다
                        Log.w(TAG, "보낼 함 버림 type=${item.type} id=${item.id} status=${r.status} code=${r.code}")
                        dao.deleteOutbox(item.id)
                        if (r.status in 400..499) dao.deleteSentId(item.id)   // L86: 4xx는 확정 응답
                    }
                }
                Res.Offline -> dao.retryOutbox(item.id, now + backoffMs(item.attempts))
            }
        }
    }

    private fun wakePumpAt(at: Long) {
        pumpWake?.cancel()
        pumpWake = scope.launch { delay((at - clock()).coerceAtLeast(0)); signal() }
    }
}
