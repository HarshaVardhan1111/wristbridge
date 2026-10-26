package com.wristbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wristbridge.service.BridgeService

/** Asks for the runtime permissions, then starts the bridge service. */
class MainActivity : ComponentActivity() {

    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private var allGranted by mutableStateOf(false)
    private var started by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = statusText(), textAlign = TextAlign.Center)
                    Chip(
                        modifier = Modifier.padding(top = 8.dp),
                        onClick = ::onButton,
                        label = { Text(if (allGranted) "Start" else "Grant permissions") },
                    )
                }
            }
        }
    }

    private fun statusText(): String = when {
        !allGranted -> "WristBridge needs Bluetooth and notification permissions."
        started -> "Running. Pair from iPhone Settings > Bluetooth."
        else -> "Ready."
    }

    private fun onButton() {
        if (allGranted) {
            BridgeService.start(this)
            started = true
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun refreshPermissions() {
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        allGranted = missing.isEmpty()
        if (allGranted) Log.i(TAG, "Permissions granted") else Log.i(TAG, "Permissions missing: $missing")
    }
}
