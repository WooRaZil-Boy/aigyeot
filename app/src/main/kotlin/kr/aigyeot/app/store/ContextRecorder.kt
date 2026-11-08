package kr.aigyeot.app.store

import kr.aigyeot.app.store.db.ContextSampleEntity
import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.VehicleSample

/**
 * 맥락 기록기(F17, 계약 4.3). 사건 흐름의 VehicleSample·CabinTemp·CrySegment·재생 상태를 1초 칸으로 묶어 최근 [halfMs]를 들고 있다가,
 * CaptureContext가 오면 경보 −30초 ~ +30초를 context_sample 줄로 낸다(+쪽은 그 초가 지날 때마다 낸다).
 * 차량 표본이나 기어를 아직 모르는 초는 줄을 만들지 않는다(없는 값을 지어내지 않는다).
 */
class ContextRecorder(private val halfMs: Long = 30_000, private val stepMs: Long = 1_000) {
    private data class Second(val at: Long, val v: VehicleSample, val gear: String, val cabinC: Double?, val playback: String?, val cryP: Double?)

    private val recent = ArrayDeque<Second>()
    private val pending = mutableMapOf<String, Long>()   // episodeId → alarmAt
    private var bucket = Long.MIN_VALUE                    // 지금 채우는 초의 시작(Second.at은 그 끝)
    private var vehicle: VehicleSample? = null
    private var gear: String? = null
    private var cabin: Double? = null
    private var playback: String? = null
    private var cryP: Double? = null                       // 이 초의 최대 울음 확률

    /** 사건 하나를 넣는다. 초가 넘어가면 앞 초를 마감하고, 그 초를 기다리던 캡처의 줄을 돌려준다. */
    fun observe(e: Event, s: EngineState): List<ContextSampleEntity> {
        val sec = e.at - Math.floorMod(e.at, stepMs)
        val out = if (sec > bucket && bucket != Long.MIN_VALUE) close() else emptyList()
        if (sec > bucket) { bucket = sec; cryP = null }
        when (e) {
            is VehicleSample -> vehicle = e
            is CrySegment -> cryP = maxOf(cryP ?: 0.0, e.p)
            else -> {}
        }
        gear = s.car.gear?.name
        cabin = s.car.cabinC
        playback = s.session?.play?.takeIf { it.startedAt != null }?.let { "${it.method.kind}:${it.mode}" }
        return out
    }

    /** 경보 −30초부터 지금까지 마감된 줄을 돌려주고, +30초까지는 마감될 때마다 [observe]가 낸다. */
    fun capture(episodeId: String, alarmAt: Long): List<ContextSampleEntity> {
        pending[episodeId] = alarmAt
        return recent.filter { it.at >= alarmAt - halfMs && it.at <= alarmAt + halfMs }.map { it.row(episodeId) }
    }

    /**
     * 모든 기록 지우기(5.9): 메모리 버퍼와 기다리는 캡처, 작성 중인 칸을 버린다.
     * 차량·기어·실내 온도 같은 지금 차 상태 값은 기록이 아니라 남겨도 된다.
     */
    fun clear() { recent.clear(); pending.clear(); bucket = Long.MIN_VALUE; cryP = null }

    /** 지운 에피소드의 기다리는 캡처를 버린다(C262). */
    fun drop(episodeId: String) { pending.remove(episodeId) }

    /**
     * 장면 경계(수명 표 장면 시작·끝의 맥락 캡처, C319): 작성 중인 칸은 경계 앞 세계의 값이라 마감해 기다리는 캡처의 줄로 돌려주고, 캡처는 거기서 끝낸다
     * (그 뒤 칸은 다른 세계 표본이라 쓰지 않는다). 그다음 [clear]에 더해 차량·기어·실내 온도·재생 값도 비운다(장면 값을 다음 칸이 이어받지 않게).
     */
    fun reset(): List<ContextSampleEntity> {
        val rows = if (bucket != Long.MIN_VALUE) close() else emptyList()
        clear(); vehicle = null; gear = null; cabin = null; playback = null
        return rows
    }

    private fun close(): List<ContextSampleEntity> {
        val v = vehicle ?: return emptyList()
        val g = gear ?: return emptyList()
        // 줄의 at은 칸의 끝이다. 칸을 마감할 때 들고 있는 값은 그 초의 끝 무렵 값이라, 시작 시각으로 적으면
        // 경보보다 뒤의 상태(경보 0.1초 뒤 시작한 재생, 바뀐 기어)가 '경보 때' 값으로 들어간다. 끝 시각으로 적으면
        // preCry(at <= 경보)와 캡처 범위(±30초)는 그 시각까지 관측한 값만 고른다
        val done = Second(bucket + stepMs, v, g, cabin, playback, cryP)
        recent.addLast(done)
        while (recent.first().at < done.at - halfMs) recent.removeFirst()
        val rows = pending.filter { (_, a) -> done.at >= a - halfMs && done.at <= a + halfMs }.map { (id, _) -> done.row(id) }
        pending.entries.removeAll { (_, a) -> done.at >= a + halfMs }
        return rows
    }

    private fun Second.row(episodeId: String) = ContextSampleEntity(
        episodeId, at, v.speedKmh, v.brake, v.steeringDeg, v.turnSignal.name, gear, cabinC, windowPos = null, playback, cryP,
    )
}
