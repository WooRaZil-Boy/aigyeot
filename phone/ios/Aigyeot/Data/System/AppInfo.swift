import Foundation

/// 앱 판(정보·라이선스 화면과 연결 요청의 `app_version`이 함께 쓴다).
enum AppInfo {
    static var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }
}
