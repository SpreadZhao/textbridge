package io.github.textbridge.android

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TextBridgeViewModel(
    private val settingsStore: SettingsStore,
    private val discoveryClient: DiscoveryClient = DiscoveryClient(),
    private val commitClient: CommitClient = CommitClient(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(TextBridgeUiState())
    val uiState: StateFlow<TextBridgeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            _uiState.update {
                it.copy(
                    address = settings.address,
                    discoveryPort = settings.discoveryPort.toString(),
                    token = settings.token,
                    sendHistory = settings.sendHistory,
                )
            }
        }
    }

    fun onAddressChange(value: String) {
        _uiState.update { it.copy(address = value) }
    }

    fun onDiscoveryPortChange(value: String) {
        _uiState.update { it.copy(discoveryPort = value.filter(Char::isDigit).take(5)) }
    }

    fun onTokenChange(value: String) {
        _uiState.update { it.copy(token = value) }
    }

    fun onBodyChange(value: String) {
        _uiState.update { it.copy(body = value) }
    }

    fun scanForComputers() {
        val port = parsePort(uiState.value.discoveryPort)
        if (port == null) {
            _uiState.update { it.copy(status = "发现端口无效") }
            return
        }

        viewModelScope.launch {
            settingsStore.saveDiscoveryPort(port)
            _uiState.update {
                it.copy(
                    isScanning = true,
                    status = "正在扫描...",
                    discoveryChoices = emptyList(),
                )
            }

            val startedAt = SystemClock.elapsedRealtime()
            val offers = withContext(Dispatchers.IO) {
                discoveryClient.discover(port)
            }
            val remainingMs = DISCOVERY_TIMEOUT_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remainingMs > 0) {
                delay(remainingMs)
            }

            when {
                offers.isEmpty() -> {
                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            status = "未发现电脑",
                        )
                    }
                }
                offers.size == 1 -> applyDiscoveryOffer(offers.first())
                else -> {
                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            status = "发现 ${offers.size} 台电脑",
                            discoveryChoices = offers,
                        )
                    }
                }
            }
        }
    }

    fun selectDiscoveryOffer(offer: DiscoveryOffer) {
        viewModelScope.launch {
            applyDiscoveryOffer(offer)
        }
    }

    fun dismissDiscoveryChooser() {
        _uiState.update { it.copy(discoveryChoices = emptyList()) }
    }

    fun sendCurrentText() {
        val state = uiState.value
        val address = state.address.trim()
        val token = state.token
        val text = state.body

        when {
            address.isBlank() -> {
                _uiState.update { it.copy(status = "请填写电脑地址") }
                return
            }
            token.isBlank() -> {
                _uiState.update { it.copy(status = "请填写访问令牌") }
                return
            }
            text.isEmpty() -> {
                _uiState.update { it.copy(status = "正文为空") }
                return
            }
        }

        viewModelScope.launch {
            settingsStore.saveAddressAndToken(address, token)
            _uiState.update { it.copy(isSending = true, status = "发送中...") }

            val result = withContext(Dispatchers.IO) {
                commitClient.postCommit(
                    address = address,
                    token = token,
                    requestId = UUID.randomUUID().toString(),
                    text = text,
                )
            }

            if (result.ok) {
                val updatedHistory = settingsStore.addSendHistoryItem(
                    SendHistoryItem(
                        id = UUID.randomUUID().toString(),
                        text = text,
                        sentAtMillis = System.currentTimeMillis(),
                        address = address,
                    ),
                )
                _uiState.update {
                    it.copy(
                        body = "",
                        status = result.message,
                        isSending = false,
                        sendHistory = updatedHistory,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        status = result.message,
                        isSending = false,
                    )
                }
            }
        }
    }

    fun useHistoryItem(item: SendHistoryItem) {
        _uiState.update {
            it.copy(
                body = item.text,
                status = "已从历史填入",
            )
        }
    }

    fun deleteHistoryItem(item: SendHistoryItem) {
        viewModelScope.launch {
            val updatedHistory = settingsStore.removeSendHistoryItem(item.id)
            _uiState.update {
                it.copy(
                    sendHistory = updatedHistory,
                    status = "已删除历史记录",
                )
            }
        }
    }

    fun requestClearHistory() {
        _uiState.update { it.copy(showClearHistoryConfirm = true) }
    }

    fun dismissClearHistory() {
        _uiState.update { it.copy(showClearHistoryConfirm = false) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            settingsStore.clearSendHistory()
            _uiState.update {
                it.copy(
                    sendHistory = emptyList(),
                    showClearHistoryConfirm = false,
                    status = "已清空历史记录",
                )
            }
        }
    }

    private suspend fun applyDiscoveryOffer(offer: DiscoveryOffer) {
        settingsStore.saveAddress(offer.address)
        _uiState.update {
            it.copy(
                address = offer.address,
                isScanning = false,
                status = "已选择 ${offer.label}",
                discoveryChoices = emptyList(),
            )
        }
    }

    private fun parsePort(value: String): Int? {
        val port = value.trim().toIntOrNull()
        return port?.takeIf { it in 1..65535 }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TextBridgeViewModel::class.java)) {
                return TextBridgeViewModel(SettingsStore(context.applicationContext)) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
