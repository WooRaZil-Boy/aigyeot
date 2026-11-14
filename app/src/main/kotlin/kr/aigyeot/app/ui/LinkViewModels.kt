package kr.aigyeot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kr.aigyeot.app.host.Host
import kr.aigyeot.app.link.Link
import kr.aigyeot.app.link.LinkStatus
import kr.aigyeot.app.link.LinkedPhone
import kr.aigyeot.app.link.db.SongSelectionEntity
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.db.SongEntity
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind

/** 노래 한 줄. [selected]면 "이 곡 쓰기"를 끈다. */
data class SongRow(val id: String, val label: String, val selected: Boolean)

/** 노래 고르기: 받은 우리 노래 중 실험에 쓸 곡을 고른다. 부모 노래 후보 다시 계산은 store가 한다. */
class SongsViewModel(store: Store, host: Host, private val link: Link) : ViewModel() {
    val rows: StateFlow<List<SongRow>> = combine(store.activeSongsFlow(), store.selectionFlow(), host.steps) { songs, sel, steps ->
        songRows(songs, sel, steps?.state?.methodsNext.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(id: String) = link.selectSong(id)
}

internal fun songRows(songs: List<SongEntity>, selection: SongSelectionEntity?, methods: List<Method>): List<SongRow> {
    val third = methods.firstOrNull { it.kind == MethodKind.SONG }?.songId
    return songs.map { s ->
        SongRow(
            s.id, (s.name ?: "이름 없는 노래") + (s.durationS?.let { " · ${it.toInt()}초" } ?: "") + if (s.id == third) " · 실험에 쓰는 노래" else "",
            s.id == selection?.songId,
        )
    }
}

/** 연결 화면 상태. [remainingS]는 코드 만료까지 남은 초(코드가 없으면 null). */
data class ConnectUiState(val link: LinkStatus, val remainingS: Long?)

internal fun connectUiState(st: LinkStatus, now: Long) = ConnectUiState(st, st.pairExpiresAt?.let { ((it - now) / 1000).coerceAtLeast(0) })

internal fun remainingText(s: Long) = "남은 시간 ${s / 60}분 ${s % 60}초"

/** 폰 한 줄(제목 = 역할, 부가 = 마지막 연락 '11월 9일(월) 오후 2:05'. 시각 글자는 TimeText 한 곳). */
internal fun phoneLines(ph: LinkedPhone): Pair<String, String?> =
    (if (ph.role == "owner") "함께 타는 보호자" else "다른 보호자") to ph.lastSeenAt?.let { "마지막 연락 ${TimeText.date(it)} ${TimeText.clock(it)}" }

/** 연결 화면: 열면 코드를 받고 닫으면 지우는 일은 화면의 열림·닫힘이 부른다. 남은 시간은 코드가 있는 동안 1초마다 갱신. */
class ConnectViewModel(private val link: Link, private val clock: () -> Long) : ViewModel() {
    val uiState: StateFlow<ConnectUiState> = link.status.transformLatest { st ->
        emit(connectUiState(st, clock()))
        while (st.pairExpiresAt != null) { delay(1_000); emit(connectUiState(st, clock())) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), connectUiState(link.status.value, clock()))

    fun open() = link.openPairing()
    fun close() = link.closePairing()
    fun disconnect(deviceId: String) = link.disconnect(deviceId)
    fun refreshPhones() = link.refreshPhones()
}
