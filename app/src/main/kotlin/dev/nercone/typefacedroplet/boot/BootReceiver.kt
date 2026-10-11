package dev.nercone.typefacedroplet.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dev.nercone.typefacedroplet.config.Settings

/** Re-applies the configuration after a boot, as mounts do not survive a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = Settings(context)
        if (!settings.applyAtBoot || !settings.applied) return
        val request = OneTimeWorkRequestBuilder<ApplyWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ApplyWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
