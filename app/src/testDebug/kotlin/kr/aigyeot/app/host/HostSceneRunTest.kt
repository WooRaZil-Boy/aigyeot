package kr.aigyeot.app.host

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kr.aigyeot.app.scenario.FakeWorld
import kr.aigyeot.app.scenario.SceneGate
import kr.aigyeot.app.scenario.SceneRun
import kr.aigyeot.app.scenario.parseScene
import kr.aigyeot.engine.CabinTemp
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.Step
import kr.aigyeot.engine.Tick
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 장면 재생기(debug 빌드 전용)와 호스트가 만나는 시험. 장면 타입을 쓰지 않는 호스트 시험은 HostKit.kt의 HostTestBase를 쓰는 Host*Test에 있다.
@RunWith(RobolectricTestRunner::class)
class HostSceneRunTest {
    private val k = Kit()

    @After fun close() { k.host.close(); k.linkScope.cancel(); k.db.close() }

    private fun collect(on: CoroutineDispatcher = Dispatchers.Unconfined, f: (Step) -> Unit) =
        CoroutineScope(on).also { it.launch(start = CoroutineStart.UNDISPATCHED) { k.host.stepLog.collect(f) } }

    private fun setup(override: Set<String> = emptySet(), sibling: Boolean = false) = ScenarioSetup(
        "A", 7, Profile("2026-03", CarSeat.LEFT, null, sibling), listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)),
        emptyList(), null, 0, override,
    )

    @Test fun stepLog_overflow_countedAndFailsSceneRun() {
        k.profile(); k.boot()
        val scene = parseScene("""{"format":1,"id":"T","title":"t","repeat":1,"setup":{"profile":{"birthYm":"2026-02","carseat":"RIGHT","feedingIntervalMin":null,"hasSibling":false},
                    "methods":["WHITE_NOISE"],"history":[],"seed":1,"override":[],"otherGuardians":0},
            "steps":[{"t":1,"tap":"BABY_ABOARD"}]}""")
        val run = SceneRun(scene, 0, k.host, FakeWorld(), k.now)   // 반복 시작: 지금 유실 수를 기억한다
        val gate = CountDownLatch(1)
        val scope = collect(Dispatchers.Default) { gate.await() }   // 멈춘 수집자: 버퍼(4096)를 넘기면 버려진다
        repeat(4_200) { k.host.post(CabinTemp(0, it.toDouble())) }
        k.host.awaitIdle()
        val lost = k.host.stepLogDrops.value
        gate.countDown(); scope.cancel()
        assertTrue(lost > 0, "버퍼가 넘친 수를 센다")
        run.checkStepLog()
        val row = run.row()
        assertEquals(false, row.completed, "Step이 버려진 반복은 통과로 남지 않는다")
        assertTrue(row.failures.any { "stepLog 유실 $lost" in it }, row.failures.toString())
    }

    @Test fun startScenario_inheritsRealCar_startStepInStepLog() {
        k.profile(); k.boot(); k.open()   // 실제: 마이크 켜짐, 뒷문 닫힘, 기어 D, 세션 열림
        val real = k.state()
        val gate = SceneGate(7)
        val got = CopyOnWriteArrayList<Step>()
        val scope = collect { if (gate.accept(it)) got += it }
        k.host.post(CabinTemp(0, 21.0))   // startScenario보다 먼저 큐에 든 실제 사건
        k.host.startScenario(setup()); k.host.awaitIdle()
        k.ev(Tick(0), k.now + 1_000)
        scope.cancel()
        val first = got.first()
        assertTrue(first.commands.isEmpty() && first.state.session == null, "장면 첫 Step부터 받는다")
        assertTrue(got.none { it.state.session?.id == real.session!!.id }, "그 앞 실제 Step은 버린다")
        assertEquals(Gear.D, first.state.car.gear, "실제 기어를 이어받는다")
        assertTrue(first.state.car.micRunning, "실제 마이크 상태")
        assertEquals(emptySet(), first.state.car.rearDoorOpen, "실제 뒷문(닫힘)")
        assertEquals(real.ign.driverUserId, first.state.ign.driverUserId)
        assertEquals(2, got.size, "시작 Step과 그 뒤 Tick")
    }
}
