package kr.aigyeot.phone.di

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.navigation.toRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kr.aigyeot.phone.BuildConfig
import kr.aigyeot.phone.CareNotify
import kr.aigyeot.phone.data.AndroidLogger
import kr.aigyeot.phone.data.AndroidNetworkWatcher
import kr.aigyeot.phone.data.AppSongFileStore
import kr.aigyeot.phone.data.FcmPushTokens
import kr.aigyeot.phone.data.FileCareRepository
import kr.aigyeot.phone.data.FilePhoneStateRepository
import kr.aigyeot.phone.data.KeystoreCredentialRepository
import kr.aigyeot.phone.data.OkHttpRelayClient
import kr.aigyeot.phone.data.PrefsLinkMarks
import kr.aigyeot.phone.data.Recorder
import kr.aigyeot.phone.data.readDocument
import kr.aigyeot.phone.domain.model.CareCsv
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.Mark
import kr.aigyeot.phone.domain.repository.OngoingNotice
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.SongFileStore
import kr.aigyeot.phone.domain.state.AppVisibility
import kr.aigyeot.phone.domain.state.LinkSwitch
import kr.aigyeot.phone.domain.state.Notices
import kr.aigyeot.phone.domain.usecase.AppLifecycle
import kr.aigyeot.phone.domain.usecase.ClearRecords
import kr.aigyeot.phone.domain.usecase.Disconnect
import kr.aigyeot.phone.domain.usecase.Invite
import kr.aigyeot.phone.domain.usecase.KeepConnected
import kr.aigyeot.phone.domain.usecase.LoseConnection
import kr.aigyeot.phone.domain.usecase.Pair
import kr.aigyeot.phone.domain.usecase.ReconcileCareSummary
import kr.aigyeot.phone.domain.usecase.RecordCare
import kr.aigyeot.phone.domain.usecase.RecordSong
import kr.aigyeot.phone.domain.usecase.RegisterPush
import kr.aigyeot.phone.domain.usecase.RemoveSong
import kr.aigyeot.phone.domain.usecase.SelectSong
import kr.aigyeot.phone.domain.usecase.SendOutbox
import kr.aigyeot.phone.domain.usecase.SetHandoffAlerts
import kr.aigyeot.phone.domain.usecase.SyncInbox
import kr.aigyeot.phone.domain.usecase.UploadSong
import kr.aigyeot.phone.domain.usecase.WipeLink
import kr.aigyeot.phone.presentation.AlertViewModel
import kr.aigyeot.phone.presentation.AppViewModel
import kr.aigyeot.phone.presentation.CarViewModel
import kr.aigyeot.phone.presentation.CareSheetViewModel
import kr.aigyeot.phone.presentation.CareTabsViewModel
import kr.aigyeot.phone.presentation.ConnectViewModel
import kr.aigyeot.phone.presentation.GuardiansViewModel
import kr.aigyeot.phone.presentation.RecordsViewModel
import kr.aigyeot.phone.presentation.ReportDetailViewModel
import kr.aigyeot.phone.presentation.ReportsViewModel
import kr.aigyeot.phone.presentation.Route
import kr.aigyeot.phone.presentation.SettingsViewModel
import kr.aigyeot.phone.presentation.SongsViewModel
import kr.aigyeot.phone.presentation.StatusViewModel
import kr.aigyeot.phone.presentation.TimerViewModel
import kr.aigyeot.phone.presentation.TodayViewModel
import java.io.File

