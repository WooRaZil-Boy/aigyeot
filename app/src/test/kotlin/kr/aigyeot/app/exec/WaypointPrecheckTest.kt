package kr.aigyeot.app.exec

import kr.aigyeot.engine.Params
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 계약 4장 AddWaypoint '보내기 전에 경유지 목록을 한 번 읽는다'(C110), Params.waypointPrecheckMs
class WaypointPrecheckTest {
    private val t = FakeTime()
    private val p = WaypointPrecheck(t, Params().waypointPrecheckMs)
    private var asked = 0
    private val results = mutableListOf<Boolean>()
    private fun start() = p.start({ asked++ }) { results += it }

    @Test fun `기본 대기는 2초`() = assertEquals(2_000L, Params().waypointPrecheckMs)

    @Test fun `이미 있다고 나오면 true로 끝난다(보내지 않는다)`() {
        start(); assertEquals(1, asked)
        assertTrue(p.onList(true))
        assertEquals(listOf(true), results)
        t.advance(5_000); assertEquals(listOf(true), results, "시간 초과가 또 결과를 내지 않는다")
    }

    @Test fun `없다고 나오면 false로 끝난다(보낸다)`() {
        start(); assertTrue(p.onList(false)); assertEquals(listOf(false), results)
    }

    @Test fun `응답이 없으면 2초 뒤 false로 끝난다(보낸다)`() {
        start(); t.advance(1_999); assertTrue(results.isEmpty())
        t.advance(1); assertEquals(listOf(false), results)
    }

    @Test fun `보낸 뒤 늦게 온 사전 응답은 사전 확인으로 받지 않는다`() {
        start(); t.advance(2_000)
        assertFalse(p.onList(true), "끝난 뒤의 응답은 소비하지 않는다(보낸 뒤 확인으로 간다)")
        assertEquals(listOf(false), results, "결과는 한 번만")
    }

    @Test fun `취소하면 응답도 시간 초과도 결과를 내지 않는다`() {
        start(); p.cancel()
        assertFalse(p.onList(true)); t.advance(5_000)
        assertTrue(results.isEmpty())
    }

    @Test fun `목록을 묻다 던지면 바로 false로 끝난다`() {
        p.start({ error("navi") }) { results += it }
        assertEquals(listOf(false), results)
    }

    @Test fun `새로 시작하면 앞 사전 확인은 버린다`() {
        start(); start()
        assertEquals(2, asked); assertTrue(p.onList(true))
        assertEquals(listOf(true), results, "앞 요청의 결과는 나오지 않는다")
    }
}
