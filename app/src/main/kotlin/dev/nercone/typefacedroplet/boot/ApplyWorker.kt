package dev.nercone.typefacedroplet.boot

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.nercone.typefacedroplet.DropletApplication
import dev.nercone.typefacedroplet.R
import dev.nercone.typefacedroplet.engine.ReloadOutcome
import dev.nercone.typefacedroplet.privilege.PrivilegeChoice
import dev.nercone.typefacedroplet.ui.MainActivity

/**
 * Applies the configuration after a boot when the boot hook did not (no root manager runs boot
 * scripts, or the hook is disabled). A privilege that is available at once (such as su) is used
 * at once; Shizuku may start after this application, so when Shizuku is installed (or Sui
 * provides it) and nothing else is available, its binder is awaited first. The user is notified
 * when the configuration cannot be applied, including when the engine refuses because its guard
 * tripped during the previous boot.
 */
class ApplyWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val droplet = (applicationContext as DropletApplication).droplet
        val waitForShizuku = droplet.settings.values.value.privilege != PrivilegeChoice.ROOT && droplet.shizuku.mayStart()
        if (waitForShizuku && droplet.selectPrivilege().privilege == null) {
            droplet.shizuku.awaitBinder(BINDER_TIMEOUT_MILLIS)
        }
        return try {
            droplet.initialize()
            val applied = droplet.applyAfterBoot()
            if (applied?.report?.reload == ReloadOutcome.IGNORED) notify(applicationContext.getString(R.string.boot_not_reloaded))
            Result.success()
        } catch (error: Exception) {
            notify(applicationContext.getString(R.string.boot_failed, error.message ?: error.toString()))
            Result.failure()
        }
    }

    fun notify(text: String) {
        if (applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, DropletApplication.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val NAME = "apply-after-boot"
        const val NOTIFICATION_ID = 1
        const val BINDER_TIMEOUT_MILLIS = 120_000L
    }
}
