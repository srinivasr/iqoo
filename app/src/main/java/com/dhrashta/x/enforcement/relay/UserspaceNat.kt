package com.dhrashta.x.enforcement.relay

import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Userspace relay for apps routed into the VPN for live inspection (IPv4 TCP + UDP).
 *
 * Packets the app writes into the TUN are terminated here and re-sent on normal (protected) sockets;
 * replies are turned back into IPv4 packets for the app. Every packet in both directions is reported
 * to [observer] with its IP length, so the network model sees the app's real traffic pattern
 * (measured at the TUN, i.e. as the app's TCP/IP stack sees it, not on the radio).
 *
 * Design: one thread owns all state. [handleOutbound] only copies the packet into a queue, so the TUN
 * reader never blocks on the network. TCP is a deliberately small state machine: the TUN is a lossless
 * local link, so there is no retransmission timer; the app's own TCP retransmits anything we drop.
 * Our SYN-ACK carries only an MSS option (no window scaling, no SACK), so both directions use plain
 * 16-bit windows, and the app's advertised window is respected before reading more from the server.
 * Pure JVM (java.nio only): tested on the build machine against real sockets.
 */
class UserspaceNat(
    private val writeToTun: (ByteArray) -> Unit,
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val observer: PacketObserver,
    /** Where to really connect for (ip, port). Production: the same address. Tests redirect it. */
    private val target: (Int, Int) -> InetSocketAddress = { ip, port -> InetSocketAddress(InetAddress.getByAddress(Ipv4.addressBytes(ip)), port) },
) {
    fun interface PacketObserver {
        fun onPacket(uid: Int, tsMillis: Long, ipLength: Int, outgoing: Boolean, remoteIp: Int, remotePort: Int, protocol: Int, syn: Boolean)
    }

    private class Inbound(val packet: ByteArray, val uid: Int, val at: Long)

    private inner class TcpFlow(
        val key: String, val uid: Int,
        val appIp: Int, val appPort: Int, val remoteIp: Int, val remotePort: Int,
        val channel: SocketChannel,
    ) {
        var selectionKey: SelectionKey? = null
        var connected = false
        var mySeq = 0L          // next sequence number we send to the app
        var myAck = 0L          // next sequence number we expect from the app
        var appAcked = 0L       // highest ack received from the app
        var appWindow = 65_535
        var mss = DEFAULT_MSS
        val toServer = ArrayDeque<ByteBuffer>()
        var toServerBytes = 0
        var appFin = false
        var serverEof = false
        var outputShut = false
        var lastActive = 0L
        /** Bytes sent to the app and not yet acknowledged, starting at [appAcked] (go-back-N buffer). */
        var unacked = ByteArray(0)
        var unackedLen = 0
        var finSent = false
        var lastProgress = 0L
        var dupAcks = 0
        var retransmits = 0
        fun inflight(): Long = (mySeq - appAcked) and MASK

        fun remember(data: ByteArray, off: Int, len: Int) {
            if (unackedLen + len > unacked.size) unacked = unacked.copyOf(maxOf(unacked.size * 2, unackedLen + len, 4_096))
            System.arraycopy(data, off, unacked, unackedLen, len)
            unackedLen += len
        }

        /** The app acknowledged [bytes] more bytes: drop them from the retransmission buffer. */
        fun acked(bytes: Int) {
            val drop = minOf(bytes, unackedLen)
            System.arraycopy(unacked, drop, unacked, 0, unackedLen - drop)
            unackedLen -= drop
        }
    }

    private inner class UdpFlow(
        val key: String, val uid: Int,
        val appIp: Int, val appPort: Int, val remoteIp: Int, val remotePort: Int,
        val channel: DatagramChannel,
    ) { var lastActive = 0L }

    private val selector: Selector = Selector.open()
    private val queue = ConcurrentLinkedQueue<Inbound>()
    private val queued = AtomicInteger()
    private val tcp = HashMap<String, TcpFlow>()
    private val udp = HashMap<String, UdpFlow>()
    private val recentlyClosed = HashMap<String, Long>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var lastSweep = 0L
    private var lastRto = 0L
    private val readBuffer = ByteBuffer.allocate(65_535)

    val activeFlows: Int get() = tcp.size + udp.size

    fun start() {
        running = true
        thread = Thread(::loop, "dhrashta-relay").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        selector.wakeup()
        thread?.join(2_000)
        runCatching { (tcp.values.map { it.channel } + udp.values.map { it.channel }).forEach { it.close() } }
        tcp.clear(); udp.clear()
        runCatching { selector.close() }
    }

    /** Called by the TUN reader with one IPv4 packet from an inspected app. Never blocks. */
    fun handleOutbound(buffer: ByteArray, length: Int, uid: Int) {
        if (!running || length < 20 || Ipv4.version(buffer) != 4) return
        if (queued.get() > MAX_QUEUE) return // overloaded: drop; TCP will retransmit
        queue.add(Inbound(buffer.copyOf(length), uid, System.currentTimeMillis()))
        queued.incrementAndGet()
        selector.wakeup()
    }

    // ------------------------------------------------------------------ event loop
    private fun loop() {
        while (running) {
            try {
                selector.select(200)
                while (true) {
                    val item = queue.poll() ?: break
                    queued.decrementAndGet()
                    runCatching { onPacketFromApp(item) }
                }
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (!key.isValid) continue
                    when (val flow = key.attachment()) {
                        is TcpFlow -> runCatching { onTcpReady(flow, key) }.onFailure { resetAndClose(flow) }
                        is UdpFlow -> runCatching { onUdpReady(flow) }.onFailure { closeUdp(flow) }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastRto >= 200) { lastRto = now; checkTimers(now) }
                if (now - lastSweep > 5_000) { lastSweep = now; sweep(now) }
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun onPacketFromApp(item: Inbound) {
        val p = item.packet
        if (Ipv4.isFragment(p)) return
        val ihl = Ipv4.headerLength(p)
        val total = minOf(Ipv4.totalLength(p), p.size)
        if (ihl < 20 || total < ihl + 8) return
        when (Ipv4.protocol(p)) {
            Ipv4.TCP -> if (total >= ihl + 20) onTcpFromApp(p, ihl, total, item.uid, item.at)
            Ipv4.UDP -> onUdpFromApp(p, ihl, total, item.uid, item.at)
        }
    }

    // ------------------------------------------------------------------ TCP
    private fun onTcpFromApp(p: ByteArray, t: Int, total: Int, uid: Int, now: Long) {
        val src = Ipv4.src(p); val dst = Ipv4.dst(p)
        val sport = Ipv4.u16(p, t); val dport = Ipv4.u16(p, t + 2)
        val seq = Ipv4.u32(p, t + 4); val ack = Ipv4.u32(p, t + 8)
        val doff = ((p[t + 12].toInt() ushr 4) and 0x0F) * 4
        val flags = p[t + 13].toInt() and 0xFF
        val window = Ipv4.u16(p, t + 14)
        val payloadOff = t + doff
        val payloadLen = (total - payloadOff).coerceAtLeast(0)
        val syn = flags and Ipv4.SYN != 0
        val hasAck = flags and Ipv4.ACK != 0
        observer.onPacket(uid, now, total, true, dst, dport, Ipv4.TCP, syn && !hasAck)
        val key = "$src:$sport>$dst:$dport"
        val flow = tcp[key]

        if (flags and Ipv4.RST != 0) { flow?.let { closeTcp(it) }; return }
        if (syn && !hasAck) {
            if (flow != null) {
                if (flow.connected) sendToApp(flow, (flow.mySeq - 1) and MASK, Ipv4.SYN or Ipv4.ACK, mss = flow.mss)
                return
            }
            openTcp(key, uid, src, sport, dst, dport, seq, p, t, doff, now)
            return
        }
        if (flow == null) {
            // Late ACK/FIN for a connection we just finished: nothing to do.
            val closedAt = recentlyClosed[key]
            if (closedAt != null && now - closedAt < 30_000) return
            // Unknown connection (e.g. after a restart): make the app give up immediately.
            val rst = if (hasAck) Ipv4.tcp(dst, dport, src, sport, ack, 0, Ipv4.RST, 0)
            else Ipv4.tcp(dst, dport, src, sport, 0, (seq + payloadLen + if (flags and Ipv4.FIN != 0) 1 else 0) and MASK, Ipv4.RST or Ipv4.ACK, 0)
            emit(uid, rst, dst, dport, Ipv4.TCP)
            return
        }
        flow.lastActive = now
        if (hasAck) {
            if (after(ack, flow.appAcked) && !after(ack, flow.mySeq)) {
                flow.acked(((ack - flow.appAcked) and MASK).toInt())
                flow.appAcked = ack
                flow.lastProgress = now
                flow.dupAcks = 0
                flow.retransmits = 0
            } else if (ack == flow.appAcked && flow.inflight() > 0 && payloadLen == 0 && flags and Ipv4.FIN == 0) {
                // Three duplicate ACKs: a segment we sent was lost; resend from the hole (fast retransmit).
                if (++flow.dupAcks == 3) retransmit(flow, now)
            }
            flow.appWindow = window
        }
        if (payloadLen > 0) {
            if (seq == flow.myAck && flow.toServerBytes < MAX_BUFFERED) {
                flow.toServer.add(ByteBuffer.wrap(p.copyOfRange(payloadOff, payloadOff + payloadLen)))
                flow.toServerBytes += payloadLen
                flow.myAck = (flow.myAck + payloadLen) and MASK
                flushToServer(flow)
            }
            sendToApp(flow, flow.mySeq, Ipv4.ACK) // ACK (or duplicate ACK for out-of-order / overflow)
        }
        if (flags and Ipv4.FIN != 0) {
            if (!flow.appFin && ((seq + payloadLen) and MASK) == flow.myAck) {
                flow.appFin = true
                flow.myAck = (flow.myAck + 1) and MASK
                flushToServer(flow)
            }
            sendToApp(flow, flow.mySeq, Ipv4.ACK)
        }
        updateInterest(flow)
        maybeFinish(flow)
    }

    private fun openTcp(key: String, uid: Int, src: Int, sport: Int, dst: Int, dport: Int, seq: Long, p: ByteArray, t: Int, doff: Int, now: Long) {
        val channel = SocketChannel.open()
        val flow = TcpFlow(key, uid, src, sport, dst, dport, channel)
        flow.myAck = (seq + 1) and MASK
        flow.mySeq = Random.nextLong(0, 1L shl 32)
        flow.appAcked = flow.mySeq
        flow.mss = minOf(DEFAULT_MSS, parseMss(p, t, doff) ?: DEFAULT_MSS)
        flow.lastActive = now
        try {
            channel.configureBlocking(false)
            protectTcp(channel.socket())
            tcp[key] = flow
            val done = channel.connect(target(dst, dport))
            flow.selectionKey = channel.register(selector, if (done) 0 else SelectionKey.OP_CONNECT, flow)
            if (done) onConnected(flow)
        } catch (e: IOException) {
            tcp.remove(key)
            runCatching { channel.close() }
            emit(uid, Ipv4.tcp(dst, dport, src, sport, 0, flow.myAck, Ipv4.RST or Ipv4.ACK, 0), dst, dport, Ipv4.TCP)
        }
    }

    private fun onConnected(flow: TcpFlow) {
        flow.connected = true
        sendToApp(flow, flow.mySeq, Ipv4.SYN or Ipv4.ACK, mss = flow.mss)
        flow.mySeq = (flow.mySeq + 1) and MASK
        flow.appAcked = flow.mySeq
        updateInterest(flow)
    }

    private fun onTcpReady(flow: TcpFlow, key: SelectionKey) {
        if (key.isConnectable) {
            if (flow.channel.finishConnect()) onConnected(flow)
            return
        }
        if (key.isWritable) flushToServer(flow)
        if (key.isValid && key.isReadable) readFromServer(flow)
        if (tcp[flow.key] === flow) {
            updateInterest(flow)
            maybeFinish(flow)
        }
    }

    private fun readFromServer(flow: TcpFlow) {
        val room = flow.appWindow - flow.inflight()
        if (room <= 0) return
        readBuffer.clear()
        readBuffer.limit(minOf(room.toInt(), readBuffer.capacity()))
        val n = flow.channel.read(readBuffer)
        flow.lastActive = System.currentTimeMillis()
        if (n < 0) {
            flow.serverEof = true
            if (flow.inflight() == 0L) flow.lastProgress = System.currentTimeMillis()
            sendToApp(flow, flow.mySeq, Ipv4.FIN or Ipv4.ACK)
            flow.finSent = true
            flow.mySeq = (flow.mySeq + 1) and MASK
            return
        }
        val data = readBuffer.array()
        var off = 0
        while (off < n) {
            val chunk = minOf(flow.mss, n - off)
            sendToApp(flow, flow.mySeq, Ipv4.ACK or Ipv4.PSH, data, off, chunk)
            if (flow.inflight() == 0L) flow.lastProgress = System.currentTimeMillis()
            flow.remember(data, off, chunk)
            flow.mySeq = (flow.mySeq + chunk) and MASK
            off += chunk
        }
    }

    private fun flushToServer(flow: TcpFlow) {
        if (!flow.connected) return
        while (flow.toServer.isNotEmpty()) {
            val head = flow.toServer.peekFirst()
            val written = try {
                flow.channel.write(head)
            } catch (e: IOException) {
                resetAndClose(flow)
                return
            }
            flow.toServerBytes -= written
            if (head.hasRemaining()) break
            flow.toServer.pollFirst()
        }
        if (flow.toServer.isEmpty() && flow.appFin && !flow.outputShut) {
            flow.outputShut = true
            runCatching { flow.channel.shutdownOutput() }
        }
    }

    private fun updateInterest(flow: TcpFlow) {
        val key = flow.selectionKey ?: return
        if (!key.isValid) return
        var ops = 0
        if (!flow.connected) ops = ops or SelectionKey.OP_CONNECT
        else {
            if (flow.toServer.isNotEmpty()) ops = ops or SelectionKey.OP_WRITE
            if (!flow.serverEof && flow.inflight() < flow.appWindow) ops = ops or SelectionKey.OP_READ
        }
        key.interestOps(ops)
    }

    private fun maybeFinish(flow: TcpFlow) {
        // Close only when both sides finished AND the app has acknowledged everything we sent (incl. FIN).
        if (flow.appFin && flow.serverEof && flow.toServer.isEmpty() && flow.inflight() == 0L) closeTcp(flow)
    }

    /** Go-back-N: resend every unacknowledged byte (and our FIN) starting at the app's last ACK. */
    private fun retransmit(flow: TcpFlow, now: Long) {
        var seq = flow.appAcked
        var off = 0
        val limit = minOf(flow.unackedLen.toLong(), maxOf(flow.appWindow.toLong(), flow.mss.toLong())).toInt()
        while (off < limit) {
            val chunk = minOf(flow.mss, limit - off)
            sendToApp(flow, seq, Ipv4.ACK or Ipv4.PSH, flow.unacked, off, chunk)
            seq = (seq + chunk) and MASK
            off += chunk
        }
        if (flow.finSent && off == flow.unackedLen && ((seq + 1) and MASK) == flow.mySeq) {
            sendToApp(flow, seq, Ipv4.FIN or Ipv4.ACK)
        }
        flow.lastProgress = now
        flow.retransmits++
    }

    private fun resetAndClose(flow: TcpFlow) {
        sendToApp(flow, flow.mySeq, Ipv4.RST or Ipv4.ACK)
        closeTcp(flow)
    }

    private fun closeTcp(flow: TcpFlow) {
        tcp.remove(flow.key)
        if (recentlyClosed.size > 4_096) recentlyClosed.clear()
        recentlyClosed[flow.key] = System.currentTimeMillis()
        flow.selectionKey?.cancel()
        runCatching { flow.channel.close() }
    }

    private fun sendToApp(flow: TcpFlow, seq: Long, flags: Int, data: ByteArray? = null, off: Int = 0, len: Int = 0, mss: Int = 0) {
        val packet = Ipv4.tcp(flow.remoteIp, flow.remotePort, flow.appIp, flow.appPort, seq, flow.myAck, flags, WINDOW, data, off, len, mss)
        emit(flow.uid, packet, flow.remoteIp, flow.remotePort, Ipv4.TCP, syn = false)
    }

    // ------------------------------------------------------------------ UDP
    private fun onUdpFromApp(p: ByteArray, t: Int, total: Int, uid: Int, now: Long) {
        val src = Ipv4.src(p); val dst = Ipv4.dst(p)
        val sport = Ipv4.u16(p, t); val dport = Ipv4.u16(p, t + 2)
        observer.onPacket(uid, now, total, true, dst, dport, Ipv4.UDP, false)
        val key = "$src:$sport>$dst:$dport"
        var flow = udp[key]
        if (flow == null) {
            val channel = DatagramChannel.open()
            try {
                channel.configureBlocking(false)
                protectUdp(channel.socket())
                channel.connect(target(dst, dport))
                flow = UdpFlow(key, uid, src, sport, dst, dport, channel)
                channel.register(selector, SelectionKey.OP_READ, flow)
                udp[key] = flow
            } catch (e: IOException) {
                runCatching { channel.close() }
                return
            }
        }
        flow.lastActive = now
        runCatching { flow.channel.write(ByteBuffer.wrap(p, t + 8, total - t - 8)) }
    }

    private fun onUdpReady(flow: UdpFlow) {
        while (true) {
            readBuffer.clear()
            val n = flow.channel.read(readBuffer)
            if (n <= 0) return
            flow.lastActive = System.currentTimeMillis()
            val packet = Ipv4.udp(flow.remoteIp, flow.remotePort, flow.appIp, flow.appPort, readBuffer.array(), 0, n)
            emit(flow.uid, packet, flow.remoteIp, flow.remotePort, Ipv4.UDP)
        }
    }

    private fun closeUdp(flow: UdpFlow) {
        udp.remove(flow.key)
        runCatching { flow.channel.close() }
    }

    // ------------------------------------------------------------------ shared
    private fun emit(uid: Int, packet: ByteArray, remoteIp: Int, remotePort: Int, protocol: Int, syn: Boolean = false) {
        observer.onPacket(uid, System.currentTimeMillis(), packet.size, false, remoteIp, remotePort, protocol, syn)
        writeToTun(packet)
    }

    private fun checkTimers(now: Long) {
        for (flow in tcp.values.toList()) {
            if (flow.connected && flow.inflight() > 0 && now - flow.lastProgress > RTO_MILLIS) {
                if (flow.retransmits > MAX_RETRANSMITS) resetAndClose(flow) else retransmit(flow, now)
            }
        }
    }

    private fun sweep(now: Long) {
        tcp.values.filter { f ->
            val idle = now - f.lastActive
            (!f.connected && idle > 30_000) || idle > TCP_IDLE_MILLIS || (f.appFin && idle > 60_000)
        }.forEach(::resetAndClose)
        udp.values.filter { f -> now - f.lastActive > if (f.remotePort == 53) 15_000 else UDP_IDLE_MILLIS }.forEach(::closeUdp)
    }

    private fun parseMss(p: ByteArray, t: Int, doff: Int): Int? {
        var i = t + 20
        val end = minOf(t + doff, p.size)
        while (i < end) {
            when (val kind = p[i].toInt() and 0xFF) {
                0 -> return null
                1 -> i++
                else -> {
                    if (i + 1 >= end) return null
                    val len = p[i + 1].toInt() and 0xFF
                    if (len < 2) return null
                    if (kind == 2 && len == 4 && i + 3 < end) return Ipv4.u16(p, i + 2)
                    i += len
                }
            }
        }
        return null
    }

    companion object {
        private const val MASK = 0xFFFFFFFFL
        private const val WINDOW = 65_535
        private const val DEFAULT_MSS = 1_360
        private const val MAX_BUFFERED = 512 * 1024
        private const val MAX_QUEUE = 4_096
        private const val TCP_IDLE_MILLIS = 10 * 60_000L
        private const val UDP_IDLE_MILLIS = 60_000L
        private const val RTO_MILLIS = 400L
        private const val MAX_RETRANSMITS = 60

        /** a is after b in 32-bit sequence space. */
        private fun after(a: Long, b: Long): Boolean {
            val d = (a - b) and MASK
            return d != 0L && d < (1L shl 31)
        }
    }
}
