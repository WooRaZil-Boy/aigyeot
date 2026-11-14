package kr.aigyeot.app.host

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.aigyeot.engine.CallMode
import kr.aigyeot.engine.CancelSpeech
import kr.aigyeot.engine.DriverStop
import kr.aigyeot.engine.FeedingFrom
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.Line
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.Tick
import kr.aigyeot.engine.Via
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 발화 끊기·시계 점프·Suspend.
@RunWith(RobolectricTestRunner::class)
class HostSpeechClockTest : HostTestBase() {
    // ---- Suspend 짐작 ----

    @Test fun clockJumpWithoutSignal_doesNotCountSleepAsQuiet() {
        k.profile()
        k.boot()
        k.open()
        k.alarm()
        k.ev(DriverStop(0, Via.VOICE))
        assertEquals(Phase.WAIT, k.ses().phase)
        val q = k.ses().quietMs
        k.slept += 600_000
        k.ev(Tick(0), k.now + 600_000)
        assertEquals(Phase.WAIT, k.ses().phase, "잠든 10분이 에피소드를 닫지 않는다")
        assertNotNull(k.ses().episode)
        assertEquals(q, k.ses().quietMs)
        assertNull(k.state().ign.suspendedAt)
    }

    @Test fun jumpAfterSuspendSignal_resumes() {
        k.profile()
        k.boot()
        k.open()
        k.host.suspend()
        k.host.awaitIdle()
        assertNotNull(k.state().ign.suspendedAt)
        k.host.suspend()
        k.host.awaitIdle() // 중복은 호스트가 막는다
        k.slept += 600_000
        k.ev(Tick(0), k.now + 600_000)
        assertNull(k.state().ign.suspendedAt)
    }

