package dev.kirker.miatadash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import dev.kirker.miatadash.core.logging.FileLogTree
import timber.log.Timber

@HiltAndroidApp
class MiataApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // DebugTree logs to logcat in debug builds.
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // FileLogTree always: persists WARN/INFO/ERROR to filesDir/logs/ for post-drive
        // analysis. Rotates 3 sessions; readable from the Session Logs diagnostic screen
        // or via `adb pull /data/data/dev.kirker.miatadash/files/logs/`.
        Timber.plant(FileLogTree(this))
        createServiceChannel()
    }

    private fun createServiceChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            getString(R.string.service_channel_id),
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Foreground service for the Bluetooth telemetry session"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }
}
