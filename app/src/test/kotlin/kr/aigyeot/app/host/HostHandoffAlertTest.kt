package kr.aigyeot.app.host

import kr.aigyeot.app.store.SnapshotJson
import kr.aigyeot.engine.AskButton
import kr.aigyeot.engine.CareCleared
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.DriverStop
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.EpisodeRecord
import kr.aigyeot.engine.EpisodeState
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.HandoffCause
import kr.aigyeot.engine.HandoffKind
import kr.aigyeot.engine.HandoffLedger
import kr.aigyeot.engine.HandoffRecord
import kr.aigyeot.engine.Heard
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.PlaybackRejected
import kr.aigyeot.engine.QaReply
import kr.aigyeot.engine.QaStep
import kr.aigyeot.engine.RearDoor
import kr.aigyeot.engine.RejectReason
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.SessionState
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Side
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.Tick
import kr.aigyeot.engine.Via
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 인계 알림·guardian_notified·질문 답 조립.
@RunWith(RobolectricTestRunner::class)
class HostHandoffAlertTest : HostTestBase() {
    // ---- 인계 알림 넣기와 told 기록을 한 트랜잭션으로 ----


    @Test fun handoffAlertAndToldSavedInOneTransaction() {
        val (id, u) = speakingWithHandoff()
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.ev(SpeechDone(0, u, true))
        assertEquals(1, outboxTypes(), "알림이 보낼 함에"); assertEquals(1, toldDriverRow(id), "told가 Room에")
    }

    @Test fun failureInsideTransaction_neitherSaved() {
        val (id, u) = speakingWithHandoff()
        failTied()
        k.ev(SpeechDone(0, u, true))
        assertEquals(0, outboxTypes(), "둘 다 되돌려진다"); assertNull(toldDriverRow(id))
    }

    // ---- CareCleared도 넣을 때 stamp ----
    @Test fun careClearedStampedAtPost() {
        k.profile(); k.boot()
        val gate = CountDownLatch(1); val inside = CountDownLatch(1)
        k.onSlept = { k.onSlept = null; inside.countDown(); gate.await() }
        k.host.post(Tick(0)); inside.await()
        val postedAt = k.now + 1_000
        k.now = postedAt; k.host.postReal(CareCleared(0))
        k.now = postedAt + 60_000   // 큐에서 꺼낼 때는 시계가 더 갔다
        gate.countDown(); k.host.awaitIdle()
        assertEquals(postedAt, k.state().now, "넣을 때 찍은 시각으로 처리된다")
    }

    // ---- 판단 코어는 guardian_notified를 갖지 않는다. 복원 뒤 SaveEpisode가 Room의 거짓을 참으로 덮지 않는다 ----
    @Test fun restoreAppliesRoomGuardianNotifiedFalse() {
        val (id, u) = speakingWithHandoff()
        sql("UPDATE episode SET guardian_notified = 0 WHERE id = '$id'"); k.phone("owner")
        k.boot()
        k.ev(SpeechDone(0, u, true)).ev(GearChanged(0, Gear.P), k.now + 60_000).ev(RearDoor(0, Side.LEFT, true))
        assertNull(k.state().session); assertEquals(0, guardianNotifiedRow(id), "SaveEpisode가 true로 덮지 않는다")
        assertTrue(reportBodies().all { "\"guardian_notified\":false" in it }, reportBodies().toString())
    }

    // ---- link가 받는 사람이 없어 알림을 보낼 함에 넣지 않으면 guardian_notified는 거짓 그대로 ----
    private fun guardianNotifiedRow(id: String): Int = k.db.openHelper.readableDatabase.query("SELECT guardian_notified FROM episode WHERE id = '$id'").use { it.moveToFirst(); it.getInt(0) }

    @Test fun linkSkipsEnqueue_guardianNotifiedStaysFalse() {
        val (id, u) = speakingWithHandoff()
        sql("DELETE FROM phone_link")   // 엔진이 LinkChanged를 받기 전에 마지막 보호자가 해제됐다
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.ev(SpeechDone(0, u, true))
        assertEquals(0, outboxTypes(), "보낼 함에 넣지 않았다"); assertEquals(1, toldDriverRow(id))
        assertEquals(0, guardianNotifiedRow(id), "리포트에 '다른 보호자에게 알림'이 남지 않는다")
    }

