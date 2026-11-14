package kr.aigyeot.app.host

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.PauseReason
import kr.aigyeot.engine.Side
import kr.aigyeot.engine.Stage
import kr.aigyeot.engine.steer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** '바로 저장' 열쇠는 손 목록이 아니라 잦게 바뀌는 칸만 뺀 상태 전체다. 옛 목록에 없던 칸도 바뀌면 바로 저장된다. */
@RunWith(RobolectricTestRunner::class)
class SnapshotKeyTest {
    private val k = Kit()

    @Test fun highRateFieldsOnly_leaveKeyUnchanged() {
        k.profile()
        k.boot()
        k.open()
        val s = k.state()
        val x = s.session!!
        val waiting = s.copy(session = x.copy(stage = Stage.Wait(1, quietMs = 1_000)))
        assertEquals(
            snapshotKey(waiting),
            snapshotKey(
                waiting.copy(
                    now = s.now + 9_000,
                    steer = s.steer.copy(filtered = 3.0, lastSampleAt = s.now + 9_000),
                    car = s.car.copy(cabinC = 27.0, gear = Gear.P, rearDoorOpen = setOf(Side.LEFT)),
                    ign = s.ign.copy(doorOpenedSinceP = !s.ign.doorOpenedSinceP),
                    session = x.copy(stage = Stage.Wait(1, quietMs = 9_000))
                )
            ),
            "조향·차량 표본·뒷문·조용함 누적·now는 묶어 저장"
        )
    }

    @Test fun fieldsMissingFromOldList_changeKey() {
        k.profile()
        k.boot()
        k.open()
        val s = k.state()
        val x = s.session!!
        for (n in listOf(
            s.copy(car = s.car.copy(pauses = setOf(PauseReason.MIC_ERROR))),
            s.copy(ign = s.ign.copy(reserved = !s.ign.reserved)),
            s.copy(otherGuardians = s.otherGuardians + 1),
            s.copy(session = x.copy(cryRuns = x.cryRuns + 1)),
            s.copy(session = x.copy(stage = Stage.Wait(1)))
        )) {
            assertNotEquals(snapshotKey(s), snapshotKey(n), "$n")
        }
    }
}
