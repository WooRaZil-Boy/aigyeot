package kr.aigyeot.phone

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kr.aigyeot.phone.core.canOpenConfirm
import kr.aigyeot.phone.core.PhoneState
import kr.aigyeot.phone.core.SendResult
import kr.aigyeot.phone.core.StoredAlert
import kr.aigyeot.phone.core.CareEvent
import kr.aigyeot.phone.core.CareFile
import kr.aigyeot.phone.core.CareKind
import kr.aigyeot.phone.core.FeedSide
import kr.aigyeot.phone.core.FeedTrigger
import kr.aigyeot.phone.core.addSong
import kr.aigyeot.phone.core.replaceable
import kr.aigyeot.phone.core.songsFull
import kr.aigyeot.phone.core.idsToClear
import kr.aigyeot.phone.core.keepingClear
import kr.aigyeot.phone.core.dropNotice
import kr.aigyeot.phone.core.markSentOnce
import kr.aigyeot.phone.core.untried
import kr.aigyeot.phone.core.withoutCleared
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
import kr.aigyeot.phone.core.reconciled
import kr.aigyeot.phone.core.reconciledSummary
import kr.aigyeot.phone.core.pairDoneValid
import kr.aigyeot.phone.core.start
import kr.aigyeot.phone.core.undoDelete
import kr.aigyeot.phone.core.upsert
import kr.aigyeot.phone.core.afterSend
import kr.aigyeot.phone.core.APP_ZONE
import kr.aigyeot.phone.core.STATE_REQUEST_GAP_MS
import kr.aigyeot.phone.core.backoffMs
import kr.aigyeot.phone.core.posting
import kr.aigyeot.phone.core.withMessageHold
import kr.aigyeot.phone.core.isPairLocked
import kr.aigyeot.phone.core.pairLockUntil
import kr.aigyeot.phone.core.clearedRecords
import kr.aigyeot.phone.core.withDropNotices
import kr.aigyeot.phone.core.withNoticeLine
import kr.aigyeot.phone.core.SAVE_FAILED
import kr.aigyeot.phone.core.LeaveWatch
import kr.aigyeot.phone.core.envelope
import kr.aigyeot.phone.core.mergeDevices
import kr.aigyeot.phone.core.frontExpired
import kr.aigyeot.phone.core.nextToSend
import kr.aigyeot.phone.core.onAppOpen
import kr.aigyeot.phone.core.pruned
import kr.aigyeot.phone.core.receive
import kr.aigyeot.phone.core.removeSong
import kr.aigyeot.phone.core.replacedSong
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
    private val relay = RelayClient(BuildConfig.RELAY_URL, BuildConfig.VERSION_NAME, onUnauthorized = ::unauthorized, onObserved = ::observed)
    private val lock = Mutex()

    /**
     * 연결 전환 잠금. 토큰·상태·파일·소켓을 함께 바꾸는 것(연결 마침, 끊기·401 정리, 차 바꾸기)을 줄 세운다.
     * 잡는 순서는 transitionLock → [lock]·fileLock이다. 안에서 이것을 다시 잡지 않는다(재진입 안 됨).
     */
    private val transitionLock = Mutex()
    private val kick = Channel<Unit>(Channel.CONFLATED)

    val songsDir = File(app.filesDir, "songs").apply { mkdirs() }

    private val credsRead = runCatching { secrets.load() }.onFailure { Log.e(TAG, "연결 정보를 읽지 못함", it) }
    private val _creds = MutableStateFlow(credsRead.getOrNull())
    val creds: StateFlow<Credentials?> = _creds

    // 토큰은 썼는데 상태 파일을 바꾸기 전에 앱이 죽었으면 다른 차의 상태다. 그 차의 빈 상태로 시작한다.
    // 토큰은 있는데 상태가 그 차 것이 아니면(읽기 실패로 빈 상태, 상태 파일을 바꾸기 전에 죽음) 그 차의 빈 상태로 시작하고
    // 상태 요청 필요를 켠다. 기기 목록 조회가 성공하면 state_request를 보낸다.
    // 돌봄 기록(phone-care-plan 6장). 연결과 상관없이 쓴다(U7). 끊어도 지우지 않는다(U6·R9). 시작 상태가 옮기기 여부를 보므로 먼저 읽는다.
    private val careStore = CareStore(File(app.filesDir, "care.json"))
    private val _care = MutableStateFlow(careStore.load())
    val care: StateFlow<CareFile> = _care

    private val loaded = store.load()
    private val recovered = _creds.value?.let { c ->
        PhoneState.recoverIfNeeded(loaded, c.carId, c.deviceId, c.role, System.currentTimeMillis())
    }
    /** 수유 옮기기(R11)를 아직 마치지 못했다: 연결이 바뀌어도 옮길 원본 수유 기록은 남긴다. */
    private fun migrating() = !_care.value.migratedFeedings
    // 자격이 있는데 읽지 못했으면(예외) 이번 실행은 연결 없이 두되 지난 상태는 그대로 쓴다. 빈 상태로 보면 녹음 정리가 남은 녹음을 지우고,
    // 기록 지우기가 차 정보 없는 상태 파일을 써 다음 실행의 연결이 받는 것을 모두 버린다. 화면의 연결 여부는 자격으로 정한다.
    private val startState = PhoneState.atStart(loaded, recovered, connected = _creds.value != null || credsRead.isFailure, migrating = migrating())
    private val _state = MutableStateFlow(startState)
    val state: StateFlow<PhoneState> = _state
    // 지난 실행에서 버리고 아직 띠를 닫지 않은 메시지를 다시 알린다(U54).
    val ui = MutableStateFlow(
        Ui(
            notice = withDropNotices(null, _state.value.unseenDrops.values.toList()),
            // 앞 프로세스가 강제 끊김(401·4001)을 정리한 뒤 사용자가 GONE 화면에서 고르기 전에 죽었다(9장, U25 a).
            gone = credsRead.isSuccess && _creds.value == null && prefs.getString(GONE, null) != null,
        ),
    )

    /** 지금 보이는 GONE(`ui.gone`)을 켠 연결의 기기 id. 옛 연결의 늦은 끊기 결과가 다른 연결의 GONE을 걷지 않게 한다. */
    private var goneDevice: String? = if (ui.value.gone) prefs.getString(GONE, null) else null

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
    private suspend fun commit(c: Credentials, f: (PhoneState) -> PhoneState): Boolean = lock.withLock { isCurrent(c) && write(f) }

    /** 연결과 따로인 일(기록 지우기·수유 옮기기 표시)만 쓴다. 자격이 없어도 저장한다. */
    private suspend fun commitLocal(f: (PhoneState) -> PhoneState): Boolean = lock.withLock { write(f) }

    /** [lock] 안에서만 부른다. */
    private suspend fun write(f: (PhoneState) -> PhoneState): Boolean {
        val next = f(_state.value)
        if (next == _state.value) return true
        if (!withContext(Dispatchers.IO) { saved(next) }) {
            ui.update { it.copy(notice = withNoticeLine(it.notice, SAVE_FAILED)) }
            return false
        }
        _state.value = next
        return true
    }

    /** 자격과 상태를 한 잠금 안에서 함께 바꾼다(연결 세대가 바뀌는 곳). `onlyIf`가 있으면 그 자격일 때만. */
    private suspend fun switchTo(next: Credentials?, make: (PhoneState) -> PhoneState, onlyIf: Credentials? = null): Boolean = lock.withLock {
        if (onlyIf != null && !isCurrent(onlyIf)) return@withLock false
        val s = make(_state.value) // 잠금 안의 최신 상태에서 만든다(그사이 저장된 지울 id를 잃지 않게)
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

    /** 마지막으로 반영한 중계 관찰의 요청 순번(메인 흐름에서만 읽고 쓴다). 먼저 시작한 요청의 늦은 결과가 뒤의 관찰을 덮지 않게 한다(iOS `linkSeen`과 같음). */
    private var observedTicket = 0L

    /**
     * 토큰을 실은 모든 HTTP 요청의 결과(9장 '중계 불통'): 상태 요청·푸시 등록·ack·알림 설정도 오프라인 띠에 반영한다.
     * IO 스레드에서 불리므로 모델의 메인 흐름으로 넘겨, 토큰·순번 확인과 표시 변경을 연결 전환(`switchTo`·`Ui()`)과 끼어들 틈 없이 한다.
     */
    private fun observed(token: String, ticket: Long, up: Boolean) {
        scope.launch {
            // 지금 연결의 토큰인지 먼저 본다: 옛 연결의 늦은 응답이 순번만 올려 새 연결의 관찰을 막지 않게.
            val c = creds.value?.takeIf { it.token == token } ?: return@launch
            if (ticket <= observedTicket) return@launch
            observedTicket = ticket
            setOnline(c, up)
        }
    }

    /** 소켓이 붙거나 협상이 5xx로 끝났다(메인 흐름). 그 전에 시작한 HTTP 요청의 늦은 결과는 이 관찰을 덮지 않는다. */
    private fun observedBySocket(c: Credentials, up: Boolean) {
        observedTicket = maxOf(observedTicket, relay.lastTicket())
        setOnline(c, up)
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
    private suspend fun cleanSongFiles(): Boolean = fileLock.withLock {
        withContext(Dispatchers.IO) {
            if (store.backup.exists() || store.unsafe) return@withContext false
            val keep = state.value.songFiles
            // 폴더를 읽지 못하면(장치 오류) 지우지 못한 것이다. 폴더가 없으면 지울 것도 없다.
            val files = songsDir.listFiles() ?: return@withContext !songsDir.exists()
            files.filter { it.name !in keep }.map { it.delete() }.all { it }
        }
    }

    /** 앞 프로세스가 남긴 임시 녹음. 녹음 시트는 프로세스를 넘어 살지 않으니, 화면이 뜨기 전(모델을 만들 때) 목록을 정해 두고 지운다. */
    private val leftoverRecordings: List<File> = app.cacheDir.listFiles { f -> f.name.startsWith("recording-") }?.toList().orEmpty()

    init {
        // `Dispatchers.Main`(즉시 아님): 생성자가 끝난 뒤 돈다. `withContext(IO)`가 멈추지 않고 끝나면 즉시 실행은 생성자 안에서 이어져
        // 아래에 선언한 칸(`pumpSignal` 등)을 아직 null일 때 쓴다.
        scope.launch(Dispatchers.Main) {
            // 복구한 상태(요청 필요 표시 포함)를 바로 저장한다. 백업하지 못했으면 저장은 거부된다(다음 실행이 다시 복구한다).
            if (recovered != null) lock.withLock { if (_state.value === startState) withContext(Dispatchers.IO) { saved(startState) } }
            // 자격이 없다고 확정되면(못 읽음이 아님) 연결 캐시는 쓸모가 없다: 끊기 정리가 중간에 죽었어도 손상 사본·녹음을 마저 지운다(7.4).
            // 지우다 만 기록 지우기 표시는 남긴다(purgeSongFiles).
            // 새 연결(`switchTo`, 같은 잠금)과 겹쳐 새 상태 파일을 지우지 않게 잠금 안에서 다시 본다.
            if (credsRead.isSuccess) transitionLock.withLock { if (_creds.value == null) purgeSongFiles() }
            withContext(Dispatchers.IO) { leftoverRecordings.forEach { it.delete() } } // 앞 프로세스가 남긴 임시 녹음
            cleanSongFiles()
            // 앱을 열 때: 30일 지난 지운 기록 정리(R8), 지금 수유 기록 한 번 옮기기(R11). 그 뒤 차로 보낼지 본다(R6).
            // U22: 옮기기 전에 state.json의 보낸 표시를 먼저 채운다. 저장하지 못했으면 이번에는 옮기지 않는다(다음 실행이 다시 한다).
            finishCareClear() // 지우다 만 기록을 먼저 마친다(옮기기·요약보다 먼저, R10)
            migrateFeedingsIfNeeded()
            saveCare { it.purged(now()) }
            reconcileAtStart()
        }
        // 진행 중 기록이 바뀔 때만 잠금 화면 알림을 고친다(R15: 매초 갱신 없음).
        scope.launch { care.collect { CareNotify.update(app, it) } }
    }

    // ---------------------------------------------------------------- 돌봄 기록(phone-care-plan 6장)

    private fun zone() = APP_ZONE.id

    /** 돌봄 기록을 바꾸고 저장한다. 저장이 확인됐을 때만 메모리를 바꾼다. `f`가 null이면(규칙 위반) 바꾸지 않는다. */
    private suspend fun saveCare(f: (CareFile) -> CareFile?): Boolean {
        // 지우다 만 '기록 지우기'를 이 저장에서 먼저 마친다. 그때의 id만 빼므로 그 뒤 적은 기록은 남는다(R10, iOS `updateCare`와 같음).
        val wiping = state.value.clearingCareIds
        val since = state.value.clearingSince
        val ok = careLock.withLock {
            val base = wiping?.let { _care.value.withoutCleared(it) } ?: _care.value
            // 바꾼 뒤에도 뺀다: 아직 보이던 지울 기록을 고쳐 저장해도 되살아나지 않는다(iOS `updateCare`와 같음).
            val next = f(base)?.let { n -> wiping?.let { n.withoutCleared(it) } ?: n } ?: return@withLock false
            if (next == _care.value) return@withLock true
            val saved = withContext(Dispatchers.IO) {
                runCatching { careStore.save(next) }.onFailure { Log.e(TAG, "돌봄 기록 저장 실패", it) }.isSuccess
            }
            if (!saved) {
                ui.update { it.copy(notice = withNoticeLine(it.notice, SAVE_FAILED)) }
                return@withLock false
            }
            _care.value = next
            true
        }
        // 표시 끄기에 실패해도 다음에 같은 id를 다시 빼므로 새 기록은 지우지 않는다. 마쳤으면 어느 저장이든(별명 포함) 빈 요약을 넣는다(R19).
        if (ok && wiping != null) {
            endCareClear(wiping, since)
            reconcileNow()
        }
        return ok
    }

    /** 사용자가 돌봄 기록을 바꿨다: 저장한 뒤 차로 보낼지 본다(R6 '돌봄 기록 저장·고치기 뒤'). */
    private suspend fun careCommit(f: (CareFile) -> CareFile?): Boolean {
        if (!saveCare(f)) return false
        reconcileFeeding(FeedTrigger.CARE_SAVED)
        return true
    }

    /**
     * R5: 차로 보낼 수유가 있으면 보낼 함에 넣고 보낸 표시를 같은 state.json 저장에서 올린다. 저장에 실패하면 아무것도 바뀌지 않는다.
     * R19: 돌봄 요약도 같은 계기·같은 저장에서 맞춘다(바뀌었을 때만, 보낼 함에 하나만).
     * 부르는 곳은 정해진 네 계기뿐이다(R6, [FeedTrigger]). 연결이 없거나 owner가 아니면 하지 않는다.
     */
    /** 앱 시작 계기(R6·R19). 시작 때 자격을 못 읽었으면 연결 전 다시 읽기가 성공했을 때 한 번 더 부른다. */
    private fun reconcileAtStart() = reconcileFeeding(FeedTrigger.APP_START)

    private fun reconcileFeeding(why: FeedTrigger) {
        scope.launch {
            val c = creds.value ?: return@launch
            migrateFeedingsIfNeeded()
            if (commit(c) { it.reconciled(now()) } && state.value.outbox.isNotEmpty()) pumpOutbox()
            Log.d(TAG, "수유 보내기 확인: $why")
        }
    }

    /** R5·R19를 한 상태에서([reconciled]). 연결이 없거나 owner가 아니면 그대로다. */
    private fun PhoneState.reconciled(t: Long) = reconciled(care.value, t, APP_ZONE, sending)
    private fun PhoneState.summaryReconciled(t: Long) = reconciledSummary(care.value, t, APP_ZONE, sending)

    /** R11·U22: 수유 기록 옮기기를 아직 못 했으면 한다(시작과 보내기 계기마다, iOS와 같음). state.json의 보낸 표시를 먼저 채운다. */
    private suspend fun migrateFeedingsIfNeeded() {
        if (_care.value.migratedFeedings) return
        val mark: (PhoneState) -> PhoneState = { it.markedMigratedFeedings() }
        if (!(creds.value?.let { commit(it, mark) } ?: commitLocal(mark))) return
        // 옛 연결에서 넘어온 원본도 함께 옮긴다. 다 옮겼으면 그 원본은 지운다(남아도 해롭지 않다).
        if (saveCare { it.migrated(state.value.feedings + state.value.migrationFeedings, zone()) } && state.value.migrationFeedings.isNotEmpty()) {
            commitLocal { it.copy(migrationFeedings = emptyList()) }
        }
    }

    /**
     * R10: 지우다 만 '기록 지우기'를 마친다(시작·앞으로 올 때, 그사이 죽었거나 care.json 저장 실패). 마치면 빈 요약을 보낸다(R19).
     * 연결이 없어도 한다(돌봄 기록은 연결과 따로다).
     */
    private suspend fun finishCareClear() {
        if (state.value.clearingCareIds != null) saveCare { it } // 저장이 지울 id를 빼고, 마치면 빈 요약을 넣는다
    }

    /** 계기 밖에서 지금 맞춘다(기록 지우기를 마친 뒤). 지우기를 아직 못 마쳤으면 아무것도 넣지 않는다. */
    private suspend fun reconcileNow() {
        val c = creds.value ?: return
        if (commit(c) { it.summaryReconciled(now()) } && state.value.outbox.isNotEmpty()) pumpOutbox()
    }

    /**
     * 지울 id를 뺀 care.json을 저장한 뒤: 돌봄 기록의 사본(손상 사본·내보낸 CSV)을 지우고(7.4 '캐시'), 지운 것이 확인되면 표시를 끈다.
     * 그사이 죽거나 사본을 못 지우면 표시가 남아 다음 저장·시작에 다시 한다.
     */
    private suspend fun endCareClear(ids: List<String>, since: Long?) {
        // 기록 지우기의 파일 정리를 모두 다시 한다(지우기 직후에 죽었어도): 상태 손상 사본 → 녹음(지운 상태 기준) → 돌봄 손상 사본·내보낸 CSV.
        val stateCopy = withContext(Dispatchers.IO) { store.backup.let { !it.exists() || it.delete() } }
        val cleaned = stateCopy && cleanSongFiles() && removeTempRecordings(upTo = since) && withContext(Dispatchers.IO) {
            val backup = careStore.backup.let { !it.exists() || it.delete() }
            // 내보낸 CSV와, 이름을 바꾸기 전에 죽어 남은 돌봄 임시 파일(저장과 같은 잠금)
            val rest = careLock.withLock { File(app.cacheDir, "export").let { !it.exists() || it.deleteRecursively() } && careStore.discardTemp() }
            backup && rest
        }
        // 그사이 다시 지웠으면(id나 누른 시각이 바뀜) 그 요청의 정리가 끝내게 남긴다.
        if (cleaned) commitLocal { if (it.clearingCareIds == ids && it.clearingSince == since) it.copy(clearingCareIds = null, clearingSince = null) else it }
    }

    /** 빠른 버튼·타이머 시작(R1·R2·U21). 앞 기록을 닫았으면 띠를 띄운다. */
    fun startCare(kind: CareKind, side: FeedSide? = null) {
        val at = now() // 누른 시각: 저장 잠금을 기다리는 사이 기록 지우기를 눌러도 그 전에 누른 기록은 지울 대상이다(R10)
        scope.launch {
            var closed: CareEvent? = null
            val ok = careCommit { f -> f.start(kind, at, zone(), side).also { closed = it.closedPrevious }.file }
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
    suspend fun saveCareEvent(e: CareEvent, editing: Boolean = false): Boolean = scope.async { careCommit { it.upsert(e, now(), editing) } }.await()

    /** 타이머: 저장 잠금 안에서 지금 진행 중인 그 기록에만 바꿀 것을 얹는다. */
    suspend fun editOngoing(id: String, f: (CareEvent) -> CareEvent): Boolean = scope.async { careCommit { it.editOngoing(id, now(), f) } }.await()

    /** 기록 지우기(밀기·화면 읽기). 저장하지 못하면 `onFail`(화면이 밀린 줄을 되돌린다). */
    fun deleteCare(id: String, onFail: () -> Unit = {}) {
        scope.launch { if (careCommit { it.delete(id, now()) }) ui.update { it.copy(undoCareId = id) } else onFail() }
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
        File(dir, exportFileName(now(), APP_ZONE)).apply {
            writeText(care.value.toCsv(APP_ZONE), Charsets.UTF_8)
        }
    }.onFailure { Log.e(TAG, "내보내기 실패", it) }.getOrNull()

    fun undoDeleteCare(id: String) {
        scope.launch { careCommit { it.undoDelete(id) } }
    }

    fun setChildNickname(name: String?) {
        scope.launch { careCommit { it.copy(childNickname = name?.trim()?.takeIf(String::isNotEmpty)) } } // care.json 저장 뒤 계기(R19)
    }

    fun setSleepDiaper(on: Boolean) {
        scope.launch { careCommit { it.copy(sleepDiaperEnabled = on) } } // 요약의 잠·기저귀 칸이 바뀐다(R19)
    }

    /** debug 시연 시드(U50, `src/debug`의 수신기)만 부른다. 사용자 저장과 같은 길(저장 뒤 계기)로 통째로 바꾼다. release에서는 하지 않는다. */
    fun replaceCareForDebug(f: (CareFile) -> CareFile): Job = scope.launch { if (BuildConfig.DEBUG) careCommit(f) }

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
        // 지우다 만 기록(R10)을 먼저 마친 뒤 수유·요약을 본다(시작 init·iOS `didBecomeActive`와 같은 순서): 지우는 사이 적은 수유도 이번에 간다.
        scope.launch {
            finishCareClear()
            reconcileFeeding(FeedTrigger.FOREGROUND)
        }
        scope.launch { saveCare { it.purged(now()) } } // R8 '앱을 열 때': 프로세스가 살아 있는 채 다시 열어도 정리한다
        refreshCareNotice() // 시스템 설정에서 알림을 켜고 돌아왔을 수 있다(기록은 그대로라 흐름으로는 다시 뜨지 않는다)
    }

    /** 생명주기: 화면이 뒤로 갔다. 끊고 멈춘다. */
    fun onBackground() {
        visible = false
        wake?.cancel()
        stopConnection()
    }

    /** 시작 저장에 실패해 연결 루프를 열지 못했다: 앞에 있는 동안 잠시 뒤 다시 연다(iOS `scheduleStaleRetry`와 같음). */
    private fun retryConnectLater(c: Credentials) {
        scope.launch {
            delay(5_000)
            if (isCurrent(c) && foreground?.isActive != true) {
                foreground = null
                startConnection()
            }
        }
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
        if (!commitPruned(c) { it.onAppOpen(now()) }) return retryConnectLater(c)
        registerPush(c, fcmToken())
        var attempts = 0
        while (isCurrent(c)) {
            // 붙을 때마다(통신이 돌아올 때 포함) 기기 목록을 다시 받고 상태 요청이 필요한지 다시 본다(7.4).
            if (!refreshDevices(c)) return
            val code = runSocket(c) { attempts = 0 }
            when (code) {
                4001, 401 -> return // 소켓 콜백이 이미 lostConnection을 불렀다
                4002 -> return // 같은 기기의 새 연결로 바뀜
                in 500..599 -> observedBySocket(c, false) // 소켓 협상 5xx: 중계 장애(9장, iOS `unreachable`과 같음)
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
        val carMark = carLiveSeen.get()
        val ticket = devicesTicket.incrementAndGet()
        when (val r = relay.devices(c.token)) {
            is Res.Ok -> {
                // 같은 snapshot_seq에서 뒤에 시작한 목록을 이미 적용했으면 이 늦은 응답은 버린다(iOS `appliedDevicesTicket`과 같은 뜻).
                // 목록을 기다리는 사이 받은 차의 실시간 봉투는 차 행을 켜진 채로 둔다. 둘 다 잠금 안에서 본다.
                // 더 큰 snapshot_seq면 늘 적용하고(작으면 mergeDevices가 버린다), 같으면 뒤에 시작한 요청이 이긴다(iOS와 같음).
                // 적용 순번은 저장이 성공한 뒤 같은 잠금 안에서 올린다.
                val saved = lock.withLock {
                    if (!isCurrent(c)) return@withLock false
                    var merged = false
                    val seq = r.value.snapshotSeq
                    write { s ->
                        if (s.devicesSnapshotSeq != null && seq < s.devicesSnapshotSeq) return@write s // 더 오래된 목록
                        if (seq == appliedDevices.first && ticket < appliedDevices.second) return@write s // 같은 목록의 앞선 요청
                        merged = true
                        s.mergeDevices(seq, r.value.devices, now(), lastCarLiveAt.takeIf { carLiveSeen.get() != carMark })
                    }.also { if (it && merged) appliedDevices = seq to ticket }
                }
                if (!saved) return false
                ok = true
            }
            is Res.Err -> if (r.status == 401) return null.also { lostConnection(c) }
            Res.Offline, Res.Unreached -> Unit // 온라인 표시는 HTTP 층의 관찰(`observed`)이 맞춘다
        }
        ui.update { it.copy(clockSkewMin = relay.clockSkewMs?.let { ms -> ms / 60_000 }) }
        return ok
    }

    /** 10분 제한을 보고 요청 시각을 저장하는 것을 한 잠금 안에서 한다. 예약에 성공한 호출만 보낸다. */
    private suspend fun requestState(c: Credentials) {
        // 메시지 429 기다림 중이면 끝난 뒤 목록 확인부터 다시 한다(표시는 남아 있다).
        holdUntil(now())?.let { return retryStateAt(c, it) }
        var reserved = false
        var gen = 0
        var before: Long? = null
        val at = now()
        val ok = commit(c) { s ->
            if (!s.shouldRequestState(at)) return@commit s
            reserved = true
            gen = s.stateNeedGen
            before = s.lastStateRequestAt
            s.copy(lastStateRequestAt = at)
        }
        if (!ok) return
        if (!reserved) {
            // 10분 안에 보냈다(또는 되돌리지 못한 예약이 남았다): 표시가 남아 있으면 그때 다시 판단한다(앞에 둔 채 다음 연결을 기다리지 않게).
            val s = state.value
            if (s.role == Role.OWNER && s.stateRequestNeeded) s.lastStateRequestAt?.let { retryStateAt(c, it + STATE_REQUEST_GAP_MS) }
            return
        }
        // 예약을 저장하는 사이 다른 메시지가 429를 받았으면 보내지 않는다: 예약을 되돌려 10분 제한에 걸리지 않게 하고, 끝나면 다시 판단한다.
        // 되돌리지 못했으면 다시 판단할 때 위의 10분 예약으로 이어진다.
        holdUntil(now())?.let { until ->
            commit(c) { if (it.lastStateRequestAt == at) it.copy(lastStateRequestAt = before) else it }
            return retryStateAt(c, until)
        }
        // 보내기와 결과 처리는 모델 범위에서 끝낸다: 화면이 뒤로 가 연결 루프가 취소돼도 429 기다림을 잃지 않게.
        scope.launch { sendStateRequest(c, gen) }.join()
    }

    private suspend fun sendStateRequest(c: Credentials, gen: Int) {
        when (val r = relay.send(c.token, envelope("state_request", buildJsonObject {}, now()))) {
            is Res.Ok -> if (r.status == 201) commit(c) { it.stateRequestDone(gen) }
            is Res.Err -> when (r.status) {
                401 -> lostConnection(c)
                // 상태 요청도 메시지 POST다: 같은 기다림에 넣어 보낼 함도 지키고, 끝나면 다시 판단한다(1.1).
                // 옛 연결의 늦은 429는 새 연결을 멈추지 않는다.
                429 -> if (isCurrent(c)) (r.retryAfterS?.times(1000) ?: backoffMs(0)).let { w ->
                    memHoldUntil = maxOf(memHoldUntil, now() + w)
                    commit(c) { it.withMessageHold(now(), w) }
                    retryStateAt(c, now() + w)
                }
            }
            Res.Offline, Res.Unreached -> Unit // 표시를 둔 채 다음 연결 때 다시 판단한다(보낼 함에 넣지 않는다)
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
                Res.Offline, Res.Unreached -> return Sync.FAILED
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
        // 4001·업그레이드 401은 콜백에서 바로 모델 범위로 넘긴다. 받은 함 처리 중이거나 화면이 뒤로 가 이 루프가 취소돼도 잃지 않는다(7.1).
        fun lostIf(code: Int) {
            if (code == 4001 || code == 401) scope.launch { lostConnection(c) }
        }
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
                lostIf(code)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closeCode = response?.code ?: -1
                frames.close()
                lostIf(closeCode)
            }
        })
        socket = ws
        var liveFrom = Int.MAX_VALUE
        var lastPong = now()
        // 앞에 있고 소켓이 붙어 있는 동안 60초마다 기기 목록만 다시 받아 차 타일을 맞춘다(설계값). 소켓과 같이 끝난다.
        val devicePoll = scope.launch {
            while (isActive) {
                delay(DEVICE_POLL_MS)
                // 목록을 받았으면 상태 요청 표시도 다시 판단한다(7.4 '목록 성공 뒤'): 앞 판단이 목록 실패로 끊겼어도 이어진다.
                when (fetchDevices(c)) {
                    null -> return@launch
                    true -> requestState(c)
                    false -> Unit
                }
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
                        ui.update { it.copy(socket = true) }
                        observedBySocket(c, true)
                        stillPaired(c)
                        // 소켓으로 들어온 프레임은 모두 실시간이다(iOS와 같음). 동기화 중 온 새 인계 알림도 배너가 뜬다.
                        // 받은 함에서 이미 저장한 id는 receive가 processed로 거른다. 실패하면 끊고 기다림 뒤 다시 붙는다.
                        liveFrom = index + 1
                        if (syncInbox(c) != Sync.DONE) break
                        onOpen() // 받은 함까지 저장했을 때만 기다림을 처음으로(저장이 계속 실패하면 늘어난 간격으로 다시 붙는다, iOS와 같음)
                        pumpOutbox()
                    }
                    "pong" -> {
                        lastPong = now()
                        observedBySocket(c, true) // 소켓이 살아 있으면 중계에 닿는다: 앞서 ack 등 HTTP 실패로 켠 오프라인 띠를 끈다(9장)
                    }
                    else -> {
                        observedBySocket(c, true)
                        if (!handleFrame(c, ws, text, live = index >= liveFrom)) break // 저장 실패: 닫고 재접속·받은 함으로 다시 받는다
                    } // 저장 실패: 닫고 재접속·받은 함으로 다시 받는다
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

    /** 기기 목록 요청을 시작한 순번과 마지막으로 적용한 순번. */
    private val devicesTicket = java.util.concurrent.atomic.AtomicLong()
    /** 마지막으로 적용한 목록의 (snapshot_seq, 요청 순번). 순번은 같은 snapshot_seq 안에서만 비교한다. */
    @Volatile private var appliedDevices = -1L to 0L

    /** 차의 실시간 봉투를 적용한 횟수와 그 시각. 기기 목록을 기다리는 사이 받았는지 본다. */
    private val carLiveSeen = AtomicInteger(0)
    @Volatile private var lastCarLiveAt: Long? = null

    /** 앞에 있을 때 푸시가 온 메시지 id. 본문이 지금 자격·같은 차로 들어오면 그때 배너로 띄운다. */
    private val pendingPush: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 받은 봉투를 저장한다. 같은 id는 한 번만 띄운다(5.6). 푸시가 먼저 온 id는 받은 함에서 꺼내도 배너로 띄운다.
     * 차 id 확인은 [receive]가 한다. 저장이 확인되면 참.
     */
    private suspend fun store(c: Credentials, env: Envelope, live: Boolean): Boolean {
        var banner: StoredAlert? = null
        val isLive = live || env.id in pendingPush
        val ok = commit(c) { s ->
            s.receive(env, now(), isLive).also { r ->
                banner = r.banner
                // 늦게 온 기기 목록이 이 관찰(차가 지금 켜져 있음)을 덮지 않게 센다(7.2, iOS `carLiveSeen`과 같음).
                // 잠금 안에서 세야 그 목록의 병합(같은 잠금)이 틈 없이 본다.
                if (isLive && env.from?.role == Role.CAR && r.state !== s) {
                    lastCarLiveAt = now()
                    carLiveSeen.incrementAndGet()
                }
            }.state
        }
        if (ok) banner?.let { b -> ui.update { it.copy(banner = b.body.text, bannerAlertId = b.id) } }
        // 다른 owner 폰에서 지운 곡(song_status removed)의 녹음 파일도 지움 상태를 저장한 뒤 정리한다(7.4).
        if (ok && env.type == "song_status") cleanSongFiles()
        if (ok) pruneDropNotices()
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
        if (ok) pruneDropNotices()
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

    /**
     * 오프라인 띠의 '다시 시도'(7.2): 연결 루프의 기다림을 건너뛰어 다시 붙고, 중계에 한 번 물어 닿으면 띠를 끈다(iOS `refresh`와 같은 뜻).
     * 보낼 함의 기다림(1·2·4…초, 429 Retry-After)은 건드리지 않는다.
     */
    fun retryNow() {
        kick.trySend(Unit)
        startConnection()
        pumpOutbox()
        // 기기 목록은 owner만 볼 수 있다(guardian은 403): guardian은 다시 붙기(소켓이 열리면 온라인)에 맡긴다. 거듭 눌러도 한 번만 묻는다.
        val c = creds.value?.takeIf { it.role == Role.OWNER } ?: return
        if (retryCheck?.isActive == true) return
        retryCheck = scope.launch { relay.devices(c.token) } // 닿으면 관찰(`observed`)이 온라인으로 바꾼다. 401은 콜백이 맡는다
    }

    private var retryCheck: Job? = null

    private suspend fun pumpOnce() {
        var refreshed: String? = null // 보내기 직전 재계산은 항목마다 한 번(시계가 뒤에 있는 동안 바꾸기만 되풀이하지 않게)
        while (true) {
            val c = creds.value ?: return
            // 보내지 않고 멈춘 요약의 '보내 봄'을 걷지 못했다: 걷을 때까지 아무것도 보내지 않는다(그 요약을 보낸 것처럼 보내지 않게).
            untryPending?.let { id ->
                if (!commit(c) { it.untried(id) }) return retrySaveLater()
                untryPending = null
            }
            // 맨 앞이 만료면 정리(만료 표시 포함)를 먼저 저장한다. 저장하지 못하면 멈춘다(다음 신호 때 다시).
            if (state.value.frontExpired(now()) && !commitPruned(c)) return retrySaveLater()
            // 메시지 429면 Retry-After까지 아무것도 보내지 않는다. 그 항목을 지우거나 바꿔도, 다시 켜도 지킨다(1.1).
            holdUntil(now())?.let { return wakeAt(it) }
            val item = state.value.nextToSend(now())
            if (item?.envelope?.type == "care_summary" && refreshed != item.envelope.id) {
                // 보내기 직전에 다시 계산한다: 오래 기다린 요약의 as_of·'오늘' 값이 낡지 않게(자정을 넘긴 복구, 그사이 지운 기록).
                // 같은 날 같은 내용이면 그대로(같은 id). 바꿨으면 처음부터 다시 고른다. 저장하지 못하면 옛 요약을 보내지 않는다(iOS와 같음).
                // 요약만 다시 계산한다: 수유 보내기는 정해진 네 계기에서만(R6).
                if (!commit(c) { it.summaryReconciled(now()) }) return retrySaveLater()
                refreshed = state.value.outbox.firstOrNull { it.envelope.type == "care_summary" }?.envelope?.id
                if (refreshed != item.envelope.id) continue
            }
            if (item == null) return wakeAt(state.value.outbox.firstOrNull()?.nextTryAt ?: return)
            // 요약은 보내기 전에 '보내 봄'을 저장한다(U57): 응답 전에 죽거나 결과 저장이 실패해도 바꿔치우지 않는다.
            var marked = false
            if (item.envelope.type == "care_summary" && !item.sentOnce) {
                var present = false
                if (!commit(c) { s -> present = s.outbox.any { it.envelope.id == item.envelope.id }; s.markSentOnce(item.envelope.id) }) return retrySaveLater()
                if (!present) continue // 그사이 지워졌다(기록 지우기 등): 다시 고른다
                marked = true
            }
            // 위 저장을 기다리는 사이 다른 메시지(상태 요청 등)가 429를 받았을 수 있다: 보내기 바로 앞에서 다시 본다(1.1).
            // 방금 적은 '보내 봄'은 걷는다: 보내지 않았으니 기다리는 사이 기록을 지우면 이 요약도 빠져야 한다(R10).
            holdUntil(now())?.let { h ->
                if (marked && !commit(c) { it.untried(item.envelope.id) }) untryPending = item.envelope.id
                return wakeAt(h)
            }
            sending = item.envelope.id
            try {
                val r = sendItem(c, item)
                if (!isCurrent(c)) return // 이전 연결의 응답은 버린다
                if (r is SendResult.Failed && r.status == 401) return lostConnection(c)
                // 메시지 POST의 429는 저장하지 못해도 Retry-After를 지킨다(상태에도 같은 저장으로 적는다).
                if (r is SendResult.Failed && r.status == 429 && item.posting) {
                    memHoldUntil = maxOf(memHoldUntil, now() + (r.retryAfterS?.times(1000) ?: backoffMs(item.attempts))) // 더 늦은 끝을 남긴다
                }
                // 저장하지 못했으면 잠시 뒤 다시 한다. 같은 id로 다시 보내므로 중계에서는 한 번만 처리된다.
                if (!commit(c) { it.afterSend(item, r, now(), aheadMs = -(relay.clockSkewMs ?: 0)) }) return retrySaveLater()
            } finally {
                sending = null
            }
            showDrops() // 영구 실패로 버렸으면 afterSend가 같은 저장에 적었다
        }
    }

    /**
     * 기록 지우기: `upTo`(누른 시각)까지 쓰인 임시 녹음(`cache/recording-*`, 보내다 실패해 남은 것)을 지운다. 그 뒤 시작한 새 녹음은 남긴다.
     * 다 지웠거나 지울 것이 없으면 참. 폴더를 못 읽거나 하나라도 못 지우면 거짓(지우기 표시가 남아 다음에 다시 한다).
     */
    private suspend fun removeTempRecordings(upTo: Long?): Boolean = withContext(Dispatchers.IO) {
        if (upTo == null) return@withContext true
        val files = app.cacheDir.listFiles { f -> f.name.startsWith("recording-") } ?: return@withContext false
        files.filter { it.lastModified() <= upTo }.map { it.delete() }.all { it }
    }

    /** 뒤에서는 타이머를 걸지 않는다. 앞으로 올 때(연결 루프) 이어 보낸다(7.5). */
    private fun wakeAt(at: Long) {
        wake?.cancel()
        if (!visible) return
        wake = scope.launch {
            delay(at - now())
            pumpOutbox()
        }
    }

    /** 저장하지 못한 메시지 429 기다림의 끝. 저장된 것(`PhoneState.messageHoldUntil`)과 함께 본다. 연결이 바뀌면 지운다. */
    @Volatile private var memHoldUntil = 0L

    /** 지금 메시지 429 기다림이 끝나는 시각. 없으면 null. */
    private fun holdUntil(now: Long): Long? = maxOf(memHoldUntil, state.value.messageHoldUntil ?: 0).takeIf { it > now }

    /** 저장에 실패했다: 잠시 뒤(429 기다림이 있으면 그 뒤) 다시 깨운다(iOS `retrySaveLater`와 같음). */
    private fun retrySaveLater() = wakeAt(maxOf(now() + 5_000, holdUntil(now()) ?: 0))

    /** 429 기다림 때문에 미룬 상태 요청: 끝나면 목록 확인부터 다시 판단한다(7.4 '목록 성공 뒤'). */
    private var stateRetry: Job? = null
    private fun retryStateAt(c: Credentials, at: Long) {
        stateRetry?.cancel()
        stateRetry = scope.launch {
            delay(at - now())
            if (isCurrent(c) && visible) refreshDevices(c)
        }
    }

    /** 지금 보내는 보낼 함 항목 id. 만료 정리가 결과를 기다리게 한다(보내는 사이 7일을 넘겨도 성공을 실패로 적지 않게). */
    @Volatile private var sending: String? = null

    /** '보내 봄'을 적었지만 보내지 않고 멈췄는데 걷는 저장이 실패한 요약 id(보내기 일꾼만 읽고 쓴다). */
    private var untryPending: String? = null

    /** 7일 지난 것을 정리해 저장하고(7.5), 그때 버린 메시지를 띠로 알린다(U54). 저장하지 못하면 거짓. */
    private suspend fun commitPruned(c: Credentials, then: (PhoneState) -> PhoneState = { it }): Boolean {
        if (!commit(c) { then(it.pruned(now(), sending)) }) return false
        showDrops()
        return true
    }

    /** U54: 저장해 둔 버린 메시지 안내를 띠에 더한다. 떠 있는 안내는 덮지 않고, 같은 글은 한 번. */
    /** 뒤늦게 차 반영이 확인돼 버림 표시에서 걷은 수유가 있으면, 남은 수유 버림이 없을 때 띠의 그 줄을 지운다(U54). */
    private fun pruneDropNotices() {
        val line = dropNotice("feeding_started") ?: return
        if (state.value.unseenDrops.any { (id, type) -> type == "feeding_started" && id !in dismissedDrops }) return
        ui.update { u -> u.copy(notice = u.notice?.lines()?.filter { it != line }?.joinToString("\n")?.ifEmpty { null }) }
    }

    private fun showDrops() = ui.update { u ->
        u.copy(notice = withDropNotices(u.notice, state.value.unseenDrops.filterKeys { it !in dismissedDrops }.values.toList()))
    }

    /** 사용자가 닫은 버림(메시지 id). 지우는 저장을 기다리는 사이 다른 버림의 저장이 끝나도 다시 띄우지 않는다. */
    private val dismissedDrops: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private suspend fun sendItem(c: Credentials, item: OutboxItem): SendResult {
        if (item.songFile != null && !item.uploaded) {
            val file = File(songsDir, item.songFile)
            if (!file.exists()) return SendResult.Failed(415, "file_missing")
            return when (val r = relay.putSong(c.token, item.songId!!, file)) {
                is Res.Ok -> SendResult.Uploaded
                is Res.Err -> if (r.code == "already_exists") SendResult.Uploaded else SendResult.Failed(r.status, r.code, r.retryAfterS)
                Res.Offline, Res.Unreached -> SendResult.Offline
            }
        }
        return when (val r = relay.send(c.token, item.envelope)) {
            is Res.Ok -> SendResult.Sent(r.value.body)
            is Res.Err -> SendResult.Failed(r.status, r.code, r.retryAfterS)
            Res.Offline -> SendResult.Offline
            Res.Unreached -> SendResult.Unreached
        }
    }

    // ---------------------------------------------------------------- 사용자 동작

    /** 보내는 중인 녹음 원본과, 그사이 시트가 닫혀 보내기가 끝나면 지울 원본. */
    private val sendingRecordings = mutableSetOf<File>()
    private val orphanRecordings = mutableSetOf<File>()

    /** 앞 프로세스가 남겨 시작 때 지울 임시 녹음인가(복원된 녹음 시트는 이 이름을 다시 쓰지 않는다). */
    fun isLeftoverRecording(recording: File): Boolean = recording in leftoverRecordings

    /** 녹음 시트의 보내기 결과(녹음 파일 경로별). 화면을 다시 만들어도 새 시트가 같은 보내기를 이어 본다. `error`가 null이면 성공. */
    data class SongSend(val busy: Boolean, val error: String? = null)

    val songSends = MutableStateFlow<Map<String, SongSend>>(emptyMap())

    /** 녹음을 차로 보낸다(모델 범위). 같은 녹음을 보내는 중이면 다시 하지 않는다. 결과는 [songSends]로 본다. */
    fun sendRecording(recording: File, name: String, durationS: Double, replacing: String? = null) {
        val key = recording.path
        var started = false
        songSends.update { if (it[key]?.busy == true) it else it + (key to SongSend(busy = true)).also { started = true } }
        if (!started) return
        scope.launch {
            val err = addSong(recording, name, durationS, replacing)
            // 그사이 시트가 닫혔으면 결과를 남기지 않는다(받을 시트가 없어 쌓이기만 한다). 실패였으면 원본도 지웠으니 띠로 한 번 알린다.
            val dropped = abandonedSends.remove(key)
            songSends.update { if (dropped) it - key else it + (key to SongSend(busy = false, error = err)) }
            if (dropped && err != null) ui.update { it.copy(notice = withNoticeLine(it.notice, "노래를 보내지 못했어요. 다시 녹음해 주세요.")) }
        }
    }

    /** 보내는 중에 시트가 닫힌 녹음(경로). 메인 흐름에서만 읽고 쓴다. */
    private val abandonedSends = mutableSetOf<String>()

    /** 화면을 다시 만들며 복원된 녹음 시트가 그 녹음을 다시 맡는다: 앞 화면이 닫히며 버린 보내기 결과·원본 지우기를 되돌린다. */
    fun adoptRecording(recording: File) {
        abandonedSends -= recording.path
        synchronized(sendingRecordings) { orphanRecordings.remove(recording) }
    }

    /** 시트가 보내기 결과를 받았다. */
    fun consumeSongSend(recording: File) = songSends.update { it - recording.path }

    /**
     * 녹음 시트가 닫혔다: 원본(`cache/recording-*`)은 보내기가 끝나면(성공이면 이미 지웠다), 보내는 중이 아니면 바로 지운다.
     * 시트의 코루틴은 닫힐 때 취소되므로 실패한 보내기의 원본을 시트가 지울 수 없다(7.4 '녹음 파일').
     */
    fun releaseRecording(recording: File) {
        // 시트가 닫혔다: 그 녹음의 보내기 결과를 받을 곳이 없다. 끝난 결과는 지우고, 도는 보내기는 끝나도 남기지 않게 적는다.
        if (songSends.value[recording.path]?.busy == true) abandonedSends += recording.path
        songSends.update { m -> if (m[recording.path]?.busy == true) m else m - recording.path }
        val now = synchronized(sendingRecordings) { (recording !in sendingRecordings).also { if (!it) orphanRecordings += recording } }
        if (now) scope.launch(Dispatchers.IO) { recording.delete() }
    }

    /**
     * 녹음을 보낼 함에 넣는다. 원본은 복사본이 목록·보낼 함에 커밋된 뒤에만 지운다.
     * 실패하면 원본을 그대로 두고 문구를 돌려준다(화면은 성공했을 때만 시트를 닫는다). 화면이 사라져도 모델 범위에서 끝난다.
     */
    suspend fun addSong(recording: File, name: String, durationS: Double, replacing: String? = null): String? = scope.async {
        synchronized(sendingRecordings) { sendingRecordings += recording }
        try { sendSong(recording, name, durationS, replacing) } finally {
            val orphan = synchronized(sendingRecordings) { sendingRecordings -= recording; orphanRecordings.remove(recording) }
            if (orphan) withContext(Dispatchers.IO + NonCancellable) { recording.delete() }
        }
    }.await()

    /** 기록 지우기를 누른 횟수. 노래 보내기가 시작할 때 보고 커밋할 때 다시 본다. */
    private val clearGen = AtomicInteger(0)

    private suspend fun sendSong(recording: File, name: String, durationS: Double, replacing: String?): String? {
        val c = creds.value ?: return "연결이 끊겼어요. 다시 연결해 주세요."
        val gen = clearGen.get()
        var added = false
        var full = false
        val songId = ulid()
        val file = File(songsDir, "$songId.m4a")
        val err = fileLock.withLock {
            val copied = withContext(Dispatchers.IO) { runCatching { recording.copyTo(file, overwrite = true) }.isSuccess }
            when {
                !copied -> "녹음을 저장하지 못했어요. 다시 보내 주세요."
                // 그사이 기록을 지웠으면 노래를 되살리지 않는다(7.4).
                // 넣은 뒤에 기록을 지웠으면 파일은 남긴다: 상태에서 노래가 빠진 뒤 기록 지우기의 정리가 지운다.
                // '다시 녹음'이면 그 곡의 지움과 새 곡을 한 저장에 넣는다(지움이 먼저, 9장). 3곡이 찼으면(그사이 다른 폰이 더함) 넣지 않는다(iOS와 같음).
                commit(c) { s ->
                    when {
                        clearGen.get() != gen -> s
                        s.songsFull(s.replaceable(replacing)) -> s.also { full = true } // 바꿀 곡은 저장하는 지금 다시 본다
                        else -> s.replacedSong(replacing, songId, name, durationS, file.name, now()).also { added = true }
                    }
                } && added -> null
                full -> {
                    withContext(Dispatchers.IO) { file.delete() }
                    "노래는 3곡까지예요. 한 곡을 지운 뒤 보내 주세요."
                }
                else -> {
                    withContext(Dispatchers.IO) { file.delete() }
                    "녹음을 저장하지 못했어요. 녹음은 그대로 있어요. 다시 보내 주세요."
                }
            }
        }
        if (err == null) {
            withContext(Dispatchers.IO) { recording.delete() }
            if (replacing != null) cleanSongFiles() // 바꾼 옛 곡의 파일은 저장이 끝난 뒤 지운다
            pumpOutbox()
        }
        return err
    }

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
        val pressedAt = now() // 누른 순간: 잠금을 기다리는 사이 새로 한 녹음은 지우지 않게
        val shown = care.value.events.mapTo(HashSet()) { it.id } // 누를 때 보이던 기록은 시각과 상관없이 지운다
        clearGen.incrementAndGet() // 이 전에 시작한 노래 보내기는 지운 뒤 노래를 되살리지 않는다
        scope.launch {
            val c = creds.value
            // 돌봄 기록은 이 순간의 id만 지운다(R10). 연결이 없거나 자격을 못 읽었어도 이 폰의 상태 파일·녹음은 지운다(7.4).
            // 앞선 돌봄 저장이 끝난 뒤(같은 잠금) 누른 시각까지 만든 기록을 지울 대상으로 정한다: 저장 중이던 기록도 지운다.
            // 지울 표시를 저장할 때까지 돌봄 잠금을 쥔다: 그 사이 수유 옮기기(R11)의 저장이 끼어 옛 수유를 넣으면 지울 id에 없어 남는다.
            // 표시 뒤의 옮기기는 지울 id가 있는 저장(`withoutCleared`)이라 옮기지 않는다. 잠금 순서는 careLock → lock 하나뿐이다.
            // 연결과 따로 최신 상태에 적용한다: 끊기와 겹쳐 자격이 바뀌어도 지우기가 조용히 취소되지 않게.
            val marked = careLock.withLock {
                val ids = care.value.idsToClear(shown, pressedAt)
                commitLocal { it.clearedRecords(ids, at = pressedAt, sending = sending) }
            }
            if (!marked) return@launch
            ui.update { it.copy(banner = null, bannerAlertId = null) } // 지운 알림 본문·상세로 가는 '보기'를 막는다
            pruneDropNotices() // 지운 수유의 버림 안내 줄도 띠에서 걷는다
            withContext(Dispatchers.IO) { store.backup.delete() } // 사용자가 기록을 지웠으니 옮겨 둔 상태도 지운다
            cleanSongFiles()
            finishCareClear() // 위 저장의 지울 id를 care.json에서 빼고, 사본·누른 때까지의 임시 녹음·CSV를 지우고, 빈 요약을 보낸다
            if (c != null && isCurrent(c)) {
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
    /** `resetOffer`: 연결 정보 읽기가 거듭 실패해 지우고 새로 연결할지 물을 때(사용자 결정 11-07). */
    data class PairRun(val code: String, val busy: Boolean, val error: String? = null, val role: Role? = null, val resetOffer: Boolean = false)

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
        pairGen++
        if (disconnectJob?.isActive == true) pairDuringLeave = true
        pairReplacing = creds.value != null
        pairJob = scope.launch {
            // 시작할 때 자격을 읽지 못했으면 연결 전에 다시 읽는다(G16·U49 끊기 → 연결).
            when (rereadCreds()) {
                Reread.FOUND -> {
                    // 옛 연결이 있었다: 수신을 다시 켜고, 확인 화면이 "끊고 연결하기"로 바뀌어 동의를 다시 받게 멈춘다.
                    // 시작 때 자격을 못 읽어 건너뛴 수유·요약 판단도 한다(R6·R19 앱 시작 계기).
                    startConnection()
                    reconcileAtStart()
                    pairRun.value = PairRun(code, busy = false, error = "지금 연결된 차가 있어요. 끊고 연결할지 다시 눌러 주세요.")
                    return@launch
                }
                Reread.FAILED -> {
                    // 옛 연결을 끊지 못한 채 덮어쓰지 않는다(중계에 옛 기기가 남는다). 거듭 실패하면(보안 저장소 고장) 그 정보를 지우고
                    // 새로 연결할지 묻는다: 앱 데이터를 지우지 않고도(돌봄 기록을 잃지 않고) 다시 연결할 길(11-07 사용자 결정).
                    rereadFailures++
                    pairRun.value = PairRun(code, busy = false, error = "지금 연결 정보를 읽지 못했어요. 앱을 다시 켠 뒤 해 주세요.", resetOffer = rereadFailures >= REREAD_LIMIT)
                    return@launch
                }
                Reread.NONE -> Unit
            }
            // 설정 끊기가 아직 도는 중이면(메모리 자격은 먼저 비지만 비밀값 정리는 뒤에 끝난다) 그 끝을 기다린 뒤 끊기 표시를 지운다.
            var err = if (creds.value != null || leavingRun?.isActive == true) disconnectNow() else null
            lostLock.withLock { } // 시작 때 이어 하는 끊김 정리(`lostConnection`)도 비밀값까지 지울 때까지 기다린다
            // 끊고 연결하기: 이 뒤는 연결 결과가 화면을 맡는다. 옛 비밀값을 못 지웠으면 다음 실행이 마치게 남긴다(새 자격을 저장하면 그때 덮인다).
            if (!secretsLeft) prefs.edit().remove(LEAVING).apply()
            // 끊기는 실패했지만 그사이 받은 401·4001로 GONE으로 갔다. GONE 화면이 알리므로 옛 결과를 남기지 않는다.
            if (err != null && creds.value == null) {
                pairRun.value = null
                return@launch
            }
            if (err == null) err = pair(code)
            val lock = pairRetryAfterS.getAndSet(-1L).takeIf { it >= 0 }?.let { pairLockUntil(now(), it) }
            if (lock != null) pairLockedUntil.value = lock
            pairRun.value = PairRun(code, busy = false, error = err, role = if (err == null) creds.value?.role else null)
        }
    }

    /**
     * 시작할 때 자격을 읽지 못했으면 연결 전에 다시 읽는다: 읽히면 옛 연결을 먼저 끊는다(G16·U49). 계속 못 읽으면 중계의 옛 기기 행을 지울 수 없다
     * (차 화면에서 지운다).
     */
    private suspend fun rereadCreds(): Reread {
        if (creds.value != null || !credsUnresolved) return Reread.NONE
        val read = withContext(Dispatchers.IO) { runCatching { secrets.load() } }
        val c = read.getOrElse { return Reread.FAILED }
        if (c == null) return Reread.NONE.also { credsUnresolved = false }
        // 시작할 때와 같은 복구를 저장한 뒤에 자격을 공개한다: 상태에 차 id가 없으면 받은 것을 저장하지 않고 ack해 잃는다(1.4).
        val ok = lock.withLock {
            if (_creds.value != null) return@withLock true
            val rec = PhoneState.recoverIfNeeded(_state.value, c.carId, c.deviceId, c.role, now())?.keepingClear(_state.value, migrating())
            if (rec != null) {
                if (!withContext(Dispatchers.IO) { saved(rec) }) return@withLock false
                _state.value = rec
            }
            _creds.value = c
            true
        }
        if (!ok) return Reread.FAILED
        credsUnresolved = false
        // 시작 때 못 읽어 건너뛴 표시를 본다: 이 자격이 이미 강제 끊김(GONE)·사용자 끊기를 받았으면 옛 연결로 되살리지 않고 정리를 마친다.
        // 새 연결이 도는 중이라 GONE 화면은 켜지 않는다(`lostConnection`). 끊기 표시만 있으면 확인 화면의 '끊고 연결하기'가 맡는다.
        if (prefs.getString(GONE, null) == c.deviceId || (leavingId() == c.deviceId && leavingDone())) {
            lostConnection(c)
            if (creds.value == null) return Reread.NONE
        }
        return Reread.FOUND
    }

    private enum class Reread { NONE, FOUND, FAILED }

    /** 이 실행에서 연결 전 다시 읽기가 연달아 실패한 횟수. */
    private var rereadFailures = 0

    /**
     * 읽지 못하는 연결 정보를 지우고 그 코드로 새로 연결한다(사용자가 대화에서 동의). 돌봄 기록·이 폰의 상태는 그대로이고,
     * 옛 연결은 중계에 남으니 화면이 차에서 지우라고 알린다. 지우지 못하면 연결하지 않는다.
     */
    fun resetCredsAndPair(code: String) {
        if (pairJob?.isActive == true) return
        pairRun.value = PairRun(code, busy = true)
        scope.launch {
            if (!withContext(Dispatchers.IO) { secrets.reset() }) {
                pairRun.value = PairRun(code, busy = false, error = SAVE_FAILED)
                return@launch
            }
            credsUnresolved = false
            rereadFailures = 0
            pairRun.value = null
            startPair(code)
        }
    }

    /** 시작할 때 자격을 읽지 못했고 아직 다시 읽어 확정하지 못했다. */
    @Volatile private var credsUnresolved = credsRead.isFailure

    /** 연결을 시작한 횟수(메인 흐름). 끊김 정리를 하는 사이 새 연결을 시작했는지(이미 끝났어도) 본다. */
    private var pairGen = 0

    /** 지금 도는 연결이 옛 연결을 끊고 바꾸는 것인가(확인 화면의 '끊고 연결하기'). */
    @Volatile private var pairReplacing = false

    /** U52: 지금 새 연결 링크·QR을 받을 수 있는가. 받은 순간과 화면이 처리할 때 모두 이것을 본다. */
    fun canTakeLink(): Boolean = pairRun.value.let { canOpenConfirm(pairBusy = it?.busy == true, doneUnshown = it?.role != null) }

    /**
     * 화면이 끝난 연결 결과로 완료 화면에 가기 직전에 부른다. 지금 연결의 성공일 때만 결과를 비우고 참.
     * 그사이 401·4001로 끊겼으면(GONE) 거짓이라 GONE 화면을 덮지 않는다(7.1, U25).
     */
    fun takePairDone(run: PairRun): Boolean {
        if (pairRun.value != run || run.busy || run.error != null || !pairDoneValid(run.role, ui.value.gone, creds.value?.role)) return false
        pairRun.value = null
        return true
    }

    fun clearPairRun() {
        if (pairJob?.isActive != true) pairRun.value = null
    }

    /** 지금 연결의 완료 화면을 아직 넘기지 않았으면 그 역할(앞 프로세스가 완료 화면 전·중에 죽음). 시작 화면을 정할 때 본다. */
    fun pendingDoneRole(): Role? = creds.value?.takeIf { prefs.getString(PAIR_DONE, null) == it.deviceId && !ui.value.gone }?.role

    private var doneRestoreChecked = false

    /**
     * 새 프로세스의 첫 화면에서만: 저장된 화면 이력이 복원돼 시작 화면을 쓰지 않았어도 완료 화면으로 보낼 역할.
     * 같은 프로세스에서 화면만 다시 만들면(회전) null이다: 그사이 받은 새 링크의 확인 화면을 지우지 않게(U52).
     */
    fun takeRestoredDone(): Role? = if (doneRestoreChecked) null else pendingDoneRole().also { doneRestoreChecked = true }

    /** 완료 화면을 넘겼다(알림 권한 답 뒤). */
    /** 바로 쓴다: 넘긴 뒤 곧 죽어도 완료 화면이 다시 뜨지 않게. 저장하지 못했으면 거짓(화면은 그대로 두고 알린다). */
    fun pairDoneShown(): Boolean {
        val id = prefs.getString(PAIR_DONE, null)
        if (prefs.edit().remove(PAIR_DONE).commit()) return true
        // 실패한 commit도 메모리 값은 지웠다: 되돌려 회전해도 완료 화면에 남게 한다(디스크에는 아직 있다).
        prefs.edit().putString(PAIR_DONE, id).apply()
        return false
    }

    /**
     * 새로 연 화면에서: 끝난 결과 중 지금 연결의 성공은 남겨 `Root`가 완료 화면(guardian 알림 권한 단계)으로 보내게 하고,
     * 실패·지난 결과만 비워 새 링크를 막지 않는다(U52, 7.1).
     */
    fun clearStalePairRun() {
        val r = pairRun.value ?: return
        if (pairJob?.isActive == true) return
        if (r.error != null || !pairDoneValid(r.role, ui.value.gone, creds.value?.role)) pairRun.value = null
    }

    /**
     * 중계에 새 기기를 만들었지만 이 폰에 저장하지 못했다: 받은 토큰으로 그 기기를 바로 지운다(1.4. 남으면 차의 기기 목록에 쓸모없는
     * 연결이 남고 guardian이면 인계 알림 푸시도 간다). 지우지 못하면 차 화면에서 지울 수 있다. 화면 문구는 저장 실패 그대로다.
     */
    private suspend fun discardPaired(c: Credentials): String {
        prefs.edit().remove(PAIR_DONE).apply()
        val removed = relay.deleteMe(c.token).let { it is Res.Ok || (it is Res.Err && it.status == 401) }
        // 지우지 못했으면 토큰을 남길 곳도 없다(저장이 실패했다): 차 화면에서 끊게 알린다(연결 계약 '모르는 폰은 차 화면에서 끊는다').
        return if (removed) "연결 정보를 저장하지 못했어요" else "연결 정보를 저장하지 못했어요. 차 화면에서 이 휴대폰 연결을 끊어 주세요."
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
                // 완료 화면(guardian 알림 권한, 7.1)을 보이기 전에 죽어도 다음 실행이 그 화면부터 보이게 먼저 적는다.
                if (!withContext(Dispatchers.IO) { prefs.edit().putString(PAIR_DONE, c.deviceId).commit() && secrets.save(c) }) return discardPaired(c)
                secretsLeft = false // 옛 비밀값을 새 자격이 덮었다
                if (!switchTo(c, { PhoneState.paired(c.carId, c.deviceId, c.role, now()).keepingClear(it, migrating()) })) {
                    withContext(Dispatchers.IO) { secrets.save(prev) }
                    return discardPaired(c)
                }
                // 화면·GONE 초기화는 자격을 공개한 바로 뒤, 멈추는 곳(파일 정리) 전에 한다: 그 사이 새 자격의 401·4001이 적은
                // GONE 표시를 뒤늦게 지우지 않게(iOS `pair`와 같은 순서).
                ui.update { Ui() }
                goneDevice = null
                clearDisconnectRun() // 지난 끊기 결과가 새 연결의 설정 화면을 닫지 않게
                prefs.edit().remove(GONE).apply() // `Ui()`가 GONE을 껐다
                memHoldUntil = 0L
                cleanSongFiles()
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
                // 화면 문구는 앱이 정한다(1.1): 중계의 코드는 로그에만 남긴다(iOS와 같은 글).
                else -> "연결하지 못했어요. 잠시 뒤에 다시 해 주세요.".also { Log.w(TAG, "연결 실패 ${r.status} ${r.code}") }
            }
            Res.Offline, Res.Unreached -> "인터넷에 연결한 뒤 다시 해 주세요"
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
                Result.failure(IllegalStateException(if (r.code == "invite_limit") "쓰지 않은 초대가 3개 있어요" else "초대를 만들지 못했어요. 잠시 뒤에 다시 해 주세요.".also { Log.w(TAG, "초대 실패 ${r.status} ${r.code}") }))
            }
            Res.Offline, Res.Unreached -> Result.failure(IllegalStateException("인터넷이 필요해요"))
        }
    }

    /** guardian의 '인계 알림 받기'(G12). 실패하면 문구. */
    suspend fun setHandoffAlerts(on: Boolean): String? {
        val c = creds.value ?: return null
        return when (val r = relay.patchMe(c.token, PatchDeviceRequest(handoffAlerts = on))) {
            is Res.Ok -> null.also { commit(c) { it.copy(handoffAlerts = r.value.handoffAlerts) } }
            is Res.Err -> if (r.status == 401) null.also { lostConnection(c) } else "바꾸지 못했어요. 잠시 뒤에 다시 해 주세요."
            Res.Offline, Res.Unreached -> "인터넷이 필요해요"
        }
    }

    /** 사용자가 요청한 끊기. 그 사이 소켓 4001·다른 요청의 401은 강제 해제가 아니라 이 끊기의 결과일 수 있다. */
    private val leave = LeaveWatch<Credentials>()
    private var leavingRun: Deferred<String?>? = null

    /** 설정의 '연결 끊기' 상태. 화면이 회전·재구성돼도 작업은 모델에서 끝나고, 화면은 이것만 본다. */
    data class DisconnectRun(val busy: Boolean, val error: String? = null)

    val disconnectRun = MutableStateFlow<DisconnectRun?>(null)
    private var disconnectJob: Job? = null

    /** 설정 끊기가 도는 동안 연결(확인 화면의 '연결하기')을 시작했다. 그 연결이 화면을 맡는다(이미 끝나 실패했어도). */
    @Volatile private var pairDuringLeave = false

    fun startDisconnect() {
        if (disconnectJob?.isActive == true) return
        pairDuringLeave = false
        disconnectRun.value = DisconnectRun(busy = true)
        disconnectJob = scope.launch {
            // 강제 끊김 정리(401·4001)가 돌면 끝을 기다린다. 그쪽이 자격을 지웠으면 GONE이 결과를 맡는다: 이 끊기는 할 일이 없다.
            lostLock.withLock { }
            if (creds.value == null) {
                // 이 끊기가 낸 '도는 중'만 걷는다: 그사이 다른 정리(끊기 재개)가 낸 끝남 결과는 화면이 받게 둔다.
                if (disconnectRun.value?.busy == true) disconnectRun.value = null
                return@launch
            }
            val error = disconnectNow()
            // '끊고 연결하기'가 이 끊기를 이어받았다: 확인 화면과 새 연결이 결과를 맡는다. 설정 쪽 이동·안내를 하지 않는다
            // (홈으로 돌아가면 확인 화면이 사라져 연결 완료를 받을 곳이 없다).
            if (pairDuringLeave || pairJob?.isActive == true) {
                disconnectRun.value = null
                return@launch
            }
            if (error == null && creds.value == null) afterUserDisconnect() // U25 b: 화면과 무관하게(설정 탭이 사라져도)
            // 끊기는 실패했지만 그사이 받은 401·4001로 GONE으로 갔다: GONE 화면이 알리므로 설정에 옛 오류를 남기지 않는다.
            disconnectRun.value = if (error != null && creds.value == null) null else DisconnectRun(busy = false, error = error)
        }
    }

    fun clearDisconnectRun() {
        if (disconnectJob?.isActive != true) disconnectRun.value = null
    }

    /** 설정을 떠나면 지난 끊기 실패 글을 걷는다(iOS는 화면 상태라 다시 열면 없다). 끝남 표시(오류 없음)는 탭 이동이 쓰니 둔다. */
    fun dismissDisconnectError() {
        if (disconnectJob?.isActive != true && disconnectRun.value?.error != null) disconnectRun.value = null
    }

    /**
     * 사용자 끊기가 중계에서 끝났다(U25 b가 결과를 맡는다). 그사이 같은 기기의 강제 끊김 처리(401·4001)가 켠 GONE을 걷는다.
     * 그 처리가 끝난 뒤에 보도록 같은 잠금을 쓴다(정리 중에 끊기를 시작했어도 GONE이 뒤에 켜지지 않게).
     */
    private suspend fun userLeftOver(c: Credentials) = lostLock.withLock {
        if (!secretsLeft && prefs.getString(GONE, null) == c.deviceId) prefs.edit().remove(GONE).apply()
        if (goneDevice == c.deviceId) {
            goneDevice = null
            ui.update { it.copy(gone = false) }
        }
    }

    /** 사용자 끊기를 시작한 연결의 기기 id(`leaving_device`). 값 뒤의 `|done`은 중계가 끊기를 확인했다(204·401)는 뜻이다. */
    private fun leavingId(): String? = prefs.getString(LEAVING, null)?.substringBefore('|')

    /** 중계가 사용자 끊기를 확인했다(204·401): 다음 실행은 중계에 묻지 않고 정리를 마친다. */
    private fun leavingDone(): Boolean = prefs.getString(LEAVING, null)?.endsWith(LEFT_MARK) == true

    /** 끊을 때 디스크의 비밀값을 지우지 못했다(이 프로세스). 다음 실행이 옛 자격을 다시 읽으니 끊기 표시를 지우지 않는다. 새 자격을 저장하면 거짓. */
    @Volatile private var secretsLeft = false

    /** 탭이 끊은 뒤 화면(오늘·띠)을 보였다. 그때까지 끊기 표시를 남겨, 그 전에 죽으면 다음 실행이 다시 보인다(U25 b). */
    fun disconnectShown() {
        clearDisconnectRun()
        if (creds.value == null && !secretsLeft) prefs.edit().remove(LEAVING).commit() // 바로 쓴다(위와 같은 까닭)
    }

    init {
        // 앞 프로세스가 사용자 끊기 중이거나 끊은 뒤 화면을 보이기 전에 죽었다(U25 b). 시작 화면(`Root`)을 정하기 전에 한다.
        // 중계에 묻거나 정리하는 일은 `Dispatchers.Main`(즉시 아님)으로 미뤄 생성자가 끝난 뒤 돈다: 아래에 선언한 칸(`lostLock` 등)이 아직 없다.
        // 자격을 읽지 못한 실행은 어느 쪽인지 알 수 없으니 표시를 남긴다(다음에 읽히면 본다).
        val c = _creds.value
        if (credsRead.isSuccess) leavingId()?.let { id ->
            // 같은 기기의 GONE 표시가 있으면 중계가 토큰을 지운 것이 확인됐다. 결과는 사용자 끊기(U25 b)가 맡는다.
            val goneToo = prefs.getString(GONE, null) == id
            when {
                // 중계가 끊기를 확인했다(204·401, 또는 같은 기기의 401·4001): 묻지 않고 정리를 마친다(GONE 표시는 정리 뒤 `lostConnection`이 걷는다).
                c?.deviceId == id && (leavingDone() || goneToo) -> scope.launch(Dispatchers.Main) { lostConnection(c) }
                // DELETE가 중계에 닿았는지 모른다. 다시 보내지 않고(7.5 끊기는 보낼 함에 넣지 않는다) 중계에 물어,
                // 이미 지웠으면(401) `lostConnection`이 U25 b로 마치고, 남아 있으면 표시만 지운다.
                c?.deviceId == id -> scope.launch(Dispatchers.Main) { checkLeft(c) }
                // 정리가 끝났다(자격 없음): 끊기 성공과 같은 결과를 낸다. 저장된 화면(설정 탭 등)이 복원돼도 `Root`·`CareTabs`가 탭의 오늘로 보낸다.
                c == null -> {
                    if (goneToo) {
                        prefs.edit().remove(GONE).apply()
                        goneDevice = null
                        ui.update { it.copy(gone = false) }
                    }
                    afterUserDisconnect()
                    disconnectRun.value = DisconnectRun(busy = false)
                }
                else -> prefs.edit().remove(LEAVING).apply()
            }
        }
        if (credsRead.isSuccess && c != null && leavingId() != c.deviceId) prefs.getString(GONE, null)?.let { id ->
            // 앞 프로세스가 GONE 표시를 적고 자격을 지우기 전에 죽었다: 그 강제 끊김 정리를 마친다(중계에 묻지 않아도 된다).
            if (id == c.deviceId) scope.launch(Dispatchers.Main) { lostConnection(c) } else prefs.edit().remove(GONE).apply() // 그 뒤 새로 연결했다
        }
    }

    /** 앞 프로세스가 끊기를 보낸 뒤 죽었다: 이 기기가 중계에 남았는지만 본다. 오프라인이면 표시를 두고 다음 실행에 다시 본다. */
    /**
     * 중계가 끊기를 확인했다(204·401): 정리 전에 적어, 정리 도중 죽어도 다음 실행이 중계에 묻지 않고 마치게 한다.
     * 메인 흐름에서 바로 쓴다(작은 파일): IO로 미루면 그사이 실패한 끊기가 지운 표시를 늦게 다시 쓴다.
     */
    private fun leftConfirmed(c: Credentials) {
        prefs.edit().putString(LEAVING, c.deviceId + LEFT_MARK).commit()
    }

    private suspend fun checkLeft(c: Credentials) {
        if (c.role != Role.OWNER) return // guardian은 GET /v1/devices가 403(6.3): 소켓이 붙거나(stillPaired) 401·4001이 오면 확인된다
        when (val r = relay.devices(c.token)) {
            is Res.Ok -> stillPaired(c)
            is Res.Err -> if (r.status == 401) lostConnection(c) else if (r.status < 500) stillPaired(c)
            Res.Offline, Res.Unreached -> Unit // 소켓이 붙으면(`runSocket`) 다시 확인된다
        }
    }

    /**
     * 이 연결이 중계에 남아 있다고 확인됐다(토큰이 받아들여짐): 앞 프로세스의 끊기 표시를 지운다. 그 뒤의 401·4001은 강제 끊김(GONE)이다.
     * 지금 끊는 중이거나 그사이 자격이 바뀌었으면 그 표시는 다른 끊기의 것이라 둔다.
     */
    private fun stillPaired(c: Credentials) {
        if (isCurrent(c) && leavingRun?.isActive != true && leavingId() == c.deviceId && !leavingDone()) prefs.edit().remove(LEAVING).apply()
    }

    /** 연결 끊기: 중계의 토큰을 지워야 해서 인터넷이 필요하다(7.2). 성공하면 null. */
    private suspend fun disconnectNow(): String? {
        // 설정의 끊기와 '끊고 연결하기'의 끊기가 겹치면 뒤에 온 쪽은 앞의 결과를 기다린다(앞 끊기가 적어 둔 401·4001을 지우지 않게).
        leavingRun?.takeIf { it.isActive }?.let { return it.await() }
        return scope.async { leaveOnce() }.also { leavingRun = it }.await()
    }

    private suspend fun leaveOnce(): String? {
        val c = creds.value ?: return null
        leave.start(c)
        // 중계가 지운 뒤 끊은 뒤 화면을 보이기 전에 죽어도 다음 실행이 U25 b로 마치게 적는다. 적지 못하면 끊지 않는다. 실패로 끝나면 지운다.
        // 메인 흐름에서 바로 적는다(작은 파일): `leave.start`와 표시 사이에 401·4001이 끼지 않아, 흡수한 것은 늘 표시가 있을 때
        // '확인됨'으로 적힌다(`absorbed`).
        if (!prefs.edit().putString(LEAVING, c.deviceId).commit()) {
            prefs.edit().remove(LEAVING).commit() // commit이 실패해도 메모리 값은 바뀌었다: 뒤의 401이 사용자 끊기로 보이지 않게
            leave.end(true) // 그사이 흡수한 것은 없다(위와 같이 끼지 않음)
            return SAVE_FAILED
        }

        var failed = true
        try {
            return when (val r = relay.deleteMe(c.token)) {
                is Res.Ok -> null.also { leftConfirmed(c); wipe(c); userLeftOver(c) }
                is Res.Err -> if (r.status == 401) null.also { leftConfirmed(c); wipe(c); userLeftOver(c) } else "끊지 못했어요. 잠시 뒤에 다시 해 주세요.".also { Log.w(TAG, "끊기 실패 ${r.status} ${r.code}") }
                Res.Offline, Res.Unreached -> "인터넷이 필요해요"
            }.also { failed = it != null }
        } finally {
            // 끊기가 실패로 끝났는데 그사이 이 연결의 401·4001이 왔다: 중계 토큰은 이미 없으니 GONE으로 간다(7.1).
            // 끊기 표시 지우기와 GONE 표시를 한 편집에 적는다: 그 사이에 죽어도 다음 실행이 정리를 마친다.
            if (failed) prefs.edit().remove(LEAVING).also { if (leave.lostFor(c)) it.putString(GONE, c.deviceId) }.commit()
            if (leave.end(failed)) lostConnection(c)
        }
    }

    fun dismissBanner() = ui.update { it.copy(banner = null, bannerAlertId = null) }

    fun clearNotice() {
        val seen = state.value.unseenDrops.keys
        dismissedDrops += seen
        ui.update { it.copy(notice = null) }
        // 닫은 띠에 있던 버림(메시지 id)만 지운다(U54). 그사이 새로 버린 것은 같은 종류여도 남긴다. 저장하지 못하면 다음 실행에 다시 뜬다.
        // 연결과 따로 저장한다(자격을 못 읽은 실행에서도 닫은 것이 다음 실행에 다시 뜨지 않게).
        if (seen.isNotEmpty()) scope.launch { commitLocal { it.copy(unseenDrops = it.unseenDrops - seen) } }
    }

    /** 끊김 화면에서 골랐다. 저장하지 못했으면 거짓: 화면은 그대로 두고 저장 실패를 알린다(다시 누르면 다시 쓴다). */
    fun acknowledgeGone(recordsOnly: Boolean = false): Boolean {
        // '기록만 계속 쓸게요'는 GONE 지우기와 같은 편집에 적는다: 둘 사이에 죽어 첫 안내로 가지 않게(U25 a).
        // 옛 비밀값을 못 지웠으면 GONE 표시를 남겨 다음 실행이 그 자격을 쓰지 않고 정리를 마치게 한다.
        // 바로 쓴다(`commit`): 고른 뒤 곧 죽어도 끊김 화면이 다시 뜨지 않게.
        val before = prefs.getString(GONE, null) to prefs.getBoolean("records_only", false)
        if (!prefs.edit().also { if (!secretsLeft) it.remove(GONE) }.also { if (recordsOnly) it.putBoolean("records_only", true) }.commit()) {
            // 실패한 commit도 메모리 값은 바꿨다: 디스크(옛 값)와 맞게 되돌린다(회전·재구성 때 화면 판단이 어긋나지 않게).
            prefs.edit().putString(GONE, before.first).putBoolean("records_only", before.second).apply()
            return false
        }
        goneDevice = null
        ui.update { it.copy(gone = false) }
        return true
    }

    /** `c`가 아직 지금 자격일 때만 토큰·캐시를 지운다. 소켓 정리(취소)는 맨 뒤에 한다. */
    /**
     * 연결 끊기·401 정리. 화면 연결 루프에서 불려도 onStop의 취소가 정리를 끊지 않게 모델 범위(앱 수명)에서 끝까지 한다.
     * 부르는 쪽이 취소돼도 이 작업은 이어진다.
     */
    private suspend fun wipe(c: Credentials): Boolean = scope.async { wipeNow(c) }.await()

    private suspend fun wipeNow(c: Credentials): Boolean = transitionLock.withLock {
        // 자격 비우기부터 비밀값·녹음·소켓 정리까지 한 덩어리다. 새 연결은 이것이 끝난 뒤에 시작된다.
        // 지우다 만 '기록 지우기'는 남긴다(돌봄 기록은 연결과 따로다, R9·R10).
        if (!switchTo(null, { PhoneState().keepingClear(it, migrating()) }, onlyIf = c)) return false
        memHoldUntil = 0L // 429 기다림은 그 연결의 것이다(저장된 것은 새 상태와 함께 사라졌다)
        // 옛 연결의 띠(버림 안내 등)도 걷는다: 상태가 비어 닫아도 지울 것이 없다(iOS `wipeAll`의 `notice = nil`과 같음).
        ui.update { it.copy(banner = null, bannerAlertId = null, notice = null) }
        dismissedDrops.clear()
        if (!withContext(Dispatchers.IO) { secrets.save(null) }) {
            Log.e(TAG, "연결 비밀값을 지우지 못했어요")
            secretsLeft = true // 다음 실행이 옛 자격을 다시 읽는다: 끊기 표시를 남겨 그때 U25 b로 마치게 한다
        }
        purgeSongFiles()
        stopConnection()
        true
    }

    /** 연결 끊기·401: 지킬 기록이 없으므로 파일 잠금 안에서 백업·녹음을 모두 지운다(7.4). 정리 건너뛰기 조건을 타지 않는다. */
    private suspend fun purgeSongFiles() = fileLock.withLock {
        withContext(Dispatchers.IO) {
            // 지우다 만 '기록 지우기'가 있으면 그 표시를 담은 새 상태 파일은 남긴다(R10). 옛 차 상태가 남았어도 연결 없는 시작은 그 표시만 읽는다.
            // 판단과 삭제를 상태 저장 잠금 안에서 한다(그사이 저장된 지울 id를 지우지 않게. fileLock → lock 순서).
            // 옮기기를 마치지 못한 원본 수유 기록도 같은 이유로 남긴다(R11·U22).
            lock.withLock { store.discardRecovery(keepFile = _state.value.let { it.clearingCareIds != null || it.migrationFeedings.isNotEmpty() }) }
            songsDir.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * 어느 요청이든 401(7.1). 부른 쪽이 취소돼(화면이 뒤로 가 연결 루프가 끝남) 결과를 받지 못해도 잃지 않게 HTTP 층이 바로 넘긴다.
     * 그 토큰이 지금 자격일 때만 본다. 부른 쪽의 401 처리와 겹쳐도 [lostConnection]은 한 번만 정리한다.
     */
    private fun unauthorized(token: String) {
        scope.launch { creds.value?.takeIf { it.token == token }?.let { lostConnection(it) } }
    }

    /** 401·닫기 4001. 이전 연결의 늦은 401이면 무시한다. 사용자가 요청한 끊기 중이면 적어 두고 끊기가 끝날 때 본다. */
    private val lostLock = Mutex()

    /** 끊는 중인 자격의 401·4001이면 그 끊기에 맡긴다. 끊기 표시가 이미 적혔으면 '확인됨'으로 바꿔 정리 도중 죽어도 다음 실행이 마치게 한다. */
    private fun absorbed(c: Credentials): Boolean {
        if (!leave.absorb(c)) return false
        if (leavingId() == c.deviceId) leftConfirmed(c)
        return true
    }

    private suspend fun lostConnection(c: Credentials) {
        if (!isCurrent(c) || absorbed(c)) return
        // 같은 자격의 끊김(같은 401의 콜백과 결과 분기 등)은 하나씩 한다: 뒤에 온 것은 앞의 정리로 자격이 바뀐 것을 보고 표시를 쓰기 전에 빠진다
        // (그러지 않으면 사용자가 GONE에서 고른 뒤 옛 GONE 표시를 다시 쓴다).
        lostLock.withLock {
            // 잠금을 기다리는 사이 사용자 끊기가 시작됐으면 그 끊기가 결과를 맡는다(U25 b, 실패하면 그 끊기가 GONE으로 보낸다).
            if (!isCurrent(c) || absorbed(c)) return
            // 정리를 마친 뒤 GONE을 켠다: 새 연결 직후라면 연결 마침(`Ui()`, 연결 전환 잠금 안)이 GONE을 덮지 않게(iOS `wipeAll` 뒤 `gone`과 같음).
            // 그사이 새 연결을 시작했으면 켜지 않는다: 확인 화면이 그 결과를 맡는다(GONE이 확인 화면을 지우면 성공해도 끊김 화면에 남는다).
            // 바꿔 연결(끊고 연결하기) 중이면 그 연결은 이 끊김 때문에 멈추므로 GONE을 켠다(7.1).
            // 앞 프로세스가 보낸 사용자 끊기가 중계에 닿았다(`checkLeft`·그 뒤 401·4001): 강제 끊김이 아니라 U25 b로 마친다.
            val gen = pairGen
            if (leavingId() == c.deviceId) {
                leftConfirmed(c) // 401·4001도 끊기 확인이다: 정리 도중 죽어도 다음 실행이 묻지 않고 마친다
                if (wipe(c)) {
                    // 앞 프로세스가 비밀값을 못 지워 함께 남긴 같은 기기의 GONE 표시도 걷는다(U25 b가 결과를 맡는다).
                    if (!secretsLeft && prefs.getString(GONE, null) == c.deviceId) prefs.edit().remove(GONE).apply()
                    afterUserDisconnect()
                    // 정리하는 사이 새 연결을 시작했으면(이미 실패로 끝났어도) 확인 화면이 결과를 맡는다: 탭으로 옮기지 않는다(`startDisconnect`와 같음).
                    if (gen == pairGen && pairJob?.isActive != true) disconnectRun.value = DisconnectRun(busy = false)
                    else if (!secretsLeft) prefs.edit().remove(LEAVING).apply()
                }
                return
            }
            // 정리한 뒤 GONE 화면을 고르기 전에 죽어도 다음 실행이 GONE을 보이게 먼저 적는다(9장).
            val marked = withContext(Dispatchers.IO) { prefs.edit().putString(GONE, c.deviceId).commit() }
            // 표시를 적는 사이 사용자 끊기가 시작됐다: 위와 같이 그 끊기에 맡기고 표시를 지운다.
            if (absorbed(c)) {
                prefs.edit().remove(GONE).apply()
                return
            }
            // 거짓이면 다른 호출(같은 401의 콜백과 결과 분기 등)이 이미 정리했거나 새 연결로 바뀌었다: 그쪽 표시를 지우지 않는다.
            if (!wipe(c)) return
            if ((gen == pairGen && pairJob?.isActive != true) || pairReplacing) {
                // 저장 공간이 없어 못 적었으면 정리가 파일을 지운 뒤 한 번 더 적는다(그래도 못 적으면 남길 방법이 없다).
                // 그 사이 새 연결이 시작·완료됐으면 옛 끊김을 켜지 않는다.
                if (!marked) {
                    withContext(Dispatchers.IO) { prefs.edit().putString(GONE, c.deviceId).commit() }
                    if (creds.value != null || ((gen != pairGen || pairJob?.isActive == true) && !pairReplacing)) {
                        if (!secretsLeft) prefs.edit().remove(GONE).apply()
                        return
                    }
                }
                goneDevice = c.deviceId
                ui.update { it.copy(gone = true) }
            } else if (!secretsLeft) {
                prefs.edit().remove(GONE).apply() // 새 연결이 확인 화면에서 결과를 맡는다(옛 비밀값을 못 지웠으면 다음 실행이 마치게 남긴다)
            }
        }
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
            Res.Offline, Res.Unreached -> Unit
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
        const val LEAVING = "leaving_device" // 사용자 끊기를 시작한 연결의 기기 id(U25 b), 중계가 확인하면 뒤에 LEFT_MARK
        const val LEFT_MARK = "|done"
        const val PAIR_DONE = "pair_done_device" // 완료 화면을 아직 넘기지 않은 연결의 기기 id(7.1)
        const val REREAD_LIMIT = 3 // 연결 정보 읽기가 이만큼 연달아 실패하면 지우고 새로 연결할지 묻는다(설계값)
        const val GONE = "gone_device" // 강제 끊김(401·4001)을 정리했고 GONE 화면에서 아직 고르지 않았다(9장)
    }
}
