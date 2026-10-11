package dev.nercone.typefacedroplet.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nercone.typefacedroplet.DropletApplication
import dev.nercone.typefacedroplet.engine.ApplyReport
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.EngineException
import dev.nercone.typefacedroplet.engine.FamilyInfo
import dev.nercone.typefacedroplet.engine.LogLine
import dev.nercone.typefacedroplet.engine.StatusReport
import dev.nercone.typefacedroplet.privilege.PrivilegeChoice
import dev.nercone.typefacedroplet.privilege.PrivilegeSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An operation the user started, shown while it runs. */
enum class Operation { PREPARING, CHECKING, APPLYING, REVERTING, RESTARTING, IMPORTING, LOADING_FAMILIES, RESETTING }

/** A message for the user about the outcome of an operation. */
sealed interface Message {
    data class Applied(val report: ApplyReport) : Message
    data object Reverted : Message
    data object Restarting : Message
    data class Failed(val text: String) : Message
}

data class UiState(
    val ready: Boolean = false,
    val operation: Operation? = null,
    val privilege: PrivilegeSelection? = null,
    val status: StatusReport? = null,
    val families: List<FamilyInfo> = emptyList(),
    val message: Message? = null,
    val log: List<LogLine> = emptyList(),
    val privilegeChoice: PrivilegeChoice = PrivilegeChoice.AUTOMATIC,
    val applyAtBoot: Boolean = true,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val droplet = (application as DropletApplication).droplet
    val library = droplet.library.fonts
    val config = droplet.configs.config

    private val _state = MutableStateFlow(UiState(privilegeChoice = droplet.settings.privilege, applyAtBoot = droplet.settings.applyAtBoot))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        run(Operation.PREPARING) {
            droplet.initialize()
            _state.update { it.copy(ready = true) }
            refreshNow()
        }
    }

    fun run(operation: Operation, block: suspend () -> Unit) {
        if (_state.value.operation != null && operation != Operation.PREPARING) return
        _state.update { it.copy(operation = operation) }
        viewModelScope.launch {
            try {
                block()
            } catch (error: EngineException) {
                _state.update { it.copy(message = Message.Failed(error.message.orEmpty()), log = error.log) }
            } catch (error: Exception) {
                _state.update { it.copy(message = Message.Failed(error.message ?: error.toString())) }
            } finally {
                _state.update { it.copy(operation = null) }
            }
        }
    }

    suspend fun refreshNow() {
        val selection = droplet.selectPrivilege()
        _state.update { it.copy(privilege = selection) }
        if (selection.privilege != null) {
            val status = droplet.status().value
            _state.update { it.copy(status = status) }
        } else {
            _state.update { it.copy(status = null) }
        }
    }

    fun refresh() = run(Operation.CHECKING) { refreshNow() }

    fun apply() = run(Operation.APPLYING) {
        val result = droplet.apply(boot = false)
        _state.update { it.copy(message = Message.Applied(result.value), log = result.log) }
        refreshNow()
    }

    fun revert() = run(Operation.REVERTING) {
        val result = droplet.revert()
        _state.update { it.copy(message = Message.Reverted, log = result.log) }
        refreshNow()
    }

    fun softRestart() = run(Operation.RESTARTING) {
        val result = droplet.softRestart()
        _state.update { it.copy(message = Message.Restarting, log = result.log) }
    }

    fun requestShizukuPermission() = run(Operation.CHECKING) {
        droplet.shizuku.requestPermission()
        refreshNow()
    }

    fun loadFamilies() = run(Operation.LOADING_FAMILIES) {
        val result = droplet.families()
        _state.update { it.copy(families = result.value) }
    }

    fun importFont(uri: Uri) = run(Operation.IMPORTING) {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        droplet.importFont(uri, name)
    }

    fun removeFont(id: String) = run(Operation.IMPORTING) { droplet.removeFont(id) }

    fun resetConfig() = run(Operation.RESETTING) { droplet.configs.save(droplet.recommended()) }

    fun updateConfig(transform: (Config) -> Config) {
        viewModelScope.launch { droplet.configs.update(transform) }
    }

    fun setPrivilegeChoice(choice: PrivilegeChoice) {
        droplet.settings.privilege = choice
        _state.update { it.copy(privilegeChoice = choice) }
        refresh()
    }

    fun setApplyAtBoot(enabled: Boolean) {
        droplet.settings.applyAtBoot = enabled
        _state.update { it.copy(applyAtBoot = enabled) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
