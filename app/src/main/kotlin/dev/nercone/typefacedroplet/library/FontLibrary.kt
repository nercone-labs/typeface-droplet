package dev.nercone.typefacedroplet.library

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import dev.nercone.typefacedroplet.engine.Engine
import dev.nercone.typefacedroplet.engine.EngineJson
import dev.nercone.typefacedroplet.engine.FontInfo
import dev.nercone.typefacedroplet.engine.FontSource
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A font file the user can assign to roles. */
@Serializable
data class LibraryFont(
    /** The SHA-256 of the font bytes; the id of the font in configurations. */
    val id: String,
    val source: FontSource,
    val info: FontInfo,
    val origin: Origin,
    /** For a bundled font: its asset name under `fonts/`. For an imported font: the original file name. */
    val name: String,
) {
    enum class Origin { BUNDLED, IMPORTED }
}

/**
 * The fonts available to the user: the fonts bundled in the APK and the fonts the user imported.
 *
 * Bundled fonts are stored uncompressed in the APK and referred to in place, as a range of the
 * APK file, so they are never copied. Imported fonts are copied into the files directory,
 * named after their SHA-256. The list, with the inspection results, is kept in `library.json`;
 * bundled fonts are inspected again only when the APK changes.
 */
class FontLibrary(private val context: Context, private val engine: Engine) {
    private val mutex = Mutex()
    private val _fonts = MutableStateFlow<List<LibraryFont>>(emptyList())
    val fonts: StateFlow<List<LibraryFont>> = _fonts.asStateFlow()

    private val directory = File(context.filesDir, DIRECTORY)
    private val index = File(context.filesDir, INDEX)
    private val serializer = ListSerializer(LibraryFont.serializer())

    fun font(id: String): LibraryFont? = _fonts.value.firstOrNull { it.id == id }

    fun bundled(name: String): LibraryFont? = _fonts.value.firstOrNull { it.origin == LibraryFont.Origin.BUNDLED && it.name == name }

    /** Loads the list and refreshes the bundled fonts if the APK changed. */
    suspend fun load() = mutex.withLock {
        val stored = withContext(Dispatchers.IO) {
            runCatching { EngineJson.json.decodeFromString(serializer, index.readText()) }.getOrDefault(emptyList())
        }
        val apk = context.applicationInfo.sourceDir
        val bundled = withContext(Dispatchers.IO) { context.assets.list(ASSET_DIRECTORY).orEmpty() }
            .filter { name -> EXTENSIONS.any { name.endsWith(".$it", ignoreCase = true) } }
            .sorted()
            .mapNotNull { name ->
                val source = withContext(Dispatchers.IO) {
                    context.assets.openFd("$ASSET_DIRECTORY/$name").use { descriptor ->
                        FontSource(apk, descriptor.startOffset, descriptor.length)
                    }
                }
                stored.firstOrNull { it.origin == LibraryFont.Origin.BUNDLED && it.name == name && it.source == source }
                    ?: runCatching {
                        val id = engine.hash(source).value.sha256
                        LibraryFont(id, source, engine.inspect(source).value, LibraryFont.Origin.BUNDLED, name)
                    }.getOrNull()
            }
        val imported = stored.filter { it.origin == LibraryFont.Origin.IMPORTED && File(it.source.path).isFile }
        publish(bundled + imported)
    }

    /** Copies a font chosen by the user into the library. Throws if it is not a font the engine can read. */
    suspend fun import(uri: Uri, displayName: String): LibraryFont = mutex.withLock {
        withContext(Dispatchers.IO) { directory.mkdirs() }
        val temporary = File(directory, ".import.tmp")
        val id = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("cannot open $uri")
            input.use { stream ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        _fonts.value.firstOrNull { it.id == id }?.let {
            temporary.delete()
            return@withLock it
        }
        val info = try {
            engine.inspect(FontSource(temporary.absolutePath)).value
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
        val file = File(directory, "$id.${info.format}")
        withContext(Dispatchers.IO) {
            if (!temporary.renameTo(file)) throw IllegalStateException("cannot store $file")
        }
        val font = LibraryFont(id, FontSource(file.absolutePath), info, LibraryFont.Origin.IMPORTED, displayName)
        publish(_fonts.value + font)
        font
    }

    /** Removes an imported font. Bundled fonts cannot be removed. */
    suspend fun remove(id: String) = mutex.withLock {
        val font = _fonts.value.firstOrNull { it.id == id && it.origin == LibraryFont.Origin.IMPORTED } ?: return@withLock
        withContext(Dispatchers.IO) { File(font.source.path).delete() }
        publish(_fonts.value - font)
    }

    suspend fun publish(fonts: List<LibraryFont>) {
        _fonts.value = fonts.sortedWith(ORDER)
        withContext(Dispatchers.IO) {
            val temporary = File(context.filesDir, "$INDEX.tmp")
            temporary.writeText(EngineJson.json.encodeToString(serializer, _fonts.value))
            temporary.renameTo(index)
        }
    }

    /** A typeface for previews: face `index` at `weight`, with the `wght` axis set when the face has one. */
    fun typeface(font: LibraryFont, index: Int = 0, weight: Int = 400, italic: Boolean = false): Typeface? = runCatching {
        val builder = when (font.origin) {
            LibraryFont.Origin.BUNDLED -> Typeface.Builder(context.assets, "$ASSET_DIRECTORY/${font.name}")
            LibraryFont.Origin.IMPORTED -> Typeface.Builder(File(font.source.path))
        }
        val face = font.info.faces.getOrNull(index)
        builder.setTtcIndex(index).setWeight(weight).setItalic(italic)
        face?.weightAxis?.let { axis -> builder.setFontVariationSettings("'wght' ${weight.toFloat().coerceIn(axis.minimum, axis.maximum)}") }
        builder.build()
    }.getOrNull()

    companion object {
        const val ASSET_DIRECTORY = "fonts"
        const val DIRECTORY = "fonts"
        const val INDEX = "library.json"
        const val BUFFER_SIZE = 256 * 1024
        val EXTENSIONS = listOf("ttf", "otf", "ttc")

        /** Bundled fonts first, then by family, upright before italic, then by name. */
        val ORDER: Comparator<LibraryFont> = compareBy<LibraryFont> { it.origin }
            .thenBy { it.info.faces.firstOrNull()?.family.orEmpty() }
            .thenBy { it.info.faces.firstOrNull()?.italic == true }
            .thenBy { it.name }
    }
}
