package kr.aigyeot.app.ui

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kr.aigyeot.app.exec.LoudnessResult
import kr.aigyeot.app.host.EventSink
import kr.aigyeot.app.host.Kit
import kr.aigyeot.app.host.T0
import kr.aigyeot.app.link.FakeCipher
import kr.aigyeot.app.link.FakeRelay
import kr.aigyeot.app.link.Link
import kr.aigyeot.engine.AskButton
import kr.aigyeot.engine.BabyAboard
import kr.aigyeot.engine.BabyAboardFrom
import kr.aigyeot.engine.ClearRecords
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FeedingConfirmed
import kr.aigyeot.engine.FeedingFrom
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.Step
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

// 화면·알림 ViewModel은 사건을 EventSink 하나로 보낸다(조립하는 곳 Host.uiSink = 실제 입구). 차 화면 수유는 link가 Room에 먼저 쓴다
@RunWith(RobolectricTestRunner::class)
class EventSinkTest {
    private val k = Kit()
    private val sent = CopyOnWriteArrayList<Event>()
    private val sink = EventSink { sent += it }
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After fun close() { k.host.close(); k.linkScope.cancel(); k.db.close() }

    private fun link(post: (Event) -> Unit) =
        Link(ctx, k.db, k.clock, post, { null }, FakeRelay(), FakeCipher, { null }, { _, _ -> LoudnessResult(true) }, k.linkScope)

    @Test fun viewModels_postThroughSink() {
        k.profile(); k.boot()
        val l = link { }
        HomeViewModel(MutableStateFlow<Step?>(null), sink, l, k.clock, MutableStateFlow(null), MutableStateFlow(null), k.store).ask()
        val app = AppViewModel(k.store, sink, ctx) { false }
        app.babyAboard(BabyAboardFrom.NOTICE)
        app.feedingConfirm(true)
        app.clearRecords()   // 아기 설정 '모든 기록 지우기'(release에도 있는 정차 화면)
        assertEquals(listOf(AskButton(0), BabyAboard(0, BabyAboardFrom.NOTICE), FeedingConfirmed(0, true), ClearRecords(0)), sent.toList())
    }

    @Test fun feedingNow_roomFirst_thenRealEventFromLink() {
        k.profile(); k.phone(); k.boot()
        val fromLink = CopyOnWriteArrayList<Event>()
        val l = link { fromLink += it }
        HomeViewModel(MutableStateFlow<Step?>(null), sink, l, k.clock, MutableStateFlow(null), MutableStateFlow(null), k.store).feedingNow()
        val end = System.currentTimeMillis() + 3_000
        while (fromLink.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(listOf<Event>(FeedingStarted(T0, T0, FeedingFrom.CAR_SCREEN, null)), fromLink.toList(), "link의 실제 사건 길로 낸다")
        assertEquals(T0, k.db.storeDao().profile()!!.lastFeedingStart, "Room에 먼저 쓴다")
        assertEquals(1, k.db.linkDao().outbox().count { it.type == "feeding_state" }, "feeding_state를 보낼 함에 넣는다")
        assertEquals(emptyList(), sent.toList(), "화면이 판단 코어 사건을 직접 넣지 않는다")
    }
}
