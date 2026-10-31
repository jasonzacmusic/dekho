package com.jasonzac.dekho

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** A slim row of apps and inputs that floats over whatever is playing. */
class QuickStrip(private val service: AccessibilityService) {

    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hideLater = Runnable { hide() }
    private var strip: View? = null

    val showing get() = strip != null

    fun show() {
        if (showing) return
        val shelf = Shelf(service)
        val context = ContextThemeWrapper(service, R.style.Dekho)
        val row = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                restartTimer()
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) hide()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            setBackgroundColor(service.getColor(R.color.strip))
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        val apps = shelf.apps().filter { it.packageName != null }
        val ports = shelf.ports()
        val width = minOf(dp(128), (service.resources.displayMetrics.widthPixels - dp(48)) / (apps.size + ports.size) - dp(12))
        apps.forEach { app ->
            row.addView(tile(context, app.name, app, width) { shelf.openApp(app.packageName!!) })
        }
        ports.forEach { port ->
            row.addView(tile(context, port.device ?: port.label, null, width) { shelf.openPort(port.id) })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        windows.addView(row, params)
        strip = row
        row.getChildAt(0)?.requestFocus()
        restartTimer()
    }

    fun hide() {
        handler.removeCallbacks(hideLater)
        strip?.let(windows::removeView)
        strip = null
    }

    private fun restartTimer() {
        handler.removeCallbacks(hideLater)
        handler.postDelayed(hideLater, 8000)
    }

    private fun tile(context: android.content.Context, name: String, app: ShelfApp?, width: Int, open: () -> Unit): View {
        val tile = FrameLayout(context).apply {
            isFocusable = true
            setBackgroundResource(R.drawable.tile_bg)
            foreground = service.getDrawable(R.drawable.app_ring)
            clipToOutline = true
            setOnClickListener {
                hide()
                open()
            }
            setOnFocusChangeListener { v, focused ->
                val size = if (focused) 1.1f else 1f
                v.animate().scaleX(size).scaleY(size).setDuration(120).start()
            }
        }
        if (app?.art != null) {
            tile.addView(ImageView(context).apply {
                setImageDrawable(app.art)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            tile.addView(TextView(context).apply {
                text = name
                gravity = Gravity.CENTER
                textSize = 15f
                isDuplicateParentStateEnabled = true
                setTextColor(service.getColorStateList(R.color.focus_ink))
            }, FrameLayout.LayoutParams(-1, -1))
        }
        tile.layoutParams = LinearLayout.LayoutParams(width, width * 9 / 16).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        }
        return tile
    }

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
}
