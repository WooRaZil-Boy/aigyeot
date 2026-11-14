package kr.aigyeot.app.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kr.aigyeot.app.exec.RouteFeed
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.HandoffCause
import kr.aigyeot.engine.HandoffKind
import kr.aigyeot.engine.HandoffLedger
import kr.aigyeot.engine.HandoffRecord
import kr.aigyeot.engine.Line
import kr.aigyeot.engine.RearDoor
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Side
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.Tick
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 지운 세션에 늦게 오는 쓰기(닫힌 인계의 SaveEpisode 등)는 store 입구에서 버려져 프로세스를 끝내지 않는다.
@RunWith(RobolectricTestRunner::class)
class ClearedWritesTest {
    private val k = Kit()

    @After fun close() {
        k.host.close()
        k.linkScope.cancel()
        k.db.close()
    }

    private fun n(t: String) = k.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $t").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test fun closedHandoffOfClearedSession_restoreFlushDoesNotKillProcess_noRowRevived() {
        k.profile()
        k.phone("guardian", alerts = true)
        k.boot()
        k.open()
        k.alarm()
        k.toGap()
        val s = k.state()
        val ses = s.session!!
        val ep = ses.episode!!
        val closed = HandoffLedger(ses.id, ep.id, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.WAYPOINT_OFFER, null, utteranceId = "u1")
        k.store.saveSnapshot(
            s.copy(
                session = null,
                handoffs = listOf(closed),
                speech = s.speech.copy(speaking = "u1", queue = listOf(Line(Sid.S07, emptyMap(), "n", queuedAt = k.now, sessionId = ses.id, handoff = true)))
            )
        )
        // 기록 지우기: 세션·에피소드 행이 없다(스냅숏만 닫힌 인계를 들고 있다)
        k.store.dao.clearContext()
        k.store.dao.clearPlays()
        k.store.dao.clearAlarms()
        k.store.dao.clearSteer()
        k.store.dao.clearEpisodes()
        k.store.dao.clearSessions()
        k.running = null
        k.exec.clear()
        k.link.clear()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        var died: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, x -> died = x }
        try {
            k.boot()
            k.host.awaitIdle()
            Thread.sleep(200)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old)
        }
        assertNull(died, "외래키 오류로 프로세스가 끝나지 않는다")
        assertEquals(0, n("session"))
        assertEquals(0, n("episode"))
        assertTrue(k.state().handoffs.isEmpty(), "닫힌 인계는 정리된다")
    }

    // 실제 길: 지운 세션(recordsCleared)의 인계를 말하는 중 P로 세션이 닫히고, 복원 없이 늦은 SpeechDone이 온다
    @Test fun liveLateSpeechDoneOfClosedClearedSession_noDeath_noRowRevived() {
        k.phone("guardian", alerts = true)
        k.profile()
        k.boot()
        k.open()
        k.alarm()
        k.toGap()
        val s = k.state()
        val ses = s.session!!
        val ep = ses.episode!!
        val line = Line(Sid.S08, emptyMap(), null, queuedAt = k.now, sessionId = ses.id, handoff = true)
        k.store.saveSnapshot(
            s.copy(
                speech = s.speech.copy(speaking = null, queue = listOf(line)),
                session = ses.copy(recordsCleared = true, episode = ep.copy(handoff = HandoffRecord(HandoffCause.TWO_ATTEMPTS, HandoffKind.NO_REST))),
                handoffs = listOf(HandoffLedger(ses.id, ep.id, k.now, HandoffCause.TWO_ATTEMPTS, HandoffKind.NO_REST, null, alert = true))
            )
        )
        k.running = null
        k.exec.clear()
        k.link.clear()
        k.boot()
        // 기록 지우기로 행이 없다(스냅숏의 recordsCleared 세션이 이어진다)
        k.store.dao.clearContext()
        k.store.dao.clearPlays()
        k.store.dao.clearAlarms()
        k.store.dao.clearSteer()
        k.store.dao.clearEpisodes()
        k.store.dao.clearSessions()
        k.ev(Tick(0), k.now + 10_000)
        val u = k.exec.filterIsInstance<Speak>().single { it.sid == Sid.S08 }.utteranceId
        k.ev(GearChanged(0, Gear.P), k.now + 1_000).ev(RearDoor(0, Side.LEFT, true), k.now + 500) // P + 뒷문 열림 = 세션 닫힘
        assertNull(k.state().session, "전제: 세션이 닫혔다")
        assertEquals(1, k.state().handoffs.size, "전제: 닫힌 인계가 말하는 중이다")
        val old = Thread.getDefaultUncaughtExceptionHandler()
        var died: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, x -> died = x }
        try {
            k.ev(SpeechDone(0, u, true), k.now + 5_000)
            Thread.sleep(200)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old)
        }
        assertNull(died, "외래키 오류로 프로세스가 끝나지 않는다")
        assertEquals(0, n("session"))
        assertEquals(0, n("episode"))
        assertTrue(k.state().handoffs.isEmpty(), "닫힌 인계는 정리된다")
        assertEquals(1, k.link.filterIsInstance<SendToPhone>().count { it.type == "handoff_alert" }, "알림 장부(보호자 알림)는 기록 지움과 상관없이 한 번 나간다")
    }

    // 끝난 경로 A의 TBT가 새 경로 B의 시간·거리와 합쳐져 출발 제안이 A 휴게소를 묻고 확정하지 않는다
    @Test fun departOffer_endedRouteRestNotAsked_thenNewRouteListDecides() {
        k.profile(interval = 180)
        k.db.storeDao().upsertProfile(k.db.storeDao().profile()!!.copy(lastFeedingStart = T0 - 120 * 60_000L))
        k.boot()
        k.open()
        var asked = 0
        val f = RouteFeed({ k.now }, { k.ev(it, k.now + 1_000) }, { asked++ })
        val a = listOf(RestCandidate("tbt:rest:A휴게소", "A휴게소", RestKind.REST_AREA, 30.0, true))
        val b = listOf(RestCandidate("tbt:rest:B휴게소", "B휴게소", RestKind.REST_AREA, 30.0, true))
        f.routing(false) // FREE_DRIVE
        f.rests(a) // 내비가 끝난 경로 A의 TBT를 다시 준다
        f.routing(true)
        f.driving(9000, 150_000) // 새 경로 B
        assertEquals(1, asked, "지금 경로 TBT를 다시 요청")
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S07_DEPART }, "A 휴게소를 묻지 않는다")
        assertEquals(false, k.ses().departChecked, "목록이 빈 동안은 기회를 쓰지 않는다")
        f.rests(b) // 다시 요청한 응답
        val q = k.state().speech.queue.single { it.sid == Sid.S07_DEPART }
        assertEquals("tbt:rest:B휴게소", q.askRestId, "B 기준으로 판단한다")
        assertTrue(k.ses().departChecked)
    }

    // A 종료 → A의 늦은 DrivingInfo → B 시작 → 옛 조회의 A 목록 → B 목록. 출발 제안은 B 값으로만 판단한다
    @Test fun departOffer_oldRouteValuesAndLateListIgnored() {
        k.profile(interval = 180)
        k.db.storeDao().upsertProfile(k.db.storeDao().profile()!!.copy(lastFeedingStart = T0 - 120 * 60_000L))
        k.boot()
        k.open()
        val f = RouteFeed({ k.now }, { k.ev(it, k.now + 1_000) }, {})
        val a = listOf(RestCandidate("tbt:rest:A휴게소", "A휴게소", RestKind.REST_AREA, 30.0, true))
        val b = listOf(RestCandidate("tbt:rest:B휴게소", "B휴게소", RestKind.REST_AREA, 30.0, true))
        f.routing(true)
        f.driving(300, 20_000)
        f.routing(false)
        f.driving(600, 10_000)
        f.routing(true)
        f.rests(a, 10.0) // 옛 조회의 늦은 응답
        assertTrue(k.state().speech.queue.none { it.sid == Sid.S07_DEPART })
        assertEquals(false, k.ses().departChecked, "옛 값으로 기회를 쓰지 않는다")
        f.driving(9000, 150_000)
        f.rests(a, 10.0)
        assertEquals(false, k.ses().departChecked)
        f.rests(b, 149.0)
        assertEquals("tbt:rest:B휴게소", k.state().speech.queue.single { it.sid == Sid.S07_DEPART }.askRestId)
    }

    // 2시간을 넘긴 뒤 안내가 시작돼 쉼터 목록을 다시 받는 동안 '쉼터를 못 찾았어요'(S10)를 말하지 않는다
    @Test fun longDrive_waitsForReRequestedRests_thenS11() {
        k.profile()
        k.boot()
        k.open() // 복원 뒤 60초 기다림은 2시간 전에 끝난다
        var asked = 0
        val f = RouteFeed({ k.now }, { k.ev(it, k.now) }, { asked++ })
        val b = listOf(RestCandidate("tbt:rest:B휴게소", "B휴게소", RestKind.REST_AREA, 12.0, false))
        k.now = T0 + 119 * 60_000L
        f.routing(false) // 앞 경로 끝(FREE_DRIVE)
        f.rests(b) // 새 경로 TBT가 상태보다 먼저 온다(안내 중이 되면 버린다)
        k.now = T0 + 121 * 60_000L
        f.routing(true)
        f.driving(9000, 150_000) // 2시간을 넘긴 뒤 안내 시작
        assertEquals(1, asked, "지금 경로 TBT를 다시 요청")
        assertTrue(k.exec.none { it is Speak && it.sid == Sid.S10 }, "목록을 기다리는 동안 쉼터 없음으로 보지 않는다")
        assertTrue(k.state().speech.queue.none { it.longDrive })
        f.rests(b) // 다시 요청한 응답
        assertEquals(Sid.S11, k.exec.filterIsInstance<Ask>().single().sid)
        assertTrue(k.exec.none { it is Speak && it.sid == Sid.S10 })
    }
}
