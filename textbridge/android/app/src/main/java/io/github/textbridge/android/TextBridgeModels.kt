package io.github.textbridge.android

const val DEFAULT_DISCOVERY_PORT = 17322
const val DISCOVERY_TIMEOUT_MS = 2000L

data class TextBridgeSettings(
    val address: String = "",
    val discoveryPort: Int = DEFAULT_DISCOVERY_PORT,
    val token: String = "",
)

data class TextBridgeUiState(
    val address: String = "",
    val discoveryPort: String = DEFAULT_DISCOVERY_PORT.toString(),
    val token: String = "",
    val body: String = "",
    val status: String = "待发送",
    val isScanning: Boolean = false,
    val isSending: Boolean = false,
    val discoveryChoices: List<DiscoveryOffer> = emptyList(),
)

data class DiscoveryOffer(
    val name: String,
    val host: String,
    val port: Int,
    val version: String,
    val auth: String,
) {
    val label: String
        get() = "$name $host:$port"

    val address: String
        get() = "$host:$port"
}

data class SendResult(
    val ok: Boolean,
    val message: String,
)
