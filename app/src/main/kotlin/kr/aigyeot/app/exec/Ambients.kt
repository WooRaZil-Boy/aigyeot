package kr.aigyeot.app.exec

import kr.aigyeot.app.store.db.ControlApi
import kr.aigyeot.engine.AmbientValue
import kr.aigyeot.engine.ControlKind
import kr.aigyeot.engine.ControlResult
import kr.aigyeot.engine.DimAmbient
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.RestoreAmbient
import kotlin.math.roundToInt

/** 앰비언트 한 영역: capability의 SeatArea 하나와 밝기 범위. [handle]은 포트 구현이 쓰는 SDK 객체다. */
internal class AmbientArea(val target: String, val min: Int, val max: Int, val handle: Any)

/** 주행 모드 자동 색. NONE = 자동 색이 없는 차(capability가 ERROR_NOT_SUPPORTED_PROPERTY), OFF = 꺼짐, UNKNOWN = 켜짐이거나 모름. */
internal enum class AutoColor { NONE, OFF, UNKNOWN }

/** 앰비언트 조명 읽기·쓰기(Pleos Light, CAR_LIGHTS). 값은 밝기 정수, 색은 24비트 RGB(알파 뺌). 실패·못 읽음은 null·false. */
internal interface AmbientPort {
    fun area(cb: (AmbientArea?) -> Unit)
    /** 스위치 ON, 또는 AUTOMATIC이고 State가 ON일 때만 true. OFF·그 밖은 false, 읽기 실패는 null(Ambients가 재시도한 뒤 꺼짐으로 본다). */
    fun lit(a: AmbientArea, cb: (Boolean?) -> Unit)
    fun autoColor(cb: (AutoColor) -> Unit)
    fun readBrightness(a: AmbientArea, cb: (Int?) -> Unit)
    fun readColor(a: AmbientArea, cb: (Int?) -> Unit)
    fun writeBrightness(a: AmbientArea, v: Int, ack: (Boolean) -> Unit)
    fun writeColor(a: AmbientArea, argb: Int, ack: (Boolean) -> Unit)
}

/** 앰비언트를 쓸 수 없는 연결(시험의 기본값). 영역을 못 읽으니 낮춤·되돌림은 모두 쓰지 않고 끝난다. */
internal object NoAmbient : AmbientPort {
    override fun area(cb: (AmbientArea?) -> Unit) = cb(null)
    override fun lit(a: AmbientArea, cb: (Boolean?) -> Unit) = cb(false)
    override fun autoColor(cb: (AutoColor) -> Unit) = cb(AutoColor.UNKNOWN)
    override fun readBrightness(a: AmbientArea, cb: (Int?) -> Unit) = cb(null)
    override fun readColor(a: AmbientArea, cb: (Int?) -> Unit) = cb(null)
    override fun writeBrightness(a: AmbientArea, v: Int, ack: (Boolean) -> Unit) = ack(false)
    override fun writeColor(a: AmbientArea, argb: Int, ack: (Boolean) -> Unit) = ack(false)
}

/**
 * 앰비언트 조명 실행기(`DimAmbient`·`RestoreAmbient`). 명령마다 종류(밝기·색) 하나에 ControlResult 하나를 늘 낸다.
 * 쓴 종류는 다시 읽어 확인하고(control_log 한 줄), 쓰지 않은 종류(꺼짐·이미 낮음·같은 색·자동 색·운전자가 바꿈·읽기 실패)는
 * ok = false로 결과만 내고 control_log 줄은 쓰지 않는다(시도가 아니다, [note]에 한 줄).
 */
