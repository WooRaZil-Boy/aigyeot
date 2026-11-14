package kr.aigyeot.engine

import kotlin.math.roundToInt

// 차량 제어: 뒷창 잠금·복원, 쾌적 조정과 결과, 열선 끄기·실패 알림. 열선 끄기는 세션 열기(LifeReducer) 안에서 한다.
// 주인 칸: ign.windowLockPending, session.controls(windowLockBefore·comfortDone·comfortPending). 쓰기는 이 파일의 private locks()·controls {}뿐이다.

private val REAR = setOf(Area.ROW_2_LEFT, Area.ROW_2_RIGHT)

private fun Tx.locks(l: List<WindowLockPending>) {
    s = s.copy(ign = s.ign.copy(windowLockPending = l))
}
private fun Tx.controls(f: ControlState.() -> ControlState) = ses { copy(controls = controls.f()) }

// ---- 열선 ----

/** 세션 중 아기 자리 열선이 켜지면 끈다. */
internal fun Tx.heatGuard(e: SeatHeat) {
    if (ses != null && e.level > 0 && e.area in babyAreas()) out += SetSeatHeat(newId(), listOf(e.area), 0)
}

/** 열선 끄기 실패. */
internal fun Tx.heatResult(e: ControlResult) {
    if (!e.ok && ses != null) out += ShowNotice(Notice.SAFETY_CONTROL_FAILED)
}

// ---- 쾌적 조정 ----

/** 2차 쾌적 시도가 시작됐다. 낮추기를 내고 그 결과를 기다린다(S04는 결과가 ok로 온 뒤). */
internal fun Tx.lowerHvac(playId: String) {
    val cmd = LowerHvac(newId(), p.comfortHvacAreas, p.comfortDeltaC)
    out += cmd
    controls { copy(comfortDone = true, comfortPending = ComfortPending(cmd.cmdId, (s.car.cabinC ?: 0.0).roundToInt().toDouble(), playId)) }
}

/** 수명 표 Comfort 행의 DROP(세션 닫힘·Boot). */
internal fun Tx.dropComfort() = controls { copy(comfortPending = null) }

// ---- 뒷창 잠금 ----

internal fun Tx.held(l: List<WindowLockPending>, skip: Int, area: Area) = l.withIndex().any { (k, w) ->
    k != skip && (w.locking.values.any { area in it } || w.restoring.values.any { area in it } || (k < skip && area in w.queued))
}

