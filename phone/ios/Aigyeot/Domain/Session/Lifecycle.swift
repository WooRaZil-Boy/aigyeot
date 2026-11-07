import Foundation

/// 대기 중인 것의 수명을 바꾸는 사건(P6, arch-phone-ios 6장).
enum LifecycleEvent: Equatable {
    /// 지금 연결이 바뀐다(새 차 연결 = 방 바뀜, 끊기, GONE). 세대가 오른다.
    case connectionChanged
    /// 끊기·GONE으로 연결이 없어졌다(연결 바뀜 뒤에 온다).
    case connectionLeft
    /// '이 휴대폰의 기록 지우기'.
    case recordsCleared
    /// 앱이 뒤로 갔다.
    case background
}

/// 앱 수명(시작·앞·뒤)과 연결 바꾸기, 그리고 수명 표 함수 하나(`handle`).
///
/// | 대기 중인 것(원본) | 연결 바뀜 | 연결 없어짐 | 기록 지우기 | 뒤로 감 |
/// | --- | --- | --- | --- | --- |
/// | 보내는 중·기기 기다림·재시도 타이머(SendOutbox) | 지움·멈춤 | — | 둠 | 재시도 멈춤 |
/// | 받기 진행·버퍼·받은 함 기다림·재시도 타이머·복구 표시(SyncInbox) | 지움·멈춤 | — | 둠 | 버퍼 비움·재시도 멈춤 |
/// | 소켓·기기 목록 주기(SyncInbox) | 연결 없어짐에 멈춤 | 멈춤 | 둠 | 멈춤 |
/// | 상태 요청 중(RequestState) | 지움 | — | 지운 횟수 셈 | 둠 |
/// | 열려던 알림·열린 상세(ShowAlert) | 지움 | 배너도 지움 | 모두 지움 | 열려던 알림만 지움 |
/// | 띠 글(NoticeBoard) | 둠 | 비움 | 둠 | 둠 |
/// | 노래 파일(SongFileStore) | 기록을 비운 저장이 성공하면 모두 지움 | — | 모두 지움 | 둠 |
///
/// 앱 재시작은 메모리 칸이 모두 사라지고 `launch`가 저장된 것을 맞춘다. 보낼 함·지우다 만 기록 지우기는 저장소에 남아 이어서 한다.
/// 다른 유스케이스를 묶어 부르는 일(시작·앞으로 옴)이 이 클래스의 일이라 필요한 것을 생성자로 모두 받는다.
@MainActor
final class Lifecycle {
    private let state: PhoneStateRepository
    private let secrets: CredentialRepository
    private let songFiles: SongFileStore
    private let notices: NoticeBoard
    private let visibility: AppVisibility
    private let alerts: ShowAlert
    private let outbox: SendOutbox
    private let inbox: SyncInbox
    private let requestState: RequestState
    private let recordCare: RecordCare
    private let clearRecords: ClearLocalRecords
    private let feeding: ReconcileFeeding
    private let summary: ReconcileCareSummary
    private let disconnect: Disconnect
    private let now: () -> Date

    init(state: PhoneStateRepository, secrets: CredentialRepository, songFiles: SongFileStore, notices: NoticeBoard,
         visibility: AppVisibility, alerts: ShowAlert, outbox: SendOutbox, inbox: SyncInbox, requestState: RequestState,
         recordCare: RecordCare, clearRecords: ClearLocalRecords, feeding: ReconcileFeeding, summary: ReconcileCareSummary,
         disconnect: Disconnect, now: @escaping () -> Date) {
        self.state = state
        self.secrets = secrets
        self.songFiles = songFiles
        self.notices = notices
        self.visibility = visibility
        self.alerts = alerts
        self.outbox = outbox
        self.inbox = inbox
        self.requestState = requestState
        self.recordCare = recordCare
        self.clearRecords = clearRecords
        self.feeding = feeding
        self.summary = summary
        self.disconnect = disconnect
        self.now = now
    }

    /// 수명 표 함수 하나: 사건마다 각 원본이 자기 칸을 버린다.
    func handle(_ e: LifecycleEvent) {
        alerts.forget(e)
        outbox.forget(e)
        inbox.forget(e)
        requestState.forget(e)
        if e == .connectionLeft { notices.replace(with: nil) }
    }

