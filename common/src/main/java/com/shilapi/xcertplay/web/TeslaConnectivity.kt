package com.shilapi.xcertplay.web

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 * What the responder has been asked, in numbers the log can hold. Every client on the hotspot shares
 * this responder, so the count of distinct callers is the only way to tell whether the car alone is
 * asking, or whether a phone joined the hotspot and is having its DNS answered by a server that
 * cannot resolve anything outside [TeslaChecks.names]. Names and addresses never leave this object.
 */
internal object TeslaDnsStats {
    private val lock = Any()
    private var queries = 0L
    private var answered = 0L
    private var total = 0L
    private val clients = HashSet<String>()

    fun query(client: String?, matched: Boolean) = synchronized(lock) {
        queries++
        total++
        if (matched) answered++
        client?.let { clients.add(it) }
        Unit
    }

    /** @return the window just closed, and starts a new one. */
    fun drain(): String = synchronized(lock) {
        val line =
            "dns queries=$queries answered=$answered refused=${queries - answered} " +
                "clients=${clients.size} total=$total"
        queries = 0
        answered = 0
        clients.clear()
        line
    }

    fun forget() = synchronized(lock) {
        queries = 0
        answered = 0
        total = 0
        clients.clear()
    }
}

/**
 * Minimal DNS for the hotspot. [TeslaChecks.names] are answered authoritatively with the shared
 * address; every other A question is forwarded to the device resolver, and a name that cannot be
 * resolved is refused with SERVFAIL.
 *
 * The forwarding is not an optimisation. The redirect that brings packets here covers the whole
 * interface, so every client on the hotspot is served by this class, not only the car. Answering
 * NXDOMAIN for everything else tells a phone that no domain exists, which it caches and believes:
 * one drive logged 938 refused questions over two minutes, all of them from a phone that had joined
 * the hotspot for CarPlay, which is what "no internet" feels like from the inside. SERVFAIL is the
 * honest answer and lets the client fall back to its own network instead.
 */
internal class TeslaDnsResponder(
    private val answer: String,
    val port: Int = PORT,
    private val resolve: (String) -> ByteArray? = ::resolveIPv4,
) {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var failure: String? = null
    private class Cached(val address: ByteArray?, val expiresAt: Long)
    private val cache = HashMap<String, Cached>()

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
            val client = packet.address?.hostAddress
            val reply =
                runCatching { reply(packet.data, packet.length, address, client) }.getOrNull()
                    ?: continue
            runCatching { bound.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
        }
    }

    internal fun reply(
        query: ByteArray,
        length: Int,
        address: ByteArray,
        client: String? = null,
    ): ByteArray? {
        val reply = build(query, length, address) ?: return null
        TeslaDnsStats.query(client, reply.answered)
        return reply.bytes
    }

    private class Answer(val bytes: ByteArray, val answered: Boolean)

    private fun build(query: ByteArray, length: Int, address: ByteArray): Answer? {
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

        val type = u16(offset)
        val asked = name.toString().lowercase()
        val known = asked in TeslaChecks.names
        // A known name answers A with the shared address. Every other type is answered with NOERROR
        // and no data rather than dropped, because iOS and Chromium ask AAAA first and a dropped
        // question costs a retransmit before anything else can proceed.
        val target =
            when {
                type != 1 -> null
                known -> address
                else -> forward(asked)
            }
        // SERVFAIL only when an A question could not be resolved: the name may well exist, so
        // claiming NXDOMAIN would be a lie the client remembers.
        val rcode = if (type == 1 && target == null) 2 else 0
        val answered = target != null
        val out = ByteArrayOutputStream(64 + questionEnd)
        out.write(u8(0))
        out.write(u8(1))
        // QR, the caller's opcode and RD bit, recursion available, and the response code.
        val flags = 0x8000 or (u16(2) and 0x7900) or 0x0080 or rcode
        out.write(flags shr 8)
        out.write(flags and 0xFF)
        out.write(0)
        out.write(1) // one question echoed back
        out.write(0)
        out.write(if (answered) 1 else 0)
        repeat(4) { out.write(0) } // no authority, no additional records
        out.write(query, 12, questionEnd - 12)
        if (target != null) {
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
            out.write(target)
        }
        return Answer(out.toByteArray(), answered)
    }

    /**
     * A phone on this hotspot asks tens of names a second, and the same few names over and over, so
     * one blocked lookup per question would both stall this thread and hammer the resolver.
     */
    private fun forward(name: String): ByteArray? {
        val now = System.nanoTime()
        synchronized(cache) {
            cache[name]?.let { if (it.expiresAt > now) return it.address }
        }
        val future = lookups.submit<ByteArray?> { resolve(name) }
        val resolved =
            runCatching { future.get(LOOKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
                .getOrElse {
                    future.cancel(true)
                    null
                }
        val ttl = if (resolved != null) POSITIVE_TTL_NANOS else NEGATIVE_TTL_NANOS
        synchronized(cache) {
            // A phone can ask for hundreds of distinct names; the cache is a short-lived hint, not a
            // record of where anyone browses.
            if (cache.size > MAX_CACHE_ENTRIES) cache.clear()
            cache[name] = Cached(resolved, now + ttl)
        }
        return resolved
    }

    companion object {
        /**
         * Avoids 53 (the tethering server) and 5353 (mDNS); reached through an iptables REDIRECT so
         * the socket can stay on the wildcard address.
         */
        const val PORT = 15353
        private const val LOOKUP_TIMEOUT_MILLIS = 1_200L
        private const val POSITIVE_TTL_NANOS = 60_000_000_000L
        private const val NEGATIVE_TTL_NANOS = 5_000_000_000L
        private const val MAX_CACHE_ENTRIES = 256

        // Two threads, process lifetime: a lookup that hangs must not block the DNS loop, and the
        // pool outlives any single responder because reconcile replaces the instance.
        private val lookups =
            Executors.newFixedThreadPool(2) { task ->
                Thread(task, "tesla-dns-upstream").apply { isDaemon = true }
            }

        private fun resolveIPv4(name: String): ByteArray? =
            runCatching { InetAddress.getAllByName(name) }
                .getOrNull()
                ?.firstOrNull { it is Inet4Address }
                ?.address
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
