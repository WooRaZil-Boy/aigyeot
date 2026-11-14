package kr.aigyeot.app.ui.car3d

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

// 3D 무대의 값(three.js 원본 연출을 그대로 옮김). 차 좌표: 앞 +X, 위 +Y, 오른쪽 +Z. az는 앞에서 왼쪽(운전석)으로 도는 각.

/** 카메라 자리. [d]는 넓은 화면의 거리, [r]은 가로로 꼭 들어와야 하는 반폭(m), [seatTarget]이면 목표는 아기 자리. */
data class Preset(val az: Float, val el: Float, val d: Float, val r: Float, val fov: Float, val t: FloatArray?, val xray: Float) {
    val seatTarget get() = t == null
}

/** [event]: 사건 시점(울음 감지~넘김). 소리 결·바람 결처럼 계속 움직이는 표현은 이 시점에서만 돌린다([Stage3d.live]). */
enum class View3d(val preset: Preset, val event: Boolean = false) {
    HERO(Preset(38f, 15f, 9.4f, 3.0f, 27f, floatArrayOf(0.1f, 0.62f, 0f), 0f)),

    /**
     * 차 앱 기본(정차 홈·듣는 중·기다림·주행 중·연결 화면): 뒷좌석 실내 X-ray. 왼쪽 Driving view가 차 바깥 모습을 늘 보여 주어(앱이 숨길 수 없는 시스템 영역)
     * 같은 바깥 모습을 또 두지 않는다. 뒷줄 가운데를 보고 REAR보다 높고 넓게 잡아, 울음 감지 때 아기 자리로 다가가는 이동이 보이게 한다.
     * 거리 9.0은 넓고 낮은 상자(연결 화면 타일·좁은 창 무대)에서도 차 앞뒤가 위아래로 잘리지 않게 고른 값이다(세로로 긴 홈 무대는 반폭 2.8로 물러난다).
     */
    CABIN(Preset(210f, 50f, 9.0f, 2.8f, 30f, floatArrayOf(-0.4f, 0.55f, 0f), 1f)),

    /** X-ray: 아기 자리 쪽 뒤·위에서 실내를 내려다본다(뒷문 스피커·뒷창·열선·독서등·송풍구가 한 화면에). */
    REAR(Preset(222f, 40f, 6.4f, 2.45f, 30f, null, 1f), event = true),
    TOP(Preset(180f, 87f, 10.5f, 1.25f, 30f, floatArrayOf(-0.15f, 0.5f, 0f), 1f)),
    SIDE(Preset(90f, 6f, 10.5f, 2.9f, 27f, floatArrayOf(0f, 0.62f, 0f), 0f)),
    PHONE(Preset(34f, 14f, 10.6f, 3.0f, 26f, floatArrayOf(0.05f, 0.6f, 0f), 0f)),
    TILE(Preset(34f, 13f, 8.3f, 2.75f, 26f, floatArrayOf(0.05f, 0.58f, 0f), 0f)),
    DID(Preset(205f, 52f, 7.0f, 2.3f, 30f, floatArrayOf(-0.45f, 0.6f, 0.1f), 1f))
}

/** 카시트 자리 → 아기 자리 z(m). */
enum class Seat3d(val z: Float, val anchor: String) { RL(-0.42f, "Anchor_Seat_RL"), RC(0f, "Anchor_Seat_RC"), RR(0.42f, "Anchor_Seat_RR") }

/** 상태 빛: 기본·듣는 중·달래는 중·경보(울음 감지). */
enum class Light3d { BASE, LISTEN, SOOTHE, ALERT }

/** 차가 한 일(3D 칩). 칩 글자·아이콘은 화면 층이 정한다. 앵커는 GLB의 Anchor_* 이름. */
enum class Callout3d(val anchors: List<String>) {
    LOCK(listOf("Anchor_Window_RR", "Anchor_Window_RL")),
    HEAT(listOf("Anchor_Heat_RR", "Anchor_Heat_RL")),
    DIM(listOf("Anchor_Lamp_R")),
    HVAC(listOf("Anchor_Vent_R"))
    ;

