package dev.nercone.typefacedroplet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontFamily
import dev.nercone.typefacedroplet.library.FontLibrary
import dev.nercone.typefacedroplet.library.LibraryFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Font families for previews, loaded off the main thread: fonts with large character sets take a while to load. */
object Previews {
    /** The family of `font` in the given style, or `null` while it loads or if it cannot be loaded. */
    @Composable
    fun family(library: FontLibrary, font: LibraryFont?, italic: Boolean = font?.italic == true): FontFamily? {
        val family by produceState<FontFamily?>(null, font?.id, italic) {
            value = font?.let { withContext(Dispatchers.IO) { library.typeface(it, italic = italic)?.let { typeface -> FontFamily(typeface) } } }
        }
        return family
    }
}
