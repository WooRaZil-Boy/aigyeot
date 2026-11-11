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

    @Test fun liveMotion_onlyInEventViews_parked() {
        val hvac = setOf(Callout3d.HVAC)
        assertFalse(Stage3d(View3d.CABIN, callouts = hvac).flowing(1f), "기본 실내 + 공조 칩: 바람 결은 멈춘 그림(계속 그리지 않음)")
        assertFalse(Stage3d(View3d.CABIN, Light3d.SOOTHE, ripple = true).rippling, "기본 실내 기다림의 소리 결도 멈춘 고리")
        assertTrue(Stage3d(View3d.REAR, callouts = hvac).flowing(1f), "사건 시점(REAR)에서는 흐른다")
        assertTrue(Stage3d(View3d.REAR, Light3d.SOOTHE, ripple = true).rippling)
        assertFalse(Stage3d(View3d.REAR, callouts = hvac, drive = true).flowing(1f), "주행 중은 사건 시점이어도 멈춤")
        assertFalse(Stage3d(View3d.REAR, callouts = hvac, reducedMotion = true).flowing(1f), "동작 줄이기도 멈춤")
        assertFalse(Stage3d(View3d.REAR, callouts = hvac).flowing(0.2f), "껍데기가 아직 불투명하면(이동 시작) 흐르지 않는다")
    }

    // 시작 연출 소진(계약 stage(3D)): 주행 중 무대가 먼저 보였으면 소진하지 않고 처음 정차 홈에서 한다. 정차 홈은 동작 줄이기여도 소진(연출 없이 cabin으로 끝)
    @Test fun intro_driveFirstView_notConsumed_parkConsumes() {
        assertFalse(consumeIntro(Stage3d(drive = true)), "주행 중 첫 보기는 소진하지 않는다")
        assertFalse(consumeIntro(Stage3d(drive = true, reducedMotion = true)), "주행 중이면 동작 줄이기여도 소진하지 않는다")
        assertTrue(consumeIntro(Stage3d()), "정차 홈 첫 보기는 소진")
        assertTrue(consumeIntro(Stage3d(reducedMotion = true)), "정차 홈 동작 줄이기도 소진(연출 없이 cabin으로 끝)")
    }

    // 정지 그림 걷기(onHoldEnd)·옅은 장 판단은 받는 쪽이 본 장의 시각으로 한다. 받는 쪽은 가장 최근 장으로 건너뛰어(updateTexImage) 받은 횟수가 낸 횟수보다 적다
    @Test fun holdEnd_byShownTimestamp_survivesSkippedFrames() {
        assertFalse(drawnSince(5_000, -1), "기다리는 것이 없으면 아무 장도 알리지 않는다")
        assertTrue(drawnSince(5_000, 5_000), "멈춤을 끝낸 doFrame에서 그린 장(같은 시각)부터")
        assertFalse(drawnSince(4_999, 5_000), "그 전에 그린 장(옅은 장)은 아니다")
        // 낸 장: 시각 1..10, 멈춤 끝은 시각 8에 그린 장부터. 받는 쪽은 2·5·10만 봤다(중간 건너뜀)
        val sent = (1L..10L).toList(); val shown = listOf(2L, 5L, 10L); val holdEndFrom = 8L
        assertTrue(shown.any { drawnSince(it, holdEndFrom) }, "건너뛰어도 그 뒤 장이 들어오면 걷는다")
        // 고치기 전 식(받은 수 > 멈춤 끝에 낸 수)은 받은 수 3이 낸 수 7을 끝내 넘지 못해 영영 걷지 않았다
        val sentAtHoldEnd = sent.count { it < holdEndFrom }
        assertFalse(shown.size > sentAtHoldEnd, "옛 식이 갇히는 경우(이 시험의 전제)")
    }
}
