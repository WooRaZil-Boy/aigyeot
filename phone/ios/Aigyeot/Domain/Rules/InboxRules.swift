import Foundation

/// 받은 메시지를 상태에 적용하는 규칙. 기기 목록·수유 상태·곡 상태·리포트·인계 알림 칸은 이 파일의 함수로만 고친다.
enum InboxRules {
    /// 저장한 뒤 할 일. 적용과 같은 저장 안에서 정하고, 저장이 성공한 뒤에만 한다.
    struct Effects {
        var banner: String?
        var outboxAdded = false
        var removeFiles: [String] = []
    }

    /// 봉투 하나를 적용하고 '처리한 id'·마지막 수신 시각을 같은 저장 안에서 남긴다.
    /// 받은 함에서 꺼낸 지난 봉투(`live` 거짓)는 차 타일을 바꾸지 않는다.
    static func receive(_ m: Inbound, live: Bool, myDevice: String, now: Date, in s: inout PhoneState) -> Effects {
        let before = s.outbox.count
        var effects = apply(m, to: &s, live: live, myDevice: myDevice, now: now)
        effects.outboxAdded = s.outbox.count > before
        s.processed[m.id] = now
        if m.fromCar { s.lastReceivedAt = now; if live { markCarSeen(&s, at: now) } }
        return effects
    }

    static func apply(_ m: Inbound, to s: inout PhoneState, live: Bool, myDevice: String, now: Date) -> Effects {
        var fx = Effects()
        let seq = m.seq
        switch m.message {
        case .paired(let b):
            // 그 기기의 마지막 적용 seq보다 큰 것만 적용한다. 끊김 표시가 더 새로우면 늦게 온 paired는 무시된다.
            guard seq > max(s.deviceMarks[b.deviceId]?.seq ?? 0, s.devicesSnapshotSeq) else { break }   // 스냅숏 이하 seq는 이미 목록에 반영돼 있다
            s.deviceMarks[b.deviceId] = DeviceMark(seq: seq, removed: false, updatedAt: now)
            let info = DeviceInfo(deviceId: b.deviceId, role: Role(rawValue: b.role.rawValue) ?? .owner,
                                  platform: Platform(rawValue: b.platform.rawValue) ?? .android,
                                  pairedAt: b.pairedAt, lastSeenAt: nil, online: false, handoffAlerts: b.handoffAlerts)
            if let i = s.devices.firstIndex(where: { $0.deviceId == b.deviceId }) {
                var merged = info; merged.lastSeenAt = s.devices[i].lastSeenAt; merged.online = s.devices[i].online
                s.devices[i] = merged
            } else { s.devices.append(info) }
            if b.deviceId == myDevice { s.handoffAlerts = b.handoffAlerts }
        case .unpaired(let b):
            guard seq > max(s.deviceMarks[b.deviceId]?.seq ?? 0, s.devicesSnapshotSeq) else { break }
            s.deviceMarks[b.deviceId] = DeviceMark(seq: seq, removed: true, updatedAt: now)
            s.devices.removeAll { $0.deviceId == b.deviceId }
        case .feedingState(let b):
            // 필수 필드가 빠진 body는 읽기(매핑)에서 무시된다(수유 값을 지우지 않는다). 큰 seq가 이긴다.
            guard seq > s.feeding.seq else { break }
            let last = b.lastFeedingStart.flatMap(Rfc3339.parse)
            s.feeding = FeedingState(lastStart: last, windowStartExpected: b.windowStartExpected.flatMap(Rfc3339.parse), seq: seq)
            if let last {
                for i in s.feedingLog.indices where s.feedingLog[i].status != .reflected {
                    let sent = s.feedingLog[i].fixedAt ?? s.feedingLog[i].at
                    if abs(sent.timeIntervalSince(last)) < 1 { s.feedingLog[i].status = .reflected }   // "차에 반영됨"
                }
            }
        case .songStatus(let b):
            fx.removeFiles += applySongStatus(b, seq: seq, now: now, to: &s)
        case .report(let b):
            if let i = s.reports.firstIndex(where: { $0.body.sessionId == b.sessionId }) {
                guard seq > s.reports[i].seq else { break }   // session_id마다 큰 seq가 이긴다
                s.reports.remove(at: i)
            }
            s.reports.append(StoredReport(messageId: m.id, seq: seq, receivedAt: now, body: b))
            s.reports = Rfc3339.sortedDescending(s.reports) { $0.body.startedAt }
        case .handoffAlert(let b):
            // 본문은 늘 저장한다. 배너는 한 번만, 그리고 방금 생긴 알림에만 띄운다.
            if !s.alerts.contains(where: { $0.id == m.id }) {
                s.alerts.append(StoredAlert(id: m.id, seq: seq, receivedAt: now, body: b))
                s.alerts = Rfc3339.sortedDescending(s.alerts) { $0.body.occurredAt }
            }
            if s.shown[m.id] == nil {
                s.shown[m.id] = now
                let age = Rfc3339.parse(b.occurredAt).map { now.timeIntervalSince($0) } ?? 0
                if live, age <= Retention.bannerWindow { fx.banner = m.id }
            }
        case .ignored:
            break
        }
        return fx
    }

