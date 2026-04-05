import Foundation
import KeyLogicKit

/// テキスト操作モードのロジックを管理するコントローラ
///
/// GamepadInputManager のコールバックから呼び出され、
/// テキスト・カーソル・選択範囲の新しい値を返す。
/// UI 状態（フォーカス rect）も保持する。
@MainActor
@Observable
final class TextOperationController {

    /// フォーカス中の文の視覚 rect（オーバーレイ描画用）
    /// 空配列の場合、テキスト操作モード中なら全面マスクになる
    private(set) var focusedSentenceRects: [CGRect] = []

    /// フォーカスオーバーレイを表示するか（モード中は常に true）
    private(set) var isFocusOverlayActive = false

    // MARK: - Dependencies

    private let rectsProvider: TextRangeRectsProvider

    // MARK: - Internal State

    /// フォーカス rect 更新用: 最後にフォーカスしたカーソル位置
    private var lastFocusCursor: Int?
    private var smartSelection = SmartSelectionState()
    /// D-pad ↑↓ の文単位選択: 伸長方向（-1=後方、0=なし、1=前方）
    private var sentenceSelectionDirection: Int = 0
    /// D-pad ↑↓ の文単位選択: 起点の UTF-16 オフセット
    private var sentenceSelectionAnchor: Int = 0

    // MARK: - Init

    init(rectsProvider: TextRangeRectsProvider) {
        self.rectsProvider = rectsProvider
    }

    // MARK: - 結果型

    /// カーソル位置・選択範囲の変更結果
    struct CursorResult {
        var cursor: Int
        var selection: Int
    }

    /// テキスト変更を伴う結果
    struct TextResult {
        var text: String
        var cursor: Int
    }

    // MARK: - 文フォーカス移動

    /// カーソルを前後の文頭/文末に移動する
    func sentenceFocusMove(direction: Int, text: String, cursor: Int) -> CursorResult {
        guard !text.isEmpty else { return CursorResult(cursor: cursor, selection: 0) }
        let idx = safeIndex(cursor, in: text)
        let newIdx: String.Index
        if direction < 0 {
            newIdx = SentenceBoundary.previousSentenceStart(in: text, before: idx)
        } else {
            newIdx = SentenceBoundary.nextSentenceEnd(in: text, after: idx)
        }
        let newCursor = newIdx.utf16Offset(in: text)
        smartSelection.reset()
        sentenceSelectionDirection = 0
        lastFocusCursor = newCursor
        // スクロール完了まで全面マスク（refreshFocusRectsIfNeeded で復帰）
        focusedSentenceRects = []
        return CursorResult(cursor: newCursor, selection: 0)
    }

    // MARK: - 文の入れ替え

    /// カーソル位置の文（または選択範囲）を隣接文と入れ替える
    func swapSentence(direction: Int, text: String,
                      cursor: Int, selection: Int = 0) -> TextResult? {
        guard !text.isEmpty else { return nil }
        var text = text

        // 対象範囲: 選択ありなら選択範囲、なければカーソル位置の文
        let currentRange: Range<String.Index>
        if selection > 0 {
            let start = safeIndex(cursor, in: text)
            let end = safeIndex(min(cursor + selection, text.utf16.count), in: text)
            currentRange = start..<end
        } else {
            let idx = safeIndex(cursor, in: text)
            currentRange = SentenceBoundary.sentenceRange(in: text, at: idx)
        }

        let currentText = String(text[currentRange])

        let newCursor: Int
        if direction < 0 {
            guard currentRange.lowerBound > text.startIndex else { return nil }
            let prevIdx = text.index(before: currentRange.lowerBound)
            let prevRange = SentenceBoundary.sentenceRange(in: text, at: prevIdx)
            let prevSentence = String(text[prevRange])
            let baseOffset = prevRange.lowerBound.utf16Offset(in: text)
            text.replaceSubrange(prevRange.lowerBound..<currentRange.upperBound,
                                 with: currentText + prevSentence)
            newCursor = baseOffset
        } else {
            guard currentRange.upperBound < text.endIndex else { return nil }
            let nextRange = SentenceBoundary.sentenceRange(in: text, at: currentRange.upperBound)
            let nextSentence = String(text[nextRange])
            let baseOffset = currentRange.lowerBound.utf16Offset(in: text)
            text.replaceSubrange(currentRange.lowerBound..<nextRange.upperBound,
                                 with: nextSentence + currentText)
            newCursor = baseOffset + nextSentence.utf16.count
        }
        resetSelectionState()
        lastFocusCursor = newCursor
        return TextResult(text: text, cursor: newCursor)
    }

