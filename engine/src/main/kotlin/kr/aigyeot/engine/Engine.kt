package kr.aigyeot.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

// 계약 2.1. 전이는 계약 5장 표를 따른다(표의 행 번호를 주석에 적는다).
class Engine(private val p: Params = Params()) {
    /** 순수 함수. 같은 state·event면 늘 같은 Step을 낸다. */
    fun step(state: EngineState, event: Event): Step = Ctx(p, state).run(event)
}

data class Step(
    val state: EngineState,        // 새 상태
    val commands: List<Command>,   // 할 일. 이 순서대로 실행한다
    val wakeAt: Long?,             // 이 시각까지 Tick이 와야 한다. null이면 기다릴 시간 전이가 없다
)

// 처음 상태. 저장해 둔 스냅숏이 있으면 호스트가 그것을 쓴다(3-14 ⑤).
fun EngineState.Companion.fresh(
    seed: Long, profile: Profile?, methods: List<Method>,
    history: List<FirstTry>, feedingStart: Long?, otherGuardians: Int,
): EngineState = EngineState(
    now = 0, rng = seed, seq = 0, profile = profile, methodsNext = methods, history = history,
    feedingStart = feedingStart, otherGuardians = otherGuardians,
    car = CarView(null, null, emptySet(), false, false, null, null, null, emptyMap()),
    ign = Ignition(false, false, false, emptyList(), null, 0),
    session = null,
    speech = SpeechQueue(null, null, emptyList()),
    steer = SteerState(null, null, null, null, 0, null),
)

private val AUTO_PHASES = setOf(Phase.ATTEMPT, Phase.GAP, Phase.CONFIRM)
private val REAR = setOf(Area.ROW_2_LEFT, Area.ROW_2_RIGHT)
private val DEFAULT_METHODS = listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY))
private val OFFER_SIDS = setOf(Sid.S07, Sid.S07_DEPART, Sid.S08, Sid.S10, Sid.S11)   // 휴식 기록에 남기는 제안 문장
private val HOLDABLE = setOf(Sid.S06, Sid.S07, Sid.S07_DEPART, Sid.S10, Sid.S11)   // C107: 운전이 바쁘면 미루는 급하지 않은 문장
private val STOP_WORDS = setOf("그만", "그만해", "그만틀어", "내가할게")   // Q4

/** step 한 번의 작업 공간. 상태는 불변 객체라 copy로 바꿔 끼운다. */
private class Ctx(val p: Params, var s: EngineState) {
    val out = mutableListOf<Command>()
    var offered = false     // P1: 이 Step에 인계·쉼터 제안을 했다
    var qaTool = false      // Q5a: ④ 질문의 도구 사건(또는 그 결과)을 처리하는 중
    var snapshot = false    // Suspend·기록 지우기: 마지막에 SaveSnapshot

    val now get() = s.now
    val ses get() = s.session
    fun ses(f: SessionState.() -> SessionState) { s.session?.let { s = s.copy(session = it.f()) } }
    fun ep(f: EpisodeState.() -> EpisodeState) = ses { copy(episode = episode?.f()) }
    fun car(f: CarView.() -> CarView) { s = s.copy(car = s.car.f()) }
    fun ign(f: Ignition.() -> Ignition) { s = s.copy(ign = s.ign.f()) }
    fun speech(f: SpeechQueue.() -> SpeechQueue) { s = s.copy(speech = s.speech.f()) }
    fun qa(f: QaWindow.() -> QaWindow) = speech { copy(qa = qa?.f()) }
    val gearP get() = s.car.gear == Gear.P
    val suspended get() = s.ign.suspendedAt != null

    fun run(e: Event): Step {
        // Boot·Resume과 복원 보정 사건(2.4 ③: MicService, InputLost, 오류 PlaybackEnded)은 시간 기준만 그 시각으로 맞춘다.
        // 그 앞 공백(프로세스가 죽어 있던 시간일 수 있다)을 quietMs에 넣지 않고, 시간 전이·장시간 제안·말하기도 내지 않는다(14g).
        // 평소에 오는 같은 사건은 다음 Tick(1초 안)에 이어 가므로 잃는 것이 없다.
        val rebase = e is Boot || e is Resume || e is MicService || e is InputLost ||
            (e is PlaybackEnded && e.reason != EndReason.DEADLINE)
        advance(e.at, countQuiet = !rebase)
        // 지금 재생의 PlaybackEnded는 그 사건이 끝을 정한다(복원 C58: endAt 뒤에 와도 시간 끝으로 보지 않는다).
        if (!rebase) timers(skipPlay = e is PlaybackEnded && e.playId == ses?.play?.id)
        handle(e)
        if (!rebase) { longDrive(); drain() }
        if (snapshot) out += SaveSnapshot(s)
        return Step(s, out, wakeAt())
    }

    /** 시간을 흘린다. HANDOFF·WAIT 5분 타이머는 판정 멈춤·Suspend 중엔 세지 않는다(3-11, 3-21, C10). */
    fun advance(at: Long, countQuiet: Boolean) {
        val t = maxOf(at, s.now)
        val x = ses
        if (countQuiet && x != null && (x.phase == Phase.HANDOFF || x.phase == Phase.WAIT) && x.pauses.isEmpty() && !suspended)
            ses { copy(quietMs = quietMs + (t - s.now)) }
        s = s.copy(now = t)
    }

    /** 시간 전이. 사건보다 먼저 본다(늦은 Tick 대신 구간이 먼저 와도 창이 endAt에 붙는다, 5.3). */
    fun timers(skipPlay: Boolean) {
        s.speech.qa?.let { if (now >= it.until) speech { copy(qa = null) } }                   // Q3
        ses?.play?.let { if (!skipPlay && now >= it.endAt) playDone(it.endAt) }                               // 6a·12a·18f·18h·18k
        val x = ses ?: return
        if ((x.phase == Phase.HANDOFF || (x.phase == Phase.WAIT && x.play == null)) &&
            x.quietMs >= p.resumeQuietMs && x.pauses.isEmpty() && !suspended) closeEpisode()      // 11a
        if (gearP && x.driveFrom != null && now - (s.car.gearSince ?: now) >= p.longDriveResetParkMs)
            ses { copy(driveFrom = null, longDriveOffered = false) }                             // L2
        val ns = x.nursingStop
        if (ns?.firstPAt != null && !ns.confirmShown && gearP && now - ns.firstPAt >= p.feedingParkConfirmMs) {
            ses { copy(nursingStop = ns.copy(confirmShown = true)) }                             // F8
            out += ShowNotice(Notice.FEEDING_CONFIRM)
        }
    }

