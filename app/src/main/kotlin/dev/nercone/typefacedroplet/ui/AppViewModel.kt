package dev.nercone.typefacedroplet.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nercone.typefacedroplet.Droplet
import dev.nercone.typefacedroplet.DropletApplication
import dev.nercone.typefacedroplet.config.Slot
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.EngineException
import dev.nercone.typefacedroplet.engine.FamilyInfo
import dev.nercone.typefacedroplet.engine.LogLine
import dev.nercone.typefacedroplet.engine.StatusReport
import dev.nercone.typefacedroplet.privilege.PrivilegeChoice
import dev.nercone.typefacedroplet.privilege.PrivilegeSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An operation the user started, shown while it runs. Only one runs at a time. */
enum class Operation { PREPARING, CHECKING, APPLYING, REVERTING, RESTARTING, IMPORTING, LOADING_FAMILIES, CONFIGURING_BOOT }

/** What the user is told about the last operation. */
sealed interface Outcome {
    data class Applied(val applied: Droplet.Applied) : Outcome
    data object Reverted : Outcome
    data object Restarting : Outcome

    /** `kind` is the kind of the engine error, such as `restricted`, or `null` for other errors. */
    data class Failed(val text: String, val kind: String?) : Outcome {
        val restricted: Boolean get() = kind == EngineException.RESTRICTED
    }
}

data class UiState(
    val ready: Boolean = false,
    val operation: Operation? = null,
    val privilege: PrivilegeSelection? = null,
    val status: StatusReport? = null,
    val outcome: Outcome? = null,
    val log: List<LogLine> = emptyList(),
    /** The families of the system documents, once loaded. */
    val families: List<FamilyInfo>? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val droplet = (application as DropletApplication).droplet
    val fonts = droplet.library.fonts
    val config = droplet.configs.config
    val settings = droplet.settings.values
    val state = MutableStateFlow(UiState())

    init {
        run(Operation.PREPARING) {
            droplet.initialize()
            state.update { it.copy(ready = true) }
            refreshNow()
        }
    }

    /** Runs `block` as `operation`, unless another operation runs; failures become the outcome. */
    fun run(operation: Operation, block: suspend () -> Unit) {
        if (state.value.operation != null && operation != Operation.PREPARING) return
        state.update { it.copy(operation = operation) }
        viewModelScope.launch {
            try {
                block()
            } catch (error: EngineException) {
                state.update { it.copy(outcome = Outcome.Failed(error.message.orEmpty(), error.kind), log = error.log) }
            } catch (error: Exception) {
                state.update { it.copy(outcome = Outcome.Failed(error.message ?: error.toString(), null)) }
            } finally {
                state.update { it.copy(operation = null) }
            }
        }
    }

    /** Edits that touch no system state run at once, independently of operations. */
    fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: Exception) {
                state.update { it.copy(outcome = Outcome.Failed(error.message ?: error.toString(), null)) }
            }
        }
    }

    suspend fun refreshNow() {
        val selection = droplet.selectPrivilege()
        state.update { it.copy(privilege = selection) }
        val status = if (selection.privilege != null) droplet.status().value else null
        state.update { it.copy(status = status) }
    }

    fun refresh() = run(Operation.CHECKING) { refreshNow() }

    fun apply() = run(Operation.APPLYING) {
        val applied = droplet.apply(boot = false)
        state.update { it.copy(outcome = Outcome.Applied(applied), log = applied.log) }
        refreshNow()
    }

    fun revert() = run(Operation.REVERTING) {
        val result = droplet.revert()
        state.update { it.copy(outcome = Outcome.Reverted, log = result.log) }
        refreshNow()
    }

    fun softRestart() = run(Operation.RESTARTING) {
        val result = droplet.softRestart()
        state.update { it.copy(outcome = Outcome.Restarting, log = result.log) }
    }

    fun grantShizuku() = run(Operation.CHECKING) {
        droplet.shizuku.requestPermission()
        refreshNow()
    }

    fun loadFamilies() = run(Operation.LOADING_FAMILIES) {
        val result = droplet.families()
        state.update { it.copy(families = result.value) }
    }

    fun importFont(uri: Uri) = run(Operation.IMPORTING) {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        droplet.importFont(uri, name)
    }

    fun removeFont(id: String) = edit { droplet.removeFont(id) }

    /** The pending change of a font name: names are typed a character at a time. */
    var renaming: Job? = null

    fun rename(id: String, name: String?) {
        renaming?.cancel()
        renaming = viewModelScope.launch {
            delay(RENAME_DELAY_MILLIS)
            withContext(NonCancellable) {
                try {
                    droplet.rename(id, name)
                } catch (error: Exception) {
                    state.update { it.copy(outcome = Outcome.Failed(error.message ?: error.toString(), null)) }
                }
            }
        }
    }

    fun select(slot: Slot, font: String?) = edit { droplet.select(slot, font) }

    fun customize(transform: (Config) -> Config) = edit { droplet.customize(transform) }

    fun configure(transform: (Config) -> Config) = edit { droplet.configure(transform) }

    fun reset() = edit { droplet.reset() }

    fun setPrivilegeChoice(choice: PrivilegeChoice) {
        droplet.settings.update { it.copy(privilege = choice) }
        refresh()
    }

    fun setBootHook(enabled: Boolean) = run(Operation.CONFIGURING_BOOT) {
        droplet.setBootHook(enabled)
        refreshNow()
    }

    fun setApplyAfterBoot(enabled: Boolean) = droplet.settings.update { it.copy(applyAfterBoot = enabled) }

    fun dismissOutcome() = state.update { it.copy(outcome = null) }

    companion object {
        const val RENAME_DELAY_MILLIS = 600L
    }
}
