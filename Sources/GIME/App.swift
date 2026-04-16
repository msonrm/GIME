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
    @State private var vrChatSettings = VrChatOscSettings()
    @State private var vrChatOutput: VrChatOscOutput?

    // ゲームパッド専用アプリだが、キーボード入力も受け付ける（フォールバック）
    private var keyRouter: KeyRouter {
        KeyRouter(definition: DefaultKeymaps.romajiUS)
    }

    @State private var text: String = ""
    @State private var cursorLocation: Int = 0
    @State private var selectionLength: Int = 0
    @State private var caretRect: CGRect = .zero
    private let textRangeRectsProvider = TextRangeRectsProvider()
    @State private var textOpController: TextOperationController?

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
                    textRangeRectsProvider: textRangeRectsProvider,
                    hidesSoftwareKeyboard: true
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel("テキストエディタ")
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
                .overlay {
                    // フォーカスオーバーレイ（テキスト操作モード中、フォーカス文以外を暗く）
                    if let ctrl = textOpController, ctrl.isFocusOverlayActive {
                        SentenceFocusOverlay(cutoutRects: ctrl.focusedSentenceRects)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }

                // ゲームパッドビジュアライザ（画面下ぴったり）
                if let gp = gamepadInput, gp.isConnected {
                    GamepadVisualizerView(
                        gamepadInput: gp,
                        vrChatSettings: vrChatSettings
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
                            .accessibilityHidden(true)
                    }
                    .padding([.horizontal, .top])
                    .background(.ultraThinMaterial)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("ゲームパッド未接続。コントローラーを接続してください。")
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
            let ctrl = TextOperationController(rectsProvider: textRangeRectsProvider)
            textOpController = ctrl

            gp.onSentenceFocusMove = { direction in
                let r = ctrl.sentenceFocusMove(direction: direction, text: text, cursor: cursorLocation)
                cursorLocation = r.cursor
                selectionLength = r.selection
            }
            gp.onSwapSentence = { direction in
                guard let r = ctrl.swapSentence(
                    direction: direction, text: text,
                    cursor: cursorLocation, selection: selectionLength
                ) else { return }
                text = r.text
                cursorLocation = r.cursor
                selectionLength = 0
            }
            gp.onSmartSelectExpand = {
                guard let r = ctrl.smartSelectExpand(text: text, cursor: cursorLocation) else { return }
                cursorLocation = r.cursor
                selectionLength = r.selection
            }
            gp.onSmartSelectShrink = {
                let r = ctrl.smartSelectShrink(text: text, cursor: cursorLocation)
                cursorLocation = r.cursor
                selectionLength = r.selection
            }
            gp.onExtendSelectionBySentence = { direction in
                let r = ctrl.extendSelectionBySentence(
                    direction: direction, text: text,
                    cursor: cursorLocation, selection: selectionLength)
                cursorLocation = r.cursor
                selectionLength = r.selection
            }
            gp.onTextOperationFrame = {
                ctrl.refreshFocusRectsIfNeeded(text: text, cursor: cursorLocation)
            }
            pinyinEngine.load()
            gp.pinyinEngine = pinyinEngine

            // MARK: VRChat OSC コールバック（有効時のみ発火）
            gp.onIdleConfirm = {
                // VRChat モード ON + テキスト非空 → chatbox に確定送信してクリア
                guard vrChatSettings.enabled, let out = vrChatOutput else { return false }
                let body = text
                guard !body.isEmpty else { return false }
                out.commit(body)
                text = ""
                cursorLocation = 0
                selectionLength = 0
                return true
            }

            gamepadInput = gp
            refreshVrChatOutput()
        }
        .onChange(of: text) { _, newValue in
            UserDefaults.standard.set(newValue, forKey: SendTextIntent.editorTextKey)
            sendVrChatDraftIfNeeded()
        }
        .onChange(of: chatboxDraft) { _, _ in
            sendVrChatDraftIfNeeded()
        }
        .onChange(of: vrChatSettings.enabled) { _, _ in
            refreshVrChatOutput()
        }
        .onChange(of: vrChatSettings.host) { _, _ in
            refreshVrChatOutput()
        }
        .onChange(of: vrChatSettings.port) { _, _ in
            refreshVrChatOutput()
        }
        .onChange(of: gamepadInput?.operationMode) { _, newMode in
            if newMode == .textOperation {
                textOpController?.onModeEnter(text: text, cursor: cursorLocation)
            } else {
                textOpController?.onModeExit()
            }
        }
    }

    // MARK: - VRChat OSC 連携

    /// chatbox に送る現在の下書きテキスト。
    /// `text`（確定済み）+ 現在の composing 状態を結合したもの。
    private var chatboxDraft: String {
        let composing: String
        switch gamepadInput?.currentMode {
        case .japanese:
            composing = inputManager.rawKanaText
        case .chineseSimplified:
            composing = gamepadInput?.pinyinBuffer ?? ""
        case .chineseTraditional:
            composing = gamepadInput?.zhuyinDisplayBuffer ?? ""
        default:
            composing = ""
        }
        return text + composing
    }

    /// 設定を元に `VrChatOscOutput` を起動/更新/停止する。
    private func refreshVrChatOutput() {
        if vrChatSettings.enabled {
            if let out = vrChatOutput {
                out.updateTarget(host: vrChatSettings.host, port: vrChatSettings.port)
            } else {
                let sender = OscSender(host: vrChatSettings.host, port: vrChatSettings.port)
                vrChatOutput = VrChatOscOutput(sender: sender)
            }
        } else {
            vrChatOutput?.close()
            vrChatOutput = nil
        }
    }

    /// VRChat モード有効時、現在の下書きを chatbox に debounce 付きで送信する。
    private func sendVrChatDraftIfNeeded() {
        guard vrChatSettings.enabled, let out = vrChatOutput else { return }
        out.sendComposingText(chatboxDraft)
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

// MARK: - フォーカスオーバーレイ

/// テキスト操作モードのフォーカス効果
///
/// 指定された rect 以外を半透明の背景色で覆い、
/// フォーカス中の文を「くり抜き」で際立たせる。
private struct SentenceFocusOverlay: View {
    let cutoutRects: [CGRect]

    var body: some View {
        Canvas { context, size in
            // 全体を覆うパスから、フォーカス文の rect をくり抜く
            var path = Path(CGRect(origin: .zero, size: size))
            for rect in cutoutRects {
                // くり抜き部分を角丸にして自然に見せる
                path.addRoundedRect(in: rect, cornerSize: CGSize(width: 4, height: 4))
            }
            context.fill(path, with: .color(Color(.systemBackground).opacity(0.7)), style: FillStyle(eoFill: true))
        }
        .animation(.easeInOut(duration: 0.15), value: cutoutRects.map { [$0.origin.x, $0.origin.y, $0.width, $0.height] })
    }
}