    fun handle(e: Event) {
        when (e) {
            is Tick -> {}
            is Boot -> {
                car { copy(gear = e.gear, gearSince = now) }
                ign { copy(driverUserId = e.userId, doorOpenedSinceP = if (e.gear == Gear.P) false else doorOpenedSinceP) }
                newIgnition()
                ses { copy(waypointPending = null, comfortPending = null) }   // comfortPending도: 옛 ControlResult는 오지 않는다(14g, C102). waypointPending: 프로세스가 새로 떴다: 옛 AddWaypoint의 콜백은 오지 않는다(C84). Resume은 살아 있을 수 있어 비우지 않는다
                dropLostLocks()
            }
            is Resume -> newIgnition()
            is Suspend -> suspend()
            is CrySegment -> cry(e)
            is InputLost -> inputLost(e.reason)
            is InputRestored -> inputRestored(e.reason)
            is MicService -> {
                car { copy(micRunning = e.running, micPath = if (e.running) e.path else null) }
                if (e.running) ses { copy(micNoticeArmed = false) }
            }
            is GearChanged -> gear(e.gear)
            is RearDoor -> door(e)
            is SeatHeat -> {
                car { copy(seatHeat = seatHeat + (e.area to e.level)) }
                if (ses != null && e.level > 0 && e.area in babyAreas()) out += SetSeatHeat(newId(), listOf(e.area), 0) // 3c
            }
            is CabinTemp -> car { copy(cabinC = e.celsius) }
            is VehicleSample -> vehicle(e)
            is RouteState -> car { copy(route = RouteView(e.at, e.hasDestination, e.remainingSec, e.rests)) }
            is CallMode -> call(e.inCall)
            is FocusChanged -> focus(e)
            is PlaybackStarted -> started(e)
            is PlaybackRejected -> rejected(e)
            is PlaybackEnded -> ended(e)
            is SpeechDone -> speechDone(e)
            is Answer -> answer(e)
            is ControlResult -> when (e.control) {
                ControlKind.SEAT_HEAT -> if (!e.ok && ses != null) out += ShowNotice(Notice.SAFETY_CONTROL_FAILED) // 3d
                ControlKind.WINDOW_LOCK -> windowResult(e)
                ControlKind.HVAC_TEMP -> hvacResult(e)
                else -> {}
            }
            is WaypointResult -> waypoint(e)
            is DriverStop -> if (tool(e.listenId)) driverStop()
            is SwitchSound -> if (tool(e.listenId)) switchSound(e.method)
            is DriverAddWaypoint -> if (tool(e.listenId)) addWaypoint(e.restId)
            is AskButton -> if (gearP && s.speech.speaking == null && listenOk()) listen()          // Q2
            is Heard -> heard(e)
            is QaReply -> qaReply(e)
            is BabyAboard -> if (ses == null) ign { copy(reserved = true) }                        // 1c
            is FeedingStarted -> feedingStarted(e)
            is FeedingConfirmed -> feedingConfirmed(e.yes)
            is ProfileChanged -> s = s.copy(profile = e.profile)
            is ClearRecords -> {                                                                  // C40a·C40b
                s = s.copy(history = emptyList())
                ses { copy(recordsCleared = true) }
                out += DeleteRecords; out += DeleteRelayRecords; snapshot = true
            }
            is MethodsChanged -> s = s.copy(methodsNext = e.methods)
            is LinkChanged -> s = s.copy(otherGuardians = e.otherGuardians)
        }
    }

    // ---- 시동·전원(14e·14f·14g) ----

    fun newIgnition() {                                                                            // G11·14g
        ign { copy(s16Said = false, suspendedAt = null) }
        if (!s.car.micRunning) ses { copy(micNoticeArmed = true) }
    }

    fun suspend() {                                                                                // Suspend 공통 처리
        val x = ses
        if (x != null) { if (x.phase in AUTO_PHASES) toWait(Unjudged.SUSPEND) else stopPlay(PlayEndReason.SUSPEND) }
        cancelSpeech()
        out += ReleaseAudio
        ign { copy(suspendedAt = now, reserved = false) }                                          // C14 예약 지움
        snapshot = true
    }

    fun cancelSpeech() {                                                                           // G30·Q8
        out += CancelSpeech
        speech { copy(speaking = null, queue = emptyList(), qa = null) }
        ses { copy(pendingAsk = null) }
    }

    fun call(on: Boolean) {
        car { copy(inCall = on) }
        if (!on) return                                                                            // 15e
        val x = ses
        if (x != null) { if (x.phase in AUTO_PHASES) toWait(Unjudged.CALL) else stopPlay(PlayEndReason.CALL) } // 15a·15b
        cancelSpeech()
    }

    // ---- 기어·뒷문·세션 열기·닫기(5.1) ----

    fun gear(g: Gear) {
        val prev = s.car.gear
        if (prev == g) return
        car { copy(gear = g, gearSince = now) }
        val x = ses
        if (g == Gear.P) {
            ign { copy(doorOpenedSinceP = false) }
            if (x == null) return
            if (x.phase in AUTO_PHASES) toWait(Unjudged.GEAR_P) else stopPlay(PlayEndReason.GEAR_P)   // 14b·18e
            if (now - (x.lastPAt ?: x.openedAt) >= p.arrivalMinDriveMs) say(Sid.S12)                  // A1
            if (!s.car.micRunning && x.micNoticeArmed) {                                              // 1f·14p
                out += ShowNotice(Notice.BABY_ABOARD)
                ses { copy(micNoticeArmed = false) }
            }
            val ns = x.nursingStop
            if (ns?.addedAt != null && ns.firstPAt == null) ses { copy(nursingStop = ns.copy(firstPAt = now)) } // F7
            return
        }
        if (x == null) { if (g == Gear.D) maybeOpen(); return }
        if (prev == Gear.P) ses {
            // lastPAt = P에서 나온 시각(이번 주행의 시작). F9: 첫 P 뒤 D면 수유 확인을 버린다.
            copy(lastPAt = now, driveFrom = driveFrom ?: now, nursingStop = if (nursingStop?.firstPAt != null) null else nursingStop)
        }
    }

    fun door(e: RearDoor) {
        car { copy(rearDoorOpen = if (e.open) rearDoorOpen + e.side else rearDoorOpen - e.side) }
        if (e.open) {
            ign { copy(doorOpenedSinceP = true) }
            if (ses != null && gearP) closeSession()                                                // 13a
        } else if (ses == null && gearP && !s.car.micRunning && !s.ign.s16Said) {                  // 2a·2b
            if (say(Sid.S16)) ign { copy(s16Said = true) }
            out += ShowNotice(Notice.BABY_ABOARD)
        }
    }

    fun maybeOpen() {                                                                              // 1a·1b·1d
        val pr = s.profile ?: return
        val doorCycle = s.ign.doorOpenedSinceP && s.car.rearDoorOpen.isEmpty()
        if (!doorCycle && !s.ign.reserved) return
        val id = ulid(now, s.seq)
        val cut = now - p.statWindowDays * 86_400_000L
        s = s.copy(seq = s.seq + 1, history = s.history.filter { it.at >= cut })
        ign { copy(reserved = false) }
        s = s.copy(session = SessionState(
            id = id, openedAt = now, trigger = if (doorCycle) OpenTrigger.DOOR_THEN_D else OpenTrigger.BUTTON_THEN_D,
            methods = s.methodsNext.ifEmpty { DEFAULT_METHODS }, phase = Phase.LISTENING, attemptNo = 0, play = null,
            judgeFrom = null, windowEnd = null, episode = null, pauses = s.car.outPauses, quietMs = 0, playedMs = 0,
            capReached = false, driveFrom = now, longDriveOffered = false, lastPAt = null, carseatFrom = now,
            s17Said = false, micNoticeArmed = false, lastOffer = emptyMap(), pendingAsk = null, windowLockBefore = null,
            frontAudible = false, comfortDone = false, nursingStop = null, waypointPending = null,
            recordsCleared = false, pendingAlarm = null, micPath = s.car.micPath,
        ))
        car { copy(outPauses = emptySet()) }                                                       // 21a: 세션 밖 끊김을 이어받음(C79)
        saveSession()
        val heated = babyAreas().filter { (s.car.seatHeat[it] ?: 0) > 0 }
        if (heated.isNotEmpty()) out += SetSeatHeat(newId(), heated, 0)                            // 3a
        if (pr.hasSibling) lockWindows()                                                           // 3b
        if (!s.car.micRunning) { say(Sid.S17); ses { copy(s17Said = true, micNoticeArmed = true) } } // 1e
        // F5 출발 수유 제안: 휴게소별 도착 예상이 입력에 없어 뺀다(C31). 도착 예상을 받게 되면 여기서 낸다.
    }

