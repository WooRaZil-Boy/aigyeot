package kr.aigyeot.engine

// 말하기 큐(설계 3.4 Speech): 넣기(say), 꺼낼 줄 고르기·내기(drain: S1·S5·S6·G15), 말하기 끝(speechDone·endSpeaking·spoke), 큐 고치기.
// 끊기·버리기(통화·Suspend·장면·복원 ③·세션 닫힘)는 수명 표 lifecycle(Waiting.kt)이 정하고 아래 큐 함수로 한다(D6). 버린 제안은 말하지 않았으니 10분 금지가 없다(결정 ②, S6a·C180).
// 주인 칸(설계 3.3, D7): speech.queue·speaking·lastDoneAt. 쓰기는 이 파일의 private speech {}뿐이고 다른 리듀서는 아래 큐 함수를 부른다
// (말하는 맨 앞 줄을 지키는 규칙은 dropQueued 하나). 장부·질문은 Offer, 휴식 기록은 Record, 에피소드는 Soothe의 함수를 부른다.

private fun Tx.speech(f: SpeechQueue.() -> SpeechQueue) { s = s.copy(speech = s.speech.f()) }

// ---- 큐 고치기(다른 리듀서가 부른다) ----

/** [gone]인 줄을 뺀다. 말하는 맨 앞 줄은 둔다(실행기가 말하고 있다. 그 줄의 끝은 SpeechDone·수명 표가 정한다). */
internal fun Tx.dropQueued(gone: (Line) -> Boolean) = speech { copy(queue = queue.filterIndexed { i, l -> (i == 0 && speaking != null) || !gone(l) }) }

/** 줄 내용만 바꾼다(자리·수는 그대로). */
internal fun Tx.mapLines(f: (Line) -> Line) = speech { copy(queue = queue.map(f)) }

/**
 * 그 줄(같은 객체) 자리에 같은 질문의 새 줄을 둔다. 세션 문맥은 이어받되,
 * 세션 id 없는 옛 줄(인계 표시를 달 줄은 세션이 있어야 알아본다, C258)만 지금 세션을 적는다. 큐에 들어간 줄을 돌려준다.
 */
internal fun Tx.replaceLine(old: Line, new: Line): Line {
    val n = new.copy(sessionId = old.sessionId ?: ses?.id)
    speech { copy(queue = queue.map { if (it === old) n else it }) }
    return n
}

/** C141: 줄을 큐에 넣을 때의 세션 id를 찍는다(이미 찍힌 줄은 그대로). */
internal fun Tx.sessionStamped(l: Line): Line =
    if (l.sessionId != null || ses == null) l else l.copy(sessionId = ses?.id)   // 바뀐 것이 없으면 같은 객체(큐 줄은 ===로 찾는다)

/** 그 줄(같은 객체)을 뺀다. */
internal fun Tx.removeLine(old: Line) = speech { copy(queue = queue.filter { it !== old }) }

/** 수명 표 Speaking 행의 CUT: 실행기를 끊었으니 말하던 맨 앞 줄을 뺀다(말하기 간격 S1은 그대로). */
internal fun Tx.cutSpeaking() = speech { copy(speaking = null, queue = queue.drop(1)) }

// ---- 말하기(8장, 4.1 말하기 규칙) ----

internal fun Tx.say(sid: Sid, vars: Map<String, String> = emptyMap()) = say(Line(sid, vars, null))

/** 큐에 넣는다. 통화 중·Suspend 중이면 버린다(15d·14v). ④ 답은 맨 앞(말하는 문장 바로 뒤)에 넣는다. */
internal fun Tx.say(l: Line): Boolean {
    if (s.car.inCall || suspended) return false
    val line = sessionStamped(l).let { it.copy(queuedAt = it.queuedAt ?: now) }
    speech {
        val q = if (line.qaAnswer || line.sid == Sid.QA)
            queue.toMutableList().apply { add(if (speaking != null && isNotEmpty()) 1 else 0, line) }
        else queue + line
        copy(queue = q)
    }
    return true
}

/**
 * 큐 맨 앞 문장을 낸다. 말하는 동안 그 문장은 큐 맨 앞에 남겨 두고 SpeechDone에서 뺀다
 * (그래야 끝난 문장이 Speak·Ask·④ 답 중 무엇인지 안다, Q1·C66).
 */
