package com.example.data

import com.example.data.model.AutoOffLog
import com.example.data.model.BluetoothDeviceInfo
import com.example.data.model.LogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object BluetoothStateRepository {

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val connectedDevices: StateFlow<List<BluetoothDeviceInfo>> = _connectedDevices.asStateFlow()

    private val _countdownSecondsRemaining = MutableStateFlow<Int?>(null)
    val countdownSecondsRemaining: StateFlow<Int?> = _countdownSecondsRemaining.asStateFlow()

    private val _idleDelaySeconds = MutableStateFlow(60)
    val idleDelaySeconds: StateFlow<Int> = _idleDelaySeconds.asStateFlow()

    private val _autoOffCount = MutableStateFlow(0)
    val autoOffCount: StateFlow<Int> = _autoOffCount.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<AutoOffLog>>(emptyList())
    val recentLogs: StateFlow<List<AutoOffLog>> = _recentLogs.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun setBluetoothEnabled(enabled: Boolean) {
        _isBluetoothEnabled.value = enabled
    }

    fun setConnectedDevices(devices: List<BluetoothDeviceInfo>) {
        _connectedDevices.value = devices
    }

    fun setCountdown(secondsRemaining: Int?) {
        _countdownSecondsRemaining.value = secondsRemaining
    }

    fun setIdleDelay(seconds: Int) {
        _idleDelaySeconds.value = seconds
    }

    fun incrementAutoOffCount() {
        _autoOffCount.update { it + 1 }
    }

    fun addLog(title: String, detail: String, type: LogType = LogType.INFO) {
        _recentLogs.update { current ->
            val newEntry = AutoOffLog(title = title, detail = detail, type = type)
            (listOf(newEntry) + current).take(50)
        }
    }

    fun clearLogs() {
        _recentLogs.value = emptyList()
    }
}