/**
 * 앱 하나에 하나(수동 생성자 주입). 저장소·상태·유스케이스를 만들어 넘긴다. 앱 범위 코루틴은 [scope] 하나다(메인 흐름, 구조화된 동시성).
 * 만드는 순서가 시작 순서다: 돌봄 기록 → 상태(옮기기 여부를 본다) → 나머지. 만든 뒤 [AppLifecycle.start]를 한 번 부른다.
 */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val log = AndroidLogger("Aigyeot")
    private val clock = Clock { System.currentTimeMillis() }

    val notices = Notices()
    private val marks = PrefsLinkMarks(app)
    private val credentials = KeystoreCredentialRepository(app)
    val care: CareRepository = FileCareRepository(File(app.filesDir, "care.json"), File(app.cacheDir, "export"), log, notices::saveFailed)
    val state: PhoneStateRepository = FilePhoneStateRepository(
        File(app.filesDir, "phone_state.json"),
        credentials,
        log,
        clock,
        migrating = { !care.care.value.migratedFeedings },
        onSaveFailed = notices::saveFailed,
        goneCleanupPending = marks.get(Mark.GONE_CLEANUP_PENDING),
    )

    init {
        // 지난 실행에서 버리고 아직 띠를 닫지 않은 메시지를 다시 알린다(U54).
        notices.showDrops(state.state.value.unseenDrops)
    }

    val link = LinkSwitch(marks, notices)
    private val visibility = AppVisibility()
    val songs: SongFileStore = AppSongFileStore(File(app.filesDir, "songs"), app.cacheDir, state)
    private val pushTokens = FcmPushTokens(app, log)

    // 401과 중계 관찰은 HTTP 층이 바로 넘긴다(부른 쪽이 취소돼도 잃지 않게). 아래에서 만든 유스케이스를 부른 때에 찾는다.
    private val relay: OkHttpRelayClient = OkHttpRelayClient(
        BuildConfig.RELAY_URL,
        BuildConfig.VERSION_NAME,
        onUnauthorized = { lose.unauthorized(it) },
        onObserved = { token, ticket, up -> connection.observed(token, ticket, up) },
    )

    private val wipe: WipeLink = WipeLink(state, care, credentials, songs, notices, link, scope, log)
    val lose: LoseConnection = LoseConnection(state, link, wipe, scope)
    val disconnect: Disconnect = Disconnect(state, relay, link, wipe, lose, scope, log)
    val outbox: SendOutbox = SendOutbox(state, care, relay, UploadSong(relay, songs), lose, notices, visibility, clock, scope)
    val inbox: SyncInbox = SyncInbox(state, relay, lose, notices, songs, clock, scope)
    val push: RegisterPush = RegisterPush(state, relay, lose, pushTokens, scope)
    val connection: KeepConnected = KeepConnected(state, relay, relay, inbox, outbox, lose, disconnect, push, AndroidNetworkWatcher(app), visibility, clock, scope)
    private val reconcile = ReconcileCareSummary(state, care, outbox, notices, clock)
    val recordCare = RecordCare(care, state, reconcile, songs, notices, clock, scope, log, allowDebugSeed = BuildConfig.DEBUG)
    val clearRecords = ClearRecords(care, state, recordCare, notices, songs, connection, outbox, clock, scope)
    val pair = Pair(state, care, credentials, relay, link, disconnect, lose, marks, pushTokens, songs, recordCare, notices, clock, scope, log)
    val recordSong = RecordSong(state, songs, clearRecords, outbox, notices, clock, scope)
    val selectSong = SelectSong(state, outbox, clock, scope)
    val removeSong = RemoveSong(state, songs, outbox, clock, scope)
    val invite = Invite(state, relay, lose, log)
    val setHandoffAlerts = SetHandoffAlerts(state, relay, lose)
    val lifecycle = AppLifecycle(state, care, songs, link, marks, visibility, connection, outbox, recordCare, disconnect, OngoingNotice { CareNotify.update(app, it) }, scope)

    /** 화면마다 ViewModel을 만든다(생성자 주입). 경로 인자(기록·리포트·알림 id)는 Navigation이 넘긴 SavedStateHandle에서 꺼낸다. */
    val viewModels: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            fun handle() = extras.createSavedStateHandle()
            val vm: ViewModel = when (modelClass) {
                AppViewModel::class.java -> AppViewModel(state, notices, link, pair, lose)
                ConnectViewModel::class.java -> ConnectViewModel(state, pair)
                StatusViewModel::class.java -> StatusViewModel(connection, notices, outbox)
                CareTabsViewModel::class.java -> CareTabsViewModel(state, notices, recordCare, disconnect)
                TodayViewModel::class.java -> TodayViewModel(state, care, notices, connection, recordCare, lifecycle)
                TimerViewModel::class.java -> TimerViewModel(handle().toRoute<Route.Timer>().id, state, care, recordCare)
                CareSheetViewModel::class.java -> CareSheetViewModel(care, notices, recordCare)
                RecordsViewModel::class.java -> RecordsViewModel(state, care, recordCare)
                CarViewModel::class.java -> CarViewModel(state, connection)
                SettingsViewModel::class.java -> SettingsViewModel(state, care, recordCare, clearRecords, disconnect, clock, { readDocument(app.contentResolver, it, CareCsv.MAX_BYTES + 1) })
                SongsViewModel::class.java -> SongsViewModel(state, connection, recordSong, selectSong, removeSong, songs, Recorder(app), clock, handle())
                ReportsViewModel::class.java -> ReportsViewModel(state)
                ReportDetailViewModel::class.java -> ReportDetailViewModel(handle().toRoute<Route.Report>().id, state)
                AlertViewModel::class.java -> AlertViewModel(handle().toRoute<Route.Alert>().id, state)
                GuardiansViewModel::class.java -> GuardiansViewModel(state, connection, invite, setHandoffAlerts)
                else -> error("모르는 ViewModel: $modelClass")
            }
            @Suppress("UNCHECKED_CAST")
            return vm as T
        }
    }
}
