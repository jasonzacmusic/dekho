package com.jasonzac.dekho

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** One app on the Watch row, found by the name it shows on the TV. */
private data class Watch(val name: String, val matches: (String) -> Boolean, val storeId: String)

/** Each screen press changes brightness by this much. */
private const val BRIGHTNESS_STEP = 5

class HomeActivity : Activity() {

    private val watchList = listOf(
        Watch("Prime Video", { "prime video" in it }, "com.amazon.amazonvideo.livingroom"),
        Watch("Netflix", { "netflix" in it }, "com.netflix.ninja"),
        Watch("JioHotstar", { "hotstar" in it }, "in.startv.hotstar"),
        Watch("Sony LIV", { "sony liv" in it || "sonyliv" in it }, "com.sonyliv"),
        Watch("YouTube", { it == "youtube" }, "com.google.android.youtube.tv"),
    )

    private val tv by lazy { getSystemService(TvInputManager::class.java) }
    private val brightness by lazy { Brightness(this) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var appsRow: LinearLayout
    private lateinit var inputsRow: LinearLayout
    private lateinit var quickRow: LinearLayout
    private lateinit var brightnessTile: View
    private lateinit var brightnessBar: ProgressBar
    private lateinit var brightnessValue: TextView
    private lateinit var brightnessName: TextView
    private lateinit var brightnessIcon: ImageView

    private var inputDots = mapOf<String, View>()
    private var inputStates = mutableMapOf<String, Int>()

    // Switches to a device the moment it comes alive while this screen is showing.
    private val inputWatcher = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) {
            val wasOff = inputStates[inputId] == TvInputManager.INPUT_STATE_DISCONNECTED
            inputStates[inputId] = state
            paintDot(inputId, state)
            if (wasOff && state == TvInputManager.INPUT_STATE_CONNECTED) openInput(inputId)
        }

        override fun onInputAdded(inputId: String) = refreshInputs()
        override fun onInputRemoved(inputId: String) = refreshInputs()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        appsRow = findViewById(R.id.apps)
        inputsRow = findViewById(R.id.inputs)
        quickRow = findViewById(R.id.quick)
        brightnessTile = findViewById(R.id.brightness)
        brightnessBar = findViewById(R.id.brightness_bar)
        brightnessValue = findViewById(R.id.brightness_value)
        brightnessName = findViewById(R.id.brightness_name)
        brightnessIcon = findViewById(R.id.brightness_icon)

