package kr.aigyeot.engine

// 감지·판정·시도·재생: 울음 구간, 판정 창, 시도 시작·재생 시작·거절·끝, WAIT·HANDOFF 들어가기, 에피소드 닫기, 운전자 요청(그만·소리 바꾸기·포커스).
// 주인 칸: session.stage(단계, Stage.kt의 enter)·episode·playedMs·alarmRun·cryRuns·output. 에피소드 쓰기는 이 파일의 private ep {}와 enter뿐이다.
// 다른 리듀서가 에피소드에 적을 일(인계 종류·경유지·S06)은 아래 mark* 함수를 부른다. 재생 결과(Playback*·FocusChanged)는 waitingFor로 낼 때 잡은 Play만 쓴다.

private fun Tx.ep(f: EpisodeState.() -> EpisodeState) = ses { copy(episode = episode?.f()) }

// ---- 감지 ----

internal fun Tx.cry(e: CrySegment) {
    val x = ses ?: return
    if (s.car.pauses.isNotEmpty()) return                   // 판정 멈춤 중 들어온 구간은 버린다
    // 이 구간을 봤나(sees) 하나를 횟수·경보·판정·울음 시간·조향 창 cry_present가 같이 쓴다.
    // 자동 시도 재생 중(ATTEMPT, 재생 소리를 울음으로 오인하지 않게)과 그 재생이 멈추기 전에 시작한 구간(재생 소리가 섞임)은 어느 단계에서도 보지 않는다. 직접 튼 소리(WAIT)는 본다
    val seen = s.sees(e)
    val alarm = seen && e.alarm                             // 본 경보. 단계마다 따로 거르지 않는다
    if (alarm) cryRun()                                     // 경보가 이어지는지 보고 새로 열리면 울음 수 하나
    val loud = seen && e.p >= p.alarmThreshold
    steerCry(seen, loud)                                    // Record: 조향 창의 observed·cry_present
    when (x.phase) {
        Phase.LISTENING -> if (alarm) when {
            s.car.inCall -> logAlarm(AlarmOnly.IN_CALL)
            x.capReached(p) -> logAlarm(AlarmOnly.SESSION_CAP)
            suspended -> {}
            // 지연 기준점 = 경보를 이룬 첫 구간(바로 앞 구간)의 start. 구간은 0.975초씩 밀린다.
            else -> startAttempt(1, PendingAlarm(now, e.injectedCryAt ?: (e.start - p.yamnetHopMs)))
        }
        Phase.HANDOFF, Phase.WAIT -> if (alarm) { enter(ses!!.stage.withQuiet(0)); logAlarm(AlarmOnly.WAITING) }
        Phase.GAP, Phase.CONFIRM -> judge(e, loud)
        Phase.ATTEMPT -> {}
    }
    // 판정 뒤에도 열려 있는 에피소드에만(성공으로 닫게 한 경계 구간은 다음 울음의 것, 다음 경보 시작값과 겹치지 않게)
    if (loud) ep { copy(cryMs = cryMs + p.yamnetHopMs) }
}

/** 판정 구간: start ≥ judgeFrom(창 시작), end ≤ windowEnd. 창은 end > windowEnd인 첫 구간이 닫는다. [loud]는 본 구간([sees])의 울음. */
internal fun Tx.judge(e: CrySegment, loud: Boolean) {
    val x = ses!!
    val w = x.stage.window ?: return
    val end = w.end
    val cried = loud && e.start >= w.from                                 // 창 시작 앞에 시작한 구간은 판정하지 않는다(창 시작만 뜻한다)
    if (cried && e.end <= end) { cryRun(); return recry() }
    if (e.end <= end) return
    if (x.stage is Stage.Gap) {
        enter(Stage.Confirm(x.attemptNo, w.copy(end = end + p.confirmMs)))
        if (cried && e.end <= end + p.confirmMs) { cryRun(); recry() }
    } else success()
}

/**
 * 본 울음이 이어진 울음([alarmContinues])을 잇거나 새로 연다. 새로 열면 울음 수 하나.
 * 판정이 '다시 울었다'고 본 한 구간(GAP·CONFIRM)도 이어진 울음이 없으면 새로 열어 센다(같은 구간의 경보는 이미 이었으니 두 번 세지 않는다).
 */
