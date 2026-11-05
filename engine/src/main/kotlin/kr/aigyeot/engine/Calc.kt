package kr.aigyeot.engine

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.roundToInt

// 계약 2.3 판단 코어 안의 순수 함수. 난수는 EngineState.rng(SplitMix64 상태) 하나뿐이다(2.1).

/** SplitMix64 한 걸음. (다음 상태, 출력). */
fun splitMix64(state: Long): Pair<Long, Long> {
    val next = state + 0x9E3779B97F4A7C15uL.toLong()
    var z = next
    z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
    z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
    return next to (z xor (z ushr 31))
}

/** Beta(a, b) 표본. 모양이 정수라 감마 = −Σ ln U(U는 (0, 1] 균등)로 만든다(2.1). (다음 rng, 표본). */
fun sampleBeta(a: Int, b: Int, rng: Long): Pair<Long, Double> {
    var r = rng
    fun gamma(k: Int): Double {
        var g = 0.0
        repeat(k) {
            val (n, x) = splitMix64(r); r = n
            g -= ln(((x ushr 11) + 1) * (1.0 / (1L shl 53)))
        }
        return g
    }
    val ga = gamma(a)
    val gb = gamma(b)
    return r to if (ga + gb == 0.0) 0.5 else ga / (ga + gb)
}

/** 처음 순서(백색소음 → 자장가 → 부모 노래). 같은 종류는 받은 순서 그대로. */
private fun ordered(methods: List<Method>) = methods.sortedBy { it.kind.ordinal }

data class Pick(val method: Method, val rng: Long)

/** 5장 처음 6번(G27)·그다음 톰슨 샘플링. `history`는 판정된 1차 시도(28일 창은 세션을 열 때 거른다). */
fun pickFirst(methods: List<Method>, history: List<FirstTry>, rng: Long, perMethod: Int = 2): Pick {
    val ms = ordered(methods)
    val tries = ms.associateWith { m -> history.count { it.method == m } }
    val few = ms.filter { tries.getValue(it) < perMethod }
    if (few.isNotEmpty()) return Pick(few.minBy { tries.getValue(it) }, rng)
    var r = rng
    var pick = ms.first()
    var top = -1.0
    for (m in ms) {
        val ok = history.count { it.method == m && it.success }
        val (n, x) = sampleBeta(1 + ok, 1 + tries.getValue(m) - ok, r); r = n
        if (x > top) { top = x; pick = m }
    }
    return Pick(pick, r)
}

/** 5장 성적: (1+성공)/(2+시도)가 가장 큰 것, 동점이면 처음 순서. `except`는 2차의 '1차와 다른 음원'. */
fun best(methods: List<Method>, history: List<FirstTry>, except: Method? = null): Method {
    val ms = ordered(methods).filter { it != except }.ifEmpty { ordered(methods) }
    return ms.maxBy { m ->
        val n = history.count { it.method == m }
        (1.0 + history.count { it.method == m && it.success }) / (2.0 + n)
    }
}

/** 10장 수유 시간대. 다음 입력은 `feedingStart`를 바꾸므로 끝은 시작 + 간격 × 2만 본다. */
fun feedingWindow(feedingStart: Long?, intervalMin: Int?, now: Long, p: Params = Params()): Boolean {
    if (feedingStart == null || intervalMin == null || intervalMin <= 0) return false
    val iv = intervalMin * 60_000L
    return now >= feedingStart + (iv * p.feedingWindowStart).toLong() &&
        now < feedingStart + (iv * p.feedingWindowEnd).toLong()
}

/** 4.3 정차 장소 고르기: 앞쪽 30km 안에서 가장 가까운 곳, 수유실 휴게소가 그 + 5km 안이면 그곳. */
fun pickRest(route: RouteView?, p: Params = Params()): RestCandidate? {
    val near = route?.rests.orEmpty().filter { it.distanceKm in 0.0..p.restSearchKm }.sortedBy { it.distanceKm }
    val first = near.firstOrNull() ?: return null
    return near.firstOrNull { it.hasNursingRoom && it.distanceKm <= first.distanceKm + p.nursingExtraKm } ?: first
}

/** 목적지가 있고 남은 시간이 10분 이하(4.3 '남은 시간 먼저'). */
fun nearDestination(route: RouteView?, p: Params = Params()): Boolean =
    route?.hasDestination == true && route.remainingSec != null && route.remainingSec <= p.arrivalNoticeMaxSec

/**
 * 4.1·4.2 ②·4.3·4.5 쉼터 제안 문장. P면, 목적지까지 10분 이하면, 같은 문장을 10분 안에 냈으면 null.
 * 쉼터가 있으면 '응/아니' 질문(`askRestId`), 없으면 S10. `longDrive`면 S11, 아니면 수유실 S07·일반 S08.
 */
