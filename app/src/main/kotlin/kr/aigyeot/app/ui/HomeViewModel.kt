package kr.aigyeot.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kr.aigyeot.app.exec.HeardText
import kr.aigyeot.app.exec.Said
import kr.aigyeot.app.host.EventSink
import kr.aigyeot.app.link.Link
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.db.OkControl
import kr.aigyeot.app.store.retryOnDbError
import kr.aigyeot.engine.AskButton
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.Step
import kr.aigyeot.shared.TimelineEpisode

/** 홈 시계 눈금(수유 '방금'·'h:mm', 오늘 하루 지금 선을 다시 계산하는 간격). */
private const val TICK_MS = 10_000L

/**
 * 홈 상태. 시계는 10초 눈금마다(마지막 수유 지난 시간), 판단 코어·연결·차가 말한 문장이 바뀔 때마다 다시 계산한다.
 * 정차 중 에피소드 카드는 세션이 있고 기어 P일 때만 Room에서 마지막 에피소드를 읽는다(리포트와 같은 변환).
 * 바꾸려면 사건을 [events](실제 입구)로 보낸다. 수유 입력은 link가 Room에 먼저 쓴다.
 */
class HomeViewModel(
    steps: StateFlow<Step?>,
    private val events: EventSink,
    private val link: Link,
    private val clock: () -> Long,
    said: StateFlow<Said?>,
    private val heard: StateFlow<HeardText?>,
    private val store: Store
) : ViewModel() {
    // 시계 글자(수유 '방금'·'h:mm', 오늘 하루 지금 선)를 다시 계산하는 눈금. 홈은 눈금의 시각만 쓴다: 판단 코어가 Step을 낼 때마다 clock()을 읽으면
    // 수유 막대 몫이 매번 달라 HomeUi가 같지 않게 되고, 주행 중 Step마다 홈 전체를 다시 그린다(장면 B·C·E 앱 CPU 2배). 눈금보다 늦은 수유 기록은 agoText·fill이 0으로 막는다
    private val ticker = flow {
        while (true) {
            emit(clock())
            delay(TICK_MS)
        }
    }
    private var lastKey: String? = null
    private var keyFrom = 0L

    /** 단계 열쇠가 바뀐 시각(차가 말한 문장을 그 단계 동안만 보인다). */
    private fun stepFrom(s: EngineState?): Long = stepKey(s).let { k ->
        if (k != lastKey) {
            lastKey = k
            keyFrom = clock() - 1_000
        }
        keyFrom
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    // 세션 id를 붙여 낸다: 세션이 바뀐 뒤 새 읽기가 값을 내기 전(또는 다시 하는 동안) 앞 세션의 카드·칩을 지금 세션에 쓰지 않게
    private val lastEpisode: Flow<Tagged<TimelineEpisode?>> =
        steps.map { st -> st?.state?.session?.takeIf { st.state.car.gear == Gear.P }?.id }.distinctUntilChanged()
            .flatMapLatest { sid ->
                if (sid == null) {
                    flowOf(Tagged(null, null))
                } // 읽기 실패는 1초 뒤 다시 구독하고 그동안 직전 카드를 둔다
                else {
                    store.reportChanges(sid).map { Tagged(sid, store.lastEpisode(sid)) }.flowOn(Dispatchers.IO).retryOnDbError("home-episode")
                }
            }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val done: Flow<Tagged<Set<OkControl>>> = steps.map { it?.state?.session?.id }.distinctUntilChanged()
        .flatMapLatest { sid -> if (sid == null) flowOf(Tagged(null, emptySet())) else store.okControlsFlow(sid).map { Tagged(sid, it.toSet()) } }
    private val askPick = AskPick()
    private fun lastQa(s: EngineState?): String? = askPick.pick(s, heard.value, clock(), store.clearedAt.value)

    init {
        // 단계 열쇠는 화면이 안 보일 때도 따라간다(다른 화면에 갔다 오는 동안 지난 단계의 문장이 지금 단계에 남지 않게)
        steps.onEach {
            stepFrom(it?.state)
            lastQa(it?.state)
        }.launchIn(viewModelScope)
    }

    /**
     * 오늘 주행 구간(시뮬레이션 뺌)과 경보 시각. 세션 표가 바뀔 때만 다시 읽는다. 날짜는 주입 시계(눈금과 같은 출처)로 정한다.
     * 이 앞 거름은 화면의 dayChart가 눈금 시각으로 다시 거르므로 넓어도 된다: 눈금 값은 지금보다 최대 [TICK_MS] 늦어, 그만큼 앞 시각의 날짜로 거른다(자정 직후에도 빠뜨리지 않게).
     */
    private val carDay: Flow<List<CarSpan>> = store.sessionsFlow().map { list ->
        val day0 = java.time.Instant.ofEpochMilli(clock() - TICK_MS).atZone(TimeText.zone).toLocalDate().atStartOfDay(TimeText.zone).toInstant().toEpochMilli()
        list.filter { it.scenarioId == null && (it.endedAt ?: Long.MAX_VALUE) >= day0 }.map { CarSpan(it.startedAt, it.endedAt, store.cryAlarms(it.id)) }
    }.flowOn(Dispatchers.IO).retryOnDbError("home-day") // 읽기 실패: 1초 뒤 다시, 그동안 직전 '오늘 하루'를 둔다('울음 없음'으로 바꾸지 않는다)

    // Room에서 읽는 덧붙임 셋은 처음 값을 먼저 낸다: 첫 읽기가 실패해 다시 하는 동안에도 판단 코어·연결 상태는 화면에 바로 간다(카드·칩·오늘 하루만 늦게)
    private val extras = combine(
        lastEpisode.onStart { emit(Tagged(null, null)) },
        done.onStart { emit(Tagged(null, emptySet())) },
        carDay.onStart { emit(emptyList()) }
    ) { a, b, c -> Triple(a, b, c) }

    // 기록 지우기 시각도 같이 본다(지운 뒤 홈에 돌아오면 옛 물어보기 카드가 바로 내려가게)
    val uiState: StateFlow<HomeUi> = combine(steps, link.status, ticker, combine(said, heard, store.clearedAt) { a, b, _ -> a to b }, extras) {
            s,
            st,
            now,
            sh,
            ex
        ->
        val sid = s?.state?.session?.id
        homeUi(
            s?.state, now, st, sh.first, sh.second, stepFrom(s?.state), ex.first.of(sid), ex.second.of(sid) ?: emptySet(), ex.third,
            lastQa = lastQa(s?.state)
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        homeUi(
            steps.value?.state,
            clock(),
            link.status.value,
            said.value,
            heard.value,
            stepFrom(steps.value?.state),
            null,
            lastQa = lastQa(steps.value?.state)
        )
    )

    fun feedingNow() {
        link.feedingFromCar(clock())
    }
    fun ask() {
        askPick.pressed(clock())
        events.post(AskButton(0))
    }
}
