import Foundation
import Observation

/// 폰 상태(`state.json`)와 지금 연결(`Connection`)의 원본(phone-care-plan 9.1, 두 앱 같은 이름).
/// 상태 쓰기 입구는 셋뿐이다(P7): `commit`(그 자격일 때만), `commitLocal`(연결과 따로인 표시), `switchTo`(연결 세대 바꿈).
/// 저장이 확인됐을 때만 메모리를 바꾼다. Keychain 비밀값은 여기서 다루지 않는다(CredentialRepository, 쓰는 곳 Pair·WipeLink).
@MainActor
@Observable
final class PhoneStateRepository {
    @ObservationIgnored private let store: StateStore
    private(set) var state: PhoneState
    private(set) var connection: Connection

    /// 지금 연결에서 계산한다(P4).
    var creds: Credentials? { connection.creds }
    /// 시작할 때 잡은 자격이 아직 지금 연결인가(P5). 연결 세대는 자격 값 자체다(같은 값이면 같은 세대).
    func isCurrent(_ c: Credentials?) -> Bool { c != nil && connection.creds == c }
    var role: PhoneRole? { creds?.role }
    var isPaired: Bool { creds != nil }
    /// 저장 실패 공통 처리(실패 범주 U: 띠 글). 합성 루트가 `Notices.saveFailed`에 잇는다.
    @ObservationIgnored var onWriteFailed: () -> Void = {}
    /// 자격이 바뀌었다(연결 마침·끊기·401). 옛 연결의 메모리 칸을 버리는 한 곳(P6, 안드로이드는 Connection 구독). 합성 루트가 잇는다.
    @ObservationIgnored var onConnectionChange: () -> Void = {}

    /// 복원하지 못했는가(파일은 있는데 읽지 못함). 그러면 그 실행에서는 녹음 파일 정리를 하지 않는다.
    var restoreFailed: Bool { store.restoreFailed }
    /// 원본을 보존하지 못해 이 실행에서 저장을 거부하는가.
    var writesRefused: Bool { store.writesRefused }

    /// `goneDevice`: 강제 끊김 정리·확인이 남은 기기(`LinkSwitch.goneCleanupPending`, D1). 자격이 없을 때만 GONE 화면으로 시작한다.
    init(store: StateStore, creds: Credentials?, goneDevice: String? = nil) {
        self.store = store
        var s = store.load()
        // 토큰이 없는데 상태만 남았으면(Keychain만 지워진 경우) 상태도 비운다(메모리만, 다음 저장에 쓰인다).
        if creds == nil, s.carId != nil { s = Self.fresh(introSeen: true, keeping: s) }
        state = s
        connection = creds.map { .paired($0) } ?? goneDevice.map { .gone(deviceId: $0) } ?? .none
    }

    /// `c`가 지금 자격일 때만 바꾸고 저장한다(이전 연결의 응답이면 아무것도 하지 않고 false). `afterSave`는 저장이 된 뒤 같은 차례에 돈다.
    /// 상태 변경과 보낼 함 넣기는 한 번의 `commit`으로 묶어 한 번에 저장한다(계약 8.3의 '한 트랜잭션').
    @discardableResult
    func commit(_ c: Credentials?, afterSave: (() -> Void)? = nil, _ f: (inout PhoneState) -> Void) -> Bool {
        guard isCurrent(c), write(f) else { return false }
        afterSave?()
        return true
    }

    /// 연결과 따로인 일(기록 지우기·수유 옮기기 표시·띠 닫기·첫 안내·만료 정리)만 쓴다. 자격이 없어도 저장한다.
    @discardableResult
    func commitLocal(_ f: (inout PhoneState) -> Void) -> Bool { write(f) }

    /// 저장하지 못하면 메모리 상태도 바꾸지 않고(롤백) false.
    private func write(_ f: (inout PhoneState) -> Void) -> Bool {
        // 원본을 보존하지 못한 실행은 모든 저장을 거부한다. 같음 비교보다 먼저 막아야
        // 빈 상태로의 초기화가 '성공'으로 보여 녹음 파일이 지워지는 일이 없다.
        if store.writesRefused { onWriteFailed(); return false }
        var copy = state
        f(&copy)
        if copy == state { return true }   // 바뀐 게 없으면 저장하지 않는다
        do { try store.save(copy) } catch { onWriteFailed(); return false }
        state = copy
        return true
    }

    /// 연결을 비운 새 상태. 지우다 만 '기록 지우기'(돌봄 기록은 연결과 따로다, R9·R10)는 남긴다.
    static func fresh(introSeen: Bool, keeping old: PhoneState) -> PhoneState {
        var s = PhoneState(introSeen: introSeen)
        s.clearingCareIds = old.clearingCareIds
        return s
    }

    /// 자격과 상태를 함께 바꾼다(연결 세대 바꿈: 연결 마침·끊기·401). `onlyIf`가 있으면 그 자격일 때만.
    /// 연결할 때(`next != nil`)는 `make`가 만든 상태의 저장이 돼야 바꾼다(이전 차의 보낼 함이 새 차로 가지 않게).
    /// 끊을 때(`next == nil`)는 저장이 실패해도 자격은 잊는다(토큰이 이미 중계에서 지워졌다). 메모리 칸·파일 정리는 부른 쪽(Pair·WipeLink)이 한다.
    /// 연결 세대는 자격 값 자체라 따로 세지 않는다: 자격이 바뀌면 옛 자격으로 시작한 결과는 `isCurrent`에서 걸러진다.
    @discardableResult
    func switchTo(_ next: Credentials?, make: (PhoneState) -> PhoneState, onlyIf: Credentials? = nil) -> SwitchResult {
        if let only = onlyIf, !isCurrent(only) { return SwitchResult(switched: false, saved: false) }
        let saved = write { $0 = make($0) }
        if !saved, next != nil { return SwitchResult(switched: false, saved: false) }
        let changed = connection.creds != next
        connection = next.map { .paired($0) } ?? .none
        if changed { onConnectionChange() }
        return SwitchResult(switched: true, saved: saved)
    }

    /// 바꿨는가와 상태 저장이 됐는가. 연결할 때는 저장이 돼야 바꾸므로 바꿨으면 늘 저장됐다.
    /// 끊을 때 저장이 실패하면 메모리 상태가 옛 것 그대로라 녹음 파일을 지우지 않는다(부른 쪽이 `saved`를 본다).
    struct SwitchResult: Equatable { var switched: Bool; var saved: Bool }

    /// 강제 끊김 정리를 마쳤다: 연결이 없을 때만 GONE으로 둔다(그사이 새로 연결했으면 그대로).
    func showGone(deviceId: String) { if creds == nil { connection = .gone(deviceId: deviceId) } }

    /// GONE을 걷는다(U25 a에서 고름). `onlyDevice`가 있으면 그 기기의 GONE일 때만.
    func hideGone(onlyDevice: String? = nil) {
        if case .gone(let id) = connection, onlyDevice == nil || onlyDevice == id { connection = .none }
    }
}
