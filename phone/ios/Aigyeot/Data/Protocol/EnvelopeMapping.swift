import Foundation

/// DTO(계약 봉투) → 도메인(`Inbound`) 바꾸기. body를 type에 맞는 계약 값으로 읽는다. 읽지 못하면 `.ignored`(적용하지 않는다, G19).
extension Envelope {
    var inbound: Inbound {
        Inbound(id: id, seq: seq ?? 0, fromCar: from?.role == .car, message: message)
    }

    private var message: InboundMessage {
        func read<B: Decodable>(_ t: B.Type, _ make: (B) -> InboundMessage) -> InboundMessage {
            (try? decodeBody(t)).map(make) ?? .ignored
        }
        switch type {
        case MessageType.paired: return read(PairedBody.self, InboundMessage.paired)
        case MessageType.unpaired: return read(UnpairedBody.self, InboundMessage.unpaired)
        case MessageType.feedingState: return read(FeedingStateBody.self, InboundMessage.feedingState)
        case MessageType.songStatus: return read(SongStatusBody.self, InboundMessage.songStatus)
        case MessageType.reportSummary: return read(ReportSummaryBody.self, InboundMessage.report)
        case MessageType.handoffAlert: return read(HandoffAlertBody.self, InboundMessage.handoffAlert)
        default: return .ignored   // 모르는 type, feeding_started 같은 차 쪽 메시지는 무시
        }
    }
}
