import Foundation
import Observation

/// 초대 만들기(owner, POST /v1/invites).
@MainActor
final class CreateInvite {
    private let state: PhoneStateRepository
    private let relay: RelayClient
    private let gate: RelayGate

    init(state: PhoneStateRepository, relay: RelayClient, gate: RelayGate) {
        self.state = state
        self.relay = relay
        self.gate = gate
    }

    /// 성공하면 (코드, nil), 실패하면 (nil, 화면 문구).
    func run() async -> (InviteResponse?, String?) {
        let gen = state.generation
        do { return (try await gate.observed { try await relay.createInvite() }, nil) }
        catch let e as RelayError {
            gate.unauthorized(e, gen: gen)
            if e.isOffline { return (nil, "인터넷이 필요해요") }
            if e.status == 409 { return (nil, "쓰지 않은 초대가 3개 있어요") }
            if e.status == 429 { return (nil, "잠시 뒤에 다시 해 주세요.") }
            return (nil, "초대를 만들지 못했어요. 잠시 뒤에 다시 해 주세요.")
        } catch { return (nil, "초대를 만들지 못했어요.") }
    }
}

/// 인계 알림 받기 켜고 끄기(guardian, PATCH /v1/devices/me).
@MainActor
@Observable
final class SetHandoffAlerts {
    @ObservationIgnored private let state: PhoneStateRepository
    @ObservationIgnored private let relay: RelayClient
    @ObservationIgnored private let gate: RelayGate
    /// 요청 중(화면을 다시 열어도 유지된다). 요청이 겹치면 늦게 온 앞 응답이 나중 설정을 덮는다.
    private(set) var busy = false

    init(state: PhoneStateRepository, relay: RelayClient, gate: RelayGate) {
        self.state = state
        self.relay = relay
        self.gate = gate
    }

    func run(_ on: Bool) async -> String? {
        guard !busy else { return nil }
        busy = true
        defer { busy = false }
        let gen = state.generation
        do {
            let r = try await gate.observed { try await relay.patchMe(PatchDeviceRequest(handoffAlerts: on)) }
            guard gen == state.generation else { return nil }
            state.update { $0.handoffAlerts = r.handoffAlerts }
            return nil
        } catch let e as RelayError {
            gate.unauthorized(e, gen: gen)
            return e.isOffline ? "인터넷이 필요해요" : "바꾸지 못했어요. 잠시 뒤에 다시 해 주세요."
        } catch { return "바꾸지 못했어요." }
    }
}

/// 인계 알림을 앱 안에 한 번만 띄우고 상세를 연다(5.3·5.6). 배너·열 알림의 원본이다.
@MainActor
@Observable
final class ShowAlert {
    @ObservationIgnored private let state: PhoneStateRepository
    @ObservationIgnored private let now: () -> Date
    /// 받은 함 갱신(SyncInbox.refresh). 받기가 이 클래스의 `raise`·`resolvePendingOpen`을 써서 합성 루트가 잇는다.
    @ObservationIgnored private let refresh: () async -> Void
    /// 앱 안 배너로 띄울 인계 알림 메시지 id(`show(msg_id)`, 5.6).
    private(set) var bannerAlertId: String?
    /// 알림을 눌러 열었을 때 상세로 갈 인계 알림 id.
    private(set) var openAlertId: String?
    /// 알림을 눌러 열려는 id. 본문이 저장되면 `openAlertId`로 옮긴다. 연결 세대가 바뀌거나 앱이 뒤로 가면 지운다(누른 그 앞 화면 동안만 기다린다).
    @ObservationIgnored var pendingOpenAlertId: String?

    init(state: PhoneStateRepository, now: @escaping () -> Date, refresh: @escaping () async -> Void) {
        self.state = state
        self.now = now
        self.refresh = refresh
    }

    func forget(_ e: LifecycleEvent) {
        switch e {
        case .connectionChanged: pendingOpenAlertId = nil; openAlertId = nil   // 지우는 알림을 가리키는 상세가 다시 연결한 뒤 '불러오고 있어요'로 남지 않게
        case .connectionLeft: bannerAlertId = nil
        case .recordsCleared: pendingOpenAlertId = nil; openAlertId = nil; bannerAlertId = nil
        case .background: pendingOpenAlertId = nil   // 본문을 못 받은 채 떠났으면 며칠 뒤 다른 화면 위에 상세가 저절로 뜨지 않게. 알림은 목록에 남는다
        }
    }

    /// 받은 함이 방금 생긴 실시간 인계 알림을 저장했다.
    func raise(_ id: String) { bannerAlertId = id }

    /// 배너 닫기(닫기 버튼, 8초). `id`를 주면 그 배너가 아직 떠 있을 때만 닫는다(그사이 새 배너가 떴으면 둔다).
    func dismissBanner(_ id: String? = nil) {
        if id == nil || bannerAlertId == id { bannerAlertId = nil }
    }

    /// 알림 상세 시트를 닫았다.
    func closeAlert() { openAlertId = nil }

    /// 앞에 있을 때 푸시(`willPresent`)가 부른다. 끊은 뒤 늦게 온 이전 연결의 푸시는 띄우지 않는다.
    func show(_ messageId: String) {
        guard state.isPaired, state.value.shown[messageId] == nil else { return }
        let t = now()
        if state.update({ $0.shown[messageId] = t }) { bannerAlertId = messageId }
    }

    /// 배너를 눌렀다(5.6): 본문이 있으면 상세를 열고, 없으면 받은 뒤 연다. 이 연결에 없는 알림(이전 차의 늦은 푸시)이면 열지 않는다(빈 상세에 머물지 않게).
    func openBanner(_ id: String) async {
        bannerAlertId = nil
        if state.value.alert(id) != nil { userOpened(id); showDetail(id) } else { await open(id) }
    }

    /// 알림 상세를 연다(본문이 저장된 알림).
    func showDetail(_ id: String) { openAlertId = id }

    /// 알림을 눌러 앱이 열렸을 때: 받은 함에서 본문을 받아 저장한 뒤 상세로 간다(5.3).
    func open(_ id: String) async {
        pendingOpenAlertId = id   // 진행 중인 조회가 있으면 refresh()가 바로 돌아오므로, 본문이 저장될 때 이어서 연다
        await refresh()
        resolvePendingOpen()
    }

    /// 사용자가 알림 상세를 직접 열었다. 다른 알림을 열려고 기다리던 요청은 버린다(고른 화면 위에 뜨지 않게).
    func userOpened(_ id: String) {
        if pendingOpenAlertId != id { pendingOpenAlertId = nil }
    }

    /// 열려던 알림의 본문이 저장돼 있으면 상세로 옮기고 보관값을 지운다.
    func resolvePendingOpen() {
        guard let id = pendingOpenAlertId, state.value.alert(id) != nil else { return }
        pendingOpenAlertId = nil
        openAlertId = id
    }
}
