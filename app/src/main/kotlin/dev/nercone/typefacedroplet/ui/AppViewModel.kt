package dev.nercone.typefacedroplet.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nercone.typefacedroplet.Droplet
import dev.nercone.typefacedroplet.DropletApplication
import dev.nercone.typefacedroplet.config.Settings
import dev.nercone.typefacedroplet.config.Slot
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.EngineException
import dev.nercone.typefacedroplet.engine.FamilyInfo
import dev.nercone.typefacedroplet.engine.LogLine
import dev.nercone.typefacedroplet.engine.StatusReport
import dev.nercone.typefacedroplet.library.LibraryFont
import dev.nercone.typefacedroplet.privilege.PrivilegeChoice
import dev.nercone.typefacedroplet.privilege.PrivilegeSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An operation the user started, shown while it runs. Only one runs at a time. */
enum class Operation { PREPARING, CHECKING, APPLYING, REVERTING, RESTARTING, IMPORTING, CONFIGURING_BOOT }

/** What the user is told about the last operation. */
sealed interface Outcome {
    data class Applied(val applied: Droplet.Applied) : Outcome
    data object Reverted : Outcome
    data object Restarting : Outcome
    data class Imported(val imported: Droplet.Imported) : Outcome

    /** The stored configuration could not be read; it was kept as `name`, and the recommended one is in use. */
    data class ConfigSetAside(val name: String) : Outcome

    /** `kind` is the kind of the engine error, such as `restricted`, or `null` for other errors. */
    data class Failed(val text: String, val kind: String?) : Outcome {
        val restricted: Boolean get() = kind == EngineException.RESTRICTED

        companion object {
            fun of(error: Exception): Failed = when (error) {
                is EngineException -> Failed(error.message.orEmpty(), error.kind)
                else -> Failed(error.message ?: error.toString(), null)
            }
        }
    }
}

/** The font families of the system documents, read once they are first needed. */
data class Families(val list: List<FamilyInfo>? = null, val loading: Boolean = false, val error: String? = null)

data class UiState(
    /** Whether the library and the configuration were loaded (or failed to load, which `preparationError` tells). */
    val ready: Boolean = false,
    /** Why the library or the configuration could not be loaded last; **Check again** tries again. */
    val preparationError: Outcome.Failed? = null,
    val operation: Operation? = null,
    val privilege: PrivilegeSelection? = null,
    val status: StatusReport? = null,
    /** Why the state of the system could not be read last, while a privilege is available. */
    val statusError: Outcome.Failed? = null,
    val outcome: Outcome? = null,
    val log: List<LogLine> = emptyList(),
    /** Whether the engine log is shown. */
    val logShown: Boolean = false,
    val families: Families = Families(),
) {
    /** Whether an operation that needs root can be started now. */
    val idle: Boolean get() = ready && operation == null && privilege?.privilege != null
}

/**
 * What every screen shows: the state of the operations and of the system, the configuration,
 * the library and the settings, read together so that a screen never mixes two moments.
 */
