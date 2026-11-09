package kr.aigyeot.engine

// 라우터(설계 3.5): 한 Step의 차례(run), 시간(advance·timers), 사건 → 리듀서 표(handle), 다음 깨울 시각(wakeAt).
// 주인 칸(설계 3.3, D7): 바깥 사실(Facts: profile·methodsNext·otherGuardians·care·careSeq·careRoom·history). D4에서 FactsReducer를 합쳤다.
// 다른 칸은 그 주인 리듀서의 함수를 부른다(now는 Tx.clock, 시간 전이는 Qa·Soothe·Offer·Feeding의 timer 함수).

internal fun Tx.run(e: Event): Step {
    // Boot·Resume과 복원 보정 사건(2.4 ③: MicService, InputLost, 오류 PlaybackEnded)은 시간 기준만 그 시각으로 맞춘다.
    // 그 앞 공백(프로세스가 죽어 있던 시간일 수 있다)을 quietMs에 넣지 않고, 시간 전이·장시간 제안·말하기도 내지 않는다(14g).
    // 평소에 오는 같은 사건은 다음 Tick(1초 안)에 이어 가므로 잃는 것이 없다.
    val rebase = e is Boot || e is Resume || e is MicService || e is InputLost ||
        (e is PlaybackEnded && e.reason != EndReason.DEADLINE)
    val waitFrom = routeWaitFrom()   // 이 사건 앞의 경로 기다림
    advance(e.at, countQuiet = !rebase)
    // 지금 재생의 PlaybackEnded는 그 사건이 끝을 정한다(복원 C58: endAt 뒤에 와도 시간 끝으로 보지 않는다).
    if (!rebase) timers(skipPlay = e is PlaybackEnded && e.playId == ses?.play?.id)
    handle(e)
    // C285: 경로가 정해졌다(목록이 오거나 routeWaitMs가 지남). C295: 이어진 기다림의 시작이 앞으로 밀려도 보류 줄 기준을 함께 옮긴다
    val waitAfter = routeWaitFrom()
    if (waitFrom != null && waitAfter != waitFrom) restartHeld(waitFrom)
    if (!rebase && (e is GearChanged || e is RouteState || (e is CallMode && !e.inCall))) departOffer()   // C153: 통화가 끝나도 다시 본다
    if (e is MicService && e.running) departOffer()   // C350: 마이크가 켜졌으니 꺼져 있어 미룬 출발 제안을 다시 본다
    if (e is Resume) departOffer()   // C163: Suspend 중엔 보지 않았으니 풀린 뒤 다시 본다(같은 경로 값은 RouteFeed가 다시 내지 않는다)
    if (!rebase) { longDrive(); drain() }
    flushLedgers()
    syncAmbient()   // 기능 D(C358): 목표와 칸을 견줘 낮춤·되돌림을 낸다. SaveSnapshot 앞이라 스냅숏에 칸이 든다
    if (snapshot) out += SaveSnapshot(s)
    return Step(s, out, wakeAt())
}

/** 시간을 흘린다. HANDOFF·WAIT 5분 타이머는 판정 멈춤·Suspend 중엔 세지 않는다(3-11, 3-21, C10). */
internal fun Tx.advance(at: Long, countQuiet: Boolean) {
    val t = maxOf(at, s.now)
    val x = ses
    if (countQuiet && x != null && quietCounts(x)) enter(x.stage.withQuiet(x.quietMs + (t - s.now)))
    clock(t)
}

/** 시간 전이. 사건보다 먼저 본다(늦은 Tick 대신 구간이 먼저 와도 창이 endAt에 붙는다, 5.3). */
internal fun Tx.timers(skipPlay: Boolean) {
    qaTimer()                                                                                // Q3
    ses?.play?.let { if (!skipPlay && now >= it.endAt) playDone(it.endAt) }                  // 6a·12a·18f·18h·18k
    ses?.let { if (quietDone(it)) closeEpisode() }                                           // 11a
    parkReset()                                                                              // L2
    nursingTimer()                                                                           // F8: 세션 없이도 돈다(결정 ①)
}

