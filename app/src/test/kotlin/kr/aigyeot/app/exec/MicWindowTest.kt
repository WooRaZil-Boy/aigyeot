package kr.aigyeot.app.exec

import kotlin.test.Test
import kotlin.test.assertEquals

// 듣기 전 소리를 STT에 넣지 않는다: MicService는 975ms 창(15,600표본)을 다 읽은 뒤 부른다
class MicWindowTest {
    @Test fun `창 안에서 open 이전 몫을 잘라 내고 뒷부분만 남긴다`() {
        val n = 15_600
        assertEquals(n, keepTail(n, openedAt = 0, now = 5_000), "open이 창보다 한참 전이면 전부")
        assertEquals(n, keepTail(n, openedAt = 1_025, now = 2_000), "정확히 창 길이(975ms)면 전부")
        assertEquals(8_000, keepTail(n, openedAt = 1_500, now = 2_000), "500ms만 open 뒤")
        assertEquals(0, keepTail(n, openedAt = 2_000, now = 2_000), "open 시각에 닫힌 창은 통째로 버린다")
        assertEquals(0, keepTail(n, openedAt = 2_100, now = 2_000), "시계 어긋남도 0 이상")
    }
}
