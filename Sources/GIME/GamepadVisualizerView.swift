import SwiftUI

/// ゲームパッドビジュアライザ（SwiftUI）
/// Web 版 GamepadVisualizer.tsx の Swift 移植
struct GamepadVisualizerView: View {
    let gamepadInput: GamepadInputManager
    let zenzaiManager: ZenzaiModelManager

    @State private var showSettings = false

    private var mode: GamepadInputMode { gamepadInput.currentMode }

    private var dpad: DpadLabels {
        switch mode {
        case .japanese:
            return gamepadInput.activeLayer == .lb ? dpadLabelsLB : dpadLabelsBase
        case .english, .chineseSimplified:
            return gamepadInput.activeLayer == .lb ? englishDpadLabelsLB : englishDpadLabelsBase
        case .chineseTraditional:
            return gamepadInput.activeLayer == .lb ? zhuyinDpadLabelsLB : zhuyinDpadLabelsBase
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
        case .chineseSimplified: return englishTable[gamepadInput.activeRow]
        case .chineseTraditional: return zhuyinTable[gamepadInput.activeRow]
        }
    }

    private var currentRowNames: [String] {
        switch mode {
        case .japanese: return rowNames
        case .english, .chineseSimplified: return englishRowNames
        case .chineseTraditional: return zhuyinRowNames
        case .korean: return koreanRowNames
        }
    }

    private var lbLabel: String {
        if gamepadInput.activeLayer == .lb { return "●" }
        switch mode {
        case .japanese: return "は〜"
        case .english, .chineseSimplified: return "pqrs〜"
        case .chineseTraditional: return "ㄗㄘㄙ〜"
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
        case .chineseSimplified, .chineseTraditional: return "—"
        case .japanese: return "拗音"
        }
    }

    private var rbLabel: String {
        faceChars[0]
    }

    private var rtLabel: String {
        switch mode {
        case .english: return "0"
        case .korean: return "ㅑㅕ"
        case .chineseSimplified, .chineseTraditional: return "—"
        case .japanese: return "ん"
        }
    }

    private var faceCenterLabel: String {
        switch mode {
        case .english, .chineseSimplified, .chineseTraditional: return "文字"
        case .korean: return "모음"
        case .japanese: return "母音"
        }
    }

    private var modeBadgeColor: Color {
        switch mode {
        case .japanese: return .blue
        case .english: return .green
        case .korean: return .purple
        case .chineseSimplified: return .red
        case .chineseTraditional: return .orange
        }
    }

    // MARK: - 右スティックラベル

    private var rStickUpLabel: String {
        switch mode {
        case .japanese: return "濁点"
        case .english: return "'"
        case .korean: return "ㅋㅌ"
        case .chineseSimplified, .chineseTraditional: return ""
        }
    }

    private var rStickDownLabel: String {
        switch mode {
        case .japanese: return "、。"
        case .english: return ","
        case .korean: return "␣,."
        case .chineseSimplified, .chineseTraditional: return "，。"
        }
    }

    private var rStickLeftLabel: String { "⌫" }

    private var rStickRightLabel: String {
        switch mode {
        case .japanese: return "ー"
        case .english: return "␣/."
        case .korean: return "ㅘㅝ"
        case .chineseSimplified, .chineseTraditional: return "␣"
        }
    }