internal fun Tx.drain() {
    if (s.car.inCall || suspended || s.speech.speaking != null || s.speech.qa != null) return
    val waiting = routePending()
    // C285: 경로가 정해졌으면 큐의 인계·쉼터 제안 줄을 먼저 다시 고른다(그 줄마다 한 drain에 한 번, 이번에 넣은 줄은 다시 보지 않는다, C251).
    // 아래 미룸(S5)·만료(S6)·앞 버림은 다시 고른 문장으로 본다(임시 S10이 S08로 바뀌면 바쁨에도 미루지 않는다)
    if (!waiting && !gearP) s.speech.queue.filter(::repick).forEach { l -> if (s.speech.queue.any { it === l }) repickLine(l) }
    val sq = s.speech
    // S6: 바쁨 때문에 너무 오래 미뤄진 급하지 않은 문장은 버린다(longDriveOffered는 말한 뒤에만 서므로 그대로다, C84).
    // 큐에 든 뒤 바쁨이 한 번도 없었으면(driveBusyAt < queuedAt) 앞 문장 뒤에서 기다린 것이지 미뤄진 것이 아니므로 버리지 않는다
    val busyAt = s.steer.driveBusyAt
    // C285: 경로를 기다리며 보류 중인 줄은 바쁨으로 미룬 것이 아니라 만료시키지 않는다(기다림이 끝나면 restartHeld가 기준을 다시 잡는다)
    // 결정 ②: 여기서 버리는 줄(S6 만료·앞 버림·꺼낼 때 무효)은 말하지 않았으니 10분 금지를 남기지 않는다(F5j·V5)
    val kept = sq.queue.filterNot { it.sid in HOLDABLE && it.queuedAt != null && now - it.queuedAt > p.holdMaxMs && busyAt != null && busyAt >= it.queuedAt && !heldForRoute(it, waiting) }
    var q = kept.dropWhile { if (repick(it)) gearP else !valid(it) }   // C258·C272: 인계·쉼터 제안 줄의 경로 판단은 위의 다시 고르기가 했다(앞 버림은 기어 P만)
    // S5: 바쁨 중이면 급하지 않은 문장을 건너뛰고 처음 나오는 급한 문장을 낸다. 건너뛴 문장은 자리를 지킨다.
    // 고른 문장도 꺼낼 때 다시 본다: 무효(C15·G15)면 버리고 다음 후보를 찾는다. 출발 제안은 거리·이름을 지금 값으로 갱신한다
    var i: Int
    while (true) {
        i = q.indexOfFirst { !(it.sid in HOLDABLE && driveBusy()) && !(it.sid == Sid.S07_DEPART && s.car.gear == null) && !heldForRoute(it, waiting) }   // C150: 경로 기다림 동안 쉼터에 묶인 줄은 버리지 않고 건너뛴다
        if (i < 0) break
        val fresh = if (valid(q[i])) refreshed(q[i]) else null
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
    if (guide(line)) openLedger()?.let { a ->   // H10·C146·C258: 인계 줄을 낼 때 그 줄에서 알림 종류·쉼터 이름·분과 에피소드 종류를 정하고 발화 id를 적는다(알림·확정은 SpeechDone에서)
        ledgerKind(a, kindOf(line.sid)) { copy(utteranceId = id, restName = line.vars["restName"], minutes = line.vars["minutes"]?.toIntOrNull() ?: minutes) }
    }
    val logAt = if (line.sid in OFFER_SIDS) logRest(line.sid, restId) else null   // Record: 휴식 기록 줄(그 위치를 질문 문맥에 잡는다, V18)
    if (restId != null) {
        // 제안한 쉼터의 속성(2.2 PendingAsk). 문장 id가 아니라 고른 RestCandidate로 본다(S11 수유실 휴게소도 F7로 이어짐)
        val nursing = restOf(restId)?.hasNursingRoom ?: (line.sid == Sid.S07 || line.sid == Sid.S07_DEPART)
        asked(PendingAsk(id, line.sid, restId, if (guide(line)) openLedger()?.episodeId else null, nursing, logAt))   // Offer: 답이 쓸 문맥(P5). 에피소드에 속하는 질문은 그 세션의 인계 줄뿐이다(C334)
        out += Ask(id, line.sid, line.vars, p.yesNoListenMs)
    } else out += Speak(id, line.sid, line.vars)
    speech { copy(speaking = id) }
}

internal fun Tx.speechDone(e: SpeechDone, listenAfter: Boolean = true) {
    // P5: 결과가 가리키는 대기는 그 발화(speaking = ref)와 맨 앞 줄, 그 발화 id로 묶은 장부·질문이다. 옛 SpeechDone은 무시(2.4 복원)
    if (s.speech.speaking != e.ref) return
    val line = s.speech.queue.firstOrNull()
    endSpeaking()
    // C146: 인계 알림·확정은 묶은 줄을 실제로 말한 뒤. 지금 세션 장부는 말하지 못했으면 두어 Step 끝 flush가 이름 없는 알림·false로 정리한다.
    // C152: 닫힌 세션 장부는 말했든 못 했든 여기서 확정하고 미뤘던 세션 리포트를 낸다. 복원 dropSpeaking은 끝까지 말했는지 몰라 건너뛴다(C123)
    s.handoffs.firstOrNull { it.utteranceId == e.utteranceId }?.let { a -> if (e.spoken || a.sessionId != ses?.id) settleLedger(a, e.spoken) }
    // C171·결정 ②: 말하지 못한 제안은 10분 금지를 남기지 않는다(spoke를 타지 않는다)
    // C276: 발화되지 않은 질문에는 답이 없다. 뒤따르는 실행기의 Answer(NONE)·장면 답은 H4b로 버려진다(답 뒤 정리·기록·경유지 없음)
    if (!e.spoken && ses?.pendingAsk?.askId == e.utteranceId) dropAsk()
    if (line == null || !e.spoken) return
    spoke(line)
    if (ses != null && line.askRestId == null && line.sid != Sid.QA && !line.qaAnswer && listenAfter && s.listenOk()) listen() // Q1
}

/** C303 A6: 말하던 맨 앞 줄을 끝낸 것으로 뺀다(speaking 비움, 말하기 간격 S1은 지금부터). SpeechDone·복원 ③(수명 표 RESTORE의 DROP)·복원 ② Room 확정(C151·C161)이 같이 쓴다. */
internal fun Tx.endSpeaking() = speech { copy(speaking = null, lastDoneAt = now, queue = queue.drop(1)) }

/** 줄을 실제로 말한 뒤의 상태 변화. speechDone과 복원(C136 markSpoken)이 같이 쓴다. */
internal fun Tx.spoke(line: Line) {
    if (line.sessionId != null && line.sessionId != ses?.id) return   // C141: 다른 세션이 넣은 줄은 지금 세션 상태를 바꾸지 않는다
    offerSpoken(line)                                                 // Offer: L1 longDriveOffered(C84), 결정 ②(S3) 말한 시각
    if (line.sid == Sid.S06) markS06Said()                            // Soothe: C66
}
