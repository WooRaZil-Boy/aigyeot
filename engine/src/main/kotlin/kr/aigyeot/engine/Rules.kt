package kr.aigyeot.engine

// 상태를 읽는 순수 규칙(설계 3.8, P1). 상태 → 값이고 부수 효과가 없다. 쓰는 칸 없음.
// Tx 확장 함수는 지금 Step의 상태(s·now·p)를 읽기만 한다. 나머지는 앱·시험·장면도 쓰는 공개 규칙이다.

internal val AUTO_PHASES = setOf(Phase.ATTEMPT, Phase.GAP, Phase.CONFIRM)
/** C303 A23: 쉼터 제안 문장. 휴식 기록에 남기고, 장면 측정은 이 문장의 '응'을 운전자 조작에서 뺀다(15장). */
val OFFER_SIDS = setOf(Sid.S07, Sid.S07_DEPART, Sid.S08, Sid.S10, Sid.S11)
internal val OTHER_OFFERS = setOf(Sid.S07, Sid.S08, Sid.S10, Sid.S11)   // 쉼터 제안 문장. F5i: 큐에 있거나 말하는 중이거나 10분(offerRepeatMs) 안에 냈으면 출발 제안을 미룬다. C272: 꺼낼 때 다시 고른다
internal val HOLDABLE = setOf(Sid.S06, Sid.S07, Sid.S07_DEPART, Sid.S10, Sid.S11)   // C107: 운전이 바쁘면 미루는 급하지 않은 문장

/** 인계 줄이 알리는 인계 종류(H2·H3·H7). */
internal fun kindOf(sid: Sid) = when (sid) { Sid.S15 -> HandoffKind.ARRIVAL_NOTICE; Sid.S10 -> HandoffKind.NO_REST; else -> HandoffKind.WAYPOINT_OFFER }

internal fun Method?.isSong() = this?.kind.let { it == MethodKind.LULLABY || it == MethodKind.SONG }

// ---- 감지·조용함(5.3·11a) ----

/** C303 ①(C1)·C325 ①: 지금 들어오는 울음 구간을 판단 코어가 본다. 세션 밖·마이크 꺼짐·판정 멈춤(5.3, car.pauses V16)·자동 시도 재생 중(R3, 노래 오인)이면 보지 않는다. */
fun EngineState.hears(): Boolean = session != null && car.micRunning && car.pauses.isEmpty() && session.stage !is Stage.Attempt

/**
 * C303 ①(C1·C301)·V4(C318): 이 구간을 봤나. [hears]이고 마지막 자동 시도 재생이 멈춘 시각(mixedUntil) 앞에 시작하지 않았다(재생 소리가 섞인 구간).
 * 다음 단계(GAP·WAIT·HANDOFF)와 상관없이 이 함수 하나가 거른다. 횟수·경보·울음 시간·조향 창·판정이 같이 쓴다. 판정 창 시작(judgeFrom)은 [judge]만 본다(P2).
 */
fun EngineState.sees(e: CrySegment): Boolean = hears() && session?.mixedUntil.let { it == null || e.start >= it }

/** C303 C2: HANDOFF·WAIT 5분 타이머가 센다(판정 멈춤·Suspend 중엔 세지 않는다). */
internal fun Tx.quietCounts(x: SessionState) = (x.phase == Phase.HANDOFF || x.phase == Phase.WAIT) && s.car.pauses.isEmpty() && !suspended

/** C303 C2: 조용함으로 에피소드를 닫을 수 있는 때(11a). 재생 중이면 그 재생 끝(18f·18h·18k)이 본다. */
internal fun Tx.quietClosable(x: SessionState) = quietCounts(x) && x.play == null

/** C303 C2: 11a 조용함 5분이 찼다. */
internal fun Tx.quietDone(x: SessionState) = quietClosable(x) && x.quietMs >= p.resumeQuietMs

// ---- 시도·차량 ----

internal fun Tx.babyAreas(): List<Area> = when (s.profile?.carseat) {
    CarSeat.LEFT -> listOf(Area.ROW_2_LEFT)
    CarSeat.RIGHT -> listOf(Area.ROW_2_RIGHT)
    else -> listOf(Area.ROW_2_LEFT, Area.ROW_2_RIGHT)
}

internal fun Tx.feeding() = feedingWindow(s.feedingStart, s.profile?.feedingIntervalMin, now, p)
internal fun Tx.hot() = (s.car.cabinC ?: Double.NEGATIVE_INFINITY) >= p.comfortTempC && ses?.comfortDone == false

// ---- ④ 묻기 ----

