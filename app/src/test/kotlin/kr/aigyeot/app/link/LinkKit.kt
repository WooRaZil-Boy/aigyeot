package kr.aigyeot.app.link

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kr.aigyeot.app.common.rfc3339
import kr.aigyeot.app.common.ulid
import kr.aigyeot.app.exec.LoudnessResult
import kr.aigyeot.app.link.db.OutboxEntity
import kr.aigyeot.app.store.db.AI_NOTICE_VERSION
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.app.store.db.PhoneLinkEntity
import kr.aigyeot.app.store.db.SongEntity
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.fresh
import kr.aigyeot.shared.DevicesResponse
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.InboxResponse
import kr.aigyeot.shared.LlmRequest
import kr.aigyeot.shared.LlmResponse
import kr.aigyeot.shared.PairCodeResponse
import kr.aigyeot.shared.RegisterCarResponse
import kr.aigyeot.shared.Role
import kr.aigyeot.shared.SendMessageResponse
import kr.aigyeot.shared.Sender

// link 시험 도구: Robolectric + Room in-memory(AppDatabase), 가짜 중계, 가짜 토큰 감싸기, 가짜 시계.

const val T0 = 1_762_300_000_000L // 2025-11-05 08:26:40Z 근처
const val PHONE = "d_5KQ2M8T1V6B5R3H2CD7F3AK2N9"
const val GUARD = "d_8HN1X0Q4M8T1V6B5R3H2CD7F3A"
const val SA = "01JC90A0Q4M8T1V6B5R3H2CD7A"
const val SB = "01JC90A0Q4M8T1V6B5R3H2CD7B"
const val SC = "01JC90A0Q4M8T1V6B5R3H2CD7C"
const val SD = "01JC90A0Q4M8T1V6B5R3H2CD7D"
const val SE = "01JC90A0Q4M8T1V6B5R3H2CD7E"

object FakeCipher : TokenCipher {
    override fun seal(plain: String) = "sealed:$plain"
    override fun open(sealed: String) = sealed.removePrefix("sealed:").also { require(sealed.startsWith("sealed:")) }
}

class FakeRelay : Relay {
    var seq = 1000L
    var registers = 0
    var register: Res<RegisterCarResponse> = Res.Ok(201, RegisterCarResponse("c_TESTCAR", "d_TESTCAR", "at1.c_TESTCAR.d_TESTCAR.EXAMPLE"))
    val sent = mutableListOf<Envelope>()
    var sendResult: suspend (Envelope) -> Res<SendMessageResponse> = { Res.Ok(201, SendMessageResponse(it.id, ++seq, 1, it.body)) }
    val inboxPages = ArrayDeque<Res<InboxResponse>>()
    val inboxCalls = mutableListOf<Long>()
    var socketCallsAtFirstInbox = -1
    val acked = mutableListOf<String>()
    var devicesRes: Res<DevicesResponse> = Res.Offline
    var devicesCalls = 0
    var onDevices: suspend () -> Unit = {}
    var pairCodeRes: Res<PairCodeResponse> = Res.Offline
    var onPairCode: suspend () -> Unit = {}
    var deleteGate: CompletableDeferred<Unit>? = null
    var onDeleted: () -> Unit = {}
    val pairLog = CopyOnWriteArrayList<String>() // 연결 코드 받기·지우기 차례
    var deleteDeviceRes: Res<Unit> = Res.Ok(204, Unit)
    val deletedDevices = mutableListOf<String>()
    var recordsRes: Res<Unit> = Res.Ok(204, Unit)
    var recordsCalls = 0
    val recordsIds = mutableListOf<String>()
    val recordsDropIds = mutableListOf<List<String>>()
    var songRes: (String, File) -> Res<Long> = { _, f ->
        f.writeBytes(ByteArray(100))
        Res.Ok(200, 100)
    }
    val songCalls = mutableListOf<String>()
    var onInbox: suspend () -> Unit = {} // 받은 함 요청을 보낸 직후(응답을 돌려주기 전)에 시험이 끼어든다
    var llmRes: suspend (LlmRequest) -> Res<LlmResponse> = { Res.Offline }
    val llmCalls = mutableListOf<LlmRequest>()

    override suspend fun registerCar() = register.also { registers++ }
    override suspend fun pairCode(token: String): Res<PairCodeResponse> {
        pairLog += "code"
        onPairCode()
        return pairCodeRes
    }
    override suspend fun deletePairCode(token: String): Res<Unit> {
        pairLog += "delete"
        deleteGate?.await()
        pairLog += "deleted"
        onDeleted()
        return Res.Ok(204, Unit)
    }
    override suspend fun devices(token: String): Res<DevicesResponse> {
        devicesCalls++
        onDevices()
        return devicesRes
    }
    override suspend fun deleteDevice(token: String, deviceId: String) = deleteDeviceRes.also { deletedDevices += deviceId }
    override suspend fun send(token: String, env: Envelope) = sendResult(env).also { sent += env }
    override suspend fun inbox(token: String, afterSeq: Long): Res<InboxResponse> {
        val page = inboxPages.removeFirstOrNull().also {
            if (inboxCalls.isEmpty()) socketCallsAtFirstInbox = socketCalls
            inboxCalls += afterSeq
        }
        onInbox() // 서버가 응답을 만든 뒤 늦게 도착하는 것을 흉내 낸다
        return page ?: Res.Ok(200, InboxResponse(emptyList(), false))
    }
    override suspend fun ack(token: String, ids: List<String>): Res<Unit> = Res.Ok(204, Unit).also { acked.addAll(ids) }
    override suspend fun song(token: String, songId: String, dest: File, maxBytes: Long) = songRes(songId, dest).also { songCalls += songId }
    override suspend fun deleteRecords(token: String, id: String, dropIds: List<String>) = recordsRes.also {
        recordsCalls++
        recordsIds += id
        recordsDropIds +=
            dropIds
    }
    override suspend fun llm(token: String, req: LlmRequest) = llmRes(req).also { llmCalls += req }

