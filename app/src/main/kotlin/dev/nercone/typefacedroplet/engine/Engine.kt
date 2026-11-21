package dev.nercone.typefacedroplet.engine

import android.content.Context
import dev.nercone.typefacedroplet.privilege.Privilege
import dev.nercone.typefacedroplet.privilege.ProcessRunner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer

/** A failed engine command, with the engine log of the attempt. */
class EngineException(val kind: String, message: String, val log: List<LogLine>) : Exception(message) {
    companion object {
        /** The engine runs as root, but the root manager removed the capabilities it needs. */
        const val RESTRICTED = "restricted"
    }
}

/** The successful result of an engine command, with the engine log. */
data class EngineResult<T>(val value: T, val log: List<LogLine>)

/**
 * The Rust engine (`droplet`), packaged as `libdroplet.so` in the native library directory.
 * Commands that read or change the system run through a `Privilege`; inspecting font files
 * runs as the application itself.
 */
class Engine(val context: Context) {
    val executable: String = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE).absolutePath
    val json = EngineJson.json

    /**
     * The response the engine printed last in `stdout`. `stderr` only explains a missing or
     * invalid response, such as the refusal of `su`, which prints nothing on the output.
     */
    fun decode(stdout: String, stderr: String): EngineResponse {
        val line = stdout.lineSequence().lastOrNull { it.startsWith("{") }
            ?: throw EngineException("output", "the engine printed no result: ${(stdout + "\n" + stderr).trim().take(MESSAGE_LENGTH)}", emptyList())
        return try {
            json.decodeFromString(EngineResponse.serializer(), line)
        } catch (error: SerializationException) {
            throw EngineException("output", "the engine printed an invalid result: ${error.message}", emptyList())
        } catch (error: IllegalArgumentException) {
            throw EngineException("output", "the engine printed an invalid result: ${error.message}", emptyList())
        }
    }

    fun <T> result(response: EngineResponse, serializer: KSerializer<T>): EngineResult<T> {
        val error = response.error
        if (!response.ok || error != null) {
            throw EngineException(error?.kind ?: "unknown", error?.message ?: "the engine failed", response.log)
        }
        val value = response.result ?: throw EngineException("output", "the engine returned no result", response.log)
        val decoded = try {
            json.decodeFromJsonElement(serializer, value)
        } catch (error: SerializationException) {
            throw EngineException("output", "the engine returned an unexpected result: ${error.message}", response.log)
        } catch (error: IllegalArgumentException) {
            throw EngineException("output", "the engine returned an unexpected result: ${error.message}", response.log)
        }
        return EngineResult(decoded, response.log)
    }

    /** Runs the engine as the application. */
    suspend fun <T> run(arguments: List<String>, serializer: KSerializer<T>, timeoutMillis: Long = TIMEOUT_MILLIS): EngineResult<T> =
        withContext(Dispatchers.IO) {
            val output = ProcessRunner.run(listOf(executable) + arguments, timeoutMillis)
            if (output.exitCode == ProcessRunner.TIMEOUT_EXIT_CODE && output.stdout.isBlank()) throw timeout(output.stderr)
            result(decode(output.stdout, output.stderr), serializer)
        }

    /** Runs the engine through `privilege`. */
    suspend fun <T> run(privilege: Privilege, arguments: List<String>, serializer: KSerializer<T>, timeoutMillis: Long = TIMEOUT_MILLIS): EngineResult<T> {
        val output = privilege.execute(listOf(executable) + arguments + listOf("--store", STORE), timeoutMillis)
        if (output.exitCode == ProcessRunner.START_FAILURE_EXIT_CODE && output.stdout.isBlank()) {
            throw EngineException("output", "the engine could not be started: ${output.stderr.take(MESSAGE_LENGTH)}", emptyList())
        }
        if (output.exitCode == ProcessRunner.TIMEOUT_EXIT_CODE && output.stdout.isBlank()) throw timeout(output.stderr)
        return result(decode(output.stdout, output.stderr), serializer)
    }

    fun timeout(stderr: String) = EngineException("timeout", "the engine did not finish in time: ${stderr.take(MESSAGE_LENGTH)}", emptyList())

    suspend fun inspect(source: FontSource): EngineResult<FontInfo> = run(sourceArguments("inspect", source), FontInfo.serializer())

    suspend fun hash(source: FontSource): EngineResult<HashResult> = run(sourceArguments("hash", source), HashResult.serializer(), LONG_TIMEOUT_MILLIS)

    suspend fun preset(preset: Preset): EngineResult<Config> = withTemporary("preset", json.encodeToString(Preset.serializer(), preset)) { file ->
        run(listOf("preset", file.absolutePath), Config.serializer())
    }

    suspend fun validate(config: Config): EngineResult<ValidationReport> = withTemporary("validate", json.encodeToString(Config.serializer(), config)) { file ->
        run(listOf("validate", file.absolutePath), ValidationReport.serializer())
    }

    suspend fun version(): EngineResult<VersionReport> = run(listOf("version"), VersionReport.serializer())

    suspend fun status(privilege: Privilege): EngineResult<StatusReport> = run(privilege, listOf("status"), StatusReport.serializer())

    suspend fun families(privilege: Privilege): EngineResult<List<FamilyInfo>> =
        run(privilege, listOf("families"), ListSerializer(FamilyInfo.serializer()))

    /**
     * Applies `config`. The configuration file must stay readable by the privileged user, so it
     * lives in the files directory; it is written whole before the engine reads it.
     */
    suspend fun apply(privilege: Privilege, config: Config, boot: Boolean): EngineResult<ApplyReport> {
        val file = File(context.filesDir, APPLIED_CONFIG)
        withContext(Dispatchers.IO) {
            val temporary = File(context.filesDir, "$APPLIED_CONFIG.tmp")
            temporary.writeText(json.encodeToString(Config.serializer(), config))
            if (!temporary.renameTo(file)) throw EngineException("config", "cannot write $file", emptyList())
        }
        val arguments = listOf("apply", file.absolutePath) + if (boot) listOf("--boot") else emptyList()
        return run(privilege, arguments, ApplyReport.serializer(), LONG_TIMEOUT_MILLIS)
    }

    suspend fun revert(privilege: Privilege): EngineResult<RevertReport> = run(privilege, listOf("revert"), RevertReport.serializer())

    /** Installs the boot scripts that apply the saved configuration before the framework starts. */
    suspend fun installHook(privilege: Privilege): EngineResult<BootHook> =
        run(privilege, listOf("hook", "install", "--package", context.packageName), BootHook.serializer())

    suspend fun removeHook(privilege: Privilege): EngineResult<BootHook> = run(privilege, listOf("hook", "remove"), BootHook.serializer())

    suspend fun softRestart(privilege: Privilege, guardSeconds: Int): EngineResult<SoftRestartReport> =
        run(privilege, listOf("soft-restart", "--seconds", guardSeconds.toString()), SoftRestartReport.serializer())

    fun sourceArguments(command: String, source: FontSource): List<String> = buildList {
        add(command)
        add(source.path)
        if (source.offset != 0L) addAll(listOf("--offset", source.offset.toString()))
        source.length?.let { addAll(listOf("--length", it.toString())) }
    }

    /**
     * Runs `block` with a new file in the cache directory that holds `text`, and deletes the file
     * afterwards. Each call has a file of its own, so that concurrent calls do not read each
     * other's input.
     */
    suspend fun <T> withTemporary(prefix: String, text: String, block: suspend (File) -> T): T {
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("$prefix-", ".json", context.cacheDir.apply { mkdirs() }).apply { writeText(text) }
        }
        try {
            return block(file)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    companion object {
        const val EXECUTABLE = "libdroplet.so"
        const val STORE = "/data/adb/typeface-droplet"
        const val APPLIED_CONFIG = "applied-config.json"
        const val TIMEOUT_MILLIS = 60_000L

        /** Applying installs fonts, which may take minutes for large fonts on slow storage. */
        const val LONG_TIMEOUT_MILLIS = 10 * 60_000L

        /** How much of the output a message quotes. */
        const val MESSAGE_LENGTH = 500
    }
}
