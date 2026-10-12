package dev.nercone.typefacedroplet.engine

import android.content.Context
import dev.nercone.typefacedroplet.privilege.Privilege
import dev.nercone.typefacedroplet.privilege.ProcessRunner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

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

    fun decode(output: String): EngineResponse {
        val line = output.lineSequence().lastOrNull { it.startsWith("{") }
            ?: throw EngineException("output", "the engine printed no result: ${output.take(500)}", emptyList())
        return try {
            json.decodeFromString(EngineResponse.serializer(), line)
        } catch (error: SerializationException) {
            throw EngineException("output", "the engine printed an invalid result: ${error.message}", emptyList())
        }
    }

    fun <T> result(response: EngineResponse, serializer: KSerializer<T>): EngineResult<T> {
        val error = response.error
        if (!response.ok || error != null) {
            throw EngineException(error?.kind ?: "unknown", error?.message ?: "the engine failed", response.log)
        }
        val value = response.result ?: throw EngineException("output", "the engine returned no result", response.log)
        return EngineResult(json.decodeFromJsonElement(serializer, value), response.log)
    }

    /** Runs the engine as the application. */
    suspend fun <T> run(arguments: List<String>, serializer: KSerializer<T>, timeoutMillis: Long = TIMEOUT_MILLIS): EngineResult<T> =
        withContext(Dispatchers.IO) {
            val output = ProcessRunner.run(listOf(executable) + arguments, timeoutMillis)
            result(decode(output.stdout + "\n" + output.stderr), serializer)
        }

    /** Runs the engine through `privilege`. */
    suspend fun <T> run(privilege: Privilege, arguments: List<String>, serializer: KSerializer<T>, timeoutMillis: Long = TIMEOUT_MILLIS): EngineResult<T> {
        val output = privilege.execute(listOf(executable) + arguments + listOf("--store", STORE), timeoutMillis)
        if (output.exitCode == ProcessRunner.TIMEOUT_EXIT_CODE && output.stdout.isBlank()) {
            throw EngineException("timeout", "the engine did not finish in time: ${output.stderr.take(500)}", emptyList())
        }
        return result(decode(output.stdout), serializer)
    }

    suspend fun inspect(source: FontSource): EngineResult<FontInfo> = run(sourceArguments("inspect", source), FontInfo.serializer())

    suspend fun hash(source: FontSource): EngineResult<HashResult> = run(sourceArguments("hash", source), HashResult.serializer(), LONG_TIMEOUT_MILLIS)

    suspend fun preset(preset: Preset): EngineResult<Config> {
        val file = writeTemporary("preset.json", json.encodeToString(Preset.serializer(), preset))
        return run(listOf("preset", file.absolutePath), Config.serializer())
    }

    suspend fun validate(config: Config): EngineResult<ValidationReport> {
        val file = writeTemporary("validate.json", json.encodeToString(Config.serializer(), config))
        return run(listOf("validate", file.absolutePath), ValidationReport.serializer())
    }

    suspend fun version(): EngineResult<VersionReport> = run(listOf("version"), VersionReport.serializer())

    suspend fun status(privilege: Privilege): EngineResult<StatusReport> = run(privilege, listOf("status"), StatusReport.serializer())

    suspend fun families(privilege: Privilege): EngineResult<List<FamilyInfo>> =
        run(privilege, listOf("families"), kotlinx.serialization.builtins.ListSerializer(FamilyInfo.serializer()))

    /** Applies `config`. The configuration file must stay readable by the privileged user, so it lives in the files directory. */
    suspend fun apply(privilege: Privilege, config: Config, boot: Boolean): EngineResult<ApplyReport> {
        val file = File(context.filesDir, APPLIED_CONFIG)
        withContext(Dispatchers.IO) { file.writeText(json.encodeToString(Config.serializer(), config)) }
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

    suspend fun writeTemporary(name: String, text: String): File = withContext(Dispatchers.IO) {
        File(context.cacheDir, name).apply { writeText(text) }
    }

    companion object {
        const val EXECUTABLE = "libdroplet.so"
        const val STORE = "/data/adb/typeface-droplet"
        const val APPLIED_CONFIG = "applied-config.json"
        const val TIMEOUT_MILLIS = 60_000L

        /** Applying installs fonts, which may take minutes for large fonts on slow storage. */
        const val LONG_TIMEOUT_MILLIS = 10 * 60_000L
    }
}
