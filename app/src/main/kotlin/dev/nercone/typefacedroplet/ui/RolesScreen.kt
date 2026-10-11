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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.engine.Action
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.Face
import dev.nercone.typefacedroplet.engine.FaceStyle
import dev.nercone.typefacedroplet.engine.Role
import dev.nercone.typefacedroplet.library.LibraryFont

/** The roles: named sets of faces that rules map system font families to. */
object RolesScreen {
    @Composable
    fun Content(config: Config?, fonts: List<LibraryFont>, model: AppViewModel, contentPadding: PaddingValues, editing: String?, onEdit: (String?) -> Unit) {
        val roles = config?.roles.orEmpty()
        LazyColumn(
            contentPadding = PaddingValues(
                start = Widgets.SCREEN_PADDING,
                end = Widgets.SCREEN_PADDING,
                top = Widgets.SCREEN_PADDING,
                bottom = Widgets.SCREEN_PADDING + contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(Widgets.SPACING),
        ) {
            if (roles.isEmpty()) item { Text(stringResource(R.string.roles_empty), style = MaterialTheme.typography.bodyMedium) }
            items(roles, key = { it.name }) { role ->
                val rules = config?.rules.orEmpty().count { rule ->
                    when (val action = rule.action) {
                        is Action.Replace -> action.role == role.name
                        is Action.Prepend -> action.entries.any { it.role == role.name }
                    }
                }
                Card(Modifier.fillMaxWidth().clickable { onEdit(role.name) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(role.name, style = MaterialTheme.typography.titleMedium)
                        role.faces.forEach { face ->
                            Text(faceSummary(face, fonts), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(stringResource(R.string.role_used_by, rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (editing != null && config != null) {
            Editor(
                original = config.role(editing),
                existing = roles.map { it.name }.toSet() - editing,
                fonts = fonts,
                onSave = { role ->
                    model.updateConfig { current ->
                        val renamed = current.rules.map { rule -> rule.copy(action = renameRole(rule.action, editing, role.name)) }
                        val list = if (current.role(editing) == null) current.roles + role else current.roles.map { if (it.name == editing) role else it }
                        current.copy(roles = list, rules = renamed)
                    }
                    onEdit(null)
                },
                onDelete = {
                    model.updateConfig { current -> deleteRole(current, editing) }
                    onEdit(null)
                },
                onDismiss = { onEdit(null) },
            )
        }
    }

    /** Removes a role and every rule entry that refers to it. */
    fun deleteRole(config: Config, name: String): Config = config.copy(
        roles = config.roles.filter { it.name != name },
        rules = config.rules.mapNotNull { rule ->
            when (val action = rule.action) {
                is Action.Replace -> rule.takeIf { action.role != name }
                is Action.Prepend -> action.entries.filter { it.role != name }.takeIf { it.isNotEmpty() }?.let { rule.copy(action = Action.Prepend(it)) }
            }
        },
    )

    fun renameRole(action: Action, from: String, to: String): Action = when (action) {
        is Action.Replace -> if (action.role == from) Action.Replace(to) else action
        is Action.Prepend -> Action.Prepend(action.entries.map { if (it.role == from) it.copy(role = to) else it })
    }

    @Composable
    fun faceSummary(face: Face, fonts: List<LibraryFont>): String {
        val font = fonts.firstOrNull { it.id == face.font }
        val name = font?.let(FontsScreen::label) ?: stringResource(R.string.font_missing)
        val style = when (face.style) {
            null -> stringResource(R.string.style_automatic)
            FaceStyle.NORMAL -> stringResource(R.string.style_normal)
            FaceStyle.ITALIC -> stringResource(R.string.style_italic)
        }
        val weight = face.weight?.let { stringResource(R.string.face_weight_fixed, it) }
        return listOfNotNull(name, style, weight).joinToString(" · ")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Editor(original: Role?, existing: Set<String>, fonts: List<LibraryFont>, onSave: (Role) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
        var name by remember { mutableStateOf(original?.name.orEmpty()) }
        var faces by remember { mutableStateOf(original?.faces.orEmpty()) }
        var axes by remember { mutableStateOf(original?.axes.orEmpty()) }
        var confirmDelete by remember { mutableStateOf(false) }
        val valid = name.isNotBlank() && name.length <= Config.MAX_NAME_LENGTH && name !in existing && faces.isNotEmpty()
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(if (original == null) R.string.role_new else R.string.role_edit)) },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
                        actions = { TextButton(onClick = { onSave(Role(name.trim(), faces, axes)) }, enabled = valid) { Text(stringResource(R.string.save)) } },
                    )
                },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Widgets.SCREEN_PADDING),
                    verticalArrangement = Arrangement.spacedBy(Widgets.SPACING),
                ) {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text(stringResource(R.string.role_name)) },
                        isError = name in existing,
                        supportingText = { if (name in existing) Text(stringResource(R.string.role_name_taken)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.role_faces), style = MaterialTheme.typography.titleSmall)
                    faces.forEachIndexed { index, face ->
                        FaceEditor(face, fonts, onChange = { changed -> faces = faces.toMutableList().also { it[index] = changed } }, onRemove = {
                            faces = faces.toMutableList().also { it.removeAt(index) }
                        })
                        HorizontalDivider()
                    }
                    OutlinedButton(onClick = { fonts.firstOrNull()?.let { faces = faces + Face(it.id) } }, enabled = fonts.isNotEmpty()) {
                        Icon(Icons.Default.Add, null)
                        Text(stringResource(R.string.role_add_face))
                    }
                    Text(stringResource(R.string.role_axes), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.role_axes_help), style = MaterialTheme.typography.bodySmall)
                    Widgets.Axes(axes) { axes = it }
                    if (original != null) {
                        OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.role_delete)) }
                    }
                }
            }
        }
        if (confirmDelete) {
            Widgets.Confirm(stringResource(R.string.role_delete), stringResource(R.string.role_delete_text), stringResource(R.string.remove), onDelete) { confirmDelete = false }
        }
    }

    @Composable
    fun FaceEditor(face: Face, fonts: List<LibraryFont>, onChange: (Face) -> Unit, onRemove: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Widgets.Dropdown(
                    label = stringResource(R.string.face_font),
                    options = fonts,
                    selected = fonts.firstOrNull { it.id == face.font },
                    describe = FontsScreen::label,
                    onSelect = { onChange(face.copy(font = it.id)) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, stringResource(R.string.remove)) }
            }
            Widgets.Segments(
                options = listOf(null, FaceStyle.NORMAL, FaceStyle.ITALIC),
                selected = face.style,
                describe = { style ->
                    stringResource(
                        when (style) {
                            null -> R.string.style_automatic
                            FaceStyle.NORMAL -> R.string.style_normal
                            FaceStyle.ITALIC -> R.string.style_italic
                        },
                    )
                },
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.face_axes), style = MaterialTheme.typography.bodySmall)
            Widgets.Axes(face.axes) { onChange(face.copy(axes = it)) }
        }
    }
}
