package com.dhrashta.x.enforcement.relay

/**
 * Minimal IPv4 / TCP / UDP parsing and building for the userspace relay. Pure JVM (unit-tested).
 * Addresses are kept as Int (network byte order packed big-endian), ports as Int.
 */
object Ipv4 {
    const val TCP = 6
    const val UDP = 17
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    fun version(b: ByteArray): Int = (b[0].toInt() ushr 4) and 0x0F
    fun headerLength(b: ByteArray): Int = (b[0].toInt() and 0x0F) * 4
    fun totalLength(b: ByteArray): Int = u16(b, 2)
    fun protocol(b: ByteArray): Int = b[9].toInt() and 0xFF
    fun src(b: ByteArray): Int = i32(b, 12)
    fun dst(b: ByteArray): Int = i32(b, 16)
    /** True for a non-first fragment or a packet with "more fragments" set. */
    fun isFragment(b: ByteArray): Boolean = (u16(b, 6) and 0x3FFF) != 0

    fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    fun i32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    fun u32(b: ByteArray, o: Int): Long = i32(b, o).toLong() and 0xFFFFFFFFL

    fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
    fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    fun addressString(a: Int): String = "${a ushr 24 and 0xFF}.${a ushr 16 and 0xFF}.${a ushr 8 and 0xFF}.${a and 0xFF}"
    fun addressBytes(a: Int): ByteArray = byteArrayOf((a ushr 24).toByte(), (a ushr 16).toByte(), (a ushr 8).toByte(), a.toByte())

    /** Internet checksum over [len] bytes at [off], plus [initial] (already-summed pseudo header). */
    fun checksum(b: ByteArray, off: Int, len: Int, initial: Long = 0): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += u16(b, i); i += 2 }
        if (i < end) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun pseudo(src: Int, dst: Int, proto: Int, l4len: Int): Long =
        (src.toLong() ushr 16 and 0xFFFF) + (src.toLong() and 0xFFFF) +
            (dst.toLong() ushr 16 and 0xFFFF) + (dst.toLong() and 0xFFFF) + proto + l4len

    private var ipId = 1
    private fun writeIpHeader(p: ByteArray, total: Int, proto: Int, src: Int, dst: Int) {
        p[0] = 0x45; p[1] = 0
        put16(p, 2, total)
        put16(p, 4, synchronized(this) { ipId = (ipId + 1) and 0xFFFF; ipId })
        put16(p, 6, 0x4000) // don't fragment
        p[8] = 64; p[9] = proto.toByte()
        put16(p, 10, 0)
        put32(p, 12, src.toLong() and 0xFFFFFFFFL)
        put32(p, 16, dst.toLong() and 0xFFFFFFFFL)
        put16(p, 10, checksum(p, 0, 20))
    }

    /** Builds an IPv4+TCP segment. [mss] > 0 adds an MSS option (used on SYN-ACK only). */
    fun tcp(
        src: Int, srcPort: Int, dst: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int,
        payload: ByteArray? = null, payloadOff: Int = 0, payloadLen: Int = 0, mss: Int = 0,
    ): ByteArray {
        val opt = if (mss > 0) 4 else 0
        val tcpLen = 20 + opt + payloadLen
        val p = ByteArray(20 + tcpLen)
        writeIpHeader(p, p.size, TCP, src, dst)
        val t = 20
        put16(p, t, srcPort); put16(p, t + 2, dstPort)
        put32(p, t + 4, seq and 0xFFFFFFFFL); put32(p, t + 8, ack and 0xFFFFFFFFL)
        p[t + 12] = (((20 + opt) / 4) shl 4).toByte()
        p[t + 13] = flags.toByte()
        put16(p, t + 14, window)
        if (opt > 0) { p[t + 20] = 2; p[t + 21] = 4; put16(p, t + 22, mss) }
        if (payloadLen > 0) System.arraycopy(payload!!, payloadOff, p, t + 20 + opt, payloadLen)
        put16(p, t + 16, checksum(p, t, tcpLen, pseudo(src, dst, TCP, tcpLen)))
        return p
    }

    fun udp(src: Int, srcPort: Int, dst: Int, dstPort: Int, payload: ByteArray, off: Int, len: Int): ByteArray {
        val udpLen = 8 + len
        val p = ByteArray(20 + udpLen)
        writeIpHeader(p, p.size, UDP, src, dst)
        put16(p, 20, srcPort); put16(p, 22, dstPort); put16(p, 24, udpLen)
        System.arraycopy(payload, off, p, 28, len)
        val c = checksum(p, 20, udpLen, pseudo(src, dst, UDP, udpLen))
        put16(p, 26, if (c == 0) 0xFFFF else c)
        return p
    }
}
