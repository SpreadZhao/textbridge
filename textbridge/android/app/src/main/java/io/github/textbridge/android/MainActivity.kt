package io.github.textbridge.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
                TextBridgeApp(
                    state = uiState.value,
                    onSettingsTransportModeChange = viewModel::onSettingsTransportModeChange,
                    onSettingsLanAddressChange = viewModel::onSettingsLanAddressChange,
                    onSettingsDiscoveryPortChange = viewModel::onSettingsDiscoveryPortChange,
                    onSettingsAdbPortChange = viewModel::onSettingsAdbPortChange,
                    onSettingsTokenChange = viewModel::onSettingsTokenChange,
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
