import Foundation

/// 모든 재시도·대기 간격(한 곳).
/// 지점마다 숫자를 쓰지 않고 여기 이름을 쓴다.
enum RetryPolicy {
    /// 보낼 함 실패 뒤 기다림 1·2·4…60초. 망 실패·5xx.
    static func outboxBackoff(attempts: Int) -> TimeInterval { min(60, pow(2, Double(min(attempts, 6)))) }
    /// 받은 함을 다 받지 못했을 때(조회·저장 실패) 다시 하기 5·10·20·40·60초. 다 받으면 처음으로 돌린다.
    static func inboxRetry(retries: Int) -> UInt64 { UInt64(min(60, 5 << min(retries, 4))) }
    /// 보낼 함 결과·만료·'보내 봄' 저장에 실패하면 이만큼 뒤 다시 보낸다.
    static let saveRetry: TimeInterval = 5
    /// 같은 연결에서 취소로 끝난 보내기(새로고침 화면이 사라짐 등)를 같은 id로 다시.
    static let cancelledRetry: TimeInterval = 1
    /// 예약한 보내기의 가장 짧은 기다림.
    static let minFlushDelay: TimeInterval = 0.2
    /// 429에 Retry-After가 없을 때(받은 함·메시지·소켓).
    static let rateLimitDefault: TimeInterval = 60
    /// 409 `song_quota`: 차가 켜져 파일을 처리한 뒤 다시.
    static let songQuota: TimeInterval = 60
    /// 연결 429 잠금(초). Retry-After가 없거나 1초 미만이면 10초·1초(안드로이드 pairLockUntil과 같다).
    static func pairLock(retryAfter: TimeInterval?) -> TimeInterval { max(retryAfter ?? 10, 1) }
    /// 연결 잠금 안내 글: 남은 시간은 잠금 끝 시각에서 그때그때 계산해 넘긴다. 60초 넘으면 분 올림, 이하면 초 올림.
    /// nil이면 시각을 모르는 '잠시 뒤', 0 이하(잠금 끝)면 nil.
    static func pairWaitText(remaining: TimeInterval?) -> String? {
        guard let r = remaining else { return "잠시 뒤에 다시 해 주세요" }
        guard r > 0 else { return nil }
        return r > 60 ? "\(Int((r / 60).rounded(.up)))분 뒤에 다시 해 주세요" : "\(Int(r.rounded(.up)))초 뒤에 다시 해 주세요"
    }
    /// 앞에 있고 소켓이 붙어 있는 동안 기기 목록을 다시 받는 주기(설계값).
    static let devicePollNanos: UInt64 = 60_000_000_000
    /// 소켓 다시 붙기 1·2·4…60초, 4008은 60초.
    static let socketFirst: UInt64 = 1
    static let socketMax: UInt64 = 60
    static let socketTooOften: UInt64 = 60
    /// 화면: '연결하는 중이에요', 인계 알림 배너, 되돌리기.
    static let pairBusyNotice: TimeInterval = 3
    static let bannerNanos: UInt64 = 8_000_000_000
    static let undoMs: Int64 = 5000
    /// 화면 읽기가 켜져 있으면 되돌리기 띠 50초(WCAG 2.2.1 '기본값의 10배 이상').
    static let undoMsScreenReader: Int64 = 50_000

    /// 띠 시간 한 곳(안드로이드 `undoBandMs`와 같은 이름): max(화면 읽기면 `screenReaderMs` 아니면 `baseMs`, 시스템 권장 시간).
    /// iOS는 시스템 권장 시간이 없어 0을 넘긴다. 띠를 띄울 때 한 번 정하고 떠 있는 동안 바꾸지 않는다.
    static func undoBandMs(screenReader: Bool, systemRecommendedMs: Int64 = 0, baseMs: Int64 = undoMs, screenReaderMs: Int64 = undoMsScreenReader) -> Int64 {
        max(screenReader ? screenReaderMs : baseMs, systemRecommendedMs)
    }
}

/// 보관 기간과 판단 창. 기간 숫자도 여기 한 곳이다.
enum Retention {
    /// 보낼 함·처리한 id·띄운 id·지운 곡 표시·기기 표시(7일).
    static let week: TimeInterval = 7 * 86400
    /// 리포트·인계 알림(90일).
    static let records: TimeInterval = 90 * 86400
    /// 인계 알림 배너는 방금 생긴 알림에만(푸시 유효 시간 30분).
    static let bannerWindow: TimeInterval = 30 * 60
    /// 마지막 수신이 이만큼 지나면 상태 요청 필요.
    static let stale: TimeInterval = 24 * 3600
    /// 상태 요청은 이 안에 다시 보내지 않는다.
    static let stateRequestGap: TimeInterval = 600
    /// 시계 차이 띠.
    static let clockWarning: TimeInterval = 120
    /// 폰 시계가 중계보다 이만큼 넘게 앞서면 차가 요약을 미래 시각이라 버린다.
    static let summaryAheadLimit: TimeInterval = 300
}
