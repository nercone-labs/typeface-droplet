package dev.nercone.typefacedroplet.config

import android.content.Context
import androidx.core.content.edit
import dev.nercone.typefacedroplet.privilege.PrivilegeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Preferences of the application itself, as opposed to the engine configuration. */
class Settings(context: Context) {
    val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    val values = MutableStateFlow(read())

    /** The preferences, as one immutable value. */
    data class Values(
        val privilege: PrivilegeChoice = PrivilegeChoice.AUTOMATIC,
        /** Whether the root manager applies the configuration at every boot, before the framework starts. */
        val bootHook: Boolean = true,
        /** Whether the application applies the configuration after every boot when it is not applied yet. */
        val applyAfterBoot: Boolean = true,
        /** Whether the configuration has been applied manually since the last restoration. */
        val applied: Boolean = false,
        /** Whether roles and rules were edited by hand; otherwise they follow the chosen fonts. */
        val customized: Boolean = false,
        /**
         * The family names the user wrote for fonts, by font id. Fonts without one get the name
         * the recommended configuration gives the family they replace.
         */
        val names: Map<String, String> = emptyMap(),
    )

    fun read(): Values = Values(
        privilege = preferences.getString(PRIVILEGE, null)?.let { runCatching { PrivilegeChoice.valueOf(it) }.getOrNull() } ?: PrivilegeChoice.AUTOMATIC,
        bootHook = preferences.getBoolean(BOOT_HOOK, true),
        applyAfterBoot = preferences.getBoolean(APPLY_AFTER_BOOT, true),
        applied = preferences.getBoolean(APPLIED, false),
        customized = preferences.getBoolean(CUSTOMIZED, false),
        names = preferences.getString(NAMES, null)?.let { runCatching { Json.decodeFromString(NAMES_SERIALIZER, it) }.getOrNull() } ?: emptyMap(),
    )

    fun update(transform: (Values) -> Values) {
        val next = transform(values.value)
        preferences.edit {
            putString(PRIVILEGE, next.privilege.name)
            putBoolean(BOOT_HOOK, next.bootHook)
            putBoolean(APPLY_AFTER_BOOT, next.applyAfterBoot)
            putBoolean(APPLIED, next.applied)
            putBoolean(CUSTOMIZED, next.customized)
            putString(NAMES, Json.encodeToString(NAMES_SERIALIZER, next.names))
        }
        values.value = next
    }

    companion object {
        const val NAME = "settings"
        const val PRIVILEGE = "privilege"
        const val BOOT_HOOK = "bootHook"
        const val APPLY_AFTER_BOOT = "applyAtBoot"
        const val APPLIED = "applied"
        const val CUSTOMIZED = "customized"
        const val NAMES = "names"
        val NAMES_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
