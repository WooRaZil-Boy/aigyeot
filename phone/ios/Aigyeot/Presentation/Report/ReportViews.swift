import SwiftUI

private typealias DS = DesignSystem

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
            ScrollView {
                VStack(spacing: 0) {
                    StatusBands(gap: 12)
                    VStack(alignment: .leading, spacing: 0) {
                        if vm.reports.isEmpty { Text("주행이 끝나면 여기에 리포트가 와요").typeStyle(.body).foregroundStyle(DS.textTertiary).padding(.vertical, 12) }
                        ForEach(vm.reports) { r in
                            NavigationLink { ReportDetailView(sessionId: r.body.sessionId) } label: { ReportRow(report: r) }.buttonStyle(.press)
                        }
                    }
                    .block(padding: EdgeInsets(top: 8, leading: 20, bottom: 8, trailing: 20))
                }
                .screenPadding()
            }
            .screenBackground()
            .navigationTitle("리포트")
            .navigationBarTitleDisplayMode(.inline)
            .refreshable { await vm.retryNow() }
        }
    }
}

/// 리포트 한 줄(U47): 길 아이콘 원 + "{날짜} {구간}" + "울음 n번". 차 탭·전체 목록·오늘 '최근 리포트'가 모두 이 줄을 쓴다.
struct ReportRow: View {
    let report: StoredReport
    var chevron = true

    var body: some View {
        let b = report.body
        let title = Rfc3339.parse(b.startedAt).flatMap { s in Rfc3339.parse(b.endedAt).map { CareText.reportTitle(start: s, end: $0) } } ?? b.startedAt
        let sub = CareText.reportSub(cries: b.timeline.filter { $0.trigger == .cryAlarm }.count, simulated: b.simulated)
        if chevron { ListRow(icon: .route, title: title, sub: sub) }
        else { ListRow(icon: .route, title: title, sub: sub) { EmptyView() } }
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

/// 리포트 상세(시안 S.report): 머리(날짜·구간·길이 + 큰 '울음 n번' + 숫자 줄) → 에피소드(띠 + 글 줄) → 방법별 성적 → 평소 대비 조향 보정 → 울음 직전 주행 → 차가 대신 한 일 → 제어 기록.
/// 명세서 11장의 다섯 항목을 모두 그린다. 숫자 줄 규칙은 안드로이드와 같다(진행 기록 '결정' 6).
private struct ReportDetailContent: View {
    let report: StoredReport
    /// `song_id`로 내 곡 목록에서 이름을 찾는다. 없으면 "부모 노래".
    let songName: (String?) -> String?

    var body: some View {
        let b = report.body
        let k = ChartData.kpis(b)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 0) {
                    let head = [Rfc3339.parse(b.startedAt).flatMap { s in Rfc3339.parse(b.endedAt).map { CareText.reportTitle(start: s, end: $0) } } ?? b.startedAt,
                                ChartData.reportMinutes(b).map { "\($0)분" }].compactMap { $0 }.joined(separator: " · ")
                    Text(head).typeStyle(.meta).foregroundStyle(DS.textTertiary)
                    Text(CareText.reportSub(cries: b.timeline.filter { $0.trigger == .cryAlarm }.count, simulated: false))
                        .typeStyle(.hero).foregroundStyle(DS.textPrimary).padding(.top, 6)
                    if b.simulated { Badge(text: "시뮬레이션").padding(.top, 6) }
                    // 시안 '그칠 때까지'는 그친 시각이 리포트에 없어 쓰지 않고 '울음 시간'(totalCryS 합)을 쓴다(거짓 시간 방지, 안드로이드와 같다).
                    VStack(alignment: .leading, spacing: 16) {
                        KpiRow(items: [
                            .init(name: "감지까지", value: k.detectS.map(ChartData.seconds) ?? "—"),
                            .init(name: "울음 시간", value: minSec(k.cryS)),
                            .init(name: "차가 한 일", value: "\(k.actionKinds)가지"),
                        ])
                        KpiRow(items: [.init(name: "정차 안내", value: "\(k.stopNotices)번")])
                    }
                    .padding(.top, 16)
                    // 재생이 한 번도 없으면 출력 경로 표시를 넣지 않는다.
                    if b.outputRoute == .rearZone { note("소리 나간 곳 · 뒷좌석") }
                    if b.outputRoute == .defaultZoneFallback { note("소리 나간 곳 · 앞에서도 들림") }
                    if b.truncated == true { note("오래된 에피소드는 줄였어요") }
                }
                .block(padding: EdgeInsets(top: 22, leading: 22, bottom: 20, trailing: 22))

                VStack(alignment: .leading, spacing: 0) {
                    BlockHeader(title: "에피소드")
                    if b.timeline.isEmpty { Text("기록된 에피소드가 없어요").typeStyle(.body).foregroundStyle(DS.textTertiary) }   // 울음이 없었다고 단정하지 않는다
                    ForEach(Array(b.timeline.enumerated()), id: \.element.episodeId) { i, e in
                        if i > 0 { Rectangle().fill(DS.line).frame(height: 1).padding(.vertical, 16) }
                        EpisodeView(episode: e, songName: songName)
                    }
                }
                .block()

                if let stats = b.methodStats, !stats.isEmpty {
                    VStack(alignment: .leading, spacing: 0) {
                        BlockHeader(title: "방법별 성적")
                        MethodDots(rows: methodDotRows(stats))
                        BlockNote(text: "그 소리를 틀었을 때 그친 횟수예요. 소리 덕분인지는 알 수 없어요.")
                    }
                    .block()
                }

                VStack(alignment: .leading, spacing: 0) {
                    BlockHeader(title: "평소 대비 조향 보정")
                    SteeringView(s: b.steering)
                }
                .block()

                if !b.preCryContext.isEmpty {
                    VStack(alignment: .leading, spacing: 0) {
                        BlockHeader(title: "울음 직전 주행")
                        ForEach(Array(b.preCryContext.enumerated()), id: \.element.episodeId) { i, c in
                            if i > 0 { Rectangle().fill(DS.line).frame(height: 1).padding(.vertical, 12) }
                            Text("울기 전 \(c.windowS)초").typeStyle(.caption).foregroundStyle(DS.textTertiary).padding(.bottom, 6)
                            KeyValues(rows: [
                                ("속도", "평균 \(Int(c.speedKmhAvg.rounded())) · 최고 \(Int(c.speedKmhMax.rounded())) km/h"),
                                ("브레이크", "\(c.brakePresses)번"),
                                ("큰 조향 반전", "\(c.steeringReversals)번"),
                            ] + (c.cabinTempC.map { [("실내", String(format: "%.1f℃", $0))] } ?? []))
                            Text("기어 \(c.gear.rawValue)" + (c.turnSignalUsed ? ", 방향지시등 씀" : "") + (c.playing ? ", 소리 재생 중" : "")
                                 + (c.windowOpen == true ? ", 창 열림" : ""))
                                .typeStyle(.note).foregroundStyle(DS.textTertiary).padding(.top, 8)
                        }
                    }
                    .block()
                }

                VStack(alignment: .leading, spacing: 0) {
                    BlockHeader(title: "차가 대신 한 일")
                    if b.carActions.isEmpty { Text("없어요").typeStyle(.body).foregroundStyle(DS.textTertiary) }
                    else { KeyValues(rows: b.carActions.map { (actionText($0.action), "\($0.count)번" + ($0.totalS.map { " · \(minSec($0))" } ?? "")) }) }
                }
                .block()

                VStack(alignment: .leading, spacing: 0) {
                    BlockHeader(title: "제어 기록")
                    if b.controls.isEmpty { Text("없어요").typeStyle(.body).foregroundStyle(DS.textTertiary) }
                    ForEach(Array(b.controls.enumerated()), id: \.offset) { _, c in
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(c.api) · \(c.target)").font(.footnote.monospaced()).foregroundStyle(DS.textPrimary)
                            Text("시도 \(c.attempts) · 성공 \(c.succeeded) · 실패 \(c.failed) · 다시 \(c.retries)").typeStyle(.note).foregroundStyle(DS.textTertiary)
                        }
                        .padding(.vertical, 4)
                    }
                }
                .block()
            }
            .screenPadding()
        }
        .screenBackground()
        .navigationTitle("주행 리포트")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func note(_ s: String) -> some View { Text(s).typeStyle(.caption).foregroundStyle(DS.textTertiary).padding(.top, 10) }
}

