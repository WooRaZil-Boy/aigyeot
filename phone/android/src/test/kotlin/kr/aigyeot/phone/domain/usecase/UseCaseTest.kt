package kr.aigyeot.phone.domain.usecase

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.DisconnectRun
import kr.aigyeot.phone.domain.model.PairRun
import kr.aigyeot.phone.domain.model.SAVE_FAILED
import kr.aigyeot.phone.domain.model.restartNotice
import kr.aigyeot.phone.domain.repository.Mark
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.shared.InboxResponse
import kr.aigyeot.shared.PairResponse
import kr.aigyeot.shared.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun `받은 함을 저장한 뒤에만 ack한다`() = kitTest {
        val env = envelope("feeding_state", feedingState("2025-11-05T08:00:00+09:00"), "01JXRECV000000000000000001", seq = 5)
        relay.inboxResult = RelayResult.Ok(200, InboxResponse(listOf(env), more = false))
        assertEquals(SyncInbox.Sync.DONE, inbox.sync(OWNER))
        assertEquals(listOf(listOf(env.id)), relay.acked)
        assertEquals(5L, state.state.value.feeding?.seq)
    }

    @Test
    fun `받은 것을 저장하지 못하면 ack하지 않고 저장 실패를 알린다`() = kitTest(prepare = { File(it, "phone_state.json.new").mkdirs() }) {
        val env = envelope("feeding_state", feedingState("2025-11-05T08:00:00+09:00"), "01JXRECV000000000000000002", seq = 6)
        relay.inboxResult = RelayResult.Ok(200, InboxResponse(listOf(env), more = false))
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
        assertNotNull(state.state.value.lastSentCareSummary) // 넣은 적이 있으면 빈 요약을 보낸다(R10·R19)
    }
}
