package kr.aigyeot.app.link

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kr.aigyeot.shared.DevicesResponse
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.ErrorResponse
import kr.aigyeot.shared.InboxResponse
import kr.aigyeot.shared.LlmRequest
import kr.aigyeot.shared.LlmResponse
import kr.aigyeot.shared.PairCodeResponse
import kr.aigyeot.shared.Platform
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.RegisterCarRequest
import kr.aigyeot.shared.RegisterCarResponse
import kr.aigyeot.shared.SendMessageResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 중계 주소(contracts-link 0장). 한 곳에만 둔다. release는 배포한 중계(L98·L101), 폰 두 앱과 같은 글자. */
internal object RelayAddress {
    const val DEBUG = "http://10.0.2.2:8787"   // 에뮬레이터에서 맥의 wrangler dev
    const val RELEASE = "https://aigyeot-relay.byuncorry.workers.dev"   // HTTPS·WSS만(0장 '전송')
    fun of(ctx: Context) = if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) DEBUG else RELEASE
}

/** 중계 응답. Err의 code는 오류 본문의 `error.code`(1.1). */
sealed interface Res<out T> {
    data class Ok<T>(val status: Int, val value: T) : Res<T>
    data class Err(val status: Int, val code: String?, val retryAfterS: Long?) : Res<Nothing>
    data object Offline : Res<Nothing>
}

/** WebSocket 한 개. 보내기는 ack·ping 글자뿐이다(2장). */
interface RelaySocket {
    fun send(text: String): Boolean
    fun cancel()
}

interface SocketEvents {
    fun onOpen()
    fun onText(text: String)
    /** 닫힘. 정상 닫기는 닫기 코드, 업그레이드 실패는 HTTP 코드, 네트워크 오류는 -1. */
    fun onClosed(code: Int)
}

/** 중계 HTTP API·WebSocket을 감싼 얇은 층(contracts-link 1·2장). 시험은 가짜로 바꾼다. */
interface Relay {
    suspend fun registerCar(): Res<RegisterCarResponse>
    suspend fun pairCode(token: String): Res<PairCodeResponse>
    suspend fun deletePairCode(token: String): Res<Unit>
    suspend fun devices(token: String): Res<DevicesResponse>
    suspend fun deleteDevice(token: String, deviceId: String): Res<Unit>
    suspend fun send(token: String, env: Envelope): Res<SendMessageResponse>
    suspend fun inbox(token: String, afterSeq: Long): Res<InboxResponse>
    suspend fun ack(token: String, ids: List<String>): Res<Unit>
    /** 노래 파일을 [dest]에 쓴다. 값은 받은 바이트 수. [maxBytes]를 넘으면 maxBytes + 1에서 멈춘다. */
    suspend fun song(token: String, songId: String, dest: File, maxBytes: Long): Res<Long>
    /** [id]는 보낼 함 records_clear 항목 id: 다시 보내도 같아서 중계가 두 번째를 건너뛴다(L67). [dropIds]는 1,000개 이하(L81), 지우기 때 보낼 함에서 뺀 report_summary·handoff_alert id: 본문 `{"drop_ids":[…]}`로 보내 늦게 닿은 같은 id를 중계가 배달하지 않게 한다(L79). */
    suspend fun deleteRecords(token: String, id: String, dropIds: List<String>): Res<Unit>
    suspend fun llm(token: String, req: LlmRequest): Res<LlmResponse>
    fun socket(token: String, events: SocketEvents): RelaySocket
}

/** ack 봉투(3.3 `ack`). WebSocket 프레임과 `POST /v1/ack`가 같이 쓴다. */
internal fun ackEnvelope(ids: List<String>, now: Long) =
    envelopeOf(ulid(now), "ack", buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }, now)

internal fun ackFrame(ids: List<String>, now: Long) = ProtocolJson.encodeToString(Envelope.serializer(), ackEnvelope(ids, now))

/**
 * 받은 함 응답. 봉투 하나를 못 읽어도 나머지는 받는다. 못 읽는 봉투는 id가 있으면 v = 0 'unreadable'로 바꿔
 * 받는 쪽 1단계(v != 1 → 로그·ack만)가 ack하게 하고(안 그러면 함이 영원히 막힌다), id·seq 둘 다 없으면 버린다(8.3).
 * seq는 읽히면 id가 없어도 남긴다(쪽 넘기기 `after_seq`가 멈추지 않게). 빈 id는 ack하지 않는다.
 */
internal fun parseInbox(text: String): InboxResponse {
    val root = ProtocolJson.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("받은 함 응답이 객체가 아님")
    val msgs = (root["messages"] as? JsonArray).orEmpty().mapNotNull(::parseEnvelope)
    return InboxResponse(msgs, (root["more"] as? JsonPrimitive)?.booleanOrNull ?: false)
}

/** 봉투 하나. 못 읽으면 seq만 남긴 v = 0 'unreadable'(id가 없으면 빈 id). seq도 없으면 null. 받은 함과 소켓이 같이 쓴다. */
internal fun parseEnvelope(e: JsonElement): Envelope? =
    runCatching { ProtocolJson.decodeFromJsonElement(Envelope.serializer(), e) }.getOrNull() ?: run {
        val o = e as? JsonObject ?: return@run null
        val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val seq = (o["seq"] as? JsonPrimitive)?.longOrNull
        if (id == null && seq == null) return@run null
        Envelope(v = 0, id = id ?: "", seq = seq, type = "unreadable", ts = "", body = JsonObject(emptyMap()))
    }

