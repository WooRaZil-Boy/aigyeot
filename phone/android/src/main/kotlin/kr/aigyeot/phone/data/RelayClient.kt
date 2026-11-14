package kr.aigyeot.phone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kr.aigyeot.phone.domain.model.Inbound
import kr.aigyeot.phone.domain.model.InboundMessage
import kr.aigyeot.phone.domain.model.envelope
import kr.aigyeot.phone.domain.repository.InboxPage
import kr.aigyeot.phone.domain.repository.RelayClient
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.phone.domain.repository.RelaySocket
import kr.aigyeot.phone.domain.repository.SocketHandle
import kr.aigyeot.phone.domain.repository.SocketListener
import kr.aigyeot.shared.DevicesResponse
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.ErrorResponse
import kr.aigyeot.shared.FeedingStateBody
import kr.aigyeot.shared.HandoffAlertBody
import kr.aigyeot.shared.InboxResponse
import kr.aigyeot.shared.InviteResponse
import kr.aigyeot.shared.PairRequest
import kr.aigyeot.shared.PairResponse
import kr.aigyeot.shared.PairedBody
import kr.aigyeot.shared.PatchDeviceRequest
import kr.aigyeot.shared.PatchDeviceResponse
import kr.aigyeot.shared.Platform
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.Role
import kr.aigyeot.shared.SendMessageResponse
import kr.aigyeot.shared.SongStatusBody
import kr.aigyeot.shared.UnpairedBody
import kr.aigyeot.shared.UploadSongResponse
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 중계 HTTP API·WebSocket. 토큰은 `Authorization` 헤더로만 보낸다. 본문·토큰은 로그에 남기지 않는다. */
class OkHttpRelayClient(
    private val baseUrl: String,
    private val appVersion: String,
    http: OkHttpClient = OkHttpClient(),
    /** 토큰을 실은 요청이 401을 받으면 그 토큰으로 바로 부른다(IO 스레드). 부른 쪽이 취소돼도 불린다. */
    private val onUnauthorized: (token: String) -> Unit = {},
    /** 토큰을 실은 요청이 끝날 때마다 중계에 닿았는지(IO 스레드). `ticket`은 요청을 시작한 순번이다. */
    private val onObserved: (token: String, ticket: Long, up: Boolean) -> Unit = { _, _, _ -> },
) : RelayClient, RelaySocket {
    /** 중계 `Date` 헤더 − 폰 시계(ms). 2분 넘게 다르면 홈에 경고한다. */
    @Volatile
    override var clockSkewMs: Long? = null
        private set

    /** 이 요청의 머리를 쓰기 시작했는가(OkHttp 다시 시도 포함). 실패 원인(예외 종류)이 아니라 실제로 보냈는지로 닿지 않음을 가린다. */
    private class Started {
        @Volatile var value = false
    }

    private val http: OkHttpClient = http.newBuilder().eventListener(object : EventListener() {
        override fun requestHeadersStart(call: Call) {
            call.request().tag(Started::class.java)?.value = true
        }
    }).build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun <T> encode(s: KSerializer<T>, v: T): RequestBody = ProtocolJson.encodeToString(s, v).toRequestBody(jsonType)

    private fun request(path: String, token: String?) = Request.Builder()
        .url(baseUrl + path)
        .header("X-Client", "android/$appVersion")
        .apply { if (token != null) header("Authorization", "Bearer $token") }

    private val tickets = java.util.concurrent.atomic.AtomicLong()

    override fun lastTicket(): Long = tickets.get()

    private suspend fun <T> exec(req: Request, read: (String) -> T): RelayResult<T> = withContext(Dispatchers.IO) {
        // 토큰을 실은 요청은 중계에 닿았는지를 순번과 함께 넘긴다('중계 불통': 네트워크 실패·5xx면 닿지 않음).
        val ticket = tickets.incrementAndGet()
        val token = req.header("Authorization")?.removePrefix("Bearer ")
        var up = false // 본문까지 받은 최종 결과로 요청마다 한 번 알린다(헤더 뒤 본문이 끊기면 닿지 않음)
        val started = Started()
        try {
            http.newCall(req.newBuilder().tag(Started::class.java, started).build()).execute().use { res ->
                // 본문을 읽기 전에 넘긴다: 부른 코루틴이 취소되면 이 결과는 돌아가지 못한다(어디서든 401 → GONE).
                if (res.code == 401) req.header("Authorization")?.removePrefix("Bearer ")?.let(onUnauthorized)
                res.header("Date")?.let { d ->
                    runCatching { ZonedDateTime.parse(d, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                        .getOrNull()?.let { clockSkewMs = it - System.currentTimeMillis() }
                }
                // 상태 줄을 받았는데 본문이 끊겼으면: 오류 코드 없이도 처리가 정해지는 401·429·5xx만 상태와 Retry-After로 돌려준다
                // (429를 오프라인으로 잃지 않게). 나머지(409 song_quota·422 song_blob_missing 등)와 성공 응답은 내용을 모르니 오프라인으로 보고 다시 보낸다.
                val text = try {
                    res.body.string()
                } catch (e: IOException) {
                    if (res.code != 401 && res.code != 429 && res.code < 500) throw e
                    up = res.code < 500
                    return@use RelayResult.Err(res.code, null, res.header("Retry-After")?.toLongOrNull())
                }
                up = res.code < 500
                if (res.isSuccessful) {
                    // 성공 본문을 해석하지 못하면(형식이 다름) 앱을 끝내지 않고 오프라인처럼 다시 시도한다(같은 id라 중계는 한 번만 처리).
                    val value = try {
                        read(text)
                    } catch (e: kotlinx.serialization.SerializationException) {
                        return@use RelayResult.Offline
                    } catch (e: IllegalArgumentException) {
                        return@use RelayResult.Offline
                    }
                    RelayResult.Ok(res.code, value)
                } else {
                    val code = runCatching { ProtocolJson.decodeFromString(ErrorResponse.serializer(), text).error.code }.getOrNull()
                    RelayResult.Err(res.code, code, res.header("Retry-After")?.toLongOrNull())
                }
            }
        } catch (e: IOException) {
            if (started.value) RelayResult.Offline else RelayResult.Unreached
        } finally {
            token?.let { onObserved(it, ticket, up) }
        }
    }

    private fun <T> parse(s: KSerializer<T>): (String) -> T = { ProtocolJson.decodeFromString(s, it) }
    private val nothing: (String) -> Unit = {}

    override suspend fun pair(code: String, pushToken: String?, installId: String) = exec(
        request("/v1/pair", null).header("X-Install-Id", installId)
            .post(encode(PairRequest.serializer(), PairRequest(code, Platform.ANDROID, pushToken, appVersion))).build(),
        parse(PairResponse.serializer()),
    )

    override suspend fun devices(token: String) = exec(request("/v1/devices", token).get().build(), parse(DevicesResponse.serializer()))

    override suspend fun patchMe(token: String, body: PatchDeviceRequest) = exec(
        request("/v1/devices/me", token).patch(encode(PatchDeviceRequest.serializer(), body)).build(),
        parse(PatchDeviceResponse.serializer()),
    )

    override suspend fun deleteMe(token: String) = exec(request("/v1/devices/me", token).delete().build(), nothing)

    override suspend fun invite(token: String) = exec(
        request("/v1/invites", token)
            .post(encode(Envelope.serializer(), envelope("invite_created", buildJsonObject {}, System.currentTimeMillis())))
            .build(),
        parse(InviteResponse.serializer()),
    )

    override suspend fun send(token: String, env: Envelope) = exec(
        request("/v1/messages", token).post(encode(Envelope.serializer(), env)).build(),
        parse(SendMessageResponse.serializer()),
    )

    override suspend fun inbox(token: String, afterSeq: Long) =
        exec(request("/v1/inbox?after_seq=$afterSeq&limit=100", token).get().build()) { text ->
            parse(InboxResponse.serializer())(text).let { r -> InboxPage(r.messages.map { it.inbound }, r.more) }
        }

    override suspend fun ack(token: String, ids: List<String>) = exec(
        request("/v1/ack", token).post(encode(Envelope.serializer(), ackEnvelope(ids))).build(),
        nothing,
    )

    override suspend fun putSong(token: String, songId: String, file: File) = exec(
        request("/v1/songs/$songId", token).put(file.asRequestBody("audio/mp4".toMediaType())).build(),
        parse(UploadSongResponse.serializer()),
    )

    /** `GET /v1/ws`. 업그레이드 요청에 Authorization 헤더를 싣는다. OkHttp `pingInterval`은 쓰지 않는다. */
    override fun open(token: String, listener: SocketListener): SocketHandle {
        val ws = http.newWebSocket(
            request("/v1/ws", token).url(baseUrl.replaceFirst("http", "ws") + "/v1/ws").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    listener.onClosing(code)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(response?.code)
            },
        )
        return object : SocketHandle {
            override fun send(text: String) = ws.send(text)
            override fun close(code: Int) {
                ws.close(code, null)
            }
            override fun cancel() = ws.cancel()
        }
    }

    override fun read(text: String): Inbound? = runCatching { ProtocolJson.decodeFromString(Envelope.serializer(), text) }.getOrNull()?.inbound

    override fun ackFrame(ids: List<String>) = ProtocolJson.encodeToString(Envelope.serializer(), ackEnvelope(ids))

    private companion object {
        fun ackEnvelope(ids: List<String>) = envelope(
            "ack",
            buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) },
            System.currentTimeMillis(),
        )
    }
}

