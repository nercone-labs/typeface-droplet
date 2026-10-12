package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.config.Settings
import dev.nercone.typefacedroplet.engine.Action
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.Face
import dev.nercone.typefacedroplet.engine.FaceStyle
import dev.nercone.typefacedroplet.engine.FallbackEntry
import dev.nercone.typefacedroplet.engine.FamilyInfo
import dev.nercone.typefacedroplet.engine.Matcher
import dev.nercone.typefacedroplet.engine.Role
import dev.nercone.typefacedroplet.engine.Rule
import dev.nercone.typefacedroplet.library.LibraryFont

/**
 * Roles and rules, edited by hand, and the families of the system they apply to.
 * A role is a set of faces; ordered rules map system families to roles, and the first matching rule decides.
 */
object CustomizeScreen {
    enum class Tab(val title: Int) {
        ROLES(R.string.tab_roles),
        RULES(R.string.tab_rules),
        SYSTEM(R.string.tab_system),
    }

    /** What the screen shows: the tab and the open editor. The floating action button opens editors too. */
    class State {
        var tab by mutableStateOf(Tab.ROLES)

        /** The role being edited: its name, or `NEW` for a new role. */
        var role by mutableStateOf<String?>(null)

        /** The rule being edited: its index, or `NEW_RULE` for a new rule. */
        var rule by mutableStateOf<Int?>(null)

        /** The system family being assigned a role or a font. */
        var assign by mutableStateOf<String?>(null)
    }

    /** The name the role editor uses for a role that does not exist yet. */
    const val NEW = ""
    const val NEW_RULE = -1

