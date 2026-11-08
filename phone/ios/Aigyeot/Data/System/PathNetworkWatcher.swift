import Foundation
import Network

/// NetworkWatcher 구현: 인터넷 경로(NWPathMonitor). 띠를 켜고 끄는 규칙은 도메인이 정한다.
@MainActor
final class PathNetworkWatcher: NetworkWatcher {
    private var monitor: NWPathMonitor?

    func watch(_ onChange: @escaping @MainActor (Bool) -> Void) {
        guard monitor == nil else { return }   // 다시 불려도 한 번만 시작한다
        let m = NWPathMonitor()
        m.pathUpdateHandler = { path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in onChange(satisfied) }
        }
        // NWPathMonitor는 알림을 받을 큐를 인자로 요구한다(OS API). 알림은 바로 위에서 메인 액터로 넘긴다.
        m.start(queue: .global(qos: .utility))
        monitor = m
    }

    func unwatch() {
        monitor?.cancel()
        monitor = nil
    }
}
