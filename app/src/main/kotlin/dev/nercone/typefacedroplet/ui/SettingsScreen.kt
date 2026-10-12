package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.config.Settings
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.Engine
import dev.nercone.typefacedroplet.privilege.PrivilegeChoice

/** Settings of the application and of the parts of the configuration that are not fonts. */
object SettingsScreen {
    const val GUARD_MAXIMUM = 300
    const val GUARD_STEP = 10

    /** The restart targets with a switch of their own. */
    val TARGETS = listOf("com.android.systemui" to R.string.restart_systemui, Config.HOME to R.string.restart_home, Config.BACKGROUND to R.string.restart_background)

    /** The components of the downloadable font provider of Google Play services. */
    val PROVIDER = listOf("com.google.android.gms/.fonts.provider.FontsProvider", "com.google.android.gms/.fonts.update.UpdateSchedulerService")

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Content(state: UiState, config: Config?, settings: Settings.Values, model: AppViewModel) {
        val privileged = state.privilege?.privilege != null && state.operation == null
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Widgets.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Widgets.Header(stringResource(R.string.settings_privilege))
            Widgets.Group {
                PrivilegeChoice.entries.forEachIndexed { index, choice ->
                    Widgets.RadioItem(
                        index, PrivilegeChoice.entries.size,
                        headline = stringResource(
                            when (choice) {
                                PrivilegeChoice.AUTOMATIC -> R.string.settings_privilege_automatic
                                PrivilegeChoice.SHIZUKU -> R.string.settings_privilege_shizuku
                                PrivilegeChoice.ROOT -> R.string.settings_privilege_root
                            },
                        ),
                        supporting = state.privilege?.statuses?.let { statuses ->
                            when (choice) {
                                PrivilegeChoice.AUTOMATIC -> state.privilege.privilege?.let { stringResource(R.string.privilege_runs_as_root, Privileges.name(it.kind)) }
                                else -> statuses.firstOrNull { (kind, _) -> (kind == dev.nercone.typefacedroplet.privilege.Privilege.Kind.ROOT) == (choice == PrivilegeChoice.ROOT) }
                                    ?.let { (_, status) -> Privileges.describe(status) }
                            }
                        },
                        selected = settings.privilege == choice,
                    ) { model.setPrivilegeChoice(choice) }
                }
            }
            Text(stringResource(R.string.settings_privilege_help), style = MaterialTheme.typography.bodySmall, color = Widgets.secondary, modifier = Modifier.padding(horizontal = 16.dp))

            Widgets.Header(stringResource(R.string.settings_boot))
            val hook = state.status?.hook
            Widgets.Group {
                Widgets.SwitchItem(
                    0, 2,
                    headline = stringResource(R.string.settings_boot_hook),
                    supporting = when {
                        hook == null -> stringResource(R.string.settings_boot_hook_help)
                        hook.managers.isEmpty() -> stringResource(R.string.settings_boot_hook_unsupported)
                        hook.installed -> stringResource(R.string.boot_hook_installed, HomeScreen.managerNames(hook.managers))
                        else -> stringResource(R.string.settings_boot_hook_pending, HomeScreen.managerNames(hook.managers))
                    },
                    checked = settings.bootHook,
                    enabled = privileged,
                    onChange = model::setBootHook,
                )
                Widgets.SwitchItem(
                    1, 2,
                    headline = stringResource(R.string.settings_apply_after_boot),
                    supporting = stringResource(R.string.settings_apply_after_boot_help),
                    checked = settings.applyAfterBoot,
                    onChange = model::setApplyAfterBoot,
                )
            }
            if (config == null) return@Column

            Widgets.Header(stringResource(R.string.settings_restart))
            Widgets.Group {
                TARGETS.forEachIndexed { index, (target, title) ->
                    Widgets.SwitchItem(index, TARGETS.size + 1, stringResource(title), null, target in config.restart) { enabled ->
                        model.configure { it.copy(restart = if (enabled) it.restart + target else it.restart - target) }
                    }
                }
                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.restart_other), style = MaterialTheme.typography.bodyLarge)
                        Widgets.StringList(
                            items = config.restart.filter { target -> TARGETS.none { it.first == target } },
                            label = stringResource(R.string.settings_package),
                            validate = { Config.PACKAGE.matches(it) && it !in config.restart },
                            onChange = { list -> model.configure { current -> current.copy(restart = current.restart.filter { target -> TARGETS.any { it.first == target } } + list) } },
                        )
                    }
                }
            }
            Text(stringResource(R.string.settings_restart_help), style = MaterialTheme.typography.bodySmall, color = Widgets.secondary, modifier = Modifier.padding(horizontal = 16.dp))

            Widgets.Header(stringResource(R.string.settings_components))
            Widgets.Group {
                Widgets.SwitchItem(0, 2, stringResource(R.string.components_provider), stringResource(R.string.components_provider_help), PROVIDER.all { it in config.components }) { enabled ->
                    model.configure { it.copy(components = if (enabled) (it.components + PROVIDER).distinct() else it.components - PROVIDER.toSet()) }
                }
                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.components_other), style = MaterialTheme.typography.bodyLarge)
                        Widgets.StringList(
                            items = config.components - PROVIDER.toSet(),
                            label = stringResource(R.string.settings_component),
                            validate = { Config.isComponent(it) && it !in config.components },
                            onChange = { list -> model.configure { current -> current.copy(components = current.components.filter { it in PROVIDER } + list) } },
                        )
                    }
                }
            }
            Text(stringResource(R.string.settings_components_help), style = MaterialTheme.typography.bodySmall, color = Widgets.secondary, modifier = Modifier.padding(horizontal = 16.dp))

            Widgets.Header(stringResource(R.string.settings_guard))
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val slider = remember(config.guardSeconds) {
                        SliderState(config.guardSeconds.coerceAtMost(GUARD_MAXIMUM).toFloat(), GUARD_MAXIMUM / GUARD_STEP - 1, 0f..GUARD_MAXIMUM.toFloat())
                    }
                    val seconds = slider.value.toInt()
                    Text(
                        if (seconds == 0) stringResource(R.string.settings_guard_off) else stringResource(R.string.settings_guard_seconds, seconds),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Slider(
                        state = slider,
                        onValueChange = { slider.value = it },
                        onValueChangeFinished = { model.configure { it.copy(guardSeconds = slider.value.toInt()) } },
                    )
                    Text(stringResource(R.string.settings_guard_help), style = MaterialTheme.typography.bodySmall, color = Widgets.secondary)
                }
            }

            Widgets.Header(stringResource(R.string.settings_advanced))
            var confirm by remember { mutableStateOf(false) }
            Widgets.Group {
                Widgets.SwitchItem(0, 2, stringResource(R.string.settings_unknown_versions), stringResource(R.string.settings_unknown_versions_help), config.allowUnknownVersions) { enabled ->
                    model.configure { it.copy(allowUnknownVersions = enabled) }
                }
                Widgets.Item(1, 2, stringResource(R.string.reset), stringResource(R.string.reset_text), onClick = { confirm = true })
            }
            if (confirm) Widgets.Confirm(stringResource(R.string.reset_title), stringResource(R.string.reset_text), stringResource(R.string.reset), model::reset) { confirm = false }

            Widgets.Header(stringResource(R.string.settings_about))
            val context = LocalContext.current
            val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
            Widgets.Group {
                Widgets.Item(0, 3, stringResource(R.string.app_name), stringResource(R.string.settings_version, version))
                Widgets.Item(1, 3, stringResource(R.string.settings_store_title), stringResource(R.string.settings_store, Engine.STORE))
                Widgets.Item(2, 3, stringResource(R.string.settings_license_title), stringResource(R.string.settings_fonts_license))
            }
            Text(stringResource(R.string.settings_uninstall), style = MaterialTheme.typography.bodySmall, color = Widgets.secondary, modifier = Modifier.padding(16.dp))
        }
    }
}
