// SPDX-License-Identifier: Apache-2.0
package com.shilapi.xcertplay.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MDNS_NAME = "60cc53dd-6c1e-4c3e-9f6f-1c0f2a9d7b55.local"

/**
 * Chromium hides host candidates behind `<uuid>.local`, which the native ICE agent cannot resolve.
 * The answer is only usable if that name is replaced by the address the page actually reached us
 * from, and only that field may change.
 */
class RtcAnswerCandidatesTest {
    private val candidate =
        "a=candidate:2039131470 1 udp 2113937151 $MDNS_NAME " +
            "62297 typ host generation 0 ufrag K3nQ network-id 1 network-cost 10"

    private val answer =
        "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n" +
            "a=ice-ufrag:K3nQ\r\na=ice-pwd:abcdefghijklmnopqrstuvwx\r\n" +
            "$candidate\r\na=setup:passive\r\n"

    @Test
    fun `mDNS candidate keeps its port and gains the head unit address`() {
        val rewritten = advertisePeerAddress(answer, "192.168.42.7")

        assertFalse(rewritten.contains(".local"))
        assertTrue(rewritten.contains("a=candidate:2039131470 1 udp 2113937151 192.168.42.7 62297 typ host"))
        // Everything else survives untouched: the ICE credentials and the DTLS role decide the
        // handshake, and a dropped line here fails the whole negotiation.
        assertTrue(rewritten.contains("a=ice-ufrag:K3nQ"))
        assertTrue(rewritten.contains("a=setup:passive"))
        assertEquals(answer.lines().size, rewritten.lines().size)
    }

    @Test
    fun `an answer without candidates is returned unchanged`() {
        val bare = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=setup:passive\r\n"
        assertEquals(bare, advertisePeerAddress(bare, "192.168.42.7"))
    }

    @Test
    fun `an address on another link is replaced too`() {
        // The automotive browser advertises a real address that is not the one it answers from,
        // and ICE never leaves checking. Only the socket's address is known to be reachable.
        val real = answer.replace(MDNS_NAME, "10.0.0.9")
        val rewritten = advertisePeerAddress(real, "192.168.42.7")
        assertFalse(rewritten.contains("10.0.0.9"))
        assertTrue(rewritten.contains("2113937151 192.168.42.7 62297 typ host"))
    }

    @Test
    fun `the address the socket came from is left alone`() {
        val real = answer.replace(MDNS_NAME, "192.168.42.7")
        assertEquals(real, advertisePeerAddress(real, "192.168.42.7"))
    }

    @Test
    fun `an ipv6 candidate cannot carry the replacement`() {
        val v6 = answer.replace(MDNS_NAME, "fe80::1c2b:3a4d:5e6f:7081")
        assertEquals(v6, advertisePeerAddress(v6, "192.168.42.7"))
    }

    @Test
    fun `a loopback peer cannot be substituted`() {
        // adb forward and local previews arrive from 127.0.0.1, which is not the page's address.
        assertEquals(answer, advertisePeerAddress(answer, "127.0.0.1"))
        assertEquals(answer, advertisePeerAddress(answer, "::1"))
        assertEquals(answer, advertisePeerAddress(answer, null))
    }
}
