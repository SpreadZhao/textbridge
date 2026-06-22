package io.github.textbridge.android

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable
private data object SendRoute : NavKey

@Serializable
private data object HistoryRoute : NavKey

@Serializable
private data object SettingsRoute : NavKey

private data class TopLevelDestination(
    val route: NavKey,
    val labelRes: Int,
    val icon: ImageVector,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextBridgeApp(
    state: TextBridgeUiState,
    onSettingsTransportModeChange: (TransportMode) -> Unit,
    onSettingsLanAddressChange: (String) -> Unit,
    onSettingsDiscoveryPortChange: (String) -> Unit,
    onSettingsAdbPortChange: (String) -> Unit,
    onSettingsBluetoothDeviceChange: (String) -> Unit,
    onSettingsTokenChange: (String) -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onRefreshBluetoothDevices: () -> Unit,
    onSaveSettings: () -> Unit,
    onBodyChange: (String) -> Unit,
    onScan: () -> Unit,
    onSend: () -> Unit,
    onSendModeChange: (SendMode) -> Unit,
    onSendKeyAction: (RemoteKey, Set<KeyModifier>) -> Unit,
    onSelectOffer: (DiscoveryOffer) -> Unit,
    onDismissOfferChooser: () -> Unit,
    onUseHistoryItem: (SendHistoryItem) -> Unit,
    onDeleteHistoryItem: (SendHistoryItem) -> Unit,
    onRequestClearHistory: () -> Unit,
    onDismissClearHistory: () -> Unit,
    onConfirmClearHistory: () -> Unit,
) {
    val backStack = rememberNavBackStack(SendRoute)
    val destinations = listOf(
        TopLevelDestination(SendRoute, R.string.nav_send, Icons.AutoMirrored.Filled.Send),
        TopLevelDestination(HistoryRoute, R.string.nav_history, Icons.Filled.History),
        TopLevelDestination(SettingsRoute, R.string.nav_settings, Icons.Filled.Settings),
    )
    val currentRoute = backStack.lastOrNull() ?: SendRoute

    fun navigateTo(route: NavKey) {
        if (currentRoute == route) {
            return
        }
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
        if (route != SendRoute) {
            backStack.add(route)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navigateTo(destination.route) },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            NavDisplay(
                backStack = backStack,
                onBack = {
                    if (backStack.size > 1) {
                        backStack.removeLastOrNull()
                    }
                },
                entryProvider = entryProvider {
                    entry<SendRoute> {
                        SendScreen(
                            state = state,
                            onBodyChange = onBodyChange,
                            onSend = onSend,
                            onSendModeChange = onSendModeChange,
                            onSendKeyAction = onSendKeyAction,
                        )
                    }
                    entry<HistoryRoute> {
                        HistoryScreen(
                            history = state.sendHistory,
                            onUseHistoryItem = { item ->
                                onUseHistoryItem(item)
                                navigateTo(SendRoute)
                            },
                            onDeleteHistoryItem = onDeleteHistoryItem,
                            onRequestClearHistory = onRequestClearHistory,
                        )
                    }
                    entry<SettingsRoute> {
                        SettingsScreen(
                            state = state,
                            onTransportModeChange = onSettingsTransportModeChange,
                            onLanAddressChange = onSettingsLanAddressChange,
                            onDiscoveryPortChange = onSettingsDiscoveryPortChange,
                            onAdbPortChange = onSettingsAdbPortChange,
                            onBluetoothDeviceChange = onSettingsBluetoothDeviceChange,
                            onTokenChange = onSettingsTokenChange,
                            onRequestBluetoothPermission = onRequestBluetoothPermission,
                            onRefreshBluetoothDevices = onRefreshBluetoothDevices,
                            onScan = onScan,
                            onSaveSettings = onSaveSettings,
                        )
                    }
                },
            )
        }
    }

    if (state.discoveryChoices.isNotEmpty()) {
        DiscoveryChooserDialog(
            offers = state.discoveryChoices,
            onSelectOffer = onSelectOffer,
            onDismiss = onDismissOfferChooser,
        )
    }

    if (state.showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = onDismissClearHistory,
            title = { Text(stringResource(R.string.clear_history_title)) },
            text = { Text(stringResource(R.string.clear_history_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmClearHistory) {
                    Text(stringResource(R.string.clear_history_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissClearHistory) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SendScreen(
    state: TextBridgeUiState,
    onBodyChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendModeChange: (SendMode) -> Unit,
    onSendKeyAction: (RemoteKey, Set<KeyModifier>) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = state.body,
            onValueChange = onBodyChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp),
            label = { Text(stringResource(R.string.text_to_send)) },
            minLines = 6,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.None,
            ),
        )

        SendActionRow(
            selectedMode = state.sendMode,
            isSending = state.isSending,
            onSendModeChange = onSendModeChange,
            onSend = onSend,
        )

        StatusCard(status = state.status)

        KeyControlPanel(
            isSending = state.isSending,
            onSendKey = onSendKeyAction,
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SendActionRow(
    selectedMode: SendMode,
    isSending: Boolean,
    onSendModeChange: (SendMode) -> Unit,
    onSend: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val modes = listOf(SendMode.SEND_ONLY, SendMode.SEND_THEN_ENTER)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            FilledTonalButton(
                onClick = { expanded = true },
                enabled = !isSending,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(selectedMode.labelRes()),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                modes.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(stringResource(mode.labelRes())) },
                        onClick = {
                            expanded = false
                            onSendModeChange(mode)
                        },
                    )
                }
            }
        }

        Button(
            onClick = onSend,
            enabled = !isSending,
            modifier = Modifier
                .width(132.dp)
                .height(52.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(
                if (isSending) {
                    stringResource(R.string.send_progress)
                } else {
                    stringResource(R.string.send)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun KeyControlPanel(
    isSending: Boolean,
    onSendKey: (RemoteKey, Set<KeyModifier>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.key_controls),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(RemoteKey.RETURN, RemoteKey.BACKSPACE).forEach { key ->
                KeyButton(
                    label = key.label,
                    enabled = !isSending,
                    onClick = { onSendKey(key, emptySet()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(RemoteKey.LEFT, RemoteKey.UP, RemoteKey.DOWN, RemoteKey.RIGHT).forEach { key ->
                KeyButton(
                    label = key.label,
                    enabled = !isSending,
                    onClick = { onSendKey(key, emptySet()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SettingsScreen(
    state: TextBridgeUiState,
    onTransportModeChange: (TransportMode) -> Unit,
    onLanAddressChange: (String) -> Unit,
    onDiscoveryPortChange: (String) -> Unit,
    onAdbPortChange: (String) -> Unit,
    onBluetoothDeviceChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onRefreshBluetoothDevices: () -> Unit,
    onScan: () -> Unit,
    onSaveSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TransportModeSelector(
            selectedMode = state.settingsTransportMode,
            onTransportModeChange = onTransportModeChange,
        )

        when (state.settingsTransportMode) {
            TransportMode.LAN -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.settingsLanAddress,
                        onValueChange = onLanAddressChange,
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.computer_address)) },
                        placeholder = { Text("192.168.1.20:17321") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    )

                    FilledTonalButton(
                        onClick = onScan,
                        enabled = !state.isScanning,
                        modifier = Modifier
                            .width(124.dp)
                            .height(56.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            if (state.isScanning) {
                                stringResource(R.string.scan_computer_progress)
                            } else {
                                stringResource(R.string.scan_computer)
                            },
                        )
                    }
                }

                OutlinedTextField(
                    value = state.settingsDiscoveryPort,
                    onValueChange = onDiscoveryPortChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.discovery_port)) },
                    placeholder = { Text(DEFAULT_DISCOVERY_PORT.toString()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
            TransportMode.ADB -> {
                OutlinedTextField(
                    value = state.settingsAdbPort,
                    onValueChange = onAdbPortChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.adb_port)) },
                    placeholder = { Text(DEFAULT_COMMIT_PORT.toString()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
            TransportMode.BLUETOOTH -> {
                BluetoothDeviceSelector(
                    state = state,
                    onBluetoothDeviceChange = onBluetoothDeviceChange,
                    onRequestBluetoothPermission = onRequestBluetoothPermission,
                    onRefreshBluetoothDevices = onRefreshBluetoothDevices,
                )
            }
        }

        OutlinedTextField(
            value = state.settingsToken,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.access_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onSaveSettings,
                enabled = state.hasUnsavedSettings,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.save_settings))
            }

            if (state.hasUnsavedSettings) {
                Text(
                    text = stringResource(R.string.unsaved_settings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        StatusCard(status = state.status)

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun TransportModeSelector(
    selectedMode: TransportMode,
    onTransportModeChange: (TransportMode) -> Unit,
) {
    val modes = listOf(TransportMode.LAN, TransportMode.ADB, TransportMode.BLUETOOTH)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.transport_mode),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selectedMode == mode,
                    onClick = { onTransportModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                ) {
                    Text(stringResource(mode.labelRes()))
                }
            }
        }
    }
}

@Composable
private fun BluetoothDeviceSelector(
    state: TextBridgeUiState,
    onBluetoothDeviceChange: (String) -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onRefreshBluetoothDevices: () -> Unit,
) {
    if (!state.bluetoothPermissionGranted) {
        Button(
            onClick = onRequestBluetoothPermission,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(stringResource(R.string.bluetooth_permission))
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val selectedDevice = state.bluetoothDevices.firstOrNull {
        it.address == state.settingsBluetoothDeviceAddress
    }
    val selectedLabel = when {
        selectedDevice != null -> selectedDevice.label
        state.settingsBluetoothDeviceName.isNotBlank() && state.settingsBluetoothDeviceAddress.isNotBlank() ->
            "${state.settingsBluetoothDeviceName} ${state.settingsBluetoothDeviceAddress}"
        state.settingsBluetoothDeviceAddress.isNotBlank() -> state.settingsBluetoothDeviceAddress
        state.bluetoothDevices.isEmpty() -> stringResource(R.string.bluetooth_no_paired_devices)
        else -> stringResource(R.string.bluetooth_select_device)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            FilledTonalButton(
                onClick = { expanded = state.bluetoothDevices.isNotEmpty() },
                enabled = state.bluetoothDevices.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    text = selectedLabel,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                state.bluetoothDevices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.label) },
                        onClick = {
                            expanded = false
                            onBluetoothDeviceChange(device.address)
                        },
                    )
                }
            }
        }

        FilledTonalButton(
            onClick = onRefreshBluetoothDevices,
            modifier = Modifier
                .width(96.dp)
                .height(56.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.refresh),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HistoryScreen(
    history: List<SendHistoryItem>,
    onUseHistoryItem: (SendHistoryItem) -> Unit,
    onDeleteHistoryItem: (SendHistoryItem) -> Unit,
    onRequestClearHistory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.send_history),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = onRequestClearHistory) {
                    Text(stringResource(R.string.clear_history))
                }
            }
        }

        if (history.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = stringResource(R.string.empty_history),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        } else {
            history.forEach { item ->
                HistoryItemRow(
                    item = item,
                    onUseHistoryItem = onUseHistoryItem,
                    onDeleteHistoryItem = onDeleteHistoryItem,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(status: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = "状态：$status",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DiscoveryChooserDialog(
    offers: List<DiscoveryOffer>,
    onSelectOffer: (DiscoveryOffer) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_computer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (offer in offers) {
                    TextButton(
                        onClick = { onSelectOffer(offer) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = offer.label,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun HistoryItemRow(
    item: SendHistoryItem,
    onUseHistoryItem: (SendHistoryItem) -> Unit,
    onDeleteHistoryItem: (SendHistoryItem) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUseHistoryItem(item) },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatHistoryTime(item.sentAtMillis)}  ${stringResource(item.transportMode.labelRes())}  ${item.address}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { onDeleteHistoryItem(item) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.delete_history_item))
                }
            }
            Text(
                text = item.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatHistoryTime(sentAtMillis: Long): String {
    return DateFormat.format("MM-dd HH:mm", sentAtMillis).toString()
}

private fun TransportMode.labelRes(): Int {
    return when (this) {
        TransportMode.LAN -> R.string.transport_lan
        TransportMode.ADB -> R.string.transport_adb
        TransportMode.BLUETOOTH -> R.string.transport_bluetooth
    }
}

private fun SendMode.labelRes(): Int {
    return when (this) {
        SendMode.SEND_ONLY -> R.string.send_mode_send_only
        SendMode.SEND_THEN_ENTER -> R.string.send_mode_send_then_enter
    }
}
