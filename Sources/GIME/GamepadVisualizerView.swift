import SwiftUI

/// ゲームパッドビジュアライザ（SwiftUI）
/// Web 版 GamepadVisualizer.tsx の Swift 移植
struct GamepadVisualizerView: View {
    let gamepadInput: GamepadInputManager

    private var mode: GamepadInputMode { gamepadInput.currentMode }

    private var dpad: DpadLabels {
        switch mode {
        case .japanese:
            return gamepadInput.activeLayer == .lb ? dpadLabelsLB : dpadLabelsBase
        case .english:
            return gamepadInput.activeLayer == .lb ? englishDpadLabelsLB : englishDpadLabelsBase
        case .korean:
            return gamepadInput.activeLayer == .lb ? koreanDpadLabelsLB : koreanDpadLabelsBase
        }
    }

    private var isRTPressed: Bool {
        gamepadInput.pressedButtons.contains("RT")
    }

    /// 英語シフト状態を反映したフェイスボタン文字
    private var faceChars: [String] {
        switch mode {
        case .japanese: return kanaTable[gamepadInput.activeRow]
        case .english:
            let row = englishTable[gamepadInput.activeRow]
            if gamepadInput.englishCapsLock || gamepadInput.englishSmartCaps || gamepadInput.englishShiftNext {
                return row.map { $0.uppercased() }
            }
            return row
        case .korean: return isRTPressed ? koreanVowelCharsShifted : koreanVowelCharsBase
        }
    }

    private var currentRowNames: [String] {
        switch mode {
        case .japanese: return rowNames
        case .english: return englishRowNames
        case .korean: return koreanRowNames
        }
    }

    private var lbLabel: String {
        if gamepadInput.activeLayer == .lb { return "●" }
        switch mode {
        case .japanese: return "は〜"
        case .english: return "pqrs〜"
        case .korean: return "ㅁ〜"
        }
    }

    private var ltLabel: String {
        switch mode {
        case .english:
            if gamepadInput.englishCapsLock { return "CAPS" }
            if gamepadInput.englishSmartCaps { return "Caps" }
            if gamepadInput.englishShiftNext { return "Shift" }
            return "shift"
        case .korean: return "ㅇ"
        default: return "拗音"
        }
    }

    private var rbLabel: String {
        faceChars[0]
    }

    private var rtLabel: String {
        switch mode {
        case .english: return "0"
        case .korean: return "ㅑㅕ"
        default: return "ん"
        }
    }

    private var faceCenterLabel: String {
        switch mode {
        case .english: return "文字"
        case .korean: return "모음"
        default: return "母音"
        }
    }

    private var modeBadgeColor: Color {
        switch mode {
        case .japanese: return .blue
        case .english: return .green
        case .korean: return .purple
        }
    }

    // MARK: - 右スティックラベル

    private var rStickUpLabel: String {
        switch mode {
        case .japanese: return "濁点"
        case .english: return "'"
        case .korean: return "ㅋㅌ"
        }
    }

    private var rStickDownLabel: String {
        switch mode {
        case .japanese: return "、。"
        case .english: return ","
        case .korean: return "␣,."
        }
    }

    private var rStickLeftLabel: String { "⌫" }

    private var rStickRightLabel: String {
        switch mode {
        case .japanese: return "ー"
        case .english: return "␣/."
        case .korean: return "ㅘㅝ"
        }
    }

    var body: some View {
        VStack(spacing: 12) {
            // モードバッジ（ビジュアライザ最上部）
            Text(mode.label)
                .font(.system(size: 14, weight: .semibold))
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
                .background(modeBadgeColor)
                .foregroundStyle(.white)
                .clipShape(Capsule())

            HStack(spacing: 24) {
                // 左側: LT/LB + D-pad（LT=外側, LB=内側）
                VStack(spacing: 8) {
                    HStack(spacing: 6) {
                        shoulderButton(
                            char: ltLabel,
                            name: "LT",
                            pressed: gamepadInput.pressedButtons.contains("LT")
                        )
                        shoulderButton(
                            char: lbLabel,
                            name: "LB",
                            pressed: gamepadInput.pressedButtons.contains("LB")
                        )
                    }
                    dpadGrid
                }

                // 中央: プレビュー
                VStack(spacing: 4) {
                    Text(currentRowNames[gamepadInput.activeRow])
                        .font(.system(size: 14))
                        .foregroundStyle(.secondary)
                    Text(gamepadInput.previewChar ?? "　")
                        .font(.system(size: 56, weight: .bold))
                        .foregroundStyle(gamepadInput.previewChar != nil ? Color.accentColor : Color(.systemGray4))
                        .frame(minWidth: 70)
                }

                // 右側: RB/RT + フェイスボタン（RB=内側, RT=外側）
                VStack(spacing: 8) {
                    HStack(spacing: 6) {
                        shoulderButton(
                            char: rbLabel,
                            name: "RB",
                            pressed: gamepadInput.pressedButtons.contains("RB")
                        )
                        shoulderButton(
                            char: rtLabel,
                            name: "RT",
                            pressed: gamepadInput.pressedButtons.contains("RT")
                        )
                    }
                    faceButtonGrid
                }
                // 右スティック（コンパクト十字型）
                rightStickGrid
            }
            .padding()
            .background(.background, in: RoundedRectangle(cornerRadius: 16))

            // ゲームパッド名
            if let name = gamepadInput.gamepadName {
                Text(name)
                    .font(.caption2)
                    .foregroundStyle(.quaternary)
            }
        }
    }

