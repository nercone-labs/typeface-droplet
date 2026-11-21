package dev.nercone.typefacedroplet.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dev.nercone.typefacedroplet.DropletApplication

/**
 * Checks after a boot that the configuration is applied, and applies it if not: mounts do not
 * survive a reboot, and the boot hook may be missing or covered by another module.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // A receiver runs in the process of the application, after `DropletApplication.onCreate`:
        // the one instance of the settings that every part writes is read, never a second copy.
        val settings = (context.applicationContext as DropletApplication).droplet.settings.values.value
        if (!settings.applyAfterBoot || !settings.applied) return
        val request = OneTimeWorkRequestBuilder<ApplyWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ApplyWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
