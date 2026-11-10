package kr.aigyeot.app.ui

import android.os.Looper
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kr.aigyeot.app.host.EventSink
import kr.aigyeot.app.host.Kit
import kr.aigyeot.engine.MicPath
import kr.aigyeot.engine.OpenTrigger
import kr.aigyeot.engine.SaveSession
import kr.aigyeot.engine.SessionRecord
import kr.aigyeot.engine.Step
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

// 홈 ViewModel의 시간 출처는 주입 시계 하나다(코드 리뷰 9). 화면 흐름을 실제로 모아 본다
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    private val k = Kit()

    @After fun close() { k.host.close(); k.linkScope.cancel(); k.db.close() }

    @Test fun carDay_usesInjectedClockDate_notDeviceDate() {
        // 가짜 시계는 기기 날짜보다 앞선 날(기기 날짜로 거르면 그날 주행이 빠진다)
        k.now = Instant.parse("2025-06-01T03:00:00Z").toEpochMilli()   // 서울 정오
        assertTrue(Instant.ofEpochMilli(k.now).atZone(TimeText.zone).toLocalDate() < LocalDate.now(TimeText.zone), "전제: 가짜 시계 날짜가 기기 날짜보다 앞선다")
        k.profile(); k.boot()
        // 가짜 시계 날의 실제 주행(닫힘) 하나
        k.store.handle(SaveSession(SessionRecord("S1", k.now - 3_600_000, k.now - 1_800_000, OpenTrigger.DOOR_THEN_D, MicPath.HOME_BUTTON, null, 10, 0, false, "[]", null, null, false, null, 10)))
        val vm = HomeViewModel(MutableStateFlow<Step?>(null), EventSink {}, k.realLink, k.clock, MutableStateFlow(null), MutableStateFlow(null), k.store)
        var day: DayChartData? = null
        val job = vm.viewModelScope.launch { vm.uiState.collect { day = it.day } }
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end && day?.lanes?.any { it.name == "차에서" } != true) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        job.cancel()
        assertTrue(day?.lanes?.any { it.name == "차에서" } == true, "가짜 시계 날의 주행이 '오늘 하루'에 들어온다: $day")
    }
}
