package pl.ambienthome.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.ArrayDeque

/** Optional, user-enabled HOME redirect. No node access, key filtering or polling. */
class HomeRedirectService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var foregroundPackage: String? = null
    private var pendingPackage: String? = null
    private var homeWindows = emptySet<Pair<String, String>>()
    private val launches = ArrayDeque<Long>()
    private val redirect = Runnable {
        val now = SystemClock.elapsedRealtime()
        if (pendingPackage == null || foregroundPackage != pendingPackage || now < bypassUntil) return@Runnable
        while (launches.isNotEmpty() && now - launches.peekFirst() > 5000) launches.removeFirst()
        if (launches.size >= 3) { Log.w(TAG, "Redirect rate limit; leaving system UI available"); return@Runnable }
        try {
            // Deliberately no ACTION_MAIN/CATEGORY_HOME: this is an app shortcut,
            // not an attempt to replace the system HOME role or recovery component.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            launches.addLast(now)
            Log.i(TAG, "Configured home window redirected to Ambient Home")
        } catch (error: Exception) { Log.w(TAG, "HOME redirect unavailable", error) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        foregroundPackage = pkg
        val key = pkg to event.className?.toString().orEmpty()
        if (key !in homeWindows) { main.removeCallbacks(redirect); pendingPackage = null; return }
        if (SystemClock.elapsedRealtime() < bypassUntil) return
        pendingPackage = pkg
        main.removeCallbacks(redirect)
        main.postDelayed(redirect, 250)
    }
    override fun onServiceConnected() {
        activeService = this
        homeWindows = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
            .map { it.activityInfo }.filter { info ->
                info.packageName != packageName && listOf("setup", "wizard", "recovery").none { it in (info.packageName + info.name).lowercase() }
            }.map { it.packageName to it.name }.toSet()
        Log.i(TAG, "HOME redirect enabled; waiting for an installed home activity window")
    }
    override fun onInterrupt() { main.removeCallbacksAndMessages(null) }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); if (activeService === this) activeService = null; super.onDestroy() }

    companion object {
        private const val TAG = "AmbientHomeKey"
        @Volatile private var bypassUntil = 0L
        @Volatile private var activeService: HomeRedirectService? = null
        fun allowStockTemporarily() { bypassUntil = SystemClock.elapsedRealtime() + 60000 }
        fun isActive() = activeService != null
        fun disableFromApp() { activeService?.disableSelf() }
    }
}
