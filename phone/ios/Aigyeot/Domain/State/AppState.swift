import Foundation
import Observation

// 앱 수명 메모리 상태의 원본(안드로이드 domain/state/AppState.kt와 같은 이름). 칸마다 뜻 하나, 바꾸는 곳은 그 클래스의 메서드뿐이다.

/// 화면 위쪽 띠·앱 안 배너·되돌리기. 글을 고르고 합치는 규칙은 NoticeRules 한 곳이다.
/// 띠 칸은 둘이다: `notice`(버림·저장 실패·링크 오류 같은 안내)와 `careNotice`(돌봄 기록 안내 '앞 수유를 끝냈어요'·'연결을 끊었어요…').
/// 그리기는 한 띠이고(일반 안내 다음 돌봄 안내), 닫기는 둘 다 걷는다(지금 iOS 화면 그대로).
@MainActor
@Observable
final class Notices {
    /// 영구 실패·안내 줄. 닫을 때까지 보인다. 줄마다 다른 안내다(NoticeRules.adding).
    private(set) var notice: String?
    /// 돌봄 기록 안내. 같은 칸을 새 안내가 바꾼다.
    private(set) var careNotice: String?
    /// 앱 안 배너로 띄울 인계 알림 id(글은 그릴 때 저장된 본문에서 계산한다).
    private(set) var banner: String?
    /// 되돌리기 띠('지웠어요'·'기록 n건을 가져왔어요' + '되돌리기'). 띄우고 걷는 것은 RecordCare가 한다(5초 한 곳).
    private(set) var undo: CareUndo?
    /// 기록 시트로 막 저장한 기록 id(새 줄 강조). 기록 탭 줄이 한 번 강조하고 걷는다.
    private(set) var fresh: String?

    func addLine(_ line: String) { notice = NoticeRules.adding(line, to: notice) }
    func saveFailed() { addLine(NoticeRules.saveFailed) }
    /// 버린 메시지 안내. 정리를 저장한 뒤에 부른다. 떠 있는 안내는 덮지 않고 같은 글은 한 번.
    func showDrops(_ types: [String]) { types.compactMap(NoticeRules.dropNotice).forEach(addLine) }
    /// 띠의 '닫기'(두 칸 다). 저장된 버림 표시 지우기는 SendOutbox.dismissNotice가 함께 한다.
    func dismiss() { notice = nil; careNotice = nil }
    /// 연결 끊김 정리(끊기·401·4001): 옛 연결의 띠와 배너를 걷는다.
    func clearForWipe() { notice = nil; careNotice = nil; banner = nil }
    /// 새 연결을 마쳤다: 화면 띠·배너·되돌리기를 처음으로.
    func clearForPair() { notice = nil; careNotice = nil; banner = nil; undo = nil }
    func showCare(_ line: String) { careNotice = line }
    func showBanner(_ alertId: String) { banner = alertId }
    /// `alertId`를 주면 그 배너가 아직 떠 있을 때만 걷는다(그사이 새 배너가 떴으면 둔다, 8초 닫힘).
    func clearBanner(_ alertId: String? = nil) { if alertId == nil || banner == alertId { banner = nil } }
    func showUndo(_ u: CareUndo) { undo = u }
    func markFresh(_ id: String) { fresh = id }
    func clearFresh(_ id: String) { if fresh == id { fresh = nil } }
    func clearUndo() { undo = nil }
}

/// 연결 전환(사용자 끊기·강제 끊김)이 함께 보는 상태와 작은 표시의 원본(안드로이드 LinkSwitch). 화면은 이 표시를 보지 않고 `Connection`만 본다.
/// iOS는 사용자 끊기 복구 표시(`leaving_device`)를 두지 않는다: DELETE 응답 뒤 같은 메인 차례에서 정리가 끝난다.
@MainActor
final class LinkSwitch {
    private let marks: LinkMarks
    init(marks: LinkMarks) { self.marks = marks }

