import SwiftUI
import UIKit
import KeyLogicKit

@main
struct GIMEApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    @State private var inputManager = InputManager()
    @State private var gamepadInput: GamepadInputManager?
    @State private var pinyinEngine = PinyinEngine()

    // ゲームパッド専用アプリだが、キーボード入力も受け付ける（フォールバック）
    private var keyRouter: KeyRouter {
        KeyRouter(definition: DefaultKeymaps.romajiUS)
    }

    @State private var text: String = ""
    @State private var cursorLocation: Int = 0
    @State private var selectionLength: Int = 0
    @State private var caretRect: CGRect = .zero
    @State private var smartSelection = SmartSelectionState()

    /// エディタ表示スタイル（動画撮影用に大きめフォント）
    private let editorStyle = EditorStyle(
        font: .monospacedSystemFont(ofSize: 28, weight: .regular),
        lineSpacing: 4
    )

    var body: some View {
        GeometryReader { geo in
            VStack(spacing: 0) {
                // テキストエディタ
                IMETextViewRepresentable(
                    inputManager: inputManager,
                    keyRouter: keyRouter,
                    editorStyle: editorStyle,
                    text: $text,
                    cursorLocation: $cursorLocation,
                    selectionLength: $selectionLength,
                    onCaretRectChange: { rect in
                        caretRect = rect
                    },
                    hidesSoftwareKeyboard: true
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .overlay(alignment: .topLeading) {
                    // 変換候補ポップアップ（日本語）
                    if inputManager.state == .selecting {
                        CandidatePopup(
                            additionalCandidates: inputManager.visibleAdditionalCandidates,
                            isAdditionalCandidateSelected: inputManager.isAdditionalCandidateSelected,
                            selectedAdditionalCandidateIndex: inputManager.selectedAdditionalCandidateIndex,
                            candidates: inputManager.visibleCandidateTexts,
                            selectedIndex: inputManager.selectedIndexInWindow,
                            font: .system(size: 28),
                            fontSize: 28,
                            anchor: caretRect,
                            bounds: geo.size
                        )
                    }
                    // 中国語ピンイン候補ポップアップ
                    else if let gp = gamepadInput,
                            gp.isChinese,
                            !gp.pinyinCandidates.isEmpty {
                        CandidatePopup(
                            additionalCandidates: [],
                            isAdditionalCandidateSelected: false,
                            selectedAdditionalCandidateIndex: 0,
                            candidates: gp.visiblePinyinCandidates.map {
                                "\($0.word)  \($0.reading)"
                            },
                            selectedIndex: gp.pinyinSelectedIndexInWindow,
                            font: .system(size: 28),
                            fontSize: 28,
                            anchor: caretRect,
                            bounds: geo.size
                        )
                    }
                }

                // ゲームパッドビジュアライザ（画面下ぴったり）
                if let gp = gamepadInput, gp.isConnected {
                    GamepadVisualizerView(
                        gamepadInput: gp
                    )
                    .padding([.horizontal, .top])
                    .background(.ultraThinMaterial)
                } else {
                    VStack(spacing: 8) {
                        HStack {
                            Label("コントローラーを接続してください", systemImage: "gamecontroller")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(.secondary)
                            Spacer()
                        }
                        .padding(.horizontal, 4)

                        RoundedRectangle(cornerRadius: 16)
                            .fill(Color(.systemGray6))
                            .frame(height: 180)
                    }
                    .padding([.horizontal, .top])
                    .background(.ultraThinMaterial)
                }
            }
        }
        .onAppear {
            let gp = GamepadInputManager(inputManager: inputManager)
            gp.onCursorMove = { offset in
                let textLen = (text as NSString).length
                let newLoc = max(0, min(textLen, cursorLocation + offset))
                cursorLocation = newLoc
                selectionLength = 0
            }
            gp.onCursorMoveVertical = { direction in
                let ns = text as NSString
                let textLen = ns.length
                guard textLen > 0 else { return }
                let loc = min(cursorLocation, textLen)
                if direction < 0 {
                    // 上: 現在行の行頭を探し、その1つ前（前行末）へ
                    let lineRange = ns.lineRange(for: NSRange(location: loc, length: 0))
                    if lineRange.location > 0 {
                        // 前行の同じ列位置を計算
                        let colInCurrentLine = loc - lineRange.location
                        let prevLineRange = ns.lineRange(for: NSRange(location: lineRange.location - 1, length: 0))
                        let prevLineLen = prevLineRange.length - (ns.substring(with: prevLineRange).hasSuffix("\n") ? 1 : 0)
                        cursorLocation = prevLineRange.location + min(colInCurrentLine, prevLineLen)
                    } else {
                        cursorLocation = 0
                    }
                } else {
                    // 下: 現在行の行末の次（次行頭）へ
                    let lineRange = ns.lineRange(for: NSRange(location: loc, length: 0))
                    let nextLineStart = lineRange.location + lineRange.length
                    if nextLineStart <= textLen {
                        let colInCurrentLine = loc - lineRange.location
                        let nextLineRange = ns.lineRange(for: NSRange(location: nextLineStart, length: 0))
                        let nextLineLen = nextLineRange.length - (ns.substring(with: nextLineRange).hasSuffix("\n") ? 1 : 0)
                        cursorLocation = nextLineRange.location + min(colInCurrentLine, nextLineLen)
                    } else {
                        cursorLocation = textLen
                    }
                }
                selectionLength = 0
            }
            gp.onDeleteBackward = {
                guard cursorLocation > 0 else { return }
                let ns = text as NSString
                // サロゲートペア対応: カーソル位置の前の文字の範囲を取得
                let range = ns.rangeOfComposedCharacterSequence(at: cursorLocation - 1)
                text = ns.replacingCharacters(in: range, with: "")
                cursorLocation = range.location
                selectionLength = 0
            }
            gp.onShareText = {
                // composing 中なら確定してからテキスト全文を共有
                if !inputManager.isEmpty {
                    _ = inputManager.confirmAll()
                }
                guard !text.isEmpty else { return }
                showShareSheet(text: text)
            }
            gp.onDirectInsert = { insertText, replaceCount in
                let ns = text as NSString
                if replaceCount > 0 {
                    let deleteFrom = max(0, cursorLocation - replaceCount)
                    let range = NSRange(location: deleteFrom, length: cursorLocation - deleteFrom)
                    text = ns.replacingCharacters(in: range, with: insertText)
                    cursorLocation = deleteFrom + (insertText as NSString).length
                } else {
                    let range = NSRange(location: cursorLocation, length: 0)
                    text = ns.replacingCharacters(in: range, with: insertText)
                    cursorLocation += (insertText as NSString).length
                }
                selectionLength = 0
            }
            // MARK: テキスト操作モード コールバック
            gp.onSentenceFocusMove = { direction in
                guard !text.isEmpty else { return }
                let idx = String.Index(utf16Offset: min(cursorLocation, text.utf16.count), in: text)
                let newIdx: String.Index
                if direction < 0 {
                    newIdx = SentenceBoundary.previousSentenceStart(in: text, before: idx)
                } else {
                    newIdx = SentenceBoundary.nextSentenceEnd(in: text, after: idx)
                }
                cursorLocation = newIdx.utf16Offset(in: text)
                selectionLength = 0
                smartSelection.reset()
            }
            gp.onSwapSentence = { direction in
                guard !text.isEmpty else { return }
                let idx = String.Index(utf16Offset: min(cursorLocation, text.utf16.count), in: text)
                let currentRange = SentenceBoundary.sentenceRange(in: text, at: idx)
                let currentSentence = String(text[currentRange])

                if direction < 0 {
                    // 前の文と入れ替え
                    guard currentRange.lowerBound > text.startIndex else { return }
                    let prevIdx = text.index(before: currentRange.lowerBound)
                    let prevRange = SentenceBoundary.sentenceRange(in: text, at: prevIdx)
                    let prevSentence = String(text[prevRange])
                    // 入れ替え前に UTF-16 オフセットを計算
                    let baseOffset = prevRange.lowerBound.utf16Offset(in: text)
                    let combinedRange = prevRange.lowerBound..<currentRange.upperBound
                    text.replaceSubrange(combinedRange, with: currentSentence + prevSentence)
                    // カーソルは移動した文の先頭
                    cursorLocation = baseOffset
                } else {
                    // 次の文と入れ替え
                    guard currentRange.upperBound < text.endIndex else { return }
                    let nextRange = SentenceBoundary.sentenceRange(in: text, at: currentRange.upperBound)
                    let nextSentence = String(text[nextRange])
                    // 入れ替え前に UTF-16 オフセットを計算（replaceSubrange 後は Index が無効になるため）
                    let baseOffset = currentRange.lowerBound.utf16Offset(in: text)
                    let combinedRange = currentRange.lowerBound..<nextRange.upperBound
                    text.replaceSubrange(combinedRange, with: nextSentence + currentSentence)
                    // カーソルは移動した文の先頭（次の文の長さ分ずれた位置）
                    cursorLocation = baseOffset + nextSentence.utf16.count
                }
                selectionLength = 0
                smartSelection.reset()
            }
            gp.onSmartSelectExpand = {
                guard !text.isEmpty else { return }
                let idx = String.Index(utf16Offset: min(cursorLocation, text.utf16.count), in: text)
                if let range = smartSelection.expand(in: text, cursor: idx) {
                    // 文レベルまでに制限（.block はスキップ）
                    if smartSelection.level > .sentence {
                        _ = smartSelection.shrink(in: text)
                        return
                    }
                    cursorLocation = range.lowerBound.utf16Offset(in: text)
                    selectionLength = range.upperBound.utf16Offset(in: text) - cursorLocation
                }
            }
            gp.onSmartSelectShrink = {
                guard !text.isEmpty else { return }
                if let range = smartSelection.shrink(in: text) {
                    cursorLocation = range.lowerBound.utf16Offset(in: text)
                    selectionLength = range.upperBound.utf16Offset(in: text) - cursorLocation
                } else {
                    // none に戻った: カーソル位置を起点に戻す
                    if let origin = smartSelection.origin {
                        cursorLocation = origin.utf16Offset(in: text)
                    }
                    selectionLength = 0
                    smartSelection.reset()
                }
            }
            gp.onExtendSelectionBySentence = { direction in
                guard !text.isEmpty else { return }
                let selStart = cursorLocation
                let selEnd = cursorLocation + selectionLength
                if direction < 0 {
                    // 選択範囲を前の文頭まで拡張
                    let startIdx = String.Index(utf16Offset: selStart, in: text)
                    let newStart = SentenceBoundary.previousSentenceStart(in: text, before: startIdx)
                    cursorLocation = newStart.utf16Offset(in: text)
                    selectionLength = selEnd - cursorLocation
                } else {
                    // 選択範囲を次の文末まで拡張
                    let endIdx = String.Index(utf16Offset: min(selEnd, text.utf16.count), in: text)
                    let newEnd = SentenceBoundary.nextSentenceEnd(in: text, after: endIdx)
                    selectionLength = newEnd.utf16Offset(in: text) - cursorLocation
                }
            }
            pinyinEngine.load()
            gp.pinyinEngine = pinyinEngine
            gamepadInput = gp
        }
        .onChange(of: text) { _, newValue in
            UserDefaults.standard.set(newValue, forKey: SendTextIntent.editorTextKey)
        }
    }

    /// 共有シートを表示する
    private func showShareSheet(text: String) {
        guard let windowScene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first,
              let rootVC = windowScene.windows.first?.rootViewController else { return }

        let activityVC = UIActivityViewController(
            activityItems: [text],
            applicationActivities: nil
        )
        if let popover = activityVC.popoverPresentationController {
            popover.sourceView = rootVC.view
            popover.sourceRect = CGRect(
                x: rootVC.view.bounds.midX,
                y: rootVC.view.bounds.midY,
                width: 0, height: 0
            )
            popover.permittedArrowDirections = []
        }
        var presenter = rootVC
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        presenter.present(activityVC, animated: true)
    }
}
