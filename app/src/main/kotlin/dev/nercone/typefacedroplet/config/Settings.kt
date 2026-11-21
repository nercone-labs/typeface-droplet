package dev.nercone.typefacedroplet.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.nercone.typefacedroplet.privilege.PrivilegeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Preferences of the application itself, as opposed to the engine configuration. There is one
 * instance per process (`Droplet.settings`): the user interface, the boot receiver and the boot
 * worker all read and write it, so that no part works on a copy that misses the writes of another.
 */
class Settings(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val values: StateFlow<Values> = state

    /** The preferences, as one immutable value. */
    data class Values(
        val privilege: PrivilegeChoice = PrivilegeChoice.AUTOMATIC,
        /**
         * Whether the user declined a permission the application asked for on its own. It is then
         * asked for only when the user chooses to grant it, until the privilege choice changes.
         */
        val permissionDeclined: Boolean = false,
        /** Whether the root manager applies the configuration at every boot, before the framework starts. */
        val bootHook: Boolean = true,
        /** Whether the application applies the configuration after every boot when it is not applied yet. */
        val applyAfterBoot: Boolean = true,
        /** Whether the configuration has been applied manually since the last restoration. */
        val applied: Boolean = false,
        /**
         * `Config.digest` of the configuration last applied, or `null` when it is not known (before
         * the first application of this version, or after a restoration).
         */
        val appliedDigest: String? = null,
        /** Whether roles and rules were edited by hand; otherwise they follow the chosen fonts. */
        val customized: Boolean = false,
        /**
         * The family names the user wrote for fonts, by font id. Fonts without one get the name
         * the recommended configuration gives the family they replace.
         */
        val names: Map<String, String> = emptyMap(),
        /** Whether the notification permission was asked for, which happens once. */
        val notificationsAsked: Boolean = false,
    )

    fun read(): Values = Values(
        privilege = preferences.getString(PRIVILEGE, null)?.let { runCatching { PrivilegeChoice.valueOf(it) }.getOrNull() } ?: PrivilegeChoice.AUTOMATIC,
        permissionDeclined = preferences.getBoolean(PERMISSION_DECLINED, false),
        bootHook = preferences.getBoolean(BOOT_HOOK, true),
        applyAfterBoot = preferences.getBoolean(APPLY_AFTER_BOOT, true),
        applied = preferences.getBoolean(APPLIED, false),
        appliedDigest = preferences.getString(APPLIED_DIGEST, null),
        customized = preferences.getBoolean(CUSTOMIZED, false),
        names = preferences.getString(NAMES, null)?.let { runCatching { Json.decodeFromString(NAMES_SERIALIZER, it) }.getOrNull() } ?: emptyMap(),
        notificationsAsked = preferences.getBoolean(NOTIFICATIONS_ASKED, false),
    )

    /**
     * Replaces the values with `transform` of the current ones. Updates come from the main thread
     * and from workers, so they are serialized: two concurrent updates must not both start from
     * the same values and lose one of the changes.
     */
    @Synchronized
    fun update(transform: (Values) -> Values) {
        val next = transform(state.value)
        preferences.edit {
            putString(PRIVILEGE, next.privilege.name)
            putBoolean(PERMISSION_DECLINED, next.permissionDeclined)
            putBoolean(BOOT_HOOK, next.bootHook)
            putBoolean(APPLY_AFTER_BOOT, next.applyAfterBoot)
            putBoolean(APPLIED, next.applied)
            if (next.appliedDigest == null) remove(APPLIED_DIGEST) else putString(APPLIED_DIGEST, next.appliedDigest)
            putBoolean(CUSTOMIZED, next.customized)
            putString(NAMES, Json.encodeToString(NAMES_SERIALIZER, next.names))
            putBoolean(NOTIFICATIONS_ASKED, next.notificationsAsked)
        }
        state.value = next
    }

    companion object {
        const val NAME = "settings"
        const val PRIVILEGE = "privilege"
        const val PERMISSION_DECLINED = "permissionDeclined"
        const val BOOT_HOOK = "bootHook"
        const val APPLY_AFTER_BOOT = "applyAtBoot"
        const val APPLIED = "applied"
        const val APPLIED_DIGEST = "appliedDigest"
        const val CUSTOMIZED = "customized"
        const val NAMES = "names"
        const val NOTIFICATIONS_ASKED = "notificationsAsked"
        val NAMES_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
