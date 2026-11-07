import Foundation

enum SendOutcome: Equatable {
    case done
    case drop
    case retry(TimeInterval)
    case unauthorized
    /// 파일부터 다시 올린다(422 `song_blob_missing`).
    case reupload
}

/// 보낼 함 응답 처리(계약 7.5, 오류 표 1.1). 순서는 넣은 순서 그대로이고 하나씩 보낸다.
enum OutboxPolicy {
    static let maxAge: TimeInterval = 7 * 24 * 3600

    /// 실패 뒤 기다림 1·2·4…60초.
    static func backoff(attempts: Int) -> TimeInterval {
        min(60, pow(2, Double(min(attempts, 6))))
    }

    static func classify(_ error: RelayError, attempts: Int) -> SendOutcome {
        switch error {
        case .network, .invalidResponse, .cancelled:   // 취소는 보내기에서 먼저 걸러 여기 오지 않는다
            return .retry(backoff(attempts: attempts))
        case .http(let status, let code, let retryAfter):
            switch status {
            case 401: return .unauthorized
            case 429: return .retry(max(retryAfter ?? 60, 1))
            case 409 where code == "song_quota": return .retry(60)   // 차가 켜져 파일을 처리한 뒤 다시
            case 422 where code == "song_blob_missing": return .reupload
            case 500...599: return .retry(backoff(attempts: attempts))
            // 400·403·409·413·415 등은 다시 보내도 같으므로 버리고 화면에 알린다.
            default: return .drop
            }
        }
    }
}
