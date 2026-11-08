package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// C350: 마이크가 꺼져 있으면 쉼터 질문(F5 출발 제안 S07_DEPART, L1 장시간 S11)을 내지 않고 기회도 쓰지 않는다. 마이크가 켜지면 다시 본다.
class C350MicAskTest {
    private val nurse = rest("n", 9.0, nursing = true)

    private fun micOffDepart(): Sim {
        val sim = session(prof = profile(interval = 180), feedingStart = T0 - 120 * MIN, mic = false)
            .route(nurse, dest = true, remainingSec = 9000, remainingKm = 150.0)
        assertFalse(sim.s.car.micRunning); assertEquals(Gear.D, sim.s.car.gear); assertTrue(sim.s.session != null)
        return sim
    }

    @Test fun c350_departOffer_micOff_noAskAndNotChecked() {
        val sim = micOffDepart()
        assertTrue(sim.all<Ask>().isEmpty()); assertTrue(Sid.S07_DEPART !in sim.sids())
        assertFalse(sim.ses.departChecked)
    }

    @Test fun c350_departOffer_micOnLater_asks() {
        val sim = micOffDepart()
        sim.ev(MicService(sim.t + 1_000, true, MicPath.HOME_BUTTON))
        assertTrue(sim.s.car.micRunning)
        assertTrue(Sid.S07_DEPART in sim.sids())   // S16·S17 뒤에 줄이 서 있거나 이미 물었다
        assertTrue(sim.ses.departChecked)
    }

    @Test fun c350_longDrive_micOff_noOffer() {
        val sim = session(mic = false).route(rest("a", 12.0))
        assertFalse(sim.s.car.micRunning); assertEquals(Gear.D, sim.s.car.gear)
        sim.ev(Tick(T0 + 121 * MIN))
        assertTrue(Sid.S11 !in sim.sids()); assertTrue(sim.all<Ask>().none { it.sid == Sid.S11 })
        assertFalse(sim.ses.longDriveOffered)
        sim.ev(MicService(sim.t + 1_000, true, MicPath.HOME_BUTTON)).tick(1_000)
        assertTrue(Sid.S11 in sim.sids())
    }
}
