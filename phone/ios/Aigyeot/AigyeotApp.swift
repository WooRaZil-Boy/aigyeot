import SwiftUI
import Network
import UserNotifications
import FirebaseCore
import FirebaseMessaging

extension AppModel {
    /// 앱 하나가 쓰는 모델. 중계 주소는 Info의 `RelayBaseURL`(debug는 `http://localhost:8787`)이다.
    static let shared: AppModel = {
        let raw = Bundle.main.object(forInfoDictionaryKey: "RelayBaseURL") as? String ?? "http://localhost:8787"
        let base = URL(string: raw) ?? URL(string: "http://localhost:8787")!
        let creds = KeychainCredentialStore()
        let client = RelayClient(baseURL: base, credentials: { creds.load() })
        let model = AppModel(relay: client, store: FileStateStore(), credentials: creds, careStore: FileCareStore(), baseURL: base)
        model.pushToken = PushToken.shared   // FCM 토큰(5.4). 연결 요청과 앱 열기의 PATCH가 이 값을 보낸다
        // RelayClient는 메인에서 돈다. 응답을 돌려주기 전에 시계 차이를 반영해, 같은 응답의 처리(요약 보낸 값 판단)가 새 값을 본다.
        client.onServerDate = { d in MainActor.assumeIsolated { model.noteServerDate(d) } }
        model.onCareChange = { CareLiveActivities.sync($0) }   // 잠금 화면 '끝내기'로 앱이 뒤에서 떠도 이 고리가 걸린다
        CareLiveActivities.sync(model.care)   // 고리를 걸기 전 시작 정리(지우다 만 기록 지우기 등)가 바꾼 것도 맞춘다
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-demoSeed") {   // U50 시연 시드
            model.replaceCareForDebug { DemoSeed.make(nowMs: CareTime.ms(Date()), zone: .current, keep: $0) }
        }
        if ProcessInfo.processInfo.arguments.contains("-longFeed") {   // 투어: 2시간 전에 시작한 진행 중 수유(긴 경과 표시를 시각과 상관없이 본다)
            let t = CareTime.ms(Date()) - 2 * 3_600_000
            model.replaceCareForDebug { f in
                var f = f
                f.events.append(CareEvent(id: ULID.generate(date: CareTime.date(t)), kind: .feed, startAt: t, zone: TimeZone.current.identifier, createdAt: t, updatedAt: t))
                return f
            }
        }
        #endif
        return model
    }()
}

/// 인터넷 연결 여부. 돌아오면 받은 함·보낼 함을 다시 확인한다(7.5). 띠를 켜고 끄는 규칙은 모델이 정한다.
@MainActor
final class NetworkWatcher {
    private let monitor = NWPathMonitor()
    private var started = false

    func start(_ model: AppModel) {
        guard !started else { return }   // onAppear가 다시 불려도 한 번만 시작한다
        started = true
        monitor.pathUpdateHandler = { path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in await model.networkPathChanged(satisfied: satisfied) }
        }
        monitor.start(queue: .global(qos: .utility))
    }
}

/// FCM 등록 토큰(5.4). Firebase 설정 파일이 없으면(공개 저장소·시험) 늘 nil이다.
final class PushToken: PushTokenSource {
    static let shared = PushToken()
    var token: String?
}

enum NotificationAccess {
    /// 알림 권한을 묻고, 받으면 원격 푸시에 등록한다(5.3: requestAuthorization 뒤 registerForRemoteNotifications).
    @discardableResult
    static func request() async -> Bool {
        let ok = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        if ok { await MainActor.run { UIApplication.shared.registerForRemoteNotifications() } }
        // 권한 창이 닫혀도 화면 단계(scenePhase)가 바뀌지 않아, 띠가 다시 확인하도록 알린다
        await MainActor.run { NotificationCenter.default.post(name: answered, object: nil) }
        return ok
    }

    static let answered = Notification.Name("NotificationAccess.answered")

    /// 알림이 허용돼 있으면 원격 푸시에 등록한다(5.3). 실행 때와 앞으로 올 때마다 부른다: 거절했다가 설정 앱에서 켜고 돌아온 경우도 등록되게.
    /// Firebase 설정 파일이 없으면 받을 곳이 없으니 등록하지 않는다. 같은 값이면 APNs·Firebase가 같은 토큰을 돌려준다.
    static func registerIfAuthorized() async {
        guard FirebaseApp.app() != nil,
              await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .authorized else { return }
        await MainActor.run { UIApplication.shared.registerForRemoteNotifications() }
    }

    static func isDenied() async -> Bool {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .denied
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        // Firebase 설정 파일은 공개하지 않는다. 있을 때만 원격 푸시를 켠다(안드로이드 google-services.json과 같다)
        if Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
            Messaging.messaging().delegate = self
            Task { await NotificationAccess.registerIfAuthorized() }   // 이미 허용했으면 실행마다 등록해 토큰을 받는다(권한 창은 다시 뜨지 않는다)
        }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        if FirebaseApp.app() != nil { Messaging.messaging().apnsToken = deviceToken }
    }

    /// 5.4: FCM 토큰이 생기거나 바뀌면 중계에 알린다. 실행마다 한 번은 불린다(FIRMessaging.h).
    /// 새 `didReceiveRegistration`(설치 ID 방식)은 쓰지 않는다: 중계는 FCM 등록 토큰으로 보낸다(relay/src/push.ts).
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        Task { @MainActor in
            PushToken.shared.token = fcmToken
            AppModel.shared.pushTokenDidChange()
        }
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
                .tint(DesignSystem.accent)
                .onOpenURL { model.receive(link: $0) }   // aigyeot://pair?code=… (7.6)
                .onAppear {
                    network.start(model)
                    CareLiveActivities.sync(model.care)   // 앱이 꺼진 사이 바뀐 것을 맞춘다
                }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                model.didBecomeActive()
                Task { await NotificationAccess.registerIfAuthorized() }   // 설정 앱에서 알림을 켜고 돌아온 경우(같은 프로세스)
                CareLiveActivities.sync(model.care)   // 설정에서 실시간 현황을 켜고 왔거나 시스템이 끝낸 표시를 되살린다
            case .background: model.didEnterBackground()
            default: break
            }
        }
    }
}
