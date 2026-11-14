package kr.aigyeot.app.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kr.aigyeot.app.common.instantOf
import kr.aigyeot.app.link.db.PhoneLinkMarkEntity
import kr.aigyeot.app.link.db.ProcessedMessageEntity
import kr.aigyeot.app.link.db.RelayCredentialEntity
import kr.aigyeot.app.link.db.SongDownloadEntity
import kr.aigyeot.app.link.db.SongSelectionEntity
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.FeedingFrom
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.LinkChanged
import kr.aigyeot.engine.fresh
import kr.aigyeot.shared.DeviceInfo
import kr.aigyeot.shared.DevicesResponse
import kr.aigyeot.shared.PairCodeResponse
import kr.aigyeot.shared.Platform
import kr.aigyeot.shared.Role
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 자격, 기기 목록 대조, 차 토큰 무효·재등록 순번, 연결 화면, 복원 뒤 대조.
@RunWith(RobolectricTestRunner::class)
class LinkConnectTest : LinkKit() {
    private val SECOND = "d_2ND0000000000000000000000C"
    private fun dev(id: String, role: Role, alerts: Boolean? = null, seen: String? = "2026-11-05T08:20:00Z") =
        DeviceInfo(id, role, if (role == Role.CAR) Platform.PLEOS else Platform.ANDROID, "2026-11-05T08:13:02Z", seen, false, alerts)

    @Test fun credential_registersOnce_sealsToken() {
        runBlocking { link.credential(); link.credential() }
        assertEquals(1, relay.registers)
        assertEquals("sealed:at1.c_TESTCAR.d_TESTCAR.EXAMPLE", dao.credential()!!.tokenEncrypted)   // 평문 토큰을 저장하지 않는다
        assertTrue(link.status.value.registered)
    }

    @Test fun credential_unreadable_treatedAsInvalid_andReRegisters() {
        dao.insertCredential(RelayCredentialEntity("c_OLD", "d_OLD", "garbage"))
        phone()
        val c = runBlocking { link.credential() }
        assertEquals("c_TESTCAR", c!!.carId)
        assertEquals(1, relay.registers)
        assertTrue(dao.phoneLinks().isEmpty())   // 옛 방의 연결은 쓸 수 없다
    }

    @Test fun invalidate_dropsOldRoomSeqs_movesActiveSongsBelowOne() {
        runBlocking { link.credential() }
        phone(); dao.putMark(PhoneLinkMarkEntity(PHONE, 300, false, T0))
        dao.insertProcessed(ProcessedMessageEntity("01JC8Y2K6N3T5Q9W0ZB4H7X2DM", 300, T0))
        song(SA, ACTIVE, 30); song(SB, ACTIVE, 10); song(SC, PENDING, 40); song(SD, DELETED, 20, last = 41)
        dao.putDownload(SongDownloadEntity(SC, 40, 0, 0))
        dao.insertSelection(SongSelectionEntity(SA, 50))
        runBlocking { link.invalidate() }
        assertNull(dao.credential())
        assertTrue(dao.phoneLinks().isEmpty()); assertNull(dao.mark(PHONE))
        assertFalse(dao.processed("01JC8Y2K6N3T5Q9W0ZB4H7X2DM")); assertTrue(dao.downloads().isEmpty())
        assertEquals(setOf(SA, SB), dao.songs().map { it.id }.toSet())          // ACTIVE만 남는다
        assertEquals(-2L, dao.song(SB)!!.addedSeq); assertEquals(-1L, dao.song(SA)!!.addedSeq)   // 오래된 곡부터 −n…−1
        assertEquals(-1L, dao.song(SA)!!.lastSeq)
        assertEquals(SongSelectionEntity(SA, 0), dao.selection())
        assertEquals(LinkChanged(T0, 0), events.last())
        assertFalse(link.status.value.registered)
        // 새 방의 seq 1 선택이 옛 선택을 이긴다
        runBlocking { link.receive(env("song_selected", 1, """{"song_id":"$SB"}""")) }
        assertEquals(SB, dao.selection()!!.songId)
    }

