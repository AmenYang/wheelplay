package com.shilapi.xcertplay

import android.system.Os
import android.system.OsConstants
import com.shilapi.xcertplay.web.TeslaDnsStats
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A number for "it feels slow". The head unit has no adb while it is in the car, so the only way
 * to tell a CPU-bound session apart from an idle one is to write the process load into the session
 * log. Counters are reset per session so one drive never inherits the previous one's totals.
 */
internal object SessionPerf {
    private val ticksPerSecond =
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK).toDouble() }.getOrDefault(100.0)
    private val imageCount = AtomicLong()
    private val imageBytes = AtomicLong()
    private val started = AtomicBoolean()
    private var previousTicks: Double? = null
    private var previousAt = 0L

    /** Every published image, whatever encoder produced it: the WebSocket sender, RTC, or both. */
    fun image(bytes: Int) {
        imageCount.incrementAndGet()
        imageBytes.addAndGet(bytes.toLong())
    }

    @Synchronized
    fun reset() {
        imageCount.set(0)
        imageBytes.set(0)
        previousTicks = null
        previousAt = 0L
        TeslaDnsStats.forget()
    }

    /** Idempotent: the log always resolves the session that is active when the tick fires. */
    fun start(intervalMillis: Long = INTERVAL_MILLIS) {
        if (!started.compareAndSet(false, true)) return
        Thread(
                {
                    while (true) {
                        try {
                            Thread.sleep(intervalMillis)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        // A throwing sample must not silence the ticker for the rest of the drive.
                        val line = runCatching { sample() }.getOrNull() ?: continue
                        runCatching { SessionLogFile.report(line) }
                    }
                },
                "session-perf",
            )
            .apply {
                isDaemon = true
                start()
            }
    }

    /** @return one log line per window, describing load and the images published during it. */
    @Synchronized
    fun sample(): String {
        val at = System.nanoTime()
        val ticks = processTicks()
        val beforeTicks = previousTicks
        val beforeAt = previousAt
        previousTicks = ticks
        previousAt = at
        val images = imageCount.getAndSet(0)
        val bytes = imageBytes.getAndSet(0)
        val elapsed = if (beforeAt != 0L) (at - beforeAt) / 1_000_000_000.0 else 0.0
        // Percent of one core: above 100 means more than one thread was busy.
        val cpu =
            if (ticks != null && beforeTicks != null && elapsed > 0.0)
                (ticks - beforeTicks) / ticksPerSecond / elapsed * 100.0
            else null
        return buildString {
            append("perf cpu=")
            append(cpu?.toInt()?.let { "$it%" } ?: "?")
            append(" threads=").append(threads())
            append(" heap=").append(heapMb()).append("MB")
            append(" image=").append(if (elapsed > 0.0) (images / elapsed).toInt() else 0).append("fps ")
            append(if (elapsed > 0.0) (bytes * 8.0 / 1000.0 / elapsed).toInt() else 0).append("kbps ")
            append(bytes / 1024).append("KB")
            append(' ').append(TeslaDnsStats.drain())
        }
    }

    private fun processTicks(): Double? = runCatching {
        val stat = File("/proc/self/stat").readText()
        // The command name is parenthesised and may contain spaces; fields resume after the last ).
        val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        fields[11].toDouble() + fields[12].toDouble()
    }.getOrNull()

    private fun threads(): Int =
        runCatching { File("/proc/self/task").list()?.size ?: 0 }.getOrDefault(0)

    private fun heapMb(): Int {
        val runtime = Runtime.getRuntime()
        return ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).toInt()
    }

    private const val INTERVAL_MILLIS = 5_000L
}
