import Foundation

/// HTTP 요청을 시작할 때 잡는 관찰 번호: 그때의 소켓·경로 관찰 번호와 요청 순번(규칙은 KeepConnected).
struct LinkMark: Equatable { let link: Int; let ticket: Int }

/// 중계 HTTP의 결과를 연결 표시(KeepConnected.status)에 알리고 401(GONE)을 LoseConnection에 넘기는 길.
/// 안드로이드는 중계 클라이언트의 `onObserved`·`onUnauthorized` 콜백이 이 일을 한다. iOS는 이 작은 길을 여러 유스케이스가 생성자로 받고,
/// 합성 루트가 KeepConnected에 잇는다(KeepConnected가 받기·보내기를 불러 서로 도는 길이라 클로저로).
@MainActor
final class RelayGate {
    private let state: PhoneStateRepository
    private let lose: LoseConnection
    private let markNow: () -> LinkMark
    private let report: (RelayError?, LinkMark) -> Void
    private let skew: () -> TimeInterval?

    init(state: PhoneStateRepository, lose: LoseConnection, mark: @escaping () -> LinkMark,
         observe: @escaping (RelayError?, LinkMark) -> Void, clockSkew: @escaping () -> TimeInterval?) {
        self.state = state
        self.lose = lose
        markNow = mark
        report = observe
        skew = clockSkew
    }

    /// HTTP 요청을 시작할 때 잡는다.
    func mark() -> LinkMark { markNow() }
    /// HTTP 결과를 띠에 알린다(규칙은 KeepConnected.observed).
    func observe(_ e: RelayError?, since mark: LinkMark) { report(e, mark) }
    /// 중계 시계 − 폰 시계(응답의 `Date` 헤더).
    var clockSkew: TimeInterval? { skew() }

    /// 띠 판단에 들어가는 HTTP: 시작할 때 표시를 잡고, 성공이면 끄고 중계 장애면 켠다. 연결이 바뀐 뒤의 결과는 띠를 바꾸지 않는다.
    func observed<T>(_ op: () async throws -> T) async throws -> T {
        let mark = mark(), c = state.creds
        do {
            let v = try await op()
            if state.creds == c { observe(nil, since: mark) }
            return v
        } catch let e as RelayError {
            if state.creds == c { observe(e, since: mark) }
            throw e
        }
    }

    /// 401을 받은 같은 연결(시작할 때 잡은 자격)의 요청: GONE. 다른 연결의 늦은 401은 버린다.
    func unauthorized(_ e: RelayError, c: Credentials?) {
        if e.isUnauthorized { lose.lost(c) }
    }
}
