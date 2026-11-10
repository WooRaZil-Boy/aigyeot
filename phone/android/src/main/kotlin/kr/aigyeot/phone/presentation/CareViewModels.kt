package kr.aigyeot.phone.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.LocalDate
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
import kr.aigyeot.phone.domain.model.CareEditDraft
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.CareUndo
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.Diaper
import kr.aigyeot.phone.domain.model.DisconnectRun
import kr.aigyeot.phone.domain.model.EditCheck
import kr.aigyeot.phone.domain.model.FeedSide
import kr.aigyeot.phone.domain.model.IMPORT_FAILED
import kr.aigyeot.phone.domain.model.IMPORT_TOO_LARGE
import kr.aigyeot.phone.domain.model.IMPORT_UNREADABLE
import kr.aigyeot.phone.domain.model.ImportDraft
import kr.aigyeot.phone.domain.model.ImportFormat
import kr.aigyeot.phone.domain.model.MINUTE
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.SAVE_FAILED
import kr.aigyeot.phone.domain.model.check
import kr.aigyeot.phone.domain.model.custom
import kr.aigyeot.phone.domain.model.exportFileName
import kr.aigyeot.phone.domain.model.problemText
import kr.aigyeot.phone.domain.model.toCsv
import kr.aigyeot.phone.domain.model.toEvent
import kr.aigyeot.phone.domain.model.toggledSide
import kr.aigyeot.phone.domain.model.withAgo
import kr.aigyeot.phone.domain.model.withDate
import kr.aigyeot.phone.domain.model.withEndPicked
import kr.aigyeot.phone.domain.model.withEndTime
import kr.aigyeot.phone.domain.model.withEndUnknown
import kr.aigyeot.phone.domain.model.withNote
import kr.aigyeot.phone.domain.model.withSide
import kr.aigyeot.phone.domain.model.withStartPicked
import kr.aigyeot.phone.domain.model.withStartTime
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.state.Notices
import kr.aigyeot.phone.domain.usecase.AppLifecycle
import kr.aigyeot.phone.domain.usecase.ClearRecords
import kr.aigyeot.phone.domain.usecase.Disconnect
import kr.aigyeot.phone.domain.usecase.KeepConnected
import kr.aigyeot.phone.domain.usecase.RecordCare
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.Role

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
data class TimerUiState(val id: String, val care: CareFile, val connected: Boolean, val guardian: Boolean) {
    /** 이 타이머의 기록(P4: 돌봄 파일에서 찾는다). 지웠으면 null. */
    val event: CareEvent? get() = care.events.find { it.id == id }
}

internal fun timerUi(id: String, care: CareFile, c: Connection) =
    TimerUiState(id, care, c is Connection.Paired, c.role == Role.GUARDIAN)

/**
 * 기록 편집(시트·타이머) 단계. 화면은 이 값을 그리기만 한다(오류 글, 겹침 확인 창, 저장 중 막음, 끝나면 닫기).
 * [ConfirmOverlap]은 R4 겹침 경고를 띄운 채 사용자의 '그래도 저장'을 기다린다.
 */
sealed interface EditStage {
    data class Editing(val error: String? = null) : EditStage

    data class ConfirmOverlap(val event: CareEvent) : EditStage

    data object Saving : EditStage

    /** 저장했거나(고치던 기록이 그사이 지워졌으면 저장하지 않고) 지웠다: 시트를 닫는다. */
    data object Done : EditStage
}

