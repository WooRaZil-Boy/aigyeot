package kr.aigyeot.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kr.aigyeot.phone.core.canOpenConfirm
import kr.aigyeot.phone.core.codeFromLink
import kr.aigyeot.phone.core.isUlid
import kr.aigyeot.phone.ui.AlertScreen
import kr.aigyeot.phone.ui.ConfirmScreen
import kr.aigyeot.phone.ui.ConnectScreen
import kr.aigyeot.phone.ui.DoneScreen
import kr.aigyeot.phone.ui.GoneScreen
import kr.aigyeot.phone.ui.GuardianHome
import kr.aigyeot.phone.ui.GuardiansScreen
import kr.aigyeot.phone.ui.IntroScreen
import kr.aigyeot.phone.ui.OwnerHome
import kr.aigyeot.phone.ui.ReportDetailScreen
import kr.aigyeot.phone.ui.ReportsScreen
import kr.aigyeot.phone.ui.SettingsScreen
import kr.aigyeot.phone.ui.SongsScreen
import kr.aigyeot.shared.Role

class MainActivity : ComponentActivity() {
    private val model get() = (application as AigyeotApp).model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 앱이 앞에 있을 때만 붙는다(계약 2장). 뒤로 가면 끊고 멈춘다.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = model.onForeground()
            override fun onStop(owner: LifecycleOwner) = model.onBackground()
        })
        // 화면을 다시 만들 때(회전 등)는 처음 받은 인텐트를 이미 처리했다(7.6).
        if (savedInstanceState == null) handle(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root(model) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** 연결 링크(7.6)와 인계 알림 누름(5.3: msg_id가 extras로 온다). */
    private fun handle(intent: Intent) {
        intent.data?.toString()?.let { link ->
            val code = codeFromLink(link)
            if (code != null) model.pendingCode.value = code
            else android.widget.Toast.makeText(this, "올바른 연결 링크가 아니에요", android.widget.Toast.LENGTH_SHORT).show()
        }
        // exported 액티비티라 다른 앱도 extras를 넣을 수 있다. ULID가 아니면 버린다(경로에 그대로 들어간다).
        intent.getStringExtra("msg_id")?.takeIf(::isUlid)?.let { model.openAlert.value = it }
    }
}

@Composable
private fun Root(model: AppModel) {
    val nav = rememberNavController()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val creds by model.creds.collectAsState()
    val ui by model.ui.collectAsState()
    val pending by model.pendingCode.collectAsState()
    val openAlert by model.openAlert.collectAsState()
    // 시작 화면은 처음 한 번만 정한다. creds가 바뀔 때 그래프가 바뀌면 이력이 사라지고
    // 연결됨(guardian 알림 권한)·확인 화면의 코루틴이 끊긴다. 연결·해제 뒤 이동은 화면마다 명시한다.
    val start = remember { if (model.creds.value == null) "intro" else "home" }

    LaunchedEffect(ui.gone) { if (ui.gone) nav.reset("gone") }
    // 코드는 확인 화면 경로에 실렸으니 바로 비운다. 다시 그려도 확인 화면을 또 띄우지 않는다.
    LaunchedEffect(pending) {
        pending?.let {
            // 직접 친 코드의 요청이 진행 중이면 확인 화면으로 옮기지 않는다(끝난 결과를 받을 화면이 사라진다).
            if (canOpenConfirm(model.pairRun.value?.busy == true)) nav.navigate("confirm/$it")
            else android.widget.Toast.makeText(ctx, "연결하고 있어요. 끝난 뒤에 링크를 다시 열어 주세요", android.widget.Toast.LENGTH_SHORT).show()
            model.pendingCode.value = null
        }
    }
    LaunchedEffect(openAlert, creds) {
        val id = openAlert ?: return@LaunchedEffect
        if (creds != null) nav.navigate("alert/$id")
        model.openAlert.value = null
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        NavHost(nav, startDestination = start) {
            composable("intro") { IntroScreen { nav.navigate("connect") } }
            composable("connect") { ConnectScreen(model, onCode = { code -> nav.navigate("confirm/$code") }, onPaired = { role -> nav.reset("done/${role.name}") }) }
            composable("confirm/{code}") { e ->
                val code = e.arguments?.getString("code").orEmpty()
                ConfirmScreen(
                    model,
                    code,
                    onDone = { role -> nav.reset("done/${role.name}") },
                    onRetry = { model.clearPairRun(); nav.navigate("connect") },
                )
            }
            composable("done/{role}") { e ->
                val role = if (e.arguments?.getString("role") == Role.GUARDIAN.name) Role.GUARDIAN else Role.OWNER
                DoneScreen(role) { nav.reset("home") }
            }
            composable("gone") { GoneScreen { model.acknowledgeGone(); nav.reset("connect") } }
            composable("home") {
                when (creds?.role) {
                    Role.GUARDIAN -> GuardianHome(model) { nav.navigate(it) }
                    null -> LaunchedEffect(Unit) { nav.reset("intro") }
                    else -> OwnerHome(model) { nav.navigate(it) }
                }
            }
            composable("songs") { SongsScreen(model) { nav.popBackStack() } }
            composable("reports") { ReportsScreen(model, { nav.popBackStack() }) { nav.navigate("report/$it") } }
            composable("report/{id}") { e -> ReportDetailScreen(model, e.arguments?.getString("id").orEmpty()) { nav.popBackStack() } }
            composable("guardians") { GuardiansScreen(model) { nav.popBackStack() } }
            composable("settings") { SettingsScreen(model, { nav.popBackStack() }) { nav.reset("intro") } }
            composable("alert/{id}") { e -> AlertScreen(model, e.arguments?.getString("id").orEmpty()) { nav.popBackStack() } }
        }
        // 앱 안 배너(5.6): 앞에 있을 때 온 인계 알림. 시스템 알림은 띄우지 않는다.
        ui.banner?.let { text ->
            Card(Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopCenter)) {
                Column(Modifier.padding(16.dp)) {
                    Text("인계 알림", style = MaterialTheme.typography.labelLarge)
                    Text(text)
                    Row {
                        ui.bannerAlertId?.let { id -> TextButton(onClick = { model.dismissBanner(); nav.navigate("alert/$id") }) { Text("보기") } }
                        TextButton(onClick = model::dismissBanner) { Text("닫기") }
                    }
                }
            }
        }
    }
}

/** 뒤로 가기 기록을 비우고 이동한다. */
private fun NavHostController.reset(route: String) = navigate(route) { popUpTo(0) { inclusive = true } }
