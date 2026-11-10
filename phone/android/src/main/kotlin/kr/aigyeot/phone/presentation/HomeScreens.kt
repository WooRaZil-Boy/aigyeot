package kr.aigyeot.phone.presentation

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kr.aigyeot.phone.AigyeotApp
import kr.aigyeot.phone.R
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.agoText
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

/** 앱 알림 전체(33+는 POST_NOTIFICATIONS 포함) 또는 인계 알림 채널이 꺼졌는지. */
private fun isNotifOff(context: Context): Boolean {
    val nm = NotificationManagerCompat.from(context)
    return !nm.areNotificationsEnabled() ||
        nm.getNotificationChannel(AigyeotApp.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
}

/**
 * guardian 오늘 탭 위쪽: 상태 띠, 알림 권한 띠, 받은 인계 알림(7.2, 시안 .alert-c: 아이 주황 배지 '차에서 · {경과}' + 문장 17/25·600).
 * U39: 머리 없이 최근 5개. 누르면 인계 알림 상세.
 */
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
    val now = rememberNow(60_000)
    val p = Tokens.colors
    // 화면이 떠 있는 동안 새로 온 인계 알림 판만 들어온다(시안 .alert-c.enter).
    val known = remember { s.alerts.map { it.id }.toSet() }
    for (a in s.alerts.asReversed().take(5)) key(a.id) { EnterNew(a.id !in known) {
        Block(padding = androidx.compose.foundation.layout.PaddingValues(18.dp), onClick = { go(Route.Alert(a.id)) }) {
            Badge("차에서 · ${agoText(instantOf(a.body.occurredAt) ?: a.receivedAt, now)}", R.drawable.ic_car_front, child = true) // U20 '~ 전'
            Text(a.body.text, Modifier.padding(top = 10.dp), style = Type.headline, color = p.textPrimary)
        }
    } }
}

/** 인계 알림 상세(guardian). */
@Composable
fun AlertScreen(onBack: () -> Unit, vm: AlertViewModel = appViewModel()) {
    val a by vm.alert.collectAsStateWithLifecycle()
    val p = Tokens.colors
    BackPage("인계 알림", onBack) {
        val alert = a
        if (alert == null) {
            FootNote("알림 내용을 받고 있어요")
        } else {
            Block {
                Badge("차에서 · ${agoText(instantOf(alert.body.occurredAt) ?: alert.receivedAt, System.currentTimeMillis())}", R.drawable.ic_car_front, child = true)
                Text(alert.body.text, Modifier.padding(top = 10.dp, bottom = 6.dp), style = Type.headline, color = p.textPrimary)
                alert.body.restName?.let { Text("쉼터: $it", style = Type.body, color = p.textSecondary) }
                alert.body.minutesToArrival?.let { Text("도착까지 ${it}분", style = Type.body, color = p.textSecondary) }
                Text(dateTime(alert.body.occurredAt), Modifier.padding(top = 8.dp), style = Type.caption, color = p.textTertiary)
            }
        }
    }
}

/** 초대 QR. 가운데에 앱 아이콘을 얹으므로 오류 정정 H(30%)로 만든다(IMPLEMENT 4장 'QR: 실제 QR에 가운데 로고만'). */
private fun qr(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 1))
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
    val p = Tokens.colors
    BackPage("보호자", onBack) {
        if (ui.role == Role.GUARDIAN) {
            Group {
                SettingRow(
                    "인계 알림 받기", R.drawable.ic_bell, sub = "아기가 계속 울어 운전자에게 쉬어 가기를 안내할 때 알림을 받아요.",
                    enabled = !ui.busy,
                    checked = ui.handoffAlerts,
                    onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                        vm.setHandoff(on)
                    },
                )
            }
        } else {
            Block {
                Text("다른 보호자를 초대하면 리포트와 인계 알림을 함께 받아요.", style = Type.headline, color = p.textPrimary)
                Text("초대 코드는 24시간 동안 한 번만 쓸 수 있어요. 받는 사람은 수유·노래를 보내지 못해요.", Modifier.padding(top = 6.dp, bottom = 16.dp), style = Type.meta, color = p.textTertiary)
                if (ui.busy) Text("초대 코드를 만들고 있어요", Modifier.padding(bottom = 8.dp), style = Type.caption, color = p.textTertiary)
                Btn("초대 코드 만들기", vm::makeInvite, enabled = !ui.busy && ui.online, icon = R.drawable.ic_users)
                if (!ui.online) Text("인터넷이 필요해요", Modifier.padding(top = 8.dp), style = Type.caption, color = p.textTertiary)
            }
            ui.invite?.let { inv ->
                Block {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(inv.display, style = Type.inviteCode, color = p.textPrimary)
                        Text("${dateTime(inv.expiresAt)}까지", Modifier.padding(bottom = 14.dp), style = Type.caption, color = p.textTertiary)
                        Box(Modifier.size(220.dp).clip(Radius.control).background(androidx.compose.ui.graphics.Color.White).padding(10.dp).semantics { contentDescription = "초대 QR" }, contentAlignment = Alignment.Center) {
                            Image(qr(inv.link).asImageBitmap(), null, Modifier.size(200.dp))
                            AppMark(44.dp, 12.dp)
                        }
                    }
                    Btn("링크로 보내기", {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "아이곁 초대 코드 ${inv.display}\n${inv.link}")
                        context.startActivity(Intent.createChooser(send, "링크로 보내기"))
                    }, Modifier.padding(top = 16.dp), kind = BtnKind.TONAL, icon = R.drawable.ic_share)
                }
            }
            if (ui.phones.isNotEmpty()) ListBlock {
                for (d in ui.phones) ListRow(
                    if (d.role == Role.OWNER) "함께 타는 보호자" else "다른 보호자",
                    if (d.deviceId == ui.myDeviceId) "이 휴대폰" else d.platform.name.lowercase(),
                    icon = if (d.role == Role.OWNER) R.drawable.ic_car_front else R.drawable.ic_smartphone,
                )
            }
        }
        ui.error?.let { Text(it, style = Type.caption, color = p.danger) }
    }
}
