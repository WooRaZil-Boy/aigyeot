package kr.aigyeot.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kr.aigyeot.phone.di.AppContainer

class AigyeotApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 인계 알림 채널(5.3). minSdk 26이라 채널이 늘 있다.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "인계 알림", NotificationManager.IMPORTANCE_HIGH),
        )
        // 진행 중 수유·잠(C6). LOW(무음 분류)는 기본 설정에서 잠금 화면에 안 보여(11-05 에뮬레이터 확인) DEFAULT로 두고 소리·진동을 끈다.
        // 채널 중요도는 만든 뒤 바뀌지 않아 옛 id(LOW로 만든 개발판)는 지운다.
        getSystemService(NotificationManager::class.java).apply {
            deleteNotificationChannel("care_ongoing")
            createNotificationChannel(
                NotificationChannel(CareNotify.CHANNEL, "진행 중 기록", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
            )
        }
        container = AppContainer(this).also { it.lifecycle.start() }
    }

    companion object {
        const val CHANNEL = "handoff_alert"
    }
}

/**
 * FCM 수신 틀(5.3·5.4). google-services.json이 없으면 Firebase가 초기화되지 않아 아무것도 오지 않는다.
 * 앱이 닫혀 있으면 시스템이 `notification`으로 띄우고, 앞에 있으면 여기로 와서 앱 안 배너만 띄운다.
 */
class PushService : FirebaseMessagingService() {
    private val container get() = (application as AigyeotApp).container

    // Firebase Messaging 26은 getToken·onNewToken을 지원 중단으로 표시했지만 계약 5.4대로 아직 쓴다(동작은 같다).
    @Deprecated("계약 5.4의 토큰 갱신 경로")
    override fun onNewToken(token: String) = container.push.onToken(token)

    override fun onMessageReceived(message: RemoteMessage) {
        container.inbox.onPush(message.data["msg_id"] ?: return)
    }
}
