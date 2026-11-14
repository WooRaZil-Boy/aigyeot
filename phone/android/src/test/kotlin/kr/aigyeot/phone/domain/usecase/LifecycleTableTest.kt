package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.yield
import kr.aigyeot.phone.domain.model.Banner
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.OutboxPayload
import kr.aigyeot.phone.domain.model.PairRun
import kr.aigyeot.phone.domain.model.SendResult
import kr.aigyeot.phone.domain.model.outboxItem
import kr.aigyeot.phone.domain.model.songRef
import kr.aigyeot.phone.domain.repository.Mark
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.shared.PairResponse
import kr.aigyeot.shared.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 수명 표 — 행 하나에 시험 하나. 사건: 연결 끊김(401·4001·사용자), 방 바뀜(새 연결), 기록 지우기, 앱 재시작.
 * 재시작은 같은 폴더·같은 표시로 Kit을 다시 만들어 본다.
 */
class LifecycleTableTest {
    private val guardianPair = PairResponse("c_NEWCAR0000000000000000000000", "d_NEWPHONE00000000000000000", "tok-new", Role.GUARDIAN)

    /** 같은 폴더·표시로 다시 켠다. 범위는 이 Kit의 자식이라 시험이 끝나면 함께 닫힌다. */
    private fun Kit.restart(): Kit = Kit(CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])), dir, credentials.stored, marks.values)

    /** 기록 지우기 한 번이 상태에 들어갈 때까지(지우기마다 상태 요청 세대가 오른다). */
    private suspend fun Kit.clearAndWait() {
        val gen = state.state.value.stateNeedGen
        clearRecords()
        until("기록 지우기") { state.state.value.stateNeedGen > gen }
        settle()
    }

    @Test
    fun `보낼 함 항목 - 재시작에 남고 기록 지우기에 노래 올리기만 빠지고 끊기에 사라진다`() = kitTest {
        relay.sendResult = { RelayResult.Offline } // 차로 보내지 못해 보낼 함에 남는다
        selectSong("s1")
        until("고르기가 보낼 함에") { state.state.value.outbox.any { it.envelope.type == "song_selected" } }
        // 재시작: 파일에서 이어 보낸다.
        assertEquals(listOf("song_selected"), restart().state.state.value.outbox.map { it.envelope.type })
        // 기록 지우기: 녹음 파일이 필요한 노래 올리기만 뺀다.
        state.commit(OWNER) { it.copy(outbox = it.outbox + outboxItem(OutboxPayload.Song("s1", songRef("s1"), uploaded = false, file = "x.m4a"), now, "01JXSONG00000000000000000A")) }
        clearAndWait()
        assertEquals(listOf("song_selected"), state.state.value.outbox.map { it.envelope.type })
        // 연결 끊김: 상태와 함께 사라진다.
        lose.lost(OWNER)
        assertTrue(state.state.value.outbox.isEmpty())
    }

    @Test
    fun `메시지 429 기다림 - 기록 지우기에 남고 재시작은 저장된 값만 보고 끊기면 걸리지 않는다`() = kitTest {
        relay.sendResult = { RelayResult.Err(429, "rate_limited", 60) }
        selectSong("s1")
        until("429 기다림 저장") { state.state.value.messageHoldUntil != null }
        assertEquals(now + 60_000, state.state.value.messageHoldUntil)
        clearAndWait()
        assertEquals(now + 60_000, outbox.holdUntil(now))
        assertEquals(now + 60_000, restart().outbox.holdUntil(now))
        // 끊기: 저장된 기다림은 새 상태와 함께 사라지고, 메모리 기다림은 그 자격에만 걸린다.
        outbox.holdInMemory(OWNER, now + 120_000)
        lose.lost(OWNER)
        assertNull(outbox.holdUntil(now))
    }

    @Test
    fun `노래 업로드 - 보내는 사이 기록을 지우면 노래를 되살리지 않는다`() = kitTest {
        val rec = File(dir, "cache/recording-a.m4a").apply { writeText("x") }
        songs.locked {
            recordSong.send(rec, "자장가", 30.0)
            settle() // 보내기가 지우기 횟수를 읽고 파일 잠금을 기다린다
            clearRecords()
        }
        until("보내기 끝") { recordSong.sends.value[rec.path] != null }
        assertTrue(state.state.value.songs.isEmpty())
        assertTrue(state.state.value.outbox.none { it.envelope.type == "song_added" })
    }

    @Test
    fun `녹음 임시 파일 - 끊기·새 연결에 남고 기록 지우기는 누른 때까지만 지우고 재시작은 앞 프로세스 것을 지운다`() {
        kitTest(prepare = { File(it, "cache").mkdirs(); File(it, "cache/recording-old.m4a").writeText("old") }) {
            lifecycle.start()
            until("앞 프로세스 녹음 지움") { !File(dir, "cache/recording-old.m4a").exists() }
            val before = File(dir, "cache/recording-before.m4a").apply { writeText("b"); setLastModified(now - 1_000) }
            val after = File(dir, "cache/recording-after.m4a").apply { writeText("a"); setLastModified(now + 60_000) }
            clearRecords()
            until("누른 때까지의 임시 녹음 지움") { !before.exists() }
            assertTrue(after.exists())
            lose.lost(OWNER)
            assertTrue(after.exists())
        }
    }

    @Test
    fun `연결 결과 - GONE이면 완료로 가지 않고 완료 화면 표시는 재시작에 남는다`() = kitTest(creds = null) {
        relay.pairResult = RelayResult.Ok(200, guardianPair)
        pair.start("123456")
        until("연결 끝") { pair.run.value is PairRun.Done }
        val done = pair.run.value!!
        assertEquals(Role.GUARDIAN, (done as PairRun.Done).role)
        assertEquals(Role.GUARDIAN, restart().pair.pendingDoneRole())
        lose.lost(state.creds!!)
        assertFalse(pair.takeDone(done))
    }

    @Test
    fun `상태 요청 표시 - 기록 지우기에 켜지고 끊기·새 연결에 꺼진다`() = kitTest {
        state.commit(OWNER) { it.copy(stateRequestNeeded = false) } // 복구한 시작 상태는 켜져 있다
        clearAndWait()
        assertTrue(state.state.value.stateRequestNeeded)
        lose.lost(OWNER)
        assertFalse(state.state.value.stateRequestNeeded)
        relay.pairResult = RelayResult.Ok(200, guardianPair)
        assertTrue(lose.acknowledgeGone())
        pair.start("123456")
        until("연결 끝") { pair.run.value is PairRun.Done }
        assertFalse(state.state.value.stateRequestNeeded)
    }

    @Test
    fun `연결 반복 - 자격이 바뀌면 멈추고 표시가 처음으로 돌아가며 새 자격으로 다시 연다`() = kitTest {
        visibility.set(true)
        connection.start()
        assertTrue(network.watching)
        connection.observed(OWNER.token, 99, up = false)
        until("오프라인") { !connection.status.value.online }
        lose.lost(OWNER)
        until("멈춤") { !network.watching }
        assertTrue(connection.status.value.online)
        relay.pairResult = RelayResult.Ok(200, guardianPair)
        lose.acknowledgeGone()
        pair.start("123456")
        until("새 자격으로 다시 엶") { network.watching }
    }

    @Test
    fun `띠와 배너 - 끊기·새 연결에 비우고 기록 지우기는 배너만 걷고 재시작은 저장된 버림만 다시 띄운다`() = kitTest {
        relay.sendResult = { RelayResult.Err(403, "forbidden", null) } // 다시 보내도 같은 실패: 버리고 띠로 알린다
        selectSong("s1")
        until("버림 띠") { notices.notice.value != null }
        val dropLine = notices.notice.value
        assertEquals(dropLine, restart().notices.notice.value)
        notices.showBanner(Banner("인계 알림", "01JXALERT000000000000000AB"))
        clearAndWait()
        assertNull(notices.banner.value)
        assertEquals(dropLine, notices.notice.value)
        notices.showCare("앞 수유를 끝냈어요")
        lose.lost(OWNER)
        assertNull(notices.notice.value)
        relay.pairResult = RelayResult.Ok(200, guardianPair)
        lose.acknowledgeGone()
        pair.start("123456")
        until("연결 끝") { pair.run.value is PairRun.Done }
        assertNull(notices.careNotice.value)
        yield()
    }

    @Test
    fun `재시작 복구 - 지금 자격의 강제 끊김 기록이면 정리를 마치고 GONE을 보인다`() = kitTest(marks = mapOf(Mark.GONE_CLEANUP_PENDING to OWNER.deviceId)) {
        assertTrue(state.connection.value is Connection.Paired)
        disconnect.recoverAtStart()
        until("GONE") { state.connection.value is Connection.Gone }
        assertNull(credentials.stored)
        assertEquals(OWNER.deviceId, marks.get(Mark.GONE_CLEANUP_PENDING)) // 고를 때까지 남는다
        // 그대로 다시 켜면(자격 없음 + 기록) 처음부터 GONE이다.
        assertEquals(Connection.Gone(OWNER.deviceId), restart().state.connection.value)
        assertTrue(lose.acknowledgeGone(recordsOnly = true))
        assertNull(marks.get(Mark.GONE_CLEANUP_PENDING))
        assertTrue(link.recordsOnly)
        assertEquals(Connection.None, state.connection.value)
    }

    @Test
    fun `재시작 복구 - 다른 기기의 옛 기록은 걷고 연결은 그대로다`() = kitTest(marks = mapOf(Mark.GONE_CLEANUP_PENDING to "d_OLD")) {
        disconnect.recoverAtStart()
        settle()
        assertNull(marks.get(Mark.GONE_CLEANUP_PENDING))
        assertEquals(Connection.Paired(OWNER), state.connection.value)
        assertNotNull(credentials.stored)
    }

    @Test
    fun `보낼 함 업로드 결과는 SendResult로 바뀐다`() = kitTest {
        val item = outboxItem(OutboxPayload.Song("s1", songRef("s1"), uploaded = false, file = "none.m4a"), now)
        assertEquals(SendResult.Failed(415, "file_missing"), UploadSong(relay, songs)(OWNER, item))
        // 파일을 올린 노래(재시작 뒤 저장에서 읽은 것 포함)와 노래가 아닌 항목은 PUT 없이 봉투로 간다(두 단계).
        assertNull(UploadSong(relay, songs)(OWNER, item.markUploaded(true)))
        assertNull(UploadSong(relay, songs)(OWNER, outboxItem(OutboxPayload.Message("song_selected", songRef("s1")), now)))
    }
}
