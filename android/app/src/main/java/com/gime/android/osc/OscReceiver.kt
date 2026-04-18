package com.gime.android.osc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * OSC メッセージを UDP で受信するデバッグ用 receiver。
 *
 * - `0.0.0.0:port` にバインドして LAN からの受信も受け付ける
 * - 受信したメッセージは SharedFlow で配信（最新 64 件はリプレイされる）
 * - デコード失敗はエラーログとして配信
 *
 * 使い方:
 * ```
 * val receiver = OscReceiver(9001)
 * receiver.messages.collect { event -> ... }
 * receiver.start()
 * // ...
 * receiver.stop()
 * ```
 */
class OscReceiver(private val port: Int) {

    sealed class Event {
        data class Received(val message: OscMessage, val fromHost: String) : Event()
        data class Error(val reason: String) : Event()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null
    private var listenJob: Job? = null

    private val _messages = MutableSharedFlow<Event>(replay = 64, extraBufferCapacity = 64)
    val messages: SharedFlow<Event> = _messages.asSharedFlow()

    fun start() {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch {
            val s = try {
                DatagramSocket(InetSocketAddress("0.0.0.0", port))
            } catch (t: Throwable) {
                _messages.tryEmit(Event.Error("socket bind failed: ${t.message}"))
                return@launch
            }
            socket = s
            val buffer = ByteArray(4096)
            while (isActive) {
                val pkt = DatagramPacket(buffer, buffer.size)
                try {
                    s.receive(pkt)
                } catch (t: Throwable) {
                    if (isActive) {
                        _messages.tryEmit(Event.Error("receive failed: ${t.message}"))
                    }
                    break
                }
                val bytes = pkt.data.copyOfRange(0, pkt.length)
                try {
                    val msg = OscPacket.decode(bytes)
                    _messages.tryEmit(
                        Event.Received(msg, pkt.address?.hostAddress ?: "?")
                    )
                } catch (t: Throwable) {
                    _messages.tryEmit(Event.Error("decode failed: ${t.message}"))
                }
            }
        }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        listenJob?.cancel()
        listenJob = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
