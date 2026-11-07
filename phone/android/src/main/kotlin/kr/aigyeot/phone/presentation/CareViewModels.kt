package kr.aigyeot.phone.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.DisconnectRun
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

    /** 방금 지운 손 기록 id(띠 하나가 계속 받는다). */
    val undoIds: Flow<String> = notices.undoCareId.mapNotNull { it }

    fun clearUndo() = notices.clearUndo()
    fun undoDelete(id: String) = recordCare.undoDelete(id)

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

/** 설정 탭. */
data class SettingsUiState(val care: CareFile, val connected: Boolean, val disconnect: DisconnectRun?)

class SettingsViewModel(
    state: PhoneStateRepository,
    private val care: CareRepository,
    private val recordCare: RecordCare,
    private val clearRecords: ClearRecords,
    private val disconnect: Disconnect,
    private val clock: Clock,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    val ui: StateFlow<SettingsUiState> = derive(care.care, state.connection, disconnect.run) {
        SettingsUiState(care.care.value, state.connection.value is Connection.Paired, disconnect.run.value)
    }

    fun setNickname(name: String?) = recordCare.setNickname(name)
    fun setSleepDiaper(on: Boolean) = recordCare.setSleepDiaper(on)

    /** 내 기록 내보내기(C7, U29): 캐시 export/에 CSV를 쓰고 그 파일을 돌려준다. 실패하면 null. */
    suspend fun export(): File? = care.export(exportFileName(clock.now(), APP_ZONE)) { it.toCsv(APP_ZONE) }

    fun clearRecords() = clearRecords.invoke()
    fun startDisconnect() = disconnect.start()

    /** 설정을 떠나면 지난 끊기 실패 글을 걷는다. */
    fun dismissDisconnectError() = disconnect.dismissError()
}
