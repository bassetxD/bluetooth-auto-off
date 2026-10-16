package com.example.receiver

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BluetoothStateReceiver(
    private val onStateChanged: (isEnabled: Boolean) -> Unit = {},
    private val onDeviceConnected: (device: BluetoothDevice) -> Unit = {},
    private val onDeviceDisconnected: (device: BluetoothDevice) -> Unit = {}
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "BluetoothStateReceiver onReceive action: $action")

        when (action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val isEnabled = state == BluetoothAdapter.STATE_ON
                Log.d(TAG, "ACTION_STATE_CHANGED: state=$state, isEnabled=$isEnabled")
                onStateChanged(isEnabled)
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device?.let {
                    Log.d(TAG, "ACTION_ACL_CONNECTED: device=${it.address}")
                    onDeviceConnected(it)
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device?.let {
                    Log.d(TAG, "ACTION_ACL_DISCONNECTED: device=${it.address}")
                    onDeviceDisconnected(it)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BluetoothReceiver"
    }
}
