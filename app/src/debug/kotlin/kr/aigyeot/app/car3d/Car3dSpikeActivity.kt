package kr.aigyeot.app.car3d

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import kr.aigyeot.app.ui.car3d.CarEngine
import kr.aigyeot.app.ui.car3d.CarView
import kr.aigyeot.app.ui.car3d.Callout3d
import kr.aigyeot.app.ui.car3d.Light3d
import kr.aigyeot.app.ui.car3d.Stage3d
import kr.aigyeot.app.ui.car3d.View3d

// debug 전용(3D 실측, 디자인 v6 R1~R4): 홈 무대와 같은 크기의 3D 보기 하나를 띄우고 카메라 이동·대기 프레임을 잰다. 태그 CAR3D.
//   am start --user 10 -n kr.aigyeot.app/.car3d.Car3dSpikeActivity [--es view rear|hero|top|did] [--es light listen|soothe|alert|base]
//      [--es callouts lock,heat,dim] [--ez ripple true] [--ez drive true] [--ez dark true]
// 이미 떠 있으면 같은 명령이 onNewIntent로 상태만 바꾼다(카메라가 1.1초 날아간다). 비행 끝에 프레임 요약, 그 뒤 5초 동안 그린 프레임 수를 찍는다.
class Car3dSpikeActivity : Activity() {
    private lateinit var car: CarView
    private val frames = ArrayList<Long>()
    private val cpu = ArrayList<Double>()
    private var measuring = false
    private var idleFrom = 0L
    private var idleCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarEngine.debugNoAo = intent.getBooleanExtra("noao", false)   // 엔진 준비는 CarView가 기다린다(CarEngine 로그에 단계별 시간)
        car = CarView(this, interactive = true)
        car.dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES || intent.getBooleanExtra("dark", false)
        car.onFrame = { ft, ms, rendered ->
            if (measuring) { frames += ft; cpu += ms }
            if (idleFrom > 0 && rendered) idleCount++
        }
        setContentView(FrameLayout(this).apply { setBackgroundColor(0xFFE9ECEF.toInt()); addView(car, FrameLayout.LayoutParams(1706 - 600 - 36, 1210 - 112)) })
        apply(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); apply(intent) }

    private fun apply(i: Intent) {
        val s = Stage3d(
            view = View3d.valueOf((i.getStringExtra("view") ?: "hero").uppercase()),
            light = Light3d.valueOf((i.getStringExtra("light") ?: "listen").uppercase()),
            ripple = i.getBooleanExtra("ripple", false),
            callouts = i.getStringExtra("callouts").orEmpty().split(',').filter { it.isNotBlank() }.map { Callout3d.valueOf(it.uppercase()) }.toSet(),
            drive = i.getBooleanExtra("drive", false),
        )
        if (i.hasExtra("dark")) car.dark = i.getBooleanExtra("dark", false)
        frames.clear(); cpu.clear(); measuring = true; idleFrom = 0; idleCount = 0
        Log.i(TAG, "set $s")
        car.set(s) {
            measuring = false
            val iv = frames.zipWithNext { a, b -> (b - a) / 1e6 }
            Log.i(TAG, "fly frames=${frames.size} interval avg=${"%.1f".format(iv.average())} max=${"%.1f".format(iv.maxOrNull() ?: 0.0)} ms; cpu avg=${"%.2f".format(cpu.average())} max=${"%.2f".format(cpu.maxOrNull() ?: 0.0)} ms; >33ms=${iv.count { it > 33.4 }}")
            idleFrom = SystemClock.elapsedRealtime()
            car.postDelayed({ Log.i(TAG, "idle 5s rendered=$idleCount (ripple=${s.ripple})"); idleFrom = 0 }, 5000)
        }
    }

    override fun onDestroy() { car.destroy(); super.onDestroy() }

    private companion object { const val TAG = "CAR3D" }
}
