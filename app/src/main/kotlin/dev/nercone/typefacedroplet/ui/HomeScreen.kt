package dev.nercone.typefacedroplet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.config.Category
import dev.nercone.typefacedroplet.config.Selection
import dev.nercone.typefacedroplet.config.Settings
import dev.nercone.typefacedroplet.config.Slot
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.FaceStyle
import dev.nercone.typefacedroplet.engine.GuardState
import dev.nercone.typefacedroplet.engine.LogLevel
import dev.nercone.typefacedroplet.engine.LogLine
import dev.nercone.typefacedroplet.engine.ReloadOutcome
import dev.nercone.typefacedroplet.engine.RootManager
import dev.nercone.typefacedroplet.library.LibraryFont
import dev.nercone.typefacedroplet.privilege.Privilege
import dev.nercone.typefacedroplet.privilege.PrivilegeSelection
import dev.nercone.typefacedroplet.privilege.PrivilegeStatus

/** The home screen: the state of the system, the actions, the outcome and a preview of the chosen fonts. */
object HomeScreen {
    /** The state of the system, as the hero of the screen tells it. */
    enum class Condition { PREPARING, NO_PRIVILEGE, CHECKING, TRIPPED, ACTIVE, ORIGINAL }

    fun condition(state: UiState): Condition = when {
        !state.ready -> Condition.PREPARING
        state.privilege != null && state.privilege.privilege == null -> Condition.NO_PRIVILEGE
        state.status == null -> Condition.CHECKING
        state.status.guard is GuardState.Tripped && !state.status.applied -> Condition.TRIPPED
        state.status.applied -> Condition.ACTIVE
        else -> Condition.ORIGINAL
    }

