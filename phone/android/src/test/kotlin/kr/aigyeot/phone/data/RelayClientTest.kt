package kr.aigyeot.phone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kr.aigyeot.phone.domain.model.Inbound
import kr.aigyeot.phone.domain.model.InboundMessage
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.HandoffAlertBody
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.Role
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RelayClientTest {
    private val release = CountDownLatch(1)
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    init {
        // 토큰 "slow"는 놓아줄 때까지 답을 붙잡고 401, "forbidden"은 403, "down"은 503, 나머지는 401.
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val r = it.getInputStream().bufferedReader()
                        val auth = generateSequence { r.readLine()?.takeIf(String::isNotEmpty) }
                            .firstOrNull { l -> l.startsWith("Authorization:", ignoreCase = true) }?.substringAfter("Bearer ")
                        if (auth == "slow") release.await(5, TimeUnit.SECONDS)
                        if (auth?.startsWith("cut") == true) { // 헤더 뒤 본문을 다 보내지 않고 끊는다
                            val head = when (auth) {
                                "cut429" -> "HTTP/1.1 429 X\r\nRetry-After: 120"
                                "cut409" -> "HTTP/1.1 409 X"
                                "cut422" -> "HTTP/1.1 422 X"
                                else -> "HTTP/1.1 200 OK"
                            }
                            it.getOutputStream().write("$head\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"x".toByteArray())
                            return@use
                        }
                        val status = when (auth) { "forbidden" -> 403; "down" -> 503; "badbody" -> 200; else -> 401 }
                        val body = if (auth == "badbody") """{"x":1}""" else """{"error":{"code":"x","message":"x"}}"""
                        it.getOutputStream().write(
                            "HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                        )
                    }
                }
            }
        }
    }

    @After
    fun close() = server.close()

    @Test
    fun `토큰을 실은 요청마다 중계에 닿았는지 순번과 함께 알린다`() {
        // 9장 '중계 불통(네트워크·중계 장애)' → 오프라인 띠. 상태 요청·푸시 등록·ack 같은 요청도 같은 길로 본다.
        val seen = CopyOnWriteArrayList<Triple<String, Long, Boolean>>()
        val relay = OkHttpRelayClient("http://127.0.0.1:${server.localPort}", "test", onObserved = { t, n, up -> seen += Triple(t, n, up) })
        runBlocking {
            relay.devices("forbidden") // 403: 닿았다
            relay.devices("down") // 503: 중계 장애
            relay.patchMe("bad", kr.aigyeot.shared.PatchDeviceRequest()) // 401: 닿았다(연결 끊김은 따로)
        }
        assertEquals(listOf("forbidden" to true, "down" to false, "bad" to true), seen.map { it.first to it.third })
        assertEquals(seen.map { it.second }.sorted(), seen.map { it.second }) // 시작 순서대로 순번
        assertEquals(seen.last().second, relay.lastTicket())
        // 헤더(200) 뒤 본문이 끊기면 요청은 오프라인이고, 관찰도 '닿지 않음' 한 번이다.
        seen.clear()
        runBlocking { assertEquals(RelayResult.Offline, relay.devices("cut")) }
        assertEquals(listOf("cut" to false), seen.map { it.first to it.third })
        // 429 헤더 뒤 본문이 끊겨도 상태와 Retry-After는 지킨다(1.1): 오프라인으로 바꾸면 기다림을 잃는다.
        seen.clear()
        runBlocking { assertEquals(RelayResult.Err(429, null, 120), relay.devices("cut429")) }
        assertEquals(listOf("cut429" to true), seen.map { it.first to it.third })
        // 성공(200) 본문의 형식이 달라 읽지 못하면 앱을 끝내지 않고 오프라인으로 다시 시도한다.
        runBlocking { assertEquals(RelayResult.Offline, relay.devices("badbody")) }
        // 409·422는 오류 코드(song_quota·song_blob_missing 등)로 처리가 갈리니, 본문이 끊기면 버리지 않고 오프라인으로 다시 보낸다.
        seen.clear()
        runBlocking {
            assertEquals(RelayResult.Offline, relay.devices("cut409"))
            assertEquals(RelayResult.Offline, relay.devices("cut422"))
        }
        assertEquals(listOf("cut409" to false, "cut422" to false), seen.map { it.first to it.third })
        // 네트워크 실패도 닿지 않음이다. 토큰이 없는 요청(연결하기)은 알리지 않는다.
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { val p = it.localPort; it.close(); p }
        val off = CopyOnWriteArrayList<Boolean>()
        val gone = OkHttpRelayClient("http://127.0.0.1:$dead", "test", onObserved = { _, _, up -> off += up })
        runBlocking {
            // 연결 전 실패는 중계에 닿지 않은 것이 확실하다(헤더 뒤 끊김 `cut`은 닿았을 수 있어 오프라인).
            assertEquals(RelayResult.Unreached, gone.devices("x"))
            // 연결 시간 초과도 요청을 보내기 전이다(예외 종류가 아니라 보내기 시작했는지로 가린다).
            val slow = okhttp3.OkHttpClient.Builder().connectTimeout(200, TimeUnit.MILLISECONDS).build()
            assertEquals(RelayResult.Unreached, OkHttpRelayClient("http://10.255.255.1:9", "test", http = slow).devices("x"))
            gone.pair("123456", null, "install")
        }
        assertEquals(listOf(false), off)
    }

    @Test
    fun `어느 요청이든 401이면 부른 쪽이 취소돼도 알린다`() {
        // 7.1 "어디서든 401 → GONE". 화면이 뒤로 가 부른 코루틴이 취소되면 결과가 돌아가지 못하므로 HTTP 층이 바로 넘긴다.
        val got = CopyOnWriteArrayList<String>()
        val relay = OkHttpRelayClient("http://127.0.0.1:${server.localPort}", "test", onUnauthorized = { got += it })
        runBlocking {
            assertEquals(401, (relay.devices("bad") as RelayResult.Err).status)
            assertEquals(listOf("bad"), got)
            relay.devices("forbidden") // 403은 연결 끊김이 아니다
            assertEquals(listOf("bad"), got)
            val call = launch(Dispatchers.Default) { relay.deleteMe("slow") }
            delay(200)
            call.cancel()
            release.countDown()
            repeat(50) { if ("slow" !in got) delay(50) }
        }
        assertEquals(listOf("bad", "slow"), got)
    }

    private fun fixture(name: String): Envelope =
        ProtocolJson.decodeFromString(Envelope.serializer(), File("../../shared/src/test/resources/protocol/$name.json").readText())

    @Test
    fun `받은 봉투는 종류마다 InboundMessage로 바뀌고 id·seq·보낸 쪽·차를 옮긴다`() {
        val kinds = mapOf(
            "paired" to InboundMessage.Paired::class,
            "unpaired" to InboundMessage.Unpaired::class,
            "feeding_state" to InboundMessage.FeedingState::class,
            "song_status" to InboundMessage.SongStatus::class,
            "report_summary" to InboundMessage.Report::class,
            "handoff_alert" to InboundMessage.HandoffAlert::class,
        )
        for ((name, kind) in kinds) {
            val env = fixture(name)
            val m = env.inbound
            assertEquals(name, kind, m.message::class)
            assertEquals(Inbound(env.id, env.seq!!, env.from?.role == Role.CAR, m.message, env.car), m)
        }
        // 본문은 계약 값 그대로 담는다.
        val alert = fixture("handoff_alert")
        assertEquals(InboundMessage.HandoffAlert(ProtocolJson.decodeFromJsonElement(HandoffAlertBody.serializer(), alert.body)), alert.inbound.message)
        assertTrue(alert.inbound.fromCar)
        assertFalse(fixture("paired").inbound.fromCar) // 중계가 보낸 것
        assertEquals(0L, alert.copy(seq = null).inbound.seq)
    }

    @Test
    fun `모르는 type·판, 폰이 적용하지 않는 type, 필수 칸이 빠진 body는 Ignored다`() {
        val state = fixture("feeding_state")
        assertEquals(InboundMessage.Ignored, state.copy(type = "future_type").inbound.message)
        assertEquals(InboundMessage.Ignored, state.copy(v = 2).inbound.message)
        assertEquals(InboundMessage.Ignored, fixture("feeding_started").inbound.message)
        assertEquals(InboundMessage.Ignored, fixture("ack").inbound.message)
        val alert = fixture("handoff_alert")
        assertEquals(InboundMessage.Ignored, alert.copy(body = JsonObject(alert.body - "text")).inbound.message)
        val song = fixture("song_status")
        assertEquals(InboundMessage.Ignored, song.copy(body = JsonObject(song.body - "song_id")).inbound.message)
    }

    @Test
    fun `소켓 프레임은 봉투면 Inbound, 아니면 null이다`() {
        val c = OkHttpRelayClient("http://127.0.0.1:${server.localPort}", "test")
        val text = File("../../shared/src/test/resources/protocol/feeding_state.json").readText()
        assertEquals(fixture("feeding_state").inbound, c.read(text))
        assertNull(c.read("pong"))
    }
}
