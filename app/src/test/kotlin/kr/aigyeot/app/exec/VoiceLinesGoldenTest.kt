package kr.aigyeot.app.exec

import kr.aigyeot.app.Golden
import kr.aigyeot.engine.Sid
import org.junit.After
import org.junit.Before
import java.util.TimeZone
import kotlin.test.Test

// 음성 문장 표 전체를 글 파일 하나로 고정한다: 문장 id × 소리 이름·휴게소 이름의 받침 변형.
class VoiceLinesGoldenTest {
    private val zone = TimeZone.getDefault()
    @Before fun seoul() = TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    @After fun restore() = TimeZone.setDefault(zone)

    private val methods = listOf("백색소음", "뱃속 소리", "자장가", "노래", "물")   // 받침 있음·없음·ㄹ받침
    private val rests = listOf("덕평휴게소", "안성휴게소(부산방향)", "용인졸음쉼터", "서울만남의광장", "죽전 휴게소")
    private val hungryAt = listOf("1800000000000", "1800000600000", "미정")   // 분이 0·0이 아님·숫자가 아님

    @Test fun `문장 표 전체가 저장된 글과 같다`() {
        val out = StringBuilder()
        fun line(sid: Sid, vararg v: Pair<String, String>) =
            out.append("$sid ${v.joinToString(" ") { "${it.first}=${it.second}" }}\n  -> ${Lines.text(sid, mapOf(*v))}\n")
        for (sid in Sid.entries) when (sid) {
            Sid.S02, Sid.S03 -> methods.forEach { line(sid, "method" to it) }
            Sid.S04 -> listOf("27" to "2", "31" to "7").forEach { (t, d) -> line(sid, "tempC" to t, "loweredC" to d) }
            Sid.S06 -> listOf("50분", "2시간 10분").forEach { line(sid, "elapsed" to it) }
            Sid.S07, Sid.S08 -> rests.forEach { r -> listOf("2.5", "30").forEach { km -> line(sid, "distanceKm" to km, "restName" to r) } }
            Sid.S07_DEPART -> rests.forEach { r -> hungryAt.forEach { h -> line(sid, "distanceKm" to "30", "restName" to r, "hungryAt" to h) } }
            Sid.S09 -> rests.forEach { line(sid, "restName" to it) }
            Sid.S11 -> rests.forEach { line(sid, "drove" to "1시간 40분", "distanceKm" to "12", "restName" to it) }
            Sid.S15 -> listOf("3", "12").forEach { line(sid, "minutes" to it) }
            Sid.QA -> line(sid, "text" to "카시트에 52분째예요.")
            else -> line(sid)
        }
        Golden.check("src/test/resources/golden", "voice-lines.txt", out.toString())
    }
}
