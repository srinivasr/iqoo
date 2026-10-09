package com.dhrashta.x.enforcement.relay

import kotlin.math.sqrt

/**
 * Per-app traffic windows for the live network model, computed exactly like mischief_extract.py
 * (`Win.add` / `Win.vector`): 10-second windows, 12 features, sequences of the last 10 windows.
 *
 * Features (same order as training): bytes_up, bytes_down, pkts_up, pkts_down, up_down_byte_ratio,
 * mean_pkt_size, unique_dst_ips, unique_dst_ports, iat_mean, iat_std, tcp_syn_count, udp_pkt_frac.
 * Sizes are IP total lengths; inter-arrival times are in seconds; unique IPs/ports count outgoing
 * packets only; SYN counts SYN-without-ACK. Pure JVM; thread-safe.
 */
class LiveWindowFeatures(private val windowMillis: Long = 10_000, private val seqLen: Int = 10) {
    data class Sequence(val uid: Int, val windows: Array<FloatArray>, val activeWindows: Int, val endMillis: Long)

    private class Win {
        var bu = 0L; var bd = 0L; var pu = 0; var pd = 0; var syn = 0; var udp = 0
        val dst = HashSet<Int>(); val ports = HashSet<Int>()
        var ts = DoubleArray(64); var n = 0

        fun add(tsSeconds: Double, size: Int, outgoing: Boolean, remoteIp: Int, remotePort: Int, protocol: Int, syn: Boolean) {
            if (outgoing) { bu += size; pu++; dst += remoteIp } else { bd += size; pd++ }
            if (n == ts.size && n < MAX_TIMESTAMPS) ts = ts.copyOf(minOf(ts.size * 2, MAX_TIMESTAMPS))
            if (n < ts.size) ts[n++] = tsSeconds
            if (protocol == Ipv4.TCP) {
                if (outgoing) ports += remotePort
                if (syn) this.syn++
            } else if (protocol == Ipv4.UDP) {
                udp++
                if (outgoing) ports += remotePort
            }
        }

        fun vector(): FloatArray {
            val total = pu + pd
            if (total < 1) return FloatArray(FEATURES.size)
            var mean = 0.0
            var std = 0.0
            if (n > 1) {
                val sorted = ts.copyOf(n).also { it.sort() }
                val k = n - 1
                var sum = 0.0
                for (i in 1 until n) sum += sorted[i] - sorted[i - 1]
                mean = sum / k
                var sq = 0.0
                for (i in 1 until n) { val d = sorted[i] - sorted[i - 1] - mean; sq += d * d }
                std = sqrt(sq / k)
            }
            return floatArrayOf(
                bu.toFloat(), bd.toFloat(), pu.toFloat(), pd.toFloat(),
                (bu / (bd + 1.0)).toFloat(),
                ((bu + bd).toDouble() / total).toFloat(),
                dst.size.toFloat(), ports.size.toFloat(),
                mean.toFloat(), std.toFloat(),
                syn.toFloat(), (udp.toDouble() / total).toFloat(),
            )
        }
    }

    private class UidState(var index: Long) {
        var current = Win()
        val history = ArrayDeque<FloatArray>()
    }

    private val states = HashMap<Int, UidState>()

    @Synchronized
    fun record(uid: Int, tsMillis: Long, ipLength: Int, outgoing: Boolean, remoteIp: Int, remotePort: Int, protocol: Int, syn: Boolean) {
        val idx = tsMillis / windowMillis
        val state = states.getOrPut(uid) { UidState(idx) }
        if (idx > state.index) close(state, idx)
        if (idx < state.index) return // clock went backwards; ignore
        state.current.add(tsMillis / 1000.0, ipLength, outgoing, remoteIp, remotePort, protocol, syn)
    }

    /**
     * Closes every window that ended before [nowMillis] and returns, per app, the last [seqLen]
     * closed windows (oldest first) when at least one of them has traffic. Fully idle apps are dropped,
     * like training, which skipped all-zero sequences.
     */
    @Synchronized
    fun roll(nowMillis: Long): List<Sequence> {
        val idx = nowMillis / windowMillis
        val out = ArrayList<Sequence>()
        val idle = ArrayList<Int>()
        for ((uid, state) in states) {
            if (idx <= state.index) continue
            close(state, idx)
            val active = state.history.count { w -> w.any { it != 0f } }
            if (active == 0) { idle += uid; continue }
            val padded = Array(seqLen) { i ->
                val from = i - (seqLen - state.history.size)
                if (from >= 0) state.history.elementAt(from) else FloatArray(FEATURES.size)
            }
            out += Sequence(uid, padded, active, idx * windowMillis)
        }
        idle.forEach { states.remove(it) }
        return out
    }

    @Synchronized
    fun clear() = states.clear()

    private fun close(state: UidState, newIndex: Long) {
        state.history.addLast(state.current.vector())
        // Windows with no packets in between are idle (all-zero), as in training.
        val gaps = minOf(newIndex - state.index - 1, seqLen.toLong()).toInt()
        repeat(gaps) { state.history.addLast(FloatArray(FEATURES.size)) }
        while (state.history.size > seqLen) state.history.removeFirst()
        state.current = Win()
        state.index = newIndex
    }

    companion object {
        val FEATURES = listOf(
            "bytes_up", "bytes_down", "pkts_up", "pkts_down", "up_down_byte_ratio", "mean_pkt_size",
            "unique_dst_ips", "unique_dst_ports", "iat_mean", "iat_std", "tcp_syn_count", "udp_pkt_frac",
        )
        private const val MAX_TIMESTAMPS = 200_000
    }
}
