import XCTest
@testable import Aigyeot

/// 수명 표(AppLifecycle 주석, 1.5) 행마다 하나. 이름은 안드로이드 LifecycleTableTest와 같고, iOS 결과가 다른 행은 뒷부분을 iOS 결과로 쓴다.
@MainActor
final class LifecycleTableTests: XCTestCase {
    private func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }

    /// L1
    func test_보낼_함_항목_재시작에_남고_기록_지우기에_노래_올리기만_빠지고_끊기에_사라진다() {
        let kit = Kit(songs: ["s1"])
        kit.app.selectSong("s1")
        XCTAssertEqual(kit.app.state.state.outbox.map(\.type), [MessageType.songSelected])
        // 재시작: 파일에서 이어 보낸다.
        XCTAssertEqual(kit.restart().state.state.outbox.map(\.type), [MessageType.songSelected])
        // 기록 지우기: 녹음 파일이 필요한 노래 올리기만 뺀다.
        let upload = OutboxRules.item(type: MessageType.songAdded, body: SongRefBody(songId: "s2"), songId: "s2", now: kit.clock)
        kit.app.state.commit(Kit.owner) { $0.outbox.append(upload) }
        kit.app.clearRecords()
        XCTAssertEqual(kit.app.state.state.outbox.map(\.type), [MessageType.songSelected])
        // 연결 끊김: 상태와 함께 사라진다.
        kit.app.lose.lost(Kit.owner)
        XCTAssertTrue(kit.app.state.state.outbox.isEmpty)
    }

    /// L2(재시작 열은 D12: iOS는 메시지 429 기다림을 저장하지 않는다)
    func test_메시지_429_기다림_기록_지우기에_남고_재시작에_사라지며_끊기면_걸리지_않는다() async {
        let kit = Kit(songs: ["s1"])
        kit.relay.sendError = .http(status: 429, code: "rate_limited", retryAfter: 60)
        kit.app.selectSong("s1")
        await kit.app.sendOutbox.run()
        XCTAssertTrue(kit.app.sendOutbox.isHeld)
        kit.app.clearRecords()
        XCTAssertTrue(kit.app.sendOutbox.isHeld)   // 기록 지우기에 남는다
        let before = kit.app!
        XCTAssertFalse(kit.restart().sendOutbox.isHeld)   // 재시작: 메모리 기다림은 없다(D12). 항목의 다음 시도 시각은 남는다
        XCTAssertGreaterThan(kit.app.state.state.outbox.first?.nextTryAt ?? .distantPast, kit.clock)
        // 끊기: 기다림은 그 자격에만 걸린다(P5)
        before.lose.lost(Kit.owner)
        XCTAssertFalse(before.sendOutbox.isHeld)
    }

    /// L3
    func test_노래_업로드_보내는_사이_기록을_지우면_노래를_되살리지_않는다() async throws {
        let kit = Kit()
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("lt-\(UUID().uuidString).m4a")
        try Data(repeating: 1, count: 100).write(to: file)
        try kit.app.recordSong(name: "자장가", recording: file, durationS: 30)
        kit.relay.putHook = { [unowned kit] in kit.app.clearRecords() }   // 올리는 사이 기록을 지운다
        await kit.app.sendOutbox.run()
        XCTAssertTrue(kit.app.state.state.songs.isEmpty)
        XCTAssertFalse(kit.relay.sent.contains { $0.type == MessageType.songAdded })
        XCTAssertTrue(kit.app.state.state.outbox.allSatisfy { $0.songId == nil })
    }

    /// L4(기록 지우기 열은 B11: iOS는 녹음 시트가 모달이라 모두 지운다)
    func test_녹음_임시_파일_끊기·새_연결에_남고_기록_지우기와_재시작에_모두_지운다() async {
        let kit = Kit()
        func recording(_ name: String) -> URL {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("recording-\(name).m4a")
            try? Data([1]).write(to: url)
            return url
        }
        let a = recording("lt-\(UUID().uuidString)")
        kit.app.lose.lost(Kit.owner)
        XCTAssertTrue(exists(a))   // 끊김에 남는다
        kit.app.appViewModel.continueWithoutCar()
        kit.relay.pairResult = .success(Kit.newOwner)
        let failure = await kit.app.pairAndWait(.pair("123456"))
        XCTAssertNil(failure)
        XCTAssertTrue(exists(a))   // 새 연결에 남는다
        kit.app.clearRecords()
        XCTAssertFalse(exists(a))   // 기록 지우기: 모두 지운다
        let b = recording("lt-\(UUID().uuidString)")
        kit.restart()
        XCTAssertFalse(exists(b))   // 재시작: 앞 실행이 남긴 것을 지운다
    }

    /// L5(D3: iOS는 완료 화면 대신 '연결됐어요' 띠와 탭, 그 뒤 GONE이면 GONE 화면)
    func test_연결_결과_GONE이면_연결됨으로_가지_않고_재시작에_남는_것이_없다() async {
        let kit = Kit(creds: nil)
        kit.relay.pairResult = .success(Kit.guardian)
        kit.app.appViewModel.connectShown = true
        let failure = await kit.app.pairAndWait(.pair("123456"))
        XCTAssertNil(failure)
        XCTAssertEqual(kit.app.pair.run, .done(code: .pair("123456"), role: .guardian))
        XCTAssertEqual(kit.app.appViewModel.ui.route, .main)   // 연결 흐름을 닫았다
        XCTAssertEqual(kit.app.status.ui.notice, NoticeRules.paired)
        kit.app.lose.lost(kit.app.state.creds)
        XCTAssertEqual(kit.app.appViewModel.ui.route, .gone)   // GONE이면 연결됨으로 가지 않는다
        XCTAssertNil(kit.app.status.ui.notice)
        XCTAssertNil(kit.restart().pair.run)   // 재시작에 남는 연결 결과가 없다
    }

    /// L6
    func test_상태_요청_표시_기록_지우기에_켜지고_끊기·새_연결에_꺼진다() async {
        let kit = Kit()
        kit.app.state.commit(Kit.owner) { $0.needsStateRequest = false }
        kit.app.clearRecords()
        XCTAssertTrue(kit.app.state.state.needsStateRequest)
        kit.app.lose.lost(Kit.owner)
        XCTAssertFalse(kit.app.state.state.needsStateRequest)
        kit.app.appViewModel.continueWithoutCar()
        kit.relay.pairResult = .success(Kit.newOwner)
        let failure = await kit.app.pairAndWait(.pair("123456"))
        XCTAssertNil(failure)
        XCTAssertFalse(kit.app.state.state.needsStateRequest)
    }

    /// L7
    func test_연결_반복_자격이_바뀌면_멈추고_표시가_처음으로_돌아가며_새_자격으로_다시_연다() async {
        let kit = Kit(baseURL: URL(string: "http://127.0.0.1:1"))
        defer { kit.app.lifecycle.onBackground() }
        kit.app.lifecycle.onForeground()
        XCTAssertNotNil(kit.app.keepConnected.socket)
        kit.app.keepConnected.socketEvent(.unreachable)
        XCTAssertFalse(kit.app.keepConnected.status.online)
        kit.app.lose.lost(Kit.owner)
        XCTAssertNil(kit.app.keepConnected.socket)   // 멈춤
        XCTAssertEqual(kit.app.keepConnected.status, LinkStatus())   // 표시가 처음으로
        kit.app.appViewModel.continueWithoutCar()
        kit.relay.pairResult = .success(Kit.newOwner)
        let failure = await kit.app.pairAndWait(.pair("123456"))
        XCTAssertNil(failure)
        XCTAssertNotNil(kit.app.keepConnected.socket)   // 앞에 있으니 새 자격으로 다시 연다
    }

    /// L8(새 연결 열은 D3: iOS는 띠를 비운 뒤 '연결됐어요'를 띄운다)
    func test_띠와_배너_끊기에_비우고_새_연결은_연결됐어요를_띄우며_기록_지우기는_배너만_걷고_재시작은_저장된_버림만_다시_띄운다() async {
        let kit = Kit(songs: ["s1"])
        kit.relay.sendError = .http(status: 403, code: "forbidden", retryAfter: nil)   // 다시 보내도 같은 실패: 버리고 띠로 알린다(U54)
        kit.app.selectSong("s1")
        await kit.app.sendOutbox.run()
        let drop = kit.app.status.ui.notice
        XCTAssertNotNil(drop)
        XCTAssertEqual(kit.restart().status.ui.notice, drop)   // 재시작: 저장된 버림만 다시 띄운다
        kit.app.notices.showBanner("01JXALERT000000000000000AB")
        kit.app.clearRecords()
        XCTAssertNil(kit.app.appViewModel.ui.banner)   // 기록 지우기: 배너만 걷는다
        XCTAssertEqual(kit.app.status.ui.notice, drop)
        kit.app.notices.showCare(CareText.previousFeedEnded)
        kit.app.lose.lost(Kit.owner)
        XCTAssertNil(kit.app.status.ui.notice)   // 끊김: 비운다
        kit.app.appViewModel.continueWithoutCar()
        kit.relay.pairResult = .success(Kit.newOwner)
        let failure = await kit.app.pairAndWait(.pair("123456"))
        XCTAssertNil(failure)
        XCTAssertEqual(kit.app.status.ui.notice, NoticeRules.paired)   // 새 연결: 비운 뒤 '연결됐어요'
        XCTAssertNil(kit.app.notices.careNotice)
    }

    /// L9(D1)
    func test_재시작_복구_지금_자격의_강제_끊김_기록이면_정리를_마치고_GONE을_보인다() {
        let kit = Kit(marks: [.goneCleanupPending: Kit.owner.deviceId])
        XCTAssertEqual(kit.app.state.connection, .gone(deviceId: Kit.owner.deviceId))   // 시작이 정리를 이어 했다
        XCTAssertNil(kit.creds.value)
        XCTAssertEqual(kit.marks.get(.goneCleanupPending), Kit.owner.deviceId)   // 고를 때까지 남는다
        // 그대로 다시 켜면(자격 없음 + 기록) 처음부터 GONE이다.
        XCTAssertEqual(kit.restart().state.connection, .gone(deviceId: Kit.owner.deviceId))
        kit.app.appViewModel.continueWithoutCar()
        XCTAssertNil(kit.marks.get(.goneCleanupPending))
        XCTAssertEqual(kit.app.state.connection, Connection.none)
    }

    /// L9(D1)
    func test_재시작_복구_다른_기기의_옛_기록은_걷고_연결은_그대로다() {
        let kit = Kit(marks: [.goneCleanupPending: "d_OLD"])
        XCTAssertNil(kit.marks.get(.goneCleanupPending))
        XCTAssertEqual(kit.app.state.connection, .paired(Kit.owner))
        XCTAssertNotNil(kit.creds.value)
    }

    /// L3 보조: 노래 올리기의 결과(UploadSong.Outcome)가 보낼 함의 SendOutcome으로 바뀐다.
    func test_보낼_함_업로드_결과는_SendOutcome으로_바뀐다() async throws {
        let kit = Kit()
        let mark = kit.app.gate.mark()
        let missing = OutboxRules.item(type: MessageType.songAdded, body: SongRefBody(songId: "none"), songId: "none", now: kit.clock)
        let missingResult = try await kit.app.uploadSong(missing, c: Kit.owner, mark: mark)
        XCTAssertEqual(missingResult, .missingFile)
        // 파일을 올린 노래(재시작 뒤 저장에서 읽은 것 포함)와 노래가 아닌 항목은 PUT 없이 봉투로 간다(7.5 두 단계).
        var uploaded = missing
        uploaded.markUploaded(true)
        let uploadedResult = try await kit.app.uploadSong(uploaded, c: Kit.owner, mark: mark)
        XCTAssertEqual(uploadedResult, .notNeeded)
        let message = OutboxRules.item(type: MessageType.songSelected, body: SongRefBody(songId: "s1"), now: kit.clock)
        let messageResult = try await kit.app.uploadSong(message, c: Kit.owner, mark: mark)
        XCTAssertEqual(messageResult, .notNeeded)
        // 보낼 함에서는 파일 없는 노래가 SendOutcome.drop이 되어 HTTP 없이 빠진다
        kit.app.state.commit(Kit.owner) { $0.outbox = [missing] }
        await kit.app.sendOutbox.run()
        XCTAssertTrue(kit.app.state.state.outbox.isEmpty)
        XCTAssertTrue(kit.relay.uploaded.isEmpty)
        XCTAssertTrue(kit.relay.sent.isEmpty)
    }
}
