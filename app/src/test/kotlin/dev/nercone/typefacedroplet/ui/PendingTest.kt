package dev.nercone.typefacedroplet.ui

import dev.nercone.typefacedroplet.config.Settings
import dev.nercone.typefacedroplet.engine.Action
import dev.nercone.typefacedroplet.engine.Config
import dev.nercone.typefacedroplet.engine.Face
import dev.nercone.typefacedroplet.engine.FontEntry
import dev.nercone.typefacedroplet.engine.FontSource
import dev.nercone.typefacedroplet.engine.GuardState
import dev.nercone.typefacedroplet.engine.Matcher
import dev.nercone.typefacedroplet.engine.Role
import dev.nercone.typefacedroplet.engine.Rule
import dev.nercone.typefacedroplet.engine.StatusReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Changes not applied yet" must appear exactly when applying again would change what the
 * system shows: when the fonts, their names, the roles, the rules or the options changed, and
 * not when only the library or the place of a font file changed.
 */
class PendingTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    private val config = Config(
        fonts = listOf(FontEntry(a, FontSource("/data/app/1/base.apk", 100, 2000)), FontEntry(b, FontSource("/fonts/b.ttf"))),
        roles = listOf(Role("sans", listOf(Face(a)))),
        rules = listOf(Rule(Matcher.Family("sans-serif"), Action.Replace("sans"))),
    )

    private fun status(applied: Boolean) = StatusReport(
        uid = 0, applied = applied, generation = 1, mounted = emptyList(), directories = emptyList(), served = emptyList(),
        guard = GuardState.Idle, components = emptyList(), documents = emptyList(),
    )

    private fun data(current: Config?, digest: String?, applied: Boolean = true, systemApplied: Boolean = true) = ScreenData(
        UiState(ready = true, status = status(systemApplied)),
        current,
        emptyList(),
        Settings.Values(applied = applied, appliedDigest = digest),
    )

    @Test
    fun theDigestIgnoresWhereFontsAreAndFontsNoRoleUses() {
        val moved = config.copy(fonts = listOf(FontEntry(a, FontSource("/data/app/2/base.apk", 300, 2000))))
        assertEquals(config.digest(), moved.digest())
        val imported = config.copy(fonts = config.fonts + FontEntry("c".repeat(64), FontSource("/fonts/c.ttf")))
        assertEquals(config.digest(), imported.digest())
        assertEquals(config.digest(), config.copy(fonts = config.fonts.reversed()).digest())
    }

    @Test
    fun theDigestFollowsWhatApplyingChanges() {
        val renamed = config.copy(fonts = config.fonts.map { if (it.id == a) it.copy(rename = "Roboto") else it })
        assertNotEquals(config.digest(), renamed.digest())
        assertNotEquals(config.digest(), config.copy(roles = listOf(Role("sans", listOf(Face(b))))).digest())
        assertNotEquals(config.digest(), config.copy(rules = emptyList()).digest())
        assertNotEquals(config.digest(), config.copy(guardSeconds = 30).digest())
        assertNotEquals(config.digest(), config.copy(restart = listOf(Config.HOME)).digest())
        assertNotEquals(config.digest(), config.copy(components = listOf("com.example/.Service")).digest())
    }

    @Test
    fun pendingOnlyWhileAppliedAndChanged() {
        val digest = config.digest()
        assertFalse(data(config, digest).pending)
        assertTrue(data(config.copy(rules = emptyList()), digest).pending)
        // Restored, or replaced by the guard: nothing is applied to differ from.
        assertFalse(data(config.copy(rules = emptyList()), digest, applied = false).pending)
        assertFalse(data(config.copy(rules = emptyList()), digest, systemApplied = false).pending)
        // Applied by an earlier version, which kept no digest: unknown is not a change.
        assertFalse(data(config.copy(rules = emptyList()), null).pending)
        assertFalse(data(null, digest).pending)
    }
}
