package kr.aigyeot.app.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.engine.ClearRecords
import kr.aigyeot.engine.Listen
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.SpeechDone
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 기록 지우기와 저장 실패 시 프로세스 종료.
@RunWith(RobolectricTestRunner::class)
class HostFailureTest : HostTestBase() {
    // ---- 기록 지우기 ----

    @Test fun lateControlLogAfterClear_notRecreated() {
        k.profile()
        k.boot()
        k.open()
        val sid = k.ses().id
        k.ev(ClearRecords(0))
        val row = { s: String? ->
            ControlLogEntity(
                sessionId = s, at = k.now, api = "a", target = "t",
                goal = "g", actual = null, ackMs = null, appliedMs = null, ok = true, retries = 0
            )
        }
        k.host.logControl(row(sid))
        k.host.awaitIdle()
        val q = {
            k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM control_log").use {
                it.moveToFirst()
                it.getInt(0)
            }
        }
        assertEquals(0, q())
        k.host.logControl(row(null))
        k.host.awaitIdle()
        assertEquals(1, q())
    }

    @Test fun clearRecords_recordsDroppedOutboxIds_storeOneTransaction_keepsProfileLinkSongs() {
        k.profile()
        k.phone()
        k.boot()
        k.open()
        k.alarm()
        sql(
            "INSERT INTO outbox (id, type, to_device, body_json, created_at, attempts, next_try_at) " +
                "VALUES ('o1', 'handoff_alert', NULL, '{}', 0, 1, 0), ('o2', 'feeding_state', NULL, '{}', 0, 0, 0), ('o3', 'handoff_alert', NULL, '{}', 0, 0, 0)"
        )
        k.ev(ClearRecords(0))
        val q = { t: String ->
            k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $t").use {
                it.moveToFirst()
                it.getInt(0)
            }
        }
        listOf("session", "episode", "play_log", "alarm_log", "context_sample", "control_log", "steer_window").forEach { assertEquals(0, q(it), it) }
        val types = k.db.openHelper.readableDatabase.query("SELECT type FROM outbox ORDER BY type").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals(listOf("feeding_state", "records_clear"), types)
        // 지우기 때 뺀 report_summary·handoff_alert id가 records_clear 본문에 담긴다
        val body = k.db.openHelper.readableDatabase.query("SELECT body_json FROM outbox WHERE type = 'records_clear'").use {
            it.moveToFirst()
            it.getString(0)
        }
        assertEquals("{\"drop_ids\":[\"o1\"]}", body) // 보낸 적 없는 o3은 담지 않는다
        assertEquals(1, q("child_profile"))
        assertEquals(1, q("phone_link"))
        assertTrue(k.store.loadSnapshot()!!.history.isEmpty())
        assertTrue(k.ses().recordsCleared)
    }

    // ---- 묶음 트랜잭션이 실패하면 예외를 다시 던져 프로세스를 끝낸다(나머지 할 일·스냅숏 없음) ----
    private fun pendingAlertInSnapshot() = k.store.loadSnapshot()!!.openLedger()

    @Test fun tiedTxFailure_throws_noFurtherCommandsOrSnapshot() {
        val (id, u) = speakingWithHandoff()
        assertNotNull(pendingAlertInSnapshot(), "전제: 스냅숏에 대기 인계가 있다")
        failTied()
        k.exec.clear()
        val died = catchingHostDeath { k.host.post(SpeechDone(0, u, true)) }
        assertNotNull(died, "예외가 프로세스 밖으로 나간다(호스트 스레드는 삼킨다)")
        assertEquals("커밋 전 실패", generateSequence(died) { it.cause }.last().message)
        assertNotNull(pendingAlertInSnapshot(), "스냅숏은 옛 것 그대로")
        assertEquals(0, outboxTypes())
        assertNull(toldDriverRow(id))
        assertTrue(k.exec.none { it is Listen }, "그 Step의 나머지 할 일은 나가지 않는다")
    }

    @Test fun restartAfterFailure_flushesPlain() {
        val (id, u) = speakingWithHandoff()
        failTied()
        catchingHostDeath { k.host.post(SpeechDone(0, u, true)) }
        k.exec.clear()
        k.link.clear()
        k.running = null
        k.linkNowHook = null
        k.boot() // 죽었다 다시 뜸: 옛 스냅숏으로 복원
        val a = k.link.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
        assertEquals(1, a.size, "알림을 잃지 않는다")
        assertEquals("아기가 계속 울어요.", a.single().body["text"]!!.toString().trim('"'))
        assertEquals(0, toldDriverRow(id))
    }

    // ---- dead 뒤에는 큐에 있던 flush·snapshot·장면 시작이 상태를 저장하거나 바꾸지 않는다 ----
    @Test fun afterDie_flushAndSnapshotBlocked() {
        val (id, u) = speakingWithHandoff()
        failTied()
        catchingHostDeath {
            k.host.post(SpeechDone(0, u, true))
            k.host.flush()
            k.host.startScenario(setup())
        }
        assertNotNull(pendingAlertInSnapshot(), "실패 뒤 flush가 대기 인계를 지운 상태를 저장하지 않는다(스냅숏은 실패 전 그대로)")
        assertNull(k.host.scenarioId, "장면 시작도 막힌다")
        k.exec.clear()
        k.link.clear()
        k.running = null
        k.linkNowHook = null
        k.boot()
        val a = k.link.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
        assertEquals(1, a.size, "알림을 잃지 않는다")
        assertEquals("아기가 계속 울어요.", a.single().body["text"]!!.toString().trim('"'))
        assertEquals(0, toldDriverRow(id))
    }

    // ---- 기록 지우기 트랜잭션 실패는 die, 부분 상태 없음 ----
    @Test fun clearRecordsTxFailureDies_noPartialState() {
        k.profile()
        k.phone()
        k.boot()
        k.open()
        k.alarm()
        val before = k.snapshotJson()
        sql("CREATE TRIGGER clr_fail BEFORE INSERT ON outbox WHEN NEW.type = 'records_clear' BEGIN SELECT RAISE(ABORT, '지우기 실패'); END")
        val died = catchingHostDeath { k.host.post(ClearRecords(0)) }
        assertNotNull(died, "삼키지 않고 프로세스를 끝낸다")
        val n = { t: String ->
            k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $t").use {
                it.moveToFirst()
                it.getInt(0)
            }
        }
        assertTrue(n("session") > 0 && n("episode") > 0, "되돌려져 기록이 그대로")
        assertEquals(0, n("outbox"), "records_clear를 넣지 못했다")
        assertEquals(before, k.snapshotJson(), "스냅숏도 옛 것 그대로")
    }
}