/// 이름 · 값 줄(시안 .kv): 이름 흐림, 값 굵게 오른쪽.
private struct KeyValues: View {
    let rows: [(String, String)]
    var body: some View {
        VStack(spacing: 10) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, r in
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    Text(r.0).typeStyle(.valueKey).foregroundStyle(DS.textTertiary)
                    Spacer(minLength: 8)
                    Text(r.1).typeStyle(.chip).foregroundStyle(DS.textPrimary).multilineTextAlignment(.trailing)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }
}

/// 에피소드 하나: 부제("오후 1:24 울음 감지부터 그칠 때까지") + 띠(소리·듣기 틈·표시) + 글 줄(감지 → 음원 → 재생 → 판정 → 재울음 → 정차).
private struct EpisodeView: View {
    let episode: TimelineEpisode
    let songName: (String?) -> String?

    var body: some View {
        let e = episode
        VStack(alignment: .leading, spacing: 0) {
            BlockSub(text: Text("\(clock(e.startedAt)) \(e.trigger == .cryAlarm ? "울음 감지" : "소리 바꾸기 요청")부터" + (e.result == .success ? " 그칠 때까지" : "")))
            if !e.plays.isEmpty {
                let band = ChartData.episode(e) { methodName($0.method, songName($0.songId)) }
                // 화면 읽기: 재생마다 실제 길이(에피소드 끝 시각은 리포트에 없어 전체 길이는 말하지 않는다, 안드로이드와 같다)
                EpisodeBandChart(band: band, summary: "에피소드: " + (band.sounds.map { "\($0.name) \($0.seconds)초" } + band.marks.map(\.label) + band.outcomes).joined(separator: ", "))
                if !band.outcomes.isEmpty {
                    HStack(spacing: 6) { ForEach(band.outcomes, id: \.self) { Badge(text: $0) } }.padding(.top, 8).accessibilityHidden(true)
                }
                Spacer().frame(height: 12)
            }
            VStack(alignment: .leading, spacing: 4) {
                if e.trigger == .cryAlarm, let l = e.alarmLatencyS { line("감지까지", ChartData.seconds(l)) }
                line("소리", e.sound.map { soundLabel($0.method, $0.label) } ?? "틀지 않았어요")
                line("재생", minSec(e.playS))
                ForEach(Array(e.plays.enumerated()), id: \.offset) { _, p in
                    Text("· \(purposeText(p.purpose)) \(methodName(p.method, songName(p.songId))) \(span(p.startedAt, p.endedAt)) \(minSec(p.playS))")
                        .typeStyle(.footnote).foregroundStyle(DS.textTertiary)
                }
                line("판정", resultText(e.result, excluded: e.excludedReason))
                if e.recried { line("다시 울었는지", "다시 울었어요") }
                if let s = e.second { line("2차", secondText(s)) }
                if let st = e.stop { line("정차", stopText(st)) }
                line("총 울음", minSec(e.totalCryS))
            }
        }
    }

