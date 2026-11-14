import SwiftUI

/// 연결 실패 글. 429 잠금이면 남은 시간이 줄어들도록 1초마다 다시 그린다.
struct ConnectFailureText: View {
    let failure: ConnectFailure
    let lockedUntil: Date?
    var body: some View {
        if case .rateLimited = failure {
            TimelineView(.periodic(from: .now, by: 1)) { ctx in Text(failure.text(lockedUntil: lockedUntil, now: ctx.date)) }
        } else {
            Text(failure.message)
        }
    }
}