    // ---- 사용자 끊기 중(Disconnect가 쓰고 LoseConnection이 본다) ----
    /// 사용자가 요청한 끊기의 연결. 그사이 온 소켓 4001·다른 요청의 401은 강제 해제가 아니라 이 끊기의 결과일 수 있다.
    private(set) var leaving: Credentials?
    /// 끊기 중에 흡수한 401·4001의 연결.
    private var lostWhileLeaving: Credentials?

    func leaveStarted(_ c: Credentials) { leaving = c; lostWhileLeaving = nil }
    /// 끊는 중이면 401·4001을 그 끊기에 맡기고 참(끊기가 끝날 때 GONE인지 본다).
    func absorb(_ c: Credentials) -> Bool {
        guard leaving != nil else { return false }
        lostWhileLeaving = c
        return true
    }
    /// 끊기가 끝났다. 그사이 흡수한 401·4001의 연결을 돌려준다(끊기가 실패했으면 그 연결은 GONE으로 간다).
    func leaveEnded() -> Credentials? {
        defer { leaving = nil; lostWhileLeaving = nil }
        return lostWhileLeaving
    }

    // ---- goneCleanupPending(표시 `gone_device`) ----
    // 강제 끊김(401·4001) 정리·확인이 남은 기기 id. 재시작 복구 기록이며 화면 상태가 아니다(화면 GONE은 `Connection.gone`).
    // 수명 — 쓰는 곳은 아래 두 함수뿐이다:
    // | 사건                                    | 표시                              |
    // | 401·4001 정리 시작(지우기 전)            | 세움 — goneCleanupStarted         |
    // | 새 연결 마침                             | 걷음 — .paired                    |
    // | GONE 화면에서 고름(기록만 계속·다시 연결) | 걷음 — .acknowledged              |
    // | 시작 복구: 다른 연결의 옛 표시           | 걷음 — .stale                     |
    // | 시작 복구: 지금 자격의 표시              | 둠. 정리를 이어 한다(LoseConnection) |
    // | 시작: 자격 없음 + 표시                   | 둠. GONE 화면으로 시작(PhoneStateRepository) |
    var goneCleanupPending: String? { marks.get(.goneCleanupPending) }

    /// 401·4001 정리를 시작한다(지우기 전). 쓴 것이 확인되면 참.
    @discardableResult
    func goneCleanupStarted(_ deviceId: String) -> Bool { marks.put(.goneCleanupPending, deviceId) }

    /// 위 표의 나머지 사건: 표시를 걷는다. 쓰지 못하면 거짓.
    @discardableResult
    func goneCleanup(_ e: GoneCleanupEvent) -> Bool { marks.put(.goneCleanupPending, nil) }
}

/// LinkSwitch.goneCleanup의 사건(표 한 줄에 하나).
enum GoneCleanupEvent: Equatable {
    case paired, acknowledged, stale
}

/// 앱이 앞에 있는가(쓰는 곳은 앱 수명 하나). 뒤에서는 보낼 함·받은 함을 다시 시도하지 않는다.
@MainActor
final class AppVisibility {
    private(set) var active = false

    func set(_ active: Bool) { self.active = active }
}

/// 앱 범위에서 띄운 작업(안드로이드 앱 범위 scope). 분리 Task 없음. 시험은 `settle()`로 모두 끝나기를 기다린다.
@MainActor
final class TaskBag {
    private var pending: [UUID: Task<Void, Never>] = [:]
    /// 작업이 도는 동안 붙들 합성 루트(유스케이스끼리 잇는 클로저가 그것을 약하게 본다).
    weak var owner: AnyObject?

    @discardableResult
    func spawn(_ work: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
        let id = UUID()
        let keep = owner
        let task = Task { await work(); withExtendedLifetime(keep) {}; self.pending[id] = nil }
        pending[id] = task
        return task
    }

    func settle() async {
        while let t = pending.values.first { await t.value }
    }
}
