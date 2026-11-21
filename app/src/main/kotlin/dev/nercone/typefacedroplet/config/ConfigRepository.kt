package dev.nercone.typefacedroplet.config

import android.content.Context
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.EngineJson
import dev.nercone.typefacedroplet.engine.FontEntry
import dev.nercone.typefacedroplet.library.LibraryFont
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * The configuration the user edits, stored as `config.json` in the format of the engine.
 *
 * `fonts` always lists every font of the library (keeping the family name the user set for
 * each), so that roles may refer to any of them; the engine only installs the fonts that
 * the rules actually use.
 */
class ConfigRepository(context: Context) {
    val file = File(context.filesDir, FILE)
    private val state = MutableStateFlow<Config?>(null)
    val config: StateFlow<Config?> = state
    val mutex = Mutex()

    /** The file an unreadable configuration was moved to by `load`, until `takeSetAside` takes it. */
    @Volatile
    private var setAside: File? = null

    /** The file an unreadable configuration was moved to since this was last called, if any. */
    fun takeSetAside(): File? = setAside.also { setAside = null }

    /**
     * Reads the stored configuration, or `null` when there is none. A file that cannot be
     * decoded (written by a newer version, or damaged) is moved aside, to a name of its own
     * (`setAside`), rather than overwritten, so that the rules the user wrote are not lost when a
     * new configuration is made. A failure to read the file at all (`IOException`) is thrown: the
     * file may be fine, and must not be replaced.
     */
    suspend fun load(): Config? = mutex.withLock {
        val loaded = withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            val text = file.readText()
            try {
                EngineJson.json.decodeFromString(Config.serializer(), text)
            } catch (error: SerializationException) {
                moveAside()
            } catch (error: IllegalArgumentException) {
                moveAside()
            }
        }
        state.value = loaded
        loaded
    }

    /** Moves the unreadable file aside; throws when it cannot, so that nothing replaces it. */
    private fun moveAside(): Config? {
        val target = File(file.parentFile, "$FILE.${System.currentTimeMillis()}$UNREADABLE_SUFFIX")
        if (!file.renameTo(target)) throw IOException("cannot move the unreadable $file aside")
        setAside = target
        return null
    }

    suspend fun save(next: Config) = mutex.withLock { write(next) }

    /** Replaces the configuration with `transform` of the current one, if there is one. */
    suspend fun update(transform: suspend (Config) -> Config) = mutex.withLock {
        state.value?.let { write(transform(it)) }
    }

    /** Writes `next` whole (to a temporary file that replaces the old one), then publishes it. */
    private suspend fun write(next: Config) {
        withContext(Dispatchers.IO) {
            val temporary = File(file.parentFile, "$FILE.tmp")
            temporary.writeText(EngineJson.json.encodeToString(Config.serializer(), next))
            if (!temporary.renameTo(file)) throw IOException("cannot save $file")
        }
        state.value = next
    }

    companion object {
        const val FILE = "config.json"
        const val UNREADABLE_SUFFIX = ".unreadable"

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
