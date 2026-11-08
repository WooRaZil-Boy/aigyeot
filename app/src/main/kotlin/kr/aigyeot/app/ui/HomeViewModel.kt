package kr.aigyeot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kr.aigyeot.app.host.EventSink
import kr.aigyeot.app.link.Link
import kr.aigyeot.app.link.LinkStatus
import kr.aigyeot.engine.AskButton
import kr.aigyeot.engine.DEFAULT_METHODS
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.FirstTry
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.Step
import kr.aigyeot.engine.canAsk
import kr.aigyeot.engine.methodLabel
import kr.aigyeot.engine.methodStats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 홈이 그리는 모든 것. 판단 코어 상태·연결 상태·시계에서 [homeUiState]가 한 번에 계산한다. */
data class HomeUiState(
    val status: String,
    val micText: String,
    val pausesWarning: Boolean,
    val feeding: String,
    val link: LinkStatus,
    val confirmShown: Boolean,
    val askEnabled: Boolean,
    val stats: List<String>,
)

fun homeUiState(s: EngineState?, now: Long, link: LinkStatus) = HomeUiState(
    status = statusText(s),
    micText = if (s?.car?.micRunning == true) "듣기 켜짐" else "듣기 꺼짐",
    pausesWarning = s?.session != null && s.car.pauses.isNotEmpty(),
    feeding = feedingText(s, now),
    link = link,
    confirmShown = s?.ign?.nursingStop?.confirmShown == true,
    askEnabled = askEnabled(s),
    stats = stats(s?.history.orEmpty(), s?.methodsNext.orEmpty(), now),
)

/** 홈 상태(30초마다 시계가 갱신). 바꾸려면 사건을 [events](실제 입구, V3)로 보낸다. 수유 입력은 link가 Room에 먼저 쓴다(C322). */
class HomeViewModel(steps: StateFlow<Step?>, private val events: EventSink, private val link: Link, private val clock: () -> Long) : ViewModel() {
    private val ticker = flow { while (true) { emit(clock()); delay(30_000) } }

    val uiState: StateFlow<HomeUiState> = combine(steps, link.status, ticker) { s, st, now -> homeUiState(s?.state, now, st) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), homeUiState(steps.value?.state, clock(), link.status.value))

    fun feedingNow() { link.feedingFromCar(clock()) }
    fun ask() = events.post(AskButton(0))
}

internal fun statusText(s: EngineState?): String {
    val x = s?.session ?: return if (s?.ign?.reserved == true) "출발하면 듣기 시작해요" else "출발 전이에요"
    return when (x.phase) {
        Phase.LISTENING -> "아기 소리를 듣고 있어요"
        Phase.ATTEMPT -> "뒷자리에서 소리를 틀고 있어요"
        Phase.GAP, Phase.CONFIRM -> "그쳤는지 보고 있어요"
        // C356: HANDOFF는 쉼터 안내 없이도 들어간다(P 정차·10분 금지·이미 넣은 경유지·쉼터 없음). 단계가 보장하는 사실만 쓴다
        Phase.HANDOFF -> "자동 달래기를 멈췄어요"
        Phase.WAIT -> "잠시 기다리고 있어요"
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

internal fun feedingText(s: EngineState?, now: Long): String {
    val f = s?.feedingStart ?: return "마지막 수유: 기록 없음"
    val min = ((now - f) / 60_000).coerceAtLeast(0)
    return "마지막 수유 ${Instant.ofEpochMilli(f).atZone(ZoneId.systemDefault()).format(HM)} (${min / 60}시간 ${min % 60}분 전)"
}

/** C303 ⑤(B7): 물어보기 버튼은 판단 코어가 AskButton을 받을 때만 켠다(말하는 중·통화·질문 창 등에서 눌러도 버려지지 않게). */
internal fun askEnabled(s: EngineState?): Boolean = s?.canAsk() == true

/**
 * "자장가 7번 중 6번". 최근 28일 1차 시도만 센다. 3번 미만이면 "아직 모름"(명세서 5장).
 * 부모 노래는 지금 후보 ③과 같은 곡의 시도만 센다(곡이 바뀌면 새로 센다). ③이 없으면 줄을 뺀다.
 * C303 A18~A20: 창·최소 시도·기본 음원·이름은 판단 코어(Params·DEFAULT_METHODS·methodLabel) 그대로.
 */
internal fun stats(h: List<FirstTry>, methods: List<Method>, now: Long, p: Params = Params()): List<String> {
    val third = methods.firstOrNull { it.kind == MethodKind.SONG }
    return methodStats(h, DEFAULT_METHODS + listOfNotNull(third), now, p).map { (m, t) ->   // C320 V17: 28일 창은 판단 코어 한 곳
        "${methodLabel(m.kind)}: " + if (t.second < p.statMinShown) "아직 모름" else "${t.second}번 중 ${t.first}번"
    }
}
