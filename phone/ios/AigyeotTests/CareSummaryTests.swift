import XCTest
@testable import Aigyeot

/// 차로 보내는 수유와 돌봄 요약.
@MainActor
final class CareSummaryTests: CareTestCase {
    // MARK: 차로 보내기

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
            app.recordCare.start(.feed, side: .left)   // guardian도 적을 수 있다
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

    // MARK: 돌봄 요약

    /// 표준 예제. 복사본 없이 저장소의 shared 예제를 그대로 읽는다.
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

    /// 저장·고치기·지우기·잠·기저귀 켜고 끄기 뒤에 owner만, `as_of`를 뺀 요약이 바뀌었을 때만 보낸다.
    func test_거절된_요약은_같은_내용도_다음_계기에_다시_보낸다() async {
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
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

    func test_1분_여유로_적은_수유도_하루_수유에_센다() {   // 안드로이드 countPerDay와 같다(지금 + 1분까지 받는다)
        let evs = [event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true), event(.feed, "2026-11-05T12:00:30+09:00", endUnknown: true)]
        let p = CareRules.pattern(evs, reports: [], now: nowMs, zone: seoul, sleepDiaper: false)
        XCTAssertEqual(p.days.last?.feeds, 2)
    }

    func test_기록의_없는_키는_기본값으로_읽는다() throws {   // '없는 키는 기본값'(안드로이드는 source만 기본값)
        let json = #"{"id":"E1","kind":"FEED","startAt":1793944800000}"#
        let e = try JSONDecoder().decode(CareEvent.self, from: Data(json.utf8))
        XCTAssertEqual(e.source, .phone)
        XCTAssertEqual(e.zone, TimeZone.current.identifier)
        XCTAssertEqual(e.createdAt, 1793944800000)
        XCTAssertEqual(e.updatedAt, 1793944800000)
        XCTAssertFalse(e.endUnknown)
    }

    func test_평균은_안드로이드처럼_반을_올린다() {
        XCTAssertEqual(CareText.avgCount(9.0 / 4), "평균 2.3번")   // printf는 2.2(짝수 쪽)였다
        XCTAssertEqual(CareText.avgCount(7.0 / 4), "평균 1.8번")
        XCTAssertEqual(CareText.avgCount(44.0 / 7), "평균 6.3번")
        XCTAssertEqual(CareText.avgCount(7), "평균 7.0번")
    }

