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

/** 처음 순서(백색소음 → 뱃속 소리 → 자장가 → 부모 노래, enum 선언 순서). 같은 종류는 받은 순서 그대로. */
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
    return ms.maxBy { m -> methodScore(history.count { it.method == m && it.success }, history.count { it.method == m }) }
}

/** C303 A18: 방법별 성적 점수 (1+성공)/(2+시도). 2차 고르기·④ sound_stats가 같이 쓴다. */
fun methodScore(successes: Int, attempts: Int): Double = (1.0 + successes) / (2.0 + attempts)

const val MINUTE_MS = 60_000L
const val DAY_MS = 86_400_000L   // C303 A24: 하루. 앱(Wire.DAY)도 이 값을 쓴다

/** C303 A18: 성적·history 창(28일)의 시작. 세션 열기·Room history·리포트·홈 화면이 같이 쓴다. */
fun Params.statSince(now: Long): Long = now - statWindowDays * DAY_MS

/**
 * C320 V17: [methods] 각각의 28일 안 1차 시도 성적(성공 수·시도 수). ④ sound_stats와 홈 '방법별 성적'이 같이 쓴다(리포트는 Room에서 같은 [statSince]로 센다).
 * 창 시작 시각(`at == statSince`)은 안에 든다. 노래 방법은 곡(songId)까지 같은 시도만 센다(Method 동등).
 */
fun methodStats(history: List<FirstTry>, methods: List<Method>, now: Long, p: Params = Params()): List<Pair<Method, Pair<Int, Int>>> {
    val since = p.statSince(now)
    val recent = history.filter { it.at >= since }
    return methods.map { m -> recent.filter { it.method == m }.let { r -> m to (r.count { it.success } to r.size) } }
}

/** C320 V13(P5): cmdId·playId(`{세션 id}-{seq}`, Tx.newId)에서 낸 때의 세션 id. 세션 밖에서 낸 id(`idle-…`)와 모양이 다른 id는 null. */
fun sessionIdOf(ref: String): String? = ref.substringBeforeLast('-', "").takeIf { it.isNotEmpty() && it != "idle" }

/** C303 A1: 이어진 경보(G3) 문턱 두 걸음. 경보는 한 걸음(975ms)마다 오고, 조용한 구간이 끼면 최소 세 걸음 뒤에 온다. 판단 코어 `alarmRun`·④ 지금 울음·`alarm_log`가 같이 쓴다. */
fun alarmContinues(last: Long, at: Long, p: Params = Params()): Boolean = at - last <= 2 * p.yamnetHopMs

/** C303 A14: 수유 시작 시각은 더 나중일 때만 바꾼다(F1·F6, 계약 4.3). */
fun laterFeeding(cur: Long?, new: Long): Boolean = cur == null || new > cur

/** C303 A15: 다음 수유 시간대 시작 예상(10장, feeding_state `window_start_expected`). */
fun windowStartExpected(feedingStart: Long?, intervalMin: Int?, p: Params = Params()): Long? =
    if (feedingStart != null && intervalMin != null) feedingStart + (intervalMin * MINUTE_MS * p.feedingWindowStart).toLong() else null

/** C303 C4: 배고플 시각 = 마지막 수유 시작 + 간격(C117). */
fun hungryAt(feedingStart: Long, intervalMin: Int): Long = feedingStart + intervalMin * MINUTE_MS

/** C303 C4: 출발 수유실 제안(S07_DEPART)의 채울 값. */
internal fun departVars(rest: RestCandidate, hungryAt: Long) = mapOf("hungryAt" to hungryAt.toString(), "distanceKm" to km(rest.distanceKm), "restName" to rest.name)

/** C303 C3: 목적지까지 남은 분(올림). */
fun remainingMinutes(route: RouteView?): Int? = route?.remainingSec?.let { (it + 59) / 60 }

