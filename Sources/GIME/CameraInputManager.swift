/// カメラ入力マネージャ — Hand Pose 検出によるバーチャルゲームコントローラー
///
/// iPad のカメラ映像から Vision の Hand Pose Detection で手の関節点を取得し、
/// 箱を持った手のジェスチャーをゲームパッド入力に変換する。
///
/// 検出方式:
/// - 親指: 4方向（上下左右）+ ニュートラル。関節の相対位置で判定
/// - 同時打鍵: 両親指の速度ピーク同期（時間窓内）
/// - 人差し指（LB/RB）: 屈曲角度の2値判定
/// - 中指（LT/RT）: 可視→不可視遷移の検出

import AVFoundation
import Observation
import Vision

// MARK: - カメラ位置

/// 使用するカメラ
enum CameraPosition {
    case front
    case back

    var capturePosition: AVCaptureDevice.Position {
        switch self {
        case .front: return .front
        case .back: return .back
        }
    }

    /// トグル
    mutating func toggle() {
        self = (self == .front) ? .back : .front
    }
}

// MARK: - Hand Pose 検出結果

/// 片手のジェスチャー状態
struct HandGestureState {
    /// 親指の方向（ニュートラル含む5状態）
    var thumbDirection: ThumbDirection = .neutral
    /// 親指先端の速度（ポイント/秒）
    var thumbVelocity: CGFloat = 0
    /// 人差し指が曲がっているか（LB/RB トリガー）
    var isIndexFingerBent: Bool = false
    /// 中指が可視かどうか（LT/RT 検出用）
    var isMiddleFingerVisible: Bool = false
}

/// 親指の方向
enum ThumbDirection {
    case neutral
    case up
    case down
    case left
    case right
}

/// 中指のタップ検出状態（不可視→可視→不可視の遷移）
enum MiddleFingerTapState {
    case hidden
    case visible(frameCount: Int)
    case cooldown(frameCount: Int)
}

/// nonisolated → @MainActor 境界を越えるための Sendable スナップショット
struct HandPoseSnapshot: Sendable {
    let chirality: VNChirality
    let thumbTip: CGPoint
    let thumbIP: CGPoint
    let wrist: CGPoint
    let indexTip: CGPoint
    let indexMCP: CGPoint
    let middleTipConfidence: Float
    /// ボーン描画用: 全関節点（Vision 正規化座標 0-1、左下原点）
    let joints: [VNHumanHandPoseObservation.JointName: CGPoint]
}

// MARK: - CameraInputManager

@Observable
@MainActor
final class CameraInputManager: NSObject {

    // MARK: - Public State

    /// 現在のカメラ位置
    private(set) var cameraPosition: CameraPosition = .front

    /// 左手のジェスチャー状態
    private(set) var leftHand = HandGestureState()

    /// 右手のジェスチャー状態
    private(set) var rightHand = HandGestureState()

    /// ボーン描画用: 最新の Hand Pose スナップショット
    private(set) var latestSnapshots: [HandPoseSnapshot] = []

    /// カメラが起動中か
    private(set) var isRunning = false

    /// エラーメッセージ
    private(set) var errorMessage: String?

    /// 左手の中指タップ検出
    var onLeftTriggerTap: (() -> Void)?

    /// 右手の中指タップ検出
    var onRightTriggerTap: (() -> Void)?

    /// 親指タップ検出（同時打鍵用）
    var onThumbTap: ((_ leftDirection: ThumbDirection, _ rightDirection: ThumbDirection) -> Void)?

    // MARK: - Private

    private let captureSession = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let processingQueue = DispatchQueue(label: "camera-input-processing")

    /// 前フレームの親指先端位置（速度計算用）
    private var prevLeftThumbTip: CGPoint?
    private var prevRightThumbTip: CGPoint?
    private var prevTimestamp: TimeInterval = 0

    /// 中指タップ検出状態
    private var leftMiddleTapState: MiddleFingerTapState = .hidden
    private var rightMiddleTapState: MiddleFingerTapState = .hidden

    /// 同時打鍵検出用: 親指速度ピークのタイムスタンプ
    private var leftThumbPeakTime: TimeInterval = 0
    private var rightThumbPeakTime: TimeInterval = 0
    private var leftThumbPeakDirection: ThumbDirection = .neutral
    private var rightThumbPeakDirection: ThumbDirection = .neutral

    /// 同時打鍵の時間窓（秒）
    private let chordTimeWindow: TimeInterval = 0.1

    /// 中指可視フレーム数の上限（超えたらタップではなく持ち替えとみなす）
    private let middleFingerMaxVisibleFrames = 15

    // MARK: - Public API

