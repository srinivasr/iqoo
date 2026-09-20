package com.dhrashta.x.enforcement

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import java.net.InetAddress
import java.net.InetSocketAddress

class PacketInspector(context: Context) {
    data class PacketInfo(
        val version: Int,
        val protocol: Int,
        val source: InetSocketAddress,
        val destination: InetSocketAddress,
    )

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    fun getUid(buffer: ByteArray, length: Int): Int {
        val packet = parse(buffer, length) ?: return Process.INVALID_UID
        return runCatching {
            connectivityManager.getConnectionOwnerUid(
                packet.protocol,
                packet.source,
                packet.destination,
            )
        }.getOrDefault(Process.INVALID_UID)
    }

    fun remoteAddress(buffer: ByteArray, length: Int): String =
        parse(buffer, length)?.destination?.address?.hostAddress.orEmpty()

    fun parse(buffer: ByteArray, length: Int): PacketInfo? {
        if (length <= 0 || length > buffer.size) return null
        return when ((buffer[0].toInt() ushr 4) and 0x0F) {
            4 -> parseIpv4(buffer, length)
            6 -> parseIpv6(buffer, length)
            else -> null
        }
    }

    private fun parseIpv4(buffer: ByteArray, length: Int): PacketInfo? {
        if (length < 20) return null
        val headerLength = (buffer[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength + 4) return null
        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != TCP && protocol != UDP) return null
        return PacketInfo(
            version = 4,
            protocol = protocol,
            source = InetSocketAddress(InetAddress.getByAddress(buffer.copyOfRange(12, 16)), u16(buffer, headerLength)),
            destination = InetSocketAddress(InetAddress.getByAddress(buffer.copyOfRange(16, 20)), u16(buffer, headerLength + 2)),
        )
    }

    private fun parseIpv6(buffer: ByteArray, length: Int): PacketInfo? {
        if (length < 44) return null
        val protocol = buffer[6].toInt() and 0xFF
        if (protocol != TCP && protocol != UDP) return null
        return PacketInfo(
            version = 6,
            protocol = protocol,
            source = InetSocketAddress(InetAddress.getByAddress(buffer.copyOfRange(8, 24)), u16(buffer, 40)),
            destination = InetSocketAddress(InetAddress.getByAddress(buffer.copyOfRange(24, 40)), u16(buffer, 42)),
        )
    }

    private fun u16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private companion object {
        const val TCP = 6
        const val UDP = 17
    }
}
