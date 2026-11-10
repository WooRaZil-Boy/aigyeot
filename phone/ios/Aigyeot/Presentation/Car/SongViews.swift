import SwiftUI

private typealias DS = DesignSystem

/// 차(5장, 시안 S.car): 차 카드(3D) → 우리 노래(owner) → 리포트. 연결 안 됨이면 '차와 연결하기'만.
struct CarView: View {
    var body: some View {
        // 우리 노래(SongsViewModel)와 다시 듣기(SongPlayer)는 차 탭이 있는 동안 산다
        Scoped({ $0.songs() }) { _ in
            Scoped({ $0.songPlayer() }) { player in CarContent(player: player) }
        }
    }
}

private struct CarContent: View {
    @Environment(CarViewModel.self) private var vm
    @Environment(SongsViewModel.self) private var songsVM
    let player: SongPlayer
    @State private var recording = false
    /// '다시 녹음'으로 바꿀 거절·실패 곡. 새 녹음을 보낼 때 이 곡을 지우고 넣는다(3곡 한도에 걸리지 않게).
    @State private var replacing: String?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 0) {
                        StatusBands(gap: 12)
                        if vm.ui.isPaired {
                            CarTile(car: vm.ui.phone.car, offline: vm.ui.offline,
                                    phones: vm.ui.owner ? vm.ui.phone.devices.filter { $0.role == .owner || $0.role == .guardian }.count : nil,
                                    stageHeight: 190)
                        } else { ConnectCarTile() }
                    }
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                    .enterRise(0)
                }
                if vm.ui.owner { songs }
                if vm.ui.isPaired {
                    Section {   // 7.1 car.section.reports(U39)
                        BlockHeader(title: "리포트").listRowInsets(Self.headInsets).enterRise(3)
                        if vm.ui.reports.isEmpty {
                            Text("주행이 끝나면 여기에 리포트가 와요").font(DS.font(15, 400)).foregroundStyle(DS.textTertiary)
                                .listRowInsets(Self.noteInsets)
                        }
                        ForEach(vm.ui.reports) { r in
                            NavigationLink { ReportDetailView(sessionId: r.body.sessionId) } label: { ReportRow(report: r, chevron: false) }.enterRise(4)
                        }
                    }
                    .listRowSeparator(.hidden)
                    .listRowBackground(DS.surface)
                }
            }
            .listStyle(.insetGrouped)
            .listSectionSpacing(12)
            .environment(\.defaultMinListRowHeight, 44)
            .screenBackground()
            .navigationTitle(CareText.tabCar)
            .refreshable { await vm.retryNow() }
            .sheet(isPresented: $recording) { Scoped({ $0.recorder() }) { rec in RecordSheet(rec: rec, player: player, replacing: replacing) } }
            .onDisappear { player.stop() }
            // 안드로이드 ON_STOP과 같다: 백그라운드로 가면 미리듣기를 멈춘다(오디오 세션이 .playback이라 이어질 수 있음).
            .onChange(of: scenePhase) { _, p in if p == .background { player.stop() } }
        }
    }

    /// 목록 행 기본 여백(위아래 약 11pt)이 블록 머리의 아래 여백과 겹쳐 머리와 내용이 벌어지지 않게, 머리는 위만·안내 글은 아래만 둔다(옆은 목록 기본 16pt와 같게).
    private static let headInsets = EdgeInsets(top: 18, leading: 16, bottom: 0, trailing: 16)
    private static let noteInsets = EdgeInsets(top: 0, leading: 16, bottom: 18, trailing: 16)

    /// 우리 노래(owner만): 1~3곡 녹음·다시 듣기·지우기, 실험에 쓸 한 곡 고르기, 차로 보낸 상태.
    private var songs: some View {
        Section {
            BlockHeader(title: "우리 노래", link: songsVM.ui.slotsUsed < SongRules.maxSongs ? "녹음하기" : nil) { player.stop(); replacing = nil; recording = true }   // 7.1 car.section.songs(U39)
                .listRowInsets(Self.headInsets)
                .enterRise(1)
            if songsVM.ui.songs.isEmpty {
                Text("아직 노래가 없어요. 평소 불러 주던 노래를 녹음해 보세요").font(DS.font(15, 400)).foregroundStyle(DS.textTertiary)
                    .listRowInsets(Self.noteInsets)
            }
            ForEach(songsVM.ui.songs) { row in
                SongRowView(row: row, player: player, rerecord: { player.stop(); replacing = row.id; recording = true },
                            remove: { songsVM.remove(row.id) })
                    .enterRise(2)
                    .swipeActions { Button("지우기", role: .destructive) { songsVM.remove(row.id) } }
            }
            if songsVM.ui.slotsUsed >= SongRules.maxSongs {
                Text("노래는 3곡까지예요. 한 곡을 지운 뒤 보내 주세요.").font(DS.font(13, 400)).foregroundStyle(DS.textTertiary).padding(.bottom, 8)
            }
        } footer: {
            Text("차에서는 다음 주행부터 써요.").font(DS.font(12.5, 500)).foregroundStyle(DS.textTertiary)
        }
        .listRowSeparator(.hidden)
        .listRowBackground(DS.surface)
        .onChange(of: songsVM.ui.songs.map(\.id)) { _, ids in   // 듣던 곡이 지워지면(직접·다른 폰) 재생과 오디오 세션을 푼다
            if let p = player.playingId, !ids.contains(p) { player.stop() }
        }
    }
}

