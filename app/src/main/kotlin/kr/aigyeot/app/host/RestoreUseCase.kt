package kr.aigyeot.app.host

import android.util.Log
import kotlinx.coroutines.runBlocking
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.bestEffort
import kr.aigyeot.app.store.retrying
import kr.aigyeot.engine.Boot
import kr.aigyeot.engine.CloseSession
import kr.aigyeot.engine.EndReason
import kr.aigyeot.engine.Engine
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.InputLost
import kr.aigyeot.engine.MethodsChanged
import kr.aigyeot.engine.MicService
import kr.aigyeot.engine.PauseReason
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.PlaybackEnded
import kr.aigyeot.engine.ProfileChanged

private const val TAG = "host"

/**
 * 복원 ①~⑤: ① 스냅숏 읽기 ② Room 대조 ③ 복원 처리(비우기·마이크 맞추기) ④ Boot 사건 ⑤ 복원 뒤 대조. 프로세스 시작([boot])과 장면 끝([restore])이 같은 길을 탄다. 호스트 스레드에서 돈다.
 * 복원 길은 retrying 뒤에도 실패하면 던져 프로세스를 끝내고, 재개 길 대조는 실패해도 로그만 남긴다.
 * 장면 중에는 ⑤ 대조를 하지 않는다([inScene]): 실제 상태는 장면 끝 복원이 Room으로 맞춘다.
 */
