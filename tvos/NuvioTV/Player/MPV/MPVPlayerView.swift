import AVKit
import CoreMedia
import SwiftUI
import UIKit

/// Hosts mpv's CAMetalLayer and turns Siri Remote touch-surface swipes into scrub events.
final class MPVHostViewController: UIViewController {
    let controller: MPVController
    var onScrub: ((CGFloat, UIGestureRecognizer.State) -> Void)?

    init(controller: MPVController) {
        self.controller = controller
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        controller.metalLayer.frame = view.bounds
        controller.metalLayer.contentsScale = UIScreen.main.nativeScale
        view.layer.addSublayer(controller.metalLayer)

        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        pan.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.indirect.rawValue)]
        pan.cancelsTouchesInView = false
        view.addGestureRecognizer(pan)

        NotificationCenter.default.addObserver(self, selector: #selector(didEnterBackground),
                                               name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(willEnterForeground),
                                               name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        controller.metalLayer.frame = view.bounds
        CATransaction.commit()
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        let dx = gesture.translation(in: view).x / max(view.bounds.width, 1)
        onScrub?(dx, gesture.state)
    }

    // Avoid a black picture after returning from the background (MPVKit demo workaround).
    @objc private func didEnterBackground() {
        controller.pause()
        controller.setVideoEnabled(false)
    }

    @objc private func willEnterForeground() {
        controller.setVideoEnabled(true)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }
}

struct MPVVideoView: UIViewControllerRepresentable {
    let controller: MPVController
    var onScrub: ((CGFloat, UIGestureRecognizer.State) -> Void)?

    func makeUIViewController(context: Context) -> MPVHostViewController {
        let host = MPVHostViewController(controller: controller)
        host.onScrub = onScrub
        return host
    }

    func updateUIViewController(_ host: MPVHostViewController, context: Context) {
        host.onScrub = onScrub
    }
}

/// Requests the TV's matching refresh rate and dynamic range (Settings ▸ Video ▸ Match Content).
enum DisplayCriteria {
    /// Requests matching criteria and returns the display manager it was set on, so the caller
    /// can reset that same manager later (the window may be gone by then).
    @MainActor
    @discardableResult
    static func apply(fps: Double, gamma: String?, in window: UIWindow?) -> AVDisplayManager? {
        guard let manager = window?.avDisplayManager, manager.isDisplayCriteriaMatchingEnabled else { return nil }
        let gammaName = gamma?.lowercased() ?? ""
        let isHdr = gammaName == "pq" || gammaName == "hlg"
        let transfer: CFString
        switch gammaName {
        case "pq": transfer = kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ
        case "hlg": transfer = kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG
        default: transfer = kCMFormatDescriptionTransferFunction_ITU_R_709_2
        }
        let extensions: [String: Any] = [
            kCMFormatDescriptionExtension_TransferFunction as String: transfer,
            kCMFormatDescriptionExtension_ColorPrimaries as String: isHdr
                ? kCMFormatDescriptionColorPrimaries_ITU_R_2020
                : kCMFormatDescriptionColorPrimaries_ITU_R_709_2,
            kCMFormatDescriptionExtension_YCbCrMatrix as String: isHdr
                ? kCMFormatDescriptionYCbCrMatrix_ITU_R_2020
                : kCMFormatDescriptionYCbCrMatrix_ITU_R_709_2,
        ]
        var description: CMFormatDescription?
        CMVideoFormatDescriptionCreate(
            allocator: kCFAllocatorDefault,
            codecType: kCMVideoCodecType_HEVC,
            width: 3840,
            height: 2160,
            extensions: extensions as CFDictionary,
            formatDescriptionOut: &description
        )
        guard let description else { return nil }
        let rate = fps > 1 ? Float(fps) : 0
        manager.preferredDisplayCriteria = AVDisplayCriteria(refreshRate: rate, formatDescription: description)
        return manager
    }

    /// Hands the display back to the system's default mode (the home screen's rate / range).
    @MainActor
    static func reset(_ manager: AVDisplayManager?) {
        manager?.preferredDisplayCriteria = nil
    }
}
