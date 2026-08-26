import SwiftUI

/// ゲームパッドビジュアライザ（SwiftUI）
/// Web 版 GamepadVisualizer.tsx の Swift 移植
struct GamepadVisualizerView: View {
    let gamepadInput: GamepadInputManager

    @State private var showSettings = false
    @State private var isCollapsed = false

    /// iPhone など狭幅では spacing / padding を詰めてはみ出しを防ぐ
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private var isCompactWidth: Bool {
        horizontalSizeClass == .compact
    }

    /// レイアウト寸法。iPad (regular) と iPhone (compact) で別セット。
    /// 中段の合計幅 = stickOuter + spacing + dpadCell*3 + spacing + faceCell*3 + spacing + stickOuter
    /// 親ビュー側の余白 (`.padding([.horizontal, .top])` ≈ 16pt × 2) と本体の outerPadding
    /// (compact 8pt × 2) を含めて iPhone SE (375pt) でも収まるよう compact を絞っている。
    /// compact: 44 + 4 + 38*3 + 4 + 38*3 + 4 + 44 = 328pt + padding 16+32 = 376pt → ギリギリ。
    /// regular: 60 + 24 + 52*3 + 24 + 48*3 + 24 + 60 = 492pt → iPad では余裕。
    private struct VizMetrics {
        let dpadCell: CGFloat
        let faceCell: CGFloat
        let stickOuter: CGFloat
        let stickCell: CGFloat
        let stickGap: CGFloat
        let shoulderMinW: CGFloat
        let shoulderMinH: CGFloat
        let columnSpacing: CGFloat
        let outerPadding: CGFloat
        let dpadFontSize: CGFloat
        let faceFontSize: CGFloat
        let shoulderFontSize: CGFloat
        let shoulderNameFontSize: CGFloat
        let stickFontSize: CGFloat

        static let regular = VizMetrics(
            dpadCell: 52, faceCell: 48,
            stickOuter: 60, stickCell: 18, stickGap: 2,
            shoulderMinW: 52, shoulderMinH: 44,
            columnSpacing: 24, outerPadding: 16,
            dpadFontSize: 13, faceFontSize: 16,
            shoulderFontSize: 16, shoulderNameFontSize: 10,
            stickFontSize: 9
        )
        static let compact = VizMetrics(
            dpadCell: 38, faceCell: 38,
            stickOuter: 44, stickCell: 13, stickGap: 1,
            shoulderMinW: 42, shoulderMinH: 36,
            columnSpacing: 4, outerPadding: 6,
            dpadFontSize: 11, faceFontSize: 13,
            shoulderFontSize: 12, shoulderNameFontSize: 9,
            stickFontSize: 8
        )
    }

    private var m: VizMetrics { isCompactWidth ? .compact : .regular }

    /// スティックの役割（左 / 右）。レイアウト・ラベル解決の分岐に使用。
    private enum StickRole { case left, right }

    private var mode: GamepadInputMode { gamepadInput.currentMode }

    private var isLTPressed: Bool {
        gamepadInput.pressedButtons.contains("LT")
    }

    private var dpad: DpadLabels {
        switch mode {
        case .japanese:
            return gamepadInput.activeLayer == .lb ? dpadLabelsLB : dpadLabelsBase
        case .english:
            return gamepadInput.activeLayer == .lb ? englishDpadLabelsLB : englishDpadLabelsBase
        case .korean:
            return gamepadInput.activeLayer == .lb ? koreanDpadLabelsLB : koreanDpadLabelsBase
        case .devanagari:
            // 非 varga サブレイヤー中 / 通常（varga 時計回り）で表示を切替。
            // 非 varga: LT 押下で sibilant (श ष स ह) / 離し時 semivowel (य र ल व)
            // 通常: LS 方向で varga を選び、D-pad が varga 内 stop を表示
            let chars: [String]
            if gamepadInput.devaNonVargaActive {
                chars = devaNonVargaDisplayChars(ltPressed: isLTPressed)
            } else {
                chars = devaVargaDisplayChars(resolveDevaVarga(gamepadInput.devaLsDir))
            }
            // [center, left, up, right, down]
            return DpadLabels(
                center: chars[0], left: chars[1], up: chars[2], right: chars[3], down: chars[4]
            )
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
        case .devanagari:
            // [RB, X, Y, B, A] = [ओ/़, ए, अ, इ, उ]。LT+A は ऋ (拡張母音)。
            // RB の表示は rbLabel で個別に上書きするため、ここでは a 位置に अ を置く。
            let aChar = isLTPressed ? "ऋ" : "उ"  // A (u-slot) は LT で ऋ
            return ["अ", "ए", "अ", "इ", aChar]
        }
    }