/// 노래 한 줄(시안 songs()): 회색 원 + 이름 + "0:48 · 상태", 오른쪽 다시 듣기(실험에 쓰는 곡은 파랑 체크). 거절·실패면 '다시 녹음', 받은 곡은 '실험에 쓰기'.
struct SongRowView: View {
    @Environment(SongsViewModel.self) private var vm
    let row: SongRow
    let player: SongPlayer
    /// 거절·실패한 곡의 '다시 녹음'(9장 '노래 거절').
    var rerecord: (() -> Void)? = nil
    /// 이 곡을 듣기 전에 멈출 다른 소리(녹음 화면의 녹음본 듣기). 소리는 한 번에 하나만 난다.
    var beforePlay: (() -> Void)? = nil
    /// 지우기(차 탭). 녹음 화면 아래 목록은 지우지 않는다(nil).
    var remove: (() -> Void)? = nil

    private var statusText: String {
        switch row.state {
        case .uploading: return vm.ui.offline ? "연결되면 보내요" : "올리는 중"
        case .waiting: return "차가 받기를 기다려요"
        case .accepted: return "다음 주행부터 써요"
        case .rejected(let r): return rejectText(r)
        case .failed(let t): return t
        case .removed: return ""
        }
    }

    var body: some View {
        let playing = player.playingId == row.id
        // 누르면 메뉴(시안·안드로이드 결정 14): 듣기·실험에 쓰기·다시 녹음·지우기. 오른쪽은 실험에 쓰는 곡이면 파랑 체크, 아니면 듣기 표시.
        HStack(spacing: 4) {
            Menu {
                if row.hasFile {
                    Button { if !playing { beforePlay?() }; player.toggle(row.id) } label: { Text(playing ? "멈추기" : "다시 듣기") }
                }
                if row.state == .accepted, !row.candidate { Button("실험에 쓰기") { vm.select(row.id) } }
                if isProblem, let rerecord { Button("다시 녹음") { rerecord() } }   // 거절·실패한 곡은 그 자리에서 다시 녹음해 바꾼다(9장 '노래 거절')
                if let remove { Button("지우기", role: .destructive) { remove() } }
            } label: {
                HStack(spacing: 12) {
                    IconImage(.song, size: 19).foregroundStyle(DS.textSecondary).frame(width: 40, height: 40).background(DS.surfaceRaised, in: Circle())
                    VStack(alignment: .leading, spacing: 0) {
                        Text(row.name).font(DS.font(16, 600)).foregroundStyle(DS.textPrimary)
                        Text(([row.durationS.map(Self.duration)] + [statusText.isEmpty ? nil : statusText]).compactMap { $0 }.joined(separator: " · "))
                            .font(DS.font(14, 400)).foregroundStyle(isProblem ? DS.danger : DS.textTertiary)
                            .multilineTextAlignment(.leading)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if row.candidate {
                        IconImage(.circleCheck, size: 22).foregroundStyle(DS.accentInk).frame(width: 28)
                    }
                }
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(row.candidate ? "실험에 쓰는 노래" : "")
            // 오른쪽 듣기·멈추기는 바로 누르는 단추(안드로이드와 같다)
            if row.hasFile {
                Button { if !playing { beforePlay?() }; player.toggle(row.id) } label: {
                    IconImage(playing ? .square : .play, size: 20).foregroundStyle(DS.textTertiary).frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(playing ? "멈추기" : "다시 듣기")
            }
        }
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

/// 노래 녹음(시안 S.song): 파형, 경과 시간과 최대 시간, 큰 녹음·멈춤 버튼(빨강 원 ↔ 둥근 네모). 멈춘 뒤 이름·다시 듣기·다시 녹음·보내기.
struct RecordSheet: View {
    @Environment(SongsViewModel.self) private var vm
    /// 이 시트가 든 녹음기(시트가 닫히면 버린다).
    let rec: RecorderViewModel
    /// 아래 노래 목록의 다시 듣기(차 탭과 같은 재생기). 녹음하는 동안은 목록을 만지지 않는다.
    let player: SongPlayer
    var replacing: String? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var name = ""
    @State private var sendError: String?

    var body: some View {
        // 가장 큰 글자에서는 멈춘 뒤의 이름·듣기·보내기·오류가 반 높이 시트를 넘는다. 굴리고 끝까지 펼칠 수 있게 한다(8장 동적 글자 크기).
        NavigationStack {
            ScrollView { content }
                .screenBackground()
                .scrollBounceBehavior(.basedOnSize)
                .scrollDismissesKeyboard(.immediately)   // 이름을 넣은 뒤 굴리면 키보드(추천 막대 포함)가 내려가 '보내기'가 가려지지 않는다
                .navigationTitle("우리 노래")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button { dismiss() } label: { if #available(iOS 26, *) { IconImage(.x, size: 20).foregroundStyle(DS.textPrimary) } else { Text(CareText.cancel) } }   // 시안 .gbtn: 먹색 X(기록 시트와 같음)
                            .accessibilityLabel(CareText.cancel)
                    }
                }
        }
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
        .onDisappear { rec.discard(); player.stop() }
    }

    private var content: some View {
        VStack(spacing: 18) {
            Waveform(levels: rec.levels).frame(height: 120)
            (Text(SongRowView.duration(rec.elapsed)).foregroundStyle(DS.textPrimary) + Text(" / 3:00").foregroundStyle(DS.textTertiary))
                .font(DS.font(44, 600)).monospacedDigit()

            if rec.permissionDenied {
                VStack(spacing: 8) {
                    Text("설정에서 마이크를 켜 주세요").typeStyle(.body)
                    Button { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) } label: { Text("설정 열기") }   // 44pt
                        .buttonStyle(.pillTonal)
                }
            } else {
                switch rec.phase {
                case .idle:
                    recordButton(recording: false) { player.stop(); Task { await rec.start() } }.accessibilityLabel("녹음 시작")   // 목록 듣기가 녹음에 섞이지 않게
                    Text("평소 불러 주던 노래를 녹음해 보세요").font(DS.font(12.5, 500)).foregroundStyle(DS.textTertiary).multilineTextAlignment(.center)
                case .recording:
                    recordButton(recording: true) { rec.stop() }.accessibilityLabel("녹음 멈추기")
                case .recorded:
                    TextField("노래 이름(30자까지)", text: $name)
                        .font(DS.font(16, 400))
                        .padding(.horizontal, 14).frame(minHeight: 50)
                        .background(DS.surface, in: RoundedRectangle(cornerRadius: DS.rM, style: .continuous))
                        .submitLabel(.done)   // 키보드의 '완료'로 닫는다
                        .onChange(of: name) { _, v in sendError = nil; let l = SongRules.nameInput(v); if l != v { name = l } }   // 고치면 옛 오류를 지운다. 30에서 더 받지 않는다(3.3)
                    HStack(spacing: 12) {
                        Button { if !rec.isPlaying { player.stop() }; rec.togglePlay() } label: {
                            Label { Text(rec.isPlaying ? "멈추기" : "다시 듣기") } icon: { IconImage(rec.isPlaying ? .square : .play, size: 18) }
                        }
                        .buttonStyle(.pillTonal)
                        Button { sendError = nil; rec.discard() } label: { Text("다시 녹음") }.buttonStyle(.pillTonal)
                    }
                    Button { send() } label: { Text("보내기") }.buttonStyle(.pill)
                    if vm.ui.offline { Text("연결되면 보내요").font(DS.caption).foregroundStyle(DS.textTertiary) }
                }
            }
            if let e = sendError ?? rec.error { Text(e).font(DS.caption).foregroundStyle(DS.danger) }
            if !vm.ui.songs.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(vm.ui.songs) { SongRowView(row: $0, player: player, beforePlay: { if rec.isPlaying { rec.togglePlay() } }) }
                }
                .block(padding: EdgeInsets(top: 8, leading: 20, bottom: 8, trailing: 20))
                .disabled(rec.phase == .recording)
                .padding(.top, 8)
                Text("차에서는 다음 주행부터 써요.").font(DS.font(12.5, 500)).foregroundStyle(DS.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
    }

    /// 큰 녹음 버튼(84): 옅은 빨강 원 안에 빨강 원(대기) ↔ 둥근 네모(녹음 중).
    private func recordButton(recording: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            RoundedRectangle(cornerRadius: recording ? 8 : 30, style: .continuous)
                .fill(DS.danger)
                .frame(width: recording ? 30 : 60, height: recording ? 30 : 60)
                .frame(width: 84, height: 84)
                .background(DS.danger.opacity(0.14), in: Circle())
                .animation(reduceMotion ? nil : DS.Motion.bouncy, value: recording)
        }
        .buttonStyle(.press)
    }

    private func send() {
        guard let url = rec.fileURL else { return }
        do {
            try vm.add(name: name, recording: url, durationS: rec.elapsed, replacing: replacing)   // '다시 녹음'이면 그 곡과 바꾼다
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
                    Capsule().fill(DS.accentInk).frame(width: 4, height: max(4, CGFloat(l) * g.size.height))
                }
                if levels.isEmpty {   // 녹음 전: 시안 .wave 모양을 옅게 멈춰 둔다(녹음하면 실제 소리 크기로 바뀐다)
                    ForEach(0..<min(44, Self.barCount(fitting: g.size.width)), id: \.self) { i in
                        Capsule().fill(DS.accentInk.opacity(0.3)).frame(width: 4, height: (0.2 + abs(sin(Double(i) * 0.7)) * 0.7) * g.size.height)
                    }
                }
            }
            .frame(width: g.size.width, height: g.size.height)
        }
        .accessibilityHidden(true)
    }

    /// 막대 4pt + 간격 3pt로 `width`에 들어가는 막대 수.
    static func barCount(fitting width: CGFloat) -> Int { max(0, Int((width + 3) / 7)) }
}
