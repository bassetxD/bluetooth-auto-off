package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.BluetoothStateRepository
import com.example.data.model.BluetoothDeviceInfo
import com.example.data.model.LogType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun readStringFromContext() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Bluetooth Auto Off", appName)
  }

  @Test
  fun testRepositoryInitialStateAndUpdates() {
    BluetoothStateRepository.setServiceRunning(true)
    assertTrue(BluetoothStateRepository.isServiceRunning.value)

    BluetoothStateRepository.setServiceRunning(false)
    assertFalse(BluetoothStateRepository.isServiceRunning.value)

    // Test Idle delay setting (defaults to 60s / 1 min)
    BluetoothStateRepository.setIdleDelay(60)
    assertEquals(60, BluetoothStateRepository.idleDelaySeconds.value)

    // Test countdown state
    BluetoothStateRepository.setCountdown(45)
    assertEquals(45, BluetoothStateRepository.countdownSecondsRemaining.value)
    BluetoothStateRepository.setCountdown(null)
    assertNull(BluetoothStateRepository.countdownSecondsRemaining.value)

    // Test connected devices
    val device = BluetoothDeviceInfo("Sony WH-1000XM4", "00:11:22:33:44:55")
    BluetoothStateRepository.setConnectedDevices(listOf(device))
    assertEquals(1, BluetoothStateRepository.connectedDevices.value.size)
    assertEquals("Sony WH-1000XM4", BluetoothStateRepository.connectedDevices.value[0].name)

    // Test logging
    BluetoothStateRepository.addLog("Test Title", "Test Details", LogType.INFO)
    assertTrue(BluetoothStateRepository.recentLogs.value.isNotEmpty())
    assertEquals("Test Title", BluetoothStateRepository.recentLogs.value[0].title)
  }
}