    var body: some View {
        VStack(spacing: 8) {
            // ヘッダー: 言語バッジ（左）+ 設定アイコン（右）
            HStack {
                Text(mode.label)
                    .font(.system(size: 14, weight: .semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 4)
                    .background(modeBadgeColor)
                    .foregroundStyle(.white)
                    .clipShape(Capsule())

                // 中国語モード: ピンインバッファ表示
                if (mode == .chineseSimplified || mode == .chineseTraditional) && !gamepadInput.pinyinBuffer.isEmpty {
                    Text(gamepadInput.pinyinBuffer)
                        .font(.system(size: 16, weight: .medium, design: .monospaced))
                        .foregroundStyle(.primary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color(.systemGray5), in: RoundedRectangle(cornerRadius: 6))
                }

                Spacer()

                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 4)

            HStack(spacing: 24) {
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
        }
        .sheet(isPresented: $showSettings) {
            GamepadSettingsSheet(
                gamepadInput: gamepadInput,
                zenzaiManager: zenzaiManager
            )
        }
    }

    // MARK: - D-pad グリッド

    /// 英語モード: 十字キーのフェイスボタン対応文字（X=左, Y=上, B=右, A=下）
    private func englishDpadCrossChars(row: Int) -> (left: String, up: String, right: String, down: String) {
        let r = englishTable[row]
        let caps = gamepadInput.englishCapsLock || gamepadInput.englishSmartCaps || gamepadInput.englishShiftNext
        func t(_ s: String) -> String { caps ? s.uppercased() : s }
        return (left: t(r[1]), up: t(r[2]), right: t(r[3]), down: t(r[4]))
    }

    /// 英語/中国語モード共通: D-pad の十字文字を取得
    private func crossCharsForCurrentMode(row: Int) -> (left: String, up: String, right: String, down: String) {
        if mode == .english {
            return englishDpadCrossChars(row: row)
        }
        // 中国語: シフトなしで英語テーブルを使用
        let r = englishTable[row]
        return (left: r[1], up: r[2], right: r[3], down: r[4])
    }

    private var dpadGrid: some View {
        let isEnglish = mode == .english || mode == .chineseSimplified
        // 繁体字（注音）はラベル表示型（日本語/韓国語と同じスタイル）
        let offset = gamepadInput.activeLayer == .lb ? 5 : 0

        return Grid(horizontalSpacing: 4, verticalSpacing: 4) {
            GridRow {
                Color.clear.frame(width: 52, height: 52)
                if isEnglish {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 2 + offset), pressed: gamepadInput.pressedButtons.contains("dpadUp"))
                } else {
                    dpadButton(label: dpad.up, pressed: gamepadInput.pressedButtons.contains("dpadUp"))
                }
                Color.clear.frame(width: 52, height: 52)
            }
            GridRow {
                if isEnglish {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 1 + offset), pressed: gamepadInput.pressedButtons.contains("dpadLeft"))
                } else {
                    dpadButton(label: dpad.left, pressed: gamepadInput.pressedButtons.contains("dpadLeft"))
                }
                if isEnglish {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 0 + offset), pressed: false)
                        .opacity(0.5)
                } else {
                    Text(dpad.center)
                        .font(.system(size: 13))
                        .foregroundStyle(.quaternary)
                        .frame(width: 52, height: 52)
                        .background(.fill.quaternary, in: RoundedRectangle(cornerRadius: 8))
                }
                if isEnglish {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 3 + offset), pressed: gamepadInput.pressedButtons.contains("dpadRight"))
                } else {
                    dpadButton(label: dpad.right, pressed: gamepadInput.pressedButtons.contains("dpadRight"))
                }
            }
            GridRow {
                Color.clear.frame(width: 52, height: 52)
                if isEnglish {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 4 + offset), pressed: gamepadInput.pressedButtons.contains("dpadDown"))
                } else {
                    dpadButton(label: dpad.down, pressed: gamepadInput.pressedButtons.contains("dpadDown"))
                }
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

    private static let buttonShadow: Color = .black.opacity(0.15)

    private func shoulderButton(char: String, name: String, pressed: Bool) -> some View {
        VStack(spacing: 1) {
            Text(char)
                .font(.system(size: 16, weight: .bold))
            Text(name)
                .font(.system(size: 10))
                .foregroundStyle(pressed ? .white.opacity(0.6) : .secondary)
        }
        .frame(minWidth: 52, minHeight: 44)
        .background(pressed ? Color.accentColor : Color(.systemGray5), in: RoundedRectangle(cornerRadius: 8))
        .foregroundStyle(pressed ? .white : .primary)
        .shadow(color: Self.buttonShadow, radius: 2, y: 1)
    }

    private func faceButton(label: String, pressed: Bool) -> some View {
        Text(label)
            .font(.system(size: 16, weight: .bold))
            .frame(width: 48, height: 48)
            .background(pressed ? Color.accentColor : Color(.systemGray5), in: Circle())
            .foregroundStyle(pressed ? .white : .primary)
            .shadow(color: Self.buttonShadow, radius: 2, y: 1)
    }

    /// 英語モード: フェイスボタン位置に文字を十字配置した D-pad ボタン
    private func dpadButtonCross(chars: (left: String, up: String, right: String, down: String), pressed: Bool) -> some View {
        ZStack {
            if !chars.up.isEmpty {
                Text(chars.up).offset(y: -13)
            }
            if !chars.left.isEmpty {
                Text(chars.left).offset(x: -13)
            }
            if !chars.right.isEmpty {
                Text(chars.right).offset(x: 13)
            }
            if !chars.down.isEmpty {
                Text(chars.down).offset(y: 13)
            }
        }
        .font(.system(size: 13, weight: .bold))
        .frame(width: 52, height: 52)
        .background(pressed ? Color.accentColor : Color(.systemGray5).opacity(0.7), in: RoundedRectangle(cornerRadius: 8))
        .foregroundStyle(pressed ? .white : .secondary)
        .shadow(color: Self.buttonShadow, radius: 2, y: 1)
    }

    private func dpadButton(label: String, pressed: Bool) -> some View {
        Text(label)
            .font(.system(size: 13, weight: .bold))
            .frame(width: 52, height: 52)
            .background(pressed ? Color.accentColor : Color(.systemGray5).opacity(0.7), in: RoundedRectangle(cornerRadius: 8))
            .foregroundStyle(pressed ? .white : .secondary)
            .shadow(color: Self.buttonShadow, radius: 2, y: 1)
    }

    private func stickButton(label: String, pressed: Bool) -> some View {
        Text(label)
            .font(.system(size: 11, weight: .semibold))
            .frame(width: 40, height: 32)
            .background(pressed ? Color.accentColor : Color(.systemGray5).opacity(0.5), in: RoundedRectangle(cornerRadius: 6))
            .foregroundStyle(pressed ? .white : .secondary)
    }
}

