import XCTest
@testable import Aigyeot

/// Domain/Rules·Repositories 시험(arch-phone-ios 9장). 화면·중계 없이 순수 함수와 저장소만 본다.
final class DomainRulesTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func test_띠_글은_같은_글을_한_번만_더하고_돌봄_안내는_바꾼다() {
        XCTAssertEqual(NoticeRules.adding("가", to: nil), "가")
        XCTAssertEqual(NoticeRules.adding("가", to: "가"), "가")
        XCTAssertEqual(NoticeRules.adding("나", to: "가"), "가\n나")
        let n = NoticeRules.replacingCareNotice(CareText.previousFeedEndedUnknown, in: "버림\n" + CareText.previousFeedEnded)
        XCTAssertEqual(n, "버림\n" + CareText.previousFeedEndedUnknown)   // 앞 돌봄 안내만 바뀌고 버림 안내는 남는다(U54)
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
        XCTAssertEqual(s.outbox.map(\.id), [tried.id, removal.id])   // 보내 본 요약은 결과를 확정할 때까지 둔다(U57)
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
        XCTAssertNil(FeedRules.latestToSend(care, s, now: t0))           // R11 이전 전
        care.migratedFeedings = true
        XCTAssertEqual(FeedRules.latestToSend(care, s, now: t0)?.id, "a")
        s.lastSentFeedingAt = now - 1000
        XCTAssertNil(FeedRules.latestToSend(care, s, now: t0))           // 더 늦지 않다(R7)
        s.lastSentFeedingAt = nil
        s.clearingCareIds = ["z"]
        XCTAssertNil(FeedRules.latestToSend(care, s, now: t0))           // 지우다 만 기록(R10)
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
        XCTAssertEqual(SummaryRules.decide(care, s, nowMs: CareTime.ms(t0), zone: zone, sending: nil), .unchanged)   // U61
        s.maxSentCareAsOf = CareTime.ms(t0) - 60_000
        guard case .enqueue(_, _, let asOf) = SummaryRules.decide(care, s, nowMs: CareTime.ms(t0) + 123, zone: zone, sending: nil) else { return XCTFail() }
        XCTAssertEqual(asOf % 1000, 0)   // as_of는 초까지
    }
}

@MainActor
final class RepositoryTests: XCTestCase {
    private let creds = Credentials(carId: "c_1", deviceId: "d_1", token: "t", role: .owner)

    func test_연결_바꾸기는_세대를_올리고_상태를_비운_저장이_성공해야_자격을_켠다() {
        let before = Credentials(carId: "c_0", deviceId: "d_0", token: "t0", role: .owner)
        let store = MemoryStateStore(PhoneState(introSeen: true, carId: "c_0", needsStateRequest: true))
        let keychain = MemoryCredentialStore(before)
        let repo = PhoneStateRepository(store: store, credentials: before)
        let secrets = CredentialRepository(store: keychain)
        store.failNext = 1
        XCTAssertEqual(repo.switchTo(.connect(creds), secrets: secrets), .stateNotSaved)
        XCTAssertEqual(repo.connection, .connected(before))   // 지난 기록을 비우지 못하면 새 자격을 켜지 않는다
        XCTAssertEqual(keychain.value, before)
        XCTAssertEqual(repo.generation, 1)   // 연결은 그대로여도 세대는 올라 옛 연결의 늦은 결과를 버린다
        keychain.failing = true
        XCTAssertEqual(repo.switchTo(.connect(creds), secrets: secrets), .secretNotSaved)
        XCTAssertEqual(repo.connection, .connected(before))
        XCTAssertFalse(repo.value.needsStateRequest)   // 지난 기록은 비웠다
        XCTAssertTrue(repo.value.introSeen)
        keychain.failing = false
        XCTAssertEqual(repo.switchTo(.connect(creds), secrets: secrets), .done)
        XCTAssertEqual(repo.connection, .connected(creds))
        XCTAssertEqual(repo.generation, 3)
        XCTAssertEqual(keychain.value, creds)
    }

    func test_GONE은_자격을_지우고_끊김으로_두며_화면을_넘기면_연결_없음이다() {
        let keychain = MemoryCredentialStore(creds)
        let repo = PhoneStateRepository(store: MemoryStateStore(PhoneState(carId: "c_1")), credentials: creds)
        XCTAssertEqual(repo.connection, .connected(creds))
        XCTAssertEqual(repo.generation, 0)
        XCTAssertEqual(repo.switchTo(.gone, secrets: CredentialRepository(store: keychain)), .done)
        XCTAssertEqual(repo.connection, .gone)
        XCTAssertNil(keychain.value)
        XCTAssertNil(repo.connection.credentials)
        repo.leaveGone()
        XCTAssertEqual(repo.connection, .none)
        XCTAssertEqual(repo.generation, 1)   // 화면을 넘겨도 세대는 그대로
    }

    func test_자격이_없는데_차가_남은_상태는_메모리에서만_비운다() {
        let store = MemoryStateStore(PhoneState(introSeen: false, carId: "c_1", clearingCareIds: ["x"]))
        let repo = PhoneStateRepository(store: store, credentials: nil)
        XCTAssertNil(repo.value.carId)
        XCTAssertTrue(repo.value.introSeen)
        XCTAssertEqual(repo.value.clearingCareIds, ["x"])   // 지우다 만 기록 지우기는 남긴다(R10)
        XCTAssertEqual(store.value.carId, "c_1")              // 저장은 다음 쓰기에
    }

    func test_쓰기를_거부하는_실행은_같은_값이어도_실패로_알린다() {
        let store = MemoryStateStore()
        store.writesRefused = true
        let repo = PhoneStateRepository(store: store, credentials: nil)
        var failed = 0
        repo.onWriteFailed = { failed += 1 }
        XCTAssertFalse(repo.update { _ in })
        XCTAssertEqual(failed, 1)
    }

    func test_돌봄_저장은_지우다_만_id를_앞뒤로_빼고_결과를_셋으로_알린다() {
        var file = CareFile()
        file.events = [CareEvent(id: "old", kind: .note, startAt: 1, zone: "Asia/Seoul", createdAt: 1, updatedAt: 1)]
        let store = MemoryCareStore(file)
        let repo = CareRepository(store: store)
        XCTAssertEqual(repo.update(wiping: nil) { _ in }, .unchanged)
        XCTAssertEqual(repo.update(wiping: ["old"]) { f in f.events = file.events + [CareEvent(id: "new", kind: .note, startAt: 2, zone: "Asia/Seoul", createdAt: 2, updatedAt: 2)] }, .saved)
        XCTAssertEqual(store.value.events.map(\.id), ["new"])   // 옛 배열로 다시 넣어도 뺀다
        XCTAssertTrue(store.value.migratedFeedings)
        store.failing = true
        var failed = false
        repo.onWriteFailed = { failed = true }
        XCTAssertEqual(repo.update(wiping: nil) { $0.childNickname = "콩이" }, .failed)
        XCTAssertTrue(failed)
        XCTAssertNil(repo.value.childNickname)   // 롤백
    }
}
