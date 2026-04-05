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
    private(set) var isTextOperationMode = false

    /// 中国語モード判定（簡体/繁体共通のロジック用）
    var isChinese: Bool {
        currentMode == .chineseSimplified || currentMode == .chineseTraditional
    }

    /// Start ボタンでサイクルする言語の順序（配列の順序で切り替わる）
    var enabledModes: [GamepadInputMode] = GamepadInputMode.allCases

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

    // MARK: - テキスト操作モード コールバック

    /// 文フォーカス移動（←↑=前文頭、→↓=次文頭）
    /// - Parameter direction: 負=前、正=次
    var onSentenceFocusMove: ((_ direction: Int) -> Void)?

    /// 文の前後移動（RT+左スティック: カーソル位置の文を隣接文と入れ替え）
    /// - Parameter direction: 負=前へ、正=後ろへ
    var onSwapSentence: ((_ direction: Int) -> Void)?

    /// スマート選択 拡大（D-pad →。文レベルまで）
    var onSmartSelectExpand: (() -> Void)?

    /// スマート選択 縮小（D-pad ←）
    var onSmartSelectShrink: (() -> Void)?

    /// 選択範囲を文単位で拡張（D-pad ↑=前方向、↓=後方向）
    /// - Parameter direction: 負=前、正=後
    var onExtendSelectionBySentence: ((_ direction: Int) -> Void)?

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

    // R🕹↓ 多段タップ（全言語共通）
    private var rStickDownLastTime: TimeInterval = 0
    private var rStickDownTapCount: Int = 0

    private var prevRStickUp = false
    private var prevRStickDown = false
    private var prevRStickLeft = false
    private var prevRStickRight = false

    private var prevLStickUp = false
    private var prevLStickDown = false
    private var prevLStickLeft = false
    private var prevLStickRight = false

    /// テキスト操作モード: RB+左スティックのキーリピート用フレームカウンタ
    private var cursorRepeatFrames: Int = 0
    private let cursorRepeatDelay: Int = 18   // 初回遅延（約300ms @ 60fps）
    private let cursorRepeatInterval: Int = 4 // リピート間隔（約67ms @ 60fps）

    private var prevDpadUp = false
    private var prevDpadDown = false
    private var prevDpadLeft = false
    private var prevDpadRight = false
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

    // 韓国語モード状態
    private var koreanComposer = KoreanComposer()
    private var allReleasedSinceSyllable = true  // 音節入力後に全ボタンリリース済みか
    private var patchimRollbackCoda: Int?  // 받침適用前のcoda値（巻き戻し用）
    private var patchimRollbackActive = false  // 巻き戻し可能な받침が適用中か

    // 中国語モード状態
    private(set) var pinyinBuffer: String = ""
    /// 繁体字モード用: 注音表示バッファ（pinyinBuffer と並行して蓄積）
    private(set) var zhuyinDisplayBuffer: String = ""
    private(set) var pinyinCandidates: [PinyinCandidate] = []
    private(set) var pinyinSelectedIndex: Int = 0
    var pinyinEngine: PinyinEngine?

    /// スライディングウィンドウの表示開始位置
    private(set) var pinyinWindowStart: Int = 0
    /// ウィンドウサイズ（CandidatePopup と同じ最大9件）
    private static let pinyinWindowSize = 9

    /// 現在のウィンドウ内に表示する候補
    var visiblePinyinCandidates: [PinyinCandidate] {
        guard !pinyinCandidates.isEmpty else { return [] }
        let end = min(pinyinWindowStart + Self.pinyinWindowSize, pinyinCandidates.count)
        return Array(pinyinCandidates[pinyinWindowStart..<end])
    }

    /// ウィンドウ内での選択位置（0-based）
    var pinyinSelectedIndexInWindow: Int {
        pinyinSelectedIndex - pinyinWindowStart
    }

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

        // 右スティック方向の解決（入力処理と UI 状態更新の両方で使用）
        let rsX = gp.rightStickX
        let rsY = gp.rightStickY
        let rsAbsX = abs(rsX)
        let rsAbsY = abs(rsY)
        let rsDominant = max(rsAbsX, rsAbsY) > stickThreshold
        let rStickRight = rsDominant && rsAbsX > rsAbsY && rsX > 0
        let rStickLeft  = rsDominant && rsAbsX > rsAbsY && rsX < 0
        let rStickUp    = rsDominant && rsAbsY >= rsAbsX && rsY > 0
        let rStickDown  = rsDominant && rsAbsY >= rsAbsX && rsY < 0

        // --- UI 状態更新（値が変わったときだけ代入し、@Observable の不要な通知を避ける） ---
        if activeRow != row { activeRow = row }
        if activeVowel != vowel { activeVowel = vowel }
        let layer: ActiveLayer = lbNow ? .lb : .base
        if activeLayer != layer { activeLayer = layer }
        updatePressedButtons(gp, rStickUp: rStickUp, rStickDown: rStickDown,
                             rStickLeft: rStickLeft, rStickRight: rStickRight)

        // プレビュー文字（モード別テーブル）
        let v = vowel?.rawValue ?? 0
        let newPreview: String?
        switch currentMode {
        case .japanese:
            newPreview = vowelNow ? kanaTable[row][v] : nil
        case .english:
            if vowelNow {
                let char = englishTable[row][v]
                if char.isEmpty { newPreview = nil }
                else if englishCapsLock || englishSmartCaps || englishShiftNext { newPreview = char.uppercased() }
                else { newPreview = char }
            } else {
                newPreview = nil
            }
        case .korean:
            if vowelNow {
                let onsetIdx = koreanOnsetForRow[row]
                let nucleusIdx = rtNow ? koreanNucleusShifted[v] : koreanNucleusBase[v]
                let code = 0xAC00 + (onsetIdx * 21 + nucleusIdx) * 28
                newPreview = String(Character(UnicodeScalar(code)!))
            } else if ltNow {
                newPreview = "ㅇ"
            } else if lbNow || gp.dpadUp || gp.dpadDown || gp.dpadLeft || gp.dpadRight {
                newPreview = koreanRowNames[row]
            } else {
                newPreview = nil
            }
        case .chineseSimplified:
            if vowelNow {
                let char = englishTable[row][v]
                if char.isEmpty { newPreview = nil }
                else if char.first?.isNumber == true { newPreview = nil }
                else { newPreview = char }
            } else {
                newPreview = nil
            }
        case .chineseTraditional:
            if vowelNow {
                let char = zhuyinTable[row][v]
                if char.isEmpty { newPreview = nil }
                else if char.first?.isNumber == true { newPreview = nil }
                else { newPreview = char }
            } else {
                newPreview = nil
            }
        }
        if previewChar != newPreview { previewChar = newPreview }

        // === 左スティック方向の解決（テキスト操作モードと通常モードの両方で使用） ===
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

        // 左スティック方向の UI 状態更新
        let lDir: StickDirection = {
            if lStickUp { return .up }
            if lStickDown { return .down }
            if lStickLeft { return .left }
            if lStickRight { return .right }
            return .neutral
        }()
        if leftStickDirection != lDir { leftStickDirection = lDir }

        // === テキスト操作モード ===
        if isTextOperationMode {
            handleTextOperationMode(gp, rtNow: rtNow,
                                    lStickUp: lStickUp, lStickDown: lStickDown,
                                    lStickLeft: lStickLeft, lStickRight: lStickRight)
            // D-pad / 左スティック / 右スティックの入力処理をスキップし、
            // Back / LS / RS / Start ボタンの処理へ進む
        } else {

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
        if rStickLeft && !prevRStickLeft {
            if isChinese && !pinyinBuffer.isEmpty {
                pinyinBuffer.removeLast()
                if !zhuyinDisplayBuffer.isEmpty { zhuyinDisplayBuffer.removeLast() }
                updatePinyinCandidates()
            } else {
                executeAction(.deleteBack)
            }
        }

        switch currentMode {
        case .japanese:
            if rStickUp && !prevRStickUp { executeAction(.toggleDakuten) }
            if rStickRight && !prevRStickRight { executeAction(.longVowel) }
        case .english:
            if rStickUp && !prevRStickUp { onDirectInsert?("'", 0) }
            if rStickRight && !prevRStickRight {
                onDirectInsert?("/", 0)
                englishSmartCaps = false
            }
        case .korean:
            // ↑ 子音サイクル（平音→激音→濃音）
            if rStickUp && !prevRStickUp { handleKoreanOnsetCycle() }
            // → 複合母音（ㅏ/ㅓ付加: ㅗ→ㅘ, ㅜ→ㅝ）
            if rStickRight && !prevRStickRight { handleKoreanVowelAddAEo() }
        case .chineseSimplified, .chineseTraditional:
            // → 顿号（バッファあれば先に先頭候補を確定）
            if rStickRight && !prevRStickRight {
                confirmPinyinTopCandidate()
                onDirectInsert?("、", 0)
            }
        }

        // R🕹↓ 句読点・空白（全言語共通の多段タップ）
        if rStickDown && !prevRStickDown {
            // 言語別の前処理
            if currentMode == .korean { koreanComposer.commit() }
            if isChinese { confirmPinyinTopCandidate() }
            if currentMode == .english { englishSmartCaps = false }

            // 多段タップ判定
            if (now - rStickDownLastTime) < doubleTapWindow {
                rStickDownTapCount += 1
            } else {
                rStickDownTapCount = 0
            }
            rStickDownLastTime = now

            // 言語別の句読点サイクル
            switch currentMode {
            case .japanese:
                // 読点(、) → 句点(。) → 空白
                switch rStickDownTapCount {
                case 0:
                    if !inputManager.isEmpty { _ = inputManager.confirmAll() }
                    onDirectInsert?("、", 0)
                case 1: onDirectInsert?("。", 1)
                default:
                    onDirectInsert?(" ", 1)
                    rStickDownTapCount = 0
                    rStickDownLastTime = 0
                }
            case .english:
                // 空白 → ピリオド(.) → カンマ(,)
                switch rStickDownTapCount {
                case 0: onDirectInsert?(" ", 0)
                case 1: onDirectInsert?(".", 1)
                default:
                    onDirectInsert?(",", 1)
                    rStickDownTapCount = 0
                    rStickDownLastTime = 0
                }
            case .korean:
                // 空白 → ピリオド(.)
                switch rStickDownTapCount {
                case 0: onDirectInsert?(" ", 0)
                default:
                    onDirectInsert?(".", 1)
                    rStickDownTapCount = 0
                    rStickDownLastTime = 0
                }
            case .chineseSimplified, .chineseTraditional:
                // 逗号(，) → 句号(。) → 空白
                switch rStickDownTapCount {
                case 0: onDirectInsert?("，", 0)
                case 1: onDirectInsert?("。", 1)
                default:
                    onDirectInsert?(" ", 1)
                    rStickDownTapCount = 0
                    rStickDownLastTime = 0
                }
            }
        }

        // === 左スティック（方向の解決は上で実施済み） ===
        if isChinese && !pinyinCandidates.isEmpty {
            // 中国語候補選択中: ↑↓ で候補移動（スライディングウィンドウ）
            if lStickDown && !prevLStickDown {
                if pinyinSelectedIndex < pinyinCandidates.count - 1 {
                    pinyinSelectedIndex += 1
                    // ウィンドウ下端を超えたらスライド
                    if pinyinSelectedIndex >= pinyinWindowStart + Self.pinyinWindowSize {
                        pinyinWindowStart = pinyinSelectedIndex - Self.pinyinWindowSize + 1
                    }
                }
            }
            if lStickUp && !prevLStickUp {
                if pinyinSelectedIndex > 0 {
                    pinyinSelectedIndex -= 1
                    // ウィンドウ上端を超えたらスライド
                    if pinyinSelectedIndex < pinyinWindowStart {
                        pinyinWindowStart = pinyinSelectedIndex
                    }
                }
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

        } // end of !isTextOperationMode

        // --- 前フレーム状態更新（共通: スティック・D-pad） ---
        prevRStickUp = rStickUp
        prevRStickDown = rStickDown
        prevRStickLeft = rStickLeft
        prevRStickRight = rStickRight
        prevLStickUp = lStickUp
        prevLStickDown = lStickDown
        prevLStickLeft = lStickLeft
        prevLStickRight = lStickRight

        // === Back=スペース/テキスト操作モードトグル, LS=確定/改行, RS=キャンセル ===
        if prevBack && !gp.back && !startBackComboFired {
            let isIdle = inputManager.isEmpty && (!isChinese || pinyinBuffer.isEmpty)
            if isTextOperationMode {
                // テキスト操作モード中: Back で解除
                isTextOperationMode = false
            } else if isIdle {
                // idle 時: テキスト操作モードに突入
                isTextOperationMode = true
            } else if currentMode == .japanese {
                executeAction(.space)
            } else {
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
            rStickDownTapCount = 0
            rStickDownLastTime = 0
            koreanComposer.commit()
            patchimRollbackActive = false
            allReleasedSinceSyllable = true
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
        prevDpadUp = gp.dpadUp
        prevDpadDown = gp.dpadDown
        prevDpadLeft = gp.dpadLeft
        prevDpadRight = gp.dpadRight
        prevBack = gp.back
        prevLS = gp.lsClick
        prevRS = gp.rsClick
        prevStart = gp.start
    }

    // MARK: - テキスト操作モード

    /// テキスト操作モードの入力処理
    ///
    /// - 左スティック: 文フォーカス移動（←↑=前文頭、→↓=次文頭）
    /// - RT + 左スティック: カーソル位置の文（または選択範囲）を前後に移動
    /// - RB + 左スティック: 1文字/1行ずつカーソル移動（キーリピートあり）
    /// - D-pad ←→: スマート選択 縮小/拡大（文レベルまで）
    /// - D-pad ↑↓: 選択範囲を文単位で前/後に拡張
    private func handleTextOperationMode(_ gp: GamepadSnapshot, rtNow: Bool,
                                         lStickUp: Bool, lStickDown: Bool,
                                         lStickLeft: Bool, lStickRight: Bool) {
        let rbNow = gp.rb
        let stickActive = lStickUp || lStickDown || lStickLeft || lStickRight

        // RB + 左スティック: 1文字/1行カーソル移動（キーリピート付き）
        if rbNow && stickActive {
            let shouldFire: Bool
            if cursorRepeatFrames == 0 {
                // 初回: 即発火
                shouldFire = true
            } else if cursorRepeatFrames >= cursorRepeatDelay {
                // リピート中: interval ごとに発火
                shouldFire = (cursorRepeatFrames - cursorRepeatDelay) % cursorRepeatInterval == 0
            } else {
                shouldFire = false
            }
            cursorRepeatFrames += 1

            if shouldFire {
                if lStickLeft { onCursorMove?(-1) }
                else if lStickRight { onCursorMove?(1) }
                else if lStickUp { onCursorMoveVertical?(-1) }
                else if lStickDown { onCursorMoveVertical?(1) }
            }
        } else if rtNow {
            cursorRepeatFrames = 0
            // RT + 左スティック: 文の前後移動
            if (lStickLeft && !prevLStickLeft) || (lStickUp && !prevLStickUp) {
                onSwapSentence?(-1)
            }
            if (lStickRight && !prevLStickRight) || (lStickDown && !prevLStickDown) {
                onSwapSentence?(1)
            }
        } else {
            cursorRepeatFrames = 0
            // 左スティック単体: 文フォーカス移動
            if (lStickLeft && !prevLStickLeft) || (lStickUp && !prevLStickUp) {
                onSentenceFocusMove?(-1)
            }
            if (lStickRight && !prevLStickRight) || (lStickDown && !prevLStickDown) {
                onSentenceFocusMove?(1)
            }
        }

        // D-pad: スマート選択・文単位拡張
        if gp.dpadRight && !prevDpadRight {
            onSmartSelectExpand?()
        }
        if gp.dpadLeft && !prevDpadLeft {
            onSmartSelectShrink?()
        }
        if gp.dpadUp && !prevDpadUp {
            onExtendSelectionBySentence?(-1)
        }
        if gp.dpadDown && !prevDpadDown {
            onExtendSelectionBySentence?(1)
        }
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
                // confirmAllAsPrefix で同期的に挿入するため、常に eagerChar を設定
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

        // === 받침巻き戻し: 母音が来たら直前の받침を取り消す ===
        // LB が先に到着して誤ったパッチムが適用された場合、
        // 母音到着で新音節と判断し、パッチムを元に戻す。
        if vowelNow && patchimRollbackActive {
            koreanComposer.revertCoda(to: patchimRollbackCoda)
            if let onset = koreanComposer.currentOnset,
               let nucleus = koreanComposer.currentNucleus {
                let coda = koreanComposer.currentCoda ?? 0
                let code = 0xAC00 + (onset * 21 + nucleus) * 28 + coda
                onDirectInsert?(String(Character(UnicodeScalar(code)!)), 1)
            }
            patchimRollbackActive = false
        }

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
                allReleasedSinceSyllable = false
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
                    allReleasedSinceSyllable = false
                    if rtNow { rtUsed = true }
                }
            }
        }
        if prevVowel != nil && !vowelNow {
            eagerChar = nil
        }
        // ボタンリリース検出（prevConsonantCount 更新前に判定）
        let consonantReleasing = dpadActive && consonantCount < prevConsonantCount
        prevConsonantCount = consonantCount

        // === 받침入力（即時適用+巻き戻し方式） ===
        // 音節入力後に全ボタンリリースを経てから받침入力を受け付ける。
        // D-pad エッジで받침を即適用し、row が変われば巻き戻して再適用する。
        // ボタンリリースで確定（巻き戻しを停止）。母音が来たら巻き戻す（上で処理済み）。

        // 全ボタンリリース検出
        let allReleased = !dpadActive && !vowelNow && !ltNow
        if allReleased {
            allReleasedSinceSyllable = true
            patchimRollbackActive = false
        }

        // ボタンリリースで받침確定（chord 解体中の誤巻き戻しを防止）
        if consonantReleasing && patchimRollbackActive {
            patchimRollbackActive = false
        }

        // D-pad エッジ → 받침即適用（or row変更で巻き戻して再適用）
        if !vowelNow && dpadActive && koreanComposer.isComposing {
            if !prevDpadActive && allReleasedSinceSyllable {
                // 新規エッジ（全リリース後）→ 받침を即適用
                let prevCoda = koreanComposer.currentCoda
                let codaIdx = koreanCodaForRow[row]
                if case .added(let output) = koreanComposer.inputPatchim(codaIndex: codaIdx, codaRow: row) {
                    onDirectInsert?(output.text, output.replaceCount)
                    patchimRollbackCoda = prevCoda
                    patchimRollbackActive = true
                    allReleasedSinceSyllable = false
                }
            } else if row != prevRow && patchimRollbackActive {
                // row が変わった（LB→LB+↑ 等）→ 巻き戻して再適用
                koreanComposer.revertCoda(to: patchimRollbackCoda)
                let codaIdx = koreanCodaForRow[row]
                if case .added(let output) = koreanComposer.inputPatchim(codaIndex: codaIdx, codaRow: row) {
                    onDirectInsert?(output.text, output.replaceCount)
                }
            }
        }

        // LT エッジ → ㅇ받침即発火
        if ltNow && !prevLT && !vowelNow && !dpadActive && allReleasedSinceSyllable {
            if koreanComposer.isComposing && koreanComposer.currentCoda == nil {
                let codaIdx = koreanCodaForRow[0]
                handleKoreanPatchim(codaIndex: codaIdx, codaRow: 0)
                allReleasedSinceSyllable = false
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

    // MARK: - 中国語入力（簡体・繁体共通）

    /// 中国語モード共通: RB 数字入力 + RT「0」入力（バッファ空のときだけ）
    private func handleChineseDigits(row: Int, vowel: VowelButton?, digit: String,
                                     ltNow: Bool, rtNow: Bool) {
        // RB（数字）: バッファが空のときだけ挿入
        if vowel == .a && pinyinBuffer.isEmpty {
            let vowelChanged = vowel != prevVowel
            let rowChanged = row != prevRow
            if prevVowel == nil || vowelChanged || rowChanged {
                onDirectInsert?(digit, 0)
            }
        }

        // RT: 数字「0」入力
        if rtNow && !prevRT { rtUsed = false }
        if !rtNow && prevRT {
            if !rtUsed && !ltNow && pinyinBuffer.isEmpty {
                onDirectInsert?("0", 0)
            }
            rtUsed = false
        }
    }

    /// 簡体字入力（abbreviated pinyin）
    private func handleChineseInput(_ gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
                                    ltNow: Bool, rtNow: Bool, now: TimeInterval) {
        let v = vowel?.rawValue ?? 0

        handleChineseDigits(row: row, vowel: vowel, digit: englishTable[row][0],
                            ltNow: ltNow, rtNow: rtNow)

        // フェイスボタン（X/Y/B/A）: アルファベットをバッファに追加
        if let vowel, vowel != .a {
            let char = englishTable[row][v]
            guard !char.isEmpty else { return }
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

    /// 繁体字入力（abbreviated zhuyin）
    private func handleZhuyinInput(_ gp: GamepadSnapshot, row: Int, vowel: VowelButton?,
                                   ltNow: Bool, rtNow: Bool, now: TimeInterval) {
        let v = vowel?.rawValue ?? 0

        handleChineseDigits(row: row, vowel: vowel, digit: zhuyinTable[row][0],
                            ltNow: ltNow, rtNow: rtNow)

        // フェイスボタン（X/Y/B/A）: 注音をバッファに追加
        if let vowel, vowel != .a {
            let char = zhuyinTable[row][v]
            guard !char.isEmpty else { return }
            guard let zhuyinChar = char.first,
                  let pinyinChar = zhuyinToPinyinInitial[zhuyinChar] else { return }

            let vowelChanged = vowel != prevVowel
            let rowChanged = row != prevRow
            if prevVowel == nil || vowelChanged || rowChanged {
                pinyinBuffer += String(pinyinChar)
                zhuyinDisplayBuffer += String(zhuyinChar)
                updatePinyinCandidates()
            }
        }
    }

    // MARK: - 中国語ヘルパー

    /// ピンインバッファに基づいて候補を更新する
    private func updatePinyinCandidates() {
        pinyinSelectedIndex = 0
        pinyinWindowStart = 0
        pinyinCandidates = pinyinEngine?.lookup(pinyinBuffer) ?? []
    }

    /// 選択中の候補を確定して挿入する
    private func confirmPinyinSelectedCandidate() {
        guard !pinyinCandidates.isEmpty,
              pinyinSelectedIndex < pinyinCandidates.count else { return }
        let candidate = pinyinCandidates[pinyinSelectedIndex]
        let text = pinyinEngine?.displayText(for: candidate) ?? candidate.word
        onDirectInsert?(text, 0)
        clearPinyinState()
    }

    /// バッファに候補があれば先頭候補を確定する（スペース・句読点入力前の暗黙確定）
    private func confirmPinyinTopCandidate() {
        guard !pinyinBuffer.isEmpty else { return }
        if let first = pinyinCandidates.first {
            let text = pinyinEngine?.displayText(for: first) ?? first.word
            onDirectInsert?(text, 0)
        }
        clearPinyinState()
    }

    /// ピンイン状態をクリアする
    private func clearPinyinState() {
        pinyinBuffer = ""
        zhuyinDisplayBuffer = ""
        pinyinCandidates = []
        pinyinSelectedIndex = 0
        pinyinWindowStart = 0
    }

    // MARK: - アクション実行

    private func executeAction(_ action: GamepadAction) {
        switch action {
        case .kana(let char, let replaceCount):
            // selecting/previewing 中に新しい文字を打ったら、現在の候補を confirmedPrefix に
            // 蓄えて composition を維持する。unmarkText() を経由しないため UIKit の
            // 内部クリーンアップと setMarkedText() の競合が発生しない。
            if replaceCount == 0 && (inputManager.state == .selecting || inputManager.state == .previewing) {
                inputManager.confirmAllAsPrefix()
                inputManager.appendDirectKana(char)
                return
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

    private func updatePressedButtons(_ gp: GamepadSnapshot,
                                      rStickUp: Bool, rStickDown: Bool,
                                      rStickLeft: Bool, rStickRight: Bool) {
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
        if rStickUp { buttons.insert("rStickUp") }
        if rStickDown { buttons.insert("rStickDown") }
        if rStickLeft { buttons.insert("rStickLeft") }
        if rStickRight { buttons.insert("rStickRight") }
        if pressedButtons != buttons { pressedButtons = buttons }
    }
}
