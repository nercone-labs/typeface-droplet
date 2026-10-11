package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.Engine
import dev.nercone.typefacedroplet.privilege.PrivilegeChoice

/** Settings of the application and of the parts of the configuration that are not fonts. */
object SettingsScreen {
    @Composable
    fun Content(state: UiState, config: Config?, model: AppViewModel) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Widgets.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(Widgets.SPACING),
        ) {
            Widgets.Section(stringResource(R.string.settings_privilege)) {
                PrivilegeChoice.entries.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = choice == state.privilegeChoice, role = Role.RadioButton) { model.setPrivilegeChoice(choice) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == state.privilegeChoice, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                stringResource(
                                    when (choice) {
                                        PrivilegeChoice.AUTOMATIC -> R.string.settings_privilege_automatic
                                        PrivilegeChoice.SHIZUKU -> R.string.settings_privilege_shizuku
                                        PrivilegeChoice.ROOT -> R.string.settings_privilege_root
                                    },
                                ),
                            )
                        }
                    }
                }
                Text(stringResource(R.string.settings_privilege_help), style = MaterialTheme.typography.bodySmall)
            }
            Widgets.Section(stringResource(R.string.settings_boot)) {
                Toggle(stringResource(R.string.settings_apply_at_boot), stringResource(R.string.settings_apply_at_boot_help), state.applyAtBoot, model::setApplyAtBoot)
            }
            if (config == null) return@Column
            Widgets.Section(stringResource(R.string.settings_guard)) {
                var seconds by remember(config.guardSeconds) { mutableFloatStateOf(config.guardSeconds.toFloat()) }
                Text(
                    if (seconds.toInt() == 0) stringResource(R.string.settings_guard_off) else stringResource(R.string.settings_guard_seconds, seconds.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = seconds,
                    onValueChange = { seconds = it },
                    onValueChangeFinished = { model.updateConfig { it.copy(guardSeconds = seconds.toInt()) } },
                    valueRange = 0f..GUARD_MAXIMUM.toFloat(),
                    steps = GUARD_MAXIMUM / GUARD_STEP - 1,
                )
                Text(stringResource(R.string.settings_guard_help), style = MaterialTheme.typography.bodySmall)
            }
            Widgets.Section(stringResource(R.string.settings_restart)) {
                Text(stringResource(R.string.settings_restart_help), style = MaterialTheme.typography.bodySmall)
                val home = stringResource(R.string.settings_home)
                Widgets.StringList(
                    items = config.restart,
                    label = stringResource(R.string.settings_package),
                    describe = { if (it == Config.HOME) home else it },
                    validate = { it == Config.HOME || PACKAGE.matches(it) },
                    onChange = { list -> model.updateConfig { it.copy(restart = list) } },
                )
            }
            Widgets.Section(stringResource(R.string.settings_components)) {
                Text(stringResource(R.string.settings_components_help), style = MaterialTheme.typography.bodySmall)
                Widgets.StringList(
                    items = config.components,
                    label = stringResource(R.string.settings_component),
                    describe = { it },
                    validate = { COMPONENT.matches(it) },
                    onChange = { list -> model.updateConfig { it.copy(components = list) } },
                )
            }
            Widgets.Section(stringResource(R.string.settings_advanced)) {
                Toggle(
                    stringResource(R.string.settings_unknown_versions),
                    stringResource(R.string.settings_unknown_versions_help),
                    config.allowUnknownVersions,
                ) { enabled -> model.updateConfig { it.copy(allowUnknownVersions = enabled) } }
                var confirm by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.settings_reset)) }
                if (confirm) {
                    Widgets.Confirm(
                        stringResource(R.string.settings_reset),
                        stringResource(R.string.settings_reset_text),
                        stringResource(R.string.settings_reset_confirm),
                        model::resetConfig,
                    ) { confirm = false }
                }
            }
            Widgets.Section(stringResource(R.string.settings_about)) {
                Text(stringResource(R.string.settings_store, Engine.STORE), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.settings_fonts_license), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.settings_uninstall), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    fun Toggle(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    const val GUARD_MAXIMUM = 300
    const val GUARD_STEP = 10
    val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    val COMPONENT = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+/[A-Za-z0-9_.$]+")
}