internal fun Tx.lockWindows() {
    val l = s.ign.windowLockPending
    val busy = REAR.filter { a -> held(l, l.size, a) }.toSet() // 새 항목은 맨 뒤
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

/**
 * 쾌적 조정 결과. 성공이고 그 재생이 계속될 때만 실제로 낮춘 값을 말한다(말하기 직전 valid가 재생이 계속되는지 다시 본다).
 * 문맥은 낼 때 잡은 ComfortPending.
 */
internal fun Tx.hvacResult(e: ControlResult) {
    val c = s.waitingFor(e) ?: return
    controls { copy(comfortPending = null) }
    val lowered = if (e.ok && e.before != null && e.after != null) e.before - e.after else return
    if (!lowered.isFinite() || lowered <= 0.0) return // 낮추지 못했거나 값이 이상하면 말하지 않는다
    if (ses?.play?.id != c.playId) return
    say(Line(Sid.S04, mapOf("tempC" to "${c.tempC.roundToInt()}", "loweredC" to fmtC(lowered)), null, playId = c.playId))
}

private fun fmtC(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)

/** 문맥은 낼 때 잡은 항목: cmdId로 그 항목의 locking·restoring 칸을 찾고 그 항목의 sessionId만 본다. */
internal fun Tx.windowResult(e: ControlResult) {
    val l = s.ign.windowLockPending.toMutableList()
    val area = e.area ?: return // 뒷창 잠금 결과는 늘 영역이 있다(앰비언트 둘만 null)
    val i = l.indexOfFirst { area in it.locking[e.cmdId].orEmpty() }
    val j = l.indexOfFirst { area in it.restoring[e.cmdId].orEmpty() }
    if (i >= 0) {
        var w = l[i].copy(locking = l[i].locking.minus(e.cmdId, area))
        val before = e.before?.let { it != 0.0 }
        if (e.ok && before != null) {
            if (w.sessionId == ses?.id) {
                controls { copy(windowLockBefore = windowLockBefore.orEmpty() + (area to before)) }
            } else {
                val c = newId(w.sessionId) // 잠금을 낸 세션 몫이다
                out += SetWindowLock(c, mapOf(area to before))
                w = w.copy(restoring = w.restoring + (c to setOf(area)))
            }
        }
        l[i] = w
    } else if (j >= 0) {
        l[j] = l[j].copy(restoring = l[j].restoring.minus(e.cmdId, area))
    } else {
        return
    }
    locks(l)
    pumpLocks()
}

/** 앞 항목이 놓은 영역의 대기 잠금을 영역마다 따로 보낸다. 세션이 닫혔으면 버린다. 빈 항목은 뺀다. */
internal fun Tx.pumpLocks() {
    val l = s.ign.windowLockPending.toMutableList()
    for (i in l.indices) {
        for (a in l[i].queued) {
            if (held(l, i, a)) continue
            var w = l[i].copy(queued = l[i].queued - a)
            if (w.sessionId == ses?.id) {
                val c = newId()
                out += SetWindowLock(c, mapOf(a to true))
                w = w.copy(locking = w.locking + (c to setOf(a)))
            }
            l[i] = w
        }
    }
    val (keep, gone) = l.partition { it.locking.isNotEmpty() || it.queued.isNotEmpty() || it.restoring.isNotEmpty() }
    locks(keep)
    gone.forEach { reportIfSettled(it.sessionId) } // 빠진 항목의 닫힌 세션 리포트. 열린 세션이면 reportIfSettled가 거른다
}

/** 새 프로세스에는 옛 SetWindowLock의 결과가 오지 않는다. 기다리던 잠금·복원을 버리고, 열린 세션이 잠그던 영역은 다시 보낸다. */
internal fun Tx.dropLostLocks() {
    val l = s.ign.windowLockPending
    if (l.isEmpty()) return
    locks(
        l.map { w ->
            val relock = if (w.sessionId == ses?.id) w.locking.values.flatten() else emptyList()
            w.copy(locking = emptyMap(), restoring = emptyMap(), queued = w.queued + relock)
        }
    )
    pumpLocks()
}

internal fun Tx.restoreWindows(sessionId: String) {
    val before = ses?.windowLockBefore.orEmpty()
    val l = s.ign.windowLockPending.toMutableList()
    var i = l.indexOfFirst { it.sessionId == sessionId }
    if (i < 0 && before.isEmpty()) return
    if (i < 0) {
        l += WindowLockPending(sessionId, emptyMap(), emptySet(), emptyMap())
        i = l.lastIndex
    }
    var w = l[i].copy(queued = emptySet()) // 보내지 않은 잠금은 잠그지 않았으므로 버린다
    if (before.isNotEmpty()) {
        val c = newId()
        out += SetWindowLock(c, before)
        w = w.copy(restoring = w.restoring + (c to before.keys))
    }
    l[i] = w
    locks(l)
    pumpLocks()
}

// ---- 앰비언트 조명 ----

private val AMBIENT_KINDS = setOf(ControlKind.AMBIENT_BRIGHTNESS, ControlKind.AMBIENT_COLOR)

/** 되돌릴 것(또는 그 결과)이 남아 닫힌 세션의 리포트를 붙잡는 칸인가: Dimming·back 있는 Dimmed·Restoring. */
internal val Ambient.holdsReport: Boolean get() = this !is Ambient.Dimmed || back.any

/** 앰비언트 칸을 쓰는 유일한 함수. 칸이 어느 세션 것에서 비거나 다른 세션 것이 될 때 그 세션의 리포트를 다시 본다. */
private fun Tx.ambient(next: Ambient?) {
    val prev = s.ign.ambient
    s = s.copy(ign = s.ign.copy(ambient = next))
    if (prev != null && prev.holdsReport && !(next != null && next.holdsReport && next.sessionId == prev.sessionId)) reportIfSettled(prev.sessionId)
}

/** 맞추기 한 함수: 목표와 칸을 견줘 바뀐 때만 명령을 낸다. 모든 Step 끝과 장면 시작의 실제 Step 끝에서 한 번 부른다. */
internal fun Tx.syncAmbient() {
    val a = s.ign.ambient
    when (s.ambientGoal()) {
        AmbientGoal.DIM -> if (a == null) {
            val c = newId()
            out += DimAmbient(c, p.ambientDimLevel, p.ambientDimColor)
            ambient(Ambient.Dimming(ses!!.id, c, AmbientBack(), AMBIENT_KINDS))
        }
        AmbientGoal.NORMAL -> if (a is Ambient.Dimmed) {
            if (!a.back.any) {
                ambient(null)
            } else {
                val c = newId(a.sessionId) // 낮춘 세션 몫
                out += RestoreAmbient(c, a.back.brightness, a.back.color)
                ambient(Ambient.Restoring(a.sessionId, c, a.back, backKinds(a.back)))
            }
            // 확인하지 못한(ok=false) 종류는 back에 없지만 실행기 장부에는 쓰기 직전 값이 남아 있을 수 있다. 되돌림 명령 뒤에 장부 되돌림을 한 번 더 낸다(장부가 비면 무해)
            if (!a.back.complete) out += RestoreAmbientLedger(newId(a.sessionId))
        }
        AmbientGoal.HOLD -> {}
    }
}

private fun backKinds(b: AmbientBack): Set<ControlKind> = buildSet {
    if (b.brightness != null) add(ControlKind.AMBIENT_BRIGHTNESS)
    if (b.color != null) add(ControlKind.AMBIENT_COLOR)
}

/** 앰비언트 결과. 문맥은 낼 때 잡은 칸의 cmdId뿐이다. 다른 cmdId는 버린다. */
internal fun Tx.ambientResult(e: ControlResult) {
    when (val a = s.ign.ambient) {
        is Ambient.Dimming -> {
            if (a.cmdId != e.cmdId || e.control !in a.pending) return
            val v = if (e.ok && e.before != null && e.after != null) AmbientValue(e.before.toInt(), e.after.toInt()) else null
            val got = if (e.control == ControlKind.AMBIENT_BRIGHTNESS) a.got.copy(brightness = v) else a.got.copy(color = v)
            val left = a.pending - e.control
            ambient(if (left.isEmpty()) Ambient.Dimmed(a.sessionId, got) else a.copy(got = got, pending = left))
        }
        is Ambient.Restoring -> {
            if (a.cmdId != e.cmdId || e.control !in a.pending) return
            val left = a.pending - e.control // ok와 상관없이 뺀다(다시 보내지 않는다. 뒷창 잠금 결과와 같다)
            ambient(if (left.isEmpty()) null else a.copy(pending = left))
        }
        else -> {}
    }
}

/**
 * 수명 표 Ambient 행 BOOT의 DROP: 옛 결과는 오지 않는다.
 * Dimming은 버리고 Restoring은 Dimmed로 되돌려 Step 끝 syncAmbient가 다시 낸다.
 */
internal fun Tx.dropAmbient() {
    when (val a = s.ign.ambient) {
        is Ambient.Dimming -> ambient(null)
        is Ambient.Restoring -> ambient(Ambient.Dimmed(a.sessionId, a.back))
        else -> {}
    }
    // 실행기 낮춤 장부로 되돌릴지는 판단 코어가 정한다. Dimmed(복원으로 낮춤을 이어 가는 GAP·CONFIRM, 또는 Step 끝에 되돌릴 것)면 내지 않는다.
    // 아니면(Dimming·null: 되돌릴 값을 잃음) 한 번 낸다. 결과 사건은 없다
    if (s.ign.ambient !is Ambient.Dimmed) out += RestoreAmbientLedger(newId())
}

/**
 * 수명 표 Ambient 행 SCENE_START의 CUT: 실제 상태가 Dimming이면 그 결과는 장면 상태로 가 버려진다. 칸을 비우고(리포트 기다림도 푼다)
 * `RestoreAmbientLedger`로 실행기의 낮춤 작업을 버린다. Dimmed·Restoring은 그대로 두어 realStep 끝 syncAmbient가 되돌림을 낸다.
 * 칸이 없으면 아무것도 안 한다.
 */
internal fun Tx.cutAmbientAtSceneStart() {
    if (s.ign.ambient !is Ambient.Dimming) return
    ambient(null)
    out += RestoreAmbientLedger(newId())
}

/** 수명 표 Ambient 행 SCENE_END의 RESTORE: 버릴 장면 상태의 back 있는 Dimmed를 되돌리는 명령만 낸다(상태는 버린다). */
internal fun Tx.restoreAmbientAtSceneEnd() {
    when (val a = s.ign.ambient) {
        is Ambient.Dimmed -> {
            if (a.back.any) out += RestoreAmbient(newId(a.sessionId), a.back.brightness, a.back.color)
            if (!a.back.complete) out += RestoreAmbientLedger(newId(a.sessionId)) // back이 비어도 낸다
        }
        // 장면 끝은 장면 상태를 통째로 버린다. Dimming(결과 전)이면 실행기의 낮춤 작업이 살아 있을 수 있어, Boot와 같이 장부 되돌림 명령으로 그 작업을 버리게 한다
        is Ambient.Dimming -> out += RestoreAmbientLedger(newId())
        else -> {} // Restoring(이미 낸 명령이 끝난다)·칸 없음
    }
}
