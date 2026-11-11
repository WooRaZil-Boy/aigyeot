package kr.aigyeot.phone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kr.aigyeot.phone.presentation.AigyeotTheme
import kr.aigyeot.phone.presentation.AppRoot
import kr.aigyeot.phone.presentation.AppViewModel
import kr.aigyeot.phone.presentation.IntroMotion
import kr.aigyeot.phone.presentation.LocalViewModelFactory
import kr.aigyeot.phone.presentation.ProvideMotion

class MainActivity : ComponentActivity() {
    private val container get() = (application as AigyeotApp).container
    private val app: AppViewModel by viewModels { container.viewModels }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 앱이 앞에 있을 때만 붙는다(계약 2장). 뒤로 가면 끊고 멈춘다.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.lifecycle.onForeground()
            override fun onStop(owner: LifecycleOwner) = container.lifecycle.onBackground()
        })
        // 화면을 다시 만들 때(회전 등)는 처음 받은 인텐트를 이미 처리했다(7.6).
        // 새로 연 화면에는 지난 연결 결과를 받을 확인 화면이 없다: 실패·지난 결과를 먼저 비운다(U52, 7.1).
        if (savedInstanceState == null) {
            app.clearStalePairRun()
            handle(intent)
        }
        setContent {
            AigyeotTheme {
                ProvideMotion {
                    CompositionLocalProvider(LocalViewModelFactory provides container.viewModels) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot(app) } // 토큰 bg(탭 밖 화면도)
                    }
                }
            }
        }
    }

    /**
     * 창이 초점을 받았다: 시작 화면이 걷히고 앱 창이 보인다(에뮬레이터 녹화에서 시작 화면이 걷힌 뒤 약 0.1초. onEnterAnimationComplete는 이 기기에서 오지 않았다).
     * 첫 안내 등장 안무는 이때부터 보인다(v8b-10 ⑤).
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) IntroMotion.windowShown = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** 연결 링크(7.6)와 인계 알림 누름(5.3: msg_id가 extras로 온다). exported 액티비티라 다른 앱도 extras를 넣을 수 있다. */
    private fun handle(intent: Intent) {
        intent.data?.toString()?.let { link -> app.onLink(link)?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() } }
        intent.getStringExtra("msg_id")?.let(app::onAlert)
    }
}