    @Composable
    fun FloatingButton(state: State, enabled: Boolean) {
        val (text, onClick) = when (state.tab) {
            Tab.ROLES -> R.string.role_new to { state.role = NEW }
            Tab.RULES -> R.string.rule_new to { state.rule = NEW_RULE }
            Tab.SYSTEM -> return
        }
        if (enabled) ExtendedFloatingActionButton(onClick = onClick, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(text)) })
    }

    @Composable
    fun Content(ui: UiState, config: Config?, fonts: List<LibraryFont>, settings: Settings.Values, model: AppViewModel, state: State) {
        Column(Modifier.fillMaxSize()) {
            SecondaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { tab ->
                    Tab(selected = state.tab == tab, onClick = { state.tab = tab }, text = { Text(stringResource(tab.title)) })
                }
            }
            if (config == null) return@Column
            LazyColumn(contentPadding = PaddingValues(Widgets.SCREEN_PADDING), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Mode(settings.customized, model) }
                when (state.tab) {
                    Tab.ROLES -> roles(config, fonts) { state.role = it }
                    Tab.RULES -> rules(config, model) { state.rule = it }
                    Tab.SYSTEM -> system(ui, config, model) { state.assign = it }
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
        if (config == null) return
        state.role?.let { name ->
            RoleEditor(
                original = config.role(name),
                existing = config.roles.map { it.name }.toSet() - name,
                fonts = fonts,
                onSave = { role -> model.customize { it.replaceRole(name, role) }; state.role = null },
                onDelete = { model.customize { it.deleteRole(name) }; state.role = null },
                onDismiss = { state.role = null },
            )
        }
        state.rule?.let { index ->
            RuleEditor(
                original = config.rules.getOrNull(index),
                roles = config.roles.map { it.name },
                onSave = { rule ->
                    model.customize { current ->
                        current.copy(rules = if (index == NEW_RULE) current.rules + rule else current.rules.toMutableList().also { it[index] = rule })
                    }
                    state.rule = null
                },
                onDelete = {
                    if (index != NEW_RULE) model.customize { current -> current.copy(rules = current.rules.toMutableList().also { it.removeAt(index) }) }
                    state.rule = null
                },
                onDismiss = { state.rule = null },
            )
        }
        state.assign?.let { family ->
            Assign(family, config, fonts, onAssign = { change -> model.customize(change); state.assign = null }, onDismiss = { state.assign = null })
        }
    }

    /** Whether the roles and rules follow the chosen fonts or were edited by hand. */
    @Composable
    fun Mode(customized: Boolean, model: AppViewModel) {
        var confirm by remember { mutableStateOf(false) }
        if (customized) {
            Widgets.Notice(Widgets.Tone.INFORMATION, stringResource(R.string.customized_title), stringResource(R.string.customized_text)) {
                TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.reset)) }
            }
        } else {
            Widgets.Notice(Widgets.Tone.INFORMATION, stringResource(R.string.recommended_title), stringResource(R.string.recommended_text))
        }
        if (confirm) {
            Widgets.Confirm(stringResource(R.string.reset_title), stringResource(R.string.reset_text), stringResource(R.string.reset), model::reset) { confirm = false }
        }
    }

    fun LazyListScope.roles(config: Config, fonts: List<LibraryFont>, onEdit: (String) -> Unit) {
        if (config.roles.isEmpty()) item { Text(stringResource(R.string.roles_empty), style = MaterialTheme.typography.bodyMedium) }
        itemsIndexed(config.roles, key = { _, role -> "role:${role.name}" }) { index, role ->
            Widgets.Item(
                index = index,
                count = config.roles.size,
                headline = role.name,
                supporting = (role.faces.map { faceSummary(it, fonts) } + stringResource(R.string.role_used_by, config.rulesUsing(role.name).size)).joinToString("\n"),
                onClick = { onEdit(role.name) },
            )
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    fun LazyListScope.rules(config: Config, model: AppViewModel, onEdit: (Int) -> Unit) {
        item { Text(stringResource(R.string.rules_help), style = MaterialTheme.typography.bodyMedium, color = Widgets.secondary) }
        itemsIndexed(config.rules) { index, rule ->
            SegmentedListItem(
                onClick = { onEdit(index) },
                shapes = ListItemDefaults.segmentedShapes(index, config.rules.size),
                supportingContent = { Text(actionText(rule.action)) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { model.customize { it.copy(rules = move(it.rules, index, -1)) } }, enabled = index > 0) {
                            Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_up))
                        }
                        IconButton(onClick = { model.customize { it.copy(rules = move(it.rules, index, 1)) } }, enabled = index < config.rules.lastIndex) {
                            Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_down))
                        }
                    }
                },
            ) { Text("${index + 1}. ${matcherText(rule.match)}") }
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    fun LazyListScope.system(ui: UiState, config: Config, model: AppViewModel, onAssign: (String) -> Unit) {
        val families = ui.families
        if (families == null) {
            item {
                LaunchedEffect(ui.privilege?.privilege) { if (ui.privilege?.privilege != null) model.loadFamilies() }
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (ui.privilege?.privilege == null) Text(stringResource(R.string.families_need_privilege)) else LoadingIndicator(Modifier.size(64.dp))
                }
            }
            return
        }
        item { Text(stringResource(R.string.families_help), style = MaterialTheme.typography.bodyMedium, color = Widgets.secondary) }
        val named = families.filter { it.element != "alias" && it.name != null }.groupBy { it.name!! }
        val names = named.keys.sorted()
        itemsIndexed(names, key = { _, name -> "family:$name" }) { index, name ->
            val infos = named.getValue(name)
            val rule = config.rules.firstOrNull { rule -> matches(rule.match, infos.first()) }
            Widgets.Item(
                index = index,
                count = names.size,
                headline = name,
                supporting = listOf(infos.flatMap { it.files }.distinct().joinToString(), rule?.let { actionText(it.action) } ?: stringResource(R.string.families_unchanged))
                    .joinToString("\n"),
                onClick = { onAssign(name) },
            )
        }
    }

    fun move(rules: List<Rule>, index: Int, offset: Int): List<Rule> {
        val target = index + offset
        if (target !in rules.indices) return rules
        return rules.toMutableList().also { it.add(target, it.removeAt(index)) }
    }

    @Composable
    fun faceSummary(face: Face, fonts: List<LibraryFont>): String {
        val name = fonts.firstOrNull { it.id == face.font }?.label ?: stringResource(R.string.font_missing)
        val style = face.style?.let { stringResource(FontsScreen.styleName(it)) } ?: stringResource(R.string.style_automatic)
        val weight = face.weight?.let { stringResource(R.string.face_weight_fixed, it) }
        return listOfNotNull(name, style, weight).joinToString(" · ")
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

    /** Whether `matcher` matches the named family `family`, as the engine decides it (wildcards `*` and `?`). */
    fun matches(matcher: Matcher, family: FamilyInfo): Boolean = when (matcher) {
        is Matcher.Family -> family.name?.let { wildcard(matcher.pattern, it) } == true
        is Matcher.File -> family.name != null && family.files.any { file -> wildcard(matcher.pattern, stem(file)) }
        is Matcher.Language -> false
    }

    /** The file name without a font extension (`.ttf`, `.otf`, `.ttc`, in any case). */
    fun stem(file: String): String {
        val dot = file.lastIndexOf('.')
        return if (dot > 0 && file.substring(dot + 1).lowercase() in listOf("ttf", "otf", "ttc")) file.substring(0, dot) else file
    }

    /** Matches `name` against a pattern where `*` matches any sequence and `?` one character. */
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

    @Composable
    fun RoleEditor(original: Role?, existing: Set<String>, fonts: List<LibraryFont>, onSave: (Role) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
        var name by remember { mutableStateOf(original?.name.orEmpty()) }
        var faces by remember { mutableStateOf(original?.faces.orEmpty()) }
        var axes by remember { mutableStateOf(original?.axes.orEmpty()) }
        var confirm by remember { mutableStateOf(false) }
        val valid = name.isNotBlank() && name.trim().length <= Config.MAX_NAME_LENGTH && name.trim() !in existing && faces.isNotEmpty()
        Widgets.Editor(stringResource(if (original == null) R.string.role_new else R.string.role_edit), valid, { onSave(Role(name.trim(), faces, axes)) }, onDismiss) {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text(stringResource(R.string.role_name)) },
                isError = name.trim() in existing,
                supportingText = { if (name.trim() in existing) Text(stringResource(R.string.role_name_taken)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.role_faces), style = MaterialTheme.typography.titleMedium)
            faces.forEachIndexed { index, face ->
                FaceEditor(face, fonts, onChange = { changed -> faces = faces.toMutableList().also { it[index] = changed } }) {
                    faces = faces.toMutableList().also { it.removeAt(index) }
                }
                HorizontalDivider()
            }
            OutlinedButton(onClick = { fonts.firstOrNull()?.let { faces = faces + Face(it.id) } }, enabled = fonts.isNotEmpty()) {
                Icon(Icons.Default.Add, null)
                Text(stringResource(R.string.role_add_face))
            }
            Text(stringResource(R.string.role_axes), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.role_axes_help), style = MaterialTheme.typography.bodySmall, color = Widgets.secondary)
            Widgets.Axes(axes) { axes = it }
            if (original != null) FilledTonalButton(onClick = { confirm = true }) { Text(stringResource(R.string.role_delete)) }
        }
        if (confirm) Widgets.Confirm(stringResource(R.string.role_delete), stringResource(R.string.role_delete_text), stringResource(R.string.remove), onDelete) { confirm = false }
    }

    @Composable
    fun FaceEditor(face: Face, fonts: List<LibraryFont>, onChange: (Face) -> Unit, onRemove: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Widgets.Dropdown(
                    label = stringResource(R.string.face_font),
                    options = fonts,
                    selected = fonts.firstOrNull { it.id == face.font },
                    describe = { it.label },
                    onSelect = { onChange(face.copy(font = it.id)) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, stringResource(R.string.remove)) }
            }
            Widgets.Segments(
                options = listOf(null, FaceStyle.NORMAL, FaceStyle.ITALIC),
                selected = face.style,
                describe = { style -> style?.let { stringResource(FontsScreen.styleName(it)) } ?: stringResource(R.string.style_automatic) },
                onSelect = { onChange(face.copy(style = it)) },
            )
            var weight by remember(face) { mutableStateOf(face.weight?.toString().orEmpty()) }
            OutlinedTextField(
                weight,
                { text ->
                    weight = text.filter(Char::isDigit).take(4)
                    val value = weight.toIntOrNull()
                    if (weight.isEmpty()) onChange(face.copy(weight = null)) else if (value != null && value in 1..1000) onChange(face.copy(weight = value))
                },
                label = { Text(stringResource(R.string.face_weight)) },
                supportingText = { Text(stringResource(R.string.face_weight_help)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.face_axes), style = MaterialTheme.typography.bodySmall, color = Widgets.secondary)
            Widgets.Axes(face.axes) { onChange(face.copy(axes = it)) }
        }
    }

    @Composable
    fun RuleEditor(original: Rule?, roles: List<String>, onSave: (Rule) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
        var kind by remember { mutableStateOf(original?.match?.key ?: Matcher.FAMILY) }
        var pattern by remember { mutableStateOf(original?.match?.pattern.orEmpty()) }
        var prepend by remember { mutableStateOf(original?.action is Action.Prepend) }
        var role by remember { mutableStateOf((original?.action as? Action.Replace)?.role ?: roles.firstOrNull()) }
        var entries by remember { mutableStateOf((original?.action as? Action.Prepend)?.entries ?: roles.take(1).map { FallbackEntry(it) }) }
        // Only language rules can insert fallback families.
        val inserting = prepend && kind == Matcher.LANGUAGE
        val action: Action? = if (inserting) entries.takeIf { it.isNotEmpty() }?.let { Action.Prepend(it) } else role?.let { Action.Replace(it) }
        val valid = pattern.isNotBlank() && action != null
        Widgets.Editor(
            stringResource(if (original == null) R.string.rule_new else R.string.rule_edit),
            valid,
            { action?.let { onSave(Rule(Matcher.of(kind, pattern.trim()), it)) } },
            onDismiss,
        ) {
            Text(stringResource(R.string.rule_match), style = MaterialTheme.typography.titleMedium)
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
            Text(stringResource(R.string.rule_action), style = MaterialTheme.typography.titleMedium)
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
                Text(stringResource(R.string.action_prepend_help), style = MaterialTheme.typography.bodySmall, color = Widgets.secondary)
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
            if (original != null) FilledTonalButton(onClick = onDelete) { Text(stringResource(R.string.rule_delete)) }
        }
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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.assign_help))
                    Widgets.Dropdown(stringResource(R.string.assign_role), config.roles.map { it.name }, role, { it }, {
                        role = it
                        font = null
                    })
                    Widgets.Dropdown(stringResource(R.string.assign_font), fonts, font, { it.label }, {
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
                                val name = current.uniqueRoleName(family)
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
}
