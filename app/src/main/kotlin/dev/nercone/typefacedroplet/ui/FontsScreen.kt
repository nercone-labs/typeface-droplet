package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import dev.nercone.typefacedroplet.engine.FaceInfo
import dev.nercone.typefacedroplet.engine.FaceStyle
import dev.nercone.typefacedroplet.library.LibraryFont

/** The fonts: one font per category and style, and the library they are chosen from. */
object FontsScreen {
    fun categoryName(category: Category): Int = when (category) {
        Category.SANS -> R.string.category_sans
        Category.SERIF -> R.string.category_serif
        Category.MONO -> R.string.category_mono
    }

    fun categoryHelp(category: Category): Int = when (category) {
        Category.SANS -> R.string.category_sans_help
        Category.SERIF -> R.string.category_serif_help
        Category.MONO -> R.string.category_mono_help
    }

    /** The generic family of the system for a category, shown when no font is chosen. */
    fun systemFamily(category: Category): FontFamily = when (category) {
        Category.SANS -> FontFamily.SansSerif
        Category.SERIF -> FontFamily.Serif
        Category.MONO -> FontFamily.Monospace
    }

    fun styleName(style: FaceStyle): Int = when (style) {
        FaceStyle.NORMAL -> R.string.style_normal
        FaceStyle.ITALIC -> R.string.style_italic
    }

