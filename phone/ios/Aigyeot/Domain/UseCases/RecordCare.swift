import Foundation
import Observation

/// 돌봄 기록 적기·고치기·지우기(phone-care-plan 6장, 안드로이드 RecordCare). 기록을 바꾸는 규칙은 CareRules, 저장은 CareRepository 하나를 지난다.
/// `care.json` 쓰기는 모두 `save` 한 입구를 지나고(P7), 저장 뒤 요약(R19)·잠금 화면(R9)은 거기서 일어난다.
/// 수유·요약을 차로 보내는 계기(R6)는 `syncToCar` 하나다.
@MainActor
final class RecordCare {
    private let care: CareRepository
    private let state: PhoneStateRepository
    private let notices: Notices
    private let summary: ReconcileCareSummary
    private let outbox: SendOutbox
    private let songFiles: SongFileStore
    private let now: () -> Date
    /// 저장 뒤 잠금 화면 진행 중 표시를 맞춘다(R9, 구현은 Live Activity). 시험이 바꾼다.
    var ongoing: OngoingNotice?
    /// 앱 시작 계기(`syncToCar(.appStart)`)를 지났는가. 시작 정리 중의 돌봄 저장(옮기기·정리)은 요약을 보낼 함에 넣기만 하고
    /// 보내기는 앞으로 올 때의 갱신이 한다.
    private var started = false

    init(care: CareRepository, state: PhoneStateRepository, notices: Notices, summary: ReconcileCareSummary,
         outbox: SendOutbox, songFiles: SongFileStore, ongoing: OngoingNotice?, now: @escaping () -> Date) {
        self.care = care
        self.state = state
        self.notices = notices
        self.summary = summary
        self.outbox = outbox
        self.songFiles = songFiles
        self.ongoing = ongoing
        self.now = now
    }

    private var nowMs: Int64 { CareTime.ms(now()) }

    /// 돌봄 기록의 쓰기 입구 하나(P7): 바꾸고 `care.json`에 저장한다. 저장하지 못하면 메모리 값도 바꾸지 않는다.
    @discardableResult
    func save(_ f: (inout CareFile) -> Void) -> Bool {
        // 지우다 만 '기록 지우기'를 이 저장에서 먼저 마친다(R10). 먼저 빼고, 바꾸는 쪽이 옛 배열로 계산해 다시 넣어도 다시 뺀다.
        // 그때의 id만 빼므로 그 뒤 적은 기록은 남는다.
        let wiping = state.state.clearingCareIds.map(Set.init)
        let result = care.update { c in
            if let wiping { c.events.removeAll { wiping.contains($0.id) }; c.migratedFeedings = true }
            f(&c)
            if let wiping { c.events.removeAll { wiping.contains($0.id) } }
        }
        switch result {
        case .failed: return false
        case .unchanged: return true
        case .saved: break
        }
        if wiping != nil { endClear() }   // 실패해도 다음에 같은 id를 다시 빼므로 새 기록은 지우지 않는다
        ongoing?.show(care.care)
        // care.json 저장 뒤(R19): 저장·고치기·지우기·되돌리기·잠·기저귀 켜고 끄기 모두 여기를 지난다
        if summary.queue() == .queued, started { outbox.kick() }
        return true
    }

    /// R5·R19를 정해진 계기에서만(R6): 수유 옮기기(R11)를 먼저 하고 보낼 함을 맞춘다. owner이고 연결됐을 때만 넣는다.
    /// 앱 시작·앞으로 올 때는 넣기만 하고 보내기는 뒤따르는 갱신이 한다. 저장·연결 직후에는 넣었으면 바로 보낸다.
    func syncToCar(_ why: FeedTrigger) {
        migrateIfNeeded()   // 끝나기 전에 보내면 보낸 기록(feedingLog)이 다음 이전에서 다른 id의 수유로 또 옮겨진다
        switch why {
        case .appStart, .foreground: summary.feedingAndSummary(kick: false)
        case .careSaved: summary.feedingOnly()   // 요약(R19)은 바로 앞의 save가 맞췄다. 두 번 계산하지 않는다
        case .paired: summary.feedingAndSummary(kick: true)
        }
        if why == .appStart { started = true }
    }

    /// R10: 지우다 만 '기록 지우기'를 마친다(기록 지우기 직후, 시작·앞으로 올 때). 다음 돌봄 저장도 먼저 뺀다(`save`).
    /// 남아 있는 동안에는 옛 기록으로 요약을 보내지 않는다. 마쳤으면 빈 요약을 넣고 보낸다.
    func finishClear() {
        guard state.state.clearingCareIds != nil else { return }
        guard save({ _ in }) else { return }   // 저장이 지울 기록을 빼고 저장한다
        if state.state.clearingCareIds != nil { endClear() }   // 이미 빠져 있어 저장할 것이 없었다
        summary.summaryOnly()
    }

