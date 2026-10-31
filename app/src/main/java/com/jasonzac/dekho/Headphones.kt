package com.jasonzac.dekho

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager

/** Tells whether headphones are connected, and connects the paired pair on request. */
class Headphones(private val context: Context, private val onChange: () -> Unit) {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var a2dp: BluetoothA2dp? = null

    private val stateWatcher = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = onChange()
    }

    private val allowed
        get() = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        context.registerReceiver(stateWatcher, IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED))
        adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as BluetoothA2dp
                onChange()
            }

            override fun onServiceDisconnected(profile: Int) {
                a2dp = null
            }
        }, BluetoothProfile.A2DP)
    }

    fun stop() {
        context.unregisterReceiver(stateWatcher)
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dp = null
    }

    /** The name of the connected headphones, or null when nothing is connected. */
    fun connectedName(): String? {
        if (!allowed) return null
        return a2dp?.connectedDevices?.firstOrNull()?.name
    }

    private fun pairedHeadphones(): BluetoothDevice? {
        if (!allowed) return null
        return adapter?.bondedDevices?.firstOrNull {
            it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        }
    }

    /** Asks the paired headphones to connect. False when this TV refuses the request. */
    fun connect(): Boolean {
        val device = pairedHeadphones() ?: return false
        val profile = a2dp ?: return false
        return try {
            BluetoothA2dp::class.java.getMethod("connect", BluetoothDevice::class.java)
                .invoke(profile, device) as Boolean
        } catch (e: ReflectiveOperationException) {
            android.util.Log.w("Dekho", "connect refused", e)
            false
        }
    }
}
