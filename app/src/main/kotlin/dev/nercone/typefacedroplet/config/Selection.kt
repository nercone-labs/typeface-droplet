package dev.nercone.typefacedroplet.config

import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.Face
import dev.nercone.typefacedroplet.engine.FaceStyle
import dev.nercone.typefacedroplet.engine.Preset
import dev.nercone.typefacedroplet.engine.Role
import dev.nercone.typefacedroplet.library.LibraryFont

/**
 * A generic family that the recommended configuration replaces, through the role of the same name.
 * `family` is the name the recommended configuration writes into the fonts of the role
 * (`Preset::SANS_NAME` and so on): applications that look fonts up by name, such as Firefox,
 * find the replacement only under the name of the family it replaces.
 */
enum class Category(val role: String, val family: String) {
    SANS("sans", "Roboto"),
    SERIF("serif", "Noto Serif"),
    MONO("mono", "Droid Sans Mono"),
}

/** A place for one font: a category in one style. */
data class Slot(val category: Category, val style: FaceStyle) {
    companion object {
        val ALL: List<Slot> = Category.entries.flatMap { category -> FaceStyle.entries.map { style -> Slot(category, style) } }
    }
}

/**
 * The fonts chosen for the slots, by font id. This is all a user has to decide: the
 * recommended configuration derives the roles and rules from it (see `droplet preset`).
 */
data class Selection(val fonts: Map<Slot, String> = emptyMap()) {
    fun font(slot: Slot): String? = fonts[slot]

    fun with(slot: Slot, font: String?): Selection = Selection(if (font == null) fonts - slot else fonts + (slot to font))

    /** The input of `droplet preset`, with the font entries (and their family names) of `config`. */
    fun preset(config: Config): Preset {
        fun entry(category: Category, style: FaceStyle) = font(Slot(category, style))?.let { id -> config.font(id) }
        return Preset(
            sans = entry(Category.SANS, FaceStyle.NORMAL),
            sansItalic = entry(Category.SANS, FaceStyle.ITALIC),
            serif = entry(Category.SERIF, FaceStyle.NORMAL),
            serifItalic = entry(Category.SERIF, FaceStyle.ITALIC),
            mono = entry(Category.MONO, FaceStyle.NORMAL),
            monoItalic = entry(Category.MONO, FaceStyle.ITALIC),
        )
    }

    /**
     * Puts the chosen fonts into the roles of the categories of a configuration whose roles and
     * rules were edited by hand. Only the font of the first face of each style changes; its
     * weight, axes and the other faces stay as written. A style without a font loses its faces,
     * unless that would leave the role without any face. Roles that do not exist stay absent.
     */
    fun applyTo(config: Config, library: List<LibraryFont>): Config = Category.entries.fold(config) { current, category ->
        val role = current.role(category.role) ?: return@fold current
        var faces = role.faces
        for (style in FaceStyle.entries) {
            val chosen = font(Slot(category, style))
            val index = faces.indexOfFirst { styleOf(it, library) == style }
            faces = when {
                chosen == null -> faces.filterNot { styleOf(it, library) == style }
                index < 0 -> faces + Face(chosen, style)
                faces[index].font == chosen -> faces
                else -> faces.toMutableList().also { it[index] = it[index].copy(font = chosen, style = style) }
            }
        }
        if (faces.isEmpty()) current else current.replaceRole(role.name, role.copy(faces = faces))
    }

    companion object {
        /** The bundled font of each slot, by asset name. */
        val BUNDLED: Map<Slot, String> = mapOf(
            Slot(Category.SANS, FaceStyle.NORMAL) to "Sans.ttf",
            Slot(Category.SANS, FaceStyle.ITALIC) to "Sans-Italic.ttf",
            Slot(Category.SERIF, FaceStyle.NORMAL) to "Serif.ttf",
            Slot(Category.SERIF, FaceStyle.ITALIC) to "Serif-Italic.ttf",
            Slot(Category.MONO, FaceStyle.NORMAL) to "Mono.ttf",
            Slot(Category.MONO, FaceStyle.ITALIC) to "Mono-Italic.ttf",
        )

        /** The bundled fonts in their slots. */
        fun recommended(library: List<LibraryFont>): Selection = Selection(
            BUNDLED.mapNotNull { (slot, name) ->
                library.firstOrNull { it.origin == LibraryFont.Origin.BUNDLED && it.name == name }?.let { slot to it.id }
            }.toMap(),
        )

        /**
         * Names the fonts of a configuration whose roles and rules were edited by hand the way the
         * recommended configuration names them: a font keeps the name the user wrote (`names`), and
         * otherwise a font of the role of a category takes the family name of the first such
         * category. Any other font keeps its name, unless it is one of those family names, which
         * only this naming gives.
         *
         * Without it, the fonts chosen for a role edited by hand kept their own family names:
         * Firefox, which reads the family names from the font files, then found no Roboto at all
         * and fell back to the first family of its list, such as Carrois Gothic SC.
         */
        fun name(config: Config, names: Map<String, String>): Config {
            val families = Category.entries.map { it.family }.toSet()
            return config.copy(
                fonts = config.fonts.map { entry ->
                    val category = Category.entries.firstOrNull { category -> config.role(category.role)?.faces?.any { it.font == entry.id } == true }
                    entry.copy(rename = names[entry.id] ?: category?.family ?: entry.rename?.takeUnless { it in families })
                },
            )
        }

        /** The style of a face: its own, or else the style of its font. */
        fun styleOf(face: Face, library: List<LibraryFont>): FaceStyle =
            face.style ?: if (library.firstOrNull { it.id == face.font }?.italic == true) FaceStyle.ITALIC else FaceStyle.NORMAL

        /**
         * The selection a configuration makes: the faces of the roles named after the categories.
         * The first face of each style counts.
         */
        fun of(config: Config, library: List<LibraryFont>): Selection = Selection(
            Category.entries.flatMap { category ->
                val role: Role = config.role(category.role) ?: return@flatMap emptyList()
                role.faces.map { face -> Slot(category, styleOf(face, library)) to face.font }.distinctBy { it.first }
            }.toMap(),
        )
    }
}
