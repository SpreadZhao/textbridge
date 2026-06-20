package io.github.textbridge.android

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextBridgeScreen(
    state: TextBridgeUiState,
    onAddressChange: (String) -> Unit,
    onDiscoveryPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onScan: () -> Unit,
    onSend: () -> Unit,
    onSelectOffer: (DiscoveryOffer) -> Unit,
    onDismissOfferChooser: () -> Unit,
    onUseHistoryItem: (SendHistoryItem) -> Unit,
    onDeleteHistoryItem: (SendHistoryItem) -> Unit,
    onRequestClearHistory: () -> Unit,
    onDismissClearHistory: () -> Unit,
    onConfirmClearHistory: () -> Unit,
) {
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.address,
                    onValueChange = onAddressChange,
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
                value = state.discoveryPort,
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

            OutlinedTextField(
                value = state.token,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.access_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
            )

            OutlinedTextField(
                value = state.body,
                onValueChange = onBodyChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                label = { Text(stringResource(R.string.text_to_send)) },
                minLines = 8,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.None,
                ),
            )

            Button(
                onClick = onSend,
                enabled = !state.isSending,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    if (state.isSending) {
                        stringResource(R.string.send_progress)
                    } else {
                        stringResource(R.string.send)
                    },
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = "状态：${state.status}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            HistorySection(
                history = state.sendHistory,
                onUseHistoryItem = onUseHistoryItem,
                onDeleteHistoryItem = onDeleteHistoryItem,
                onRequestClearHistory = onRequestClearHistory,
            )

            Spacer(modifier = Modifier.height(8.dp))
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
private fun HistorySection(
    history: List<SendHistoryItem>,
    onUseHistoryItem: (SendHistoryItem) -> Unit,
    onDeleteHistoryItem: (SendHistoryItem) -> Unit,
    onRequestClearHistory: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
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
                    text = "${formatHistoryTime(item.sentAtMillis)}  ${item.address}",
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
