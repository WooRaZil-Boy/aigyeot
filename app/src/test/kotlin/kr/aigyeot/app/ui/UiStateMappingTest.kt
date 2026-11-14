package kr.aigyeot.app.ui

import kr.aigyeot.app.link.LinkStatus
import kr.aigyeot.app.link.LinkedPhone
import kr.aigyeot.app.link.db.SongSelectionEntity
import kr.aigyeot.app.store.db.SessionEntity
import kr.aigyeot.app.store.db.SongEntity
import kr.aigyeot.engine.AskButton
import kr.aigyeot.engine.Engine
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.OpenTrigger
import kr.aigyeot.engine.SessionState
import kr.aigyeot.engine.Stage
import kr.aigyeot.engine.fresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val T0 = 1_800_000_000_000L

// 화면이 하던 계산을 옮긴 순수 매핑 함수. 기대값은 옮기기 전과 같다.
class UiStateMappingTest {
    private fun fresh() = EngineState.fresh(1, null, emptyList(), emptyList(), null, 0)

    /** 홈 화면 물어보기 버튼은 판단 코어가 AskButton을 받는 조건(canAsk) 그대로. 말하는 중·통화 중에는 켜지 않는다(눌러도 버려지지 않게). */
    @Test fun askEnabledFromEngine() {
        val f = fresh()
        val parked = f.copy(car = f.car.copy(gear = Gear.P, micRunning = true))
        assertTrue(askEnabled(parked))
        assertFalse(askEnabled(parked.copy(speech = parked.speech.copy(speaking = "u1"))), "말하는 중이면 판단 코어가 버린다")
        assertFalse(askEnabled(parked.copy(car = parked.car.copy(inCall = true))), "통화 중")
        assertFalse(askEnabled(parked.copy(ign = parked.ign.copy(suspendedAt = T0))), "Suspend 중")
        assertFalse(askEnabled(null))
        val asked = Engine().step(parked, AskButton(T0)).state
        assertEquals(askEnabled(parked), asked.speech.qa != null, "켜진 버튼은 판단 코어가 듣기를 연다")
    }

    @Test fun songRows_markThirdAndSelected() {
        fun song(id: String, name: String?, dur: Double?) = SongEntity(id, name, null, "m4a", dur, null, null, "ACTIVE", null, 1, 1)
        val rows = songRows(listOf(song("a", "자장", 61.7), song("b", null, null)), SongSelectionEntity("b", 1), listOf(Method(MethodKind.SONG, "a")))
        assertEquals(listOf("자장 · 61초 · 실험에 쓰는 노래", "이름 없는 노래"), rows.map { it.label })
        assertEquals(listOf(false, true), rows.map { it.selected })
        assertEquals(listOf(false, false), songRows(listOf(song("a", "x", null), song("b", "y", null)), null, emptyList()).map { it.selected })
    }

    @Test fun reportRows_openSessionDisabled_simulationMarked() {   // 한 줄 글자가 제목(날짜·구간)과 부가로 나뉘었다. 자세한 문구는 ReportViewTest
        fun s(id: String, end: Long?, scene: String?) = SessionEntity(id, T0, end, "T", null, null, 0, 0, false, "[]", null, null, false, scene, 10)
        val rows = reportRows(listOf(s("a", null, null), s("b", T0 + 60_000, "sc")))
        assertEquals(listOf(false, true), rows.map { it.enabled })
        assertEquals("주행 중", rows[0].sub)
        assertTrue(rows[1].title.contains("–") && rows[1].sub.endsWith("연습 장면"))
        assertEquals(listOf("a", "b"), rows.map { it.id })
    }

