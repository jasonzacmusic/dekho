package com.jasonzac.dekho

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/** Everything an owner can change about their Dekho. */
class Prefs(context: Context) {

    private val store = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** Packages picked for the Watch row, in order. Null until the owner picks their own. */
    var apps: List<String>?
        get() = store.getString("apps", null)?.split(",")?.filter { it.isNotEmpty() }
        set(value) = store.edit().putString("apps", value?.joinToString(",")).apply()

    /** "Apple TV", "Hidden" or "" for one HDMI port. */
    fun portName(port: String) = store.getString("port:$port", "") ?: ""

    fun setPortName(port: String, name: String) = store.edit().putString("port:$port", name).apply()

    var dekhoIsHome: Boolean
        get() = store.getBoolean("dekho", true)
        set(value) = store.edit().putBoolean("dekho", value).apply()

    /** Secret part of the phone remote's address, so only people who have it can use it. */
    val remoteToken: String
        get() = store.getString("token", null) ?: ByteArray(4).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { store.edit().putString("token", it).apply() }

    var remoteKey: ByteArray?
        get() = store.getString("key", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(value) = store.edit().putString("key", Base64.encodeToString(value, Base64.NO_WRAP)).apply()

    var remotePublicKey: ByteArray?
        get() = store.getString("public", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(value) = store.edit().putString("public", Base64.encodeToString(value, Base64.NO_WRAP)).apply()

    var remotePaired: Boolean
        get() = store.getBoolean("allowed", false)
        set(value) = store.edit().putBoolean("allowed", value).apply()

    companion object {
        const val HIDDEN = "Hidden"
        val PORT_NAMES = listOf("", "Apple TV", "PlayStation", "Xbox", "Fire TV", "Set-top box", "Soundbar", HIDDEN)
        const val MAX_APPS = 6
    }
}