    private var lbLabel: String {
        if mode == .devanagari {
            // 現 LS latch の varga の鼻音を表示（常時発火）
            let varga = resolveDevaVarga(gamepadInput.devaLsDir)
            return String(devaVargaConsonants[varga.rawValue][4])
        }
        if gamepadInput.activeLayer == .lb { return "●" }
        switch mode {
        case .japanese: return "は〜"
        case .english: return "pqrs〜"
        case .korean: return "ㅁ〜"
        case .devanagari: return ""
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
        case .japanese: return "拗音"
        case .devanagari: return ""  // LT 単押しは emit 無し（拡張母音/sibilant/nukta の修飾子）
        }
    }

    private var rbLabel: String {
        if mode == .devanagari {
            // LT 併用で nukta、単独で ओ
            return isLTPressed ? "़" : "ओ"
        }
        return faceChars[0]
    }

    private var rtLabel: String {
        switch mode {
        case .english: return "0"
        case .korean: return "ㅑㅕ"
        case .japanese: return "ん"
        case .devanagari: return isLTPressed ? "ः" : "्⇆"  // LT+RT=visarga、RT 単=halant、RT+LS=cursor
        }
    }

    private var modeBadgeColor: Color {
        switch mode {
        case .japanese: return .pink
        case .english: return .green
        case .korean: return .indigo
        case .devanagari: return .orange
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
        case .devanagari: return "ंँ"
        }
    }