/**
 * 결정 ②(S3·S3a·F5i, V5): 그 제안 문장을 말한 지 offerRepeatMs 안이거나([spokenAt] = 말한 시각만), 이 세션 큐(말하는 맨 앞 줄 포함)에
 * 같은 문장 줄이 있으면 막힘. 버린 줄은 큐에 없으니 금지를 남기지 않는다. S07과 S07_DEPART는 서로 묶어 본다(S3a).
 * [except]: 다시 고르는 그 줄과 겹쳐 넘겨받는 줄(같은 객체로 견준다). 세션 id가 없는 줄은 이 세션 것으로 본다(overlap과 같다).
 */
fun offerBanned(
    spokenAt: Map<Sid, Long>, queue: List<Line>, sessionId: String?, sid: Sid, now: Long, p: Params = Params(), except: List<Line?> = emptyList(),
): Boolean {
    val sids = if (sid == Sid.S07 || sid == Sid.S07_DEPART) setOf(Sid.S07, Sid.S07_DEPART) else setOf(sid)
    return sids.any { k -> spokenAt[k]?.let { now - it < p.offerRepeatMs } == true } ||
        queue.any { l -> l.sid in sids && (l.sessionId == null || l.sessionId == sessionId) && except.none { it === l } }
}

/** C303 A22: 공백을 뺀 글자가 두 자 미만이면 듣지 않은 것(Q3). null = 들은 말 없음. 실행기 듣기와 판단 코어가 같이 쓴다. */
fun heardText(text: String?): String? = text?.takeIf { s -> s.count { !it.isWhitespace() } >= 2 }

/** C303 B11: 말 견주기 정규화. 글자·숫자만 남기고 끝의 '요' 한 글자를 뗀다(Q4 그만, '응/아니' 낱말, C109). */
fun plainWord(text: String): String = text.filter { it.isLetterOrDigit() }.removeSuffix("요")

/** 10장 수유 시간대. 다음 입력은 `feedingStart`를 바꾸므로 끝은 시작 + 간격 × 2만 본다. */
fun feedingWindow(feedingStart: Long?, intervalMin: Int?, now: Long, p: Params = Params()): Boolean {
    if (feedingStart == null || intervalMin == null || intervalMin <= 0) return false
    return now >= windowStartExpected(feedingStart, intervalMin, p)!! &&
        now < feedingStart + (intervalMin * MINUTE_MS * p.feedingWindowEnd).toLong()
}

/** 4.3 정차 장소 고르기: 앞쪽 30km 안에서 가장 가까운 곳, 수유실 휴게소가 그 + 5km 안이면 그곳. */
fun pickRest(route: RouteView?, p: Params = Params()): RestCandidate? {
    // C304: 제안·④ 다음 쉼터는 넣을 수 있는 쉼터(addable)만 본다. 거르는 곳은 pickRest·pickDepartRest 둘뿐이다.
    val near = route?.rests.orEmpty().filter { it.addable && it.distanceKm in 0.0..p.restSearchKm }.sortedBy { it.distanceKm }
    val first = near.firstOrNull() ?: return null
    return near.firstOrNull { it.hasNursingRoom && it.distanceKm <= first.distanceKm + p.nursingExtraKm } ?: first
}

/**
 * 10장 출발할 때(C117). 배고플 시각 h = 마지막 수유 시작 + 간격이 [now + lead, now + 남은 시간]에 들고,
 * 휴게소 도착 예상 eta = now + 남은 시간 × (휴게소까지 km ÷ 남은 km)가 h − lead 이하인 수유실 휴게소 중 eta가 가장 늦은(같으면 가까운) 곳.
 */
