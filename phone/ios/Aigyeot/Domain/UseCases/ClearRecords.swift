import Foundation

/// '이 휴대폰의 기록 지우기'(7.4, R10·U18, 안드로이드 ClearRecords): 캐시·녹음 파일·기록을 지우고 토큰은 남긴다. 상태 요청 표시를 켠다.
/// 돌봄 기록 지우기를 마치는 일은 RecordCare.finishClear(돌봄 쓰기 입구 쪽)가 한다.
@MainActor
final class ClearRecords {
    private let state: PhoneStateRepository
    private let care: CareRepository
    private let songFiles: SongFileStore
    private let tasks: TaskBag
    private let outbox: SendOutbox
    private let recordCare: RecordCare
    private let connection: KeepConnected
    /// 수명 표의 '기록 지우기'(AppLifecycle.handle(.recordsCleared)). AppLifecycle이 이 클래스를 써서 합성 루트가 잇는다.
    private let cleared: () -> Void

    init(state: PhoneStateRepository, care: CareRepository, songFiles: SongFileStore, tasks: TaskBag, outbox: SendOutbox,
         recordCare: RecordCare, connection: KeepConnected, cleared: @escaping () -> Void) {
        self.state = state
        self.care = care
        self.songFiles = songFiles
        self.tasks = tasks
        self.outbox = outbox
        self.recordCare = recordCare
        self.connection = connection
        self.cleared = cleared
    }

    func callAsFunction() {
        let sending = outbox.sendingId
        let ids = care.care.events.map(\.id)
        let ok = state.commitLocal { st in
            st.reports = []; st.alerts = []; st.songs = []
            st.feeding = FeedingState(); st.feedingLog = []; st.shown = [:]
            OutboxRules.clearForRecordsWipe(sending: sending, in: &st)   // 다 지운 뒤 빈 요약을 새로 넣는다
            st.lastSentCareSummary = nil
            st.needsStateRequest = true
            st.clearingCareIds = ids   // 아래 돌봄 기록 지우기 전에 죽거나 저장에 실패해도 다음 저장·시작에 이어서 지운다
        }
        guard ok else { return }
        cleared()   // 지운 알림을 가리키지 않게, 상태 요청이 지운 것을 알게
        songFiles.purge()   // 파일은 저장이 성공한 뒤에 지운다
        songFiles.deleteLeftovers()   // 앱이 죽어 남은 녹음 원본도(7.4 녹음 파일)
        recordCare.finishClear()
        let epoch = connection.foregroundEpoch
        tasks.spawn { [connection] in await connection.afterDevices(epoch: epoch) }
    }
}
