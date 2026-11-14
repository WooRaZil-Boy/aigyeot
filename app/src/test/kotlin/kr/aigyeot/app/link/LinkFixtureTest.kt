package kr.aigyeot.app.link

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kr.aigyeot.app.common.instantOf
import kr.aigyeot.app.common.ulid
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.SwitchSound
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.FeedingStateBody
import kr.aigyeot.shared.LlmRequest
import kr.aigyeot.shared.LlmResponse
import kr.aigyeot.shared.LlmState
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.Role
import kr.aigyeot.shared.Sender
import kr.aigyeot.shared.SongStatusBody
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// shared/ JSON 예제를 차 쪽 받기·보내기에 넣어 같은 모양인지 본다.
@RunWith(RobolectricTestRunner::class)
class LinkFixtureTest : LinkKit() {
    private fun fixture(name: String) = File("../shared/src/test/resources/protocol/$name").readText()
    private fun envOf(name: String) = ProtocolJson.decodeFromString(Envelope.serializer(), fixture(name))
    private fun keys(json: String) = Json.parseToJsonElement(json).jsonObject.keys
    private fun bodyKeys(name: String) = Json.parseToJsonElement(fixture(name)).jsonObject.getValue("body").jsonObject.keys

    @Test fun receivedFixtures_applyWithoutBodyErrors() {
        profile()
        for (f in listOf("paired.json", "feeding_started.json", "song_added.json", "song_selected.json", "song_removed.json", "state_request.json", "unpaired.json")) {
            assertEquals(Link.Rx.ACK, runBlocking { link.receive(envOf(f)) }, f)
        }
        assertEquals(instantOf("2026-11-05T08:12:21+09:00"), events.filterIsInstance<FeedingStarted>().single().startedAt)
        assertTrue(dao.mark(PHONE) != null)                                   // paired.json의 기기
        assertTrue(dao.songs().isNotEmpty())                                  // song_added.json·song_removed.json
    }

    @Test fun sentBodies_roundTripAndMatchFixtureFields() {
        profile(lastFeeding = T0)
        song(SA, ACTIVE, 10); song(SB, REJECTED, 11)
        // 예제는 폰이 보내는 모양이라 from·seq가 없다. 중계가 채운 모양으로 받는다
        runBlocking { link.receive(envOf("state_request.json").copy(seq = 800, from = Sender(Role.OWNER, PHONE))) }
        val fs = outbox("feeding_state").single()
        val decoded = ProtocolJson.decodeFromString(FeedingStateBody.serializer(), fs.bodyJson)
        assertEquals(fs.bodyJson, ProtocolJson.encodeToString(FeedingStateBody.serializer(), decoded))
        assertEquals(bodyKeys("feeding_state.json"), keys(fs.bodyJson))
        for (st in outbox("song_status")) {
            val b = ProtocolJson.decodeFromString(SongStatusBody.serializer(), st.bodyJson)
            assertEquals(st.bodyJson, ProtocolJson.encodeToString(SongStatusBody.serializer(), b))
            assertEquals(bodyKeys("song_status.json"), keys(st.bodyJson))
        }
        assertEquals(envOf("feeding_state_reply.json").to, fs.toDevice)       // state_request의 답은 그 폰에게만
    }

    @Test fun llmRequestAndToolResponse_matchFixtures() {
        val st = llmState(null, T0, 0)
        val req = ProtocolJson.encodeToString(LlmRequest.serializer(), LlmRequest(ulid(T0), "왜 울어?", st))
        assertEquals(keys(fixture("api_llm_request.json")), keys(req))
        val stateKeys = Json.parseToJsonElement(fixture("api_llm_request.json")).jsonObject.getValue("state").jsonObject.keys
        assertEquals(stateKeys, Json.parseToJsonElement(req).jsonObject.getValue("state").jsonObject.keys)
        val r = ProtocolJson.decodeFromString(LlmResponse.serializer(), fixture("api_llm_response_tool.json"))
        val (tools, text) = qaEvents(r, "L1", T0, st, "자장가 틀어줘", emptyList())
        assertEquals(listOf(SwitchSound(T0, Method(MethodKind.LULLABY), "L1")), tools)
        assertEquals("소리를 바꿀게요.", text)
        val a = ProtocolJson.decodeFromString(LlmResponse.serializer(), fixture("api_llm_response_answer.json"))
        // 예제는 글 없이 사실 주제(feeding·sleep)만 고른 응답이라 차가 주제 문장을 말한다. 상태값은 돌봄 요약 예제의 것
        val care = ProtocolJson.decodeFromJsonElement(LlmState.serializer(), Json.parseToJsonElement(fixture("api_llm_request_care.json")).jsonObject.getValue("state"))
        assertEquals("마지막 수유 시작부터 2시간 50분 지났고, 기록상 오늘 3번 먹었어요. 기록상 40분 전에 시작한 잠이 아직 끝나지 않았고, 오늘은 모두 7시간 10분, 지난밤에는 2번 깼어요.",
            qaEventsCare(a, "L1", T0, care, "q", emptyList()).let { assertEquals("", it.second, "글은 호스트가 큐에서 짠다"); fallbackReply(it.factTopics, care) })
        assertEquals("수유 기록을 확인할 수 없어요. 잠 기록을 확인할 수 없어요.", qaEventsCare(a, "L1", T0, st, "q", emptyList()).let { fallbackReply(it.factTopics, st) })   // 상태값이 비면 확인할 수 없다
    }
}
