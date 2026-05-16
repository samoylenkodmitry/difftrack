package dev.branchlens.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object TempFileUtil {
    fun writeTempUtf8(prefix: String, suffix: String, text: String): Path {
        val path = Files.createTempFile(prefix, suffix)
        Files.write(
            path,
            text.toByteArray(Charsets.UTF_8),
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        path.toFile().deleteOnExit()
        return path
    }

    fun safeDelete(path: Path?) {
        if (path == null) return
        try {
            Files.deleteIfExists(path)
        } catch (_: Throwable) {
            // Best effort.
        }
    }
}
