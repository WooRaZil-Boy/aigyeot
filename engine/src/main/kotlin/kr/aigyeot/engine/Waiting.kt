package kr.aigyeot.engine

// 수명 표(설계 3.6, 계약 2.0 P6, D6): 대기 중인 것(행 Waiting) × 생애 사건(열 Life) → 운명(Fate). 표는 fate() 하나, 처리는 lifecycle() 하나다.
// F-A(C319): 장면 시작은 실제 상태가 말하는 중인지와 상관없이 Suspend처럼 끊고(V9), 장면 끝은 장면 세션 뒷창을 되돌리고(V11), 기록 지우기는 장부를 버리지 않는다(V19).
// 장부(HandoffLedger)는 늘 묶인 줄을 따라간다(C258): 줄이 끊기거나 버려지면 Step 끝 flushLedgers가 이름 없는 알림·false로 확정한다.
// 그래서 장부 칸이 KEEP인 열(Suspend·통화·장면 시작·복원 ③·기록 지우기)에서도 줄이 사라지면 그 Step 끝에, 남으면 그 줄이 끝날 때 확정된다.

/** P6 대기 중인 것(수명 표의 행). 행을 더하면 fate()의 when(else 없음)이 컴파일 오류로 표에 넣으라고 알린다. lifecycle은 이 선언 순서로 처리한다. */
internal sealed interface Waiting {
    /** 인계 알림 장부(V6). 열린 세션 것인지는 상태로 가른다. */
    data class Ledger(val a: HandoffLedger) : Waiting
    /** 재생(Play)과 그 자동 시도 단계(ATTEMPT·GAP·CONFIRM의 결과 기다림). 경보 대기(PendingAlarm)는 ATTEMPT 안에만 있어(D5) 이 행과 같이 간다. */
    data object Playing : Waiting
    /** 말하는 줄(큐 맨 앞, speech.speaking)과 실행기 발화. */
    data object Speaking : Waiting
    /** 큐에서 차례를 기다리는 줄(말하는 맨 앞 줄 빼고). 실행기에 아직 없어 CUT도 DROP도 큐에서 빼기만 한다. */
    data class Queued(val line: Line) : Waiting
    /** 쉼터 질문의 답 기다림(session.pendingAsk, 실행기 답 듣기). */
    data object AskAnswer : Waiting
    /** ④ 질문 창(speech.qa, 실행기 듣기·link 답). */
    data object QaTurn : Waiting
    /** 경유지 결과 기다림(session.waypointPending). */
    data object Waypoint : Waiting
    /** 수유실 들름(ign.nursingStop, 결정 ①: 시동 단위). */
    data object Nursing : Waiting
    /** 쾌적 조정 결과 기다림(session.comfortPending, C102). */
    data object Comfort : Waiting
    /** 뒷창 잠금·되돌림 결과 기다림(ign.windowLockPending). */
    data object WindowLock : Waiting
    /** 앰비언트 조명 낮춤·되돌림 결과 기다림(ign.ambient, 기능 D C360). 처리 순서는 뒷창 다음. */
    data object Ambient : Waiting
    /** 진행 중 조향 창(steer.window). */
    data object SteerWindow : Waiting
    /** 세션 예약(ign.reserved, 1c). 타입이 아니라 칸이다. */
    data object Reserved : Waiting
}

/** 생애 사건(수명 표의 열). */
internal enum class Life {
    /** 13a 세션 닫힘(closeSession). */
    SESSION_CLOSE,
    /** 복원 ②(Room에서 이미 끝난 세션, C137): 13a의 할 일은 그때 나갔으니 SESSION_CLOSE 열의 DROP만 상태에 맞춘다. */
    ROOM_CLOSED,
    /** 1a·1b 새 세션(maybeOpen). */
    SESSION_OPEN,
    /** 장면 시작: 보관할 실제 상태(Engine.sceneStart). 장면이 실행기·결과 사건을 넘겨받으므로 실제 감시를 Suspend처럼 멈춘다(V9). */
    SCENE_START,
    /** 장면 끝: 버릴 장면 상태(Engine.sceneEnd). */
    SCENE_END,
    /** 복원 ③ 비우기(Engine.afterRestore). 프로세스 시작과 장면 끝이 같이 탄다. */
    RESTORE,
    /** 복원 ④ Boot: 새 프로세스라 옛 콜백이 오지 않는다(14g). */
    BOOT,
    /** 기록 지우기(ClearRecords, 5.9). */
    CLEAR,
    SUSPEND,
    RESUME,
    /** 통화 시작(CallMode(inCall = true), 15a·15b). */
    CALL,
}

