import Foundation

/// 돌봄 요약을 보낼 함에 넣을지 정하고 넣는 규칙. 요약 계산은 `BuildCareSummary`다.
/// `lastSentCareSummary`·`lastSentCareAsOf`·`maxSentCareAsOf`와 보낼 함의 요약 항목은 이 파일의 함수로만 고친다.
enum SummaryRules {
    enum Decision: Equatable {
        case unchanged
        /// `key`: `as_of`를 뺀 body(보낸 값 비교용). `asOf`: 초 단위로 내린 epoch ms.
        case enqueue(body: CareSummaryBody, key: [String: JSONValue], asOf: Int64)
    }

    /// owner이고 연결됐는지는 부르는 쪽이 본다. 여기서는 기록·상태만 본다.
    /// 요약에서 `as_of`를 뺀 것이 `lastSentCareSummary`와 다르면 넣는다.
    /// 내용이 같아도 넣는 때: 날이 바뀌었을 때(안 보낸 요약도 새 as_of로 바꾼다), 시계가 뒤로 갔을 때.
    static func decide(_ care: CareFile, _ state: PhoneState, nowMs t: Int64, zone: TimeZone, sending: String?) -> Decision {
        guard care.migratedFeedings,                 // 옮기기 전 수유가 빠진 요약을 보내지 않는다
              state.clearingCareIds == nil else { return .unchanged }   // 지우다 만 기록으로 보내지 않는다
        let asOf = t - ((t % 1000) + 1000) % 1000   // as_of는 초까지 쓴다
        let body = BuildCareSummary()(care, asOf: t, zone: zone)
        // 기록에서 온 값이 없는 요약이고 이 연결에서 요약을 넣은 적도 없으면 보내지 않는다(같은 차의 다른 owner 폰 요약을 빈 요약으로 덮지 않게).
        // '넣은 적'은 maxSentCareAsOf다: 기록 지우기에도 남아(빈 요약으로 차의 옛 요약을 바꾼다) 연결 끊기에서만 사라진다(안드로이드와 같다)
        if state.maxSentCareAsOf == nil, body.isEmpty { return .unchanged }
        guard case .object(var key)? = try? JSONValue(encoding: body) else { return .unchanged }
        key["as_of"] = nil
        // 같은 날이어야 한다: 날이 바뀌면 차는 앞 요약의 '오늘' 값을 빼고, 24시간이 지나면 요약을 쓰지 않는다.
        let sameDay = { (ms: Int64) in CareRules.dayKey(ms, zone) == CareRules.dayKey(asOf, zone) }
        if let p = state.outbox.first(where: { $0.type == MessageType.careSummary && !OutboxRules.tried($0, sending: sending) }) {
            // 안 보낸 요약이 같은 날 같은 내용이면 그대로 둔다: 같은 id로 다시 보내야 응답을 잃어도 중계가 한 번만 처리한다.
            // 날이 바뀌었거나 시계가 뒤로 갔으면 새 as_of로 바꾼다.
            if case .object(var pk) = p.body, case .string(let s)? = pk["as_of"], let pd = Rfc3339.parse(s) {
                pk["as_of"] = nil
                let pAsOf = CareTime.ms(pd)
                // 앞서 보낸 더 늦은 as_of보다 이르면 차가 무시한다: 시각이 따라잡은 지금 as_of로 바꾼다
                // 25시간인 날도 24시간을 넘기면 바꾼다
                if pk == key, pAsOf <= asOf, sameDay(pAsOf), asOf - pAsOf < 86_400_000, pAsOf >= (state.maxSentCareAsOf ?? pAsOf) { return .unchanged }
            }
        } else if state.lastSentCareSummary == .object(key), let last = state.lastSentCareAsOf,
                  last <= asOf, last >= (state.maxSentCareAsOf ?? last), sameDay(last), asOf - last < 86_400_000 {
            // 보낸 것과 같다. 다만 시계가 뒤로 갔으면(지금이 마지막 as_of보다 이르거나, 그 앞에 더 늦은 as_of를 보낸 적이 있으면) 차가 무시했을 수 있어 보낸다.
            return .unchanged
        }
        return .enqueue(body: body, key: key, asOf: asOf)
    }

    /// 같은 초에 또 바뀌어도 그대로 보낸다: 차는 as_of가 같으면 중계 seq가 큰 것을 받는다(careReplaces).
    static func enqueue(_ item: OutboxItem, key: [String: JSONValue], asOf: Int64, sending: String?, in s: inout PhoneState) {
        if let i = s.outbox.firstIndex(where: { $0.type == MessageType.careSummary && !OutboxRules.tried($0, sending: sending) }) {
            // 안 보낸 요약은 하나만(새것이 옛것을 바꾼다). 줄의 자리와 다시 보낼 기다림(429 Retry-After·백오프)은 이어받는다. 보내 본 요약은 두고 그 뒤에 둔다
            let old = s.outbox[i]
            var next = item
            // 실패해 기다리던 것(429로 되돌린 요약)만 기다림을 넘긴다. 보낸 적 없는 항목의 nextTryAt은 만든 시각이라 넘기면 시계가 앞섰을 때 만든 값이 줄을 막는다
            if old.attempts > 0 { next.attempts = old.attempts; next.nextTryAt = max(next.nextTryAt, old.nextTryAt); next.networkWait = old.networkWait }
            s.outbox[i] = next
        } else {
            s.outbox.append(item)
        }
        s.lastSentCareSummary = .object(key)
        s.lastSentCareAsOf = asOf
        s.maxSentCareAsOf = max(s.maxSentCareAsOf ?? asOf, asOf)
    }
}
