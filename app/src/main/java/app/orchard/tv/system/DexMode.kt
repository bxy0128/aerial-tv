package app.orchard.tv.system

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import app.orchard.tv.MainActivity

/**
 * Samsung DeX: the phone draws a desktop on an HDMI screen, and Samsung's own desktop launcher
 * owns it. Nothing starts Aerial there — on the phone it only ever opened on the DeX display when
 * adb named it (`am start --display 14`). This finds that display and opens Aerial on it, so the
 * desktop comes up as Aerial instead of the stock DeX home.
 *
 * MainActivity is not resizable (AndroidManifest), which is what makes DeX show it full screen
 * rather than in one of its windows.
 *
 * Verified on: Galaxy S21+ and Tab S9+, One UI, DeX over HDMI.
 */
object DexMode {

    /** Samsung's DeX broadcasts: the desktop came up, the desktop went away. */
    const val ACTION_ENTER = "android.app.action.ENTER_KNOX_DESKTOP"
    const val ACTION_EXIT = "android.app.action.EXIT_KNOX_DESKTOP"

    /** DeX adds the display a moment after it broadcasts, so keep looking for a short while. */
    private const val LOOK_EVERY_MS = 250L
    private const val LOOK_TRIES = 40

    /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN, for the reflection in [open]. */
    private const val WINDOWING_MODE_FULLSCREEN = 1

    /** The DeX display, or null when DeX is not running. */
    fun display(context: Context): Display? {
        if (Build.VERSION.SDK_INT < 26) return null
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        return manager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && isDesktop(it) }
    }

    /**
     * Samsung names the DeX display "DeX". Any other desktop mode shows up as an extended
     * presentation display, which is what DeX is underneath as well.
     */
    private fun isDesktop(display: Display): Boolean =
        display.name?.contains("DeX", ignoreCase = true) == true ||
            (display.flags and Display.FLAG_PRESENTATION) != 0

    /** Opens Aerial on the DeX display, full screen. False when DeX is not up. */
    fun open(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val display = display(context) ?: return false
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
        // DeX otherwise opens the activity in a phone-shaped window in the middle of the desktop.
        // The method is system-only, so it may be missing; the launch still works without it.
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, WINDOWING_MODE_FULLSCREEN)
        }
        val started = runCatching { context.startActivity(intent, options.toBundle()); true }.getOrDefault(false)
        DiagLog.i("DeX display ${display.displayId} (${display.name}): ${if (started) "opened" else "not opened"}")
        return started
    }

    /** Opens Aerial on the DeX display as soon as one exists, waiting for DeX to add it. */
    fun openWhenReady(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val app = context.applicationContext
        if (open(app)) return
        DiagLog.i("no DeX display yet; watching for one")
        val handler = Handler(Looper.getMainLooper())
        var tries = LOOK_TRIES
        val look = object : Runnable {
            override fun run() {
                if (open(app) || --tries <= 0) return
                handler.postDelayed(this, LOOK_EVERY_MS)
            }
        }
        handler.postDelayed(look, LOOK_EVERY_MS)
    }
}
