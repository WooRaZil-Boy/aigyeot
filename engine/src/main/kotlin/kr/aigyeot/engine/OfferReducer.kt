package kr.aigyeot.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 인계·쉼터 제안·경로 기다림·답·경유지·인계 장부(설계 3.4 Offer). 폰 알림 문장 표(handoffBody)도 인계 책임이라 여기 둔다(설계 3.1).
// 주인 칸(설계 3.3, D7): session.offers(spokenAt·pendingAsk·waypointPending·addedRestIds·driveFrom·longDriveOffered·leftParkAt), handoffs.
// 쓰기는 이 파일의 private offers {}와 장부 함수(ledger·dropLedger·settleLedger)뿐이다. 큐 줄은 Speech, 에피소드는 Soothe(mark*), 휴식 기록은 Record,
// 수유실 들름은 Feeding, ④ 규칙 표시는 Qa의 함수를 부른다. 답·경유지 결과는 waitingFor로 낼 때 잡은 PendingAsk·WaypointPending만 쓴다(P5).

/** C258: 지금 낼 인계 안내. line이 null이면 줄 없음(P·막힘). slot은 line이 들어갈 큐의 기존 줄(없으면 새 줄로 넣는다). */
private class Guidance(val kind: HandoffKind, val line: Line?, val slot: Line? = null)

private fun Tx.offers(f: OfferState.() -> OfferState) = ses { copy(offers = offers.f()) }

/** 지금 세션의 인계 장부(세션마다 하나). 나머지는 닫힌 세션의 것이다. */
internal fun Tx.openLedger(): HandoffLedger? = ses?.let { x -> s.handoffs.firstOrNull { it.sessionId == x.id } }
internal fun Tx.ledger(a: HandoffLedger, f: HandoffLedger.() -> HandoffLedger) { s = s.copy(handoffs = s.handoffs.map { if (it === a) it.f() else it }) }
/** 장부를 알림·확정 없이 뺀다(수명 표 DROP·장면 끝 SETTLE은 Room이 확정, 복원 ② Room 확정이 앞섬). */
internal fun Tx.dropLedger(a: HandoffLedger) { s = s.copy(handoffs = s.handoffs - a) }

// ---- 제안 상태(offers)의 작은 변화: 다른 리듀서는 이 함수를 부른다 ----

/** P에서 나왔다(Life.gear): 이번 주행의 시작(A1), 장시간 주행 시작(L1)이 없으면 지금. */
internal fun Tx.leftPark() = offers { copy(leftParkAt = now, driveFrom = driveFrom ?: now) }

/** L2: P에 오래 서 있으면 장시간 주행을 처음부터 다시 센다(Router.timers). */
internal fun Tx.parkReset() {
    val x = ses ?: return
    if (gearP && x.driveFrom != null && now - (s.car.gearSince ?: now) >= p.longDriveResetParkMs) offers { copy(driveFrom = null, longDriveOffered = false) }
}

/** 쉼터 질문을 냈다(Speech.drain). [pa]는 그때 잡은 문맥(P5, 휴식 기록 줄 위치 포함). */
internal fun Tx.asked(pa: PendingAsk) = offers { copy(pendingAsk = pa) }

/** 답 기다림을 버린다(답이 왔음·말하지 못한 질문 C276·수명 표 AskAnswer의 CUT·DROP). */
internal fun Tx.dropAsk() = offers { copy(pendingAsk = null) }

/** 수명 표 Waypoint 행의 DROP(13a·Boot C84). */
internal fun Tx.dropWaypoint() = offers { copy(waypointPending = null) }

/** 제안 줄을 실제로 말했다(Speech.spoke, 이 세션 줄만). L1: 장시간 제안은 말했을 때만 선다(C84). 결정 ②(S3): 10분 금지 시각은 여기서만 쓴다. */
internal fun Tx.offerSpoken(line: Line) {
    if (line.longDrive) offers { copy(longDriveOffered = true) }
    if (line.sid in OFFER_SIDS) offers { copy(spokenAt = spokenAt + (line.sid to now)) }
}

