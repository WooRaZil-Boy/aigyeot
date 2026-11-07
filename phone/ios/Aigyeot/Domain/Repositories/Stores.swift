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
}

/// 녹음 파일(Application Support/songs)과 녹음 임시 파일(두 앱 같은 이름 SongFileStore, phone-care-plan 9.1). 구현은 Data/Storage/FileSongStore.
protocol SongFileStore {
    func url(for songId: String) -> URL
    func exists(_ songId: String) -> Bool
    /// 올릴 파일을 먼저 둔다. 같은 id 파일이 있으면 바꾼다.
    func copyIn(_ recording: URL, as songId: String) throws
    func size(of url: URL) -> Int
    func remove(_ songId: String)
    func removeAll()
    /// 복원 실패 때 남긴 상태·노래 사본을 지운다. 사용자가 기록 지우기·연결 끊기를 한 때만 부른다.
    func removeBrokenCopies()
    /// 목록에 없는 곡의 파일을 지운다(저장 직후 죽어 남은 파일, 앱 시작).
    func removeFiles(notIn keep: Set<String>)
    /// 녹음 시트의 임시 원본(앱이 죽어 남은 것, 앱 시작·기록 지우기).
    func removeTempRecordings()
}

struct StoreError: Error {}

/// FCM 등록 토큰(5.4). 앱의 `PushToken`이 `messaging(_:didReceiveRegistrationToken:)`에서 채우고, 연결 요청·앱 열기·토큰 바뀜 때 `PATCH /v1/devices/me`로 올린다.
/// `GoogleService-Info.plist`가 없으면(공개 저장소·시험) 늘 nil이다.
protocol PushTokenSource {
    var token: String? { get }
}
