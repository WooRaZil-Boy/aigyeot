import FirebaseCore
import UIKit
import UserNotifications

/// 알림 권한 묻기와 원격 푸시 등록.
enum NotificationAccess {
    /// 알림 권한을 묻고, 받으면 원격 푸시에 등록한다(requestAuthorization 뒤 registerForRemoteNotifications).
    @discardableResult
    static func request() async -> Bool {
        let ok = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        if ok { await MainActor.run { UIApplication.shared.registerForRemoteNotifications() } }
        // 권한 창이 닫혀도 화면 단계(scenePhase)가 바뀌지 않아, 띠가 다시 확인하도록 알린다
        await MainActor.run { NotificationCenter.default.post(name: answered, object: nil) }
        return ok
    }

    static let answered = Notification.Name("NotificationAccess.answered")

    /// 알림이 허용돼 있으면 원격 푸시에 등록한다. 실행 때와 앞으로 올 때마다 부른다: 거절했다가 설정 앱에서 켜고 돌아온 경우도 등록되게.
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
