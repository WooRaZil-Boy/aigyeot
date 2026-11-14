@testable import Aigyeot
import XCTest

/// Domain/Rules·Repositories 시험. 화면·중계 없이 순수 함수와 저장소만 본다.
final class DomainRulesTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func test_띠_글은_같은_글을_한_번만_더한다() {
        XCTAssertEqual(NoticeRules.adding("가", to: nil), "가")
        XCTAssertEqual(NoticeRules.adding("가", to: "가"), "가")
        XCTAssertEqual(NoticeRules.adding("나", to: "가"), "가\n나")
        XCTAssertNil(NoticeRules.previousEnded(.note, unknown: false))
        XCTAssertNil(NoticeRules.clockWarning(skew: 120))
        XCTAssertNotNil(NoticeRules.clockWarning(skew: -121))
    }

    func test_재시도_간격은_한_곳에서_정한다() {
        XCTAssertEqual((0..<6).map { RetryPolicy.inboxRetry(retries: $0) }, [5, 10, 20, 40, 60, 60])
        XCTAssertEqual((0..<8).map { RetryPolicy.outboxBackoff(attempts: $0) }, [1, 2, 4, 8, 16, 32, 60, 60])
        XCTAssertEqual(RetryPolicy.pairLock(retryAfter: nil), 10)
        XCTAssertEqual(RetryPolicy.pairLock(retryAfter: 0.2), 1)
        XCTAssertEqual(OutboxPolicy.classify(.http(status: 429, code: nil, retryAfter: nil), attempts: 0), .retry(RetryPolicy.rateLimitDefault))
    }

    func test_만료는_차에_반영된_수유를_버림으로_알리지_않고_보내는_중은_둔다() {
        var s = PhoneState()
        let old = t0.addingTimeInterval(-8 * 86400)
        s.feedingLog = [FeedingRecord(id: "F1", at: old, status: .reflected), FeedingRecord(id: "F2", at: old)]
        s.outbox = [OutboxItem(id: "F1", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: old, nextTryAt: old),
                    OutboxItem(id: "F2", type: MessageType.feedingStarted, body: .object([:]), ts: "t", createdAt: old, nextTryAt: old),
                    OutboxItem(id: "S1", type: MessageType.songSelected, body: .object([:]), ts: "t", createdAt: old, nextTryAt: old)]
        let expired = OutboxRules.expire(before: t0.addingTimeInterval(-Retention.week), except: "S1", in: &s)
        XCTAssertEqual(expired, [MessageType.feedingStarted])
        XCTAssertEqual(s.outbox.map(\.id), ["S1"])                       // 보내는 중은 결과를 먼저 적용한다
        XCTAssertEqual(s.feedingLog.map(\.status), [.reflected, .expired])
        XCTAssertEqual(s.unseenDrops, [MessageType.feedingStarted])     // 반영 안 된 F2만 알린다
    }

    func test_보낸_결과는_한_함수가_한_번에_적용한다() {
        var s = PhoneState()
        let item = OutboxRules.item(type: MessageType.songRemoved, body: SongRefBody(songId: "A"), now: t0)
        s.outbox = [item]
        OutboxRules.completeDropped(item, .http(status: 400, code: nil, retryAfter: nil), wasQueued: false, in: &s)
        XCTAssertTrue(s.outbox.isEmpty)
        XCTAssertTrue(s.unseenDrops.isEmpty)   // 보낼 함에서 이미 빠진 것의 거절은 알리지 않는다
        s.outbox = [item]
        OutboxRules.scheduleRetry(item.id, delay: 30, network: true, untry: false, now: t0, in: &s)
        XCTAssertEqual(s.outbox[0].attempts, 1)
        XCTAssertEqual(s.outbox[0].nextTryAt, t0.addingTimeInterval(30))
        XCTAssertTrue(s.outbox[0].networkWait)
        XCTAssertTrue(OutboxRules.hasNetworkWait(s, now: t0))
        OutboxRules.hurryNetworkWaits(now: t0, in: &s)
        XCTAssertEqual(s.outbox[0].nextTryAt, t0)
    }

    func test_기록_지우기의_보낼_함은_노래_올리기와_안_보낸_요약만_뺀다() {
        var s = PhoneState()
        let song = OutboxRules.item(type: MessageType.songAdded, body: SongRefBody(songId: "A"), songId: "A", now: t0)
        var tried = OutboxRules.item(type: MessageType.careSummary, body: SongRefBody(songId: "x"), now: t0)
        tried.markTried(true)
        let unsent = OutboxRules.item(type: MessageType.careSummary, body: SongRefBody(songId: "y"), now: t0)
        let removal = OutboxRules.item(type: MessageType.songRemoved, body: SongRefBody(songId: "B"), now: t0)
        s.outbox = [song, tried, unsent, removal]
        OutboxRules.clearForRecordsWipe(sending: nil, in: &s)
        XCTAssertEqual(s.outbox.map(\.id), [tried.id, removal.id])   // 보내 본 요약은 결과를 확정할 때까지 둔다
    }

    func test_24시간_지난_수신은_상태_요청_표시가_꺼져_있을_때만_켠다() {
        var s = PhoneState()
        XCTAssertFalse(InboxRules.needsStaleMark(s, now: t0))
        s.lastReceivedAt = t0.addingTimeInterval(-Retention.stale - 1)
        XCTAssertTrue(InboxRules.needsStaleMark(s, now: t0))
        s.needsStateRequest = true
        XCTAssertFalse(InboxRules.needsStaleMark(s, now: t0))
    }

    func test_수유_보내기는_옮기기를_마치고_더_늦고_24시간_안일_때만() {
        let now = CareTime.ms(t0)
        var care = CareFile()
        care.events = [CareEvent(id: "a", kind: .feed, startAt: now - 1000, zone: "Asia/Seoul", createdAt: now, updatedAt: now)]
        var s = PhoneState()
        XCTAssertNil(FeedRules.latestToSend(care, s, now: t0))           // 이전 전
        care.migratedFeedings = true
        XCTAssertEqual(FeedRules.latestToSend(care, s, now: t0)?.id, "a")
        s.lastSentFeedingAt = now - 1000
        XCTAssertNil(FeedRules.latestToSend(care, s, now: t0))           // 더 늦지 않다
        s.lastSentFeedingAt = nil
        s.clearingCareIds = ["z"]
        XCTAssertNil(FeedRules.latestToSend(care, s, now: t0))           // 지우다 만 기록
        s.clearingCareIds = nil
        FeedRules.enqueue(care.events[0], now: t0, in: &s)
        XCTAssertEqual(s.outbox.map(\.type), [MessageType.feedingStarted])
        XCTAssertEqual(s.feedingLog.first?.status, .sending)
        XCTAssertEqual(s.lastSentFeedingAt, now - 1000)
    }

    func test_빈_요약은_넣은_적이_없으면_보내지_않는다() {
        var care = CareFile()
        care.migratedFeedings = true
        let zone = TimeZone(identifier: "Asia/Seoul")!
        var s = PhoneState()
        XCTAssertEqual(SummaryRules.decide(care, s, nowMs: CareTime.ms(t0), zone: zone, sending: nil), .unchanged)
        s.maxSentCareAsOf = CareTime.ms(t0) - 60_000
        guard case .enqueue(_, _, let asOf) = SummaryRules.decide(care, s, nowMs: CareTime.ms(t0) + 123, zone: zone, sending: nil) else { return XCTFail("기대한 꼴이 아니다") }
        XCTAssertEqual(asOf % 1000, 0)   // as_of는 초까지
    }
}

