package kr.aigyeot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.db.SessionEntity
import kr.aigyeot.app.store.retryOnDbError
import kr.aigyeot.engine.Params
import kr.aigyeot.shared.MethodStat
import kr.aigyeot.shared.ReportSummaryBody

/** 리포트 목록 한 줄. 열린 세션은 '주행 중'이라 열 수 없다. */
data class ReportRow(val id: String, val label: String, val enabled: Boolean)

internal fun reportRows(sessions: List<SessionEntity>) = sessions.map { s ->
    ReportRow(s.id, hm(s.startedAt) + (s.endedAt?.let { " ~ ${hm(it)}" } ?: " · 주행 중") + if (s.scenarioId != null) " · 시뮬레이션" else "", s.endedAt != null)
}

class ReportListViewModel(store: Store) : ViewModel() {
    val rows: StateFlow<List<ReportRow>> = store.sessionsFlow().map(::reportRows)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

sealed interface ReportDetailUiState {
    data object Loading : ReportDetailUiState
    /** [settling]: 인계가 아직 안 끝났다(C185). */
    data class Loaded(val body: ReportSummaryBody, val settling: Boolean) : ReportDetailUiState
}

/** 리포트 상세. 세션·에피소드가 바뀔 때마다 다시 읽는다: 인계 TTS 도중 열어도 결과가 들어오면 고쳐 그린다(C185). */
class ReportDetailViewModel(store: Store, clock: () -> Long, id: String) : ViewModel() {
    // C293: 다시 읽기까지 흐름 안에 넣어, 한 번 실패해도 직전 화면을 두고 1초 뒤 다시 읽는다
    val uiState: StateFlow<ReportDetailUiState> = store.reportChanges(id).map { store.readReport(id, clock()) }.flowOn(Dispatchers.IO)
        .retryOnDbError("report").map { (b, settling) -> if (b == null) ReportDetailUiState.Loading else ReportDetailUiState.Loaded(b, settling) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportDetailUiState.Loading)
}

/** C303 A18: 3번 미만이면 "아직 모름"(Params.statMinShown). */
internal fun methodStatLines(stats: List<MethodStat>?, p: Params = Params()) =
    stats.orEmpty().map { "${it.label}: " + if (it.attempts < p.statMinShown) "아직 모름" else "${it.attempts}번 중 ${it.successes}번 그침" }
