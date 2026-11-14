package kr.aigyeot.app.ui.car3d

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
import kr.aigyeot.app.ui.Tokens

/**
 * 3D 보기 하나(TextureView 하나). 그리기는 필요할 때만: 카메라 이동·손가락 회전(감쇠)·소리 결·빛 맥박이 실제로 움직일 때만 다음 프레임을 잇고,
 * 그 밖에는 0프레임. 주행 중([Stage3d.drive])·동작 줄이기면 조작을 끄고 상태가 바뀔 때 한 장만 그린다.
 * [intro]면(홈 무대) 앱 프로세스에서 처음 한 번, 첫 그림을 바깥 모습(HERO)으로 그리고 잠깐 멈췄다가 지금 상태 자리로 돌며 껍데기가 X-ray로 바뀐다.
 * 메인 스레드 전용. 화면을 떠나면 [destroy].
 */
@SuppressLint("ViewConstructor")
class CarView(context: Context, private val interactive: Boolean, intro: Boolean = false) :
    TextureView(context),
    Choreographer.FrameCallback {
    // 엔진은 처음 한 번 작업 스레드 준비 + 메인 스레드 단계 양보로 만든다(CarEngine.whenReady). 그 전에는 투명(아무것도 그리지 않는다)
    private lateinit var ce: CarEngine
    private lateinit var engine: com.google.android.filament.Engine
    private lateinit var renderer: Renderer
    private lateinit var view: View
    private var camEntity = 0 // 엔진 준비 뒤 만든다(그 전에는 Filament 네이티브 라이브러리가 없다)
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
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                }
                swapChain = null
            }
            override fun onResized(width: Int, height: Int) = resized(width, height)
        }
    }

    var dark = false
        set(v) {
            if (field != v) {
                field = v
                invalidate3d()
            }
        }
    private var aspect = 1.6f
    var state = Stage3d()
        private set

    // 첫 장부터 기본 상태의 자리·투명도로(처음 한 장이 다른 시점으로 깜빡이지 않게)
    private var cam = CarMath.cam(state.view, state.seat, aspect)
    private var xray = state.view.preset.xray

    // 시작 연출은 첫 그림 전까지 시작 시각을 비워 둔 이동으로 들고 있다(그 사이 set은 목표만 바꾼다). 첫 그림에서 할지 정한다(doFrame의 introAt)
    private var introArmed = intro && !introDone
    private var fly: Fly? = if (introArmed) Fly(cam, cam, 0f, xray, PENDING, INTRO_MS, {}, intro = true) else null

    /**
     * 시작 연출 앞 멈춤(바깥 모습). 바깥 모습 장을 내고 그 장이 TextureView에 들어오면(화면에 나옴) [warm]으로 넘어가 옅은 X-ray로 X-ray 셰이더를 만든다
     * (셰이더를 만드느라 장을 건너뛰는 동안 화면은 앞 장 그대로). [warmFrom]은 옅은 장을 그리기 시작한 시각이라 그 뒤 시각을 단 장이 옅은 X-ray 장이고,
     * [warmSeen]은 그런 장이 화면에 들어온 횟수다. [shownAt]은 바깥 모습이 화면에 나온 시각(최소 멈춤의 기준). null이면 멈춤이 아니다.
     */
    private class Hold(var warm: Boolean = false, var shownAt: Long = 0L, var warmFrom: Long = 0L, var warmSeen: Int = 0)
    private var hold: Hold? = null

    /** 화면(TextureView)에 들어온 장 수(받는 쪽, 첫 그림 알림에만 쓴다). 낸 장 수와 견주지 않는다: 받는 쪽은 가장 최근 장으로 건너뛰어 덜 센다. */
    private var framesDone = 0L
    private var userMoved = false

    /** 손으로 돌렸는가('처음 자리로' 단추를 보일지). */
    var onMoved: ((Boolean) -> Unit)? = null

    /** 그릴 때마다(앵커 화면 좌표를 다시 잴 때). */
    var onRender: (() -> Unit)? = null

    /** 프레임 시간 측정(스파이크·성능 기록용): 이 프레임의 CPU ms. */
    var onFrame: ((frameNanos: Long, cpuMs: Double, rendered: Boolean) -> Unit)? = null

    /** 첫 그림이 화면(TextureView)에 들어왔다(한 번, 메인 스레드). 준비 표시·홈 정지 그림이 이것으로 걷힌다. */
    var onFirstFrame: (() -> Unit)? = null

    /** 엔진 준비가 실패했다(한 번, 메인 스레드). 준비 표시가 실패 정지 표시로 바뀐다. */
    var onFailed: (() -> Unit)? = null

    /**
     * 시작 연출 앞 멈춤(바깥 모습 + 옅은 X-ray 셰이더 준비)이 끝난 뒤 처음 그린 장이 화면에 들어왔다(한 번). 홈 정지 그림은 멈춤 동안 3D를 덮고 이때 걷힌다:
     * 옅은 X-ray 장(껍데기 BLEND)은 바깥 모습보다 유리·그릴이 옅게 보여, 그대로 보이면 회전 직전 몇 장 깜빡인다(car3d_cabin_1111.txt 12절).
     */
    var onHoldEnd: (() -> Unit)? = null

    /** 시작 연출 앞 멈춤 중인가. */
    val holding get() = hold != null

    /** 멈춤을 끝낸 시각(그 뒤 그린 장에 단 시각과 견준다). 아직 알리지 않았으면 0 이상. */
    private var holdEndFrom = -1L

    /** 멈춤 끝 알림 안전망: 받는 쪽 신호가 끝내 오지 않아도(장 건너뜀·그리기 멈춤) 회전 길이 뒤에는 알린다(정지 그림이 남지 않게). */
    private val holdEndNet = Runnable { holdEnded() }

    // 한 번 움직이는 효과는 '시작 시각'만 들고 진행은 그릴 때 지금 상태에서 계산한다(상태가 바뀌면 맞지 않는 효과는 저절로 사라진다).
    // 열선 꺼짐 1.6초·잠금 테 0.9초는 그 칩이 새로 생길 때, 울음 감지 빛 맥박 1.2초는 경보에 들어가 카메라가 닿은 뒤.
    private val fx = Effects()
    private val t0 = System.nanoTime()
    private var posted = false
    private var destroyed = false

    /** 카메라 이동. [start]가 [PENDING]이면 첫 그림을 기다리는 시작 연출. [intro]는 앞 멈춤([hold])이 있고, 손으로 끌면 그 자리에서 넘겨준다. [cont]는 움직이던 중에 바뀐 목표([CONT_EASE]). */
    private data class Fly(
        val from: Cam,
        val to: Cam,
        val x0: Float,
        val x1: Float,
        val start: Long,
        val ms: Int,
        val done: () -> Unit,
        val intro: Boolean = false,
        val cont: Boolean = false
    ) {
        fun arriveIn(now: Long) = if (start == PENDING) (INTRO_HOLD_MS + ms) * 1_000_000L else (start + ms * 1_000_000L - now).coerceAtLeast(0)
    }

    private fun resized(width: Int, height: Int) {
        view.viewport = Viewport(0, 0, width, height)
        aspect = width.toFloat() / max(1, height)
        // 이동 중이면 목표도 새 비율로(레이아웃 전에 시작한 이동이 옛 비율 거리로 도착하지 않게)
        fly?.let { f -> fly = f.copy(to = CarMath.cam(state.view, state.seat, aspect), from = if (f.intro && hold != null) hero() else f.from) }
            ?: run { if (!userMoved) cam = CarMath.cam(state.view, state.seat, aspect) }
        invalidate3d()
    }

    /** 처음 만들 때부터 첫 장을 그릴 때까지(ms, 성능 기록용). */
    var firstFrameMs: Long? = null
        private set
    private val born = System.nanoTime()

    init {
        isOpaque = false
        CarEngine.whenReady(context, onFail = { if (!destroyed) onFailed?.invoke() }) { e ->
            if (destroyed) return@whenReady
            ce = e
            engine = e.engine
            renderer = engine.createRenderer().apply {
                clearOptions = clearOptions.apply {
                    clear = true
                    clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
                }
            }
            view = engine.createView().apply {
                scene = e.scene
                blendMode = View.BlendMode.TRANSLUCENT
                colorGrading = e.colorGrading
                isShadowingEnabled = false
                multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
                    enabled = true
                    sampleCount = 4
                }
            }
            camEntity = com.google.android.filament.EntityManager.get().create()
            camera = engine.createCamera(camEntity).apply { setExposure(1f) }.also { view.camera = it }
            ready = true
            ui.attachTo(this) // 표면이 이미 있으면 onNativeWindowChanged는 오지만 onResized는 오지 않을 수 있어 아래에서 크기를 맞춘다
            // 새 장이 TextureView에 들어올 때마다(곧 화면에 그려짐) 센다. 시작 연출이 바깥 모습이 화면에 나온 것·X-ray 셰이더가 준비된 것을 이것으로 안다.
            // UiHelper가 단 수신기는 그대로 부른다
            val inner = surfaceTextureListener
            if (inner != null) {
                surfaceTextureListener = object : SurfaceTextureListener by inner {
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
                        inner.onSurfaceTextureUpdated(st)
                        frameDone(st.timestamp)
                    }
                }
            }
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
        val pending = fly?.takeIf { it.start == PENDING }
        val move = pending == null && (s.view != before.view || s.seat != before.seat || s.drive || (fly != null && !ok))
        val ongoing = fly
        val arriveIn = when {
            move && ok -> CAMERA_MS * 1_000_000L
            move -> 0L
            ongoing != null -> ongoing.arriveIn(now) // 같은 자리로 가는 중: 닿을 때까지 기다린다
            else -> 0L
        }
        fx.onSet(before, s, ok, arriveIn, now)
        when {
            // 첫 그림 전: 시작 연출의 목표만 새 상태로(주행 중·동작 줄이기면 첫 그림에서 연출 없이 바로 이 자리)
            pending != null -> fly = pending.copy(to = CarMath.cam(s.view, s.seat, aspect), x1 = s.view.preset.xray, done = onArrive)
            move -> flyTo(s.view, ok, onArrive)
            ongoing != null -> fly = ongoing.copy(done = onArrive) // 도착 알림은 지금 상태의 것으로(옛 상태의 칩을 띄우지 않게)
            else -> onArrive()
        }
        invalidate3d()
    }

    /** '처음 자리로': 지금 상태의 자리로 날아간다. */
    fun reset() {
        dragging = false
        flyTo(state.view, state.motionOk) {}
    } // 끄는 중 눌러도 이동이 멈추지 않게

    private fun flyTo(v: View3d, animate: Boolean, done: () -> Unit) {
        val to = CarMath.cam(v, state.seat, aspect)
        setMoved(false)
        vAz = 0f
        vEl = 0f // 손가락 회전 관성이 자동 이동에 섞이지 않게
        // 움직이는 중에 새 목표가 오면(시작 연출 중 울음 감지 등) 멈췄다 다시 출발하지 않게 처음부터 속도가 있는 곡선으로 이어 간다
        val moving = fly != null && hold == null
        endHold()
        if (!animate) {
            fly = null
            cam = to
            xray = v.preset.xray
            done()
            invalidate3d()
            return
        }
        fly = Fly(cam, to, xray, v.preset.xray, System.nanoTime(), CAMERA_MS, done, cont = moving)
        invalidate3d()
    }

    private fun setMoved(m: Boolean) {
        if (userMoved != m) {
            userMoved = m
            onMoved?.invoke(m)
        }
    }

    /** 앵커 → 이 보기 안 픽셀 좌표(x, y, 거리). 화면 뒤면 null. */
    fun project(anchor: String): FloatArray? {
        if (!ready) return null
        val p = ce.anchor(anchor)
        val v = camera.getViewMatrix(DoubleArray(16))
        val pr = camera.getProjectionMatrix(DoubleArray(16))
        val vx = v[0] * p[0] + v[4] * p[1] + v[8] * p[2] + v[12]
        val vy = v[1] * p[0] + v[5] * p[1] + v[9] * p[2] + v[13]
        val vz = v[2] * p[0] + v[6] * p[1] + v[10] * p[2] + v[14]
        val cx = pr[0] * vx + pr[4] * vy + pr[8] * vz + pr[12]
        val cy = pr[1] * vx + pr[5] * vy + pr[9] * vz + pr[13]
        val cw = pr[3] * vx + pr[7] * vy + pr[11] * vz + pr[15]
        if (cw <= 0) return null
        val e = CarMath.eye(cam)
        val d = Math.sqrt(((p[0] - e[0]) * (p[0] - e[0]) + (p[1] - e[1]) * (p[1] - e[1]) + (p[2] - e[2]) * (p[2] - e[2])).toDouble())
        return floatArrayOf(((cx / cw + 1) / 2 * width).toFloat(), ((1 - cy / cw) / 2 * height).toFloat(), d.toFloat())
    }

    // 미뤄 둔 깨움(doFrame의 wake)이 남아 있으면 지우고 다음 장으로 당긴다(한 장을 두 번 부르지 않게)
    fun invalidate3d() {
        if (!posted && !destroyed) {
            posted = true
            Choreographer.getInstance().run {
                removeFrameCallback(this@CarView)
                postFrameCallback(this@CarView)
            }
        }
    }

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
        if (!ready || windowVisibility != VISIBLE || !isShown) return // 준비 전·숨은 동안 0프레임(소리 결·바람 결도 멈춤), 보일 때 onWindowVisibilityChanged가 다시 부른다
        val sc = swapChain ?: return
        val t = System.nanoTime()
        if (introArmed) introAt(t)
        var more = false
        var wake = 0L
        val h = hold
        val held = fly?.takeIf { h != null }
        if (h != null && held != null) {
            // 시작 연출 앞 멈춤. 처음 쓰는 셰이더는 그 장을 그릴 때 만들어져 그동안 장을 건너뛴다(에뮬레이터: 바깥 모습 첫 장 약 3초, X-ray 재질 약 0.8초 더,
            // car3d_cabin_1111.txt 11절). 그대로 날면 이동이 끝으로 튄다. 그래서 ① 바깥 모습만 내고 화면에 들어오기를 기다린 뒤(frameDone) ② X-ray를 아주 옅게(WARM_K)
            // 그려 셰이더를 만들고(그동안 화면은 바깥 모습 그대로) ③ 옅은 장이 WARM_FRAMES장 화면에 들어오고 바깥 모습이 나온 지 INTRO_HOLD_MS가 지나면 날아간다.
            // 끝내 준비되지 않아도 첫 장에서 WARM_MAX_MS가 지나면 날아간다(멈춤에 갇히지 않게)
            val warmed = h.warm && h.warmSeen >= WARM_FRAMES
            val minHoldAt = h.shownAt + INTRO_HOLD_MS * 1_000_000L
            val capAt = held.start + WARM_MAX_MS * 1_000_000L
            if ((warmed && t >= minHoldAt) || t >= capAt) {
                endHold(t)
                fly = held.copy(start = t)
            } // 이 doFrame에서 그리는 회전 첫 장(시각 t)부터
            else {
                cam = held.from
                xray = if (h.warm) WARM_K else 0f
                when {
                    warmed -> wake = minHoldAt - t // 최소 멈춤만 남았다: 그 시각에 한 번 깨운다
                    h.warm -> more = true // 옅은 장을 잇는다(백엔드가 바쁘면 건너뛰어 싸다)
                    else -> wake = capAt - t // 바깥 모습이 화면에 들어오기를 기다린다(frameDone이 깨운다, 못 오면 한도에)
                }
            }
        }
        if (hold == null) {
            fly?.let { f ->
                val k = min(1f, (t - f.start) / 1e6f / f.ms)
                val e = (if (f.cont) CONT_EASE else CAMERA_EASE).transform(k)
                cam = CarMath.lerp(f.from, f.to, e)
                xray = f.x0 + (f.x1 - f.x0) * e
                if (k >= 1f) {
                    fly = null
                    cam = f.to
                    xray = f.x1
                    f.done()
                } else {
                    more = true
                }
            }
        }
        val f = fx.at(t)
        if (f.running) more = true
        if (spin()) more = true
        // 계속 움직이는 표현은 사건 시점에서만([Stage3d.live]): 기본 실내 같은 대기 시점에서는 소리 결·바람 결이 멈춘 그림이다
        val ripple = if (state.rippling) ((t - t0) / 1e6f / RIPPLE_MS) % 1f else null // 소리 결 한 주기 = 토큰 motion.ripple
        if (ripple != null) more = true
        val flowing = state.flowing(xray) // 바람 결이 실제로 보이며 흐를 때만 다음 프레임
        if (flowing) more = true
        ce.apply(state, xray, dark, f.heatWarm, f.lockK, ripple, f.pulseK, flowT = if (flowing) (t - t0) / 1e9f / 1.4f else 0f)
        val e = CarMath.eye(cam)
        camera.setProjection(cam.fov.toDouble(), aspect.toDouble(), 0.1, 80.0, Camera.Fov.VERTICAL)
        camera.lookAt(e[0].toDouble(), e[1].toDouble(), e[2].toDouble(), cam.t[0].toDouble(), cam.t[1].toDouble(), cam.t[2].toDouble(), 0.0, 1.0, 0.0)
        var rendered = false
        // 그린 장마다 이 doFrame의 시각(t, 단조 시계)을 단다(EGL 표시 시각 → SurfaceTexture.timestamp). 받는 쪽이 '어느 때 그린 장이 들어왔나'를
        // 장 수가 아니라 이 시각으로 안다(SurfaceTexture.updateTexImage는 가장 최근 장으로 건너뛰어 받은 횟수가 낸 횟수보다 적을 수 있다).
        // 지난 시각이라 TextureView가 장을 늦추지 않는다(받는 쪽 updateTexImage는 표시 시각을 기다리지 않는다)
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.setPresentationTime(t)
            renderer.render(view)
            renderer.endFrame()
            rendered = true
        }
        if (rendered && firstFrameMs == null) {
            firstFrameMs = (System.nanoTime() - born) / 1_000_000
            android.util.Log.i("CarView", "첫 장 ${firstFrameMs}ms")
        }
        onFrame?.invoke(frameTimeNanos, (System.nanoTime() - t) / 1e6, rendered)
        onRender?.invoke()
        if (more || !rendered) {
            invalidate3d()
        } else if (wake > 0) {
            Choreographer.getInstance().postFrameCallbackDelayed(this, wake / 1_000_000 + 1)
        }
    }

    /**
     * 첫 그림에서 시작 연출을 정한다. 정차 중·동작 켜짐이면 HERO에서 멈췄다([hold]) 날아가고, 아니면 바로 지금 상태 자리.
     * 소진은 [consumeIntro]대로: 주행 중 무대는 이 보기만 건너뛰고 연출은 처음 정차 홈에 남긴다.
     */
    private fun introAt(t: Long) {
        introArmed = false
        if (consumeIntro(state)) introDone = true
        val f = fly?.takeIf { it.start == PENDING } ?: return // 첫 그림 전에 다른 이동이 이미 들어왔다
        val to = CarMath.cam(state.view, state.seat, aspect)
        fly = if (state.motionOk) {
            f.copy(from = hero(), to = to, start = t).also { hold = Hold() } // start는 앞 멈춤 동안 첫 장 시각(한도 기준), 날 때 그 시각으로 바꾼다
        } else {
            cam = to
            xray = f.x1
            f.done()
            null
        }
    }

    private fun hero() = CarMath.cam(View3d.HERO, state.seat, aspect)

    /**
     * 앞 멈춤을 끝낸다(날기 시작·새 목표·손으로 끎). [from](이 시각 이후에 그린 장, doFrame 안이면 그 doFrame 시각)이 화면에 들어오면 [onHoldEnd].
     * 안전망으로 회전 길이 뒤에도 알린다.
     */
    private fun endHold(from: Long = System.nanoTime()) {
        if (hold == null) return
        hold = null
        holdEndFrom = from
        removeCallbacks(holdEndNet)
        postDelayed(holdEndNet, INTRO_MS.toLong())
    }

    private fun holdEnded() {
        if (holdEndFrom < 0 || destroyed) return
        holdEndFrom = -1
        removeCallbacks(holdEndNet)
        onHoldEnd?.invoke()
    }

    /** 새 장이 TextureView에 들어왔다(메인 스레드, [shownTs] = 그 장에 단 시각). 앞 멈춤 중 바깥 모습이 처음 들어오면 옅은 X-ray로 넘어가고, 옅은 장이 들어오면 다시 깨운다. */
    private fun frameDone(shownTs: Long) {
        if (destroyed) return
        framesDone++
        if (framesDone == 1L) {
            android.util.Log.i("CarView", "첫 화면 ${(System.nanoTime() - born) / 1_000_000}ms ${width}x$height")
            onFirstFrame?.invoke()
        }
        if (drawnSince(shownTs, holdEndFrom)) holdEnded()
        val h = hold ?: return
        if (!h.warm) {
            h.warm = true
            h.shownAt = System.nanoTime()
            h.warmFrom = h.shownAt
        } // 이 뒤 doFrame에서 그리는 장이 옅은 장
        else if (drawnSince(shownTs, h.warmFrom)) {
            h.warmSeen++
        }
        invalidate3d()
    }

    // 손가락 회전(정차 중만, 감쇠 0.09) ───────────
    private var lastX = 0f
    private var lastY = 0f
    private var vAz = 0f
    private var vEl = 0f
    private var dragging = false
    private var lastMoveAt = 0L

    private fun spin(): Boolean {
        if (!state.motionOk || fly != null) {
            if (!state.motionOk) dragging = false
            vAz = 0f
            vEl = 0f
            return false
        }
        if (dragging) return true
        if (kotlin.math.abs(vAz) < 0.01f && kotlin.math.abs(vEl) < 0.01f) return false
        rotate(vAz, vEl)
        vAz *= 1 - 0.09f
        vEl *= 1 - 0.09f
        return true
    }

    private fun rotate(daz: Float, del: Float) {
        cam = cam.copy(az = (cam.az + daz) % 360f, el = (cam.el + del).coerceIn(4f, 84f))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val end = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
        if (end && dragging) { // 끄는 중 주행으로 바뀌어도 손을 뗀 것은 받는다
            dragging = false
            if (ev.eventTime - lastMoveAt > 80) {
                vAz = 0f
                vEl = 0f
            } // 마지막 끌기 뒤 80ms 넘게 멈췄다 떼면 관성 없음(폰과 같은 값)
            invalidate3d()
            return true
        }
        if (!interactive || !state.motionOk || (fly != null && fly?.intro != true)) return false
        val k = 0.55f * 360f / max(1, height) // 회전 속도 0.55: 화면 높이만큼 끌면 약 200도(2π·dx/높이·0.55)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fly?.takeIf { it.intro }?.let { f ->
                    fly = null
                    endHold()
                    xray = f.x1
                    f.done()
                } // 시작 연출 중 끌면 그 자리에서 손에 넘긴다(껍데기는 바로 X-ray)
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                lastX = ev.x
                lastY = ev.y
                lastMoveAt = ev.eventTime
                vAz = 0f
                vEl = 0f
                setMoved(true)
                invalidate3d()
            }
            MotionEvent.ACTION_MOVE -> {
                // 오른쪽으로 끌면 방위가 줄어든다(three OrbitControls rotateLeft와 같은 방향), 아래로 끌면 위에서 내려다본다
                val daz = -(ev.x - lastX) * k
                val del = (ev.y - lastY) * k
                // 관성은 시간으로 잰 속도(각/ms)를 프레임 길이(16.7ms)로: 이벤트가 몇 번에 나뉘어 오든 같은 회전
                val dt = (ev.eventTime - lastMoveAt).coerceIn(1, 100).toFloat()
                rotate(daz, del)
                vAz = daz / dt * 16.7f
                vEl = del / dt * 16.7f
                lastX = ev.x
                lastY = ev.y
                lastMoveAt = ev.eventTime
                invalidate3d()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate3d()
            }
        }
        return true
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        Choreographer.getInstance().removeFrameCallback(this)
        removeCallbacks(holdEndNet)
        if (ready) {
            ui.detach()
            swapChain?.let { engine.destroySwapChain(it) }
            engine.destroyRenderer(renderer)
            engine.destroyView(view)
            engine.destroyCameraComponent(camEntity)
            com.google.android.filament.EntityManager.get().destroy(camEntity)
        }
    }

    companion object {
        /** 카메라 이동(토큰 motion.camera: 1100ms, cubic-bezier(.65,0,.35,1)). */
        val CAMERA_MS = Tokens.motion.getValue("camera").ms
        private val CAMERA_EASE = Tokens.motion.getValue("camera").easing
        private val RIPPLE_MS = Tokens.motion.getValue("ripple").ms.toFloat()

        /** 이동 중에 목표가 바뀐 이동의 곡선: 처음 기울기 2(평균 속도의 2배, 카메라 곡선 가운데쯤의 속도)로 출발해 카메라 곡선처럼 멈추며 닿는다. 길이는 카메라 토큰 그대로. */
        private val CONT_EASE = androidx.compose.animation.core.CubicBezierEasing(0.33f, 0.66f, 0.35f, 1f)

        /**
         * 시작 연출: 바깥 모습이 화면에 나온 뒤 멈춤 최소 600ms(셰이더 준비가 길면 그만큼 더), 이동 1800ms(곡선은 카메라 토큰 cb(.65,0,.35,1)).
         * 같은 크기로 도는 HERO → REAR 사건 이동(1100ms)보다 1.6배 느리게 해 한 번 보여 주는 소개로 읽히게 했다. 멈춤은 바깥 모습을 한 번 알아볼 만큼(빛 토큰 900ms보다 짧게).
         */
        const val INTRO_HOLD_MS = 600L
        const val INTRO_MS = 1800
        private const val PENDING = Long.MIN_VALUE

        /** 앞 멈춤: 셰이더가 준비됐다고 볼 화면에 들어온 옅은 X-ray 장 수, 첫 장부터 기다림 한도, 그때의 X-ray 값(눈에 띄지 않을 만큼). */
        private const val WARM_FRAMES = 2
        private const val WARM_MAX_MS = 6000L
        private const val WARM_K = 0.01f

        /** 이 프로세스에서 시작 연출을 이미 정했는가(정차 홈에서 했든 동작 줄이기로 건너뛰었든, [consumeIntro]). 홈으로 다시 와도 되풀이하지 않는다. */
        private var introDone = false

        /** 홈 무대의 첫 장이 시작 연출(바깥 모습)일 수 있는가(정지 그림 고르기, 움직임 조건은 부르는 쪽이 본다). */
        fun introPending() = !introDone
    }
}

/**
 * 받는 쪽 기준 판단: 화면에 들어온 장에 단 시각 [shownTs]가 [fromNs](멈춤을 끝낸 때·옅은 장을 그리기 시작한 때) 이상이면 그때 이후에 그린 장이 화면에 있다.
 * [fromNs]가 음수면 기다리는 것이 없다. 장 수를 세어 낸 수와 견주지 않는다(받는 쪽은 가장 최근 장으로 건너뛰어 덜 센다, car3d_cabin_1111.txt 14절).
 */
internal fun drawnSince(shownTs: Long, fromNs: Long): Boolean = fromNs >= 0 && shownTs >= fromNs

/**
 * 홈 무대의 첫 그림이 시작 연출을 소진하는가: 정차 홈이면 소진한다(동작 줄이기면 연출 없이 cabin이고 그것으로 끝).
 * 주행 중 무대가 먼저 보였으면 소진하지 않는다: 그 보기는 연출 없이 지금 자리이고 연출은 처음 정차 홈에서 한다(정차 홈은 새 CarView라 다시 무장한다).
 */
internal fun consumeIntro(s: Stage3d): Boolean = !s.drive
