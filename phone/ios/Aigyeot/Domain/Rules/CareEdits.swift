import Foundation

/// 돌봄 기록을 고치는 규칙. `CareFile.events`는 이 함수들로만 고친다. 모두 순수 함수다.
extension CareRules {
    /// 새 기록의 틀. 시간대는 지금 기기 시간대다.
    static func newEvent(_ kind: CareKind, at: Int64, zone: String = TimeZone.current.identifier) -> CareEvent {
        CareEvent(id: ULID.generate(date: CareTime.date(at)), kind: kind, startAt: at, zone: zone, createdAt: at, updatedAt: at)
    }

    /// 빠른 길로 닫기: 지금 시각으로 끝낸다. 이미 최대 길이를 넘었으면 끝 시각 모름으로 닫는다.
    /// 끝 시각 모름으로 닫았으면 true.
    @discardableResult
    static func closeQuickly(_ e: inout CareEvent, now t: Int64) -> Bool {
        let unknown = isOverLimit(e, now: t)
        // 시작은 지금+1분까지 고칠 수 있어 끝이 시작보다 앞서지 않게 한다.
        if unknown { e.endUnknown = true } else { e.endAt = max(t, e.startAt) }
        e.updatedAt = t
        return unknown
    }

    /// 수유·잠 시작: 같은 종류의 진행 중 기록(`previous`)을 빠른 길로 닫고 새 기록을 넣는다. 앞 기록을 끝 모름으로 닫았으면 true.
    @discardableResult
    static func start(_ e: CareEvent, closing previous: CareEvent?, now t: Int64, in c: inout CareFile) -> Bool {
        var closedUnknown = false
        if let p = previous, let i = c.events.firstIndex(where: { $0.id == p.id }) {
            closedUnknown = closeQuickly(&c.events[i], now: t)
        }
        c.events.append(e)
        return closedUnknown
    }

    /// 타이머·잠금 화면의 '끝내기'(빠른 길).
    static func endNow(_ id: String, now t: Int64, in c: inout CareFile) {
        if let i = c.events.firstIndex(where: { $0.id == id && $0.inProgress }) { closeQuickly(&c.events[i], now: t) }
    }

    /// 타이머가 최대 길이를 넘었을 때 '끝 시각 모름으로 닫기'.
    static func closeEndUnknown(_ id: String, now t: Int64, in c: inout CareFile) {
        if let i = c.events.firstIndex(where: { $0.id == id && $0.inProgress }) { c.events[i].endUnknown = true; c.events[i].updatedAt = t }
    }

    /// 빠른 기록·고치기 저장의 검사. 겹침은 `allowOverlap`이 아니면 묻는다. 문제가 없으면 nil.
    static func saveProblem(_ event: CareEvent, allowOverlap: Bool, in c: CareFile, now t: Int64) -> CareSaveResult? {
        if let p = problem(event, now: t) { return .problem(p) }
        if event.inProgress, let other = inProgress(event.kind, in: c.events), other.id != event.id {
            return .problem(.inProgressExists)
        }
        if !allowOverlap, overlaps(event, in: c.events, now: t) { return .overlap }
        return nil
    }

    /// 고친 기록은 만든 시각을 지킨다. 없으면 새로 넣는다.
    static func upsert(_ event: CareEvent, now t: Int64, in c: inout CareFile) {
        var e = event
        e.updatedAt = t
        if let i = c.events.firstIndex(where: { $0.id == e.id }) {
            e.createdAt = c.events[i].createdAt
            c.events[i] = e
        } else {
            c.events.append(e)
        }
    }

    /// 지우기는 표시만 한다. 차에는 알리지 않는다.
    static func delete(_ id: String, now t: Int64, in c: inout CareFile) {
        if let i = c.events.firstIndex(where: { $0.id == id && $0.deletedAt == nil }) { c.events[i].deletedAt = t; c.events[i].updatedAt = t }
    }

    /// 지운 지 5초 안의 '되돌리기'. 지운 사이 같은 종류를 새로 시작했으면 되살린 기록은 끝 시각 모름으로 둔다(진행 중은 하나).
    static func undoDelete(_ id: String, now t: Int64, in c: inout CareFile) {
        guard let i = c.events.firstIndex(where: { $0.id == id && $0.deletedAt != nil }) else { return }
        c.events[i].deletedAt = nil; c.events[i].updatedAt = t
        let e = c.events[i]
        if e.inProgress, c.events.contains(where: { $0.id != e.id && $0.kind == e.kind && $0.inProgress }) { c.events[i].endUnknown = true }
    }
}
