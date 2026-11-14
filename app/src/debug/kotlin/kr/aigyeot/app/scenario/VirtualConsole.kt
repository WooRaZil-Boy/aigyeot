package kr.aigyeot.app.scenario

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 에뮬레이터 Virtual Console HTTP. 앱 안에서 127.0.0.1:8088로 보낸다(평문은 debug 네트워크 설정만 허락).
 * 막는 호출이라 IO 스레드에서 부른다. 세션 신호가 3초 넘게 끊기면 차량 입력이 먹지 않는다(`emulator_inactive`).
 */
class VirtualConsole(private val base: String = "http://127.0.0.1:8088") {
    data class Reply(val code: Int, val body: String) {
        /** 2xx이고 본문에 오류 표시가 없음(세션이 끊기면 200에 {"error":"emulator_inactive"}가 온다). */
        val ok get() = code in 200..299 && !body.contains("\"error\"")
    }

    fun send(method: String, path: String): Reply = try {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 1_500
            c.readTimeout = 3_000
            if (method != "GET") { c.doOutput = true; c.setFixedLengthStreamingMode(0); c.outputStream.close() }
            val code = c.responseCode
            Reply(code, ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "").take(300))
        } finally { c.disconnect() }
    } catch (e: IOException) {
        Reply(-1, "${e.javaClass.simpleName}: ${e.message}")
    } catch (e: SecurityException) {   // 평문 금지(release 네트워크 설정) 등
        Reply(-1, "${e.javaClass.simpleName}: ${e.message}")
    }

    companion object { const val SESSION = "/browser/session?client=aigyeot" }
}