    /// AVCaptureSession への参照（CameraPreviewView 用）
    var session: AVCaptureSession { captureSession }

    /// カメラを起動する
    func start() {
        guard !isRunning else { return }
        setupSession()
        captureSession.startRunning()
        isRunning = true
    }

    /// カメラを停止する
    func stop() {
        guard isRunning else { return }
        captureSession.stopRunning()
        isRunning = false
    }

    /// 前面/背面カメラを切り替える
    func toggleCamera() {
        cameraPosition.toggle()
        guard isRunning else { return }
        reconfigureCamera()
    }

    // MARK: - Session Setup

    private func setupSession() {
        captureSession.beginConfiguration()
        captureSession.sessionPreset = .medium

        // カメラ入力
        guard let device = captureDevice(for: cameraPosition),
              let input = try? AVCaptureDeviceInput(device: device) else {
            errorMessage = "カメラを利用できません"
            captureSession.commitConfiguration()
            return
        }
        if captureSession.canAddInput(input) {
            captureSession.addInput(input)
        }

        // ビデオ出力
        videoOutput.setSampleBufferDelegate(self, queue: processingQueue)
        videoOutput.alwaysDiscardsLateVideoFrames = true
        if captureSession.canAddOutput(videoOutput) {
            captureSession.addOutput(videoOutput)
        }

        captureSession.commitConfiguration()
    }

    private func reconfigureCamera() {
        captureSession.beginConfiguration()

        // 既存の入力を削除
        for input in captureSession.inputs {
            captureSession.removeInput(input)
        }

        // 新しいカメラで再構成
        guard let device = captureDevice(for: cameraPosition),
              let input = try? AVCaptureDeviceInput(device: device) else {
            errorMessage = "カメラを切り替えできません"
            captureSession.commitConfiguration()
            return
        }
        if captureSession.canAddInput(input) {
            captureSession.addInput(input)
        }

        captureSession.commitConfiguration()
    }

