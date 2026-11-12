package kr.aigyeot.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * P3(계약 2.0, 설계 3.3, D7): 상태 칸마다 그 칸을 바꾸는 코드가 주인 리듀서 파일에만 있다.
 * 방법: 엔진 소스를 읽어(주석·문자열은 지운다) `copy(` 호출의 이름 있는 인자(`칸 =`)를 모두 모으고, 아래 표의 칸이면 그 파일이 주인 파일인지 본다.
 * 상태는 불변 data class라 칸을 바꾸는 길은 copy 하나다. 생성자(새 세션의 처음 값 등)는 바꾸기가 아니라 보지 않는다.
 * 이름이 겹쳐 가를 수 없는 안쪽 값(에피소드·질문 창·장부·줄의 칸)은 그 바깥 칸(episode·qa·handoffs·queue)과 주인 파일의 private 쓰기 함수(ep·setQa·…)가 막는다.
 */
class OwnershipTest {
    /** 리듀서 → 파일(설계 3.1). Soothe의 단계 칸은 Stage.kt의 enter가 쓴다. */
    private val files = mapOf(
        "Tx" to setOf("Tx.kt"),
        "Facts" to setOf("Router.kt"),
        "Life" to setOf("LifeReducer.kt"),
        "Feeding" to setOf("FeedingReducer.kt"),
        "Soothe" to setOf("SootheReducer.kt", "Stage.kt"),
        "Offer" to setOf("OfferReducer.kt"),
        "Qa" to setOf("QaReducer.kt"),
        "Control" to setOf("ControlReducer.kt"),
        "Speech" to setOf("SpeechReducer.kt"),
        "Record" to setOf("RecordReducer.kt"),
    )

    private fun own(reducer: String, vararg fields: String) = fields.map { it to reducer }

    /** 칸 → 주인 리듀서(설계 3.3). 바깥 묶음 칸(ign·speech)은 주인이 셋·둘이라 잎 칸으로 본다. */
    private val owners: Map<String, String> = listOf(
        own("Tx", "now", "seq", "rng"),
        own("Facts", "profile", "methodsNext", "otherGuardians", "care", "careSeq", "careRoom", "history"),
        own("Feeding", "feedingStart", "nursingStop", "departChecked"),
        own("Life", "car", "gear", "gearSince", "rearDoorOpen", "inCall", "micRunning", "micPath", "cabinC", "route", "seatHeat", "pauses",
            "s16Said", "doorOpenedSinceP", "reserved", "suspendedAt", "driverUserId", "boardNotice", "session", "micNoticeArmed", "s17Said", "bootAt"),
        own("Soothe", "stage", "episode", "playedMs", "alarmRun", "cryRuns", "output", "mixedUntil"),
        own("Offer", "offers", "spokenAt", "pendingAsk", "waypointPending", "addedRestIds", "driveFrom", "longDriveOffered", "leftParkAt", "handoffs"),
        own("Qa", "qa"),
        own("Control", "windowLockPending", "controls", "windowLockBefore", "comfortDone", "comfortPending", "ambient"),
        own("Speech", "queue", "speaking", "lastDoneAt"),
        own("Record", "steer", "restLog", "recordsCleared"),
    ).flatten().toMap()

    /** 남은 예외: Tx.kt의 `ses {}`는 세션 잎 칸을 고치는 통로라 `session`을 통째로 바꿔 끼운다(어느 잎 칸인지는 위 표가 본다). */
    private val exceptions = mapOf("session" to setOf("Tx.kt"))

    private val src = File("src/main/kotlin/kr/aigyeot/engine")

    @Test fun ownership_writesOnlyInOwnerFile() {
        val bad = mutableListOf<String>()
        var seen = 0
        src.listFiles { f -> f.name.endsWith(".kt") }!!.sortedBy { it.name }.forEach { f ->
            for ((field, line) in copyWrites(f.readText())) {
                val owner = owners[field] ?: continue
                seen++
                if (f.name !in files.getValue(owner) && f.name !in exceptions[field].orEmpty()) bad += "${f.name}:$line `$field =` (주인 $owner)"
            }
        }
        assertTrue(seen > 50, "소스에서 copy 쓰기를 찾지 못했다(경로·구문 확인): $seen")
        assertTrue(bad.isEmpty(), "주인 파일 밖의 쓰기(주인 리듀서의 함수를 부를 것):\n" + bad.joinToString("\n"))
    }

    /** 표의 칸이 실제 상태 타입의 칸이다(개명·삭제가 표에 남지 않게). */
    @Test fun ownership_tableMatchesStateTypes() {
        val types = listOf("EngineState", "CarView", "Ignition", "SessionState", "OfferState", "ControlState", "SpeechQueue")
        val state = File(src, "State.kt").readText()
        // 세션 정체(id·openedAt·trigger·methods)는 만들 때만 정하고, ign·speech는 주인이 여럿인 묶음이라 잎 칸으로 본다
        val declared = types.flatMap { t -> ctorFields(state, t) }.toSet() - setOf("id", "openedAt", "trigger", "methods", "ign", "speech")
        assertEquals(declared, owners.keys, "상태 칸과 소유 표가 다르다")
    }

