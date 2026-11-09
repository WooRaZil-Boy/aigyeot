package kr.aigyeot.phone.presentation.car3d

import android.content.Context
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
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 폰 3D 장면 하나(프로세스 수명, Filament 호출은 메인 스레드): GLB(assets/car/aigyeot_car.glb), 재질 덮어쓰기(이름이 계약), 스튜디오 환경광, 열쇠 빛, 바닥 그림자.
 * 폰은 바깥 보기(PRESET tile·phone, X-ray 0)만 쓰므로 유령 껍데기·제어 표시는 없다(차 앱 레시피 1~8·11·12, progress-1110-car '3D 레시피').
 * 빛 상태는 시안 phone.js의 data-light="listen"(실내 앰비언트 선이 곁의 응답 파랑으로 켜짐) 하나다.
 */
class CarScene private constructor() {
    companion object {
        private const val TAG = "CarScene"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var loading: Deferred<CarScene>? = null

        /**
         * 장면을 한 번 만든다(프로세스 수명). 파일 읽기·GLB 이름표·환경광 픽셀 계산은 작업 스레드에서,
         * Filament 객체 만들기는 메인 스레드에서 단계마다 한 프레임씩 양보하며 한다(첫 화면이 멈추지 않게).
         */
        @MainThread
        fun load(ctx: Context): Deferred<CarScene> = loading ?: scope.async {
            val app = ctx.applicationContext
            val t0 = System.nanoTime()
            val prep = withContext(Dispatchers.Default) { Prep.read(app) }
            Log.i(TAG, "준비 계산 ${(System.nanoTime() - t0) / 1_000_000}ms")
            Gltfio.init()
            CarScene().also { it.build(prep); Log.i(TAG, "3D 준비 ${(System.nanoTime() - t0) / 1_000_000}ms, 렌더러블 ${it.asset.renderableEntities.size}") }
        }.also { loading = it }

        /** 곁의 응답 빛(tokens glow). */
        private const val GLOW = 0x7AA5FF
    }

    /** 작업 스레드에서 미리 만드는 것: GLB 바이트, 노드별 COLOR_0 여부, 환경광 큐브맵 단계별 면 픽셀(라이트·다크). */
    private class Prep(val glb: ByteBuffer, val glbColor: Map<String, List<Boolean>>, val faces: Map<Boolean, List<Array<FloatArray>>>) {
        companion object {
            fun read(ctx: Context): Prep {
                val bytes = ctx.assets.open("car/aigyeot_car.glb").use { it.readBytes() }
                val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
                return Prep(buf, readGlbJson(bytes), mapOf(false to studioFaces(false), true to studioFaces(true)))
            }
        }
    }

    val engine: Engine = Engine.create()
    val scene: Scene = engine.createScene()
    private val em = EntityManager.get()
    private val rm get() = engine.renderableManager

    val colorGrading: ColorGrading = ColorGrading.Builder().toneMapper(ToneMapper.PBRNeutral()).exposure(log2(1.05f)).build(engine)

    private val provider = UbershaderProvider(engine)
    private val loader = AssetLoader(engine, provider, em)
    private lateinit var asset: FilamentAsset
    private var glbColor: Map<String, List<Boolean>> = emptyMap()
    private val mats = HashMap<String, MaterialInstance>()
    private val matOf = HashMap<MaterialInstance, Material>()
    private val key = em.create()
    private lateinit var ibl: Map<Boolean, IndirectLight>
    private var lastDark: Boolean? = null

