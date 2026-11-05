package kr.aigyeot.phone

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kr.aigyeot.phone.core.OutboxItem
import kr.aigyeot.phone.core.PhoneState
import kr.aigyeot.phone.core.SendResult
import kr.aigyeot.phone.core.StoredAlert
import kr.aigyeot.phone.core.CareEvent
import kr.aigyeot.phone.core.CareFile
import kr.aigyeot.phone.core.CareKind
import kr.aigyeot.phone.core.FeedSide
import kr.aigyeot.phone.core.FeedTrigger
import kr.aigyeot.phone.core.addSong
import kr.aigyeot.phone.core.cleared
import kr.aigyeot.phone.core.delete
import kr.aigyeot.phone.core.editOngoing
import kr.aigyeot.phone.core.endNow
import kr.aigyeot.phone.core.endUnknown
import kr.aigyeot.phone.core.exportFileName
import kr.aigyeot.phone.core.toCsv
import kr.aigyeot.phone.core.markedMigratedFeedings
import kr.aigyeot.phone.core.restartNotice
import kr.aigyeot.phone.core.migrated
import kr.aigyeot.phone.core.purged
import kr.aigyeot.phone.core.reconciledFeeding
import kr.aigyeot.phone.core.start
import kr.aigyeot.phone.core.undoDelete
import kr.aigyeot.phone.core.upsert
import kr.aigyeot.phone.core.afterSend
import kr.aigyeot.phone.core.backoffMs
import kr.aigyeot.phone.core.isPairLocked
import kr.aigyeot.phone.core.pairLockUntil
import kr.aigyeot.phone.core.clearedRecords
import kr.aigyeot.phone.core.dropNotice
import kr.aigyeot.phone.core.envelope
import kr.aigyeot.phone.core.mergeDevices
import kr.aigyeot.phone.core.frontExpired
import kr.aigyeot.phone.core.nextToSend
import kr.aigyeot.phone.core.onAppOpen
import kr.aigyeot.phone.core.pruned
import kr.aigyeot.phone.core.receive
import kr.aigyeot.phone.core.removeSong
import kr.aigyeot.phone.core.selectSong
import kr.aigyeot.phone.core.shouldRequestState
import kr.aigyeot.phone.core.isUlid
import kr.aigyeot.phone.core.songFiles
import kr.aigyeot.phone.core.songId
import kr.aigyeot.phone.core.stateRequestDone
import kr.aigyeot.phone.core.ulid
import kr.aigyeot.phone.data.CareStore
import kr.aigyeot.phone.data.Credentials
import kr.aigyeot.phone.data.RelayClient
import kr.aigyeot.phone.data.RelayClient.Res
import kr.aigyeot.phone.data.SecretStore
import kr.aigyeot.phone.data.StateStore
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.InviteResponse
import kr.aigyeot.shared.PatchDeviceRequest
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.PushTokenPatch
import kr.aigyeot.shared.Role
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * 앱 전체에서 하나. 상태 저장, 중계 HTTPS·WebSocket, 보낼 함, 푸시 토큰을 맡는다.
 * 폰은 앱이 화면 앞에 있을 때만 WebSocket으로 붙는다(계약 2장).
 *
 * 연결 세대: 모든 비동기 응답은 보낼 때의 자격([Credentials])을 들고 다니고, 적용하거나 401을 처리하기 전에
 * 아직 같은 자격인지 본다. 끊고 새 차에 붙은 뒤 이전 연결의 늦은 401이 새 연결을 지우지 않게 한다(계약 3.1, 9장).
 */
