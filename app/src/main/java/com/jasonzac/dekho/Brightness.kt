package com.jasonzac.dekho

import android.content.Context
import android.provider.Settings

/** Reads and sets the TV's brightness on a 0 to 100 scale. */
class Brightness(private val context: Context) {

    fun get(): Int {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return (raw * 100 + 127) / 255
    }

    fun set(level: Int) {
        if (!Settings.System.canWrite(context)) return
        val raw = level.coerceIn(0, 100) * 255 / 100
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
    }
}
