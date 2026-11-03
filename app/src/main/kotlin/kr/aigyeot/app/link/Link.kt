package kr.aigyeot.app.link

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event

/** 차 쪽 중계 연결. link가 채운다. 공개 모양은 고치지 않는다(handoff README 4장). */
class Link(
    private val context: Context,
    private val db: AppDatabase,
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    private val state: () -> EngineState?,         // 최신 상태 읽기(상황판 호출에 넣는 상태 JSON, contracts-link 8.8)
) {
    val status: StateFlow<LinkStatus> = MutableStateFlow(LinkStatus())   // 차 화면 연결·폰 목록용
    fun start() {}                                 // 차 등록·WebSocket·보낼 함(감시 서비스 시작 때)
    fun stop() {}
    fun handle(cmd: Command) {}                    // SendToPhone, Qa(상황판 호출). DeleteRelayRecords는 store가 한 트랜잭션으로 처리한다(5.9)
    fun openPairing() {}                           // 연결 화면 열림(contracts-link 8.7 ①~③)
    fun closePairing() {}                          // 8.7 ④
    fun disconnect(deviceId: String) {}            // 8.7 ⑤
    fun refreshPhones() {}                         // 8.7 ⑥
    fun selectSong(songId: String) {}              // 정차 화면에서 고른 곡(G20, 8.5 song_selected): 보내고 돌아온 seq로 적용
    suspend fun receiveForScenario(envelopeJson: String) {}   // 장면 'phone' 단계(계약 7.3): 중계 없이 받는 쪽에 바로
}

data class LinkStatus(
    val registered: Boolean = false, val online: Boolean = false,
    val pairCode: String? = null, val pairUri: String? = null, val pairExpiresAt: Long? = null,
    val phones: List<LinkedPhone> = emptyList(), val error: String? = null,
)

data class LinkedPhone(val deviceId: String, val role: String, val lastSeenAt: Long?)