    @Test fun linkSkipsEnqueue_closedEpisodeRow() {
        val (id, _) = closedSessionWithHandoff()
        sql("DELETE FROM phone_link")
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.boot()   // 닫힌 세션의 인계 묶음을 첫 Step이 정리한다
        assertEquals(0, outboxTypes()); assertEquals(0, toldDriverRow(id))
        assertEquals(0, guardianNotifiedRow(id), "닫힌 행도 거짓")
    }

    // ---- guardian_notified는 link가 보낼 함에 넣는 트랜잭션에서만 쓴다. 판단 코어는 이 값을 갖지 않는다 ----
    /** P에서 경보 → 뒷좌석 출력이 없어 재생 거절: 같은 Step에서 에피소드 행이 새로 생기고 알림이 바로 나간다. 그 에피소드 id. */
    private fun parkedNoSoundHandoff(): String {
        k.alarm()
        k.ev(PlaybackRejected(0, k.ses().play!!.id, RejectReason.NO_REAR_OUTPUT))
        assertEquals(Phase.HANDOFF, k.ses().phase)
        return k.ses().episode!!.id
    }

    @Test fun guardianNotifiedOnlyByLink() {
        k.phone("guardian", alerts = true); k.profile(); k.boot(); k.open()
        k.autoStart = false
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.ev(GearChanged(0, Gear.P), k.now + 1_000)
        val queued = parkedNoSoundHandoff()
        assertEquals(1, outboxTypes()); assertEquals(1, guardianNotifiedRow(queued), "넣은 트랜잭션에서 참(store 쓰기로 행이 먼저 생긴다)")
        val step = k.host.steps.value!!
        assertTrue(step.commands.any { it is SendToPhone && it.type == "handoff_alert" }, "전제: 이 Step이 알림을 냈다")
        val json = SnapshotJson.encodeToString(EngineState.serializer(), step.state) + step.commands.joinToString { it.toString() }
        assertTrue("guardianNotified" !in json && "guardian_notified" !in json, "판단 코어의 상태·할 일에 그 값이 없다: $json")
        val types = listOf(EngineState::class.java, SessionState::class.java, EpisodeState::class.java, HandoffRecord::class.java, HandoffLedger::class.java, EpisodeRecord::class.java)
        assertTrue(types.flatMap { it.declaredFields.toList() }.none { "guardian" in it.name.lowercase() && it.name != "otherGuardians" }, "판단 코어 타입에 칸이 없다")
        // 받는 사람이 없어 넣지 못하면 거짓 그대로(판단 코어는 캐시 otherGuardians > 0이라 알림을 냈다)
        sql("DELETE FROM phone_link")
        k.ev(Tick(0), k.now + Params().resumeQuietMs + 1_000)   // 조용함으로 에피소드를 닫는다
        assertNull(k.ses().episode)
        val skipped = parkedNoSoundHandoff()
        assertTrue(k.host.steps.value!!.commands.any { it is SendToPhone && it.type == "handoff_alert" }, "전제: 판단 코어는 알림을 냈다")
        assertEquals(1, outboxTypes(), "보낼 함에 넣지 않았다"); assertEquals(0, guardianNotifiedRow(skipped), "넣지 못하면 거짓")
        assertEquals(1, guardianNotifiedRow(queued), "앞 행은 그대로")
    }

