package kr.aigyeot.engine

import kotlin.math.roundToInt

// 차량 제어(설계 3.4 Control): 뒷창 잠금·복원(3b·3e~3i·13a·C86), 쾌적 조정(42a)과 결과(42g·42h), 열선 끄기(3c)·실패 알림(3d). 열선 3a는 세션 열기(Life) 안 한 줄.
// 주인 칸(설계 3.3, D7): ign.windowLockPending, session.controls(windowLockBefore·comfortDone·comfortPending). 쓰기는 이 파일의 private locks()·controls {}뿐이다.

private val REAR = setOf(Area.ROW_2_LEFT, Area.ROW_2_RIGHT)

private fun Tx.locks(l: List<WindowLockPending>) { s = s.copy(ign = s.ign.copy(windowLockPending = l)) }
private fun Tx.controls(f: ControlState.() -> ControlState) = ses { copy(controls = controls.f()) }

// ---- 열선(3c·3d) ----

/** 3c: 세션 중 아기 자리 열선이 켜지면 끈다. */
internal fun Tx.heatGuard(e: SeatHeat) {
    if (ses != null && e.level > 0 && e.area in babyAreas()) out += SetSeatHeat(newId(), listOf(e.area), 0)
}

/** 3d: 열선 끄기 실패. */
internal fun Tx.heatResult(e: ControlResult) { if (!e.ok && ses != null) out += ShowNotice(Notice.SAFETY_CONTROL_FAILED) }

// ---- 쾌적 조정(42a·42g·42h) ----

/** 42a: 2차 쾌적 시도가 시작됐다. 낮추기를 내고 그 결과를 기다린다(S04는 결과가 ok로 온 뒤, C102). */
internal fun Tx.lowerHvac(playId: String) {
    val cmd = LowerHvac(newId(), p.comfortHvacAreas, p.comfortDeltaC)
    out += cmd
    controls { copy(comfortDone = true, comfortPending = ComfortPending(cmd.cmdId, (s.car.cabinC ?: 0.0).roundToInt().toDouble(), playId)) }
}

/** 수명 표 Comfort 행의 DROP(13a·Boot). */
internal fun Tx.dropComfort() = controls { copy(comfortPending = null) }

// ---- 뒷창 잠금(3b·3e~3i·13a) ----

internal fun Tx.held(l: List<WindowLockPending>, skip: Int, area: Area) = l.withIndex().any { (k, w) ->
    k != skip && (w.locking.values.any { area in it } || w.restoring.values.any { area in it } || (k < skip && area in w.queued))
}

internal fun Tx.lockWindows() {
    val l = s.ign.windowLockPending
    val busy = REAR.filter { a -> held(l, l.size, a) }.toSet()   // 새 항목은 맨 뒤
    var w = WindowLockPending(ses!!.id, emptyMap(), busy, emptyMap())
    val send = REAR - busy
    if (send.isNotEmpty()) {
        val c = newId()
        out += SetWindowLock(c, send.associateWith { true })
        w = w.copy(locking = mapOf(c to send))
    }
    locks(l + w)
}

private fun Map<String, Set<Area>>.minus(k: String, a: Area): Map<String, Set<Area>> {
    val left = getValue(k) - a
    return if (left.isEmpty()) this - k else this + (k to left)
}

/** 42g·42h: 쾌적 조정 결과. 성공이고 그 재생이 계속될 때만 실제로 낮춘 값을 말한다(C102, 말하기 직전 valid가 C15를 다시 본다). 문맥은 낼 때 잡은 ComfortPending(P5). */
internal fun Tx.hvacResult(e: ControlResult) {
    val c = s.waitingFor(e) ?: return
    controls { copy(comfortPending = null) }
    val lowered = if (e.ok && e.before != null && e.after != null) e.before - e.after else return
    if (!lowered.isFinite() || lowered <= 0.0) return   // 42h: 낮추지 못했거나 값이 이상하면 말하지 않는다
    if (ses?.play?.id != c.playId) return
    say(Line(Sid.S04, mapOf("tempC" to "${c.tempC.roundToInt()}", "loweredC" to fmtC(lowered)), null, playId = c.playId))
}

