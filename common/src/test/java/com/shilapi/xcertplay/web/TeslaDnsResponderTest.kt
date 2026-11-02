// SPDX-License-Identifier: Apache-2.0
package com.shilapi.xcertplay.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The responder is the only DNS the head unit and the iPhone can reach while the hotspot has no
 * upstream, so a question it never answers costs the client a retransmit before it can fall back.
 */
class TeslaDnsResponderTest {
    private val address = byteArrayOf(100, 96.toByte(), 0, 1)

    private fun query(name: String, type: Int, id: Int = 0x1234, qdCount: Int = 1): ByteArray =
        ByteArrayOutputStream().apply {
            write(id shr 8); write(id and 0xFF)
            write(0x01); write(0x00) // recursion desired
            write(qdCount shr 8); write(qdCount and 0xFF)
            repeat(6) { write(0) } // no answer, authority or additional records
            for (label in name.split('.')) {
                write(label.length)
                write(label.toByteArray(Charsets.US_ASCII))
            }
            write(0)
            write(type shr 8); write(type and 0xFF)
            write(0); write(1) // IN
        }.toByteArray()

    private fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    private fun rcode(bytes: ByteArray) = bytes[3].toInt() and 0x0F
    private fun answers(bytes: ByteArray) = u16(bytes, 6)

    @Test
    fun `a known name answers A with the shared address`() {
        val packet = query("connman.vn.cloud.tesla.cn", 1)
        val reply = TeslaDnsResponder("100.96.0.1").reply(packet, packet.size, address)
        assertNotNull(reply)
        reply!!
        assertEquals(0, rcode(reply))
        assertEquals(1, answers(reply))
        // The transaction id has to come back unchanged or the client drops the answer.
        assertEquals(0x1234L, u16(reply, 0).toLong())
        assertArrayEquals(address, reply.copyOfRange(reply.size - 4, reply.size))
    }

    @Test
    fun `the AAAA question iOS asks first is answered instead of dropped`() {
        // iOS and Chromium ask AAAA before A; silence here is a five second stall per name.
        val packet = query("connectivitycheck.gstatic.com", 28)
        val reply = TeslaDnsResponder("100.96.0.1").reply(packet, packet.size, address)
        assertNotNull(reply)
        assertEquals(0, rcode(reply!!)) // NOERROR, not NXDOMAIN: the name exists, it has no AAAA
        assertEquals(0, answers(reply))
    }

    @Test
    fun `an unknown name is refused so the client stops asking`() {
        val packet = query("captive.apple.com", 1)
        val reply = TeslaDnsResponder("100.96.0.1").reply(packet, packet.size, address)
        assertNotNull(reply)
        assertEquals(3, rcode(reply!!)) // NXDOMAIN
        assertEquals(0, answers(reply))
    }

    @Test
    fun `a response is never answered`() {
        val packet = query("www.baidu.com", 1)
        packet[2] = 0x81.toByte() // QR set
        assertNull(TeslaDnsResponder("100.96.0.1").reply(packet, packet.size, address))
    }

    @Test
    fun `a query with no question is ignored`() {
        val packet = query("www.qq.com", 1, qdCount = 0)
        assertNull(TeslaDnsResponder("100.96.0.1").reply(packet, packet.size, address))
    }
}