    /** 칩 선이 가리킬 앵커: [Stage3d.sides]에 쪽이 있으면 그 쪽 앵커만, 없으면 가까운 쪽. */
    fun anchorsIn(s: Stage3d): List<String> =
        s.sides[this]?.takeIf { it.isNotEmpty() }?.let { sides -> anchors.filter { a -> sides.any { a.endsWith("_" + it.name) } } } ?: anchors

    /** 3D 표시(열선 패드·뒷창 테)를 [side] 쪽에 켤까. 쪽을 모르는 그림이면 둘 다. */
    fun shownAt(s: Stage3d, side: Seat3d?): Boolean = this in s.callouts && (side == null || s.sides[this].isNullOrEmpty() || side in s.sides.getValue(this))
}

/** 무대 한 장면의 상태(화면 층이 계산해 넘긴다). [ripple]은 소리가 실제로 나갈 때만. [drive]면 조작·카메라 이동·움직임 없음. */
data class Stage3d(
    val view: View3d = View3d.CABIN,
    val light: Light3d = Light3d.BASE,
    val ripple: Boolean = false,
    val listen: Boolean = false,
    val callouts: Set<Callout3d> = emptySet(),
    val seat: Seat3d = Seat3d.RR,
    /** 칩마다 실제로 해낸 뒷자리 쪽(RL·RR: 잠근 뒷창, 끈 열선). 있으면 칩 선·3D 표시는 이 쪽만, 없으면(쪽을 모르는 그림) 둘 다. */
    val sides: Map<Callout3d, Set<Seat3d>> = emptyMap(),
    val drive: Boolean = false,
    val reducedMotion: Boolean = false
) {
    /** 움직임을 써도 되는가(정차 중·동작 켜짐). */
    val motionOk get() = !drive && !reducedMotion

    /**
     * 계속 움직이는 표현(소리 결·바람 결)을 돌려도 되는가: 움직임을 쓸 수 있고 사건 시점일 때만. 기본 실내(CABIN) 같은 대기 시점에서는 멈춘 그림으로 두어
     * 정차해 기다리는 동안 3D가 쉬지 않고 그리지 않게 한다(CPU 예산). 한 번 움직이는 효과(열선·잠금·맥박)와 카메라 이동은 따로다.
     */
    val live get() = motionOk && view.event

    /** 소리 결이 돈다(소리가 실제로 나가고 [live]). 아니면 멈춘 고리. */
    val rippling get() = ripple && live

    /** 바람 결이 흐른다(공조 칩이 있고 [live]이며 실내가 보일 때, [xray] = 지금 껍데기 투명도). 아니면 멈춘 줄. */
    fun flowing(xray: Float) = Callout3d.HVAC in callouts && live && xray > 0.3f
}

/** 카메라 구면 좌표(도·m) + 목표. */
data class Cam(val az: Float, val el: Float, val d: Float, val fov: Float, val t: FloatArray)

object CarMath {
    const val D2R = (PI / 180).toFloat()

    /** 아기 자리 목표. */
    fun seatTarget(seat: Seat3d) = floatArrayOf(-0.62f, 0.62f, seat.z * 0.45f)

    /** 세로로 긴 화면에서는 반폭 [Preset.r]이 가로로 들어오게 물러난다. */
    fun fitD(p: Preset, aspect: Float): Float {
        val hf = atan(tan(p.fov * D2R / 2) * aspect)
        return max(p.d, p.r / tan(hf))
    }

    fun cam(v: View3d, seat: Seat3d, aspect: Float) = v.preset.let { Cam(it.az, it.el, fitD(it, aspect), it.fov, it.t?.copyOf() ?: seatTarget(seat)) }

    /**
     * [w0]×[h0]에서 찍은 [v] 시점 정지 그림을 [w]×[h] 칸에 그릴 배율(가운데 맞춤). 목표점에서 한 m가 차지하는 화소는 세로 화각이 같으므로
     * 높이 ÷ 거리([fitD])에 비례한다. 같은 크기면 1(화소 그대로). 비율이 다르면 크기는 맞지만 거리가 달라 원근이 조금 다르다(교차 페이드가 덮는다).
     */
    fun posterScale(v: View3d, w0: Int, h0: Int, w: Float, h: Float): Float = (h / fitD(v.preset, w / h)) / (h0 / fitD(v.preset, w0.toFloat() / h0))

