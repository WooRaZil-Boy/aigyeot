package kr.aigyeot.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color

/**
 * 디자인 v6 토큰: 시안 tokens.json(공용 사본 shared/src/test/resources/design/tokens.json) 한 출처의 차 값.
 * 손으로 옮겼고 TokensTest가 사본과 값을 하나씩 대조한다. 값을 바꿀 때는 tokens.json을 먼저 고친다.
 */
object Tokens {
    /** 색: 이름 → (라이트, 다크) ARGB. 이름은 tokens.json `color` 열쇠 그대로. */
    val color: Map<String, Pair<Long, Long>> = mapOf(
        "bg" to (0xFFF2F4F6L to 0xFF0F1013L),
        "stage" to (0xFFE9ECEFL to 0xFF141519L),
        "surface" to (0xFFFFFFFFL to 0xFF1B1C21L),
        "surfaceRaised" to (0xFFF2F4F6L to 0xFF25262CL),
        "line" to (0x14191F28L to 0x14FFFFFFL),
        "textPrimary" to (0xFF191F28L to 0xFFF2F3F5L),
        "textSecondary" to (0xFF4E5968L to 0xFFB0B8C1L),
        "textTertiary" to (0xFF5D6673L to 0xFF8B95A1L),
        "accent" to (0xFF2867E6L to 0xFF2867E6L),
        "accentInk" to (0xFF2867E6L to 0xFF7AA5FFL),
        "onAccent" to (0xFFFFFFFFL to 0xFFFFFFFFL),
        "accentSoft" to (0xFFEAF1FFL to 0xFF18233AL),
        "onAccentSoft" to (0xFF1D4FC4L to 0xFFA9C4FFL),
        "glow" to (0xFF7AA5FFL to 0xFF7AA5FFL),
        "child" to (0xFFC2410CL to 0xFFFF8F55L),
        "childSoft" to (0xFFFFF1E8L to 0xFF33211AL),
        "childGlow" to (0xFFFF9A5CL to 0xFFFF9A5CL),
        "warning" to (0xFFB45309L to 0xFFF5C451L),
        "danger" to (0xFFD12F34L to 0xFFFF6B6BL),
        "active" to (0xFF12A150L to 0xFF34C77BL),
        "fill" to (0xFF2B2E33L to 0xFFE0E1E6L),
        "onFill" to (0xFFFFFFFFL to 0xFF131417L),
        "cFeed" to (0xFF1BAF7AL to 0xFF199E70L),
        "cSleep" to (0xFF9B87F5L to 0xFF9580EEL),
        "cDiaper" to (0xFF8B95A1L to 0xFF6B7684L),
        "cNote" to (0xFF8B95A1L to 0xFF6B7684L),
        "cCar" to (0xFFEB6834L to 0xFFD95926L),
    )

    /** 유리(차 위 칩·'처음 자리로' 단추만): 이름 → (라이트, 다크) rgba. */
    val glass: Map<String, Pair<Rgba, Rgba>> = mapOf(
        "fill" to (Rgba(255, 255, 255, 0.62f) to Rgba(30, 32, 36, 0.58f)),
        "rim" to (Rgba(255, 255, 255, 0.80f) to Rgba(255, 255, 255, 0.16f)),
        "shade" to (Rgba(22, 24, 26, 0.08f) to Rgba(0, 0, 0, 0.40f)),
    )

    /** 차 글자(Connect-S, 1px = 1dp): 이름 → (크기, 줄 높이, 굵기). 24 미만은 없다. */
    val carType: Map<String, Triple<Int, Int, Int>> = mapOf(
        "headlineL" to Triple(60, 76, 700),
        "headlineS" to Triple(48, 62, 700),
        "titleL" to Triple(40, 52, 600),
        "titleM" to Triple(36, 46, 600),
        "titleS" to Triple(32, 40, 600),
        "bodyL" to Triple(30, 40, 400),
        "bodyS" to Triple(26, 36, 400),
        "label" to Triple(24, 30, 500),
        "numeral" to Triple(72, 76, 600),
    )

    /** 차 모서리 반경(dp). */
    val carRadius: Map<String, Int> = mapOf("rS" to 16, "rM" to 24, "rL" to 32, "rXL" to 44)

    /** 모션: 이름 → 길이(ms)·곡선. 주행 중·동작 줄이기에서는 쓰지 않는다(바로 바뀜, 주행 중은 200ms 페이드 한 번). */
    val motion: Map<String, Motion> = mapOf(
        "press" to Motion(90, null),
        "fast" to Motion(150, floatArrayOf(0.4f, 0f, 0.2f, 1f)),
        "base" to Motion(220, floatArrayOf(0.4f, 0f, 0.2f, 1f)),
        "enter" to Motion(360, floatArrayOf(0.2f, 0.8f, 0.2f, 1f)),
        "exit" to Motion(180, floatArrayOf(0.4f, 0f, 1f, 1f)),
        "camera" to Motion(1100, floatArrayOf(0.65f, 0f, 0.35f, 1f)),
        "light" to Motion(900, floatArrayOf(0.4f, 0f, 0.2f, 1f)),
        "ripple" to Motion(2400, floatArrayOf(0.2f, 0.6f, 0.35f, 1f)),
    )

    /** 스프링: 이름 → (감쇠비, 강성). */
    val spring: Map<String, Pair<Float, Float>> = mapOf("snappy" to (0.86f to 600f), "smooth" to (1.0f to 385f), "bouncy" to (0.78f to 420f))

    /**
     * 차 화면의 되풀이 움직임 한 주기(ms). tokens.json 밖, 시안 car.css 키프레임 값 그대로(한 곳에 둔다):
     * live = 듣는 점 숨쉬기(@keyframes live 1.8s), micRing = 물어보기 마이크 테(2s), levels = 마이크 막대(@keyframes lv 1.1s), dash = 연결 그림 점선(1.2s).
     */
    val loopMs: Map<String, Int> = mapOf("live" to 1800, "micRing" to 2000, "levels" to 1100, "dash" to 1200)

    /** 주행 중 상태가 바뀔 때 한 번 쓰는 페이드(ms, 시안 4.7 차 규칙). */
    const val DRIVE_FADE_MS = 200
}

data class Rgba(val r: Int, val g: Int, val b: Int, val a: Float) {
    val color get() = Color(r, g, b, (a * 255).toInt().coerceIn(0, 255))
}

class Motion(val ms: Int, private val bezier: FloatArray?) {
    val curve: FloatArray? get() = bezier?.copyOf()
    /** 만들 때 한 번만 계산한다(무한 애니메이션이 매 프레임 읽는다). bezier는 밖에서 바뀌지 않는다(curve는 복사본). */
    val easing: Easing = bezier?.let { CubicBezierEasing(it[0], it[1], it[2], it[3]) } ?: LinearEasing
}
