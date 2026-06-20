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
                TextBridgeScreen(
                    state = uiState.value,
                    onAddressChange = viewModel::onAddressChange,
                    onDiscoveryPortChange = viewModel::onDiscoveryPortChange,
                    onTokenChange = viewModel::onTokenChange,
                    onBodyChange = viewModel::onBodyChange,
                    onScan = viewModel::scanForComputers,
                    onSend = viewModel::sendCurrentText,
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
