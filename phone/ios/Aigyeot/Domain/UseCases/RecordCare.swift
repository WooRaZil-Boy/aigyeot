import Foundation
import Observation

/// 돌봄 기록 적기·고치기·지우기(phone-care-plan 6장). 기록을 바꾸는 규칙은 CareRules, 저장은 CareRepository 하나를 지난다.
/// 저장 뒤 계기(R6 수유 보내기·R19 요약·잠금 화면)는 여기 `update` 한 곳에서 일어난다.
@MainActor
final class RecordCare {
    private let care: CareRepository
    private let state: PhoneStateRepository
    private let notices: NoticeBoard
    private let visibility: AppVisibility
    private let feeding: ReconcileFeeding
    private let summary: ReconcileCareSummary
    private let outbox: SendOutbox
    private let now: () -> Date
    /// 지우다 만 '기록 지우기'를 이 저장에서 마쳤다(ClearLocalRecords.end). 그쪽이 이 클래스의 `update`를 써서 합성 루트가 잇는다.
    private let endClear: () -> Void
    /// 저장 뒤 잠금 화면 표시를 맞춘다(앱의 CareLiveActivities). 시험이 바꾼다.
    var onCareChange: ((CareFile) -> Void)?

    init(care: CareRepository, state: PhoneStateRepository, notices: NoticeBoard, visibility: AppVisibility,
         feeding: ReconcileFeeding, summary: ReconcileCareSummary, outbox: SendOutbox, now: @escaping () -> Date,
         endClear: @escaping () -> Void) {
        self.care = care
        self.state = state
        self.notices = notices
        self.visibility = visibility
        self.feeding = feeding
        self.summary = summary
        self.outbox = outbox
        self.now = now
        self.endClear = endClear
    }

    private var nowMs: Int64 { CareTime.ms(now()) }

    /// 돌봄 기록을 바꾸고 `care.json`에 저장한다. 저장하지 못하면 메모리 값도 바꾸지 않는다.
    @discardableResult
    func update(_ f: (inout CareFile) -> Void) -> Bool {
        // 지우다 만 '기록 지우기'를 이 저장에서 먼저 마친다. 그때의 id만 빼므로 그 뒤 적은 기록은 남는다(R10).
        let wiping = state.value.clearingCareIds.map(Set.init)
        switch care.update(wiping: wiping, f) {
        case .failed: return false
        case .unchanged: return true
        case .saved: break
        }
        if wiping != nil { endClear() }   // 실패해도 다음에 같은 id를 다시 빼므로 새 기록은 지우지 않는다
        onCareChange?(care.value)
        // care.json 저장 뒤(R19): 저장·고치기·지우기·되돌리기·잠·기저귀 켜고 끄기 모두 여기를 지난다
        if summary.enqueue(), visibility.launched { outbox.kick() }
        return true
    }

    #if DEBUG
    /// U50 시연 시드. 사용자 저장과 같은 길(`update`)이라 화면이 바로 바뀌고, owner면 저장 뒤 계기로 요약이 간다.
    func replaceForDebug(_ f: (CareFile) -> CareFile) { update { $0 = f($0) } }
    #endif

    /// 수유·잠 시작. 같은 종류가 진행 중이면 그것을 빠른 길로 닫고 새로 시작한다(R1·R2·U14). 한 번에 저장한다.
    @discardableResult
    private func start(_ kind: CareKind, side: FeedSide? = nil) -> Bool {
        let t = nowMs
        var e = CareRules.newEvent(kind, at: t)
        e.feedSide = side
        let previous = CareRules.inProgress(kind, in: care.value.events)
        var closedUnknown = false
        guard update({ closedUnknown = CareRules.start(e, closing: previous, now: t, in: &$0) }) else { return false }
        if previous != nil, let line = NoticeRules.previousEnded(kind, unknown: closedUnknown) { notices.setCare(line) }   // U14·U21
        feeding.run()
        return true
    }

    /// '수유 시작'. 기록을 만든 뒤 차로 보내기는 ReconcileFeeding이 한다(U4). guardian 기록은 이 폰에만 남는다(U23).
    func startFeeding(side: FeedSide? = nil) { start(.feed, side: side) }

    func startSleep() { start(.sleep) }

    /// 타이머·잠금 화면의 '끝내기'(U14 빠른 길, 띠 없음).
    func endNow(_ id: String) {
        let t = nowMs
        if update({ CareRules.endNow(id, now: t, in: &$0) }) { feeding.run() }
    }

    /// 타이머가 최대 길이를 넘었을 때 '끝 시각 모름으로 닫기'(U14).
    func closeEndUnknown(_ id: String) {
        let t = nowMs
        if update({ CareRules.closeEndUnknown(id, now: t, in: &$0) }) { feeding.run() }
    }

    /// 빠른 기록·고치기 저장(R1·R3·R4). 겹치면 묻기 위해 저장하지 않고 `.overlap`을 돌려준다.
    func saveEvent(_ event: CareEvent, allowOverlap: Bool = false) -> CareSaveResult {
        let t = nowMs
        if let r = CareRules.saveProblem(event, allowOverlap: allowOverlap, in: care.value, now: t) { return r }
        guard update({ CareRules.upsert(event, now: t, in: &$0) }) else { return .notSaved }   // 문구는 위쪽 띠("저장하지 못했어요")가 보인다
        feeding.run()
        return .saved
    }

