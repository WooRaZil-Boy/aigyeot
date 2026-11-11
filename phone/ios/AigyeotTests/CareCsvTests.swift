import XCTest
@testable import Aigyeot

/// 기록 가져오기(phone-care-plan U64~U69). 시험 이름은 13장 그대로다.
/// 예제는 복사하지 않고 공용 `shared/src/test/resources/care/import/`를 읽는다(안드로이드와 같은 파일·같은 결과, U69).
@MainActor
final class CareCsvTests: XCTestCase {
    static let dir = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("shared/src/test/resources/care/import")
    /// U69 `now` = 2026-11-06T15:00:00+09:00
    static let now: Int64 = 1_793_944_800_000

    func text(_ name: String) throws -> String {
        guard case .text(let t) = CareCsv.read(try Data(contentsOf: Self.dir.appendingPathComponent(name + ".csv"))) else {
            throw XCTSkip("읽지 못함 \(name)")
        }
        return t
    }

    func existing() throws -> CareFile { try CareCoding.decode(Data(contentsOf: Self.dir.appendingPathComponent("existing.json"))) }

    func parse(_ name: String, profile: String? = nil, existing events: [CareEvent]? = nil) throws -> ImportDraft {
        CareCsv.parse(try text(name), existing: try events ?? existing().events, now: Self.now, profile: profile)
    }

