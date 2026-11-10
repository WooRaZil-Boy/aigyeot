import Foundation
import Observation

/// 녹음 시트의 화면 상태(구현 계획 4장, 최대 3분). 장치는 init으로 받은 SongRecorder다(R10).
/// 녹음 단계는 연관값 enum이라 '녹음 파일 없는 녹음 중', '녹음 전 재생 중' 같은 조합을 만들 수 없다.
/// 시트가 녹음기 수명을 갖는다(시트가 닫히면 버린다). 안드로이드는 SongsViewModel 하나에 둔다(관용 차이, arch-phone-ios).
@MainActor
@Observable
final class RecorderViewModel {
    enum State: Equatable {
        case idle
        case recording(url: URL, elapsed: TimeInterval)
        case recorded(url: URL, duration: TimeInterval, playing: Bool)
    }
    enum Phase { case idle, recording, recorded }

    /// 화면에 보이는 최대 길이(3:00).
    static let maxDuration: TimeInterval = 180

    private(set) var state: State = .idle
    private(set) var levels: [Float] = []
    private(set) var permissionDenied = false
    private(set) var error: String?

    @ObservationIgnored private let recorder: SongRecorder
    @ObservationIgnored private var timer: Timer?
    /// 버릴 때마다 올린다. 권한 창을 기다리는 사이 시트가 닫히면(버림) 늦게 온 허용으로 녹음기·타이머를 만들지 않는다.
    @ObservationIgnored private var session = 0
    @ObservationIgnored private var starting = false
    /// 마이크 권한 요청(시험에서 바꾼다).
    @ObservationIgnored var requestPermission: () async -> Bool

    init(recorder: SongRecorder) {
        self.recorder = recorder
        requestPermission = { [recorder] in await recorder.requestPermission() }
    }

    var phase: Phase {
        switch state { case .idle: .idle; case .recording: .recording; case .recorded: .recorded }
    }
    var elapsed: TimeInterval {
        switch state { case .idle: 0; case .recording(_, let t): t; case .recorded(_, let d, _): d }
    }
    var fileURL: URL? {
        switch state { case .idle: nil; case .recording(let u, _), .recorded(let u, _, _): u }
    }
    var isPlaying: Bool { if case .recorded(_, _, true) = state { true } else { false } }

    /// 녹음 시트를 열 때 한 번 묻는다(계약 7.3 마이크 '녹음 시트를 처음 열 때', 안드로이드와 같다). 이미 정했으면 상태만 읽는다.
    func askPermissionIfNeeded() async {
        guard recorder.permissionUndetermined else { refreshPermission(); return }
        permissionDenied = !(await requestPermission())
    }

    /// 설정에서 마이크를 켜고 돌아왔을 때 거부 표시를 맞춘다. 권한 요청 창은 띄우지 않고 상태만 읽는다.
    func refreshPermission() { permissionDenied = recorder.permissionDenied }

    func start() async {
        // 권한 창을 기다리는 사이 또 누르면 무시한다. 둘 다 시작하면 뒤의 녹음기·타이머가 앞의 것을 덮어 앞 타이머를 멈출 수 없다.
        guard !starting, phase == .idle else { return }
        starting = true
        defer { starting = false }
        error = nil
        let mine = session
        let granted = await requestPermission()
        guard mine == session else { return }   // 기다리는 사이 버렸다(시트를 닫음·다시 녹음)
        guard granted else {
            permissionDenied = true
            return
        }
        permissionDenied = false
        do {
            let url = try recorder.start()
            levels = []
            state = .recording(url: url, elapsed: 0)
            timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            }
        } catch {
            self.error = "녹음을 시작하지 못했어요."
        }
    }

    private func tick() {
        guard case .recording(let url, let elapsed) = state, recorder.isActive else { return }
        if let s = recorder.sample() {
            state = .recording(url: url, elapsed: s.elapsed)
            levels.append(s.level)
            if levels.count > 60 { levels.removeFirst() }
        } else {
            finish(lastKnown: elapsed)   // 시간이 되면 자동으로 멈춘다. 멈춘 recorder의 currentTime은 믿지 않는다
        }
    }

    func stop() {
        let t = recorder.stop(lastKnown: elapsed)
        finish(lastKnown: t)
    }

    private func finish(lastKnown: TimeInterval) {
        timer?.invalidate(); timer = nil
        guard case .recording(let url, _) = state else { return }
        state = .recorded(url: url, duration: recorder.durationS(url, lastKnown: lastKnown), playing: false)
    }

    func togglePlay() {
        guard case .recorded(let url, let d, let playing) = state else { return }
        if playing { recorder.stopPlaying(); state = .recorded(url: url, duration: d, playing: false); return }
        state = .recorded(url: url, duration: d, playing: recorder.play(url) { [weak self] in self?.playbackEnded() })   // 재생을 못 시작했어도 세션을 풀어 준다
    }

    private func playbackEnded() {
        if case .recorded(let url, let d, true) = state { state = .recorded(url: url, duration: d, playing: false) }
    }

    /// 권한 창을 기다리는 시작을 버린다(앱이 뒤로 감). 녹음 중·녹음 뒤 상태는 그대로 둔다.
    func cancelPendingStart() { if phase == .idle { session += 1 } }

    /// 다시 녹음 또는 닫기.
    func discard() {
        session += 1
        timer?.invalidate(); timer = nil
        recorder.discard(fileURL)
        levels = []
        state = .idle
    }
}

/// 곡 목록의 '다시 듣기'(앱 전용 폴더의 내 녹음). 지금 듣는 곡 id가 화면 상태다.
@MainActor
@Observable
final class SongPlayer {
    private(set) var playingId: String?
    @ObservationIgnored private let recorder: SongRecorder
    @ObservationIgnored private let files: SongFileStore

    init(files: SongFileStore, recorder: SongRecorder) {
        self.files = files
        self.recorder = recorder
    }

    func toggle(_ songId: String) {
        if playingId == songId { stopPlayback(); return }
        playingId = recorder.play(files.url(for: songId)) { [weak self] in self?.playingId = nil } ? songId : nil
    }

    private func stopPlayback() {
        recorder.stopPlaying()
        playingId = nil
    }

    /// 녹음 시트를 열기 전에 목록 재생을 멈춘다. 재생 중이 아니면 아무것도 하지 않는다.
    func stop() {
        if playingId != nil { stopPlayback() }
    }
}