    /// 지워야 할 파일의 곡 id를 돌려준다.
    static func applySongStatus(_ b: SongStatusBody, seq: Int64, now: Date, to s: inout PhoneState) -> [String] {
        var files: [String] = []
        if let i = s.songs.firstIndex(where: { $0.id == b.songId }) {
            if s.songs[i].state == .removed && b.status != .removed { return [] }   // 지운 곡은 되살리지 않는다
            guard seq > s.songs[i].lastSeq else { return [] }
            s.songs[i].lastSeq = seq
            s.songs[i].candidate = b.candidate
            if let n = b.name, s.songs[i].name.isEmpty { s.songs[i].name = n }
            switch b.status {
            case .accepted:
                s.songs[i].state = .accepted
                s.songs[i].durationS = b.durationS ?? s.songs[i].durationS
            case .rejected:
                s.songs[i].state = .rejected(b.reason ?? .badFormat)
                s.songs[i].candidate = false
            case .removed:
                if s.songs[i].state != .removed { s.songs[i].removedAt = now }
                s.songs[i].state = .removed
                s.songs[i].candidate = false
                files.append(b.songId)
            }
        } else {
            // 다른 owner 폰이 올린 곡도 목록에 보인다(`song_status.name`). 지움이면 순번 비교용 표시만 남긴다.
            var row = SongRow(id: b.songId, name: b.name ?? "지운 노래", durationS: b.durationS,
                              state: .removed, candidate: b.candidate, lastSeq: seq, addedAt: now, removedAt: now)
            switch b.status {
            case .accepted: row.state = .accepted; row.removedAt = nil
            case .rejected: row.state = .rejected(b.reason ?? .badFormat); row.candidate = false; row.removedAt = nil
            case .removed: row.candidate = false
            }
            s.songs.append(row)
        }
        // 거절된 곡은 목록에 잠깐 보이고 같은 id로 song_removed를 자동으로 보낸다. 같은 저장 안에서 보낼 함에 넣는다.
        if b.status == .rejected, let row = s.songs.first(where: { $0.id == b.songId }), case .rejected = row.state {
            OutboxRules.enqueueRejectedRemoval(b.songId, now: now, in: &s)
        }
        return files
    }

    /// 차가 보낸 봉투를 받았으면 차는 지금 켜져 있다. 기기 목록(60초 간격)을 기다리지 않고 차 타일을 바로 맞춘다.
    static func markCarSeen(_ s: inout PhoneState, at t: Date) {
        for i in s.devices.indices where s.devices[i].role == .car {
            s.devices[i].online = true
            s.devices[i].lastSeenAt = Rfc3339.string(t)
        }
    }

    /// 기기 목록을 합친다. 목록을 읽은 뒤(`snapshot_seq`보다 새로) 소켓으로 받은 연결 변경은 목록이 덮지 않는다.
    static func mergeDevices(local: [DeviceInfo], snapshot r: DevicesResponse, marks: [String: DeviceMark]) -> [DeviceInfo] {
        var result: [DeviceInfo] = []
        for d in r.devices {
            if let m = marks[d.deviceId], m.seq > r.snapshotSeq {
                if m.removed { continue }                                    // 스냅숏 뒤에 끊겼다
                if let mine = local.first(where: { $0.deviceId == d.deviceId }) { result.append(mine); continue }
            }
            result.append(d)
        }
        for l in local where !result.contains(where: { $0.deviceId == l.deviceId }) {
            if let m = marks[l.deviceId], m.seq > r.snapshotSeq, !m.removed { result.append(l) }   // 스냅숏 뒤에 붙었다
        }
        return result
    }

    /// 목록과 `snapshot_seq`를 한 번에 저장한다. 목록을 기다리는 사이 차의 실시간 봉투를 받았으면(`carSeenAt`) 차 행의 연결 상태는 그쪽이 새것이다.
    static func applyDevices(_ r: DevicesResponse, carSeenAt: Date?, in s: inout PhoneState) {
        var merged = mergeDevices(local: s.devices, snapshot: r, marks: s.deviceMarks)
        if let seen = carSeenAt {
            // 캐시에 차 행이 없던 첫 목록에도 적용한다(그때는 바꿀 행이 없어 실시간 관찰이 남지 않았다)
            for i in merged.indices where merged[i].role == .car { merged[i].online = true; merged[i].lastSeenAt = Rfc3339.string(seen) }
        }
        s.devices = merged
        s.devicesSnapshotSeq = r.snapshotSeq
    }

    /// 7일 지난 id·보낼 함, 90일 지난 기록, 지운 지 7일 지난 곡 표시를 지운다. 보낼 함에서 버린 type을 돌려준다.
    static func purgeExpired(now t: Date, sending: String?, in s: inout PhoneState) -> [String] {
        let week = t.addingTimeInterval(-Retention.week), quarter = t.addingTimeInterval(-Retention.records)
        s.processed = s.processed.filter { $0.value > week }
        s.shown = s.shown.filter { $0.value > week }
        let expired = OutboxRules.expire(before: week, except: sending, in: &s)
        s.reports.removeAll { $0.receivedAt < quarter }
        s.alerts.removeAll { $0.receivedAt < quarter }
        s.songs.removeAll { $0.state == .removed && ($0.removedAt ?? $0.addedAt) < week }
        s.deviceMarks = s.deviceMarks.filter { $0.value.updatedAt > week }
        return expired
    }

    /// 마지막으로 차의 메시지를 받은 지 24시간이 넘었고 표시가 꺼져 있으면 켜야 한다. 표시는 201로만 끈다.
    static func needsStaleMark(_ s: PhoneState, now: Date) -> Bool {
        guard let last = s.lastReceivedAt else { return false }
        return now.timeIntervalSince(last) > Retention.stale && !s.needsStateRequest
    }
}
