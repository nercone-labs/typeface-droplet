package dev.nercone.typefacedroplet.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dev.nercone.typefacedroplet.config.Settings

/**
 * Checks after a boot that the configuration is applied, and applies it if not: mounts do not
 * survive a reboot, and the boot hook may be missing or covered by another module.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = Settings(context).values.value
        if (!settings.applyAfterBoot || !settings.applied) return
        val request = OneTimeWorkRequestBuilder<ApplyWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ApplyWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
