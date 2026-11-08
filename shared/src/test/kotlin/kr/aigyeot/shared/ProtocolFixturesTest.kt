package kr.aigyeot.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `protocol/`의 모든 예제를 해당 타입으로 decode → encode 해 원래 JSON과 트리로 같은지 본다. */
class ProtocolFixturesTest {
    private val json = ProtocolJson

    // 파일 이름(확장자 뺀 것) → 타입. 값이 문자열이면 그 type의 봉투, 직렬화기이면 API 본문.
    private val table: Map<String, Any> = mapOf(
        // 봉투(메시지 type별)
        "paired" to "paired",
        "unpaired" to "unpaired",
        "feeding_started" to "feeding_started",
        "feeding_started_send" to "feeding_started",
        "song_added" to "song_added",
        "song_removed" to "song_removed",
        "song_selected" to "song_selected",
        "invite_created" to "invite_created",
        "state_request" to "state_request",
        "song_status" to "song_status",
        "song_status_removed" to "song_status",
        "song_status_rejected" to "song_status",
        "feeding_state" to "feeding_state",
        "feeding_state_reply" to "feeding_state",
        "report_summary" to "report_summary",
        "report_summary_handoff" to "report_summary",
        "handoff_alert" to "handoff_alert",
        "handoff_alert_arrival" to "handoff_alert",
        "care_summary" to "care_summary",
        "care_summary_off" to "care_summary",
        "report_summary_womb" to "report_summary",
        "ack" to "ack",
        // 중계 API
        "api_error" to ErrorResponse.serializer(),
        "api_health_response" to HealthResponse.serializer(),
        "api_cars_request" to RegisterCarRequest.serializer(),
        "api_cars_response" to RegisterCarResponse.serializer(),
        "api_pair_codes_request" to Empty.serializer(),
        "api_pair_codes_response" to PairCodeResponse.serializer(),
        "api_pair_request" to PairRequest.serializer(),
        "api_pair_request_push" to PairRequest.serializer(),
        "api_pair_response" to PairResponse.serializer(),
        "api_invites_response" to InviteResponse.serializer(),
        "api_devices_response" to DevicesResponse.serializer(),
        "api_devices_me_request" to PatchDeviceRequest.serializer(),
        "api_devices_me_clear_request" to PatchDeviceRequest.serializer(),
        "api_devices_me_keep_request" to PatchDeviceRequest.serializer(),
        "api_devices_me_response" to PatchDeviceResponse.serializer(),
        "api_messages_response" to SendMessageResponse.serializer(),
        "api_inbox_response" to InboxResponse.serializer(),
        "api_songs_response" to UploadSongResponse.serializer(),
        "api_llm_request" to LlmRequest.serializer(),
        "api_llm_request_minimal" to LlmRequest.serializer(),
        "api_llm_request_care" to LlmRequest.serializer(),
        "api_llm_response" to LlmResponse.serializer(),
        "api_llm_response_tool" to LlmResponse.serializer(),
        "api_llm_response_answer" to LlmResponse.serializer(),
    )

    private fun roundTrip(text: String, serializer: KSerializer<*>): Pair<JsonElement, JsonElement> {
        @Suppress("UNCHECKED_CAST")
        val s = serializer as KSerializer<Any>
        val original = Json.parseToJsonElement(text)
        val encoded = json.encodeToJsonElement(s, json.decodeFromString(s, text))
        return original to encoded
    }

    @Test
    fun 모든_예제가_왕복해도_같다() {
        val dir = File(assertNotNull(javaClass.getResource("/protocol")).toURI())
        val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty())

        // 대응 없는 파일, 파일 없는 대응 모두 실패
        assertEquals(table.keys.sorted(), files.map { it.nameWithoutExtension }, "표와 protocol/ 파일이 다르다")

        for (f in files) {
            val text = f.readText()
            val target = table.getValue(f.nameWithoutExtension)
            if (target is String) {
                val (o, e) = roundTrip(text, Envelope.serializer())
                assertEquals(o, e, "${f.name}: 봉투")
                val env = json.decodeFromString(Envelope.serializer(), text)
                assertEquals(target, env.type, "${f.name}: type")
                val bs = assertNotNull(bodySerializers[target], "${f.name}: body 직렬화기 없음")
                @Suppress("UNCHECKED_CAST")
                val s = bs as KSerializer<Any>
                val body: JsonObject = env.body
                assertEquals(body, json.encodeToJsonElement(s, json.decodeFromJsonElement(s, body)), "${f.name}: body")
            } else {
                val (o, e) = roundTrip(text, target as KSerializer<*>)
                assertEquals(o, e, f.name)
            }
        }
    }
}
