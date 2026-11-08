import Foundation

// 로컬 저장(계약 7.4)의 저장소 protocol. 구현은 Data/Storage에 있다: 기기 토큰은 Keychain, 나머지는 앱 전용 폴더의 JSON 파일이다.

protocol CredentialStore {
    func load() -> Credentials?
    /// 저장하지 못하면 던진다. 호출한 쪽은 성공한 뒤에만 연결을 켠다.
    func save(_ c: Credentials) throws
    func clear()
}

protocol StateStore {
    /// 복원하지 못했는가(파일은 있는데 읽지 못함). 그러면 그 실행에서는 녹음 파일 정리를 하지 않는다.
    var restoreFailed: Bool { get }
    /// 원본을 보존하지 못해 이 실행에서 저장을 거부하는가.
    var writesRefused: Bool { get }
    func load() -> PhoneState
    /// 저장하지 못하면 던진다. 부르는 쪽은 성공했을 때만 메모리 상태를 바꾸고 ack한다.
    func save(_ s: PhoneState) throws
}

protocol CareStore {
    /// 깨진 원본을 보존하지 못해 이 실행에서 저장을 거부하는가.
    var writesRefused: Bool { get }
    func load() -> CareFile
    /// 저장하지 못하면 던진다. 부르는 쪽은 성공했을 때만 메모리 값을 바꾼다.
    func save(_ c: CareFile) throws
    /// 지운 기록의 사본(깨진 원본 사본, 공유하려고 쓴 내보내기 CSV)을 지운다. '이 휴대폰의 기록 지우기'에서만 부른다.
    func removeCopies()
    /// 공유할 내보내기 파일을 임시 폴더에 쓴다(지난 내보내기는 지운다). 쓰지 못하면 nil.
    func writeExport(name: String, data: Data) -> URL?
}

/// 녹음 파일(Application Support/songs)과 녹음 임시 파일(두 앱 같은 이름 SongFileStore, phone-care-plan 9.1). 구현은 Data/Storage/FileSongStore.
protocol SongFileStore {
    func url(for songId: String) -> URL
    func exists(_ songId: String) -> Bool
    /// 올릴 파일을 먼저 둔다. 같은 id 파일이 있으면 바꾼다.
    func copyIn(_ recording: URL, as songId: String) throws
    func size(of url: URL) -> Int
    func delete(_ songId: String)
    /// 연결 끊기·401·기록 지우기: 녹음 파일과 복원 실패 사본을 모두 지운다(안드로이드 purge와 같은 이름).
    func purge()
    /// 복원 실패 때 남긴 상태·노래 사본만 지운다(기록 지우기를 마칠 때).
    func removeBrokenCopies()
    /// 목록(`keep`)에 없는 곡의 파일을 지운다(저장 직후 죽어 남은 파일, 앱 시작).
    func clean(keep: Set<String>)
    /// 녹음 시트의 임시 원본(앱이 죽어 남은 것, 앱 시작·기록 지우기).
    func deleteLeftovers()
}

struct StoreError: Error {}

/// FCM 등록 토큰(5.4). 앱의 `PushToken`이 `messaging(_:didReceiveRegistrationToken:)`에서 채우고, 연결 요청·앱 열기·토큰 바뀜 때 `PATCH /v1/devices/me`로 올린다.
/// `GoogleService-Info.plist`가 없으면(공개 저장소·시험) 늘 nil이다.
protocol PushTokens {
    var token: String? { get set }
}

/// 인터넷 경로(두 앱 같은 이름). 구현은 NWPathMonitor(Data/System/PathNetworkWatcher). 받기·연결 반복이 init으로 받는다.
@MainActor
protocol NetworkWatcher: AnyObject {
    /// 경로가 바뀔 때마다 `onChange(닿을 수 있음)`. 이미 보고 있으면 아무것도 하지 않는다.
    func watch(_ onChange: @escaping @MainActor (Bool) -> Void)
    func unwatch()
}

/// 노래 녹음·재생 장치(R10, 두 앱 같은 이름). 구현은 Data/Audio(AVAudioRecorder·AVAudioPlayer). 녹음 시트·곡 목록 뷰모델이 init으로 받는다.
@MainActor
protocol SongRecorder: AnyObject {
    var permissionUndetermined: Bool { get }
    var permissionDenied: Bool { get }
    func requestPermission() async -> Bool
    /// 녹음기가 열려 있는가(시간이 돼 멈춘 뒤에도 끝내기 전까지).
    var isActive: Bool { get }
    /// 임시 폴더에 새 녹음을 시작한다.
    func start() throws -> URL
    /// 녹음 중이면 (경과, 0~1 소리 크기).
    func sample() -> (elapsed: TimeInterval, level: Float)?
    /// 멈춘다. 마지막으로 잰 경과를 돌려준다.
    func stop(lastKnown: TimeInterval) -> TimeInterval
    /// 녹음을 끝내고 최종 길이(초)를 돌려준다.
    func durationS(_ url: URL, lastKnown: TimeInterval) -> TimeInterval
    /// 재생한다. 끝까지 들으면 `onEnded`. 시작했으면 true.
    func play(_ url: URL, onEnded: @escaping () -> Void) -> Bool
    func stopPlaying()
    /// 녹음·재생을 버리고 오디오 세션을 푼다(시트 닫힘·다시 녹음).
    func cancel()
}

/// 작은 표시 키(R11, D1). 값의 뜻과 쓰는 곳은 LinkSwitch가 정한다(화면은 이 표시를 보지 않는다).
enum Mark: String {
    /// 강제 끊김(401·4001) 정리·확인이 남은 기기 id(안드로이드 prefs `gone_device`와 같은 키).
    case goneCleanupPending = "gone_device"
}

/// 작은 표시 저장(두 앱 같은 이름 LinkMarks). 구현은 UserDefaults(Data/System/UserDefaultsLinkMarks).
@MainActor
protocol LinkMarks: AnyObject {
    func get(_ mark: Mark) -> String?
    /// nil이면 지운다. 쓴 것이 확인되면 참.
    @discardableResult
    func put(_ mark: Mark, _ value: String?) -> Bool
}

/// 잠금 화면 진행 중 표시(C6, 두 앱 같은 이름). 구현은 Live Activity(Data/System/LiveActivities). 돌봄 기록이 바뀌면 다시 그린다.
@MainActor
protocol OngoingNotice {
    func show(_ care: CareFile)
}
