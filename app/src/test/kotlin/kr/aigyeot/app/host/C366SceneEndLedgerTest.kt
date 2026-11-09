package kr.aigyeot.app.host

import kr.aigyeot.engine.*
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// C366 2회차: 장면 끝에 장면 상태가 Dimming(낮춤 결과 전)이면 판단 코어가 RestoreAmbientLedger를 내고, 호스트가 그것을 실행기로 보낸다(sendLoose → EXEC)
@RunWith(RobolectricTestRunner::class)
class C366SceneEndLedgerTest {
    private val k = Kit()
    @After fun close() { k.host.close(); k.db.close(); k.linkScope.cancel() }

    // C367: 장면 시작에 실제 상태가 Dimming이면 realStep의 RestoreAmbientLedger가 실행기로 가고, 장면의 낮춤보다 먼저 간다(SceneController.start가 realStep을 먼저 dispatch)
    @Test fun c367_sceneStartWhileRealDimming_ledgerCommandGoesToExecFirst() {
        k.profile(); k.phone(); k.boot()
        k.open(); k.alarm()   // 실제 자동 시도가 시작되어 DimAmbient가 나가고 결과는 아직 없다
        assertTrue(k.state().ign.ambient is Ambient.Dimming, "실제 상태가 Dimming")
        k.exec.clear()
        k.host.startScenario(ScenarioSetup(
            "A", 7, Profile("2026-03", CarSeat.LEFT, null, false), listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)),
            emptyList(), null, 0, emptySet(),
        )); k.host.awaitIdle()
        val ledger = k.exec.indexOfFirst { it is RestoreAmbientLedger }
        assertTrue(ledger >= 0, "실행기로 간 명령: ${k.exec.map { it::class.simpleName }}")
        assertEquals(1, k.exec.filterIsInstance<RestoreAmbientLedger>().size)
        assertTrue(k.exec.withIndex().none { (i, c) -> c is DimAmbient && i < ledger }, "장면 낮춤보다 먼저")
    }

    @Test fun c366_sceneEndWhileSceneDimming_ledgerCommandGoesToExec() {
        k.profile(); k.phone(); k.boot()
        k.host.startScenario(ScenarioSetup(
            "A", 7, Profile("2026-03", CarSeat.LEFT, null, false), listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)),
            emptyList(), null, 0, emptySet(),
        )); k.host.awaitIdle()
        k.open(); k.alarm()   // 장면의 자동 시도가 시작되어 DimAmbient가 나가고 결과는 아직 없다
        assertTrue(k.state().ign.ambient is Ambient.Dimming, "장면 상태가 Dimming")
        k.exec.clear()
        k.host.endScenario(); k.host.awaitIdle()
        // 둘: 장면 끝(장면 Dimming, SceneEnd.commands → sendLoose → 실행기)과 그 뒤 실제 상태 복원 Boot(실제 칸 없음)
        assertEquals(2, k.exec.filterIsInstance<RestoreAmbientLedger>().size, "실행기로 간 명령: ${k.exec.map { it::class.simpleName }}")
        assertTrue(k.exec.indexOfFirst { it is RestoreAmbientLedger } < k.exec.lastIndexOf(k.exec.last { it is RestoreAmbientLedger }), "장면 끝 명령이 먼저")
    }
}
