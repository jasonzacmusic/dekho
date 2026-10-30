package com.jasonzac.dekho

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Sends the remote's Home button to Dekho, and comes back to Dekho whenever
 * Google's home screen appears on its own (after switching the TV on, for example).
 */
class HomeButtonService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP) openDekho()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val stockHomeOpened = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName == STOCK_HOME
        if (stockHomeOpened && !showingStockHome) openDekho()
    }

    override fun onInterrupt() = Unit

    private fun openDekho() {
        showingStockHome = false
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val STOCK_HOME = "com.google.android.apps.tv.launcherx"

        /** True while the Google TV tile has deliberately opened Google's own home screen. */
        @Volatile
        var showingStockHome = false
    }
}