// ---- 인계·쉼터(5.7) ----

internal fun Tx.handoff(cause: HandoffCause) {
    offered = true                                   // P1: 인계가 장시간 제안보다 앞선다
    val minutes = remainingMinutes(s.car.route)
    flushOpenLedger(force = true)                   // 앞 인계의 대기 줄이 남았으면 먼저 정리한다(덮어써 잃지 않게, H11)
    val olds = s.speech.queue.filter(::guide)        // 앞 인계의 표시 줄: 이 안내가 대신한다(C267: 그 출처 속성을 새 줄로 옮긴다)
    val g = handoffGuidance()
    val line = place(g, olds, mark = true)           // H10: 새 줄, 또는 큐에 이미 있던 겹치는 줄(C130·C133·C147·C273)
    // 앞 인계의 표시 줄은 이 안내가 대신한다: 말하는 줄은 표시만 떼고 나머지는 버린다(두 줄이 같은 안내로 겹쳐 나가지 않게)
    dropQueued { guide(it) && it !== line }
    mapLines { l -> if (guide(l) && l !== line) l.copy(handoff = false) else l }
    val notify = s.otherGuardians > 0                                                           // H8
    // C131·C146: 돌려받은 줄이 이미 말하는 중(큐 맨 앞)이면 그 발화 id로 묶어 그 SpeechDone을 기다린다
    val speakingNow = line != null && s.speech.speaking != null && s.speech.queue.firstOrNull() === line
    ses?.let { enter(Stage.Handoff(it.attemptNo)) }
    markHandoff(cause, g.kind)
    val alertNow = notify && (line == null || g.kind.alertsAtOnce())            // H1·H2는 알림을 바로 보낸다. 줄이 없으면 이름 없는 알림(H3·H7)
    if (alertNow) out += SendToPhone("handoff_alert", handoffBody(g.kind, null, minutes, plain = true, cause = cause))
    val x = ses!!
    if (line != null) s = s.copy(handoffs = s.handoffs + HandoffLedger(x.id, x.episode?.id, now, cause, g.kind, minutes, alert = notify && !alertNow,
        utteranceId = if (speakingNow) s.speech.speaking else null, restName = if (speakingNow) line.vars["restName"] else null))
    saveEpisode()
    if (line == null) x.episode?.let { rec(settleHandoff(it.id, g.kind, told = false, restName = null)) }   // C303 A10: 줄이 없으면 바로 확정(H1은 null)
}

/**
 * C258: 지금 기어·경로·10분 금지·넣은 경유지로 지금 낼 인계 안내를 고른다. 부작용이 없다(넣기는 `place`).
 * P → 줄 없음(H1). 도착 10분 이내 → S15(H2). 쉼터 있음 → S07·S08(H3, `offerRest` 규칙), 막히면 줄 없음. 그 밖 → S10(H7·H9).
 * [old]: 꺼낼 때 다시 보는 인계 줄. 같은 안내(같은 문장·쉼터)면 그 줄 자리에 지금 값(분·거리·이름)을 넣어 돌려준다. 다른 안내를 고를 때는
 * 그 줄을 없는 것으로 본다(말하지 않았다). 꺼낼 때 겹치는 줄(overlap)이 큐에 있으면 그 자리에 지금 안내를 넣는다(다시 봐도 같은 결과).
 * C272: [handoff]가 거짓이면 인계가 아닌 쉼터 제안 줄(S07·S08·S10·S11)을 꺼낼 때 다시 고른다. 같은 규칙에서 S15·P 안내만 없고(offerRest가 null → 버림),
 * 장시간 줄이면 S11·S10으로 고른다.
 * C279: 새 쉼터 제안(`offer`, [old] 없음)도 이 함수로 고른다. [longDrive]는 그 제안이 2시간 제안인가(겹치는 줄은 그 출처를 넘겨받는다).
 */
