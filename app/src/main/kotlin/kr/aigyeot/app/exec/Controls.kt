package kr.aigyeot.app.exec

import kotlin.math.abs
import kr.aigyeot.app.store.db.ControlApi
import kr.aigyeot.engine.AddWaypoint
import kr.aigyeot.engine.Area
import kr.aigyeot.engine.ControlKind
import kr.aigyeot.engine.ControlResult
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.LowerHvac
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.SetReadingLight
import kr.aigyeot.engine.SetSeatHeat
import kr.aigyeot.engine.SetWindowLock
import kr.aigyeot.engine.WaypointResult

/**
 * 차 제어 넷과 경유지. 명령 뒤 0.1초부터 0.15초 간격으로 다시 읽고, 5초 안에 목표가 안 되면 한 번 다시 보내고,
 * 그래도 안 되면 ok = false. 영역마다 ControlResult 하나와 control_log 한 줄. ok는 쓰기 성공 콜백과 다시 읽은 값이 모두 맞을 때만이다
 * (거절된 명령은 값이 우연히 같아도 실패, 프로브 measure와 같은 정의).
 */
internal class Controls(
    private val port: ControlPort,
    private val navi: NaviPort,
    private val sched: Sched,
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    private val log: ControlLogger,
    private val p: Params,
    // 경로 상태의 쉼터(경유지 좌표 찾기)
    private val restOf: (String) -> RestCandidate? = { null }
) {
    fun seatHeat(c: SetSeatHeat) = c.areas.forEach { verify(c.cmdId, ControlKind.SEAT_HEAT, it, c.level.toDouble()) }
    fun windowLock(c: SetWindowLock) = c.values.forEach { (a, v) -> verify(c.cmdId, ControlKind.WINDOW_LOCK, a, if (v) 1.0 else 0.0) }
    fun readingLight(c: SetReadingLight) = c.areas.forEach { verify(c.cmdId, ControlKind.READING_LIGHT, it, if (c.on) 1.0 else 0.0) }

    /** 목표 = max(지금 설정 온도 + deltaC, 설정 최솟값). 설정 온도를 읽지 못하면 보내지 않고 실패. */
    fun lowerHvac(c: LowerHvac) = c.areas.forEach { verify(c.cmdId, ControlKind.HVAC_TEMP, it, null, c.deltaC) }

    private inner class Job(val cmdId: String, val kind: ControlKind, val area: Area) {
        val t0 = clock()
        var target: Double? = null
        var before: Double? = null
        var last: Double? = null
        var acked: Boolean? = null
        var ackMs: Long? = null
        var attempt = 0
        var done = false
        val timers = mutableListOf<() -> Unit>()
    }

    /** [target]이 null이면 바꾸기 전 값 + [delta]가 목표다(LowerHvac). 이 경우 목표는 max(그 값, 설정 최솟값)이고 이미 최솟값 이하면 쓰지 않고 실패. */
    private fun verify(cmdId: String, kind: ControlKind, area: Area, target: Double?, delta: Double = 0.0) {
        val j = Job(cmdId, kind, area)
        jobs += j
        val lower = target == null
        var gotBefore = false
        var before: Double? = null
        var beforeIn = false
        var lo: Double? = null
        var loIn = !lower
        fun go() {
            if (gotBefore || !beforeIn || !loIn) return
            gotBefore = true
            j.before = before
            if (target != null) {
                j.target = target
            } else {
                val base = before ?: return finish(j, false)
                val min = lo ?: p.hvacMinC // 차가 범위를 안 주면 Params.hvacMinC
                j.target = maxOf(base + delta, min)
                if (base <= min) {
                    j.last = base
                    return finish(j, false)
                } // 이미 최솟값 이하: 쓰지 않는다. before = after = 지금 값
            }
            send(j, 0)
        }
        j.timers += sched.after(p.verifyTimeoutMs) {
            beforeIn = true
            loIn = true
            go()
        } // 바꾸기 전 값을 끝내 못 읽으면 모르는 채로 보낸다
        safeRead(kind, area) {
            before = it
            beforeIn = true
            go()
        }
        if (lower) {
            try {
                port.minTemp(area) {
                    lo = it
                    loIn = true
                    go()
                }
            } catch (e: Exception) {
                loIn = true
                go()
            }
        }
    }

    /** 포트가 던지면 못 읽은 것(null)으로 본다. 던진 예외가 Exec까지 올라가 결과를 두 번 내지 않게. */
    private fun safeRead(kind: ControlKind, area: Area, cb: (Double?) -> Unit) = try {
        port.read(kind, area, cb)
    } catch (e: Exception) {
        cb(null)
    }

    private fun send(j: Job, n: Int) {
        if (j.done) return
        j.timers.forEach { it() }
        j.timers.clear()
        j.attempt = n
        j.acked = null
        val want = j.target!!
        val ack = { ok: Boolean ->
            if (!j.done && j.attempt == n) {
                j.acked = ok // 거절이면 이 시도는 성공할 수 없다. 5초 타이머가 다시 보낸다
                j.ackMs = clock() - j.t0
            }
        }
        try {
            port.write(j.kind, j.area, want, ack)
        } catch (e: Exception) {
            ack(false)
        }
        fun poll() {
            if (j.done || j.attempt != n) return
            safeRead(j.kind, j.area) { v ->
                if (j.done || j.attempt != n) return@safeRead
                if (v != null) j.last = v
                if (j.acked == true && v != null && same(j.kind, v, want)) finish(j, true)
            }
            if (!j.done && j.attempt == n) j.timers += sched.after(p.verifyIntervalMs) { poll() }
        }
        j.timers += sched.after(p.verifyFirstMs) { poll() }
        j.timers += sched.after(p.verifyTimeoutMs) { if (!j.done && j.attempt == n) next(j, n) }
    }

    private fun next(j: Job, n: Int) = if (n < p.verifyRetries) send(j, n + 1) else finish(j, false)

    private val jobs = mutableSetOf<Job>() // 진행 중인 제어 작업(closeAll이 닫는다)

    /** Exec.stop: 진행 중인 모든 제어 작업을 ok=false로 닫고(타이머 취소) 기다리는 경유지도 닫는다. 늦은 콜백은 끝난 작업이라 무시된다. */
    fun closeAll(why: String) {
        jobs.toList().forEach { finish(it, false) }
        wp?.let { wpDone(it, false, why) }
    }

    private fun finish(j: Job, ok: Boolean) {
        if (j.done) return
        j.done = true
        jobs -= j
        j.timers.forEach { it() }
        log.row(
            j.cmdId, API.getValue(j.kind), j.area.name, j.target?.let(::fmt) ?: "unknown", j.last?.let(::fmt), ok, j.ackMs,
            if (ok) clock() - j.t0 else null, j.attempt
        )
        post(ControlResult(clock(), j.cmdId, j.kind, j.area, ok, j.before, j.last))
    }

    private fun same(kind: ControlKind, v: Double, want: Double) = if (kind == ControlKind.HVAC_TEMP) abs(v - want) < 0.05 else v == want
    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

    // ---- 경유지('응이면 addWaypoint 후 S09', 결과 콜백으로만 성공) ----

    private inner class Wp(val c: AddWaypoint) {
        val t0 = clock()
        var cancel: () -> Unit = {}
    }
    private var wp: Wp? = null

    /** 재시도 예외: 결과 콜백으로만 확인, 다시 보내지 않음, [Params.waypointTimeoutMs](20초). */
    fun waypoint(c: AddWaypoint) {
        wp?.let { wpDone(it, false, "superseded") }
        val w = Wp(c).also { wp = it }
        w.cancel = sched.after(p.waypointTimeoutMs) { wpDone(w, false, "timeout") }
        try {
            navi.addWaypoint(c.restId, restOf(c.restId)) { ok, note -> wpDone(w, ok, note) }
        } catch (e: Exception) {
            wpDone(w, false, "error")
        }
    }

    private fun wpDone(w: Wp, ok: Boolean, note: String) {
        if (wp !== w) return
        wp = null
        w.cancel()
        try {
            navi.cancelWaypoint()
        } catch (e: Exception) { /* 닫는 일은 결과를 막지 않는다 */ }
        val ms = clock() - w.t0
        log.row(w.c.cmdId, "addWaypoint", w.c.restId, "added", note, ok, ms, if (ok) ms else null)
        post(WaypointResult(clock(), w.c.cmdId, w.c.restId, ok))
    }

    companion object {
        private val API = mapOf(
            ControlKind.SEAT_HEAT to ControlApi.SEAT_HEAT,
            ControlKind.WINDOW_LOCK to ControlApi.WINDOW_LOCK,
            ControlKind.READING_LIGHT to ControlApi.READING_LIGHT,
            ControlKind.HVAC_TEMP to ControlApi.HVAC_TEMP,
            ControlKind.AMBIENT_BRIGHTNESS to ControlApi.AMBIENT_BRIGHTNESS, // 앰비언트는 Controls가 아니라 Ambients가 쓴다(영역이 capability). 이름은 control_log와 같다
            ControlKind.AMBIENT_COLOR to ControlApi.AMBIENT_COLOR
        )
    }
}
