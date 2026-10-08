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
 * DNS relay for GuardVpnService's monitor tunnel. That tunnel routes only [VIRTUAL_DNS]; each valid
 * IPv4 UDP/53 query arriving on it is reported through [onQuery] and forwarded to the device's real
 * resolver over a protected socket, and the answer is written back into the tunnel.
 *
 * Correctness rules (each one fixes a past bug):
 *  - Every upstream query gets its own 16-bit relay ID. The client's original ID, address and port are
 *    stored under that relay ID and restored on the answer, so two clients that pick the same DNS ID
 *    can no longer overwrite each other.
 *  - IPv4 headers are validated (IHL >= 5, header and total length inside the packet, no fragments)
 *    before any offset is trusted.
 *  - Answers larger than one tunnel packet are sent as IPv4 fragments instead of being dropped, so
 *    clients never need a TC-bit retry.
 *  - TCP to the virtual resolver (DNS-over-TCP or a Private DNS probe on 853) is answered with an RST
 *    so the client falls back immediately instead of hanging on a black hole.
 *  - A query that carries a planted canary is NOT forwarded: it is reported as a blocked send attempt.
 */
class DnsForwarder(
    private val vpn: VpnService,
    private val inspector: PacketInspector,
    private val upstream: InetAddress,
    private val onQuery: (uid: Int, domain: String, ts: Long) -> Unit,
    private val onCanaryAttempt: (uid: Int, token: String) -> Unit,
) {
    private val lock = Any()

    // Pending queries indexed by relay ID. A slot is free when pendingTime == 0.
    private val pendingClientAddress = IntArray(ID_SPACE)
    private val pendingClientPort = IntArray(ID_SPACE)
    private val pendingOriginalId = IntArray(ID_SPACE)
    private val pendingTime = LongArray(ID_SPACE)
    private var nextRelayId = 1
    private var ipIdentification = 1

    /** Starts the query reader and the response relay on [scope]; cancel the job or close [tunnel] to stop. */
    fun start(scope: CoroutineScope, tunnel: ParcelFileDescriptor): Job = scope.launch {
        val socket = DatagramSocket()
        try {
            check(vpn.protect(socket)) { "Could not protect the upstream DNS socket" }
            socket.connect(upstream, DNS_PORT)
            val output = FileOutputStream(tunnel.fileDescriptor)
            val relay = launch { relayResponses(socket, output) }
            FileInputStream(tunnel.fileDescriptor).use { input -> forwardQueries(input, output, socket) }
            relay.cancel()
        } catch (error: Exception) {
            Log.w(TAG, "DNS monitor stopped", error)
        } finally {
            socket.close()
        }
    }

    private fun CoroutineScope.forwardQueries(input: FileInputStream, output: FileOutputStream, socket: DatagramSocket) {
        val buffer = ByteArray(MTU)
        val datagram = DatagramPacket(buffer, 0)
        val reset = ByteArray(IP_HEADER + TCP_HEADER)
        while (isActive) {
            val count = try {
                input.read(buffer)
            } catch (_: IOException) {
                break
            }
            val header = ipv4HeaderLength(buffer, count)
            if (header < 0 || readU32(buffer, 16) != VIRTUAL_DNS_BITS) continue
            when (buffer[9].toInt() and 0xFF) {
                TCP -> {
                    if (buildTcpReset(buffer, header, count, reset)) writeQuietly(output, reset, reset.size)
                    continue
                }
                UDP -> Unit
                else -> continue
            }
            val payload = header + UDP_HEADER
            if (count < payload + DNS_HEADER || readU16(buffer, header + 2) != DNS_PORT) continue
            val udpLength = readU16(buffer, header + 4)
            if (udpLength < UDP_HEADER + DNS_HEADER || header + udpLength > count) continue
            val end = header + udpLength
            val uid = inspector.getUid(buffer, count)
            val canary = CanaryMatcher.scan(buffer, end)
            if (canary != null) {
                // Do not let the decoy leave the device. The app sees a timeout for this lookup.
                onCanaryAttempt(uid, canary)
                continue
            }
            val ts = System.currentTimeMillis()
            val relayId = synchronized(lock) {
                allocateRelayId(ts).also { id ->
                    if (id >= 0) {
                        pendingClientAddress[id] = readU32(buffer, 12).toInt()
                        pendingClientPort[id] = readU16(buffer, header)
                        pendingOriginalId[id] = readU16(buffer, payload)
                        pendingTime[id] = ts
                    }
                }
            }
            if (relayId < 0) continue // Every ID is in flight; let the client retry.
            writeU16(buffer, payload, relayId)
            datagram.setData(buffer, payload, end - payload)
            try {
                socket.send(datagram)
            } catch (_: IOException) {
                synchronized(lock) { pendingTime[relayId] = 0L }
                continue
            }
            val domain = questionName(buffer, payload + DNS_HEADER, end) ?: continue
            onQuery(uid, domain, ts)
        }
    }

    /** Free relay ID, reusing slots whose query is older than [PENDING_TIMEOUT_MILLIS]; -1 if none. */
    private fun allocateRelayId(now: Long): Int {
        repeat(ID_SPACE - 1) {
            val id = nextRelayId
            nextRelayId = if (nextRelayId >= ID_SPACE - 1) 1 else nextRelayId + 1
            val started = pendingTime[id]
            if (started == 0L || now - started > PENDING_TIMEOUT_MILLIS) return id
        }
        return -1
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
            if (length < DNS_HEADER) continue
            val relayId = readU16(answer, 0)
            var clientAddress = 0
            var clientPort = 0
            var originalId = 0
            val found = synchronized(lock) {
                if (pendingTime[relayId] == 0L) {
                    false
                } else {
                    clientAddress = pendingClientAddress[relayId]
                    clientPort = pendingClientPort[relayId]
                    originalId = pendingOriginalId[relayId]
                    pendingTime[relayId] = 0L
                    true
                }
            }
            if (!found) continue
            writeU16(answer, 0, originalId)
            if (!writeUdpAnswer(output, packet, answer, length, clientAddress, clientPort)) break
        }
    }

    /**
     * Writes one UDP datagram from [VIRTUAL_DNS]:53 to the client, splitting it into IPv4 fragments when
     * it does not fit [MTU]. Returns false only if the tunnel is gone.
     */
    private fun writeUdpAnswer(
        output: FileOutputStream,
        packet: ByteArray,
        answer: ByteArray,
        length: Int,
        clientAddress: Int,
        clientPort: Int,
    ): Boolean {
        val udpTotal = UDP_HEADER + length
        val identification = synchronized(lock) { ipIdentification.also { ipIdentification = (it + 1) and 0xFFFF } }
        // The UDP datagram (header + DNS message) is the IP payload we may have to split.
        val datagram = ByteArray(udpTotal)
        writeU16(datagram, 0, DNS_PORT)
        writeU16(datagram, 2, clientPort)
        writeU16(datagram, 4, udpTotal)
        // UDP checksum 0 = "not computed", valid for IPv4.
        System.arraycopy(answer, 0, datagram, UDP_HEADER, length)

        val maxChunk = ((MTU - IP_HEADER) / 8) * 8
        var offset = 0
        while (offset < udpTotal) {
            val chunk = minOf(maxChunk, udpTotal - offset)
            val more = offset + chunk < udpTotal
            writeIpv4Header(packet, clientAddress, IP_HEADER + chunk, identification, more, offset / 8)
            System.arraycopy(datagram, offset, packet, IP_HEADER, chunk)
            if (!writeQuietly(output, packet, IP_HEADER + chunk)) return false
            offset += chunk
        }
        return true
    }

    private fun writeIpv4Header(packet: ByteArray, destination: Int, total: Int, id: Int, moreFragments: Boolean, offset8: Int) {
        packet.fill(0, 0, IP_HEADER)
        packet[0] = 0x45
        writeU16(packet, 2, total)
        writeU16(packet, 4, id)
        writeU16(packet, 6, (if (moreFragments) 0x2000 else 0) or (offset8 and 0x1FFF))
        packet[8] = 64 // TTL
        packet[9] = UDP.toByte()
        writeU32(packet, 12, VIRTUAL_DNS_BITS.toInt())
        writeU32(packet, 16, destination)
        writeU16(packet, 10, checksum(packet, 0, IP_HEADER, 0))
    }

    /**
     * Builds an RST(+ACK) answering the TCP segment in [buffer], from the virtual resolver back to the
     * client, into [out] (IP + TCP, 40 bytes). Returns false for segments that must not be answered.
     */
    private fun buildTcpReset(buffer: ByteArray, header: Int, count: Int, out: ByteArray): Boolean {
        if (count < header + TCP_HEADER) return false
        val flags = buffer[header + 13].toInt() and 0xFF
        if (flags and TCP_RST != 0) return false
        val clientAddress = readU32(buffer, 12).toInt()
        val clientPort = readU16(buffer, header)
        val serverPort = readU16(buffer, header + 2)
        val seq = readU32(buffer, header + 4)
        val ack = readU32(buffer, header + 8)
        val dataOffset = ((buffer[header + 12].toInt() ushr 4) and 0x0F) * 4
        val total = readU16(buffer, 2)
        var segmentLength = (total - header - dataOffset).coerceAtLeast(0).toLong()
        if (flags and TCP_SYN != 0) segmentLength++
        if (flags and TCP_FIN != 0) segmentLength++

        out.fill(0)
        out[0] = 0x45
        writeU16(out, 2, out.size)
        out[8] = 64
        out[9] = TCP.toByte()
        writeU32(out, 12, VIRTUAL_DNS_BITS.toInt())
        writeU32(out, 16, clientAddress)
        writeU16(out, 10, checksum(out, 0, IP_HEADER, 0))

        val t = IP_HEADER
        writeU16(out, t, serverPort)
        writeU16(out, t + 2, clientPort)
        val ackFlag = flags and TCP_ACK != 0
        // RFC 793: if the segment had ACK, RST uses seq = its ack; otherwise seq 0 with ACK = seq + len.
        writeU32(out, t + 4, if (ackFlag) ack.toInt() else 0)
        writeU32(out, t + 8, ((seq + segmentLength) and 0xFFFFFFFFL).toInt())
        out[t + 12] = (5 shl 4).toByte()
        out[t + 13] = (TCP_RST or TCP_ACK).toByte()
        // Pseudo-header: src, dst, zero, protocol, TCP length.
        var sum = 0L
        sum += readU16(out, 12) + readU16(out, 14) + readU16(out, 16) + readU16(out, 18)
        sum += TCP + TCP_HEADER
        writeU16(out, t + 16, checksum(out, t, TCP_HEADER, sum))
        return true
    }

    /** IPv4 header length if [buffer] holds a well-formed, unfragmented IPv4 packet, else -1. */
    private fun ipv4HeaderLength(buffer: ByteArray, count: Int): Int {
        if (count < IP_HEADER || (buffer[0].toInt() ushr 4) and 0x0F != 4) return -1
        val header = (buffer[0].toInt() and 0x0F) * 4
        if (header < IP_HEADER || header > count) return -1
        val total = readU16(buffer, 2)
        if (total < header || total > count) return -1
        val fragment = readU16(buffer, 6)
        if (fragment and 0x3FFF != 0) return -1 // MF set or non-zero offset: not a whole datagram.
        return header
    }

    /** Lower-case QNAME of the first question starting at [offset], or null if malformed. */
    private fun questionName(buffer: ByteArray, offset: Int, end: Int): String? {
        val name = StringBuilder(64)
        var i = offset
        while (i < end) {
            val labelLength = buffer[i].toInt() and 0xFF
            if (labelLength == 0) return name.takeIf { it.isNotEmpty() }?.toString()
            if (labelLength > 63 || i + 1 + labelLength > end || name.length + labelLength > 253) return null
            if (name.isNotEmpty()) name.append('.')
            for (j in i + 1..i + labelLength) name.append((buffer[j].toInt() and 0xFF).toChar().lowercaseChar())
            i += 1 + labelLength
        }
        return null
    }

    private fun writeQuietly(output: FileOutputStream, data: ByteArray, length: Int): Boolean = try {
        output.write(data, 0, length)
        true
    } catch (_: IOException) {
        false
    }

    companion object {
        /** Tunnel-only resolver address handed to apps; never leaves the device. */
        const val VIRTUAL_DNS = "10.0.0.53"
        const val MTU = 1500
        private const val VIRTUAL_DNS_BITS = (10L shl 24) or 53L
        private const val TAG = "DhrashtaDns"
        private const val TCP = 6
        private const val UDP = 17
        private const val TCP_FIN = 0x01
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_ACK = 0x10
        private const val DNS_PORT = 53
        private const val DNS_HEADER = 12
        private const val IP_HEADER = 20
        private const val UDP_HEADER = 8
        private const val TCP_HEADER = 20
        private const val ID_SPACE = 65_536
        private const val PENDING_TIMEOUT_MILLIS = 10_000L
        private const val MAX_UPSTREAM = 65_535

        internal fun readU16(buffer: ByteArray, offset: Int): Int =
            ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

        internal fun readU32(buffer: ByteArray, offset: Int): Long =
            (readU16(buffer, offset).toLong() shl 16) or readU16(buffer, offset + 2).toLong()

        internal fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
            buffer[offset] = (value ushr 8).toByte()
            buffer[offset + 1] = value.toByte()
        }

        internal fun writeU32(buffer: ByteArray, offset: Int, value: Int) {
            writeU16(buffer, offset, value ushr 16)
            writeU16(buffer, offset + 2, value)
        }

        /** Internet checksum of [length] bytes at [offset], starting from [initial] (pseudo-header sum). */
        internal fun checksum(buffer: ByteArray, offset: Int, length: Int, initial: Long): Int {
            var sum = initial
            var i = 0
            while (i + 1 < length) {
                sum += readU16(buffer, offset + i)
                i += 2
            }
            if (i < length) sum += (buffer[offset + i].toInt() and 0xFF) shl 8
            while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
            return sum.inv().toInt() and 0xFFFF
        }
    }
}