internal fun Tx.cryRun() {
    val live = ses!!.alarmRun?.takeIf { alarmContinues(it.last, now, p) }
    ses { copy(alarmRun = live?.copy(last = now) ?: AlarmRun(now, now), cryRuns = cryRuns + if (live == null) 1 else 0) }
}

private fun TryRecord?.decide(r: TryOutcome) = this?.let { if (it.outcome == null) it.copy(outcome = r) else it }

internal fun Tx.recry() {
    if (ses!!.attemptNo <= 1) {
        ep { copy(recried = true, first = first.decide(TryOutcome.Fail)) }
        saveEpisode()
        startAttempt(2)
    } else {
        ep { copy(second = second.decide(TryOutcome.Fail)) }
        handoff(HandoffCause.TWO_ATTEMPTS)
    }
}

internal fun Tx.success() {
    if (ses!!.attemptNo <= 1) ep { copy(first = first.decide(TryOutcome.Success)) }
    else ep { copy(second = second.decide(TryOutcome.Success)) }
    closeEpisode()
}

/** 판정 창을 지금부터 다시 잰다. GAP·CONFIRM이 아니면 그대로. 호스트 장면 끝도 Engine.reopenWindow로 이 함수를 쓴다. */
internal fun Tx.reopenWindow() {
    val st = ses?.stage ?: return
    if (st is Stage.Gap || st is Stage.Confirm) enter(st.withWindow(JudgeWindow(now, now + if (st is Stage.Gap) p.gapMs else p.confirmMs)))
}

// ---- 시도·재생(공통 절차) ----

/** 시도 시작(n). 길이 = min(56초, 세션 남은 재생 초). [alarm]은 1차의 경보 시각. */
internal fun Tx.startAttempt(n: Int, alarm: PendingAlarm? = null) {
    val x = ses!!
    if (x.capReached(p)) return handoff(HandoffCause.SESSION_CAP)
    var action: SecondAction? = null
    val method = if (n == 1) {
        if (feeding()) best(x.methods, s.history)
        else pick { pickFirst(x.methods, s.history, it, p.roundRobinPerMethod) }
    } else {
        val first = x.episode?.first?.method
        action = if (hot()) SecondAction.COMFORT else if (feeding()) SecondAction.FEEDING else SecondAction.OTHER  // 시작할 때 한 번
        if (action == SecondAction.COMFORT && first != null) first else best(x.methods, s.history, first)
    }
    val end = now + minOf(p.attemptMs, p.sessionPlayCapMs - x.playedMs)
    val id = newId()
    out += StartPlayback(id, PlayKind.AUTO, method, end, p.fadeMs)
    val purpose = if (n == 1) PlayPurpose.AUTO_FIRST else PlayPurpose.AUTO_SECOND
    enter(Stage.Attempt(n, Play(id, purpose, method, null, end, PlayMode.NORMAL, action), alarm))
}

/** [pa]는 1차 시작 전 경보 시각(ATTEMPT의 alarm). 에피소드로 옮기면 단계에서 지운다. */
internal fun Tx.openEpisode(trigger: EpisodeTrigger, first: TryRecord?, pa: PendingAlarm?) {
    val e = EpisodeState(
        newUlid(), trigger, pa?.alarmAt ?: now, pa?.alarmAt, pa?.let { it.alarmAt - it.delayFromAt },
        0, first, null, false, null, emptyList(), if (pa != null) p.alarmSegments * p.yamnetHopMs else 0, false,   // 경보를 이룬 구간도 들은 울음
    )
    ses { copy(episode = e) }
    (ses?.stage as? Stage.Attempt)?.let { enter(it.copy(alarm = null)) }
}

