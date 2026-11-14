package kr.aigyeot.app.host

import kotlinx.coroutines.cancel
import kr.aigyeot.engine.AddWaypoint
import kr.aigyeot.engine.CancelSpeech
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.DriverAddWaypoint
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.HideNotice
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Notice
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.RestKind
import kr.aigyeot.engine.RouteState
import kr.aigyeot.engine.StopPlayback
import kr.aigyeot.engine.Tick
import kr.aigyeot.engine.WaypointResult
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Step 밖 할 일(장면 끝 SceneEnd.commands)도 route()대로 실행기·ui에 나눈다. HideNotice가 실행기로 새면 알림이 안 내려간다.
@RunWith(RobolectricTestRunner::class)
class SendLooseTest {
    private val k = Kit()
    @After fun close() { k.host.close(); k.db.close(); k.linkScope.cancel() }

    @Test fun sendLoose_routesHideNoticeToUi() {
        k.profile(); k.phone(); k.boot()
        val router = CommandRouter(k.store, k.clock, Params(), { k.host.targets }, { false }, { throw it })
        k.exec.clear(); k.hiddenNotices.clear()
        router.sendLoose(listOf(HideNotice(Notice.FEEDING_CONFIRM), StopPlayback("p1", false), CancelSpeech))
        assertEquals(listOf(Notice.FEEDING_CONFIRM), k.hiddenNotices)
        assertEquals(listOf(StopPlayback("p1", false), CancelSpeech), k.exec)
        assertTrue(k.exec.none { it is HideNotice }, "HideNotice는 실행기로 가지 않는다")
    }

    @Test fun sceneEnd_hideNoticeGoesToUi() {
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
