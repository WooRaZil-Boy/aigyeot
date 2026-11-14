package kr.aigyeot.engine

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

// 성능 측정(판단 코어, JVM). 환경 변수 PERF=true(또는 -Dperf=true)가 없으면 건너뛴다.
// 결과는 PERF_OUT(기본 engine/build/perf_engine.txt)에 쓴다.
class PerfBench {
    private val enabled = System.getenv("PERF") == "true" || System.getProperty("perf") == "true"

    private class Stat(val name: String) {
        var ns = LongArray(1 shl 16); var n = 0
        fun add(x: Long) { if (n == ns.size) ns = ns.copyOf(n * 2); ns[n++] = x }
        fun line(): String {
            val a = ns.copyOf(n).also { it.sort() }
            val med = a[n / 2] / 1000.0; val p99 = a[(n * 0.99).toInt().coerceAtMost(n - 1)] / 1000.0
            val tot = a.sum() / 1e6
            return "%-14s n=%6d  중앙값 %7.1f µs  p99 %8.1f µs  최대 %9.1f µs  합계 %8.1f ms".format(name, n, med, p99, a[n - 1] / 1000.0, tot)
        }
    }

    private class Run(val stats: Map<String, Stat>, val snapsAny: Int, val snapsNoSteer: Int, val snapsCoarse: Int,
                      val maxStateBytes: Int, val by: Map<String, Int>, val cmds: Map<String, Int>, val last: EngineState, val maxHistory: Int, val maxRestLog: Int,
                      val maxLastOffer: Int, val maxQueue: Int, val steps: Int, val wallMs: Double)

    private val ENGINE = Engine()
    private val rests = listOf(
        RestCandidate("r1", "가나 휴게소", RestKind.REST_AREA, 6.0, true),
        RestCandidate("r2", "다라 졸음쉼터", RestKind.DROWSY_SHELTER, 3.0, false),
        RestCandidate("r3", "마바 휴게소", RestKind.REST_AREA, 18.0, true),
    )

    private fun oldHistory(): List<FirstTry> {
        val ms = listOf(WN, LL, Method(MethodKind.SONG, "s1"), Method(MethodKind.SONG, "s2"))
        return (0 until 280).map { FirstTry(T0 - (it + 1) * 2 * 3_600_000L, ms[it % 4], it % 3 != 0) }   // 28일 × 10번
    }

