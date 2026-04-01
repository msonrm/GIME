import SwiftUI
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

    // ゲームパッド専用アプリだが、キーボード入力も受け付ける（フォールバック）
    private var keyRouter: KeyRouter {
        KeyRouter(definition: DefaultKeymaps.romajiUS)
    }

    @State private var text: String = ""
    @State private var cursorLocation: Int = 0
    @State private var selectionLength: Int = 0
    @State private var caretRect: CGRect = .zero

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
                    // 変換候補ポップアップ
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
                }

                // ゲームパッドビジュアライザ（接続時のみ）
                if let gp = gamepadInput, gp.isConnected {
                    GamepadVisualizerView(gamepadInput: gp)
                        .padding()
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
            gp.onDeleteBackward = {
                guard cursorLocation > 0 else { return }
                let ns = text as NSString
                // サロゲートペア対応: カーソル位置の前の文字の範囲を取得
                let range = ns.rangeOfComposedCharacterSequence(at: cursorLocation - 1)
                text = ns.replacingCharacters(in: range, with: "")
                cursorLocation = range.location
                selectionLength = 0
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
            gamepadInput = gp
        }
    }
}
