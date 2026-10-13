package dev.nercone.typefacedroplet.config

import dev.nercone.typefacedroplet.engine.Action
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.Face
import dev.nercone.typefacedroplet.engine.FaceInfo
import dev.nercone.typefacedroplet.engine.FaceStyle
import dev.nercone.typefacedroplet.engine.FontEntry
import dev.nercone.typefacedroplet.engine.FontInfo
import dev.nercone.typefacedroplet.engine.FontSource
import dev.nercone.typefacedroplet.engine.Matcher
import dev.nercone.typefacedroplet.engine.Role
import dev.nercone.typefacedroplet.engine.Rule
import dev.nercone.typefacedroplet.library.LibraryFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The selection is what the user decides in the fonts screen: one font per category and style.
 * The categories are the roles of the recommended configuration (`droplet preset`): sans, serif and mono.
 */
class SelectionTest {
    fun font(id: String, italic: Boolean, origin: LibraryFont.Origin = LibraryFont.Origin.IMPORTED, name: String = "$id.ttf") = LibraryFont(
        id = id,
        source = FontSource("/fonts/$id.ttf"),
        info = FontInfo("ttf", 1, listOf(FaceInfo(0, "Family $id", if (italic) "Italic" else "Regular", null, null, 400, italic, "truetype", emptyList(), 0))),
        origin = origin,
        name = name,
    )

    @Test
    fun categoriesAreTheRolesOfThePreset() {
        assertEquals(listOf("sans", "serif", "mono"), Category.entries.map { it.role })
        assertEquals(6, Slot.ALL.size)
    }

    @Test
    fun selectionsAreReadFromTheRolesOfTheCategories() {
        val library = listOf(font("a", false), font("b", true), font("c", false))
        val config = Config(
            roles = listOf(
                Role("sans", listOf(Face("a"), Face("b"))),
                Role("mono", listOf(Face("c", FaceStyle.ITALIC))),
                Role("other", listOf(Face("a"))),
            ),
        )
        val selection = Selection.of(config, library)
        assertEquals("a face without a style takes the style of its font", "a", selection.font(Slot(Category.SANS, FaceStyle.NORMAL)))
        assertEquals("b", selection.font(Slot(Category.SANS, FaceStyle.ITALIC)))
        assertEquals("an explicit style wins", "c", selection.font(Slot(Category.MONO, FaceStyle.ITALIC)))
        assertNull(selection.font(Slot(Category.MONO, FaceStyle.NORMAL)))
        assertNull(selection.font(Slot(Category.SERIF, FaceStyle.NORMAL)))
        assertEquals(3, selection.fonts.size)
    }

    @Test
    fun presetsUseTheFontEntriesOfTheConfiguration() {
        val entry = FontEntry("a", FontSource("/fonts/a.ttf"), rename = "Mine")
        val config = Config(fonts = listOf(entry, FontEntry("b", FontSource("/fonts/b.ttf"))))
        val preset = Selection().with(Slot(Category.SANS, FaceStyle.NORMAL), "a").with(Slot(Category.MONO, FaceStyle.ITALIC), "b").preset(config)
        assertEquals(entry, preset.sans)
        assertEquals("b", preset.monoItalic?.id)
        assertNull(preset.sansItalic)
        assertNull(preset.serif)
        assertNull("a font missing from the configuration is not in the preset", Selection().with(Slot(Category.SERIF, FaceStyle.NORMAL), "z").preset(config).serif)
        assertEquals(Selection(), Selection().with(Slot(Category.SANS, FaceStyle.NORMAL), "a").with(Slot(Category.SANS, FaceStyle.NORMAL), null))
    }

