package kr.aigyeot.app.ui.car3d

import kr.aigyeot.app.ui.Tokens
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.google.android.filament.Box
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.ToneMapper
import com.google.android.filament.VertexBuffer
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D 엔진 하나(프로세스 수명, 메인 스레드 전용). GLB 하나(assets/car/aigyeot_car.glb), 재질 덮어쓰기(이름이 계약), 스튜디오 환경광,
 * 제어 표시(고리·소리 결·그림자), X-ray 유령 껍데기를 든다. 보기([CarView])는 그리기 전에 자기 상태를 [apply]로 장면에 얹는다.
 * 시안 js/car3d.js의 makeScene·applyState·studio를 Filament로 옮겼다.
 */
class CarEngine private constructor(private val prep: Prep, val engine: Engine) {
    companion object {
        private const val TAG = "CarEngine"
        private var inst: CarEngine? = null
        /** 실측용(debug 3D 실측 액티비티): 참이면 구운 AO(COLOR_0)를 빼고 그려 곱해지는지 견준다. */
        var debugNoAo = false
        /** 홈 정지 그림 찍기용(debug Car3dSpikeActivity `--ez noseat true`): 참이면 카시트를 그리지 않는다(정지 그림은 지금 자리를 담지 않는다). */
        var debugNoChildSeat = false

        /** 기다리는 보기: 준비되면 첫째, 실패하면 둘째를 부른다. */
        private val waiting = ArrayList<Pair<(CarEngine) -> Unit, () -> Unit>>()
        private var loading = false

        /**
         * 엔진이 준비되면 [cb](메인 스레드), 준비가 실패하면 [onFail]. 처음이면 GLB 읽기·JSON·환경광 픽셀 계산은 작업 스레드에서, Filament 단계(모델·자원·재질·장면·환경광·효과)는
         * 메인 스레드에서 단계마다 한 번씩 양보하며 만든다(화면 전환·주행 중 화면이 오래 막히지 않게, Filament는 메인 스레드에서만).
         * 준비는 첫 보기가 붙을 때 시작한다. 앱 시작(MainActivity.onCreate)에서 미리 돌려 보니 앱 시작 일과 CPU를 나눠 홈(정지 그림)이 약 0.2초 늦게 뜨고
         * 3D 첫 장은 빨라지지 않았다(car3d_cabin_1111.txt 12절). 대신 작업 스레드 준비 자체를 줄였다(환경광 계산).
         * 셰이더 미리 컴파일(Material.compile)도 쓰지 않는다: Filament 1.77.3은 병렬 셰이더 컴파일을 쓸 수 있을 때만 미리 만들고,
         * 이 에뮬레이터 EGL에는 EGL_KHR_surfaceless_context·GL_KHR_parallel_shader_compile이 없어 동기 모드라 아무것도 하지 않는다(12절)
         */
        @MainThread fun whenReady(ctx: Context, onFail: () -> Unit = {}, cb: (CarEngine) -> Unit) {
            inst?.let { return cb(it) }
            waiting += cb to onFail
            if (loading) return
            loading = true
            val app = ctx.applicationContext
            val main = Handler(Looper.getMainLooper())
            // 준비가 실패하면(자산 읽기·재질 컴파일·Filament 단계 예외) 로그를 남기고 기다리던 보기에 알린다(그 칸은 실패 정지 표시, 앱은 그대로).
            // 다음에 붙는 보기가 처음부터 다시 부른다
            fun failed(t: Throwable) { Log.e(TAG, "3D 준비 실패: 3D 칸을 비워 둔다", t); loading = false; waiting.toList().also { waiting.clear() }.forEach { it.second() } }
            Thread({
                val prep = try { Prep.load(app) } catch (t: Throwable) { main.post { failed(t) }; return@Thread }
                main.post {
                    try {
                        Utils.init(); Gltfio.init()
                        // 생성자(재질 공급·자산 읽개·색 보정)가 실패하면 만든 Engine을 바로 놓는다: 남기면 이 프로세스에서 다음 Engine.create가 막혀 3D가 다시 뜨지 않는다
                        val engine = Engine.create()
                        val e = try { CarEngine(prep, engine) } catch (t: Throwable) { runCatching { engine.destroy() }; throw t }
                        e.build(main, { t -> e.release(); failed(t) }) { inst = e; loading = false; waiting.toList().also { waiting.clear() }.forEach { it.first(e) } }
                    } catch (t: Throwable) { failed(t) }
                }
            }, "car3d-prep").start()
        }



        /** 곁의 응답·아이의 신호 빛(토큰 glow·childGlow를 그대로 읽는다). */
        val GLOW = (Tokens.color.getValue("glow").first and 0xFFFFFF).toInt()
        val CHILD_GLOW = (Tokens.color.getValue("childGlow").first and 0xFFFFFF).toInt()

        /** 바깥 껍데기(X-ray에서 사라진다)·유령 겹(프레넬 테두리로 남는다). 이름 규칙은 시안 그대로. */
        private val SHELL = Regex("^(Body|Body_Roof|Door_F_|Door_R_|Door_Handle|Mirror_|DLO_|Belt_|Grille|Head_Lamp|Rear_Lamp|Rear_Stop|Panel_Gap|Fuel_Door|Opera_Glass|Glass_|Sunroof_Glass|WheelWell|Parcel_Shelf|Lamp_R_Pod|Door_Trim_|Door_Upper_|Armrest_R_)")
        private val GHOST = Regex("^(Body|Body_Roof|Mirror_Head|Door_F_.*Window|Door_R_.*Window|Opera_Glass|Glass_Front|Glass_Rear|Sunroof_Glass)")
        private val CHILD = Regex("^ChildSeat")
        /**
         * 흐리는 껍데기의 투명 그리기 차례: 안쪽(문 안감·휠 하우스) 3 → 바깥 판 4 → 유리 5. 실내는 기본 4, 효과 고리 5, 유령 겹 6·7.
         * Filament는 같은 차례 안의 투명 물체를 물체 중심 거리로 정렬한다. 문 안감(Door_Trim_Lining, 양쪽 문이 한 물체)과 차체(Body)는 중심이 거의 같아
         * 카메라가 돌 때 앞뒤가 뒤바뀌며 검은 안감이 차체 위로 덮였다 사라졌다(깜빡임, car3d_cabin_1111.txt 9절). 바깥에서 보면 이 차례가 늘 맞으므로 고정한다.
         */
        private val SHELL_INNER = Regex("^(Door_Trim_|Door_Upper_|Armrest_R_|Parcel_Shelf|WheelWell)")
        private val SHELL_GLASS = Regex("^(Glass_|Opera_Glass|Sunroof_Glass|Door_[FR]_[LR]_Window)")
        /** X-ray에서 가는 빛 선으로 남길 짜임(시안 v7 LINES). */
        private val LINES = Regex("^(Panel_Gap|DLO_Chrome)")
        /** 유령 껍데기 바탕 불투명도(시안 v7 uBase). */
        private const val GHOST_BASE = 0.014f
    }

    val scene: Scene = engine.createScene()
    private val em = EntityManager.get()
    private val rm get() = engine.renderableManager
    private val tm get() = engine.transformManager
    private val lm get() = engine.lightManager

    val colorGrading: ColorGrading = ColorGrading.Builder().toneMapper(ToneMapper.PBRNeutral()).exposure(log2(1.05f)).build(engine)

    // ── 모델 ──
    private val provider = UbershaderProvider(engine)
    private val loader = AssetLoader(engine, provider, em)
    private lateinit var asset: FilamentAsset
    private lateinit var main: FilamentInstance
    private lateinit var ghostInst: FilamentInstance
    private val glbColor get() = prep.colors
    private val vbOf = HashMap<Int, VertexBuffer>()   // 노드 이름 → 프리미티브마다 COLOR_0(구운 AO)가 있는가

