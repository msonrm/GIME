package com.gime.android.osc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OSC 1.0 パケットのエンコーダ/デコーダ（外部依存なしの最小実装）。
 *
 * サポートする型タグ:
 * - `s` : string（UTF-8、null 終端、4 byte アラインメント）
 * - `i` : int32（big-endian）
 * - `f` : float32（big-endian IEEE 754）
 * - `T` : true （引数データなし）
 * - `F` : false（引数データなし）
 *
 * Bundles (`#bundle`) は今回対象外（VRChat chatbox / avatar parameters には不要）。
 *
 * 参考: https://opensoundcontrol.stanford.edu/spec-1_0.html
 */
object OscPacket {

    /**
     * OSC メッセージを byte 配列にエンコードする。
     *
     * @param address OSC アドレスパターン（例: "/chatbox/input"）
     * @param args 引数。サポート型: String, Int, Float, Boolean。
     *             Double は Float に丸める。Long は Int にキャストする（オーバーフロー注意）。
     */
    fun encode(address: String, vararg args: Any): ByteArray {
        require(address.startsWith("/")) { "OSC address must start with '/': $address" }

        // 1. type tag string: ",<tags>"
        val typeTags = StringBuilder(",")
        for (arg in args) {
            typeTags.append(
                when (arg) {
                    is String -> 's'
                    is Int -> 'i'
                    is Long -> 'i'
                    is Float -> 'f'
                    is Double -> 'f'
                    is Boolean -> if (arg) 'T' else 'F'
                    else -> throw IllegalArgumentException(
                        "Unsupported OSC argument type: ${arg::class.java.name}"
                    )
                }
            )
        }

        // 2. 動的バッファに書き込む
        //    アドレス（padded string） + type tag（padded string） + 引数データ
        val buf = ByteBuffer.allocate(estimatedSize(address, typeTags.toString(), args))
            .order(ByteOrder.BIG_ENDIAN)
        writePaddedString(buf, address)
        writePaddedString(buf, typeTags.toString())
        for (arg in args) {
            when (arg) {
                is String -> writePaddedString(buf, arg)
                is Int -> buf.putInt(arg)
                is Long -> buf.putInt(arg.toInt())
                is Float -> buf.putFloat(arg)
                is Double -> buf.putFloat(arg.toFloat())
                is Boolean -> {
                    // T / F はデータなし
                }
            }
        }

        val result = ByteArray(buf.position())
        buf.flip()
        buf.get(result)
        return result
    }

    /**
     * byte 配列から OSC メッセージをデコードする。
     * Bundle は未対応（`#bundle` で始まるパケットは [OscDecodeException] を投げる）。
     */
    fun decode(bytes: ByteArray): OscMessage {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val address = readPaddedString(buf)
        if (address == "#bundle") {
            throw OscDecodeException("OSC bundle is not supported")
        }
        if (!address.startsWith("/")) {
            throw OscDecodeException("Invalid OSC address: $address")
        }
        val typeTagStr = readPaddedString(buf)
        if (!typeTagStr.startsWith(",")) {
            throw OscDecodeException("Invalid OSC type tag: $typeTagStr")
        }
        val tags = typeTagStr.substring(1)
        val args = mutableListOf<Any>()
        for (tag in tags) {
            when (tag) {
                's' -> args.add(readPaddedString(buf))
                'i' -> args.add(buf.int)
                'f' -> args.add(buf.float)
                'T' -> args.add(true)
                'F' -> args.add(false)
                'N' -> { /* Null 値、とりあえず無視 */ }
                else -> throw OscDecodeException("Unsupported type tag: '$tag'")
            }
        }
        return OscMessage(address, args)
    }

    // MARK: - ヘルパー

    private fun writePaddedString(buf: ByteBuffer, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        buf.put(bytes)
        buf.put(0)
        // 4 byte アラインメントまで null padding
        val pad = (4 - (bytes.size + 1) % 4) % 4
        for (i in 0 until pad) buf.put(0)
    }

    private fun readPaddedString(buf: ByteBuffer): String {
        val start = buf.position()
        var end = start
        while (end < buf.limit() && buf.get(end) != 0.toByte()) end++
        if (end >= buf.limit()) throw OscDecodeException("Unterminated string")
        val strBytes = ByteArray(end - start)
        buf.get(strBytes)
        // null byte + アラインメント分を読み飛ばす
        buf.get() // null terminator
        val consumed = end - start + 1
        val pad = (4 - consumed % 4) % 4
        for (i in 0 until pad) buf.get()
        return String(strBytes, Charsets.UTF_8)
    }

    private fun estimatedSize(address: String, typeTags: String, args: Array<out Any>): Int {
        // 粗い見積もり（pad を 4 で丸める）。最大値を返す
        var size = padded(address.toByteArray(Charsets.UTF_8).size + 1)
        size += padded(typeTags.toByteArray(Charsets.UTF_8).size + 1)
        for (arg in args) {
            size += when (arg) {
                is String -> padded(arg.toByteArray(Charsets.UTF_8).size + 1)
                is Int, is Long, is Float, is Double -> 4
                is Boolean -> 0
                else -> 0
            }
        }
        return size + 16 // 安全マージン
    }

    private fun padded(n: Int): Int = ((n + 3) / 4) * 4
}

/**
 * デコードされた OSC メッセージ。
 */
data class OscMessage(val address: String, val args: List<Any>) {
    override fun toString(): String {
        val argStr = args.joinToString(" ") { a ->
            when (a) {
                is String -> "\"$a\""
                else -> a.toString()
            }
        }
        return if (argStr.isEmpty()) address else "$address $argStr"
    }
}

class OscDecodeException(message: String) : RuntimeException(message)
