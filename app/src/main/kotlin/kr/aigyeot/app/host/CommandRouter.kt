package kr.aigyeot.app.host

import android.util.Log
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.retrying
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.CaptureContext
import kr.aigyeot.engine.CarView
import kr.aigyeot.engine.CloseSession
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.DeleteRecords
import kr.aigyeot.engine.DeleteRelayRecords
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.LogAlarm
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.SaveEpisode
import kr.aigyeot.engine.SaveFeedingStart
import kr.aigyeot.engine.SavePlay
import kr.aigyeot.engine.SaveSession
import kr.aigyeot.engine.SaveSnapshot
import kr.aigyeot.engine.SaveSteerWindow
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.SetWindowLock
import kr.aigyeot.engine.SettleHandoff
import kr.aigyeot.engine.HideNotice
import kr.aigyeot.engine.ShowNotice
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.Stage
import kr.aigyeot.engine.StartPlayback
import kr.aigyeot.engine.SteerState
import kr.aigyeot.engine.Step
import kr.aigyeot.engine.StopPlayback

private const val TAG = "host"

/**
 * 할 일 배달(계약 4장 '받는 곳')과 스냅숏 저장(C78). 호스트 스레드에서만 부른다. store 몫은 이 스레드에서 바로 쓴다(쓰기 줄 하나).
 * 묶음 트랜잭션(C160·C173·C207)·필수 저장 실패의 프로세스 끝내기([fatal], 범주 6.0 T)·바로/묶음 저장 판단을 한 곳에서 한다.
 */
