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
protocol RelayClient {
    func pair(code: String, pushToken: String?) async throws -> PairResponse
    func devices() async throws -> DevicesResponse
    func patchMe(_ request: PatchDeviceRequest) async throws -> PatchDeviceResponse
    func deleteMe() async throws
    /// 저장하지 못한 연결을 되돌린다: Keychain이 아니라 연결 응답의 토큰을 쓴다.
    func deleteMe(token: String) async throws
    func createInvite() async throws -> InviteResponse
    func send(_ envelope: Envelope) async throws -> SendMessageResponse
    func inbox(afterSeq: Int64?) async throws -> InboxResponse
    func ack(ids: [String]) async throws
    func putSong(id: String, file: URL) async throws
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
protocol RelaySocket: AnyObject {
    func start()
    func stop()
}

/// 소켓을 만드는 함수(합성 루트가 넘긴다, 시험에서는 없음). 토큰은 붙을 때마다 읽고, 사건은 메인에서 알린다.
typealias RelaySocketMaker = (_ token: @escaping () -> String?, _ onEvent: @escaping @MainActor (RelaySocketEvent) -> Void) -> RelaySocket