    fun closeSession() {                                                                           // 13a·13c·13d
        val id = ses!!.id
        stopPlay(PlayEndReason.SESSION_END)
        if (ses!!.episode != null) { unjudge(Unjudged.SESSION_END); closeEpisode() }
        restoreWindows(id)
        saveSession(endedAt = now, reportPending = true)
        rec(CloseSession(id))
        out += SendToPhone("feeding_state", feedingBody())
        car { copy(outPauses = ses!!.pauses) }                                                     // 남은 끊김은 다음 세션이 이어받음(C79)
        s = s.copy(session = null, steer = s.steer.copy(window = null))                            // C50
    }

    fun babyAreas(): List<Area> = when (s.profile?.carseat) {
        CarSeat.LEFT -> listOf(Area.ROW_2_LEFT)
        CarSeat.RIGHT -> listOf(Area.ROW_2_RIGHT)
        else -> listOf(Area.ROW_2_LEFT, Area.ROW_2_RIGHT)
    }

    // ---- 뒷창 잠금(3b·3e~3i·13a) ----

    fun held(l: List<WindowLockPending>, skip: Int, area: Area) = l.withIndex().any { (k, w) ->
        k != skip && (w.locking.values.any { area in it } || w.restoring.values.any { area in it } || (k < skip && area in w.queued))
    }

    fun lockWindows() {
        val l = s.ign.windowLockPending
        val busy = REAR.filter { a -> held(l, l.size, a) }.toSet()   // 새 항목은 맨 뒤
        var w = WindowLockPending(ses!!.id, emptyMap(), busy, emptyMap())
        val send = REAR - busy
        if (send.isNotEmpty()) {
            val c = newId()
            out += SetWindowLock(c, send.associateWith { true })
            w = w.copy(locking = mapOf(c to send))
        }
        ign { copy(windowLockPending = l + w) }
    }

    fun Map<String, Set<Area>>.minus(k: String, a: Area): Map<String, Set<Area>> {
        val left = getValue(k) - a
        return if (left.isEmpty()) this - k else this + (k to left)
    }

    /** 42g·42h: 쾌적 조정 결과. 성공이고 그 재생이 계속될 때만 실제로 낮춘 값을 말한다(C102, 말하기 직전 valid가 C15를 다시 본다). */
    fun hvacResult(e: ControlResult) {
        val c = ses?.comfortPending?.takeIf { it.cmdId == e.cmdId } ?: return
        ses { copy(comfortPending = null) }
        val lowered = if (e.ok && e.before != null && e.after != null) e.before - e.after else return
        if (!lowered.isFinite() || lowered <= 0.0) return   // 42h: 낮추지 못했거나 값이 이상하면 말하지 않는다
        if (ses?.play?.id != c.playId) return
        say(Line(Sid.S04, mapOf("tempC" to "${c.tempC.roundToInt()}", "loweredC" to fmtC(lowered)), null, playId = c.playId))
    }

    private fun fmtC(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)

    fun windowResult(e: ControlResult) {
        val l = s.ign.windowLockPending.toMutableList()
        val i = l.indexOfFirst { e.area in it.locking[e.cmdId].orEmpty() }
        val j = l.indexOfFirst { e.area in it.restoring[e.cmdId].orEmpty() }
        if (i >= 0) {
            var w = l[i].copy(locking = l[i].locking.minus(e.cmdId, e.area))
            val before = e.before?.let { it != 0.0 }
            if (e.ok && before != null) {
                if (w.sessionId == ses?.id) ses { copy(windowLockBefore = windowLockBefore.orEmpty() + (e.area to before)) } // 3e
                else {                                                                             // 3f
                    val c = newId()
                    out += SetWindowLock(c, mapOf(e.area to before))
                    w = w.copy(restoring = w.restoring + (c to setOf(e.area)))
                }
            }
            l[i] = w
        } else if (j >= 0) l[j] = l[j].copy(restoring = l[j].restoring.minus(e.cmdId, e.area))       // 3g
        else return
        ign { copy(windowLockPending = l) }
        pumpLocks()
    }

    /** 3g·3i: 앞 항목이 놓은 영역의 대기 잠금을 영역마다 따로 보낸다. 세션이 닫혔으면 버린다. 빈 항목은 뺀다. */
    fun pumpLocks() {
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
        ign { copy(windowLockPending = l.filter { it.locking.isNotEmpty() || it.queued.isNotEmpty() || it.restoring.isNotEmpty() }) }
    }

    /** C86: 새 프로세스에는 옛 SetWindowLock의 결과가 오지 않는다. 기다리던 잠금·복원을 버리고, 열린 세션이 잠그던 영역은 다시 보낸다. */
    fun dropLostLocks() {
        val l = s.ign.windowLockPending
        if (l.isEmpty()) return
        ign { copy(windowLockPending = l.map { w ->
            val relock = if (w.sessionId == ses?.id) w.locking.values.flatten() else emptyList()
            w.copy(locking = emptyMap(), restoring = emptyMap(), queued = w.queued + relock)
        }) }
        pumpLocks()
    }

    fun restoreWindows(sessionId: String) {                                                        // 13a
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
        ign { copy(windowLockPending = l) }
        pumpLocks()
    }

    // ---- 감지(5.2·5.3) ----

    fun cry(e: CrySegment) {
        val x = ses ?: return
        if (x.pauses.isNotEmpty()) return                       // 판정 멈춤 중 들어온 구간은 버린다(5.3, 21f)
        val loud = e.p >= p.alarmThreshold
        if (loud) {
            s.steer.window?.let { s = s.copy(steer = s.steer.copy(window = it.copy(cryPresent = true))) }
            ep { copy(cryMs = cryMs + p.yamnetHopMs) }          // G37
        }
        when (x.phase) {
            Phase.LISTENING -> if (e.alarm) when {
                s.car.inCall -> logAlarm(AlarmOnly.IN_CALL)                                         // 15c
                x.capReached -> logAlarm(AlarmOnly.SESSION_CAP)                                     // 4d
                suspended -> {}                                                                     // 14v
                else -> {                                                                           // 4a·5a
                    // 지연 기준점 = 경보를 이룬 첫 구간(바로 앞 구간)의 start. 구간은 0.975초씩 밀린다(C36).
                    ses { copy(pendingAlarm = PendingAlarm(now, e.injectedCryAt ?: (e.start - p.yamnetHopMs))) }
                    startAttempt(1)
                }
            }
            Phase.HANDOFF, Phase.WAIT -> if (e.alarm) { ses { copy(quietMs = 0) }; logAlarm(AlarmOnly.WAITING) } // 11b
            Phase.GAP, Phase.CONFIRM -> judge(e, loud)
            Phase.ATTEMPT -> {}
        }
    }

    /** 판정 구간: start ≥ judgeFrom, end ≤ windowEnd. 창은 end > windowEnd인 첫 구간이 닫는다(G1·G2). */
    fun judge(e: CrySegment, loud: Boolean) {
        val x = ses!!
        val from = x.judgeFrom ?: return
        val end = x.windowEnd ?: return
        if (loud && e.start >= from && e.end <= end) return recry()                                 // 8a·8b·10a·10b
        if (e.end <= end) return                                                                    // 7b
        if (x.phase == Phase.GAP) {
            ses { copy(phase = Phase.CONFIRM, windowEnd = end + p.confirmMs) }                       // 7a
            if (loud && e.start >= from && e.end <= end + p.confirmMs) recry()                       // 7c
        } else success()                                                                            // 9a
    }