@MainActor
final class RepositoryTests: XCTestCase {
    private let creds = Credentials(carId: "c_1", deviceId: "d_1", token: "t", role: .owner)

    func test_연결_바꾸기는_상태를_저장해야_자격을_켜고_옛_자격의_쓰기는_버린다() {
        let before = Credentials(carId: "c_0", deviceId: "d_0", token: "t0", role: .owner)
        let store = MemoryStateStore(PhoneState(introSeen: true, carId: "c_0", needsStateRequest: true))
        let repo = PhoneStateRepository(store: store, creds: before)
        let fresh: (PhoneState) -> PhoneState = { PhoneStateRepository.fresh(introSeen: $0.introSeen, keeping: $0) }
        store.failNext = 1
        XCTAssertFalse(repo.switchTo(creds, make: fresh).switched)
        XCTAssertEqual(repo.connection, .paired(before))   // 지난 기록을 비운 상태를 저장하지 못하면 새 자격을 켜지 않는다
        XCTAssertTrue(repo.state.needsStateRequest)
        XCTAssertEqual(repo.switchTo(creds, make: fresh), .init(switched: true, saved: true))
        XCTAssertEqual(repo.connection, .paired(creds))
        XCTAssertFalse(repo.state.needsStateRequest)   // 지난 기록은 비웠다
        XCTAssertTrue(repo.state.introSeen)
        XCTAssertTrue(repo.isCurrent(creds))
        XCTAssertFalse(repo.isCurrent(before))   // 옛 자격으로 시작한 결과는 버려진다
        XCTAssertFalse(repo.commit(before) { $0.needsStateRequest = true })
        XCTAssertFalse(repo.state.needsStateRequest)
        XCTAssertFalse(repo.switchTo(nil, make: fresh, onlyIf: before).switched)   // 옛 자격의 늦은 끊기는 새 연결을 지우지 않는다
        XCTAssertEqual(repo.connection, .paired(creds))
    }

