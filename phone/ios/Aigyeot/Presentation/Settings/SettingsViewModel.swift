import Foundation
import Observation

/// 설정(5장 순서)·보호자 화면의 상태.
struct SettingsState {
    var isPaired: Bool
    var role: PhoneRole?
    var credentials: Credentials?
    var offline: Bool
    var care: CareFile
    var devices: [DeviceInfo]
    var handoffAlerts: Bool?
    var handoffBusy: Bool
}

@MainActor
@Observable
final class SettingsViewModel {
    @ObservationIgnored private let stateRepo: PhoneStateRepository
    @ObservationIgnored private let careRepo: CareRepository
    @ObservationIgnored private let link: LinkObserver
    @ObservationIgnored private let recordCare: RecordCare
    @ObservationIgnored private let clearRecords: ClearLocalRecords
    @ObservationIgnored private let disconnecting: Disconnect
    @ObservationIgnored private let invite: CreateInvite
    @ObservationIgnored private let handoff: SetHandoffAlerts
    @ObservationIgnored private let devices: RefreshDevices

    init(state: PhoneStateRepository, care: CareRepository, link: LinkObserver, recordCare: RecordCare, clearRecords: ClearLocalRecords,
         disconnect: Disconnect, invite: CreateInvite, handoff: SetHandoffAlerts, devices: RefreshDevices) {
        stateRepo = state
        careRepo = care
        self.link = link
        self.recordCare = recordCare
        self.clearRecords = clearRecords
        disconnecting = disconnect
        self.invite = invite
        self.handoff = handoff
        self.devices = devices
    }

    var state: SettingsState {
        SettingsState(isPaired: stateRepo.isPaired, role: stateRepo.role, credentials: stateRepo.credentials, offline: link.offline,
                      care: careRepo.value, devices: stateRepo.value.devices, handoffAlerts: stateRepo.value.handoffAlerts,
                      handoffBusy: handoff.busy)
    }

    func setChildNickname(_ name: String) { recordCare.setChildNickname(name) }
    func setSleepDiaperEnabled(_ on: Bool) { recordCare.setSleepDiaperEnabled(on) }
    func clearLocalRecords() { clearRecords.run() }
    func disconnect() async -> String? { await disconnecting.run() }
    func createInvite() async -> (InviteResponse?, String?) { await invite.run() }
    func setHandoffAlerts(_ on: Bool) async -> String? { await handoff.run(on) }
    func refreshDevices() async { await devices.run() }
}
