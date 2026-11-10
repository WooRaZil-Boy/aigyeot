import XCTest

/// 디자인 v6 화면 캡처(시험이 아니라 캡처 도구, `SHOT_DIR`이 없으면 건너뛴다). 시안 화면과 같은 상태로 찍어 나란히 본다.
/// 라이트·다크는 시뮬레이터 모습을 바꿔 두 번 돌린다(`SHOT_SUFFIX`로 이름 뒤에 붙인다).
/// 실행: `TEST_RUNNER_SHOT_DIR=<폴더> TEST_RUNNER_SHOT_SUFFIX=-light xcodebuild test -scheme AigyeotScreens -only-testing:AigyeotUITests/DesignTour ...`
final class DesignTour: XCTestCase {
    private var app: XCUIApplication!
    private var dir: URL!
    private var suffix = ""

    override func setUpWithError() throws {
        let env = ProcessInfo.processInfo.environment
        guard let d = env["SHOT_DIR"] else { throw XCTSkip("SHOT_DIR 없음") }
        dir = URL(fileURLWithPath: d)
        suffix = env["SHOT_SUFFIX"] ?? ""
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["-demoSeed"]   // U50 시연 시드(지난 6일 + 오늘 장면)
        app.launch()
    }

    private func shot(_ name: String, settle: UInt32 = 2) {
        sleep(settle)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: dir.appendingPathComponent("\(name)\(suffix).png"))
    }

    /// 탭 이름 버튼. 내리면 iOS 26 탭 바가 작아져 숨으므로, 없으면 위로 끌어 내려 다시 펼친다.
    private func tab(_ name: String) {
        let b = app.buttons[name].firstMatch
        for _ in 0..<4 where !(b.exists && b.isHittable) { app.swipeDown() }
        if !b.exists { app.tabBars.buttons.firstMatch.tap(); sleep(1) }   // 작아진 탭 바(iOS 26)는 눌러야 펼쳐진다
        b.tap()
    }

    private func label(beginsWith s: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", s)).firstMatch
    }

    /// 연결 안 됨(기록만): 첫 안내 → 오늘 → 수유 중 → 타이머 → 기록 하루·고치기 시트 → 패턴 → 설정.
    func test_v6_기록만() {
        if app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10) {
            shot("01-intro", settle: 3)
            app.buttons["먼저 기록만 쓸게요"].tap()
        }
        XCTAssert(app.buttons["오늘"].waitForExistence(timeout: 5))
        shot("02-today")
        app.swipeUp()
        shot("02b-today-scrolled")
        app.swipeDown(); app.swipeDown()

        app.buttons["수유"].firstMatch.tap()
        shot("03-today-running")
        label(beginsWith: "수유 중").tap()
        XCTAssert(app.buttons["끝내기"].waitForExistence(timeout: 5))
        app.buttons["왼쪽"].tap()
        shot("04-timer")
        app.buttons["끝내기"].tap()

        tab("기록")
        shot("05-records-day")
        let feedRow = label(beginsWith: "수유 · ")
        if feedRow.waitForExistence(timeout: 3) {
            feedRow.tap()
            shot("06-sheet-edit")
            app.buttons["취소"].firstMatch.tap()
        }
        app.buttons["패턴"].firstMatch.tap()
        shot("07-records-pattern")
        let bars = label(beginsWith: "지난 7일 수유 간격")
        if bars.waitForExistence(timeout: 3) {   // 오늘 막대를 눌러 툴팁
            bars.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.7)).tap()
            shot("07a-records-tooltip", settle: 1)
        }
        app.swipeUp()
        shot("07b-records-pattern-scrolled")

        app.swipeDown(); app.swipeDown()   // 내리면 탭 바가 작아져(iOS 26) 탭 이름 버튼이 숨는다
        tab("설정")
        shot("08-settings")

        // 잠금 화면 Live Activity(시안 S.lock): 수유를 시작해 왼쪽을 고르고 알림 센터를 끌어내린다(시뮬레이터는 잠그기 버튼이 없다)
        tab("오늘")
        app.buttons["수유"].firstMatch.tap()
        label(beginsWith: "수유 중").tap()
        XCTAssert(app.buttons["왼쪽"].waitForExistence(timeout: 5))
        app.buttons["왼쪽"].tap()
        app.buttons["취소"].firstMatch.tap()
        sleep(2)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.0))
            .press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.7)))
        sleep(3)
        for label in ["허용", "Allow"] where springboard.buttons[label].exists && springboard.buttons[label].isHittable { springboard.buttons[label].tap(); sleep(2); break }
        shot("09-lock-live-activity")
        springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.99))
            .press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2)))
    }

    /// 시안 예시와 같은 리포트: 13:10–13:52(42분), 울음 1번(감지 2.4초), 자장가 → 듣기 틈 → 백색소음(자동 2차, 다시 울음), 쉼터 제안, 그쳤어요,
    /// 조향 0.8 → 2.1 → 0.9, 차가 한 일 4가지, 방법별 성적 넷. `minAgo`는 주행 시작이 몇 분 전인지.
    private func sendRichReport(_ car: FakeCar, minAgo: Int) {
        let s = Date().addingTimeInterval(TimeInterval(-minAgo * 60)), cry = s.addingTimeInterval(14 * 60), ep = FakeCar.ulid()
        func r(_ d: Date) -> String { FakeCar.rfc(d) }
        car.send("report_summary", [
            "session_id": FakeCar.ulid(), "started_at": r(s), "ended_at": r(s.addingTimeInterval(42 * 60)), "simulated": false, "output_route": "rear_zone",
            "timeline": [["episode_id": ep, "trigger": "cry_alarm", "started_at": r(cry), "alarm_latency_s": 2.4,
                          "sound": ["method": "lullaby", "label": "자장가"], "play_s": 112,
                          "plays": [["purpose": "auto_first", "method": "lullaby", "song_id": NSNull(), "started_at": r(cry.addingTimeInterval(2)), "ended_at": r(cry.addingTimeInterval(58)), "play_s": 56],
                                    ["purpose": "auto_second", "method": "white_noise", "song_id": NSNull(), "started_at": r(cry.addingTimeInterval(66)), "ended_at": r(cry.addingTimeInterval(122)), "play_s": 56]],
                          "result": "failure", "excluded_reason": NSNull(), "recried": true,
                          "second": ["action": "other_sound", "sound": ["method": "white_noise", "label": "백색소음"], "result": "success"],
                          "stop": ["handoff": true, "reason": "attempt_limit", "mode": "rest_offer", "rest_name": "○○휴게소", "waypoint_added": false, "guardian_notified": true, "told_driver": true],
                          "total_cry_s": 192]],
            "pre_cry_context": [["episode_id": ep, "window_s": 30, "speed_kmh_avg": 58.2, "speed_kmh_max": 71.0, "brake_presses": 2,
                                 "steering_reversals": 1, "turn_signal_used": false, "gear": "D", "cabin_temp_c": 24.5, "window_open": false, "playing": false]],
            "steering": ["available": true, "baseline_per_min": 0.8, "during_cry_per_min": 2.1, "after_response_per_min": 0.9, "during_cry_z": 2.2, "unavailable_reason": NSNull()],
            "car_actions": [["action": "rear_playback", "count": 2, "total_s": 112], ["action": "seat_heater_off", "count": 1, "total_s": NSNull()],
                            ["action": "rear_window_lock", "count": 1, "total_s": NSNull()], ["action": "ambient_dim", "count": 1, "total_s": NSNull()]],
            "controls": [["api": "Seat.setHvacSeatTemperature", "target": "ROW_2_LEFT", "attempts": 1, "succeeded": 1, "failed": 0, "retries": 0]],
            "method_stats": [["method": "lullaby", "label": "자장가", "successes": 6, "attempts": 7], ["method": "womb", "label": "뱃속 소리", "successes": 3, "attempts": 4],
                             ["method": "white_noise", "label": "백색소음", "successes": 2, "attempts": 6], ["method": "parent_song", "label": "부모 노래", "successes": 0, "attempts": 2]],
        ])
    }

    private func allowSystemAlert() {
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow", "허용", "OK", "확인"] where sb.buttons[label].waitForExistence(timeout: 2) { sb.buttons[label].tap(); return }
    }

    /// 차와 연결한 화면: owner(오늘·차 탭·노래 녹음·리포트·기록·설정) → 설정에서 연결 끊기 → 초대 코드로 guardian(오늘 인계 알림·리포트·설정·보호자).
    /// 차 등록은 한 번만 한다(중계는 IP당 시간당 5번, G7). 새로 깐 앱·지운 키체인에서 돌린다.
    /// 중계: `TEST_RUNNER_RELAY_URL=http://127.0.0.1:8787`(앱 debug 주소와 같은 중계).
    func test_v6_연결() {
        let car = FakeCar()
        car.register()
        XCTAssert(app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10))
        app.buttons["먼저 기록만 쓸게요"].tap()
        app.open(URL(string: "aigyeot://pair?code=\(car.pairCode())")!)
        XCTAssert(app.buttons["연결하기"].waitForExistence(timeout: 5))
        shot("20-confirm")
        app.buttons["연결하기"].tap()
        XCTAssert(app.buttons["차"].firstMatch.waitForExistence(timeout: 10))
        car.sendFeedingState(lastMinAgo: 81, windowInMin: 63)   // 마지막 수유 1시간 21분 전, 다음 시간대 = 시작 + 3시간 × 0.8
        sendRichReport(car, minAgo: 63)
        car.sendReport(minAgo: 60 * 24 + 30)
        sleep(4)
        if app.buttons["닫기"].exists { app.buttons["닫기"].firstMatch.tap() }   // '연결됐어요' 띠
        shot("21-owner-today", settle: 3)
        app.swipeUp()
        shot("22-owner-today-2", settle: 3)
        app.swipeUp()
        shot("23-owner-today-3")
        tab("차")
        shot("24-owner-car", settle: 3)
        let rec = app.buttons["녹음하기"].firstMatch
        if rec.waitForExistence(timeout: 3) {
            rec.tap()
            allowSystemAlert()
            shot("25-song-record")
            app.buttons["취소"].firstMatch.tap()
        }
        let report = label(beginsWith: "11월")
        for _ in 0..<3 where !(report.exists && report.isHittable) { app.swipeUp() }
        report.tap()
        shot("26-report", settle: 3)
        app.swipeUp()
        shot("27-report-2", settle: 3)
        app.swipeUp()
        shot("28-report-3")
        app.navigationBars.buttons.firstMatch.tap()
        tab("기록")
        shot("29-owner-records")
        // 타임라인의 차 기록을 누르면 그 리포트가 열린다(목록 안 단추 → 내비게이션)
        label(beginsWith: "차에서 울음 · ").tap()
        XCTAssert(app.navigationBars["주행 리포트"].waitForExistence(timeout: 5), "타임라인 차 기록에서 리포트가 열리지 않았다")
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["패턴"].firstMatch.tap()
        app.swipeUp(); app.swipeUp()
        shot("30-owner-pattern-car")
        tab("설정")
        shot("31-owner-settings")
        label(beginsWith: "보호자").tap()   // 줄 글은 "보호자, 1명"
        let invite = app.buttons["초대 만들기"].firstMatch
        if invite.waitForExistence(timeout: 5) { invite.tap(); sleep(2) }
        shot("31b-owner-guardians")
        app.navigationBars.buttons.firstMatch.tap()

        // 연결 끊기 → guardian
        let disconnect = app.buttons["연결 끊기"].firstMatch
        for _ in 0..<3 where !(disconnect.exists && disconnect.isHittable) { app.swipeUp() }
        disconnect.tap()
        let confirm = app.buttons.matching(identifier: "연결 끊기").element(boundBy: 1)
        if confirm.waitForExistence(timeout: 3) { confirm.tap() } else { app.sheets.buttons["연결 끊기"].firstMatch.tap() }
        XCTAssert(app.buttons["오늘"].firstMatch.waitForExistence(timeout: 10))
        sleep(2)
        let connect = label(beginsWith: "차와 연결하기")
        for _ in 0..<4 where !(connect.exists && connect.isHittable) { app.swipeUp() }
        connect.tap()
        let field = app.textFields.firstMatch
        XCTAssert(field.waitForExistence(timeout: 5))
        shot("35-connect")
        field.tap(); field.typeText(car.inviteCode())
        app.buttons["확인"].tap()
        allowSystemAlert()   // guardian은 연결 뒤 알림 권한을 묻는다(G13)
        XCTAssert(app.buttons["설정"].firstMatch.waitForExistence(timeout: 10))
        sendRichReport(car, minAgo: 63)
        car.sendHandoff(minAgo: 0)
        sleep(2)
        shot("32a-guardian-banner")
        sleep(3)
        if app.buttons["닫기"].exists { app.buttons["닫기"].firstMatch.tap() }
        shot("32-guardian-today", settle: 3)
        app.swipeUp()
        shot("33-guardian-today-2")
        tab("설정")
        shot("34-guardian-settings")
        label(beginsWith: "보호자").tap()
        shot("34b-guardian-alerts-toggle")
    }

    /// 3D 측정용(캡처 아님): 첫 안내의 3D 차를 6초 동안 손가락으로 돌린 뒤 8초 둔다. 맥에서 같은 때 앱의 CPU·메모리를 잰다.
    /// 움직임 확인용(화면 녹화와 함께 돌린다): 첫 화면 올라옴 → 수유 시작(진행 중 판·빛) → 타이머(확대 전환·숫자 굴림) → 끝내기
    /// → 탭 바꾸기(한 줄씩 올라옴) → 하루·패턴 → 설정 → 오늘. 장면 사이 2초.
    func test_v6_모션() {
        if app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10) {
            sleep(2)
            app.buttons["먼저 기록만 쓸게요"].tap()
        }
        XCTAssert(app.buttons["오늘"].waitForExistence(timeout: 5))
        sleep(2)
        app.buttons["수유"].firstMatch.tap()
        sleep(3)
        label(beginsWith: "수유 중").tap()
        XCTAssert(app.buttons["끝내기"].waitForExistence(timeout: 5))
        sleep(4)
        app.buttons["끝내기"].tap()
        sleep(3)
        for t in ["기록", "패턴", "설정", "오늘"] {
            if t == "패턴" { app.buttons["패턴"].firstMatch.tap() } else { tab(t) }
            sleep(2)
        }
    }

    func test_v6_3d_돌리기() {
        XCTAssert(app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10))
        sleep(4)   // 대기(정지 그림)
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.17))
        for i in 0..<6 {
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: i % 2 == 0 ? 0.8 : 0.2, dy: 0.17))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        shot("40-3d-rotated")
        sleep(8)   // 감쇠가 끝나고 정지 그림으로 바뀐 뒤
    }
}
