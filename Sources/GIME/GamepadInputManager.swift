import GameController
import Observation
import KeyLogicKit

/// ゲームパッドのボタン・スティック状態のスナップショット（Sendable）
/// GCExtendedGamepad は non-Sendable なので、コールバック内で値を詰め替えてから MainActor に渡す
struct GamepadSnapshot: Sendable {
    var dpadUp = false
    var dpadDown = false
    var dpadLeft = false
    var dpadRight = false
    var buttonA = false
    var buttonB = false
    var buttonX = false
    var buttonY = false
    var lb = false
    var rb = false
    var ltValue: Float = 0
    var rtValue: Float = 0
    var start = false
    var back = false
    var lsClick = false
    var rsClick = false
    var rightStickX: Float = 0
    var rightStickY: Float = 0
    var leftStickX: Float = 0
    var leftStickY: Float = 0

    init() {}

    init(_ gp: GCExtendedGamepad) {
        dpadUp = gp.dpad.up.isPressed
        dpadDown = gp.dpad.down.isPressed
        dpadLeft = gp.dpad.left.isPressed
        dpadRight = gp.dpad.right.isPressed
        buttonA = gp.buttonA.isPressed
        buttonB = gp.buttonB.isPressed
        buttonX = gp.buttonX.isPressed
        buttonY = gp.buttonY.isPressed
        lb = gp.leftShoulder.isPressed
        rb = gp.rightShoulder.isPressed
        ltValue = gp.leftTrigger.value
        rtValue = gp.rightTrigger.value
        start = gp.buttonMenu.isPressed
        back = gp.buttonOptions?.isPressed ?? false
        lsClick = gp.leftThumbstickButton?.isPressed ?? false
        rsClick = gp.rightThumbstickButton?.isPressed ?? false
        rightStickX = gp.rightThumbstick.xAxis.value
        rightStickY = gp.rightThumbstick.yAxis.value
        leftStickX = gp.leftThumbstick.xAxis.value
        leftStickY = gp.leftThumbstick.yAxis.value
    }
}

/// ゲームパッド入力を管理し、GamepadResolver → InputManager パイプラインを駆動する
@MainActor
@Observable
final class GamepadInputManager {
    // MARK: - Public State

    private(set) var gamepadName: String?
    var isConnected: Bool { gamepadName != nil }
    private(set) var activeRow: Int = 0
    private(set) var activeVowel: VowelButton?
    private(set) var previewChar: String?
    private(set) var activeLayer: ActiveLayer = .base
    private(set) var pressedButtons: Set<String> = []
    private(set) var leftStickDirection: StickDirection = .neutral
    private(set) var currentMode: GamepadInputMode = .japanese

    /// 中国語モード判定（簡体/繁体共通のロジック用）
    var isChinese: Bool {
        currentMode == .chineseSimplified || currentMode == .chineseTraditional
    }

    /// Start ボタンでサイクルする言語の有効/無効設定
    var enabledModes: Set<GamepadInputMode> = Set(GamepadInputMode.allCases)

    enum ActiveLayer { case base, lb }

    enum StickDirection {
        case neutral, up, down, left, right
    }

    /// デバッグログ出力コールバック
    var debugLog: ((String) -> Void)?

    /// カーソル移動コールバック（idle 時の左スティック左右）
    /// - Parameter offset: 移動量（負=左、正=右）
    var onCursorMove: ((_ offset: Int) -> Void)?

    /// カーソル上下移動コールバック（左スティック上下）
    /// - Parameter direction: 負=上、正=下
    var onCursorMoveVertical: ((_ direction: Int) -> Void)?

    /// 削除コールバック（idle 時の右スティック←）
    var onDeleteBackward: (() -> Void)?

    /// 直接テキスト挿入コールバック（英語モード用、IME バイパス）
    /// - Parameters:
    ///   - text: 挿入する文字列
    ///   - replaceCount: 置換する直前の文字数（0 = 追加）
    var onDirectInsert: ((_ text: String, _ replaceCount: Int) -> Void)?

    /// Start+Back 同時押しコールバック（テキスト全文を共有）
    var onShareText: (() -> Void)?

    // MARK: - Dependencies

    let inputManager: InputManager

    // MARK: - Private State

    private let chordWindow: TimeInterval = 0.300
    private let doubleTapWindow: TimeInterval = 0.400
    private let stickThreshold: Float = 0.5

    private var eagerChar: String?
    private var eagerCharLen: Int = 0
    private var eagerTime: TimeInterval = 0
    private var prevRow: Int = 0
    private var prevVowel: VowelButton?
    private var prevConsonantCount: Int = 0

    private var rtDuringLT = false
    private var rtUsed = false
    private var prevLT = false
    private var prevRT = false

    private var lastCommaTime: TimeInterval = 0

