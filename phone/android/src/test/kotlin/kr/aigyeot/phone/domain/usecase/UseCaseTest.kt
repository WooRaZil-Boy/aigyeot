package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.DisconnectRun
import kr.aigyeot.phone.domain.model.OutboxPayload
import kr.aigyeot.phone.domain.model.PairRun
import kr.aigyeot.phone.domain.model.outboxItem
import kr.aigyeot.phone.domain.model.rfc3339
import kr.aigyeot.phone.domain.model.SAVE_FAILED
import kr.aigyeot.phone.domain.model.restartNotice
import kr.aigyeot.phone.domain.repository.Mark
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.shared.DevicesResponse
import kr.aigyeot.shared.InboxResponse
import kr.aigyeot.shared.PairResponse
import kr.aigyeot.shared.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 유스케이스 동작(가짜 중계·prefs·Keystore, 진짜 파일). 옛 AppModel과 같은 결과인지 본다(arch-phone-android 9장 대조표). */
class UseCaseTest {
    private fun feedingState(at: String) = buildJsonObject {
        put("last_feeding_start", JsonPrimitive(at))
        put("window_start_expected", JsonPrimitive(at))
    }

    private fun handoffAlert(occurredAt: String) = buildJsonObject {
        put("text", JsonPrimitive("아기가 계속 울어서 휴게소에 들르자고 제안했어요."))
        put("kind", JsonPrimitive("rest_offer"))
        put("rest_name", JsonPrimitive("휴게소"))
        put("minutes_to_arrival", kotlinx.serialization.json.JsonNull)
        put("occurred_at", JsonPrimitive(occurredAt))
        put("session_id", JsonPrimitive("01JC990Q4M8T1V6B5R3H2CD7FA"))
        put("episode_id", JsonPrimitive("01JC9A00Q4M8T1V6B5R3H2CD7G"))
    }

    @Test
    fun `받은 함을 저장한 뒤에만 ack한다`() = kitTest {
        val env = envelope("feeding_state", feedingState("2025-11-05T08:00:00+09:00"), "01JXRECV000000000000000001", seq = 5)
        relay.inboxResult = { RelayResult.Ok(200, InboxResponse(listOf(env), more = false)) }
        assertEquals(SyncInbox.Sync.DONE, inbox.sync(OWNER))
        assertEquals(listOf(listOf(env.id)), relay.acked)
        assertEquals(5L, state.state.value.feeding?.seq)
    }

    @Test
    fun `받은 함 429면 Retry-After 전에는 다시 묻지 않고 소켓도 열지 않는다`() = kitTest {
        relay.inboxResult = { RelayResult.Err(429, "rate_limited", 120) }
        assertEquals(SyncInbox.Sync.FAILED, inbox.sync(OWNER))
        assertEquals(now + 120_000, inbox.inboxHoldUntil(OWNER, now))
        assertNull(inbox.inboxHoldUntil(OWNER.copy(token = "other"), now)) // 다른 자격에는 걸리지 않는다(P5)
        // 옛 자격의 늦은 429는 지금 자격의 기한을 덮지 않는다.
        assertEquals(SyncInbox.Sync.FAILED, inbox.sync(OWNER.copy(token = "old")))
        assertEquals(now + 120_000, inbox.inboxHoldUntil(OWNER, now))
        // 기한 전: 받은 함 대체 경로·푸시도 묻지 않고, 연결 반복은 소켓을 열지 않는다(iOS `beginRefresh`와 같음).
        assertEquals(SyncInbox.Sync.FAILED, inbox.sync(OWNER))
        inbox.onPush("01JXPVSH00000000000000000A")
        visibility.set(true)
        connection.start()
        connection.retryNow() // 깨워도 기한은 지킨다
        settle()
        assertEquals(2, relay.inboxAfter.size) // 처음 것과 옛 자격의 것뿐
        assertNull(relay.listener)
        // 기한이 지나면 연결 반복이 다시 붙는다.
        relay.inboxResult = { RelayResult.Ok(200, InboxResponse(emptyList(), false)) }
        now += 121_000
        connection.retryNow()
        until("소켓을 엶") { relay.listener != null }
        // Retry-After가 없으면 60초(iOS `rateLimitDefault`).
        relay.inboxResult = { RelayResult.Err(429, "rate_limited", null) }
        inbox.sync(OWNER)
        assertEquals(now + 60_000, inbox.inboxHoldUntil(OWNER, now))
    }