    /// 지우기는 표시만 한다(R8). 차에는 알리지 않는다. 저장했으면 true(화면이 되돌리기를 보인다, U38).
    @discardableResult
    func deleteEvent(_ id: String) -> Bool {
        let t = nowMs
        return update { CareRules.delete(id, now: t, in: &$0) }
    }

    /// 지운 지 5초 안의 '되돌리기'. 저장했으면 true.
    @discardableResult
    func undoDelete(_ id: String) -> Bool {
        let t = nowMs
        guard update({ CareRules.undoDelete(id, now: t, in: &$0) }) else { return false }
        feeding.run()
        return true
    }

    func setSleepDiaperEnabled(_ on: Bool) { update { $0.sleepDiaperEnabled = on } }

    /// 아이 별명은 폰 안에서만 쓴다(U8). 비우면 없앤다.
    func setChildNickname(_ name: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        update { $0.childNickname = n.isEmpty ? nil : n }
    }

    /// R8: 지운 지 30일 지난 기록을 실제로 지운다. 앱을 열 때 부른다.
    func purge() {
        let kept = CareRules.purged(care.value.events, now: nowMs)
        if kept.count != care.value.events.count { update { $0.events = kept } }
    }

    /// R11: 처음 한 번 지금 수유 기록을 돌봄 기록으로 옮긴다. 원래 기록은 전송 상태 표시용으로 둔다(U5).
    /// U22: 옛 경로가 이미 보낼 함에 넣은 수유를 다시 보내지 않게, `state.json`의 `lastSentFeedingAt`을 먼저 채우고 `care.json`을 쓴다.
    /// 그사이 죽으면 다음 실행이 다시 옮기고 결과는 같다.
    func migrateFeedingsIfNeeded() {
        guard !care.value.migratedFeedings else { return }
        let t = nowMs
        if !state.value.feedingLog.isEmpty { guard state.update({ FeedRules.markMigrated(in: &$0) }) else { return } }
        let log = state.value.feedingLog
        update { c in c = CareRules.migrated(c, from: log, zone: TimeZone.current.identifier, now: t) }
    }
}

/// 수유를 차로 보내기(U4·R5·R6). 차로 보내는 길은 이 하나다.
@MainActor
final class ReconcileFeeding {
    private let state: PhoneStateRepository
    private let care: CareRepository
    private let outbox: SendOutbox
    private let now: () -> Date
    /// R11 이전(RecordCare.migrateFeedingsIfNeeded). 그쪽이 이 클래스의 `run`을 써서 합성 루트가 잇는다.
    private let migrate: () -> Void

    init(state: PhoneStateRepository, care: CareRepository, outbox: SendOutbox, now: @escaping () -> Date,
         migrate: @escaping () -> Void) {
        self.state = state
        self.care = care
        self.outbox = outbox
        self.now = now
        self.migrate = migrate
    }

    /// 보낼 함에 넣었으면 바로 보낸다.
    func run() { if enqueue() { outbox.kick() } }

    /// R5: owner이고 연결됐으며, 지우지 않은 수유 중 가장 늦은 시작이 `lastSentFeedingAt`보다 늦고 24시간 안이면
    /// 지금의 수유 보내기 경로(보낼 함 + 전송 상태 기록)에 넣고 같은 저장에서 `lastSentFeedingAt`을 올린다.
    /// 저장에 실패하면 아무것도 바뀌지 않는다. 다음 계기가 다시 본다.
    @discardableResult
    func enqueue() -> Bool {
        // R11 이전을 못 끝냈으면 먼저 한다. 끝나기 전에 보내면 보낸 기록(feedingLog)이 다음 이전에서 다른 id의 수유로 또 옮겨진다.
        migrate()
        let t = now()
        guard state.role == .owner, let latest = FeedRules.latestToSend(care.value, state.value, now: t) else { return false }
        return state.update { FeedRules.enqueue(latest, now: t, in: &$0) }
    }
}

/// R18(U45): 차로 보낼 돌봄 요약을 계산한다. 계산은 CareRules.careSummary 한 곳이다(P1).
struct BuildCareSummary {
    func callAsFunction(_ care: CareFile, asOf: Int64, zone: TimeZone) -> CareSummaryBody { CareRules.careSummary(care, asOf: asOf, zone: zone) }
}

/// R19(U45·U57·U61): 돌봄 요약 보내기는 이 길 하나다. 넣을지 판단은 SummaryRules.
@MainActor
final class ReconcileCareSummary {
    private let state: PhoneStateRepository
    private let care: CareRepository
    /// 보내는 중인 항목(`sendingId`)을 보고, 넣은 뒤 보내기를 시작한다.
    private let outbox: SendOutbox
    private let now: () -> Date
    /// 시험에서 끈다(요약이 보낼 함에 끼지 않게).
    var sendsCareSummary: Bool
    /// '오늘'을 자르는 시간대(시험이 바꾼다).
    var timeZone: () -> TimeZone = { .current }

