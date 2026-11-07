import Foundation

/// 받은 봉투를 도메인이 판단에 쓰는 꼴로 바꾼 것(계약 3.3). Data/Protocol/EnvelopeMapping이 만든다.
/// 본문은 차와 글자까지 같은 계약 값이라 그대로 담는다(표시 전용, 복사 타입을 만들지 않는다).
enum InboundMessage: Equatable {
    case paired(PairedBody)
    case unpaired(UnpairedBody)
    case feedingState(FeedingStateBody)
    case songStatus(SongStatusBody)
    case report(ReportSummaryBody)
    case handoffAlert(HandoffAlertBody)
    /// 모르는 type, 차 쪽 메시지(feeding_started 등), 읽지 못한 body(필수 칸이 빠짐): 적용하지 않고 처리한 것으로 친다.
    case ignored
}

/// 받은 메시지 하나(봉투의 id·seq·보낸 쪽과 바꾼 본문).
struct Inbound: Equatable {
    var id: String
    var seq: Int64
    var fromCar: Bool
    var message: InboundMessage
}
