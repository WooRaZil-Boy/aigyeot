import Foundation

// 계약(contracts-link.md 0장 '공통 표기'): 필드는 snake_case, 모르는 필드는 무시한다.
// "또는 null"은 명시적 null(`@NullEncoded`), "선택"은 키 빠짐(보통 Optional)이다.

enum ProtocolCoding {
    static var decoder: JSONDecoder {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }

    static var encoder: JSONEncoder {
        let e = JSONEncoder()
        e.keyEncodingStrategy = .convertToSnakeCase
        return e
    }
}

/// 값이 없으면 키를 빼지 않고 `null`로 쓰는 Optional. 계약의 "또는 null" 필드에 쓴다.
/// 읽을 때도 키가 있어야 한다. 키가 빠지면 decode 실패다(Kotlin은 기본값이 없고 explicitNulls = true).
@propertyWrapper
struct NullEncoded<T: Codable & Equatable>: Codable, Equatable {
    var wrappedValue: T?
    init(wrappedValue: T?) { self.wrappedValue = wrappedValue }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        wrappedValue = c.decodeNil() ? nil : try c.decode(T.self)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        if let v = wrappedValue { try c.encode(v) } else { try c.encodeNil() }
    }
}

/// 형식을 모르는 JSON 조각(봉투 body, LLM 도구 입력). 소수 여부는 구분하지 않고 `Double`로 둔다.
enum JSONValue: Codable, Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([JSONValue].self) { self = .array(v) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }

    /// 키 이름을 바꾸지 않는 일반 디코더로 읽는다(시험에서 원본 JSON을 읽을 때도 쓴다).
    static func parse(_ data: Data) throws -> JSONValue {
        try JSONDecoder().decode(JSONValue.self, from: data)
    }

    var objectValue: [String: JSONValue]? {
        if case .object(let o) = self { return o }
        return nil
    }

    func data() throws -> Data { try JSONEncoder().encode(self) }

    /// 타입이 있는 값 → 계약 표기(snake_case)의 JSON 조각.
    init<T: Encodable>(encoding value: T) throws {
        self = try JSONValue.parse(ProtocolCoding.encoder.encode(value))
    }

    func decode<T: Decodable>(_ type: T.Type) throws -> T {
        try ProtocolCoding.decoder.decode(type, from: data())
    }
}
