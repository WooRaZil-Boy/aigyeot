import SwiftUI

/// 차(5장): 연결 상태 → 우리 노래(owner, 지금 화면) → 리포트 목록·상세(지금 화면). 연결 안 됨이면 '차와 연결하기'만.
struct CarView: View {
    @Environment(AppModel.self) private var model
    @State private var player = SongPlayer()
    @State private var recording = false
    /// '다시 녹음'으로 바꿀 거절·실패 곡. 새 녹음을 보낼 때 이 곡을 지우고 넣는다(3곡 한도에 걸리지 않게).
    @State private var replacing: String?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if model.isPaired { CarTile() } else { ConnectCarTile() }
                }
                .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                if model.role == .owner { songs }
                if model.isPaired {
                    Section {   // 7.1 car.section.songs / car.section.reports(U39)
                        if model.state.reports.isEmpty {
                            Text("주행이 끝나면 여기에 리포트가 와요").foregroundStyle(DesignSystem.textSecondary)
                        }
                        ForEach(model.state.reports) { r in
                            NavigationLink { ReportDetailView(report: r) } label: { ReportRow(report: r) }
                        }
                    } header: { SectionHeader("리포트") }
                }
            }
            .screenBackground()
            .navigationTitle(CareText.tabCar)
            .safeAreaInset(edge: .top) { StatusBands().padding(.horizontal, 16) }
            .refreshable { await model.retryNow() }
            .sheet(isPresented: $recording) { RecordSheet(replacing: replacing) }
            .onDisappear { player.stop() }
            // 안드로이드 ON_STOP과 같다: 백그라운드로 가면 미리듣기를 멈춘다(오디오 세션이 .playback이라 이어질 수 있음).
            .onChange(of: scenePhase) { _, p in if p == .background { player.stop() } }
        }
    }

    /// 우리 노래(owner만): 1~3곡 녹음·다시 듣기·지우기, 실험에 쓸 한 곡 고르기, 차로 보낸 상태.
    private var songs: some View {
        Section {
            if model.visibleSongs.isEmpty {
                Text("아직 노래가 없어요. 평소 불러 주던 노래를 녹음해 보세요")
                    .foregroundStyle(DesignSystem.textSecondary)
            }
            ForEach(model.visibleSongs) { row in
                SongRowView(row: row, player: player, rerecord: { player.stop(); replacing = row.id; recording = true })
                    .swipeActions { Button("지우기", role: .destructive) { model.removeSong(row.id) } }
            }
            Button { player.stop(); replacing = nil; recording = true } label: { Label("녹음하기", systemImage: "mic.fill") }
                .disabled(model.songSlotsUsed >= 3)
            if model.songSlotsUsed >= 3 {
                Text("노래는 3곡까지예요. 한 곡을 지운 뒤 보내 주세요.").font(.footnote).foregroundStyle(DesignSystem.textSecondary)
            }
        } header: {
            SectionHeader("우리 노래")   // 7.1 car.section.songs / car.section.reports(U39)
        } footer: {
            SectionHeader("차에서는 다음 주행부터 써요.")
        }
        .onChange(of: model.visibleSongs.map(\.id)) { _, ids in   // 듣던 곡이 지워지면(직접·다른 폰) 재생과 오디오 세션을 푼다
            if let p = player.playingId, !ids.contains(p) { player.stop() }
        }
    }
}

struct SongRowView: View {
    @Environment(AppModel.self) private var model
    let row: SongRow
    let player: SongPlayer
    /// 거절·실패한 곡의 '다시 녹음'(9장 '노래 거절').
    var rerecord: (() -> Void)? = nil

