package kr.aigyeot.phone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
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
        val relay = RelayClient("http://127.0.0.1:${server.localPort}", "test", onObserved = { t, n, up -> seen += Triple(t, n, up) })
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
        runBlocking { assertEquals(RelayClient.Res.Offline, relay.devices("cut")) }
        assertEquals(listOf("cut" to false), seen.map { it.first to it.third })
        // 429 헤더 뒤 본문이 끊겨도 상태와 Retry-After는 지킨다(1.1): 오프라인으로 바꾸면 기다림을 잃는다.
        seen.clear()
        runBlocking { assertEquals(RelayClient.Res.Err(429, null, 120), relay.devices("cut429")) }
        assertEquals(listOf("cut429" to true), seen.map { it.first to it.third })
        // 성공(200) 본문의 형식이 달라 읽지 못하면 앱을 끝내지 않고 오프라인으로 다시 시도한다.
        runBlocking { assertEquals(RelayClient.Res.Offline, relay.devices("badbody")) }
        // 409·422는 오류 코드(song_quota·song_blob_missing 등)로 처리가 갈리니, 본문이 끊기면 버리지 않고 오프라인으로 다시 보낸다.
        seen.clear()
        runBlocking {
            assertEquals(RelayClient.Res.Offline, relay.devices("cut409"))
            assertEquals(RelayClient.Res.Offline, relay.devices("cut422"))
        }
        assertEquals(listOf("cut409" to false, "cut422" to false), seen.map { it.first to it.third })
        // 네트워크 실패도 닿지 않음이다. 토큰이 없는 요청(연결하기)은 알리지 않는다.
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { val p = it.localPort; it.close(); p }
        val off = CopyOnWriteArrayList<Boolean>()
        val gone = RelayClient("http://127.0.0.1:$dead", "test", onObserved = { _, _, up -> off += up })
        runBlocking {
            // 연결 전 실패는 중계에 닿지 않은 것이 확실하다(헤더 뒤 끊김 `cut`은 닿았을 수 있어 오프라인).
            assertEquals(RelayClient.Res.Unreached, gone.devices("x"))
            // 연결 시간 초과도 요청을 보내기 전이다(예외 종류가 아니라 보내기 시작했는지로 가린다).
            val slow = okhttp3.OkHttpClient.Builder().connectTimeout(200, TimeUnit.MILLISECONDS).build()
            assertEquals(RelayClient.Res.Unreached, RelayClient("http://10.255.255.1:9", "test", http = slow).devices("x"))
            gone.pair("123456", null, "install")
        }
        assertEquals(listOf(false), off)
    }

    @Test
    fun `어느 요청이든 401이면 부른 쪽이 취소돼도 알린다`() {
        // 7.1 "어디서든 401 → GONE". 화면이 뒤로 가 부른 코루틴이 취소되면 결과가 돌아가지 못하므로 HTTP 층이 바로 넘긴다.
        val got = CopyOnWriteArrayList<String>()
        val relay = RelayClient("http://127.0.0.1:${server.localPort}", "test", onUnauthorized = { got += it })
        runBlocking {
            assertEquals(401, (relay.devices("bad") as RelayClient.Res.Err).status)
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
}
