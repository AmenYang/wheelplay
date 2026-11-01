package com.shilapi.xcertplay.web

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaConnectivityTest {
    @Test
    fun checkNamesMatchIgnoringCasePortAndRootDot() {
        assertTrue(TeslaChecks.matches("connman.vn.cloud.tesla.cn"))
        assertTrue(TeslaChecks.matches("CONNMAN.VN.TESLA.SERVICES:80"))
        assertTrue(TeslaChecks.matches("www.tesla.cn."))
        assertTrue(TeslaChecks.matches("connectivitycheck.gstatic.com"))
        assertFalse(TeslaChecks.matches("www.tesla.com"))
        assertFalse(TeslaChecks.matches(null))
        assertFalse(TeslaChecks.matches(""))
    }

    @Test
    fun answersACheckNameWithTheSharedAddress() {
        val reply = query("connman.vn.cloud.tesla.cn")
        assertEquals(0x8180, reply.u16(2)) // QR, RD, RA, NOERROR
        assertEquals(1, reply.u16(6)) // one answer
        val address = reply.copyOfRange(reply.size - 4, reply.size).joinToString(".") {
            (it.toInt() and 0xFF).toString()
        }
        assertEquals("100.96.0.1", address)
    }

    @Test
    fun refusesNamesItDoesNotOwn() {
        val reply = query("example.com")
        assertEquals(0x8183, reply.u16(2)) // QR, RD, RA, NXDOMAIN
        assertEquals(0, reply.u16(6))
        // header + the echoed question only: example.com is 13 bytes of labels and terminator
        assertEquals(12 + 13 + 4, reply.size)
    }

    /** Talks to a real socket so binding, framing and the wildcard address are covered too. */
    private fun query(name: String): ByteArray {
        val responder = TeslaDnsResponder("100.96.0.1", TeslaDnsResponder.PORT)
        responder.start()
        try {
            assertTrue("responder failed to bind: ${responder.error}", responder.running)
            val socket = DatagramSocket()
            socket.soTimeout = 3000
            val request = question(name)
            socket.send(
                DatagramPacket(
                    request,
                    request.size,
                    InetAddress.getByName("127.0.0.1"),
                    TeslaDnsResponder.PORT,
                )
            )
            val buffer = ByteArray(1500)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            socket.close()
            return packet.data.copyOf(packet.length)
        } finally {
            responder.stop()
        }
    }

    private fun question(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01)) // A, IN
        return out.toByteArray()
    }

    private fun ByteArray.u16(at: Int): Int =
        ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)
}