    /// 기대 JSON(U69)과 글자까지 견준다. 행은 createdAt·updatedAt을 뺀 칸 전부.
    func assertExpected(_ name: String, file: StaticString = #filePath, line: UInt = #line) throws {
        let expected = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: Self.dir.appendingPathComponent(name + ".expected.json"))) as? [String: Any])
        let draft = try parse(name, profile: expected["profile"] as? String)   // profile은 입력값(U69 11-09)
        XCTAssertEqual(draft.format.rawValue, expected["format"] as? String, name, file: file, line: line)
        XCTAssertEqual(draft.profiles, expected["profiles"] as? [String], name, file: file, line: line)
        let rows = try draft.rows.map { e -> NSDictionary in
            var d = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(e)) as? [String: Any])
            d["createdAt"] = nil; d["updatedAt"] = nil
            return d as NSDictionary
        }
        let want = try XCTUnwrap(expected["rows"] as? [[String: Any]]).map { $0 as NSDictionary }
        XCTAssertEqual(rows.count, want.count, name, file: file, line: line)
        for (i, (a, b)) in zip(rows, want).enumerated() { XCTAssertEqual(a, b, "\(name) 행 \(i)", file: file, line: line) }
        let s = draft.skipped
        XCTAssertEqual(["unsupported": s.unsupported, "unreadable": s.unreadable, "future": s.future, "old": s.old, "duplicate": s.duplicate] as NSDictionary,
                       expected["skipped"] as? NSDictionary, name, file: file, line: line)
        XCTAssertTrue(draft.rows.allSatisfy { $0.createdAt == Self.now && $0.updatedAt == Self.now && $0.source == .imported }, name, file: file, line: line)
    }

    /// 예제의 기록을 담은 앱(owner, 연결됨). 시계는 U69 now.
    func kit(creds: Credentials? = Kit.owner) throws -> Kit {
        let k = Kit(creds: creds)
        k.careStore.value = try existing()
        k.clock = CareTime.date(Self.now)
        k.restart()
        return k
    }

    var queued: (Kit) -> [OutboxItem] = { $0.app.state.state.outbox.filter { $0.type == MessageType.feedingStarted } }

    // MARK: U65

    func test_헤더로_형식을_정하고_모르는_헤더는_거부한다() async throws {
        XCTAssertEqual(try parse("aigyeot").format, .aigyeot)
        XCTAssertEqual(try parse("huckleberry").format, .huckleberry)
        XCTAssertEqual(try parse("nara").format, .nara)
        XCTAssertEqual(try parse("unknown"), ImportDraft(format: .unknown))   // 열을 추측하지 않는다
        // 칸 이름은 앞뒤 공백을 지우고 대소문자 없이 견준다. Nara를 Huckleberry보다 먼저 본다.
        XCTAssertEqual(CareCsv.detect([" KIND", "Start ", "end", "END_UNKNOWN", "side", "amount_ml", "diaper", "note"].map { $0.trimmingCharacters(in: .whitespaces).lowercased() }), .aigyeot)
        XCTAssertEqual(CareCsv.parse(" TYPE , start date/time ,Start,End,Duration\n", existing: [], now: Self.now).format, .nara)
        XCTAssertEqual(CareCsv.parse("Type,Start,End\n", existing: [], now: Self.now).format, .unknown)
        XCTAssertEqual(CareCsv.parse("kind,start,end\n", existing: [], now: Self.now).format, .unknown)   // 아이곁 헤더는 칸이 모두 같아야 한다
        XCTAssertEqual(CareCsv.parse("", existing: [], now: Self.now).format, .unknown)
        // 헤더 앞의 빈 줄·공백뿐인 줄은 건너뛰고 첫 레코드를 헤더로 본다(안드로이드와 같다).
        let row = "NOTE,2026-11-06T12:00:00+09:00,,false,,,,EXAMPLE"
        let lead = CareCsv.parse("\r\n \n,,\n" + CareText.csvHeader + "\r\n" + row + "\r\n", existing: [], now: Self.now)
        XCTAssertEqual(lead.format, .aigyeot)
        XCTAssertEqual(lead.rows.map(\.note), ["EXAMPLE"])
        // 화면: 모르는 형식은 import.unreadable로 끝낸다.
        let k = try kit()
        let vm = k.app.settings()
        let unknown = await vm.loaded(.text(try text("unknown")))
        XCTAssertEqual(unknown, .failed("이 파일은 읽을 수 없어요. 아이곁·Huckleberry·Nara에서 내보낸 CSV인지 확인해 주세요."))
        let notUtf8 = await vm.loaded(.unreadable)
        XCTAssertEqual(notUtf8, .failed(CareText.importUnreadable))
    }

    /// U65 11-09 보충: 따옴표가 닫히지 않은 채 파일이 끝나면 뒤 기록이 메모 한 칸에 삼켜지므로 파일째 읽지 않는다(import.unreadable).
    func test_닫히지_않은_따옴표가_있는_파일은_읽지_않는다() async throws {
        let broken = CareText.csvHeader + "\n" + [
            "FEED,2026-11-06T12:00:00+09:00,,true,LEFT,,,\"EXAMPLE 닫히지 않은 메모",
            "FEED,2026-11-06T13:00:00+09:00,,true,LEFT,,,EXAMPLE 뒤 기록",
            "DIAPER,2026-11-06T14:00:00+09:00,,false,,,WET,EXAMPLE 뒤 기록",
        ].joined(separator: "\n") + "\n"
        XCTAssertEqual(CareCsv.parse(broken, existing: [], now: Self.now), ImportDraft(format: .unknown))
        let step = await (try kit()).app.settings().loaded(.text(broken))
        XCTAssertEqual(step, .failed(CareText.importUnreadable))
        // 헤더에서 따옴표가 안 닫혀도 같다. 닫힌 따옴표 안의 줄바꿈·"" 는 그대로 읽는다.
        XCTAssertEqual(CareCsv.parse("\"kind,start", existing: [], now: Self.now), ImportDraft(format: .unknown))
        let closed = CareCsv.parse(CareText.csvHeader + "\nNOTE,2026-11-06T12:00:00+09:00,,false,,,,\"EXAMPLE \"\"첫\"\"\n둘째\"", existing: [], now: Self.now)
        XCTAssertEqual(closed.rows.map(\.note), ["EXAMPLE \"첫\"\n둘째"])
    }

    /// U65 11-09 보충: 한 레코드는 256칸까지. 헤더든 데이터 줄이든 넘으면 파일째 읽지 않는다(import.unreadable).
    func test_칸이_256개를_넘는_줄이_있으면_읽지_않는다() async throws {
        let row = "NOTE,2026-11-06T12:00:00+09:00,,false,,,,EXAMPLE"
        let wide256 = CareText.csvHeader + "\n" + row + String(repeating: ",", count: 256 - 8) + "\n"   // 256칸은 읽는다
        XCTAssertEqual(CareCsv.parse(wide256, existing: [], now: Self.now).rows.count, 1)
        let header257 = CareText.csvHeader + String(repeating: ",x", count: 257 - 8) + "\n" + row + "\n"
        XCTAssertEqual(CareCsv.parse(header257, existing: [], now: Self.now), ImportDraft(format: .unknown))
        let row257 = CareText.csvHeader + "\n" + row + "\n" + row + String(repeating: ",", count: 257 - 8) + "\n" + row + "\n"
        XCTAssertEqual(CareCsv.parse(row257, existing: [], now: Self.now), ImportDraft(format: .unknown))
        let step = await (try kit()).app.settings().loaded(.text(row257))
        XCTAssertEqual(step, .failed(CareText.importUnreadable))
    }

    func test_너무_큰_파일은_읽지_않는다() async throws {
        XCTAssertEqual(CareCsv.read(Data(count: CareCsv.maxBytes + 1)), .tooLarge)
        let header = CareText.csvHeader + "\n"
        let line = "NOTE,2026-11-06T12:00:00+09:00,,false,,,,EXAMPLE\n"
        XCTAssertEqual(CareCsv.read(Data((header + String(repeating: line, count: 19_999)).utf8)), .text(header + String(repeating: line, count: 19_999)))   // 2만 줄까지
        XCTAssertEqual(CareCsv.read(Data((header + String(repeating: line, count: 20_000)).utf8)), .tooLarge)
        XCTAssertEqual(CareCsv.read(Data([0xEF, 0xBB, 0xBF] + Array("a,b".utf8))), .text("a,b"))   // 앞 BOM은 버린다
        XCTAssertEqual(CareCsv.read(Data([0x61, 0xFF, 0xFE])), .unreadable)                        // UTF-8이 아니다
        // 파일 고르기 바로 뒤: 크기 상한은 읽기 전에 본다. 문구는 import.tooLarge.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("EXAMPLE-big.csv")
        try Data(count: CareCsv.maxBytes + 1).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertEqual(CoordinatedFileReader().read(url, upTo: CareCsv.maxBytes + 1).map(CareCsv.read), .tooLarge)
        let vm = try kit().app.settings()
        vm.startImport()
        vm.picked(.success(url))
        await vm.importJob?.value
        XCTAssertEqual(vm.importStep, .failed("파일이 너무 커요"))
        vm.closeImport()
        XCTAssertEqual(vm.importStep, .idle)
    }

    // MARK: U66

    func test_세_형식_예제를_기대_기록으로_바꾼다() async throws {
        try assertExpected("aigyeot")
        try assertExpected("huckleberry")
        try assertExpected("nara")
        try assertExpected("unknown")
    }

    /// U66 11-09 보충: 끝이 지금 + 1분보다 늦으면 끝 모름, 시각은 세 형식 공통으로 엄격하게, 글자 대소문자 규칙.
    func test_세_형식의_보충_규칙() async throws {
        // 시각: 오프셋 있는 ISO는 그대로(소수 초는 ms까지), 오프셋 없는 'YYYY-MM-DD HH:MM[:SS]'는 서울 벽시계.
        XCTAssertEqual(CareCsv.time("2026-11-06T15:00:00+09:00"), Self.now)
        XCTAssertEqual(CareCsv.time("2026-11-06T06:00Z"), Self.now)
        XCTAssertEqual(CareCsv.time("2026-11-06T15:00:00.1239+09:00"), Self.now + 123)
        XCTAssertEqual(CareCsv.time("2026-11-06 15:00"), Self.now)
        XCTAssertEqual(CareCsv.time("2026-11-06 15:00:07"), Self.now + 7000)
        XCTAssertEqual(CareCsv.time("2026-11-06T00:00:00-18:00"), Self.now + 12 * 3_600_000)   // 오프셋은 Java ZoneOffset 범위(±18:00)까지
        XCTAssertEqual(CareCsv.time("2026-11-06T15:00:00+18:00"), Self.now - 9 * 3_600_000)
        for bad in ["2026-11-06T14:30:00+09:60", "2026-11-06T14:30:00+24:00", "2026-11-06T14:30:00+18:01", "2026-11-06T15:00:00", "2026-11-06 15:00:00+09:00", "2026-11-06T15:00", "2026-02-30 10:00", "2026-11-06 24:00",
                    "2026/11/06 15:00", "2026-11-06 15:00:00.5", " 2026-11-06 15:00x", "EXAMPLE"] {
            XCTAssertNil(CareCsv.time(bad), bad)
        }
        // 끝이 지금 + 1분보다 늦은 수유·잠은 끝 모름으로 살린다. 지금 + 1분까지는 그대로.
        let ends = CareCsv.parse(CareText.csvHeader + "\n" + [
            "SLEEP,2026-11-06T14:30:00+09:00,2026-11-06T15:01:01+09:00,false,,,,EXAMPLE 끝이 미래",
            "FEED,2026-11-06T14:40:00+09:00,2026-11-06T15:01:00+09:00,false,LEFT,,,EXAMPLE 끝이 지금 + 1분",
        ].joined(separator: "\n"), existing: [], now: Self.now).rows
        XCTAssertEqual(ends.map(\.endAt), [nil, Self.now + 60_000])
        XCTAssertEqual(ends.map(\.endUnknown), [true, false])
        // 아이곁 kind·side·diaper는 enum 글자 그대로(대소문자 구분).
        let mine = CareCsv.parse(CareText.csvHeader + "\nfeed,2026-11-06T10:00:00+09:00,,true,,,,EXAMPLE\nFEED,2026-11-06T11:00:00+09:00,,true,left,,,EXAMPLE\n"
                                 + "DIAPER,2026-11-06T12:00:00+09:00,,false,,,wet,EXAMPLE\n", existing: [], now: Self.now)
        XCTAssertEqual(mine.skipped.unsupported, 1)
        XCTAssertEqual(mine.rows.map(\.feedSide), [nil, nil])
        XCTAssertEqual(mine.rows.map(\.diaper), [nil, nil])
        // 아이곁 amount_ml은 종류와 상관없이 정수·0~400이 아니면 읽을 수 없는 줄. 빈 칸은 통과한다.
        let amounts = CareCsv.parse(CareText.csvHeader + "\n" + [
            "SLEEP,2026-11-06T09:00:00+09:00,2026-11-06T09:30:00+09:00,false,,abc,,EXAMPLE",
            "DIAPER,2026-11-06T10:00:00+09:00,,false,,abc,WET,EXAMPLE",
            "NOTE,2026-11-06T11:00:00+09:00,,false,,abc,,EXAMPLE",
            "SLEEP,2026-11-06T12:00:00+09:00,2026-11-06T12:30:00+09:00,false,,500,,EXAMPLE",
            "SLEEP,2026-11-06T13:00:00+09:00,2026-11-06T13:30:00+09:00,false,,,,EXAMPLE 빈 칸은 통과",
            "DIAPER,2026-11-06T13:10:00+09:00,,false,,,WET,EXAMPLE 빈 칸은 통과",
        ].joined(separator: "\n"), existing: [], now: Self.now)
        XCTAssertEqual(amounts.skipped.unreadable, 4)
        XCTAssertEqual(amounts.rows.map(\.kind), [.sleep, .diaper])
        XCTAssertEqual(amounts.rows.map(\.amountMl), [nil, nil])
        // 값은 종류에 맞는 칸에만(U66 11-09 보충): feedSide·amountMl은 FEED만, diaper는 DIAPER만. 그 밖은 값이 있어도 nil이고 id 해시에도 nil이다.
        let fields = CareCsv.parse(CareText.csvHeader + "\n" + [
            "SLEEP,2026-11-06T09:00:00+09:00,2026-11-06T09:30:00+09:00,false,LEFT,120,WET,EXAMPLE",
            "FEED,2026-11-06T10:00:00+09:00,2026-11-06T10:10:00+09:00,false,RIGHT,,WET,EXAMPLE",
            "DIAPER,2026-11-06T11:00:00+09:00,,false,LEFT,120,DRY,EXAMPLE",
        ].joined(separator: "\n"), existing: [], now: Self.now).rows
        XCTAssertEqual(fields.map(\.feedSide), [nil, .right, nil])
        XCTAssertEqual(fields.map(\.amountMl), [nil, nil, nil])
        XCTAssertEqual(fields.map(\.diaper), [nil, nil, .dry])
        let sleepStart = Self.now - 6 * 3_600_000
        XCTAssertEqual(fields.first?.id, CareCsv.event(.init(kind: .sleep, startAt: sleepStart, endAt: sleepStart + 1_800_000), now: Self.now).id)
        // Huckleberry·Nara Type은 대소문자 없이. Huckleberry 쪽 조각은 대문자 R·L만, 조각 전체가 맞아야 한다. 부피 단위는 대소문자 없이 띄워도 된다.
        let huck = CareCsv.parse("Type,Start,End,Duration,Start Condition,Start Location,End Condition,Notes\n" + [
            "FEED,2026-11-06 09:00,2026-11-06 09:10,0:10r,,,,EXAMPLE 소문자 r",
            "feed,2026-11-06 10:00,2026-11-06 10:10,x0:10R 0:05L,,,,EXAMPLE 앞이 붙은 조각",
            "Feed,2026-11-06 11:00,2026-11-06 11:10,0:05L 120 ML,,,,EXAMPLE 띄운 단위",
            "Feed,2026-11-06 12:00,2026-11-06 12:10,120mlx,,,,EXAMPLE 단위 뒤에 붙은 글자",
            "Feed,2026-11-06 13:00,2026-11-06 13:10,-0.4ml 0:10R,,,,EXAMPLE 음수 부피는 범위 밖",
            "Feed,2026-11-06 14:00,2026-11-06 14:10,+120ml,,,,EXAMPLE 더하기 부호",
        ].joined(separator: "\n"), existing: [], now: Self.now)
        XCTAssertEqual(huck.rows.map(\.feedSide), [nil, .left, .bottle, nil, .bottle])
        XCTAssertEqual(huck.rows.map(\.amountMl), [nil, nil, 120, nil, 120])
        XCTAssertEqual(huck.skipped.unreadable, 1)
        // Nara 좌우 초: 소수 허용, 숫자가 아니거나 음수면 그 쪽 없음, 끝 = 시작 + (합 × 1000의 ms 아래 버림).
        let nara = CareCsv.parse("Type,Profile Name,Start Date/time,[Breastfeed] Left Duration (Seconds),[Breastfeed] Right Duration (Seconds)\n" + [
            "BREASTFEED,EXAMPLE-A,2026-11-06 10:00:00,60,",
            "Breastfeed,EXAMPLE-A,2026-11-06 11:00:00,600.0009,EXAMPLE",
            "Breastfeed,EXAMPLE-A,2026-11-06 12:00:00,-60,600.0",
            "Breastfeed,EXAMPLE-A,2026-11-06 13:00:00,600.5,0.5",
            "Breastfeed,EXAMPLE-A,2026-11-06 14:00:00,10000000000000000,",   // 최대 길이를 크게 넘는 초: 크래시 없이 끝 모름
            "Breastfeed,EXAMPLE-A,2026-11-06 14:30:00,1e308,1e308",
        ].joined(separator: "\n"), existing: [], now: Self.now)
        let t10 = Self.now - 5 * 3_600_000
        XCTAssertEqual(nara.rows.map(\.feedSide), [.left, .left, .right, .both, .left, .both])
        XCTAssertEqual(nara.rows.map(\.endAt), [t10 + 60_000, t10 + 3_600_000 + 600_000, t10 + 7_200_000 + 600_000, t10 + 10_800_000 + 601_000, nil, nil])
        XCTAssertEqual(nara.rows.map(\.endUnknown), [false, false, false, false, true, true])
        // Nara 젖병: 부피·단위가 비어도 읽을 수 없는 줄(U66: 단위가 없으면 읽을 수 없음).
        let bottle = CareCsv.parse("Type,Profile Name,Start Date/time,[Bottle Feed] Volume,[Bottle Feed] Volume Unit\n" + [
            "Bottle Feed,EXAMPLE-A,2026-11-06 09:30:00,,",
            "Bottle Feed,EXAMPLE-A,2026-11-06 09:40:00,,ml",
            "Bottle Feed,EXAMPLE-A,2026-11-06 09:50:00,120,ml",
        ].joined(separator: "\n"), existing: [], now: Self.now)
        XCTAssertEqual(bottle.skipped.unreadable, 2)
        XCTAssertEqual(bottle.rows.map(\.amountMl), [120])
        // 부피: 소수 허용, 범위(0~400)는 반올림 전 값(oz는 ×29.57 한 값)으로 보고, 0.5에서 올린다.
        XCTAssertNil(CareCsv.volume("400.4", unit: "ml"))
        XCTAssertNil(CareCsv.volume("-0.4", unit: "ml"))
        XCTAssertNil(CareCsv.volume("13.53", unit: "oz"))   // ≈400.08ml
        XCTAssertEqual(CareCsv.volume("13.52", unit: "OZ"), 400)   // ≈399.80ml
        XCTAssertEqual(CareCsv.volume("120.5", unit: "ml"), 121)
        XCTAssertEqual(CareCsv.volume("0", unit: "ml"), 0)
        XCTAssertEqual(CareCsv.volume("400", unit: "ml"), 400)
        XCTAssertNil(CareCsv.volume("120", unit: ""))
        XCTAssertNil(CareCsv.volume("EXAMPLE", unit: "ml"))
    }

    func test_가장_늦은_수유_시각은_오늘_어제_그전으로_쓴다() async {
        let seoul = TimeZone(identifier: "Asia/Seoul")!
        let now = CareTime.date(Self.now)   // 11월 6일 오후 3:00
        XCTAssertEqual(CareText.dayClock(Self.now - 4 * 3_600_000, now: now, zone: seoul), "오전 11:00")
        XCTAssertEqual(CareText.dayClock(Self.now - 15 * 3_600_000, now: now, zone: seoul), "오전 12:00")   // 오늘 0시
        XCTAssertEqual(CareText.dayClock(Self.now - 15 * 3_600_000 - 60_000, now: now, zone: seoul), "어제 오후 11:59")
        XCTAssertEqual(CareText.dayClock(Self.now - 28 * 3_600_000, now: now, zone: seoul), "어제 오전 11:00")
        XCTAssertEqual(CareText.dayClock(Self.now - 3 * 86_400_000 - 4 * 3_600_000, now: now, zone: seoul), "11월 3일 오전 11:00")
        XCTAssertEqual(CareText.importLatestFeed(CareText.dayClock(Self.now - 28 * 3_600_000, now: now, zone: seoul)), "가장 늦은 수유 어제 오전 11:00")
    }

    func test_Nara_아이를_고르면_그_아이_줄만_본다() async throws {
        // 고르기 전: 이름은 파일에 처음 나온 순서, 기록·건너뜀은 없다(U69).
        let none = try parse("nara")
        XCTAssertEqual(none.profiles, ["EXAMPLE-B", "EXAMPLE-A"])
        XCTAssertEqual(none.rows, [])
        XCTAssertEqual(none.skipped, ImportSkipped())
        // B를 고르면 B의 네 줄만: 수유 하나, 유축(가져오지 않는 종류)·오래됨·미래 하나씩. A의 줄은 어디에도 세지 않는다.
        let b = try parse("nara", profile: "EXAMPLE-B")
        XCTAssertEqual(b.rows.map(\.note), ["EXAMPLE B 수유"])
        XCTAssertEqual(b.skipped, ImportSkipped(unsupported: 1, unreadable: 0, future: 1, old: 1, duplicate: 0))
        let a = try parse("nara", profile: "EXAMPLE-A")
        XCTAssertEqual(a.rows.count + a.skipped.unsupported + a.skipped.unreadable + a.skipped.future + a.skipped.old + a.skipped.duplicate, 40)
        XCTAssertFalse(a.rows.contains { $0.note?.contains("EXAMPLE B") == true })
        // 화면: 아이가 둘 이상이면 아이 고르기 → 고른 아이의 미리 보기. 이름은 저장하지 않는다.
        let k = try kit()
        let vm = k.app.settings()
        let t = try text("nara")
        vm.startImport()
        XCTAssertEqual(vm.importStep, .picking)
        let step = await vm.loaded(.text(t))
        XCTAssertEqual(step, .pickChild(text: t, profiles: ["EXAMPLE-B", "EXAMPLE-A"]))
        XCTAssertEqual(CareText.importPickChild, "어느 아기의 기록인가요?")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("EXAMPLE-nara.csv")
        try Data(t.utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        vm.picked(.success(url))
        XCTAssertEqual(vm.importStep, .reading)   // 읽기·판별은 메인 밖 작업 하나
        await vm.importJob?.value
        XCTAssertEqual(vm.importStep, step)
        vm.pickChild("EXAMPLE-A")
        await vm.importJob?.value
        XCTAssertEqual(vm.importStep, .preview(a))
        // 읽는 중에 다시 고르면 앞 작업을 버리고 마지막 파일만 미리 보기다(앞 결과는 늦게 끝나도 적용하지 않는다).
        let other = FileManager.default.temporaryDirectory.appendingPathComponent("EXAMPLE-again-aigyeot.csv")
        try Data(try text("aigyeot").utf8).write(to: other)
        defer { try? FileManager.default.removeItem(at: other) }
        vm.startImport()
        vm.picked(.success(url))
        let first = vm.importJob
        vm.startImport()
        vm.picked(.success(other))
        await vm.importJob?.value
        await first?.value
        await vm.importJob?.value
        XCTAssertEqual(vm.importStep, .preview(try parse("aigyeot")))
        // 읽는 중에 닫으면 그 작업을 버리고 '읽는 중'에 남지 않는다.
        vm.startImport()
        vm.picked(.success(url))
        vm.closeImport()
        await vm.importJob?.value
        XCTAssertEqual(vm.importStep, .idle)
        // 아이가 하나뿐인 Nara 파일은 고르지 않고 바로 미리 보기.
        let one = t.split(separator: "\n", omittingEmptySubsequences: false).filter { !$0.contains("EXAMPLE-B") }.joined(separator: "\n")
        guard case .preview(let d) = await vm.loaded(.text(one)) else { return XCTFail("바로 미리 보기") }
        XCTAssertEqual(d.profiles, ["EXAMPLE-A"])
        XCTAssertEqual(d.rows, a.rows)
    }

    func test_같은_줄은_두_앱에서_같은_id다() async throws {
        // id = "imp-" + SHA-256("kind|startAt|endAt|feedSide|amountMl", null은 빈 글자) 16진수 앞 16글자(U66).
        func id(_ p: CareCsv.Picked) -> String { CareCsv.event(p, now: Self.now).id }
        XCTAssertEqual(id(.init(kind: .feed, startAt: 1_792_735_200_000, endAt: 1_792_735_920_000, feedSide: .right)), "imp-47fd89f213c9a86d")
        XCTAssertEqual(id(.init(kind: .feed, startAt: 1_793_599_200_000, feedSide: .bottle, amountMl: 100)), "imp-9b86e21badf24cd0")
        // 끝 모름으로 살린 기록은 끝 칸이 빈 글자다. 메모·기저귀 값은 id에 들지 않는다.
        XCTAssertEqual(id(.init(kind: .feed, startAt: 1_793_599_200_000, endAt: 1_793_599_100_000, feedSide: .bottle, amountMl: 100)), "imp-9b86e21badf24cd0")
        XCTAssertEqual(id(.init(kind: .diaper, startAt: 1_793_944_860_000, diaper: .wet, note: "EXAMPLE")), "imp-46bd9758caf28643")
        // 세 형식의 같은 기저귀 줄(지금 + 1분)은 같은 id다.
        for name in ["aigyeot", "huckleberry", "nara"] {
            let rows = try parse(name, profile: name == "nara" ? "EXAMPLE-A" : nil).rows
            XCTAssertEqual(rows.last?.id, "imp-46bd9758caf28643", name)
        }
        // 같은 파일을 다시 읽어도 같다.
        XCTAssertEqual(try parse("huckleberry").rows.map(\.id), try parse("huckleberry").rows.map(\.id))
    }

    // MARK: U67

    func test_건너뛴_이유를_정해진_순서로_센다() async throws {
        let hand = CareEvent(id: "01JDEXAMPLE0000000000000001", kind: .feed, startAt: Self.now - 3_600_000, zone: "Asia/Seoul",
                             createdAt: Self.now, updatedAt: Self.now)
        let csv = CareText.csvHeader + "\n" + [
            "PUMP,,,false,,,,EXAMPLE ① 종류가 ② 빈 시작보다 먼저",
            "FEED,2026-11-07T15:00:00+09:00,,true,BOTTLE,500,,EXAMPLE ② 부피 오류가 ③ 미래보다 먼저",
            "FEED,2026-11-06T15:01:01+09:00,,true,LEFT,,,EXAMPLE ③ 미래",
            "FEED,2026-10-01T09:00:00+09:00,,true,LEFT,,,EXAMPLE ④ 오래됨",
            "FEED,2026-10-01T09:00:00+09:00,,true,LEFT,,,EXAMPLE ④ 오래된 같은 줄은 ⑤가 아니라 ④",
            "FEED,2026-11-06T14:00:30+09:00,,true,RIGHT,,,EXAMPLE ⑤ 손 기록과 30초",
            "FEED,2026-11-06T13:00:00+09:00,,true,LEFT,,,EXAMPLE 가져옴",
            "FEED,2026-11-06T13:00:00+09:00,,true,LEFT,,,EXAMPLE ⑤ 앞줄과 같음",
            "FEED,2026-11-06T13:01:00+09:00,,true,RIGHT,,,EXAMPLE ⑤ 가져오기로 정한 앞줄과 60초",
            "FEED,2026-11-06T15:00:30+09:00,,true,LEFT,,,EXAMPLE 건너뛴 미래 줄과는 견주지 않아 가져옴",
            "SLEEP,2026-11-06T14:00:00+09:00,2026-11-06T14:30:00+09:00,false,,,,EXAMPLE 다른 종류는 60초 안이어도 가져옴",
            "",                  // 빈 줄과
            " , ,,,,,,",         // 칸이 모두 비었거나 공백뿐인 줄은 줄로 치지 않는다
        ].joined(separator: "\r\n") + "\r\n,,,,,,,\r\n"
        let d = CareCsv.parse(csv, existing: [hand], now: Self.now)
        XCTAssertEqual(d.skipped, ImportSkipped(unsupported: 1, unreadable: 1, future: 1, old: 2, duplicate: 3))
        XCTAssertEqual(d.rows.map(\.note), ["EXAMPLE 가져옴", "EXAMPLE 다른 종류는 60초 안이어도 가져옴",
                                            "EXAMPLE 건너뛴 미래 줄과는 견주지 않아 가져옴"])   // startAt 오름차순
        // 화면 줄은 0인 이유를 감춘다(U68).
        XCTAssertEqual(CareText.importSkips(d.skipped),
                       ["이미 있는 기록 3건", "14일보다 오래된 기록 2건", "앞으로의 시각 1건", "읽을 수 없는 줄 1건", "가져오지 않는 종류 1건"])
        XCTAssertEqual(CareText.importSkips(ImportSkipped(duplicate: 3)), ["이미 있는 기록 3건"])
    }

    func test_가져오기_저장이_실패하면_아무것도_들어가지_않는다() async throws {
        let k = try kit()
        let before = k.app.care.care
        let vm = k.app.settings()
        vm.startImport()
        guard case .preview(let d) = await vm.loaded(.text(try text("aigyeot"))) else { return XCTFail("미리 보기") }
        XCTAssertEqual(d.rows.count, 20)
        k.careStore.failing = true
        XCTAssertNil(k.app.recordCare.importRows(d.rows))
        // 화면 흐름도 같다: 문구는 import.failed, 메모리·파일 모두 그대로, 차로도 보내지 않는다.
        let vm2 = k.app.settings()
        vm2.startImport()
        vm2.closeImport()   // 고르기 취소(시스템이 고르기 창을 닫는다)
        XCTAssertEqual(vm2.importStep, .idle)
        // 파일을 열거나 읽지 못한 IO 실패는 import.failed(11-09 보충).
        vm2.picked(.success(FileManager.default.temporaryDirectory.appendingPathComponent("EXAMPLE-없는-파일.csv")))
        await vm2.importJob?.value
        XCTAssertEqual(vm2.importStep, .failed(CareText.importFailed))
        vm2.picked(.failure(CocoaError(.fileReadNoPermission)))
        await vm2.importJob?.value
        XCTAssertEqual(vm2.importStep, .failed(CareText.importFailed))
        XCTAssertEqual(ImportPreview(failure: CareText.importFailed).buttons, ["닫기"])   // 끝내는 안내도 글 + '닫기'
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("EXAMPLE-aigyeot.csv")
        try Data(try text("aigyeot").utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        vm2.picked(.success(url))
        await vm2.importJob?.value
        XCTAssertEqual(vm2.importStep, .preview(d))
        vm2.confirmImport()
        XCTAssertEqual(vm2.importStep, .failed("가져오지 못했어요. 다시 해 주세요."))
        XCTAssertEqual(k.app.care.care, before)
        XCTAssertEqual(k.careStore.value, before)
        XCTAssertTrue(queued(k).isEmpty)
    }

    func test_되돌리면_넣은_기록이_모두_빠지고_다시_가져올_수_있다() async throws {
        let k = try kit()
        let before = k.app.care.care
        let vm = k.app.settings()
        let t = try text("aigyeot")
        vm.startImport()
        guard case .preview(let d) = await vm.loaded(.text(t)) else { return XCTFail("미리 보기") }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("EXAMPLE-undo.csv")
        try Data(t.utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        vm.picked(.success(url))
        await vm.importJob?.value
        vm.confirmImport()
        XCTAssertEqual(vm.importStep, .idle)
        XCTAssertEqual(k.app.notices.undo, .imported(d.rows.map(\.id)))
        XCTAssertEqual(CareText.undoText(.imported(d.rows.map(\.id))), "기록 20건을 가져왔어요")
        XCTAssertEqual(k.careStore.value.events.count, before.events.count + 20)
        XCTAssertTrue(k.careStore.value.events.suffix(20).allSatisfy { $0.source == .imported })
        // 되돌리기: deletedAt 표시가 아니라 완전히 뺀다.
        k.app.careTabs.undo(.imported(d.rows.map(\.id)))
        XCTAssertNil(k.app.notices.undo)
        XCTAssertEqual(k.app.care.care, before)
        XCTAssertEqual(k.careStore.value, before)
        // 다시 가져올 수 있다.
        vm.startImport()
        vm.picked(.success(url))
        await vm.importJob?.value
        XCTAssertEqual(vm.importStep, .preview(d))
        k.app.recordCare.undoMs = 0
        vm.confirmImport()
        XCTAssertEqual(k.app.notices.undo, .imported(d.rows.map(\.id)))
        XCTAssertEqual(k.careStore.value.events.count, before.events.count + 20)
        // 띠 시간이 다 되면 걷히고 기록은 남는다.
        await k.app.recordCare.undoTask?.value
        XCTAssertNil(k.app.notices.undo)
        XCTAssertEqual(k.careStore.value.events.count, before.events.count + 20)
    }

    /// 11-09 보충: 지우다 만 '기록 지우기'(R10)가 남았으면 저장 전에 마치고 가져온다. 띠 {n}은 실제로 들어간 수, 마치지 못하면 import.failed.
    func test_지우다_만_기록이_남아도_같은_파일을_가져온다() async throws {
        let k = try kit()
        let rows = try parse("aigyeot").rows
        XCTAssertEqual(k.app.recordCare.importRows(rows), rows.map(\.id))
        // 기록 지우기가 care.json을 비우기 전에 멈췄다: 지울 id 표시만 남았다.
        let wiping1 = k.careStore.value.events.map(\.id)
        XCTAssertTrue(k.app.state.commitLocal { $0.clearingCareIds = wiping1 })   // 앱을 다시 켜면 시작 계기가 먼저 마치므로 켜진 채로 둔다
        // 유스케이스: 먼저 지우기를 마치고 같은 id를 넣는다(저장 뒤처리가 다시 빼지 않는다).
        XCTAssertEqual(k.app.recordCare.importRows(rows), rows.map(\.id))
        XCTAssertNil(k.store.value.clearingCareIds)
        XCTAssertEqual(k.careStore.value.events.map(\.id), rows.map(\.id))
        // 화면: 지우기를 마친 기록으로 미리 보기를 만들고, 띠의 수는 실제로 들어간 수다.
        let wiping2 = k.careStore.value.events.map(\.id)
        XCTAssertTrue(k.app.state.commitLocal { $0.clearingCareIds = wiping2 })   // 앱을 다시 켜면 시작 계기가 먼저 마치므로 켜진 채로 둔다
        let vm = k.app.settings()
        let t = try text("aigyeot")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("EXAMPLE-clear.csv")
        try Data(t.utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        vm.startImport()
        vm.picked(.success(url))
        await vm.importJob?.value
        let fresh = CareCsv.parse(t, existing: [], now: Self.now).rows
        XCTAssertEqual(vm.importStep, .preview(ImportDraft(format: .aigyeot, rows: fresh, skipped: ImportSkipped(unsupported: 1, unreadable: 4, future: 1, old: 2, duplicate: 1))))
        vm.confirmImport()
        XCTAssertEqual(vm.importStep, .idle)
        XCTAssertEqual(k.app.notices.undo, .imported(fresh.map(\.id)))
        XCTAssertEqual(k.careStore.value.events.map(\.id), fresh.map(\.id))
        // 지우기를 마치지 못하면 가져오지 않는다.
        let wiping3 = k.careStore.value.events.map(\.id)
        XCTAssertTrue(k.app.state.commitLocal { $0.clearingCareIds = wiping3 })   // 앱을 다시 켜면 시작 계기가 먼저 마치므로 켜진 채로 둔다
        let before = k.careStore.value
        k.careStore.failing = true
        XCTAssertNil(k.app.recordCare.importRows(fresh))
        XCTAssertEqual(k.careStore.value, before)
        XCTAssertNotNil(k.store.value.clearingCareIds)
    }

    /// U68 보충 ④: 중복은 미리 보기와 저장하는 순간 둘 다 같은 판정(CareCsv.Seen)으로 본다. 띠 {n}은 실제로 들어간 수.
    func test_미리_보기_뒤_손으로_넣은_기록과_겹치는_줄은_저장_때_뺀다() async throws {
        let k = try kit()
        let vm = k.app.settings()
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("EXAMPLE-late.csv")
        try Data(try text("aigyeot").utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        vm.startImport()
        vm.picked(.success(url))
        await vm.importJob?.value
        guard case .preview(let d) = vm.importStep else { return XCTFail("미리 보기") }
        XCTAssertEqual(d.rows.count, 20)
        // 미리 보기가 뜬 뒤 다른 화면에서 가져올 기저귀(10월 30일 12:00)와 40초 차이인 기저귀를 손으로 적었다.
        let clash = try XCTUnwrap(d.rows.first { $0.kind == .diaper && $0.startAt == 1_793_329_200_000 })
        let hand = CareEvent(id: ULID.generate(), kind: .diaper, startAt: clash.startAt + 40_000, zone: "Asia/Seoul", createdAt: Self.now, updatedAt: Self.now)
        XCTAssertEqual(k.app.recordCare.saveEvent(hand), .saved)
        vm.confirmImport()
        let added = d.rows.map(\.id).filter { $0 != clash.id }
        XCTAssertEqual(vm.importStep, .idle)
        XCTAssertEqual(k.app.notices.undo, .imported(added))
        XCTAssertEqual(CareText.importDone(added.count), "기록 19건을 가져왔어요")
        XCTAssertNil(k.careStore.value.events.first { $0.id == clash.id })
        XCTAssertNotNil(k.careStore.value.events.first { $0.id == hand.id })
        // 저장 때 거르는 판정은 미리 보기와 같은 함수다: 같은 기록 목록이면 미리 보기의 줄이 그대로 남는다.
        XCTAssertEqual(CareCsv.fresh(d.rows, existing: try existing().events), d.rows)
    }

    func test_손으로_지운_가져온_기록은_다시_가져오지_않는다() async throws {
        let k = try kit()
        let rows = try parse("huckleberry").rows
        XCTAssertEqual(k.app.recordCare.importRows(rows), rows.map(\.id))
        let gone = rows[3].id
        k.app.recordCare.delete(gone)   // R8: deletedAt 표시
        XCTAssertNotNil(k.app.care.care.events.first { $0.id == gone }?.deletedAt)
        let again = CareCsv.parse(try text("huckleberry"), existing: k.app.care.care.events, now: Self.now)
        XCTAssertEqual(again.rows, [])
        XCTAssertEqual(again.skipped.duplicate, 4 + rows.count)
        XCTAssertNil(again.rows.first { $0.id == gone })
        // 미리 보기 뒤 같은 기록이 생겼어도 같은 id는 두 번 넣지 않는다.
        XCTAssertEqual(k.app.recordCare.importRows(rows), [])
        XCTAssertEqual(k.app.care.care.events.filter { $0.id == gone }.count, 1)
    }

    /// 파일을 골라 '가져오기'까지 누른 앱(띠 import.done이 떠 있다). 띠가 든 id를 돌려준다.
    func importedWithBand(_ app: AppContainer, file: String = "aigyeot") async throws -> [String] {
        let vm = app.settings()
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("EXAMPLE-band-\(file).csv")
        try Data(try text(file).utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        vm.startImport()
        vm.picked(.success(url))
        await vm.importJob?.value
        vm.confirmImport()
        XCTAssertEqual(vm.importStep, .idle)   // 시트는 닫히고 띠는 되돌리기 띠(UndoBar) 하나다
        guard case .imported(let ids)? = app.notices.undo else { XCTFail("띠"); return [] }
        XCTAssertEqual(app.careTabs.undo, .imported(ids))
        return ids
    }

    func queuedAt(_ k: Kit) -> [Date] {
        queued(k).compactMap { e -> Date? in if case .string(let s)? = e.body.objectValue?["at"] { Rfc3339.parse(s) } else { nil } }
    }

    /// U67 11-09 보충(사용자 결정): 가져오기의 차 보내기 계기(R5·R19)는 되돌리기 띠가 끝난 뒤 한 번 낸다. 띠 수명은 RecordCare 한 곳이다.
    func test_가져온_더_늦은_수유는_owner일_때_차로_보낸다() async throws {
        // owner·연결됨: 가져온 가장 늦은 수유(2026-11-06 12:00)가 24시간 안이고 더 늦다. 저장 직후에는 보내지 않는다.
        let k = try kit()
        k.app.recordCare.undoMs = 0   // 띠 5초를 기다리지 않는다(시간이 다 됨과 같은 길)
        let ids = try await importedWithBand(k.app)
        XCTAssertEqual(ids.count, 20)
        XCTAssertTrue(queued(k).isEmpty)
        XCTAssertNil(k.store.value.lastSentFeedingAt)
        await k.app.recordCare.undoTask?.value   // 띠의 시간이 다 됐다
        XCTAssertNil(k.app.notices.undo)
        XCTAssertEqual(queuedAt(k), [CareTime.date(1_793_934_000_000)])
        XCTAssertEqual(k.store.value.lastSentFeedingAt, 1_793_934_000_000)
        XCTAssertEqual(RetryPolicy.undoMs, 5000)
        // guardian·연결 안 됨은 가져오되 띠가 끝나도 차로 보내지 않는다.
        for creds in [Credentials(carId: "c_1", deviceId: "d_3", token: "at1.c_1.d_3.x", role: .guardian), nil] {
            let g = try kit(creds: creds)
            g.app.recordCare.undoMs = 0
            let gids = try await importedWithBand(g.app)
            XCTAssertEqual(gids.count, 20)
            await g.app.recordCare.undoTask?.value
            XCTAssertNil(g.app.notices.undo)
            XCTAssertTrue(queued(g).isEmpty)
            XCTAssertNil(g.store.value.lastSentFeedingAt)
        }
    }

    /// U87·U67: 화면 읽기가 켜져 띠가 길어져도 가져온 줄은 그 띠가 끝난 뒤에 차로 간다(띠 수명은 RecordCare 한 곳).
    func test_띠_시간이_길어져도_가져온_줄은_띠가_끝난_뒤에_차로_간다() async throws {
        let k = try kit()
        k.app.recordCare.screenReader = { true }
        k.app.recordCare.undoMs = 0                    // 꺼져 있을 때의 시간이면 바로 끝났을 것
        k.app.recordCare.undoMsScreenReader = 300      // 50초 대신 짧게(같은 길)
        let ids = try await importedWithBand(k.app)
        XCTAssertEqual(ids.count, 20)
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertNotNil(k.app.notices.undo)            // 화면 읽기 시간으로 아직 떠 있다
        XCTAssertTrue(queued(k).isEmpty)
        await k.app.recordCare.undoTask?.value
        XCTAssertNil(k.app.notices.undo)
        XCTAssertEqual(queuedAt(k), [CareTime.date(1_793_934_000_000)])
    }

    /// 요약(R19)까지 보는 앱. 이 연결에서 요약을 넣은 적이 있다(U61, 값 없는 요약도 간다).
    func summaryKit() throws -> Kit {
        let k = try kit()
        k.app.reconcileCareSummary.sendsCareSummary = true
        k.app.state.commitLocal { $0.maxSentCareAsOf = Self.now - 30 * 86_400_000 }
        return k
    }

    /// 차로 간 요약(보낸 것 다음 보낼 함, 넣은 순서).
    func summaries(_ k: Kit) -> [[String: JSONValue]] {
        (k.relay.sent.filter { $0.type == MessageType.careSummary }.map(\.body) + k.app.state.state.outbox.filter { $0.type == MessageType.careSummary }.map(\.body))
            .compactMap(\.objectValue)
    }

    /// 차로 간 요약의 '오늘 수유 수'.
    func summaryFeeds(_ k: Kit) -> [Double?] { summaries(k).map { if case .number(let n)? = $0["today_feed_count"] { n } else { nil } } }

    /// U67 11-09 보충(규칙 한 곳): 차로 가는 계산 세 곳(수유·요약 맞추기, 보낼 함의 보내기 직전 요약 재계산)은 가져오기 띠의 줄을 뺀 기록(forCar)으로 한다.
    func test_보낼_함에서_기다리던_요약을_띠_안에_다시_계산해도_가져온_줄은_빼고_되돌리면_끝까지_차로_가지_않는다() async throws {
        let k = try summaryKit()
        let app = k.app!
        k.relay.sendError = .network                           // 망이 끊겨 가져오기 전 요약이 보낼 함에 남는다
        app.lifecycle.onForeground()
        await app.sendOutbox.run()
        XCTAssertEqual(app.state.state.outbox.map(\.type), [MessageType.careSummary])
        XCTAssertTrue(app.state.state.outbox[0].sentOnce)
        XCTAssertTrue(k.relay.sent.isEmpty)
        let care = k.careStore.value
        let ids = try await importedWithBand(app)
        XCTAssertEqual(ids.count, 20)
        // 띠 안에 망이 돌아왔다: 보낼 함이 보내기 직전에 요약을 다시 계산해도 가져온 수유는 세지 않는다.
        k.relay.sendError = nil
        app.sendOutbox.hurryNetworkWaits()
        await app.sendOutbox.run()
        XCTAssertNotNil(app.notices.undo)
        XCTAssertTrue(app.state.state.outbox.isEmpty)
        XCTAssertFalse(k.relay.sent.isEmpty)
        XCTAssertEqual(Set(summaryFeeds(k)), [0])
        app.careTabs.undo(.imported(ids))                      // 띠 안에 되돌린다
        XCTAssertNil(app.notices.undo)
        await app.recordCare.undoTask?.value                   // 띠가 끝났을 시간도 지났다
        k.clock = k.clock.addingTimeInterval(5)
        app.lifecycle.onForeground()
        await app.sendOutbox.run()
        // 가져온 수유는 끝까지 차로 가지 않는다.
        XCTAssertFalse((k.relay.sent.map(\.type) + app.state.state.outbox.map(\.type)).contains(MessageType.feedingStarted))
        XCTAssertNil(app.state.state.lastSentFeedingAt)
        XCTAssertEqual(Set(summaryFeeds(k)), [0])
        XCTAssertEqual(k.careStore.value.events, care.events)
    }

    /// 띠 안의 다른 계기(앞으로 오기)·다른 저장은 미루지 않고 가져온 줄만 빼고 맞춘다. 띠 안에 되돌리면 띠 시간이 지나도 가져온 줄은 차로 가지 않는다.
    func test_가져오기를_되돌리기_띠_안에_되돌리면_차로_아무것도_보내지_않는다() async throws {
        let k = try summaryKit()
        let app = k.app!
        let ids = try await importedWithBand(app)
        XCTAssertTrue(app.state.state.outbox.isEmpty)          // 저장 직후에는 계기가 없다
        k.clock = k.clock.addingTimeInterval(1)
        app.lifecycle.onForeground()                           // 띠 안의 다른 계기는 미루지 않는다(가져온 줄만 뺀다)
        let d0 = summaries(k).last?["today_diaper_count"]
        let diaper = CareEvent(id: ULID.generate(), kind: .diaper, startAt: Self.now, zone: "Asia/Seoul", createdAt: Self.now, updatedAt: Self.now)
        XCTAssertEqual(app.recordCare.saveEvent(diaper), .saved)   // 띠 안의 다른 저장도 바로 맞춘다(기저귀는 실제 기록)
        XCTAssertNotNil(app.notices.undo)
        guard case .number(let a)? = d0, case .number(let b)? = summaries(k).last?["today_diaper_count"] else { return XCTFail("기저귀 수") }
        XCTAssertEqual(b, a + 1)
        app.careTabs.undo(.imported(ids))
        await app.recordCare.undoTask?.value
        k.clock = k.clock.addingTimeInterval(5)
        app.lifecycle.onForeground()
        await app.sendOutbox.run()
        XCTAssertNil(app.notices.undo)
        XCTAssertFalse((k.relay.sent.map(\.type) + app.state.state.outbox.map(\.type)).contains(MessageType.feedingStarted))
        XCTAssertNil(app.state.state.lastSentFeedingAt)
        XCTAssertFalse(summaryFeeds(k).isEmpty)
        XCTAssertEqual(Set(summaryFeeds(k)), [0])
    }

    /// 띠가 다른 띠로 바뀌면 그것이 '띠가 닫힘'이다: 앞 띠가 가져오기면 그 줄은 이제 되돌릴 수 없으니 그때 보낸다. 새 띠가 가져오기면 새 줄만 그 띠가 끝날 때까지 빠진다.
    func test_띠를_닫으면_바로_차로_보낸다() async throws {
        let k = try kit()
        _ = try await importedWithBand(k.app)
        XCTAssertTrue(queued(k).isEmpty)
        k.app.recordCare.delete("01JD0000000000000000000002")        // 손 기록을 지워 '지웠어요' 띠로 바뀐다
        XCTAssertEqual(k.app.notices.undo, .delete("01JD0000000000000000000002"))
        XCTAssertEqual(queuedAt(k), [CareTime.date(1_793_934_000_000)])
        k.app.recordCare.undoMs = 0                                  // 늦게 깬 앞 띠 타이머는 다시 보내지 않는다(한 번만)
        k.app.recordCare.undoDelete("01JD0000000000000000000002")
        XCTAssertEqual(queued(k).count, 1)
        // 가져오기 띠가 새 가져오기 띠로 바뀌면 앞 줄(아이곁 11-06 12:00)은 그때 보내고, 새 줄(Huckleberry 11-06 13:00)은 그 띠가 끝날 때 간다.
        let n = try kit()
        _ = try await importedWithBand(n.app)
        n.app.recordCare.undoMs = 0
        _ = try await importedWithBand(n.app, file: "huckleberry")    // 다른 가져오기 띠로 바뀜
        XCTAssertEqual(queuedAt(n), [CareTime.date(1_793_934_000_000)])
        n.app.recordCare.syncToCar(.foreground)                      // 띠 안의 다른 계기도 새 줄은 뺀다
        XCTAssertEqual(queuedAt(n), [CareTime.date(1_793_934_000_000)])
        await n.app.recordCare.undoTask?.value
        XCTAssertEqual(queuedAt(n), [CareTime.date(1_793_934_000_000), CareTime.date(1_793_937_600_000)])
        // 연결 마침은 띠를 먼저 걷으므로 가져온 줄도 보낸다.
        let p = try kit()
        _ = try await importedWithBand(p.app)
        p.app.notices.clearForPair()
        p.app.recordCare.syncToCar(.paired)
        XCTAssertEqual(queuedAt(p), [CareTime.date(1_793_934_000_000)])
    }

    // MARK: U68

    func test_가져올_것이_없으면_닫기만_보인다() async throws {
        let k = try kit()
        let vm = k.app.settings()
        let t = try text("aigyeot")
        // 처음: 가져올 수·종류별 수·건너뛴 이유·가장 늦은 수유, 버튼 '가져오기'·'취소'. 메모 글은 없다.
        guard case .preview(let first) = await vm.loaded(.text(t)) else { return XCTFail("미리 보기") }
        let p = ImportPreview(first, now: CareTime.date(Self.now))
        XCTAssertEqual(p.title, "기록 20건을 가져올게요")
        XCTAssertEqual(p.kinds, "수유 10 · 잠 5 · 기저귀 4 · 메모 1")
        XCTAssertEqual(p.skips, ["이미 있는 기록 4건", "14일보다 오래된 기록 2건", "앞으로의 시각 1건", "읽을 수 없는 줄 4건", "가져오지 않는 종류 1건"])
        XCTAssertEqual(p.latestFeed, "가장 늦은 수유 오후 12:00")   // 오늘이면 시각만
        XCTAssertEqual(p.zoneNote, "시각은 한국 시간으로 읽어요")
        XCTAssertEqual(p.buttons, ["가져오기", "취소"])
        XCTAssertEqual(CareText.importSkipTitle, "가져오지 않는 것")
        XCTAssertEqual(CareText.importZoneNote, "시각은 한국 시간으로 읽어요")
        XCTAssertEqual([CareText.importEntry, CareText.importEntrySub], ["다른 앱 기록 가져오기", "아이곁·Huckleberry·Nara에서 내보낸 CSV를 읽어요"])
        XCTAssertNotNil(k.app.recordCare.importRows(first.rows))
        // 같은 파일을 다시: 가져올 것이 0이면 import.nothing과 '닫기'만.
        let before = k.app.care.care
        vm.startImport()
        let step = await vm.loaded(.text(t))
        guard case .preview(let second) = step else { return XCTFail("미리 보기") }
        let empty = ImportPreview(second, now: CareTime.date(Self.now))
        XCTAssertEqual(empty.title, "가져올 기록이 없어요")
        XCTAssertNil(empty.kinds)
        XCTAssertNil(empty.latestFeed)
        XCTAssertEqual(empty.buttons, ["닫기"])
        XCTAssertEqual(empty.skips, [])        // 건너뛴 이유·시간대 문구도 보이지 않는다(안드로이드와 같음)
        XCTAssertNil(empty.zoneNote)
        // '닫기'는 저장하지 않고 닫는다.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("EXAMPLE-again.csv")
        try Data(t.utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        vm.picked(.success(url))
        await vm.importJob?.value
        XCTAssertEqual(vm.importStep, step)
        vm.confirmImport()
        XCTAssertEqual(vm.importStep, .idle)
        XCTAssertEqual(k.app.care.care, before)
    }
}
