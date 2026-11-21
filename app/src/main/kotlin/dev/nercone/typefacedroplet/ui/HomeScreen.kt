package dev.nercone.typefacedroplet.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.config.Category
import dev.nercone.typefacedroplet.config.Selection
import dev.nercone.typefacedroplet.config.Slot
import dev.nercone.typefacedroplet.engine.FaceStyle
import dev.nercone.typefacedroplet.engine.GuardState
import dev.nercone.typefacedroplet.library.FontLibrary
import dev.nercone.typefacedroplet.library.LibraryFont
import dev.nercone.typefacedroplet.privilege.PrivilegeSelection
import dev.nercone.typefacedroplet.privilege.PrivilegeStatus
import dev.nercone.typefacedroplet.ui.Notices.notices

/**
 * The home screen: the verdict with the actions, the details, and a preview of the chosen fonts.
 * In a wide window the preview stands beside the verdict.
 */
object HomeScreen {
    /** The state of the system, as the verdict tells it. */
    enum class Condition { PREPARING, NO_PRIVILEGE, RESTRICTED, UNREADABLE, CHECKING, TRIPPED, ACTIVE, ORIGINAL }

    fun condition(state: UiState): Condition = when {
        !state.ready -> Condition.PREPARING
        state.privilege != null && state.privilege.privilege == null -> Condition.NO_PRIVILEGE
        state.statusError?.restricted == true -> Condition.RESTRICTED
        state.statusError != null -> Condition.UNREADABLE
        state.status == null -> Condition.CHECKING
        state.status.applied -> Condition.ACTIVE
        state.status.guard is GuardState.Tripped -> Condition.TRIPPED
        else -> Condition.ORIGINAL
    }

