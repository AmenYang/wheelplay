package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(val file: File) : Closeable {
    private val lock = Any()
    private var closed = false
    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            append(header)
        }
    }
    fun append(line: String) = synchronized(lock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        runCatching {
            if (file.length() > MAX_BYTES) {
                rotate()
                file.writeText("")
            }
            file.appendText(safe + "\n")
        }
        Unit
    }
    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }
    override fun close() = synchronized(lock) { closed = true }
    companion object {
        const val MAX_BYTES = 512 * 1024L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"

        // The head unit has no adb while it is in the car, so anything worth diagnosing has to
        // reach this file. Web-service code appends through [report] to the session the host owns.
        @Volatile private var active: SessionLogFile? = null

        fun publish(log: SessionLogFile?) {
            active = log
        }

        fun report(message: String) {
            // Web-service lines arrive from many threads and carry no clock of their own. Without a
            // stamp they cannot be read against the host's own lines, which matters when the only
            // question is "what happened in the ten seconds after the page loaded".
            val log = active ?: return
            val stamp =
                SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(System.currentTimeMillis()))
            log.append("$stamp  $message")
        }
    }
}
