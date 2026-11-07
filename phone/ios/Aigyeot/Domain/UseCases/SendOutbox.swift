import Foundation

/// 보낼 함을 넣은 순서대로 하나씩 보낸다(계약 7.5). 보낼 함 칸은 OutboxRules로만 고치고, 보내기 일꾼은 이 하나다.
@MainActor
final class SendOutbox {
    private let state: PhoneStateRepository
    private let relay: RelayClient
    private let link: LinkObserver
    private let notices: NoticeBoard
    private let tasks: TaskBag
    private let visibility: AppVisibility
    private let upload: UploadSong
    private let now: () -> Date
    /// 보내기 직전의 요약 다시 계산(ReconcileCareSummary.queue). 그쪽이 이 클래스의 `sendingId`·`kick`을 써서 합성 루트가 잇는다.
    private let queueSummary: () -> ReconcileCareSummary.Queued
    /// 같은 연결의 401(Lifecycle.handleUnauthorized).
    private let lose: () -> Void
    /// 시험에서 끈다. 켜 두면 보낼 함에 넣을 때마다 바로 보낸다.
    var autoFlush = true
    private var flushing = false
    /// 지금 보내는 보낼 함 항목 id. 만료 정리가 결과를 기다리게 한다(보내는 사이 7일을 넘겨도 성공을 실패로 적지 않게, 안드로이드 `sending`).
    private(set) var sendingId: String?
    private var retryTask: Task<Void, Never>?
    /// 메시지 429(기기당 한도, 1.4)의 기다림. 저장하지 못한 429 기다림도 메모리로 지킨다. 상태 요청도 이것을 본다.
    private var holdUntil: Date?

    init(state: PhoneStateRepository, relay: RelayClient, link: LinkObserver, notices: NoticeBoard, tasks: TaskBag,
         visibility: AppVisibility, upload: UploadSong, now: @escaping () -> Date,
         queueSummary: @escaping () -> ReconcileCareSummary.Queued, lose: @escaping () -> Void) {
        self.state = state
        self.relay = relay
        self.link = link
        self.notices = notices
        self.tasks = tasks
        self.visibility = visibility
        self.upload = upload
        self.now = now
        self.queueSummary = queueSummary
        self.lose = lose
    }

    func forget(_ e: LifecycleEvent) {
        switch e {
        case .connectionChanged:
            retryTask?.cancel()
            flushing = false; holdUntil = nil
            sendingId = nil   // 옛 연결의 보내는 중 표시가 새 보낼 함의 '보내 봄' 판정(U57)에 남지 않게
        case .background:
            retryTask?.cancel(); retryTask = nil   // 뒤에서는 다시 시도하지 않는다. 남은 것은 앞으로 올 때 한다
        case .connectionLeft, .recordsCleared: break
        }
    }

    /// 메시지 429 기다림 중인가.
    var isHeld: Bool { holdUntil.map { $0 > now() } ?? false }
    func holdMessages(for delay: TimeInterval) { holdUntil = max(holdUntil ?? .distantPast, now().addingTimeInterval(delay)) }

    /// 넣은 것을 바로 보낸다(시험에서는 끈다).
    func kick() {
        if autoFlush { tasks.spawn { [self] in await run() } }
    }

    /// 뒤에서는 다시 시도하지 않는다(잠금 화면 끝내기의 한 번 보내기는 된다). 앞으로 오면 갱신이 보낸다.
    func schedule(at date: Date) {
        retryTask?.cancel()
        guard visibility.active else { return }
        let delay = max(RetryPolicy.minFlushDelay, date.timeIntervalSince(now()))
        retryTask = Task { [weak self] in   // 기다리는 동안 붙들지 않는다
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            await self.run()
        }
    }

    /// 결과·만료·'보내 봄'을 저장하지 못했다(StoreFailure.worker): 항목을 두고 잠시 뒤 다시 보낸다.
    private func retrySaveLater() { schedule(at: now().addingTimeInterval(RetryPolicy.saveRetry)) }

    /// 망 실패(응답 없음)로 생긴 보낼 함 기다림을 지금으로 당긴다(계약 7.5 '네트워크가 돌아올 때, 앱이 앞으로 올 때').
    /// 중계가 준 기다림(429 Retry-After·song_quota)과 5xx 백오프는 둔다. 메시지 429 기기 기다림은 보내기가 따로 지킨다.
    func hurryNetworkWaits() {
        let t = now()
        guard OutboxRules.hasNetworkWait(state.value, now: t) else { return }
        state.update { OutboxRules.hurryNetworkWaits(now: t, in: &$0) }
    }

    /// 7일 지난 보낼 함 항목을 정리해 저장하고 버린 것을 알린다(U54). 저장하지 못하면 false.
    private func expireNow() -> Bool {
        let cutoff = now().addingTimeInterval(-OutboxPolicy.maxAge)
        var expired: [String] = []
        let sending = sendingId
        guard state.update({ expired = OutboxRules.expire(before: cutoff, except: sending, in: &$0) }) else { return false }
        notices.addDrops(expired)
        return true
    }