    /** A one-line description of the style of a face: weight range or weight, italic, axes. */
    @Composable
    fun describe(face: FaceInfo): String {
        val weight = face.weightAxis?.let { stringResource(R.string.font_variable, it.minimum.toInt(), it.maximum.toInt()) }
            ?: stringResource(R.string.font_static, face.weight)
        val style = stringResource(if (face.italic) R.string.style_italic else R.string.style_normal)
        val axes = face.axes.filter { !it.hidden && it.tag != "wght" }.joinToString(" ") { it.tag }
        return listOf(weight, style, axes).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    @Composable
    fun Content(state: UiState, config: Config?, fonts: List<LibraryFont>, settings: Settings.Values, model: AppViewModel) {
        var picking by remember { mutableStateOf<Slot?>(null) }
        var details by remember { mutableStateOf<String?>(null) }
        val selection = config?.let { Selection.of(it, fonts) } ?: Selection()
        LazyColumn(
            contentPadding = PaddingValues(Widgets.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (settings.customized) {
                item { Widgets.Notice(Widgets.Tone.INFORMATION, stringResource(R.string.customized_title), stringResource(R.string.customized_fonts_text)) }
            }
            item { Text(stringResource(R.string.fonts_help), style = MaterialTheme.typography.bodyMedium, color = Widgets.secondary) }
            items(Category.entries) { category ->
                val available = !settings.customized || config?.role(category.role) != null
                CategoryCard(category, selection, fonts, available, model) { picking = it }
            }
            item { Widgets.Header(stringResource(R.string.library), Modifier.padding(top = 8.dp)) }
            if (fonts.isEmpty()) item { Text(stringResource(if (state.ready) R.string.fonts_empty else R.string.loading)) }
            itemsIndexed(fonts, key = { _, font -> font.id }) { index, font ->
                LibraryItem(index, fonts.size, font, model) { details = font.id }
            }
            // Room for the floating action button.
            item { Spacer(Modifier.height(72.dp)) }
        }
        picking?.let { slot ->
            // A role edited by hand keeps at least one face.
            val other = Slot(slot.category, FaceStyle.entries.first { it != slot.style })
            val allowNone = !settings.customized || selection.font(other) != null
            Picker(slot, selection.font(slot), fonts, allowNone, model, onPick = { model.select(slot, it) }) { picking = null }
        }
        details?.let { id -> fonts.firstOrNull { it.id == id }?.let { font -> Details(font, config, model) { details = null } } }
    }

    @Composable
    fun CategoryCard(category: Category, selection: Selection, fonts: List<LibraryFont>, available: Boolean, model: AppViewModel, onPick: (Slot) -> Unit) {
        val upright = fonts.firstOrNull { it.id == selection.font(Slot(category, FaceStyle.NORMAL)) }
        val family = Previews.family(model.droplet.library, upright, italic = false)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(categoryName(category)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
                Text(stringResource(categoryHelp(category)), style = MaterialTheme.typography.bodySmall, color = Widgets.secondary, modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    stringResource(R.string.specimen_text),
                    fontSize = 22.sp,
                    fontFamily = family ?: systemFamily(category),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                Widgets.Group {
                    FaceStyle.entries.forEachIndexed { index, style ->
                        val slot = Slot(category, style)
                        val font = fonts.firstOrNull { it.id == selection.font(slot) }
                        Widgets.Item(
                            index = index,
                            count = FaceStyle.entries.size,
                            headline = stringResource(styleName(style)),
                            supporting = when {
                                !available -> stringResource(R.string.category_unused)
                                font != null -> font.label
                                style == FaceStyle.ITALIC && upright != null -> stringResource(R.string.font_slanted)
                                else -> stringResource(R.string.font_system)
                            },
                            enabled = available,
                            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            onClick = { onPick(slot) },
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun LibraryItem(index: Int, count: Int, font: LibraryFont, model: AppViewModel, onClick: () -> Unit) {
        val family = Previews.family(model.droplet.library, font)
        SegmentedListItem(
            onClick = onClick,
            shapes = ListItemDefaults.segmentedShapes(index, count),
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.specimen_short), fontFamily = family ?: FontFamily.Default, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    font.face?.let { Text(describe(it), style = MaterialTheme.typography.bodySmall) }
                }
            },
            overlineContent = { Text(stringResource(if (font.origin == LibraryFont.Origin.BUNDLED) R.string.font_bundled else R.string.font_imported)) },
        ) { Text(font.label) }
    }

    /** Chooses the font of a slot: every font of the library, with a preview, or none. */
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun Picker(slot: Slot, selected: String?, fonts: List<LibraryFont>, allowNone: Boolean, model: AppViewModel, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
        val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
        val italic = slot.style == FaceStyle.ITALIC
        // Fonts of the requested style first.
        val ordered = fonts.sortedBy { it.italic != italic }
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Text(
                "${stringResource(categoryName(slot.category))} · ${stringResource(styleName(slot.style))}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                val offset = if (allowNone) 1 else 0
                val count = ordered.size + offset
                if (allowNone) item {
                    SegmentedListItem(
                        selected = selected == null,
                        onClick = {
                            onPick(null)
                            onDismiss()
                        },
                        shapes = ListItemDefaults.segmentedShapes(0, count),
                        supportingContent = { Text(stringResource(if (italic) R.string.font_none_italic else R.string.font_none_upright)) },
                        trailingContent = if (selected == null) ({ Icon(Icons.Default.Check, null) }) else null,
                    ) { Text(stringResource(R.string.font_none)) }
                }
                itemsIndexed(ordered, key = { _, font -> font.id }) { index, font ->
                    val family = Previews.family(model.droplet.library, font)
                    SegmentedListItem(
                        selected = font.id == selected,
                        onClick = {
                            onPick(font.id)
                            onDismiss()
                        },
                        shapes = ListItemDefaults.segmentedShapes(index + offset, count),
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    stringResource(R.string.specimen_short),
                                    fontFamily = family ?: FontFamily.Default,
                                    fontStyle = if (font.italic) FontStyle.Italic else FontStyle.Normal,
                                    fontSize = 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                font.face?.let { Text(describe(it), style = MaterialTheme.typography.bodySmall) }
                            }
                        },
                        trailingContent = if (font.id == selected) ({ Icon(Icons.Default.Check, null) }) else null,
                    ) { Text(font.label) }
                }
            }
        }
    }

    /** Everything about one font: preview, facts, the family name written into it, removal. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Details(font: LibraryFont, config: Config?, model: AppViewModel, onDismiss: () -> Unit) {
        val family = Previews.family(model.droplet.library, font)
        val roles = config?.roles.orEmpty().filter { role -> role.faces.any { it.font == font.id } }.map { it.name }
        var confirm by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(font.label, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.specimen_text), fontSize = 28.sp, lineHeight = 36.sp, fontFamily = family ?: FontFamily.Default)
                Text(stringResource(R.string.specimen_alphabet), fontSize = 18.sp, fontFamily = family ?: FontFamily.Default)
                val face = font.face
                val facts = buildList {
                    add(stringResource(R.string.font_format, font.info.format.uppercase(), font.info.size / 1_048_576.0))
                    if (font.info.faces.size > 1) add(stringResource(R.string.font_faces, font.info.faces.size))
                    face?.let { add(describe(it)) }
                    face?.postscriptName?.let { add(stringResource(R.string.font_postscript, it)) }
                    face?.axes?.forEach { axis -> add(stringResource(R.string.font_axis, axis.tag, axis.minimum, axis.default, axis.maximum)) }
                    add(stringResource(R.string.font_file, font.name))
                    add(if (roles.isEmpty()) stringResource(R.string.font_unused) else stringResource(R.string.font_used_by, roles.joinToString()))
                }
                facts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Widgets.secondary) }
                val written = model.settings.value.names[font.id]
                val current = config?.font(font.id)?.rename
                var name by remember(font.id) { mutableStateOf(written.orEmpty()) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { text ->
                        name = text.take(Config.MAX_NAME_LENGTH)
                        model.rename(font.id, name.trim().ifEmpty { null })
                    },
                    placeholder = current?.let { { Text(it) } },
                    label = { Text(stringResource(R.string.font_rename)) },
                    supportingText = { Text(stringResource(R.string.font_rename_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (font.origin == LibraryFont.Origin.IMPORTED) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FilledTonalButton(onClick = { confirm = true }, enabled = roles.isEmpty()) { Text(stringResource(R.string.remove)) }
                    }
                    if (roles.isNotEmpty()) Text(stringResource(R.string.font_remove_in_use), style = MaterialTheme.typography.bodySmall, color = Widgets.secondary)
                }
            }
        }
        if (confirm) {
            Widgets.Confirm(stringResource(R.string.font_remove_title), stringResource(R.string.font_remove_text), stringResource(R.string.remove), {
                model.removeFont(font.id)
                onDismiss()
            }) { confirm = false }
        }
    }
}