    // 재질: 이름 → 인스턴스(불투명), 껍데기용 따로(흐릴 때 BLEND로 바꾼다)
    private val mats = HashMap<String, MaterialInstance>()
    private val matOf = HashMap<MaterialInstance, Material>()
    private val shellOpaque = HashMap<Pair<Int, Int>, MaterialInstance>()
    private val shellFade = HashMap<Pair<Int, Int>, MaterialInstance>()
    private val shellFadeByName = HashMap<String, MutableList<MaterialInstance>>()
    private val shellRenderables = ArrayList<Int>()
    private val ghostRenderables = ArrayList<Int>()
    private val lineRenderables = ArrayList<Int>()   // X-ray 빛 선(Panel_Gap·DLO_Chrome의 둘째 인스턴스)
    private val childGhostRenderables = ArrayList<Int>()
    private val heatPads = HashMap<Int, Seat3d?>()   // 열선 패드 → 뒷자리 쪽(노드 Heat_Pad_RL·RR)
    private val lockRims = HashMap<Int, Seat3d?>()   // 뒷창 잠금 테 → 쪽(노드 Lock_Outline_RL·RR, 재질 Indicator 하나를 같이 쓴다)
    private var heatMi: MaterialInstance? = null
    private val anchors = HashMap<String, Int>()
    private var childSeat = 0
    private var childSeatGhost = 0
    private var childSeatLocal: FloatArray? = null

    // 맞춤 재질(matc로 미리 컴파일한 assets/materials, R2)
    private lateinit var ghostMat: Material
    private lateinit var flatMat: Material
    private lateinit var vtxMat: Material
    private lateinit var addMat: Material
    private lateinit var ghost: MaterialInstance
    private lateinit var childGhost: MaterialInstance
    private lateinit var indicator: MaterialInstance
    private lateinit var lineMi: MaterialInstance

    // 빛
    private val key = em.create()
    private val seatLight = em.create()
    private val cabin = em.create()
    private lateinit var ibl: Map<Boolean, IndirectLight>

    // 효과 메시
    private lateinit var flowMat: Material
    private lateinit var flowMi: MaterialInstance
    private val flows = ArrayList<Int>()
    private lateinit var seatRing: Mesh
    private lateinit var listenDisc: Mesh
    private val arcs = ArrayList<Arc>()
    private var arcSeat: Seat3d? = null

    /** 준비에 든 메인 스레드 시간 합(ms, 단계 사이 양보는 빼고). */
    var loadMs = 0L; private set

    /**
     * 준비가 중간에 실패하면 이 엔진이 만든 것을 모두 놓는다. 엔진을 지워야 그 JobSystem이 쥔 메인 스레드가 풀려
     * 다음 보기가 새 엔진을 만들 수 있다(Filament는 다른 JobSystem이 쥔 스레드의 등록을 거부한다).
     */
    private fun release() {
        runCatching { if (::asset.isInitialized) loader.destroyAsset(asset) }
        runCatching { loader.destroy() }
        runCatching { provider.destroyMaterials(); provider.destroy() }
        runCatching { engine.destroy() }
    }

    /** Filament 단계들. 단계마다 메인 루프에 한 번 양보한다. 끝나면 [done], 예외면 [fail]. */
    private fun build(main: Handler, fail: (Throwable) -> Unit, done: () -> Unit) {
        val steps = listOf<() -> Unit>(
            {
                val buf = ByteBuffer.allocateDirect(prep.glb.size).order(ByteOrder.nativeOrder()).put(prep.glb).also { it.flip() }
                val insts = arrayOfNulls<FilamentInstance>(2)
                asset = loader.createInstancedAsset(buf, insts) ?: error("GLB를 읽지 못함")
                this.main = insts[0]!!; ghostInst = insts[1]!!
            },
            { ResourceLoader(engine).run { loadResources(asset); destroy() }; asset.releaseSourceData() },
            {
                ghostMat = material("ghost"); flatMat = material("flat")
                ghost = ghostMat.createInstance("ghost"); childGhost = ghostMat.createInstance("childGhost")
                // 유령 바탕 0.014(시안 v7: 가운데는 더 비고 테두리는 또렷, 테두리 계수 0.86·지수 2.9는 ghost.mat)
                ghost.setParameter("base", GHOST_BASE); childGhost.setParameter("base", GHOST_BASE)
                indicator = flatMat.createInstance("indicator")
                lineMi = flatMat.createInstance("lines")
            },
            { vtxMat = material("vtx"); addMat = material("add"); flowMat = material("flow") },
            { setupMain() },
            { setupGhost() },
            { ibl = mapOf(false to uploadIbl(prep.cube.getValue(false)), true to uploadIbl(prep.cube.getValue(true))) },
            { lightsAndEffects() },
        )
        var i = 0
        val each = ArrayList<Long>()
        fun next() {
            val t = System.nanoTime()
            try { steps[i++]() } catch (e: Throwable) { return fail(e) }
            ((System.nanoTime() - t) / 1_000_000).let { each += it; loadMs += it }
            if (i < steps.size) main.post { next() }
            else { Log.i(TAG, "3D 준비 메인 ${loadMs}ms(단계 ${each.joinToString("·")}ms, 작업 스레드 ${prep.ms}ms: ${prep.detail}), 렌더러블 ${asset.renderableEntities.size}, 껍데기 ${shellRenderables.size}, 유령 ${ghostRenderables.size}"); done() }
        }
        next()
    }

