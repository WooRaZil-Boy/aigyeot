package kr.aigyeot.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.ongoing
import kr.aigyeot.phone.domain.model.sideName

/**
 * 잠금 화면 진행 표시. 진행 중 수유·잠마다 알림 하나: 경과는 시스템 시계(`setUsesChronometer`)가 그린다.
 * 포그라운드 서비스·매초 갱신 없이 기록이 바뀔 때만 다시 띄운다. 알림 권한이 없으면 아무것도 보이지 않는다(앱 안에서만 보임).
 * 플랫폼다움: "수유 중 · 12:34 · 왼쪽"을 제목 "수유 중"·내용 "왼쪽"·시스템 시계로 나눠 보인다.
 */
object CareNotify {
    const val CHANNEL = "care_running"
    private const val ACTION_END = "kr.aigyeot.phone.END_CARE"
    private const val EXTRA_ID = "care_id"

    private fun noticeId(kind: CareKind) = if (kind == CareKind.FEED) 101 else 102

    fun update(context: Context, care: CareFile) {
        val nm = NotificationManagerCompat.from(context)
        for (kind in listOf(CareKind.FEED, CareKind.SLEEP)) {
            val e = care.ongoing(kind)
            if (e == null || !nm.areNotificationsEnabled()) {
                nm.cancel(noticeId(kind))
                continue
            }
            val open = PendingIntent.getActivity(
                context, noticeId(kind),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val end = PendingIntent.getBroadcast(
                context, noticeId(kind),
                Intent(context, CareEndReceiver::class.java).setAction(ACTION_END).putExtra(EXTRA_ID, e.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_aigyeot) // 앱 아이콘 앞 층의 흰 실루엣
                .setColor(context.getColor(R.color.notification_accent))
                .setContentTitle(if (kind == CareKind.FEED) "수유 중" else "자는 중")
                .setContentText(e.feedSide?.let(::sideName))
                .setWhen(e.startAt)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 아기 이름·내용 없이 종류와 경과만 보인다
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setContentIntent(open)
                .addAction(0, "끝내기", end)
                .build()
            try {
                nm.notify(noticeId(kind), n)
            } catch (_: SecurityException) {
                // 권한이 그사이 꺼졌다: 앱 안에서만 보인다.
            }
        }
    }

    /** 알림 '끝내기': 같은 저장소를 고친다. 저장이 끝날 때까지 수신을 붙들어 프로세스가 먼저 죽지 않게 한다. */
    class CareEndReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_END) return
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val pending = goAsync()
            (context.applicationContext as AigyeotApp).container.recordCare.end(id).invokeOnCompletion { pending.finish() }
        }
    }
}
