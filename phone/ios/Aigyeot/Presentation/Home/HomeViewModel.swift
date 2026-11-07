import Foundation
import Observation

/// 탭·위쪽 띠·인계 알림 배너·차 타일의 상태(7.2, 5.6).
struct HomeState {
    var role: PhoneRole?
    var isPaired: Bool
    var offline: Bool
    var clockWarning: String?
    var notice: String?
    var bannerAlertId: String?
    var openAlertId: String?
    var car: DeviceInfo?
    var alerts: [StoredAlert]

    /// '누른 것은 연결되면 차로 가요' 띠는 차에 연결된 폰에만 보인다(U7: 연결 전에는 차로 보내지 않는다, 안드로이드와 같다).
    var showsOfflineBand: Bool { offline && isPaired }
    func alert(_ id: String) -> StoredAlert? { alerts.first { $0.id == id } }
}

@MainActor
@Observable
final class HomeViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let link: LinkObserver
    @ObservationIgnored private let notices: NoticeBoard
    @ObservationIgnored private let alerts: ShowAlert
    @ObservationIgnored private let inbox: SyncInbox

    init(state: PhoneStateRepository, link: LinkObserver, notices: NoticeBoard, alerts: ShowAlert, inbox: SyncInbox) {
        stateRepo = state
        self.link = link
        self.notices = notices
        self.alerts = alerts
        self.inbox = inbox
    }

    var state: HomeState {
        HomeState(role: stateRepo.role, isPaired: stateRepo.isPaired, offline: link.offline, clockWarning: link.clockWarning,
                  notice: notices.notice, bannerAlertId: alerts.bannerAlertId, openAlertId: alerts.openAlertId,
                  car: stateRepo.value.car, alerts: stateRepo.value.alerts)
    }

    /// 띠의 '닫기'(U48·U54).
    func dismissNotice() { notices.dismiss() }
    /// 띠의 '다시 시도'·당겨서 새로고침(7.2, 7.5).
    func retryNow() async { await inbox.retryNow() }
    /// 배너를 눌렀다: 본문이 있으면 상세, 없으면 받은 뒤 연다(5.6).
    func openBanner(_ id: String) async { await alerts.openBanner(id) }
    /// 배너 닫기. `id`를 주면 그 배너가 아직 떠 있을 때만(8초).
    func dismissBanner(_ id: String? = nil) { alerts.dismissBanner(id) }
    /// 알림 상세 시트를 닫았다.
    func closeAlert() { alerts.closeAlert() }
    /// 직접 고른 알림: 대기하던 다른 알림 열기는 버린다.
    func userOpenedAlert(_ id: String) { alerts.userOpened(id) }
}
