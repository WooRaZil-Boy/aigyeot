import XCTest

/// 화면 캡처 순회(시험이 아니라 캡처 도구). `SHOT_DIR`이 없으면 건너뛴다.
/// 앱은 캡처용 가짜 중계 주소로 빌드한다(`RELAY_BASE_URL=http://localhost:8799`). 연결 코드 123456 → owner, 초대 코드 7K3QM2XW9D → guardian.
/// 실행: `TEST_RUNNER_SHOT_DIR=<폴더> xcodebuild test -scheme AigyeotScreens ...`
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

    /// 가짜 중계가 이 뒤의 토큰 요청에 401을 주게 한다(차 쪽에서 끊긴 것 흉내).
    private func expireToken() {
        var req = URLRequest(url: URL(string: "http://localhost:8799/stub/expire")!)
        req.httpMethod = "POST"
        let done = expectation(description: "expire")
        URLSession.shared.dataTask(with: req) { _, _, _ in done.fulfill() }.resume()
        wait(for: [done], timeout: 5)
    }

    private func label(beginsWith s: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", s)).firstMatch
    }

    /// 새 화면(phone-care-plan 5장): 연결 안 됨 → owner → 앱에서 끊기 → guardian → 차 쪽 끊김.
    func test_새_화면() {
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
        shot("07-unpaired-today")

        // 설정: 아이 별명, 잠·기저귀 켜기
        tab("설정")
        let nick = app.textFields.firstMatch   // 설정의 입력칸은 별명 하나다
        nick.tap()
        if app.keyboards.firstMatch.waitForExistence(timeout: 2) { app.typeText("콩이\n") }   // 큰 글자에서는 칸이 줄바꿈돼 초점이 안 갈 수 있다
        let toggle = app.switches.firstMatch
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        shot("08-unpaired-settings")
        app.swipeUp()
        app.buttons["정보·라이선스"].tap()
        shot("08b-about")
        tab("설정")   // 같은 탭을 다시 누르면 첫 화면으로 돌아간다
        tab("오늘")
        app.buttons["잠"].tap()
        shot("09-unpaired-today-sleep")

        // 기록: 하루 → 지우기·되돌리기 → 패턴
        tab("기록")
        shot("10-records-day")
        let row = app.cells.element(boundBy: 0)
        row.swipeLeft()
        if app.buttons["지우기"].waitForExistence(timeout: 2) { app.buttons["지우기"].tap(); shot("11-records-undo"); app.buttons["되돌리기"].tap() }
        app.buttons["패턴"].tap()
        shot("12-records-pattern")
        app.swipeUp(); shot("13-records-pattern-2")
        app.buttons["하루"].tap()

        // 차 탭(연결 안 됨) → 연결 흐름 → owner
        tab("차")
        shot("14-unpaired-car")
        app.buttons["차와 연결하기"].firstMatch.tap()
        XCTAssert(app.buttons["QR 찍기"].waitForExistence(timeout: 5))
        shot("15-connect-from-tabs")
        app.open(URL(string: "aigyeot://pair?code=123456")!)
        XCTAssert(app.buttons["연결하기"].waitForExistence(timeout: 5))
        app.buttons["연결하기"].tap()
        XCTAssert(app.buttons["차"].firstMatch.waitForExistence(timeout: 10))
        sleep(2)
        shot("16-owner-today")
        app.swipeUp(); shot("17-owner-today-2")
        tab("기록")
        shot("18-owner-records")
        tab("차")
        shot("19-owner-car")
        app.swipeUp(); shot("20-owner-car-2")
        tab("설정")
        shot("21-owner-settings")
        app.buttons["보호자"].tap()
        shot("22-owner-guardians")
        tab("설정")
        app.swipeUp()   // 큰 글자에서는 화면 밖에 있다
        app.buttons["연결 끊기"].firstMatch.tap()
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
        field.tap(); field.typeText("7K3QM2XW9D")
        app.buttons["확인"].tap()
        allowSystemAlert()
        XCTAssert(app.buttons["기록"].firstMatch.waitForExistence(timeout: 10))
        sleep(2)
        shot("25-guardian-today")
        app.swipeUp(); shot("26-guardian-today-2")
        tab("기록")
        shot("27-guardian-records")
        app.buttons["지난 기록 적기"].tap()
        shot("28-guardian-quick")
        app.swipeDown(velocity: .fast)

        // 차 쪽에서 끊김(401) → '연결이 끊겼어요' → '기록만 계속 쓸게요'
        expireToken()
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssert(app.buttons["기록만 계속 쓸게요"].waitForExistence(timeout: 30))
        shot("29-gone")
        app.buttons["기록만 계속 쓸게요"].tap()
        shot("30-after-gone")
    }

    /// 잠금 화면 진행 표시(C6): 수유·잠 시작 → 잠그기 → 잠금 화면 '끝내기' → 앱에서 닫혔는지.
    func test_잠금_화면() {
        XCTAssert(app.buttons["먼저 기록만 쓸게요"].waitForExistence(timeout: 10))
        app.buttons["먼저 기록만 쓸게요"].tap()
        tab("설정")
        app.switches.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()   // 잠·기저귀도 기록하기
        tab("오늘")
        app.buttons["수유 시작"].tap()
        label(beginsWith: "수유 중").tap()
        XCTAssert(app.buttons["왼쪽"].waitForExistence(timeout: 5))
        app.buttons["왼쪽"].tap()
        // 타이머 시트를 위에서 아래로 끌어 닫는다.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.04))
            .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.95)))
        XCTAssert(app.buttons["잠"].waitForExistence(timeout: 5))
        sleep(1)
        app.buttons["잠"].tap()
        sleep(2)
        shot("L1-app")

        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        // 잠금 화면과 같은 알림 센터를 위에서 끌어내려 연다(시뮬레이터에서는 잠그기 버튼을 누를 수 없다).
        let top = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.0))
        top.press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.7)))
        sleep(3)
        // 처음 한 번 시스템이 실시간 현황을 허용할지 묻는다(시스템 글자, U33).
        for label in ["허용", "Allow"] where springboard.buttons[label].exists { springboard.buttons[label].tap(); sleep(2); break }
        shot("L2-lock-screen")
        // 활동이 둘이면 카드가 겹쳐 있어 처음 누르면 펼쳐진다.
        let ends = springboard.buttons.matching(identifier: "끝내기")
        XCTAssert(ends.firstMatch.waitForExistence(timeout: 5), "잠금 화면에 끝내기가 없다")
        // 카드가 겹쳐 있으면 첫 탭은 펼치기만 하고, 펼쳐져 있으면 그 카드를 끝낸다(시스템이 정한다).
        ends.firstMatch.tap()
        sleep(3)
        for label in ["허용", "Allow"] where springboard.buttons[label].exists { springboard.buttons[label].tap(); sleep(2); break }
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

    func test_현재_화면() {
        // 소개 → 연결
        XCTAssert(app.buttons["시작하기"].waitForExistence(timeout: 10))
        shot("01-intro")
        app.buttons["시작하기"].tap()
        shot("02-connect")

        app.buttons["QR 찍기"].tap()
        allowSystemAlert()
        shot("03-scan")
        app.buttons["코드로 입력"].tap()

        // 링크로 온 코드 → 확인 화면
        app.open(URL(string: "aigyeot://pair?code=123456")!)
        XCTAssert(app.buttons["연결하기"].waitForExistence(timeout: 5))
        shot("04-confirm-pair")
        app.buttons["연결하기"].tap()

        // owner 홈
        XCTAssert(app.buttons["수유 시작"].waitForExistence(timeout: 10))
        shot("05-owner-home")
        app.buttons["수유 시작"].tap()
        shot("06-owner-home-fed")

        // 우리 노래 → 녹음 시트(멈춘 뒤 모습까지)
        tab("우리 노래")
        shot("07-songs")
        app.buttons["녹음하기"].tap()
        let timer = app.staticTexts["0:00 / 3:00"]
        XCTAssert(timer.waitForExistence(timeout: 5))
        shot("08-record-idle")
        // 녹음 중에는 시간 글자가 바뀌므로 버튼 자리를 화면 좌표로 한 번만 잡는다.
        let f = timer.frame
        let recordButton = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: f.midX, dy: f.maxY + 56))
        recordButton.tap()
        allowSystemAlert()
        sleep(7)
        shot("09-record-recording")
        recordButton.tap()
        shot("10-record-stopped")
        app.staticTexts["노래 녹음"].swipeDown(velocity: .fast)

        // 리포트 목록 → 상세
        tab("리포트")
        shot("11-reports")
        app.collectionViews.cells.element(boundBy: 0).tap()
        shot("12-report-detail-1")
        app.swipeUp(); shot("13-report-detail-2")
        app.swipeUp(); shot("14-report-detail-3")

        // 보호자(owner)
        tab("보호자")
        shot("15-owner-guardians")
        app.buttons["초대 만들기"].tap()
        shot("16-owner-invite")

        // 설정 → 연결 끊기
        tab("설정")
        shot("17-owner-settings")
        app.buttons["연결 끊기"].firstMatch.tap()
        shot("18-disconnect-dialog")
        app.sheets.buttons["연결 끊기"].tap()

        // guardian: 초대 코드를 직접 쳐서 연결
        let field = app.textFields.firstMatch
        XCTAssert(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("7K3QM2XW9D")
        shot("19-connect-typed")
        app.buttons["확인"].tap()
        allowSystemAlert()
        XCTAssert(app.buttons["리포트"].firstMatch.waitForExistence(timeout: 10))
        shot("20-guardian-home")
        app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH '아기가 계속 울어서'")).firstMatch.tap()
        shot("21-guardian-alert-detail")
        tab("리포트")
        shot("22-guardian-reports")
        tab("보호자")
        shot("23-guardian-toggle")
        tab("설정")
        shot("24-guardian-settings")
        app.buttons["연결 끊기"].firstMatch.tap()
        app.sheets.buttons["연결 끊기"].tap()
    }
}
