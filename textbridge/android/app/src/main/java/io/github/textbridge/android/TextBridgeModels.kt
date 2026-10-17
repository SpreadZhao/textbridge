package io.github.textbridge.android

const val DEFAULT_COMMIT_PORT = 17321
const val DEFAULT_DISCOVERY_PORT = 17322
const val DISCOVERY_TIMEOUT_MS = 2000L
const val MAX_SEND_HISTORY_ITEMS = 50

enum class TransportMode(val storageValue: String) {
    LAN("lan"),
    ADB("adb"),
    BLUETOOTH("bluetooth");

    companion object {
        fun fromStorage(value: String?): TransportMode {
            return entries.firstOrNull { it.storageValue == value } ?: LAN
        }
    }
}

enum class SendMode(val storageValue: String) {
    SEND_ONLY("send_only"),
    SEND_THEN_KEY("send_then_key");

    companion object {
        fun fromStorage(value: String?): SendMode {
            if (value == "send_then_enter") return SEND_THEN_KEY
            return entries.firstOrNull { it.storageValue == value } ?: SEND_ONLY
        }
    }
}

enum class KeyModifier(val wireValue: String, val label: String) {
    CONTROL("Control", "Ctrl"),
    SHIFT("Shift", "Shift"),
    ALT("Alt", "Alt"),
}

enum class KeyCategory {
    COMMON, LETTERS, DIGITS, FUNCTION,
}

enum class RemoteKey(
    val wireValue: String,
    val label: String,
    val category: KeyCategory = KeyCategory.COMMON,
) {
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
    A("A", "A", KeyCategory.LETTERS),
    B("B", "B", KeyCategory.LETTERS),
    C("C", "C", KeyCategory.LETTERS),
    D("D", "D", KeyCategory.LETTERS),
    E("E", "E", KeyCategory.LETTERS),
    F("F", "F", KeyCategory.LETTERS),
    G("G", "G", KeyCategory.LETTERS),
    H("H", "H", KeyCategory.LETTERS),
    I("I", "I", KeyCategory.LETTERS),
    J("J", "J", KeyCategory.LETTERS),
    K("K", "K", KeyCategory.LETTERS),
    L("L", "L", KeyCategory.LETTERS),
    M("M", "M", KeyCategory.LETTERS),
    N("N", "N", KeyCategory.LETTERS),
    O("O", "O", KeyCategory.LETTERS),
    P("P", "P", KeyCategory.LETTERS),
    Q("Q", "Q", KeyCategory.LETTERS),
    R("R", "R", KeyCategory.LETTERS),
    S("S", "S", KeyCategory.LETTERS),
    T("T", "T", KeyCategory.LETTERS),
    U("U", "U", KeyCategory.LETTERS),
    V("V", "V", KeyCategory.LETTERS),
    W("W", "W", KeyCategory.LETTERS),
    X("X", "X", KeyCategory.LETTERS),
    Y("Y", "Y", KeyCategory.LETTERS),
    Z("Z", "Z", KeyCategory.LETTERS),
    DIGIT_0("0", "0", KeyCategory.DIGITS),
    DIGIT_1("1", "1", KeyCategory.DIGITS),
    DIGIT_2("2", "2", KeyCategory.DIGITS),
    DIGIT_3("3", "3", KeyCategory.DIGITS),
    DIGIT_4("4", "4", KeyCategory.DIGITS),
    DIGIT_5("5", "5", KeyCategory.DIGITS),
    DIGIT_6("6", "6", KeyCategory.DIGITS),
    DIGIT_7("7", "7", KeyCategory.DIGITS),
    DIGIT_8("8", "8", KeyCategory.DIGITS),
    DIGIT_9("9", "9", KeyCategory.DIGITS),
    F1("F1", "F1", KeyCategory.FUNCTION),
    F2("F2", "F2", KeyCategory.FUNCTION),
    F3("F3", "F3", KeyCategory.FUNCTION),
    F4("F4", "F4", KeyCategory.FUNCTION),
    F5("F5", "F5", KeyCategory.FUNCTION),
    F6("F6", "F6", KeyCategory.FUNCTION),
    F7("F7", "F7", KeyCategory.FUNCTION),
    F8("F8", "F8", KeyCategory.FUNCTION),
    F9("F9", "F9", KeyCategory.FUNCTION),
    F10("F10", "F10", KeyCategory.FUNCTION),
    F11("F11", "F11", KeyCategory.FUNCTION),
    F12("F12", "F12", KeyCategory.FUNCTION);

    companion object {
        fun fromWireValue(value: String?): RemoteKey? = entries.firstOrNull { it.wireValue == value }
    }
}

data class KeyCombination(
    val key: RemoteKey = RemoteKey.RETURN,
    val modifiers: Set<KeyModifier> = emptySet(),
) {
    val orderedModifiers: List<KeyModifier>
        get() = KeyModifier.entries.filter { it in modifiers }

    val label: String
        get() = (orderedModifiers.map { it.label } + key.label).joinToString("+")
}

data class TextBridgeSettings(
    val transportMode: TransportMode = TransportMode.LAN,
    val lanAddress: String = "",
    val discoveryPort: Int = DEFAULT_DISCOVERY_PORT,
    val adbPort: Int = DEFAULT_COMMIT_PORT,
    val bluetoothDeviceAddress: String = "",
    val bluetoothDeviceName: String = "",
    val sendMode: SendMode = SendMode.SEND_ONLY,
    val afterSendKey: KeyCombination = KeyCombination(),
    val sendHistory: List<SendHistoryItem> = emptyList(),
)

data class TextBridgeUiState(
    val transportMode: TransportMode = TransportMode.LAN,
    val lanAddress: String = "",
    val discoveryPort: String = DEFAULT_DISCOVERY_PORT.toString(),
    val adbPort: String = DEFAULT_COMMIT_PORT.toString(),
    val bluetoothDeviceAddress: String = "",
    val bluetoothDeviceName: String = "",
    val settingsTransportMode: TransportMode = TransportMode.LAN,
    val settingsLanAddress: String = "",
    val settingsDiscoveryPort: String = DEFAULT_DISCOVERY_PORT.toString(),
    val settingsAdbPort: String = DEFAULT_COMMIT_PORT.toString(),
    val settingsBluetoothDeviceAddress: String = "",
    val settingsBluetoothDeviceName: String = "",
    val hasUnsavedSettings: Boolean = false,
    val sendMode: SendMode = SendMode.SEND_ONLY,
    val afterSendKey: KeyCombination = KeyCombination(),
    val body: String = "",
    val status: String = "待发送",
    val isScanning: Boolean = false,
    val isSending: Boolean = false,
    val bluetoothPermissionGranted: Boolean = false,
    val bluetoothDevices: List<BluetoothDeviceChoice> = emptyList(),
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

data class BluetoothDeviceChoice(
    val name: String,
    val address: String,
) {
    val label: String
        get() = if (name.isBlank()) address else "$name $address"
}

data class SendHistoryItem(
    val id: String,
    val text: String,
    val sentAtMillis: Long,
    val address: String,
    val transportMode: TransportMode = TransportMode.LAN,
)