    private func captureDevice(for position: CameraPosition) -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position.capturePosition)
    }

    // MARK: - Hand Pose 解析

    /// フレームから Hand Pose を検出し、ジェスチャー状態を更新する
    nonisolated private func processFrame(_ sampleBuffer: CMSampleBuffer) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let request = VNDetectHumanHandPoseRequest()
        request.maximumHandCount = 2

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        do {
            try handler.perform([request])
        } catch {
            return
        }

        guard let observations = request.results, !observations.isEmpty else { return }

        // non-Sendable な VNHumanHandPoseObservation から Sendable な値を抽出
        let allJointNames: [VNHumanHandPoseObservation.JointName] = [
            .wrist,
            .thumbCMC, .thumbMP, .thumbIP, .thumbTip,
            .indexMCP, .indexPIP, .indexDIP, .indexTip,
            .middleMCP, .middlePIP, .middleDIP, .middleTip,
            .ringMCP, .ringPIP, .ringDIP, .ringTip,
            .littleMCP, .littlePIP, .littleDIP, .littleTip,
        ]
        let snapshots = observations.compactMap { obs -> HandPoseSnapshot? in
            guard let thumbTip = try? obs.recognizedPoint(.thumbTip),
                  let thumbIP = try? obs.recognizedPoint(.thumbIP),
                  let wrist = try? obs.recognizedPoint(.wrist),
                  let indexTip = try? obs.recognizedPoint(.indexTip),
                  let indexMCP = try? obs.recognizedPoint(.indexMCP),
                  let middleTip = try? obs.recognizedPoint(.middleTip) else {
                return nil
            }
            // 全関節点を収集（ボーン描画用）
            var joints: [VNHumanHandPoseObservation.JointName: CGPoint] = [:]
            for name in allJointNames {
                if let pt = try? obs.recognizedPoint(name), pt.confidence > 0.1 {
                    joints[name] = pt.location
                }
            }
            return HandPoseSnapshot(
                chirality: obs.chirality,
                thumbTip: thumbTip.location, thumbIP: thumbIP.location, wrist: wrist.location,
                indexTip: indexTip.location, indexMCP: indexMCP.location,
                middleTipConfidence: middleTip.confidence,
                joints: joints
            )
        }

        let now = CACurrentMediaTime()

        Task { @MainActor in
            self.updateHandStates(snapshots: snapshots, timestamp: now)
        }
    }

    /// スナップショットからジェスチャー状態を更新
    private func updateHandStates(snapshots: [HandPoseSnapshot], timestamp: TimeInterval) {
        latestSnapshots = snapshots
        let dt = timestamp - prevTimestamp
        prevTimestamp = timestamp

        for snap in snapshots {
            // 親指の方向: 親指先端の wrist からの相対位置で判定
            let thumbDir = resolveThumbDirection(thumbTip: snap.thumbTip, thumbIP: snap.thumbIP, wrist: snap.wrist)

            // 親指の速度
            let velocity: CGFloat
            if snap.chirality == .left, let prev = prevLeftThumbTip, dt > 0 {
                velocity = hypot(snap.thumbTip.x - prev.x, snap.thumbTip.y - prev.y) / dt
            } else if snap.chirality == .right, let prev = prevRightThumbTip, dt > 0 {
                velocity = hypot(snap.thumbTip.x - prev.x, snap.thumbTip.y - prev.y) / dt
            } else {
                velocity = 0
            }

            // 人差し指の屈曲: 指先と MCP の距離で判定
            let indexBent = distanceBetween(snap.indexTip, snap.indexMCP) < 0.08

            // 中指の可視性: confidence で判定
            let middleVisible = snap.middleTipConfidence > 0.3

            let state = HandGestureState(
                thumbDirection: thumbDir,
                thumbVelocity: velocity,
                isIndexFingerBent: indexBent,
                isMiddleFingerVisible: middleVisible
            )

            if snap.chirality == .left {
                leftHand = state
                prevLeftThumbTip = snap.thumbTip
                updateMiddleFingerTap(visible: middleVisible, state: &leftMiddleTapState, onTap: onLeftTriggerTap)
                detectThumbPeak(velocity: velocity, direction: thumbDir, timestamp: timestamp, isLeft: true)
            } else {
                rightHand = state
                prevRightThumbTip = snap.thumbTip
                updateMiddleFingerTap(visible: middleVisible, state: &rightMiddleTapState, onTap: onRightTriggerTap)
                detectThumbPeak(velocity: velocity, direction: thumbDir, timestamp: timestamp, isLeft: false)
            }
        }
    }

    // MARK: - ジェスチャー判定

    /// 親指の方向を判定（wrist 基準の相対位置）
    private func resolveThumbDirection(
        thumbTip: CGPoint, thumbIP: CGPoint, wrist: CGPoint
    ) -> ThumbDirection {
        let dx = thumbTip.x - wrist.x
        let dy = thumbTip.y - wrist.y

        // 移動量が小さければニュートラル
        let threshold: CGFloat = 0.05
        guard max(abs(dx), abs(dy)) > threshold else { return .neutral }

        if abs(dx) > abs(dy) {
            return dx > 0 ? .right : .left
        } else {
            return dy > 0 ? .up : .down
        }
    }

    /// 2点間の距離
    private func distanceBetween(_ a: CGPoint, _ b: CGPoint) -> CGFloat {
        hypot(a.x - b.x, a.y - b.y)
    }

    /// 中指タップ検出（不可視→可視→不可視の遷移）
    private func updateMiddleFingerTap(
        visible: Bool, state: inout MiddleFingerTapState, onTap: (() -> Void)?
    ) {
        switch state {
        case .hidden:
            if visible {
                state = .visible(frameCount: 1)
            }
        case .visible(let count):
            if visible {
                if count >= middleFingerMaxVisibleFrames {
                    // 長く見えすぎ → 持ち替えとみなしリセット
                    state = .hidden
                } else {
                    state = .visible(frameCount: count + 1)
                }
            } else {
                // 可視→不可視: タップ成立
                onTap?()
                state = .cooldown(frameCount: 0)
            }
        case .cooldown(let count):
            if count >= 5 {
                state = .hidden
            } else {
                state = .cooldown(frameCount: count + 1)
            }
        }
    }

    /// 親指の速度ピーク検出 + 同時打鍵判定
    private func detectThumbPeak(velocity: CGFloat, direction: ThumbDirection, timestamp: TimeInterval, isLeft: Bool) {
        let peakThreshold: CGFloat = 1.5

        guard velocity > peakThreshold, direction != .neutral else { return }

        if isLeft {
            leftThumbPeakTime = timestamp
            leftThumbPeakDirection = direction
        } else {
            rightThumbPeakTime = timestamp
            rightThumbPeakDirection = direction
        }

        // 両方のピークが時間窓内なら同時打鍵
        if abs(leftThumbPeakTime - rightThumbPeakTime) < chordTimeWindow,
           leftThumbPeakDirection != .neutral,
           rightThumbPeakDirection != .neutral {
            onThumbTap?(leftThumbPeakDirection, rightThumbPeakDirection)
            // リセット（二重発火防止）
            leftThumbPeakDirection = .neutral
            rightThumbPeakDirection = .neutral
        }
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

extension CameraInputManager: AVCaptureVideoDataOutputSampleBufferDelegate {
    nonisolated func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        processFrame(sampleBuffer)
    }
}
