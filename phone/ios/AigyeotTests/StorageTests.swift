@testable import Aigyeot
import XCTest

/// 저장 실패·복구·보관.
@MainActor
final class StorageTests: AppTestCase {
    func test_기록_지우기는_복구_사본도_지우고_저장이_거부되면_남긴다() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let songs = FileSongStore(root: dir)
        let fm = FileManager.default
        let stateCopy = dir.appendingPathComponent("state.json.broken-1")
        let songsCopy = dir.appendingPathComponent("songs.broken-1", isDirectory: true)
        func plant() throws {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            try fm.createDirectory(at: songsCopy, withIntermediateDirectories: true)
            try Data("x".utf8).write(to: songsCopy.appendingPathComponent("x.m4a"))
            try Data("{}".utf8).write(to: stateCopy)
        }
        defer { try? fm.removeItem(at: dir) }
        // 저장이 거부된 실행: 아무것도 지우지 않는다.
        try plant()
        store.writesRefused = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false, songFiles: songs)
        app.clearRecords()
        XCTAssertTrue(fm.fileExists(atPath: stateCopy.path))
        XCTAssertTrue(fm.fileExists(atPath: songsCopy.path))
        // 정상 실행: 둘 다 지운다.
        store.writesRefused = false
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false, songFiles: songs)
        app.clearRecords()
        XCTAssertFalse(fm.fileExists(atPath: stateCopy.path))
        XCTAssertFalse(fm.fileExists(atPath: songsCopy.path))
    }

    func test_컨테이너_기본_녹음_폴더는_실제_앱_폴더_밖의_임시_폴더다() {
        let real = FileStateStore.supportDirectory.standardizedFileURL.path
        XCTAssertFalse(app.songFiles.url(for: "x").standardizedFileURL.path.hasPrefix(real))   // 시험 컨테이너의 시작 정리가 앱의 녹음을 지우지 않는다
        XCTAssertTrue(FileSongStore().url(for: "x").standardizedFileURL.path.hasPrefix(real))  // 앱이 넘기는 실제 저장소
    }

    func test_저장에_실패하면_ack하지_않고_상태도_바꾸지_않는다() async {
        store.failing = true
        let e = feedingState(5, last: "2026-11-05T08:00:00Z")
        await app.syncInbox.store(app.state.creds, [e], live: true)
        XCTAssertTrue(relay.acked.isEmpty)
        XCTAssertNil(app.state.state.processed[e.id])
        XCTAssertEqual(app.state.state.feeding.seq, 0)
        store.failing = false
        await app.syncInbox.store(app.state.creds, [e], live: true)   // 중계가 다시 주면 그때 처리하고 ack한다
        XCTAssertEqual(relay.acked, [e.id])
        XCTAssertEqual(app.state.state.feeding.seq, 5)
    }

    func test_삭제_표시는_삭제한_시각부터_7일_뒤에_지운다() {
        let old = clock.addingTimeInterval(-30 * 86400)
        store.value.songs = [SongRow(id: "a", name: "a", durationS: 1, state: .removed, addedAt: old, removedAt: clock.addingTimeInterval(-86400)),
                             SongRow(id: "b", name: "b", durationS: 1, state: .removed, addedAt: old, removedAt: old)]
        restart()
        XCTAssertEqual(app.state.state.songs.map(\.id), ["a"])   // 추가는 오래전이어도 어제 지웠으면 남는다
    }

    func test_결과_저장에_실패하면_표시도_보낼_함도_그대로_두었다가_함께_바꾼다() async {
        app.recordCare.start(.feed)
        relay.sendHook = { [unowned self] in self.store.failing = true }   // 전송 뒤 결과 저장만 실패한다
        await app.sendOutbox.run()
        relay.sendHook = nil
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .sending)   // 결과 표시와 지우기는 함께 저장된다
        XCTAssertEqual(app.state.state.outbox.count, 1)
        store.failing = false
        clock = clock.addingTimeInterval(6)
        await app.sendOutbox.run()
        XCTAssertEqual(app.state.state.feedingLog.first?.status, .sent)
        XCTAssertTrue(app.state.state.outbox.isEmpty)
    }

    func test_저장하지_못한_연결을_지우지도_못하면_차에서_끊게_알린다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
        creds.failing = true
        relay.deleteTokenError = .network            // 되돌리기도 못 했다
        let g = await app.pairAndWait(.pair("042917"))
        XCTAssertEqual(g, .notSaved(removed: false))
        XCTAssertTrue(g?.message.contains("차 화면에서 이 휴대폰 연결을 끊어 주세요") == true)
    }

    func test_만료_정리_저장이_실패하면_만료된_항목을_보내지_않는다() async {
        let old = clock.addingTimeInterval(-8 * 86400)
        store.value.outbox = [OutboxItem(id: "F3", type: MessageType.feedingStarted, body: .object(["at": .string("x")]), ts: "t",
                                         createdAt: old, nextTryAt: old)]
        store.failing = true
        restart()   // 시작 정리가 저장에 실패해 항목이 남는다
        XCTAssertEqual(app.state.state.outbox.count, 1)
        await app.sendOutbox.run()
        XCTAssertTrue(relay.sent.isEmpty)            // 만료된 항목은 보내지 않는다
        XCTAssertEqual(app.state.state.outbox.count, 1)
        store.failing = false
        clock = clock.addingTimeInterval(6)
        await app.sendOutbox.run()
        XCTAssertTrue(relay.sent.isEmpty)
        XCTAssertTrue(app.state.state.outbox.isEmpty)    // 저장이 되면 지운다
    }

    func test_보낸_돌봄_요약_표시는_state_json에_저장하고_다시_읽는다() throws {
        var s = PhoneState(introSeen: true)
        s.lastSentCareSummary = .object(["zone": .string("Asia/Seoul"), "today_feed_count": .number(2), "last_diaper_at": .null])
        let dir = tempDir()
        try FileStateStore(directory: dir).save(s)
        XCTAssertEqual(FileStateStore(directory: dir).load().lastSentCareSummary, s.lastSentCareSummary)
    }

    func test_새_필드가_없는_옛_state_json도_기록_그대로_읽는다() throws {
        var s = PhoneState(introSeen: true)
        s.carId = "c_1"
        s.processed = ["m1": clock]
        s.songs = [SongRow(id: "S1", name: "나비야", durationS: 5, state: .accepted, addedAt: clock)]
        s.feedingLog = [FeedingRecord(id: "F1", at: clock)]
        s.outbox = [OutboxItem(id: "F1", type: "feeding_started", body: .object(["at": .string("x")]), ts: "t", createdAt: clock, nextTryAt: clock)]
        let dir = tempDir()
        let file = dir.appendingPathComponent("state.json")
        try FileStateStore(directory: dir).save(s)
        // 옛 형식으로 만든다: 나중에 더한 필드를 모두 뺀다.
        var obj = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        for k in ["devicesSnapshotSeq", "deviceMarks", "needsStateRequest", "devices", "feeding", "shown"] { obj[k] = nil }
        var songs = obj["songs"] as! [[String: Any]]
        for i in songs.indices { for k in ["candidate", "lastSeq", "removedAt", "hasFile"] { songs[i][k] = nil } }
        obj["songs"] = songs
        var log = obj["feedingLog"] as! [[String: Any]]
        for i in log.indices { for k in ["status", "fixedAt", "sentAt"] { log[i][k] = nil } }
        obj["feedingLog"] = log
        var outbox = obj["outbox"] as! [[String: Any]]
        for i in outbox.indices { for k in ["uploaded", "attempts", "songId"] { outbox[i][k] = nil } }
        obj["outbox"] = outbox
        try JSONSerialization.data(withJSONObject: obj).write(to: file)

        let store = FileStateStore(directory: dir)
        XCTAssertEqual(store.load(), s)         // 기록이 그대로다
        XCTAssertFalse(store.restoreFailed)
    }

    func test_깨진_state_json은_원본을_보존하고_녹음_파일을_지우지_않는다() throws {
        let dir = tempDir()
        let broken = Data("{ not json".utf8)
        try broken.write(to: dir.appendingPathComponent("state.json"))
        let songId = ULID.generate()
        let songs = FileSongStore(root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true))   // 상태 폴더와 다른 곳: 복원 실패가 옆으로 옮기는 폴더가 아니라 시작 정리가 건드리는지를 본다
        try Data(repeating: 1, count: 10).write(to: songs.url(for: songId))
        let store = FileStateStore(directory: dir)
        let m = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false, songFiles: songs)
        XCTAssertTrue(store.restoreFailed)
        XCTAssertTrue(FileManager.default.fileExists(atPath: songs.url(for: songId).path))   // 정리하지 않았다
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path)
        let side = try XCTUnwrap(names.first { $0.hasPrefix("state.json.broken-") })
        XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent(side)), broken)           // 원본이 옆에 남았다
        XCTAssertNotNil(m)
    }

    func test_실시간_메시지_저장이_실패하면_뒤_메시지를_먼저_적용하지_않는다() async {
        make(role: .guardian)
        let body = { (t: String) in HandoffAlertBody(text: t, kind: .parked, restName: nil, minutesToArrival: nil,
                                                   occurredAt: Rfc3339.string(self.clock), sessionId: "S", episodeId: "E") }
        let a = env(MessageType.handoffAlert, seq: 1, body: body("a")), b = env(MessageType.handoffAlert, seq: 2, body: body("b"))
        store.failNext = 1   // a만 저장 실패
        app.keepConnected.socketEvent(.envelope(a))
        app.keepConnected.socketEvent(.envelope(b))
        await app.tasks.settle()
        XCTAssertNil(app.state.state.alert(a.id))
        XCTAssertNil(app.state.state.alert(b.id))   // a보다 뒤라 받은 함에서 순서대로 다시 받는다
        XCTAssertTrue(relay.acked.isEmpty)
    }

    func test_보낼_함_429의_기다림을_저장하지_못해도_지킨다() async {
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songSelected,
                                         body: try! JSONValue(encoding: SongRefBody(songId: "S1")),
                                         ts: Rfc3339.string(clock), songId: nil, createdAt: clock, nextTryAt: clock)]
        restart()
        app.sendOutbox.autoFlush = false
        var tries = 0
        relay.sendHook = { tries += 1 }
        relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 600)
        store.failNext = 1   // 기다림 저장만 실패
        await app.sendOutbox.run()
        clock = clock.addingTimeInterval(10)
        relay.sendError = nil
        await app.sendOutbox.run()
        XCTAssertEqual(tries, 1)   // 600초 안에는 다시 보내지 않는다
    }

    func test_모은_실시간_메시지_저장이_실패하면_ack를_기다리는_사이_온_것을_적용하지_않는다() async {
        make(role: .guardian)
        let body = { (t: String) in HandoffAlertBody(text: t, kind: .parked, restName: nil, minutesToArrival: nil,
                                                   occurredAt: Rfc3339.string(self.clock), sessionId: "S", episodeId: "E") }
        let a = env(MessageType.handoffAlert, seq: 1, body: body("a")), b = env(MessageType.handoffAlert, seq: 2, body: body("b"))
        let c = env(MessageType.handoffAlert, seq: 3, body: body("c"))
        relay.inboxHook = { [unowned self] in app.keepConnected.socketEvent(.envelope(a)); app.keepConnected.socketEvent(.envelope(b)) }   // 받은 함을 기다리는 사이 모인다
        relay.ackHook = { [unowned self] in app.keepConnected.socketEvent(.envelope(c)) }   // a의 ack를 기다리는 사이 새것이 온다
        store.saveCount = 0
        store.failAtSave = 2   // a는 저장, b는 실패
        app.keepConnected.socketEvent(.connected(true))
        await app.tasks.settle()
        XCTAssertNotNil(app.state.state.alert(a.id))
        XCTAssertNil(app.state.state.alert(b.id))
        XCTAssertNil(app.state.state.alert(c.id))   // b보다 뒤라 받은 함에서 순서대로 다시 받는다
    }

    func test_기록_지우기와_앱_시작은_남은_임시_녹음을_지운다() throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("recording-test-\(ULID.generate()).m4a")
        try Data("x".utf8).write(to: tmp)
        app.clearRecords()
        XCTAssertFalse(FileManager.default.fileExists(atPath: tmp.path))
        try Data("x".utf8).write(to: tmp)
        restart()
        XCTAssertFalse(FileManager.default.fileExists(atPath: tmp.path))
    }

    func test_받은_함_저장이_실패하면_다음_쪽을_요청하지_않는다() async {
        relay.inboxPages = [InboxResponse(messages: [feedingState(5, last: "2026-11-05T08:00:00Z")], more: true),
                            InboxResponse(messages: [feedingState(6, last: "2026-11-05T09:00:00Z")], more: false)]
        store.failing = true
        await app.keepConnected.refresh()
        XCTAssertEqual(relay.inboxCalls, 1)                // 실패한 메시지보다 뒤의 쪽으로 넘어가지 않는다
        XCTAssertTrue(relay.acked.isEmpty)
        XCTAssertEqual(app.state.state.feeding.seq, 0)
        store.failing = false
        await app.keepConnected.refresh()                              // 저장이 되면 이어서 받는다
        XCTAssertEqual(relay.inboxCalls, 2)
    }

    func test_보관_기간이_지난_것은_지운다() {
        let old = clock.addingTimeInterval(-8 * 86400)
        let older = clock.addingTimeInterval(-91 * 86400)
        store.value.processed = ["a": old, "b": clock]
        store.value.shown = ["a": old]
        store.value.reports = [StoredReport(messageId: "m", seq: 1, receivedAt: older, body: report("old")),
                               StoredReport(messageId: "n", seq: 2, receivedAt: old, body: report("keep"))]
        restart()
        XCTAssertEqual(Set(app.state.state.processed.keys), ["b"])
        XCTAssertTrue(app.state.state.shown.isEmpty)
        XCTAssertEqual(app.state.state.reports.map(\.body.sessionId), ["keep"])
    }

    func test_폰_시계가_2분_넘게_다르면_경고() {
        app.keepConnected.noteServerDate(clock.addingTimeInterval(60))
        XCTAssertNil(app.status.ui.clockWarning)
        app.keepConnected.noteServerDate(clock.addingTimeInterval(-5 * 60))
        XCTAssertEqual(app.status.ui.clockWarning, "휴대폰 시계가 5분 달라요. 수유 시각이 어긋날 수 있어요.")
    }
}