    // MARK: - スマート選択

    /// スマート選択を1段階拡大する（文レベルが上限）
    func smartSelectExpand(text: String, cursor: Int) -> CursorResult? {
        guard !text.isEmpty else { return nil }
        sentenceSelectionDirection = 0
        clearFocus()
        let idx = safeIndex(cursor, in: text)
        guard let range = smartSelection.expand(in: text, cursor: idx) else { return nil }
        if smartSelection.level > .sentence {
            _ = smartSelection.shrink(in: text)
            return nil
        }
        let newCursor = range.lowerBound.utf16Offset(in: text)
        let selection = range.upperBound.utf16Offset(in: text) - newCursor
        return CursorResult(cursor: newCursor, selection: selection)
    }

    /// スマート選択を1段階縮小する
    func smartSelectShrink(text: String, cursor: Int) -> CursorResult {
        sentenceSelectionDirection = 0
        clearFocus()
        guard !text.isEmpty else { return CursorResult(cursor: cursor, selection: 0) }
        if let range = smartSelection.shrink(in: text) {
            let newCursor = range.lowerBound.utf16Offset(in: text)
            let selection = range.upperBound.utf16Offset(in: text) - newCursor
            return CursorResult(cursor: newCursor, selection: selection)
        }
        // none に戻った: 起点にカーソルを戻す
        let origin = smartSelection.origin.map { $0.utf16Offset(in: text) } ?? cursor
        smartSelection.reset()
        return CursorResult(cursor: origin, selection: 0)
    }

    // MARK: - 文単位選択（ラバーバンド）

    /// D-pad ↑↓ による文単位選択（伸ばす/縮める）
    func extendSelectionBySentence(direction: Int, text: String,
                                   cursor: Int, selection: Int) -> CursorResult {
        clearFocus()
        guard !text.isEmpty else { return CursorResult(cursor: cursor, selection: selection) }
        let textLen = text.utf16.count

        if selection == 0 {
            // 選択なし: 押した方向に選択開始
            sentenceSelectionAnchor = cursor
            sentenceSelectionDirection = direction
            return extendInDirection(direction, text: text, cursor: cursor,
                                     selection: 0, textLen: textLen)
        }

        if sentenceSelectionDirection == 0 {
            // スマート選択等で選択中（direction 未設定）: 押した方向を設定して伸ばす
            sentenceSelectionDirection = direction
            sentenceSelectionAnchor = direction < 0 ? cursor + selection : cursor
            return extendInDirection(direction, text: text, cursor: cursor,
                                     selection: selection, textLen: textLen)
        }

        if direction == sentenceSelectionDirection {
            // 同じ方向: さらに伸ばす
            return extendInDirection(direction, text: text, cursor: cursor,
                                     selection: selection, textLen: textLen)
        }

        // 逆方向: 1文分縮める
        return shrinkSelection(text: text, cursor: cursor,
                               selection: selection, textLen: textLen)
    }

    // MARK: - モード切替

    /// テキスト操作モードに突入
    func onModeEnter(text: String, cursor: Int) {
        isFocusOverlayActive = true
        lastFocusCursor = cursor
        updateFocusRects(text: text, cursor: cursor)
    }