    func run() async {
        guard state.isPaired, !flushing else { return }
        let gen = state.generation
        flushing = true
        defer { if gen == state.generation { flushing = false } }
        // 만료 정리를 저장하지 못하면 만료된 항목이 메모리에 남아 전송될 수 있다. 보내지 않고 나중에 다시 한다.
        guard expireNow() else { retrySaveLater(); return }
        var refreshedId: String?
        while let item = state.value.outbox.first {
            if let hold = holdUntil, hold > now() { schedule(at: hold); return }   // 저장하지 못한 429 기다림
            if item.nextTryAt > now() { schedule(at: item.nextTryAt); return }   // 백오프·Retry-After(노래 PUT은 한 시간도 온다)
            // 앞 항목을 기다리는 사이 7일이 지났을 수 있다. 보내기 직전에 다시 보고 만료면 정리·저장 뒤 다음으로(계약 7.5).
            if item.createdAt < now().addingTimeInterval(-OutboxPolicy.maxAge) {
                guard expireNow() else { retrySaveLater(); return }
                continue
            }
            if item.type == MessageType.careSummary, refreshedId != item.id {
                // 보내기 직전에 다시 계산한다: 오래 기다린 안 보낸 요약의 as_of·'오늘' 값이 낡지 않게(복구가 자정을 넘긴 경우 등).
                // 보내 본 요약은 그대로 같은 id로 보내고, 달라졌으면 새것을 그 뒤에 둔다(U57. 차는 날짜 경계 규칙으로 앞 요약의 '오늘'을 뺀다).
                // 같은 날 같은 내용이면 그대로(같은 id로 다시 보내야 중계가 한 번만 처리한다). 바꿨으면 줄의 자리는 이어받는다(바뀌는 것은 안 보낸 요약뿐이다, U57).
                switch queueSummary() {
                case .queued: refreshedId = state.value.outbox.first?.id; continue
                case .failed: retrySaveLater(); return   // 지운 기록이 담긴 옛 요약을 보내지 않는다
                case .unchanged: refreshedId = item.id
                }
            }
            // 요약은 보내기 전에 '보내 봄'을 저장한다(U57): 응답 전에 죽거나 결과 저장이 실패해도 바꿔치우지 않는다
            if item.type == MessageType.careSummary, !item.sentOnce {
                guard state.update({ st in if let i = st.outbox.firstIndex(where: { $0.id == item.id }) { st.outbox[i].markTried(true) } }) else { retrySaveLater(); return }
            }
            sendingId = item.id
            let result = await attempt(item, gen: gen)
            if gen == state.generation { sendingId = nil }
            guard let (outcome, error, response, mark, posting) = result, gen == state.generation else {
                // 같은 연결에서 취소로 끝났다(새로고침을 띄운 화면이 사라짐 등): 항목은 그대로 두고 곧 다시 보낸다. 뒤에서는 schedule이 막고 앞으로 올 때 보낸다
                if result == nil, gen == state.generation { schedule(at: now().addingTimeInterval(RetryPolicy.cancelledRetry)) }
                return
            }
            // 통신 결과(결과 저장 실패와 따로). 4xx 거절도 중계에 닿은 것이다. 보내기 전에 버린 것(파일 없음 등)은 HTTP가 없어 보지 않는다
            if case .drop = outcome, error == nil {} else { link.observe(error, since: mark) }
            let t = now()
            switch outcome {
            case .done:
                // 결과 표시와 보낼 함 지우기를 한 번에 저장한다. 저장하지 못하면 항목을 남기고 멈춘다.
                // 잠시 뒤 다시 보내도 같은 id라 한 번만 처리된다(계약 1.4).
                let skew = link.clockSkew
                guard state.update({ OutboxRules.completeSent(item, response, clockSkew: skew, now: t, in: &$0) }) else { retrySaveLater(); return }
            case .drop:
                // 보내는 사이 보낼 함에서 빠진 항목(기록 지우기의 노래 등)의 거절은 알리지 않는다(안드로이드 wasQueued). 요약은 U57로 빠지지 않는다
                let wasQueued = state.value.outbox.contains { $0.id == item.id }
                guard state.update({ OutboxRules.completeDropped(item, error, wasQueued: wasQueued, in: &$0) }) else { retrySaveLater(); return }
                if wasQueued, let text = NoticeRules.dropNotice(item.type) { notices.add(text) }   // 떠 있는 안내를 덮지 않는다(U54)
            case .reupload:
                guard state.update({ OutboxRules.scheduleReupload(item.id, now: t, in: &$0) }) else { retrySaveLater(); return }
            case .retry(let delay):
                // 429는 기기당 메시지 한도다(1.4): 이 항목이 바뀌거나 지워져도 다른 메시지·상태 요청도 기다린다
                if error?.status == 429, posting { holdMessages(for: delay) }
                // 보내는 중인 요약은 바뀌지 않는다(U57). 보내는 사이 지운 항목(기록 지우기의 노래 등)의 기다림은 남은 줄을 막지 않는다.
                // 메시지 429는 위 기기 기다림이 지킨다
                let id = item.id
                guard state.value.outbox.contains(where: { $0.id == id }) else { continue }
                // 429인 요약은 중계에 없다(같은 id를 한도 검사보다 먼저 본다): 보내 보지 않은 것으로 되돌려 새 요약으로 바꿀 수 있게 한다(U57, 안드로이드 untried)
                let untry = error?.status == 429 && item.type == MessageType.careSummary
                guard state.update({ OutboxRules.scheduleRetry(id, delay: delay, network: error == .network, untry: untry, now: t, in: &$0) }) else {
                    // 저장은 못 해도 메시지 Retry-After는 지킨다. 노래 PUT 한도는 메시지와 따로라(1.4) 기기 전체 기다림은 걸지 않는다(잠시 뒤 다시 올려 다시 받는다).
                    // 다만 맨 앞 항목의 기다림은 넣은 순서대로 줄을 막는다(7.5, 보고서 계약 결정 항목)
                    if posting { holdMessages(for: delay) }
                    retrySaveLater(); return
                }
                schedule(at: now().addingTimeInterval(delay))
                return
            case .unauthorized:
                lose()
                return
            }
        }
    }