    // ---- 장부 하나. 열린 세션 장부와 닫힌 세션 장부가 같은 확정 규칙(SettleHandoff)으로 행을 쓴다 ----
    @Test fun singleLedgerSettle() {
        fun kindRow(id: String) = k.db.openHelper.readableDatabase.query("SELECT handoff_kind FROM episode WHERE id = '$id'").use { it.moveToFirst(); it.getString(0) }
        fun row(id: String) = Triple(kindRow(id), toldDriverRow(id), restNameRow(id))
        // 닫힌 세션: 묶인 줄을 말하던 세션이 Room에서 끝난 채 복원 → 첫 Step의 flush가 닫힌 세션 장부를 확정
        val (closedId, _) = closedSessionWithHandoff(alert = false)
        k.running = emptySet(); k.boot()   // 마이크는 살아 있다(다음 세션이 입력 끊김을 이어받지 않게)
        assertNull(k.state().session); assertTrue(k.state().handoffs.isEmpty())
        // 열린 세션: 새 세션 에피소드의 장부가 묶인 줄 없이 남았다 → 첫 Step의 flush가 열린 세션 장부를 확정
        k.open(); k.alarm(); k.toGap()
        val s = k.state(); val x = s.session!!; val ep = x.episode!!
        k.store.saveSnapshot(s.copy(session = x.copy(episode = ep.copy(handoff = HandoffRecord(HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER))),
            handoffs = listOf(HandoffLedger(x.id, ep.id, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, alert = false))))
        k.running = null; k.boot()
        assertNotNull(k.state().session); assertTrue(k.state().handoffs.isEmpty())
        assertEquals(Triple("WAYPOINT_OFFER", 0, null), row(ep.id))
        assertEquals(row(ep.id), row(closedId), "열린·닫힌 세션 장부가 같은 규칙으로 행을 쓴다")
        // 확정 뒤 그 에피소드의 SaveEpisode가 확정 칸을 덮지 않는다(store가 지금 행 값을 지킨다)
        k.ev(DriverStop(0, Via.TOOL), k.now + 1_000)
        assertEquals(Phase.WAIT, k.ses().phase)
        assertEquals(Triple("WAYPOINT_OFFER", 0, null), row(ep.id))
    }

    // ---- 보낼 함에 못 넣으면 에피소드 행이 거짓이라 같은 Step의 리포트가 맞다 ----
    @Test fun notQueued_reportSaysNotNotified() {
        val (id, _) = closedSessionWithHandoff()
        sql("DELETE FROM phone_link"); k.phone("owner")   // 받는 보호자는 없고 리포트를 받을 owner만 남는다
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.boot()
        assertEquals(0, outboxTypes(), "알림은 보낼 함에 넣지 않았다")
        assertEquals(0, guardianNotifiedRow(id))
        val r = reportBodies(); assertEquals(1, r.size, "owner에게 리포트: $r")
        assertTrue("\"guardian_notified\":false" in r[0], r[0])
    }

    // ---- 넣지 않은 알림의 행은 다시 켜져도 거짓이다 ----
    @Test fun notQueuedClosedRow_restoreKeepsNotNotified() {
        val (id, _) = closedSessionWithHandoff()
        sql("DELETE FROM phone_link"); k.phone("owner")
        k.linkNowHook = { c, also -> k.realLink.enqueueNow(c, also) }
        k.boot()
        k.boot()   // 다시 켜짐: 복원 대조
        assertEquals(0, outboxTypes()); assertEquals(0, guardianNotifiedRow(id), "Room의 거짓 값이 남는다")
        val r = reportBodies(); assertEquals(1, r.size, "$r"); assertTrue("\"guardian_notified\":false" in r[0], r[0])
    }

    /** 말하던 줄은 말하지 않은 것으로 끝낸다(듣기를 열지 않게). */