class AppModel(private val app: Application) {
    data class Ui(
        val online: Boolean = true,
        val socket: Boolean = false,
        /** 앱 안 배너(5.6). 푸시만 먼저 왔으면 본문 없이 글만 있다. */
        val banner: String? = null,
        val bannerAlertId: String? = null,
        /** 401·닫기 4001: "연결이 끊겼어요"(9장). */
        val gone: Boolean = false,
        val clockSkewMin: Long? = null,
        /** 버려진 노래 삭제·선택 같은 영구 실패 안내. 닫을 때까지 보인다. */
        val notice: String? = null,
        /** 돌봄 기록 알림 띠("앞 수유를 끝냈어요", 7장). 닫을 때까지 보인다. */
        val careNotice: String? = null,
        /** 방금 지운 손 기록 id. 탭 화면이 "지웠어요 · 되돌리기"를 5초 보인다(7장). */
        val undoCareId: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = StateStore(File(app.filesDir, "phone_state.json"))
    private val secrets = SecretStore(app)
    private val relay = RelayClient(BuildConfig.RELAY_URL, BuildConfig.VERSION_NAME)
    private val lock = Mutex()

    /**
     * 연결 전환 잠금. 토큰·상태·파일·소켓을 함께 바꾸는 것(연결 마침, 끊기·401 정리, 차 바꾸기)을 줄 세운다.
     * 잡는 순서는 transitionLock → [lock]·fileLock이다. 안에서 이것을 다시 잡지 않는다(재진입 안 됨).
     */
    private val transitionLock = Mutex()
    private val kick = Channel<Unit>(Channel.CONFLATED)

    val songsDir = File(app.filesDir, "songs").apply { mkdirs() }

    private val _creds = MutableStateFlow(secrets.load())
    val creds: StateFlow<Credentials?> = _creds

    // 토큰은 썼는데 상태 파일을 바꾸기 전에 앱이 죽었으면 다른 차의 상태다. 그 차의 빈 상태로 시작한다.
    // 토큰은 있는데 상태가 그 차 것이 아니면(읽기 실패로 빈 상태, 상태 파일을 바꾸기 전에 죽음) 그 차의 빈 상태로 시작하고
    // 상태 요청 필요를 켠다. 기기 목록 조회가 성공하면 state_request를 보낸다.
    private val loaded = store.load()
    private val recovered = _creds.value?.let { c ->
        PhoneState.recoverIfNeeded(loaded, c.carId, c.deviceId, c.role, System.currentTimeMillis())
    }
    private val _state = MutableStateFlow(recovered ?: loaded)
    val state: StateFlow<PhoneState> = _state
    val ui = MutableStateFlow(Ui())

    // 돌봄 기록(phone-care-plan 6장). 연결과 상관없이 쓴다(U7). 끊어도 지우지 않는다(U6·R9).
    private val careStore = CareStore(File(app.filesDir, "care.json"))
    private val _care = MutableStateFlow(careStore.load())
    val care: StateFlow<CareFile> = _care
    private val careLock = Mutex()

    /** 링크·QR로 받은 코드. 확인 화면을 거친 뒤에만 쓴다(G17). */
    val pendingCode = MutableStateFlow<String?>(null)

    /** 시스템 알림을 눌러 연 인계 알림 id. */
    val openAlert = MutableStateFlow<String?>(null)

    private val installId: String by lazy {
        val p = app.getSharedPreferences("install", Context.MODE_PRIVATE)
        p.getString("id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
    }

    private fun now() = System.currentTimeMillis()

    private fun isCurrent(c: Credentials) = creds.value == c

    private fun saved(s: PhoneState): Boolean = runCatching { store.save(s) }
        .onFailure { Log.e(TAG, "상태 저장 실패", it) }
        .isSuccess

    /**
     * 상태를 바꾸고 저장한다. 한 번에 하나씩. 저장이 확인됐을 때만 메모리를 바꾸고 참을 돌려준다.
     * 받은 메시지는 이것이 참일 때만 ack한다(계약 1.4 'ack는 처리를 저장한 뒤').
     * `c`가 지금 자격이 아니면(이전 연결의 응답) 아무것도 하지 않고 거짓.
     */
    private suspend fun commit(c: Credentials, f: (PhoneState) -> PhoneState): Boolean = lock.withLock {
        if (!isCurrent(c)) return@withLock false
        val next = f(_state.value)
        if (next == _state.value) return@withLock true
        if (!withContext(Dispatchers.IO) { saved(next) }) {
            ui.update { it.copy(notice = "저장하지 못했어요. 저장 공간을 확인해 주세요.") }
            return@withLock false
        }
        _state.value = next
        true
    }

    /** 자격과 상태를 한 잠금 안에서 함께 바꾼다(연결 세대가 바뀌는 곳). `onlyIf`가 있으면 그 자격일 때만. */
    private suspend fun switchTo(next: Credentials?, s: PhoneState, onlyIf: Credentials? = null): Boolean = lock.withLock {
        if (onlyIf != null && !isCurrent(onlyIf)) return@withLock false
        val ok = withContext(Dispatchers.IO) { saved(s) }
        // 끊을 때는 저장이 실패해도 자격은 잊는다. 연결할 때는 저장이 돼야 바꾼다.
        if (!ok && next != null) return@withLock false
        _state.value = s
        _creds.value = next
        true
    }

    private fun setOnline(c: Credentials, online: Boolean) {
        if (isCurrent(c)) ui.update { it.copy(online = online) }
    }

    /**
     * 녹음 파일 잠금. 새 녹음의 이동·상태 커밋과 파일 정리를 줄 세워, 정리가 읽은 목록보다 늦게 저장된 새 녹음을
     * 지우지 않게 한다(7.4·7.5). 이 잠금 안에서 [lock]을 잡을 수는 있지만 반대 순서로는 잡지 않는다.
     */
    private val fileLock = Mutex()

    /**
     * 목록에 없는 녹음 파일을 지운다. 상태를 커밋한 뒤에만 부른다(앱 시작, 곡 지움, 기록 지움, 연결 바뀜).
     * 읽지 못한 상태 파일을 옮겨 둔 것이 있으면 지우지 않는다. 빈 상태로 시작해 그 파일이 아는 녹음을 모르기 때문이다.
     * '이 휴대폰의 기록 지우기'가 옮겨 둔 파일을 지운 뒤에야 다시 정리한다.
     */
    private suspend fun cleanSongFiles() = fileLock.withLock {
        withContext(Dispatchers.IO) {
            if (store.backup.exists() || store.unsafe) return@withContext
            val keep = state.value.songFiles
            songsDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        }
    }

    init {
        scope.launch {
            // 복구한 상태(요청 필요 표시 포함)를 바로 저장한다. 백업하지 못했으면 저장은 거부된다(다음 실행이 다시 복구한다).
            if (recovered != null) lock.withLock { if (_state.value === recovered) withContext(Dispatchers.IO) { saved(recovered) } }
            cleanSongFiles()
            // 앱을 열 때: 30일 지난 지운 기록 정리(R8), 지금 수유 기록 한 번 옮기기(R11). 그 뒤 차로 보낼지 본다(R6).
            // U22: 옮기기 전에 state.json의 보낸 표시를 먼저 채운다. 저장하지 못했으면 이번에는 옮기지 않는다(다음 실행이 다시 한다).
            val marked = _care.value.migratedFeedings || (creds.value?.let { c -> commit(c) { it.markedMigratedFeedings() } } ?: true)
            saveCare { f -> f.purged(now()).let { if (marked) it.migrated(state.value.feedings, zone()) else it } }
            reconcileFeeding(FeedTrigger.APP_START)
        }
        // 진행 중 기록이 바뀔 때만 잠금 화면 알림을 고친다(R15: 매초 갱신 없음).
        scope.launch { care.collect { CareNotify.update(app, it) } }
    }

    // ---------------------------------------------------------------- 돌봄 기록(phone-care-plan 6장)

    private fun zone() = java.time.ZoneId.systemDefault().id

    /** 돌봄 기록을 바꾸고 저장한다. 저장이 확인됐을 때만 메모리를 바꾼다. `f`가 null이면(규칙 위반) 바꾸지 않는다. */
    private suspend fun saveCare(f: (CareFile) -> CareFile?): Boolean = careLock.withLock {
        val next = f(_care.value) ?: return@withLock false
        if (next == _care.value) return@withLock true
        val ok = withContext(Dispatchers.IO) {
            runCatching { careStore.save(next) }.onFailure { Log.e(TAG, "돌봄 기록 저장 실패", it) }.isSuccess
        }
        if (!ok) {
            ui.update { it.copy(notice = "저장하지 못했어요. 저장 공간을 확인해 주세요.") }
            return@withLock false
        }
        _care.value = next
        true
    }

    /** 사용자가 돌봄 기록을 바꿨다: 저장한 뒤 차로 보낼지 본다(R6 '돌봄 기록 저장·고치기 뒤'). */
    private suspend fun careCommit(f: (CareFile) -> CareFile?): Boolean {
        if (!saveCare(f)) return false
        reconcileFeeding(FeedTrigger.CARE_SAVED)
        return true
    }

    /**
     * R5: 차로 보낼 수유가 있으면 보낼 함에 넣고 보낸 표시를 같은 state.json 저장에서 올린다. 저장에 실패하면 아무것도 바뀌지 않는다.
     * 부르는 곳은 정해진 네 계기뿐이다(R6, [FeedTrigger]). 연결이 없거나 owner가 아니면 하지 않는다.
     */
    private fun reconcileFeeding(why: FeedTrigger) {
        scope.launch {
            val c = creds.value ?: return@launch
            if (commit(c) { it.reconciledFeeding(care.value.events, now()) } && state.value.outbox.isNotEmpty()) pumpOutbox()
            Log.d(TAG, "수유 보내기 확인: $why")
        }
    }

    /** 빠른 버튼·타이머 시작(R1·R2·U21). 앞 기록을 닫았으면 띠를 띄운다. */
    fun startCare(kind: CareKind, side: FeedSide? = null) {
        scope.launch {
            var closed: CareEvent? = null
            val ok = careCommit { f -> f.start(kind, now(), zone(), side).also { closed = it.closedPrevious }.file }
            closed?.takeIf { ok }?.let { c -> ui.update { it.copy(careNotice = restartNotice(c)) } }
        }
    }

    /** 타이머·잠금 화면 '끝내기'. 최대 길이를 넘었으면 끝 모름으로 닫힌다(U14). */
    fun endCare(id: String) {
        endCareJob(id)
    }

    /** 알림 수신기가 저장이 끝날 때까지 기다리게 작업을 돌려준다. */
    fun endCareJob(id: String): Job = scope.launch { careCommit { it.endNow(id, now()) } }

    /** 타이머 '끝 시각 모름으로 닫기'(U14). */
    fun endCareUnknown(id: String) {
        scope.launch { careCommit { it.endUnknown(id, now()) } }
    }

    /** 빠른 기록·고치기 저장. 규칙(R3)에 맞지 않으면 거짓(화면이 먼저 [kr.aigyeot.phone.core.problem]으로 안내한다). */
    suspend fun saveCareEvent(e: CareEvent): Boolean = scope.async { careCommit { it.upsert(e, now()) } }.await()

    /** 타이머: 저장 잠금 안에서 지금 진행 중인 그 기록에만 바꿀 것을 얹는다. */
    suspend fun editOngoing(id: String, f: (CareEvent) -> CareEvent): Boolean = scope.async { careCommit { it.editOngoing(id, now(), f) } }.await()

    fun deleteCare(id: String) {
        scope.launch { if (careCommit { it.delete(id, now()) }) ui.update { it.copy(undoCareId = id) } }
    }

    fun clearUndoCare() = ui.update { it.copy(undoCareId = null) }

    /** 내 기록 내보내기(C7, U29): 캐시 export/에 CSV를 쓰고 그 파일을 돌려준다. 실패하면 null. */
    suspend fun exportCare(): File? = withContext(Dispatchers.IO) {
        careLock.withLock { exportCareLocked() }
    }

    // 기록 지우기와 같은 잠금 안에서: 지운 뒤에 지운 기록이 담긴 사본이 다시 생기지 않는다.
    private fun exportCareLocked(): File? = runCatching {
        val dir = File(app.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // 지난 내보내기 파일은 남기지 않는다
        File(dir, exportFileName(now(), java.time.ZoneId.systemDefault())).apply {
            writeText(care.value.toCsv(java.time.ZoneId.systemDefault()), Charsets.UTF_8)
        }
    }.onFailure { Log.e(TAG, "내보내기 실패", it) }.getOrNull()

    fun undoDeleteCare(id: String) {
        scope.launch { careCommit { it.undoDelete(id) } }
    }

    fun setChildNickname(name: String?) {
        scope.launch { saveCare { it.copy(childNickname = name?.trim()?.takeIf(String::isNotEmpty)) } }
    }

    fun setSleepDiaper(on: Boolean) {
        scope.launch { saveCare { it.copy(sleepDiaperEnabled = on) } }
    }

    fun clearCareNotice() = ui.update { it.copy(careNotice = null) }

    private val prefs get() = app.getSharedPreferences("install", Context.MODE_PRIVATE)

    /** 첫 화면에서 '먼저 기록만 쓸게요'를 골랐거나 끊긴 뒤 기록만 쓰기로 했다(U7·U25). 다음 실행부터 바로 탭으로 간다. */
    val recordsOnly: Boolean get() = prefs.getBoolean("records_only", false)

    fun chooseRecordsOnly() = prefs.edit().putBoolean("records_only", true).apply()

    /** 알림 권한은 처음 타이머를 시작할 때 한 번만 묻는다(9장). 처음이면 참을 돌려주고 물었다고 적는다. */
    fun takeNotificationAsk(): Boolean {
        if (prefs.getBoolean("asked_notifications", false)) return false
        prefs.edit().putBoolean("asked_notifications", true).apply()
        return true
    }

    /** 권한을 받은 뒤 진행 중 알림을 다시 그린다. */
    fun refreshCareNotice() = CareNotify.update(app, care.value)

    /** 설정에서 직접 끊은 뒤(U25 b): 탭(연결 안 됨)의 오늘로 가고 띠를 보인다. */
    fun afterUserDisconnect() {
        chooseRecordsOnly()
        ui.update { it.copy(careNotice = "연결을 끊었어요. 돌봄 기록은 이 휴대폰에 남아 있어요") }
    }

    // ---------------------------------------------------------------- 앞·뒤

    private var foreground: Job? = null
    private var socket: WebSocket? = null
    private var watchingNetwork = false
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            kick.trySend(Unit) // 기다림을 건너뛰고 바로 다시 붙고 보낼 함을 비운다
            pumpOutbox()
        }
    }

    /** 화면이 앞에 있는지(액티비티 onStart~onStop). 폰은 앞에 있을 때만 WebSocket으로 붙는다(2장). */
    @Volatile private var visible = false

    /** 생명주기: 화면이 앞에 왔다. */
    fun onForeground() {
        visible = true
        startConnection()
        reconcileFeeding(FeedTrigger.FOREGROUND)
        scope.launch { saveCare { it.purged(now()) } } // R8 '앱을 열 때': 프로세스가 살아 있는 채 다시 열어도 정리한다
        refreshCareNotice() // 시스템 설정에서 알림을 켜고 돌아왔을 수 있다(기록은 그대로라 흐름으로는 다시 뜨지 않는다)
    }

    /** 생명주기: 화면이 뒤로 갔다. 끊고 멈춘다. */
    fun onBackground() {
        visible = false
        wake?.cancel()
        stopConnection()
    }

    /** 앞에 있을 때만 연결 루프를 연다. 모델 범위 작업(연결 마침 등)이 뒤에서 불러도 열지 않는다. */
    private fun startConnection() {
        if (!visible || foreground?.isActive == true) return
        if (!watchingNetwork) {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
            watchingNetwork = true
        }
        foreground = scope.launch { connectLoop() }
    }

    private fun stopConnection() {
        if (watchingNetwork) {
            app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback)
            watchingNetwork = false
        }
        foreground?.cancel()
        socket?.close(1000, null)
        socket = null
        ui.update { it.copy(socket = false) }
    }

