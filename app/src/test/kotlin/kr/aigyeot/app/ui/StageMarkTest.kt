package kr.aigyeot.app.ui

import kotlin.math.tan
import kr.aigyeot.app.ui.car3d.CarMath
import kr.aigyeot.app.ui.car3d.Light3d
import kr.aigyeot.app.ui.car3d.Seat3d
import kr.aigyeot.app.ui.car3d.Stage3d
import kr.aigyeot.app.ui.car3d.View3d
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3D 준비 표시(안드로이드 폰 StageTest와 같은 줄)와 홈 무대 첫 장 전 정지 그림. 시각은 3D 칸을 붙인 때부터 ms. */
class StageMarkTest {
    private fun marks(firstFrame: Long?, failed: Long? = null, still: Boolean = false, until: Long = 5_000) =
        (0..until step 10).associateWith { stageMark(it, firstFrame, failed, still) }

    @Test
    fun `준비가 800ms 안에 끝나면 고리를 보이지 않는다`() {
        assertTrue(marks(firstFrame = 670).values.all { it == StageMark.HIDDEN })
        assertTrue(marks(firstFrame = 800).values.all { it == StageMark.HIDDEN })
    }

    @Test
    fun `한 번 보인 고리는 400ms는 둔다`() {
        val m = marks(firstFrame = 900)
        assertEquals(StageMark.HIDDEN, m[790])
        assertTrue((800L until 1_200L step 10).all { m[it] == StageMark.RING }) // 첫 그림(900)이 와도 800 + 400까지
        assertEquals(StageMark.HIDDEN, m[1_200])
    }

    @Test
    fun `첫 그림이 나오면 고리를 감춘다`() {
        val m = marks(firstFrame = 4_300)
        assertTrue((800L until 4_300L step 10).all { m[it] == StageMark.RING })
        assertTrue((4_300L..5_000L step 10).all { m[it] == StageMark.HIDDEN })
        assertEquals(StageMark.RING, stageMark(60_000, null, null, still = false)) // 기다리는 시간 상한은 두지 않는다
        assertFalse(stageMarkSettled(1_000, null, null))
        assertFalse(stageMarkSettled(1_100, 900, null))
        assertTrue(stageMarkSettled(1_200, 900, null))
        assertTrue(stageMarkSettled(10, 670, null))
    }

    @Test
    fun `동작 줄이기와 주행 중은 고리 대신 정지 표시다`() {
        val m = marks(firstFrame = 900, still = true)
        assertEquals(StageMark.HIDDEN, m[790])
        assertTrue((800L until 1_200L step 10).all { m[it] == StageMark.STILL }) // 같은 때(800·400)에 고리 대신 정지 표시
        assertEquals(StageMark.HIDDEN, m[1_200])
    }

    @Test
    fun `준비 실패면 실패 정지 표시만 남는다`() {
        val m = marks(firstFrame = null, failed = 1_000)
        assertEquals(StageMark.RING, m[990])
        assertTrue((1_000L..5_000L step 10).all { m[it] == StageMark.FAILED }) // FAILED는 읽기 이름이 없는 정지 표시
        assertEquals(StageMark.FAILED, stageMark(300, null, failedMs = 200, still = false))
        assertTrue(stageMarkSettled(1_000, null, 1_000))
    }

    @Test
    fun `고리는 초당 15장, 한 장 24도로 끊어 돈다`() {
        assertEquals(15, RING_FPS) // 세 앱 같음
        assertEquals(24f, 360f / RING_FPS, 0f)
    }

    private val park = Stage3d(View3d.CABIN, Light3d.LISTEN)

    @Test
    fun `정지 그림은 정차 홈 시작 연출에만, 엔진 준비·카시트 자리·빛과 무관하게 둔다`() {
        // 그림은 카시트·듣는 빛을 담지 않아(13절) 자리·빛이 달라도 같다. 엔진이 이미 준비돼도(주행 뒤 첫 정차·설정 뒤 첫 홈) 둔다(15절): 인자에 엔진 준비가 없다
        for (seat in Seat3d.entries) for (light in Light3d.entries) assertTrue(posterFor(introPending = true, park.copy(seat = seat, light = light)))
        assertFalse(posterFor(true, park.copy(drive = true))) // 주행 중: 첫 장이 실내 → 정지 표시(차 아이콘)
        assertFalse(posterFor(true, park.copy(reducedMotion = true))) // 동작 줄이기도 같다
        assertFalse(posterFor(introPending = false, park)) // 연출을 이미 정한 프로세스
        assertTrue(posterRes(dark = false) != posterRes(dark = true)) // 테마만 고른다
    }

