package com.gime.android.osc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OscPacketTest {

    // MARK: - エンコード

    @Test
    fun encodeAddressOnly_padsToBoundary() {
        // "/a" (2 bytes) + null (1) → 4 byte 境界なので pad 1 byte
        // type tag "," (1) + null (1) → 4 byte 境界まで pad 2 byte
        val bytes = OscPacket.encode("/a")
        val expected = byteArrayOf(
            '/'.code.toByte(), 'a'.code.toByte(), 0, 0,
            ','.code.toByte(), 0, 0, 0,
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun encodeStringArgument() {
        // /test  (null含めて 5 bytes → pad to 8): "/test\0\0\0"
        // ,s     (null含めて 3 bytes → pad to 4): ",s\0\0"
        // "abc"  (null含めて 4 bytes → pad to 4): "abc\0"
        val bytes = OscPacket.encode("/test", "abc")
        val expected = byteArrayOf(
            '/'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(),
            't'.code.toByte(), 0, 0, 0,
            ','.code.toByte(), 's'.code.toByte(), 0, 0,
            'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0,
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun encodeInt() {
        // /i  pad 4, ,i pad 4, int32 big-endian 42
        val bytes = OscPacket.encode("/i", 42)
        val expected = byteArrayOf(
            '/'.code.toByte(), 'i'.code.toByte(), 0, 0,
            ','.code.toByte(), 'i'.code.toByte(), 0, 0,
            0, 0, 0, 42,
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun encodeBooleanTrue_noPayload() {
        // T / F は引数データなし（type tag だけ）
        val bytes = OscPacket.encode("/b", true)
        val expected = byteArrayOf(
            '/'.code.toByte(), 'b'.code.toByte(), 0, 0,
            ','.code.toByte(), 'T'.code.toByte(), 0, 0,
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun encodeVrChatChatbox_roundTrip() {
        // 実戦想定: VRChat chatbox メッセージ
        val bytes = OscPacket.encode("/chatbox/input", "こんにちは", true, false)
        val msg = OscPacket.decode(bytes)
        assertEquals("/chatbox/input", msg.address)
        assertEquals(3, msg.args.size)
        assertEquals("こんにちは", msg.args[0])
        assertEquals(true, msg.args[1])
        assertEquals(false, msg.args[2])
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodeInvalidAddress_throws() {
        OscPacket.encode("noslash", "x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodeUnsupportedArg_throws() {
        OscPacket.encode("/x", listOf(1, 2, 3))
    }

    // MARK: - デコード

    @Test
    fun decodeIntMessage() {
        val bytes = OscPacket.encode("/viseme", 12)
        val msg = OscPacket.decode(bytes)
        assertEquals("/viseme", msg.address)
        assertEquals(listOf<Any>(12), msg.args)
    }

    @Test
    fun decodeFloat_roundTrip() {
        val bytes = OscPacket.encode("/f", 3.14f)
        val msg = OscPacket.decode(bytes)
        assertEquals("/f", msg.address)
        assertEquals(1, msg.args.size)
        assertTrue(msg.args[0] is Float)
        assertEquals(3.14f, msg.args[0] as Float, 0.0001f)
    }

    @Test(expected = OscDecodeException::class)
    fun decodeBundle_throws() {
        // bundle プレフィックスは未対応
        val bytes = OscPacket.encode("/x", "bundle-test")
        // address を "#bundle" に書き換えてデコード
        val mod = byteArrayOf(
            '#'.code.toByte(), 'b'.code.toByte(), 'u'.code.toByte(), 'n'.code.toByte(),
            'd'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 0,
        ) + bytes.drop(8).toByteArray()
        OscPacket.decode(mod)
    }

    @Test
    fun decodeMultipleMixedArgs() {
        val bytes = OscPacket.encode("/mix", "label", 100, 0.5f, true, false)
        val msg = OscPacket.decode(bytes)
        assertEquals("/mix", msg.address)
        assertEquals("label", msg.args[0])
        assertEquals(100, msg.args[1])
        assertEquals(0.5f, msg.args[2] as Float, 0.0001f)
        assertEquals(true, msg.args[3])
        assertEquals(false, msg.args[4])
    }

    // MARK: - toString (表示用)

    @Test
    fun messageToString_human() {
        val msg = OscMessage("/chatbox/input", listOf("Hello", true, false))
        assertEquals("/chatbox/input \"Hello\" true false", msg.toString())
    }
}
