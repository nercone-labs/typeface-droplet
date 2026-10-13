package dev.nercone.typefacedroplet.config

import android.content.Context
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.EngineJson
import dev.nercone.typefacedroplet.engine.FontEntry
import dev.nercone.typefacedroplet.library.LibraryFont
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The configuration the user edits, stored as `config.json` in the format of the engine.
 *
 * `fonts` always lists every font of the library (keeping the family name the user set for
 * each), so that roles may refer to any of them; the engine only installs the fonts that
 * the rules actually use.
 */
class ConfigRepository(context: Context) {
    val file = File(context.filesDir, FILE)
    val config = MutableStateFlow<Config?>(null)
    val mutex = Mutex()

    suspend fun load(): Config? = withContext(Dispatchers.IO) {
        runCatching { EngineJson.json.decodeFromString(Config.serializer(), file.readText()) }.getOrNull()
    }.also { config.value = it }

    suspend fun save(next: Config) = mutex.withLock { write(next) }

    /** Replaces the configuration with `transform` of the current one, if there is one. */
    suspend fun update(transform: suspend (Config) -> Config) = mutex.withLock {
        config.value?.let { write(transform(it)) }
    }

    suspend fun write(next: Config) {
        config.value = next
        withContext(Dispatchers.IO) {
            val temporary = File(file.parentFile, "$FILE.tmp")
            temporary.writeText(EngineJson.json.encodeToString(Config.serializer(), next))
            if (!temporary.renameTo(file)) throw IllegalStateException("cannot save $file")
        }
    }

    companion object {
        const val FILE = "config.json"

        /**
         * The family names that earlier versions wrote into the fonts of the roles sans, serif and
         * mono when the user named none. The engine now names each copy of a font after the family
         * it replaces (Roboto, Google Sans, Noto Serif, ...), which a fixed name would prevent.
         */
        val FORMER_DEFAULT_NAMES = setOf("Roboto", "Noto Serif", "Droid Sans Mono")

        /**
         * Gives every font the family name the user wrote (`names`). A name the configuration has
         * from an earlier version stays, unless it is one of the former default names.
         */
        fun name(config: Config, names: Map<String, String>): Config =
            config.copy(fonts = config.fonts.map { entry -> entry.copy(rename = names[entry.id] ?: entry.rename?.takeUnless { it in FORMER_DEFAULT_NAMES }) })

        /** Makes `fonts` match the library: new fonts are added, removed fonts dropped, moved fonts updated. */
        fun synchronize(config: Config, library: List<LibraryFont>): Config {
            val known = config.fonts.associateBy { it.id }
            val fonts = library.map { font -> known[font.id]?.copy(source = font.source) ?: FontEntry(font.id, font.source) }
            return config.copy(fonts = fonts)
        }
    }
}
