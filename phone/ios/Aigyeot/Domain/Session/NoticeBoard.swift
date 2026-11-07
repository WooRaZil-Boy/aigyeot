import Foundation
import Observation

/// 위쪽 띠 글의 원본(U54). 글을 고르고 합치는 규칙은 NoticeRules, 칸은 이 클래스의 함수로만 바뀐다(P3).
@MainActor
@Observable
final class NoticeBoard {
    var notice: String?
    @ObservationIgnored private let state: PhoneStateRepository

    init(state: PhoneStateRepository) { self.state = state }

    /// 띠 글에 한 줄을 더한다(떠 있는 안내를 덮지 않는다).
    func add(_ line: String) { notice = NoticeRules.adding(line, to: notice) }

    /// 버린 메시지 안내(U54). 정리를 저장한 뒤에 부른다.
    func addDrops(_ types: [String]) { types.compactMap(NoticeRules.dropNotice).forEach(add) }

    /// '앞 기록을 끝냈어요'. 앞의 같은 종류 안내는 바꾸고 다른 안내는 남긴다.
    func setCare(_ line: String) { notice = NoticeRules.replacingCareNotice(line, in: notice) }

    /// 띠를 이 글 하나로 바꾼다(연결됐어요·연결을 끊었어요) 또는 비운다(끊기·GONE).
    func replace(with line: String?) { notice = line }

    /// 띠의 '닫기'. 저장해 둔 버린 메시지 안내도 이때 지운다(U54).
    func dismiss() {
        notice = nil
        if !state.value.unseenDrops.isEmpty { state.update { $0.unseenDrops = [] } }
    }
}

/// 띄운 작업(분리 Task 없음). 시험은 `settle()`로 모두 끝나기를 기다린다.
@MainActor
final class TaskBag {
    private var pending: [UUID: Task<Void, Never>] = [:]
    /// 작업이 도는 동안 붙들 합성 루트(유스케이스끼리 잇는 클로저가 그것을 약하게 본다).
    weak var owner: AnyObject?

    func spawn(_ work: @escaping @MainActor () async -> Void) {
        let id = UUID()
        let keep = owner
        pending[id] = Task { await work(); withExtendedLifetime(keep) {}; self.pending[id] = nil }
    }

    func settle() async {
        while let t = pending.values.first { await t.value }
    }
}

/// 앱이 앞에 있는지·화면 세대·시작 정리가 끝났는지(쓰는 곳은 Lifecycle 하나, P3). 받기·보내기·돌봄 저장이 읽는다.
@MainActor
final class AppVisibility {
    /// 앞에 있는가. 뒤에서는 보낼 함·받은 함을 다시 시도하지 않는다(2장 '뒤로 가면 멈춘다').
    private(set) var active = false
    /// 뒤로 갈 때 올린다. 진행 중 갱신이 뒤에서 다음 요청을 시작하지 않게 한다.
    private(set) var foregroundEpoch = 0
    /// 시작 정리가 끝났는가. 시작 중의 돌봄 저장(옮기기·정리)은 요약을 보낼 함에 넣기만 하고 보내기는 앞으로 올 때의 갱신이 한다.
    private(set) var launched = false

    func foreground() { active = true }
    func background() { active = false; foregroundEpoch += 1 }
    func markLaunched() { launched = true }
}

/// 중계 HTTP의 띠 관찰(9장)과 401(계약 7.1 GONE)을 한 곳에서 처리한다(P1). 여러 유스케이스가 생성자로 받는다.
@MainActor
final class RelayGate {
    private let link: LinkObserver
    private let state: PhoneStateRepository
    private let onUnauthorized: () -> Void

    init(link: LinkObserver, state: PhoneStateRepository, onUnauthorized: @escaping () -> Void) {
        self.link = link
        self.state = state
        self.onUnauthorized = onUnauthorized
    }

    /// 띠 판단에 들어가는 HTTP: 시작할 때 표시를 잡고, 성공이면 끄고 중계 장애면 켠다. 연결이 바뀐 뒤의 결과는 띠를 바꾸지 않는다.
    func observed<T>(_ op: () async throws -> T) async throws -> T {
        let mark = link.mark(), gen = state.generation
        do {
            let v = try await op()
            if gen == state.generation { link.observe(nil, since: mark) }
            return v
        } catch let e as RelayError {
            if gen == state.generation { link.observe(e, since: mark) }
            throw e
        }
    }

    /// 401을 받은 같은 연결의 요청: GONE. 다른 연결의 늦은 401은 버린다.
    func unauthorized(_ e: RelayError, gen: Int) {
        if gen == state.generation, e.isUnauthorized { onUnauthorized() }
    }
}
