package kr.aigyeot.phone.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.CareCsv
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.CareUndo
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.DisconnectRun
import kr.aigyeot.phone.domain.model.IMPORT_FAILED
import kr.aigyeot.phone.domain.model.IMPORT_TOO_LARGE
import kr.aigyeot.phone.domain.model.IMPORT_UNREADABLE
import kr.aigyeot.phone.domain.model.ImportDraft
import kr.aigyeot.phone.domain.model.ImportFormat
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.SAVE_FAILED
import kr.aigyeot.phone.domain.model.exportFileName
import kr.aigyeot.phone.domain.model.toCsv
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.state.Notices
import kr.aigyeot.phone.domain.usecase.AppLifecycle
import kr.aigyeot.phone.domain.usecase.ClearRecords
import kr.aigyeot.phone.domain.usecase.Disconnect
import kr.aigyeot.phone.domain.usecase.KeepConnected
import kr.aigyeot.phone.domain.usecase.RecordCare
import kr.aigyeot.shared.Role
import java.io.File

// 돌봄 기록 화면(phone-care-plan 5·7장)의 ViewModel. 원본은 care.json(CareRepository)·state.json(PhoneStateRepository)이고, 화면 상태는 그것에서 계산한다.

private val Connection.role: Role? get() = (this as? Connection.Paired)?.creds?.role

/** 탭 묶음: 탭 모양(guardian은 차 탭 없음), 설정 끊기 끝(오늘로), 지운 기록 되돌리기 띠. */
data class CareTabsUiState(val guardian: Boolean, val disconnectDone: Boolean)

internal fun careTabsUi(c: Connection, run: DisconnectRun?) = CareTabsUiState(c.role == Role.GUARDIAN, run == DisconnectRun.Done)

class CareTabsViewModel(
    state: PhoneStateRepository,
    private val notices: Notices,
    private val recordCare: RecordCare,
    private val disconnect: Disconnect,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    val ui: StateFlow<CareTabsUiState> = derive(state.connection, disconnect.run) { careTabsUi(state.connection.value, disconnect.run.value) }

    /** 떠 있는 되돌리기 띠(방금 지운 손 기록·가져온 기록). 수명(5초·바뀜)은 RecordCare 한 곳이 정한다. */
    val undo: StateFlow<CareUndo?> = notices.undo

    /** 끝날 때까지 기다릴 작업. 끝난 뒤에도 [undo]가 그 띠면 되돌리기 저장이 실패한 것이다(U38 11-09 보충). */
    fun undo(u: CareUndo) = recordCare.undo(u)

    /** 끊은 뒤 오늘·띠를 한 번 그렸다(U25 b). */
    fun disconnectShown() = disconnect.shown()
}

/** 오늘 탭(C1). */
data class CareUiState(
    val care: CareFile,
    val phone: PhoneState,
    val connection: Connection,
    val online: Boolean,
    val careNotice: String?,
) {
    val connected get() = connection is Connection.Paired
    val role get() = connection.role
    val guardian get() = role == Role.GUARDIAN
    val owner get() = role == Role.OWNER
}

/** 오늘 탭(C1). guardian은 인계 알림·최근 리포트도 본다. */
class TodayViewModel(
    state: PhoneStateRepository,
    care: CareRepository,
    private val notices: Notices,
    connection: KeepConnected,
    private val recordCare: RecordCare,
    private val lifecycle: AppLifecycle,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    val ui: StateFlow<CareUiState> = derive(care.care, state.state, state.connection, connection.status, notices.careNotice) {
        CareUiState(care.care.value, state.state.value, state.connection.value, connection.status.value.online, notices.careNotice.value)
    }

    fun start(kind: CareKind) = recordCare.start(kind)
    fun clearCareNotice() = notices.clearCare()

    /** 알림 권한은 처음 타이머를 시작할 때 한 번만 묻는다(9장). */
    fun takeNotificationAsk() = lifecycle.takeNotificationAsk()
    fun refreshOngoingNotice() = lifecycle.refreshOngoingNotice()
}

/** 타이머(C2). 끝났거나 지운 기록이면 `event`가 null이고 화면이 닫힌다. */
data class TimerUiState(val event: CareEvent?, val care: CareFile, val connected: Boolean, val guardian: Boolean)

internal fun timerUi(id: String, care: CareFile, c: Connection) =
    TimerUiState(care.events.find { it.id == id }, care, c is Connection.Paired, c.role == Role.GUARDIAN)

