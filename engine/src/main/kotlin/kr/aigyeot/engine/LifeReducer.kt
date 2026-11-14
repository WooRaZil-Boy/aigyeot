package kr.aigyeot.engine

// 세션 수명·시동·차 사실: 시동(Boot·Resume·Suspend)·통화·기어·뒷문·입력 끊김·차 사실 사건, 세션 열기·닫기, 장면 드나들 때 차 상태.
// 주인 칸: car.*, ign.s16Said·doorOpenedSinceP·reserved·suspendedAt·driverUserId, session 있음·없음, session.micNoticeArmed·s17Said, bootAt.
// 쓰기는 이 파일의 private car {}·ign {}와 세션 만들기·닫기뿐이다. 수유실 들름은 Feeding, 주행 시작(lastPAt·driveFrom)은 Offer의 함수를 부른다.
// 대기 중인 것의 처리(세션 닫힘·새 세션·Suspend·통화)는 수명 표 lifecycle(Waiting.kt) 하나가 한다.

private fun Tx.car(f: CarView.() -> CarView) { s = s.copy(car = s.car.f()) }
/** ign의 Life 칸만 쓴다(windowLockPending은 Control, nursingStop은 Feeding). */
private fun Tx.ign(f: Ignition.() -> Ignition) { s = s.copy(ign = s.ign.f()) }

// ---- 시동·전원 ----

internal fun Tx.boot(e: Boot) {
    car { copy(gear = e.gear, gearSince = now) }
    // 장면 전 정차·문 열림이 끝났는지는 짐작하지 않고 장면 중 실제 기어가 P를 벗어났는지(stopEnded)로 본다. 프로세스 시작 Boot는 거짓이라 남기고 실행기의 첫 GearChanged가 정한다
    // 실행기 기어가 P가 아니면 stopEnded(GearChanged 처리 전에 장면이 끝난 경우)와 상관없이 정차는 끝난 것. 프로세스 시작은 gear null이라 그대로
    val ended = e.stopEnded || (e.gear != null && e.gear != Gear.P)
    if (ended) nursingLeftPark()
    // 프로세스가 다시 떠도(장면 끝 복원 포함) 지난 '아기가 탔나요?' 알림이 주행 중 남지 않게
    if (e.gear != null && e.gear != Gear.P) { out += HideNotice(Notice.BABY_ABOARD); out += HideNotice(Notice.MIC_STOPPED) }
    if (s.ign.nursingStop?.confirmShown == true) out += ShowNotice(Notice.FEEDING_CONFIRM)   // 알림 = 상태. 장면 끝 복원·프로세스 시작에 다시 띄운다(ended면 위가 이미 지웠다)
    ign { copy(driverUserId = e.userId, doorOpenedSinceP = if (ended && e.gear == Gear.P) false else doorOpenedSinceP) }
    newIgnition()
    // 장면 끝 Boot는 지금 기어 P를 읽어 뒤이은 reemit의 P가 무시되므로 여기서 상태대로 다시 띄운다.
    // '아기가 탔나요?'는 뺀다(위 newIgnition이 장면 실행기가 끈 마이크로 건 칸이라 다음 P에 맡긴다)
    if (e.gear == Gear.P) parkNotices(baby = false)
    lifecycle(Life.BOOT)   // 새 프로세스라 옛 콜백이 오지 않는다. 경유지·쾌적 버림, 잠금 결과 대기 버림·열린 세션 영역 다시 잠금. Resume은 살아 있을 수 있어 그대로
}

internal fun Tx.newIgnition() {
    ign { copy(s16Said = false, suspendedAt = null, boardNotice = false) }
    if (!s.car.micRunning) ses { copy(micNoticeArmed = true) }
}

internal fun Tx.suspend() {                                                                                // Suspend 공통 처리
    lifecycle(Life.SUSPEND)   // 재생 끊기(자동 시도면 WAIT) → 말하기·질문·질문 답 끊기(CancelSpeech) → 예약 지움. 묶인 장부는 Step 끝 flush
    out += ReleaseAudio
    ign { copy(suspendedAt = now) }
    snapshot = true
}

internal fun Tx.call(on: Boolean) {
    car { copy(inCall = on) }
    if (!on) return
    lifecycle(Life.CALL)                                                                       // Suspend와 같은 끊기
}

/** 수명 표 SESSION_OPEN·SUSPEND의 예약 DROP. */
internal fun Tx.dropReserved() = ign { copy(reserved = false) }

// ---- 차 사실 ----

