package kr.aigyeot.phone.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.SongEntry
import kr.aigyeot.phone.domain.model.StoredAlert
import kr.aigyeot.phone.domain.model.instantOf
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.usecase.Invite
import kr.aigyeot.phone.domain.usecase.KeepConnected
import kr.aigyeot.phone.domain.usecase.SetHandoffAlerts
import kr.aigyeot.shared.DeviceInfo
import kr.aigyeot.shared.InviteResponse
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.Role

// 리포트·인계 알림·보호자 화면. 리포트·알림은 state.json이 원본이고 읽기만 한다(저장소를 바로 읽는다, 11-08 구조 원칙).

/** 리포트 목록: 새 것이 위. */
fun reportsNewestFirst(reports: Collection<ReportSummaryBody>) = reports.sortedByDescending { instantOf(it.startedAt) ?: 0 }

class ReportsViewModel(state: PhoneStateRepository, scope: CoroutineScope = vmScope()) : ViewModel(scope) {
    val reports: StateFlow<List<ReportSummaryBody>> = derive(state.state) { reportsNewestFirst(state.state.value.reports.values.map { it.body }) }
}

/** 리포트 상세. 부모 노래 이름은 이 폰이 아는 곡에서 붙인다. */
data class ReportDetailUiState(val report: ReportSummaryBody?, val songs: List<SongEntry>)

class ReportDetailViewModel(sessionId: String, state: PhoneStateRepository, scope: CoroutineScope = vmScope()) : ViewModel(scope) {
    val ui: StateFlow<ReportDetailUiState> = derive(state.state) {
        ReportDetailUiState(state.state.value.reports[sessionId]?.body, state.state.value.songs)
    }
}

/** 인계 알림 상세(guardian). 본문을 아직 받지 못했으면 null. */
class AlertViewModel(id: String, state: PhoneStateRepository, scope: CoroutineScope = vmScope()) : ViewModel(scope) {
    val alert: StateFlow<StoredAlert?> = derive(state.state) { state.state.value.alerts.find { it.id == id } }
}

/** 보호자: owner는 초대·목록, guardian은 인계 알림 받기(7.1). */
data class GuardiansUiState(
    val role: Role?,
    val handoffAlerts: Boolean,
    /** 차를 뺀 연결된 폰. */
    val phones: List<DeviceInfo>,
    val myDeviceId: String?,
    val online: Boolean,
    val busy: Boolean,
    val error: String?,
    val invite: InviteResponse?,
)

/** 차를 뺀 연결된 폰, 인계 알림 받기는 중계 값(없으면 꺼짐). */
internal fun guardiansUi(s: PhoneState, online: Boolean, busy: Boolean, error: String?, invite: InviteResponse?) =
    GuardiansUiState(s.role, s.handoffAlerts == true, s.devices.filter { it.role != Role.CAR }, s.myDeviceId, online, busy, error, invite)

class GuardiansViewModel(
    state: PhoneStateRepository,
    connection: KeepConnected,
    private val invite: Invite,
    private val setHandoffAlerts: SetHandoffAlerts,
    scope: CoroutineScope = vmScope(),
) : ViewModel(scope) {
    private data class Local(val busy: Boolean = false, val error: String? = null, val invite: InviteResponse? = null)

    private val local = MutableStateFlow(Local())

    val ui: StateFlow<GuardiansUiState> = derive(state.state, connection.status, local) {
        local.value.let { l -> guardiansUi(state.state.value, connection.status.value.online, l.busy, l.error, l.invite) }
    }

    fun makeInvite() {
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            invite().onSuccess { r -> local.update { it.copy(invite = r) } }.onFailure { e -> local.update { it.copy(error = e.message) } }
            local.update { it.copy(busy = false) }
        }
    }

    fun setHandoff(on: Boolean) {
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val error = setHandoffAlerts(on)
            local.update { it.copy(busy = false, error = error) }
        }
    }
}
