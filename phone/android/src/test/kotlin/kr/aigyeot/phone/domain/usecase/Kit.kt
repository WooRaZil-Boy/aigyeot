package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kr.aigyeot.phone.data.AppSongFileStore
import kr.aigyeot.phone.data.FileCareRepository
import kr.aigyeot.phone.data.FilePhoneStateRepository
import kr.aigyeot.phone.data.inbound
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.CredentialRepository
import kr.aigyeot.phone.domain.repository.InboxPage
import kr.aigyeot.phone.domain.repository.LinkMarks
import kr.aigyeot.phone.domain.repository.Logger
import kr.aigyeot.phone.domain.repository.Mark
import kr.aigyeot.phone.domain.repository.MarkEdit
import kr.aigyeot.phone.domain.repository.NetworkWatcher
import kr.aigyeot.phone.domain.repository.PushTokens
import kr.aigyeot.phone.domain.repository.RelayClient
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.phone.domain.repository.RelaySocket
import kr.aigyeot.phone.domain.repository.SocketHandle
import kr.aigyeot.phone.domain.repository.SocketListener
import kr.aigyeot.phone.domain.state.AppVisibility
import kr.aigyeot.phone.domain.state.LinkSwitch
import kr.aigyeot.phone.domain.state.Notices
import kr.aigyeot.shared.DevicesResponse
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.InboxResponse
import kr.aigyeot.shared.InviteResponse
import kr.aigyeot.shared.PairResponse
import kr.aigyeot.shared.PatchDeviceRequest
import kr.aigyeot.shared.PatchDeviceResponse
import kr.aigyeot.shared.Platform
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.Role
import kr.aigyeot.shared.SendMessageResponse
import kr.aigyeot.shared.UploadSongResponse
import java.io.File
import kotlin.io.path.createTempDirectory

// 유스케이스 시험 도구: 파일 저장소는 진짜(임시 폴더), 중계·prefs·Keystore·FCM·네트워크는 가짜다. 배선은 AppContainer와 같다.

const val NOW = 1_762_300_000_000L
val OWNER = Credentials("c_7F3AK2N9X0Q4M8T1V6B5R3H2CD", "d_5KQ2M8T1V6B5R3H2CD7F3AK2N9", "tok-owner", Role.OWNER)

class FakeRelay : RelayClient, RelaySocket {
    override var clockSkewMs: Long? = null
    private var ticket = 0L
    override fun lastTicket() = ticket

    var pairResult: RelayResult<PairResponse> = RelayResult.Offline
    var devicesResult: RelayResult<DevicesResponse> = RelayResult.Offline
    var patchResult: RelayResult<PatchDeviceResponse> = RelayResult.Ok(200, PatchDeviceResponse(OWNER.deviceId, Role.OWNER, Platform.ANDROID, false, true))
    var deleteResult: RelayResult<Unit> = RelayResult.Ok(204, Unit)
    var inviteResult: RelayResult<InviteResponse> = RelayResult.Offline
    var sendResult: (Envelope) -> RelayResult<SendMessageResponse> = { RelayResult.Ok(201, SendMessageResponse(it.id, 1, 1, it.body)) }
    var inboxResult: (afterSeq: Long) -> RelayResult<InboxResponse> = { RelayResult.Ok(200, InboxResponse(emptyList(), false)) }

    val sent = mutableListOf<Envelope>()
    val inboxAfter = mutableListOf<Long>()
    val acked = mutableListOf<List<String>>()
    val deleted = mutableListOf<String>()
    var listener: SocketListener? = null
    val frames = mutableListOf<String>()

