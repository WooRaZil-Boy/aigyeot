import Foundation

/// 받은 함(GET /v1/inbox)·소켓 봉투를 저장한 뒤 ack한다(안드로이드 SyncInbox와 같은 자리).
/// 언제 받을지(소켓·갱신 순서·다시 하기)는 KeepConnected가 정한다. 여기는 받은 것을 저장하는 일과 '상태 요청 필요' 표시만 한다.
@MainActor
final class SyncInbox {
    private let state: PhoneStateRepository
    private let relay: RelayClient
    private let gate: RelayGate
    private let songFiles: SongFileStore
    private let outbox: SendOutbox
    private let notices: Notices
    private let now: () -> Date
    /// 상태 파일 복구 표시를 저장하지 못했다. 저장될 때까지 메시지 적용을 미룬다.
    private var pendingRecovery = false
    /// 차의 실시간 봉투를 적용한 횟수와 그 시각. 늦게 온 기기 목록이 이 관찰을 덮지 않게 한다(KeepConnected.refreshDevices가 본다).
    private(set) var carLiveSeen = 0
    private(set) var lastCarLiveAt: Date?

    init(state: PhoneStateRepository, relay: RelayClient, gate: RelayGate, songFiles: SongFileStore, outbox: SendOutbox,
         notices: Notices, now: @escaping () -> Date) {
        self.state = state
        self.relay = relay
        self.gate = gate
        self.songFiles = songFiles
        self.outbox = outbox
        self.notices = notices
        self.now = now
    }

    /// 수명 표(AppLifecycle.handle)의 이 원본 칸.
    func forget(_ e: LifecycleEvent) {
        if e == .connectionChanged { pendingRecovery = false }
    }

    /// 앞에 있을 때 온 푸시(`willPresent`)의 입구: 띄운 적 없는 id면 띄운 것을 적고 배너를 띄운다. 글은 배너가 그릴 때 정한다.
    /// 끊은 뒤 늦게 온 이전 연결의 푸시는 띄우지 않는다.
    func onPush(_ messageId: String) {
        guard let c = state.creds, state.state.shown[messageId] == nil else { return }
        let t = now()
        if state.commit(c, { $0.shown[messageId] = t }) { notices.showBanner(messageId) }
    }

    /// 저장 파일이 깨져 빈 상태로 복구했는데 자격은 남아 있으면, 이미 ack한 수유·곡 상태가 받은 함에 없다.
    /// 요청 필요 표시를 켜 두면 기기 목록 조회가 성공한 뒤 state_request로 다시 받는다.
    /// 저장이 거부된 실행은 건드리지 않는다. 원본이 그대로라 다음 정상 실행이 그것을 읽는다.
    /// 이 표시의 저장이 실패하면 기억해 두고, 저장될 때까지 메시지 적용을 미룬다(markStaleIfNeeded가 다시 한다).
    /// 자격만 남고 상태에 차가 없는 경우(앱 재설치 뒤 Keychain만 남음, 자격 저장 직후 상태 저장 전에 종료)도 같다.
    func markRecoveryAtLaunch() {
        guard state.restoreFailed || state.state.carId == nil, !state.writesRefused, let c = state.creds, c.role == .owner else { return }
        // 기준 시각도 채워, 첫 요청이 중계에서 만료돼도 24시간 재요청이 걸리게 한다(안드로이드 paired와 같다).
        let t = now()
        pendingRecovery = !state.commit(c) { $0.carId = c.carId; $0.needsStateRequest = true; $0.lastReceivedAt = $0.lastReceivedAt ?? t }
    }

    /// 마지막으로 차의 메시지를 받은 지 24시간이 넘었으면 '상태 요청 필요'를 켠다. 표시는 201로만 끈다.
    /// 표시가 필요 없거나 저장에 성공하면 true. 저장에 실패하면 false(호출한 쪽은 메시지 적용을 미룬다).
    func markStaleIfNeeded() -> Bool {
        if pendingRecovery, let c = state.creds {
            let t = now()
            guard state.commit(c, { $0.carId = c.carId; $0.needsStateRequest = true; $0.lastReceivedAt = $0.lastReceivedAt ?? t }) else { return false }
            pendingRecovery = false
        }
        guard InboxRules.needsStaleMark(state.state, now: now()) else { return true }
        return state.commit(state.creds) { $0.needsStateRequest = true }
    }

