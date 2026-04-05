/// カメラモードビュー — ビジュアライザ領域にカメラ映像 + Hand Pose 状態を表示
///
/// GamepadVisualizerView のコンテンツ領域でカメラモード時に表示される。
/// 将来的にバーチャルボタンのオーバーレイを追加予定。

import SwiftUI

struct CameraModeView: View {
    var gamepadInput: GamepadInputManager

    @State private var cameraInput = CameraInputManager()

    var body: some View {
        VStack(spacing: 8) {
            // カメラプレビュー
            ZStack {
                CameraPreviewView(session: cameraInput.session)
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                // Hand Pose 状態オーバーレイ（将来のバーチャルボタン用の土台）
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