    func test_끊기는_저장이_실패해도_자격을_잊고_GONE은_연결이_없을_때만_켠다() {
        let store = MemoryStateStore(PhoneState(carId: "c_1"))
        let repo = PhoneStateRepository(store: store, creds: creds)
        store.failNext = 1
        XCTAssertEqual(repo.switchTo(nil, make: { PhoneStateRepository.fresh(introSeen: $0.introSeen, keeping: $0) }, onlyIf: creds),
                       .init(switched: true, saved: false))
        XCTAssertEqual(repo.connection, .none)
        repo.showGone(deviceId: "d_1")
        XCTAssertEqual(repo.connection, .gone(deviceId: "d_1"))
        XCTAssertNil(repo.connection.creds)
        repo.hideGone(onlyDevice: "d_other")
        XCTAssertEqual(repo.connection, .gone(deviceId: "d_1"))   // 다른 기기의 GONE이면 그대로
        repo.hideGone()
        XCTAssertEqual(repo.connection, .none)
        _ = repo.switchTo(creds, make: { $0 })
        repo.showGone(deviceId: "d_1")
        XCTAssertEqual(repo.connection, .paired(creds))   // 그사이 새로 연결했으면 옛 끊김을 켜지 않는다
    }

    func test_강제_끊김_정리가_남은_기기는_자격이_없을_때만_GONE으로_시작한다() {
        XCTAssertEqual(PhoneStateRepository(store: MemoryStateStore(), creds: nil, goneDevice: "d_1").connection, .gone(deviceId: "d_1"))
        XCTAssertEqual(PhoneStateRepository(store: MemoryStateStore(), creds: creds, goneDevice: "d_1").connection, .paired(creds))
    }

    func test_자격이_없는데_차가_남은_상태는_메모리에서만_비운다() {
        let store = MemoryStateStore(PhoneState(introSeen: false, carId: "c_1", clearingCareIds: ["x"]))
        let repo = PhoneStateRepository(store: store, creds: nil)
        XCTAssertNil(repo.state.carId)
        XCTAssertTrue(repo.state.introSeen)
        XCTAssertEqual(repo.state.clearingCareIds, ["x"])   // 지우다 만 기록 지우기는 남긴다
        XCTAssertEqual(store.value.carId, "c_1")              // 저장은 다음 쓰기에
    }

    func test_쓰기를_거부하는_실행은_같은_값이어도_실패로_알린다() {
        let store = MemoryStateStore()
        store.writesRefused = true
        let repo = PhoneStateRepository(store: store, creds: nil)
        var failed = 0
        repo.onWriteFailed = { failed += 1 }
        XCTAssertFalse(repo.commitLocal { _ in })
        XCTAssertEqual(failed, 1)
    }

    func test_돌봄_저장은_결과를_셋으로_알리고_실패하면_되돌린다() {
        var file = CareFile()
        file.events = [CareEvent(id: "old", kind: .note, startAt: 1, zone: "Asia/Seoul", createdAt: 1, updatedAt: 1)]
        let store = MemoryCareStore(file)
        let repo = CareRepository(store: store)
        XCTAssertEqual(repo.update { _ in }, .unchanged)
        XCTAssertEqual(repo.update { $0.events.append(CareEvent(id: "new", kind: .note, startAt: 2, zone: "Asia/Seoul", createdAt: 2, updatedAt: 2)) }, .saved)
        XCTAssertEqual(store.value.events.map(\.id), ["old", "new"])
        store.failing = true
        var failed = false
        repo.onWriteFailed = { failed = true }
        XCTAssertEqual(repo.update { $0.childNickname = "콩이" }, .failed)
        XCTAssertTrue(failed)
        XCTAssertNil(repo.care.childNickname)   // 롤백
    }
}
