import Foundation

/// 중계 호출 실패. 화면 문구는 앱이 `code`로 정한다(계약 1.1·9장).
enum RelayError: Error, Equatable {
    case http(status: Int, code: String?, retryAfter: TimeInterval?)
    case network
    case invalidResponse

    var status: Int? { if case .http(let s, _, _) = self { return s } else { return nil } }
    var code: String? { if case .http(_, let c, _) = self { return c } else { return nil } }
    var retryAfter: TimeInterval? { if case .http(_, _, let r) = self { return r } else { return nil } }
    var isUnauthorized: Bool { status == 401 }
    var isOffline: Bool { self == .network }
}

/// 폰이 쓰는 중계 호출 전부. 시험에서는 가짜로 바꾼다.
protocol RelayAPI {
    func pair(code: String, pushToken: String?) async throws -> PairResponse
    func devices() async throws -> DevicesResponse
    func patchMe(_ request: PatchDeviceRequest) async throws -> PatchDeviceResponse
    func deleteMe() async throws
    func createInvite() async throws -> InviteResponse
    func send(_ envelope: Envelope) async throws -> SendMessageResponse
    func inbox(afterSeq: Int64?) async throws -> InboxResponse
    func ack(ids: [String]) async throws
    func putSong(id: String, file: URL) async throws
}

/// 기기 토큰. 연결 응답에서 받아 Keychain에만 둔다.
struct Credentials: Codable, Equatable {
    var carId: String
    var deviceId: String
    var token: String
    var role: PhoneRole
}

final class RelayClient: RelayAPI {
    let baseURL: URL
    private let session: URLSession
    private let credentials: () -> Credentials?
    /// 응답의 `Date` 헤더(시계 차이 확인용, 계약 9장).
    var onServerDate: ((Date) -> Void)?

    private let installId: String
    private let clientHeader: String

    init(baseURL: URL, session: URLSession = .shared, credentials: @escaping () -> Credentials?) {
        self.baseURL = baseURL
        self.session = session
        self.credentials = credentials
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

    static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    // MARK: 요청

    private func request(_ method: String, _ path: String, query: [URLQueryItem] = [],
                         authorized: Bool = true, contentType: String? = nil) throws -> URLRequest {
        var comps = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { comps.queryItems = query }
        var req = URLRequest(url: comps.url!)
        req.httpMethod = method
        req.timeoutInterval = 20
        req.setValue(clientHeader, forHTTPHeaderField: "X-Client")
        if authorized {
            // 토큰은 헤더로만 보낸다. 경로·쿼리에는 넣지 않는다(1.1).
            guard let c = credentials() else { throw RelayError.http(status: 401, code: "unauthorized", retryAfter: nil) }
            req.setValue("Bearer \(c.token)", forHTTPHeaderField: "Authorization")
        } else {
            req.setValue(installId, forHTTPHeaderField: "X-Install-Id")
        }
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        return req
    }

    private func jsonRequest<B: Encodable>(_ method: String, _ path: String, body: B, authorized: Bool = true) throws -> URLRequest {
        var req = try request(method, path, authorized: authorized, contentType: "application/json; charset=utf-8")
        req.httpBody = try ProtocolCoding.encoder.encode(body)
        return req
    }

    private func perform(_ req: URLRequest, upload: URL? = nil) async throws -> Data {
        let data: Data
        let response: URLResponse
        do {
            if let upload { (data, response) = try await session.upload(for: req, fromFile: upload) }
            else { (data, response) = try await session.data(for: req) }
        } catch {
            throw RelayError.network
        }
        guard let http = response as? HTTPURLResponse else { throw RelayError.invalidResponse }
        if let date = http.value(forHTTPHeaderField: "Date"), let d = Self.httpDate.date(from: date) { onServerDate?(d) }
        guard (200..<300).contains(http.statusCode) else {
            let code = (try? ProtocolCoding.decoder.decode(ErrorResponse.self, from: data))?.error.code
            let retry = http.value(forHTTPHeaderField: "Retry-After").flatMap { TimeInterval($0) }
            throw RelayError.http(status: http.statusCode, code: code, retryAfter: retry)
        }
        return data
    }

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do { return try ProtocolCoding.decoder.decode(type, from: data) }
        catch { throw RelayError.invalidResponse }
    }

    private static let httpDate: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return f
    }()

    // MARK: 엔드포인트(1.4)

    func pair(code: String, pushToken: String?) async throws -> PairResponse {
        // 연결 코드는 URL이 아니라 본문으로 보낸다(1.1).
        let body = PairRequest(code: code, platform: .ios, pushToken: pushToken, appVersion: Self.appVersion)
        return try decode(PairResponse.self, try await perform(jsonRequest("POST", "v1/pair", body: body, authorized: false)))
    }

    func devices() async throws -> DevicesResponse {
        try decode(DevicesResponse.self, try await perform(request("GET", "v1/devices")))
    }

    func patchMe(_ r: PatchDeviceRequest) async throws -> PatchDeviceResponse {
        try decode(PatchDeviceResponse.self, try await perform(jsonRequest("PATCH", "v1/devices/me", body: r)))
    }

    func deleteMe() async throws {
        _ = try await perform(request("DELETE", "v1/devices/me"))
    }

    func createInvite() async throws -> InviteResponse {
        let env = Envelope(id: ULID.generate(), type: MessageType.inviteCreated, ts: Rfc3339.string(Date()), body: .object([:]))
        return try decode(InviteResponse.self, try await perform(jsonRequest("POST", "v1/invites", body: env)))
    }

    func send(_ envelope: Envelope) async throws -> SendMessageResponse {
        try decode(SendMessageResponse.self, try await perform(jsonRequest("POST", "v1/messages", body: envelope)))
    }

    func inbox(afterSeq: Int64?) async throws -> InboxResponse {
        var q = [URLQueryItem(name: "limit", value: "100")]
        if let afterSeq { q.append(URLQueryItem(name: "after_seq", value: String(afterSeq))) }
        return try decode(InboxResponse.self, try await perform(request("GET", "v1/inbox", query: q)))
    }

    func ack(ids: [String]) async throws {
        guard !ids.isEmpty else { return }
        for chunk in stride(from: 0, to: ids.count, by: 100).map({ Array(ids[$0..<min($0 + 100, ids.count)]) }) {
            let body = try JSONValue(encoding: AckBody(ids: chunk))
            let env = Envelope(id: ULID.generate(), type: MessageType.ack, ts: Rfc3339.string(Date()), body: body)
            _ = try await perform(jsonRequest("POST", "v1/ack", body: env))
        }
    }

    /// `PUT /v1/songs/{song_id}`. 같은 id 파일이 이미 있으면(409 `already_exists`) 성공으로 본다.
    func putSong(id: String, file: URL) async throws {
        let req = try request("PUT", "v1/songs/\(id)", contentType: "audio/mp4")
        do { _ = try await perform(req, upload: file) }
        catch let e as RelayError where e.status == 409 && e.code == "already_exists" { return }
    }
}
