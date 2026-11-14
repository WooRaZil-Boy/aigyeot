package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 달래는 동안 실내 앰비언트 조명(낮춤·되돌림·결과 대기·장면 드나들기).
class AmbientTest {
    private val B = ControlKind.AMBIENT_BRIGHTNESS
    private val C = ControlKind.AMBIENT_COLOR
    private val BRIGHT = AmbientValue(70, 20)
    private val COLOR = AmbientValue(0xFFFFFF, 0x3050A0)
    private val BACK = AmbientBack(BRIGHT, COLOR)

    private fun Sim.amb() = s.ign.ambient
    private fun Sim.res(cmd: String, kind: ControlKind, ok: Boolean = true, before: Double? = null, after: Double? = null) =
        ev(ControlResult(t, cmd, kind, null, ok, before, after))
    private fun Sim.dimId() = all<DimAmbient>().last().cmdId
    private fun Sim.restoreId() = all<RestoreAmbient>().last().cmdId
    /** 낮춤 결과 둘(바꾸고 확인됨). 기본은 밝기 70 → 20, 색 흰색 → 어두운 파랑. */
    private fun Sim.dimOk(): Sim {
        val c = dimId()
        res(c, B, true, 70.0, 20.0); res(c, C, true, 0xFFFFFF.toDouble(), 0x3050A0.toDouble())
        return this
    }
    private fun Sim.restoreResults(ok: Boolean = true): Sim {
        val c = restoreId()
        res(c, B, ok, 20.0, 70.0); res(c, C, ok, 0x3050A0.toDouble(), 0xFFFFFF.toDouble())
        return this
    }
    private fun Sim.dimmedAttempt() = attempt1().dimOk()
    private fun noSpeechOrNotice(cmds: List<Command>) =
        assertTrue(cmds.none { it is Speak || it is Ask || it is ShowNotice }, "$cmds")
    private fun Sim.setAmbient(a: Ambient?) = apply { s = s.copy(ign = s.ign.copy(ambient = a)) }