    /** 한 자격(연결 세대)으로만 돈다. 자격이 바뀌면 stopConnection의 취소로 끝난다. */
    private suspend fun connectLoop() {
        val c = creds.value ?: return
        // WebSocket·받은 함을 처리하기 전에 24시간을 본다(7.4).
        if (!commit(c) { it.pruned(now()).onAppOpen(now()) }) return
        registerPush(c, fcmToken())
        var attempts = 0
        while (isCurrent(c)) {
            // 붙을 때마다(통신이 돌아올 때 포함) 기기 목록을 다시 받고 상태 요청이 필요한지 다시 본다(7.4).
            if (!refreshDevices(c)) return
            val code = runSocket(c) { attempts = 0 }
            when (code) {
                4001, 401 -> return lostConnection(c)
                4002 -> return // 같은 기기의 새 연결로 바뀜
            }
            // 붙지 못했거나 끊긴 사이 온 것은 HTTPS로 받는다.
            if (syncInbox(c) == Sync.LOST) return
            pumpOutbox()
            val wait = if (code == 4008 || code == 429) 60_000 else backoffMs(attempts++)
            kick.tryReceive() // 연결 중 쌓인 신호로 간격 없이 재접속하지 않는다
            withTimeoutOrNull(wait) { kick.receive() }
        }
    }

