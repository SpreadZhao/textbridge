package io.github.textbridge.android

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
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
    private val bluetoothRepository: TextBridgeBluetoothDeviceRepository = EmptyBluetoothDeviceRepository,
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
                    bluetoothDeviceAddress = settings.bluetoothDeviceAddress,
                    bluetoothDeviceName = settings.bluetoothDeviceName,
                    settingsTransportMode = settings.transportMode,
                    settingsLanAddress = settings.lanAddress,
                    settingsDiscoveryPort = settings.discoveryPort.toString(),
                    settingsAdbPort = settings.adbPort.toString(),
                    settingsBluetoothDeviceAddress = settings.bluetoothDeviceAddress,
                    settingsBluetoothDeviceName = settings.bluetoothDeviceName,
                    hasUnsavedSettings = false,
                    sendMode = settings.sendMode,
                    afterSendKey = settings.afterSendKey,
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

    fun onSettingsBluetoothDeviceChange(address: String) {
        val device = uiState.value.bluetoothDevices.firstOrNull { it.address == address }
        _uiState.update {
            it.withSettingsDraft(
                bluetoothDeviceAddress = address,
                bluetoothDeviceName = device?.name.orEmpty(),
            )
        }
    }

    fun refreshBluetoothDevices(hasPermission: Boolean) {
        viewModelScope.launch {
            if (!hasPermission) {
                _uiState.update {
                    it.copy(
                        bluetoothPermissionGranted = false,
                        bluetoothDevices = emptyList(),
                    )
                }
                return@launch
            }

            val devices = withContext(ioDispatcher) {
                bluetoothRepository.pairedDevices()
            }
            _uiState.update {
                it.copy(
                    bluetoothPermissionGranted = true,
                    bluetoothDevices = devices,
                )
            }
        }
    }

    fun onBodyChange(value: String) {
        _uiState.update { it.copy(body = value) }
    }

    fun saveSendOptions(mode: SendMode, combination: KeyCombination) {
        if (uiState.value.isSending) return
        viewModelScope.launch {
            try {
                settingsStore.saveSendOptions(mode, combination)
                _uiState.update { it.copy(sendMode = mode, afterSendKey = combination) }
            } catch (e: IOException) {
                _uiState.update { it.copy(status = "发送后动作保存失败") }
            }
        }
    }

    fun toggleKeyModifier(modifier: KeyModifier) {
        _uiState.update { state ->
            if (state.isSending) return@update state
            val next = if (modifier in state.selectedKeyModifiers) {
                state.selectedKeyModifiers - modifier
            } else {
                state.selectedKeyModifiers + modifier
            }
            state.copy(selectedKeyModifiers = next)
        }
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
        val bluetoothDeviceAddress = state.settingsBluetoothDeviceAddress.trim()
        val bluetoothDeviceName = state.settingsBluetoothDeviceName.trim()
        if (transportMode == TransportMode.BLUETOOTH && bluetoothDeviceAddress.isBlank()) {
            _uiState.update { it.copy(status = "请选择蓝牙电脑") }
            return
        }

        viewModelScope.launch {
            settingsStore.saveConnectionSettings(
                transportMode = transportMode,
                lanAddress = lanAddress,
                discoveryPort = discoveryPort,
                adbPort = adbPort,
                bluetoothDeviceAddress = bluetoothDeviceAddress,
                bluetoothDeviceName = bluetoothDeviceName,
            )
            _uiState.update {
                it.copy(
                    transportMode = transportMode,
                    lanAddress = lanAddress,
                    discoveryPort = discoveryPort.toString(),
                    adbPort = adbPort.toString(),
                    bluetoothDeviceAddress = bluetoothDeviceAddress,
                    bluetoothDeviceName = bluetoothDeviceName,
                    settingsTransportMode = transportMode,
                    settingsLanAddress = lanAddress,
                    settingsDiscoveryPort = discoveryPort.toString(),
                    settingsAdbPort = adbPort.toString(),
                    settingsBluetoothDeviceAddress = bluetoothDeviceAddress,
                    settingsBluetoothDeviceName = bluetoothDeviceName,
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

    fun sendCurrentText(): Boolean {
        val state = uiState.value
        if (state.isSending) return false
        val endpoint = resolveEndpoint(state) ?: return false
        val text = state.body
        val afterSendKey = state.afterSendKey.takeIf { state.sendMode == SendMode.SEND_THEN_KEY }

        if (text.isEmpty()) {
            _uiState.update { it.copy(status = "正文为空") }
            return false
        }

        // Mark busy before dispatching so two taps cannot start concurrent sends.
        _uiState.update { it.copy(isSending = true, status = "发送中...") }
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    if (endpoint.transportMode == TransportMode.BLUETOOTH) {
                        commitClient.postBluetoothCommit(
                            deviceAddress = endpoint.address,
                            requestId = UUID.randomUUID().toString(),
                            text = text,
                        )
                    } else {
                        commitClient.postCommit(
                            address = endpoint.address,
                            requestId = UUID.randomUUID().toString(),
                            text = text,
                        )
                    }
                }
                if (!result.ok) {
                    _uiState.update { it.copy(status = result.message) }
                    return@launch
                }

                val keyResult = afterSendKey?.let { postKey(endpoint, it) }
                val message = when {
                    afterSendKey == null -> result.message
                    keyResult?.ok == true -> "已发送并按 ${afterSendKey.label}"
                    else -> "文字已发送，但 ${afterSendKey.label} 失败：${keyResult?.message}"
                }
                _uiState.update {
                    it.copy(body = if (it.body == text) "" else it.body, status = message)
                }
                try {
                    val history = settingsStore.addSendHistoryItem(
                        SendHistoryItem(
                            id = UUID.randomUUID().toString(),
                            text = text,
                            sentAtMillis = System.currentTimeMillis(),
                            address = endpoint.historyAddress,
                            transportMode = endpoint.transportMode,
                        ),
                    )
                    _uiState.update { it.copy(sendHistory = history) }
                } catch (e: IOException) {
                    _uiState.update { it.copy(status = "$message；历史保存失败") }
                }
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
        return true
    }

    fun sendKeyAction(key: RemoteKey, modifiers: Set<KeyModifier>? = null) {
        val state = uiState.value
        if (state.isSending) return
        val endpoint = resolveEndpoint(state) ?: return
        val combination = KeyCombination(key, (modifiers ?: state.selectedKeyModifiers).toSet())
        _uiState.update {
            it.copy(isSending = true, status = "发送按键中...", selectedKeyModifiers = emptySet())
        }
        viewModelScope.launch {
            try {
                val result = postKey(endpoint, combination)
                _uiState.update {
                    it.copy(status = if (result.ok) "按键已发送：${combination.label}" else result.message)
                }
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    private suspend fun postKey(endpoint: ResolvedEndpoint, combination: KeyCombination): SendResult {
        return withContext(ioDispatcher) {
            if (endpoint.transportMode == TransportMode.BLUETOOTH) {
                commitClient.postBluetoothKey(
                    deviceAddress = endpoint.address,
                    requestId = UUID.randomUUID().toString(),
                    key = combination.key,
                    modifiers = combination.orderedModifiers,
                )
            } else {
                commitClient.postKey(
                    address = endpoint.address,
                    requestId = UUID.randomUUID().toString(),
                    key = combination.key,
                    modifiers = combination.orderedModifiers,
                )
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
            TransportMode.BLUETOOTH -> {
                val address = state.bluetoothDeviceAddress.trim()
                if (address.isBlank()) {
                    _uiState.update { it.copy(status = "请选择蓝牙电脑") }
                    null
                } else {
                    val name = state.bluetoothDeviceName.trim()
                    ResolvedEndpoint(
                        address = address,
                        transportMode = TransportMode.BLUETOOTH,
                        historyAddress = if (name.isBlank()) address else "$name $address",
                    )
                }
            }
        }
    }

    private fun TextBridgeUiState.withSettingsDraft(
        transportMode: TransportMode = settingsTransportMode,
        lanAddress: String = settingsLanAddress,
        discoveryPort: String = settingsDiscoveryPort,
        adbPort: String = settingsAdbPort,
        bluetoothDeviceAddress: String = settingsBluetoothDeviceAddress,
        bluetoothDeviceName: String = settingsBluetoothDeviceName,
    ): TextBridgeUiState {
        return copy(
            settingsTransportMode = transportMode,
            settingsLanAddress = lanAddress,
            settingsDiscoveryPort = discoveryPort,
            settingsAdbPort = adbPort,
            settingsBluetoothDeviceAddress = bluetoothDeviceAddress,
            settingsBluetoothDeviceName = bluetoothDeviceName,
            hasUnsavedSettings = transportMode != this.transportMode ||
                lanAddress != this.lanAddress ||
                discoveryPort != this.discoveryPort ||
                adbPort != this.adbPort ||
                bluetoothDeviceAddress != this.bluetoothDeviceAddress ||
                bluetoothDeviceName != this.bluetoothDeviceName,
        )
    }

    private data class ResolvedEndpoint(
        val address: String,
        val transportMode: TransportMode,
        val historyAddress: String = address,
    )

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TextBridgeViewModel::class.java)) {
                val appContext = context.applicationContext
                return TextBridgeViewModel(
                    settingsStore = SettingsStore(appContext),
                    commitClient = CommitClient(BluetoothCommitClient(appContext)),
                    bluetoothRepository = BluetoothDeviceRepository(appContext),
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