    @Test fun ambient_snapshotRoundTrip_everyState() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }   // Store.SnapshotJson과 같은 설정
        val base = session().dimmedAttempt().s
        for (a in listOf<Ambient?>(null, Ambient.Dimming("s", "c", AmbientBack(BRIGHT, null), setOf(C)), Ambient.Dimmed("s", BACK), Ambient.Restoring("s", "c", BACK, setOf(B, C)))) {
            val s = base.copy(ign = base.ign.copy(ambient = a))
            assertEquals(s, json.decodeFromString<EngineState>(json.encodeToString(s)), "$a")
        }
    }

    @Test fun attemptStarted_dimsOnce() {
        val sim = session().alarm()
        assertTrue(sim.all<DimAmbient>().isEmpty(), "시작 대기는 HOLD")
        sim.started()
        val d = sim.step<DimAmbient>().single()
        assertEquals(P.ambientDimLevel, d.level); assertEquals(P.ambientDimColor, d.color)
        assertEquals(Ambient.Dimming(sim.ses.id, d.cmdId, AmbientBack(), setOf(B, C)), sim.amb())
        assertTrue(sim.cmds.indexOfLast { it is DimAmbient } > sim.cmds.indexOfFirst { it is SaveEpisode }, "그 Step의 다른 할 일 뒤")
        sim.tick(1_000).tick(1_000)
        assertEquals(1, sim.all<DimAmbient>().size, "목표가 같으면 다시 내지 않는다")
    }

    @Test fun attempt1PendingOrRejected_noAmbient() {
        val sim = session().alarm()
        assertNull(sim.amb()); assertTrue(sim.all<DimAmbient>().isEmpty())
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.NO_REAR_OUTPUT))
        assertNull(sim.amb()); assertTrue(sim.all<DimAmbient>().isEmpty() && sim.all<RestoreAmbient>().isEmpty())
        sim.alarm(60_000)   // 경보마다 재생을 새로 내도 조명은 바뀌지 않는다
        assertTrue(sim.all<DimAmbient>().isEmpty())
    }

    @Test fun pendingStart_holdsLighting() {
        val sim = session().dimmedAttempt().toGap().recry()   // 2차 시작 대기
        assertNull(sim.play().startedAt)
        assertEquals(Ambient.Dimmed(sim.ses.id, BACK), sim.amb())
        assertTrue(sim.all<RestoreAmbient>().isEmpty()); assertEquals(1, sim.all<DimAmbient>().size)
        sim.started()
        assertEquals(1, sim.all<DimAmbient>().size); assertTrue(sim.all<RestoreAmbient>().isEmpty())
    }

    @Test fun requestFromListening_dimsOnStarted() {
        val sim = session().ev(SwitchSound(T0, LL))
        assertTrue(sim.all<DimAmbient>().isEmpty(), "요청 재생도 시작 대기는 HOLD")
        sim.started()
        assertEquals(1, sim.step<DimAmbient>().size)
    }

    @Test fun secondRejected_restores() {
        val sim = session().dimmedAttempt().toGap().recry()
        sim.ev(PlaybackRejected(sim.t, sim.play().id, RejectReason.FOCUS_DENIED))
        assertNull(sim.ses.play)
        assertEquals(RestoreAmbient(sim.restoreId(), BRIGHT, COLOR), sim.step<RestoreAmbient>().single())
        assertTrue(sim.amb() is Ambient.Restoring)
    }

    @Test fun wholeAutoCycle_singleDim() {
        val sim = session().attempt2()   // 1차 → GAP → 2차 시작
        sim.dimOk()
        sim.toGap().closeQuiet()          // GAP → CONFIRM
        assertEquals(Phase.CONFIRM, sim.ses.phase)
        assertEquals(1, sim.all<DimAmbient>().size); assertTrue(sim.all<RestoreAmbient>().isEmpty())
        assertTrue(sim.amb() is Ambient.Dimmed)
    }

    @Test fun success_restores() {
        val sim = session().dimmedAttempt().toConfirm().closeQuiet()
        assertEquals(Phase.LISTENING, sim.ses.phase)
        val r = sim.step<RestoreAmbient>().single()
        assertEquals(RestoreAmbient(r.cmdId, BRIGHT, COLOR), r)
        assertTrue(r.cmdId.startsWith("${sim.ses.id}-"))
        assertEquals(Ambient.Restoring(sim.ses.id, r.cmdId, BACK, setOf(B, C)), sim.amb())
        assertFalse(sim.s.ambientDimmed)
        assertTrue(sim.step<RestoreAmbientLedger>().isEmpty(), "두 종류 모두 확인됐으면 장부를 부르지 않는다")
    }

    @Test fun handoff_restores() {
        val sim = session().attempt2().dimOk().toGap().recry()
        assertEquals(Phase.HANDOFF, sim.ses.phase)
        assertEquals(1, sim.step<RestoreAmbient>().size)
        assertTrue(sim.amb() is Ambient.Restoring)
    }

    @Test fun waitPaths_restore() {
        val stops: Map<String, (Sim) -> Event> = mapOf(
            "그만" to { DriverStop(it.t, Via.VOICE) }, "P" to { GearChanged(it.t, Gear.P) }, "포커스 상실" to { FocusChanged(it.t, it.play().id, FocusChange.LOSS) },
            "재생 오류" to { PlaybackEnded(it.t, it.play().id, EndReason.ERROR) }, "통화" to { CallMode(it.t, true) }, "Suspend" to { Suspend(it.t) },
        )
        for ((name, stop) in stops) {
            val sim = session().dimmedAttempt()
            sim.ev(stop(sim))
            assertEquals(Phase.WAIT, sim.ses.phase, name)
            assertEquals(1, sim.step<RestoreAmbient>().size, name)
            assertTrue(sim.amb() is Ambient.Restoring, name)
        }
    }

    @Test fun requestPlayEnds_restores() {
        val end = session().ev(SwitchSound(T0, LL)).started().dimOk().ev(Tick(T0 + 56_000))   // 시간 끝
        assertNull(end.ses.play)
        assertEquals(1, end.step<RestoreAmbient>().size)
        val stop = session().ev(SwitchSound(T0, LL)).started().dimOk().ev(DriverStop(T0 + 1_000, Via.VOICE))
        assertEquals(1, stop.step<RestoreAmbient>().size)
    }

    @Test fun sessionClose_restores() {
        // 정상 흐름에서는 P가 되는 Step에 이미 되돌림이 나가므로, 되돌림 전 상태를 칸에 놓고 닫는다(세션 닫힘도 같은 길)
        val sim = session().gear(Gear.P, MIN)
        val id = sim.ses.id
        sim.setAmbient(Ambient.Dimmed(id, BACK))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertNull(sim.s.session)
        val r = sim.step<RestoreAmbient>().single()
        assertTrue(r.cmdId.startsWith("$id-"), "낮춘 세션 몫의 cmdId")
        assertTrue(sim.amb() is Ambient.Restoring)
        assertTrue(sim.step<CloseSession>().isEmpty(), "되돌림 결과 전에는 리포트 없음")
    }

    // 확인된 종류가 하나도 없어도(이미 낮음·같은 색·꺼짐, 또는 쓰고도 확인 못 함) 실행기 장부가 값을 알 수 있어 장부 되돌림을 낸다(장부가 비면 무해)
    @Test fun nothingConfirmed_ledgerRestoreOnly() {
        val sim = session().attempt1()
        val c = sim.dimId()
        sim.res(c, B, false, 20.0, 20.0).res(c, C, false, 0x3050A0.toDouble(), 0x3050A0.toDouble())   // 이미 낮음·같은 색
        assertEquals(Ambient.Dimmed(sim.ses.id, AmbientBack()), sim.amb())
        assertFalse(sim.s.ambientDimmed)
        sim.toConfirm().closeQuiet()
        assertTrue(sim.all<RestoreAmbient>().isEmpty()); assertNull(sim.amb())
        assertEquals(1, sim.step<RestoreAmbientLedger>().size)
    }

    @Test fun unconfirmedKind_alsoLedgerRestore() {
        val sim = session().attempt1()
        val c = sim.dimId()
        sim.res(c, B, true, 70.0, 20.0).res(c, C, false, null, null)   // 색은 쓰고도 확인 못 함(ok=false)
        sim.toConfirm().closeQuiet()
        val r = sim.step<RestoreAmbient>().single()
        assertEquals(RestoreAmbient(r.cmdId, BRIGHT, null), r)
        val names = sim.cmds.map { it::class.simpleName }
        assertTrue(names.indexOf("RestoreAmbient") < names.indexOf("RestoreAmbientLedger"), "$names")
        assertTrue(sim.step<RestoreAmbientLedger>().single().cmdId.startsWith("${sim.ses.id}-"), "낮춘 세션 몫")
        assertTrue(sim.amb() is Ambient.Restoring)
    }

    @Test fun partialBack_sessionClose_pairOfCommands() {
        val sim = session().gear(Gear.P, MIN)
        val id = sim.ses.id
        sim.setAmbient(Ambient.Dimmed(id, AmbientBack(BRIGHT, null)))
        sim.ev(RearDoor(sim.t, Side.LEFT, true))
        assertEquals(1, sim.step<RestoreAmbient>().size); assertEquals(1, sim.step<RestoreAmbientLedger>().size)
        assertTrue(sim.step<RestoreAmbientLedger>().single().cmdId.startsWith("$id-"))
    }

    @Test fun dimResults_collectBoth() {
        val sim = session().attempt1()
        val c = sim.dimId()
        sim.res(c, B, true, 70.0, 20.0)
        assertEquals(Ambient.Dimming(sim.ses.id, c, AmbientBack(brightness = BRIGHT), setOf(C)), sim.amb())
        assertFalse(sim.s.ambientDimmed)
        sim.res(c, C, true, 0xFFFFFF.toDouble(), 0x3050A0.toDouble())
        assertEquals(Ambient.Dimmed(sim.ses.id, BACK), sim.amb())
        assertTrue(sim.s.ambientDimmed)
    }

    @Test fun failedOrSkippedKind_notRestored() {
        val sim = session().attempt1()
        val c = sim.dimId()
        sim.res(c, B, true, 70.0, 20.0).res(c, C, false, 0xFFFFFF.toDouble(), 0xFFFFFF.toDouble())   // 색은 쓰지 않았다(before = after = 읽은 값, ok = false)
        assertEquals(Ambient.Dimmed(sim.ses.id, AmbientBack(brightness = BRIGHT)), sim.amb())
        assertTrue(sim.s.ambientDimmed)
        sim.toConfirm().closeQuiet()
        assertEquals(RestoreAmbient(sim.restoreId(), BRIGHT, null), sim.step<RestoreAmbient>().single())
        assertEquals(setOf(B), (sim.amb() as Ambient.Restoring).pending)
    }

    @Test fun soothingEndedBeforeResults_restoresAfter() {
        val sim = session().attempt1()
        val c = sim.dimId()
        sim.ev(DriverStop(sim.t, Via.VOICE))
        assertTrue(sim.all<RestoreAmbient>().isEmpty(), "되돌릴 값을 아직 모른다")
        sim.res(c, B, true, 70.0, 20.0)
        assertTrue(sim.all<RestoreAmbient>().isEmpty())
        sim.res(c, C, true, 0xFFFFFF.toDouble(), 0x3050A0.toDouble())
        assertEquals(RestoreAmbient(sim.restoreId(), BRIGHT, COLOR), sim.step<RestoreAmbient>().single())
    }

    @Test fun restoreResult_clears() {
        val sim = session().dimmedAttempt().toConfirm().closeQuiet()
        val c = sim.restoreId()
        sim.res(c, B, true, 20.0, 70.0)
        assertEquals(setOf(C), (sim.amb() as Ambient.Restoring).pending)
        sim.res(c, C, true, 0x3050A0.toDouble(), 0xFFFFFF.toDouble())
        assertNull(sim.amb())
    }

    @Test fun restoreFailed_notRetried() {
        val sim = session().dimmedAttempt().toConfirm().closeQuiet().restoreResults(ok = false)
        assertNull(sim.amb()); assertEquals(1, sim.all<RestoreAmbient>().size)
        sim.tick(1_000)
        assertEquals(1, sim.all<RestoreAmbient>().size)
    }

    @Test fun staleAmbientResult_ignored() {
        val sim = session().attempt1()
        val before = sim.amb()
        sim.res("old-1", B, true, 70.0, 20.0).res("old-1", C, true, 1.0, 2.0)
        assertEquals(before, sim.amb())
        sim.dimOk().toConfirm().closeQuiet()
        val restoring = sim.amb()
        sim.res(sim.dimId(), B, true, 70.0, 20.0)   // 지난 낮춤의 결과가 되돌림 중에 늦게 와도 그대로
        assertEquals(restoring, sim.amb())
    }

    @Test fun dimWaitsForRestoreResult() {
        val sim = session().dimmedAttempt().toConfirm().closeQuiet()   // Restoring
        sim.alarm(MIN).started()
        assertEquals(1, sim.all<DimAmbient>().size, "되돌림 결과 전에는 낮추지 않는다(처음 한 번뿐)")
        assertTrue(sim.amb() is Ambient.Restoring)
        sim.restoreResults()
        assertEquals(1, sim.step<DimAmbient>().size, "결과 뒤 같은 Step 끝에 낸다")
        assertTrue(sim.amb() is Ambient.Dimming)
    }

    @Test fun requestPlay_dimsAndRestores() {
        val sim = session().ev(SwitchSound(T0, LL)).started().dimOk().ev(Tick(T0 + 56_000))
        sim.restoreResults()
        assertNull(sim.amb())
        sim.ev(SwitchSound(sim.t, WN)).started()
        assertEquals(2, sim.all<DimAmbient>().size, "운전자가 튼 재생마다 시작·끝 한 번씩")
    }

    @Test fun switchWhilePlaying_noFlicker() {
        val sim = session().ev(SwitchSound(T0, LL)).started().dimOk().tick(10_000)
        sim.ev(SwitchSound(sim.t, WN))   // 교체(시작 대기)
        assertTrue(sim.all<RestoreAmbient>().isEmpty())
        sim.started()
        assertTrue(sim.all<RestoreAmbient>().isEmpty()); assertEquals(1, sim.all<DimAmbient>().size)
        assertTrue(sim.amb() is Ambient.Dimmed)
    }

    @Test fun reportWaitsForAmbientRestore() {
        // (가) 되돌림 결과를 기다리는 중 세션이 닫힌다
        val a = session().dimmedAttempt()
        val id = a.ses.id
        a.gear(Gear.P, MIN)   // WAIT → Restoring
        assertEquals(1, a.all<RestoreAmbient>().size)
        a.ev(RearDoor(a.t, Side.LEFT, true))
        assertNull(a.s.session)
        assertTrue(a.all<CloseSession>().isEmpty(), "되돌림 결과 전에는 리포트 없음")
        a.res(a.restoreId(), B, false, 20.0, 20.0)
        assertTrue(a.all<CloseSession>().isEmpty())
        a.res(a.restoreId(), C, true, 0x3050A0.toDouble(), 0xFFFFFF.toDouble())
        assertEquals(listOf(CloseSession(id)), a.all<CloseSession>(), "되돌림 실패도 끝난 것으로 한 번")
        // (나) 낮춤 결과를 기다리는 중 닫힌다: 결과 → 되돌림 → 결과 뒤 한 번
        val b = session().attempt1()
        val idB = b.ses.id
        val c = b.dimId()
        b.gear(Gear.P, MIN).ev(RearDoor(b.t, Side.LEFT, true))
        assertTrue(b.all<CloseSession>().isEmpty())
        b.res(c, B, true, 70.0, 20.0); b.res(c, C, true, 0xFFFFFF.toDouble(), 0x3050A0.toDouble())
        assertTrue(b.all<CloseSession>().isEmpty(), "결과가 오면 되돌림을 내고 그 결과를 또 기다린다")
        assertEquals(1, b.all<RestoreAmbient>().size)
        b.restoreResults()
        assertEquals(listOf(CloseSession(idB)), b.all<CloseSession>())
        // (다) 바꾼 것이 없으면 닫는 Step에 바로 한 번(되돌릴 것이 없다)
        val d = session().attempt1()
        val cd = d.dimId()
        d.res(cd, B, false, 20.0, 20.0).res(cd, C, false, 1.0, 1.0)
        val idD = d.ses.id
        d.gear(Gear.P, MIN)
        assertEquals(1, d.step<RestoreAmbientLedger>().size, "back이 비어도 달래기가 끝나면 장부 되돌림을 낸다")
        d.ev(RearDoor(d.t, Side.LEFT, true))
        assertEquals(listOf(CloseSession(idD)), d.all<CloseSession>(), "장부 되돌림은 리포트를 붙잡지 않는다")
    }

    @Test fun bootWhileDimming_dropped() {
        val sim = session().attempt1()
        sim.ev(PlaybackEnded(sim.t, sim.play().id, EndReason.ERROR))   // 호스트가 복원 ③ 앞에서 넣는 사실 사건
        assertTrue(sim.amb() is Ambient.Dimming)
        sim.clear().ev(Boot(sim.t, Gear.D, 10))
        assertNull(sim.amb())
        assertTrue(sim.all<RestoreAmbient>().isEmpty() && sim.all<DimAmbient>().isEmpty())
    }

    @Test fun bootWhileRestoring_resends() {
        val sim = session().dimmedAttempt().toConfirm().closeQuiet()
        val old = sim.restoreId()
        sim.clear().ev(Boot(sim.t, Gear.D, 10))
        val r = sim.step<RestoreAmbient>().single()
        assertNotEquals(old, r.cmdId)
        assertEquals(RestoreAmbient(r.cmdId, BRIGHT, COLOR), r)
        assertEquals(Ambient.Restoring(sim.ses.id, r.cmdId, BACK, setOf(B, C)), sim.amb())
        sim.res(old, B, true, 20.0, 70.0)   // 지난 결과는 오지 않지만 오더라도 버린다
        assertEquals(setOf(B, C), (sim.amb() as Ambient.Restoring).pending)
    }

    @Test fun restoreWhilePlaying_restoresAmbient() {
        val sim = session().dimmedAttempt()
        val eng = Engine()
        val at = sim.t + 10 * MIN
        val st = eng.restoreFromRoom(sim.s, facts(), at)
        val boot = eng.step(st, Boot(at, Gear.D, 10))
        assertTrue(boot.commands.none { it is RestoreAmbient }, "재생이 남아 있어 목표 DIM")
        val ended = eng.step(boot.state, PlaybackEnded(st.now, st.session!!.play!!.id, EndReason.ERROR))
        assertEquals(1, ended.commands.filterIsInstance<RestoreAmbient>().size)
        assertTrue(ended.state.ign.ambient is Ambient.Restoring)
    }

    @Test fun roomClosedSession_restores() {
        val sim = session().dimmedAttempt()
        val eng = Engine()
        val st = eng.restoreFromRoom(sim.s, facts(ended = true, history = sim.s.history), sim.t + MIN)   // ②(a): IDLE
        assertNull(st.session)
        assertTrue(st.ign.ambient is Ambient.Dimmed)
        val boot = eng.step(st, Boot(sim.t + MIN, Gear.D, 10))
        val r = boot.commands.filterIsInstance<RestoreAmbient>().single()
        assertTrue(r.cmdId.startsWith("${sim.ses.id}-"))
    }

    @Test fun restoreInGap_keepsDim() {
        val sim = session().dimmedAttempt().toGap()
        assertEquals(Phase.GAP, sim.ses.phase)
        sim.clear().ev(Boot(sim.t, Gear.D, 10))
        assertEquals(Ambient.Dimmed(sim.ses.id, BACK), sim.amb())
        assertTrue(sim.all<RestoreAmbient>().isEmpty() && sim.all<DimAmbient>().isEmpty())
    }

    // 장부로 되돌릴지는 판단 코어가 정한다. Boot에서 수명 표 적용 뒤 ign.ambient가 Dimmed가 아니면 RestoreAmbientLedger를 한 번 낸다
    @Test fun boot_ledgerCommandOnlyWhenNotDimmed() {
        // ① GAP(복원으로 낮춤을 이어 감, Dimmed): 내지 않는다
        val gap = session().dimmedAttempt().toGap()
        gap.clear().ev(Boot(gap.t, Gear.D, 10))
        assertTrue(gap.all<RestoreAmbientLedger>().isEmpty(), "Dimmed면 내지 않는다")
        assertTrue(gap.amb() is Ambient.Dimmed)
        // Restoring → Dimmed: 내지 않는다(되돌림은 판단 코어의 RestoreAmbient가 한다)
        val rs = session().dimmedAttempt().toConfirm().closeQuiet()
        rs.clear().ev(Boot(rs.t, Gear.D, 10))
        assertTrue(rs.all<RestoreAmbientLedger>().isEmpty()); assertEquals(1, rs.all<RestoreAmbient>().size)
        // ② Dimming(되돌릴 값을 잃음): 한 번 낸다
        val dimming = session().attempt1()
        dimming.ev(PlaybackEnded(dimming.t, dimming.play().id, EndReason.ERROR))
        dimming.clear().ev(Boot(dimming.t, Gear.D, 10))
        val c = dimming.all<RestoreAmbientLedger>().single()
        assertTrue(c.cmdId.isNotEmpty() && dimming.all<ControlResult>().isEmpty())
        // ② 칸 없음(장면 상태를 잃음, 또는 평소 시작): 한 번 낸다
        val none = session()
        none.clear().ev(Boot(none.t, Gear.D, 10))
        assertEquals(1, none.all<RestoreAmbientLedger>().size)
    }

    @Test fun sceneStart_restoresRealDim() {
        val sim = session().dimmedAttempt()
        val eng = Engine()
        val scene = EngineState.fresh(1, profile(), listOf(WN), emptyList(), null, 1)
        val st = eng.sceneStart(sim.s, scene, sim.t + 1_000)
        val r = st.realStep.commands.filterIsInstance<RestoreAmbient>().single()
        assertEquals(RestoreAmbient(r.cmdId, BRIGHT, COLOR), r)
        assertTrue(st.realStep.state.ign.ambient is Ambient.Restoring)
        assertNull(st.scene.ign.ambient, "장면 상태는 새로 시작한다")
        // 그 결과는 장면 상태로 가 버려지고 실제 칸은 Restoring으로 남는다(장면 끝 Boot가 Dimmed로 맞춘다)
        val inScene = eng.step(st.scene, ControlResult(sim.t + 2_000, r.cmdId, B, null, true, 20.0, 70.0))
        assertNull(inScene.state.ign.ambient)
    }

    // 장면 시작에 실제 상태가 Dimming이면 결과가 장면 상태로 가 버려진다. 칸을 비우고 RestoreAmbientLedger로 실행기 낮춤 작업을 버린다
    @Test fun sceneStartRealDimming_ledgerCommand() {
        val real = session().attempt1()
        val c = real.dimId()
        val eng = Engine()
        val scene = EngineState.fresh(1, profile(), listOf(WN), emptyList(), null, 1)
        val st = eng.sceneStart(real.s, scene, real.t + 1_000)
        assertEquals(1, st.realStep.commands.filterIsInstance<RestoreAmbientLedger>().size)
        assertTrue(st.realStep.commands.none { it is RestoreAmbient })
        assertNull(st.realStep.state.ign.ambient)
        val late = eng.step(st.scene, ControlResult(real.t + 2_000, c, B, null, true, 70.0, 20.0))   // 지난 cmdId 결과는 장면 상태에서 무시된다
        assertNull(late.state.ign.ambient)
        // (b) 칸 없음은 장면 시작마다 명령을 만들지 않는다
        val none = session()
        val noneStart = eng.sceneStart(none.s, scene, none.t + 1_000)
        assertTrue(noneStart.realStep.commands.none { it is RestoreAmbientLedger || it is RestoreAmbient })
        // (c) 실제 세션이 닫혀 Dimming만 리포트를 붙잡던 경우: 칸을 비우면 그 세션 리포트가 풀린다
        val closed = session().attempt1()
        val id = closed.ses.id
        closed.gear(Gear.P, MIN).ev(RearDoor(closed.t, Side.LEFT, true))
        assertTrue(closed.all<CloseSession>().isEmpty(), "Dimming이 리포트를 붙잡는다")
        val closedStart = eng.sceneStart(closed.s, scene, closed.t + 1_000)
        assertEquals(listOf(CloseSession(id)), closedStart.realStep.commands.filterIsInstance<CloseSession>())
        // (d) 장면 끝 Boot: 실제 칸이 null이면 RestoreAmbientLedger가 한 번 더 나온다(장부가 비어 있으면 무해하다)
        val boot = eng.step(st.realStep.state, Boot(st.realStep.state.now + 1_000, Gear.D, 10))
        assertEquals(1, boot.commands.filterIsInstance<RestoreAmbientLedger>().size)
    }

    @Test fun sceneEnd_restoresSceneDim() {
        val sim = session().dimmedAttempt()
        val end = Engine().sceneEnd(sim.s)
        val r = end.commands.filterIsInstance<RestoreAmbient>().single()
        assertEquals(RestoreAmbient(r.cmdId, BRIGHT, COLOR), r)
        assertTrue(r.cmdId.startsWith("${sim.ses.id}-"))
        val dimming = session().attempt1()
        Engine().sceneEnd(dimming.s).commands.let { c ->   // Dimming은 장부 되돌림 명령으로 실행기의 낮춤 작업을 버리게 한다
            assertEquals(1, c.filterIsInstance<RestoreAmbientLedger>().size); assertTrue(c.none { it is RestoreAmbient })
        }
        val none = session().attempt1().also { val c = it.dimId(); it.res(c, B, false, 20.0, 20.0).res(c, C, false, 1.0, 1.0) }
        Engine().sceneEnd(none.s).commands.let { c ->   // back이 비어도(확인 못 한 종류) 장부 되돌림은 낸다
            assertTrue(c.none { it is RestoreAmbient }, "되돌릴 확인된 값은 없다"); assertEquals(1, c.filterIsInstance<RestoreAmbientLedger>().size)
        }
        val restoring = session().dimmedAttempt().toConfirm().closeQuiet()   // Restoring
        assertTrue(Engine().sceneEnd(restoring.s).commands.none { it is RestoreAmbient || it is RestoreAmbientLedger }, "Restoring은 이미 낸 명령이 끝난다")
        assertTrue(Engine().sceneEnd(sim.s).commands.none { it is RestoreAmbientLedger }, "back이 완전한 Dimmed는 장부를 부르지 않는다")
    }

    @Test fun sceneEnd_unconfirmedKind_ledgerRestore() {
        val sim = session().attempt1()
        val c = sim.dimId()
        sim.res(c, B, true, 70.0, 20.0).res(c, C, false, null, null)   // 색은 확인 못 함
        val end = Engine().sceneEnd(sim.s).commands
        assertEquals(RestoreAmbient(end.filterIsInstance<RestoreAmbient>().single().cmdId, BRIGHT, null), end.filterIsInstance<RestoreAmbient>().single())
        assertTrue(end.indexOfFirst { it is RestoreAmbient } < end.indexOfFirst { it is RestoreAmbientLedger }, "되돌림 뒤에 장부")
        assertTrue(end.filterIsInstance<RestoreAmbientLedger>().single().cmdId.startsWith("${sim.ses.id}-"))
    }

    // 실제 Dimmed → 장면 시작(실제 Restoring) → 장면에서 낮춤 시작(Dimming) → 장면 끝은 장부 되돌림, 뒤이은 실제 Boot는 Restoring→Dimmed라 장부 명령 없이 Step 끝 되돌림
    @Test fun sceneDimmingAfterRealRestoring_ledgerCommandAtSceneEnd() {
        val real = session().dimmedAttempt()
        val eng = Engine()
        val start = eng.sceneStart(real.s, EngineState.fresh(1, profile(), listOf(WN), emptyList(), null, 1), real.t + 1_000)
        assertTrue(start.realStep.state.ign.ambient is Ambient.Restoring)
        assertNull(start.scene.ign.ambient, "장면 상태는 새로 시작한다")
        val sceneSim = session()           // 장면 상태 자리(장면 setup이 만든 세션): 거기서 달래기가 시작돼 낮춤이 결과 전(Dimming)이다
        sceneSim.attempt1()
        assertTrue(sceneSim.amb() is Ambient.Dimming)
        val end = eng.sceneEnd(sceneSim.s)
        assertEquals(1, end.commands.filterIsInstance<RestoreAmbientLedger>().size)
        val boot = eng.step(start.realStep.state, Boot(start.realStep.state.now + 1_000, Gear.D, 10))
        assertTrue(boot.commands.none { it is RestoreAmbientLedger }, "실제 Restoring → Dimmed라 장부 명령 없음")
        assertEquals(1, boot.commands.filterIsInstance<RestoreAmbient>().size, "Step 끝 되돌림")
    }

    @Test fun suspend_restoresBeforeSnapshot() {
        val sim = session().dimmedAttempt()
        sim.clear().ev(Suspend(sim.t + 1_000))
        val names = sim.cmds.map { it::class.simpleName }
        assertTrue(names.indexOf("ReleaseAudio") < names.indexOf("RestoreAmbient"), "$names")
        assertTrue(names.indexOf("RestoreAmbient") < names.indexOf("SaveSnapshot"), "$names")
        val snap = sim.step<SaveSnapshot>().single().state
        assertTrue(snap.ign.ambient is Ambient.Restoring, "스냅숏에 Restoring이 든다")
    }

    @Test fun noSpeechNoNotice() {
        val sim = session().attempt1()
        val c = sim.dimId()
        val steps = mutableListOf<List<Command>>()
        sim.res(c, B, false, null, null); steps += sim.cmds   // 둘 다 실패해도 알림 없음
        sim.res(c, C, false, null, null); steps += sim.cmds
        for (cmds in steps) noSpeechOrNotice(cmds)
        assertTrue(sim.all<ShowNotice>().none { it.kind == Notice.SAFETY_CONTROL_FAILED })
        val a = session().dimmedAttempt().toConfirm().closeQuiet()
        assertNotNull(a.amb())
        noSpeechOrNotice(a.step<RestoreAmbient>())
        a.restoreResults(ok = false)
        noSpeechOrNotice(a.cmds)
    }
}
