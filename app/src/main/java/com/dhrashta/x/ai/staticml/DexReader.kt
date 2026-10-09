package com.dhrashta.x.ai.staticml

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads exactly what the S0 feature extractor needs from one classes*.dex, matching androguard 4.x:
 *  - every string in the string table (string_ids order), and
 *  - every method_id (defined AND referenced methods, e.g. java.lang.Class.getMethods) as
 *    (method name, class descriptor) in method_ids order. This equals androguard 4 DEX.get_methods(),
 *    which returns MethodIdItem objects.
 * Pure JVM, bounds-checked: a malformed dex throws, and the caller treats that dex as unreadable.
 */
class DexReader(bytes: ByteArray) {
    private val b: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    init {
        require(bytes.size >= 0x70 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() && bytes[2] == 'x'.code.toByte()) {
            "Not a dex file"
        }
    }

    private val stringIdsSize = b.getInt(0x38)
    private val stringIdsOff = b.getInt(0x3C)
    private val typeIdsSize = b.getInt(0x40)
    private val typeIdsOff = b.getInt(0x44)
    private val methodIdsSize = b.getInt(0x58)
    private val methodIdsOff = b.getInt(0x5C)
    private val classDefsSize = b.getInt(0x60)
    private val classDefsOff = b.getInt(0x64)

    private val stringCache = arrayOfNulls<String>(stringIdsSize.coerceAtLeast(0))

    fun stringCount(): Int = stringIdsSize

    fun string(index: Int): String {
        stringCache[index]?.let { return it }
        val dataOff = b.getInt(stringIdsOff + index * 4)
        var p = dataOff
        // uleb128 utf16 length, then MUTF-8 bytes up to a 0 byte
        var utf16 = 0
        var shift = 0
        while (true) {
            val v = b.get(p++).toInt() and 0xFF
            utf16 = utf16 or ((v and 0x7F) shl shift)
            if (v and 0x80 == 0) break
            shift += 7
            require(shift < 35) { "Bad uleb128" }
        }
        val sb = StringBuilder(utf16)
        while (true) {
            val a = b.get(p++).toInt() and 0xFF
            if (a == 0) break
            when {
                a < 0x80 -> sb.append(a.toChar())
                a and 0xE0 == 0xC0 -> {
                    val c2 = b.get(p++).toInt() and 0x3F
                    sb.append((((a and 0x1F) shl 6) or c2).toChar())
                }
                a and 0xF0 == 0xE0 -> {
                    val c2 = b.get(p++).toInt() and 0x3F
                    val c3 = b.get(p++).toInt() and 0x3F
                    sb.append((((a and 0x0F) shl 12) or (c2 shl 6) or c3).toChar())
                }
                else -> sb.append('�')
            }
        }
        // Join surrogate pairs the way Python sees them is not needed: lengths use code points below.
        val s = sb.toString()
        stringCache[index] = s
        return s
    }

    private fun typeDescriptor(typeIdx: Int): String = string(b.getInt(typeIdsOff + typeIdx * 4))

    /** Calls [visit] with (methodName, classDescriptor) for every method_id, in table order. */
    fun forEachMethodId(visit: (String, String) -> Unit) {
        for (m in 0 until methodIdsSize) {
            val item = methodIdsOff + m * 8
            val classIdx = b.getShort(item).toInt() and 0xFFFF
            val nameIdx = b.getInt(item + 4)
            visit(string(nameIdx), typeDescriptor(classIdx))
        }
    }

    /** Calls [visit] with (methodName, classDescriptor) for each method defined in this dex. */
    fun forEachDefinedMethod(visit: (String, String) -> Unit) {
        for (c in 0 until classDefsSize) {
            val def = classDefsOff + c * 32
            val classIdx = b.getInt(def)
            val classDataOff = b.getInt(def + 24)
            if (classDataOff == 0) continue
            val classDescriptor = typeDescriptor(classIdx)
            val cursor = intArrayOf(classDataOff)
            val staticFields = uleb(cursor)
            val instanceFields = uleb(cursor)
            val directMethods = uleb(cursor)
            val virtualMethods = uleb(cursor)
            repeat(staticFields + instanceFields) { uleb(cursor); uleb(cursor) }
            for (count in intArrayOf(directMethods, virtualMethods)) {
                var methodIdx = 0
                repeat(count) {
                    methodIdx += uleb(cursor)
                    uleb(cursor) // access flags
                    uleb(cursor) // code offset
                    require(methodIdx in 0 until methodIdsSize) { "Bad method index" }
                    val nameIdx = b.getInt(methodIdsOff + methodIdx * 8 + 4)
                    visit(string(nameIdx), classDescriptor)
                }
            }
        }
    }

    private fun uleb(cursor: IntArray): Int {
        var result = 0
        var shift = 0
        while (true) {
            val v = b.get(cursor[0]++).toInt() and 0xFF
            result = result or ((v and 0x7F) shl shift)
            if (v and 0x80 == 0) return result
            shift += 7
            require(shift < 35) { "Bad uleb128" }
        }
    }
}