class TimerViewModel(
    private val id: String,
    state: PhoneStateRepository,
    care: CareRepository,
    private val recordCare: RecordCare,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    val ui: StateFlow<TimerUiState> = derive(care.care, state.connection) { timerUi(id, care.care.value, state.connection.value) }

    /** 진행 중인 그 기록에만 바꿀 것을 얹는다. 저장하지 못하면 거짓. */
    suspend fun edit(f: (CareEvent) -> CareEvent) = recordCare.editOngoing(id, f)
    fun end() = recordCare.end(id)
    fun endUnknown() = recordCare.endUnknown(id)
    fun delete() = recordCare.delete(id)
}

/** 빠른 기록·고치기 시트(C3)의 저장 결과. */
sealed interface SheetSave {
    data object Saved : SheetSave

    /** 고치던 기록이 그사이 지워졌다: 저장하지 않고 닫는다(되살리지 않는다, R10). */
    data object Gone : SheetSave

    /** 저장 실패는 시트 안에서도 보인다. 띠의 다른 줄(버림 안내)은 시트에 옮기지 않는다. */
    data class Failed(val error: String?) : SheetSave
}

class CareSheetViewModel(
    private val care: CareRepository,
    private val notices: Notices,
    private val recordCare: RecordCare,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    val careFile: StateFlow<CareFile> = care.care

    suspend fun save(e: CareEvent, editing: Boolean): SheetSave = when {
        recordCare.saveEvent(e, editing) -> SheetSave.Saved
        editing && care.care.value.events.none { it.id == e.id && it.deletedAt == null } -> SheetSave.Gone
        else -> SheetSave.Failed(SAVE_FAILED.takeIf { notices.notice.value?.lines()?.contains(it) == true })
    }

    fun delete(id: String) = recordCare.delete(id)
}

/** 기록 탭(C4·C5·C14): 하루 타임라인과 패턴. */
data class RecordsUiState(val care: CareFile, val phone: PhoneState, val connection: Connection) {
    val guardian get() = connection.role == Role.GUARDIAN
}

class RecordsViewModel(
    state: PhoneStateRepository,
    care: CareRepository,
    private val recordCare: RecordCare,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    val ui: StateFlow<RecordsUiState> = derive(care.care, state.state, state.connection) {
        RecordsUiState(care.care.value, state.state.value, state.connection.value)
    }

    /** 밀어서 지우기. 저장하지 못하면 `onFail`(밀린 줄을 되돌린다). */
    fun delete(id: String, onFail: () -> Unit) = recordCare.delete(id, onFail)
}

/** 차 탭: 연결 상태 → 우리 노래 → 리포트(5장). */
data class CarUiState(val phone: PhoneState, val connection: Connection, val online: Boolean) {
    val owner get() = connection.role == Role.OWNER
}

class CarViewModel(
    state: PhoneStateRepository,
    connection: KeepConnected,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    val ui: StateFlow<CarUiState> = derive(state.state, state.connection, connection.status) {
        CarUiState(state.state.value, state.connection.value, connection.status.value.online)
    }
}

/** 설정 탭. `phones`는 차를 뺀 연결된 휴대폰 수('보호자 n명', 시안 S.settings). */
data class SettingsUiState(val care: CareFile, val connected: Boolean, val disconnect: DisconnectRun?, val phones: Int = 0)

/** 기록 가져오기(U64~U68) 단계. 파일 글은 이 메모리에만 두고 저장하지 않는다. */
sealed interface ImportUi {
    data object Closed : ImportUi

    /** 고른 파일을 읽는 중. */
    data object Reading : ImportUi

    /** Nara 이름이 둘 이상(import.pickChild). 고르면 같은 글을 다시 읽는다. */
    data class PickChild(val text: String, val profiles: List<String>) : ImportUi

    /** 미리 보기(U68). 가져올 기록이 0이면 '닫기'만. */
    data class Preview(val draft: ImportDraft) : ImportUi

    data class Saving(val draft: ImportDraft) : ImportUi

    /** 끝내는 안내(import.unreadable·tooLarge·failed): 글과 '닫기'만. */
    data class Ended(val message: String) : ImportUi
}

