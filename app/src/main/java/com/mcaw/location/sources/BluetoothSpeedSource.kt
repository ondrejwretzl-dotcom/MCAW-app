package com.mcaw.location.sources

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.UUID

class BluetoothSpeedSource(private val context: Context) {
    data class Sample(val speedMps: Float, val timestampMs: Long)

    private val speedServiceUuid: UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
    private val speedCharacteristicUuid: UUID = UUID.fromString("00002A5B-0000-1000-8000-00805f9b34fb")
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    @Volatile
    private var latestSample: Sample? = null
    private var started = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (!hasConnectPermission()) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            gatt = device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED && hasConnectPermission()) {
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(speedServiceUuid) ?: return
            val c = service.getCharacteristic(speedCharacteristicUuid) ?: return
            if (!hasConnectPermission()) return
            gatt.setCharacteristicNotification(c, true)
            val descriptor: BluetoothGattDescriptor? = c.getDescriptor(cccdUuid)
            descriptor?.let {
                @Suppress("DEPRECATION")
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(it)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == speedCharacteristicUuid) {
                @Suppress("DEPRECATION")
                val value = parseSpeed(characteristic.value)
                latestSample = Sample(value, SystemClock.elapsedRealtime())
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started || !hasScanPermission()) return
        started = true
        adapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        if (hasScanPermission()) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        gatt?.close()
        gatt = null
        started = false
    }

    fun latest(): Sample? = latestSample

    private fun parseSpeed(raw: ByteArray?): Float {
        // Generic placeholder parser for BLE CSC-like sensors.
        if (raw == null || raw.size < 2) return 0f
        val kmh = ((raw[1].toInt() and 0xFF) shl 8 or (raw[0].toInt() and 0xFF)) / 100f
        return kmh / 3.6f
    }

    private fun hasScanPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
