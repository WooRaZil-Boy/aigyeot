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
import kr.aigyeot.app.store.retryOnDbError

/**
 * 목록 '울음 n번': 홈 '오늘 하루'와 같은 [Store.cryAlarms](규칙 isCryAlarm 하나). 리포트 본문으로 세지 않는다(64KB를 넘으면 앞 에피소드를 버린다).
 * [reportRows]는 닫힌 세션만 묻고, 닫힌 세션의 에피소드 종류는 바뀌지 않으니 [cache]에 한 번만 센다.
 */
internal fun closedCries(store: Store, cache: MutableMap<String, Int>): (String) -> Int = { id -> cache.getOrPut(id) { store.cryAlarms(id).size } }

class ReportListViewModel(store: Store) : ViewModel() {
    // 다시 구독할 때 앞 흐름과 겹칠 수 있어 동시 맵
    private val cries = closedCries(store, java.util.concurrent.ConcurrentHashMap())

    val rows: StateFlow<List<ReportRow>> = store.sessionsFlow().map { ss -> reportRows(ss, cries) }.flowOn(Dispatchers.IO).retryOnDbError("report_list")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

sealed interface ReportDetailUiState {
    data object Loading : ReportDetailUiState
    data class Loaded(val view: ReportView) : ReportDetailUiState
}

/** 리포트 상세. 세션·에피소드가 바뀔 때마다 다시 읽는다: 인계 TTS 도중 열어도 결과가 들어오면 고쳐 그린다. */
class ReportDetailViewModel(store: Store, clock: () -> Long, id: String) : ViewModel() {
    // 다시 읽기까지 흐름 안에 넣어, 한 번 실패해도 직전 화면을 두고 1초 뒤 다시 읽는다
    val uiState: StateFlow<ReportDetailUiState> = store.reportChanges(id).map { store.readReport(id, clock()) }.flowOn(Dispatchers.IO)
        .retryOnDbError("report").map { (b, settling) -> if (b == null) ReportDetailUiState.Loading else ReportDetailUiState.Loaded(reportView(b, settling)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportDetailUiState.Loading)
}
