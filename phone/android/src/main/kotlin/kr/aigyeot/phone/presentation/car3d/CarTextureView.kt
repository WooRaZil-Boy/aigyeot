package kr.aigyeot.phone.presentation.car3d

import android.annotation.SuppressLint
import android.content.Context
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.FilamentHelper
import com.google.android.filament.android.UiHelper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kr.aigyeot.phone.presentation.Motion

/** 카메라 자리(시안 js/car3d.js PRESET 중 폰이 쓰는 둘. 차 좌표: 앞 +X, 위 +Y, 오른쪽 +Z, az는 앞에서 왼쪽으로 도는 각). */
enum class CarPose(val az: Float, val el: Float, val d: Float, val r: Float, val fov: Float, val tx: Float, val ty: Float, val tz: Float) {
    /** 오늘·차 탭 카드. */
    TILE(34f, 13f, 8.3f, 2.75f, 26f, 0.05f, 0.58f, 0f),
    /** 첫 안내 무대. */
    PHONE(34f, 14f, 10.6f, 3.0f, 26f, 0.05f, 0.6f, 0f),
    ;

    /** 세로로 긴 화면에서는 반폭 [r]이 가로로 들어오게 물러난다(시안 fitD). */
    fun distance(aspect: Float): Float = max(d, r / tan(atan(tan(fov * D2R / 2) * aspect)))
}

private const val D2R = (PI / 180).toFloat()

/**
 * 3D 차 보기 하나(TextureView 하나, 투명 바탕). 그리기는 필요할 때만: 처음 한 장, 크기·테마가 바뀔 때, 손가락으로 돌리는 동안과 감쇠(0.09),
 * '처음 자리로' 이동(1.1초) 동안만 다음 프레임을 잇고 그 밖에는 0프레임이다(레시피 12). 메인 스레드 전용. 화면을 떠나면 [destroy].
 * 돌리기는 방위만 바꾼다(가로 끌기; 세로 끌기는 화면 굴리기에 넘긴다, 손가락 처리는 Compose 쪽).
 */