class TimerViewModel(
    private val id: String,
    state: PhoneStateRepository,
    private val care: CareRepository,
    private val recordCare: RecordCare,
    private val clock: Clock,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    val ui: StateFlow<TimerUiState> = derive(care.care, state.connection) { timerUi(id, care.care.value, state.connection.value) }

    private val _stage = MutableStateFlow<EditStage>(EditStage.Editing())
    val stage: StateFlow<EditStage> = _stage.asStateFlow()

    fun setSide(side: FeedSide) { viewModelScope.launch { recordCare.editOngoing(id) { it.withSide(side) } } }
    fun setAmount(ml: Int?) { viewModelScope.launch { recordCare.editOngoing(id) { it.copy(amountMl = ml) } } }

    /** 시작 시각 수정(타이머 '시작 시각 수정'). */
    fun pickStart(hour: Int, minute: Int) = propose { it.withStartPicked(hour, minute, clock.now() + MINUTE) }

    /** 끝 시각 고르기(최대 길이를 넘긴 기록, U14). */
    fun pickEnd(hour: Int, minute: Int) = propose { it.withEndPicked(hour, minute) }

    /** 고른 시각을 검사한다: R3 문제면 글, R4 겹침이면 확인을 기다리고, 아니면 저장한다(시트와 같은 규칙). */
    private fun propose(f: (CareEvent) -> CareEvent) {
        val e = ui.value.event ?: return
        if (_stage.value == EditStage.Saving) return
        val next = f(e)
        when (val c = care.care.value.check(next, clock.now())) {
            is EditCheck.Problem -> _stage.value = EditStage.Editing(problemText(c.problem, e.kind))
            EditCheck.Overlap -> _stage.value = EditStage.ConfirmOverlap(next)
            EditCheck.Ok -> save(next)
        }
    }

    fun confirmOverlap() { (_stage.value as? EditStage.ConfirmOverlap)?.let { save(it.event) } }
    fun dismissOverlap() { _stage.value = EditStage.Editing() }

    /** 진행 중인 그 기록에 시작·끝만 얹는다. 저장하지 못하면 앞 오류 글을 둔다(띠가 실패를 말한다). */
    private fun save(next: CareEvent) {
        val before = (_stage.value as? EditStage.Editing)?.error
        _stage.value = EditStage.Saving
        viewModelScope.launch {
            val ok = recordCare.editOngoing(id) { it.copy(startAt = next.startAt, endAt = next.endAt) }
            _stage.value = EditStage.Editing(if (ok) null else before)
        }
    }

    fun end() = recordCare.end(id)
    fun endUnknown() = recordCare.endUnknown(id)
    fun delete() = recordCare.delete(id)
}

/** 열린 시트 하나: 입력과 단계를 한 값으로 둔다(저장 결과는 그 시트에만 닿는다, P5). */
data class SheetState(val draft: CareEditDraft, val stage: EditStage = EditStage.Editing()) {
    val sheetId: String get() = draft.sheetId
}

/**
 * 빠른 기록·고치기 시트(C3). 입력([CareEditDraft])과 단계([EditStage])를 여기서 갖고, 화면은 동작만 보낸다.
 * 입력은 화면 저장 상태에도 둬 시스템이 화면만 없앤 뒤에도 이어 쓴다(옛 rememberSaveable과 같다). 어느 시트인지는 `sheetId`가 말하고,
 * 늦게 끝난 저장·닫기는 그 시트가 아직 열려 있을 때만 반영한다(닫고 새 시트를 열었으면 새 시트를 건드리지 않는다).
 */