    private var statusText: String {
        switch row.state {
        case .uploading: return model.offline ? "연결되면 보내요" : "올리는 중"
        case .waiting: return "차가 받기를 기다려요"
        case .accepted: return "다음 주행부터 써요"
        case .rejected(let r): return rejectText(r)
        case .failed(let t): return t
        case .removed: return ""
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(row.name).font(.headline)
                if row.candidate { Text("실험에 쓰는 곡").font(.caption).padding(.horizontal, 8).padding(.vertical, 2)
                    .background(DesignSystem.accent.opacity(0.15), in: Capsule()) }
                Spacer()
                if let d = row.durationS { Text(Self.duration(d)).font(.footnote).foregroundStyle(DesignSystem.textSecondary) }
            }
            Text(statusText).font(.footnote).foregroundStyle(isProblem ? DesignSystem.danger : DesignSystem.textSecondary)
            HStack(spacing: 16) {
                if row.hasFile {
                    Button { player.toggle(row.id) } label: {
                        Label(player.playingId == row.id ? "멈추기" : "다시 듣기",
                              systemImage: player.playingId == row.id ? "stop.fill" : "play.fill")
                            .frame(minHeight: 44).contentShape(Rectangle())   // 터치 영역 44pt(8장)
                    }
                }
                if isProblem, let rerecord {
                    Button { rerecord() } label: { Text("다시 녹음").frame(minHeight: 44).contentShape(Rectangle()) }
                }
                if row.state == .accepted, !row.candidate {
                    Button { model.selectSong(row.id) } label: { Text("실험에 쓰기").frame(minHeight: 44).contentShape(Rectangle()) }
                }
            }
            .font(.footnote).buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
    }

    private var isProblem: Bool {
        switch row.state { case .rejected, .failed: return true; default: return false }
    }

    static func duration(_ s: Double) -> String { String(format: "%d:%02d", Int(s) / 60, Int(s) % 60) }
}

/// 거절 이유 문구(3.3 표).
func rejectText(_ r: SongRejectReason) -> String {
    switch r {
    case .peakLimit: return "조금 더 크게 다시 녹음해 주세요."
    case .silent: return "소리가 거의 들리지 않아요. 다시 녹음해 주세요."
    case .badFormat: return "파일을 읽지 못했어요. 다시 녹음해 주세요."
    case .tooLong: return "3분이 넘어요. 3분 안으로 다시 녹음해 주세요."
    case .tooLarge: return "파일이 너무 커요. 다시 녹음해 주세요."
    case .limitReached: return "노래는 3곡까지예요. 한 곡을 지운 뒤 보내 주세요."
    case .downloadFailed: return "차가 파일을 받지 못했어요. 다시 보내 주세요."
    }
}

/// 아래에서 올라오는 녹음 시트: 파형, 큰 녹음·멈춤 버튼, 경과 시간과 최대 시간. 멈춘 뒤 다시 듣기·다시 녹음·보내기.
struct RecordSheet: View {
    @Environment(AppModel.self) private var model
    var replacing: String? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var rec = RecorderModel()
    @State private var name = ""
    @State private var sendError: String?

    var body: some View {
        // 가장 큰 글자에서는 멈춘 뒤의 이름·듣기·보내기·오류가 반 높이 시트를 넘는다. 굴리고 끝까지 펼칠 수 있게 한다(8장 동적 글자 크기).
        ScrollView { content }
            .screenBackground()   // 어둠에서 시스템 순검정 대신 bg(8장)
            .scrollBounceBehavior(.basedOnSize)
            .presentationDetents([.medium, .large])
            .task { await rec.askPermissionIfNeeded() }   // 시트를 처음 열 때 묻는다(7.3). 녹음 버튼은 정해진 권한으로 시작한다
            .onChange(of: scenePhase) { _, p in
                if p == .active { rec.refreshPermission() }   // 설정에서 돌아오면 다시 확인(7.3)
                else if p == .background {
                    if rec.phase == .recording { rec.stop() }   // 녹음은 앱 화면에서만(명세서 12장). 완성된 녹음은 남긴다
                    if rec.isPlaying { rec.togglePlay() }       // 미리듣기도 멈추고 오디오 세션을 푼다(안드로이드 ON_STOP)
                    rec.cancelPendingStart()                    // 권한 창을 기다리던 시작이 뒤에서 녹음을 열지 않게
                }
            }
            .onDisappear { rec.discard() }
    }

