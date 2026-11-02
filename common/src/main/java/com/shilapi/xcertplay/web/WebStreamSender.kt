package com.shilapi.xcertplay.web

import android.os.SystemClock
import com.shilapi.xcertplay.SessionLogFile
import com.shilapi.xcertplay.media.MediaAudioBuffer
import fi.iki.elonen.NanoWSD.WebSocketFrame
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One writer, bounded still-image pipeline, and coalesced control replies. Never writes under [lock]. */
internal class WebStreamSender(
    private val latestFrame: () -> WebSession.Frame?,
    private val write: (WebSocketFrame) -> Unit,
    private val onFailure: () -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val versionedVideo: Boolean = false,
    private val imageProtocol: Int = if (versionedVideo) 2 else 1,
) : Closeable {
    enum class Control { STATUS, PHONE_CONNECT, INPUT_UNAVAILABLE, BUSY, PONG, CLOSE, RTC, MIC_CONFIG, MIC_STOP, AUDIO_STOP, AUDIO_RESET, AUDIO_ROUTE, NOW_PLAYING, MEDIA_COMMAND_RESULT }
    private data class Outgoing(val frame: WebSocketFrame, val afterSend: () -> Unit = {})
    private val lock = Any()
    private val controls = linkedMapOf<Any, Outgoing>()
    private var closed = false
    private var scheduled = false
    private data class ImageId(val session: Long, val epoch: Long, val sequence: Long)
    private data class ImageFlight(val at: Long, val bytes: Int)
    private val imagesInFlight = linkedMapOf<ImageId, ImageFlight>()
    private var singleFrameUntil = 0L
    private var lastImageAckMs = 0L
    private var sequence = -1L
    private data class Audio(val frame: WebSocketFrame, val sequence: Long, val at: Long,
        val stream: Int, val media: Boolean, val durationMillis: Double)
    private val audio = java.util.ArrayDeque<Audio>()
    private val audioInFlight = linkedMapOf<Long, Long>()
    private var mediaBufferMillis = MediaAudioBuffer.DEFAULT_MILLIS
    private var audioDropped = 0L
    private var audioSent = 0L
    private var audioStatsAt = 0L
    // WebCodecs transport: a shallow queue so a slow socket never turns into latency.
    private val videos = java.util.ArrayDeque<Outgoing>()
    private var videoDropped = 0L
    private var videoSent = 0L

    fun configureAudioBuffer(millis: Int) = synchronized(lock) {
        mediaBufferMillis = MediaAudioBuffer.sanitize(millis)
    }

    private fun audioFrame(packet: ByteArray, sequence: Long): Audio {
        val header = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (packet.size >= 22 && header.int == BrowserAudioBridge.AUDIO_MAGIC) {
            val stream = header.int
            val rate = header.int
            val channels = header.short.toInt()
            val media = header.short.toInt() and BrowserAudioBridge.AUDIO_FLAG_MEDIA != 0
            if (rate in 8_000..96_000 && channels in 1..2 && (packet.size - 20) % (channels * 2) == 0) {
                return Audio(WebSocketFrame(WebSocketFrame.OpCode.Binary, true, packet), sequence, now(),
                    stream, media, (packet.size - 20) * 1000.0 / (rate * channels * 2))
            }
        }
        return Audio(WebSocketFrame(WebSocketFrame.OpCode.Binary, true, packet), sequence, now(), 0, false, 100.0 / 16)
    }

    private fun audioBudgetMillis(frame: Audio) = if (frame.media) mediaBufferMillis + 200 else 100

    /** Called under the lock, at most once every five seconds, and only when audio was lost. */
    private fun reportAudioLocked() {
        if (audioDropped == 0L) return
        val at = now()
        if (audioStatsAt == 0L) audioStatsAt = at
        if (at - audioStatsAt < AUDIO_STATS_WINDOW_MS) return
        SessionLogFile.report(
            "web-audio dropped=$audioDropped sent=$audioSent budget=${mediaBufferMillis + 200}ms " +
                "queued=${audio.size} inFlight=${audioInFlight.size}",
        )
        audioDropped = 0
        audioSent = 0
        audioStatsAt = at
    }

    fun audio(packet: ByteArray, sequence: Long) = synchronized(lock) {
        if (!closed) {
            val next = audioFrame(packet, sequence)
            audio.addLast(next)
            var queuedMillis = audio.filter { it.stream == next.stream }.sumOf { it.durationMillis }
            while (queuedMillis > audioBudgetMillis(next) && audio.count { it.stream == next.stream } > 1) {
                val oldest = audio.first { it.stream == next.stream }
                audio.remove(oldest); queuedMillis -= oldest.durationMillis
                audioDropped++
            }
            // Duration is the main bound; this also caps pathological tiny PCM packets.
            while (audio.size > 128) audio.removeFirst()
            scheduleLocked()
        }
    }
    fun acknowledgeAudio(sequence: Long) = synchronized(lock) {
        audioInFlight.keys.removeAll { it <= sequence }
        scheduleLocked()
    }
    fun clearAudio() = synchronized(lock) { audio.clear(); audioInFlight.clear() }
    fun clearAudioStream(stream: Int) = synchronized(lock) {
        audio.removeAll {
            val bytes = it.frame.binaryPayload
            bytes.size >= 8 && java.nio.ByteBuffer.wrap(bytes, 4, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int == stream
        }
    }

    fun frameAvailable() = synchronized(lock) { scheduleLocked() }

    /**
     * Queues one WebCodecs packet. Returns false when the socket writer is already behind, which
     * tells the caller to drop queued frames and resynchronise on the next IDR.
     */
    fun videoPacket(bytes: ByteArray): Boolean = synchronized(lock) {
        if (closed) return false
        if (videos.size >= VIDEO_QUEUE_DEPTH) {
            videos.clear()
            videoDropped++
            return false
        }
        videos.addLast(Outgoing(WebSocketFrame(WebSocketFrame.OpCode.Binary, true, bytes)))
        scheduleLocked()
        return true
    }

    fun videoSnapshot(): Map<String, Any> = synchronized(lock) {
        mapOf("queued" to videos.size, "sent" to videoSent, "dropped" to videoDropped)
    }

    fun acknowledge() = synchronized(lock) {
        if (imageProtocol == 3) return@synchronized // Unversioned/stale ACKs cannot free v3 slots.
        imagesInFlight.clear()
        scheduleLocked()
    }

    fun acknowledge(session: Long, epoch: Long, sequence: Long) = synchronized(lock) {
        if (imageProtocol != 3) return@synchronized
        val flight = imagesInFlight.remove(ImageId(session, epoch, sequence)) ?: return@synchronized
        lastImageAckMs = (now() - flight.at).coerceAtLeast(0)
        if (lastImageAckMs >= 250) singleFrameUntil = now() + 1000
        scheduleLocked()
    }

    fun imageSnapshot(): Map<String, Any> = synchronized(lock) {
        mapOf("protocol" to imageProtocol, "inFlight" to imagesInFlight.size,
            "inFlightBytes" to imagesInFlight.values.sumOf { it.bytes },
            "window" to imageWindow(), "lastAckMs" to lastImageAckMs)
    }

    private fun imageWindow(): Int = if (imageProtocol != 3 || now() < singleFrameUntil ||
        imagesInFlight.values.firstOrNull()?.let { now() - it.at >= 250 } == true) 1 else 2

    private fun nextImage(): WebSession.Frame? {
        if (imagesInFlight.size >= imageWindow()) return null
        val frame = latestFrame()?.takeIf { it.sequence != sequence } ?: return null
        // Permit one oversized image so high-resolution/static screens can still make progress.
        if (imagesInFlight.isNotEmpty() && imagesInFlight.values.sumOf { it.bytes.toLong() } +
                frame.jpeg.size > 2 * 1024 * 1024) return null
        return frame
    }

    fun control(kind: Control, frame: WebSocketFrame, key: Any = kind, afterSend: () -> Unit = {}) = synchronized(lock) {
        if (!closed) {
            controls[key] = Outgoing(frame, afterSend)
            scheduleLocked()
        }
    }

    fun frameStalled(at: Long): Boolean = synchronized(lock) {
        imagesInFlight.values.firstOrNull()?.let { at - it.at > 6000 } == true ||
            audioInFlight.values.firstOrNull()?.let { at - it > 2000 } == true
    }

    private fun scheduleLocked() {
        if (closed || scheduled) return
        if (videos.isEmpty() && controls.isEmpty() && (audio.isEmpty() || audioInFlight.size >= 4)) {
            if (nextImage() == null) return
        }
        scheduled = true
        executor.execute(::drain)
    }

    private fun drain() {
        var lastVideo = false
        try {
            while (true) {
                var sentVideo = false
                val outgoing = synchronized(lock) {
                    if (closed) return
                    val control = controls.entries.firstOrNull()
                    // Video goes first, but yields to audio every other turn so sound never starves.
                    val videoReady = videos.isNotEmpty() &&
                        (!lastVideo || audio.isEmpty() || audioInFlight.size >= 4)
                    val audioReady = audio.isNotEmpty() && audioInFlight.size < 4
                    when {
                        control != null -> {
                            controls.remove(control.key)
                            control.value
                        }
                        videoReady -> {
                            videoSent++
                            sentVideo = true
                            videos.removeFirst()
                        }
                        audioReady -> {
                            // Audio that waited longer than the budget is audible as a gap; count
                            // it, because a silent sender-side drop looks exactly like radio jitter.
                            val before = audio.size
                            audio.removeAll { now() - it.at > audioBudgetMillis(it) }
                            audioDropped += before - audio.size
                            if (audio.isEmpty()) {
                                scheduled = false
                                scheduleLocked()
                                return
                            }
                            val next = audio.removeFirst()
                            audioInFlight[next.sequence] = now()
                            audioSent++
                            reportAudioLocked()
                            Outgoing(next.frame)
                        }
                        else -> {
                            val frame = nextImage() ?: run {
                                // Events use the same lock: none can be lost while the writer goes idle.
                                scheduled = false
                                return
                            }
                            imagesInFlight[ImageId(frame.sessionId, frame.videoEpoch, frame.sequence)] =
                                ImageFlight(now(), frame.jpeg.size)
                            sequence = frame.sequence
                            Outgoing(WebSocketFrame(WebSocketFrame.OpCode.Binary, true,
                                if (imageProtocol >= 2) VideoPacket.encode(frame, imageProtocol) else frame.jpeg))
                        }
                    }
                }
                write(outgoing.frame)
                outgoing.afterSend()
                lastVideo = sentVideo
            }
        } catch (_: Exception) {
            close()
            onFailure()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            controls.clear()
            imagesInFlight.clear()
            videos.clear()
            audio.clear(); audioInFlight.clear()
            // Shutdown under the lock prevents a racing event from scheduling after shutdown.
            executor.shutdownNow()
        }
    }

    private companion object {
        /** Three frames is roughly one round trip of headroom at 60 fps; deeper just adds latency. */
        const val VIDEO_QUEUE_DEPTH = 3
        const val AUDIO_STATS_WINDOW_MS = 5_000L
    }
}
