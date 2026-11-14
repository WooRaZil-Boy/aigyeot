@testable import Aigyeot
import XCTest

/// 화면에 보이는 값과 문구.
@MainActor
final class CareTextTests: CareTestCase {
    // MARK: 보이는 값

    func test_마지막_수유는_기록과_차_값_중_늦은_것() {
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T10:00:00+09:00", endUnknown: true))
        _ = app.recordCare.saveEvent(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true, id: "LATE"))
        app.recordCare.delete("LATE")   // 지운 것은 빼고 본다
        XCTAssertEqual(app.today().ui.lastFeedingStart, Self.t("2026-11-05T10:00:00+09:00"))
        var state = app.state.state
        state.feeding = FeedingState(lastStart: Self.t("2026-11-05T10:30:00+09:00"), seq: 1)
        make(care: careStore.value, state: state)
        XCTAssertEqual(app.today().ui.lastFeedingStart, Self.t("2026-11-05T10:30:00+09:00"))
        state.feeding = FeedingState(lastStart: Self.t("2026-11-05T09:00:00+09:00"), seq: 1)
        make(care: careStore.value, state: state)
        XCTAssertEqual(app.today().ui.lastFeedingStart, Self.t("2026-11-05T10:00:00+09:00"))
    }

    func test_자정을_넘는_기록은_시작한_날에_두고_합계는_나눠_센다() {
        let sleep = event(.sleep, "2026-11-05T23:00:00+09:00", end: "2026-11-06T02:00:00+09:00")
        // 다른 시간대에서 적은 기록은 그 시간대의 날짜에 둔다(서울 11-05 10:00 = LA 11-04 17:00).
        let la = event(.note, "2026-11-05T10:00:00+09:00", zone: "America/Los_Angeles")
        let all = [sleep, la]
        XCTAssertEqual(CareRules.events(onDay: "2026-11-05", in: all).map(\.id), [sleep.id])
        XCTAssertTrue(CareRules.events(onDay: "2026-11-06", in: all).isEmpty)
        XCTAssertEqual(CareRules.events(onDay: "2026-11-04", in: all).map(\.id), [la.id])
        XCTAssertEqual(CareFormat.clock(CareTime.date(la.startAt), TimeZone(identifier: la.zone)!), "오후 5:00")
        // 하루 합계는 날짜 경계로 나눈다.
        XCTAssertEqual(CareRules.sleepMs(onDay: "2026-11-05", in: all, zone: seoul), 3_600_000)
        XCTAssertEqual(CareRules.sleepMs(onDay: "2026-11-06", in: all, zone: seoul), 2 * 3_600_000)
    }

    /// 고른 날의 줄(dayItems)이 비면 기록 탭은 가운데 빈 상태 판(달력 고리 + '이 날 기록이 없어요' + '＋ 지난 기록 적기')만 그리고
    /// 목록 끝 '지난 기록 적기' 줄은 숨긴다(그림은 캡처로 확인). 지운 기록만 있는 날도 빈 날이다. 안드로이드와 같은 이름.
    func test_빈_날에는_가운데_지난_기록_적기_하나만_보인다() {
        var care = CareFile()
        let note = event(.note, "2026-11-05T11:00:00+09:00")
        care.events = [event(.feed, "2026-11-04T08:00:00+09:00", endUnknown: true, deletedAt: 1), note]
        let ui = RecordsUiState(care: care, phone: PhoneState())
        let carAt = Self.t("2026-11-05T09:00:00+09:00")
        let car = [CarRow(id: "EXAMPLE-car", sessionId: "s", at: carAt, text: "탑승")]
        XCTAssertTrue(ui.dayItems("2026-11-04", car: car).isEmpty)   // 지운 기록만 있는 날
        XCTAssertEqual(ui.dayItems("2026-11-05", car: []).map(\.id), [note.id])
        let carDay = CareRules.dayKey(CareTime.ms(carAt), .current)   // 차 기록 줄은 기기 시간대의 날
        XCTAssertTrue(ui.dayItems(carDay, car: car).contains { $0.id == "EXAMPLE-car" })
    }

    /// 간격이 5개 미만이면 가짜 막대 없이 쌓인 수만 점으로(읽기 이름 pattern.intervalProgress).
    func test_수유_간격이_5개_미만이면_쌓인_수만_점으로_보인다() {
        clock = Self.t("2026-11-05T12:00:00+09:00")
        let events = ["2026-11-04T20:00:00+09:00", "2026-11-04T23:00:00+09:00", "2026-11-05T02:00:00+09:00", "2026-11-05T06:00:00+09:00"]
            .map { event(.feed, $0, endUnknown: true) }   // 간격 3개
        let p = CareRules.pattern(events, reports: [], now: nowMs, zone: seoul, sleepDiaper: false)
        XCTAssertNil(p.feedIntervalMedianMs)
        XCTAssertEqual(p.feedIntervalCount, 3)
        XCTAssertEqual(CareText.intervalProgress(p.feedIntervalCount), "수유 간격 5개 중 3개 쌓임")
    }

    func test_패턴은_7일_중앙값이고_5개_미만이면_숨긴다() {
        clock = Self.t("2026-11-05T12:00:00+09:00")
        var events = [
            event(.feed, "2026-10-29T23:00:00+09:00", endUnknown: true),   // 7일 밖
            event(.feed, "2026-11-04T06:00:00+09:00", endUnknown: true),   // 다음 수유까지 12시간: 빠진 기록으로 보고 뺀다
            event(.feed, "2026-11-04T18:00:00+09:00", endUnknown: true),
            event(.feed, "2026-11-04T20:00:00+09:00", end: "2026-11-04T20:20:00+09:00"),   // 2시간
            event(.feed, "2026-11-04T23:00:00+09:00", endUnknown: true),   // 3시간
            event(.feed, "2026-11-05T02:00:00+09:00", endUnknown: true),   // 3시간
            event(.feed, "2026-11-05T06:00:00+09:00", endUnknown: true)   // 4시간
        ]
        // 간격 4개(2·3·3·4시간)면 숨긴다.
        XCTAssertNil(CareRules.pattern(events, reports: [], now: nowMs, zone: seoul, sleepDiaper: true).feedIntervalMedianMs)
        events.append(event(.feed, "2026-11-05T11:00:00+09:00", endUnknown: true))      // 5시간
        events.append(event(.feed, "2026-11-05T11:30:00+09:00", endUnknown: true, deletedAt: nowMs))   // 지운 것은 뺀다
        events.append(event(.sleep, "2026-11-04T23:00:00+09:00", end: "2026-11-05T02:00:00+09:00"))
        events.append(event(.sleep, "2026-11-05T03:00:00+09:00", endUnknown: true))      // 길이를 모르는 기록은 합계에서 뺀다
        events.append(event(.diaper, "2026-11-05T07:00:00+09:00"))
        let p = CareRules.pattern(events, reports: [], now: nowMs, zone: seoul, sleepDiaper: true)
        XCTAssertEqual(p.feedIntervalMedianMs, 3 * 3_600_000)   // 2·3·3·4·5시간의 중앙값
        XCTAssertEqual(p.days.map(\.day), ["2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05"])
        XCTAssertEqual(p.days.map(\.feeds), [0, 0, 0, 0, 0, 4, 3])
        XCTAssertEqual(p.days[5].sleepMs, 3_600_000)
        XCTAssertEqual(p.days[6].sleepMs, 2 * 3_600_000)
        XCTAssertEqual(p.days[6].diapers, 1)
        XCTAssertEqual(p.days.map(\.sleepRecorded), [false, false, false, false, false, true, true])   // 자정을 넘은 잠은 다음 날에도 센다
        // 진행 중·끝 모름 잠만 있는 날은 분모에 넣지 않는다.
        let onlyOpen = CareRules.pattern([event(.sleep, "2026-11-05T09:00:00+09:00"), event(.sleep, "2026-11-04T09:00:00+09:00", endUnknown: true)],
                                         reports: [], now: nowMs, zone: seoul, sleepDiaper: true)
        XCTAssertEqual(onlyOpen.sleepDays, 0)
        XCTAssertNil(onlyOpen.sleepMsPerDay)
        XCTAssertEqual(CareText.patternFewIntervals, "기록이 더 쌓이면 보여 드려요")
        XCTAssertEqual(CareText.patternFootnote, "직접 적은 기록만 세어요. 빠진 기록이 있으면 실제와 다를 수 있어요.")
    }

    func test_진행_중_경과는_시작_시각으로_계산한다() {
        app.recordCare.start(.feed, side: .left)
        let saves = careStore.saves
        clock.addTimeInterval(12 * 60 + 34)
        let running = CareRules.inProgress(.feed, in: app.care.care.events)!
        XCTAssertEqual(CareRules.elapsedMs(running, now: nowMs), (12 * 60 + 34) * 1000)
        XCTAssertEqual(CareFormat.timer(CareRules.elapsedMs(running, now: nowMs)), "12:34")
        XCTAssertEqual(CareText.feedingNow(running, now: nowMs), "수유 중 · 12분 · 왼쪽")
        clock.addTimeInterval(50 * 60)
        XCTAssertEqual(CareFormat.timer(CareRules.elapsedMs(running, now: nowMs)), "1:02:34")
        XCTAssertEqual(careStore.saves, saves)   // 시간이 흘러도 저장하지 않는다
    }

    func test_잠과_기저귀를_끄면_버튼과_패턴에서_숨긴다() {
        XCTAssertFalse(app.care.care.sleepDiaperEnabled)   // 기본 꺼짐
        XCTAssertEqual(CareRules.quickKinds(sleepDiaper: false), [.feed, .note])
        XCTAssertEqual(CareRules.quickKinds(sleepDiaper: true), [.feed, .note, .sleep, .diaper])
        app.recordCare.setSleepDiaper(true)
        XCTAssertTrue(careStore.value.sleepDiaperEnabled)
        let sleep = event(.sleep, "2026-11-05T09:00:00+09:00", end: "2026-11-05T10:00:00+09:00")
        XCTAssertEqual(app.recordCare.saveEvent(sleep), .saved)
        app.recordCare.setSleepDiaper(false)
        // 이미 적은 기록은 지우지 않고 타임라인에 남는다.
        XCTAssertEqual(CareRules.events(onDay: "2026-11-05", in: app.care.care.events).map(\.id), [sleep.id])
        let p = CareRules.pattern(app.care.care.events, reports: [], now: nowMs, zone: seoul, sleepDiaper: false)
        XCTAssertTrue(p.days.allSatisfy { $0.sleepMs == nil && $0.diapers == nil })
        XCTAssertEqual(CareText.sleepDiaperToggle, "잠·기저귀도 적기")
        XCTAssertEqual(CareText.sleepDiaperToggleNote, "다른 앱에 적고 있다면 꺼도 돼요")
    }

    /// 결과·뺀 이유 낱말은 차·두 앱이 같은 글자다(이름: 값 콜론·열거 이름 없음). 안드로이드와 같은 이름의 시험.
    func test_리포트_결과와_뺀_이유는_정해진_낱말로_보인다() {
        XCTAssertEqual([.success, .failure, .undetermined, nil].map { CareText.result($0) }, ["그침", "계속 울음", "판정하지 못함", "1차 시도 없음"])
        let reasons: [ExcludedReason] = [.driverStop, .call, .gearToPark, .startedInPark, .switchSound, .feedingWindow, .sessionCap, .sessionEnd,
                                         .micError, .detectorTimeout, .inputSilenced, .focusLoss, .suspend]
        XCTAssertEqual(reasons.map(CareText.excluded), ["운전자가 멈춤", "통화 중", "정차 중", "정차 중", "소리 바꾸기 요청", "수유 시간대", "달래기 20분 끝남",
                                                        "탑승이 끝남", "마이크 오류", "감지 시간 초과", "마이크 입력이 막힘", "소리를 이어서 틀지 못함", "차가 절전에 들어감"])
        XCTAssertEqual(CareText.method(.parentSong), "우리 노래")
    }

    /// copy-audit 14-1: 리포트 에피소드 블록 머리는 시작 사건으로 정한다(낱말은 episodeHead).
    func test_리포트_울음_블록_머리는_시작_사건으로_정한다() {
        XCTAssertEqual(CareText.episodeSectionHead([]), "울음")   // 빈 목록('기록된 울음이 없어요')
        XCTAssertEqual(CareText.episodeSectionHead([.cryAlarm, .cryAlarm]), "울음")
        XCTAssertEqual(CareText.episodeSectionHead([.switchSound]), "소리 바꾸기 요청")
        XCTAssertEqual(CareText.episodeSectionHead([.switchSound, .cryAlarm]), "울음과 소리 바꾸기 요청")
    }

    /// 소리를 틀지 않은 울음은 '소리 · 재생 없음'만 두고 '재생' 줄을 빼 같은 말을 두 번 하지 않는다(안드로이드 episodeSoundLines와 같다).
    func test_틀지_않은_울음은_재생_줄_없이_소리_재생_없음만_보인다() throws {
        let data = try Data(contentsOf: ProtocolFixturesTests.dir.appendingPathComponent("report_summary_handoff.json"))
        var e = try ProtocolCoding.decoder.decode(Envelope.self, from: data).decodeBody(ReportSummaryBody.self).timeline[0]
        e.trigger = .cryAlarm
        e.alarmLatencyS = 2.4
        e.sound = SoundRef(method: .lullaby, label: "자장가")
        e.playS = 75
        XCTAssertEqual(episodeSoundLines(e).map(\.0), ["감지까지", "소리", "재생"])
        XCTAssertEqual(episodeSoundLines(e).last?.1, "1분 15초")
        e.sound = nil
        e.playS = 0
        XCTAssertEqual(episodeSoundLines(e).map(\.0), ["감지까지", "소리"])
        XCTAssertEqual(episodeSoundLines(e).last?.1, "재생 없음")
        e.trigger = .switchSound   // 소리 바꾸기 요청으로 연 울음은 '감지까지'가 없다
        XCTAssertEqual(episodeSoundLines(e).map(\.0), ["소리"])
    }

    func test_리포트에서_차_기록_줄을_만든다() throws {
        let data = try Data(contentsOf: ProtocolFixturesTests.dir.appendingPathComponent("report_summary_handoff.json"))
        let env = try ProtocolCoding.decoder.decode(Envelope.self, from: data)
        var body = try env.decodeBody(ReportSummaryBody.self)
        let report = StoredReport(messageId: env.id, seq: 1, receivedAt: clock, body: body)
        let rows = CareRules.carRows([report], zone: seoul)
        XCTAssertEqual(rows.map(\.text), ["탑승 · 오후 3:00–3:49", "차에서 울음 · 오후 3:10 · 자장가 · 계속 울음"])
        XCTAssertEqual(rows.map(\.at), [Self.t("2026-11-05T15:00:00+09:00"), Self.t("2026-11-05T15:10:00+09:00")])
        XCTAssertEqual(rows.map(\.sessionId), [body.sessionId, body.sessionId])   // 누르면 그 리포트 상세로 간다
        // 결과별 문구, 리포트에 없는 값은 비워 둔다.
        body.timeline[0].result = .success
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: body)], zone: seoul)[1].text,
                       "차에서 울음 · 오후 3:10 · 자장가 · 그침")
        body.timeline[0].result = .undetermined
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: body)], zone: seoul)[1].text,
                       "차에서 울음 · 오후 3:10 · 자장가 · 판정하지 못함")
        body.timeline[0].sound = SoundRef(method: .parentSong, label: "나비야")   // 소리는 방법 이름만
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: body)], zone: seoul)[1].text,
                       "차에서 울음 · 오후 3:10 · 우리 노래 · 판정하지 못함")
        body.timeline[0].sound = SoundRef(method: .whiteNoise, label: "x")
        body.timeline[0].result = nil
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: body)], zone: seoul)[1].text,
                       "차에서 울음 · 오후 3:10 · 백색소음")
        var short = body   // 같은 분에 끝난 탑승
        short.startedAt = "2026-11-05T16:57:10+09:00"; short.endedAt = "2026-11-05T16:57:30+09:00"
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: short)], zone: seoul)[0].text,
                       "탑승 · 오후 4:57")
        body.timeline[0].sound = nil
        XCTAssertEqual(CareRules.carRows([StoredReport(messageId: "m", seq: 1, receivedAt: clock, body: body)], zone: seoul)[1].text,
                       "차에서 울음 · 오후 3:10")
        // 운전자가 소리를 요청해 시작한 에피소드는 줄을 만들지 않고 울음 수에도 넣지 않는다.
        var withRequest = try env.decodeBody(ReportSummaryBody.self)
        var request = withRequest.timeline[0]
        request.episodeId = "REQ"; request.trigger = .switchSound
        withRequest.timeline.append(request)
        let both = StoredReport(messageId: env.id, seq: 1, receivedAt: clock, body: withRequest)
        XCTAssertEqual(CareRules.carRows([both], zone: seoul).map(\.id), ["drive-\(withRequest.sessionId)", withRequest.timeline[0].episodeId])
        // 리포트에 있는 차 울음 에피소드는 패턴의 하루 울음 에피소드 수에 들어간다.
        let p = CareRules.pattern([], reports: [both], now: nowMs + 6 * 3_600_000, zone: seoul, sleepDiaper: false)
        XCTAssertEqual(p.days.last?.carEpisodes, 1)
    }

    // MARK: 전체 문구

    func test_첫_안내_글은_정한_문구_그대로이고_원문_넷은_시트에_남는다() {
        // 머리 이름, 두 줄 제목(마침표 없음, 줄바꿈 하나)
        XCTAssertEqual(CareText.introTitle, "아이곁")
        XCTAssertEqual(CareText.introLead, "차의 아이곁과\n이 휴대폰을 연결해요")
        XCTAssertEqual(CareText.introLead.filter { $0 == "\n" }.count, 1)
        XCTAssertFalse(CareText.introLead.hasSuffix("."))
        // intro.short.*: 첫 화면 줄 넷(제목 / 부제, 이 순서)
        XCTAssertEqual(CareText.introShort.map(\.title), ["차와 주고받아요", "울음소리와 차 위치는 보내지 않아요", "아이곁 중계 서버를 거쳐요", "차에서 물으면 AI 서버를 써요"])
        XCTAssertEqual(CareText.introShort.map(\.sub), ["수유 시작 시각, 우리 노래, 수유·잠·기저귀 요약, 주행 리포트, 인계 알림이에요", "차 안 마이크 소리도 보내지 않아요",
                                                       "인계 알림은 Google·Apple 알림 서버도 거쳐요", "답을 만들려고 수유·잠·기저귀 숫자를 보내요"])
        XCTAssertEqual([CareText.introMore, CareText.introMoreA11y, CareText.introSheetTitle, CareText.introSheetClose], ["자세히 보기", "오가는 정보 자세히 보기", "오가는 정보", "닫기"])
        // intro.sheet.*: 라벨 넷, 원문은 intro.* 글자 그대로(원문 보존)
        let sheet = CareText.introSheet
        XCTAssertEqual(sheet.map(\.label), ["주고받는 것", "보내지 않는 것", "거치는 서버", "AI 서버"])
        XCTAssertEqual([sheet[0].label + ": " + sheet[0].text, sheet[1].label + ": " + sheet[1].text, sheet[2].text, sheet[3].text], [
            "주고받는 것: 수유 시작 시각, 우리 노래 녹음, 수유·잠·기저귀 기록 요약(시각·횟수·잠 시간), 주행 리포트, 인계 알림 문장",
            "보내지 않는 것: 아기 울음 소리, 차 안 마이크 소리, 차의 위치",
            "모두 아이곁 중계 서버를 거쳐 오가요. 인계 알림은 Google·Apple 알림 서버도 거쳐요.",
            "차에서 아기 상황을 물으면, 답을 만들려고 수유·잠·기저귀 숫자를 AI 서버로 보내요."
        ])
        XCTAssertEqual([CareText.connectCar, CareText.recordOnly], ["차와 연결하기", "먼저 기록만 쓸게요"])
        XCTAssertEqual([CareText.pairConfirm, CareText.pairConfirmSwitch], ["연결하기", "끊고 연결하기"])
        XCTAssertEqual(CareText.bannerClose, "닫기")
    }

    func test_리포트_목록_줄과_제목은_정한_문구_그대로() {
        // report.row.title "{날짜} {구간}", report.row.sub, report.detail.title "{날짜}"
        XCTAssertEqual(CareText.reportTitle(start: Self.t("2026-11-06T02:54:00+09:00"), end: Self.t("2026-11-06T03:44:00+09:00"), zone: seoul),
                       "11월 6일(금) 오전 2:54–3:44")
        XCTAssertEqual(CareText.reportTitle(start: Self.t("2026-11-06T23:50:00+09:00"), end: Self.t("2026-11-07T00:20:00+09:00"), zone: seoul),
                       "11월 6일(금) 오후 11:50–오전 12:20")
        XCTAssertEqual(CareText.reportSub(cries: 3, simulated: false), "울음 3번")
        XCTAssertEqual(CareText.reportSub(cries: 0, simulated: false), "울음 기록 없음")
        XCTAssertEqual(CareText.reportSub(cries: 1, simulated: true), "울음 1번 · 연습 장면")
        XCTAssertEqual(CareText.reportSub(cries: 0, simulated: true), "울음 기록 없음 · 연습 장면")
    }

    func test_기록_줄과_패턴_문구는_정한_문구_그대로() {
        // log.row.*
        var feed = event(.feed, "2026-11-05T13:10:00+09:00", end: "2026-11-05T13:52:00+09:00")
        feed.feedSide = .left
        XCTAssertEqual(CareText.row(feed), "수유 · 오후 1:10–1:52 · 왼쪽")
        feed.feedSide = .bottle; feed.amountMl = 120
        XCTAssertEqual(CareText.row(feed), "수유 · 오후 1:10–1:52 · 젖병 120ml")
        XCTAssertEqual(CareText.row(event(.feed, "2026-11-05T13:10:00+09:00", endUnknown: true)), "수유 · 오후 1:10 · 끝 시각 모름")
        var open = event(.feed, "2026-11-05T13:10:00+09:00", endUnknown: true); open.feedSide = .left
        XCTAssertEqual(CareText.row(open), "수유 · 오후 1:10 · 왼쪽 · 끝 시각 모름")
        open.feedSide = .bottle; open.amountMl = 120
        XCTAssertEqual(CareText.row(open), "수유 · 오후 1:10 · 젖병 120ml · 끝 시각 모름")
        // 같은 분에 끝난 기록
        var short = event(.feed, "2026-11-05T16:57:10+09:00", end: "2026-11-05T16:57:30+09:00"); short.feedSide = .left
        XCTAssertEqual(CareText.row(short), "수유 · 오후 4:57 · 왼쪽")
        XCTAssertEqual(CareText.row(event(.sleep, "2026-11-05T16:57:10+09:00", end: "2026-11-05T16:57:30+09:00")), "잠 · 오후 4:57 · 1분 미만")
        XCTAssertEqual(CareText.todayTitle(nickname: "콩이"), "콩이의 오늘")
        XCTAssertEqual(CareText.todayTitle(nickname: nil), "오늘")
        XCTAssertEqual(CareText.row(event(.feed, "2026-11-05T13:10:00+09:00")), "수유 중 · 오후 1:10부터")
        XCTAssertEqual(CareText.row(event(.sleep, "2026-11-05T11:50:00+09:00", end: "2026-11-05T13:00:00+09:00")),
                       "잠 · 오전 11:50–오후 1:00 · 1시간 10분")
        XCTAssertEqual(CareText.row(event(.sleep, "2026-11-05T13:10:00+09:00", endUnknown: true)), "잠 · 오후 1:10 · 끝 시각 모름")
        XCTAssertEqual(CareText.row(event(.sleep, "2026-11-05T13:10:00+09:00")), "자는 중 · 오후 1:10부터")
        var diaper = event(.diaper, "2026-11-05T13:10:00+09:00"); diaper.diaper = .dry
        XCTAssertEqual(CareText.row(diaper), "기저귀 · 오후 1:10 · 깨끗함")
        var note = event(.note, "2026-11-05T13:10:00+09:00"); note.note = "트림 잘 함"
        XCTAssertEqual(CareText.row(note), "메모 · 오후 1:10 · 트림 잘 함")
        XCTAssertEqual(DiaperKind.allCases.map(CareText.diaperLabel), ["소변", "대변", "둘 다", "깨끗함"])
        // log.date.*
        XCTAssertEqual(CareText.dayTitle("2026-11-05", now: clock, zone: seoul), "오늘")
        XCTAssertEqual(CareText.dayTitle("2026-11-04", now: clock, zone: seoul), "어제")
        XCTAssertEqual(CareText.dayTitle("2026-11-03", now: clock, zone: seoul), "11월 3일(화)")
        // sheet.title.*·sheet.edit.*는 쓰지 않는다(질문이 대신, CareDraftTests)
        // pattern.value: 평균은 그 종류 기록이 있는 날만 나눠 센다.
        let days = (0..<7).map { i in
            CareDayStat(day: "d\(i)", feeds: [0, 0, 0, 0, 7, 8, 8][i], sleepMs: i == 6 ? 7 * 3_600_000 : 0, sleepRecorded: i == 6,
                        diapers: 0, carEpisodes: i == 6 ? 2 : 0)
        }
        let p = CarePattern(days: days, feedIntervalMedianMs: 170 * 60_000)
        XCTAssertEqual(p.feedDays, 3)
        XCTAssertEqual(p.feedsPerDay.map(CareText.avgCount), "평균 7.7번")
        XCTAssertEqual(CareText.basedOn(days: p.feedDays), "3일 기준")
        XCTAssertEqual(p.sleepMsPerDay.map(CareText.avgDuration), "평균 7시간")
        XCTAssertEqual(p.sleepDays, 1)
        XCTAssertNil(p.diapersPerDay)   // 기록한 날이 없다
        XCTAssertEqual(CareText.noRecords, "기록 없음")
        XCTAssertEqual(CareText.basedOn(days: p.diaperDays), "0일 기준")
        XCTAssertEqual(p.feedIntervalMedianMs.map(CareFormat.length), "2시간 50분")
        XCTAssertEqual(CareText.times(p.carCries), "2번")
        // 길이·평균은 "방금"을 쓰지 않는다.
        XCTAssertEqual(CareFormat.length(0), "0분")
        XCTAssertEqual(CareFormat.length(59_999), "1분 미만")
        XCTAssertEqual(CareFormat.length(130 * 60_000), "2시간 10분")
        XCTAssertEqual(CareText.avgDuration(30_000), "평균 1분 미만")
        XCTAssertEqual(CareText.row(event(.sleep, "2026-11-05T13:10:00+09:00", end: "2026-11-05T13:10:00+09:00")), "잠 · 오후 1:10 · 0분")
        // about.*
        XCTAssertEqual(CareText.version("1.0.0"), "버전 1.0.0")
        XCTAssertEqual(CareText.oss, "오픈소스 라이선스")
        // 목록의 라이선스 전문이 앱 묶음에 모두 들어 있다(빠지면 누른 화면이 비어 보인다)
        XCTAssertFalse(OssEntry.all.isEmpty)
        for e in OssEntry.all { for l in e.licenses { XCTAssertFalse((OssEntry.text(l.file) ?? "").isEmpty, "\(e.name) \(l.file)") } }
        // export.fileName
        XCTAssertEqual(CareText.exportFileName(clock, zone: seoul), "aigyeot-care-20261105.csv")
    }

    // MARK: 형식

    func test_시각과_기간_형식() {
        let d = Self.t("2026-11-05T12:52:00+09:00")
        XCTAssertEqual(CareFormat.clock(d, seoul), "오후 12:52")
        XCTAssertEqual(CareFormat.clock(Self.t("2026-11-05T00:05:00+09:00"), seoul), "오전 12:05")
        XCTAssertEqual(CareFormat.day(d, seoul), "11월 5일(목)")
        XCTAssertEqual(CareFormat.dateTime(d, seoul), "11월 5일(목) 오후 12:52")   // 인계 알림 상세(날짜 형식 + 시각)
        XCTAssertEqual(CareFormat.duration(59_999), "방금")
        XCTAssertEqual(CareFormat.duration(60_000), "1분")
        XCTAssertEqual(CareFormat.duration(12 * 60_000), "12분")
        XCTAssertEqual(CareFormat.duration(120 * 60_000), "2시간")
        XCTAssertEqual(CareFormat.duration(130 * 60_000), "2시간 10분")
        XCTAssertEqual(CareText.ago(130 * 60_000), "2시간 10분 전")
        XCTAssertEqual(CareText.ago(30_000), "방금")
        // 인계 알림 경과도 경과 형식(분을 버리지 않는다).
        let at = "2026-11-05T09:50:00+09:00"
        XCTAssertEqual(ago(at, now: Self.t("2026-11-05T12:00:00+09:00")), "2시간 10분 전")
        XCTAssertEqual(ago(at, now: Self.t("2026-11-05T11:50:00+09:00")), "2시간 전")
        XCTAssertEqual(ago(at, now: Self.t("2026-11-05T09:50:30+09:00")), "방금")
        XCTAssertEqual(ago(at, now: Self.t("2026-11-05T10:02:00+09:00")), "12분 전")
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T13:10:00+09:00"), Self.t("2026-11-05T13:52:00+09:00"), seoul), "오후 1:10–1:52")
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T11:50:00+09:00"), Self.t("2026-11-05T12:20:00+09:00"), seoul), "오전 11:50–오후 12:20")
        // 같은 분이면 시각 하나, 1초라도 분이 넘어가면 구간
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T16:57:10+09:00"), Self.t("2026-11-05T16:57:30+09:00"), seoul), "오후 4:57")
        XCTAssertEqual(CareFormat.clockRange(Self.t("2026-11-05T16:57:59+09:00"), Self.t("2026-11-05T16:58:00+09:00"), seoul), "오후 4:57–4:58")
        XCTAssertEqual(CareFormat.timer(754_000), "12:34")
        XCTAssertEqual(CareFormat.timer(3_754_000), "1:02:34")
    }
}