private fun Tx.handoffGuidance(old: Line? = null, handoff: Boolean = true, longDrive: Boolean = false): Guidance {
    if (gearP) return Guidance(HandoffKind.PARKED, null)
    val x = ses!!
    val route = s.car.route
    val ld = !handoff && (old?.longDrive ?: longDrive)
    val pick = if (handoff && nearDestination(route, p)) Line(Sid.S15, mapOf("minutes" to "${remainingMinutes(route)}"), null)
        else offerRest(ld, s.car.gear, route, now, x.driveFrom, p) ?: return Guidance(HandoffKind.NO_REST, null)
    val kind = kindOf(pick.sid)
    // 그 줄 자리에 지금 안내(문장·쉼터·거리·분). H4c: 진행 중이거나 이미 넣은 쉼터는 묻지 않는다(막힘)
    fun fresh(l: Line) = l.copy(sid = pick.sid, askRestId = pick.askRestId, vars = pick.vars).takeIf { it.askRestId == null || canAddWaypoint(it.askRestId) }
    val sameOld = old != null && old.sid == pick.sid && old.askRestId == pick.askRestId
    val o = if (sameOld) null else overlap(pick, old)
    // C308·결정 ②: S3·S3a 10분 금지는 세 갈래(같은 안내·겹친 줄·새 줄) 모두 고른 줄에 여기서 한 번 본다. 다시 쓰는 줄(old·겹친 o)은
    // 큐에 있어도 막지 않는다(그 줄 자리를 쓴다). C269: 경로를 기다리면(같은 안내 제외) 자리만 잡고 금지는 꺼낼 때 본다
    val allowed = pick.sid == Sid.S15 || (routePending() && !sameOld) ||
        !offerBanned(x.spokenAt, s.speech.queue, x.id, pick.sid, now, p, except = listOf(old, o))
    if (sameOld) return Guidance(kind, fresh(old)?.takeIf { allowed }, old)
    // H10·C273: 겹치는 줄이 큐에 있으면 그 자리를 쓴다(C133·C147). 인계·새 제안 때 같은 쉼터 줄은 그대로 넘겨받고(말하는 줄이면 C131),
    // C280: 같은 문장이라 겹친 다른 쉼터 줄은 지금 쉼터 값으로 바꾼다(꺼낼 때와 같다. 말하는 줄은 overlap이 같은 쉼터일 때만 돌려준다)
    if (o != null) return Guidance(kind, (if (old == null && o.askRestId == pick.askRestId) o else fresh(o))?.takeIf { allowed }?.let { it.copy(longDrive = it.longDrive || ld) }, o)
    if (pick.sid == Sid.S15) return Guidance(kind, pick)
    return Guidance(kind, pick.takeIf { allowed && (it.askRestId == null || canAddWaypoint(it.askRestId)) })   // H4c
}

/**
 * H10·C273: 지금 세션 큐에서 이 제안과 겹치는 줄. 같은 쉼터를 묻는 줄이 먼저(큐에는 한 쉼터를 묻는 줄을 하나만 둔다), 없으면 같은 문장 줄.
 * C280: 말하는 줄(큐 맨 앞)은 바꿀 수 없으니 고른 쉼터가 같을 때만 돌려준다(쉼터 없는 S15·S10은 같은 문장이면).
 */
internal fun Tx.overlap(pick: Line, except: Line? = null): Line? {
    val sq = s.speech
    val mine = sq.queue.filterIndexed { i, it -> it !== except && (it.sessionId == null || it.sessionId == ses?.id) && !(i == 0 && sq.speaking != null && it.askRestId != pick.askRestId) }
    return mine.firstOrNull { pick.askRestId != null && it.askRestId == pick.askRestId } ?: mine.firstOrNull { it.sid == pick.sid }
}