    @Test
    fun `망 실패 기다림은 망 복구·다시 시도·앞으로 오기 때 당겨 보낸다`() = kitTest {
        visibility.set(true)
        connection.start()
        val ok = relay.sendResult
        suspend fun waitingThen(why: String, hurry: () -> Unit) {
            relay.sendResult = { RelayResult.Offline }
            selectSong("s1")
            until("$why: 망 실패로 기다림") { state.state.value.outbox.singleOrNull()?.networkWait == true }
            relay.sendResult = ok
            settle()
            assertEquals(1, state.state.value.outbox.size) // 시계가 그대로면 기다림 뒤까지 보내지 않는다
            hurry()
            until("$why: 당겨 보냄") { state.state.value.outbox.isEmpty() }
        }
        waitingThen("망 복구") { network.restore() }
        waitingThen("다시 시도") { connection.retryNow() }
        waitingThen("앞으로 오기") { lifecycle.onForeground() }
        // 중계가 준 기다림(5xx 백오프)은 당기지 않는다.
        relay.sendResult = { RelayResult.Err(503, "unavailable", null) }
        selectSong("s2")
        until("5xx 기다림") { state.state.value.outbox.singleOrNull()?.attempts == 1 }
        relay.sendResult = ok
        outbox.hurryNetworkWaits()
        settle()
        assertEquals(1, state.state.value.outbox.size)
    }

    @Test
    fun `받은 함 받기는 한 번에 하나다 - 대체 경로는 합류하고 푸시·소켓이 붙음은 끝난 뒤 한 번 더 받는다`() = kitTest {
        relay.inboxResult = { RelayResult.Ok(200, InboxResponse(emptyList(), more = false)) }
        // 1) 연결 반복의 대체 경로는 도는 받기에 합류한다(iOS `refresh`가 갱신 중이면 열지 않음).
        var blocked = CompletableDeferred<Unit>()
        relay.inboxGate = blocked
        val first = scope.async { inbox.sync(OWNER) }
        until("첫 받기가 묻는 중") { relay.inboxAfter.size == 1 }
        relay.inboxGate = null
        val fallback = scope.async { inbox.sync(OWNER) }
        settle()
        blocked.complete(Unit)
        assertEquals(SyncInbox.Sync.DONE, first.await())
        assertEquals(SyncInbox.Sync.DONE, fallback.await())
        assertEquals(1, relay.inboxAfter.size)
        // 2) 푸시·소켓이 붙음은 끝난 뒤 한 번 더 받는다(둘은 한 번으로 합친다).
        blocked = CompletableDeferred()
        relay.inboxGate = blocked
        val second = scope.async { inbox.sync(OWNER) }
        until("둘째 받기가 묻는 중") { relay.inboxAfter.size == 2 }
        relay.inboxGate = null
        // 그사이 새 인계 알림이 중계에 들어왔다. 도는 받기는 그 전에 물었다.
        val alertId = "01JXA1ERT00000000000000000"
        val alert = envelope("handoff_alert", handoffAlert(rfc3339(now)), alertId, seq = 11)
        relay.inboxResult = { RelayResult.Ok(200, InboxResponse(listOf(alert), more = false)) }
        inbox.onPush(alertId) // 그 메시지를 받아 배너로
        val opened = scope.async { inbox.sync(OWNER, fresh = true) } // iOS `reconnectedDuringRefresh`
        settle()
        assertEquals(2, relay.inboxAfter.size)
        blocked.complete(Unit)
        assertEquals(SyncInbox.Sync.DONE, second.await())
        assertEquals(SyncInbox.Sync.DONE, opened.await())
        until("푸시의 배너") { notices.banner.value?.alertId == alertId }
        assertEquals(3, relay.inboxAfter.size)
    }

