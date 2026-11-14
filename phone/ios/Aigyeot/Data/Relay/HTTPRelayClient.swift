import Foundation

/// 도메인과 같은 메인 액터에서 돈다. 토큰은 요청마다 부른 쪽이 잡아 둔 자격의 것을 받는다(안드로이드와 같다):
/// Keychain을 읽지 않으니 그사이 새 연결이 끝나도 이전 연결의 요청에 새 토큰이 붙지 않는다.
@MainActor
final class HTTPRelayClient: RelayClient {
    let baseURL: URL
    private let session: URLSession
    /// 응답의 `Date` 헤더(시계 차이 확인용).
    var onServerDate: ((Date) -> Void)?

    private let installId: String
    private let clientHeader: String

    init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
        let key = "install_id"
        if let v = UserDefaults.standard.string(forKey: key) {
            installId = v
        } else {
            installId = UUID().uuidString
            UserDefaults.standard.set(installId, forKey: key)
        }
        let info = Bundle.main.infoDictionary
        clientHeader = "ios/\(info?["CFBundleShortVersionString"] as? String ?? "0")(\(info?["CFBundleVersion"] as? String ?? "0"))"
    }

    static var appVersion: String { AppInfo.version }

    // MARK: 요청

    /// `token`이 없으면 연결 요청(설치 id 헤더)이다.
    private func request(_ method: String, _ path: String, query: [URLQueryItem] = [],
                         token: String?, contentType: String? = nil) -> URLRequest {
        var comps = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { comps.queryItems = query }
        var req = URLRequest(url: comps.url!)
        req.httpMethod = method
        req.timeoutInterval = 20
        req.setValue(clientHeader, forHTTPHeaderField: "X-Client")
        if let token {
            // 토큰은 헤더로만 보낸다. 경로·쿼리에는 넣지 않는다.
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        } else {
            req.setValue(installId, forHTTPHeaderField: "X-Install-Id")
        }
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        return req
    }

    private func jsonRequest<B: Encodable>(_ method: String, _ path: String, body: B, token: String?) throws -> URLRequest {
        var req = request(method, path, token: token, contentType: "application/json; charset=utf-8")
        req.httpBody = try ProtocolCoding.encoder.encode(body)
        return req
    }

    /// `Retry-After`(초). 유한한 0 이상만 받고 하루로 자른다: 잘못된 헤더(inf·아주 큰 수)가 정수 변환에서 앱을 죽이지 않게.
    nonisolated static func retryAfter(_ http: HTTPURLResponse) -> TimeInterval? {
        guard let v = http.value(forHTTPHeaderField: "Retry-After").flatMap({ TimeInterval($0) }), v.isFinite, v >= 0 else { return nil }
        return min(v, 86_400)
    }

    /// 응답 전 실패. 취소(화면을 떠남·뒤로 감)는 망 실패와 가른다.
    nonisolated static func transportError(_ e: Error) -> RelayError {
        if e is CancellationError || (e as? URLError)?.code == .cancelled { return .cancelled }
        return .network
    }

    private func perform(_ req: URLRequest, upload: URL? = nil) async throws -> Data {
        let data: Data
        let response: URLResponse
        do {
            if let upload {
                (data, response) = try await session.upload(for: req, fromFile: upload)
            } else {
                (data, response) = try await session.data(for: req)
            }
        } catch {
            throw Self.transportError(error)
        }
        guard let http = response as? HTTPURLResponse else { throw RelayError.invalidResponse }
        if let date = http.value(forHTTPHeaderField: "Date"), let d = Self.httpDate.date(from: date) { onServerDate?(d) }
        guard (200..<300).contains(http.statusCode) else {
            let code = (try? ProtocolCoding.decoder.decode(ErrorResponse.self, from: data))?.error.code
            throw RelayError.http(status: http.statusCode, code: code, retryAfter: Self.retryAfter(http))
        }
        return data
    }

    /// 받은 함 한 쪽을 봉투마다 읽는다. 읽지 못한 봉투는 쪽 전체를 막지 않게 id·seq만 남긴 판 0 봉투로 바꾼다:
    /// 모델은 모르는 판을 ack만 하고 넘어가고, 다음 `after_seq`도 그 seq를 넘는다. id도 없으면 ack할 수 없어 뺀다.
    nonisolated static func lenientInbox(_ data: Data, car: String?) throws -> InboxResponse {
        struct Item: Decodable {
            let env: Envelope?, id: String?, seq: Int64?
            enum K: String, CodingKey { case id, seq }
            init(from d: Decoder) throws {
                env = try? Envelope(from: d)
                let c = try? d.container(keyedBy: K.self)
                id = try? c?.decode(String.self, forKey: .id)
                seq = try? c?.decode(Int64.self, forKey: .seq)
            }
        }
        struct Page: Decodable { let messages: [Item]; let more: Bool }
        guard let page = try? ProtocolCoding.decoder.decode(Page.self, from: data) else { throw RelayError.invalidResponse }
        let messages: [Envelope] = page.messages.compactMap { i in
            if let e = i.env { return e }
            guard let id = i.id else { return nil }
            return Envelope(v: 0, id: id, seq: i.seq, type: "", car: car, to: nil, from: nil, ts: "", body: .null)
        }
        return InboxResponse(messages: messages, more: page.more)
    }

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do { return try ProtocolCoding.decoder.decode(type, from: data) } catch { throw RelayError.invalidResponse }
    }

    private static let httpDate: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return f
    }()

    // MARK: 엔드포인트

    func pair(code: String, pushToken: String?) async throws -> PairResponse {
        // 연결 코드는 URL이 아니라 본문으로 보낸다.
        let body = PairRequest(code: code, platform: .ios, pushToken: pushToken, appVersion: Self.appVersion)
        return try decode(PairResponse.self, try await perform(jsonRequest("POST", "v1/pair", body: body, token: nil)))
    }

    func devices(token: String) async throws -> DevicesResponse {
        try decode(DevicesResponse.self, try await perform(request("GET", "v1/devices", token: token)))
    }

    func patchMe(token: String, _ r: PatchDeviceRequest) async throws -> PatchDeviceResponse {
        try decode(PatchDeviceResponse.self, try await perform(jsonRequest("PATCH", "v1/devices/me", body: r, token: token)))
    }

    func deleteMe(token: String) async throws {
        _ = try await perform(request("DELETE", "v1/devices/me", token: token))
    }

    func createInvite(token: String) async throws -> InviteResponse {
        let env = Envelope(id: ULID.generate(), type: MessageType.inviteCreated, ts: Rfc3339.string(Date()), body: .object([:]))
        return try decode(InviteResponse.self, try await perform(jsonRequest("POST", "v1/invites", body: env, token: token)))
    }

    func send(token: String, _ envelope: Envelope) async throws -> SendMessageResponse {
        try decode(SendMessageResponse.self, try await perform(jsonRequest("POST", "v1/messages", body: envelope, token: token)))
    }

    func inbox(token: String, car: String, afterSeq: Int64?) async throws -> InboxResponse {
        var q = [URLQueryItem(name: "limit", value: "100")]
        if let afterSeq { q.append(URLQueryItem(name: "after_seq", value: String(afterSeq))) }
        let data = try await perform(request("GET", "v1/inbox", query: q, token: token))
        // 한 쪽에 64KB 봉투가 100개까지 온다. 해석은 메인 밖에서 한다.
        return try await Self.parseInbox(data, car: car)
    }

    /// 도메인 액터 밖(동시 실행기)에서 읽는다(분리 Task 없이).
    @concurrent
    nonisolated private static func parseInbox(_ data: Data, car: String?) async throws -> InboxResponse {
        try lenientInbox(data, car: car)
    }

    func ack(token: String, ids: [String]) async throws {
        guard !ids.isEmpty else { return }
        for chunk in stride(from: 0, to: ids.count, by: 100).map({ Array(ids[$0..<min($0 + 100, ids.count)]) }) {
            let body = try JSONValue(encoding: AckBody(ids: chunk))
            let env = Envelope(id: ULID.generate(), type: MessageType.ack, ts: Rfc3339.string(Date()), body: body)
            _ = try await perform(jsonRequest("POST", "v1/ack", body: env, token: token))
        }
    }

    /// `PUT /v1/songs/{song_id}`. 같은 id 파일이 이미 있으면(409 `already_exists`) 성공으로 본다.
    func putSong(token: String, id: String, file: URL) async throws {
        let req = request("PUT", "v1/songs/\(id)", token: token, contentType: "audio/mp4")
        do { _ = try await perform(req, upload: file) } catch let e as RelayError where e.status == 409 && e.code == "already_exists" { return }
    }
}
