package com.dhrashta.x.enforcement

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DNS relay for GuardVpnService's monitor tunnel. That tunnel routes only [VIRTUAL_DNS]; each IPv4
 * UDP/53 query arriving on it is reported through [onQuery] and forwarded unchanged to the device's
 * real resolver over a protected socket, and the answer is written back into the tunnel.
 * Buffers are allocated once; only the parsed domain string is allocated per query.
 */
class DnsForwarder(
    private val vpn: VpnService,
    private val inspector: PacketInspector,
    private val upstream: InetAddress,
    private val onQuery: (uid: Int, domain: String, ts: Long) -> Unit,
    private val onCanary: (uid: Int, token: String) -> Unit,
) {
    private val lock = Any()

    /** Pending queries by DNS transaction ID: client IPv4 address << 16 | client port, 0 when empty. */
    private val pending = LongArray(65_536)

    /** Starts the query reader and the response relay on [scope]; cancel the job or close [tunnel] to stop. */
    fun start(scope: CoroutineScope, tunnel: ParcelFileDescriptor): Job = scope.launch {
        val socket = DatagramSocket()
        try {
            check(vpn.protect(socket)) { "Could not protect the upstream DNS socket" }
            socket.connect(upstream, DNS_PORT)
            val output = FileOutputStream(tunnel.fileDescriptor)
            val relay = launch { relayResponses(socket, output) }
            FileInputStream(tunnel.fileDescriptor).use { input -> forwardQueries(input, socket) }
            relay.cancel()
        } catch (error: Exception) {
            Log.w(TAG, "DNS monitor stopped", error)
        } finally {
            socket.close()
        }
    }

    private fun CoroutineScope.forwardQueries(input: FileInputStream, socket: DatagramSocket) {
        val buffer = ByteArray(MTU)
        val datagram = DatagramPacket(buffer, 0)
        while (isActive) {
            val count = try {
                input.read(buffer)
            } catch (_: IOException) {
                break
            }
            val payload = dnsPayloadOffset(buffer, count)
            if (payload < 0) continue
            CanaryMatcher.scan(buffer, count)?.let { token -> onCanary(inspector.getUid(buffer, count), token) }
            val ts = System.currentTimeMillis()
            val headerLength = (buffer[0].toInt() and 0x0F) * 4
            val client = (u32(buffer, 12) shl 16) or u16(buffer, headerLength).toLong()
            synchronized(lock) { pending[u16(buffer, payload)] = client }
            datagram.setData(buffer, payload, count - payload)
            try {
                socket.send(datagram)
            } catch (_: IOException) {
                continue
            }
            val domain = questionName(buffer, payload + DNS_HEADER, count) ?: continue
            onQuery(inspector.getUid(buffer, count), domain, ts)
        }
    }

    private fun CoroutineScope.relayResponses(socket: DatagramSocket, output: FileOutputStream) {
        val answer = ByteArray(MAX_UPSTREAM)
        val datagram = DatagramPacket(answer, answer.size)
        val packet = ByteArray(MTU)
        while (isActive) {
            datagram.setData(answer, 0, answer.size)
            try {
                socket.receive(datagram)
            } catch (_: IOException) {
                break
            }
            val length = datagram.length
            // Answers that do not fit one tunnel packet are dropped; the client times out and retries.
            if (length < DNS_HEADER || length > MTU - IP_UDP_HEADER) continue
            val txId = u16(answer, 0)
            val client = synchronized(lock) { pending[txId].also { pending[txId] = 0L } }
            if (client == 0L) continue
            val total = IP_UDP_HEADER + length
            writeIpv4UdpHeader(packet, (client ushr 16).toInt(), (client and 0xFFFF).toInt(), total)
            System.arraycopy(answer, 0, packet, IP_UDP_HEADER, length)
            try {
                output.write(packet, 0, total)
            } catch (_: IOException) {
                break
            }
        }
    }

    /** Offset of the DNS message if [buffer] holds an IPv4 UDP query to [VIRTUAL_DNS]:53, else -1. */
    private fun dnsPayloadOffset(buffer: ByteArray, count: Int): Int {
        if (count < 20 || (buffer[0].toInt() ushr 4) != 4 || (buffer[9].toInt() and 0xFF) != UDP) return -1
        val headerLength = (buffer[0].toInt() and 0x0F) * 4
        val payload = headerLength + 8
        if (count < payload + DNS_HEADER || u32(buffer, 16) != VIRTUAL_DNS_BITS) return -1
        return if (u16(buffer, headerLength + 2) == DNS_PORT) payload else -1
    }

    /** Lower-case QNAME of the first question starting at [offset], or null if malformed. */
    private fun questionName(buffer: ByteArray, offset: Int, end: Int): String? {
        val name = StringBuilder(64)
        var i = offset
        while (i < end) {
            val labelLength = buffer[i].toInt() and 0xFF
            if (labelLength == 0) return name.takeIf { it.isNotEmpty() }?.toString()
            if (labelLength > 63 || i + 1 + labelLength > end) return null
            if (name.isNotEmpty()) name.append('.')
            for (j in i + 1..i + labelLength) name.append((buffer[j].toInt() and 0xFF).toChar().lowercaseChar())
            i += 1 + labelLength
        }
        return null
    }

    private fun writeIpv4UdpHeader(packet: ByteArray, clientAddress: Int, clientPort: Int, total: Int) {
        packet.fill(0, 0, IP_UDP_HEADER)
        packet[0] = 0x45
        putU16(packet, 2, total)
        packet[8] = 64 // TTL
        packet[9] = UDP.toByte()
        putU32(packet, 12, VIRTUAL_DNS_BITS.toInt())
        putU32(packet, 16, clientAddress)
        var sum = 0
        for (i in 0 until 20 step 2) sum += u16(packet, i)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        putU16(packet, 10, sum.inv() and 0xFFFF)
        putU16(packet, 20, DNS_PORT)
        putU16(packet, 22, clientPort)
        putU16(packet, 24, total - 20) // UDP checksum left 0: optional for IPv4.
    }

    private fun u16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun u32(buffer: ByteArray, offset: Int): Long =
        (u16(buffer, offset).toLong() shl 16) or u16(buffer, offset + 2).toLong()

    private fun putU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 8).toByte()
        buffer[offset + 1] = value.toByte()
    }

    private fun putU32(buffer: ByteArray, offset: Int, value: Int) {
        putU16(buffer, offset, value ushr 16)
        putU16(buffer, offset + 2, value)
    }

    companion object {
        /** Tunnel-only resolver address handed to apps; never leaves the device. */
        const val VIRTUAL_DNS = "10.0.0.53"
        const val MTU = 1500
        private const val VIRTUAL_DNS_BITS = (10L shl 24) or 53L
        private const val TAG = "DhrashtaDns"
        private const val UDP = 17
        private const val DNS_PORT = 53
        private const val DNS_HEADER = 12
        private const val IP_UDP_HEADER = 28
        private const val MAX_UPSTREAM = 4_096
    }
}