fun pickDepartRest(route: RouteView?, feedingStart: Long?, intervalMin: Int?, now: Long, leadMin: Int): RestCandidate? {
    val km = route?.remainingKm ?: return null
    val sec = route.remainingSec ?: return null
    if (feedingStart == null || intervalMin == null || km <= 0.0) return null
    val h = hungryAt(feedingStart, intervalMin)
    val lead = leadMin * MINUTE_MS
    if (h < now + lead || h > now + sec * 1000L) return null
    fun eta(r: RestCandidate) = now + (sec * 1000.0 * r.distanceKm / km).toLong()
    return route.rests.filter { it.addable && it.hasNursingRoom && eta(it) <= h - lead }   // C304
        .sortedWith(compareByDescending<RestCandidate> { eta(it) }.thenBy { it.distanceKm }).firstOrNull()
}

/** 목적지가 있고 남은 시간이 10분 이하(4.3 '남은 시간 먼저'). */
fun nearDestination(route: RouteView?, p: Params = Params()): Boolean =
    route?.hasDestination == true && route.remainingSec != null && route.remainingSec <= p.arrivalNoticeMaxSec

/**
 * 4.1·4.2 ②·4.3·4.5 쉼터 제안 문장. P면, 목적지까지 10분 이하면 null. 10분 금지(S3·S3a)는 부르는 쪽이 [offerBanned]로 본다(결정 ②).
 * 쉼터가 있으면 '응/아니' 질문(`askRestId`), 없으면 S10. `longDrive`면 S11, 아니면 수유실 S07·일반 S08.
 */
fun offerRest(longDrive: Boolean, gear: Gear?, route: RouteView?, now: Long, driveFrom: Long?, p: Params = Params()): Line? {
    if (gear == Gear.P || nearDestination(route, p)) return null
    val rest = pickRest(route, p)
    return if (rest == null) Line(Sid.S10, emptyMap(), null, longDrive = longDrive) else {
        val sid = if (longDrive) Sid.S11 else if (rest.hasNursingRoom) Sid.S07 else Sid.S08
        val vars = mutableMapOf("distanceKm" to km(rest.distanceKm), "restName" to rest.name)
        if (longDrive) vars["drove"] = hm(now - (driveFrom ?: now))
        Line(sid, vars, rest.id, longDrive = longDrive)
    }
}

data class SteerOut(val state: SteerState, val closed: SteerWindowRecord?)

/**
 * 기준 문서 3.4(F9)·C38 조향 반전. 0.6 Hz 1차 저역통과 → 문턱 3° 반전 수를 60초 창에 더한다.
 * 표본이 창 시작 + 60초 이상이면 창을 닫아 기록을 돌려주고 이 표본으로 새 창을 연다.
 */
fun steer(
    st: SteerState, sample: VehicleSample, cryPresent: Boolean, playing: Boolean, sessionId: String,
    p: Params = Params(), observed: Boolean = true,
): SteerOut {
    var w = st.window
    var closed: SteerWindowRecord? = null
    if (w != null && sample.at >= w.startAt + p.steerWindowMs) {
        val avg = w.speedSumKmh / maxOf(1, w.speedCount)
        closed = SteerWindowRecord(sessionId, w.startAt, (avg / 20).toInt(), w.excluded, w.cryPresent, w.playing, w.reversals, w.observed)
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
        observed = acc.observed && observed,   // C312
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
    MethodKind.WOMB -> "뱃속 소리"
    MethodKind.LULLABY -> "자장가"
    MethodKind.SONG -> "우리 노래"
}

/** C303 A19: 화면·④·리포트에 쓰는 음원 이름. 말(methodName)과 부모 노래만 다르다("우리 노래" ↔ "부모 노래"). */
fun methodLabel(k: MethodKind) = if (k == MethodKind.SONG) "부모 노래" else methodName(Method(k))

private const val B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** ULID 모양 id(세션·에피소드, contracts-link). 앞 48비트 = 시각, 뒤 80비트 = 순번이라 시드·사건이 같으면 같다. */
internal fun ulid(ms: Long, n: Long): String = buildString(26) {
    for (i in 9 downTo 0) append(B32[((ms ushr (5 * i)) and 31).toInt()])
    for (i in 15 downTo 0) append(B32[if (5 * i >= 64) 0 else ((n ushr (5 * i)) and 31).toInt()])
}
