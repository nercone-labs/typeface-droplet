package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.config.Settings
import dev.nercone.typefacedroplet.engine.BootHook
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.Engine
import dev.nercone.typefacedroplet.ui.Notices.notices

/**
 * The settings of the application and of the parts of the configuration that are not fonts: a
 * list of pages, each of one concern, so that no page is a wall of choices. The frame shows a
 * page in place of the list, with a way back (`DropletApp`).
 */
object SettingsScreen {
    /** A page of the settings. */
    enum class Page(val title: Int) {
        APPLYING(R.string.settings_applying),
        COMPONENTS(R.string.settings_components),
        BOOT(R.string.settings_boot),
        ADVANCED(R.string.settings_advanced),
        PRIVILEGE(R.string.settings_privilege),
        ABOUT(R.string.settings_about),
    }

    /** Where the source of the application is published. */
    const val SOURCE = "https://github.com/nercone-labs/typeface-droplet"

    /** The restart targets with a switch of their own. */
    val TARGETS = listOf("com.android.systemui" to R.string.restart_systemui, Config.HOME to R.string.restart_home, Config.BACKGROUND to R.string.restart_background)

    /** The components of the downloadable font provider of Google Play services. */
    val PROVIDER = listOf("com.google.android.gms/.fonts.provider.FontsProvider", "com.google.android.gms/.fonts.update.UpdateSchedulerService")

    /** The guard durations offered, in seconds; a value set otherwise (by an earlier version) is offered too. */
    val GUARD_SECONDS = listOf(0, 30, 60, 120, 300)

    @Composable
    fun Content(data: ScreenData, model: AppViewModel, page: Page?, onOpen: (Page) -> Unit) {
        Widgets.Screen {
            notices(data, model)
            when (page) {
                null -> item(key = "pages") { Pages(data, onOpen) }
                Page.PRIVILEGE -> item(key = "privilege") {
                    Privileges.Choices(data.state.privilege, data.settings.privilege, enabled = data.state.operation == null, onSelect = model::setPrivilegeChoice)
                }
                Page.BOOT -> item(key = "boot") { Boot(data, model) }
                Page.APPLYING -> item(key = "applying") { Configured(data) { config -> Applying(config, model) } }
                Page.COMPONENTS -> item(key = "components") { Configured(data) { config -> Components(config, model) } }
                Page.ADVANCED -> item(key = "advanced") { Configured(data) { config -> Advanced(config, data.settings, model) } }
                Page.ABOUT -> item(key = "about") { About() }
            }
        }
    }

    /** Shows `content` with the configuration, or that it is not loaded (yet, or because loading failed). */
    @Composable
    fun Configured(data: ScreenData, content: @Composable (Config) -> Unit) {
        val config = data.config
        if (config == null) Widgets.Help(stringResource(if (data.state.ready) R.string.config_unavailable else R.string.loading)) else content(config)
    }

    /** The list of pages, each with a summary of what it is set to. */
    @Composable
    fun Pages(data: ScreenData, onOpen: (Page) -> Unit) {
        val settings = data.settings
        val config = data.config
        val pages = listOf(
            Triple(Page.APPLYING, Icons.Default.RestartAlt, config?.let { applyingSummary(it) } ?: stringResource(R.string.loading)),
            Triple(Page.COMPONENTS, Icons.Default.Block, config?.let { pluralStringResource(R.plurals.components_count, it.components.size, it.components.size) } ?: stringResource(R.string.loading)),
            Triple(Page.BOOT, Icons.Default.PowerSettingsNew, bootSummary(data.state.status?.hook, settings)),
            Triple(Page.ADVANCED, Icons.Default.Tune, stringResource(if (settings.customized) R.string.customized_short else R.string.recommended_short)),
            Triple(Page.PRIVILEGE, Icons.Default.Security, Privileges.summary(settings.privilege, data.state.privilege)),
            Triple(Page.ABOUT, Icons.Default.Info, stringResource(R.string.settings_version, version())),
        )
        Widgets.Group {
            pages.forEachIndexed { index, (page, icon, summary) ->
                Widgets.Item(index, pages.size, stringResource(page.title), summary, icon, onClick = { onOpen(page) })
            }
        }
    }

    /** What happens at boot, in a line: by the root manager, by this application, or nothing. */
    @Composable
    fun bootSummary(hook: BootHook?, settings: Settings.Values): String = when {
        settings.bootHook && hook?.installed == true -> stringResource(R.string.boot_hook_installed, Labels.rootManagers(hook.managers))
        settings.bootHook && hook != null && hook.managers.isEmpty() && settings.applyAfterBoot -> stringResource(R.string.boot_no_manager_application)
        settings.applyAfterBoot -> stringResource(R.string.boot_application)
        settings.bootHook && hook == null -> stringResource(R.string.unknown)
        else -> stringResource(R.string.boot_off)
    }

    @Composable
    fun applyingSummary(config: Config): String {
        val restarts = config.restart.size
        return pluralStringResource(R.plurals.restart_count, restarts, restarts) + " · " + stringResource(R.string.settings_guard) + " " + Labels.guard(config.guardSeconds)
    }

    @Composable
    fun version(): String {
        val context = LocalContext.current
        return remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    }

