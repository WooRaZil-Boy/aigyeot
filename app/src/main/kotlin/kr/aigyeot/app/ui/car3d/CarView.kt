package kr.aigyeot.app.ui.car3d

import kr.aigyeot.app.ui.Tokens
import android.annotation.SuppressLint
import android.content.Context
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.FilamentHelper
import com.google.android.filament.android.UiHelper
import kotlin.math.max
import kotlin.math.min

/**
 * 3D 보기 하나(TextureView 하나). 그리기는 필요할 때만: 카메라 이동·손가락 회전(감쇠)·소리 결·빛 맥박이 실제로 움직일 때만 다음 프레임을 잇고,
 * 그 밖에는 0프레임(시안 car3d.js View). 주행 중([Stage3d.drive])·동작 줄이기면 조작을 끄고 상태가 바뀔 때 한 장만 그린다.
 * 메인 스레드 전용. 화면을 떠나면 [destroy].
 */
@SuppressLint("ViewConstructor")
class CarView(context: Context, private val interactive: Boolean) : TextureView(context), Choreographer.FrameCallback {
    // 엔진은 처음 한 번 작업 스레드 준비 + 메인 스레드 단계 양보로 만든다(CarEngine.whenReady). 그 전에는 투명(아무것도 그리지 않는다)
    private lateinit var ce: CarEngine
    private lateinit var engine: com.google.android.filament.Engine
    private lateinit var renderer: Renderer
    private lateinit var view: View
    private var camEntity = 0   // 엔진 준비 뒤 만든다(그 전에는 Filament 네이티브 라이브러리가 없다)
    private lateinit var camera: Camera
    private var ready = false
    private var swapChain: SwapChain? = null
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
            override fun onResized(width: Int, height: Int) = resized(width, height)
        }
    }

    var dark = false
        set(v) { if (field != v) { field = v; invalidate3d() } }
    private var aspect = 1.6f
    var state = Stage3d(); private set
    private var cam = CarMath.cam(View3d.HERO, Seat3d.RR, aspect)
    private var xray = 0f
    private var fly: Fly? = null
    private var userMoved = false
    /** 손으로 돌렸는가('처음 자리로' 단추를 보일지). */
    var onMoved: ((Boolean) -> Unit)? = null
    /** 그릴 때마다(앵커 화면 좌표를 다시 잴 때). */
    var onRender: (() -> Unit)? = null
    /** 프레임 시간 측정(스파이크·성능 기록용): 이 프레임의 CPU ms. */
    var onFrame: ((frameNanos: Long, cpuMs: Double, rendered: Boolean) -> Unit)? = null

    // 한 번 움직이는 효과는 '시작 시각'만 들고 진행은 그릴 때 지금 상태에서 계산한다(상태가 바뀌면 맞지 않는 효과는 저절로 사라진다).
    // 열선 꺼짐 1.6초·잠금 테 0.9초는 그 칩이 새로 생길 때, 울음 감지 빛 맥박 1.2초는 경보에 들어가 카메라가 닿은 뒤.
    private val fx = Effects()
    private val t0 = System.nanoTime()
    private var posted = false
    private var destroyed = false

    private data class Fly(val from: Cam, val to: Cam, val x0: Float, val x1: Float, val start: Long, val ms: Int, val done: () -> Unit)

    private fun resized(width: Int, height: Int) {
        view.viewport = Viewport(0, 0, width, height)
        aspect = width.toFloat() / max(1, height)
        // 이동 중이면 목표도 새 비율로(레이아웃 전에 시작한 이동이 옛 비율 거리로 도착하지 않게)
        fly?.let { f -> fly = f.copy(to = CarMath.cam(state.view, state.seat, aspect)) } ?: run { if (!userMoved) cam = CarMath.cam(state.view, state.seat, aspect) }
        invalidate3d()
    }

    /** 처음 만들 때부터 첫 장을 그릴 때까지(ms, 성능 기록용). */
    var firstFrameMs: Long? = null; private set
    private val born = System.nanoTime()

    init {
        isOpaque = false
        CarEngine.whenReady(context) { e ->
            if (destroyed) return@whenReady
            ce = e; engine = e.engine
            renderer = engine.createRenderer().apply { clearOptions = clearOptions.apply { clear = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0) } }
            view = engine.createView().apply {
                scene = e.scene
                blendMode = View.BlendMode.TRANSLUCENT
                colorGrading = e.colorGrading
                isShadowingEnabled = false
                multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
            }
            camEntity = com.google.android.filament.EntityManager.get().create()
            camera = engine.createCamera(camEntity).apply { setExposure(1f) }.also { view.camera = it }
            ready = true
            ui.attachTo(this)   // 표면이 이미 있으면 onNativeWindowChanged는 오지만 onResized는 오지 않을 수 있어 아래에서 크기를 맞춘다
            if (width > 0 && height > 0) resized(width, height)
            invalidate3d()
        }
    }

    /** TextureView는 표면 크기를 바꾸기 전에 그리던 프레임을 끝내야 한다(FilamentHelper 안내: onResized에서는 늦다). 엔진 준비 전·놓은 뒤에는 부르지 않는다. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (ready && !destroyed) FilamentHelper.synchronizePendingFrames(engine)
        super.onSizeChanged(w, h, oldw, oldh)
    }

    /** 상태를 바꾼다. 카메라 자리가 바뀌면 날아가고(정차 중·동작 켜짐 1.1초, 아니면 바로), 새로 켜진 열선·잠금은 한 번 움직인다. */
    fun set(s: Stage3d, animate: Boolean = true, onArrive: () -> Unit = {}) {
        val before = state
        state = s
        val ok = animate && s.motionOk
        val now = System.nanoTime()
        if (!s.motionOk) dragging = false
        val move = s.view != before.view || s.seat != before.seat || s.drive || (fly != null && !ok)
        val ongoing = fly
        val arriveIn = when {
            move && ok -> CAMERA_MS * 1_000_000L
            move -> 0L
            ongoing != null -> (ongoing.start + ongoing.ms * 1_000_000L - now).coerceAtLeast(0)   // 같은 자리로 가는 중: 닿을 때까지 기다린다
            else -> 0L
        }
        fx.onSet(before, s, ok, arriveIn, now)
        when {
            move -> flyTo(s.view, ok, onArrive)
            ongoing != null -> fly = ongoing.copy(done = onArrive)   // 도착 알림은 지금 상태의 것으로(옛 상태의 칩을 띄우지 않게)
            else -> onArrive()
        }
        invalidate3d()
    }

    /** '처음 자리로': 지금 상태의 자리로 날아간다. */
    fun reset() { dragging = false; flyTo(state.view, state.motionOk) {} }   // 끄는 중 눌러도 이동이 멈추지 않게

    private fun flyTo(v: View3d, animate: Boolean, done: () -> Unit) {
        val to = CarMath.cam(v, state.seat, aspect)
        setMoved(false)
        vAz = 0f; vEl = 0f   // 손가락 회전 관성이 자동 이동에 섞이지 않게
        if (!animate) { fly = null; cam = to; xray = v.preset.xray; done(); invalidate3d(); return }
        fly = Fly(cam, to, xray, v.preset.xray, System.nanoTime(), CAMERA_MS, done)
        invalidate3d()
    }

    private fun setMoved(m: Boolean) { if (userMoved != m) { userMoved = m; onMoved?.invoke(m) } }

    /** 앵커 → 이 보기 안 픽셀 좌표(x, y, 거리). 화면 뒤면 null. */
    fun project(anchor: String): FloatArray? {
        if (!ready) return null
        val p = ce.anchor(anchor)
        val v = camera.getViewMatrix(DoubleArray(16)); val pr = camera.getProjectionMatrix(DoubleArray(16))
        val vx = v[0] * p[0] + v[4] * p[1] + v[8] * p[2] + v[12]; val vy = v[1] * p[0] + v[5] * p[1] + v[9] * p[2] + v[13]
        val vz = v[2] * p[0] + v[6] * p[1] + v[10] * p[2] + v[14]
        val cx = pr[0] * vx + pr[4] * vy + pr[8] * vz + pr[12]; val cy = pr[1] * vx + pr[5] * vy + pr[9] * vz + pr[13]
        val cw = pr[3] * vx + pr[7] * vy + pr[11] * vz + pr[15]
        if (cw <= 0) return null
        val e = CarMath.eye(cam)
        val d = Math.sqrt(((p[0] - e[0]) * (p[0] - e[0]) + (p[1] - e[1]) * (p[1] - e[1]) + (p[2] - e[2]) * (p[2] - e[2])).toDouble())
        return floatArrayOf(((cx / cw + 1) / 2 * width).toFloat(), ((1 - cy / cw) / 2 * height).toFloat(), d.toFloat())
    }

    fun invalidate3d() { if (!posted && !destroyed) { posted = true; Choreographer.getInstance().postFrameCallback(this) } }

    /** 화면이 숨으면(액티비티 멈춤·다른 화면) 프레임을 잇지 않는다. 다시 보이면 한 장부터 다시. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate3d()
    }

    /** 이 보기나 부모가 다시 보이면(창은 그대로) 숨은 동안 건너뛴 그림을 한 장부터 다시. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate3d()
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (!ready || windowVisibility != VISIBLE || !isShown) return   // 준비 전·숨은 동안 0프레임(소리 결·바람 결도 멈춤), 보일 때 onWindowVisibilityChanged가 다시 부른다
        val sc = swapChain ?: return
        val t = System.nanoTime()
        var more = false
        fly?.let { f ->
            val k = min(1f, (t - f.start) / 1e6f / f.ms); val e = CAMERA_EASE.transform(k)
            cam = CarMath.lerp(f.from, f.to, e); xray = f.x0 + (f.x1 - f.x0) * e
            if (k >= 1f) { fly = null; cam = f.to; xray = f.x1; f.done() } else more = true
        }
        val f = fx.at(t)
        if (f.running) more = true
        if (spin()) more = true
        val ripple = if (state.ripple && state.motionOk) ((t - t0) / 1e6f / RIPPLE_MS) % 1f else null   // 소리 결 한 주기 = 토큰 motion.ripple
        if (ripple != null) more = true
        val hvac = Callout3d.HVAC in state.callouts
        if (hvac && state.motionOk && xray > 0.3f) more = true   // 바람 결이 실제로 보일 때만(실내 보기) 다음 프레임
        ce.apply(state, xray, dark, f.heatWarm, f.lockK, ripple, f.pulseK, flowT = if (hvac && state.motionOk) (t - t0) / 1e9f / 1.4f else 0f)
        val e = CarMath.eye(cam)
        camera.setProjection(cam.fov.toDouble(), aspect.toDouble(), 0.1, 80.0, Camera.Fov.VERTICAL)
        camera.lookAt(e[0].toDouble(), e[1].toDouble(), e[2].toDouble(), cam.t[0].toDouble(), cam.t[1].toDouble(), cam.t[2].toDouble(), 0.0, 1.0, 0.0)
        var rendered = false
        if (renderer.beginFrame(sc, frameTimeNanos)) { renderer.render(view); renderer.endFrame(); rendered = true }
        if (rendered && firstFrameMs == null) { firstFrameMs = (System.nanoTime() - born) / 1_000_000; android.util.Log.i("CarView", "첫 장 ${firstFrameMs}ms") }
        onFrame?.invoke(frameTimeNanos, (System.nanoTime() - t) / 1e6, rendered)
        onRender?.invoke()
        if (more || !rendered) invalidate3d()
    }

    // 손가락 회전(정차 중만, 감쇠 0.09) ───────────
    private var lastX = 0f; private var lastY = 0f; private var vAz = 0f; private var vEl = 0f; private var dragging = false; private var lastMoveAt = 0L

    private fun spin(): Boolean {
        if (!state.motionOk || fly != null) { if (!state.motionOk) dragging = false; vAz = 0f; vEl = 0f; return false }
        if (dragging) return true
        if (kotlin.math.abs(vAz) < 0.01f && kotlin.math.abs(vEl) < 0.01f) return false
        rotate(vAz, vEl); vAz *= 1 - 0.09f; vEl *= 1 - 0.09f
        return true
    }

    private fun rotate(daz: Float, del: Float) { cam = cam.copy(az = (cam.az + daz) % 360f, el = (cam.el + del).coerceIn(4f, 84f)) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val end = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
        if (end && dragging) {   // 끄는 중 주행으로 바뀌어도 손을 뗀 것은 받는다
            dragging = false
            if (ev.eventTime - lastMoveAt > 80) { vAz = 0f; vEl = 0f }   // 마지막 끌기 뒤 80ms 넘게 멈췄다 떼면 관성 없음(폰과 같은 값)
            invalidate3d(); return true
        }
        if (!interactive || !state.motionOk || fly != null) return false
        val k = 0.55f * 360f / max(1, height)   // 시안 OrbitControls rotateSpeed 0.55: 화면 높이만큼 끌면 약 200도(2π·dx/높이·0.55)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; lastX = ev.x; lastY = ev.y; lastMoveAt = ev.eventTime; vAz = 0f; vEl = 0f; setMoved(true); invalidate3d() }
            MotionEvent.ACTION_MOVE -> {
                // 오른쪽으로 끌면 방위가 줄어든다(three OrbitControls rotateLeft와 같은 방향), 아래로 끌면 위에서 내려다본다
                val daz = -(ev.x - lastX) * k; val del = (ev.y - lastY) * k
                // 관성은 시간으로 잰 속도(각/ms)를 프레임 길이(16.7ms)로: 이벤트가 몇 번에 나뉘어 오든 같은 회전
                val dt = (ev.eventTime - lastMoveAt).coerceIn(1, 100).toFloat()
                rotate(daz, del); vAz = daz / dt * 16.7f; vEl = del / dt * 16.7f; lastX = ev.x; lastY = ev.y; lastMoveAt = ev.eventTime; invalidate3d()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate3d() }
        }
        return true
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        Choreographer.getInstance().removeFrameCallback(this)
        if (ready) {
            ui.detach()
            swapChain?.let { engine.destroySwapChain(it) }
            engine.destroyRenderer(renderer); engine.destroyView(view); engine.destroyCameraComponent(camEntity)
            com.google.android.filament.EntityManager.get().destroy(camEntity)
        }
    }

    companion object {
        /** 카메라 이동(토큰 motion.camera: 1100ms, cubic-bezier(.65,0,.35,1)). */
        val CAMERA_MS = Tokens.motion.getValue("camera").ms
        private val CAMERA_EASE = Tokens.motion.getValue("camera").easing
        private val RIPPLE_MS = Tokens.motion.getValue("ripple").ms.toFloat()
    }
}