    fun TryRecord?.decide(r: TryResult) = this?.let { if (it.result == null) it.copy(result = r) else it }

    fun recry() {
        if (ses!!.attemptNo <= 1) {
            ep { copy(recried = true, first = first.decide(TryResult.FAIL)) }                         // C55
            saveEpisode()
            startAttempt(2)
        } else {
            ep { copy(second = second.decide(TryResult.FAIL)) }
            handoff(HandoffCause.TWO_ATTEMPTS)
        }
    }

    fun success() {
        if (ses!!.attemptNo <= 1) ep { copy(first = first.decide(TryResult.SUCCESS)) }
        else ep { copy(second = second.decide(TryResult.SUCCESS)) }
        closeEpisode()
    }

    fun inputLost(r: PauseReason) {
        if (r == PauseReason.MIC_ERROR) {                                                          // 21g·21h
            car { copy(micRunning = false, micPath = null) }
            out += ShowNotice(Notice.MIC_STOPPED)
        }
        if (ses == null) { car { copy(outPauses = outPauses + r) }; return }                       // 21h·C79
        ses { copy(pauses = pauses + r, judgeFrom = null, windowEnd = null) }                       // 21a·14t
        unjudge(Unjudged.valueOf(r.name))
    }

    fun inputRestored(r: PauseReason) {                                                            // 21d·21e
        car { copy(outPauses = outPauses - r) }
        val x = ses ?: return
        if (r !in x.pauses) return
        ses { copy(pauses = pauses - r) }
        if (x.pauses.size == 1 && (x.phase == Phase.GAP || x.phase == Phase.CONFIRM))
            ses { copy(judgeFrom = now, windowEnd = now + if (phase == Phase.GAP) p.gapMs else p.confirmMs) }
    }

    fun vehicle(e: VehicleSample) {                                                                // V1·V2
        val x = ses ?: return
        val o = steer(s.steer, e, false, x.play?.startedAt != null, x.id, p)
        // S4(C107): 바쁨 시각. steer 안에 두어 스냅숏 저장 판단(C78 (b) 묶어 저장)에는 영향이 없다 — 저장 횟수를 늘리지 않는다
        val busy = e.brake || e.turnSignal != TurnSignal.NONE || kotlin.math.abs(e.steeringDeg) >= p.steerHoldDeg
        s = s.copy(steer = if (busy) o.state.copy(driveBusyAt = e.at) else o.state)
        o.closed?.let { rec(SaveSteerWindow(it)) }
    }

    // ---- 시도·재생(공통 절차) ----

    fun feeding() = feedingWindow(s.feedingStart, s.profile?.feedingIntervalMin, now, p)
    fun hot() = (s.car.cabinC ?: Double.NEGATIVE_INFINITY) >= p.comfortTempC && ses?.comfortDone == false

    /** 시도 시작(n). 길이 = min(56초, 세션 남은 재생 초)(G5). */
    fun startAttempt(n: Int) {
        val x = ses!!
        if (x.capReached) return handoff(HandoffCause.SESSION_CAP)                                  // 12c
        var action: SecondAction? = null
        val method = if (n == 1) {
            if (feeding()) best(x.methods, s.history)                                               // 4h
            else pickFirst(x.methods, s.history, s.rng, p.roundRobinPerMethod).also { s = s.copy(rng = it.rng) }.method // 4f·4g
        } else {
            val first = x.episode?.first?.method
            action = if (hot()) SecondAction.COMFORT else if (feeding()) SecondAction.FEEDING else SecondAction.OTHER  // 42a~42c: 시작할 때 한 번(C81)
            if (action == SecondAction.COMFORT && first != null) first else best(x.methods, s.history, first)
        }
        val end = now + minOf(p.attemptMs, p.sessionPlayCapMs - x.playedMs)                         // 12b
        val id = newId()
        out += StartPlayback(id, PlayKind.AUTO, method, end, p.fadeMs)
        val purpose = if (n == 1) PlayPurpose.AUTO_FIRST else PlayPurpose.AUTO_SECOND
        ses { copy(phase = Phase.ATTEMPT, attemptNo = n, play = Play(id, PlayKind.AUTO, purpose, method, null, end, PlayMode.NORMAL, action), judgeFrom = null, windowEnd = null) }
    }

    fun openEpisode(trigger: EpisodeTrigger, first: TryRecord?) {
        val pa = ses!!.pendingAlarm
        val e = EpisodeState(
            ulid(now, s.seq), trigger, pa?.alarmAt ?: now, pa?.alarmAt, pa?.let { it.alarmAt - it.delayFromAt },
            0, first, null, false, null, emptyList(), 0, false,
        )
        s = s.copy(seq = s.seq + 1)
        ses { copy(episode = e, pendingAlarm = null) }
    }

    fun started(e: PlaybackStarted) {
        val x = ses
        val pl = x?.play
        if (pl == null || pl.id != e.playId) { out += StopPlayback(e.playId, false); return }      // 모르는 재생(2.4)
        if (pl.startedAt != null) return
        ses { copy(play = pl.copy(startedAt = now), playStarted = true, frontAudible = frontAudible || e.fallback) }
        if (pl.method.isSong() && s.speech.qa?.step == QaStep.HEARING) qa { copy(songHeard = true) } // Q5d: 듣는 도중 시작
        val method = mapOf("method" to methodName(pl.method))
        when (pl.purpose) {
            PlayPurpose.AUTO_FIRST -> {                                                             // 4b·4e·5b
                val fw = feeding()
                val un = if (gearP) Unjudged.PARKED_ALARM else if (fw) Unjudged.FEEDING_WINDOW else null
                openEpisode(EpisodeTrigger.ALARM, TryRecord(pl.method, null, un?.let { TryResult.UNJUDGED }, un))
                ses!!.pauses.forEach { unjudge(Unjudged.valueOf(it.name)) }                         // 21a: 시작 대기 중 끊긴 입력(C80)
                saveEpisode()
                captureContext()
                if (gearP) out += SetReadingLight(newId(), babyAreas(), false)
                say(Line(Sid.S02, method, null, playId = pl.id))
                if (fw && !gearP) { saySinceFeeding(); offer(false) }
            }
            PlayPurpose.AUTO_SECOND -> {                                                            // 42a·42b·42c
                val action = pl.action ?: SecondAction.OTHER
                ep { copy(second = TryRecord(pl.method, action, null, null)) }
                when (action) {
                    SecondAction.COMFORT -> {
                        val cmd = LowerHvac(newId(), p.comfortHvacAreas, p.comfortDeltaC)
                        out += cmd
                        ses { copy(comfortDone = true, comfortPending = ComfortPending(cmd.cmdId, (s.car.cabinC ?: 0.0).roundToInt().toDouble(), pl.id)) }   // S04는 결과가 ok로 온 뒤(42g, C102)
                    }
                    SecondAction.FEEDING -> {
                        say(Line(Sid.S03, method, null, playId = pl.id))
                        if (ses?.episode?.s06Said != true) saySinceFeeding()
                        offer(false)
                    }
                    SecondAction.OTHER -> say(Line(Sid.S03, method, null, playId = pl.id))
                }
                saveEpisode()
            }
            PlayPurpose.REQUEST -> {}
        }
    }

    fun saySinceFeeding() = say(Sid.S06, mapOf("elapsed" to hm(now - (s.feedingStart ?: now))))