    private var content: some View {
        VStack(spacing: 20) {
            Text("노래 녹음").font(.headline).padding(.top, 20)
            Waveform(levels: rec.levels).frame(height: 80)
            Text("\(SongRowView.duration(rec.elapsed)) / 3:00").font(.title3.monospacedDigit())

            if rec.permissionDenied {
                VStack(spacing: 8) {
                    Text("설정에서 마이크를 켜 주세요")
                    Button { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) } label: { Text("설정 열기").frame(minHeight: 44).contentShape(Rectangle()) }   // 44pt
                }
            } else {
                switch rec.phase {
                case .idle:
                    Button { Task { await rec.start() } } label: {
                        Image(systemName: "mic.circle.fill").resizable().frame(width: 72, height: 72).foregroundStyle(DesignSystem.danger)
                    }
                    .accessibilityLabel("녹음 시작")
                case .recording:
                    Button { rec.stop() } label: {
                        Image(systemName: "stop.circle.fill").resizable().frame(width: 72, height: 72).foregroundStyle(DesignSystem.danger)
                    }
                    .accessibilityLabel("녹음 멈추기")
                case .recorded:
                    TextField("노래 이름(30자까지)", text: $name).textFieldStyle(.roundedBorder)
                        .onChange(of: name) { _, v in sendError = nil; if v.count > 30 { name = String(v.prefix(30)) } }   // 고치면 옛 오류를 지운다. 30에서 더 받지 않는다(3.3, 안드로이드 take(30)과 같다)
                    HStack(spacing: 24) {
                        Button { rec.togglePlay() } label: { Label(rec.isPlaying ? "멈추기" : "다시 듣기", systemImage: rec.isPlaying ? "stop.fill" : "play.fill").frame(minHeight: 44).contentShape(Rectangle()) }   // 터치 영역 44pt(8장)
                        Button { sendError = nil; rec.discard() } label: { Text("다시 녹음").frame(minHeight: 44).contentShape(Rectangle()) }
                    }
                    Button { send() } label: { Text("보내기").frame(maxWidth: .infinity) }
                        .accentButton().controlSize(.large)
                    if model.offline { Text("연결되면 보내요").font(.footnote).foregroundStyle(DesignSystem.textSecondary) }
                }
            }
            if let e = sendError ?? rec.error { Text(e).font(.footnote).foregroundStyle(DesignSystem.danger) }
        }
        .padding(24)
    }

    private func send() {
        guard let url = rec.fileURL else { return }
        do {
            try model.addSong(name: name, recording: url, durationS: rec.elapsed, replacing: replacing)   // '다시 녹음'이면 그 곡과 바꾼다
            dismiss()
        } catch let e as SongError { sendError = e.message }
        catch { sendError = "노래를 저장하지 못했어요." }
    }
}

struct Waveform: View {
    let levels: [Float]
    var body: some View {
        GeometryReader { g in
            // U13 ①: 폭에 들어가는 만큼만 최근 막대를 그린다(60개 × 7pt가 시트 폭을 넘었다).
            HStack(alignment: .center, spacing: 3) {
                ForEach(Array(levels.suffix(Self.barCount(fitting: g.size.width)).enumerated()), id: \.offset) { _, l in
                    Capsule().fill(DesignSystem.accent).frame(width: 4, height: max(4, CGFloat(l) * 80))
                }
                if levels.isEmpty { Capsule().fill(.quaternary).frame(height: 4) }
            }
            .frame(width: g.size.width, height: g.size.height)
        }
    }

    /// 막대 4pt + 간격 3pt로 `width`에 들어가는 막대 수.
    static func barCount(fitting width: CGFloat) -> Int { max(0, Int((width + 3) / 7)) }
}