    /// 받은 함 한 번 다 받기의 결과. `mark`는 마지막 쪽 요청을 시작할 때의 관찰 번호다(띠 판단은 KeepConnected가 한다).
    struct Sync {
        var error: RelayError?
        /// 받은 것을 모두 저장했는가(저장에 실패해 멈췄으면 거짓).
        var saved: Bool
        var mark: LinkMark
    }

    /// 받은 함을 쪽마다 받아 저장한다. `current`가 거짓이 되면(연결이 바뀌거나 뒤로 감) 멈추고 nil.
    func sync(_ c: Credentials, current: () -> Bool) async -> Sync? {
        var result = Sync(error: nil, saved: true, mark: gate.mark())
        do {
            var after: Int64?
            var more = true
            while more {
                result.mark = gate.mark()   // 요청마다 잡는다: 앞 쪽을 처리하는 사이의 관찰보다 뒤에 시작한 요청의 결과는 새것이다
                let page = try await relay.inbox(token: c.token, car: c.carId, afterSeq: after)
                guard current() else { return nil }
                result.saved = await store(c, page.messages, live: false)
                guard current() else { return nil }
                if !result.saved { break }   // 실패한 메시지보다 뒤의 쪽을 먼저 적용하지 않는다
                after = page.messages.last?.seq
                more = page.more && after != nil
            }
        } catch let e as RelayError {
            result.error = e
        } catch {}
        return result
    }

    /// 받은 메시지를 `seq` 오름차순으로 하나씩 처리한다. 본문과 '처리한 id'를 한 번에 저장하고, 저장이 성공한 것만 ack한다.
    /// 저장하지 못하면 그 메시지부터 ack하지 않는다(중계가 다시 준다).
    /// `live`는 처음 받은 함을 처리한 뒤 WebSocket으로 받은 것이다. 받은 함에서 꺼낸 것은 배너 없이 목록에만 넣는다.
    /// 모두 저장했거나 저장할 것이 없으면 true, 저장에 실패해 멈췄으면 false(호출한 쪽이 다시 받게 한다).
    /// `onSaveFailed`: 저장에 실패한 바로 그때(ack를 기다리기 전) 부른다. 실시간이면 앞 것의 ack를 기다리는 사이 새 실시간 메시지가 먼저 적용되지 않게 막는다.
    @discardableResult
    func store(_ c: Credentials?, _ envelopes: [Envelope], live: Bool, onSaveFailed: (() -> Void)? = nil) async -> Bool {
        guard let creds = c, state.isCurrent(creds) else { return true }
        guard markStaleIfNeeded() else { return false }   // 재요청 표시를 저장하기 전에는 적용도 ack도 하지 않는다(중계가 다시 준다)
        var ackIds: [String] = []
        var allSaved = true
        for env in envelopes.sorted(by: { ($0.seq ?? 0) < ($1.seq ?? 0) }) {
            // 다른 차(이전 연결)의 메시지는 적용도 ack도 하지 않는다. seq는 같은 차 안에서만 비교한다.
            guard env.car == creds.carId else { continue }
            if state.state.processed[env.id] != nil || env.v != 1 {
                ackIds.append(env.id)   // 이미 처리한 id, 모르는 판은 ack만 해서 받은 함이 막히지 않게 한다
                continue
            }
            var effects = InboxRules.Effects()
            let inbound = env.inbound   // 봉투 → 받은 메시지(EnvelopeMapping)
            let t = now()
            let saved = state.commit(creds) { effects = InboxRules.receive(inbound, live: live, myDevice: creds.deviceId, now: t, in: &$0) }
            guard saved else { allSaved = false; break }
            if live, env.from?.role == .car { carLiveSeen += 1; lastCarLiveAt = now() }   // 늦게 온 기기 목록이 이 관찰을 덮지 않게
            ackIds.append(env.id)
            if let id = effects.banner { notices.showBanner(id) }
            effects.removeFiles.forEach(songFiles.delete)
            if effects.outboxAdded { outbox.kick() }   // 거절된 곡의 자동 삭제 같은, 받은 결과로 생긴 전송을 바로 시작한다
        }
        if !allSaved { onSaveFailed?() }
        guard !ackIds.isEmpty else { return allSaved }
        // 못 보내도 다시 받으면 중복으로 ack만 한다
        do {
            try await gate.observed { try await relay.ack(token: creds.token, ids: ackIds) }
        } catch let e as RelayError {
            gate.unauthorized(e, c: creds)
        } catch {}
        return allSaved || !state.isCurrent(creds)   // ack를 기다리는 동안 연결이 바뀌었으면 이 연결이 다시 받을 것이 없다
    }
}
