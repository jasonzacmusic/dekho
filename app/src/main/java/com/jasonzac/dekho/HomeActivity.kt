package com.jasonzac.dekho

import android.app.Activity
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** TCL has no standard shortcut to its Bluetooth page. */
private val TCL_BLUETOOTH =
    ComponentName("com.android.tv.settings", "com.tcl.settings.accessories.AccessoriesActivity")

/** What is plugged into each port, for devices that do not announce themselves. */
private val HDMI_NAMES = mapOf("HDMI 2" to "Apple TV")

/** One app on the Watch row, found by the name it shows on the TV. */
private data class Watch(val name: String, val matches: (String) -> Boolean, val storeId: String)

class HomeActivity : Activity() {

    private val watchList = listOf(
        Watch("Prime Video", { "prime video" in it }, "com.amazon.amazonvideo.livingroom"),
        Watch("Netflix", { "netflix" in it }, "com.netflix.ninja"),
        Watch("JioHotstar", { "hotstar" in it }, "in.startv.hotstar"),
        Watch("Sony LIV", { "sony liv" in it || "sonyliv" in it }, "com.sonyliv"),
        Watch("YouTube", { it == "youtube" }, "com.google.android.youtube.tv"),
    )

    private val tv by lazy { getSystemService(TvInputManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var selected: TextView
    private lateinit var appsRow: LinearLayout
    private lateinit var inputsRow: LinearLayout
    private lateinit var quickRow: LinearLayout

    private lateinit var wifiNote: TextView
    private lateinit var bluetoothNote: TextView
    private val headphones by lazy { Headphones(this) { handler.post { paintConnections() } } }
    private val network by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkWatcher = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { handler.post { paintConnections() } }
        override fun onLost(n: Network) { handler.post { paintConnections() } }
    }

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
        selected = findViewById(R.id.selected)
        appsRow = findViewById(R.id.apps)
        inputsRow = findViewById(R.id.inputs)
        quickRow = findViewById(R.id.quick)
        buildQuickRow()
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    override fun onResume() {
        super.onResume()
        HomeButtonService.showingStockHome = false
        HomeButtonService.setDekhoIsHome(this, true)
        refreshApps()
        refreshInputs()
        tv?.registerCallback(inputWatcher, handler)
        headphones.start()
        network.registerDefaultNetworkCallback(networkWatcher)
        paintConnections()
        if (currentFocus == null) appsRow.getChildAt(0)?.requestFocus()
    }

    override fun onPause() {
        tv?.unregisterCallback(inputWatcher)
        headphones.stop()
        network.unregisterNetworkCallback(networkWatcher)
        super.onPause()
    }

    // This is the home screen, so Back has nowhere to go.
    @Suppress("OVERRIDE_DEPRECATION")
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
        tile.growOnFocus(watch.name)
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
                ?: HDMI_NAMES[port.loadLabel(this).toString()]
            val state = tv!!.getInputState(port.id)
            inputStates[port.id] = state
            val tile = inputTile(port, device)
            // Down from each input lands on the settings tile beneath it.
            val below = i * (quickRow.childCount - 1) / maxOf(ports.size - 1, 1)
            tile.nextFocusDownId = quickRow.getChildAt(below).id
            dots[port.id] = tile.findViewWithTag("dot")
            inputsRow.addView(tile, rowParams(i, 0f, 84))
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

        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isDuplicateParentStateEnabled = true
        }
        val name = tileLabel(port.loadLabel(this).toString()).apply { textSize = 22f }
        words.addView(name)
        val sub = tileLabel(device ?: "").apply {
            textSize = 14f
            visibility = if (device == null) View.GONE else View.VISIBLE
        }
        words.addView(sub)
        tile.addView(words)

        tile.setOnClickListener { openInput(port.id) }
        tile.growOnFocus(device?.let { "$it · ${port.loadLabel(this)}" } ?: port.loadLabel(this).toString())
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

    // --- Settings row ---

    private fun buildQuickRow() {
        val tiles = listOf(
            Triple("Brightness", R.drawable.ic_sun) { openBrightness() },
            Triple("Picture", R.drawable.ic_display) { launch(Intent(Settings.ACTION_DISPLAY_SETTINGS)) },
            Triple("Wi-Fi", R.drawable.ic_wifi) { launch(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            Triple("Bluetooth", R.drawable.ic_bluetooth) { connectHeadphones() },
            Triple("Sound", R.drawable.ic_sound) { launch(Intent(Settings.ACTION_SOUND_SETTINGS)) },
            Triple("All settings", R.drawable.ic_settings) { launch(Intent(Settings.ACTION_SETTINGS)) },
            Triple("Google TV", R.drawable.ic_grid) { openStockHome() },
        )
        tiles.forEachIndexed { i, (name, icon, action) ->
            quickRow.addView(quickTile(name, icon, action), rowParams(i, 0f, 96))
        }
    }

    private fun paintConnections() {
        val online = network.getNetworkCapabilities(network.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        paintNote(wifiNote, if (online) "Connected" else "Offline", online)
        val phones = headphones.connectedName()
        paintNote(bluetoothNote, phones ?: "Not connected", phones != null)
    }

    private fun paintNote(note: TextView, words: String, good: Boolean) {
        note.text = words
        note.alpha = if (good) 1f else 0.65f
    }

    private fun connectHeadphones() {
        if (headphones.connectedName() != null || !headphones.connect()) {
            launch(Intent().setComponent(TCL_BLUETOOTH))
        } else {
            paintNote(bluetoothNote, "Connecting…", false)
        }
    }

    private fun quickTile(name: String, icon: Int, action: () -> Unit): View {
        val tile = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            setBackgroundResource(R.drawable.tile_bg)
        }
        tile.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = focusInk()
            isDuplicateParentStateEnabled = true
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        tile.addView(tileLabel(name).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        val note = tileLabel("").apply {
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), dp(2), dp(6), 0)
        }
        if (name == "Wi-Fi") wifiNote = note
        if (name == "Bluetooth") bluetoothNote = note
        if (name == "Wi-Fi" || name == "Bluetooth") tile.addView(note)
        tile.setOnClickListener { action() }
        if (name == "Bluetooth") {
            tile.setOnLongClickListener {
                launch(Intent().setComponent(TCL_BLUETOOTH))
                true
            }
        }
        if (name == "Google TV") {
            tile.setOnLongClickListener {
                HomeButtonService.setDekhoIsHome(this, false)
                Toast.makeText(this, "Google TV is your home screen. Open Dekho from Apps to switch back.", Toast.LENGTH_LONG).show()
                openStockHome()
                true
            }
        }
        tile.growOnFocus(name)
        return tile
    }

    // The TV's own slider is the only thing that moves its backlight.
    private fun openBrightness() {
        launch(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        HomeButtonService.running?.pressBrightnessRow()
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
        setTextColor(focusInk())
        isDuplicateParentStateEnabled = true
    }

    // Dark on the bright focused tile, cream otherwise.
    private fun focusInk() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(getColor(R.color.on_focus), getColor(R.color.cream)),
    )

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

    private fun View.growOnFocus(name: String) {
        setOnFocusChangeListener { v, hasFocus ->
            v.animateGrow(hasFocus, 1.08f)
            if (hasFocus) selected.text = name
        }
    }

    private fun View.animateGrow(grown: Boolean, scale: Float) {
        val target = if (grown) scale else 1f
        elevation = if (grown) dp(12).toFloat() else 0f
        animate().scaleX(target).scaleY(target).setDuration(130)
            .setInterpolator(DecelerateInterpolator()).start()
    }
}