    // ---- 사실 답은 큐에서 처리하는 순간의 상태로 짠다 ----
    private fun askThinking(): String {
        k.profile(); k.boot(); k.open()
        k.ev(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 1), seq = 1), k.now + 1_000)
        k.ev(GearChanged(0, Gear.P), k.now + 1_000)
        k.ev(AskButton(0), k.now + 1_000)
        val id = k.state().speech.qa!!.listenId
        k.ev(Heard(0, id, "오늘 몇 번 먹었어"), k.now + 1_000)
        assertEquals(QaStep.THINKING, k.state().speech.qa!!.step, "전제: 답을 기다린다")
        return id
    }
    private fun qaLine() = k.state().speech.queue.firstOrNull { it.sid == Sid.QA }?.vars?.get("text")
        ?: k.exec.filterIsInstance<Speak>().firstOrNull { it.sid == Sid.QA }?.vars?.get("text")

    @Test fun factAnswerComposedAtHostQueue() {
        val id = askThinking()
        k.ev(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 2), seq = 2), k.now + 1_000)   // 새 요약(2회)이 큐에서 먼저 처리된다
        assertEquals(true, k.state().speech.qa?.careStale, "전제: 질문 중 요약이 바뀌었다")
        k.ev(QaReply(0, id, "", careUsed = true, factTopics = listOf("feeding")), k.now + 1_000)
        assertEquals("기록상 오늘 2번 먹었어요.", qaLine(), "처리하는 순간의 요약(2회)으로 말한다")
    }

    @Test fun factAnswerAfterCareCleared_saysUnknown() {
        val id = askThinking()
        k.host.postReal(CareCleared(0)); k.host.awaitIdle()
        k.ev(QaReply(0, id, "", careUsed = true, factTopics = listOf("feeding")), k.now + 1_000)
        assertEquals("수유 기록을 확인할 수 없어요.", qaLine(), "지운 뒤 상태로 말한다")
    }

    // 대체 답도 큐에서 처리하는 순간의 상태로 짠다
    @Test fun whyFallbackComposedAtHost_afterCareCleared() {
        val id = askThinking()
        k.ev(CareSummary(0, CareView(k.now, true, null, null, null, null, null, null, null, 3), seq = 3), k.now + 1_000)
        k.host.postReal(CareCleared(0)); k.host.awaitIdle()
        k.ev(QaReply(0, id, "AI 글", careUsed = true, llm = true, fallback = "기록상 134분 전에 깼어요.", fallbackCare = true, fallbackTopics = listOf("why")), k.now + 1_000)
        val line = qaLine()
        assertTrue(line != null && "카시트에" in line && "134분" !in line, "$line")
    }

    @Test fun offlineWhyComposedAtHost() {
        val id = askThinking()
        k.host.postReal(CareCleared(0)); k.host.awaitIdle()
        k.ev(QaReply(0, id, "", careUsed = true, factTopics = listOf("why")), k.now + 1_000)
        val line = qaLine()
        assertTrue(line != null && "카시트에" in line && "기록상" !in line, "$line")
    }

    // QaReply 시각까지 시간 진행을 먼저 적용한 상태로 짠다
    @Test fun factComposedAfterTimeAdvance() {
        k.profile(); k.boot(Params(attemptMs = 2_500)); k.open()   // 재생이 질문 대기(4초) 안에 끝나게
        k.ev(GearChanged(0, Gear.P), k.now + 100)
        k.ev(AskButton(0), k.now + 100)
        val id = k.state().speech.qa!!.listenId
        k.ev(Heard(0, id, "얼마나 울었어"), k.now + 100)
        k.ev(GearChanged(0, Gear.D), k.now + 100).alarm(500)   // 질문 중 주행해 ATTEMPT 재생
        assertEquals(Phase.ATTEMPT, k.ses().phase, "전제: 재생 중")
        assertEquals(QaStep.THINKING, k.state().speech.qa?.step, "전제: 답을 기다린다")
        k.ev(QaReply(0, id, "", careUsed = false, factTopics = listOf("cry")), k.ses().play!!.endAt)
        assertEquals(Phase.GAP, k.ses().phase, "같은 사건에서 GAP")
        val line = qaLine()
        assertTrue(line != null && "울음이 그쳤는지 확인하고 있고" in line && "지금은 울음이 들리지 않고" !in line, "GAP에서는 확인 중이라 말한다 $line")
    }

    // ---- 그침 확인 중(GAP)의 울음 답은 '들리지 않고'가 아니다 ----
    @Test fun gapSaysConfirming_notSilent() {
        k.profile(); k.boot(Params(attemptMs = 2_500)); k.open()
        k.ev(GearChanged(0, Gear.P), k.now + 100)
        k.ev(AskButton(0), k.now + 100)
        val id = k.state().speech.qa!!.listenId
        k.ev(Heard(0, id, "얼마나 울었어"), k.now + 100)
        k.ev(GearChanged(0, Gear.D), k.now + 100).alarm(500)
        k.ev(QaReply(0, id, "", careUsed = false, factTopics = listOf("cry")), k.ses().play!!.endAt)
        assertEquals(Phase.GAP, k.ses().phase)
        val line = qaLine()
        assertTrue(line != null && line.startsWith("울음이 그쳤는지 확인하고 있고, 이번 주행에서 ") && line.endsWith("번 울었어요."), "$line")
    }
}
