package com.jasonzac.dekho

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread

/**
 * Keeps Dekho as the home screen: sends the remote's Home button here, comes back when
 * Google's screen appears by itself, and runs the quick-switch strip and the phone remote.
 */
class HomeButtonService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }
    private val strip by lazy { QuickStrip(this) }
    private val remote by lazy { TvRemote(this) }
    private val airPods by lazy { AirPodsWatch(this) }
    private var server: RemoteServer? = null
    private var inFront = ""

    /** The app to offer first, so Home then OK swaps straight back. */
    var previousApp: String? = null
        private set

    override fun onServiceConnected() {
        running = this
        server = RemoteServer(this, remote).also { it.start() }
        airPods.start()
    }

    override fun onDestroy() {
        running = null
        strip.hide()
        server?.stop()
        airPods.stop()
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !prefs.dekhoIsHome) return false
        if (event.action == KeyEvent.ACTION_UP) {
            // First Home over an app offers the strip; a second one goes all the way home.
            if (inFront in QUIET_SCREENS || strip.showing) {
                noteLeaving(null)
                strip.hide()
                openDekho()
            } else {
                strip.show()
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val app = event.packageName?.toString() ?: return
        if (app != SYSTEM_UI && !strip.showing) inFront = app
        if (app in STOCK_HOMES && !showingStockHome && prefs.dekhoIsHome) openDekho()
    }

    override fun onInterrupt() = Unit

    fun switchTo(packageName: String) {
        noteLeaving(packageName)
        Shelf(this).openApp(packageName)
    }

    fun switchToPort(id: String) {
        noteLeaving(null)
        Shelf(this).openPort(id)
    }

    private fun noteLeaving(next: String?) {
        if (inFront == next || inFront in QUIET_SCREENS) return
        previousApp = inFront
    }

    fun toggleStrip() = if (strip.showing) strip.hide() else strip.show()

    /** Asks the TV to allow the remote. The owner answers on screen. */
    fun pairInBackground(done: (Boolean) -> Unit) {
        thread {
            val paired = remote.pair()
            handler.post { done(paired) }
        }
    }

    // The panel fills in a moment after it appears. Press once only, and only on the
    // Picture page: a press on the Brightness page itself would move a slider.
    fun pressBrightnessRow(attemptsLeft: Int = 20) {
        val panel = rootInActiveWindow?.takeIf { it.packageName == SETTINGS_APP }
        val title = panel?.findAccessibilityNodeInfosByViewId("$SETTINGS_APP:id/page_title")?.firstOrNull()
        if (title?.text?.toString() == "Picture") {
            var row = panel.findAccessibilityNodeInfosByText("Brightness").firstOrNull()
            while (row != null && !row.isClickable) row = row.parent
            if (row != null) {
                row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
        if (attemptsLeft > 0) handler.postDelayed({ pressBrightnessRow(attemptsLeft - 1) }, 200)
    }

    private fun openDekho() {
        showingStockHome = false
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val STOCK_HOME = "com.google.android.apps.tv.launcherx"
        private val STOCK_HOMES = setOf(STOCK_HOME, "com.google.android.tvlauncher", "com.android.tv.launcher")
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SETTINGS_APP = "com.android.tv.settings"
        private val QUIET_SCREENS = STOCK_HOMES + SETTINGS_APP + "com.jasonzac.dekho" + ""

        /** True while the Google TV tile has deliberately opened Google's own home screen. */
        @Volatile
        var showingStockHome = false

        /** The live service, when the Home button setting is on. */
        @Volatile
        var running: HomeButtonService? = null
    }
}
