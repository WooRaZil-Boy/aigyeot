package kr.aigyeot.phone.presentation

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 디자인 v6 토큰: Kotlin 값이 공개 사본 tokens.json(시안 원본과 같은 파일)과 같다. 새 토큰이 생기면 여기서 먼저 걸린다. */
class TokensTest {
    private val json = Json.parseToJsonElement(File("../../shared/src/test/resources/design/tokens.json").readText()).jsonObject

    /** "#RRGGBB" 또는 "#RRGGBBAA"(CSS 순서) → ARGB. */
    private fun css(hex: String): Int {
        val h = hex.removePrefix("#")
        val rgb = h.take(6).toLong(16)
        val a = if (h.length == 8) h.substring(6, 8).toLong(16) else 0xFF
        return ((a shl 24) or rgb).toInt()
    }

    private fun colors(p: Palette) = mapOf(
        "bg" to p.bg, "stage" to p.stage, "surface" to p.surface, "surfaceRaised" to p.surfaceRaised, "line" to p.line,
        "textPrimary" to p.textPrimary, "textSecondary" to p.textSecondary, "textTertiary" to p.textTertiary,
        "accent" to p.accent, "accentInk" to p.accentInk, "onAccent" to p.onAccent, "accentSoft" to p.accentSoft, "onAccentSoft" to p.onAccentSoft,
        "glow" to p.glow, "child" to p.child, "childSoft" to p.childSoft, "childGlow" to p.childGlow,
        "warning" to p.warning, "danger" to p.danger, "active" to p.active, "fill" to p.fill, "onFill" to p.onFill,
        "cFeed" to p.cFeed, "cSleep" to p.cSleep, "cDiaper" to p.cDiaper, "cNote" to p.cNote, "cCar" to p.cCar,
    )

    @Test
    fun `tokens json의 영역은 이 시험이 아는 것뿐이고 서체는 Pretendard다`() {
        // 새 영역이 생기면 여기서 걸린다. glass는 안드로이드가 쓰지 않는다(시안 v5-3: 글래스는 iOS 기능 층·차 칩만).
        // type·radius의 car 값은 차 앱 몫이다.
        assertEquals(setOf("\$comment", "font", "color", "glass", "type", "radius", "motion", "spring"), json.keys)
        assertEquals(setOf("car", "phone"), json.getValue("type").jsonObject.keys)
        assertEquals(setOf("car", "phone"), json.getValue("radius").jsonObject.keys)
        assertTrue(json.getValue("font").jsonPrimitive.content.startsWith("'Pretendard Variable'"))
        assertTrue(Type.body.fontFamily === Pretendard && Type.timer.fontFamily === Pretendard)
    }

    @Test
    fun `색은 라이트·다크 모두 tokens json과 같고 빠진 토큰이 없다`() {
        val src = json.getValue("color").jsonObject
        assertEquals(src.keys, colors(LightPalette).keys)
        for ((i, p) in listOf(LightPalette, DarkPalette).withIndex()) {
            for ((name, c) in colors(p)) {
                assertEquals(name, 2, src.getValue(name).jsonArray.size)
                val want = css(src.getValue(name).jsonArray[i].jsonPrimitive.content)
                assertEquals("$name[$i]", Integer.toHexString(want), Integer.toHexString(c.toArgb()))
            }
        }
    }

    @Test
    fun `글자 단계는 폰 값(크기·줄 높이·굵기)과 같다`() {
        val src = json.getValue("type").jsonObject.getValue("phone").jsonObject
        val mine: Map<String, TextStyle> = mapOf(
            "hero" to Type.hero, "display" to Type.display, "title" to Type.title, "headline" to Type.headline,
            "body" to Type.body, "caption" to Type.caption, "timer" to Type.timer,
        )
        assertEquals(src.keys - "\$comment", mine.keys)
        for ((name, s) in mine) {
            assertEquals(name, 3, src.getValue(name).jsonArray.size)
            val (size, line, weight) = src.getValue(name).jsonArray.map { it.jsonPrimitive.int }
            assertEquals(name, size.toFloat(), s.fontSize.value)
            assertEquals(name, line.toFloat(), s.lineHeight.value)
            assertEquals(name, weight, s.fontWeight!!.weight)
        }
    }

    @Test
    fun `모서리는 폰 값과 같다`() {
        val src = json.getValue("radius").jsonObject.getValue("phone").jsonObject
        val mine = mapOf("rS" to Radius.rS, "rM" to Radius.rM, "rL" to Radius.rL, "rXL" to Radius.rXL)
        assertEquals(src.keys, mine.keys)
        for ((name, r) in mine) assertEquals(name, src.getValue(name).jsonPrimitive.float, r.value)
    }

    @Test
    fun `모션 곡선과 스프링은 tokens json과 같다`() {
        val motion = json.getValue("motion").jsonObject
        val mine = mapOf(
            "press" to Motion.press, "fast" to Motion.fast, "base" to Motion.base, "enter" to Motion.enter,
            "exit" to Motion.exit, "camera" to Motion.camera, "light" to Motion.light, "ripple" to Motion.ripple,
        )
        assertEquals(motion.keys - "\$comment", mine.keys)
        for ((name, c) in mine) {
            assertEquals(name, 2, motion.getValue(name).jsonArray.size)
            val (ms, curve) = motion.getValue(name).jsonArray.let { it[0].jsonPrimitive.int to it[1].jsonPrimitive.content }
            assertEquals(name, ms, c.ms)
            val want = if (curve == "linear") listOf(0f, 0f, 1f, 1f)
            else Regex("""cubic-bezier\(([^)]*)\)""").find(curve)!!.groupValues[1].split(",").map { it.trim().toFloat() }
            assertEquals(name, want, listOf(c.x1, c.y1, c.x2, c.y2))
        }
        val spring = json.getValue("spring").jsonObject
        val springs = mapOf("snappy" to Springs.snappy, "smooth" to Springs.smooth, "bouncy" to Springs.bouncy)
        assertEquals(spring.keys - "\$comment", springs.keys)
        for ((name, s) in springs) {
            val o = spring.getValue(name).jsonObject
            // swiftui는 iOS 표현이라 값은 대조하지 않지만, 모르는 키가 생기면 걸린다.
            assertEquals(name, setOf("damping", "stiffness", "swiftui"), o.keys)
            assertEquals(name, o.getValue("damping").jsonPrimitive.float, s.damping)
            assertEquals(name, o.getValue("stiffness").jsonPrimitive.float, s.stiffness)
        }
    }

}