internal class CommandRouter(
    private val store: Store,
    private val clock: () -> Long,
    private val p: Params,
    private val targets: () -> Targets,
    private val inScene: () -> Boolean,
    private val fatal: (Throwable) -> Unit,
) {
    private var lastSnapshotAt = 0L                    // 0이면 첫 변화에서 저장(MIN_VALUE는 빼기에서 넘친다)
    private var dirty = false                          // 묶음 저장을 건너뛴 변경이 있다(C78)

    /** 할 일을 순서대로 '받는 곳'에 넘긴다(Wiring.route). store 몫은 이 스레드에서 바로 쓴다. */
    fun dispatch(old: EngineState, step: Step) {
        val cmds = step.commands
        val clearing = DeleteRecords in cmds
        var saved = false
        // C160·C207: 인계 알림의 보낼 함 넣기, 그 Step의 store 쓰기, 그 Step의 스냅숏(바로 저장이면)을 한 Room 트랜잭션으로. 묶음의 첫 할 일 자리에서 한 번에 한다
        val alerts = cmds.filterIsInstance<SendToPhone>().filter { it.type == "handoff_alert" }
        val writes0 = cmds.filter { it is SaveSession || it is SaveEpisode || it is SettleHandoff || it is SavePlay || it is LogAlarm || it is SaveSteerWindow || (it is SaveFeedingStart && !inScene()) }
        val tied = if (alerts.isEmpty() && (clearing || writes0.isEmpty())) emptyList() else alerts + writes0 + cmds.filter { it is SaveSnapshot && !clearing && !inScene() }
        var tiedDone = false
        var snapDecided = false
        fun commitTied() {
            tiedDone = true
            snapDecided = true
            val snap = tied.filterIsInstance<SaveSnapshot>().lastOrNull() ?: if (!inScene() && !clearing && needsSnapshot(old, step)) SaveSnapshot(step.state) else null
            val writes = tied.filter { it !is SendToPhone && it !is SaveSnapshot } + listOfNotNull(snap)
            // C173: 묶음이 실패하면 예외를 다시 던져 프로세스를 끝낸다(나머지 할 일·스냅숏을 실행하지 않는다). 다시 켜지면 앞 스냅숏 복원(C151·C152·C159)
            // 결정 ③: 인계 묶음은 store 쓰기 먼저, 보낼 함 넣기 나중(같은 Step에서 생긴 에피소드 행에도 link가 guardian_notified를 붙인다)
            if (alerts.isEmpty()) mustSave("Step 저장 묶음 트랜잭션") { store.applyAllTx(writes) }
            else mustSave("인계 묶음 트랜잭션") { targets().linkNow(alerts) { store.applyAll(writes) } }
            if (snap != null) { lastSnapshotAt = clock(); dirty = false; saved = true }
        }
        // C188: 이 Step이 speaking을 바꾸면 Speak·Ask를 넘기기 전에 스냅숏을 저장한다(그 사이 죽어도 복원에 말하지 않은 질문이 남지 않게)
        val speechChanged = old.speech.speaking != step.state.speech.speaking && !inScene() && !clearing
        var preSaved = false
        for (c in cmds) {
            try {
                if (speechChanged && !preSaved && (c is Speak || c is Ask)) {
                    preSaved = true
                    if (tied.isNotEmpty() && !tiedDone) commitTied()   // 인계 묶음이 있으면 C173 트랜잭션(알림·에피소드·스냅숏)을 먼저 한다
                    if (!saved) {   // C192: 저장 실패도 C173처럼 그 Step을 끊고 프로세스를 끝낸다(말하지 않은 speaking을 남기고 이어 가지 않는다)
                        snapshot(cmds.filterIsInstance<SaveSnapshot>().lastOrNull()?.state ?: step.state)   // 실패하면 snapshot()이 die(C192·C222)
                        saved = true
                    }
                }
                if (c in tied) {
                    if (!tiedDone) commitTied()
                    continue
                }
                when (route(c)) {
                    To.EXEC -> targets().exec(c)
                    To.LINK -> targets().link(c)
                    To.UI -> deliverUi(c)
                    To.STORE -> when {
                        // 5.9·C90: 같은 Step의 DeleteRecords·SaveSnapshot·DeleteRelayRecords를 한 트랜잭션으로
                        c is DeleteRecords -> { mustSave("기록 지우기 트랜잭션") { store.clearRecords(cmds.filterIsInstance<SaveSnapshot>().lastOrNull()?.state ?: step.state) }; lastSnapshotAt = clock(); dirty = false; saved = true }
                        c is DeleteRelayRecords -> {}   // 중계 삭제는 store.clearRecords가 보낼 함에 넣었다
                        c is SaveSnapshot -> if (!clearing) { if (!inScene()) snapshot(c.state); saved = true }
                        // C299: 리포트 만들기(CloseSession)도 같다. 세션 종료 저장이 커밋된 뒤 따로 하고 실패하면 report_pending가 남아 다음 시작에 재개된다(purgeOld는 store.handle 안)
                        // C297: 맥락 기록은 부수 일이라 묶음 밖이다. 에피소드 행이 커밋된 뒤 쓰고, 실패해도 바깥 catch가 로그만 남긴다(C274)
                        c is CaptureContext || c is CloseSession -> { if (tied.isNotEmpty() && !tiedDone) commitTied(); store.handle(c) }
                        c is SaveFeedingStart && inScene() -> {}   // 장면은 실제 마지막 수유를 바꾸지 않는다
                        else -> store.handle(c)
                    }
                }
            } catch (x: Exception) { Log.e(TAG, "할 일 실패: $c", x) }
        }
        // C78: 그 Step의 저장 뒤 같은 쓰기 줄에 스냅숏. 장면 중에는 실제 스냅숏을 덮지 않는다
        if (!saved && !snapDecided && !inScene() && needsSnapshot(old, step)) snapshot(step.state)
    }

    private fun deliverUi(c: Command) = if (c is HideNotice) targets().hideNotice(c.kind) else targets().notice((c as ShowNotice).kind)

    /** 장면 끝처럼 Step 밖에서 나온 할 일(실행기·ui 몫만)을 [route]대로 보낸다. 저장·link 몫은 이 길로 오지 않는다(C349). */
    fun sendLoose(cmds: List<Command>) = cmds.forEach { c ->
        when (route(c)) {
            To.EXEC -> targets().exec(c)
            To.UI -> deliverUi(c)
            else -> Log.w(TAG, "Step 밖 할 일 무시: $c")
        }
    }

    /** C173: 인계 묶음 트랜잭션 실패. Exception이 아니라 Error라 할 일별 catch에 잡히지 않고 그 Step을 끊는다. */
    private class TiedTxFailure(cause: Throwable) : Error(cause)

    /** C302 T: 필수 저장은 [retrying] 뒤에도 실패하면 C173처럼 그 Step을 끊고 프로세스를 끝낸다. */
    private fun <T> mustSave(what: String, block: () -> T): T =
        try { retrying(what, block) } catch (x: Exception) { Log.e(TAG, "$what 실패: 프로세스를 끝낸다", x); fatal(TiedTxFailure(x)); throw TiedTxFailure(x) }

    /** 단독 스냅숏 저장. C222: 실패는 C173처럼 프로세스를 끝낸다(예외가 scheduleTick까지 빠져 예약이 끊긴 채 이어 가지 않는다). */
    fun snapshot(s: EngineState) {
        mustSave("스냅숏 저장") { store.saveSnapshot(s) }
        lastSnapshotAt = clock(); dirty = false
    }

    /** C78 (a) 바로 저장 / (b) 30초 묶음 / Tick으로 now만 바뀐 Step은 저장하지 않음. */
    fun needsSnapshot(old: EngineState, step: Step): Boolean {
        val n = step.state
        if (step.commands.any { it::class in IMMEDIATE }) return true
        if (snapshotKey(old) != snapshotKey(n)) return true
        if (old.copy(now = n.now) != n) dirty = true   // 건너뛴 변경은 그 뒤 Tick만 와도 30초 안에 저장한다
        return dirty && clock() - lastSnapshotAt >= p.snapshotIntervalMs
    }

    /** 건너뛴 변경을 묶어 저장할 시각(그 Tick을 예약한다). 없으면 null. 장면 중에는 실제 스냅숏을 쓰지 않는다. */
    fun batchDueAt(): Long? = if (dirty && !inScene()) lastSnapshotAt + p.snapshotIntervalMs else null

    private companion object {
        val IMMEDIATE = setOf(
            SaveSession::class, SaveEpisode::class, SettleHandoff::class, SavePlay::class, SaveSteerWindow::class, SaveFeedingStart::class,
            LogAlarm::class, CloseSession::class, DeleteRecords::class, SetWindowLock::class, StartPlayback::class, StopPlayback::class, Speak::class, Ask::class,   // C123: 말하기 시작
        )
    }
}