    fun rejected(e: PlaybackRejected) {
        val pl = ses?.play ?: return
        if (pl.id != e.playId) return
        ses { copy(play = null) }
        when (pl.purpose) {
            PlayPurpose.AUTO_FIRST -> if (e.reason == RejectReason.NO_REAR_OUTPUT) {               // 4k
                openEpisode(EpisodeTrigger.ALARM, null)
                saveEpisode()
                captureContext()
                handoff(HandoffCause.NO_SOUND)
            } else {                                                                                // 4c·4j
                ses { copy(phase = Phase.LISTENING, attemptNo = 0, pendingAlarm = null) }
                logAlarm(AlarmOnly.FOCUS_DENIED)
            }
            PlayPurpose.AUTO_SECOND -> { ses { copy(phase = Phase.WAIT, quietMs = 0) }; saveEpisode() } // 42d·42f·8c
            PlayPurpose.REQUEST -> {                                                                // 18d·18n·Q5a
                qaTool = s.speech.qa?.step == QaStep.THINKING
                if (e.reason == RejectReason.FOCUS_DENIED) ruleSay(Sid.S18) else if (qaTool) ruleSay(Sid.S20)
            }
        }
    }

    fun ended(e: PlaybackEnded) {
        val pl = ses?.play ?: return                                                                // 늦은 STOPPED는 버린다(14n)
        if (pl.id != e.playId) return
        if (e.reason == EndReason.DEADLINE) return playDone(pl.endAt)
        // ERROR(C49). 우리가 내지 않은 STOPPED도 예기치 않은 멈춤이라 같게 본다.
        playEnd(now, PlayEndReason.ERROR, e.recorded)                                              // 14o·14r·14s·18m
        if (pl.kind == PlayKind.AUTO) toWait(Unjudged.FOCUS_LOSS)
    }

    /** 재생이 endAt에 끝남(Tick·DEADLINE). stopAt = endAt. */
    fun playDone(stopAt: Long) {
        val pl = ses?.play ?: return
        playEnd(stopAt, PlayEndReason.DEADLINE)
        val x = ses!!
        if (pl.kind == PlayKind.REQUEST) {                                                         // 18f·18h·18k
            if (x.quietMs >= p.resumeQuietMs && x.pauses.isEmpty() && !suspended) closeEpisode()    // 11a와 같은 조건
            return
        }
        if (x.capReached) { unjudge(Unjudged.SESSION_CAP); return handoff(HandoffCause.SESSION_CAP) } // 12a
        val paused = x.pauses.isNotEmpty()                                                          // 6a·6b·6c
        ses { copy(phase = Phase.GAP, judgeFrom = if (paused) null else stopAt, windowEnd = if (paused) null else stopAt + p.gapMs) }
    }

    /** 재생 종료 기록(stopAt). 사용 시간은 여기서만 더한다. */
    fun playEnd(stopAt: Long, reason: PlayEndReason, recorded: Boolean = false) {
        val x = ses ?: return
        val pl = x.play ?: return
        val used = if (recorded) 0 else pl.startedAt?.let { (stopAt - it).coerceAtLeast(0) } ?: 0
        val total = x.playedMs + used
        ses {
            copy(play = null, playedMs = total, capReached = capReached || total >= p.sessionPlayCapMs,
                episode = episode?.let { it.copy(playedMs = it.playedMs + used) })
        }
        val ep = ses!!.episode
        if (!recorded && ep != null)
            rec(SavePlay(PlayRecord(pl.id, x.id, ep.id, pl.purpose, pl.method.kind, pl.method.songId, pl.startedAt, stopAt, used, reason)))
        saveEpisode()
        saveSession()
    }

    fun stopPlay(reason: PlayEndReason) {
        val pl = ses?.play ?: return
        out += StopPlayback(pl.id, false)
        playEnd(now, reason)
    }

    /** 1차가 미정이면 UNJUDGED(이유), 정해졌으면 laterReasons(3-E). */
    fun unjudge(r: Unjudged) = ep {
        val f = first
        when {
            f == null -> this
            f.result == null -> copy(first = f.copy(result = TryResult.UNJUDGED, unjudged = r))
            r in laterReasons -> this
            else -> copy(laterReasons = laterReasons + r)
        }
    }

    fun toWait(r: Unjudged) {
        stopPlay(PlayEndReason.valueOf(r.name))   // WAIT로 가는 이유는 모두 같은 이름의 PlayEndReason이 있다
        unjudge(r)
        ses { copy(phase = Phase.WAIT, quietMs = 0, judgeFrom = null, windowEnd = null, pendingAlarm = null) }
        saveEpisode()
    }

    /** 에피소드 닫기 → LISTENING. 에피소드 한도는 여기서만 초기화한다(3-E, E1). */
    fun closeEpisode() {
        val x = ses ?: return
        val e = x.episode
        if (e != null) {
            val f = e.first
            if (!x.recordsCleared && f != null && (f.result == TryResult.SUCCESS || f.result == TryResult.FAIL))
                s = s.copy(history = s.history + FirstTry(e.startedAt, f.method, f.result == TryResult.SUCCESS))
            saveEpisode(endedAt = now)
        }
        ses { copy(phase = Phase.LISTENING, attemptNo = 0, play = null, judgeFrom = null, windowEnd = null, episode = null, quietMs = 0, pendingAlarm = null) }
    }

    // ---- 운전자 요청(5.6) ----

    fun driverStop() {                                                                             // 14a·18e·19
        val x = ses ?: return
        if (x.phase in AUTO_PHASES) toWait(Unjudged.DRIVER_STOP) else stopPlay(PlayEndReason.DRIVER_STOP)
    }

    fun switchSound(m: Method) {
        if (s.car.inCall || suspended) return                                                      // 17c·14v
        val x = ses ?: run { if (qaTool) ruleSay(Sid.S20); return }                                 // 17b·Q5a
        when (x.phase) {
            Phase.LISTENING -> {                                                                    // 17a
                openEpisode(EpisodeTrigger.SWITCH_SOUND, null)
                ses { copy(phase = Phase.WAIT, quietMs = 0) }
                saveEpisode()
                requestPlay(m)
            }
            Phase.ATTEMPT, Phase.GAP, Phase.CONFIRM -> { toWait(Unjudged.SWITCH_SOUND); requestPlay(m) } // 14d
            Phase.WAIT -> { stopPlay(PlayEndReason.SWITCH_SOUND); requestPlay(m) }                  // 18i
            Phase.HANDOFF -> ruleSay(Sid.S18)                                                       // 20
        }
    }

    /** 요청 재생 길이 = min(56초, 에피소드 남은 재생 초, 세션 남은 재생 초). */
    fun requestPlay(m: Method) {
        val x = ses!!
        val len = minOf(p.requestPlayMs, p.episodePlayCapMs - (x.episode?.playedMs ?: 0), p.sessionPlayCapMs - x.playedMs)
        if (len <= 0) return ruleSay(Sid.S18)                                                       // 18c·18l
        val id = newId()
        out += StartPlayback(id, PlayKind.REQUEST, m, now + len, p.fadeMs)                          // 18a·18b
        ses { copy(play = Play(id, PlayKind.REQUEST, PlayPurpose.REQUEST, m, null, now + len, PlayMode.NORMAL)) }
    }

    fun focus(e: FocusChanged) {
        val x = ses ?: return
        // 14c: GAP·CONFIRM에는 재생이 없어도 이 세션의 앞 재생이 포커스를 영구히 잃었으면 WAIT로 간다
        if (e.change == FocusChange.LOSS && (x.phase == Phase.GAP || x.phase == Phase.CONFIRM) && e.playId.startsWith("${x.id}-"))
            return toWait(Unjudged.FOCUS_LOSS)
        val pl = x.play ?: return
        if (pl.id != e.playId) return
        when (e.change) {
            FocusChange.LOSS -> if (pl.kind == PlayKind.AUTO) toWait(Unjudged.FOCUS_LOSS) else stopPlay(PlayEndReason.FOCUS_LOSS) // 14c·18e
            FocusChange.LOSS_TRANSIENT -> adjust(pl, PlayMode.PAUSE)                                // 14h
            FocusChange.LOSS_TRANSIENT_CAN_DUCK -> adjust(pl, PlayMode.DUCK)                        // 14i
            FocusChange.GAIN -> if (now < pl.endAt) adjust(pl, PlayMode.NORMAL)                     // 14j
        }
    }

