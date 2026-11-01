package com.shilapi.xcertplay.web

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Answers the car's own connectivity check so a hotspot without an upstream still counts as online.
 *
 * The head unit uses ConnMan-style checks: resolve one of [names], then fetch it over HTTP and look
 * for `X-ConnMan-Status: online` (the body and the `X-Cache` header are copied from the real
 * servers). DashPlay answers the same seven names for Teslas; reproduced here because a car that
 * refuses a hotspot with no internet never reaches the browser.
 */
internal object TeslaChecks {
    val names =
        setOf(
            "connman.vn.cloud.tesla.cn",
            "connman.vn.tesla.services",
            "www.tesla.cn",
            "www.baidu.cn",
            "www.baidu.com",
            "www.qq.com",
            "connectivitycheck.gstatic.com",
        )

    const val BODY = "<html>\n<head>\n</head>\n<body>\n</body>\n</html>\n\n"

    /** Strips the port, a trailing root dot and case, so `Host:` and DNS names compare equal. */
    fun hostName(host: String?): String? =
        host?.substringBefore(':')?.trim()?.removeSuffix(".")?.lowercase()?.takeIf { it.isNotEmpty() }

    fun matches(host: String?): Boolean = hostName(host)?.let { it in names } == true
}

/**
 * Minimal authoritative DNS for [TeslaChecks.names]. Anything else is answered NXDOMAIN: there is no
 * upstream to forward to, and a wrong answer would only make the car retry.
 */
internal class TeslaDnsResponder(
    private val answer: String,
    val port: Int = PORT,
) {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var failure: String? = null

    val running: Boolean
        get() = thread?.isAlive == true && socket != null

    val error: String?
        get() = failure

    @Synchronized
    fun start() {
        if (running) return
        stop()
        failure = null
        val address =
            runCatching { InetAddress.getByName(answer) }.getOrElse {
                failure = "bad address"
                return
            }
        val bytes = address.address
        if (bytes.size != 4) {
            failure = "not IPv4"
            return
        }
        val bound =
            runCatching {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(this@TeslaDnsResponder.port))
                    }
                }
                .getOrElse {
                    failure = it.toString()
                    return
                }
        socket = bound
        thread =
            Thread({ loop(bound, bytes) }, "tesla-dns").apply {
                isDaemon = true
                start()
            }
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    private fun loop(bound: DatagramSocket, address: ByteArray) {
        val buffer = ByteArray(1500)
        while (!bound.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { bound.receive(packet) }
            if (received.isFailure) break
            val reply = runCatching { reply(packet.data, packet.length, address) }.getOrNull() ?: continue
            runCatching { bound.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
        }
    }

    private fun reply(query: ByteArray, length: Int, address: ByteArray): ByteArray? {
        if (length < 17) return null
        fun u8(at: Int) = query[at].toInt() and 0xFF
        fun u16(at: Int) = (u8(at) shl 8) or u8(at + 1)

        if (u16(2) and 0x8000 != 0) return null // a response, never a query
        if (u16(4) < 1) return null

        val name = StringBuilder()
        var offset = 12
        while (true) {
            if (offset >= length) return null
            val size = u8(offset)
            if (size == 0) {
                offset++
                break
            }
            if (size and 0xC0 != 0) return null // compression pointers do not belong in a question
            if (offset + 1 + size > length) return null
            if (name.isNotEmpty()) name.append('.')
            name.append(String(query, offset + 1, size, Charsets.US_ASCII))
            if (name.length > 255) return null
            offset += 1 + size
        }
        val questionEnd = offset + 4
        if (questionEnd > length) return null
        if (u16(offset) != 1) return null // only A records are worth answering here

        val matched = name.toString().lowercase() in TeslaChecks.names
        val out = ByteArrayOutputStream(64 + questionEnd)
        out.write(u8(0))
        out.write(u8(1))
        // QR, the caller's opcode and RD bit, recursion available, and NOERROR or NXDOMAIN.
        val flags = 0x8000 or (u16(2) and 0x7900) or 0x0080 or (if (matched) 0 else 0x0003)
        out.write(flags shr 8)
        out.write(flags and 0xFF)
        out.write(0)
        out.write(1) // one question echoed back
        out.write(0)
        out.write(if (matched) 1 else 0)
        repeat(4) { out.write(0) } // no authority, no additional records
        out.write(query, 12, questionEnd - 12)
        if (matched) {
            out.write(0xC0)
            out.write(0x0C) // the name above
            out.write(0)
            out.write(1) // A
            out.write(0)
            out.write(1) // IN
            repeat(3) { out.write(0) }
            out.write(60) // short TTL: the answer changes with the shared address
            out.write(0)
            out.write(4)
            out.write(address)
        }
        return out.toByteArray()
    }

    companion object {
        /**
         * Avoids 53 (the tethering server) and 5353 (mDNS); reached through an iptables REDIRECT so
         * the socket can stay on the wildcard address.
         */
        const val PORT = 15353
    }
}

/** Owns the responder alongside the root routing session. Every call is idempotent. */
internal object TeslaConnectivity {
    private var responder: TeslaDnsResponder? = null

    @Synchronized
    fun reconcile(active: Boolean, address: String): Boolean {
        val current = responder
        if (!active) {
            current?.stop()
            responder = null
            return false
        }
        if (current != null && current.running) return true
        current?.stop()
        responder = TeslaDnsResponder(address).also { it.start() }
        return responder?.running == true
    }

    @Synchronized
    fun stop() {
        responder?.stop()
        responder = null
    }
}