    private fun lightsAndEffects() {
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f, 1f, 1f).intensity(1.4f).direction(-3f, -6f, 2f).castShadows(false).build(engine, key)
        LightManager.Builder(LightManager.Type.POINT).color(1f, 1f, 1f).intensityCandela(0f).falloff(1.3f).castShadows(false).build(engine, seatLight)
        val warm = lin(0xFFF4E6)
        LightManager.Builder(LightManager.Type.POINT).color(warm[0], warm[1], warm[2]).intensityCandela(0f).falloff(4.5f).position(-0.35f, 1.18f, 0f).castShadows(false).build(engine, cabin)
        scene.addEntity(key); scene.addEntity(seatLight); scene.addEntity(cabin)
        scene.addEntity(shadowMesh().entity)
        seatRing = Mesh(ring(0.30f, 0.315f, 96, 0f, (2 * PI).toFloat()), addMat.createInstance("seatRing"), 5).also { rotFlat(it.entity, 1f) }
        listenDisc = Mesh(ring(0f, 0.2f, 64, 0f, (2 * PI).toFloat()), addMat.createInstance("listenDisc"), 5).also { rotFlat(it.entity, 1f) }
        for (sp in listOf("Anchor_Speaker_RL", "Anchor_Speaker_RR")) for (i in 0 until 3) arcs += Arc(sp, i, Mesh(ring(0.93f, 1f, 40, 0f, 1f), vtxMat.createInstance("arc"), 5))
        flowMi = flowMat.createInstance("flow")
        lin(0x9EC7E6).let { flowMi.setParameter("color", it[0], it[1], it[2]) }
        val vent = anchor("Anchor_Vent_R")
        for (sd in listOf(-1f, 1f)) flows += tube(listOf(vent, floatArrayOf(vent[0] - 0.18f, vent[1] + 0.12f, sd * 0.12f), floatArrayOf(-0.72f, 0.84f, sd * 0.32f), floatArrayOf(-0.98f, 0.92f, sd * 0.42f)), 0.035f, flowMi)
        flows.forEach { visible(it, false) }
    }

    /** 장면에 보기 하나의 상태를 얹는다(시안 applyState). [k]는 X-ray 정도(0 일반 ↔ 1 실내 보기). */
    /** [pulseK]: 울음 감지 빛 맥박 진행(0~1, 1.2초), 없으면 null. */
    fun apply(s: Stage3d, k: Float, dark: Boolean, heatWarm: Float, lockK: Float, ripplePhase: Float?, pulseK: Float?, flowT: Float = 0f) {
        val pulse = pulseK?.let { 2.6f * sin(PI.toFloat() * min(1f, it * 1.5f)) * (1 - it * 0.6f) } ?: 0f
        scene.indirectLight = ibl.getValue(dark)
        val on = s.callouts
        val methodC = lin(GLOW)
        val teal = lin(GLOW); val apricot = lin(CHILD_GLOW)
        // 실내 앰비언트 선: 듣는 중 파랑, 달래는 중 방법 색(v6: 파랑 하나), 경보 주황. 조명 낮춤이면 낮게
        val ambC = if (s.light == Light3d.ALERT) apricot else if (s.light == Light3d.SOOTHE) methodC else teal
        val ambI = when (s.light) { Light3d.BASE -> 0f; Light3d.LISTEN -> 2.2f; Light3d.SOOTHE -> 2.6f; Light3d.ALERT -> 2.8f } * (if (Callout3d.DIM in on) 0.3f else 1f)
        emissive("Ambient", ambC, ambI)
        emissive("Reading_Lamp", lin(0xFFE2B8), k * (if (Callout3d.DIM in on) 0.12f else 0.9f))
        val sl = lm.getInstance(seatLight)
        (if (s.light == Light3d.ALERT) apricot else methodC).let { lm.setColor(sl, it[0], it[1], it[2]) }
        lm.setIntensityCandela(sl, (when (s.light) { Light3d.SOOTHE -> 0.6f; Light3d.ALERT -> 0.75f; else -> 0f }) + maxOf(0f, pulse))
        // 바깥 껍데기 → 유령(k로 이어진다). 흐리는 동안은 BLEND 짝으로 바꾼다
        setShell(k, dark)
        lin(if (dark) 0xDCE6FF else 0x4A5F8F).let { ghost.setParameter("color", it[0], it[1], it[2]) }
        ghost.setParameter("k", k * if (dark) 1f else 0.66f)
        // 문·창의 짜임(Panel_Gap·DLO_Chrome)을 가는 빛 선으로 남긴다(색은 유령과 같음, 불투명도 라이트 0.55k·다크 0.5k)
        lin(if (dark) 0xDCE6FF else 0x4A5F8F).let { lineMi.setParameter("color", it[0], it[1], it[2], k * if (dark) 0.5f else 0.55f) }
        // 라이트 X-ray: 흰 바탕에서 검은 대시·화면이 덩어리로 보이지 않게 회색 쪽으로 올린다
        val lift = if (dark) 0f else 0.5f * k
        baseColor("Interior_Dark", mix(lin(0x2A2C30), lin(0x8A8F96), lift), 1f)
        baseColor("Display", mix(lin(0x050607), lin(0x8A8F96), lift * 0.7f), 1f)
        (if (s.light == Light3d.ALERT) apricot else teal).let { childGhost.setParameter("color", it[0], it[1], it[2]) }
        childGhost.setParameter("k", k * 0.9f)
        val ghostOn = k > 0.005f
        (ghostRenderables + childGhostRenderables + lineRenderables).forEach { visible(it, ghostOn) }
        lm.setIntensityCandela(lm.getInstance(cabin), 1.5f * k)
        lm.setIntensity(lm.getInstance(key), 1.4f * (1 - 0.35f * k))
        // 제어 표시(실내 보기에서만 또렷하게)
        val ind = if (Callout3d.LOCK in on) min(1f, lockK * (0.35f + 0.65f * k)) else 0f
        indicator.setParameter("color", teal[0] * ind, teal[1] * ind, teal[2] * ind, ind)
        lockRims.forEach { (e, side) -> visible(e, Callout3d.LOCK.shownAt(s, side)) }   // 잠근 쪽 뒷창 테만
        val heatOn = Callout3d.HEAT in on
        // 실제로 끈 쪽 패드만(Stage3d.sides). 쪽을 모르는 그림(리포트 '차가 대신 한 일')은 둘 다
        heatPads.forEach { (e, side) -> visible(e, Callout3d.HEAT.shownAt(s, side)) }
        if (heatOn) heatMi?.let { mi ->
            val c = mix(lin(0x7FA6C9), lin(0xE8955A), heatWarm); val i = (0.7f + 1.7f * heatWarm) * EMISSIVE_GAIN
            val b = SPECS.getValue("Heat_Pad").color
            mi.setParameter("baseColorFactor", b[0], b[1], b[2], 0.25f + 0.65f * k)
            if (matOf[mi]?.hasParameter("emissiveFactor") == true) mi.setParameter("emissiveFactor", c[0] * i, c[1] * i, c[2] * i)
        }
        // 바람 결(공조 낮춤): 실내 보기에서만, 사건 시점·정차 중이면 흐른다(시간 [flowT], Stage3d.flowing), 그 밖에는 멈춘 줄
        val hvac = Callout3d.HVAC in on
        flows.forEach { visible(it, hvac && k > 0.3f) }
        flowMi.setParameter("k", if (hvac) k else 0f); flowMi.setParameter("time", flowT)
        // 아기 자리
        moveChildSeat(s.seat)
        val seat = anchor(s.seat.anchor)
        lm.setPosition(sl, seat[0], seat[1] + 0.15f, seat[2])
        // 고리: 맥박 동안 1.2초 내내 1 → 2.8배로 퍼지며 사라진다(빛 세기와 따로, 시안 pulse)
        place(seatRing.entity, seat[0] + 0.02f, 0.585f, seat[2], 1f + (pulseK ?: 0f) * 1.8f)
        place(listenDisc.entity, seat[0] + 0.02f, 0.585f, seat[2], 1f)
        val ringA = if (pulseK != null) 0.95f * (1 - pulseK) else if (s.light == Light3d.BASE) 0.35f else 0.55f
        seatRing.color(if (s.light == Light3d.ALERT) apricot else teal, ringA)
        listenDisc.color(teal, if (s.listen) 0.28f else 0f)
        // 소리 결 호: 스피커에서 아기 자리 쪽으로만
        if (arcSeat != s.seat) rebuildArcs(s.seat, seat)
        for (a in arcs) {
            val (scale, alpha) = when {
                !s.ripple -> 0f to 0f
                ripplePhase == null -> (a.reach * (0.4f + a.i * 0.25f)) to (0.7f - a.i * 0.18f) * (0.4f + 0.6f * k)
                else -> { val p = (ripplePhase + a.i / 3f) % 1f; (0.12f + p * a.reach) to 0.95f * (1 - p) * min(1f, p * 5) * (0.4f + 0.6f * k) }
            }
            visible(a.mesh.entity, alpha > 0.001f)
            a.mesh.color(methodC, alpha)
            scaleAt(a.mesh.entity, a.pos, scale)
        }
    }

    /** 앵커 → 세계 좌표(m). */
    fun anchor(name: String): FloatArray {
        val e = anchors[name] ?: return floatArrayOf(0f, 0f, 0f)
        val m = tm.getWorldTransform(tm.getInstance(e), FloatArray(16))
        return floatArrayOf(m[12], m[13], m[14])
    }

    // ───────────── 만들기 ─────────────

    private fun nameOf(e: Int) = asset.getName(e) ?: ""
    /** 노드(또는 부모) 이름 '{base}_RL'·'{base}_RR'의 쪽. */
    private fun sideIn(e: Int, base: String) = (nameOf(e) + "|" + parentName(e)).let { n -> Seat3d.entries.firstOrNull { "${base}_${it.name}" in n } }
    private fun parentName(e: Int) = tm.getParent(tm.getInstance(e)).let { if (it == 0) "" else nameOf(it) }
    private fun named(e: Int, re: Regex) = re.containsMatchIn(nameOf(e)) || re.containsMatchIn(parentName(e))

    private fun setupMain() {
        for (e in main.entities) {
            val n = nameOf(e)
            if (n.startsWith("Anchor_")) anchors[n] = e
            if (n == "ChildSeat") childSeat = e
        }
        for (e in main.entities) {
            val ri = rm.getInstance(e)
            if (ri == 0) continue
            // 선루프 가림막은 껍데기 규칙 밖이지만 X-ray에서 같이 흐려진다(시안: 투명 재질, 불투명도 1-k)
            val shell = named(e, SHELL) || nameOf(e) == "Roofglass_Shade"
            val colors = glbColor[nameOf(e)].orEmpty()
            for (p in 0 until rm.getPrimitiveCount(ri)) {
                val orig = rm.getMaterialInstanceAt(ri, p)
                val mname = orig.name ?: continue
                val spec = SPECS[mname]
                val hasColor = colors.getOrElse(p) { false }
                val baseMat = provider.getMaterial(keyFor(spec, hasColor, if (spec?.blend == true) 2 else 0), IntArray(8), mname)
                if (shell && spec != null && !spec.blend) {
                    // 껍데기는 실내와 같은 이름이어도 따로(흐릴 때 BLEND 짝으로 바꾼다)
                    val opaque = MaterialInstance.duplicate(orig, "shell_$mname").also { if (baseMat != null) spec.applyTo(it, baseMat) }
                    val fadeKey = keyFor(spec, hasColor, 2)
                    val fadeMat = provider.getMaterial(fadeKey, IntArray(8), "fade_$mname")
                    val fade = provider.createMaterialInstance(fadeKey, IntArray(8), "fade_$mname", null)?.also { if (fadeMat != null) spec.applyTo(it, fadeMat) }
                    shellOpaque[ri to p] = opaque
                    if (fade != null) { shellFade[ri to p] = fade; shellFadeByName.getOrPut(mname) { mutableListOf() } += fade }
                    rm.setMaterialInstanceAt(ri, p, opaque)
                } else {
                    val mi = mats.getOrPut(if (shell) "shell_$mname" else mname) { orig.also { if (spec != null && baseMat != null) spec.applyTo(it, baseMat); if (baseMat != null) matOf[it] = baseMat } }
                    if (mi !== orig) rm.setMaterialInstanceAt(ri, p, mi)
                }
                if (mname == "Indicator") { rm.setMaterialInstanceAt(ri, p, indicator); lockRims[e] = sideIn(e, "Lock_Outline") }
                if (mname == "Heat_Pad") {
                    // 열선 패드는 반투명(시안: 불투명도 0.25 + 0.65×X-ray): BLEND 짝 하나를 모든 패드가 같이 쓴다
                    val key = keyFor(SPECS["Heat_Pad"], hasColor, 2)
                    val m = provider.getMaterial(key, IntArray(8), "heat")
                    val mi = heatMi ?: provider.createMaterialInstance(key, IntArray(8), "heat", null)?.also { if (m != null) { SPECS.getValue("Heat_Pad").applyTo(it, m); matOf[it] = m } }
                    heatMi = mi
                    if (mi != null) rm.setMaterialInstanceAt(ri, p, mi)
                    heatPads[e] = sideIn(e, "Heat_Pad")
                }
            }
            if (shell) { shellRenderables += e; rm.setPriority(ri, when { named(e, SHELL_INNER) -> 3; named(e, SHELL_GLASS) -> 5; else -> 4 }) }
            rm.setCastShadows(ri, false); rm.setReceiveShadows(ri, false)
        }
        scene.addEntities(main.entities)
        childSeatLocal = if (childSeat != 0) tm.getTransform(tm.getInstance(childSeat), FloatArray(16)) else null
    }

    private fun n(e: Int) = nameOf(e)

    /** gltfio 제공자 열쇠(GLB 재질과 같은 조합). 복제·제공자 인스턴스는 Java 쪽 material이 비어 있어 이 열쇠로 재질을 다시 얻는다. */
    private fun keyFor(spec: Spec?, hasColor: Boolean, alpha: Int) = MaterialProvider.MaterialKey().apply {
        doubleSided = true; hasVertexColors = hasColor; hasClearCoat = spec?.clearCoat != null; alphaMode = alpha
    }

    private fun setupGhost() {
        for (e in ghostInst.entities) {
            val ri = rm.getInstance(e)
            if (nameOf(e) == "ChildSeat") childSeatGhost = e
            if (ri == 0) continue
            val isChild = named(e, CHILD)
            val isGhost = named(e, GHOST)
            val isLine = named(e, LINES)
            if (!isChild && !isGhost && !isLine) continue
            val mi = if (isChild) childGhost else if (isLine) lineMi else ghost
            for (p in 0 until rm.getPrimitiveCount(ri)) rm.setMaterialInstanceAt(ri, p, mi)
            rm.setPriority(ri, if (isChild) 7 else 6)
            rm.setCastShadows(ri, false); rm.setReceiveShadows(ri, false)
            when { isChild -> childGhostRenderables += e; isLine -> lineRenderables += e; else -> ghostRenderables += e }
            scene.addEntity(e)
            visible(e, false)
        }
    }

    /** 껍데기: k=0 불투명, 0<k<1 BLEND 짝(불투명도 1-k, 유리는 1-0.94k), k≥0.995 숨김(유령 겹만). */
    private fun setShell(k: Float, dark: Boolean) {
        val fading = k > 0.001f
        for (e in shellRenderables) {
            val ri = rm.getInstance(e)
            visible(e, k < 0.995f)
            for (p in 0 until rm.getPrimitiveCount(ri)) {
                val mi = (if (fading) shellFade[ri to p] else null) ?: shellOpaque[ri to p] ?: continue
                if (rm.getMaterialInstanceAt(ri, p) !== mi) rm.setMaterialInstanceAt(ri, p, mi)
            }
        }
        if (fading) for ((name, list) in shellFadeByName) {
            val spec = SPECS[name] ?: continue
            val c = spec.color
            list.forEach { it.setParameter("baseColorFactor", c[0], c[1], c[2], 1 - k) }
        }
        // 유리(원래 BLEND): 틴트를 맑게, 불투명도 낮춤
        for ((name, spec) in SPECS) if (spec.blend && spec.clear != null) {
            val mi = mats["shell_$name"] ?: mats[name] ?: continue
            val c = mix(spec.color, spec.clear, min(1f, k * 1.5f))
            mi.setParameter("baseColorFactor", c[0], c[1], c[2], spec.alpha * (1 - 0.94f * k))
        }
    }

    private fun visible(e: Int, on: Boolean) { val ri = rm.getInstance(e); if (ri != 0) rm.setLayerMask(ri, 0xFF, if (on) 0x1 else 0) }

    private fun emissive(name: String, c: FloatArray, i: Float) {
        listOfNotNull(mats[name], mats["shell_$name"]).forEach { mi ->
            if (matOf[mi]?.hasParameter("emissiveFactor") == true) mi.setParameter("emissiveFactor", c[0] * i * EMISSIVE_GAIN, c[1] * i * EMISSIVE_GAIN, c[2] * i * EMISSIVE_GAIN)
        }
    }

    private fun baseColor(name: String, c: FloatArray, a: Float) = listOfNotNull(mats[name], mats["shell_$name"]).forEach { it.setParameter("baseColorFactor", c[0], c[1], c[2], a) }

    private fun moveChildSeat(seat: Seat3d) {
        val local = childSeatLocal ?: return
        val m = local.copyOf().also { it[14] = local[14] + seat.z - Seat3d.RR.z }
        if (debugNoChildSeat) for (i in 0..10) if (i % 4 != 3) m[i] = 0f   // 회전·크기 3×3을 0으로(자식 렌더러블까지 안 보임)
        tm.setTransform(tm.getInstance(childSeat), m)
        if (childSeatGhost != 0) tm.setTransform(tm.getInstance(childSeatGhost), m)
    }

    // 맞춤 재질 ───────────────────

    /** 작업 스레드에서 미리 컴파일한 재질 꾸러미([Prep.mats])를 올린다. */
    private fun material(name: String): Material = prep.mats.getValue(name).let { Material.Builder().payload(it, it.remaining()).build(engine) }

    /** 점 넷을 지나는 Catmull-Rom 관(반지름 [r], 길이 48·둘레 8칸). 위치 + UV0(x = 길이 방향). */
    private fun tube(pts: List<FloatArray>, r: Float, mi: MaterialInstance): Int {
        val seg = 48; val rad = 8
        fun cr(i: Int, t: Float): FloatArray {
            val p0 = pts[max(i - 1, 0)]; val p1 = pts[i]; val p2 = pts[min(i + 1, pts.size - 1)]; val p3 = pts[min(i + 2, pts.size - 1)]
            val t2 = t * t; val t3 = t2 * t
            return FloatArray(3) { k -> 0.5f * (2 * p1[k] + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2 + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3) }
        }
        val spans = pts.size - 1
        val centers = (0..seg).map { j -> val u = j.toFloat() / seg * spans; val i = min(u.toInt(), spans - 1); cr(i, u - i) }
        val pos = FloatArray((seg + 1) * (rad + 1) * 3); val uv = FloatArray((seg + 1) * (rad + 1) * 2)
        for (j in 0..seg) {
            val c = centers[j]; val n = centers[min(j + 1, seg)]; val pv = centers[max(j - 1, 0)]
            var tx = n[0] - pv[0]; var ty = n[1] - pv[1]; var tz = n[2] - pv[2]
            val tl = sqrt(tx * tx + ty * ty + tz * tz); tx /= tl; ty /= tl; tz /= tl
            // 법선: 위(+Y)와 접선의 외적, 접선이 거의 위면 +Z로
            var nx = -tz; var ny = 0f; var nz = tx
            var nl = sqrt(nx * nx + nz * nz); if (nl < 1e-3f) { nx = 0f; ny = -tz; nz = ty; nl = sqrt(ny * ny + nz * nz) }
            nx /= nl; ny /= nl; nz /= nl
            val bx = ty * nz - tz * ny; val by = tz * nx - tx * nz; val bz = tx * ny - ty * nx
            for (q in 0..rad) {
                val a = q.toFloat() / rad * 2 * PI.toFloat(); val ca = cos(a); val sa = sin(a)
                val o = j * (rad + 1) + q
                pos[o * 3] = c[0] + r * (ca * nx + sa * bx); pos[o * 3 + 1] = c[1] + r * (ca * ny + sa * by); pos[o * 3 + 2] = c[2] + r * (ca * nz + sa * bz)
                uv[o * 2] = j.toFloat() / seg; uv[o * 2 + 1] = q.toFloat() / rad
            }
        }
        val idx = ShortArray(seg * rad * 6)
        for (j in 0 until seg) for (q in 0 until rad) {
            val a = j * (rad + 1) + q; val b = a + rad + 1; val o = (j * rad + q) * 6
            idx[o] = a.toShort(); idx[o + 1] = b.toShort(); idx[o + 2] = (a + 1).toShort(); idx[o + 3] = (a + 1).toShort(); idx[o + 4] = b.toShort(); idx[o + 5] = (b + 1).toShort()
        }
        val n = pos.size / 3
        val vb = VertexBuffer.Builder().vertexCount(n).bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8).build(engine)
        vb.setBufferAt(engine, 0, ByteBuffer.allocateDirect(pos.size * 4).order(ByteOrder.nativeOrder()).also { b -> pos.forEach { b.putFloat(it) }; b.flip() })
        vb.setBufferAt(engine, 1, ByteBuffer.allocateDirect(uv.size * 4).order(ByteOrder.nativeOrder()).also { b -> uv.forEach { b.putFloat(it) }; b.flip() })
        val ib = IndexBuffer.Builder().indexCount(idx.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        ib.setBuffer(engine, ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).also { b -> idx.forEach { b.putShort(it) }; b.flip() })
        val e = em.create()
        RenderableManager.Builder(1).boundingBox(Box(-0.6f, 0.8f, 0f, 0.6f, 0.3f, 0.6f)).geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
            .material(0, mi).culling(false).castShadows(false).receiveShadows(false).priority(6).build(engine, e)
        scene.addEntity(e)
        return e
    }

    // 스튜디오 환경광(시안 studio: 어두운 방 + 위 소프트박스 + 옆 띠, 외부 HDRI 없음) ───────────

    /** CPU에서 만든 큐브맵 밉들([Prep.cube])을 올린다(형식 RGB16F, 면 6개를 층으로). */
    private fun uploadIbl(levels: List<ByteBuffer>): IndirectLight {
        val size = 64
        val tex = Texture.Builder().width(size).height(size).levels(levels.size).sampler(Texture.Sampler.SAMPLER_CUBEMAP).format(Texture.InternalFormat.RGB16F).build(engine)
        var s = size
        levels.forEachIndexed { lv, buf ->
            tex.setImage(engine, lv, 0, 0, 0, s, s, 6, Texture.PixelBufferDescriptor(buf, Texture.Format.RGB, Texture.Type.FLOAT))
            s = maxOf(1, s / 2)
        }
        return IndirectLight.Builder().reflections(tex).intensity(1f).build(engine)
    }

    // 효과 메시 ───────────────────

    private inner class Mesh(geo: Pair<FloatArray, ShortArray>, val mi: MaterialInstance, priority: Int, vertexAlpha: FloatArray? = null) {
        val entity = em.create()
        init {
            val (pos, idx) = geo
            val n = pos.size / 3
            val vb = VertexBuffer.Builder().vertexCount(n).bufferCount(2)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
                .attribute(VertexBuffer.VertexAttribute.COLOR, 1, VertexBuffer.AttributeType.UBYTE4, 0, 4).normalized(VertexBuffer.VertexAttribute.COLOR)
                .build(engine)
            vbOf[entity] = vb
            vb.setBufferAt(engine, 0, ByteBuffer.allocateDirect(pos.size * 4).order(ByteOrder.nativeOrder()).also { b -> pos.forEach { b.putFloat(it) }; b.flip() })
            vb.setBufferAt(engine, 1, ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).also { b ->
                repeat(n) { i -> val a = ((vertexAlpha?.get(i) ?: 1f) * 255).toInt().coerceIn(0, 255).toByte(); b.put(-1).put(-1).put(-1).put(a) }; b.flip()
            })
            val ib = IndexBuffer.Builder().indexCount(idx.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
            ib.setBuffer(engine, ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).also { b -> idx.forEach { b.putShort(it) }; b.flip() })
            var mx = 0f; for (v in pos) mx = max(mx, abs(v))
            RenderableManager.Builder(1).boundingBox(Box(0f, 0f, 0f, mx + 0.01f, mx + 0.01f, mx + 0.01f)).geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
                .material(0, mi).culling(false).castShadows(false).receiveShadows(false).priority(priority).build(engine, entity)
            scene.addEntity(entity)
        }
        fun color(c: FloatArray, a: Float) = mi.setParameter("color", c[0], c[1], c[2], a)
    }

    private inner class Arc(val speaker: String, val i: Int, val mesh: Mesh) { var reach = 0.35f; var pos = floatArrayOf(0f, 0.66f, 0f) }

    /** XY 평면의 고리(반지름 r0~r1, 각 a0부터 a0+len). 바닥에 눕히는 것은 변환이 한다. */
    private fun ring(r0: Float, r1: Float, seg: Int, a0: Float, len: Float): Pair<FloatArray, ShortArray> {
        val pos = FloatArray((seg + 1) * 2 * 3); val idx = ShortArray(seg * 6)
        for (i in 0..seg) {
            val a = a0 + len * i / seg
            listOf(r0, r1).forEachIndexed { j, r -> val o = (i * 2 + j) * 3; pos[o] = cos(a) * r; pos[o + 1] = sin(a) * r; pos[o + 2] = 0f }
        }
        for (i in 0 until seg) { val b = (i * 2).toShort(); listOf(0, 1, 2, 1, 3, 2).forEachIndexed { j, d -> idx[i * 6 + j] = (b + d).toShort() } }
        return pos to idx
    }

    /** 고리를 바닥에 눕힌다(시안 rotation.x = -π/2: 링 평면 XY → XZ, +Y가 -Z로). */
    private fun rotFlat(e: Int, s: Float) = tm.setTransform(tm.getInstance(e), flat(0f, 0f, 0f, s))
    private fun flat(x: Float, y: Float, z: Float, s: Float) = floatArrayOf(s, 0f, 0f, 0f, 0f, 0f, -s, 0f, 0f, s, 0f, 0f, x, y, z, 1f)
    private fun place(e: Int, x: Float, y: Float, z: Float, s: Float) = tm.setTransform(tm.getInstance(e), flat(x, y, z, s))
    private fun scaleAt(e: Int, p: FloatArray, s: Float) = place(e, p[0], p[1], p[2], max(s, 1e-4f))

    private fun rebuildArcs(seat: Seat3d, at: FloatArray) {
        arcSeat = seat
        for (a in arcs) {
            val sp = anchor(a.speaker)
            a.pos = floatArrayOf(sp[0], 0.66f, sp[2])
            val dx = at[0] - sp[0]; val dz = at[2] - sp[2]
            a.reach = max(0.35f, sqrt(dx * dx + dz * dz))
            val ang = atan2(-dz, dx); val spread = 72f * CarMath.D2R
            // 눕힌 뒤 XY의 +Y가 세계 -Z가 되므로 각은 시안과 같은 식(atan2(-dz, dx))
            val (pos, _) = ring(0.93f, 1f, 40, ang - spread / 2, spread)
            a.mesh.updatePositions(pos)
        }
    }

    private fun Mesh.updatePositions(pos: FloatArray) {
        // 같은 꼭짓점 수라 위치 버퍼만 바꾼다
        val vb = vbOf[entity] ?: return
        vb.setBufferAt(engine, 0, ByteBuffer.allocateDirect(pos.size * 4).order(ByteOrder.nativeOrder()).also { b -> pos.forEach { b.putFloat(it) }; b.flip() })
    }

    /** 손으로 그린 부드러운 바닥 그림자(시안 shadow: 겹친 둥근 사각형 + 바퀴 접지 네 점)를 꼭짓점 알파 격자로. */
    private fun shadowMesh(): Mesh {
        val nx = 48; val nz = 24; val sx = 7.6f; val sz = 3.8f
        val pos = FloatArray((nx + 1) * (nz + 1) * 3); val alpha = FloatArray((nx + 1) * (nz + 1)); val idx = ShortArray(nx * nz * 6)
        for (j in 0..nz) for (i in 0..nx) {
            val u = i.toFloat() / nx; val v = j.toFloat() / nz
            val v0 = j * (nx + 1) + i
            pos[v0 * 3] = -0.08f + (u - 0.5f) * sx; pos[v0 * 3 + 1] = 0.002f; pos[v0 * 3 + 2] = (v - 0.5f) * sz
            alpha[v0] = shadowAlpha(u * 512f, v * 256f)
        }
        for (j in 0 until nz) for (i in 0 until nx) {
            val b = j * (nx + 1) + i; val o = (j * nx + i) * 6
            idx[o] = b.toShort(); idx[o + 1] = (b + 1).toShort(); idx[o + 2] = (b + nx + 1).toShort()
            idx[o + 3] = (b + 1).toShort(); idx[o + 4] = (b + nx + 2).toShort(); idx[o + 5] = (b + nx + 1).toShort()
        }
        return Mesh(pos to idx, vtxMat.createInstance("shadow"), 0, alpha).also { it.color(floatArrayOf(0f, 0f, 0f), 1f) }
    }

    /** 시안 캔버스(512×256)의 그림자 알파: 둥근 사각형 26겹(0.028+0.02k) + 바퀴 자리 방사 그러데이션(0.55→0). */
    private fun shadowAlpha(x: Float, y: Float): Float {
        var a = 0f
        for (i in 0 until 26) {
            val k = i / 25f
            val rx = 48 + k * 60; val ry = 52 + k * 34; val rw = 416 - k * 120; val rh = 152 - k * 68; val r = 70 - k * 40
            if (inRoundRect(x, y, rx, ry, rw, rh, r)) a += (0.028f + 0.02f * k) * (1 - a)
        }
        for ((cx, cy) in listOf(154f to 92f, 154f to 164f, 358f to 92f, 358f to 164f)) {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            if (d < 34f) a += 0.55f * (1 - d / 34f) * (1 - a)
        }
        return a
    }

    private fun inRoundRect(x: Float, y: Float, rx: Float, ry: Float, w: Float, h: Float, r: Float): Boolean {
        if (x < rx || y < ry || x > rx + w || y > ry + h) return false
        val cx = x.coerceIn(rx + r, rx + w - r); val cy = y.coerceIn(ry + r, ry + h - r)
        return (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
    }
}