class CareSheetViewModel(
    private val care: CareRepository,
    private val notices: Notices,
    private val recordCare: RecordCare,
    private val clock: Clock,
    private val saved: SavedStateHandle,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    val careFile: StateFlow<CareFile> = care.care

    private val _sheet = MutableStateFlow(
        saved.get<String>(K_DRAFT)?.let { runCatching { ProtocolJson.decodeFromString(CareEditDraft.serializer(), it) }.getOrNull() }?.let { SheetState(it) },
    )
    val sheet: StateFlow<SheetState?> = _sheet.asStateFlow()

    /** 시트를 연다. 같은 시트를 다시 그리면(회전·복원) 적던 것을 그대로 둔다. */
    fun open(sheetId: String, kind: CareKind, initial: CareEvent?, baseDate: LocalDate?) {
        if (_sheet.value?.sheetId == sheetId) return
        set(SheetState(initial?.let { CareEditDraft.edit(sheetId, it) } ?: CareEditDraft.new(sheetId, kind, baseDate, clock.now(), APP_ZONE)))
    }

    /** 그 시트가 아직 열려 있을 때만 걷는다. */
    fun close(sheetId: String) {
        if (_sheet.value?.sheetId == sheetId) set(null)
    }

    /** 시트 상태를 바꾸는 곳은 여기 하나다(P3): 메모리와 화면 저장 상태(입력만)를 함께 쓴다. */
    private fun set(s: SheetState?) {
        _sheet.value = s
        saved[K_DRAFT] = s?.draft?.let { ProtocolJson.encodeToString(CareEditDraft.serializer(), it) }
    }

    /** 그 시트일 때만 단계를 바꾼다(늦게 온 저장 결과가 다른 시트를 닫지 않게). */
    private fun stage(sheetId: String, st: EditStage) {
        val cur = _sheet.value ?: return
        if (cur.sheetId == sheetId) set(cur.copy(stage = st))
    }

    /** 입력을 고친다. 고치면 앞 오류 글은 걷는다. 저장 중·확인 중에는 바꾸지 않는다. */
    private fun edit(f: (CareEditDraft) -> CareEditDraft) {
        val cur = _sheet.value ?: return
        if (cur.stage !is EditStage.Editing) return
        set(SheetState(f(cur.draft)))
    }

    fun setKind(k: CareKind) = edit { it.copy(kind = k) }
    fun pickAgo(min: Long) = edit { it.withAgo(min, clock.now()) }
    fun custom() = edit { it.custom() }
    fun pickDate(d: LocalDate) = edit { it.withDate(d) }
    fun pickStart(hour: Int, minute: Int) = edit { it.withStartTime(hour, minute, clock.now()) }
    fun pickEnd(hour: Int, minute: Int) = edit { it.withEndTime(hour, minute) }
    fun endUnknown() = edit { it.withEndUnknown() }
    fun toggleSide(s: FeedSide) = edit { it.toggledSide(s) }
    fun setAmount(ml: Int?) = edit { it.copy(ml = ml) }
    fun setDiaper(d: Diaper) = edit { it.copy(diaper = d) }
    fun setNote(text: String) = edit { it.withNote(text) }

    /** 저장: R3 문제면 글, R4 겹침이면 확인을 기다린다. 고치던 기록이 그사이 지워졌으면 저장하지 않고 닫는다(되살리지 않는다, R10). */
    fun save() {
        val cur = _sheet.value ?: return
        val d = cur.draft
        if (cur.stage !is EditStage.Editing || !d.savable) return
        val now = clock.now()
        val base = d.editingId?.let { eid -> care.care.value.events.firstOrNull { it.id == eid && it.deletedAt == null } }
        if (d.editingId != null && base == null) return stage(d.sheetId, EditStage.Done)
        val e = d.toEvent(base, now)
        when (val c = care.care.value.check(e, now)) {
            is EditCheck.Problem -> stage(d.sheetId, EditStage.Editing(problemText(c.problem, e.kind)))
            EditCheck.Overlap -> stage(d.sheetId, EditStage.ConfirmOverlap(e))
            EditCheck.Ok -> commit(d.sheetId, e, editing = d.editingId != null)
        }
    }

    fun confirmOverlap() {
        val cur = _sheet.value ?: return
        val o = cur.stage as? EditStage.ConfirmOverlap ?: return
        commit(cur.sheetId, o.event, editing = cur.draft.editingId != null)
    }

    fun dismissOverlap() {
        val cur = _sheet.value ?: return
        if (cur.stage is EditStage.ConfirmOverlap) stage(cur.sheetId, EditStage.Editing())
    }

    /** 저장은 앱 범위에서 끝까지 하고, 결과는 그 시트가 아직 열려 있을 때만 보인다. */
    private fun commit(sheetId: String, e: CareEvent, editing: Boolean) {
        stage(sheetId, EditStage.Saving)
        viewModelScope.launch {
            stage(
                sheetId,
                when {
                    recordCare.saveEvent(e, editing) -> EditStage.Done
                    editing && care.care.value.events.none { it.id == e.id && it.deletedAt == null } -> EditStage.Done
                    // 저장 실패는 시트 안에서도 보인다. 띠의 다른 줄(버림 안내)은 시트에 옮기지 않는다.
                    else -> EditStage.Editing(SAVE_FAILED.takeIf { notices.notice.value?.lines()?.contains(it) == true })
                },
            )
        }
    }

    /** 고치던 기록 지우기(5초 되돌리기는 띠가 맡는다). */
    fun delete() {
        val cur = _sheet.value ?: return
        val id = cur.draft.editingId ?: return
        recordCare.delete(id)
        stage(cur.sheetId, EditStage.Done)
    }

    private companion object {
        const val K_DRAFT = "careSheet.draft"
    }
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