    /** owner: 기기 목록(차 타일) → 필요하면 state_request(7.4). 401이거나 세대가 바뀌었으면 거짓. */
    private suspend fun refreshDevices(c: Credentials): Boolean {
        val fetched = fetchDevices(c)
        if (fetched == true) requestState(c)
        return if (fetched == null) false else isCurrent(c)
    }

    /**
     * 기기 목록만 받아 차 타일을 맞춘다(state_request 같은 다른 일은 하지 않는다). 받아 저장했으면 참,
     * 못 받았으면(오프라인·owner 아님·저장 실패) 거짓, 401이면 연결을 끝내고 null을 돌려 부른 쪽이 멈추게 한다.
     */
    private suspend fun fetchDevices(c: Credentials): Boolean? {
        if (c.role != Role.OWNER) return false
        var ok = false
        when (val r = relay.devices(c.token)) {
            is Res.Ok -> {
                if (!commit(c) { it.mergeDevices(r.value.snapshotSeq, r.value.devices, now()) }) return false
                setOnline(c, true)
                ok = true
            }
            is Res.Err -> if (r.status == 401) return null.also { lostConnection(c) }
            Res.Offline -> setOnline(c, false)
        }
        ui.update { it.copy(clockSkewMin = relay.clockSkewMs?.let { ms -> ms / 60_000 }) }
        return ok
    }

    /** 10분 제한을 보고 요청 시각을 저장하는 것을 한 잠금 안에서 한다. 예약에 성공한 호출만 보낸다. */
    private suspend fun requestState(c: Credentials) {
        var reserved = false
        var gen = 0
        val ok = commit(c) { s ->
            if (!s.shouldRequestState(now())) return@commit s
            reserved = true
            gen = s.stateNeedGen
            s.copy(lastStateRequestAt = now())
        }
        if (!ok || !reserved) return
        when (val r = relay.send(c.token, envelope("state_request", buildJsonObject {}, now()))) {
            is Res.Ok -> if (r.status == 201) commit(c) { it.stateRequestDone(gen) }
            is Res.Err -> if (r.status == 401) lostConnection(c)
            Res.Offline -> Unit // 표시를 둔 채 다음 연결 때 다시 판단한다(보낼 함에 넣지 않는다)
        }
    }

    /** 받은 함 동기화 결과. DONE은 모든 쪽을 받아 저장까지 끝난 것뿐이다. */
    private enum class Sync { DONE, FAILED, LOST }

