package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.media.VideoCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary framing for the WebCodecs transport, carried as WebSocket binary messages.
 *
 * ```
 * [magic u32 "WCP1"][type u8][streamType u8][flags u8][reserved u8][ptsMicros u64][payload]
 * ```
 *
 * * type 1 CONFIG: `[codec u8][width u16][height u16][avcC / hvcC record]`
 * * type 2 FRAME: Annex B NAL units of one access unit
 *
 * Audio keeps WheelPlay's existing PCM framing, so the browser tells the two apart by the leading
 * magic instead of by message order. The layout mirrors PlayPort's `Wire.kt` (DashPlay's upstream)
 * with a WheelPlay magic word so both streams can share one socket.
 */
internal object WebCodecsWire {
    /** "WCP1" — WheelPlay WebCodecs Packet, version 1. */
    const val MAGIC = 0x31504357

    const val HEADER_BYTES = 16

    const val TYPE_CONFIG = 1
    const val TYPE_FRAME = 2

    const val CODEC_H264 = 1
    const val CODEC_H265 = 2

    const val FLAG_KEYFRAME = 1

    fun config(
        streamType: Int,
        codec: VideoCodec,
        width: Int,
        height: Int,
        codecData: ByteArray,
    ): ByteArray {
        val payload = ByteBuffer.allocate(5 + codecData.size).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(if (codec == VideoCodec.H265) CODEC_H265.toByte() else CODEC_H264.toByte())
        payload.putShort(width.coerceIn(0, 8192).toShort())
        payload.putShort(height.coerceIn(0, 8192).toShort())
        payload.put(codecData)
        return packet(TYPE_CONFIG, streamType, 0, 0L, payload.array())
    }

    fun frame(streamType: Int, keyframe: Boolean, ptsMicros: Long, annexB: ByteArray): ByteArray =
        packet(TYPE_FRAME, streamType, if (keyframe) FLAG_KEYFRAME else 0, ptsMicros, annexB)

    private fun packet(
        type: Int,
        streamType: Int,
        flags: Int,
        ptsMicros: Long,
        payload: ByteArray,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(type.toByte())
        buffer.put(streamType.toByte())
        buffer.put(flags.toByte())
        buffer.put(0)
        buffer.putLong(ptsMicros)
        buffer.put(payload)
        return buffer.array()
    }
}
