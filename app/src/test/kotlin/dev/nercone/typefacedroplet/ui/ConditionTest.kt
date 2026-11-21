package dev.nercone.typefacedroplet.ui

import dev.nercone.typefacedroplet.engine.EngineException
import dev.nercone.typefacedroplet.engine.GuardState
import dev.nercone.typefacedroplet.engine.StatusReport
import dev.nercone.typefacedroplet.privilege.CommandResult
import dev.nercone.typefacedroplet.privilege.Privilege
import dev.nercone.typefacedroplet.privilege.PrivilegeSelection
import dev.nercone.typefacedroplet.privilege.PrivilegeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the verdict of the home screen says for each state. Above all, a reading that failed
 * must never pass for one that is still running: the screen would show "Checking" for ever.
 */
class ConditionTest {
    private val root = object : Privilege {
        override val kind = Privilege.Kind.ROOT
        override suspend fun status(): PrivilegeStatus = PrivilegeStatus.Available(0)
        override suspend fun execute(command: List<String>, timeoutMillis: Long) = CommandResult(0, "", "")
    }
    private val available = PrivilegeSelection(root, listOf(Privilege.Kind.ROOT to PrivilegeStatus.Available(0)))
    private val none = PrivilegeSelection(null, listOf(Privilege.Kind.ROOT to PrivilegeStatus.Unavailable(PrivilegeStatus.Reason.NOT_INSTALLED)))

    private fun status(applied: Boolean, guard: GuardState = GuardState.Idle) = StatusReport(
        uid = 0,
        applied = applied,
        generation = 1,
        mounted = emptyList(),
        directories = emptyList(),
        served = emptyList(),
        guard = guard,
        components = emptyList(),
        documents = emptyList(),
    )

    private fun condition(state: UiState) = HomeScreen.condition(state)

    @Test
    fun nothingIsToldBeforeTheLibraryIsReady() {
        assertEquals(HomeScreen.Condition.PREPARING, condition(UiState(ready = false, privilege = available, status = status(true))))
    }

    @Test
    fun aMissingPrivilegeIsToldAsSuch() {
        assertEquals(HomeScreen.Condition.NO_PRIVILEGE, condition(UiState(ready = true, privilege = none)))
    }

    @Test
    fun aReadingThatFailedIsNotStillChecking() {
        val failed = Outcome.Failed("the engine printed no result", "output")
        assertEquals(HomeScreen.Condition.UNREADABLE, condition(UiState(ready = true, privilege = available, statusError = failed)))
    }

    @Test
    fun aRestrictedRootIsToldApartFromOtherFailures() {
        val restricted = Outcome.Failed("capabilities missing", EngineException.RESTRICTED)
        assertEquals(HomeScreen.Condition.RESTRICTED, condition(UiState(ready = true, privilege = available, statusError = restricted)))
    }

    @Test
    fun aReadingInProgressIsChecking() {
        assertEquals(HomeScreen.Condition.CHECKING, condition(UiState(ready = true, privilege = null)))
        assertEquals(HomeScreen.Condition.CHECKING, condition(UiState(ready = true, privilege = available)))
    }

    @Test
    fun appliedFontsAreActiveEvenAfterAnEarlierTrip() {
        assertEquals(HomeScreen.Condition.ACTIVE, condition(UiState(ready = true, privilege = available, status = status(true))))
        val tripped = GuardState.Tripped("system_server died", 1)
        assertEquals(HomeScreen.Condition.ACTIVE, condition(UiState(ready = true, privilege = available, status = status(true, tripped))))
        assertEquals(HomeScreen.Condition.TRIPPED, condition(UiState(ready = true, privilege = available, status = status(false, tripped))))
    }

    @Test
    fun originalFontsAreToldWhenNothingIsApplied() {
        assertEquals(HomeScreen.Condition.ORIGINAL, condition(UiState(ready = true, privilege = available, status = status(false))))
    }
}