    private var rStickDownLabel: String {
        switch mode {
        case .japanese: return "、。␣"
        case .english: return "␣.,"
        case .korean: return "␣."
        case .devanagari: return "␣।"
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
        case .devanagari: return "ा"  // 短母音 → 長母音 post-shift（schwa 状態では ा を追加）
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
                // Android 版と同じ 2 段構造:
                //  上段: [LT][LB] ─── [RB][RT]
                //  中段: [LS] [D-pad] [Face] [RS]
                // 中央プレビュー（旧）と下段テキストヒント（旧）は廃止。
                // LS/RS は内部 3×3 グリッドに方向別ラベルを直接表示する。
                VStack(spacing: 6) {
                    // 上段: ショルダー
                    HStack(alignment: .center) {
                        HStack(spacing: 4) {
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
                        Spacer()
                        HStack(spacing: 4) {
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
                    }

                    // 中段: LS | D-pad | Face | RS（等幅で中央寄せ）
                    HStack(alignment: .center, spacing: m.columnSpacing) {
                        stickGrid(role: .left)
                            .accessibilityElement(children: .contain)
                            .accessibilityLabel("左スティック")
                        dpadGrid
                        faceButtonGrid
                        stickGrid(role: .right)
                            .accessibilityElement(children: .contain)
                            .accessibilityLabel("右スティック")
                    }
                }
                .padding(m.outerPadding)
                .background(.background, in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .sheet(isPresented: $showSettings) {
            GamepadSettingsSheet(
                gamepadInput: gamepadInput
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
        let r = englishTable[row]
        return (left: r[1], up: r[2], right: r[3], down: r[4])
    }

    private var dpadGrid: some View {
        let useCrossLayout = mode == .english
        let offset = gamepadInput.activeLayer == .lb ? 5 : 0
        let cell = m.dpadCell

        return Grid(horizontalSpacing: 4, verticalSpacing: 4) {
            GridRow {
                Color.clear.frame(width: cell, height: cell)
                if useCrossLayout {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 2 + offset), pressed: gamepadInput.pressedButtons.contains("dpadUp"))
                } else {
                    dpadButton(label: dpad.up, pressed: gamepadInput.pressedButtons.contains("dpadUp"))
                }
                Color.clear.frame(width: cell, height: cell)
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
                        .font(.system(size: m.dpadFontSize))
                        .foregroundStyle(.quaternary)
                        .frame(width: cell, height: cell)
                        .background(.fill.quaternary, in: RoundedRectangle(cornerRadius: 8))
                }
                if useCrossLayout {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 3 + offset), pressed: gamepadInput.pressedButtons.contains("dpadRight"))
                } else {
                    dpadButton(label: dpad.right, pressed: gamepadInput.pressedButtons.contains("dpadRight"))
                }
            }
            GridRow {
                Color.clear.frame(width: cell, height: cell)
                if useCrossLayout {
                    dpadButtonCross(chars: crossCharsForCurrentMode(row: 4 + offset), pressed: gamepadInput.pressedButtons.contains("dpadDown"))
                } else {
                    dpadButton(label: dpad.down, pressed: gamepadInput.pressedButtons.contains("dpadDown"))
                }
                Color.clear.frame(width: cell, height: cell)
            }
        }
    }

    // MARK: - フェイスボタングリッド

    private var faceButtonGrid: some View {
        let cell = m.faceCell
        return Grid(horizontalSpacing: 4, verticalSpacing: 4) {
            GridRow {
                Color.clear.frame(width: cell, height: cell)
                faceButton(label: faceChars[2], pressed: gamepadInput.pressedButtons.contains("Y"))
                Color.clear.frame(width: cell, height: cell)
            }
            GridRow {
                faceButton(label: faceChars[1], pressed: gamepadInput.pressedButtons.contains("X"))
                Color.clear.frame(width: cell, height: cell)
                faceButton(label: faceChars[3], pressed: gamepadInput.pressedButtons.contains("B"))
            }
            GridRow {
                Color.clear.frame(width: cell, height: cell)
                faceButton(label: faceChars[4], pressed: gamepadInput.pressedButtons.contains("A"))
                Color.clear.frame(width: cell, height: cell)
            }
        }
    }

    // MARK: - スティックグリッド (3x3、Android 版 StickIndicator と同等)

    /// LS / RS の方向別ロール（latch 視認・cursor / 特殊アクションの矢印太さ等）を
    /// 内部 3×3 グリッドで表現する。Android 版の StickIndicator に倣う。
    private func stickGrid(role: StickRole) -> some View {
        let outer = m.stickOuter
        let cell = m.stickCell
        let pressed: Bool = role == .left
            ? gamepadInput.pressedButtons.contains("LS")
            : gamepadInput.pressedButtons.contains("RS")

        // Devanagari の非 varga モード中は LS 方向に意味がないので物理 dir / latch
        // を抑止する（方向セルが無意味にハイライトされるのを防ぐ）。
        let suppressLs = role == .left && mode == .devanagari && gamepadInput.devaNonVargaActive
        let physDir: GamepadInputManager.StickDirection = {
            if suppressLs { return .neutral }
            return role == .left ? gamepadInput.leftStickDirection : gamepadInput.rightStickDirection
        }()
        // Devanagari の LS latch を保持表示（物理 neutral でも latch 残存）。
        let latchDir: GamepadInputManager.StickDirection = {
            guard role == .left, mode == .devanagari, !gamepadInput.devaNonVargaActive else {
                return .neutral
            }
            switch gamepadInput.devaLsDir {
            case .up: return .up
            case .down: return .down
            case .left: return .left
            case .right: return .right
            case .neutral: return .neutral
            }
        }()
        let centerLabel = stickCenterLabel(role: role)

        return ZStack {
            Circle()
                .fill(Color(.systemGray6))
                .frame(width: outer, height: outer)
            VStack(spacing: m.stickGap) {
                HStack(spacing: m.stickGap) {
                    Color.clear.frame(width: cell, height: cell)
                    stickCell(label: cellLabel(role: role, dir: .up),
                              isActive: physDir == .up || latchDir == .up)
                    Color.clear.frame(width: cell, height: cell)
                }
                HStack(spacing: m.stickGap) {
                    stickCell(label: cellLabel(role: role, dir: .left),
                              isActive: physDir == .left || latchDir == .left)
                    stickCell(label: centerLabel, isActive: pressed)
                    stickCell(label: cellLabel(role: role, dir: .right),
                              isActive: physDir == .right || latchDir == .right)
                }
                HStack(spacing: m.stickGap) {
                    Color.clear.frame(width: cell, height: cell)
                    stickCell(label: cellLabel(role: role, dir: .down),
                              isActive: physDir == .down || latchDir == .down)
                    Color.clear.frame(width: cell, height: cell)
                }
            }
        }
        .frame(width: outer, height: outer)
    }

    /// 1 セルの背景。活性時は accentColor 系でハイライト（Android の primaryContainer 相当）。
    private func stickCell(label: String, isActive: Bool) -> some View {
        let cell = m.stickCell
        return ZStack {
            if isActive {
                Circle().fill(Color.accentColor.opacity(0.85))
            }
            Text(label)
                .font(.system(size: m.stickFontSize, weight: .semibold))
                .foregroundStyle(isActive ? Color.white : Color.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(width: cell, height: cell)
    }

    private func cellLabel(role: StickRole, dir: GamepadInputManager.StickDirection) -> String {
        switch role {
        case .left: return lsDirectionLabel(dir)
        case .right: return rsDirectionLabel(dir)
        }
    }

    /// LS 方向別の役割ラベル。GamepadInputManager の LS 処理ロジックと対応。
    /// カーソル系は細い ↑↓←→、特殊アクション系は太い ⇧⇩⇦⇨ で視覚的に区別する。
    private func lsDirectionLabel(_ dir: GamepadInputManager.StickDirection) -> String {
        if dir == .neutral { return "" }
        // Devanagari
        if mode == .devanagari {
            if gamepadInput.devaNonVargaActive { return "" }
            if isRTPressed { return cursorArrowLabel(dir) }
            switch dir {
            case .up: return "क"
            case .right: return "च"
            case .down: return "ट"
            case .left: return "त"
            case .neutral: return ""
            }
        }
        let im = gamepadInput.inputManager
        let isConverting = im.state == .selecting || im.state == .previewing
        // 日本語 / 変換中: 全方向が特殊アクション
        if mode == .japanese && isConverting {
            switch dir {
            case .up: return "⇧"
            case .down: return "⇩"
            case .left: return "⇦"
            case .right: return "⇨"
            case .neutral: return ""
            }
        }
        // 日本語 / 未変換バッファあり: ↓ だけ特殊（変換）、他はカーソル
        if mode == .japanese && !im.isEmpty {
            if dir == .down { return "⇩" }
            return cursorArrowLabel(dir)
        }
        return cursorArrowLabel(dir)
    }

    private func cursorArrowLabel(_ dir: GamepadInputManager.StickDirection) -> String {
        switch dir {
        case .up: return "↑"
        case .down: return "↓"
        case .left: return "←"
        case .right: return "→"
        case .neutral: return ""
        }
    }

    /// RS 方向別の役割ラベル。代表 1 字に短縮（旧 rStickXxxLabel は複数文字を含むため
    /// 3×3 セルでは縮めて表示する）。
    private func rsDirectionLabel(_ dir: GamepadInputManager.StickDirection) -> String {
        switch mode {
        case .japanese:
            switch dir {
            case .up: return "゛"
            case .down: return "、"
            case .left: return "⌫"
            case .right: return "ー"
            case .neutral: return ""
            }
        case .english:
            switch dir {
            case .up: return "'"
            case .down: return "␣"
            case .left: return "⌫"
            case .right: return "/"
            case .neutral: return ""
            }
        case .korean:
            // 자모 모드: ↑ 평격경 cycle / → 直前 jamo 連打
            if gamepadInput.isKoreanJamoMode {
                switch dir {
                case .up: return "ㅋ"
                case .down: return "␣"
                case .left: return "⌫"
                case .right: return "↻"
                case .neutral: return ""
                }
            }
            switch dir {
            case .up: return "ㅋ"
            case .down: return "␣"
            case .left: return "⌫"
            case .right: return "ㅘ"
            case .neutral: return ""
            }
        case .devanagari:
            switch dir {
            case .up: return "ं"
            case .down: return "␣"
            case .left: return "⌫"
            case .right: return "ा"
            case .neutral: return ""
            }
        }
    }

    /// スティック中央セルのラベル（クリック時の動作）。
    /// - LS / Devanagari + RT 押下: ↵ (改行)
    /// - LS / Devanagari + 非 varga モード: ✻
    /// - LS / Devanagari + 通常: 現在 latch している varga 代表子音
    /// - LS / 日本語 (変換中・未変換あり): ✓
    /// - LS / 中国語 (候補表示中): ✓
    /// - LS / その他: ↵
    /// - RS: ✕
    private func stickCenterLabel(role: StickRole) -> String {
        switch role {
        case .right: return "✕"
        case .left:
            switch mode {
            case .devanagari:
                if isRTPressed { return "↵" }
                if gamepadInput.devaNonVargaActive { return "✻" }
                let varga = resolveDevaVarga(gamepadInput.devaLsDir)
                return String(devaVargaConsonants[varga.rawValue][0])
            case .japanese:
                let im = gamepadInput.inputManager
                let isConverting = im.state == .selecting || im.state == .previewing
                return (isConverting || !im.isEmpty) ? "✓" : "↵"
            default:
                return "↵"
            }
        }
    }

    // MARK: - ボタンコンポーネント

    private static let buttonShadow: Color = .black.opacity(0.15)

    private func shoulderButton(char: String, name: String, pressed: Bool) -> some View {
        VStack(spacing: 1) {
            Text(char)
                .font(.system(size: m.shoulderFontSize, weight: .bold))
            Text(name)
                .font(.system(size: m.shoulderNameFontSize))
                .foregroundStyle(pressed ? .white.opacity(0.6) : .secondary)
        }
        .frame(minWidth: m.shoulderMinW, minHeight: m.shoulderMinH)
        .background(pressed ? Color.accentColor : Color(.systemGray5), in: RoundedRectangle(cornerRadius: 8))
        .foregroundStyle(pressed ? .white : .primary)
        .shadow(color: Self.buttonShadow, radius: 2, y: 1)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(name): \(char)")
        .accessibilityValue(pressed ? "押下中" : "")
    }

    private func faceButton(label: String, pressed: Bool) -> some View {
        Text(label)
            .font(.system(size: m.faceFontSize, weight: .bold))
            .frame(width: m.faceCell, height: m.faceCell)
            .background(pressed ? Color.accentColor : Color(.systemGray5), in: Circle())
            .foregroundStyle(pressed ? .white : .primary)
            .shadow(color: Self.buttonShadow, radius: 2, y: 1)
            .accessibilityLabel(label)
            .accessibilityValue(pressed ? "押下中" : "")
    }

    /// 英語モード: フェイスボタン位置に文字を十字配置した D-pad ボタン
    private func dpadButtonCross(chars: (left: String, up: String, right: String, down: String), pressed: Bool) -> some View {
        let cell = m.dpadCell
        let off = cell * 0.25
        return ZStack {
            if !chars.up.isEmpty {
                Text(chars.up).offset(y: -off)
            }
            if !chars.left.isEmpty {
                Text(chars.left).offset(x: -off)
            }
            if !chars.right.isEmpty {
                Text(chars.right).offset(x: off)
            }
            if !chars.down.isEmpty {
                Text(chars.down).offset(y: off)
            }
        }
        .font(.system(size: m.dpadFontSize, weight: .bold))
        .frame(width: cell, height: cell)
        .background(pressed ? Color.accentColor : Color(.systemGray5).opacity(0.7), in: RoundedRectangle(cornerRadius: 8))
        .foregroundStyle(pressed ? .white : .secondary)
        .shadow(color: Self.buttonShadow, radius: 2, y: 1)
        .accessibilityElement(children: .combine)
        .accessibilityLabel([chars.up, chars.left, chars.right, chars.down].filter { !$0.isEmpty }.joined(separator: " "))
        .accessibilityValue(pressed ? "押下中" : "")
    }

    private func dpadButton(label: String, pressed: Bool) -> some View {
        Text(label)
            .font(.system(size: m.dpadFontSize, weight: .bold))
            .frame(width: m.dpadCell, height: m.dpadCell)
            .background(pressed ? Color.accentColor : Color(.systemGray5).opacity(0.7), in: RoundedRectangle(cornerRadius: 8))
            .foregroundStyle(pressed ? .white : .secondary)
            .shadow(color: Self.buttonShadow, radius: 2, y: 1)
            .accessibilityLabel(label)
            .accessibilityValue(pressed ? "押下中" : "")
    }
}

// MARK: - 設定シート

/// ビジュアライザ設定シート（言語サイクル設定）
private struct GamepadSettingsSheet: View {
    let gamepadInput: GamepadInputManager

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
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
