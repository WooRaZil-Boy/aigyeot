import Foundation
import Observation

/// 중계에 닿는지(오프라인 띠, 계약 9장)와 시계 차이의 원본. 칸은 이 클래스의 함수로만 바뀐다(P3).
/// HTTP 결과·소켓 사건·인터넷 경로가 사실만 넘긴다. 나중에 시작한 관찰이 이긴다(관찰 번호).
@MainActor
@Observable
final class LinkObserver {
    /// 기기가 인터넷에 닿지 않거나 마지막 관찰이 중계 불통이다.
    private(set) var offline = false
    private(set) var socketConnected = false
    private(set) var clockSkew: TimeInterval?
    /// 소켓이 중계에 닿았는지(붙음·닿지 않음)를 본 횟수. 이 값이 바뀐 뒤에 끝난 HTTP 요청은 더 오래된 관찰이라 띠를 바꾸지 않는다.
    @ObservationIgnored private var linkSeen = 0
    /// HTTP 요청에 시작 순서대로 매기는 번호. 늦게 끝난 앞 요청의 결과가 나중에 시작한 요청의 결과를 덮지 않게 한다.
    @ObservationIgnored private var httpTicket = 0
    @ObservationIgnored private var appliedTicket = 0
    @ObservationIgnored private var lastPathSatisfied: Bool?

    /// HTTP 요청을 시작할 때 잡는다: 그때의 소켓·경로 관찰 번호와 요청 순번.
    struct Mark { let link: Int; let ticket: Int }
    func mark() -> Mark {
        httpTicket += 1
        return Mark(link: linkSeen, ticket: httpTicket)
    }

    /// HTTP 결과를 띠에 반영하는 한 곳(9장). 응답을 받았으면(성공·4xx) 중계에 닿았고, 닿지 않거나 5xx면 불통이다(안드로이드 `<500`은 닿음).
    /// 응답이 아닌 실패(형식 오류 등)는 띠를 그대로 둔다.
    func observe(_ e: RelayError?, since mark: Mark) {
        guard let e else { setOffline(false, since: mark); return }
        if e.isRelayDown { setOffline(true, since: mark) } else if e.status != nil { setOffline(false, since: mark) }
    }

    /// HTTP 요청의 결과로 띠를 바꾼다. 요청을 시작한 뒤(`mark`) 소켓·인터넷 경로가 새로 본 것이 있으면 그쪽이 더 새 관찰이다.
    private func setOffline(_ value: Bool, since mark: Mark) {
        guard mark.link == linkSeen, mark.ticket > appliedTicket else { return }
        offline = value
        appliedTicket = mark.ticket
    }

    /// 소켓이 붙었거나 pong을 받았다: 중계에 닿고 있다(보호자 폰은 HTTP가 적어 이것이 띠를 끈다).
    func reached() { linkSeen += 1; offline = false }
    /// 앞에 있는 동안 소켓이 중계에 닿지 않는다(계약 9장 '중계 불통').
    func unreachable() { linkSeen += 1; offline = true }
    func socketChanged(_ up: Bool) { socketConnected = up }

    /// 인터넷 경로가 바뀌었다(NWPathMonitor). 같은 상태의 갱신(인터페이스·비용 변화 등)이면 false.
    /// 경로가 없으면 띠를 켠다. 경로가 생겼다고 중계에 닿는 것은 아니라 띠는 그대로 둔다: 끄는 것은 소켓이 붙거나 HTTP가 성공할 때다.
    func pathChanged(_ satisfied: Bool) -> Bool {
        guard lastPathSatisfied != satisfied else { return false }
        lastPathSatisfied = satisfied
        linkSeen += 1   // 이 관찰보다 먼저 시작한 HTTP의 늦은 결과가 덮지 않게
        if !satisfied { offline = true }
        return true
    }

    /// 연결 안 됨에 인터넷이 돌아왔다: 확인할 중계가 없어 띠를 끈다.
    func noRelayToCheck() { offline = false }

    /// 응답의 `Date` 헤더(계약 9장).
    func noteServerDate(_ date: Date, now: Date) { clockSkew = date.timeIntervalSince(now) }

    var clockWarning: String? { NoticeRules.clockWarning(skew: clockSkew) }
}
