import Foundation

/// VRChat chatbox / avatar parameter 系の OSC 送信を扱うラッパー。
///
/// `OscSender` をラップして:
/// - typing indicator の自動 on/off
/// - composing 中の下書き送信 (`/chatbox/input text false false`)
/// - 確定送信 (`/chatbox/input text true true`)
/// - 144 文字制限の自動トリム
/// - debounce: 下書き送信は最終更新から 100ms 後に発火（T9 rollback 等の
///   過渡状態を吸収する）
///
/// 送信は全て fire-and-forget。MainActor 分離されているので MainActor から
/// 呼び出すこと。
@MainActor
public final class VrChatOscOutput {
    /// VRChat chatbox の文字数上限
    public static let maxChatboxLen = 144
    /// 下書き送信の debounce 時間 (ms)
    public static let debounceMillis: UInt64 = 100

    private let sender: OscSender
    private var typingActive = false
    private var lastSentBody = ""
    private var pendingText: String?
    private var debounceTask: Task<Void, Never>?

    public init(sender: OscSender) {
        self.sender = sender
    }

    /// composing テキストの更新（debounce 付き）。
    /// 100ms 以内に連続呼び出しされた場合は最新のテキストのみが送信される。
    public func sendComposingText(_ text: String) {
        if text.isEmpty {
            debounceTask?.cancel()
            debounceTask = nil
            pendingText = nil
            finishTyping()
            return
        }
        if !typingActive {
            typingActive = true
            sender.send("/chatbox/typing", .bool(true))
        }
        pendingText = text
        debounceTask?.cancel()
        debounceTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: Self.debounceMillis * 1_000_000)
            guard !Task.isCancelled, let self else { return }
            guard let body = self.pendingText else { return }
            if body == self.lastSentBody { return }
            self.lastSentBody = body
            let truncated = truncate(body, max: Self.maxChatboxLen)
            self.sender.send(
                "/chatbox/input",
                .string(truncated), .bool(false), .bool(false)
            )
        }
    }

    /// 確定送信。通知音 + 永続表示で chatbox に確定メッセージが載る。
    /// debounce 中の下書きは破棄し、確定テキストを即送信する。
    public func commit(_ text: String) {
        debounceTask?.cancel()
        debounceTask = nil
        pendingText = nil
        let body = truncate(text, max: Self.maxChatboxLen)
        if body.isEmpty {
            finishTyping()
            return
        }
        sender.send(
            "/chatbox/input",
            .string(body), .bool(true), .bool(true)
        )
        sender.send("/chatbox/typing", .bool(false))
        lastSentBody = ""
        typingActive = false
    }

    /// typing indicator を OFF にする（composing を捨てたとき）。
    public func finishTyping() {
        if !typingActive && lastSentBody.isEmpty { return }
        typingActive = false
        lastSentBody = ""
        sender.send("/chatbox/typing", .bool(false))
        sender.send("/chatbox/input", .string(""), .bool(false), .bool(false))
    }

    /// 送信先を runtime に変更。
    public func updateTarget(host: String, port: UInt16) {
        sender.updateTarget(host: host, port: port)
    }

    public func close() {
        debounceTask?.cancel()
        debounceTask = nil
        finishTyping()
        sender.close()
    }

    // MARK: - ヘルパー

    /// Unicode スカラ単位で先頭 `max` 文字に切り詰める。
    /// 絵文字などの合成済み文字がバイト境界で切られて化けないよう、
    /// `String.prefix` を使い Character 単位で丸める。
    private func truncate(_ text: String, max: Int) -> String {
        if text.count <= max { return text }
        return String(text.prefix(max))
    }
}
