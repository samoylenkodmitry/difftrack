package dev.branchlens.git

import java.nio.file.Path

object GitPathUtil {
    fun canonical(path: Path): Path = try {
        path.toRealPath()
    } catch (_: Throwable) {
        path.toAbsolutePath().normalize()
    }

    fun relativePath(repoRoot: Path, file: Path): String? = try {
        val canonicalRoot = canonical(repoRoot)
        val canonicalFile = canonical(file)
        if (!canonicalFile.startsWith(canonicalRoot)) {
            null
        } else {
            canonicalRoot.relativize(canonicalFile).toString().replace('\\', '/')
        }
    } catch (_: Throwable) {
        null
    }
}