    @Composable
    fun Boot(data: ScreenData, model: AppViewModel) {
        val hook = data.state.status?.hook
        val settings = data.settings
        Widgets.Section(null, stringResource(R.string.settings_boot_help)) {
            Widgets.SwitchItem(
                0, 2,
                headline = stringResource(R.string.settings_boot_hook),
                supporting = when {
                    hook == null -> stringResource(R.string.settings_boot_hook_help)
                    hook.managers.isEmpty() -> stringResource(R.string.settings_boot_hook_unsupported)
                    hook.installed -> stringResource(R.string.boot_hook_installed, Labels.rootManagers(hook.managers))
                    else -> stringResource(R.string.settings_boot_hook_pending, Labels.rootManagers(hook.managers))
                },
                checked = settings.bootHook,
                // Installing or removing the scripts needs root.
                enabled = data.state.idle,
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
    }

    @Composable
    fun Applying(config: Config, model: AppViewModel) {
        Column(verticalArrangement = Arrangement.spacedBy(Widgets.SPACING)) {
            Widgets.Section(stringResource(R.string.settings_restart), stringResource(R.string.settings_restart_help)) {
                val count = TARGETS.size + 1
                TARGETS.forEachIndexed { index, (target, title) ->
                    Widgets.SwitchItem(index, count, stringResource(title), null, target in config.restart) { enabled ->
                        model.configure { it.copy(restart = if (enabled) (it.restart + target).distinct() else it.restart - target) }
                    }
                }
                Widgets.StringListItem(
                    TARGETS.size, count,
                    headline = stringResource(R.string.restart_other),
                    items = config.restart.filter { target -> TARGETS.none { it.first == target } },
                    label = stringResource(R.string.settings_package),
                    help = stringResource(R.string.restart_other_help),
                    validate = { Config.PACKAGE.matches(it) && it !in config.restart },
                    onAdd = { target -> model.configure { it.copy(restart = (it.restart + target).distinct()) } },
                    onRemove = { target -> model.configure { it.copy(restart = it.restart - target) } },
                )
            }
            Widgets.Section(stringResource(R.string.settings_guard), null) {
                val options = (GUARD_SECONDS + config.guardSeconds).distinct().sorted()
                Widgets.ChoiceItem(
                    0, 1,
                    headline = stringResource(R.string.settings_guard_duration),
                    options = options,
                    selected = config.guardSeconds,
                    describe = { Labels.guard(it) },
                    onSelect = { seconds -> model.configure { it.copy(guardSeconds = seconds) } },
                    description = stringResource(R.string.settings_guard_help),
                    help = { seconds -> if (seconds == 0) stringResource(R.string.settings_guard_off_help) else null },
                )
            }
        }
    }

    @Composable
    fun Components(config: Config, model: AppViewModel) {
        Widgets.Section(null, stringResource(R.string.settings_components_help)) {
            Widgets.SwitchItem(0, 2, stringResource(R.string.components_provider), stringResource(R.string.components_provider_help), PROVIDER.all { it in config.components }) { enabled ->
                model.configure { it.copy(components = if (enabled) (it.components + PROVIDER).distinct() else it.components - PROVIDER.toSet()) }
            }
            Widgets.StringListItem(
                1, 2,
                headline = stringResource(R.string.components_other),
                items = config.components - PROVIDER.toSet(),
                label = stringResource(R.string.settings_component),
                help = stringResource(R.string.components_other_help),
                validate = { Config.isComponent(it) && it !in config.components },
                onAdd = { component -> model.configure { it.copy(components = (it.components + component).distinct()) } },
                onRemove = { component -> model.configure { it.copy(components = it.components - component) } },
            )
        }
    }

    @Composable
    fun Advanced(config: Config, settings: Settings.Values, model: AppViewModel) {
        var confirm by rememberSaveable { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(Widgets.SPACING)) {
            Widgets.Section(stringResource(R.string.settings_rules), stringResource(if (settings.customized) R.string.customized_text else R.string.recommended_text)) {
                Widgets.Item(0, 1, stringResource(R.string.reset), stringResource(R.string.reset_text), Icons.Default.RestartAlt, onClick = { confirm = true })
            }
            Widgets.Section(stringResource(R.string.settings_documents), null) {
                Widgets.SwitchItem(0, 1, stringResource(R.string.settings_unknown_versions), stringResource(R.string.settings_unknown_versions_help), config.allowUnknownVersions) { enabled ->
                    model.configure { it.copy(allowUnknownVersions = enabled) }
                }
            }
        }
        if (confirm) Widgets.Confirm(stringResource(R.string.reset_title), stringResource(R.string.reset_text), stringResource(R.string.reset_confirm), model::reset) { confirm = false }
    }

    @Composable
    fun About() {
        val uri = LocalUriHandler.current
        Column(verticalArrangement = Arrangement.spacedBy(Widgets.SPACING)) {
            Widgets.Identity(stringResource(R.string.settings_version, version()))
            Widgets.Section(null, stringResource(R.string.settings_uninstall)) {
                Widgets.Item(0, 3, stringResource(R.string.settings_store_title), stringResource(R.string.settings_store, Engine.STORE), Icons.Default.Folder)
                Widgets.Item(1, 3, stringResource(R.string.settings_license_title), stringResource(R.string.settings_fonts_license), Icons.Default.FontDownload)
                Widgets.Item(2, 3, stringResource(R.string.settings_source_title), SOURCE, Icons.Default.Code, onClick = { Widgets.open(uri, SOURCE) })
            }
        }
    }
}
