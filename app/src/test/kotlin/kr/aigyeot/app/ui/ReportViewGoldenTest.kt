package kr.aigyeot.app.ui

import kotlinx.serialization.json.jsonObject
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.ReportSummaryBody
import java.io.File
import java.time.ZoneId
import kotlin.test.BeforeTest
import kotlin.test.Test

/** 공용 JSON 예제(report_summary*)가 차 리포트 화면 값(숫자 줄·띠·자세히·차가 한 일·조향·방법별)으로 어떻게 바뀌는지 고정한다. */
class ReportViewGoldenTest {
    @BeforeTest fun zone() { TimeText.zone = ZoneId.of("Asia/Seoul") }

    private fun dump(name: String): String {
        val raw = File("../shared/src/test/resources/protocol/$name.json").readText()
        val body = ProtocolJson.decodeFromJsonElement(ReportSummaryBody.serializer(), ProtocolJson.parseToJsonElement(raw).jsonObject.getValue("body"))
        val v = reportView(body, settling = false)
        return buildString {
            appendLine("[$name]")
            appendLine("제목: ${v.title}")
            v.stats.forEach { appendLine("숫자: $it") }
            v.episodes.forEach { appendLine("울음 카드: ${it.title} / ${it.chart}") }
            v.details.forEach { appendLine("자세히: ${if (it.head) "# " else "  "}${it.text}") }
            v.actions.forEach { appendLine("차가 한 일: $it") }
            appendLine("3D 칩: ${v.callouts.sortedBy { it.name }}")
            appendLine("조향: ${v.steer} / ${v.steerText}")
            v.methods.forEach { appendLine("방법별: $it") }
        }
    }

    @Test fun reportViews_matchGolden() {
        assertGolden("report_view.txt", listOf("report_summary", "report_summary_handoff", "report_summary_womb").joinToString("\n") { dump(it) })
    }
}
