package kr.aigyeot.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AigyeotApp : Application() {
    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        // 인계 알림 채널(5.3). minSdk 26이라 채널이 늘 있다.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "인계 알림", NotificationManager.IMPORTANCE_HIGH),
        )
        model = AppModel(this)
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
    private val model get() = (application as AigyeotApp).model

    // Firebase Messaging 26은 getToken·onNewToken을 지원 중단으로 표시했지만 계약 5.4대로 아직 쓴다(동작은 같다).
    @Deprecated("계약 5.4의 토큰 갱신 경로")
    override fun onNewToken(token: String) = model.onPushToken(token)

    override fun onMessageReceived(message: RemoteMessage) {
        model.onPush(message.data["msg_id"] ?: return)
    }
}