private val NO_STEER = SteerState(null, null, null, null, 0, null)
private val NO_CAR = CarView(null, null, emptySet(), false, false, null, null, null, emptyMap())

/**
 * C78 (a)·V14: '바로 저장' 열쇠 = 상태 전체에서 잦게 바뀌는 칸만 뺀 값. 저장할 칸의 손 목록이 아니라 뺄 칸만 적으므로 새 칸은 저절로 바로 저장 쪽이다.
 * 빼는 칸(묶어 저장, 최대 Params.snapshotIntervalMs 뒤처짐): `now`, `steer`(10Hz 조향 누적), `car`의 차량 표본(끊김 사유 `pauses`만 남김),
 * `ign.doorOpenedSinceP`(뒷문), WAIT·HANDOFF 조용함 누적 `quietMs`(사건마다 는다).
 */
internal fun snapshotKey(s: EngineState): EngineState = s.copy(
    now = 0, steer = NO_STEER, car = NO_CAR.copy(pauses = s.car.pauses), ign = s.ign.copy(doorOpenedSinceP = false),
    session = s.session?.let { x -> x.copy(stage = x.stage.let { st -> if (st is Stage.Wait) st.copy(quietMs = 0) else if (st is Stage.Handoff) st.copy(quietMs = 0) else st }) },
)
