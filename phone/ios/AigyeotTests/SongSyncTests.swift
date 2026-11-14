import XCTest
@testable import Aigyeot

/// 노래 올리기·삭제.
@MainActor
final class SongSyncTests: AppTestCase {
    func test_거절된_곡은_같은_id로_song_removed를_자동으로_보낸다() async throws {
        let id = "01SONG"
        let rejected = SongStatusBody(songId: id, name: "나비야", status: .rejected, reason: .peakLimit, durationS: nil, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: rejected)], live: true)
        guard case .rejected(.peakLimit) = app.state.state.songs.first?.state else { return XCTFail("거절 표시") }
        XCTAssertEqual(app.state.state.outbox.map(\.type), ["song_removed"])
        XCTAssertEqual(try app.state.state.outbox[0].body.decode(SongRefBody.self).songId, id)
        // 같은 거절이 다른 id로 다시 와도 두 번 만들지 않는다.
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 6, body: rejected)], live: true)
        XCTAssertEqual(app.state.state.outbox.count, 1)
    }

    func test_지운_곡은_늦게_온_accepted로_되살리지_않는다() async throws {
        let id = "01SONG"
        let acc = SongStatusBody(songId: id, name: "나비야", status: .accepted, reason: nil, durationS: 30, candidate: true)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: acc)], live: true)
        XCTAssertEqual(app.songs().ui.songs.count, 1)
        app.removeSong(id)
        XCTAssertTrue(app.songs().ui.songs.isEmpty)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 6, body: acc)], live: true)
        XCTAssertTrue(app.songs().ui.songs.isEmpty)
    }

    func test_곡_상태는_곡마다_큰_seq가_이긴다() async {
        let id = "01SONG"
        let acc = SongStatusBody(songId: id, name: "나비야", status: .accepted, reason: nil, durationS: 30, candidate: true)
        let rej = SongStatusBody(songId: id, name: "나비야", status: .rejected, reason: .silent, durationS: nil, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 8, body: acc)], live: true)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 7, body: rej)], live: true)
        XCTAssertEqual(app.state.state.songs.first?.state, .accepted)
        XCTAssertEqual(app.state.state.songs.first?.candidate, true)
    }

    func test_거절된_곡은_줄은_남아도_3곡_자리를_차지하지_않는다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 0, count: 100).write(to: file)
        for i in 0..<2 { try app.recordSong(name: "곡\(i)", recording: file, durationS: 10) }
        let rejected = SongStatusBody(songId: "01REJ", name: "나비야", status: .rejected, reason: .peakLimit, durationS: nil, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: rejected)], live: true)
        XCTAssertEqual(app.songs().ui.songs.count, 3)     // 거절 줄은 거절 문구·'다시 녹음'과 함께 보인다
        XCTAssertEqual(app.songs().ui.slotsUsed, 2)          // 자동 지움을 보내므로 자리는 빈다
        XCTAssertNoThrow(try app.recordSong(name: "셋", recording: file, durationS: 10))
        XCTAssertThrowsError(try app.recordSong(name: "넷", recording: file, durationS: 10)) { XCTAssertEqual($0 as? SongError, .limitReached) }
        app.songs().ui.songs.forEach { app.removeSong($0.id) }
    }

    func test_노래를_올리는_사이_다른_메시지가_429를_받으면_song_added도_기다린다() async throws {
        store.value.needsStateRequest = true
        restart()
        app.sendOutbox.autoFlush = false
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 0, count: 100).write(to: file)
        try app.recordSong(name: "시연", recording: file, durationS: 1)
        relay.putHook = { [unowned self] in
            relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 60)
            await app.keepConnected.requestStateIfNeeded()   // 올리는 사이 상태 요청이 메시지 429를 받았다
            relay.sendError = nil
        }
        await app.sendOutbox.run()
        XCTAssertFalse(relay.sent.contains { $0.type == MessageType.songAdded })   // 기기 기다림 중에는 보내지 않는다
        XCTAssertEqual(app.state.state.outbox.first?.uploaded, true)   // 파일은 올렸으니 기다림 뒤 POST만 한다
        XCTAssertEqual(app.state.state.outbox.first?.attempts, 0)      // 실패로 세지 않는다
        app.songs().ui.songs.forEach { app.removeSong($0.id) }
    }

    func test_노래는_3곡까지이고_이름은_1에서_30자() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 0, count: 100).write(to: file)
        XCTAssertThrowsError(try app.recordSong(name: "  ", recording: file, durationS: 10)) { XCTAssertEqual($0 as? SongError, .badName) }
        XCTAssertThrowsError(try app.recordSong(name: "가", recording: file, durationS: 181)) { XCTAssertEqual($0 as? SongError, .tooLong) }
        for i in 0..<3 { try app.recordSong(name: "곡\(i)", recording: file, durationS: 10) }
        XCTAssertThrowsError(try app.recordSong(name: "넷", recording: file, durationS: 10)) { XCTAssertEqual($0 as? SongError, .limitReached) }
        app.songs().ui.songs.forEach { app.removeSong($0.id) }
    }

    func test_노래는_파일을_먼저_올리고_song_added를_보낸다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(app.state.state.songs.first?.id)
        XCTAssertEqual(app.state.state.songs.first?.state, .uploading)
        await app.sendOutbox.run()
        XCTAssertEqual(relay.uploaded, [id])
        XCTAssertEqual(relay.sent.map(\.type), ["song_added"])
        XCTAssertEqual(app.state.state.songs.first?.state, .waiting)
        app.removeSong(id)
    }

    func test_곡_삭제는_저장이_성공한_뒤에만_파일을_지운다() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(app.state.state.songs.first?.id)
        XCTAssertEqual(store.value.outbox.count, 1)   // 곡과 song_added가 같이 저장됐다
        store.failing = true
        app.removeSong(id)
        XCTAssertTrue(FileManager.default.fileExists(atPath: app.songFiles.url(for: id).path))
        XCTAssertEqual(app.state.state.songs.first?.state, .uploading)
        store.failing = false
        app.removeSong(id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: app.songFiles.url(for: id).path))
        XCTAssertEqual(store.value.outbox.map(\.type), ["song_removed"])   // 아직 못 올린 올리기는 빼고 지움은 늘 보낸다(안드로이드 removeSong과 같다)
        XCTAssertNotNil(store.value.songs.first?.removedAt)
    }

    func test_실시간으로_거절된_곡의_자동_삭제는_바로_전송된다() async throws {
        app.sendOutbox.autoFlush = true
        let rejected = SongStatusBody(songId: "01SONG", name: "나비야", status: .rejected, reason: .peakLimit, durationS: nil, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: rejected)], live: true)
        await app.tasks.settle()
        XCTAssertEqual(relay.sent.map(\.type), ["song_removed"])
        XCTAssertTrue(app.state.state.outbox.isEmpty)
    }

    func test_시작할_때_지움_상태이거나_목록에_없는_곡_파일을_정리한다() throws {
        let keep = ULID.generate(), gone = ULID.generate(), orphan = ULID.generate()
        let songs = FileSongStore(root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true))
        for id in [keep, gone, orphan] { try Data(repeating: 1, count: 10).write(to: songs.url(for: id)) }
        store.value.songs = [SongRow(id: keep, name: "k", durationS: 1, state: .accepted, addedAt: clock),
                             SongRow(id: gone, name: "g", durationS: 1, state: .removed, addedAt: clock, removedAt: clock)]
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false, songFiles: songs)
        XCTAssertTrue(FileManager.default.fileExists(atPath: songs.url(for: keep).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: songs.url(for: gone).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: songs.url(for: orphan).path))
    }

    func test_노래를_올리는_사이_기록을_지우면_노래_추가를_보내지_않는다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        XCTAssertEqual(app.state.state.outbox.count, 1)
        relay.putHook = { [unowned self] in self.app.clearRecords() }   // 업로드를 기다리는 사이 기록을 지운다
        await app.sendOutbox.run()
        relay.putHook = nil
        XCTAssertFalse(relay.sent.contains { $0.type == "song_added" })
        XCTAssertTrue(app.state.state.outbox.filter { $0.songId != nil }.isEmpty)
        XCTAssertTrue(app.state.state.songs.isEmpty)
    }

    func test_올리는_사이_지운_노래의_늦은_429는_남은_보낼_함을_막지_않는다() async throws {
        store.value.maxSentCareAsOf = CareTime.ms(clock) - 86_400_000   // 전제: 이 연결에서 요약을 넣은 적이 있어 지운 뒤 빈 요약이 들어온다
        restart()
        app.sendOutbox.autoFlush = false
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        app.reconcileCareSummary.sendsCareSummary = true
        relay.putError = .http(status: 429, code: "rate_limited", retryAfter: 3600)   // 시간당 업로드 한도
        relay.putHook = { [unowned self] in self.app.clearRecords() }   // 업로드를 기다리는 사이 기록을 지운다(빈 요약이 들어온다)
        await app.sendOutbox.run()
        relay.putHook = nil
        XCTAssertTrue(relay.sent.contains { $0.type == MessageType.careSummary })   // 한 시간 기다리지 않고 지우기 결과를 보낸다
        XCTAssertTrue(app.state.state.outbox.isEmpty)
    }

    func test_PUT_429를_기다리는_노래를_지우면_뒤_메시지를_막지_않는다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(app.state.state.songs.first?.id)
        relay.putError = .http(status: 429, code: "rate_limited", retryAfter: 3600)
        await app.sendOutbox.run()   // 한 시간 기다림이 그 곡에 걸린다
        relay.putError = nil
        app.removeSong(id)
        await app.sendOutbox.run()
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.songRemoved])   // 지운 곡의 기다림이 지움 메시지를 막지 않는다
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertTrue(relay.uploaded.isEmpty)   // 지운 곡은 올리지 않는다
    }

    func test_노래_PUT_429의_기다림을_저장하지_못해도_기기_전체를_멈추지_않는다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(app.state.state.songs.first?.id)
        relay.putError = .http(status: 429, code: "rate_limited", retryAfter: 3600)
        relay.putHook = { [unowned self] in self.store.failing = true }   // 기다림 저장만 실패한다
        await app.sendOutbox.run()
        relay.putHook = nil
        store.failing = false
        relay.putError = nil
        app.removeSong(id)
        await app.sendOutbox.run()
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.songRemoved])   // 메시지는 노래 한도와 따로다
    }

    func test_파일이_없어_보내기_전에_버린_노래는_띠를_바꾸지_않는다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)
        let id = try XCTUnwrap(app.state.state.songs.first?.id)
        app.songFiles.delete(id)              // 올릴 파일이 사라졌다
        app.keepConnected.socketEvent(.unreachable)   // 띠가 켜져 있다
        await app.sendOutbox.run()
        XCTAssertTrue(app.status.ui.offline)      // HTTP가 없었으니 '닿음'으로 치지 않는다
        XCTAssertTrue(relay.uploaded.isEmpty)
    }

    func test_노래_삭제와_선택이_버려지면_실패를_알린다() async {
        let acc = SongStatusBody(songId: "01SONG", name: "나비야", status: .accepted, reason: nil, durationS: 30, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: acc)], live: true)
        relay.sendError = .http(status: 403, code: "forbidden_role", retryAfter: nil)
        app.selectSong("01SONG")
        await app.sendOutbox.run()
        XCTAssertEqual(app.status.ui.notice, "실험에 쓸 노래 선택을 차로 보내지 못했어요")
        app.notices.dismiss()
        app.removeSong("01SONG")
        await app.sendOutbox.run()
        XCTAssertTrue(app.status.ui.notice?.contains("노래 지우기") == true)
        XCTAssertTrue(app.state.state.outbox.isEmpty)
    }

    func test_곡_폴더를_보존하지_못하면_저장을_거부한다() throws {
        let dir = tempDir()
        try Data("{ not json".utf8).write(to: dir.appendingPathComponent("state.json"))
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("songs"), withIntermediateDirectories: true)
        let fixed = Date(timeIntervalSince1970: 1_800_000_000)
        // 이동할 자리가 이미 있어 moveItem이 실패한다.
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("songs.broken-1800000000"), withIntermediateDirectories: true)
        let store = FileStateStore(directory: dir, clock: { fixed })
        _ = store.load()
        XCTAssertTrue(store.restoreFailed)
        XCTAssertTrue(store.writesRefused)
        XCTAssertThrowsError(try store.save(PhoneState()))   // 빈 상태 저장을 거부한다
        let raw = try Data(contentsOf: dir.appendingPathComponent("state.json"))
        XCTAssertEqual(raw, Data("{ not json".utf8))          // 원본은 그대로다
    }

    func test_노래_올리기와_연결_끊기도_띠에_반영한다() async {
        let id = "S_put"
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songAdded,
                                         body: try! JSONValue(encoding: SongAddedBody(songId: id, name: "곡", durationS: 10)),
                                         ts: Rfc3339.string(clock), songId: id, createdAt: clock, nextTryAt: clock)]
        restart()
        app.sendOutbox.autoFlush = false
        try? Data("x".utf8).write(to: app.songFiles.url(for: id))
        defer { app.songFiles.delete(id) }
        app.keepConnected.socketEvent(.unreachable)
        relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 60)   // 올리기는 성공, 보내기는 429
        await app.sendOutbox.run()
        XCTAssertEqual(relay.uploaded, [id])
        XCTAssertFalse(app.status.ui.offline)   // 올리기 성공이 중계에 닿았음을 보였다
        // 끊기 DELETE가 네트워크 실패면 띠를 켠다
        relay.deleteError = .network
        _ = await app.disconnect.now()
        XCTAssertTrue(app.status.ui.offline)
    }

    func test_노래_올리기_429는_같은_모델에서도_메시지_기다림을_만들지_않는다() async {
        let id = "S_429b"
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songAdded,
                                         body: try! JSONValue(encoding: SongAddedBody(songId: id, name: "곡", durationS: 10)),
                                         ts: Rfc3339.string(clock), songId: id, createdAt: clock, nextTryAt: clock)]
        store.value.needsStateRequest = true
        restart()
        app.sendOutbox.autoFlush = false
        try? Data("x".utf8).write(to: app.songFiles.url(for: id))
        defer { app.songFiles.delete(id) }
        relay.putError = .http(status: 429, code: "rate_limited", retryAfter: 3600)
        var puts = 0
        relay.putHook = { puts += 1 }
        await app.sendOutbox.run()
        clock = clock.addingTimeInterval(1)
        await app.sendOutbox.run()   // 새 기록 저장 등으로 다시 깨워도
        XCTAssertEqual(puts, 1)     // 올리기 Retry-After(한 시간) 안에는 다시 올리지 않는다
        await app.keepConnected.requestStateIfNeeded()
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.stateRequest])   // 업로드 한도는 상태 요청을 막지 않는다
    }

    func test_다시_녹음은_새_곡이_저장될_때만_기존_곡과_바꾼다() throws {
        let rec = FileManager.default.temporaryDirectory.appendingPathComponent("re-\(ULID.generate()).m4a")
        try Data("x".utf8).write(to: rec)
        defer { try? FileManager.default.removeItem(at: rec) }
        for n in ["a", "b", "c"] { try app.recordSong(name: n, recording: rec, durationS: 10) }
        var st = app.state.state; st.songs[2].state = .failed("x"); store.value = st   // 셋째 곡이 올리기에 실패했다
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false, songFiles: app.songFiles)
        app.sendOutbox.autoFlush = false
        let bad = app.songs().ui.songs[2].id
        XCTAssertThrowsError(try app.recordSong(name: "d", recording: rec, durationS: 10))   // 3곡 한도
        // 거절·실패가 아닌 곡(시트를 연 사이 바뀐 곡 등)은 바꾸지 않는다: 3곡 한도에 걸리고 그 곡은 남는다
        let fine = app.songs().ui.songs[0].id
        XCTAssertThrowsError(try app.recordSong(name: "d", recording: rec, durationS: 10, replacing: fine))
        XCTAssertTrue(app.songs().ui.songs.contains { $0.id == fine })
        // 이름이 비면 실패하고 기존 곡은 그대로다
        XCTAssertThrowsError(try app.recordSong(name: " ", recording: rec, durationS: 10, replacing: bad))
        XCTAssertTrue(app.songs().ui.songs.contains { $0.id == bad })
        XCTAssertTrue(FileManager.default.fileExists(atPath: app.songFiles.url(for: bad).path))
        // 정상이면 한 번에 바꾼다(지움이 새 올리기보다 먼저 선다)
        try app.recordSong(name: "d", recording: rec, durationS: 10, replacing: bad)
        XCTAssertFalse(app.songs().ui.songs.contains { $0.id == bad })
        XCTAssertEqual(app.songs().ui.songs.count, 3)
        let types = app.state.state.outbox.map(\.type)
        XCTAssertEqual(types.filter { $0 == MessageType.songRemoved }.count, 1)
        XCTAssertLessThan(types.firstIndex(of: MessageType.songRemoved)!, types.lastIndex(of: MessageType.songAdded)!)
        XCTAssertFalse(FileManager.default.fileExists(atPath: app.songFiles.url(for: bad).path))
        for r in app.state.state.songs { app.songFiles.delete(r.id) }
    }

    func test_노래_보내기는_올리기_뒤_보내기를_시작할_때의_관찰로_띠를_정한다() async {
        let id = "S_mark"
        store.value.outbox = [OutboxItem(id: ULID.generate(), type: MessageType.songAdded,
                                         body: try! JSONValue(encoding: SongAddedBody(songId: id, name: "곡", durationS: 10)),
                                         ts: Rfc3339.string(clock), songId: id, createdAt: clock, nextTryAt: clock)]
        restart()
        app.sendOutbox.autoFlush = false
        try? Data("x".utf8).write(to: app.songFiles.url(for: id))   // 시작 정리(목록에 없는 파일 지우기) 뒤에 둔다
        defer { app.songFiles.delete(id) }
        relay.putHook = { [unowned self] in app.keepConnected.socketEvent(.unreachable) }   // 올리는 사이 소켓이 닿지 않음을 봤다
        await app.sendOutbox.run()
        XCTAssertEqual(relay.sent.map(\.type), [MessageType.songAdded])
        XCTAssertFalse(app.status.ui.offline)   // 그 뒤에 시작한 보내기가 성공했다
    }

    func test_기록_지우기는_노래_올리기만_빼고_삭제와_선택_요청은_남긴다() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try app.recordSong(name: "나비야", recording: file, durationS: 12)   // 올리는 중(song_added)
        let acc = SongStatusBody(songId: "01SONG", name: "자장가", status: .accepted, reason: nil, durationS: 30, candidate: false)
        await app.syncInbox.store(app.state.creds, [env(MessageType.songStatus, seq: 5, body: acc)], live: true)
        app.selectSong("01SONG")
        app.removeSong("01SONG")   // 오프라인에서 지운 노래
        XCTAssertEqual(store.value.outbox.map(\.type), ["song_added", "song_selected", "song_removed"])
        app.clearRecords()
        XCTAssertEqual(store.value.outbox.map(\.type), ["song_selected", "song_removed"])
    }
}
