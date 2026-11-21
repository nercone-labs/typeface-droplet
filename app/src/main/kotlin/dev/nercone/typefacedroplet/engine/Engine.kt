package dev.nercone.typefacedroplet.engine

import android.content.Context
import dev.nercone.typefacedroplet.config.DurableFile
import dev.nercone.typefacedroplet.privilege.Privilege
import dev.nercone.typefacedroplet.privilege.ProcessRunner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * The Rust engine (`typeface-droplet`), packaged as `libtypeface-droplet.so` in the native library directory.
 * Commands that read or change the system run through a `Privilege`; inspecting font files
 * runs as the application itself.
 */
class Engine(val context: Context) {
    val executable: String = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE).absolutePath
    val json = EngineJson.json

    /** Runs the engine as the application. */
    suspend fun <T> run(arguments: List<String>, serializer: KSerializer<T>, timeoutMillis: Long = TIMEOUT_MILLIS): EngineResult<T> =
        withContext(Dispatchers.IO) {
            EngineProtocol.read(json, ProcessRunner.run(listOf(executable) + arguments, timeoutMillis), serializer)
        }

    /** Runs the engine through `privilege`. */
    suspend fun <T> run(privilege: Privilege, arguments: List<String>, serializer: KSerializer<T>, timeoutMillis: Long = TIMEOUT_MILLIS): EngineResult<T> =
        EngineProtocol.read(json, privilege.execute(listOf(executable) + arguments + listOf("--store", STORE), timeoutMillis), serializer)

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
        val text = json.encodeToString(Config.serializer(), config)
        val file = File(context.filesDir, APPLYING_CONFIG)
        withContext(Dispatchers.IO) { DurableFile.write(file, text) }
        val arguments = listOf("apply", file.absolutePath) + if (boot) listOf("--boot") else emptyList()
        val result = run(privilege, arguments, ApplyReport.serializer(), LONG_TIMEOUT_MILLIS)
        // Kept once the engine applied it, so that a failed application never becomes the one
        // that a boot applies again (`appliedConfig`).
        withContext(NonCancellable + Dispatchers.IO) { DurableFile.write(File(context.filesDir, APPLIED_CONFIG), text) }
        return result
    }

    /**
     * The configuration the engine applied last, as it was written for the engine, or `null`
     * when there is none (nothing was applied since this version) or it cannot be read.
     */
    suspend fun appliedConfig(): Config? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(Config.serializer(), File(context.filesDir, APPLIED_CONFIG).readText()) }.getOrNull()
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
        const val EXECUTABLE = "libtypeface-droplet.so"
        const val STORE = "/data/adb/typeface-droplet"
        /** The configuration handed to the engine by `apply`, which it must read whole. */
        const val APPLYING_CONFIG = "applying-config.json"

        /** The configuration the engine applied last (`appliedConfig`). */
        const val APPLIED_CONFIG = "applied-config.json"
        const val TIMEOUT_MILLIS = 60_000L

        /** Applying installs fonts, which may take minutes for large fonts on slow storage. */
        const val LONG_TIMEOUT_MILLIS = 10 * 60_000L
    }
}
