import Foundation

/// 대기 중인 것의 수명을 바꾸는 사건(P6, arch-phone-ios 6장).
enum LifecycleEvent: Equatable {
    /// 지금 연결(자격)이 바뀌었다(새 차 연결 = 방 바뀜, 끊기, GONE). PhoneStateRepository가 알린다.
    case connectionChanged
    /// '이 휴대폰의 기록 지우기'.
    case recordsCleared
    /// 앱이 뒤로 갔다.
    case background
}

/// 앱 수명(시작·앞·뒤)과 수명 표 함수 하나(`handle`). 열은 안드로이드와 같은 넷이다(1.5). 행마다 시험 하나(LifecycleTableTests).
///
/// | # | 대기 중인 것(원본) | 연결 끊김(사용자·401·4001) | 방 바뀜(새 연결) | 기록 지우기 | 앱 재시작 |
/// | --- | --- | --- | --- | --- | --- |
/// | L1 | 보낼 함 항목(state.json) | 상태와 함께 사라짐(WipeLink) | 새 상태라 없음(Pair) | 노래 올리기·안 보낸 요약만 뺌 | 파일에서 이어 보냄 |
/// | L2 | 메시지 429 기다림(SendOutbox.memHold, 자격과 함께) | 옛 자격 것이라 걸리지 않음 | 같음 | 남음 | 저장하지 않아 사라짐(D12, 항목의 nextTryAt은 남음) |
/// | L3 | 노래 업로드(보내는 중, UploadSong) | 자격이 달라 결과를 버림 | 같음 | 보낼 함에서 빠졌으면 보내지 않음(되살리지 않음) | 보낼 함에서 다시 |
/// | L4 | 녹음 임시 파일(SongFileStore.deleteLeftovers) | 그대로 | 그대로 | 모두 지움(B11) | 앞 실행 것을 지움 |
/// | L5 | 연결 결과(Pair.run) | GONE이면 연결됨으로 가지 않음 | 결과가 연결 흐름을 닫음(D3: '연결됐어요' 띠·오늘 탭) | 그대로 | 남는 것 없음 |
/// | L6 | 상태 요청 표시(needsStateRequest) | 사라짐 | 새 상태는 꺼짐 | 켬 | 복구했거나 24시간 지났으면 켬 |
/// | L7 | 연결 반복·소켓·연결 표시(KeepConnected) | 자격이 바뀌는 한 곳에서 소켓을 닫고 메모리 칸·표시를 처음으로 | 같은 곳에서 멈춘 뒤 앞에 있으면 새 자격으로 다시 엶 | 지운 횟수만 셈 | 앞에 오면 엶 |
/// | L8 | 띠·배너(Notices) | 띠·배너 비움(사용자 끊기는 뒤에 '연결을 끊었어요') | 비우고 '연결됐어요'(D3) | 배너만 비움 | 저장된 버린 메시지 안내만 다시 띄움 |
/// | L9 | 강제 끊김 정리 표시(LinkSwitch.goneCleanupPending, D1) | 정리 전에 세우고 GONE에서 고르면 걷음 | 걷음 | 그대로 | 지금 자격 것이면 정리를 이어 하고 GONE, 다른 기기 것이면 걷음, 자격이 없으면 GONE으로 시작 |
///
/// '자격이 바뀌면'은 저장소가 연결이 바뀔 때 부르는 한 곳(`connectionChanged`)과 잡은 자격 비교(P5)로 처리한다.
/// 화면 상태(열려던 알림, L10)는 같은 사건을 `onEvent`로 받는다(AppViewModel.forget).
/// 앞으로 옴(`onForeground`): 지우다 만 기록 지우기·30일 정리 → (연결돼 있으면) 만료 정리 → 망 실패 기다림 당김 → 수유·요약 계기 → 갱신·소켓.
/// 뒤로 감(`onBackground`): 소켓을 닫고 다시 시도 타이머를 멈춘다(받은 함 버퍼 비움, 본문을 기다리던 열 알림 버림). 보낼 함·기록은 그대로다.
/// 다른 유스케이스를 묶어 부르는 일(시작·앞으로 옴)이 이 클래스의 일이라 필요한 것을 생성자로 모두 받는다.
@MainActor
final class AppLifecycle {
    private let state: PhoneStateRepository
    private let songFiles: SongFileStore
    private let notices: Notices
    private let visibility: AppVisibility
    private let outbox: SendOutbox
    private let inbox: SyncInbox
    private let connection: KeepConnected
    private let recordCare: RecordCare
    private let lose: LoseConnection
    private let now: () -> Date
    /// 화면 상태가 같은 사건에 자기 칸을 버린다(열려던 알림, AppViewModel.forget). 합성 루트가 잇는다.
    var onEvent: ((LifecycleEvent) -> Void)?

