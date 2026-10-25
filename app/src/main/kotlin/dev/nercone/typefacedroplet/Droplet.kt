package dev.nercone.typefacedroplet

import android.content.Context
import android.net.Uri
import dev.nercone.typefacedroplet.config.ConfigRepository
import dev.nercone.typefacedroplet.config.Selection
import dev.nercone.typefacedroplet.config.Settings
import dev.nercone.typefacedroplet.config.Slot
import dev.nercone.typefacedroplet.engine.ApplyReport
import dev.nercone.typefacedroplet.engine.BootHook
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.Engine
import dev.nercone.typefacedroplet.engine.EngineException
import dev.nercone.typefacedroplet.engine.EngineResult
import dev.nercone.typefacedroplet.engine.FamilyInfo
import dev.nercone.typefacedroplet.engine.LogLine
import dev.nercone.typefacedroplet.engine.RevertReport
import dev.nercone.typefacedroplet.engine.SoftRestartReport
import dev.nercone.typefacedroplet.engine.StatusReport
import dev.nercone.typefacedroplet.library.FontLibrary
import dev.nercone.typefacedroplet.library.LibraryFont
import dev.nercone.typefacedroplet.privilege.Privilege
import dev.nercone.typefacedroplet.privilege.PrivilegeChoice
import dev.nercone.typefacedroplet.privilege.PrivilegeException
import dev.nercone.typefacedroplet.privilege.PrivilegeSelection
import dev.nercone.typefacedroplet.privilege.PrivilegeSelector
import dev.nercone.typefacedroplet.privilege.RootPrivilege
import dev.nercone.typefacedroplet.privilege.ShizukuPrivilege
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The application: the engine, the font library, the configuration, the settings and the
 * privileges, with the operations the user interface and the boot worker share. Operations
 * that change the system are serialized.
 */
class Droplet(context: Context) {
    val engine = Engine(context)
    val library = FontLibrary(context, engine)
    val configs = ConfigRepository(context)
    val settings = Settings(context)
    val shizuku = ShizukuPrivilege(context)
    val selector = PrivilegeSelector(shizuku, RootPrivilege())
    val operations = Mutex()
    val initialization = Mutex()
    var initialized = false

    init {
        // A configuration of an earlier version, which had no font selection, counts as edited
        // by hand, so that choosing a font never discards rules the user wrote. Decided before
        // anything else can write the settings.
        if (!settings.preferences.contains(Settings.CUSTOMIZED)) settings.update { it.copy(customized = configs.file.exists()) }
    }

    /** The outcome of an application: the engine report, and the boot hook if it was installed or failed. */
    data class Applied(val report: ApplyReport, val log: List<LogLine>, val hook: BootHook?, val hookFailure: String?)

    /** Loads the library and the configuration, creating the recommended configuration on first use. */
    suspend fun initialize() = initialization.withLock {
        if (initialized) return@withLock
        library.load()
        val stored = configs.load()
        if (stored == null) {
            configs.save(recommended())
            settings.update { it.copy(customized = false) }
        } else {
            configs.save(ConfigRepository.name(ConfigRepository.synchronize(stored, library.fonts.value), settings.values.value.names))
        }
        initialized = true
    }

    /** The recommended configuration (the behavior of TypeFace Magic) with the bundled fonts. */
    suspend fun recommended(): Config = compose(Selection.recommended(library.fonts.value), null)

    /**
     * The configuration the recommended rules make of `selection`. Components, restart targets
     * and the other options are taken from `base` when there is one. Fonts are named as the
     * user named them, or else as the recommended configuration names them.
     */
    suspend fun compose(selection: Selection, base: Config?): Config {
        val fonts = ConfigRepository.synchronize(base ?: Config(), library.fonts.value).let { config ->
            config.copy(fonts = config.fonts.map { it.copy(rename = settings.values.value.names[it.id]) })
        }
        val preset = engine.preset(selection.preset(fonts)).value
        val composed = if (base == null) preset else preset.copy(
            components = base.components,
            restart = base.restart,
            guardSeconds = base.guardSeconds,
            allowUnknownVersions = base.allowUnknownVersions,
        )
        return ConfigRepository.synchronize(composed, library.fonts.value).let { config ->
            config.copy(fonts = config.fonts.map { entry -> entry.copy(rename = preset.font(entry.id)?.rename ?: settings.values.value.names[entry.id]) })
        }
    }

    /** Sets the family name written into a font, or `null` for the name the configuration gives it. */
    suspend fun rename(id: String, name: String?) {
        settings.update { it.copy(names = if (name == null) it.names - id else it.names + (id to name)) }
        configs.update { config ->
            if (settings.values.value.customized) {
                config.copy(fonts = config.fonts.map { if (it.id == id) it.copy(rename = name) else it })
            } else {
                compose(Selection.of(config, library.fonts.value), config)
            }
        }
    }

