package kr.aigyeot.app.ui.car3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 3D 무대의 순수 계산(시안 car3d.js fitD·fly·pulse와 같은 값). */
class CarMathTest {
    private val MS = 1_000_000L

    @Test fun fitD_widensForTallViews_andKeepsPresetOnWide() {
        assertEquals(9.4f, CarMath.fitD(View3d.HERO.preset, 2.0f), "넓으면 시안 거리 그대로")
        val tall = CarMath.fitD(View3d.REAR.preset, 1070f / 1098f)
        assertEquals(9.38f, tall, 0.02f, "세로로 긴 무대(홈 1070×1098)는 반폭 2.45가 들어오게 물러난다")
    }

    @Test fun lerp_turnsTheShortWay_andEaseIsSymmetric() {
        val a = Cam(350f, 10f, 9f, 27f, floatArrayOf(0f, 0f, 0f)); val b = Cam(10f, 20f, 7f, 30f, floatArrayOf(1f, 1f, 1f))
        assertEquals(360f, CarMath.lerp(a, b, 0.5f).az, 0.001f, "350° → 10°는 20°만 돈다")
        val ease = kr.aigyeot.app.ui.Tokens.motion.getValue("camera").easing   // 카메라 곡선은 토큰 하나(cubic-bezier(.65,0,.35,1))
        assertEquals(0.5f, ease.transform(0.5f), 1e-3f); assertEquals(0f, ease.transform(0f)); assertEquals(1f, ease.transform(1f))
    }

    @Test fun eye_isTargetPlusSpherical() {
        val e = CarMath.eye(Cam(0f, 0f, 5f, 30f, floatArrayOf(1f, 2f, 3f)))
        assertEquals(6f, e[0], 1e-4f); assertEquals(2f, e[1], 1e-4f); assertEquals(3f, e[2], 1e-4f)
    }

    @Test fun effects_heatLockOnlyWhenNew_andGoneWhenStateChanges() {
        val fx = Effects()
        val base = Stage3d(); val hl = base.copy(callouts = setOf(Callout3d.HEAT, Callout3d.LOCK))
        fx.onSet(base, hl, ok = true, arriveIn = 0, now = 0)
        assertEquals(1f, fx.at(0).heatWarm); assertTrue(fx.at(450 * MS).lockK > 1.8f); assertTrue(fx.at(0).running)
        assertEquals(0f, fx.at(1600 * MS).heatWarm); assertEquals(1f, fx.at(900 * MS).lockK); assertFalse(fx.at(1600 * MS).running, "끝나면 0프레임")
        fx.onSet(hl, hl, ok = true, arriveIn = 0, now = 10)
        assertEquals(0L, fx.heatFrom, "이미 있던 칩은 다시 시작하지 않는다")
        fx.onSet(hl, hl.copy(drive = true), ok = false, arriveIn = 0, now = 20)
        assertNull(fx.heatFrom); assertNull(fx.lockFrom); assertFalse(fx.at(20).running, "주행 중으로 바뀌면 진행 중 효과도 없다")
    }

    @Test fun effects_pulseAfterCameraArrives_cancelledIfAlertEnds() {
        val fx = Effects()
        val hero = Stage3d(); val alert = Stage3d(View3d.REAR, Light3d.ALERT)
        fx.onSet(hero, alert, ok = true, arriveIn = 1100 * MS, now = 0)
        assertNull(fx.at(500 * MS).pulseK, "카메라가 닿기 전에는 맥박이 없다"); assertTrue(fx.at(500 * MS).running, "기다리는 동안 프레임은 잇는다")
        assertEquals(0.5f, fx.at(1700 * MS).pulseK!!, 1e-3f)
        assertNull(fx.at(2300 * MS).pulseK); assertFalse(fx.at(2300 * MS).running)
        fx.onSet(hero, alert, ok = true, arriveIn = 1100 * MS, now = 0)
        fx.onSet(alert, alert.copy(light = Light3d.BASE), ok = true, arriveIn = 500 * MS, now = 600 * MS)
        assertNull(fx.pulseFrom, "닿기 전에 경보가 풀리면 맥박을 하지 않는다")
        fx.onSet(hero, alert, ok = true, arriveIn = 1100 * MS, now = 0)
        fx.onSet(alert, alert.copy(seat = Seat3d.RL), ok = true, arriveIn = 1100 * MS, now = 600 * MS)
        assertEquals(1700 * MS, fx.pulseFrom, "이동 중 목표가 바뀌면 시작 전 맥박은 새 도착 시각에")
    }
}
