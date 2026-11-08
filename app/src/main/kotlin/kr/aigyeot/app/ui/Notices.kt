package kr.aigyeot.app.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kr.aigyeot.engine.Notice

/**
 * ShowNotice(계약 4.3)를 안드로이드 알림으로 띄운다. 누르면 앱 화면을 열어 그 화면이 마이크 서비스를 켜거나 사건을 넣는다.
 * 화면 없이 마이크를 바로 켜는 알림 액션(C70)은 첫날 실측 전이라 넣지 않았다(명세서 6장: 안 되면 앱 화면을 연다).
 */
object Notices {
    const val EXTRA = "notice_action"
    const val BABY_ABOARD = "baby_aboard"
    const val MIC_RESTART = "mic_restart"
    const val FEEDING_YES = "feeding_yes"
    const val FEEDING_NO = "feeding_no"
    private const val CHANNEL = "notice"
    /** 매니페스트의 비공개 activity-alias. MainActivity는 이 이름으로 들어온 intent만 EXTRA를 처리한다(C352). */
    const val ENTRY = "kr.aigyeot.app.NoticeEntry"

    fun show(ctx: Context, kind: Notice) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "아이곁 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val b = Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true)
        when (kind) {
            Notice.BABY_ABOARD -> b.setContentTitle("아기가 탔나요?").setContentText("누르면 듣기를 켜요").setContentIntent(open(ctx, BABY_ABOARD))
            Notice.MIC_STOPPED -> b.setContentTitle("듣기가 멈췄어요").setContentText("누르면 다시 들어요").setContentIntent(open(ctx, MIC_RESTART))
            Notice.FEEDING_CONFIRM -> b.setContentTitle("수유하셨어요?").setContentIntent(open(ctx, null)).setOnlyAlertOnce(true)   // C349: Boot가 다시 띄워도 소리는 한 번
                .addAction(Notification.Action.Builder(null, "네", open(ctx, FEEDING_YES)).build())
                .addAction(Notification.Action.Builder(null, "아니요", open(ctx, FEEDING_NO)).build())
            Notice.SAFETY_CONTROL_FAILED -> b.setContentTitle("열선을 끄지 못했어요").setContentText("뒷좌석 열선을 직접 확인해 주세요")
        }
        nm.notify(kind.ordinal + 10, b.build())
    }

    fun cancel(ctx: Context, kind: Notice) = ctx.getSystemService(NotificationManager::class.java).cancel(kind.ordinal + 10)

    private fun open(ctx: Context, action: String?): PendingIntent = PendingIntent.getActivity(
        ctx, action.hashCode(),
        Intent().setComponent(ComponentName(ctx, ENTRY)).putExtra(EXTRA, action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
