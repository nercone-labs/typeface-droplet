package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Box
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
import dev.nercone.typefacedroplet.engine.FaceStyle
import dev.nercone.typefacedroplet.library.LibraryFont
import dev.nercone.typefacedroplet.ui.Notices.notices

/**
 * The fonts: one font per category and style, and the library they are chosen from. In a wide
 * window the categories stand side by side.
 */
object FontsScreen {
    @Composable
    fun Content(data: ScreenData, model: AppViewModel) {
        val config = data.config
        val fonts = data.fonts
        val settings = data.settings
        var picking by rememberSaveable(stateSaver = SLOT_SAVER) { mutableStateOf<Slot?>(null) }
        var details by rememberSaveable { mutableStateOf<String?>(null) }
        val selection = config?.let { Selection.of(it, fonts) } ?: Selection()
        val available = { category: Category -> config != null && (!settings.customized || config.role(category.role) != null) }
        Widgets.Screen(columnsWidth = 1100.dp, spacing = 12.dp, bottom = Widgets.FLOATING_BUTTON_ROOM) { width ->
            notices(data, model)
            if (settings.customized) {
                item(key = "customized") { Widgets.Notice(Widgets.Tone.INFORMATION, stringResource(R.string.customized_title), stringResource(R.string.customized_fonts_text)) }
            }
            item(key = "help") { Widgets.Help(stringResource(R.string.fonts_help)) }
            if (width >= Widgets.TWO_COLUMNS) {
                item(key = "categories") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Category.entries.forEach { category ->
                            Box(Modifier.weight(1f)) { CategoryCard(category, selection, fonts, available(category), model) { picking = it } }
                        }
                    }
                }
            } else {
                items(Category.entries, key = { "category:${it.name}" }) { category ->
                    CategoryCard(category, selection, fonts, available(category), model) { picking = it }
                }
            }
            item(key = "library") { Widgets.Header(stringResource(R.string.library), Modifier.padding(top = 8.dp)) }
            if (fonts.isEmpty()) item(key = "empty") { Widgets.Help(stringResource(if (data.state.ready) R.string.fonts_empty else R.string.loading)) }
            item(key = "fonts") {
                Widgets.Group {
                    fonts.forEachIndexed { index, font -> LibraryItem(index, fonts.size, font, model) { details = font.id } }
                }
            }
        }
        picking?.let { slot ->
            // A role edited by hand keeps at least one face.
            val other = Slot(slot.category, FaceStyle.entries.first { it != slot.style })
            val allowNone = !settings.customized || selection.font(other) != null
            Picker(slot, selection.font(slot), fonts, allowNone, model, onPick = { model.select(slot, it) }) { picking = null }
        }
        // The font may not be in the library yet (after the process was recreated, until the
        // library is loaded) or any more (removed); the sheet shows once it is there.
        details?.let { id -> fonts.firstOrNull { it.id == id } }?.let { font -> Details(font, config, settings, model) { details = null } }
    }

    /** Saves the slot being picked across configuration changes, as "category:style". */
    val SLOT_SAVER = Saver<Slot?, String>(
        save = { slot -> slot?.let { "${it.category.name}:${it.style.name}" } ?: "" },
        restore = { text ->
            text.split(':').takeIf { it.size == 2 }?.let { (category, style) ->
                runCatching { Slot(Category.valueOf(category), FaceStyle.valueOf(style)) }.getOrNull()
            }
        },
    )

    @Composable
    fun CategoryCard(category: Category, selection: Selection, fonts: List<LibraryFont>, available: Boolean, model: AppViewModel, onPick: (Slot) -> Unit) {
        val upright = fonts.firstOrNull { it.id == selection.font(Slot(category, FaceStyle.NORMAL)) }
        val family = Previews.family(model.droplet.library, upright, italic = false)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Labels.category(category), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
                Text(Labels.categoryHelp(category), style = MaterialTheme.typography.bodySmall, color = Widgets.secondary, modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    stringResource(R.string.specimen_text),
                    fontSize = 22.sp,
                    fontFamily = family ?: Previews.systemFamily(category),
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
                            headline = Labels.style(style),
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
                    font.face?.let { Text(Labels.face(it), style = MaterialTheme.typography.bodySmall) }
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
                "${Labels.category(slot.category)} · ${Labels.style(slot.style)}",
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
                                font.face?.let { Text(Labels.face(it), style = MaterialTheme.typography.bodySmall) }
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
    fun Details(font: LibraryFont, config: Config?, settings: Settings.Values, model: AppViewModel, onDismiss: () -> Unit) {
        val family = Previews.family(model.droplet.library, font)
        val roles = config?.roles.orEmpty().filter { role -> role.faces.any { it.font == font.id } }.map { it.name }
        var confirm by rememberSaveable { mutableStateOf(false) }
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
                    face?.let { add(Labels.face(it)) }
                    face?.postscriptName?.let { add(stringResource(R.string.font_postscript, it)) }
                    face?.axes?.forEach { axis -> add(stringResource(R.string.font_axis, axis.tag, axis.minimum, axis.default, axis.maximum)) }
                    add(stringResource(R.string.font_file, font.name))
                    add(if (roles.isEmpty()) stringResource(R.string.font_unused) else stringResource(R.string.font_used_by, roles.joinToString()))
                }
                facts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Widgets.secondary) }
                val written = settings.names[font.id]
                val current = config?.font(font.id)?.rename
                var name by rememberSaveable(font.id) { mutableStateOf(written.orEmpty()) }
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