    private fun run(): Run {
        val stats = listOf("Tick", "CrySegment", "VehicleSample", "RouteState", "기타").associateWith { Stat(it) }
        var s = EngineState.fresh(42, profile(interval = 180, sibling = true), listOf(WN, LL, SONG), oldHistory(), T0 - 90 * MIN, 1)
        fun step(e: Event, key: String): Step {
            val t0 = System.nanoTime()
            val r = ENGINE.step(s, e)
            stats[key]!!.add(System.nanoTime() - t0)
            return r
        }
        val by = mutableMapOf<String, Int>(); val cmds = mutableMapOf<String, Int>()
        var snapsAny = 0; var snapsNoSteer = 0; var snapsCoarse = 0; var maxBytes = 0; var steps = 0
        var maxH = 0; var maxR = 0; var maxO = 0; var maxQ = 0
        val pending = ArrayDeque<Pair<Long, Event>>()
        fun apply(e: Event, key: String) {
            val r = step(e, key); steps++
            val o = s; s = r.state
            fun EngineState.noQuiet() = session?.let { it.copy(stage = it.stage.withQuiet(0)) }
            if (o.copy(now = 0, session = o.noQuiet()) != s.copy(now = 0, session = s.noQuiet())) {
                snapsAny++
                if (o.copy(now = 0, steer = o.steer.copy(filtered = null, lastSampleAt = null, extremumDeg = null, window = null, dir = 0),
                        session = o.noQuiet()) !=
                    s.copy(now = 0, steer = s.steer.copy(filtered = null, lastSampleAt = null, extremumDeg = null, window = null, dir = 0),
                        session = s.noQuiet()) || r.commands.any { it is SaveSession || it is SaveEpisode || it is SavePlay })
                    run { snapsNoSteer++; by.merge(key, 1, Int::plus) }
            }
            if (r.commands.any { it is SaveSession || it is SaveEpisode || it is SavePlay || it is SaveSnapshot || it is CloseSession }) snapsCoarse++
            s.history.size.let { if (it > maxH) maxH = it }
            s.session?.let { x -> if (x.restLog.size > maxR) maxR = x.restLog.size; if (x.spokenAt.size > maxO) maxO = x.spokenAt.size }
            if (s.speech.queue.size > maxQ) maxQ = s.speech.queue.size
            for (c in r.commands) cmds.merge(c::class.simpleName!!, 1, Int::plus)
            for (c in r.commands) when (c) {
                is StartPlayback -> pending += (e.at + 400) to PlaybackStarted(e.at + 400, c.playId, "bus100_audio_zone_1", false)
                is Speak -> pending += (e.at + 2_000) to SpeechDone(e.at + 2_000, c.utteranceId, true)
                is Ask -> pending += (e.at + 2_000) to SpeechDone(e.at + 2_000, c.askId, true)
                else -> {}
            }
        }
        // 시동: 문 열고 닫고 D
        apply(Boot(T0, Gear.P, 10), "기타"); apply(MicService(T0, true, MicPath.HOME_BUTTON), "기타")
        apply(RearDoor(T0, Side.LEFT, true), "기타"); apply(RearDoor(T0, Side.LEFT, false), "기타")
        apply(GearChanged(T0, Gear.D), "기타")
        val cry = listOf(10..12, 40..43, 70..71, 100..103)   // 울음 에피소드 4번(분)
        val wall = System.nanoTime()
        var t = T0
        val end = T0 + 120 * MIN
        while (t < end) {
            t += 25
            while (pending.isNotEmpty() && pending.first().first <= t) {
                val e = pending.removeFirst().second
                apply(e, "기타")
                s.speech.qa?.let { apply(Heard(e.at, it.listenId, null), "기타") }
            }
            val rel = t - T0
            if (rel % 100 == 0L) {
                val ph = rel / 1000.0
                apply(VehicleSample(t, 90.0 + 5 * Math.sin(ph / 7), false, 8 * Math.sin(ph / 3), TurnSignal.NONE), "VehicleSample")
            }
            if (rel % 975 == 0L) {
                val inCry = cry.any { (rel / MIN).toInt() in it }
                val first = inCry && ((rel - 975) / MIN).toInt() !in (cry.first { (rel / MIN).toInt() in it })
                apply(CrySegment(t, t - 3_000, t, if (inCry) 0.9 else 0.05, first), "CrySegment")
            }
            if (rel % 1000 == 0L) apply(Tick(t), "Tick")
            if (rel % 30_000 == 0L) {
                val d = rel / 1000 / 3600.0 * 90
                apply(RouteState(t, true, 3600, rests.map { it.copy(distanceKm = (it.distanceKm - d % 18).coerceAtLeast(1.0)) }), "RouteState")
            }
            if (rel % 1_000 == 0L && rel % 60_000 == 0L) maxBytes = maxOf(maxBytes, Json.encodeToString(EngineState.serializer(), s).length)
        }
        return Run(stats, snapsAny, snapsNoSteer, snapsCoarse, maxBytes, by, cmds, s, maxH, maxR, maxO, maxQ, steps, (System.nanoTime() - wall) / 1e6)
    }