    /** 칸 → 주인 표가 설계 3.3과 같다. 설계 문서는 로컬 전용이라 없으면 건너뛴다. */
    @Test fun ownership_tableMatchesDesign() {
        val doc = File("../docs/architecture-car.md")
        assumeTrue(doc.exists(), "설계 문서 없음(공개 저장소)")
        val lines = doc.readLines()
        val start = lines.indexOfFirst { it.startsWith("### 3.3") }
        val rows = lines.drop(start + 1).dropWhile { !it.startsWith("|") }.takeWhile { it.startsWith("|") }.drop(2)
        val docOwners = mutableMapOf<String, String>()
        val docFiles = mutableMapOf<String, Set<String>>()
        val tick = Regex("`([^`]+)`")
        for (r in rows) {
            val c = r.split("|").map { it.trim() }
            val reducer = c[1].trim('*')
            docFiles[reducer] = tick.findAll(c[2]).map { it.groupValues[1] }.toSet()
            tick.findAll(c[3]).forEach { docOwners[it.groupValues[1].substringAfterLast('.')] = reducer }
        }
        assertEquals(files, docFiles, "리듀서 → 파일이 설계 3.3과 다르다")
        assertEquals(owners, docOwners, "칸 → 주인이 설계 3.3과 다르다")
    }

    /** 검사기 자체: 주석·문자열을 지우고 copy의 바깥 이름 인자만 모은다. */
    @Test fun ownership_parserSelfCheck() {
        val code = "s = s.copy(a = 1, b = f(c = 2), d = x.copy(e = \"f = 3\")) // copy(z = 3)\n/* copy(y = 1) */ g(h = 1)"
        assertEquals(listOf("a" to 1, "b" to 1, "d" to 1, "e" to 1), copyWrites(code))
    }

    companion object {
        /** 소스에서 `copy(` 호출의 바깥 이름 인자와 그 줄 번호. */
        fun copyWrites(text: String): List<Pair<String, Int>> {
            val code = blank(text)
            val out = mutableListOf<Pair<String, Int>>()
            Regex("""\bcopy\(""").findAll(code).forEach { m ->
                var depth = 0
                var argStart = m.range.last + 1
                var i = m.range.last
                while (i < code.length) {
                    val ch = code[i]
                    if (ch in "([{") depth++
                    if (ch in ")]}") depth--
                    if (depth == 1 && ch == ',' || depth == 0) {
                        Regex("""^\s*(\w+)\s*=(?!=)""").find(code.substring(argStart, i))?.let { a ->
                            out += a.groupValues[1] to code.substring(0, argStart).count { it == '\n' } + 1
                        }
                        argStart = i + 1
                        if (depth == 0) break
                    }
                    i++
                }
            }
            return out.sortedBy { it.second }
        }

        /** 주석은 지우고 문자열 안은 빈칸으로(줄 바꿈은 남겨 줄 번호를 지킨다). 문자열 템플릿 안의 따옴표는 짝이 맞아 그대로 지나간다. */
        private fun blank(t: String): String {
            val b = StringBuilder(t.length)
            var i = 0
            while (i < t.length) {
                when {
                    t.startsWith("//", i) -> { while (i < t.length && t[i] != '\n') i++ }
                    t.startsWith("/*", i) -> { val e = t.indexOf("*/", i + 2).let { if (it < 0) t.length else it + 2 }; t.substring(i, e).forEach { b.append(if (it == '\n') '\n' else ' ') }; i = e }
                    t[i] == '"' -> {
                        b.append('"'); i++
                        while (i < t.length && t[i] != '"') { if (t[i] == '\\') { b.append(' '); i++ }; b.append(if (t[i] == '\n') '\n' else ' '); i++ }
                        if (i < t.length) { b.append('"'); i++ }
                    }
                    t[i] == '\'' && i + 2 < t.length -> { val e = t.indexOf('\'', i + 2) + 1; repeat(e - i) { b.append(' ') }; i = e }
                    else -> { b.append(t[i]); i++ }
                }
            }
            return b.toString()
        }

        /** data class [type] 생성자의 칸 이름. */
        fun ctorFields(state: String, type: String): List<String> {
            val code = blank(state)
            val at = Regex("""class\s+$type\s*\(""").find(code)!!.range.last
            var depth = 0
            var i = at
            while (true) { if (code[i] == '(') depth++; if (code[i] == ')') depth--; if (depth == 0) break; i++ }
            return Regex("""\bva[lr]\s+(\w+)""").findAll(code.substring(at, i)).map { it.groupValues[1] }.toList()
        }
    }
}