/** C303 ②(A2): ④ 지금 울음(C288). unavailable·confirming·runFrom(이어진 울음의 처음) 중 앞 칸이 서면 뒤 칸은 비운다. */
data class CryView(val unavailable: Boolean, val confirming: Boolean, val runFrom: Long?)

/**
 * C303 ②(A2·B3): ④ 지금 울음. 판단 코어가 울음 구간을 보지 않으면([hears]가 거짓: 세션 밖·끊김·자동 시도 재생 중, 또는 마이크 꺼짐) 확인할 수 없음,
 * 그침 확인 중(GAP·CONFIRM)이면 confirming, 아니면 이어진 경보가 끊기지 않았을 때 그 처음. 상한 인계도 경보가 이어지면 울고 있음이다(C288 감지 기준).
 */
fun EngineState.cryView(now: Long, p: Params = Params()): CryView {
    val x = session
    val unavailable = x == null || !hears()
    val confirming = !unavailable && (x!!.phase == Phase.GAP || x.phase == Phase.CONFIRM)
    return CryView(unavailable, confirming, x?.alarmRun?.takeIf { !unavailable && !confirming && alarmContinues(it.last, now, p) }?.from)
}

/** 듣기 창을 열 수 있다(Q2·Q1): 통화·Suspend·마이크 꺼짐·질문 창·답을 기다리는 쉼터 질문이 없을 때. */
fun EngineState.listenOk(): Boolean = !car.inCall && ign.suspendedAt == null && car.micRunning && speech.qa == null && session?.pendingAsk == null

/** C303 ⑤(B7): 물어보기(AskButton, Q2)를 받는다. 홈 화면 버튼도 이 값으로 켠다. */
fun EngineState.canAsk(): Boolean = car.gear == Gear.P && speech.speaking == null && listenOk()

/** CS1(C126 ①·L77): 새 요약이 지금 값을 바꾸는가. 같은 방 seq 비교가 먼저, 아니면 asOf가 늦거나 같은 초에 둘 다 seq가 있고 새것이 크다. */
fun careReplaces(old: CareView?, oldSeq: Long?, new: CareView, newSeq: Long?, oldRoom: String? = null, newRoom: String? = null): Boolean {
    if (old == null) return true
    // L77: 같은 방이고 양쪽 seq가 있으면 보낸 기기와 관계없이 중계 도착 순서(seq)가 새 순서다. as_of(폰 시계)는 보지 않는다
    if (oldRoom != null && oldRoom == newRoom && oldSeq != null && newSeq != null) return newSeq > oldSeq
    return new.asOf > old.asOf ||
        (new.asOf == old.asOf && ((oldRoom != null && newRoom != null && oldRoom != newRoom) || (oldSeq != null && newSeq != null && newSeq > oldSeq)))   // 둘 다 방을 알고 다르면 seq 비교가 뜻이 없어 나중에 받은 것(C130). 모르면(옛 스냅숏·장면) 같은 방으로 본다
}

// ---- 인계(H행) ----

/** C303 A10: 알림을 줄을 기다리지 않고 바로 보내는 인계 종류(H1 PARKED·H2 ARRIVAL_NOTICE). */
fun HandoffKind.alertsAtOnce(): Boolean = this == HandoffKind.ARRIVAL_NOTICE || this == HandoffKind.PARKED

/** C305: 인계 문장을 운전자에게 말하는 종류인가(H1 PARKED는 말하지 않는다). '말했는지'·'확정 전' 판정이 이 함수 하나를 쓴다. StoreDao·AppDatabase의 SQL 상수 `!= 'PARKED'`가 이 규칙이다. */
fun HandoffKind.speaks(): Boolean = this != HandoffKind.PARKED

/** C303 A10: 인계 문장을 내지 않는 종류(H1 PARKED)는 말했는지가 늘 null이다. */
fun HandoffKind.told(told: Boolean): Boolean? = if (speaks()) told else null

/** C303 A10: 확정된 인계 행의 '말함'. null은 옛 행·H1이라 말한 것으로 본다(C130 ⑦). 확정 전(settled 거짓)의 null은 아직 모름. 리포트 집계·차 화면이 같이 쓴다. */
fun toldShown(told: Boolean?, settled: Boolean): Boolean = told == true || (settled && told == null)

/**
 * C303 A9·V20(P1): 인계 확정 규칙 하나(H11). 종류는 확정할 때의 종류(장부면 다시 고른 종류), H1은 말함이 null, 쉼터 이름은 말한 경유 제안만 남긴다.
 * 판단 코어(장부 확정·줄 없는 인계)와 장면 끝(Store.settleSceneHandoffs)이 이 함수로 [SettleHandoff]를 만든다.
 */