internal fun Tx.micService(e: MicService) {
    val was = s.car.micRunning
    car { copy(micRunning = e.running, micPath = if (e.running) e.path else null) }
    if (!e.running && was && gearP) parkNotices(baby = false)   // 다시 켜기가 같은 오류로 실패하면 감지기가 InputLost를 다시 내지 않으므로 여기서 되살린다(이미 꺼졌으면 was가 거짓)
    // 알림 = 상태. 다른 길(앱 화면·단추·장면 뒤 다시 켬)로 켜져도 둘 다 내린다
    if (e.running) {
        ses { copy(micNoticeArmed = false) }; ign { copy(boardNotice = false) }
        out += HideNotice(Notice.BABY_ABOARD); out += HideNotice(Notice.MIC_STOPPED)
    }
}

/** 열선 값(차 사실). 아기 자리 열선을 켜면 끄는 일은 Control.heatGuard. */
internal fun Tx.seatHeat(e: SeatHeat) = car { copy(seatHeat = seatHeat + (e.area to e.level)) }

internal fun Tx.cabinTemp(e: CabinTemp) = car { copy(cabinC = e.celsius) }

internal fun Tx.route(e: RouteState) = car { copy(route = RouteView(e.at, e.hasDestination, e.remainingSec, e.rests, e.remainingKm,
    restsPendingSince = if (e.restsPending) route?.restsPendingSince ?: e.at else null)) }   // 기다림 시작은 처음 참이 된 때(at은 사건마다 바뀐다)

internal fun Tx.babyAboard() { if (ses == null) ign { copy(reserved = true) } }

/** 복원 ③: 경로를 비우고 복원 시각을 남긴다(새 경로를 기다린다). */
internal fun Tx.restored(at: Long) {
    car { copy(route = null) }
    s = s.copy(bootAt = at)
}

/** 장면 시작: 장면 첫 상태가 실제 차 상태·운전자를 이어받는다. 끊김 사유는 세션 밖 것만 넘긴다. */
internal fun Tx.intoScene(real: EngineState) {
    car { real.car.copy(pauses = if (real.session != null) emptySet() else real.car.pauses) }
    ign { copy(driverUserId = real.ign.driverUserId) }
}

// ---- 기어·뒷문·세션 열기·닫기 ----

internal fun Tx.gear(g: Gear) {
    val prev = s.car.gear
    if (prev == g) return
    car { copy(gear = g, gearSince = now) }
    val x = ses
    if (g == Gear.P) {
        ign { copy(doorOpenedSinceP = false) }
        nursingParked()                                                                        // 세션 없이도
        if (x == null) { parkNotices(); return }
        if (x.phase in AUTO_PHASES) toWait(Unjudged.GEAR_P) else stopPlay(PlayEndReason.GEAR_P)
        if (now - (x.leftParkAt ?: x.openedAt) >= p.arrivalMinDriveMs) say(Sid.S12)
        parkNotices()
        return
    }
    // 출발하면 주행 중 눌러 마이크를 켜지 않게 내린다. 다음 P에서 다시 띄운다
    if (prev == null || prev == Gear.P) { out += HideNotice(Notice.BABY_ABOARD); out += HideNotice(Notice.MIC_STOPPED) }
    // 첫 P 뒤 P가 아니면 수유 확인을 버린다(세션 없이도). 앞 기어가 모름(Boot 뒤)이어도 돈다. firstPAt은 P에서만 찍힌다
    nursingLeftPark()
    if (x == null) { if (g == Gear.D) maybeOpen(); return }
    if (prev == Gear.P) leftPark()                                                             // 이번 주행의 시작(Offer)
}

/** 알림 = 상태. P에서 마이크가 꺼져 있으면 띄운다(내리는 곳은 따로 있다). 띄웠다고 칸을 끄지 않는다. [baby]가 거짓이면 '듣기가 멈췄어요'만. */
private fun Tx.parkNotices(baby: Boolean = true) {
    if (s.car.micRunning) return
    if (PauseReason.MIC_ERROR in s.car.pauses) out += ShowNotice(Notice.MIC_STOPPED)   // 세션 유무와 상관없이
    if (baby && (ses?.micNoticeArmed ?: s.ign.boardNotice)) out += ShowNotice(Notice.BABY_ABOARD) // 세션 안은 세션 칸, 세션 밖은 시동 칸
}

internal fun Tx.door(e: RearDoor) {
    if ((e.side in s.car.rearDoorOpen) == e.open) return  // 바뀔 때만 동작(처음 읽은 값·장면 끝 reemitAll은 사실이 이미 같다)
    car { copy(rearDoorOpen = if (e.open) rearDoorOpen + e.side else rearDoorOpen - e.side) }
    if (e.open) {
        ign { copy(doorOpenedSinceP = true) }
        if (ses != null && gearP) closeSession()
    } else if (ses == null && gearP && !s.car.micRunning && !s.ign.s16Said) {
        if (say(Sid.S16)) ign { copy(s16Said = true) }
        out += ShowNotice(Notice.BABY_ABOARD)
        if (s.profile != null) ign { copy(boardNotice = true) }                                                     // 알림 = 상태
    }
}

