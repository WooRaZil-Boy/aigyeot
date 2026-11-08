package kr.aigyeot.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 세션 단계(설계 3.2, D5). 단계에만 의미 있는 값은 그 단계 안에만 있어 단계와 맞지 않는 칸 조합을 표현할 수 없다:
// 판정 창은 GAP·CONFIRM, 재생은 ATTEMPT·WAIT, 조용함 누적은 WAIT·HANDOFF, 경보 대기는 ATTEMPT에만 있다.
// 단계 칸(SessionState.stage)을 쓰는 곳은 Tx.enter 하나다(V23).

@Serializable
sealed interface Stage {
    /** 읽기 투영(앱·장면 expect.state·시험이 읽는 이름). 직렬화되지 않는다. */
    val phase: Phase
    /** 시도 번호. LISTENING은 0. WAIT·HANDOFF는 직전 자동 시도 번호(판단에는 안 쓰고 시험·장면 측정이 읽는다). */
    val n: Int
    /** 재생(ATTEMPT 자동 시도, WAIT 요청 재생). */
    val play: Play? get() = null
    /** 판정 창(GAP·CONFIRM). null이면 판정 멈춤(입력 끊김 21a·복원 14t). */
    val window: JudgeWindow? get() = null
    /** 조용함 누적(WAIT·HANDOFF, 11a). */
    val quietMs: Long get() = 0

    @Serializable @SerialName("LISTENING")
    data object Listening : Stage { override val phase get() = Phase.LISTENING; override val n get() = 0 }

    /** 자동 시도 재생(시작 대기 포함). [alarm] = 1차 시작 전 경보 시각(C36). PlaybackStarted가 에피소드로 옮긴다. */
    @Serializable @SerialName("ATTEMPT")
    data class Attempt(override val n: Int, override val play: Play, val alarm: PendingAlarm? = null) : Stage { override val phase get() = Phase.ATTEMPT }

    @Serializable @SerialName("GAP")
    data class Gap(override val n: Int, override val window: JudgeWindow?) : Stage { override val phase get() = Phase.GAP }

    @Serializable @SerialName("CONFIRM")
    data class Confirm(override val n: Int, override val window: JudgeWindow?) : Stage { override val phase get() = Phase.CONFIRM }

    /** [play]는 요청 재생만. */
    @Serializable @SerialName("WAIT")
    data class Wait(override val n: Int, override val quietMs: Long = 0, override val play: Play? = null) : Stage { override val phase get() = Phase.WAIT }

    @Serializable @SerialName("HANDOFF")
    data class Handoff(override val n: Int, override val quietMs: Long = 0) : Stage { override val phase get() = Phase.HANDOFF }
}

/** 판정 구간: start ≥ from([sees]), end ≤ end(G1·G2). */
@Serializable data class JudgeWindow(val from: Long, val end: Long)

/** 단계 칸을 쓰는 유일한 함수(V23). LISTENING이면 에피소드를 비운다(LISTENING에는 에피소드가 없다). */
internal fun Tx.enter(next: Stage) = ses { copy(stage = next, episode = if (next == Stage.Listening) null else episode) }

/**
 * 재생을 바꾼 같은 단계. 재생이 없는 단계는 그대로.
 * ATTEMPT의 재생이 끝나면(null) 판정 멈춘 GAP으로 둔다: 자동 시도 재생을 끝낸 쪽(playDone·toWait·ended·rejected·세션 닫힘)이 곧 다음 단계로 들어간다.
 */
internal fun Stage.withPlay(pl: Play?): Stage = when (this) {
    is Stage.Attempt -> if (pl == null) Stage.Gap(n, null) else copy(play = pl)
    is Stage.Wait -> copy(play = pl)
    else -> this
}

/** 판정 창을 바꾼 같은 단계(GAP·CONFIRM만). */
internal fun Stage.withWindow(w: JudgeWindow?): Stage = when (this) {
    is Stage.Gap -> copy(window = w)
    is Stage.Confirm -> copy(window = w)
    else -> this
}

/** 조용함 누적을 바꾼 같은 단계(WAIT·HANDOFF만). */
internal fun Stage.withQuiet(ms: Long): Stage = when (this) {
    is Stage.Wait -> copy(quietMs = ms)
    is Stage.Handoff -> copy(quietMs = ms)
    else -> this
}
