package kr.aigyeot.app.link

import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.ProtocolJson
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// contracts-link 2장·8.3·8.6: 가짜 소켓으로 연결 반복을 돌린다(대체 확인, ping, 저장 실패 시 닫기).
@RunWith(RobolectricTestRunner::class)
class LinkSocketTest : LinkKit() {
    @AfterTest fun stopLink() = link.stop()

    /** 기다림(backoff)을 건너뛰려고 깨우면서 조건을 기다린다. */
    private fun wakingUntil(cond: () -> Boolean) = eventually { link.wake(); cond() }

    @Test fun httpsFallback_runsAfterThreeFailures_thenReconcilesDevices() {
        phone()
        link.start()
        wakingUntil { relay.inboxCalls.isNotEmpty() }
        assertTrue(relay.socketCallsAtFirstInbox >= 3)    // 연속 3번 실패 뒤에야 대체 확인
        wakingUntil { relay.devicesCalls > 0 }            // 첫 성공 뒤 기기 목록 대조
    }

    @Test fun wake_whileConnected_sendsPingAtOnce() {
        phone()
        relay.onSocket = { it.onOpen() }
        link.start()
        eventually { link.status.value.conn == LinkConn.CONNECTED }
        assertFalse("ping" in relay.socketSent)           // 25초 전에는 보내지 않는다
        link.wake()
        eventually { "ping" in relay.socketSent }
    }

    @Test fun inboxStoreFailure_dropsLiveFramesAndReconnects() {
        profile(); phone()
        val a = env("feeding_started", 10, """{"at":"2026-11-05T08:12:21+09:00"}""", id = "01JC8Y2K6N3T5Q9W0ZB4H7X2D1")
        val b = env("feeding_started", 11, """{"at":"2026-11-05T09:00:00+09:00"}""", id = "01JC8Y2K6N3T5Q9W0ZB4H7X2D2")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_a BEFORE INSERT ON processed_message WHEN NEW.id = '${a.id}' BEGIN SELECT RAISE(ABORT, 'test'); END",
        )
        relay.inboxPages += Res.Ok(200, kr.aigyeot.shared.InboxResponse(listOf(a), false))
        relay.onSocket = { ev ->
            if (relay.socketCalls == 1) {   // 첫 연결만: 열리자마자 실시간 프레임 B
                ev.onOpen()
                ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), b))
            } else ev.onClosed(-1)
        }
        link.start()
        wakingUntil { relay.socketCalls >= 2 }            // 저장 실패로 소켓을 닫고 다시 붙었다(처리했다면 프레임을 기다리며 머문다)
        assertTrue(relay.inboxCalls.isNotEmpty())
        assertFalse(dao.processed(a.id))
        assertFalse(dao.processed(b.id))                  // 실시간 프레임은 처리하지 않았다
        assertTrue(relay.acked.isEmpty())
    }

    @Test fun invalid4001_waitsBeforeReRegister_noChain() {
        phone()
        relay.onSocket = { it.onClosed(4001) }
        link.openPairing()                                // 연결 화면이 열려 있어야 무효 뒤에도 소켓을 원한다(연쇄가 나는 조건)
        link.start()
        eventually { relay.registers >= 1 && relay.socketCalls >= 1 }
        Thread.sleep(1_000)                               // wake 없이: 기다림 간격을 둔다(예전에는 곧바로 재등록이 연쇄했다)
        assertTrue(relay.registers <= 4, "registers=${relay.registers}")   // 연결 반복·연결 코드 반복이 각각 1초 기다림
    }

    @Test fun socketUnreadableFrame_withId_isAcked_withoutId_isNot() {
        profile(); phone()
        val id = "01JC8Y2K6N3T5Q9W0ZB4H7X2D9"
        relay.onSocket = { ev ->
            if (relay.socketCalls == 1) {
                ev.onOpen()
                ev.onText("""{"v":"x","id":"$id","seq":7}""")
                ev.onText("""{"v":1,"seq":8}""")
            } else ev.onClosed(-1)
        }
        link.start()
        eventually { relay.socketSent.any { id in it } }
        assertEquals(1, relay.socketSent.count { it.contains("\"ack\"") })   // 빈 id는 ack하지 않는다
    }
}
