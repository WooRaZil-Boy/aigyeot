import XCTest

/// 차 흉내: 로컬 중계(`RELAY_URL`, 기본 http://127.0.0.1:8788)에 차로 등록하고 연결 코드·메시지를 HTTP로 다룬다.
/// 차 에뮬레이터 없이 폰의 연결 상태를 보려고 쓴다(시험 기기 규칙: 차 쪽은 curl처럼 흉내 낸다).
final class FakeCar {
    let base = URL(string: ProcessInfo.processInfo.environment["RELAY_URL"] ?? "http://127.0.0.1:8788")!
    private(set) var token: String?

    /// 요청 하나를 보내고 상태 코드와 JSON 본문을 돌려준다. `token`이 없으면 차 토큰을 쓴다.
    @discardableResult
    func call(_ method: String, _ path: String, token: String? = nil, body: Any? = nil) -> (status: Int, json: [String: Any]) {
        var req = URLRequest(url: URL(string: path, relativeTo: base)!.absoluteURL)   // 경로에 쿼리(`?after_seq=`)가 붙을 수 있다
        req.httpMethod = method
        if let t = token ?? self.token, !t.isEmpty { req.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization") }
        if let body {
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let done = DispatchSemaphore(value: 0)
        var out: (Int, [String: Any]) = (0, [:])
        URLSession.shared.dataTask(with: req) { d, r, _ in
            out = ((r as? HTTPURLResponse)?.statusCode ?? 0, d.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:])
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 10)
        return out
    }

    func register() {
        let r = call("POST", "v1/cars", body: ["platform": "pleos", "app_version": "1.0.0"])
        token = r.json["car_token"] as? String
        XCTAssertNotNil(token, "차 등록 실패 \(r.status) \(r.json)")
    }

    /// 6자리 연결 코드(owner).
    func pairCode() -> String { call("POST", "v1/pair-codes", body: [String: Any]()).json["code"] as? String ?? "" }

    /// 초대 코드(guardian). 다른 owner 폰(안드로이드 흉내)을 하나 붙여 그 토큰으로 만든다.
    func inviteCode() -> String {
        let p = call("POST", "v1/pair", token: "", body: ["code": pairCode(), "platform": "android", "push_token": NSNull(), "app_version": "1.0.0"])
        let owner = p.json["device_token"] as? String ?? ""
        let r = call("POST", "v1/invites", token: owner, body: envelope("invite_created", [String: Any]()))
        return r.json["code"] as? String ?? ""
    }

    /// 차가 보내는 메시지(report_summary·feeding_state·handoff_alert 등).
    func send(_ type: String, _ body: [String: Any]) {
        let r = call("POST", "v1/messages", body: envelope(type, body))
        XCTAssertEqual(r.status, 201, "\(type) 보내기 실패 \(r.json)")
    }

    /// 차 받은 함(ack하지 않은 것, seq 오름차순).
    func inbox() -> [[String: Any]] { call("GET", "v1/inbox?after_seq=0&limit=100").json["messages"] as? [[String: Any]] ?? [] }

    /// 폰이 보낸 노래를 받아들였다고 알린다(song_status accepted, 실험에 쓰지 않음). 받은 함에 song_added가 없으면 거짓.
    func acceptSong() -> Bool {
        guard let added = inbox().last(where: { $0["type"] as? String == "song_added" }),
              let id = (added["body"] as? [String: Any])?["song_id"] as? String else { return false }
        send("song_status", ["song_id": id, "name": NSNull(), "status": "accepted", "reason": NSNull(), "duration_s": NSNull(), "candidate": false])
        return true
    }

    /// 차 쪽에서 폰을 지운다(폰은 다음 요청에서 401 → '연결이 끊겼어요').
    func removePhones(role: String) {
        let list = call("GET", "v1/devices").json["devices"] as? [[String: Any]] ?? []
        for d in list where d["role"] as? String == role { call("DELETE", "v1/devices/\(d["device_id"] as? String ?? "")") }
    }

    /// 주행 리포트 하나(울음 에피소드 하나). `minAgo`분 전에 시작해 40분 달렸다.
    func sendReport(minAgo: Int, method: String = "lullaby", label: String = "자장가", result: String = "success") {
        let s = Date().addingTimeInterval(TimeInterval(-minAgo * 60)), cry = s.addingTimeInterval(300), ep = Self.ulid()
        send("report_summary", [
            "session_id": Self.ulid(), "started_at": Self.rfc(s), "ended_at": Self.rfc(s.addingTimeInterval(2400)),
            "simulated": false, "output_route": "rear_zone",
            "timeline": [["episode_id": ep, "trigger": "cry_alarm", "started_at": Self.rfc(cry), "alarm_latency_s": 2.4,
                          "sound": ["method": method, "label": label], "play_s": 56,
                          "plays": [["purpose": "auto_first", "method": method, "song_id": NSNull(), "started_at": Self.rfc(cry.addingTimeInterval(3)),
                                     "ended_at": Self.rfc(cry.addingTimeInterval(59)), "play_s": 56]],
                          "result": result, "excluded_reason": NSNull(), "recried": false, "second": NSNull(), "stop": NSNull(), "total_cry_s": 61]],
            "pre_cry_context": [["episode_id": ep, "window_s": 30, "speed_kmh_avg": 58.2, "speed_kmh_max": 71.0, "brake_presses": 2,
                                 "steering_reversals": 1, "turn_signal_used": false, "gear": "D", "cabin_temp_c": 24.5, "window_open": false, "playing": false]],
            "steering": ["available": false, "baseline_per_min": NSNull(), "during_cry_per_min": NSNull(), "after_response_per_min": NSNull(),
                         "during_cry_z": NSNull(), "unavailable_reason": "no_comparable_window"],
            "car_actions": [["action": "rear_playback", "count": 1, "total_s": 56]],
            "controls": [["api": "Seat.setHvacSeatTemperature", "target": "ROW_2_LEFT", "attempts": 1, "succeeded": 1, "failed": 0, "retries": 0]],
            "method_stats": [["method": method, "label": label, "successes": 6, "attempts": 7]],
        ])
    }

    /// 차 수유 시계(owner만 받는다).
    func sendFeedingState(lastMinAgo: Int, windowInMin: Int) {
        send("feeding_state", ["last_feeding_start": Self.rfc(Date().addingTimeInterval(TimeInterval(-lastMinAgo * 60))),
                               "window_start_expected": Self.rfc(Date().addingTimeInterval(TimeInterval(windowInMin * 60)))])
    }

    /// 인계 알림(guardian). `minAgo`분 전에 일어났다.
    func sendHandoff(minAgo: Int) {
        send("handoff_alert", ["text": "아기가 계속 울어서 ○○휴게소에 들르자고 운전자에게 제안했어요.", "kind": "rest_offer", "rest_name": "○○휴게소",
                               "minutes_to_arrival": NSNull(), "occurred_at": Self.rfc(Date().addingTimeInterval(TimeInterval(-minAgo * 60))),
                               "session_id": Self.ulid(), "episode_id": Self.ulid()])
    }

    func envelope(_ type: String, _ body: Any) -> [String: Any] {
        ["v": 1, "id": Self.ulid(), "type": type, "ts": Self.rfc(Date()), "body": body]
    }

    static func rfc(_ d: Date) -> String {
        let f = ISO8601DateFormatter()
        f.timeZone = TimeZone(identifier: "Asia/Seoul")
        return f.string(from: d)
    }

    static func ulid() -> String {
        let a = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
        var t = UInt64(Date().timeIntervalSince1970 * 1000), head = ""
        for _ in 0..<10 { head = String(a[Int(t % 32)]) + head; t /= 32 }
        return head + String((0..<16).map { _ in a.randomElement()! })
    }
}

/// 화면 캡처 순회(시험이 아니라 캡처 도구). `SHOT_DIR`이 없으면 건너뛴다.
/// 앱과 이 순회는 같은 로컬 중계를 쓰고(`RELAY_BASE_URL=http://127.0.0.1:8788`), 차는 `FakeCar`가 흉내 낸다.
/// 실행: `TEST_RUNNER_SHOT_DIR=<폴더> xcodebuild test -scheme AigyeotScreens RELAY_BASE_URL=http://127.0.0.1:8788 ...`
final class ScreenTour: XCTestCase {
    private var app: XCUIApplication!
    private var dir: URL!