internal fun Tx.handle(e: Event) {
    when (e) {
        is Tick -> {}
        is Boot -> boot(e)
        is Resume -> { newIgnition(); lifecycle(Life.RESUME) }
        is Suspend -> suspend()
        is CrySegment -> cry(e)
        is InputLost -> inputLost(e.reason)
        is InputRestored -> inputRestored(e.reason)
        is MicService -> micService(e)
        is GearChanged -> gear(e.gear)
        is RearDoor -> door(e)
        is SeatHeat -> { seatHeat(e); heatGuard(e) }   // 차 사실(Life) → 3c(Control)
        is CabinTemp -> cabinTemp(e)
        is VehicleSample -> vehicle(e)
        is RouteState -> route(e)
        is CallMode -> call(e.inCall)
        is FocusChanged -> focus(e)
        is PlaybackStarted -> started(e)
        is PlaybackRejected -> rejected(e)
        is PlaybackEnded -> ended(e)
        is SpeechDone -> speechDone(e)
        is Answer -> answer(e)
        is ControlResult -> when (e.control) {
            ControlKind.SEAT_HEAT -> heatResult(e)
            ControlKind.WINDOW_LOCK -> windowResult(e)
            ControlKind.HVAC_TEMP -> hvacResult(e)
            ControlKind.AMBIENT_BRIGHTNESS, ControlKind.AMBIENT_COLOR -> ambientResult(e)
            else -> {}
        }
        is WaypointResult -> waypoint(e)
        is DriverStop -> if (tool(e.listenId)) driverStop()
        is SwitchSound -> if (tool(e.listenId)) switchSound(e.method)
        is DriverAddWaypoint -> if (tool(e.listenId)) addWaypoint(e.restId)
        is AskButton -> if (s.canAsk()) listen()          // Q2. C303 ⑤: 홈 화면 버튼과 같은 함수
        is Heard -> heard(e)
        is QaReply -> qaReply(e)
        is BabyAboard -> babyAboard()
        is FeedingStarted -> feedingStarted(e)
        is FeedingConfirmed -> feedingConfirmed(e.yes)
        is ProfileChanged -> s = s.copy(profile = e.profile)
        is ClearRecords -> {                                                                  // C40a·C40b
            s = s.copy(history = emptyList())   // care는 지우지 않는다(C40e, C157)
            // 수명 표 CLEAR: ④ 질문 창을 닫고 아직 말하지 않은 ④ 답 줄을 뺀다(C148, 말하는 맨 앞 줄은 둔다). 인계 장부는 두어 묶인 줄을 따라
            // 알림을 보낸다(C319 V19: 알림은 기록이 아니다). 지운 세션 행에 되쓰지 않는 것은 recordsCleared(rec)·store C262가 맡는다
            lifecycle(Life.CLEAR)
            resetCryRuns()     // Soothe(C277·C287)
            clearRecords()     // Record: recordsCleared·DeleteRecords·DeleteRelayRecords·스냅숏
        }
        is MethodsChanged -> s = s.copy(methodsNext = e.methods)
        is LinkChanged -> s = s.copy(otherGuardians = e.otherGuardians)
        is CareSummary -> if (careReplaces(s.care, s.careSeq, e.summary, e.seq, s.careRoom, e.room)) {   // CS1·CS2
            val changed = s.care != e.summary
            s = s.copy(care = e.summary, careSeq = e.seq, careRoom = e.room)
            if (changed) dropQa()   // C178: 새 요약이면 옛 요약으로 만든 ④ 답을 말하지 않는다
        }
        is CareCleared -> {   // C158·C170: ④ 질문 창도 닫고 말하지 않은 ④ 답 줄을 뺀다(C148과 같다)
            s = s.copy(care = null, careSeq = null, careRoom = null)
            dropQa()
        }
    }
}

// ---- 성적(history, Facts 주인) 쓰기: 다른 리듀서는 이 함수를 부른다 ----

/** 에피소드 닫기(Soothe)가 남긴 1차 결과. */
internal fun Tx.addFirstTry(t: FirstTry) { s = s.copy(history = s.history + t) }

/** 세션을 열 때(Life) 28일 창 밖 성적을 버린다. */
internal fun Tx.trimHistory(cut: Long) { s = s.copy(history = s.history.filter { it.at >= cut }) }

/** 복원 ②(Restore): Room에서 다시 센 성적. */
internal fun Tx.roomHistory(h: List<FirstTry>) { s = s.copy(history = h) }

internal fun Tx.wakeAt(): Long? {
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
        if (quietClosable(x)) c += now + p.resumeQuietMs - x.quietMs
        x.driveFrom?.let { if (!x.longDriveOffered) c += it + p.longDriveMs }
        if (routePending()) listOfNotNull(s.bootAt, s.car.route?.restsPendingSince).forEach { c += it + p.routeWaitMs }   // 지난 쪽은 아래에서 빠진다
        if (gearP && x.driveFrom != null) s.car.gearSince?.let { c += it + p.longDriveResetParkMs }
    }
    s.ign.nursingStop?.let { ns -> if (ns.firstPAt != null && !ns.confirmShown && gearP) c += ns.firstPAt + p.feedingParkConfirmMs }
    return c.filter { it > now }.minOrNull()
}
