@testable import Aigyeot
import XCTest

/// 메시지 형식 시험:`shared/src/test/resources/protocol/` 의 모든 JSON을 해당 타입으로 decode → encode 해
/// 원래 JSON과 트리로 같은지 본다. 표에 없는 파일, 파일 없는 표 항목은 실패한다.
/// 소수 값은 트리에서 숫자로 비교한다(Swift의 JSONEncoder는 71.0을 `71`로 쓴다. 받는 쪽은 같은 숫자로 읽는다).
final class ProtocolFixturesTests: XCTestCase {
    /// 이 파일 기준 저장소 루트의 protocol 폴더(`phone/ios/AigyeotTests/` → 위로 셋).
    static let dir = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("shared/src/test/resources/protocol")

    typealias RoundTrip = (Data) throws -> JSONValue

    static func rt<T: Codable>(_: T.Type) -> RoundTrip {
        { data in
            let value = try ProtocolCoding.decoder.decode(T.self, from: data)
            return try JSONValue.parse(ProtocolCoding.encoder.encode(value))
        }
    }

    /// 메시지 type → body 왕복(type 전부).
    static let bodies: [String: RoundTrip] = [
        MessageType.paired: rt(PairedBody.self),
        MessageType.unpaired: rt(UnpairedBody.self),
        MessageType.feedingStarted: rt(FeedingStartedBody.self),
        MessageType.songAdded: rt(SongAddedBody.self),
        MessageType.songRemoved: rt(SongRefBody.self),
        MessageType.songSelected: rt(SongRefBody.self),
        MessageType.inviteCreated: rt(Empty.self),
        MessageType.stateRequest: rt(Empty.self),
        MessageType.songStatus: rt(SongStatusBody.self),
        MessageType.feedingState: rt(FeedingStateBody.self),
        MessageType.reportSummary: rt(ReportSummaryBody.self),
        MessageType.handoffAlert: rt(HandoffAlertBody.self),
        MessageType.careSummary: rt(CareSummaryBody.self),
        MessageType.ack: rt(AckBody.self)
    ]

    enum Target {
        case envelope(String)
        case api(RoundTrip)
    }

    static let table: [String: Target] = [
        "paired": .envelope("paired"),
        "unpaired": .envelope("unpaired"),
        "feeding_started": .envelope("feeding_started"),
        "feeding_started_send": .envelope("feeding_started"),
        "song_added": .envelope("song_added"),
        "song_removed": .envelope("song_removed"),
        "song_selected": .envelope("song_selected"),
        "invite_created": .envelope("invite_created"),
        "state_request": .envelope("state_request"),
        "song_status": .envelope("song_status"),
        "song_status_removed": .envelope("song_status"),
        "song_status_rejected": .envelope("song_status"),
        "feeding_state": .envelope("feeding_state"),
        "feeding_state_reply": .envelope("feeding_state"),
        "report_summary": .envelope("report_summary"),
        "report_summary_handoff": .envelope("report_summary"),
        "handoff_alert": .envelope("handoff_alert"),
        "handoff_alert_arrival": .envelope("handoff_alert"),
        "care_summary": .envelope("care_summary"),
        "care_summary_off": .envelope("care_summary"),
        "report_summary_womb": .envelope("report_summary"),
        "ack": .envelope("ack"),
        "api_error": .api(rt(ErrorResponse.self)),
        "api_health_response": .api(rt(HealthResponse.self)),
        "api_cars_request": .api(rt(RegisterCarRequest.self)),
        "api_cars_response": .api(rt(RegisterCarResponse.self)),
        "api_pair_codes_request": .api(rt(Empty.self)),
        "api_pair_codes_response": .api(rt(PairCodeResponse.self)),
        "api_pair_request": .api(rt(PairRequest.self)),
        "api_pair_request_push": .api(rt(PairRequest.self)),
        "api_pair_response": .api(rt(PairResponse.self)),
        "api_invites_response": .api(rt(InviteResponse.self)),
        "api_devices_response": .api(rt(DevicesResponse.self)),
        "api_devices_me_request": .api(rt(PatchDeviceRequest.self)),
        "api_devices_me_clear_request": .api(rt(PatchDeviceRequest.self)),
        "api_devices_me_keep_request": .api(rt(PatchDeviceRequest.self)),
        "api_devices_me_response": .api(rt(PatchDeviceResponse.self)),
        "api_messages_response": .api(rt(SendMessageResponse.self)),
        "api_inbox_response": .api(rt(InboxResponse.self)),
        "api_songs_response": .api(rt(UploadSongResponse.self)),
        "api_llm_request": .api(rt(LlmRequest.self)),
        "api_llm_request_care": .api(rt(LlmRequest.self)),
        "api_llm_request_minimal": .api(rt(LlmRequest.self)),
        "api_llm_response": .api(rt(LlmResponse.self)),
        "api_llm_response_tool": .api(rt(LlmResponse.self)),
        "api_llm_response_answer": .api(rt(LlmResponse.self))
    ]