    /** 카메라 위치(목표 + 구면). */
    fun eye(c: Cam): FloatArray {
        val a = c.az * D2R
        val e = c.el * D2R
        return floatArrayOf(cos(e) * cos(a) * c.d + c.t[0], sin(e) * c.d + c.t[1], -cos(e) * sin(a) * c.d + c.t[2])
    }

    /** 두 자리 사이(방위는 짧은 쪽으로 돈다). */
    fun lerp(a: Cam, b: Cam, e: Float): Cam {
        val daz = ((b.az - a.az + 540f) % 360f) - 180f
        return Cam(
            a.az + daz * e,
            a.el + (b.el - a.el) * e,
            a.d + (b.d - a.d) * e,
            a.fov + (b.fov - a.fov) * e,
            FloatArray(3) { a.t[it] + (b.t[it] - a.t[it]) * e }
        )
    }
}

/**
 * 한 번 움직이는 효과의 시작 시각과 진행. 상태가 바뀔 때 [onSet]이 지금 상태에 맞지 않는 효과를 지우고
 * 새로 생긴 것만 시작한다. 진행은 그릴 때 [at]이 계산한다(따로 끝내는 곳이 없어 남은 효과가 다른 상태에 섞이지 않는다).
 * - 열선 꺼짐(1.6초, 따뜻함 1 → 0): 열선 칩이 새로 생길 때.
 * - 잠금 테(0.9초, 1 → 1.9 → 1): 잠금 칩이 새로 생길 때.
 * - 울음 감지 빛 맥박(1.2초): 경보에 새로 들어갈 때, 카메라가 움직이면 닿은 뒤부터. 경보가 풀리면 지운다.
 * 움직임을 쓰면 안 되면(주행 중·동작 줄이기) 모두 없다.
 */
class Effects {
    var heatFrom: Long? = null
        private set
    var lockFrom: Long? = null
        private set
    var pulseFrom: Long? = null
        private set

    /** [arriveIn]: 카메라가 지금 상태의 자리에 닿기까지 남은 시간(나노초, 움직이지 않으면 0). */
    fun onSet(before: Stage3d, s: Stage3d, ok: Boolean, arriveIn: Long, now: Long) {
        heatFrom = if (Callout3d.HEAT !in s.callouts || !ok) {
            null
        } else if (Callout3d.HEAT !in before.callouts) {
            now
        } else {
            heatFrom
        }
        lockFrom = if (Callout3d.LOCK !in s.callouts || !ok) {
            null
        } else if (Callout3d.LOCK !in before.callouts) {
            now
        } else {
            lockFrom
        }
        pulseFrom = when {
            s.light != Light3d.ALERT || !ok -> null
            before.light != Light3d.ALERT -> now + arriveIn
            pulseFrom?.let { it > now } == true -> now + arriveIn // 아직 시작 전이면 새 도착 시각에 맞춘다(이동 중 목표가 바뀜)
            else -> pulseFrom
        }
    }

    /** [running]이면 다음 프레임이 필요하다(맥박 시작을 기다리는 동안 포함). [pulseK]는 시작 뒤 진행(0~1)만. */
    data class Frame(val heatWarm: Float, val lockK: Float, val pulseK: Float?, val running: Boolean)

    fun at(t: Long): Frame {
        fun prog(from: Long?, ms: Int): Float? = from?.let { (t - it) / 1e6f / ms }?.takeIf { it < 1f }
        val h = prog(heatFrom, 1600)
        val l = prog(lockFrom, 900)
        val p = prog(pulseFrom, 1200)
        return Frame(
            heatWarm = h?.coerceAtLeast(0f)?.let { 1 - it * it * (3 - 2 * it) } ?: 0f,
            lockK = l?.coerceAtLeast(0f)?.let { 1 + 0.9f * kotlin.math.sin(PI * it).toFloat() } ?: 1f,
            pulseK = p?.takeIf { it >= 0f },
            running = h != null || l != null || p != null
        )
    }
}