    @Test fun connectUiState_remainingClampedAtZero_andPhoneLabel() {
        assertNull(connectUiState(LinkStatus(), T0).remainingS)
        assertEquals(90L, connectUiState(LinkStatus(pairExpiresAt = T0 + 90_000), T0).remainingS)
        assertEquals(0L, connectUiState(LinkStatus(pairExpiresAt = T0 - 5_000), T0).remainingS)
        assertEquals("남은 시간 1분 30초", remainingText(90))
        assertEquals("함께 타는 보호자" to null, phoneLines(LinkedPhone("d", "owner", null)))
        assertEquals("다른 보호자", phoneLines(LinkedPhone("d", "guardian", T0)).first)
        assertTrue(phoneLines(LinkedPhone("d", "guardian", T0)).second!!.startsWith("마지막 연락 "))
    }

    /** HANDOFF 홈 상태는 안내 여부를 말하지 않는다(쉼터 안내 없이도 들어간다). */
    @Test fun handoffStatusText_doesNotClaimGuidance() {
        val f = fresh()
        val ses = SessionState(
            id = "S1", openedAt = T0, trigger = OpenTrigger.DOOR_THEN_D, methods = emptyList(), stage = Stage.Handoff(1),
            episode = null, playedMs = 0, s17Said = false, micNoticeArmed = false, recordsCleared = false,
        )
        assertEquals("자동 달래기를 멈췄어요", statusText(f.copy(session = ses)))
    }

    @Test fun route_keysRoundTrip() {
        for (r in listOf(Route.Home, Route.Setup, Route.Connect, Route.Songs, Route.Reports, Route.Report("S1"), Route.Demo, Route.DemoScenes)) assertEquals(r, Route.parse(r.key))
        assertEquals(Route.Home, Route.parse("모르는 값"))
    }

    // 화면을 옮기면 갈 화면과 그 위 화면(parentOf 사슬) 밖의 입력은 버린다. 저장하지 않고 나간 설정 입력이 다시 열 때 남지 않는다
    @Test fun route_move_dropsLeftScreenInput_keepsParentWhileInChild() {
        val home = Route.Home.key; val setup = Route.Setup.key; val songs = Route.Songs.key; val demo = Route.Demo.key
        assertEquals(setOf(setup), keysToDrop(setOf(home, setup), Route.Home), "설정에서 저장하지 않고 홈으로(뒤로·닫기): 설정 입력을 버린다")
        assertEquals(emptySet(), keysToDrop(setOf(home, setup), Route.Songs), "설정 → 노래: 설정 입력은 남아 돌아오면 이어 쓴다")
        assertEquals(setOf(songs), keysToDrop(setOf(home, setup, songs), Route.Setup), "노래 → 설정: 노래 화면 상태만 버린다")
        assertEquals(setOf(setup, demo), keysToDrop(setOf(home, setup, demo), Route.Home), "시연에서 홈으로: 위 화면(설정) 입력까지 버린다")
        // 주행 중 화면 전환은 route를 바꾸지 않는다: 지금 화면으로 '옮기면' 아무것도 버리지 않는다
        assertEquals(emptySet(), keysToDrop(setOf(home, setup), Route.Setup))
    }

    // 리포트 목록과 리포트 하나는 한 화면 열쇠다. 넓은 창에서 다른 리포트를 골라도, 840 이하에서 상세 → 목록으로 돌아와도 목록 입력(스크롤)을 버리지 않는다
    @Test fun route_reportsShareScreenKey_noDropBetweenReports() {
        assertEquals(Route.Reports.screen, Route.Report("A").screen); assertEquals(Route.Report("A").screen, Route.Report("B").screen)
        val seen = setOf(Route.Home.screen, Route.Report("A").screen)
        assertEquals(emptySet(), keysToDrop(seen, Route.Report("B")), "넓은 창에서 A → B")
        assertEquals(emptySet(), keysToDrop(seen, Route.Reports), "840 이하 상세 → 목록(뒤로)")
        assertEquals(setOf(Route.Reports.screen), keysToDrop(seen, Route.Home), "리포트를 떠나면 버린다")
        assertEquals(setOf(Route.Setup.screen), keysToDrop(setOf(Route.Home.screen, Route.Setup.screen), Route.Home), "설정 → 홈은 여전히 버린다")
    }
}
