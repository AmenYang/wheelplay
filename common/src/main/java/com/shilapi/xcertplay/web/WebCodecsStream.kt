package com.shilapi.xcertplay.web

import android.util.Log
import com.shilapi.xcertplay.media.CompressedVideoFrame
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pushes CarPlay access units to the browser as WebSocket binary frames for WebCodecs decoding.
 *
 * This is DashPlay/PlayPort's transport (upstream: `WebHub` + `web/src/video.ts`) lifted into the
 * Android host. Compared with the WebRTC path there is no RTP packetisation, no DTLS/SRTP, no
 * jitter buffer and no NACK/RTCP loop: one TCP stream already retransmits, so the only congestion
 * control needed is "drop everything until the next IDR" once the writer falls behind.
 */
internal class WebCodecsStream(
    private val source: WebVideoSource,
    private val config: WebVideoSource.Config,
    private val dimensions: () -> Pair<Int, Int>,
    private val write: (bytes: ByteArray, keyframe: Boolean) -> Boolean,
    private val failure: (String) -> Unit,
) : VideoStreamConsumer {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val queue =
        RtcFrameQueue(
            maxAgeNs = FRAME_MAX_AGE_NS,
            maxBytes = MAX_QUEUE_BYTES,
            maxFrames = MAX_QUEUE_FRAMES,
        )
    private var scheduled = false
    private var ptsOrigin: Long? = null

    @Volatile override var connected = false
        private set

    @Volatile var sentFrames = 0L
        private set

    @Volatile private var displayed = false
    val holdingFrame get() = displayed

    fun start() {
        execute {
            if (closed.get()) return@execute
            sendConfig()
            ptsOrigin = null
            connected = true
        }
        try {
            worker.schedule(
                { if (!closed.get() && !displayed) source.requestKeyframe(force = true) },
                FIRST_FRAME_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Re-sends the decoder configuration; the page asks for this when it rebuilt its decoder. */
    fun resendConfig() = execute { if (!closed.get()) sendConfig() }

    private fun sendConfig() {
        val (width, height) = dimensions()
        write(WebCodecsWire.config(config.type, config.codec, width, height, config.parameters), true)
    }

    override fun frame(frame: CompressedVideoFrame, pts: Long) {
        if (closed.get()) return
        val keyframe = frame.isRandomAccess(config.codec)
        synchronized(lock) {
            if (closed.get()) return
            val accepted =
                queue.offer(frame, pts, keyframe, System.nanoTime(), if (keyframe) config.parameters.size else 0)
            if (!accepted) {
                if (queue.needsKeyframe) source.requestKeyframe()
                return
            }
            if (!scheduled) {
                scheduled = true
                scheduleDrain()
            }
        }
    }

    private fun scheduleDrain() {
        try {
            worker.execute(::drain)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun drain() {
        // Bounded batch so close/reconfigure tasks are never starved by a fast producer.
        repeat(DRAIN_BATCH) {
            val next =
                synchronized(lock) {
                    if (closed.get()) {
                        scheduled = false
                        return
                    }
                    val item = queue.poll(System.nanoTime())
                    if (item == null) {
                        scheduled = false
                        if (queue.needsKeyframe) source.requestKeyframe()
                        return
                    }
                    item
                }
            try {
                val origin = ptsOrigin ?: next.pts.also { ptsOrigin = it }
                val micros = (next.pts - origin).coerceAtLeast(0)
                val bytes = WebCodecsWire.frame(config.type, next.keyframe, micros, next.data.toByteArray())
                if (write(bytes, next.keyframe)) {
                    sentFrames++
                } else {
                    // The socket writer is behind. Skip to the next IDR instead of queueing stale frames.
                    synchronized(lock) { queue.invalidate(System.nanoTime()) }
                    source.requestKeyframe()
                }
            } finally {
                next.data.close()
            }
        }
        if (!closed.get()) scheduleDrain()
    }

    override fun displayed() {
        if (!connected || sentFrames == 0L) return
        displayed = true
        source.displayed(this)
    }

    fun snapshot(): Map<String, Any?> = synchronized(lock) {
        mapOf(
            "transport" to "webcodecs",
            "sentFrames" to sentFrames,
            "queueFrames" to queue.size,
            "queueBytes" to queue.bytes,
            "expiredFrames" to queue.expired,
            "overflowFrames" to queue.overflow,
            "keyframeSkips" to queue.skipped,
            "codec" to config.codec.name,
            "displayed" to displayed,
        )
    }

    private fun execute(task: () -> Unit) {
        if (closed.get()) return
        try {
            worker.execute {
                if (closed.get()) return@execute
                try {
                    task()
                } catch (error: Exception) {
                    Log.w(TAG, "WebCodecs stream failed; falling back to images", error)
                    failure("transport exception")
                } catch (error: LinkageError) {
                    Log.w(TAG, "WebCodecs transport unavailable; falling back to images", error)
                    failure("transport unavailable")
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected = false
        synchronized(lock) { queue.invalidate() }
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "WheelPlayWebCodecs"

        /** A frame older than this is worthless for a real-time car display. */
        private const val FRAME_MAX_AGE_NS = 200_000_000L

        /** Roughly two 1080p IDRs plus change; also caps the memory a stalled page can pin. */
        private const val MAX_QUEUE_BYTES = 6 * 1024 * 1024

        private const val MAX_QUEUE_FRAMES = 4
        private const val DRAIN_BATCH = 8
        private const val FIRST_FRAME_MS = 4000L
    }
}
