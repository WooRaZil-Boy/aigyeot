package kr.aigyeot.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 수유 시계·수유실 들름·출발 제안(설계 3.4 Feeding): FeedingStarted·FeedingConfirmed(F1·F2·F6·F10), F5 출발 제안, 수유실 들름 F7~F9, S06 문장, feeding_state 몸통.
// 주인 칸(설계 3.3, D7): feedingStart, ign.nursingStop, session.departChecked. 수유실 들름은 이 파일의 private nursing() 하나로 쓴다.
// 기어(Life)·시간(Router)·경유지 결과(Offer)·수명 표는 아래 nursing* 함수를 부른다.

private fun Tx.nursing(ns: NursingStop?) {
    val shown = s.ign.nursingStop?.confirmShown == true
    s = s.copy(ign = s.ign.copy(nursingStop = ns))
    if (shown && ns?.confirmShown != true) out += HideNotice(Notice.FEEDING_CONFIRM)  // C347: 띄운 확인이 사라지면 알림도 내린다(출발·H5a·Boot·DROP·답)
}

/** C323: S06의 값. 수유 창이 아니면 null(말하지 않음). 넣을 때(saySinceFeeding)와 꺼낼 때(Rules.refreshed)가 같이 쓴다. */
internal fun Tx.s06Vars(): Map<String, String>? = if (feeding()) mapOf("elapsed" to hm(now - (s.feedingStart ?: now))) else null

internal fun Tx.saySinceFeeding() { s06Vars()?.let { say(Sid.S06, it) } }

/** F5·F5a~F5e·F5j: 세션이 열렸고 기어가 D인 첫 경로에서 한 번만 본다. 세션을 연 GearChanged(D)와 같은 Step에서도 본다. */
internal fun Tx.departOffer() {
    val x = ses ?: return
    if (s.car.inCall || suspended) return   // C153: 통화·Suspend 중엔 say()가 거절하니 기회(departChecked)를 쓰지 않는다
    if (!s.car.micRunning) return           // C350: 마이크가 꺼져 있으면 쉼터 질문을 들을 수 없으니 기회를 쓰지 않는다(MicService(true)가 다시 본다)
    val r = s.car.route
    // F5c: 경로가 오면 다시 본다. rests가 비면 DrivingInfo만 먼저 온 것일 수 있다(TBT가 늦다). F5i: 같은 Step에 다른 제안이 나갔으면(P1) 다음 RouteState로 미룬다
    if (s.car.gear != Gear.D || offered || routePending() || x.pendingAsk != null || s.speech.queue.any { it.sid in OTHER_OFFERS } || OTHER_OFFERS.any { offerBanned(x.spokenAt, s.speech.queue, x.id, it, now, p) } || x.departChecked || r?.hasDestination != true || r.remainingKm == null || r.remainingSec == null || r.rests.isEmpty()) return
    ses { copy(departChecked = true) }
    val fs = s.feedingStart ?: return
    val rest = pickDepartRest(r, fs, s.profile?.feedingIntervalMin, now, p.departLeadMin) ?: return
    if (!canAddWaypoint(rest.id) || s.speech.queue.any { it.sid == Sid.S07_DEPART }) return
    if (say(Line(Sid.S07_DEPART, departVars(rest, hungryAt(fs, s.profile!!.feedingIntervalMin!!)), rest.id))) offered = true
}

// ---- 수유 시계(10장) ----

// F1·F2. 저장·feeding_state는 수유 입력 함수(link LinkRoom.feedingStarted, 차 화면·폰 같음)가 Room 트랜잭션에서 이미 했다(V3, C322). 상태만 바꾼다
internal fun Tx.feedingStarted(e: FeedingStarted) {
    if (laterFeeding(s.feedingStart, e.startedAt)) s = s.copy(feedingStart = e.startedAt)
}

internal fun Tx.feedingConfirmed(yes: Boolean) {                                                           // F6·F10
    val ns = s.ign.nursingStop ?: return
    if (!yes) { if (ns.confirmShown) nursing(null); return }   // C347: 옛 알림의 '아니요'가 확인 전 새 정차를 지우지 않게
    val at = ns.firstPAt
    if (!ns.confirmShown || at == null || !gearP) return
    nursing(null)
    if (!laterFeeding(s.feedingStart, at)) return
    s = s.copy(feedingStart = at)
    out += SaveFeedingStart(at); out += SendToPhone("feeding_state", feedingBody())
}

// ---- 수유실 들름(H4·H5·F7~F9, 결정 ①: 시동 단위) ----

/** H4·F5h·Q5b: 수유실 휴게소 경유지를 요청했다(결과 전). */
internal fun Tx.nursingRequested(restId: String) = nursing(NursingStop.Requested(restId))

/** H5: 그 경유지가 들어갔다. */
internal fun Tx.nursingAdded() = nursing(when (val ns = s.ign.nursingStop) {
    null -> null
    is NursingStop.Parked -> ns.copy(addedAt = now)
    else -> NursingStop.Added(ns.restId, now)
})

/** H5a(그 쉼터 실패)·수명 표 Nursing 행의 DROP. */
internal fun Tx.dropNursing() = nursing(null)

/** F7: 경유지를 넣은 뒤 처음 P. */
internal fun Tx.nursingParked() {
    val ns = s.ign.nursingStop
    if (ns is NursingStop.Added) nursing(NursingStop.Parked(ns.restId, ns.addedAt, now))
}

/** F9: 첫 P 뒤 P에서 나오면 수유 확인을 버린다. */
internal fun Tx.nursingLeftPark() { if (s.ign.nursingStop?.firstPAt != null) nursing(null) }

/** F8: 첫 P에서 오래 서 있으면 수유 확인을 띄운다. */
internal fun Tx.nursingTimer() {
    val ns = s.ign.nursingStop
    if (ns is NursingStop.Parked && !ns.confirmShown && gearP && now - ns.firstPAt >= p.feedingParkConfirmMs) {
        nursing(ns.copy(confirmShown = true))
        out += ShowNotice(Notice.FEEDING_CONFIRM)
    }
}

/** link가 보낼 때 Room 값으로 다시 만든다(4.3). 여기서는 같은 이름으로 채워 둔다. */
internal fun Tx.feedingBody(): JsonObject = buildJsonObject {
    put("last_feeding_start", s.feedingStart)
    put("window_start_expected", windowStartExpected(s.feedingStart, s.profile?.feedingIntervalMin, p))
}