/**
 * KEEP 남김. CUT 끊음(실행기 끊기 할 일 + 상태 비움: 재생은 열마다 멈춤·WAIT, 말하기·질문·④는 CancelSpeech 하나). SETTLE 장부 확정.
 * DROP 버림(콜백이 오지 않거나 받을 곳이 사라져 할 일 없이 비움. 뒷창 잠금만 C86 다시 잠금을 낸다). RESTORE 제어 되돌림(뒷창 13a·3f·V11, 앰비언트 AM15).
 * HIDE 상태는 남기고 띄운 알림만 내림(C349, 수유 확인 알림).
 */
internal enum class Fate { KEEP, CUT, SETTLE, DROP, RESTORE, HIDE }

/** 세션이 닫히면 큐에서 빼는 제안 문장(C140). */
private val SESSION_OFFERS = setOf(Sid.S06, Sid.S07, Sid.S07_DEPART, Sid.S08, Sid.S10, Sid.S11, Sid.S15)

/**
 * 수명 표(설계 3.6) 그대로인 순수 함수. [s]는 lifecycle을 시작할 때의 상태다(세션 닫힘에서 그 장부의 줄을 말하는 중인지, 열린 세션 장부인지만 본다).
 * 열(Life)은 KEEP이 아닌 칸만 적는다(나머지 KEEP). '—'(해당 없음)도 KEEP이다.
 */
