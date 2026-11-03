package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 계약 2.3 순수 함수와 명세서 15장 '톰슨 샘플링(고정 시드 재현)'.
class CalcTest {
    @Test fun splitMix64_matchesReferenceOutput() {
        // SplitMix64 참조 구현(시드 0)의 첫 출력. JVM·플랫폼과 상관없이 같아야 한다.
        assertEquals(0xE220A8397B1DCDAFuL.toLong(), splitMix64(0).second)
    }

    @Test fun beta_fixedSeedReproducible_andMeanSane() {
        // 참조 표본(SplitMix64 정수 연산을 따로 계산한 값): 다음 rng와 표본값을 고정한다
        val (r1, x1) = sampleBeta(3, 2, 7)
        assertEquals(1663341875487337584L, r1)
        assertEquals(0.7938915127288727, x1, 1e-12)
        var r = 99L
        var sum = 0.0
        repeat(4_000) { val (n, x) = sampleBeta(3, 1, r); r = n; sum += x }
        assertEquals(0.75, sum / 4_000, 0.02)   // Beta(3, 1) 평균 = 3/4
    }

    @Test fun thompson_fixedSeedSameSequence() {
        val ms = listOf(WN, LL, SONG)
        val h = ms.flatMap { m -> List(3) { i -> FirstTry(i.toLong(), m, m == LL || i == 0) } }
        // 참조: Beta(2,3)=0.362, Beta(4,1)=0.533, Beta(2,3)=0.295 → 자장가, rng는 균등 난수 15개 뒤 값
        assertEquals(Pick(LL, 4990025626462014757L), pickFirst(ms, h, 2026))
        fun seq(seed: Long): List<Method> {
            var rr = seed
            return List(20) { pickFirst(ms, h, rr).also { rr = it.rng }.method }
        }
        assertEquals(seq(2026), seq(2026))
        assertTrue(seq(2026).toSet().size > 1)              // 고정 음원이 아니다
    }

    @Test fun pickFirst_roundRobinInFirstOrder() {
        assertEquals(WN, pickFirst(listOf(SONG, LL, WN), emptyList(), 1).method)
        val h = listOf(FirstTry(1, WN, true), FirstTry(2, WN, false), FirstTry(3, LL, true))
        assertEquals(LL, pickFirst(listOf(WN, LL), h, 1).method)
        assertEquals(SONG, pickFirst(listOf(WN, LL, SONG), h, 1).method)   // 가장 적은 것(G27)
        // 새 곡(다른 songId)은 0부터 센다
        assertEquals(SONG, pickFirst(listOf(WN, LL, SONG), h + FirstTry(4, LL, true) + List(2) { FirstTry(5, Method(MethodKind.SONG, "old"), true) }, 1).method)
    }

    @Test fun best_scoreThenFirstOrder() {
        assertEquals(WN, best(listOf(LL, WN), emptyList()))
        val h = listOf(FirstTry(1, LL, true), FirstTry(2, WN, false))
        assertEquals(LL, best(listOf(WN, LL), h))
        assertEquals(WN, best(listOf(WN, LL), h, except = LL))
    }

    @Test fun pickRest_30kmAndNursingPlus5km() {
        assertNull(pickRest(null))
        assertNull(pickRest(RouteView(0, false, null, listOf(rest("far", 31.0)))))
        val r = RouteView(0, false, null, listOf(rest("a", 10.0), rest("n", 14.9, true), rest("n2", 12.0, true)))
        assertEquals("n2", pickRest(r)!!.id)
        assertEquals("a", pickRest(RouteView(0, false, null, listOf(rest("a", 10.0), rest("n", 15.1, true))))!!.id)
    }

    @Test fun offerRest_rules() {
        val r = RouteView(0, false, null, listOf(rest("a", 10.0)))
        assertNull(offerRest(false, Gear.P, r, emptyMap(), 0, null))
        assertEquals(Sid.S08, offerRest(false, Gear.D, r, emptyMap(), 0, null)!!.sid)
        assertEquals(Sid.S11, offerRest(true, Gear.D, r, emptyMap(), 0, null)!!.sid)
        assertEquals(Sid.S10, offerRest(false, Gear.D, null, emptyMap(), 0, null)!!.sid)
        assertNull(offerRest(false, Gear.D, r, mapOf(Sid.S08 to 0L), 9 * MIN, null))
        assertNull(offerRest(false, Gear.D, RouteView(0, true, 600, r.rests), emptyMap(), 0, null))
    }
}