/**
 * C258: 고른 인계 줄을 큐에 둔다. 큐에 있던 줄 자리면 그 자리에 표시해 두고, 새 줄이면 넣는다. 넣지 못하면(통화·Suspend) null.
 * 결정 ②: 10분 금지는 남기지 않는다(큐에 있는 동안은 offerBanned가 막고, 말한 뒤에는 spoke가 남긴다).
 * C267: [from]은 이 안내가 대신하는 줄. 출처 속성 longDrive를 옮긴다(나머지 칸은 새 안내의 값: S6 만료도 새로 센다).
 * C272·C273: [mark]면 인계 줄로 표시한다(인계 시각). 대신하는 줄·넘겨받는 줄의 인계 표시도 합친다. 넘겨받는 줄의 문장이 바뀌면 새 줄로 넣는다(그 줄은 말하지 않고 버림).
 */
private fun Tx.place(g: Guidance, from: List<Line>, mark: Boolean = false): Line? {
    val l = g.line ?: return null
    val m = l.copy(handoff = mark || l.handoff || from.any { it.handoff }, longDrive = l.longDrive || from.any { it.longDrive })
    val slot = g.slot
    val inQueue = slot != null && s.speech.queue.any { it === slot }
    if (inQueue && m.sid == slot!!.sid) return replaceLine(slot, m)   // 같은 질문 갱신: 세션 문맥을 지킨다
    if (inQueue) removeLine(slot!!)
    if (!say(m.copy(queuedAt = if (inQueue) null else m.queuedAt))) return null
    offered = true
    return s.speech.queue.last()
}

/** H10: 지금 세션 장부의 줄(인계 표시 줄)이 큐에 없으면(말하지 않고 버려짐·막힘) 그때의 종류로 확정한다. [force]면 줄이 있어도(새 인계·세션 닫기). */
internal fun Tx.flushOpenLedger(force: Boolean = false) {
    val a = openLedger() ?: return
    if (!force && s.speech.queue.any(::guide)) return
    settleLedger(a, false)
}

/** H10·C152(V6): Step 끝에 장부 하나를 본다. 묶인 줄(그 세션의 표시 줄)이 큐에 없으면 이름 없는 알림·false로 확정한다. 지금 세션 것이 먼저, 닫힌 세션 것은 만든 순서. */
internal fun Tx.flushLedgers() {
    flushOpenLedger()
    s.handoffs.filter { a -> a.sessionId != ses?.id && s.speech.queue.none { it.isHandoffOf(a.sessionId) } }.forEach { settleLedger(it, false) }
}

/**
 * C303 C7·V6: 장부를 끝낸다(H10·H11·C146). 장부에서 빼고, 말했으면 쉼터 이름을 담은 알림, 못 했으면 이름 없는 알림(alert일 때)을 보내고
 * 확정 할 일 하나(SettleHandoff, [HandoffLedger.settle])를 낸다. 닫힌 세션 것이면 미뤘던 세션 리포트(C152). SpeechDone·flush 두 길이 같이 쓴다.
 * guardian_notified는 link가 보낼 함에 넣을 때 쓴다(결정 ③). 여기서는 보낼지만 정한다.
 */
internal fun Tx.settleLedger(a: HandoffLedger, spoken: Boolean) {
    dropLedger(a)
    if (a.alert && s.otherGuardians > 0)   // H8·C193: 낼 때 받는 보호자를 다시 본다
        out += SendToPhone("handoff_alert", handoffBody(a.kind, if (spoken) a.restName else null, a.minutes, a, plain = !spoken))
    a.settle(spoken)?.let(::rec)   // 열린 에피소드의 종류는 ledgerKind가 장부와 함께 써 왔다(C258: 뒤 SaveEpisode가 확정 종류를 되돌리지 않는다)
    reportIfSettled(a.sessionId)   // C335: 뒷창 되돌림 대기가 남았으면 그것이 끝날 때
}