fun offerRest(
    longDrive: Boolean, gear: Gear?, route: RouteView?, lastOffer: Map<Sid, Long>, now: Long, driveFrom: Long?,
    p: Params = Params(),
): Line? {
    if (gear == Gear.P || nearDestination(route, p)) return null
    val rest = pickRest(route, p)
    val line = if (rest == null) Line(Sid.S10, emptyMap(), null, longDrive = longDrive) else {
        val sid = if (longDrive) Sid.S11 else if (rest.hasNursingRoom) Sid.S07 else Sid.S08
        val vars = mutableMapOf("distanceKm" to km(rest.distanceKm), "restName" to rest.name)
        if (longDrive) vars["drove"] = hm(now - (driveFrom ?: now))
        Line(sid, vars, rest.id, longDrive = longDrive)
    }
    val last = lastOffer[line.sid]
    return if (last != null && now - last < p.offerRepeatMs) null else line
}

data class SteerOut(val state: SteerState, val closed: SteerWindowRecord?)

/**
 * 기준 문서 3.4(F9)·C38 조향 반전. 0.6 Hz 1차 저역통과 → 문턱 3° 반전 수를 60초 창에 더한다.
 * 표본이 창 시작 + 60초 이상이면 창을 닫아 기록을 돌려주고 이 표본으로 새 창을 연다.
 */
fun steer(
    st: SteerState, sample: VehicleSample, cryPresent: Boolean, playing: Boolean, sessionId: String,
    p: Params = Params(),
): SteerOut {
    var w = st.window
    var closed: SteerWindowRecord? = null
    if (w != null && sample.at >= w.startAt + p.steerWindowMs) {
        val avg = w.speedSumKmh / maxOf(1, w.speedCount)
        closed = SteerWindowRecord(sessionId, w.startAt, (avg / 20).toInt(), w.excluded, w.cryPresent, w.playing, w.reversals)
        w = null
    }
    val acc = w ?: SteerWindowAcc(sample.at, 0.0, 0, 0, false, false, false)
    val x = sample.steeringDeg
    val prev = st.filtered
    val f = if (prev == null || st.lastSampleAt == null) x else {
        val dt = (sample.at - st.lastSampleAt).coerceAtLeast(0) / 1000.0
        val rc = 1 / (2 * PI * p.steerLowpassHz)
        prev + dt / (rc + dt) * (x - prev)
    }
    var ext = st.extremumDeg ?: f
    var dir = st.dir
    var rev = 0
    val thr = p.steerReversalDeg
    when (dir) {
        // 첫 방향은 잡음을 넘는 작은 움직임으로만 정하고 극값을 따라간다. 반전에만 3° 문턱을 쓴다.
        0 -> if (f - ext >= p.steerDirMinDeg) { dir = 1; ext = f } else if (ext - f >= p.steerDirMinDeg) { dir = -1; ext = f }
        1 -> if (f > ext) ext = f else if (ext - f >= thr) { rev = 1; dir = -1; ext = f }
        else -> if (f < ext) ext = f else if (f - ext >= thr) { rev = 1; dir = 1; ext = f }
    }
    val next = acc.copy(
        speedSumKmh = acc.speedSumKmh + sample.speedKmh, speedCount = acc.speedCount + 1,
        reversals = acc.reversals + rev, excluded = acc.excluded || sample.turnSignal != TurnSignal.NONE,
        cryPresent = acc.cryPresent || cryPresent, playing = acc.playing || playing,
    )
    return SteerOut(st.copy(filtered = f, lastSampleAt = sample.at, extremumDeg = ext, dir = dir, window = next), closed)
}

// ---- 문장 채울 값(C29) ----
internal fun km(d: Double) = d.roundToInt().toString()
internal fun hm(ms: Long): String {
    val m = ms.coerceAtLeast(0) / 60_000
    return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
}
internal fun methodName(m: Method) = when (m.kind) {
    MethodKind.WHITE_NOISE -> "백색소음"
    MethodKind.LULLABY -> "자장가"
    MethodKind.SONG -> "우리 노래"
}

private const val B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** ULID 모양 id(세션·에피소드, contracts-link). 앞 48비트 = 시각, 뒤 80비트 = 순번이라 시드·사건이 같으면 같다. */
internal fun ulid(ms: Long, n: Long): String = buildString(26) {
    for (i in 9 downTo 0) append(B32[((ms ushr (5 * i)) and 31).toInt()])
    for (i in 15 downTo 0) append(B32[if (5 * i >= 64) 0 else ((n ushr (5 * i)) and 31).toInt()])
}
