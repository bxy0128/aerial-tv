package app.orchard.tv.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.orchard.tv.R

/**
 * Samsung DeX starts on its own and never asks a third-party app to open, so something has to be
 * listening when the desktop comes up. This service waits for the desktop and opens Aerial on it,
 * full screen (see [DexMode]).
 *
 * A service rather than only the manifest receiver: an implicit broadcast does not wake a
 * manifest receiver on Android 8+, so with no process running Aerial never heard DeX come up
 * (measured on Galaxy S21+, Android 15). The display listener covers One UI versions where the
 * broadcast arrives late or not at all.
 */
class DexService : Service() {

    private val displays get() = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val dexBroadcasts = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DiagLog.i("DeX enter broadcast")
            if (intent.action == DexMode.ACTION_ENTER) DexMode.openWhenReady(context)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            DiagLog.i("display $displayId added")
            DexMode.openWhenReady(this@DexService)
        }
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        startForeground(NOTIFICATION_ID, notification())
        ContextCompat.registerReceiver(
            this, dexBroadcasts, IntentFilter(DexMode.ACTION_ENTER), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        displays.registerDisplayListener(displayListener, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(dexBroadcasts) }
        displays.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    /** Quiet by design: MIN importance, no sound, no badge. It is only here because a foreground
     *  service is what keeps a process alive to hear DeX come up. */
    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.dex_service_name), NotificationManager.IMPORTANCE_MIN),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dex)
            .setContentTitle(getString(R.string.dex_service_name))
            .setContentText(getString(R.string.dex_service_hint))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL = "dex"
        private const val NOTIFICATION_ID = 2

        /** Starts (or keeps) the listener that opens Aerial when DeX comes up. */
        fun start(context: Context) {
            val intent = Intent(context, DexService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent)
                else context.startService(intent)
            }
        }
    }
}