    /// テキスト操作モードから離脱
    func onModeExit() {
        isFocusOverlayActive = false
        focusedSentenceRects = []
        lastFocusCursor = nil
        sentenceSelectionDirection = 0
        smartSelection.reset()
    }

    /// 毎フレーム呼び出してフォーカス rect をスクロールに追従させる
    func refreshFocusRectsIfNeeded(text: String, cursor: Int) {
        guard let focusCursor = lastFocusCursor else { return }
        // カーソルが変わっていたら更新（RB+スティックでのカーソル移動追従）
        if focusCursor != cursor {
            lastFocusCursor = cursor
        }
        updateFocusRects(text: text, cursor: lastFocusCursor ?? cursor)
    }

    // MARK: - Private

    /// フォーカスオーバーレイを解除する
    private func clearFocus() {
        lastFocusCursor = nil
        focusedSentenceRects = []
    }

    private func resetSelectionState() {
        smartSelection.reset()
        sentenceSelectionDirection = 0
    }

    private func safeIndex(_ cursor: Int, in text: String) -> String.Index {
        String.Index(utf16Offset: min(cursor, text.utf16.count), in: text)
    }

    private func updateFocusRects(text: String, cursor: Int) {
        guard !text.isEmpty else { focusedSentenceRects = []; return }
        let idx = safeIndex(cursor, in: text)
        let range = SentenceBoundary.sentenceRange(in: text, at: idx)
        let nsRange = NSRange(range, in: text)
        focusedSentenceRects = rectsProvider.getRects(nsRange)
    }

    /// 指定方向に1文伸ばす
    private func extendInDirection(_ direction: Int, text: String,
                                   cursor: Int, selection: Int,
                                   textLen: Int) -> CursorResult {
        if direction < 0 {
            let idx = String.Index(utf16Offset: min(cursor, textLen), in: text)
            let newStart = SentenceBoundary.previousSentenceStart(in: text, before: idx)
            let newCursor = newStart.utf16Offset(in: text)
            return CursorResult(cursor: newCursor,
                                selection: sentenceSelectionAnchor - newCursor)
        } else {
            let selEnd = cursor + selection
            let idx = String.Index(utf16Offset: min(selEnd, textLen), in: text)
            let newEnd = SentenceBoundary.nextSentenceEnd(in: text, after: idx)
            return CursorResult(cursor: cursor,
                                selection: newEnd.utf16Offset(in: text) - cursor)
        }
    }

    /// 1文分縮める
    private func shrinkSelection(text: String, cursor: Int,
                                 selection: Int, textLen: Int) -> CursorResult {
        if sentenceSelectionDirection < 0 {
            // 後方選択中 → ↓ で先頭側を縮める
            let idx = String.Index(utf16Offset: min(cursor, textLen), in: text)
            let newStart = SentenceBoundary.nextSentenceEnd(in: text, after: idx)
            let newOffset = newStart.utf16Offset(in: text)
            if newOffset >= sentenceSelectionAnchor {
                sentenceSelectionDirection = 0
                return CursorResult(cursor: sentenceSelectionAnchor, selection: 0)
            }
            return CursorResult(cursor: newOffset,
                                selection: sentenceSelectionAnchor - newOffset)
        } else {
            // 前方選択中 → ↑ で末尾側を縮める
            let selEnd = cursor + selection
            let idx = String.Index(utf16Offset: min(selEnd, textLen), in: text)
            let newEnd = SentenceBoundary.previousSentenceStart(in: text, before: idx)
            let newEndOffset = newEnd.utf16Offset(in: text)
            if newEndOffset <= sentenceSelectionAnchor {
                sentenceSelectionDirection = 0
                return CursorResult(cursor: sentenceSelectionAnchor, selection: 0)
            }
            return CursorResult(cursor: cursor,
                                selection: newEndOffset - cursor)
        }
    }
}
