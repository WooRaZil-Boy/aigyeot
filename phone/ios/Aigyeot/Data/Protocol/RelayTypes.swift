import Foundation

// 중계 HTTP API 요청·응답 본문(contracts-link.md 1.4). 메시지 보내기·ack·초대 만들기의 요청은 봉투다.

struct ErrorResponse: Codable, Equatable { var error: ErrorDetail }
struct ErrorDetail: Codable, Equatable { var code: String; var message: String }

struct HealthResponse: Codable, Equatable {
    var ok: Bool
    var version: String
    var llm: Bool
    var push: Bool
}

struct RegisterCarRequest: Codable, Equatable { var platform: Platform; var appVersion: String }
struct RegisterCarResponse: Codable, Equatable { var carId: String; var deviceId: String; var carToken: String }

struct PairCodeResponse: Codable, Equatable { var code: String; var expiresAt: String; var link: String }

struct PairRequest: Codable, Equatable {
    var code: String
    var platform: Platform
    @NullEncoded var pushToken: String?
    var appVersion: String
}

struct PairResponse: Codable, Equatable {
    var carId: String
    var deviceId: String
    var deviceToken: String
    var role: Role
}

struct InviteResponse: Codable, Equatable {
    var code: String
    var display: String
    var expiresAt: String
    var link: String
}

struct DeviceInfo: Codable, Equatable {
    var deviceId: String
    var role: Role
    var platform: Platform
    var pairedAt: String
    @NullEncoded var lastSeenAt: String?
    var online: Bool
    /// 차 행에는 없다(1.4 예시).
    var handoffAlerts: Bool?
}

struct DevicesResponse: Codable, Equatable { var snapshotSeq: Int64; var devices: [DeviceInfo] }

/// `PATCH /v1/devices/me`의 `push_token` 세 상태(1.4): 빠짐 = 그대로, 문자열 = 바꾸기, 명시적 null = 지우기.
enum PushTokenPatch: Equatable {
    case keep
    case set(String)
    case clear
}

struct PatchDeviceRequest: Codable, Equatable {
    var pushToken: PushTokenPatch = .keep
    var handoffAlerts: Bool?

    enum CodingKeys: String, CodingKey { case pushToken, handoffAlerts }

    init(pushToken: PushTokenPatch = .keep, handoffAlerts: Bool? = nil) {
        self.pushToken = pushToken
        self.handoffAlerts = handoffAlerts
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if c.contains(.pushToken) {
            pushToken = try c.decodeNil(forKey: .pushToken) ? .clear : .set(try c.decode(String.self, forKey: .pushToken))
        } else {
            pushToken = .keep
        }
        handoffAlerts = try c.decodeIfPresent(Bool.self, forKey: .handoffAlerts)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        switch pushToken {
        case .keep: break
        case .set(let t): try c.encode(t, forKey: .pushToken)
        case .clear: try c.encodeNil(forKey: .pushToken)
        }
        try c.encodeIfPresent(handoffAlerts, forKey: .handoffAlerts)
    }
}

struct PatchDeviceResponse: Codable, Equatable {
    var deviceId: String
    var role: Role
    var platform: Platform
    var handoffAlerts: Bool
    var pushTokenSet: Bool
}

/// `POST /v1/messages` 응답. `body`는 중계가 저장한 그대로(고쳐진 값 포함).
struct SendMessageResponse: Codable, Equatable {
    var id: String
    var seq: Int64
    var recipients: Int
    var body: JSONValue
}

struct InboxResponse: Codable, Equatable { var messages: [Envelope]; var more: Bool }

struct UploadSongResponse: Codable, Equatable { var songId: String; var sizeBytes: Int64 }

// ---- POST /v1/llm (차만 쓴다. 계약 시험에서 형식을 맞추려고 둔다) ----

struct LlmCry: Codable, Equatable {
    @NullEncoded var currentMin: Int?
    var countThisDrive: Int
}

struct LlmFeeding: Codable, Equatable { var elapsedMin: Int; var intervalMin: Int }

struct LlmNextRest: Codable, Equatable {
    var id: String
    var name: String
    var distanceKm: Double
    var nursingRoom: Bool
}

struct LlmState: Codable, Equatable {
    var cry: LlmCry
    @NullEncoded var feeding: LlmFeeding?
    var carseatMin: Int
    @NullEncoded var cabinTemp: Double?
    @NullEncoded var nextRest: LlmNextRest?
    var methodStats: [MethodStat]
    @NullEncoded var lastNightWakeups: Int?
}

struct LlmRequest: Codable, Equatable {
    var requestId: String
    var question: String
    var state: LlmState
}

struct LlmToolCall: Codable, Equatable { var name: String; var input: JSONValue }

struct LlmResponse: Codable, Equatable {
    var text: String
    var toolCalls: [LlmToolCall]
    var stopReason: String
    var model: String
    var promptVersion: String
    var latencyMs: Int64
}
