import FirebaseCore
import FirebaseMessaging
import SwiftUI
import UserNotifications

/// FCM 등록 토큰. Firebase 설정 파일이 없으면(시험) 늘 nil이다.
final class PushToken: PushTokens {
    static let shared = PushToken()
    var token: String?
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

    /// FCM 토큰이 생기거나 바뀌면 중계에 알린다. 실행마다 한 번은 불린다(FIRMessaging.h).
    /// 새 `didReceiveRegistration`(설치 ID 방식)은 쓰지 않는다: 중계는 FCM 등록 토큰으로 보낸다(relay/src/push.ts).
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        Task { @MainActor in AppContainer.shared.registerPush.onToken(fcmToken) }
    }

    /// 앞에 있을 때: 앱 안 표시 함수 `show(msg_id)`만 부르고 시스템 표시 옵션은 늘 `[]`로 돌려준다.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if let id = notification.request.content.userInfo["msg_id"] as? String {
            Task { @MainActor in AppContainer.shared.syncInbox.onPush(id) }
        }
        completionHandler([])
    }

    /// 알림을 눌러 열었을 때: 받은 함에서 본문을 받아 저장한 뒤 상세로 간다.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let id = response.notification.request.content.userInfo["msg_id"] as? String {
            Task { @MainActor in await AppContainer.shared.appViewModel.onAlert(id) }
        }
        completionHandler()
    }
}

@main
struct AigyeotApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase
    private let app = AppContainer.shared

    init() {
        // 내비게이션 제목도 Pretendard(큰 제목 26/700, 접힌 제목 17/700). 막대 바탕은 시스템 그대로 둔다.
        UINavigationBar.appearance().largeTitleTextAttributes = [.font: DesignSystem.uiFont(26, 700, relativeTo: .largeTitle),
                                                                 .foregroundColor: UIColor(DesignSystem.textPrimary)]
        UINavigationBar.appearance().titleTextAttributes = [.font: DesignSystem.uiFont(17, 700, relativeTo: .headline),
                                                            .foregroundColor: UIColor(DesignSystem.textPrimary)]
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                // 화면은 뷰모델만 본다(저장소·유스케이스에 직접 손대지 않는다). 앱 범위는 셋, 나머지는 화면이 공장으로 만든다
                .environment(app.appViewModel).environment(app.status).environment(app.careTabs)
                .environment(\.viewModels, app)
                .tint(DesignSystem.accentInk)
                .typeStyle(.body)   // 글자를 따로 정하지 않은 글도 시스템 서체가 아니라 본문 단계(Pretendard)로
                .environment(\.locale, Locale(identifier: "ko_KR"))   // 한국 전용 범위: 시각 고르기도 "오전 3:12"로
                .onOpenURL { app.appViewModel.receive(link: $0) }   // aigyeot://pair?code=…
                .onAppear {
                    app.keepConnected.watchNetwork()   // 인터넷이 돌아오면 받은 함·보낼 함을 다시 확인한다
                    app.syncLiveActivities()   // 앱이 꺼진 사이 바뀐 것을 맞춘다
                }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                app.lifecycle.onForeground()
                Task { await NotificationAccess.registerIfAuthorized() }   // 설정 앱에서 알림을 켜고 돌아온 경우(같은 프로세스)
                app.syncLiveActivities()   // 설정에서 실시간 현황을 켜고 왔거나 시스템이 끝낸 표시를 되살린다
            case .background: app.lifecycle.onBackground()
            default: break
            }
        }
    }
}
