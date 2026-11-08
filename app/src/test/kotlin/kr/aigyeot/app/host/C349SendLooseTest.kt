package kr.aigyeot.app.host

import kr.aigyeot.engine.*
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// C349: Step 밖 할 일(장면 끝 SceneEnd.commands)도 route()대로 실행기·ui에 나눈다. HideNotice가 실행기로 새면 알림이 안 내려간다.
@RunWith(RobolectricTestRunner::class)
class C349SendLooseTest {
    private val k = Kit()
    @After fun close() { k.host.close(); k.db.close(); k.linkScope.cancel() }

    @Test fun c349_sendLoose_routesHideNoticeToUi() {
        k.profile(); k.phone(); k.boot()
        val router = CommandRouter(k.store, k.clock, Params(), { k.host.targets }, { false }, { throw it })
        k.exec.clear(); k.hiddenNotices.clear()
        router.sendLoose(listOf(HideNotice(Notice.FEEDING_CONFIRM), StopPlayback("p1", false), CancelSpeech))
        assertEquals(listOf(Notice.FEEDING_CONFIRM), k.hiddenNotices)
        assertEquals(listOf(StopPlayback("p1", false), CancelSpeech), k.exec)
        assertTrue(k.exec.none { it is HideNotice }, "HideNotice는 실행기로 가지 않는다")
    }

    @Test fun c349_sceneEnd_hideNoticeGoesToUi() {
        k.profile(); k.phone(); k.boot()
        k.host.startScenario(ScenarioSetup(
            "A", 7, Profile("2026-03", CarSeat.LEFT, null, false), listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)),
            emptyList(), null, 0, emptySet(),
        )); k.host.awaitIdle()
        k.open()
        k.ev(RouteState(k.now, false, null, listOf(RestCandidate("n", "n 휴게소", RestKind.REST_AREA, 9.0, true)), null))
        k.ev(DriverAddWaypoint(k.now, "n"))
        val add = k.exec.filterIsInstance<AddWaypoint>().single()
        k.ev(WaypointResult(k.now + 500, add.cmdId, "n", true), k.now + 500)
        k.ev(GearChanged(k.now + 60_000, Gear.P), k.now + 60_000)
        k.ev(Tick(k.now + 15 * 60_000), k.now + 15 * 60_000)
        assertTrue(k.state().ign.nursingStop!!.confirmShown, "조건: 장면 상태에 수유 확인이 떠 있다")
        k.hiddenNotices.clear(); k.exec.clear()
        k.host.endScenario(); k.host.awaitIdle()
        assertTrue(Notice.FEEDING_CONFIRM in k.hiddenNotices)
        assertTrue(k.exec.none { it is HideNotice })
    }
}
