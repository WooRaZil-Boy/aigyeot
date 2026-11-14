import XCTest
@testable import Aigyeot

/// 기록 지우기·연결 끊기·수유 옮기기.
@MainActor
final class CareClearTests: CareTestCase {
    // MARK: 지우기·끊기·옮기기

    /// 지운 사이 같은 종류를 새로 시작했으면 되살린 진행 중 기록은 끝 시각 모름이 된다. 차에는 다시 보내지 않는다.
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

    /// 시작을 지금+1분 안쪽으로 늦춘 진행 중 기록을 바로 끝내도 끝이 시작보다 앞서지 않는다.
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

    /// '이 휴대폰의 기록 지우기'는 내보내기 사본(임시 폴더의 CSV)도 지운다. 다른 임시 파일은 남긴다.
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

    /// 지우기 되돌리기는 저장한 뒤에 띠를 걷는다. 저장이 실패하면 띠를 남겨 남은 시간 안에 다시 누를 수 있다(지운 기록은 목록에 없어 손으로 되살릴 길이 없다).
    /// 기록 지우기는 되돌릴 기록을 지우므로 되돌리기 띠(지우기·가져오기)도 걷는다.
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
        XCTAssertEqual(app.notices.undo, .delete("N1"))   // 어느 길로 지워도 "지웠어요" + "되돌리기"(5초)
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
        XCTAssertEqual(app.status.ui.notice, "연결을 끊었어요. 돌봄 기록은 이 휴대폰에 남아 있어요.")   // 탭(연결 안 됨)으로 간다
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
        XCTAssertEqual(careStore.value, before)
        XCTAssertEqual(app.care.care, before)
        XCTAssertNil(app.state.state.lastSentFeedingAt)   // state.json과 함께 지워진다
        // 새 차에 붙으면 24시간 안의 최신 수유가 다시 간다.
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "at1.c_2.d_2.x", role: .owner))
        _ = await app.pairAndWait(.pair("123456"))
        XCTAssertEqual(queuedFeedings.map { Rfc3339.parse($0) }, [Self.t("2026-11-05T11:00:00+09:00")])
        // 차 쪽에서 끊긴 경우(401)도 같다. '연결이 끊겼어요' 화면에서 '기록만 계속 쓸게요'면 탭으로 간다.
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
        restart()
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
        // 설정은 남기고, 지운 수유를 다시 옮겨 오지 않게 옮기기 표시를 켠다.
        XCTAssertEqual(careStore.value.childNickname, "콩이")
        XCTAssertTrue(careStore.value.sleepDiaperEnabled)
        XCTAssertTrue(careStore.value.migratedFeedings)
        XCTAssertTrue(app.state.isPaired)   // 연결은 그대로
        XCTAssertEqual([CareText.clearConfirm, CareText.clearConfirmNote], ["이 휴대폰의 리포트·알림·노래·돌봄 기록을 지울까요?", "차와 다른 보호자에게 있는 기록은 그대로예요"])   // 기록 지우기 확인 제목·본문
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
        // 옛 경로가 이미 보낸 수유를 다시 보내지 않게 lastSentFeedingAt을 옮긴 것 중 가장 늦은 시각으로 채운다.
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

        // 순서는 state 먼저, care 다음. state를 못 쓰면 옮기지 않는다.
        store = MemoryStateStore(state); store.failing = true
        careStore = MemoryCareStore(CareFile())
        restart()
        XCTAssertFalse(careStore.value.migratedFeedings)
        XCTAssertTrue(careStore.value.events.isEmpty)
        // care만 못 쓰면 state는 채워지고, 다음 실행이 다시 옮겨 같은 결과가 된다.
        store.failing = false
        careStore.failing = true
        restart()
        XCTAssertEqual(store.value.lastSentFeedingAt, CareTime.ms(t2))
        XCTAssertFalse(careStore.value.migratedFeedings)
        careStore.failing = false
        restart()
        XCTAssertTrue(careStore.value.migratedFeedings)
        XCTAssertEqual(store.value.lastSentFeedingAt, CareTime.ms(t2))
        XCTAssertTrue(store.value.outbox.isEmpty)
    }

    func test_처음_이전에_실패한_뒤_적은_수유는_다시_열어도_하나다() {
        var state = PhoneState(introSeen: true, carId: "c_1")
        state.feedingLog = [FeedingRecord(id: "F1", at: Self.t("2026-11-05T08:00:00+09:00"), status: .sent)]
        store = MemoryStateStore(state)
        careStore = MemoryCareStore(CareFile()); careStore.failing = true   // 처음 이전이 실패한다
        restart()
        app.sendOutbox.autoFlush = false
        XCTAssertFalse(careStore.value.migratedFeedings)
        careStore.failing = false   // 저장이 되살아난 뒤 수유를 적는다
        XCTAssertEqual(app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true)), .saved)
        XCTAssertTrue(careStore.value.migratedFeedings)   // 보내기 전에 이전을 끝낸다
        XCTAssertEqual(queuedFeedings.count, 1)            // 새 수유는 그대로 차에 보낸다
        restart()
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
        restart()
        XCTAssertEqual(app.today().ui.feedingRecord(for: e)?.id, id)
    }
}
