package com.coderred.andclaw.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coderred.andclaw.R
import com.coderred.andclaw.data.OpenRouterModel

@Composable
fun ModelSelectionDialog(
    models: List<OpenRouterModel>,
    selectedModelIds: Set<String>,
    isLoading: Boolean,
    errorMessage: String?,
    onApplySelection: (List<OpenRouterModel>) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    var localSelectedIds by remember(models, selectedModelIds) {
        mutableStateOf(selectedModelIds.toMutableSet())
    }
    val canApplySelection =
        !isLoading &&
            errorMessage == null &&
            models.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_select_model),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (!isLoading) {
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.settings_model_retry),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
        text = {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Text(
                                text = stringResource(R.string.settings_model_loading),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                errorMessage != null -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_model_load_error, errorMessage),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.settings_model_retry))
                        }
                    }
                }

                models.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.settings_model_none_found),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                else -> {
                    val freeModels = models.filter { it.isFree }
                    val paidModels = models.filter { !it.isFree }
                    val showSections = freeModels.isNotEmpty() && paidModels.isNotEmpty()

                    LazyColumn(modifier = Modifier.height(420.dp)) {
                        if (freeModels.isNotEmpty()) {
                            if (showSections) {
                                item {
                                    SectionLabel(stringResource(R.string.settings_model_section_free))
                                    Text(
                                        text = stringResource(R.string.settings_model_free_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                                    )
                                }
                            }
                            items(freeModels) { model ->
                                val checked = localSelectedIds.contains(model.id)
                                ModelItem(
                                    model = model,
                                    checked = checked,
                                    onToggleChecked = {
                                        if (checked) {
                                            localSelectedIds = localSelectedIds.toMutableSet().apply { remove(model.id) }
                                        } else {
                                            localSelectedIds = localSelectedIds.toMutableSet().apply { add(model.id) }
                                        }
                                    },
                                )
                            }
                        }
                        if (paidModels.isNotEmpty()) {
                            if (showSections) {
                                item {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    SectionLabel(stringResource(R.string.settings_model_section_paid))
                                }
                            }
                            items(paidModels) { model ->
                                val checked = localSelectedIds.contains(model.id)
                                ModelItem(
                                    model = model,
                                    checked = checked,
                                    onToggleChecked = {
                                        if (checked) {
                                            localSelectedIds = localSelectedIds.toMutableSet().apply { remove(model.id) }
                                        } else {
                                            localSelectedIds = localSelectedIds.toMutableSet().apply { add(model.id) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = models.filter { localSelectedIds.contains(it.id) }
                    onApplySelection(selected)
                },
                enabled = canApplySelection,
            ) {
                Text(stringResource(R.string.settings_api_key_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_model_close))
            }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun ModelItem(
    model: OpenRouterModel,
    checked: Boolean,
    onToggleChecked: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (checked) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleChecked)
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggleChecked() },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    formatContextLength(model.contextLength)?.let { contextLabel ->
                        Text(
                            text = contextLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (model.pricing.isNotBlank()) {
                        Text(
                            text = model.pricing,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (model.isFree) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

data class DefaultModelDialogOption(
    val provider: String,
    val providerLabel: String,
    val modelId: String,
    val displayModelId: String,
)

@Composable
fun DefaultModelSelectionDialog(
    options: List<DefaultModelDialogOption>,
    selectedProvider: String,
    selectedModelId: String,
    onApplySelection: (DefaultModelDialogOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var localSelection by remember(options, selectedProvider, selectedModelId) {
        mutableStateOf(
            options.firstOrNull { it.provider == selectedProvider && it.modelId == selectedModelId },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = stringResource(R.string.settings_default_model_label),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            if (options.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_default_model_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(options) { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { localSelection = option }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = localSelection == option,
                                onClick = { localSelection = option },
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp),
                            ) {
                                Text(
                                    text = option.providerLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = option.displayModelId,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    localSelection?.let(onApplySelection)
                },
                enabled = localSelection != null,
            ) {
                Text(stringResource(R.string.settings_api_key_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_model_close))
            }
        },
    )
}

fun formatContextLength(contextLength: Int): String? {
    if (contextLength <= 0) return null
    return when {
        contextLength >= 1_000_000 -> "${contextLength / 1_000_000}M tokens"
        contextLength >= 1_000 -> "${contextLength / 1_000}K tokens"
        else -> "$contextLength tokens"
    }
}