internal class RestoreUseCase(
    private val runner: EngineRunner,
    private val engine: Engine,
    private val store: Store,
    private val clock: () -> Long,
    private val mic: MicPort,
    private val userId: () -> Int,
    private val targets: () -> Targets,
    private val inScene: () -> Boolean
) {
    /** 프로세스 시작의 복원(EngineRunner.start가 시작 길 fail-fast 안에서 부른다). */
    fun boot(now: Long) {
        restore(retrying("시작 스냅숏·초기 상태") { store.loadSnapshot() ?: store.initialState(seed = now) }, now, null) // ④ 기어는 exec 첫 GearChanged가 채운다
        // 리포트 재개는 부수 일이라 조회가 다 실패해도 프로세스를 끝내지 않는다(report_pending이 남아 다음 시작 때 다시)
        bestEffort("리포트 재개") { store.pendingReportIds().forEach { store.handle(CloseSession(it)) } }
        store.purgeOld()
    }

    /** 복원 ①~⑤. 프로세스 시작과 장면 끝(장면 동안 콜백이 사라진 일을 비운다)이 같은 길을 탄다. */
    fun restore(s: EngineState, now: Long, gear: Gear?, stopEnded: Boolean = false) {
        // 복원 중 예외는 상태를 만들다 만 채 감지·안내를 멈추므로 프로세스를 끝낸다(다시 뜨면 스냅숏에서 복원. 장면 끝도 실제 스냅숏은 장면 시작 때 저장돼 있다)
        try {
            restoreSteps(s, now, gear, stopEnded)
        } catch (x: Exception) {
            Log.e(TAG, "복원 실패: 프로세스를 끝낸다", x)
            runner.die(x)
            throw x
        }
    }

    private fun restoreSteps(s: EngineState, now: Long, gear: Gear?, stopEnded: Boolean) {
        store.closeScenarioSessions(now) // 실제 상태로 돌아올 때(장면 끝·프로세스 시작) 끝나지 못한 장면 세션을 닫는다. 보관 정리는 닫힌 세션만 지운다
        // reconcileRoom은 읽기만 한다(Room 쓰기 없음). 다 실패하면 던져 프로세스를 끝낸다
        runner.replace(retrying("복원 ①②") { reconcileRoom(s, now) }) // ①②
        afterRestore(now) // ③(진행 중 조향 창 버림도 판단 코어 afterRestore)
        runner.run(runner.stamp(Boot(now, gear = gear, userId = userId(), stopEnded = stopEnded))) // ④
        reconcileLink() // ⑤
    }

    /**
     * ② Room 대조(Room 우선). 상태를 고치는 규칙은 판단 코어 [Engine.restoreFromRoom] 하나다. 호스트는 Room에서 사실만 읽어 넘긴다(읽기만, 쓰기 없음).
     */
    private fun reconcileRoom(snap: EngineState, now: Long): EngineState = engine.restoreFromRoom(snap, store.roomFacts(snap, now), now)

    /** ③ 복원 처리: 재생 이중 누적 방지, GAP·CONFIRM 판정 멈춤, 판단 코어 비우기(Engine.afterRestore), 마이크 맞추기. */
    private fun afterRestore(now: Long) {
        // 멈춘 시각은 복원 시각이 아니라 ② 뒤 state.now(스냅숏의 마지막 시각, 장면 끝이면 장면 시작 시각). 그 시각 그대로 넣으려고 stamp하지 않는다
        runner.state.session?.play?.let {
            runner.run(
                PlaybackEnded(
                    runner.state.now,
                    it.id,
                    EndReason.ERROR,
                    recorded = retrying("복원 ③ 재생 기록") { store.playLogged(it.id) }
                )
            )
        }
        val running = mic.runningPauses()
        val x = runner.state.session
        // 프로세스가 새로 떴다
        if ((x?.phase == Phase.GAP || x?.phase == Phase.CONFIRM) && running == null) runner.run(runner.stamp(InputLost(now, PauseReason.MIC_ERROR)))
        // 복원 때 비우는 것(말하던 줄·질문·질문 창·장시간 줄·조향 창 버림)·경로 비우기·판정 창 다시 재기(마이크가 살아 있으면)는 판단 코어가 한다
        runner.replace(engine.afterRestore(runner.state, now, micAlive = running != null))
        if (runner.state.car.micRunning && running == null) {
            runner.run(runner.stamp(MicService(now, false, null)))
            runner.run(runner.stamp(InputLost(now, PauseReason.MIC_ERROR)))
        }
        val unresolved = runner.state.car.pauses
        // 엔진에만 있는 사유는 늘 assumePausedNow로: 서비스가 그사이 시작해도 잠금 안에서 감지기·pendingPauses로 나뉜다
        (unresolved - (running ?: emptySet())).takeIf { it.isNotEmpty() }?.let(mic.assumePausedNow)
        running?.let { r -> (r - unresolved).forEach { runner.run(runner.stamp(InputLost(now, it))) } }
    }

    /**
     * ⑤ 복원 뒤 대조: Room 값과 다르면 사건으로 맞춘다. 수유·보호자는 link 몫(Link.restoreEvents), 부모 노래 후보는 store 몫.
     * 프로필도 같은 틈이 있어 함께 본다. restoreEvents는 IO에서 돌지만 첫 판단 전에 넣어야 해 호스트 스레드가 끝날 때까지 기다린다(큐 한 스레드 그대로).
     */
    fun reconcileLink(resume: Boolean = false) {
        // 장면 중(Resume·시계 점프)에는 대조하지 않는다. 장면 상태를 건드리지 않고, 보관한 실제 상태는 장면 끝 ⑤가 맞춘다
        if (!runner.started || inScene()) return
        val now = clock()
        val base = runner.state
        val read = {
            runBlocking { targets().restore(base, now) } + listOfNotNull(
                store.methods().takeIf { it != base.methodsNext }?.let { MethodsChanged(now, it) },
                store.profile().takeIf { it != base.profile }?.let { ProfileChanged(now, it) }
            )
        }
        // 읽기가 한 번 실패해도 빈 목록으로 넘어가지 않는다. 복원 길은 다 실패하면 던져 프로세스를 끝내고,
        // 재개 길은 로그만 남기고 뒤의 Resume을 막지 않는다.
        var evs: List<Event> = emptyList()
        if (!resume) {
            evs = retrying("복원 ⑤ 대조", read)
        } else if (!bestEffort("재개 대조") { evs = read() }) {
            return
        }
        evs.forEach { runner.process(runner.stamp(it)) }
    }
}