    fun selection(): Selection = configs.config.value?.let { Selection.of(it, library.fonts.value) } ?: Selection()

    /** Puts `font` (or nothing) into `slot`. */
    suspend fun select(slot: Slot, font: String?) = configs.update { config ->
        val fonts = library.fonts.value
        val selection = Selection.of(config, fonts).with(slot, font)
        if (settings.values.value.customized) selection.applyTo(config, fonts) else compose(selection, config)
    }

    /** Edits roles and rules by hand: from now on, choosing fonts only changes the faces of the roles. */
    suspend fun customize(transform: (Config) -> Config) {
        settings.update { it.copy(customized = true) }
        configs.update { transform(it) }
    }

    /** Changes options that do not touch roles and rules. */
    suspend fun configure(transform: (Config) -> Config) = configs.update { transform(it) }

    /** Returns to the recommended configuration with the bundled fonts. */
    suspend fun reset() {
        configs.save(recommended())
        settings.update { it.copy(customized = false) }
    }

    /**
     * Chooses the privilege. With `interactive`, a missing permission is asked for while the
     * user is here, once: Shizuku keeps no refusal, so a declined permission is remembered and
     * not asked for again. Callers that run without the user, such as the boot worker, leave
     * `interactive` false.
     */
    suspend fun selectPrivilege(interactive: Boolean = false): PrivilegeSelection {
        val values = settings.values.value
        val selection = selector.select(values.privilege, interactive && !values.permissionDeclined)
        if (selection.declined) settings.update { it.copy(permissionDeclined = true) }
        return selection
    }

    /** Whether the privilege choice may use Shizuku, and a Shizuku binder may arrive. */
    fun awaitsShizuku(): Boolean = settings.values.value.privilege != PrivilegeChoice.ROOT && shizuku.mayStart()

    suspend fun privilege(): Privilege = selectPrivilege().privilege ?: throw PrivilegeException("no privilege can run the engine as root")

    suspend fun status(): EngineResult<StatusReport> = engine.status(privilege())

    suspend fun families(): EngineResult<List<FamilyInfo>> = engine.families(privilege())

    /**
     * Applies the current configuration. With `boot`, the engine refuses if its guard tripped.
     * Then the boot hook is installed (with a fresh copy of the engine) or removed, as the
     * settings say; a failure of that does not undo the application.
     */
    suspend fun apply(boot: Boolean): Applied = operations.withLock {
        initialize()
        val config = configs.config.value ?: throw EngineException("config", "there is no configuration", emptyList())
        val privilege = privilege()
        val result = engine.apply(privilege, ConfigRepository.synchronize(config, library.fonts.value), boot)
        settings.update { it.copy(applied = true) }
        val hook = runCatching { synchronizeHook(privilege) }
        Applied(result.value, result.log, hook.getOrNull(), hook.exceptionOrNull()?.let { it.message ?: it.toString() })
    }

    /** Installs or removes the boot hook as the settings say. */
    suspend fun synchronizeHook(privilege: Privilege): BootHook =
        if (settings.values.value.bootHook) engine.installHook(privilege).value else engine.removeHook(privilege).value

    suspend fun setBootHook(enabled: Boolean): BootHook = operations.withLock {
        settings.update { it.copy(bootHook = enabled) }
        synchronizeHook(privilege())
    }

    /**
     * Applies the configuration after a boot, unless it is applied already (by the boot hook)
     * or the user has restored the original fonts. Returns `null` when nothing had to be done.
     */
    suspend fun applyAfterBoot(): Applied? {
        if (!settings.values.value.applied) return null
        if (status().value.applied) return null
        return apply(boot = true)
    }

    /**
     * Restores the original fonts. The engine forgets the saved configuration first, so even
     * when a later step fails, the fonts are not applied again after the next boot.
     */
    suspend fun revert(): EngineResult<RevertReport> = operations.withLock {
        val privilege = privilege()
        try {
            engine.revert(privilege)
        } finally {
            settings.update { it.copy(applied = false) }
        }
    }

    suspend fun softRestart(): EngineResult<SoftRestartReport> = operations.withLock {
        engine.softRestart(privilege(), configs.config.value?.guardSeconds ?: Config().guardSeconds)
    }

    suspend fun importFont(uri: Uri, name: String): LibraryFont {
        val font = library.import(uri, name)
        configs.update { ConfigRepository.synchronize(it, library.fonts.value) }
        return font
    }

    /** Removes an imported font that no role uses. */
    suspend fun removeFont(id: String) {
        if (configs.config.value?.roles.orEmpty().any { role -> role.faces.any { it.font == id } }) return
        library.remove(id)
        configs.update { ConfigRepository.synchronize(it, library.fonts.value) }
    }
}
