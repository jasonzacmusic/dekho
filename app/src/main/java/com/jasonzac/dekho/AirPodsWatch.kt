package com.jasonzac.dekho

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/** Connects the paired headphones when AirPods nearby announce they are out of the case. */
class AirPodsWatch(private val context: Context) {

    private val headphones = Headphones(context) {}
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var lastTry = 0L
    private var scanning = false

    private val sighting = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(APPLE) ?: return
            Log.i(TAG, "seen ${result.rssi} ${data.joinToString("") { "%02x".format(it) }}")
            if (result.rssi < NEARBY || headphones.connectedName() != null) return
            val now = SystemClock.elapsedRealtime()
            if (lastTry != 0L && now - lastTry < WAIT_BETWEEN_TRIES) return
            lastTry = now
            Log.i(TAG, "connect ${headphones.connect()}")
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // The TV switches Bluetooth off in standby, which ends any scan without a word.
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) scanning = false
            refresh()
        }
    }

    fun start() {
        headphones.start()
        context.registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        })
        refresh()
    }

    fun stop() {
        context.unregisterReceiver(screen)
        stopScan()
        headphones.stop()
    }

    // Only look while the TV is on, so a TV in standby never takes the AirPods.
    private fun refresh() {
        if (context.getSystemService(PowerManager::class.java).isInteractive) startScan() else stopScan()
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanning || scanner == null) return
        val filter = ScanFilter.Builder()
            .setManufacturerData(APPLE, byteArrayOf(PROXIMITY_PAIRING), byteArrayOf(-1))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        try {
            scanner.startScan(listOf(filter), settings, sighting)
            scanning = true
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to look for AirPods", e)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(sighting)
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to look for AirPods", e)
        }
    }

    private companion object {
        const val TAG = "Dekho"
        const val APPLE = 0x004C
        const val PROXIMITY_PAIRING: Byte = 0x07
        const val NEARBY = -75
        const val WAIT_BETWEEN_TRIES = 30_000L
    }
}
