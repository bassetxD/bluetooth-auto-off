package com.example.data.model

enum class LogType {
    INFO,
    WARNING,
    SUCCESS,
    ACTION
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val type: String = "Connected Device"
)

data class AutoOffLog(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val title: String,
    val detail: String,
    val type: LogType = LogType.INFO
)