/**
 * 작업 스레드에서 미리 하는 일(Filament 없음): GLB 바이트, 노드마다 COLOR_0(구운 AO) 여부, 스튜디오 환경광 큐브맵 밉(라이트·다크).
 * 에뮬레이터(GLES 3.0, 기능 수준 1)에서 IBLPrefilterContext가 전제 조건으로 멈춰(실측 11-10) 큐브맵을 CPU에서 만든다:
 * 0단계는 스튜디오 밝기를 해석적으로, 다음 단계는 2×2 평균(거친 재질일수록 흐린 반사).
 */
class Prep private constructor(
    val glb: ByteArray, val colors: Map<String, List<Boolean>>, val cube: Map<Boolean, List<ByteBuffer>>, val mats: Map<String, ByteBuffer>, val ms: Long,
    /** 작업 스레드 시간 나눔(로그용): GLB 읽기·JSON, 환경광 계산. */
    val detail: String,
) {
    companion object {
        fun load(ctx: Context): Prep {
            val t0 = System.nanoTime()
            val bytes = ctx.assets.open("car/aigyeot_car.glb").use { it.readBytes() }
            val colors = HashMap<String, List<Boolean>>()
            readGlbJson(bytes, colors)
            if (CarEngine.debugNoAo) whitenColors(bytes)
            val t1 = System.nanoTime()
            // 라이트·다크 환경광은 서로 기대지 않아 다크를 다른 스레드에서 같이 만든다(작업 스레드 시간이 첫 3D 장까지의 기다림에 그대로 더해진다)
            val darkCube = java.util.concurrent.FutureTask { cubeLevels(true) }.also { Thread(it, "car3d-ibl").start() }
            val cube = mapOf(false to cubeLevels(false), true to darkCube.get())
            val t2 = System.nanoTime()
            // 맞춤 재질은 빌드 때 matc(Filament 1.77.3)로 컴파일한 꾸러미(assets/materials, 원본 src/main/materials/*.mat). 실행 중 컴파일(filamat-jni, ABI당 약 9MB)을 빼 APK를 줄였다
            val mats = listOf("ghost", "flat", "vtx", "add", "flow").associateWith { n ->
                ctx.assets.open("materials/$n.filamat").use { it.readBytes() }.let { b -> ByteBuffer.allocateDirect(b.size).order(ByteOrder.nativeOrder()).put(b).also { it.flip() } }
            }
            return Prep(bytes, colors, cube, mats, (System.nanoTime() - t0) / 1_000_000, "읽기 ${(t1 - t0) / 1_000_000}·환경광 ${(t2 - t1) / 1_000_000}")
        }

        fun whitenColors(b: ByteArray) {
            val len = ByteBuffer.wrap(b, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val j = JSONObject(String(b, 20, len, Charsets.UTF_8))
            val bin = 20 + len + 8
            val meshes = j.getJSONArray("meshes"); val accs = j.getJSONArray("accessors"); val views = j.getJSONArray("bufferViews")
            for (m in 0 until meshes.length()) { val prims = meshes.getJSONObject(m).getJSONArray("primitives")
                for (p in 0 until prims.length()) { val at = prims.getJSONObject(p).getJSONObject("attributes"); if (!at.has("COLOR_0")) continue
                    val a = accs.getJSONObject(at.getInt("COLOR_0")); val v = views.getJSONObject(a.getInt("bufferView"))
                    val off = bin + v.optInt("byteOffset") + a.optInt("byteOffset"); val n = a.getInt("count") * 8
                    for (i in off until off + n) b[i] = -1
                }
            }
        }

        fun readGlbJson(b: ByteArray, glbColor: HashMap<String, List<Boolean>>) {
            val len = ByteBuffer.wrap(b, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val j = JSONObject(String(b, 20, len, Charsets.UTF_8))
            val meshes = j.getJSONArray("meshes"); val nodes = j.getJSONArray("nodes")
            for (i in 0 until nodes.length()) {
                val n = nodes.getJSONObject(i)
                if (!n.has("mesh")) continue
                val prims = meshes.getJSONObject(n.getInt("mesh")).getJSONArray("primitives")
                glbColor[n.optString("name")] = (0 until prims.length()).map { prims.getJSONObject(it).getJSONObject("attributes").has("COLOR_0") }
            }
        }

        /** 큐브맵 밉 단계마다 면 6개를 이어 붙인 RGB float 버퍼(올리기만 하면 된다). */
        private fun cubeLevels(dark: Boolean): List<ByteBuffer> = cubeFaces(dark).map { faces ->
            ByteBuffer.allocateDirect(faces.sumOf { it.size } * 4).order(ByteOrder.nativeOrder()).also { b -> faces.forEach { px -> px.forEach { b.putFloat(it) } }; b.flip() }
        }

        private fun cubeFaces(dark: Boolean): List<Array<FloatArray>> {
            val size = 64
            val room = lin(if (dark) 0x1C1F23 else 0x646A72); val floor = lin(if (dark) 0x111316 else 0x8A9097)
            var faces = Array(6) { f -> FloatArray(size * size * 3).also { px ->
                for (y in 0 until size) for (x in 0 until size) {
                    val sc = (x + 0.5f) / size * 2 - 1; val tc = (y + 0.5f) / size * 2 - 1
                    val d = when (f) { 0 -> floatArrayOf(1f, -tc, -sc); 1 -> floatArrayOf(-1f, -tc, sc); 2 -> floatArrayOf(sc, 1f, tc); 3 -> floatArrayOf(sc, -1f, -tc); 4 -> floatArrayOf(sc, -tc, 1f); else -> floatArrayOf(-sc, -tc, -1f) }
                    val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                    val c = studioRadiance(d[0] / l, d[1] / l, d[2] / l, room, floor, dark)
                    val o = (y * size + x) * 3; px[o] = c[0]; px[o + 1] = c[1]; px[o + 2] = c[2]
                }
            } }
            val out = ArrayList<Array<FloatArray>>()
            var s = size
            while (true) {
                out += faces
                if (s == 1) break
                val n = s / 2; val src = faces; val ss = s
                faces = Array(6) { f -> FloatArray(n * n * 3).also { o ->
                    val q = src[f]
                    for (y in 0 until n) for (x in 0 until n) for (c in 0 until 3) {
                        o[(y * n + x) * 3 + c] = (q[((2 * y) * ss + 2 * x) * 3 + c] + q[((2 * y) * ss + 2 * x + 1) * 3 + c] + q[((2 * y + 1) * ss + 2 * x) * 3 + c] + q[((2 * y + 1) * ss + 2 * x + 1) * 3 + c]) / 4
                    }
                } }
                s = n
            }
            return out
        }
    }
}

/**
 * 원점에서 (dx,dy,dz)로 본 스튜디오 밝기(선형, 시안 v7 studio). 바닥 밝은 자리(라이트 0.62·다크 0.10), 천장 긴 띠 셋(가운데 4.2, 옆 3.4),
 * 벽 긴 띠 3.2, 낮은 띠 1.8, 앞뒤 세로 판 2.2. 다크는 바닥 자리 밖 조명을 0.55배. 천장 옆 띠는 시안에서 원점을 향해 약 19° 기울지만 수평 판으로 둔다.
 */
private fun studioRadiance(dx: Float, dy: Float, dz: Float, room: FloatArray, floor: FloatArray, dark: Boolean): FloatArray {
    var best = Float.MAX_VALUE; var k = -1f
    // 화소마다 판 열 개를 본다(면 6 × 64² × 테마 2): 배열·쌍을 만들지 않고 같은 식으로 계산한다(결과는 전과 같은 값, 작업 스레드 시간을 줄인다)
    fun panel(axis: Int, at: Float, cu: Float, cv: Float, hu: Float, hv: Float, kk: Float) {
        val da = when (axis) { 0 -> dx; 1 -> dy; else -> dz }
        if (abs(da) < 1e-4f) return
        val t = at / da
        if (t <= 0f || t >= best) return
        val u = if (axis == 0) dz * t else dx * t   // 판 위 좌표: x축 판 (z, y), y축 판 (x, z), z축 판 (x, y)
        val v = if (axis == 1) dz * t else dy * t
        if (abs(u - cu) <= hu && abs(v - cv) <= hv) { best = t; k = kk }
    }
    val f = if (dark) 0.55f else 1f
    panel(1, -1.88f, 0f, 0f, 5.5f, 3.5f, if (dark) 0.10f else 0.62f)   // 바닥: 차 둘레 밝은 자리(11×7)
    panel(1, 13.5f, 0f, 0f, 10f, 1.2f, 4.2f * f)        // 천장 가운데 긴 띠(20×2.4)
    panel(1, 13.2f, 0f, 4.6f, 10f, 0.75f, 3.4f * f)     // 천장 옆 띠(20×1.5)
    panel(1, 13.2f, 0f, -4.6f, 10f, 0.75f, 3.4f * f)
    panel(2, 15.5f, 0f, 6.0f, 11f, 0.8f, 3.2f * f)      // 벽 긴 띠(22×1.6, 어깨 위 면에 맺힌다)
    panel(2, -15.5f, 0f, 6.0f, 11f, 0.8f, 3.2f * f)
    panel(2, 15.8f, 0f, 2.6f, 11f, 0.35f, 1.8f * f)     // 낮은 띠(22×0.7)
    panel(2, -15.8f, 0f, 2.6f, 11f, 0.35f, 1.8f * f)
    panel(0, 15.5f, 0f, 4.5f, 2.5f, 4f, 2.2f * f)       // 앞·뒤 세로 판(5×8)
    panel(0, -15.5f, 0f, 4.5f, 2.5f, 4f, 2.2f * f)
    if (k >= 0f) return floatArrayOf(k, k, k)
    return if (dy < -0.1f) floor else room
}


/** 재질 하나의 덮어쓸 값(시안 materials(): 색은 sRGB → 선형). */
private class Spec(hex: Int, val roughness: Float, val metallic: Float = 0f, val clearCoat: Float? = null, val ccRough: Float = 0f,
                   val blend: Boolean = false, val alpha: Float = 1f, clearHex: Int? = null, val emissive: FloatArray? = null, val emissiveStrength: Float = 1f,
                   colorGain: Float = 1f, val reflectance: Float? = null) {
    val color = lin(hex).map { it * colorGain }.toFloatArray()
    val clear = clearHex?.let(::lin)
    /** [m]은 [mi]의 재질(복제·제공자가 만든 인스턴스는 Java 쪽 material이 비어 있어 따로 받는다). */
    fun applyTo(mi: MaterialInstance, m: Material) {
        if (m.hasParameter("baseColorFactor")) mi.setParameter("baseColorFactor", color[0], color[1], color[2], alpha)
        if (m.hasParameter("roughnessFactor")) mi.setParameter("roughnessFactor", roughness)
        if (m.hasParameter("metallicFactor")) mi.setParameter("metallicFactor", metallic)
        if (reflectance != null && m.hasParameter("reflectance")) mi.setParameter("reflectance", reflectance)
        if (clearCoat != null && m.hasParameter("clearCoatFactor")) { mi.setParameter("clearCoatFactor", clearCoat); if (m.hasParameter("clearCoatRoughnessFactor")) mi.setParameter("clearCoatRoughnessFactor", ccRough) }
        // 제공자가 새로 만든 인스턴스는 emissiveStrength가 0이라(발광 = 색 × 세기) 늘 넣는다. 발광이 없는 재질은 1(색만으로)
        val hasStrength = m.hasParameter("emissiveStrength")
        if (hasStrength) mi.setParameter("emissiveStrength", if (emissive != null) emissiveStrength else 1f)
        if (emissive != null && m.hasParameter("emissiveFactor")) {
            val k = if (hasStrength) 1f else emissiveStrength
            mi.setParameter("emissiveFactor", emissive[0] * k, emissive[1] * k, emissive[2] * k)
        }
    }
}

/** 시안 car3d.js materials()의 값. 유리는 투과 대신 BLEND(GLB 알파)에 틴트 색. 램프 발광은 GLB 값 그대로. */
private val SPECS: Map<String, Spec> = mapOf(
    "Paint" to Spec(0xE4E7EA, 0.42f, 0.26f, clearCoat = 1f, ccRough = 0.03f),
    "Trim_Black" to Spec(0x141618, 0.55f),
    "Black_Gloss" to Spec(0x07080A, 0.12f, clearCoat = 1f, ccRough = 0.03f),
    // 크롬 환경광 1.5배(시안 envMapIntensity): Filament 표준 재질에는 재질마다 환경광 배율이 없어, 금속 1의 반사색(기본색)을 1.5배로 올린다
    "Chrome" to Spec(0xE2E6EA, 0.08f, 1f, colorGain = 1.5f),
    "Grille_Chrome" to Spec(0xCFD4DA, 0.16f, 1f),
    "Grille_Base" to Spec(0x08090B, 0.45f),
    // 유리: 거칠기 0.03, 정반사 세기 0.25(three specularIntensity가 F0 0.04를 0.01로 → Filament reflectance 0.25: F0 = 0.16·r²)
    "Glass_Front" to Spec(0x59626A, 0.03f, blend = true, alpha = 0.30f, clearHex = 0x9AA3AB, reflectance = GLASS_REFLECTANCE),
    "Glass_Side" to Spec(0x262C32, 0.03f, blend = true, alpha = 0.55f, clearHex = 0x9EA8B0, reflectance = GLASS_REFLECTANCE),
    "Glass_Rear" to Spec(0x262C32, 0.03f, blend = true, alpha = 0.55f, clearHex = 0x7C868E, reflectance = GLASS_REFLECTANCE),
    "Sunroof_Glass" to Spec(0x15191D, 0.03f, blend = true, alpha = 0.75f, clearHex = 0x6C767E, reflectance = GLASS_REFLECTANCE),
    "Roofglass_Shade" to Spec(0x0E1013, 0.92f),   // 검은 가림막: 지붕 유리가 앞·뒤 유리와 같은 검정으로
    "Rear_L_Stop" to Spec(0x3A0809, 0.3f),
    // 램프: GLB 값 그대로(발광 색 × 세기 KHR_materials_emissive_strength). 껍데기라 X-ray로 갈 때 함께 흐린다
    "Head_L_All" to Spec(0xFFFFFF, 0.2f, emissive = floatArrayOf(0.888f, 0.930f, 1f), emissiveStrength = 4f),
    "Rear_L_Pos" to Spec(0x5A0A0C, 0.2f, emissive = floatArrayOf(1f, 0.0232f, 0.0176f), emissiveStrength = 3f),
    "Panel_Gap" to Spec(0x050506, 0.6f),
    "Tire" to Spec(0x18191B, 0.8f),
    "Rim_Silver" to Spec(0xDFE3E7, 0.26f, 0.75f),
    "Rim_Black" to Spec(0x0F1012, 0.38f, 0.3f),
    "Liner" to Spec(0x0A0A0B, 0.95f),
    "Interior_Dark" to Spec(0x2A2C30, 0.62f),
    "Seat" to Spec(0x7C6B5D, 0.78f),
    "Carpet" to Spec(0x3B3A38, 0.95f),
    "Display" to Spec(0x050607, 0.3f, clearCoat = 0.3f),
    "Interior_Trim" to Spec(0xA99F92, 0.85f),
    "Speaker" to Spec(0x1A1C1F, 0.7f),
    "Speaker_Ring" to Spec(0x4A4D52, 0.3f, 0.7f),
    "Ambient" to Spec(0x1D2023, 0.4f),
    "Heat_Pad" to Spec(0x3A2F28, 0.7f),
    "Reading_Lamp" to Spec(0xD8D4CC, 0.3f),
    "ChildSeat_Shell" to Spec(0xE6E0D6, 0.45f),
    "ChildSeat_Cushion" to Spec(0xF2E9DB, 0.8f),
    "ChildSeat_Canopy" to Spec(0x2C2E32, 0.85f),
    "ChildSeat_Handle" to Spec(0x5E5852, 0.36f),
    "ChildSeat_Base" to Spec(0x3B3D41, 0.45f),
)

/** 유리 정반사 세기(시안 specularIntensity 0.25 → Filament reflectance). */
private const val GLASS_REFLECTANCE = 0.25f

/** 발광 세기 보정(Filament 발광 단위를 시안 three.js 세기에 맞추는 값, 에뮬레이터 캡처로 맞춤). */
private const val EMISSIVE_GAIN = 1f

internal fun lin(hex: Int): FloatArray = intArrayOf((hex shr 16) and 0xFF, (hex shr 8) and 0xFF, hex and 0xFF).let { c ->
    FloatArray(3) { i -> (c[i] / 255f).let { if (it <= 0.04045f) it / 12.92f else ((it + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() } }
}
private fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }
private fun log2(x: Float) = (kotlin.math.ln(x.toDouble()) / kotlin.math.ln(2.0)).toFloat()
