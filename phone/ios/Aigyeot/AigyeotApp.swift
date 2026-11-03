import SwiftUI
import Network
import UserNotifications

extension AppModel {
    /// 앱 하나가 쓰는 모델. 중계 주소는 Info의 `RelayBaseURL`(debug는 `http://localhost:8787`)이다.
    static let shared: AppModel = {
        let raw = Bundle.main.object(forInfoDictionaryKey: "RelayBaseURL") as? String ?? "http://localhost:8787"
        let base = URL(string: raw) ?? URL(string: "http://localhost:8787")!
        let creds = KeychainCredentialStore()
        let client = RelayClient(baseURL: base, credentials: { creds.load() })
        let model = AppModel(relay: client, store: FileStateStore(), credentials: creds, baseURL: base)
        client.onServerDate = { d in Task { @MainActor in model.noteServerDate(d) } }
        return model
    }()
}

/// 인터넷 연결 여부. 돌아오면 받은 함·보낼 함을 다시 확인한다(7.5).
@MainActor
final class NetworkWatcher {
    private let monitor = NWPathMonitor()
    private var wasOffline = false

    func start(_ model: AppModel) {
        monitor.pathUpdateHandler = { path in
            let offline = path.status != .satisfied
            Task { @MainActor in
                let back = self.wasOffline && !offline
                self.wasOffline = offline
                model.offline = offline
                if back { await model.refresh() }
            }
        }
        monitor.start(queue: .global(qos: .utility))
    }
}

enum NotificationAccess {
    /// 알림 권한을 묻는다. 원격 푸시 등록은 FCM을 붙일 때 `registerForRemoteNotifications()`를 더한다.
    @discardableResult
    static func request() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    static func isDenied() async -> Bool {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .denied
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    /// 앞에 있을 때: 앱 안 표시 함수 `show(msg_id)`만 부르고 시스템 표시 옵션은 늘 `[]`로 돌려준다(5.3, 5.6).
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if let id = notification.request.content.userInfo["msg_id"] as? String {
            Task { @MainActor in AppModel.shared.show(id) }
        }
        completionHandler([])
    }

    /// 알림을 눌러 열었을 때: 받은 함에서 본문을 받아 저장한 뒤 상세로 간다.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let id = response.notification.request.content.userInfo["msg_id"] as? String {
            Task { @MainActor in await AppModel.shared.openAlert(id) }
        }
        completionHandler()
    }
}

@main
struct AigyeotApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase
    private let model = AppModel.shared
    private let network = NetworkWatcher()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(Theme.accent)
                .onOpenURL { model.receive(link: $0) }   // aigyeot://pair?code=… (7.6)
                .onAppear { network.start(model) }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: model.didBecomeActive()
            case .background: model.didEnterBackground()
            default: break
            }
        }
    }
}
