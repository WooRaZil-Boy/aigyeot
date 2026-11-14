import XCTest

/// 화면 캡처(시험이 아니라 캡처 도구, `SHOT_DIR`이 없으면 건너뛴다). 디자인과 같은 상태로 찍어 나란히 본다.
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
        // 시연 시드(지난 6일 + 오늘 장면). 기록 시트 캡처는 빈 상태부터 찍으려고 시드 없이 연다
        app.launchArguments = name.contains("v8") ? [] : ["-demoSeed"]
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

    /// 기록 시트와 빈 상태 셋. 시드 없이: 오늘 빈 판 → 기록 빈 날 → 지난 기록 적기(첫 기록 도움말·종류 칸)
    /// → 수유(왼쪽·젖병 양)·시작 줄 펼침 → 저장(새 줄 강조) → 잠·기저귀·메모 → 고치기(끝 줄 펼침) → 패턴 빈 상태.
    func test_v8_시트() {
        XCTAssert(app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10))
        shot("v8-00-intro", settle: 3)   // 첫 안내 안 B와 '자세히 보기' 시트
        app.buttons["오가는 정보 자세히 보기"].tap()
        XCTAssert(app.staticTexts["오가는 정보"].waitForExistence(timeout: 5))
        shot("v8-00b-intro-sheet")
        app.buttons["닫기"].firstMatch.tap()
        sleep(1)
        app.buttons["먼저 기록만 쓸게요"].tap()
        XCTAssert(app.buttons["수유 시작하기"].waitForExistence(timeout: 5))
        shot("v8-01-today-empty")
        tab("기록")
        let past = app.buttons.matching(NSPredicate(format: "label == %@", "지난 기록 적기"))
        XCTAssert(past.firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(past.count, 1, "빈 날에는 가운데 지난 기록 적기 하나만 보인다")
        shot("v8-02-day-empty")
        past.firstMatch.tap()
        XCTAssert(app.staticTexts["어떻게 먹였어요?"].waitForExistence(timeout: 5))
        shot("v8-03-sheet-past-first")
        app.buttons["왼쪽"].firstMatch.tap()
        shot("v8-04-sheet-feed-left", settle: 1)
        app.buttons["젖병"].firstMatch.tap()
        let amount = app.descendants(matching: .any)["양"].firstMatch
        // 양 칸은 한 요소(위아래 쓸기)라 오른쪽 ＋ 자리를 눌러 120ml로
        if amount.waitForExistence(timeout: 3) { for _ in 0..<12 { amount.coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).tap() } }
        shot("v8-05-sheet-bottle", settle: 1)
        app.buttons["시작"].firstMatch.tap()
        shot("v8-06-sheet-start-open", settle: 1)
        save.tap()
        XCTAssert(app.staticTexts["어떻게 먹였어요?"].waitForNonExistence(timeout: 5))
        shot("v8-07-day-fresh", settle: 1)

        tab("설정")
        let toggle = app.switches.firstMatch
        if toggle.waitForExistence(timeout: 5), toggle.value as? String != "1" { toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap() }
        tab("기록")
        let pastRow = app.buttons["지난 기록 적기"].firstMatch
        for _ in 0..<4 where !(pastRow.exists && pastRow.isHittable) { app.swipeUp() }
        pastRow.tap()
        XCTAssert(app.buttons["잠"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["잠"].firstMatch.tap()
        shot("v8-08-sheet-sleep", settle: 1)
        app.buttons["기저귀"].firstMatch.tap()
        shot("v8-09-sheet-diaper", settle: 1)
        app.buttons["메모"].firstMatch.tap()
        shot("v8-10-sheet-note", settle: 1)
        app.buttons["취소"].firstMatch.tap()
        sleep(1)

        let row = label(beginsWith: "수유 · ")
        for _ in 0..<4 where !(row.exists && row.isHittable) { app.swipeDown() }
        if row.waitForExistence(timeout: 3) {
            row.tap()
            XCTAssert(app.staticTexts["고치는 중"].waitForExistence(timeout: 5))
            shot("v8-11-sheet-edit")
            app.buttons["끝"].firstMatch.tap()
            shot("v8-12-sheet-edit-end", settle: 1)
            app.buttons["취소"].firstMatch.tap()
            sleep(1)
        }
        app.buttons["패턴"].firstMatch.tap()
        shot("v8-13-pattern-empty")
    }

    /// 3D 준비 표시: 오늘 탭 아래 '차와 연결하기' 카드 자리까지 내려 둔 채 차와 연결하면, 그 자리에 차 카드가 들어와
    /// 이 프로세스에서 처음 3D 모델을 읽는다(시뮬레이터 약 3초). 고리('3D 차 준비 중')는 붙이고 0.8초 뒤에 나타나 첫 그림까지만 있다.
    /// XCUITest는 누를 때마다 앱이 쉴 때까지 기다리는데 RealityKit 준비가 그동안 주 스레드를 잡아 고리가 있는 동안 확인하기 어렵다.
    /// 나타남은 스크립트의 화면 녹화(화면 녹화 파일)로 보고, 여기서는 첫 그림 뒤 준비 표시가 남지 않고 3D 무대가 있는지를 못박는다.
    func test_v8c_3d_준비() {
        let car = FakeCar()
        car.register()
        XCTAssert(app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10))
        app.buttons["먼저 기록만 쓸게요"].tap()
        app.open(URL(string: "aigyeot://pair?code=\(car.pairCode())")!)
        XCTAssert(app.buttons["연결하기"].waitForExistence(timeout: 5))
        app.buttons["연결하기"].tap()
        // 연결되면 오늘 탭 아래 차 카드가 처음 3D를 읽기 시작한다. 바로 내려 그 카드를 화면에 둔다(링크를 열면 앱이 다시 앞으로 와 미리 내려 둘 수 없다)
        let tile = label(beginsWith: "우리 차 3D 그림")
        for _ in 0..<4 where !(tile.exists && tile.isHittable) { app.swipeUp() }
        shot("v8c-01-ring", settle: 0)
        let loading = app.descendants(matching: .any)["3D 차 준비 중"]
        XCTAssert(loading.waitForNonExistence(timeout: 20), "첫 그림 뒤에도 준비 표시가 남았다")
        shot("v8c-02-car", settle: 1)
        XCTAssert(app.descendants(matching: .any)["우리 차 3D 그림"].firstMatch.exists, "차 카드 3D 무대가 없다")
    }

    /// 큰 글자에서 끝 줄을 바꿔(시작이 지금이라 끝이 미래·다음 날) 접고 본문 맨 위로 굴려 끝 줄이 화면 밖일 때 저장하면,
    /// 끝 줄이 다시 펼쳐지고 그 자리로 굴러 오류 글이 보인다. 스크립트가 글자 크기를 접근성 크기로 바꾼 뒤 돌린다.
    func test_v8_시트_오류_굴림() {
        XCTAssert(app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10))
        app.buttons["먼저 기록만 쓸게요"].tap()
        let feed = app.buttons["수유"].firstMatch
        XCTAssert(feed.waitForExistence(timeout: 5))
        for _ in 0..<4 where !feed.isHittable { app.swipeUp() }
        sleep(1)
        feed.press(forDuration: 1.0)   // 길게 누르면 빠른 기록 시트
        let body = app.scrollViews.firstMatch
        func reach(_ e: XCUIElement) { for _ in 0..<6 where !(e.exists && e.isHittable) { body.swipeUp() } }
        let endRow = app.buttons["끝"].firstMatch
        XCTAssert(endRow.waitForExistence(timeout: 5), "기록 시트가 뜨지 않았다")
        reach(endRow)
        endRow.tap()
        let pill = app.buttons["끝 시각 고르기"].firstMatch
        reach(pill)
        pill.tap()
        let wheels = app.pickerWheels
        XCTAssert(wheels.firstMatch.waitForExistence(timeout: 3))
        let hour = wheels.element(boundBy: wheels.count >= 3 ? 1 : 0)
        reach(hour)
        hour.swipeUp()   // 시를 돌려 끝을 바꾼다(시작이 지금이라 끝은 미래·다음 날 → 끝 줄 탓)
        sleep(1)
        endRow.tap()   // 접는다
        for _ in 0..<6 { body.swipeDown() }   // 본문 맨 위로
        sleep(1)
        XCTAssertFalse(endRow.isHittable, "재현 조건이 안 됐다: 저장 전에 끝 줄이 이미 보인다")
        shot("v8-14-ax-before-save")
        save.tap()
        let err = app.staticTexts.matching(NSPredicate(format: "label IN %@", ["앞으로의 시각은 적을 수 없어요", "수유는 3시간까지 적을 수 있어요"])).firstMatch
        XCTAssert(err.waitForExistence(timeout: 3), "오류 글이 없다")
        sleep(1)
        shot("v8-15-ax-error-scrolled")
        XCTAssert(err.isHittable, "오류 줄이 본문 밖에 남았다")
    }

    /// 기록 시트의 저장 단추(글이 저장될 것을 말한다).
    private var save: XCUIElement { app.buttons.matching(NSPredicate(format: "label ENDSWITH %@", "저장하기")).firstMatch }

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
            let start = app.buttons["시작"].firstMatch   // 기록 시트 v8: 줄을 펼치고 시각 알약 → 그 자리 바퀴
            if start.exists {
                start.tap()
                let time = label(beginsWith: "시각 고르기")
                if time.waitForExistence(timeout: 3) { time.tap() }
                shot("06b-sheet-time", settle: 1)
            }
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

        // 잠금 화면 Live Activity: 수유를 시작해 왼쪽을 고르고 알림 센터를 끌어내린다(시뮬레이터는 잠그기 버튼이 없다)
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

    /// 예시 리포트: 13:10–13:52(42분), 울음 1번(감지 2.4초), 자장가 → 재생 사이 → 백색소음(자동 2차, 다시 울음), 쉼터 제안, 그쳤어요,
    /// 조향 0.8 → 2.1 → 0.9, 차가 한 일 4가지, 소리별 성적 넷. `minAgo`는 주행 시작이 몇 분 전인지.
    private func sendRichReport(_ car: FakeCar, minAgo: Int) {
        let s = Date().addingTimeInterval(TimeInterval(-minAgo * 60)), cry = s.addingTimeInterval(14 * 60), ep = FakeCar.ulid()
        func r(_ d: Date) -> String { FakeCar.rfc(d) }
        car.send("report_summary", [
            "session_id": FakeCar.ulid(), "started_at": r(s), "ended_at": r(s.addingTimeInterval(42 * 60)), "simulated": false, "output_route": "rear_zone",
            "timeline": [["episode_id": ep, "trigger": "cry_alarm", "started_at": r(cry), "alarm_latency_s": 2.4,
                          "sound": ["method": "lullaby", "label": "자장가"], "play_s": 112,
                          "plays": [["purpose": "auto_first", "method": "lullaby", "song_id": NSNull(), "started_at": r(cry.addingTimeInterval(2)),
                              "ended_at": r(cry.addingTimeInterval(58)), "play_s": 56],
                                    ["purpose": "auto_second", "method": "white_noise", "song_id": NSNull(),
                                        "started_at": r(cry.addingTimeInterval(66)), "ended_at": r(cry.addingTimeInterval(122)), "play_s": 56]],
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
                             ["method": "white_noise", "label": "백색소음", "successes": 2, "attempts": 6], ["method": "parent_song", "label": "우리 노래", "successes": 0, "attempts": 2]]
        ])
    }

    private func allowSystemAlert() {
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow", "허용", "OK", "확인"] where sb.buttons[label].waitForExistence(timeout: 2) { sb.buttons[label].tap(); return }
    }

    /// 차와 연결한 화면: owner(오늘·차 탭·노래 녹음·리포트·기록·설정) → 설정에서 연결 끊기 → 초대 코드로 guardian(오늘 인계 알림·리포트·설정·보호자).
    /// 차 등록은 한 번만 한다(중계는 IP당 시간당 5번). 새로 깐 앱·지운 키체인에서 돌린다.
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
        let invite = app.buttons["초대 코드 만들기"].firstMatch
        if invite.waitForExistence(timeout: 5) { invite.tap(); sleep(2) }
        shot("31b-owner-guardians")
        app.navigationBars.buttons.firstMatch.tap()

        // 연결 끊기 → guardian
        let disconnect = app.buttons["연결 끊기"].firstMatch
        for _ in 0..<3 where !(disconnect.exists && disconnect.isHittable) { app.swipeUp() }
        disconnect.tap()
        let confirm = app.buttons["끊기"].firstMatch   // 확인 창 주 단추
        XCTAssert(confirm.waitForExistence(timeout: 3)); confirm.tap()
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
        allowSystemAlert()   // guardian은 연결 뒤 알림 권한을 묻는다
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