    /** 받은 함을 모두 받아 저장한 뒤 ack한다(배너 없이, 5.6). */
    private suspend fun syncInbox(c: Credentials): Sync {
        var after = 0L
        while (true) {
            when (val r = relay.inbox(c.token, after)) {
                is Res.Ok -> {
                    if (!isCurrent(c)) return Sync.LOST
                    setOnline(c, true)
                    val got = r.value.messages
                    // 한 쪽을 모두 적용해 한 번만 저장한다. 저장이 확인돼야 ack하고, 실패하면 하나도 ack하지 않는다(중계가 다시 준다).
                    val stored = if (storeAll(c, got)) got else emptyList()
                    if (stored.isNotEmpty()) {
                        val ack = relay.ack(c.token, stored.map { it.id })
                        if (ack is Res.Err && ack.status == 401) return Sync.LOST.also { lostConnection(c) }
                    }
                    if (!isCurrent(c)) return Sync.LOST
                    if (stored.size < got.size) return Sync.FAILED
                    if (got.isEmpty() || !r.value.more) return Sync.DONE
                    after = got.last().seq ?: after
                }
                is Res.Err -> return if (r.status == 401) Sync.LOST.also { lostConnection(c) } else Sync.FAILED
                Res.Offline -> {
                    setOnline(c, false)
                    return Sync.FAILED
                }
            }
        }
    }

    /**
     * WebSocket 하나를 열어 닫힐 때까지 돈다. 닫기 코드(실패면 HTTP 코드 또는 -1)를 돌려준다.
     * 붙으면 먼저 받은 함을 HTTPS로 다 받고, 그동안 소켓으로 온 프레임(밀린 메시지)은 모아 두었다가 배너 없이 처리한다.
     * 받은 함 동기화가 끝난 뒤 온 프레임만 실시간으로 본다(5.6 '앱을 열며 받은 함에서 꺼낸 것: 배너 없이').
     */
    private suspend fun runSocket(c: Credentials, onOpen: () -> Unit): Int {
        val frames = Channel<Pair<Int, String>>(Channel.UNLIMITED)
        val received = AtomicInteger(0)
        var closeCode = -1
        val ws = relay.socket(c.token, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                frames.trySend(received.getAndIncrement() to OPENED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                frames.trySend(received.getAndIncrement() to text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closeCode = code
                webSocket.close(code, null)
                frames.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closeCode = response?.code ?: -1
                frames.close()
            }
        })
        socket = ws
        var liveFrom = Int.MAX_VALUE
        var lastPong = now()
        // 앞에 있고 소켓이 붙어 있는 동안 60초마다 기기 목록만 다시 받아 차 타일을 맞춘다(설계값). 소켓과 같이 끝난다.
        val devicePoll = scope.launch {
            while (isActive) {
                delay(DEVICE_POLL_MS)
                if (fetchDevices(c) == null) return@launch
            }
        }
        val pinger = scope.launch {
            // 25초 간격 글자 ping, 보낸 뒤 10초 안에 pong이 없으면 끊고 다시 붙는다(2장). 10초 확인 + 15초 기다림 = 25초 간격.
            delay(25_000)
            while (isActive) {
                val sentAt = now()
                ws.send("ping")
                delay(10_000)
                if (lastPong < sentAt) ws.cancel()
                delay(15_000)
            }
        }
        try {
            for ((index, text) in frames) {
                if (!isCurrent(c)) break
                when (text) {
                    OPENED -> {
                        onOpen()
                        ui.update { it.copy(socket = true) }
                        setOnline(c, true)
                        // 소켓으로 들어온 프레임은 모두 실시간이다(iOS와 같음). 동기화 중 온 새 인계 알림도 배너가 뜬다.
                        // 받은 함에서 이미 저장한 id는 receive가 processed로 거른다. 실패하면 끊고 기다림 뒤 다시 붙는다.
                        liveFrom = index + 1
                        if (syncInbox(c) != Sync.DONE) break
                        pumpOutbox()
                    }
                    "pong" -> lastPong = now()
                    else -> if (!handleFrame(c, ws, text, live = index >= liveFrom)) break // 저장 실패: 닫고 재접속·받은 함으로 다시 받는다
                }
            }
        } finally {
            pinger.cancel()
            devicePoll.cancel()
            ws.cancel()
            ui.update { it.copy(socket = false) }
        }
        return closeCode
    }

    /** 저장하지 못했으면 거짓: ack 없이 연결을 닫게 해, 재접속 뒤 받은 함에서 다시 받는다(5.6, 8.3). */
    private suspend fun handleFrame(c: Credentials, ws: WebSocket, text: String, live: Boolean): Boolean {
        val env = runCatching { ProtocolJson.decodeFromString(Envelope.serializer(), text) }.getOrNull() ?: return true
        if (!store(c, env, live)) return !isCurrent(c) // 세대가 바뀐 경우는 루프가 알아서 끝난다
        ws.send(RelayClient.ackFrame(listOf(env.id)))
        if (state.value.outbox.isNotEmpty()) pumpOutbox()
        return true
    }

    /** 앞에 있을 때 푸시가 온 메시지 id. 본문이 지금 자격·같은 차로 들어오면 그때 배너로 띄운다. */
    private val pendingPush: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 받은 봉투를 저장한다. 같은 id는 한 번만 띄운다(5.6). 푸시가 먼저 온 id는 받은 함에서 꺼내도 배너로 띄운다.
     * 차 id 확인은 [receive]가 한다. 저장이 확인되면 참.
     */
    private suspend fun store(c: Credentials, env: Envelope, live: Boolean): Boolean {
        var banner: StoredAlert? = null
        val ok = commit(c) { s -> s.receive(env, now(), live || env.id in pendingPush).also { banner = it.banner }.state }
        if (ok) banner?.let { b -> ui.update { it.copy(banner = b.body.text, bannerAlertId = b.id) } }
        // 다른 owner 폰에서 지운 곡(song_status removed)의 녹음 파일도 지움 상태를 저장한 뒤 정리한다(7.4).
        if (ok && env.type == "song_status") cleanSongFiles()
        return ok
    }

