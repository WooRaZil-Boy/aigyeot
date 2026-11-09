import Foundation

// 돌봄 요약(R18 계산·R19 보내기)과 수유 보내기(R5·R6). 안드로이드 CareSummary.kt와 같은 나눔.

/// R18(U45): 차로 보낼 돌봄 요약을 계산하는 한 곳(P1). 시각·횟수와 오늘 잔 시간 합만 넣는다(메모·기저귀 종류·젖병 양·쪽·별명은 넣지 않는다, U60).
/// '오늘 0시'·'어제 22시'·'오늘 6시'는 `asOf`를 `zone` 벽시계로 본 날 기준이다.
struct BuildCareSummary {
    func callAsFunction(_ file: CareFile, asOf: Int64, zone: TimeZone) -> CareSummaryBody {
        let cal = CareRules.calendar(zone)
        let today = cal.startOfDay(for: CareTime.date(asOf))
        let today0 = CareTime.ms(today)
        let night = cal.date(byAdding: .day, value: -1, to: today).flatMap { cal.date(bySettingHour: 22, minute: 0, second: 0, of: $0) }.map(CareTime.ms) ?? today0
        let morning = cal.date(bySettingHour: 6, minute: 0, second: 0, of: today).map(CareTime.ms) ?? today0
        let live = file.events.filter { $0.deletedAt == nil && $0.startAt <= asOf }
        func at(_ ms: Int64?) -> String? { ms.map { Rfc3339.string(CareTime.date($0), timeZone: zone) } }

        let feeds = live.filter { $0.kind == .feed && $0.startAt >= today0 }.count   // 끝 모름 수유도 센다
        var body = CareSummaryBody(asOf: at(asOf)!, zone: zone.identifier, sleepDiaperEnabled: file.sleepDiaperEnabled, todayFeedCount: feeds)
        guard file.sleepDiaperEnabled else { return body }

        let sleeps = live.filter { $0.kind == .sleep && !$0.endUnknown }   // 끝 모름 잠은 모든 잠 칸에서 뺀다
        let ended = sleeps.filter { ($0.endAt ?? .max) <= asOf }
        let last = ended.max { ($0.endAt!, $0.startAt) < ($1.endAt!, $1.startAt) }
        let todayMs = sleeps.reduce(Int64(0)) { sum, e in sum + max(0, min(e.endAt ?? asOf, asOf) - max(e.startAt, today0)) }
        let diapers = live.filter { $0.kind == .diaper }
        body.lastSleepStartAt = at(last?.startAt)
        body.lastSleepEndAt = at(last?.endAt)
        body.sleepingSince = at(sleeps.filter { $0.endAt == nil }.map(\.startAt).max())
        body.todaySleepMin = Int(todayMs / 60_000)
        body.nightWakeups = ended.filter { night <= $0.endAt! && $0.endAt! < morning }.count
        body.lastDiaperAt = at(diapers.map(\.startAt).max())
        body.todayDiaperCount = diapers.filter { $0.startAt >= today0 }.count
        return body
    }
}

/// 수유(R5·R6)와 돌봄 요약(R19, U45·U57·U61)을 보낼 함에 맞추는 길 하나. 넣을지 판단은 FeedRules·SummaryRules.
/// 계기는 RecordCare.syncToCar 한 곳에서 부른다(R6 계기가 한 곳인지 시험이 본다).
@MainActor
final class ReconcileCareSummary {
    private let state: PhoneStateRepository
    private let care: CareRepository
    /// 가져오기 되돌리기 띠(`undo`). 차로 가는 계산은 `forCar`로 그 줄을 뺀다(U67 11-09 보충).
    private let notices: Notices
    /// 보내는 중인 항목(`sendingId`)을 보고, 넣은 뒤 보내기를 시작한다.
    private let outbox: SendOutbox
    private let now: () -> Date
    /// 시험에서 끈다(요약이 보낼 함에 끼지 않게).
    var sendsCareSummary: Bool
    /// '오늘'을 자르는 시간대(시험이 바꾼다).
    var timeZone: () -> TimeZone = { .current }

    init(state: PhoneStateRepository, care: CareRepository, notices: Notices, outbox: SendOutbox, now: @escaping () -> Date, sendsCareSummary: Bool) {
        self.state = state
        self.care = care
        self.notices = notices
        self.outbox = outbox
        self.now = now
        self.sendsCareSummary = sendsCareSummary
    }

    enum Queued { case queued, unchanged, failed }

    /// 차로 가는 계산 세 곳(수유 맞추기·요약 맞추기·보낼 함의 보내기 직전 재계산 `queue`)이 보는 기록 한 곳: 가져오기 띠의 줄을 뺀다(U67 11-09 보충).
    private var forCar: CareFile { care.care.forCar(notices.undo) }

    /// 수유를 먼저, 요약을 그다음에 맞춘다. `kick`이면 넣은 것이 있을 때 바로 보낸다(앱 시작·앞으로 올 때는 갱신이 보낸다).
    func feedingAndSummary(kick: Bool) {
        let fed = enqueueFeeding()
        let summed = queue() == .queued
        if kick, fed || summed { outbox.kick() }
    }

    /// 요약만 맞추고 넣었으면 바로 보낸다(기록 지우기를 마친 뒤).
    func summaryOnly() { if queue() == .queued { outbox.kick() } }

    /// 수유만 맞추고 넣었으면 바로 보낸다(돌봄 저장 뒤: 요약은 RecordCare.save가 방금 맞췄다).
    func feedingOnly() { if enqueueFeeding() { outbox.kick() } }

    /// R5: owner이고 연결됐으며, 지우지 않은 수유 중 가장 늦은 시작이 `lastSentFeedingAt`보다 늦고 24시간 안이면
    /// 지금의 수유 보내기 경로(보낼 함 + 전송 상태 기록)에 넣고 같은 저장에서 `lastSentFeedingAt`을 올린다.
    /// 저장에 실패하면 아무것도 바뀌지 않는다. 다음 계기가 다시 본다.
    private func enqueueFeeding() -> Bool {
        let t = now()
        guard state.role == .owner, let latest = FeedRules.latestToSend(forCar, state.state, now: t) else { return false }
        return state.commit(state.creds) { FeedRules.enqueue(latest, now: t, in: &$0) }
    }

    /// R19 요약. owner이고 연결됐을 때만. 저장 실패면 `.failed`(보낼 함이 보내기 직전 재계산에서 멈추는 데 쓴다).
    func queue() -> Queued {
        guard sendsCareSummary, state.role == .owner else { return .unchanged }
        let sending = outbox.sendingId
        let t = now()
        guard case let .enqueue(body, key, asOf) = SummaryRules.decide(forCar, state.state, nowMs: CareTime.ms(t), zone: timeZone(), sending: sending) else { return .unchanged }
        let item = OutboxRules.item(type: MessageType.careSummary, body: body, now: t)
        return state.commit(state.creds) { SummaryRules.enqueue(item, key: key, asOf: asOf, sending: sending, in: &$0) } ? .queued : .failed
    }
}