    override suspend fun pair(code: String, pushToken: String?, installId: String) = pairResult.also { ticket++ }
    override suspend fun devices(token: String) = devicesResult.also { ticket++ }
    override suspend fun patchMe(token: String, body: PatchDeviceRequest) = patchResult.also { ticket++ }
    override suspend fun deleteMe(token: String) = deleteResult.also { deleted += token; ticket++ }
    override suspend fun invite(token: String) = inviteResult.also { ticket++ }
    override suspend fun send(token: String, env: Envelope) = sendResult(env).also { sent += env; ticket++ }
    /** 있으면 받은 함 요청이 이것이 끝날 때까지 답하지 않는다(도는 받기를 만든다). */
    var inboxGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** 받은 함은 봉투(계약 DTO)로 정하고 진짜 바꾸기([inbound])를 거쳐 돌려준다. */
    override suspend fun inbox(token: String, afterSeq: Long): RelayResult<InboxPage> {
        inboxAfter += afterSeq
        ticket++
        val answer = inboxResult(afterSeq) // 중계는 요청을 받을 때 답을 정한다. 답이 늦게 올 뿐이다
        inboxGate?.await()
        return when (val r = answer) {
            is RelayResult.Ok -> RelayResult.Ok(r.status, InboxPage(r.value.messages.map { it.inbound }, r.value.more))
            is RelayResult.Err -> r
            RelayResult.Offline -> RelayResult.Offline
            RelayResult.Unreached -> RelayResult.Unreached
        }
    }
    override suspend fun ack(token: String, ids: List<String>): RelayResult<Unit> = RelayResult.Ok(200, Unit).also { acked += ids; ticket++ }
    override suspend fun putSong(token: String, songId: String, file: File): RelayResult<UploadSongResponse> = RelayResult.Ok(201, UploadSongResponse(songId, file.length()))

    override fun open(token: String, listener: SocketListener): SocketHandle {
        this.listener = listener
        return object : SocketHandle {
            override fun send(text: String) = frames.add(text)
            override fun close(code: Int) = Unit
            override fun cancel() = Unit
        }
    }

    override fun read(text: String) = runCatching { ProtocolJson.decodeFromString(Envelope.serializer(), text) }.getOrNull()?.inbound

    override fun ackFrame(ids: List<String>) = "ack:" + ids.joinToString(",")
}

class MemoryMarks(initial: Map<Mark, String> = emptyMap()) : LinkMarks {
    val values = initial.toMutableMap()
    var failCommit = false
    override val installId = "install-test"
    override fun get(mark: Mark) = values[mark]
    override fun flag(mark: Mark) = values[mark] == "true"
    override fun commit(vararg edits: MarkEdit): Boolean = if (failCommit) false.also { write(edits) } else true.also { write(edits) }
    override fun apply(vararg edits: MarkEdit) = write(edits)
    override suspend fun commitInBackground(vararg edits: MarkEdit) = commit(*edits)

    private fun write(edits: Array<out MarkEdit>) {
        for (e in edits) when (e) {
            is MarkEdit.Put -> if (e.value == null) values.remove(e.mark) else values[e.mark] = e.value
            is MarkEdit.Flag -> values[e.mark] = e.on.toString()
            is MarkEdit.Remove -> values.remove(e.mark)
        }
    }
}

class MemoryCredentials(var stored: Credentials?) : CredentialRepository {
    var failSave = false
    override fun loadBlocking() = stored
    override suspend fun load() = Result.success(stored)
    override suspend fun save(c: Credentials?): Boolean = if (failSave) false else true.also { stored = c }
    override suspend fun reset() = true.also { stored = null }
}

class FakeNetwork : NetworkWatcher {
    var watching = false
    private var onAvailable: () -> Unit = {}
    override fun watch(onAvailable: () -> Unit) {
        watching = true
        this.onAvailable = onAvailable
    }

    override fun unwatch() {
        watching = false
    }

    /** 인터넷이 돌아왔다(보는 중일 때만 알린다). */
    fun restore() {
        if (watching) onAvailable()
    }
}

object Quiet : Logger {
    override fun d(msg: String) = Unit
    override fun w(msg: String) = Unit
    override fun e(msg: String, t: Throwable?) = Unit
}

class Kit(val scope: CoroutineScope, val dir: File, creds: Credentials?, marks: Map<Mark, String> = emptyMap(), undoMs: Long = 2_000) {
    var now = NOW
    val clock = Clock { now }
    val relay = FakeRelay()
    val marks = MemoryMarks(marks)
    val credentials = MemoryCredentials(creds)
    val notices = Notices()
    val care = FileCareRepository(File(dir, "care.json"), File(dir, "export"), Quiet, notices::saveFailed)
    val state = FilePhoneStateRepository(
        File(dir, "phone_state.json"), credentials, Quiet, clock,
        migrating = { !care.care.value.migratedFeedings }, onSaveFailed = notices::saveFailed,
        goneCleanupPending = this.marks.get(Mark.GONE_CLEANUP_PENDING),
    )
    init {
        notices.showDrops(state.state.value.unseenDrops) // AppContainer와 같다(U54)
    }