fun settleHandoff(episodeId: String, kind: HandoffKind, told: Boolean, restName: String?): SettleHandoff =
    SettleHandoff(episodeId, kind, kind.told(told), restName.takeIf { told && kind == HandoffKind.WAYPOINT_OFFER })

/** 장부를 같은 규칙으로 확정한다. 에피소드가 없으면 쓸 행이 없다. */
fun HandoffLedger.settle(told: Boolean): SettleHandoff? = episodeId?.let { settleHandoff(it, kind, told, restName) }

/** C303 A8: 그 세션의 인계 안내 줄(C258 표시). */
fun Line.isHandoffOf(sessionId: String?): Boolean = handoff && sessionId != null && this.sessionId == sessionId

/** C258: 지금 세션의 인계 안내 줄인가(표시로 알아본다). */
internal fun Tx.guide(l: Line) = l.isHandoffOf(ses?.id)

/** C258·C272: 꺼낼 때 지금 경로로 다시 고르는 줄. 인계 줄, 그리고 세션이 열린 동안의 쉼터 제안 줄(S07·S08·S10·S11). */
internal fun Tx.repick(l: Line) = guide(l) || (l.sid in OTHER_OFFERS && ses != null)

// ---- 경로·쉼터(G15·C269) ----

/**
 * 경로가 아직 정해지지 않음(G15). 쉼터에 기대는 판단(L1, F5, 인계 고르기·꺼내기, 꺼낼 때 S10)은 이 동안 보류한다. 둘 다 최대 routeWaitMs.
 * ① C128 ⑤·C145·C150: 복원 뒤, 목적지와 쉼터 목록을 함께 든 경로가 아직 없음(목적지 신호가 쉼터 목록보다 먼저 와도 기다린다).
 * ② C269: 쉼터 목록을 다시 받는 중(restsPending이 처음 참이 된 때부터). 빈 목록을 '쉼터 없음'으로 보지 않는다.
 */
internal fun Tx.routePending() = routeWaitFrom() != null

/** 지금 경로 기다림(①·②)이 시작된 시각. 둘 다면 이른 쪽, 기다리지 않으면 null. */
internal fun Tx.routeWaitFrom(): Long? {
    val r = s.car.route
    val boot = s.bootAt?.takeIf { now - it < p.routeWaitMs && !(r?.hasDestination == true && r.rests.isNotEmpty()) }
    return listOfNotNull(boot, r?.restsPendingSince?.takeIf { now - it < p.routeWaitMs }).minOrNull()
}

/** C303 C6: 지금 경로의 그 쉼터(이름·수유실 여부는 여기서 읽는다). 없으면 null. */
internal fun Tx.restOf(id: String): RestCandidate? = s.car.route?.rests?.firstOrNull { it.id == id }

/** 경유지를 새로 요청해도 되는가. 이미 넣은 쉼터거나 다른 요청이 진행 중이면 아니다(H4c, ④ 질문과 같은 검사). C353: 지금 경로에서 addable=false면 넣지 않는다. */
internal fun Tx.canAddWaypoint(restId: String): Boolean =
    ses?.let { restId !in it.addedRestIds && it.waypointPending == null && restOf(restId)?.addable != false } ?: false

// ---- 비동기 결과의 문맥(P5, 설계 3.7) ----
// 결과(Reply)의 ref가 가리키는 대기 항목. null이면 늦게 왔거나 모르는 결과라 버린다(H4b·C65·14n·Q7). 처리 함수는 이 항목에 잡아 둔 문맥만 쓰고
// 결과가 온 순간의 상태로 대상을 다시 정하지 않는다. 말하기 끝(SpeechDone)은 speechDone이 ref(= speaking)로, 뒷창 잠금 결과는 windowResult가
// cmdId로 그 항목의 locking·restoring 칸을 찾는다(항목 안 자리까지 필요해서).

/** 그 질문의 답 기다림(쉼터·에피소드·수유실·휴식 기록 줄 위치를 질문할 때 잡았다). */
internal fun EngineState.waitingFor(r: Answer): PendingAsk? = session?.pendingAsk?.takeIf { it.askId == r.ref }

/** 그 요청의 경유지 결과 기다림(C65: 명령 id와 쉼터가 같아야 한다). */
internal fun EngineState.waitingFor(r: WaypointResult): WaypointPending? = session?.waypointPending?.takeIf { it.cmdId == r.ref && it.restId == r.restId }

