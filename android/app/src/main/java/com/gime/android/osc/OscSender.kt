package com.gime.android.osc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * OSC メッセージを UDP で送信するシンプルな sender。
 *
 * - スレッドセーフ（送信は `Dispatchers.IO` 上で実行）
 * - 送信先は runtime に変更可能（`updateTarget`）
 * - 接続状態は維持せず、毎回 DatagramPacket を送る fire-and-forget
 *
 * 使い方:
 * ```
 * val sender = OscSender("127.0.0.1", 9000)
 * sender.send("/chatbox/input", "こんにちは", true, false)
 * sender.close()
 * ```
 */
class OscSender(host: String, port: Int) {

    private data class Target(val address: InetAddress, val port: Int)

    private val target = AtomicReference(resolve(host, port))
    private val socket: DatagramSocket = DatagramSocket()

    /** 送信先を更新する。IP 解決は同期的に行う（Main スレッドで呼ぶのは避ける）。 */
    fun updateTarget(host: String, port: Int) {
        target.set(resolve(host, port))
    }

    /** 現在の送信先（表示用）。 */
    fun currentTarget(): Pair<String, Int> {
        val t = target.get()
        return t.address.hostAddress to t.port
    }

    /**
     * OSC メッセージをエンコードして送信。例外は呼出側に throw する。
     * 呼び出しは suspend で `Dispatchers.IO` に切り替え。
     */
    suspend fun send(address: String, vararg args: Any) {
        val bytes = OscPacket.encode(address, *args)
        sendRaw(bytes)
    }

    /** 生バイト列を送信（デバッグ用）。 */
    suspend fun sendRaw(bytes: ByteArray) {
        val t = target.get()
        withContext(Dispatchers.IO) {
            val pkt = DatagramPacket(bytes, bytes.size, t.address, t.port)
            socket.send(pkt)
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private fun resolve(host: String, port: Int): Target {
            val addr = InetAddress.getByName(host)
            return Target(addr, port)
        }
    }
}
