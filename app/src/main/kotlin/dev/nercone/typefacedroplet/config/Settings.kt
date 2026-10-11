package dev.nercone.typefacedroplet.config

import android.content.Context
import androidx.core.content.edit
import dev.nercone.typefacedroplet.privilege.PrivilegeChoice

/** Preferences of the application itself, as opposed to the engine configuration. */
class Settings(context: Context) {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var privilege: PrivilegeChoice
        get() = preferences.getString(PRIVILEGE, null)?.let { runCatching { PrivilegeChoice.valueOf(it) }.getOrNull() } ?: PrivilegeChoice.AUTOMATIC
        set(value) = preferences.edit { putString(PRIVILEGE, value.name) }

    /** Whether the configuration is applied again after every boot. */
    var applyAtBoot: Boolean
        get() = preferences.getBoolean(APPLY_AT_BOOT, true)
        set(value) = preferences.edit { putBoolean(APPLY_AT_BOOT, value) }

    /** Whether the configuration has been applied manually since the last revert. Boot application only re-applies an applied configuration. */
    var applied: Boolean
        get() = preferences.getBoolean(APPLIED, false)
        set(value) = preferences.edit { putBoolean(APPLIED, value) }

    companion object {
        const val NAME = "settings"
        const val PRIVILEGE = "privilege"
        const val APPLY_AT_BOOT = "applyAtBoot"
        const val APPLIED = "applied"
    }
}