    @Test
    fun `준비가 실패하면 정지 그림을 걷고 실패 표시를 둔다`() {
        assertTrue(posterShown(chosen = true, unveiled = false, failedMs = null))
        assertFalse(posterShown(true, unveiled = true, failedMs = null)) // 3D로 바꿈
        assertFalse(posterShown(true, unveiled = false, failedMs = 2_000)) // 실패: 걷힘
        assertFalse(posterShown(chosen = false, unveiled = false, failedMs = null))
        // 걷힌 칸은 준비 표시가 맡는다: 실패면 실패 정지 표시
        assertEquals(StageMark.FAILED, stageMark(2_000, null, 2_000, still = false))
    }

    @Test
    fun `같은 크기 정지 그림은 한 장에 바꾸고 어긋날 때만 교차 페이드한다`() {
        // 찍은 크기 = 무대 크기: 같은 구도라 바로(겹쳐 흐리면 투명한 데가 두 겹으로 짙어진다)
        listOf(park, park.copy(drive = true), park.copy(reducedMotion = true)).forEach { assertEquals(0, posterFadeMs(it, exact = true)) }
        assertEquals(360, posterFadeMs(park, exact = false)) // 3D 들어옴 = enter 토큰
        assertEquals(Tokens.DRIVE_FADE_MS, posterFadeMs(park.copy(drive = true), exact = false)) // 주행 중 200ms 한 번
        assertEquals(0, posterFadeMs(park.copy(reducedMotion = true), exact = false)) // 동작 줄이기 바로
    }

    @Test
    fun `준비 표시 시계는 다음 경계에서만 깬다`() {
        assertEquals(STAGE_MARK_DELAY_MS, stageMarkNextMs(0, null, null)) // 붙인 직후: 800ms 나타남까지
        assertEquals(STAGE_MARK_DELAY_MS, stageMarkNextMs(799, null, null))
        assertNull(stageMarkNextMs(800, null, null)) // 고리가 보이면 첫 그림·실패 알림까지 깨지 않는다(가려져도 돌지 않음)
        assertNull(stageMarkNextMs(60_000, null, null))
        assertEquals(1_200L, stageMarkNextMs(900, 900, null)) // 900ms 첫 그림: 최소 표시 끝(800 + 400)
        assertNull(stageMarkNextMs(4_300, 4_300, null)) // 4,300ms 첫 그림: 경계를 이미 지나 바로 정해짐
        assertNull(stageMarkNextMs(10, 670, null)) // 800 안에 첫 그림: 정해짐
        assertNull(stageMarkNextMs(1_000, null, 1_000)) // 실패: 정해짐
        // 900ms 첫 그림: 시계가 깨는 때는 붙일 때·800 경계·첫 그림 알림·1,200 경계뿐이고, 그때 본 표시가 규칙과 같다
        val ff = 900L
        val wakes = listOf(0L, stageMarkNextMs(0, null, null)!!, ff, stageMarkNextMs(ff, ff, null)!!)
        assertEquals(listOf(0L, 800L, 900L, 1_200L), wakes)
        assertEquals(
            listOf(StageMark.HIDDEN, StageMark.RING, StageMark.RING, StageMark.HIDDEN),
            wakes.map { t ->
                stageMark(t, ff.takeIf { t >= it }, null, still = false)
            }
        )
        assertNull(stageMarkNextMs(1_200, ff, null)) // 그 뒤로는 깨지 않는다
    }

    @Test
    fun `정지 그림 배율은 3D 카메라와 같은 크기로 맞춘다`() {
        // 찍은 크기 그대로면 화소 그대로
        assertEquals(1f, CarMath.posterScale(View3d.HERO, 998, 1097, 998f, 1097f), 1e-6f)
        // 세로로 긴 칸(반폭이 가로로 들어오게 물러남): 차 폭이 칸 폭에 맞으므로 폭 비율
        assertEquals(800f / 998f, CarMath.posterScale(View3d.HERO, 998, 1097, 800f, 1097f), 1e-4f)
        // 넓고 낮은 칸(거리 = 시점 거리): 그림 속 반폭(찍은 칸 폭의 절반) × 배율 = 3D에서 반폭이 차지하는 화소
        val p = View3d.HERO.preset
        val w = 2000f
        val h = 600f
        val px3d = (h / 2) * p.r / (p.d * tan(p.fov * CarMath.D2R / 2))
        assertEquals(px3d, 998f / 2 * CarMath.posterScale(View3d.HERO, 998, 1097, w, h), 0.5f)
    }
}
