package io.github.textbridge.android

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
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
    private val settingsStore: TextBridgeSettingsRepository,
    private val discoveryClient: TextBridgeDiscoveryClient = DiscoveryClient(),
    private val commitClient: TextBridgeCommitClient = CommitClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TextBridgeUiState())
    val uiState: StateFlow<TextBridgeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            _uiState.update {
                it.copy(
                    transportMode = settings.transportMode,
                    lanAddress = settings.lanAddress,
                    discoveryPort = settings.discoveryPort.toString(),
                    adbPort = settings.adbPort.toString(),
                    token = settings.token,
                    settingsTransportMode = settings.transportMode,
                    settingsLanAddress = settings.lanAddress,
                    settingsDiscoveryPort = settings.discoveryPort.toString(),
                    settingsAdbPort = settings.adbPort.toString(),
                    settingsToken = settings.token,
                    hasUnsavedSettings = false,
                    sendHistory = settings.sendHistory,
                )
            }
        }
    }

    fun onSettingsTransportModeChange(value: TransportMode) {
        _uiState.update { it.withSettingsDraft(transportMode = value) }
    }

    fun onSettingsLanAddressChange(value: String) {
        _uiState.update { it.withSettingsDraft(lanAddress = value) }
    }

    fun onSettingsDiscoveryPortChange(value: String) {
        _uiState.update { it.withSettingsDraft(discoveryPort = value.filter(Char::isDigit).take(5)) }
    }

    fun onSettingsAdbPortChange(value: String) {
        _uiState.update { it.withSettingsDraft(adbPort = value.filter(Char::isDigit).take(5)) }
    }

    fun onSettingsTokenChange(value: String) {
        _uiState.update { it.withSettingsDraft(token = value) }
    }

    fun onBodyChange(value: String) {
        _uiState.update { it.copy(body = value) }
    }

    fun saveSettings() {
        val state = uiState.value
        val discoveryPort = parsePort(state.settingsDiscoveryPort)
        if (discoveryPort == null) {
            _uiState.update { it.copy(status = "发现端口无效") }
            return
        }
        val adbPort = parsePort(state.settingsAdbPort)
        if (adbPort == null) {
            _uiState.update { it.copy(status = "ADB 端口无效") }
            return
        }

        val transportMode = state.settingsTransportMode
        val lanAddress = state.settingsLanAddress.trim()
        val token = state.settingsToken

        viewModelScope.launch {
            settingsStore.saveConnectionSettings(
                transportMode = transportMode,
                lanAddress = lanAddress,
                discoveryPort = discoveryPort,
                adbPort = adbPort,
                token = token,
            )
            _uiState.update {
                it.copy(
                    transportMode = transportMode,
                    lanAddress = lanAddress,
                    discoveryPort = discoveryPort.toString(),
                    adbPort = adbPort.toString(),
                    token = token,
                    settingsTransportMode = transportMode,
                    settingsLanAddress = lanAddress,
                    settingsDiscoveryPort = discoveryPort.toString(),
                    settingsAdbPort = adbPort.toString(),
                    settingsToken = token,
                    hasUnsavedSettings = false,
                    status = "配置已保存",
                )
            }
        }
    }

    fun scanForComputers() {
        if (uiState.value.settingsTransportMode != TransportMode.LAN) {
            _uiState.update { it.copy(status = "扫描只适用于局域网方式") }
            return
        }

        val port = parsePort(uiState.value.settingsDiscoveryPort)
        if (port == null) {
            _uiState.update { it.copy(status = "发现端口无效") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    status = "正在扫描...",
                    discoveryChoices = emptyList(),
                )
            }

            val startedAt = SystemClock.elapsedRealtime()
            val offers = withContext(ioDispatcher) {
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
        applyDiscoveryOffer(offer)
    }

    fun dismissDiscoveryChooser() {
        _uiState.update { it.copy(discoveryChoices = emptyList()) }
    }

    fun sendCurrentText() {
        val state = uiState.value
        val endpoint = resolveEndpoint(state) ?: return
        val token = state.token
        val text = state.body

        when {
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
            _uiState.update { it.copy(isSending = true, status = "发送中...") }

            val result = withContext(ioDispatcher) {
                commitClient.postCommit(
                    address = endpoint.address,
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
                        address = endpoint.address,
                        transportMode = endpoint.transportMode,
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

    private fun applyDiscoveryOffer(offer: DiscoveryOffer) {
        _uiState.update {
            it.withSettingsDraft(
                transportMode = TransportMode.LAN,
                lanAddress = offer.address,
            ).copy(
                isScanning = false,
                status = "已填入 ${offer.label}，保存后生效",
                discoveryChoices = emptyList(),
            )
        }
    }

    private fun parsePort(value: String): Int? {
        val port = value.trim().toIntOrNull()
        return port?.takeIf { it in 1..65535 }
    }

    private fun resolveEndpoint(state: TextBridgeUiState): ResolvedEndpoint? {
        return when (state.transportMode) {
            TransportMode.LAN -> {
                val address = state.lanAddress.trim()
                if (address.isBlank()) {
                    _uiState.update { it.copy(status = "请填写电脑地址") }
                    null
                } else {
                    ResolvedEndpoint(address, TransportMode.LAN)
                }
            }
            TransportMode.ADB -> {
                val port = parsePort(state.adbPort)
                if (port == null) {
                    _uiState.update { it.copy(status = "ADB 端口无效") }
                    null
                } else {
                    ResolvedEndpoint("127.0.0.1:$port", TransportMode.ADB)
                }
            }
        }
    }

    private fun TextBridgeUiState.withSettingsDraft(
        transportMode: TransportMode = settingsTransportMode,
        lanAddress: String = settingsLanAddress,
        discoveryPort: String = settingsDiscoveryPort,
        adbPort: String = settingsAdbPort,
        token: String = settingsToken,
    ): TextBridgeUiState {
        return copy(
            settingsTransportMode = transportMode,
            settingsLanAddress = lanAddress,
            settingsDiscoveryPort = discoveryPort,
            settingsAdbPort = adbPort,
            settingsToken = token,
            hasUnsavedSettings = transportMode != this.transportMode ||
                lanAddress != this.lanAddress ||
                discoveryPort != this.discoveryPort ||
                adbPort != this.adbPort ||
                token != this.token,
        )
    }

    private data class ResolvedEndpoint(
        val address: String,
        val transportMode: TransportMode,
    )

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
