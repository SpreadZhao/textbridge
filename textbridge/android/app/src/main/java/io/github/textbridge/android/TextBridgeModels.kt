package io.github.textbridge.android

const val DEFAULT_COMMIT_PORT = 17321
const val DEFAULT_DISCOVERY_PORT = 17322
const val DISCOVERY_TIMEOUT_MS = 2000L
const val MAX_SEND_HISTORY_ITEMS = 50

enum class TransportMode(val storageValue: String) {
    LAN("lan"),
    ADB("adb");

    companion object {
        fun fromStorage(value: String?): TransportMode {
            return entries.firstOrNull { it.storageValue == value } ?: LAN
        }
    }
}

enum class SendMode(val storageValue: String) {
    SEND_ONLY("send_only"),
    SEND_THEN_ENTER("send_then_enter");

    companion object {
        fun fromStorage(value: String?): SendMode {
            return entries.firstOrNull { it.storageValue == value } ?: SEND_ONLY
        }
    }
}

enum class KeyModifier(val wireValue: String, val label: String) {
    CONTROL("Control", "Ctrl"),
    SHIFT("Shift", "Shift"),
    ALT("Alt", "Alt"),
}

enum class RemoteKey(val wireValue: String, val label: String) {
    RETURN("Return", "Enter"),
    ESCAPE("Escape", "Esc"),
    TAB("Tab", "Tab"),
    BACKSPACE("BackSpace", "⌫"),
    DELETE("Delete", "Del"),
    LEFT("Left", "←"),
    RIGHT("Right", "→"),
    UP("Up", "↑"),
    DOWN("Down", "↓"),
    HOME("Home", "Home"),
    END("End", "End"),
    PAGE_UP("Page_Up", "PgUp"),
    PAGE_DOWN("Page_Down", "PgDn"),
    SPACE("Space", "Space"),
    A("A", "A"),
    C("C", "C"),
    V("V", "V"),
    X("X", "X"),
    Z("Z", "Z"),
}

data class TextBridgeSettings(
    val transportMode: TransportMode = TransportMode.LAN,
    val lanAddress: String = "",
    val discoveryPort: Int = DEFAULT_DISCOVERY_PORT,
    val adbPort: Int = DEFAULT_COMMIT_PORT,
    val token: String = "",
    val sendMode: SendMode = SendMode.SEND_ONLY,
    val sendHistory: List<SendHistoryItem> = emptyList(),
)

data class TextBridgeUiState(
    val transportMode: TransportMode = TransportMode.LAN,
    val lanAddress: String = "",
    val discoveryPort: String = DEFAULT_DISCOVERY_PORT.toString(),
    val adbPort: String = DEFAULT_COMMIT_PORT.toString(),
    val token: String = "",
    val settingsTransportMode: TransportMode = TransportMode.LAN,
    val settingsLanAddress: String = "",
    val settingsDiscoveryPort: String = DEFAULT_DISCOVERY_PORT.toString(),
    val settingsAdbPort: String = DEFAULT_COMMIT_PORT.toString(),
    val settingsToken: String = "",
    val hasUnsavedSettings: Boolean = false,
    val sendMode: SendMode = SendMode.SEND_ONLY,
    val body: String = "",
    val status: String = "待发送",
    val isScanning: Boolean = false,
    val isSending: Boolean = false,
    val selectedKeyModifiers: Set<KeyModifier> = emptySet(),
    val discoveryChoices: List<DiscoveryOffer> = emptyList(),
    val sendHistory: List<SendHistoryItem> = emptyList(),
    val showClearHistoryConfirm: Boolean = false,
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

data class SendHistoryItem(
    val id: String,
    val text: String,
    val sentAtMillis: Long,
    val address: String,
    val transportMode: TransportMode = TransportMode.LAN,
)
