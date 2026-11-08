package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// C349: 수유 확인 알림 = 그때 쓰는 상태의 확인 투영. 장면 시작은 실제 알림만 내리고(상태 유지), 장면 끝은 장면 확인을 버리고, Boot는 남은 확인을 다시 띄운다.
class C349NoticeTest {
    private val nurse = rest("n", 9.0, nursing = true)
    private val hide = HideNotice(Notice.FEEDING_CONFIRM)

    private fun parked(): Sim {
        val sim = session().route(nurse).ev(DriverAddWaypoint(T0, "n"))
        sim.ev(WaypointResult(sim.t + 500, sim.step<AddWaypoint>().single().cmdId, "n", true))
        return sim.gear(Gear.P, MIN)
    }
    private fun confirmed(): Sim = parked().tick(15 * MIN).also { assertTrue(it.s.ign.nursingStop!!.confirmShown) }

    @Test fun c349_sceneStart_hidesRealConfirmButKeepsState() {
        val sim = confirmed()
        val step = Engine().sceneStart(sim.s, sim.s, sim.t + 100).realStep
        assertTrue(hide in step.commands)
        assertTrue(step.state.ign.nursingStop!!.confirmShown)
    }

    @Test fun c349_sceneStart_noConfirm_noHide() {
        val sim = parked()
        assertNotNull(sim.s.ign.nursingStop!!.firstPAt); assertTrue(!sim.s.ign.nursingStop!!.confirmShown)
        val step = Engine().sceneStart(sim.s, sim.s, sim.t + 100).realStep
        assertTrue(step.commands.none { it is HideNotice })
        assertEquals(sim.s.ign.nursingStop, step.state.ign.nursingStop)
    }

    @Test fun c349_sceneEnd_dropsSceneConfirmAndHides() {
        val sim = confirmed()
        assertTrue(hide in Engine().sceneEnd(sim.s).commands)
    }

    @Test fun c349_boot_reshowsConfirm() {
        val sim = confirmed()
        sim.ev(Boot(sim.t + 1, Gear.P, 10, stopEnded = false))
        assertTrue(sim.s.ign.nursingStop!!.confirmShown)
        assertEquals(Notice.FEEDING_CONFIRM, sim.step<ShowNotice>().single().kind)
    }

    @Test fun c349_boot_stopEnded_hidesNotReshows() {
        val sim = confirmed()
        sim.ev(Boot(sim.t + 1, Gear.P, 10, stopEnded = true))
        assertNull(sim.s.ign.nursingStop)
        assertTrue(hide in sim.cmds)
        assertTrue(sim.step<ShowNotice>().isEmpty())
    }
}
