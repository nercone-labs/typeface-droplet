package dev.nercone.typefacedroplet.config

import android.content.Context
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.EngineJson
import dev.nercone.typefacedroplet.engine.FontEntry
import dev.nercone.typefacedroplet.library.LibraryFont
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The configuration the user edits, stored as `config.json` in the format of the engine.
 *
 * `fonts` always lists every font of the library (keeping the family name the user set for
 * each), so that roles may refer to any of them; the engine only installs the fonts that
 * the rules actually use.
 */
class ConfigRepository(context: Context) {
    private val file = File(context.filesDir, FILE)
    private val _config = MutableStateFlow<Config?>(null)
    val config: StateFlow<Config?> = _config.asStateFlow()

    suspend fun load(): Config? = withContext(Dispatchers.IO) {
        runCatching { EngineJson.json.decodeFromString(Config.serializer(), file.readText()) }.getOrNull()
    }.also { _config.value = it }

    suspend fun save(config: Config) {
        _config.value = config
        withContext(Dispatchers.IO) {
            val temporary = File(file.parentFile, "$FILE.tmp")
            temporary.writeText(EngineJson.json.encodeToString(Config.serializer(), config))
            temporary.renameTo(file)
        }
    }

    suspend fun update(transform: (Config) -> Config) {
        val current = _config.value ?: return
        save(transform(current))
    }

    companion object {
        const val FILE = "config.json"

        /** Makes `fonts` match the library: new fonts are added, removed fonts dropped, moved fonts updated. */
        fun synchronize(config: Config, library: List<LibraryFont>): Config {
            val known = config.fonts.associateBy { it.id }
            val fonts = library.map { font ->
                known[font.id]?.copy(source = font.source) ?: FontEntry(font.id, font.source)
            }
            return config.copy(fonts = fonts)
        }
    }
}