data class ScreenData(val state: UiState, val config: Config?, val fonts: List<LibraryFont>, val settings: Settings.Values) {
    /**
     * Whether the configuration changed since it was last applied, while it is applied: the
     * screens then offer to apply it again. Unknown (no digest from an earlier version) is not a
     * change.
     */
    val pending: Boolean by lazy {
        val applied = settings.appliedDigest
        settings.applied && applied != null && state.status?.applied == true && config != null && config.digest() != applied
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val droplet: Droplet = (application as DropletApplication).droplet
    val fonts: StateFlow<List<LibraryFont>> = droplet.library.fonts
    val config: StateFlow<Config?> = droplet.configs.config
    val settings: StateFlow<Settings.Values> = droplet.settings.values
    val state = MutableStateFlow(UiState())

    init {
        run(Operation.PREPARING) {
            prepare()
            // Shizuku sends its binder once this application comes to the foreground; choosing
            // before it arrives would pass it over for su without asking for its permission.
            if (droplet.awaitsShizuku()) droplet.shizuku.awaitBinder(SHIZUKU_BINDER_TIMEOUT_MILLIS)
            check(interactive = true)
        }
    }

    /**
     * Loads the library and the configuration. A failure is told as the outcome instead of
     * stopping the reading of the system that follows, and the screens leave "getting ready" in
     * any case: they must not wait for something that will not come. Checking again retries it.
     */
    suspend fun prepare() {
        try {
            droplet.initialize()
            val setAside = droplet.configs.takeSetAside()
            state.update {
                // A failure told before is over; a configuration moved aside is told once.
                val outcome = when {
                    setAside != null && (it.outcome == null || it.outcome == it.preparationError) -> Outcome.ConfigSetAside(setAside.name)
                    it.outcome == it.preparationError -> null
                    else -> it.outcome
                }
                it.copy(preparationError = null, outcome = outcome)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = Outcome.Failed.of(error)
            state.update { it.copy(preparationError = failure, outcome = failure, log = (error as? EngineException)?.log ?: it.log) }
        } finally {
            state.update { it.copy(ready = true) }
        }
    }

    /**
     * Runs `block` as `operation`, unless another operation runs. A failure becomes the outcome,
     * with the engine log of the attempt, and the state of the system is read again: an operation
     * that failed half-way may have changed it, and the screens must not show the state from
     * before.
     */
    fun run(operation: Operation, block: suspend () -> Unit) {
        if (state.value.operation != null && operation != Operation.PREPARING) return
        state.update { it.copy(operation = operation, outcome = if (operation in UNCHANGING) it.outcome else null) }
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(outcome = Outcome.Failed.of(error), log = (error as? EngineException)?.log.orEmpty()) }
                if (operation !in UNCHANGING) {
                    try {
                        check()
                    } catch (again: CancellationException) {
                        throw again
                    } catch (_: Exception) {
                        // The failure of the operation is what the user is told; a reading
                        // that fails as well must not end the application.
                    }
                }
            } finally {
                state.update { it.copy(operation = null) }
            }
        }
    }

    /**
     * Reads the privilege and the system. With `interactive`, a missing permission is asked for
     * while the user is here; after an operation, no dialog is shown. A failure to read the
     * system is kept apart from the outcome of the operation, which stays as it was, except for
     * a restricted root, which the frame explains with a dialog of its own. The engine log of the
     * failed reading becomes the log, so that the user can read why.
     */
    suspend fun check(interactive: Boolean = false) {
        val selection = try {
            droplet.selectPrivilege(interactive)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A privilege that fails while it is asked (a Shizuku binder that died in between)
            // leaves nothing known: told as a reading that failed, with Check again.
            state.update { it.copy(privilege = null, status = null, statusError = Outcome.Failed.of(error)) }
            return
        }
        state.update { it.copy(privilege = selection) }
        val privilege = selection.privilege
        if (privilege == null) {
            state.update { it.copy(status = null, statusError = null) }
            return
        }
        try {
            val status = droplet.status(privilege)
            state.update { it.copy(status = status.value, statusError = null) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = Outcome.Failed.of(error)
            state.update {
                it.copy(
                    status = null,
                    statusError = failure,
                    outcome = if (failure.restricted) failure else it.outcome,
                    log = (error as? EngineException)?.log ?: it.log,
                )
            }
        }
    }

    /** Loads what failed to load, and reads the privilege and the system again. */
    fun recheck() = run(Operation.CHECKING) {
        prepare()
        check(interactive = true)
    }

    fun grantShizuku() = run(Operation.CHECKING) {
        droplet.settings.update { it.copy(permissionDeclined = false) }
        droplet.shizuku.requestPermission()
        check()
    }

    /** Ends an operation that the user started: tells its outcome, with its log, and reads the system again. */
    suspend fun finish(outcome: Outcome?, log: List<LogLine>) {
        state.update { it.copy(outcome = outcome ?: it.outcome, log = log) }
        check()
    }

    fun apply() = run(Operation.APPLYING) {
        val applied = droplet.apply(boot = false)
        finish(Outcome.Applied(applied), applied.log)
    }

    fun revert() = run(Operation.REVERTING) {
        val result = droplet.revert()
        finish(Outcome.Reverted, result.log)
    }

    /** Restarts the system services; nothing is read afterwards, as this application ends with them. */
    fun softRestart() = run(Operation.RESTARTING) {
        val result = droplet.softRestart()
        state.update { it.copy(outcome = Outcome.Restarting, log = result.log) }
    }

    /**
     * Reads the font families of the system once, for the families of the customization. It
     * runs apart from the operations (it changes nothing and needs no exclusive access), so that
     * an operation running when the families are first needed does not keep them from loading.
     * A failure is kept and shown, with a way to try again (`retryFamilies`).
     */
    fun loadFamilies() {
        val current = state.value
        val privilege = current.privilege?.privilege ?: return
        if (current.families.list != null || current.families.loading || current.families.error != null) return
        state.update { it.copy(families = Families(loading = true)) }
        viewModelScope.launch {
            try {
                val result = droplet.families(privilege)
                state.update { it.copy(families = Families(list = result.value)) }
            } catch (error: CancellationException) {
                state.update { it.copy(families = Families()) }
                throw error
            } catch (error: Exception) {
                state.update { it.copy(families = Families(error = Outcome.Failed.of(error).text)) }
            }
        }
    }

    fun retryFamilies() {
        state.update { it.copy(families = Families()) }
        loadFamilies()
    }

    /** The name the provider gives `uri`, or its last path segment. A provider may be slow, so it is asked off the main thread. */
    suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        name ?: uri.lastPathSegment.orEmpty()
    }

    fun importFont(uri: Uri) = run(Operation.IMPORTING) {
        val imported = droplet.importFont(uri, displayName(uri))
        state.update { it.copy(outcome = Outcome.Imported(imported)) }
    }

    /** Edits that touch no system state run at once, independently of operations; a failure becomes the outcome. */
    fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(outcome = Outcome.Failed.of(error), log = (error as? EngineException)?.log ?: it.log) }
            }
        }
    }

    fun removeFont(id: String) = edit { droplet.removeFont(id) }

    /** The pending change of a font name: names are typed a character at a time. */
    private var renaming: Job? = null

    fun rename(id: String, name: String?) {
        renaming?.cancel()
        renaming = viewModelScope.launch {
            delay(RENAME_DELAY_MILLIS)
            withContext(NonCancellable) {
                try {
                    droplet.rename(id, name)
                } catch (error: Exception) {
                    state.update { it.copy(outcome = Outcome.Failed.of(error)) }
                }
            }
        }
    }

    fun select(slot: Slot, font: String?) = edit { droplet.select(slot, font) }

    fun customize(transform: (Config) -> Config) = edit { droplet.customize(transform) }

    fun configure(transform: (Config) -> Config) = edit { droplet.configure(transform) }

    fun reset() = edit { droplet.reset() }

    /** Changes the privilege choice and reads the system with it; refused while an operation runs, which would keep the old reading. */
    fun setPrivilegeChoice(choice: PrivilegeChoice) {
        if (state.value.operation != null) return
        droplet.settings.update { it.copy(privilege = choice, permissionDeclined = false) }
        recheck()
    }

    fun setBootHook(enabled: Boolean) = run(Operation.CONFIGURING_BOOT) {
        val result = droplet.setBootHook(enabled)
        finish(null, result.log)
    }

    fun setApplyAfterBoot(enabled: Boolean) = droplet.settings.update { it.copy(applyAfterBoot = enabled) }

    fun setNotificationsAsked() = droplet.settings.update { it.copy(notificationsAsked = true) }

    fun dismissOutcome() = state.update { it.copy(outcome = null) }

    fun showLog(shown: Boolean) = state.update { it.copy(logShown = shown) }

    /** Shows `text` as a failure that happened outside an operation. */
    fun fail(text: String) = state.update { it.copy(outcome = Outcome.Failed(text, null)) }

    companion object {
        const val RENAME_DELAY_MILLIS = 600L
        const val SHIZUKU_BINDER_TIMEOUT_MILLIS = 2_000L

        /** The operations that change nothing on the system, and keep the outcome told before them. */
        val UNCHANGING = setOf(Operation.PREPARING, Operation.CHECKING, Operation.IMPORTING)
    }
}