    private func line(_ k: String, _ v: String) -> some View {
        HStack(alignment: .top) {
            Text(k).typeStyle(.rowSub).foregroundStyle(DS.textTertiary).frame(width: 84, alignment: .leading)
            Text(v).typeStyle(.meta).foregroundStyle(DS.textPrimary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// 평소 대비 조향 보정(시안 Charts.steer): 평소·울음 중·대응 뒤 세 막대, '울음 중'만 주황. 비교할 수 없으면 이유 한 줄.
private struct SteeringView: View {
    let s: Steering
    var body: some View {
        if !s.available {
            Text(s.unavailableReason == .noCry ? "울음이 없어 비교하지 않았어요" : "비교할 구간이 없어요").typeStyle(.body).foregroundStyle(DS.textTertiary)
        } else {
            let items: [(String, Double?, Bool)] = [("평소", s.baselinePerMin, false), ("울음 중", s.duringCryPerMin, true), ("대응 뒤", s.afterResponsePerMin, false)]
            BlockSub(text: Text("1분당 큰 조향 보정"))
            WeekBarsChart(items: items.map { k, v, hot in
                .init(label: k, value: v, tip: v.map { "\(k) · 1분당 \(ChartData.perMin($0))번" }, hot: hot, valueLabel: v.map(ChartData.perMin), dim: !hot)
            }, hot: DS.cCar, height: 140,
                          summary: "1분당 큰 조향 보정: " + items.compactMap { k, v, _ in v.map { "\(k) \(ChartData.perMin($0))번" } }.joined(separator: ", "))
        }
    }
}

// MARK: 문구

/// 음원 표시 이름(CareText.method 한 곳). 부모 노래는 곡 이름을 덧붙인다.
private func methodName(_ m: SoundMethod, _ song: String?) -> String {
    let base = CareText.method(m)
    return m == .parentSong ? song.map { "\(base)(\($0))" } ?? base : base
}

/// 방법별 성적 점 행렬 줄(순서 규칙은 `CareStats.methodOrder` 한 곳, 안드로이드와 같다).
func methodDotRows(_ stats: [MethodStat]) -> [MethodDots.Row] {
    CareStats.methodOrder(stats).map { MethodDots.Row(id: $0.index, name: soundLabel($0.stat.method, $0.stat.label), ok: $0.stat.successes, n: $0.stat.attempts) }
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
    case .comfortCool: what = "더운 실내로 판단했어요"
    case .feedingWindow: what = "수유 시간대로 판단했어요"
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
        case .noRest: return "안전한 곳에 세우도록 안내하지 못했어요"
        case .parked: break
        }
    }
    switch s.mode {
    case .restOffer: return s.restName.map { "\($0)에 들르자고 제안했어요" } ?? "쉼터에 들르자고 제안했어요"
    case .arrivalNotice: return "도착 안내를 했어요"
    case .noRest: return "경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요"
    case .parked: return s.reason == .noSound ? "차가 선 채로 소리를 틀지 못했어요" : "차가 선 채로 달래기를 멈췄어요"   // U59
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

/// 차가 대신 한 일(car_actions) 이름. 문구표 7.1 report.actions, 안드로이드 `actionText`와 같은 글자.
func actionText(_ a: CarActionKind) -> String {
    switch a {
    case .rearPlayback: return "뒷좌석 재생"
    case .defaultZonePlayback: return "앞에서도 들리게 재생"
    case .seatHeaterOff: return "뒷좌석 열선 끔"   // 아이 자리(2열)만 끈다
    case .rearWindowLock: return "뒷창 잠금"
    case .cabinCool: return "실내 온도 낮춤"   // 성공한 공조 줄만 센다(C355)
    case .readingLightOff: return "독서등 끔"
    case .ambientDim: return "조명 낮춤"
    case .waypointAdded: return "경유지 추가"
    case .arrivalNotice: return "도착 안내"
    case .guardianAlert: return "다른 보호자 알림"
    }
}
