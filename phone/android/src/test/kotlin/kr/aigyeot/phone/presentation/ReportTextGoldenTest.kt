package kr.aigyeot.phone.presentation

import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kr.aigyeot.phone.Golden
import kr.aigyeot.phone.domain.model.actionText
import kr.aigyeot.phone.domain.model.cabinTempText
import kr.aigyeot.phone.domain.model.controlStatsText
import kr.aigyeot.phone.domain.model.controlTitle
import kr.aigyeot.phone.domain.model.episodeHead
import kr.aigyeot.phone.domain.model.episodeSectionHead
import kr.aigyeot.phone.domain.model.outputRouteText
import kr.aigyeot.phone.domain.model.playName
import kr.aigyeot.phone.domain.model.preCryDrive
import kr.aigyeot.phone.domain.model.purposeText
import kr.aigyeot.phone.domain.model.reportCries
import kr.aigyeot.phone.domain.model.reportDate
import kr.aigyeot.phone.domain.model.reportRowSub
import kr.aigyeot.phone.domain.model.reportRowTitle
import kr.aigyeot.phone.domain.model.reportSpan
import kr.aigyeot.phone.domain.model.triggerText
import kr.aigyeot.phone.fixture
import kr.aigyeot.phone.show
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.ReportSummaryBody
import org.junit.Test

/** 리포트 화면에 나가는 글: 리포트 예제 파일마다 문구 함수가 내는 줄을 골든 파일과 견준다. */
class ReportTextGoldenTest {
    private val zone = ZoneId.of("Asia/Seoul")

    private fun body(name: String): ReportSummaryBody = ProtocolJson.decodeFromJsonElement(ReportSummaryBody.serializer(), fixture(name).body as JsonObject)

    private fun lines(name: String): List<String> = buildList {
        val b = body(name)
        val key = { field: String -> "$name.$field" }
        add("${key("date")}: ${reportDate(b, zone).show()}")
        add("${key("rowTitle")}: ${reportRowTitle(b, zone)}")
        add("${key("cries")}: ${reportCries(b)}")
        add("${key("rowSub")}: ${reportRowSub(b)}")
        add("${key("route")}: ${outputRouteText(b.outputRoute).show()}")
        add("${key("sectionHead")}: ${episodeSectionHead(b.timeline.map { it.trigger })}")
        for ((i, c) in b.preCryContext.withIndex()) {
            add("${key("preCry[$i].drive")}: ${preCryDrive(c)}")
            add("${key("preCry[$i].cabinTemp")}: ${c.cabinTempC?.let(::cabinTempText).show()}")
        }
        for ((i, e) in b.timeline.withIndex()) {
            val ep = key("timeline[$i]")
            add("$ep.trigger: ${triggerText(e.trigger)}")
            add("$ep.head: ${episodeHead(e.trigger)}")
            for ((k, v) in episodeSoundLines(emptyList(), e) + episodeResultLines(emptyList(), e)) add("$ep.line.$k: $v")
            for ((j, p) in e.plays.withIndex()) {
                add("$ep.plays[$j]: ${purposeText(p.purpose)} ${playName(emptyList(), p)} ${reportSpan(p.startedAt, p.endedAt, zone)} ${seconds(p.playS)}")
            }
        }
        for ((i, a) in b.carActions.withIndex()) add("${key("carActions[$i]")}: ${actionText(a.action)}")
        for ((i, c) in b.controls.withIndex()) {
            add("${key("controls[$i].title")}: ${controlTitle(c)}")
            add("${key("controls[$i].stats")}: ${controlStatsText(c)}")
        }
    }

    @Test
    fun `리포트 화면 문구는 골든 파일 그대로다`() {
        Golden.check("report_text.txt", listOf("report_summary", "report_summary_handoff", "report_summary_womb").flatMap(::lines))
    }
}
