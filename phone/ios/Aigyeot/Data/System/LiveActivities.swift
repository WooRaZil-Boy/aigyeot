import ActivityKit
import Foundation

/// 진행 중 기록과 떠 있는 Live Activity를 맞추는 판단(C6). ActivityKit 없이 시험한다.
enum CareActivityPlan {
    struct Wanted: Equatable {
        var eventId: String
        var sleep: Bool
        var state: CareActivityAttributes.ContentState
    }

    struct Diff: Equatable {
        var start: [Wanted] = []
        var update: [Wanted] = []
        var end: [String] = []
    }

    /// 진행 중 수유·잠마다 하나. 쪽은 수유만(7.1 `notify.body`).
    static func wanted(_ care: CareFile) -> [Wanted] {
        [CareKind.feed, .sleep].compactMap { CareRules.inProgress($0, in: care.events) }.map { e in
            Wanted(eventId: e.id, sleep: e.kind == .sleep,
                   state: .init(startAt: CareTime.date(e.startAt), side: e.kind == .feed ? e.feedSide?.label : nil))
        }
    }

    /// `existing`: 떠 있는 활동의 기록 id → 지금 상태.
    static func diff(_ wanted: [Wanted], existing: [String: CareActivityAttributes.ContentState]) -> Diff {
        var d = Diff()
        for w in wanted {
            if let s = existing[w.eventId] { if s != w.state { d.update.append(w) } } else { d.start.append(w) }
        }
        d.end = existing.keys.filter { id in !wanted.contains { $0.eventId == id } }.sorted()
        return d
    }
}

/// 돌봄 기록이 바뀔 때마다 잠금 화면 표시를 맞춘다. 시작은 앱이 앞에 있을 때만 된다(ActivityKit 규칙).
/// OngoingNotice 구현(R9): 잠금 화면 Live Activity를 지금 돌봄 기록에 맞춘다.
struct LiveActivityNotice: OngoingNotice {
    func show(_ care: CareFile) { CareLiveActivities.sync(care) }
}

@MainActor
enum CareLiveActivities {
    static func sync(_ care: CareFile) {
        let live = Activity<CareActivityAttributes>.activities.filter { $0.activityState == .active || $0.activityState == .stale }
        var have: [String: Activity<CareActivityAttributes>] = [:]
        for a in live { have[a.attributes.eventId] = a }
        let d = CareActivityPlan.diff(CareActivityPlan.wanted(care), existing: have.mapValues(\.content.state))
        for id in d.end { if let a = have[id] { Task { await a.end(nil, dismissalPolicy: .immediate) } } }
        for w in d.update { if let a = have[w.eventId] { Task { await a.update(ActivityContent(state: w.state, staleDate: nil)) } } }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }   // 사용자가 끄면 앱 안에서만 보인다
        for w in d.start {
            _ = try? Activity.request(attributes: CareActivityAttributes(eventId: w.eventId, sleep: w.sleep),
                                      content: ActivityContent(state: w.state, staleDate: nil))
        }
    }
}

/// 잠금 화면 '끝내기'(`EndCareIntent`)가 앱 프로세스에서 부른다. 닫기 규칙은 타이머와 같다(U14).
enum CareIntentHandler {
    @MainActor static func end(_ eventId: String) { AppContainer.shared.recordCare.end(eventId) }
}