    @Composable
    fun Content(data: ScreenData, model: AppViewModel, navigator: DropletApp.Navigator) {
        val state = data.state
        val condition = condition(state)
        val selection = data.config?.let { Selection.of(it, data.fonts) } ?: Selection()
        val library = model.droplet.library
        Widgets.Screen(columnsWidth = 1100.dp) { width ->
            notices(data, model)
            val main: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(Widgets.SPACING)) {
                    Verdict(condition, data, selection, library, model)
                    if (condition == Condition.NO_PRIVILEGE) state.privilege?.let { Setup(it, onGrant = model::grantShizuku, onRecheck = model::recheck) }
                    Details(data, model) { page -> navigator.open(DropletApp.Destination.SETTINGS, page) }
                }
            }
            val side: @Composable () -> Unit = {
                Specimen(selection, data.fonts, library) { navigator.open(DropletApp.Destination.FONTS, null) }
            }
            if (width >= Widgets.TWO_COLUMNS) {
                item(key = "columns") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Widgets.SCREEN_PADDING)) {
                        Box(Modifier.weight(1f)) { main() }
                        Box(Modifier.weight(1f)) { side() }
                    }
                }
            } else {
                item(key = "main") { main() }
                item(key = "side") { side() }
            }
        }
    }

    /** The verdict: which fonts the system uses, what happens at boot, and the actions. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun Verdict(condition: Condition, data: ScreenData, selection: Selection, library: FontLibrary, model: AppViewModel) {
        val state = data.state
        val active = condition == Condition.ACTIVE
        val failing = condition == Condition.TRIPPED || condition == Condition.NO_PRIVILEGE || condition == Condition.RESTRICTED || condition == Condition.UNREADABLE
        val container by animateColorAsState(
            when {
                active -> MaterialTheme.colorScheme.primaryContainer
                failing -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            },
            MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "container",
        )
        val rotation by animateFloatAsState(if (active) 45f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "rotation")
        val sans = data.fonts.firstOrNull { it.id == selection.font(Slot(Category.SANS, FaceStyle.NORMAL)) }
        val family = Previews.family(library, sans, italic = false)
        val busy = state.operation != null || condition == Condition.PREPARING || condition == Condition.CHECKING
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = container) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                        if (busy) {
                            ContainedLoadingIndicator(Modifier.size(72.dp))
                        } else {
                            Box(
                                Modifier.size(72.dp).rotate(rotation).clip(MaterialShapes.Cookie9Sided.toShape()).background(
                                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                ),
                            )
                            Text(
                                "Aa",
                                fontSize = 28.sp,
                                fontFamily = family ?: FontFamily.Default,
                                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface,
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(title(condition)), style = MaterialTheme.typography.headlineSmall)
                        Text(state.operation?.let { Labels.operation(it) } ?: description(condition, data), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.statusError?.takeIf { condition == Condition.UNREADABLE }?.let { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                Actions(condition, data, model)
            }
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun Actions(condition: Condition, data: ScreenData, model: AppViewModel) {
        val state = data.state
        when {
            condition == Condition.NO_PRIVILEGE || condition == Condition.PREPARING -> return
            // Nothing can be applied without the configuration, which Check again loads again.
            condition == Condition.UNREADABLE || condition == Condition.RESTRICTED || state.preparationError != null -> {
                FilledTonalButton(onClick = model::recheck, enabled = state.operation == null) { Text(stringResource(R.string.check_again)) }
                return
            }
        }
        // Restoring also re-enables components and forgets the saved configuration, which stay after a reboot or a tripped guard.
        val restorable = state.status?.let { it.applied || it.saved || it.components.isNotEmpty() } == true
        val height = ButtonDefaults.MediumContainerHeight
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                primary = true,
                icon = Icons.Default.Check,
                text = stringResource(if (condition == Condition.ACTIVE) R.string.apply_again else R.string.apply),
                enabled = state.idle && data.config != null && state.status != null,
                onClick = model::apply,
                modifier = Modifier.weight(1f).heightIn(min = height),
            )
            ActionButton(
                primary = false,
                icon = Icons.Default.Restore,
                text = stringResource(R.string.revert),
                enabled = state.idle && restorable,
                onClick = model::revert,
                modifier = Modifier.weight(1f).heightIn(min = height),
            )
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun ActionButton(primary: Boolean, icon: ImageVector, text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
        val height = ButtonDefaults.MediumContainerHeight
        val content: @Composable () -> Unit = {
            Icon(icon, null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(height)))
            Text(text, style = ButtonDefaults.textStyleFor(height), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (primary) {
            Button(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.contentPaddingFor(height), modifier = modifier) { content() }
        } else {
            FilledTonalButton(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.contentPaddingFor(height), modifier = modifier) { content() }
        }
    }

    fun title(condition: Condition): Int = when (condition) {
        Condition.PREPARING -> R.string.hero_preparing
        Condition.NO_PRIVILEGE -> R.string.hero_no_privilege
        Condition.RESTRICTED -> R.string.hero_restricted
        Condition.UNREADABLE -> R.string.hero_unreadable
        Condition.CHECKING -> R.string.hero_checking
        Condition.TRIPPED -> R.string.hero_tripped
        Condition.ACTIVE -> R.string.hero_active
        Condition.ORIGINAL -> R.string.hero_original
    }

    @Composable
    fun description(condition: Condition, data: ScreenData): String = when (condition) {
        Condition.PREPARING -> stringResource(R.string.hero_preparing_text)
        Condition.NO_PRIVILEGE -> stringResource(R.string.hero_no_privilege_text)
        Condition.RESTRICTED -> stringResource(R.string.hero_restricted_text)
        Condition.UNREADABLE -> stringResource(R.string.hero_unreadable_text)
        Condition.CHECKING -> stringResource(R.string.hero_checking_text)
        Condition.TRIPPED -> stringResource(R.string.hero_tripped_text, (data.state.status?.guard as? GuardState.Tripped)?.reason.orEmpty())
        Condition.ACTIVE -> {
            val hook = data.state.status?.hook
            when {
                hook?.installed == true && data.settings.bootHook -> stringResource(R.string.hero_active_hook, Labels.rootManagers(hook.managers))
                data.settings.applyAfterBoot -> stringResource(R.string.hero_active_application)
                else -> stringResource(R.string.hero_active_once)
            }
        }
        Condition.ORIGINAL -> stringResource(R.string.hero_original_text)
    }

    /** Guides the user to a privilege: what each candidate says, and what to do. */
    @Composable
    fun Setup(selection: PrivilegeSelection, onGrant: () -> Unit, onRecheck: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(Widgets.SPACING)) {
            Widgets.Section(stringResource(R.string.setup_title), stringResource(R.string.setup_help)) {
                selection.statuses.forEachIndexed { index, (kind, status) ->
                    Widgets.Item(
                        index = index,
                        count = selection.statuses.size,
                        headline = Labels.privilege(kind),
                        supporting = Labels.describe(status),
                        icon = Icons.Default.AdminPanelSettings,
                        trailing = if (status == PrivilegeStatus.PermissionRequired) {
                            { FilledTonalButton(onClick = onGrant) { Text(stringResource(R.string.grant)) } }
                        } else {
                            null
                        },
                    )
                }
            }
            FlowRow(Modifier.padding(horizontal = 8.dp)) { TextButton(onClick = onRecheck) { Text(stringResource(R.string.check_again)) } }
        }
    }

    /** The details of the state, each opening the page of the settings it is about. */
    @Composable
    fun Details(data: ScreenData, model: AppViewModel, onSettings: (SettingsScreen.Page) -> Unit) {
        val state = data.state
        val status = state.status
        val items = buildList<@Composable (Int, Int) -> Unit> {
            add { index, count ->
                Widgets.Item(
                    index, count,
                    headline = stringResource(R.string.detail_privilege),
                    supporting = state.privilege?.privilege?.let { stringResource(R.string.privilege_runs_as_root, Labels.privilege(it.kind)) } ?: stringResource(R.string.privilege_none_short),
                    icon = Icons.Default.Security,
                    onClick = { onSettings(SettingsScreen.Page.PRIVILEGE) },
                )
            }
            add { index, count ->
                Widgets.Item(
                    index, count,
                    headline = stringResource(R.string.detail_boot),
                    supporting = SettingsScreen.bootSummary(status?.hook, data.settings),
                    icon = Icons.Default.PowerSettingsNew,
                    onClick = { onSettings(SettingsScreen.Page.BOOT) },
                )
            }
            when (val guard = status?.guard) {
                is GuardState.Watching -> add { index, count ->
                    Widgets.Item(index, count, stringResource(R.string.detail_guard), stringResource(R.string.guard_watching), Icons.Default.Shield, onClick = { onSettings(SettingsScreen.Page.APPLYING) })
                }
                is GuardState.Tripped -> add { index, count ->
                    Widgets.Item(index, count, stringResource(R.string.detail_guard), stringResource(R.string.guard_tripped, guard.reason), Icons.Default.Shield, onClick = { onSettings(SettingsScreen.Page.APPLYING) })
                }
                else -> Unit
            }
            if (status != null && status.components.isNotEmpty()) add { index, count ->
                val disabled = status.components.map { it.component }.distinct().size
                Widgets.Item(
                    index, count,
                    headline = stringResource(R.string.detail_components),
                    supporting = pluralStringResource(R.plurals.components_disabled, disabled, disabled),
                    icon = Icons.Default.Block,
                    onClick = { onSettings(SettingsScreen.Page.COMPONENTS) },
                )
            }
            if (state.log.isNotEmpty()) add { index, count ->
                Widgets.Item(
                    index, count,
                    stringResource(R.string.log),
                    pluralStringResource(R.plurals.log_lines, state.log.size, state.log.size),
                    Icons.AutoMirrored.Filled.Notes,
                    onClick = { model.showLog(true) },
                )
            }
        }
        Widgets.Section(stringResource(R.string.details_title)) { items.forEachIndexed { index, item -> item(index, items.size) } }
    }

    /** A sample of every category in the chosen fonts; it opens the fonts. */
    @Composable
    fun Specimen(selection: Selection, fonts: List<LibraryFont>, library: FontLibrary, onClick: () -> Unit) {
        Widgets.Section(stringResource(R.string.specimen_title)) {
            Category.entries.forEachIndexed { index, category ->
                val upright = fonts.firstOrNull { it.id == selection.font(Slot(category, FaceStyle.NORMAL)) }
                val italic = fonts.firstOrNull { it.id == selection.font(Slot(category, FaceStyle.ITALIC)) }
                val family = Previews.family(library, upright, italic = false) ?: Previews.systemFamily(category)
                val italicFamily = Previews.family(library, italic, italic = true) ?: family
                Widgets.Panel(index, Category.entries.size, onClick = onClick) {
                    Text(
                        "${Labels.category(category)} · ${upright?.label ?: stringResource(R.string.font_system)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Widgets.secondary,
                    )
                    Text(stringResource(R.string.specimen_text), fontSize = 20.sp, fontFamily = family, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.specimen_italic),
                        fontSize = 16.sp,
                        fontStyle = FontStyle.Italic,
                        fontFamily = italicFamily,
                        color = Widgets.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
