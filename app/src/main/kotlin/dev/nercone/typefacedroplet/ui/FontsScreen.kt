package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.FaceInfo
import dev.nercone.typefacedroplet.library.FontLibrary
import dev.nercone.typefacedroplet.library.LibraryFont

/** The font library: bundled and imported fonts, with previews, details and family names. */
object FontsScreen {
    @Composable
    fun Content(fonts: List<LibraryFont>, config: Config?, library: FontLibrary, model: AppViewModel, contentPadding: PaddingValues) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = Widgets.SCREEN_PADDING,
                end = Widgets.SCREEN_PADDING,
                top = Widgets.SCREEN_PADDING,
                bottom = Widgets.SCREEN_PADDING + contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(Widgets.SPACING),
        ) {
            if (fonts.isEmpty()) {
                item { Text(stringResource(R.string.fonts_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(fonts, key = { it.id }) { font ->
                val roles = config?.roles.orEmpty().filter { role -> role.faces.any { it.font == font.id } }.map { it.name }
                val rename = config?.fonts?.firstOrNull { it.id == font.id }?.rename
                Item(font, library, roles, rename, onRename = { name ->
                    model.updateConfig { current ->
                        current.copy(fonts = current.fonts.map { if (it.id == font.id) it.copy(rename = name) else it })
                    }
                }, onRemove = { model.removeFont(font.id) })
            }
        }
    }

    /** The name of a font in pickers: family and subfamily of its first face, which tells upright and italic apart. */
    fun label(font: LibraryFont): String =
        font.info.faces.firstOrNull()?.let { face -> listOfNotNull(face.family, face.subfamily).joinToString(" ") }?.ifEmpty { null } ?: font.name

    /** A one-line description of the style of a face: weight range or weight, italic, axes. */
    @Composable
    fun describe(face: FaceInfo): String {
        val weight = face.weightAxis?.let { stringResource(R.string.font_variable, it.minimum.toInt(), it.maximum.toInt()) }
            ?: stringResource(R.string.font_static, face.weight)
        val style = if (face.italic) stringResource(R.string.style_italic) else stringResource(R.string.style_normal)
        val axes = face.axes.filter { !it.hidden && it.tag != "wght" }.joinToString(" ") { it.tag }
        return listOf(weight, style, axes).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Item(font: LibraryFont, library: FontLibrary, roles: List<String>, rename: String?, onRename: (String?) -> Unit, onRemove: () -> Unit) {
        var expanded by rememberSaveable(font.id) { mutableStateOf(false) }
        val face = font.info.faces.firstOrNull()
        val typeface = remember(font.id) { library.typeface(font) }
        Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label(font), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.font_sample),
                    fontSize = 22.sp,
                    fontFamily = typeface?.let { FontFamily(it) } ?: FontFamily.Default,
                )
                face?.let { Text(describe(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = {}, label = {
                        Text(stringResource(if (font.origin == LibraryFont.Origin.BUNDLED) R.string.font_bundled else R.string.font_imported))
                    })
                    AssistChip(onClick = {}, label = { Text(font.info.format.uppercase()) })
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.font_size, font.info.size / 1_048_576.0)) })
                    if (font.info.faces.size > 1) AssistChip(onClick = {}, label = { Text(stringResource(R.string.font_faces, font.info.faces.size)) })
                }
                if (!expanded) return@Column
                Text(stringResource(R.string.font_file, font.name), style = MaterialTheme.typography.bodySmall)
                face?.postscriptName?.let { Text(stringResource(R.string.font_postscript, it), style = MaterialTheme.typography.bodySmall) }
                face?.axes?.forEach { axis ->
                    Text(stringResource(R.string.font_axis, axis.tag, axis.minimum, axis.default, axis.maximum), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (roles.isEmpty()) stringResource(R.string.font_unused) else stringResource(R.string.font_used_by, roles.joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                )
                var name by remember(font.id, rename) { mutableStateOf(rename.orEmpty()) }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(Config.MAX_NAME_LENGTH)
                        onRename(name.trim().ifEmpty { null })
                    },
                    label = { Text(stringResource(R.string.font_rename)) },
                    supportingText = { Text(stringResource(R.string.font_rename_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (font.origin == LibraryFont.Origin.IMPORTED) {
                    var confirm by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { confirm = true }, enabled = roles.isEmpty()) { Text(stringResource(R.string.remove)) }
                    }
                    if (confirm) {
                        Widgets.Confirm(stringResource(R.string.font_remove_title), stringResource(R.string.font_remove_text), stringResource(R.string.remove), onRemove) { confirm = false }
                    }
                }
            }
        }
    }
}