internal fun Tx.started(e: PlaybackStarted) {
    val pl = s.waitingFor(e) ?: run { out += StopPlayback(e.playId, false); return }          // 모르는 재생
    if (pl.startedAt != null) return
    val st = ses!!.stage
    val alarm = (st as? Stage.Attempt)?.alarm
    enter(st.withPlay(pl.copy(startedAt = now)))
    ses { copy(output = if (e.fallback || output == OutputRoute.DEFAULT_FALLBACK) OutputRoute.DEFAULT_FALLBACK else OutputRoute.REAR_ZONE) }
    if (pl.method.isSong()) songStarted()                                                       // 듣는 도중 시작
    val method = mapOf("method" to methodLabel(pl.method.kind))
    when (pl.purpose) {
        PlayPurpose.AUTO_FIRST -> {
            val fw = feeding()
            val un = if (gearP) Unjudged.PARKED_ALARM else if (fw) Unjudged.FEEDING_WINDOW else null
            openEpisode(EpisodeTrigger.ALARM, TryRecord(pl.method, null, un?.let { TryOutcome.NotJudged(it) }), alarm)
            s.car.pauses.forEach { unjudge(Unjudged.valueOf(it.name)) }                         // 시작 대기 중 끊긴 입력
            saveEpisode()
            captureContext()
            if (gearP) out += SetReadingLight(newId(), babyAreas(), false)
            say(Line(Sid.S02, method, null, playId = pl.id))
            if (fw && !gearP) { saySinceFeeding(); offer(false) }
        }
        PlayPurpose.AUTO_SECOND -> {
            val action = pl.action ?: SecondAction.OTHER
            ep { copy(second = TryRecord(pl.method, action)) }
            when (action) {
                SecondAction.COMFORT -> lowerHvac(pl.id)                                        // Control. S04는 결과가 ok로 온 뒤
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

internal fun Tx.rejected(e: PlaybackRejected) {
    val x = ses ?: return
    val pl = s.waitingFor(e) ?: return
    val alarm = (x.stage as? Stage.Attempt)?.alarm
    enter(x.stage.withPlay(null))
    when (pl.purpose) {
        PlayPurpose.AUTO_FIRST -> if (e.reason == RejectReason.NO_REAR_OUTPUT) {
            openEpisode(EpisodeTrigger.ALARM, null, alarm)
            saveEpisode()
            captureContext()
            handoff(HandoffCause.NO_SOUND)
        } else {
            enter(Stage.Listening)
            logAlarm(AlarmOnly.FOCUS_DENIED)
        }
        PlayPurpose.AUTO_SECOND -> { enter(Stage.Wait(x.attemptNo)); saveEpisode() }
        PlayPurpose.REQUEST -> {                                                                // 운전자 요청 재생을 거절한 경우
            qaTool = pl.fromQa   // 질문 답 도구로 연 재생인지는 낼 때 잡은 값(지금 질문 창으로 짐작하지 않는다)
            if (e.reason == RejectReason.FOCUS_DENIED) ruleSay(Sid.S18) else if (qaTool) ruleSay(Sid.S20)
        }
    }
}

internal fun Tx.ended(e: PlaybackEnded) {
    val pl = s.waitingFor(e) ?: return                                                          // 늦은 STOPPED는 버린다
    if (e.reason == EndReason.DEADLINE) return playDone(pl.endAt)
    // ERROR. 우리가 내지 않은 STOPPED도 예기치 않은 멈춤이라 같게 본다.
    playEnd(now, PlayEndReason.ERROR, e.recorded)
    if (pl.kind == PlayKind.AUTO) toWait(Unjudged.FOCUS_LOSS)
}

/** 재생이 endAt에 끝남(Tick·DEADLINE). stopAt = endAt. */
internal fun Tx.playDone(stopAt: Long) {
    val pl = ses?.play ?: return
    playEnd(stopAt, PlayEndReason.DEADLINE)
    val x = ses!!
    if (pl.kind == PlayKind.REQUEST) {
        if (quietDone(x)) closeEpisode()    // 조용함 5분이 찼을 때와 같은 조건
        return
    }
    if (x.capReached(p)) { unjudge(Unjudged.SESSION_CAP); return handoff(HandoffCause.SESSION_CAP) }
    val paused = s.car.pauses.isNotEmpty()
    enter(Stage.Gap(x.attemptNo, if (paused) null else JudgeWindow(stopAt, stopAt + p.gapMs)))
}

/**
 * 재생 종료 기록(stopAt). 사용 시간은 여기서만 더한다. 멈춘 시각은 endAt을 넘지 않는다(실행기는 endAt에 멈춘다. 늦게 온 끝·복원이 그 뒤를 세지 않는다).
 * 자동 시도 재생 소리가 실제로 났으면 멈춘 시각을 mixedUntil로 남긴다(그 앞에 시작한 구간은 재생 소리가 섞였다, sees). 시작 전·0ms에 멈춘 재생은 섞인 소리가 없다.
 */
internal fun Tx.playEnd(stopAt: Long, reason: PlayEndReason, recorded: Boolean = false) {
    val x = ses ?: return
    val pl = x.play ?: return
    val stop = minOf(stopAt, pl.endAt)
    val used = if (recorded) 0 else pl.startedAt?.let { (stop - it).coerceAtLeast(0) } ?: 0
    val mixed = pl.kind == PlayKind.AUTO && pl.startedAt?.let { stop > it } == true
    ses { copy(playedMs = playedMs + used, episode = episode?.let { it.copy(playedMs = it.playedMs + used) },
        mixedUntil = if (mixed) stop else mixedUntil) }
    enter(x.stage.withPlay(null))
    val ep = ses!!.episode
    if (!recorded && ep != null)
        rec(SavePlay(PlayRecord(pl.id, x.id, ep.id, pl.purpose, pl.method.kind, pl.method.songId, pl.startedAt, stop, used, reason)))
    saveEpisode()
    saveSession()
}

internal fun Tx.stopPlay(reason: PlayEndReason) {
    val pl = ses?.play ?: return
    out += StopPlayback(pl.id, false)
    playEnd(now, reason)
}

/** 1차가 미정이면 UNJUDGED(이유), 정해졌으면 laterReasons(3-E). */
internal fun Tx.unjudge(r: Unjudged) = ep {
    val f = first
    when {
        f == null -> this
        f.outcome == null -> copy(first = f.copy(outcome = TryOutcome.NotJudged(r)))
        r in laterReasons -> this
        else -> copy(laterReasons = laterReasons + r)
    }
}

internal fun Tx.toWait(r: Unjudged) {
    stopPlay(PlayEndReason.valueOf(r.name))   // WAIT로 가는 이유는 모두 같은 이름의 PlayEndReason이 있다
    unjudge(r)
    ses?.let { enter(Stage.Wait(it.attemptNo)) }
    saveEpisode()
}

/** 에피소드 닫기 → LISTENING. 에피소드 한도는 여기서만 초기화한다. */
internal fun Tx.closeEpisode() {
    val x = ses ?: return
    val e = x.episode
    if (e != null) {
        val f = e.first
        if (!x.recordsCleared && f != null && (f.result == TryResult.SUCCESS || f.result == TryResult.FAIL))
            addFirstTry(FirstTry(e.startedAt, f.method, f.result == TryResult.SUCCESS))           // 성적(Facts)
        saveEpisode(endedAt = now)
    }
    enter(Stage.Listening)
}

// ---- 다른 리듀서가 에피소드·울음 수에 적을 일 ----

/** 인계를 시작했다(Offer.handoff). */
internal fun Tx.markHandoff(cause: HandoffCause, kind: HandoffKind) = ep { copy(handoff = HandoffRecord(cause, kind)) }

/** 인계 종류가 바뀌었다(줄을 낼 때·장부를 확정할 때). 뒤 SaveEpisode가 확정 종류를 되돌리지 않게 열린 에피소드에도 적는다. */
internal fun Tx.markHandoffKind(kind: HandoffKind) = ep { copy(handoff = handoff?.copy(kind = kind)) }

/** 이 에피소드에 경유지를 넣었다(Offer.waypoint, 요청 당시 에피소드일 때만). */
internal fun Tx.markWaypointAdded() = ep { copy(waypointAdded = true) }

/** 이 에피소드에서 S06을 말했다(Speech.spoke). */
internal fun Tx.markS06Said() = ep { copy(s06Said = true) }

/** 기록 지우기. 울음 수 0, 이어지던 울음도 끊어 다음 경보부터 새로 센다(지금 울음과 수가 어긋나지 않게). */
internal fun Tx.resetCryRuns() = ses { copy(cryRuns = 0, alarmRun = null) }

/**
 * 복원 ②: 같은 세션이 Room에 남긴 값으로 맞춘다. 재생 누적·울음 수는 하한(경보 에피소드는 저마다 새 울음),
 * Room에서 끝난 에피소드는 닫고(true를 돌려 성적을 Room에서 다시 세게 한다), 아니면 확정 결과가 앞선다.
 */
internal fun Tx.matchRoom(r: RoomFacts): Boolean {
    val x = ses ?: return false
    ses { copy(playedMs = maxOf(x.playedMs, r.sessionPlayedMs), cryRuns = maxOf(cryRuns, r.alarmEpisodes)) }   // 상한 도달은 playedMs로 계산(capReached)
    val e = x.episode ?: return false
    val room = r.episodes[e.id]
    if (room?.endedAt != null) { enter(Stage.Listening); return true }
    ep {
        val f = first
        copy(playedMs = maxOf(playedMs, r.episodePlayedMs),
            first = if (f != null && f.outcome == null && room?.firstResult != null) {
                f.copy(outcome = TryRecord.outcomeOf(room.firstResult, room.firstUnjudged))
            } else f)
    }
    return false
}

// ---- 운전자 요청 ----

internal fun Tx.driverStop() {                                                                             // 운전자가 그만·소리 바꾸기를 요청했다
    val x = ses ?: return
    if (x.phase in AUTO_PHASES) toWait(Unjudged.DRIVER_STOP) else stopPlay(PlayEndReason.DRIVER_STOP)
}

internal fun Tx.switchSound(m: Method) {
    if (s.car.inCall || suspended) return
    val x = ses ?: run { if (qaTool) ruleSay(Sid.S20); return }                                 // 세션이 없을 때 질문 답 도구 요청이면 안내 문장만
    when (x.phase) {
        Phase.LISTENING -> {
            openEpisode(EpisodeTrigger.SWITCH_SOUND, null, null)
            enter(Stage.Wait(0))
            saveEpisode()
            requestPlay(m)
        }
        Phase.ATTEMPT, Phase.GAP, Phase.CONFIRM -> { toWait(Unjudged.SWITCH_SOUND); requestPlay(m) }
        Phase.WAIT -> { stopPlay(PlayEndReason.SWITCH_SOUND); requestPlay(m) }
        Phase.HANDOFF -> ruleSay(Sid.S18)                                                       // 20
    }
}

/** 요청 재생 길이 = min(56초, 에피소드 남은 재생 초, 세션 남은 재생 초). */
internal fun Tx.requestPlay(m: Method) {
    val x = ses!!
    val len = minOf(p.requestPlayMs, p.episodePlayCapMs - (x.episode?.playedMs ?: 0), p.sessionPlayCapMs - x.playedMs)
    if (len <= 0) return ruleSay(Sid.S18)
    val id = newId()
    out += StartPlayback(id, PlayKind.REQUEST, m, now + len, p.fadeMs)
    // 요청 재생은 WAIT에서만 연다(switchSound). fromQa = 질문 답 도구 사건으로 열었나(거절이 이 값을 본다)
    enter(ses!!.stage.withPlay(Play(id, PlayPurpose.REQUEST, m, null, now + len, PlayMode.NORMAL, fromQa = qaTool)))
}

internal fun Tx.focus(e: FocusChanged) {
    val x = ses ?: return
    // GAP·CONFIRM에는 재생이 없어도 이 세션의 앞 재생이 포커스를 영구히 잃었으면 WAIT로 간다
    // 예외: GAP·CONFIRM에는 기다리는 재생이 없어 '이 세션의 앞 재생'을 id 앞부분(newId = "{세션}-{seq}")으로 본다
    if (e.change == FocusChange.LOSS && (x.phase == Phase.GAP || x.phase == Phase.CONFIRM) && e.playId.startsWith("${x.id}-"))
        return toWait(Unjudged.FOCUS_LOSS)
    val pl = s.waitingFor(e) ?: return
    when (e.change) {
        FocusChange.LOSS -> if (pl.kind == PlayKind.AUTO) toWait(Unjudged.FOCUS_LOSS) else stopPlay(PlayEndReason.FOCUS_LOSS)
        FocusChange.LOSS_TRANSIENT -> adjust(pl, PlayMode.PAUSE)
        FocusChange.LOSS_TRANSIENT_CAN_DUCK -> adjust(pl, PlayMode.DUCK)
        FocusChange.GAIN -> if (now < pl.endAt) adjust(pl, PlayMode.NORMAL)
    }
}

internal fun Tx.adjust(pl: Play, mode: PlayMode) {
    out += AdjustPlayback(pl.id, mode)
    enter(ses!!.stage.withPlay(pl.copy(mode = mode)))
}
