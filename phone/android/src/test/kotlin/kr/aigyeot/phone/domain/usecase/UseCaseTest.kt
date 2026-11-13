package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kr.aigyeot.phone.domain.model.CSV_HEADER
import kr.aigyeot.phone.domain.model.CareCsv
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.CareUndo
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.HOUR
import kr.aigyeot.phone.domain.model.MINUTE
import kr.aigyeot.phone.domain.model.text
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
import org.junit.Assert.assertFalse
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
        assertEquals("연결을 끊었어요. 돌봄 기록은 이 휴대폰에 남아 있어요.", notices.careNotice.value)
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
        relay.pairResult = RelayResult.Err(429, null, 600)
        pair.start("123456")
        // 429는 글을 굳히지 않는다: 화면이 잠금 끝 시각에서 그때그때 잰다(7.2, 11-12 결정). 10분 잠금이 9분 지나면 "600초"가 아니라 "60초".
        until("429") { pair.run.value == PairRun.Limited("123456") }
        assertEquals(now + 600_000, pair.lockedUntil.value)
        val wait = { t: Long -> kr.aigyeot.phone.domain.model.pairWaitText(pair.lockedUntil.value!! - t) }
        assertEquals("10분 뒤에 다시 해 주세요", kr.aigyeot.phone.domain.model.pairErrorShown(pair.run.value, wait(now)))
        assertEquals("2분 뒤에 다시 해 주세요", kr.aigyeot.phone.domain.model.pairErrorShown(pair.run.value, wait(now + 8 * MINUTE)))
        assertEquals("60초 뒤에 다시 해 주세요", kr.aigyeot.phone.domain.model.pairErrorShown(pair.run.value, wait(now + 9 * MINUTE)))
        assertEquals(null, kr.aigyeot.phone.domain.model.pairErrorShown(pair.run.value, wait(now + 10 * MINUTE)))
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

    // ---- 기록 가져오기(U64·U67): 저장은 RecordCare 입구 하나, 차 보내기는 다른 저장과 같은 계기 ----

    private fun Kit.imported(vararg rows: String) =
        CareCsv.parse((listOf(CSV_HEADER) + rows).joinToString("\r\n") + "\r\n", care.care.value, now).rows

    @Test
    fun `가져오기 저장이 실패하면 아무것도 들어가지 않는다`() = kitTest(prepare = { File(it, "care.json.new").mkdirs() }) {
        val rows = imported("FEED,${rfc3339(now - HOUR)},,true,LEFT,,,EXAMPLE", "DIAPER,${rfc3339(now - 2 * HOUR)},,false,,,WET,EXAMPLE")
        assertEquals(2, rows.size)
        assertFalse(recordCare.importRows(rows))
        assertEquals(emptyList<CareEvent>(), care.care.value.events)
        assertNull(notices.undo.value)
        settle()
        assertNull(state.state.value.lastSentFeedingAt)
        assertTrue(relay.sent.none { it.type == "feeding_started" })
    }

    @Test
    fun `되돌리면 넣은 기록이 모두 빠지고 다시 가져올 수 있다`() = kitTest {
        recordCare.start(CareKind.NOTE)
        until("손 기록") { care.care.value.events.size == 1 }
        val before = care.care.value
        val text = arrayOf(
            "FEED,${rfc3339(now - 3 * HOUR)},${rfc3339(now - 3 * HOUR + 600_000)},false,RIGHT,,,EXAMPLE",
            "SLEEP,${rfc3339(now - 6 * HOUR)},${rfc3339(now - 5 * HOUR)},false,,,,EXAMPLE",
            "DIAPER,${rfc3339(now - 2 * HOUR)},,false,,,WET,",
        )
        val rows = imported(*text)
        assertTrue(recordCare.importRows(rows))
        assertEquals(CareUndo.Import(rows.map { it.id }), notices.undo.value) // 띠 import.done + 되돌리기
        assertEquals("기록 3건을 가져왔어요", (notices.undo.value as CareUndo).text)
        recordCare.undo(notices.undo.value!!)
        until("되돌림") { care.care.value.events == before.events } // 지운 표시가 아니라 완전히 뺀다
        val again = imported(*text)
        assertEquals(rows.map { it.id }, again.map { it.id }) // 같은 파일을 다시 가져올 수 있다
        assertTrue(recordCare.importRows(again))
        assertEquals(4, care.care.value.events.size)
        // 같은 미리 보기를 한 번 더 저장해도 같은 id는 다시 넣지 않고 되돌리기도 띄우지 않는다.
        notices.clearUndo()
        assertTrue(recordCare.importRows(again))
        assertEquals(4, care.care.value.events.size)
        assertNull(notices.undo.value)
    }

    private fun Kit.threeRows() = imported(
        "FEED,${rfc3339(now - 3 * HOUR)},${rfc3339(now - 3 * HOUR + 600_000)},false,RIGHT,,,EXAMPLE",
        "SLEEP,${rfc3339(now - 6 * HOUR)},${rfc3339(now - 5 * HOUR)},false,,,,EXAMPLE",
        "DIAPER,${rfc3339(now - 2 * HOUR)},,false,,,WET,",
    )

    @Test
    fun `미리 보기 뒤 손으로 넣은 기록과 겹치는 줄은 저장 때 빼고 띠 수는 실제로 들어간 수다`() = kitTest {
        val rows = threeRows() // 미리 보기(기저귀 지금 - 2시간 포함)
        // 미리 보기를 띄운 채 다른 탭에서 같은 종류·30초 차이 기저귀를 손으로 적었다.
        val hand = CareEvent("01JXHAND000000000000000001", CareKind.DIAPER, now - 2 * HOUR + 30_000, zone = "Asia/Seoul", createdAt = now, updatedAt = now)
        assertTrue(recordCare.saveEvent(hand))
        assertTrue(recordCare.importRows(rows))
        val diaper = rows.single { it.kind == CareKind.DIAPER }
        assertEquals(listOf(hand.id) + rows.filter { it != diaper }.map { it.id }, care.care.value.events.map { it.id })
        assertEquals(CareUndo.Import(rows.filter { it != diaper }.map { it.id }), notices.undo.value)
        assertEquals("기록 2건을 가져왔어요", (notices.undo.value as CareUndo).text)
    }

    @Test
    fun `지우다 만 기록 지우기는 먼저 마치고 가져오며 띠 수는 실제로 들어간 수다`() = kitTest {
        // 기록 지우기가 care.json은 비웠지만 사본 정리를 못 해 표시가 남았다. 지울 id가 지금 가져올 줄의 id(내용 해시)와 같다.
        val rows = threeRows()
        state.commitLocal { it.copy(clearingCareIds = rows.map { r -> r.id }, clearingSince = now) }
        assertTrue(recordCare.importRows(rows))
        assertEquals(rows.map { it.id }, care.care.value.events.map { it.id }) // 저장 후처리가 다시 빼지 않았다
        assertNull(state.state.value.clearingCareIds)
        assertEquals("기록 3건을 가져왔어요", (notices.undo.value as CareUndo).text)
    }

    @Test
    fun `지우다 만 기록 지우기를 마치지 못하면 가져오지 않는다`() {
        lateinit var locked: File
        kitTest(prepare = { dir ->
            // 내보낸 CSV 사본(7.4 '캐시')을 지울 수 없게 해 기록 지우기의 정리가 끝나지 않게 한다.
            locked = File(dir, "export/locked").apply { mkdirs(); File(this, "x.csv").writeText("x"); setWritable(false) }
        }) {
            try {
                val rows = threeRows()
                state.commitLocal { it.copy(clearingCareIds = rows.map { r -> r.id }, clearingSince = now) }
                assertFalse(recordCare.importRows(rows)) // import.failed
                assertEquals(emptyList<CareEvent>(), care.care.value.events)
                assertNull(notices.undo.value)
                assertEquals(rows.map { it.id }, state.state.value.clearingCareIds) // 다음 계기에 다시 마친다
            } finally {
                locked.setWritable(true)
            }
        }
    }

    @Test
    fun `가져온 더 늦은 수유는 owner일 때 차로 보낸다`() {
        fun Kit.rows() = imported(
            "FEED,${rfc3339(now - 30 * HOUR)},,true,LEFT,,,EXAMPLE 24시간 밖",
            "FEED,${rfc3339(now - HOUR)},${rfc3339(now - HOUR + 600_000)},false,LEFT,,,EXAMPLE",
        )
        // U67 11-09 보충(결정): 저장 직후가 아니라 되돌리기 띠가 끝난 뒤 한 번 보낸다.
        kitTest(undoMs = 1_000) {
            assertTrue(recordCare.importRows(rows()))
            settle()
            assertTrue(relay.sent.none { it.type == "feeding_started" }) // 띠가 떠 있는 동안은 보내지 않는다
            assertNull(state.state.value.lastSentFeedingAt)
            until("띠가 끝난 뒤 수유 보냄") { relay.sent.any { it.type == "feeding_started" } }
            assertNull(notices.undo.value)
            assertEquals(now - HOUR, state.state.value.lastSentFeedingAt) // 가장 늦은 수유 하나(R5)
            assertEquals(1, relay.sent.count { it.type == "feeding_started" })
        }
        kitTest(creds = OWNER.copy(role = Role.GUARDIAN), undoMs = 300) {
            assertTrue(recordCare.importRows(rows())) // guardian도 가져온다(U64). 차로는 보내지 않는다(R5)
            assertEquals(2, care.care.value.events.size)
            until("띠가 끝남") { notices.undo.value == null }
            settle()
            assertNull(state.state.value.lastSentFeedingAt)
            assertTrue(relay.sent.none { it.type == "feeding_started" })
        }
    }

    @Test
    fun `화면 읽기가 켜져 있으면 되돌리기 띠는 50초다`() {
        assertEquals(50_000L, kr.aigyeot.phone.domain.model.undoBandMs(screenReader = true, systemRecommendedMs = 5_000))
        assertEquals(5_000L, kr.aigyeot.phone.domain.model.undoBandMs(screenReader = false, systemRecommendedMs = 5_000)) // 꺼져 있으면 지금처럼 5초
        assertEquals(5_000L, kr.aigyeot.phone.domain.model.undoBandMs(screenReader = false, systemRecommendedMs = 0)) // 권장 시간이 짧아도 5초 아래로 내려가지 않는다
    }

    @Test
    fun `시스템 권장 시간이 더 길면 그 값이다`() {
        assertEquals(12_000L, kr.aigyeot.phone.domain.model.undoBandMs(screenReader = false, systemRecommendedMs = 12_000))
        assertEquals(120_000L, kr.aigyeot.phone.domain.model.undoBandMs(screenReader = true, systemRecommendedMs = 120_000))
        assertEquals(50_000L, kr.aigyeot.phone.domain.model.undoBandMs(screenReader = true, systemRecommendedMs = 20_000))
    }

    @Test
    fun `띠 시간이 길어져도 가져온 줄은 띠가 끝난 뒤에 차로 간다`() = kitTest(undoMs = 300) {
        undoBand = 1_500 // 화면 읽기로 늘어난 띠(시험은 줄인 값)
        assertTrue(recordCare.importRows(imported("FEED,${rfc3339(now - HOUR)},${rfc3339(now - HOUR + 600_000)},false,LEFT,,,EXAMPLE")))
        undoBand = 100 // 띠가 떠 있는 동안 바뀌어도 띄울 때 정한 시간을 따른다
        kotlinx.coroutines.delay(800) // 옛 띠 시간(300ms)도, 바뀐 값(100ms)도 지났다
        assertTrue(notices.undo.value is CareUndo.Import)
        assertTrue(relay.sent.none { it.type == "feeding_started" })
        until("띠가 끝난 뒤 수유 보냄") { relay.sent.any { it.type == "feeding_started" } }
        assertNull(notices.undo.value)
        assertEquals(now - HOUR, state.state.value.lastSentFeedingAt)
    }

    @Test
    fun `가져오기를 되돌리기 띠 안에 되돌리면 차로 아무것도 보내지 않는다`() = kitTest(undoMs = 1_000) {
        assertTrue(recordCare.importRows(threeRows()))
        // 띠가 떠 있는 동안 온 다른 계기(앞으로 오기)는 가져온 줄을 빼고 맞춘다: 되돌릴 수유를 먼저 보내지 않는다(값 없는 요약은 보내지 않는다, U61).
        recordCare.syncToCar(kr.aigyeot.phone.domain.model.FeedTrigger.FOREGROUND)
        settle()
        assertTrue(relay.sent.isEmpty())
        recordCare.undo(notices.undo.value!!)
        until("되돌림") { care.care.value.events.isEmpty() }
        kotlinx.coroutines.delay(1_500) // 띠가 끝났을 시간도 지났다
        assertNull(notices.undo.value)
        assertTrue(relay.sent.none { it.type == "feeding_started" || it.type == "care_summary" })
        assertNull(state.state.value.lastSentFeedingAt)
    }

    @Test
    fun `가져오기 띠가 다른 띠로 바뀌어 닫히면 바로 차로 보낸다`() = kitTest(undoMs = 60_000) {
        val hand = CareEvent("01JXHAND000000000000000002", CareKind.NOTE, now - 5 * HOUR, zone = "Asia/Seoul", note = "EXAMPLE", createdAt = now, updatedAt = now)
        assertTrue(recordCare.saveEvent(hand))
        assertTrue(recordCare.importRows(threeRows()))
        settle()
        assertTrue(relay.sent.none { it.type == "feeding_started" })
        // 손 기록을 지워 '지웠어요' 띠가 가져오기 띠를 바꿨다: 가져오기는 이제 되돌릴 수 없으니 바로 보낸다(띠 시간 60초를 기다리지 않는다).
        recordCare.delete(hand.id)
        until("앞 띠가 닫혀 수유 보냄") { relay.sent.any { it.type == "feeding_started" } }
        assertEquals(CareUndo.Delete(hand.id), notices.undo.value)
        assertEquals(now - 3 * HOUR, state.state.value.lastSentFeedingAt)
        // 새로 가져온 더 늦은 수유는 그 띠가 떠 있는 동안 차로 가는 계산에서 빠진다.
        val more = imported("FEED,${rfc3339(now - HOUR)},${rfc3339(now - HOUR + 600_000)},false,LEFT,,,EXAMPLE")
        assertTrue(recordCare.importRows(more))
        recordCare.syncToCar(kr.aigyeot.phone.domain.model.FeedTrigger.FOREGROUND) // 띠 안의 다른 계기
        settle()
        assertEquals(now - 3 * HOUR, state.state.value.lastSentFeedingAt)
        // 가져오기 띠가 새 가져오기 띠로 바뀌면 앞 줄은 이제 되돌릴 수 없으니 보내고, 새 줄만 그 띠가 끝날 때까지 빠진다.
        assertTrue(recordCare.importRows(imported("FEED,${rfc3339(now - 30 * MINUTE)},,true,RIGHT,,,EXAMPLE")))
        until("앞 가져오기 수유 보냄") { state.state.value.lastSentFeedingAt == now - HOUR }
        settle()
        assertEquals(now - HOUR, state.state.value.lastSentFeedingAt)
    }

    @Test
    fun `지우기 되돌리기 저장이 실패하면 띠를 남겨 다시 누를 수 있다`() = kitTest(undoMs = 60_000) {
        val hand = CareEvent("01JXHAND000000000000000004", CareKind.DIAPER, now - HOUR, zone = "Asia/Seoul", createdAt = now, updatedAt = now)
        assertTrue(recordCare.saveEvent(hand))
        recordCare.delete(hand.id)
        until("지움") { notices.undo.value == CareUndo.Delete(hand.id) }
        val u = notices.undo.value!!
        val block = File(dir, "care.json.new").apply { mkdirs() } // care.json 저장 한 번 실패
        recordCare.undo(u).join()
        assertEquals(u, notices.undo.value) // 띠가 남아 남은 시간 안에 다시 누를 수 있다(U38 11-09 보충)
        assertTrue(care.care.value.events.single().deletedAt != null)
        block.deleteRecursively()
        recordCare.undo(u).join()
        assertNull(care.care.value.events.single().deletedAt) // 되살아났다
        assertNull(notices.undo.value)
    }

    @Test
    fun `기록을 지우면 되돌리기 띠도 걷는다`() = kitTest(undoMs = 1_000) {
        // 지우기 띠: 지운 기록은 id째 빠지므로 되돌릴 것이 없다(U38 11-09 보충).
        val hand = CareEvent("01JXHAND000000000000000005", CareKind.DIAPER, now - HOUR, zone = "Asia/Seoul", createdAt = now, updatedAt = now)
        assertTrue(recordCare.saveEvent(hand))
        recordCare.delete(hand.id)
        until("지우기 띠") { notices.undo.value == CareUndo.Delete(hand.id) }
        clearRecords()
        until("지움") { care.care.value.events.isEmpty() && state.state.value.clearingCareIds == null }
        assertNull(notices.undo.value)
        // 가져오기 띠: 띠 시간이 끝나도 지운 줄을 차로 보내지 않는다.
        val sentBefore = relay.sent.size
        assertTrue(recordCare.importRows(threeRows()))
        assertTrue(notices.undo.value is CareUndo.Import)
        clearRecords()
        until("지움") { care.care.value.events.isEmpty() && state.state.value.clearingCareIds == null }
        assertNull(notices.undo.value)
        kotlinx.coroutines.delay(1_500) // 띠 시간이 지났다
        assertNull(notices.undo.value)
        val after = relay.sent.drop(sentBefore)
        assertTrue(after.none { it.type == "feeding_started" })
        val summaries = after.filter { it.type == "care_summary" }.map { it.body } +
            state.state.value.outbox.mapNotNull { (it.payload as? OutboxPayload.CareSummary)?.body }
        assertTrue("$summaries", summaries.all { it.isEmptySummary() }) // 빈 요약만
    }

    private fun kotlinx.serialization.json.JsonObject.isEmptySummary() =
        (get("today_feed_count") as JsonPrimitive).content == "0" && (get("last_diaper_at") as? JsonPrimitive)?.contentOrNull == null &&
            (get("last_sleep_start_at") as? JsonPrimitive)?.contentOrNull == null

    /** 보낸 요약의 '오늘 수유 수'. */
    private fun kr.aigyeot.shared.Envelope.feeds() = body["today_feed_count"]?.let { (it as JsonPrimitive).content.toInt() }

    @Test
    fun `보낼 함에서 기다리던 요약을 띠 안에 다시 계산해도 가져온 줄은 빼고 되돌리면 끝까지 차로 가지 않는다`() = kitTest(undoMs = 1_000) {
        val ok = relay.sendResult
        relay.sendResult = { if (it.type == "care_summary") RelayResult.Unreached else ok(it) }
        val hand = CareEvent("01JXHAND000000000000000003", CareKind.FEED, now - 2 * HOUR, now - 2 * HOUR + 600_000, zone = "Asia/Seoul", createdAt = now, updatedAt = now)
        assertTrue(recordCare.saveEvent(hand)) // 오늘 수유 1회 요약이 망 실패로 보낼 함에 남았다
        until("망 실패 기다림") { state.state.value.outbox.any { it.payload is OutboxPayload.CareSummary && it.networkWait } }
        assertTrue(recordCare.importRows(imported("FEED,${rfc3339(now - HOUR)},${rfc3339(now - HOUR + 600_000)},false,LEFT,,,EXAMPLE")))
        relay.sendResult = ok
        outbox.hurryNetworkWaits() // 띠 안에 망이 돌아왔다: 보내기 직전 재계산
        until("기다리던 요약 보냄") { state.state.value.outbox.none { it.payload is OutboxPayload.CareSummary } }
        assertEquals(listOf(1), relay.sent.filter { it.type == "care_summary" }.map { it.feeds() }.distinct()) // 가져온 수유는 세지 않았다
        recordCare.undo(notices.undo.value!!)
        until("되돌림") { care.care.value.events.map { it.id } == listOf(hand.id) }
        kotlinx.coroutines.delay(1_500) // 띠가 끝났을 시간도 지났다
        assertNull(notices.undo.value)
        assertEquals(listOf(1), relay.sent.filter { it.type == "care_summary" }.map { it.feeds() }.distinct())
        assertEquals(now - 2 * HOUR, state.state.value.lastSentFeedingAt) // 가져온 더 늦은 수유는 끝까지 가지 않았다
    }
}