    /// 지운 기록의 사본(돌봄·내보내기·상태·노래 복구 사본)을 먼저 지우고, 마지막에 표시를 지운다. 그사이 죽어도 다음에 다시 한다.
    private func endClear() {
        care.deleteCopies(); songFiles.removeBrokenCopies()
        state.commitLocal { $0.clearingCareIds = nil }
    }

    #if DEBUG
    /// U50 시연 시드. 사용자 저장과 같은 길(`save`)이라 화면이 바로 바뀌고, owner면 저장 뒤 계기로 요약이 간다.
    func replaceForDebug(_ f: (CareFile) -> CareFile) { save { $0 = f($0) } }
    #endif

    // MARK: 사용자 동작

    /// 빠른 버튼·타이머 시작(R1·R2·U14). 같은 종류가 진행 중이면 그것을 빠른 길로 닫고 새로 시작한다. 한 번에 저장한다.
    /// guardian 기록은 이 폰에만 남는다(U23).
    func start(_ kind: CareKind, side: FeedSide? = nil) {
        let t = nowMs
        var e = CareRules.newEvent(kind, at: t)
        e.feedSide = side
        let previous = CareRules.inProgress(kind, in: care.care.events)
        var closedUnknown = false
        guard save({ closedUnknown = CareRules.start(e, closing: previous, now: t, in: &$0) }) else { return }
        if previous != nil, let line = NoticeRules.previousEnded(kind, unknown: closedUnknown) { notices.showCare(line) }   // U14·U21
        syncToCar(.careSaved)
    }

    /// 타이머·잠금 화면의 '끝내기'(U14 빠른 길, 띠 없음).
    func end(_ id: String) {
        let t = nowMs
        if save({ CareRules.endNow(id, now: t, in: &$0) }) { syncToCar(.careSaved) }
    }

    /// 타이머가 최대 길이를 넘었을 때 '끝 시각 모름으로 닫기'(U14).
    func endUnknown(_ id: String) {
        let t = nowMs
        if save({ CareRules.closeEndUnknown(id, now: t, in: &$0) }) { syncToCar(.careSaved) }
    }

    /// 빠른 기록·고치기 저장(R1·R3·R4). 겹치면 묻기 위해 저장하지 않고 `.overlap`을 돌려준다.
    func saveEvent(_ event: CareEvent, allowOverlap: Bool = false) -> CareSaveResult {
        let t = nowMs
        if let r = CareRules.saveProblem(event, allowOverlap: allowOverlap, in: care.care, now: t) { return r }
        guard save({ CareRules.upsert(event, now: t, in: &$0) }) else { return .notSaved }   // 문구는 위쪽 띠("저장하지 못했어요")가 보인다
        syncToCar(.careSaved)
        return .saved
    }

    /// 지우기는 표시만 한다(R8). 차에는 알리지 않는다. 저장했으면 되돌리기를 띄운다(U38, S9).
    func delete(_ id: String) {
        let t = nowMs
        if save({ CareRules.delete(id, now: t, in: &$0) }) { notices.showUndo(id) }
    }

    /// 지운 지 5초 안의 '되돌리기'.
    func undoDelete(_ id: String) {
        let t = nowMs
        guard save({ CareRules.undoDelete(id, now: t, in: &$0) }) else { return }
        notices.clearUndo(id)
        syncToCar(.careSaved)
    }

    func setSleepDiaper(_ on: Bool) { save { $0.sleepDiaperEnabled = on } }

    /// 아이 별명은 폰 안에서만 쓴다(U8). 비우면 없앤다.
    func setNickname(_ name: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        save { $0.childNickname = n.isEmpty ? nil : n }
    }

    /// R8: 지운 지 30일 지난 기록을 실제로 지운다. 앱을 열 때 부른다.
    func purgeOld() {
        let kept = CareRules.purged(care.care.events, now: nowMs)
        if kept.count != care.care.events.count { save { $0.events = kept } }
    }

    /// R11: 처음 한 번 지금 수유 기록을 돌봄 기록으로 옮긴다. 원래 기록은 전송 상태 표시용으로 둔다(U5).
    /// U22: 옛 경로가 이미 보낼 함에 넣은 수유를 다시 보내지 않게, `state.json`의 `lastSentFeedingAt`을 먼저 채우고 `care.json`을 쓴다.
    /// 그사이 죽으면 다음 실행이 다시 옮기고 결과는 같다.
    func migrateIfNeeded() {
        guard !care.care.migratedFeedings else { return }
        let t = nowMs
        if !state.state.feedingLog.isEmpty { guard state.commitLocal({ FeedRules.markMigrated(in: &$0) }) else { return } }
        let log = state.state.feedingLog
        save { c in c = CareRules.migrated(c, from: log, zone: TimeZone.current.identifier, now: t) }
    }
}