/**
 * 봉투(메시지 DTO) → 도메인 [Inbound]. 받은 봉투를 바꾸는 곳은 여기 한 곳이다(iOS `Domain/Protocol/EnvelopeMapping`의 `Envelope.inbound`).
 * body를 type에 맞는 메시지 값으로 읽는다. 모르는 버전·type, 폰이 적용하지 않는 type(feeding_started 등), 읽지 못한 body는 Ignored.
 */
internal val Envelope.inbound: Inbound
    get() = Inbound(id, seq ?: 0, from?.role == Role.CAR, message(), car)

private fun Envelope.message(): InboundMessage {
    if (v != 1) return InboundMessage.Ignored
    fun <B> read(s: KSerializer<B>, make: (B) -> InboundMessage): InboundMessage =
        runCatching { ProtocolJson.decodeFromJsonElement(s, body) }.getOrNull()?.let(make) ?: InboundMessage.Ignored
    return when (type) {
        "paired" -> read(PairedBody.serializer(), InboundMessage::Paired)
        "unpaired" -> read(UnpairedBody.serializer(), InboundMessage::Unpaired)
        "feeding_state" -> read(FeedingStateBody.serializer(), InboundMessage::FeedingState)
        "song_status" -> read(SongStatusBody.serializer(), InboundMessage::SongStatus)
        "report_summary" -> read(ReportSummaryBody.serializer(), InboundMessage::Report)
        "handoff_alert" -> read(HandoffAlertBody.serializer(), InboundMessage::HandoffAlert)
        else -> InboundMessage.Ignored
    }
}
