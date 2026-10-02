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

    /// iPhone（compact 幅）では全体を縮小、iPad（regular 幅）では従来の大きめスタイル。
    /// Stage Manager で iPad を縦長に細くした場合も compact 扱いになる。
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private var isCompactWidth: Bool {
        horizontalSizeClass == .compact
    }

    // ゲームパッド専用アプリだが、キーボード入力も受け付ける（フォールバック）
    private var keyRouter: KeyRouter {
        KeyRouter(definition: DefaultKeymaps.romajiUS)
    }

    @State private var text: String = ""
    @State private var cursorLocation: Int = 0
    @State private var selectionLength: Int = 0
    @State private var caretRect: CGRect = .zero

    /// エディタ表示スタイル。compact 幅では小さめのフォント、regular では配信・動画撮影向けの大きめフォント（42pt）。
    private var editorStyle: EditorStyle {
        if isCompactWidth {
            return EditorStyle(
                font: .monospacedSystemFont(ofSize: 16, weight: .regular),
                lineSpacing: 2
            )
        } else {
            return EditorStyle(
                // ★配信向けに 28 → 42（1.5 倍。2026-10 の要望）。行間も比例させる。
                font: .monospacedSystemFont(ofSize: 42, weight: .regular),
                lineSpacing: 6
            )
        }
    }

    /// 変換候補ポップアップのフォントサイズ
    private var candidateFontSize: CGFloat {
        // ★本文は 42pt だが、候補は 9 件の縦並びなので 28 に戻した（42 だと画面を覆う）。
        //   多い候補はページ送り（下の点列）で見せる。
        isCompactWidth ? 18 : 28
    }

    /// ゲームパッド未接続プレースホルダの高さ
    private var placeholderHeight: CGFloat {
        isCompactWidth ? 100 : 180
    }

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
                .accessibilityLabel("テキストエディタ")
                .overlay(alignment: .topLeading) {
                    // 変換候補ポップアップ（日本語）
                    if inputManager.state == .selecting {
                        CandidatePopup(
                            // 追加候補（ひらがな・カタカナ等）は 1 ページ目でだけ見せる。2 ページ目以降は
                            // ↑ でそのページ内を遡るだけで到達できず、「選べそうで選べない」になるため（へちまと同じ）
                            additionalCandidates: (inputManager.candidatePageIndex == 0
                                || inputManager.isAdditionalCandidateSelected)
                                ? inputManager.visibleAdditionalCandidates : [],
                            isAdditionalCandidateSelected: inputManager.isAdditionalCandidateSelected,
                            selectedAdditionalCandidateIndex: inputManager.selectedAdditionalCandidateIndex,
                            candidates: inputManager.visibleCandidateTexts,
                            selectedIndex: inputManager.selectedIndexInWindow,
                            font: .system(size: candidateFontSize),
                            fontSize: candidateFontSize,
                            pageCount: inputManager.candidatePageCount,
                            pageIndex: inputManager.candidatePageIndex,
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
                            .frame(height: placeholderHeight)
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
            // 候補は固定ページで見せる（へちまと同じ。点列でページ位置を示す）
            inputManager.pagedCandidateWindow = true
            // 50 件で打ち切る（へちまと同じ）。ページ数が 6 以下になり、点列が常に出る
            inputManager.maxCandidateCount = 50
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

