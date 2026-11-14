import Foundation

/// 보낼 함(`PhoneState.outbox`)을 고치는 규칙. 보낼 함 칸은 이 파일의 함수로만 고친다.
/// 모두 순수 함수다: 부르는 쪽이 저장소 한 번의 `update` 안에서 다른 칸과 함께 부른다(한 트랜잭션).
enum OutboxRules {
    static func item<B: Encodable>(type: String, body: B, id: String? = nil, songId: String? = nil, now t: Date) -> OutboxItem {
        OutboxItem(id: id ?? ULID.generate(date: t), type: type, body: (try? JSONValue(encoding: body)) ?? .object([:]),
                   ts: Rfc3339.string(t), songId: songId, createdAt: t, nextTryAt: t)
    }

    /// 보내 본 요약인가: 보내기 전에 '보내 봄'을 저장했거나 지금 보내는 중이다(안드로이드 tried와 같다).
    /// 이런 요약은 바꾸지 않고 결과를 확정할 때까지 둔다(새것은 뒤에 하나). 바꿔치우면 늦게 닿은 옛 요약이 더 큰 seq로 새것을 덮는다.
    /// 시도 횟수는 보지 않는다: 429로 중계가 받지 않은 것이 확정된 요약은 '보내 봄'을 걷어(untried) 새것으로 바꿀 수 있게 한다.
    static func tried(_ i: OutboxItem, sending: String?) -> Bool { i.sentOnce || i.id == sending }

    /// 버린 메시지 안내를 앱이 끝나도 잃지 않게 남긴다. 안내가 없는 type은 남기지 않는다.
    static func noteDrop(_ type: String, in s: inout PhoneState) {
        if NoticeRules.dropNotice(type) != nil, !s.unseenDrops.contains(type) { s.unseenDrops.append(type) }
    }

    /// 7일 지난 보낼 함 항목을 지우고, 진행 중으로 보이던 수유·곡에는 만료 표시를 남긴다(같은 저장 안에서).
    /// 버린 항목의 type을 돌려준다(저장한 뒤 안내).
    @discardableResult
    static func expire(before cutoff: Date, except sending: String?, in s: inout PhoneState) -> [String] {
        let old = { (i: OutboxItem) in i.createdAt < cutoff && i.id != sending }   // 보내는 중인 것은 결과를 먼저 적용한다
        // 차에 이미 반영된 수유(feeding_state로 확인)는 실패로 알리지 않는다(안드로이드 feedingDone과 같다)
        let reflected = Set(s.feedingLog.filter { $0.status == .reflected }.map(\.id))
        let expired = s.outbox.filter { old($0) && !($0.type == MessageType.feedingStarted && reflected.contains($0.id)) }.map(\.type)
        for t in expired { noteDrop(t, in: &s) }   // 앱이 끝나도 안내를 잃지 않게
        for item in s.outbox where old(item) {
            switch item.type {
            case MessageType.feedingStarted:
                if let i = s.feedingLog.firstIndex(where: { $0.id == item.id }), s.feedingLog[i].status == .sending {
                    s.feedingLog[i].status = .expired
                }
            case MessageType.songAdded:
                if let id = item.songId, let i = s.songs.firstIndex(where: { $0.id == id }),
                   s.songs[i].state == .uploading || s.songs[i].state == .waiting {
                    s.songs[i].state = .failed("7일이 지나 보내지 못했어요. 다시 녹음해 주세요.")
                }
            default: break
            }
        }
        s.outbox.removeAll(where: old)
        if expired.contains(MessageType.careSummary) { forgetSentSummary(in: &s) }   // 차에 닿지 않았다
        return expired
    }

    /// 곡 지우기 한 벌(지우기·다시 녹음이 함께 쓴다): 표시·선택을 지우고, 아직 못 올린 그 곡의 올리기는 빼고
    /// (지운 곡의 PUT 기다림이 뒤 메시지를 막지 않게, 안드로이드 `removeSong`과 같다) 지움 메시지를 넣는다.
    static func markSongRemoved(_ id: String, removal: OutboxItem, now: Date, in s: inout PhoneState) {
        if let i = s.songs.firstIndex(where: { $0.id == id }) { s.songs[i].state = .removed; s.songs[i].candidate = false; s.songs[i].removedAt = now }
        s.outbox.removeAll { $0.songId == id }
        s.outbox.append(removal)
    }

