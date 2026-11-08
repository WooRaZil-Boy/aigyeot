package kr.aigyeot.app.link

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.aigyeot.app.exec.LoudnessResult
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.Qa
import kr.aigyeot.engine.QaReply
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실제 로컬 중계(wrangler dev)에 붙는 종단 시험. 평소에는 건너뛴다.
 * `cd relay && npx wrangler dev --port 8787` 뒤 `AIGYEOT_RELAY=http://127.0.0.1:8787 ./gradlew :app:testDebugUnitTest --tests '*LinkRelayLiveTest'`.
 * 폰은 HTTP로 흉내 낸다(폰 앱 화면은 지나지 않는다). 지연을 표준 출력에 찍는다.
 */
@RunWith(RobolectricTestRunner::class)
class LinkRelayLiveTest {
    private val base = System.getenv("AIGYEOT_RELAY")
    private val http = OkHttpClient()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun call(method: String, path: String, token: String?, body: String? = null): Pair<Int, String> {
        val b = Request.Builder().url(base + path).method(method, body?.toRequestBody(json))
        if (token != null) b.header("Authorization", "Bearer $token")
        return http.newCall(b.build()).execute().use { it.code to it.body.string() }
    }

    private fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!cond()) { check(System.currentTimeMillis() < until) { "시간 초과: $what" }; Thread.sleep(5) }
    }

    @Test fun pairFeedingStateRequestQa_overRealRelay() {
        assumeTrue("AIGYEOT_RELAY가 없어 건너뜀", base != null)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        db.openHelper.writableDatabase.execSQL(   // 첫 설정을 마친 차(아기 프로필 있음)
            "INSERT INTO child_profile VALUES ('p', '지우', '2026-03', 'LEFT', 180, NULL, '[]', 0, 0, 0, 1)",
        )
        val events = CopyOnWriteArrayList<Pair<Long, Event>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val link = Link(ctx, db, System::currentTimeMillis, { events += System.currentTimeMillis() to it }, { null },
            OkRelay(base!!), FakeCipher, { null }, { _, _ -> LoudnessResult(false, "bad_format") }, scope)
        try {
            link.start()
            link.openPairing()
            waitFor("연결 코드") { link.status.value.pairCode != null }
            val code = link.status.value.pairCode!!

            val tPair = System.currentTimeMillis()
            val (pc, pb) = call("POST", "/v1/pair", null, """{"code":"$code","platform":"android","push_token":null,"app_version":"1.0.0"}""")
            assertEquals(201, pc, pb)
            val pj = Json.parseToJsonElement(pb).jsonObject
            val token = pj.getValue("device_token").jsonPrimitive.content
            val phone = pj.getValue("device_id").jsonPrimitive.content
            waitFor("차 PhoneLink") { link.status.value.phones.any { it.deviceId == phone } }
            val pairMs = System.currentTimeMillis() - tPair
            waitFor("새 연결 코드(G41)") { link.status.value.pairCode != null && link.status.value.pairCode != code }

            val at = rfc3339(System.currentTimeMillis() - 5_000)
            val tTap = System.currentTimeMillis()
            val (sc, sb) = call("POST", "/v1/messages", token, """{"v":1,"id":"${ulid(tTap)}","type":"feeding_started","ts":"${rfc3339(tTap)}","body":{"at":"$at"}}""")
            assertEquals(201, sc, sb)
            waitFor("차 FeedingStarted") { events.any { it.second is FeedingStarted } }
            val tapToCar = events.first { it.second is FeedingStarted }.first - tTap

            fun inbox() = Json.parseToJsonElement(call("GET", "/v1/inbox?after_seq=0&limit=100", token).second).jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
            var reflected = 0L
            waitFor("폰 feeding_state(차에 반영됨)") {
                inbox().any { m -> m["type"]?.jsonPrimitive?.content == "feeding_state" &&
                    instantOf(m.getValue("body").jsonObject["last_feeding_start"]?.jsonPrimitive?.content) == instantOf(at) }
                    .also { if (it) reflected = System.currentTimeMillis() - tTap }
            }

            val tReq = System.currentTimeMillis()
            val before = inbox().size
            assertEquals(201, call("POST", "/v1/messages", token, """{"v":1,"id":"${ulid(tReq)}","type":"state_request","ts":"${rfc3339(tReq)}","body":{}}""").first)
            waitFor("state_request 답") { inbox().size > before }
            val stateMs = System.currentTimeMillis() - tReq

            val tQa = System.currentTimeMillis()
            link.handle(Qa("live", "다음 수유실 어디야?"))
            waitFor("QaReply") { events.any { it.second is QaReply } }
            val qaMs = events.first { it.second is QaReply }.first - tQa

            link.disconnect(phone)
            waitFor("차 끊기") { link.status.value.phones.none { it.deviceId == phone } }
            assertEquals(401, call("GET", "/v1/inbox?after_seq=0&limit=100", token).first)

            println("LIVE pair_ms=$pairMs tap_to_car_ms=$tapToCar tap_to_phone_feeding_state_ms=$reflected state_request_ms=$stateMs qa_offline_ms=$qaMs conn=${link.status.value.conn}")
            assertTrue(tapToCar >= 0)
        } finally {
            link.stop(); scope.cancel(); db.close()
        }
    }
}
