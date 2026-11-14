package kr.aigyeot.app.ui

import java.time.ZoneId
import kotlin.test.Test
import kr.aigyeot.app.link.LinkStatus
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.EpisodeState
import kr.aigyeot.engine.EpisodeTrigger
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.JudgeWindow
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.OpenTrigger
import kr.aigyeot.engine.Play
import kr.aigyeot.engine.PlayMode
import kr.aigyeot.engine.PlayPurpose
import kr.aigyeot.engine.SessionState
import kr.aigyeot.engine.Stage
import kr.aigyeot.engine.TryOutcome
import kr.aigyeot.engine.TryRecord
import kr.aigyeot.engine.fresh

private const val T0 = 1_800_000_000_000L

/** 홈 화면 값(상태 글·눈썹·카드·큰 기호)이 대표 상태마다 어떻게 나오는지 글 파일 하나로 고정한다. */
class HomeUiGoldenTest {
    private val lullaby = Method(MethodKind.LULLABY)
    private val noise = Method(MethodKind.WHITE_NOISE)
    private fun fresh() = EngineState.fresh(1, null, emptyList(), emptyList(), null, 0)
    private fun play(n: Int, started: Long?, m: Method = lullaby) =
        Play("p$n", if (n == 1) PlayPurpose.AUTO_FIRST else PlayPurpose.AUTO_SECOND, m, started, (started ?: T0) + 56_000, PlayMode.NORMAL)
    private fun ep(first: TryRecord? = null, second: TryRecord? = null) =
        EpisodeState("E1", EpisodeTrigger.ALARM, T0, T0, 2_400, 0, first, second, false, null, emptyList(), 0, false, false)
    private fun driving(stage: Stage, episode: EpisodeState?): EngineState {
        val session = SessionState(
            id = "S1", openedAt = T0, trigger = OpenTrigger.DOOR_THEN_D, methods = listOf(lullaby), stage = stage, episode = episode, playedMs = 0,
            s17Said = false, micNoticeArmed = false, recordsCleared = false
        )
        return fresh().let { it.copy(session = session, car = it.car.copy(gear = Gear.D, micRunning = true)) }
    }
    private fun noSession(gear: Gear?, mic: Boolean = true) = fresh().let { it.copy(car = it.car.copy(gear = gear, micRunning = mic)) }

    private fun dump(label: String, s: EngineState): String {
        val u = homeUi(s, T0, LinkStatus(), null, null, 0, null, emptySet(), emptyList(), ZoneId.of("Asia/Seoul"), null)
        return listOf(
            "[$label]",
            "상태: ${u.status}",
            "눈썹: ${u.eyebrow}",
            "부가: ${u.sub}",
            "카드: ${u.card}",
            "단추: ${u.cta}",
            "큰 기호: ${u.drive}",
            "무대: ${u.stage}"
        ).joinToString("\n")
    }

    @Test fun homeTexts_matchGolden() {
        val fail = TryOutcome.Fail
        val states = listOf(
            "출발 전, 듣기 꺼짐" to fresh(),
            "출발 전, 듣기 켜짐" to noSession(Gear.P),
            "주행 중, 세션 없음" to noSession(Gear.D),
            "울음 감지, 재생 전" to driving(Stage.Attempt(1, play(1, null)), ep()),
            "1차 재생 중" to driving(Stage.Attempt(1, play(1, T0)), ep()),
            "듣기 틈" to driving(Stage.Gap(1, JudgeWindow(T0, T0 + 8_000)), ep(TryRecord(lullaby, null))),
            "2차 재생 중" to driving(Stage.Attempt(2, play(2, T0, noise)), ep(TryRecord(lullaby, null, fail))),
            "확인 중" to driving(Stage.Confirm(2, JudgeWindow(T0, T0 + 8_000)), ep(TryRecord(lullaby, null, fail), TryRecord(noise, null))),
            "사람에게 넘김" to driving(Stage.Handoff(2), ep(TryRecord(lullaby, null, fail), TryRecord(noise, null, fail)))
        )
        assertGolden("home_ui.txt", states.joinToString("\n\n") { (label, s) -> dump(label, s) })
    }
}