internal fun fate(w: Waiting, life: Life, s: EngineState): Fate {
    if (life == Life.ROOM_CLOSED) return if (fate(w, Life.SESSION_CLOSE, s) == Fate.DROP) Fate.DROP else Fate.KEEP
    return when (w) {
        is Waiting.Ledger -> {
            val open = s.session != null && w.a.sessionId == s.session.id
            when (life) {
                // C152: 그 줄을 말하는 중이면 닫힌 세션 장부로 남아 그 SpeechDone까지 따라간다
                Life.SESSION_CLOSE -> if (open && !(w.a.utteranceId != null && w.a.utteranceId == s.speech.speaking)) Fate.SETTLE else Fate.KEEP
                Life.SCENE_END -> Fate.SETTLE   // C290: 알림 없이 Room만(호스트가 SceneEnd.settle로 store에)
                // CLEAR(V19): 알림은 기록이 아니라 지금 우는 아기에 대한 통지라 버리지 않는다. 줄을 따라 알림이 나가고 Room 확정은 recordsCleared·C262가 막는다.
                // 여기서 바로 확정하면 같은 Step의 DeleteRecords(보낼 함의 미전송 알림 지우기)보다 앞서 넣어져 지워진다
                else -> Fate.KEEP
            }
        }
        Waiting.Playing -> when (life) {
            Life.SESSION_CLOSE, Life.SCENE_START, Life.SCENE_END, Life.SUSPEND, Life.CALL -> Fate.CUT   // SCENE_START(V9): 장면 재생이 덮을 때까지 두지 않는다
            // RESTORE: 호스트가 lifecycle 앞에서 사실 사건 PlaybackEnded(ERROR, recorded)로 끝낸다(Room 읽기가 필요, r14r·r14s. 멈춘 시각은 스냅숏 now, V10)
            else -> Fate.KEEP
        }
        Waiting.Speaking -> when (life) {
            Life.SCENE_START, Life.SCENE_END, Life.SUSPEND, Life.CALL -> Fate.CUT   // C191·V9: 말하는 줄이 없어도 실행기를 끊는다(답·④ 듣기)
            Life.RESTORE -> Fate.DROP   // C123·C151: 말하지 않은 것으로 버린다(장면 중에는 실제 발화가 없다, C187)
            else -> Fate.KEEP           // SESSION_CLOSE: 끝까지 말한다(인계 줄이면 장부가 따라간다, C152)
        }
        is Waiting.Queued -> when (life) {
            Life.SESSION_CLOSE -> if (w.line.sid in SESSION_OFFERS) Fate.DROP else Fate.KEEP   // C140
            Life.SCENE_START, Life.SUSPEND, Life.CALL -> Fate.CUT   // V9: 장면 뒤 옛 값(S06 경과 시간 등)을 말하지 않게
            Life.SCENE_END -> Fate.DROP
            // C138·C258. 옛 값 S06·④ 답이 남는 것은 프로세스 재시작뿐이다(장면 끝 복원은 장면 시작이 이미 비웠다, V9)
            Life.RESTORE -> if (w.line.longDrive && !w.line.handoff) Fate.DROP else Fate.KEEP
            Life.CLEAR -> if (w.line.sid == Sid.QA) Fate.DROP else Fate.KEEP   // C148
            else -> Fate.KEEP
        }
        Waiting.AskAnswer -> when (life) {
            Life.SESSION_CLOSE, Life.SCENE_END, Life.RESTORE -> Fate.DROP
            Life.SCENE_START, Life.SUSPEND, Life.CALL -> Fate.CUT   // V9: 질문이 끝나 듣는 중이어도 끊는다(실제 '응'이 장면 상태로 가 버려지지 않게)
            else -> Fate.KEEP
        }
        Waiting.QaTurn -> when (life) {
            Life.SCENE_END, Life.RESTORE, Life.CLEAR -> Fate.DROP
            Life.SCENE_START, Life.SUSPEND, Life.CALL -> Fate.CUT   // V9
            else -> Fate.KEEP           // SESSION_CLOSE: ④는 세션 없이도 된다
        }
        Waiting.Waypoint -> when (life) {
            // BOOT: 옛 AddWaypoint 콜백은 오지 않는다(C84). SCENE_START(V9): 결과는 장면 상태로 가 받을 곳이 없다. 실행기 요청은 끊지 않는다
            // (운전자가 '응'한 경유지 넣기라 내비에는 들어가게 두고, 늦은 결과는 cmdId가 달라 장면이 버린다, C65)
            Life.SESSION_CLOSE, Life.SCENE_START, Life.BOOT -> Fate.DROP
            else -> Fate.KEEP
        }
        // 결정 ①(D1): 시동 단위라 세션 닫힘·새 세션·Boot도 지우지 않는다. C349: 알림은 그때 쓰는 상태의 확인 투영이라 장면 시작은 실제 알림만 내리고(상태 유지,
        // 장면 끝 Boot가 다시 띄운다), 장면 끝은 장면 상태의 확인을 버려 알림을 내린다
        Waiting.Nursing -> when (life) {
            Life.SCENE_START -> Fate.HIDE
            Life.SCENE_END -> Fate.DROP
            else -> Fate.KEEP
        }
        Waiting.Comfort -> when (life) {
            Life.SESSION_CLOSE, Life.SCENE_START, Life.BOOT -> Fate.DROP   // BOOT: 옛 ControlResult는 오지 않는다(C102). SCENE_START: 결과는 장면 상태로 가고 그 재생도 끊었다
            else -> Fate.KEEP
        }
        Waiting.WindowLock -> when (life) {
            Life.SESSION_CLOSE, Life.SCENE_END -> Fate.RESTORE   // 13a·3f. SCENE_END(V11): 장면 세션이 닫히기 전에 끝나도 되돌린다
            Life.BOOT -> Fate.DROP                    // C86: 결과 대기분 버림·열린 세션 영역 다시 잠금(장면 시작 때 남긴 실제 잠금 대기도 장면 끝 Boot가 맞춘다)
            else -> Fate.KEEP   // SESSION_OPEN: 3g가 같은 영역을 기다린다(lockWindows)
        }
        // 조명은 기록이 아니라 KEEP이 많다: 재생·세션이 끝나면 Step 끝 syncAmbient(투영)가 되돌린다. SCENE_END는 장면 상태의 back 있는 Dimmed를 되돌리고(AM15),
        // BOOT는 옛 결과가 오지 않아 Dimming은 버리고 Restoring은 Dimmed로 돌려 다시 보낸다(AM13·AM13a)
        Waiting.Ambient -> when (life) {
            Life.SCENE_START -> Fate.CUT   // C367: 실제 Dimming의 결과는 장면 상태로 가 버려지니 칸을 비우고 실행기 낮춤 작업을 장부 되돌림으로 버린다. Dimmed·Restoring은 그대로(realStep 끝 AM5)
            Life.SCENE_END -> Fate.RESTORE
            Life.BOOT -> Fate.DROP
            else -> Fate.KEEP
        }
        Waiting.SteerWindow -> when (life) {
            Life.SESSION_CLOSE, Life.RESTORE -> Fate.DROP   // C50. RESTORE: C78 이미 커밋된 창을 옛 부분 집계로 덮지 않게
            else -> Fate.KEEP
        }
        Waiting.Reserved -> when (life) {
            Life.SESSION_OPEN, Life.SUSPEND -> Fate.DROP   // 1a: 열며 씀. C14: Suspend가 지움
            else -> Fate.KEEP
        }
    }
}

