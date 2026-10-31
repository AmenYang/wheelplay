package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.media.VideoCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the binary contract with `webcodecs-player.js` (`parsePacket`). */
class WebCodecsWireTest {
    @Test
    fun configPacketMatchesTheBrowserParser() {
        val codecData = byteArrayOf(1, 0x42, 0xe0.toByte(), 0x1e, 0xff.toByte())
        val bytes = WebCodecsWire.config(1, VideoCodec.H264, 1920, 1080, codecData)

        assertEquals(0x31504357, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0))
        assertEquals(WebCodecsWire.TYPE_CONFIG, bytes[4].toInt())
        assertEquals(1, bytes[5].toInt())
        assertEquals(0, bytes[6].toInt())
        assertEquals(WebCodecsWire.HEADER_BYTES + 5 + codecData.size, bytes.size)

        // payload: codec u8, width u16 little-endian, height u16 little-endian, avcC
        assertEquals(WebCodecsWire.CODEC_H264, bytes[16].toInt())
        assertEquals(1920, (bytes[17].toInt() and 0xff) or ((bytes[18].toInt() and 0xff) shl 8))
        assertEquals(1080, (bytes[19].toInt() and 0xff) or ((bytes[20].toInt() and 0xff) shl 8))
        assertTrue(bytes.copyOfRange(21, bytes.size).contentEquals(codecData))
    }

    @Test
    fun framePacketCarriesKeyframeFlagAndPts() {
        val nals = byteArrayOf(0, 0, 0, 1, 0x65, 0x01, 0x02)
        val key = WebCodecsWire.frame(2, keyframe = true, ptsMicros = 40_000, annexB = nals)
        val delta = WebCodecsWire.frame(2, keyframe = false, ptsMicros = 56_000, annexB = nals)

        assertEquals(WebCodecsWire.TYPE_FRAME, key[4].toInt())
        assertEquals(2, key[5].toInt())
        assertEquals(WebCodecsWire.FLAG_KEYFRAME, key[6].toInt() and 0xff)
        assertEquals(40_000L, ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN).getLong(8))
        assertTrue(key.copyOfRange(WebCodecsWire.HEADER_BYTES, key.size).contentEquals(nals))

        assertEquals(0, delta[6].toInt())
        assertEquals(56_000L, ByteBuffer.wrap(delta).order(ByteOrder.LITTLE_ENDIAN).getLong(8))
    }

    @Test
    fun h265UsesItsOwnCodecId() {
        val bytes = WebCodecsWire.config(1, VideoCodec.H265, 1280, 720, byteArrayOf(1, 2, 3))
        assertEquals(WebCodecsWire.CODEC_H265, bytes[16].toInt())
    }
}
