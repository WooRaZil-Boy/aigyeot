import XCTest
@testable import Aigyeot

/// 돌봄 기록 1단계(계획 6장 R1~R17, 13장 표준 예제). 시험 이름은 계획 13장 그대로다.
@MainActor
final class CareTests: XCTestCase {
    var relay: FakeRelay!
    var store: MemoryStateStore!
    var creds: MemoryCredentialStore!
    var careStore: MemoryCareStore!
    var clock: Date!
    var app: AppContainer!
    let seoul = TimeZone(identifier: "Asia/Seoul")!

    static func t(_ s: String) -> Date { Rfc3339.parse(s)! }
    static func ms(_ s: String) -> Int64 { CareTime.ms(t(s)) }
    var nowMs: Int64 { CareTime.ms(clock) }

    /// role이 nil이면 차와 연결하지 않은 폰이다(U7).
    func make(role: PhoneRole? = .owner, care: CareFile = CareFile(migratedFeedings: true), state: PhoneState? = nil) {
        relay = FakeRelay()
        store = MemoryStateStore(state ?? PhoneState(introSeen: true, carId: role == nil ? nil : "c_1"))
        creds = MemoryCredentialStore(role.map { Credentials(carId: "c_1", deviceId: "d_1", token: "at1.c_1.d_1.x", role: $0) })
        careStore = MemoryCareStore(care)
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore,
                         now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
    }

    /// U61 전제: 이 연결에서 요약을 넣은 적이 있는 폰(기록에서 온 값이 없는 빈 요약도 보낸다). 다시 보내기·날 바뀜·시계 같은 R19 규칙 시험은 이 폰에서 본다.
    /// '넣은 적'은 maxSentCareAsOf다. 시험들이 쓰는 어떤 시각보다 이르게 둔다(시계 비교에 끼어들지 않게).
    func makeSummarySentBefore() {
        var s = PhoneState(introSeen: true, carId: "c_1")
        s.maxSentCareAsOf = Self.ms("2026-10-01T00:00:00+09:00")
        make(state: s)
    }

    override func setUp() async throws {
        await MainActor.run {
            clock = Self.t("2026-11-05T12:00:00+09:00")
            make()
        }
    }

    func event(_ kind: CareKind, _ start: String, end: String? = nil, endUnknown: Bool = false,
               zone: String = "Asia/Seoul", deletedAt: Int64? = nil, id: String = ULID.generate()) -> CareEvent {
        CareEvent(id: id, kind: kind, startAt: Self.ms(start), endAt: end.map(Self.ms), endUnknown: endUnknown, zone: zone,
                  createdAt: Self.ms(start), updatedAt: Self.ms(start), deletedAt: deletedAt)
    }

    /// 보낼 함의 feeding_started `at` 값들.
    var queuedFeedings: [String] {
        app.state.state.outbox.filter { $0.type == MessageType.feedingStarted }.compactMap {
            if case .string(let at)? = $0.body.objectValue?["at"] { return at } else { return nil }
        }
    }

    // MARK: 표준 예제