internal fun Tx.handoffBody(kind: HandoffKind, restName: String?, minutes: Int?, a: HandoffLedger? = null, plain: Boolean = false, cause: HandoffCause? = null): JsonObject {
    val cap = (cause ?: a?.cause) == HandoffCause.SESSION_CAP   // C214: 상한 인계는 울음 지속을 단정하지 않는다
    val (wire, text) = when (kind) {   // contracts-link 3.3 문장 표
        HandoffKind.WAYPOINT_OFFER -> "rest_offer" to if (cap) (if (restName != null) "달래기 시간이 끝나 ${restName}에 들르자고 운전자에게 제안했어요." else "달래기 시간이 끝났어요. 아기를 살펴봐 주세요.") else if (restName != null) "아기가 계속 울어서 ${restName}에 들르자고 운전자에게 제안했어요." else "아기가 계속 울어요."
        HandoffKind.ARRIVAL_NOTICE -> "arrival_notice" to if (cap) "달래기 시간이 끝났어요. ${minutes}분 뒤 도착해요." else "아기가 계속 울어요. ${minutes}분 뒤 도착해요."
        HandoffKind.NO_REST -> "no_rest" to if (cap) (if (plain) "달래기 시간이 끝났어요. 아기를 살펴봐 주세요." else "달래기 시간이 끝나 안전한 곳에 세우도록 안내했어요.") else if (plain) "아기가 계속 울어요." else "아기가 계속 울어요. 경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요."
        HandoffKind.PARKED -> "parked" to if (cause == HandoffCause.SESSION_CAP) "차가 선 채로 달래기 시간이 끝났어요. 아기를 살펴봐 주세요." else "차가 선 채로 아기가 계속 울어요."   // U56
    }
    return buildJsonObject {
        put("text", text); put("kind", wire)
        put("rest_name", if (kind == HandoffKind.WAYPOINT_OFFER) restName else null)
        put("minutes_to_arrival", if (kind == HandoffKind.ARRIVAL_NOTICE) minutes else null)
        put("occurred_at", a?.occurredAt ?: now); put("session_id", a?.sessionId ?: ses!!.id); put("episode_id", if (a != null) a.episodeId else ses!!.episode?.id)
    }
}

/**
 * 쉼터 제안(수유 시간대·2시간)을 큐에 둔다. C279: 인계와 같은 고르기(handoffGuidance)·두기(place)를 쓴다.
 * 겹치는 줄(같은 쉼터·같은 문장, C273)이 큐에 있거나 말하는 중이면 10분 금지·경유지 검사 전에 그 줄을 넘겨받고(장시간이면 출처도, C267),
 * 경로가 정해지지 않은 동안(C269)은 10분 금지를 보지 않고 자리만 잡는다(꺼낼 때 drain이 다시 고르며 그 문장의 금지를 본다).
 */
internal fun Tx.offer(longDrive: Boolean): Line? = if (ses == null) null else place(handoffGuidance(handoff = false, longDrive = longDrive), emptyList())

/**
 * C285: 경로 기다림이 끝났다. 그동안 넣어 자리만 잡은 줄(경로에 기대는 줄)은 미룸 만료(S6) 기준을 지금으로 한다(목록을 기다린 시간은 바쁨으로 미룬 시간이 아니다).
 * 기다림 전에 넣은 줄은 원래 시각 그대로.
 */
internal fun Tx.restartHeld(from: Long) = mapLines { l -> if (l.queuedAt == null || l.queuedAt < from || !heldForRoute(l, true)) l else l.copy(queuedAt = now) }