    /// 보내 본 요약은 바꾸지 않고 뒤에 새것을 하나 둔다(중계 도착 순서가 계산 순서와 같게).
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
        XCTAssertEqual(sent.last?.body.objectValue?["today_feed_count"], .number(0))   // 빈 요약이 뒤에 가서 이긴다
    }

    func test_돌봄_요약은_owner가_바뀌었을_때만_보내고_보낼_함에_하나만_둔다() async {
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
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

        // 안 보낸 요약이 같은 날 같은 내용이면 그대로 둔다: 같은 id로 다시 보내야 응답을 잃어도 중계가 한 번만 처리한다.
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

        // 끊기(401)면 표시도 지우고, 다시 연결하면 연결 직후에 다시 보낸다.
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

    func test_날이_바뀌면_같은_내용의_요약도_다시_보낸다() async {
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        let midnight = nextMidnight
        clock = midnight.addingTimeInterval(-300)   // 23:55
        app.reconcileCareSummary.summaryOnly(); await app.sendOutbox.run()
        XCTAssertEqual(sentSummaries, 1)
        clock = midnight.addingTimeInterval(300)    // 00:05. 기록이 없어 내용은 같다
        app.reconcileCareSummary.summaryOnly()
        XCTAssertEqual(careSummaries.count, 1)       // 차는 다른 날의 '오늘' 값을 빼므로 새 날의 요약을 보낸다
        await app.sendOutbox.run()
        clock = midnight.addingTimeInterval(600)
        app.reconcileCareSummary.summaryOnly()
        XCTAssertTrue(careSummaries.isEmpty)         // 같은 날에는 다시 보내지 않는다
    }

    func test_폰_시계가_5분_넘게_앞서_보낸_요약은_보낸_값으로_치지_않는다() async {
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        app.keepConnected.noteServerDate(clock.addingTimeInterval(-600))   // 폰 시계가 중계보다 10분 앞선다
        app.reconcileCareSummary.summaryOnly(); await app.sendOutbox.run()
        XCTAssertEqual(sentSummaries, 1)
        XCTAssertNil(app.state.state.lastSentCareSummary)   // 차가 미래 시각이라 버렸을 수 있다
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
        app.lose.lost(app.state.creds)   // 401: 연결 상태를 비운다(돌봄 기록은 연결과 따로다)
        XCTAssertNotNil(store.value.clearingCareIds)
        careStore.failing = false
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore,
                         now: { [unowned self] in self.clock }, sendsCareSummary: false)
        XCTAssertTrue(careStore.value.events.isEmpty)
        XCTAssertNil(app.state.state.clearingCareIds)
    }

    func test_돌봄_저장_실패도_떠_있는_안내를_덮지_않는다() {
        app.notices.addLine("노래 지우기를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요.")
        careStore.failing = true
        app.recordCare.setNickname("콩이")
        XCTAssertEqual(app.status.ui.notice?.components(separatedBy: "\n").count, 2)
    }

    func test_앞_기록을_끝냈다는_안내가_떠_있는_안내를_덮지_않는다() {
        app.recordCare.setSleepDiaper(true)
        app.notices.addLine("다른 안내")
        app.recordCare.start(.sleep)
        clock = clock.addingTimeInterval(600)
        app.recordCare.start(.sleep)   // 자는 중에 또 시작: 앞 잠을 끝낸다
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
        XCTAssertFalse(app.state.state.outbox.contains { $0.type == MessageType.feedingStarted })   // 지울 수유를 새 차로 보내지 않는다
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
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
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

    /// 보내는 중인 요약은 바뀌지 않으니 그 거절은 알리고(안드로이드 afterSend와 같다), 뒤에 둔 새 요약은 따로 보낸다.
    func test_보내는_중에_바뀐_요약이_거절되면_알리고_뒤_요약은_보낸다() async {
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
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
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.timeZone = { TimeZone(identifier: "America/New_York")! }   // 11/1은 25시간인 날
        app.reconcileCareSummary.sendsCareSummary = true
        clock = Self.t("2026-11-01T00:05:00-04:00")
        app.reconcileCareSummary.summaryOnly(); await app.sendOutbox.run()
        XCTAssertEqual(sentSummaries, 1)
        clock = Self.t("2026-11-01T23:30:00-05:00")   // 같은 날, 24시간 25분 뒤
        app.reconcileCareSummary.summaryOnly()
        XCTAssertEqual(careSummaries.count, 1)        // 차는 24시간 지난 요약을 쓰지 않는다
    }

    func test_시계가_앞섰을_때_만든_안_보낸_요약은_시계를_고친_뒤_줄을_막지_않는다() {
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
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
        guard case .string(let aAt)? = a.body.objectValue?["as_of"], case .string(let bAt)? = b.body.objectValue?["as_of"] else { return XCTFail("기대한 꼴이 아니다") }
        XCTAssertGreaterThan(Rfc3339.parse(bAt)!, Rfc3339.parse(aAt)!)   // 차가 '이르다'고 무시하지 않는다
        XCTAssertEqual(b.body.objectValue?["today_diaper_count"], .number(0))
    }

    func test_7일_지나_버린_돌봄_요약은_안내하고_같은_내용이어도_다시_보낸다() {
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        app.reconcileCareSummary.summaryOnly()
        XCTAssertEqual(careSummaries.count, 1)
        clock = clock.addingTimeInterval(8 * 86400)
        app.lifecycle.purgeExpired()
        XCTAssertTrue(careSummaries.isEmpty)
        XCTAssertEqual(app.status.ui.notice, CareText.careSummaryDropped)
        app.reconcileCareSummary.summaryOnly()   // 내용은 같지만(기록 없음) 차에 닿지 않았다
        XCTAssertEqual(careSummaries.count, 1)
    }

    func test_시계가_뒤로_가면_같은_내용이어도_앞서_보낸_as_of를_지날_때까지_다시_보낸다() async {
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
        app.reconcileCareSummary.sendsCareSummary = true
        let real: Date = clock
        // 폰 시계가 3분 앞선 채 보냈다(차는 받는다). 시계를 바로잡은 뒤의 요약은 차가 '이르다'고 무시한다.
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

    /// 429를 받은 요약은 보내 본 것이라 바꾸지 않는다. 기다림을 지킨 뒤 같은 id로 먼저 가고, 새 요약은 그 뒤에 간다.
    func test_429를_받은_요약은_중계에_없으니_새_요약으로_바꾸고_기다림을_지킨다() async {
        makeSummarySentBefore()   // 전제: 넣은 적이 있는 폰
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
        // 429인 요약은 중계에 없다(같은 id를 한도 검사보다 먼저 본다): '보내 봄'을 걷었으니 새 요약이 바꾸고 기다림은 이어받는다(안드로이드 untried)
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
        XCTAssertTrue(careSummaries.isEmpty)                  // 기록이 없다
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
        XCTAssertEqual(careSummaries.count, 1)                // 넣은 적이 있으니 지운 뒤 빈 요약으로 차의 옛 요약을 바꾼다
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
        // 계기 '앞으로 올 때': 저장 없이 0시를 넘겨 오늘 숫자가 0이 된 것을 잡는다.
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
        state.feedingLog = [FeedingRecord(id: "F1", at: Self.t("2026-11-05T11:00:00+09:00"), status: .sent)]   // 옮길 오늘 수유(빈 요약이 아니다)
        store = MemoryStateStore(state)
        creds = MemoryCredentialStore(Credentials(carId: "c_1", deviceId: "d_1", token: "at1.c_1.d_1.x", role: .owner))
        careStore = MemoryCareStore(CareFile())   // 옮기기 전
        app = AppContainer(relay: relay, store: store, credentials: creds, careStore: careStore, now: { [unowned self] in self.clock })
        XCTAssertTrue(app.care.care.migratedFeedings)
        XCTAssertEqual(careSummaries.count, 1)
        await app.tasks.settle()
        XCTAssertTrue(relay.sent.isEmpty)
    }
}