    /** 받은 함 한 쪽을 한 번의 commit(저장 한 번)으로 적용한다. 저장이 확인되면 참. */
    private suspend fun storeAll(c: Credentials, envs: List<Envelope>): Boolean {
        if (envs.isEmpty()) return true
        var banner: StoredAlert? = null
        val ok = commit(c) { s0 ->
            envs.fold(s0) { s, env ->
                s.receive(env, now(), env.id in pendingPush).also { r -> r.banner?.let { banner = it } }.state
            }
        }
        if (ok) banner?.let { b -> ui.update { it.copy(banner = b.body.text, bannerAlertId = b.id) } }
        if (ok && envs.any { it.type == "song_status" }) cleanSongFiles()
        return ok
    }

    // ---------------------------------------------------------------- 보낼 함(7.5)

    private var wake: Job? = null
    private val pumpSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        // 보내기는 한 일꾼이 넣은 순서대로 하나씩 한다. 신호는 합쳐지므로 도는 중에 온 신호는 한 번 더 돌게 한다.
        scope.launch { for (x in pumpSignal) pumpOnce() }
    }

    fun pumpOutbox() {
        pumpSignal.trySend(Unit)
    }

    private suspend fun pumpOnce() {
        while (true) {
            val c = creds.value ?: return
            // 맨 앞이 만료면 정리(만료 표시 포함)를 먼저 저장한다. 저장하지 못하면 멈춘다(다음 신호 때 다시).
            if (state.value.frontExpired(now()) && !commit(c) { it.pruned(now()) }) return
            val item = state.value.nextToSend(now())
            if (item == null) {
                val next = state.value.outbox.firstOrNull() ?: return
                wake?.cancel()
                if (!visible) return // 뒤에서는 타이머를 걸지 않는다. 앞으로 올 때(연결 루프) 이어 보낸다(7.5)
                wake = scope.launch {
                    delay(next.nextTryAt - now())
                    pumpOutbox()
                }
                return
            }
            val r = sendItem(c, item)
            if (!isCurrent(c)) return // 이전 연결의 응답은 버린다
            if (r is SendResult.Failed && r.status == 401) return lostConnection(c)
            setOnline(c, r !is SendResult.Offline)
            // 저장하지 못했으면 멈춘다. 다음 신호 때 같은 id로 다시 보내므로 중계에서는 한 번만 처리된다.
            if (!commit(c) { it.afterSend(item, r, now()) }) return
            // 영구 실패면 항목이 보낼 함에서 빠졌다(재시도면 남아 있다).
            if (r is SendResult.Failed && state.value.outbox.none { it.envelope.id == item.envelope.id }) {
                dropNotice(item.envelope.type)?.let { n -> ui.update { it.copy(notice = n) } }
            }
        }
    }

    private suspend fun sendItem(c: Credentials, item: OutboxItem): SendResult {
        if (item.songFile != null && !item.uploaded) {
            val file = File(songsDir, item.songFile)
            if (!file.exists()) return SendResult.Failed(415, "file_missing")
            return when (val r = relay.putSong(c.token, item.songId!!, file)) {
                is Res.Ok -> SendResult.Uploaded
                is Res.Err -> if (r.code == "already_exists") SendResult.Uploaded else SendResult.Failed(r.status, r.code, r.retryAfterS)
                Res.Offline -> SendResult.Offline
            }
        }
        return when (val r = relay.send(c.token, item.envelope)) {
            is Res.Ok -> SendResult.Sent(r.value.body)
            is Res.Err -> SendResult.Failed(r.status, r.code, r.retryAfterS)
            Res.Offline -> SendResult.Offline
        }
    }

    // ---------------------------------------------------------------- 사용자 동작

    /**
     * 녹음을 보낼 함에 넣는다. 원본은 복사본이 목록·보낼 함에 커밋된 뒤에만 지운다.
     * 실패하면 원본을 그대로 두고 문구를 돌려준다(화면은 성공했을 때만 시트를 닫는다). 화면이 사라져도 모델 범위에서 끝난다.
     */
    suspend fun addSong(recording: File, name: String, durationS: Double): String? = scope.async {
        val c = creds.value ?: return@async "연결이 끊겼어요. 다시 연결해 주세요."
        val songId = ulid()
        val file = File(songsDir, "$songId.m4a")
        val err = fileLock.withLock {
            val copied = withContext(Dispatchers.IO) { runCatching { recording.copyTo(file, overwrite = true) }.isSuccess }
            when {
                !copied -> "녹음을 저장하지 못했어요. 다시 보내 주세요."
                commit(c) { it.addSong(songId, name, durationS, file.name, now()) } -> null
                else -> {
                    withContext(Dispatchers.IO) { file.delete() }
                    "녹음을 저장하지 못했어요. 녹음은 그대로 있어요. 다시 보내 주세요."
                }
            }
        }
        if (err == null) {
            withContext(Dispatchers.IO) { recording.delete() }
            pumpOutbox()
        }
        err
    }.await()

    /** 지움 상태와 song_removed를 먼저 함께 커밋하고, 그 뒤에 녹음 파일을 지운다. */
    fun removeSong(songId: String) = act { c ->
        if (commit(c) { it.removeSong(songId, now()) }) cleanSongFiles()
    }

    fun selectSong(songId: String) = act { c -> commit(c) { it.selectSong(songId, now()) } }

    /**
     * '이 휴대폰의 기록 지우기': 커밋 → 파일 정리 → 기기 목록 확인과 상태 요청 판단(7.4). 돌봄 기록도 지운다(R10).
     * 연결 상태를 먼저 지운다: 돌봄 기록만 지워진 채 죽으면 남은 수유 전송 기록이 다시 옮겨 올 수 있다. 연결이 없으면 돌봄 기록만.
     */
    fun clearRecords() {
        scope.launch {
            val c = creds.value
            if (c != null) {
                if (!commit(c) { it.clearedRecords() }) return@launch
                ui.update { it.copy(banner = null, bannerAlertId = null) } // 지운 알림 본문·상세로 가는 '보기'를 막는다
                withContext(Dispatchers.IO) { store.backup.delete() } // 사용자가 기록을 지웠으니 옮겨 둔 상태도 지운다
                cleanSongFiles()
            }
            saveCare { it.cleared() }
            withContext(Dispatchers.IO) {
                careStore.backup.delete()
                careLock.withLock { File(app.cacheDir, "export").deleteRecursively() } // 내보낸 CSV 사본도 지운다(계약 7.4 '캐시')
            }
            if (c != null) {
                refreshDevices(c)
                pumpOutbox()
            }
        }
    }

    private fun act(f: suspend (Credentials) -> Unit) {
        scope.launch {
            val c = creds.value ?: return@launch
            f(c)
            pumpOutbox()
        }
    }

    /** 연결 작업 상태. 화면이 회전·재구성돼도 작업은 모델 수명에서 끝나고, 화면은 결과만 본다. */
    data class PairRun(val code: String, val busy: Boolean, val error: String? = null, val role: Role? = null)

    val pairRun = MutableStateFlow<PairRun?>(null)
    private var pairJob: Job? = null
    /** 429 잠금이 끝나는 시각(epoch ms). 화면·입력·확인 화면이 같이 본다. 화면을 옮기거나 코드를 고쳐도 풀리지 않는다. */
    val pairLockedUntil = MutableStateFlow<Long?>(null)
    /** 마지막 pair가 429였으면 Retry-After 초(헤더 없으면 0), 아니면 -1. 0은 [pairLockUntil]이 10초로 본다. */
    private val pairRetryAfterS = java.util.concurrent.atomic.AtomicLong(-1L)

    /** 확인 화면의 '연결할게요'. 이미 연결된 차가 있으면 먼저 끊고(G16), 둘 다 성공해야 role이 실린다. */
    fun startPair(code: String) {
        if (pairJob?.isActive == true || isPairLocked(pairLockedUntil.value, now())) return
        pairRun.value = PairRun(code, busy = true)
        pairJob = scope.launch {
            var err = if (creds.value != null) disconnectNow() else null
            if (err == null) err = pair(code)
            val lock = pairRetryAfterS.getAndSet(-1L).takeIf { it >= 0 }?.let { pairLockUntil(now(), it) }
            if (lock != null) pairLockedUntil.value = lock
            pairRun.value = PairRun(code, busy = false, error = err, role = if (err == null) creds.value?.role else null)
        }
    }

    fun clearPairRun() {
        if (pairJob?.isActive != true) pairRun.value = null
    }

    /** 코드 쓰기(1.4). 실패하면 화면 문구(7.2). 토큰 저장부터 자격 전환·되돌림까지 끊기지 않게 모델 범위에서만 부른다. */
    private suspend fun pair(code: String): String? {
        val push = fcmToken()
        return when (val r = relay.pair(code, push, installId)) {
            is Res.Ok -> transitionLock.withLock {
                // 진행 중인 옛 연결 정리(wipe)가 끝난 뒤에만 새 토큰·상태·파일·소켓을 바꾼다.
                val c = Credentials(r.value.carId, r.value.deviceId, r.value.deviceToken, r.value.role)
                stopConnection()
                // 토큰을 디스크에 쓴 것이 확인된 뒤에 상태를 커밋하고 자격을 공개한다(7.4).
                val prev = creds.value
                if (!withContext(Dispatchers.IO) { secrets.save(c) }) return "연결 정보를 저장하지 못했어요"
                if (!switchTo(c, PhoneState.paired(c.carId, c.deviceId, c.role, now()))) {
                    withContext(Dispatchers.IO) { secrets.save(prev) }
                    return "연결 정보를 저장하지 못했어요"
                }
                cleanSongFiles()
                ui.update { Ui() }
                clearDisconnectRun() // 지난 끊기 결과가 새 연결의 설정 화면을 닫지 않게
                startConnection()
                reconcileFeeding(FeedTrigger.PAIRED) // 새 차에는 24시간 안의 최신 수유가 다시 간다(U6)
                null
            }
            is Res.Err -> when {
                r.code == "code_invalid" -> "코드가 맞지 않거나 시간이 지났어요. 차 화면의 코드를 다시 확인해 주세요."
                r.code == "bad_code_format" -> "6자리 숫자 또는 초대 코드를 넣어 주세요"
                r.status == 429 -> {
                    pairRetryAfterS.set(r.retryAfterS ?: 0L)
                    "잠시 뒤에 다시 해 주세요."
                }
                else -> "연결하지 못했어요(${r.code ?: r.status})"
            }
            Res.Offline -> "인터넷에 연결한 뒤 다시 해 주세요"
        }
    }

    suspend fun invite(): Result<InviteResponse> {
        val c = creds.value ?: return Result.failure(IllegalStateException("연결이 없어요"))
        val r = relay.invite(c.token)
        if (!isCurrent(c)) return Result.failure(IllegalStateException("연결이 바뀌었어요"))
        return when (r) {
            is Res.Ok -> Result.success(r.value)
            is Res.Err -> {
                if (r.status == 401) lostConnection(c)
                Result.failure(IllegalStateException(if (r.code == "invite_limit") "쓰지 않은 초대가 3개 있어요" else "초대를 만들지 못했어요(${r.code ?: r.status})"))
            }
            Res.Offline -> Result.failure(IllegalStateException("인터넷이 필요해요"))
        }
    }

    /** guardian의 '인계 알림 받기'(G12). 실패하면 문구. */
    suspend fun setHandoffAlerts(on: Boolean): String? {
        val c = creds.value ?: return null
        return when (val r = relay.patchMe(c.token, PatchDeviceRequest(handoffAlerts = on))) {
            is Res.Ok -> null.also { commit(c) { it.copy(handoffAlerts = r.value.handoffAlerts) } }
            is Res.Err -> if (r.status == 401) null.also { lostConnection(c) } else "바꾸지 못했어요"
            Res.Offline -> "인터넷이 필요해요"
        }
    }

    /** 사용자가 끊기를 요청한 자격. 그 사이 소켓 4001·다른 요청의 401은 강제 해제가 아니라 이 끊기의 결과다. */
    private var leaving: Credentials? = null

    /** 설정의 '연결 끊기' 상태. 화면이 회전·재구성돼도 작업은 모델에서 끝나고, 화면은 이것만 본다. */
    data class DisconnectRun(val busy: Boolean, val error: String? = null)

    val disconnectRun = MutableStateFlow<DisconnectRun?>(null)
    private var disconnectJob: Job? = null

    fun startDisconnect() {
        if (disconnectJob?.isActive == true) return
        disconnectRun.value = DisconnectRun(busy = true)
        disconnectJob = scope.launch {
            val error = disconnectNow()
            if (error == null && creds.value == null) afterUserDisconnect() // U25 b: 화면과 무관하게(설정 탭이 사라져도)
            disconnectRun.value = DisconnectRun(busy = false, error = error)
        }
    }

    fun clearDisconnectRun() {
        if (disconnectJob?.isActive != true) disconnectRun.value = null
    }

    /** 연결 끊기: 중계의 토큰을 지워야 해서 인터넷이 필요하다(7.2). 성공하면 null. */
    private suspend fun disconnectNow(): String? {
        val c = creds.value ?: return null
        leaving = c
        try {
            return when (val r = relay.deleteMe(c.token)) {
                is Res.Ok -> null.also { wipe(c) }
                is Res.Err -> if (r.status == 401) null.also { wipe(c) } else "끊지 못했어요(${r.code ?: r.status})"
                Res.Offline -> "인터넷이 필요해요"
            }
        } finally {
            leaving = null
        }
    }

    fun dismissBanner() = ui.update { it.copy(banner = null, bannerAlertId = null) }

    fun clearNotice() = ui.update { it.copy(notice = null) }

    fun acknowledgeGone() = ui.update { it.copy(gone = false) }

    /** `c`가 아직 지금 자격일 때만 토큰·캐시를 지운다. 소켓 정리(취소)는 맨 뒤에 한다. */
    /**
     * 연결 끊기·401 정리. 화면 연결 루프에서 불려도 onStop의 취소가 정리를 끊지 않게 모델 범위(앱 수명)에서 끝까지 한다.
     * 부르는 쪽이 취소돼도 이 작업은 이어진다.
     */
    private suspend fun wipe(c: Credentials): Boolean = scope.async { wipeNow(c) }.await()

    private suspend fun wipeNow(c: Credentials): Boolean = transitionLock.withLock {
        // 자격 비우기부터 비밀값·녹음·소켓 정리까지 한 덩어리다. 새 연결은 이것이 끝난 뒤에 시작된다.
        if (!switchTo(null, PhoneState(), onlyIf = c)) return false
        ui.update { it.copy(banner = null, bannerAlertId = null) }
        if (!withContext(Dispatchers.IO) { secrets.save(null) }) Log.e(TAG, "연결 비밀값을 지우지 못했어요")
        purgeSongFiles()
        stopConnection()
        true
    }

    /** 연결 끊기·401: 지킬 기록이 없으므로 파일 잠금 안에서 백업·녹음을 모두 지운다(7.4). 정리 건너뛰기 조건을 타지 않는다. */
    private suspend fun purgeSongFiles() = fileLock.withLock {
        withContext(Dispatchers.IO) {
            store.discardRecovery()
            songsDir.listFiles()?.forEach { it.delete() }
        }
    }

    /** 401·닫기 4001. 이전 연결의 늦은 401이거나 사용자가 요청한 끊기 중이면 무시한다(끊기가 마무리한다). */
    private suspend fun lostConnection(c: Credentials) {
        if (!isCurrent(c) || leaving == c) return
        ui.update { it.copy(gone = true) }
        wipe(c)
    }

    // ---------------------------------------------------------------- 푸시(5장)

    /**
     * 앞에 있을 때 온 푸시(5.6). 푸시 문장은 바로 띄우지 않는다: 이전 차의 늦은 푸시일 수 있다.
     * id를 보류해 두고 지금 자격으로 받은 함을 받아, 이 차의 본문이 저장될 때 배너로 띄운다.
     */
    fun onPush(msgId: String) {
        if (!isUlid(msgId)) return
        scope.launch {
            val c = creds.value ?: return@launch
            if (msgId in state.value.shown) return@launch // 이미 띄웠다(WebSocket이 먼저 옴)
            pendingPush += msgId
            try {
                syncInbox(c)
            } finally {
                pendingPush -= msgId
            }
        }
    }

    fun onPushToken(token: String) {
        scope.launch { creds.value?.let { registerPush(it, token) } }
    }

    /** 앱을 열 때마다 같은 값이어도 보낸다(5.4). 실패하면 다음 열기 때 다시. */
    private suspend fun registerPush(c: Credentials, token: String?) {
        val body = PatchDeviceRequest(pushToken = token?.let { PushTokenPatch.Set(it) } ?: PushTokenPatch.Keep)
        when (val r = relay.patchMe(c.token, body)) {
            is Res.Ok -> commit(c) { it.copy(handoffAlerts = r.value.handoffAlerts) }
            is Res.Err -> if (r.status == 401) lostConnection(c)
            Res.Offline -> Unit
        }
    }

    /** Firebase 설정 파일이 없으면(공개 저장소 빌드) null. */
    @Suppress("DEPRECATION") // Firebase Messaging 26의 register() 대신 계약 5.4의 token을 쓴다
    private suspend fun fcmToken(): String? {
        if (FirebaseApp.getApps(app).isEmpty()) return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                    cont.resume(if (t.isSuccessful) t.result else null)
                }
            }
        }.also { if (it == null) Log.w(TAG, "FCM 토큰 없음") }
    }

    private companion object {
        const val TAG = "AppModel"
        const val DEVICE_POLL_MS = 60_000L // 설계값(실측 아님)
        const val OPENED = "\u0000opened"
    }
}
