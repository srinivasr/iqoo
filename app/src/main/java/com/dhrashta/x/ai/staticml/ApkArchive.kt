package com.dhrashta.x.ai.staticml

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Minimal, tolerant APK (ZIP) reader. It reads the central directory itself instead of using
 * java.util.zip.ZipFile, because ZipFile refuses archives with duplicate entry names, and duplicate
 * names are exactly one of the tampering signs we want to report (MODERN_APK_TAMPERED).
 * Pure JVM: no Android dependency, so it is unit-tested on the build machine.
 */
class ApkArchive private constructor(private val file: File, val entries: List<Entry>) {
    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    val names: List<String> get() = entries.map { it.name }

    /** Root-level classes.dex, classes2.dex, ... in archive order (same selection as androguard get_all_dex). */
    fun dexEntries(): List<Entry> = entries.filter { DEX_NAME.matches(it.name) }

    /** Uncompressed bytes of [entry], or null if it is larger than [maxBytes] or unreadable. */
    fun read(entry: Entry, maxBytes: Long = MAX_ENTRY_BYTES): ByteArray? {
        if (entry.uncompressedSize > maxBytes || entry.compressedSize > maxBytes) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val local = ByteArray(30)
                raf.seek(entry.localHeaderOffset)
                raf.readFully(local)
                val lb = ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN)
                if (lb.getInt(0) != LOCAL_SIG) return null
                val nameLen = lb.getShort(26).toInt() and 0xFFFF
                val extraLen = lb.getShort(28).toInt() and 0xFFFF
                raf.seek(entry.localHeaderOffset + 30 + nameLen + extraLen)
                val compressed = ByteArray(entry.compressedSize.toInt())
                raf.readFully(compressed)
                when (entry.method) {
                    0 -> compressed
                    8 -> inflate(compressed, entry.uncompressedSize.toInt())
                    else -> null
                }
            }
        }.getOrNull()
    }

    companion object {
        private const val EOCD_SIG = 0x06054b50
        private const val CEN_SIG = 0x02014b50
        private const val LOCAL_SIG = 0x04034b50
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        private val DEX_NAME = Regex("^classes\\d*\\.dex$")

        fun open(file: File): ApkArchive {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val tailSize = minOf(length, 65_557L).toInt()
                val tail = ByteArray(tailSize)
                raf.seek(length - tailSize)
                raf.readFully(tail)
                val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
                var eocd = -1
                for (i in tailSize - 22 downTo 0) {
                    if (tb.getInt(i) == EOCD_SIG) { eocd = i; break }
                }
                require(eocd >= 0) { "Not a ZIP archive (no end of central directory)" }
                val total = tb.getShort(eocd + 10).toInt() and 0xFFFF
                val cdSize = tb.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
                val cdOffset = tb.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
                require(cdOffset + cdSize <= length) { "Central directory outside file" }
                val cd = ByteArray(cdSize.toInt())
                raf.seek(cdOffset)
                raf.readFully(cd)
                val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
                val entries = ArrayList<Entry>(total)
                var p = 0
                while (p + 46 <= cd.size && b.getInt(p) == CEN_SIG) {
                    val method = b.getShort(p + 10).toInt() and 0xFFFF
                    val csize = b.getInt(p + 20).toLong() and 0xFFFFFFFFL
                    val usize = b.getInt(p + 24).toLong() and 0xFFFFFFFFL
                    val n = b.getShort(p + 28).toInt() and 0xFFFF
                    val m = b.getShort(p + 30).toInt() and 0xFFFF
                    val k = b.getShort(p + 32).toInt() and 0xFFFF
                    val off = b.getInt(p + 42).toLong() and 0xFFFFFFFFL
                    if (p + 46 + n > cd.size) break
                    val name = String(cd, p + 46, n, Charsets.UTF_8)
                    entries += Entry(name, method, csize, usize, off)
                    p += 46 + n + m + k
                }
                return ApkArchive(file, entries)
            }
        }

        private fun inflate(data: ByteArray, expected: Int): ByteArray {
            val inflater = Inflater(true)
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream(if (expected > 0) expected else data.size * 3)
                val buf = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buf)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    }
                    out.write(buf, 0, count)
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
    }
}
