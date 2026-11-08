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
internal fun numbersIn(s: String): List<BigDecimal> = numberHits(s).map { it.first }

/** 숫자와 "그 숫자 바로 뒤(공백 허용)가 도인가". 세 검사가 같은 읽기를 쓰도록 [readable]로 먼저 바꾼다. */
private fun numberHits(s: String): List<Pair<BigDecimal, Boolean>> {
    val t = readable(s).replace(THOUSANDS, "")
    return NUMBER.findAll(t).map { m ->
        BigDecimal(m.value).stripTrailingZeros() to t.drop(m.range.last + 1).trimStart().let { it.startsWith("도") || it.startsWith("°") }   // ℃는 NFKC로 °C
    }.toList()
}

/** NFKC 뒤 전각·아랍 인도 숫자 등 모든 유니코드 숫자를 ASCII로(`\d`는 ASCII만 본다). 아랍 소수점·천 단위 기호도 바꾼다. */
internal fun readable(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        .codePoints().toArray().joinToString("") { cp ->
            when {
                Character.isDigit(cp) -> "${Character.digit(cp, 10)}"
                cp == 0x066B -> "."
                cp == 0x066C -> ","
                else -> String(Character.toChars(cp))
            }
        }

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
    val t = readable(text).filterNot { it.isWhitespace() }
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

/**
 * 숫자-항목 검사(contracts-link 8.8, 명세서 17장 C129). 글을 문장으로 나누고, 문장의 숫자 n마다 상태값에서 값이 n인 칸들 중
 * 하나라도 그 칸의 낱말([CELL_WORDS])이 같은 문장(NFKC·공백 제거)에 있어야 참. 쉼터 이름 등 글자 칸의 숫자는 그 글자 전체가 같은 문장에 있으면 된다.
 * 숫자가 없는 문장은 통과. [numbersGrounded]를 먼저 통과한 글에 쓴다.
 */
fun numbersMatchCells(text: String, state: JsonElement): Boolean {
    val cells = ArrayList<Pair<BigDecimal, List<String>>>().also { collectCells(state, "", null, it) }
    return SENTENCE_END.split(readable(text)).all { sentence ->
        val flat = squash(sentence)
        numberHits(sentence).all { (n, doAfter) ->
            cells.any { (v, words) -> v == n && words.any { if (it == AFTER_DO) doAfter else it in flat } }
        }
    }
}

// 소수점(숫자 사이 마침표)은 문장 끝이 아니다
private val SENTENCE_END = Regex("""(?<!\d)\.|\.(?!\d)|[?!\r\n\u0085\u2028\u2029。！？]""")

private fun squash(s: String) = readable(s).filterNot { it.isWhitespace() }

private fun collectCells(e: JsonElement, path: String, label: String?, out: MutableList<Pair<BigDecimal, List<String>>>) {
    when (e) {
        is JsonObject -> {
            val lab = (e["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: label   // method_stats[] 원소의 label
            e.forEach { (k, v) -> if (k != "id") collectCells(v, if (path.isEmpty()) k else "$path.$k", lab, out) }
        }
        is JsonArray -> e.forEach { collectCells(it, "$path[]", label, out) }
        JsonNull -> Unit
        is JsonPrimitive -> if (e.isString) squash(e.content).let { w -> numbersIn(e.content).forEach { out += it to listOf(w) } }
            else e.content.toBigDecimalOrNull()?.let { out += it.abs().stripTrailingZeros() to cellWords(path, label) }
    }
}

private fun cellWords(path: String, label: String?): List<String> =
    CELL_WORDS.firstOrNull { (p, _) -> path == p || (p.endsWith(".*") && path.startsWith(p.dropLast(1))) }?.second.orEmpty() +
        (if (path.startsWith("method_stats[]") && label != null) listOf(squash(label)) else emptyList())

/** 낱말 대신 "그 숫자 바로 뒤의 도"를 뜻하는 표지. */
private const val AFTER_DO = "#도"

/** 설계값: 칸 → 같은 문장에 있어야 할 낱말 중 하나(공백 없는 꼴). 바꾸면 contracts-link 8.8 표와 NumberCheckTest를 함께 고친다. */
internal val CELL_WORDS: List<Pair<String, List<String>>> = listOf(
    "cry.current_min" to listOf("울"), "cry.count_this_drive" to listOf("울"),
    "feeding.elapsed_min" to listOf("수유", "먹", "분유", "젖", "간격"),
    "feeding.interval_min" to listOf("수유", "먹", "분유", "젖", "간격"),
    "feeds_today" to listOf("수유", "먹", "분유", "젖", "간격"),
    "carseat_min" to listOf("카시트"),
    "cabin_temp" to listOf("온도", "덥", "춥", "추워", AFTER_DO),
    "next_rest.distance_km" to listOf("쉼터", "휴게소", "km", "킬로"),
    "method_stats[].attempts" to listOf("소리", "틀", "그쳤"),
    "method_stats[].successes" to listOf("소리", "틀", "그쳤"),
    "sleep.*" to listOf("잠", "잤", "깼", "밤"),
    "last_night_wakeups" to listOf("잠", "잤", "깼", "밤"),
    "diaper.*" to listOf("기저귀"),
    "care_as_of_min_ago" to listOf("기록"),
)