    @Test
    fun `소켓이 붙으면 도는 받기(푸시)가 끝난 뒤 한 번 더 받는다`() = kitTest {
        relay.inboxResult = { RelayResult.Ok(200, InboxResponse(emptyList(), false)) }
        visibility.set(true)
        connection.start()
        until("소켓 엶") { relay.listener != null }
        val blocked = CompletableDeferred<Unit>()
        relay.inboxGate = blocked
        inbox.onPush("01JXPVSH00000000000000000C")
        until("푸시 받기가 묻는 중") { relay.inboxAfter.size == 1 }
        relay.inboxGate = null
        relay.listener!!.onOpen() // 그 받은 함은 이 접속 전 것이다
        settle()
        assertEquals(1, relay.inboxAfter.size)
        blocked.complete(Unit)
        until("붙은 뒤 한 번 더") { relay.inboxAfter.size == 2 }
    }

    @Test
    fun `연결 전에 실패한 요약은 기록 지우기에서 빠져 지운 기록이 차로 가지 않는다`() = kitTest {
        val ok = relay.sendResult
        // 요약만 이름 풀기·연결 실패(비행기 모드 등): 중계에 닿지 않은 것이 확실하다. 수유 시작은 간다.
        relay.sendResult = { if (it.type == "care_summary") RelayResult.Unreached else ok(it) }
        recordCare.start(CareKind.FEED)
        until("망 실패 기다림") { state.state.value.outbox.singleOrNull()?.networkWait == true }
        val first = state.state.value.outbox.single()
        assertEquals(OutboxPayload.CareSummary(first.body, tried = false), first.payload) // '보내 봄'을 걷었다(U57)
        assertEquals(1, first.attempts)
        clearRecords()
        until("지움") { care.care.value.events.isEmpty() && state.state.value.clearingCareIds == null }
        relay.sendResult = ok
        outbox.hurryNetworkWaits() // 망이 돌아왔다
        until("보낼 함 비움") { state.state.value.outbox.isEmpty() }
        assertEquals(1, relay.sent.count { it.id == first.id }) // 지운 기록으로 만든 요약은 실패한 그 한 번뿐이다(R10)
        // 응답을 잃은 망 실패는 닿았을 수 있어 '보내 봄'을 둔다(U57).
        relay.sendResult = { RelayResult.Offline }
        state.commit(OWNER) { it.copy(outbox = listOf(outboxItem(OutboxPayload.CareSummary(first.body, tried = false), now))) }
        outbox.pump()
        until("응답 잃음") { state.state.value.outbox.singleOrNull()?.networkWait == true }
        assertTrue(state.state.value.outbox.single().sentOnce)
    }

    @Test
    fun `429에 Retry-After가 없으면 60초 기다린다 - 결과를 저장하지 못한 메시지와 상태 요청도`() = kitTest {
        // 메시지 POST: 결과 저장이 실패해도 메모리 기다림이 60초(iOS `rateLimitDefault`).
        relay.sendResult = { File(dir, "phone_state.json.new").mkdirs(); RelayResult.Err(429, "rate_limited", null) }
        selectSong("s1")
        until("메모리 기다림") { outbox.holdUntil(now) != null }
        assertEquals(now + 60_000, outbox.holdUntil(now))
        assertNull(state.state.value.messageHoldUntil) // 저장하지 못했다
        File(dir, "phone_state.json.new").deleteRecursively()
        // 상태 요청: 기기 목록 성공 뒤 보낸 state_request의 429도 60초.
        val later = now + 120_000
        now = later
        relay.sendResult = { RelayResult.Err(429, "rate_limited", null) }
        relay.devicesResult = RelayResult.Ok(200, DevicesResponse(1, emptyList()))
        state.commit(OWNER) { it.copy(stateRequestNeeded = true, lastStateRequestAt = null, outbox = emptyList()) }
        connection.refreshDevices(OWNER)
        assertEquals(later + 60_000, state.state.value.messageHoldUntil)
    }