    /// 거절된 곡은 같은 id로 song_removed를 자동으로 보낸다. 같은 지움이 이미 있으면 넣지 않는다.
    static func enqueueRejectedRemoval(_ songId: String, now: Date, in s: inout PhoneState) {
        let item = item(type: MessageType.songRemoved, body: SongRefBody(songId: songId), now: now)
        if !s.outbox.contains(where: { $0.type == MessageType.songRemoved && $0.body == item.body }) { s.outbox.append(item) }
    }

    /// 전송 결과를 상태에 반영한다. 보낼 함 지우기와 같은 `update` 안에서 부른다.
    static func applySent(_ item: OutboxItem, _ r: SendMessageResponse, now: Date, to s: inout PhoneState) {
        switch item.type {
        case MessageType.feedingStarted:
            if let i = s.feedingLog.firstIndex(where: { $0.id == item.id }) {
                // 중계가 고쳐 저장한 값을 보여 준다.
                if case .string(let at)? = r.body.objectValue?["at"] { s.feedingLog[i].fixedAt = Rfc3339.parse(at) }
                s.feedingLog[i].sentAt = now
                if s.feedingLog[i].status == .sending { s.feedingLog[i].status = .sent }
                // feeding_state가 HTTP 응답보다 먼저 왔을 수 있다. 고친 시각을 저장한 뒤 지금 수유 상태와 다시 비교한다.
                if let last = s.feeding.lastStart, abs((s.feedingLog[i].fixedAt ?? s.feedingLog[i].at).timeIntervalSince(last)) < 1 {
                    s.feedingLog[i].status = .reflected
                }
            }
        case MessageType.songAdded:
            if let id = item.songId, let i = s.songs.firstIndex(where: { $0.id == id }), s.songs[i].state == .uploading {
                s.songs[i].state = .waiting
            }
        default: break
        }
    }

    /// 보낸 결과(.done)를 한 번에: 결과 표시, 보낼 함에서 빼기, 시계가 많이 앞선 요약은 보낸 값으로 치지 않기.
    static func completeSent(_ item: OutboxItem, _ response: SendMessageResponse?, clockSkew: TimeInterval?, now: Date, in s: inout PhoneState) {
        if let response { applySent(item, response, now: now, to: &s) }
        s.outbox.removeAll { $0.id == item.id }
        // 폰 시계가 중계보다 5분 넘게 앞서면 차는 이 요약을 미래 시각이라 버린다. 보낸 값으로 치지 않고 다음 계기에 다시 보낸다.
        if item.type == MessageType.careSummary, let skew = clockSkew, skew < -Retention.summaryAheadLimit { forgetSentSummary(in: &s) }
    }

    /// 버린 결과(.drop)를 한 번에: 실패 표시, 빼기, 요약 표시 되돌림, 버림 안내(보낼 함에 있던 것만).
    static func completeDropped(_ item: OutboxItem, _ error: RelayError?, wasQueued: Bool, in s: inout PhoneState) {
        applyDropped(item, error, to: &s)
        s.outbox.removeAll { $0.id == item.id }
        if item.type == MessageType.careSummary { forgetSentSummary(in: &s) }   // 차에 닿지 못했다(안드로이드 afterSend와 같다)
        if wasQueued { noteDrop(item.type, in: &s) }   // 안내를 보기 전에 끝나도 잃지 않게
    }

    /// 다시 보낼 결과(.retry)를 한 번에: 시도 횟수, 기다림, 기다림 이유. 429인 요약은 '보내 봄'을 걷는다.
    static func scheduleRetry(_ id: String, delay: TimeInterval, network: Bool, untry: Bool, now: Date, in s: inout PhoneState) {
        guard let i = s.outbox.firstIndex(where: { $0.id == id }) else { return }
        s.outbox[i].attempts += 1; s.outbox[i].nextTryAt = max(s.outbox[i].nextTryAt, now.addingTimeInterval(delay)); s.outbox[i].networkWait = network
        if untry { untried(id, in: &s) }
    }

    /// 파일부터 다시 올린다(.reupload, 422 song_blob_missing).
    static func scheduleReupload(_ id: String, now: Date, in s: inout PhoneState) {
        guard let i = s.outbox.firstIndex(where: { $0.id == id }) else { return }
        s.outbox[i].markUploaded(false); s.outbox[i].attempts += 1
        s.outbox[i].nextTryAt = now.addingTimeInterval(RetryPolicy.outboxBackoff(attempts: s.outbox[i].attempts))
    }

