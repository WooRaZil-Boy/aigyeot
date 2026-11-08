package kr.aigyeot.engine

// 시험 도구: 가짜 시계(사건의 at)로 상태를 만들고 step을 차례로 넣는다.

const val T0 = 1_800_000_000_000L
const val MIN = 60_000L
val P = Params()
val WN = Method(MethodKind.WHITE_NOISE)
val WB = Method(MethodKind.WOMB)
val LL = Method(MethodKind.LULLABY)
val SONG = Method(MethodKind.SONG, "s1")

fun profile(seat: CarSeat = CarSeat.LEFT, interval: Int? = null, sibling: Boolean = false) =
    Profile("2026-03", seat, interval, sibling)

fun rest(id: String, km: Double, nursing: Boolean = false) =
    RestCandidate(id, "$id 휴게소", RestKind.REST_AREA, km, nursing)

class Sim(var s: EngineState, private val eng: Engine = Engine()) {
    var t = s.now
    val log = mutableListOf<Command>()
    var last: Step = Step(s, emptyList(), null)

    fun ev(e: Event): Sim {
        last = eng.step(s, e); s = last.state; log += last.commands; t = maxOf(t, e.at)
        return this
    }
    fun tick(dt: Long = 0) = ev(Tick(t + dt))
    val cmds get() = last.commands
    inline fun <reified C> all() = log.filterIsInstance<C>()
    inline fun <reified C> step() = cmds.filterIsInstance<C>()
    val ses get() = s.session!!
    val ep get() = s.session!!.episode!!
    fun play() = s.session!!.play!!
    fun clear() = apply { log.clear() }

    /** 낸 Speak·Ask와 아직 큐에 있는 문장. */
    fun sids(): List<Sid> = log.mapNotNull { (it as? Speak)?.sid ?: (it as? Ask)?.sid } +
        s.speech.queue.drop(if (s.speech.speaking != null) 1 else 0).map { it.sid }

    /** 말하는 문장을 끝낸다(듣기 창이 열리면 들은 말 없음으로 닫는다). */
    fun done(dt: Long = 0, spoken: Boolean = true): Sim {
        ev(SpeechDone(t + dt, s.speech.speaking!!, spoken))
        s.speech.qa?.let { ev(Heard(t, it.listenId, null)) }
        return this
    }
}

/**
 * D5 시험 조립(옛 `copy(phase = …, attemptNo = …, play = …, judgeFrom = …)`): 단계만 바꾼 세션. 시도 번호는 지금 값.
 * ATTEMPT는 지금 재생(없으면 시험용 자동 재생), GAP·CONFIRM은 판정 멈춤(창 없음), WAIT·HANDOFF는 조용함 0. LISTENING이면 enter처럼 에피소드를 비운다.
 */
fun SessionState.withStage(phase: Phase): SessionState = copy(
    stage = when (phase) {
        Phase.LISTENING -> Stage.Listening
        Phase.ATTEMPT -> Stage.Attempt(maxOf(attemptNo, 1), play ?: Play("$id-test", PlayKind.AUTO, PlayPurpose.AUTO_FIRST, WN, null, openedAt + P.attemptMs, PlayMode.NORMAL))
        Phase.GAP -> Stage.Gap(attemptNo, null)
        Phase.CONFIRM -> Stage.Confirm(attemptNo, null)
        Phase.WAIT -> Stage.Wait(attemptNo)
        Phase.HANDOFF -> Stage.Handoff(attemptNo)
    },
    episode = if (phase == Phase.LISTENING) null else episode,
)

/** D7 시험 조립(옛 `copy(lastOffer = …, pendingAsk = …, driveFrom = …)`): 제안 상태(OfferState)만 바꾼 세션. */
fun SessionState.withOffers(f: OfferState.() -> OfferState): SessionState = copy(offers = offers.f())