        buildQuickRow()
        setupBrightness()
    }

    override fun onResume() {
        super.onResume()
        HomeButtonService.showingStockHome = false
        refreshApps()
        refreshInputs()
        paintBrightness()
        tv?.registerCallback(inputWatcher, handler)
        if (currentFocus == null) appsRow.getChildAt(0)?.requestFocus()
    }

    override fun onPause() {
        tv?.unregisterCallback(inputWatcher)
        super.onPause()
    }

    // This is the home screen, so Back has nowhere to go.
    override fun onBackPressed() = Unit

    // --- Watch row ---

    private fun refreshApps() {
        val focused = appsRow.indexOfChild(appsRow.findFocus())
        appsRow.removeAllViews()
        val installed = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
        )
        watchList.forEachIndexed { i, watch ->
            val found = installed.firstOrNull { watch.matches(it.loadLabel(packageManager).toString().lowercase()) }
            appsRow.addView(appTile(watch, found), rowParams(i, 16f / 9f))
        }
        if (focused >= 0) appsRow.getChildAt(focused)?.requestFocus()
    }

    private fun appTile(watch: Watch, app: ResolveInfo?): View {
        val tile = FrameLayout(this).apply {
            isFocusable = true
            foreground = getDrawable(R.drawable.app_ring)
            setBackgroundColor(getColor(R.color.tile))
            roundCorners()
        }
        val art = app?.activityInfo?.loadBanner(packageManager)
            ?: app?.activityInfo?.applicationInfo?.loadBanner(packageManager)
        if (art != null) {
            tile.addView(ImageView(this).apply {
                setImageDrawable(art)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            tile.addView(TextView(this).apply {
                text = if (app == null) "${watch.name}\nGet app" else watch.name
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.cream))
                textSize = 15f
            }, FrameLayout.LayoutParams(-1, -1))
        }
        tile.setOnClickListener {
            if (app != null) {
                launch(packageManager.getLeanbackLaunchIntentForPackage(app.activityInfo.packageName))
            } else {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${watch.storeId}")))
            }
        }
        tile.growOnFocus()
        return tile
    }

    // --- Inputs row ---

    private fun refreshInputs() {
        val focused = inputsRow.indexOfChild(inputsRow.findFocus())
        inputsRow.removeAllViews()
        val all = tv?.tvInputList.orEmpty()
        val ports = all.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            .sortedBy { it.loadLabel(this).toString() }

        val dots = mutableMapOf<String, View>()
        inputStates.clear()
        ports.forEachIndexed { i, port ->
            // A device that names itself over the cable shows up as a child of its port.
            val device = all.firstOrNull { it.parentId == port.id }?.loadLabel(this)?.toString()
            val state = tv!!.getInputState(port.id)
            inputStates[port.id] = state
            val tile = inputTile(port, device)
            dots[port.id] = tile.findViewWithTag("dot")
            inputsRow.addView(tile, rowParams(i, 0f, 62))
        }
        inputDots = dots
        ports.forEach { paintDot(it.id, inputStates.getValue(it.id)) }
        if (ports.isEmpty()) {
            inputsRow.addView(TextView(this).apply {
                text = "This TV did not list its HDMI inputs."
                setTextColor(getColor(R.color.muted))
            })
        }
        if (focused >= 0) inputsRow.getChildAt(focused)?.requestFocus()
    }

    private fun inputTile(port: TvInputInfo, device: String?): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setBackgroundResource(R.drawable.tile_bg)
            setPadding(dp(18), 0, dp(18), 0)
        }
        tile.addView(View(this).apply {
            tag = "dot"
            setBackgroundResource(R.drawable.dot)
        }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })

        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = tileLabel(port.loadLabel(this).toString()).apply { textSize = 17f }
        words.addView(name)
        val sub = tileLabel(device ?: "").apply {
            textSize = 12f
            alpha = 0.7f
            visibility = if (device == null) View.GONE else View.VISIBLE
        }
        words.addView(sub)
        tile.addView(words)

        tile.setOnClickListener { openInput(port.id) }
        tile.growOnFocus()
        return tile
    }

    private fun paintDot(inputId: String, state: Int) {
        val color = when (state) {
            TvInputManager.INPUT_STATE_CONNECTED -> R.color.live
            TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> R.color.saffron
            else -> R.color.off
        }
        inputDots[inputId]?.backgroundTintList = ColorStateList.valueOf(getColor(color))
    }

    private fun openInput(inputId: String) =
        launch(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId)))

    // --- Picture row ---

    private fun setupBrightness() {
        brightnessTile.growOnFocus(1.02f)
        brightnessTile.setOnFocusChangeListener { v, hasFocus ->
            v.animateGrow(hasFocus, 1.02f)
            val ink = getColor(if (hasFocus) R.color.on_focus else R.color.cream)
            brightnessName.setTextColor(ink)
            brightnessValue.setTextColor(ink)
            brightnessIcon.imageTintList = ColorStateList.valueOf(ink)
        }
        brightnessTile.setOnKeyListener { _, code, event ->
            val step = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -BRIGHTNESS_STEP
                KeyEvent.KEYCODE_DPAD_RIGHT -> BRIGHTNESS_STEP
                else -> return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                brightness.set(brightness.get() + step)
                paintBrightness()
            }
            true
        }
    }

    private fun paintBrightness() {
        val level = brightness.get()
        brightnessBar.progress = level
        brightnessValue.text = level.toString()
    }

    // --- Settings row ---

    private fun buildQuickRow() {
        val tiles = listOf(
            Triple("Wi-Fi", R.drawable.ic_wifi) { launch(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            Triple("Bluetooth", R.drawable.ic_bluetooth) { launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            Triple("Sound", R.drawable.ic_sound) { launch(Intent(Settings.ACTION_SOUND_SETTINGS)) },
            Triple("Display", R.drawable.ic_display) { launch(Intent(Settings.ACTION_DISPLAY_SETTINGS)) },
            Triple("All settings", R.drawable.ic_settings) { launch(Intent(Settings.ACTION_SETTINGS)) },
            Triple("Google TV", R.drawable.ic_grid) { openStockHome() },
        )
        tiles.forEachIndexed { i, (name, icon, action) ->
            quickRow.addView(quickTile(name, icon, action), rowParams(i, 0f, 62))
        }
    }

    private fun quickTile(name: String, icon: Int, action: () -> Unit): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            setBackgroundResource(R.drawable.tile_bg)
        }
        tile.addView(ImageView(this).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        tile.addView(tileLabel(name).apply {
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })
        tile.setOnClickListener { action() }
        tile.growOnFocus()
        return tile
    }

    private fun openStockHome() {
        HomeButtonService.showingStockHome = true
        launch(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(HomeButtonService.STOCK_HOME),
        )
    }

    // --- Shared pieces ---

    private fun launch(intent: Intent?) {
        try {
            startActivity(requireNotNull(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "This TV can't open that", Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "This TV can't open that", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tileLabel(words: String) = TextView(this).apply {
        text = words
        fontFeatureSettings = "tnum"
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        // Dark on the bright focused tile, cream otherwise.
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(getColor(R.color.on_focus), getColor(R.color.cream)),
            ),
        )
        isDuplicateParentStateEnabled = true
    }

    private fun rowParams(index: Int, aspect: Float, heightDp: Int = 0): LinearLayout.LayoutParams {
        val width = (resources.displayMetrics.widthPixels - dp(80) - dp(14) * 4) / 5f
        val height = if (aspect > 0f) (width / aspect).toInt() else dp(heightDp)
        return LinearLayout.LayoutParams(0, height, 1f).apply {
            if (index > 0) marginStart = dp(14)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun View.roundCorners() {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
        }
        clipToOutline = true
    }

    private fun View.growOnFocus(scale: Float = 1.06f) {
        setOnFocusChangeListener { v, hasFocus -> v.animateGrow(hasFocus, scale) }
    }

    private fun View.animateGrow(grown: Boolean, scale: Float) {
        val target = if (grown) scale else 1f
        elevation = if (grown) dp(8).toFloat() else 0f
        animate().scaleX(target).scaleY(target).setDuration(130)
            .setInterpolator(DecelerateInterpolator()).start()
    }
}
