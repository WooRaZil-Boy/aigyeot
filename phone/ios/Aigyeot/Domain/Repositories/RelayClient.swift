import Foundation

/// 중계 호출 실패. 화면 문구는 앱이 `code`로 정한다(계약 1.1·9장).
enum RelayError: Error, Equatable {
    case http(status: Int, code: String?, retryAfter: TimeInterval?)
    case network
    /// 화면을 떠나거나 뒤로 가 멈춘 요청. 중계 불통이 아니라 띠·재시도에 세지 않는다(9장)
    case cancelled
    case invalidResponse

    var status: Int? { if case .http(let s, _, _) = self { return s } else { return nil } }
    var code: String? { if case .http(_, let c, _) = self { return c } else { return nil } }
    var retryAfter: TimeInterval? { if case .http(_, _, let r) = self { return r } else { return nil } }
    var isUnauthorized: Bool { status == 401 }
    var isOffline: Bool { self == .network }
    /// 중계에 닿지 않거나 중계가 고장(5xx): 오프라인 띠를 켠다(계약 9장 '중계 불통(네트워크·중계 장애)', 안드로이드와 같다).
    var isRelayDown: Bool { self == .network || (status ?? 0) >= 500 }
}

/// 폰이 쓰는 중계 HTTP 호출 전부(두 앱 같은 이름, phone-care-plan 9.1). 구현은 Data/Relay/HTTPRelayClient, 시험에서는 가짜로 바꾼다.
/// 연결 요청 말고는 모두 `token`을 받는다: 부른 쪽이 일을 시작할 때 잡은 자격의 토큰이다(P5, 안드로이드와 같다).
protocol RelayClient {
    func pair(code: String, pushToken: String?) async throws -> PairResponse
    func devices(token: String) async throws -> DevicesResponse
    func patchMe(token: String, _ request: PatchDeviceRequest) async throws -> PatchDeviceResponse
    /// 끊기(설정), 그리고 저장하지 못한 연결 되돌리기(연결 응답의 토큰).
    func deleteMe(token: String) async throws
    func createInvite(token: String) async throws -> InviteResponse
    func send(token: String, _ envelope: Envelope) async throws -> SendMessageResponse
    /// `car`: 읽지 못한 봉투를 ack만 하게 남길 때 붙일 차 id(그 연결의 것).
    func inbox(token: String, car: String, afterSeq: Int64?) async throws -> InboxResponse
    func ack(token: String, ids: [String]) async throws
    func putSong(token: String, id: String, file: URL) async throws
}

/// 중계 WebSocket 사건(계약 2장).
enum RelaySocketEvent {
    case connected(Bool)
    /// 붙은 뒤의 pong: 중계에 닿고 있다(앞 HTTP 실패로 켠 띠를 끈다, 안드로이드와 같다).
    case alive
    case envelope(Envelope)
    case unauthorized
    /// 응답을 받지 못했거나 5xx: 중계에 닿지 않는다(계약 9장 '중계 불통'). 다시 붙기는 계속한다.
    case unreachable
}

/// 중계 WebSocket(두 앱 같은 이름, phone-care-plan 9.1). 폰은 앱이 앞에 있을 때만 붙는다. 구현은 Data/Relay/URLSessionRelaySocket.
protocol RelaySocket {
    /// 잡아 둔 자격의 토큰으로 소켓을 열고 붙기 시작한다(다시 붙을 때도 같은 토큰, P5). 사건은 메인에서 알린다.
    func open(token: String, onEvent: @escaping @MainActor (RelaySocketEvent) -> Void) -> SocketHandle
}

/// 연 소켓(두 앱 같은 이름). 닫으면 다시 붙지 않는다.
protocol SocketHandle: AnyObject {
    func start()
    func stop()
}
