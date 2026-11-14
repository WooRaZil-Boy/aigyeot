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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import kr.aigyeot.phone.domain.model.phoneSub
import kr.aigyeot.shared.Role

/** 위쪽 띠(오프라인·안내·시계 차이). 연결 안 됨이면 차와 관계된 띠는 뜻이 없고, 저장 실패 같은 안내만 보인다. */
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
 * guardian 오늘 탭 위쪽: 상태 띠, 알림 권한 띠, 받은 인계 알림(아이 주황 배지 '차에서 · {경과}' + 문장 17/25·600).
 * 머리 없이 최근 5개. 누르면 인계 알림 상세.
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
    // 화면이 떠 있는 동안 새로 온 인계 알림 판만 들어온다.
    val known = remember { s.alerts.map { it.id }.toSet() }
    for (a in s.alerts.asReversed().take(5)) key(a.id) { EnterNew(a.id !in known) {
        Block(padding = androidx.compose.foundation.layout.PaddingValues(18.dp), onClick = { go(Route.Alert(a.id)) }) {
            Badge("차에서 · ${agoText(instantOf(a.body.occurredAt) ?: a.receivedAt, now)}", R.drawable.ic_car_front, child = true) // '~ 전'
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
                Badge(
                    "차에서 · ${agoText(instantOf(alert.body.occurredAt) ?: alert.receivedAt, System.currentTimeMillis())}",
                    R.drawable.ic_car_front,
                    child = true,
                )
                Text(alert.body.text, Modifier.padding(top = 10.dp, bottom = 6.dp), style = Type.headline, color = p.textPrimary)
                // 이름·값 두 칸(iOS detailRow와 같음, '이름: 값' 콜론 없음)
                KeyValues(listOfNotNull(alert.body.restName?.let { "쉼터" to it }, alert.body.minutesToArrival?.let { "도착까지" to "${it}분" }))
                Text(dateTime(alert.body.occurredAt), Modifier.padding(top = 8.dp), style = Type.caption, color = p.textTertiary)
            }
        }
    }
}

/** 초대 QR. 가운데에 앱 아이콘을 얹으므로 오류 정정 H(30%)로 만든다. */
private fun qr(text: String): Bitmap {
    val m = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        512,
        512,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 1),
    )
    return Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565).apply {
        for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else -1)
    }
}

/** 보호자: owner는 초대·목록, guardian은 인계 알림 받기. */
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
                    "인계 알림 받기", R.drawable.ic_bell, sub = "아기가 계속 울면 알려요", // 인계 알림 부제
                    enabled = !ui.busy,
                    checked = ui.handoffAlerts,
                    onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                        vm.setHandoff(on)
                    },
                )
            }
            FootNote("리포트는 알림을 꺼도 받아요") // 인계 알림 안내 글
        } else {
            // 보호자 초대: 블록 머리 + 정보 줄 셋. 코드를 만든 뒤에는 코드·기한·QR + 주 단추 '링크로 보내기' + 글자 단추 '새 코드 만들기'.
            Block {
                BlockHeader("다른 보호자 초대")
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    InfoLine(R.drawable.ic_bell, "리포트와 인계 알림을 같이 받아요", plain = true)
                    InfoLine(R.drawable.ic_clock, "코드는 24시간 동안 한 번만 쓸 수 있어요", plain = true) // '써요'는 시키는 말로 읽혀 '쓸 수 있어요'
                    InfoLine(R.drawable.ic_lock, "수유 시작과 우리 노래는 보낼 수 없어요", plain = true)
                }
                val inv = ui.invite
                if (inv != null) {
                    Column(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(inv.display, style = Type.inviteCode, color = p.textPrimary)
                        Text("${dateTime(inv.expiresAt)}까지", Modifier.padding(top = 4.dp, bottom = 16.dp), style = Type.caption, color = p.textTertiary)
                        Box(
                            Modifier.size(196.dp).clip(Radius.control).background(androidx.compose.ui.graphics.Color.White).padding(10.dp)
                                .semantics { contentDescription = "초대 QR" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(qr(inv.link).asImageBitmap(), null, Modifier.size(176.dp))
                            AppMark(40.dp, 11.dp)
                        }
                    }
                }
                if (ui.busy) Text("초대 코드를 만들고 있어요", Modifier.padding(top = 16.dp), style = Type.caption, color = p.textTertiary)
                if (inv != null) {
                    Btn("링크로 보내기", {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "아이곁 초대 코드 ${inv.display}\n${inv.link}")
                        context.startActivity(Intent.createChooser(send, "링크로 보내기"))
                    }, Modifier.padding(top = 20.dp), icon = R.drawable.ic_share)
                    Btn("새 코드 만들기", vm::makeInvite, Modifier.padding(top = 4.dp), kind = BtnKind.TEXT, enabled = !ui.busy && ui.online)
                } else {
                    Btn("초대 코드 만들기", vm::makeInvite, Modifier.padding(top = 20.dp), enabled = !ui.busy && ui.online, icon = R.drawable.ic_users)
                }
                if (!ui.online) Text("인터넷이 필요해요", Modifier.padding(top = 8.dp), style = Type.caption, color = p.textTertiary)
            }
            if (ui.phones.isNotEmpty()) ListBlock("연결된 휴대폰") {
                for (d in ui.phones) ListRow(
                    if (d.role == Role.OWNER) "함께 타는 보호자" else "다른 보호자",
                    phoneSub(d, ui.myDeviceId),
                    icon = if (d.role == Role.OWNER) R.drawable.ic_car_front else R.drawable.ic_smartphone,
                )
            }
        }
        ui.error?.let { Text(it, style = Type.caption, color = p.danger) }
    }
}
