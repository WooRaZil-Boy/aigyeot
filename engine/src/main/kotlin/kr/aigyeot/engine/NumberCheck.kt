package kr.aigyeot.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

// 명세서 9장 '숫자 검사'(contracts-link 8.8 '답 처리'). ④(link)가 LLM 답 글에 부른다.

/**
 * 답 글에 든 숫자가 모두 상태값(JSON, `POST /v1/llm`의 `state`)에 있으면 참. 숫자가 없으면 참.
 * - 상태값의 숫자는 수 칸과 글자 칸(쉼터 이름 등) 안의 숫자다. `id` 칸은 차가 매긴 임시 값이라 넣지 않는다.
 * - 값으로 견준다(`7`과 `7.0`은 같다). 부호는 보지 않는다(영하 기온을 "3.5도"로 말할 수 있다). 천 단위 쉼표는 지운다.
 * - 한글 수사("두 번")는 잡지 못한다. 중계의 시스템 지시가 숫자를 아라비아 숫자로 쓰게 한다(contracts-link 1.4 p2).
 */
fun numbersGrounded(text: String, state: JsonElement): Boolean {
    val allowed = HashSet<BigDecimal>().also { collectNumbers(state, it) }
    return numbersIn(text).all { it in allowed }
}

private val NUMBER = Regex("""\d+(?:\.\d+)?""")
private val THOUSANDS = Regex("""(?<=\d),(?=\d{3}(?!\d))""")

/** 글 안의 숫자(절댓값, 끝 0을 지운 값). */
internal fun numbersIn(s: String): List<BigDecimal> =
    NUMBER.findAll(asciiDigits(s).replace(THOUSANDS, "")).map { BigDecimal(it.value).stripTrailingZeros() }.toList()

/** 전각·아랍 인도 숫자 등을 ASCII로(`\d`는 ASCII만 본다). NFKC가 못 바꾼 유니코드 숫자는 자릿값으로 바꾼다. */
private fun asciiDigits(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        .codePoints().toArray().joinToString("") { cp -> if (Character.isDigit(cp)) "${Character.digit(cp, 10)}" else String(Character.toChars(cp)) }

private fun collectNumbers(e: JsonElement, out: MutableSet<BigDecimal>) {
    when (e) {
        is JsonObject -> e.forEach { (k, v) -> if (k != "id") collectNumbers(v, out) }
        is JsonArray -> e.forEach { collectNumbers(it, out) }
        JsonNull -> Unit
        is JsonPrimitive -> if (e.isString) out += numbersIn(e.content)
            else e.content.toBigDecimalOrNull()?.let { out += it.abs().stripTrailingZeros() }
    }
}

/**
 * 상태 단정 검사(명세서 9장, 17장 C108, contracts-link 8.8). 답 글이 관찰하지 않은 아이 상태(잠·깸·배고픔·아픔·안전·숨)나
 * 울음 원인을 단정하는 표현을 담으면 거짓이다. NFKC로 정규화하고 공백을 모두 지운 글에서 [STATE_CLAIMS]를 찾는다.
 * 지나치게 걸러도 오프라인 문장이 나갈 뿐이라 안전 쪽이다.
 */
fun noStateClaim(text: String): Boolean {
    val t = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).filterNot { it.isWhitespace() }
    return STATE_CLAIMS.none { it in t }
}

/** 설계값(공백 없는 꼴). 바꾸면 contracts-link 8.8과 NumberCheckTest를 함께 고친다. */
internal val STATE_CLAIMS = listOf(
    "잠들", "자고있", "자는중", "잘자", "푹자",         // 잠
    "깼", "깨어",                                      // 깸
    "배고프", "배고파", "배고픈", "졸려", "졸린",                            // 배고픔
    "아파", "아픈", "열이",                            // 아픔
    "안전해", "안전합", "안전하게", "괜찮아", "괜찮습",   // 안전·괜찮음
    "숨을", "숨쉬", "호흡",                            // 숨
    "때문에울", "해서울어요",                          // 원인 단정
)
