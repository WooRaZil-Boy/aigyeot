import SwiftUI
import VisionKit
import AVFoundation

/// QR 찍기. VisionKit `DataScannerViewController`를 SwiftUI에 감싼다.
/// 카메라를 못 쓰는 기기(시뮬레이터 등)는 `isAvailable`이 거짓이라 코드 입력으로 안내한다.
struct QRScanner: UIViewControllerRepresentable {
    /// 올바른 연결 코드로 받아들였으면 true. false면 계속 찍는다.
    var onScan: (String) -> Bool

    static var isSupported: Bool { DataScannerViewController.isSupported }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced, recognizesMultipleItems: false, isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true, isGuidanceEnabled: false, isHighlightingEnabled: true)
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: DataScannerViewController, context: Context) {
        if !vc.isScanning { try? vc.startScanning() }
    }

    static func dismantleUIViewController(_ vc: DataScannerViewController, coordinator: Coordinator) {
        vc.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScan: (String) -> Bool
        private var done = false
        init(onScan: @escaping (String) -> Bool) { self.onScan = onScan }

        func dataScanner(_ scanner: DataScannerViewController, didAdd items: [RecognizedItem], allItems: [RecognizedItem]) {
            for case .barcode(let b) in items {
                if let text = b.payloadStringValue, !done, onScan(text) {
                    done = true   // 형식이 맞을 때만 완료로 본다. 잘못된 QR은 다시 찍을 수 있다.
                    return
                }
            }
        }
    }
}

enum CameraAccess {
    static func request() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }
}
