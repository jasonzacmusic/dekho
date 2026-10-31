package com.jasonzac.dekho

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Sends the remote's Home button to Dekho, and comes back to Dekho whenever
 * Google's home screen appears on its own (after switching the TV on, for example).
 */
class HomeButtonService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !dekhoIsHome(this)) return false
        if (event.action == KeyEvent.ACTION_UP) openDekho()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val stockHomeOpened = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName == STOCK_HOME
        if (stockHomeOpened && !showingStockHome && dekhoIsHome(this)) openDekho()
    }

    override fun onServiceConnected() {
        running = this
    }

    override fun onDestroy() {
        running = null
        super.onDestroy()
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

    override fun onInterrupt() = Unit

    private fun openDekho() {
        showingStockHome = false
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val STOCK_HOME = "com.google.android.apps.tv.launcherx"
        private const val SETTINGS_APP = "com.android.tv.settings"

        /** True while the Google TV tile has deliberately opened Google's own home screen. */
        @Volatile
        var showingStockHome = false

        /** False once Google TV has been chosen as the home screen again. */
        fun dekhoIsHome(context: Context) =
            context.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("dekho", true)

        fun setDekhoIsHome(context: Context, value: Boolean) =
            context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("dekho", value).apply()

        /** The live service, when the Home button setting is on. */
        @Volatile
        var running: HomeButtonService? = null
    }
}
