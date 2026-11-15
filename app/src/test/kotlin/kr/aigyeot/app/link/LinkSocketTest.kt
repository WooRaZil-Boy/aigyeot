package kr.aigyeot.app.link

import android.database.sqlite.SQLiteException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kr.aigyeot.app.link.db.RelayCredentialEntity
import kr.aigyeot.shared.DeviceInfo
import kr.aigyeot.shared.DevicesResponse
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.InboxResponse
import kr.aigyeot.shared.PairCodeResponse
import kr.aigyeot.shared.Platform
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.Role
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 가짜 소켓으로 연결 반복을 돌린다(대체 확인, ping, 저장 실패 시 닫기).
@RunWith(RobolectricTestRunner::class)
class LinkSocketTest : LinkKit() {
    @AfterTest fun stopLink() = link.stop()

    /** 기다림(backoff)을 건너뛰려고 깨우면서 조건을 기다린다. */
    private fun wakingUntil(cond: () -> Boolean) = eventually {
        link.wake()
        cond()
    }

    @Test fun httpsFallback_runsAfterThreeFailures_thenReconcilesDevices() {
        phone()
        link.start()
        wakingUntil { relay.inboxCalls.isNotEmpty() }
        assertTrue(relay.socketCallsAtFirstInbox >= 3) // 연속 3번 실패 뒤에야 대체 확인
        wakingUntil { relay.devicesCalls > 0 } // 첫 성공 뒤 기기 목록 대조
    }

    @Test fun wake_whileConnected_sendsPingAtOnce() {
        phone()
        relay.onSocket = { it.onOpen() }
        link.start()
        eventually { link.status.value.conn == LinkConn.CONNECTED }
        assertFalse("ping" in relay.socketSent) // 25초 전에는 보내지 않는다
        link.wake()
        eventually { "ping" in relay.socketSent }
    }