    override func setUpWithError() throws {
        guard let d = ProcessInfo.processInfo.environment["SHOT_DIR"] else { throw XCTSkip("SHOT_DIR 없음") }
        dir = URL(fileURLWithPath: d)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        continueAfterFailure = true
        app = XCUIApplication()
        app.launch()
    }

    private func shot(_ name: String) {
        sleep(1)
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? png.write(to: dir.appendingPathComponent("\(name).png"))
    }

    /// 시스템 권한 창이 떠 있으면 허용한다.
    private func allowSystemAlert() {
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow", "허용", "OK", "확인"] where sb.buttons[label].waitForExistence(timeout: 2) {
            sb.buttons[label].tap(); return
        }
    }

    private func tab(_ name: String) {
        // iPhone Duo는 탭 막대가 옆 세로 알약이라 tabBars로 잡히지 않는다. 탭 이름 버튼으로 찾는다.
        app.buttons[name].firstMatch.tap()
    }

    /// 설정의 '잠·기저귀도 기록하기'를 켠다. 탭을 바꾼 직후에는 첫 탭이 빗나가기도 해(가장 큰 글자) 값을 보고 다시 누른다.
    private func turnOnSleepDiaper() {
        let toggle = app.switches.firstMatch
        XCTAssert(toggle.waitForExistence(timeout: 5))
        for _ in 0..<3 where toggle.value as? String != "1" {
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
            _ = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '1'"), object: toggle)], timeout: 2)
        }
        XCTAssertEqual(toggle.value as? String, "1", "잠·기저귀 켜기가 켜지지 않았다")
    }

    private func label(beginsWith s: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", s)).firstMatch
    }

