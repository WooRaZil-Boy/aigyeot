import AVFoundation
import Observation
import os

/// 녹음·재생을 마친 모든 경로(정상 종료, 취소, 실패, 재생 멈춤·끝남)가 쓰는 하나의 해제 함수.
/// 오디오 객체를 먼저 멈춘 뒤 부른다. 중단됐던 다른 앱 소리에 '돌아가도 된다'고 알린다.
enum AudioSessionRelease {
    private static let log = Logger(subsystem: "kr.aigyeot.phone", category: "audio")

    static func deactivate() {
        do { try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
        catch { log.error("오디오 세션 비활성화 실패: \(error.localizedDescription)") }   // 로그만 남긴다
    }
}

/// 녹음(구현 계획 4장): `AVAudioRecorder`, AAC, 44.1kHz 모노 64kbps, 최대 3분.
@MainActor
@Observable
final class RecorderModel: NSObject, AVAudioPlayerDelegate {
    enum Phase { case idle, recording, recorded }

    /// 화면에 보이는 최대 길이(3:00).
    static let maxDuration: TimeInterval = 180
    /// 실제로 멈추는 길이. AAC 프레임 때문에 파일이 조금 길어질 수 있어 1초 여유를 둔다(차는 180초 초과를 거절한다).
    static let recordLimit: TimeInterval = 179

    private(set) var phase: Phase = .idle
    private(set) var elapsed: TimeInterval = 0
    private(set) var levels: [Float] = []
    private(set) var permissionDenied = false
    private(set) var isPlaying = false
    private(set) var error: String?
    private(set) var fileURL: URL?

    @ObservationIgnored private var recorder: AVAudioRecorder?
    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var timer: Timer?
    /// 버릴 때마다 올린다. 권한 창을 기다리는 사이 시트가 닫히면(버림) 늦게 온 허용으로 녹음기·타이머를 만들지 않는다.
    @ObservationIgnored private var session = 0
    /// 마이크 권한 요청(시험에서 바꾼다).
    @ObservationIgnored var requestPermission: () async -> Bool = { await AVAudioApplication.requestRecordPermission() }

    static let settings: [String: Any] = [
        AVFormatIDKey: kAudioFormatMPEG4AAC,
        AVSampleRateKey: 44100,
        AVNumberOfChannelsKey: 1,
        AVEncoderBitRateKey: 64000,
    ]

    /// 녹음 시트를 열 때 한 번 묻는다(계약 7.3 마이크 '녹음 시트를 처음 열 때', 안드로이드와 같다). 이미 정했으면 상태만 읽는다.
    func askPermissionIfNeeded() async {
        guard AVAudioApplication.shared.recordPermission == .undetermined else { refreshPermission(); return }
        permissionDenied = !(await requestPermission())
    }

    /// 설정에서 마이크를 켜고 돌아왔을 때 거부 표시를 맞춘다. 권한 요청 창은 띄우지 않고 상태만 읽는다.
    func refreshPermission() {
        permissionDenied = AVAudioApplication.shared.recordPermission == .denied
    }

