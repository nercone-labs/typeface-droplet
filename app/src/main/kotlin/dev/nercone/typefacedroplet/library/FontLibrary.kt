package dev.nercone.typefacedroplet.library

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import dev.nercone.typefacedroplet.engine.Engine
import dev.nercone.typefacedroplet.engine.EngineJson
import dev.nercone.typefacedroplet.engine.FaceInfo
import dev.nercone.typefacedroplet.engine.FontInfo
import dev.nercone.typefacedroplet.engine.FontSource
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A font file the user can choose. */
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

    val face: FaceInfo? get() = info.faces.firstOrNull()

    /** The name shown to users: family and subfamily of the first face, which tells upright and italic apart. */
    val label: String
        get() = face?.let { face -> listOfNotNull(face.family, face.subfamily?.takeIf { it != "Regular" }).joinToString(" ") }?.ifEmpty { null } ?: name

    /** Whether the first face is italic. */
    val italic: Boolean get() = face?.italic == true
}

/**
 * The fonts available to the user: the fonts bundled in the APK and the fonts the user imported.
 *
 * Bundled fonts are stored uncompressed in the APK and referred to in place, as a range of the
 * APK file, so they are never copied. Imported fonts are copied into the files directory,
 * named after their SHA-256. The list, with the inspection results, is kept in `library.json`;
 * bundled fonts are inspected again only when the APK changes.
 */
class FontLibrary(val context: Context, val engine: Engine) {
    val mutex = Mutex()
    val fonts = MutableStateFlow<List<LibraryFont>>(emptyList())
    val directory = File(context.filesDir, DIRECTORY)
    val index = File(context.filesDir, INDEX)
    val serializer = ListSerializer(LibraryFont.serializer())
    val typefaces = ConcurrentHashMap<TypefaceKey, Typeface>()

    /** The face of a font a preview is drawn with. */
    data class TypefaceKey(val id: String, val weight: Int, val italic: Boolean)

    fun font(id: String): LibraryFont? = fonts.value.firstOrNull { it.id == id }

    fun bundled(name: String): LibraryFont? = fonts.value.firstOrNull { it.origin == LibraryFont.Origin.BUNDLED && it.name == name }

    /** Loads the list and refreshes the bundled fonts if the APK changed. */
    suspend fun load() = mutex.withLock {
        val stored = withContext(Dispatchers.IO) {
            runCatching { EngineJson.json.decodeFromString(serializer, index.readText()) }.getOrDefault(emptyList())
        }
        val apk = context.applicationInfo.sourceDir
        val names = withContext(Dispatchers.IO) { context.assets.list(ASSET_DIRECTORY).orEmpty() }
            .filter { name -> EXTENSIONS.any { name.endsWith(".$it", ignoreCase = true) } }
            .sorted()
        val bundled = names.mapNotNull { name ->
            val source = withContext(Dispatchers.IO) {
                context.assets.openFd("$ASSET_DIRECTORY/$name").use { descriptor -> FontSource(apk, descriptor.startOffset, descriptor.length) }
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
        fonts.value.firstOrNull { it.id == id }?.let {
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
        publish(fonts.value + font)
        font
    }

    /** Removes an imported font. Bundled fonts cannot be removed. */
    suspend fun remove(id: String) = mutex.withLock {
        val font = fonts.value.firstOrNull { it.id == id && it.origin == LibraryFont.Origin.IMPORTED } ?: return@withLock
        withContext(Dispatchers.IO) { File(font.source.path).delete() }
        typefaces.keys.removeAll { it.id == id }
        publish(fonts.value - font)
    }

    suspend fun publish(list: List<LibraryFont>) {
        fonts.value = list.sortedWith(ORDER)
        withContext(Dispatchers.IO) {
            val temporary = File(context.filesDir, "$INDEX.tmp")
            temporary.writeText(EngineJson.json.encodeToString(serializer, fonts.value))
            temporary.renameTo(index)
        }
    }

    /**
     * A typeface for previews: the first face at `weight`, with the `wght` axis set when the face
     * has one. Typefaces are cached, as fonts with large character sets take a while to load.
     */
    fun typeface(font: LibraryFont, weight: Int = 400, italic: Boolean = font.italic): Typeface? {
        val key = TypefaceKey(font.id, weight, italic)
        typefaces[key]?.let { return it }
        val typeface = runCatching {
            val builder = when (font.origin) {
                LibraryFont.Origin.BUNDLED -> Typeface.Builder(context.assets, "$ASSET_DIRECTORY/${font.name}")
                LibraryFont.Origin.IMPORTED -> Typeface.Builder(File(font.source.path))
            }
            builder.setTtcIndex(0).setWeight(weight).setItalic(italic)
            font.face?.weightAxis?.let { axis -> builder.setFontVariationSettings("'wght' ${weight.toFloat().coerceIn(axis.minimum, axis.maximum)}") }
            builder.build()
        }.getOrNull() ?: return null
        typefaces[key] = typeface
        return typeface
    }

    companion object {
        const val ASSET_DIRECTORY = "fonts"
        const val DIRECTORY = "fonts"
        const val INDEX = "library.json"
        const val BUFFER_SIZE = 256 * 1024
        val EXTENSIONS = listOf("ttf", "otf", "ttc")

        /** Bundled fonts first, then by family, upright before italic, then by name. */
        val ORDER: Comparator<LibraryFont> = compareBy<LibraryFont> { it.origin }
            .thenBy { it.face?.family.orEmpty() }
            .thenBy { it.italic }
            .thenBy { it.name }
    }
}