    @Test fun inboxStoreFailure_dropsLiveFramesAndReconnects() {
        profile()
        phone()
        val a = env("feeding_started", 10, """{"at":"2026-11-05T08:12:21+09:00"}""", id = "01JC8Y2K6N3T5Q9W0ZB4H7X2D1")
        val b = env("feeding_started", 11, """{"at":"2026-11-05T09:00:00+09:00"}""", id = "01JC8Y2K6N3T5Q9W0ZB4H7X2D2")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_a BEFORE INSERT ON processed_message WHEN NEW.id = '${a.id}' BEGIN SELECT RAISE(ABORT, 'test'); END"
        )
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(a), false))
        relay.onSocket = { ev ->
            if (relay.socketCalls == 1) { // 첫 연결만: 열리자마자 실시간 프레임 B
                ev.onOpen()
                ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), b))
            } else {
                ev.onClosed(-1)
            }
        }
        link.start()
        wakingUntil { relay.socketCalls >= 2 } // 저장 실패로 소켓을 닫고 다시 붙었다(처리했다면 프레임을 기다리며 머문다)
        assertTrue(relay.inboxCalls.isNotEmpty())
        assertFalse(dao.processed(a.id))
        assertFalse(dao.processed(b.id)) // 실시간 프레임은 처리하지 않았다
        assertTrue(relay.acked.isEmpty())
    }

    @Test fun lastPhoneUnpaired_thenNewPaired_notLost() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        val b =
            relayEnv("paired", 11, """{"device_id":"$GUARD","role":"guardian","platform":"ios","paired_at":"2026-11-05T09:01:44Z","handoff_alerts":false}""")
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) // 붙을 때 받은 함은 비어 있다
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(b), false)) // 폰이 빠진 뒤 다시 받은 함에는 B가 있다
        // 확인은 모두 성공. 붙을 때 목록(1번째)에는 A, 폰이 빠진 뒤 다시 확인하는 목록(2번째)에는 B가 있다
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(device(PHONE, Role.OWNER))))
        relay.onDevices = { if (relay.devicesCalls >= 2) relay.devicesRes = Res.Ok(200, DevicesResponse(531, listOf(device(GUARD, Role.GUARDIAN)))) }
        relay.onSocket = { ev ->
            if (relay.socketCalls == 1) { // 같은 소켓에 A의 unpaired, 이어 B의 paired
                ev.onOpen()
                ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
                ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), b))
            } else {
                ev.onClosed(-1)
            }
        }
        link.start()
        eventually { dao.phoneLink(GUARD) != null } // 고치기 전: B 프레임이 버려지고 폰이 없어 다시 붙지도 않아 영영 없다
        assertEquals(null, dao.phoneLink(PHONE))
        Thread.sleep(300)
        assertEquals(1, relay.socketCalls) // 확인 뒤 폰이 있으니 닫지 않았다
        assertEquals(LinkConn.CONNECTED, link.status.value.conn)
    }

    @Test fun confirmedNoPhone_closesSocket() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(device(PHONE, Role.OWNER))))
        relay.onDevices = { if (relay.devicesCalls >= 2) relay.devicesRes = Res.Ok(200, DevicesResponse(531, emptyList())) } // 받은 함도 기기 목록도 성공, 폰 없음
        relay.onSocket = { ev ->
            if (relay.socketCalls == 1) {
                ev.onOpen()
                ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
            } else {
                ev.onClosed(-1)
            }
        }
        link.start()
        eventually { dao.phoneLink(PHONE) == null }
        eventually { link.status.value.conn == LinkConn.NONE } // 확인까지 성공했는데 폰이 없으면 그때 닫는다
        assertEquals(null, dao.phoneLink(PHONE))
        assertEquals(1, relay.socketCalls)
    }

    // 닫은 뒤 받은 함을 한 번 더 받는다. 닫을 때 버려진 ack 안 된 paired가 처리되고 폰이 생기면 다시 붙는다
    @Test fun pairedArrivingAfterCheck_recoveredByInboxAfterClose() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        val b =
            relayEnv("paired", 11, """{"device_id":"$GUARD","role":"guardian","platform":"ios","paired_at":"2026-11-05T09:01:44Z","handoff_alerts":false}""")
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) // 붙을 때
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) // 확인: 비어 있다
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(b), false)) // 닫은 뒤: 그사이 온 paired
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(device(PHONE, Role.OWNER))))
        // 확인(2번째)은 폰 없음. 닫은 뒤 paired가 처리되면 중계 목록에도 그 폰이 있다(재접속 때 대조가 새 폰을 지우지 않게)
        relay.onDevices = {
            if (relay.devicesCalls == 2) {
                relay.devicesRes = Res.Ok(200, DevicesResponse(531, emptyList()))
            } else if (relay.devicesCalls >= 3) {
                relay.devicesRes = Res.Ok(200, DevicesResponse(532, listOf(device(GUARD, Role.GUARDIAN))))
            }
        }
        relay.onSocket = { ev ->
            if (relay.socketCalls == 1) {
                ev.onOpen()
                ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
            } else {
                ev.onClosed(-1)
            }
        }
        link.start()
        eventually { dao.phoneLink(GUARD) != null }
        wakingUntil { relay.socketCalls >= 2 } // 폰이 생겨 다시 붙는다
    }

    // 닫은 뒤 받은 함 받기가 실패해도 성공할 때까지 다시 받아 새 폰 paired를 반영하고 다시 붙는다
    @Test fun inboxAfterCloseFails_retriedUntilPairedRecovered() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        val b =
            relayEnv("paired", 11, """{"device_id":"$GUARD","role":"guardian","platform":"ios","paired_at":"2026-11-05T09:01:44Z","handoff_alerts":false}""")
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) // 붙을 때
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) // 확인: 비어 있다
        relay.inboxPages += Res.Offline // 닫은 뒤 첫 받기 실패
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(b), false)) // 다음 시도: 그사이 온 paired
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(device(PHONE, Role.OWNER))))
        relay.onDevices = {
            if (relay.devicesCalls == 2) {
                relay.devicesRes = Res.Ok(200, DevicesResponse(531, emptyList()))
            } else if (relay.devicesCalls >= 3) {
                relay.devicesRes = Res.Ok(200, DevicesResponse(532, listOf(device(GUARD, Role.GUARDIAN))))
            }
        }
        relay.onSocket = { ev ->
            if (relay.socketCalls == 1) {
                ev.onOpen()
                ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
            } else {
                ev.onClosed(-1)
            }
        }
        link.start()
        eventually { dao.phoneLink(GUARD) != null }
        wakingUntil { relay.socketCalls >= 2 }
    }

    // 닫은 뒤 받기가 401(LOST)로 자격이 지워지면 재시도를 끝낸다
    @Test fun lostStopsRetry() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) // 붙을 때
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) // 확인
        relay.inboxPages += Res.Err(401, "unauthorized", null) // 닫은 뒤 받기: 토큰 폐기
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) // 재시도했다면 여기까지 간다
        relay.devicesRes = Res.Ok(200, DevicesResponse(531, emptyList()))
        relay.onSocket = { ev ->
            if (relay.socketCalls == 1) {
                ev.onOpen()
                ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
            } else {
                ev.onClosed(-1)
            }
        }
        link.start()
        eventually { relay.inboxCalls.size >= 3 }
        Thread.sleep(2500) // 백오프(1초대)보다 길게 기다려도 더 받지 않는다
        assertEquals(3, relay.inboxCalls.size)
    }

    // 확인이 실패하면 닫지 않고 백오프로 다시 확인하며, 성공해 폰이 없으면 그때 닫는다
    @Test fun failedCheckRetried_thenCloses() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) // 붙을 때
        relay.inboxPages += Res.Offline // 첫 확인 실패
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(device(PHONE, Role.OWNER))))
        relay.onDevices = { if (relay.devicesCalls >= 2) relay.devicesRes = Res.Ok(200, DevicesResponse(531, emptyList())) }
        relay.onSocket = { ev ->
            if (relay.socketCalls == 1) {
                ev.onOpen()
                ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
            } else {
                ev.onClosed(-1)
            }
        }
        link.start()
        eventually { relay.inboxCalls.size >= 2 }
        assertEquals(LinkConn.CONNECTED, link.status.value.conn) // 실패한 동안은 닫지 않았다
        eventually { link.status.value.conn == LinkConn.NONE } // 재확인(1초 뒤)이 성공해 폰이 없으니 닫는다
        assertTrue(relay.inboxCalls.size >= 3)
    }

    // 확인 도중 방이 바뀌어 새 소켓이 붙었다. 옛 방의 성공 응답으로 새 소켓을 닫지 않고 지금 연결로 다시 확인한다
    @Test fun roomChangedDuringRecheck_newSocketKept() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        val b = relayEnv("paired", 1, """{"device_id":"$GUARD","role":"guardian","platform":"ios","paired_at":"2026-11-05T09:01:44Z","handoff_alerts":false}""")
        repeat(3) { relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) } // 첫 연결, 확인, 새 방 연결
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(b), false)) // 다시 확인: 새 폰 B
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(device(PHONE, Role.OWNER))))
        relay.onDevices = {
            when (relay.devicesCalls) {
                2 -> { // 확인의 기기 목록 응답이 늦는 사이: 다른 요청의 401로 방이 바뀌고 새 연결 화면에서 새 소켓이 붙은 뒤 화면을 닫는다
                    now += 120_000 // 차 등록 간격이 지나 새로 등록할 수 있다
                    link.invalidate()
                    link.openPairing()
                    wakingUntil { relay.socketCalls >= 2 && relay.devicesCalls >= 3 }
                    link.closePairing()
                    relay.devicesRes = Res.Ok(200, DevicesResponse(531, emptyList()))
                }
                4 -> relay.devicesRes = Res.Ok(200, DevicesResponse(532, listOf(device(GUARD, Role.GUARDIAN))))
            }
        }
        relay.onSocket = { ev ->
            if (relay.socketCalls <=
                2
            ) {
                ev.onOpen()
                if (relay.socketCalls == 1) ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
            } else {
                ev.onClosed(-1)
            }
        }
        link.start()
        eventually { dao.phoneLink(GUARD) != null } // 고치기 전: 옛 방의 성공 응답으로 새 소켓을 닫아 B를 받지 못한다
        Thread.sleep(300)
        assertEquals(2, relay.socketCalls)
        assertEquals(LinkConn.CONNECTED, link.status.value.conn)
    }

    // 확인이 도는 중 들어온 확인 요청(폰 해제)을 버리지 않고 끝나기 전에 한 번 더 확인해 닫는다
    @Test fun unpairDuringRecheck_reCheckedAndClosed() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        val b =
            relayEnv("paired", 11, """{"device_id":"$GUARD","role":"guardian","platform":"ios","paired_at":"2026-11-05T09:01:44Z","handoff_alerts":false}""")
        val ub = relayEnv("unpaired", 12, """{"device_id":"$GUARD","role":"guardian","by":"self"}""")
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(device(PHONE, Role.OWNER))))
        relay.onDevices = { if (relay.devicesCalls >= 2) relay.devicesRes = Res.Ok(200, DevicesResponse(531, emptyList())) }
        relay.onInbox = {
            if (relay.inboxCalls.size == 3) { // 첫 확인이 닫은 뒤 다시 받는 중(작업이 아직 돈다): 새 폰 B가 붙어 소켓이 다시 열린 뒤 B가 해제된다
                link.receive(b)
                eventually { relay.socketCalls >= 2 && link.status.value.conn == LinkConn.CONNECTED }
                link.receive(ub)
            }
        }
        relay.onSocket = { ev ->
            ev.onOpen()
            if (relay.socketCalls == 1) ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
        }
        link.start()
        eventually { dao.phoneLink(GUARD) == null && relay.socketCalls >= 2 && relay.inboxCalls.size >= 3 }
        eventually { link.status.value.conn == LinkConn.NONE } // 고치기 전: 요청을 버려 폰이 없는데 소켓이 남는다
        assertEquals(2, relay.socketCalls)
    }

    // 받은 함 확인이 실패한 뒤 기다리는 중 소켓이 끊겨도(live = null) 확인은 성공할 때까지 계속되어 새 폰 B가 반영된다
    @Test fun socketDropDuringRecheck_keepsRetrying() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        val b =
            relayEnv("paired", 11, """{"device_id":"$GUARD","role":"guardian","platform":"ios","paired_at":"2026-11-05T09:01:44Z","handoff_alerts":false}""")
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) // 붙을 때
        relay.inboxPages += Res.Offline // 첫 확인 실패
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(b), false)) // 소켓이 끊긴 뒤 재시도: 새 폰 B
        var first: SocketEvents? = null
        relay.onInbox = {
            if (relay.inboxCalls.size == 2) first?.onClosed(-1) // 실패 뒤 기다리는 중 소켓이 끊긴다
        }
        relay.onSocket =
            { ev ->
                if (relay.socketCalls ==
                    1
                ) {
                    first = ev
                    ev.onOpen()
                    ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
                } else {
                    ev.onClosed(-1)
                }
            }
        link.start()
        eventually { dao.phoneLink(GUARD) != null } // 고치기 전: live == null이라 재시도를 멈춰 B를 받지 못한다
    }

    // 확인 중 예외(SQLiteException 등)가 나도 같은 재시도를 하고, 결국 폰이 없으면 닫는다(실행 표시도 풀린다)
    @Test fun exceptionInRecheck_releasesAndRetries() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(device(PHONE, Role.OWNER))))
        var thrownAt = 0 // 예외를 던진 호출 번호
        relay.onDevices = {
            // 1번째(붙을 때) 뒤에는 unpaired가 온 뒤라 중계 목록에도 폰이 없다
            if (relay.devicesCalls >= 2) relay.devicesRes = Res.Ok(200, DevicesResponse(531, emptyList()))
            // 예외는 마지막 폰 확인 경로에서 한 번만 던진다. 호출 번호로 고르면 연결 반복 쪽 조회에 걸릴 때가 있고,
            // 그러면 연결 반복이 60초 기다려(Connection.startLoop) 시험이 실행 순서에 따라 갈린다
            if (thrownAt == 0 && Thread.currentThread().stackTrace.any { "recheckOnce" in it.className }) {
                thrownAt = relay.devicesCalls
                throw SQLiteException("boom")
            }
        }
        relay.onSocket = { ev ->
            ev.onOpen()
            if (relay.socketCalls == 1) ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
        }
        link.start()
        eventually { thrownAt > 0 && relay.devicesCalls > thrownAt } // 예외 뒤에도 같은 확인을 다시 한다
        eventually { link.status.value.conn == LinkConn.NONE } // 예외로 끝나도 소비 코루틴이 살아 다음 요청이 돈다
    }

    // stop→start를 반복해도 소비 코루틴이 하나뿐이라 확인(조회)이 겹치지 않는다
    @Test fun repeatedStopStart_checksDoNotOverlap() {
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("042917", "2026-11-05T08:17:30Z", "aigyeot://pair?code=042917"))
        repeat(5) {
            link.start()
            link.stop()
        }
        link.start()
        link.openPairing()
        eventually { link.status.value.pairCode == "042917" }
        val live = AtomicInteger()
        val maxLive = AtomicInteger()
        relay.onInbox = {
            val n = live.incrementAndGet()
            maxLive.accumulateAndGet(n) { x, y -> maxOf(x, y) }
            Thread.sleep(150)
            live.decrementAndGet()
        }
        link.closePairing()
        link.openPairing()
        link.closePairing()
        eventually { relay.inboxCalls.isNotEmpty() }
        Thread.sleep(600)
        assertEquals(1, maxLive.get(), "조회가 겹친다")
    }

    // 연결 화면 닫기의 DELETE 응답을 기다리는 사이 stop()이 불리면, 늦게 끝나도 recheck가 새 조회를 만들지 않는다. stop 뒤 start하면 다시 동작한다
    @Test fun stopDuringClosePairingDelete_noRecheckAfterStop() {
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("042917", "2026-11-05T08:17:30Z", "aigyeot://pair?code=042917"))
        link.start()
        link.openPairing()
        eventually { link.status.value.pairCode == "042917" }
        relay.deleteGate = CompletableDeferred()
        link.closePairing()
        eventually { relay.pairLog.lastOrNull() == "delete" }
        link.stop()
        Thread.sleep(200)
        val inbox = relay.inboxCalls.size
        val devices = relay.devicesCalls
        relay.deleteGate!!.complete(Unit)
        eventually { relay.pairLog.lastOrNull() == "deleted" }
        Thread.sleep(800)
        assertEquals(inbox, relay.inboxCalls.size, "멈춘 뒤 받은 함 조회가 이어진다")
        assertEquals(devices, relay.devicesCalls, "멈춘 뒤 기기 목록 조회가 이어진다")
    }

    @Test fun stopThenStart_recheckWorksAgain() {
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("042917", "2026-11-05T08:17:30Z", "aigyeot://pair?code=042917"))
        link.start()
        link.openPairing()
        eventually { link.status.value.pairCode == "042917" }
        link.stop()
        link.start()
        link.closePairing()
        eventually { relay.inboxCalls.isNotEmpty() } // 폰이 없고 화면이 닫혀 마지막 폰 확인(받은 함 조회)이 돈다
    }

    // 코루틴이 시작되기 전에 stop()이 취소해도 stop 뒤 start하면 확인이 다시 돈다
    @Test fun cancelBeforeRecheckStarts_startAgain_recheckWorks() {
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("042917", "2026-11-05T08:17:30Z", "aigyeot://pair?code=042917"))
        link.start()
        link.openPairing()
        eventually { link.status.value.pairCode == "042917" }
        relay.deleteGate = CompletableDeferred()
        link.closePairing()
        eventually { relay.pairLog.lastOrNull() == "delete" }
        relay.onDeleted = { hold.holding = true } // DELETE가 끝난 뒤 recheck의 launch가 시작되기 전에 붙잡는다
        relay.deleteGate!!.complete(Unit)
        eventually { hold.held.isNotEmpty() }
        Thread.sleep(100)
        link.stop()
        hold.release()
        Thread.sleep(300)
        relay.onDeleted = {}
        relay.deleteGate = null
        val inbox = relay.inboxCalls.size
        link.start()
        link.closePairing()
        eventually { relay.inboxCalls.size > inbox } // 소비 코루틴이 새로 떠 확인이 돈다
    }

    // 마지막 폰이 해제되고 연결 화면이 닫힌 뒤 소켓을 닫으면 받은 함·기기 목록 조회가 더 늘지 않는다
    @Test fun lastPhoneGone_recheckStopsPolling() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(device(PHONE, Role.OWNER))))
        relay.onDevices = { if (relay.devicesCalls >= 2) relay.devicesRes = Res.Ok(200, DevicesResponse(531, emptyList())) }
        relay.onSocket =
            { ev ->
                if (relay.socketCalls == 1) {
                    ev.onOpen()
                    ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
                } else {
                    ev.onClosed(-1)
                }
            }
        link.start()
        eventually { link.status.value.conn == LinkConn.NONE }
        Thread.sleep(300)
        val inbox = relay.inboxCalls.size
        val devices = relay.devicesCalls
        Thread.sleep(2500) // 백오프보다 길게 기다려도 더 부르지 않는다
        assertEquals(inbox, relay.inboxCalls.size)
        assertEquals(devices, relay.devicesCalls)
    }

    // 마지막 폰 확인 중 대조가 폰을 실제로 지우면(LinkChanged) 한 번 더 확인하고 멈춘다
    @Test fun reconcileDeletesPhone_oneMoreCheckThenStops() {
        profile()
        phone()
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, emptyList()))
        relay.onSocket = { ev -> if (relay.socketCalls == 1) ev.onOpen() else ev.onClosed(-1) }
        link.start()
        eventually { dao.phoneLink(PHONE) == null && link.status.value.conn == LinkConn.NONE }
        Thread.sleep(300)
        val devices = relay.devicesCalls
        assertTrue(devices >= 2, "지운 뒤 한 번 더 확인한다")
        Thread.sleep(2500)
        assertEquals(devices, relay.devicesCalls)
    }

    // 마지막 접속 시각만 바뀐 대조는 화면 상태(lastSeenAt)를 고친다
    @Test fun lastSeenOnlyReconcile_refreshesStatus() {
        profile()
        phone()
        fun dev(seen: String) = DeviceInfo(PHONE, Role.OWNER, Platform.IOS, "2026-11-05T09:01:44Z", seen, false, false)
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(dev("2026-11-05T09:02:00Z"))))
        relay.onSocket = { it.onOpen() }
        link.start()
        eventually { link.status.value.conn == LinkConn.CONNECTED && relay.devicesCalls >= 1 && link.status.value.phones.isNotEmpty() }
        val before = link.status.value.phones.single().lastSeenAt
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(dev("2026-11-05T09:30:00Z"))))
        kotlinx.coroutines.runBlocking { link.reconcileDevices(link.credential()!!) }
        eventually { link.status.value.phones.single().lastSeenAt != before }
        assertEquals(LinkConn.CONNECTED, link.status.value.conn)
    }

    private fun device(id: String, role: Role) = DeviceInfo(id, role, Platform.IOS, "2026-11-05T09:01:44Z", "2026-11-05T09:02:00Z", false, false)

    private fun storedCred() = dao.insertCredential(RelayCredentialEntity("c_TESTCAR", "d_TESTCAR", "sealed:at1.c_TESTCAR.d_TESTCAR.EXAMPLE"))
    private val pairedB get() = relayEnv(
        "paired",
        11,
        """{"device_id":"$GUARD","role":"guardian","platform":"ios","paired_at":"2026-11-05T09:01:44Z","handoff_alerts":false}"""
    )
    private fun devs(vararg ids: String) = Res.Ok(200, DevicesResponse(530, ids.map { device(it, Role.GUARDIAN) }))

    // 확인이 실패해 재시도하는 중 stop()→start()해도 확인이 다시 시작되어, 화면 조작 없이 새 폰 B가 반영되고 소켓이 붙는다
    @Test fun stopStartDuringRetry_resumesAndNewPhoneReflected() {
        profile()
        storedCred()
        relay.inboxPages += Res.Offline
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(pairedB), false))
        relay.devicesRes = devs(GUARD)
        relay.onSocket = { it.onOpen() }
        link.start()
        eventually { relay.inboxCalls.size == 1 } // 첫 확인 실패, 1초 기다리는 중
        link.stop()
        link.start()
        eventually { dao.phoneLink(GUARD) != null } // 고치기 전: 신호가 사라져 확인이 다시 돌지 않는다
        eventually { link.status.value.conn == LinkConn.CONNECTED }
    }

    // 시작 때 자격 있음·폰 없음 → 확인 한 번. 소켓이 없어도(live == null) 닫기 뒤 마지막 받은 함 확인까지 하고(받은 함 2번) 멈춘다
    @Test fun startWithCredNoPhone_oneCheckThenStops_finalInboxPassFindsNewPhone() {
        profile()
        storedCred()
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false))
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(pairedB), false)) // 닫으며 버려진 프레임 자리
        relay.devicesRes = devs()
        relay.onDevices = { if (relay.devicesCalls >= 2) relay.devicesRes = devs(GUARD) }
        relay.onSocket = { it.onOpen() }
        link.start()
        eventually { dao.phoneLink(GUARD) != null } // 고치기 전: live == null이라 마지막 받은 함 확인을 건너뛴다
    }

    @Test fun startWithCredNoPhone_checksOnceThenStopsPolling() {
        profile()
        storedCred()
        relay.devicesRes = devs()
        link.start()
        eventually { link.status.value.conn == LinkConn.NONE && relay.devicesCalls >= 1 && relay.inboxCalls.size >= 2 }
        Thread.sleep(300)
        val inbox = relay.inboxCalls.size
        val devices = relay.devicesCalls
        Thread.sleep(2500)
        assertEquals(inbox, relay.inboxCalls.size)
        assertEquals(devices, relay.devicesCalls)
        assertEquals(0, relay.socketCalls)
    }

    @Test fun startWithoutCred_orWithPhone_noRecheck() {
        profile()
        link.start()
        Thread.sleep(500)
        assertEquals(0, relay.inboxCalls.size)
        assertEquals(0, relay.registers)
        link.stop()
        storedCred()
        phone()
        link.start()
        Thread.sleep(500)
        assertTrue(relay.socketCalls >= 1) // 폰이 있으면 연결 반복이 맡는다. 소켓은 열리지 않아 연결 반복의 조회(3번 실패 뒤)는 아직 없다
        assertEquals(0, relay.inboxCalls.size, "시작 확인이 돌았다")
        assertEquals(0, relay.devicesCalls, "시작 확인이 돌았다")
    }

    // 확인 밖의 예외(Room이 잠깐 깨짐)로 빠져나가도 확인을 끝까지 한다
    @Test fun exceptionOutsideChecks_retriesUntilDone() {
        profile()
        storedCred()
        relay.inboxPages += Res.Offline
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(pairedB), false))
        relay.devicesRes = devs(GUARD)
        relay.onSocket = { it.onOpen() }
        relay.onInbox = {
            if (relay.inboxCalls.size == 1) db.openHelper.writableDatabase.execSQL("ALTER TABLE phone_link RENAME TO phone_link_x")
        } // 실패 뒤 기다림이 끝나면 wantSocket()이 예외
        link.start()
        eventually { relay.inboxCalls.size == 1 }
        Thread.sleep(1_500)
        db.openHelper.writableDatabase.execSQL("ALTER TABLE phone_link_x RENAME TO phone_link")
        eventually { dao.phoneLink(GUARD) != null } // 고치기 전: 예외로 확인이 사라진다
    }

    // 시작 때 첫 자격 읽기가 예외여도 기다림 뒤 다시 읽어 확인한다
    @Test fun firstCredRead_throws_retriesAndNewPhoneReflected() {
        profile()
        storedCred()
        relay.inboxPages += Res.Ok(200, InboxResponse(listOf(pairedB), false))
        relay.devicesRes = devs(GUARD)
        relay.onSocket = { it.onOpen() }
        db.openHelper.writableDatabase.execSQL("ALTER TABLE relay_credential RENAME TO relay_credential_x")
        link.start()
        Thread.sleep(500)
        assertEquals(0, relay.inboxCalls.size)
        db.openHelper.writableDatabase.execSQL("ALTER TABLE relay_credential_x RENAME TO relay_credential")
        eventually { dao.phoneLink(GUARD) != null } // 고치기 전: 첫 읽기 예외로 확인이 사라진다
    }

    @Test fun recheckFetchFails_keepsSocket_newPairedProcessed() {
        profile()
        phone()
        val a = relayEnv("unpaired", 10, """{"device_id":"$PHONE","role":"owner","by":"self"}""")
        val b =
            relayEnv("paired", 11, """{"device_id":"$GUARD","role":"guardian","platform":"ios","paired_at":"2026-11-05T09:01:44Z","handoff_alerts":false}""")
        relay.inboxPages += Res.Ok(200, InboxResponse(emptyList(), false)) // 붙을 때 받은 함
        relay.inboxPages += Res.Offline // recheck의 받은 함 요청은 실패한다
        relay.onSocket = { ev ->
            if (relay.socketCalls == 1) {
                ev.onOpen()
                ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), a))
                ev.onText(ProtocolJson.encodeToString(Envelope.serializer(), b))
            } else {
                ev.onClosed(-1)
            }
        }
        link.start()
        eventually { dao.phoneLink(GUARD) != null } // 고치기 전: 소켓을 닫아 B 프레임이 버려진다
        assertEquals(1, relay.socketCalls) // 닫고 다시 붙지도 않았다
    }

    @Test fun invalid4001_waitsBeforeReRegister_noChain() {
        phone()
        relay.onSocket = { it.onClosed(4001) }
        link.openPairing() // 연결 화면이 열려 있어야 무효 뒤에도 소켓을 원한다(연쇄가 나는 조건)
        link.start()
        eventually { relay.registers >= 1 && relay.socketCalls >= 1 }
        Thread.sleep(1_000) // wake 없이: 기다림 간격을 둔다(예전에는 곧바로 재등록이 연쇄했다)
        assertTrue(relay.registers <= 4, "registers=${relay.registers}") // 연결 반복·연결 코드 반복이 각각 1초 기다림
    }

    @Test fun socketUnreadableFrame_withId_isAcked_withoutId_isNot() {
        profile()
        phone()
        val id = "01JC8Y2K6N3T5Q9W0ZB4H7X2D9"
        relay.onSocket = { ev ->
            if (relay.socketCalls == 1) {
                ev.onOpen()
                ev.onText("""{"v":"x","id":"$id","seq":7}""")
                ev.onText("""{"v":1,"seq":8}""")
            } else {
                ev.onClosed(-1)
            }
        }
        link.start()
        eventually { relay.socketSent.any { id in it } }
        assertEquals(1, relay.socketSent.count { it.contains("\"ack\"") }) // 빈 id는 ack하지 않는다
    }
}
