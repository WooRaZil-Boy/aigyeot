package kr.aigyeot.app.detect

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// 판별층 계산이 파이썬(rt_train.py 식)과 같은 값을 내는가. 기대값은 실제 cry_head24_rt.json으로 파이썬에서 계산했다.
class CryHeadTest {
    private val headFile = File("src/main/assets/cry_head24_rt.json")

    // 결정적인 가짜 점수: 창 k의 클래스 c 점수 = ((7c + 13k + 3) mod 97) / 97 * 0.1
    private fun scores(k: Int) = FloatArray(521) { c -> ((c * 7 + k * 13 + 3) % 97) / 97f * 0.1f }

    @Test fun `파이썬과 같은 확률`() {
        val head = CryHead.parse(headFile.readText())
        val expected = listOf(0.5173621696, 0.6042965819, 0.3498476126)
        expected.forEachIndexed { i, e -> assertEquals(e, head.prob(listOf(scores(i), scores(i + 1), scores(i + 2))), 1e-5, "구간 $i") }
    }

    @Test fun `threshold가 있으면 쓰고 없으면 0점7`() {
        assertEquals(0.7, CryHead.parse(headFile.readText()).threshold)
        assertEquals(0.7, CryHead.parse("""{"classes":[0],"w":[1.0,1.0],"bias":0.0}""").threshold)
        assertEquals(0.5, CryHead.parse("""{"classes":[0],"w":[1.0,1.0],"bias":0.0,"threshold":0.5}""").threshold)
    }
}
