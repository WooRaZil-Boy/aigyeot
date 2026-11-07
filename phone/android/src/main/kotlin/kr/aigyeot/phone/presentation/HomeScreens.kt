package kr.aigyeot.phone.presentation

import androidx.compose.foundation.layout.heightIn
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kr.aigyeot.phone.AigyeotApp
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.agoText
import kr.aigyeot.phone.domain.model.carTileText
import kr.aigyeot.phone.domain.model.instantOf
import kr.aigyeot.shared.Role

/** 위쪽 띠(오프라인·안내·시계 차이, 7.2). 연결 안 됨이면 차와 관계된 띠는 뜻이 없고, 저장 실패 같은 안내만 보인다(U7). */
@Composable
internal fun StatusBands(connected: Boolean = true, vm: StatusViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    if (connected && !ui.status.online) Band("오프라인이에요. 누른 것은 연결되면 차로 가요.", "다시 시도") { vm.retryNow() }
    ui.notice?.let { Band(it, "닫기") { vm.dismissNotice() } }
    if (connected) ui.status.clockSkewMin?.takeIf { kotlin.math.abs(it) >= 2 }?.let { Band("휴대폰 시계가 ${kotlin.math.abs(it)}분 달라요. 수유 시각이 어긋날 수 있어요.") }
}

/** 홈 차 타일(7.2): 오프라인이면 알 수 없음, 아니면 연결됨 또는 차 꺼짐 · 마지막 연락 n분 전. */
@Composable
internal fun CarTile(s: PhoneState, online: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Muted("차")
            Text(carTileText(s.devices, online, System.currentTimeMillis()), style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** 앱 알림 전체(33+는 POST_NOTIFICATIONS 포함) 또는 인계 알림 채널이 꺼졌는지. */
private fun isNotifOff(context: Context): Boolean {
    val nm = NotificationManagerCompat.from(context)
    return !nm.areNotificationsEnabled() ||
        nm.getNotificationChannel(AigyeotApp.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
}

/** guardian 오늘 탭 위쪽: 상태 띠, 알림 권한 띠, 받은 인계 알림(7.2). */
@Composable
internal fun GuardianAlerts(s: PhoneState, go: (Route) -> Unit) {
    val context = LocalContext.current
    var notifOff by remember { mutableStateOf(isNotifOff(context)) }
    // 설정에서 알림을 켜거나 끄고 돌아오면 다시 본다.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notifOff = isNotifOff(context) }
    StatusBands()
    if (notifOff) {
        Band("알림이 꺼져 있어 인계 알림을 못 받아요", "설정 열기") {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
    if (s.alerts.isEmpty() && s.reports.isEmpty()) Muted("아직 받은 리포트·인계 알림이 없어요", Type.body)
    for (a in s.alerts.asReversed().take(5)) { // U39: 머리 없이 최근 5개
        Card(onClick = { go(Route.Alert(a.id)) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(a.body.text)
                Muted(agoText(instantOf(a.body.occurredAt) ?: a.receivedAt, System.currentTimeMillis())) // U20 '~ 전'
            }
        }
    }
}

/** 인계 알림 상세(guardian). */
@Composable
fun AlertScreen(onBack: () -> Unit, vm: AlertViewModel = appViewModel()) {
    val a by vm.alert.collectAsStateWithLifecycle()
    Page("인계 알림", onBack) {
        val alert = a
        if (alert == null) {
            Muted("알림 내용을 받고 있어요")
        } else {
            Text(alert.body.text, style = MaterialTheme.typography.titleMedium)
            alert.body.restName?.let { Text("쉼터: $it") }
            alert.body.minutesToArrival?.let { Text("도착까지 ${it}분") }
            Muted("${dateTime(alert.body.occurredAt)} · ${agoText(instantOf(alert.body.occurredAt) ?: alert.receivedAt, System.currentTimeMillis())}")
        }
    }
}

private fun qr(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    return Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565).apply {
        for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else -1)
    }
}

/** 보호자: owner는 초대·목록, guardian은 인계 알림 받기(7.1). */
@Composable
fun GuardiansScreen(onBack: () -> Unit, vm: GuardiansViewModel = appViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Page("보호자", onBack) {
        if (ui.role == Role.GUARDIAN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("인계 알림 받기", Modifier.weight(1f))
                Switch(
                    checked = ui.handoffAlerts,
                    enabled = !ui.busy,
                    onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                        vm.setHandoff(on)
                    },
                )
            }
            Muted("아기가 계속 울어 운전자에게 쉬어 가기를 안내할 때 알림을 받아요.")
        } else {
            Text("다른 보호자를 초대하면 리포트와 인계 알림을 함께 받아요.")
            Muted("초대 코드는 24시간 동안 한 번만 쓸 수 있어요. 받는 사람은 수유·노래를 보내지 못해요.")
            if (ui.busy) Muted("초대 코드를 만들고 있어요")
            Button(
                enabled = !ui.busy && ui.online,
                onClick = vm::makeInvite,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("초대 코드 만들기") }
            if (!ui.online) Muted("인터넷이 필요해요")
            ui.invite?.let { inv ->
                Text(inv.display, style = MaterialTheme.typography.displaySmall)
                Muted("${dateTime(inv.expiresAt)}까지")
                Image(qr(inv.link).asImageBitmap(), "초대 QR", Modifier.size(220.dp))
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "아이곁 초대 코드 ${inv.display}\n${inv.link}")
                    context.startActivity(Intent.createChooser(send, "링크로 보내기"))
                }) { Text("링크로 보내기") }
            }
            HorizontalDivider()
            for (d in ui.phones) {
                Row {
                    Text(if (d.role == Role.OWNER) "함께 타는 보호자" else "다른 보호자", Modifier.weight(1f))
                    Muted(if (d.deviceId == ui.myDeviceId) "이 휴대폰" else d.platform.name.lowercase(), Type.body)
                }
            }
        }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
