package dev.branchlens.git

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

data class GitResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
) {
    val stdoutText: String get() = String(stdout, Charsets.UTF_8)
}

class GitTimeoutException(message: String) : RuntimeException(message)
class GitNotFoundException(message: String) : RuntimeException(message)

/**
 * Thin wrapper around the `git` executable. Never spawns through a shell.
 * All arguments are passed as a list; the working directory is set per call.
 */
class GitCli(
    private val executable: String = "git",
    private val maxConcurrent: Int = 3,
) {
    private val semaphore = Semaphore(permits = maxConcurrent.coerceAtLeast(1))

    suspend fun run(
        workingDir: Path,
        args: List<String>,
        timeoutMs: Long,
        captureBinaryStdout: Boolean = false,
    ): GitResult = withContext(Dispatchers.IO) {
        semaphore.acquire()
        try {
            execute(workingDir, args, timeoutMs, captureBinaryStdout)
        } finally {
            semaphore.release()
        }
    }

    private suspend fun execute(
        workingDir: Path,
        args: List<String>,
        timeoutMs: Long,
        captureBinaryStdout: Boolean,
    ): GitResult {
        val command = ArrayList<String>(args.size + 1).apply {
            add(executable)
            addAll(args)
        }
        val process: Process = try {
            ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            throw GitNotFoundException("Failed to launch git: ${e.message}")
        }

        val stdoutBuf = ByteArrayOutputStream()
        val stderrBuf = StringBuilder()

        return try {
            coroutineScope {
                val stdoutJob: Job = launch(Dispatchers.IO) {
                    process.inputStream.use { input ->
                        val buf = ByteArray(8 * 1024)
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            stdoutBuf.write(buf, 0, n)
                        }
                    }
                }
                val stderrJob: Job = launch(Dispatchers.IO) {
                    process.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                        while (isActive) {
                            val line = r.readLine() ?: break
                            stderrBuf.append(line).append('\n')
                        }
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                while (process.isAlive) {
                    if (!coroutineContext.isActive) {
                        process.destroyForcibly()
                        throw CancellationException("Git cancelled")
                    }
                    if (System.currentTimeMillis() > deadline) {
                        process.destroyForcibly()
                        throw GitTimeoutException("git ${args.firstOrNull()} timed out after ${timeoutMs}ms")
                    }
                    Thread.sleep(20)
                }

                stdoutJob.join()
                stderrJob.join()

                val bytes = if (captureBinaryStdout) stdoutBuf.toByteArray() else stdoutBuf.toByteArray()
                GitResult(
                    exitCode = process.exitValue(),
                    stdout = bytes,
                    stderr = stderrBuf.toString().trim(),
                )
            }
        } catch (e: Throwable) {
            if (process.isAlive) process.destroyForcibly()
            throw e
        }
    }
}
