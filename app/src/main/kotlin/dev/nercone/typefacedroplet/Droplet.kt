package dev.nercone.typefacedroplet

import android.content.Context
import dev.nercone.typefacedroplet.config.ConfigRepository
import dev.nercone.typefacedroplet.config.Settings
import dev.nercone.typefacedroplet.engine.ApplyReport
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.Engine
import dev.nercone.typefacedroplet.engine.EngineException
import dev.nercone.typefacedroplet.engine.EngineResult
import dev.nercone.typefacedroplet.engine.FamilyInfo
import dev.nercone.typefacedroplet.engine.Preset
import dev.nercone.typefacedroplet.engine.RevertReport
import dev.nercone.typefacedroplet.engine.SoftRestartReport
import dev.nercone.typefacedroplet.engine.StatusReport
import dev.nercone.typefacedroplet.library.FontLibrary
import dev.nercone.typefacedroplet.library.LibraryFont
import dev.nercone.typefacedroplet.privilege.Privilege
import dev.nercone.typefacedroplet.privilege.PrivilegeException
import dev.nercone.typefacedroplet.privilege.PrivilegeSelection
import dev.nercone.typefacedroplet.privilege.PrivilegeSelector
import dev.nercone.typefacedroplet.privilege.RootPrivilege
import dev.nercone.typefacedroplet.privilege.ShizukuPrivilege
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The application: the engine, the font library, the configuration and the privileges,
 * with the operations the user interface and the boot worker share.
 * Engine operations that change the system are serialized.
 */
class Droplet(context: Context) {
    val engine = Engine(context)
    val library = FontLibrary(context, engine)
    val configs = ConfigRepository(context)
    val settings = Settings(context)
    val shizuku = ShizukuPrivilege(context)
    val selector = PrivilegeSelector(shizuku, RootPrivilege())
    private val operations = Mutex()
    private val initialization = Mutex()
    private var initialized = false

    /** Loads the library and the configuration, creating the recommended configuration on first use. */
    suspend fun initialize() = initialization.withLock {
        if (initialized) return@withLock
        library.load()
        val config = configs.load() ?: recommended()
        configs.save(ConfigRepository.synchronize(config, library.fonts.value))
        initialized = true
    }

    /** The recommended configuration (the behavior of TypeFace Magic) with the bundled fonts. */
    suspend fun recommended(): Config {
        fun entry(name: String) = library.bundled(name)?.let { dev.nercone.typefacedroplet.engine.FontEntry(it.id, it.source) }
        val preset = Preset(
            sans = entry(SANS),
            sansItalic = entry(SANS_ITALIC),
            serif = entry(SERIF),
            serifItalic = entry(SERIF_ITALIC),
            mono = entry(MONO),
            monoItalic = entry(MONO_ITALIC),
        )
        return ConfigRepository.synchronize(engine.preset(preset).value, library.fonts.value)
    }

    suspend fun selectPrivilege(): PrivilegeSelection = selector.select(settings.privilege)

    suspend fun privilege(): Privilege =
        selectPrivilege().privilege ?: throw PrivilegeException("no privilege can run the engine as root")

    suspend fun status(): EngineResult<StatusReport> = engine.status(privilege())

    suspend fun families(): EngineResult<List<FamilyInfo>> = engine.families(privilege())

    /** Applies the current configuration. With `boot`, the engine refuses if its guard tripped. */
    suspend fun apply(boot: Boolean): EngineResult<ApplyReport> = operations.withLock {
        initialize()
        val config = configs.config.value ?: throw EngineException("config", "there is no configuration", emptyList())
        val result = engine.apply(privilege(), ConfigRepository.synchronize(config, library.fonts.value), boot)
        settings.applied = true
        result
    }

    suspend fun revert(): EngineResult<RevertReport> = operations.withLock {
        val result = engine.revert(privilege())
        settings.applied = false
        result
    }

    suspend fun softRestart(): EngineResult<SoftRestartReport> = operations.withLock {
        engine.softRestart(privilege(), configs.config.value?.guardSeconds ?: DEFAULT_GUARD_SECONDS)
    }

    suspend fun importFont(uri: android.net.Uri, name: String): LibraryFont {
        val font = library.import(uri, name)
        configs.update { ConfigRepository.synchronize(it, library.fonts.value) }
        return font
    }

    suspend fun removeFont(id: String) {
        library.remove(id)
        configs.update { ConfigRepository.synchronize(it, library.fonts.value) }
    }

    companion object {
        const val SANS = "Sans.ttf"
        const val SANS_ITALIC = "Sans-Italic.ttf"
        const val SERIF = "Serif.ttf"
        const val SERIF_ITALIC = "Serif-Italic.ttf"
        const val MONO = "Mono.ttf"
        const val MONO_ITALIC = "Mono-Italic.ttf"
        const val DEFAULT_GUARD_SECONDS = 60
    }
}