// MARK: - 設定シート

/// ビジュアライザ設定シート（Zenzai トグル + 言語サイクル設定）
private struct GamepadSettingsSheet: View {
    let gamepadInput: GamepadInputManager
    let zenzaiManager: ZenzaiModelManager

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var zm = zenzaiManager
        return NavigationStack {
            Form {
                // Zenzai セクション
                Section("Zenzai") {
                    Toggle(isOn: $zm.isEnabled) {
                        HStack(spacing: 6) {
                            Image(systemName: "brain")
                            Text("Zenzai（ニューラル変換）")
                        }
                    }
                    switch zenzaiManager.state {
                    case .downloading(let progress):
                        HStack {
                            ProgressView(value: progress)
                            Text("\(Int(progress * 100))%")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    case .error:
                        Button("再試行") { zenzaiManager.startDownloadIfNeeded() }
                    default:
                        EmptyView()
                    }
                }

                // 言語サイクル設定セクション
                Section {
                    ForEach(GamepadInputMode.allCases, id: \.self) { mode in
                        Toggle(isOn: Binding(
                            get: { gamepadInput.enabledModes.contains(mode) },
                            set: { enabled in
                                if enabled {
                                    gamepadInput.enabledModes.insert(mode)
                                } else {
                                    // 最低1つは残す
                                    if gamepadInput.enabledModes.count > 1 {
                                        gamepadInput.enabledModes.remove(mode)
                                    }
                                }
                            }
                        )) {
                            Text(mode.label)
                        }
                        .disabled(
                            gamepadInput.enabledModes.contains(mode) && gamepadInput.enabledModes.count <= 1
                        )
                    }
                } header: {
                    Text("Start ボタンの言語切替")
                } footer: {
                    Text("Start ボタンで切り替える言語を選択します。最低1つは有効にする必要があります。")
                }
            }
            .navigationTitle("設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完了") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
