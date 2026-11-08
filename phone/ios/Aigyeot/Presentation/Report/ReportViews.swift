import SwiftUI

/// 시각은 기기 언어와 관계없이 "오후 12:52", 구간은 "오전 3:04–3:05"(7.1 시각 형식, U20·U47).
private func clock(_ rfc: String) -> String { Rfc3339.parse(rfc).map { CareFormat.clock($0) } ?? rfc }

private func span(_ start: String, _ end: String) -> String {
    guard let s = Rfc3339.parse(start), let e = Rfc3339.parse(end) else { return "\(start)–\(end)" }
    return CareFormat.clockRange(s, e, .current)
}

private func minSec(_ s: Int) -> String { s >= 60 ? "\(s / 60)분 \(s % 60)초" : "\(s)초" }

/// 리포트 목록(guardian 오늘 '최근 리포트'에서 연다. 안드로이드 `ReportsScreen`과 같다).
struct ReportListView: View {
    var body: some View {
        Scoped({ $0.reports() }) { vm in
            List {
                if vm.reports.isEmpty { Text("주행이 끝나면 여기에 리포트가 와요").foregroundStyle(DesignSystem.textSecondary) }
                ForEach(vm.reports) { r in
                    NavigationLink { ReportDetailView(sessionId: r.body.sessionId) } label: { ReportRow(report: r) }
                }
            }
            .screenBackground()
            .navigationTitle("리포트")
            .safeAreaInset(edge: .top) { StatusBands().padding(.horizontal, 16) }
            .refreshable { await vm.retryNow() }
        }
    }
}

struct ReportRow: View {
    let report: StoredReport

    var body: some View {
        let b = report.body
        // U47: 차 탭·전체 목록·guardian '최근 리포트'가 모두 이 줄을 쓴다.
        VStack(alignment: .leading, spacing: 4) {
            Text(Rfc3339.parse(b.startedAt).flatMap { s in Rfc3339.parse(b.endedAt).map { CareText.reportTitle(start: s, end: $0) } } ?? b.startedAt)
                .font(DesignSystem.headline)
            Text(CareText.reportSub(cries: b.timeline.filter { $0.trigger == .cryAlarm }.count, simulated: b.simulated))
                .font(DesignSystem.caption).foregroundStyle(DesignSystem.textSecondary)
        }
    }
}

/// 리포트 상세(세션 id로 연다).
struct ReportDetailView: View {
    let sessionId: String

    var body: some View {
        Scoped({ [sessionId] in $0.reportDetail(sessionId: sessionId) }) { vm in
            if let r = vm.ui.report { ReportDetailContent(report: r, songName: vm.ui.songName) }
        }
    }
}

/// 리포트 상세: 명세서 11장의 다섯 항목을 같은 순서로 그린다(타임라인 → 직전 맥락 → 조향 → 차가 한 일 → 제어 기록).
private struct ReportDetailContent: View {
    let report: StoredReport
    /// `song_id`로 내 곡 목록에서 이름을 찾는다. 없으면 "부모 노래".
    let songName: (String?) -> String?

