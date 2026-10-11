package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nercone.typefacedroplet.R

/** User interface parts shared by the screens. */
object Widgets {
    val SCREEN_PADDING = 16.dp
    val SPACING = 12.dp

    /** A card with a title, used for every group of content. */
    @Composable
    fun Section(
        title: String,
        modifier: Modifier = Modifier,
        container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    /** A dropdown to pick one of `options`, shown with `label`. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun <T> Dropdown(label: String, options: List<T>, selected: T?, describe: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
            OutlinedTextField(
                value = selected?.let(describe).orEmpty(),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(describe(option)) }, onClick = {
                        onSelect(option)
                        expanded = false
                    })
                }
            }
        }
    }

    /** A row of segmented buttons to pick one of `options`. */
    @Composable
    fun <T> Segments(options: List<T>, selected: T, describe: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
        SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(describe(option)) }
            }
        }
    }

    /** A dialog that asks to confirm a destructive or far-reaching action. */
    @Composable
    fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    onConfirm()
                    onDismiss()
                }) { Text(confirm) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    /**
     * An editor of fixed variation axis values (`tag` → value). Tags `wght` and `ital` are
     * refused, because weights and styles control them.
     */
    @Composable
    fun Axes(axes: Map<String, Float>, onChange: (Map<String, Float>) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            axes.forEach { (tag, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$tag = $value", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { onChange(axes - tag) }) { Icon(Icons.Default.Close, stringResource(R.string.remove)) }
                }
            }
            var tag by remember { mutableStateOf("") }
            var value by remember { mutableStateOf("") }
            val number = value.toFloatOrNull()
            val valid = tag.length in 1..4 && tag.all { it.code in 0x20..0x7E } && tag != "wght" && tag != "ital" && number != null && number.isFinite()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tag, { tag = it.take(4) }, label = { Text(stringResource(R.string.axis_tag)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value,
                    { value = it },
                    label = { Text(stringResource(R.string.axis_value)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                IconButton(enabled = valid, onClick = {
                    onChange(axes + (tag to (number ?: 0f)))
                    tag = ""
                    value = ""
                }) { Icon(Icons.Default.Add, stringResource(R.string.add)) }
            }
        }
    }

    /** An editable list of strings, such as packages or components. */
    @Composable
    fun StringList(items: List<String>, label: String, describe: (String) -> String, validate: (String) -> Boolean, onChange: (List<String>) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(describe(item), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { onChange(items - item) }) { Icon(Icons.Default.Close, stringResource(R.string.remove)) }
                }
            }
            var text by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it.trim() }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(enabled = validate(text) && text !in items, onClick = {
                    onChange(items + text)
                    text = ""
                }) { Icon(Icons.Default.Add, stringResource(R.string.add)) }
            }
        }
    }
}
