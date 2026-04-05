/// カメラプレビュー — AVCaptureSession の映像を SwiftUI で表示する UIViewRepresentable
///
/// 前面/背面カメラの切替に対応。将来的にバーチャルボタンのオーバーレイ用レイヤーを追加予定。

import AVFoundation
import SwiftUI
import UIKit

// MARK: - CameraPreviewView

/// カメラ映像を表示する SwiftUI ビュー
struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> CameraPreviewUIView {
        let view = CameraPreviewUIView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: CameraPreviewUIView, context: Context) {
        uiView.previewLayer.session = session
    }
}

// MARK: - CameraPreviewUIView

/// AVCaptureVideoPreviewLayer をホストする UIView
final class CameraPreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        // swiftlint:disable:next force_cast
        layer as! AVCaptureVideoPreviewLayer
    }

    /// 将来のオーバーレイ用レイヤー（バーチャルボタン等）
    let overlayLayer = CALayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.addSublayer(overlayLayer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        overlayLayer.frame = bounds
    }
}
