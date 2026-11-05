import ActivityKit
import AppIntents
import Foundation
import os

// 앱과 위젯 확장이 함께 쓰는 잠금 화면 진행 표시(C6). 앱의 다른 코드에 기대지 않는다.

/// 진행 중 수유·잠 하나에 Live Activity 하나. 시작 시각·쪽은 고칠 수 있어 상태에 둔다.
struct CareActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var startAt: Date
        /// 쪽 글자(수유만, 7.1 `notify.body`). 없으면 nil.
        var side: String?
    }
    var eventId: String
    var sleep: Bool
}

/// 7.1 `notify.title.*`·`notify.end`. 앱의 `CareText`도 이 값을 쓴다.
enum CareActivityText {
    static let feeding = "수유 중"
    static let sleeping = "자는 중"
    static let end = "끝내기"
}

/// 잠금 화면·Dynamic Island의 '끝내기'. `LiveActivityIntent`라 시스템이 앱 프로세스에서 `perform`을 부른다.
/// 그래서 앱과 같은 `care.json`을 쓰고(App Group 없음), 닫기 규칙(U14)도 앱의 `endNow`가 한다.
struct EndCareIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "끝내기"
    static var isDiscoverable = false   // 단축어 목록에 내놓지 않는다

    @Parameter(title: "eventId") var eventId: String

    init() {}
    init(eventId: String) { self.eventId = eventId }

    func perform() async throws -> some IntentResult {
        #if WIDGET_EXTENSION
        Logger(subsystem: "kr.aigyeot.phone", category: "care").error("끝내기 인텐트가 위젯 확장에서 불렸다(앱에서 돌아야 한다)")
        #else
        Logger(subsystem: "kr.aigyeot.phone", category: "care").info("끝내기 인텐트: 앱에서 실행")
        await CareIntentHandler.end(eventId)
        #endif
        return .result()
    }
}