    // 韓国語モード: R🕹↓ のスペース→カンマ→ピリオド トグル
    private var lastKoreanDownTime: TimeInterval = 0
    private var koreanDownTapCount: Int = 0

    private var prevRStickUp = false
    private var prevRStickDown = false
    private var prevRStickLeft = false
    private var prevRStickRight = false

    private var prevLStickUp = false
    private var prevLStickDown = false
    private var prevLStickLeft = false
    private var prevLStickRight = false

    private var prevBack = false
    private var startBackComboFired = false
    private var prevLS = false
    private var prevRS = false
    private var prevStart = false

    // 英語モード状態（ビジュアライザから参照可能）
    private(set) var englishShiftNext = false
    private(set) var englishCapsLock = false
    private(set) var englishSmartCaps = false
    private var englishLTHolding = false
    private var lastLTReleaseTime: TimeInterval = 0
    private var ltPressTime: TimeInterval = 0
    private let longPressThreshold: TimeInterval = 0.500
    private var lastRTSpaceTime: TimeInterval = 0
    private var lastRTWasSpace = false

    // 韓国語モード状態
    private var koreanComposer = KoreanComposer()
    private var pendingPatchimRow: Int?  // 받침の1フレーム遅延発火用

    // 中国語モード状態
    private(set) var pinyinBuffer: String = ""
    private(set) var pinyinCandidates: [PinyinCandidate] = []
    private(set) var pinyinSelectedIndex: Int = 0
    var pinyinEngine: PinyinEngine?

    // MARK: - Init

    init(inputManager: InputManager) {
        self.inputManager = inputManager
        setupControllerObservation()
    }

    // MARK: - GCController 接続監視