private fun fmtC(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)

/** 3e~3g. 문맥(P5)은 낼 때 잡은 항목: cmdId로 그 항목의 locking·restoring 칸을 찾고 그 항목의 sessionId만 본다. */
internal fun Tx.windowResult(e: ControlResult) {
    val l = s.ign.windowLockPending.toMutableList()
    val i = l.indexOfFirst { e.area in it.locking[e.cmdId].orEmpty() }
    val j = l.indexOfFirst { e.area in it.restoring[e.cmdId].orEmpty() }
    if (i >= 0) {
        var w = l[i].copy(locking = l[i].locking.minus(e.cmdId, e.area))
        val before = e.before?.let { it != 0.0 }
        if (e.ok && before != null) {
            if (w.sessionId == ses?.id) controls { copy(windowLockBefore = windowLockBefore.orEmpty() + (e.area to before)) } // 3e
            else {                                                                             // 3f
                val c = newId(w.sessionId) // C323(P5): 잠금을 낸 세션 몫이다
                out += SetWindowLock(c, mapOf(e.area to before))
                w = w.copy(restoring = w.restoring + (c to setOf(e.area)))
            }
        }
        l[i] = w
    } else if (j >= 0) l[j] = l[j].copy(restoring = l[j].restoring.minus(e.cmdId, e.area))       // 3g
    else return
    locks(l)
    pumpLocks()
}

/** 3g·3i: 앞 항목이 놓은 영역의 대기 잠금을 영역마다 따로 보낸다. 세션이 닫혔으면 버린다. 빈 항목은 뺀다. */
internal fun Tx.pumpLocks() {
    val l = s.ign.windowLockPending.toMutableList()
    for (i in l.indices) for (a in l[i].queued) {
        if (held(l, i, a)) continue
        var w = l[i].copy(queued = l[i].queued - a)
        if (w.sessionId == ses?.id) {
            val c = newId()
            out += SetWindowLock(c, mapOf(a to true))
            w = w.copy(locking = w.locking + (c to setOf(a)))
        }
        l[i] = w
    }
    val (keep, gone) = l.partition { it.locking.isNotEmpty() || it.queued.isNotEmpty() || it.restoring.isNotEmpty() }
    locks(keep)
    gone.forEach { reportIfSettled(it.sessionId) }   // C335: 빠진 항목의 닫힌 세션 리포트(3f·3g·C86). 열린 세션이면 reportIfSettled가 거른다
}

/** C86: 새 프로세스에는 옛 SetWindowLock의 결과가 오지 않는다. 기다리던 잠금·복원을 버리고, 열린 세션이 잠그던 영역은 다시 보낸다. */
internal fun Tx.dropLostLocks() {
    val l = s.ign.windowLockPending
    if (l.isEmpty()) return
    locks(l.map { w ->
        val relock = if (w.sessionId == ses?.id) w.locking.values.flatten() else emptyList()
        w.copy(locking = emptyMap(), restoring = emptyMap(), queued = w.queued + relock)
    })
    pumpLocks()
}

internal fun Tx.restoreWindows(sessionId: String) {                                                        // 13a
    val before = ses?.windowLockBefore.orEmpty()
    val l = s.ign.windowLockPending.toMutableList()
    var i = l.indexOfFirst { it.sessionId == sessionId }
    if (i < 0 && before.isEmpty()) return
    if (i < 0) { l += WindowLockPending(sessionId, emptyMap(), emptySet(), emptyMap()); i = l.lastIndex }
    var w = l[i].copy(queued = emptySet())   // 보내지 않은 잠금은 잠그지 않았으므로 버린다
    if (before.isNotEmpty()) {
        val c = newId()
        out += SetWindowLock(c, before)
        w = w.copy(restoring = w.restoring + (c to before.keys))
    }
    l[i] = w
    locks(l)
    pumpLocks()
}