    init(state: PhoneStateRepository, songFiles: SongFileStore, notices: Notices,
         visibility: AppVisibility, outbox: SendOutbox, inbox: SyncInbox, connection: KeepConnected,
         recordCare: RecordCare, lose: LoseConnection, now: @escaping () -> Date) {
        self.state = state
        self.songFiles = songFiles
        self.notices = notices
        self.visibility = visibility
        self.outbox = outbox
        self.inbox = inbox
        self.connection = connection
        self.recordCare = recordCare
        self.lose = lose
        self.now = now
    }

    /// 수명 표 함수 하나: 사건마다 각 원본이 자기 칸을 버린다.
    func handle(_ e: LifecycleEvent) {
        outbox.forget(e)
        inbox.forget(e)
        connection.forget(e)
        if e == .recordsCleared { notices.clearBanner() }   // 지운 알림을 가리키는 배너
        onEvent?(e)
    }

    /// 지금 연결이 바뀌었다(PhoneStateRepository.switchTo가 알리는 한 곳, P6). 옛 연결의 소켓을 닫고 메모리 칸을 버린다.
    func connectionChanged() {
        connection.stopSocket()
        handle(.connectionChanged)
    }

    /// 앱 시작(수명 표 '앱 재시작'): 저장된 것을 맞춘다. 보내기는 앞으로 올 때의 갱신이 한다.
    func start() {
        lose.recoverAtStart()   // D1: 앞 실행이 마치지 못한 강제 끊김 정리를 먼저 마친다(시작 정리가 지울 연결을 보지 않게)
        // 띠를 보기 전에 앱이 끝났으면 버린 메시지 안내를 다시 보인다(U54, 사용자가 닫을 때까지)
        notices.showDrops(state.state.unseenDrops)
        inbox.markRecoveryAtLaunch()
        purgeExpired()
        if !state.restoreFailed {   // 복원에 실패한 실행에서는 녹음 파일을 지우지 않는다
            songFiles.clean(keep: Set(state.state.songs.filter { $0.state != .removed }.map(\.id)))   // 저장 직후 죽어 파일이 남은 경우
        }
        songFiles.deleteLeftovers()   // 앞 실행이 녹음 시트에서 죽어 남긴 원본(시작할 때는 녹음 중일 수 없다)
        recordCare.migrateIfNeeded()
        recordCare.finishClear()
        recordCare.purgeOld()
        recordCare.syncToCar(.appStart)   // 앱 시작(R6·R19). 보내기는 앞으로 올 때의 갱신이 한다
    }

    func onForeground() {
        visibility.set(true)
        recordCare.finishClear()
        recordCare.purgeOld()
        guard state.isPaired else { return }
        purgeExpired()
        outbox.hurryNetworkWaits()   // 앞으로 올 때 망 실패 기다림 없이 보낸다(7.5)
        recordCare.syncToCar(.foreground)   // 아래 갱신이 보낸다. 저장 없이 날이 바뀌어 '오늘' 숫자가 달라진 것도 여기서 잡는다(R19)
        connection.foregrounded()
    }

    func onBackground() {
        connection.stopSocket()
        visibility.set(false)
        handle(.background)
    }

    /// 7일 지난 id·보낼 함, 90일 지난 기록, 지운 지 7일 지난 곡 표시를 지운다(7.4, G34).
    func purgeExpired() {
        var expired: [String] = []
        let sending = outbox.sendingId
        if state.commitLocal({ expired = InboxRules.purgeExpired(now: now(), sending: sending, in: &$0) }) { notices.showDrops(expired) }
    }
}
