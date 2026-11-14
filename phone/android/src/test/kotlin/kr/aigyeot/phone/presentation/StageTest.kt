package kr.aigyeot.phone.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3D 준비 표시와 첫 안내 등장 안무. 시각은 무대를 붙인 때부터 ms. */
class StageTest {
    private fun marks(firstFrame: Long?, failed: Long? = null, reduced: Boolean = false, until: Long = 5_000) =
        (0..until step 10).associateWith { stageMark(it, firstFrame, failed, reduced) }

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
        // 첫 그림이 없으면 계속 고리(기다리는 시간 상한은 두지 않는다)
        assertEquals(StageMark.RING, stageMark(60_000, null, null, reduced = false))

    }

    @Test
    fun `동작 줄이기면 정지 표시다`() {
        val m = marks(firstFrame = 900, reduced = true)
        assertEquals(StageMark.HIDDEN, m[790])
        assertTrue((800L until 1_200L step 10).all { m[it] == StageMark.STILL }) // 같은 때(800·400)에 고리 대신 정지 표시
        assertEquals(StageMark.HIDDEN, m[1_200])
    }

    @Test
    fun `준비 실패면 정지 표시만 남고 읽지 않는다`() {
        val m = marks(firstFrame = null, failed = 1_000)
        assertEquals(StageMark.RING, m[990])
        assertTrue((1_000L..5_000L step 10).all { m[it] == StageMark.FAILED }) // FAILED는 읽기 이름이 없는 정지 표시(StageMarkView)
        assertEquals(StageMark.FAILED, stageMark(300, null, failedMs = 200, reduced = false)) // 800 전 실패도 바로
        assertNull(stageMarkNextMs(1_000, null, 1_000)) // 실패 뒤에는 시계를 멈춘다
    }

    @Test
    fun `준비 표시는 다음 경계까지만 기다린다`() {
        // 50ms마다 깨지 않고, 시간만 흘러 표시가 바뀌는 때(800ms 나타남, 걷힘)에만 깬다. 그 밖에는 첫 그림·실패 사건을 기다린다(null).
        assertEquals(800L, stageMarkNextMs(0, null, null))
        assertEquals(800L, stageMarkNextMs(500, null, null))
        assertNull(stageMarkNextMs(850, null, null)) // 고리가 돌며 첫 그림을 기다린다
        assertEquals(1_200L, stageMarkNextMs(900, 900, null)) // 첫 그림이 와도 400ms는 둔다
        assertEquals(4_300L, stageMarkNextMs(4_300 - 1, 4_300, null))
        assertNull(stageMarkNextMs(1_200, 900, null)) // 걷힌 뒤
        assertNull(stageMarkNextMs(700, 670, null)) // 800ms 안에 첫 그림: 고리 없이 끝
        assertNull(stageMarkNextMs(300, null, 200)) // 실패
        // 깨어날 때마다 표시는 규칙대로다(경계 바로 앞·뒤)
        assertEquals(StageMark.HIDDEN, stageMark(799, null, null, reduced = false))
        assertEquals(StageMark.RING, stageMark(800, null, null, reduced = false))
        assertEquals(StageMark.RING, stageMark(1_199, 900, null, reduced = false))
        assertEquals(StageMark.HIDDEN, stageMark(1_200, 900, null, reduced = false))
    }

    @Test
    fun `첫 안내는 3D 보기를 만들지 않는다`() {
        // 첫 안내 무대는 정지 그림이라 3D 무대·준비 표시가 없다(차 탭만 3D).
        val src = File("src/main/kotlin/kr/aigyeot/phone/presentation/IntroScreen.kt").readText()
        assertFalse(src.contains("CarStage(") || src.contains("CarTextureView"))
        assertTrue(src.contains("R.drawable.intro_car"))
    }

    @Test
    fun `첫 안내 안무는 처음 한 번만 한다`() {
        IntroMotion.played = false
        assertNotNull(IntroMotion.take(reduced = false, stage = true))
        assertNull(IntroMotion.take(reduced = false, stage = true)) // 뒤로 와 다시 그림·시트 닫기·다크 전환
        IntroMotion.played = false
    }

    @Test
    fun `동작 줄이기면 첫 안내 안무가 없다`() {
        IntroMotion.played = false
        assertNull(IntroMotion.take(reduced = true, stage = true))
        assertNull(IntroMotion.take(reduced = false, stage = true)) // 줄이기로 건너뛴 것도 했음
        IntroMotion.played = false
    }

    @Test
    fun `무대가 숨으면 차 안무 없이 글이 120ms부터다`() {
        assertEquals(IntroPlan(car = true, textDelayMs = 240, barDelayMs = 590), introPlan(played = false, reduced = false, stage = true))
        assertEquals(IntroPlan(car = false, textDelayMs = 120, barDelayMs = 470), introPlan(played = false, reduced = false, stage = false))
    }

    @Test
    fun `첫 안내의 누를 것은 나타나기 시작한 뒤에만 눌린다`() {
        assertFalse(introLive(decided = false, startMs = null, clockMs = 0f)) // 잰 첫 장(화면 전체 투명)
        assertFalse(introLive(decided = true, startMs = 590, clockMs = 0f)) // 창을 기다리는 동안·안무 앞부분
        assertFalse(introLive(decided = true, startMs = 590, clockMs = 589f))
        assertTrue(introLive(decided = true, startMs = 590, clockMs = 590f))
        assertTrue(introLive(decided = true, startMs = null, clockMs = 0f)) // 안무 없음(이미 함·동작 줄이기)
    }
}