    @Test
    fun handEditedConfigurationsKeepWhatTheUserWrote() {
        val library = listOf(font("a", false), font("i", true), font("s", false))
        val config = Config(
            roles = listOf(
                Role("sans", listOf(Face("a", weight = 500, axes = mapOf("GRAD" to 50f)), Face("i"), Face("a", FaceStyle.NORMAL, weight = 700)), axes = mapOf("opsz" to 14f)),
                Role("serif", listOf(Face("s"))),
            ),
            rules = listOf(Rule(Matcher.Family("sans-serif"), Action.Replace("sans")), Rule(Matcher.Family("serif"), Action.Replace("serif"))),
        )
        val chosen = Selection.of(config, library).with(Slot(Category.SANS, FaceStyle.NORMAL), "x").with(Slot(Category.MONO, FaceStyle.NORMAL), "m")
        val result = chosen.applyTo(config, library)
        assertEquals(
            "only the font of the first upright face changes; its weight and axes, and the other faces, stay",
            Role("sans", listOf(Face("x", FaceStyle.NORMAL, weight = 500, axes = mapOf("GRAD" to 50f)), Face("i"), Face("a", FaceStyle.NORMAL, weight = 700)), axes = mapOf("opsz" to 14f)),
            result.role("sans"),
        )
        assertNull("roles that do not exist are not created", result.role("mono"))
        assertEquals(config.rules, result.rules)

        val withoutItalic = Selection.of(config, library).with(Slot(Category.SANS, FaceStyle.ITALIC), null).applyTo(config, library)
        assertEquals("a style without a font loses its faces", listOf("a", "a"), withoutItalic.role("sans")?.faces?.map { it.font })
        val added = Selection.of(config, library).with(Slot(Category.SERIF, FaceStyle.ITALIC), "i").applyTo(config, library)
        assertEquals(listOf(Face("s"), Face("i", FaceStyle.ITALIC)), added.role("serif")?.faces)
        val emptied = Selection.of(config, library).with(Slot(Category.SERIF, FaceStyle.NORMAL), null).applyTo(config, library)
        assertEquals("a role is never left without faces", config.role("serif"), emptied.role("serif"))
        assertEquals(config.rules, emptied.rules)
    }

    @Test
    fun handEditedConfigurationsNameTheirFontsLikeThePreset() {
        fun entry(id: String, rename: String? = null) = FontEntry(id, FontSource("/fonts/$id.ttf"), rename = rename)
        val config = Config(
            fonts = listOf(entry("a"), entry("i", "Old"), entry("s"), entry("m"), entry("o", "Roboto"), entry("k", "Kept"), entry("n")),
            roles = listOf(
                Role("sans", listOf(Face("a"), Face("i", FaceStyle.ITALIC))),
                Role("serif", listOf(Face("s"))),
                Role("mono", listOf(Face("m"), Face("a"))),
                Role("other", listOf(Face("o"), Face("k"))),
            ),
        )
        val named = Selection.name(config, mapOf("s" to "Mine", "n" to "Unused"))
        val renames = named.fonts.associate { it.id to it.rename }
        assertEquals("a font of a category takes the family it replaces", "Roboto", renames["a"])
        assertEquals("even over a name of an earlier version", "Roboto", renames["i"])
        assertEquals("the name the user wrote wins", "Mine", renames["s"])
        assertEquals("Droid Sans Mono", renames["m"])
        assertNull("a font that left its category loses the name of that category", renames["o"])
        assertEquals("other fonts keep their names", "Kept", renames["k"])
        assertEquals("Unused", renames["n"])
        assertEquals(config.copy(fonts = named.fonts), named)
        assertEquals(listOf("Roboto", "Noto Serif", "Droid Sans Mono"), Category.entries.map { it.family })
    }

    @Test
    fun theRecommendedSelectionUsesTheBundledFonts() {
        val library = Selection.BUNDLED.entries.map { (slot, name) ->
            font("${slot.category.role}-${slot.style}", slot.style == FaceStyle.ITALIC, LibraryFont.Origin.BUNDLED, name)
        } + font("imported", false, name = "Sans.ttf")
        val selection = Selection.recommended(library)
        assertEquals(6, selection.fonts.size)
        assertEquals("sans-NORMAL", selection.font(Slot(Category.SANS, FaceStyle.NORMAL)))
        assertEquals("mono-ITALIC", selection.font(Slot(Category.MONO, FaceStyle.ITALIC)))
    }
}
