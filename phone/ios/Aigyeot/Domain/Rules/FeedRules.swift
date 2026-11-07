import Foundation

/// 수유를 차로 보내는 규칙(U3·U4·R5·R6·R12). `feedingLog`·`lastSentFeedingAt`은 이 파일과 받기(InboxRules)·보낸 결과(OutboxRules)로만 고친다(P3).
enum FeedRules {
    /// R5: 지우지 않은 수유 중 가장 늦은 시작이 `lastSentFeedingAt`보다 늦고 24시간 안이면 보낸다.
    /// R11 이전을 마쳤고 지우다 만 기록 지우기가 없어야 한다(지우다 만 기록을 새 차로 보내지 않는다, R10). owner·연결은 부르는 쪽이 본다.
    static func latestToSend(_ care: CareFile, _ s: PhoneState, now: Date) -> CareEvent? {
        guard care.migratedFeedings, s.clearingCareIds == nil, let latest = CareRules.latestFeed(care.events) else { return nil }
        guard latest.startAt > (s.lastSentFeedingAt ?? .min), latest.startAt >= CareTime.ms(now) - CareRules.sendWindowMs else { return nil }
        return latest
    }

    /// 지금의 수유 보내기 경로(보낼 함 + 전송 상태 기록)에 넣고 같은 저장에서 `lastSentFeedingAt`을 올린다.
    /// 메시지 id는 보낼 때마다 새로 만든다(같은 수유를 더 늦게 고쳐도 다시 보낼 수 있게).
    static func enqueue(_ latest: CareEvent, now t: Date, in s: inout PhoneState) {
        let at = CareTime.date(latest.startAt)
        let id = ULID.generate(date: t)
        let item = OutboxRules.item(type: MessageType.feedingStarted, body: FeedingStartedBody(at: Rfc3339.string(at)), id: id, now: t)
        s.feedingLog.insert(FeedingRecord(id: id, at: at), at: 0)
        s.feedingLog = Array(s.feedingLog.prefix(30))
        s.outbox.append(item)
        s.lastSentFeedingAt = latest.startAt
    }

    /// 이 수유를 보낸 기록(보내는 중·보냄·차에 반영됨). `state.json`은 초까지만 저장해(.iso8601) 1초 안이면 같은 수유로 본다(받기의 반영 판정과 같다).
    static func record(for e: CareEvent, in s: PhoneState) -> FeedingRecord? {
        s.feedingLog.first { abs(CareTime.ms($0.at) - e.startAt) < 1000 }
    }

    /// 화면에 보일 수유 시작. 중계가 미래 시각을 고쳐 저장했으면 그 시각이다(계약 3.3). 기록의 누른 시각은 고치지 않는다(G33).
    static func shownStart(_ e: CareEvent, in s: PhoneState) -> Date { record(for: e, in: s)?.fixedAt ?? CareTime.date(e.startAt) }

    /// 마지막 수유 시작: 지우지 않은 수유 기록 중 가장 늦은 것과 차의 `feeding_state` 값 중 늦은 것(R12).
    static func lastFeedingStart(_ s: PhoneState, _ care: CareFile) -> Date? {
        [s.feeding.lastStart, CareRules.latestFeed(care.events).map { shownStart($0, in: s) }].compactMap { $0 }.max()
    }

    /// R11·U22: 옛 경로가 이미 보낼 함에 넣은 수유를 다시 보내지 않게 `lastSentFeedingAt`을 옛 기록의 가장 늦은 시각까지 올린다.
    static func markMigrated(in s: inout PhoneState) {
        if let latest = s.feedingLog.map({ CareTime.ms($0.at) }).max() { s.lastSentFeedingAt = max(s.lastSentFeedingAt ?? .min, latest) }
    }
}