    var body: some View {
        let b = report.body
        List {
            Section {
                Text(span(b.startedAt, b.endedAt)).font(DesignSystem.headline)   // U47: 제목은 날짜, 첫 줄은 구간
                if b.simulated { Text("시뮬레이션으로 만든 리포트예요").foregroundStyle(DesignSystem.warning) }
                // 재생이 한 번도 없으면 출력 경로 표시를 넣지 않는다.
                if b.outputRoute == .rearZone { LabeledContent("소리 나간 곳", value: "뒷좌석") }
                if b.outputRoute == .defaultZoneFallback { LabeledContent("소리 나간 곳", value: "앞에서도 들림") }
                if b.truncated == true { Text("오래된 에피소드는 줄였어요").font(.footnote).foregroundStyle(DesignSystem.textSecondary) }
            }

            Section {
                if b.timeline.isEmpty { Text("기록된 에피소드가 없어요").foregroundStyle(DesignSystem.textSecondary) }   // 울음이 없었다고 단정하지 않는다
                ForEach(b.timeline, id: \.episodeId) { EpisodeView(episode: $0, songName: songName) }
            } header: { SectionHeader("에피소드") }

            Section {
                if b.preCryContext.isEmpty { Text("없어요").foregroundStyle(DesignSystem.textSecondary) }
                ForEach(b.preCryContext, id: \.episodeId) { c in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("울기 전 \(c.windowS)초").font(.subheadline.bold())
                        Text("속도 평균 \(Int(c.speedKmhAvg.rounded())) · 최고 \(Int(c.speedKmhMax.rounded())) km/h, 브레이크 \(c.brakePresses)번, 큰 조향 반전 \(c.steeringReversals)번")
                        Text("기어 \(c.gear.rawValue)" + (c.turnSignalUsed ? ", 방향지시등 씀" : "") + (c.playing ? ", 소리 재생 중" : "")
                             + (c.cabinTempC.map { String(format: ", 실내 %.1f℃", $0) } ?? "")
                             + (c.windowOpen == true ? ", 창 열림" : ""))
                            .foregroundStyle(DesignSystem.textSecondary)
                    }
                    .font(.footnote)
                }
            } header: { SectionHeader("울음 직전 주행") }

            Section { SteeringView(s: b.steering) } header: { SectionHeader("평소 대비 조향 보정") }

            Section {
                if b.carActions.isEmpty { Text("없어요").foregroundStyle(DesignSystem.textSecondary) }
                ForEach(b.carActions, id: \.action) { a in
                    LabeledContent(actionText(a.action), value: "\(a.count)번" + (a.totalS.map { " · \(minSec($0))" } ?? ""))
                }
            } header: { SectionHeader("차가 대신 한 일") }

            Section {
                if b.controls.isEmpty { Text("없어요").foregroundStyle(DesignSystem.textSecondary) }
                ForEach(Array(b.controls.enumerated()), id: \.offset) { _, c in
                    VStack(alignment: .leading) {
                        Text("\(c.api) · \(c.target)").font(.footnote.monospaced())
                        Text("시도 \(c.attempts) · 성공 \(c.succeeded) · 실패 \(c.failed) · 다시 \(c.retries)").font(.footnote).foregroundStyle(DesignSystem.textSecondary)
                    }
                }
            } header: { SectionHeader("제어 기록") }

            if let stats = b.methodStats, !stats.isEmpty {
                Section {
                    ForEach(methodStatRows(stats), id: \.id) { r in
                        LabeledContent(r.title, value: r.value)
                    }
                } header: { SectionHeader("소리별 결과") } footer: {
                    SectionHeader("그 소리를 틀었을 때 그친 횟수예요. 소리 덕분인지는 알 수 없어요.")
                }
            }
        }
        .screenBackground()
        .labeledContentStyle(TokenLabeledContentStyle())   // 값 글자 대비 4.5:1(8장)
        .navigationTitle(Rfc3339.parse(b.startedAt).map { CareFormat.day($0) } ?? b.startedAt)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct EpisodeView: View {
    let episode: TimelineEpisode
    let songName: (String?) -> String?

    var body: some View {
        let e = episode
        VStack(alignment: .leading, spacing: 6) {
            // 감지 → 음원 → 재생 시간 → 판정 → 재울음 → 정차
            Text("\(clock(e.startedAt)) \(e.trigger == .cryAlarm ? "울음 감지" : "소리 바꾸기 요청")")
                .font(.subheadline.bold())
            if e.trigger == .cryAlarm, let l = e.alarmLatencyS { line("감지까지", String(format: "%.1f초", l)) }
            line("소리", e.sound.map { soundLabel($0.method, $0.label) } ?? "틀지 않았어요")
            line("재생", minSec(e.playS))
            ForEach(Array(e.plays.enumerated()), id: \.offset) { _, p in
                Text("· \(purposeText(p.purpose)) \(methodName(p.method, songName(p.songId))) \(span(p.startedAt, p.endedAt)) \(minSec(p.playS))")
                    .font(.caption).foregroundStyle(DesignSystem.textSecondary)
            }
            line("판정", resultText(e.result, excluded: e.excludedReason))
            line("다시 울었는지", e.recried ? "다시 울었어요" : "다시 울지 않았어요")
            if let s = e.second {
                line("2차", secondText(s))
            }
            if let st = e.stop { line("정차", stopText(st)) }
            line("총 울음", minSec(e.totalCryS))
        }
        .font(.footnote)
        .padding(.vertical, 4)
    }

    private func line(_ k: String, _ v: String) -> some View {
        HStack(alignment: .top) { Text(k).foregroundStyle(DesignSystem.textSecondary).frame(width: 84, alignment: .leading); Text(v) }
    }
}

private struct SteeringView: View {
    let s: Steering
    var body: some View {
        if !s.available {
            Text(s.unavailableReason == .noCry ? "울음이 없어 비교하지 않았어요" : "비교할 구간이 없어요").foregroundStyle(DesignSystem.textSecondary)
        } else {
            if let v = s.baselinePerMin { LabeledContent("평소", value: String(format: "분당 %.1f번", v)) }
            if let v = s.duringCryPerMin { LabeledContent("울음 동안", value: String(format: "분당 %.1f번", v)) }
            if let v = s.afterResponsePerMin { LabeledContent("대응 뒤", value: String(format: "분당 %.1f번", v)) }
        }
    }
}

// MARK: 문구

/// 음원 표시 이름. 부모 노래는 곡 이름을 함께 보인다.
private func methodName(_ m: SoundMethod, _ song: String?) -> String {
    switch m {
    case .whiteNoise: return "백색소음"
    case .lullaby: return "자장가"
    case .parentSong: return song.map { "부모 노래(\($0))" } ?? "부모 노래"
    case .womb: return "뱃속 소리"
    case .unknown: return "기타"
    }
}

/// 방법별 성적 행. 모르는 소리가 둘이어도 두 행(method를 행 id로 쓰지 않는다).
struct MethodStatRow: Identifiable {
    let id: Int
    let title: String
    let value: String
}

func methodStatRows(_ stats: [MethodStat]) -> [MethodStatRow] {
    stats.enumerated().map { i, s in
        MethodStatRow(id: i, title: soundLabel(s.method, s.label), value: s.attempts < 3 ? "아직 모름" : "\(s.attempts)번 중 \(s.successes)번 그침")
    }
}

/// 차가 보낸 label을 보이되, 모르는 method(U46)는 label 대신 "기타".
func soundLabel(_ method: SoundMethod, _ label: String) -> String {
    method == .unknown ? methodName(method, nil) : label
}

private func purposeText(_ p: PlayPurpose) -> String {
    switch p { case .autoFirst: return "자동 1차"; case .autoSecond: return "자동 2차"; case .request: return "운전자 요청" }
}

private func resultText(_ r: EpisodeResult?, excluded: ExcludedReason?) -> String {
    if let x = excluded { return "성적에서 뺐어요(\(excludedText(x)))" }
    switch r {
    case .success?: return "그쳤어요"
    case .failure?: return "이어졌어요"
    case .undetermined?: return "판정하지 못했어요"
    case nil: return "1차 시도가 없었어요"
    }
}

private func excludedText(_ x: ExcludedReason) -> String {
    switch x {
    case .driverStop: return "운전자가 멈춤"
    case .call: return "통화 중"
    case .gearToPark: return "기어를 P로 바꿈"
    case .startedInPark: return "정차 중에 시작"
    case .switchSound: return "소리를 바꿈"
    case .sessionCap: return "세션 시간 상한"
    case .feedingWindow: return "수유 시간대"
    case .micError: return "마이크 오류"
    case .focusLoss: return "다른 소리가 이어받음"
    case .detectorTimeout: return "판별 시간 초과"
    case .inputSilenced: return "입력이 무음 처리됨"
    case .suspend: return "차가 절전에 들어감"
    case .sessionEnd: return "세션이 끝나 판정하지 못했어요"
    }
}

private func secondText(_ s: SecondAttempt) -> String {
    let what: String
    switch s.action {
    case .comfortCool: what = "실내를 시원하게 했어요"
    case .feedingWindow: what = "수유 시간대라 안내했어요"
    case .otherSound: what = "다른 소리로 바꿨어요" + (s.sound.map { "(\(soundLabel($0.method, $0.label)))" } ?? "")
    case .focusDenied: what = "소리를 이어서 틀지 못했어요"
    }
    return what + (s.result.map { " · " + resultText($0, excluded: nil) } ?? "")
}

/// U53: 차가 운전자에게 말하지 못한 인계(toldDriver == false)는 제안했다고 보이지 않는다.
func stopHandoffText(_ s: StopInfo) -> String {
    if s.toldDriver == false {
        switch s.mode {
        case .restOffer: return "쉼터는 묻지 못했어요"
        case .arrivalNotice: return "도착 안내는 하지 못했어요"
        case .noRest: return "가까운 쉼터가 없었어요"
        case .parked: break
        }
    }
    switch s.mode {
    case .restOffer: return s.restName.map { "\($0)에 들르자고 제안했어요" } ?? "쉼터에 들르자고 제안했어요"
    case .arrivalNotice: return "도착 안내를 했어요"
    case .noRest: return "가까운 쉼터가 없어 안전한 곳에 세우도록 안내했어요"
    case .parked: return "차가 선 채로 알렸어요"
    }
}

func stopText(_ s: StopInfo) -> String {
    var t = stopHandoffText(s)
    let why: String
    switch s.reason {
    case .attemptLimit: why = "두 번 시도 뒤"
    case .sessionCap: why = "세션 20분 상한"
    case .feedingWindow: why = "수유 시간대"
    case .noSound: why = "뒷좌석 출력 없음"
    }
    if s.waypointAdded { t += " · 경유지 추가" }
    if s.guardianNotified { t += " · 다른 보호자에게 알림" }
    return why + " · " + t
}

private func actionText(_ a: CarActionKind) -> String {
    switch a {
    case .rearPlayback: return "뒷좌석 재생"
    case .defaultZonePlayback: return "기본 존 재생"
    case .seatHeaterOff: return "시트 열선 끔"
    case .rearWindowLock: return "뒷창 잠금"
    case .cabinCool: return "실내 냉방"
    case .readingLightOff: return "독서등 끔"
    case .waypointAdded: return "경유지 추가"
    case .arrivalNotice: return "도착 안내"
    case .guardianAlert: return "보호자 알림"
    }
}