/** 지금 세션의 인계 장부(V6: 장부 하나 `handoffs`에서 세션 id로 고른다). */
fun EngineState.openLedger(): HandoffLedger? = session?.let { x -> handoffs.firstOrNull { it.sessionId == x.id } }
/** 닫힌 세션의 인계 장부(옛 closedHandoffs). */
fun EngineState.closedLedgers(): List<HandoffLedger> = handoffs.filter { it.sessionId != session?.id }
val Sim.ledger: HandoffLedger? get() = s.openLedger()
/** 그 에피소드의 마지막 인계 확정(SettleHandoff, 지금까지 낸 할 일에서). 없으면 아직 확정 전. */
fun Sim.settled(id: String = ep.id): SettleHandoff? = log.filterIsInstance<SettleHandoff>().lastOrNull { it.episodeId == id }
/** 인계 문장을 말했나(옛 ep.handoff.toldDriver). 확정 전·H1이면 null. */
fun Sim.told(id: String = ep.id): Boolean? = settled(id)?.toldDriver

fun idle(
    prof: Profile? = profile(), methods: List<Method> = listOf(WN, LL), history: List<FirstTry> = emptyList(),
    feedingStart: Long? = null, guardians: Int = 1, seed: Long = 42, mic: Boolean = true, gear: Gear = Gear.P,
): Sim {
    val sim = Sim(EngineState.fresh(seed, prof, methods, history, feedingStart, guardians))
    sim.ev(Boot(T0, gear, 10))
    if (mic) sim.ev(MicService(T0, true, MicPath.HOME_BUTTON))
    return sim
}

fun Sim.doorCycle(side: Side = Side.LEFT) = ev(RearDoor(t, side, true)).ev(RearDoor(t, side, false))
fun Sim.gear(g: Gear, dt: Long = 0) = ev(GearChanged(t + dt, g))
fun Sim.route(vararg rests: RestCandidate, dest: Boolean = false, remainingSec: Int? = null, remainingKm: Double? = null) =
    ev(RouteState(t, dest, remainingSec, rests.toList(), remainingKm))

/** LISTENING인 세션. */
fun session(
    prof: Profile? = profile(), methods: List<Method> = listOf(WN, LL), history: List<FirstTry> = emptyList(),
    feedingStart: Long? = null, guardians: Int = 1, seed: Long = 42, mic: Boolean = true,
): Sim = idle(prof, methods, history, feedingStart, guardians, seed, mic).doorCycle().gear(Gear.D)

fun Sim.alarm(dt: Long = 0) = ev(CrySegment(t + dt, t + dt - 3_000, t + dt, 0.9, true))
/** start에 시작한 3초 구간. 구간이 끝난 뒤 도착한다. */
fun Sim.seg(start: Long, p: Double = 0.9) = ev(CrySegment(maxOf(t, start + 3_000), start, start + 3_000, p, false))
fun Sim.started(fallback: Boolean = false) = ev(PlaybackStarted(t, play().id, "bus100_audio_zone_1", fallback))
fun Sim.attempt1() = alarm().started()
fun Sim.toGap() = ev(Tick(play().endAt))
/** 지금 시작하는 울음 구간(GAP·CONFIRM 판정 창 안). */
fun Sim.recry() = seg(t)
/** 창을 조용히 닫는 구간(end > windowEnd). */
fun Sim.closeQuiet() = seg(ses.windowEnd!! - 1_000, 0.1)
fun Sim.toConfirm() = toGap().closeQuiet()
/** 1차 실패 → ATTEMPT(2) 재생 중. */
fun Sim.attempt2() = attempt1().toGap().recry().started()
/** 두 번 시도 뒤 HANDOFF. */
fun Sim.handoff() = attempt2().toGap().recry()

/** 큐가 빌 때까지 문장을 끝내며 8초씩 흘린다(듣기 창은 들은 말 없음으로 닫는다). */
fun Sim.flush(): Sim {
    repeat(12) {
        if (s.speech.speaking != null) done()
        if (s.speech.queue.isEmpty()) return this
        tick(P.speechGapMs)
    }
    return this
}