    fun adjust(pl: Play, mode: PlayMode) {
        out += AdjustPlayback(pl.id, mode)
        ses { copy(play = pl.copy(mode = mode)) }
    }

    // ---- 인계·쉼터(5.7) ----

    fun handoff(cause: HandoffCause) {
        offered = true                                   // P1: 인계가 장시간 제안보다 앞선다
        val route = s.car.route
        val rest = pickRest(route, p)
        val kind = when {
            gearP -> HandoffKind.PARKED                                                             // H1
            nearDestination(route, p) -> HandoffKind.ARRIVAL_NOTICE                                 // H2
            rest != null -> HandoffKind.WAYPOINT_OFFER                                              // H3
            else -> HandoffKind.NO_REST                                                             // H7·H9
        }
        val minutes = route?.remainingSec?.let { (it + 59) / 60 }
        when (kind) {
            HandoffKind.ARRIVAL_NOTICE -> say(Sid.S15, mapOf("minutes" to "$minutes"))
            HandoffKind.WAYPOINT_OFFER, HandoffKind.NO_REST -> offer(false)
            HandoffKind.PARKED -> {}
        }
        val notify = s.otherGuardians > 0                                                           // H8
        ses { copy(phase = Phase.HANDOFF, quietMs = 0, judgeFrom = null, windowEnd = null, pendingAlarm = null) }
        ep { copy(handoff = HandoffRecord(cause, kind, false, notify)) }
        if (notify) out += SendToPhone("handoff_alert", handoffBody(kind, rest, minutes))
        saveEpisode()
    }

    fun handoffBody(kind: HandoffKind, rest: RestCandidate?, minutes: Int?): JsonObject {
        val x = ses!!
        val (wire, text) = when (kind) {   // contracts-link 3.3 문장 표
            HandoffKind.WAYPOINT_OFFER -> "rest_offer" to "아기가 계속 울어서 ${rest?.name}에 들르자고 운전자에게 제안했어요."
            HandoffKind.ARRIVAL_NOTICE -> "arrival_notice" to "아기가 계속 울어요. ${minutes}분 뒤 도착해요."
            HandoffKind.NO_REST -> "no_rest" to "아기가 계속 울어요. 가까운 쉼터가 없어 안전한 곳에 세우도록 안내했어요."
            HandoffKind.PARKED -> "parked" to "차가 선 채로 아기가 계속 울어요."
        }
        return buildJsonObject {
            put("text", text); put("kind", wire)
            put("rest_name", if (kind == HandoffKind.WAYPOINT_OFFER) rest?.name else null)
            put("minutes_to_arrival", if (kind == HandoffKind.ARRIVAL_NOTICE) minutes else null)
            put("occurred_at", now); put("session_id", x.id); put("episode_id", x.episode?.id)
        }
    }

    /** offerRest를 큐에 넣는다. 넣었으면 그 문장의 10분 반복 금지 시각을 남긴다. */
    fun offer(longDrive: Boolean) {
        val x = ses ?: return
        val line = offerRest(longDrive, s.car.gear, s.car.route, x.lastOffer, now, x.driveFrom, p) ?: return
        if (line.askRestId != null && !canAddWaypoint(line.askRestId)) return                       // H4c: 진행 중이거나 이미 넣은 쉼터는 제안하지 않는다(S10은 요청이 없어 해당 없음)
        if (s.speech.queue.any { it.sid == line.sid }) return       // 같은 문장이 큐에 있거나 말하는 중(큐 맨 앞): 10분 반복 금지 시각이 아직 없어도 겹쳐 내지 않는다(S3, C84)
        if (say(line)) {
            if (!line.longDrive) ses { copy(lastOffer = lastOffer + (line.sid to now)) }          // 2시간 제안의 시각은 말한 뒤 speechDone이 남긴다(C84)
            offered = true
        }
    }

    fun longDrive() {                                                                              // L1·P1
        val x = ses ?: return
        val from = x.driveFrom ?: return
        if (offered || x.longDriveOffered || gearP || suspended || s.car.inCall || now - from < p.longDriveMs) return
        if (x.play != null && x.play.startedAt == null) return   // 시작 결과(수유 분기·시도 알림)가 먼저다(3-P)
        if (s.speech.queue.any { it.longDrive }) return          // 이미 큐에 있거나 말하는 중(큐 맨 앞에 남아 있다)
        offer(true)                                              // longDriveOffered는 speechDone에서 말한 뒤에만 선다(L1, C84)
    }

    fun answer(e: Answer) {
        val pa = ses?.pendingAsk
        if (pa == null || pa.askId != e.askId) return                                               // H4b
        // 이 질문의 기록 줄(같은 문장은 10분 안에 다시 묻지 않으므로 문장·쉼터가 같은 마지막 미응답 줄)
        val i = ses!!.restLog.indexOfLast { it.sid == pa.sid && it.restId == pa.restId && it.answer == null }.takeIf { it >= 0 }
        ses { copy(pendingAsk = null) }                                                             // H4a·H6
        setLog(i) { copy(answer = e.answer) }
        if (e.answer == YesNo.YES && !gearP) {                                                      // H4
            if (canAddWaypoint(pa.restId)) sendWaypoint(pa.restId, pa.hasNursingRoom, false, i)
            else if (pa.restId in ses!!.addedRestIds) setLog(i) { copy(waypointOk = true) }         // H4c: 이미 넣은 쉼터
        }
    }

    /** 경유지를 새로 요청해도 되는가. 이미 넣은 쉼터거나 다른 요청이 진행 중이면 아니다(H4c, ④ 질문과 같은 검사). */
    fun canAddWaypoint(restId: String): Boolean = ses?.let { restId !in it.addedRestIds && it.waypointPending == null } ?: false

    fun sendWaypoint(restId: String, nursing: Boolean, fromQa: Boolean, logIndex: Int?) {
        val c = newId()
        out += AddWaypoint(c, restId)
        ses {
            val qaLog = if (fromQa) restLog.size else null                    // ④ 요청은 자기 기록 줄을 새로 만든다
            copy(waypointPending = WaypointPending(c, restId, id, episode?.id, fromQa, qaLog ?: logIndex),
                nursingStop = if (nursing) NursingStop(restId, null, null, false) else nursingStop,
                restLog = if (fromQa) restLog + RestLogEntry(now, Sid.QA, restId) else restLog)
        }
    }

    /** 휴식 기록 i번째 줄만 바꾼다(i가 없거나 범위 밖이면 그대로). */
    fun setLog(i: Int?, f: RestLogEntry.() -> RestLogEntry) = ses {
        if (i == null || i !in restLog.indices) this else copy(restLog = restLog.toMutableList().also { it[i] = it[i].f() })
    }

