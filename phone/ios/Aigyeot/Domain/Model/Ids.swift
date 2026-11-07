import Foundation

/// 돌봄 기록의 시각(epoch ms, UTC)과 `Date`를 바꾼다.
enum CareTime {
    static func ms(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
    static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(ms) / 1000) }
}

/// ULID(대문자 Crockford base32 26자): 시각 48비트 + 무작위 80비트.
enum ULID {
    private static let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    static func generate(date: Date = Date()) -> String {
        var out: [Character] = []
        var t = UInt64(max(0, date.timeIntervalSince1970 * 1000))
        for _ in 0..<10 { out.append(alphabet[Int(t & 31)]); t >>= 5 }
        out.reverse()
        for _ in 0..<16 { out.append(alphabet[Int.random(in: 0..<32)]) }
        return String(out)
    }
}

/// RFC 3339 시각. 계약은 시차를 붙인 문자열이고 밀리초는 있어도 된다.
enum Rfc3339 {
    // 포매터는 만들기가 비싸 한 번만 만든다. ISO8601DateFormatter는 읽기 전용으로 쓰면 스레드 안전하다(옵션을 바꾸지 않는다).
    nonisolated(unsafe) private static let withFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    nonisolated(unsafe) private static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    static func parse(_ s: String) -> Date? {
        withFraction.date(from: s) ?? plain.date(from: s)
    }

    /// 키를 항목마다 한 번만 파싱해 내림차순으로 정렬한다.
    static func sortedDescending<T>(_ items: [T], key: (T) -> String) -> [T] {
        items.map { (parse(key($0)) ?? .distantPast, $0) }.sorted { $0.0 > $1.0 }.map(\.1)
    }

    /// 보낼 때는 이 폰의 시차를 붙여 쓴다(`2026-11-05T08:12:21+09:00`).
    static func string(_ d: Date, timeZone: TimeZone = .current) -> String {
        lock.lock(); defer { lock.unlock() }
        let f = writers[timeZone.identifier] ?? {
            let f = ISO8601DateFormatter()
            f.formatOptions = [.withInternetDateTime]
            f.timeZone = timeZone
            writers[timeZone.identifier] = f
            return f
        }()
        return f.string(from: d)
    }
    nonisolated(unsafe) private static var writers: [String: ISO8601DateFormatter] = [:]   // 시간대마다 한 번만 만든다
    private static let lock = NSLock()
}

/// 연결 코드(6자리 숫자)와 초대 코드(10자 Crockford base32). QR·링크·입력 셋 다 같은 처리다(명세서 12장).
enum PairCode: Equatable, Identifiable {
    var id: String { value }
    case pair(String)
    case invite(String)

    var value: String {
        switch self { case .pair(let c), .invite(let c): return c }
    }

    /// 사람이 읽기 쉬운 표기. 초대 코드는 5자씩 끊는다(`7K3QM-2XW9D`).
    var display: String {
        switch self {
        case .pair(let c): return c
        case .invite(let c): return String(c.prefix(5)) + "-" + String(c.suffix(5))
        }
    }

    /// 확인 화면 제목. 초대 코드에 "차 화면에…"라고 묻지 않는다(G17).
    var confirmTitle: String {
        switch self {
        case .pair: return "차 화면에 이 코드가 보이나요?"
        case .invite: return "받은 초대 코드가 이것과 같나요?"
        }
    }

    private static let crockford = Set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    /// 공백·하이픈을 지우고 대문자로 바꾼 뒤 Crockford 규칙(O→0, I·L→1)을 적용한다. 형식이 아니면 nil.
    static func normalize(_ raw: String) -> PairCode? {
        let s = raw.uppercased().filter { !$0.isWhitespace && $0 != "-" }
        if s.count == 6, s.allSatisfy({ $0 >= "0" && $0 <= "9" }) { return .pair(s) }
        let mapped = String(s.map { c -> Character in
            switch c { case "O": return "0"; case "I", "L": return "1"; default: return c }
        })
        if mapped.count == 10, mapped.allSatisfy({ crockford.contains($0) }) { return .invite(mapped) }
        return nil
    }

    /// `aigyeot://pair?code=…` 링크. 다른 앱도 같은 scheme을 등록할 수 있으므로 형식 검사를 거친다(6.4).
    static func parse(link url: URL) -> PairCode? {
        guard url.scheme == "aigyeot", url.host == "pair",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let code = items.first(where: { $0.name == "code" })?.value else { return nil }
        return normalize(code)
    }

    /// QR 글자 또는 입력. 링크면 링크로, 아니면 코드 글자로 읽는다.
    static func parse(scanned text: String) -> PairCode? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: t), url.scheme != nil { return parse(link: url) }
        return normalize(t)
    }
}