    @ObservationIgnored private var starting = false

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
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("recording-\(UUID().uuidString).m4a")
            let r = try AVAudioRecorder(url: url, settings: Self.settings)
            r.isMeteringEnabled = true
            guard r.record(forDuration: Self.recordLimit) else {
                error = "녹음을 시작하지 못했어요."
                AudioSessionRelease.deactivate()
                return
            }
            recorder = r
            fileURL = url
            levels = []
            elapsed = 0
            phase = .recording
            timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            }
        } catch {
            self.error = "녹음을 시작하지 못했어요."
            AudioSessionRelease.deactivate()
        }
    }

    private func tick() {
        guard let r = recorder else { return }
        if r.isRecording {
            r.updateMeters()
            elapsed = r.currentTime
            // -50dB~0dB를 0~1로. 파형 막대용.
            levels.append(max(0, min(1, (r.averagePower(forChannel: 0) + 50) / 50)))
            if levels.count > 60 { levels.removeFirst() }
        } else {
            finish(lastKnown: elapsed)   // 시간이 되면 자동으로 멈춘다. 멈춘 recorder의 currentTime은 믿지 않는다
        }
    }

    func stop() {
        // currentTime은 녹음 중에만 유효하다. 멈추기 전에 읽는다.
        let t = recorder?.isRecording == true ? recorder?.currentTime ?? elapsed : elapsed
        recorder?.stop()
        finish(lastKnown: t)
    }

    private func finish(lastKnown: TimeInterval) {
        timer?.invalidate(); timer = nil
        guard phase == .recording else { return }
        recorder = nil
        // 최종 길이는 완성된 파일에서 읽는다. 못 읽으면 마지막으로 잰 값을 쓴다.
        let fileLength = fileURL.flatMap { try? AVAudioPlayer(contentsOf: $0).duration } ?? 0
        elapsed = fileLength > 0 ? fileLength : lastKnown
        AudioSessionRelease.deactivate()
        phase = .recorded
    }

    func togglePlay() {
        if isPlaying { stopPlayback(); return }
        guard let url = fileURL else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        player = try? AVAudioPlayer(contentsOf: url)
        player?.delegate = self
        isPlaying = player?.play() ?? false
        if !isPlaying { stopPlayback() }   // 재생을 못 시작했어도 세션을 풀어 준다
    }

    private func stopPlayback() {
        player?.stop(); player = nil
        isPlaying = false
        AudioSessionRelease.deactivate()
    }

    /// 늦게 온 종료 콜백은 지금 플레이어의 것일 때만 처리한다(세션 해제도 그때만).
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        let id = ObjectIdentifier(player)
        Task { @MainActor in
            if let current = self.player, ObjectIdentifier(current) == id { self.stopPlayback() }
        }
    }

    /// 임시 폴더의 녹음 원본(`recording-*.m4a`)을 지운다. 앱 시작과 기록 지우기에서 부른다.
    nonisolated static func removeTempRecordings(in dir: URL = FileManager.default.temporaryDirectory) {
        let fm = FileManager.default
        for n in (try? fm.contentsOfDirectory(atPath: dir.path)) ?? [] where n.hasPrefix("recording-") && n.hasSuffix(".m4a") {
            try? fm.removeItem(at: dir.appendingPathComponent(n))
        }
    }

    /// 권한 창을 기다리는 시작을 버린다(앱이 뒤로 감). 녹음 중·녹음 뒤 상태는 그대로 둔다.
    func cancelPendingStart() { if phase == .idle { session += 1 } }

    /// 다시 녹음 또는 닫기.
    func discard() {
        session += 1
        timer?.invalidate(); timer = nil
        let wasActive = recorder != nil || player != nil
        recorder?.stop(); recorder = nil
        player?.stop(); player = nil; isPlaying = false
        if wasActive { AudioSessionRelease.deactivate() }   // 객체를 먼저 멈춘 뒤 세션을 푼다
        if let fileURL { try? FileManager.default.removeItem(at: fileURL) }
        fileURL = nil; elapsed = 0; levels = []; phase = .idle
    }
}

/// 곡 목록의 '다시 듣기'(앱 전용 폴더의 내 녹음).
@MainActor
@Observable
final class SongPlayer: NSObject, AVAudioPlayerDelegate {
    private(set) var playingId: String?
    @ObservationIgnored private var player: AVAudioPlayer?

    func toggle(_ songId: String) {
        if playingId == songId { stopPlayback(); return }
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        player = try? AVAudioPlayer(contentsOf: SongFiles.url(for: songId))
        player?.delegate = self
        playingId = (player?.play() ?? false) ? songId : nil
        if playingId == nil { stopPlayback() }
    }

    private func stopPlayback() {
        player?.stop(); player = nil
        playingId = nil
        AudioSessionRelease.deactivate()
    }

    /// 녹음 시트를 열기 전에 목록 재생을 멈춘다. 재생 중이 아니면 아무것도 하지 않는다.
    func stop() {
        if playingId != nil { stopPlayback() }
    }

    /// 늦게 온 종료 콜백은 지금 플레이어의 것일 때만 처리한다(새 녹음 중에 공용 세션을 끄지 않게).
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        let id = ObjectIdentifier(player)
        Task { @MainActor in
            if let current = self.player, ObjectIdentifier(current) == id { self.stopPlayback() }
        }
    }
}
