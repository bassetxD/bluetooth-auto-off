package com.example.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BluetoothStateRepository
import com.example.data.model.AutoOffLog
import com.example.data.model.BluetoothDeviceInfo
import com.example.service.BluetoothAutoOffService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val isServiceRunning: StateFlow<Boolean> = BluetoothStateRepository.isServiceRunning
    val isBluetoothEnabled: StateFlow<Boolean> = BluetoothStateRepository.isBluetoothEnabled
    val connectedDevices: StateFlow<List<BluetoothDeviceInfo>> = BluetoothStateRepository.connectedDevices
    val countdownSecondsRemaining: StateFlow<Int?> = BluetoothStateRepository.countdownSecondsRemaining
    val idleDelaySeconds: StateFlow<Int> = BluetoothStateRepository.idleDelaySeconds
    val autoOffCount: StateFlow<Int> = BluetoothStateRepository.autoOffCount
    val recentLogs: StateFlow<List<AutoOffLog>> = BluetoothStateRepository.recentLogs

    init {
        refreshBluetoothState()
    }

    fun refreshBluetoothState() {
        val manager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        BluetoothStateRepository.setBluetoothEnabled(adapter?.isEnabled == true)
    }

    fun toggleService(enable: Boolean) {
        val context = getApplication<Application>()
        if (enable) {
            BluetoothAutoOffService.startService(context)
        } else {
            BluetoothAutoOffService.stopService(context)
        }
    }

    fun setIdleDelay(seconds: Int) {
        BluetoothStateRepository.setIdleDelay(seconds)
    }

    fun cancelActiveCountdown() {
        BluetoothAutoOffService.cancelTimer(getApplication())
    }

    fun forceTurnOffNow() {
        BluetoothAutoOffService.turnOffNow(getApplication())
    }

    fun simulateDisconnect() {
        BluetoothAutoOffService.simulateDisconnect(getApplication())
    }

    fun simulateConnect() {
        BluetoothAutoOffService.simulateConnect(getApplication())
    }

    fun clearLogs() {
        BluetoothStateRepository.clearLogs()
    }
}
