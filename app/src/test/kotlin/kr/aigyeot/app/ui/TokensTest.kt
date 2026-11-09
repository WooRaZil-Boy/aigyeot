package kr.aigyeot.app.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/** Tokens(손으로 옮긴 값)가 공용 사본 tokens.json과 같은지. 깨끗한 클론에서도 돌게 저장소 안 사본을 읽는다(시험 작업 폴더 = app/). */
class TokensTest {
    private val json: JsonObject = Json.parseToJsonElement(File("../shared/src/test/resources/design/tokens.json").readText()).jsonObject

    private fun argb(css: String): Long {
        val h = css.removePrefix("#")
        return if (h.length == 8) (h.substring(6) + h.substring(0, 6)).toLong(16) else ("FF$h").toLong(16)
    }

    @Test fun colorsMatchTokensJson() {
        val src = json.getValue("color").jsonObject
        assertEquals(src.keys, Tokens.color.keys)
        src.forEach { (k, v) ->
            val (l, d) = v.jsonArray.map { argb(it.jsonPrimitive.content) }
            assertEquals("$k 라이트", l, Tokens.color.getValue(k).first)
            assertEquals("$k 다크", d, Tokens.color.getValue(k).second)
        }
    }

    @Test fun glassMatchesTokensJson() {
        val src = json.getValue("glass").jsonObject
        Tokens.glass.forEach { (k, pair) ->
            src.getValue(k).jsonArray.map { it.jsonPrimitive.content }.zip(listOf(pair.first, pair.second)).forEach { (css, rgba) ->
                val n = Regex("""rgba\((\d+),(\d+),(\d+),([\d.]+)\)""").find(css.replace(" ", ""))!!.groupValues
                assertEquals("$k $css", Rgba(n[1].toInt(), n[2].toInt(), n[3].toInt(), n[4].toFloat()), rgba)
            }
        }
    }

    @Test fun carTypeRadiusMatchTokensJson() {
        val type = json.getValue("type").jsonObject.getValue("car").jsonObject.filterKeys { !it.startsWith("$") }
        assertEquals(type.keys, Tokens.carType.keys)
        type.forEach { (k, v) -> assertEquals(k, v.jsonArray.map { it.jsonPrimitive.int }, Tokens.carType.getValue(k).toList()) }
        assertTrue("차 글자 24 이상", Tokens.carType.values.all { it.first >= 24 })
        val r = json.getValue("radius").jsonObject.getValue("car").jsonObject
        assertEquals(r.mapValues { it.value.jsonPrimitive.int }, Tokens.carRadius)
    }

    @Test fun motionSpringMatchTokensJson() {
        val m = json.getValue("motion").jsonObject.filterKeys { !it.startsWith("$") }
        assertEquals(m.keys, Tokens.motion.keys)
        m.forEach { (k, v) ->
            val a = v.jsonArray
            assertEquals(k, a[0].jsonPrimitive.int, Tokens.motion.getValue(k).ms)
            val curve = a[1].jsonPrimitive.content
            val want = if (curve == "linear") null else Regex("""[-\d.]+""").findAll(curve.substringAfter("(")).map { it.value.toFloat() }.toList()
            assertEquals(k, want, Tokens.motion.getValue(k).curve?.toList())
        }
        val s = json.getValue("spring").jsonObject.filterKeys { !it.startsWith("$") }
        assertEquals(s.keys, Tokens.spring.keys)
        s.forEach { (k, v) -> assertEquals(k, v.jsonObject.getValue("damping").jsonPrimitive.float to v.jsonObject.getValue("stiffness").jsonPrimitive.float, Tokens.spring.getValue(k)) }
    }

    /** 시안 4.3 대비표의 글자 짝(build.py PAIRS)이 라이트·다크 모두 4.5:1 이상. 투명 색은 그 바탕에 합성. */
    @Test fun textPairsContrastAtLeast45() {
        val pairs = listOf(
            "textPrimary" to "bg", "textPrimary" to "surface", "textPrimary" to "surfaceRaised", "textSecondary" to "bg", "textSecondary" to "surface",
            "textSecondary" to "surfaceRaised", "textTertiary" to "bg", "textTertiary" to "surface", "textTertiary" to "stage", "accentInk" to "bg",
            "accentInk" to "surface", "onAccent" to "accent", "onAccentSoft" to "accentSoft", "child" to "bg", "child" to "surface", "child" to "childSoft",
            "warning" to "surface", "danger" to "surface", "onFill" to "fill", "textPrimary" to "stage", "textSecondary" to "stage",
        )
        for ((fg, bg) in pairs) for (dark in listOf(false, true)) {
            val pick = { n: String -> Tokens.color.getValue(n).let { if (dark) it.second else it.first } }
            val c = contrast(pick(fg), pick(bg))
            assertTrue("$fg/$bg ${if (dark) "다크" else "라이트"} $c", c >= 4.5)
        }
    }

    private fun lum(c: Long): Double = listOf(16, 8, 0).map { s -> ((c shr s) and 0xFF) / 255.0 }
        .map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }.let { (r, g, b) -> 0.2126 * r + 0.7152 * g + 0.0722 * b }

    private fun contrast(fg: Long, bg: Long): Double {
        val a = ((fg shr 24) and 0xFF) / 255.0
        val mix = listOf(16, 8, 0).fold(0xFF000000L) { acc, s ->
            val v = (((fg shr s) and 0xFF) * a + ((bg shr s) and 0xFF) * (1 - a)).toLong().coerceIn(0, 255)
            acc or (v shl s)
        }
        val (l1, l2) = listOf(lum(mix), lum(bg)).sortedDescending()
        return (l1 + 0.05) / (l2 + 0.05)
    }
}