    /// 연결이 바뀌었으면(세대가 다르면) nil. 이전 차로 가던 요청의 결과는 새 연결에 적용하지 않는다.
    /// 넷째 값은 결과를 낸 HTTP 요청을 시작할 때의 관찰 번호다(노래는 올리기·보내기가 따로 잡는다, 띠 판단용).
    /// 마지막 값: 메시지 POST까지 갔는가(노래 PUT의 429는 시간당 업로드 한도라 메시지 한도와 따로다, 1.4).
    private func attempt(_ item: OutboxItem, gen: Int) async -> (SendOutcome, RelayError?, SendMessageResponse?, LinkObserver.Mark, Bool)? {
        var mark = link.mark()
        var posting = false
        do {
            switch try await upload.run(item, gen: gen, mark: mark) {
            case .notNeeded, .uploaded: break
            case .missingFile: return (.drop, nil, nil, mark, posting)
            case .removedMeanwhile: return (.done, nil, nil, mark, posting)
            case .stale: return nil
            }
            // 올리는 사이 다른 메시지가 429를 받았으면 기기 기다림을 지킨다(1.4 '다른 메시지도 기다린다', 안드로이드와 같다).
            // 결과가 아니므로(nil) 시도 횟수를 늘리지 않고, 다음 보내기가 기다림 뒤 POST만 한다(파일은 올렸다)
            if item.songId != nil, !item.uploaded, isHeld { return nil }
            let env = Envelope(id: item.id, type: item.type, ts: item.ts, body: item.body)
            mark = link.mark()
            posting = true
            let response = try await relay.send(env)
            guard gen == state.generation else { return nil }
            return (.done, nil, response, mark, posting)
        } catch let e as RelayError {
            // 취소(뒤로 감·새로고침 화면이 사라짐)는 결과가 아니다: 항목·시도 횟수·띠를 그대로 둔다. 보내기가 곧 같은 id로 다시 보낸다
            guard gen == state.generation, e != .cancelled else { return nil }
            return (OutboxPolicy.classify(e, attempts: item.attempts), e, nil, mark, posting)
        } catch {
            return gen == state.generation ? (.drop, nil, nil, mark, posting) : nil
        }
    }
}

/// 노래 보내기의 첫 단계: 파일 PUT(7.5 두 단계). 성공(201·409 `already_exists`)해야 `song_added`로 넘어간다.
@MainActor
final class UploadSong {
    private let state: PhoneStateRepository
    private let relay: RelayClient
    private let link: LinkObserver
    private let songFiles: SongFileStore

    init(state: PhoneStateRepository, relay: RelayClient, link: LinkObserver, songFiles: SongFileStore) {
        self.state = state
        self.relay = relay
        self.link = link
        self.songFiles = songFiles
    }

    enum Outcome: Equatable {
        /// 노래가 아니거나 이미 올렸다.
        case notNeeded
        case uploaded
        /// 보내기 전에 버린다(HTTP 없음).
        case missingFile
        /// 업로드 사이 기록을 지웠다: 보내지 않고 끝난 것으로 친다.
        case removedMeanwhile
        /// 연결이 바뀌었다.
        case stale
    }

    func run(_ item: OutboxItem, gen: Int, mark: LinkObserver.Mark) async throws -> Outcome {
        guard let songId = item.songId, !item.uploaded else { return .notNeeded }
        guard songFiles.exists(songId) else { return .missingFile }
        try await relay.putSong(id: songId, file: songFiles.url(for: songId))
        guard gen == state.generation else { return .stale }
        link.observe(nil, since: mark)   // 올리기도 중계에 닿은 HTTP다
        guard state.value.outbox.contains(where: { $0.id == item.id }) else { return .removedMeanwhile }   // 업로드 사이 기록을 지웠으면 보내지 않는다
        state.update { st in if let i = st.outbox.firstIndex(where: { $0.id == item.id }) { st.outbox[i].markUploaded(true) } }
        return .uploaded
    }
}
