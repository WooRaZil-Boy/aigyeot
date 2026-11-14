import Foundation

/// 잠금 화면 '끝내기'(`EndCareIntent`)가 앱 프로세스에서 부른다. 닫기 규칙은 타이머와 같다.
enum CareIntentHandler {
    @MainActor static func end(_ eventId: String) { AppContainer.shared.recordCare.end(eventId) }
}
