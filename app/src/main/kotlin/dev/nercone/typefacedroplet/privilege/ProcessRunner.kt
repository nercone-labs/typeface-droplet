package dev.nercone.typefacedroplet.privilege

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Runs a process and collects its output. Used in the application and in the privileged service alike. */
object ProcessRunner {
    /** The exit code reported when the process did not finish in time and was killed. */
    const val TIMEOUT_EXIT_CODE = -1

    /**
     * How long the output is still read after a process that timed out was killed. A child of the
     * killed process (the engine started by `su`) may keep the output open, and the caller must
     * not wait for it without end.
     */
    const val DRAIN_MILLIS = 5_000L

    fun run(command: List<String>, timeoutMillis: Long): CommandResult {
        val process = ProcessBuilder(command).start()
        process.outputStream.close()
        val stdout = AtomicReference("")
        val stderr = AtomicReference("")
        val readers = listOf(
            thread(name = "stdout", isDaemon = true) { stdout.set(process.inputStream.bufferedReader().readText()) },
            thread(name = "stderr", isDaemon = true) { stderr.set(process.errorStream.bufferedReader().readText()) },
        )
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (finished) {
            readers.forEach { it.join() }
        } else {
            process.destroyForcibly()
            process.waitFor()
            readers.forEach { it.join(DRAIN_MILLIS) }
        }
        return CommandResult(if (finished) process.exitValue() else TIMEOUT_EXIT_CODE, stdout.get(), stderr.get())
    }
}
