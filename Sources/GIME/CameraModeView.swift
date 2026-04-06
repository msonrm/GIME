/// カメラモードビュー — ビジュアライザ領域にカメラ映像 + Hand Pose 状態を表示
///
/// GamepadVisualizerView のコンテンツ領域でカメラモード時に表示される。
/// 将来的にバーチャルボタンのオーバーレイを追加予定。

import SwiftUI
import Vision

struct CameraModeView: View {
    var gamepadInput: GamepadInputManager

    @State private var cameraInput = CameraInputManager()

    var body: some View {
        VStack(spacing: 8) {
            // カメラプレビュー + ボーンオーバーレイ
            ZStack {
                CameraPreviewView(session: cameraInput.session)
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                // Hand Pose ボーン描画
                GeometryReader { geo in
                    HandBoneOverlay(
                        snapshots: cameraInput.latestSnapshots,
                        viewSize: geo.size,
                        isFrontCamera: cameraInput.cameraPosition == .front
                    )
                }
                .allowsHitTesting(false)

                // Hand Pose 状態オーバーレイ
                VStack {
                    Spacer()
                    handStateOverlay
                }
            }
            .frame(height: 200)

            // コントロールバー
            HStack {
                // カメラ切替ボタン
                Button {
                    cameraInput.toggleCamera()
                } label: {
                    Label(
                        cameraInput.cameraPosition == .front ? "前面" : "背面",
                        systemImage: "arrow.triangle.2.circlepath.camera"
                    )
                    .font(.system(size: 13, weight: .medium))
                }
                .buttonStyle(.bordered)
                .tint(.purple)

                Spacer()

                // 検出状態
                if cameraInput.isRunning {
                    HStack(spacing: 12) {
                        handIndicator(label: "L", state: cameraInput.leftHand)
                        handIndicator(label: "R", state: cameraInput.rightHand)
                    }
                }
            }

            // エラー表示
            if let error = cameraInput.errorMessage {
                Text(error)
                    .font(.system(size: 12))
                    .foregroundStyle(.red)
            }
        }
        .padding()
        .background(.background, in: RoundedRectangle(cornerRadius: 16))
        .onAppear {
            cameraInput.start()
        }
        .onDisappear {
            cameraInput.stop()
        }
    }

    // MARK: - Hand Pose 状態オーバーレイ

    /// カメラ映像上に重ねる Hand Pose 状態表示
    private var handStateOverlay: some View {
        HStack(spacing: 20) {
            // 左手
            VStack(spacing: 2) {
                directionArrow(cameraInput.leftHand.thumbDirection)
                    .font(.system(size: 24))
                if cameraInput.leftHand.isIndexFingerBent {
                    Text("LB")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.yellow)
                }
            }

            Spacer()

            // 右手
            VStack(spacing: 2) {
                directionArrow(cameraInput.rightHand.thumbDirection)
                    .font(.system(size: 24))
                if cameraInput.rightHand.isIndexFingerBent {
                    Text("RB")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.yellow)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    // MARK: - Subviews

    /// 手の検出状態インジケータ
    private func handIndicator(label: String, state: HandGestureState) -> some View {
        HStack(spacing: 4) {
            Text(label)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(.secondary)
            Circle()
                .fill(state.thumbDirection != .neutral ? Color.green : Color(.systemGray4))
                .frame(width: 8, height: 8)
            directionArrow(state.thumbDirection)
                .font(.system(size: 12))
                .foregroundStyle(.primary)
        }
    }

    /// 親指方向の矢印表示
    private func directionArrow(_ direction: ThumbDirection) -> some View {
        let icon: String = switch direction {
        case .neutral: "circle"
        case .up: "arrow.up"
        case .down: "arrow.down"
        case .left: "arrow.left"
        case .right: "arrow.right"
        }
        return Image(systemName: icon)
            .foregroundStyle(direction == .neutral ? Color(.systemGray4) : .white)
    }
}

// MARK: - Hand Pose ボーンオーバーレイ

/// Vision の関節点を線で結んでカメラ映像上に描画する
private struct HandBoneOverlay: View {
    let snapshots: [HandPoseSnapshot]
    let viewSize: CGSize
    let isFrontCamera: Bool

    /// 指ごとのボーン接続（wrist → 各指先）
    private static let fingerBones: [[VNHumanHandPoseObservation.JointName]] = [
        [.wrist, .thumbCMC, .thumbMP, .thumbIP, .thumbTip],
        [.wrist, .indexMCP, .indexPIP, .indexDIP, .indexTip],
        [.wrist, .middleMCP, .middlePIP, .middleDIP, .middleTip],
        [.wrist, .ringMCP, .ringPIP, .ringDIP, .ringTip],
        [.wrist, .littleMCP, .littlePIP, .littleDIP, .littleTip],
    ]

    var body: some View {
        Canvas { context, size in
            for snapshot in snapshots {
                let color: Color = snapshot.chirality == .left ? .cyan : .yellow

                // ボーン（骨格線）を描画
                for finger in Self.fingerBones {
                    var path = Path()
                    var started = false
                    for jointName in finger {
                        guard let pt = snapshot.joints[jointName] else { continue }
                        let viewPt = visionToView(pt, in: size)
                        if started {
                            path.addLine(to: viewPt)
                        } else {
                            path.move(to: viewPt)
                            started = true
                        }
                    }
                    context.stroke(path, with: .color(color.opacity(0.8)), lineWidth: 2)
                }

                // 関節点を描画
                for (_, pt) in snapshot.joints {
                    let viewPt = visionToView(pt, in: size)
                    let dot = Path(ellipseIn: CGRect(
                        x: viewPt.x - 3, y: viewPt.y - 3,
                        width: 6, height: 6
                    ))
                    context.fill(dot, with: .color(color))
                }
            }
        }
    }

    /// Vision 正規化座標（0-1、左下原点）→ ビュー座標に変換
    private func visionToView(_ point: CGPoint, in size: CGSize) -> CGPoint {
        // Vision: 左下原点、Y 上向き → ビュー: 左上原点、Y 下向き
        let x = isFrontCamera ? (1 - point.x) : point.x  // 前面カメラはミラー
        let y = 1 - point.y
        return CGPoint(x: x * size.width, y: y * size.height)
    }
}
