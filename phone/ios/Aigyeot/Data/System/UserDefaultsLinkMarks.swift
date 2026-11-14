import Foundation

/// LinkMarks 구현: 앱 UserDefaults의 작은 표시. 키 이름은 Mark의 값(안드로이드 prefs 키와 같다).
@MainActor
final class UserDefaultsLinkMarks: LinkMarks {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func get(_ mark: Mark) -> String? { defaults.string(forKey: mark.rawValue) }

    @discardableResult
    func put(_ mark: Mark, _ value: String?) -> Bool {
        if let value { defaults.set(value, forKey: mark.rawValue) } else { defaults.removeObject(forKey: mark.rawValue) }
        // UserDefaults는 쓰기 실패를 알리지 않는다: 읽어 맞춰 본다. 디스크 기록은 시스템이 맡는다(synchronize는 쓰지 않는다, Apple: 필요 없음)
        return get(mark) == value
    }
}
