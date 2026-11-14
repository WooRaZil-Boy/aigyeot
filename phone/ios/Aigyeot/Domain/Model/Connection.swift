import Foundation

/// 기기 토큰. 연결 응답에서 받아 Keychain에만 둔다. 같은 값이면 같은 연결 세대다.
struct Credentials: Codable, Equatable {
    var carId: String
    var deviceId: String
    var token: String
    var role: PhoneRole
}

// 작업 상태(연관값 enum, 안드로이드 Links.kt와 같은 이름). 불가능한 조합(도는 중인데 오류 등)은 타입으로 막는다.

/// 지금 연결(두 앱 같음). 원본은 PhoneStateRepository 하나다. 자격이 있으면서 GONE인 조합은 없다(강제 끊김은 지운 뒤에 GONE이 된다).
enum Connection: Equatable {
    case none
    /// 같은 값이면 같은 연결 세대다. 비동기 결과는 시작할 때 잡은 이 자격으로 처리한다.
    case paired(Credentials)
    /// 401·닫기 4001로 끊겼고 GONE 화면('연결이 끊겼어요')에서 아직 고르지 않았다.
    case gone(deviceId: String)

    var creds: Credentials? { if case .paired(let c) = self { c } else { nil } }
}

/// 코드로 연결하기. 원본은 PairDevice.run. '끊고 연결하기'의 끊기도 `running` 하나가 덮는다.
enum PairRun: Equatable {
    case running(code: PairCode)
    case failed(code: PairCode, ConnectFailure)
    /// 연결됐다. `role`이 nil이면 끝난 사이 끊겨(GONE) 연결됨으로 가지 않는다.
    case done(code: PairCode, role: PhoneRole?)

    var code: PairCode {
        switch self {
        case .running(let c), .failed(let c, _), .done(let c, _): return c
        }
    }
}

/// 설정의 '연결 끊기'. 원본은 Disconnect.run.
enum DisconnectRun: Equatable {
    case running
    case failed(String)
    /// 끊었다: 탭(연결 안 됨)의 오늘로 간다.
    case done
}

/// 연결 표시. 원본은 KeepConnected.status. 자격이 바뀌면 처음 값으로 돌아간다.
struct LinkStatus: Equatable {
    /// 중계에 닿는가. 거짓이면 오프라인 띠.
    var online = true
    var socket = false
    /// 시계 차이 띠의 글(2분 넘게 다를 때만). 원시 차이(초)는 응답마다 조금씩 바뀌어 화면을 깨우지 않게
    /// 여기 두지 않는다(`KeepConnected.clockSkew`, 안드로이드 `clockSkewMin`과 같은 자리).
    var clockWarning: String?
}

/// 수유·요약을 차로 보내는 계기. 계기마다 RecordCare.syncToCar 한 곳을 부른다.
enum FeedTrigger: Equatable {
    case appStart, foreground, careSaved, paired
    /// 가져오기의 되돌리기 띠가 끝났다(5초·다른 띠로 바뀜·되돌림): 띠 동안 차로 가는 계산에서 빠졌던 줄이 이제 보이므로 수유·요약을 한 번 맞춘다.
    case importSettled
}

enum ConnectFailure: Equatable {
    case codeInvalid, rateLimited, offline, badCode, other, busy
    /// 새 차로 바꾸려고 지금 연결을 끊다가 실패했다. 끊기의 이유 문구 그대로다.
    case disconnectFailed(String)
    /// 중계는 연결했는데 이 폰에 저장하지 못했다. `removed`: 받은 토큰으로 중계의 그 연결을 지웠다(안드로이드 discardPaired와 같은 글).
    case notSaved(removed: Bool)

    /// 화면에 보일 실패인가('코드 시도 초과': 이 글은 잠금 동안만 보이고 Retry-After가 지나 단추가 다시 켜지면 걷는다).
    /// 429는 잠금 동안만, 다른 실패는 그대로다. 직접 입력·확인 화면·잠금 중 누름이 모두 이것만 쓴다.
    func visible(locked: Bool) -> ConnectFailure? {
        if case .rateLimited = self { return locked ? self : nil }
        return self
    }

    /// 화면에 보일 글. 429는 잠금 끝 시각에서 지금 남은 시간을 계산한다(처음 받은 값을 굳히지 않는다).
    func text(lockedUntil: Date?, now: Date) -> String {
        guard case .rateLimited = self, let until = lockedUntil else { return message }
        return RetryPolicy.pairWaitText(remaining: until.timeIntervalSince(now)) ?? message
    }

    var message: String {
        switch self {
        case .codeInvalid: return "코드가 맞지 않거나 시간이 지났어요. 차 화면의 코드를 다시 확인해 주세요."
        case .rateLimited: return RetryPolicy.pairWaitText(remaining: nil)!   // 남은 시간이 든 글은 `text(lockedUntil:now:)`
        case .offline: return "인터넷에 연결한 뒤 다시 해 주세요"   // 연결 오류 글자
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