    // 기본: 붙자마자 네트워크 오류로 닫힌다. 시험이 바꾸면 열기·프레임·보낸 글자를 정한다
    val socketSent = CopyOnWriteArrayList<String>()
    var socketCalls = 0
    var onSocket: (SocketEvents) -> Unit = { it.onClosed(-1) }
    override fun socket(token: String, events: SocketEvents): RelaySocket {
        socketCalls++
        onSocket(events)
        return object : RelaySocket {
            override fun send(text: String) = socketSent.add(text)
            override fun cancel() = Unit
        }
    }
}

abstract class LinkKit {
    val ctx: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    val dao = db.linkDao()
    var now = T0
    val events = CopyOnWriteArrayList<Event>()
    val relay = FakeRelay()
    private val stateFlow = MutableStateFlow<EngineState?>(EngineState.fresh(1, null, emptyList(), emptyList(), null, 0))
    var engineState: EngineState? get() = stateFlow.value
        set(v) {
            stateFlow.value = v
        } // 호스트 복원 뒤의 상태. 복원 전은 null로 둔다
    var probeResult: SongProbe? = SongProbe(94.5, true)
    var copyResult: suspend (File, File) -> LoudnessResult = { i, o ->
        i.copyTo(o, overwrite = true)
        LoudnessResult(true)
    }

    /** 켜면 새로 보내는 작업을 붙잡아 두었다가 [release]에서 돌린다(시작 전 취소 시험). */
    class HoldDispatcher : CoroutineDispatcher() {
        @Volatile var holding = false
        val held = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (holding) held.add(block) else Dispatchers.IO.dispatch(context, block)
        }
        fun release() {
            holding = false
            while (true) Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, held.poll() ?: break)
        }
    }
    val hold = HoldDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + hold)
    open val qaWaitMs = 3_000L
    var onPost: (Event) -> Unit = {} // 사건이 나간 직후(받기 잠금 안)에 시험이 끼어든다
    val link by lazy {
        Link(
            ctx, db, { now }, {
                events += it
                onPost(it)
            }, { engineState },
            relay, FakeCipher, { probeResult }, { i, o -> copyResult(i, o) }, scope, qaWaitMs, stateReady = { stateFlow.first { it != null } }
        )
    }

    @AfterTest fun closeKit() {
        scope.cancel()
        db.close()
    }

    fun env(type: String, seq: Long?, body: String, from: Sender? = Sender(Role.OWNER, PHONE), id: String = ulid(now), v: Int = 1) =
        Envelope(v, id, seq, type, "c_TESTCAR", null, from, rfc3339(now), Json.parseToJsonElement(body).jsonObject)

    fun relayEnv(type: String, seq: Long, body: String) = env(type, seq, body, Sender(Role.RELAY, null))

    fun profile(lastFeeding: Long? = null, interval: Int? = 180, nickname: String = "지우", noticeVersion: Int = AI_NOTICE_VERSION) =
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO child_profile (id, nickname, birth_ym, carseat, feeding_interval_min, last_feeding_start, methods, has_sibling, " +
                "front_fallback_allowed, consent_at, ai_notice_version) VALUES ('p', ?, '2026-03', 'LEFT', ?, ?, '[]', 0, 0, 0, ?)",
            arrayOf<Any?>(nickname, interval, lastFeeding, noticeVersion)
        )

    fun lastFeeding(): Long? = dao.profile()?.lastFeedingStart

    fun phone(id: String = PHONE, role: String = "owner", alerts: Boolean = false) = dao.putPhoneLink(PhoneLinkEntity(id, "android", role, T0, null, alerts))

    fun song(id: String, status: String, added: Long?, last: Long = added ?: 0, name: String? = "곡$id") =
        dao.putSong(SongEntity(id, name, null, "AAC", 60.0, null, if (status == ACTIVE) "/x/$id.m4a" else null, status, null, added, last))

    fun outbox(): List<OutboxEntity> = dao.outbox()
    fun outbox(type: String) = outbox().filter { it.type == type }
    fun OutboxEntity.body(): JsonObject = Json.parseToJsonElement(bodyJson).jsonObject
    fun JsonObject.str(k: String) = this[k]?.let { if (it is JsonNull) null else (it as JsonPrimitive).content }
}