    @Test fun perf() {
        assumeTrue(enabled)
        val sb = StringBuilder()
        val first = run()   // 콜드(JIT 전)
        repeat(2) { run() }  // 예열
        val r = run()
        sb.appendLine("== 사건 하나당 Engine.step 시간 (2시간 주행 흉내 1회, 예열 3회 뒤) ==")
        r.stats.values.forEach { sb.appendLine(it.line()) }
        sb.appendLine("전체: step %d번, 재는 포함 실행 시간 %.0f ms (콜드 첫 실행 %.0f ms)".format(r.steps, r.wallMs, first.wallMs))
        val coldAll = first.stats.values.sumOf { it.ns.copyOf(it.n).sum() } / 1e6
        val warmAll = r.stats.values.sumOf { it.ns.copyOf(it.n).sum() } / 1e6
        sb.appendLine("step 시간 합계: 콜드 %.0f ms, 예열 뒤 %.0f ms (2시간 = 7,200,000 ms)".format(coldAll, warmAll))

        sb.appendLine()
        sb.appendLine("== 상태 증가 ==")
        sb.appendLine("history 최대 ${r.maxHistory}, restLog 최대 ${r.maxRestLog}, lastOffer 최대 ${r.maxLastOffer}, 말 큐 최대 ${r.maxQueue}")
        sb.appendLine("2시간 중 관측한 EngineState JSON 최대 ${r.maxStateBytes} 바이트(1분마다 표본)")

        // 현실적 최대 상태: 열린 에피소드 + 휴식 기록 많음 + 28일 history
        var big = r.last
        big = big.copy(history = oldHistory() + big.history.takeLast(5))
        big.session?.let { x ->
            val log = (0 until 24).map { RestLogEntry(T0 + it * 5 * MIN, listOf(Sid.S07, Sid.S08, Sid.S10, Sid.S11)[it % 4], "r${it % 3 + 1}", YesNo.NO, null) }
            big = big.copy(session = x.copy(restLog = log))
        }
        val json = Json.encodeToString(EngineState.serializer(), big)
        repeat(3000) { Json.encodeToString(EngineState.serializer(), big) }
        val enc = LongArray(2000) { val a = System.nanoTime(); Json.encodeToString(EngineState.serializer(), big); System.nanoTime() - a }.also { it.sort() }
        val dec = LongArray(500) { val a = System.nanoTime(); Json.decodeFromString(EngineState.serializer(), json); System.nanoTime() - a }.also { it.sort() }
        sb.appendLine()
        sb.appendLine("== SaveSnapshot용 EngineState JSON (history ${big.history.size}줄, restLog ${big.session?.restLog?.size ?: 0}줄, 세션 ${if (big.session != null) "열림" else "없음"}) ==")
        sb.appendLine("크기 ${json.length} 바이트, 직렬화 중앙값 %.0f µs / p99 %.0f µs, 복원 중앙값 %.0f µs".format(enc[1000] / 1000.0, enc[1980] / 1000.0, dec[250] / 1000.0))

        sb.appendLine()
        sb.appendLine("== 2시간 동안 SaveSnapshot이 나올 횟수(상태가 바뀐 Step마다, 시간 값 now·quietMs만 바뀐 Step 제외) ==")
        sb.appendLine("(가) 문자 그대로(조향 필터값 steer도 상태이므로 VehicleSample마다 바뀜): ${r.snapsAny}번 → 시간당 ${r.snapsAny / 2}번")
        sb.appendLine("(나) steer 필터 값(filtered·lastSampleAt·extremumDeg·window·dir)만 바뀐 Step도 제외: ${r.snapsNoSteer}번 → 시간당 ${r.snapsNoSteer / 2}번")
        sb.appendLine("(나)의 사건별: ${r.by}")
        sb.appendLine("2시간 동안 낸 명령 수: ${r.cmds}")
        sb.appendLine("(다) Save*(Session·Episode·Play)·CloseSession 명령이 나온 Step만: ${r.snapsCoarse}번 → 시간당 ${r.snapsCoarse / 2}번")
        sb.appendLine("(가) 쓰기량 추정: 시간당 ${r.snapsAny / 2} × ${json.length} 바이트 ≈ ${(r.snapsAny / 2.0 * json.length / 1e6).toInt()} MB")

        val out = File(System.getenv("PERF_OUT") ?: "build/perf_engine.txt")
        out.parentFile?.mkdirs(); out.writeText(sb.toString())
        println(sb)
    }
}
