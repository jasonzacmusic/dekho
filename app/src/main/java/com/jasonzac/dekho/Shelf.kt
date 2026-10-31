package com.jasonzac.dekho

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/** One thing an owner can jump to. */
class ShelfApp(val name: String, val packageName: String?, val storeId: String?, val art: Drawable?)

class ShelfPort(val id: String, val label: String, val device: String?, val state: Int)

/** The owner's apps and HDMI ports, shared by the home screen, the strip and the remote. */
class Shelf(private val context: Context) {

    private val prefs = Prefs(context)
    private val tv = context.getSystemService(TvInputManager::class.java)
    private val pm = context.packageManager

    private class Default(val name: String, val matches: (String) -> Boolean, val storeId: String)

    private val defaults = listOf(
        Default("Prime Video", { "prime video" in it }, "com.amazon.amazonvideo.livingroom"),
        Default("Netflix", { "netflix" in it }, "com.netflix.ninja"),
        Default("JioHotstar", { "hotstar" in it }, "in.startv.hotstar"),
        Default("Sony LIV", { "sony liv" in it || "sonyliv" in it }, "com.sonyliv"),
        Default("FanCode", { "fancode" in it }, "com.fancode.tv"),
        Default("YouTube", { it == "youtube" }, "com.google.android.youtube.tv"),
    )

    fun installed(): List<ResolveInfo> = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
    ).filter { it.activityInfo.packageName != context.packageName }
        .sortedBy { it.loadLabel(pm).toString().lowercase() }

    private fun art(app: ResolveInfo): Drawable? = app.activityInfo.loadBanner(pm)
        ?: app.activityInfo.applicationInfo.loadBanner(pm)

    private fun shelfApp(app: ResolveInfo) =
        ShelfApp(app.loadLabel(pm).toString(), app.activityInfo.packageName, null, art(app))

    fun apps(): List<ShelfApp> {
        val all = installed()
        prefs.apps?.let { chosen ->
            return chosen.mapNotNull { p -> all.firstOrNull { it.activityInfo.packageName == p } }.map(::shelfApp)
        }
        return defaults.map { wanted ->
            val found = all.firstOrNull { wanted.matches(it.loadLabel(pm).toString().lowercase()) }
            found?.let(::shelfApp) ?: ShelfApp(wanted.name, null, wanted.storeId, null)
        }
    }

    fun ports() = allPorts().filter { prefs.portName(it.label) != Prefs.HIDDEN }

    /** Every port, with hidden ones too. */
    fun allPorts(): List<ShelfPort> {
        val all = tv?.tvInputList.orEmpty()
        return all.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            .map { port ->
                val label = port.loadLabel(context).toString()
                // A device that names itself over the cable shows up as a child of its port.
                val named = all.firstOrNull { it.parentId == port.id }?.loadLabel(context)?.toString()
                ShelfPort(port.id, label, named ?: prefs.portName(label).takeIf { it.isNotEmpty() && it != Prefs.HIDDEN }, tv!!.getInputState(port.id))
            }
            .sortedBy { it.label }
    }

    fun openPort(id: String) = context.startActivity(portIntent(id))

    fun openApp(packageName: String) {
        pm.getLeanbackLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let(context::startActivity)
    }

    companion object {
        fun portIntent(id: String) = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
