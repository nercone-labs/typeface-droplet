package dev.nercone.typefacedroplet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.engine.ReloadOutcome

/**
 * What every screen tells at its top, the same way on each: the outcome of the last operation,
 * and that the configuration changed since it was applied. A restricted root is told by a dialog
 * of the frame instead (`Frame.Restricted`).
 */
object Notices {
    /**
     * Adds the notices to the list of a screen, before its own items. `extra` is added under
     * each, for a screen whose list spaces its items less than `Widgets.SPACING`.
     */
    fun LazyListScope.notices(data: ScreenData, model: AppViewModel, extra: Dp = 0.dp) {
        val outcome = data.state.outcome?.takeIf { (it as? Outcome.Failed)?.restricted != true }
        if (outcome != null) item(key = "outcome") {
            Box(Modifier.padding(bottom = extra)) {
                Outcome(outcome, data.state.idle, onSoftRestart = model::softRestart, onLog = { model.showLog(true) }, onDismiss = model::dismissOutcome)
            }
        }
        if (data.pending) item(key = "pending") { Box(Modifier.padding(bottom = extra)) { Pending(data.state.idle, onApply = model::apply) } }
    }

    @Composable
    fun Pending(idle: Boolean, onApply: () -> Unit) {
        Widgets.Notice(Widgets.Tone.INFORMATION, stringResource(R.string.pending_title), stringResource(R.string.pending_text)) {
            FilledTonalButton(onClick = onApply, enabled = idle) { Text(stringResource(R.string.apply_now)) }
        }
    }

    @Composable
    fun Outcome(outcome: Outcome, idle: Boolean, onSoftRestart: () -> Unit, onLog: () -> Unit, onDismiss: () -> Unit) {
        val (tone, title, text) = describe(outcome)
        Widgets.Notice(tone, title, text) {
            if (outcome is Outcome.Failed || outcome is Outcome.Applied || outcome is Outcome.Reverted) TextButton(onClick = onLog) { Text(stringResource(R.string.show_log)) }
            if (outcome is Outcome.Applied && outcome.applied.report.reload == ReloadOutcome.IGNORED) {
                FilledTonalButton(onClick = onSoftRestart, enabled = idle) { Text(stringResource(R.string.soft_restart)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }

    /** The tone, the title and the text of `outcome`. */
    @Composable
    fun describe(outcome: Outcome): Triple<Widgets.Tone, String, String?> = when (outcome) {
        is Outcome.Applied -> {
            val report = outcome.applied.report
            val restarted = report.restarted.isNotEmpty()
            val (tone, title, text) = when (report.reload) {
                ReloadOutcome.SERVED, ReloadOutcome.AT_START -> Triple(
                    Widgets.Tone.INFORMATION,
                    stringResource(R.string.outcome_applied),
                    pluralStringResource(if (restarted) R.plurals.outcome_applied_text_restarted else R.plurals.outcome_applied_text, report.replaced, report.replaced),
                )
                ReloadOutcome.IGNORED -> Triple(Widgets.Tone.WARNING, stringResource(R.string.outcome_ignored), stringResource(R.string.outcome_ignored_text))
                ReloadOutcome.UNCHANGED -> Triple(Widgets.Tone.WARNING, stringResource(R.string.outcome_unchanged), stringResource(R.string.outcome_unchanged_text))
            }
            val hook = outcome.applied.hookFailure?.let { stringResource(R.string.outcome_hook_failed, it) }
            Triple(if (hook != null) Widgets.Tone.WARNING else tone, title, listOfNotNull(text, hook).joinToString("\n\n"))
        }
        Outcome.Reverted -> Triple(Widgets.Tone.INFORMATION, stringResource(R.string.outcome_reverted), stringResource(R.string.outcome_reverted_text))
        Outcome.Restarting -> Triple(Widgets.Tone.INFORMATION, stringResource(R.string.outcome_restarting), stringResource(R.string.outcome_restarting_text))
        is Outcome.Imported -> Triple(
            Widgets.Tone.INFORMATION,
            stringResource(if (outcome.imported.known) R.string.outcome_import_known else R.string.outcome_imported),
            outcome.imported.font.label,
        )
        is Outcome.ConfigSetAside -> Triple(Widgets.Tone.WARNING, stringResource(R.string.outcome_config_set_aside), stringResource(R.string.outcome_config_set_aside_text, outcome.name))
        is Outcome.Failed -> Triple(Widgets.Tone.ERROR, stringResource(R.string.outcome_failed), outcome.text)
    }
}