/** 그 명령의 쾌적 조정 결과 기다림(42g, C102). */
internal fun EngineState.waitingFor(r: ControlResult): ComfortPending? = session?.comfortPending?.takeIf { it.cmdId == r.ref }

/** 그 재생(시작 대기·재생 중). */
internal fun EngineState.waitingFor(r: PlayReply): Play? = session?.play?.takeIf { it.id == r.ref }

/** 그 듣기 창(듣는 중). */
internal fun EngineState.waitingFor(r: Heard): QaWindow? = speech.qa?.takeIf { it.listenId == r.ref && it.step == QaStep.HEARING }

/** 그 질문 창(답 기다림). */
internal fun EngineState.waitingFor(r: QaReply): QaWindow? = speech.qa?.takeIf { it.listenId == r.ref && it.step == QaStep.THINKING }

// ---- 말하기 꺼내기(S5·S6·G15·C150) ----

/** 최근 표본이 바쁨이다. 표본이 끊겨 `driveBusyAt`이 오래되면 거짓이라 미루지 않는다(S7). */
internal fun Tx.driveBusy() = s.steer.driveBusyAt?.let { now - it < p.holdStableMs } == true

/** F5k·C124·C323: 꺼낼 때 S06·출발 제안을 지금 수유·경로로 다시 고른다(바뀌었으면 null = 버림). 다른 쉼터 제안은 drain의 다시 고르기(C258·C272)가 맡는다. */
internal fun Tx.refreshed(l: Line): Line? {
    if (l.sid == Sid.S06) return s06Vars()?.let { l.copy(vars = l.vars + it) }   // C323: 수유 창이 아니면 버림(s06Said는 말할 때만 선다)
    if (l.sid != Sid.S07_DEPART) return l
    val rest = pickDepartRest(s.car.route, s.feedingStart, s.profile?.feedingIntervalMin, now, p.departLeadMin)
    if (rest == null || rest.id != l.askRestId) return null
    return l.copy(vars = l.vars + departVars(rest, hungryAt(s.feedingStart!!, s.profile!!.feedingIntervalMin!!)))
}

/** C150·C246: 경로 기다림 동안 경로에 기대는 줄(쉼터 질문·S07_DEPART·S10·S15)은 버리지 않고 건너뛴다. */
internal fun Tx.heldForRoute(l: Line, waiting: Boolean) = waiting && (l.sid == Sid.S07_DEPART || l.sid == Sid.S10 || l.sid == Sid.S15 || l.askRestId != null)

/** H4·F5j: 질문 문장의 기어 조건. 꺼낼 때 valid와 답 처리 직전이 같은 함수 하나를 쓴다. */
internal fun Tx.gearAllows(sid: Sid) =
    if (sid == Sid.S07_DEPART) s.car.gear == null || s.car.gear == Gear.D   // C233: 기어를 모르면 버리지 않고 보류(drain이 건너뜀)
    else !gearP

/** 꺼낼 때 문장이 아직 맞는지 다시 본다(G15·C15). */
internal fun Tx.valid(l: Line) = when (l.sid) {
    Sid.S02, Sid.S03, Sid.S04 -> l.playId != null && ses?.play?.id == l.playId   // '그 재생'이 계속될 때만
    Sid.S07_DEPART -> gearAllows(l.sid) &&
         (l.askRestId == null || canAddWaypoint(l.askRestId))   // F5j: 후진·중립에서는 말하지 않고 버린다
    Sid.S06, Sid.S07, Sid.S08, Sid.S10, Sid.S11, Sid.S15 -> gearAllows(l.sid) && (l.askRestId == null || canAddWaypoint(l.askRestId))   // 쉼터 제안·인계 줄의 경로 판단은 drain의 다시 고르기(C258·C272)
    else -> true
}

// ---- 앰비언트 조명 목표(기능 D, 5.10 '언제' 표, C358) ----

/** 앰비언트 목표. HOLD = 지금 조명을 그대로 둔다(재생 시작 대기). */
internal enum class AmbientGoal { DIM, NORMAL, HOLD }

/** 지금 상태에서 계산한 앰비언트 목표(P4: 칸에 두지 않는다). 읽는 곳은 [syncAmbient] 하나. */
internal fun EngineState.ambientGoal(): AmbientGoal {
    val x = session ?: return AmbientGoal.NORMAL
    x.play?.let { return if (it.startedAt == null) AmbientGoal.HOLD else AmbientGoal.DIM }   // 시작 대기는 HOLD, 시작된 자동·요청 재생은 DIM
    return if (x.phase in setOf(Phase.GAP, Phase.CONFIRM) && x.episode != null) AmbientGoal.DIM else AmbientGoal.NORMAL
}