internal class Ambients(
    private val port: AmbientPort,
    private val sched: Sched,
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    private val log: ControlLogger,
    private val p: Params,
    private val ledger: LedgerStore = NoLedger,   // 실행기 낮춤 장부
    private val note: (String) -> Unit = {},
) {
    private val open = mutableSetOf<Task>()   // 진행 중인 종류별 작업(closeAll이 닫는다)
    /**
     * 종류별 차례: 한 종류(밝기·색)를 읽고 쓰는 작업은 한 번에 하나만 돈다(낮춤·되돌림·장부 되돌림 모두, 들어온 차례). 뒤 작업은 앞 작업이 끝난 뒤(썼든 건너뛰었든
     * 버려졌든) 그 종류의 지금 값을 새로 읽으며 시작한다. 그래서 되돌림이 반영되기 전에 낮춤이 낮은 값을 읽지 않고, 같은 종류 되돌림이 겹치면 뒤 것은 지금 값이 이미 set이 아니라 건너뛴다.
     */
    private val lanes = mapOf(ControlKind.AMBIENT_BRIGHTNESS to ArrayDeque<Task>(), ControlKind.AMBIENT_COLOR to ArrayDeque<Task>())
    private var holdNext = false   // abandon이 여럿을 닫는 동안은 다음 작업을 시작하지 않는다(닫힐 것을 읽지 않게)

    /**
     * 차례가 와서 할 일을 채운다(영역·켜짐·자동 색을 읽은 뒤). 차례 자체는 작업을 만들 때(명령을 받는 즉시) 이미 잡았다: 앞 명령이 영역을 읽는 동안 뒤 명령이 앞지르지 못한다.
     * 맨 앞이면 바로 시작한다.
     */
    private fun arm(t: Task, turn: () -> Unit) {
        if (t.done) return
        t.turn = turn
        startHead(t.kind)
    }

    private fun startHead(kind: ControlKind) {
        if (holdNext) return
        val h = lanes.getValue(kind).firstOrNull() ?: return
        if (h.started || h.done || h.turn == null) return   // 머리 작업이 아직 영역 등을 읽는 중이면 뒤 작업은 기다린다
        h.started = true
        h.turn?.invoke()
    }

    /** 읽기 재시도: 못 읽으면(null·던짐) [Params.verifyRetries]번 [Params.verifyIntervalMs] 뒤에 다시 읽는다. 읽기마다 이 함수 하나를 거친다. */
    private fun <T : Any> reading(f: ((T?) -> Unit) -> Unit, cb: (T?) -> Unit, n: Int = 0) {
        var done = false
        var cancel: () -> Unit = {}
        val once = { v: T? ->
            if (!done) {   // 늦게 온 옛 응답·시간 초과 뒤 응답은 무시한다
                done = true; cancel()
                if (v == null && n < p.verifyRetries) sched.after(p.verifyIntervalMs) { reading(f, cb, n + 1) } else cb(v)
            }
        }
        cancel = sched.after(p.verifyTimeoutMs) { once(null) }   // 응답 없음도 실패로 센다
        try { f(once) } catch (e: Exception) { once(null) }
    }
    private fun readBrightness(a: AmbientArea, cb: (Int?) -> Unit) = reading({ k -> port.readBrightness(a, k) }, cb)
    private fun readColor(a: AmbientArea, cb: (Int?) -> Unit) = reading({ k -> port.readColor(a) { k(it?.and(0xFFFFFF)) } }, cb)

    private inner class Task(val cmdId: String, val kind: ControlKind) {
        var done = false
        val timers = mutableListOf<() -> Unit>()
        var finishNow: (Boolean, Int?, Int?) -> Unit = { _, _, _ -> }
        var restoring = false      // 되돌림 작업: 결과가 정해지면(resolved) 장부에서 그 종류를 지운다
        var quiet = false          // 장부 되돌림: 결과를 판단 코어로 보내지 않는다
        var turn: (() -> Unit)? = null   // 차례가 오면 할 일(그 종류의 지금 값 읽기부터)
        var started = false
    }

    /** 영역을 못 읽거나 던지면 null로 본다. */
    private fun withArea(cmdId: String, kinds: Collection<ControlKind>, restoring: Boolean = false, quiet: Boolean = false, f: (AmbientArea) -> Unit) {
        // 차례는 받는 즉시 잡는다
        val tasks = kinds.associateWith { Task(cmdId, it).also { t -> t.restoring = restoring; t.quiet = quiet; open.add(t); lanes.getValue(t.kind) += t } }
        fun skipAll(why: String) { note("ambient $cmdId skip: $why"); tasks.values.forEach { settle(it, false, null, null) } }
        var got = false
        reading<AmbientArea>({ k -> port.area(k) }, { a ->   // 영역 읽기도 시간 제한·재시도는 reading 하나가 한다
            if (!got) {
                got = true
                if (a == null) skipAll("no area") else try { f(a) } catch (e: Exception) { skipAll("error $e") }
            }
        })
        // 읽기 콜백이 끝내 안 오는 SDK에서도 결과를 영원히 기다리지 않게(쓰기는 다시 읽기 확인이 5초 안에 끝낸다)
        sched.after(p.verifyTimeoutMs * 4) {
            tasks.values.forEach { if (!it.done) { runCatching { it.finishNow(false, null, null) }; settle(it, false, null, null) } } }
    }

    private fun task(cmdId: String, kind: ControlKind) = open.firstOrNull { it.cmdId == cmdId && it.kind == kind && !it.done }

    /** 쓰지 않고 끝낸 종류(또는 쓰다 실패해 닫은 종류): 결과만 낸다. */
    private fun settle(t: Task, ok: Boolean, before: Int?, after: Int?) {
        if (t.done) return
        t.done = true; open -= t
        t.timers.forEach { it() }
        if (!t.quiet) post(ControlResult(clock(), t.cmdId, t.kind, null, ok, before?.toDouble(), after?.toDouble()))
        lanes.getValue(t.kind).remove(t)   // 끝남·건너뜀·버림 모두 차례에서 빠지고 다음 작업이 지금 값을 새로 읽으며 시작한다
        startHead(t.kind)
    }

    /**
     * 되돌림의 결과가 정해졌다: 장부는 이 함수 하나로만 지운다. 정해진 결과는 둘뿐이다: 되돌림 쓰기가 확인됐다, 또는 지금 값을 읽었는데 set과 다르다(운전자가 바꿨다).
     * 못 읽음·쓰기 실패·확인 못 함은 미정이라 부르지 않는다(장부가 남아 다음 장부 되돌림이 다시 본다). 낮춤 작업은 지우지 않는다.
     */
    private fun resolved(t: Task) { if (t.restoring) ledgerSet(t.kind, null) }

    /** 장부의 그 종류를 바꾼다(null = 지움). 저장 실패는 낮춤·되돌림을 막지 않는다(장부는 보조). */
    private fun ledgerSet(kind: ControlKind, v: AmbientValue?) {
        runCatching {
            val e = ledger.read() ?: LedgerEntry()
            val next = if (kind == ControlKind.AMBIENT_BRIGHTNESS) e.copy(brightness = v) else e.copy(color = v)
            if (next != e) ledger.write(next)
        }.onFailure { note("ambient ledger write failed: $it") }
    }

    /**
     * 앱 시작 뒤 차가 준비되면 첫 일로 부른다(Exec). 장부에 남은 낮춤을 되돌림과 같은 규칙(지금 값 = set일 때만 before)으로 되돌리고 지운다.
     * 판단 코어로 결과를 보내지 않고 control_log에 `restore:` 줄만(cmd_id = RestoreAmbientLedger의 cmdId) 남긴다.
     */
    fun restoreFromLedger(cmdId: String) {
        // 판단 코어가 이 명령을 낸 것은 Dimming을 버렸다는 뜻이다(주인 없는 낮춤). 살아 있는 낮춤(읽기 재시도 대기·쓰기 재전송)은 장부를 읽기 전에 버린다.
        // 되돌림 작업(예: 장면 끝 RestoreAmbient)은 건드리지 않는다
        abandon { !it.restoring }
        val e = runCatching { ledger.read() }.getOrNull() ?: return
        if (e.isEmpty) { runCatching { ledger.write(null) }; return }
        restore(RestoreAmbient(cmdId, e.brightness, e.color), quiet = true)
    }

    fun dim(c: DimAmbient) {
        val kinds = listOf(ControlKind.AMBIENT_BRIGHTNESS, ControlKind.AMBIENT_COLOR)
        // 켜짐·자동 색도 지금 값처럼 차례가 온 뒤에 읽는다(앞 작업이 재전송을 기다리는 동안 스위치가 꺼졌는데 옛 켜짐으로 쓰지 않게). 종류마다 차례 안에서 읽는다
        withArea(c.cmdId, kinds) { a -> dimBrightness(c, a); dimColor(c, a) }
    }

    /** 판단 스냅숏. [allowed] = (낮춤) 켜짐, 색은 자동 색 NONE·OFF까지. (되돌림은 늘 true). [now] = 그 종류의 지금 값(색은 24비트). 허용이 아니면 now는 없다. */
    private class Snap(val allowed: Boolean, val now: Int?)

    /** 한 번만 읽는다(재시도 없음). [Params.verifyTimeoutMs] 안에 응답이 없거나 던지면 null. 늦은 응답은 무시한다. */
    private fun <T : Any> readOnce(f: ((T?) -> Unit) -> Unit, cb: (T?) -> Unit) {
        var done = false
        var cancel: () -> Unit = {}
        val once = { v: T? -> if (!done) { done = true; cancel(); cb(v) } }
        cancel = sched.after(p.verifyTimeoutMs) { once(null) }
        try { f(once) } catch (e: Exception) { once(null) }
    }

    /**
     * 판단에 쓰는 차 값을 한 묶음으로 잇달아 읽는 함수 하나. (낮춤) 켜짐 → (색) 자동 색 → 그 종류의 지금 값. 되돌림은 지금 값만.
     * 읽기 하나라도 못 읽으면(실패·무응답) 그 값만 다시 읽지 않고 [Params.verifyIntervalMs] 뒤 묶음 전체를 처음부터 다시 읽는다([Params.verifyRetries]번까지). 그래도 안 되면 null.
     * 꺼짐과 자동 색 UNKNOWN은 실패가 아니라 답이라 다시 읽지 않는다. 차례 시작과 다시 보내기 앞에서만 부른다. 부른 쪽이 응답 뒤 작업이 살아 있는지 본다.
     */
    private fun snapshot(kind: ControlKind, a: AmbientArea, dim: Boolean, cb: (Snap?) -> Unit, n: Int = 0) {
        fun fail() { if (n < p.verifyRetries) sched.after(p.verifyIntervalMs) { snapshot(kind, a, dim, cb, n + 1) } else cb(null) }
        fun now() = readOnce<Int>(
            { k -> if (kind == ControlKind.AMBIENT_BRIGHTNESS) port.readBrightness(a, k) else port.readColor(a) { k(it?.and(0xFFFFFF)) } },
            { v -> if (v == null) fail() else cb(Snap(true, v)) },
        )
        if (!dim) return now()
        readOnce<Boolean>({ k -> port.lit(a, k) }) { lit ->
            when {
                lit == null -> fail()
                !lit -> cb(Snap(false, null))
                kind == ControlKind.AMBIENT_BRIGHTNESS -> now()
                else -> readOnce<AutoColor>({ k -> port.autoColor(k) }) { auto ->
                    when (auto) { null -> fail(); AutoColor.UNKNOWN -> cb(Snap(false, null)); else -> now() }
                }
            }
        }
    }

    private fun refusal(kind: ControlKind) = if (kind == ControlKind.AMBIENT_BRIGHTNESS) "off" else "off/auto color"

    private fun skip(cmdId: String, kind: ControlKind, read: Int?) { task(cmdId, kind)?.let { settle(it, false, read, read) } }

    private fun dimBrightness(c: DimAmbient, a: AmbientArea) {
        val t = task(c.cmdId, ControlKind.AMBIENT_BRIGHTNESS) ?: return
        val target = a.min + ((a.max - a.min) * c.level).roundToInt()
        val kind = ControlKind.AMBIENT_BRIGHTNESS
        arm(t) {
            snapshot(kind, a, dim = true, { s ->   // 차례가 온 뒤 읽은 한 번의 스냅숏으로 판단하고 곧바로 쓴다
                if (task(c.cmdId, kind) == null) return@snapshot   // 버려진 작업의 늦은 응답은 아무것도 바꾸지 않는다
                val b = s?.now
                when {
                    s == null -> { note("ambient ${c.cmdId} skip $kind: read failed"); skip(c.cmdId, kind, null) }
                    !s.allowed -> { note("ambient ${c.cmdId} skip $kind: ${refusal(kind)}"); skip(c.cmdId, kind, null) }
                    b == null || b <= target -> { note("ambient ${c.cmdId} skip brightness: $b"); skip(c.cmdId, kind, b) }
                    else -> write(c.cmdId, kind, a, b, target, ControlApi.AMBIENT_BRIGHTNESS, ControlApi.DIM + target, { "$it" },
                        ::readBrightness) { v, ack -> port.writeBrightness(a, v, ack) }
                }
            })
        }
    }

    private fun dimColor(c: DimAmbient, a: AmbientArea) {
        val t = task(c.cmdId, ControlKind.AMBIENT_COLOR) ?: return
        val rgb = c.color and 0xFFFFFF
        val kind = ControlKind.AMBIENT_COLOR
        arm(t) {
            snapshot(kind, a, dim = true, { s ->
                if (task(c.cmdId, kind) == null) return@snapshot
                val cur = s?.now
                when {
                    s == null -> { note("ambient ${c.cmdId} skip $kind: read failed"); skip(c.cmdId, kind, null) }
                    !s.allowed -> { note("ambient ${c.cmdId} skip $kind: ${refusal(kind)}"); skip(c.cmdId, kind, null) }
                    cur == null || cur == rgb -> { note("ambient ${c.cmdId} skip color: $cur"); skip(c.cmdId, kind, cur) }
                    else -> write(c.cmdId, kind, a, cur, rgb, ControlApi.AMBIENT_COLOR, ControlApi.DIM + hex(rgb), ::hex,
                        ::readColor) { v, ack -> port.writeColor(a, 0xFF000000.toInt() or v, ack) }
                }
            })
        }
    }

    /** 되돌림: 지금 값이 그 종류의 set과 같을 때만 before를 쓴다(운전자가 바꿨으면 쓰지 않는다). */
    fun restore(c: RestoreAmbient, quiet: Boolean = false) {
        val kinds = buildList {
            if (c.brightness != null) add(ControlKind.AMBIENT_BRIGHTNESS)
            if (c.color != null) add(ControlKind.AMBIENT_COLOR)
        }
        if (kinds.isEmpty()) return
        withArea(c.cmdId, kinds, restoring = true, quiet = quiet) { a ->
            c.brightness?.let { v -> task(c.cmdId, ControlKind.AMBIENT_BRIGHTNESS)?.let { t -> arm(t) {
                snapshot(ControlKind.AMBIENT_BRIGHTNESS, a, dim = false, { s ->
                    restoreOne(c.cmdId, ControlKind.AMBIENT_BRIGHTNESS, s?.now, v, a, ControlApi.AMBIENT_BRIGHTNESS, { "$it" },
                        ::readBrightness) { x, ack -> port.writeBrightness(a, x, ack) } })
            } } }
            c.color?.let { v -> task(c.cmdId, ControlKind.AMBIENT_COLOR)?.let { t -> arm(t) {
                snapshot(ControlKind.AMBIENT_COLOR, a, dim = false, { s ->
                    restoreOne(c.cmdId, ControlKind.AMBIENT_COLOR, s?.now, v, a, ControlApi.AMBIENT_COLOR, ::hex,
                        ::readColor) { x, ack -> port.writeColor(a, 0xFF000000.toInt() or x, ack) } })
            } } }
        }
    }

    private fun restoreOne(
        cmdId: String, kind: ControlKind, now: Int?, v: AmbientValue, a: AmbientArea, api: String, fmt: (Int) -> String,
        read: (AmbientArea, (Int?) -> Unit) -> Unit, put: (Int, (Boolean) -> Unit) -> Unit,
    ) {
        if (task(cmdId, kind) == null) return   // 버려진 작업의 늦은 읽기 응답은 아무것도 바꾸지 않는다
        if (now == null) { note("ambient $cmdId skip restore $kind: read failed"); skip(cmdId, kind, null); return }   // 미정: 장부는 그대로
        if (now != v.set) {   // 정해짐(운전자가 바꿨다): 장부를 지운다
            note("ambient $cmdId skip restore $kind: now=$now set=${v.set}")
            task(cmdId, kind)?.let { resolved(it) }
            skip(cmdId, kind, now)
            return
        }
        write(cmdId, kind, a, now, v.before, api, ControlApi.RESTORE + fmt(v.before), fmt, read, put)   // 되돌림은 스위치를 보지 않는다
    }

    /** 쓰기 성공 콜백과 다시 읽은 값이 모두 맞아야 ok. 5초 안에 안 되면 한 번 다시 보내고, 그래도 안 되면 ok = false. */
    private fun write(
        cmdId: String, kind: ControlKind, a: AmbientArea, before: Int, want: Int, api: String, goal: String, fmt: (Int) -> String,
        read: (AmbientArea, (Int?) -> Unit) -> Unit, put: (Int, (Boolean) -> Unit) -> Unit,
    ) {
        val t = task(cmdId, kind) ?: return
        val t0 = clock()
        var last: Int? = null
        var ackMs: Long? = null
        var attempt = 0
        fun finish(ok: Boolean) {
            if (t.done) return
            log.row(cmdId, api, a.target, goal, last?.let(fmt), ok, ackMs, if (ok) clock() - t0 else null, attempt)
            if (ok) resolved(t)   // 되돌림 쓰기가 확인됐다. 실패·확인 못 함은 미정이라 장부를 남긴다
            settle(t, ok, before, last)
        }
        t.finishNow = { ok, _, _ -> finish(ok) }   // 첫 put 바로 앞에서 건다: 그 전에 버려지면 settle만(시도가 아니라 control_log 없음)
        val dimGoal = goal.startsWith(ControlApi.DIM)
        fun send(n: Int) {
            if (t.done) return
            t.timers.forEach { it() }; t.timers.clear()
            attempt = n
            var acked: Boolean? = null
            val ack = { ok: Boolean -> if (!t.done && attempt == n) { acked = ok; ackMs = clock() - t0 } }
            if (n == 0 && goal.startsWith(ControlApi.DIM)) ledgerSet(kind, AmbientValue(before, want))   // 쓰기 전에 장부에 남긴다(쓰다 죽어도 되돌릴 값이 남게)
            try { put(want, ack) } catch (e: Exception) { ack(false) }
            fun poll() {
                if (t.done || attempt != n) return
                try {
                    read(a) { v ->
                        if (t.done || attempt != n) return@read
                        if (v != null) last = v
                        if (acked == true && v == want) finish(true)
                    }
                } catch (e: Exception) { /* 못 읽은 것 */ }
                if (!t.done && attempt == n) t.timers += sched.after(p.verifyIntervalMs) { poll() }
            }
            t.timers += sched.after(p.verifyFirstMs) { poll() }
            t.timers += sched.after(p.verifyTimeoutMs) {
                if (!t.done && attempt == n) {
                    if (n >= p.verifyRetries) finish(false)
                    else snapshot(kind, a, dimGoal, { s ->   // 다시 보내기 앞에 스냅숏을 새로 읽는다(낮춤은 켜짐·자동 색까지)
                        if (t.done || attempt != n) return@snapshot
                        val cur = s?.now
                        when {
                            // 못 읽으면 덮을지 모르니 보내지 않고 실패(첫 시도가 있었다: control_log)
                            s == null -> finish(false)
                            !s.allowed -> { note("ambient $cmdId stop resend $kind: ${refusal(kind)}"); finish(false) }
                            // 운전자(또는 차)가 바꿨다: 정해짐, 장부를 지운다
                            cur != null && cur != before && cur != want -> {
                                note("ambient $cmdId skip resend $kind: now=$cur before=$before")
                                resolved(t)
                                settle(t, false, cur, cur)
                            }
                            else -> send(n + 1)
                        }
                    })
                }
            }
        }
        send(0)
    }

    /** Exec.stop: 진행 중인 모든 종류를 ok = false로 닫는다(쓰던 것은 control_log 줄도). 늦은 콜백은 끝난 작업이라 무시된다. */
    fun closeAll() = abandon { true }

    /** 조건에 맞는 진행 중인 작업을 ok = false로 닫는다(장부는 건드리지 않는다). 늦은 읽기·재전송은 끝난 작업이라 아무것도 하지 않는다. closeAll과 장부 되돌림이 같이 쓴다. */
    private fun abandon(pred: (Task) -> Boolean) {
        holdNext = true   // 닫는 동안 다음 작업을 시작하지 않는다(곧 닫힐 작업이 지금 값을 읽지 않게)
        try {
            open.toList().forEach { t -> if (!t.done && pred(t)) { runCatching { t.finishNow(false, null, null) }; settle(t, false, null, null) } }
        } finally { holdNext = false }
        lanes.keys.forEach { startHead(it) }   // 남은 작업(예: 장부 되돌림이 버린 낮춤 뒤의 되돌림)이 이어서 돈다
    }

    private fun hex(v: Int) = "#%06X".format(v and 0xFFFFFF)
}