internal fun Tx.maybeOpen() {
    val pr = s.profile ?: return
    val doorCycle = s.ign.doorOpenedSinceP && s.car.rearDoorOpen.isEmpty()
    if (!doorCycle && !s.ign.reserved) return
    val id = newUlid()
    trimHistory(p.statSince(now))
    ign { copy(boardNotice = false) }                                                          // 세션 안 알림은 micNoticeArmed가 이어받는다
    lifecycle(Life.SESSION_OPEN)                                                               // 예약을 썼다. 나머지 대기는 그대로
    s = s.copy(session = SessionState(
        id = id, openedAt = now, trigger = if (doorCycle) OpenTrigger.DOOR_THEN_D else OpenTrigger.BUTTON_THEN_D,
        methods = s.methodsNext.ifEmpty { DEFAULT_METHODS }, stage = Stage.Listening, episode = null, playedMs = 0,
        offers = OfferState(driveFrom = now), controls = ControlState(),
        s17Said = false, micNoticeArmed = false, recordsCleared = false, micPath = s.car.micPath,
    ))   // 세션 밖 끊김(car.pauses)은 그대로 이어받는다
    saveSession()
    val heated = babyAreas().filter { (s.car.seatHeat[it] ?: 0) > 0 }
    if (heated.isNotEmpty()) out += SetSeatHeat(newId(), heated, 0)
    if (pr.hasSibling) lockWindows()
    if (!s.car.micRunning) { say(Sid.S17); ses { copy(s17Said = true, micNoticeArmed = true) } }
}

internal fun Tx.closeSession() {
    val id = ses!!.id
    // 말하는 인계 줄의 장부는 KEEP(닫힌 세션 장부로 남아 그 줄을 끝까지 따라간다). 리포트도 그 장부를 확정할 때 만든다
    lifecycle(Life.SESSION_CLOSE)   // 장부 확정 → 재생 멈춤·에피소드 닫기 → 제안 줄·질문·경유지·쾌적 버림 → 뒷창 되돌림 → 조향 창 버림
    saveSession(endedAt = now, reportPending = true)
    val cleared = ses!!.recordsCleared
    out += SendToPhone("feeding_state", feedingBody())
    s = s.copy(session = null)   // 남은 끊김은 car.pauses에 있어 다음 세션이 이어받는다
    if (!cleared) reportIfSettled(id) // 말하는 인계 장부·뒷창 되돌림 대기가 남았으면 마지막 것이 끝날 때 낸다
}

/**
 * 닫힌 세션의 리포트(CloseSession)는 그 세션을 기다리는 것(말하는 인계 장부, 뒷창 잠금·되돌림 결과 대기)이 모두 끝났을 때 한 번 낸다.
 * 기다리는 것이 끝나는 곳(settleLedger·windowResult·pumpLocks·dropLostLocks)이 부른다. 닫힌 세션은 닫을 때 한 번, 그 뒤에는 마지막 대기가 빠질 때 한 번뿐이다.
 */
internal fun Tx.reportIfSettled(sessionId: String) {
    if (sessionId == ses?.id) return
    if (s.handoffs.any { it.sessionId == sessionId } || s.ign.windowLockPending.any { it.sessionId == sessionId }) return
    s.ign.ambient?.let { if (it.sessionId == sessionId && it.holdsReport) return }   // 앰비언트 되돌림(또는 그 결과) 대기
    out += CloseSession(sessionId)
}

/** 복원 ②: Room에서 이미 끝난 세션. 세션 닫힘의 할 일은 그때 나갔으니 수명 표 ROOM_CLOSED(버림만) 뒤 세션을 닫는다. */
internal fun Tx.closeFromRoom() {
    lifecycle(Life.ROOM_CLOSED)
    s = s.copy(session = null)
}

// ---- 입력 끊김 ----

internal fun Tx.inputLost(r: PauseReason) {
    if (r == PauseReason.MIC_ERROR) {
        car { copy(micRunning = false, micPath = null) }
        if (s.car.gear == Gear.P) out += ShowNotice(Notice.MIC_STOPPED)                       // 정차 중에만. 주행 중이면 다음 P에서 띄운다
    }
    car { copy(pauses = pauses + r) }
    val x = ses ?: return
    enter(x.stage.withWindow(null))                                                            // 판정 멈춤
    unjudge(Unjudged.valueOf(r.name))
}

internal fun Tx.inputRestored(r: PauseReason) {
    val was = s.car.pauses
    car { copy(pauses = pauses - r) }
    ses ?: return
    if (r !in was) return
    if (was.size == 1) reopenWindow()
}