    @Test
    fun `받은 함은 앞 쪽 마지막 seq 뒤를 이어 받는다`() = kitTest {
        val first = envelope("feeding_state", feedingState("2025-11-05T08:00:00+09:00"), "01JXRECV000000000000000003", seq = 5)
        val next = envelope("feeding_state", feedingState("2025-11-05T09:00:00+09:00"), "01JXRECV000000000000000004", seq = 7)
        relay.inboxResult = { after -> RelayResult.Ok(200, if (after == 0L) InboxResponse(listOf(first), more = true) else InboxResponse(listOf(next), more = false)) }
        assertEquals(SyncInbox.Sync.DONE, inbox.sync(OWNER))
        assertEquals(listOf(0L, 5L), relay.inboxAfter)
        assertEquals(listOf(listOf(first.id), listOf(next.id)), relay.acked)
        assertEquals(7L, state.state.value.feeding?.seq)
    }

    @Test
    fun `받은 것을 저장하지 못하면 ack하지 않고 저장 실패를 알린다`() = kitTest(prepare = { File(it, "phone_state.json.new").mkdirs() }) {
        val env = envelope("feeding_state", feedingState("2025-11-05T08:00:00+09:00"), "01JXRECV000000000000000002", seq = 6)
        relay.inboxResult = { RelayResult.Ok(200, InboxResponse(listOf(env), more = false)) }
        assertEquals(SyncInbox.Sync.FAILED, inbox.sync(OWNER))
        assertTrue(relay.acked.isEmpty())
        assertEquals(SAVE_FAILED, notices.notice.value)
    }

    @Test
    fun `보낼 때 401이면 강제 끊김으로 GONE에 간다`() = kitTest {
        relay.sendResult = { RelayResult.Err(401, "unauthorized", null) }
        selectSong("s1")
        until("GONE") { state.connection.value is Connection.Gone }
        assertNull(credentials.stored)
        assertEquals(OWNER.deviceId, marks.get(Mark.GONE_CLEANUP_PENDING))
    }

    @Test
    fun `옛 연결의 늦은 401은 새 연결을 지우지 않는다`() = kitTest {
        lose.lost(OWNER.copy(token = "old"))
        assertEquals(Connection.Paired(OWNER), state.connection.value)
        lose.unauthorized("old")
        settle()
        assertEquals(Connection.Paired(OWNER), state.connection.value)
    }

    @Test
    fun `설정 끊기는 중계가 지우면 끝나고 기록만 쓰기로 탭에 간다`() = kitTest {
        disconnect.start()
        until("끊기 끝") { disconnect.run.value == DisconnectRun.Done }
        assertEquals(Connection.None, state.connection.value)
        assertEquals(listOf(OWNER.token), relay.deleted)
        assertTrue(link.recordsOnly)
        assertEquals("연결을 끊었어요. 돌봄 기록은 이 휴대폰에 남아 있어요", notices.careNotice.value)
        assertEquals(OWNER.deviceId + "|done", marks.get(Mark.LEAVING))
        disconnect.shown()
        assertNull(marks.get(Mark.LEAVING))
        assertNull(disconnect.run.value)
    }

    @Test
    fun `끊기가 오프라인이면 실패 문구를 남기고 연결은 그대로다`() = kitTest {
        relay.deleteResult = RelayResult.Offline
        disconnect.start()
        until("끊기 끝") { disconnect.run.value is DisconnectRun.Failed }
        assertEquals(DisconnectRun.Failed("인터넷이 필요해요"), disconnect.run.value)
        assertEquals(Connection.Paired(OWNER), state.connection.value)
        assertNull(marks.get(Mark.LEAVING))
    }