    /// 429 잠금이 Retry-After 끝에 풀리는지(대장 'iOS 429 잠금이 17~45초 늦게 풀림', ios_429_1105d).
    /// 중계의 로컬 IP 한도(10분에 10번 틀림)를 먼저 채워 앱의 첫 요청이 429를 받게 한다. 한도가 10분 남으므로 따로 돌린다:
    /// `TEST_RUNNER_PAIR_429=1 xcodebuild test -scheme AigyeotScreens -only-testing:AigyeotUITests/ScreenTour/test_429_잠금은_Retry_After에_풀린다 RELAY_BASE_URL=http://127.0.0.1:8788 ...`
    func test_429_잠금은_Retry_After에_풀린다() throws {
        guard ProcessInfo.processInfo.environment["PAIR_429"] != nil else { throw XCTSkip("PAIR_429 없음") }
        let car = FakeCar()
        for i in 0..<10 {
            car.call("POST", "v1/pair", body: ["code": String(format: "%06d", 900_000 + i), "platform": "android", "push_token": NSNull(), "app_version": "1.0.0"])
        }
        XCTAssert(app.buttons["차와 연결하기"].waitForExistence(timeout: 10))
        app.buttons["차와 연결하기"].tap()
        let field = app.textFields.firstMatch
        XCTAssert(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText("000001")   // 6자리가 차면 바로 보낸다
        let locked = label(beginsWith: "잠시 뒤에 다시 해 주세요. (")
        XCTAssert(locked.waitForExistence(timeout: 10))
        let shownAt = Date()
        let secs = try XCTUnwrap(Double(locked.label.filter(\.isNumber)))   // "(595초 뒤)"
        shot("429-locked")
        field.typeText("000002")   // 잠금 중: 요청 없이 입력칸에 남는다
        let confirm = app.buttons["확인"]
        XCTAssertFalse(confirm.isEnabled)
        while !confirm.isEnabled && Date().timeIntervalSince(shownAt) < secs + 120 { usleep(250_000) }
        let late = Date().timeIntervalSince(shownAt) - secs
        shot("429-unlocked")
        let line = String(format: "429 잠금: Retry-After %.0f초, 문구가 보인 뒤 %.1f초에 '확인'이 켜짐(끝 예상보다 %.1f초)", secs, secs + late, late)
        print(line)
        try? (line + "\n").write(to: dir.appendingPathComponent("429.txt"), atomically: true, encoding: .utf8)
        XCTAssertLessThan(late, 3, line)
        XCTAssertGreaterThan(late, -3, line)   // 너무 일찍 풀려도 틀리다   // 문구는 응답 뒤에 보이고 남은 초는 올림이라 0~1초 안팎이 정상
    }

    /// 새 화면(phone-care-plan 5장): 연결 안 됨 → owner → 앱에서 끊기 → guardian → 차 쪽 끊김.
    /// 5장: 연결 흐름이 끝나면 오늘 탭으로 간다. 기록 탭에서 링크를 열지만 앱이 다시 떠서 콜드 스타트 링크만 본다.
    /// shortcut: app.open(URL)은 앱을 다시 띄운다(33회차 확인: 시트를 닫으면 기록 탭이 아니었다). 그래서 이 시험은 콜드 스타트 링크만 보고,
    /// 같은 프로세스에서 기록 탭 위로 링크가 오는 길(HomeViews의 onChange)은 거치지 않는다. 실기기에서 메시지 앱 링크로 확인할 일이다
    func test_링크로_다시_뜬_앱은_연결_뒤_오늘_탭이다() {
        let car = FakeCar()
        car.register()
        XCTAssert(app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10))
        app.buttons["먼저 기록만 쓸게요"].tap()
        tab("기록")
        XCTAssert(app.buttons["하루"].waitForExistence(timeout: 5))
        app.open(URL(string: "aigyeot://pair?code=\(car.pairCode())")!)
        XCTAssert(app.buttons["연결하기"].waitForExistence(timeout: 5))
        app.buttons["연결하기"].tap()
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: app.buttons["오늘"].firstMatch)
        waitForExpectations(timeout: 10)
        XCTAssertFalse(app.buttons["하루"].exists)
    }

    func test_새_화면() {
        let car = FakeCar()
        car.register()
        XCTAssert(app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10))
        shot("01-intro")
        app.buttons["먼저 기록만 쓸게요"].tap()

        // 연결 안 됨: 오늘
        XCTAssert(app.buttons["수유 시작"].waitForExistence(timeout: 5))
        shot("02-unpaired-today-empty")
        app.buttons["수유 시작"].tap()
        shot("03-unpaired-today-feeding")
        label(beginsWith: "수유 중").tap()
        XCTAssert(app.buttons["끝내기"].waitForExistence(timeout: 5))
        app.buttons["왼쪽"].tap()
        shot("04-timer")
        app.buttons["끝내기"].tap()

        // 빠른 기록 시트(길게 누름). 타이머 시트가 다 닫힌 뒤에 누른다.
        XCTAssert(app.buttons["수유"].waitForExistence(timeout: 5))
        sleep(1)
        app.buttons["수유"].press(forDuration: 1.0)
        XCTAssert(app.buttons["1시간 전"].waitForExistence(timeout: 5))
        app.buttons["1시간 전"].tap()
        app.buttons["젖병"].tap()
        shot("05-quick-feed")
        app.buttons["저장"].tap()
        // 겹치면 묻는다
        if app.buttons["그래도 저장"].waitForExistence(timeout: 2) { shot("05b-overlap"); app.buttons["그래도 저장"].tap() }
        app.buttons["메모"].tap()
        let memo = app.textViews.firstMatch.exists ? app.textViews.firstMatch : app.textFields.firstMatch
        memo.tap(); memo.typeText("트림 잘 함")
        shot("06-quick-note")
        app.buttons["저장"].tap()
        XCTAssert(app.staticTexts["메모 적기"].waitForNonExistence(timeout: 5), "메모 시트가 닫히지 않았다")
        shot("07-unpaired-today")

        // 설정: 아이 별명, 잠·기저귀 켜기
        tab("설정")
        let nick = app.textFields.firstMatch   // 설정의 입력칸은 별명 하나다
        nick.tap()
        if app.keyboards.firstMatch.waitForExistence(timeout: 2) { app.typeText("콩이\n") }   // 큰 글자에서는 칸이 줄바꿈돼 초점이 안 갈 수 있다
        turnOnSleepDiaper()
        shot("08-unpaired-settings")
        app.swipeUp()
        app.buttons["정보·라이선스"].tap()
        shot("08b-about")
        tab("설정")   // 같은 탭을 다시 누르면 첫 화면으로 돌아간다
        tab("오늘")
        app.buttons["잠"].tap()
        shot("09-unpaired-today-sleep")
        // 새 기저귀는 종류를 고르지 않아도 소변으로 저장된다(안드로이드와 같다)
        let diaperButton = app.buttons["기저귀"].firstMatch
        for _ in 0..<4 where !(diaperButton.exists && diaperButton.isHittable) { app.swipeUp() }
        diaperButton.tap()
        XCTAssert(app.buttons["저장"].waitForExistence(timeout: 5), "기저귀 시트가 뜨지 않았다")
        app.buttons["저장"].firstMatch.tap()

        // 기록: 하루 → 지우기·되돌리기 → 패턴
        tab("기록")
        shot("10-records-day")
        let diaperRow = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH '기저귀 · ' AND label ENDSWITH '· 소변'")).firstMatch
        for _ in 0..<4 where !diaperRow.exists { app.swipeUp() }
        XCTAssert(diaperRow.exists, "종류를 고르지 않은 새 기저귀가 소변으로 저장되지 않았다")
        for _ in 0..<4 where !(app.buttons["하루"].exists && app.buttons["하루"].isHittable) { app.swipeDown() }
        // 띠가 없으면 고정 영역은 0이다(제목 바로 아래 하루·패턴 전환). 늘 200pt를 차지하던 회귀를 막는다.
        let gap = app.buttons["하루"].firstMatch.frame.minY - app.navigationBars.firstMatch.frame.maxY   // 가장 큰 글자에서는 나눈 선택 대신 글자 버튼
        XCTAssertLessThan(gap, 40, "띠가 없는데 위쪽 빈칸 \(gap)pt")
        XCTAssertGreaterThanOrEqual(app.buttons["하루"].firstMatch.frame.height, 44, "하루·패턴 전환의 누르는 높이")   // 나눈 선택도 44pt(8장)
        let row = app.cells.containing(NSPredicate(format: "label BEGINSWITH '수유 · '")).firstMatch   // 가장 큰 글자에서는 첫 줄이 전환·날짜 띠다
        row.swipeLeft()
        XCTAssert(app.buttons["지우기"].waitForExistence(timeout: 5), "밀어도 지우기가 없다")
        app.buttons["지우기"].tap(); shot("11-records-undo")
        XCTAssert(app.buttons["되돌리기"].waitForExistence(timeout: 3), "지운 뒤 되돌리기가 없다")
        app.buttons["되돌리기"].tap()
        app.buttons["패턴"].tap()
        shot("12-records-pattern")
        app.swipeUp(); shot("13-records-pattern-2")
        for _ in 0..<6 where !(app.buttons["하루"].exists && app.buttons["하루"].isHittable) { app.swipeDown() }   // 가장 큰 글자에서는 전환이 목록과 함께 굴러 올라간다
        app.buttons["하루"].tap()

        // 차 탭(연결 안 됨) → 연결 흐름 → owner
        tab("차")
        shot("14-unpaired-car")
        app.buttons["차와 연결하기"].firstMatch.tap()
        XCTAssert(app.buttons["QR 찍기"].waitForExistence(timeout: 5))
        shot("15-connect-from-tabs")
        app.open(URL(string: "aigyeot://pair?code=\(car.pairCode())")!)
        XCTAssert(app.buttons["연결하기"].waitForExistence(timeout: 5))
        app.buttons["연결하기"].tap()
        XCTAssert(app.buttons["차"].firstMatch.waitForExistence(timeout: 10))
        // 차가 보내는 것: 수유 시계, 리포트 둘(차 기록 줄)
        car.sendFeedingState(lastMinAgo: 200, windowInMin: 40)
        car.sendReport(minAgo: 300)
        car.sendReport(minAgo: 90, method: "white_noise", label: "백색소음", result: "failure")
        sleep(3)
        shot("16-owner-today")
        // 차가 받는 돌봄 요약(계약 6.4 care_summary): 연결 직후 owner가 보낸다. 여기서는 칸·꼴·잠 켜짐만 본다. 값 계산은 단위 시험(R18 표준 예제)이 본다.
        let summary = car.inbox().last { $0["type"] as? String == "care_summary" }
        XCTAssertEqual((summary?["from"] as? [String: Any])?["role"] as? String, "owner", "차 받은 함에 돌봄 요약이 없다")
        let body = summary?["body"] as? [String: Any] ?? [:]
        XCTAssertEqual(Set(body.keys), ["as_of", "zone", "sleep_diaper_enabled", "last_sleep_start_at", "last_sleep_end_at", "sleeping_since",
                                        "today_sleep_min", "night_wakeups", "last_diaper_at", "today_diaper_count", "today_feed_count"])
        XCTAssertEqual(body["sleep_diaper_enabled"] as? Bool, true)
        XCTAssertGreaterThanOrEqual(body["today_feed_count"] as? Int ?? 0, 1)   // 자정 직후에 돌리면 '1시간 전' 수유는 어제다
        let secondsWithOffset = NSPredicate(format: "SELF MATCHES %@", #"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d[+-]\d\d:\d\d"#)
        for k in ["as_of", "sleeping_since"] {
            XCTAssert(secondsWithOffset.evaluate(with: body[k] as? String ?? ""), "\(k) 꼴이 계약과 다르다: \(body[k] ?? "없음")")
        }

        // 연결된 폰에 새 연결 링크: 확인 화면이 끊을지 묻고, '아니에요'면 지금 연결을 그대로 둔다(G16)
        app.open(URL(string: "aigyeot://pair?code=\(car.pairCode())")!)
        XCTAssert(app.buttons["끊고 연결하기"].waitForExistence(timeout: 5))
        XCTAssert(app.staticTexts["지금 연결된 차를 끊고 새 차에 연결할까요?"].exists)
        shot("16b-owner-replace-confirm")
        app.buttons["아니에요"].tap()
        let stillPaired = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS '마지막 연락'")).firstMatch
        XCTAssert(stillPaired.waitForExistence(timeout: 5), "'아니에요' 뒤 연결이 그대로가 아니다")   // 차 타일은 연결돼 있을 때만 있다. FakeCar는 소켓 없이 HTTP만 써서 차는 '꺼짐 · 마지막 연락'으로 보인다
        app.swipeUp(); shot("17-owner-today-2")
        tab("기록")
        shot("18-owner-records")
        tab("차")
        shot("19-owner-car")
        // 녹음 시트: 파형이 시트 폭 안에 든다(U13 ①). 7초면 막대가 60개 넘게 쌓인다.
        let rec = app.buttons["녹음하기"]
        for _ in 0..<4 where !(rec.exists && rec.isHittable) { app.swipeUp() }   // 큰 글자에서는 화면 밖에 있다(목록은 보이는 줄만 만든다)
        rec.tap()
        allowSystemAlert()   // 마이크 권한은 시트를 열 때 묻는다(7.3)
        let recTimer = app.staticTexts["0:00 / 3:00"]
        XCTAssert(recTimer.waitForExistence(timeout: 5))
        let f = recTimer.frame
        let recordButton = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX, dy: f.maxY + 56))
        recordButton.tap()
        sleep(8)
        shot("19b-owner-recording")
        recordButton.tap()
        XCTAssert(app.buttons["보내기"].waitForExistence(timeout: 5))
        shot("19c-owner-recorded")   // 멈춘 뒤: 이름·다시 듣기·보내기(가장 큰 글자에서는 시트 안에서 굴린다)
        // 이름을 넣어 보내고, 차가 받아들이면 목록에 '다시 듣기'·'실험에 쓰기'가 생긴다(둘 다 44pt 이상, 8장)
        let songName = app.textFields["노래 이름(30자까지)"]
        songName.tap(); songName.typeText("song1\n")   // 키보드의 '완료'로 닫는다(키보드 추천 막대가 '보내기'를 덮는다)
        let send = app.buttons["보내기"]
        for _ in 0..<3 where !send.isHittable { app.scrollViews.firstMatch.swipeUp() }   // 큰 글자에서는 시트 안에서 굴린다
        send.tap()
        XCTAssert(app.staticTexts["노래 녹음"].waitForNonExistence(timeout: 5))
        var accepted = false
        for _ in 0..<10 where !accepted { sleep(1); accepted = car.acceptSong() }   // 올리기와 song_added가 끝나기를 기다린다
        XCTAssert(accepted, "차 받은 함에 song_added가 없다")
        let useIt = app.buttons["실험에 쓰기"], replay = app.buttons["다시 듣기"]
        XCTAssert(useIt.waitForExistence(timeout: 15), "받아들인 노래의 '실험에 쓰기'가 없다")
        XCTAssertGreaterThanOrEqual(useIt.frame.height, 44)
        XCTAssertGreaterThanOrEqual(replay.frame.height, 44)
        shot("19d-owner-song-accepted")
        app.swipeUp(); shot("20-owner-car-2")
        tab("설정")
        shot("21-owner-settings")
        app.buttons["보호자"].tap()
        shot("22-owner-guardians")
        // 초대 코드 카드(가장 큰 글자에서 코드 전체가 보이는지는 캡처로 본다)
        let invite = app.buttons["초대 만들기"].firstMatch
        for _ in 0..<6 where !(invite.exists && invite.isHittable) { app.swipeUp() }
        invite.tap()
        XCTAssert(app.staticTexts.matching(NSPredicate(format: "label MATCHES '[0-9A-Z]{5}-[0-9A-Z]{5}'")).firstMatch.waitForExistence(timeout: 10), "초대 코드가 뜨지 않았다")
        shot("22b-owner-invite")
        tab("설정")
        let disconnect = app.buttons["연결 끊기"].firstMatch
        for _ in 0..<6 where !(disconnect.exists && disconnect.isHittable) { app.swipeUp() }   // 큰 글자에서는 화면 밖에 있다
        disconnect.tap()
        shot("23-disconnect-dialog")
        app.sheets.buttons["연결 끊기"].firstMatch.tap()
        // U25 b: 탭을 누르지 않아도 오늘로 간다.
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: app.buttons["오늘"].firstMatch)
        waitForExpectations(timeout: 10)
        shot("24-after-disconnect")

        // guardian: 차 탭에서 연결 → 초대 코드
        tab("차")
        app.buttons["차와 연결하기"].firstMatch.tap()
        let field = app.textFields.firstMatch
        XCTAssert(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText(car.inviteCode())
        app.buttons["확인"].tap()
        allowSystemAlert()
        XCTAssert(app.buttons["기록"].firstMatch.waitForExistence(timeout: 10))
        // 인계 알림(2시간 10분 전, U20)과 리포트 넷(오늘에는 3개, 5장)
        car.sendHandoff(minAgo: 130)
        car.sendHandoff(minAgo: 0)   // 지금 온 인계 알림은 위에 배너로 뜬다(5.6)
        let banner = app.buttons["handoffBanner"]   // 목록 줄이 아니라 배너 자체
        XCTAssert(banner.waitForExistence(timeout: 10), "인계 배너가 뜨지 않았다")
        shot("24b-guardian-banner")
        for m in [400, 300, 200, 90] { car.sendReport(minAgo: m) }
        sleep(3)
        shot("25-guardian-today")
        // 가장 큰 글자에서는 배너가 화면 대부분을 덮어 swipeUp이 배너 누름이 된다. 배너 아래에서 민다
        let h = app.frame.height, startY = max(banner.frame.maxY + 30, h * 0.5)
        let origin = app.coordinate(withNormalizedOffset: .zero)
        origin.withOffset(CGVector(dx: app.frame.width * 0.3, dy: startY))
            .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: app.frame.width * 0.3, dy: startY - h * 0.6)))
        shot("26-guardian-today-2")
        app.buttons["최근 리포트"].firstMatch.tap()
        shot("26b-guardian-reports")
        // 리포트 상세(제목은 주행 날짜)까지 들어가 큰 글자 배치를 찍는다. 먼저 리포트 목록인지 못박는다
        XCTAssert(app.navigationBars["리포트"].waitForExistence(timeout: 5), "리포트 목록이 아니다")
        app.cells.firstMatch.tap()
        XCTAssert(app.staticTexts["에피소드"].waitForExistence(timeout: 5), "리포트 상세로 들어가지 못했다")
        shot("26c-report-detail")
        app.swipeUp(); shot("26d-report-detail-2")
        tab("오늘")   // 같은 탭을 다시 누르면 첫 화면으로 돌아간다
        tab("기록")
        shot("27-guardian-records")
        app.buttons["지난 기록 적기"].tap()
        shot("28-guardian-quick")
        app.swipeDown(velocity: .fast)

        // 차 쪽에서 끊김(401) → '연결이 끊겼어요' → '기록만 계속 쓸게요'
        car.removePhones(role: "guardian")
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssert(app.buttons["기록만 계속 쓸게요"].waitForExistence(timeout: 30))
        shot("29-gone")
        app.buttons["기록만 계속 쓸게요"].tap()
        shot("30-after-gone")
    }

    /// 띠 묶음(8장 동적 글자 크기): guardian에서 알림 꺼짐·시계 차이·오프라인 띠를 함께 띄운다.
    /// 앱은 응답 `Date`를 옮기고 연결을 끊을 수 있는 프록시를 거쳐 중계에 붙는다(`PROXY_CTRL`이 그 조종 주소). 차는 중계에 바로 붙는다.
    func test_띠_묶음() throws {
        guard let ctrl = ProcessInfo.processInfo.environment["PROXY_CTRL"] else { throw XCTSkip("PROXY_CTRL 없음") }
        func proxy(_ path: String) { _ = try? Data(contentsOf: URL(string: ctrl + path)!) }
        proxy("/skew?s=600")
        defer { proxy("/skew?s=0"); proxy("/up") }
        let car = FakeCar()
        car.register()
        XCTAssert(app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10))
        app.buttons["먼저 기록만 쓸게요"].tap()
        tab("차")
        app.buttons["차와 연결하기"].firstMatch.tap()
        let field = app.textFields.firstMatch
        XCTAssert(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText(car.inviteCode())
        app.buttons["확인"].tap()
        // 권한 창에서 실제로 거부해야 '답한 뒤 다시 확인' 경로를 거친다(새로 설치해 처음 묻는다)
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let deny = ["허용 안 함", "Don’t Allow", "Don't Allow"].map { sb.buttons[$0] }
        XCTAssert(deny[0].waitForExistence(timeout: 5) || deny[1].exists || deny[2].exists, "알림 권한 창이 뜨지 않았다")
        deny.first { $0.exists }?.tap()
        XCTAssert(app.buttons["기록"].firstMatch.waitForExistence(timeout: 10))
        XCTAssert(label(beginsWith: "휴대폰 시계가").waitForExistence(timeout: 10))
        // 앱이 앞에 있는 채로 중계가 끊긴다: 소켓이 다시 붙지 못하면 띠가 켜진다(계약 9장 '중계 불통').
        proxy("/down")
        XCTAssert(label(beginsWith: "오프라인이에요").waitForExistence(timeout: 10))
        XCTAssert(label(beginsWith: "알림이 꺼져 있어").exists)
        XCTAssert(app.buttons["다시 시도"].exists)   // 7.2 '그 밖: 띠에 다시 시도'
        shot("B1-bands-today")
        app.swipeUp(); shot("B1b-bands-today-2")
        tab("기록")
        shot("B2-bands-records")
        // 고정 영역은 200pt까지만 차지하고 넘치면 그 안에서 굴린다(8장): 맨 아래 띠의 '설정 열기'까지 닿아야 한다.
        let bandsArea = app.scrollViews["statusBands"]
        XCTAssert(bandsArea.exists)
        // 스크롤 뷰 틀은 내비게이션 바 밑(안전 영역)까지 넓어진다. 보이는 높이는 바 아래부터 잰다.
        let visible = bandsArea.frame.maxY - max(bandsArea.frame.minY, app.navigationBars.firstMatch.frame.maxY)
        XCTAssertLessThanOrEqual(visible, 201)
        let openSettings = app.buttons["설정 열기"]
        for _ in 0..<4 where !openSettings.isHittable { bandsArea.swipeUp() }
        XCTAssert(openSettings.isHittable, "고정 띠 안에서 '설정 열기'에 닿지 못했다")
        shot("B2b-bands-records-scrolled")
        tab("설정")
        shot("B3-bands-settings")
        // 중계가 돌아오면 소켓이 다시 붙고 띠가 꺼진다(다시 붙기 간격은 60초까지).
        proxy("/up")
        XCTAssert(label(beginsWith: "오프라인이에요").waitForNonExistence(timeout: 70))
    }

    /// 잠금 화면 진행 표시(C6): 수유·잠 시작 → 잠그기 → 잠금 화면 '끝내기' → 앱에서 닫혔는지.
    /// 경과가 1시간을 넘을 때(1:0x:xx) 잠금 화면 카드와 Dynamic Island가 잘리지 않는지 본다(가장 큰 글자 확인용).
    /// 2시간 전에 시작한 진행 중 수유를 debug 실행 인자(-longFeed)로 넣는다. 시각 바퀴를 돌리지 않아 하루 중 언제 돌려도 같다.
    func test_긴_경과_잠금_화면() throws {
        app.terminate()
        app.launchArguments = ["-longFeed"]
        app.launch()
        XCTAssert(app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10))
        app.buttons["먼저 기록만 쓸게요"].tap()
        // 실제로 한 시간 넘게 지난 기록인지 먼저 못박는다(저장이 거절되면 짧은 경과로 찍힌다)
        // 경과가 한 시간을 넘었다(1:0x:xx 꼴): '1시간…' 또는 '2시간…'. 시각 바퀴는 고른 값보다 한 칸 덜 갈 때가 있어 1시간도 받는다.
        // 14시간 같은 엉뚱한 값(24시간제 계산 실수)은 받지 않는다.
        let long = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH '수유 중 · 1시간' OR label BEGINSWITH '수유 중 · 2시간'")).firstMatch
        XCTAssert(long.waitForExistence(timeout: 5), "경과가 한 시간을 넘지 않았다")
        shot("L6-app-long")
        // 홈으로 간다(이 시뮬레이터에서는 press(.home)이 먹지 않아 앱 화면이 찍혔다). 앱이 뒤로 간 것을 못박고 찍는다
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        springboard.activate()
        XCTAssert(app.wait(for: .runningBackground, timeout: 5) || app.state == .runningBackgroundSuspended, "앱이 뒤로 가지 않았다")
        sleep(2)
        shot("L7-island-long")   // 홈 화면의 Dynamic Island(압축)
        app.activate()
        springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.0))
            .press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.7)))
        sleep(3)
        for label in ["허용", "Allow"] where springboard.buttons[label].exists && springboard.buttons[label].isHittable { springboard.buttons[label].tap(); sleep(2); break }
        shot("L8-lock-long")
        XCTAssert(springboard.buttons["끝내기"].waitForExistence(timeout: 5), "잠금 화면에 끝내기가 없다")
        // 잠금 화면 카드도 한 시간 넘은 경과를 시:분:초로 보인다(7.1 '1:02:34'). 접근성 글은 형식만 본다. 잘림은 L8 캡처로 본다(가장 큰 글자는 두 줄로 접힌다)
        print("잠금 화면 줄: \(springboard.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS '수유 중'")).allElementsBoundByIndex.map(\.label))")
        let lockLong = springboard.descendants(matching: .any).matching(NSPredicate(format: "label MATCHES '수유 중 · [12]:[0-5][0-9]:[0-5][0-9].*'")).firstMatch
        XCTAssert(lockLong.waitForExistence(timeout: 5), "잠금 화면 경과가 한 시간 넘은 시:분:초가 아니다")
        springboard.swipeUp()
    }


    func test_잠금_화면() {
        XCTAssert(app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10))
        app.buttons["먼저 기록만 쓸게요"].tap()
        tab("설정")
        turnOnSleepDiaper()
        tab("오늘")
        app.buttons["수유 시작"].tap()
        label(beginsWith: "수유 중").tap()
        XCTAssert(app.buttons["왼쪽"].waitForExistence(timeout: 5))
        app.buttons["왼쪽"].tap()
        // 타이머 시트를 위에서 아래로 끌어 닫는다.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.04))
            .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.95)))
        let sleepButton = app.buttons["잠"]
        XCTAssert(app.buttons["수유 중"].waitForExistence(timeout: 5))   // 시트가 닫히고 오늘 탭이 보인다
        sleep(1)
        for _ in 0..<4 where !(sleepButton.exists && sleepButton.isHittable) { app.swipeUp() }   // 큰 글자에서는 화면 밖에 있다
        sleepButton.tap()
        sleep(2)
        // 끝내기 전에 수유·잠이 모두 진행 중이어야 '하나는 닫혔다'가 끝내기의 결과다
        XCTAssert(label(beginsWith: "수유 중 ·").exists && label(beginsWith: "자는 중 ·").exists, "끝내기 전에 둘 다 진행 중이 아니다")
        shot("L1-app")

        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        // 잠금 화면과 같은 알림 센터를 위에서 끌어내려 연다(시뮬레이터에서는 잠그기 버튼을 누를 수 없다).
        let top = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.0))
        top.press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.7)))
        sleep(3)
        // 처음 한 번 시스템이 실시간 현황을 허용할지 묻는다(시스템 글자, U33).
        for label in ["허용", "Allow"] where springboard.buttons[label].exists && springboard.buttons[label].isHittable { springboard.buttons[label].tap(); sleep(2); break }   // 알림의 숨은 밀기 버튼도 '허용'이다
        shot("L2-lock-screen")
        // 활동이 둘이면 카드가 겹쳐 있어 처음 누르면 펼쳐진다.
        let ends = springboard.buttons.matching(identifier: "끝내기")
        XCTAssert(ends.firstMatch.waitForExistence(timeout: 5), "잠금 화면에 끝내기가 없다")
        // 카드가 겹쳐 있으면 첫 탭은 펼치기만 하고, 펼쳐져 있으면 그 카드를 끝낸다(시스템이 정한다).
        ends.firstMatch.tap()
        sleep(3)
        for label in ["허용", "Allow"] where springboard.buttons[label].exists && springboard.buttons[label].isHittable { springboard.buttons[label].tap(); sleep(2); break }   // 알림의 숨은 밀기 버튼도 '허용'이다
        shot("L3-lock-expanded")
        if ends.count >= 2 { ends.element(boundBy: 0).tap() }   // 펼쳐졌으면 위 카드를 끝낸다
        sleep(4)
        shot("L4-lock-after-end")

        // 알림 센터를 닫고 앱으로 돌아가면 끝낸 기록은 진행 중이 아니다(인텐트가 앱 프로세스에서 endNow를 불렀다).
        springboard.swipeUp()
        sleep(1)
        app.activate()
        sleep(2)
        shot("L5-app-after-end")
        // 시스템 화면 자동 조작은 회차마다 겹침·펼침이 달라 몇 개를 눌렀는지 정하지 못한다. 적어도 하나는 닫혀야 한다.
        // 닫기 규칙 자체는 단위 시험(R2·U14의 endNow)과 'care' 로그("끝내기 인텐트: 앱에서 실행")로 본다.
        let feedRunning = label(beginsWith: "수유 중 ·").exists, sleepRunning = label(beginsWith: "자는 중 ·").exists
        XCTAssertFalse(feedRunning && sleepRunning, "잠금 화면 끝내기로 하나도 닫히지 않았다")
    }
}