    // ---- 발화를 끊는 사건(장면 시작·CallMode(true)·Suspend)은 실행기에 원자적으로 묻는다. 이미 끝났으면 그 SpeechDone을 먼저 처리한다 ----
    @Test fun sceneStart_speechAlreadyDone_toldTrue_sceneInputsStayInScene() {
        val (id, u) = speakingWithHandoff(handoffPhase = true)
        val feedBefore = k.db.storeDao().profile()?.lastFeedingStart
        k.exec.clear()
        k.link.clear()
        k.cancelIfSpeakingHook = { true to k.now } // 실행기에서는 이미 끝났다(spoken=true). SpeechDone은 큐에 이미 들어 있다
        val gate = CountDownLatch(1)
        val inside = CountDownLatch(1)
        k.onSlept = {
            k.onSlept = null
            inside.countDown()
            gate.await()
        } // 호스트를 붙잡고 큐를 쌓는다: 장면 시작 → 장면 입력 → SpeechDone
        k.host.post(Tick(0))
        inside.await()
        k.host.startScenario(setup())
        k.host.post(FeedingStarted(0, k.now - 1_000, FeedingFrom.PHONE, "m-scene"))
        k.host.postReal(SpeechDone(0, u, true))
        gate.countDown()
        k.host.awaitIdle()
        assertEquals(1, toldDriverRow(id), "끝난 발화는 말했다(true)로 확정된다")
        assertEquals("A", k.host.scenarioId, "장면은 미루지 않고 시작했다")
        assertEquals(feedBefore, k.db.storeDao().profile()?.lastFeedingStart, "장면 입력이 실제 Room 수유 시각을 바꾸지 않는다")
        k.host.endScenario()
        k.host.awaitIdle()
        assertNotEquals(k.now - 1_000, k.state().feedingStart, "장면 입력이 실제 상태로 새지 않는다")
        assertEquals(1, k.link.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" }, "알림은 한 번")
    }

    @Test fun callMode_speechAlreadyDone_toldTrue() {
        val (id, u) = speakingWithHandoff(handoffPhase = true)
        k.cancelIfSpeakingHook = { true to k.now }
        k.ev(CallMode(0, true))
        k.host.postReal(SpeechDone(0, u, true))
        k.host.awaitIdle() // 뒤의 같은 SpeechDone은 엔진이 무시한다
        assertEquals(1, toldDriverRow(id), "끝난 발화는 true로 확정")
        assertNull(k.state().speech.speaking)
        assertEquals(1, k.link.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" })
    }

    @Test fun suspend_speechAlreadyDone_toldTrue() {
        val (id, u) = speakingWithHandoff(handoffPhase = true)
        k.cancelIfSpeakingHook = { true to k.now }
        k.host.suspend()
        k.host.postReal(SpeechDone(0, u, true))
        k.host.awaitIdle()
        assertNotNull(k.state().ign.suspendedAt, "전제: Suspend가 처리됐다")
        assertEquals(1, toldDriverRow(id), "끝난 발화는 true로 확정")
        assertNull(k.state().speech.speaking)
        assertEquals(1, k.link.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" })
    }

    @Test fun stillSpeaking_cancelledAsBefore() {
        val (id, u) = speakingWithHandoff(handoffPhase = true)
        k.exec.clear()
        k.link.clear()
        k.cancelIfSpeakingHook = { x ->
            assertEquals(u, x)
            k.exec += CancelSpeech
            null
        }
        k.host.startScenario(setup())
        k.host.awaitIdle()
        assertTrue(k.exec.any { it is CancelSpeech })
        assertNull(k.state().session)
        assertEquals(0, toldDriverRow(id), "끊긴 안내는 false")
        val a = k.link.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
        assertEquals(1, a.size)
    }

    // ---- 끊기 직전에 이미 끝난 발화는 실제 완료 시각으로 SpeechDone ----
    @Test fun settledSpeechUsesActualDoneTime() {
        val (_, u) = speakingWithHandoff(handoffPhase = true)
        val doneAt = k.now + 500 // 끊는 사건(+1000)보다 이르다. 늦으면 사건 시각에 맞춘다
        k.cancelIfSpeakingHook = { x ->
            assertEquals(u, x)
            true to doneAt
        }
        k.ev(CallMode(0, true), k.now + 1_000)
        assertEquals(doneAt, k.state().speech.lastDoneAt, "호스트가 밀려도 실제 완료 시각")
    }

    // SpeechDone 시각 = min(실제 완료, 끊는 사건 시각). 점프 Suspend는 잠들기 전 시각이라 잠든 시간을 세지 않는다
    @Test fun jumpSuspend_settleDoesNotCountSleep() {
        speakingWithHandoff(handoffPhase = true)
        val q0 = k.ses().quietMs
        val wake = k.now + 600_000
        k.cancelIfSpeakingHook = { true to wake } // 깨어난 뒤에야 TTS 완료가 보고됐다
        k.slept += 600_000
        k.ev(Tick(0), wake)
        assertTrue((k.state().session?.quietMs ?: 0) - q0 < 600_000, "잠든 10분이 quietMs에 더해지지 않는다")
        assertNotEquals(Phase.LISTENING, k.state().session?.phase)
    }

    // ---- 통화·장면 시작은 밀려 늦게 처리돼도 실제 완료 시각 그대로(8초 간격). Suspend만 사건 시각에 맞춘다 ----
    private fun queuedBehindHost(post: () -> Unit, doneAt: Long, processAt: Long) {
        val gate = CountDownLatch(1)
        val inside = CountDownLatch(1)
        k.onSlept = {
            k.onSlept = null
            inside.countDown()
            gate.await()
        }
        k.host.post(Tick(0))
        inside.await()
        post() // 사건은 지금 시각으로 stamp된다
        k.now = processAt
        k.cancelIfSpeakingHook = { true to doneAt }
        gate.countDown()
        k.host.awaitIdle()
    }

    @Test fun callMode_queuedEventEarlierThanDone_keepsDoneAt() {
        speakingWithHandoff(handoffPhase = true)
        val t0 = k.now
        queuedBehindHost({ k.host.post(CallMode(0, true)) }, doneAt = t0 + 4_000, processAt = t0 + 5_000)
        assertEquals(t0 + 4_000, k.state().speech.lastDoneAt, "통화 사건 시각(t0)이 아니라 실제 완료 시각")
    }

    @Test fun sceneStart_keepsDoneAt() {
        speakingWithHandoff(handoffPhase = true)
        val doneAt = k.now + 500
        k.cancelIfSpeakingHook = { true to doneAt }
        k.host.startScenario(setup())
        k.host.awaitIdle()
        k.host.endScenario()
        k.host.awaitIdle()
        assertEquals(doneAt, k.state().speech.lastDoneAt)
    }

    @Test fun normalSuspend_keepsDoneAt() {
        speakingWithHandoff(handoffPhase = true)
        val doneAt = k.now + 5_000 // 사건 시각(지금)보다 늦은 실제 완료: 이른 시각 상한이면 앞당겨진다
        k.cancelIfSpeakingHook = { true to doneAt }
        k.host.suspend()
        k.host.awaitIdle()
        assertEquals(doneAt, k.state().speech.lastDoneAt)
    }

    // 점프 확인의 첫 정리 뒤 다음 발화가 바로 끝나도(포커스 거절) 그 정리도 잠들기 전 시각이 상한이다
    @Test fun jumpSettle_nextSpeechFailsAtOnce_sleepNotCounted() {
        val (_, u) = speakingWithHandoff(handoffPhase = true, extra = listOf(Line(Sid.S19, emptyMap(), null, qaAnswer = true)))
        val q0 = k.ses().quietMs
        val wake = k.now + 600_000
        k.cancelIfSpeakingHook = { x -> if (x == u) true to wake else false to wake } // 첫 발화는 끝났고 다음 발화는 바로 실패
        k.slept += 600_000
        k.ev(Tick(0), wake)
        assertTrue((k.state().session?.quietMs ?: 0) - q0 < 600_000, "잠든 10분이 quietMs에 더해지지 않는다")
        assertEquals(Phase.HANDOFF, k.state().session?.phase, "인계를 끝내지 않는다")
    }

    // ---- 실행기 결과를 버리는 모든 곳(통화 시작·Suspend·시계 점프·장면 시작·장면 교체)이 먼저 정리한다. 끝났으면 told=참(1), 말하는 중이면 끊어 거짓(0) ----
    private fun settleCase(finished: Boolean, trigger: (wake: Long) -> Unit) {
        val (id, _) = speakingWithHandoff(handoffPhase = true)
        val wake = k.now + 600_000
        k.cancelIfSpeakingHook = if (finished) { _ -> true to wake } else null
        trigger(wake)
        assertEquals(if (finished) 1 else 0, toldDriverRow(id))
    }
    private fun call(@Suppress("UNUSED_PARAMETER") w: Long) {
        k.ev(CallMode(0, true), k.now + 1_000)
    }
    private fun suspendNow(@Suppress("UNUSED_PARAMETER") w: Long) {
        k.host.suspend()
        k.host.awaitIdle()
    }
    private fun jump(w: Long) {
        k.slept += 600_000
        k.ev(Tick(0), w)
    }
    private fun sceneStart(@Suppress("UNUSED_PARAMETER") w: Long) {
        k.host.startScenario(setup())
        k.host.awaitIdle()
    }

    @Test fun call_finished() = settleCase(true, ::call)

    @Test fun call_stillSpeaking() = settleCase(false, ::call)

    @Test fun suspend_finished() = settleCase(true, ::suspendNow)

    @Test fun suspend_stillSpeaking() = settleCase(false, ::suspendNow)

    @Test fun jump_finished() = settleCase(true, ::jump)

    @Test fun jump_stillSpeaking() = settleCase(false, ::jump)

    @Test fun sceneStart_finished() = settleCase(true, ::sceneStart)

    @Test fun sceneStart_stillSpeaking() = settleCase(false, ::sceneStart)
    // endScenario의 두 경우는 SceneCloseTest endScenario_alreadyFinishedHandoffSpeech_toldTrue·endScenario_stillSpeaking_toldFalse

    // 장면 중 장면 교체: 장면 상태의 발화도 실행기에서 먼저 끊고 정리한다
    @Test fun sceneReplace_settlesSceneSpeech() {
        k.profile()
        k.boot()
        k.host.startScenario(setup())
        k.host.awaitIdle()
        k.open()
        k.alarm()
        val u = k.state().speech.speaking!!
        var asked: String? = null
        k.cancelIfSpeakingHook = { x ->
            asked = x
            null
        }
        k.host.startScenario(setup())
        k.host.awaitIdle()
        assertEquals(u, asked, "교체 전에 실행기에 묻는다")
    }

    // 점프 중 첫 정리가 다음 발화를 시작하고 그 발화가 바로 끝나는 경우 run 안의 Suspend 정리도 상한을 쓴다. HANDOFF 유지
    @Test fun jumpCapAppliesToNestedSettle() = jumpSettle_nextSpeechFailsAtOnce_sleepNotCounted()

    @Test fun jumpSuspend_stillClampedToEventTime() = jumpSuspend_settleDoesNotCountSleep()
}
