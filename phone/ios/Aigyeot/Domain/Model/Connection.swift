import Foundation

/// 기기 토큰. 연결 응답에서 받아 Keychain에만 둔다.
struct Credentials: Codable, Equatable {
    var carId: String
    var deviceId: String
    var token: String
    var role: PhoneRole
}

enum ConnectFailure: Equatable {
    case codeInvalid, rateLimited(TimeInterval?), offline, badCode, other, busy
    /// 새 차로 바꾸려고 지금 연결을 끊다가 실패했다. 끊기의 이유 문구 그대로다.
    case disconnectFailed(String)
    /// 중계는 연결했는데 이 폰에 저장하지 못했다. `removed`: 받은 토큰으로 중계의 그 연결을 지웠다(안드로이드 discardPaired와 같은 글).
    case notSaved(removed: Bool)

    var message: String {
        switch self {
        case .codeInvalid: return "코드가 맞지 않거나 시간이 지났어요. 차 화면의 코드를 다시 확인해 주세요."
        case .rateLimited(let wait):
            if let w = wait, w >= 1 { return "잠시 뒤에 다시 해 주세요. (\(Int(w.rounded(.up)))초 뒤)" }
            return "잠시 뒤에 다시 해 주세요."
        case .offline: return "인터넷에 연결한 뒤 다시 해 주세요."
        case .badCode: return "6자리 숫자 또는 초대 코드를 넣어 주세요"
        case .other: return "연결하지 못했어요. 잠시 뒤에 다시 해 주세요."
        case .busy: return "이미 연결하고 있어요. 잠시 뒤에 다시 해 주세요."
        case .disconnectFailed(let m): return m
        case .notSaved(let removed): return removed ? "연결 정보를 저장하지 못했어요" : "연결 정보를 저장하지 못했어요. 차 화면에서 이 휴대폰 연결을 끊어 주세요."
        }
    }
}

enum SongError: Error, Equatable {
    case badName, tooLong, tooLarge, limitReached, saveFailed

    var message: String {
        switch self {
        case .badName: return "이름은 1~30자로 써 주세요."
        case .tooLong: return "3분이 넘어요. 3분 안으로 다시 녹음해 주세요."
        case .tooLarge: return "파일이 너무 커요. 다시 녹음해 주세요."
        case .limitReached: return "노래는 3곡까지예요. 한 곡을 지운 뒤 보내 주세요."
        case .saveFailed: return "저장하지 못했어요. 저장 공간을 확인해 주세요."
        }
    }
}
