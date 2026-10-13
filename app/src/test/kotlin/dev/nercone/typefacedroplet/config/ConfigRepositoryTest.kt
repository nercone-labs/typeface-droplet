package dev.nercone.typefacedroplet.config

import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.FontEntry
import dev.nercone.typefacedroplet.engine.FontSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** The family names written into fonts: only those the user wrote, so that the engine names the other copies. */
class ConfigRepositoryTest {
    fun entry(id: String, rename: String? = null) = FontEntry(id, FontSource("/fonts/$id.ttf"), rename = rename)

    @Test
    fun fontsKeepOnlyTheNamesTheUserWrote() {
        val config = Config(fonts = listOf(entry("a", "Roboto"), entry("b", "Noto Serif"), entry("c", "Droid Sans Mono"), entry("d", "Kept"), entry("e"), entry("f", "Old")))
        val named = ConfigRepository.name(config, mapOf("b" to "Noto Serif", "e" to "Mine", "f" to "New"))
        val renames = named.fonts.associate { it.id to it.rename }
        assertEquals(
            mapOf("a" to null, "b" to "Noto Serif", "c" to null, "d" to "Kept", "e" to "Mine", "f" to "New"),
            renames,
        )
        assertEquals(config.copy(fonts = named.fonts), named)
    }
}