class SettingsViewModel(
    state: PhoneStateRepository,
    private val care: CareRepository,
    private val recordCare: RecordCare,
    private val clearRecords: ClearRecords,
    private val disconnect: Disconnect,
    private val clock: Clock,
    /** 기록 가져오기: 고른 문서를 메모리로만 읽는다(상한 + 1바이트까지, 열거나 읽지 못하면 null). 앱은 ContentResolver, 시험은 가짜. */
    private val readDocument: suspend (uri: String) -> ByteArray?,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    val ui: StateFlow<SettingsUiState> = derive(care.care, state.connection, state.state, disconnect.run) {
        SettingsUiState(
            care.care.value,
            state.connection.value is Connection.Paired,
            disconnect.run.value,
            state.state.value.devices.count { it.role != Role.CAR },
        )
    }

    fun setNickname(name: String?) = recordCare.setNickname(name)
    fun setSleepDiaper(on: Boolean) = recordCare.setSleepDiaper(on)

    /** 내 기록 내보내기(C7, U29): 캐시 export/에 CSV를 쓰고 그 파일을 돌려준다. 실패하면 null. */
    suspend fun export(): File? = care.export(exportFileName(clock.now(), APP_ZONE)) { it.toCsv(APP_ZONE) }

    private val _import = MutableStateFlow<ImportUi>(ImportUi.Closed)
    val import: StateFlow<ImportUi> = _import.asStateFlow()

    /** 가져오기 작업은 하나다: 새 고르기·아이 고르기·저장·닫기가 앞 작업을 취소하고, 결과는 그 작업 안에서만 적용한다(P5). */
    private var importJob: Job? = null

    private fun runImport(now: ImportUi, block: suspend () -> ImportUi) {
        importJob?.cancel()
        _import.value = now
        importJob = viewModelScope.launch { _import.value = block() }
    }

    /**
     * 파일 고르기 결과(문서 주소). 읽기([readDocument], 상한 + 1바이트까지)·형식 판별을 이 ViewModel이 쥔 작업이 한다:
     * 화면이 회전하거나 사라져도 이어지고, 다시 고르면 앞 파일의 결과는 버린다. 열거나 읽지 못하면 import.failed.
     */
    fun pick(uri: String) = runImport(ImportUi.Reading) {
        val bytes = readDocument(uri) ?: return@runImport ImportUi.Ended(IMPORT_FAILED)
        val text = withContext(Dispatchers.Default) { CareCsv.read(bytes) } ?: return@runImport ImportUi.Ended(IMPORT_TOO_LARGE)
        show(text, null)
    }

    /** Nara 아이를 골랐다: 그 아이 줄만 다시 읽는다. */
    fun pickChild(name: String) {
        val p = _import.value as? ImportUi.PickChild ?: return
        runImport(ImportUi.Reading) { show(p.text, name) }
    }

    /** 읽기는 지금 돌봄 기록을 기준으로 건너뛸 줄을 정한다(U67). 아이를 골라야 하면 고르기로. */
    private suspend fun show(text: String, profile: String?): ImportUi {
        val d = withContext(Dispatchers.Default) { CareCsv.parse(text, care.care.value, clock.now(), profile) }
        return when {
            d.format == ImportFormat.UNKNOWN -> ImportUi.Ended(IMPORT_UNREADABLE)
            d.needsChild && profile == null -> ImportUi.PickChild(text, d.profiles)
            else -> ImportUi.Preview(d)
        }
    }

    /** '가져오기': 한 번에 저장하고 닫는다. 띠의 되돌리기는 RecordCare가 띄운다. 실패하면 import.failed. */
    fun confirmImport() {
        val p = _import.value as? ImportUi.Preview ?: return
        if (p.draft.rows.isEmpty()) return closeImport()
        // 저장은 앱 범위에서 끝난다(RecordCare). 이 기다림이 취소돼도 들어간 기록과 띠는 그대로다.
        runImport(ImportUi.Saving(p.draft)) { if (recordCare.importRows(p.draft.rows)) ImportUi.Closed else ImportUi.Ended(IMPORT_FAILED) }
    }

    /** 취소·닫기: 읽던 작업은 버린다. 저장 중에는 닫지 않는다(저장 결과가 화면을 정한다). */
    fun closeImport() {
        if (_import.value is ImportUi.Saving) return
        importJob?.cancel()
        _import.value = ImportUi.Closed
    }

    fun clearRecords() = clearRecords.invoke()
    fun startDisconnect() = disconnect.start()

    /** 설정을 떠나면 지난 끊기 실패 글을 걷는다. */
    fun dismissDisconnectError() = disconnect.dismissError()
}
