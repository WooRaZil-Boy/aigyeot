package kr.aigyeot.phone.domain.repository

import kr.aigyeot.phone.domain.model.Inbound
import kr.aigyeot.shared.DevicesResponse
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.InviteResponse
import kr.aigyeot.shared.PairResponse
import kr.aigyeot.shared.PatchDeviceRequest
import kr.aigyeot.shared.PatchDeviceResponse
import kr.aigyeot.shared.SendMessageResponse
import kr.aigyeot.shared.UploadSongResponse
import java.io.File

/** 중계 요청 결과(계약 1장). 네트워크 실패는 둘로 나눈다: 보냈을 수 있음([Offline]), 닿지 않은 것이 확실함([Unreached]). */
sealed interface RelayResult<out T> {
    data class Ok<T>(val status: Int, val value: T) : RelayResult<T>
    data class Err(val status: Int, val code: String?, val retryAfterS: Long?) : RelayResult<Nothing>
    data object Offline : RelayResult<Nothing>

    /** 요청을 보내기 시작하기 전에 실패했다(이름 풀기·연결·TLS 등): 늦게 도착할 수 없다. 다시 보내는 것은 [Offline]과 같다. */
    data object Unreached : RelayResult<Nothing>
}

/**
 * 중계 HTTP API(계약 1장, iOS와 같은 이름). 토큰을 실은 요청이 401을 받으면, 그리고 끝날 때마다 중계에 닿았는지를
 * 만들 때 넘긴 콜백으로 알린다(부른 쪽이 취소돼도 잃지 않게).
 */
interface RelayClient {
    /** 중계 `Date` 헤더 − 폰 시계(ms). */
    val clockSkewMs: Long?

    /** 지금까지 시작한 요청의 마지막 순번. 소켓 관찰이 그보다 먼저 시작한 요청의 늦은 결과를 이기게 한다. */
    fun lastTicket(): Long

    suspend fun pair(code: String, pushToken: String?, installId: String): RelayResult<PairResponse>
    suspend fun devices(token: String): RelayResult<DevicesResponse>
    suspend fun patchMe(token: String, body: PatchDeviceRequest): RelayResult<PatchDeviceResponse>
    suspend fun deleteMe(token: String): RelayResult<Unit>
    suspend fun invite(token: String): RelayResult<InviteResponse>
    suspend fun send(token: String, env: Envelope): RelayResult<SendMessageResponse>
    suspend fun inbox(token: String, afterSeq: Long): RelayResult<InboxPage>
    suspend fun ack(token: String, ids: List<String>): RelayResult<Unit>
    suspend fun putSong(token: String, songId: String, file: File): RelayResult<UploadSongResponse>
}

/** 받은 함 한 쪽(`GET /v1/inbox`). 봉투는 데이터 층이 [Inbound]로 바꿔 준다. */
data class InboxPage(val messages: List<Inbound>, val more: Boolean)

/** 중계 WebSocket(계약 2장, iOS와 같은 이름). */
interface RelaySocket {
    fun open(token: String, listener: SocketListener): SocketHandle

    /** 받은 프레임을 [Inbound]로 바꾼다. 봉투가 아니면 null(적용도 ack도 하지 않는다). */
    fun read(text: String): Inbound?

    /** 받은 봉투의 ack 프레임. */
    fun ackFrame(ids: List<String>): String
}

/** 열린 WebSocket 하나. */
interface SocketHandle {
    fun send(text: String): Boolean
    fun close(code: Int)
    fun cancel()
}

/** WebSocket 사건(중계 IO 스레드에서 불린다). 닫기는 `onClosing`에서 맞받아 닫은 뒤 부른다. 실패 코드는 HTTP 코드, 없으면 null. */
interface SocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosing(code: Int)
    fun onFailure(httpCode: Int?)
}
