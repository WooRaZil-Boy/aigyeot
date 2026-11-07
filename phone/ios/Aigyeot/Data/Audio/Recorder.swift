import AVFoundation
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

/// 녹음 하드웨어(구현 계획 4장): `AVAudioRecorder`, AAC, 44.1kHz 모노 64kbps. 화면 상태는 Presentation의 RecorderViewModel이 갖는다.
@MainActor
final class AudioRecorder {
    /// 실제로 멈추는 길이. AAC 프레임 때문에 파일이 조금 길어질 수 있어 1초 여유를 둔다(차는 180초 초과를 거절한다).
    static let recordLimit: TimeInterval = 179

    static let settings: [String: Any] = [
        AVFormatIDKey: kAudioFormatMPEG4AAC,
        AVSampleRateKey: 44100,
        AVNumberOfChannelsKey: 1,
        AVEncoderBitRateKey: 64000,
    ]

    private var recorder: AVAudioRecorder?

    var permissionUndetermined: Bool { AVAudioApplication.shared.recordPermission == .undetermined }
    var permissionDenied: Bool { AVAudioApplication.shared.recordPermission == .denied }
    static func requestPermission() async -> Bool { await AVAudioApplication.requestRecordPermission() }

    var isActive: Bool { recorder != nil }
    var isRecording: Bool { recorder?.isRecording == true }

    struct StartFailed: Error {}

    /// 임시 폴더에 새 녹음을 시작한다. 시작하지 못하면 오디오 세션을 풀고 던진다.
    func start() throws -> URL {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("recording-\(UUID().uuidString).m4a")
            let r = try AVAudioRecorder(url: url, settings: Self.settings)
            r.isMeteringEnabled = true
            guard r.record(forDuration: Self.recordLimit) else { throw StartFailed() }
            recorder = r
            return url
        } catch {
            AudioSessionRelease.deactivate()
            throw error
        }
    }

    /// 녹음 중이면 (경과, 0~1 소리 크기). -50dB~0dB를 0~1로(파형 막대용).
    func sample() -> (elapsed: TimeInterval, level: Float)? {
        guard let r = recorder, r.isRecording else { return nil }
        r.updateMeters()
        return (r.currentTime, max(0, min(1, (r.averagePower(forChannel: 0) + 50) / 50)))
    }

    /// 멈춘다. currentTime은 녹음 중에만 유효해 멈추기 전에 읽는다. 마지막으로 잰 경과를 돌려준다.
    func stop(lastKnown: TimeInterval) -> TimeInterval {
        let t = recorder?.isRecording == true ? recorder?.currentTime ?? lastKnown : lastKnown
        recorder?.stop()
        return t
    }

    /// 녹음을 끝낸다(멈췄거나 시간이 됐다). 최종 길이는 완성된 파일에서 읽고, 못 읽으면 마지막으로 잰 값을 쓴다.
    func finish(_ url: URL, lastKnown: TimeInterval) -> TimeInterval {
        recorder = nil
        let fileLength = (try? AVAudioPlayer(contentsOf: url).duration) ?? 0
        AudioSessionRelease.deactivate()
        return fileLength > 0 ? fileLength : lastKnown
    }

    /// 버린다(시트 닫힘·다시 녹음). 오디오 객체를 먼저 멈춘다. 멈춘 것이 있었으면 true.
    func cancel() -> Bool {
        let was = recorder != nil
        recorder?.stop(); recorder = nil
        return was
    }

    /// 임시 폴더의 녹음 원본(`recording-*.m4a`)을 지운다. 앱 시작과 기록 지우기에서 부른다.
    nonisolated static func removeTempRecordings(in dir: URL = FileManager.default.temporaryDirectory) {
        let fm = FileManager.default
        for n in (try? fm.contentsOfDirectory(atPath: dir.path)) ?? [] where n.hasPrefix("recording-") && n.hasSuffix(".m4a") {
            try? fm.removeItem(at: dir.appendingPathComponent(n))
        }
    }
}

/// 재생 하드웨어(`AVAudioPlayer`): 녹음 시트의 다시 듣기와 곡 목록의 다시 듣기가 함께 쓴다.
@MainActor
final class AudioPlayback: NSObject, AVAudioPlayerDelegate {
    private var player: AVAudioPlayer?
    /// 지금 플레이어가 끝까지 재생했다(세션은 이미 풀었다).
    var onEnded: (() -> Void)?

    var isActive: Bool { player != nil }

    /// 재생을 시작하지 못했어도 세션을 풀어 준다. 시작했으면 true.
    func play(_ url: URL) -> Bool {
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        player = try? AVAudioPlayer(contentsOf: url)
        player?.delegate = self
        let ok = player?.play() ?? false
        if !ok { stop() }
        return ok
    }

    func stop() {
        player?.stop(); player = nil
        AudioSessionRelease.deactivate()
    }

    /// 버린다(세션은 부른 쪽이 푼다). 멈춘 것이 있었으면 true.
    func cancel() -> Bool {
        let was = player != nil
        player?.stop(); player = nil
        return was
    }

    /// 늦게 온 종료 콜백은 지금 플레이어의 것일 때만 처리한다(새 녹음 중에 공용 세션을 끄지 않게).
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        let id = ObjectIdentifier(player)
        Task { @MainActor in
            if let current = self.player, ObjectIdentifier(current) == id { self.stop(); self.onEnded?() }
        }
    }
}
