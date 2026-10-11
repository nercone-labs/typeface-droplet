package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.engine.GuardState
import dev.nercone.typefacedroplet.engine.LogLevel
import dev.nercone.typefacedroplet.engine.LogLine
import dev.nercone.typefacedroplet.engine.StatusReport
import dev.nercone.typefacedroplet.privilege.Privilege
import dev.nercone.typefacedroplet.privilege.PrivilegeSelection
import dev.nercone.typefacedroplet.privilege.PrivilegeStatus

/** The home screen: the privilege, the state of the system, and the apply and revert actions. */
object HomeScreen {
    @Composable
    fun Content(state: UiState, model: AppViewModel) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Widgets.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(Widgets.SPACING),
        ) {
            state.privilege?.let { Privileges(it, onGrant = model::requestShizukuPermission) }
            Status(state.status, state.privilege?.privilege != null)
            val available = state.privilege?.privilege != null && state.ready && state.operation == null
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = model::apply, enabled = available, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.apply)) }
                // Restoring also re-enables components, which stay disabled after a reboot or a tripped guard.
                val revertible = state.status?.let { it.applied || it.mounted.isNotEmpty() || it.components.isNotEmpty() } == true
                OutlinedButton(onClick = model::revert, enabled = available && revertible, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.revert))
                }
            }
            TextButton(onClick = model::refresh, enabled = state.operation == null, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.refresh)) }
            state.message?.let { Outcome(it, onSoftRestart = model::softRestart, onDismiss = model::dismissMessage) }
            if (state.log.isNotEmpty()) Log(state.log)
        }
    }

    @Composable
    fun kindName(kind: Privilege.Kind): String = stringResource(
        when (kind) {
            Privilege.Kind.ROOT -> R.string.privilege_root
            Privilege.Kind.SHIZUKU -> R.string.privilege_shizuku
            Privilege.Kind.SUI -> R.string.privilege_sui
        },
    )

    @Composable
    fun Privileges(selection: PrivilegeSelection, onGrant: () -> Unit) {
        val selected = selection.privilege
        if (selected != null) {
            Widgets.Section(stringResource(R.string.privilege)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.privilege_available, kindName(selected.kind)))
                }
            }
            return
        }
        Widgets.Section(stringResource(R.string.privilege), container = MaterialTheme.colorScheme.errorContainer) {
            Text(stringResource(R.string.privilege_none))
            selection.statuses.forEach { (kind, status) ->
                val text = when (status) {
                    is PrivilegeStatus.Available -> stringResource(R.string.privilege_available, kindName(kind))
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
                Text("${kindName(kind)}: $text", style = MaterialTheme.typography.bodyMedium)
                if (status == PrivilegeStatus.PermissionRequired) {
                    FilledTonalButton(onClick = onGrant) { Text(stringResource(R.string.grant_shizuku)) }
                }
            }
        }
    }

    @Composable
    fun Status(status: StatusReport?, privileged: Boolean) {
        Widgets.Section(stringResource(R.string.status)) {
            if (status == null) {
                Text(stringResource(if (privileged) R.string.status_unknown else R.string.status_needs_privilege))
                return@Section
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.TextFields, null, tint = if (status.applied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                Text(stringResource(if (status.applied) R.string.status_applied else R.string.status_not_applied), style = MaterialTheme.typography.titleSmall)
            }
            status.api?.let { Text(stringResource(R.string.status_api, it), style = MaterialTheme.typography.bodyMedium) }
            if (status.applied) {
                Text(stringResource(R.string.status_served, status.served.size, status.mounted.size), style = MaterialTheme.typography.bodyMedium)
            }
            if (status.components.isNotEmpty()) {
                Text(stringResource(R.string.status_components, status.components.size), style = MaterialTheme.typography.bodyMedium)
            }
            when (val guard = status.guard) {
                is GuardState.Watching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.guard_watching), style = MaterialTheme.typography.bodyMedium)
                }
                is GuardState.Tripped -> Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.guard_tripped, guard.reason), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                GuardState.Passed, GuardState.Idle -> {}
            }
        }
    }

    @Composable
    fun Outcome(message: Message, onSoftRestart: () -> Unit, onDismiss: () -> Unit) {
        val failed = message is Message.Failed
        Widgets.Section(
            stringResource(if (failed) R.string.outcome_failed else R.string.outcome),
            container = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ) {
            when (message) {
                is Message.Applied -> {
                    val report = message.report
                    val replaced = report.documents.values.sumOf { it.replaced.size }
                    Text(
                        when (report.reloaded) {
                            true -> stringResource(R.string.outcome_applied, replaced)
                            false -> stringResource(R.string.outcome_not_reloaded)
                            null -> stringResource(R.string.outcome_nothing_matched)
                        },
                    )
                    if (report.restarted.isNotEmpty()) Text(stringResource(R.string.outcome_restarted, report.restarted.joinToString()), style = MaterialTheme.typography.bodySmall)
                    if (report.reloaded == false) FilledTonalButton(onClick = onSoftRestart) { Text(stringResource(R.string.soft_restart)) }
                }
                Message.Reverted -> Text(stringResource(R.string.outcome_reverted))
                Message.Restarting -> Text(stringResource(R.string.outcome_restarting))
                is Message.Failed -> Text(message.text)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.dismiss)) }
        }
    }

    @Composable
    fun Log(lines: List<LogLine>) {
        var expanded by remember { mutableStateOf(false) }
        Widgets.Section(stringResource(R.string.log)) {
            val shown = if (expanded) lines else lines.takeLast(COLLAPSED_LINES)
            shown.forEach { line ->
                Text(
                    line.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when (line.level) {
                        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                    },
                )
            }
            if (lines.size > COLLAPSED_LINES) {
                TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.show_less else R.string.show_all, lines.size)) }
            }
        }
    }

    const val COLLAPSED_LINES = 6
}