    @Test fun reconcile_bySnapshotSeq() {
        runBlocking { link.credential() }
        profile()
        phone("d_OLD0000000000000000000000A"); dao.putMark(PhoneLinkMarkEntity("d_OLD0000000000000000000000A", 100, false, T0))   // 목록에 없음, 낡음 → 지움
        phone("d_NEW0000000000000000000000B"); dao.putMark(PhoneLinkMarkEntity("d_NEW0000000000000000000000B", 600, false, T0))   // 목록에 없지만 스냅숏보다 새로 붙음 → 둔다
        dao.putMark(PhoneLinkMarkEntity(GUARD, 400, true, T0))                                                                     // 끊김 표시 → 건너뜀
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(dev("d_TESTCAR", Role.CAR), dev(PHONE, Role.OWNER, false), dev(GUARD, Role.GUARDIAN, true))))
        runBlocking { link.reconcileDevices(link.credential()!!) }
        assertEquals(setOf(PHONE, "d_NEW0000000000000000000000B"), dao.phoneLinks().map { it.id }.toSet())
        assertTrue(dao.mark("d_OLD0000000000000000000000A")!!.unpaired)
        assertEquals(530L, dao.mark(PHONE)!!.lastSeq)
        assertEquals(instantOf("2026-11-05T08:20:00Z"), dao.phoneLink(PHONE)!!.lastSeenAt)
        assertEquals(1, outbox("feeding_state").size)                 // 새 owner에게 보낸다
        assertEquals(LinkChanged(T0, 0), events.single())
        assertNull(link.status.value.justPaired)                       // 대조로는 "연결됐어요"를 띄우지 않는다
        // 스냅숏 이하의 paired는 나중에 와도 무시된다(4단계)
        runBlocking { link.receive(relayEnv("paired", 520, """{"device_id":"$PHONE","role":"owner","platform":"android","paired_at":"2026-11-05T08:13:02Z","handoff_alerts":true}""")) }
        assertFalse(dao.phoneLink(PHONE)!!.handoffAlerts)
    }

    @Test fun reconcile_onlyLastSeenChanged_noLinkChanged() {
        runBlocking { link.credential() }
        phone(); dao.putMark(PhoneLinkMarkEntity(PHONE, 600, false, T0))
        relay.devicesRes = Res.Ok(200, DevicesResponse(530, listOf(dev(PHONE, Role.OWNER, false, seen = "2026-11-05T09:00:00Z"))))
        runBlocking { link.reconcileDevices(link.credential()!!) }
        assertEquals(instantOf("2026-11-05T09:00:00Z"), dao.phoneLink(PHONE)!!.lastSeenAt)
        assertTrue(events.isEmpty())
    }

    @Test fun disconnect_deletesLinkAndMarksUnpaired() {
        phone(GUARD, "guardian", alerts = true)
        link.disconnect(GUARD)
        eventually { dao.phoneLink(GUARD) == null }
        eventually { events.isNotEmpty() }
        assertEquals(listOf(GUARD), relay.deletedDevices)
        assertTrue(dao.mark(GUARD)!!.unpaired)
        assertEquals(LinkChanged(T0, 0), events.last())
    }

    @Test fun pairing_showsCode_newCodeAfterOwnerPairs_clearedOnClose() {
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("042917", "2026-11-05T08:17:30Z", "aigyeot://pair?code=042917"))
        link.openPairing()
        eventually { link.status.value.pairCode == "042917" }
        assertEquals("aigyeot://pair?code=042917", link.status.value.pairUri)
        // 연결 코드로 owner가 붙으면 화면이 열린 동안 바로 새 코드
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("551234", "2026-11-05T08:22:30Z", "aigyeot://pair?code=551234"))
        runBlocking { link.receive(relayEnv("paired", 410, """{"device_id":"$PHONE","role":"owner","platform":"android","paired_at":"2026-11-05T08:13:02Z","handoff_alerts":false}""")) }
        eventually { link.status.value.pairCode == "551234" }
        // 기기 목록 대조가 실시간 paired보다 먼저 새 owner를 만들어도 새 코드
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("660000", "2026-11-05T08:27:30Z", "aigyeot://pair?code=660000"))
        relay.devicesRes = Res.Ok(200, DevicesResponse(900, listOf(dev(PHONE, Role.OWNER, false), dev(SECOND, Role.OWNER, false))))
        link.refreshPhones()
        eventually { link.status.value.pairCode == "660000" }
        assertEquals(PHONE, link.status.value.justPaired)   // 대조로 생긴 폰에는 "연결됐어요"를 띄우지 않는다
        link.closePairing()
        assertNull(link.status.value.pairCode)
    }

    @Test fun invalidate_clearsDeadPairCode_thenNewCodeAfterReRegister() {
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("042917", "2026-11-05T08:17:30Z", "aigyeot://pair?code=042917"))
        link.openPairing()
        eventually { link.status.value.pairCode == "042917" }
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("551234", "2026-11-05T08:22:30Z", "aigyeot://pair?code=551234"))
        runBlocking { link.invalidate() }
        assertNull(link.status.value.pairCode)           // 옛 방의 코드는 곧 내려간다
        now += 1_000                                     // 등록 간격
        eventually { link.status.value.pairCode == "551234" }
    }

    @Test fun reconcile_listFromOldRoom_afterInvalidate_notApplied() {
        relay.devicesRes = Res.Ok(200, DevicesResponse(900, listOf(dev(PHONE, Role.OWNER, false))))
        relay.onDevices = { link.invalidate() }   // 목록을 받는 사이 다른 길의 401로 방이 바뀐다
        val c = runBlocking { link.credential() }!!
        runBlocking { link.reconcileDevices(c) }
        assertTrue(dao.phoneLinks().isEmpty())   // 옛 방의 폰을 새 방에 만들지 않는다
        assertNull(dao.mark(PHONE))
    }

    @Test fun invalidate_lateOldToken401_afterReRegister_keepsNewRoom() {
        val old = runBlocking { link.credential() }!!
        runBlocking { link.invalidate(old) }
        now += 1_000
        val fresh = runBlocking { link.credential() }!!
        phone()
        runBlocking { link.invalidate(old) }   // 옛 토큰 요청의 늦은 401
        assertTrue(dao.credential() != null)
        assertEquals(1, dao.phoneLinks().size)
        runBlocking { link.invalidate(fresh) }
        assertNull(dao.credential())
        assertTrue(dao.phoneLinks().isEmpty())
    }

    @Test fun pairing_codeFromOldRoom_afterInvalidate_notShown() {
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("042917", "2026-11-05T08:17:30Z", "aigyeot://pair?code=042917"))
        relay.onPairCode = { relay.onPairCode = {}; link.invalidate() }   // 코드를 받는 사이 방이 바뀐다
        link.openPairing()
        eventually { relay.pairLog.size == 1 }
        Thread.sleep(100)
        assertNull(link.status.value.pairCode)
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("551234", "2026-11-05T08:22:30Z", "aigyeot://pair?code=551234"))
        now += 1_000   // 등록 간격 뒤 새 방에서 다시 받는다
        eventually { link.status.value.pairCode == "551234" }
    }

    @Test fun pairing_reconcile401AfterCode_newCodeWithoutWaitingForExpiry() {
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("042917", "2026-11-05T08:17:30Z", "aigyeot://pair?code=042917"))
        relay.devicesRes = Res.Err(401, "unauthorized", null)   // 코드를 받은 직후 첫 기기 목록 대조가 401
        link.openPairing()
        eventually { relay.devicesCalls == 1 }
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("551234", "2026-11-05T08:22:30Z", "aigyeot://pair?code=551234"))
        now += 1_000   // 등록 간격 뒤
        eventually { link.status.value.pairCode == "551234" }   // 옛 코드 만료(1년 뒤)까지 비워 두지 않는다
    }

    @Test fun pairing_reopenWhileDeleting_newCodeOnlyAfterDelete() {
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("042917", "2026-11-05T08:17:30Z", "aigyeot://pair?code=042917"))
        link.openPairing()
        eventually { link.status.value.pairCode == "042917" }
        relay.deleteGate = CompletableDeferred()
        link.closePairing()
        eventually { relay.pairLog.lastOrNull() == "delete" }   // DELETE가 중계로 가는 중
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("551234", "2026-11-05T08:22:30Z", "aigyeot://pair?code=551234"))
        link.openPairing()
        Thread.sleep(100)
        assertEquals(listOf("code", "delete"), relay.pairLog.toList())   // 늦은 DELETE가 새 코드를 지우지 않게 그 뒤에 받는다
        relay.deleteGate!!.complete(Unit)
        eventually { link.status.value.pairCode == "551234" }
        assertEquals(listOf("code", "delete", "deleted", "code"), relay.pairLog.toList())
    }

    @Test fun pairing_serviceRestartWhileOpen_resumesCodeRefresh() {
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("042917", "2026-11-05T08:17:30Z", "aigyeot://pair?code=042917"))
        link.start(); link.openPairing()
        eventually { link.status.value.pairCode == "042917" }
        link.stop()
        relay.pairCodeRes = Res.Ok(201, PairCodeResponse("551234", "2026-11-05T08:22:30Z", "aigyeot://pair?code=551234"))
        link.start()
        eventually { link.status.value.pairCode == "551234" }
        link.stop()
    }

    @Test fun restoreEvents_onlyWhenRoomDiffersFromSnapshot() {
        profile(lastFeeding = T0 - 60_000)
        phone(GUARD, "guardian", alerts = true)
        val snap = EngineState.fresh(1, null, emptyList(), emptyList(), T0 - 120_000, 0)
        assertEquals(
            listOf(FeedingStarted(T0, T0 - 60_000, FeedingFrom.PHONE, null), LinkChanged(T0, 1)),
            runBlocking { link.restoreEvents(snap, T0) },
        )
        val same = EngineState.fresh(1, null, emptyList(), emptyList(), T0 - 60_000, 1)
        assertTrue(runBlocking { link.restoreEvents(same, T0) }.isEmpty())
    }
}
