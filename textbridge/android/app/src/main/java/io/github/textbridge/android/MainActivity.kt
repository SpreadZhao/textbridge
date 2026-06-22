package io.github.textbridge.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: TextBridgeViewModel by viewModels {
        TextBridgeViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TextBridgeTheme {
                val uiState = viewModel.uiState.collectAsStateWithLifecycle()
                val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    viewModel.refreshBluetoothDevices(granted)
                }

                fun hasBluetoothPermission(): Boolean {
                    return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                }

                LaunchedEffect(uiState.value.settingsTransportMode) {
                    if (uiState.value.settingsTransportMode == TransportMode.BLUETOOTH) {
                        viewModel.refreshBluetoothDevices(hasBluetoothPermission())
                    }
                }

                val requestBluetoothPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        viewModel.refreshBluetoothDevices(true)
                    }
                }
                val refreshBluetoothDevices = {
                    viewModel.refreshBluetoothDevices(hasBluetoothPermission())
                }

                TextBridgeApp(
                    state = uiState.value,
                    onSettingsTransportModeChange = viewModel::onSettingsTransportModeChange,
                    onSettingsLanAddressChange = viewModel::onSettingsLanAddressChange,
                    onSettingsDiscoveryPortChange = viewModel::onSettingsDiscoveryPortChange,
                    onSettingsAdbPortChange = viewModel::onSettingsAdbPortChange,
                    onSettingsBluetoothDeviceChange = viewModel::onSettingsBluetoothDeviceChange,
                    onSettingsTokenChange = viewModel::onSettingsTokenChange,
                    onRequestBluetoothPermission = requestBluetoothPermission,
                    onRefreshBluetoothDevices = refreshBluetoothDevices,
                    onSaveSettings = viewModel::saveSettings,
                    onBodyChange = viewModel::onBodyChange,
                    onScan = viewModel::scanForComputers,
                    onSend = viewModel::sendCurrentText,
                    onSendModeChange = viewModel::onSendModeChange,
                    onSendKeyAction = viewModel::sendKeyAction,
                    onSelectOffer = viewModel::selectDiscoveryOffer,
                    onDismissOfferChooser = viewModel::dismissDiscoveryChooser,
                    onUseHistoryItem = viewModel::useHistoryItem,
                    onDeleteHistoryItem = viewModel::deleteHistoryItem,
                    onRequestClearHistory = viewModel::requestClearHistory,
                    onDismissClearHistory = viewModel::dismissClearHistory,
                    onConfirmClearHistory = viewModel::clearHistory,
                )
            }
        }
    }
}
