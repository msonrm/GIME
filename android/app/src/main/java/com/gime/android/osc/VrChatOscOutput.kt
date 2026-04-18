package com.gime.android.osc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * VRChat chatbox / avatar parameter 系の OSC 送信を扱うラッパー。
 *
 * `OscSender` をラップして:
 * - typing indicator の自動 on/off
 * - composing 中の下書き送信 (/chatbox/input text false false)
 * - 確定送信 (/chatbox/input text true true)
 * - 144 文字制限の自動トリム
 * - debounce: 下書き送信は最終更新から 100ms 後に発火（T9 rollback 抑制）
 *
 * を提供する。送信は全て `serviceScope` 上で非同期 fire-and-forget。
 */
class VrChatOscOutput(
    private val sender: OscSender,
    private val scope: CoroutineScope,
) {

    private var typingActive = false
    private var lastSentBody = ""
    private var pendingText: String? = null
    private var debounceJob: Job? = null

    /**
     * 確定時のみ送信するモード。VRChat Mobile が下書き受信で chatbox 入力 UI を
     * 開いてしまう問題を回避するために使う。ON の間は `sendComposingText` が
     * /chatbox/input 下書きを送らず、`commit` だけが実際に /chatbox/input を送る。
     * typing indicator の送信は sendTypingIndicator で別途制御する。
     */
    var commitOnly: Boolean = false

    /**
     * /chatbox/typing（タイピング 3 点インジケータ）を送るか。
     * ON の間は composing 開始で typing=true、確定 or クリアで typing=false。
     * OFF の間は typing 系パケットを一切送らない。
     * commitOnly と独立。
     */
    var sendTypingIndicator: Boolean = true

    /// composing テキストの更新（debounce 付き）。
    /// 100ms 以内に連続呼び出しされた場合は最新の text のみが送信され、
    /// 途中の T9 rollback 等の過渡状態はスキップされる。
    fun sendComposingText(text: String) {
        if (text.isEmpty()) {
            debounceJob?.cancel()
            pendingText = null
            finishTyping()
            return
        }
        if (sendTypingIndicator && !typingActive) {
            typingActive = true
            scope.launch {
                runCatching { sender.send("/chatbox/typing", true) }
            }
        }
        if (commitOnly) return
        pendingText = text
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            val body = pendingText ?: return@launch
            if (body == lastSentBody) return@launch
            lastSentBody = body
            val truncated = body.take(MAX_CHATBOX_LEN)
            runCatching { sender.send("/chatbox/input", truncated, false, false) }
        }
    }

    /// 確定送信。通知音 + 永続表示で chatbox に確定メッセージが載る。
    /// debounce 中の下書きは破棄し、確定テキストを即送信する。
    fun commit(text: String) {
        debounceJob?.cancel()
        pendingText = null
        val body = text.take(MAX_CHATBOX_LEN)
        if (body.isEmpty()) {
            finishTyping()
            return
        }
        scope.launch {
            runCatching {
                sender.send("/chatbox/input", body, true, true)
                if (sendTypingIndicator) sender.send("/chatbox/typing", false)
            }
        }
        lastSentBody = ""
        typingActive = false
    }

    /// typing indicator を OFF にする（composing を捨てたとき）。
    /// commitOnly 時は /chatbox/input クリアだけスキップし、typing=false は
    /// sendTypingIndicator に従って送る。
    fun finishTyping() {
        val hadTyping = typingActive
        val hadBody = lastSentBody.isNotEmpty()
        if (!hadTyping && !hadBody) return
        typingActive = false
        lastSentBody = ""
        scope.launch {
            runCatching {
                if (hadTyping && sendTypingIndicator) {
                    sender.send("/chatbox/typing", false)
                }
                if (hadBody && !commitOnly) {
                    sender.send("/chatbox/input", "", false, false)
                }
            }
        }
    }

    /// 送信先を runtime に変更。
    fun updateTarget(host: String, port: Int) {
        sender.updateTarget(host, port)
    }

    fun close() {
        debounceJob?.cancel()
        try { finishTyping() } catch (_: Throwable) {}
        sender.close()
    }

    companion object {
        /** VRChat chatbox の文字数上限 */
        const val MAX_CHATBOX_LEN = 144
        /** 下書き送信の debounce 時間 (ms) */
        const val DEBOUNCE_MS = 100L
    }
}