    private nonisolated func setupControllerObservation() {
        NotificationCenter.default.addObserver(
            forName: .GCControllerDidConnect,
            object: nil, queue: .main
        ) { [weak self] notification in
            guard let controller = notification.object as? GCController else { return }
            self?.attachGamepad(controller)
        }
        NotificationCenter.default.addObserver(
            forName: .GCControllerDidDisconnect,
            object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.gamepadName = nil
                self?.pressedButtons = []
            }
        }
        if let controller = GCController.controllers().first {
            attachGamepad(controller)
        }
    }

    private nonisolated func attachGamepad(_ controller: GCController) {
        let name = controller.vendorName ?? controller.productCategory
        Task { @MainActor [weak self] in self?.gamepadName = name }

        controller.extendedGamepad?.valueChangedHandler = { [weak self] gp, _ in
            let snapshot = GamepadSnapshot(gp)
            Task { @MainActor [weak self] in self?.handleSnapshot(snapshot) }
        }
    }

    // MARK: - メイン入力処理

    private func handleSnapshot(_ gp: GamepadSnapshot) {
        let now = ProcessInfo.processInfo.systemUptime

        let consonant = ConsonantState(
            dpadUp: gp.dpadUp,
            dpadDown: gp.dpadDown,
            dpadLeft: gp.dpadLeft,
            dpadRight: gp.dpadRight,
            lb: gp.lb
        )
        let vowel = resolveVowel(gp)
        let vowelNow = vowel != nil
        let ltNow = gp.ltValue > stickThreshold
        let rtNow = gp.rtValue > stickThreshold
        let lbNow = gp.lb

        let row = resolveConsonantRow(consonant)

        // --- UI 状態更新 ---
        activeRow = row
        activeVowel = vowel
        activeLayer = lbNow ? .lb : .base
        updatePressedButtons(gp)

        // プレビュー文字（モード別テーブル）
        let v = vowel?.rawValue ?? 0
        switch currentMode {
        case .japanese:
            previewChar = vowelNow ? kanaTable[row][v] : nil
        case .english:
            if vowelNow {
                let char = englishTable[row][v]
                if char.isEmpty { previewChar = nil }
                else if englishCapsLock || englishSmartCaps || englishShiftNext { previewChar = char.uppercased() }
                else { previewChar = char }
            } else {
                previewChar = nil
            }
        case .korean:
            if vowelNow {
                let onsetIdx = koreanOnsetForRow[row]
                let nucleusIdx = rtNow ? koreanNucleusShifted[v] : koreanNucleusBase[v]
                let code = 0xAC00 + (onsetIdx * 21 + nucleusIdx) * 28
                previewChar = String(Character(UnicodeScalar(code)!))
            } else if ltNow {
                previewChar = "ㅇ"
            } else if lbNow || gp.dpadUp || gp.dpadDown || gp.dpadLeft || gp.dpadRight {
                previewChar = koreanRowNames[row]
            } else {
                previewChar = nil
            }
        case .chineseSimplified:
            if vowelNow {
                let char = englishTable[row][v]
                if char.isEmpty { previewChar = nil }
                else { previewChar = char }
            } else {
                previewChar = nil
            }
        case .chineseTraditional:
            if vowelNow {
                let char = zhuyinTable[row][v]
                if char.isEmpty { previewChar = nil }
                else { previewChar = char }
            } else {
                previewChar = nil
            }
        }

        // === モード別の文字入力・トリガー処理 ===
        switch currentMode {
        case .japanese:
            handleJapaneseInput(gp, row: row, vowel: vowel, ltNow: ltNow, rtNow: rtNow, now: now)
        case .english:
            handleEnglishInput(gp, row: row, vowel: vowel, ltNow: ltNow, rtNow: rtNow, now: now)
        case .korean:
            handleKoreanInput(gp, row: row, vowel: vowel, ltNow: ltNow, rtNow: rtNow, now: now)
        case .chineseSimplified:
            handleChineseInput(gp, row: row, vowel: vowel, ltNow: ltNow, rtNow: rtNow, now: now)
        case .chineseTraditional:
            handleZhuyinInput(gp, row: row, vowel: vowel, ltNow: ltNow, rtNow: rtNow, now: now)
        }

        // === 右スティック（共通: ← バックスペース） ===
        let rsX = gp.rightStickX
        let rsY = gp.rightStickY
        let absX = abs(rsX)
        let absY = abs(rsY)
        let maxAxis = max(absX, absY)
        let dominant: String? = maxAxis > stickThreshold ? (absX > absY ? "x" : "y") : nil

        let rStickRight = dominant == "x" && rsX > 0
        let rStickLeft = dominant == "x" && rsX < 0
        let rStickUp = dominant == "y" && rsY > 0
        let rStickDown = dominant == "y" && rsY < 0

        if rStickLeft && !prevRStickLeft {
            if isChinese && !pinyinBuffer.isEmpty {
                pinyinBuffer.removeLast()
                updatePinyinCandidates()
            } else {
                executeAction(.deleteBack)
            }
        }

        switch currentMode {
        case .japanese:
            if rStickUp && !prevRStickUp { executeAction(.toggleDakuten) }
            if rStickRight && !prevRStickRight { executeAction(.longVowel) }
            if rStickDown && !prevRStickDown {
                if (now - lastCommaTime) < doubleTapWindow {
                    executeAction(.punctuation(isSecond: true))
                    lastCommaTime = 0
                } else {
                    executeAction(.punctuation(isSecond: false))
                    lastCommaTime = now
                }
            }
        case .english:
            if rStickUp && !prevRStickUp { onDirectInsert?("'", 0) }
            if rStickRight && !prevRStickRight {
                // スペース / 2度押しでピリオド置換
                if lastRTWasSpace && (now - lastRTSpaceTime) < doubleTapWindow {
                    onDirectInsert?(".", 1)
                    lastRTWasSpace = false
                } else {
                    onDirectInsert?(" ", 0)
                    lastRTWasSpace = true
                    lastRTSpaceTime = now
                }
                englishSmartCaps = false
            }
            if rStickDown && !prevRStickDown {
                onDirectInsert?(",", 0)
                englishSmartCaps = false
            }
        case .korean:
            // ↑ 子音サイクル（平音→激音→濃音）
            if rStickUp && !prevRStickUp { handleKoreanOnsetCycle() }
            // → 複合母音（ㅏ/ㅓ付加: ㅗ→ㅘ, ㅜ→ㅝ）
            if rStickRight && !prevRStickRight { handleKoreanVowelAddAEo() }
            // ↓ スペース→カンマ→ピリオド（トグル）
        case .chineseSimplified, .chineseTraditional:
            // → スペース（バッファあれば先に先頭候補を確定）
            if rStickRight && !prevRStickRight {
                confirmPinyinTopCandidate()
                onDirectInsert?(" ", 0)
            }
            // ↓ カンマ・ピリオド
            if rStickDown && !prevRStickDown {
                confirmPinyinTopCandidate()
                if (now - lastCommaTime) < doubleTapWindow {
                    onDirectInsert?("。", 1)
                    lastCommaTime = 0
                } else {
                    onDirectInsert?("，", 0)
                    lastCommaTime = now
                }
            }
            if rStickDown && !prevRStickDown {
                koreanComposer.commit()
                if (now - lastKoreanDownTime) < doubleTapWindow {
                    koreanDownTapCount += 1
                    if koreanDownTapCount == 1 {
                        // 2回目: スペース→カンマに差し替え
                        onDirectInsert?(",", 1)
                    } else {
                        // 3回目: カンマ→ピリオドに差し替え
                        onDirectInsert?(".", 1)
                        koreanDownTapCount = 0
                        lastKoreanDownTime = 0
                    }
                } else {
                    // 1回目: スペース
                    onDirectInsert?(" ", 0)
                    koreanDownTapCount = 0
                }
                lastKoreanDownTime = now
            }
        }

        prevRStickUp = rStickUp
        prevRStickDown = rStickDown
        prevRStickLeft = rStickLeft
        prevRStickRight = rStickRight

        // === 左スティック ===
        let lsX = gp.leftStickX
        let lsY = gp.leftStickY
        let lAbsX = abs(lsX)
        let lAbsY = abs(lsY)
        let lMaxAxis = max(lAbsX, lAbsY)
        let lDominant: String? = lMaxAxis > stickThreshold ? (lAbsX > lAbsY ? "x" : "y") : nil

        let lStickRight = lDominant == "x" && lsX > 0
        let lStickLeft = lDominant == "x" && lsX < 0
        let lStickUp = lDominant == "y" && lsY > 0
        let lStickDown = lDominant == "y" && lsY < 0

        if lStickDown { leftStickDirection = .down }
        else if lStickRight { leftStickDirection = .right }
        else if lStickLeft { leftStickDirection = .left }
        else if lStickUp { leftStickDirection = .up }
        else { leftStickDirection = .neutral }

        if isChinese && !pinyinCandidates.isEmpty {
            // 中国語候補選択中: ↑↓ で候補移動
            if lStickDown && !prevLStickDown {
                pinyinSelectedIndex = min(pinyinSelectedIndex + 1, pinyinCandidates.count - 1)
            }
            if lStickUp && !prevLStickUp {
                pinyinSelectedIndex = max(pinyinSelectedIndex - 1, 0)
            }
            // ←→ はカーソル移動
            if lStickRight && !prevLStickRight { onCursorMove?(1) }
            if lStickLeft && !prevLStickLeft { onCursorMove?(-1) }
        } else if currentMode == .english || currentMode == .korean || isChinese {
            // 英語/韓国語/中国語（候補なし）: 常にカーソル移動（上下左右）
            if lStickRight && !prevLStickRight { onCursorMove?(1) }
            if lStickLeft && !prevLStickLeft { onCursorMove?(-1) }
            if lStickUp && !prevLStickUp { onCursorMoveVertical?(-1) }
            if lStickDown && !prevLStickDown { onCursorMoveVertical?(1) }
        } else if inputManager.isEmpty {
            // 日本語 idle: 上下左右カーソル移動
            if lStickRight && !prevLStickRight { executeAction(.cursorRight) }
            if lStickLeft && !prevLStickLeft { executeAction(.cursorLeft) }
            if lStickUp && !prevLStickUp { onCursorMoveVertical?(-1) }
            if lStickDown && !prevLStickDown { onCursorMoveVertical?(1) }
        } else {
            if lStickDown && !prevLStickDown { executeAction(.convert) }
            if lStickUp && !prevLStickUp { executeAction(.prevCandidate) }
            if lStickRight && !prevLStickRight { executeAction(.expandSegment) }
            if lStickLeft && !prevLStickLeft { executeAction(.shrinkSegment) }
        }

        prevLStickUp = lStickUp
        prevLStickDown = lStickDown
        prevLStickLeft = lStickLeft
        prevLStickRight = lStickRight

        // === Back=スペース, LS=確定/改行, RS=キャンセル ===
        if currentMode == .japanese {
            if prevBack && !gp.back { executeAction(.space) }
        } else {
            if prevBack && !gp.back {
                if currentMode == .korean { koreanComposer.commit() }
                if isChinese { confirmPinyinTopCandidate() }
                onDirectInsert?(" ", 0)
            }
        }
        if prevLS && !gp.lsClick {
            if isChinese && !pinyinCandidates.isEmpty {
                confirmPinyinSelectedCandidate()
            } else {
                executeAction(.confirmOrNewline)
            }
        }
        if prevRS && !gp.rsClick {
            if isChinese && !pinyinBuffer.isEmpty {
                clearPinyinState()
            } else {
                executeAction(.cancel)
            }
        }

        // === Start+Back 同時押し=テキスト共有, Start 単体=モード切替 ===
        let startBackCombo = gp.start && gp.back
        if startBackCombo && !startBackComboFired {
            startBackComboFired = true
            onShareText?()
        }
        if !gp.start && !gp.back {
            startBackComboFired = false
        }

        if prevStart && !gp.start && !startBackComboFired {
            if !inputManager.isEmpty {
                _ = inputManager.confirmAll()
            }
            currentMode = currentMode.next(enabledModes: enabledModes)
            eagerChar = nil
            englishShiftNext = false
            englishCapsLock = false
            englishSmartCaps = false
            lastRTWasSpace = false
            koreanComposer.commit()
            pendingPatchimRow = nil
            clearPinyinState()
            // 中国語モードの variant を同期
            if currentMode == .chineseSimplified {
                pinyinEngine?.variant = .simplified
            } else if currentMode == .chineseTraditional {
                pinyinEngine?.variant = .traditional
            }
        }

        // --- 前フレーム状態更新 ---
        prevRow = row
        prevVowel = vowelNow ? vowel : nil
        prevLT = ltNow
        prevRT = rtNow
        prevBack = gp.back
        prevLS = gp.lsClick
        prevRS = gp.rsClick
        prevStart = gp.start
    }

    // MARK: - 日本語入力

    private func handleJapaneseInput(_ gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
                                     ltNow: Bool, rtNow: Bool, now: TimeInterval) {
        let vowelNow = vowel != nil
        let v = vowel?.rawValue ?? 0
        let consonantCount = [gp.dpadUp, gp.dpadDown, gp.dpadLeft, gp.dpadRight, gp.lb]
            .filter { $0 }.count

        if vowelNow {
            let char = kanaTable[row][v]
            let rowChanged = row != prevRow
            let vowelChanged = vowel != prevVowel

            if prevVowel == nil {
                executeAction(.kana(char))
                eagerChar = char
                eagerCharLen = 1
                eagerTime = now
            } else if rowChanged || vowelChanged {
                let consonantReleased = rowChanged && consonantCount < prevConsonantCount
                if !consonantReleased {
                    if eagerChar != nil && (now - eagerTime) < chordWindow {
                        executeAction(.kana(char, replaceCount: eagerCharLen))
                    } else {
                        executeAction(.kana(char))
                    }
                    eagerChar = char
                    eagerCharLen = 1
                    eagerTime = now
                }
            }
        }
        if prevVowel != nil && !vowelNow {
            eagerChar = nil
        }
        prevConsonantCount = consonantCount

        // LT: 拗音後置シフト / LT+RT=っ
        if ltNow && !prevLT { rtDuringLT = false }
        if ltNow && rtNow { rtDuringLT = true; rtUsed = true }
        if !ltNow && prevLT {
            if rtDuringLT {
                executeAction(.kana("っ"))
            } else {
                executeAction(.youon)
            }
            rtDuringLT = false
        }

        // RT 単押し → ん
        if rtNow && !prevRT { rtUsed = false }
        if !rtNow && prevRT {
            if !rtUsed && !ltNow {
                executeAction(.kana("ん"))
            }
            rtUsed = false
        }
    }

    // MARK: - 英語入力

    private func handleEnglishInput(_ gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
                                    ltNow: Bool, rtNow: Bool, now: TimeInterval) {
        let vowelNow = vowel != nil
        let v = vowel?.rawValue ?? 0
        let consonantCount = [gp.dpadUp, gp.dpadDown, gp.dpadLeft, gp.dpadRight, gp.lb]
            .filter { $0 }.count

        if vowelNow {
            var char = englishTable[row][v]
            guard !char.isEmpty else { return }

            // シフト適用
            if englishCapsLock || englishSmartCaps || englishShiftNext {
                char = char.uppercased()
                if englishShiftNext { englishShiftNext = false }
            }

            let rowChanged = row != prevRow
            let vowelChanged = vowel != prevVowel

            if prevVowel == nil {
                onDirectInsert?(char, 0)
                eagerChar = char
                eagerCharLen = 1
                eagerTime = now
            } else if rowChanged || vowelChanged {
                let consonantReleased = rowChanged && consonantCount < prevConsonantCount
                if !consonantReleased {
                    if eagerChar != nil && (now - eagerTime) < chordWindow {
                        onDirectInsert?(char, eagerCharLen)
                    } else {
                        onDirectInsert?(char, 0)
                    }
                    eagerChar = char
                    eagerCharLen = 1
                    eagerTime = now
                }
            }
        }
        if prevVowel != nil && !vowelNow {
            eagerChar = nil
        }
        prevConsonantCount = consonantCount

        // LT 押下開始: 時刻記録
        if ltNow && !prevLT {
            ltPressTime = now
            englishLTHolding = true
        }

        // LT 押下中: 長押し閾値到達でリアルタイムに Caps Lock 切替
        if ltNow && englishLTHolding && (now - ltPressTime) >= longPressThreshold {
            englishCapsLock.toggle()
            englishSmartCaps = false
            englishShiftNext = false
            englishLTHolding = false  // 1回だけ発火
        }

        // LT リリース: 押下時間で判定（長押しは押下中に処理済み）
        if !ltNow && prevLT {
            if englishLTHolding {
                // 長押し閾値未到達 → 短押し判定
                if (now - lastLTReleaseTime) < doubleTapWindow {
                    // 短押し2度押し → スマート Caps Lock
                    englishSmartCaps = true
                    englishShiftNext = false
                } else {
                    // 短押し → 次の1文字だけ大文字
                    englishShiftNext = true
                }
            }
            englishLTHolding = false
            lastLTReleaseTime = now
        }

        // RT: 数字「0」入力
        if rtNow && !prevRT { rtUsed = false }
        if !rtNow && prevRT {
            if !rtUsed && !ltNow {
                onDirectInsert?("0", 0)
            }
            rtUsed = false
        }
    }

    // MARK: - 韓国語入力

    private func handleKoreanInput(_ gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
                                   ltNow: Bool, rtNow: Bool, now: TimeInterval) {
        let vowelNow = vowel != nil
        let v = vowel?.rawValue ?? 0
        let consonantCount = [gp.dpadUp, gp.dpadDown, gp.dpadLeft, gp.dpadRight, gp.lb]
            .filter { $0 }.count
        let dpadActive = consonantCount > 0
        let prevDpadActive = prevConsonantCount > 0

        // === 子音+母音 同時押し → 音節入力 ===
        // RT 同時押しで y系母音
        if vowelNow {
            let onsetIdx = koreanOnsetForRow[row]
            let nucleusIdx = rtNow ? koreanNucleusShifted[v] : koreanNucleusBase[v]

            let rowChanged = row != prevRow
            let vowelChanged = vowel != prevVowel

            if prevVowel == nil {
                let output = koreanComposer.inputSyllable(onset: onsetIdx, nucleus: nucleusIdx)
                onDirectInsert?(output.text, output.replaceCount)
                eagerChar = output.text
                eagerCharLen = 1
                eagerTime = now
                if rtNow { rtUsed = true }
            } else if rowChanged || vowelChanged {
                let consonantReleased = rowChanged && consonantCount < prevConsonantCount
                if !consonantReleased {
                    let output = koreanComposer.inputSyllable(onset: onsetIdx, nucleus: nucleusIdx)
                    if eagerChar != nil && (now - eagerTime) < chordWindow {
                        onDirectInsert?(output.text, eagerCharLen)
                    } else {
                        onDirectInsert?(output.text, output.replaceCount)
                    }
                    eagerChar = output.text
                    eagerCharLen = 1
                    eagerTime = now
                    if rtNow { rtUsed = true }
                }
            }
        }
        if prevVowel != nil && !vowelNow {
            eagerChar = nil
        }
        prevConsonantCount = consonantCount

        // === 받침入力（2ボル式スタイル、1フレーム遅延） ===
        // 子音単独（母音なし） → 받침候補を記録、次フレームで安定していたら発火
        // LB+↑ 等の chord 途中で LB 単独が誤って받침になる問題を防ぐ

        // 1. 前フレームの받침候補を確認（セットより先にチェック）
        if let pending = pendingPatchimRow {
            if row == pending && !vowelNow && dpadActive && koreanComposer.isComposing {
                // row が安定 → 받침発火
                let codaIdx = koreanCodaForRow[pending]
                handleKoreanPatchim(codaIndex: codaIdx, codaRow: pending)
                pendingPatchimRow = nil
            } else {
                // row が変わった or 母音が来た or D-pad離した → キャンセル
                pendingPatchimRow = nil
            }
        }

        // 2. D-pad 単独エッジ → 받침候補を記録（まだ発火しない）
        if !vowelNow && dpadActive && pendingPatchimRow == nil {
            let dpadEdge = !prevDpadActive || row != prevRow
            let consonantReleased = dpadEdge && consonantCount < prevConsonantCount
            if dpadEdge && !consonantReleased && koreanComposer.isComposing {
                pendingPatchimRow = row
            }
        }

        // 3. LT エッジ（母音なし、D-pad なし） → ㅇ받침（即発火、遅延不要）
        //    既に받침がある場合は無視（誤上書き防止）
        if ltNow && !prevLT && !vowelNow && !dpadActive {
            if koreanComposer.isComposing && koreanComposer.currentCoda == nil {
                let codaIdx = koreanCodaForRow[0]  // Row 0 = ㅇ
                handleKoreanPatchim(codaIndex: codaIdx, codaRow: 0)
            }
        }

        // === RT: 単押しリリースで複合母音（ㅣ付加）、同時押しは y系（上で処理済み） ===
        if rtNow && !prevRT { rtUsed = false }
        if !rtNow && prevRT {
            if !rtUsed && !ltNow {
                handleKoreanVowelAddI()
            }
            rtUsed = false
        }
    }

    // MARK: - 韓国語ヘルパー

    /// 받침入力（겹받침・上書き対応）
    private func handleKoreanPatchim(codaIndex: Int, codaRow: Int) {
        guard case .added(let output) = koreanComposer.inputPatchim(codaIndex: codaIndex, codaRow: codaRow) else { return }
        onDirectInsert?(output.text, output.replaceCount)
    }

    /// 子音サイクル（平音→激音→濃音→平音）
    /// 子音サイクル（받침があれば받침を、なければ초성をサイクル）
    private func handleKoreanOnsetCycle() {
        // 받침があれば받침をサイクル
        if let currentCoda = koreanComposer.currentCoda,
           let nextCoda = koreanCodaCycle[currentCoda],
           let output = koreanComposer.modifyCoda(to: nextCoda) {
            onDirectInsert?(output.text, output.replaceCount)
            return
        }
        // なければ초성をサイクル
        guard let currentOnset = koreanComposer.currentOnset,
              let nextOnset = koreanOnsetCycle[currentOnset],
              let output = koreanComposer.modifyOnset(to: nextOnset) else { return }
        onDirectInsert?(output.text, output.replaceCount)
    }

    /// 複合母音（ㅣ付加: ㅏ→ㅐ, ㅓ→ㅔ, ㅗ→ㅚ 等）
    private func handleKoreanVowelAddI() {
        guard let currentNucleus = koreanComposer.currentNucleus,
              let newNucleus = koreanNucleusAddI[currentNucleus],
              let output = koreanComposer.modifyNucleus(to: newNucleus) else { return }
        onDirectInsert?(output.text, output.replaceCount)
    }

    /// 複合母音（ㅏ/ㅓ付加: ㅗ→ㅘ, ㅜ→ㅝ 等）
    private func handleKoreanVowelAddAEo() {
        guard let currentNucleus = koreanComposer.currentNucleus,
              let newNucleus = koreanNucleusAddAEo[currentNucleus],
              let output = koreanComposer.modifyNucleus(to: newNucleus) else { return }
        onDirectInsert?(output.text, output.replaceCount)
    }

    // MARK: - 中国語入力（简体、abbreviated pinyin）

    private func handleChineseInput(_ gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
                                    ltNow: Bool, rtNow: Bool, now: TimeInterval) {
        let vowelNow = vowel != nil
        let v = vowel?.rawValue ?? 0

        // 英語テーブルを再利用してアルファベットを入力
        if vowelNow {
            let char = englishTable[row][v]
            guard !char.isEmpty else { return }

            // アルファベットのみバッファに追加（数字・記号はスキップ）
            let lower = char.lowercased()
            guard lower.first?.isLetter == true else { return }

            let vowelChanged = vowel != prevVowel
            let rowChanged = row != prevRow

            if prevVowel == nil || vowelChanged || rowChanged {
                pinyinBuffer += lower
                updatePinyinCandidates()
            }
        }
    }

    // MARK: - 繁体字入力（注音首 = abbreviated zhuyin）

    private func handleZhuyinInput(_ gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
                                   ltNow: Bool, rtNow: Bool, now: TimeInterval) {
        let vowelNow = vowel != nil
        let v = vowel?.rawValue ?? 0

        // 注音テーブルから記号を取得
        if vowelNow {
            let char = zhuyinTable[row][v]
            guard !char.isEmpty else { return }
            guard let zhuyinChar = char.first,
                  let pinyinChar = zhuyinToPinyinInitial[zhuyinChar] else { return }

            let vowelChanged = vowel != prevVowel
            let rowChanged = row != prevRow

            if prevVowel == nil || vowelChanged || rowChanged {
                // ピンインバッファに abbreviated pinyin 頭文字を追加（検索用）
                pinyinBuffer += String(pinyinChar)
                updatePinyinCandidates()
            }
        }
    }

    // MARK: - 中国語ヘルパー

    /// ピンインバッファに基づいて候補を更新する
    private func updatePinyinCandidates() {
        pinyinSelectedIndex = 0
        pinyinCandidates = pinyinEngine?.lookup(pinyinBuffer) ?? []
    }

    /// 選択中の候補を確定して挿入する
    private func confirmPinyinSelectedCandidate() {
        guard !pinyinCandidates.isEmpty,
              pinyinSelectedIndex < pinyinCandidates.count else { return }
        let candidate = pinyinCandidates[pinyinSelectedIndex]
        let text = pinyinEngine?.displayText(for: candidate) ?? candidate.w
        onDirectInsert?(text, 0)
        clearPinyinState()
    }

    /// バッファに候補があれば先頭候補を確定する（スペース・句読点入力前の暗黙確定）
    private func confirmPinyinTopCandidate() {
        guard !pinyinBuffer.isEmpty else { return }
        if let first = pinyinCandidates.first {
            let text = pinyinEngine?.displayText(for: first) ?? first.w
            onDirectInsert?(text, 0)
        }
        clearPinyinState()
    }

    /// ピンイン状態をクリアする
    private func clearPinyinState() {
        pinyinBuffer = ""
        pinyinCandidates = []
        pinyinSelectedIndex = 0
    }

    // MARK: - アクション実行

    private func executeAction(_ action: GamepadAction) {
        switch action {
        case .kana(let char, let replaceCount):
            // selecting/previewing 中に新しい文字を打ったら、現在の候補を確定してから入力
            if replaceCount == 0 && (inputManager.state == .selecting || inputManager.state == .previewing) {
                _ = inputManager.confirmAll()
            }
            if replaceCount > 0 {
                inputManager.replaceDirectKana(count: replaceCount, with: char)
            } else {
                inputManager.appendDirectKana(char)
            }
        case .youon:
            applyYouon()
        case .toggleDakuten:
            applyToggleDakuten()
        case .deleteBack:
            if currentMode == .korean { koreanComposer.commit() }
            if isChinese { clearPinyinState() }
            if inputManager.isEmpty {
                // idle 時: UITextView 側で1文字削除
                onDeleteBackward?()
            } else {
                _ = inputManager.deleteBackward()
            }
        case .space:
            if !inputManager.isEmpty { _ = inputManager.confirmAll() }
            onDirectInsert?(" ", 0)
        case .cancel:
            _ = inputManager.cancelConversion()
        case .confirmOrNewline:
            if !inputManager.isEmpty {
                let result = inputManager.confirmConversion()
                if case .partial = result {
                    // 部分確定: 残りはまだ composing 中
                }
            } else {
                // 通常状態: 改行
                onDirectInsert?("\n", 0)
            }
        case .longVowel:
            inputManager.appendDirectKana("ー")
        case .punctuation(let isSecond):
            if isSecond {
                onDirectInsert?("。", 1)
            } else {
                if !inputManager.isEmpty { _ = inputManager.confirmAll() }
                onDirectInsert?("、", 0)
            }
        case .convert:
            if inputManager.state == .composing {
                inputManager.requestConversion()
            } else if inputManager.state == .previewing {
                inputManager.enterSelecting()
            } else if inputManager.state == .selecting {
                inputManager.selectNextCandidate()
            }
        case .nextCandidate:
            if inputManager.state == .previewing {
                inputManager.enterSelecting()
            } else if inputManager.state == .selecting {
                inputManager.selectNextCandidate()
            }
        case .prevCandidate:
            if inputManager.state == .previewing {
                inputManager.enterSelecting()
            } else if inputManager.state == .selecting {
                inputManager.selectPrevCandidate()
            }
        case .expandSegment:
            inputManager.editSegment(count: 1)
        case .shrinkSegment:
            inputManager.editSegment(count: -1)
        case .cursorLeft:
            onCursorMove?(-1)
        case .cursorRight:
            onCursorMove?(1)
        }
    }

    // MARK: - 後置シフト

    private func applyYouon() {
        let text = inputManager.rawKanaText
        guard let last = text.last else { return }
        if let replacement = youonPostshiftMap[last] {
            inputManager.replaceDirectKana(count: 1, with: replacement)
        } else {
            inputManager.appendDirectKana("っ")
        }
    }

    private func applyToggleDakuten() {
        let text = inputManager.rawKanaText
        guard let last = text.last else { return }

        if let seion = handakutenReverse[last] {
            inputManager.replaceDirectKana(count: 1, with: String(seion))
            return
        }
        if let seion = dakutenReverse[last] {
            if let handakuten = handakutenMap[seion] {
                inputManager.replaceDirectKana(count: 1, with: String(handakuten))
            } else {
                inputManager.replaceDirectKana(count: 1, with: String(seion))
            }
            return
        }
        if let dakuten = dakutenMap[last] {
            inputManager.replaceDirectKana(count: 1, with: String(dakuten))
        }
    }

    // MARK: - ヘルパー

    private func resolveVowel(_ gp: GamepadSnapshot) -> VowelButton? {
        if gp.rb { return .a }
        if gp.buttonX { return .i }
        if gp.buttonY { return .u }
        if gp.buttonB { return .e }
        if gp.buttonA { return .o }
        return nil
    }

    private func updatePressedButtons(_ gp: GamepadSnapshot) {
        var buttons: Set<String> = []
        if gp.dpadUp { buttons.insert("dpadUp") }
        if gp.dpadDown { buttons.insert("dpadDown") }
        if gp.dpadLeft { buttons.insert("dpadLeft") }
        if gp.dpadRight { buttons.insert("dpadRight") }
        if gp.lb { buttons.insert("LB") }
        if gp.rb { buttons.insert("RB") }
        if gp.ltValue > stickThreshold { buttons.insert("LT") }
        if gp.rtValue > stickThreshold { buttons.insert("RT") }
        if gp.buttonA { buttons.insert("A") }
        if gp.buttonB { buttons.insert("B") }
        if gp.buttonX { buttons.insert("X") }
        if gp.buttonY { buttons.insert("Y") }
        if gp.start { buttons.insert("Start") }
        if gp.back { buttons.insert("Back") }
        // 右スティック方向（ビジュアライザ用）
        let rsX = gp.rightStickX
        let rsY = gp.rightStickY
        let rAbsX = abs(rsX)
        let rAbsY = abs(rsY)
        let rMax = max(rAbsX, rAbsY)
        if rMax > stickThreshold {
            if rAbsX > rAbsY {
                buttons.insert(rsX > 0 ? "rStickRight" : "rStickLeft")
            } else {
                buttons.insert(rsY > 0 ? "rStickUp" : "rStickDown")
            }
        }
        pressedButtons = buttons
    }
}