    init(state: PhoneStateRepository, care: CareRepository, outbox: SendOutbox, now: @escaping () -> Date, sendsCareSummary: Bool) {
        self.state = state
        self.care = care
        self.outbox = outbox
        self.now = now
        self.sendsCareSummary = sendsCareSummary
    }

    enum Queued { case queued, unchanged, failed }

    /// 보낼 함에 넣었으면 바로 보낸다.
    func run() { if enqueue() { outbox.kick() } }

    @discardableResult
    func enqueue() -> Bool { queue() == .queued }

    /// owner이고 연결됐을 때만. 저장 실패면 `.failed`(보낼 함이 보내기 직전 재계산에서 멈추는 데 쓴다).
    func queue() -> Queued {
        guard sendsCareSummary, state.role == .owner else { return .unchanged }
        let sending = outbox.sendingId
        let t = now()
        guard case let .enqueue(body, key, asOf) = SummaryRules.decide(care.value, state.value, nowMs: CareTime.ms(t), zone: timeZone(), sending: sending) else { return .unchanged }
        let item = OutboxRules.item(type: MessageType.careSummary, body: body, now: t)
        return state.update { SummaryRules.enqueue(item, key: key, asOf: asOf, sending: sending, in: &$0) } ? .queued : .failed
    }
}

/// '이 휴대폰의 기록 지우기'(7.4, R10·U18): 캐시·녹음 파일·기록을 지우고 토큰은 남긴다. 상태 요청 표시를 켠다.
@MainActor
final class ClearLocalRecords {
    private let state: PhoneStateRepository
    private let care: CareRepository
    private let songFiles: SongFileStore
    private let tasks: TaskBag
    private let visibility: AppVisibility
    private let outbox: SendOutbox
    private let recordCare: RecordCare
    private let summary: ReconcileCareSummary
    private let requestState: RequestState
    /// 수명 표의 '기록 지우기'(Lifecycle.handle(.recordsCleared)). Lifecycle이 이 클래스를 써서 합성 루트가 잇는다.
    private let cleared: () -> Void

    init(state: PhoneStateRepository, care: CareRepository, songFiles: SongFileStore, tasks: TaskBag, visibility: AppVisibility,
         outbox: SendOutbox, recordCare: RecordCare, summary: ReconcileCareSummary, requestState: RequestState,
         cleared: @escaping () -> Void) {
        self.state = state
        self.care = care
        self.songFiles = songFiles
        self.tasks = tasks
        self.visibility = visibility
        self.outbox = outbox
        self.recordCare = recordCare
        self.summary = summary
        self.requestState = requestState
        self.cleared = cleared
    }

    func run() {
        let sending = outbox.sendingId
        let ids = care.value.events.map(\.id)
        let ok = state.update { st in
            st.reports = []; st.alerts = []; st.songs = []; st.selectedSongId = nil
            st.feeding = FeedingState(); st.feedingLog = []; st.shown = [:]
            OutboxRules.clearForRecordsWipe(sending: sending, in: &st)   // 다 지운 뒤 빈 요약을 새로 넣는다
            st.lastSentCareSummary = nil
            st.needsStateRequest = true
            st.clearingCareIds = ids   // 아래 돌봄 기록 지우기 전에 죽거나 저장에 실패해도 다음 저장·시작에 이어서 지운다
        }
        guard ok else { return }
        cleared()   // 지운 알림을 가리키지 않게, 상태 요청이 지운 것을 알게
        songFiles.removeAll()   // 파일은 저장이 성공한 뒤에 지운다
        songFiles.removeBrokenCopies()
        songFiles.removeTempRecordings()   // 앱이 죽어 남은 녹음 원본도(7.4 녹음 파일)
        finish()
        let epoch = visibility.foregroundEpoch
        tasks.spawn { [requestState] in await requestState.afterDevices(epoch: epoch) }
    }

    /// R10: 돌봄 기록도 지운다. 설정은 남기고, 지운 수유를 다시 옮겨 오지 않게 옮기기 표시를 켠다.
    /// 지울 id가 남아 있으면(지우는 사이 죽었거나 care.json 저장에 실패) 시작·앞으로 올 때 다시 한다. 다음 돌봄 저장도 먼저 뺀다.
    /// 남아 있는 동안에는 옛 기록으로 요약을 보내지 않는다.
    func finish() {
        guard state.value.clearingCareIds != nil else { return }
        guard recordCare.update({ _ in }) else { return }   // 저장이 지울 기록을 빼고 저장한다
        if state.value.clearingCareIds != nil { end() }   // 이미 빠져 있어 저장할 것이 없었다
        summary.run()
    }

    /// 지운 기록의 사본(돌봄·내보내기·상태·노래 복구 사본)을 먼저 지우고, 마지막에 표시를 지운다. 그사이 죽어도 다음에 다시 한다.
    func end() {
        care.removeCopies(); songFiles.removeBrokenCopies()
        state.update { $0.clearingCareIds = nil }
    }
}
