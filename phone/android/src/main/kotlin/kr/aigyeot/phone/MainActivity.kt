package kr.aigyeot.phone

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kr.aigyeot.phone.core.PAIR_BUSY
import kr.aigyeot.phone.core.SAVE_FAILED
import kr.aigyeot.phone.core.codeFromLink
import kr.aigyeot.phone.core.isUlid
import kr.aigyeot.phone.ui.AlertScreen
import kr.aigyeot.phone.ui.ConfirmScreen
import kr.aigyeot.phone.ui.ConnectScreen
import kr.aigyeot.phone.ui.DoneScreen
import kr.aigyeot.phone.ui.GoneScreen
import kr.aigyeot.phone.ui.AboutScreen
import kr.aigyeot.phone.ui.AigyeotTheme
import kr.aigyeot.phone.ui.LicenseScreen
import kr.aigyeot.phone.ui.CareTabs
import kr.aigyeot.phone.ui.GuardiansScreen
import kr.aigyeot.phone.ui.IntroScreen
import kr.aigyeot.phone.ui.ReportDetailScreen
import kr.aigyeot.phone.ui.ReportsScreen
import kr.aigyeot.phone.ui.SongsScreen
import kr.aigyeot.phone.ui.TimerScreen
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
        // 새로 연 화면에는 지난 연결 결과를 받을 확인 화면이 없다. 실패·지난 결과는 새 링크를 막지 않게 먼저 비우고,
        // 지금 연결의 성공은 남겨 Root가 완료 화면으로 보낸다(U52, 7.1).
        if (savedInstanceState == null) {
            model.clearStalePairRun()
            handle(intent)
        }
        setContent {
            AigyeotTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(model) } // 13장 bg(탭 밖 화면도)
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
            // U52: 받은 순간에 연결이 돌고 있으면 버린다. 화면이 처리할 때 보면 그사이 연결이 끝나 뒤늦게 받는다.
            val msg = when {
                code == null -> "올바른 연결 링크가 아니에요"
                !model.canTakeLink() -> PAIR_BUSY
                else -> null
            }
            if (msg == null) model.pendingCode.value = code
            else android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
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
    // 첫 실행 안내 화면에서 받은 연결 링크의 코드: 안내를 읽은 뒤 '차와 연결하기'에서 확인 화면을 연다(7.1, U62).
    var introCode by rememberSaveable { mutableStateOf<String?>(null) }
    // 시작 화면은 처음 한 번만 정한다. creds가 바뀔 때 그래프가 바뀌면 이력이 사라지고
    // 연결됨(guardian 알림 권한)·확인 화면의 코루틴이 끊긴다. 연결·해제 뒤 이동은 화면마다 명시한다.
    // 연결이 없어도 '먼저 기록만 쓸게요'를 골랐으면 바로 탭으로 간다(U7).
    // 앞 프로세스가 연결 완료 화면을 넘기기 전에 죽었으면 그 화면부터(guardian 알림 권한 단계, 7.1).
    val start = remember {
        // 앞 실행의 강제 끊김을 고르기 전에 죽었으면 끊김 화면부터(한 프레임 다른 화면을 보이지 않게).
        if (model.ui.value.gone) "gone"
        else model.pendingDoneRole()?.let { "done/${it.name}" } ?: if (model.creds.value == null && !model.recordsOnly) "intro" else "home"
    }

    LaunchedEffect(ui.gone) { if (ui.gone) nav.reset("gone") }
    // 저장된 화면 이력이 복원되면 시작 화면은 쓰이지 않는다(프로세스가 죽은 뒤 최근 앱으로 돌아옴): 완료 화면을 넘기지 않았으면 그리로 간다(7.1).
    LaunchedEffect(Unit) {
        // 이미 넘긴 GONE·완료 화면이 저장된 이력에서 복원됐으면(고른 뒤 이력을 저장하기 전에 죽음) 시작 화면으로 돌린다.
        val restored = nav.currentDestination?.route
        if ((restored == "gone" && !model.ui.value.gone) || (restored == "done/{role}" && model.pendingDoneRole() == null)) {
            nav.reset(if (model.creds.value == null && !model.recordsOnly) "intro" else "home")
        }
        val role = model.takeRestoredDone() ?: return@LaunchedEffect
        // 복원된 완료 화면이라도 역할이 다르면(옛 owner 완료 위에 새 guardian 연결) 바꾼다: 알림 권한 단계는 지금 역할로 정한다.
        val top = nav.currentBackStackEntry
        if (top?.destination?.route != "done/{role}" || top.arguments?.getString("role") != role.name) nav.reset("done/${role.name}")
    }
    // U52: 끝난 연결 결과는 그것을 받는 화면(연결·확인)만 쓴다. 다른 화면에 있으면(GONE으로 갔거나 화면을 닫은 사이 끝남) 비워 새 링크를 막지 않게 한다.
    val run by model.pairRun.collectAsState()
    val top by nav.currentBackStackEntryAsState()
    LaunchedEffect(run, top) {
        val route = top?.destination?.route ?: return@LaunchedEffect
        val r = run?.takeIf { !it.busy } ?: return@LaunchedEffect
        // 그 결과를 실제로 받는 화면이면 맡긴다: 같은 코드의 확인 화면, 또는 연결 화면의 실패(직접 친 코드의 오류는 입력칸 아래에 보인다).
        // 다른 코드의 확인 화면이나 연결 화면으로 돌아와 끝난 성공은 여기서 완료 화면으로 보낸다(그 화면은 자기 코드만 받는다).
        val receiver = when (route) {
            "connect" -> r.error != null
            "confirm/{code}" -> top?.arguments?.getString("code") == r.code
            else -> false
        }
        if (receiver) return@LaunchedEffect
        // 다른 화면에 있는 사이 지금 연결이 성공했다(옛 연결의 GONE 뒤, 연결 중 알림 상세로 감 등): 완료 화면으로 간다
        // (guardian 알림 권한 단계를 빠뜨리지 않게, 7.1). 실패·지난 결과는 비워 새 링크를 막지 않는다.
        if (model.takePairDone(r)) nav.reset("done/${r.role!!.name}") else model.clearPairRun()
    }
    // 연결 정보를 거듭 읽지 못하면 지우고 새로 연결할지 묻는다(11-07 사용자 결정). 돌봄 기록은 남는다.
    run?.takeIf { it.resetOffer && !it.busy }?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { model.clearPairRun() },
            title = { androidx.compose.material3.Text("연결 정보를 읽지 못했어요") },
            text = { androidx.compose.material3.Text("이 휴대폰의 연결 정보를 지우고 새로 연결할까요? 돌봄 기록은 그대로 남아요. 전에 연결했던 이 휴대폰은 차 화면에서 지워 주세요.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { model.resetCredsAndPair(r.code) }) { androidx.compose.material3.Text("지우고 연결하기") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { model.clearPairRun() }) { androidx.compose.material3.Text("취소") } },
        )
    }
    // U25 b: 설정에서 끊기가 끝나면, 그사이 정보·보호자 같은 다른 화면을 열었어도 탭으로 돌아간다. 오늘 탭 고르기는 CareTabs가 한다.
    val disconnect by model.disconnectRun.collectAsState()
    // 이력에 탭이 없으면(완료·확인·GONE 화면에서 복원 등) 탭으로 새로 간다.
    LaunchedEffect(disconnect) {
        if (disconnect?.let { !it.busy && it.error == null } == true && !nav.popBackStack("home", inclusive = false)) nav.reset("home")
    }
    // 코드는 확인 화면 경로에 실렸으니 바로 비운다. 다시 그려도 확인 화면을 또 띄우지 않는다.
    LaunchedEffect(pending) {
        pending?.let {
            // 직접 친 코드의 요청이 진행 중이면 확인 화면으로 옮기지 않는다(끝난 결과를 받을 화면이 사라진다).
            if (!model.canTakeLink()) android.widget.Toast.makeText(ctx, PAIR_BUSY, android.widget.Toast.LENGTH_SHORT).show()
            // 첫 실행이면 무엇이 오가는지 안내 한 화면을 먼저 보인다(7.1, U62).
            else if (nav.currentDestination?.route == "intro") introCode = it
            else nav.navigate("confirm/$it")
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
            composable("intro") {
                IntroScreen(
                    onConnect = { introCode?.let { c -> introCode = null; nav.navigate("confirm/$c") } ?: nav.navigate("connect") },
                    onRecordsOnly = { introCode = null; model.chooseRecordsOnly(); nav.reset("home") },
                )
            }
            composable("connect") {
                // 탭(연결 안 됨)에서 열었으면 '취소'로 돌아간다(U39). 첫 실행 흐름에서는 소개로 돌아가는 뒤로 가기만 있다.
                val fromTabs = nav.previousBackStackEntry?.destination?.route == "home"
                ConnectScreen(
                    model,
                    onCode = { code -> nav.navigate("confirm/$code") },
                    onPaired = { role -> nav.reset("done/${role.name}") },
                    onCancel = if (fromTabs) ({ nav.popBackStack() }) else null,
                )
            }
            composable("confirm/{code}") { e ->
                val code = e.arguments?.getString("code").orEmpty()
                ConfirmScreen(
                    model,
                    code,
                    onDone = { role -> nav.reset("done/${role.name}") },
                    onRetry = {
                        model.clearPairRun()
                        // 밑에 연결 화면이 있으면 그리로 돌아간다(탭에서 열었으면 '취소'가 남는다, U39). 링크로 바로 왔으면 확인 화면을 바꾼다.
                        if (!nav.popBackStack("connect", inclusive = false)) nav.navigate("connect") { popUpTo(e.destination.id) { inclusive = true } }
                    },
                )
            }
            composable("done/{role}") { e ->
                val role = if (e.arguments?.getString("role") == Role.GUARDIAN.name) Role.GUARDIAN else Role.OWNER
                DoneScreen(role) {
                    if (model.pairDoneShown()) nav.reset("home") else android.widget.Toast.makeText(ctx, SAVE_FAILED, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            // U25 a: 차·중계 쪽에서 끊김. 다시 연결하거나 탭(연결 안 됨)에서 기록을 계속 쓴다.
            composable("gone") {
                val recordsOnly = {
                    if (model.acknowledgeGone(recordsOnly = true)) nav.reset("home") else android.widget.Toast.makeText(ctx, SAVE_FAILED, android.widget.Toast.LENGTH_SHORT).show()
                }
                // U25 a: 뒤 버튼은 탭(연결 안 됨)으로 간다(이력이 끊김 화면 하나라 그냥 두면 앱이 끝난다).
                BackHandler { recordsOnly() }
                GoneScreen(
                    // 고른 결과를 저장하지 못했으면 이 화면에 남고 알린다(다음 실행에 같은 선택을 다시 묻지 않게 다시 누르게).
                    onReconnect = { if (model.acknowledgeGone()) nav.reset("connect") else android.widget.Toast.makeText(ctx, SAVE_FAILED, android.widget.Toast.LENGTH_SHORT).show() },
                    onRecordsOnly = recordsOnly,
                )
            }
            // 탭(5장): 연결 안 됨·owner·guardian이 같은 경로를 쓰고 탭 모양만 다르다.
            composable("home") { CareTabs(model) { nav.navigate(it) } }
            composable("timer/{id}") { e -> TimerScreen(model, e.arguments?.getString("id").orEmpty()) { nav.popBackStack() } }
            composable("about") { AboutScreen({ nav.popBackStack() }) { nav.navigate("license/$it") } }
            composable("license/{i}") { e -> LicenseScreen(e.arguments?.getString("i")?.toIntOrNull() ?: -1) { nav.popBackStack() } }
            composable("songs") { SongsScreen(model) { nav.popBackStack() } }
            composable("reports") { ReportsScreen(model, { nav.popBackStack() }) { nav.navigate("report/$it") } }
            composable("report/{id}") { e -> ReportDetailScreen(model, e.arguments?.getString("id").orEmpty()) { nav.popBackStack() } }
            composable("guardians") { GuardiansScreen(model) { nav.popBackStack() } }
            composable("alert/{id}") { e -> AlertScreen(model, e.arguments?.getString("id").orEmpty()) { nav.popBackStack() } }
        }
        // 앱 안 배너(5.6): 앞에 있을 때 온 인계 알림. 시스템 알림은 띄우지 않는다.
        ui.banner?.let { text ->
            Card(Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopCenter).semantics { liveRegion = LiveRegionMode.Polite }) {
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
