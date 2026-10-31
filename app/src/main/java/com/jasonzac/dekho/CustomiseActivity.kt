package com.jasonzac.dekho

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Lets any owner make Dekho theirs with the remote alone: no typing needed. */
class CustomiseActivity : Activity() {

    private val prefs by lazy { Prefs(this) }
    private val shelf by lazy { Shelf(this) }

    private lateinit var page: LinearLayout
    private var chosen = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundResource(R.drawable.backdrop)
            isFillViewport = true
            addView(page)
        })
        chosen = (prefs.apps ?: shelf.apps().mapNotNull { it.packageName }).toMutableList()
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val focused = page.findFocus()?.tag
        page.removeAllViews()
        page.addView(header())
        heading("Apps on your home screen · pick up to ${Prefs.MAX_APPS}, in order")
        appGrid()
        heading("HDMI names · press to change")
        portRow()
        heading("Home screen and remote")
        settingsRow()
        focused?.let { page.findViewWithTag<View>(it)?.requestFocus() }
    }

    private fun appGrid() {
        val grid = GridLayout(this).apply { columnCount = 6 }
        shelf.installed().forEach { app ->
            val id = app.activityInfo.packageName
            val place = chosen.indexOf(id)
            val mark = if (place >= 0) "${place + 1}  " else ""
            grid.addView(button("$mark${app.loadLabel(packageManager)}", place >= 0, "app:$id") {
                when {
                    id in chosen -> chosen.remove(id)
                    chosen.size == Prefs.MAX_APPS -> toast("That is ${Prefs.MAX_APPS} apps. Take one off first.")
                    else -> chosen.add(id)
                }
                prefs.apps = chosen
                build()
            }, GridLayout.LayoutParams().apply {
                width = (resources.displayMetrics.widthPixels - dp(96)) / 6
                height = dp(56)
            })
        }
        page.addView(grid)
    }

    private fun portRow() {
        val row = LinearLayout(this)
        shelf.allPorts().forEach { port ->
            val name = prefs.portName(port.label)
            val shown = name.ifEmpty { "No name" }
            row.addView(button("${port.label}: $shown", name.isNotEmpty(), "port:${port.label}") {
                val next = (Prefs.PORT_NAMES.indexOf(name) + 1) % Prefs.PORT_NAMES.size
                prefs.setPortName(port.label, Prefs.PORT_NAMES[next])
                build()
            }, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        page.addView(row)
    }

    private fun settingsRow() {
        val serviceOn = HomeButtonService.running != null
        val row = LinearLayout(this)
        val slot = { LinearLayout.LayoutParams(0, dp(56), 1f) }
        row.addView(button("Open Dekho on Home: ${if (prefs.dekhoIsHome) "On" else "Off"}", prefs.dekhoIsHome, "home") {
            prefs.dekhoIsHome = !prefs.dekhoIsHome
            build()
        }, slot())
        row.addView(button("Home button helper: ${if (serviceOn) "On" else "Off"}", serviceOn, "helper") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, slot())
        row.addView(button(if (prefs.remotePaired) "Phone remote: Ready" else "Set up phone remote", prefs.remotePaired, "remote") {
            val service = HomeButtonService.running
            if (service == null) {
                toast("Turn on the Home button helper first.")
            } else {
                toast("The TV will ask to allow this. Tick Always allow, then press OK.")
                service.pairInBackground { paired ->
                    toast(if (paired) "Phone remote is ready." else "The TV did not allow it. Check USB debugging is on, then try again.")
                    build()
                }
            }
        }, slot())
        page.addView(row)
    }

    private fun header() = LinearLayout(this).apply {
        gravity = Gravity.BOTTOM
        addView(words("Customise", 34f, Typeface.SERIF), LinearLayout.LayoutParams(0, -2, 1f))
        RemoteServer.address(this@CustomiseActivity)?.let {
            addView(words("Phone remote, same Wi-Fi: ${it.removePrefix("http://")}", 18f))
        }
    }

    private fun heading(text: String) {
        page.addView(words(text, 15f).apply {
            setTextColor(getColor(R.color.muted))
            setPadding(dp(6), dp(20), 0, dp(8))
        })
    }

    private fun words(text: String, size: Float, face: Typeface = Typeface.DEFAULT) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = face
        setTextColor(getColor(R.color.cream))
    }

    private fun button(text: String, on: Boolean, id: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        tag = id
        textSize = 15f
        gravity = Gravity.CENTER
        maxLines = 2
        isFocusable = true
        setPadding(dp(8), 0, dp(8), 0)
        setBackgroundResource(R.drawable.tile_bg)
        setTextColor(getColorStateList(if (on) R.color.focus_ink_on else R.color.focus_ink))
        typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
        setOnClickListener { action() }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
