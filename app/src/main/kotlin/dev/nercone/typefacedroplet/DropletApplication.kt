package dev.nercone.typefacedroplet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.nercone.typefacedroplet.privilege.ShizukuPrivilege

class DropletApplication : Application() {
    lateinit var droplet: Droplet
        private set

    override fun onCreate() {
        super.onCreate()
        ShizukuPrivilege.initialize(this)
        droplet = Droplet(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val CHANNEL = "status"
    }
}
