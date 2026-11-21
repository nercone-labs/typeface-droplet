package dev.nercone.typefacedroplet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.config.Category
import dev.nercone.typefacedroplet.engine.Action
import dev.nercone.typefacedroplet.engine.FaceInfo
import dev.nercone.typefacedroplet.engine.FaceStyle
import dev.nercone.typefacedroplet.engine.Matcher
import dev.nercone.typefacedroplet.engine.RootManager
import dev.nercone.typefacedroplet.privilege.Privilege
import dev.nercone.typefacedroplet.privilege.PrivilegeChoice
import dev.nercone.typefacedroplet.privilege.PrivilegeStatus

/** The words the screens use for the values of the application, the same on every screen. */
object Labels {
    @Composable
    fun privilege(kind: Privilege.Kind): String = stringResource(
        when (kind) {
            Privilege.Kind.ROOT -> R.string.privilege_root
            Privilege.Kind.SHIZUKU -> R.string.privilege_shizuku
            Privilege.Kind.SUI -> R.string.privilege_sui
        },
    )

    @Composable
    fun privilegeChoice(choice: PrivilegeChoice): String = stringResource(
        when (choice) {
            PrivilegeChoice.AUTOMATIC -> R.string.settings_privilege_automatic
            PrivilegeChoice.SHIZUKU -> R.string.settings_privilege_shizuku
            PrivilegeChoice.ROOT -> R.string.settings_privilege_root
        },
    )

    @Composable
    fun describe(status: PrivilegeStatus): String = when (status) {
        is PrivilegeStatus.Available -> stringResource(R.string.privilege_available)
        is PrivilegeStatus.InsufficientUser ->
            if (status.uid == Privilege.SHELL_UID) stringResource(R.string.privilege_shizuku_adb) else stringResource(R.string.privilege_wrong_user, status.uid)
        PrivilegeStatus.PermissionRequired -> stringResource(R.string.privilege_permission_required)
        is PrivilegeStatus.Unavailable -> stringResource(
            when (status.reason) {
                PrivilegeStatus.Reason.NOT_INSTALLED -> R.string.privilege_not_installed
                PrivilegeStatus.Reason.NOT_RUNNING -> R.string.privilege_not_running
                PrivilegeStatus.Reason.DENIED -> R.string.privilege_denied
                PrivilegeStatus.Reason.OUTDATED -> R.string.privilege_outdated
            },
        )
    }

    @Composable
    fun rootManagers(managers: List<RootManager>): String = managers.map { manager ->
        stringResource(
            when (manager) {
                RootManager.MAGISK -> R.string.manager_magisk
                RootManager.KERNEL_SU -> R.string.manager_kernelsu
                RootManager.APATCH -> R.string.manager_apatch
            },
        )
    }.joinToString(" / ")

    @Composable
    fun category(category: Category): String = stringResource(
        when (category) {
            Category.SANS -> R.string.category_sans
            Category.SERIF -> R.string.category_serif
            Category.MONO -> R.string.category_mono
        },
    )

    @Composable
    fun categoryHelp(category: Category): String = stringResource(
        when (category) {
            Category.SANS -> R.string.category_sans_help
            Category.SERIF -> R.string.category_serif_help
            Category.MONO -> R.string.category_mono_help
        },
    )

    @Composable
    fun style(style: FaceStyle): String = stringResource(
        when (style) {
            FaceStyle.NORMAL -> R.string.style_normal
            FaceStyle.ITALIC -> R.string.style_italic
        },
    )

    /** The style of a face, or "Detect" when the face leaves it to the font. */
    @Composable
    fun faceStyle(style: FaceStyle?): String = style?.let { style(it) } ?: stringResource(R.string.style_automatic)

    /** A one-line description of the style of a face: weight range or weight, italic, axes. */
    @Composable
    fun face(face: FaceInfo): String {
        val weight = face.weightAxis?.let { stringResource(R.string.font_variable, it.minimum.toInt(), it.maximum.toInt()) }
            ?: stringResource(R.string.font_static, face.weight)
        val style = style(if (face.italic) FaceStyle.ITALIC else FaceStyle.NORMAL)
        val axes = face.axes.filter { !it.hidden && it.tag != "wght" }.joinToString(" ") { it.tag }
        return listOf(weight, style, axes).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    @Composable
    fun matcherKind(key: String): String = stringResource(
        when (key) {
            Matcher.FAMILY -> R.string.match_family
            Matcher.FILE -> R.string.match_file
            else -> R.string.match_language
        },
    )

    @Composable
    fun matcher(matcher: Matcher): String = when (matcher) {
        is Matcher.Family -> stringResource(R.string.match_family_text, matcher.pattern)
        is Matcher.File -> stringResource(R.string.match_file_text, matcher.pattern)
        is Matcher.Language -> stringResource(R.string.match_language_text, matcher.pattern)
    }

    @Composable
    fun action(action: Action): String = when (action) {
        is Action.Replace -> stringResource(R.string.action_replace_text, action.role)
        is Action.Prepend -> stringResource(R.string.action_prepend_text, action.entries.joinToString { entry -> entry.fallbackFor?.let { "${entry.role} ($it)" } ?: entry.role })
    }

    /** How long the guard watches: off, or a number of seconds. */
    @Composable
    fun guard(seconds: Int): String = if (seconds == 0) stringResource(R.string.settings_guard_off) else pluralStringResource(R.plurals.seconds, seconds, seconds)

    @Composable
    fun operation(operation: Operation): String = stringResource(
        when (operation) {
            Operation.PREPARING -> R.string.operation_preparing
            Operation.CHECKING -> R.string.operation_checking
            Operation.APPLYING -> R.string.operation_applying
            Operation.REVERTING -> R.string.operation_reverting
            Operation.RESTARTING -> R.string.operation_restarting
            Operation.IMPORTING -> R.string.operation_importing
            Operation.CONFIGURING_BOOT -> R.string.operation_configuring_boot
        },
    )
}
