import Foundation
import Observation

/// 지금 연결(phone-care-plan 9.1). 원본은 PhoneStateRepository 하나다. 끊김(GONE, 401·4001)과 연결됨이 함께일 수 없다.
enum Connection: Equatable {
    case none
    /// 연결 세대는 저장소의 `generation` 한 곳에만 있다(P1). 비동기 결과는 시작할 때 잡은 그 세대와 같을 때만 적용한다(P5).
    case connected(Credentials)
    /// 차나 중계 쪽에서 끊겼다. '연결이 끊겼어요' 화면을 볼 때까지 남는다(U25 a).
    case gone

    var credentials: Credentials? { if case .connected(let c) = self { c } else { nil } }
}

/// 폰 상태(`state.json`)와 지금 연결의 원본(phone-care-plan 9.1). 쓰기 입구는 `update`와 `switchTo` 둘뿐이다(P7).
@MainActor
@Observable
final class PhoneStateRepository {
    @ObservationIgnored private let store: StateStore
    private(set) var value: PhoneState
    private(set) var connection: Connection
    /// 연결이 바뀔 때마다(새 차 연결·끊기·GONE) 오른다. 연결 없음에서도 다음 연결이 옛 결과와 섞이지 않게 센다.
    private(set) var generation = 0

    /// 지금 연결에서 계산한다(P4).
    var credentials: Credentials? { connection.credentials }
    var role: PhoneRole? { credentials?.role }
    var isPaired: Bool { credentials != nil }
    /// 저장 실패 공통 처리(StoreFailure: 띠 글). 범주마다 다른 뒷일은 부른 쪽이 정한다.
    @ObservationIgnored var onWriteFailed: () -> Void = {}

    /// 복원하지 못했는가(파일은 있는데 읽지 못함). 그러면 그 실행에서는 녹음 파일 정리를 하지 않는다.
    var restoreFailed: Bool { store.restoreFailed }
    /// 원본을 보존하지 못해 이 실행에서 저장을 거부하는가.
    var writesRefused: Bool { store.writesRefused }

    init(store: StateStore, credentials: Credentials?) {
        self.store = store
        var s = store.load()
        // 토큰이 없는데 상태만 남았으면(Keychain만 지워진 경우) 상태도 비운다(메모리만, 다음 저장에 쓰인다).
        if credentials == nil, s.carId != nil { s = Self.fresh(introSeen: true, keeping: s) }
        value = s
        connection = credentials.map { .connected($0) } ?? .none
    }

    /// 상태를 바꾸고 저장한다. 저장하지 못하면 메모리 상태도 바꾸지 않고(롤백) false를 돌려준다.
    /// 상태 변경과 보낼 함 넣기는 한 번의 `update`로 묶어 한 번에 저장한다(계약 8.3의 '한 트랜잭션').
    @discardableResult
    func update(_ f: (inout PhoneState) -> Void) -> Bool {
        // 원본을 보존하지 못한 실행은 모든 저장을 거부한다. 같음 비교보다 먼저 막아야
        // 빈 상태로의 초기화가 '성공'으로 보여 녹음 파일이 지워지는 일이 없다.
        if store.writesRefused { onWriteFailed(); return false }
        var copy = value
        f(&copy)
        if copy == value { return true }   // 바뀐 게 없으면 저장하지 않는다
        do { try store.save(copy) } catch { onWriteFailed(); return false }
        value = copy
        return true
    }

    /// 연결을 비운 새 상태. 지우다 만 '기록 지우기'(돌봄 기록은 연결과 따로다, R9·R10)는 남긴다.
    static func fresh(introSeen: Bool, keeping old: PhoneState) -> PhoneState {
        var s = PhoneState(introSeen: introSeen)
        s.clearingCareIds = old.clearingCareIds
        return s
    }

    enum Switch: Equatable {
        /// 새 차에 연결(방 바뀜). Keychain 저장이 성공해야 연결됨이 된다.
        case connect(Credentials)
        /// 사용자가 끊었다(U25 b).
        case disconnect
        /// 401·4001(계약 7.1 GONE).
        case gone
    }

    enum SwitchResult: Equatable { case done, stateNotSaved, secretNotSaved }

    /// 지금 연결을 바꾸는 한 입구(옛 switchTo): 세대를 올리고, 지난 연결의 기록을 비운 상태를 저장하고, Keychain 비밀값을 맞춘 뒤 연결을 바꾼다.
    /// 이전 상태·보낼 함을 지우는 저장이 성공해야만 새 자격을 켠다(이전 차의 보낼 함이 새 차로 가지 않게).
    /// 끊기·GONE은 저장에 실패해도 자격을 지운다(토큰이 이미 중계에서 지워졌다). 메모리 칸·파일의 정리는 수명 함수(Lifecycle)가 한다.
    func switchTo(_ s: Switch, secrets: CredentialRepository) -> SwitchResult {
        generation += 1
        let wiped = update { $0 = Self.fresh(introSeen: $0.introSeen, keeping: $0) }
        switch s {
        case .connect(let c):
            guard wiped else { return .stateNotSaved }
            // Keychain에 저장하지 못하면 연결로 치지 않는다(다음 실행에서 토큰이 없어 끊긴 것처럼 되지 않게).
            do { try secrets.save(c) } catch { return .secretNotSaved }
            connection = .connected(c)
        case .disconnect:
            secrets.clear()
            connection = .none
        case .gone:
            secrets.clear()
            connection = .gone
        }
        return wiped ? .done : .stateNotSaved
    }

    /// '연결이 끊겼어요' 화면을 넘겼다(기록만 계속·다시 연결하기, U25 a). 세대는 그대로다.
    func leaveGone() { if connection == .gone { connection = .none } }
}