    fun waypoint(e: WaypointResult) {                                                              // H5·H5a
        val x = ses ?: return
        val w = x.waypointPending
        if (w == null || w.cmdId != e.cmdId || w.restId != e.restId) return                       // C65
        val mine = x.nursingStop?.restId == w.restId
        setLog(w.restLogIndex) { copy(waypointOk = e.ok) }                                       // 그 요청의 줄에만
        if (e.ok) {
            ses {
                copy(waypointPending = null, addedRestIds = addedRestIds + w.restId,
                    nursingStop = if (mine) nursingStop?.copy(addedAt = now) else nursingStop)
            }
            if (x.episode?.id == w.episodeId) ep { copy(waypointAdded = true, handoff = handoff?.copy(waypointAdded = true)) }
            val name = s.car.route?.rests?.firstOrNull { it.id == w.restId }?.name ?: w.restId
            say(Line(Sid.S09, mapOf("restName" to name), null, qaAnswer = w.fromQa))
        } else {
            ses { copy(waypointPending = null, nursingStop = if (mine) null else nursingStop) }
            if (w.fromQa) say(Line(Sid.S19, emptyMap(), null, qaAnswer = true))
        }
    }

    // ---- 수유 시계(10장) ----

    fun feedingStarted(e: FeedingStarted) {                                                        // F1·F2
        val fs = s.feedingStart
        if (fs != null && e.startedAt <= fs) return
        s = s.copy(feedingStart = e.startedAt)
        if (e.from == FeedingFrom.CAR_SCREEN) { out += SaveFeedingStart(e.startedAt); out += SendToPhone("feeding_state", feedingBody()) }
    }

    fun feedingConfirmed(yes: Boolean) {                                                           // F6·F10
        val ns = ses?.nursingStop ?: return
        if (!yes) return ses { copy(nursingStop = null) }
        val at = ns.firstPAt
        if (!ns.confirmShown || at == null || !gearP) return
        ses { copy(nursingStop = null) }
        val fs = s.feedingStart
        if (fs != null && at <= fs) return
        s = s.copy(feedingStart = at)
        out += SaveFeedingStart(at); out += SendToPhone("feeding_state", feedingBody())
    }

    /** link가 보낼 때 Room 값으로 다시 만든다(4.3). 여기서는 같은 이름으로 채워 둔다. */
    fun feedingBody(): JsonObject = buildJsonObject {
        val fs = s.feedingStart
        val iv = s.profile?.feedingIntervalMin
        put("last_feeding_start", fs)
        put("window_start_expected", if (fs != null && iv != null) fs + (iv * 60_000L * p.feedingWindowStart).toLong() else null)
    }

    // ---- ④ 묻기(Q행) ----

    fun tool(listenId: String?): Boolean {                                                         // Q7
        if (listenId == null) return true
        val qa = s.speech.qa
        if (qa == null || qa.step != QaStep.THINKING || qa.listenId != listenId) return false
        qaTool = true
        return true
    }

    fun listenOk() = !s.car.inCall && !suspended && s.car.micRunning && s.speech.qa == null && ses?.pendingAsk == null

    private fun Method?.isSong() = this?.kind.let { it == MethodKind.LULLABY || it == MethodKind.SONG }

    fun listen() {
        val id = newId()
        speech { copy(qa = QaWindow(id, QaStep.HEARING, now + p.qaListenMs + 1_000, false, ses?.play?.method.isSong())) }
        out += Listen(id, p.qaListenMs)
    }

    fun heard(e: Heard) {
        val qa = s.speech.qa ?: return
        if (qa.listenId != e.listenId || qa.step != QaStep.HEARING) return                         // Q7
        val text = e.text
        if (text == null || text.count { !it.isWhitespace() } < 2) return speech { copy(qa = null) } // Q3
        if (text.filter { it.isLetterOrDigit() } in STOP_WORDS) { speech { copy(qa = null) }; return driverStop() } // Q4
        if (qa.songHeard || ses?.play?.method.isSong()) return speech { copy(qa = null) }  // Q5d: 노래 가사를 질문으로 받지 않는다
        qa { copy(step = QaStep.THINKING, until = now + p.qaReplyWaitMs + 1_000) }                  // Q5
        out += Qa(e.listenId, text)
    }

    fun qaReply(e: QaReply) {                                                                      // Q6
        val qa = s.speech.qa ?: return
        if (qa.listenId != e.listenId || qa.step != QaStep.THINKING) return
        speech { copy(qa = null) }
        if (!qa.ruleSaid && e.text.isNotBlank()) say(Line(Sid.QA, mapOf("text" to e.text), null, qaAnswer = true))
    }

    fun addWaypoint(restId: String) {                                                              // Q5b·Q5c
        val x = ses
        val route = s.car.route
        val rest = route?.rests?.firstOrNull { it.id == restId }
        if (qaTool) qa { copy(ruleSaid = true) }
        if (x == null || gearP || nearDestination(route, p) || rest == null || !canAddWaypoint(restId))
            return ruleSay(Sid.S19)
        sendWaypoint(restId, rest.hasNursingRoom, qaTool, null)
    }

    /** 규칙 문장. ④ 질문의 답이면 qaAnswer로 맨 앞에 넣고 LLM 글을 버리게 표시한다(Q5a). */
    fun ruleSay(sid: Sid) {
        if (qaTool) qa { copy(ruleSaid = true) }
        say(Line(sid, emptyMap(), null, qaAnswer = qaTool))
    }

    // ---- 말하기(8장, 4.1 말하기 규칙) ----

    fun say(sid: Sid, vars: Map<String, String> = emptyMap()) = say(Line(sid, vars, null))

    /** 큐에 넣는다. 통화 중·Suspend 중이면 버린다(15d·14v). ④ 답은 맨 앞(말하는 문장 바로 뒤)에 넣는다. */
    fun say(l: Line): Boolean {
        if (s.car.inCall || suspended) return false
        val line = if (l.queuedAt == null) l.copy(queuedAt = now) else l
        speech {
            val q = if (line.qaAnswer || line.sid == Sid.QA)
                queue.toMutableList().apply { add(if (speaking != null && isNotEmpty()) 1 else 0, line) }
            else queue + line
            copy(queue = q)
        }
        return true
    }

    /** 최근 표본이 바쁨이다. 표본이 끊겨 `driveBusyAt`이 오래되면 거짓이라 미루지 않는다(S7). */
    fun driveBusy() = s.steer.driveBusyAt?.let { now - it < p.holdStableMs } == true

    /** 말하지 않고 버린 제안이 넣을 때 적은 10분 반복 금지 시각(`offer()`, 값 = `queuedAt`)을 지운다. 2시간 제안은 적지 않았으므로 해당 없음. */
    fun forgetOffer(l: Line) {
        if (l.queuedAt != null) ses { if (lastOffer[l.sid] == l.queuedAt) copy(lastOffer = lastOffer - l.sid) else this }
    }

    /** 꺼낼 때 쉼터 제안을 지금 경로로 갱신한다. 그 쉼터가 지금 `RouteState` 후보에 없으면 null(버림). 쉼터 없는 문장은 그대로. */
    fun refreshed(l: Line): Line? {
        if (l.sid !in HOLDABLE) return l        // S08 등 인계 제안은 이전과 똑같이 둔다
        val id = l.askRestId ?: return l
        val r = s.car.route?.rests?.firstOrNull { it.id == id } ?: return null
        return l.copy(vars = l.vars + mapOf("distanceKm" to km(r.distanceKm), "restName" to r.name))
    }

    /** 꺼낼 때 문장이 아직 맞는지 다시 본다(G15·C15). */
    fun valid(l: Line) = when (l.sid) {
        Sid.S02, Sid.S03, Sid.S04 -> l.playId != null && ses?.play?.id == l.playId   // '그 재생'이 계속될 때만
        Sid.S06, Sid.S07, Sid.S07_DEPART, Sid.S08, Sid.S10, Sid.S11, Sid.S15 -> !gearP && (l.askRestId == null || canAddWaypoint(l.askRestId))
        else -> true
    }