    private suspend fun build(p: Prep) {
        glbColor = p.glbColor
        var t = System.nanoTime()
        fun lap(what: String) { Log.d(TAG, "$what ${(System.nanoTime() - t) / 1_000_000}ms"); t = System.nanoTime() }
        asset = loader.createAsset(p.glb) ?: error("GLB를 읽지 못함")
        lap("GLB")
        yield()
        ResourceLoader(engine).run { loadResources(asset); destroy() }
        asset.releaseSourceData()
        lap("자원")
        yield()
        overrideMaterials()
        scene.addEntities(asset.entities)
        lap("재질")
        yield()
        ibl = p.faces.mapValues { (_, levels) -> iblFrom(levels) }
        lap("환경광")
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f, 1f, 1f).intensity(1.4f).direction(-3f, -6f, 2f).castShadows(false).build(engine, key)
        scene.addEntity(key)
        shadow()
        // 듣는 중(listen): 실내 앰비언트 선 파랑 2.2(시안 applyState)
        val g = lin(GLOW)
        mats["Ambient"]?.let { mi -> if (matOf[mi]?.hasParameter("emissiveFactor") == true) mi.setParameter("emissiveFactor", g[0] * 2.2f, g[1] * 2.2f, g[2] * 2.2f) }
    }

    /** 그리기 전에 테마에 맞는 환경광을 건다(라이트·다크는 방 밝기만 다르다). */
    fun theme(dark: Boolean) {
        if (lastDark != dark) { scene.indirectLight = ibl.getValue(dark); lastDark = dark }
    }

    // ───────────── 만들기 ─────────────

    /** gltfio 제공자 열쇠(GLB 재질과 같은 조합). 제공자 인스턴스는 Java 쪽 material이 비어 있어 이 열쇠로 재질을 다시 얻는다(레시피 4). */
    private fun keyFor(spec: Spec?, hasColor: Boolean, alpha: Int) = MaterialProvider.MaterialKey().apply {
        doubleSided = true; hasVertexColors = hasColor; hasClearCoat = spec?.clearCoat != null; alphaMode = alpha
    }

    private fun overrideMaterials() {
        for (e in asset.entities) {
            val ri = rm.getInstance(e)
            if (ri == 0) continue
            val colors = glbColor[asset.getName(e) ?: ""].orEmpty()
            for (p in 0 until rm.getPrimitiveCount(ri)) {
                val orig = rm.getMaterialInstanceAt(ri, p)
                val name = orig.name ?: continue
                val spec = SPECS[name]
                val base = provider.getMaterial(keyFor(spec, colors.getOrElse(p) { false }, if (spec?.blend == true) 2 else 0), IntArray(8), name)
                val mi = mats.getOrPut(name) { orig.also { if (spec != null && base != null) spec.applyTo(it, base); if (base != null) matOf[it] = base } }
                if (mi !== orig) rm.setMaterialInstanceAt(ri, p, mi)
                // 제어 표시(열선 패드·뒷창 잠금 테)는 차가 한 일을 보일 때만 쓴다(시안: 상태가 없으면 불투명도 0). 폰 카드는 바깥 보기라 숨긴다.
                if (name == "Heat_Pad" || name == "Indicator") rm.setLayerMask(ri, 0xFF, 0)
            }
            rm.setCastShadows(ri, false); rm.setReceiveShadows(ri, false)
        }
    }

    /**
     * 손으로 그린 부드러운 바닥 그림자(시안 shadow: 겹친 둥근 사각형 + 바퀴 접지 네 점)를 꼭짓점 알파 격자로.
     * 맞춤 재질(filamat) 없이 gltfio 우버셰이더의 unlit·꼭짓점 색·BLEND 조합으로 그린다(폰은 맞춤 재질이 필요 없다).
     */
    private fun shadow() {
        val nx = 48; val nz = 24; val sx = 7.6f; val sz = 3.8f
        val n = (nx + 1) * (nz + 1)
        val pos = FloatArray(n * 3); val alpha = FloatArray(n); val idx = ShortArray(nx * nz * 6)
        for (j in 0..nz) for (i in 0..nx) {
            val u = i.toFloat() / nx; val v = j.toFloat() / nz
            val o = j * (nx + 1) + i
            pos[o * 3] = -0.08f + (u - 0.5f) * sx; pos[o * 3 + 1] = 0.002f; pos[o * 3 + 2] = (v - 0.5f) * sz
            alpha[o] = shadowAlpha(u * 512f, v * 256f)
        }
        for (j in 0 until nz) for (i in 0 until nx) {
            val b = j * (nx + 1) + i; val o = (j * nx + i) * 6
            idx[o] = b.toShort(); idx[o + 1] = (b + 1).toShort(); idx[o + 2] = (b + nx + 1).toShort()
            idx[o + 3] = (b + 1).toShort(); idx[o + 4] = (b + nx + 2).toShort(); idx[o + 5] = (b + nx + 1).toShort()
        }
        // 우버셰이더가 UV0·UV1을 요구한다(쓰지는 않음): 0으로 채운 버퍼를 함께 둔다(빠지면 '필수 속성 없음' 경고).
        val vb = VertexBuffer.Builder().vertexCount(n).bufferCount(3)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 1, VertexBuffer.AttributeType.UBYTE4, 0, 4).normalized(VertexBuffer.VertexAttribute.COLOR)
            .attribute(VertexBuffer.VertexAttribute.UV0, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .attribute(VertexBuffer.VertexAttribute.UV1, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)
        vb.setBufferAt(engine, 2, ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder()).also { b -> repeat(n * 2) { b.putFloat(0f) }; b.flip() })
        vb.setBufferAt(engine, 0, ByteBuffer.allocateDirect(pos.size * 4).order(ByteOrder.nativeOrder()).also { b -> pos.forEach { b.putFloat(it) }; b.flip() })
        vb.setBufferAt(engine, 1, ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).also { b ->
            repeat(n) { i -> b.put(-1).put(-1).put(-1).put((alpha[i] * 255).toInt().coerceIn(0, 255).toByte()) }; b.flip()
        })
        val ib = IndexBuffer.Builder().indexCount(idx.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        ib.setBuffer(engine, ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).also { b -> idx.forEach { b.putShort(it) }; b.flip() })
        val k = MaterialProvider.MaterialKey().apply { unlit = true; hasVertexColors = true; alphaMode = 2; doubleSided = true }
        val mi = provider.createMaterialInstance(k, IntArray(8), "shadow", null) ?: return
        mi.setParameter("baseColorFactor", 0f, 0f, 0f, 1f)
        val entity = em.create()
        RenderableManager.Builder(1).boundingBox(Box(-0.08f, 0f, 0f, sx / 2 + 0.1f, 0.1f, sz / 2 + 0.1f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
            .material(0, mi).culling(false).castShadows(false).receiveShadows(false).priority(0).build(engine, entity)
        scene.addEntity(entity)
    }

    /** 시안 캔버스(512×256)의 그림자 알파: 둥근 사각형 26겹(0.028+0.02k) + 바퀴 자리 방사 그러데이션(0.55→0). */
    private fun shadowAlpha(x: Float, y: Float): Float {
        var a = 0f
        for (i in 0 until 26) {
            val k = i / 25f
            if (inRoundRect(x, y, 48 + k * 60, 52 + k * 34, 416 - k * 120, 152 - k * 68, 70 - k * 40)) a += (0.028f + 0.02f * k) * (1 - a)
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

    /** 미리 계산한 큐브맵 단계별 면 픽셀로 환경광을 만든다(메인 스레드). */
    private fun iblFrom(levels: List<Array<FloatArray>>): IndirectLight {
        val size = 64
        val tex = Texture.Builder().width(size).height(size).levels(levels.size).sampler(Texture.Sampler.SAMPLER_CUBEMAP).format(Texture.InternalFormat.RGB16F).build(engine)
        var s = size
        levels.forEachIndexed { lv, faces ->
            val buf = ByteBuffer.allocateDirect(s * s * 3 * 4 * 6).order(ByteOrder.nativeOrder())
            faces.forEach { px -> px.forEach { buf.putFloat(it) } }
            buf.flip()
            tex.setImage(engine, lv, 0, 0, 0, s, s, 6, Texture.PixelBufferDescriptor(buf, Texture.Format.RGB, Texture.Type.FLOAT))
            s = maxOf(1, s / 2)
        }
        return IndirectLight.Builder().reflections(tex).intensity(1f).build(engine)
    }
}

/**
 * 스튜디오 환경광(시안 studio: 어두운 방 + 위 소프트박스 + 옆 띠, 외부 HDRI 없음, 레시피 6)의 큐브맵 픽셀: 64px 0단계를 해석적으로 그리고
 * 2×2 평균으로 밉 7단(순수 계산, 작업 스레드).
 */
private fun studioFaces(dark: Boolean): List<Array<FloatArray>> {
    val size = 64; val levels = 7
    val room = lin(if (dark) 0x1C1F23 else 0x6D737B); val floor = lin(if (dark) 0x111316 else 0x8F959C)
    var faces = Array(6) { f ->
        FloatArray(size * size * 3).also { px ->
            for (y in 0 until size) for (x in 0 until size) {
                val sc = (x + 0.5f) / size * 2 - 1; val tc = (y + 0.5f) / size * 2 - 1
                val d = when (f) { 0 -> floatArrayOf(1f, -tc, -sc); 1 -> floatArrayOf(-1f, -tc, sc); 2 -> floatArrayOf(sc, 1f, tc); 3 -> floatArrayOf(sc, -1f, -tc); 4 -> floatArrayOf(sc, -tc, 1f); else -> floatArrayOf(-sc, -tc, -1f) }
                val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                val c = studioRadiance(d[0] / l, d[1] / l, d[2] / l, room, floor)
                val o = (y * size + x) * 3; px[o] = c[0]; px[o + 1] = c[1]; px[o + 2] = c[2]
            }
        }
    }
    val out = ArrayList<Array<FloatArray>>()
    var s = size
    for (lv in 0 until levels) {
        out += faces
        if (s == 1) break
        val h = s / 2
        val src = faces
        faces = Array(6) { f ->
            FloatArray(h * h * 3).also { o ->
                val a = src[f]
                for (y in 0 until h) for (x in 0 until h) for (c in 0 until 3) {
                    o[(y * h + x) * 3 + c] = (a[((2 * y) * s + 2 * x) * 3 + c] + a[((2 * y) * s + 2 * x + 1) * 3 + c] + a[((2 * y + 1) * s + 2 * x) * 3 + c] + a[((2 * y + 1) * s + 2 * x + 1) * 3 + c]) / 4
                }
            }
        }
        s = h
    }
    return out
}

/** 원점에서 (dx,dy,dz)로 본 스튜디오 밝기(선형). 판: 위 큰 소프트박스 3.4, 좌우 긴 띠 3.0, 낮은 띠 1.6, 앞뒤 세로 판 1.8. */
private fun studioRadiance(dx: Float, dy: Float, dz: Float, room: FloatArray, floor: FloatArray): FloatArray {
        var best = Float.MAX_VALUE; var k = -1f
        fun panel(axis: Int, at: Float, cu: Float, cv: Float, hu: Float, hv: Float, kk: Float) {
            val d = floatArrayOf(dx, dy, dz)
            if (abs(d[axis]) < 1e-4f) return
            val t = at / d[axis]
            if (t <= 0f || t >= best) return
            val p = floatArrayOf(dx * t, dy * t, dz * t)
            val (u, v) = when (axis) { 0 -> p[2] to p[1]; 1 -> p[0] to p[2]; else -> p[0] to p[1] }
            if (abs(u - cu) <= hu && abs(v - cv) <= hv) { best = t; k = kk }
        }
        panel(1, 13.5f, 0f, 0f, 8f, 3f, 3.4f)
        panel(2, 15.5f, 0f, 5.5f, 11f, 0.55f, 3.0f)
        panel(2, -15.5f, 0f, 5.5f, 11f, 0.55f, 3.0f)
        panel(2, 15.8f, 0f, 2.4f, 11f, 0.3f, 1.6f)
        panel(2, -15.8f, 0f, 2.4f, 11f, 0.3f, 1.6f)
        panel(0, 15.5f, 0f, 4f, 2f, 4.5f, 1.8f)
        panel(0, -15.5f, 0f, 4f, 2f, 4.5f, 1.8f)
        if (k >= 0f) return floatArrayOf(k, k, k)
        return if (dy < -0.1f) floor else room
}

/** GLB JSON에서 노드 이름 → 프리미티브마다 COLOR_0(구운 AO)가 있는가. */
private fun readGlbJson(b: ByteArray): Map<String, List<Boolean>> {
    val len = ByteBuffer.wrap(b, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
    val j = JSONObject(String(b, 20, len, Charsets.UTF_8))
    val meshes = j.getJSONArray("meshes")
    val nodes = j.getJSONArray("nodes")
    val out = HashMap<String, List<Boolean>>()
    for (i in 0 until nodes.length()) {
        val n = nodes.getJSONObject(i)
        if (!n.has("mesh")) continue
        val prims = meshes.getJSONObject(n.getInt("mesh")).getJSONArray("primitives")
        out[n.optString("name")] = (0 until prims.length()).map { prims.getJSONObject(it).getJSONObject("attributes").has("COLOR_0") }
    }
    return out
}

/** 재질 하나의 덮어쓸 값(시안 car3d.js materials(): 색은 sRGB → 선형). */
private class Spec(
    hex: Int, val roughness: Float, val metallic: Float = 0f, val clearCoat: Float? = null, val ccRough: Float = 0f,
    val blend: Boolean = false, val alpha: Float = 1f, val emissive: FloatArray? = null, val emissiveStrength: Float = 1f,
) {
    val color = lin(hex)
    fun applyTo(mi: MaterialInstance, m: Material) {
        if (m.hasParameter("baseColorFactor")) mi.setParameter("baseColorFactor", color[0], color[1], color[2], alpha)
        if (m.hasParameter("roughnessFactor")) mi.setParameter("roughnessFactor", roughness)
        if (m.hasParameter("metallicFactor")) mi.setParameter("metallicFactor", metallic)
        if (clearCoat != null && m.hasParameter("clearCoatFactor")) {
            mi.setParameter("clearCoatFactor", clearCoat)
            if (m.hasParameter("clearCoatRoughnessFactor")) mi.setParameter("clearCoatRoughnessFactor", ccRough)
        }
        // 발광은 색 × 세기 한 번만(로더가 GLB 세기를 색과 세기 둘 다에 넣어 두 번 곱해지는 것을 막는다, 차 앱 CarEngine과 같음).
        val hasStrength = m.hasParameter("emissiveStrength")
        if (hasStrength) mi.setParameter("emissiveStrength", if (emissive != null) emissiveStrength else 1f)
        if (emissive != null && m.hasParameter("emissiveFactor")) {
            val k = if (hasStrength) 1f else emissiveStrength
            mi.setParameter("emissiveFactor", emissive[0] * k, emissive[1] * k, emissive[2] * k)
        }
    }
}

/** 시안 car3d.js materials()의 값(차 앱 CarEngine과 같은 표). 유리는 투과 대신 BLEND(GLB 알파)에 틴트 색. 램프 발광은 GLB 값(앞 4·뒤 3)을 한 번만. */
private val SPECS: Map<String, Spec> = mapOf(
    "Paint" to Spec(0xE9EAEC, 0.3f, 0.06f, clearCoat = 1f, ccRough = 0.035f),
    "Trim_Black" to Spec(0x141618, 0.55f),
    "Black_Gloss" to Spec(0x07080A, 0.12f, clearCoat = 1f, ccRough = 0.03f),
    "Chrome" to Spec(0xE2E6EA, 0.1f, 1f),
    "Grille_Chrome" to Spec(0xCFD4DA, 0.16f, 1f),
    "Grille_Base" to Spec(0x08090B, 0.45f),
    "Head_L_All" to Spec(0xFFFFFF, 0.2f, emissive = floatArrayOf(0.888f, 0.930f, 1f), emissiveStrength = 4f),
    "Rear_L_Pos" to Spec(0x5A0A0C, 0.2f, emissive = floatArrayOf(1f, 0.0232f, 0.0176f), emissiveStrength = 3f),
    "Glass_Front" to Spec(0x59626A, 0.05f, blend = true, alpha = 0.30f),
    "Glass_Side" to Spec(0x262C32, 0.05f, blend = true, alpha = 0.55f),
    "Glass_Rear" to Spec(0x262C32, 0.05f, blend = true, alpha = 0.55f),
    "Sunroof_Glass" to Spec(0x15191D, 0.05f, blend = true, alpha = 0.75f),
    "Roofglass_Shade" to Spec(0x2B2A28, 0.92f),
    "Rear_L_Stop" to Spec(0x3A0809, 0.3f),
    "Panel_Gap" to Spec(0x050506, 0.6f),
    "Tire" to Spec(0x18191B, 0.8f),
    "Rim_Silver" to Spec(0xDFE3E7, 0.26f, 0.75f),
    "Rim_Black" to Spec(0x0F1012, 0.38f, 0.3f),
    "Brake" to Spec(0x2C2E31, 0.5f, 0.6f),
    "Caliper" to Spec(0x2E3135, 0.4f, 0.5f),
    "Liner" to Spec(0x0A0A0B, 0.95f),
    "Interior_Dark" to Spec(0x2A2C30, 0.62f),
    "Seat" to Spec(0x7C6B5D, 0.78f),
    "Headliner" to Spec(0xBDB7AD, 0.9f),
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

internal fun lin(hex: Int): FloatArray = intArrayOf((hex shr 16) and 0xFF, (hex shr 8) and 0xFF, hex and 0xFF).let { c ->
    FloatArray(3) { i -> (c[i] / 255f).let { if (it <= 0.04045f) it / 12.92f else ((it + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() } }
}

private fun log2(x: Float) = (ln(x.toDouble()) / ln(2.0)).toFloat()