    // MARK: - D-pad グリッド

    private var dpadGrid: some View {
        Grid(horizontalSpacing: 4, verticalSpacing: 4) {
            GridRow {
                Color.clear.frame(width: 52, height: 52)
                dpadButton(label: dpad.up, pressed: gamepadInput.pressedButtons.contains("dpadUp"))
                Color.clear.frame(width: 52, height: 52)
            }
            GridRow {
                dpadButton(label: dpad.left, pressed: gamepadInput.pressedButtons.contains("dpadLeft"))
                Text(dpad.center)
                    .font(.system(size: 13))
                    .foregroundStyle(.quaternary)
                    .frame(width: 52, height: 52)
                    .background(.fill.quaternary, in: RoundedRectangle(cornerRadius: 8))
                dpadButton(label: dpad.right, pressed: gamepadInput.pressedButtons.contains("dpadRight"))
            }
            GridRow {
                Color.clear.frame(width: 52, height: 52)
                dpadButton(label: dpad.down, pressed: gamepadInput.pressedButtons.contains("dpadDown"))
                Color.clear.frame(width: 52, height: 52)
            }
        }
    }

    // MARK: - フェイスボタングリッド

    private var faceButtonGrid: some View {
        Grid(horizontalSpacing: 4, verticalSpacing: 4) {
            GridRow {
                Color.clear.frame(width: 48, height: 48)
                faceButton(label: faceChars[2], pressed: gamepadInput.pressedButtons.contains("Y"))
                Color.clear.frame(width: 48, height: 48)
            }
            GridRow {
                faceButton(label: faceChars[1], pressed: gamepadInput.pressedButtons.contains("X"))
                Text(faceCenterLabel)
                    .font(.system(size: 12))
                    .foregroundStyle(.quaternary)
                    .frame(width: 48, height: 48)
                faceButton(label: faceChars[3], pressed: gamepadInput.pressedButtons.contains("B"))
            }
            GridRow {
                Color.clear.frame(width: 48, height: 48)
                faceButton(label: faceChars[4], pressed: gamepadInput.pressedButtons.contains("A"))
                Color.clear.frame(width: 48, height: 48)
            }
        }
    }

    // MARK: - 右スティックグリッド

    private var rightStickGrid: some View {
        Grid(horizontalSpacing: 2, verticalSpacing: 2) {
            GridRow {
                Color.clear.frame(width: 40, height: 32)
                stickButton(label: rStickUpLabel, pressed: gamepadInput.pressedButtons.contains("rStickUp"))
                Color.clear.frame(width: 40, height: 32)
            }
            GridRow {
                stickButton(label: rStickLeftLabel, pressed: gamepadInput.pressedButtons.contains("rStickLeft"))
                Text("R🕹")
                    .font(.system(size: 10))
                    .foregroundStyle(.quaternary)
                    .frame(width: 40, height: 32)
                stickButton(label: rStickRightLabel, pressed: gamepadInput.pressedButtons.contains("rStickRight"))
            }
            GridRow {
                Color.clear.frame(width: 40, height: 32)
                stickButton(label: rStickDownLabel, pressed: gamepadInput.pressedButtons.contains("rStickDown"))
                Color.clear.frame(width: 40, height: 32)
            }
        }
    }

    // MARK: - ボタンコンポーネント

    private func shoulderButton(char: String, name: String, pressed: Bool) -> some View {
        VStack(spacing: 1) {
            Text(char)
                .font(.system(size: 16, weight: .bold))
            Text(name)
                .font(.system(size: 10))
                .foregroundStyle(pressed ? .white.opacity(0.6) : .secondary)
        }
        .frame(minWidth: 52, minHeight: 44)
        .background(pressed ? Color.accentColor : Color(.systemBackground), in: RoundedRectangle(cornerRadius: 8))
        .foregroundStyle(pressed ? .white : .primary)
        .shadow(color: .black.opacity(0.05), radius: 1, y: 1)
    }

    private func faceButton(label: String, pressed: Bool) -> some View {
        Text(label)
            .font(.system(size: 16, weight: .bold))
            .frame(width: 48, height: 48)
            .background(pressed ? Color.accentColor : Color(.systemBackground), in: Circle())
            .foregroundStyle(pressed ? .white : .primary)
            .shadow(color: .black.opacity(0.05), radius: 1, y: 1)
    }

    private func dpadButton(label: String, pressed: Bool) -> some View {
        Text(label)
            .font(.system(size: 13, weight: .bold))
            .frame(width: 52, height: 52)
            .background(pressed ? Color.accentColor : Color(.systemBackground).opacity(0.7), in: RoundedRectangle(cornerRadius: 8))
            .foregroundStyle(pressed ? .white : .secondary)
            .shadow(color: .black.opacity(0.05), radius: 1, y: 1)
    }

    private func stickButton(label: String, pressed: Bool) -> some View {
        Text(label)
            .font(.system(size: 11, weight: .semibold))
            .frame(width: 40, height: 32)
            .background(pressed ? Color.accentColor : Color(.systemBackground).opacity(0.5), in: RoundedRectangle(cornerRadius: 6))
            .foregroundStyle(pressed ? .white : .secondary)
    }
}