    val link = LinkSwitch(this.marks, notices)
    val visibility = AppVisibility()
    val songs = AppSongFileStore(File(dir, "songs"), File(dir, "cache").apply { mkdirs() }, state)
    val pushTokens = object : PushTokens { override suspend fun token(): String? = null }
    val network = FakeNetwork()
    val wipe = WipeLink(state, care, credentials, songs, notices, link, scope, Quiet)
    val lose = LoseConnection(state, link, wipe, scope)
    val disconnect = Disconnect(state, relay, link, wipe, lose, scope, Quiet)
    val outbox = SendOutbox(state, care, relay, UploadSong(relay, songs), lose, notices, visibility, clock, scope)
    val inbox = SyncInbox(state, relay, lose, notices, songs, clock, scope)
    val push = RegisterPush(state, relay, lose, pushTokens, scope)
    val connection = KeepConnected(state, relay, relay, inbox, outbox, lose, disconnect, push, network, visibility, clock, scope)
    val reconcile = ReconcileCareSummary(state, care, outbox, notices, clock)
    /** 띠 시간(앱은 화면 읽기·시스템 권장 시간으로 정한다, U87). 시험이 띠를 띄우기 전후에 바꿔 볼 수 있다. */
    var undoBand = undoMs
    val recordCare = RecordCare(care, state, reconcile, songs, notices, clock, scope, Quiet, allowDebugSeed = true, undoMs = { undoBand })
    val clearRecords = ClearRecords(care, state, recordCare, notices, songs, connection, outbox, clock, scope)
    val pair = Pair(state, care, credentials, relay, link, disconnect, lose, this.marks, pushTokens, songs, recordCare, notices, clock, scope, Quiet)
    val recordSong = RecordSong(state, songs, clearRecords, outbox, notices, clock, scope)
    val selectSong = SelectSong(state, outbox, clock, scope)
    val removeSong = RemoveSong(state, songs, outbox, clock, scope)
    val lifecycle = AppLifecycle(state, care, songs, link, this.marks, visibility, connection, outbox, recordCare, disconnect, { }, scope)

    /** 조건이 참이 될 때까지 기다린다(앱 범위 작업·IO 저장이 끝나기를). */
    suspend fun until(what: String = "조건", cond: () -> Boolean) {
        runCatching { withTimeout(3_000) { while (!cond()) delay(5) } }.getOrElse { throw AssertionError("기다려도 $what 아님") }
    }

    /** 앱 범위 작업이 돌 틈을 준다. */
    suspend fun settle() = delay(100)

    fun envelope(type: String, body: JsonObject, id: String, seq: Long, fromCar: Boolean = true) = Envelope(
        v = 1, id = id, seq = seq, type = type, car = OWNER.carId, from = if (fromCar) kr.aigyeot.shared.Sender(Role.CAR, null) else null,
        ts = kr.aigyeot.phone.domain.model.rfc3339(now), body = body,
    )
}

/** 앱 범위(시험의 runBlocking 위)에서 Kit을 만들고, 끝나면 범위를 닫고 임시 폴더를 지운다. `prepare`는 Kit을 만들기 전에 폴더에 파일을 둔다. */
fun kitTest(
    creds: Credentials? = OWNER,
    marks: Map<Mark, String> = emptyMap(),
    prepare: (File) -> Unit = {},
    /** 되돌리기 띠 시간(앱은 5초). 시험은 기본 2초, 띠가 끝나는 것을 보는 시험은 더 줄이거나 늘린다. */
    undoMs: Long = 2_000,
    block: suspend Kit.() -> Unit,
) = runBlocking {
    val job = SupervisorJob(coroutineContext[Job])
    val dir = createTempDirectory().toFile()
    try {
        prepare(dir)
        Kit(CoroutineScope(coroutineContext + job), dir, creds, marks, undoMs).block()
    } finally {
        job.cancel()
        dir.deleteRecursively()
    }
}