    /// 버려지는 전송의 실패 표시. 보낼 함 지우기와 같은 `update` 안에서 부른다.
    static func applyDropped(_ item: OutboxItem, _ error: RelayError?, to s: inout PhoneState) {
        // 수유 기록은 실패 표시를 남긴다(보내지 않는 기록에 '보내는 중'이 남지 않게).
        if item.type == MessageType.feedingStarted {
            if let i = s.feedingLog.firstIndex(where: { $0.id == item.id }), s.feedingLog[i].status == .sending {
                s.feedingLog[i].status = .failed
            }
            return
        }
        guard item.type == MessageType.songAdded, let id = item.songId else { return }
        let text: String
        switch error?.status {
        case 413?: text = "파일이 너무 커요. 3분 안으로 다시 녹음해 주세요."
        case 415?: text = "녹음 파일을 읽지 못했어요. 다시 녹음해 주세요."
        default: text = "노래를 보내지 못했어요. 다시 녹음해 주세요."
        }
        if let i = s.songs.firstIndex(where: { $0.id == id }), s.songs[i].state != .removed { s.songs[i].state = .failed(text) }
    }

    /// 차에 닿지 못한 요약(버림·만료·차가 미래로 버림): 내용이 같아도 다음 계기에 다시 보낸다. 보낼 함에 요약이 남았으면
    /// 표시는 그것을 가리키므로 둔다(안드로이드 afterSend와 같다). 항목을 뺀 뒤에 부른다.
    static func forgetSentSummary(in s: inout PhoneState) {
        if !s.outbox.contains(where: { $0.type == MessageType.careSummary }) { s.lastSentCareSummary = nil }
    }

    /// '보내 봄'을 걷는다(안드로이드 untried와 같다): 429로 중계가 받지 않은 것이 확정된 요약. 그사이 이 표시 때문에 남긴 안 보낸 요약을 정리한다:
    /// 기록 지우기 중이면 모두 빼고(빈 요약은 지우기를 마칠 때 들어간다), 아니면 가장 뒤의 하나만 둔다. 메시지 429 기다림은 기기 기다림이 지킨다.
    static func untried(_ id: String, in s: inout PhoneState) {
        guard let i = s.outbox.firstIndex(where: { $0.id == id }) else { return }
        s.outbox[i].markTried(false)
        let unsent = s.outbox.filter { $0.type == MessageType.careSummary && !$0.sentOnce }.map(\.id)
        let keep = s.clearingCareIds != nil ? [] : Array(unsent.suffix(1))
        s.outbox.removeAll { unsent.contains($0.id) && !keep.contains($0.id) }
        if !s.outbox.contains(where: { $0.type == MessageType.careSummary }) { s.lastSentCareSummary = nil }
    }

    /// 망 실패(응답 없음)로 생긴 기다림이 남은 항목이 있는가.
    static func hasNetworkWait(_ s: PhoneState, now t: Date) -> Bool {
        s.outbox.contains(where: { $0.networkWait && $0.nextTryAt > t })
    }

    /// 망 실패로 생긴 기다림을 지금으로 당긴다('네트워크가 돌아올 때, 앱이 앞으로 올 때').
    /// 중계가 준 기다림(429 Retry-After·song_quota)과 5xx 백오프는 둔다.
    static func hurryNetworkWaits(now t: Date, in s: inout PhoneState) {
        for i in s.outbox.indices where s.outbox[i].networkWait && s.outbox[i].nextTryAt > t { s.outbox[i].nextTryAt = t }
    }

    /// '이 휴대폰의 기록 지우기'의 보낼 함: 녹음 파일이 필요한 노래 올리기만 뺀다(삭제·선택 요청은 파일이 필요 없어 남긴다).
    /// 지울 기록의 안 보낸 요약은 보내지 않는다. 보내 본 요약은 결과를 확정할 때까지 둔다(빈 요약이 뒤에 가서 이긴다).
    static func clearForRecordsWipe(sending: String?, in s: inout PhoneState) {
        s.outbox.removeAll { $0.songId != nil }
        s.outbox.removeAll { $0.type == MessageType.careSummary && !tried($0, sending: sending) }
    }
}