    /// 앱 시작(수명 표 '앱 재시작'): 저장된 것을 맞춘다. 보내기는 앞으로 올 때의 갱신이 한다.
    func launch() {
        // 띠를 보기 전에 앱이 끝났으면 버린 메시지 안내를 다시 보인다(U54, 사용자가 닫을 때까지)
        notices.addDrops(state.value.unseenDrops)
        inbox.markRecoveryAtLaunch()
        purgeExpired()
        if !state.restoreFailed {   // 복원에 실패한 실행에서는 녹음 파일을 지우지 않는다
            songFiles.removeFiles(notIn: Set(state.value.songs.filter { $0.state != .removed }.map(\.id)))   // 저장 직후 죽어 파일이 남은 경우
        }
        songFiles.removeTempRecordings()   // 앞 실행이 녹음 시트에서 죽어 남긴 원본(시작할 때는 녹음 중일 수 없다)
        recordCare.migrateFeedingsIfNeeded()
        clearRecords.finish()
        recordCare.purge()
        feeding.enqueue()    // 앱 시작(R6). 보내기는 앞으로 올 때의 갱신이 한다
        summary.enqueue()    // 같은 계기(R19)
        visibility.markLaunched()
    }

    func didBecomeActive() {
        visibility.foreground()
        clearRecords.finish()
        recordCare.purge()
        guard state.connection.credentials != nil else { return }
        purgeExpired()
        outbox.hurryNetworkWaits()   // 앞으로 올 때 망 실패 기다림 없이 보낸다(7.5)
        feeding.enqueue()            // 앞으로 올 때(R6). 아래 갱신이 보낸다
        summary.enqueue()            // 같은 계기(R19). 저장 없이 날이 바뀌어 '오늘' 숫자가 달라진 것도 여기서 잡는다
        inbox.foregrounded()
    }

    func didEnterBackground() {
        inbox.stopSocket()
        visibility.background()
        handle(.background)
    }

    /// 7일 지난 id·보낼 함, 90일 지난 기록, 지운 지 7일 지난 곡 표시를 지운다(7.4, G34).
    func purgeExpired() {
        var expired: [String] = []
        let sending = outbox.sendingId
        if state.update({ expired = InboxRules.purgeExpired(now: now(), sending: sending, in: &$0) }) { notices.addDrops(expired) }
    }

    /// 지금 연결을 바꾼다(PhoneStateRepository.switchTo 한 입구). 세대가 올라 이 뒤에 도착하는 이전 연결의 응답·오류는 모두 버려진다.
    /// 옛 연결의 메모리 칸을 같은 순간에 비우고, 지난 기록을 비운 저장이 성공했으면 녹음 파일도 지운다(파일은 저장이 성공한 뒤에).
    func switchConnection(_ sw: PhoneStateRepository.Switch) -> PhoneStateRepository.SwitchResult {
        handle(.connectionChanged)
        let result = state.switchTo(sw, secrets: secrets)
        if result != .stateNotSaved {
            songFiles.removeAll()
            songFiles.removeBrokenCopies()
        }
        return result
    }

    /// 끊기·GONE: 소켓을 닫고 연결을 바꾼 뒤 배너·띠를 비운다. 확인 화면은 남긴다(끊긴 뒤에도 그 코드로 연결할 수 있다, U13 ②).
    func leaveConnection(_ sw: PhoneStateRepository.Switch) {
        inbox.stopSocket()
        _ = switchConnection(sw)
        handle(.connectionLeft)
    }

    /// 어디서든 401 또는 닫기 코드 4001(계약 7.1 GONE). 호출한 쪽이 현재 연결의 응답인지 먼저 확인한다.
    func handleUnauthorized() {
        guard state.connection.credentials != nil else { return }
        if disconnect.absorbUnauthorized() { return }   // 사용자가 끊는 중이면 그 끊기의 결과일 수 있다. 끊기가 끝날 때 본다
        leaveConnection(.gone)
    }

    /// '연결이 끊겼어요' 화면을 넘겼다(U25 a).
    func leaveGone() { state.leaveGone() }
}