    /**
     * 큐 맨 앞 문장을 낸다. 말하는 동안 그 문장은 큐 맨 앞에 남겨 두고 SpeechDone에서 뺀다
     * (그래야 끝난 문장이 Speak·Ask·④ 답 중 무엇인지 안다, Q1·C66).
     */
    fun drain() {
        val sq = s.speech
        if (s.car.inCall || suspended || sq.speaking != null || sq.qa != null) return
        // S6: 바쁨 때문에 너무 오래 미뤄진 급하지 않은 문장은 버린다(longDriveOffered는 말한 뒤에만 서므로 그대로다, C84).
        // 큐에 든 뒤 바쁨이 한 번도 없었으면(driveBusyAt < queuedAt) 앞 문장 뒤에서 기다린 것이지 미뤄진 것이 아니므로 버리지 않는다
        val busyAt = s.steer.driveBusyAt
        val (expired, kept) = sq.queue.partition { it.sid in HOLDABLE && it.queuedAt != null && now - it.queuedAt > p.holdMaxMs && busyAt != null && busyAt >= it.queuedAt }
        expired.forEach { forgetOffer(it) }
        var q = kept.dropWhile { !valid(it) }
        // S5: 바쁨 중이면 급하지 않은 문장을 건너뛰고 처음 나오는 급한 문장을 낸다. 건너뛴 문장은 자리를 지킨다.
        // 고른 문장도 꺼낼 때 다시 본다: 무효(C15·G15)이거나 제안한 쉼터가 지금 경로에 없으면 버리고 다음 후보를 찾는다. 있으면 거리·이름을 지금 값으로 갱신한다
        var i: Int
        while (true) {
            i = q.indexOfFirst { !(it.sid in HOLDABLE && driveBusy()) }
            if (i < 0) break
            val ok = valid(q[i])
            val fresh = if (ok) refreshed(q[i]) else null
            if (fresh == null && ok) forgetOffer(q[i])
            if (fresh == null) q = q.filterIndexed { j, _ -> j != i } else { q = q.toMutableList().also { it[i] = fresh }; break }
        }
        speech { copy(queue = q) }
        if (i < 0) return
        val line = q[i]
        val last = sq.lastDoneAt
        if (!line.qaAnswer && line.sid != Sid.QA && last != null && now < last + p.speechGapMs) return   // S1
        if (i > 0) speech { copy(queue = listOf(line) + q.filterIndexed { j, _ -> j != i }) }
        val id = newId()
        val restId = line.askRestId
        if (line.sid in OFFER_SIDS) ses { copy(restLog = restLog + RestLogEntry(now, line.sid, restId)) }
        if (restId != null) {
            // 제안한 쉼터의 속성(2.2 PendingAsk). 문장 id가 아니라 고른 RestCandidate로 본다(S11 수유실 휴게소도 F7로 이어짐)
            val nursing = s.car.route?.rests?.firstOrNull { it.id == restId }?.hasNursingRoom
                ?: (line.sid == Sid.S07 || line.sid == Sid.S07_DEPART)
            ses { copy(pendingAsk = PendingAsk(id, line.sid, restId, episode?.id, nursing)) }
            out += Ask(id, line.sid, line.vars, p.yesNoListenMs)
        } else out += Speak(id, line.sid, line.vars)
        speech { copy(speaking = id) }
    }

    fun speechDone(e: SpeechDone) {
        if (s.speech.speaking != e.utteranceId) return     // 옛 SpeechDone은 무시(2.4 복원)
        val line = s.speech.queue.firstOrNull()
        speech { copy(speaking = null, lastDoneAt = now, queue = queue.drop(1)) }
        if (line == null || !e.spoken) return
        if (line.longDrive) ses { copy(longDriveOffered = true, lastOffer = lastOffer + (line.sid to now)) } // L1: 실제로 말했을 때만(C84)
        if (line.sid == Sid.S06) ep { copy(s06Said = true) }                                        // C66
        if (ses != null && line.askRestId == null && line.sid != Sid.QA && !line.qaAnswer && listenOk()) listen() // Q1
    }

    // ---- 기록 ----

    fun newId(): String {
        val id = "${ses?.id ?: "idle"}-${s.seq}"
        s = s.copy(seq = s.seq + 1)
        return id
    }

    /** 기록 지움(5.9) 뒤 이 세션의 기록 할 일은 내지 않는다. */
    fun rec(c: Command) { if (ses?.recordsCleared != true) out += c }

    fun logAlarm(r: AlarmOnly) = rec(LogAlarm(ses!!.id, now, r))

    fun captureContext() { ses?.episode?.let { e -> e.alarmAt?.let { rec(CaptureContext(e.id, it)) } } }

    fun saveEpisode(endedAt: Long? = null) {
        val x = ses ?: return
        val e = x.episode ?: return
        val f = e.first
        val sc = e.second
        val h = e.handoff
        rec(SaveEpisode(EpisodeRecord(
            e.id, x.id, e.startedAt, endedAt, e.trigger, e.alarmAt, e.alarmDelayMs,
            f?.method?.kind, f?.method?.songId, f?.result, f?.unjudged,
            sc?.action, sc?.method?.kind, sc?.method?.songId, sc?.result,
            h?.cause, h?.kind, e.waypointAdded || h?.waypointAdded == true, h?.guardianNotified ?: false,
            e.cryMs, e.recried, e.playedMs, e.laterReasons,
        )))
    }

    fun saveSession(endedAt: Long? = null, reportPending: Boolean = false) {
        val x = ses ?: return
        rec(SaveSession(SessionRecord(
            x.id, x.openedAt, endedAt, x.trigger, x.micPath,
            if (!x.playStarted) null else if (x.frontAudible) OutputRoute.DEFAULT_FALLBACK else OutputRoute.REAR_ZONE,   // C85
            x.driveFrom?.let { ((now - it) / 60_000).toInt() } ?: 0,
            x.playedMs, x.capReached, Json.encodeToString(x.restLog), x.windowLockBefore?.let { Json.encodeToString(it) },
            null, reportPending, null, s.ign.driverUserId,
        )))
    }

    fun wakeAt(): Long? {
        val c = mutableListOf<Long>()
        val sq = s.speech
        sq.qa?.let { c += it.until }
        if (sq.queue.isNotEmpty() && sq.speaking == null && sq.qa == null) sq.lastDoneAt?.let { c += it + p.speechGapMs }
        if (sq.queue.isNotEmpty() && sq.speaking == null && sq.qa == null) {
            // C107: 미뤄진 문장은 잠잠해질 때와 버릴 때에 다시 본다(표본이 끊기거나 Tick뿐이어도 나간다)
            s.steer.driveBusyAt?.let { c += it + p.holdStableMs }
            sq.queue.forEach { l -> if (l.sid in HOLDABLE && l.queuedAt != null) c += l.queuedAt + p.holdMaxMs + 1 }
        }
        val x = ses
        if (x != null) {
            x.play?.let { c += it.endAt }
            if ((x.phase == Phase.HANDOFF || x.phase == Phase.WAIT) && x.play == null && x.pauses.isEmpty() && !suspended)
                c += now + p.resumeQuietMs - x.quietMs
            if (x.driveFrom != null && !x.longDriveOffered) c += x.driveFrom + p.longDriveMs
            if (gearP && x.driveFrom != null) s.car.gearSince?.let { c += it + p.longDriveResetParkMs }
            x.nursingStop?.let { ns -> if (ns.firstPAt != null && !ns.confirmShown && gearP) c += ns.firstPAt + p.feedingParkConfirmMs }
        }
        return c.filter { it > now }.minOrNull()
    }
}
