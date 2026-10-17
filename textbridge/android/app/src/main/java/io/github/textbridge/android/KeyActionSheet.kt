package io.github.textbridge.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun KeyModifierSelector(
    selectedModifiers: Set<KeyModifier>,
    onToggleModifier: (KeyModifier) -> Unit,
    enabled: Boolean = true,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        KeyModifier.entries.forEach { modifier ->
            val selected = modifier in selectedModifiers
            FilterChip(
                selected = selected,
                onClick = { onToggleModifier(modifier) },
                enabled = enabled,
                label = { Text(modifier.label) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
        }
    }
}

/** A local draft: dismissing the sheet never changes the saved or one-shot action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyActionSheet(
    initialCombination: KeyCombination,
    isSending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (SendMode, KeyCombination) -> Unit,
    initialMode: SendMode? = null,
) {
    val configuringAfterSend = initialMode != null
    var enabledAfterSend by rememberSaveable { mutableStateOf(initialMode != SendMode.SEND_ONLY) }
    var keyWire by rememberSaveable { mutableStateOf(initialCombination.key.wireValue) }
    var modifierMask by rememberSaveable {
        mutableIntStateOf(initialCombination.modifiers.fold(0) { mask, modifier -> mask or (1 shl modifier.ordinal) })
    }
    var categoryName by rememberSaveable { mutableStateOf(initialCombination.key.category.name) }
    val category = KeyCategory.valueOf(categoryName)
    val combination = KeyCombination(
        key = RemoteKey.fromWireValue(keyWire) ?: RemoteKey.RETURN,
        modifiers = KeyModifier.entries.filter { modifierMask and (1 shl it.ordinal) != 0 }.toSet(),
    )
    val canEdit = !isSending && (!configuringAfterSend || enabledAfterSend)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.82f).dp)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                stringResource(if (configuringAfterSend) R.string.after_send_title else R.string.send_key_action),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))
            if (configuringAfterSend) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(false, true).forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = enabledAfterSend == value,
                            onClick = { enabledAfterSend = value },
                            enabled = !isSending,
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text(stringResource(if (value) R.string.after_send_combination else R.string.after_send_none))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            KeyModifierSelector(
                selectedModifiers = combination.modifiers,
                onToggleModifier = { modifierMask = modifierMask xor (1 shl it.ordinal) },
                enabled = canEdit,
            )
            Text(
                stringResource(R.string.main_key),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            PrimaryTabRow(
                selectedTabIndex = category.ordinal,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                KeyCategory.entries.forEach { item ->
                    Tab(
                        selected = item == category,
                        onClick = { categoryName = item.name },
                        enabled = canEdit,
                        text = { Text(stringResource(item.labelRes())) },
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(RemoteKey.entries.filter { it.category == category }, key = { it.wireValue }) { key ->
                    val selected = key == combination.key
                    FilledTonalButton(
                        onClick = { keyWire = key.wireValue },
                        enabled = canEdit,
                        shape = RoundedCornerShape(10.dp),
                        colors = if (selected) {
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                            this.selected = selected
                            if (key == RemoteKey.BACKSPACE) contentDescription = "Backspace"
                        },
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp),
                    ) { Text(key.label) }
                }
            }
            Text(
                text = if (configuringAfterSend && !enabledAfterSend) {
                    stringResource(R.string.after_send_none_hint)
                } else {
                    stringResource(R.string.key_combination_preview, combination.label)
                },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        onConfirm(if (enabledAfterSend) SendMode.SEND_THEN_KEY else SendMode.SEND_ONLY, combination)
                    },
                    enabled = !isSending,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(if (configuringAfterSend) R.string.save_action else R.string.send_key_action))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun KeyCategory.labelRes(): Int = when (this) {
    KeyCategory.COMMON -> R.string.key_category_common
    KeyCategory.LETTERS -> R.string.key_category_letters
    KeyCategory.DIGITS -> R.string.key_category_digits
    KeyCategory.FUNCTION -> R.string.key_category_function
}