    @Composable
    fun Content(state: UiState, config: Config?, fonts: List<LibraryFont>, settings: Settings.Values, model: AppViewModel, onNavigate: (DropletApp.Destination) -> Unit) {
        val condition = condition(state)
        val selection = config?.let { Selection.of(it, fonts) } ?: Selection()
        var log by remember { mutableStateOf(false) }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Widgets.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(Widgets.SPACING),
        ) {
            Hero(condition, state, settings, selection, fonts, model)
            if (condition == Condition.NO_PRIVILEGE) state.privilege?.let { Setup(it, onGrant = model::grantShizuku, onRecheck = model::refresh) }
            Actions(state, condition, config != null, model)
            AnimatedVisibility(state.outcome != null && !(state.outcome as? Outcome.Failed)?.restricted.orFalse()) {
                state.outcome?.let { Outcome(it, onSoftRestart = model::softRestart, onLog = { log = true }, onDismiss = model::dismissOutcome) }
            }
            Specimen(selection, fonts, model) { onNavigate(DropletApp.Destination.FONTS) }
            Details(state, settings, onSettings = { onNavigate(DropletApp.Destination.SETTINGS) }, onLog = { log = true })
        }
        if (log) Log(state.log) { log = false }
    }

    fun Boolean?.orFalse(): Boolean = this == true

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun Hero(condition: Condition, state: UiState, settings: Settings.Values, selection: Selection, fonts: List<LibraryFont>, model: AppViewModel) {
        val active = condition == Condition.ACTIVE
        val container by animateColorAsState(
            when (condition) {
                Condition.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
                Condition.TRIPPED, Condition.NO_PRIVILEGE -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            },
            MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "container",
        )
        val rotation by animateFloatAsState(if (active) 45f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "rotation")
        val sans = fonts.firstOrNull { it.id == selection.font(Slot(Category.SANS, FaceStyle.NORMAL)) }
        val family = Previews.family(model.droplet.library, sans, italic = false)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = container) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    if (state.operation != null || condition == Condition.PREPARING || condition == Condition.CHECKING) {
                        ContainedLoadingIndicator(Modifier.size(88.dp))
                    } else {
                        Box(
                            Modifier.size(88.dp).rotate(rotation).clip(MaterialShapes.Cookie9Sided.toShape()).background(
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            ),
                        )
                        Text(
                            "Aa",
                            fontSize = 34.sp,
                            fontFamily = family ?: FontFamily.Default,
                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface,
                        )
                    }
                }
                Text(stringResource(title(condition)), style = MaterialTheme.typography.headlineSmall)
                Text(description(condition, state, settings), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    fun title(condition: Condition): Int = when (condition) {
        Condition.PREPARING -> R.string.hero_preparing
        Condition.NO_PRIVILEGE -> R.string.hero_no_privilege
        Condition.CHECKING -> R.string.hero_checking
        Condition.TRIPPED -> R.string.hero_tripped
        Condition.ACTIVE -> R.string.hero_active
        Condition.ORIGINAL -> R.string.hero_original
    }

    @Composable
    fun description(condition: Condition, state: UiState, settings: Settings.Values): String = when (condition) {
        Condition.PREPARING -> stringResource(R.string.hero_preparing_text)
        Condition.NO_PRIVILEGE -> stringResource(R.string.hero_no_privilege_text)
        Condition.CHECKING -> stringResource(R.string.hero_checking_text)
        Condition.TRIPPED -> stringResource(R.string.hero_tripped_text, (state.status?.guard as? GuardState.Tripped)?.reason.orEmpty())
        Condition.ACTIVE -> {
            val hook = state.status?.hook
            when {
                hook?.installed == true && settings.bootHook -> stringResource(R.string.hero_active_hook, managerNames(hook.managers))
                settings.applyAfterBoot -> stringResource(R.string.hero_active_application)
                else -> stringResource(R.string.hero_active_once)
            }
        }
        Condition.ORIGINAL -> stringResource(R.string.hero_original_text)
    }

    @Composable
    fun managerNames(managers: List<RootManager>): String = managers.map { manager ->
        stringResource(
            when (manager) {
                RootManager.MAGISK -> R.string.manager_magisk
                RootManager.KERNEL_SU -> R.string.manager_kernelsu
                RootManager.APATCH -> R.string.manager_apatch
            },
        )
    }.joinToString(" / ")

    /** Guides the user to a privilege: what each candidate says, and what to do. */
    @Composable
    fun Setup(selection: PrivilegeSelection, onGrant: () -> Unit, onRecheck: () -> Unit) {
        Widgets.Header(stringResource(R.string.setup_title))
        Widgets.Group {
            selection.statuses.forEachIndexed { index, (kind, status) ->
                Widgets.Item(
                    index = index,
                    count = selection.statuses.size,
                    headline = Privileges.name(kind),
                    supporting = Privileges.describe(status),
                    icon = Icons.Default.AdminPanelSettings,
                    trailing = if (status == PrivilegeStatus.PermissionRequired) {
                        { FilledTonalButton(onClick = onGrant) { Text(stringResource(R.string.grant)) } }
                    } else {
                        null
                    },
                )
            }
        }
        Text(stringResource(R.string.setup_help), style = MaterialTheme.typography.bodyMedium, color = Widgets.secondary)
        TextButton(onClick = onRecheck) { Text(stringResource(R.string.check_again)) }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun Actions(state: UiState, condition: Condition, configured: Boolean, model: AppViewModel) {
        val idle = state.ready && state.operation == null && state.privilege?.privilege != null
        // Restoring also re-enables components and forgets the saved configuration, which stay after a reboot or a tripped guard.
        val restorable = state.status?.let { it.applied || it.saved || it.components.isNotEmpty() } == true
        val height = ButtonDefaults.MediumContainerHeight
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = model::apply,
                enabled = idle && configured,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.contentPaddingFor(height),
                modifier = Modifier.weight(1f).heightIn(min = height),
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
                Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(height)))
                Text(stringResource(if (condition == Condition.ACTIVE) R.string.apply_again else R.string.apply), style = ButtonDefaults.textStyleFor(height))
            }
            FilledTonalButton(
                onClick = model::revert,
                enabled = idle && restorable,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.contentPaddingFor(height),
                modifier = Modifier.weight(1f).heightIn(min = height),
            ) {
                Icon(Icons.Default.Restore, null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
                Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(height)))
                Text(stringResource(R.string.revert), style = ButtonDefaults.textStyleFor(height))
            }
        }
    }

    @Composable
    fun Outcome(outcome: Outcome, onSoftRestart: () -> Unit, onLog: () -> Unit, onDismiss: () -> Unit) {
        val dismiss: @Composable () -> Unit = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } }
        val logButton: @Composable () -> Unit = { TextButton(onClick = onLog) { Text(stringResource(R.string.show_log)) } }
        when (outcome) {
            is Outcome.Applied -> {
                val report = outcome.applied.report
                val restarted = report.restarted.isNotEmpty()
                val (tone, title, text) = when (report.reload) {
                    ReloadOutcome.SERVED, ReloadOutcome.AT_START -> Triple(
                        Widgets.Tone.INFORMATION,
                        stringResource(R.string.outcome_applied),
                        stringResource(if (restarted) R.string.outcome_applied_text_restarted else R.string.outcome_applied_text, report.replaced),
                    )
                    ReloadOutcome.IGNORED -> Triple(Widgets.Tone.WARNING, stringResource(R.string.outcome_ignored), stringResource(R.string.outcome_ignored_text))
                    ReloadOutcome.UNCHANGED -> Triple(Widgets.Tone.WARNING, stringResource(R.string.outcome_unchanged), stringResource(R.string.outcome_unchanged_text))
                }
                val hookFailure = outcome.applied.hookFailure?.let { stringResource(R.string.outcome_hook_failed, it) }
                Widgets.Notice(tone, title, listOfNotNull(text, hookFailure).joinToString("\n\n")) {
                    logButton()
                    if (report.reload == ReloadOutcome.IGNORED) FilledTonalButton(onClick = onSoftRestart) { Text(stringResource(R.string.soft_restart)) }
                    dismiss()
                }
            }
            Outcome.Reverted -> Widgets.Notice(Widgets.Tone.INFORMATION, stringResource(R.string.outcome_reverted), stringResource(R.string.outcome_reverted_text)) {
                logButton()
                dismiss()
            }
            Outcome.Restarting -> Widgets.Notice(Widgets.Tone.INFORMATION, stringResource(R.string.outcome_restarting), stringResource(R.string.outcome_restarting_text)) { dismiss() }
            is Outcome.Failed -> Widgets.Notice(Widgets.Tone.ERROR, stringResource(R.string.outcome_failed), outcome.text) {
                logButton()
                dismiss()
            }
        }
    }

    /** A sample of every category in the chosen fonts. */
    @Composable
    fun Specimen(selection: Selection, fonts: List<LibraryFont>, model: AppViewModel, onClick: () -> Unit) {
        Surface(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).clickable(onClick = onClick), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.specimen_title), style = MaterialTheme.typography.titleMedium)
                Category.entries.forEach { category ->
                    val upright = fonts.firstOrNull { it.id == selection.font(Slot(category, FaceStyle.NORMAL)) }
                    val italic = fonts.firstOrNull { it.id == selection.font(Slot(category, FaceStyle.ITALIC)) }
                    val family = Previews.family(model.droplet.library, upright, italic = false)
                    val italicFamily = Previews.family(model.droplet.library, italic, italic = true)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${stringResource(FontsScreen.categoryName(category))} · ${upright?.label ?: stringResource(R.string.font_system)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Widgets.secondary,
                        )
                        Text(stringResource(R.string.specimen_text), fontSize = 20.sp, fontFamily = family ?: FontsScreen.systemFamily(category), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.specimen_italic),
                            fontSize = 16.sp,
                            fontStyle = FontStyle.Italic,
                            fontFamily = italicFamily ?: family ?: FontsScreen.systemFamily(category),
                            color = Widgets.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun Details(state: UiState, settings: Settings.Values, onSettings: () -> Unit, onLog: () -> Unit) {
        val status = state.status
        val items = buildList<@Composable (Int, Int) -> Unit> {
            add { index, count ->
                val selected = state.privilege?.privilege
                Widgets.Item(
                    index, count,
                    headline = stringResource(R.string.detail_privilege),
                    supporting = selected?.let { stringResource(R.string.privilege_runs_as_root, Privileges.name(it.kind)) } ?: stringResource(R.string.privilege_none_short),
                    icon = Icons.Default.AdminPanelSettings,
                    onClick = onSettings,
                )
            }
            add { index, count ->
                val hook = status?.hook
                Widgets.Item(
                    index, count,
                    headline = stringResource(R.string.detail_boot),
                    supporting = when {
                        hook == null -> stringResource(R.string.unknown)
                        settings.bootHook && hook.installed -> stringResource(R.string.boot_hook_installed, managerNames(hook.managers))
                        settings.bootHook && hook.managers.isEmpty() && settings.applyAfterBoot -> stringResource(R.string.boot_no_manager_application)
                        settings.applyAfterBoot -> stringResource(R.string.boot_application)
                        else -> stringResource(R.string.boot_off)
                    },
                    icon = Icons.Default.PowerSettingsNew,
                    onClick = onSettings,
                )
            }
            when (val guard = status?.guard) {
                is GuardState.Watching -> add { index, count ->
                    Widgets.Item(index, count, stringResource(R.string.detail_guard), stringResource(R.string.guard_watching), Icons.Default.Shield)
                }
                is GuardState.Tripped -> add { index, count ->
                    Widgets.Item(index, count, stringResource(R.string.detail_guard), stringResource(R.string.guard_tripped, guard.reason), Icons.Default.Shield)
                }
                else -> {}
            }
            if (status != null && status.components.isNotEmpty()) add { index, count ->
                Widgets.Item(
                    index, count,
                    headline = stringResource(R.string.detail_components),
                    supporting = stringResource(R.string.components_disabled, status.components.map { it.component }.distinct().size),
                    icon = Icons.Default.Block,
                    onClick = onSettings,
                )
            }
            if (state.log.isNotEmpty()) add { index, count ->
                Widgets.Item(index, count, stringResource(R.string.log), stringResource(R.string.log_lines, state.log.size), Icons.AutoMirrored.Filled.Notes, onClick = onLog)
            }
        }
        Widgets.Group { items.forEachIndexed { index, item -> item(index, items.size) } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Log(lines: List<LogLine>, onDismiss: () -> Unit) {
        ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Text(stringResource(R.string.log), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (lines.isEmpty()) Text(stringResource(R.string.log_empty), modifier = Modifier.padding(24.dp))
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
                items(lines) { line ->
                    Text(
                        line.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (line.level) {
                            LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                            LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** Names and descriptions of privileges, shared by the home and settings screens. */
object Privileges {
    @Composable
    fun name(kind: Privilege.Kind): String = stringResource(
        when (kind) {
            Privilege.Kind.ROOT -> R.string.privilege_root
            Privilege.Kind.SHIZUKU -> R.string.privilege_shizuku
            Privilege.Kind.SUI -> R.string.privilege_sui
        },
    )

    @Composable
    fun describe(status: PrivilegeStatus): String = when (status) {
        is PrivilegeStatus.Available -> stringResource(R.string.privilege_available)
        is PrivilegeStatus.InsufficientUser ->
            if (status.uid == Privilege.SHELL_UID) stringResource(R.string.privilege_shizuku_adb) else stringResource(R.string.privilege_wrong_user, status.uid)
        PrivilegeStatus.PermissionRequired -> stringResource(R.string.privilege_permission_required)
        is PrivilegeStatus.Unavailable -> stringResource(
            when (status.reason) {
                PrivilegeStatus.Reason.NOT_INSTALLED -> R.string.privilege_not_installed
                PrivilegeStatus.Reason.NOT_RUNNING -> R.string.privilege_not_running
                PrivilegeStatus.Reason.DENIED -> R.string.privilege_denied
                PrivilegeStatus.Reason.OUTDATED -> R.string.privilege_outdated
            },
        )
    }
}