internal fun Tx.longDrive() {                                                                              // L1·P1
    val x = ses ?: return
    val from = x.driveFrom ?: return
    if (routePending()) return   // C128 ⑤·C145·C269: 경로(목적지·쉼터 목록)가 정해질 때까지 기다린다
    if (!s.car.micRunning) return   // C350: 마이크가 꺼져 있으면 쉼터 질문을 들을 수 없다(기회를 쓰지 않고 Tick마다 다시 본다)
    if (offered || x.longDriveOffered || gearP || suspended || s.car.inCall || now - from < p.longDriveMs) return
    if (x.play?.let { it.startedAt == null } == true) return   // 시작 결과(수유 분기·시도 알림)가 먼저다(3-P)
    if (s.speech.queue.any { it.longDrive }) return          // 이미 큐에 있거나 말하는 중(큐 맨 앞에 남아 있다)
    offer(true)                                              // longDriveOffered는 speechDone에서 말한 뒤에만 선다(L1, C84)
}

internal fun Tx.answer(e: Answer) {
    val pa = s.waitingFor(e) ?: return                                                          // H4b
    val i = pa.restLogIndex                                                                     // V18(P5): 질문을 낼 때 적은 기록 줄(지금 기록을 다시 찾지 않는다)
    dropAsk()                                                                                   // H4a·H6
    // C273: 그 쉼터를 다시 묻지 않는다. 큐에 남은 같은 쉼터 줄은 말하지 않고 버린다(말하는 맨 앞 줄은 둔다. 묶인 인계 알림은 Step 끝 flush가 정리).
    // 버린 줄이 장시간 줄이면 같은 쉼터를 물었으니 longDriveOffered만 세운다(C267. 아니면 L1이 같은 쉼터로 S11을 다시 묻는다).
    // C276: 여기 닿는 질문은 실제로 발화됐다(못 했으면 speechDone이 pendingAsk를 비웠다). 버린 줄 자체는 말하지 않았으니 그 문장의 10분 금지는 없다(결정 ②)
    val sq = s.speech
    val same = sq.queue.filterIndexed { j, l -> l.askRestId == pa.restId && !(j == 0 && sq.speaking != null) }
    if (same.any { it.longDrive }) offers { copy(longDriveOffered = true) }
    dropQueued { it.askRestId == pa.restId }
    setLog(i) { copy(answer = e.answer) }
    if (e.answer == YesNo.YES && gearAllows(pa.sid)) {                                          // H4: 꺼낼 때 valid와 같은 기어 규칙
        if (restOf(pa.restId) == null) return ruleSay(Sid.S19)   // C213: 지금 경로에 없는 쉼터(옛 경로에서 물은 질문)는 넣지 않는다
        if (canAddWaypoint(pa.restId)) sendWaypoint(pa.restId, pa.hasNursingRoom, false, i, pa.episodeId)   // C313: 질문 당시 에피소드(출발 제안은 null)로 기록한다. // C213: 경로 기다림(C269) 중이면 목록이 비어 위에서 S19(옛 경로 질문일 수 있어 넣지 않는 쪽)
        else if (pa.restId in ses!!.addedRestIds) setLog(i) { copy(waypointOk = true) }         // H4c: 이미 넣은 쉼터(지금 addable=false여도 이쪽이 먼저)
        else if (restOf(pa.restId)?.addable == false) return ruleSay(Sid.S19)                   // H4d(C353): 경로가 바뀌어 이제 넣을 수 없는 쉼터
    }
}

/** C313: episodeId는 부르는 쪽이 정한다(답은 질문 당시 PendingAsk.episodeId, ④ 도구는 지금 열린 에피소드). 여기서 지금 상태를 읽지 않는다. */
internal fun Tx.sendWaypoint(restId: String, nursing: Boolean, fromQa: Boolean, logIndex: Int?, episodeId: String?) {
    val c = newId()
    out += AddWaypoint(c, restId)
    val x = ses ?: return
    val log = if (fromQa) logRest(Sid.QA, restId) else logIndex          // ④ 요청은 자기 기록 줄을 새로 만든다(Record)
    // 결과가 쓸 문맥(P5)은 여기서 다 잡는다: 기록 줄·에피소드·쉼터 이름(C269: 결과가 올 때 목록이 비어 있을 수 있다)
    offers { copy(waypointPending = WaypointPending(c, restId, x.id, episodeId, fromQa, log, restOf(restId)?.name)) }
    if (nursing) nursingRequested(restId)                                 // Feeding(결정 ①)
}