/** OkHttp 구현. 토큰은 Authorization 헤더로만 보내고, 본문·토큰은 로그에 남기지 않는다(6.6). */
internal class OkRelay(private val baseUrl: String, private val http: OkHttpClient = OkHttpClient()) : Relay {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val llmHttp = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()   // 8.8 HTTP 타임아웃 10초

    private fun <T> encode(s: KSerializer<T>, v: T): RequestBody = ProtocolJson.encodeToString(s, v).toRequestBody(jsonType)
    private fun <T> parse(s: KSerializer<T>): (Response) -> T = { ProtocolJson.decodeFromString(s, it.body.string()) }
    private val nothing: (Response) -> Unit = {}

    private fun req(path: String, token: String?) = Request.Builder().url(baseUrl + path).header("X-Client", "pleos/$APP_VERSION")
        .apply { if (token != null) header("Authorization", "Bearer $token") }

    private suspend fun <T> exec(build: () -> Request, read: (Response) -> T, client: OkHttpClient = http): Res<T> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(build()).execute().use { res ->
                    if (res.isSuccessful) {
                        // 본문을 못 읽는 것은 오프라인이 아니다(다시 보내도 같다). SerializationException은 IllegalArgumentException의 하위라 먼저 잡는다
                        try { Res.Ok(res.code, read(res)) } catch (e: SerializationException) { Res.Err(res.code, "bad_response", null) }
                    } else Res.Err(
                        res.code,
                        runCatching { ProtocolJson.decodeFromString(ErrorResponse.serializer(), res.body.string()).error.code }.getOrNull(),
                        res.header("Retry-After")?.toLongOrNull(),
                    )
                }
            } catch (e: IOException) {
                Res.Offline
            } catch (e: IllegalArgumentException) {
                Res.Offline   // 주소가 비었거나(release 미정) 틀림
            }
        }

    override suspend fun registerCar() = exec(
        { req("/v1/cars", null).post(encode(RegisterCarRequest.serializer(), RegisterCarRequest(Platform.PLEOS, APP_VERSION))).build() },
        parse(RegisterCarResponse.serializer()),
    )

    override suspend fun pairCode(token: String) =
        exec({ req("/v1/pair-codes", token).post("{}".toRequestBody(jsonType)).build() }, parse(PairCodeResponse.serializer()))

    override suspend fun deletePairCode(token: String) = exec({ req("/v1/pair-codes", token).delete().build() }, nothing)

    override suspend fun devices(token: String) = exec({ req("/v1/devices", token).get().build() }, parse(DevicesResponse.serializer()))

    override suspend fun deleteDevice(token: String, deviceId: String) =
        exec({ req("/v1/devices/$deviceId", token).delete().build() }, nothing)

    override suspend fun send(token: String, env: Envelope) =
        exec({ req("/v1/messages", token).post(encode(Envelope.serializer(), env)).build() }, parse(SendMessageResponse.serializer()))

    override suspend fun inbox(token: String, afterSeq: Long) =
        exec({ req("/v1/inbox?after_seq=$afterSeq&limit=100", token).get().build() }, { parseInbox(it.body.string()) })

    override suspend fun ack(token: String, ids: List<String>) = exec(
        { req("/v1/ack", token).post(encode(Envelope.serializer(), ackEnvelope(ids, System.currentTimeMillis()))).build() },
        nothing,
    )

    override suspend fun song(token: String, songId: String, dest: File, maxBytes: Long) = exec(
        { req("/v1/songs/$songId", token).get().build() },
        { res ->
            var n = 0L
            res.body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (n <= maxBytes) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        n += r
                    }
                }
            }
            minOf(n, maxBytes + 1)
        },
    )

    override suspend fun deleteRecords(token: String, id: String, dropIds: List<String>) =
        exec({ req("/v1/records?id=$id", token).delete(buildJsonObject { put("drop_ids", JsonArray(dropIds.map(::JsonPrimitive))) }.toString().toRequestBody(jsonType)).build() }, nothing)

    override suspend fun llm(token: String, req: LlmRequest) = exec(
        { req("/v1/llm", token).post(encode(LlmRequest.serializer(), req)).build() },
        parse(LlmResponse.serializer()),
        llmHttp,
    )

    /** `GET /v1/ws`. OkHttp `pingInterval`은 쓰지 않는다(글자 ping으로 통일, 2장). */
    override fun socket(token: String, events: SocketEvents): RelaySocket {
        val url = baseUrl.replaceFirst("http", "ws") + "/v1/ws"   // http→ws, https→wss
        val ws = try {
            http.newWebSocket(req("/v1/ws", token).url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = events.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = events.onText(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    events.onClosed(code)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = events.onClosed(response?.code ?: -1)
            })
        } catch (e: IllegalArgumentException) {
            events.onClosed(-1)   // 주소가 비었음
            null
        }
        return object : RelaySocket {
            override fun send(text: String) = ws?.send(text) ?: false
            override fun cancel() { ws?.cancel() }
        }
    }

    companion object { const val APP_VERSION = "0.1" }
}
