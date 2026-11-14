package kr.aigyeot.app.car3d

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import java.io.File
import kr.aigyeot.app.ui.AppTheme
import kr.aigyeot.app.ui.CarStage
import kr.aigyeot.app.ui.LocalPalette
import kr.aigyeot.app.ui.car3d.Callout3d
import kr.aigyeot.app.ui.car3d.CarEngine
import kr.aigyeot.app.ui.car3d.CarView
import kr.aigyeot.app.ui.car3d.Light3d
import kr.aigyeot.app.ui.car3d.Seat3d
import kr.aigyeot.app.ui.car3d.Stage3d
import kr.aigyeot.app.ui.car3d.View3d

// debug 전용(3D 실측): 홈 무대와 같은 크기의 3D 보기 하나를 띄우고 카메라 이동·대기 프레임을 잰다. 태그 CAR3D.
//   am start --user 10 -n kr.aigyeot.app/.car3d.Car3dSpikeActivity [--es view rear|hero|top|did] [--es light listen|soothe|alert|base]
//      [--es callouts lock,heat,dim] [--ez ripple true] [--ez drive true] [--ez dark true]
//      [--es seat rl|rc|rr] [--ez noseat true] [--ei w 998 --ei h 1097] [--es capture 이름]
// 이미 떠 있으면 같은 명령이 onNewIntent로 상태만 바꾼다(카메라가 1.1초 날아간다). 비행 끝에 프레임 요약, 그 뒤 5초 동안 그린 프레임 수를 찍는다.
// capture: 이동 없이 그 상태를 그리고, 첫 장이 화면에 들어온 뒤 0.8초에 보기 그림(투명 바탕)을 files/<이름>.png로 남긴다(홈 무대 정지 그림 만들기, CarStage Poster).
//   꺼내기: adb exec-out run-as kr.aigyeot.app --user 10 cat files/<이름>.png > <이름>.png
class Car3dSpikeActivity : Activity() {
    private lateinit var car: CarView
    private val frames = ArrayList<Long>()
    private val cpu = ArrayList<Double>()
    private var measuring = false
    private var idleFrom = 0L
    private var idleCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarEngine.debugNoAo = intent.getBooleanExtra("noao", false) // 엔진 준비는 CarView가 기다린다(CarEngine 로그에 단계별 시간)
        CarEngine.debugNoChildSeat = intent.getBooleanExtra("noseat", false)
        car = CarView(this, interactive = true)
        car.dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ||
            intent.getBooleanExtra("dark", false)
        car.onFrame = { ft, ms, rendered ->
            if (measuring) {
                frames += ft
                cpu += ms
            }
            if (idleFrom > 0 && rendered) idleCount++
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(0xFFE9ECEF.toInt())
                addView(car, FrameLayout.LayoutParams(intent.getIntExtra("w", 1706 - 600 - 36), intent.getIntExtra("h", 1210 - 112)))
            }
        )
        intent.getStringExtra("capture")?.let { name ->
            car.onFirstFrame = {
                car.postDelayed({
                    val b = car.bitmap ?: run {
                        Log.e(TAG, "capture $name: 그림 없음")
                        return@postDelayed
                    }
                    File(filesDir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Log.i(TAG, "capture $name ${b.width}x${b.height} alpha=${b.hasAlpha()}")
                }, 800)
            }
        }
        apply(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        apply(intent)
    }

    private fun apply(i: Intent) {
        val s = Stage3d(
            view = View3d.valueOf((i.getStringExtra("view") ?: "hero").uppercase()),
            light = Light3d.valueOf((i.getStringExtra("light") ?: "listen").uppercase()),
            ripple = i.getBooleanExtra("ripple", false),
            callouts = i.getStringExtra("callouts").orEmpty().split(',').filter { it.isNotBlank() }.map { Callout3d.valueOf(it.uppercase()) }.toSet(),
            drive = i.getBooleanExtra("drive", false),
            seat = Seat3d.valueOf((i.getStringExtra("seat") ?: "rr").uppercase())
        )
        if (i.hasExtra("dark")) car.dark = i.getBooleanExtra("dark", false)
        frames.clear()
        cpu.clear()
        measuring = true
        idleFrom = 0
        idleCount = 0
        Log.i(TAG, "set $s")
        car.set(s, animate = !i.hasExtra("capture")) {
            measuring = false
            val iv = frames.zipWithNext { a, b -> (b - a) / 1e6 }
            Log.i(
                TAG,
                "fly frames=${frames.size} interval avg=${"%.1f".format(iv.average())} max=${"%.1f".format(iv.maxOrNull() ?: 0.0)} ms; " +
                    "cpu avg=${"%.2f".format(cpu.average())} max=${"%.2f".format(cpu.maxOrNull() ?: 0.0)} ms; >33ms=${iv.count { it > 33.4 }}"
            )
            idleFrom = SystemClock.elapsedRealtime()
            car.postDelayed({
                Log.i(TAG, "idle 5s rendered=$idleCount (ripple=${s.ripple})")
                idleFrom = 0
            }, 5000)
        }
    }

    override fun onDestroy() {
        car.destroy()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "CAR3D"
    }
}

/**
 * debug 전용(화면 대조): 홈 무대 그리기([CarStage], 칩·지시선 포함)를 같은 크기로 띄운다. 정차 중 달래기 X-ray처럼 실제 흐름으로 만들기 어려운 그림을 찍는다.
 *   am start --user 10 -n kr.aigyeot.app/.car3d.Car3dStageActivity --es view rear --es light soothe
 *   --es callouts heat,lock,dim --ez ripple true [--es seat rl|rc|rr]
 * 다크는 시스템 설정(cmd uimode night yes)을 따른다.
 */
class Car3dStageActivity : ComponentActivity() {
    private val stage = androidx.compose.runtime.mutableStateOf(Stage3d())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stage.value = stageOf(intent)
        setContent {
            AppTheme {
                val d = LocalDensity.current
                Box(Modifier.fillMaxSize().background(LocalPalette.current.stage)) {
                    CarStage(stage.value, with(d) { Modifier.size((1706 - 600 - 36).toDp(), (1210 - 112).toDp()) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stage.value = stageOf(intent)
    }

    private fun stageOf(i: Intent) = Stage3d(
        view = View3d.valueOf((i.getStringExtra("view") ?: "hero").uppercase()),
        light = Light3d.valueOf((i.getStringExtra("light") ?: "base").uppercase()),
        ripple = i.getBooleanExtra("ripple", false),
        callouts = i.getStringExtra("callouts").orEmpty().split(',').filter { it.isNotBlank() }.map { Callout3d.valueOf(it.uppercase()) }.toSet(),
        drive = i.getBooleanExtra("drive", false),
        seat = Seat3d.valueOf((i.getStringExtra("seat") ?: "rr").uppercase())
    )
}
