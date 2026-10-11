package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.engine.Action
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.Face
import dev.nercone.typefacedroplet.engine.FallbackEntry
import dev.nercone.typefacedroplet.engine.FamilyInfo
import dev.nercone.typefacedroplet.engine.Matcher
import dev.nercone.typefacedroplet.engine.Role
import dev.nercone.typefacedroplet.engine.Rule
import dev.nercone.typefacedroplet.library.LibraryFont

/** The ordered rules, and the system families they apply to. The first matching rule wins. */
object RulesScreen {
    /** Which dialog of the screen is open. */
    sealed interface Dialogs {
        data class EditRule(val index: Int?) : Dialogs
        data object Families : Dialogs
        data class Assign(val family: String) : Dialogs
    }

    @Composable
    fun Content(state: UiState, config: Config?, fonts: List<LibraryFont>, model: AppViewModel, contentPadding: PaddingValues, dialog: Dialogs?, onDialog: (Dialogs?) -> Unit) {
        val rules = config?.rules.orEmpty()
        LazyColumn(
            contentPadding = PaddingValues(
                start = Widgets.SCREEN_PADDING,
                end = Widgets.SCREEN_PADDING,
                top = Widgets.SCREEN_PADDING,
                bottom = Widgets.SCREEN_PADDING + contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(stringResource(R.string.rules_help), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(
                    onClick = { onDialog(Dialogs.Families) },
                    enabled = state.privilege?.privilege != null,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.rules_browse)) }
            }
            itemsIndexed(rules) { index, rule ->
                Card(Modifier.fillMaxWidth().clickable { onDialog(Dialogs.EditRule(index)) }) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}. ${matcherText(rule.match)}", style = MaterialTheme.typography.titleSmall)
                            Text(actionText(rule.action), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { model.updateConfig { it.copy(rules = move(it.rules, index, -1)) } }, enabled = index > 0) {
                            Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_up))
                        }
                        IconButton(onClick = { model.updateConfig { it.copy(rules = move(it.rules, index, 1)) } }, enabled = index < rules.lastIndex) {
                            Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_down))
                        }
                    }
                }
            }
        }
        when (dialog) {
            is Dialogs.EditRule -> if (config != null) {
                Editor(
                    original = dialog.index?.let { rules.getOrNull(it) },
                    roles = config.roles.map { it.name },
                    onSave = { rule ->
                        model.updateConfig { current ->
                            current.copy(rules = if (dialog.index == null) current.rules + rule else current.rules.toMutableList().also { it[dialog.index] = rule })
                        }
                        onDialog(null)
                    },
                    onDelete = {
                        if (dialog.index != null) model.updateConfig { current -> current.copy(rules = current.rules.toMutableList().also { it.removeAt(dialog.index) }) }
                        onDialog(null)
                    },
                    onDismiss = { onDialog(null) },
                )
            }
            Dialogs.Families -> Families(state, config, model, onPick = { onDialog(Dialogs.Assign(it)) }, onDismiss = { onDialog(null) })
            is Dialogs.Assign -> if (config != null) {
                Assign(dialog.family, config, fonts, onAssign = { change ->
                    model.updateConfig(change)
                    onDialog(null)
                }, onDismiss = { onDialog(null) })
            }
            null -> {}
        }
    }

    fun move(rules: List<Rule>, index: Int, offset: Int): List<Rule> {
        val target = index + offset
        if (target !in rules.indices) return rules
        return rules.toMutableList().also { it.add(target, it.removeAt(index)) }
    }

    @Composable
    fun matcherText(matcher: Matcher): String = when (matcher) {
        is Matcher.Family -> stringResource(R.string.match_family_text, matcher.pattern)
        is Matcher.File -> stringResource(R.string.match_file_text, matcher.pattern)
        is Matcher.Language -> stringResource(R.string.match_language_text, matcher.pattern)
    }

    @Composable
    fun actionText(action: Action): String = when (action) {
        is Action.Replace -> stringResource(R.string.action_replace_text, action.role)
        is Action.Prepend -> stringResource(R.string.action_prepend_text, action.entries.joinToString { entry -> entry.fallbackFor?.let { "${entry.role} ($it)" } ?: entry.role })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Editor(original: Rule?, roles: List<String>, onSave: (Rule) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
        var kind by remember { mutableStateOf(original?.match?.key ?: Matcher.FAMILY) }
        var pattern by remember { mutableStateOf(original?.match?.pattern.orEmpty()) }
        var prepend by remember { mutableStateOf(original?.action is Action.Prepend) }
        var role by remember { mutableStateOf((original?.action as? Action.Replace)?.role ?: roles.firstOrNull()) }
        var entries by remember { mutableStateOf((original?.action as? Action.Prepend)?.entries ?: roles.take(1).map { FallbackEntry(it) }) }
        // Only language rules can insert fallback families.
        val inserting = prepend && kind == Matcher.LANGUAGE
        val action: Action? = if (inserting) entries.takeIf { it.isNotEmpty() }?.let { Action.Prepend(it) } else role?.let { Action.Replace(it) }
        val valid = pattern.isNotBlank() && action != null
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(if (original == null) R.string.rule_new else R.string.rule_edit)) },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
                        actions = {
                            TextButton(onClick = { action?.let { onSave(Rule(Matcher.of(kind, pattern.trim()), it)) } }, enabled = valid) { Text(stringResource(R.string.save)) }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Widgets.SCREEN_PADDING),
                    verticalArrangement = Arrangement.spacedBy(Widgets.SPACING),
                ) {
                    Text(stringResource(R.string.rule_match), style = MaterialTheme.typography.titleSmall)
                    Widgets.Segments(
                        options = listOf(Matcher.FAMILY, Matcher.FILE, Matcher.LANGUAGE),
                        selected = kind,
                        describe = {
                            stringResource(
                                when (it) {
                                    Matcher.FAMILY -> R.string.match_family
                                    Matcher.FILE -> R.string.match_file
                                    else -> R.string.match_language
                                },
                            )
                        },
                        onSelect = { kind = it },
                    )
                    OutlinedTextField(
                        pattern,
                        { pattern = it },
                        label = { Text(stringResource(R.string.rule_pattern)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    when (kind) {
                                        Matcher.FAMILY -> R.string.rule_pattern_family_help
                                        Matcher.FILE -> R.string.rule_pattern_file_help
                                        else -> R.string.rule_pattern_language_help
                                    },
                                ),
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.rule_action), style = MaterialTheme.typography.titleSmall)
                    if (kind == Matcher.LANGUAGE) {
                        Widgets.Segments(
                            options = listOf(false, true),
                            selected = inserting,
                            describe = { stringResource(if (it) R.string.action_prepend else R.string.action_replace) },
                            onSelect = { prepend = it },
                        )
                    }
                    if (!inserting) {
                        Widgets.Dropdown(stringResource(R.string.rule_role), roles, role, { it }, { role = it })
                    } else {
                        Text(stringResource(R.string.action_prepend_help), style = MaterialTheme.typography.bodySmall)
                        entries.forEachIndexed { index, entry ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Widgets.Dropdown(stringResource(R.string.rule_role), roles, entry.role, { it }, { selected ->
                                    entries = entries.toMutableList().also { it[index] = entry.copy(role = selected) }
                                }, modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    entry.fallbackFor.orEmpty(),
                                    { text -> entries = entries.toMutableList().also { it[index] = entry.copy(fallbackFor = text.trim().ifEmpty { null }) } },
                                    label = { Text(stringResource(R.string.rule_fallback_for)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { entries = entries.toMutableList().also { it.removeAt(index) } }) { Icon(Icons.Default.Close, stringResource(R.string.remove)) }
                            }
                        }
                        OutlinedButton(onClick = { roles.firstOrNull()?.let { entries = entries + FallbackEntry(it) } }, enabled = roles.isNotEmpty()) {
                            Icon(Icons.Default.Add, null)
                            Text(stringResource(R.string.rule_add_entry))
                        }
                    }
                    if (original != null) OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.rule_delete)) }
                }
            }
        }
    }

    /** The named families of the system documents (without the generated ones), to assign one directly. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Families(state: UiState, config: Config?, model: AppViewModel, onPick: (String) -> Unit, onDismiss: () -> Unit) {
        LaunchedEffect(Unit) { model.loadFamilies() }
        val named = state.families.filter { it.element != "alias" && it.name != null }.groupBy { it.name!! }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.families_title)) },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
                    )
                },
            ) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    if (state.operation == Operation.LOADING_FAMILIES) item { Text(stringResource(R.string.loading), Modifier.padding(Widgets.SCREEN_PADDING)) }
                    items(named.keys.sorted()) { name ->
                        val infos = named.getValue(name)
                        val rule = config?.rules?.firstOrNull { rule -> matches(rule.match, infos.first()) }
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = {
                                Text(
                                    listOf(
                                        infos.flatMap { it.files }.distinct().joinToString(),
                                        rule?.let { actionText(it.action) } ?: stringResource(R.string.families_unchanged),
                                    ).joinToString("\n"),
                                )
                            },
                            modifier = Modifier.clickable { onPick(name) },
                        )
                    }
                }
            }
        }
    }

    /** Whether `matcher` matches the named family `family`, as the engine decides it (wildcards `*` and `?`). */
    fun matches(matcher: Matcher, family: FamilyInfo): Boolean = when (matcher) {
        is Matcher.Family -> family.name?.let { wildcard(matcher.pattern, it) } == true
        is Matcher.File -> family.name != null && family.files.any { file -> wildcard(matcher.pattern, stem(file)) }
        is Matcher.Language -> false
    }

    fun stem(file: String): String {
        val dot = file.lastIndexOf('.')
        return if (dot > 0 && file.substring(dot + 1).lowercase() in listOf("ttf", "otf", "ttc")) file.substring(0, dot) else file
    }

    fun wildcard(pattern: String, name: String): Boolean {
        val expression = pattern.map { character ->
            when (character) {
                '*' -> ".*"
                '?' -> "."
                else -> Regex.escape(character.toString())
            }
        }.joinToString("")
        return Regex(expression).matches(name)
    }

    /**
     * Assigns a role, or a single font, to one family. A font creates a role named after the
     * family. The new rule matches the family name exactly and goes first, so it wins.
     */
    @Composable
    fun Assign(family: String, config: Config, fonts: List<LibraryFont>, onAssign: ((Config) -> Config) -> Unit, onDismiss: () -> Unit) {
        var role by remember { mutableStateOf<String?>(null) }
        var font by remember { mutableStateOf<LibraryFont?>(null) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.assign_title, family)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Widgets.SPACING)) {
                    Text(stringResource(R.string.assign_help))
                    Widgets.Dropdown(stringResource(R.string.assign_role), config.roles.map { it.name }, role, { it }, {
                        role = it
                        font = null
                    })
                    Widgets.Dropdown(stringResource(R.string.assign_font), fonts, font, FontsScreen::label, {
                        font = it
                        role = null
                    })
                }
            },
            confirmButton = {
                TextButton(enabled = role != null || font != null, onClick = {
                    val chosenRole = role
                    val chosenFont = font
                    onAssign { current ->
                        val rule = { name: String -> Rule(Matcher.Family(family), Action.Replace(name)) }
                        val rules = current.rules.filterNot { it.match == Matcher.Family(family) }
                        when {
                            chosenRole != null -> current.copy(rules = listOf(rule(chosenRole)) + rules)
                            chosenFont != null -> {
                                val name = uniqueName(current, family)
                                current.copy(roles = current.roles + Role(name, listOf(Face(chosenFont.id))), rules = listOf(rule(name)) + rules)
                            }
                            else -> current
                        }
                    }
                }) { Text(stringResource(R.string.assign)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    fun uniqueName(config: Config, base: String): String {
        val names = config.roles.map { it.name }.toSet()
        if (base !in names) return base
        return generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { it !in names }
    }
}
