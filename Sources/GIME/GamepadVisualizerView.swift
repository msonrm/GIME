import SwiftUI

/// ゲームパッドビジュアライザ（SwiftUI）
/// Web 版 GamepadVisualizer.tsx の Swift 移植
struct GamepadVisualizerView: View {
    let gamepadInput: GamepadInputManager
    @Bindable var vrChatSettings: VrChatOscSettings

    @State private var showSettings = false
    @State private var showVrChatSettings = false
    @State private var isCollapsed = false

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
        case .korean:
            if gamepadInput.koreanJamoLock { return "LOCK" }    // 持続モード（長押しで toggle）
            if gamepadInput.koreanSmartJamo { return "자모" }   // 一時モード（空白/句読点で解除）
            return "ㅇ"                                          // 通常: 単押しで ㅇ받침
        case .chineseSimplified, .chineseTraditional: return ""
        case .japanese: return "拗音"
        }
    }

    private var rbLabel: String {
        faceChars[0]
    }

    private var rtLabel: String {
        switch mode {
        case .english, .chineseSimplified, .chineseTraditional: return "0"
        case .korean: return "ㅑㅕ"
        case .japanese: return "ん"
        }
    }

    private var modeBadgeColor: Color {
        switch mode {
        case .japanese: return .pink
        case .english: return .green
        case .korean: return .indigo
        case .chineseSimplified: return .red
        case .chineseTraditional: return .blue
        }
    }

    // MARK: - 右スティックラベル

    private var rStickUpLabel: String {
        switch mode {
        case .japanese: return "濁点"
        case .english: return "'"
        case .korean:
            // 자모 모드: ↑ = 直前子音 평→격→경 サイクル
            return gamepadInput.isKoreanJamoMode ? "ㄱㅋㄲ" : "ㅋㅌ"
        case .chineseSimplified, .chineseTraditional: return ""
        }
    }

    private var rStickDownLabel: String {
        switch mode {
        case .japanese: return "、。␣"
        case .english: return "␣.,"
        case .korean: return "␣."
        case .chineseSimplified, .chineseTraditional: return "，。␣"
        }
    }

    private var rStickLeftLabel: String { "⌫" }

    private var rStickRightLabel: String {
        switch mode {
        case .japanese: return "ー"
        case .english: return "/"
        case .korean:
            // 자모 모드: → = 直前 jamo の連打（연타）
            return gamepadInput.isKoreanJamoMode ? "연타" : "ㅘㅝ"
        case .chineseSimplified, .chineseTraditional: return "、"
        }
    }

    var body: some View {
        VStack(spacing: 8) {
            // ヘッダー: 言語バッジ（左）+ 折りたたみ/設定（右）
            HStack {
                Text(mode.label)
                    .font(.system(size: 14, weight: .semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 4)
                    .background(modeBadgeColor)
                    .foregroundStyle(.white)
                    .clipShape(Capsule())
                    .accessibilityLabel("入力モード: \(mode.label)")

                // 操作モードバッジ
                if gamepadInput.isTextOperationMode {
                    Text("テキスト操作")
                        .font(.system(size: 12, weight: .semibold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(.orange)
                        .foregroundStyle(.white)
                        .clipShape(Capsule())
                        .accessibilityLabel("テキスト操作モード")
                }

                // VRChat OSC モードバッジ
                if vrChatSettings.enabled {
                    Button {
                        showVrChatSettings = true
                    } label: {
                        Label("VRChat OSC", systemImage: "paperplane.fill")
                            .font(.system(size: 12, weight: .semibold))
                            .labelStyle(.titleAndIcon)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(.purple)
                            .foregroundStyle(.white)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("VRChat OSC モード 有効。タップして設定を開く")
                }

                // 中国語モード: バッファ表示（繁体字は注音、簡体字はピンイン）
                if (mode == .chineseSimplified || mode == .chineseTraditional) && !gamepadInput.pinyinBuffer.isEmpty {
                    let bufferText = mode == .chineseTraditional ? gamepadInput.zhuyinDisplayBuffer : gamepadInput.pinyinBuffer
                    Text(bufferText)
                        .font(.system(size: 16, weight: .medium, design: .monospaced))
                        .foregroundStyle(.primary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color(.systemGray5), in: RoundedRectangle(cornerRadius: 6))
                        .accessibilityLabel("入力中: \(bufferText)")
                }

                Spacer()

                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isCollapsed.toggle()
                    }
                } label: {
                    Image(systemName: isCollapsed ? "chevron.up" : "chevron.down")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                .accessibilityLabel(isCollapsed ? "ビジュアライザを展開" : "ビジュアライザを折りたたむ")

                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(.secondary)
                }
                .accessibilityLabel("設定")
            }
            .padding(.horizontal, 4)

            if !isCollapsed {
                if gamepadInput.isTextOperationMode {
                    textOperationGuide
                } else {
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
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("プレビュー: \(gamepadInput.previewChar ?? "なし")、行: \(currentRowNames[gamepadInput.activeRow])")

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
                        .accessibilityElement(children: .contain)
                        .accessibilityLabel("右スティック")
                }
                .padding()
                .background(.background, in: RoundedRectangle(cornerRadius: 16))
                } // end of normal mode
            }
        }
        .sheet(isPresented: $showSettings) {
            GamepadSettingsSheet(
                gamepadInput: gamepadInput,
                vrChatSettings: vrChatSettings,
                onOpenVrChat: {
                    showSettings = false
                    // 少し遅延させてシートの切替を安定させる
                    Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 300_000_000)
                        showVrChatSettings = true
                    }
                }
            )
        }
        .sheet(isPresented: $showVrChatSettings) {
            VrChatSettingsView(settings: vrChatSettings)
        }
    }

    // MARK: - テキスト操作モード ガイド

    private var textOperationGuide: some View {
        HStack(alignment: .top, spacing: 20) {
            guideColumn(title: "D-pad 選択", items: [
                "→ 拡大（括弧→文）",
                "← 縮小",
                "↑ 文選択（後方）",
                "↓ 文選択（前方）",
            ])
            guideColumn(title: "L🕹 フォーカス", items: [
                "←↑ 前の文頭",
                "→↓ 次の文末",
            ])
            guideColumn(title: "RB+L🕹 カーソル", items: [
                "←→ 1文字移動",
                "↑↓ 1行移動",
            ])
            guideColumn(title: "RT+L🕹 並替", items: [
                "←↑ 前へ移動",
                "→↓ 後ろへ移動",
            ])
        }
        .padding()
        .background(.background, in: RoundedRectangle(cornerRadius: 16))
    }

    private func guideColumn(title: String, items: [String]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.secondary)
            ForEach(items, id: \.self) { item in
                Text(item)
                    .font(.system(size: 13))
                    .foregroundStyle(.primary)
            }
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
        if mode == .chineseTraditional {
            let r = zhuyinTable[row]
            return (left: r[1], up: r[2], right: r[3], down: r[4])
        }
        // 簡体字: シフトなしで英語テーブルを使用
        let r = englishTable[row]
        return (left: r[1], up: r[2], right: r[3], down: r[4])
    }

    private var dpadGrid: some View {
        let useCrossLayout = mode == .english || mode == .chineseSimplified || mode == .chineseTraditional
        let offset = gamepadInput.activeLayer == .lb ? 5 : 0

        return Grid(horizontalSpacing: 4, verticalSpacing: 4) {
            GridRow {
                Color.clear.frame(width: 52, height: 52)
                if useCrossLayout {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 2 + offset), pressed: gamepadInput.pressedButtons.contains("dpadUp"))
                } else {
                    dpadButton(label: dpad.up, pressed: gamepadInput.pressedButtons.contains("dpadUp"))
                }
                Color.clear.frame(width: 52, height: 52)
            }
            GridRow {
                if useCrossLayout {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 1 + offset), pressed: gamepadInput.pressedButtons.contains("dpadLeft"))
                } else {
                    dpadButton(label: dpad.left, pressed: gamepadInput.pressedButtons.contains("dpadLeft"))
                }
                if useCrossLayout {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 0 + offset), pressed: false)
                        .opacity(0.5)
                } else {
                    Text(dpad.center)
                        .font(.system(size: 13))
                        .foregroundStyle(.quaternary)
                        .frame(width: 52, height: 52)
                        .background(.fill.quaternary, in: RoundedRectangle(cornerRadius: 8))
                }
                if useCrossLayout {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 3 + offset), pressed: gamepadInput.pressedButtons.contains("dpadRight"))
                } else {
                    dpadButton(label: dpad.right, pressed: gamepadInput.pressedButtons.contains("dpadRight"))
                }
            }
            GridRow {
                Color.clear.frame(width: 52, height: 52)
                if useCrossLayout {
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
                Color.clear.frame(width: 48, height: 48)
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

    private let stickSize: CGFloat = 36

    private var rightStickGrid: some View {
        let totalSize = stickSize * 3 + 8
        return ZStack {
            Circle()
                .fill(Color(.systemGray6))
                .frame(width: totalSize, height: totalSize)
            Grid(horizontalSpacing: 4, verticalSpacing: 4) {
                GridRow {
                    Color.clear.frame(width: stickSize, height: stickSize)
                    stickButton(label: rStickUpLabel, pressed: gamepadInput.pressedButtons.contains("rStickUp"))
                    Color.clear.frame(width: stickSize, height: stickSize)
                }
                GridRow {
                    stickButton(label: rStickLeftLabel, pressed: gamepadInput.pressedButtons.contains("rStickLeft"))
                    Color.clear.frame(width: stickSize, height: stickSize)
                    stickButton(label: rStickRightLabel, pressed: gamepadInput.pressedButtons.contains("rStickRight"))
                }
                GridRow {
                    Color.clear.frame(width: stickSize, height: stickSize)
                    stickButton(label: rStickDownLabel, pressed: gamepadInput.pressedButtons.contains("rStickDown"))
                    Color.clear.frame(width: stickSize, height: stickSize)
                }
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
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(name): \(char)")
        .accessibilityValue(pressed ? "押下中" : "")
    }

    private func faceButton(label: String, pressed: Bool) -> some View {
        Text(label)
            .font(.system(size: 16, weight: .bold))
            .frame(width: 48, height: 48)
            .background(pressed ? Color.accentColor : Color(.systemGray5), in: Circle())
            .foregroundStyle(pressed ? .white : .primary)
            .shadow(color: Self.buttonShadow, radius: 2, y: 1)
            .accessibilityLabel(label)
            .accessibilityValue(pressed ? "押下中" : "")
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
        .accessibilityElement(children: .combine)
        .accessibilityLabel([chars.up, chars.left, chars.right, chars.down].filter { !$0.isEmpty }.joined(separator: " "))
        .accessibilityValue(pressed ? "押下中" : "")
    }

    private func dpadButton(label: String, pressed: Bool) -> some View {
        Text(label)
            .font(.system(size: 13, weight: .bold))
            .frame(width: 52, height: 52)
            .background(pressed ? Color.accentColor : Color(.systemGray5).opacity(0.7), in: RoundedRectangle(cornerRadius: 8))
            .foregroundStyle(pressed ? .white : .secondary)
            .shadow(color: Self.buttonShadow, radius: 2, y: 1)
            .accessibilityLabel(label)
            .accessibilityValue(pressed ? "押下中" : "")
    }

    private func stickButton(label: String, pressed: Bool) -> some View {
        Text(label)
            .font(.system(size: 11, weight: .semibold))
            .frame(width: stickSize, height: stickSize)
            .background(pressed ? Color.accentColor : Color(.systemGray5), in: Circle())
            .foregroundStyle(pressed ? .white : .secondary)
            .accessibilityLabel(label)
            .accessibilityValue(pressed ? "押下中" : "")
    }
}

// MARK: - 設定シート

/// ビジュアライザ設定シート（言語サイクル設定）
private struct GamepadSettingsSheet: View {
    let gamepadInput: GamepadInputManager
    let vrChatSettings: VrChatOscSettings
    let onOpenVrChat: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                // VRChat OSC エントリ
                Section {
                    Button {
                        onOpenVrChat()
                    } label: {
                        HStack {
                            Label("VRChat OSC 連携", systemImage: "paperplane")
                                .foregroundStyle(.primary)
                            Spacer()
                            Text(vrChatSettings.enabled ? "ON" : "OFF")
                                .font(.caption)
                                .foregroundStyle(vrChatSettings.enabled ? .purple : .secondary)
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityLabel("VRChat OSC 連携設定を開く。現在\(vrChatSettings.enabled ? "有効" : "無効")")
                } header: {
                    Text("外部連携")
                } footer: {
                    Text("VRChat の chatbox に OSC 経由で入力を送信できます。")
                }

                // 言語サイクル設定セクション
                Section {
                    ForEach(GamepadInputMode.allCases, id: \.self) { mode in
                        Toggle(isOn: Binding(
                            get: { gamepadInput.enabledModes.contains(mode) },
                            set: { enabled in
                                if enabled {
                                    gamepadInput.enabledModes.append(mode)
                                } else {
                                    if gamepadInput.enabledModes.count > 1 {
                                        gamepadInput.enabledModes.removeAll { $0 == mode }
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
                }

                Section {
                    ForEach(gamepadInput.enabledModes, id: \.self) { mode in
                        Text(mode.label)
                    }
                    .onMove { source, destination in
                        gamepadInput.enabledModes.move(fromOffsets: source, toOffset: destination)
                    }
                } header: {
                    Text("切替順序")
                } footer: {
                    Text("ハンドルをドラッグして切り替え順を変更できます。")
                }
                // このアプリについて
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("GiME")
                            .font(.headline)
                        Text(Self.versionString)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text("© 2024-2026 Masao Narumi")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                } header: {
                    Text("このアプリについて")
                }

                // オープンソースライセンス
                Section {
                    DisclosureGroup("AzooKeyKanaKanjiConverter") {
                        Text("MIT License — Copyright (c) 2023 Miwa / Ensan")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        DisclosureGroup("ライセンス全文") {
                            Text(Self.mitLicenseText)
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundStyle(.secondary)
                        }
                        .font(.caption)
                    }
                    .font(.subheadline)

                    DisclosureGroup("CC-CEDICT") {
                        Text("Creative Commons Attribution-ShareAlike 4.0 International")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text("簡体字辞書データの語彙・ピンイン情報に使用")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .font(.subheadline)

                    DisclosureGroup("libchewing") {
                        Text("LGPL v2.1 — libchewing contributors")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text("繁體字辞書データの語彙・注音情報に使用")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .font(.subheadline)
                } header: {
                    Text("オープンソースライセンス")
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完了") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // MARK: - 定数

    private static var versionString: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—"
        return "v\(version) (\(build))"
    }

    private static let mitLicenseText = """
        MIT License

        Copyright (c) 2023 Miwa / Ensan

        Permission is hereby granted, free of charge, to any person obtaining a copy \
        of this software and associated documentation files (the "Software"), to deal \
        in the Software without restriction, including without limitation the rights \
        to use, copy, modify, merge, publish, distribute, sublicense, and/or sell \
        copies of the Software, and to permit persons to whom the Software is \
        furnished to do so, subject to the following conditions:

        The above copyright notice and this permission notice shall be included in all \
        copies or substantial portions of the Software.

        THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR \
        IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, \
        FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE \
        AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER \
        LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, \
        OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE \
        SOFTWARE.
        """
}
