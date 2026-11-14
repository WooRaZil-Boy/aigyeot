package kr.aigyeot.app.exec

import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.engine.Area
import kr.aigyeot.engine.ControlKind
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FocusChange
import kr.aigyeot.engine.Method

/** 가짜 시계 겸 예약. advance가 시각 순서대로 예약을 돌린다. */
internal class FakeTime(var now: Long = 1_000_000L) : Sched {
    private class Job(val at: Long, val seq: Long, val f: () -> Unit) {
        var dead = false
    }
    private val jobs = mutableListOf<Job>()
    private var seq = 0L
    override fun after(ms: Long, task: () -> Unit): () -> Unit {
        val j = Job(now + maxOf(ms, 0), seq++, task)
        jobs += j
        return { j.dead = true }
    }
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val j = jobs.filter { !it.dead && it.at <= end }.minWithOrNull(compareBy<Job>({ it.at }, { it.seq })) ?: break
            jobs.remove(j)
            now = j.at
            j.f()
        }
        now = end
    }
    fun pending() = jobs.count { !it.dead }
}

internal class Rec {
    val events = mutableListOf<Event>()
    val rows = mutableListOf<ControlLogEntity>()
    inline fun <reified T : Event> of() = events.filterIsInstance<T>()
}

internal fun logger(t: FakeTime, r: Rec) = ControlLogger({ t.now }, { "s1" }, { r.rows += it })

internal class FakeTrack(val method: Method, val rear: Boolean, var routed: String?) : Track {
    val gains = mutableListOf<Float>()
    var started = false
    var paused = false
    var released = false
    var throwGain = false
    var gainBeforeStart = false // start() 전에 gain()이 불렸는가(첫 소리가 이득 1.0으로 새는 길)
    override fun start(): String? {
        started = true
        return routed
    }
    override fun routed(): String? = routed
    override fun gain(g: Float) {
        if (throwGain) error("setVolume 실패")
        if (!started) gainBeforeStart = true
        gains += g
    }
    override fun pause() {
        paused = true
    }
    override fun resume() {
        paused = false
    }
    override fun release() {
        released = true
    }
}

internal class FakeAudio(
    var throwRear: Boolean = false,
    var throwOpen: Boolean = false,
    var throwFocus: Boolean = false,
    var throwGain: Boolean = false,
    var rear: Boolean = true,
    var focusOk: Boolean = true,
    var openOk: Boolean = true,
    var routed: String? = REAR_ZONE
) : AudioPort {
    var focusHeld = false
    var focusRequests = 0
    var onFocus: ((FocusChange) -> Unit)? = null
    var onRouted: ((String?) -> Unit)? = null
    var onError: (() -> Unit)? = null
    val tracks = mutableListOf<FakeTrack>()
    override fun hasRearOutput() = if (throwRear) error("getDevices 실패") else rear
    override fun requestFocus(onChange: (FocusChange) -> Unit): Boolean {
        focusRequests++
        if (throwFocus) error("포커스 실패")
        if (!focusOk) return false
        focusHeld = true
        onFocus = onChange
        return true
    }
    override fun abandonFocus() {
        focusHeld = false
    }
    override fun open(method: Method, rear: Boolean, onRouted: (String?) -> Unit, onError: () -> Unit): Track? {
        if (throwOpen) error("open 실패")
        if (!openOk) return null
        this.onRouted = onRouted
        this.onError = onError
        return FakeTrack(method, rear, if (rear) routed else "bus0_media_out").also {
            it.throwGain = throwGain
            tracks += it
        }
    }
}

/** 차 제어 가짜. 쓰면 [applyAfterMs] 뒤 값이 바뀐다(null이면 안 바뀜). 읽은 시각을 남긴다. */
internal class FakeControl(private val t: FakeTime) : ControlPort {
    val values = mutableMapOf<Pair<ControlKind, Area>, Double>()
    var applyAfterMs: Long? = 300
    var ackOk = true
    var readOk = true
    var throwRead = false
    var minC: Double? = 15.0 // 차가 알려 주는 공조 설정 최솟값(null = 못 읽음)
    override fun minTemp(area: Area, cb: (Double?) -> Unit) {
        cb(minC)
    }
    var throwWrite = false
    val writes = mutableListOf<Triple<ControlKind, Area, Double>>()
    val reads = mutableListOf<Long>()
    override fun read(kind: ControlKind, area: Area, cb: (Double?) -> Unit) {
        reads += t.now
        if (throwRead) error("read 실패")
        cb(
            if (readOk) {
                values[
                    kind to
                        area
                ]
            } else {
                null
            }
        )
    }
    override fun write(kind: ControlKind, area: Area, value: Double, ack: (Boolean) -> Unit) {
        writes += Triple(kind, area, value)
        if (throwWrite) error("write 실패")
        t.after(50) { ack(ackOk) }
        applyAfterMs?.let { t.after(it) { values[kind to area] = value } }
    }
}
