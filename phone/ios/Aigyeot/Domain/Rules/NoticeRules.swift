import Foundation

/// 위쪽 띠 글(U54). 띠는 하나라 안내를 줄로 모은다. 글을 고르고 합치는 규칙은 여기 한 곳이다(P1).
enum NoticeRules {
    /// 띠와 시트가 같은 글을 쓴다(범주 U 저장 실패).
    static let saveFailed = "저장하지 못했어요. 저장 공간을 확인해 주세요."
    static let paired = "연결됐어요"
    static let badLink = "올바른 연결 링크가 아니에요"   // 7.6 검사

    /// 7.1 outbox.dropped(U54): 버린 메시지의 띠 글. 노래 추가는 노래 줄에만 보인다.
    static func dropNotice(_ type: String) -> String? {
        switch type {
        case MessageType.feedingStarted: "수유 기록을 차로 보내지 못했어요."
        case MessageType.songRemoved: "노래 삭제를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요."
        case MessageType.songSelected: "실험에 쓸 노래 선택을 차로 보내지 못했어요."
        case MessageType.careSummary: CareText.careSummaryDropped
        default: nil
        }
    }

    /// 띠 글에 한 줄을 더한다. 떠 있는 안내(다른 버림·저장 실패)를 덮지 않고 줄을 바꿔 함께 보인다.
    /// 같은 글은 한 번. 사용자가 띠를 닫으면 다음 안내부터 새로 모은다(안드로이드 withNoticeLine).
    static func adding(_ line: String, to notice: String?) -> String {
        guard let n = notice else { return line }
        return n.components(separatedBy: "\n").contains(line) ? n : n + "\n" + line
    }

    /// 같은 종류가 진행 중이라 앞 기록을 빠른 길로 닫았을 때의 안내(U14·U21). 수유·잠만 있다.
    static func previousEnded(_ kind: CareKind, unknown: Bool) -> String? {
        switch kind {
        case .feed: unknown ? CareText.previousFeedEndedUnknown : CareText.previousFeedEnded
        case .sleep: unknown ? CareText.previousSleepEndedUnknown : CareText.previousSleepEnded
        default: nil
        }
    }

    /// 시계 차이 띠(9장). 2분이 넘으면 보인다.
    static func clockWarning(skew: TimeInterval?) -> String? {
        guard let s = skew, abs(s) > Retention.clockWarning else { return nil }
        return "휴대폰 시계가 \(Int((abs(s) / 60).rounded()))분 달라요. 수유 시각이 어긋날 수 있어요."
    }
}