internal fun Tx.waypoint(e: WaypointResult) {                                                              // H5·H5a
    val x = ses ?: return
    val w = s.waitingFor(e) ?: return                                                         // C65
    val mine = s.ign.nursingStop?.restId == w.restId
    setLog(w.restLogIndex) { copy(waypointOk = e.ok) }                                       // 그 요청의 줄에만
    if (e.ok) {
        offers { copy(waypointPending = null, addedRestIds = addedRestIds + w.restId) }
        if (mine) nursingAdded()
        if (w.episodeId != null && x.episode?.id == w.episodeId) markWaypointAdded()   // C313: 요청 당시 에피소드가 없으면(출발 제안) 에피소드에 붙이지 않는다
        say(Line(Sid.S09, mapOf("restName" to (w.restName ?: w.restId)), null, qaAnswer = w.fromQa))   // 요청 때 잡은 이름(P5)
    } else {
        dropWaypoint()
        if (mine) dropNursing()
        if (w.fromQa) say(Line(Sid.S19, emptyMap(), null, qaAnswer = true))
    }
}

internal fun Tx.addWaypoint(restId: String) {                                                              // Q5b·Q5c
    val x = ses
    val rest = restOf(restId)
    markRuleSaid()                                                                              // Q5a(Qa): ④ 도구면 LLM 글을 말하지 않는다
    if (x == null || gearP || nearDestination(s.car.route, p) || rest == null || !canAddWaypoint(restId))
        return ruleSay(Sid.S19)
    sendWaypoint(restId, rest.hasNursingRoom, qaTool, null, x.episode?.id)
}

/** C258: 꺼낸 인계 줄을 다른 안내로 바꾸거나(rebind할 줄) 막혀 버렸다. 대기 알림의 종류·분을 그 줄에 맞춘다(줄이 없으면 종류만: Step 끝 flush가 그 종류로 이름 없는 알림). 이미 보낸 알림(H2)은 alert 그대로라 다시 보내지 않는다. */
internal fun Tx.rebind(l: Line?, kind: HandoffKind) {
    val a = openLedger() ?: return
    ledgerKind(a, kind) { if (l == null) this else copy(minutes = l.vars["minutes"]?.toIntOrNull() ?: minutes, utteranceId = null, restName = null) }
}

/**
 * C319 결정 7(A9): 장부의 인계 종류를 바꾸는 유일한 함수(다시 고를 때 rebind·꺼낼 때 drain). 열린 에피소드의 장부면 에피소드 종류와 행(SaveEpisode)도
 * 같은 때 쓴다. 그래서 열린 에피소드의 종류는 늘 장부와 같고, 확정(settleLedger)이 따로 맞출 일이 없다.
 */
internal fun Tx.ledgerKind(a: HandoffLedger, kind: HandoffKind, f: HandoffLedger.() -> HandoffLedger = { this }) {
    ledger(a) { f().copy(kind = kind) }
    if (a.episodeId != null && a.episodeId == ses?.episode?.id && ses?.episode?.handoff?.kind != kind) { markHandoffKind(kind); saveEpisode() }
}

/** C258·C272: 인계·쉼터 제안 줄을 지금 경로로 다시 고른다. 같은 안내면 값만 갱신하고, 다르면 바꾸고(넣기는 place 규칙), 막히면 버린다. */
internal fun Tx.repickLine(l: Line) {
    val g = handoffGuidance(l, handoff = guide(l))
    if (g.slot === l && g.line != null) { replaceLine(l, g.line); return }
    removeLine(l)
    val placed = place(g, listOf(l))
    if (guide(l) || placed?.let(::guide) == true) rebind(placed, g.kind)   // 인계가 아닌 줄은 알림 장부가 없다(C272). 인계 줄 자리를 넘겨받았으면 그 장부를 맞춘다
}
