import Foundation

// 봉투(3.1)와 메시지 body(3.3). 기준은 shared/ 의 Kotlin 타입과 protocol/*.json 이다.

enum Role: String, Codable { case car, owner, guardian, relay }
enum Platform: String, Codable { case pleos, android, ios }
enum PhoneRole: String, Codable { case owner, guardian }
enum PhonePlatform: String, Codable { case android, ios }

struct Sender: Codable, Equatable {
    var role: Role
    @NullEncoded var device: String?
}

/// 봉투. 시각은 RFC 3339 문자열 그대로 둔다. `seq`·`car`·`from`은 중계가 채우므로 보낼 때는 비운다.
struct Envelope: Codable, Equatable {
    var v: Int = 1
    var id: String
    var seq: Int64?
    var type: String
    var car: String?
    var to: String?
    var from: Sender?
    var ts: String
    var body: JSONValue

    func decodeBody<T: Decodable>(_ type: T.Type) throws -> T { try body.decode(type) }
}

/// 메시지 type 이름(3.2 표).
enum MessageType {
    static let paired = "paired"
    static let unpaired = "unpaired"
    static let feedingStarted = "feeding_started"
    static let songAdded = "song_added"
    static let songRemoved = "song_removed"
    static let songSelected = "song_selected"
    static let inviteCreated = "invite_created"
    static let stateRequest = "state_request"
    static let songStatus = "song_status"
    static let feedingState = "feeding_state"
    static let reportSummary = "report_summary"
    static let handoffAlert = "handoff_alert"
    static let careSummary = "care_summary"
    static let ack = "ack"
}

/// `{}`인 body·요청 본문.
struct Empty: Codable, Equatable {}

struct PairedBody: Codable, Equatable {
    var deviceId: String
    var role: PhoneRole
    var platform: PhonePlatform
    var pairedAt: String
    var handoffAlerts: Bool
}

enum UnpairedBy: String, Codable { case car, `self`, relay }

struct UnpairedBody: Codable, Equatable {
    var deviceId: String
    var role: PhoneRole
    var by: UnpairedBy
}

struct FeedingStartedBody: Codable, Equatable { var at: String }

struct SongAddedBody: Codable, Equatable {
    var songId: String
    var name: String
    var durationS: Double
}

/// `song_removed`와 `song_selected`가 함께 쓴다.
struct SongRefBody: Codable, Equatable { var songId: String }

enum SongStatusKind: String, Codable { case accepted, rejected, removed }

enum SongRejectReason: String, Codable {
    case peakLimit = "peak_limit"
    case silent
    case badFormat = "bad_format"
    case tooLong = "too_long"
    case tooLarge = "too_large"
    case limitReached = "limit_reached"
    case downloadFailed = "download_failed"
}

struct SongStatusBody: Codable, Equatable {
    var songId: String
    @NullEncoded var name: String?
    var status: SongStatusKind
    @NullEncoded var reason: SongRejectReason?
    @NullEncoded var durationS: Double?
    var candidate: Bool
}

struct FeedingStateBody: Codable, Equatable {
    @NullEncoded var lastFeedingStart: String?
    @NullEncoded var windowStartExpected: String?
}

/// `care_summary`(3.3, L60). 숫자와 시각만 보낸다. `sleepDiaperEnabled`가 거짓이면 잠·기저귀 칸은 모두 null이다.
struct CareSummaryBody: Codable, Equatable {
    var asOf: String
    var zone: String
    var sleepDiaperEnabled: Bool
    @NullEncoded var lastSleepStartAt: String?
    @NullEncoded var lastSleepEndAt: String?
    @NullEncoded var sleepingSince: String?
    @NullEncoded var todaySleepMin: Int?
    @NullEncoded var nightWakeups: Int?
    @NullEncoded var lastDiaperAt: String?
    @NullEncoded var todayDiaperCount: Int?
    var todayFeedCount: Int
}

// ---- report_summary ----

enum OutputRoute: String, Codable {
    case rearZone = "rear_zone"
    case defaultZoneFallback = "default_zone_fallback"
    case noPlayback = "no_playback"
}

enum SoundMethod: String, Codable {
    case whiteNoise = "white_noise"
    case lullaby
    case parentSong = "parent_song"
    case womb
    case unknown   // 모르는 글자(U46: "기타"). 리포트를 버리지 않는다

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = SoundMethod(rawValue: raw) ?? .unknown
    }
}

enum EpisodeTrigger: String, Codable {
    case cryAlarm = "cry_alarm"
    case switchSound = "switch_sound"
}

enum EpisodeResult: String, Codable { case success, failure, undetermined }

enum ExcludedReason: String, Codable {
    case driverStop = "driver_stop"
    case call
    case gearToPark = "gear_to_park"
    case startedInPark = "started_in_park"
    case switchSound = "switch_sound"
    case sessionCap = "session_cap"
    case feedingWindow = "feeding_window"
    case micError = "mic_error"
    case focusLoss = "focus_loss"
    case detectorTimeout = "detector_timeout"
    case inputSilenced = "input_silenced"
    case suspend
    case sessionEnd = "session_end"
}

