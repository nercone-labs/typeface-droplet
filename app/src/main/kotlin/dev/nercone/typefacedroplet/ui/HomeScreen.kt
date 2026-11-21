package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
                    if (condition == Condition.NO_PRIVILEGE) state.privilege?.let { Privileges.Setup(it, onGrant = model::grantShizuku, onRecheck = model::recheck) }
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
    @Composable
    fun Verdict(condition: Condition, data: ScreenData, selection: Selection, library: FontLibrary, model: AppViewModel) {
        val state = data.state
        val sans = data.fonts.firstOrNull { it.id == selection.font(Slot(Category.SANS, FaceStyle.NORMAL)) }
        val family = Previews.family(library, sans, italic = false)
        Widgets.Hero(
            mood = mood(condition),
            busy = state.operation != null || condition == Condition.PREPARING || condition == Condition.CHECKING,
            title = stringResource(title(condition)),
            text = state.operation?.let { Labels.operation(it) } ?: description(condition, data),
            detail = state.statusError?.takeIf { condition == Condition.UNREADABLE }?.text,
            actions = actions(condition, data, model),
        ) { color -> Text("Aa", fontSize = 28.sp, fontFamily = family ?: FontFamily.Default, color = color) }
    }

    fun mood(condition: Condition): Widgets.Mood = when (condition) {
        Condition.ACTIVE -> Widgets.Mood.GOOD
        Condition.TRIPPED, Condition.NO_PRIVILEGE, Condition.RESTRICTED, Condition.UNREADABLE -> Widgets.Mood.BAD
        Condition.PREPARING, Condition.CHECKING, Condition.ORIGINAL -> Widgets.Mood.NEUTRAL
    }

    @Composable
    fun actions(condition: Condition, data: ScreenData, model: AppViewModel): List<Widgets.Action> {
        val state = data.state
        when {
            condition == Condition.NO_PRIVILEGE || condition == Condition.PREPARING -> return emptyList()
            // Nothing can be applied without the configuration, which Check again loads again.
            condition == Condition.UNREADABLE || condition == Condition.RESTRICTED || state.preparationError != null ->
                return listOf(Widgets.Action(stringResource(R.string.check_again), Icons.Default.Refresh, state.operation == null, model::recheck))
        }
        // Restoring also re-enables components and forgets the saved configuration, which stay after a reboot or a tripped guard.
        val restorable = state.status?.let { it.applied || it.saved || it.components.isNotEmpty() } == true
        return listOf(
            Widgets.Action(
                stringResource(if (condition == Condition.ACTIVE) R.string.apply_again else R.string.apply),
                Icons.Default.Check,
                state.idle && data.config != null && state.status != null,
                model::apply,
            ),
            Widgets.Action(stringResource(R.string.revert), Icons.Default.Restore, state.idle && restorable, model::revert),
        )
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
                    supporting = Privileges.current(state.privilege),
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
