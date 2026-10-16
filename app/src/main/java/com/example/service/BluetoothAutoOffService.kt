package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.BluetoothStateRepository
import com.example.data.model.BluetoothDeviceInfo
import com.example.data.model.LogType
import com.example.receiver.BluetoothStateReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BluetoothAutoOffService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var countdownJob: Job? = null
    private var isReceiverRegistered = false

    private val bluetoothManager: BluetoothManager? by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    // Tracks currently connected devices
    private val connectedDevicesMap = mutableMapOf<String, BluetoothDeviceInfo>()

    private val bluetoothReceiver = BluetoothStateReceiver(
        onStateChanged = { isEnabled ->
            handleBluetoothStateChanged(isEnabled)
        },
        onDeviceConnected = { device ->
            handleDeviceConnected(device)
        },
        onDeviceDisconnected = { device ->
            handleDeviceDisconnected(device)
        }
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BluetoothAutoOffService onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_START -> {
                startForegroundServiceSafely()
                registerBluetoothReceiverSafely()
                syncInitialBluetoothState()
            }
            ACTION_STOP -> {
                stopMonitoringService()
            }
            ACTION_CANCEL_TIMER -> {
                cancelCountdown(reason = "Cancelled by user")
            }
            ACTION_TURN_OFF_NOW -> {
                cancelCountdown(reason = null)
                executeAutoTurnOff()
            }
            ACTION_SIMULATE_DISCONNECT -> {
                simulateDeviceDisconnect()
            }
            ACTION_SIMULATE_CONNECT -> {
                simulateDeviceConnect()
            }
        }

        return START_STICKY
    }

    private fun startForegroundServiceSafely() {
        BluetoothStateRepository.setServiceRunning(true)
        val notification = buildNotification(
            title = getString(R.string.service_title),
            text = getString(R.string.service_running_desc),
            isCountingDown = false
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
        }

        BluetoothStateRepository.addLog(
            title = "Service Started",
            detail = "Bluetooth Auto-Off monitor is now active in the background.",
            type = LogType.SUCCESS
        )
    }

    private fun registerBluetoothReceiverSafely() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(bluetoothReceiver, filter)
                }
                isReceiverRegistered = true
                Log.d(TAG, "Bluetooth receiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register receiver: ${e.message}", e)
            }
        }
    }

    private fun syncInitialBluetoothState() {
        val adapter = bluetoothAdapter
        val isEnabled = adapter?.isEnabled == true
        BluetoothStateRepository.setBluetoothEnabled(isEnabled)

        if (isEnabled) {
            checkCurrentConnectionsAndEvaluate()
        } else {
            cancelCountdown(reason = null)
            BluetoothStateRepository.setConnectedDevices(emptyList())
            updateNotification(
                title = getString(R.string.service_title),
                text = "Bluetooth is currently OFF",
                isCountingDown = false
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkCurrentConnectionsAndEvaluate() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            BluetoothStateRepository.setBluetoothEnabled(false)
            cancelCountdown(reason = null)
            return
        }

        // Check if known profiles have connected devices
        val hasProfileConnection = try {
            adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED ||
                    adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        } catch (e: SecurityException) {
            false
        }

        if (connectedDevicesMap.isEmpty() && !hasProfileConnection) {
            // No devices connected: Start countdown!
            BluetoothStateRepository.setConnectedDevices(emptyList())
            startCountdownTimer()
        } else {
            cancelCountdown(reason = "Devices are actively connected")
            BluetoothStateRepository.setConnectedDevices(connectedDevicesMap.values.toList())
            val count = if (connectedDevicesMap.isNotEmpty()) connectedDevicesMap.size else 1
            updateNotification(
                title = getString(R.string.service_title),
                text = "$count device(s) connected • Auto-off paused",
                isCountingDown = false
            )
        }
    }

    private fun handleBluetoothStateChanged(isEnabled: Boolean) {
        BluetoothStateRepository.setBluetoothEnabled(isEnabled)
        if (isEnabled) {
            BluetoothStateRepository.addLog(
                title = "Bluetooth Turned ON",
                detail = "Checking for connected devices...",
                type = LogType.INFO
            )
            checkCurrentConnectionsAndEvaluate()
        } else {
            cancelCountdown(reason = "Bluetooth was powered off")
            connectedDevicesMap.clear()
            BluetoothStateRepository.setConnectedDevices(emptyList())
            BluetoothStateRepository.addLog(
                title = "Bluetooth Turned OFF",
                detail = "Monitor is standing by until Bluetooth is switched on.",
                type = LogType.INFO
            )
            updateNotification(
                title = getString(R.string.service_title),
                text = "Bluetooth is OFF • Standing by",
                isCountingDown = false
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceConnected(device: BluetoothDevice) {
        val name = try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }
        val info = BluetoothDeviceInfo(name = name, address = device.address)
        connectedDevicesMap[device.address] = info
        BluetoothStateRepository.setConnectedDevices(connectedDevicesMap.values.toList())

        BluetoothStateRepository.addLog(
            title = "Device Connected",
            detail = "$name ($device)",
            type = LogType.INFO
        )

        // Cancel countdown immediately if a device connects
        cancelCountdown(reason = "Device connected: $name")

        updateNotification(
            title = getString(R.string.service_title),
            text = "$name connected • Monitoring active",
            isCountingDown = false
        )
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceDisconnected(device: BluetoothDevice) {
        val removed = connectedDevicesMap.remove(device.address)
        val name = removed?.name ?: device.address
        BluetoothStateRepository.setConnectedDevices(connectedDevicesMap.values.toList())

        BluetoothStateRepository.addLog(
            title = "Device Disconnected",
            detail = "$name disconnected from device.",
            type = LogType.WARNING
        )

        checkCurrentConnectionsAndEvaluate()
    }

    private fun startCountdownTimer() {
        if (countdownJob?.isActive == true) return

        val delaySeconds = BluetoothStateRepository.idleDelaySeconds.value
        BluetoothStateRepository.addLog(
            title = "Auto-Off Timer Started",
            detail = "No devices connected. Bluetooth will turn off in $delaySeconds seconds.",
            type = LogType.WARNING
        )

        countdownJob = serviceScope.launch {
            for (remaining in delaySeconds downTo 1) {
                BluetoothStateRepository.setCountdown(remaining)
                updateNotification(
                    title = getString(R.string.service_title),
                    text = String.format(getString(R.string.service_countdown_format), remaining),
                    isCountingDown = true
                )
                delay(1000L)
            }

            BluetoothStateRepository.setCountdown(null)

            // Final check before powering down
            val adapter = bluetoothAdapter
            if (adapter?.isEnabled == true && connectedDevicesMap.isEmpty()) {
                executeAutoTurnOff()
            } else {
                Log.d(TAG, "Countdown ended but conditions changed: adapter=${adapter?.isEnabled}, count=${connectedDevicesMap.size}")
            }
        }
    }

    private fun cancelCountdown(reason: String?) {
        if (countdownJob?.isActive == true) {
            countdownJob?.cancel()
            countdownJob = null
            BluetoothStateRepository.setCountdown(null)
            if (reason != null) {
                BluetoothStateRepository.addLog(
                    title = "Timer Cancelled",
                    detail = reason,
                    type = LogType.INFO
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeAutoTurnOff() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        try {
            @Suppress("DEPRECATION")
            val result = adapter.disable()

            if (result) {
                BluetoothStateRepository.incrementAutoOffCount()
                BluetoothStateRepository.setBluetoothEnabled(false)
                BluetoothStateRepository.addLog(
                    title = "Bluetooth Turned Off",
                    detail = "Auto-off executed after idle timeout. Battery saved!",
                    type = LogType.SUCCESS
                )
                updateNotification(
                    title = "Bluetooth Turned Off",
                    text = "Bluetooth powered off to save battery.",
                    isCountingDown = false
                )
            } else {
                // On recent Android versions, disable() may require manual toggle if restricted by OS
                BluetoothStateRepository.addLog(
                    title = "Turn Off Prompted",
                    detail = "System security restricted direct disable. Tap to open Bluetooth settings.",
                    type = LogType.ACTION
                )
                notifyUserManualTurnOff()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while disabling Bluetooth: ${e.message}", e)
            BluetoothStateRepository.addLog(
                title = "Permission Error",
                detail = "Missing permission to change Bluetooth state: ${e.message}",
                type = LogType.WARNING
            )
        }
    }

    private fun notifyUserManualTurnOff() {
        val settingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            201,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Idle Timeout: Turn Off Bluetooth")
            .setContentText("No devices connected. Tap here to turn off Bluetooth and save battery.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(PROMPT_NOTIFICATION_ID, notification)
    }

    // Testing simulation aids for emulator and quick verification
    private fun simulateDeviceDisconnect() {
        connectedDevicesMap.clear()
        BluetoothStateRepository.setConnectedDevices(emptyList())
        BluetoothStateRepository.addLog(
            title = "Simulated Disconnect",
            detail = "Disconnected all devices for testing.",
            type = LogType.WARNING
        )
        checkCurrentConnectionsAndEvaluate()
    }

    private fun simulateDeviceConnect() {
        val mockDevice = BluetoothDeviceInfo(
            name = "Wireless Headset (Test)",
            address = "00:11:22:33:44:55",
            type = "Headphones"
        )
        connectedDevicesMap[mockDevice.address] = mockDevice
        BluetoothStateRepository.setConnectedDevices(connectedDevicesMap.values.toList())
        BluetoothStateRepository.addLog(
            title = "Simulated Connect",
            detail = "Connected to ${mockDevice.name}",
            type = LogType.INFO
        )
        cancelCountdown(reason = "Simulated device connected")
        updateNotification(
            title = getString(R.string.service_title),
            text = "${mockDevice.name} connected • Monitoring active",
            isCountingDown = false
        )
    }

    private fun updateNotification(title: String, text: String, isCountingDown: Boolean) {
        val notification = buildNotification(title, text, isCountingDown)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String, isCountingDown: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isCountingDown) {
            // Action to cancel the countdown
            val cancelIntent = Intent(this, BluetoothAutoOffService::class.java).apply {
                action = ACTION_CANCEL_TIMER
            }
            val cancelPending = PendingIntent.getService(
                this,
                101,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.action_cancel_timer), cancelPending)

            // Action to turn off right now
            val offNowIntent = Intent(this, BluetoothAutoOffService::class.java).apply {
                action = ACTION_TURN_OFF_NOW
            }
            val offNowPending = PendingIntent.getService(
                this,
                102,
                offNowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.action_turn_off_now), offNowPending)
        }

        return builder.build()
    }

    private fun stopMonitoringService() {
        Log.d(TAG, "Stopping BluetoothAutoOffService")
        cancelCountdown(reason = null)
        BluetoothStateRepository.setServiceRunning(false)
        BluetoothStateRepository.setCountdown(null)

        if (isReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver: ${e.message}")
            }
            isReceiverRegistered = false
        }

        BluetoothStateRepository.addLog(
            title = "Service Stopped",
            detail = "Auto-off monitoring disabled by user.",
            type = LogType.INFO
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BluetoothAutoOffService onDestroy")
        stopMonitoringService()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BluetoothAutoOffService"
        const val CHANNEL_ID = "bluetooth_auto_off_channel"
        const val NOTIFICATION_ID = 1001
        const val PROMPT_NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_CANCEL_TIMER = "com.example.service.ACTION_CANCEL_TIMER"
        const val ACTION_TURN_OFF_NOW = "com.example.service.ACTION_TURN_OFF_NOW"
        const val ACTION_SIMULATE_DISCONNECT = "com.example.service.ACTION_SIMULATE_DISCONNECT"
        const val ACTION_SIMULATE_CONNECT = "com.example.service.ACTION_SIMULATE_CONNECT"

        fun startService(context: Context) {
            val intent = Intent(context, BluetoothAutoOffService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BluetoothAutoOffService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun cancelTimer(context: Context) {
            val intent = Intent(context, BluetoothAutoOffService::class.java).apply {
                action = ACTION_CANCEL_TIMER
            }
            context.startService(intent)
        }

        fun turnOffNow(context: Context) {
            val intent = Intent(context, BluetoothAutoOffService::class.java).apply {
                action = ACTION_TURN_OFF_NOW
            }
            context.startService(intent)
        }

        fun simulateDisconnect(context: Context) {
            val intent = Intent(context, BluetoothAutoOffService::class.java).apply {
                action = ACTION_SIMULATE_DISCONNECT
            }
            context.startService(intent)
        }

        fun simulateConnect(context: Context) {
            val intent = Intent(context, BluetoothAutoOffService::class.java).apply {
                action = ACTION_SIMULATE_CONNECT
            }
            context.startService(intent)
        }
    }
}
