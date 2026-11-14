import XCTest
@testable import Aigyeot

/// 연결·끊기·링크.
@MainActor
final class PairingFlowTests: AppTestCase {
    func test_첫_안내를_넘기지_않고_링크로_연결해도_홈으로_간다() async {
        make(paired: false)
        store = MemoryStateStore(PhoneState())   // 설치 직후: 첫 안내를 아직 넘기지 않았다
        restart()
        XCTAssertFalse(app.state.state.introSeen)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .guardian))
        let f = await app.pairAndWait(.invite("7K3QM2XW9D"))
        XCTAssertNil(f)
        XCTAssertTrue(app.state.state.introSeen)    // 안내 화면이 남지 않는다(DONE → HOME)
    }

    func test_첫_실행_안내에서_받은_링크는_안내를_두고_차와_연결하기에서_확인_화면을_연다() {
        make(paired: false)
        store = MemoryStateStore(PhoneState())   // 연결도 '먼저 기록만'도 고르지 않은 첫 실행
        restart()
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertNil(app.appViewModel.pendingCode)                      // 안내를 덮지 않는다: 무엇이 오가는지 읽기 전에 연결되지 않게
        XCTAssertFalse(app.state.state.introSeen)
        app.appViewModel.connectFromIntro()
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))   // '차와 연결하기'에서 그 코드로 확인 화면
        XCTAssertFalse(app.state.state.introSeen)                // 안내는 그대로: 확인 화면을 닫으면 안내로 돌아간다
        app.appViewModel.pendingCode = nil
        app.appViewModel.connectFromIntro()                             // 한 번만 쓴다: 다시 누르면 연결 방법 화면
        XCTAssertTrue(app.appViewModel.connectShown)
        XCTAssertTrue(app.state.state.introSeen)
    }

    func test_첫_실행_안내에서_기록만_고르면_받은_링크_코드는_버린다() {
        make(paired: false)
        store = MemoryStateStore(PhoneState())
        restart()
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        app.appViewModel.recordsOnlyFromIntro()
        XCTAssertTrue(app.state.state.introSeen)
        XCTAssertNil(app.appViewModel.introCode)
        XCTAssertNil(app.appViewModel.pendingCode)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=555555")!)   // 안내를 지난 뒤의 링크는 전처럼 확인 화면
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("555555"))
    }

    func test_잘못된_연결_링크는_탭_화면에서도_보이고_연결_화면에_남지_않는다() {
        make(paired: false)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=12")!)   // 탭 화면에서 연 깨진 링크
        XCTAssertEqual(app.status.ui.notice, "올바른 연결 링크가 아니에요")
        XCTAssertNil(app.appViewModel.linkError)   // 나중에 연 연결 화면에 옛 문구가 남지 않는다
        app.appViewModel.connectShown = true
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=12")!)
        XCTAssertEqual(app.appViewModel.linkError, "올바른 연결 링크가 아니에요")
        app.appViewModel.connectShown = false   // 연결 화면을 닫았다 다시 연다
        app.appViewModel.connectShown = true
        XCTAssertNil(app.appViewModel.linkError)
    }

    func test_전송_취소는_망_실패와_가른다() {
        XCTAssertEqual(HTTPRelayClient.transportError(URLError(.cancelled)), .cancelled)
        XCTAssertEqual(HTTPRelayClient.transportError(CancellationError()), .cancelled)
        XCTAssertEqual(HTTPRelayClient.transportError(URLError(.notConnectedToInternet)), .network)
    }

    func test_연결_성공은_토큰을_저장하고_역할을_정한다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .guardian))
        let f = await app.pairAndWait(.invite("7K3QM2XW9D"))
        XCTAssertNil(f)
        XCTAssertEqual(creds.value, Credentials(carId: "c_9", deviceId: "d_9", token: "at1.c_9.d_9.s", role: .guardian))
        XCTAssertEqual(app.state.state.handoffAlerts, true)   // guardian 기본값 참
    }

    func test_연결_실패_문구_분류() async {
        make(paired: false)
        for (err, want) in [(RelayError.http(status: 404, code: "code_invalid", retryAfter: nil), ConnectFailure.codeInvalid),
                            (.http(status: 429, code: "rate_limited", retryAfter: 10), .rateLimited),
                            (.http(status: 400, code: "bad_code_format", retryAfter: nil), .badCode),
                            (.network, .offline)] {
            relay.pairResult = .failure(err)
            let got = await app.pairAndWait(.pair("042917"))
            XCTAssertEqual(got, want)
            XCTAssertNil(creds.value)
            clock = clock.addingTimeInterval(11)   // 429의 잠금(10초)을 지나 다음 경우로
        }
    }

    func test_링크는_바로_연결하지_않고_확인을_거친다() {
        make(paired: false)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))
        app.appViewModel.pendingCode = nil
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=zz")!)
        XCTAssertNil(app.appViewModel.pendingCode)
        XCTAssertEqual(app.status.ui.notice, "올바른 연결 링크가 아니에요")   // 탭 화면이라 띠로 보인다
    }

    func test_직접_친_코드는_확인_없이_연결_요청() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        let f = await app.pairAndWait(typed: .pair("042917"))
        XCTAssertNil(f)
        XCTAssertNil(app.appViewModel.pendingCode)       // 확인 화면을 거치지 않았다
        XCTAssertTrue(app.state.isPaired)
    }

    func test_직접_친_코드의_오류는_호출한_화면에_돌려준다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 404, code: "code_invalid", retryAfter: nil))
        let f = await app.pairAndWait(typed: .pair("042917"))
        XCTAssertEqual(f, .codeInvalid)
        XCTAssertNil(app.appViewModel.pendingCode)
        XCTAssertFalse(app.state.isPaired)
    }

    func test_링크로_온_연결_코드는_확인_화면_제목이_차_화면() {
        make(paired: false)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertEqual(app.appViewModel.pendingCode?.confirmTitle, "차 화면에 이 코드가 보이나요?")
    }

    func test_링크로_온_초대_코드는_확인_화면_제목이_받은_초대_코드() {
        make(paired: false)
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=7k3qm-2xw9d")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .invite("7K3QM2XW9D"))
        XCTAssertEqual(app.appViewModel.pendingCode?.confirmTitle, "받은 초대 코드가 이것과 같나요?")
    }

    func test_이미_연결된_폰에_새_링크가_오면_확인_화면에서_바꿀지_묻는다() {
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))   // 확인 화면이 "지금 연결된 차를 끊고…"를 함께 묻는다
        XCTAssertTrue(app.state.isPaired)
        XCTAssertFalse(relay.deleted)
    }

    func test_끊고_연결은_확인해야_기존_연결을_지운다() async {
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        app.appViewModel.pendingCode = nil                 // '닫기': 창만 닫는다
        XCTAssertTrue(app.state.isPaired)
        XCTAssertFalse(relay.deleted)
        XCTAssertNotNil(creds.value)

        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        let f = await app.pairAndWait(.pair("042917"))   // 확인 화면의 버튼 하나로 끊고 연결한다
        XCTAssertNil(f)
        XCTAssertTrue(relay.deleted)
        XCTAssertEqual(creds.value?.carId, "c_9")
        XCTAssertNil(app.appViewModel.pendingCode)
    }

    func test_연결하는_동안_온_새_링크는_화면_코드를_바꾸지_않는다() async {
        // 끊고 연결의 끊기 요청 중과 연결 요청 중 모두 본다.
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        var stage = ""
        var gate: CheckedContinuation<Void, Never>?
        relay.deleteHook = { stage = "delete"; await withCheckedContinuation { gate = $0 } }
        relay.pairHook = { stage = "pair"; await withCheckedContinuation { gate = $0 } }
        let run = Task { await self.app.pairAndWait(.pair("042917")) }
        while stage != "delete" { await Task.yield() }
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=555555")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))
        XCTAssertEqual(app.appViewModel.linkError, CareText.pairBusy)
        gate?.resume()
        while stage != "pair" { await Task.yield() }
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=7k3qm-2xw9d")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))
        gate?.resume()
        let f = await run.value
        XCTAssertNil(f)
        XCTAssertEqual(relay.pairCalls, 1)
        XCTAssertEqual(creds.value?.carId, "c_9")
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=555555")!)   // 끝나면 평소처럼 받는다
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("555555"))
        XCTAssertNil(app.appViewModel.linkError)
    }

    func test_끊기에_실패하면_새_차에_연결하지_않고_지금_연결을_둔다() async {
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        relay.deleteError = .network
        let f = await app.pairAndWait(.pair("042917"))
        XCTAssertEqual(f, .disconnectFailed("인터넷이 필요해요"))
        XCTAssertEqual(relay.pairCalls, 0)
        XCTAssertTrue(app.state.isPaired)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))   // 확인 화면은 남아 이유를 보인다
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=555555")!)   // 실패로 끝났으니 새 링크를 받는다
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("555555"))
    }

    func test_확인_화면이_떠_있는_동안_끊기면_바꿀지_묻지_않고_연결한다() async {
        // 차 쪽에서 끊긴(401) 뒤에는 이전 연결이 없으니 끊기를 거치지 않는다.
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        app.lose.lost(app.state.creds)
        XCTAssertTrue((app.appViewModel.ui.route == .gone))
        XCTAssertFalse(app.state.isPaired)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))   // 확인 화면이 남고, 이제 바꿀지 묻지 않는다
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        let f = await app.pairAndWait(.pair("042917"))
        XCTAssertNil(f)
        XCTAssertFalse(relay.deleted)            // 끊기 요청을 보내지 않았다
        XCTAssertEqual(relay.pairCalls, 1)
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
    }

    func test_연결_끊기는_중계가_성공해야_지운다() async {
        let msg = await app.disconnect.now()
        XCTAssertNil(msg)
        XCTAssertTrue(relay.deleted)
        XCTAssertNil(creds.value)
        XCTAssertFalse(app.state.isPaired)
    }

    func test_직접_끊는_사이_온_4001은_끊김_화면이_아니라_끊기의_결과다() async {
        // 실제 중계는 DELETE /v1/devices/me의 204보다 먼저 소켓을 4001로 닫는다(removeDevice → closeAll). 사용자 끊기 처리가 나와야 한다.
        relay.deleteHook = { await MainActor.run { self.app.lose.lost(self.app.state.creds) } }
        let msg = await app.disconnect.now()
        XCTAssertNil(msg)
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
        XCTAssertEqual(app.status.ui.notice, "연결을 끊었어요. 돌봄 기록은 이 휴대폰에 남아 있어요.")
        XCTAssertFalse(app.state.isPaired)
        XCTAssertNil(creds.value)
        // 끊기가 끝난 뒤의 401은 다시 강제 해제로 다룬다(이미 끊겼으니 바뀌는 것은 없다).
        app.lose.lost(app.state.creds)
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
    }

    func test_끊기가_실패해도_그사이_온_4001을_잊지_않는다() async {
        // 중계가 4001로 닫은 뒤 DELETE 응답을 잃었다: 토큰은 이미 지워졌으니 다음 요청을 기다리지 않고 GONE.
        relay.deleteError = .network
        relay.deleteHook = { await MainActor.run { self.app.lose.lost(self.app.state.creds) } }
        let msg = await app.disconnect.now()
        XCTAssertEqual(msg, "인터넷이 필요해요")
        XCTAssertTrue((app.appViewModel.ui.route == .gone))
        XCTAssertFalse(app.state.isPaired)
        XCTAssertNil(creds.value)
        XCTAssertNil(app.state.state.carId)
    }

    func test_끊기가_겹쳐도_그사이_온_4001을_잊지_않는다() async {
        // 설정의 끊기가 도는 사이 링크로 '끊고 연결하기'를 누르면 뒤의 끊기는 앞의 결과를 기다린다(끊기 요청은 한 번).
        var deletes = 0
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        relay.deleteHook = {
            deletes += 1
            if deletes == 1 { entered = true; await withCheckedContinuation { gate = $0 } }
        }
        relay.deleteError = .network
        let first = Task { await self.app.disconnect.now() }
        while !entered { await Task.yield() }
        app.lose.lost(app.state.creds)   // 중계가 4001로 닫았다
        let second = Task { await self.app.pairAndWait(.pair("042917")) }
        for _ in 0..<20 { await Task.yield() }
        gate?.resume()
        let msg = await first.value
        let f = await second.value
        XCTAssertEqual(msg, "인터넷이 필요해요")
        XCTAssertEqual(f, .disconnectFailed("인터넷이 필요해요"))
        XCTAssertEqual(deletes, 1)
        XCTAssertEqual(relay.pairCalls, 0)
        XCTAssertTrue((app.appViewModel.ui.route == .gone))
        XCTAssertNil(creds.value)
    }

    func test_끊기가_실패하고_그사이_401이_없었으면_연결을_둔다() async {
        relay.deleteError = .network
        let msg = await app.disconnect.now()
        XCTAssertEqual(msg, "인터넷이 필요해요")
        XCTAssertTrue(app.state.isPaired)
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
    }

    func test_401이면_토큰과_기록을_지우고_끊김_안내() async {
        relay.inboxError = .http(status: 401, code: "unauthorized", retryAfter: nil)
        await app.keepConnected.refresh()
        XCTAssertNil(creds.value)
        XCTAssertTrue((app.appViewModel.ui.route == .gone))
    }

    func test_저장이_거부된_실행의_401_초기화는_실패하고_녹음_파일을_지우지_않는다() async throws {
        let songs = FileSongStore(root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true))
        let file = songs.url(for: "refused-test")
        try Data("m4a".utf8).write(to: file)
        store.restoreFailed = true   // 복원에 실패해 원본을 보존하지 못한 실행
        store.writesRefused = true
        app = AppContainer(relay: relay, store: store, credentials: creds, now: { [unowned self] in self.clock }, sendsCareSummary: false, songFiles: songs)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path), "init 직후")
        relay.inboxError = .http(status: 401, code: "unauthorized", retryAfter: nil)
        await app.keepConnected.refresh()
        XCTAssertTrue((app.appViewModel.ui.route == .gone))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    }

    func test_연결이_진행_중이면_다른_연결_요청은_busy이고_자격은_처음_것_하나다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_A", deviceId: "d_A", deviceToken: "at1.c_A.d_A.x", role: .owner))
        var entered = false
        var gate: CheckedContinuation<Void, Never>?
        relay.pairHook = { [unowned self] in if self.relay.pairCalls == 1 { entered = true; await withCheckedContinuation { gate = $0 } } }
        let a = Task { await self.app.pairAndWait(.pair("042917")) }
        while !entered { await Task.yield() }
        XCTAssertEqual(relay.pairCalls, 1)
        let b = await app.pairAndWait(.pair("111111"))
        XCTAssertEqual(b, .busy)
        XCTAssertEqual(relay.pairCalls, 1)
        gate?.resume()
        let ra = await a.value
        XCTAssertNil(ra)
        XCTAssertEqual(app.state.state.carId, "c_A")
        XCTAssertEqual(creds.value?.carId, "c_A")
    }

    func test_연결_직후_앱이_앞에_있으면_받기를_시작한다() async {
        make(paired: false)
        app.lifecycle.onForeground()   // 연결 전에는 아무것도 하지 않는다
        await app.tasks.settle()
        XCTAssertEqual(relay.inboxCalls, 0)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "t", role: .owner))
        _ = await app.pairAndWait(.pair("042917"))
        await app.tasks.settle()
        XCTAssertEqual(relay.inboxCalls, 1)
    }

    func test_연결이_바뀐_뒤_도착한_이전_응답은_버린다() async {
        let old = feedingState(9, last: "2026-11-05T08:00:00Z")
        relay.inboxPages = [InboxResponse(messages: [old], more: false)]
        relay.inboxHook = { [unowned self] in
            // 응답을 기다리는 사이 401로 끊기고 다른 차에 새로 연결된다.
            self.app.lose.lost(self.app.state.creds)
            self.relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
            _ = await self.app.pairAndWait(.pair("042917"))
        }
        await app.keepConnected.refresh()
        XCTAssertEqual(app.state.state.carId, "c_2")
        XCTAssertEqual(app.state.state.feeding.seq, 0)
        XCTAssertTrue(relay.acked.isEmpty)
        XCTAssertFalse((app.appViewModel.ui.route == .gone))
    }

    func test_연결_429는_Retry_After를_넘긴다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        let f = await app.pairAndWait(.pair("042917"))
        XCTAssertEqual(f, .rateLimited)
        XCTAssertEqual(f!.text(lockedUntil: Date(timeIntervalSinceNow: 30), now: Date()), "30초 뒤에 다시 해 주세요")
    }

    func test_연결_429_뒤_잠금_중에는_직접_친_코드도_요청하지_않는다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        _ = await app.pairAndWait(typed: .pair("042917"))
        XCTAssertTrue(app.connect().ui.isLocked)
        XCTAssertEqual(relay.pairCalls, 1)
        let f = await app.pairAndWait(typed: .pair("111111"))     // 코드를 고쳐도(자동 제출) 잠금은 풀리지 않는다
        XCTAssertEqual(f, .rateLimited)
        XCTAssertEqual(relay.pairCalls, 1)                      // 두 번째는 중계까지 가지 않았다
        let g = await app.pairAndWait(.pair("042917"))               // 확인 화면 길도 같은 잠금을 쓴다
        XCTAssertEqual(g, .rateLimited)
        XCTAssertEqual(relay.pairCalls, 1)
    }

    func test_잠금은_RetryAfter가_지나면_풀린다() async {
        make(paired: false)
        relay.pairResult = .failure(.http(status: 429, code: "rate_limited", retryAfter: 30))
        _ = await app.pairAndWait(typed: .pair("042917"))
        clock = clock.addingTimeInterval(31)
        XCTAssertFalse(app.connect().ui.isLocked)
        relay.pairResult = .failure(.http(status: 404, code: "code_invalid", retryAfter: nil))
        let f = await app.pairAndWait(typed: .pair("042917"))
        XCTAssertEqual(f, .codeInvalid)
        XCTAssertEqual(relay.pairCalls, 2)
    }

    func test_잠금_길이는_RetryAfter_없으면_10초_1초_미만은_1초() {
        XCTAssertEqual(RetryPolicy.pairLock(retryAfter: nil), 10)
        XCTAssertEqual(RetryPolicy.pairLock(retryAfter: 0), 1)
        XCTAssertEqual(RetryPolicy.pairLock(retryAfter: 30), 30)
    }

    func test_직접_친_코드의_요청_중에_QR_링크가_와도_확인_화면으로_옮기지_않는다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_9", deviceId: "d_9", deviceToken: "at1.c_9.d_9.s", role: .owner))
        relay.pairHook = { [unowned self] in self.app.appViewModel.propose(.pair("111111")) }   // 응답이 늦는 사이 QR을 읽었다
        let f = await app.pairAndWait(typed: .pair("042917"))
        XCTAssertNil(f)
        XCTAssertNil(app.appViewModel.pendingCode)
        XCTAssertNotNil(app.appViewModel.linkError)
        XCTAssertTrue(app.state.isPaired)
    }

    func test_초기화_저장이_실패하면_새_자격_증명을_켜지_않는다() async {
        make(paired: false)
        store.value.outbox = [OutboxItem(id: "old", type: "feeding_started", body: .object([:]), ts: "t", createdAt: clock, nextTryAt: clock)]
        restart()
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
        store.failing = true
        let f = await app.pairAndWait(.pair("042917"))
        XCTAssertEqual(f, .notSaved(removed: true))
        XCTAssertEqual(relay.deletedTokens, ["t2"])   // 중계의 그 연결은 받은 토큰으로 지운다
        XCTAssertNil(creds.value)
        XCTAssertFalse(app.state.isPaired)
        store.failing = false
        let retry = await app.pairAndWait(.pair("042917"))
        XCTAssertNil(retry)
        XCTAssertTrue(app.state.state.outbox.isEmpty)   // 이전 차의 보낼 함은 새 차로 가지 않는다
    }

    func test_Keychain_저장에_실패하면_연결로_치지_않는다() async {
        make(paired: false)
        relay.pairResult = .success(PairResponse(carId: "c_2", deviceId: "d_2", deviceToken: "t2", role: .owner))
        creds.failing = true
        let f = await app.pairAndWait(.pair("042917"))
        XCTAssertEqual(f, .notSaved(removed: true))
        XCTAssertEqual(relay.deletedTokens, ["t2"])   // 중계에 토큰 없는 폰이 남지 않게 받은 토큰으로 지운다
        XCTAssertFalse(app.state.isPaired)
        XCTAssertNil(app.state.state.carId)
        XCTAssertNil(app.status.ui.notice)   // "연결됐어요"를 띄우지 않는다
        creds.failing = false
        let retry = await app.pairAndWait(.pair("042917"))
        XCTAssertNil(retry)
        XCTAssertTrue(app.state.isPaired)
    }

    func test_확인을_누른_즉시_새_링크를_받지_않는다() {
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=042917")!)
        app.connect().start(app.appViewModel.pendingCode!)   // 확인 화면의 연결 버튼: 작업이 돌기 전에 '도는 중'이 된다
        app.appViewModel.receive(link: URL(string: "aigyeot://pair?code=555555")!)
        XCTAssertEqual(app.appViewModel.pendingCode, .pair("042917"))
    }
}