    func test_모든_예제가_왕복해도_같다() throws {
        let files = try FileManager.default.contentsOfDirectory(at: Self.dir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        XCTAssertFalse(files.isEmpty, "예제 폴더를 못 찾았다: \(Self.dir.path)")
        XCTAssertEqual(Self.table.keys.sorted(), files.map { $0.deletingPathExtension().lastPathComponent },
                       "표와 protocol/ 파일이 다르다")

        for f in files {
            let name = f.deletingPathExtension().lastPathComponent
            guard let target = Self.table[name] else { continue }
            let data = try Data(contentsOf: f)
            let original = try JSONValue.parse(data)
            switch target {
            case .envelope(let type):
                let roundTripped = try Self.rt(Envelope.self)(data)
                XCTAssertEqual(original, roundTripped, "\(name): 봉투")
                let env = try ProtocolCoding.decoder.decode(Envelope.self, from: data)
                XCTAssertEqual(env.type, type, "\(name): type")
                let rtBody = try XCTUnwrap(Self.bodies[type], "\(name): body 왕복 없음")
                let originalBody = try XCTUnwrap(original.objectValue?["body"])
                XCTAssertEqual(originalBody, try rtBody(originalBody.data()), "\(name): body")
            case .api(let rt):
                XCTAssertEqual(original, try rt(data), name)
            }
        }
    }

    /// 조명 낮춤: car_actions의 ambient_dim을 읽고 '조명 낮춤'으로 보인다(안드로이드와 같은 글자).
    func test_리포트_차_동작에_조명_낮춤이_있다() throws {
        let env = try ProtocolCoding.decoder.decode(Envelope.self, from: Data(contentsOf: Self.dir.appendingPathComponent("report_summary.json")))
        let body = try env.decodeBody(ReportSummaryBody.self)
        XCTAssertEqual(body.carActions.map(\.action), [.rearPlayback, .seatHeaterOff, .ambientDim])
        XCTAssertEqual(actionText(.ambientDim), "조명 낮춤")
    }

    func test_모르는_method의_리포트도_읽고_unknown이_된다() throws {
        let url = Self.dir.appendingPathComponent("report_summary.json")
        let text = try String(contentsOf: url, encoding: .utf8)
        XCTAssertTrue(text.contains("\"method\":\"lullaby\"") || text.contains("\"method\": \"lullaby\""), "예제에 lullaby가 없다")
        let changed = text.replacingOccurrences(of: "\"lullaby\"", with: "\"future_sound\"")
        let env = try ProtocolCoding.decoder.decode(Envelope.self, from: Data(changed.utf8))
        let body = try env.decodeBody(ReportSummaryBody.self)
        XCTAssertEqual(body.timeline.first?.sound?.method, .unknown)
    }

    /// 보낼 때 "빠짐"과 "null"이 메시지 형식대로 나가는지(왕복이 같아도 놓칠 수 있는 한 줄 확인).
    func test_push_token_세_상태() throws {
        func json(_ r: PatchDeviceRequest) throws -> String {
            String(decoding: try ProtocolCoding.encoder.encode(r), as: UTF8.self)
        }
        XCTAssertEqual(try json(PatchDeviceRequest()), "{}")
        XCTAssertEqual(try json(PatchDeviceRequest(pushToken: .clear)), "{\"push_token\":null}")
        // 키 순서는 메시지 형식이 정하지 않는다. 트리로 본다.
        XCTAssertEqual(try JSONValue.parse(Data(json(PatchDeviceRequest(pushToken: .set("t"), handoffAlerts: false)).utf8)),
                       .object(["push_token": .string("t"), "handoff_alerts": .bool(false)]))
    }

    func test_연결_요청의_push_token은_명시적_null() throws {
        let r = PairRequest(code: "042917", platform: .ios, pushToken: nil, appVersion: "1.0.0")
        let s = String(decoding: try ProtocolCoding.encoder.encode(r), as: UTF8.self)
        XCTAssertTrue(s.contains("\"push_token\":null"), s)
    }
}
