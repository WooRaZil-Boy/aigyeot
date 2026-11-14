import Foundation

// MARK: 내 기록 내보내기

/// 손 기록 CSV. 지우지 않은 것을 시작 시각 오름차순으로, 차 기록 줄은 넣지 않는다.
/// UTF-8(BOM), CRLF, 첫 줄은 `export.csvHeader`, 시각은 그 기록 시간대의 오프셋을 붙인 ISO 8601.
enum CareExport {
    static func csv(_ events: [CareEvent]) -> Data {
        var lines = [CareText.csvHeader]
        for e in CareRules.live(events).sorted(by: { $0.startAt < $1.startAt }) {
            lines.append([
                e.kind.rawValue,
                iso(e.startAt, e.timeZone),
                e.endAt.map { iso($0, e.timeZone) } ?? "",
                e.endUnknown ? "true" : "false",
                e.feedSide?.rawValue ?? "",
                e.amountMl.map(String.init) ?? "",
                e.diaper?.rawValue ?? "",
                quoted(e.note ?? "")
            ].joined(separator: ","))
        }
        return Data([0xEF, 0xBB, 0xBF]) + Data((lines.joined(separator: "\r\n") + "\r\n").utf8)
    }

    /// "2026-11-05T09:00:00+09:00"
    static func iso(_ ms: Int64, _ zone: TimeZone) -> String {
        Rfc3339.string(CareTime.date(ms), timeZone: zone)   // 같은 형식, 시간대마다 만든 포매터를 다시 쓴다
    }

    /// RFC 4180: 쉼표·따옴표·줄바꿈이 있으면 따옴표로 감싸고 안의 따옴표는 두 번 쓴다.
    static func quoted(_ s: String) -> String {
        s.unicodeScalars.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) ? "\"" + s.replacingOccurrences(of: "\"",
            with: "\"\"") + "\"" : s
    }
}
