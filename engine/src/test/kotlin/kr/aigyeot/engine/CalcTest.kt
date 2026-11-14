package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 순수 함수와 톰슨 샘플링(고정 시드 재현).
class CalcTest {
    @Test fun methodStats_windowBoundary28Days() {
        val now = 100 * DAY_MS
        val m = Method(MethodKind.WHITE_NOISE)
        val since = Params().statSince(now)
        val h = listOf(FirstTry(since - 1, m, true), FirstTry(since, m, true), FirstTry(since + 1, m, false), FirstTry(now, Method(MethodKind.WOMB), true))
        assertEquals(
            listOf(m to (1 to 2), Method(MethodKind.WOMB) to (1 to 1), Method(MethodKind.LULLABY) to (0 to 0)),
            methodStats(h, listOf(m, Method(MethodKind.WOMB), Method(MethodKind.LULLABY)), now),
            "28일 바로 전은 빠지고 경계 시각은 든다"
        )
    }

    @Test fun sessionIdOf_fromCmdId() {
        assertEquals("01ABC", sessionIdOf("01ABC-12"))
        assertNull(sessionIdOf("idle-3"))
        assertNull(sessionIdOf("noseq"))
    }

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
        repeat(4_000) {
            val (n, x) = sampleBeta(3, 1, r)
            r = n
            sum += x
        }
        assertEquals(0.75, sum / 4_000, 0.02) // Beta(3, 1) 평균 = 3/4
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
        assertTrue(seq(2026).toSet().size > 1) // 고정 음원이 아니다
    }

    @Test fun pickFirst_roundRobinInFirstOrder() {
        assertEquals(WN, pickFirst(listOf(SONG, LL, WN), emptyList(), 1).method)
        val h = listOf(FirstTry(1, WN, true), FirstTry(2, WN, false), FirstTry(3, LL, true))
        assertEquals(LL, pickFirst(listOf(WN, LL), h, 1).method)
        assertEquals(SONG, pickFirst(listOf(WN, LL, SONG), h, 1).method) // 가장 적은 것
        // 새 곡(다른 songId)은 0부터 센다
        assertEquals(SONG, pickFirst(listOf(WN, LL, SONG), h + FirstTry(4, LL, true) + List(2) { FirstTry(5, Method(MethodKind.SONG, "old"), true) }, 1).method)
    }

    @Test fun best_scoreThenFirstOrder() {
        assertEquals(WN, best(listOf(LL, WN), emptyList()))
        val h = listOf(FirstTry(1, LL, true), FirstTry(2, WN, false))
        assertEquals(LL, best(listOf(WN, LL), h))
        assertEquals(WN, best(listOf(WN, LL), h, except = LL))
    }

    @Test fun pickFirst_roundRobinFourMethods() {
        val ms = listOf(SONG, LL, WB, WN) // 받은 순서와 무관하게 처음 순서로
        var h = emptyList<FirstTry>()
        val order = List(8) { i -> pickFirst(ms, h, 1).method.also { h = h + FirstTry(i.toLong(), it, true) } }
        assertEquals(listOf(WN, WB, LL, SONG, WN, WB, LL, SONG), order)
    }

    @Test fun pickFirst_threeMethodsWithoutSong() {
        val ms = listOf(LL, WB, WN)
        var h = emptyList<FirstTry>()
        val order = List(6) { i -> pickFirst(ms, h, 1).method.also { h = h + FirstTry(i.toLong(), it, true) } }
        assertEquals(listOf(WN, WB, LL, WN, WB, LL), order)
    }

    @Test fun best_tieOrderIncludesWomb() {
        val ms = listOf(LL, WB, WN, SONG)
        assertEquals(WN, best(ms, emptyList()))
        assertEquals(WB, best(ms, emptyList(), except = WN)) // 2차 '다른 음원'이 뱃속 소리를 고른다
        val h = listOf(FirstTry(1, WN, false), FirstTry(2, WB, true), FirstTry(3, LL, true))
        assertEquals(WB, best(ms, h)) // 성적 같으면 처음 순서(뱃속 소리 먼저)
        assertEquals(LL, best(ms, h, except = WB))
        assertEquals(WB, best(listOf(WN, WB), h, except = WN))
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
        assertNull(offerRest(false, Gear.P, r, 0, null))
        assertEquals(Sid.S08, offerRest(false, Gear.D, r, 0, null)!!.sid)
        assertEquals(Sid.S11, offerRest(true, Gear.D, r, 0, null)!!.sid)
        assertEquals(Sid.S10, offerRest(false, Gear.D, null, 0, null)!!.sid)
        assertTrue(offerBanned(mapOf(Sid.S08 to 0L), emptyList(), "s", Sid.S08, 9 * MIN)) // 10분 금지는 offerBanned
        assertNull(offerRest(false, Gear.D, RouteView(0, true, 600, r.rests), 0, null))
    }

    @Test fun departOffer_skipsUnaddable() {
        val now = 0L
        val iv = 180
        // 수유 시작 = now - 70분 → 배고플 시각 h = now + 110분. 남은 7200초(120분)·120km: eta(분) = km
        val start = now - 70 * MIN
        fun at(id: String, d: Double, ok: Boolean = true) = RestCandidate(id, id, RestKind.REST_AREA, d, true, addable = ok)
        val rests = listOf(at("용인", 28.5), at("덕평", 41.2), at("여주", 57.9, ok = false), at("충주", 95.4))
        // h - lead(20) = 90분: 충주(95.4)는 늦고, addable이면 가장 늦은 eta는 여주(57.9)
        assertEquals("여주", pickDepartRest(RouteView(now, true, 7200, rests.map { it.copy(addable = true) }, 120.0), start, iv, now, 20)?.id)
        assertEquals("덕평", pickDepartRest(RouteView(now, true, 7200, rests, 120.0), start, iv, now, 20)?.id)
    }

    @Test fun offerRest_skipsUnaddable() {
        fun r(vararg l: RestCandidate) = RouteView(0, false, null, l.toList())
        val bad = rest("bad", 5.0).copy(addable = false)
        assertEquals("ok", offerRest(false, Gear.D, r(bad, rest("ok", 10.0)), 0, null)!!.askRestId)
        assertEquals(Sid.S10, offerRest(false, Gear.D, r(bad), 0, null)!!.sid, "넣을 수 있는 쉼터가 없으면 쉼터 없음 안내")
        assertEquals("ok", pickRest(r(bad, rest("ok", 10.0)))!!.id)
    }

    @Test fun s07WaitsAfterDepartOffer() {
        val r = RouteView(0, false, null, listOf(rest("n", 10.0, true)))
        assertEquals(Sid.S07, offerRest(false, Gear.D, r, 0, null)!!.sid)
        fun banned(spoken: Map<Sid, Long>, now: Long) = offerBanned(spoken, emptyList(), "s", Sid.S07, now)
        assertTrue(banned(mapOf(Sid.S07_DEPART to 0L), 9 * MIN))
        assertFalse(banned(mapOf(Sid.S07_DEPART to 0L), 10 * MIN))
        assertTrue(banned(mapOf(Sid.S07 to 0L, Sid.S07_DEPART to 5 * MIN), 14 * MIN)) // 늦은 쪽 기준
    }
}
