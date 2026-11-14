package kr.aigyeot.phone.presentation

import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kr.aigyeot.phone.Golden
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.onDate
import kr.aigyeot.phone.fixture
import kr.aigyeot.phone.show
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.ReportSummaryBody
import org.junit.Test

/** 차트 값: 대표 입력(기록 한 벌과 리포트 예제)에서 순수 함수가 내는 값을 골든 파일과 견준다. */
class ChartGoldenTest {
    private val zone = APP_ZONE
    private val today = LocalDate.of(2026, 11, 9)
    private fun at(h: Int, m: Int = 0, day: LocalDate = today) = onDate(day, h, m, zone)
    private val now = at(14, 13)
    private var n = 0
    private fun ev(kind: CareKind, start: Long, end: Long? = null, deleted: Boolean = false) =
        CareEvent("e${n++}", kind, start, end, zone = zone.id, createdAt = start, updatedAt = start, deletedAt = if (deleted) start else null)

    private fun report(name: String): ReportSummaryBody = ProtocolJson.decodeFromJsonElement(ReportSummaryBody.serializer(), fixture(name).body as JsonObject)

    private fun careLines(): List<String> = buildList {
        val care = CareFile(
            sleepDiaperEnabled = true,
            events = listOf(
                ev(CareKind.SLEEP, at(23, 0, today.minusDays(1)), at(1, 0)),
                ev(CareKind.SLEEP, at(10, 20), at(11, 40)),
                ev(CareKind.FEED, at(3, 15), at(3, 35)),
                ev(CareKind.FEED, at(14, 0)),
                ev(CareKind.FEED, at(9, 0), at(9, 20), deleted = true),
                ev(CareKind.DIAPER, at(12, 58))
            )
        )
        val day = dayChart(care, emptyList(), today, now, zone, showCar = false)
        add("dayChart.nowMin: ${day.nowMin.show()}")
        add("dayChart.summary: ${day.summary}")
        for (lane in day.lanes) {
            add("dayChart.${lane.name}.head: ${lane.sub.show()} | ${lane.value}")
            for (s in lane.spans) add("dayChart.${lane.name}.span: ${s.from}-${s.to} ${s.tip}")
            for (m in lane.marks) add("dayChart.${lane.name}.mark: ${m.at} ${m.tip}")
        }
        val week = intervalWeek(care, now, zone)
        add("intervalWeek.usualMs: ${week.usualMs.show()}")
        for (b in week.bars) add("intervalWeek.bar: ${b.label} ${b.value.show()} ${b.tip.show()}")
        val grid = sleepGrid(care, now, zone)
        for ((i, row) in grid.rows.withIndex()) add("sleepGrid.row[$i]: " + row.joinToString("") { if (it) "1" else "0" })
        add("sleepGrid.perDayMs: ${grid.perDayMs}")
    }

    private fun reportLines(name: String): List<String> = buildList {
        val b = report(name)
        val kpi = reportKpis(b)
        add("$name.kpis: detectS=${kpi.detectS.show()} cryS=${kpi.cryS} actionKinds=${kpi.actionKinds}")
        add("$name.minutes: ${reportMinutes(b).show()}")
        val cry = carCryWeek(listOf(b), now, zone)
        add("$name.carCryWeek.avgLatencyS: ${cry.avgLatencyS.show()}")
        for (bar in cry.bars) add("$name.carCryWeek.bar: ${bar.label} ${bar.value.show()} ${bar.tip.show()}")
        for ((i, e) in b.timeline.withIndex()) {
            val band = episodeBand(e, emptyList())
            add("$name.episode[$i].head: ${band.head}")
            for (s in band.sounds) add("$name.episode[$i].sound: ${s.from}-${s.to} ${s.name.show()} ${s.playS.show()}")
            for (m in band.marks) add("$name.episode[$i].mark: ${m.at} ${m.label}")
            add("$name.episode[$i].outcomes: ${band.outcomes}")
        }
    }

    @Test
    fun `차트 값은 골든 파일 그대로다`() {
        Golden.check("chart_values.txt", careLines() + listOf("report_summary", "report_summary_handoff", "report_summary_womb").flatMap(::reportLines))
    }
}
