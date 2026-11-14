package kr.aigyeot.app.detect

import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.Params

// YAMNet 창 점수를 받아 3창 구간(0.975초씩 밀림)마다 CrySegment를 만든다.
// 경보 = 문턱 이상 구간이 alarmSegments(2)개 이어짐: 이 구간과 바로 앞 구간(rt_train.py alarm). 시계·스레드·안드로이드가 없어 JVM에서 시험한다.
// 문턱·구간 수는 판단 코어 Params 하나(판단 코어가 같은 문턱으로 재울음·울음 시간·조향을 본다)
class SegmentTracker(private val head: CryHead, private val p: Params = Params()) {
    private class Win(val scores: FloatArray, val endMs: Long)
    private val wins = ArrayDeque<Win>()
    private var emitted = false
    private var loudRun = 0   // 문턱 이상 구간이 이어진 수

    /** 이음이 끊길 때(입력 끊김) 부른다. 끊기기 전 창·앞 구간 확률은 버린다. */
    fun reset() { wins.clear(); emitted = false; loudRun = 0 }

    /** 창 하나(끝 시각 [endMs])를 넣는다. 3창이 모이면 구간을 돌려준다. [at]은 사건 시각. */
    fun push(scores: FloatArray, endMs: Long, at: Long, injectedCryAt: Long? = null): CrySegment? {
        wins.addLast(Win(scores, endMs))
        if (wins.size > Detect.SEGMENT_WINDOWS) wins.removeFirst()
        if (wins.size < Detect.SEGMENT_WINDOWS) return null
        val w = wins.toList()
        return make(w.map { it.scores }, w.first().endMs - Detect.WINDOW_MS, w.last().endMs, at, injectedCryAt)
    }

    /**
     * 입력이 끝났는데 창이 1~2개뿐이면 rt_train.py segs처럼 앞 창을 반복해 3창으로 채운 구간 하나를 낸다(2창이면 1·2·1).
     * 앞 구간이 없어 경보는 아니다. 끝이 없는 마이크에는 쓰지 않는다(녹음 파일 입력용).
     */
    fun flushShort(at: Long, injectedCryAt: Long? = null): CrySegment? {
        if (emitted || wins.isEmpty() || wins.size >= Detect.SEGMENT_WINDOWS) return null
        val list = wins.toList()
        // 점수만 반복한다. 시각은 실제로 들어온 입력 범위
        return make(List(Detect.SEGMENT_WINDOWS) { list[it % list.size].scores }, list.first().endMs - Detect.WINDOW_MS, list.last().endMs, at, injectedCryAt)
    }

    private fun make(scores: List<FloatArray>, start: Long, end: Long, at: Long, injectedCryAt: Long?): CrySegment {
        val prob = head.prob(scores)
        loudRun = if (prob >= p.alarmThreshold) loudRun + 1 else 0
        emitted = true
        return CrySegment(at, start, end, prob, loudRun >= p.alarmSegments, injectedCryAt)
    }
}
