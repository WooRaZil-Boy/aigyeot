package kr.aigyeot.phone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kr.aigyeot.phone.core.envelope
import kr.aigyeot.shared.DevicesResponse
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.ErrorResponse
import kr.aigyeot.shared.InboxResponse
import kr.aigyeot.shared.InviteResponse
import kr.aigyeot.shared.PairRequest
import kr.aigyeot.shared.PairResponse
import kr.aigyeot.shared.PatchDeviceRequest
import kr.aigyeot.shared.PatchDeviceResponse
import kr.aigyeot.shared.Platform
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.SendMessageResponse
import kr.aigyeot.shared.UploadSongResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 중계 HTTP API·WebSocket(계약 1·2장). 토큰은 `Authorization` 헤더로만 보낸다. 본문·토큰은 로그에 남기지 않는다. */
class RelayClient(private val baseUrl: String, private val appVersion: String, private val http: OkHttpClient = OkHttpClient()) {
    sealed interface Res<out T> {
        data class Ok<T>(val status: Int, val value: T) : Res<T>
        data class Err(val status: Int, val code: String?, val retryAfterS: Long?) : Res<Nothing>
        data object Offline : Res<Nothing>
    }

    /** 중계 `Date` 헤더 − 폰 시계(ms). 2분 넘게 다르면 홈에 경고한다(9장). */
    @Volatile
    var clockSkewMs: Long? = null
        private set

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun <T> encode(s: KSerializer<T>, v: T): RequestBody = ProtocolJson.encodeToString(s, v).toRequestBody(jsonType)

    private fun request(path: String, token: String?) = Request.Builder()
        .url(baseUrl + path)
        .header("X-Client", "android/$appVersion")
        .apply { if (token != null) header("Authorization", "Bearer $token") }

    private suspend fun <T> exec(req: Request, read: (String) -> T): Res<T> = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { res ->
                res.header("Date")?.let { d ->
                    runCatching { ZonedDateTime.parse(d, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                        .getOrNull()?.let { clockSkewMs = it - System.currentTimeMillis() }
                }
                val text = res.body.string()
                if (res.isSuccessful) {
                    Res.Ok(res.code, read(text))
                } else {
                    val code = runCatching { ProtocolJson.decodeFromString(ErrorResponse.serializer(), text).error.code }.getOrNull()
                    Res.Err(res.code, code, res.header("Retry-After")?.toLongOrNull())
                }
            }
        } catch (e: IOException) {
            Res.Offline
        }
    }

    private fun <T> parse(s: KSerializer<T>): (String) -> T = { ProtocolJson.decodeFromString(s, it) }
    private val nothing: (String) -> Unit = {}

    suspend fun pair(code: String, pushToken: String?, installId: String) = exec(
        request("/v1/pair", null).header("X-Install-Id", installId)
            .post(encode(PairRequest.serializer(), PairRequest(code, Platform.ANDROID, pushToken, appVersion))).build(),
        parse(PairResponse.serializer()),
    )

    suspend fun devices(token: String) = exec(request("/v1/devices", token).get().build(), parse(DevicesResponse.serializer()))

    suspend fun patchMe(token: String, body: PatchDeviceRequest) = exec(
        request("/v1/devices/me", token).patch(encode(PatchDeviceRequest.serializer(), body)).build(),
        parse(PatchDeviceResponse.serializer()),
    )

    suspend fun deleteMe(token: String) = exec(request("/v1/devices/me", token).delete().build(), nothing)

    suspend fun invite(token: String) = exec(
        request("/v1/invites", token)
            .post(encode(Envelope.serializer(), envelope("invite_created", buildJsonObject {}, System.currentTimeMillis())))
            .build(),
        parse(InviteResponse.serializer()),
    )

    suspend fun send(token: String, env: Envelope) = exec(
        request("/v1/messages", token).post(encode(Envelope.serializer(), env)).build(),
        parse(SendMessageResponse.serializer()),
    )

    suspend fun inbox(token: String, afterSeq: Long) =
        exec(request("/v1/inbox?after_seq=$afterSeq&limit=100", token).get().build(), parse(InboxResponse.serializer()))

    suspend fun ack(token: String, ids: List<String>) = exec(
        request("/v1/ack", token).post(encode(Envelope.serializer(), ackEnvelope(ids))).build(),
        nothing,
    )

    suspend fun putSong(token: String, songId: String, file: File) = exec(
        request("/v1/songs/$songId", token).put(file.asRequestBody("audio/mp4".toMediaType())).build(),
        parse(UploadSongResponse.serializer()),
    )

    /** `GET /v1/ws`. 업그레이드 요청에 Authorization 헤더를 싣는다. OkHttp `pingInterval`은 쓰지 않는다(2장). */
    fun socket(token: String, listener: WebSocketListener): WebSocket =
        http.newWebSocket(request("/v1/ws", token).url(baseUrl.replaceFirst("http", "ws") + "/v1/ws").build(), listener)

    companion object {
        fun ackEnvelope(ids: List<String>) = envelope(
            "ack",
            buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) },
            System.currentTimeMillis(),
        )

        fun ackFrame(ids: List<String>) = ProtocolJson.encodeToString(Envelope.serializer(), ackEnvelope(ids))
    }
}
