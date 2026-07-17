package dev.branchlens.util

object TextUtil {
    fun looksBinary(bytes: ByteArray, sampleSize: Int = 4096): Boolean {
        val limit = minOf(bytes.size, sampleSize)
        for (i in 0 until limit) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    fun looksBinary(text: String): Boolean {
        for (ch in text) {
            if (ch.code == 0) return true
        }
        return false
    }

    fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * Number of lines, using the editor convention: an empty document has 1 line,
     * and a trailing newline does not add an extra line.
     */
    fun countLines(text: String): Int {
        if (text.isEmpty()) return 1
        var count = 1
        for (ch in text) {
            if (ch == '\n') count++
        }
        if (text.endsWith('\n')) count--
        return count.coerceAtLeast(1)
    }

    fun utf8SizeExceeds(text: String, maxBytes: Long): Boolean {
        if (maxBytes < 0) return true
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            bytes += when {
                ch.code <= 0x7F -> 1
                ch.code <= 0x7FF -> 2
                Character.isHighSurrogate(ch) && index + 1 < text.length &&
                    Character.isLowSurrogate(text[index + 1]) -> {
                    index++
                    4
                }
                else -> 3
            }
            if (bytes > maxBytes) return true
            index++
        }
        return false
    }

    fun stableHash(text: String): String {
        var h1 = 0x9E3779B9L.toInt()
        var h2 = 0x85EBCA6BL.toInt()
        for (ch in text) {
            h1 = (h1 xor ch.code) * 0x01000193
            h2 = (h2 xor ch.code.inv()) * 0x01000193
        }
        return "%08x%08x".format(h1, h2)
    }
}
