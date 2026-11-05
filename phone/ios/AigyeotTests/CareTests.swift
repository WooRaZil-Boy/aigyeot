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
    var model: AppModel!
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
        model = AppModel(relay: relay, store: store, credentials: creds, careStore: careStore,
                         now: { [unowned self] in self.clock })
        model.autoFlush = false
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
        model.state.outbox.filter { $0.type == MessageType.feedingStarted }.compactMap {
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
        model.startFeeding(side: .left)
        clock.addTimeInterval(600)
        model.startFeeding(side: .right)
        let running = model.care.events.filter { $0.kind == .feed && $0.inProgress }
        XCTAssertEqual(running.count, 1)
        XCTAssertEqual(running.first?.feedSide, .right)
        // 수유 중에 잠을 시작할 수 있다.
        model.startSleep()
        XCTAssertNotNil(CareRules.inProgress(.sleep, in: model.care.events))
        XCTAssertNotNil(CareRules.inProgress(.feed, in: model.care.events))
        // 고치기·빠른 기록으로도 진행 중 수유를 하나 더 만들지 않는다.
        let extra = CareEvent(id: "X", kind: .feed, startAt: nowMs - 60_000, zone: "Asia/Seoul", createdAt: nowMs, updatedAt: nowMs)
        XCTAssertEqual(model.saveEvent(extra), .problem(.inProgressExists))
        XCTAssertEqual(model.care.events.filter { $0.kind == .feed && $0.inProgress }.count, 1)
    }

    func test_새_수유가_진행_중_수유를_끝낸다() throws {
        model.startFeeding(side: .left)
        let first = try XCTUnwrap(CareRules.inProgress(.feed, in: model.care.events))
        clock.addTimeInterval(20 * 60)
        model.startFeeding(side: .right)
        let ended = try XCTUnwrap(model.care.events.first { $0.id == first.id })
        XCTAssertEqual(ended.endAt, nowMs)
        XCTAssertFalse(ended.endUnknown)
        XCTAssertEqual(model.notice, "앞 수유를 끝냈어요")
        XCTAssertEqual(careStore.value, model.care)   // 끝내기와 새 시작이 한 번에 저장됐다

        // U14: 앞 수유가 이미 3시간을 넘었으면 끝 시각 모름으로 닫는다(R3를 어기는 끝 시각을 만들지 않는다).
        let second = try XCTUnwrap(CareRules.inProgress(.feed, in: model.care.events))
        clock.addTimeInterval(3 * 3600 + 1)
        model.notice = nil
        model.startFeeding(side: .left)
        let closed = try XCTUnwrap(model.care.events.first { $0.id == second.id })
        XCTAssertNil(closed.endAt)
        XCTAssertTrue(closed.endUnknown)
        XCTAssertFalse(closed.inProgress)
        XCTAssertEqual(model.notice, "앞 수유를 끝냈어요 · 끝 시각 모름")
        // 3시간 정각은 넘은 것이 아니다.
        let third = try XCTUnwrap(CareRules.inProgress(.feed, in: model.care.events))
        clock.addTimeInterval(3 * 3600)
        model.startFeeding(side: .right)
        XCTAssertEqual(model.care.events.first { $0.id == third.id }?.endAt, nowMs)

        // 잠금 화면 '끝내기'도 같은 규칙이고 띠는 없다.
        let fourth = try XCTUnwrap(CareRules.inProgress(.feed, in: model.care.events))
        model.notice = nil
        clock.addTimeInterval(600)
        model.endNow(fourth.id)
        XCTAssertEqual(model.care.events.first { $0.id == fourth.id }?.endAt, nowMs)
        XCTAssertNil(model.notice)
        model.startSleep()
        let sleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: model.care.events))
        clock.addTimeInterval(16 * 3600 + 1)
        model.endNow(sleep.id)
        XCTAssertEqual(model.care.events.first { $0.id == sleep.id }?.endUnknown, true)
        XCTAssertNil(model.care.events.first { $0.id == sleep.id }?.endAt)
        XCTAssertNil(model.notice)

        // U21: 잠을 또 시작해도 같다. 띠는 "앞 잠을 끝냈어요"(끝 모름이면 " · 끝 시각 모름").
        model.startSleep()
        let firstSleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: model.care.events))
        clock.addTimeInterval(600)
        model.startSleep()
        XCTAssertEqual(model.care.events.first { $0.id == firstSleep.id }?.endAt, nowMs)
        XCTAssertEqual(model.notice, "앞 잠을 끝냈어요")
        let secondSleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: model.care.events))
        clock.addTimeInterval(16 * 3600 + 1)
        model.startSleep()
        XCTAssertEqual(model.care.events.first { $0.id == secondSleep.id }?.endUnknown, true)
        XCTAssertEqual(model.notice, "앞 잠을 끝냈어요 · 끝 시각 모름")
        model.endNow(CareRules.inProgress(.sleep, in: model.care.events)!.id)

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
        XCTAssertEqual(model.saveEvent(feed(now + 120_000, nil)), .problem(.future))
        XCTAssertTrue(model.care.events.isEmpty)
        // 진행 중 수유가 3시간을 넘으면 타이머가 끝 시각을 묻는다(기록은 그대로 있다).
        model.startFeeding(side: .left)
        clock.addTimeInterval(3 * 3600 + 1)
        let running = CareRules.inProgress(.feed, in: model.care.events)!
        XCTAssertTrue(CareRules.isOverLimit(running, now: nowMs))
        XCTAssertEqual(CareText.overLimitFeed, "수유가 3시간째예요. 끝내는 걸 잊으셨나요?")
        XCTAssertEqual(CareText.overLimitSleep, "잠이 16시간째예요. 끝내는 걸 잊으셨나요?")   // U36
        XCTAssertEqual([CareText.pickEnd, CareText.closeEndUnknown], ["끝 시각 고르기", "끝 시각 모름으로 닫기"])
        // '끝 시각 고르기'로 3시간 넘는 끝을 넣으면 저장하지 않는다.
        var picked = running; picked.endAt = nowMs
        XCTAssertEqual(model.saveEvent(picked), .problem(.tooLong))
        XCTAssertTrue(model.care.events.first { $0.id == running.id }!.inProgress)
        // '끝 시각 모름으로 닫기'
        model.closeEndUnknown(running.id)
        XCTAssertEqual(model.care.events.first { $0.id == running.id }?.endUnknown, true)
        XCTAssertNil(model.care.events.first { $0.id == running.id }?.endAt)
    }

    func test_겹치는_수유는_경고하고_저장할_수_있다() {
        XCTAssertEqual(model.saveEvent(event(.feed, "2026-11-05T10:00:00+09:00", end: "2026-11-05T10:30:00+09:00")), .saved)
        let overlapping = event(.feed, "2026-11-05T10:20:00+09:00", end: "2026-11-05T10:40:00+09:00")
        XCTAssertEqual(model.saveEvent(overlapping), .overlap)
        XCTAssertEqual(model.care.events.count, 1)   // 묻기 전에는 저장하지 않는다
        XCTAssertEqual(CareSaveResult.overlap.message(for: .feed), "다른 수유와 시간이 겹쳐요")
        XCTAssertEqual(model.saveEvent(overlapping, allowOverlap: true), .saved)   // '그래도 저장'
        XCTAssertEqual(model.care.events.count, 2)
        // 맞닿기만 하면 겹침이 아니다. 다른 종류와는 겹쳐도 묻지 않는다.
        XCTAssertEqual(model.saveEvent(event(.feed, "2026-11-05T10:40:00+09:00", end: "2026-11-05T10:50:00+09:00")), .saved)
        XCTAssertEqual(model.saveEvent(event(.sleep, "2026-11-05T10:10:00+09:00", end: "2026-11-05T10:45:00+09:00")), .saved)
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
        XCTAssertEqual(model.saveEvent(event(.feed, "2026-11-04T11:59:00+09:00", endUnknown: true)), .saved)
        XCTAssertTrue(queuedFeedings.isEmpty)
        XCTAssertNil(model.state.lastSentFeedingAt)
        // 더 늦고 24시간 안이면 보낸다. 보낼 함과 표시는 같은 state 저장에 들어간다.
        let start = "2026-11-05T11:00:00+09:00"
        XCTAssertEqual(model.saveEvent(event(.feed, start, endUnknown: true)), .saved)
        XCTAssertEqual(queuedFeedings.map { Rfc3339.parse($0) }, [Self.t(start)])
        XCTAssertEqual(store.value.lastSentFeedingAt, Self.ms(start))
        XCTAssertEqual(store.value.outbox.count, 1)
        XCTAssertEqual(store.value.feedingLog.first?.at, Self.t(start))   // 지금의 전송 상태 기록을 쓴다
        XCTAssertEqual(store.value.feedingLog.first?.id, store.value.outbox.first?.id)
        // state 저장에 실패하면 보낼 함도 표시도 바뀌지 않는다. 다음 계기가 메운다.
        store.failing = true
        XCTAssertEqual(model.saveEvent(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true)), .saved)
        XCTAssertEqual(store.value.lastSentFeedingAt, Self.ms(start))
        XCTAssertEqual(model.state.outbox.count, 1)
        store.failing = false
        model.didBecomeActive()
        XCTAssertEqual(model.state.lastSentFeedingAt, Self.ms("2026-11-05T11:30:00+09:00"))
        XCTAssertEqual(queuedFeedings.count, 2)
        // owner가 아니거나 연결되지 않았으면 보내지 않는다.
        for role in [PhoneRole.guardian, nil] {
            make(role: role)
            XCTAssertEqual(model.saveEvent(event(.feed, "2026-11-05T11:50:00+09:00", endUnknown: true)), .saved)
            model.startFeeding(side: .left)   // guardian도 적을 수 있다(U23)
            XCTAssertEqual(model.care.events.filter { $0.kind == .feed }.count, 2, "\(String(describing: role))")
            XCTAssertTrue(queuedFeedings.isEmpty, "\(String(describing: role))")
            XCTAssertNil(model.state.lastSentFeedingAt)
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
        await model.ingest([Envelope(id: ULID.generate(), seq: 3, type: MessageType.feedingState, car: "c_1",
                                     from: Sender(role: .car, device: "d_9"), ts: "2026-11-05T12:00:00+09:00",
                                     body: try! JSONValue(encoding: FeedingStateBody(lastFeedingStart: "2026-11-05T11:59:00+09:00", windowStartExpected: nil)))],
                           live: true)
        await model.flushOutbox()
        await model.refresh()
        XCTAssertEqual(relay.sent.filter { $0.type == MessageType.feedingStarted }.count, 1)
        XCTAssertTrue(queuedFeedings.isEmpty)

        // 앞으로 올 때: 기록은 저장됐는데 state 저장이 실패해 못 넣은 수유를 메운다.
        store.failing = true
        XCTAssertEqual(model.saveEvent(event(.feed, "2026-11-05T11:40:00+09:00", endUnknown: true)), .saved)
        XCTAssertTrue(queuedFeedings.isEmpty)
        store.failing = false
        model.didBecomeActive()
        XCTAssertEqual(queuedFeedings.map { Rfc3339.parse($0) }, [Self.t("2026-11-05T11:40:00+09:00")])
        await model.settle()

        // 연결 직후: 연결 전에 적은 수유는 연결이 끝나면 간다.
        make(role: nil)
        model.startFeeding(side: .left)
        XCTAssertTrue(queuedFeedings.isEmpty)
        relay.pairResult = .success(PairResponse(carId: "c_1", deviceId: "d_1", deviceToken: "at1.c_1.d_1.x", role: .owner))
        _ = await model.pair(.pair("123456"))
        XCTAssertEqual(queuedFeedings.map { Rfc3339.parse($0) }, [clock])

        // 기록 저장·고치기 뒤: 시작 시각을 더 늦게 고치면 다시 보낸다.
        clock.addTimeInterval(60)
        var running = CareRules.inProgress(.feed, in: model.care.events)!
        running.startAt = nowMs - 10_000
        XCTAssertEqual(model.saveEvent(running), .saved)
        XCTAssertEqual(queuedFeedings.count, 2)
    }

    func test_더_이른_수유는_보내지_않는다() throws {
        XCTAssertEqual(model.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertEqual(queuedFeedings.count, 1)
        // 더 이른 수유를 적는다.
        XCTAssertEqual(model.saveEvent(event(.feed, "2026-11-05T10:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertEqual(queuedFeedings.count, 1)
        // 보낸 수유를 더 이르게 고친다.
        var sent = try XCTUnwrap(CareRules.latestFeed(model.care.events))
        sent.startAt = Self.ms("2026-11-05T10:30:00+09:00")
        XCTAssertEqual(model.saveEvent(sent), .saved)
        XCTAssertEqual(queuedFeedings.count, 1)
        XCTAssertEqual(model.state.lastSentFeedingAt, Self.ms("2026-11-05T11:00:00+09:00"))
    }

    // MARK: R8~R11 지우기·끊기·옮기기

    /// U40: 지운 사이 같은 종류를 새로 시작했으면 되살린 진행 중 기록은 끝 시각 모름이 된다. 차에는 다시 보내지 않는다.
    func test_되돌린_진행_중_기록은_새로_시작한_기록이_있으면_끝_시각_모름이_된다() throws {
        model.startFeeding(side: .left)
        let first = try XCTUnwrap(CareRules.inProgress(.feed, in: model.care.events))
        model.deleteEvent(first.id)
        clock = clock.addingTimeInterval(120)
        model.startFeeding(side: .right)
        let second = try XCTUnwrap(CareRules.inProgress(.feed, in: model.care.events))
        let sent = queuedFeedings
        model.undoDelete(first.id)
        let restored = try XCTUnwrap(careStore.value.events.first { $0.id == first.id })
        XCTAssertNil(restored.deletedAt)
        XCTAssertNil(restored.endAt)
        XCTAssertTrue(restored.endUnknown)
        XCTAssertEqual(CareRules.inProgress(.feed, in: model.care.events)?.id, second.id)   // 새 기록은 그대로 진행 중
        XCTAssertEqual(queuedFeedings, sent)
        // 겹치는 진행 중 기록이 없으면 진행 중으로 되살린다.
        model.deleteEvent(second.id)
        model.undoDelete(second.id)
        XCTAssertEqual(CareRules.inProgress(.feed, in: model.care.events)?.id, second.id)
        XCTAssertFalse(try XCTUnwrap(careStore.value.events.first { $0.id == second.id }).endUnknown)
    }

    /// R3: 시작을 지금+1분 안쪽으로 늦춘 진행 중 기록을 바로 끝내도 끝이 시작보다 앞서지 않는다.
    func test_시작을_조금_뒤로_고친_기록을_바로_끝내도_끝이_시작보다_빠르지_않다() throws {
        model.startFeeding()
        var e = try XCTUnwrap(CareRules.inProgress(.feed, in: model.care.events))
        e.startAt = nowMs + 50_000
        XCTAssertEqual(model.saveEvent(e), .saved)
        model.endNow(e.id)
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
        model.clearLocalRecords()
        XCTAssertFalse(FileManager.default.fileExists(atPath: copy.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: other.path))
    }

    func test_지운_기록은_표시하고_30일_뒤_지운다() throws {
        XCTAssertEqual(model.saveEvent(event(.note, "2026-11-05T11:00:00+09:00", id: "N1")), .saved)
        model.deleteEvent("N1")
        XCTAssertEqual(model.recentlyDeleted, "N1")   // U38: 어느 길로 지워도 "지웠어요" + "되돌리기"(5초)
        let marked = try XCTUnwrap(careStore.value.events.first { $0.id == "N1" })
        XCTAssertEqual(marked.deletedAt, nowMs)   // 표시만 하고 파일에 남는다
        XCTAssertTrue(CareRules.live(model.care.events).isEmpty)
        model.undoDelete("N1")                    // 되돌리기
        XCTAssertNil(careStore.value.events.first?.deletedAt)
        XCTAssertNil(model.recentlyDeleted)
        model.deleteEvent("N1")
        // 차에 보낸 수유를 지워도 차에는 알리지 않는다.
        XCTAssertEqual(model.saveEvent(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true, id: "F1")), .saved)
        let outboxBefore = model.state.outbox
        model.deleteEvent("F1")
        XCTAssertEqual(model.state.outbox, outboxBefore)

        // 30일 뒤 앱을 열 때 실제로 지운다(29일은 남긴다).
        var care = careStore.value
        care.events.append(event(.note, "2026-10-01T09:00:00+09:00", deletedAt: nowMs - 29 * 86_400_000, id: "OLD29"))
        care.events.append(event(.note, "2026-10-01T09:00:00+09:00", deletedAt: nowMs - 30 * 86_400_000 - 1, id: "OLD30"))
        make(care: care)
        XCTAssertNotNil(model.care.events.first { $0.id == "OLD29" })
        XCTAssertNil(model.care.events.first { $0.id == "OLD30" })
        XCTAssertNil(careStore.value.events.first { $0.id == "OLD30" })
    }

    func test_연결을_끊어도_돌봄_기록은_남는다() async {
        XCTAssertEqual(model.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        _ = model.saveEvent(event(.note, "2026-11-05T11:10:00+09:00"))
        let before = careStore.value
        XCTAssertNotNil(model.state.lastSentFeedingAt)
        // 앱에서 끊기
        let message = await model.disconnect()
        XCTAssertNil(message)
        XCTAssertEqual(model.notice, "연결을 끊었어요. 돌봄 기록은 이 휴대폰에 남아 있어요")   // U25 b: 탭(연결 안 됨)으로 간다
        XCTAssertFalse(model.gone)
        XCTAssertEqual(careStore.value, before)
        XCTAssertEqual(model.care, before)
        XCTAssertNil(model.state.lastSentFeedingAt)   // state.json과 함께 지워진다
        // 새 차에 붙으면 24시간 안의 최신 수유가 다시 간다.
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "at1.c_2.d_2.x", role: .owner))
        _ = await model.pair(.pair("123456"))
        XCTAssertEqual(queuedFeedings.map { Rfc3339.parse($0) }, [Self.t("2026-11-05T11:00:00+09:00")])
        // 차 쪽에서 끊긴 경우(401)도 같다. '연결이 끊겼어요' 화면에서 '기록만 계속 쓸게요'면 탭으로 간다(U25 a).
        model.handleUnauthorized()
        XCTAssertEqual(careStore.value, before)
        XCTAssertNil(model.state.lastSentFeedingAt)
        XCTAssertTrue(model.gone)
        model.continueWithoutCar()
        XCTAssertFalse(model.gone)
        XCTAssertEqual(model.care, before)
        XCTAssertEqual([CareText.reconnect, CareText.keepRecording], ["다시 연결하기", "기록만 계속 쓸게요"])
    }

    func test_이_휴대폰의_기록_지우기는_돌봄_기록도_지운다() {
        // 옮기기 표시를 못 남긴 상태로 시작한다.
        careStore = MemoryCareStore(CareFile()); careStore.failing = true
        model = AppModel(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock })
        model.autoFlush = false
        careStore.failing = false
        XCTAssertFalse(model.care.migratedFeedings)
        model.setChildNickname("콩이")
        model.setSleepDiaperEnabled(true)
        _ = model.saveEvent(event(.note, "2026-11-05T11:10:00+09:00"))
        _ = model.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true))
        XCTAssertEqual(careStore.value.events.count, 2)
        model.clearLocalRecords()
        XCTAssertTrue(model.care.events.isEmpty)
        XCTAssertTrue(careStore.value.events.isEmpty)
        // U18: 설정은 남기고, 지운 수유를 다시 옮겨 오지 않게 옮기기 표시를 켠다.
        XCTAssertEqual(careStore.value.childNickname, "콩이")
        XCTAssertTrue(careStore.value.sleepDiaperEnabled)
        XCTAssertTrue(careStore.value.migratedFeedings)
        XCTAssertTrue(model.isPaired)   // 연결은 그대로
        XCTAssertEqual(CareText.clearConfirm, "이 휴대폰의 리포트·알림·노래·돌봄 기록을 지울까요? 차와 다른 보호자에게 있는 기록은 그대로예요")
    }

    func test_지금_수유_기록을_한_번만_옮긴다() {
        let t1 = Self.t("2026-11-05T08:00:00+09:00"), t2 = Self.t("2026-11-05T10:00:00+09:00")
        var state = PhoneState(introSeen: true, carId: "c_1")
        state.feedingLog = [FeedingRecord(id: "F2", at: t2, status: .reflected), FeedingRecord(id: "F1", at: t1, status: .sent)]
        make(care: CareFile(), state: state)
        let feeds = model.care.events.filter { $0.kind == .feed }.sorted { $0.startAt < $1.startAt }
        XCTAssertEqual(feeds.map(\.id), ["F1", "F2"])
        XCTAssertEqual(feeds.map(\.startAt), [CareTime.ms(t1), CareTime.ms(t2)])
        XCTAssertTrue(feeds.allSatisfy { $0.endUnknown && $0.endAt == nil && $0.source == .phone && !$0.inProgress })
        XCTAssertTrue(model.care.migratedFeedings)
        XCTAssertTrue(careStore.value.migratedFeedings)
        XCTAssertEqual(model.state.feedingLog.map(\.id), ["F2", "F1"])   // 원래 기록은 그대로 둔다
        // U22: 옛 경로가 이미 보낸 수유를 다시 보내지 않게 lastSentFeedingAt을 옮긴 것 중 가장 늦은 시각으로 채운다.
        XCTAssertEqual(store.value.lastSentFeedingAt, CareTime.ms(t2))
        XCTAssertTrue(queuedFeedings.isEmpty)

        // 다시 열어도 또 옮기지 않는다.
        let care = careStore.value
        store.value.feedingLog.insert(FeedingRecord(id: "F3", at: Self.t("2026-11-05T11:00:00+09:00")), at: 0)
        make(care: care, state: store.value)
        XCTAssertNil(model.care.events.first { $0.id == "F3" })
        // 옮기다 끝나지 않았던 경우(표시 false): 같은 id는 건너뛴다.
        var half = care; half.migratedFeedings = false
        make(care: half, state: state)
        XCTAssertEqual(model.care.events.filter { $0.id == "F1" }.count, 1)

        // 순서는 state 먼저, care 다음(U22). state를 못 쓰면 옮기지 않는다.
        store = MemoryStateStore(state); store.failing = true
        careStore = MemoryCareStore(CareFile())
        model = AppModel(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock })
        XCTAssertFalse(careStore.value.migratedFeedings)
        XCTAssertTrue(careStore.value.events.isEmpty)
        // care만 못 쓰면 state는 채워지고, 다음 실행이 다시 옮겨 같은 결과가 된다.
        store.failing = false
        careStore.failing = true
        model = AppModel(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock })
        XCTAssertEqual(store.value.lastSentFeedingAt, CareTime.ms(t2))
        XCTAssertFalse(careStore.value.migratedFeedings)
        careStore.failing = false
        model = AppModel(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock })
        XCTAssertTrue(careStore.value.migratedFeedings)
        XCTAssertEqual(store.value.lastSentFeedingAt, CareTime.ms(t2))
        XCTAssertTrue(store.value.outbox.isEmpty)
    }

    func test_처음_이전에_실패한_뒤_적은_수유는_다시_열어도_하나다() {
        var state = PhoneState(introSeen: true, carId: "c_1")
        state.feedingLog = [FeedingRecord(id: "F1", at: Self.t("2026-11-05T08:00:00+09:00"), status: .sent)]
        store = MemoryStateStore(state)
        careStore = MemoryCareStore(CareFile()); careStore.failing = true   // 처음 이전이 실패한다
        model = AppModel(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock })
        model.autoFlush = false
        XCTAssertFalse(careStore.value.migratedFeedings)
        careStore.failing = false   // 저장이 되살아난 뒤 수유를 적는다
        XCTAssertEqual(model.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertTrue(careStore.value.migratedFeedings)   // 보내기 전에 이전을 끝낸다
        XCTAssertEqual(queuedFeedings.count, 1)            // 새 수유는 그대로 차에 보낸다
        model = AppModel(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock })
        XCTAssertEqual(model.care.events.filter { $0.kind == .feed }.count, 2)   // F1 + 새 수유. 보낸 기록이 또 옮겨지지 않는다
    }

    func test_다시_열어도_내_수유의_보내기_상태를_찾는다() throws {
        clock = Self.t("2026-11-05T12:00:00+09:00").addingTimeInterval(0.123)   // 밀리초가 있는 시각
        make()
        model.startFeeding()
        let e = try XCTUnwrap(CareRules.latestFeed(model.care.events))
        let id = try XCTUnwrap(model.feedingRecord(for: e)?.id)
        // state.json을 거치면 소수 초가 사라진다.
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(ULID.generate())
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileStateStore(directory: dir).save(store.value)
        store = MemoryStateStore(FileStateStore(directory: dir).load())
        XCTAssertNotEqual(CareTime.ms(store.value.feedingLog[0].at), e.startAt)
        model = AppModel(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock })
        XCTAssertEqual(model.feedingRecord(for: e)?.id, id)
    }

    // MARK: R12~R17 보이는 값

    func test_마지막_수유는_기록과_차_값_중_늦은_것() {
        _ = model.saveEvent(event(.feed, "2026-11-05T10:00:00+09:00", endUnknown: true))
        _ = model.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true, id: "LATE"))
        model.deleteEvent("LATE")   // 지운 것은 빼고 본다
        XCTAssertEqual(model.lastFeedingStart, Self.t("2026-11-05T10:00:00+09:00"))
        var state = model.state
        state.feeding = FeedingState(lastStart: Self.t("2026-11-05T10:30:00+09:00"), seq: 1)
        make(care: careStore.value, state: state)
        XCTAssertEqual(model.lastFeedingStart, Self.t("2026-11-05T10:30:00+09:00"))
        state.feeding = FeedingState(lastStart: Self.t("2026-11-05T09:00:00+09:00"), seq: 1)
        make(care: careStore.value, state: state)
        XCTAssertEqual(model.lastFeedingStart, Self.t("2026-11-05T10:00:00+09:00"))
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
        model.startFeeding(side: .left)
        let saves = careStore.saves
        clock.addTimeInterval(12 * 60 + 34)
        let running = CareRules.inProgress(.feed, in: model.care.events)!
        XCTAssertEqual(CareRules.elapsedMs(running, now: nowMs), (12 * 60 + 34) * 1000)
        XCTAssertEqual(CareFormat.timer(CareRules.elapsedMs(running, now: nowMs)), "12:34")
        XCTAssertEqual(CareText.feedingNow(running, now: nowMs), "수유 중 · 12분 · 왼쪽")
        clock.addTimeInterval(50 * 60)
        XCTAssertEqual(CareFormat.timer(CareRules.elapsedMs(running, now: nowMs)), "1:02:34")
        XCTAssertEqual(careStore.saves, saves)   // 시간이 흘러도 저장하지 않는다
    }

    func test_잠과_기저귀를_끄면_버튼과_패턴에서_숨긴다() {
        XCTAssertFalse(model.care.sleepDiaperEnabled)   // 기본 꺼짐(U2)
        XCTAssertEqual(CareRules.quickKinds(sleepDiaper: false), [.feed, .note])
        XCTAssertEqual(CareRules.quickKinds(sleepDiaper: true), [.feed, .note, .sleep, .diaper])
        model.setSleepDiaperEnabled(true)
        XCTAssertTrue(careStore.value.sleepDiaperEnabled)
        let sleep = event(.sleep, "2026-11-05T09:00:00+09:00", end: "2026-11-05T10:00:00+09:00")
        XCTAssertEqual(model.saveEvent(sleep), .saved)
        model.setSleepDiaperEnabled(false)
        // 이미 적은 기록은 지우지 않고 타임라인에 남는다.
        XCTAssertEqual(CareRules.events(onDay: "2026-11-05", in: model.care.events).map(\.id), [sleep.id])
        let p = CareRules.pattern(model.care.events, reports: [], now: nowMs, zone: seoul, sleepDiaper: false)
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
        model.startFeeding(side: .left)
        model.startSleep()
        let feed = try XCTUnwrap(CareRules.inProgress(.feed, in: model.care.events))
        let sleep = try XCTUnwrap(CareRules.inProgress(.sleep, in: model.care.events))
        let wanted = CareActivityPlan.wanted(model.care)
        XCTAssertEqual(wanted.map(\.eventId), [feed.id, sleep.id])
        XCTAssertEqual(wanted[0].state, .init(startAt: CareTime.date(feed.startAt), side: "왼쪽"))
        XCTAssertEqual(wanted[1].state.side, nil)   // 잠은 쪽이 없다
        XCTAssertTrue(wanted[1].sleep)
        // 처음에는 둘 다 시작한다.
        XCTAssertEqual(CareActivityPlan.diff(wanted, existing: [:]).start, wanted)
        // 쪽을 바꾸면 고치고, 끝난 기록의 활동은 끝낸다.
        var changed = feed; changed.feedSide = .right
        XCTAssertEqual(model.saveEvent(changed, allowOverlap: true), .saved)
        model.endNow(sleep.id)
        let d = CareActivityPlan.diff(CareActivityPlan.wanted(model.care),
                                      existing: [feed.id: wanted[0].state, sleep.id: wanted[1].state, "OLD": wanted[1].state])
        XCTAssertEqual(d.start, [])
        XCTAssertEqual(d.update.map(\.state.side), ["오른쪽"])
        XCTAssertEqual(d.end, ["OLD", sleep.id].sorted())
        // 바뀐 것이 없으면 아무것도 하지 않는다.
        let same = CareActivityPlan.wanted(model.care)
        XCTAssertEqual(CareActivityPlan.diff(same, existing: [feed.id: same[0].state]), .init())
        // 잠금 화면 글자(7.1 notify.*)
        XCTAssertEqual([CareActivityText.feeding, CareActivityText.sleeping, CareActivityText.end], ["수유 중", "자는 중", "끝내기"])
        // 기록이 바뀔 때마다 알린다(앱은 이 알림으로 Live Activity를 맞춘다).
        var seen: [CareFile] = []
        model.onCareChange = { seen.append($0) }
        model.endNow(feed.id)
        XCTAssertEqual(seen.last, model.care)
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

    // MARK: 7.1 전체 문구표

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
        XCTAssertEqual([CareText.oss, CareText.ossNone], ["오픈소스 라이선스", "이 앱은 외부 오픈소스 라이브러리를 쓰지 않아요"])
        // export.fileName
        XCTAssertEqual(CareText.exportFileName(clock, zone: seoul), "aigyeot-care-20261105.csv")
    }

    // MARK: 형식(7장)

    func test_시각과_기간_형식() {
        let d = Self.t("2026-11-05T12:52:00+09:00")
        XCTAssertEqual(CareFormat.clock(d, seoul), "오후 12:52")
        XCTAssertEqual(CareFormat.clock(Self.t("2026-11-05T00:05:00+09:00"), seoul), "오전 12:05")
        XCTAssertEqual(CareFormat.day(d, seoul), "11월 5일(목)")
        XCTAssertEqual(CareFormat.duration(59_999), "방금")
        XCTAssertEqual(CareFormat.duration(60_000), "1분")
        XCTAssertEqual(CareFormat.duration(12 * 60_000), "12분")
        XCTAssertEqual(CareFormat.duration(120 * 60_000), "2시간")
        XCTAssertEqual(CareFormat.duration(130 * 60_000), "2시간 10분")
        XCTAssertEqual(CareText.ago(130 * 60_000), "2시간 10분 전")
        XCTAssertEqual(CareText.ago(30_000), "방금")
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T13:10:00+09:00"), Self.t("2026-11-05T13:52:00+09:00"), seoul), "오후 1:10–1:52")
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T11:50:00+09:00"), Self.t("2026-11-05T12:20:00+09:00"), seoul), "오전 11:50–오후 12:20")
        // U41: 같은 분이면 시각 하나, 1초라도 분이 넘어가면 구간
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T16:57:10+09:00"), Self.t("2026-11-05T16:57:30+09:00"), seoul), "오후 4:57")
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T16:57:59+09:00"), Self.t("2026-11-05T16:58:00+09:00"), seoul), "오후 4:57–4:58")
        XCTAssertEqual(CareFormat.timer(754_000), "12:34")
        XCTAssertEqual(CareFormat.timer(3_754_000), "1:02:34")
    }
}