    func test_표준_예제를_읽고_다시_쓰면_같다() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "care-sample", withExtension: "json"))
        let original = try Data(contentsOf: url)
        let file = try CareCoding.decode(original)
        XCTAssertEqual(file.childNickname, "콩이")
        XCTAssertEqual(file.events.count, 6)
        XCTAssertEqual(file.events[1].endUnknown, true)
        XCTAssertEqual(file.events[2].amountMl, 120)
        XCTAssertEqual(file.events[4].diaper, .wet)
        XCTAssertEqual(file.events[5].deletedAt, 1793860000000)

        let written = try CareCoding.encode(file)
        XCTAssertEqual(Self.withoutNulls(try JSONSerialization.jsonObject(with: written)) as? NSDictionary,
                       Self.withoutNulls(try JSONSerialization.jsonObject(with: original)) as? NSDictionary)
        // 쓸 때는 null인 키도 쓴다(13장): 파일 키 5개, 기록마다 키 14개.
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: written) as? [String: Any])
        XCTAssertEqual(Set(obj.keys), ["version", "events", "childNickname", "sleepDiaperEnabled", "migratedFeedings"])
        for e in try XCTUnwrap(obj["events"] as? [[String: Any]]) {
            XCTAssertEqual(Set(e.keys), ["id", "kind", "startAt", "endAt", "endUnknown", "zone", "feedSide", "amountMl",
                                         "diaper", "note", "source", "createdAt", "updatedAt", "deletedAt"])
        }
        // 없는 키는 기본값, 모르는 키는 무시한다(6장).
        let sparse = try CareCoding.decode(Data(#"{"version":1,"events":[{"id":"A","kind":"NOTE","startAt":1,"zone":"Asia/Seoul","source":"PHONE","createdAt":1,"updatedAt":1,"later":true}],"x":1}"#.utf8))
        XCTAssertEqual(sparse.sleepDiaperEnabled, false)
        XCTAssertEqual(sparse.migratedFeedings, false)
        XCTAssertNil(sparse.childNickname)
        XCTAssertEqual(sparse.events.first?.endUnknown, false)
        XCTAssertNil(sparse.events.first?.endAt)
    }

    static func withoutNulls(_ v: Any) -> Any {
        if let d = v as? [String: Any] { return d.filter { !($0.value is NSNull) }.mapValues(withoutNulls) }
        if let a = v as? [Any] { return a.map(withoutNulls) }
        return v
    }

    // MARK: R1~R4 기록 규칙

    func test_진행_중_수유는_하나만() {
        app.recordCare.start(.feed, side: .left)
        clock.addTimeInterval(600)
        app.recordCare.start(.feed, side: .right)
        let running = app.care.care.events.filter { $0.kind == .feed && $0.inProgress }
        XCTAssertEqual(running.count, 1)
        XCTAssertEqual(running.first?.feedSide, .right)
        // 수유 중에 잠을 시작할 수 있다.
        app.recordCare.start(.sleep)
        XCTAssertNotNil(CareRules.inProgress(.sleep, in: app.care.care.events))
        XCTAssertNotNil(CareRules.inProgress(.feed, in: app.care.care.events))
        // 고치기·빠른 기록으로도 진행 중 수유를 하나 더 만들지 않는다.
        let extra = CareEvent(id: "X", kind: .feed, startAt: nowMs - 60_000, zone: "Asia/Seoul", createdAt: nowMs, updatedAt: nowMs)
        XCTAssertEqual(app.recordCare.saveEvent(extra), .problem(.inProgressExists))
        XCTAssertEqual(app.care.care.events.filter { $0.kind == .feed && $0.inProgress }.count, 1)
    }

    func test_새_수유가_진행_중_수유를_끝낸다() throws {
        app.recordCare.start(.feed, side: .left)
        let first = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        clock.addTimeInterval(20 * 60)
        app.recordCare.start(.feed, side: .right)
        let ended = try XCTUnwrap(app.care.care.events.first { $0.id == first.id })
        XCTAssertEqual(ended.endAt, nowMs)
        XCTAssertFalse(ended.endUnknown)
        XCTAssertEqual(app.status.ui.notice, "앞 수유를 끝냈어요")
        XCTAssertEqual(careStore.value, app.care.care)   // 끝내기와 새 시작이 한 번에 저장됐다

        // U14: 앞 수유가 이미 3시간을 넘었으면 끝 시각 모름으로 닫는다(R3를 어기는 끝 시각을 만들지 않는다).
        let second = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        clock.addTimeInterval(3 * 3600 + 1)
        app.notices.dismiss()
        app.recordCare.start(.feed, side: .left)
        let closed = try XCTUnwrap(app.care.care.events.first { $0.id == second.id })
        XCTAssertNil(closed.endAt)
        XCTAssertTrue(closed.endUnknown)
        XCTAssertFalse(closed.inProgress)
        XCTAssertEqual(app.status.ui.notice, "앞 수유를 끝냈어요 · 끝 시각 모름")
        // 3시간 정각은 넘은 것이 아니다.
        let third = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        clock.addTimeInterval(3 * 3600)
        app.recordCare.start(.feed, side: .right)
        XCTAssertEqual(app.care.care.events.first { $0.id == third.id }?.endAt, nowMs)

        // 잠금 화면 '끝내기'도 같은 규칙이고 띠는 없다.
        let fourth = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        app.notices.dismiss()
        clock.addTimeInterval(600)
        app.recordCare.end(fourth.id)
        XCTAssertEqual(app.care.care.events.first { $0.id == fourth.id }?.endAt, nowMs)
        XCTAssertNil(app.status.ui.notice)
        app.recordCare.start(.sleep)
        let sleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: app.care.care.events))
        clock.addTimeInterval(16 * 3600 + 1)
        app.recordCare.end(sleep.id)
        XCTAssertEqual(app.care.care.events.first { $0.id == sleep.id }?.endUnknown, true)
        XCTAssertNil(app.care.care.events.first { $0.id == sleep.id }?.endAt)
        XCTAssertNil(app.status.ui.notice)

        // U21: 잠을 또 시작해도 같다. 띠는 "앞 잠을 끝냈어요"(끝 모름이면 " · 끝 시각 모름").
        app.recordCare.start(.sleep)
        let firstSleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: app.care.care.events))
        clock.addTimeInterval(600)
        app.recordCare.start(.sleep)
        XCTAssertEqual(app.care.care.events.first { $0.id == firstSleep.id }?.endAt, nowMs)
        XCTAssertEqual(app.status.ui.notice, "앞 잠을 끝냈어요")
        let secondSleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: app.care.care.events))
        clock.addTimeInterval(16 * 3600 + 1)
        app.recordCare.start(.sleep)
        XCTAssertEqual(app.care.care.events.first { $0.id == secondSleep.id }?.endUnknown, true)
        XCTAssertEqual(app.status.ui.notice, "앞 잠을 끝냈어요 · 끝 시각 모름")
        app.recordCare.end(CareRules.inProgress(.sleep, in: app.care.care.events)!.id)

    }

    func test_미래_시각과_최대_길이를_넘는_기록은_저장하지_않는다() {
        let now = nowMs
        func feed(_ start: Int64, _ end: Int64?) -> CareEvent {
            CareEvent(id: ULID.generate(), kind: .feed, startAt: start, endAt: end, zone: "Asia/Seoul", createdAt: now, updatedAt: now)
        }
        // 미래: 지금 + 1분까지 허용
        XCTAssertNil(CareRules.problem(feed(now + 60_000, nil), now: now))
        XCTAssertEqual(CareRules.problem(feed(now + 60_001, nil), now: now), .future)
        XCTAssertEqual(CareRules.problem(feed(now - 60_000, now + 60_001), now: now), .future)
        XCTAssertEqual(CareProblem.future.message(for: .feed), "앞으로의 시각은 적을 수 없어요")
        XCTAssertEqual(CareProblem.endBeforeStart.message(for: .feed), "끝 시각이 시작보다 빨라요")
        XCTAssertEqual(CareProblem.tooLong.message(for: .feed), "수유는 3시간까지 적을 수 있어요")
        XCTAssertEqual(CareProblem.tooLong.message(for: .sleep), "잠은 16시간까지 적을 수 있어요")
        XCTAssertEqual(CareProblem.amount.message(for: .feed), "0~400ml로 적어 주세요")
        // 끝 ≥ 시작
        XCTAssertNil(CareRules.problem(feed(now - 1000, now - 1000), now: now))
        XCTAssertEqual(CareRules.problem(feed(now - 1000, now - 1001), now: now), .endBeforeStart)
        // 수유 3시간, 잠 16시간
        let h: Int64 = 3_600_000
        XCTAssertNil(CareRules.problem(feed(now - 3 * h, now), now: now))
        XCTAssertEqual(CareRules.problem(feed(now - 3 * h - 1, now), now: now), .tooLong)
        var sleep = feed(now - 16 * h, now); sleep.kind = .sleep
        XCTAssertNil(CareRules.problem(sleep, now: now))
        sleep.startAt -= 1
        XCTAssertEqual(CareRules.problem(sleep, now: now), .tooLong)
        // 젖병 0~400ml, 메모 200자
        var bottle = feed(now - 600_000, now); bottle.feedSide = .bottle
        bottle.amountMl = 400; XCTAssertNil(CareRules.problem(bottle, now: now))
        bottle.amountMl = 401; XCTAssertEqual(CareRules.problem(bottle, now: now), .amount)
        bottle.amountMl = -1; XCTAssertEqual(CareRules.problem(bottle, now: now), .amount)
        var note = CareEvent(id: "N", kind: .note, startAt: now, zone: "Asia/Seoul", note: String(repeating: "가", count: 200), createdAt: now, updatedAt: now)
        XCTAssertNil(CareRules.problem(note, now: now))
        note.note = String(repeating: "가", count: 201)
        XCTAssertEqual(CareRules.problem(note, now: now), .noteTooLong)
        // U24: 코드 포인트로 센다. 가족 이모지는 글자 하나로 보이지만 코드 포인트 5개다.
        note.note = String(repeating: "가", count: 199) + "👨‍👩‍👧"
        XCTAssertEqual(note.note!.count, 200)
        XCTAssertEqual(CareRules.problem(note, now: now), .noteTooLong)
        XCTAssertEqual(CareRules.noteLimited(String(repeating: "가", count: 199) + "👨‍👩‍👧").unicodeScalars.count, 200)   // 입력칸은 200에서 더 받지 않는다(U17)
        // 저장하지 않는다
        XCTAssertEqual(app.recordCare.saveEvent(feed(now + 120_000, nil)), .problem(.future))
        XCTAssertTrue(app.care.care.events.isEmpty)
        // 진행 중 수유가 3시간을 넘으면 타이머가 끝 시각을 묻는다(기록은 그대로 있다).
        app.recordCare.start(.feed, side: .left)
        clock.addTimeInterval(3 * 3600 + 1)
        let running = CareRules.inProgress(.feed, in: app.care.care.events)!
        XCTAssertTrue(CareRules.isOverLimit(running, now: nowMs))
        XCTAssertEqual(CareText.overLimitFeed, "수유가 3시간째예요. 끝내는 걸 잊으셨나요?")
        XCTAssertEqual(CareText.overLimitSleep, "잠이 16시간째예요. 끝내는 걸 잊으셨나요?")   // U36
        XCTAssertEqual([CareText.pickEnd, CareText.closeEndUnknown], ["끝 시각 고르기", "끝 시각 모름으로 닫기"])
        // '끝 시각 고르기'로 3시간 넘는 끝을 넣으면 저장하지 않는다.
        var picked = running; picked.endAt = nowMs
        XCTAssertEqual(app.recordCare.saveEvent(picked), .problem(.tooLong))
        XCTAssertTrue(app.care.care.events.first { $0.id == running.id }!.inProgress)
        // '끝 시각 모름으로 닫기'
        app.recordCare.endUnknown(running.id)
        XCTAssertEqual(app.care.care.events.first { $0.id == running.id }?.endUnknown, true)
        XCTAssertNil(app.care.care.events.first { $0.id == running.id }?.endAt)
    }

    func test_겹치는_수유는_경고하고_저장할_수_있다() {
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T10:00:00+09:00", end: "2026-11-05T10:30:00+09:00")), .saved)
        let overlapping = event(.feed, "2026-11-05T10:20:00+09:00", end: "2026-11-05T10:40:00+09:00")
        XCTAssertEqual(app.recordCare.saveEvent(overlapping), .overlap)
        XCTAssertEqual(app.care.care.events.count, 1)   // 묻기 전에는 저장하지 않는다
        XCTAssertEqual(CareSaveResult.overlap.message(for: .feed), "다른 수유와 시간이 겹쳐요")
        XCTAssertEqual(app.recordCare.saveEvent(overlapping, allowOverlap: true), .saved)   // '그래도 저장'
        XCTAssertEqual(app.care.care.events.count, 2)
        // 맞닿기만 하면 겹침이 아니다. 다른 종류와는 겹쳐도 묻지 않는다.
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T10:40:00+09:00", end: "2026-11-05T10:50:00+09:00")), .saved)
        XCTAssertEqual(app.recordCare.saveEvent(event(.sleep, "2026-11-05T10:10:00+09:00", end: "2026-11-05T10:45:00+09:00")), .saved)
        XCTAssertEqual(CareSaveResult.overlap.message(for: .sleep), "다른 잠과 시간이 겹쳐요")

        // U16 경계: 끝 모름 기록은 시작 한 점, 점 p와 [s, e)는 s ≤ p < e, 두 점은 같은 시각이면 겹친다.
        let now = nowMs
        let base = [event(.feed, "2026-11-05T08:00:00+09:00", end: "2026-11-05T08:30:00+09:00"),
                    event(.feed, "2026-11-05T09:00:00+09:00", endUnknown: true)]
        func overlaps(_ e: CareEvent, _ list: [CareEvent] = base) -> Bool { CareRules.overlaps(e, in: list, now: now) }
        XCTAssertTrue(overlaps(event(.feed, "2026-11-05T08:00:00+09:00", endUnknown: true)))    // p = s
        XCTAssertTrue(overlaps(event(.feed, "2026-11-05T08:29:00+09:00", endUnknown: true)))
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T08:30:00+09:00", endUnknown: true)))   // p = e
        XCTAssertTrue(overlaps(event(.feed, "2026-11-05T09:00:00+09:00", endUnknown: true)))    // 두 점이 같은 시각
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T09:00:01+09:00", endUnknown: true)))
        XCTAssertTrue(overlaps(event(.feed, "2026-11-05T08:50:00+09:00", end: "2026-11-05T09:10:00+09:00")))   // 구간이 점을 품음
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T09:00:01+09:00", end: "2026-11-05T09:10:00+09:00")))
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T08:40:00+09:00", end: "2026-11-05T09:00:00+09:00")))  // 끝이 점에 닿기만 함
        // 진행 중 기록은 [시작, 지금): 지금부터 시작하는 기록과는 겹치지 않는다.
        var running = event(.feed, "2026-11-05T11:00:00+09:00")
        XCTAssertTrue(running.inProgress)
        XCTAssertTrue(overlaps(event(.feed, "2026-11-05T11:59:00+09:00", endUnknown: true), [running]))
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T12:00:00+09:00", endUnknown: true), [running]))
        running.kind = .sleep
        XCTAssertFalse(overlaps(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true), [running]))   // 다른 종류
        XCTAssertFalse(CareRules.overlaps(event(.note, "2026-11-05T08:10:00+09:00"),
                                          in: [event(.note, "2026-11-05T08:10:00+09:00")], now: now))      // 메모·기저귀는 보지 않는다
    }

    // MARK: R5~R7 차로 보내기

    func test_더_늦고_24시간_안의_수유만_차로_보내고_같은_저장에서_표시를_올린다() {
        // 24시간보다 오래된 수유는 보내지 않는다.
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-04T11:59:00+09:00", endUnknown: true)), .saved)
        XCTAssertTrue(queuedFeedings.isEmpty)
        XCTAssertNil(app.state.state.lastSentFeedingAt)
        // 더 늦고 24시간 안이면 보낸다. 보낼 함과 표시는 같은 state 저장에 들어간다.
        let start = "2026-11-05T11:00:00+09:00"
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, start, endUnknown: true)), .saved)
        XCTAssertEqual(queuedFeedings.map { Rfc3339.parse($0) }, [Self.t(start)])
        XCTAssertEqual(store.value.lastSentFeedingAt, Self.ms(start))
        XCTAssertEqual(store.value.outbox.count, 1)
        XCTAssertEqual(store.value.feedingLog.first?.at, Self.t(start))   // 지금의 전송 상태 기록을 쓴다
        XCTAssertEqual(store.value.feedingLog.first?.id, store.value.outbox.first?.id)
        // state 저장에 실패하면 보낼 함도 표시도 바뀌지 않는다. 다음 계기가 메운다.
        store.failing = true
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true)), .saved)
        XCTAssertEqual(store.value.lastSentFeedingAt, Self.ms(start))
        XCTAssertEqual(app.state.state.outbox.count, 1)
        store.failing = false
        app.lifecycle.onForeground()
        XCTAssertEqual(app.state.state.lastSentFeedingAt, Self.ms("2026-11-05T11:30:00+09:00"))
        XCTAssertEqual(queuedFeedings.count, 2)
        // owner가 아니거나 연결되지 않았으면 보내지 않는다.
        for role in [PhoneRole.guardian, nil] {
            make(role: role)
            XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:50:00+09:00", endUnknown: true)), .saved)
            app.recordCare.start(.feed, side: .left)   // guardian도 적을 수 있다(U23)
            XCTAssertEqual(app.care.care.events.filter { $0.kind == .feed }.count, 2, "\(String(describing: role))")
            XCTAssertTrue(queuedFeedings.isEmpty, "\(String(describing: role))")
            XCTAssertNil(app.state.state.lastSentFeedingAt)
        }
        XCTAssertEqual(CareText.guardianNote, "보호자 기록은 이 휴대폰에만 남아요")
    }

    func test_수유_보내기는_정해진_계기에서만_일어난다() async {
        // 앱 시작: 저장된 수유가 아직 안 갔으면 시작할 때 넣는다.
        var care = CareFile(migratedFeedings: true)
        care.events = [event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)]
        make(care: care)
        XCTAssertEqual(queuedFeedings.count, 1)
        // 받기·보내기·갱신은 수유를 만들지 않는다.
        await app.syncInbox.store(app.state.creds, [Envelope(id: ULID.generate(), seq: 3, type: MessageType.feedingState, car: "c_1",
                                     from: Sender(role: .car, device: "d_9"), ts: "2026-11-05T12:00:00+09:00",
                                     body: try! JSONValue(encoding: FeedingStateBody(lastFeedingStart: "2026-11-05T11:59:00+09:00", windowStartExpected: nil)))],
                           live: true)
        await app.sendOutbox.run()
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.sent.filter { $0.type == MessageType.feedingStarted }.count, 1)
        XCTAssertTrue(queuedFeedings.isEmpty)

        // 앞으로 올 때: 기록은 저장됐는데 state 저장이 실패해 못 넣은 수유를 메운다.
        store.failing = true
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:40:00+09:00", endUnknown: true)), .saved)
        XCTAssertTrue(queuedFeedings.isEmpty)
        store.failing = false
        app.lifecycle.onForeground()
        XCTAssertEqual(queuedFeedings.map { Rfc3339.parse($0) }, [Self.t("2026-11-05T11:40:00+09:00")])
        await app.tasks.settle()

        // 연결 직후: 연결 전에 적은 수유는 연결이 끝나면 간다.
        make(role: nil)
        app.recordCare.start(.feed, side: .left)
        XCTAssertTrue(queuedFeedings.isEmpty)
        relay.pairResult = .success(PairResponse(carId: "c_1", deviceId: "d_1", deviceToken: "at1.c_1.d_1.x", role: .owner))
        _ = await app.pairAndWait(.pair("123456"))
        XCTAssertEqual(queuedFeedings.map { Rfc3339.parse($0) }, [clock])

        // 기록 저장·고치기 뒤: 시작 시각을 더 늦게 고치면 다시 보낸다.
        clock.addTimeInterval(60)
        var running = CareRules.inProgress(.feed, in: app.care.care.events)!
        running.startAt = nowMs - 10_000
        XCTAssertEqual(app.recordCare.saveEvent(running), .saved)
        XCTAssertEqual(queuedFeedings.count, 2)
    }

    func test_더_이른_수유는_보내지_않는다() throws {
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertEqual(queuedFeedings.count, 1)
        // 더 이른 수유를 적는다.
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T10:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertEqual(queuedFeedings.count, 1)
        // 보낸 수유를 더 이르게 고친다.
        var sent = try XCTUnwrap(CareRules.latestFeed(app.care.care.events))
        sent.startAt = Self.ms("2026-11-05T10:30:00+09:00")
        XCTAssertEqual(app.recordCare.saveEvent(sent), .saved)
        XCTAssertEqual(queuedFeedings.count, 1)
        XCTAssertEqual(app.state.state.lastSentFeedingAt, Self.ms("2026-11-05T11:00:00+09:00"))
    }

    // MARK: R8~R11 지우기·끊기·옮기기

    /// U40: 지운 사이 같은 종류를 새로 시작했으면 되살린 진행 중 기록은 끝 시각 모름이 된다. 차에는 다시 보내지 않는다.
    func test_되돌린_진행_중_기록은_새로_시작한_기록이_있으면_끝_시각_모름이_된다() throws {
        app.recordCare.start(.feed, side: .left)
        let first = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        app.recordCare.delete(first.id)
        clock = clock.addingTimeInterval(120)
        app.recordCare.start(.feed, side: .right)
        let second = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        let sent = queuedFeedings
        app.recordCare.undoDelete(first.id)
        let restored = try XCTUnwrap(careStore.value.events.first { $0.id == first.id })
        XCTAssertNil(restored.deletedAt)
        XCTAssertNil(restored.endAt)
        XCTAssertTrue(restored.endUnknown)
        XCTAssertEqual(CareRules.inProgress(.feed, in: app.care.care.events)?.id, second.id)   // 새 기록은 그대로 진행 중
        XCTAssertEqual(queuedFeedings, sent)
        // 겹치는 진행 중 기록이 없으면 진행 중으로 되살린다.
        app.recordCare.delete(second.id)
        app.recordCare.undoDelete(second.id)
        XCTAssertEqual(CareRules.inProgress(.feed, in: app.care.care.events)?.id, second.id)
        XCTAssertFalse(try XCTUnwrap(careStore.value.events.first { $0.id == second.id }).endUnknown)
    }

    /// R3: 시작을 지금+1분 안쪽으로 늦춘 진행 중 기록을 바로 끝내도 끝이 시작보다 앞서지 않는다.
    func test_시작을_조금_뒤로_고친_기록을_바로_끝내도_끝이_시작보다_빠르지_않다() throws {
        app.recordCare.start(.feed)
        var e = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        e.startAt = nowMs + 50_000
        XCTAssertEqual(app.recordCare.saveEvent(e), .saved)
        app.recordCare.end(e.id)
        let closed = try XCTUnwrap(careStore.value.events.first { $0.id == e.id })
        XCTAssertEqual(closed.endAt, closed.startAt)
        XCTAssertNil(CareRules.problem(closed, now: nowMs))
    }

    /// 계약 7.4: '이 휴대폰의 기록 지우기'는 내보내기 사본(임시 폴더의 CSV)도 지운다. 다른 임시 파일은 남긴다.
    func test_기록_지우기는_내보낸_CSV_사본도_지운다() throws {
        let tmp = FileManager.default.temporaryDirectory
        let copy = tmp.appendingPathComponent(CareText.exportFileName(clock))
        let other = tmp.appendingPathComponent("other-\(UUID().uuidString).txt")
        try Data("x".utf8).write(to: copy)
        try Data("x".utf8).write(to: other)
        defer { try? FileManager.default.removeItem(at: other) }
        app.clearRecords()
        XCTAssertFalse(FileManager.default.fileExists(atPath: copy.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: other.path))
    }

    /// U38 11-09 보충: 지우기 되돌리기는 저장한 뒤에 띠를 걷는다. 저장이 실패하면 띠를 남겨 남은 시간 안에 다시 누를 수 있다(지운 기록은 목록에 없어 손으로 되살릴 길이 없다).
    /// 기록 지우기(R10)는 되돌릴 기록을 지우므로 되돌리기 띠(지우기·가져오기)도 걷는다.
    func test_기록을_지우면_되돌리기_띠도_걷는다() async throws {
        XCTAssertEqual(app.recordCare.saveEvent(event(.note, "2026-11-05T11:00:00+09:00", id: "N1")), .saved)
        app.recordCare.delete("N1")
        XCTAssertEqual(app.notices.undo, .delete("N1"))
        app.clearRecords()
        XCTAssertNil(app.careTabs.undo)
        app.reconcileCareSummary.sendsCareSummary = true
        app.recordCare.undoMs = 0   // 띠 5초를 기다리지 않는다(시간 작업은 아래 await에서야 돈다)
        XCTAssertEqual(app.recordCare.importRows([feedAgo(10, id: "I1")]), ["I1"])
        XCTAssertEqual(app.notices.undo, .imported(["I1"]))
        app.clearRecords()
        XCTAssertNil(app.careTabs.undo)
        XCTAssertTrue(careStore.value.events.isEmpty)
        let queued = careSummaries.map(\.id)
        await app.recordCare.undoTask?.value   // 띠 시간이 끝나도 다시 띄우지 않고, 차로 다시 맞추지 않는다
        XCTAssertNil(app.careTabs.undo)
        XCTAssertEqual(careSummaries.map(\.id), queued)
        XCTAssertFalse(careSummaries.contains { $0.body.objectValue?["today_feed_count"] == .number(1) })   // 지운 수유가 든 요약은 없다
        XCTAssertFalse(app.state.state.outbox.contains { $0.type == MessageType.feedingStarted })
    }

    func test_지우기_되돌리기_저장이_실패하면_띠를_남겨_다시_누를_수_있다() async throws {
        XCTAssertEqual(app.recordCare.saveEvent(event(.note, "2026-11-05T11:00:00+09:00", id: "N1")), .saved)
        app.recordCare.delete("N1")
        let timer = app.recordCare.undoTask
        careStore.failing = true
        app.careTabs.undo(.delete("N1"))                   // 저장 실패
        XCTAssertEqual(app.notices.undo, .delete("N1"))    // 띠가 남는다
        XCTAssertEqual(app.careTabs.undo, .delete("N1"))   // 화면(UndoBar)은 이 값을 그대로 그린다: 따로 숨기지 않는다
        XCTAssertNotNil(careStore.value.events.first { $0.id == "N1" }?.deletedAt)
        XCTAssertFalse(timer?.isCancelled ?? true)         // 띠 시간도 그대로 흐른다
        careStore.failing = false
        app.careTabs.undo(.delete("N1"))                   // 다시 누른다(저장 성공)
        XCTAssertNil(careStore.value.events.first { $0.id == "N1" }?.deletedAt)
        XCTAssertNil(app.notices.undo)
        XCTAssertTrue(timer?.isCancelled ?? false)
    }

    func test_지운_기록은_표시하고_30일_뒤_지운다() throws {
        XCTAssertEqual(app.recordCare.saveEvent(event(.note, "2026-11-05T11:00:00+09:00", id: "N1")), .saved)
        app.recordCare.delete("N1")
        XCTAssertEqual(app.notices.undo, .delete("N1"))   // U38: 어느 길로 지워도 "지웠어요" + "되돌리기"(5초)
        let marked = try XCTUnwrap(careStore.value.events.first { $0.id == "N1" })
        XCTAssertEqual(marked.deletedAt, nowMs)   // 표시만 하고 파일에 남는다
        XCTAssertTrue(CareRules.live(app.care.care.events).isEmpty)
        app.recordCare.undoDelete("N1")                    // 되돌리기
        XCTAssertNil(careStore.value.events.first?.deletedAt)
        XCTAssertNil(app.notices.undo)
        app.recordCare.delete("N1")
        // 차에 보낸 수유를 지워도 차에는 알리지 않는다.
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true, id: "F1")), .saved)
        let outboxBefore = app.state.state.outbox
        app.recordCare.delete("F1")
        XCTAssertEqual(app.state.state.outbox, outboxBefore)

        // 30일 뒤 앱을 열 때 실제로 지운다(29일은 남긴다).
        var care = careStore.value
        care.events.append(event(.note, "2026-10-01T09:00:00+09:00", deletedAt: nowMs - 29 * 86_400_000, id: "OLD29"))
        care.events.append(event(.note, "2026-10-01T09:00:00+09:00", deletedAt: nowMs - 30 * 86_400_000 - 1, id: "OLD30"))
        care.events.append(event(.note, "2026-10-01T09:00:00+09:00", deletedAt: nowMs - 30 * 86_400_000, id: "EXACT30"))   // 정확히 30일도 지운다(안드로이드 `now - deletedAt < 30일`만 남김)
        care.events.append(event(.note, "2026-10-01T09:00:00+09:00", deletedAt: nowMs - 30 * 86_400_000 + 1, id: "UNDER30"))
        make(care: care)
        XCTAssertNotNil(app.care.care.events.first { $0.id == "OLD29" })
        XCTAssertNotNil(app.care.care.events.first { $0.id == "UNDER30" })
        XCTAssertNil(app.care.care.events.first { $0.id == "EXACT30" })
        XCTAssertNil(app.care.care.events.first { $0.id == "OLD30" })
        XCTAssertNil(careStore.value.events.first { $0.id == "OLD30" })
    }

    func test_연결을_끊어도_돌봄_기록은_남는다() async {
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        _ = app.recordCare.saveEvent(event(.note, "2026-11-05T11:10:00+09:00"))
        let before = careStore.value
        XCTAssertNotNil(app.state.state.lastSentFeedingAt)
        // 앱에서 끊기
        let message = await app.disconnect.now()
        XCTAssertNil(message)
        XCTAssertEqual(app.status.ui.notice, "연결을 끊었어요. 돌봄 기록은 이 휴대폰에 남아 있어요")   // U25 b: 탭(연결 안 됨)으로 간다
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
        XCTAssertEqual(careStore.value, before)
        XCTAssertEqual(app.care.care, before)
        XCTAssertNil(app.state.state.lastSentFeedingAt)   // state.json과 함께 지워진다
        // 새 차에 붙으면 24시간 안의 최신 수유가 다시 간다.
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "at1.c_2.d_2.x", role: .owner))
        _ = await app.pairAndWait(.pair("123456"))
        XCTAssertEqual(queuedFeedings.map { Rfc3339.parse($0) }, [Self.t("2026-11-05T11:00:00+09:00")])
        // 차 쪽에서 끊긴 경우(401)도 같다. '연결이 끊겼어요' 화면에서 '기록만 계속 쓸게요'면 탭으로 간다(U25 a).
        app.lose.lost(app.state.creds)
        XCTAssertEqual(careStore.value, before)
        XCTAssertNil(app.state.state.lastSentFeedingAt)
        XCTAssertTrue((app.appViewModel.ui.route == .gone))
        app.appViewModel.continueWithoutCar()
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
        XCTAssertEqual(app.care.care, before)
        XCTAssertEqual([CareText.reconnect, CareText.keepRecording], ["다시 연결하기", "기록만 계속 쓸게요"])
    }

    func test_이_휴대폰의_기록_지우기는_돌봄_기록도_지운다() {
        // 옮기기 표시를 못 남긴 상태로 시작한다.
        careStore = MemoryCareStore(CareFile()); careStore.failing = true
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        careStore.failing = false
        XCTAssertFalse(app.care.care.migratedFeedings)
        app.recordCare.setNickname("콩이")
        app.recordCare.setSleepDiaper(true)
        _ = app.recordCare.saveEvent(event(.note, "2026-11-05T11:10:00+09:00"))
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true))
        XCTAssertEqual(careStore.value.events.count, 2)
        app.clearRecords()
        XCTAssertTrue(app.care.care.events.isEmpty)
        XCTAssertTrue(careStore.value.events.isEmpty)
        // U18: 설정은 남기고, 지운 수유를 다시 옮겨 오지 않게 옮기기 표시를 켠다.
        XCTAssertEqual(careStore.value.childNickname, "콩이")
        XCTAssertTrue(careStore.value.sleepDiaperEnabled)
        XCTAssertTrue(careStore.value.migratedFeedings)
        XCTAssertTrue(app.state.isPaired)   // 연결은 그대로
        XCTAssertEqual(CareText.clearConfirm, "이 휴대폰의 리포트·알림·노래·돌봄 기록을 지울까요? 차와 다른 보호자에게 있는 기록은 그대로예요")
    }

    func test_지금_수유_기록을_한_번만_옮긴다() {
        let t1 = Self.t("2026-11-05T08:00:00+09:00"), t2 = Self.t("2026-11-05T10:00:00+09:00")
        var state = PhoneState(introSeen: true, carId: "c_1")
        state.feedingLog = [FeedingRecord(id: "F2", at: t2, status: .reflected), FeedingRecord(id: "F1", at: t1, status: .sent)]
        make(care: CareFile(), state: state)
        let feeds = app.care.care.events.filter { $0.kind == .feed }.sorted { $0.startAt < $1.startAt }
        XCTAssertEqual(feeds.map(\.id), ["F1", "F2"])
        XCTAssertEqual(feeds.map(\.startAt), [CareTime.ms(t1), CareTime.ms(t2)])
        XCTAssertTrue(feeds.allSatisfy { $0.endUnknown && $0.endAt == nil && $0.source == .phone && !$0.inProgress })
        XCTAssertTrue(app.care.care.migratedFeedings)
        XCTAssertTrue(careStore.value.migratedFeedings)
        XCTAssertEqual(app.state.state.feedingLog.map(\.id), ["F2", "F1"])   // 원래 기록은 그대로 둔다
        // U22: 옛 경로가 이미 보낸 수유를 다시 보내지 않게 lastSentFeedingAt을 옮긴 것 중 가장 늦은 시각으로 채운다.
        XCTAssertEqual(store.value.lastSentFeedingAt, CareTime.ms(t2))
        XCTAssertTrue(queuedFeedings.isEmpty)

        // 다시 열어도 또 옮기지 않는다.
        let care = careStore.value
        store.value.feedingLog.insert(FeedingRecord(id: "F3", at: Self.t("2026-11-05T11:00:00+09:00")), at: 0)
        make(care: care, state: store.value)
        XCTAssertNil(app.care.care.events.first { $0.id == "F3" })
        // 옮기다 끝나지 않았던 경우(표시 false): 같은 id는 건너뛴다.
        var half = care; half.migratedFeedings = false
        make(care: half, state: state)
        XCTAssertEqual(app.care.care.events.filter { $0.id == "F1" }.count, 1)

        // 순서는 state 먼저, care 다음(U22). state를 못 쓰면 옮기지 않는다.
        store = MemoryStateStore(state); store.failing = true
        careStore = MemoryCareStore(CareFile())
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertFalse(careStore.value.migratedFeedings)
        XCTAssertTrue(careStore.value.events.isEmpty)
        // care만 못 쓰면 state는 채워지고, 다음 실행이 다시 옮겨 같은 결과가 된다.
        store.failing = false
        careStore.failing = true
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertEqual(store.value.lastSentFeedingAt, CareTime.ms(t2))
        XCTAssertFalse(careStore.value.migratedFeedings)
        careStore.failing = false
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertTrue(careStore.value.migratedFeedings)
        XCTAssertEqual(store.value.lastSentFeedingAt, CareTime.ms(t2))
        XCTAssertTrue(store.value.outbox.isEmpty)
    }

    func test_처음_이전에_실패한_뒤_적은_수유는_다시_열어도_하나다() {
        var state = PhoneState(introSeen: true, carId: "c_1")
        state.feedingLog = [FeedingRecord(id: "F1", at: Self.t("2026-11-05T08:00:00+09:00"), status: .sent)]
        store = MemoryStateStore(state)
        careStore = MemoryCareStore(CareFile()); careStore.failing = true   // 처음 이전이 실패한다
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        app.sendOutbox.autoFlush = false
        XCTAssertFalse(careStore.value.migratedFeedings)
        careStore.failing = false   // 저장이 되살아난 뒤 수유를 적는다
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertTrue(careStore.value.migratedFeedings)   // 보내기 전에 이전을 끝낸다
        XCTAssertEqual(queuedFeedings.count, 1)            // 새 수유는 그대로 차에 보낸다
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertEqual(app.care.care.events.filter { $0.kind == .feed }.count, 2)   // F1 + 새 수유. 보낸 기록이 또 옮겨지지 않는다
    }

    func test_다시_열어도_내_수유의_보내기_상태를_찾는다() throws {
        clock = Self.t("2026-11-05T12:00:00+09:00").addingTimeInterval(0.123)   // 밀리초가 있는 시각
        make()
        app.recordCare.start(.feed)
        let e = try XCTUnwrap(CareRules.latestFeed(app.care.care.events))
        let id = try XCTUnwrap(app.today().ui.feedingRecord(for: e)?.id)
        // state.json을 거치면 소수 초가 사라진다.
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(ULID.generate())
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileStateStore(directory: dir).save(store.value)
        store = MemoryStateStore(FileStateStore(directory: dir).load())
        XCTAssertNotEqual(CareTime.ms(store.value.feedingLog[0].at), e.startAt)
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertEqual(app.today().ui.feedingRecord(for: e)?.id, id)
    }

    // MARK: R12~R17 보이는 값

    func test_마지막_수유는_기록과_차_값_중_늦은_것() {
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T10:00:00+09:00", endUnknown: true))
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true, id: "LATE"))
        app.recordCare.delete("LATE")   // 지운 것은 빼고 본다
        XCTAssertEqual(app.today().ui.lastFeedingStart, Self.t("2026-11-05T10:00:00+09:00"))
        var state = app.state.state
        state.feeding = FeedingState(lastStart: Self.t("2026-11-05T10:30:00+09:00"), seq: 1)
        make(care: careStore.value, state: state)
        XCTAssertEqual(app.today().ui.lastFeedingStart, Self.t("2026-11-05T10:30:00+09:00"))
        state.feeding = FeedingState(lastStart: Self.t("2026-11-05T09:00:00+09:00"), seq: 1)
        make(care: careStore.value, state: state)
        XCTAssertEqual(app.today().ui.lastFeedingStart, Self.t("2026-11-05T10:00:00+09:00"))
    }

    func test_자정을_넘는_기록은_시작한_날에_두고_합계는_나눠_센다() {
        let sleep = event(.sleep, "2026-11-05T23:00:00+09:00", end: "2026-11-06T02:00:00+09:00")
        // 다른 시간대에서 적은 기록은 그 시간대의 날짜에 둔다(서울 11-05 10:00 = LA 11-04 17:00).
        let la = event(.note, "2026-11-05T10:00:00+09:00", zone: "America/Los_Angeles")
        let all = [sleep, la]
        XCTAssertEqual(CareRules.events(onDay: "2026-11-05", in: all).map(\.id), [sleep.id])
        XCTAssertTrue(CareRules.events(onDay: "2026-11-06", in: all).isEmpty)
        XCTAssertEqual(CareRules.events(onDay: "2026-11-04", in: all).map(\.id), [la.id])
        XCTAssertEqual(CareFormat.clock(CareTime.date(la.startAt), TimeZone(identifier: la.zone)!), "오후 5:00")
        // 하루 합계는 날짜 경계로 나눈다.
        XCTAssertEqual(CareRules.sleepMs(onDay: "2026-11-05", in: all, zone: seoul), 3_600_000)
        XCTAssertEqual(CareRules.sleepMs(onDay: "2026-11-06", in: all, zone: seoul), 2 * 3_600_000)
    }

    func test_패턴은_7일_중앙값이고_5개_미만이면_숨긴다() {
        clock = Self.t("2026-11-05T12:00:00+09:00")
        var events = [
            event(.feed, "2026-10-29T23:00:00+09:00", endUnknown: true),   // 7일 밖
            event(.feed, "2026-11-04T06:00:00+09:00", endUnknown: true),   // 다음 수유까지 12시간: 빠진 기록으로 보고 뺀다
            event(.feed, "2026-11-04T18:00:00+09:00", endUnknown: true),
            event(.feed, "2026-11-04T20:00:00+09:00", end: "2026-11-04T20:20:00+09:00"),   // 2시간
            event(.feed, "2026-11-04T23:00:00+09:00", endUnknown: true),   // 3시간
            event(.feed, "2026-11-05T02:00:00+09:00", endUnknown: true),   // 3시간
            event(.feed, "2026-11-05T06:00:00+09:00", endUnknown: true),   // 4시간
        ]
        // 간격 4개(2·3·3·4시간)면 숨긴다.
        XCTAssertNil(CareRules.pattern(events, reports: [], now: nowMs, zone: seoul, sleepDiaper: true).feedIntervalMedianMs)
        events.append(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true))      // 5시간
        events.append(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true, deletedAt: nowMs))   // 지운 것은 뺀다
        events.append(event(.sleep, "2026-11-04T23:00:00+09:00", end: "2026-11-05T02:00:00+09:00"))
        events.append(event(.sleep, "2026-11-05T03:00:00+09:00", endUnknown: true))      // 길이를 모르는 기록은 합계에서 뺀다
        events.append(event(.diaper, "2026-11-05T07:00:00+09:00"))
        let p = CareRules.pattern(events, reports: [], now: nowMs, zone: seoul, sleepDiaper: true)
        XCTAssertEqual(p.feedIntervalMedianMs, 3 * 3_600_000)   // 2·3·3·4·5시간의 중앙값
        XCTAssertEqual(p.days.map(\.day), ["2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05"])
        XCTAssertEqual(p.days.map(\.feeds), [0, 0, 0, 0, 0, 4, 3])
        XCTAssertEqual(p.days[5].sleepMs, 3_600_000)
        XCTAssertEqual(p.days[6].sleepMs, 2 * 3_600_000)
        XCTAssertEqual(p.days[6].diapers, 1)
        XCTAssertEqual(p.days.map(\.sleepRecorded), [false, false, false, false, false, true, true])   // 자정을 넘은 잠은 다음 날에도 센다
        // U31: 진행 중·끝 모름 잠만 있는 날은 분모에 넣지 않는다.
        let onlyOpen = CareRules.pattern([event(.sleep, "2026-11-05T09:00:00+09:00"), event(.sleep, "2026-11-04T09:00:00+09:00", endUnknown: true)],
                                         reports: [], now: nowMs, zone: seoul, sleepDiaper: true)
        XCTAssertEqual(onlyOpen.sleepDays, 0)
        XCTAssertNil(onlyOpen.sleepMsPerDay)
        XCTAssertEqual(CareText.patternFewIntervals, "기록이 더 쌓이면 보여 드려요")
        XCTAssertEqual(CareText.patternFootnote, "직접 기록한 것만 셉니다. 빠진 기록이 있으면 실제와 다를 수 있어요")
    }

    func test_진행_중_경과는_시작_시각으로_계산한다() {
        app.recordCare.start(.feed, side: .left)
        let saves = careStore.saves
        clock.addTimeInterval(12 * 60 + 34)
        let running = CareRules.inProgress(.feed, in: app.care.care.events)!
        XCTAssertEqual(CareRules.elapsedMs(running, now: nowMs), (12 * 60 + 34) * 1000)
        XCTAssertEqual(CareFormat.timer(CareRules.elapsedMs(running, now: nowMs)), "12:34")
        XCTAssertEqual(CareText.feedingNow(running, now: nowMs), "수유 중 · 12분 · 왼쪽")
        clock.addTimeInterval(50 * 60)
        XCTAssertEqual(CareFormat.timer(CareRules.elapsedMs(running, now: nowMs)), "1:02:34")
        XCTAssertEqual(careStore.saves, saves)   // 시간이 흘러도 저장하지 않는다
    }

    func test_잠과_기저귀를_끄면_버튼과_패턴에서_숨긴다() {
        XCTAssertFalse(app.care.care.sleepDiaperEnabled)   // 기본 꺼짐(U2)
        XCTAssertEqual(CareRules.quickKinds(sleepDiaper: false), [.feed, .note])
        XCTAssertEqual(CareRules.quickKinds(sleepDiaper: true), [.feed, .note, .sleep, .diaper])
        app.recordCare.setSleepDiaper(true)
        XCTAssertTrue(careStore.value.sleepDiaperEnabled)
        let sleep = event(.sleep, "2026-11-05T09:00:00+09:00", end: "2026-11-05T10:00:00+09:00")
        XCTAssertEqual(app.recordCare.saveEvent(sleep), .saved)
        app.recordCare.setSleepDiaper(false)
        // 이미 적은 기록은 지우지 않고 타임라인에 남는다.
        XCTAssertEqual(CareRules.events(onDay: "2026-11-05", in: app.care.care.events).map(\.id), [sleep.id])
        let p = CareRules.pattern(app.care.care.events, reports: [], now: nowMs, zone: seoul, sleepDiaper: false)
        XCTAssertTrue(p.days.allSatisfy { $0.sleepMs == nil && $0.diapers == nil })
        XCTAssertEqual(CareText.sleepDiaperToggle, "잠·기저귀도 기록하기")
        XCTAssertEqual(CareText.sleepDiaperToggleNote, "다른 육아 앱에 이미 적고 있다면 꺼 두셔도 돼요")
    }

    func test_리포트에서_차_기록_줄을_만든다() throws {
        let data = try Data(contentsOf: ProtocolFixturesTests.dir.appendingPathComponent("report_summary_handoff.json"))
        let env = try ProtocolCoding.decoder.decode(Envelope.self, from: data)
        var body = try env.decodeBody(ReportSummaryBody.self)
        let report = StoredReport(messageId: env.id, seq: 1, receivedAt: clock, body: body)
        let rows = CareRules.carRows([report], zone: seoul)
        XCTAssertEqual(rows.map(\.text), ["주행 · 오후 3:00–3:49", "차에서 울음 · 오후 3:10 · 자장가 · 계속 울었어요"])
        XCTAssertEqual(rows.map(\.at), [Self.t("2026-11-05T15:00:00+09:00"), Self.t("2026-11-05T15:10:00+09:00")])
        XCTAssertEqual(rows.map(\.sessionId), [body.sessionId, body.sessionId])   // 누르면 그 리포트 상세로 간다
        // 결과별 문구, 리포트에 없는 값은 비워 둔다.
        body.timeline[0].result = .success
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: body)], zone: seoul)[1].text,
                       "차에서 울음 · 오후 3:10 · 자장가 · 그쳤어요")
        body.timeline[0].result = .undetermined
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: body)], zone: seoul)[1].text,
                       "차에서 울음 · 오후 3:10 · 자장가 · 알 수 없음")
        body.timeline[0].sound = SoundRef(method: .parentSong, label: "나비야")   // 소리는 방법 이름만(U15)
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: body)], zone: seoul)[1].text,
                       "차에서 울음 · 오후 3:10 · 부모 노래 · 알 수 없음")
        body.timeline[0].sound = SoundRef(method: .whiteNoise, label: "x")
        body.timeline[0].result = nil
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: body)], zone: seoul)[1].text,
                       "차에서 울음 · 오후 3:10 · 백색소음")
        var short = body   // U41: 같은 분에 끝난 주행
        short.startedAt = "2026-11-05T16:57:10+09:00"; short.endedAt = "2026-11-05T16:57:30+09:00"
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: short)], zone: seoul)[0].text,
                       "주행 · 오후 4:57")
        body.timeline[0].sound = nil
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: body)], zone: seoul)[1].text,
                       "차에서 울음 · 오후 3:10")
        // U19: 운전자가 소리를 요청해 시작한 에피소드는 줄을 만들지 않고 울음 수에도 넣지 않는다.
        var withRequest = try env.decodeBody(ReportSummaryBody.self)
        var request = withRequest.timeline[0]
        request.episodeId = "REQ"; request.trigger = .switchSound
        withRequest.timeline.append(request)
        let both = StoredReport(messageId: env.id, seq: 1, receivedAt: clock, body: withRequest)
        XCTAssertEqual(CareRules.carRows([both], zone: seoul).map(\.id), ["drive-\(withRequest.sessionId)", withRequest.timeline[0].episodeId])
        // 리포트에 있는 차 울음 에피소드는 패턴의 하루 울음 에피소드 수에 들어간다.
        let p = CareRules.pattern([], reports: [both], now: nowMs + 6 * 3_600_000, zone: seoul, sleepDiaper: false)
        XCTAssertEqual(p.days.last?.carEpisodes, 1)
    }

    // MARK: C6 잠금 화면

    func test_잠금_화면_표시는_진행_중_기록과_맞춘다() throws {
        app.recordCare.start(.feed, side: .left)
        app.recordCare.start(.sleep)
        let feed = try XCTUnwrap(CareRules.inProgress(.feed, in: app.care.care.events))
        let sleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: app.care.care.events))
        let wanted = CareActivityPlan.wanted(app.care.care)
        XCTAssertEqual(wanted.map(\.eventId), [feed.id, sleep.id])
        XCTAssertEqual(wanted[0].state, .init(startAt: CareTime.date(feed.startAt), side: "왼쪽"))
        XCTAssertEqual(wanted[1].state.side, nil)   // 잠은 쪽이 없다
        XCTAssertTrue(wanted[1].sleep)
        // 처음에는 둘 다 시작한다.
        XCTAssertEqual(CareActivityPlan.diff(wanted, existing: [:]).start, wanted)
        // 쪽을 바꾸면 고치고, 끝난 기록의 활동은 끝낸다.
        var changed = feed; changed.feedSide = .right
        XCTAssertEqual(app.recordCare.saveEvent(changed, allowOverlap: true), .saved)
        app.recordCare.end(sleep.id)
        let d = CareActivityPlan.diff(CareActivityPlan.wanted(app.care.care),
                                      existing: [feed.id: wanted[0].state, sleep.id: wanted[1].state, "OLD": wanted[1].state])
        XCTAssertEqual(d.start, [])
        XCTAssertEqual(d.update.map(\.state.side), ["오른쪽"])
        XCTAssertEqual(d.end, ["OLD", sleep.id].sorted())
        // 바뀐 것이 없으면 아무것도 하지 않는다.
        let same = CareActivityPlan.wanted(app.care.care)
        XCTAssertEqual(CareActivityPlan.diff(same, existing: [feed.id: same[0].state]), .init())
        // 잠금 화면 글자(7.1 notify.*)
        XCTAssertEqual([CareActivityText.feeding, CareActivityText.sleeping, CareActivityText.end], ["수유 중", "자는 중", "끝내기"])
        // 기록이 바뀔 때마다 알린다(앱은 이 알림으로 Live Activity를 맞춘다).
        var seen: [CareFile] = []
        app.recordCare.ongoing = ClosureNotice { seen.append($0) }
        app.recordCare.end(feed.id)
        XCTAssertEqual(seen.last, app.care.care)
    }

    // MARK: U29 내보내기

    func test_내_기록을_정해진_CSV로_내보낸다() throws {
        var feed = event(.feed, "2026-11-05T09:00:00+09:00", end: "2026-11-05T09:15:00+09:00", id: "F")
        feed.feedSide = .bottle; feed.amountMl = 120
        var note = event(.note, "2026-11-05T08:00:00+09:00", zone: "America/Los_Angeles", id: "N")
        note.note = "트림, \"잘\" 함\n두 번"
        var diaper = event(.diaper, "2026-11-05T10:00:00+09:00", id: "D"); diaper.diaper = .wet
        let sleep = event(.sleep, "2026-11-05T11:00:00+09:00", endUnknown: true, id: "S")
        let gone = event(.note, "2026-11-05T07:00:00+09:00", deletedAt: nowMs, id: "X")   // 지운 것은 넣지 않는다
        // U32: 초까지(밀리초 버림), 오프셋이 0이면 Z.
        var london = event(.note, "2026-11-05T12:00:00+09:00", zone: "Europe/London", id: "L")
        london.startAt += 999
        XCTAssertEqual(CareExport.iso(london.startAt, london.timeZone), "2026-11-05T03:00:00Z")
        XCTAssertEqual(CareExport.iso(london.startAt, TimeZone(identifier: "UTC")!), "2026-11-05T03:00:00Z")
        let data = CareExport.csv([diaper, gone, sleep, feed, note])
        XCTAssertEqual(Array(data.prefix(3)), [0xEF, 0xBB, 0xBF])   // UTF-8 BOM
        let text = try XCTUnwrap(String(data: data.dropFirst(3), encoding: .utf8))
        XCTAssertEqual(text, [
            "kind,start,end,end_unknown,side,amount_ml,diaper,note",
            "NOTE,2026-11-04T15:00:00-08:00,,false,,,,\"트림, \"\"잘\"\" 함\n두 번\"",   // 그 기록의 시간대, RFC 4180 따옴표
            "FEED,2026-11-05T09:00:00+09:00,2026-11-05T09:15:00+09:00,false,BOTTLE,120,,",
            "DIAPER,2026-11-05T10:00:00+09:00,,false,,,WET,",
            "SLEEP,2026-11-05T11:00:00+09:00,,true,,,,",
        ].joined(separator: "\r\n") + "\r\n")   // 줄바꿈은 CRLF, 시작 시각 오름차순
        XCTAssertEqual(CareText.exportFileName(clock, zone: seoul), "aigyeot-care-20261105.csv")
        XCTAssertEqual(CareText.export, "내 기록 내보내기")
    }

    // MARK: 보관(U44)

    func test_지우지_않은_돌봄_기록은_오래돼도_남는다() {
        var care = CareFile(migratedFeedings: true)
        care.events.append(event(.note, "2024-11-05T09:00:00+09:00", id: "Y2"))                                       // 2년 전
        care.events.append(event(.feed, "2026-08-01T09:00:00+09:00", end: "2026-08-01T09:20:00+09:00", id: "D96"))   // 리포트 보관 90일보다 오래됨
        make(care: care)        // 앱을 열 때의 정리(R8)
        app.lifecycle.purgeExpired()    // 리포트·알림 90일 정리(7.4)
        XCTAssertEqual(Set(app.care.care.events.map(\.id)), ["Y2", "D96"])
        XCTAssertEqual(careStore.value, care)
    }

    // MARK: 돌봄 요약(R18, U45)

    /// 13장 표준 예제. 복사본 없이 저장소의 shared 예제를 그대로 읽는다.
    func test_돌봄_요약은_표준_예제에서_기대_요약과_같다() throws {
        let res = ProtocolFixturesTests.dir.deletingLastPathComponent()
        var input = try CareCoding.decode(Data(contentsOf: res.appendingPathComponent("care/summary_input.json")))
        for (enabled, name) in [(true, "care_summary"), (false, "care_summary_off")] {
            input.sleepDiaperEnabled = enabled
            let body = BuildCareSummary()(input, asOf: 1_793_944_800_000, zone: seoul)
            let expected = try XCTUnwrap(JSONValue.parse(Data(contentsOf: res.appendingPathComponent("protocol/\(name).json"))).objectValue?["body"])
            XCTAssertEqual(try JSONValue.parse(ProtocolCoding.encoder.encode(body)), expected, name)
        }
    }

    /// R19: 저장·고치기·지우기·잠·기저귀 켜고 끄기 뒤에 owner만, `as_of`를 뺀 요약이 바뀌었을 때만 보낸다.
    func test_거절된_요약은_같은_내용도_다음_계기에_다시_보낸다() async {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        app.reconcileCareSummary.summaryOnly()
        relay.sendError = .http(status: 400, code: nil, retryAfter: nil)   // 중계가 거절했다(차에 닿지 않음)
        await app.sendOutbox.run()
        relay.sendError = nil
        XCTAssertTrue(careSummaries.isEmpty)
        XCTAssertNil(app.state.state.lastSentCareSummary)
        app.reconcileCareSummary.summaryOnly()   // 내용은 같다
        XCTAssertEqual(careSummaries.count, 1)
    }

    func test_1분_여유로_적은_수유도_하루_수유에_센다() {   // 안드로이드 countPerDay와 같다(R3는 지금 + 1분까지 받는다)
        let evs = [event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true), event(.feed, "2026-11-05T12:00:30+09:00", endUnknown: true)]
        let p = CareRules.pattern(evs, reports: [], now: nowMs, zone: seoul, sleepDiaper: false)
        XCTAssertEqual(p.days.last?.feeds, 2)
    }

    func test_기록의_없는_키는_기본값으로_읽는다() throws {   // 6장 '없는 키는 기본값'(안드로이드는 source만 기본값)
        let json = #"{"id":"E1","kind":"FEED","startAt":1793944800000}"#
        let e = try JSONDecoder().decode(CareEvent.self, from: Data(json.utf8))
        XCTAssertEqual(e.source, .phone)
        XCTAssertEqual(e.zone, TimeZone.current.identifier)
        XCTAssertEqual(e.createdAt, 1793944800000)
        XCTAssertEqual(e.updatedAt, 1793944800000)
        XCTAssertFalse(e.endUnknown)
    }

    func test_평균은_안드로이드처럼_반을_올린다() {
        XCTAssertEqual(CareText.avgCount(9.0 / 4), "평균 2.3회")   // printf는 2.2(짝수 쪽)였다
        XCTAssertEqual(CareText.avgCount(7.0 / 4), "평균 1.8회")
        XCTAssertEqual(CareText.avgCount(44.0 / 7), "평균 6.3회")
        XCTAssertEqual(CareText.avgCount(7), "평균 7.0회")
    }

    /// U57: 보내 본 요약은 바꾸지 않고 뒤에 새것을 하나 둔다(중계 도착 순서가 계산 순서와 같게). 계약 시험 이름 그대로.
    func test_보내는_중인_요약은_바꾸지_않고_뒤에_둔다() async {
        app.reconcileCareSummary.sendsCareSummary = true
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        let a = careSummaries.map(\.id)
        XCTAssertEqual(a.count, 1)
        var during: [String] = []
        relay.sendHook = { [unowned self] in
            relay.sendHook = nil
            XCTAssertEqual(store.value.outbox.first?.sentOnce, true)   // 보내기 전에 '보내 봄'을 저장했다
            _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true))   // 보내는 사이 바뀐다
            during = careSummaries.map(\.id)
            _ = app.recordCare.saveEvent(event(.note, "2026-11-05T11:40:00+09:00"))   // 요약에 들지 않는 메모: 뒤의 새것과 견줘 그대로 둔다
            XCTAssertEqual(careSummaries.map(\.id), during)
        }
        await app.sendOutbox.run()
        XCTAssertEqual(during.count, 2)
        XCTAssertEqual(during.first, a.first)   // 옛것은 그대로, 새것은 뒤에
        XCTAssertEqual(relay.sent.filter { $0.type == MessageType.careSummary }.map(\.id), during)   // 넣은 순서대로 간다
        XCTAssertEqual(relay.sent.last { $0.type == MessageType.careSummary }?.body.objectValue?["today_feed_count"], .number(2))
        XCTAssertTrue(careSummaries.isEmpty)
    }

    func test_실패한_요약은_바꾸지_않고_같은_id로_먼저_다시_보낸다() async {
        app.reconcileCareSummary.sendsCareSummary = true
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true))
        let a = careSummaries[0].id
        relay.sendError = .network
        await app.sendOutbox.run()
        relay.sendError = nil
        XCTAssertEqual(careSummaries.first?.attempts, 1)
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true))
        XCTAssertEqual(careSummaries.map(\.id).first, a)
        XCTAssertEqual(careSummaries.count, 2)
        clock = clock.addingTimeInterval(120)   // 백오프 뒤
        await app.sendOutbox.run()
        XCTAssertEqual(relay.sent.filter { $0.type == MessageType.careSummary }.map(\.id).first, a)
        XCTAssertEqual(sentSummaries, 2)
        XCTAssertEqual(relay.sent.last { $0.type == MessageType.careSummary }?.body.objectValue?["today_feed_count"], .number(2))
    }

    func test_보내_봄을_저장한_요약은_다시_켜도_바꾸지_않는다() async {
        app.reconcileCareSummary.sendsCareSummary = true
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true))
        let a = careSummaries[0].id
        relay.sendHook = { [unowned self] in store.failing = true }   // 보낸 뒤 결과 저장만 실패한다(응답을 잃고 죽은 것과 같다)
        await app.sendOutbox.run()
        relay.sendHook = nil
        store.failing = false
        XCTAssertEqual(store.value.outbox.first?.id, a)
        XCTAssertEqual(store.value.outbox.first?.sentOnce, true)
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock })   // 다시 켠다
        app.sendOutbox.autoFlush = false
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true))
        XCTAssertEqual(careSummaries.first?.id, a)   // 보내 본 것은 그대로 두고 같은 id로 다시 보낸다
        XCTAssertEqual(careSummaries.count, 2)
    }

    func test_보내는_중인_요약은_기록_지우기에도_남고_빈_요약이_뒤에_간다() async {
        app.reconcileCareSummary.sendsCareSummary = true
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true))
        let a = careSummaries[0].id
        // 보내는 사이 기록을 지우고, 그 요청은 시간 초과로 끝난다(중계에는 늦게 닿을 수 있다)
        relay.sendHook = { [unowned self] in relay.sendHook = nil; app.clearRecords(); relay.sendError = .network }
        await app.sendOutbox.run()
        relay.sendError = nil
        XCTAssertEqual(careSummaries.first?.id, a)   // 보내 본 요약은 남아 같은 id로 먼저 다시 간다(중계가 한 번만 처리한다)
        clock = clock.addingTimeInterval(120)
        await app.sendOutbox.run()
        let sent = relay.sent.filter { $0.type == MessageType.careSummary }
        XCTAssertEqual(sent.map(\.id).first, a)
        XCTAssertEqual(sent.count, 2)
        XCTAssertEqual(sent.last?.body.objectValue?["today_feed_count"], .number(0))   // 빈 요약이 뒤에 가서 이긴다(R10)
    }

    func test_돌봄_요약은_owner가_바뀌었을_때만_보내고_보낼_함에_하나만_둔다() async {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        var summaries: [OutboxItem] { app.state.state.outbox.filter { $0.type == MessageType.careSummary } }
        func field(_ k: String) -> JSONValue? { summaries.last?.body.objectValue?[k] }
        // 앱 시작(init)이 첫 요약을 보낼 함에 넣는다. 보내기는 앞으로 올 때의 갱신이 한다.
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock })
        app.sendOutbox.autoFlush = false
        XCTAssertEqual(summaries.count, 1)
        XCTAssertEqual(field("today_feed_count"), .number(0))
        XCTAssertTrue(relay.sent.isEmpty)

        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertEqual(summaries.count, 1)
        XCTAssertEqual(field("today_feed_count"), .number(1))
        XCTAssertEqual(field("sleep_diaper_enabled"), .bool(false))
        XCTAssertNil(app.state.state.lastSentCareSummary?.objectValue?["as_of"])   // 견줄 때는 as_of를 뺀다
        let first = summaries[0].id

        // 안 보낸 요약이 같은 날 같은 내용이면 그대로 둔다: 같은 id로 다시 보내야 응답을 잃어도 중계가 한 번만 처리한다(7.5).
        clock = clock.addingTimeInterval(60)
        XCTAssertEqual(app.recordCare.saveEvent(event(.note, "2026-11-05T11:10:00+09:00")), .saved)
        XCTAssertEqual(summaries.map(\.id), [first])
        // 보낸 뒤에는 요약에 들지 않는 메모, 시각만 지난 것은 보내지 않는다.
        await app.sendOutbox.run()
        XCTAssertTrue(summaries.isEmpty)
        clock = clock.addingTimeInterval(60)
        XCTAssertEqual(app.recordCare.saveEvent(event(.note, "2026-11-05T11:12:00+09:00")), .saved)
        XCTAssertTrue(summaries.isEmpty)

        // 바뀌면 새것 하나만 둔다.
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true, id: "F2")), .saved)
        XCTAssertEqual(summaries.count, 1)
        XCTAssertEqual(field("today_feed_count"), .number(2))
        app.recordCare.setSleepDiaper(true)
        XCTAssertEqual(summaries.count, 1)
        XCTAssertEqual(field("today_sleep_min"), .number(0))
        app.recordCare.delete("F2")
        XCTAssertEqual(field("today_feed_count"), .number(1))
        app.recordCare.undoDelete("F2")
        XCTAssertEqual(field("today_feed_count"), .number(2))
        XCTAssertEqual(summaries.count, 1)

        // state.json 저장에 실패하면 보낼 함도 표시도 바뀌지 않는다(다음 계기가 다시 본다).
        let sent = app.state.state.lastSentCareSummary
        store.failing = true
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:50:00+09:00", endUnknown: true))
        XCTAssertEqual(app.state.state.lastSentCareSummary, sent)
        XCTAssertEqual(field("today_feed_count"), .number(2))
        store.failing = false
        _ = app.recordCare.saveEvent(event(.note, "2026-11-05T11:55:00+09:00"))   // 다음 계기
        XCTAssertEqual(field("today_feed_count"), .number(3))
        XCTAssertEqual(summaries.count, 1)

        // 끊기(401)면 표시도 지우고, 다시 연결하면 연결 직후에 다시 보낸다(R9).
        app.lose.lost(app.state.creds)
        XCTAssertNil(app.state.state.lastSentCareSummary)
        XCTAssertTrue(summaries.isEmpty)
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "at1.c_2.d_2.x", role: .owner))
        _ = await app.pairAndWait(.pair("123456"))
        XCTAssertEqual(summaries.count, 1)
        XCTAssertEqual(field("today_feed_count"), .number(3))

        // guardian과 연결 안 된 폰은 보내지 않는다.
        for role in [PhoneRole.guardian, nil] {
            make(role: role)
            app.reconcileCareSummary.sendsCareSummary = true
            XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
            XCTAssertTrue(summaries.isEmpty)
            XCTAssertNil(app.state.state.lastSentCareSummary)
        }
    }

    var careSummaries: [OutboxItem] { app.state.state.outbox.filter { $0.type == MessageType.careSummary } }
    var sentSummaries: Int { relay.sent.filter { $0.type == MessageType.careSummary }.count }

    /// 지금 시각 기준 n분 전에 시작한 끝 모름 수유(기기 시간대).
    func feedAgo(_ minutes: Double, id: String = ULID.generate()) -> CareEvent {
        let start = CareTime.ms(clock) - Int64(minutes * 60_000)
        return CareEvent(id: id, kind: .feed, startAt: start, endAt: nil, endUnknown: true, zone: TimeZone.current.identifier,
                         createdAt: start, updatedAt: start)
    }

    var nextMidnight: Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        return cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: clock))!
    }

    func test_날이_바뀌면_같은_내용의_요약도_다시_보낸다() async {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        let midnight = nextMidnight
        clock = midnight.addingTimeInterval(-300)   // 23:55
        app.reconcileCareSummary.summaryOnly(); await app.sendOutbox.run()
        XCTAssertEqual(sentSummaries, 1)
        clock = midnight.addingTimeInterval(300)    // 00:05. 기록이 없어 내용은 같다
        app.reconcileCareSummary.summaryOnly()
        XCTAssertEqual(careSummaries.count, 1)       // 차는 다른 날의 '오늘' 값을 빼므로(1.4 L61) 새 날의 요약을 보낸다
        await app.sendOutbox.run()
        clock = midnight.addingTimeInterval(600)
        app.reconcileCareSummary.summaryOnly()
        XCTAssertTrue(careSummaries.isEmpty)         // 같은 날에는 다시 보내지 않는다
    }

    func test_폰_시계가_5분_넘게_앞서_보낸_요약은_보낸_값으로_치지_않는다() async {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        app.keepConnected.noteServerDate(clock.addingTimeInterval(-600))   // 폰 시계가 중계보다 10분 앞선다
        app.reconcileCareSummary.summaryOnly(); await app.sendOutbox.run()
        XCTAssertEqual(sentSummaries, 1)
        XCTAssertNil(app.state.state.lastSentCareSummary)   // 차가 미래 시각이라 버렸을 수 있다(3.3)
        app.keepConnected.noteServerDate(clock)                    // 시계를 고쳤다
        app.reconcileCareSummary.summaryOnly(); await app.sendOutbox.run()
        XCTAssertEqual(sentSummaries, 2)               // 같은 내용이어도 다시 보낸다
        app.reconcileCareSummary.summaryOnly()
        XCTAssertTrue(careSummaries.isEmpty)
    }

    func test_오래_기다린_요약은_보내기_직전에_다시_계산한다() async {
        app.reconcileCareSummary.sendsCareSummary = true
        let midnight = nextMidnight
        clock = midnight.addingTimeInterval(-3600)   // 23:00
        relay.sendError = .network
        XCTAssertEqual(app.recordCare.saveEvent(feedAgo(10)), .saved)
        await app.sendOutbox.run()                    // 오프라인이라 보낼 함에 남는다
        XCTAssertEqual(careSummaries.first?.body.objectValue?["today_feed_count"], .number(1))
        clock = midnight.addingTimeInterval(300)     // 앱을 앞에 둔 채 자정을 넘겨 통신이 돌아왔다(요약 계기는 없다)
        relay.sendError = nil
        await app.sendOutbox.run()
        let sent = relay.sent.last { $0.type == MessageType.careSummary }
        XCTAssertEqual(sent?.body.objectValue?["today_feed_count"], .number(0))   // 어제 23시의 '오늘 1번'을 보내지 않는다
    }

    func test_보내는_중인_항목은_만료_정리에서_빼고_결과를_먼저_적용한다() async {
        XCTAssertEqual(app.recordCare.saveEvent(feedAgo(5)), .saved)
        let id = try! XCTUnwrap(app.state.state.outbox.first { $0.type == MessageType.feedingStarted }?.id)
        clock = clock.addingTimeInterval(7 * 86400 - 30)   // 7일 직전에 보내기 시작
        relay.sendHook = { [unowned self] in   // 응답을 기다리는 사이 7일이 지나 앱이 앞으로 왔다
            self.clock = self.clock.addingTimeInterval(60)
            self.app.lifecycle.purgeExpired()
        }
        await app.sendOutbox.run()
        XCTAssertEqual(app.state.state.feedingLog.first { $0.id == id }?.status, .sent)
        XCTAssertNil(app.status.ui.notice)
    }

    func test_연결이_끊겨도_지우다_만_기록_지우기는_남아_이어서_지운다() {
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true, id: "OLD"))
        careStore.failing = true
        app.clearRecords()
        app.lose.lost(app.state.creds)   // 401: 연결 상태를 비운다(R9: 돌봄 기록은 연결과 따로다)
        XCTAssertNotNil(store.value.clearingCareIds)
        careStore.failing = false
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore,
                         now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertTrue(careStore.value.events.isEmpty)
        XCTAssertNil(app.state.state.clearingCareIds)
    }

    func test_돌봄_저장_실패도_떠_있는_안내를_덮지_않는다() {
        app.notices.addLine("노래 삭제를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요.")
        careStore.failing = true
        app.recordCare.setNickname("콩이")
        XCTAssertEqual(app.status.ui.notice?.components(separatedBy: "\n").count, 2)
    }

    func test_앞_기록을_끝냈다는_안내가_떠_있는_안내를_덮지_않는다() {
        app.recordCare.setSleepDiaper(true)
        app.notices.addLine("다른 안내")
        app.recordCare.start(.sleep)
        clock = clock.addingTimeInterval(600)
        app.recordCare.start(.sleep)   // 자는 중에 또 시작: 앞 잠을 끝낸다(U21)
        XCTAssertEqual(app.status.ui.notice, "다른 안내\n" + CareText.previousSleepEnded)
        clock = clock.addingTimeInterval(16 * 3600 + 1)
        app.recordCare.start(.sleep)   // 같은 종류 안내는 바꾼다(앞 잠 안내가 둘 쌓이지 않는다)
        XCTAssertEqual(app.status.ui.notice, "다른 안내\n" + CareText.previousSleepEndedUnknown)
    }

    func test_지우다_만_기록은_다시_연결한_새_차로_수유를_보내지_않는다() async {
        XCTAssertEqual(app.recordCare.saveEvent(feedAgo(5)), .saved)
        careStore.failing = true
        app.clearRecords()
        app.lose.lost(app.state.creds)
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "at1.c_2.d_2.x", role: .owner))
        _ = await app.pairAndWait(.pair("123456"))
        XCTAssertFalse(app.state.state.outbox.contains { $0.type == MessageType.feedingStarted })   // 지울 수유를 새 차로 보내지 않는다(R10)
    }

    func test_만료_정리가_지울_기록을_되살리지_않는다() {
        let a = event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true, id: "A")
        let b = event(.note, "2026-09-01T10:00:00+09:00", deletedAt: Self.ms("2026-09-01T11:00:00+09:00"), id: "B")   // 지운 지 30일 지남
        var st = PhoneState(introSeen: true, carId: "c_1")
        st.clearingCareIds = ["A", "B"]
        make(care: CareFile(events: [a, b], migratedFeedings: true), state: st)
        // 위 make는 이미 지우기를 마쳤다. 첫 돌봄 저장만 실패하는 시작을 다시 만든다.
        careStore = MemoryCareStore(CareFile(events: [a, b], migratedFeedings: true))
        careStore.failNext = 1   // 시작의 지우기 마치기만 실패, 이어지는 만료 정리 저장은 성공
        store.value.clearingCareIds = ["A", "B"]
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore,
                         now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertTrue(careStore.value.events.isEmpty)   // 만료 정리가 옛 배열로 A를 다시 넣지 않았다
        XCTAssertNil(app.state.state.clearingCareIds)
    }

    func test_다시_계산한_요약을_저장하지_못하면_옛_요약을_보내지_않는다() async {
        app.reconcileCareSummary.sendsCareSummary = true
        app.recordCare.setSleepDiaper(true)
        let sleep = event(.sleep, "2026-11-05T10:00:00+09:00", end: "2026-11-05T11:00:00+09:00", id: "S")
        XCTAssertEqual(app.recordCare.saveEvent(sleep), .saved)
        relay.sendError = .network
        await app.sendOutbox.run()                    // 잠이 담긴 요약이 남는다
        store.failing = true                         // state.json만 실패
        app.recordCare.delete("S")                       // care.json은 저장된다(요약 교체 저장은 실패)
        clock = clock.addingTimeInterval(5)
        relay.sendError = nil
        await app.sendOutbox.run()
        XCTAssertEqual(sentSummaries, 0)             // 지운 잠이 담긴 요약을 보내지 않는다
    }

    func test_보내는_사이_바뀐_요약도_보내기_직전에_다시_계산한다() async {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        let midnight = nextMidnight
        clock = midnight.addingTimeInterval(-10)     // 23:59:50
        app.reconcileCareSummary.summaryOnly()
        var n = 0
        relay.sendHook = { [unowned self] in
            n += 1
            guard n == 1 else { return }
            self.clock = midnight.addingTimeInterval(-5)            // 23:59:55에 기록해 요약이 바뀐다
            _ = self.app.recordCare.saveEvent(self.feedAgo(1))
            self.clock = midnight.addingTimeInterval(5)             // 첫 응답은 자정 뒤에 온다
        }
        await app.sendOutbox.run()
        let last = relay.sent.last { $0.type == MessageType.careSummary }
        XCTAssertEqual(last?.body.objectValue?["today_feed_count"], .number(0))   // 어제 23시 59분의 '오늘 1번'을 마지막으로 남기지 않는다
    }

    /// U57: 보내는 중인 요약은 바뀌지 않으니 그 거절은 알리고(안드로이드 afterSend와 같다), 뒤에 둔 새 요약은 따로 보낸다.
    func test_보내는_중에_바뀐_요약이_거절되면_알리고_뒤_요약은_보낸다() async {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        app.recordCare.setSleepDiaper(true)
        app.reconcileCareSummary.summaryOnly()
        var n = 0
        relay.sendHook = { [unowned self] in
            n += 1
            if n == 1 {
                _ = self.app.recordCare.saveEvent(self.event(.diaper, "2026-11-05T11:30:00+09:00"))   // 보내는 사이 요약이 바뀐다
                self.relay.sendError = .http(status: 400, code: nil, retryAfter: nil)       // 옛 것은 거절된다
            } else {
                self.relay.sendError = nil
            }
        }
        await app.sendOutbox.run()
        XCTAssertEqual(app.status.ui.notice, CareText.careSummaryDropped)
        XCTAssertEqual(sentSummaries, 1)   // 뒤에 둔 새 요약은 갔다
        XCTAssertTrue(careSummaries.isEmpty)
    }

    func test_같은_날이어도_24시간이_지나면_요약을_다시_보낸다() async {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.timeZone = { TimeZone(identifier: "America/New_York")! }   // 11/1은 25시간인 날
        app.reconcileCareSummary.sendsCareSummary = true
        clock = Self.t("2026-11-01T00:05:00-04:00")
        app.reconcileCareSummary.summaryOnly(); await app.sendOutbox.run()
        XCTAssertEqual(sentSummaries, 1)
        clock = Self.t("2026-11-01T23:30:00-05:00")   // 같은 날, 24시간 25분 뒤
        app.reconcileCareSummary.summaryOnly()
        XCTAssertEqual(careSummaries.count, 1)        // 차는 24시간 지난 요약을 쓰지 않는다(1.4 L61)
    }

    func test_시계가_앞섰을_때_만든_안_보낸_요약은_시계를_고친_뒤_줄을_막지_않는다() {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        let real: Date = clock
        clock = real.addingTimeInterval(3600)   // 시계가 한 시간 앞선 채 만들었다(보낸 적 없음)
        app.reconcileCareSummary.summaryOnly()
        clock = real                             // 시계를 고쳤다
        app.reconcileCareSummary.summaryOnly()
        XCTAssertLessThanOrEqual(try! XCTUnwrap(careSummaries.first).nextTryAt, real)   // 만든 시각을 기다림으로 이어받지 않는다
    }

    func test_이른_시각에_넣은_시드도_수유와_잠이_겹치지_않는다() {
        for (zone, at) in [("Asia/Seoul", "2026-11-06T00:30:00+09:00"), ("Asia/Seoul", "2026-11-06T07:30:00+09:00"),
                           ("America/New_York", "2026-03-08T08:30:00-04:00")] {   // 오늘이 서머타임 전환일인 경우도
        let t = Self.ms(at)
        let f = DemoSeed.make(nowMs: t, zone: TimeZone(identifier: zone)!)
        let spans = f.events.filter { $0.kind == .feed || $0.kind == .sleep }.compactMap { e in e.endAt.map { (e, e.startAt, $0) } }
        for (i, a) in spans.enumerated() {
            for b in spans[(i + 1)...] where a.1 < b.2 && b.1 < a.2 {
                XCTFail("\(at) \(a.0.kind) \(a.1) ~ \(b.0.kind) \(b.1) 겹침")
            }
        }
        }
    }

    func test_날짜_띠는_기기_시간대에_없는_날의_기록도_열_수_있다() {
        let apia = TimeZone(identifier: "Pacific/Apia")!   // 2011-12-30이 없다
        let days = CareRules.days(from: Self.t("2011-12-29T12:00:00-10:00"), through: Self.t("2011-12-31T12:00:00+14:00"), zone: apia).map(\.key)
        XCTAssertFalse(days.contains("2011-12-30"))
        let keys = CareRules.stripKeys(days, records: ["2011-12-30"])   // 서울 시간대 기록
        XCTAssertEqual(keys, ["2011-12-29", "2011-12-30", "2011-12-31"])
        XCTAssertEqual(CareRules.weekdayName("2011-12-30"), "금")
        XCTAssertEqual(CareRules.weekdayName("2026-11-07"), "토")
        // 제목도 그날이다(기기 시간대로 바꾸면 12월 31일이 됐다)
        XCTAssertEqual(CareText.dayTitle("2011-12-30", now: Self.t("2026-11-07T12:00:00+09:00"), zone: apia), "12월 30일(금)")
    }

    func test_기록_지우기는_사본을_먼저_지우고_표시를_마지막에_지운다() throws {
        let copy = FileStateStore.supportDirectory.appendingPathComponent("care.json.broken-1")
        try FileManager.default.createDirectory(at: FileStateStore.supportDirectory, withIntermediateDirectories: true)
        try Data("{}".utf8).write(to: copy)
        defer { try? FileManager.default.removeItem(at: copy) }
        _ = app.recordCare.saveEvent(event(.note, "2026-11-05T11:00:00+09:00"))
        store.saveCount = 0
        store.failFromSave = 2   // 지우기 저장은 되고, 그 뒤 상태 저장은 모두 실패(표시를 지우기 전에 죽은 것과 같다)
        app.clearRecords()
        XCTAssertNotNil(store.value.clearingCareIds)                       // 표시가 남아 다음에 다시 한다
        XCTAssertFalse(FileManager.default.fileExists(atPath: copy.path))  // 사본은 이미 지웠다
    }

    func test_메시지_429_기다림은_기록_지우기로_요약이_바뀌어도_지킨다() async {
        app.reconcileCareSummary.sendsCareSummary = true
        app.reconcileCareSummary.summaryOnly()
        relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 60)
        await app.sendOutbox.run()
        relay.sendError = nil
        clock = clock.addingTimeInterval(1)
        app.clearRecords()   // 기다리던 요약을 빼고 빈 기록의 새 요약을 넣는다
        await app.sendOutbox.run()
        XCTAssertEqual(sentSummaries, 0)   // Retry-After 안에는 보내지 않는다
    }

    func test_앞서_보낸_as_of보다_이른_안_보낸_요약은_시각이_따라잡으면_새_as_of로_보낸다() async {
        app.reconcileCareSummary.sendsCareSummary = true
        app.recordCare.setSleepDiaper(true)
        let real: Date = clock
        clock = real.addingTimeInterval(180)   // 시계가 3분 앞선 채 A를 보냈다(차가 적용)
        _ = app.recordCare.saveEvent(event(.diaper, "2026-11-05T11:30:00+09:00", id: "D"))
        await app.sendOutbox.run()
        let a = try! XCTUnwrap(relay.sent.last { $0.type == MessageType.careSummary })
        clock = real                           // 시계를 고치고 기저귀를 지웠다. B는 보내기에 실패해 남는다
        relay.sendError = .network
        app.recordCare.delete("D")
        await app.sendOutbox.run()
        relay.sendError = nil
        clock = real.addingTimeInterval(240)   // A의 as_of를 지난 뒤 다시 보낸다
        await app.sendOutbox.run()
        let b = try! XCTUnwrap(relay.sent.last { $0.type == MessageType.careSummary })
        guard case .string(let aAt)? = a.body.objectValue?["as_of"], case .string(let bAt)? = b.body.objectValue?["as_of"] else { return XCTFail() }
        XCTAssertGreaterThan(Rfc3339.parse(bAt)!, Rfc3339.parse(aAt)!)   // 차가 '이르다'고 무시하지 않는다(3.3)
        XCTAssertEqual(b.body.objectValue?["today_diaper_count"], .number(0))
    }

    func test_7일_지나_버린_돌봄_요약은_안내하고_같은_내용이어도_다시_보낸다() {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        app.reconcileCareSummary.summaryOnly()
        XCTAssertEqual(careSummaries.count, 1)
        clock = clock.addingTimeInterval(8 * 86400)
        app.lifecycle.purgeExpired()
        XCTAssertTrue(careSummaries.isEmpty)
        XCTAssertEqual(app.status.ui.notice, CareText.careSummaryDropped)   // U54
        app.reconcileCareSummary.summaryOnly()   // 내용은 같지만(기록 없음) 차에 닿지 않았다
        XCTAssertEqual(careSummaries.count, 1)
    }

    func test_시계가_뒤로_가면_같은_내용이어도_앞서_보낸_as_of를_지날_때까지_다시_보낸다() async {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        let real: Date = clock
        // 폰 시계가 3분 앞선 채 보냈다(차는 받는다). 시계를 바로잡은 뒤의 요약은 차가 '이르다'고 무시한다(3.3).
        // 5분 넘게 앞섰으면 차가 앞의 것을 버렸으니 역시 다시 보내야 한다.
        clock = real.addingTimeInterval(180)
        app.reconcileCareSummary.summaryOnly(); await app.sendOutbox.run()
        XCTAssertEqual(sentSummaries, 1)
        for t in [10.0, 120, 200] {   // 계기마다 다시 보낸다. 200초(앞서 보낸 as_of 뒤)에 보낸 것을 차가 받는다
            clock = real.addingTimeInterval(t)
            app.reconcileCareSummary.summaryOnly()
            XCTAssertEqual(careSummaries.count, 1, "\(t)")
            await app.sendOutbox.run()
        }
        XCTAssertEqual(sentSummaries, 4)
        clock = real.addingTimeInterval(260)
        app.reconcileCareSummary.summaryOnly()
        XCTAssertTrue(careSummaries.isEmpty)   // 그다음부터는 같은 내용을 보내지 않는다
    }

    /// U57: 429를 받은 요약은 보내 본 것이라 바꾸지 않는다. 기다림을 지킨 뒤 같은 id로 먼저 가고, 새 요약은 그 뒤에 간다.
    func test_429를_받은_요약은_중계에_없으니_새_요약으로_바꾸고_기다림을_지킨다() async {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        app.reconcileCareSummary.summaryOnly()
        let a = try! XCTUnwrap(careSummaries.first).id
        relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 60)
        await app.sendOutbox.run()
        let wait = try! XCTUnwrap(careSummaries.first).nextTryAt
        XCTAssertEqual(wait, clock.addingTimeInterval(60))
        relay.sendError = nil
        clock = clock.addingTimeInterval(1)
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        // 429인 요약은 중계에 없다(같은 id를 한도 검사보다 먼저 본다): '보내 봄'을 걷었으니 새 요약이 바꾸고 기다림은 이어받는다(U57, 안드로이드 untried)
        XCTAssertEqual(careSummaries.count, 1)
        XCTAssertNotEqual(careSummaries[0].id, a)
        XCTAssertEqual(careSummaries[0].nextTryAt, wait)
        await app.sendOutbox.run()
        XCTAssertTrue(relay.sent.isEmpty)   // Retry-After 안에는 아무것도 보내지 않는다
        clock = wait.addingTimeInterval(1)
        await app.sendOutbox.run()
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.careSummary, MessageType.feedingStarted])
        XCTAssertFalse(relay.sent.contains { $0.id == a })   // 옛 요약은 가지 않는다(기록을 지운 경우 지운 기록이 차에 닿지 않게)
        XCTAssertEqual(relay.sent[0].body.objectValue?["today_feed_count"], .number(1))
    }

    func test_기록이_없는_폰은_요약을_보내지_않고_지운_뒤에는_빈_요약을_보낸다() async {
        app.reconcileCareSummary.sendsCareSummary = true
        app.reconcileCareSummary.summaryOnly()
        XCTAssertTrue(careSummaries.isEmpty)                  // 기록이 없다(U61)
        var memo = event(.note, "2026-11-05T11:00:00+09:00"); memo.note = "메모"
        XCTAssertEqual(app.recordCare.saveEvent(memo), .saved)
        XCTAssertTrue(careSummaries.isEmpty)                  // 메모만 있다
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-04T09:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertTrue(careSummaries.isEmpty)                  // 옛 수유만 있다(오늘 수유 0회)
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true)), .saved)
        XCTAssertEqual(careSummaries.count, 1)                // 오늘 수유가 생겼다
        await app.sendOutbox.run()
        XCTAssertEqual(sentSummaries, 1)
        app.clearRecords()
        XCTAssertEqual(careSummaries.count, 1)                // 넣은 적이 있으니 지운 뒤 빈 요약으로 차의 옛 요약을 바꾼다(R10)
        XCTAssertEqual(careSummaries[0].body.objectValue?["today_feed_count"], .number(0))
        make()                                                // 새 연결(연결 끊기 뒤): '넣은 적'은 연결 끊기에서만 사라진다
        app.reconcileCareSummary.sendsCareSummary = true
        app.reconcileCareSummary.summaryOnly()
        XCTAssertTrue(careSummaries.isEmpty)
    }

    func test_기록_지우기가_돌봄_기록_전에_멈추면_다음_시작에_이어서_지우고_그동안_요약을_보내지_않는다() {
        app.reconcileCareSummary.sendsCareSummary = true
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true))
        XCTAssertEqual(careStore.value.events.count, 1)
        XCTAssertEqual(careSummaries.count, 1)
        careStore.failing = true   // 상태 파일을 지운 뒤 care.json을 저장하기 전에 멈춘 것과 같다
        app.clearRecords()
        XCTAssertNotNil(store.value.clearingCareIds)
        XCTAssertEqual(careStore.value.events.count, 1)
        XCTAssertTrue(careSummaries.isEmpty)   // 옛 기록의 안 보낸 요약도 뺐다
        app.reconcileCareSummary.summaryOnly()
        XCTAssertTrue(careSummaries.isEmpty)   // 지우다 만 기록으로 보내지 않는다
        // 다음 시작이 이어서 지우고 빈 기록의 요약을 넣는다
        careStore.failing = false
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore,
                         now: { [unowned self] in self.clock }, sendsCareSummary: true)
        XCTAssertTrue(careStore.value.events.isEmpty)
        XCTAssertTrue(careStore.value.migratedFeedings)
        XCTAssertNil(app.state.state.clearingCareIds)
        XCTAssertEqual(careSummaries.count, 1)
        XCTAssertEqual(careSummaries.first?.body.objectValue?["today_feed_count"], .number(0))
    }

    func test_지우다_만_기록_지우기는_그때의_기록만_지우고_그_뒤에_적은_기록은_남긴다() {
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true, id: "OLD"))
        careStore.failing = true
        app.clearRecords()   // care.json 저장 실패: 지울 id가 남는다
        XCTAssertEqual(careStore.value.events.map(\.id), ["OLD"])
        // 저장이 돌아온 뒤 새 기록을 적는다. 상태 파일 저장은 실패해 지울 id 표시를 지우지 못한다.
        careStore.failing = false
        store.failing = true
        XCTAssertEqual(app.recordCare.saveEvent(event(.note, "2026-11-05T11:40:00+09:00", id: "NEW")), .saved)
        XCTAssertEqual(careStore.value.events.map(\.id), ["NEW"])   // 같은 저장에서 지우다 만 것을 먼저 마쳤다
        XCTAssertNotNil(store.value.clearingCareIds)
        // 다음 시작이 이어서 정리해도 새 기록은 지우지 않는다
        store.failing = false
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore,
                         now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertEqual(careStore.value.events.map(\.id), ["NEW"])
        XCTAssertNil(app.state.state.clearingCareIds)
    }

    func test_앞으로_오면_날이_바뀐_돌봄_요약을_넣는다() async {
        // R19 계기 '앞으로 올 때': 저장 없이 0시를 넘겨 오늘 숫자가 0이 된 것을 잡는다.
        app.reconcileCareSummary.sendsCareSummary = true
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertEqual(careSummaries.last?.body.objectValue?["today_feed_count"], .number(1))
        clock = Self.t("2026-11-06T00:30:00+09:00")
        app.lifecycle.onForeground()
        XCTAssertEqual(careSummaries.count, 1)
        XCTAssertEqual(careSummaries.last?.body.objectValue?["today_feed_count"], .number(0))
        await app.tasks.settle()
    }

    func test_앱_시작의_옮기기는_요약을_넣기만_하고_보내지_않는다() async {
        // init 안의 저장(옮기기)이 요약을 넣어도 보내기는 앞으로 올 때의 갱신이 한다(수유와 같다).
        relay = FakeRelay()
        var state = PhoneState(introSeen: true, carId: "c_1")
        state.feedingLog = [FeedingRecord(id: "F1", at: Self.t("2026-11-05T11:00:00+09:00"), status: .sent)]   // 옮길 오늘 수유(빈 요약이 아니다, U61)
        store = MemoryStateStore(state)
        creds = MemoryCredentialStore(Credentials(carId: "c_1", deviceId: "d_1", token: "at1.c_1.d_1.x", role: .owner))
        careStore = MemoryCareStore(CareFile())   // 옮기기 전
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock })
        XCTAssertTrue(app.care.care.migratedFeedings)
        XCTAssertEqual(careSummaries.count, 1)
        await app.tasks.settle()
        XCTAssertTrue(relay.sent.isEmpty)
    }

    // MARK: 7.1 전체 문구표

    func test_첫_안내와_연결_확인과_띠_글자는_7_1_표_그대로() {
        XCTAssertEqual([CareText.introTitle, CareText.introLead, CareText.introSends, CareText.introNotSent, CareText.introRelay, CareText.introAI], [
            "아이곁",
            "차의 아이곁과 이 휴대폰을 연결해요.",
            "주고받는 것: 수유 시작 시각, 우리 노래 녹음, 수유·잠·기저귀 기록 요약(시각·횟수·잠 시간), 주행 리포트, 인계 알림 문장",
            "보내지 않는 것: 아기 울음 소리, 차 안 마이크 소리, 차의 위치",
            "모두 아이곁 중계 서버를 거쳐 오가요. 인계 알림은 Google·Apple 알림 서버도 거쳐요.",
            "차에서 아기 상황을 물으면, 답을 만들려고 수유·잠·기저귀 숫자를 AI 서버로 보내요.",
        ])   // U43
        XCTAssertEqual([CareText.connectCar, CareText.recordOnly], ["차와 연결하기", "먼저 기록만 쓸게요"])
        XCTAssertEqual([CareText.pairConfirm, CareText.pairConfirmSwitch], ["연결하기", "끊고 연결하기"])   // U49
        XCTAssertEqual(CareText.bannerClose, "닫기")   // U48
    }

    func test_리포트_목록_줄과_제목은_7_1_표_그대로() {
        // U47: report.row.title "{날짜} {구간}", report.row.sub, report.detail.title "{날짜}"
        XCTAssertEqual(CareText.reportTitle(start: Self.t("2026-11-06T02:54:00+09:00"), end: Self.t("2026-11-06T03:44:00+09:00"), zone: seoul),
                       "11월 6일(금) 오전 2:54–3:44")
        XCTAssertEqual(CareText.reportTitle(start: Self.t("2026-11-06T23:50:00+09:00"), end: Self.t("2026-11-07T00:20:00+09:00"), zone: seoul),
                       "11월 6일(금) 오후 11:50–오전 12:20")
        XCTAssertEqual(CareText.reportSub(cries: 3, simulated: false), "울음 3번")
        XCTAssertEqual(CareText.reportSub(cries: 0, simulated: false), "울음 기록 없음")
        XCTAssertEqual(CareText.reportSub(cries: 1, simulated: true), "울음 1번 · 시뮬레이션")
        XCTAssertEqual(CareText.reportSub(cries: 0, simulated: true), "울음 기록 없음 · 시뮬레이션")
    }

    func test_기록_줄과_패턴_문구는_7_1_표_그대로() {
        // log.row.*
        var feed = event(.feed, "2026-11-05T13:10:00+09:00", end: "2026-11-05T13:52:00+09:00")
        feed.feedSide = .left
        XCTAssertEqual(CareText.row(feed), "수유 · 오후 1:10–1:52 · 왼쪽")
        feed.feedSide = .bottle; feed.amountMl = 120
        XCTAssertEqual(CareText.row(feed), "수유 · 오후 1:10–1:52 · 젖병 120ml")
        XCTAssertEqual(CareText.row(event(.feed, "2026-11-05T13:10:00+09:00", endUnknown: true)), "수유 · 오후 1:10 · 끝 시각 모름")
        var open = event(.feed, "2026-11-05T13:10:00+09:00", endUnknown: true); open.feedSide = .left
        XCTAssertEqual(CareText.row(open), "수유 · 오후 1:10 · 왼쪽 · 끝 시각 모름")   // U37
        open.feedSide = .bottle; open.amountMl = 120
        XCTAssertEqual(CareText.row(open), "수유 · 오후 1:10 · 젖병 120ml · 끝 시각 모름")
        // U41: 같은 분에 끝난 기록
        var short = event(.feed, "2026-11-05T16:57:10+09:00", end: "2026-11-05T16:57:30+09:00"); short.feedSide = .left
        XCTAssertEqual(CareText.row(short), "수유 · 오후 4:57 · 왼쪽")
        XCTAssertEqual(CareText.row(event(.sleep, "2026-11-05T16:57:10+09:00", end: "2026-11-05T16:57:30+09:00")), "잠 · 오후 4:57 · 1분 미만")
        // U34
        XCTAssertEqual(CareText.todayTitle(nickname: "콩이"), "콩이의 오늘")
        XCTAssertEqual(CareText.todayTitle(nickname: nil), "오늘")
        XCTAssertEqual(CareText.row(event(.feed, "2026-11-05T13:10:00+09:00")), "수유 중 · 오후 1:10부터")
        XCTAssertEqual(CareText.row(event(.sleep, "2026-11-05T11:50:00+09:00", end: "2026-11-05T13:00:00+09:00")),
                       "잠 · 오전 11:50–오후 1:00 · 1시간 10분")
        XCTAssertEqual(CareText.row(event(.sleep, "2026-11-05T13:10:00+09:00", endUnknown: true)), "잠 · 오후 1:10 · 끝 시각 모름")
        XCTAssertEqual(CareText.row(event(.sleep, "2026-11-05T13:10:00+09:00")), "자는 중 · 오후 1:10부터")
        var diaper = event(.diaper, "2026-11-05T13:10:00+09:00"); diaper.diaper = .dry
        XCTAssertEqual(CareText.row(diaper), "기저귀 · 오후 1:10 · 깨끗함")
        var note = event(.note, "2026-11-05T13:10:00+09:00"); note.note = "트림 잘 함"
        XCTAssertEqual(CareText.row(note), "메모 · 오후 1:10 · 트림 잘 함")
        XCTAssertEqual(DiaperKind.allCases.map(CareText.diaperLabel), ["소변", "대변", "둘 다", "깨끗함"])
        // log.date.*
        XCTAssertEqual(CareText.dayTitle("2026-11-05", now: clock, zone: seoul), "오늘")
        XCTAssertEqual(CareText.dayTitle("2026-11-04", now: clock, zone: seoul), "어제")
        XCTAssertEqual(CareText.dayTitle("2026-11-03", now: clock, zone: seoul), "11월 3일(화)")
        // sheet.title.* / sheet.edit.*
        XCTAssertEqual([CareKind.feed, .sleep, .diaper, .note].map { CareText.sheetTitle($0, editing: false) }, ["수유 적기", "잠 적기", "기저귀 적기", "메모 적기"])
        XCTAssertEqual(CareText.sheetTitle(.feed, editing: true), "수유 고치기")
        // pattern.value: 평균은 그 종류 기록이 있는 날만 나눠 센다(U27).
        let days = (0..<7).map { i in
            CareDayStat(day: "d\(i)", feeds: [0, 0, 0, 0, 7, 8, 8][i], sleepMs: i == 6 ? 7 * 3_600_000 : 0, sleepRecorded: i == 6,
                        diapers: 0, carEpisodes: i == 6 ? 2 : 0)
        }
        let p = CarePattern(days: days, feedIntervalMedianMs: 170 * 60_000)
        XCTAssertEqual(p.feedDays, 3)
        XCTAssertEqual(p.feedsPerDay.map(CareText.avgCount), "평균 7.7회")
        XCTAssertEqual(CareText.basedOn(days: p.feedDays), "3일 기준")
        XCTAssertEqual(p.sleepMsPerDay.map(CareText.avgDuration), "평균 7시간")
        XCTAssertEqual(p.sleepDays, 1)
        XCTAssertNil(p.diapersPerDay)   // 기록한 날이 없다
        XCTAssertEqual(CareText.noRecords, "기록 없음")
        XCTAssertEqual(CareText.basedOn(days: p.diaperDays), "0일 기준")
        XCTAssertEqual(CareText.median(p.feedIntervalMedianMs!), "중앙값 2시간 50분")
        XCTAssertEqual(CareText.times(p.carCries), "2번")
        // U27: 길이·평균은 "방금"을 쓰지 않는다.
        XCTAssertEqual(CareFormat.length(0), "0분")
        XCTAssertEqual(CareFormat.length(59_999), "1분 미만")
        XCTAssertEqual(CareFormat.length(130 * 60_000), "2시간 10분")
        XCTAssertEqual(CareText.avgDuration(30_000), "평균 1분 미만")
        XCTAssertEqual(CareText.row(event(.sleep, "2026-11-05T13:10:00+09:00", end: "2026-11-05T13:10:00+09:00")), "잠 · 오후 1:10 · 0분")   // U41
        // about.*(U28)
        XCTAssertEqual(CareText.version("1.0.0"), "판 1.0.0")
        XCTAssertEqual(CareText.oss, "오픈소스 라이선스")
        // U63: 목록의 라이선스 전문이 앱 묶음에 모두 들어 있다(빠지면 누른 화면이 비어 보인다)
        XCTAssertFalse(OssEntry.all.isEmpty)
        for e in OssEntry.all { for l in e.licenses { XCTAssertFalse((OssEntry.text(l.file) ?? "").isEmpty, "\(e.name) \(l.file)") } }
        // export.fileName
        XCTAssertEqual(CareText.exportFileName(clock, zone: seoul), "aigyeot-care-20261105.csv")
    }

    // MARK: 형식(7장)

    func test_시각과_기간_형식() {
        let d = Self.t("2026-11-05T12:52:00+09:00")
        XCTAssertEqual(CareFormat.clock(d, seoul), "오후 12:52")
        XCTAssertEqual(CareFormat.clock(Self.t("2026-11-05T00:05:00+09:00"), seoul), "오전 12:05")
        XCTAssertEqual(CareFormat.day(d, seoul), "11월 5일(목)")
        XCTAssertEqual(CareFormat.dateTime(d, seoul), "11월 5일(목) 오후 12:52")   // 인계 알림 상세(7.1 날짜 형식 + 시각)
        XCTAssertEqual(CareFormat.duration(59_999), "방금")
        XCTAssertEqual(CareFormat.duration(60_000), "1분")
        XCTAssertEqual(CareFormat.duration(12 * 60_000), "12분")
        XCTAssertEqual(CareFormat.duration(120 * 60_000), "2시간")
        XCTAssertEqual(CareFormat.duration(130 * 60_000), "2시간 10분")
        XCTAssertEqual(CareText.ago(130 * 60_000), "2시간 10분 전")
        XCTAssertEqual(CareText.ago(30_000), "방금")
        // 인계 알림 경과도 U20 형식(분을 버리지 않는다).
        let at = "2026-11-05T09:50:00+09:00"
        XCTAssertEqual(ago(at, now: Self.t("2026-11-05T12:00:00+09:00")), "2시간 10분 전")
        XCTAssertEqual(ago(at, now: Self.t("2026-11-05T11:50:00+09:00")), "2시간 전")
        XCTAssertEqual(ago(at, now: Self.t("2026-11-05T09:50:30+09:00")), "방금")
        XCTAssertEqual(ago(at, now: Self.t("2026-11-05T10:02:00+09:00")), "12분 전")
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T13:10:00+09:00"), Self.t("2026-11-05T13:52:00+09:00"), seoul), "오후 1:10–1:52")
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T11:50:00+09:00"), Self.t("2026-11-05T12:20:00+09:00"), seoul), "오전 11:50–오후 12:20")
        // U41: 같은 분이면 시각 하나, 1초라도 분이 넘어가면 구간
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T16:57:10+09:00"), Self.t("2026-11-05T16:57:30+09:00"), seoul), "오후 4:57")
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T16:57:59+09:00"), Self.t("2026-11-05T16:58:00+09:00"), seoul), "오후 4:57–4:58")
        XCTAssertEqual(CareFormat.timer(754_000), "12:34")
        XCTAssertEqual(CareFormat.timer(3_754_000), "1:02:34")
    }
    // MARK: U50 시연 시드(안드로이드 DebugSeedTest와 같은 두 시험)

    func test_시연_시드는_U50_숫자를_낸다() {
        let t = Self.ms("2026-11-06T15:00:30+09:00")
        let m = t / 60_000 * 60_000   // 분 내림
        let f = DemoSeed.make(nowMs: t, zone: seoul, keep: CareFile(childNickname: "콩이"))
        assertU50(f, t)
        XCTAssertEqual(f.events.first { $0.startAt == m - 120 * 60_000 }?.feedSide, .both)
        XCTAssertEqual(f.childNickname, "콩이")
        // 지난 6일: 날마다 수유 7번(간격 중앙값 3시간), 잠 13시간 30분, 기저귀 6번.
        let p = CareRules.pattern(f.events, reports: [], now: t, zone: seoul, sleepDiaper: true)
        XCTAssertEqual(p.days.map(\.feeds), [7, 7, 7, 7, 7, 7, 2])
        XCTAssertEqual(p.days.prefix(6).map(\.sleepMs), Array(repeating: 810 * 60_000, count: 6))
        XCTAssertEqual(p.days.map(\.diapers), [6, 6, 6, 6, 6, 6, 2])
        XCTAssertEqual(p.feedIntervalMedianMs, 180 * 60_000)
    }

    func test_시연_시드는_언제_넣어도_규칙에_맞는다() {
        // 0시부터 30분마다: 미래 기록·같은 종류 겹침·진행 중 기록이 없다(이른 시각에는 오늘 장면이 어제로 넘어간다).
        let day0 = Self.ms("2026-11-06T00:00:00+09:00")
        for half in 0..<48 {
            let t = day0 + Int64(half) * 30 * 60_000
            let f = DemoSeed.make(nowMs: t, zone: seoul)
            for e in f.events {
                XCTAssertNil(CareRules.problem(e, now: t), "\(half) \(e.kind)")
                XCTAssertFalse(CareRules.overlaps(e, in: f.events, now: t), "\(half) \(e.kind) \(e.startAt)")
                XCTAssertFalse(e.inProgress, "\(half) \(e.kind)")
            }
            XCTAssertEqual(CareRules.latestFeed(f.events)?.startAt, t - 120 * 60_000)
            XCTAssertEqual(f.events.filter { $0.kind == .diaper }.map(\.startAt).max(), t - 60 * 60_000)
            // U50 오늘 숫자는 08:30부터 모두 맞는다. 그 전에는 6시간 전 수유가 0시 앞이고, 마지막 잠 끝(T−150)이 오늘 6시 전이라 밤중 깸에 들어간다.
            if half >= 17 { assertU50(f, t) }
        }
    }

    func test_서머타임이_바뀐_날이_지난_6일에_있어도_시드_기록이_겹치지_않는다() {
        // 뉴욕 봄(3/8 2시→3시)·가을(11/1 2시→1시), 산티아고 0시 건너뜀(9/6 24시→1시). 안드로이드 시험과 같은 경우.
        for (id, date) in [("America/New_York", "2026-03-10"), ("America/New_York", "2026-11-03"), ("America/Santiago", "2026-09-08")] {
            let zone = TimeZone(identifier: id)!
            var cal = Calendar(identifier: .gregorian)
            cal.timeZone = zone
            let p = date.split(separator: "-").map { Int($0)! }
            let t = CareTime.ms(cal.date(from: DateComponents(year: p[0], month: p[1], day: p[2], hour: 15))!)
            let f = DemoSeed.make(nowMs: t, zone: zone)
            for e in f.events {
                XCTAssertNil(CareRules.problem(e, now: t), "\(id) \(e.kind)")
                XCTAssertFalse(CareRules.overlaps(e, in: f.events, now: t), "\(id) \(e.kind) \(e.startAt)")
            }
            assertU50(f, t, zone: zone)
            // 지난 6일 모두 잠 합이 13~14시간이다(U50). 전환일도(봄 12시간 30분, 가을 14시간 30분이 되지 않게 보정)
            for d in 1...6 {
                let key = CareRules.dayKey(CareTime.ms(cal.date(byAdding: .day, value: -d, to: CareTime.date(t))!), zone)
                let total = CareRules.sleepMs(onDay: key, in: f.events, zone: zone)
                XCTAssert((780...840).contains(total / 60_000), "\(id) \(key) 잠 합 \(total / 60_000)분")
            }
        }
        // 산티아고 9/6은 0시가 없다: 00:10 잠은 안드로이드처럼 01:10에 시작한다. 낮잠은 10분씩 당겨 합이 780분이다.
        let santiago = TimeZone(identifier: "America/Santiago")!
        let f = DemoSeed.make(nowMs: Self.ms("2026-09-08T15:00:00-03:00"), zone: santiago)
        XCTAssertTrue(f.events.contains { $0.kind == .sleep && $0.startAt == Self.ms("2026-09-06T01:10:00-03:00") })
        XCTAssertEqual(CareRules.sleepMs(onDay: "2026-09-06", in: f.events, zone: santiago), 780 * 60_000)
        // 뉴욕 가을 11/1은 한 시간이 되풀이된다: 밤잠을 00:40에 시작해 합이 840분이다.
        let ny = TimeZone(identifier: "America/New_York")!
        let g = DemoSeed.make(nowMs: Self.ms("2026-11-03T15:00:00-05:00"), zone: ny)
        XCTAssertEqual(CareRules.sleepMs(onDay: "2026-11-01", in: g.events, zone: ny), 840 * 60_000)
        // 두 시간이 되풀이되는 날(남극 Troll 10/25)도 밤잠을 끝에서 7시간 전에 시작해 840분이다.
        // 두 시간이 빠지는 날(3/29)은 수유와 겹치지 않고는 13시간을 맞출 수 없어 보정 범위 밖이다(보고서 3h).
        let troll = TimeZone(identifier: "Antarctica/Troll")!
        let h = DemoSeed.make(nowMs: Self.ms("2026-10-27T15:00:00+00:00"), zone: troll)
        XCTAssertEqual(CareRules.sleepMs(onDay: "2026-10-25", in: h.events, zone: troll), 840 * 60_000)
    }

    func test_고른_날의_낮_12시는_서머타임이_바뀌는_날에도_12시다() {
        // 0시가 없는 날(산티아고)·한 시간이 되풀이되는 날(뉴욕)에 '하루 시작 + 12시간'은 13시·11시가 된다(안드로이드 onDate(day, 12, 0)).
        for (zone, day) in [("America/Santiago", "2026-09-06"), ("America/New_York", "2026-11-01"), ("Asia/Seoul", "2026-11-05")] {
            let tz = TimeZone(identifier: zone)!
            var cal = Calendar(identifier: .gregorian)
            cal.timeZone = tz
            let noon = CareTime.date(try! XCTUnwrap(CareRules.noon(day, tz)))
            XCTAssertEqual(cal.dateComponents([.hour, .minute], from: noon), DateComponents(hour: 12, minute: 0), zone)
            XCTAssertEqual(CareRules.dayKey(CareTime.ms(noon), tz), day, zone)
        }
    }

    func test_날짜_띠는_0시가_없는_날부터_시작해도_오늘까지_모두_있다() {
        // 산티아고 9/6은 01:00에 시작한다. 시각 차이로 세면 10/5가 빠졌다.
        let santiago = TimeZone(identifier: "America/Santiago")!
        let first = CareTime.date(CareRules.dayRange("2026-09-06", santiago)!.0)
        let last = CareTime.date(CareRules.dayRange("2026-10-05", santiago)!.0)
        let keys = CareRules.days(from: first, through: last, zone: santiago).map(\.key)
        XCTAssertEqual(keys.count, 30)
        XCTAssertEqual(keys.first, "2026-09-06")
        XCTAssertEqual(keys.last, "2026-10-05")
        XCTAssertEqual(Set(keys).count, 30)
    }

    func test_차가_거절한_돌봄_요약은_버리고_화면에_알린다() async {
        makeSummarySentBefore()   // U61 전제: 넣은 적이 있는 폰
        // 7.5: 400·403·409 id_conflict·413·415는 버리고 화면에 알린다.
        app.reconcileCareSummary.sendsCareSummary = true
        app.reconcileCareSummary.summaryOnly()   // 보낼 함에 요약만 둔다(수유 저장은 feeding_started도 넣는다)
        XCTAssertEqual(app.state.state.outbox.map(\.type), [MessageType.careSummary])
        relay.sendError = .http(status: 403, code: "forbidden", retryAfter: nil)
        await app.sendOutbox.run()
        XCTAssertTrue(careSummaries.isEmpty)
        XCTAssertEqual(app.status.ui.notice, CareText.careSummaryDropped)
    }

    func test_버린_메시지_안내는_서로_덮지_않는다() async {
        // U54: 수유 시작은 요약과 수유를 함께 넣는다. 둘 다 거절되면 앞 안내가 덮이지 않게 함께 보인다(7.5).
        app.reconcileCareSummary.sendsCareSummary = true
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertEqual(app.state.state.outbox.map(\.type), [MessageType.careSummary, MessageType.feedingStarted])
        relay.sendError = .http(status: 400, code: "bad_body", retryAfter: nil)
        await app.sendOutbox.run()
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(app.status.ui.notice, CareText.careSummaryDropped + "\n수유 기록을 차로 보내지 못했어요.")

        // 같은 글은 한 번만 보인다: 두 곡 삭제가 함께 거절돼도 줄은 하나다.
        var st = PhoneState(introSeen: true, carId: "c_1")
        st.songs = [SongRow(id: "a", name: "a", durationS: 1, state: .accepted, addedAt: clock),
                    SongRow(id: "b", name: "b", durationS: 1, state: .accepted, addedAt: clock)]
        make(state: st)
        app.removeSong("a")
        app.removeSong("b")
        XCTAssertEqual(app.state.state.outbox.map(\.type), [MessageType.songRemoved, MessageType.songRemoved])
        relay.sendError = .http(status: 400, code: "bad_body", retryAfter: nil)
        await app.sendOutbox.run()
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertEqual(app.status.ui.notice, "노래 삭제를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요.")
    }

    func test_0시가_없는_날의_하루_범위는_다음_날_0시에서_끝나고_패턴_창은_첫날_0시부터다() {
        // 산티아고 9/6은 0시를 건너뛴다(01:00 시작). 안드로이드 atStartOfDay와 같은 경계여야 두 앱 숫자가 같다.
        let santiago = TimeZone(identifier: "America/Santiago")!
        XCTAssertEqual(CareRules.dayRange("2026-09-06", santiago)?.0, Self.ms("2026-09-06T01:00:00-03:00"))
        XCTAssertEqual(CareRules.dayRange("2026-09-06", santiago)?.1, Self.ms("2026-09-07T00:00:00-03:00"))
        XCTAssertEqual(CareRules.dayRange("2026-09-05", santiago)?.1, Self.ms("2026-09-06T01:00:00-03:00"))
        // 오늘이 그날이면 7일 창의 첫날(8/31) 0시 반 수유도 센다.
        let feed = CareEvent(id: "F", kind: .feed, startAt: Self.ms("2026-08-31T00:30:00-04:00"), endAt: Self.ms("2026-08-31T00:50:00-04:00"),
                             zone: santiago.identifier, createdAt: 0, updatedAt: 0)
        let p = CareRules.pattern([feed], reports: [], now: Self.ms("2026-09-06T15:00:00-03:00"), zone: santiago, sleepDiaper: false)
        XCTAssertEqual(p.days.first?.day, "2026-08-31")
        XCTAssertEqual(p.days.first?.feeds, 1)
    }

    private func assertU50(_ f: CareFile, _ t: Int64, zone: TimeZone? = nil, line: UInt = #line) {
        let zone = zone ?? seoul
        let m = t / 60_000 * 60_000
        let s = BuildCareSummary()(f, asOf: t, zone: zone)
        func at(_ ms: Int64) -> String { Rfc3339.string(CareTime.date(ms), timeZone: zone) }
        let w = at(t)
        XCTAssertEqual(s.lastSleepStartAt, at(m - 210 * 60_000), w, line: line)
        XCTAssertEqual(s.lastSleepEndAt, at(m - 150 * 60_000), w, line: line)
        XCTAssertNil(s.sleepingSince, w, line: line)
        XCTAssertEqual(s.todaySleepMin, 120, w, line: line)
        XCTAssertEqual(s.nightWakeups, 1, w, line: line)
        XCTAssertEqual(s.lastDiaperAt, at(m - 60 * 60_000), w, line: line)
        XCTAssertEqual(s.todayDiaperCount, 2, w, line: line)
        XCTAssertEqual(s.todayFeedCount, 2, w, line: line)
    }
}