/**
 * 수명 표를 따라 대기를 처리하는 유일한 함수(P6). 행 순서(Waiting 선언 순서)대로 처리한다: 장부 → 재생 → 말하기 → … → 예약.
 * 운명은 시작할 때의 상태로 정한다(앞 행의 처리가 '말하는 중'을 바꿔도 같은 열의 판단은 그대로).
 * 칸은 직접 쓰지 않고 그 행 주인 리듀서의 함수를 부른다(P3, D7): 장부 Offer, 재생 Soothe, 말하기·큐 Speech, 질문·경유지 Offer, ④ Qa,
 * 수유실 Feeding, 쾌적·뒷창 Control, 조향 창 Record, 예약 Life.
 */
internal fun Tx.lifecycle(life: Life) {
    val s0 = s
    fun f(w: Waiting) = fate(w, life, s0)
    for (a in s0.handoffs) when (f(Waiting.Ledger(a))) {
        Fate.SETTLE -> if (life == Life.SCENE_END) dropLedger(a) else settleLedger(a, false)   // SCENE_END: Room 확정은 호스트(SceneEnd.settle)
        Fate.DROP -> dropLedger(a)
        else -> {}
    }
    if (f(Waiting.Playing) == Fate.CUT) cutPlaying(life)
    // 말하기·질문 답 듣기·④ 듣기는 실행기(Voice) 하나라 하나라도 CUT이면 CancelSpeech 하나(G30·Q8)
    val speak = f(Waiting.Speaking)
    if (speak == Fate.CUT || f(Waiting.AskAnswer) == Fate.CUT || f(Waiting.QaTurn) == Fate.CUT) out += CancelSpeech
    if (s.speech.speaking != null) when (speak) {
        Fate.CUT -> cutSpeaking()
        Fate.DROP -> endSpeaking()   // 말하기 간격(S1)은 지금부터
        else -> {}
    }
    dropQueued { f(Waiting.Queued(it)) != Fate.KEEP }   // 말하는 맨 앞 줄은 위 Speaking 행이 정했다
    if (f(Waiting.AskAnswer) != Fate.KEEP) dropAsk()
    if (f(Waiting.QaTurn) != Fate.KEEP) closeQa()
    if (f(Waiting.Waypoint) != Fate.KEEP) dropWaypoint()
    when (f(Waiting.Nursing)) {
        Fate.HIDE -> if (s.ign.nursingStop?.confirmShown == true) out += HideNotice(Notice.FEEDING_CONFIRM)
        Fate.DROP -> dropNursing()
        else -> {}
    }
    if (f(Waiting.Comfort) != Fate.KEEP) dropComfort()
    when (f(Waiting.WindowLock)) {
        Fate.RESTORE -> ses?.let { restoreWindows(it.id) }
        Fate.DROP -> dropLostLocks()
        else -> {}
    }
    when (f(Waiting.Ambient)) {   // 뒷창 다음(기능 D, C360)
        Fate.RESTORE -> restoreAmbientAtSceneEnd()
        Fate.CUT -> cutAmbientAtSceneStart()
        Fate.DROP -> dropAmbient()
        else -> {}
    }
    if (f(Waiting.SteerWindow) != Fate.KEEP) dropSteerWindow()
    if (f(Waiting.Reserved) != Fate.KEEP) dropReserved()
}

/** 재생·자동 시도 행의 CUT(열마다 다르다). */
private fun Tx.cutPlaying(life: Life) {
    val x = ses ?: return
    when (life) {
        Life.SESSION_CLOSE -> {                                                                // 13a
            stopPlay(PlayEndReason.SESSION_END)
            if (ses!!.episode != null) { unjudge(Unjudged.SESSION_END); closeEpisode() }
        }
        // 14f·15a·15b: 자동 시도면 WAIT. 장면 시작(V9)은 실제 감시를 멈추는 것이라 Suspend와 같은 사유다(리포트 '일시 중단', 사유 값을 새로 만들지 않는다)
        Life.SUSPEND, Life.CALL, Life.SCENE_START -> {
            val r = if (life == Life.CALL) Unjudged.CALL else Unjudged.SUSPEND
            if (x.phase in AUTO_PHASES) toWait(r) else stopPlay(PlayEndReason.valueOf(r.name))
        }
        Life.SCENE_END -> x.play?.let { out += StopPlayback(it.id, false) }   // 장면 상태는 버린다: 실행기만 멈추고 장면 세션 기록은 Room 정리가 닫는다(C98)
        Life.ROOM_CLOSED, Life.SESSION_OPEN, Life.RESTORE, Life.BOOT, Life.CLEAR, Life.RESUME -> {}   // 표에 CUT이 없는 열
    }
}