@SuppressLint("ViewConstructor")
class CarTextureView(context: Context, private val pose: CarPose, private val cs: CarScene) : TextureView(context), Choreographer.FrameCallback {
    private val engine = cs.engine
    private val renderer: Renderer = engine.createRenderer().apply {
        clearOptions = clearOptions.apply { clear = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
    }
    private val view: View = engine.createView().apply {
        scene = cs.scene
        blendMode = View.BlendMode.TRANSLUCENT
        colorGrading = cs.colorGrading
        isShadowingEnabled = false
        multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
    }
    private val camEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(camEntity).apply { setExposure(1f) }.also { view.camera = it }
    private var swapChain: SwapChain? = null
    private var aspect = 1.6f
    private var az = pose.az
    /** 아직 돌지 않은 회전량(도). 시안 OrbitControls처럼 끌기는 쌓고, 프레임마다 남은 양의 0.09씩 돈다(감쇠). */
    private var pending = 0f
    private var lastFrame = 0L
    private var back: Back? = null
    private var posted = false
    private var destroyed = false

    var dark = false
        set(v) { if (field != v) { field = v; invalidate3d() } }

    /** 손으로 돌려 처음 자리가 아닌가('처음 자리로' 단추를 보일지). */
    var onMoved: ((Boolean) -> Unit)? = null
    private var moved = false

    private class Back(val from: Float, val to: Float, val start: Long)

    private val ui = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
        isOpaque = false
        renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, swapChainFlags)
                invalidate3d()
            }
            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
                swapChain = null
            }
            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
                aspect = width.toFloat() / max(1, height)
                invalidate3d()
            }
        }
    }

    /** 첫 그림(U90): 그린 장이 처음 TextureView에 들어왔을 때 한 번 부른다(메인 스레드). */
    var onFirstFrame: (() -> Unit)? = null
    private var shown = false

    init {
        ui.attachTo(this)
        isOpaque = false
        // UiHelper가 단 수신기를 감싸 첫 onSurfaceTextureUpdated를 안다(차 앱 CarView와 같은 방법). SwapChain 다 그림 알림은 에뮬레이터 OpenGL에서 오지 않는다.
        val inner = surfaceTextureListener
        if (inner != null) surfaceTextureListener = object : SurfaceTextureListener by inner {
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
                inner.onSurfaceTextureUpdated(st)
                if (!shown) { shown = true; onFirstFrame?.invoke() }
            }
        }
    }

    /** TextureView는 표면 크기를 바꾸기 전에 그리던 프레임을 끝내야 한다(FilamentHelper 안내: onResized에서는 늦다). */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!destroyed) FilamentHelper.synchronizePendingFrames(engine)
        super.onSizeChanged(w, h, oldw, oldh)
    }

    /**
     * 가로 끌기 dx(px)(시안 OrbitControls: 보기 높이만큼 끌면 2π × 0.55 = 198도, 오른쪽으로 끌면 방위가 줄어든다).
     * 동작 줄이기면 쌓지 않고 바로 돈다.
     */
    fun drag(dxPx: Float) {
        if (back != null) return
        val d = -dxPx * 0.55f * 360f / max(1, height)
        if (reduced) az = (az + d) % 360f else pending += d
        setMoved(true)
        invalidate3d()
    }

    /** 손을 뗐다: 남은 회전량은 감쇠로 마저 돈다. */
    fun release() = invalidate3d()

    /** 동작 줄이기: 켜지면 남은 관성·되돌리기 이동을 끝 상태로 바로 둔다. */
    var reduced = false
        set(v) {
            if (field == v) return
            field = v
            if (v) { az = (az + pending) % 360f; pending = 0f; back?.let { az = pose.az; back = null }; invalidate3d() }
        }

    /** '처음 자리로'(1.1초, 동작 줄이기면 바로). */
    fun reset(animate: Boolean) {
        pending = 0f
        setMoved(false)
        if (!animate) { az = pose.az; invalidate3d(); return }
        val to = az + (((pose.az - az + 540f) % 360f) - 180f)
        back = Back(az, to, System.nanoTime())
        invalidate3d()
    }

    private fun setMoved(m: Boolean) { if (moved != m) { moved = m; onMoved?.invoke(m) } }

    private fun invalidate3d() { if (!posted && !destroyed) { posted = true; Choreographer.getInstance().postFrameCallback(this) } }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        val sc = swapChain ?: return
        var more = false
        back?.let { b ->
            val k = min(1f, (System.nanoTime() - b.start) / 1e6f / Motion.camera.ms)
            val e = Motion.camera.easing.transform(k) // 시안 카메라 cb(.65,0,.35,1)
            az = b.from + (b.to - b.from) * e
            if (k >= 1f) { back = null; az = pose.az } else more = true
        }
        // 감쇠: 프레임 간격(60Hz 기준 배수)만큼 남은 회전량을 줄인다(화면 주사율과 무관하게 같은 시간에 멈춘다).
        val f = if (lastFrame == 0L) 1f else ((frameTimeNanos - lastFrame) / 1e6f / FRAME_MS).coerceIn(0.25f, 4f)
        lastFrame = frameTimeNanos
        if (back == null && abs(pending) >= 0.01f) {
            val step = pending * (1 - (1 - 0.09f).pow(f))
            az = (az + step) % 360f
            pending -= step
            more = true
        } else pending = 0f
        cs.theme(dark)
        val dist = pose.distance(aspect)
        val a = az * D2R; val e = pose.el * D2R
        camera.setProjection(pose.fov.toDouble(), aspect.toDouble(), 0.1, 80.0, Camera.Fov.VERTICAL)
        camera.lookAt(
            (cos(e) * cos(a) * dist + pose.tx).toDouble(), (sin(e) * dist + pose.ty).toDouble(), (-cos(e) * sin(a) * dist + pose.tz).toDouble(),
            pose.tx.toDouble(), pose.ty.toDouble(), pose.tz.toDouble(), 0.0, 1.0, 0.0,
        )
        val rendered = renderer.beginFrame(sc, frameTimeNanos).also { if (it) { renderer.render(view); renderer.endFrame() } }
        if (more || !rendered) invalidate3d() else lastFrame = 0L
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        Choreographer.getInstance().removeFrameCallback(this)
        ui.detach()
        swapChain?.let { engine.destroySwapChain(it) }
        engine.destroyRenderer(renderer); engine.destroyView(view); engine.destroyCameraComponent(camEntity)
        EntityManager.get().destroy(camEntity)
    }

    companion object {
        private const val FRAME_MS = 1000f / 60f
    }
}
