package kr.aigyeot.app.ui

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertTrue
import kr.aigyeot.engine.Notice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** 상태대로 다시 띄우는 알림(장면 끝·프로세스 시작·첫 P)은 이미 떠 있으면 소리를 다시 내지 않는다. */
@RunWith(RobolectricTestRunner::class)
class NoticesTest {
    @Test fun stateNotices_reshowAlertsOnce() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val nm = shadowOf(ctx.getSystemService(NotificationManager::class.java))
        for (k in listOf(Notice.BABY_ABOARD, Notice.MIC_STOPPED, Notice.FEEDING_CONFIRM)) {
            Notices.show(ctx, k)
            Notices.show(ctx, k)
            assertTrue(nm.getNotification(k.ordinal + 10).flags and Notification.FLAG_ONLY_ALERT_ONCE != 0, "$k")
        }
    }
}