enum PlayPurpose: String, Codable {
    case autoFirst = "auto_first"
    case autoSecond = "auto_second"
    case request
}

enum SecondAction: String, Codable {
    case comfortCool = "comfort_cool"
    case feedingWindow = "feeding_window"
    case otherSound = "other_sound"
    case focusDenied = "focus_denied"
}

enum StopReason: String, Codable {
    case attemptLimit = "attempt_limit"
    case sessionCap = "session_cap"
    case feedingWindow = "feeding_window"
    case noSound = "no_sound"
}

/// 인계 방식(4.3). `timeline[].stop.mode`와 `handoff_alert.kind`가 함께 쓴다.
enum HandoffKind: String, Codable {
    case restOffer = "rest_offer"
    case arrivalNotice = "arrival_notice"
    case noRest = "no_rest"
    case parked
}

enum Gear: String, Codable { case P, R, N, D }

enum SteeringUnavailableReason: String, Codable {
    case noComparableWindow = "no_comparable_window"
    case noCry = "no_cry"
}

enum CarActionKind: String, Codable {
    case rearPlayback = "rear_playback"
    case defaultZonePlayback = "default_zone_playback"
    case seatHeaterOff = "seat_heater_off"
    case rearWindowLock = "rear_window_lock"
    case cabinCool = "cabin_cool"
    case readingLightOff = "reading_light_off"
    case waypointAdded = "waypoint_added"
    case arrivalNotice = "arrival_notice"
    case guardianAlert = "guardian_alert"
}

struct SoundRef: Codable, Equatable {
    var method: SoundMethod
    var label: String
}

struct PlayEntry: Codable, Equatable {
    var purpose: PlayPurpose
    var method: SoundMethod
    @NullEncoded var songId: String?
    var startedAt: String
    var endedAt: String
    var playS: Int
}

struct SecondAttempt: Codable, Equatable {
    var action: SecondAction
    @NullEncoded var sound: SoundRef?
    @NullEncoded var result: EpisodeResult?
}

struct StopInfo: Codable, Equatable {
    var handoff: Bool
    var reason: StopReason
    var mode: HandoffKind
    @NullEncoded var restName: String?
    var waypointAdded: Bool
    var guardianNotified: Bool
}

struct TimelineEpisode: Codable, Equatable {
    var episodeId: String
    var trigger: EpisodeTrigger
    var startedAt: String
    @NullEncoded var alarmLatencyS: Double?
    @NullEncoded var sound: SoundRef?
    var playS: Int
    var plays: [PlayEntry]
    @NullEncoded var result: EpisodeResult?
    @NullEncoded var excludedReason: ExcludedReason?
    var recried: Bool
    @NullEncoded var second: SecondAttempt?
    @NullEncoded var stop: StopInfo?
    var totalCryS: Int
}

struct PreCryContext: Codable, Equatable {
    var episodeId: String
    var windowS: Int
    var speedKmhAvg: Double
    var speedKmhMax: Double
    var brakePresses: Int
    var steeringReversals: Int
    var turnSignalUsed: Bool
    var gear: Gear
    @NullEncoded var cabinTempC: Double?
    @NullEncoded var windowOpen: Bool?
    var playing: Bool
}

struct Steering: Codable, Equatable {
    var available: Bool
    @NullEncoded var baselinePerMin: Double?
    @NullEncoded var duringCryPerMin: Double?
    @NullEncoded var afterResponsePerMin: Double?
    @NullEncoded var duringCryZ: Double?
    @NullEncoded var unavailableReason: SteeringUnavailableReason?
}

struct CarAction: Codable, Equatable {
    var action: CarActionKind
    var count: Int
    @NullEncoded var totalS: Int?
}

struct ControlRecord: Codable, Equatable {
    var api: String
    var target: String
    var attempts: Int
    var succeeded: Int
    var failed: Int
    var retries: Int
}

/// `report_summary.method_stats[]`와 `POST /v1/llm`의 `state.method_stats[]`가 함께 쓴다.
struct MethodStat: Codable, Equatable {
    var method: SoundMethod
    var label: String
    var successes: Int
    var attempts: Int
}

struct ReportSummaryBody: Codable, Equatable {
    var sessionId: String
    var startedAt: String
    var endedAt: String
    var simulated: Bool
    var outputRoute: OutputRoute
    var truncated: Bool?
    var timeline: [TimelineEpisode]
    var preCryContext: [PreCryContext]
    var steering: Steering
    var carActions: [CarAction]
    var controls: [ControlRecord]
    var methodStats: [MethodStat]?
}

struct HandoffAlertBody: Codable, Equatable {
    var text: String
    var kind: HandoffKind
    @NullEncoded var restName: String?
    @NullEncoded var minutesToArrival: Int?
    var occurredAt: String
    var sessionId: String
    var episodeId: String
}

struct AckBody: Codable, Equatable { var ids: [String] }