    @Test
    fun `연결 오류는 앱이 정한 문구이고 429는 버튼을 잠근다`() = kitTest(creds = null) {
        relay.pairResult = RelayResult.Err(400, "code_invalid", null)
        pair.start("123456")
        until("연결 끝") { pair.run.value is PairRun.Failed }
        assertEquals("코드가 맞지 않거나 시간이 지났어요. 차 화면의 코드를 다시 확인해 주세요.", (pair.run.value as PairRun.Failed).error)
        relay.pairResult = RelayResult.Err(429, null, 30)
        pair.start("123456")
        until("429") { (pair.run.value as? PairRun.Failed)?.error == "잠시 뒤에 다시 해 주세요." }
        assertEquals(now + 30_000, pair.lockedUntil.value)
        assertTrue(!pair.canTakeLink() || pair.run.value !is PairRun.Running)
    }

    @Test
    fun `연결을 마치면 새 자격·완료 화면 표시·띠 처음으로`() = kitTest(creds = null) {
        notices.showCare("옛 띠")
        relay.pairResult = RelayResult.Ok(200, PairResponse("c_NEW", "d_NEW", "tok-new", Role.OWNER))
        pair.start("123456")
        until("연결 끝") { pair.run.value is PairRun.Done }
        assertEquals(Connection.Paired(kr.aigyeot.phone.domain.model.Credentials("c_NEW", "d_NEW", "tok-new", Role.OWNER)), state.connection.value)
        assertEquals("d_NEW", marks.get(Mark.PAIR_DONE))
        assertNull(notices.careNotice.value)
        assertEquals(Role.OWNER, pair.pendingDoneRole())
        assertTrue(pair.takeDone(pair.run.value!!))
        assertTrue(pair.doneShown())
        assertNull(marks.get(Mark.PAIR_DONE))
    }

    @Test
    fun `연결 정보를 저장하지 못하면 중계의 새 기기를 지운다`() = kitTest(creds = null) {
        credentials.failSave = true
        relay.pairResult = RelayResult.Ok(200, PairResponse("c_NEW", "d_NEW", "tok-new", Role.OWNER))
        pair.start("123456")
        until("연결 끝") { pair.run.value is PairRun.Failed }
        assertEquals("연결 정보를 저장하지 못했어요", (pair.run.value as PairRun.Failed).error)
        assertEquals(listOf("tok-new"), relay.deleted)
        assertEquals(Connection.None, state.connection.value)
    }

    @Test
    fun `수유를 시작하면 owner는 차로 보내고 다시 시작하면 앞 기록을 닫았다고 알린다`() = kitTest {
        recordCare.start(CareKind.FEED)
        until("수유 보냄") { relay.sent.any { it.type == "feeding_started" } }
        val first = care.care.value.events.single()
        now += 60_000
        recordCare.start(CareKind.FEED)
        until("앞 수유 닫음") { notices.careNotice.value != null }
        assertEquals(restartNotice(care.care.value.events.first { it.id == first.id }), notices.careNotice.value)
    }

    @Test
    fun `기록 지우기는 돌봄 기록을 지우고 지우기 표시를 마친다`() = kitTest {
        recordCare.start(CareKind.FEED) // 오늘 수유 1회: 요약이 한 번 간다(값 없는 요약은 처음에 보내지 않는다, U61)
        until("요약 보냄") { relay.sent.any { it.type == "care_summary" } }
        val gen = clearRecords.generation()
        clearRecords()
        assertEquals(gen + 1, clearRecords.generation())
        until("지움") { care.care.value.events.isEmpty() && state.state.value.clearingCareIds == null }
        // 넣은 적이 있으면 빈 요약을 보낸다(R10·R19). 지우기 표시를 끈 저장 다음 저장에서 넣으므로 기다린다.
        until("빈 요약") { state.state.value.lastSentCareSummary != null }
    }
}
